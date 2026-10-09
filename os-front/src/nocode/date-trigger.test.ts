import { describe, expect, it } from 'vitest'
import type { PublishedObject } from '@/types/nocode/application'
import type { AutomationConfig } from '@/types/nocode/automation'
import {
  automationDateFields,
  automationKinds,
  automationTargetFields,
  dateTriggerSummary,
  dateTriggerTargets,
  defaultDateAutomation,
  newAutomationAssignment,
  offsetLabel,
  validateAutomation
} from './automation'
import { changeOperator, dateTriggerSourceLabel, linkageSourceLabel } from './record-history'

// 房间场景（派单书）：入住记录引用房间；按入住日 / 退房日改房间状态，或按日期改入住记录本身。
const objects = {
  stays: {
    objectId: 'stays',
    versionNo: 2,
    checksum: 's2',
    definition: {
      objectId: 'stays',
      objectName: '入住记录',
      fields: [
        { id: 'name', type: 'TEXT', name: '名称' },
        { id: 'checkin', type: 'DATE', name: '入住日' },
        { id: 'checkout', type: 'DATE', name: '退房日' },
        { id: 'arrived', type: 'DATETIME', name: '到店时刻' },
        { id: 'nights', type: 'INTEGER', name: '晚数' },
        { id: 'old', type: 'DATE', name: '旧日期' },
        { id: 'room', type: 'INTEGER', name: '房间' },
        { id: 'state', type: 'TEXT', name: '入住状态' }
      ],
      relations: [{ id: 'stayRoom', name: '入住房间', fieldId: 'room', targetObjectId: 'rooms', kind: 'REFERENCE' }],
      fieldOptions: { old: { state: 'INACTIVE' }, room: { generated: true } },
      details: []
    }
  },
  rooms: {
    objectId: 'rooms',
    versionNo: 1,
    checksum: 'r1',
    definition: {
      objectId: 'rooms',
      objectName: '房间',
      fields: [
        { id: 'name', type: 'TEXT', name: '房号' },
        { id: 'status', type: 'SELECT', name: '房间状态' }
      ],
      relations: [],
      fieldOptions: {
        status: {
          options: [
            { code: 'dirty', label: '空房待清扫' },
            { code: 'clean', label: '空房已清扫' },
            { code: 'occupied', label: '入住中' },
            { code: 'occupied_dirty', label: '入住待清扫' }
          ]
        }
      },
      details: []
    }
  }
} as unknown as Record<string, PublishedObject>
const stays = objects.stays as PublishedObject
const rooms = objects.rooms as PublishedObject

const checkoutRule = (): AutomationConfig => ({
  ...defaultDateAutomation('stays'),
  targetObjectId: 'rooms',
  binding: { relationId: 'stayRoom', direction: 'OUTGOING' },
  dateFieldId: 'checkout',
  assignments: [{ ...newAutomationAssignment('DATE'), fieldId: 'status', value: 'dirty' }]
})

