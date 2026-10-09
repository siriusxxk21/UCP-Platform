<script setup lang="ts">
import { v4 as uuidv4 } from 'uuid'
import { computed, ref, watch } from 'vue'
import type { PublishedObject, ApplicationResource } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import {
  ReportDisplay,
  ReportBucket,
  ReportSortBy,
  ReportGrain,
  MAX_REPORT_EXTRA_SOURCES,
  type ReportConfig,
  type ReportDimension,
  type ReportMetric,
  type ReportResult,
  type ReportSource
} from '@/types/nocode/report'
import type { ViewConfig } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import {
  displayOptions,
  reportOperationOptions,
  amountLikeField,
  reportDetailId,
  reportDrillViewMatches,
  reportDetails,
  numericField,
  dateField,
  scalarField,
  reportFieldOptions,
  reportFields,
  pivotOptions,
  pivotPercentOptions,
  dimensionKey,
  MAX_GROUP_DIMENSIONS,
  MAX_PIVOT_DIMENSIONS,
  MAX_REPORT_CHART_GROUPS,
  MAX_REPORT_PIVOT_CELLS,
  MAX_REPORT_TABLE_ROWS,
  pivotDimensionLimitMessage,
  reportLimitMax,
  reportSortDescending,
  reportTableDisplay
} from '@/nocode/report'
import {
  defaultReportChart,
  metricNeedsField,
  formatReportValue,
  financialReportMetric,
  metricDescription,
  duplicateReportMetrics,
  formulaLabels,
  metricGrainError,
  reportFieldScope,
  reportGrainMessages,
  validateReport
} from '@/nocode/report-presentation'
import {
  MAIN_SOURCE_ID,
  alignSlots,
  alignedChoices,
  bucketLabels,
  carriedSourcesNotice,
  defaultSource,
  defaultSourceDateField,
  dimensionName,
  inferredFilterKey,
  metricSource,
  multiSource,
  normalizeReportSources,
  reportDimensionKey,
  reportSourceMessages,
  reportSources,
  reportSourcesBlocked,
  reportSourcesFooter,
  resetSourceObject,
  sourceFieldOptions,
  sourceLabel,
  validateReportSources,
  type ReportSourceView
} from '@/nocode/report-sources'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { recordFilterValue } from '@/nocode/record-form'
import ReportConditionEditor from './ReportConditionEditor.vue'
import ReportChart from './AsyncReportChart.vue'
import FixedFilterField from './FixedFilterField.vue'
import ReportPivotTable from './ReportPivotTable.vue'
const config = defineModel<ReportConfig>({ required: true })
const props = defineProps<{
  applicationId: string
  objects: Record<string, PublishedObject>
  resources: ApplicationResource[]
  fixedFilters?: Array<{ fieldId: string; value: unknown }>
  orderedReadiness?: Record<string, Record<string, string>>
}>()
const api = useNocodePlatform().applications
const object = computed(() => props.objects[config.value.objectId]?.definition)
const fields = computed(() =>
  object.value ? reportFields(object.value, props.orderedReadiness?.[config.value.objectId]) : []
)
const fieldOptions = computed(() =>
  reportFieldOptions(config.value.objectId, props.objects, props.orderedReadiness, reportDetailId(config.value))
)
// —— 统计粒度（按主记录 / 按明细行）——
const detailGrain = computed(() => config.value.grain === ReportGrain.DETAIL)
const details = computed(() => reportDetails(object.value))
const grainDetail = computed(() =>
  detailGrain.value ? details.value.find(d => d.id === config.value.detailId) : undefined
)
const grainScope = computed(() => reportFieldScope(config.value, object.value))
/** 粒度明细里可用于统计的字段（与字段下拉同一口径）。 */
const detailFields = computed(() => fieldOptions.value.filter(o => o.detailId).map(o => o.field))
const operations = computed(() => reportOperationOptions(detailGrain.value))
// —— 多个数据来源（契约 laneM）：来源 1 = 顶层配置，附加来源 = extraSources ——
const multi = computed(() => multiSource(config.value))
const sources = computed(() => reportSources(config.value))
const metricLimit = computed(() => (multi.value ? 10 : 5))
const sourcesBlocked = computed(() => reportSourcesBlocked(config.value))
const sourcesFull = computed(() => (config.value.extraSources?.length || 0) >= MAX_REPORT_EXTRA_SOURCES)
const objectChoices = computed(() =>
  Object.values(props.objects).map(o => ({ value: o.objectId, label: o.definition.objectName }))
)
const sourceNotice = ref('')
const viewOf = (s: ReportSource) => sources.value.find(v => v.id === s.id)!
const entriesOf = (s: Pick<ReportSourceView, 'objectId' | 'grain' | 'detailId'>) =>
  sourceFieldOptions(s, props.objects, props.orderedReadiness)
/** 附加来源的字段口径（与来源 1 的 fields / detailFields / detailGrain 同义）。 */
function sourceContext(s: ReportSourceView) {
  const definition = props.objects[s.objectId]?.definition
  const entries = entriesOf(s)
  const detailId = reportDetailId(s)
  return {
    fields: definition ? reportFields(definition, props.orderedReadiness?.[s.objectId]) : [],
    detailFields: entries.filter(o => o.detailId).map(o => o.field),
    detail: !!detailId,
    relations: definition?.relations,
    detailName: reportDetails(definition).find(d => d.id === detailId)?.name,
    entries,
    source: s as ReportSourceView | undefined
  }
}
/** 指标所属来源的字段口径：来源 1（含单来源）与原来逐项相同。 */
function metricContext(m: ReportMetric) {
  const source = multi.value ? metricSource(config.value, m) : undefined
  if (!source || source.main)
    return {
      fields: fields.value,
      detailFields: detailFields.value,
      detail: detailGrain.value,
      relations: object.value?.relations,
      detailName: grainDetail.value?.name,
      entries: fieldOptions.value,
      source: undefined as ReportSourceView | undefined
    }
  return sourceContext(source)
}
const metricFieldPool = (m: ReportMetric) => {
  const c = metricContext(m)
  return [...c.fields, ...c.detailFields]
}
const metricOperations = (m: ReportMetric) =>
  metricContext(m).source ? reportOperationOptions(metricContext(m).detail) : operations.value
function metricError(m: ReportMetric) {
  const source = metricContext(m).source
  if (!source) return metricGrainError(m, config.value, grainScope.value)
  return metricGrainError(m, source, reportFieldScope(source, props.objects[source.objectId]?.definition))
}
const metricNames = (m: ReportMetric) => {
  const c = metricContext(m)
  return c.source ? Object.fromEntries(c.entries.map(f => [f.value, f.label])) : names.value
}
const metricDescriptionOf = (m: ReportMetric) => {
  const source = metricContext(m).source
  return metricDescription(m, source ? { ...config.value, grain: source.grain } : config.value, metricNames(m))
}
const sourceOptions = computed(() => sources.value.map(s => ({ value: s.id, label: sourceLabel(s, props.objects) })))
/** 换指标的来源：字段键属于原来源，字段与指标条件清空（契约第 3 章）。 */
function changeMetricSource(m: ReportMetric, id: string) {
  if (id === MAIN_SOURCE_ID) delete m.sourceId
  else m.sourceId = id
  m.fieldId = null
  m.conditions = null
  if (m.operation === 'COUNT_ROOT' && !metricContext(m).detail) m.operation = 'COUNT'
}
/** 配置里的粒度明细不在启用的明细里（接口写入或明细后来被停用）：照服务端同一句提示。 */
const grainDetailError = computed(() => {
  if (!detailGrain.value || grainDetail.value) return ''
  if (!config.value.detailId) return reportGrainMessages.M11b
  const stopped = (object.value?.details || []).find(d => d.id === config.value.detailId)
  return stopped ? `统计所选的内部明细「${stopped.name}」已停用` : '统计所选的内部明细不存在'
})
const grainNotice = ref('')
const references = computed(() =>
  Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum }))
)
const names = computed(() => Object.fromEntries(fieldOptions.value.map(f => [f.value, f.label])))
const preview = ref<ReportResult>(),
  previewConfig = ref<ReportConfig>()
const previewBusy = ref(false),
  previewError = ref(''),
  previewStale = ref(false),
  previewTable = ref(false)
let previewGeneration = 0
const tab = ref('data')
let rememberedDimensions: ReportConfig['dimensions'] = []
const displayNotice = ref('')
const pivot = computed(() => config.value.display === ReportDisplay.PIVOT)
/** 行列维度重复（字段+关系路径+分桶相同）时不能保存。 */
const pivotDuplicate = computed(() => {
  const rows = new Set(config.value.dimensions.map(dimensionKey))
  return (config.value.columnDimensions || []).some(d => rows.has(dimensionKey(d)))
})
/** 同一侧（行或列）出现两个相同维度。 */
const duplicateIn = (list: ReportDimension[]) => new Set(list.map(dimensionKey)).size !== list.length
/** 透视表只限行 + 列合计数；达到上限时两个「添加」按钮都禁用并说明原因。 */
const pivotDimensionCount = computed(
  () => config.value.dimensions.length + (config.value.columnDimensions?.length || 0)
)
const pivotFull = computed(() => pivot.value && pivotDimensionCount.value >= MAX_PIVOT_DIMENSIONS)
const detailView = computed(
  () =>
    props.resources.find(r => r.id === config.value.detailViewId && r.kind === ResourceKind.VIEW)?.config as unknown as
      ViewConfig | undefined
)
const detailViewFiltered = computed(
  () =>
    !!detailView.value &&
    (Object.keys(detailView.value.equal || {}).length > 0 || (detailView.value.query?.fixed?.length || 0) > 0)
)
/** 下钻明细视图的候选：同一对象的视图；按明细行统计时只列按同一明细逐行显示的数据视图。 */
const drillViewOptions = computed(() =>
  props.resources
    .filter(
      r => r.kind === ResourceKind.VIEW && reportDrillViewMatches(config.value, r.config as unknown as ViewConfig)
    )
    .map(r => ({ value: r.id, label: r.name }))
)
/** 已选的下钻视图不按当前粒度逐行显示（视图后来被改了形状等）：照服务端同一句提示，保存会被拒。 */
const drillViewError = computed(() =>
  detailView.value && !reportDrillViewMatches(config.value, detailView.value)
    ? reportGrainMessages.M8(grainDetail.value?.name || '所选明细')
    : ''
)
// 明细允许编辑依附于下钻视图：取消视图即回到默认只读。
watch(
  () => config.value.detailViewId,
  id => {
    if (!id && config.value.detailEditable != null) config.value.detailEditable = null
  }
)
/**
 * 排序：依据（行维度的值 / 某个指标）+ 方向。存量配置没有 sortBy，界面按它实际生效的样子显示（见 reportSortDescending）；
 * 只有动了排序控件才写入 sortBy，所以打开再保存不改变存量统计的行为。
 */
