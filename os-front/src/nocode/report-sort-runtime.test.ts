// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App, type Component } from 'vue'
import Antd from 'ant-design-vue'
import { defaultReport } from './report'
import { KeptPages } from './kept-pages'
import { invalidateRuntimeData } from './runtime-data'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig, ReportQuery, ReportResult, ReportSort } from '@/types/nocode/report'

const api = vi.hoisted(() => ({
  report: vi.fn(),
  reportDetails: vi.fn(),
  reportExport: vi.fn(),
  model: vi.fn(),
  viewModel: vi.fn(),
  page: vi.fn(),
  get: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-sort-test' } }) }))
vi.mock('@/views/nocode/application/components/ReportFilterInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
/** 下钻视图只看它收到的 reportDrill。 */
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    __esModule: true,
    default: defineComponent({
      props: ['reportDrill'],
      setup: props => () => h('div', { 'data-drill': JSON.stringify(props.reportDrill) })
    })
  }
})
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

const metrics = [
  { id: 'in', name: '入金', operation: 'SUM' as const, fieldId: 'in' },
  { id: 'out', name: '出金', operation: 'SUM' as const, fieldId: 'out' }
]
/** 三个日期：入金 60 / 140 / 200，出金 6 / 2 / 50。 */
const days = [
  { key: '2026-08-01', in: '60', out: '6' },
  { key: '2026-09-05', in: '140', out: '2' },
  { key: '2026-09-10', in: '200', out: '50' }
]
/** 模拟后端：按请求里的 sort 排好再返回（没有 sort 时按日期升序 = 配置的顺序）。 */
function ordered(sort?: ReportSort | null) {
  const list = [...days]
  if (sort?.metricId) {
    const id = sort.metricId as 'in' | 'out'
    list.sort((a, b) => Number(a[id]) - Number(b[id]))
  }
  if (sort?.descending) list.reverse()
  return list
}
const pivotResult = (sort?: ReportSort | null): ReportResult => {
  const list = ordered(sort)
  return {
    dimensionNames: ['日期'],
    metrics,
    groups: [],
    totals: { in: '400', out: '58' },
    totalGroups: 3,
    recordCount: 6,
    canExport: true,
    timeZone: 'Asia/Shanghai',
    pivot: {
      rowDimensionNames: ['日期'],
      columnDimensionNames: [],
      rows: list.map(d => ({ keys: [d.key], labels: [d.key] })),
      columns: [{ keys: [], labels: [] }],
      cells: [
        ...list.map(d => ({ rowKeys: [d.key], columnKeys: [], values: { in: d.in, out: d.out } })),
        { rowKeys: [], columnKeys: [], values: { in: '400', out: '58' } }
      ],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 3,
      totalColumnGroups: 1
    }
  }
}
const tableResult = (sort?: ReportSort | null, count = 3): ReportResult => {
  const list =
    count === 3
      ? ordered(sort)
      : Array.from({ length: count }, (_, i) => ({ key: 'D' + String(i).padStart(3, '0'), in: String(i), out: '1' }))
  return {
    dimensionNames: ['日期'],
    metrics,
    groups: list.map(d => ({ keys: [d.key], labels: [d.key], values: { in: d.in, out: d.out } })),
    totals: { in: '400', out: '58' },
    totalGroups: count,
    recordCount: 6,
    canExport: true,
    timeZone: 'Asia/Shanghai'
  }
}
function reportConfig(display: ReportConfig['display'], extra: Partial<ReportConfig> = {}): ReportConfig {
  return {
    ...defaultReport('flow'),
    display,
    metrics,
    dimensions: [{ fieldId: 'date', relationPath: null, bucket: 'VALUE' }],
    columnDimensions: [],
    pivot: null,
    ...extra
  }
}
const resource = (config: ReportConfig, id = 'report'): ApplicationResource => ({
  id,
  kind: ResourceKind.REPORT,
  name: '每日入出金',
  code: id,
  config: config as unknown as Record<string, unknown>
})
const drillView: ApplicationResource = {
  id: 'drill-view',
  kind: ResourceKind.VIEW,
  name: '流水明细',
  code: 'drill_view',
  config: { objectId: 'flow', fieldIds: [], equal: {}, sortFieldId: null, descending: true, pageSize: 20, formId: null }
}

// jsdom 没有 matchMedia；ant 的表格、栅格在挂载后读断点。
window.matchMedia ||= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => undefined,
  removeListener: () => undefined,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  dispatchEvent: () => false
})) as typeof window.matchMedia
const computedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle

