// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import ReportPivotTable from '@/views/nocode/application/components/ReportPivotTable.vue'
import { defaultReport } from './report'
import { PIVOT_ROW_HEIGHT, PIVOT_WINDOW_OVERSCAN, PIVOT_WINDOW_THRESHOLD, type PivotSelection } from './report-pivot'
import type { ReportSortTarget } from './report-sort'
import {
  ReportDisplay,
  type ReportConfig,
  type ReportMetric,
  type ReportPivotCell,
  type ReportPivotResult,
  type ReportResult,
  type ReportSort
} from '@/types/nocode/report'

const metrics: ReportMetric[] = [
  { id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount', format: { decimals: 0 } },
  { id: 'out', name: '出金', operation: 'SUM', fieldId: 'amount', format: { decimals: 0 } }
]
const config = (extra: Partial<ReportConfig> = {}): ReportConfig => ({
  ...defaultReport('flow'),
  display: ReportDisplay.PIVOT,
  metrics,
  dimensions: [{ fieldId: 'date', relationPath: null, bucket: 'VALUE' }],
  columnDimensions: [],
  pivot: { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 },
  ...extra
})
const day = (i: number) => 'D' + String(i).padStart(5, '0')
/** 业务方那张统计的形状：行 = 日期，无列维度（后端给一个空列组），入金 / 出金。第 i 行入金 = i、出金 = 2i。 */
function byDate(count: number, truncatedFrom?: number): ReportPivotResult {
  const cells: ReportPivotCell[] = []
  for (let i = 0; i < count; i++)
    cells.push({ rowKeys: [day(i)], columnKeys: [], values: { in: String(i), out: String(2 * i) } })
  cells.push({ rowKeys: [], columnKeys: [], values: { in: '49995000', out: '99990000' } })
  return {
    rowDimensionNames: ['日期'],
    columnDimensionNames: [],
    rows: Array.from({ length: count }, (_, i) => ({ keys: [day(i)], labels: [day(i)] })),
    columns: [{ keys: [], labels: [] }],
    cells,
    rowsTruncated: truncatedFrom != null,
    columnsTruncated: false,
    totalRowGroups: truncatedFrom ?? count,
    totalColumnGroups: 1
  }
}
/** 两层行维度：公司（groups 个）→ 日期（每个公司 per 个）。 */
function nested(groups: number, per: number): ReportPivotResult {
  const rows = [],
    cells: ReportPivotCell[] = []
  for (let g = 0; g < groups; g++) {
    for (let i = 0; i < per; i++) {
      rows.push({ keys: ['C' + g, day(i)], labels: ['公司' + g, day(i)] })
      cells.push({ rowKeys: ['C' + g, day(i)], columnKeys: [], values: { in: String(g * 1000 + i), out: '1' } })
    }
    cells.push({ rowKeys: ['C' + g], columnKeys: [], values: { in: String(g), out: String(per) } })
  }
  cells.push({ rowKeys: [], columnKeys: [], values: { in: '7', out: '8' } })
  return {
    rowDimensionNames: ['公司', '日期'],
    columnDimensionNames: [],
    rows,
    columns: [{ keys: [], labels: [] }],
    cells,
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: rows.length,
    totalColumnGroups: 1
  }
}
const result = (pivot: ReportPivotResult): ReportResult => ({
  dimensionNames: pivot.rowDimensionNames,
  metrics,
  groups: [],
  totals: {},
  totalGroups: pivot.totalRowGroups,
  recordCount: 3,
  canExport: false,
  timeZone: 'Asia/Shanghai',
  pivot
})

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
interface Mounted {
  select: ReturnType<typeof vi.fn<(selection: PivotSelection) => void>>
  sort: ReturnType<typeof vi.fn<(target: ReportSortTarget) => void>>
  props: { sort: ReportSort | null; pivot: ReportPivotResult }
}
async function mount(
  pivot: ReportPivotResult,
  options: { config?: ReportConfig; sortable?: boolean; sort?: ReportSort | null } = {}
): Promise<Mounted> {
  const select = vi.fn<(selection: PivotSelection) => void>(),
    sort = vi.fn<(target: ReportSortTarget) => void>()
  const props = reactive({ sort: options.sort ?? null, pivot }) as Mounted['props']
  app = createApp(() =>
    h(ReportPivotTable, {
      config: options.config ?? config(),
      result: result(props.pivot),
      pivot: props.pivot,
      sortable: options.sortable,
      sort: props.sort,
      onSelect: select,
      onSort: sort
    })
  )
  app.component(
    'AAlert',
    defineComponent({ props: ['message'], setup: p => () => h('aside', { role: 'alert' }, p.message) })
  )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', { type: 'button' }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { select, sort, props }
}
const scroller = () => host.querySelector<HTMLElement>('.pivot-scroll')!
const dataRows = () => Array.from(host.querySelectorAll<HTMLElement>('tbody tr[data-row-level]'))
const spacers = () => Array.from(host.querySelectorAll<HTMLElement>('tbody tr.pivot-spacer td'))
const firstHead = (row: Element) => row.querySelector('th')!.textContent!.trim()
const values = (row: Element) => Array.from(row.querySelectorAll('td')).map(td => td.textContent!.trim())
async function scrollTo(top: number) {
  scroller().scrollTop = top
  scroller().dispatchEvent(new Event('scroll'))
  await flush()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('透视表行很多时只渲染看得见的行', () => {
  it('一万行：不逐行建 DOM，用占位行撑开滚动条；表头与合计行照常在', async () => {
    const started = performance.now()
    await mount(byDate(10000))
    const elapsed = performance.now() - started
    const window = Math.ceil(560 / PIVOT_ROW_HEIGHT) + PIVOT_WINDOW_OVERSCAN * 2
    expect(dataRows()).toHaveLength(window)
    expect(host.querySelectorAll('tbody tr')).toHaveLength(window + 1)
    expect(host.querySelectorAll('tbody td').length).toBeLessThan(200)
    expect(firstHead(dataRows()[0])).toBe(day(0))
    // 只有下方占位：高度 = 没渲染的行数 × 行高
    expect(spacers()).toHaveLength(1)
    expect(spacers()[0].style.height).toBe((10000 - window) * PIVOT_ROW_HEIGHT + 'px')
    expect(spacers()[0].getAttribute('colspan')).toBe('3')
    expect(host.querySelector('table')!.classList.contains('pivot-table--windowed')).toBe(true)
    // 表头、合计行不在窗口里，始终渲染
    expect(Array.from(host.querySelectorAll('thead th')).map(th => th.textContent!.trim())).toEqual([
      '日期',
      '入金',
      '出金'
    ])
    expect(values(host.querySelector('tfoot tr')!)).toEqual(['49,995,000', '99,990,000'])
    // 挂载一万行不应是秒级（逐行建 DOM 时是）
    expect(elapsed).toBeLessThan(1500)
  })
  it('滚动后换成对应位置的行；滚到底能看到最后一行', async () => {
    await mount(byDate(10000))
    const window = dataRows().length
    await scrollTo(5000 * PIVOT_ROW_HEIGHT)
    expect(dataRows()).toHaveLength(window)
    expect(firstHead(dataRows()[0])).toBe(day(5000 - PIVOT_WINDOW_OVERSCAN))
    expect(dataRows().map(firstHead)).toContain(day(5000))
    expect(values(dataRows()[PIVOT_WINDOW_OVERSCAN])).toEqual(['5,000', '10,000'])
    expect(spacers().map(td => td.style.height)).toEqual([
      (5000 - PIVOT_WINDOW_OVERSCAN) * PIVOT_ROW_HEIGHT + 'px',
      (10000 - (5000 - PIVOT_WINDOW_OVERSCAN) - window) * PIVOT_ROW_HEIGHT + 'px'
    ])
    await scrollTo(10000 * PIVOT_ROW_HEIGHT)
    expect(firstHead(dataRows().at(-1)!)).toBe(day(9999))
    expect(spacers()).toHaveLength(1)
    expect(spacers()[0].style.height).toBe((10000 - window) * PIVOT_ROW_HEIGHT + 'px')
    expect(host.querySelector('tfoot tr th')!.textContent!.trim()).toBe('合计')
  })
  it('窗口里的格子下钻发出的是那一行自己的键', async () => {
    const { select } = await mount(byDate(10000))
    await scrollTo(7000 * PIVOT_ROW_HEIGHT)
    const row = dataRows().find(r => firstHead(r) === day(7003))!
    row.querySelectorAll<HTMLButtonElement>('td button')[1].click()
    expect(select).toHaveBeenCalledWith({
      rowKeys: [day(7003)],
      columnKeys: [],
      metricId: 'out',
      label: day(7003),
      values: { in: '7003', out: '14006' }
    })
  })
  it('行数不超过阈值时整表渲染，没有占位行（与原先一致）', async () => {
    await mount(byDate(PIVOT_WINDOW_THRESHOLD))
    expect(dataRows()).toHaveLength(PIVOT_WINDOW_THRESHOLD)
    expect(spacers()).toHaveLength(0)
    expect(host.querySelector('table')!.classList.contains('pivot-table--windowed')).toBe(false)
  })
  it('多层行维度：窗口首行补上延续的上层行表头，跨度不超出窗口；小计行跟着分组', async () => {
    // 5 个公司 × 300 个日期：每个公司 300 叶子 + 1 小计 = 301 行
    await mount(nested(5, 300))
    await scrollTo(450 * PIVOT_ROW_HEIGHT)
    const rows = dataRows()
    const first = rows[0]
    // 第 435 行属于公司 1（第 301..601 行）：首行带「公司1」的行表头
    const heads = Array.from(first.querySelectorAll('th'))
    expect(heads.map(th => th.textContent!.replace(/[▾▸]/g, '').trim())).toEqual([
      '公司1',
      day(450 - PIVOT_WINDOW_OVERSCAN - 301)
    ])
    expect(Number(heads[0].getAttribute('rowspan'))).toBe(rows.length)
    expect(rows.slice(1).every(r => r.querySelectorAll('th').length === 1)).toBe(true)
    // 滚到公司 1 的小计附近：小计行紧随其最后一个叶子，下一行是公司 2 的第一行
    await scrollTo(590 * PIVOT_ROW_HEIGHT)
    const levels = dataRows().map(r => r.dataset.rowLevel)
    const subtotal = levels.indexOf('subtotal')
    expect(subtotal).toBeGreaterThan(0)
    expect(firstHead(dataRows()[subtotal])).toBe('小计')
    expect(values(dataRows()[subtotal])).toEqual(['1', '300'])
    const next = Array.from(dataRows()[subtotal + 1].querySelectorAll('th'))
    expect(next.map(th => th.textContent!.replace(/[▾▸]/g, '').trim())).toEqual(['公司2', day(0)])
    expect(Number(next[0].getAttribute('rowspan'))).toBe(dataRows().length - subtotal - 1)
  })
  it('不限制且被保护值截断时写明「共 M 行，只显示前 N 行」；填了数字的截断只保留原有提示', async () => {
    await mount(byDate(300, 20050))
    expect(host.querySelector('[data-pivot-truncated="rows"]')!.textContent).toBe('行仅显示前 300 个，共 20050 个')
    const guard = host.querySelector('[data-pivot-row-guard]')!.textContent!.replace(/\s+/g, '')
    expect(guard).toContain('共20050行，只显示前300行')
    expect(guard).toContain('小计与合计仍按全部数据计算')
    app!.unmount()
    host.remove()
    await mount(byDate(30, 500), { config: config({ limit: 30 }) })
    expect(host.querySelector('[data-pivot-truncated="rows"]')!.textContent).toBe('行仅显示前 30 个，共 500 个')
    expect(host.querySelector('[data-pivot-row-guard]')).toBeNull()
  })
})

describe('透视表列头排序', () => {
  it('不开启时（配置预览）表头是纯文字，没有按钮', async () => {
    await mount(byDate(3))
    expect(host.querySelectorAll('thead button')).toHaveLength(0)
    expect(Array.from(host.querySelectorAll('thead th')).map(th => th.textContent!.trim())).toEqual([
      '日期',
      '入金',
      '出金'
    ])
  })
  it('开启后行维度列头与指标列头可点，发出排序目标；当前排序的列标出方向', async () => {
    const { sort, props } = await mount(byDate(3), { sortable: true })
    const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('thead button.pivot-sort'))
    expect(buttons.map(b => b.textContent!.replace(/[↕▲▼]/g, '').trim())).toEqual(['日期', '入金', '出金'])
    expect(Array.from(host.querySelectorAll('thead th')).map(th => th.getAttribute('aria-sort'))).toEqual([
      null,
      null,
      null
    ])
    buttons[1].click()
    expect(sort).toHaveBeenLastCalledWith({ metricId: 'in', columnGroup: [] })
    buttons[0].click()
    expect(sort).toHaveBeenLastCalledWith({ dimension: 0 })
    // 父组件把排序状态传回来：对应列头显示方向
    props.sort = { metricId: 'in', columnGroup: [], descending: true }
    await flush()
    const heads = Array.from(host.querySelectorAll('thead th'))
    expect(heads.map(th => th.getAttribute('aria-sort'))).toEqual([null, 'descending', null])
    expect(heads[1].querySelector('.pivot-sort-mark')!.textContent).toBe('▼')
    props.sort = { dimension: 0, descending: false }
    await flush()
    expect(Array.from(host.querySelectorAll('thead th')).map(th => th.getAttribute('aria-sort'))).toEqual([
      'ascending',
      null,
      null
    ])
    expect(host.querySelector('thead th .pivot-sort-mark')!.textContent).toBe('▲')
  })
  it('多个列组：点某个列组下的指标，排序目标带上那个列组的列键；合计列组为空列键', async () => {
    const pivot: ReportPivotResult = {
      rowDimensionNames: ['公司'],
      columnDimensionNames: ['月份'],
      rows: [{ keys: ['A'], labels: ['甲'] }],
      columns: [
        { keys: ['2026-08'], labels: ['2026-08'] },
        { keys: ['2026-09'], labels: ['2026-09'] }
      ],
      cells: [{ rowKeys: ['A'], columnKeys: ['2026-08'], values: { in: '1', out: '2' } }],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 1,
      totalColumnGroups: 2
    }
    const { sort, props } = await mount(pivot, {
      sortable: true,
      config: config({ columnDimensions: [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }] })
    })
    const metricsRow = Array.from(host.querySelectorAll('thead tr')[1].querySelectorAll<HTMLButtonElement>('button'))
    expect(metricsRow).toHaveLength(6)
    metricsRow[3].click()
    expect(sort).toHaveBeenLastCalledWith({ metricId: 'out', columnGroup: ['2026-09'] })
    metricsRow[4].click()
    expect(sort).toHaveBeenLastCalledWith({ metricId: 'in', columnGroup: [] })
    // 列组表头（月份、合计）本身不可点
    expect(host.querySelectorAll('thead tr')[0].querySelectorAll('button')).toHaveLength(1)
    props.sort = { metricId: 'out', columnGroup: ['2026-09'], descending: false }
    await flush()
    const marks = Array.from(host.querySelectorAll('thead tr')[1].querySelectorAll('th')).map(th =>
      th.getAttribute('aria-sort')
    )
    expect(marks).toEqual([null, null, null, 'ascending', null, null])
  })
  it('换了排序回到表格顶部；数据静默刷新（排序没变）保持滚动位置', async () => {
    const { props } = await mount(byDate(10000), { sortable: true })
    await scrollTo(3000 * PIVOT_ROW_HEIGHT)
    expect(firstHead(dataRows()[0])).toBe(day(3000 - PIVOT_WINDOW_OVERSCAN))
    props.pivot = byDate(10000)
    await flush()
    expect(scroller().scrollTop).toBe(3000 * PIVOT_ROW_HEIGHT)
    expect(firstHead(dataRows()[0])).toBe(day(3000 - PIVOT_WINDOW_OVERSCAN))
    props.sort = { metricId: 'in', columnGroup: [], descending: true }
    await flush()
    expect(scroller().scrollTop).toBe(0)
    expect(firstHead(dataRows()[0])).toBe(day(0))
  })
})
