// @vitest-environment jsdom
// 透视表与运行端数据视图列表共用一组样式值（style.css 的 --data-table-*）：透视表这边引用变量、不另写字面值；
// 行很多、只画看得见的行时，每行与普通透视表一样高 —— 行高常数由这组变量算出，挂载后按带小数的实际行高校准。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import ReportPivotTable from '@/views/nocode/application/components/ReportPivotTable.vue'
import { defaultReport } from './report'
import { PIVOT_ROW_HEIGHT, PIVOT_WINDOW_OVERSCAN } from './report-pivot'
import {
  ReportDisplay,
  type ReportConfig,
  type ReportMetric,
  type ReportPivotResult,
  type ReportResult
} from '@/types/nocode/report'

const source = (path: string) => readFileSync(fileURLToPath(new URL('../' + path, import.meta.url)), 'utf8')
const rootStyle = source('style.css'),
  pivotSource = source('views/nocode/application/components/ReportPivotTable.vue'),
  pivotStyle = pivotSource.slice(pivotSource.indexOf('<style'))

/** 某条样式规则里某个属性的值（按选择器原文找规则）。 */
function declared(text: string, selector: string, property: string) {
  const start = text.indexOf(selector + ' {')
  if (start < 0) throw new Error('没有这条规则：' + selector)
  const block = text.slice(start, text.indexOf('}', start))
  return block.match(new RegExp('(?:^|[;{\\s])' + property + ':\\s*([^;]+);'))?.[1]?.trim()
}
const variable = (name: string) => declared(rootStyle, ':root', name)!

describe('透视表引用 --data-table-* 变量', () => {
  it('字号、行高、内边距、表头字重都写成变量引用', () => {
    expect(declared(pivotStyle, '.pivot-table', 'font-size')).toBe('var(--data-table-font-size)')
    expect(declared(pivotStyle, '.pivot-table', 'line-height')).toBe('var(--data-table-line-height)')
    expect(declared(pivotStyle, '.pivot-table td', 'padding')).toBe('var(--data-table-cell-padding)')
    expect(declared(pivotStyle, '.pivot-head', 'font-weight')).toBe('var(--data-table-header-font-weight)')
  })

  it('只画看得见的行时不另设行高：每行与普通透视表同高', () => {
    const rules = pivotStyle.split('}').filter(rule => rule.includes('.pivot-table--windowed'))
    for (const rule of rules) expect(rule).not.toMatch(/line-height\s*:/)
  })

  it('行高常数 = 字号 × 行高 + 上下内边距 + 下边框 1px', () => {
    const [vertical] = variable('--data-table-cell-padding').split(/\s+/)
    const expected =
      Number.parseFloat(variable('--data-table-font-size')) * Number(variable('--data-table-line-height')) +
      Number.parseFloat(vertical!) * 2 +
      1
    expect(expected).toBe(32.5)
    expect(PIVOT_ROW_HEIGHT).toBe(expected)
  })
})

const metrics: ReportMetric[] = [
  { id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount', format: { decimals: 0 } }
]
const config: ReportConfig = {
  ...defaultReport('flow'),
  display: ReportDisplay.PIVOT,
  metrics,
  dimensions: [{ fieldId: 'date', relationPath: null, bucket: 'VALUE' }],
  columnDimensions: [],
  pivot: { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 }
}
function byDate(count: number): ReportPivotResult {
  const key = (i: number) => 'D' + String(i).padStart(5, '0')
  return {
    rowDimensionNames: ['日期'],
    columnDimensionNames: [],
    rows: Array.from({ length: count }, (_, i) => ({ keys: [key(i)], labels: [key(i)] })),
    columns: [{ keys: [], labels: [] }],
    cells: Array.from({ length: count }, (_, i) => ({ rowKeys: [key(i)], columnKeys: [], values: { in: String(i) } })),
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: count,
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
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.restoreAllMocks()
})

describe('只画看得见的行：按带小数的实际行高校准', () => {
  it('实际行高是 31.25px 时，占位高度与窗口大小都按 31.25 算，不取整', async () => {
    const actual = 31.25
    expect(actual).not.toBe(PIVOT_ROW_HEIGHT)
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const height = this.matches('tbody tr[data-row-level]') ? actual : 0
      return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: height, width: 0, height, toJSON: () => ({}) }
    })
    const pivot = byDate(10000)
    app = createApp(() => h(ReportPivotTable, { config, result: result(pivot), pivot }))
    app.component('AAlert', defineComponent({ setup: () => () => h('aside') }))
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
    for (let i = 0; i < 5; i++) {
      await Promise.resolve()
      await nextTick()
    }
    const size = Math.ceil(560 / actual) + PIVOT_WINDOW_OVERSCAN * 2
    expect(host.querySelectorAll('tbody tr[data-row-level]')).toHaveLength(size)
    const spacers = Array.from(host.querySelectorAll<HTMLElement>('tbody tr.pivot-spacer td'))
    expect(spacers).toHaveLength(1)
    expect(spacers[0]!.style.height).toBe((10000 - size) * actual + 'px')
  })
})
