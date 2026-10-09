// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App, type Component } from 'vue'
import FormulaExpressionEditor from '@/views/nocode/components/FormulaExpressionEditor.vue'
import FormulaConfigurationModal from '@/views/nocode/components/FormulaConfigurationModal.vue'
import {
  describeFormula,
  formulaNodeError,
  formulaOperations,
  operationFormula,
  parseFormula,
  serializeFormula
} from './formula-builder'
import {
  caretAfterNormalize,
  dateArgumentLabel,
  dateContextOfCalculation,
  dateFormulaOperations,
  formulaDateError,
  normalizeFormulaError,
  normalizeFormulaMessage,
  normalizeFormulaSource,
  type DateFormulaContext
} from './formula-dates'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import type { CalculationOptions } from '@/types/nocode/data-center'

vi.mock('@/utils/request', () => ({ default: {} }))

/** 与线上「入住记录」同形状：入住日、退房日（DATE）、房费（MONEY）。 */
const fields = [
  { ...newField(0, '入住日'), key: 'c_in', code: 'c_in', type: 'DATE' as const },
  { ...newField(1, '退房日'), key: 'c_out', code: 'c_out', type: 'DATE' as const },
  { ...newField(2, '登记时间'), key: 'c_at', code: 'c_at', type: 'DATETIME' as const },
  { ...newField(3, '房费'), key: 'c_fee', code: 'c_fee', type: 'MONEY' as const },
  { ...newField(4, '天数'), key: 'c_n', code: 'c_n', type: 'INTEGER' as const },
  { ...newField(5, '备注'), key: 'c_memo', code: 'c_memo', type: 'TEXT' as const }
]
/** 业务方在钉钉里拼的原文。 */
const DINGTALK = 'IF( MONTH( [入住日]) = MONTH( [退房日]), 0, DAY( [退房日])-1)'
const NORMALIZED = 'IF( MONTH( c_in) = MONTH( c_out), 0, DAY( c_out)-1)'
const stored: DateFormulaContext = { volatileAllowed: false, generated: true }
const live: DateFormulaContext = { volatileAllowed: true, generated: false }
const dateError = (expression: string, context: DateFormulaContext = stored) =>
  formulaDateError(parseFormula(expression), fields, context)
const local = (updateMode: 'LIVE' | 'ON_SAVE'): CalculationOptions => ({
  mode: 'LOCAL',
  updateMode,
  targetObjectId: null,
  relationId: null,
  targetField: null,
  aggregate: 'SINGLE',
  logic: 'AND',
  conditions: [],
  excludeCurrent: false
})

