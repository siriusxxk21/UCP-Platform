// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, reactive, type App } from 'vue'
import { defaultReport, reportFieldOptions } from './report'
import { prepareReportConfig } from './resource-config'
import { reportFieldScope } from './report-presentation'
import { validateReportSources } from './report-sources'
import {
  example1Config,
  example2Config,
  example3Config,
  multiObjects,
  singleStayConfig
} from './report-multisource-fixture'
import { flush, pick, registerStubs } from './report-multisource-harness'
import type { ReportConfig, ReportMetric } from '@/types/nocode/report'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'

const api = vi.hoisted(() => ({ previewReport: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: api, runtime: api }) }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['label', 'entries'],
      setup: props => () =>
        h('div', {
          'data-condition': props.label,
          'data-entries': (props.entries || []).map((e: { value: string }) => e.value).join(',')
        })
    })
  }
})
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))

import ReportConfigEditor from '@/views/nocode/application/components/ReportConfigEditor.vue'

let app: App | undefined, host: HTMLDivElement, state: { config: ReportConfig }
async function mount(initial: ReportConfig, resources: ApplicationResource[] = []) {
  state = reactive({ config: initial })
  app = createApp(() =>
    h(ReportConfigEditor, {
      modelValue: state.config,
      'onUpdate:modelValue': (value: ReportConfig) => {
        state.config = value
      },
      applicationId: 'app',
      objects: multiObjects,
      resources
    })
  )
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.clearAllMocks()
})
const $ = <T extends Element = HTMLElement>(selector: string, root: ParentNode = host) =>
  root.querySelector<T>(selector)!
const button = (label: string, root: ParentNode = host) =>
  Array.from(root.querySelectorAll('button')).find(b => b.textContent?.trim() === label)!
async function click(target: HTMLElement) {
  target.click()
  await flush()
}
async function type(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flush()
}
const card = (id: string) => $(`[data-source="${id}"]`)
const metric = (index: number) => host.querySelectorAll<HTMLElement>('.metric-editor')[index]
async function setMetric(
  index: number,
  m: { name: string; operation: string; fieldId?: string; source?: string; formula?: [string, string, string] }
) {
  const editor = () => metric(index)
  if (m.source) await pick($<HTMLSelectElement>('[data-metric-source]', editor()), m.source)
  await type($<HTMLInputElement>('input[aria-label="指标名称"]', editor()), m.name)
  await pick($<HTMLSelectElement>('select[aria-label="计算方式"]', editor()), m.operation)
  if (m.fieldId) await pick($<HTMLSelectElement>('select[placeholder="统计字段"]', editor()), m.fieldId)
  if (m.formula) {
    const left = $<HTMLSelectElement>('select[placeholder="左侧指标"]', editor())
    const row = left.parentElement!
    await pick(left, metricId(m.formula[0]))
    await pick(row.querySelectorAll('select')[1] as HTMLSelectElement, m.formula[1])
    await pick($<HTMLSelectElement>('select[placeholder="右侧指标"]', editor()), metricId(m.formula[2]))
  }
}
/** 指标是界面生成的随机 ID：按名称找。 */
const metricId = (name: string) => state.config.metrics.find(m => m.name === name)!.id
const rowSelect = (index: number, section: 'rows' | 'columns') =>
  host.querySelectorAll<HTMLElement>(`[data-section="${section}"] .config-row`)[index]
async function setDimension(section: 'rows' | 'columns', index: number, value: string, bucket?: string) {
  const selects = rowSelect(index, section).querySelectorAll('select')
  await pick(selects[0] as HTMLSelectElement, value)
  if (bucket) await pick(rowSelect(index, section).querySelectorAll('select')[1] as HTMLSelectElement, bucket)
}
const slot = (source: string, key: string) => $<HTMLSelectElement>(`[data-slot="${key}"] select`, card(source))

/** 保存：与 ResourceManager.apply 同一条路（prepareReportConfig + validateReportSources）。 */
function save(config: ReportConfig = state.config) {
  const definition = multiObjects[config.objectId].definition
  const saved = prepareReportConfig(
    JSON.parse(JSON.stringify(config)),
    reportFieldOptions(config.objectId, multiObjects, {}, config.grain === 'DETAIL' ? config.detailId : undefined),
    [],
    reportFieldScope(config, definition)
  )
  validateReportSources(saved, multiObjects)
  return saved
}
/**
 * 契约 14.5 的配置原文没有写单来源编辑器本来就会写的那几个键（chart、pivot、detailEditable、指标的 format，
 * 以及改过计算方式的指标上的 formula: null / conditions: null）。比对前把这些键拿出来单独核对，其余逐键相同；
 * 指标 ID 是界面生成的，按顺序换成契约里的 ID。
 */
