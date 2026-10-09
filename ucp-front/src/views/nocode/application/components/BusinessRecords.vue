<script setup lang="ts">
import { businessFields, fieldRelation, recordTitle, isRelationFieldId } from '@/nocode/business-fields'
import { computed, ref, watch, markRaw, defineAsyncComponent, inject, provide, onBeforeUnmount } from 'vue'
import { message, Modal, Drawer } from 'ant-design-vue'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  PlayCircleOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import type { SorterResult } from 'ant-design-vue/es/table/interface'
import { hasRuntimeFieldId } from '@/nocode/runtime-field-projection'
import { useUserStore } from '@/stores/user'
import {
  basicQuery,
  combineConditions,
  deleteRecordBatch,
  dynamicQueryField,
  listPreferenceKey,
  normalizeAdvancedQuery,
  pageSizeOptions,
  supportsAdvancedQuery
} from '@/nocode/runtime-list'
import SelectionField from './SelectionField.vue'
import BusinessFileField from './BusinessFileField.vue'
import HyperlinkField from './HyperlinkField.vue'
import { selectionSource } from '@/nocode/selection'
import RecordQueryField from './RecordQueryField.vue'
import '../../management-tables.css'
import { triggerDownload } from '@/utils/file'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { resolveViewForm } from '@/nocode/default-form'
import { recordDisplay } from '@/nocode/record-display'
import { ruleAutoUpdate } from '@/nocode/field-rule-runtime'
import { AUTO_UPDATE_TEXT, AutoUpdateMark } from '@/nocode/auto-update-mark'
import {
  calculationQueryReady,
  calculationValueField,
  storedOrderedCalculation
} from '@/nocode/calculation-presentation'
import { orderedReadiness, orderedStateLabel } from '@/nocode/ordered-calculation'
import { richTextSummary } from '@/nocode/rich-text'
import RichTextDisplay from '../../components/RichTextDisplay.vue'
import { MemberState, FieldType, RelationType } from '@/types/nocode/enums'
import { directoryOptions, directoryTypes } from '@/nocode/directory-options'
import { BusinessAction } from '@/types/nocode/authorization'
import { BusinessActionKind } from '@/types/nocode/business'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { AppDictionary } from '@/types/nocode/business'
import type { Aggregate, BusinessRow, RecordModel, RecordContext } from '@/types/nocode/runtime'
import type { ViewConfig, FormConfig, PageConfig } from '@/types/nocode/application-ui'
import { findPageNode } from '@/nocode/page-context'
import RecordEditor from './RecordEditor.vue'
import DataViewChildren from './DataViewChildren.vue'
import type { DataViewModel, ChildFilter } from '@/types/nocode/data-view'
import RecordSurface from './RecordSurface.vue'
import { ViewButton, RecordOpenMode, ListOverflow } from '@/types/nocode/application-ui'
import { viewButtonEnabled, viewBusinessActions } from '@/nocode/view-interaction'
import { useApplicationRefresh } from '@/nocode/application-context'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
import { liveChangeTouches, useRecordLive, type LiveChange } from '@/nocode/record-live'
import { isRecordConflict, isRecordMissing, REBASE_DELETE } from '@/nocode/save-rebase'
import { taskEntrySessionKey } from '@/nocode/task-entry-context'
import { provideReadOnly } from '@/nocode/record-read-only'
import type { ReportDrill } from '@/types/nocode/report'
import { splitQueryFields } from '@/nocode/runtime-search-placement'
import type { ApplicationDashboardDrill } from '@/types/nocode/application-dashboard-runtime'
const refresh = useApplicationRefresh()
const PageRenderer = defineAsyncComponent(() => import('./PageRenderer.vue'))
const detailDepth = inject<number>('nocode-detail-depth', 0)
provide('nocode-detail-depth', detailDepth + 1)

const props = withDefaults(
  defineProps<{
    title?: string
    standalone?: boolean
    inlineEditing?: boolean
    interactionDisplayMode?: 'modal' | 'drawer'
    confirmAction?: (title: string, content?: string, okText?: string) => Promise<boolean>
    confirmLeave?: (changed: boolean, title?: string) => Promise<boolean>
    initialRecordId?: string
    refreshKey?: number
    applicationId: string
    objectId: string
    viewId?: string
    view?: ViewConfig
    form?: FormConfig
    /** 受控入口可以明确传 null，防止继承列表或对象的表单绑定。 */
    formId?: string | null
    inheritDefaultForm?: boolean
    resources?: ApplicationResource[]
    context?: RecordContext
    /** 统计下钻：列表与该视图自身条件取交集。 */
    dashboardDrill?: ApplicationDashboardDrill
    reportDrill?: ReportDrill
    /** 只保留查看；新建/编辑/删除/导入/批量/业务动作全部隐藏。只能收不能放。 */
    readOnly?: boolean
    /** 查询栏的外部挂载点（页签行右侧）；没有时查询栏在表格标题行。 */
    searchTarget?: HTMLElement | null
  }>(),
  { inheritDefaultForm: true }
)
/** 抽屉内写操作成功后通知宿主（如统计视图重新查询）。 */
const emit = defineEmits<{ changed: []; fault: [cause: unknown] }>()
const readOnly = provideReadOnly(() => props.readOnly)
const api = useNocodePlatform().runtime
const user = useUserStore()
const table = ref<InstanceType<typeof OsTablePage>>()
const preferenceKey = computed(() =>
  listPreferenceKey(user.userInfo?.id, props.applicationId, props.objectId, props.viewId)
)
const actions = computed(() => viewBusinessActions(props.view, props.resources || [], props.objectId))
const formResolution = computed(() => {
  try {
    const explicitId = props.formId !== undefined ? props.formId : props.view?.formId
    const resource =
      explicitId || props.inheritDefaultForm !== false
        ? resolveViewForm(props.resources || [], props.objectId, explicitId)
        : undefined
    return { resource, error: '' }
  } catch (e) {
    return { resource: undefined, error: errorMessage(e) }
  }
})
const effectiveForm = computed(
  () => (formResolution.value.resource?.config as unknown as FormConfig | undefined) || props.form
)
const effectiveFormId = computed(() => formResolution.value.resource?.id)
const showButton = (button: ViewButton) =>
  !(readOnly.value && button !== ViewButton.VIEW) &&
  // 导出接口不接受下钻条件；下钻列表内导出会得到整个视图，故不提供。
  !((props.reportDrill || props.dashboardDrill) && button === ViewButton.EXPORT) &&
  !(
    (formResolution.value.error || effectiveForm.value?.options?.readOnly) &&
    [ViewButton.CREATE, ViewButton.UPDATE].some(b => b === button)
  ) &&
  viewButtonEnabled(props.view, button)
