// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App, type Component } from 'vue'
import Antd from 'ant-design-vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { RuleCondition } from '@/types/nocode/field-rules'
import type { DataScope } from '@/types/nocode/data-scope'
import type { ReportConfig, ReportMetric } from '@/types/nocode/report'
import RelativeDateValue from '@/views/nocode/components/RelativeDateValue.vue'
import DataScopeEditor from '@/views/nocode/components/DataScopeEditor.vue'
import RuleConditionRows from '@/views/nocode/components/RuleConditionRows.vue'
import {
  RELATIVE_BLOCKED,
  RELATIVE_DATE_CODES,
  describeRelativeDate,
  isRelativeDate,
  relativeDateError,
  relativeDateFromOption,
  relativeDateOption
} from './relative-date'
import { validateScope } from './data-scope'
import { conditionError, type PublishedDefinition } from './field-rules'
import { DATE_RELATIVE_SEARCH_OPERATORS, dynamicQueryField } from './runtime-list'
import { metricDescription } from './report-presentation'
import { summarizeConditions } from '@/components/os-table-page/conditionSummary'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'

vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => ({}) }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))

function field(type: string, id: string, name = type): ObjectField {
  return { ...newField(0, name), key: id, id, code: id, type: type as ObjectField['type'] }
}
function need<T>(value: T | null | undefined, what: string): T {
  if (value === null || value === undefined) throw new Error(`缺少${what}`)
  return value
}
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const mounted: { app: App; host: HTMLElement }[] = []
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-select-dropdown').forEach(node => node.parentElement?.remove())
})
function mount<T>(component: Component, initial: T, props: Record<string, unknown> = {}) {
  const model = ref(initial) as { value: T }
  const app = createApp({
    setup: () => () =>
      h(component, {
        modelValue: model.value,
        'onUpdate:modelValue': (value: T) => (model.value = value),
        ...props
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  return { host, model }
}
/** 按显示文字点一个单选按钮（antd 的按钮式单选）。 */
async function clickRadio(host: ParentNode, label: string) {
  const button = Array.from(host.querySelectorAll<HTMLElement>('.ant-radio-button-wrapper')).find(
    node => node.textContent?.trim() === label
  )
  if (!button) throw new Error(`没有「${label}」按钮`)
  need(button.querySelector('input'), '按钮输入').dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flush()
}
const radioLabels = (host: ParentNode) =>
  Array.from(host.querySelectorAll<HTMLElement>('.ant-radio-button-wrapper')).map(node => ({
    label: node.textContent?.trim(),
    disabled: node.classList.contains('ant-radio-button-wrapper-disabled')
  }))
async function pickIn(select: Element, label: string) {
  const before = new Set(Array.from(document.querySelectorAll('.ant-select-dropdown')))
  need(select.querySelector('.ant-select-selector'), '下拉').dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true })
  )
  await flush()
  const dropdown =
    Array.from(document.querySelectorAll('.ant-select-dropdown')).find(node => !before.has(node)) ??
    need(Array.from(document.querySelectorAll('.ant-select-dropdown')).at(-1), '下拉弹层')
  const options = Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option'))
  const option = options.find(node => node.textContent === label)
  if (!option) throw new Error(`下拉里没有「${label}」：${options.map(o => o.textContent).join('、')}`)
  option.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flush()
  return options
}

describe('相对日期的存储形状与文案', () => {
  it('存 { relative, n? }；预设天数与自填天数互相换算', () => {
    expect(relativeDateFromOption('THIS_MONTH')).toEqual({ relative: 'THIS_MONTH' })
    expect(relativeDateFromOption('PAST_N_DAYS:30')).toEqual({ relative: 'PAST_N_DAYS', n: 30 })
    expect(relativeDateFromOption('NEXT_N_DAYS', { relative: 'PAST_N_DAYS', n: 12 })).toEqual({
      relative: 'NEXT_N_DAYS',
      n: 12
    })
    expect(relativeDateOption({ relative: 'PAST_N_DAYS', n: 7 })).toBe('PAST_N_DAYS:7')
    expect(relativeDateOption({ relative: 'PAST_N_DAYS', n: 12 })).toBe('PAST_N_DAYS')
    expect(describeRelativeDate({ relative: 'PAST_N_DAYS', n: 12 })).toBe('过去 12 天')
    expect(describeRelativeDate({ relative: 'THIS_WEEK' })).toBe('本周')
  })
  it('只有带 relative 键的对象才是相对日期；存量的字符串与 [起, 止] 不是', () => {
    expect(isRelativeDate({ relative: 'TODAY' })).toBe(true)
    expect(isRelativeDate('2026-10-03')).toBe(false)
    expect(isRelativeDate(['2026-10-01', '2026-10-31'])).toBe(false)
    expect(isRelativeDate(null)).toBe(false)
  })
  it('校验编码与天数（1–3650 的整数）', () => {
    for (const code of RELATIVE_DATE_CODES)
      expect(
        relativeDateError(relativeDateFromOption(code === 'PAST_N_DAYS' || code === 'NEXT_N_DAYS' ? `${code}:7` : code))
      ).toBeNull()
    expect(relativeDateError({ relative: 'SOMEDAY' })).toContain('SOMEDAY')
    expect(relativeDateError({ relative: 'PAST_N_DAYS', n: 0 })).toContain('1–3650')
    expect(relativeDateError({ relative: 'PAST_N_DAYS', n: 1.5 })).toContain('1–3650')
    expect(relativeDateError({ relative: 'TODAY', n: 3 })).toContain('不需要天数')
  })
})

describe('各入口的保存前校验', () => {
  const fields = [field(FieldType.DATE, 'checkout', '退房日'), field(FieldType.TEXT, 'memo', '备注')]
  it('记录范围：日期字段配可组合的比较方式通过；文本字段、属于任意一个拒绝', () => {
    const scope = (fieldId: string, operator: string, value: unknown): DataScope => ({
      logic: 'AND',
      groups: [],
      conditions: [{ fieldId, operator: operator as 'eq', value, valueSource: 'CONSTANT' }]
    })
    expect(() => validateScope(scope('checkout', 'lt', { relative: 'TODAY' }), fields)).not.toThrow()
    expect(() => validateScope(scope('memo', 'eq', { relative: 'TODAY' }), fields)).toThrow('日期或日期时间字段')
    expect(() => validateScope(scope('checkout', 'in', { relative: 'TODAY' }), fields)).toThrow('比较方式')
    expect(() => validateScope(scope('checkout', 'eq', { relative: 'PAST_N_DAYS', n: 0 }), fields)).toThrow('1–3650')
    // 存量具体日期照旧。
    expect(() => validateScope(scope('checkout', 'eq', '2026-10-03'), fields)).not.toThrow()
  })
  it('对象规则：相对日期不再要求「起止两格」，但天数要合法', () => {
    const condition = (value: unknown): RuleCondition => ({
      fieldId: 'checkout',
      operator: 'between',
      valueSource: 'CONSTANT',
      value
    })
    expect(conditionError(condition({ relative: 'THIS_WEEK' }), 0)).toBeNull()
    expect(conditionError(condition({ relative: 'NEXT_N_DAYS', n: 9999 }), 0)).toContain('1–3650')
    expect(conditionError(condition(['2026-10-01', '']), 0)).toContain('起止两个值')
  })
})

describe('条件搜索（统计条件、数据视图固定范围、运行端高级查询）里的日期字段', () => {
  it('在区间内之外多出等于 / 早于 / 晚于等，取值控件是「具体 / 相对日期」', () => {
    const date = dynamicQueryField(field(FieldType.DATE, 'checkout', '退房日'))
    expect(date.operators).toEqual(['between', ...DATE_RELATIVE_SEARCH_OPERATORS])
    expect(date.valueComponent).toBe(RelativeDateValue)
    expect(date.valueProps).toMatchObject({ fieldType: FieldType.DATE, valueFormat: 'YYYY-MM-DD' })
    const stamp = dynamicQueryField(field(FieldType.DATETIME, 'arrived', '到达'), {
      ...defaultFieldOptions(),
      nativeType: 'timestamp with time zone'
    })
    expect(stamp.valueProps).toMatchObject({ valueFormat: 'YYYY-MM-DDTHH:mm:ssZ' })
    // 文本、数值不变。
    expect(dynamicQueryField(field(FieldType.TEXT, 'memo')).valueComponent).toBeUndefined()
  })
  it('列表上方「临时查询」摘要把相对日期写成中文，具体日期照旧', () => {
    const date = dynamicQueryField(field(FieldType.DATE, 'checkout', '退房日'))
    expect(
      summarizeConditions(
        {
          logic: 'AND',
          items: [
            { type: 'condition', field: 'checkout', operator: 'eq', value: { relative: 'PAST_N_DAYS', n: 7 } },
            { type: 'condition', field: 'checkout', operator: 'between', value: ['2026-10-01', '2026-10-31'] }
          ]
        },
        [date]
      )
    ).toBe('退房日 等于「过去 7 天」 且 退房日 在区间内「2026-10-01 至 2026-10-31」')
  })
  it('统计指标说明把相对日期写成中文', () => {
    const metric = {
      id: 'm',
      name: '本月退房',
      operation: 'COUNT',
      fieldId: null,
      conditions: {
        logic: 'AND',
        items: [{ type: 'condition', field: 'checkout', operator: 'eq', value: { relative: 'THIS_MONTH' } }]
      }
    } as unknown as ReportMetric
    expect(metricDescription(metric, {} as ReportConfig, { checkout: '退房日' })).toContain('退房日 等于 本月')
  })
})

describe('取值控件「具体 / 相对日期」', () => {
  it('切到相对日期存 { relative: TODAY }，再选「过去 30 天」带天数；切回具体日期清空', async () => {
    const { host, model } = mount<unknown>(RelativeDateValue, '2026-10-03', {
      operator: 'eq',
      fieldType: FieldType.DATE
    })
    await flush()
    expect(host.querySelector('[data-relative-mode]')?.getAttribute('data-relative-mode')).toBe('CONCRETE')
    await clickRadio(host, '相对日期')
    expect(model.value).toEqual({ relative: 'TODAY' })
    await pickIn(need(host.querySelector('.relative-date-select'), '相对日期下拉'), '过去 7 天（含今天）')
    expect(model.value).toEqual({ relative: 'PAST_N_DAYS', n: 7 })
    expect(host.querySelector('.relative-date-days')).not.toBeNull()
    await clickRadio(host, '具体日期')
    expect(model.value).toBeNull()
  })
  it('结果会存下来的入口：相对日期置灰，写明原因', async () => {
    const { host, model } = mount<unknown>(RelativeDateValue, null, {
      operator: 'eq',
      blockedReason: RELATIVE_BLOCKED.maintain
    })
    await flush()
    expect(radioLabels(host)).toEqual([
      { label: '具体日期', disabled: false },
      { label: '相对日期', disabled: true }
    ])
    await clickRadio(host, '相对日期')
    expect(model.value).toBeNull()
  })
  it('属于任意一个（多值）、为空等比较方式只有具体日期', async () => {
    const { host } = mount<unknown>(RelativeDateValue, [], { operator: 'in', multiple: true })
    await flush()
    expect(radioLabels(host)).toEqual([])
    const search = mount<unknown>(RelativeDateValue, [], {
      operator: 'between',
      relativeOperators: DATE_RELATIVE_SEARCH_OPERATORS
    })
    await flush()
    expect(radioLabels(search.host)).toEqual([])
    expect(search.host.querySelector('.ant-picker-range')).not.toBeNull()
  })
})

describe('常用相对日期一点即选（业务方：「日期是筛选的是当天是当月」）', () => {
  const quick = (host: ParentNode) =>
    Array.from(host.querySelectorAll<HTMLButtonElement>('[data-quick]')).map(b => b.textContent?.replace(/\s/g, ''))
  async function clickQuick(host: ParentNode, code: string) {
    need(host.querySelector<HTMLButtonElement>(`[data-quick="${code}"]`), code).click()
    await flush()
  }
  it('具体日期状态下也直接列出「今天 / 本周 / 本月」，点一下就存成相对日期', async () => {
    const { host, model } = mount<unknown>(RelativeDateValue, '2026-10-03', {
      operator: 'eq',
      fieldType: FieldType.DATE
    })
    await flush()
    expect(quick(host)).toEqual(['今天', '本周', '本月'])
    await clickQuick(host, 'THIS_MONTH')
    expect(model.value).toEqual({ relative: 'THIS_MONTH' })
    expect(host.querySelector('[data-quick="THIS_MONTH"]')?.classList.contains('ant-btn-primary')).toBe(true)
  })
  it('结果会存下来的入口、多值比较方式不出现快捷按钮', async () => {
    const blocked = mount<unknown>(RelativeDateValue, null, { operator: 'eq', blockedReason: RELATIVE_BLOCKED.onSave })
    const multi = mount<unknown>(RelativeDateValue, [], { operator: 'in', multiple: true })
    await flush()
    expect(quick(blocked.host)).toEqual([])
    expect(quick(multi.host)).toEqual([])
  })
  it('引用筛选的日期条件行也有快捷按钮；数据联动没有', async () => {
    const definition = {
      objectId: 'src',
      label: '入住记录',
      fields: [field(FieldType.DATE, 'checkout', '退房日')],
      fieldOptions: {},
      relations: []
    } as unknown as PublishedDefinition
    const initial: RuleCondition[] = [
      { fieldId: 'checkout', operator: 'between', valueSource: 'CONSTANT', value: ['', ''] }
    ]
    const reference = mount<RuleCondition[]>(RuleConditionRows, initial, {
      definition,
      formGroups: [],
      emptyText: '无'
    })
    await flush()
    await clickQuick(reference.host, 'TODAY')
    expect(reference.model.value[0]).toEqual({
      fieldId: 'checkout',
      operator: 'between',
      valueSource: 'CONSTANT',
      value: { relative: 'TODAY' }
    })
    const linkage = mount<RuleCondition[]>(RuleConditionRows, initial, {
      definition,
      formGroups: [],
      emptyText: '无',
      relativeBlocked: RELATIVE_BLOCKED.linkage
    })
    await flush()
    expect(quick(linkage.host)).toEqual([])
  })
})

describe('记录范围编辑（视图固定范围 / 记录权限）', () => {
  const fields = [field(FieldType.DATE, 'checkout', '退房日'), field(FieldType.TEXT, 'memo', '备注')]
  const scope = (value: unknown, operator = 'eq'): DataScope => ({
    logic: 'AND',
    groups: [],
    conditions: [{ fieldId: 'checkout', operator: operator as 'eq', value, valueSource: 'CONSTANT' }]
  })
  it('日期字段出现「具体 / 相对日期」；存量具体日期仍用原来的输入框原样显示', async () => {
    const { host } = mount<DataScope>(DataScopeEditor, scope('2026-10-03'), { fields, simple: true })
    await flush()
    expect(radioLabels(host).map(r => r.label)).toEqual(['具体日期', '相对日期'])
    expect(host.querySelector<HTMLInputElement>('input[aria-label="范围值"]')?.value).toBe('2026-10-03')
  })
  it('相对日期在两个可组合的比较方式之间切换时保留，换成「属于任意一个」清空', async () => {
    const { host, model } = mount<DataScope>(DataScopeEditor, scope({ relative: 'THIS_WEEK' }), {
      fields,
      simple: true
    })
    await flush()
    const operator = need(host.querySelectorAll('.scope-inputs > .ant-select')[1], '下拉')
    await pickIn(operator, '小于')
    expect(model.value.conditions[0]).toMatchObject({ operator: 'lt', value: { relative: 'THIS_WEEK' } })
    await pickIn(need(host.querySelectorAll('.scope-inputs > .ant-select')[1], '下拉'), '属于任意一个')
    expect(model.value.conditions[0]).toMatchObject({ operator: 'in', value: [] })
  })
  it('传了原因时相对日期置灰（持续维护等）', async () => {
    const { host } = mount<DataScope>(DataScopeEditor, scope(null), {
      fields,
      simple: true,
      relativeBlocked: RELATIVE_BLOCKED.maintain
    })
    await flush()
    expect(radioLabels(host)).toContainEqual({ label: '相对日期', disabled: true })
  })
})

describe('对象规则条件行（引用筛选 / 数据联动）', () => {
  const definition: PublishedDefinition = {
    objectId: 'src',
    label: '入住记录',
    fields: [field(FieldType.DATE, 'checkout', '退房日'), field(FieldType.TEXT, 'memo', '备注')],
    fieldOptions: {},
    relations: []
  } as unknown as PublishedDefinition
  const rows = (initial: RuleCondition[], props: Record<string, unknown> = {}) =>
    mount<RuleCondition[]>(RuleConditionRows, initial, { definition, formGroups: [], emptyText: '无', ...props })
  const valueSource = (host: ParentNode) => need(host.querySelectorAll('.rule-row > .ant-select')[2], '下拉')

  it('引用筛选：日期字段的值来源多出「相对日期」，选中后存 CONSTANT + { relative }', async () => {
    const { host, model } = rows([
      { fieldId: 'checkout', operator: 'lt', valueSource: 'CONSTANT', value: '2026-10-01' }
    ])
    await flush()
    const options = await pickIn(valueSource(host), '相对日期')
    expect(options.map(o => o.textContent)).toEqual(['固定值', '当前字段', '相对日期'])
    expect(model.value[0]).toEqual({
      fieldId: 'checkout',
      operator: 'lt',
      valueSource: 'CONSTANT',
      value: { relative: 'TODAY' }
    })
    // 「在范围内」也能用，且换比较方式时保留相对日期。
    await pickIn(need(host.querySelectorAll('.rule-row > .ant-select')[1], '下拉'), '在范围内')
    expect(model.value[0]).toMatchObject({ operator: 'between', value: { relative: 'TODAY' } })
  })
  it('文本字段没有「相对日期」', async () => {
    const { host } = rows([{ fieldId: 'memo', operator: 'eq', valueSource: 'CONSTANT', value: 'x' }])
    await flush()
    const options = await pickIn(valueSource(host), '固定值')
    expect(options.map(o => o.textContent)).not.toContain('相对日期')
  })
  it('数据联动：「相对日期」置灰并说明原因；选它不生效', async () => {
    const { host, model } = rows(
      [{ fieldId: 'checkout', operator: 'lt', valueSource: 'CONSTANT', value: '2026-10-01' }],
      {
        relativeBlocked: RELATIVE_BLOCKED.linkage
      }
    )
    await flush()
    const options = await pickIn(valueSource(host), '固定值')
    const relative = need(
      options.find(o => o.textContent === '相对日期'),
      '相对日期选项'
    )
    expect(relative.classList.contains('ant-select-item-option-disabled')).toBe(true)
    expect(model.value[0]?.value).toBe('2026-10-01')
  })
})
