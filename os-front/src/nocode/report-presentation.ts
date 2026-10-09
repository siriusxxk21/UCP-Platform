import type { EChartsOption } from 'echarts'
import {
  MAX_REPORT_MULTI_SOURCE_METRICS,
  ReportGrain,
  type ReportChart,
  type ReportConfig,
  type ReportMetric,
  type ReportResult
} from '@/types/nocode/report'
import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'
import {
  MAX_GROUP_DIMENSIONS,
  MAX_PIVOT_DIMENSIONS,
  dimensionKey,
  exactNumber,
  pivotDimensionLimitMessage,
  pivotOptions,
  reportDetails,
  reportLimitMax,
  reportTableDisplay
} from './report'
import { formatFinancialAmount } from './money-display'
import { OPERATOR_LABELS, type DynamicSearchCondition } from '@/components/os-table-page/types'
import { describeRelativeDate, isRelativeDate } from './relative-date'

export const defaultReportChart = (): ReportChart => ({
  barMode: 'GROUPED',
  horizontal: false,
  labels: false,
  legendPosition: 'TOP'
})
export const metricNeedsField = (m: ReportMetric) =>
  m.operation !== 'COUNT' && m.operation !== 'FORMULA' && m.operation !== 'COUNT_ROOT'
export const metricZeroWhenEmpty = (m: ReportMetric) =>
  ['COUNT', 'COUNT_FIELD', 'COUNT_DISTINCT', 'COUNT_ROOT', 'SUM'].includes(m.operation)
export const formulaLabels = { ADD: '+', SUBTRACT: '−', MULTIPLY: '×', DIVIDE: '÷' } as const

export function financialReportMetric(metric: ReportMetric, sourceType?: FieldType): boolean {
  if (metric.format?.percent) return false
  if (metric.operation === 'FORMULA') return !!metric.format?.financial
  return ['SUM', 'AVG', 'MIN', 'MAX'].includes(metric.operation) && sourceType === FieldType.MONEY
}

/** 展示舍入只处理十进制字符串；图形坐标才转换为近似 Number。 */
export function formatReportValue(raw: string | null | undefined, metric: ReportMetric): string {
  if (raw == null || raw === '') return '—'
  const format = metric.format
  if (format?.financial) return formatFinancialAmount(raw) + (format.unit || '')
  if (!format || (!format.percent && format.decimals == null)) return exactNumber(raw) + (format?.unit || '')
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return raw
  const negative = raw.startsWith('-')
  const [integer, fraction = ''] = raw.replace(/^-/, '').split('.')
  const decimals = Math.max(0, Math.min(8, format.decimals ?? 2))
  const scale = fraction.length - (format.percent ? 2 : 0)
  let digits = BigInt(integer + fraction)
  if (scale > decimals) {
    const divisor = 10n ** BigInt(scale - decimals)
    digits = (digits + divisor / 2n) / divisor
  } else digits *= 10n ** BigInt(decimals - scale)
  const text = digits.toString().padStart(decimals + 1, '0')
  const result = decimals ? text.slice(0, -decimals) + '.' + text.slice(-decimals) : text
  return exactNumber((negative && digits !== 0n ? '-' : '') + result) + (format.percent ? '%' : format.unit || '')
}

export function metricDescription(
  m: ReportMetric,
  config: ReportConfig,
  fieldNames: Record<string, string> = {}
): string {
  if (m.formula)
    return `${config.metrics.find(v => v.id === m.formula!.left)?.name || '失效指标'} ${formulaLabels[m.formula.operator]} ${config.metrics.find(v => v.id === m.formula!.right)?.name || '失效指标'}；合计按总体重新计算，除数为零显示 —`
  const operations = {
    COUNT: config.grain === ReportGrain.DETAIL ? '明细行数' : '记录计数',
    COUNT_FIELD: '非空计数',
    COUNT_DISTINCT: '去重计数',
    COUNT_ROOT: '涉及的主记录数，同一条主记录只算一次',
    SUM: '求和',
    AVG: '平均值',
    MIN: '最小值',
    MAX: '最大值',
    FORMULA: '指标计算'
  }
  const describe = (items: DynamicSearchCondition['items'], logic: string): string =>
    items
      .map(item => {
        if (item.type === 'group') return '(' + describe(item.groupItems, item.groupLogic) + ')'
        const values = Array.isArray(item.value) ? item.value : [item.value]
        const text = isRelativeDate(item.value)
          ? describeRelativeDate(item.value)
          : values.map(v => String(v ?? '')).join('、')
        return `${fieldNames[item.field] || '条件字段'} ${OPERATOR_LABELS[item.operator]} ${['isNull', 'notNull'].includes(item.operator) ? '' : text}`
      })
      .join(logic === 'AND' ? ' 且 ' : ' 或 ')
  return `${m.fieldId ? (fieldNames[m.fieldId] || '选定字段') + ' · ' : ''}${operations[m.operation]}${m.conditions ? ' · ' + describe(m.conditions.items, m.conditions.logic) : ' · 沿用公共统计范围'}`
}

