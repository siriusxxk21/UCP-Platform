import type { PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import type { ObjectField } from '@/types/nocode/object'
import {
  AutomationMode,
  AutomationValueKind,
  type AutomationAssignment,
  type AutomationConfig
} from '@/types/nocode/automation'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import { fieldRelation } from './business-fields'
import { scopeFields, validateScope } from './data-scope'
import { selectionLinkCompatible } from './selection-compatibility'
import { systemManagedField } from './record-form'
import { recordNumberError } from './record-number'

type ObjectCatalog = Record<string, PublishedObject>
const numeric = new Set<FieldType>([FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT])
const scalar = new Set<FieldType>([
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT,
  FieldType.BOOLEAN,
  FieldType.DATE,
  FieldType.DATETIME,
  FieldType.TIME,
  FieldType.SELECT,
  FieldType.REFERENCE,
  FieldType.UUID,
  FieldType.USER,
  FieldType.DEPARTMENT,
  FieldType.ORGANIZATION,
  FieldType.POST,
  FieldType.USER_GROUP
])
export const automationEvents = [
  { value: 'CREATE', label: '新增记录' },
  { value: 'UPDATE', label: '修改记录' },
  { value: 'DELETE', label: '删除记录' }
] as const
export const automationModes = [
  { value: AutomationMode.EVENT, label: '事件发生后赋值' },
  { value: AutomationMode.MAINTAIN, label: '持续维护关联结果' }
]
const kinds = [
  { value: AutomationValueKind.VALUE, label: '固定值' },
  { value: AutomationValueKind.FIELD, label: '来源记录的字段值' },
  { value: AutomationValueKind.EXISTS, label: '按是否存在有效关联记录赋值' },
  { value: AutomationValueKind.COUNT, label: '有效关联记录数量' },
  { value: AutomationValueKind.SUM, label: '关联字段合计' },
  { value: AutomationValueKind.MIN, label: '关联字段最小值' },
  { value: AutomationValueKind.MAX, label: '关联字段最大值' }
]

export function defaultAutomation(objectId: string): AutomationConfig {
  return {
    objectId,
    targetObjectId: '',
    enabled: true,
    mode: AutomationMode.MAINTAIN,
    events: ['CREATE', 'UPDATE', 'DELETE'],
    conditions: null,
    binding: { relationId: '', direction: 'OUTGOING' },
    assignments: []
  }
}

/** 按日期自动执行的初始配置：默认更新本条记录、日期当天执行。 */
export function defaultDateAutomation(objectId: string): AutomationConfig {
  return {
    objectId,
    targetObjectId: objectId,
    enabled: true,
    mode: AutomationMode.DATE,
    events: [],
    conditions: null,
    binding: { relationId: null, direction: 'SELF' },
    assignments: [],
    dateFieldId: null,
    offsetDays: 0
  }
}

/** 偏移天数上下限，与服务端一致。 */
export const MAX_OFFSET_DAYS = 365

/** 来源上能按日期筛选的字段：日期、日期时间（读取时计算的字段不能参与筛选）。 */
export function automationDateFields(definition?: PublishedDefinition): ObjectField[] {
  return automationFields(definition).filter(f => f.type === FieldType.DATE || f.type === FieldType.DATETIME)
}

/** 「要更新的记录」选项：本条记录 + 现有关系（同事件赋值）。 */
export function dateTriggerTargets(objectId: string, objects: ObjectCatalog) {
  const source = objects[objectId]?.definition
  const self = source && !source.readOnly && !source.mainBinding?.readOnly
  return [
    ...(self
      ? [
          {
            value: 'SELF:',
            label: `本条记录（${source.objectName}）`,
            targetObjectId: objectId,
            binding: { relationId: null, direction: 'SELF' as const }
          }
        ]
      : []),
    ...automationRelations(objectId, objects)
  ]
}

/** 执行日的说明：日期当天 / 日期之前 N 天 / 日期之后 N 天。 */
export function offsetLabel(offset?: number | null) {
  const days = offset || 0
  return days === 0 ? '日期当天' : days < 0 ? `日期之前 ${-days} 天` : `日期之后 ${days} 天`
}

/** 列表里「动作内容」的一句话：按日期（退房日 当天）。 */
export function dateTriggerSummary(config: AutomationConfig, objects: ObjectCatalog) {
  const source = objects[config.objectId]?.definition
  const field = source?.fields.find(f => f.id === config.dateFieldId)
  return `按日期（${field?.name || '日期字段'} ${offsetLabel(config.offsetDays)}）`
}

export function automationFields(definition?: PublishedDefinition): ObjectField[] {
  return definition?.fields.filter(f => f.id && definition.fieldOptions[f.id]?.state !== MemberState.INACTIVE) || []
}

/** 两个方向均基于同一条引用关系，不要求业务对象互相添加引用字段。 */
export function automationRelations(objectId: string, objects: ObjectCatalog) {
  const result: Array<{ value: string; label: string; targetObjectId: string; binding: AutomationConfig['binding'] }> =
    []
  for (const item of Object.values(objects)) {
    for (const relation of item.definition.relations) {
      if (relation.kind !== RelationType.REFERENCE || relation.sourceDetailId || !relation.id || !relation.fieldId)
        continue
      if (!automationFields(item.definition).some(f => f.id === relation.fieldId)) continue
      const outgoing = item.objectId === objectId && relation.targetObjectId !== objectId
      const incoming = relation.targetObjectId === objectId && item.objectId !== objectId
      if (!outgoing && !incoming) continue
      const targetObjectId = outgoing ? relation.targetObjectId : item.objectId
      const target = objects[targetObjectId]?.definition
      if (!target || target.readOnly || target.mainBinding?.readOnly) continue
      const direction = outgoing ? 'OUTGOING' : 'INCOMING'
      result.push({
        value: `${direction}:${relation.id}`,
        label: outgoing
          ? `${relation.name} → ${target.objectName}（来源引用的记录）`
          : `${target.objectName} · ${relation.name}（引用来源的记录）`,
        targetObjectId,
        binding: { relationId: relation.id, direction }
      })
    }
  }
  return result
}

export function automationTargetFields(config: AutomationConfig, objects: ObjectCatalog) {
  const definition = objects[config.targetObjectId]?.definition
  const linking =
    config.binding.direction === 'INCOMING'
      ? definition?.relations.find(r => r.id === config.binding.relationId)?.fieldId
      : null
  return automationFields(definition).filter(f => {
    const options = definition?.fieldOptions[f.id!]
    return (
      scalar.has(f.type) &&
      f.id !== linking &&
      f.id !== definition?.settings?.documentPolicy?.lifecycle?.fieldId &&
      !systemManagedField(f, options) &&
      !options?.primaryKey &&
      (!options?.generated || !!fieldRelation(definition?.relations, f.id))
    )
  })
}

export function automationKinds(config: AutomationConfig, target?: ObjectField, definition?: PublishedDefinition) {
  if (config.mode === AutomationMode.EVENT || config.mode === AutomationMode.DATE)
    return kinds.filter(k => k.value === 'VALUE' || k.value === 'FIELD')
  const plain = target && !fieldRelation(definition?.relations, target.id)
  return kinds.filter(k => {
    if (k.value === 'EXISTS') return true
    if (!plain) return false
    if (k.value === 'COUNT' || k.value === 'SUM') return numeric.has(target.type)
    return (
      (k.value === 'MIN' || k.value === 'MAX') &&
      (numeric.has(target.type) || [FieldType.DATE, FieldType.DATETIME, FieldType.TIME].some(t => t === target.type))
    )
  })
}

export function automationSourceFields(
  config: AutomationConfig,
  assignment: AutomationAssignment,
  objects: ObjectCatalog
) {
  const source = objects[config.objectId]?.definition,
    target = objects[config.targetObjectId]?.definition
  const output = target?.fields.find(f => f.id === assignment.fieldId)
  if (!source || !target || !output) return []
  return automationFields(source).filter(f => {
    if (!scalar.has(f.type) || !selectionLinkCompatible(source, f.id!, target, output.id!)) return false
    if (output.type === FieldType.INTEGER && f.type !== FieldType.INTEGER) return false
    return (
      assignment.kind === 'FIELD' ||
      (!fieldRelation(source.relations, f.id) && (assignment.kind !== 'SUM' || numeric.has(f.type)))
    )
  })
}

export function newAutomationAssignment(mode: AutomationMode): AutomationAssignment {
  return {
    fieldId: '',
    kind: mode === 'MAINTAIN' ? 'EXISTS' : 'VALUE',
    sourceFieldId: null,
    value: null,
    emptyValue: null
  }
}

/** 清除依赖旧字段的赋值参数，避免界面切换后静默提交隐藏的旧配置。 */
export function resetAutomationAssignment(assignment: AutomationAssignment, mode: AutomationMode) {
  Object.assign(assignment, newAutomationAssignment(mode), { fieldId: assignment.fieldId })
}

export function validateAutomation(config: AutomationConfig, objects: ObjectCatalog) {
  const source = objects[config.objectId]?.definition,
    target = objects[config.targetObjectId]?.definition
  if (!source) throw new Error('请选择来源数据对象')
  const dated = config.mode === AutomationMode.DATE
  const relation = (dated ? dateTriggerTargets : automationRelations)(config.objectId, objects).find(
    r =>
      r.targetObjectId === config.targetObjectId &&
      (r.binding.direction === 'SELF' || r.binding.relationId === config.binding.relationId) &&
      r.binding.direction === config.binding.direction
  )
  if (!relation || !target) throw new Error(dated ? '请选择要更新的记录' : '请选择有效的关联关系和目标对象')
  if (dated) {
    if (!automationDateFields(source).some(f => f.id === config.dateFieldId))
      throw new Error('请选择来源记录上的日期字段')
    const offset = config.offsetDays ?? 0
    if (!Number.isInteger(offset) || Math.abs(offset) > MAX_OFFSET_DAYS)
      throw new Error(`执行日需在日期前后 ${MAX_OFFSET_DAYS} 天以内`)
    config.offsetDays = offset
    config.events = []
  } else {
    if (!config.events.length) throw new Error('请至少选择一个触发事件')
    if (config.mode === 'MAINTAIN') config.events = ['CREATE', 'UPDATE', 'DELETE']
  }
  if (config.conditions) {
    validateScope(config.conditions, scopeFields(automationFields(source)))
    const fixedOnly = (scope: NonNullable<AutomationConfig['conditions']>): boolean =>
      scope.conditions.every(c => !c.valueSource || c.valueSource === 'CONSTANT') && scope.groups.every(fixedOnly)
    if (!fixedOnly(config.conditions)) throw new Error('自动更新条件只能使用固定值，不能随操作人变化')
  }
  if (!config.assignments.length) throw new Error('请至少添加一个要更新的字段')
  if (new Set(config.assignments.map(a => a.fieldId)).size !== config.assignments.length)
    throw new Error('同一个目标字段不能重复配置')
  for (const assignment of config.assignments) {
    const field = automationTargetFields(config, objects).find(f => f.id === assignment.fieldId)
    if (!field) throw new Error('请选择可更新的目标字段')
    if (!automationKinds(config, field, target).some(k => k.value === assignment.kind))
      throw new Error(`“${field.name}”不支持当前取值方式`)
    if (
      ['FIELD', 'SUM', 'MIN', 'MAX'].includes(assignment.kind) &&
      !automationSourceFields(config, assignment, objects).some(f => f.id === assignment.sourceFieldId)
    )
      throw new Error(`“${field.name}”需要选择类型兼容的来源字段`)
    if (
      field.required &&
      ['VALUE', 'EXISTS'].includes(assignment.kind) &&
      (assignment.value == null || assignment.value === '')
    )
      throw new Error(`“${field.name}”是必填字段，请设置写入值`)
    if (
      field.required &&
      ['EXISTS', 'MIN', 'MAX'].includes(assignment.kind) &&
      (assignment.emptyValue == null || assignment.emptyValue === '')
    )
      throw new Error(`“${field.name}”是必填字段，请设置没有有效记录时的值`)
    const values =
      assignment.kind === 'EXISTS'
        ? [assignment.value, assignment.emptyValue]
        : assignment.kind === 'VALUE'
          ? [assignment.value]
          : ['MIN', 'MAX'].includes(assignment.kind)
            ? [assignment.emptyValue]
            : []
    for (const value of values) {
      if (value == null || value === '') continue
      if (!fieldRelation(target.relations, field.id)) {
        const numberError = recordNumberError(field, target.fieldOptions[assignment.fieldId], value)
        if (numberError) throw new Error(numberError)
      }
      if (field.type === FieldType.BOOLEAN && typeof value !== 'boolean') throw new Error(`“${field.name}”请选择是或否`)
      if (
        field.type === FieldType.SELECT &&
        target.fieldOptions[assignment.fieldId]?.options?.length &&
        !target.fieldOptions[assignment.fieldId]?.options.some(o => !o.disabled && o.code === value)
      )
        throw new Error(`“${field.name}”请选择可用选项`)
    }
  }
}