describe('钉钉写法兼容：[字段名]、全角标点', () => {
  it('钉钉那条公式：[字段名] 换成字段编码，其余一字不改', () => {
    const result = normalizeFormulaSource(DINGTALK, fields)
    expect(result.text).toBe(NORMALIZED)
    expect(result.replaced).toEqual([
      { name: '入住日', code: 'c_in' },
      { name: '退房日', code: 'c_out' },
      { name: '退房日', code: 'c_out' }
    ])
    expect(result.unknown).toEqual([])
    expect(normalizeFormulaMessage(result)).toBe('已把 [入住日]、[退房日] 换成字段编码 c_in、c_out，含义不变。')
    expect(normalizeFormulaError(result)).toBe('')
    expect(formulaNodeError(parseFormula(result.text), fields)).toBeNull()
    expect(dateError(result.text)).toBeNull()
    expect(describeFormula(parseFormula(result.text), fields)).toBe(
      '如果 (【入住日】的月份 = 【退房日】的月份)，那么 0，否则 (【退房日】是几号 − 1)'
    )
  })
  it('[字段编码] 同样认；长名字优先；引号里的方括号与全角标点不动', () => {
    const more = [...fields, { ...newField(6, '入住日期'), key: 'c_in2', code: 'c_in2', type: 'DATE' as const }]
    expect(normalizeFormulaSource('DAYS([c_out], [入住日期]) + DAY([入住日])', more).text).toBe(
      'DAYS(c_out, c_in2) + DAY(c_in)'
    )
    expect(normalizeFormulaSource('c_memo || \'[入住日]，（原样）\' || "[退房日]"', fields).text).toBe(
      'c_memo || \'[入住日]，（原样）\' || "[退房日]"'
    )
  })
  it('全角括号、逗号、中文引号换成半角', () => {
    const result = normalizeFormulaSource('DATEDIF（[入住日]，[退房日]，“D”）', fields)
    expect(result.text).toBe('DATEDIF(c_in,c_out,"D")')
    expect(parseFormula(result.text)).toEqual(parseFormula("datedif(c_in, c_out, 'D')"))
    const quotes = normalizeFormulaSource('if（c_memo = ‘A’，1，0）', fields)
    expect(quotes.text).toBe("if(c_memo = 'A',1,0)")
    expect(quotes.replaced).toEqual([])
  })
  it('找不到或重名的字段名留在原处并说明', () => {
    const unknown = normalizeFormulaSource('MONTH([入住日期])', fields)
    expect(unknown.text).toBe('MONTH([入住日期])')
    expect(normalizeFormulaError(unknown)).toBe('找不到名为「入住日期」的字段；请核对字段名，或点下面的字段按钮插入')
    const twins = [...fields, { ...newField(6, '入住日'), key: 'other', code: 'c_other', type: 'DATE' as const }]
    const ambiguous = normalizeFormulaSource('MONTH([入住日])', twins)
    expect(ambiguous.text).toBe('MONTH([入住日])')
    expect(normalizeFormulaError(ambiguous)).toContain('有多个字段都叫「入住日」')
  })
  it('不含钉钉写法的存量公式原样返回，一个字符都不动', () => {
    for (const expression of [
      'qty * price',
      'round(qty * price, 2)',
      "if(type = 'IN', amount, -amount)",
      "name || ' - ' || code",
      'if(or(isblank(qty), qty = 0), null, amount / qty)',
      "coalesce(a, 'O''Brien')"
    ]) {
      const result = normalizeFormulaSource(expression, fields)
      expect(result.text).toBe(expression)
      expect(result.edits).toEqual([])
    }
  })
  it('改写后光标跟着走：前面的改动整体平移，落在被换掉的片段里时放到片段末尾', () => {
    const source = 'MONTH([入住日]) + DAY([退房日])'
    const { text, edits } = normalizeFormulaSource(source, fields)
    expect(text).toBe('MONTH(c_in) + DAY(c_out)')
    expect(caretAfterNormalize(source.length, edits)).toBe(text.length)
    expect(caretAfterNormalize(source.indexOf(') +'), edits)).toBe(text.indexOf(') +'))
    expect(caretAfterNormalize(source.indexOf('入住日') + 1, edits)).toBe('MONTH(c_in'.length)
    expect(caretAfterNormalize(3, edits)).toBe(3)
  })
})

