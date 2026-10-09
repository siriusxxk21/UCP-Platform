import { describe, expect, it } from 'vitest'
import {
  fillBindingIssue,
  fillSourceRelations,
  fillTargetIssue,
  fillValueFields,
  fillCyclePath,
  selectFillCompatible
} from './form-fill'
import type { FieldOptions } from '@/types/nocode/data-center'
import { formDesignIssues } from './form-design'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import type { PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'

const field = (id: string, name: string, type: FieldType): ObjectField => ({
  id,
  key: id,
  code: id,
  name,
  type,
  required: false,
  unique: false,
  sort: 0,
  length: null,
  precision: null,
  scale: null
})
const relation = (id: string, fieldId: string, targetObjectId: string, kind: RelationType = RelationType.REFERENCE) =>
  ({
    id,
    code: id,
    name: id,
    kind,
    targetObjectId,
    fieldId,
    targetFieldId: null,
    required: false,
    onDelete: 'RESTRICT'
  }) as PublishedDefinition['relations'][number]
const definition = (
  objectId: string,
  fields: ObjectField[],
  relations: PublishedDefinition['relations'] = [],
  fieldOptions: PublishedDefinition['fieldOptions'] = {}
) =>
  ({
    objectId,
    objectCode: objectId,
    objectName: objectId,
    fields,
    fieldOptions,
    relations,
    details: [],
    titleFieldId: fields[0]?.id
  }) as unknown as PublishedDefinition
const published = (value: PublishedDefinition) =>
  ({ objectId: value.objectId, versionNo: 1, checksum: 'checksum', definition: value }) as unknown as PublishedObject

// 订单引用银行账户（目标）与银行流水（来源），流水自身也引用银行账户，构成可带入的同对象路径。
const order = definition(
  'order',
  [
    field('bank', '银行流水', FieldType.REFERENCE),
    field('payAccount', '银行账户', FieldType.REFERENCE),
    field('amount', '金额', FieldType.DECIMAL),
    field('memo', '备注', FieldType.TEXT)
  ],
  [
    relation('r_bank', 'bank', 'bankStatement'),
    relation('r_pay', 'payAccount', 'bankAccount'),
    relation('r_flow', 'flow', 'bankStatement', RelationType.MANY_TO_MANY)
  ]
)
const statement = definition(
  'bankStatement',
  [
    field('account', '账户', FieldType.REFERENCE),
    field('voucher', '凭证', FieldType.REFERENCE),
    field('paid', '发生额', FieldType.DECIMAL),
    field('text', '摘要', FieldType.TEXT),
    field('stopped', '停用摘要', FieldType.TEXT)
  ],
  [relation('r_account', 'account', 'bankAccount'), relation('r_voucher', 'voucher', 'voucher')],
  { stopped: { state: MemberState.INACTIVE } as PublishedDefinition['fieldOptions'][string] }
)
const account = definition(
  'bankAccount',
  [field('name', '账户名称', FieldType.TEXT), field('statement', '关联流水', FieldType.REFERENCE)],
  [relation('r_statement', 'statement', 'bankStatement')]
)
const objects = {
  bankStatement: published(statement),
  bankAccount: published(account),
  voucher: published(definition('voucher', [field('no', '编号', FieldType.TEXT)]))
}

describe('关联带入的配置规则', () => {
  it('选项只在编码与标签一致或同一字典时兼容，顺序和停用状态不改变身份', () => {
    const options = [
      { code: 'A', label: '甲', disabled: false },
      { code: 'B', label: '乙', disabled: false }
    ]
    const local = { options } as FieldOptions
    expect(
      selectFillCompatible(local, {
        options: [...options].reverse().map(o => ({ ...o, disabled: true }))
      } as FieldOptions)
    ).toBe(true)
    expect(
      selectFillCompatible(local, { options: [{ ...options[0]!, label: '不同含义' }, options[1]!] } as FieldOptions)
    ).toBe(false)
    const dictionary = (dictionaryType: string) =>
      ({ selection: { kind: 'SYSTEM_DICTIONARY', dictionaryType } }) as FieldOptions
    expect(selectFillCompatible(dictionary('status'), dictionary('status'))).toBe(true)
    expect(selectFillCompatible(dictionary('status'), dictionary('unit'))).toBe(false)
    expect(selectFillCompatible(dictionary('status'), local)).toBe(false)
    const receiving = definition('order', [...order.fields, field('unit', '单位', FieldType.SELECT)], order.relations, {
      unit: local
    })
    const sending = definition(
      'bankStatement',
      [field('unit', '单位', FieldType.SELECT), field('label', '文本', FieldType.TEXT)],
      [],
      { unit: local }
    )
    expect(fillTargetIssue(receiving, 'unit')).toBeNull()
    expect(fillValueFields(receiving, { bankStatement: published(sending) }, 'bank', 'unit').map(f => f.id)).toEqual([
      'unit'
    ])
  })
  it('单值引用可作为目标，多选关系、计算、自动编号与未开放类型被拒绝', () => {
    expect(fillTargetIssue(order, 'payAccount')).toBeNull()
    expect(fillTargetIssue(order, 'memo')).toBeNull()
    expect(fillTargetIssue(order, 'relation_r_flow')).toBe('多选关系')
    expect(fillTargetIssue(order, 'amount')).toBeNull()
  })
  it('引用目标的来源候选只保留指向同一对象的单值引用', () => {
    expect(fillValueFields(order, objects, 'bank', 'payAccount').map(f => f.id)).toEqual(['account'])
  })
  it('标量目标的来源候选按类型兼容过滤，并排除停用字段', () => {
    expect(fillValueFields(order, objects, 'bank', 'amount').map(f => f.id)).toEqual(['paid'])
    expect(fillValueFields(order, objects, 'bank', 'memo').map(f => f.id)).toEqual(['text'])
  })
  it('停用的来源字段与来源关系都不进入候选', () => {
    const stopped = definition('bankStatement', statement.fields, statement.relations, {
      stopped: { state: MemberState.INACTIVE } as PublishedDefinition['fieldOptions'][string]
    })
    expect(
      fillValueFields(order, { ...objects, bankStatement: published(stopped) }, 'bank', 'memo').map(f => f.id)
    ).toEqual(['text'])
    const inactiveOrder = definition('order', order.fields, order.relations, {
      bank: { state: MemberState.INACTIVE } as PublishedDefinition['fieldOptions'][string]
    })
    expect(fillSourceRelations(inactiveOrder).map(r => r.fieldId)).toEqual(['payAccount'])
  })
  it('配置缺项给出可读原因，覆盖未选关联、未选来源字段与失效来源', () => {
    expect(
      fillBindingIssue(order, objects, 'payAccount', { sourceFieldId: '', valueFieldId: '', mode: 'DEFAULT' })
    ).toBe('请选择本表单中的关联字段')
    expect(
      fillBindingIssue(order, objects, 'payAccount', { sourceFieldId: 'bank', valueFieldId: '', mode: 'DEFAULT' })
    ).toBe('请选择来源字段')
    expect(
      fillBindingIssue(order, objects, 'payAccount', {
        sourceFieldId: 'bank',
        valueFieldId: 'voucher',
        mode: 'DEFAULT'
      })
    ).toContain('与目标类型不匹配')
  })
})

describe('表单检查覆盖关联带入配置', () => {
  it('联动与带入合并检查，反向带入上游形成环时拒绝', () => {
    const nodes = [
      uiNode(NodeKind.FIELD, {
        fieldId: 'bank',
        presentation: { selection: { linkFieldId: 'payAccount', linkTargetFieldId: 'account' } }
      }),
      uiNode(NodeKind.FIELD, {
        fieldId: 'payAccount',
        presentation: { fill: { sourceFieldId: 'bank', valueFieldId: 'account', mode: 'SOURCE_CHANGE' } }
      })
    ]
    expect(fillCyclePath(nodes)).toEqual([])
    expect(fillCyclePath(nodes, true)).toEqual(['bank', 'payAccount', 'bank'])
    expect(formDesignIssues(nodes, order, [], objects).some(issue => issue.message.includes('循环联动'))).toBe(true)
  })
  it('合法与非法配置分别通过和给出可定位的问题', () => {
    const valid = uiNode(NodeKind.FIELD, {
      fieldId: 'payAccount',
      presentation: { fill: { sourceFieldId: 'bank', valueFieldId: 'account', mode: 'DEFAULT' } }
    })
    expect(formDesignIssues([uiNode(NodeKind.FIELD, { fieldId: 'bank' }), valid], order, [], objects)).toEqual([])
    expect(formDesignIssues([valid], order, [], objects)[0]?.message).toContain('来源未放入当前表单')
    const missingSource = uiNode(NodeKind.FIELD, {
      fieldId: 'memo',
      presentation: { fill: { sourceFieldId: '', valueFieldId: '', mode: 'DEFAULT' } }
    })
    expect(formDesignIssues([missingSource], order, [], objects)).toEqual([
      { fieldId: 'memo', message: '“备注”的关联带入未选择关联字段' }
    ])
    const wrongType = uiNode(NodeKind.FIELD, {
      fieldId: 'amount',
      presentation: { fill: { sourceFieldId: 'bank', valueFieldId: 'text', mode: 'DEFAULT' } }
    })
    expect(formDesignIssues([wrongType], order, [], objects)[0]?.message).toContain('与目标类型不匹配')
  })
  it('单向链式带入放行，互为来源时提示循环联动', () => {
    // 链式：银行账户取银行流水，备注再取银行流水上的字段，方向单一不成环。
    const chain = [
      uiNode(NodeKind.FIELD, {
        fieldId: 'bank',
        presentation: { fill: { sourceFieldId: 'payAccount', valueFieldId: 'statement', mode: 'DEFAULT' } }
      }),
      uiNode(NodeKind.FIELD, {
        fieldId: 'memo',
        presentation: { fill: { sourceFieldId: 'bank', valueFieldId: 'text', mode: 'DEFAULT' } }
      })
    ]
    expect(
      formDesignIssues([uiNode(NodeKind.FIELD, { fieldId: 'payAccount' }), ...chain], order, [], objects).map(
        i => i.message
      )
    ).toEqual([])
    // 环：银行流水与银行账户互为带入来源。
    const cyclic = [
      ...chain.slice(0, 1),
      uiNode(NodeKind.FIELD, {
        fieldId: 'payAccount',
        presentation: { fill: { sourceFieldId: 'bank', valueFieldId: 'account', mode: 'DEFAULT' } }
      })
    ]
    expect(formDesignIssues(cyclic, order, [], objects).map(i => i.message)).toEqual([
      '关联带入存在循环联动：银行账户 → 银行流水 → 银行账户'
    ])
  })
})
