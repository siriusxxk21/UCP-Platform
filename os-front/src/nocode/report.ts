import {
  ReportDisplay,
  ReportGrain,
  ReportOperation,
  ReportPivotPercent,
  type ReportConfig,
  type ReportFilter,
  type ReportPivotOptions,
  type ReportQuery
} from '@/types/nocode/report'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { PublishedDefinition } from '@/types/nocode/application'
import { fieldRelation } from './business-fields'
import type { ObjectDetail, ObjectRelation } from '@/types/nocode/data-center'
import { calculationQueryReady, calculationValueField, storedOrderedCalculation } from './calculation-presentation'

/** 统计字段下拉的一项；detailId 只出现在「粒度明细自己的字段」上（主表字段与关系目标字段不带）。 */
export interface ReportFieldEntry {
  value: string
  label: string
  field: ObjectField
  objectId: string
  detailId?: string
}
/** 明细粒度时取粒度明细的 ID；主记录粒度（缺省、null、ROOT）返回 undefined。 */
export const reportDetailId = (config?: Pick<ReportConfig, 'grain' | 'detailId'> | null): string | undefined =>
  config?.grain === ReportGrain.DETAIL ? config.detailId || undefined : undefined
/**
 * 这个视图能不能当这张统计的「下钻明细视图」：须是同一对象的视图；按明细行统计时还须是「一行表示 = 一条内部明细」、
 * 明细来源就是统计所按明细的数据视图（下钻出来一行一条明细，条数与金额才对得上格子）。与后端保存校验同一规则。
 */
export function reportDrillViewMatches(
  config: Pick<ReportConfig, 'objectId' | 'grain' | 'detailId'>,
  view?: { objectId?: string; composition?: { grain?: string; detailId?: string | null } | null } | null
): boolean {
  if (!view || view.objectId !== config.objectId) return false
  const detailId = reportDetailId(config)
  return !detailId || (view.composition?.grain === ReportGrain.DETAIL && view.composition.detailId === detailId)
}
/** 对象上启用的内部明细。 */
export const reportDetails = (definition?: Pick<PublishedDefinition, 'details'>): ObjectDetail[] =>
  (definition?.details || []).filter(d => !!d.id && d.state !== MemberState.INACTIVE)
/** 条目的字段配置：粒度明细的字段取该明细自己的 fieldOptions，其余取对象的。 */
export function reportEntryOptions(
  entry: Pick<ReportFieldEntry, 'field' | 'objectId' | 'detailId'>,
  objects: Record<string, { definition: PublishedDefinition }>
) {
  const definition = objects[entry.objectId]?.definition
  const owner = entry.detailId ? definition?.details?.find(d => d.id === entry.detailId) : definition
  return owner?.fieldOptions[entry.field.id!]
}

/** 明确列出最多两段单值关系的字段路径，搭建者无需手写连接表达式。 */
// detailId 缺省 = 主记录粒度：只有主表字段与主表上的关系；明细上的关系（sourceDetailId 非空）不展开。
// 传入 detailId = 明细粒度：再加这个明细的字段与它上面的单值关系。顺序：主表字段 → 明细字段 → 主表关系路径 → 明细关系路径。
export function reportFieldOptions(
  objectId: string,
  objects: Record<string, { definition: PublishedDefinition }>,
  readiness: Record<string, Record<string, string>> = {},
  detailId?: string | null
) {
  const output: ReportFieldEntry[] = []
  const detail = detailId ? reportDetails(objects[objectId]?.definition).find(d => d.id === detailId) : undefined
  const visit = (id: string, path: string[], labels: string[]) => {
    const d = objects[id]?.definition
    if (!d) return
    reportFields(d, readiness[id]).forEach(f =>
      output.push({
        value: path.length ? path.join('/') + ':' + f.id : f.id!,
        label: [...labels, f.name].join(' / '),
        field: f,
        objectId: id
      })
    )
    if (!path.length && detail)
      reportFields(detail, readiness[id]).forEach(f =>
        output.push({ value: f.id!, label: detail.name + ' · ' + f.name, field: f, objectId: id, detailId: detail.id! })
      )
    if (path.length >= 2) return
    const expand = (owner: string | null) =>
      d.relations
        .filter(
          r => r.kind !== RelationType.MANY_TO_MANY && !path.includes(r.id!) && (r.sourceDetailId || null) === owner
        )
        .forEach(r => visit(r.targetObjectId, [...path, r.id!], [...labels, r.name]))
    expand(null)
    if (!path.length && detail) expand(detail.id)
  }
  visit(objectId, [], [])
  return output
}