describe('日期函数：解析、函数列表、参数提示', () => {
  it('函数名大小写、空格随意；= 与 == 等价；单双引号等价', () => {
    expect(parseFormula('MONTH(c_in)')).toEqual(parseFormula('month ( c_in )'))
    expect(parseFormula('MONTH(c_in) = MONTH(c_out)')).toEqual(parseFormula('month(c_in)==month(c_out)'))
    expect(parseFormula('DATEDIF(c_in, c_out, "M")')).toEqual(parseFormula("datedif(c_in,c_out,'M')"))
    expect(parseFormula('"a""b"')).toEqual({ kind: 'text', value: 'a"b' })
    expect(parseFormula('TODAY( ) - c_in')).toEqual({
      kind: 'operation',
      operation: '-',
      args: [
        { kind: 'operation', operation: 'today', args: [] },
        { kind: 'field', value: 'c_in' }
      ]
    })
  })
  it.each([
    NORMALIZED,
    'c_out - c_in',
    'DAYS(c_out, c_in)',
    "DATEDIF(c_in, c_out, 'D')",
    'WEEKDAY(c_in, 2)',
    'DAY(EOMONTH(c_in, 0))',
    'YEAR(EDATE(c_in, -1))',
    'MONTH(DATE(2026, 7, 30) + 1)',
    'c_fee * ((c_out - c_in) - if(month(c_in) = month(c_out), 0, day(c_out) - 1)) / (c_out - c_in)',
    'today() - c_in',
    'DAYS(now(), c_in)'
  ])('选择式配置与表达式互转不改变结构：%s', expression => {
    const node = parseFormula(expression)
    expect(parseFormula(serializeFormula(node))).toEqual(node)
  })
  it.each([
    ['MONTH(c_in, 1)', 'MONTH 的参数个数不对：需要 1 个，写了 2 个'],
    ['DAYS(c_in)', 'DAYS 的参数个数不对：需要 2 个，写了 1 个'],
    ['WEEKDAY(c_in, 1, 2)', 'WEEKDAY 的参数个数不对：需要 1 到 2 个，写了 3 个'],
    ['TODAY(1)', 'TODAY() 不带参数'],
    ['FLOOR(c_fee)', '暂不支持函数“FLOOR”'],
    ['"未结束', '文字内容缺少结束引号']
  ])('写错时点名函数：%s', (expression, message) => {
    expect(() => parseFormula(expression)).toThrow(message)
  })
  it('函数列表认识全部日期函数，参数提示与默认参数齐全', () => {
    const names = ['year', 'month', 'day', 'weekday', 'days', 'datedif', 'eomonth', 'edate', 'date', 'today', 'now']
    expect(dateFormulaOperations.map(item => item.value)).toEqual(names)
    for (const name of names) {
      expect(formulaOperations.some(item => item.value === name)).toBe(true)
      const op = dateFormulaOperations.find(item => item.value === name)!
      expect(op.signature.startsWith(name.toUpperCase() + '(')).toBe(true)
      expect(op.insert.startsWith(name.toUpperCase() + '(')).toBe(true)
      expect(op.args).toHaveLength(op.range[1])
      const node = operationFormula(name)
      expect(node.kind === 'operation' && node.args).toHaveLength(op.count)
    }
    expect(dateArgumentLabel('days', 0)).toBe('结束日期')
    expect(dateArgumentLabel('days', 1)).toBe('开始日期')
    expect(dateArgumentLabel('datedif', 2)).toBe('单位（D、M 或 Y）')
    expect(dateArgumentLabel('round', 0)).toBeNull()
    expect(serializeFormula(operationFormula('datedif', { kind: 'field', value: 'c_in' }))).toBe("datedif(c_in, , 'D')")
    expect(serializeFormula(operationFormula('eomonth', { kind: 'field', value: 'c_in' }))).toBe('eomonth(c_in, 0)')
    expect(serializeFormula(operationFormula('today'))).toBe('today()')
    // 原有函数的默认参数不变。
    expect(serializeFormula(operationFormula('round', { kind: 'field', value: 'c_fee' }))).toBe('round(c_fee, 2)')
    expect(operationFormula('if').kind === 'operation' && operationFormula('if')).toMatchObject({ args: { length: 3 } })
  })
  it('中文预览', () => {
    const text = (expression: string) => describeFormula(parseFormula(expression), fields)
    expect(text('c_out - c_in')).toBe('(【退房日】 − 【入住日】)')
    expect(text('DAYS(c_out, c_in)')).toBe('从【入住日】到【退房日】的天数')
    expect(text('EOMONTH(c_in, 0)')).toBe('【入住日】往后 0 个月的月末')
    expect(text('today()')).toBe('今天')
    expect(text('round(c_fee, 2)')).toContain('保留 2 位')
  })
})

