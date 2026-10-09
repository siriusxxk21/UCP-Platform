import { businessFields, fieldRelation } from './business-fields'
import { boundFields } from './application-ui'
import { selectionLinkCompatible } from './selection-compatibility'
import { fillCyclePath, fillSourceRelations, fillTargetIssue, fillValueFields } from './form-fill'
import type { ApplicationResource, PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import { FieldType, MemberState } from '@/types/nocode/enums'
import { systemManagedField } from './record-form'
import { hasObjectValueRule } from './field-rule-runtime'

export const formDesignModel = { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' }
export interface FormDesignIssue {
  fieldId?: string
  message: string
}

/** 应用前给出可定位的检查，最终保存仍由后端按固定对象版本复核。 */
export function formDesignIssues(
  nodes: UiNode[],
  definition: PublishedDefinition,
  resources: ApplicationResource[] = [],
  objects: Record<string, PublishedObject> = {},
  parent?: { definition: PublishedDefinition; fieldIds: string[] },
  /** 查关系目标对象定义用的表（已引用 ∪ 因关联而可读取）；不传时与 objects 相同。关联带入仍只认已引用的对象。 */
  readableObjects: Record<string, PublishedObject> = objects
): FormDesignIssue[] {
  const issues: FormDesignIssue[] = []
  const fields = businessFields(definition).filter(f => definition.fieldOptions[f.id!]?.state !== MemberState.INACTIVE)
  const used = new Set<string>(),
    ids = new Set<string>()
  const usedDetails = new Set<string>()
  const available = new Set(boundFields(nodes))
  /** 关联带入按后端发布规则前置检查；提示带目标字段名，可定位具体字段。 */
  function fillIssue(node: UiNode, field?: { id?: string | null; name: string }) {
    const fill = node.presentation?.fill
    if (!fill || !node.fieldId || hasObjectValueRule(definition.fieldOptions[node.fieldId])) return
    const label = field ? `“${field.name}”` : '该字段'
    const fail = (message: string) => issues.push({ fieldId: node.fieldId || undefined, message })
    const blocked = fillTargetIssue(definition, node.fieldId)
    if (node.presentation?.readOnly) fail(`${label}已设为本表单只读，不能作为关联带入目标`)
    else if (blocked) fail(`${label}的关联带入目标不支持带入（${blocked}），请移除该配置`)
    else if (!fill.sourceFieldId) fail(`${label}的关联带入未选择关联字段`)
    else if (!fillSourceRelations(definition).some(r => r.fieldId === fill.sourceFieldId))
      fail(`${label}的关联带入来源已失效或不是当前表单的单值关系`)
    else if (!fill.valueFieldId) fail(`${label}的关联带入未选择来源字段`)
    else if (
      !fillValueFields(definition, objects, fill.sourceFieldId, node.fieldId).some(f => f.id === fill.valueFieldId)
    )
      fail(`${label}的关联带入来源字段已失效、已停用或与目标类型不匹配`)
    else if (!available.has(fill.sourceFieldId)) fail(`${label}的关联带入来源未放入当前表单`)
  }
  function selectionIssue(node: UiNode, field?: { name: string }) {
    const selection = node.presentation?.selection
    if (!selection?.linkFieldId || !node.fieldId) return
    const sourceId = selection.linkFieldId
    const sourceDefinition = available.has(sourceId)
      ? definition
      : parent?.fieldIds.includes(sourceId)
        ? parent.definition
        : undefined
    const fail = (reason: string) =>
      issues.push({ fieldId: node.fieldId!, message: `“${field?.name || node.fieldId}”的联动${reason}` })
    if (!sourceDefinition) {
      fail('来源未放入当前表单或主表')
      return
    }
    const relation = fieldRelation(definition.relations, node.fieldId)
    if (!relation) return // 组织、部门的层级联动不使用对象字段相等条件。
    const target = readableObjects[relation.targetObjectId]?.definition
    if (!target || !selection.linkTargetFieldId) {
      fail('未选择关联对象中的匹配字段')
      return
    }
    if (!selectionLinkCompatible(sourceDefinition, sourceId, target, selection.linkTargetFieldId))
      fail('来源与匹配字段类型或引用对象不兼容')
  }
  function visit(items: UiNode[], depth: number) {
    if (depth > 8) {
      issues.push({ message: '布局嵌套不能超过 8 层' })
      return
    }
    for (const node of items) {
      if (ids.has(node.id)) issues.push({ message: '布局节点重复，请移除重复节点' })
      ids.add(node.id)
      if (node.type === NodeKind.FIELD) {
        const field = fields.find(f => f.id === node.fieldId)
        if (!field)
          issues.push({
            fieldId: node.fieldId || undefined,
            message: `表单字段“${node.presentation?.label || node.fieldId || node.id}”已不可用，请从表单移除`
          })
        else if (used.has(field.id!))
          issues.push({ fieldId: field.id!, message: `“${field.name}”重复绑定，一个字段只能放置一次` })
        used.add(node.fieldId!)
        fillIssue(node, field)
        selectionIssue(node, field)
      }
      if (node.type === NodeKind.ROW && node.children.some(n => n.type !== NodeKind.COLUMN))
        issues.push({ message: '行布局中只能包含列' })
      if (node.type === NodeKind.INTERNAL_DETAIL) {
        const detail = definition.details.find(d => d.id === node.detail?.detailId && d.state !== MemberState.INACTIVE)
        if (parent || !detail) issues.push({ message: '内部明细已不可用，或被放入了另一个明细内部' })
        else if (usedDetails.has(detail.id!)) issues.push({ message: `“${detail.name}”重复放置，一张明细只能放置一次` })
        usedDetails.add(node.detail?.detailId || '')
        if (node.fieldId || node.resourceId || node.children.length)
          issues.push({ message: '内部明细区块不能绑定主表字段、业务页面资源或嵌套子节点' })
        if (node.detail?.mode && !['GRID', 'CARDS'].includes(node.detail.mode))
          issues.push({ message: '内部明细展示方式无效' })
      }
      if (node.type === NodeKind.TABS && (!node.children.length || node.children.some(n => n.type !== NodeKind.TAB)))
        issues.push({ message: '页签分组至少包含一个页签' })
      if ([NodeKind.ROW, NodeKind.COLUMN, NodeKind.CARD, NodeKind.TABS, NodeKind.TAB].some(type => type === node.type))
        visit(node.children, depth + 1)
      else if (node.children.length) issues.push({ message: '字段和说明组件不能包含子节点' })
    }
  }
  visit(nodes, 0)
  const ruleTargets = new Set(
    fields.filter(field => hasObjectValueRule(definition.fieldOptions[field.id!])).map(field => field.id!)
  )
  const cycle = fillCyclePath(nodes, true, ruleTargets)
  if (cycle.length)
    issues.push({
      fieldId: cycle[0],
      message: `关联带入存在循环联动：${cycle.map(id => fields.find(f => f.id === id)?.name || id).join(' → ')}`
    })
  if (ids.size > 200) issues.push({ message: '表单最多包含 200 个节点' })
  if (!used.size) issues.push({ message: '表单至少包含一个对象字段' })
  for (const field of fields) {
    const options = definition.fieldOptions[field.id!]
    const numbered = resources.some(
      r =>
        r.kind === ResourceKind.NUMBER_RULE &&
        r.config.objectId === definition.objectId &&
        r.config.fieldId === field.id
    )
    const generated = options?.generated && !definition.relations.some(r => r.fieldId === field.id)
    if (
      field.required &&
      !used.has(field.id!) &&
      options?.defaultValue == null &&
      !generated &&
      !numbered &&
      !systemManagedField(field, options) &&
      field.type !== FieldType.FORMULA &&
      field.type !== FieldType.AUTO_NUMBER
    )
      issues.push({ fieldId: field.id!, message: `缺少必填字段“${field.name}”` })
  }
  return issues
}

/** 只重排字段和分栏，保留卡片、页签、说明、字段 ID 与呈现属性。 */
export function arrangeFormColumns(nodes: UiNode[], columns: 1 | 2 | 3): UiNode[] {
  const flat = nodes.flatMap(node =>
    node.type === NodeKind.ROW ? node.children.flatMap(column => column.children) : [node]
  )
  const result: UiNode[] = []
  let pending: UiNode[] = []
  function flush() {
    if (columns === 1) result.push(...pending)
    else
      for (let index = 0; index < pending.length; index += columns)
        result.push(
          uiNode(NodeKind.ROW, {
            children: pending
              .slice(index, index + columns)
              .map(field => uiNode(NodeKind.COLUMN, { span: 24 / columns, children: [field] }))
          })
        )
    pending = []
  }
  for (const node of flat) {
    if (node.type === NodeKind.FIELD) pending.push(structuredClone(node))
    else {
      flush()
      result.push({ ...node, children: arrangeFormColumns(node.children, columns) })
    }
  }
  flush()
  return result
}
