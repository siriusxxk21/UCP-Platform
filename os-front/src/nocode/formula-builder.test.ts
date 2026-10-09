import { describe, expect, it } from 'vitest'
import {
  describeFormula,
  formulaFields,
  formulaNodeError,
  operationFormula,
  parseFormula,
  serializeFormula,
  sequenceFormulaFields
} from './formula-builder'
import { newField } from './object-draft'
import { defaultFieldOptions } from './data-center'
const fields = [
  { ...newField(0, '数量'), key: 'qty', code: 'qty', type: 'INTEGER' as const },
  { ...newField(1, '单价'), key: 'price', code: 'price', type: 'DECIMAL' as const },
  { ...newField(2, '名称'), key: 'name', code: 'name', type: 'TEXT' as const }
]
describe('选择式公式与现有表达式互转', () => {
  it.each([
    'qty * price',
    'round(qty * price, 2)',
    'qty - (price - 1)',
    'qty / (price / 100)',
    '(qty + price) * 2',
    "name || 'O''Brien'",
    'coalesce(qty, price, 0)',
    'round(abs(-qty))',
    'upper(lower(name))',
    '999999999999999999.123456789 + .2'
  ])('已有公式保持分组、函数及精度：%s', expression => {
    const node = parseFormula(expression)
    expect(parseFormula(serializeFormula(node))).toEqual(node)
  })
  it('与服务端一致：拼接、加减同级，乘除优先', () => {
    expect(parseFormula("'值' || qty + price")).toEqual({
      kind: 'operation',
      operation: '+',
      args: [
        {
          kind: 'operation',
          operation: '||',
          args: [
            { kind: 'text', value: '值' },
            { kind: 'field', value: 'qty' }
          ]
        },
        { kind: 'field', value: 'price' }
      ]
    })
    expect(serializeFormula(parseFormula('qty - (price - 1)'))).toBe('qty - (price - 1)')
  })
  it('数字固定值保留原精度，文字自动转义，中文预览不暴露字段编码', () => {
    expect(serializeFormula({ kind: 'text', value: "O'Brien" })).toBe("'O''Brien'")
    expect(describeFormula(parseFormula('qty * price'), fields)).toBe('(【数量】 × 【单价】)')
    expect(describeFormula(parseFormula('round(price, 2)'), fields)).toContain('保留 2 位')
  })
  it.each([
    'qty +',
    'qty * (price',
    'round(qty,2,3)',
    'coalesce(qty)',
    'abs(qty,price)',
    'eval(qty)',
    'qty; drop table t',
    '1.2.3',
    "'未结束",
    'qty >',
    'if(qty > 0, 1)',
    'and(qty > 0)',
    'not(qty > 0, true)',
    'x'.repeat(1001)
  ])('拒绝无效配置：%s', expression => {
    expect(() => parseFormula(expression)).toThrow()
  })
  it('未选字段、不可用字段、除零和无效小数位即时提示', () => {
    expect(formulaNodeError(operationFormula('*'), fields)).toContain('请选择')
    expect(formulaNodeError(parseFormula('missing * qty'), fields)).toContain('不可用')
    expect(formulaNodeError(parseFormula('qty / 0'), fields)).toContain('除数')
    expect(formulaNodeError(parseFormula('round(price, 2.5)'), fields)).toContain('整数')
    expect(formulaNodeError(parseFormula('round(price, 11)'), fields)).toContain('-10')
    expect(formulaNodeError(parseFormula('round(price, 2)'), fields)).toBeNull()
  })
  it('排除自身、停用和结构化字段，仅本行模式允许引用其他计算字段', () => {
    const all = [
      ...fields,
      { ...fields[0]!, key: 'calc', code: 'calc', type: 'FORMULA' as const },
      { ...fields[0]!, key: 'sum', code: 'sum', type: 'SUMMARY' as const },
      { ...fields[0]!, key: 'url', code: 'url', type: 'URL' as const }
    ]
    const options = { name: { ...defaultFieldOptions(), state: 'INACTIVE' as const } }
    expect(formulaFields(all, 'qty', false, options).map(f => f.code)).toEqual(['price'])
    expect(formulaFields(all, 'qty', true, options).map(f => f.code)).toEqual(['price', 'calc', 'sum'])
  })
  it('顺序候选排除直接或间接依赖明细汇总的公式，也排除跨记录公式和循环', () => {
    const extra = ['summary', 'local_summary', 'chain', 'safe', 'loop'].map(code => ({
      ...newField(0, code),
      key: code,
      code,
      type: (code === 'summary' ? 'SUMMARY' : 'FORMULA') as 'SUMMARY' | 'FORMULA'
    }))
    const local = {
      mode: 'LOCAL' as const,
      aggregate: 'SINGLE' as const,
      updateMode: 'LIVE' as const,
      targetObjectId: null,
      targetField: null,
      relationId: null,
      conditions: [],
      excludeCurrent: false,
      logic: 'AND' as const
    }
    const options = Object.fromEntries(
      Object.entries({
        local_summary: 'summary * 2',
        chain: 'local_summary + 1',
        safe: 'qty * price',
        loop: 'loop + 1'
      }).map(([key, expression]) => [key, { ...defaultFieldOptions(), expression, calculation: local }])
    )
    expect(formulaFields([...fields, ...extra], '', true, options).map(field => field.code)).toContain('summary')
    expect(sequenceFormulaFields([...fields, ...extra], '', options).map(field => field.code)).toEqual([
      'qty',
      'price',
      'name',
      'safe'
    ])
  })
  it.each([
    "IF(name = 'IN', price, -price)",
    'if(or(isblank(qty), qty = 0), null, price / qty)',
    'and(qty >= 1, qty <= 10, not(price != 0))',
    'if(true, 1, 1 / 0)',
    'qty + 1 > price * 2',
    "name <> 'OUT'",
    'qty == 1',
    '(qty > 0) = true',
    'FALSE'
  ])('条件公式往返保留语义：%s', expression => {
    const node = parseFormula(expression)
    expect(parseFormula(serializeFormula(node))).toEqual(node)
    expect(formulaNodeError(node, fields)).toBeNull()
  })
  it('IF 创建三个参数并中文描述，比较优先级低于四则运算', () => {
    expect(operationFormula('if')).toMatchObject({ args: [{ kind: 'field' }, { kind: 'field' }, { kind: 'field' }] })
    const comparison = parseFormula('qty + 1 > price * 2')
    expect(comparison).toMatchObject({ operation: '>', args: [{ operation: '+' }, { operation: '*' }] })
    expect(describeFormula(parseFormula("if(name = 'IN', price, -price)"), fields)).toContain('如果')
  })
})
