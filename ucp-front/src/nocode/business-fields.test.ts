import { describe, it, expect } from 'vitest'
import { businessFields, fieldRelation, relationFieldId, recordTitle, linkableField } from './business-fields'
import { businessFieldRules } from './business-field-rules'
import { formDesignModel, formDesignIssues } from './form-design'
import { numericField } from './report'
import { FieldType, RelationType } from '@/types/nocode/enums'
import { uiNode, NodeKind } from '@/types/nocode/application-ui'
import type { PublishedDefinition } from '@/types/nocode/application'
const definition = {
  objectId: '10',
  titleFieldId: 'name',
  fields: [
    { id: 'name', code: 'c_name', name: '名称', type: FieldType.TEXT, required: true },
    { id: 'fk', code: 'c_type', name: '类型', type: FieldType.INTEGER },
    { id: 'count', code: 'c_count', name: '数量', type: FieldType.INTEGER }
  ],
  fieldOptions: {},
  details: [],
  settings: {},
  relations: [
    { id: 'single', fieldId: 'fk', name: '类型', kind: RelationType.REFERENCE, targetObjectId: '20' },
    {
      id: 'many',
      fieldId: null,
      code: 'c_types',
      name: '类型数组',
      kind: RelationType.MANY_TO_MANY,
      targetObjectId: '20',
      required: true
    }
  ]
} as unknown as PublishedDefinition

describe('业务逻辑字段贯通', () => {
  it('应用候选加入多选关系，物理字段列表保持不变', () => {
    const fields = businessFields(definition)
    expect(fields).toHaveLength(4)
    expect(definition.fields).toHaveLength(3)
    expect(fields[3]).toMatchObject({ id: relationFieldId('many'), type: FieldType.MULTI_SELECT, required: true })
    expect(fieldRelation(definition.relations, undefined)).toBeUndefined()
    const rule = businessFieldRules(fields, {}, formDesignModel, true, {
      relations: definition.relations,
      objectId: '10'
    }).at(-1)!
    expect(rule.props).toMatchObject({
      multiple: true,
      selection: true,
      fieldId: 'relation_many',
      targetObjectId: '20'
    })
    expect(rule.validate).toEqual(expect.arrayContaining([expect.objectContaining({ required: true })]))
    const nodes = definition.fields.map(f => uiNode(NodeKind.FIELD, { fieldId: f.id }))
    expect(formDesignIssues(nodes, definition)).toEqual(
      expect.arrayContaining([expect.objectContaining({ fieldId: 'relation_many' })])
    )
    expect(formDesignIssues([...nodes, uiNode(NodeKind.FIELD, { fieldId: 'relation_many' })], definition)).toEqual([])
  })
  it('引用 ID 不进入数值指标，多值与文件不进入上游联动', () => {
    expect(numericField(definition.fields[1]!, definition.relations)).toBe(false)
    expect(numericField(definition.fields[2]!, definition.relations)).toBe(true)
    for (const type of [
      FieldType.MULTI_SELECT,
      FieldType.ATTACHMENT,
      FieldType.IMAGE,
      FieldType.REGION,
      FieldType.CASCADE
    ])
      expect(linkableField({ ...definition.fields[0]!, type })).toBe(false)
  })
  it('标题模板按编码解析，并尊重缺失字段与空值', () => {
    const d = { ...definition, settings: { ...definition.settings, titleTemplate: '{{c_name}} · {{c_count}}' } }
    expect(recordTitle(d, { name: '电脑', count: 0 })).toBe('电脑 · 0')
    expect(recordTitle(d, { name: '电脑' })).toBe('未提供可见标题')
    expect(recordTitle(definition, { name: '电脑' })).toBe('电脑')
  })
})