function comparable(saved: ReportConfig, ids: string[]) {
  const map = Object.fromEntries(saved.metrics.map((m, i) => [m.id, ids[i]]))
  const { chart, pivot, detailEditable, ...rest } = saved
  expect(chart).toEqual({ barMode: 'GROUPED', horizontal: false, labels: false, legendPosition: 'TOP' })
  expect(pivot).toEqual({ subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 })
  expect(detailEditable).toBeNull()
  return {
    ...rest,
    sortMetricId: rest.sortMetricId ? map[rest.sortMetricId] : rest.sortMetricId,
    metrics: rest.metrics.map((m: ReportMetric) => {
      const { format, ...metric } = m
      expect(format).toEqual({ financial: m.operation === 'SUM' })
      const out: Record<string, unknown> = { ...metric, id: map[m.id] }
      if (out.formula === null) delete out.formula
      if (out.conditions === null) delete out.conditions
      if (m.formula) out.formula = { ...m.formula, left: map[m.formula.left], right: map[m.formula.right] }
      return out
    })
  }
}

/** 从零配出「来源 1 当月 + 来源 2 次月」（例 2 的前半）。 */
async function buildExample2Sources() {
  await mount({ ...defaultReport('stay'), timeZone: 'Asia/Tokyo' })
  await click($('[data-segmented] [data-display="PIVOT"]'))
  await setDimension('rows', 0, 'stay_property')
  await click(button('添加列维度'))
  await setDimension('columns', 0, 'stay_check_in', 'MONTH')
  await click($('[data-source-add]'))
  expect(card('s2')).toBeTruthy()
  // 同对象：维度逐位抄来源 1；改列维度的对应为「退房日」
  expect(slot('s2', 'r0').value).toBe('stay_property')
  expect(slot('s2', 'c0').value).toBe('stay_check_in')
  await pick(slot('s2', 'c0'), 'stay_check_out')
  await type($<HTMLInputElement>('[data-source="main"] input[data-source-name]'), '当月')
  await type($<HTMLInputElement>('input[data-source-name]', card('s2')), '次月')
  await type($<HTMLInputElement>('[data-section="rows"] input[data-dimension-label]'), '物件')
  await type($<HTMLInputElement>('[data-section="columns"] input[data-dimension-label]'), '月份')
  await setMetric(0, { name: '当月金额', operation: 'SUM', fieldId: 'stay_cur_amount' })
  await click(button('添加指标'))
  // 先选计算方式（字段自动带出来源 1 的「当月金额」），再换来源：字段与指标条件必须清空
  await setMetric(1, { name: '次月金额', operation: 'SUM' })
  expect(state.config.metrics[1].fieldId).toBe('stay_cur_amount')
  await pick($<HTMLSelectElement>('[data-metric-source]', metric(1)), 's2')
  expect(state.config.metrics[1].sourceId).toBe('s2')
  expect(state.config.metrics[1].fieldId).toBeNull()
  expect($<HTMLSelectElement>('select[placeholder="统计字段"]', metric(1)).value).toBe('')
  await pick($<HTMLSelectElement>('select[placeholder="统计字段"]', metric(1)), 'stay_next_amount')
}

describe('F3 编辑器：例 2 从零配出', () => {
  it('来源 1 → 添加来源 → 维度对应 → 指标 → 计算指标，生成的配置与契约 14.5（例 2）逐键相同', async () => {
    await buildExample2Sources()
    await click(button('添加指标'))
    await setMetric(2, { name: '月收益', operation: 'FORMULA', formula: ['当月金额', 'ADD', '次月金额'] })
    expect(comparable(save(), ['cur', 'nxt', 'rev'])).toEqual(example2Config())
  })
})

