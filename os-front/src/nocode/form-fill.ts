import type { UiNode, FormFillBinding } from '@/types/nocode/application-ui'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import type { PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { SelectionKind } from '@/types/nocode/selection'
import { businessFields, fieldRelation } from './business-fields'
import { hasObjectValueRule } from './field-rule-runtime'

export const fillNodes = (nodes: UiNode[]): UiNode[] =>
  nodes.flatMap(node => [...(node.fieldId && node.presentation?.fill ? [node] : []), ...fillNodes(node.children)])

const scalarFillTypes: string[] = [
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT,
  FieldType.BOOLEAN,
  FieldType.DATE,
  FieldType.DATETIME,
  FieldType.SELECT
]
const numericTypes: string[] = [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT]

/** 选项身份以字典或编码/标签定义为准；停用状态在运行时按当前值复核。 */
export function selectFillCompatible(source?: FieldOptions, target?: FieldOptions): boolean {
  const sourceKind = source?.selection?.kind || SelectionKind.LOCAL_OPTIONS
  const targetKind = target?.selection?.kind || SelectionKind.LOCAL_OPTIONS
  if (sourceKind !== targetKind) return false
  if (sourceKind === SelectionKind.SYSTEM_DICTIONARY)
    return !!source?.selection?.dictionaryType && source.selection.dictionaryType === target?.selection?.dictionaryType
  if (sourceKind !== SelectionKind.LOCAL_OPTIONS) return false
  const options = (value?: FieldOptions) =>
    JSON.stringify((value?.options || []).map(o => [o.code, o.label]).sort((a, b) => a[0]!.localeCompare(b[0]!)))
  return options(source) === options(target)
}

/** 字段停用状态与后端发布校验取同一来源，避免设计器放行停用字段。 */
export const fillFieldInactive = (definition: PublishedDefinition, fieldId: string) =>
  definition.fieldOptions?.[fieldId]?.state === MemberState.INACTIVE

/**
 * 目标字段不支持带入时返回原因，否则返回 null。规则与后端 FormFillBindings 的发布校验一致：
 * 单值引用可作为目标，多选关系、计算、自动编号和非标量类型不开放。
 */
export function fillTargetIssue(definition: PublishedDefinition, fieldId: string): string | null {
  const field = businessFields(definition).find(f => f.id === fieldId)
  if (!field) return '字段已不可用'
  const relation = fieldRelation(definition.relations, fieldId)
  if (relation?.kind === RelationType.MANY_TO_MANY) return '多选关系'
  if (field.type === FieldType.FORMULA || field.type === FieldType.SUMMARY) return '计算字段'
  if (field.type === FieldType.AUTO_NUMBER) return '自动编号'
  if (relation) return null
  return scalarFillTypes.includes(field.type) ? null : '该字段类型'
}

/** 本表单可作为带入来源的关联字段：主表单值关系，排除多选关系和已停用字段。 */
export const fillSourceRelations = (definition: PublishedDefinition) =>
  definition.relations.filter(
    r =>
      !r.sourceDetailId &&
      r.kind !== RelationType.MANY_TO_MANY &&
      !!r.fieldId &&
      !fillFieldInactive(definition, r.fieldId)
  )

/**
 * 来源对象上可带入的字段：排除停用字段；标量目标要求类型兼容（或同为数值），
 * 引用目标要求来源也是单值引用且指向同一对象。
 */
export function fillValueFields(
  definition: PublishedDefinition,
  objects: Record<string, PublishedObject> | undefined,
  sourceFieldId: string,
  targetFieldId: string
): ObjectField[] {
  const source = fieldRelation(definition.relations, sourceFieldId)
  const sourceDefinition = source ? objects?.[source.targetObjectId]?.definition : undefined
  if (!sourceDefinition) return []
  const target = businessFields(definition).find(f => f.id === targetFieldId)
  const targetRelation = fieldRelation(definition.relations, targetFieldId)
  return businessFields(sourceDefinition).filter(field => {
    if (!field.id || fillFieldInactive(sourceDefinition, field.id)) return false
    const relation = fieldRelation(sourceDefinition.relations, field.id)
    if (targetRelation)
      return (
        !!relation &&
        relation.kind !== RelationType.MANY_TO_MANY &&
        relation.targetObjectId === targetRelation.targetObjectId
      )
    if (!target) return false
    const type =
      field.type === FieldType.FORMULA || field.type === FieldType.SUMMARY
        ? sourceDefinition.fieldOptions?.[field.id]?.resultType
        : field.type
    if (!type) return false
    if (target.type === FieldType.SELECT || type === FieldType.SELECT)
      return (
        target.type === type &&
        selectFillCompatible(sourceDefinition.fieldOptions?.[field.id], definition.fieldOptions?.[targetFieldId])
      )
    return type === target.type || (numericTypes.includes(target.type) && numericTypes.includes(type))
  })
}

/** 配置缺项提示；设计器在保存前即时反馈，避免带着半成品配置进入草稿。 */
export function fillBindingIssue(
  definition: PublishedDefinition,
  objects: Record<string, PublishedObject> | undefined,
  targetFieldId: string,
  binding: FormFillBinding
): string | null {
  if (hasObjectValueRule(definition.fieldOptions[targetFieldId])) return null
  const target = fillTargetIssue(definition, targetFieldId)
  if (target) return `目标字段不支持带入（${target}）`
  if (!binding.sourceFieldId) return '请选择本表单中的关联字段'
  if (!fillSourceRelations(definition).some(r => r.fieldId === binding.sourceFieldId))
    return '关联来源已失效或不是当前表单的单值关系，请重新选择'
  if (!binding.valueFieldId) return '请选择来源字段'
  if (
    !fillValueFields(definition, objects, binding.sourceFieldId, targetFieldId).some(f => f.id === binding.valueFieldId)
  )
    return '来源字段已失效、已停用或与目标类型不匹配，请重新选择'
  return null
}

/** 沿“来源字段 → 目标字段”的写入方向找首个环，首尾为同一字段；无环返回空数组。 */
export function fillCyclePath(
  nodes: UiNode[],
  includeSelection = false,
  ignoredTargets: ReadonlySet<string> = new Set()
): string[] {
  const adjacency = new Map<string, string[]>()
  for (const node of fillNodes(nodes)) {
    if (ignoredTargets.has(node.fieldId!)) continue
    const from = node.presentation!.fill!.sourceFieldId
    adjacency.set(from, [...(adjacency.get(from) || []), node.fieldId!])
  }
  if (includeSelection) {
    const visitNodes = (items: UiNode[]) => {
      for (const node of items) {
        const from = node.presentation?.selection?.linkFieldId
        if (from && node.fieldId) adjacency.set(from, [...(adjacency.get(from) || []), node.fieldId])
        visitNodes(node.children)
      }
    }
    visitNodes(nodes)
  }
  const state = new Map<string, number>()
  const stack: string[] = []
  const visit = (id: string): string[] => {
    state.set(id, 1)
    stack.push(id)
    for (const next of adjacency.get(id) || []) {
      if (state.get(next) === 1) return [...stack.slice(stack.indexOf(next)), next]
      if (!state.get(next)) {
        const found = visit(next)
        if (found.length) return found
      }
    }
    stack.pop()
    state.set(id, 2)
    return []
  }
  for (const id of adjacency.keys())
    if (!state.get(id)) {
      const found = visit(id)
      if (found.length) return found
    }
  return []
}
