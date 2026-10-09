import type { ObjectField } from '@/types/nocode/object'
import { NodeKind, type UiNode } from '@/types/nocode/application-ui'

/** 表格表头与画布字段共用显示名称；自定义标签仅影响当前表单，不改对象字段定义。 */
export function formFieldLabel(field: Pick<ObjectField, 'id' | 'name'>, nodes?: UiNode[]) {
  const find = (items: UiNode[]): UiNode | undefined => {
    for (const node of items) {
      if (node.type === NodeKind.FIELD && node.fieldId === field.id) return node
      const child = find(node.children)
      if (child) return child
    }
  }
  return find(nodes || [])?.presentation?.label || field.name
}

/** 布局字段顺序与写入白名单共用递归投影；未指定布局保留全部字段，空布局保持为空。 */
export function formFieldProjection(fields: ObjectField[], nodes?: UiNode[], writeFields?: string[]) {
  const order: string[] = []
  const readOnly = new Set<string>()
  const visit = (items: UiNode[]) => {
    for (const node of items) {
      if (node.type === NodeKind.FIELD && node.fieldId) {
        order.push(node.fieldId)
        if (node.presentation?.readOnly) readOnly.add(node.fieldId)
      }
      visit(node.children)
    }
  }
  if (nodes) visit(nodes)
  const byId = new Map(fields.map(field => [field.id, field]))
  const selected = nodes
    ? [...new Set(order)].map(id => byId.get(id)).filter((field): field is ObjectField => !!field)
    : fields
  return {
    fields: selected,
    writeFields: selected
      .map(field => field.id!)
      .filter(id => !readOnly.has(id) && (writeFields == null || writeFields.includes(id)))
  }
}