export function duplicateReportMetrics(metrics: ReportMetric[]): boolean {
  const signatures = metrics.map(m => JSON.stringify([m.operation, m.fieldId, m.conditions || null, m.formula || null]))
  return new Set(signatures).size !== signatures.length
}

/** 校验用的字段归属：统计对象的主表字段与粒度明细的字段（ID → 名称）；不含关系路径。 */
export interface ReportFieldScope {
  /** 粒度明细的名称；主记录粒度、或所选明细不存在 / 已停用时为 null。 */
  detailName: string | null
  rootFields: Record<string, string>
  detailFields: Record<string, string>
}
export function reportFieldScope(
  config: Pick<ReportConfig, 'grain' | 'detailId'>,
  definition?: Pick<PublishedDefinition, 'fields' | 'details'>
): ReportFieldScope {
  const detail =
    config.grain === ReportGrain.DETAIL ? reportDetails(definition).find(d => d.id === config.detailId) : undefined
  const names = (fields: Array<{ id: string | null; name: string }> = []) =>
    Object.fromEntries(fields.filter(f => f.id).map(f => [f.id!, f.name]))
  return {
    detailName: detail?.name ?? null,
    rootFields: names(definition?.fields),
    detailFields: names(detail?.fields)
  }
}
/** 逐字同契约第 6 章；保存、预览、界面标红共用，服务端抛的是同一句。 */
export const reportGrainMessages = {
  M6: (fieldName: string, detailName: string) =>
    `按明细行统计时，主表字段「${fieldName}」会按明细行数重复计算，不能求和、求平均或做非空计数。请改用明细「${detailName}」里的字段，或把「统计粒度」改回「按主记录」`,
  /** 明细粒度的下钻明细视图须按同一明细逐行显示（与后端 ReportGrainMessages.detailDrillView 逐字相同）。 */
  M8: (detail: string) =>
    `按明细行统计时，下钻明细视图须是按明细「${detail}」逐行显示的数据视图（视图设置里「一行表示」选「一条内部明细」、明细来源选「${detail}」）`,
  M9: '「主记录数」只用于按明细行统计；按主记录统计时请用「记录计数」',
  M10: '「主记录数」无需指定字段',
  M11b: '按明细行统计需要选择一个内部明细'
}
/** 明细粒度下对主表字段求和、求平均、非空计数会按明细行数重复计算：返回 M6 整句，否则 null。 */
export function metricGrainError(
  m: ReportMetric,
  config: Pick<ReportConfig, 'grain'>,
  scope?: ReportFieldScope
): string | null {
  if (config.grain !== ReportGrain.DETAIL || !scope?.detailName || !m.fieldId) return null
  if (!['SUM', 'AVG', 'COUNT_FIELD'].includes(m.operation)) return null
  if (m.fieldId in scope.detailFields || !(m.fieldId in scope.rootFields)) return null
  return reportGrainMessages.M6(scope.rootFields[m.fieldId], scope.detailName)
}

