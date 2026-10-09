// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { defaultReport } from './report'
import { validateReport } from './report-presentation'
import { prepareReportConfig } from './resource-config'
import { ReportDisplay, type ReportConfig } from '@/types/nocode/report'
import type { PublishedObject } from '@/types/nocode/application'

const api = vi.hoisted(() => ({ previewReport: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: api, runtime: api }) }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))

import ReportConfigEditor from '@/views/nocode/application/components/ReportConfigEditor.vue'

const objects = {
  flow: {
    objectId: 'flow',
    versionNo: 1,
    checksum: 'v1',
    definition: {
      objectId: 'flow',
      objectName: '资金流水',
      fields: [
        { id: 'date', name: '日期', type: 'DATE' },
        { id: 'company', name: '公司', type: 'TEXT' },
        { id: 'in', name: '入金', type: 'MONEY' },
        { id: 'out', name: '出金', type: 'MONEY' }
      ],
      fieldOptions: {},
      relations: [],
      details: []
    }
  }
} as unknown as Record<string, PublishedObject>
/** 业务方那张统计的形状：透视表，行 = 日期（原值），无列维度，入金 / 出金。 */
const flow = (extra: Partial<ReportConfig> = {}): ReportConfig => ({
  ...defaultReport('flow'),
  display: ReportDisplay.PIVOT,
  dimensions: [{ fieldId: 'date', relationPath: null, bucket: 'VALUE' }],
  columnDimensions: [],
  pivot: { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 },
  metrics: [
    { id: 'in', name: '入金', operation: 'SUM', fieldId: 'in', conditions: null },
    { id: 'out', name: '出金', operation: 'SUM', fieldId: 'out', conditions: null }
  ],
  ...extra
})