const SORT_BY_DIMENSION = '@dimension'
const tableDisplay = computed(() => reportTableDisplay(config.value.display))
const sortTarget = computed(() =>
  config.value.sortBy === ReportSortBy.DIMENSION ? SORT_BY_DIMENSION : config.value.sortMetricId || SORT_BY_DIMENSION
)
const sortTargetOptions = computed(() => [
  { value: SORT_BY_DIMENSION, label: pivot.value ? '按行维度的值' : '按分组的值' },
  ...config.value.metrics.map(m => ({ value: m.id, label: '按指标：' + m.name }))
])
const sortDirection = computed(() => (reportSortDescending(config.value) ? 'DESC' : 'ASC'))
const directionOptions = [
  { value: 'ASC', label: '升序' },
  { value: 'DESC', label: '降序' }
]
function selectSortTarget(value: string) {
  const descending = reportSortDescending(config.value)
  config.value.sortMetricId = value === SORT_BY_DIMENSION ? null : value
  config.value.sortBy = value === SORT_BY_DIMENSION ? ReportSortBy.DIMENSION : ReportSortBy.METRIC
  config.value.descending = descending
}
function selectSortDirection(value: string) {
  config.value.sortBy = config.value.sortMetricId ? ReportSortBy.METRIC : ReportSortBy.DIMENSION
  config.value.descending = value === 'DESC'
}
/** 排序指标被移除：明确选过「按指标」的回到「按维度的值」，存量配置（没有 sortBy）保持原样。 */
function clearSortMetric() {
  config.value.sortMetricId = null
  if (config.value.sortBy) config.value.sortBy = ReportSortBy.DIMENSION
}
function selectColumnDirection(value: string) {
  if (config.value.pivot) config.value.pivot.columnDescending = value === 'DESC' ? true : null
}
function changeDisplay() {
  displayNotice.value = ''
  // 列维度与透视选项只属于透视表；切走即清空，避免残留配置随保存生效。
  if (config.value.display === ReportDisplay.PIVOT) {
    config.value.columnDimensions ||= []
    config.value.pivot = pivotOptions(config.value)
  } else {
    config.value.columnDimensions = []
    config.value.pivot = null
  }
  if (config.value.display === ReportDisplay.METRIC) {
    rememberedDimensions = JSON.parse(JSON.stringify(config.value.dimensions))
    config.value.dimensions = []
    displayNotice.value = '指标卡展示总体数据；本次编辑中切回图表可恢复原分组。'
  } else if (!config.value.dimensions.length) {
    if (rememberedDimensions.length) config.value.dimensions = rememberedDimensions
    else addDimension()
  }
  // 透视表可以有更多行维度；切到其它展示方式时只保留前两个分组（其余展示方式上限不变）。
  if (config.value.display !== ReportDisplay.PIVOT && config.value.dimensions.length > MAX_GROUP_DIMENSIONS) {
    config.value.dimensions = config.value.dimensions.slice(0, MAX_GROUP_DIMENSIONS)
    displayNotice.value = '非透视展示最多两个分组，已保留前两个行维度。'
  }
  // 「不限制」与更大的行数只对汇总表、透视表有效；切到图表时超出图表上限的数字收回到上限。
  if (config.value.limit != null && config.value.limit > reportLimitMax(config.value.display))
    config.value.limit = reportLimitMax(config.value.display)
}
watch(
  [config, fields],
  () => {
    config.value.chart ||= defaultReportChart()
    config.value.metrics.forEach(m => {
      m.format ||= {}
      m.format.financial = financialReportMetric(m, metricFieldPool(m).find(f => f.id === m.fieldId)?.type)
    })
    previewStale.value = true
  },
  { deep: true, immediate: true }
)
watch(
  () => props.fixedFilters,
  () => {
    previewStale.value = true
  },
  { deep: true }
)
watch(
  [references, () => props.resources],
  () => {
    previewStale.value = true
  },
  { deep: true }
)
const previewFingerprint = () => JSON.stringify([config.value, props.fixedFilters, references.value, props.resources])
function selectDimension(index: number, value: string, list: ReportDimension[] = config.value.dimensions) {
  const parts = value.split(':')
  list[index] = {
    fieldId: parts.at(-1)!,
    relationPath: parts.length > 1 ? parts[0] : null,
    bucket: ReportBucket.VALUE
  }
}
/** 透视表默认挑一个行、列都还没用到的字段（原值分桶），减少重复配置；都用过时退回第一个字段。 */
/** 新增维度的默认字段跳过数值字段（整数也跳过）：数值放进行或列几乎总是误用；没有非数值字段时退回原来的挑法。 */
const preferNonNumeric = (list: ObjectField[]) => list.find(f => !numericField(f, object.value?.relations)) || list[0]
const defaultScalarId = () => preferNonNumeric(fields.value.filter(scalarField))?.id || ''
function unusedFieldId(fallback: boolean) {
  const used = new Set(
    [...config.value.dimensions, ...(config.value.columnDimensions || [])]
      .filter(d => d.bucket === ReportBucket.VALUE)
      .map(d => (d.relationPath ? d.relationPath + ':' : '') + d.fieldId)
  )
  const fresh = preferNonNumeric(fields.value.filter(f => scalarField(f) && !!f.id && !used.has(f.id)))?.id
  return fresh || (fallback ? defaultScalarId() : '')
}
function addDimension(list: ReportDimension[] = config.value.dimensions) {
  if (pivotFull.value) return
  list.push({
    fieldId: pivot.value ? unusedFieldId(true) : defaultScalarId(),
    relationPath: null,
    bucket: ReportBucket.VALUE
  })
}
function addColumnDimension() {
  if (pivotFull.value) return
  config.value.columnDimensions ||= []
  config.value.columnDimensions.push({
    fieldId: unusedFieldId(false),
    relationPath: null,
    bucket: ReportBucket.VALUE
  })
}
const dimensionValue = (d: ReportDimension) => (d.relationPath ? d.relationPath + ':' : '') + d.fieldId
const bucketOptions = (d: ReportDimension) =>
  isDate(d)
    ? [
        { value: 'VALUE', label: '原值' },
        { value: 'DAY', label: '按日' },
        { value: 'MONTH', label: '按月' },
        { value: 'YEAR', label: '按年' }
      ]
    : [{ value: 'VALUE', label: '原值' }]