describe('按日期自动执行的配置', () => {
  it('默认更新本条记录、日期当天执行，不带触发事件', () => {
    expect(defaultDateAutomation('stays')).toMatchObject({
      mode: 'DATE',
      targetObjectId: 'stays',
      binding: { relationId: null, direction: 'SELF' },
      events: [],
      dateFieldId: null,
      offsetDays: 0
    })
    expect(newAutomationAssignment('DATE').kind).toBe('VALUE')
  })

  it('日期字段只列启用的日期与日期时间字段', () => {
    expect(automationDateFields(stays.definition).map(f => f.id)).toEqual(['checkin', 'checkout', 'arrived'])
  })

  it('要更新的记录：本条记录在前，其后是现有关系；只读对象不能更新本条', () => {
    expect(dateTriggerTargets('stays', objects).map(t => [t.value, t.targetObjectId])).toEqual([
      ['SELF:', 'stays'],
      ['OUTGOING:stayRoom', 'rooms']
    ])
    const readOnly = {
      ...objects,
      stays: { ...stays, definition: { ...stays.definition, readOnly: true } }
    } as unknown as Record<string, PublishedObject>
    expect(dateTriggerTargets('stays', readOnly).map(t => t.value)).toEqual(['OUTGOING:stayRoom'])
  })

  it('取值方式与事件赋值相同：固定值或来源字段', () => {
    const config = checkoutRule()
    const status = automationTargetFields(config, objects).find(f => f.id === 'status')
    expect(automationKinds(config, status, rooms.definition).map(k => k.value)).toEqual(['VALUE', 'FIELD'])
  })

  it('本条记录作为目标时，目标字段取来源对象自己的字段', () => {
    const self: AutomationConfig = { ...defaultDateAutomation('stays'), dateFieldId: 'checkin' }
    expect(automationTargetFields(self, objects).map(f => f.id)).toContain('state')
    self.assignments = [{ ...newAutomationAssignment('DATE'), fieldId: 'state', value: '入住中' }]
    expect(() => validateAutomation(self, objects)).not.toThrow()
  })

  it('校验：通过后清空触发事件、补齐偏移天数', () => {
    const config = { ...checkoutRule(), events: ['CREATE' as const], offsetDays: undefined }
    validateAutomation(config, objects)
    expect(config.events).toEqual([])
    expect(config.offsetDays).toBe(0)
  })

  it('校验：缺日期字段、选了非日期字段、偏移超出一年、目标不对应关系都拦下', () => {
    expect(() => validateAutomation({ ...checkoutRule(), dateFieldId: null }, objects)).toThrow('日期字段')
    expect(() => validateAutomation({ ...checkoutRule(), dateFieldId: 'nights' }, objects)).toThrow('日期字段')
    expect(() => validateAutomation({ ...checkoutRule(), dateFieldId: 'old' }, objects)).toThrow('日期字段')
    expect(() => validateAutomation({ ...checkoutRule(), offsetDays: 366 }, objects)).toThrow('365')
    expect(() => validateAutomation({ ...checkoutRule(), offsetDays: 1.5 }, objects)).toThrow('365')
    expect(() =>
      validateAutomation(
        { ...checkoutRule(), targetObjectId: 'rooms', binding: { relationId: null, direction: 'SELF' } },
        objects
      )
    ).toThrow('要更新的记录')
    expect(() => validateAutomation({ ...checkoutRule(), offsetDays: -365 }, objects)).not.toThrow()
  })

  it('列表与配置面的说明文字', () => {
    expect(offsetLabel(0)).toBe('日期当天')
    expect(offsetLabel(null)).toBe('日期当天')
    expect(offsetLabel(-3)).toBe('日期之前 3 天')
    expect(offsetLabel(2)).toBe('日期之后 2 天')
    expect(dateTriggerSummary(checkoutRule(), objects)).toBe('按日期（退房日 日期当天）')
    expect(dateTriggerSummary({ ...checkoutRule(), offsetDays: -1 }, objects)).toBe('按日期（退房日 日期之前 1 天）')
  })
})

describe('变更来源：按日期自动执行', () => {
  const source = {
    kind: 'DATE_TRIGGER',
    applicationId: '3054',
    version: 7,
    name: '退房日到了改房间状态',
    businessDate: '2026-10-03'
  }
  it('标「按日期自动执行 · 规则名 · 业务日」，手动执行另行标出；变更人显示为系统', () => {
    expect(dateTriggerSourceLabel(source)).toBe('按日期自动执行 · 退房日到了改房间状态 · 2026-10-03')
    expect(dateTriggerSourceLabel({ ...source, manual: true })).toBe(
      '按日期自动执行（手动） · 退房日到了改房间状态 · 2026-10-03'
    )
    expect(changeOperator({ source, employeeName: '应用创建人' })).toBe('系统')
    expect(linkageSourceLabel(source)).toBeNull()
  })
  it('其它来源不标、变更人照常', () => {
    for (const kind of ['LINKAGE', 'AUTOMATION', 'TASK_ENTRY'])
      expect(dateTriggerSourceLabel({ ...source, kind })).toBeNull()
    expect(dateTriggerSourceLabel(null)).toBeNull()
    expect(changeOperator({ source: { ...source, kind: 'AUTOMATION' }, employeeName: '张三' })).toBe('张三')
    expect(changeOperator({ source: null, employeeName: '张三' })).toBe('张三')
  })
})
