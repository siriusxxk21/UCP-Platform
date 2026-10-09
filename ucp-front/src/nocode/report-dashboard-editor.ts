import type { DashboardChart, DashboardContent, DashboardDisplay } from '@/types/nocode/report-dashboard'
import type { DatasetRelease } from '@/types/nocode/report-center'
import { MAX_PIVOT_DIMENSIONS } from './report'

/** 结果只绑定取数配置；标题、布局和交互配置不会使单图基础结果过期。 */
export function chartPreviewKey(chart: DashboardChart) {
  return JSON.stringify([
    chart.dataset,
    chart.display,
    chart.dimensions,
    chart.columnDimensions || [],
    chart.metricIds,
    chart.pivot || null
  ])
}

/** 只在来源定义完全一致、已选指标仍存在时保留版本间配置，避免同名字段误匹配。 */
export function canKeepChartVersion(chart: DashboardChart, before?: DatasetRelease, after?: DatasetRelease) {
  return (
    !!before &&
    !!after &&
    before.datasetId === chart.dataset.id &&
    before.versionNo === chart.dataset.versionNo &&
    before.checksum === chart.dataset.checksum &&
    before.datasetId === after.datasetId &&
    JSON.stringify(before.definition.source) === JSON.stringify(after.definition.source) &&
    chart.metricIds.every(id => after.definition.analysis?.metrics.some(metric => metric.id === id))
  )
}

export function chartDataErrors(chart: DashboardChart, release?: DatasetRelease) {
  const errors: Record<string, string> = {}
  if (!chart.title.trim()) errors.title = '请填写图表标题'
  if (!chart.dataset.id) errors.dataset = '请先选择已发布的数据集'
  else if (!release || chart.dataset.versionNo !== release.versionNo || chart.dataset.checksum !== release.checksum)
    errors.version = '请选择可用的发布版本'
  if (!release) return errors
  const fields = release.definition.source?.fields.filter(field => field.role === 'DIMENSION') || []
  const metrics = release.definition.analysis?.metrics || []
  const all = [...chart.dimensions, ...(chart.columnDimensions || [])]
  if (chart.display !== 'METRIC' && !chart.dimensions.length) errors.dimensions = '请至少选择一个维度'
  if (all.some(dimension => !fields.some(field => field.id === dimension.fieldId)))
    errors.dimensions = '所选维度已不可用，请重新选择'
  if (new Set(all.map(dimension => dimension.fieldId)).size !== all.length) errors.dimensions = '行列维度不能重复'
  const max =
    chart.display === 'PIE' ? 1 : chart.display === 'TABLE' ? 3 : chart.display === 'PIVOT' ? MAX_PIVOT_DIMENSIONS : 2
  if (all.length > max)
    errors.dimensions =
      chart.display === 'PIVOT' ? `行列维度合计最多选择 ${max} 个` : `当前类型最多选择 ${max} 个分组维度`
  if (!chart.metricIds.length)
    errors.metrics = metrics.length ? '请至少选择一个指标' : '此版本没有可用指标，请先在数据集中定义并发布指标'
  else if (chart.metricIds.some(id => !metrics.some(metric => metric.id === id)))
    errors.metrics = '所选指标已不可用，请重新选择'
  else if (chart.metricIds.length > (chart.display === 'PIE' ? 1 : 10))
    errors.metrics = chart.display === 'PIE' ? '饼图只能选择一个指标' : '最多选择 10 个指标'
  if (
    chart.display === 'PIVOT' &&
    (!Number.isInteger(chart.pivot?.maxColumnGroups) ||
      chart.pivot!.maxColumnGroups < 1 ||
      chart.pivot!.maxColumnGroups > 100)
  )
    errors.pivot = '列组上限须为 1 至 100 的整数'
  return errors
}

/** 与服务端现有图表能力保持一致，不将建议粒度作为强制条件。 */
export const chartConfigurationGuide: Record<DashboardDisplay, string> = {
  METRIC: '选择 1–10 个指标，无需分组维度，适合查看总量或关键数值。',
  BAR: '选择 1–2 个分组维度、1–10 个指标，比较不同类别的数值。',
  LINE: '选择 1–2 个分组维度、1–10 个指标；查看趋势时建议使用日期维度并设置日、月或年粒度。',
  PIE: '选择 1 个分组维度和 1 个指标，查看各类别的数值占比。',
  TABLE: '选择 1–3 个分组维度、1–10 个指标，查看分组汇总结果。',
  PIVOT: `至少选择 1 个行维度，列维度可选；行列合计最多 ${MAX_PIVOT_DIMENSIONS} 个维度，选择 1–10 个指标。`
}