describe('F4 编辑器：例 1、例 3 从零配出', () => {
  it('例 1（跨对象）：再加来源「支出」换成支出对象，配置与契约 14.5 逐键相同', async () => {
    await buildExample2Sources()
    await click($('[data-source-add]'))
    await pick($<HTMLSelectElement>('[data-source-object]', card('s3')), 'expense')
    // 换对象：维度对应清空、卡片标红并阻止保存
    expect(slot('s3', 'r0').value).toBe('')
    expect(card('s3').querySelector('[data-source-missing]')!.textContent).toBe('还没有对应字段')
    expect(() => save()).toThrow('请填写数据来源名称（最多 30 字）')
    await type($<HTMLInputElement>('input[data-source-name]', card('s3')), '支出')
    expect(() => save()).toThrow('来源「支出」要为每个行维度、列维度各指定一个对应字段')
    await pick(slot('s3', 'r0'), 'expense_property')
    await pick(slot('s3', 'c0'), 'expense_paid_on')
    expect(card('s3').querySelector('[data-source-missing]')).toBeNull()
    await click(button('添加指标'))
    await setMetric(2, { name: '支出', operation: 'SUM', source: 's3', fieldId: 'expense_amount' })
    await click(button('添加指标'))
    await setMetric(3, { name: '收入', operation: 'FORMULA', formula: ['当月金额', 'ADD', '次月金额'] })
    await click(button('添加指标'))
    await setMetric(4, { name: '利润', operation: 'FORMULA', formula: ['收入', 'SUBTRACT', '支出'] })
    // 计算指标不显示来源下拉
    expect(metric(4).querySelector('[data-metric-source]')).toBeNull()
    await pick($<HTMLSelectElement>('[data-sort-target]'), metricId('利润'))
    await click($('[data-sort-direction] [data-display="DESC"]'))
    expect(comparable(save(), ['cur', 'nxt', 'exp', 'inc', 'pro'])).toEqual(example1Config())
  })

  it('例 3（两个来源都是明细粒度）：配置与契约 14.5 逐键相同', async () => {
    await mount({ ...defaultReport('voucher'), timeZone: 'Asia/Tokyo' })
    await click($('[data-segmented] [data-display="PIVOT"]'))
    $<HTMLInputElement>('[data-report-grain] [data-radio="DETAIL"] input').dispatchEvent(new Event('change'))
    await flush()
    await setDimension('rows', 0, 'line_debit_account')
    await click(button('添加列维度'))
    await setDimension('columns', 0, 'voucher_date', 'MONTH')
    await click($('[data-source-add]'))
    // 同对象同明细：粒度与维度都抄来源 1
    expect(state.config.extraSources![0]).toMatchObject({ grain: 'DETAIL', detailId: 'voucher_lines' })
    await pick(slot('s2', 'r0'), 'line_credit_account')
    await type($<HTMLInputElement>('[data-source="main"] input[data-source-name]'), '借方')
    await type($<HTMLInputElement>('input[data-source-name]', card('s2')), '贷方')
    await type($<HTMLInputElement>('[data-section="rows"] input[data-dimension-label]'), '科目')
    await setMetric(0, { name: '借方合计', operation: 'SUM', fieldId: 'line_debit_amount' })
    await click(button('添加指标'))
    await setMetric(1, { name: '贷方合计', operation: 'SUM', source: 's2', fieldId: 'line_credit_amount' })
    await click(button('添加指标'))
    await setMetric(2, { name: '余额', operation: 'FORMULA', formula: ['借方合计', 'SUBTRACT', '贷方合计'] })
    // 契约 14.5 例 3 的原文没写 sortBy；保存整理（prepareReportConfig，基线行为）恒写 sortBy: null
    const saved = comparable(save(), ['dr', 'cr', 'bal'])
    expect(saved.sortBy).toBeNull()
    delete saved.sortBy
    expect(saved).toEqual(example3Config())
  })
})