const model = ref<RecordModel>(),
  rows = ref<BusinessRow[]>([]),
  total = ref(0),
  pageNo = ref(1),
  pageSize = ref(DEFAULT_PAGE_SIZE),
  search = ref(''),
  error = ref(''),
  loading = ref(false),
  drillTotal = ref<number | null>(null),
  drillDifferentGrain = ref(false)
const drillExcluded = computed(() =>
  (props.reportDrill || props.dashboardDrill) &&
  !drillDifferentGrain.value &&
  drillTotal.value != null &&
  total.value < drillTotal.value
    ? drillTotal.value - total.value
    : 0
)
const viewModel = ref<DataViewModel>()
const childFilters = ref<ChildFilter[]>([])
const expanded = ref<(string | number)[]>([])
const hasChildren = computed(
  () =>
    viewModel.value?.composition?.grain === 'ROOT' &&
    viewModel.value.composition.sections.some(s => s.showTable !== false)
)
const displayOptions = computed(() => ({ ...model.value?.object.fieldOptions, ...viewModel.value?.fieldOptions }))
/** 列头是否加「系统自动更新」标识：该列字段是开了自动更新的只读联动。 */
const autoUpdateColumn = (key: unknown) => typeof key === 'string' && ruleAutoUpdate(displayOptions.value[key])
function applyChildFilter(filter: ChildFilter) {
  childFilters.value = [...childFilters.value.filter(f => f.sectionId !== filter.sectionId), filter]
  pageNo.value = 1
  void loadRows()
}
function removeChildFilter(id: string) {
  childFilters.value = childFilters.value.filter(f => f.sectionId !== id)
  pageNo.value = 1
  void loadRows()
}
const contextNode = computed(() => {
  const context = props.context
  if (!context) return undefined
  const view = props.resources?.find(r => r.id === context.pageId && r.kind === ResourceKind.VIEW)
    ?.config as unknown as ViewConfig | undefined
  const section = view?.composition?.sections.find(s => s.id === context.nodeId)
  if (section) return { binding: section.binding }
  const page = props.resources?.find(r => r.id === context.pageId)?.config as unknown as PageConfig | undefined
  return findPageNode(page?.nodes || [], context.nodeId)
})
const incomingRelation = computed(() =>
  contextNode.value?.binding?.direction === 'INCOMING'
    ? model.value?.object.relations.find(r => r.id === contextNode.value?.binding?.relationId)
    : undefined
)
const canCreateRelated = computed(
  () =>
    !props.context || (!!incomingRelation.value?.fieldId && incomingRelation.value.kind !== RelationType.MANY_TO_MANY)
)
const lockedValues = computed(() =>
  props.context && incomingRelation.value?.fieldId
    ? { [incomingRelation.value.fieldId]: props.context.recordId }
    : undefined
)
const detailOpen = ref(false),
  detailRecord = ref<Aggregate>()
const editorOpen = ref(false),
  editing = ref<Aggregate>()
/** 列表被内联编辑 / 内联详情遮住时，挂在页签行上的查询栏也收回来（随表格一起隐藏）。 */
const listShown = computed(() => !props.inlineEditing || (!editorOpen.value && !detailOpen.value))
// 正看着的详情被别人删了：详情不关、内容留着，顶上标出来。
const detailDeleted = ref(false)
const detailResource = computed(() =>
  detailDepth < 4
    ? props.resources?.find(r => r.id === props.view?.detailPageId && r.kind === ResourceKind.PAGE)
    : undefined
)
const detailConfig = computed(() => detailResource.value?.config as unknown as PageConfig | undefined)
// 同一列表只接受最后一次打开意图；旧请求不能重开已关闭窗口或替换正在编辑的记录。
let recordOpenGeneration = 0
onBeforeUnmount(() => recordOpenGeneration++)
async function showDetail(row: BusinessRow) {
  if (!showButton(ViewButton.VIEW)) return
  const id = row.parentId || row.id
  if (!id) {
    error.value = '记录尚未保存，无法查看详情'
    return
  }
  const current = ++recordOpenGeneration
  try {
    if (!detailConfig.value && formResolution.value.error) throw new Error(formResolution.value.error)
    const record = await api.get(props.applicationId, props.objectId, id)
    if (current !== recordOpenGeneration) return
    detailRecord.value = record
    detailDeleted.value = false
    detailOpen.value = true
  } catch (e) {
    if (current === recordOpenGeneration) error.value = errorMessage(e)
  }
}
const labels = ref<Record<string, Record<string, string>>>({})
let rowGeneration = 0
watch([editorOpen, detailOpen], ([editor, detail], [previousEditor, previousDetail]) => {
  if ((previousEditor && !editor) || (previousDetail && !detail)) recordOpenGeneration++
})
const queryValues = ref<Record<string, unknown>>({})
const allowedValues = (id: string) => props.view?.query?.candidates[id]
const advancedConditions = ref<DynamicSearchCondition | null>(null)
const appliedQuery = ref<{ search: string; equal: Record<string, unknown>; conditions: DynamicSearchCondition | null }>(
  { search: '', equal: {}, conditions: null }
)
const sortFieldId = ref<string>()
const descending = ref(true)
const selectedKeys = ref<(string | number)[]>([])
const batchBusy = ref(false)
const batchResult = ref('')
const fields = computed(() =>
  [...(model.value ? businessFields(model.value.object) : []), ...(viewModel.value?.fields || [])]
    .filter(hasRuntimeFieldId)
    .filter(f => displayOptions.value[f.id]?.state !== MemberState.INACTIVE)
)
const visibleFields = computed(() =>
  props.view
    ? [
        ...new Set([
          ...props.view.fieldIds,
          ...(viewModel.value?.fields.filter(hasRuntimeFieldId).map(f => f.id) || [])
        ])
      ]
        .map(id => fields.value.find(f => f.id === id))
        .filter((f): f is NonNullable<typeof f> => !!f)
    : fields.value.slice(0, 12)
)
const queryFields = computed(() =>
  (props.view?.list?.queryFieldIds || [])
    .map(id => fields.value.find(f => f.id === id))
    .filter((f): f is NonNullable<typeof f> => !!f && f.type !== FieldType.SUMMARY && queryReady(f.id))
    .map(field => calculationValueField(field, displayOptions.value[field.id]))
)
/** 行内只放前几个常用查询字段，其余收进「更多条件」（展开在表格顶部）；收起时照常参与查询。 */
const queryParts = computed(() => splitQueryFields(queryFields.value))
const moreOpen = ref(false)
const moreFilled = computed(
  () =>
    queryParts.value.more.filter(field => {
      try {
        const part = basicQuery([field], queryValues.value)
        return Object.keys(part.equal).length > 0 || !!part.conditions
      } catch {
        return true
      }
    }).length
)
const calculationStates = computed(() => orderedReadiness(model.value?.orderedStates))
function queryReady(id: string) {
  return calculationQueryReady(displayOptions.value[id], calculationStates.value[id])
}
function choices(id: string) {
  const field = fields.value.find(f => f.id === id)
  if (!field) return []
  const allowed = allowedValues(id)
  const dictionaryId = props.view?.filterDictionaries?.[id]
  const options = dictionaryId
    ? (props.resources?.find(r => r.id === dictionaryId)?.config as unknown as AppDictionary)?.items
    : displayOptions.value[id]?.options
  return (
    (options?.length || dictionaryId || selectionSource(field, displayOptions.value[id]) ? options : undefined) ||
    (allowed || []).map(code => ({ code, label: code, disabled: false }))
  )
    .filter(i => !i.disabled && (allowed == null || allowed.includes(i.code)))
    .map(i => ({ label: i.label, value: i.code }))
}
const advancedFields = computed(() =>
  fields.value
    .filter(
      f =>
        supportsAdvancedQuery(f) &&
        queryReady(f.id) &&
        (props.view?.list?.advancedFieldIds == null || props.view.list.advancedFieldIds.includes(f.id))
    )
    .map(f => {
      const options = displayOptions.value[f.id]
      const source = selectionSource(f, options)
      const field = dynamicQueryField(f, options, choices(f.id))
      if (allowedValues(f.id) != null) {
        field.type = 'select'
        field.operators = ['eq', 'neq', 'in']
        field.options = choices(f.id)
      }
      if (fieldRelation(model.value?.object.relations, f.id) || (source && source.kind !== 'LOCAL_OPTIONS')) {
        field.type = 'select'
        field.operators = ['eq', 'neq', 'in']
        field.valueComponent = markRaw(SelectionField)
        field.valueProps = {
          applicationId: props.applicationId,
          objectId: props.objectId,
          fieldId: f.id,
          allowedValues: allowedValues(f.id),
          compact: true,
          placeholder: '请选择'
        }
      }
      return field
    })
)
const canDelete = (row: BusinessRow) =>
  !!row.id &&
  !!row.revision &&
  !row.parentId &&
  !!model.value?.writable &&
  showButton(ViewButton.DELETE) &&
  !!row.permissions?.actions.includes(BusinessAction.DELETE)
