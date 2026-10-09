import { BehaviorSubject, type Observable, Subject } from 'rxjs'
import type { ConnectionStatus } from './events'
import type { IncomingProtocolFrame, OutgoingProtocolFrame } from './protocol.types'

export interface WebSocketLike {
  readonly readyState: number
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  send(data: string): void
  close(code?: number, reason?: string): void
}

export interface WebSocketTransportOptions {
  getToken: () => string | undefined
  buildUrl: (token: string) => string
  socketFactory?: (url: string) => WebSocketLike
  heartbeatIntervalMs?: number
  maxReconnectDelayMs?: number
  random?: () => number
}

const DEFAULT_HEARTBEAT_INTERVAL = 30_000
const DEFAULT_MAX_RECONNECT_DELAY = 30_000
const SOCKET_OPEN = 1

/**
 * WebSocket 物理连接管理器。
 *
 * 通过连接代次隔离旧 Socket 回调，并保证重连、心跳和主动停止互不干扰。
 */
export class WebSocketTransport {
  private readonly framesSubject = new Subject<IncomingProtocolFrame>()
  readonly frames$: Observable<IncomingProtocolFrame> = this.framesSubject.asObservable()
  private readonly statusSubject = new BehaviorSubject<ConnectionStatus>('disconnected')
  readonly status$: Observable<ConnectionStatus> = this.statusSubject.asObservable()
  private socket?: WebSocketLike
  private reconnectTimer?: ReturnType<typeof setTimeout>
  private heartbeatTimer?: ReturnType<typeof setInterval>
  private generation = 0
  private retryCount = 0
  private started = false

  constructor(private readonly options: WebSocketTransportOptions) {}

  get status(): ConnectionStatus {
    return this.statusSubject.value
  }

  start(): void {
    if (this.started) return
    this.started = true
    this.retryCount = 0
    this.openConnection(false)
  }

  stop(): void {
    this.started = false
    this.generation++
    this.clearReconnectTimer()
    this.stopHeartbeat()
    const socket = this.socket
    this.socket = undefined
    if (socket) {
      socket.onopen = null
      socket.onmessage = null
      socket.onerror = null
      socket.onclose = null
      socket.close(1000, 'Client stop')
    }
    this.setStatus('disconnected')
  }

  send(frame: OutgoingProtocolFrame | string): boolean {
    if (!this.socket || this.status !== 'connected' || this.socket.readyState !== SOCKET_OPEN) return false
    this.socket.send(typeof frame === 'string' ? frame : JSON.stringify(frame))
    return true
  }

  destroy(): void {
    this.stop()
    this.framesSubject.complete()
    this.statusSubject.complete()
  }

  private openConnection(reconnecting: boolean): void {
    if (!this.started) return
    const token = this.options.getToken()
    if (!token) {
      this.started = false
      this.setStatus('disconnected')
      return
    }

    this.clearReconnectTimer()
    const currentGeneration = ++this.generation
    this.setStatus(reconnecting ? 'reconnecting' : 'connecting')

    let socket: WebSocketLike
    try {
      const factory = this.options.socketFactory || (url => new WebSocket(url))
      socket = factory(this.options.buildUrl(token))
    } catch (error) {
      this.logConnectionError('创建连接失败', error)
      this.scheduleReconnect(currentGeneration)
      return
    }
    this.socket = socket

    socket.onopen = () => {
      if (!this.isCurrent(currentGeneration, socket)) {
        socket.close(1000, 'Stale connection')
        return
      }
      this.retryCount = 0
      this.setStatus('connected')
      this.startHeartbeat()
    }
    socket.onmessage = event => {
      if (!this.isCurrent(currentGeneration, socket)) return
      this.handleRawMessage(event.data)
    }
    socket.onerror = event => {
      if (!this.isCurrent(currentGeneration, socket)) return
      this.logConnectionError('连接异常', event)
      this.invalidateAndReconnect(currentGeneration, socket)
    }
    socket.onclose = event => {
      if (!this.isCurrent(currentGeneration, socket)) return
      this.socket = undefined
      this.stopHeartbeat()
      if (isAuthenticationClose(event.code)) {
        this.started = false
        this.setStatus('disconnected')
        return
      }
      this.scheduleReconnect(currentGeneration)
    }
  }

  private invalidateAndReconnect(currentGeneration: number, socket: WebSocketLike): void {
    if (!this.isCurrent(currentGeneration, socket)) return
    this.generation++
    this.socket = undefined
    this.stopHeartbeat()
    socket.onopen = null
    socket.onmessage = null
    socket.onerror = null
    socket.onclose = null
    socket.close()
    this.scheduleReconnect(this.generation)
  }

  private scheduleReconnect(currentGeneration: number): void {
    if (!this.started || this.reconnectTimer) {
      if (!this.started) this.setStatus('disconnected')
      return
    }
    if (currentGeneration !== this.generation) return

    this.setStatus('reconnecting')
    const baseDelay = Math.min(
      this.options.maxReconnectDelayMs || DEFAULT_MAX_RECONNECT_DELAY,
      1000 * 2 ** this.retryCount
    )
    const jitter = Math.floor((this.options.random?.() ?? Math.random()) * 500)
    this.retryCount++
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined
      this.openConnection(true)
    }, baseDelay + jitter)
  }

  private handleRawMessage(data: unknown): void {
    if (data === 'pong') return
    try {
      const value = typeof data === 'string' ? JSON.parse(data) : data
      const frames = Array.isArray(value) ? value : [value]
      frames.forEach(frame => {
        if (this.isProtocolFrame(frame)) this.framesSubject.next(frame)
        else this.logInvalidFrame(frame, '外层协议帧不合法')
      })
    } catch (error) {
      this.logInvalidFrame(data, error)
    }
  }

  private isProtocolFrame(value: unknown): value is IncomingProtocolFrame {
    if (typeof value !== 'object' || value === null) return false
    const frame = Reflect.get(value, 'frame')
    if (frame === 'reply') return Reflect.has(value, 'id') && typeof Reflect.get(value, 'ok') === 'boolean'
    return (
      frame === 'event' &&
      typeof Reflect.get(value, 'eventId') === 'string' &&
      typeof Reflect.get(value, 'topic') === 'string' &&
      typeof Reflect.get(value, 'event') === 'string'
    )
  }

  private startHeartbeat(): void {
    this.stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      this.send('ping')
    }, this.options.heartbeatIntervalMs || DEFAULT_HEARTBEAT_INTERVAL)
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    this.heartbeatTimer = undefined
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = undefined
  }

  private isCurrent(currentGeneration: number, socket: WebSocketLike): boolean {
    return this.started && currentGeneration === this.generation && socket === this.socket
  }

  private setStatus(status: ConnectionStatus): void {
    if (this.statusSubject.value !== status) this.statusSubject.next(status)
  }

  private logInvalidFrame(value: unknown, error: unknown): void {
    console.warn('[RealtimeTransport] 丢弃非法消息', {
      error: error instanceof Error ? error.message : String(error),
      summary: createValueSummary(value)
    })
  }

  private logConnectionError(message: string, error: unknown): void {
    console.warn(`[RealtimeTransport] ${message}`, error instanceof Error ? error.message : String(error))
  }
}

/** 生成不包含完整敏感载荷的日志摘要。 */
export function createValueSummary(value: unknown): string {
  try {
    const text = typeof value === 'string' ? value : JSON.stringify(value)
    return (text || String(value)).slice(0, 200)
  } catch {
    return String(value).slice(0, 200)
  }
}

function isAuthenticationClose(code: number): boolean {
  return code === 1008 || code === 4001 || code === 4401
}
