import { describe, it, expect } from 'vitest'
import {
  moveDashboardChart,
  dashboardDisplayChange,
  copyDashboard,
  dashboardChartConfig,
  dashboardDrilledChart,
  toggleDashboardSelection,
  duplicateDashboardChart
} from './report-dashboard'
import type { DashboardChart, DashboardContent } from '@/types/nocode/report-dashboard'
const first: DashboardChart = {
  id: 'first',
  title: '金额',
  display: 'BAR',
  dataset: { id: '7', versionNo: 2, checksum: 'fixed' },
  dimensions: [{ fieldId: 'company', bucket: 'VALUE' }],
  metricIds: ['sum'],
  x: 0,
  y: 0,
  w: 6,
  h: 6
}
describe('独立仪表板网格', () => {
  it('复制组件使用空白网格并延续筛选与双向联动，原组件引用和发布 pin 不变', () => {
    const source: DashboardContent = {
      schemaVersion: 1,
      name: '看板',
      description: '',
      charts: [
        { ...first, links: [{ targetChartId: 'other', sourceFieldId: 'company', targetFieldId: 'company' }] },
        {
          ...first,
          id: 'other',
          x: 6,
          links: [{ targetChartId: 'first', sourceFieldId: 'company', targetFieldId: 'company' }]
        }
      ],
      filters: [{ id: 'filter', name: '公司', kind: 'SELECT', mappings: [{ chartId: 'first', fieldId: 'company' }] }]
    }
    const next = duplicateDashboardChart(source, 'first', 'copy')
    const clone = next.charts[2]!
    expect([clone.x, clone.y, clone.w, clone.h]).toEqual([0, 6, 6, 6])
    expect(clone.dataset).toEqual(first.dataset)
    expect(clone.links).toEqual(source.charts[0]!.links)
    expect(next.charts[1]!.links!.map(link => link.targetChartId)).toEqual(['first', 'copy'])
    expect(next.filters![0]!.mappings.map(mapping => mapping.chartId)).toEqual(['first', 'copy'])
    clone.metricIds.push('extra')
    expect(next.charts[0]!.metricIds).toEqual(['sum'])
    expect(source.charts).toHaveLength(2)
    expect(source.filters![0]!.mappings).toHaveLength(1)
    expect(() => duplicateDashboardChart(source, 'first', 'other')).toThrow('编号')
  })
  it('同名标签联动按原键取消，NULL 与业务字符串 null 不混淆', () => {
    const initial = [{ chartId: 'other', group: ['7'] }]
    const selected = toggleDashboardSelection(initial, 'first', [null])
    expect(toggleDashboardSelection(selected, 'first', [null])).toEqual(initial)
    expect(toggleDashboardSelection(selected, 'first', ['null'])).toEqual([
      ...initial,
      { chartId: 'first', group: ['null'] }
    ])
    expect(initial).toHaveLength(1)
  })
  it('日期按已发布层级切换并保留原配置', () => {
    const chart = {
      ...first,
      drillDimensions: [
        { fieldId: 'date', bucket: 'MONTH' as const },
        { fieldId: 'date', bucket: 'DAY' as const }
      ]
    }
    expect(dashboardDrilledChart(chart, 1).dimensions).toEqual([{ fieldId: 'date', bucket: 'MONTH' }])
    expect(dashboardDrilledChart(chart, 2).dimensions).toEqual([{ fieldId: 'date', bucket: 'DAY' }])
    expect(dashboardDrilledChart(chart, 0)).toBe(chart)
    expect(() => dashboardDrilledChart(chart, 3)).toThrow('超出')
    expect(chart.dimensions[0]!.fieldId).toBe('company')
  })
  it('移动保持固定数据集引用且不修改原始配置', () => {
    const result = moveDashboardChart([first], first.id, { x: 6, y: 0, w: 6, h: 6 })
    expect(() => moveDashboardChart([], first.id, first)).not.toThrow()
    expect(first.x).toBe(0)
    expect(result[0]!.dataset).toEqual(first.dataset)
    expect(result[0]!.x).toBe(6)
  })
  it('阻止重叠、超界和非整数网格', () => {
    const other = { ...first, id: 'other', x: 6 }
    expect(() => moveDashboardChart([first, other], 'first', { x: 3, y: 0, w: 6, h: 6 })).toThrow('已有图表')
    expect(() => moveDashboardChart([first], 'first', { x: 7, y: 0, w: 6, h: 6 })).toThrow('范围')
    expect(() => moveDashboardChart([first], 'first', { x: 0.5, y: 0, w: 6, h: 6 })).toThrow('范围')
  })
  it('撤销快照不共享内部组件数组，展示保留服务端精确指标', () => {
    const draft: DashboardContent = { schemaVersion: 1, name: '看板', description: '', charts: [first] }
    const snapshot = copyDashboard(draft)
    snapshot.charts[0]!.metricIds.push('ratio')
    expect(draft.charts[0]!.metricIds).toEqual(['sum'])
    const metrics = [{ id: 'sum', name: '金额', operation: 'SUM' as const, fieldId: 'amount' }]
    const config = dashboardChartConfig(first, {
      dimensionNames: ['公司'],
      metrics,
      groups: [],
      totals: { sum: '9999999999999999.01' },
      totalGroups: 0,
      recordCount: 0,
      canExport: false,
      timeZone: 'Asia/Shanghai'
    })
    expect(config.metrics).toBe(metrics)
    expect(config.dimensions[0]!.fieldId).toBe('company')
  })
})

describe('图表类型切换', () => {
  it('保留兼容字段和固定版本，仅列出目标类型不能使用的配置', () => {
    const source = {
      ...first,
      drillDimensions: [{ fieldId: 'month', bucket: 'MONTH' as const }],
      links: [{ sourceFieldId: 'company', targetChartId: 'other', targetFieldId: 'company' }]
    }
    expect(dashboardDisplayChange(source, 'LINE')).toEqual({ chart: { ...source, display: 'LINE' }, removed: [] })
    const metric = dashboardDisplayChange(source, 'METRIC')
    expect(metric.removed).toEqual(['分组维度', '下钻层级', '对外联动'])
    expect(metric.chart.dimensions).toEqual([])
    expect(metric.chart.dataset).toEqual(first.dataset)
    expect(metric.chart.metricIds).toEqual(first.metricIds)
    expect(source.dimensions).toHaveLength(1)
    expect(source.links).toHaveLength(1)
  })
  it('退出透视明确列出移除项；切饼图保留多维和多指标供用户修正，不静默截断', () => {
    const source = dashboardDisplayChange(first, 'PIVOT').chart
    source.columnDimensions = [{ fieldId: 'month', bucket: 'MONTH' }]
    source.metricIds.push('count')
    source.dimensions.push({ fieldId: 'account', bucket: 'VALUE' })
    const pie = dashboardDisplayChange(source, 'PIE')
    expect(pie.removed).toEqual(['透视列维度', '透视小计和显示设置'])
    expect(pie.chart.pivot).toBeUndefined()
    expect(pie.chart.columnDimensions).toBeUndefined()
    expect(pie.chart.dimensions).toHaveLength(2)
    expect(pie.chart.metricIds).toEqual(['sum', 'count'])
    expect(source.columnDimensions).toHaveLength(1)
  })
})