const canBatchDelete = computed(
  () =>
    viewModel.value?.composition?.grain !== 'DETAIL' &&
    !!props.view?.list?.batchDelete &&
    !!model.value?.writable &&
    showButton(ViewButton.DELETE) &&
    !!model.value.permissions.actions.includes(BusinessAction.DELETE)
)
const rowSelection = computed(() =>
  canBatchDelete.value
    ? {
        preserveSelectedRowKeys: false,
        getCheckboxProps: (row: BusinessRow) => ({ disabled: batchBusy.value || !canDelete(row) })
      }
    : false
)
/**
 * 视图开了「内容超出列宽时自动截断」：列宽严格按配置，业务列统一单行省略、悬停看全文。
 * 没开时显示与这个配置出现之前完全一样（多行文本、富文本换行且最高约 5 行，其余单行、列随内容变宽）。
 */
const truncate = computed(() => props.view?.list?.overflow === ListOverflow.ELLIPSIS)
// 业务列仅来自视图字段配置；记录主键只用于行标识和操作，不额外追加显示列。
const columns = computed(() => [
  ...visibleFields.value.map(f => ({
    title: f.name,
    key: f.id,
    width: props.view?.list?.columnWidths[f.id] || 170,
    ellipsis: truncate.value || ![FieldType.TEXTAREA, FieldType.RICH_TEXT].some(type => type === f.type),
    sorter: f.type !== FieldType.SUMMARY && f.type !== FieldType.URL && queryReady(f.id) && !isRelationFieldId(f.id),
    sortOrder: sortFieldId.value === f.id ? (descending.value ? ('descend' as const) : ('ascend' as const)) : null
  })),
  { title: '操作', key: 'actions', width: actions.value.length ? 360 : 250, fixed: 'right' as const }
])
/**
 * 整页列表每页不超过这么多条时，整页的行都露出来、表格内部不出纵向滚动（窗口放不下由页面滚动）；
 * 超过时照旧占满窗口、多出的行在表格内部滚动。按每页条数而不是当前行数判断：数据增减不会让版式来回切换。
 */