let app: App | undefined, host: HTMLDivElement
async function flush() {
  for (let index = 0; index < 5; index++) {
    await vi.dynamicImportSettled()
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}
async function mount(component: Component, props: Record<string, unknown> = {}) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props))
  app.use(Antd)
  app.mount(host)
  await flush()
}
const mountReport = (config: ReportConfig, resources?: ApplicationResource[]) =>
  mount(ReportBlock, { applicationId: 'app', resource: resource(config), resources })
const lastQuery = () => api.report.mock.calls.at(-1)![0] as ReportQuery
const pivotHeads = () => Array.from(host.querySelectorAll<HTMLButtonElement>('.report-pivot thead button.pivot-sort'))
const pivotDates = () =>
  Array.from(host.querySelectorAll('.report-pivot tbody tr[data-row-level] th')).map(th => th.textContent!.trim())
const sortState = () => host.querySelector('[data-report-sort]')?.textContent?.replace(/\s+/g, '') ?? null
async function click(element: Element | null | undefined) {
  ;(element as HTMLElement).click()
  await flush()
}
const tableSorters = () =>
  Array.from(host.querySelectorAll<HTMLElement>('.ant-table-thead th.ant-table-column-has-sorters'))
const tableDates = () =>
  Array.from(host.querySelectorAll('.ant-table-tbody tr.ant-table-row td:first-child')).map(td =>
    td.textContent!.trim()
  )
const ariaSorts = () => Array.from(host.querySelectorAll('.ant-table-thead th')).map(th => th.getAttribute('aria-sort'))

