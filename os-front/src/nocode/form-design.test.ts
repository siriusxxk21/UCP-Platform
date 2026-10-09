import { describe, expect, it } from 'vitest'
import { businessFieldOptions, businessFieldRules } from './business-field-rules'
import { defaultFieldOptions } from './data-center'
import { arrangeFormColumns, formDesignIssues, formDesignModel } from './form-design'
import { boundFields, nodesToRules, rulesToNodes } from './application-ui'
import { FieldType } from '@/types/nocode/enums'
import { ResourceKind, type PublishedDefinition } from '@/types/nocode/application'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'

const fields = Object.values(FieldType).map((type, index) => ({
  id: String(index + 1),
  code: type.toLowerCase(),
  name: type,
  type,
  required: type === FieldType.TEXT,
  length: 100,
  precision: 20,
  scale: 2
})) as ObjectField[]
const definition = {
  objectId: 'object',
  fields,
  fieldOptions: {},
  relations: [],
  details: []
} as unknown as PublishedDefinition
const nodes = fields.map(f => uiNode(NodeKind.FIELD, { fieldId: f.id }))

describe('对象驱动的表单设计', () => {
  it('对象规则接管赋值后保留旧带入配置，但不再因失效来源或旧循环阻止发布', () => {
    const targetId = fields[0]!.id!
    const legacy = uiNode(NodeKind.FIELD, {
      fieldId: targetId,
      presentation: { fill: { sourceFieldId: targetId, valueFieldId: 'missing', mode: 'SOURCE_CHANGE' } }
    })
    expect(formDesignIssues([legacy], definition).some(issue => issue.message.includes('关联带入'))).toBe(true)
    const withRule = {
      ...definition,
      fieldOptions: { [targetId]: { rules: { linkage: { readOnly: false } } } }
    } as PublishedDefinition
    expect(formDesignIssues([legacy], withRule)).toEqual([])
    expect(legacy.presentation?.fill?.valueFieldId).toBe('missing')
  })
  it('设计和运行使用同一种控件映射，不从表单节点接收类型或授权', () => {
    const design = businessFieldRules(fields, {}, formDesignModel, true, { mode: 'design' })
    const runtime = businessFieldRules(fields, {}, formDesignModel, true)
    expect(design.map(r => [r.field, r.type])).toEqual(runtime.map(r => [r.field, r.type]))
    for (const type of [FieldType.USER, FieldType.DEPARTMENT, FieldType.POST, FieldType.IMAGE, FieldType.ATTACHMENT]) {
      const rule = design.find(r => r.field === fields.find(f => f.type === type)!.id)!
      expect(rule.type).toBe('nocodeBusinessField')
      expect(rule.props?.kind).toBe(type)
      expect(rule.props?.mode).toBe('design')
    }
    const saved = rulesToNodes(nodesToRules(nodes, design, true))
    expect(saved.every(node => node.type === NodeKind.FIELD)).toBe(true)
    expect(JSON.stringify(saved)).not.toMatch(/nocodeBusinessField|applicationId|onUploadStatus/)
    const denied = businessFieldRules(fields, {}, { ...formDesignModel, writeFields: [] }, true)
    expect(nodesToRules(saved, denied).every(r => r.props?.disabled)).toBe(true)
  })
  it('引用由对象关系识别，不依赖外键的 INTEGER 或 UUID 物理类型', () => {
    const relation = { fieldId: fields[2]!.id, targetObjectId: 'company' }
    const rules = businessFieldRules(fields, {}, formDesignModel, true, {
      relations: [relation] as any,
      applicationId: 'app'
    })
    expect(rules[2]).toMatchObject({
      type: 'nocodeBusinessField',
      props: { kind: FieldType.REFERENCE, targetObjectId: 'company', applicationId: 'app' }
    })
  })
  it('平台生成的关联列仍允许选择业务值，实际写权限保持有效', () => {
    const field = { ...fields.find(f => f.type === FieldType.INTEGER)!, required: true }
    const relations = [{ fieldId: field.id, targetObjectId: 'company' }] as any
    const options = { [field.id!]: { ...defaultFieldOptions(), generated: true } }
    const editable = businessFieldRules([field], options, formDesignModel, true, { mode: 'preview', relations })[0]!
    expect(editable.props?.disabled).toBe(false)
    expect(editable.validate?.[0]).toMatchObject({ required: true })
    expect(businessFieldOptions(options, relations)[field.id!]?.generated).toBe(false)
    expect(options[field.id!]?.generated).toBe(true)
    const denied = businessFieldRules([field], options, { ...formDesignModel, writeFields: [] }, true, {
      relations
    })[0]!
    expect(denied.props?.disabled).toBe(true)
  })
  it('结构检查接受最深合法字段，拒绝空页签和不合法的行布局', () => {
    let nested = nodes[0]!
    for (let index = 0; index < 8; index++) nested = uiNode(NodeKind.CARD, { children: [nested] })
    expect(formDesignIssues([nested], definition)).toEqual([])
    expect(
      formDesignIssues([uiNode(NodeKind.CARD, { children: [nested] })], definition).some(i =>
        i.message.includes('8 层')
      )
    ).toBe(true)
    expect(
      formDesignIssues([nodes[0]!, uiNode(NodeKind.TABS)], definition).some(i => i.message.includes('至少包含一个页签'))
    ).toBe(true)
    expect(
      formDesignIssues([uiNode(NodeKind.ROW, { children: [nodes[0]!] })], definition).some(i =>
        i.message.includes('只能包含列')
      )
    ).toBe(true)
  })
  it('排版保留字段身份、属性、卡片、页签和说明；列数转换不重复或遗漏字段', () => {
    const original = [
      uiNode(NodeKind.TABS, {
        children: [
          uiNode(NodeKind.TAB, {
            text: '资料',
            children: [
              uiNode(NodeKind.CARD, { text: '基本信息', children: nodes.slice(0, 5) }),
              uiNode(NodeKind.TEXT, { text: '请按实际资料填写' }),
              ...nodes.slice(5)
            ]
          })
        ]
      })
    ]
    original[0]!.children[0]!.children[0]!.children[0]!.presentation = { label: '公司名称', readOnly: true }
    const triple = arrangeFormColumns(original, 3)
    const single = arrangeFormColumns(triple, 1)
    expect(boundFields(triple)).toEqual(boundFields(original))
    expect(single).toEqual(original)
    expect(original[0]!.children[0]!.children[0]!.children[0]!.type).toBe(NodeKind.FIELD)
  })
  it('应用前检测重复绑定、失效字段和缺少对象级必填字段', () => {
    const duplicate = [nodes[0]!, uiNode(NodeKind.FIELD, { fieldId: fields[0]!.id })]
    expect(formDesignIssues(duplicate, definition).some(i => i.message.includes('重复绑定'))).toBe(true)
    expect(
      formDesignIssues([uiNode(NodeKind.FIELD, { fieldId: 'missing' })], definition).some(i =>
        i.message.includes('不可用')
      )
    ).toBe(true)
    expect(formDesignIssues(nodes.slice(1), definition)).toContainEqual(
      expect.objectContaining({ fieldId: fields[0]!.id, message: '缺少必填字段“TEXT”' })
    )
    expect(formDesignIssues(nodes, definition)).toEqual([])
  })
  it('失效字段检查使用存量显示名称或字段ID，显式移除后可通过校验', () => {
    const missing = uiNode(NodeKind.FIELD, {
      id: 'old-summary',
      fieldId: 'summary',
      presentation: { label: '内容汇总' }
    })
    const unnamed = uiNode(NodeKind.FIELD, { id: 'old-unnamed', fieldId: 'removed-id' })
    const layout = [uiNode(NodeKind.CARD, { children: [...nodes, missing, unnamed] })]
    expect(formDesignIssues(layout, definition)).toEqual([
      { fieldId: 'summary', message: '表单字段“内容汇总”已不可用，请从表单移除' },
      { fieldId: 'removed-id', message: '表单字段“removed-id”已不可用，请从表单移除' }
    ])
    layout[0]!.children = nodes
    expect(formDesignIssues(layout, definition)).toEqual([])
  })
  it('默认值和同对象编号规则可满足未放入画布的必填字段，不借用其他对象规则', () => {
    const number = {
      id: 'n',
      name: '编号',
      code: 'number',
      kind: ResourceKind.NUMBER_RULE,
      config: { objectId: 'object', fieldId: fields[0]!.id }
    }
    expect(formDesignIssues(nodes.slice(1), definition, [number])).toEqual([])
    expect(
      formDesignIssues(nodes.slice(1), definition, [{ ...number, config: { ...number.config, objectId: 'other' } }])
    ).toHaveLength(1)
    expect(
      formDesignIssues(nodes.slice(1), {
        ...definition,
        fieldOptions: { [fields[0]!.id!]: { defaultValue: '默认' } } as any
      })
    ).toEqual([])
  })
  it('字段展示属性在设计、保存和运行之间往返，不覆盖数值与对象校验', () => {
    const rules = businessFieldRules(fields, {}, formDesignModel, true, { mode: 'design' })
    const node = {
      ...nodes[0]!,
      presentation: { label: '公司全称', placeholder: '营业执照上的名称', help: '必填说明', readOnly: false }
    }
    const design = nodesToRules([node], rules, true)
    expect(design[0]).toMatchObject({
      title: '公司全称',
      wrap: { extra: '必填说明' },
      props: { placeholder: '营业执照上的名称' }
    })
    const restored = rulesToNodes(design)
    expect(restored[0]!.presentation).toEqual(node.presentation)
    expect(nodesToRules(restored, rules)[0]!.validate).toEqual(rules[0]!.validate)
  })
})