/** 只扩展本版已就绪有序字段，其他公式和明细汇总保留原报表范围。 */
// 入参也可以是一个内部明细：明细的字段用明细自己的 fieldOptions 判。
export function reportFields(
  definition: Pick<PublishedDefinition, 'fields' | 'fieldOptions'>,
  readiness: Record<string, string> = {}
): ObjectField[] {
  return definition.fields
    .filter(field => {
      const options = definition.fieldOptions[field.id!]
      if (options?.state === MemberState.INACTIVE) return false
      if (field.type !== FieldType.FORMULA) return scalarField(field)
      return storedOrderedCalculation(options) && calculationQueryReady(options, readiness[field.id!])
    })
    .map(field => calculationValueField(field, definition.fieldOptions[field.id!]))
}

export const displayOptions = [
  { value: ReportDisplay.METRIC, label: '指标卡' },
  { value: ReportDisplay.BAR, label: '柱状图' },
  { value: ReportDisplay.LINE, label: '折线图' },
  { value: ReportDisplay.PIE, label: '饼图' },
  { value: ReportDisplay.TABLE, label: '汇总表' },
  { value: ReportDisplay.PIVOT, label: '透视表' }
]
export const pivotPercentOptions = [
  { value: ReportPivotPercent.NONE, label: '不显示占比' },
  { value: ReportPivotPercent.ROW, label: '占所在行合计' },
  { value: ReportPivotPercent.COLUMN, label: '占所在列合计' },
  { value: ReportPivotPercent.TOTAL, label: '占总计' }
]
/** 约定默认值；存量或 null 的 pivot 按此补齐。 */
export const defaultPivotOptions = (): ReportPivotOptions => ({
  subtotals: true,
  rowTotals: true,
  columnTotals: true,
  percent: ReportPivotPercent.NONE,
  maxColumnGroups: 24
})
export const pivotOptions = (config: Pick<ReportConfig, 'pivot'>): ReportPivotOptions => ({
  ...defaultPivotOptions(),
  ...config.pivot
})
/**
 * 透视表行维度数 + 列维度数的上限（后端 ApplicationReports.MAX_PIVOT_DIMENSIONS 同值）。行、列各自不再限个数，只保留这一条：
 * 小计与合计要按 (行维度数+1)×(列维度数+1) 组分组集合重新聚合，维度越多查询越重。
 */
export const MAX_PIVOT_DIMENSIONS = 10
/** 非透视展示方式（汇总表、柱、线、饼）的分组上限，保持不变。 */
export const MAX_GROUP_DIMENSIONS = 2
/** 汇总表、透视表「不限制」时一次展示的行数保护值，也是填数字时的上限（后端 ApplicationReports.MAX_TABLE_ROWS 同值）。 */
export const MAX_REPORT_TABLE_ROWS = 20000
/** 透视表行数超过原上限（200）时，行数 × 已展示列组数不超过这个数（后端 ApplicationReports.MAX_PIVOT_CELLS 同值）。 */
export const MAX_REPORT_PIVOT_CELLS = 100000
/** 指标卡、柱、线、饼的展示组数上限（保持不变）；留空时也按这个数（后端 ApplicationReports.MAX_CHART_GROUPS 同值）。 */
export const MAX_REPORT_CHART_GROUPS = 200
/** 「不限制」只对汇总表与透视表有效。 */
export const reportTableDisplay = (display: ReportConfig['display']) =>
  display === ReportDisplay.TABLE || display === ReportDisplay.PIVOT
export const reportLimitMax = (display: ReportConfig['display']) =>
  reportTableDisplay(display) ? MAX_REPORT_TABLE_ROWS : MAX_REPORT_CHART_GROUPS
/**
 * 配置实际生效的排序方向。存量透视表（没有 sortBy）在没选排序指标时恒按维度值升序，descending 不生效；
 * 其余情况（含存量汇总表与图表）都取 descending。
 */
export const reportSortDescending = (
  config: Pick<ReportConfig, 'display' | 'sortBy' | 'sortMetricId' | 'descending'>
) => (!config.sortBy && !config.sortMetricId && config.display === ReportDisplay.PIVOT ? false : !!config.descending)
export const pivotDimensionLimitMessage = `维度过多，请减少：透视表行维度与列维度合计最多 ${MAX_PIVOT_DIMENSIONS} 个（小计与合计要按 (行维度数+1)×(列维度数+1) 组分组重新聚合，维度越多查询越重）`
/** 同一字段（字段+关系路径+分桶）不能同时作为行维度和列维度。 */
export const dimensionKey = (d: { fieldId: string; relationPath: string | null; bucket: string }) =>
  JSON.stringify([d.fieldId, d.relationPath || null, d.bucket])
