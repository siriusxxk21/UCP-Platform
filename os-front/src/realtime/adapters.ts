import type { RxRealtimeBus } from './bus'
import type { ChannelDescriptor } from './channel-registry'
import type { SystemNotification } from './events'
import type { EventFrame } from './protocol.types'
import type { RecordsChanged } from '@/types/nocode/record-live'

export const SYSTEM_MESSAGE_CHANNEL: ChannelDescriptor = {
  key: 'system.message',
  topic: 'user.notifications'
}

/** 记录变更通知的主题：nocode.records.{应用}.{对象}，两段都是不以 0 开头的十进制数字串。 */
export const RECORDS_TOPIC_PREFIX = 'nocode.records.'
const RECORDS_TOPIC = /^nocode\.records\.([1-9]\d{0,18})\.([1-9]\d{0,18})$/

interface AdapterContext {
  bus: RxRealtimeBus
  setUnreadCount: (count: number) => void
  isSessionCurrent: () => boolean
}

/** 将协议 Event 转换成强类型前端领域事件，并校验 Topic 与运行 ID 的一致性。 */
export class RealtimeAdapterRegistry {
  constructor(private readonly context: AdapterContext) {}

  handle(descriptor: ChannelDescriptor, frame: EventFrame): void {
    if (!this.context.isSessionCurrent()) return
    if (descriptor.topic === SYSTEM_MESSAGE_CHANNEL.topic) {
      this.handleSystemMessage(frame)
    }
    if (descriptor.topic.startsWith(RECORDS_TOPIC_PREFIX)) this.handleRecords(descriptor.topic, frame)
  }

  /** 只转发「哪个对象的哪几条记录变了」；不合格的通知抛错，由频道层丢弃并告警。 */
  private handleRecords(topic: string, frame: EventFrame): void {
    const matched = RECORDS_TOPIC.exec(topic)
    if (!matched) return
    const applicationId = matched[1]
    const objectId = matched[2]
    if (frame.event === 'protocol.subscription.error') {
      // 订阅流在服务端中断：其间的通知可能丢了，按「整个对象都可能变了」处理；空 epoch 使序号判为不接续。
      this.context.bus.emit('nocode.records.changed', {
        applicationId,
        objectId,
        kind: 'object',
        many: false,
        created: [],
        updated: [],
        deleted: [],
        origin: null,
        epoch: '',
        fromSeq: 0,
        seq: 0
      })
      return
    }
    if (frame.event !== 'records.changed') return
    const data = frame.data
    if (!isRecordsChanged(data)) throw new Error('记录变更通知字段不完整')
    if (data.objectId !== objectId) throw new Error('记录变更通知的对象与主题不符')
    this.context.bus.emit('nocode.records.changed', {
      applicationId,
      objectId,
      kind: data.kind,
      many: data.many,
      created: data.created,
      updated: data.updated,
      deleted: data.deleted,
      // 服务端保证这个键一定出现；万一被序列化省掉，按「没有发起人」处理。
      origin: data.origin ?? null,
      epoch: data.epoch,
      fromSeq: data.fromSeq,
      seq: data.seq
    })
  }

  private handleSystemMessage(frame: EventFrame): void {
    if (frame.event === 'notification.unread-count.changed') {
      if (typeof frame.data !== 'number') throw new Error('未读数量不是数字')
      this.context.setUnreadCount(frame.data)
      return
    }
    if (frame.event === 'notification.created') {
      if (!isSystemNotification(frame.data)) throw new Error('系统通知字段不完整')
      this.context.bus.emit('system.notification', {
        ...frame.data,
        msgId: String(frame.data.msgId),
        sourceType: frame.data.sourceType === undefined ? undefined : String(frame.data.sourceType),
        sourceId: frame.data.sourceId === undefined ? undefined : String(frame.data.sourceId)
      })
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isIdList(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(id => typeof id === 'string')
}

function isRecordsChanged(value: unknown): value is Omit<RecordsChanged, 'applicationId'> {
  return (
    isRecord(value) &&
    typeof value.objectId === 'string' &&
    (value.kind === 'ids' || value.kind === 'object') &&
    typeof value.many === 'boolean' &&
    isIdList(value.created) &&
    isIdList(value.updated) &&
    isIdList(value.deleted) &&
    (value.origin == null || typeof value.origin === 'string') &&
    typeof value.epoch === 'string' &&
    Number.isInteger(value.fromSeq) &&
    Number.isInteger(value.seq)
  )
}

function isSystemNotification(value: unknown): value is SystemNotification {
  return isRecord(value) && (typeof value.msgId === 'string' || typeof value.msgId === 'number') && isRecord(value.data)
}
