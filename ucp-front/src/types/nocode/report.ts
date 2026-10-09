import type { RecordContext } from './runtime'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
export const ReportDisplay = {
  METRIC: 'METRIC',
  BAR: 'BAR',
  LINE: 'LINE',
  PIE: 'PIE',
  TABLE: 'TABLE',
  PIVOT: 'PIVOT'
} as const
export type ReportDisplay = (typeof ReportDisplay)[keyof typeof ReportDisplay]
export const ReportBucket = { VALUE: 'VALUE', DAY: 'DAY', MONTH: 'MONTH', YEAR: 'YEAR' } as const
export type ReportBucket = (typeof ReportBucket)[keyof typeof ReportBucket]
/** 统计粒度：ROOT = 一条主记录算一行（缺省）；DETAIL = 所选内部明细的一行算一行，带着所属主记录的字段。 */
export const ReportGrain = { ROOT: 'ROOT', DETAIL: 'DETAIL' } as const
export type ReportGrain = (typeof ReportGrain)[keyof typeof ReportGrain]
export const ReportOperation = {
  COUNT: 'COUNT',
  COUNT_FIELD: 'COUNT_FIELD',
  COUNT_DISTINCT: 'COUNT_DISTINCT',
  /** 主记录数：去重计数所属主记录。只在明细粒度可用，不带字段。 */
  COUNT_ROOT: 'COUNT_ROOT',
  SUM: 'SUM',
  AVG: 'AVG',
  MIN: 'MIN',
  MAX: 'MAX',
  FORMULA: 'FORMULA'
} as const
export type ReportOperation = (typeof ReportOperation)[keyof typeof ReportOperation]
export interface ReportDimension {
  fieldId: string
  relationPath: string | null
  bucket: ReportBucket
}
export interface ReportMetric {
  id: string
  name: string
  operation: ReportOperation
  fieldId: string | null
  conditions?: DynamicSearchCondition | null
  formula?: { operator: 'ADD' | 'SUBTRACT' | 'MULTIPLY' | 'DIVIDE'; left: string; right: string } | null
  /** 多来源：指标属于哪个来源；null / 缺省 = 来源 1。计算指标必须为空。 */
  sourceId?: string | null
  format?: {
    unit?: string | null
    decimals?: number | null
    percent?: boolean
    financial?: boolean
    color?: string | null
  } | null
}
export interface ReportChart {
  barMode: 'GROUPED' | 'STACKED' | 'PERCENT'
  horizontal: boolean
  labels: boolean
  legendPosition: 'TOP' | 'RIGHT' | 'BOTTOM' | 'LEFT'
}
/** 透视表占比基准：所在行合计 / 所在列合计 / 总计。 */
export const ReportPivotPercent = { NONE: 'NONE', ROW: 'ROW', COLUMN: 'COLUMN', TOTAL: 'TOTAL' } as const
export type ReportPivotPercent = (typeof ReportPivotPercent)[keyof typeof ReportPivotPercent]
/** 仅 PIVOT 使用；null 时各项取默认值。 */
export interface ReportPivotOptions {
  subtotals: boolean
  rowTotals: boolean
  columnTotals: boolean
  percent: ReportPivotPercent
  maxColumnGroups: number
  /** 列组排序方向：缺省 / null 按列维度原值升序（存量行为）；true 降序（如月份新的在前）。 */
  columnDescending?: boolean | null
}
/** 行排序依据；配置里缺省 / null 表示沿用存量行为（见 reportSortDescending）。 */
export const ReportSortBy = { DIMENSION: 'DIMENSION', METRIC: 'METRIC' } as const
export type ReportSortBy = (typeof ReportSortBy)[keyof typeof ReportSortBy]
/**
 * 查看时点击列头的临时排序（不改配置）。metricId 非空：按该指标，透视表里 columnGroup 为所点列组的列键前缀（缺省或 [] = 行合计）；
 * metricId 为空：按第 dimension 个行维度（0 起）的值。
 */