describe('F5 维度对应下拉', () => {
  it('例 1 来源 3 的「月份」行：「金额」禁用并提示 R5，「支出日期」可选；「物件」行的「金额」提示 R1', async () => {
    await mount(example1Config())
    const month = slot('s3', 'c0')
    expect(card('s3').querySelector('[data-slot="c0"] .slot-target')!.textContent).toBe('月份（按月）→')
    const option = (select: HTMLSelectElement, value: string) =>
      select.querySelector<HTMLOptionElement>(`option[value="${value}"]`)!
    expect(option(month, 'expense_amount').disabled).toBe(true)
    expect(option(month, 'expense_amount').title).toBe('小数、金额、百分比字段只能与同一个字段对齐')
    expect(option(month, 'expense_paid_on').disabled).toBe(false)
    expect(option(month, 'expense_paid_on').title).toBe('')
    const property = slot('s3', 'r0')
    expect(option(property, 'expense_amount').disabled).toBe(true)
    expect(option(property, 'expense_amount').title).toBe('一个是引用其它对象的字段，另一个不是')
    expect(option(property, 'expense_property').disabled).toBe(false)
  })
})

describe('F6 只有来源 1 时保存的配置与基线逐键相同', () => {
  it('添加再删除来源后保存：不出现四个新键、指标不出现 sourceId，与没碰过来源时逐键相同', async () => {
    await mount(singleStayConfig())
    const untouched = save()
    app!.unmount()
    host.remove()
    await mount(singleStayConfig())
    await click($('[data-source-add]'))
    expect(state.config.extraSources).toHaveLength(1)
    await click(button('删除来源', card('s2')))
    const saved = save()
    expect(JSON.stringify(saved)).toBe(JSON.stringify(untouched))
    expect(Object.keys(saved)).toEqual(Object.keys(untouched))
    for (const key of ['sourceName', 'extraSources', 'dimensionLabels', 'columnDimensionLabels'])
      expect(saved).not.toHaveProperty(key)
    saved.metrics.forEach(m => expect(m).not.toHaveProperty('sourceId'))
    // 单来源界面：没有来源名称、维度显示名、指标来源三处输入
    expect(host.querySelector('[data-source-name]')).toBeNull()
    expect(host.querySelector('[data-dimension-label]')).toBeNull()
    expect(host.querySelector('[data-metric-source]')).toBeNull()
  })
})

describe('F7 多来源切到柱状图', () => {
  it('显示 L1，来源不自动删除；预览不发请求', async () => {
    await mount(example1Config())
    expect(host.querySelector('[data-sources-blocked]')).toBeNull()
    await click($('[data-segmented] [data-display="BAR"]'))
    expect($('[data-sources-blocked]').textContent).toBe('多个数据来源目前只用于透视表和汇总表')
    expect(state.config.extraSources).toHaveLength(2)
    expect($<HTMLButtonElement>('[data-source-add]').disabled).toBe(true)
    await click(button('刷新预览'))
    expect(api.previewReport).not.toHaveBeenCalled()
    expect(host.querySelector('.report-preview [role="alert"]')!.textContent).toBe(
      '多个数据来源目前只用于透视表和汇总表'
    )
  })
})

describe('F8 删除来源同时删除其指标（先确认）', () => {
  it('确认后删掉来源「支出」、它的指标「支出」和引用它的「利润」；排序指标一并清掉', async () => {
    await mount(example1Config())
    await click(button('删除来源', card('s3')))
    expect(state.config.extraSources).toHaveLength(2)
    expect($('[data-source-confirm] span', card('s3')).textContent).toBe('删除来源会同时删除它的 1 个指标')
    await click($('[data-source-confirm-ok]', card('s3')))
    expect(state.config.extraSources!.map(s => s.id)).toEqual(['s2'])
    expect(state.config.metrics.map(m => m.id)).toEqual(['cur', 'nxt', 'inc'])
    expect(state.config.sortMetricId).toBeNull()
  })
})

describe('来源 1 增删维度时附加来源同步', () => {
  it('删掉行维度、再加一个：附加来源同位删掉 / 新增空位并标红；分桶跟来源 1', async () => {
    await mount(example1Config())
    await click(button('添加行维度'))
    expect(state.config.extraSources!.map(s => s.dimensions.length)).toEqual([2, 2])
    expect(state.config.extraSources![1].dimensions[1].fieldId).toBe('')
    expect(card('s3').querySelector('[data-source-missing]')).toBeTruthy()
    expect(state.config.dimensionLabels).toEqual(['物件', ''])
    const remove = rowSelect(0, 'rows').querySelectorAll('button')[0] as HTMLButtonElement
    await click(remove)
    expect(state.config.extraSources![1].dimensions).toEqual([{ fieldId: '', relationPath: null, bucket: 'VALUE' }])
    expect(state.config.dimensionLabels).toEqual([''])
    await pick(rowSelect(0, 'columns').querySelectorAll('select')[1] as HTMLSelectElement, 'YEAR')
    expect(state.config.extraSources!.map(s => s.columnDimensions![0].bucket)).toEqual(['YEAR', 'YEAR'])
  })
})

