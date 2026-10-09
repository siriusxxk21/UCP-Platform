import { describe, expect, it, vi } from 'vitest'
import { RealtimeAdapterRegistry, SYSTEM_MESSAGE_CHANNEL } from '@/realtime/adapters'
import { RxRealtimeBus } from '@/realtime/bus'
import type { EventFrame } from '@/realtime/protocol.types'
import type { RecordsChanged } from '@/types/nocode/record-live'

const topic = 'nocode.records.41.3057'
const channel = { key: 'nocode.records:41:3057', topic }
/** 后端一路交付的真实帧（laneR-realtime/backend/frame-samples.md），只把主题换成本用例的。 */
const sample = {
  objectId: '3057',
  kind: 'ids',
  many: false,
  created: ['9001'],
  updated: ['88', '89'],
  deleted: [],
  origin: '0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44',
  epoch: 'pjo4rb3j',
  fromSeq: 1,
  seq: 2
}
function frame(data: unknown, event = 'records.changed', extra: Record<string, unknown> = {}): EventFrame {
  return { frame: 'event', eventId: 'e-' + Math.random(), topic, event, timestamp: 1, replay: false, data, ...extra }
}
function setup(current = true) {
  const bus = new RxRealtimeBus()
  const received: RecordsChanged[] = [],
    notifications: unknown[] = []
  bus.on('nocode.records.changed').subscribe(event => received.push(event))
  bus.on('system.notification').subscribe(event => notifications.push(event))
  const setUnreadCount = vi.fn()
  const adapters = new RealtimeAdapterRegistry({ bus, setUnreadCount, isSessionCurrent: () => current })
  return { adapters, received, notifications, setUnreadCount }
}

describe('记录变更通知的适配', () => {
  it('D1 合法的 records.changed 变成总线事件，应用和对象取自主题，其余字段原样', () => {
    const { adapters, received } = setup()
    adapters.handle(channel, frame(sample))
    expect(received).toEqual([{ applicationId: '41', ...sample }])
  })

  it('D1 没有发起人时 origin 是 null；外层 offset 是 null 或不出现都接受', () => {
    const { adapters, received } = setup()
    const anonymous = { ...sample, kind: 'object', many: true, created: [], updated: [], origin: null }
    adapters.handle(channel, frame(anonymous, 'records.changed', { offset: null }))
    adapters.handle(channel, frame(anonymous))
    expect(received).toHaveLength(2)
    expect(received[0]).toEqual({ applicationId: '41', ...anonymous })
    expect(received[0].origin).toBeNull()
  })

  it('D2 通知里的对象与主题不符：抛错，总线不发', () => {
    const { adapters, received } = setup()
    expect(() => adapters.handle(channel, frame({ ...sample, objectId: '3058' }))).toThrow('对象与主题不符')
    expect(received).toEqual([])
  })

  it.each([
    ['created 不是字符串数组', { created: [9001] }],
    ['updated 不是数组', { updated: '88' }],
    ['deleted 缺失', { deleted: undefined }],
    ['kind 不是两值之一', { kind: 'rows' }],
    ['many 不是布尔', { many: 'false' }],
    ['seq 不是整数', { seq: 2.5 }],
    ['fromSeq 是字符串', { fromSeq: '1' }],
    ['epoch 缺失', { epoch: undefined }],
    ['origin 是数字', { origin: 7 }],
    ['objectId 缺失', { objectId: undefined }]
  ])('D2 %s：抛错，总线不发', (_name, patch) => {
    const { adapters, received } = setup()
    expect(() => adapters.handle(channel, frame({ ...sample, ...patch }))).toThrow('记录变更通知字段不完整')
    expect(received).toEqual([])
  })

  it('D2 data 不是对象：抛错', () => {
    const { adapters, received } = setup()
    expect(() => adapters.handle(channel, frame(null))).toThrow('记录变更通知字段不完整')
    expect(() => adapters.handle(channel, frame('3057'))).toThrow('记录变更通知字段不完整')
    expect(received).toEqual([])
  })

  it('D3 订阅流出错：发一条「整体刷新」的等效事件，序号判为不接续', () => {
    const { adapters, received } = setup()
    adapters.handle(
      channel,
      frame({ code: 'INTERNAL_ERROR', message: 'Subscription stream terminated' }, 'protocol.subscription.error')
    )
    expect(received).toHaveLength(1)
    expect(received[0]).toMatchObject({
      applicationId: '41',
      objectId: '3057',
      kind: 'object',
      many: false,
      created: [],
      updated: [],
      deleted: [],
      origin: null,
      epoch: ''
    })
  })

  it('同主题下不认识的事件不处理', () => {
    const { adapters, received } = setup()
    adapters.handle(channel, frame(sample, 'records.other'))
    expect(received).toEqual([])
  })

  it('会话已经换了：不处理', () => {
    const { adapters, received } = setup(false)
    adapters.handle(channel, frame(sample))
    expect(received).toEqual([])
  })

  it('D4 系统消息的两种事件照旧', () => {
    const { adapters, received, notifications, setUnreadCount } = setup()
    const system = (event: string, data: unknown): EventFrame => ({
      frame: 'event',
      eventId: 'n-' + event,
      topic: SYSTEM_MESSAGE_CHANNEL.topic,
      event,
      timestamp: 1,
      replay: false,
      data
    })
    adapters.handle(SYSTEM_MESSAGE_CHANNEL, system('notification.unread-count.changed', 3))
    expect(setUnreadCount).toHaveBeenCalledWith(3)
    adapters.handle(SYSTEM_MESSAGE_CHANNEL, system('notification.created', { msgId: 7, sourceId: 9, data: {} }))
    expect(notifications).toEqual([{ msgId: '7', sourceType: undefined, sourceId: '9', data: {} }])
    expect(() => adapters.handle(SYSTEM_MESSAGE_CHANNEL, system('notification.unread-count.changed', '3'))).toThrow(
      '未读数量不是数字'
    )
    expect(() => adapters.handle(SYSTEM_MESSAGE_CHANNEL, system('notification.created', { data: {} }))).toThrow(
      '系统通知字段不完整'
    )
    // 系统消息主题上来的 records.changed 不会被当成记录通知
    adapters.handle(SYSTEM_MESSAGE_CHANNEL, system('records.changed', sample))
    expect(received).toEqual([])
  })
})
