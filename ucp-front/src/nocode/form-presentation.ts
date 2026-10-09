import { FieldType } from '@/types/nocode/enums'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'

/** 设计画布、预览、运行表单共用的布局选项；容器响应式由 business-form.css 负责。 */
export function businessFormOptions(layout: 'vertical' | 'horizontal' = 'vertical') {
  return {
    form: {
      class: 'os-business-form',
      layout,
      labelAlign: 'left' as const,
      labelWrap: true,
      labelCol: { flex: '120px' },
      wrapperCol: { flex: '1' }
    },
    row: { gutter: 24 },
    submitBtn: false,
    resetBtn: false
  }
}

/** 未指定表单时，详情与编辑仍使用同一布局：普通字段双列，长文本与文件整行。 */
export function defaultFormNodes(fields: ObjectField[]): UiNode[] {
  const nodes: UiNode[] = []
  let pending: UiNode[] = []
  function flush() {
    if (!pending.length) return
    nodes.push(
      uiNode(NodeKind.ROW, {
        id: `default-row-${pending[0]!.fieldId}`,
        children: pending.map(field =>
          uiNode(NodeKind.COLUMN, {
            id: `default-column-${field.fieldId}`,
            span: 12,
            children: [field]
          })
        )
      })
    )
    pending = []
  }
  for (const field of fields) {
    const node = uiNode(NodeKind.FIELD, { id: `default-field-${field.id}`, fieldId: field.id! })
    if ([FieldType.TEXTAREA, FieldType.RICH_TEXT, FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === field.type)) {
      flush()
      nodes.push(node)
    } else {
      pending.push(node)
      if (pending.length === 2) flush()
    }
  }
  flush()
  return nodes
}