/** 配置校验在保存与预览共用；服务端仍独立校验全部规则。 */
// scope 可选：给了才能判「明细粒度下指标用的是主表字段」（M6，需要字段归属与名称）。
/** 契约 laneM 第 6 章 L12：多来源的统计最多 10 个指标（单来源仍是 5 个，提示不变）。 */
export const multiSourceMetricLimitMessage = '多个来源的统计最多 10 个指标'
export function validateReport(config: ReportConfig, scope?: ReportFieldScope) {
  // 多来源（extraSources 非空）放宽到 10 个；其余多来源规则在 report-sources 的 validateReportSources。
  if (config.extraSources?.length && config.metrics.length > MAX_REPORT_MULTI_SOURCE_METRICS)
    throw new Error(multiSourceMetricLimitMessage)
  const metricLimit = config.extraSources?.length ? MAX_REPORT_MULTI_SOURCE_METRICS : 5
  if (!config.objectId || config.metrics.length < 1 || config.metrics.length > metricLimit)
    throw new Error('请选择统计对象并配置 1～5 个指标')
  if (config.display === 'METRIC' ? config.dimensions.length > 0 : !config.dimensions.length)
    throw new Error('指标卡不分组，图表至少设置一个分组')
  const pivotDisplay = config.display === 'PIVOT'
  // 透视表行、列维度各自不限个数，只限合计数；其它展示方式仍最多两个分组。
  if ((!pivotDisplay && config.dimensions.length > MAX_GROUP_DIMENSIONS) || config.dimensions.some(d => !d.fieldId))
    throw new Error('请选择有效分组，最多两个')
  const columns = config.columnDimensions || []
  if (pivotDisplay) {
    if (columns.some(d => !d.fieldId)) throw new Error('透视表列维度须选择字段')
    if (config.dimensions.length + columns.length > MAX_PIVOT_DIMENSIONS) throw new Error(pivotDimensionLimitMessage)
    const rows = new Set(config.dimensions.map(dimensionKey))
    if (rows.size !== config.dimensions.length) throw new Error('透视表行维度不能重复')
    if (new Set(columns.map(dimensionKey)).size !== columns.length) throw new Error('透视表列维度不能重复')
    if (columns.some(d => rows.has(dimensionKey(d)))) throw new Error('同一字段不能同时作为行维度和列维度')
    const max = pivotOptions(config).maxColumnGroups
    if (!Number.isInteger(max) || max < 1 || max > 100) throw new Error('列组上限须为 1～100 的整数')
  } else if (columns.length || config.pivot) throw new Error('仅透视表可设置列维度与透视选项')
  if (config.detailEditable && !config.detailViewId) throw new Error('请先选择下钻明细视图，再允许编辑明细')
  // 行数留空 = 不限制（只对汇总表、透视表有效）；填了数字须在该展示方式的上限内。
  if (
    config.limit != null &&
    (!Number.isInteger(config.limit) || config.limit < 1 || config.limit > reportLimitMax(config.display))
  )
    throw new Error(
      reportTableDisplay(config.display)
        ? `最多展示行数须为 1～${reportLimitMax(config.display)} 的整数，留空表示不限制`
        : `展示组数须为 1～${reportLimitMax(config.display)} 的整数`
    )
  if (config.sortBy === 'METRIC' && !config.sortMetricId) throw new Error('按指标排序需要选择排序指标')
  if (config.sortBy === 'DIMENSION' && config.sortMetricId) throw new Error('按维度的值排序时不能同时设置排序指标')
  if (config.display === 'PIE' && (config.dimensions.length !== 1 || config.metrics.length !== 1))
    throw new Error('饼图需要一个分组和一个指标')
  const visit = (id: string, path: string[]) => {
    const m = config.metrics.find(m => m.id === id)
    if (!m) throw new Error('计算指标引用了不存在的指标')
    if (path.includes(id)) throw new Error('指标不能循环引用')
    if (!m.name.trim() || (metricNeedsField(m) && !m.fieldId)) throw new Error('请补齐指标名称与字段')
    if (m.format?.financial && m.format.percent) throw new Error('金额格式不能同时设为百分比')
    if (m.operation === 'FORMULA') {
      if (!m.formula) throw new Error('请选择参与计算的两个指标')
      visit(m.formula.left, [...path, id])
      visit(m.formula.right, [...path, id])
    }
  }
  config.metrics.forEach(m => visit(m.id, []))
  if (
    config.display === 'BAR' &&
    config.chart?.barMode !== undefined &&
    config.chart.barMode !== 'GROUPED' &&
    config.metrics.some(m => m.operation === 'FORMULA' || m.format?.percent)
  )
    throw new Error('堆叠图只展示同单位的基础数量指标，比例请使用分组图、表格或指标卡')
  if (
    config.display === 'BAR' &&
    config.chart?.barMode &&
    config.chart.barMode !== 'GROUPED' &&
    new Set(config.metrics.map(m => m.format?.unit?.trim() || '')).size > 1
  )
    throw new Error('堆叠指标的单位必须一致，请统一单位或改为分组图')
  // 统计粒度（契约第 4、6 章）。主记录粒度的存量配置不带 grain / detailId，也不会有「主记录数」，下面各条都不触发。
  const detailGrain = config.grain === ReportGrain.DETAIL
  if (detailGrain && !config.detailId) throw new Error(reportGrainMessages.M11b)
  for (const m of config.metrics) {
    if (m.operation === 'COUNT_ROOT') {
      if (!detailGrain) throw new Error(reportGrainMessages.M9)
      if (m.fieldId || m.formula) throw new Error(reportGrainMessages.M10)
    }
    const grainError = metricGrainError(m, config, scope)
    if (grainError) throw new Error(grainError)
  }
}

