import { describe, expect, it } from 'vitest'
import type { PublishedObject } from '@/types/nocode/application'
import type { AutomationConfig } from '@/types/nocode/automation'
import {
  automationKinds,
  automationRelations,
  automationSourceFields,
  automationTargetFields,
  defaultAutomation,
  newAutomationAssignment,
  resetAutomationAssignment,
  validateAutomation
} from './automation'

const objects = {
  sources: {
    objectId: 'sources',
    versionNo: 3,
    checksum: 's3',
    definition: {
      objectId: 'sources',
      objectName: '跟进记录',
      fields: [
        { id: 'name', type: 'TEXT', name: '名称' },
        { id: 'amount', type: 'DECIMAL', name: '金额' },
        { id: 'count', type: 'INTEGER', name: '人数' },
        { id: 'date', type: 'DATE', name: '日期' },
        { id: 'customer', type: 'INTEGER', name: '客户' },
        { id: 'status', type: 'INTEGER', name: '阶段' },
        { id: 'inactive', type: 'TEXT', name: '停用' }
      ],
      relations: [
        { id: 'link', name: '关联客户', fieldId: 'customer', targetObjectId: 'targets', kind: 'REFERENCE' },
        { id: 'statusLink', name: '阶段', fieldId: 'status', targetObjectId: 'statuses', kind: 'REFERENCE' },
        { id: 'detailLink', fieldId: 'd', sourceDetailId: 'detail', targetObjectId: 'targets', kind: 'REFERENCE' },
        { id: 'manyLink', fieldId: null, targetObjectId: 'targets', kind: 'MANY_TO_MANY' }
      ],
      fieldOptions: { inactive: { state: 'INACTIVE' }, customer: { generated: true }, status: { generated: true } },
      details: []
    }
  },
  targets: {
    objectId: 'targets',
    versionNo: 1,
    checksum: 't1',
    definition: {
      objectId: 'targets',
      objectName: '客户',
      fields: [
        { id: 'id', type: 'INTEGER', name: '主键' },
        { id: 'total', type: 'DECIMAL', name: '合计' },
        { id: 'count', type: 'INTEGER', name: '次数' },
        { id: 'last', type: 'DATE', name: '最近日期' },
        { id: 'status', type: 'INTEGER', name: '状态', required: true },
        { id: 'calc', type: 'FORMULA', name: '计算' }
      ],
      fieldOptions: { id: { primaryKey: true }, status: { generated: true } },
      relations: [{ id: 'targetStatus', fieldId: 'status', targetObjectId: 'statuses', kind: 'REFERENCE' }],
      details: []
    }
  },
  statuses: {
    objectId: 'statuses',
    definition: { objectId: 'statuses', objectName: '状态', fields: [], relations: [], fieldOptions: {} }
  }
} as unknown as Record<string, PublishedObject>
const rule = (): AutomationConfig => ({
  ...defaultAutomation('sources'),
  targetObjectId: 'targets',
  binding: { relationId: 'link', direction: 'OUTGOING' },
  assignments: [{ ...newAutomationAssignment('MAINTAIN'), fieldId: 'status', value: '2', emptyValue: '1' }]
})