const FIT_PAGE_SIZE = 10
const fitRows = computed(() => !!props.standalone && pageSize.value <= FIT_PAGE_SIZE)
const pagination = computed(() => ({
  current: pageNo.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: pageSizeOptions(props.view?.pageSize || DEFAULT_PAGE_SIZE),
  showTotal: (n: number) => `共 ${n} 条`
}))
function display(row: BusinessRow, key: string) {
  const field = visibleFields.value.find(f => f.id === key)
  const state = calculationStates.value[key]
  if (state !== 'READY' && storedOrderedCalculation(displayOptions.value[key])) return orderedStateLabel(state)
  if (field?.type === FieldType.RICH_TEXT) return richTextSummary(row.values[key])
  if (
    field?.type === FieldType.MONEY ||
    ([FieldType.FORMULA, FieldType.SUMMARY].some(type => type === field?.type) &&
      displayOptions.value[key]?.resultType === FieldType.MONEY)
  )
    return recordDisplay(row, key, displayOptions.value[key], field)
  if (row.displayValues && key in row.displayValues) return row.displayValues[key] || '—'
  const value = row.values[key]
  if (value === null || value === undefined) return '—'
  const fieldLabels = labels.value[key]
  if (fieldLabels) return fieldLabels[String(value)] || '已失效或无权查看'
  return recordDisplay(row, key, displayOptions.value[key], field)
}
// quiet 只认 true（按钮和 watch 会把事件、新旧值当第一个参数传进来）。
// 静默重取：不出 loading、不清选中；行和总数都没变就不动界面。有可见的加载在跑时让它先跑完。
async function loadRows(quiet?: unknown) {
  if (!model.value) return
  if (quiet === true && loading.value) return
  const current = ++rowGeneration
  const currentModel = model.value,
    appId = props.applicationId
  if (quiet !== true) {
    loading.value = true
    error.value = ''
    selectedKeys.value = []
  }
  try {
    // 静默重取失败不弹全局错误通知；用户主动的加载（刷新、翻页、搜索）照旧弹。
    const fetchPage = (query: Parameters<typeof api.page>[0]) =>
      quiet === true ? api.page(query, { quiet: true }) : api.page(query)
    const result = await fetchPage({
      applicationId: props.applicationId,
      objectId: props.objectId,
      viewId: props.viewId,
      context: props.context,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      ...appliedQuery.value,
      childFilters: childFilters.value,
      sortFieldId: sortFieldId.value,
      descending: descending.value,
      ...(props.reportDrill ? { reportDrill: props.reportDrill } : {}),
      ...(props.dashboardDrill ? { dashboardDrill: props.dashboardDrill } : {})
    })
    if (current !== rowGeneration) return
    if (quiet === true) {
      if (result.total === total.value && JSON.stringify(result.list) === JSON.stringify(rows.value)) return
      // 已经不在这一页的行从选中里去掉，其余选中保留。
      selectedKeys.value = selectedKeys.value.filter(key => result.list.some(row => row.id === key))
    }
    rows.value = result.list
    total.value = result.total
    drillTotal.value = props.reportDrill || props.dashboardDrill ? (result.drillTotal ?? null) : null
    drillDifferentGrain.value = !!props.dashboardDrill && !!result.drillDifferentGrain
    // 名称随当前页重读，不跨应用或撤权缓存关联业务数据。
    const resolved: Record<string, Record<string, string>> = {}
    const tasks: Array<() => Promise<void>> = []
    const visible = visibleFields.value
    for (const kind of directoryTypes) {
      const selected = visible.filter(
        f => f.type === kind && result.list.some(row => row.values[f.id] != null && row.displayValues?.[f.id] == null)
      )
      if (!selected.length) continue
      selected.forEach(f => {
        resolved[f.id] = {}
      })
      const ids = [
        ...new Set(
          result.list.flatMap(row =>
            selected
              .map(f => row.values[f.id])
              .filter(v => v != null)
              .map(String)
          )
        )
      ]
      if (ids.length)
        tasks.push(async () => {
          const options = Object.fromEntries((await directoryOptions(kind, ids)).map(o => [o.value, o.label]))
          selected.forEach(f => {
            resolved[f.id] = options
          })
        })
    }
    for (const relation of currentModel.object.relations) {
      if (!relation.fieldId || !visible.some(f => f.id === relation.fieldId)) continue
      const fieldId = relation.fieldId
      const relationLabels: Record<string, string> = {}
      resolved[fieldId] = relationLabels
      const ids = [
        ...new Set(
          result.list
            .filter(row => row.displayValues?.[fieldId] == null)
            .map(row => row.values[fieldId])
            .filter(v => v != null)
            .map(String)
        )
      ]
      let target: Promise<RecordModel> | undefined
      for (const id of ids)
        tasks.push(async () => {
          const targetModel = await (target ||= api.model(appId, relation.targetObjectId))
          const row = await api.get(appId, relation.targetObjectId, id)
          relationLabels[id] = recordTitle(targetModel.object, row.record.values)
        })
    }
    // 静默重取时旧名称先留着，等新名称取齐再整体替换，避免闪一下「已失效」。
    if (quiet !== true) labels.value = resolved
    // 有界并发；一个目录失效不能使业务记录列表消失。
    for (let offset = 0; offset < tasks.length; offset += 8)
      await Promise.allSettled(tasks.slice(offset, offset + 8).map(task => task()))
    if (current === rowGeneration) labels.value = { ...resolved }
  } catch (e) {
    if (current !== rowGeneration) return
    // 静默重取失败不清空已有内容。
    if (quiet === true) return
    rows.value = []
    total.value = 0
    drillTotal.value = null
    drillDifferentGrain.value = false
    error.value = errorMessage(e)
    emit('fault', e)
  } finally {
    if (current === rowGeneration) loading.value = false
  }
}
async function load() {
  recordOpenGeneration++
  const current = ++rowGeneration
  // 对象能力和记录数据是一次完整加载，避免元数据请求期间闪现空列表。
  loading.value = true
  model.value = undefined
  viewModel.value = undefined
  childFilters.value = []
  expanded.value = []
  rows.value = []
  labels.value = {}
  error.value = ''
  pageNo.value = 1
  pageSize.value = props.view?.pageSize || DEFAULT_PAGE_SIZE
  queryValues.value = JSON.parse(JSON.stringify(props.view?.query?.defaults || {}))
  advancedConditions.value = null
  appliedQuery.value = { search: '', equal: {}, conditions: null }
  sortFieldId.value = props.view?.sortFieldId || undefined
  descending.value = props.view?.descending ?? true
  batchResult.value = ''
  search.value = ''
  drillTotal.value = null
  drillDifferentGrain.value = false
  try {
    const next = await api.model(props.applicationId, props.objectId)
    const composition =
      props.view?.composition && props.viewId
        ? await api.viewModel(props.applicationId, props.objectId, props.viewId)
        : undefined
    if (current !== rowGeneration) return
    model.value = next
    viewModel.value = composition
    const initial = basicQuery(
      queryFields.value,
      queryValues.value,
      new Set(Object.keys(props.view?.filterDictionaries || {}))
    )
    appliedQuery.value = { search: '', equal: initial.equal, conditions: initial.conditions }
    await loadRows()
    if (props.initialRecordId) await showDetail({ id: props.initialRecordId, revision: null, values: {} })
  } catch (e) {
    if (current !== rowGeneration) return
    error.value = errorMessage(e)
    loading.value = false
  }
}
async function edit(row?: BusinessRow) {
  const id = row?.parentId || row?.id
  if (row && !id) {
    error.value = '记录尚未保存，无法编辑现有记录'
    return
  }
  const current = ++recordOpenGeneration
  try {
    if (formResolution.value.error) throw new Error(formResolution.value.error)
    const record = id ? await api.get(props.applicationId, props.objectId, id) : undefined
    if (current !== recordOpenGeneration) return
    editing.value = record
    editorOpen.value = true
  } catch (e) {
    if (current === recordOpenGeneration) error.value = errorMessage(e)
  }
}
// 任务入口场景的提交带着任务上下文，不做下面的自动重试。
const taskEntry = inject(taskEntrySessionKey, undefined)
/**
 * 删除遇到「记录已被修改」（别人刚改过）：取最新修订号再删一次，后操作的生效；只重试一次。
 * 取最新时发现记录已经不存在：别人先删了，按删除成功处理。
 */
