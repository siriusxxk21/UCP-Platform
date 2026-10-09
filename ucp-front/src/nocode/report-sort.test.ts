import { describe, expect, it } from 'vitest'
import { nextReportSort, reportSortDirection, sameSortTarget, validReportSort } from './report-sort'
import {
  MAX_REPORT_CHART_GROUPS,
  MAX_REPORT_PIVOT_CELLS,
  MAX_REPORT_TABLE_ROWS,
  defaultReport,
  reportLimitMax,
  reportSortDescending,
  reportTableDisplay
} from './report'
import { validateReport } from './report-presentation'
import { prepareReportConfig } from './resource-config'
import {
  PIVOT_ROW_HEIGHT,
  PIVOT_WINDOW_OVERSCAN,
  pivotBodyRows,
  pivotHeaderRows,
  pivotRowSpans,
  pivotRowWindow,
  pivotWindowRows
} from './report-pivot'
import { ReportDisplay, type ReportConfig, type ReportPivotResult } from '@/types/nocode/report'

const base = (extra: Partial<ReportConfig> = {}): ReportConfig => ({
  ...defaultReport('flow'),
  display: ReportDisplay.PIVOT,
  dimensions: [{ fieldId: 'date', relationPath: null, bucket: 'VALUE' }],
  metrics: [
    { id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount' },
    { id: 'out', name: '出金', operation: 'SUM', fieldId: 'amount' }
  ],
  ...extra
})

describe('点列头排序的三态', () => {
  it('同一列：升序 → 降序 → 恢复默认；换一列从升序重新开始', () => {
    const income = { metricId: 'in', columnGroup: ['2026-09'] }
    const first = nextReportSort(null, income)
    expect(first).toEqual({ metricId: 'in', columnGroup: ['2026-09'], descending: false })
    const second = nextReportSort(first, income)
    expect(second).toEqual({ metricId: 'in', columnGroup: ['2026-09'], descending: true })
    expect(nextReportSort(second, income)).toBeNull()
    // 同一个指标、另一个列组算另一列
    expect(nextReportSort(second, { metricId: 'in', columnGroup: ['2026-08'] })).toEqual({
      metricId: 'in',
      columnGroup: ['2026-08'],
      descending: false
    })
    // 行维度列
    const dimension = nextReportSort(second, { dimension: 1 })
    expect(dimension).toEqual({ dimension: 1, descending: false })
    expect(nextReportSort(dimension, { dimension: 1 })).toEqual({ dimension: 1, descending: true })
    expect(nextReportSort({ dimension: 1, descending: true }, { dimension: 1 })).toBeNull()
  })
  it('行合计列组：缺省列键与空数组是同一列', () => {
    expect(sameSortTarget({ metricId: 'in', descending: false }, { metricId: 'in', columnGroup: [] })).toBe(true)
    expect(sameSortTarget({ metricId: 'in', columnGroup: [], descending: true }, { metricId: 'in' })).toBe(true)
    expect(sameSortTarget({ metricId: 'in', columnGroup: ['a'], descending: true }, { metricId: 'in' })).toBe(false)
    expect(sameSortTarget({ dimension: 0, descending: true }, { metricId: 'in' })).toBe(false)
    expect(sameSortTarget(null, { dimension: 0 })).toBe(false)
  })
  it('列头状态：只有当前排序的那一列有方向', () => {
    const sort = { metricId: 'out', columnGroup: [], descending: true }
    expect(reportSortDirection(sort, { metricId: 'out', columnGroup: [] })).toBe('descending')
    expect(reportSortDirection({ ...sort, descending: false }, { metricId: 'out' })).toBe('ascending')
    expect(reportSortDirection(sort, { metricId: 'in' })).toBeUndefined()
    expect(reportSortDirection(sort, { dimension: 0 })).toBeUndefined()
    expect(reportSortDirection(null, { dimension: 0 })).toBeUndefined()
  })
  it('配置变了以后不成立的排序当作没点过', () => {
    const config = base()
    expect(validReportSort({ metricId: 'in', descending: true }, config)).toEqual({ metricId: 'in', descending: true })
    expect(validReportSort({ metricId: 'gone', descending: true }, config)).toBeNull()
    expect(validReportSort({ dimension: 0, descending: false }, config)).toEqual({ dimension: 0, descending: false })
    expect(validReportSort({ dimension: 1, descending: false }, config)).toBeNull()
    expect(validReportSort({ descending: false }, config)).toBeNull()
    expect(validReportSort(null, config)).toBeNull()
  })
})

describe('配置的排序方向与行数上限', () => {
  it('存量透视表没选排序指标时倒序不生效（显示为升序）；其余情况取 descending', () => {
    expect(reportSortDescending(base({ descending: true }))).toBe(false)
    expect(reportSortDescending(base({ descending: true, sortBy: 'DIMENSION' }))).toBe(true)
    expect(reportSortDescending(base({ descending: true, sortMetricId: 'in' }))).toBe(true)
    expect(reportSortDescending(base({ descending: true, display: ReportDisplay.TABLE }))).toBe(true)
    expect(reportSortDescending(base({ descending: true, display: ReportDisplay.BAR }))).toBe(true)
    expect(reportSortDescending(base({ descending: false, sortBy: 'DIMENSION' }))).toBe(false)
  })
  it('「不限制」只对汇总表与透视表有效；新建统计默认不限制', () => {
    expect(defaultReport('flow').limit).toBeNull()
    expect(defaultReport('flow').sortBy).toBeNull()
    expect([ReportDisplay.TABLE, ReportDisplay.PIVOT].map(reportTableDisplay)).toEqual([true, true])
    expect(
      [ReportDisplay.METRIC, ReportDisplay.BAR, ReportDisplay.LINE, ReportDisplay.PIE].map(reportTableDisplay)
    ).toEqual([false, false, false, false])
    expect(reportLimitMax(ReportDisplay.PIVOT)).toBe(MAX_REPORT_TABLE_ROWS)
    expect(reportLimitMax(ReportDisplay.TABLE)).toBe(MAX_REPORT_TABLE_ROWS)
    expect(reportLimitMax(ReportDisplay.BAR)).toBe(MAX_REPORT_CHART_GROUPS)
    expect(MAX_REPORT_CHART_GROUPS).toBe(200)
    expect(MAX_REPORT_TABLE_ROWS).toBe(20000)
    expect(MAX_REPORT_PIVOT_CELLS).toBe(100000)
  })
  it('保存校验：留空通过；表格上限 20000、图表上限 200；排序依据与排序指标要一致', () => {
    const table = (extra: Partial<ReportConfig>) => base({ display: ReportDisplay.TABLE, ...extra })
    const bar = (extra: Partial<ReportConfig>) => base({ display: ReportDisplay.BAR, ...extra })
    expect(() => validateReport(base({ limit: null }))).not.toThrow()
    expect(() => validateReport(bar({ limit: null }))).not.toThrow()
    expect(() => validateReport(table({ limit: MAX_REPORT_TABLE_ROWS }))).not.toThrow()
    expect(() => validateReport(table({ limit: MAX_REPORT_TABLE_ROWS + 1 }))).toThrow('留空表示不限制')
    expect(() => validateReport(base({ limit: 0 }))).toThrow('最多展示行数须为 1～20000 的整数')
    expect(() => validateReport(base({ limit: 1.5 }))).toThrow('最多展示行数须为 1～20000 的整数')
    expect(() => validateReport(bar({ limit: 200 }))).not.toThrow()
    expect(() => validateReport(bar({ limit: 201 }))).toThrow('展示组数须为 1～200 的整数')
    expect(() => validateReport(base({ sortBy: 'METRIC' }))).toThrow('按指标排序需要选择排序指标')
    expect(() => validateReport(base({ sortBy: 'DIMENSION', sortMetricId: 'in' }))).toThrow('不能同时设置排序指标')
    expect(() => validateReport(base({ sortBy: 'METRIC', sortMetricId: 'in' }))).not.toThrow()
  })
  it('保存规范化：存量配置不凭空多出排序依据；新配置的排序依据、空行数、列组降序原样保存', () => {
    const legacy = base({ descending: true, limit: 30 })
    delete (legacy as Partial<ReportConfig>).sortBy
    const saved = prepareReportConfig(legacy, [], [])
    expect(saved.sortBy).toBeNull()
    expect(saved.descending).toBe(true)
    expect(saved.limit).toBe(30)
    expect(saved.pivot).toEqual({
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE',
      maxColumnGroups: 24
    })
    const current = prepareReportConfig(
      base({
        sortBy: 'DIMENSION',
        descending: true,
        limit: null,
        pivot: {
          subtotals: true,
          rowTotals: true,
          columnTotals: true,
          percent: 'NONE',
          maxColumnGroups: 24,
          columnDescending: true
        }
      }),
      [],
      []
    )
    expect(current.sortBy).toBe('DIMENSION')
    expect(current.limit).toBeNull()
    expect(current.pivot!.columnDescending).toBe(true)
    // 输入框被清空（undefined）也按「不限制」保存；排序依据跟着排序指标走
    const cleared = prepareReportConfig(
      { ...base({ sortBy: 'METRIC', sortMetricId: 'out' }), limit: undefined as unknown as null },
      [],
      []
    )
    expect(cleared.limit).toBeNull()
    expect(cleared.sortBy).toBe('METRIC')
    expect(prepareReportConfig(base({ sortBy: 'METRIC', sortMetricId: '' }), [], []).sortBy).toBe('DIMENSION')
  })
})

describe('透视表窗口化渲染的行区间', () => {
  it('只取看得见的行加上下余量；前后用占位高度撑开，总高度不变', () => {
    const top = pivotRowWindow(10000, 0, 560)
    expect(top.start).toBe(0)
    expect(top.end).toBe(Math.ceil(560 / PIVOT_ROW_HEIGHT) + PIVOT_WINDOW_OVERSCAN * 2)
    expect(top.before).toBe(0)
    expect(top.before + (top.end - top.start) * PIVOT_ROW_HEIGHT + top.after).toBe(10000 * PIVOT_ROW_HEIGHT)
    const middle = pivotRowWindow(10000, 5000 * PIVOT_ROW_HEIGHT, 560)
    expect(middle.start).toBe(5000 - PIVOT_WINDOW_OVERSCAN)
    expect(middle.end - middle.start).toBe(top.end - top.start)
    expect(middle.before).toBe(middle.start * PIVOT_ROW_HEIGHT)
    expect(middle.after).toBe((10000 - middle.end) * PIVOT_ROW_HEIGHT)
    // 滚到底：最后一行在窗口里，后面不再有占位
    const bottom = pivotRowWindow(10000, 10000 * PIVOT_ROW_HEIGHT, 560)
    expect(bottom.end).toBe(10000)
    expect(bottom.after).toBe(0)
    expect(bottom.end - bottom.start).toBe(top.end - top.start)
    // 行数比一屏还少
    expect(pivotRowWindow(5, 0, 560)).toEqual({ start: 0, end: 5, before: 0, after: 0 })
    // 实际行高不同于默认值时按实际行高算
    expect(pivotRowWindow(1000, 4000, 400, 40).start).toBe(100 - PIVOT_WINDOW_OVERSCAN)
  })
  it('多层行维度：窗口首行补上起点在窗口之前的合并行表头，跨度截到窗口内', () => {
    const pivot: ReportPivotResult = {
      rowDimensionNames: ['公司', '日期'],
      columnDimensionNames: [],
      rows: [
        ...[1, 2, 3, 4].map(i => ({ keys: ['A', 'd' + i], labels: ['甲', 'd' + i] })),
        ...[1, 2, 3].map(i => ({ keys: ['B', 'd' + i], labels: ['乙', 'd' + i] }))
      ],
      columns: [{ keys: [], labels: [] }],
      cells: [],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 7,
      totalColumnGroups: 1
    }
    const options = {
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE' as const,
      maxColumnGroups: 24
    }
    // 甲：4 叶子 + 小计 = 第 0..4 行；乙：3 叶子 + 小计 = 第 5..8 行
    const rows = pivotBodyRows(pivot, options, new Set())
    expect(rows.map(r => r.id)).toHaveLength(9)
    const spans = pivotRowSpans(rows)
    expect(spans.map(s => [s.start, s.end, s.header.text])).toEqual([
      [0, 5, '甲'],
      [5, 9, '乙']
    ])
    // 窗口 [2, 7)：首行补「甲」（剩 3 行：d3、d4、小计）；「乙」起点在窗口内，跨度从 4 截成 2
    const window = pivotWindowRows(rows, spans, 2, 7)
    expect(window.map(r => r.id)).toEqual(rows.slice(2, 7).map(r => r.id))
    expect(window[0].headers.map(h => [h.text, h.rowspan, h.column])).toEqual([
      ['甲', 3, 0],
      ['d3', 1, 1]
    ])
    expect(window[3].headers.map(h => [h.text, h.rowspan])).toEqual([
      ['乙', 2],
      ['d1', 1]
    ])
    // 没有被截的行原样返回（同一个对象），键不变 ⇒ 下钻、折叠与整表渲染时相同
    expect(window[1]).toBe(rows[3])
    expect(window.map(r => r.keys)).toEqual(rows.slice(2, 7).map(r => r.keys))
    // 窗口从分组起点开始：不补
    expect(pivotWindowRows(rows, spans, 5, 9)[0].headers.map(h => [h.text, h.rowspan])).toEqual([
      ['乙', 4],
      ['d1', 1]
    ])
  })
  it('表头格带排序目标：行维度名称格是第几层，指标格是哪个指标、哪个列组', () => {
    const pivot: ReportPivotResult = {
      rowDimensionNames: ['公司', '部门'],
      columnDimensionNames: ['月份'],
      rows: [{ keys: ['A', 'd1'], labels: ['甲', '一部'] }],
      columns: [
        { keys: ['2026-08'], labels: ['2026-08'] },
        { keys: ['2026-09'], labels: ['2026-09'] }
      ],
      cells: [],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 1,
      totalColumnGroups: 2
    }
    const options = {
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE' as const,
      maxColumnGroups: 24
    }
    const header = pivotHeaderRows(pivot, options, base().metrics)
    expect(header[0].filter(h => h.level === 'dimension').map(h => h.sort)).toEqual([
      { dimension: 0 },
      { dimension: 1 }
    ])
    expect(header[0].filter(h => h.level !== 'dimension').every(h => h.sort === undefined)).toBe(true)
    expect(header[1].map(h => h.sort)).toEqual([
      { metricId: 'in', columnGroup: ['2026-08'] },
      { metricId: 'out', columnGroup: ['2026-08'] },
      { metricId: 'in', columnGroup: ['2026-09'] },
      { metricId: 'out', columnGroup: ['2026-09'] },
      { metricId: 'in', columnGroup: [] },
      { metricId: 'out', columnGroup: [] }
    ])
  })
})
