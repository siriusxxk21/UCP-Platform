import type { Subscription } from 'rxjs'
import { buildWebSocketUrl } from '@/utils/websocketUrl'
import { useRealtimeStore } from '@/stores/realtime'
import { RealtimeAdapterRegistry, SYSTEM_MESSAGE_CHANNEL } from './adapters'
import { realtimeBus } from './bus'
import { type ChannelDescriptor, ChannelRegistry } from './channel-registry'
import { ProtocolClient } from './protocol'
import { WebSocketTransport, type WebSocketTransportOptions } from './transport'

export interface RealtimeRuntimeOptions {
  getToken: () => string | undefined
  buildUrl?: (token: string) => string
  transport?: Omit<WebSocketTransportOptions, 'getToken' | 'buildUrl'>
}

/** 协调连接、频道、Adapter、总线和用户会话的应用级运行时。 */
export class RealtimeRuntime {
  private readonly transport: WebSocketTransport
  private readonly protocol: ProtocolClient
  private readonly channelRegistry: ChannelRegistry
  private readonly statusSubscription: Subscription
  private readonly registryReadySubscription: Subscription
  private sessionEpoch = 0
  private activeSessionEpoch = 0
  private started = false

  constructor(options: RealtimeRuntimeOptions) {
    this.transport = new WebSocketTransport({
      ...options.transport,
      getToken: options.getToken,
      buildUrl: options.buildUrl || buildWebSocketUrl
    })
    this.protocol = new ProtocolClient(this.transport)
    const adapters = new RealtimeAdapterRegistry({
      bus: realtimeBus,
      setUnreadCount: count => useRealtimeStore().setUnreadCount(count),
      isSessionCurrent: () => this.started && this.activeSessionEpoch === this.sessionEpoch
    })
    this.channelRegistry = new ChannelRegistry(this.protocol, (descriptor, frame) => {
      adapters.handle(descriptor, frame)
    })
    this.statusSubscription = this.transport.status$.subscribe(status => {
      useRealtimeStore().setConnectionStatus(status === 'connected' && !this.protocol.ready ? 'recovering' : status)
    })
    this.registryReadySubscription = this.channelRegistry.ready$.subscribe(ready => {
      if (ready) useRealtimeStore().setConnectionStatus('connected')
    })
  }

  start(): void {
    if (this.started) return
    this.started = true
    this.activeSessionEpoch = this.sessionEpoch
    this.channelRegistry.acquire(SYSTEM_MESSAGE_CHANNEL)
    this.transport.start()
  }

  acquireChannel(descriptor: ChannelDescriptor): () => void {
    return this.channelRegistry.acquire(descriptor)
  }

  /** 某个 Topic 的订阅被服务端确认时回调；返回注销函数。 */
  onChannelSubscribed(handler: (topic: string) => void): () => void {
    const subscription = this.channelRegistry.subscribed$.subscribe(handler)
    return () => subscription.unsubscribe()
  }

  resetSession(): void {
    this.sessionEpoch++
    this.started = false
    this.transport.stop()
    this.protocol.reset()
    this.channelRegistry.clear()
    realtimeBus.reset()
    useRealtimeStore().reset()
  }

  destroy(): void {
    this.resetSession()
    this.channelRegistry.destroy()
    this.statusSubscription.unsubscribe()
    this.registryReadySubscription.unsubscribe()
    this.protocol.destroy()
    this.transport.destroy()
    realtimeBus.destroy()
  }
}

let runtime: RealtimeRuntime | undefined

export function initializeRealtimeRuntime(options: RealtimeRuntimeOptions): RealtimeRuntime {
  if (!runtime) runtime = new RealtimeRuntime(options)
  return runtime
}

function requireRuntime(): RealtimeRuntime {
  if (!runtime) throw new Error('RealtimeRuntime 尚未初始化')
  return runtime
}

/** 避免业务模块持有可替换运行时实例的稳定入口。 */
export const realtimeRuntime = {
  start: (): void => requireRuntime().start(),
  acquireChannel: (descriptor: ChannelDescriptor): (() => void) => requireRuntime().acquireChannel(descriptor),
  onChannelSubscribed: (handler: (topic: string) => void): (() => void) =>
    requireRuntime().onChannelSubscribed(handler),
  resetSession: (): void => runtime?.resetSession(),
  destroy: (): void => runtime?.destroy()
}
