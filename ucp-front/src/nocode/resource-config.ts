import type { ViewConfig, ViewInteraction, ViewListConfig } from '@/types/nocode/application-ui'
import { ListOverflow } from '@/types/nocode/application-ui'
import type { ViewQueryOptions } from '@/types/nocode/data-scope'
import type { ObjectField } from '@/types/nocode/object'
import { ReportDisplay, ReportGrain, type ReportConfig } from '@/types/nocode/report'
import { scopeFields, validateScope } from './data-scope'
import { recordFilterValue } from './record-form'
import { pivotOptions } from './report'
import { metricNeedsField, validateReport, type ReportFieldScope } from './report-presentation'
import { normalizeReportSources } from './report-sources'
import { defaultViewList } from './runtime-list'
import { isRelativeDate, relativeDateError, relativeDateField } from './relative-date'

/** 编辑入口补齐旧配置后，应用阶段可直接使用查询与列表配置。 */
export type EditableViewConfig = ViewConfig & {
  interaction: ViewInteraction
  list: ViewListConfig
  query: ViewQueryOptions
}

export function normalizeViewConfigForEdit(
  config: ViewConfig,
  fields: ObjectField[],
  interaction: ViewInteraction
): EditableViewConfig {
  const equal = { ...config.equal }
  const query: ViewQueryOptions = config.query
    ? { ...config.query, fixed: [...config.query.fixed] }
    : { fixed: [], defaults: {}, candidates: {} }
  for (const [id, value] of Object.entries(equal)) {
    // 完整数组等值和特殊字段维持旧协议，不能直接替换为多选范围。
    if (value == null || typeof value === 'object' || !scopeFields(fields).some(field => field.id === id)) continue
    query.fixed.push({ fieldId: id, operator: 'eq', value, valueSource: 'CONSTANT' })
    delete equal[id]
  }
  return {
    ...config,
    equal,
    query,
    interaction: config.interaction || interaction,
    list: config.list || defaultViewList()
  }
}

/** 在副本上规范化。validateScope 会转换条件值，条件对象也必须隔离。 */
export function prepareViewConfig(
  config: EditableViewConfig,
  fields: ObjectField[],
  dictionaries: Array<{ fieldId: string; dictionaryId: string }>
): EditableViewConfig {
  const list = { ...config.list }
  if (list.queryFieldIds.length > 6) throw new Error('常用查询最多配置 6 个字段')
  list.columnWidths = Object.fromEntries(
    Object.entries(list.columnWidths).filter(([id, width]) => config.fieldIds.includes(id) && width != null)
  )
  // 「内容超出列宽时自动截断」没有开就不带这个键：存量视图保存后的定义与原来逐键相同。
  if (list.overflow !== ListOverflow.ELLIPSIS) delete list.overflow
  if (!config.fieldIds.length) throw new Error('至少选择一个显示字段')
  const query: ViewQueryOptions = {
    ...config.query,
    fixed: config.query.fixed.map(condition => ({ ...condition })),
    defaults: { ...config.query.defaults }
  }
  if (query.fixed.length) validateScope({ logic: 'AND', conditions: query.fixed, groups: [] }, fields)
  if (new Set(query.fixed.map(condition => condition.fieldId)).size !== query.fixed.length)
    throw new Error('固定范围字段不能重复；同一字段选择多个值时，请在一条条件中使用“属于任意一个”或“包含任意”')
  for (const [id, raw] of Object.entries(query.defaults)) {
    if (!list.queryFieldIds.includes(id)) throw new Error('默认查询字段需要同时配置为常用查询字段')
    const field = fields.find(field => field.id === id)
    if (!field) throw new Error('默认查询字段已不可用')
    // 相对日期（今天、本月……）原样保存，打开列表时按当天换算。
    if (isRelativeDate(raw)) {
      if (!relativeDateField(field.type)) throw new Error(`相对日期只能用于日期或日期时间字段：${field.name}`)
      const error = relativeDateError(raw)
      if (error) throw new Error(error)
      continue
    }
    const normalized = recordFilterValue(field, raw)
    if (normalized === undefined) delete query.defaults[id]
    else query.defaults[id] = normalized
  }
  if (
    dictionaries.some(binding => !binding.fieldId || !binding.dictionaryId) ||
    new Set(dictionaries.map(binding => binding.fieldId)).size !== dictionaries.length
  )
    throw new Error('筛选字典需要指定字段和字典，字段不能重复')
  return {
    ...config,
    list,
    query,
    formId: config.formId || null,
    sortFieldId: config.sortFieldId || null,
    filterDictionaries: Object.fromEntries(dictionaries.map(binding => [binding.fieldId, binding.dictionaryId]))
  }
}

/** 保留指标、分组和关联字段协议，仅在所有校验通过后返回可应用配置。 */
export function prepareReportConfig(
  config: ReportConfig,
  fields: Array<{ value: string; field: ObjectField }>,
  filters: Array<{ fieldId: string; value: unknown }>,
  scope?: ReportFieldScope
): ReportConfig {
  if (!config.objectId) throw new Error('请选择统计对象')
  if (config.display === ReportDisplay.METRIC && config.dimensions.length)
    throw new Error('指标卡不设置分组，请移除分组或选择图表')
  if (config.display !== ReportDisplay.METRIC && !config.dimensions.length) throw new Error('至少添加一个分组')
  if (
    config.dimensions.some(dimension => !dimension.fieldId) ||
    config.metrics.some(metric => !metric.name || (metricNeedsField(metric) && !metric.fieldId))
  )
    throw new Error('请补齐分组和指标')
  if (filters.some(filter => !filter.fieldId) || new Set(filters.map(filter => filter.fieldId)).size !== filters.length)
    throw new Error('固定筛选字段不能为空或重复')
  const result: ReportConfig = {
    ...config,
    equal: Object.fromEntries(
      filters.map(filter => {
        const field = fields.find(field => field.value === filter.fieldId)
        if (!field) throw new Error('报表固定筛选字段已不可用')
        return [filter.fieldId, recordFilterValue(field.field, filter.value)]
      })
    ),
    dateFieldId: config.dateFieldId || null,
    sortMetricId: config.sortMetricId || null,
    // 存量配置没有 sortBy，保持没有；有的话与排序指标保持一致。行数留空（含被清空的输入框）统一存为 null = 不限制。
    sortBy: config.sortBy ? (config.sortMetricId ? 'METRIC' : 'DIMENSION') : null,
    limit: config.limit ?? null,
    detailViewId: config.detailViewId || null,
    columnDimensions: config.display === ReportDisplay.PIVOT ? config.columnDimensions || [] : [],
    pivot: config.display === ReportDisplay.PIVOT ? pivotOptions(config) : null,
    detailEditable: config.detailViewId ? !!config.detailEditable : null
  }
  // 统计粒度：主记录粒度不带 grain / detailId 两个键（与存量配置逐键相同）。明细粒度也可以挂下钻明细视图并允许编辑，
  // 视图须按同一明细逐行显示——配置器只列出这样的视图，保存时后端再按资源判一次。
  if (config.grain !== ReportGrain.DETAIL) {
    delete result.grain
    delete result.detailId
  }
  // 多个数据来源：单来源不带四个新键、指标不带 sourceId（与存量逐键相同）；多来源整理附加来源（契约 laneM 2.1、第 8 章）。
  const normalized = normalizeReportSources(result)
  validateReport(normalized, scope)
  return normalized
}
