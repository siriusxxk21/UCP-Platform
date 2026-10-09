import { describe, expect, it } from 'vitest'
import { behaviorState, formCondition } from './form-behavior'
import { nodesToRules, rulesToNodes } from './application-ui'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import type { DocumentExpression as E } from '@/types/nocode/document-policy'

const field = (fieldId: string): E => ({ op: 'FIELD', fieldId, args: [] })
const value = (value: E['value']): E => ({ op: 'VALUE', value, args: [] })
const expression = (op: E['op'], ...args: E[]): E => ({ op, args })
describe('动态表单条件', () => {
  it('默认保留显示和编辑；0 与 false 不能被当作未填', () => {
    expect(behaviorState(undefined, {})).toEqual({ visible: true, required: false, readOnly: false })
    for (const v of [0, false, '0']) expect(formCondition(expression('EMPTY', field('x')), { x: v })).toBe(false)
    for (const v of [null, undefined, '', '  ', []])
      expect(formCondition(expression('EMPTY', field('x')), { x: v })).toBe(true)
  })
  it('条件状态随输入变化，隐藏不改写输入', () => {
    const behavior = {
      showWhen: expression('EQ', field('type'), value('采购')),
      requiredWhen: value(true),
      readOnlyWhen: value(false)
    }
    const values = { type: '其他', note: '已填写说明' }
    expect(behaviorState(behavior, values)).toEqual({ visible: false, required: true, readOnly: false })
    expect(values.note).toBe('已填写说明')
    expect(behaviorState(behavior, { ...values, type: '采购' }).visible).toBe(true)
  })
  it('比较超出浮点精度的金额和两个数值字段', () => {
    expect(
      formCondition(
        expression('GT', field('a'), field('b')),
        { a: '9007199254740993', b: '9007199254740992' },
        new Set(['a', 'b'])
      )
    ).toBe(true)
    expect(formCondition(expression('LT', field('a'), field('b')), { a: '2', b: '10' }, new Set(['a', 'b']))).toBe(true)
    expect(formCondition(expression('EQ', field('a'), value(0.1)), { a: '0.100' })).toBe(true)
  })
  it('空值比较、组合与无效数值不会意外命中', () => {
    expect(formCondition(expression('GT', field('missing'), value(0)), {})).toBe(false)
    expect(formCondition(expression('NE', field('missing'), value(null)), {})).toBe(false)
    expect(formCondition(expression('GT', field('x'), value(0)), { x: '错误' })).toBe(false)
    expect(formCondition(expression('AND', value(true), expression('NOT', value(false))), {})).toBe(true)
  })
  it('拒绝未适配的聚合，避免给用户显示错误条件结果', () => {
    expect(() => formCondition({ op: 'COUNT', detailId: 'd', args: [] }, {})).toThrow('跨明细')
  })
  it('设计器往返和再次打开保留动态条件', () => {
    const behavior = { showWhen: expression('EQ', field('type'), value('采购')), clearWhenHidden: true }
    const nodes = [uiNode(NodeKind.FIELD, { fieldId: 'note', presentation: { behavior } })]
    const restored = rulesToNodes(nodesToRules(nodes, [{ field: 'note', type: 'input' }], true))
    expect(restored[0]?.presentation?.behavior).toEqual(behavior)
  })
})