function isDate(d: ReportDimension) {
  const field = fieldOptions.value.find(
    o => o.value === (d.relationPath ? d.relationPath + ':' : '') + d.fieldId
  )?.field
  return field && dateField(field)
}
function selectOperation(m: ReportMetric) {
  m.formula = null
  m.fieldId = metricNeedsField(m)
    ? metricChoices(m).find(f => f.id === m.fieldId)?.id || metricChoices(m)[0]?.id || null
    : null
  if (m.operation === 'FORMULA') {
    m.conditions = null
    delete m.sourceId
    m.formula = { operator: 'DIVIDE', left: config.value.metrics.find(v => v.id !== m.id)?.id || '', right: '' }
  }
}
function metricFields(m: ReportMetric, source: ObjectField[] = fields.value, relations = object.value?.relations) {
  return source.filter(f =>
    ['COUNT_FIELD', 'COUNT_DISTINCT'].includes(m.operation) ? scalarField(f) : numericField(f, relations)
  )
}
/** 求和、平均、非空计数在明细粒度下只能用粒度明细的字段（主表字段会按明细行数重复计算）。 */
const detailOnly = (m: ReportMetric) => ['SUM', 'AVG', 'COUNT_FIELD'].includes(m.operation)
/** 指标可选字段：主记录粒度 = 主表字段（原样）；明细粒度 = 粒度明细的字段，极值与去重计数再加主表字段。 */
// 多个数据来源：字段、明细粒度取指标所属来源的（metricContext）；来源 1 与原来逐项相同。
function metricChoices(m: ReportMetric) {
  const c = metricContext(m)
  if (!c.detail) return metricFields(m, c.fields, c.relations)
  const own = metricFields(m, c.detailFields, c.relations)
  return detailOnly(m) ? own : [...own, ...metricFields(m, c.fields, c.relations)]
}
function metricFieldOptions(m: ReportMetric) {
  const option = (f: ObjectField) => ({ value: f.id!, label: f.name })
  const c = metricContext(m)
  if (!c.detail || detailOnly(m)) return metricChoices(m).map(option)
  return [
    { label: c.detailName || '明细', options: metricFields(m, c.detailFields, c.relations).map(option) },
    { label: '主表', options: metricFields(m, c.fields, c.relations).map(option) }
  ]
}
// —— 统计粒度：范围判定、切换、存量引导 ——
/** 键（字段ID 或 关系路径:字段ID）落在哪个内部明细上；主表字段、主表上的关系返回 undefined。 */
function keyDetail(key: string) {
  const definition = object.value
  if (!definition || !key) return undefined
  const all = definition.details || []
  if (key.includes(':')) {
    const relation = definition.relations.find(r => r.id === key.split(':')[0].split('/')[0])
    const detail = relation?.sourceDetailId ? all.find(d => d.id === relation.sourceDetailId) : undefined
    if (!relation || !detail) return undefined
    // 契约 M1：关系路径时取「关系引用字段」的名称（例：贷方科目）。
    return { detail, name: detail.fields.find(f => f.id === relation.fieldId)?.name || relation.name }
  }
  for (const detail of all) {
    const field = detail.fields.find(f => f.id === key)
    if (field) return { detail, name: field.name }
  }
  return undefined
}
const outOfScope = (key: string, detailId: string | undefined) => {
  const owner = keyDetail(key)
  return !!owner && owner.detail.id !== detailId
}
const configKeys = () => {
  const keys: string[] = []
  const walk = (items: DynamicSearchCondition['items'] = []) =>
    items.forEach(item => (item.type === 'group' ? walk(item.groupItems) : keys.push(item.field)))
  ;[...config.value.dimensions, ...(config.value.columnDimensions || [])].forEach(d => keys.push(dimensionValue(d)))
  config.value.metrics.forEach(m => {
    if (m.fieldId) keys.push(m.fieldId)
    walk(m.conditions?.items)
  })
  keys.push(...config.value.filterFieldIds)
  walk(config.value.conditions?.items)
  ;(props.fixedFilters || []).forEach(f => keys.push(f.fieldId))
  return keys
}
/** 存量配置：按主记录统计，却用了某个明细里的字段或明细上的关系路径（每个明细提示一次）。 */
const staleDetailRefs = computed(() => {
  if (detailGrain.value) return []
  const found = new Map<string, { detailId: string; detailName: string; fieldName: string; enabled: boolean }>()
  for (const key of configKeys()) {
    const owner = keyDetail(key)
    if (owner?.detail.id && !found.has(owner.detail.id))
      found.set(owner.detail.id, {
        detailId: owner.detail.id,
        detailName: owner.detail.name,
        fieldName: owner.name,
        enabled: details.value.some(d => d.id === owner.detail.id)
      })
  }
  return [...found.values()]
})
/** 存量配置里按主记录却用了明细上的字段作维度：下拉里补上它原来的名字（禁用，不能新选），框里不露出内部键。 */
const dimensionOptions = computed(() => {
  if (!staleDetailRefs.value.length) return fieldOptions.value
  const used = new Set([...config.value.dimensions, ...(config.value.columnDimensions || [])].map(dimensionValue))
  const known = new Set(fieldOptions.value.map(o => o.value))
  const stale = staleDetailRefs.value
    .flatMap(r => reportFieldOptions(config.value.objectId, props.objects, props.orderedReadiness, r.detailId))
    .filter(o => used.has(o.value) && !known.has(o.value))
    .map(o => ({ ...o, disabled: true }))
  return [...fieldOptions.value, ...stale]
})
function pruneConditions(
  value: DynamicSearchCondition | null | undefined,
  drop: (key: string) => boolean
): DynamicSearchCondition | null {
  if (!value) return null
  const prune = (items: DynamicSearchCondition['items']): DynamicSearchCondition['items'] =>
    items.flatMap((item): DynamicSearchCondition['items'] => {
      if (item.type !== 'group') return drop(item.field) ? [] : [item]
      const groupItems = prune(item.groupItems)
      return groupItems.length ? [{ ...item, groupItems }] : []
    })
  const items = prune(value.items)
  return items.length ? { ...value, items } : null
}
/** 切换统计粒度或粒度明细：不在新范围里的维度、指标、筛选、条件项移除，并明确告知移除了什么。 */
function applyGrain(grain: ReportGrain, detailId?: string | null) {
  const target = grain === ReportGrain.DETAIL ? detailId || details.value[0]?.id || undefined : undefined
  const labels = { ...names.value }
  const removed = new Set<string>()
  const drop = (key: string) => {
    if (!outOfScope(key, target)) return false
    removed.add(labels[key] || keyDetail(key)?.name || key)
    return true
  }
  config.value.dimensions = config.value.dimensions.filter(d => !drop(dimensionValue(d)))
  if (config.value.columnDimensions)
    config.value.columnDimensions = config.value.columnDimensions.filter(d => !drop(dimensionValue(d)))
  const gone = new Set<string>(),
    goneNames: string[] = []
  const remove = (m: ReportMetric) => {
    gone.add(m.id)
    goneNames.push(m.name)
  }
  config.value.metrics.forEach(m => {
    if ((m.fieldId && drop(m.fieldId)) || (!target && m.operation === 'COUNT_ROOT')) remove(m)
  })
  // 计算指标引用了被移除的指标时一并移除，否则留下的是「引用了不存在的指标」。
  let changed = true
  while (changed) {
    changed = false
    config.value.metrics.forEach(m => {
      if (gone.has(m.id) || !m.formula || (!gone.has(m.formula.left) && !gone.has(m.formula.right))) return
      remove(m)
      changed = true
    })
  }
  config.value.metrics = config.value.metrics.filter(m => !gone.has(m.id))
  config.value.metrics.forEach(m => {
    if (m.conditions) m.conditions = pruneConditions(m.conditions, drop)
  })
  if (config.value.conditions) config.value.conditions = pruneConditions(config.value.conditions, drop)
  config.value.filterFieldIds = config.value.filterFieldIds.filter(key => !drop(key))
  if (config.value.sortMetricId && gone.has(config.value.sortMetricId)) config.value.sortMetricId = null
  // 固定筛选归上层管，这里不替人删，只提醒。
  const pendingFilters = (props.fixedFilters || []).filter(f => outOfScope(f.fieldId, target)).length
  if (target) {
    config.value.grain = ReportGrain.DETAIL
    config.value.detailId = target
  } else {
    // 主记录粒度不带这两个键，保存出的配置与存量逐键相同。
    delete config.value.grain
    delete config.value.detailId
  }
  // 换了粒度或明细后，原来的下钻视图不再按这一粒度逐行显示：取消它（「允许编辑」随之回到只读），并告知。
  let droppedView = ''
  if (detailView.value && !reportDrillViewMatches(config.value, detailView.value)) {
    droppedView = props.resources.find(r => r.id === config.value.detailViewId)?.name || ''
    config.value.detailViewId = null
  }
  let restoredMetric = false
  if (!config.value.metrics.length) {
    config.value.metrics.push({ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null, format: {} })
    restoredMetric = true
  }
  if (config.value.display !== ReportDisplay.METRIC && !config.value.dimensions.length) addDimension()
  const parts: string[] = []
  if (removed.size) parts.push('已移除不再可用的字段：' + [...removed].join('、'))
  if (goneNames.length)
    parts.push((removed.size ? '一并移除的指标：' : '已移除不再可用的指标：') + goneNames.join('、'))
  if (restoredMetric) parts.push('已补回默认的「记录数」指标')
  if (pendingFilters) parts.push('下方「固定筛选」里还有 ' + pendingFilters + ' 项用到这些字段，请手动移除')
  if (droppedView) parts.push('下钻明细视图「' + droppedView + '」不是按这一粒度逐行显示的，已取消，请重新选择')
  grainNotice.value = parts.join('；')
}
function changeGrain(grain: ReportGrain) {
  // 存量配置里已经用了某个明细上的字段时，优先切到那个明细，原来的维度得以保留。
  applyGrain(grain, staleDetailRefs.value.find(r => r.enabled)?.detailId)
}
// —— 数值字段放进行 / 列 / 分组：提示并一键改为指标 ——
const dimensionEntry = (d: ReportDimension) => fieldOptions.value.find(o => o.value === dimensionValue(d))
/** 只对统计对象自己的字段（主表或粒度明细）提示；关系路径上的字段不能直接作指标字段。 */
function moneyHint(d: ReportDimension) {
  const entry = dimensionEntry(d)
  if (!entry || d.relationPath || !amountLikeField(entry.field)) return undefined
  const full = config.value.metrics.length >= metricLimit.value
  const grainError = metricGrainError(
    { id: '', name: '', operation: 'SUM', fieldId: entry.field.id },
    config.value,
    grainScope.value
  )
  return {
    message: `「${entry.field.name}」是数值字段。放在行或列里，会把每一个不同的数值当成一组（有多少种数值就有多少行或列）。要汇总它，请放到下面的「统计指标」里选「求和」。`,
    disabled: full || !!grainError,
    reason: grainError || (full ? '指标已满 ' + metricLimit.value + ' 个' : '')
  }
}
function dimensionToMetric(list: ReportDimension[], index: number) {
  const d = list[index]
  const hint = d && moneyHint(d)
  const entry = d && dimensionEntry(d)
  if (!hint || hint.disabled || !entry) return
  list.splice(index, 1)
  const same = (m: ReportMetric) => m.operation === 'SUM' && m.fieldId === entry.field.id && !m.conditions
  if (!config.value.metrics.some(same))
    config.value.metrics.push({
      id: 'metric_' + uuidv4().slice(0, 8),
      name: entry.field.name + '合计',
      operation: 'SUM',
      fieldId: entry.field.id,
      format: {}
    })
  // 透视表至少一个行维度、图表至少一个分组：移空了就补一个默认维度。
  if (config.value.display !== ReportDisplay.METRIC && !config.value.dimensions.length) addDimension()
}
function addMetric(copy?: ReportMetric) {
  if (config.value.metrics.length >= metricLimit.value) return
  config.value.metrics.push(
    copy
      ? {
          ...JSON.parse(JSON.stringify(copy)),
          id: 'metric_' + uuidv4().slice(0, 8),
          name: copy.name + '副本'
        }
      : {
          id: 'metric_' + uuidv4().slice(0, 8),
          name: '统计指标',
          operation: 'COUNT',
          fieldId: null,
          format: {}
        }
  )
}
function moveMetric(index: number, step: number) {
  const metrics = config.value.metrics
  if (index + step < 0 || index + step >= metrics.length) return
  ;[metrics[index], metrics[index + step]] = [metrics[index + step], metrics[index]]
}
function removeMetric(m: ReportMetric) {
  if (config.value.metrics.some(v => v.formula?.left === m.id || v.formula?.right === m.id)) {
    previewError.value = '该指标仍被计算指标引用，请先调整计算关系'
    return
  }
  config.value.metrics = config.value.metrics.filter(v => v.id !== m.id)
  if (config.value.sortMetricId === m.id) clearSortMetric()
}
async function refreshPreview() {
  const generation = ++previewGeneration
  previewBusy.value = true
  previewError.value = ''
  try {
    const fingerprint = previewFingerprint()
    const snapshot: ReportConfig = JSON.parse(JSON.stringify(config.value))
    if (props.fixedFilters)
      snapshot.equal = Object.fromEntries(
        props.fixedFilters.map(f => {
          const entry = fieldOptions.value.find(e => e.value === f.fieldId)
          if (!entry || f.value == null || f.value === '') throw new Error('请补齐固定筛选字段和值')
          return [f.fieldId, recordFilterValue(entry.field, f.value)]
        })
      )
    validateReport(snapshot, grainScope.value)
    validateReportSources(snapshot, props.objects, props.orderedReadiness)
    const result = await api.previewReport({
      applicationId: props.applicationId,
      objects: references.value,
      config: normalizeReportSources(snapshot),
      resources: props.resources
    })
    if (generation !== previewGeneration) return
    preview.value = result
    previewConfig.value = snapshot
    previewStale.value = fingerprint !== previewFingerprint()
  } catch (e) {
    if (generation === previewGeneration) previewError.value = errorMessage(e)
  } finally {
    if (generation === previewGeneration) previewBusy.value = false
  }
}
const previewColumns = computed(() => [
  ...(preview.value?.dimensionNames || []).map((name, i) => ({
    title: name,
    key: 'd' + i,
    customRender: ({ record }: any) => record.labels[i]
  })),
  ...(preview.value?.metrics || []).map(m => ({
    title: m.name,
    key: m.id,
    customRender: ({ record }: any) => formatReportValue(record.values[m.id], m)
  }))
])
const templateOpen = ref(false)
const template = ref({ group: '', status: '', quantity: '', values: [null, null, null] as unknown[] })
const templateStatus = computed(() => fieldOptions.value.find(f => f.value === template.value.status))
function applyTemplate() {
  if (!template.value.group || !templateStatus.value || template.value.values.some(v => v == null || v === '')) {
    previewError.value = '请先选择模板字段及三个状态值'
    return
  }
  const operation = template.value.quantity ? 'SUM' : 'COUNT'
  const make = (id: string, name: string, state?: unknown): ReportMetric => ({
    id,
    name,
    operation,
    fieldId: template.value.quantity || null,
    conditions:
      state == null
        ? null
        : {
            logic: 'AND',
            items: [
              {
                type: 'condition',
                field: template.value.status,
                operator: 'eq',
                value: recordFilterValue(templateStatus.value!.field, state)
              }
            ]
          },
    format: { unit: '台', decimals: 0 }
  })
  config.value.display = 'BAR'
  config.value.columnDimensions = []
  config.value.pivot = null
  config.value.dimensions = []
  addDimension()
  selectDimension(0, template.value.group)
  config.value.metrics = [
    make('total', '笔记本总数'),
    make('in_use', '在用数', template.value.values[0]),
    make('idle', '空闲数', template.value.values[1]),
    make('damaged', '损坏数', template.value.values[2])
  ]
  clearSortMetric()
  if (config.value.limit != null && config.value.limit > MAX_REPORT_CHART_GROUPS)
    config.value.limit = MAX_REPORT_CHART_GROUPS
  config.value.chart = defaultReportChart()
  templateOpen.value = false
}
// —— 多个数据来源：「数据来源」区块 ——
function addSource() {
  if (!tableDisplay.value || sourcesFull.value) return
  const source = defaultSource(config.value, config.value.objectId, props.objects)
  config.value.extraSources = [...(config.value.extraSources || []), source]
  config.value.sourceName ??= ''
}
/** 删除来源：同时删除属于它的指标（及引用了这些指标的计算指标），先确认。 */
const removingSource = ref<string>()
const sourceMetrics = (id: string) => config.value.metrics.filter(m => m.operation !== 'FORMULA' && m.sourceId === id)
function askRemoveSource(id: string) {
  if (sourceMetrics(id).length) removingSource.value = id
  else removeSource(id)
}
function removeSource(id: string) {
  removingSource.value = undefined
  const gone = new Set(sourceMetrics(id).map(m => m.id))
  let changed = true
  while (changed) {
    changed = false
    config.value.metrics.forEach(m => {
      if (gone.has(m.id) || !m.formula || (!gone.has(m.formula.left) && !gone.has(m.formula.right))) return
      gone.add(m.id)
      changed = true
    })
  }
  config.value.metrics = config.value.metrics.filter(m => !gone.has(m.id))
  if (config.value.sortMetricId && gone.has(config.value.sortMetricId)) clearSortMetric()
  if (!config.value.metrics.length)
    config.value.metrics.push({ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null, format: {} })
  const rest = (config.value.extraSources || []).filter(s => s.id !== id)
  if (rest.length) config.value.extraSources = rest
  else {
    // 回到单来源：四个新键不留（与存量逐键相同）。
    delete config.value.extraSources
    delete config.value.sourceName
    delete config.value.dimensionLabels
    delete config.value.columnDimensionLabels
  }
}
/** 附加来源换对象：维度对应、条件、日期、筛选对应、下钻视图清空；它的指标字段与条件也清空（字段键属于原对象）。 */
function changeSourceObject(s: ReportSource, objectId: string) {
  const index = (config.value.extraSources || []).findIndex(v => v.id === s.id)
  if (index < 0) return
  config.value.extraSources![index] = resetSourceObject(config.value, s, objectId)
  sourceMetrics(s.id).forEach(m => {
    m.fieldId = metricNeedsField(m) ? null : m.fieldId
    m.conditions = null
    if (m.operation === 'COUNT_ROOT') m.operation = 'COUNT'
  })
}
/** 附加来源换粒度：不在新范围里的维度对应、条件项、筛选对应、指标字段清空并告知。 */
function changeSourceGrain(s: ReportSource, grain: ReportGrain, detailId?: string | null) {
  const target =
    grain === ReportGrain.DETAIL
      ? detailId || reportDetails(props.objects[s.objectId]?.definition)[0]?.id || undefined
      : undefined
  if (target) {
    s.grain = ReportGrain.DETAIL
    s.detailId = target
  } else {
    delete s.grain
    delete s.detailId
  }
  // 换了粒度或明细后，原来的下钻视图不再按这一粒度逐行显示：取消它（「允许编辑」随之回到只读），并告知（R6 衔接，与来源 1 同口径）。
  let droppedView = ''
  const currentView = sourceViewConfig(s)
  if (currentView && !reportDrillViewMatches(s, currentView)) {
    droppedView = props.resources.find(r => r.id === s.detailViewId)?.name || ''
    s.detailViewId = null
    s.detailEditable = null
  }
  const known = new Set(entriesOf(viewOf(s)).map(e => e.value))
  const removed: string[] = []
  const clear = (d: ReportDimension) => {
    if (!d.fieldId || known.has(reportDimensionKey(d))) return d
    removed.push(d.fieldId)
    return { fieldId: '', relationPath: null, bucket: d.bucket }
  }
  s.dimensions = s.dimensions.map(clear)
  if (s.columnDimensions) s.columnDimensions = s.columnDimensions.map(clear)
  if (s.conditions) s.conditions = pruneConditions(s.conditions, key => !known.has(key))
  if (s.filterTargets)
    s.filterTargets = Object.fromEntries(Object.entries(s.filterTargets).filter(([, v]) => known.has(v)))
  sourceMetrics(s.id).forEach(m => {
    if (m.fieldId && !metricChoices(m).some(f => f.id === m.fieldId)) {
      removed.push(m.name)
      m.fieldId = null
    }
    if (m.operation === 'COUNT_ROOT' && !target) m.operation = 'COUNT'
  })
  const notices: string[] = []
  if (removed.length)
    notices.push(`来源「${s.name || '未命名'}」换了统计粒度，已清空不再可用的对应字段或指标字段，请重新选择`)
  if (droppedView) notices.push('下钻明细视图「' + droppedView + '」不是按这一粒度逐行显示的，已取消，请重新选择')
  sourceNotice.value = notices.join('；')
}
/** 维度对应一行：左边是来源 1 的维度显示名与分组方式，右边是本来源的字段（不相容的禁用并写明原因）。 */
function slotRows(s: ReportSource) {
  const rows = config.value.dimensions.map((d, i) => ({ d, i, column: false }))
  const columns = (config.value.columnDimensions || []).map((d, i) => ({ d, i, column: true }))
  const mainEntries = fieldOptions.value
  const entries = entriesOf(viewOf(s))
  return [...rows, ...columns].map(({ d, i, column }) => {
    const mine = (column ? s.columnDimensions : s.dimensions)?.[i]
    return {
      key: (column ? 'c' : 'r') + i,
      column,
      index: i,
      label: dimensionName(config.value, column, i, props.objects, props.orderedReadiness),
      bucket: bucketLabels[d.bucket] || d.bucket,
      value: mine?.fieldId ? reportDimensionKey(mine) : undefined,
      choices: alignedChoices(
        mainEntries.find(e => e.value === reportDimensionKey(d)),
        entries,
        props.objects,
        d.bucket
      )
    }
  })
}
function selectSlot(s: ReportSource, column: boolean, index: number, value: string) {
  const top = (column ? config.value.columnDimensions : config.value.dimensions)?.[index]
  if (!top) return
  const parts = (value || '').split(':')
  const slot: ReportDimension = value
    ? { fieldId: parts.at(-1)!, relationPath: parts.length > 1 ? parts[0] : null, bucket: top.bucket }
    : { fieldId: '', relationPath: null, bucket: top.bucket }
  if (column) {
    const list = [...(s.columnDimensions || [])]
    list[index] = slot
    s.columnDimensions = list
  } else s.dimensions[index] = slot
  if (config.value.dateFieldId && !s.dateFieldId) {
    const date = defaultSourceDateField(config.value, s, props.objects)
    if (date) s.dateFieldId = date
  }
}
const sourceMissing = (s: ReportSource) =>
  [...s.dimensions, ...(s.columnDimensions || [])].some(d => !d?.fieldId) ||
  s.dimensions.length !== config.value.dimensions.length
