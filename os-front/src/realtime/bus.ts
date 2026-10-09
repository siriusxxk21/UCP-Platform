import { filter, map, type Observable, Subject } from 'rxjs'
import type { RealtimeEnvelope, RealtimeEventMap, RealtimeEventType } from './events'

/**
 * WebSocket 领域事件总线。
 *
 * 只承载瞬时领域事件；连接状态和未读数量等可查询数据由状态源保存。
 */
export class RxRealtimeBus {
  private eventsSubject = new Subject<RealtimeEnvelope>()

  emit<K extends RealtimeEventType>(type: K, payload: RealtimeEventMap[K]): void {
    this.eventsSubject.next({ type, payload } as RealtimeEnvelope)
  }

  on<K extends RealtimeEventType>(type: K): Observable<RealtimeEventMap[K]> {
    return this.eventsSubject.pipe(
      filter((event): event is Extract<RealtimeEnvelope, { type: K }> => event.type === type),
      map(event => event.payload)
    ) as Observable<RealtimeEventMap[K]>
  }

  /** 完成旧会话订阅并创建隔离的新事件流。 */
  reset(): void {
    this.eventsSubject.complete()
    this.eventsSubject = new Subject<RealtimeEnvelope>()
  }

  destroy(): void {
    this.eventsSubject.complete()
  }
}

export const realtimeBus = new RxRealtimeBus()
