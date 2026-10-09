import type {
  DashboardChart,
  DashboardContent,
  DashboardDisplay,
  DashboardLinkSelection
} from '@/types/nocode/report-dashboard'
import type { ReportConfig, ReportResult } from '@/types/nocode/report'
import { defaultReportChart } from './report-presentation'
export const dashboardDisplays: { value: DashboardDisplay; label: string }[] = [
  { value: 'METRIC', label: '指标卡' },
  { value: 'BAR', label: '柱状图' },
  { value: 'LINE', label: '折线图' },
  { value: 'PIE', label: '饼图' },
  { value: 'TABLE', label: '汇总表' },
  { value: 'PIVOT', label: '透视表' }
]
/** 类型切换先生成独立草稿与明确的移除清单；调用者确认后才应用，不改原配置。 */
export function dashboardDisplayChange(source: DashboardChart, display: DashboardDisplay) {
  const chart = JSON.parse(JSON.stringify(source)) as DashboardChart
  const removed: string[] = []
  if (display !== 'PIVOT') {
    if (chart.columnDimensions?.length) removed.push('透视列维度')
    if (chart.pivot) removed.push('透视小计和显示设置')
    delete chart.columnDimensions
    delete chart.pivot
  } else {
    chart.columnDimensions ||= []
    chart.pivot ||= { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 }
  }
  if (display === 'METRIC') {
    if (chart.dimensions.length) removed.push('分组维度')
    chart.dimensions = []
  }
  if (display === 'METRIC' || display === 'PIVOT') {
    if (chart.drillDimensions?.length) removed.push('下钻层级')
    if (chart.links?.length) removed.push('对外联动')
    chart.drillDimensions = []
    chart.links = []
  }
  chart.display = display
  return { chart, removed }
}
export const copyDashboard = (value: DashboardContent): DashboardContent => JSON.parse(JSON.stringify(value))
/** 独立入口遇到发布版本变化时，所有组件必须一起放弃旧版本结果。 */
export function dashboardVersionChanged(cause: unknown): boolean {
  return (cause as { businessCode?: number } | null)?.businessCode === 1050000007
}
/** 组件复制保持固定来源，并延续公共筛选及双向联动；自动寻找不重叠位置。 */
export function duplicateDashboardChart(content: DashboardContent, id: string, newId: string): DashboardContent {
  const next = copyDashboard(content),
    original = next.charts.find(chart => chart.id === id)
  if (!original || !newId || next.charts.some(chart => chart.id === newId)) throw new Error('组件编号无效')
  if (next.charts.length >= 30) throw new Error('仪表板最多支持 30 个组件')
  let position: { x: number; y: number } | undefined
  for (let y = 0; y <= 200 && !position; y++) {
    for (let x = 0; x <= 12 - original.w; x++) {
      const layout = { ...original, x, y }
      try {
        moveDashboardChart(next.charts, newId, layout)
        position = { x, y }
        break
      } catch {
        // 只接受完整空白区域，不能在复制时移动或覆盖其他图表。
      }
    }
  }
  if (!position) throw new Error('当前画布没有足够的空白网格')
  const duplicate = { ...JSON.parse(JSON.stringify(original)), ...position, id: newId } as DashboardChart
  duplicate.title = (original.title + ' 副本').slice(0, 80)
  next.charts.forEach(chart => {
    const incoming = chart.links?.find(link => link.targetChartId === id)
    if (incoming) chart.links!.push({ ...incoming, targetChartId: newId })
  })
  next.filters?.forEach(filter => {
    const mapping = filter.mappings.find(item => item.chartId === id)
    if (mapping) filter.mappings.push({ ...mapping, chartId: newId })
  })
  next.charts.push(duplicate)
  return next
}
/** 展示维度随钻取层级切换；固定配置和请求中的原键路径分别保存。 */
export function dashboardDrilledChart(chart: DashboardChart, depth: number): DashboardChart {
  if (!depth) return chart
  const dimension = chart.drillDimensions?.[depth - 1]
  if (!dimension) throw new Error('钻取层级超出配置范围')
  return { ...chart, dimensions: [{ ...dimension }] }
}
/** 再点同一原键取消该来源；同名标签、NULL 和字符串均不能混作同一个键。 */
export function toggleDashboardSelection(
  selections: DashboardLinkSelection[],
  chartId: string,
  group: (string | null)[]
): DashboardLinkSelection[] {
  const previous = selections.find(s => s.chartId === chartId)
  const remaining = selections.filter(s => s.chartId !== chartId)
  return previous && JSON.stringify(previous.group) === JSON.stringify(group)
    ? remaining
    : [...remaining, { chartId, group: [...group] }]
}
export function dashboardChartConfig(chart: DashboardChart, result: ReportResult): ReportConfig {
  return {
    objectId: chart.dataset.id,
    dimensions: chart.dimensions.map(d => ({ ...d, relationPath: null })),
    columnDimensions: chart.columnDimensions?.map(d => ({ ...d, relationPath: null })),
    pivot: chart.pivot,
    metrics: result.metrics,
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: result.timeZone,
    display: chart.display,
    sortMetricId: null,
    descending: false,
    limit: 100,
    detailViewId: null,
    chart: defaultReportChart()
  }
}
export function moveDashboardChart(
  charts: DashboardChart[],
  id: string,
  layout: Pick<DashboardChart, 'x' | 'y' | 'w' | 'h'>
): DashboardChart[] {
  if (
    ![layout.x, layout.y, layout.w, layout.h].every(Number.isInteger) ||
    layout.x < 0 ||
    layout.y < 0 ||
    layout.y > 200 ||
    layout.w < 3 ||
    layout.w > 12 ||
    layout.h < 2 ||
    layout.h > 12 ||
    layout.x + layout.w > 12
  )
    throw new Error('布局超出网格范围')
  if (
    charts.some(
      c =>
        c.id !== id &&
        layout.x < c.x + c.w &&
        c.x < layout.x + layout.w &&
        layout.y < c.y + c.h &&
        c.y < layout.y + layout.h
    )
  )
    throw new Error('此位置已有图表，请移到空白网格')
  return charts.map(c => (c.id === id ? { ...c, ...layout } : c))
}
/** 默认值仅初始化可清除的公共筛选；应用固定绑定输入由服务端单独处理。 */
export function dashboardDefaultFilterValues(
  content: DashboardContent,
  excludedIds: string[] = []
): import('@/types/nocode/report-dashboard').DashboardFilterValue[] {
  return (content.filters || [])
    .filter(filter => filter.defaultValue && !excludedIds.includes(filter.id))
    .map(filter => ({ filterId: filter.id, ...JSON.parse(JSON.stringify(filter.defaultValue)) }))
}