const sourceDateOptions = (s: ReportSource) => {
  const definition = props.objects[s.objectId]?.definition
  return definition
    ? reportFields(definition, props.orderedReadiness?.[s.objectId])
        .filter(dateField)
        .map(f => ({ value: f.id!, label: f.name }))
    : []
}
/** 筛选对应：只列推不出来的可筛选字段（契约第 8 章 1–3 推得出来的不显示、不存）。 */
function filterRows(s: ReportSource) {
  const entries = entriesOf(viewOf(s))
  return config.value.filterFieldIds
    .filter(key => !inferredFilterKey(config.value, viewOf(s), key))
    .map(key => {
      const target = fieldOptions.value.find(e => e.value === key)
      return {
        key,
        label: target?.label || key,
        value: s.filterTargets?.[key] || undefined,
        choices: alignedChoices(target, entries, props.objects, ReportBucket.VALUE)
      }
    })
}
function selectFilterTarget(s: ReportSource, key: string, value?: string) {
  const next = { ...(s.filterTargets || {}) }
  if (value) next[key] = value
  else delete next[key]
  s.filterTargets = next
}
/** 来源下钻视图的候选：同一对象的视图；按明细行统计的来源只列按同一明细逐行显示的数据视图（R6 衔接，与来源 1 的 drillViewOptions 同一规则）。 */
const sourceViewOptions = (s: ReportSource) =>
  props.resources
    .filter(r => r.kind === ResourceKind.VIEW && reportDrillViewMatches(s, r.config as unknown as ViewConfig))
    .map(r => ({ value: r.id, label: r.name }))