export interface ReportSort {
  metricId?: string | null
  dimension?: number | null
  columnGroup?: (string | null)[]
  descending: boolean
}
export interface ReportConfig {
  objectId: string
  /** PIVOT 下即「行维度」（至少 1 个，与列维度合计 ≤ MAX_PIVOT_DIMENSIONS）；其余展示方式维持原上限 2。 */
  dimensions: ReportDimension[]
  /** 仅 PIVOT 使用，可为 0 个（与行维度合计 ≤ MAX_PIVOT_DIMENSIONS）；其它展示方式必须为空。存量配置可能缺省。 */
  columnDimensions?: ReportDimension[]
  pivot?: ReportPivotOptions | null
  metrics: ReportMetric[]
  equal: Record<string, unknown>
  filterFieldIds: string[]
  dateFieldId: string | null
  timeZone: string
  display: ReportDisplay
  sortMetricId: string | null
  descending: boolean
  /** 缺省 / null：沿用存量排序行为。DIMENSION：按行维度（分组）的值；METRIC：按 sortMetricId。方向都取 descending。 */
  sortBy?: ReportSortBy | null
  /** null = 不限制（只对汇总表、透视表有效，受 MAX_REPORT_TABLE_ROWS 保护；其它展示方式按 MAX_REPORT_CHART_GROUPS）。 */
  limit: number | null
  detailViewId: string | null
  /** 明细允许编辑：null 视为 false；detailViewId 为空时必须为 null/false。只能收不能放。 */
  detailEditable?: boolean | null
  conditions?: DynamicSearchCondition | null
  chart?: ReportChart | null
  /** 缺省或 null = 按主记录 */
  grain?: ReportGrain | null
  /** 仅 DETAIL：粒度明细的 ID */
  detailId?: string | null
  /** 多来源：来源 1 的显示名；单来源为 null / 缺省。 */
  sourceName?: string | null
  /** 多来源：来源 2 起；单来源为 null / 缺省（保存时空列表不输出）。 */
  extraSources?: ReportSource[] | null
  /** 行维度显示名，与 dimensions 等长；可为 null。 */
  dimensionLabels?: string[] | null
  /** 列维度显示名，与 columnDimensions 等长；可为 null。 */
  columnDimensionLabels?: string[] | null
}
/** 附加来源。字段键相对本来源的 objectId（与本来源的粒度）解析，写法与顶层相同。 */
export interface ReportSource {
  id: string
  name: string
  objectId: string
  grain?: ReportGrain | null
  detailId?: string | null
  /** 与顶层 dimensions 等长、逐位对应。 */
  dimensions: ReportDimension[]
  /** 与顶层 columnDimensions 等长（顶层为 null / 空时为 null）。 */
  columnDimensions?: ReportDimension[] | null
  conditions?: DynamicSearchCondition | null
  dateFieldId?: string | null
  /** 键 = 顶层 filterFieldIds 里的键，值 = 本来源的字段键；只存按规则推不出来的。 */
  filterTargets?: Record<string, string> | null
  /** 本来源的下钻明细视图（业务方 2026-10-04「加进去」；契约变更 C1）。 */
  detailViewId?: string | null
  /** 本来源的明细允许编辑：只存 true 或 null（契约变更 C1）；只能收不能放。 */
  detailEditable?: boolean | null
}
export interface ReportSourceSummary {
  id: string
  name: string
  objectId: string
  recordCount: number
  detailName?: string | null
}
export const MAX_REPORT_EXTRA_SOURCES = 3
export const MAX_REPORT_MULTI_SOURCE_METRICS = 10
export interface ReportFilter {
  id: string
  name: string
  objectId: string
  fieldId: string
  dateRange: boolean
  targets: Record<string, string>
}
export interface ReportQuery {
  applicationId: string
  reportId: string
  equal?: Record<string, unknown>
  dateFrom?: string
  dateTo?: string
  context?: RecordContext
  /** PIVOT 下为行键前缀（可短于行维度数 → 小计格下钻）。 */
  group?: (string | null)[]
  /** 透视格下钻的列键前缀；[] 或缺省 = 不按列限定。 */
  columnGroup?: (string | null)[]
  pageNo?: number
  pageSize?: number
  conditions?: DynamicSearchCondition | null
  metricId?: string | null
  /** 查看时点击列头的临时排序；缺省按配置。导出带同一个 sort 即与屏幕同序。 */
  sort?: ReportSort | null
  /** 仅多来源下钻：指定来源；一般不传，由 metricId 推出。 */
  sourceId?: string | null
}
export interface ReportGroup {
  keys: (string | null)[]
  labels: string[]
  values: Record<string, string | null>
}
export interface ReportResult {
  dimensionNames: string[]
  metrics: ReportMetric[]
  groups: ReportGroup[]
  totals: Record<string, string | null>
  totalGroups: number
  recordCount: number
  canExport: boolean
  timeZone: string
  /** 仅 PIVOT 时非 null。 */
  pivot?: ReportPivotResult | null
  /** 仅 DETAIL：粒度明细的名称；此时 recordCount 为来源明细行数。 */
  detailName?: string | null
  /** 仅多来源：来源 1、附加来源按配置顺序。 */
  sources?: ReportSourceSummary[] | null
}
export interface ReportPivotHeader {
  keys: (string | null)[]
  labels: string[]
}
export interface ReportPivotCell {
  /** 前缀语义：长度 = 行维度数 → 叶子；更短 → 该层小计；[] → 列合计行。 */
  rowKeys: (string | null)[]
  /** 前缀语义：长度 = 列维度数 → 叶子；更短 → 列组小计；[] → 行合计列组。 */
  columnKeys: (string | null)[]
  /** 精确十进制字符串；null ≠ 0。 */
  values: Record<string, string | null>
  /** 仅 percent≠NONE；0..1 的十进制字符串，分母为 0 → null。 */
  ratios?: Record<string, string | null>
}
export interface ReportPivotResult {
  rowDimensionNames: string[]
  columnDimensionNames: string[]
  rows: ReportPivotHeader[]
  columns: ReportPivotHeader[]
  cells: ReportPivotCell[]
  rowsTruncated: boolean
  columnsTruncated: boolean
  totalRowGroups: number
  totalColumnGroups: number
}
/** 数据视图列表查询的「统计下钻」参数；条件构造由服务端与 report-details 共用。 */
export interface ReportDrill {
  applicationId: string
  reportId: string
  group?: (string | null)[]
  columnGroup?: (string | null)[]
  metricId?: string | null
  equal?: Record<string, unknown>
  dateFrom?: string
  dateTo?: string
  conditions?: DynamicSearchCondition | null
  /** 同 ReportQuery.context：统计放在记录页区块内时必须随下钻带上；列表自身的 context 不能代替。 */
  context?: RecordContext
}