describe('自动更新关联数据的配置边界', () => {
  it('同一条主表引用可选择两个方向，不要求双向字段，也不混入明细或多对多', () => {
    expect(automationRelations('sources', objects).map(r => r.value)).toEqual(['OUTGOING:link', 'OUTGOING:statusLink'])
    expect(automationRelations('targets', objects)).toEqual([
      expect.objectContaining({ value: 'INCOMING:link', targetObjectId: 'sources' }),
      expect.objectContaining({ value: 'OUTGOING:targetStatus', targetObjectId: 'statuses' })
    ])
  })
  it('目标只读或引用未加入应用时不能误配关系', () => {
    const changed = structuredClone(objects)
    changed.targets!.definition.readOnly = true
    delete changed.statuses
    expect(automationRelations('sources', changed)).toEqual([])
    expect(() => validateAutomation(rule(), changed)).toThrow('有效的关联关系')
  })
  it('反向更新排除用来定位目标的引用字段，保留其他普通对象引用字段', () => {
    const config = {
      ...rule(),
      objectId: 'targets',
      targetObjectId: 'sources',
      binding: { relationId: 'link', direction: 'INCOMING' as const }
    }
    expect(automationTargetFields(config, objects).map(f => f.id)).toEqual([
      'name',
      'amount',
      'count',
      'date',
      'status'
    ])
    expect(automationTargetFields(rule(), objects).map(f => f.id)).toEqual(['total', 'count', 'last', 'status'])
  })
  it('同为整数存储的对象引用不能参与数值统计，也不能复制无关对象的身份', () => {
    const config = rule()
    const status = objects.targets!.definition.fields.find(f => f.id === 'status')!
    expect(automationKinds(config, status, objects.targets!.definition).map(k => k.value)).toEqual(['EXISTS'])
    config.mode = 'EVENT'
    const assignment = { ...newAutomationAssignment('EVENT'), fieldId: 'status', kind: 'FIELD' as const }
    expect(automationSourceFields(config, assignment, objects).map(f => f.id)).toEqual(['status'])
  })
  it('单据生命周期控制的字段不能再由自动更新规则接管', () => {
    const changed = structuredClone(objects)
    changed.targets!.definition.settings = {
      documentPolicy: { lifecycle: { fieldId: 'status' } }
    } as unknown as PublishedObject['definition']['settings']
    expect(automationTargetFields(rule(), changed).map(f => f.id)).not.toContain('status')
    expect(() => validateAutomation(rule(), changed)).toThrow('可更新的目标字段')
  })
  it('整数目标不接受小数来源；日期最大值只列出日期', () => {
    const config = rule()
    const assignment = { ...newAutomationAssignment('MAINTAIN'), fieldId: 'count', kind: 'SUM' as const }
    expect(automationSourceFields(config, assignment, objects).map(f => f.id)).toEqual(['count'])
    expect(
      automationSourceFields(config, { ...assignment, fieldId: 'last', kind: 'MAX' }, objects).map(f => f.id)
    ).toEqual(['date'])
  })
  it('存在性维护通过普通引用值配置两种结果，校验不改变记录ID', () => {
    const config = rule()
    expect(() => validateAutomation(config, objects)).not.toThrow()
    expect(config.assignments[0]).toMatchObject({ value: '2', emptyValue: '1' })
    config.assignments[0]!.emptyValue = null
    expect(() => validateAutomation(config, objects)).toThrow('没有有效记录时')
  })
  it('拒绝重复目标字段和失效来源，持续维护强制覆盖完整生命周期', () => {
    const config = rule()
    config.events = ['CREATE']
    validateAutomation(config, objects)
    expect(config.events).toEqual(['CREATE', 'UPDATE', 'DELETE'])
    config.assignments.push({ ...config.assignments[0]! })
    expect(() => validateAutomation(config, objects)).toThrow('不能重复')
    config.assignments = [
      { ...newAutomationAssignment('MAINTAIN'), fieldId: 'total', kind: 'SUM', sourceFieldId: 'inactive' }
    ]
    expect(() => validateAutomation(config, objects)).toThrow('类型兼容')
  })
  it('规则有效性不随操作者变化，拒绝嵌套动态条件', () => {
    const config = rule()
    config.conditions = {
      logic: 'AND',
      conditions: [],
      groups: [
        {
          logic: 'AND',
          groups: [],
          conditions: [{ fieldId: 'customer', operator: 'eq', value: null, valueSource: 'CURRENT_USER' }]
        }
      ]
    }
    expect(() => validateAutomation(config, objects)).toThrow('不能随操作人变化')
  })
  it('更换赋值模式清除旧的来源和固定值，避免隐藏依赖继续提交', () => {
    const assignment = { ...rule().assignments[0]!, kind: 'FIELD' as const, sourceFieldId: 'status' }
    resetAutomationAssignment(assignment, 'MAINTAIN')
    expect(assignment).toEqual({
      fieldId: 'status',
      kind: 'EXISTS',
      sourceFieldId: null,
      value: null,
      emptyValue: null
    })
  })
})