/** 仅控制当前容器的展示密度，不写入报表配置或发布快照。 */
export interface ReportChartPresentation {
  compact?: boolean
  primaryColor?: string
}

/** 预览与运行共享系列构造，保留指标 ID 供后端还原下钻条件。 */
export function reportChartOption(
  config: ReportConfig,
  result: ReportResult,
  presentation: ReportChartPresentation = {}
): EChartsOption {
  const chart = config.chart || defaultReportChart()
  const groups = result.groups
  const metrics = result.metrics
  const palette = ['#4f46e5', '#0891b2', '#10b981', '#f59e0b', '#a855f7']
  const stableColor = (key: string) =>
    palette[Array.from(key).reduce((n, c) => (n * 31 + c.charCodeAt(0)) >>> 0, 0) % palette.length]
  const firstKeys = Array.from(new Map(groups.map(g => [JSON.stringify(g.keys[0]), g.labels[0]])).entries())
  const secondKeys =
    config.dimensions.length === 2
      ? Array.from(new Map(groups.map(g => [JSON.stringify(g.keys[1]), g.labels[1]])).entries())
      : [['', '']]
  const compactHorizontal =
    presentation.compact && config.display === 'BAR' && chart.horizontal && chart.barMode === 'GROUPED'
  const compactSingleSeries = compactHorizontal && metrics.length * secondKeys.length === 1
  // containLabel 只保护坐标轴文字；柱外数值另留空白，避免最大值贴右边被截断。
  const valueLabelSpace = compactHorizontal
    ? Math.max(
        84,
        ...groups.flatMap(group =>
          metrics.map(
            metric =>
              Array.from(formatReportValue(group.values[metric.id], metric)).reduce(
                (width, character) => width + (character.charCodeAt(0) > 127 ? 13 : 7),
                0
              ) + 24
          )
        )
      )
    : 28
  const common = {
    animation: false,
    color: palette,
    aria: { enabled: true },
    legend: {
      ...(compactSingleSeries ? { show: false } : {}),
      [chart.legendPosition.toLowerCase()]: 0,
      orient: ['LEFT', 'RIGHT'].includes(chart.legendPosition) ? 'vertical' : 'horizontal'
    },
    grid: {
      left: chart.legendPosition === 'LEFT' ? 150 : 28,
      right: Math.max(chart.legendPosition === 'RIGHT' ? 150 : 28, valueLabelSpace),
      bottom: chart.legendPosition === 'BOTTOM' ? 65 : 40,
      top: compactSingleSeries ? 20 : 50,
      containLabel: true
    },
    tooltip: {
      trigger: 'item',
      renderMode: 'richText',
      formatter: (p: any) => {
        const point = p.data
        const m = metrics.find(m => m.id === point?.metricId)
        return m
          ? `${point.groupLabel}\n${m.name}：${formatReportValue(point.raw, m)}${chart.barMode === 'PERCENT' && config.display === 'BAR' ? '\n构成占比：' + Number(point.value).toFixed(2) + '%' : ''}`
          : ''
      }
    }
  }
  if (config.display === 'PIE') {
    const m = metrics[0]
    if (groups.some(g => g.values[m.id] != null && Number(g.values[m.id]) < 0))
      throw new Error('饼图存在负数，请切换柱状图或表格查看完整结果')
    return {
      ...common,
      series: [
        {
          type: 'pie',
          radius: ['35%', '65%'],
          data: groups
            .map((g, index) => ({
              name: g.labels[0],
              value: Number(g.values[m.id]),
              raw: g.values[m.id],
              metricId: m.id,
              groupIndex: index,
              groupLabel: g.labels.join(' / ')
            }))
            .filter(p => p.raw != null && p.value >= 0),
          label: { show: chart.labels, formatter: (p: any) => `${p.name} ${formatReportValue(p.data.raw, m)}` }
        }
      ]
    } as EChartsOption
  }
  const stacked = config.display === 'BAR' && chart.barMode !== 'GROUPED'
  const horizontal = chart.horizontal && config.display === 'BAR'
  const mixedPercent = !stacked && metrics.some(m => m.format?.percent) && metrics.some(m => !m.format?.percent)
  const series = metrics.flatMap(m =>
    secondKeys.map(([key, label]) => ({
      id: `${m.id}:${key}`,
      name: label ? label + ' · ' + m.name : m.name,
      type: config.display === 'LINE' ? 'line' : 'bar',
      ...(compactHorizontal ? { barMaxWidth: 24 } : {}),
      stack: stacked ? 'value' : undefined,
      ...(mixedPercent ? { [horizontal ? 'xAxisIndex' : 'yAxisIndex']: m.format?.percent ? 1 : 0 } : {}),
      connectNulls: false,
      itemStyle: {
        color:
          compactSingleSeries && presentation.primaryColor
            ? presentation.primaryColor
            : secondKeys.length === 1
              ? m.format?.color || stableColor(m.id)
              : stableColor(m.id + ':' + key)
      },
      label: {
        show: compactHorizontal || chart.labels,
        position: stacked ? 'inside' : chart.horizontal ? 'right' : 'top',
        formatter: (p: any) =>
          chart.barMode === 'PERCENT' && stacked
            ? Number(p.data.value).toFixed(1) + '%'
            : formatReportValue(p.data.raw, m)
      },
      data: firstKeys.map(([first, firstLabel]) => {
        const index = groups.findIndex(
          g =>
            JSON.stringify(g.keys[0]) === first && (config.dimensions.length < 2 || JSON.stringify(g.keys[1]) === key)
        )
        const g = groups[index]
        const raw = g ? g.values[m.id] : metricZeroWhenEmpty(m) ? '0' : null
        return {
          value: raw == null ? null : Number(raw) * (m.format?.percent ? 100 : 1),
          raw,
          metricId: m.id,
          groupIndex: index < 0 ? undefined : index,
          groupLabel: g ? g.labels.join(' / ') : firstLabel + ' / ' + label
        }
      })
    }))
  )
  if (stacked && series.some(s => s.data.some(p => p.value != null && p.value < 0)))
    throw new Error('堆叠数量图存在负数，请切换分组柱状图查看')
  if (stacked && chart.barMode === 'PERCENT')
    firstKeys.forEach((_, index) => {
      const sum = series.reduce((total, s) => total + (s.data[index].value || 0), 0)
      series.forEach(s => {
        if (s.data[index].value == null) return
        s.data[index].value = sum ? ((s.data[index].value || 0) / sum) * 100 : 0
      })
    })
  const category = {
    type: 'category',
    data: firstKeys.map(([, label]) => label),
    ...(compactHorizontal ? { inverse: true } : {}),
    axisLabel: { hideOverlap: true }
  }
  const percentOnly = metrics.every(m => m.format?.percent)
  const numeric = {
    type: 'value',
    max: stacked && chart.barMode === 'PERCENT' ? 100 : undefined,
    name:
      stacked && chart.barMode === 'PERCENT' ? '构成占比（%）' : percentOnly ? '比例（%）' : mixedPercent ? '数值' : '',
    axisLabel: { formatter: percentOnly ? '{value}%' : '{value}' }
  }
  const axes = mixedPercent
    ? [
        numeric,
        {
          type: 'value',
          name: '比例（%）',
          position: horizontal ? 'top' : 'right',
          axisLabel: { formatter: '{value}%' },
          splitLine: { show: false }
        }
      ]
    : numeric
  return {
    ...common,
    xAxis: horizontal ? axes : category,
    yAxis: horizontal ? category : axes,
    series
  } as EChartsOption
}
