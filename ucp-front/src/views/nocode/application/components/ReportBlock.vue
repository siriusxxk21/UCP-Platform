<script setup lang="ts">
import { orderedReadiness } from '@/nocode/ordered-calculation'
import { computed, defineAsyncComponent, h, onBeforeUnmount, ref, watch } from 'vue'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import { DownloadOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { useReportDashboard } from '@/nocode/report-context'
import { useApplicationRefresh } from '@/nocode/application-context'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
import { useRecordLive } from '@/nocode/record-live'
import {
  reportFilters,
  reportFieldOptions,
  reportTableDisplay,
  reportDetailId,
  reportDrillViewMatches
} from '@/nocode/report'
import { nextReportSort, reportSortDirection, validReportSort, type ReportSortTarget } from '@/nocode/report-sort'
import {
  metricSource,
  multiSource,
  reportSources,
  reportSourcesFooter,
  sourceLabel,
  type ReportSourceView
} from '@/nocode/report-sources'
import { recordFilterValue } from '@/nocode/record-form'
import { errorMessage } from '@/nocode/data-center'
import { recordDisplay } from '@/nocode/record-display'
import RichTextDisplay from '../../components/RichTextDisplay.vue'
import { hasRuntimeFieldId } from '@/nocode/runtime-field-projection'
import {
  ReportDisplay,
  type ReportConfig,
  type ReportDrill,
  type ReportQuery,
  type ReportResult,
  type ReportGroup,
  type ReportSort
} from '@/types/nocode/report'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { FormConfig, ViewConfig } from '@/types/nocode/application-ui'
import type { PivotSelection } from '@/nocode/report-pivot'
import type { RecordModel, RecordContext, BusinessRow } from '@/types/nocode/runtime'
import ReportFilterInput from './ReportFilterInput.vue'
import BusinessFileField from './BusinessFileField.vue'
import { FieldType } from '@/types/nocode/enums'
import { formatReportValue, metricDescription } from '@/nocode/report-presentation'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import ReportChart from './AsyncReportChart.vue'
import ReportConditionEditor from './ReportConditionEditor.vue'
import ReportPivotTable from './ReportPivotTable.vue'
// 下钻视图只在打开抽屉时加载；也避免 BusinessRecords → PageRenderer → ReportBlock 的静态循环。
const BusinessRecords = defineAsyncComponent(() => import('./BusinessRecords.vue'))
const props = defineProps<{
  applicationId: string
  resource: ApplicationResource
  /** 应用资源；用于渲染「下钻明细视图」。缺省时下钻退回只读明细表。 */
  resources?: ApplicationResource[]
  context?: RecordContext
  refreshKey?: number
  title?: string
}>()
/** 下钻目标：行键前缀 → group，列键前缀（仅透视表）→ columnGroup。 */
interface DrillTarget {
  group?: (string | null)[]
  columnGroup?: (string | null)[]
  label: string
  values?: Record<string, string | null>
}
const config = computed(() => props.resource.config as unknown as ReportConfig)
const api = useNocodePlatform().runtime,
  dashboard = useReportDashboard(),
  refresh = useApplicationRefresh()
const model = ref<RecordModel>(),
  result = ref<ReportResult>(),
  busy = ref(false),
  error = ref(''),
  draft = ref<Record<string, unknown>>({}),
  applied = ref<Record<string, unknown>>({}),
  range = ref<string[]>([]),
  appliedRange = ref<string[]>([]),
  exporting = ref(false),
  showTable = ref(false)
const filterModels = ref<Record<string, RecordModel>>({})
const detailOpen = ref(false),
  detailRows = ref<BusinessRow[]>([]),
  detailTotal = ref(0),
  detailPage = ref(1),
  detailBusy = ref(false),
  detailError = ref(''),
  selected = ref<DrillTarget>(),
  drill = ref<ReportDrill>()
const selectedMetric = ref<string>()
const analysisMetric = ref<string>(),
  analysisGroup = ref<DrillTarget>(),
  analysisOpen = ref(false)
// —— 多个数据来源：下钻看「所点指标所属来源」的明细（契约 laneM 第 9 章；各来源各自的下钻视图，业务方 2026-10-04）——
const multi = computed(() => multiSource(config.value))
/** 全部来源的对象（去重）；单来源就是 [objectId]。 */
const sourceObjectIds = computed(() => [...new Set(reportSources(config.value).map(s => s.objectId))])
/** 附加来源对象的运行端模型（来源 1 的仍是 model）。 */
const sourceModels = ref<Record<string, RecordModel>>({})
const drillSource = computed<ReportSourceView | undefined>(() =>
  multi.value
    ? metricSource(
        config.value,
        config.value.metrics.find(m => m.id === selectedMetric.value)
      )
    : undefined
)
const drillModel = computed(() =>
  drillSource.value && !drillSource.value.main ? sourceModels.value[drillSource.value.objectId] : model.value
)
const drillObjectId = computed(() => drillSource.value?.objectId || config.value.objectId)
const drillDetailId = computed(() =>
  drillSource.value ? reportDetailId(drillSource.value) : reportDetailId(config.value)
)
const drillViewId = computed(() => (drillSource.value ? drillSource.value.detailViewId : config.value.detailViewId))
const drillEditable = computed(() =>
  drillSource.value ? drillSource.value.detailEditable : !!config.value.detailEditable
)
/** 来源的显示名：优先用结果里的（来源 1 没填名称时后端给的是对象名）。 */
const sourceName = (source: ReportSourceView) =>
  result.value?.sources?.find(s => s.id === source.id)?.name ||
  sourceLabel(
    source,
    Object.fromEntries(Object.entries(sourceModels.value).map(([id, m]) => [id, { definition: m.object }]))
  )
/**
 * 配置了下钻明细视图时直接渲染该数据视图；未配置时保留只读明细表。按明细行统计时只认按同一明细逐行显示的数据视图
 * （下钻出来一行一条命中的明细）；视图形状不对（存量或后来被改）就退回只读明细行。
 */
const drillView = computed(() => {
  const id = drillViewId.value
  if (!id) return undefined
  const view = props.resources?.find(r => r.id === id && r.kind === ResourceKind.VIEW)
  const viewConfig = view ? (view.config as unknown as ViewConfig) : undefined
  // 按所点指标所属来源判形状（R6 衔接：明细粒度的附加来源也可挂视图；单来源 / 来源 1 即顶层配置）。
  if (drillDetailId.value && !reportDrillViewMatches(drillSource.value ?? config.value, viewConfig)) return undefined
  return viewConfig
})
const drillForm = computed(
  () => props.resources?.find(r => r.id === drillView.value?.formId)?.config as unknown as FormConfig | undefined
)
const calculated = computed(() => config.value.metrics.find(m => m.id === analysisMetric.value))
const analysisOperands = computed(() => {
  const formula = calculated.value?.formula
  return formula
    ? [formula.left, formula.right].map(id => ({ id, metric: config.value.metrics.find(m => m.id === id) }))
    : []
})
const advancedDraft = ref<DynamicSearchCondition | null>(null),
  advancedApplied = ref<DynamicSearchCondition | null>(null)
const conditionObjects = computed(() =>
  Object.fromEntries(Object.entries(filterModels.value).map(([id, m]) => [id, { definition: m.object }]))
)
const orderedStates = computed(() =>
  Object.fromEntries(
    Object.entries(filterModels.value).map(([id, model]) => [id, orderedReadiness(model.orderedStates)])
  )
)
const fields = computed(() => model.value?.object.fields.filter(hasRuntimeFieldId) || [])
/** 明细粒度：统计的粒度明细（运行端模型只含当前用户可读的明细，取不到时按没有处理）。 */
const grainDetail = computed(() => {
  const id = reportDetailId(config.value)
  return id ? model.value?.object.details?.find(d => d.id === id) : undefined
})
const grainFields = computed(() => grainDetail.value?.fields.filter(hasRuntimeFieldId) || [])
/** 指标说明里的字段名：全部来源的主表字段与粒度明细字段（单来源时 extraFields 为空，与原来相同）。 */
const extraFields = computed(() =>
  multi.value
    ? reportSources(config.value)
        .slice(1)
        .flatMap(s => {
          const object = sourceModels.value[s.objectId]?.object
          const detail = reportDetailId(s) ? object?.details?.find(d => d.id === s.detailId) : undefined
          return [...(object?.fields || []), ...(detail?.fields || [])].filter(hasRuntimeFieldId)
        })
    : []
)
const metricNames = computed(() =>
  Object.fromEntries([...fields.value, ...grainFields.value, ...extraFields.value].map(f => [f.id, f.name]))
)
/** 只读下钻表按所点指标所属来源出列：来源对象的字段与粒度明细（单来源即统计对象自己）。 */
const drillFields = computed(() => drillModel.value?.object.fields.filter(hasRuntimeFieldId) || [])
const drillGrainDetail = computed(() =>
  drillDetailId.value ? drillModel.value?.object.details?.find(d => d.id === drillDetailId.value) : undefined
)
const drillGrainFields = computed(() => drillGrainDetail.value?.fields.filter(hasRuntimeFieldId) || [])
const bound = computed(() => (dashboard?.definitions.value || []).filter(f => f.targets[props.resource.id]))
const localFields = computed(() =>
  reportFieldOptions(
    config.value.objectId,
    Object.fromEntries(Object.entries(filterModels.value).map(([id, m]) => [id, { definition: m.object }])),
    orderedStates.value,
    reportDetailId(config.value)
  ).filter(
    f =>
      config.value.filterFieldIds.includes(f.value) &&
      !bound.value.some(b => !b.dateRange && b.targets[props.resource.id] === f.value)
  )
)
const localDate = computed(() => config.value.dateFieldId && !bound.value.some(b => b.dateRange))
const query = (): ReportQuery => {
  const shared = reportFilters(props.resource.id, dashboard?.definitions.value || [], dashboard?.values.value || {})
  return {
    applicationId: props.applicationId,
    reportId: props.resource.id,
    context: props.context,
    conditions: advancedApplied.value,
    equal: { ...applied.value, ...shared.equal },
    dateFrom: localDate.value ? appliedRange.value[0] : shared.dateFrom,
    dateTo: localDate.value ? appliedRange.value[1] : shared.dateTo
  }
}
let generation = 0,
  detailGeneration = 0
/**
 * 查看时点列头的临时排序：只改变显示顺序，不改配置；翻页、静默刷新、切走再切回都保持（状态在本组件里，每次取数都带上）。
 * 只对汇总表与透视表开放；配置变了以后不再成立的排序（指标被删等）当作没点过。
 */
const sort = ref<ReportSort | null>(null)
const sortable = computed(() => reportTableDisplay(config.value.display))
const activeSort = computed(() => (sortable.value ? validReportSort(sort.value, config.value) : null))
/** 统计取数与导出带上临时排序（屏幕与导出同序）；明细下钻不带。 */
const reportQuery = (): ReportQuery => (activeSort.value ? { ...query(), sort: activeSort.value } : query())
const sortLabel = computed(() => {
  const current = activeSort.value
  if (!current) return ''
  const direction = current.descending ? '降序' : '升序'
  if (current.metricId == null)
    return '「' + (result.value?.dimensionNames[current.dimension ?? 0] || '行维度') + '」' + direction
  const metric = config.value.metrics.find(m => m.id === current.metricId)?.name || '指标'
  const column = result.value?.pivot?.columns.find(
    c => JSON.stringify(c.keys.slice(0, current.columnGroup?.length || 0)) === JSON.stringify(current.columnGroup || [])
  )
  const group = current.columnGroup?.length
    ? (column?.labels.slice(0, current.columnGroup.length).join(' / ') || current.columnGroup.join(' / ')) + ' · '
    : ''
  return '「' + group + metric + '」' + direction
})
/** 汇总表分页：行数不设上限后每页条数可调；排序与取数都在服务端，翻页不重新取数。 */
const tablePage = ref({ current: 1, pageSize: DEFAULT_PAGE_SIZE })
function toggleSort(target: ReportSortTarget) {
  applySort(nextReportSort(activeSort.value, target))
}
function applySort(next: ReportSort | null) {
  if (JSON.stringify(next) === JSON.stringify(activeSort.value)) return
  sort.value = next
  tablePage.value = { ...tablePage.value, current: 1 }
  void resort()
}
/** 换排序只重取统计结果（对象结构没变，不重取）；保留当前结果直到新结果到达，下钻抽屉不动。 */
async function resort() {
  if (!result.value || !model.value) return load(true)
  const turn = ++generation
  busy.value = true
  error.value = ''
  try {
    const data = await api.report(reportQuery())
    if (turn === generation) result.value = data
  } catch (e) {
    if (turn === generation) error.value = errorMessage(e)
  } finally {
    if (turn === generation) busy.value = false
  }
}
interface TableSorter {
  order?: 'ascend' | 'descend' | null
  column?: { sortTarget?: ReportSortTarget }
}
/** ant 表格的 change 事件依次给出：分页、筛选、排序。 */
function onTableChange(
  pagination: { current?: number; pageSize?: number },
  _filters?: unknown,
  sorter?: TableSorter | TableSorter[]
) {
  tablePage.value = { current: pagination.current || 1, pageSize: pagination.pageSize || DEFAULT_PAGE_SIZE }
  if (!sortable.value) return
  const single = Array.isArray(sorter) ? sorter[0] : sorter
  const target = single?.column?.sortTarget
  if (!single?.order || !target) return applySort(null)
  const descending = single.order === 'descend'
  applySort(
    'metricId' in target
      ? { metricId: target.metricId, columnGroup: [], descending }
      : { dimension: target.dimension, descending }
  )
}
/** 汇总表列头的排序属性（服务端排序，受控显示当前状态）。图表的「表格」视图不开放。 */
function tableSorter(target: ReportSortTarget) {
  if (config.value.display !== ReportDisplay.TABLE) return {}
  const direction = reportSortDirection(activeSort.value, target)
  return {
    sorter: true,
    sortDirections: ['ascend', 'descend'],
    sortOrder: direction === 'ascending' ? 'ascend' : direction === 'descending' ? 'descend' : null,
    sortTarget: target
  }
}
/** keep：抽屉内保存后重查，保留当前结果直到新结果到达，下钻抽屉保持打开。 */
async function load(keep = false) {
  const turn = ++generation
  busy.value = true
  error.value = ''
  if (!keep) result.value = undefined
  if (!keep) tablePage.value = { ...tablePage.value, current: 1 }
  try {
    const [data, metadata] = await Promise.all([
      api.report(reportQuery()),
      api.model(props.applicationId, config.value.objectId)
    ])
    const models: Record<string, RecordModel> = { [config.value.objectId]: metadata }
    for (const key of config.value.filterFieldIds) {
      if (!key.includes(':')) continue
      let current = metadata
      for (const relationId of key.split(':')[0].split('/')) {
        const relation = current.object.relations.find(r => r.id === relationId)
        if (!relation) throw new Error('统计筛选关系不可用')
        models[relation.targetObjectId] ||= await api.model(props.applicationId, relation.targetObjectId)
        current = models[relation.targetObjectId]
      }
    }
    // 多个数据来源：附加来源的对象模型（只读下钻表的列与格式化用）；单来源不多取。
    const extra: Record<string, RecordModel> = {}
    for (const id of sourceObjectIds.value)
      if (id !== config.value.objectId) extra[id] = models[id] || (await api.model(props.applicationId, id))
    if (turn !== generation) return
    result.value = data
    model.value = metadata
    filterModels.value = models
    sourceModels.value = extra
  } catch (e) {
    if (turn === generation) error.value = errorMessage(e)
  } finally {
    if (turn === generation) busy.value = false
  }
}
function apply() {
  try {
    applied.value = Object.fromEntries(
      localFields.value
        .filter(f => draft.value[f.value] != null && draft.value[f.value] !== '')
        .map(f => [f.value, recordFilterValue(f.field, String(draft.value[f.value]))])
    )
    advancedApplied.value = advancedDraft.value
    appliedRange.value = range.value || []
    detailOpen.value = false
    void load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function reset() {
  draft.value = {}
  range.value = []
  applied.value = {}
  appliedRange.value = []
  advancedDraft.value = null
  advancedApplied.value = null
  detailOpen.value = false
  void load()
}
// 同一轮里「应用刷新信号」与抽屉的 changed 事件只重查一次。
let reloadQueued = false
function scheduleReload() {
  if (reloadQueued) return
  reloadQueued = true
  void Promise.resolve().then(() => {
    reloadQueued = false
    void load(true)
  })
}
/** 静默重查用：失败不弹全局错误通知。 */
const detailsQuietly = (query: Parameters<typeof api.reportDetails>[0]) => api.reportDetails(query, { quiet: true })
/** 静默重查：不出 loading、不清空；结果没变不动界面，下钻抽屉保持打开。有可见的查询在跑时让它先跑完。 */
async function refreshQuietly() {
  if (busy.value || !result.value) return
  const turn = generation
  const data = await api.report(reportQuery(), { quiet: true })
  if (turn !== generation) return
  if (JSON.stringify(data) !== JSON.stringify(result.value)) result.value = data
  // 只读明细表（未配下钻视图）跟着重取当前页；配了下钻视图的由那个列表自己重取。
  if (!detailOpen.value || drillView.value || detailBusy.value) return
  const detailTurn = detailGeneration
  const details = await detailsQuietly({
    ...query(),
    group: selected.value?.group,
    columnGroup: selected.value?.columnGroup,
    metricId: selectedMetric.value,
    pageNo: detailPage.value,
    pageSize: DEFAULT_PAGE_SIZE
  })
  if (detailTurn !== detailGeneration || !detailOpen.value) return
  if (details.total !== detailTotal.value || JSON.stringify(details.list) !== JSON.stringify(detailRows.value)) {
    detailRows.value = details.list
    detailTotal.value = details.total
  }
}
useRuntimeDataRefresh({
  interest: () => (result.value ? { applicationId: props.applicationId, objectIds: sourceObjectIds.value } : undefined),
  refresh: refreshQuietly
})
// 别人改了数据：统计自己变（比列表慢一拍）。走静默重查，不关正在看的下钻明细。
useRecordLive({
  applicationId: () => props.applicationId,
  objectIds: () => sourceObjectIds.value,
  cadence: 'report',
  reload: refreshQuietly
})
const groupTarget = (g: ReportGroup): DrillTarget => ({ group: g.keys, label: g.labels.join(' / '), values: g.values })
/** 兼容直接传入汇总分组（ReportGroup）的调用方。 */
const isGroup = (target: DrillTarget | ReportGroup): target is ReportGroup =>
  Array.isArray((target as ReportGroup).keys)
const drillTarget = (target?: DrillTarget | ReportGroup): DrillTarget | undefined =>
  target ? (isGroup(target) ? groupTarget(target) : target) : undefined
function inspect(source: DrillTarget | ReportGroup | undefined, metricId: string) {
  const target = drillTarget(source)
  const m = config.value.metrics.find(m => m.id === metricId)
  if (!m) {
    error.value = '指标已失效，请刷新报表配置'
    return
  }
  if (m.formula) {
    analysisMetric.value = metricId
    analysisGroup.value = target
    analysisOpen.value = true
  } else void details(target, metricId)
}
function selectGroup(index: number, metricId: string) {
  const group = result.value?.groups[index]
  if (group) inspect(groupTarget(group), metricId)
}
function selectPivot(selection: PivotSelection) {
  inspect(
    {
      group: selection.rowKeys,
      columnGroup: selection.columnKeys,
      label: selection.label,
      values: selection.values
    },
    selection.metricId
  )
}
async function details(source?: DrillTarget | ReportGroup, metricId?: string) {
  const target = drillTarget(source)
  analysisOpen.value = false
  selectedMetric.value = metricId
  selected.value = target
  detailPage.value = 1
  // 下钻条件在点击时定格，与当前显示的统计结果同一口径（公共筛选随之传入）。
  const { equal, dateFrom, dateTo, conditions, context } = query()
  drill.value = {
    applicationId: props.applicationId,
    reportId: props.resource.id,
    group: target?.group,
    columnGroup: target?.columnGroup,
    metricId: metricId ?? null,
    equal,
    dateFrom,
    dateTo,
    conditions,
    // 报表在记录页区块内时带上同一个 context，保证与格子数值同一范围；不在记录页不传。
    ...(context ? { context } : {})
  }
  detailOpen.value = true
  if (!drillView.value) await loadDetails()
}
async function loadDetails() {
  const turn = ++detailGeneration
  detailBusy.value = true
  detailError.value = ''
  detailRows.value = []
  try {
    const data = await api.reportDetails({
      ...query(),
      group: selected.value?.group,
      columnGroup: selected.value?.columnGroup,
      metricId: selectedMetric.value,
      pageNo: detailPage.value,
      pageSize: DEFAULT_PAGE_SIZE
    })
    if (turn === detailGeneration) {
      detailRows.value = data.list
      detailTotal.value = data.total
    }
  } catch (e) {
    if (turn === detailGeneration) detailError.value = errorMessage(e)
  } finally {
    if (turn === detailGeneration) detailBusy.value = false
  }
}
const detailColumns = computed(() => [
  { title: '记录ID', dataIndex: 'id', key: 'id', width: 100 },
  ...drillFields.value
    .filter(f => detailRows.value.some(r => Object.hasOwn(r.values, f.id)))
    .map(f => ({
      title: f.name,
      key: f.id,
      customRender: ({ record }: { record: BusinessRow }) =>
        [FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === f.type)
          ? h(BusinessFileField, {
              modelValue: (record.values[f.id] || []) as string[],
              applicationId: props.applicationId,
              objectId: drillObjectId.value,
              recordId: record.id || undefined,
              fieldId: f.id!,
              businessPolicy: drillModel.value?.object.settings.businessFilePolicy || null,
              disabled: true
            })
          : f.type === FieldType.RICH_TEXT
            ? h(RichTextDisplay, { value: record.values[f.id], compact: true })
            : recordDisplay(record, f.id, drillModel.value?.object.fieldOptions[f.id], f)
    }))
])
/**
 * 明细粒度的只读下钻表：每行是一条明细行（id = 主记录ID:明细行ID，parentId = 主记录ID）。
 * 列 = 主记录ID → 粒度明细的字段 → 主表字段；行键仍是 id。
 */
const grainDetailColumns = computed(() => {
  const detail = drillGrainDetail.value
  const column = (f: (typeof fields.value)[number], own: boolean) => {
    const options = own ? detail?.fieldOptions[f.id] : drillModel.value?.object.fieldOptions[f.id]
    return {
      title: f.name,
      key: f.id,
      customRender: ({ record }: { record: BusinessRow }) =>
        [FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === f.type)
          ? h(BusinessFileField, {
              modelValue: (record.values[f.id] || []) as string[],
              applicationId: props.applicationId,
              objectId: drillObjectId.value,
              recordId: record.parentId || undefined,
              ...(own && detail?.id
                ? { detailId: detail.id, detailRecordId: record.id?.split(':').at(-1) || undefined }
                : {}),
              fieldId: f.id!,
              businessPolicy: drillModel.value?.object.settings.businessFilePolicy || null,
              disabled: true
            })
          : f.type === FieldType.RICH_TEXT
            ? h(RichTextDisplay, { value: record.values[f.id], compact: true })
            : recordDisplay(record, f.id, options, f)
    }
  }
  const present = (f: { id: string }) => detailRows.value.some(r => Object.hasOwn(r.values, f.id))
  return [
    { title: '主记录ID', dataIndex: 'parentId', key: 'parentId', width: 100 },
    ...drillGrainFields.value.filter(present).map(f => column(f, true)),
    ...drillFields.value.filter(present).map(f => column(f, false))
  ]
})
const drillColumns = computed(() => (drillDetailId.value ? grainDetailColumns.value : detailColumns.value))
const sourcesFooter = computed(() => (result.value ? reportSourcesFooter(result.value) : null))
/** 下钻抽屉标题：多来源时前面加所点指标的来源名（契约第 12 章）。 */
const drillTitlePrefix = computed(() => (drillSource.value ? sourceName(drillSource.value) + ' · ' : ''))
/** 引用指标后加「（来源名）」；计算指标没有来源。 */
const operandSource = (id: string) => {
  const source = multi.value
    ? metricSource(
        config.value,
        config.value.metrics.find(m => m.id === id)
      )
    : undefined
  return source ? '（' + sourceName(source) + '）' : ''
}
const groupColumns = computed(() => [
  ...(result.value?.dimensionNames || []).map((name, i) => ({
    title: name,
    key: 'd' + i,
    customRender: ({ record }: { record: ReportGroup }) => record.labels[i],
    ...tableSorter({ dimension: i })
  })),
  ...(result.value?.metrics || []).map(m => ({
    title: m.name,
    key: m.id,
    customRender: ({ record }: { record: ReportGroup }) =>
      h('a', { onClick: () => inspect(groupTarget(record), m.id) }, formatReportValue(record.values[m.id], m)),
    ...tableSorter({ metricId: m.id })
  })),
  // 多来源：没有「整行明细」（要点具体指标，契约 L19），不出操作列。
  ...(multi.value ? [] : [{ title: '操作', key: 'action', width: 90 }])
])
async function exportReport() {
  exporting.value = true
  try {
    const blob = await api.reportExport(reportQuery()),
      url = URL.createObjectURL(blob),
      link = document.createElement('a')
    link.href = url
    link.download = props.resource.name + '.xlsx'
    link.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    exporting.value = false
  }
}
// 换了一张统计：临时排序不带过去（先于下面的取数监听执行）。
watch(
  () => [props.applicationId, props.resource.id],
  () => {
    sort.value = null
  }
)
watch(
  () => [props.applicationId, props.resource.id, props.context, dashboard?.values.value],
  () => {
    detailOpen.value = false
    void load()
  },
  { immediate: true, deep: true }
)
watch(
  () => [props.refreshKey, refresh.value],
  () => {
    // 只读明细表不随刷新更新，关闭以免显示过期数据；数据视图抽屉自行重查。
    if (!drillView.value) detailOpen.value = false
    scheduleReload()
  }
)
onBeforeUnmount(() => {
  generation++
  detailGeneration++
})
</script>
<template>
  <a-card :title="title || resource.name" size="small" class="report-block">
    <template #extra>
      <a-space>
        <a-button
          v-if="
            config.display !== ReportDisplay.METRIC &&
            config.display !== ReportDisplay.TABLE &&
            config.display !== ReportDisplay.PIVOT
          "
          size="small"
          @click="showTable = !showTable"
        >
          <TableOutlined />
          {{ showTable ? '图表' : '表格' }}
        </a-button>
        <a-button size="small" :loading="busy" @click="load">
          <ReloadOutlined />
          刷新
        </a-button>
        <a-button v-if="result?.canExport" size="small" :loading="exporting" @click="exportReport">
          <DownloadOutlined />
          导出
        </a-button>
      </a-space>
    </template>
    <a-form v-if="model && (localFields.length || localDate)" layout="inline" class="report-filters">
      <a-form-item v-for="field in localFields" :key="field.value" :label="field.label">
        <ReportFilterInput
          v-model="draft[field.value]"
          :model="filterModels[field.objectId]"
          :field="field.field"
          :application-id="applicationId"
          :detail-id="field.detailId"
        />
      </a-form-item>
      <a-form-item v-if="localFields.length" label="组合筛选">
        <ReportConditionEditor
          v-model="advancedDraft"
          :application-id="applicationId"
          :entries="localFields"
          :objects="conditionObjects"
          label="设置筛选"
        />
      </a-form-item>
      <a-form-item v-if="localDate" label="日期">
        <a-range-picker v-model:value="range" value-format="YYYY-MM-DD" />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" size="small" @click="apply">查询</a-button>
          <a-button size="small" @click="reset">重置</a-button>
        </a-space>
      </a-form-item>
    </a-form>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-spin :spinning="busy">
      <template v-if="result">
        <p v-if="activeSort" class="report-sort-state" data-report-sort>
          已按{{ sortLabel }}排列（只影响当前查看，不改变配置）
          <a data-report-sort-reset @click="applySort(null)">恢复默认排序</a>
        </p>
        <div v-if="config.display === ReportDisplay.METRIC" class="metric-grid">
          <button v-for="m in result.metrics" :key="m.id" class="metric-cell" @click="inspect(undefined, m.id)">
            <span>{{ m.name }}</span>
            <strong>{{ formatReportValue(result.totals[m.id], m) }}</strong>
            <small>查看明细 →</small>
          </button>
        </div>
        <template v-else-if="config.display === ReportDisplay.PIVOT">
          <ReportPivotTable
            v-if="result.pivot?.rows.length"
            :config="config"
            :result="result"
            :pivot="result.pivot"
            sortable
            :sort="activeSort"
            @select="selectPivot"
            @sort="toggleSort"
          />
          <a-empty v-else description="当前条件下暂无数据" />
        </template>
        <a-empty v-else-if="!result.groups.length" description="当前条件下暂无数据" />
        <a-table
          v-else-if="showTable || config.display === ReportDisplay.TABLE"
          :data-source="result.groups"
          :columns="groupColumns"
          :row-key="(g: ReportGroup) => JSON.stringify(g.keys)"
          :pagination="{
            current: tablePage.current,
            pageSize: tablePage.pageSize,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
            showTotal: (n: number) => '共 ' + n + ' 行'
          }"
          size="small"
          @change="onTableChange"
        >
          <template #bodyCell="{ column, record }">
            <a v-if="column.key === 'action'" @click="details(groupTarget(record))">查看明细</a>
          </template>
        </a-table>
        <ReportChart v-else :config="config" :result="result" @select="selectGroup" />
        <a-alert
          v-if="
            config.display === ReportDisplay.PIE &&
            result.groups.some(g => Object.values(g.values).some(v => v != null && Number(v) < 0))
          "
          type="warning"
          show-icon
          message="含负数，饼图无法表达完整占比；请切换表格或改用柱状图。"
        />
        <div v-if="config.display !== ReportDisplay.METRIC" class="report-totals">
          <button
            v-for="m in result.metrics"
            :key="m.id"
            class="total-metric"
            :title="metricDescription(m, config, metricNames)"
            @click="inspect(undefined, m.id)"
          >
            <span>{{ m.name }} · 总体</span>
            <strong>{{ formatReportValue(result.totals[m.id], m) }}</strong>
          </button>
        </div>
        <footer class="report-footer">
          <span v-if="sourcesFooter" data-report-sources>{{ sourcesFooter }}</span>
          <span v-else-if="reportDetailId(config)" data-report-source>
            来源明细行 {{ result.recordCount }} 行（明细「{{ result.detailName || grainDetail?.name || '' }}」）· 时区
            {{ result.timeZone }}
          </span>
          <span v-else>来源记录 {{ result.recordCount }} 条 · 时区 {{ result.timeZone }}</span>
          <span v-if="config.display === ReportDisplay.PIVOT && result.pivot">
            显示 {{ result.pivot.rows.length }} / {{ result.pivot.totalRowGroups }} 行 ·
            {{ result.pivot.columns.length }} / {{ result.pivot.totalColumnGroups }} 列组
          </span>
          <span v-else-if="config.display !== ReportDisplay.METRIC">
            显示 {{ result.groups.length }} / {{ result.totalGroups }} 组
          </span>
          <a v-if="!multi" @click="details()">全部明细</a>
        </footer>
        <a-alert
          v-if="config.display !== ReportDisplay.PIVOT && result.totalGroups > result.groups.length"
          type="info"
          show-icon
          data-report-truncated
          :message="
            config.display === ReportDisplay.TABLE && config.limit == null
              ? '共 ' +
                result.totalGroups +
                ' 行，只显示前 ' +
                result.groups.length +
                ' 行（已到一次展示的上限，请加筛选条件或改用更粗的分组；导出可包含更多行）。总体指标和全部明细仍按完整条件计算。'
              : '当前只展示设定数量的分组，图表和导出不包含全部分组；总体指标和全部明细仍按完整条件计算。'
          "
        />
      </template>
    </a-spin>
    <a-drawer
      v-model:open="detailOpen"
      :title="
        drillTitlePrefix +
        resource.name +
        ' · ' +
        (selected?.label || '全部') +
        ' · ' +
        (config.metrics.find(m => m.id === selectedMetric)?.name || '明细')
      "
      width="min(1180px, 94vw)"
      :destroy-on-close="true"
    >
      <p class="detail-hint">
        明细沿用公共筛选、当前分组、所选指标条件和实时权限。去重计数显示参与计算的原始记录，记录条数可能多于唯一值数。
        <template v-if="drillDetailId">按明细行统计：下面每一行是一条明细，带着所属主记录的信息。</template>
      </p>
      <BusinessRecords
        v-if="drillView && drill && drillViewId"
        :application-id="applicationId"
        :object-id="drillView.objectId"
        :view-id="drillViewId"
        :view="drillView"
        :form="drillForm"
        :resources="resources"
        :report-drill="drill"
        :read-only="!drillEditable"
        @changed="scheduleReload"
      />
      <template v-else>
        <a-alert v-if="detailError" :message="detailError" type="error" show-icon />
        <a-table
          :data-source="detailRows"
          :columns="drillColumns"
          row-key="id"
          :loading="detailBusy"
          :scroll="{ x: 'max-content' }"
          :pagination="{
            current: detailPage,
            pageSize: DEFAULT_PAGE_SIZE,
            total: detailTotal,
            showSizeChanger: false,
            showTotal: (n: number) => '共 ' + n + ' 条'
          }"
          @change="
            (p: { current?: number }) => {
              detailPage = p.current || 1
              loadDetails()
            }
          "
        />
      </template>
    </a-drawer>
    <a-modal v-model:open="analysisOpen" title="指标计算依据" :footer="null">
      <template v-if="calculated?.formula && result">
        <p>{{ calculated.name }}：{{ metricDescription(calculated, config, metricNames) }}</p>
        <p>当前值：{{ formatReportValue((analysisGroup?.values || result.totals)[calculated.id], calculated) }}</p>
        <p v-for="(operand, i) in analysisOperands" :key="i">
          {{ i === 0 ? '左侧指标 / 分子' : '右侧指标 / 分母' }}：
          <a v-if="operand.metric" @click="inspect(analysisGroup, operand.id)">
            {{ operand.metric.name }}{{ operandSource(operand.id) }} ·
            {{ formatReportValue((analysisGroup?.values || result.totals)[operand.id], operand.metric) }}
          </a>
          <span v-else>指标已失效（{{ operand.id }}）</span>
        </p>
        <p class="detail-hint">点击引用指标查看明细或继续展开计算。总体比例按总体分子、分母重算。</p>
      </template>
    </a-modal>
  </a-card>
</template>
<style scoped>
.report-totals {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
}
.total-metric {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid #e8e7f7;
  border-radius: 8px;
  background: #fafaff;
  text-align: left;
  cursor: pointer;
}
.total-metric span {
  font-size: 12px;
  color: #64748b;
}
.total-metric strong {
  color: #312e81;
}

.report-block {
  width: 100%;
  min-width: 0;
  margin-bottom: 16px;
  border-radius: 8px;
}
.report-filters {
  row-gap: 12px;
  margin-bottom: 16px;
}
.report-filters :deep(.ant-select),
.report-filters :deep(.ant-input-affix-wrapper) {
  min-width: 140px;
}
.chart-canvas {
  width: 100%;
  height: 340px;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
}
.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px;
  text-align: left;
  border: 1px solid #e8e7f7;
  border-radius: 8px;
  background: #fafaff;
  cursor: pointer;
}
.metric-cell:hover {
  border-color: #4f46e5;
  background: #f4f3ff;
}
.metric-cell span,
.report-footer,
.detail-hint {
  color: #64748b;
  font-size: 12px;
}
.metric-cell strong {
  color: #312e81;
  font-size: 26px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.metric-cell small {
  color: #4f46e5;
}
.report-sort-state {
  margin: 0 0 8px;
  color: #64748b;
  font-size: 12px;
}
.report-sort-state a {
  margin-left: 8px;
}
.report-footer {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
</style>