function sourceViewConfig(s: ReportSource) {
  return props.resources.find(r => r.id === s.detailViewId && r.kind === ResourceKind.VIEW)?.config as unknown as
    ViewConfig | undefined
}
/** 来源按明细行统计时的明细名（提示里用）。 */
const sourceDetailName = (s: ReportSource) =>
  reportDetails(props.objects[s.objectId]?.definition).find(d => d.id === s.detailId)?.name || '所选明细'
/** 已选的视图不按本来源的粒度逐行显示（视图后来被改了形状等）：照服务端同一句提示（加来源前缀），保存会被拒。 */
function sourceViewError(s: ReportSource) {
  const view = sourceViewConfig(s)
  return view && !reportDrillViewMatches(s, view)
    ? '来源「' + (s.name || '未命名') + '」：' + reportGrainMessages.M8(sourceDetailName(s))
    : ''
}
/** 来源挂的视图有固定筛选时，该来源的统计也会被收窄（契约变更 C2，与来源 1 同口径）。 */
function sourceViewFiltered(s: ReportSource) {
  const view = sourceViewConfig(s)
  return !!view && (Object.keys(view.equal || {}).length > 0 || (view.query?.fixed?.length || 0) > 0)
}
function selectSourceView(s: ReportSource, id?: string) {
  s.detailViewId = id || null
  if (!id) s.detailEditable = null
}
function setDimensionLabel(column: boolean, index: number, value: string) {
  const length = (column ? config.value.columnDimensions || [] : config.value.dimensions).length
  const current = (column ? config.value.columnDimensionLabels : config.value.dimensionLabels) || []
  const next = Array.from({ length }, (_, i) => (i === index ? value : current[i] || ''))
  if (column) config.value.columnDimensionLabels = next
  else config.value.dimensionLabels = next
}
/** 来源 1 增删行 / 列维度：附加来源的维度对应与维度显示名逐位同步（新增位留空，分桶跟来源 1）。 */
watch(
  () => [config.value, [...config.value.dimensions], [...(config.value.columnDimensions || [])]] as const,
  (next, previous) => {
    const extras = config.value.extraSources
    const [current, rows, columns] = next
    if (!previous || previous[0] !== current) return
    const [, oldRows, oldColumns] = previous
    const empty = (d: ReportDimension): ReportDimension => ({ fieldId: '', relationPath: null, bucket: d.bucket })
    const keepBucket = (top: readonly ReportDimension[]) => (d: ReportDimension, i: number) =>
      d.bucket === top[i]?.bucket ? d : { ...d, bucket: top[i].bucket }
    extras?.forEach(s => {
      s.dimensions = alignSlots(oldRows, rows, s.dimensions, empty).map(keepBucket(rows))
      s.columnDimensions = columns.length
        ? alignSlots(oldColumns, columns, s.columnDimensions, empty).map(keepBucket(columns))
        : null
    })
    if (config.value.dimensionLabels)
      config.value.dimensionLabels = alignSlots(oldRows, rows, config.value.dimensionLabels, () => '')
    if (config.value.columnDimensionLabels)
      config.value.columnDimensionLabels = alignSlots(oldColumns, columns, config.value.columnDimensionLabels, () => '')
  }
)
/** 来源 1 改了某一位的分组方式：附加来源同位的分桶跟着改（不让单独选）。 */
watch(
  () => [...config.value.dimensions, ...(config.value.columnDimensions || [])].map(d => d.bucket).join(','),
  () => {
    const rows = config.value.dimensions,
      columns = config.value.columnDimensions || []
    config.value.extraSources?.forEach(s => {
      s.dimensions.forEach((d, i) => rows[i] && d.bucket !== rows[i].bucket && (d.bucket = rows[i].bucket))
      s.columnDimensions?.forEach(
        (d, i) => columns[i] && d.bucket !== columns[i].bucket && (d.bucket = columns[i].bucket)
      )
    })
  }
)
/** 来源 1 开 / 关日期范围：附加来源的日期范围字段跟着补默认值 / 清空（L13、L13b）。 */
watch(
  () => config.value.dateFieldId,
  dateFieldId =>
    config.value.extraSources?.forEach(s => {
      if (!dateFieldId) delete s.dateFieldId
      else if (!s.dateFieldId) {
        const date = defaultSourceDateField(config.value, s, props.objects)
        if (date) s.dateFieldId = date
      }
    })
)
/** 来源 1 换了数据对象（ResourceManager 已按 carryReportSources 清空对应）：提示一次。 */
watch(
  () => config.value.objectId,
  (next, previous) => {
    if (previous !== undefined && previous !== next && multi.value) sourceNotice.value = carriedSourcesNotice
  }
)
const previewSourcesFooter = computed(() => (preview.value ? reportSourcesFooter(preview.value) : null))
</script>
<template>
  <div class="report-workspace">
    <div class="report-settings">
      <a-alert type="info" show-icon message="指标名称只影响展示。各状态数量需配置独立条件；比例使用指标计算。" />
      <a-tabs v-model:active-key="tab">
        <a-tab-pane key="data" tab="数据与指标">
          <a-form-item label="展示方式">
            <a-segmented v-model:value="config.display" :options="displayOptions" @change="changeDisplay" />
          </a-form-item>
          <a-alert v-if="displayNotice" type="info" :message="displayNotice" />
          <a-alert
            v-if="sourcesBlocked"
            type="error"
            show-icon
            data-sources-blocked
            :message="reportSourceMessages.L1"
          />
          <section class="config-section" data-section="sources">
            <h4>
              数据来源
              <span>透视表、汇总表可以再加最多 3 个来源（可以是同一个对象），各自指定用哪个字段对应到共同的行和列</span>
            </h4>
            <div class="config-row" data-source="main">
              <strong class="source-title">来源 1 · {{ object?.objectName || '统计对象' }}</strong>
              <a-input
                v-if="multi"
                :value="config.sourceName || ''"
                placeholder="来源名称（必填，最多 30 字）"
                :maxlength="30"
                aria-label="来源 1 名称"
                data-source-name
                @update:value="(v: string) => (config.sourceName = v)"
              />
              <span class="hint">数据对象在上方选，统计粒度在下方选</span>
            </div>
            <article
              v-for="(s, sIndex) in config.extraSources || []"
              :key="s.id"
              class="source-card"
              :class="{ 'source-card-missing': sourceMissing(s) }"
              :data-source="s.id"
            >
              <div class="section-heading">
                <h5>来源 {{ sIndex + 2 }}</h5>
                <a-button size="small" danger data-source-remove @click="askRemoveSource(s.id)">删除来源</a-button>
              </div>
              <div v-if="removingSource === s.id" class="source-confirm" data-source-confirm>
                <span>删除来源会同时删除它的 {{ sourceMetrics(s.id).length }} 个指标</span>
                <a-button size="small" danger data-source-confirm-ok @click="removeSource(s.id)">确认删除</a-button>
                <a-button size="small" @click="removingSource = undefined">取消</a-button>
              </div>
              <p v-if="sourceMissing(s)" class="source-missing" data-source-missing>还没有对应字段</p>
              <div class="config-row">
                <a-input
                  :value="s.name"
                  placeholder="来源名称（必填，最多 30 字）"
                  :maxlength="30"
                  aria-label="来源名称"
                  data-source-name
                  @update:value="(v: string) => (s.name = v)"
                />
                <a-select
                  :value="s.objectId"
                  :options="objectChoices"
                  aria-label="数据对象"
                  data-source-object
                  @change="(v: string) => changeSourceObject(s, v)"
                />
              </div>
              <div class="grain-row" data-source-grain>
                <a-radio-group
                  :value="s.grain === ReportGrain.DETAIL ? ReportGrain.DETAIL : ReportGrain.ROOT"
                  @update:value="(v: ReportGrain) => changeSourceGrain(s, v)"
                >
                  <a-radio :value="ReportGrain.ROOT">
                    按主记录（每条{{ objects[s.objectId]?.definition.objectName || '主记录' }}算一行）
                  </a-radio>
                  <a-radio
                    :value="ReportGrain.DETAIL"
                    :disabled="!reportDetails(objects[s.objectId]?.definition).length"
                  >
                    按明细行（每条明细算一行，带出主表信息）
                  </a-radio>
                </a-radio-group>
                <span v-if="!reportDetails(objects[s.objectId]?.definition).length" class="hint">
                  当前对象没有内部明细
                </span>
                <div v-if="s.grain === ReportGrain.DETAIL" class="grain-detail" data-source-detail>
                  <span>明细来源</span>
                  <a-select
                    :value="s.detailId ?? undefined"
                    :options="
                      reportDetails(objects[s.objectId]?.definition).map(d => ({ value: d.id!, label: d.name }))
                    "
                    aria-label="明细来源"
                    placeholder="选择内部明细"
                    @change="(v: string) => changeSourceGrain(s, ReportGrain.DETAIL, v)"
                  />
                </div>
              </div>
              <div class="source-block">
                <span class="source-label">固定条件</span>
                <ReportConditionEditor
                  v-model="s.conditions"
                  :application-id="applicationId"
                  :entries="entriesOf(viewOf(s))"
                  :objects="objects"
                  :preview-objects="references"
                  label="设置固定条件"
                />
              </div>
              <div class="source-block" data-source-slots>
                <span class="source-label">维度对应</span>
                <div v-for="row in slotRows(s)" :key="row.key" class="config-row slot-row" :data-slot="row.key">
                  <span class="slot-target">{{ row.label }}（{{ row.bucket }}）→</span>
                  <a-select
                    :value="row.value"
                    :options="row.choices"
                    show-search
                    option-filter-prop="label"
                    placeholder="选择本来源对应的字段"
                    @change="(v: string) => selectSlot(s, row.column, row.index, v)"
                  />
                </div>
                <p v-if="!slotRows(s).length" class="hint">先在下面设置行维度</p>
              </div>
              <div v-if="config.dateFieldId" class="source-block" data-source-date>
                <span class="source-label">日期范围字段</span>
                <a-select
                  :value="s.dateFieldId ?? undefined"
                  :options="sourceDateOptions(s)"
                  placeholder="必填：本来源按哪个日期筛选日期范围"
                  @change="(v: string) => (s.dateFieldId = v)"
                />
              </div>
              <div v-if="filterRows(s).length" class="source-block" data-source-filters>
                <span class="source-label">筛选对应</span>
                <div v-for="row in filterRows(s)" :key="row.key" class="config-row slot-row" :data-filter="row.key">
                  <span class="slot-target">{{ row.label }} →</span>
                  <a-select
                    :value="row.value"
                    :options="row.choices"
                    allow-clear
                    placeholder="选择本来源对应的字段"
                    @change="(v?: string) => selectFilterTarget(s, row.key, v)"
                  />
                </div>
              </div>
              <div class="source-block" data-source-drill>
                <span class="source-label">下钻明细视图</span>
                <div class="config-row">
                  <a-select
                    :value="s.detailViewId ?? undefined"
                    :options="sourceViewOptions(s)"
                    allow-clear
                    :placeholder="s.grain === ReportGrain.DETAIL ? '默认：命中的明细行（只读）' : '默认对象字段'"
                    aria-label="来源下钻明细视图"
                    @change="(v?: string) => selectSourceView(s, v)"
                  />
                  <a-checkbox
                    :checked="!!s.detailEditable"
                    :disabled="!s.detailViewId"
                    data-source-editable
                    @update:checked="(v: boolean) => (s.detailEditable = v || null)"
                  >
                    允许在下钻明细中新建、编辑、删除
                  </a-checkbox>
                </div>
                <a-alert
                  v-if="sourceViewError(s)"
                  type="error"
                  show-icon
                  data-source-drill-error
                  :message="sourceViewError(s)"
                />
                <p v-if="s.grain === ReportGrain.DETAIL" class="hint" data-source-detail-drill>
                  按明细行统计时，只能选「一行表示一条内部明细」且明细来源为「{{
                    sourceDetailName(s)
                  }}」的数据视图；不选则下钻显示命中的明细行（只读）。
                </p>
                <p v-if="sourceViewFiltered(s)" class="hint" data-source-view-filtered>
                  本来源的统计会按该视图的固定筛选收窄。
                </p>
              </div>
            </article>
            <a-alert v-if="sourceNotice" type="info" show-icon data-source-notice :message="sourceNotice" />
            <div class="config-row">
              <a-button :disabled="!tableDisplay || sourcesFull" data-source-add @click="addSource">添加来源</a-button>
              <span v-if="!tableDisplay" class="hint" data-source-add-hint>{{ reportSourceMessages.L1 }}</span>
              <span v-else-if="sourcesFull" class="hint" data-source-add-hint>{{ reportSourceMessages.L2 }}</span>
            </div>
          </section>
          <a-form-item label="统计粒度">
            <div class="grain-row" data-report-grain>
              <a-radio-group
                :value="detailGrain ? ReportGrain.DETAIL : ReportGrain.ROOT"
                @update:value="(v: ReportGrain) => changeGrain(v)"
              >
                <a-radio :value="ReportGrain.ROOT">按主记录（每条{{ object?.objectName || '主记录' }}算一行）</a-radio>
                <a-radio :value="ReportGrain.DETAIL" :disabled="!details.length">
                  按明细行（每条明细算一行，带出主表信息）
                </a-radio>
              </a-radio-group>
              <span v-if="!details.length" class="hint" data-report-grain-empty>当前对象没有内部明细</span>
              <div v-if="detailGrain" class="grain-detail" data-report-detail>
                <span>明细来源</span>
                <a-select
                  :value="config.detailId ?? undefined"
                  :options="details.map(d => ({ value: d.id!, label: d.name }))"
                  aria-label="明细来源"
                  placeholder="选择内部明细"
                  @change="(v: string) => applyGrain(ReportGrain.DETAIL, v)"
                />
              </div>
            </div>
            <p class="hint">
              按明细行统计时，可以用明细里的字段分组和求和；主表上的金额不能求和（会按明细行数重复计算）。
            </p>
          </a-form-item>
          <a-alert v-if="grainDetailError" type="error" show-icon data-grain-error :message="grainDetailError" />
          <a-alert v-if="grainNotice" type="info" show-icon data-grain-notice :message="grainNotice" />
          <div v-for="stale in staleDetailRefs" :key="stale.detailId" class="grain-stale" data-grain-stale>
            <a-alert
              type="warning"
              show-icon
              :message="`「${stale.fieldName}」是明细「${stale.detailName}」里的字段，按主记录统计时不能使用。`"
            />
            <a-button v-if="stale.enabled" size="small" @click="applyGrain(ReportGrain.DETAIL, stale.detailId)">
              改为按明细行 · {{ stale.detailName }}
            </a-button>
          </div>
          <section class="config-section">
            <h4>
              固定条件
              <span>与页面及用户筛选共同生效，指标条件只进一步收窄范围</span>
            </h4>
            <ReportConditionEditor
              v-model="config.conditions"
              :application-id="applicationId"
              :entries="fieldOptions"
              :objects="objects"
              :preview-objects="references"
              label="设置固定条件"
            />
            <p v-if="fixedFilters?.length" class="hint">
              同时沿用下方已有的 {{ fixedFilters.length }} 项固定等值筛选。
            </p>
          </section>
          <section v-if="config.display !== ReportDisplay.METRIC" class="config-section" data-section="rows">
            <h4>
              {{ pivot ? '行维度' : '分组维度' }}
              <span>
                {{
                  pivot
                    ? '至少 1 个，与列维度合计最多 ' + MAX_PIVOT_DIMENSIONS + ' 个；多层时每一层都可折叠并带小计'
                    : '最多两个；第一维为分类，第二维拆分系列'
                }}
              </span>
            </h4>
            <div v-for="(d, index) in config.dimensions" :key="index" class="config-row">
              <a-select
                :value="dimensionValue(d)"
                :options="dimensionOptions"
                show-search
                option-filter-prop="label"
                :placeholder="pivot ? '选择行字段' : '选择分组字段'"
                @change="(v: string) => selectDimension(index, v)"
              />
              <a-select v-model:value="d.bucket" :options="bucketOptions(d)" />
              <a-input
                v-if="multi"
                :value="config.dimensionLabels?.[index] || ''"
                :placeholder="
                  '显示名（默认「' +
                  dimensionName({ ...config, dimensionLabels: null }, false, index, objects, orderedReadiness) +
                  '」）'
                "
                :maxlength="30"
                aria-label="维度显示名"
                data-dimension-label
                @update:value="(v: string) => setDimensionLabel(false, index, v)"
              />
              <a-button :disabled="pivot && config.dimensions.length <= 1" @click="config.dimensions.splice(index, 1)">
                移除
              </a-button>
              <div v-if="moneyHint(d)" class="dimension-hint" data-dimension-money-hint>
                <p class="dimension-hint-text">{{ moneyHint(d)!.message }}</p>
                <a-button
                  size="small"
                  :disabled="moneyHint(d)!.disabled"
                  @click="dimensionToMetric(config.dimensions, index)"
                >
                  改为指标（求和）
                </a-button>
                <span v-if="moneyHint(d)!.reason" class="hint" data-dimension-money-reason>
                  {{ moneyHint(d)!.reason }}
                </span>
              </div>
            </div>
            <a-button
              :disabled="pivot ? pivotFull : config.dimensions.length >= MAX_GROUP_DIMENSIONS"
              @click="addDimension()"
            >
              {{ pivot ? '添加行维度' : '添加分组' }}
            </a-button>
            <a-alert
              v-if="pivot && duplicateIn(config.dimensions)"
              type="error"
              show-icon
              message="行维度不能重复（同一字段、同一分桶）。"
            />
          </section>
          <template v-if="pivot">
            <section class="config-section" data-section="columns">
              <h4>
                列维度
                <span>
                  可为 0 个，与行维度合计最多
                  {{ MAX_PIVOT_DIMENSIONS }} 个；每个列组下展开全部指标，新出现的值（如新月份）自动多一组
                </span>
              </h4>
              <div v-for="(d, index) in config.columnDimensions || []" :key="index" class="config-row">
                <a-select
                  :value="dimensionValue(d)"
                  :options="dimensionOptions"
                  show-search
                  option-filter-prop="label"
                  placeholder="选择列字段"
                  @change="(v: string) => selectDimension(index, v, config.columnDimensions)"
                />
                <a-select v-model:value="d.bucket" :options="bucketOptions(d)" />
                <a-input
                  v-if="multi"
                  :value="config.columnDimensionLabels?.[index] || ''"
                  :placeholder="
                    '显示名（默认「' +
                    dimensionName({ ...config, columnDimensionLabels: null }, true, index, objects, orderedReadiness) +
                    '」）'
                  "
                  :maxlength="30"
                  aria-label="维度显示名"
                  data-dimension-label
                  @update:value="(v: string) => setDimensionLabel(true, index, v)"
                />
                <a-button @click="config.columnDimensions?.splice(index, 1)">移除</a-button>
                <div v-if="moneyHint(d)" class="dimension-hint" data-dimension-money-hint>
                  <p class="dimension-hint-text">{{ moneyHint(d)!.message }}</p>
                  <a-button
                    size="small"
                    :disabled="moneyHint(d)!.disabled"
                    @click="dimensionToMetric(config.columnDimensions || [], index)"
                  >
                    改为指标（求和）
                  </a-button>
                  <span v-if="moneyHint(d)!.reason" class="hint" data-dimension-money-reason>
                    {{ moneyHint(d)!.reason }}
                  </span>
                </div>
              </div>
              <a-button :disabled="pivotFull" @click="addColumnDimension">添加列维度</a-button>
              <a-alert
                v-if="pivotFull"
                type="warning"
                show-icon
                data-pivot-limit
                :message="
                  pivotDimensionCount > MAX_PIVOT_DIMENSIONS
                    ? pivotDimensionLimitMessage
                    : '已达上限：行维度与列维度合计最多 ' +
                      MAX_PIVOT_DIMENSIONS +
                      ' 个（小计与合计要按 (行维度数+1)×(列维度数+1) 组分组重新聚合，维度越多查询越重）。'
                "
              />
              <a-alert
                v-if="duplicateIn(config.columnDimensions || [])"
                type="error"
                show-icon
                message="列维度不能重复（同一字段、同一分桶）。"
              />
              <a-alert
                v-if="pivotDuplicate"
                type="error"
                show-icon
                message="同一字段（相同分桶）不能同时作为行维度和列维度。"
              />
            </section>
            <section v-if="config.pivot" class="config-section" data-section="pivot">
              <h4>
                透视选项
                <span>小计与合计由服务端按原始记录重新聚合，不是叶子值相加</span>
              </h4>
              <a-space wrap>
                <a-checkbox v-model:checked="config.pivot.subtotals">显示小计</a-checkbox>
                <a-checkbox v-model:checked="config.pivot.rowTotals">行合计（最右合计列组）</a-checkbox>
                <a-checkbox v-model:checked="config.pivot.columnTotals">列合计（最下合计行）</a-checkbox>
              </a-space>
              <div class="two-columns">
                <a-form-item label="占比基准">
                  <a-select v-model:value="config.pivot.percent" :options="pivotPercentOptions" aria-label="占比基准" />
                </a-form-item>
                <a-form-item label="列组上限">
                  <a-input-number
                    v-model:value="config.pivot.maxColumnGroups"
                    :min="1"
                    :max="100"
                    :precision="0"
                    aria-label="列组上限"
                  />
                </a-form-item>
              </div>
            </section>
          </template>
          <section class="config-section">
            <div class="section-heading">
              <h4>
                统计指标
                <span>{{ multi ? '多个来源时最多十个' : '最多五个' }}；金额精确计算，空值不参与平均及极值</span>
              </h4>
              <a-button v-if="!detailGrain && !multi" size="small" @click="templateOpen = true">部门资产模板</a-button>
            </div>
            <a-alert
              v-if="duplicateReportMetrics(config.metrics)"
              type="warning"
              show-icon
              message="有指标的字段、计算与条件完全一致，仅改名会得到相同结果。"
            />
            <div v-for="(m, index) in config.metrics" :key="m.id" class="metric-editor">
              <a-alert
                v-if="metricError(m)"
                type="error"
                show-icon
                data-metric-grain-error
                :message="metricError(m)!"
              />
              <div class="config-row">
                <a-select
                  v-if="multi && m.operation !== 'FORMULA'"
                  :value="m.sourceId || MAIN_SOURCE_ID"
                  :options="sourceOptions"
                  aria-label="数据来源"
                  data-metric-source
                  @change="(v: string) => changeMetricSource(m, v)"
                />
                <a-input v-model:value="m.name" placeholder="指标名称" aria-label="指标名称" :maxlength="60" />
                <a-select
                  v-model:value="m.operation"
                  :options="metricOperations(m)"
                  aria-label="计算方式"
                  @change="selectOperation(m)"
                />
                <a-select
                  v-if="metricNeedsField(m)"
                  v-model:value="m.fieldId"
                  :options="metricFieldOptions(m)"
                  placeholder="统计字段"
                />
              </div>
              <div v-if="m.formula" class="config-row">
                <a-select
                  v-model:value="m.formula.left"
                  :options="config.metrics.filter(v => v.id !== m.id).map(v => ({ value: v.id, label: v.name }))"
                  placeholder="左侧指标"
                />
                <a-select
                  v-model:value="m.formula.operator"
                  :options="Object.entries(formulaLabels).map(([value, label]) => ({ value, label }))"
                />
                <a-select
                  v-model:value="m.formula.right"
                  :options="config.metrics.filter(v => v.id !== m.id).map(v => ({ value: v.id, label: v.name }))"
                  placeholder="右侧指标"
                />
              </div>
              <ReportConditionEditor
                v-else
                v-model="m.conditions"
                :application-id="applicationId"
                :entries="metricContext(m).entries"
                :objects="objects"
                :preview-objects="references"
                label="指标条件"
              />
              <p class="hint">{{ metricDescriptionOf(m) }}</p>
              <a-space size="small">
                <a-button size="small" :disabled="index === 0" @click="moveMetric(index, -1)">上移</a-button>
                <a-button size="small" :disabled="index === config.metrics.length - 1" @click="moveMetric(index, 1)">
                  下移
                </a-button>
                <a-button size="small" :disabled="config.metrics.length >= metricLimit" @click="addMetric(m)">
                  复制
                </a-button>
                <a-button size="small" :disabled="config.metrics.length <= 1" @click="removeMetric(m)">移除</a-button>
              </a-space>
            </div>
            <a-button :disabled="config.metrics.length >= metricLimit" @click="addMetric()">添加指标</a-button>
          </section>
          <a-form-item label="用户可筛选字段">
            <a-select
              v-model:value="config.filterFieldIds"
              mode="multiple"
              :options="fieldOptions"
              option-filter-prop="label"
            />
          </a-form-item>
          <a-alert
            v-if="config.metrics.some(m => m.formula) && config.filterFieldIds.length"
            type="info"
            message="用户筛选也会作用于计算指标的分子与分母。开放状态筛选可能改变总数口径。"
          />
          <div class="two-columns">
            <a-form-item label="日期范围字段">
              <a-select
                v-model:value="config.dateFieldId"
                allow-clear
                :options="fields.filter(dateField).map(f => ({ value: f.id!, label: f.name }))"
                placeholder="不设置则不显示日期筛选"
              />
            </a-form-item>
            <a-form-item label="日期统计时区">
              <a-select
                v-model:value="config.timeZone"
                :options="[
                  { value: 'Asia/Shanghai', label: '北京时间' },
                  { value: 'UTC', label: 'UTC' }
                ]"
              />
            </a-form-item>
          </div>
          <a-form-item label="下钻明细视图">
            <a-select
              v-model:value="config.detailViewId"
              allow-clear
              :options="drillViewOptions"
              :placeholder="detailGrain ? '默认：命中的明细行（只读）' : '默认对象字段'"
            />
            <a-alert v-if="drillViewError" type="error" show-icon data-drill-view-error :message="drillViewError" />
            <p v-if="detailGrain" class="hint" data-detail-grain-drill>
              按明细行统计时，只能选「一行表示一条内部明细」且明细来源为「{{
                grainDetail?.name || '所选明细'
              }}」的数据视图；不选则下钻显示命中的明细行（只读）。
            </p>
            <p v-if="detailGrain && !drillViewOptions.length" class="hint" data-detail-grain-drill-empty>
              还没有这样的视图：在应用里新建一个数据视图，「一行表示」选「一条内部明细」、明细来源选「{{
                grainDetail?.name || '所选明细'
              }}」。
            </p>
            <p v-if="multi" class="hint" data-multi-source-drill>
              多个来源时这里是来源 1 的下钻明细视图；其它来源在「数据来源」里各自选。点格子看的是该指标所属来源的明细。
            </p>
            <p class="hint">所选视图的固定条件同时限定统计与明细。点击指标时继续叠加该指标条件。</p>
            <p v-if="detailViewFiltered" class="hint" data-detail-view-filtered>统计会按该视图的固定筛选收窄。</p>
          </a-form-item>
          <a-form-item label="明细允许编辑">
            <a-checkbox
              :checked="!!config.detailEditable"
              :disabled="!config.detailViewId"
              data-detail-editable
              @update:checked="(v: boolean) => (config.detailEditable = v)"
            >
              允许在下钻明细中新建、编辑、删除
            </a-checkbox>
            <p class="hint">
              默认关闭，只能查看。开启后仍须下钻视图配置了对应按钮且当前用户对记录有写权限；未选下钻视图时不可开启。
            </p>
          </a-form-item>
        </a-tab-pane>
        <a-tab-pane key="style" tab="展示与格式">
          <template v-if="config.chart">
            <a-form-item v-if="config.display === 'BAR'" label="柱状图方式">
              <a-segmented
                v-model:value="config.chart.barMode"
                :options="[
                  { value: 'GROUPED', label: '分组' },
                  { value: 'STACKED', label: '堆叠' },
                  { value: 'PERCENT', label: '百分比堆叠' }
                ]"
              />
            </a-form-item>
            <a-alert
              v-if="config.display === 'BAR' && config.chart.barMode !== 'GROUPED'"
              type="warning"
              message="请只堆叠同单位、互斥且非负的数量。总数与其组成部分应分开，否则会重复累计。"
            />
            <a-space>
              <a-checkbox v-if="config.display === 'BAR'" v-model:checked="config.chart.horizontal">
                横向展示
              </a-checkbox>
              <a-checkbox v-model:checked="config.chart.labels">显示数据标签</a-checkbox>
            </a-space>
            <a-form-item label="图例位置">
              <a-select
                v-model:value="config.chart.legendPosition"
                :options="[
                  { value: 'TOP', label: '上方' },
                  { value: 'BOTTOM', label: '下方' },
                  { value: 'LEFT', label: '左侧' },
                  { value: 'RIGHT', label: '右侧' }
                ]"
              />
            </a-form-item>
          </template>
          <section v-for="m in config.metrics" :key="m.id" class="config-section">
            <h4>{{ m.name }}</h4>
            <div v-if="m.format" class="config-row">
              <a-input
                v-model:value="m.format.unit"
                placeholder="单位，如台、元"
                :maxlength="20"
                :disabled="m.format.percent"
              />
              <a-input-number
                :value="m.format.financial ? 2 : m.format.decimals"
                :min="0"
                :max="8"
                :disabled="m.format.financial"
                placeholder="保留原始精度"
                @update:value="m.format.decimals = $event"
              />
              <a-checkbox v-model:checked="m.format.percent">百分比</a-checkbox>
              <a-checkbox
                v-model:checked="m.format.financial"
                :disabled="m.format.percent || m.operation !== 'FORMULA'"
              >
                金额格式（固定两位）
              </a-checkbox>
              <input
                type="color"
                :value="m.format.color || '#4f46e5'"
                :aria-label="m.name + '颜色'"
                @input="m.format.color = ($event.target as HTMLInputElement).value"
              />
            </div>
          </section>
          <section class="config-section" data-section="sort">
            <h4>
              排序与行数
              <span>
                {{
                  tableDisplay
                    ? '这里定的是打开时的默认顺序；查看时还可以点列头临时排序'
                    : '决定图表里各组的先后；展示组数有限时，排在前面的组优先展示'
                }}
              </span>
            </h4>
            <div class="two-columns">
              <a-form-item :label="pivot ? '行排序依据' : '排序依据'">
                <a-select
                  :value="sortTarget"
                  :options="sortTargetOptions"
                  :aria-label="pivot ? '行排序依据' : '排序依据'"
                  data-sort-target
                  @change="(v: string) => selectSortTarget(v)"
                />
              </a-form-item>
              <a-form-item label="排序方向">
                <a-segmented
                  :value="sortDirection"
                  :options="directionOptions"
                  data-sort-direction
                  @change="(v: string) => selectSortDirection(v)"
                />
              </a-form-item>
            </div>
            <p v-if="pivot && config.dimensions.length > 1" class="hint">
              多层行维度时，每一层在各自的上级分组内排序；小计行跟着所在分组，合计行始终在最下。
            </p>
            <div class="two-columns">
              <a-form-item v-if="pivot && config.pivot && (config.columnDimensions || []).length" label="列组排序">
                <a-segmented
                  :value="config.pivot.columnDescending ? 'DESC' : 'ASC'"
                  :options="directionOptions"
                  data-column-direction
                  @change="(v: string) => selectColumnDirection(v)"
                />
                <p class="hint">按列维度的值排列各列组，例如选降序让新的月份排在前面。</p>
              </a-form-item>
              <a-form-item :label="tableDisplay ? '最多展示行数' : '最多展示组数'">
                <a-input-number
                  v-model:value="config.limit"
                  :min="1"
                  :max="reportLimitMax(config.display)"
                  :precision="0"
                  :placeholder="tableDisplay ? '不限制' : '留空按 ' + MAX_REPORT_CHART_GROUPS + ' 组'"
                  :aria-label="tableDisplay ? '最多展示行数' : '最多展示组数'"
                  data-report-limit
                />
                <p class="hint" data-limit-hint>
                  {{
                    tableDisplay
                      ? '留空表示不限制，全部行都展示（一次最多 ' +
                        MAX_REPORT_TABLE_ROWS +
                        ' 行；透视表分了很多列组时会更少，行数 × 列组数不超过 ' +
                        MAX_REPORT_PIVOT_CELLS +
                        ' 格。超出时会在表格上方写明「共多少行，只显示前多少行」）。填了数字就只展示排在前面的这么多行。'
                      : '「不限制」只对透视表和汇总表有效。指标卡与图表最多展示 ' +
                        MAX_REPORT_CHART_GROUPS +
                        ' 组，留空时按 ' +
                        MAX_REPORT_CHART_GROUPS +
                        ' 组。'
                  }}
                </p>
              </a-form-item>
            </div>
          </section>
          <p class="hint">无记录的计数和求和显示 0；平均及极值显示 —。总体合计不受展示组数限制。</p>
        </a-tab-pane>
      </a-tabs>
    </div>
    <aside class="report-preview">
      <div class="section-heading">
        <h4>真实数据预览</h4>
        <a-button type="primary" :loading="previewBusy" @click="refreshPreview">刷新预览</a-button>
      </div>
      <p class="hint">按当前使用者权限读取，不保存配置和业务记录。</p>
      <a-alert v-if="previewError" :message="previewError" type="error" show-icon />
      <a-alert v-if="preview && previewStale" type="info" message="配置已变化，请刷新预览查看最新结果。" />
      <template v-if="preview && previewConfig">
        <a-checkbox v-if="!['TABLE', 'METRIC', 'PIVOT'].includes(previewConfig.display)" v-model:checked="previewTable">
          显示结果表
        </a-checkbox>
        <template v-if="previewConfig.display === 'PIVOT'">
          <ReportPivotTable v-if="preview.pivot" :config="previewConfig" :result="preview" :pivot="preview.pivot" />
        </template>
        <ReportChart
          v-else-if="!previewTable && !['TABLE', 'METRIC'].includes(previewConfig.display)"
          :config="previewConfig"
          :result="preview"
        />
        <a-table
          v-else-if="previewConfig.display !== 'METRIC'"
          :data-source="preview.groups"
          :columns="previewColumns"
          :pagination="preview.groups.length > 200 ? { pageSize: 100, showSizeChanger: false } : false"
          size="small"
          :scroll="{ x: 'max-content', y: 320 }"
        />
        <div class="preview-totals">
          <div v-for="m in preview.metrics" :key="m.id">
            <span>{{ m.name }} · 总体</span>
            <strong>{{ formatReportValue(preview.totals[m.id], m) }}</strong>
          </div>
        </div>
        <p v-if="previewSourcesFooter" class="hint" data-preview-source>
          {{ previewSourcesFooter }} · 显示 {{ preview.groups.length }} / {{ preview.totalGroups }} 组
        </p>
        <p v-else-if="preview.detailName" class="hint" data-preview-source>
          来源明细行 {{ preview.recordCount }} 行（明细「{{ preview.detailName }}」）· 显示
          {{ preview.groups.length }} / {{ preview.totalGroups }} 组 · {{ preview.timeZone }}
        </p>
        <p v-else class="hint">
          来源 {{ preview.recordCount }} 条 · 显示 {{ preview.groups.length }} / {{ preview.totalGroups }} 组 ·
          {{ preview.timeZone }}
        </p>
      </template>
      <a-empty v-else-if="!previewBusy" description="配置指标后，点击刷新预览" />
    </aside>
    <a-modal v-model:open="templateOpen" title="部门资产分析模板" @ok="applyTemplate">
      <a-alert
        type="info"
        message="选择实际字段和状态值，生成四个指标并替换当前指标。资产类型＝笔记本请在固定条件中配置。"
      />
      <a-form-item label="部门分组"><a-select v-model:value="template.group" :options="fieldOptions" /></a-form-item>
      <a-form-item label="状态字段">
        <a-select
          v-model:value="template.status"
          :options="fieldOptions"
          @change="template.values = [null, null, null]"
        />
      </a-form-item>
      <a-form-item label="统计单位">
        <a-select
          v-model:value="template.quantity"
          :options="[
            { value: '', label: '每条记录代表一台（记录计数）' },
            ...fields
              .filter(f => numericField(f, object?.relations))
              .map(f => ({ value: f.id!, label: f.name + '求和' }))
          ]"
        />
      </a-form-item>
      <template v-if="templateStatus">
        <a-form-item v-for="(label, i) in ['在用状态值', '空闲状态值', '损坏状态值']" :key="i" :label="label">
          <FixedFilterField
            v-model="template.values[i]"
            :application-id="applicationId"
            :entry="templateStatus"
            :objects="objects"
          />
        </a-form-item>
      </template>
    </a-modal>
  </div>
