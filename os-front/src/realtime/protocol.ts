import { BehaviorSubject, type Observable, Subject, type Subscription } from 'rxjs'
import type {
  CommandFrame,
  EventFrame,
  ProtocolCommand,
  ProtocolError,
  ProtocolReply,
  ReplyFrame
} from './protocol.types'
import type { WebSocketTransport } from './transport'

const COMMAND_TIMEOUT_MS = 10_000

interface PendingCommand {
  resolve: (reply: ProtocolReply) => void
  reject: (error: Error) => void
  timer: ReturnType<typeof setTimeout>
}

/** 带结构化错误信息的 WebSocket 命令异常。 */
export class ProtocolCommandError extends Error {
  constructor(readonly detail: ProtocolError) {
    super(detail.message)
    this.name = 'ProtocolCommandError'
  }
}

/**
 * Command / Reply / Event 协议客户端。
 *
 * 负责命令 ID、Promise 关联、连接握手和业务 Event 输出，不感知具体业务 Topic。
 */
export class ProtocolClient {
  private readonly eventsSubject = new Subject<EventFrame>()
  readonly events$: Observable<EventFrame> = this.eventsSubject.asObservable()
  private readonly readySubject = new BehaviorSubject(false)
  readonly ready$: Observable<boolean> = this.readySubject.asObservable()
  private readonly subscriptions: Subscription[]
  private readonly pendingCommands = new Map<number, PendingCommand>()
  private nextCommandId = 1

  constructor(private readonly transport: WebSocketTransport) {
    this.subscriptions = [
      transport.frames$.subscribe(frame => this.handleFrame(frame)),
      transport.status$.subscribe(status => {
        if (status === 'connected') void this.establishProtocolSession()
        else this.resetConnection(new Error('WebSocket connection is not available'))
      })
    ]
  }

  get ready(): boolean {
    return this.readySubject.value
  }

  sendCommand(command: ProtocolCommand): Promise<ProtocolReply> {
    const id = this.nextCommandId++
    const frame: CommandFrame = { frame: 'command', id, command }
    return this.sendPreparedCommand(frame)
  }

  sendCommands(commands: ProtocolCommand[]): Promise<ProtocolReply[]> {
    if (!commands.length) return Promise.resolve([])
    const prepared = commands.map(command => {
      const id = this.nextCommandId++
      return { frame: { frame: 'command', id, command } as CommandFrame, pending: this.createPending(id) }
    })
    if (!this.transport.send(prepared.map(item => item.frame))) {
      const error = new Error('WebSocket command batch could not be sent')
      prepared.forEach(item => this.rejectPending(item.frame.id as number, error))
    }
    return Promise.all(prepared.map(item => item.pending))
  }

  reset(): void {
    this.resetConnection(new Error('WebSocket protocol session was reset'))
  }

  destroy(): void {
    this.reset()
    this.subscriptions.forEach(subscription => subscription.unsubscribe())
    this.eventsSubject.complete()
    this.readySubject.complete()
  }

  private async establishProtocolSession(): Promise<void> {
    this.readySubject.next(false)
    try {
      await this.sendCommand({ type: 'connect' })
      if (this.transport.status === 'connected') this.readySubject.next(true)
    } catch (error) {
      console.warn('[RealtimeProtocol] connect 命令失败', error instanceof Error ? error.message : String(error))
    }
  }

  private sendPreparedCommand(frame: CommandFrame): Promise<ProtocolReply> {
    const pending = this.createPending(frame.id as number)
    if (!this.transport.send(frame)) {
      this.rejectPending(frame.id as number, new Error('WebSocket command could not be sent'))
    }
    return pending
  }

  private createPending(id: number): Promise<ProtocolReply> {
    return new Promise<ProtocolReply>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingCommands.delete(id)
        reject(new Error(`WebSocket command ${id} timed out`))
      }, COMMAND_TIMEOUT_MS)
      this.pendingCommands.set(id, { resolve, reject, timer })
    })
  }

  private handleFrame(frame: ReplyFrame | EventFrame): void {
    if (frame.frame === 'event') {
      this.eventsSubject.next(frame)
      return
    }
    const id = Number(frame.id)
    const pending = this.pendingCommands.get(id)
    if (!pending) return
    clearTimeout(pending.timer)
    this.pendingCommands.delete(id)
    if (frame.ok && frame.reply) pending.resolve(frame.reply)
    else pending.reject(new ProtocolCommandError(frame.error || { code: 'INTERNAL_ERROR', message: 'Unknown error' }))
  }

  private rejectPending(id: number, error: Error): void {
    const pending = this.pendingCommands.get(id)
    if (!pending) return
    clearTimeout(pending.timer)
    this.pendingCommands.delete(id)
    pending.reject(error)
  }

  private resetConnection(error: Error): void {
    if (this.readySubject.value) this.readySubject.next(false)
    Array.from(this.pendingCommands.keys()).forEach(id => this.rejectPending(id, error))
  }
}
