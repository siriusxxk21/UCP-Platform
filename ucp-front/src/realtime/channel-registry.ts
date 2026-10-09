import { BehaviorSubject, type Observable, Subject, type Subscription } from 'rxjs'
import type { ProtocolClient } from './protocol'
import type { EventFrame } from './protocol.types'

/** 一个可复用的服务端 Topic 订阅。 */
export interface ChannelDescriptor {
  key: string
  topic: string
  offset?: string
  data?: unknown
}

interface ChannelEntry {
  descriptor: ChannelDescriptor
  lastOffset?: string
  refCount: number
  active: boolean
  retryAttempt: number
  retryTimer?: ReturnType<typeof setTimeout>
}

export type ChannelFrameHandler = (descriptor: ChannelDescriptor, frame: EventFrame) => void

/**
 * 管理服务端 Topic 订阅、引用计数、Offset 和事件幂等。
 *
 * 页面只持有 release 函数；最后一个引用释放时才向服务端发送 unsubscribe。
 */
export class ChannelRegistry {
  private static readonly RETRY_DELAYS = [1_000, 2_000, 5_000, 10_000, 30_000]
  private readonly entries = new Map<string, ChannelEntry>()
  private readonly readySubject = new BehaviorSubject(false)
  readonly ready$: Observable<boolean> = this.readySubject.asObservable()
  private readonly subscribedSubject = new Subject<string>()
  /** 某个 Topic 的订阅刚被服务端确认（首次订阅、被拒后重试成功、断线重连后恢复都算），值是 Topic。 */
  readonly subscribed$: Observable<string> = this.subscribedSubject.asObservable()
  private readonly subscriptions: Subscription[]
  private readonly processedEventIds = new Set<string>()
  private readonly processedEventQueue: string[] = []

  constructor(
    private readonly protocol: ProtocolClient,
    private readonly frameHandler: ChannelFrameHandler
  ) {
    this.subscriptions = [
      protocol.events$.subscribe(frame => this.handleEvent(frame)),
      protocol.ready$.subscribe(ready => {
        if (ready) void this.restoreSubscriptions()
        else {
          this.readySubject.next(false)
          this.markSubscriptionsInactive()
        }
      })
    ]
  }

  acquire(descriptor: ChannelDescriptor): () => void {
    const existing = this.entries.get(descriptor.key)
    if (existing) {
      this.assertSameDescriptor(existing.descriptor, descriptor)
      existing.refCount++
      if (!existing.active && this.protocol.ready) this.subscribeEntry(existing)
    } else {
      const entry: ChannelEntry = {
        descriptor,
        lastOffset: descriptor.offset,
        refCount: 1,
        active: false,
        retryAttempt: 0
      }
      this.entries.set(descriptor.key, entry)
      if (this.protocol.ready) this.subscribeEntry(entry)
    }

    let released = false
    return () => {
      if (released) return
      released = true
      this.release(descriptor.key)
    }
  }

  async restoreSubscriptions(): Promise<void> {
    this.readySubject.next(false)
    const tasks = Array.from(this.entries.values())
      .filter(entry => entry.refCount > 0 && !entry.active)
      .map(entry => this.subscribeEntry(entry))
    await Promise.all(tasks)
    if (this.protocol.ready) this.readySubject.next(true)
  }

  clear(): void {
    this.entries.forEach(entry => this.cancelRetry(entry))
    this.entries.clear()
    this.readySubject.next(false)
    this.processedEventIds.clear()
    this.processedEventQueue.length = 0
  }

  destroy(): void {
    this.clear()
    this.subscriptions.forEach(subscription => subscription.unsubscribe())
    this.readySubject.complete()
    this.subscribedSubject.complete()
  }

  private handleEvent(frame: EventFrame): void {
    const entry = Array.from(this.entries.values()).find(value => value.descriptor.topic === frame.topic)
    if (!entry || this.processedEventIds.has(frame.eventId)) return

    try {
      this.frameHandler(entry.descriptor, frame)
      this.rememberProcessedEvent(frame.eventId)
      if (frame.offset) entry.lastOffset = frame.offset
    } catch (error) {
      console.warn('[RealtimeChannel] 丢弃非法业务事件', {
        topic: frame.topic,
        event: frame.event,
        error: error instanceof Error ? error.message : String(error)
      })
    }
  }

  private async subscribeEntry(entry: ChannelEntry): Promise<void> {
    if (entry.active || entry.refCount <= 0 || !this.protocol.ready) return
    this.cancelRetry(entry)
    entry.active = true
    try {
      const reply = await this.protocol.sendCommand({
        type: 'subscribe',
        topic: entry.descriptor.topic,
        offset: entry.lastOffset,
        data: entry.descriptor.data
      })
      if (entry.refCount <= 0) return
      if (entry.lastOffset && reply.recovered === false) {
        console.warn('[RealtimeChannel] Topic 无法按 Offset 恢复，需要 REST 快照兜底', {
          topic: entry.descriptor.topic,
          offset: entry.lastOffset
        })
      }
      entry.retryAttempt = 0
      this.subscribedSubject.next(entry.descriptor.topic)
    } catch (error) {
      entry.active = false
      console.warn('[RealtimeChannel] 服务端拒绝 Topic 订阅', {
        topic: entry.descriptor.topic,
        error: error instanceof Error ? error.message : String(error)
      })
      this.scheduleRetry(entry)
    }
  }

  private release(key: string): void {
    const entry = this.entries.get(key)
    if (!entry) return
    entry.refCount--
    if (entry.refCount > 0) return

    this.cancelRetry(entry)
    this.entries.delete(key)
    if (entry.active && this.protocol.ready) {
      void this.protocol.sendCommand({ type: 'unsubscribe', topic: entry.descriptor.topic }).catch(error => {
        console.warn('[RealtimeChannel] Topic 取消订阅失败', {
          topic: entry.descriptor.topic,
          error: error instanceof Error ? error.message : String(error)
        })
      })
    }
  }

  private markSubscriptionsInactive(): void {
    this.entries.forEach(entry => {
      this.cancelRetry(entry)
      entry.active = false
    })
  }

  private scheduleRetry(entry: ChannelEntry): void {
    if (entry.retryTimer || entry.refCount <= 0 || !this.protocol.ready) return
    const index = Math.min(entry.retryAttempt, ChannelRegistry.RETRY_DELAYS.length - 1)
    const delay = ChannelRegistry.RETRY_DELAYS[index]
    entry.retryAttempt++
    entry.retryTimer = setTimeout(() => {
      entry.retryTimer = undefined
      if (entry.refCount > 0 && !entry.active && this.protocol.ready) {
        void this.subscribeEntry(entry)
      }
    }, delay)
  }

  private cancelRetry(entry: ChannelEntry): void {
    if (!entry.retryTimer) return
    clearTimeout(entry.retryTimer)
    entry.retryTimer = undefined
  }

  private rememberProcessedEvent(eventId: string): void {
    this.processedEventIds.add(eventId)
    this.processedEventQueue.push(eventId)
    if (this.processedEventQueue.length <= 5_000) return
    const expired = this.processedEventQueue.shift()
    if (expired) this.processedEventIds.delete(expired)
  }

  private assertSameDescriptor(current: ChannelDescriptor, requested: ChannelDescriptor): void {
    if (
      current.topic !== requested.topic ||
      current.offset !== requested.offset ||
      JSON.stringify(current.data) !== JSON.stringify(requested.data)
    ) {
      throw new Error(`频道键 ${requested.key} 对应了不同的 Topic 订阅参数`)
    }
  }
}