let app: App | undefined, host: HTMLDivElement, state: { config: ReportConfig }
const flush = async () => {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function register(target: App) {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ATabs', 'ATabPane', 'ASpace', 'AModal', 'AEmpty', 'ATable', 'AInput', 'AAlert', 'ACheckbox'])
    target.component(name, plain)
  target.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-form-item': props.label }, slots.default?.())
    })
  )
  target.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled }, slots.default?.())
    })
  )
  target.component(
    'ASegmented',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            { 'data-value': props.value },
            props.options.map((o: { value: string; label: string }) =>
              h(
                'button',
                {
                  type: 'button',
                  'data-option': o.value,
                  'aria-pressed': String(o.value === props.value),
                  onClick: () => {
                    emit('update:value', o.value)
                    emit('change', o.value)
                  }
                },
                o.label
              )
            )
          )
    })
  )
  target.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value ?? '',
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value || undefined
                emit('update:value', value)
                emit('change', value)
              }
            },
            [
              h('option', { value: '' }, ''),
              ...(props.options || []).map((o: { value: string; label: string }) =>
                h('option', { value: o.value, selected: o.value === props.value }, o.label)
              )
            ]
          )
    })
  )
  // 与 ant 的数字输入一致：清空时给出 null；min / max / placeholder 原样可查。
  target.component(
    'AInputNumber',
    defineComponent({
      props: ['value', 'min', 'max', 'placeholder'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'number',
            value: props.value ?? '',
            min: props.min,
            max: props.max,
            placeholder: props.placeholder,
            onInput: (event: Event) => {
              const text = (event.target as HTMLInputElement).value
              emit('update:value', text === '' ? null : Number(text))
            }
          })
    })
  )
}
async function mount(initial: ReportConfig) {
  state = reactive({ config: initial })
  app = createApp(() =>
    h(ReportConfigEditor, {
      modelValue: state.config,
      'onUpdate:modelValue': (value: ReportConfig) => {
        state.config = value
      },
      applicationId: 'app',
      objects,
      resources: []
    })
  )
  register(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const sortSection = () => host.querySelector('[data-section="sort"]')!
const target = () => sortSection().querySelector<HTMLSelectElement>('select[data-sort-target]')!
const targetLabels = () =>
  Array.from(target().querySelectorAll('option'))
    .map(o => o.textContent)
    .filter(Boolean)
const direction = () => sortSection().querySelector<HTMLElement>('[data-sort-direction]')!
const columnDirection = () => sortSection().querySelector<HTMLElement>('[data-column-direction]')
const limit = () => sortSection().querySelector<HTMLInputElement>('input[data-report-limit]')!
const limitHint = () => sortSection().querySelector('[data-limit-hint]')!.textContent!.replace(/\s+/g, '')
async function click(root: Element, option: string) {
  root.querySelector<HTMLButtonElement>(`[data-option="${option}"]`)!.click()
  await flush()
}
async function pick(select: HTMLSelectElement, value: string) {
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
}
async function type(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flush()
}
async function display(value: string) {
  await click(host.querySelector('[data-form-item="展示方式"]')!, value)
}
const saved = () => prepareReportConfig(JSON.parse(JSON.stringify(state.config)), [], [])
beforeEach(() => {
  vi.clearAllMocks()
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('配置器：展示与格式里的排序', () => {
  it('透视表有「行排序依据」与「排序方向」：选「按行维度的值 · 降序」即新日期在前', async () => {
    await mount(flow())
    expect(sortSection().querySelector('h4')!.textContent).toContain('排序与行数')
    expect(sortSection().querySelector('[data-form-item="行排序依据"]')).not.toBeNull()
    expect(targetLabels()).toEqual(['按行维度的值', '按指标：入金', '按指标：出金'])
    expect(target().value).toBe('@dimension')
    expect(direction().dataset.value).toBe('ASC')
    await click(direction(), 'DESC')
    expect(direction().dataset.value).toBe('DESC')
    expect(state.config.sortBy).toBe('DIMENSION')
    expect(state.config.descending).toBe(true)
    expect(state.config.sortMetricId).toBeNull()
    expect(saved()).toMatchObject({ sortBy: 'DIMENSION', descending: true, sortMetricId: null })
  })
  it('选某个指标：按该指标排序，方向保持；再选回「按行维度的值」清掉排序指标', async () => {
    await mount(flow({ sortBy: 'DIMENSION', descending: true }))
    await pick(target(), 'out')
    expect(state.config).toMatchObject({ sortBy: 'METRIC', sortMetricId: 'out', descending: true })
    await click(direction(), 'ASC')
    expect(state.config).toMatchObject({ sortBy: 'METRIC', sortMetricId: 'out', descending: false })
    await pick(target(), '@dimension')
    expect(state.config).toMatchObject({ sortBy: 'DIMENSION', sortMetricId: null, descending: false })
    expect(() => validateReport(saved())).not.toThrow()
  })
  it('存量透视表（没选排序指标却勾过倒序）：界面按实际生效的升序显示，不动排序就原样保存', async () => {
    const legacy = flow({ descending: true, limit: 30 })
    delete (legacy as Partial<ReportConfig>).sortBy
    await mount(legacy)
    expect(target().value).toBe('@dimension')
    expect(direction().dataset.value).toBe('ASC')
    expect(saved()).toMatchObject({ sortBy: null, descending: true, sortMetricId: null, limit: 30 })
    // 动了方向才写入排序依据；选回升序后真的是升序
    await click(direction(), 'DESC')
    expect(saved()).toMatchObject({ sortBy: 'DIMENSION', descending: true })
    await click(direction(), 'ASC')
    expect(saved()).toMatchObject({ sortBy: 'DIMENSION', descending: false })
  })
  it('存量配置带排序指标：显示为按该指标及其方向；移除这个指标后回到按行维度的值', async () => {
    const legacy = flow({ sortMetricId: 'out', descending: true, limit: 30 })
    delete (legacy as Partial<ReportConfig>).sortBy
    await mount(legacy)
    expect(target().value).toBe('out')
    expect(direction().dataset.value).toBe('DESC')
    expect(saved()).toMatchObject({ sortBy: null, sortMetricId: 'out', descending: true })
    // 明确选过「按指标」之后移除该指标
    await pick(target(), 'out')
    expect(state.config.sortBy).toBe('METRIC')
    const remove = Array.from(host.querySelectorAll('button')).filter(b => b.textContent?.trim() === '移除')
    // 行维度 1 个「移除」+ 两个指标各一个：最后一个是「出金」
    remove.at(-1)!.click()
    await flush()
    expect(state.config.metrics.map(m => m.id)).toEqual(['in'])
    expect(state.config).toMatchObject({ sortBy: 'DIMENSION', sortMetricId: null })
    expect(target().value).toBe('@dimension')
    expect(() => validateReport(saved())).not.toThrow()
  })
  it('列组排序只在透视表有列维度时出现；降序写入 pivot.columnDescending，升序清掉', async () => {
    await mount(flow())
    expect(columnDirection()).toBeNull()
    state.config.columnDimensions = [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }]
    state.config.dimensions = [{ fieldId: 'company', relationPath: null, bucket: 'VALUE' }]
    await flush()
    expect(columnDirection()!.dataset.value).toBe('ASC')
    await click(columnDirection()!, 'DESC')
    expect(state.config.pivot!.columnDescending).toBe(true)
    expect(columnDirection()!.dataset.value).toBe('DESC')
    expect(saved().pivot).toMatchObject({ columnDescending: true, maxColumnGroups: 24 })
    await click(columnDirection()!, 'ASC')
    expect(state.config.pivot!.columnDescending).toBeNull()
    // 汇总表没有列组排序
    await display('TABLE')
    expect(columnDirection()).toBeNull()
    expect(sortSection().querySelector('[data-form-item="排序依据"]')).not.toBeNull()
    expect(targetLabels()[0]).toBe('按分组的值')
  })
})

describe('配置器：最多展示行数可以不限制', () => {
  it('新建的透视表默认不限制：输入框为空、占位「不限制」，说明里写明一次最多多少行', async () => {
    await mount(flow())
    expect(state.config.limit).toBeNull()
    expect(limit().value).toBe('')
    expect(limit().placeholder).toBe('不限制')
    expect(limit().max).toBe('20000')
    expect(sortSection().querySelector('[data-form-item="最多展示行数"]')).not.toBeNull()
    expect(limitHint()).toContain('留空表示不限制')
    expect(limitHint()).toContain('一次最多20000行')
    expect(limitHint()).toContain('行数×列组数不超过100000格')
    expect(limitHint()).toContain('共多少行，只显示前多少行')
    expect(saved().limit).toBeNull()
  })
  it('填了数字按数字保存；清空回到不限制', async () => {
    await mount(flow({ limit: 30 }))
    expect(limit().value).toBe('30')
    await type(limit(), '5000')
    expect(saved().limit).toBe(5000)
    await type(limit(), '')
    expect(state.config.limit).toBeNull()
    expect(saved().limit).toBeNull()
  })
  it('图表上写明「不限制」只对透视表和汇总表有效，上限仍是 200；从表格切到图表时超出的数字收回到 200', async () => {
    await mount(flow({ limit: 5000 }))
    await display('TABLE')
    expect(limit().max).toBe('20000')
    expect(state.config.limit).toBe(5000)
    await display('BAR')
    expect(sortSection().querySelector('[data-form-item="最多展示组数"]')).not.toBeNull()
    expect(limit().max).toBe('200')
    expect(limit().placeholder).toBe('留空按 200 组')
    expect(limitHint()).toContain('「不限制」只对透视表和汇总表有效')
    expect(limitHint()).toContain('最多展示200组')
    expect(state.config.limit).toBe(200)
    expect(() => validateReport(saved())).not.toThrow()
    // 图表留空可以保存（按 200 组）
    await type(limit(), '')
    expect(saved().limit).toBeNull()
  })
})
