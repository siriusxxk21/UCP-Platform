import { describe, expect, it } from 'vitest'
import { defaultFormNodes } from './form-presentation'
import { arrangeFormColumns } from './form-design'
import { boundFields, nodesToRules, rulesToNodes } from './application-ui'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'

describe('表单布局展示协议', () => {
  const fields = Array.from({ length: 5 }, (_, i) => ({
    id: `f${i}`,
    name: `字段${i}`,
    type: FieldType.TEXT
  })) as ObjectField[]
  const fieldRules = fields.map(f => ({ type: 'input', field: f.id!, title: f.name }))
  it.each([1, 2, 3] as const)('%i 列保存重开与运行保留整行边界及字段顺序', columns => {
    const nodes = arrangeFormColumns(
      fields.map(f => uiNode(NodeKind.FIELD, { fieldId: f.id })),
      columns
    )
    const stored = rulesToNodes(nodesToRules(nodes, fieldRules, true))
    expect(boundFields(stored)).toEqual(fields.map(f => f.id))
    const runtime = nodesToRules(stored, fieldRules)
    expect(rulesToNodes(runtime)).toEqual(stored)
    if (columns > 1)
      for (const row of runtime) {
        expect(row.type).toBe('fcRow')
        expect(row.col).toEqual({ show: false })
        for (const column of row.children as any[]) {
          expect(column.col).toEqual({ show: false })
          expect(column.props.span).toBe(24 / columns)
        }
      }
  })
  it('未配置表单也保持长文本整行、普通字段双列和稳定身份', () => {
    const defaults = [...fields.slice(0, 3), { ...fields[3]!, type: FieldType.TEXTAREA }, fields[4]!]
    const first = defaultFormNodes(defaults)
    expect(first).toEqual(defaultFormNodes(defaults))
    expect(boundFields(first)).toEqual(defaults.map(f => f.id))
    expect(first[2]).toMatchObject({ type: NodeKind.FIELD, fieldId: 'f3' })
    expect(first[3]!.children[0]!.span).toBe(12)
  })
})