describe('各来源各自的下钻明细视图与允许编辑（业务方 2026-10-04「加进去」）', () => {
  const view = (id: string, objectId: string): ApplicationResource => ({
    id,
    kind: ResourceKind.VIEW,
    name: id,
    code: id,
    config: { objectId, fieldIds: [], equal: {} }
  })
  it('来源卡片里只列本来源对象的视图；选了视图才能开「允许编辑」；保存带上；来源 1 的仍在原处可选', async () => {
    await mount(example1Config(), [view('stay-view', 'stay'), view('expense-view', 'expense')])
    const select = $<HTMLSelectElement>('[data-source-drill] select', card('s3'))
    expect(Array.from(select.querySelectorAll('option')).map(o => o.value)).toEqual(['', 'expense-view'])
    const editable = () => $<HTMLInputElement>('[data-source-editable] input', card('s3'))
    expect(editable().disabled).toBe(true)
    await pick(select, 'expense-view')
    expect(editable().disabled).toBe(false)
    editable().click()
    await flush()
    expect(state.config.extraSources![1]).toMatchObject({ detailViewId: 'expense-view', detailEditable: true })
    expect(host.querySelector('[data-multi-source-drill]')).toBeTruthy()
    const saved = save()
    expect(saved.extraSources![1]).toMatchObject({ detailViewId: 'expense-view', detailEditable: true })
    expect(saved.extraSources![0]).not.toHaveProperty('detailViewId')
    // 取消视图 ⇒ 允许编辑一并收回
    await pick(select, '')
    expect(state.config.extraSources![1].detailEditable).toBeNull()
  })
  // R6 衔接：laneDV 放开了「按明细行统计挂下钻视图并可编辑」，多来源里按明细行统计的来源同样放开（原「下钻视图禁用」这条随之改写）。
  const lineView: ApplicationResource = {
    id: 'line-view',
    kind: ResourceKind.VIEW,
    name: 'line-view',
    code: 'line-view',
    config: {
      objectId: 'voucher',
      fieldIds: [],
      equal: {},
      composition: { grain: 'DETAIL', detailId: 'voucher_lines', sections: [], columns: [] }
    }
  }
  it('明细粒度的来源：只列按同一明细逐行显示的视图，可选、可开允许编辑，保存带上', async () => {
    await mount(example3Config(), [view('voucher-view', 'voucher'), lineView])
    const select = $<HTMLSelectElement>('[data-source-drill] select', card('s2'))
    expect(select.disabled).toBe(false)
    expect(Array.from(select.querySelectorAll('option')).map(o => o.value)).toEqual(['', 'line-view'])
    expect($('[data-source-detail-drill]', card('s2')).textContent!.replace(/\s+/g, '')).toBe(
      '按明细行统计时，只能选「一行表示一条内部明细」且明细来源为「分录」的数据视图；不选则下钻显示命中的明细行（只读）。'
    )
    expect(card('s2').querySelector('[data-source-drill-error]')).toBeNull()
    await pick(select, 'line-view')
    const editable = $<HTMLInputElement>('[data-source-editable] input', card('s2'))
    expect(editable.disabled).toBe(false)
    editable.click()
    await flush()
    const saved = save()
    expect(saved.extraSources![0]).toMatchObject({ grain: 'DETAIL', detailViewId: 'line-view', detailEditable: true })
  })
  it('明细粒度的来源挂着一行一张凭证的视图（存量或视图后来被改）：显示服务端同一句话（加来源前缀）', async () => {
    const config = example3Config()
    config.extraSources![0] = { ...config.extraSources![0], detailViewId: 'voucher-view' }
    await mount(config, [view('voucher-view', 'voucher'), lineView])
    expect($('[data-source-drill-error]', card('s2')).textContent).toBe(
      '来源「贷方」：按明细行统计时，下钻明细视图须是按明细「分录」逐行显示的数据视图（视图设置里「一行表示」选「一条内部明细」、明细来源选「分录」）'
    )
  })
})
