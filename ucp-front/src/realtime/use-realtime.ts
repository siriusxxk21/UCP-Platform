import { onScopeDispose } from 'vue'
import type { Subscription } from 'rxjs'
import { realtimeBus } from './bus'
import type { ChannelDescriptor } from './channel-registry'
import type { RealtimeEventMap, RealtimeEventType } from './events'
import { realtimeRuntime } from './runtime'

/** 在当前 Vue effect scope 内订阅强类型实时事件。 */
export function useRealtimeEvent<K extends RealtimeEventType>(
  type: K,
  handler: (payload: RealtimeEventMap[K]) => void
): Subscription {
  const subscription = realtimeBus.on(type).subscribe({
    next: handler,
    error: error => console.error(`[RealtimeBus] ${String(type)}`, error)
  })
  onScopeDispose(() => subscription.unsubscribe())
  return subscription
}

/** 在当前 Vue effect scope 内持有服务端频道引用。 */
export function useRealtimeChannel(descriptor: ChannelDescriptor): () => void {
  const release = realtimeRuntime.acquireChannel(descriptor)
  onScopeDispose(release)
  return release
}