describe('日期函数：类型检查（与服务端同一套规则与文案）', () => {
  it.each([
    NORMALIZED,
    'c_out - c_in',
    '(c_out - c_in) - (' + NORMALIZED + ')',
    'DAYS(c_out, c_at) + WEEKDAY(c_in, 2) + DATEDIF(c_in, c_out, "m")',
    'IF(EOMONTH(c_in, 0) < c_out, DAY(c_out) - 1, 0)',
    "YEAR(DATE(2026, c_n, 1) + 1) + MONTH('2026-07-30')",
    'c_fee * 2 + c_n',
    'coalesce(c_in, c_out)',
    'c_in',
    'if(isblank(c_in), 0, c_in)',
    'coalesce(c_in, 0)'
  ])('合法：%s', expression => {
    expect(dateError(expression)).toBeNull()
  })
  it.each([
    ['MONTH(c_fee)', 'MONTH 的第 1 个参数要填日期或日期时间字段（或其它日期函数的结果），「房费」是数字'],
    ['DAYS(c_out, c_memo)', 'DAYS 的第 2 个参数要填日期或日期时间字段（或其它日期函数的结果），「备注」是文字'],
    ["DAY('七月')", "，日期请写成 '2026-07-30' 这样"],
    ["DAY('2026-02-30')", 'DAY 的第 1 个参数要填日期'],
    ['EDATE(c_in, c_memo)', 'EDATE 的第 2 个参数要填数字，「备注」是文字'],
    ['DATE(c_in, 1, 1)', 'DATE 的第 1 个参数要填数字，「入住日」是日期'],
    ['WEEKDAY(c_in, c_n)', 'WEEKDAY 的第二个参数请直接写 1、2 或 3'],
    ['WEEKDAY(c_in, 4)', 'WEEKDAY 的第二个参数请直接写 1、2 或 3'],
    ["DATEDIF(c_in, c_out, 'W')", 'DATEDIF 的第三个参数请直接写'],
    ['DATEDIF(c_in, c_out, c_memo)', 'DATEDIF 的第三个参数请直接写'],
    ['c_in + c_out', '两个日期不能相加'],
    ['c_n - c_in', '数字不能减日期'],
    ['c_in * 2', '「入住日」是日期，不能做乘除'],
    ['c_fee / EOMONTH(c_in, 0)', 'EOMONTH 的结果是日期，不能做乘除'],
    ['c_in + c_memo', '日期只能加减天数（数字）或减另一个日期，「备注」是文字'],
    ['IF(c_in > 5, 1, 0)', '「入住日」是日期，不能直接和数字比较（另一边是数字 5）'],
    ['ABS(c_in)', 'ABS 只能用在数字上，「入住日」是日期'],
    ['IF(c_n > 1, c_in + 1, 0)', 'IF 的几个结果里有的是日期、有的是数字']
  ])('点名出错的函数：%s', (expression, message) => {
    expect(dateError(expression)).toContain(message)
  })
  it.each(['EOMONTH(c_in, 0)', 'EDATE(c_in, 1)', 'DATE(2026, 1, 1)', 'c_in + 1', 'IF(c_n > 1, c_in + 1, c_out)'])(
    '公式字段的结果不能是日期：%s',
    expression => {
      for (const context of [stored, live])
        expect(formulaDateError(parseFormula(expression), fields, context)).toContain(
          '这条公式最后算出来的是一个日期，而公式字段的结果只能是文本、整数、小数或金额'
        )
    }
  )
  it('TODAY() / NOW()：落库的公式里拦住并说明，读取时计算与公式默认值可以用', () => {
    for (const expression of ['TODAY() - c_in', 'DAYS(NOW(), c_in)', 'IF(c_out < today(), 1, 0)']) {
      expect(dateError(expression, dateContextOfCalculation(null))).toContain(
        '每天的结果都不一样，而这个字段的值是保存下来的'
      )
      expect(dateError(expression, dateContextOfCalculation(local('ON_SAVE')))).toContain('读取时计算')
      expect(dateError(expression, dateContextOfCalculation(local('LIVE')))).toBeNull()
      expect(dateError(expression, { ...live, defaultTarget: 'INTEGER' })).toBeNull()
    }
    expect(dateContextOfCalculation(null)).toEqual({ volatileAllowed: false, generated: true })
    expect(dateContextOfCalculation(local('LIVE'))).toEqual({ volatileAllowed: true, generated: false })
  })
  it('字段类型不明时不下结论、不误报', () => {
    const untyped = [{ ...newField(0, '某个公式字段'), key: 'c_x', code: 'c_x', type: 'FORMULA' as const }, ...fields]
    for (const expression of ['YEAR(c_x + 1)', 'DAY(c_x - 1) + (c_out - c_x)', 'MONTH(c_x)', 'c_x * 2'])
      expect(formulaDateError(parseFormula(expression), untyped, live)).toBeNull()
    expect(formulaDateError(parseFormula('YEAR(c_missing + 1)'), fields, live)).toBeNull()
  })
  it('公式默认值：目标是数值字段时结果不能是日期，文本目标可以', () => {
    expect(dateError('EOMONTH(c_in, 0)', { ...live, defaultTarget: 'MONEY' })).toBe(
      '公式算出来的是一个日期，不能填进数值字段；要天数请用两个日期相减或 DAYS'
    )
    expect(dateError('EOMONTH(c_in, 0)', { ...live, defaultTarget: 'TEXT' })).toBeNull()
    expect(dateError('TODAY()', { ...live, defaultTarget: 'TEXT' })).toBeNull()
  })
})