beforeEach(() => {
  api.report.mockImplementation(async (query: ReportQuery) => pivotResult(query.sort))
  api.model.mockResolvedValue({
    writable: true,
    permissions: { actions: ['READ'], readFields: [], writeFields: [], readDetails: [], writeDetails: [] },
    object: {
      objectId: 'flow',
      objectName: '流水',
      titleFieldId: 'date',
      fields: [{ id: 'date', name: '日期', type: 'DATE' }],
      fieldOptions: {},
      details: [],
      relations: [],
      settings: {}
    },
    details: {}
  })
  api.reportDetails.mockResolvedValue({ list: [], total: 0 })
  api.reportExport.mockResolvedValue(new Blob(['x']))
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('透视表：点列头排序', () => {
  it('点指标列头：升序 → 降序 → 恢复默认；每次带着排序重新取数，不带排序时请求里没有 sort', async () => {
    await mountReport(reportConfig('PIVOT'))
    expect(api.report).toHaveBeenCalledTimes(1)
    expect(lastQuery()).not.toHaveProperty('sort')
    expect(pivotDates()).toEqual(['2026-08-01', '2026-09-05', '2026-09-10'])
    expect(sortState()).toBeNull()

    await click(pivotHeads()[2])
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(lastQuery().sort).toEqual({ metricId: 'out', columnGroup: [], descending: false })
    expect(pivotDates()).toEqual(['2026-09-05', '2026-08-01', '2026-09-10'])
    expect(sortState()).toContain('已按「出金」升序排列（只影响当前查看，不改变配置）')
    expect(host.querySelectorAll('.report-pivot thead th')[2].getAttribute('aria-sort')).toBe('ascending')

    await click(pivotHeads()[2])
    expect(lastQuery().sort).toEqual({ metricId: 'out', columnGroup: [], descending: true })
    expect(pivotDates()).toEqual(['2026-09-10', '2026-08-01', '2026-09-05'])
    expect(sortState()).toContain('已按「出金」降序排列')

    await click(pivotHeads()[2])
    expect(api.report).toHaveBeenCalledTimes(4)
    expect(lastQuery()).not.toHaveProperty('sort')
    expect(pivotDates()).toEqual(['2026-08-01', '2026-09-05', '2026-09-10'])
    expect(sortState()).toBeNull()
    // 换排序只重取统计结果，对象结构只在打开时取一次
    expect(api.model).toHaveBeenCalledTimes(1)
  })
  it('排序请求失败：显示错误、保留原来的结果，不转圈不清空', async () => {
    await mountReport(reportConfig('PIVOT'))
    api.report.mockRejectedValueOnce(new Error('排序指标不存在'))
    await click(pivotHeads()[1])
    expect(host.querySelector('.ant-alert-error')!.textContent).toContain('排序指标不存在')
    expect(pivotDates()).toEqual(['2026-08-01', '2026-09-05', '2026-09-10'])
    expect(host.querySelector('.ant-spin-spinning')).toBeNull()
  })
  it('点行维度列头按维度值排序；「恢复默认排序」回到配置的顺序', async () => {
    await mountReport(reportConfig('PIVOT'))
    await click(pivotHeads()[0])
    expect(lastQuery().sort).toEqual({ dimension: 0, descending: false })
    await click(pivotHeads()[0])
    expect(lastQuery().sort).toEqual({ dimension: 0, descending: true })
    expect(pivotDates()).toEqual(['2026-09-10', '2026-09-05', '2026-08-01'])
    expect(sortState()).toContain('已按「日期」降序排列')
    await click(host.querySelector('[data-report-sort-reset]'))
    expect(lastQuery()).not.toHaveProperty('sort')
    expect(sortState()).toBeNull()
    expect(pivotDates()).toEqual(['2026-08-01', '2026-09-05', '2026-09-10'])
  })
  it('排序之后点格子下钻：下钻的是那一行自己的分组，下钻参数不带排序', async () => {
    await mountReport(reportConfig('PIVOT', { detailViewId: 'drill-view' }), [drillView])
    await click(pivotHeads()[1])
    await click(pivotHeads()[1])
    // 入金降序：09-10、09-05、08-01。点第二行（排序前它在第二行的是 09-05，排序前的第一行 08-01 现在在最后）
    expect(pivotDates()).toEqual(['2026-09-10', '2026-09-05', '2026-08-01'])
    await click(host.querySelectorAll('.report-pivot tbody tr[data-row-level]')[2].querySelector('td button'))
    const drill = JSON.parse(document.querySelector<HTMLElement>('[data-drill]')!.dataset.drill!)
    expect(drill).toMatchObject({ reportId: 'report', group: ['2026-08-01'], columnGroup: [], metricId: 'in' })
    expect(drill).not.toHaveProperty('sort')
  })
  it('未配置下钻视图时的只读明细取数同样不带排序', async () => {
    await mountReport(reportConfig('PIVOT'))
    await click(pivotHeads()[2])
    // 出金升序：09-05、08-01、09-10。点最后一行
    await click(host.querySelectorAll('.report-pivot tbody tr[data-row-level]')[2].querySelector('td button'))
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
    expect(api.reportDetails.mock.calls[0][0]).toMatchObject({ group: ['2026-09-10'], metricId: 'in' })
    expect(api.reportDetails.mock.calls[0][0]).not.toHaveProperty('sort')
  })
  it('静默刷新（别人改了数据）保持当前的点击排序，不转圈', async () => {
    await mountReport(reportConfig('PIVOT'))
    await click(pivotHeads()[1])
    await click(pivotHeads()[1])
    const calls = api.report.mock.calls.length
    invalidateRuntimeData({ applicationId: 'app', objectId: 'flow' })
    await flush()
    expect(api.report).toHaveBeenCalledTimes(calls + 1)
    expect(lastQuery().sort).toEqual({ metricId: 'in', columnGroup: [], descending: true })
    expect(api.report.mock.calls.at(-1)![1]).toEqual({ quiet: true })
    expect(pivotDates()).toEqual(['2026-09-10', '2026-09-05', '2026-08-01'])
    expect(sortState()).toContain('已按「入金」降序排列')
    expect(host.querySelector('.ant-spin-spinning')).toBeNull()
  })
  it('切走再切回（保活页面）：排序还在，回来时的补取也带着它', async () => {
    const shown = ref('report')
    const block = resource(reportConfig('PIVOT'))
    await mount(
      defineComponent({
        setup: () => () =>
          h(KeptPages, { pageKey: shown.value, max: 5 }, () =>
            shown.value === 'report'
              ? h(ReportBlock, { key: 'report', applicationId: 'app', resource: block })
              : h('p', { key: 'other' }, '别的页面')
          )
      })
    )
    await click(pivotHeads()[2])
    const calls = api.report.mock.calls.length
    shown.value = 'other'
    await flush()
    invalidateRuntimeData({ applicationId: 'app', objectId: 'flow' })
    await flush()
    expect(api.report).toHaveBeenCalledTimes(calls)
    shown.value = 'report'
    await flush()
    expect(api.report).toHaveBeenCalledTimes(calls + 1)
    expect(lastQuery().sort).toEqual({ metricId: 'out', columnGroup: [], descending: false })
    expect(sortState()).toContain('已按「出金」升序排列')
    expect(pivotDates()).toEqual(['2026-09-05', '2026-08-01', '2026-09-10'])
  })
  it('点「刷新」保持排序；导出带同一个排序（与屏幕同序）', async () => {
    await mountReport(reportConfig('PIVOT'))
    await click(pivotHeads()[1])
    const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('.ant-card-extra button'))
    await click(buttons.find(b => b.textContent!.includes('刷新')))
    expect(lastQuery().sort).toEqual({ metricId: 'in', columnGroup: [], descending: false })
    const anchor = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    URL.createObjectURL ||= () => 'blob:test'
    URL.revokeObjectURL ||= () => undefined
    await click(
      Array.from(host.querySelectorAll<HTMLButtonElement>('.ant-card-extra button')).find(b =>
        b.textContent!.includes('导出')
      )
    )
    expect(api.reportExport).toHaveBeenCalledTimes(1)
    expect(api.reportExport.mock.calls[0][0].sort).toEqual({ metricId: 'in', columnGroup: [], descending: false })
    anchor.mockRestore()
  })
  it('换了一张统计：排序不带过去；指标已不存在的排序不再发送', async () => {
    const current = ref(resource(reportConfig('PIVOT')))
    await mount(
      defineComponent({ setup: () => () => h(ReportBlock, { applicationId: 'app', resource: current.value }) })
    )
    await click(pivotHeads()[2])
    expect(lastQuery().sort).toMatchObject({ metricId: 'out' })
    // 同一张统计重新发布后少了「出金」
    current.value = resource(reportConfig('PIVOT', { metrics: [metrics[0]] }))
    await flush()
    expect(sortState()).toBeNull()
    await click(
      Array.from(host.querySelectorAll<HTMLButtonElement>('.ant-card-extra button')).find(b =>
        b.textContent!.includes('刷新')
      )
    )
    expect(lastQuery()).not.toHaveProperty('sort')
    // 换到另一张统计
    await click(pivotHeads()[1])
    expect(lastQuery().sort).toMatchObject({ metricId: 'in' })
    current.value = resource(reportConfig('PIVOT'), 'other')
    await flush()
    expect(lastQuery().reportId).toBe('other')
    expect(lastQuery()).not.toHaveProperty('sort')
    expect(sortState()).toBeNull()
  })
})

describe('汇总表：点列头排序与翻页', () => {
  beforeEach(() => {
    api.report.mockImplementation(async (query: ReportQuery) => tableResult(query.sort))
  })
  it('列头可排序（服务端）：升序 → 降序 → 恢复默认；当前列标出方向', async () => {
    await mountReport(reportConfig('TABLE'))
    expect(tableSorters()).toHaveLength(3)
    expect(tableDates()).toEqual(['2026-08-01', '2026-09-05', '2026-09-10'])
    expect(lastQuery()).not.toHaveProperty('sort')

    await click(tableSorters()[2])
    expect(lastQuery().sort).toEqual({ metricId: 'out', columnGroup: [], descending: false })
    expect(tableDates()).toEqual(['2026-09-05', '2026-08-01', '2026-09-10'])
    expect(ariaSorts().slice(0, 3)).toEqual([null, null, 'ascending'])
    expect(sortState()).toContain('已按「出金」升序排列')

    await click(tableSorters()[2])
    expect(lastQuery().sort).toEqual({ metricId: 'out', columnGroup: [], descending: true })
    expect(tableDates()).toEqual(['2026-09-10', '2026-08-01', '2026-09-05'])
    expect(ariaSorts().slice(0, 3)).toEqual([null, null, 'descending'])

    await click(tableSorters()[2])
    expect(lastQuery()).not.toHaveProperty('sort')
    expect(tableDates()).toEqual(['2026-08-01', '2026-09-05', '2026-09-10'])
    expect(sortState()).toBeNull()

    await click(tableSorters()[0])
    expect(lastQuery().sort).toEqual({ dimension: 0, descending: false })
    await click(tableSorters()[0])
    expect(lastQuery().sort).toEqual({ dimension: 0, descending: true })
    expect(tableDates()).toEqual(['2026-09-10', '2026-09-05', '2026-08-01'])
    expect(sortState()).toContain('已按「日期」降序排列')
  })
  it('一万行不逐行建 DOM（分页）；翻页不重新取数、不丢排序；每页条数可调', async () => {
    api.report.mockImplementation(async (query: ReportQuery) => tableResult(query.sort, 10000))
    await mountReport(reportConfig('TABLE'))
    expect(host.querySelectorAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(10)
    expect(host.querySelector('.ant-pagination-total-text')!.textContent).toBe('共 10000 行')
    await click(tableSorters()[1])
    await click(tableSorters()[1])
    const calls = api.report.mock.calls.length
    expect(lastQuery().sort).toEqual({ metricId: 'in', columnGroup: [], descending: true })
    await click(host.querySelector('.ant-pagination-item-2'))
    expect(api.report).toHaveBeenCalledTimes(calls)
    expect(tableDates()[0]).toBe('D010')
    expect(ariaSorts().slice(0, 3)).toEqual([null, 'descending', null])
    expect(sortState()).toContain('已按「入金」降序排列')
    expect(host.querySelector('.ant-pagination-options')).not.toBeNull()
    // 静默刷新：停在第 2 页，排序还在
    invalidateRuntimeData({ applicationId: 'app', objectId: 'flow' })
    await flush()
    expect(api.report).toHaveBeenCalledTimes(calls + 1)
    expect(lastQuery().sort).toEqual({ metricId: 'in', columnGroup: [], descending: true })
    expect(host.querySelector('.ant-pagination-item-active')!.textContent).toBe('2')
    // 换排序回到第 1 页
    await click(tableSorters()[2])
    expect(host.querySelector('.ant-pagination-item-active')!.textContent).toBe('1')
    // 一万行的真实 ant 表格在 jsdom 里每次重渲染要几百毫秒，这条用例连点带翻页有八次；机器忙时会超过默认的 5 秒
  }, 30000)
  it('不限制且被保护值截断时写明「共 M 行，只显示前 N 行」；填了数字时保留原有提示', async () => {
    api.report.mockImplementation(async () => ({ ...tableResult(null), totalGroups: 20050 }))
    await mountReport(reportConfig('TABLE'))
    const notice = host.querySelector('[data-report-truncated]')!.textContent!
    expect(notice).toContain('共 20050 行，只显示前 3 行')
    expect(notice).toContain('总体指标和全部明细仍按完整条件计算')
    app!.unmount()
    document.body.innerHTML = ''
    await mountReport(reportConfig('TABLE', { limit: 3 }))
    expect(host.querySelector('[data-report-truncated]')!.textContent).toContain('当前只展示设定数量的分组')
  })
  it('图表的「表格」视图不开放点列头排序（图表行为不变）', async () => {
    await mountReport(reportConfig('BAR'))
    const toggle = Array.from(host.querySelectorAll<HTMLButtonElement>('.ant-card-extra button')).find(b =>
      b.textContent!.includes('表格')
    )
    await click(toggle)
    expect(host.querySelectorAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(3)
    expect(tableSorters()).toHaveLength(0)
    expect(lastQuery()).not.toHaveProperty('sort')
  })
})
