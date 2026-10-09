import { describe, expect, it } from 'vitest'
import {
  taskBusinessConfigError,
  taskWorkRuleError,
  taskWorkRuleFields,
  taskWorkRuleSummary,
  taskWorkRulePreview
} from './task-work-rule'
import type { ObjectField } from '@/types/nocode/object'
import { newTaskNode } from './task-center'
describe('业务办理项标准工时', () => {
  it('发布与发起时新视图及旧表单均可不配置工时，必办要求保持不变', () => {
    const node = newTaskNode()
    node.entries = [
      {
        key: 'room',
        name: '房间办理',
        binding: { applicationId: 'app', formId: 'form', viewId: 'view', entryId: null },
        dataMode: 'ROOT_SHARED',
        required: true,
        allowAll: false,
        readableFieldIds: null,
        writableFieldIds: null,
        sourceNodeId: null,
        sourceEntryKey: null
      }
    ]
    node.workTotalMode = 'AUTO'
    expect(taskBusinessConfigError([node])).toBe('')
    expect(node.entries[0]!.required).toBe(true)
    node.entries[0]!.binding!.viewId = null
    expect(taskBusinessConfigError([node])).toBe('')
    node.entries[0]!.workRule = { mode: 'RECORD_ONCE', minutes: 15 }
    expect(taskBusinessConfigError([node])).toBe('')
  })
  it('空规则和旧零分钟占位不阻塞，启用正工时后仍要求对应字段', () => {
    expect(taskWorkRuleError(null)).toBe('')
    expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: 0 })).toBe('')
    expect(taskWorkRuleError({ mode: 'QUANTITY', minutes: 0 })).toBe('')
    expect(taskWorkRuleError({ mode: 'CONDITION', minutes: 0 })).toBe('')
    expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: -1 })).toContain('标准工时')
    expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: 600000 })).toContain('标准工时')
    expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: 0, plannedQuantity: -1 })).toContain('预计工作量')
    expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: 135 })).toBe('')
    expect(taskWorkRuleError({ mode: 'QUANTITY', minutes: 15 })).toContain('数量字段')
    expect(
      taskWorkRuleError({ mode: 'CONDITION', minutes: 15, conditionFieldId: 'state', conditionValue: false })
    ).toBe('')
  })
  it('摘要区分每条和每单位，支持小时分钟', () => {
    expect(taskWorkRuleSummary()).toBe('未设置标准工时')
    expect(taskWorkRuleSummary({ mode: 'RECORD_ONCE', minutes: 135 })).toContain('2 小时 15 分钟')
    expect(taskWorkRuleSummary({ mode: 'QUANTITY', minutes: 15 })).toContain('/ 单位')
  })
  it('本次加减单独保留基准并校验最终时长', () => {
    const rule = { mode: 'RECORD_ONCE' as const, minutes: 15, adjustmentMinutes: 5 }
    expect(taskWorkRuleSummary(rule)).toContain('20 分钟 / 条')
    expect(rule.minutes).toBe(15)
    expect(taskWorkRuleError(rule)).toBe('')
    expect(taskWorkRuleSummary({ ...rule, adjustmentMinutes: -5 })).toContain('10 分钟 / 条')
    expect(taskWorkRuleError({ ...rule, adjustmentMinutes: -15 })).toBe('')
    expect(taskWorkRuleError({ ...rule, mode: 'QUANTITY', adjustmentMinutes: -15 })).toContain('数量字段')
    expect(taskWorkRuleError({ ...rule, mode: 'QUANTITY', quantityFieldId: 'count', adjustmentMinutes: -15 })).toBe('')
    expect(taskWorkRuleError({ ...rule, adjustmentMinutes: -16 })).toContain('调整后的标准工时')
    expect(taskWorkRuleError({ ...rule, adjustmentMinutes: 0.5 })).toContain('调整后的标准工时')
    expect(taskWorkRuleError({ ...rule, adjustmentMinutes: 599999 })).toContain('调整后的标准工时')
  })
  it('数量仅整数小数，条件排除富文本文件和复杂字段', () => {
    const fields = ['INTEGER', 'DECIMAL', 'TEXT', 'BOOLEAN', 'RICH_TEXT', 'ATTACHMENT', 'FORMULA'].map(type => ({
      id: type,
      type
    })) as ObjectField[]
    expect(taskWorkRuleFields(fields, 'QUANTITY').map(f => f.id)).toEqual(['INTEGER', 'DECIMAL'])
    expect(taskWorkRuleFields(fields, 'CONDITION').map(f => f.id)).toEqual(['INTEGER', 'DECIMAL', 'TEXT', 'BOOLEAN'])
  })
  it('计算预览不假造缺失配置，布尔否和数值零都是有效条件', () => {
    const fields = [
      { id: 'count', name: '设备数量', type: 'INTEGER' },
      { id: 'check', name: '需要复查', type: 'BOOLEAN' }
    ] as ObjectField[]
    expect(taskWorkRulePreview({ mode: 'RECORD_ONCE', minutes: 0 }, fields)).toContain('填写标准工时')
    expect(taskWorkRulePreview({ mode: 'RECORD_ONCE', minutes: 600000 }, fields)).toContain('填写标准工时')
    expect(taskWorkRulePreview({ mode: 'RECORD_ONCE', minutes: 15, adjustmentMinutes: 0.5 }, fields)).toContain(
      '填写标准工时'
    )
    expect(taskWorkRulePreview({ mode: 'QUANTITY', minutes: 15 }, fields)).toContain('选择数量字段')
    expect(
      taskWorkRulePreview({ mode: 'CONDITION', minutes: 15, conditionFieldId: 'check', conditionValue: false }, fields)
    ).toContain('「否」')
    expect(
      taskWorkRulePreview({ mode: 'CONDITION', minutes: 15, conditionFieldId: 'count', conditionValue: 0 }, fields)
    ).toContain('「0」')
    expect(
      taskWorkRulePreview({ mode: 'QUANTITY', minutes: 15, adjustmentMinutes: 5, quantityFieldId: 'count' }, fields)
    ).toBe('工时 = 设备数量 × 20 分钟')
  })
})