export const operationOptions = [
  { value: ReportOperation.COUNT, label: '记录计数' },
  { value: ReportOperation.COUNT_FIELD, label: '非空计数' },
  { value: ReportOperation.COUNT_DISTINCT, label: '去重计数' },
  { value: ReportOperation.SUM, label: '求和' },
  { value: ReportOperation.AVG, label: '平均值' },
  { value: ReportOperation.MIN, label: '最小值' },
  { value: ReportOperation.MAX, label: '最大值' },
  { value: ReportOperation.FORMULA, label: '指标计算' }
]
/** 计算方式下拉：主记录粒度原样；明细粒度下「记录计数」叫「明细行数」，并在「去重计数」后多一项「主记录数」。 */
export function reportOperationOptions(detailGrain: boolean): Array<{ value: ReportOperation; label: string }> {
  if (!detailGrain) return operationOptions
  return operationOptions.flatMap((o): Array<{ value: ReportOperation; label: string }> => {
    if (o.value === ReportOperation.COUNT) return [{ value: o.value, label: '明细行数' }]
    if (o.value === ReportOperation.COUNT_DISTINCT) return [o, { value: ReportOperation.COUNT_ROOT, label: '主记录数' }]
    return [o]
  })
}
export const dateField = (f: ObjectField) => f.type === FieldType.DATE || f.type === FieldType.DATETIME
export const numericField = (f: ObjectField, relations: ObjectRelation[] = []) =>
  !fieldRelation(relations, f.id) &&
  [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(t => t === f.type)
/** 金额、小数、百分比：作维度几乎一定是误用（每个不同的数值各成一组）；整数（年度、期间）不算。 */
export const amountLikeField = (f: ObjectField) =>
  [FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(t => t === f.type)
export const scalarField = (f: ObjectField) =>
  [
    FieldType.TEXT,
    FieldType.INTEGER,
    FieldType.DECIMAL,
    FieldType.MONEY,
    FieldType.PERCENT,
    FieldType.BOOLEAN,
    FieldType.DATE,
    FieldType.DATETIME,
    FieldType.TIME,
    FieldType.SELECT,
    FieldType.AUTO_NUMBER,
    FieldType.REFERENCE,
    FieldType.UUID,
    FieldType.ORGANIZATION,
    FieldType.DEPARTMENT,
    FieldType.USER,
    FieldType.POST,
    FieldType.USER_GROUP
  ].some(t => t === f.type)
export function defaultReport(objectId: string): ReportConfig {
  return {
    objectId,
    dimensions: [],
    metrics: [{ id: 'count', name: '记录数', operation: ReportOperation.COUNT, fieldId: null }],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Shanghai',
    display: ReportDisplay.METRIC,
    sortMetricId: null,
    descending: false,
    sortBy: null,
    limit: null,
    detailViewId: null,
    columnDimensions: [],
    pivot: null,
    detailEditable: null
  }
}
/** 金额标签不经 Number 或 toFixed；仅省略两位之后的末尾零，不改变数值。 */
export function exactNumber(value: string | null | undefined): string {
  if (value == null) return '—'
  if (!/^-?\d+(\.\d+)?$/.test(value)) return value
  const [integer, fraction] = value.split('.')
  const decimal = fraction && fraction.length > 2 ? fraction.replace(/0+$/, '').padEnd(2, '0') : fraction
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (decimal ? '.' + decimal : '')
}
/** 页面筛选只向显式绑定的报表传值，空值表示取消筛选，不把 false/0 当作空。 */
export function reportFilters(
  reportId: string,
  definitions: ReportFilter[],
  values: Record<string, unknown>
): Pick<ReportQuery, 'equal' | 'dateFrom' | 'dateTo'> {
  const result: Pick<ReportQuery, 'equal' | 'dateFrom' | 'dateTo'> = { equal: {} }
  for (const f of definitions) {
    const field = f.targets[reportId],
      value = values[f.id]
    if (!field || value == null || value === '') continue
    if (f.dateRange && Array.isArray(value)) {
      result.dateFrom = value[0] || undefined
      result.dateTo = value[1] || undefined
    } else if (!f.dateRange) result.equal![field] = value
  }
  return result
}