const mounted: App[] = []
async function mount(component: Component, props: Record<string, unknown>, preview = vi.fn()) {
  const app = createApp({ ...component, render: () => null }, props)
  app.provide(nocodePlatformKey, { dataCenter: { formulaPreview: preview } } as unknown as NocodePlatform)
  const vm = app.mount(document.createElement('div'))
  mounted.push(app)
  await nextTick()
  return (vm.$ as unknown as { setupState: Record<string, any> }).setupState
}
afterEach(() => {
  mounted.splice(0).forEach(app => app.unmount())
})

describe('公式编辑器：贴入钉钉公式、试算、存量公式不变', () => {
  it('贴入钉钉那条公式：自动换成字段编码并通过校验，发出的就是要存的文本，试算用服务端结果', async () => {
    const onUpdate = vi.fn()
    const preview = vi
      .fn()
      .mockResolvedValue({ value: '1', resultType: 'DECIMAL', referencedFields: ['c_in', 'c_out'] })
    const state = await mount(
      FormulaExpressionEditor,
      { modelValue: null, fields, calculation: null, 'onUpdate:modelValue': onUpdate },
      preview
    )
    state.changeMode('advanced')
    state.typeRaw(DINGTALK)
    await nextTick()
    expect(state.raw).toBe(NORMALIZED)
    expect(onUpdate).toHaveBeenLastCalledWith(NORMALIZED)
    expect(state.normalizeNote).toBe('已把 [入住日]、[退房日] 换成字段编码 c_in、c_out，含义不变。')
    expect(state.validationError).toBe('')
    expect(state.description).toContain('【入住日】的月份')
    expect(state.sampleFields.map((field: { code: string }) => field.code)).toEqual(['c_in', 'c_out'])
    state.sampleValues.c_in = '2026-07-30'
    state.sampleValues.c_out = '2026-08-02'
    await nextTick()
    await state.previewSamples()
    expect(preview).toHaveBeenCalledWith({
      expression: NORMALIZED,
      fieldCodes: fields.map(field => field.code),
      fieldTypes: Object.fromEntries(fields.map(field => [field.code, field.type])),
      values: { c_in: '2026-07-30', c_out: '2026-08-02' }
    })
    expect(state.sampleResult).toBe('1')
    expect(state.samplePlaceholder(fields[0])).toContain('2026-07-30')
    expect(state.samplePlaceholder(fields[3])).toBe('空值可留空')
  })
  it('字段名认不出、参数类型不对、落库公式用 TODAY()：各自给出说人话的提示', async () => {
    const state = await mount(FormulaExpressionEditor, { modelValue: null, fields, calculation: null })
    state.changeMode('advanced')
    state.typeRaw('MONTH([入住日期])')
    await nextTick()
    expect(state.raw).toBe('MONTH([入住日期])')
    expect(state.validationError).toBe('找不到名为「入住日期」的字段；请核对字段名，或点下面的字段按钮插入')
    state.typeRaw('MONTH([房费])')
    await nextTick()
    expect(state.validationError).toContain('MONTH 的第 1 个参数要填日期或日期时间字段')
    state.typeRaw('TODAY() - [入住日]')
    await nextTick()
    expect(state.raw).toBe('TODAY() - c_in')
    expect(state.validationError).toContain('TODAY() 每天的结果都不一样')
    state.typeRaw('EOMONTH([入住日]，0）')
    await nextTick()
    expect(state.raw).toBe('EOMONTH(c_in,0)')
    expect(state.validationError).toContain('这条公式最后算出来的是一个日期')
    const liveState = await mount(FormulaExpressionEditor, { modelValue: null, fields, calculation: local('LIVE') })
    liveState.changeMode('advanced')
    liveState.typeRaw('TODAY() - [入住日]')
    await nextTick()
    expect(liveState.validationError).toBe('')
    const defaultState = await mount(FormulaExpressionEditor, { modelValue: null, fields, defaultTarget: 'MONEY' })
    defaultState.changeMode('advanced')
    defaultState.typeRaw('[房费] * (TODAY() - [入住日])')
    await nextTick()
    expect(defaultState.raw).toBe('c_fee * (TODAY() - c_in)')
    expect(defaultState.validationError).toBe('')
    defaultState.typeRaw('EDATE([入住日], 1)')
    await nextTick()
    expect(defaultState.validationError).toBe('公式算出来的是一个日期，不能填进数值字段；要天数请用两个日期相减或 DAYS')
  })
  it('点函数按钮插入到光标处，光标停在括号里', async () => {
    const onUpdate = vi.fn()
    const state = await mount(FormulaExpressionEditor, {
      modelValue: null,
      fields,
      calculation: null,
      'onUpdate:modelValue': onUpdate
    })
    state.changeMode('advanced')
    state.insertFunction(dateFormulaOperations.find(item => item.value === 'month')!)
    expect(state.raw).toBe('MONTH()')
    state.insertField('c_in')
    expect(state.raw).toBe('MONTH(c_in)')
    expect(onUpdate).toHaveBeenLastCalledWith('MONTH(c_in)')
    expect(state.validationError).toBe('')
  })
  it.each([
    'c_fee * c_n',
    'round(c_fee * c_n, 2)',
    "if(c_memo = 'IN', c_fee, -c_fee)",
    "c_memo || ' - ' || c_memo",
    'if(or(isblank(c_n), c_n = 0), null, c_fee / c_n)',
    'coalesce(c_fee, 0)',
    'abs(c_fee) >= 100',
    'c_fee == c_n',
    'c_fee<>c_n',
    'ROUND( c_fee/c_n ,2 )'
  ])('存量公式打开后文本不变、不发出改动、校验照旧通过：%s', async expression => {
    const onUpdate = vi.fn()
    const state = await mount(FormulaExpressionEditor, {
      modelValue: expression,
      fields,
      calculation: null,
      'onUpdate:modelValue': onUpdate
    })
    expect(state.raw).toBe(expression)
    expect(state.validationError).toBe('')
    expect(onUpdate).not.toHaveBeenCalled()
    // 在表达式模式里原样再输入一遍（等于没改）：发出的文本与原文逐字相同。
    state.changeMode('advanced')
    state.typeRaw(expression)
    await nextTick()
    expect(state.raw).toBe(expression)
    expect(state.normalizeNote).toBe('')
    expect(onUpdate).toHaveBeenLastCalledWith(expression)
  })
  it('公式字段配置弹窗：日期公式通过字段校验；落库公式里的 TODAY() 被拦', async () => {
    const field = { ...newField(9, '次月天数'), key: 'next', code: 'next', type: 'FORMULA' as const }
    const base = { field, fields: [...fields, field], fieldOptions: {}, relations: [] }
    const ok = await mount(FormulaConfigurationModal, {
      ...base,
      options: { ...defaultFieldOptions(), expression: NORMALIZED, resultType: 'INTEGER', calculation: null }
    })
    expect(ok.appliedError()).toBe('')
    const stale = await mount(FormulaConfigurationModal, {
      ...base,
      options: { ...defaultFieldOptions(), expression: 'TODAY() - c_in', resultType: 'INTEGER', calculation: null }
    })
    expect(stale.appliedError()).toContain('TODAY() 每天的结果都不一样')
    const fresh = await mount(FormulaConfigurationModal, {
      ...base,
      options: {
        ...defaultFieldOptions(),
        expression: 'TODAY() - c_in',
        resultType: 'INTEGER',
        calculation: local('LIVE')
      }
    })
    expect(fresh.appliedError()).toBe('')
  })
})
