import { describe, expect, it } from 'vitest'
import { formFieldProjection } from './form-field-projection'
import { recordPayload } from './record-form'
import { businessFieldOptions, businessFieldRules } from './business-field-rules'
import { nodesToRules } from './application-ui'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'

const fields = ['editable', 'locked', 'hidden', 'product', 'formula', 'denied'].map(id => ({
  id,
  code: id,
  name: id,
  type: id === 'formula' ? FieldType.FORMULA : id === 'product' ? FieldType.INTEGER : FieldType.TEXT
})) as ObjectField[]
const nodes = [
  uiNode(NodeKind.CARD, {
    children: [
      uiNode(NodeKind.FIELD, { fieldId: 'locked', presentation: { readOnly: true } }),
      uiNode(NodeKind.ROW, {
        children: [
          uiNode(NodeKind.COLUMN, {
            children: ['product', 'editable', 'formula', 'denied'].map(fieldId => uiNode(NodeKind.FIELD, { fieldId }))
          })
        ]
      })
    ]
  })
]
const model = { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' }

describe('递归表单字段投影', () => {
  it('遵循布局顺序与字段权限，静态只读仍显示但不提交，关系归一化和生成字段规则保留', () => {
    const projection = formFieldProjection(fields, nodes, ['editable', 'locked', 'hidden', 'product', 'formula'])
    expect(projection.fields.map(f => f.id)).toEqual(['locked', 'product', 'editable', 'formula', 'denied'])
    expect(projection.writeFields).toEqual(['product', 'editable', 'formula'])
    const relation = { fieldId: 'product' } as any
    const options = businessFieldOptions({ product: { generated: true } } as any, [relation])
    const projectedModel = { ...model, writeFields: projection.writeFields }
    expect(
      recordPayload(fields, options, projectedModel, false, {
        editable: '保留',
        locked: '旧值',
        hidden: '不在表单',
        product: '42',
        formula: '9',
        denied: '无权修改'
      })
    ).toEqual({ editable: '保留', product: '42' })
    const rules = nodesToRules(nodes, businessFieldRules(fields, options, projectedModel, false))
    expect((rules[0]!.children![0] as any).props.disabled).toBe(true)
  })
  it('未配置布局与显式空布局语义不同，空权限不扩大为全部可写', () => {
    expect(formFieldProjection(fields).fields).toEqual(fields)
    expect(formFieldProjection(fields, []).fields).toEqual([])
    expect(formFieldProjection(fields, undefined, []).writeFields).toEqual([])
    expect(formFieldProjection(fields, [uiNode(NodeKind.FIELD, { fieldId: 'unavailable' })]).fields).toEqual([])
  })
})