/** 切换前给出与实际应用规则一致的保留、重置及整板引用影响。 */
export function chartVersionImpact(
  chart: DashboardChart,
  before: DatasetRelease | undefined,
  after: DatasetRelease,
  content: DashboardContent,
  referencesAlreadyReset = false
) {
  const keep = canKeepChartVersion(chart, before, after)
  const preserved = ['图表标题与位置尺寸', ...(chart.pivot ? ['透视显示设置'] : [])]
  const reset: string[] = []
  const notices: string[] = []
  const fieldName = (id: string) =>
    before?.definition.source?.fields.find(field => field.id === id)?.name || `失效字段（${id}）`
  const metricName = (id: string) =>
    before?.definition.analysis?.metrics.find(metric => metric.id === id)?.name || `失效指标（${id}）`
  const filters = (content.filters || []).filter(filter =>
    filter.mappings.some(mapping => mapping.chartId === chart.id)
  )
  const incoming = content.charts.filter(
    item => item.id !== chart.id && item.links?.some(link => link.targetChartId === chart.id)
  )
  if (keep) {
    preserved.push('维度、指标、下钻和对外联动配置')
    if (!referencesAlreadyReset && filters.length)
      preserved.push(`公共筛选映射：${filters.map(filter => filter.name).join('、')}`)
    if (!referencesAlreadyReset && incoming.length)
      preserved.push(`来自其他图表的联动：${incoming.map(item => item.title).join('、')}`)
  } else {
    if (chart.dimensions.length)
      reset.push(`分组/行维度：${chart.dimensions.map(d => fieldName(d.fieldId)).join('、')}`)
    if (chart.columnDimensions?.length)
      reset.push(`列维度：${chart.columnDimensions.map(d => fieldName(d.fieldId)).join('、')}`)
    if (chart.metricIds.length) reset.push(`指标：${chart.metricIds.map(metricName).join('、')}`)
    if (chart.drillDimensions?.length)
      reset.push(`下钻层级：${chart.drillDimensions.map(d => fieldName(d.fieldId)).join('、')}`)
    if (chart.links?.length)
      reset.push(
        `对外联动：${chart.links.map(link => content.charts.find(item => item.id === link.targetChartId)?.title || '失效图表').join('、')}`
      )
    for (const filter of filters)
      reset.push(
        `公共筛选「${filter.name}」：${filter.mappings.length === 1 ? '失去唯一映射，将移除整个筛选' : '仅移除此图映射，其他图表保留'}`
      )
    if (incoming.length) reset.push(`移除指向此图的联动：${incoming.map(item => item.title).join('、')}`)
    notices.push('来源结构或所选指标不兼容，将按新版本的首个可用维度和指标重新初始化，请重新检查选择。')
  }
  if (keep && referencesAlreadyReset) {
    notices.push(
      '此前已确认重置来源，应用到画布时仍将清理原有公共筛选和指向此图的联动；本次保留配置不恢复已重置的引用。'
    )
    if (filters.length) reset.push(`此前待清理公共筛选：${filters.map(filter => filter.name).join('、')}`)
    if (incoming.length) reset.push(`此前待清理入站联动：${incoming.map(item => item.title).join('、')}`)
  }
  if (before && JSON.stringify(before.definition.analysis) !== JSON.stringify(after.definition.analysis))
    notices.push('目标版本的指标定义、固定条件或显示口径有变化；即使保留字段选择，查询结果也可能不同。')
  const removed = []
  const mappingCount = filters.reduce(
    (count, filter) => count + filter.mappings.filter(mapping => mapping.chartId === chart.id).length,
    0
  )
  const removedFilterCount = filters.filter(filter =>
    filter.mappings.every(mapping => mapping.chartId === chart.id)
  ).length
  const linkCount =
    (chart.links?.length || 0) +
    incoming.reduce(
      (count, item) => count + (item.links?.filter(link => link.targetChartId === chart.id).length || 0),
      0
    )
  if (mappingCount)
    removed.push(
      `${mappingCount} 个筛选映射${removedFilterCount ? `（其中 ${removedFilterCount} 个筛选将被移除）` : ''}`
    )
  if (linkCount) removed.push(`${linkCount} 条联动`)
  if (chart.drillDimensions?.length) removed.push(`${chart.drillDimensions.length} 层下钻`)
  const summary = keep
    ? '保留当前配置并切换版本。'
    : `将重置${chart.display === 'METRIC' ? '指标' : '维度和指标'}选择${removed.length ? `，并移除 ${removed.join('、')}` : ''}。`
  return { keep, preserved, reset, notices, summary }
}