</template>
<style scoped>
.report-workspace {
  display: grid;
  grid-template-columns: minmax(520px, 1.2fr) minmax(350px, 1fr);
  gap: 24px;
  align-items: start;
}
.report-settings,
.report-preview {
  min-width: 0;
}
.report-preview {
  position: sticky;
  top: 0;
  padding: 20px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafbfe;
}
.config-section {
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  margin: 14px 0;
}
h4 {
  margin: 0 0 12px;
  font-weight: 600;
}
h4 span,
.hint {
  color: #64748b;
  font-size: 12px;
  font-weight: normal;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}
.config-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.config-row > .ant-select,
.config-row > .ant-input {
  flex: 1;
  min-width: 140px;
}
.metric-editor {
  padding: 14px 0;
  border-bottom: 1px solid #edf0f5;
  margin-bottom: 12px;
}
.metric-editor:has([data-metric-grain-error]) {
  padding: 14px 12px;
  border: 1px solid #ffccc7;
  border-radius: 8px;
  background: #fff8f7;
}
.metric-editor [data-metric-grain-error] {
  margin-bottom: 12px;
}
.grain-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
}
.grain-detail {
  display: flex;
  align-items: center;
  gap: 8px;
}
.grain-detail > span {
  white-space: nowrap;
}
.grain-detail .ant-select {
  min-width: 200px;
}
.grain-stale {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
}
.grain-stale > .ant-alert {
  flex: 1;
  min-width: 260px;
}
.dimension-hint {
  flex-basis: 100%;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.dimension-hint-text {
  flex-basis: 100%;
  margin: 0;
  padding: 8px 12px;
  border: 1px solid #ffe58f;
  border-radius: 6px;
  background: #fffbe6;
  font-size: 13px;
}
.two-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.preview-totals {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.preview-totals > div {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: white;
  border-radius: 8px;
}
.preview-totals span {
  color: #64748b;
  font-size: 12px;
}
.preview-totals strong {
  color: #312e81;
  font-size: 20px;
  overflow-wrap: anywhere;
}
.source-title {
  white-space: nowrap;
}
.source-card {
  padding: 12px 14px;
  margin: 12px 0;
  border: 1px solid #dbe3f0;
  border-left: 4px solid #6366f1;
  border-radius: 8px;
  background: #fbfcff;
}
.source-card-missing {
  border-color: #ffccc7;
  border-left-color: #ff4d4f;
  background: #fff8f7;
}
.source-card h5 {
  margin: 0;
  font-weight: 600;
}
.source-missing {
  margin: 6px 0;
  color: #cf1322;
  font-size: 12px;
}
.source-confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
  padding: 8px 12px;
  border: 1px solid #ffccc7;
  border-radius: 6px;
  background: #fff1f0;
}
.source-block {
  margin-top: 10px;
}
.source-label {
  display: block;
  margin-bottom: 6px;
  color: #334155;
  font-size: 12px;
  font-weight: 600;
}
.slot-row {
  margin-bottom: 6px;
}
.slot-target {
  min-width: 140px;
  color: #475569;
  font-size: 13px;
}
@media (max-width: 1100px) {
  .report-workspace {
    grid-template-columns: 1fr;
  }
  .report-preview {
    position: static;
  }
}
</style>
