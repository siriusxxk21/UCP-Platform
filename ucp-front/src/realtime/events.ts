import type { RecordsChanged } from '@/types/nocode/record-live'

/** WebSocket 对外暴露的连接状态。 */
export type ConnectionStatus = 'connecting' | 'connected' | 'recovering' | 'reconnecting' | 'disconnected'

/** 系统通知中的跳转信息。 */
export interface SystemNotificationRoute {
  title?: string
  content?: string
  route?: string
}

/** 系统通知领域对象。 */
export interface SystemNotification {
  msgId: string
  type?: string
  typeName?: string
  priority?: number
  ownerId?: string | number
  ownerName?: string
  time?: string
  sourceType?: string
  sourceId?: string
  data: SystemNotificationRoute
}

/** 通知详情打开请求，使用 kind 避免多个可选字段产生非法组合。 */
export type NotificationDetailRequest =
  { kind: 'message'; messageId: string } | { kind: 'source'; sourceType: string; sourceId: string }

/** WebSocket 领域总线支持的瞬时事件。 */
export interface RealtimeEventMap {
  'system.notification': SystemNotification
  'notification.detail-requested': NotificationDetailRequest
  'nocode.records.changed': RecordsChanged
}

export type RealtimeEventType = keyof RealtimeEventMap

export type RealtimeEnvelope = {
  [K in RealtimeEventType]: { type: K; payload: RealtimeEventMap[K] }
}[RealtimeEventType]