async function deleteRecord(body: { applicationId: string; objectId: string; id: string; expectedRevision: string }) {
  if (!REBASE_DELETE || taskEntry) return api.delete(body)
  try {
    return await api.delete(body)
  } catch (conflict) {
    if (!isRecordConflict(conflict)) throw conflict
    let latest: Aggregate
    try {
      latest = await api.get(body.applicationId, body.objectId, body.id, { quiet: true })
    } catch (e) {
      if (isRecordMissing(e)) return true
      throw conflict
    }
    if (!latest.record.revision) throw conflict
    return api.delete({ ...body, expectedRevision: latest.record.revision })
  }
}
async function remove(row: BusinessRow) {
  if (!row.id || !row.revision) return
  try {
    await deleteRecord({
      applicationId: props.applicationId,
      objectId: props.objectId,
      id: row.id,
      expectedRevision: row.revision
    })
    message.success('记录已删除')
    refresh.value++
    emit('changed')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function changePage(
  p: { current?: number; pageSize?: number },
  _filters: unknown,
  sorter: SorterResult<BusinessRow> | SorterResult<BusinessRow>[]
) {
  // 默认排序仅用于初始化。保留用户取消排序的状态，使下一次点击能进入升序。
  const sorting = Array.isArray(sorter) ? sorter[0] : sorter
  const nextSortField = sorting?.order && sorting.columnKey != null ? String(sorting.columnKey) : undefined
  // 没有排序列时表格不报方向：方向取视图的默认值（与打开时一致），不能当成升序——否则第一次翻页会被当成「排序变了」而回到第一页。
  const nextDescending = nextSortField ? sorting?.order === 'descend' : (props.view?.descending ?? true)
  const sortingChanged = nextSortField !== sortFieldId.value || nextDescending !== descending.value
  pageNo.value = p.pageSize !== pageSize.value || sortingChanged ? 1 : p.current || 1
  pageSize.value = p.pageSize || DEFAULT_PAGE_SIZE
  sortFieldId.value = nextSortField
  descending.value = nextDescending
  void loadRows()
}
function query() {
  try {
    const basic = basicQuery(
      queryFields.value,
      queryValues.value,
      new Set(Object.keys(props.view?.filterDictionaries || {}))
    )
    appliedQuery.value = {
      search: search.value.trim(),
      equal: basic.equal,
      conditions: combineConditions(basic.conditions, normalizeAdvancedQuery(advancedConditions.value, fields.value))
    }
    pageNo.value = 1
    void loadRows()
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function resetQuery() {
  search.value = ''
  queryValues.value = {}
  table.value?.clearAdvancedSearch()
  advancedConditions.value = null
  query()
}
async function confirmRemove(row: BusinessRow) {
  if (
    props.confirmAction &&
    (await props.confirmAction(
      '删除业务记录？',
      '将删除该记录和内部明细；引用、级联和权限规则仍由服务端校验。',
      '删除'
    ))
  )
    await remove(row)
}
async function batchDelete(keys: (string | number)[]) {
  if (
    props.confirmAction &&
    !(await props.confirmAction(
      '批量删除业务记录？',
      `已选择 ${keys.length} 条，按当前关系和权限规则逐条删除并反馈结果。`,
      '删除'
    ))
  )
    return
  if (batchBusy.value || !canBatchDelete.value) return
  const selected = rows.value.filter(row => row.id != null && keys.includes(row.id) && canDelete(row))
  if (!selected.length) return
  const applicationId = props.applicationId
  const objectId = props.objectId
  batchBusy.value = true
  try {
    const result = await deleteRecordBatch(selected, row => {
      const { id, revision } = row
      if (!id || !revision) throw new Error('记录缺少主键或修订号，请刷新后重试')
      return deleteRecord({
        applicationId,
        objectId,
        id,
        expectedRevision: revision
      })
    })
    const succeeded = result.succeeded
    const failures = result.failures.map(f => `记录 ${f.id}：${errorMessage(f.error)}`)
    if (applicationId !== props.applicationId || objectId !== props.objectId) return
    batchResult.value = `已删除 ${succeeded} 条，失败 ${failures.length} 条${failures.length ? '。' + failures.join('；') : ''}`
    selectedKeys.value = []
    if (succeeded && selected.length === rows.value.length && !failures.length)
      pageNo.value = Math.max(1, pageNo.value - 1)
    refresh.value++
    if (succeeded) emit('changed')
  } finally {
    batchBusy.value = false
  }
}
function saved() {
  editorOpen.value = false
  refresh.value++
  emit('changed')
}
const importing = ref(false),
  importOpen = ref(false),
  importFile = ref<File>(),
  transferBusy = ref(false)
const canImport = computed(
  () =>
    canCreateRelated.value &&
    model.value?.writable &&
    model.value.permissions.actions.includes(BusinessAction.IMPORT) &&
    model.value.permissions.actions.includes(BusinessAction.CREATE)
)
async function download(blob: Blob, filename: string) {
  if (blob.type.includes('json')) {
    const result = JSON.parse(await blob.text())
    throw new Error(result.msg || result.message || '下载失败')
  }
  triggerDownload(blob, filename)
}
async function template() {
  try {
    await download(await api.template(props.applicationId, props.objectId), '业务导入模板.xlsx')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function exportRows() {
  if (transferBusy.value) return
  transferBusy.value = true
  try {
    const blob = await api.export({
      applicationId: props.applicationId,
      objectId: props.objectId,
      viewId: props.viewId,
      context: props.context,
      pageNo: 1,
      pageSize: 100,
      ...appliedQuery.value,
      childFilters: childFilters.value,
      sortFieldId: sortFieldId.value,
      descending: descending.value
    })
    await download(blob, (model.value?.object.objectName || '业务记录') + '.xlsx')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    transferBusy.value = false
  }
}
function selectImport(file: File) {
  error.value = ''
  importFile.value = file
  return false
}
async function runImport() {
  if (!importFile.value) return
  error.value = ''
  importing.value = true
  try {
    const count = await api.import(props.applicationId, props.objectId, importFile.value, props.context)
    message.success('已导入 ' + count + ' 条记录')
    importOpen.value = false
    importFile.value = undefined
    refresh.value++
    emit('changed')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    importing.value = false
  }
}
function availableActions(row: BusinessRow) {
  if (readOnly.value) return []
  return actions.value.filter(a =>
    row.permissions?.actions.includes(
      a.config.kind === BusinessActionKind.START_PROCESS ? BusinessAction.START_PROCESS : BusinessAction.UPDATE
    )
  )
}
async function executeAction(action: ApplicationResource, row: BusinessRow) {
  if (!row.id || !row.revision) return
  try {
    await api.action({
      applicationId: props.applicationId,
      objectId: props.objectId,
      actionId: action.id,
      recordId: row.parentId || row.id,
      expectedRevision: row.revision
    })
    message.success('业务动作已执行')
    refresh.value++
    emit('changed')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
watch(() => [refresh.value, props.refreshKey], loadRows)
/** 打开着的只读详情（未配详情页时）跟着静默重取；配了详情页的由页面里的区块各自重取。 */
async function refreshOpenDetail() {
  const id = detailOpen.value && !detailResource.value ? detailRecord.value?.record.id : undefined
  if (!id) return
  if (detailDeleted.value) return
  const current = recordOpenGeneration
  const record = await api.get(props.applicationId, props.objectId, id, { quiet: true })
  if (current !== recordOpenGeneration || !detailOpen.value) return
  if (JSON.stringify(record) !== JSON.stringify(detailRecord.value)) detailRecord.value = record
}
useRuntimeDataRefresh({
  interest: () =>
    model.value
      ? {
          applicationId: props.applicationId,
          objectIds: [
            props.objectId,
            ...Object.values(viewModel.value?.sections || {}).flatMap(section =>
              section.recordModel ? [section.recordModel.object.objectId] : []
            )
          ]
        }
      : undefined,
  refresh: async () => {
    await Promise.all([loadRows(true), refreshOpenDetail().catch(detailGone)])
  }
})
/** 静默重取打开着的详情时发现记录已经不存在（被别人删了）：详情不关，标出来。 */
function detailGone(e: unknown) {
  if (!isRecordMissing(e)) throw e
  if (detailOpen.value) detailDeleted.value = true
}
/** 有可见的加载在跑：它发出时数据可能还是旧的，等它跑完再静默补取。 */
function settled() {
  if (!loading.value) return Promise.resolve()
  return new Promise<void>(resolve => {
    const stop = watch(loading, busy => {
      if (busy) return
      stop()
      resolve()
    })
  })
}
/**
 * 打开着的只读详情：推送的变更涉及它时静默重取；它被删了就留着内容，顶上标出「这条记录已被删除」。
 * 配了详情页的由页面里的区块各自重取，这里只确认记录还在。编辑抽屉不在此列：正在编辑的记录不因推送被替换。
 */
async function refreshLiveDetail(change: LiveChange) {
  const id = detailOpen.value ? detailRecord.value?.record.id : undefined
  if (!id || detailDeleted.value || !liveChangeTouches(change, id)) return
  if (change.deleted.has(id)) {
    detailDeleted.value = true
    return
  }
  const current = recordOpenGeneration
  const open = () => current === recordOpenGeneration && detailOpen.value
  try {
    const record = await api.get(props.applicationId, props.objectId, id, { quiet: true })
    if (!open() || detailResource.value) return
    if (JSON.stringify(record) !== JSON.stringify(detailRecord.value)) detailRecord.value = record
  } catch (e) {
    if (open() && isRecordMissing(e)) detailDeleted.value = true
  }
}
// 别人改了这个对象的记录：软刷新当前页（不转圈、不清勾选、不动页码筛选排序、不关抽屉）。
const live = useRecordLive({
  applicationId: () => props.applicationId,
  objectIds: () => [props.objectId],
  reload: async change => {
    await settled()
    await Promise.all([loadRows(true), refreshLiveDetail(change)])
  }
})
/** 刚被别人新增或修改、且仍在本页的行：淡色提示约 2 秒。明细粒度的行按所属主记录算。 */
function liveRow(row: BusinessRow) {
  const id = row.parentId || row.id
  return id && live.touched.value.has(id) ? { class: 'business-records__row--live' } : {}
}
// 同一抽屉内切换下钻格时回到第一页重查，保留视图自身的查询状态。
watch(
  () => [props.reportDrill, props.dashboardDrill],
  () => {
    pageNo.value = 1
    void loadRows()
  },
  { deep: true }
)
// 父页面刷新会重建 context 对象；只有上下文的业务身份改变才重置筛选。
watch(
  [
    () => props.applicationId,
    () => props.objectId,
    () => props.viewId,
    () => props.context?.pageId,
    () => props.context?.recordId,
    () => props.context?.nodeId
  ],
  load,
  { immediate: true }
)
</script>
<template>
  <section
    class="business-records nocode-list-page"
    :class="{ 'business-records--standalone': standalone, 'business-records--fit': fitRows }"
  >
    <component
      :is="interactionDisplayMode === 'drawer' ? Drawer : Modal"
      v-model:open="importOpen"
      title="导入业务记录"
      ok-text="导入整批"
      :confirm-loading="importing"
      :ok-button-props="{ disabled: !importFile }"
      @ok="runImport"
      @close="importOpen = false"
    >
      <a-alert
        message="本次只新增主记录，不覆盖已有数据，也不导入内部明细。任一行失败整批回滚；最多 500 行、2 MB。"
        type="info"
        show-icon
        class="notice"
      />
      <p>
        先下载当前模板，保留列名。日期使用 YYYY-MM-DD，人员和关联填写记录编号，选项填写编码，多选填写 JSON
        数组；较长的编号请使用文本单元格。
      </p>
      <a-space>
        <a-button @click="template">下载模板</a-button>
        <a-upload accept=".xlsx,.xls" :before-upload="selectImport" :show-upload-list="false">
          <a-button>选择 Excel</a-button>
        </a-upload>
      </a-space>
      <a-alert
        type="info"
        show-icon
        message="Excel 导入仅支持主表字段，不导入内部明细和多选对象关系。包含必填多选关系时，请通过表单新建。导出文件包含关系 ID 和显示名称，不能直接作为导入模板。"
        style="margin-top: 12px"
      />
      <p v-if="importFile" style="margin-top: 12px">{{ importFile.name }}</p>
      <a-alert v-if="error" :message="error" type="error" show-icon class="notice" />
    </component>
    <a-alert
      v-if="drillDifferentGrain && drillTotal != null"
      type="info"
      show-icon
      class="notice"
      data-drill-grain
      :message="
        '看板范围命中 ' + drillTotal + ' 条主记录；当前业务视图按明细行展示，共 ' + total + ' 条，统计粒度不同。'
      "
    />
    <a-alert
      v-if="drillExcluded"
      type="info"
      show-icon
      class="notice"
      data-drill-excluded
      :message="'业务视图条件及当前搜索或筛选排除了 ' + drillExcluded + ' 条（下钻范围共 ' + drillTotal + ' 条）'"
    />
    <a-alert v-if="error" :message="error" type="error" show-icon class="notice" />
    <a-alert v-if="formResolution.error" :message="formResolution.error" type="error" show-icon class="notice" />
    <a-alert
      v-if="batchResult"
      :message="batchResult"
      type="info"
      show-icon
      closable
      class="notice"
      @close="batchResult = ''"
    />
    <a-alert v-if="model && !model.writable" type="info" message="此对象当前只读，业务数据可查询。" class="notice" />
    <a-alert
      v-if="model && !loading && !visibleFields.length"
      type="warning"
      show-icon
      message="当前列表没有可显示的业务字段"
      description="请联系应用管理员检查数据视图的显示字段，以及当前应用、成员和任务的数据权限。这与“暂无业务记录”不同。"
      class="notice"
    />
    <a-alert
      v-if="viewModel?.composition?.grain === 'DETAIL'"
      type="info"
      show-icon
      message="当前一行代表一条明细。查看或编辑将打开所属主记录的整单；明细在整单中增删。"
      class="notice"
    />
    <a-space v-if="childFilters.length" wrap class="notice">
      <a-tag
        v-for="filter in childFilters"
        :key="filter.sectionId"
        closable
        @close.prevent="removeChildFilter(filter.sectionId)"
      >
        {{ viewModel?.composition?.sections.find(s => s.id === filter.sectionId)?.name }}：{{
          filter.requireMatch ? '同时筛选主记录' : '仅筛选子表和汇总'
        }}
      </a-tag>
    </a-space>
    <a-spin v-if="!model && loading" class="initial-loading" />
    <OsTablePage
      v-if="model"
      v-show="listShown"
      :key="preferenceKey"
      ref="table"
      :title="title || model.object.objectName + '列表'"
      :columns="columns"
      :data-source="rows"
      row-key="id"
      :loading="loading || batchBusy"
      :pagination="pagination"
      :scroll="{ x: 'max-content', y: standalone ? '100%' : undefined }"
      :fixed-layout="truncate"
      show-column-settings
      :column-settings-key="preferenceKey"
      resizable
      :show-advanced-search="advancedFields.length > 0"
      advanced-search-mode="dynamic"
      :dynamic-search-fields="advancedFields"
      :search-storage-key="preferenceKey + ':search'"
      search-placement="header"
      :search-target="listShown ? searchTarget : null"
      :show-import="showButton(ViewButton.IMPORT) && canImport"
      :show-export="showButton(ViewButton.EXPORT) && model.permissions.actions.includes(BusinessAction.EXPORT)"
      show-download-template
      import-accept=".xlsx,.xls"
      :row-selection="rowSelection"
      :selected-row-keys="selectedKeys"
      :expanded-row-keys="expanded"
      :custom-row="liveRow"
      @expand="
        (open, record) => {
          expanded = open ? [...expanded, record.id] : expanded.filter(id => id !== record.id)
        }
      "
      :show-batch-bar="canBatchDelete && !batchBusy"
      batch-delete-confirm-content="确定删除选中的 {count} 条记录及其内部明细？按已配置关系规则，相关独立明细也可能被级联删除，引用可能被清空；限制删除的引用会阻止操作。将逐条反馈结果。"
      @change="changePage"
      @search="query"
      @dynamic-search="advancedConditions = $event"
      @selection-change="keys => (selectedKeys = keys)"
      :batch-delete-confirm="!confirmAction"
      :dynamic-search-display-mode="interactionDisplayMode"
      @batch-delete="batchDelete"
      @import="
        file => {
          selectImport(file)
          importOpen = true
        }
      "
      @export="exportRows"
      @download-template="template"
    >
      <template v-if="hasChildren && viewModel && viewId" #expandedRowRender="{ record }">
        <DataViewChildren
          :application-id="applicationId"
          :object-id="objectId"
          :view-id="viewId"
          :parent="record"
          :model="viewModel"
          :filters="childFilters"
          :resources="resources"
          :inherit-default-form="inheritDefaultForm"
          :refresh-key="refresh"
          @filter="applyChildFilter"
          @edit-parent="edit(record)"
          @saved="
            () => {
              refresh++
              emit('changed')
            }
          "
        />
      </template>
      <template #search="{ triggerSearch }">
        <a-form layout="inline" @submit.prevent>
          <a-form-item label="关键词">
            <a-input
              v-model:value="search"
              allow-clear
              placeholder="搜索文本内容"
              aria-label="搜索业务记录"
              class="nocode-filter-input"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item v-for="field in queryParts.inline" :key="field.id" :label="field.name">
            <div class="nocode-filter-input">
              <RecordQueryField
                v-model="queryValues[field.id!]"
                :field="field"
                :reference="!!fieldRelation(model?.object.relations, field.id)"
                :application-id="applicationId"
                :object-id="objectId"
                :options="model.object.fieldOptions[field.id!]"
                :choices="choices(field.id)"
                :allowed-values="allowedValues(field.id)"
                relative-dates
                @search="triggerSearch"
              />
            </div>
          </a-form-item>
          <a-form-item v-if="queryParts.more.length">
            <a-button
              type="link"
              class="business-records__more"
              :aria-expanded="moreOpen"
              @click="moreOpen = !moreOpen"
            >
              {{ moreOpen ? '收起条件' : `更多条件（${queryParts.more.length}）` }}
              <template v-if="!moreOpen && moreFilled">· 已填 {{ moreFilled }}</template>
            </a-button>
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" :disabled="batchBusy" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button :disabled="batchBusy" @click="resetQuery">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>
      <template v-if="moreOpen && queryParts.more.length" #searchMore="{ triggerSearch }">
        <a-form layout="inline" class="business-records__more-fields" @submit.prevent>
          <a-form-item v-for="field in queryParts.more" :key="field.id" :label="field.name">
            <div class="nocode-filter-input">
              <RecordQueryField
                v-model="queryValues[field.id!]"
                :field="field"
                :reference="!!fieldRelation(model?.object.relations, field.id)"
                :application-id="applicationId"
                :object-id="objectId"
                :options="model.object.fieldOptions[field.id!]"
                :choices="choices(field.id)"
                :allowed-values="allowedValues(field.id)"
                relative-dates
                @search="triggerSearch"
              />
            </div>
          </a-form-item>
        </a-form>
      </template>
      <template #toolbar>
        <a-button :loading="loading" :disabled="batchBusy" @click="loadRows">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #actions>
        <a-button
          v-if="
            showButton(ViewButton.CREATE) &&
            canCreateRelated &&
            model.writable &&
            model.permissions.actions.includes(BusinessAction.CREATE)
          "
          type="primary"
          :disabled="batchBusy"
          @click="edit()"
        >
          <PlusOutlined />
          新增
        </a-button>
      </template>
      <!-- 列头：系统自动更新的列在列名后加标识；其它列照常只显示列名。 -->
      <template #columnTitle="{ column }">
        <span>{{ column.title }}</span>
        <a-tooltip v-if="autoUpdateColumn(column.key)" :title="AUTO_UPDATE_TEXT">
          <AutoUpdateMark />
        </a-tooltip>
      </template>
      <template #bodyCell="{ column, record }">
        <div v-if="column.key === 'actions'" class="nocode-table-actions">
          <a-button v-if="showButton(ViewButton.VIEW)" type="link" @click="showDetail(record)">
            <EyeOutlined />
            查看
          </a-button>
          <a-button
            v-if="
              showButton(ViewButton.UPDATE) &&
              model.writable &&
              record.permissions?.actions.includes(BusinessAction.UPDATE)
            "
            type="link"
            :disabled="batchBusy"
            @click="edit(record)"
          >
            <EditOutlined />
            编辑
          </a-button>
          <a-popconfirm v-if="canDelete(record)" :disabled="!!confirmAction" @confirm="remove(record)">
            <template #title>
              <div style="max-width: 360px">
                删除此记录及其内部明细？按已配置关系规则，相关独立明细也可能被级联删除，引用可能被清空；限制删除的引用会阻止操作。
              </div>
            </template>
            <a-button type="link" danger :disabled="batchBusy" @click="confirmAction && confirmRemove(record)">
              <DeleteOutlined />
              删除
            </a-button>
          </a-popconfirm>
          <template v-if="model.writable || record.permissions?.actions.includes(BusinessAction.START_PROCESS)">
            <a-popconfirm
              v-for="action in availableActions(record).slice(0, 1)"
              :key="action.id"
              :title="'执行“' + action.name + '”？'"
              :disabled="!!confirmAction"
              @confirm="executeAction(action, record)"
            >
              <a-button
                type="link"
                :disabled="batchBusy"
                @click="
                  async () => {
                    if (confirmAction && (await confirmAction('执行' + action.name + '？')))
                      await executeAction(action, record)
                  }
                "
              >
                <PlayCircleOutlined />
                {{ action.name }}
              </a-button>
            </a-popconfirm>
            <a-dropdown v-if="availableActions(record).length > 1">
              <a-button type="link" :disabled="batchBusy">更多</a-button>
              <template #overlay>
                <a-menu>
                  <a-menu-item v-for="action in availableActions(record).slice(1)" :key="action.id">
                    <a-popconfirm
                      :disabled="!!confirmAction"
                      :title="'执行“' + action.name + '”？'"
                      @confirm="executeAction(action, record)"
                    >
                      <span
                        @click="
                          async () => {
                            if (confirmAction && (await confirmAction('执行' + action.name + '？')))
                              await executeAction(action, record)
                          }
                        "
                      >
                        {{ action.name }}
                      </span>
                    </a-popconfirm>
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </template>
        </div>
        <HyperlinkField
          v-else-if="visibleFields.some(f => f.id === column.key && f.type === FieldType.URL)"
          :model-value="record.values[column.key]"
          read-only
        />
        <BusinessFileField
          v-else-if="
            visibleFields.some(
              f => f.id === column.key && [FieldType.IMAGE, FieldType.ATTACHMENT].some(t => t === f.type)
            )
          "
          :model-value="record.values[column.key] || []"
          :image="visibleFields.some(f => f.id === column.key && f.type === FieldType.IMAGE)"
          :application-id="applicationId"
          :object-id="objectId"
          :record-id="record.parentId || record.id || undefined"
          :field-id="String(column.key)"
          :business-policy="model.object.settings.businessFilePolicy || null"
          disabled
        />
        <!-- 自动截断时富文本按摘要文字单行显示（走下面的通用分支）。 -->
        <RichTextDisplay
          v-else-if="!truncate && visibleFields.some(f => f.id === column.key && f.type === FieldType.RICH_TEXT)"
          :value="record.values[column.key]"
          compact
        />
        <template v-else-if="column.key !== '_index'">
          <a
            v-if="showButton(ViewButton.VIEW) && column.key === model.object.titleFieldId"
            :title="display(record, column.key)"
            :class="{
              'nocode-table-multiline':
                !truncate && visibleFields.some(f => f.id === column.key && f.type === FieldType.TEXTAREA)
            }"
            @click="showDetail(record)"
          >
            {{ display(record, column.key) }}
          </a>
          <span
            v-else
            :title="display(record, column.key)"
            :class="{
              'nocode-table-multiline':
                !truncate && visibleFields.some(f => f.id === column.key && f.type === FieldType.TEXTAREA)
            }"
          >
            {{ display(record, column.key) }}
          </span>
        </template>
      </template>
    </OsTablePage>
    <RecordSurface
      v-model:open="editorOpen"
      :inline="inlineEditing"
      :title="(editing ? '编辑记录' : '新建记录') + ' · ' + (model?.object.objectName || '')"
      :mode="view?.interaction?.editMode || RecordOpenMode.DRAWER"
    >
      <RecordEditor
        :confirm-leave="confirmLeave"
        :interaction-display-mode="interactionDisplayMode"
        v-if="model && !formResolution.error"
        :application-id="applicationId"
        :model="model"
        :record="editing"
        :form="effectiveForm"
        :form-id="effectiveFormId"
        :context="context"
        :locked-values="lockedValues"
        merge-on-conflict
        @saved="saved"
        @cancel="editorOpen = false"
      />
    </RecordSurface>
    <RecordSurface
      v-model:open="detailOpen"
      :inline="inlineEditing"
      :title="model && detailRecord ? recordTitle(model.object, detailRecord.record.values) : '记录详情'"
      :mode="view?.interaction?.detailMode || RecordOpenMode.DRAWER"
    >
      <a-alert
        v-if="detailDeleted"
        type="warning"
        show-icon
        class="notice"
        message="这条记录已被删除"
        data-record-deleted
      />
      <PageRenderer
        v-if="detailConfig && detailResource && detailRecord"
        :nodes="detailConfig.nodes"
        :application-id="applicationId"
        :resources="resources || []"
        :page-id="detailResource.id"
        :record-id="detailRecord.record.id!"
        :read-only="detailDeleted"
      />
      <RecordEditor
        :confirm-leave="confirmLeave"
        :interaction-display-mode="interactionDisplayMode"
        v-else-if="model && detailRecord && !formResolution.error"
        :application-id="applicationId"
        :model="model"
        :record="detailRecord"
        :form="effectiveForm"
        :form-id="effectiveFormId"
        read-only
        hide-footer
      />
    </RecordSurface>
  </section>
</template>
<style scoped>
.business-records {
  height: auto;
  min-width: 0;
  /* 列表表格的表头、单元格字号跟透视表一致（全局表格样式读这两个变量；展开的子表在本节点之内，一并生效）。 */
  --table-header-font-size: var(--data-table-font-size);
  --table-body-font-size: var(--data-table-font-size);
}
/* 字体、字号：行高、内边距、表头字重同样取透视表那一组值；颜色、背景、对齐方式不在这里改。 */
.business-records :deep(.ant-table-wrapper .ant-table .ant-table-thead > tr > th),
.business-records :deep(.ant-table-wrapper .ant-table .ant-table-tbody > tr > td) {
  padding: var(--data-table-cell-padding);
  line-height: var(--data-table-line-height);
}
.business-records :deep(.ant-table-wrapper .ant-table .ant-table-thead > tr > th) {
  font-weight: var(--data-table-header-font-weight);
}
/* 字体、字号结束 */
.business-records > .os-table-page {
  flex: none;
  height: auto;
  min-height: 0;
}
.business-records--standalone {
  flex: 1;
  height: 100%;
  min-height: 360px;
}
.business-records--standalone > .os-table-page {
  flex: 1;
  min-height: 320px;
}
/* 每页条数少：高度至少放得下整页的行（内容自身的高度），窗口更高时照旧占满。 */
.business-records--standalone.business-records--fit,
.business-records--standalone.business-records--fit > .os-table-page {
  min-height: max-content;
}
.notice {
  margin-bottom: 12px;
}
.initial-loading {
  padding: 60px;
}
.business-records :deep(.os-table-page__table .ant-card-head-wrapper) {
  flex-wrap: wrap;
  gap: 8px;
}
.business-records :deep(.os-table-page__table .ant-card-extra) {
  margin-inline-start: auto;
}
/* 刚被别人改过的行：淡色闪一下，约 2 秒后恢复。 */
.business-records :deep(.business-records__row--live > td) {
  animation: business-records-live 2s ease-out;
}
@keyframes business-records-live {
  from {
    background-color: rgba(22, 119, 255, 0.16);
  }
  to {
    background-color: transparent;
  }
}
</style>
