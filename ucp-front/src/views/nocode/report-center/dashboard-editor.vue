<script setup lang="ts">
import { computed, nextTick, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  PlusOutlined,
  DownOutlined,
  UndoOutlined,
  RedoOutlined,
  SettingOutlined,
  FilterOutlined,
  QuestionCircleOutlined
} from '@ant-design/icons-vue'
import { getUserInfo } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  chartPreviewKey,
  chartDataErrors,
  chartVersionImpact,
  chartConfigurationGuide
} from '@/nocode/report-dashboard-editor'
import { formatDateTime } from '@/utils/format'
import { datasetKey } from '@/nocode/report-dataset-editor'
import { fieldTypes as objectFieldTypes } from '@/nocode/object-draft'
import { MAX_PIVOT_DIMENSIONS, pivotDimensionLimitMessage, operationOptions } from '@/nocode/report'
import {
  copyDashboard,
  dashboardDisplays,
  dashboardDisplayChange,
  duplicateDashboardChart,
  moveDashboardChart
} from '@/nocode/report-dashboard'
import type {
  DashboardChart,
  DashboardContent,
  DashboardDetail,
  DashboardDisplay,
  DashboardFilter,
  DashboardQuery
} from '@/types/nocode/report-dashboard'
import type { DatasetDetail, DatasetRelease, DatasetSource } from '@/types/nocode/report-center'
import type { ReportResult } from '@/types/nocode/report'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
import DashboardRuntime from './components/DashboardRuntime.vue'
import DashboardChartView from './components/DashboardChartView.vue'
import DashboardInteractionEditor from './components/DashboardInteractionEditor.vue'
import DashboardAuthorization from './components/DashboardAuthorization.vue'
import DashboardMenuSettings from './components/DashboardMenuSettings.vue'
import type { PlatformNavigationSettings } from '@/types/nocode/platform-navigation'
const platform = useNocodePlatform(),
  api = platform.reportCenter,
  route = useRoute(),
  router = useRouter()
const board = ref<DashboardDetail>(),
  draft = ref<DashboardContent>(),
  busy = ref(false),
  error = ref(''),
  results = ref<Record<string, ReportResult>>({}),
  chartErrors = ref<Record<string, string>>({})
const runtimePreview = ref(false)
const filtersExpanded = ref(false)
const visibleFilters = computed(() =>
  filtersExpanded.value ? draft.value?.filters || [] : (draft.value?.filters || []).slice(0, 3)
)
function draftQuery(query: DashboardQuery): DashboardQuery {
  const { versionNo: _version, checksum: _checksum, ...rest } = query
  return { ...rest, preview: true }
}
const previewTransport: DashboardRuntimeTransport = {
  load: async () => {
    const current = await api.dashboardGet(board.value!.id)
    return {
      dashboard: { id: current.id, versionNo: current.revision, checksum: current.checksum, content: current.draft }
    }
  },
  query: (query, signal) => api.dashboardQuery(draftQuery(query), signal),
  options: (query, signal) => api.dashboardOptions({ ...query, query: draftQuery(query.query) }, signal),
  details: (query, signal) => api.dashboardDetails({ ...query, query: draftQuery(query.query) }, signal),
  export: (query, signal) => api.dashboardExport(draftQuery(query), signal)
}
async function tryInteractions() {
  if (dirty.value && !(await save())) return
  runtimePreview.value = true
}
const authorizationOpen = ref(false)
const menuSettingsOpen = ref(false)
function applyMenuSettings(navigation: PlatformNavigationSettings) {
  if (draft.value && canEdit.value && !busy.value) commit({ ...copyDashboard(draft.value), navigation })
}
const settingsOpen = ref(false)
const settings = ref({ name: '', description: '' })
const settingsError = ref('')
const previewExpanded = ref(true)
const settingsChanged = computed(
  () => settings.value.name !== draft.value?.name || settings.value.description !== (draft.value?.description || '')
)
function openSettings() {
  if (!draft.value) return
  settings.value = { name: draft.value.name, description: draft.value.description || '' }
  settingsError.value = ''
  settingsOpen.value = true
}
async function closeSettings() {
  if (settingsChanged.value) {
    const discard = await new Promise<boolean>(resolve =>
      Modal.confirm({
        title: '放弃尚未应用的仪表板设置？',
        okText: '放弃修改',
        cancelText: '继续配置',
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      })
    )
    if (!discard) return
  }
  settingsOpen.value = false
}
function applySettings() {
  if (!draft.value || !canEdit.value || busy.value) return
  if (!settings.value.name.trim()) {
    settingsError.value = '请填写仪表板名称'
    return
  }
  commit({ ...copyDashboard(draft.value), name: settings.value.name.trim(), description: settings.value.description })
  settingsOpen.value = false
}
const canEdit = computed(() => board.value?.capabilities?.canEdit ?? platform.hasPermission('nocode:report:create'))
const canPublish = computed(
  () => board.value?.capabilities?.canPublish ?? platform.hasPermission('nocode:report:publish')
)
const canGrant = computed(() => board.value?.capabilities?.canGrant ?? false)
type Layout = Pick<DashboardChart, 'x' | 'y' | 'w' | 'h'>
const gridElement = ref<HTMLElement>(),
  resizePreview = ref<{ id: string; layout: Layout }>()
let resizeSession:
  | {
      id: string
      pointerId: number
      x: number
      y: number
      stepX: number
      stepY: number
      chart: DashboardChart
      draft: DashboardContent
    }
  | undefined
let dragOffset = { x: 0, y: 0 }
const undo = ref<DashboardContent[]>([]),
  redo = ref<DashboardContent[]>([])
const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== JSON.stringify(board.value?.draft))
const form = ref<DashboardChart>(),
  dialog = ref(false),
  formError = ref(''),
  datasets = ref<DatasetDetail[]>([]),
  releases = ref<DatasetRelease[]>([]),
  selectedDataset = ref(''),
  selectedVersion = ref<number>(),
  catalogBusy = ref(false),
  filterDialog = ref(false),
  referenceBusy = ref(false),
  referenceReset = ref(false),
  chartFields = ref<Record<string, DatasetSource['fields']>>({}),
  referenceErrors = ref<Record<string, string>>({})
const catalogError = ref('')
let retryCatalog: (() => Promise<void>) | undefined
const pendingVersion = ref<{ datasetId: string; versions: DatasetRelease[]; target: DatasetRelease }>()
const versionImpact = computed(() =>
  form.value && draft.value && pendingVersion.value
    ? chartVersionImpact(form.value, release.value, pendingVersion.value.target, draft.value, referenceReset.value)
    : undefined
)
const configTab = ref('data'),
  formInitial = ref(''),
  submitted = ref(false)
const formElement = ref<HTMLElement>()
const previewResult = ref<ReportResult>(),
  previewError = ref(''),
  previewBusy = ref(false)
const previewKey = ref('')
const resultKeys = ref<Record<string, string>>({})
const fieldTypes = ref<Record<string, string>>({})
let fieldTypeGeneration = 0,
  previewGeneration = 0,
  previewTimer: ReturnType<typeof setTimeout> | undefined
let previewController: AbortController | undefined
const validation = computed(() => (form.value ? chartDataErrors(form.value, release.value) : {}))
const previewCurrent = computed(() => !!form.value && previewKey.value === chartPreviewKey(form.value))
const formChanged = computed(() => !!form.value && JSON.stringify(form.value) !== formInitial.value)
const interactionSupported = computed(() => !!form.value && !['METRIC', 'PIVOT'].includes(form.value.display))
const sourceHint = computed(() =>
  catalogError.value
    ? '数据来源加载失败，可重试；当前配置尚未更换。'
    : catalogBusy.value
      ? '正在加载数据来源…'
      : !selectedDataset.value
        ? '选择数据集后，再选择维度与指标。仅可使用已发布且启用的数据集。'
        : !release.value
          ? '此数据集没有可用发布版本，请选择其他数据集或先完成发布。'
          : `固定使用 V${release.value.versionNo}，后续发布不会自动改变当前图表。`
)
const release = computed(() => releases.value.find(v => v.versionNo === selectedVersion.value))
const dimensions = computed(() => release.value?.definition.source?.fields.filter(f => f.role === 'DIMENSION') || [])
const metrics = computed(() => release.value?.definition.analysis?.metrics || [])
function datasetName(id: string, fallback = '数据集') {
  return datasets.value.find(item => item.id === id)?.draft.name || fallback
}
function dimensionOptions(column = false) {
  const selected = column ? form.value?.columnDimensions || [] : form.value?.dimensions || []
  const excluded = column ? form.value?.dimensions || [] : form.value?.columnDimensions || []
  const options = dimensions.value
    .filter(field => !excluded.some(item => item.fieldId === field.id))
    .map(field => ({
      value: field.id,
      label:
        field.name +
        (fieldTypes.value[field.id]
          ? ' · ' + (objectFieldTypes.find(type => type.value === fieldTypes.value[field.id])?.label || '字段')
          : ''),
      disabled: false
    }))
  for (const item of selected)
    if (!options.some(option => option.value === item.fieldId))
      options.push({
        value: item.fieldId,
        label: `${release.value ? '失效字段' : '待核实字段'}（${item.fieldId}）`,
        disabled: true
      })
  return options
}
const metricOptions = computed(() => {
  const options = metrics.value.map(metric => ({ value: metric.id, label: metric.name, disabled: false }))
  for (const id of form.value?.metricIds || [])
    if (!options.some(option => option.value === id))
      options.push({ value: id, label: `${release.value ? '失效指标' : '待核实指标'}（${id}）`, disabled: true })
  return options
})
const canDrill = computed(
  () =>
    !!form.value && ['BAR', 'LINE', 'PIE', 'TABLE'].includes(form.value.display) && form.value.dimensions.length === 1
)
const canLink = computed(() => canDrill.value && form.value?.dimensions[0]?.bucket === 'VALUE')
const bucketOptions = [
  { value: 'VALUE', label: '原值' },
  { value: 'DAY', label: '按日' },
  { value: 'MONTH', label: '按月' },
  { value: 'YEAR', label: '按年' }
]
const releaseCache = new Map<string, Promise<DatasetRelease>>()
let generation = 0,
  catalogGeneration = 0,
  referenceGeneration = 0,
  dragged = ''
async function load() {
  const g = ++generation
  runtimePreview.value = false
  filtersExpanded.value = false
  resizeSession = undefined
  resizePreview.value = undefined
  dragged = ''
  busy.value = true
  error.value = ''
  draft.value = undefined
  board.value = undefined
  chartFields.value = {}
  referenceErrors.value = {}
  referenceGeneration++
  referenceBusy.value = false
  dialog.value = false
  filterDialog.value = false
  catalogGeneration++
  results.value = {}
  resultKeys.value = {}
  try {
    const data = await api.dashboardGet(String(route.query.id || ''))
    if (g !== generation) return
    board.value = data
    draft.value = copyDashboard(data.draft)
    undo.value = []
    redo.value = []
    await refresh(g)
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) busy.value = false
  }
}
async function refresh(g = generation) {
  results.value = {}
  chartErrors.value = {}
  if (!board.value) return
  const id = board.value.id
  await Promise.all(
    board.value.draft.charts.map(async chart => {
      try {
        const result = await api.dashboardQuery({ id, chartId: chart.id, preview: true })
        if (
          g === generation &&
          draft.value?.charts.some(
            current => current.id === chart.id && chartPreviewKey(current) === chartPreviewKey(chart)
          )
        ) {
          results.value[chart.id] = result
          resultKeys.value[chart.id] = chartPreviewKey(chart)
        }
      } catch (e) {
        if (
          g === generation &&
          draft.value?.charts.some(
            current => current.id === chart.id && chartPreviewKey(current) === chartPreviewKey(chart)
          )
        )
          chartErrors.value[chart.id] = errorMessage(e)
      }
    })
  )
}
function commit(next: DashboardContent) {
  if (!draft.value || !canEdit.value) return
  undo.value.push(copyDashboard(draft.value))
  if (undo.value.length > 50) undo.value.shift()
  redo.value = []
  retainResults(next)
  draft.value = next
}
function retainResults(next: DashboardContent) {
  for (const id of Object.keys(results.value)) {
    const chart = next.charts.find(item => item.id === id)
    if (!chart || resultKeys.value[id] !== chartPreviewKey(chart)) {
      delete results.value[id]
      delete resultKeys.value[id]
    }
  }
  chartErrors.value = {}
}
function stopPreview() {
  previewGeneration++
  clearTimeout(previewTimer)
  previewController?.abort()
  previewBusy.value = false
}
async function previewChart() {
  stopPreview()
  if (!dialog.value || !form.value || catalogBusy.value || Object.keys(validation.value).length) return
  const chart: DashboardChart = JSON.parse(JSON.stringify(form.value)),
    key = chartPreviewKey(chart)
  const g = previewGeneration
  previewController = new AbortController()
  previewBusy.value = true
  previewError.value = ''
  previewResult.value = undefined
  try {
    const result = await api.dashboardChartPreview(chart, previewController.signal)
    if (g === previewGeneration && dialog.value && form.value && key === chartPreviewKey(form.value)) {
      previewResult.value = result
      previewKey.value = key
    }
  } catch (e) {
    if (g === previewGeneration) previewError.value = errorMessage(e)
  } finally {
    if (g === previewGeneration) previewBusy.value = false
  }
}
watch(
  () => [
    dialog.value,
    form.value && chartPreviewKey(form.value),
    catalogBusy.value,
    Object.keys(validation.value).length
  ],
  () => {
    stopPreview()
    previewError.value = ''
    if (!dialog.value || !form.value || catalogBusy.value || Object.keys(validation.value).length) return
    if (!previewCurrent.value) previewTimer = setTimeout(() => void previewChart(), 450)
  }
)
async function closeChart() {
  if (formChanged.value) {
    const discard = await new Promise<boolean>(resolve =>
      Modal.confirm({
        title: '放弃尚未应用的图表配置？',
        content: '这些修改还没有应用到画布。',
        okText: '放弃修改',
        cancelText: '继续配置',
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      })
    )
    if (!discard) return
  }
  dialog.value = false
  pendingVersion.value = undefined
  catalogGeneration++
  stopPreview()
}
function bucketsFor(fieldId: string) {
  const type = fieldTypes.value[fieldId]
  return type && !['DATE', 'DATETIME'].includes(type) ? bucketOptions.slice(0, 1) : bucketOptions
}
async function loadFieldTypes(fixed: DatasetRelease) {
  const g = ++fieldTypeGeneration
  fieldTypes.value = {}
  const source = fixed.definition.source
  // 报表来源接口按当前来源权限模式鉴权，不再额外要求数据中心的对象查询权限。
  if (!source?.fields.length) return
  const objects = [source.root, ...source.relations.map(relation => relation.target)]
  const definitions = await Promise.all(
    objects.map(object => api.sourceObject(object.objectId, object.versionNo).catch(() => undefined))
  )
  // 版本切换不一定重新加载候选，字段元数据需要独立隔离迟到响应。
  if (g !== fieldTypeGeneration || !dialog.value || release.value !== fixed) return
  for (const field of source.fields) {
    const reference = field.path.length
      ? source.relations.find(
          relation => JSON.stringify([...relation.parentPath, relation.id]) === JSON.stringify(field.path)
        )?.target
      : source.root
    const index = objects.findIndex(
      object => object.objectId === reference?.objectId && object.versionNo === reference?.versionNo
    )
    const type = definitions[index]?.fields.find(item => item.id === field.sourceFieldId)?.type
    if (type) fieldTypes.value[field.id] = type
  }
}
async function chartRelease(chart: DashboardChart) {
  const key = `${chart.dataset.id}:${chart.dataset.versionNo}:${chart.dataset.checksum}`,
    cached = releaseCache.get(key)
  if (cached) return cached
  const request = (async () => {
    let pageNo = 1
    while (true) {
      const page = await api.releases(chart.dataset.id, { pageNo, pageSize: 100 }),
        fixed = page.list.find(item => item.versionNo === chart.dataset.versionNo)
      if (fixed) {
        if (fixed.checksum !== chart.dataset.checksum) throw new Error('图表数据集版本校验值不一致，请重新选择版本')
        return fixed
      }
      if (pageNo * 100 >= page.total || !page.list.length) throw new Error('图表引用的数据集版本已不可用')
      pageNo++
    }
  })()
  releaseCache.set(key, request)
  try {
    return await request
  } catch (e) {
    releaseCache.delete(key)
    throw e
  }
}
async function loadChartFields() {
  if (!draft.value) return
  const g = ++referenceGeneration
  referenceBusy.value = true
  referenceErrors.value = {}
  chartFields.value = {}
  await Promise.all(
    draft.value.charts.map(async chart => {
      try {
        const fixed = await chartRelease(chart)
        if (g === referenceGeneration) chartFields.value[chart.id] = fixed.definition.source?.fields || []
      } catch (e) {
        if (g === referenceGeneration) referenceErrors.value[chart.id] = errorMessage(e)
      }
    })
  )
  if (g === referenceGeneration) referenceBusy.value = false
}
function openFilters() {
  filterDialog.value = true
  void loadChartFields()
}
function applyFilters(filters: DashboardFilter[]) {
  if (draft.value) commit({ ...copyDashboard(draft.value), filters })
}
function history(back: boolean) {
  const from = back ? undo : redo,
    to = back ? redo : undo
  const value = from.value.pop()
  if (value && draft.value) {
    to.value.push(copyDashboard(draft.value))
    retainResults(value)
    draft.value = value
  }
}
async function save() {
  if (!draft.value || !board.value || !canEdit.value) return
  busy.value = true
  error.value = ''
  try {
    const data = await api.dashboardSave({
      id: board.value.id,
      expectedRevision: board.value.revision,
      content: copyDashboard(draft.value)
    })
    board.value = data
    draft.value = copyDashboard(data.draft)
    await refresh()
    return data
  } catch (e) {
    error.value = errorMessage(e)
    return undefined
  } finally {
    busy.value = false
  }
}
async function publish() {
  if (!canPublish.value) return
  const current = dirty.value ? await save() : board.value
  if (!current) return
  busy.value = true
  error.value = ''
  try {
    await api.dashboardPublish({ id: current.id, expectedRevision: current.revision, requestId: crypto.randomUUID() })
    await load()
    // 菜单随发布事务生效，再从底座刷新当前账号可见入口。
    try {
      useUserStore().applyPermissionInfo(await getUserInfo())
    } catch {
      message.warning('仪表板已发布，当前菜单刷新失败，请刷新页面查看新入口')
    }
    await router.push({ path: '/nocode/report-center/dashboard-view', query: { id: current.id } })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function openChart(display: DashboardDisplay, existing?: DashboardChart) {
  if (!draft.value) return
  referenceReset.value = false
  pendingVersion.value = undefined
  catalogError.value = ''
  configTab.value = 'data'
  submitted.value = false
  previewResult.value = undefined
  previewKey.value = ''
  fieldTypes.value = {}
  formError.value = ''
  selectedDataset.value = ''
  selectedVersion.value = undefined
  releases.value = []
  form.value = existing
    ? JSON.parse(JSON.stringify(existing))
    : {
        id: datasetKey('chart'),
        title: dashboardDisplays.find(d => d.value === display)?.label || '图表',
        display,
        dataset: { id: '', versionNo: 0, checksum: '' },
        dimensions: [],
        ...(display === 'PIVOT'
          ? {
              columnDimensions: [],
              pivot: {
                subtotals: true,
                rowTotals: true,
                columnTotals: true,
                percent: 'NONE' as const,
                maxColumnGroups: 24
              }
            }
          : {}),
        metricIds: [],
        x: 0,
        y: Math.max(0, ...draft.value.charts.map(c => c.y + c.h)),
        w: display === 'METRIC' ? 4 : display === 'PIVOT' || display === 'TABLE' ? 12 : 6,
        h: display === 'METRIC' ? 3 : 6
      }
  formInitial.value = JSON.stringify(form.value)
  dialog.value = true
  void loadChartFields()
  await loadCatalog(existing)
}
async function loadCatalog(existing?: DashboardChart) {
  retryCatalog = () => loadCatalog(existing && form.value ? JSON.parse(JSON.stringify(form.value)) : undefined)
  catalogError.value = ''
  catalogBusy.value = true
  const g = ++catalogGeneration
  try {
    datasets.value = []
    let pageNo = 1
    while (true) {
      const data = await api.page({ pageNo, pageSize: 100, search: '' })
      if (g !== catalogGeneration) return
      datasets.value.push(...data.list.filter(d => d.publishedVersion && d.status === 'ACTIVE'))
      if (!data.list.length || pageNo * 100 >= data.total) break
      pageNo++
    }
    if (existing) {
      await chooseDataset(existing.dataset.id, existing)
    }
  } catch (e) {
    if (g === catalogGeneration) catalogError.value = errorMessage(e)
  } finally {
    if (g === catalogGeneration) catalogBusy.value = false
  }
}
async function chooseDataset(id: string, existing?: DashboardChart) {
  if (!existing && (id === selectedDataset.value || pendingVersion.value)) return
  const g = ++catalogGeneration
  retryCatalog = () => chooseDataset(id, existing && form.value ? JSON.parse(JSON.stringify(form.value)) : undefined)
  catalogBusy.value = true
  catalogError.value = ''
  formError.value = ''
  try {
    const versions: DatasetRelease[] = []
    let pageNo = 1
    while (true) {
      const data = await api.releases(id, { pageNo, pageSize: 100 })
      if (g !== catalogGeneration) return
      versions.push(...data.list)
      if (!data.list.length || pageNo * 100 >= data.total) break
      pageNo++
    }
    if (existing && !versions.some(item => item.versionNo === existing.dataset.versionNo)) {
      versions.push(await chartRelease(existing))
      if (g !== catalogGeneration) return
    }
    versions.sort((a, b) => b.versionNo - a.versionNo)
    if (!existing && form.value?.dataset.id) {
      const target = versions[0]
      if (!target) throw new Error('所选数据集没有可用发布版本，请选择其他数据集。当前配置未更换。')
      pendingVersion.value = { datasetId: id, versions, target }
      if (versionImpact.value?.keep) acceptVersionChange()
      return
    }
    selectedDataset.value = id
    releases.value = versions
    selectedVersion.value = existing?.dataset.versionNo || versions[0]?.versionNo
    if (!existing) setVersion()
    else if (form.value) form.value = JSON.parse(JSON.stringify(existing))
    if (release.value) void loadFieldTypes(release.value)
  } catch (e) {
    if (g === catalogGeneration) catalogError.value = errorMessage(e)
  } finally {
    if (g === catalogGeneration) catalogBusy.value = false
  }
}
function cancelVersionChange() {
  pendingVersion.value = undefined
  formError.value = ''
}
function acceptVersionChange() {
  const pending = pendingVersion.value,
    impact = versionImpact.value
  if (!pending || !impact || !form.value) return
  selectedDataset.value = pending.datasetId
  releases.value = pending.versions
  selectedVersion.value = pending.target.versionNo
  if (impact.keep)
    form.value.dataset = {
      id: pending.datasetId,
      versionNo: pending.target.versionNo,
      checksum: pending.target.checksum
    }
  else setVersion()
  pendingVersion.value = undefined
  formError.value = ''
  void loadFieldTypes(pending.target)
  if (impact.keep) message.success(`已切换至 V${pending.target.versionNo}，配置已保留，请核对预览结果。`)
}
async function chooseDisplay(display: DashboardDisplay) {
  const current = form.value
  if (!current || current.display === display) return
  const { chart, removed } = dashboardDisplayChange(current, display)
  if (removed.length) {
    const accepted = await new Promise<boolean>(resolve =>
      Modal.confirm({
        title: '切换图表类型？',
        content: `将移除${removed.join('、')}。其他兼容字段继续保留，确认图表配置后才更新画布。`,
        okText: '切换类型',
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      })
    )
    if (!accepted || form.value !== current || !dialog.value) return
  }
  form.value = chart
  formError.value = ''
}
function chooseVersion(versionNo: number) {
  if (!form.value || versionNo === form.value.dataset.versionNo || pendingVersion.value) return
  const target = releases.value.find(item => item.versionNo === versionNo)
  if (!target) return
  pendingVersion.value = { datasetId: selectedDataset.value, versions: releases.value, target }
  if (versionImpact.value?.keep) acceptVersionChange()
}
function setVersion() {
  if (!form.value || !release.value) return
  if (
    form.value.dataset.id &&
    (form.value.dataset.id !== selectedDataset.value || form.value.dataset.versionNo !== release.value.versionNo)
  ) {
    referenceReset.value = true
  }
  form.value.dataset = {
    id: selectedDataset.value,
    versionNo: release.value.versionNo,
    checksum: release.value.checksum
  }
  form.value.dimensions =
    form.value.display === 'METRIC' || !dimensions.value[0]
      ? []
      : [{ fieldId: dimensions.value[0].id, bucket: 'VALUE' }]
  if (form.value.display === 'PIVOT') form.value.columnDimensions = []
  form.value.metricIds = metrics.value[0] ? [metrics.value[0].id] : []
  form.value.drillDimensions = []
  form.value.links = []
}
function setChartWidth(width: number) {
  if (!form.value) return
  form.value.w = width
  form.value.x = Math.min(form.value.x, 12 - width)
}
function addDrill() {
  if (!form.value || !canDrill.value || (form.value.drillDimensions?.length || 0) >= 2) return
  const used = [...form.value.dimensions, ...(form.value.drillDimensions || [])],
    field = dimensions.value.find(item => !used.some(dimension => dimension.fieldId === item.id))
  form.value.drillDimensions = [...(form.value.drillDimensions || []), { fieldId: field?.id || '', bucket: 'VALUE' }]
}
function moveDrill(index: number, direction: number) {
  if (!form.value?.drillDimensions) return
  const layers = [...form.value.drillDimensions],
    target = index + direction
  if (target < 0 || target >= layers.length) return
  const item = layers.splice(index, 1)[0]
  if (!item) return
  layers.splice(target, 0, item)
  form.value.drillDimensions = layers
}
function addLink() {
  if (!form.value || !draft.value || !canLink.value) return
  const source = form.value,
    dimension = source.dimensions[0]
  if (!dimension) return
  const target = draft.value.charts.find(
    chart => chart.id !== source.id && !source.links?.some(link => link.targetChartId === chart.id)
  )
  source.links = [
    ...(source.links || []),
    { targetChartId: target?.id || '', sourceFieldId: dimension.fieldId, targetFieldId: '' }
  ]
}
function linkFieldOptions(chartId: string, current = '') {
  const fields = (chartFields.value[chartId] || []).filter(field => field.role === 'DIMENSION'),
    options = fields.map(field => ({ value: field.id, label: field.name, disabled: false }))
  if (current && !fields.some(field => field.id === current)) {
    options.unshift({ value: current, label: `字段不可用（${current}）`, disabled: true })
  }
  return options
}
function linkTargetOptions(current: string) {
  const charts = (draft.value?.charts || []).filter(chart => chart.id !== form.value?.id),
    options = charts.map(chart => ({ value: chart.id, label: chart.title, disabled: false }))
  if (current && !charts.some(chart => chart.id === current)) {
    options.unshift({ value: current, label: `图表不可用（${current}）`, disabled: true })
  }
  return options
}
function clearChartReferences(content: DashboardContent, id: string) {
  if (content.filters) {
    content.filters = content.filters
      .map(filter => ({ ...filter, mappings: filter.mappings.filter(mapping => mapping.chartId !== id) }))
      .filter(filter => filter.mappings.length)
  }
  content.charts.forEach(chart => {
    if (chart.id !== id && chart.links) chart.links = chart.links.filter(link => link.targetChartId !== id)
  })
}
async function applyChart() {
  if (pendingVersion.value) {
    formError.value = '请先确认或取消数据版本切换'
    return
  }
  submitted.value = true
  if (Object.keys(validation.value).length || catalogBusy.value) {
    configTab.value = 'data'
    formError.value = catalogBusy.value
      ? '数据来源正在加载，请稍候'
      : Object.values(validation.value)[0] || '请完善配置'
    await nextTick()
    formElement.value
      ?.querySelector('.ant-form-item-has-error')
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    return
  }
  if (!draft.value || !form.value || !release.value || referenceBusy.value) return
  formError.value = ''
  if (
    !form.value.title.trim() ||
    !form.value.metricIds.length ||
    (form.value.display !== 'METRIC' && !form.value.dimensions.length)
  ) {
    formError.value = '请填写标题并选择维度和指标'
    return
  }
  if (form.value.display === 'PIE' && (form.value.dimensions.length !== 1 || form.value.metricIds.length !== 1)) {
    formError.value = '饼图需要一个维度和一个指标'
    return
  }
  const maximum = form.value.display === 'TABLE' ? 3 : 2
  if (form.value.display !== 'PIVOT' && form.value.dimensions.length > maximum) {
    formError.value = `当前图表最多选择 ${maximum} 个分组维度，请减少维度后再应用`
    return
  }
  if (
    form.value.display === 'PIVOT' &&
    form.value.dimensions.length + (form.value.columnDimensions?.length || 0) > MAX_PIVOT_DIMENSIONS
  ) {
    formError.value = pivotDimensionLimitMessage
    return
  }
  const drill = form.value.drillDimensions || [],
    links = form.value.links || [],
    source = form.value,
    content = draft.value
  if (drill.length && (!canDrill.value || drill.length > 2)) {
    formError.value = '柱图、折线图、饼图和表格在只有一个基础维度时，可配置最多两个下钻层级'
    return
  }
  const layers = [...form.value.dimensions, ...drill],
    layerKeys = layers.map(dimension => `${dimension.fieldId}:${dimension.bucket}`)
  if (
    drill.some(dimension => !dimensions.value.some(field => field.id === dimension.fieldId)) ||
    (drill.length && new Set(layerKeys).size !== layerKeys.length)
  ) {
    formError.value = '请选择每层的有效维度，且维度与分组粒度组合不能重复'
    return
  }
  if (
    links.length &&
    (!canLink.value ||
      new Set(links.map(link => link.targetChartId)).size !== links.length ||
      links.some(
        link =>
          link.sourceFieldId !== source.dimensions[0]?.fieldId ||
          link.targetChartId === source.id ||
          !content.charts.some(chart => chart.id === link.targetChartId) ||
          !(chartFields.value[link.targetChartId] || []).some(field => field.id === link.targetFieldId)
      ))
  ) {
    formError.value = '联动需要一个原值基础维度，请为不同目标图表选择有效的来源及目标字段'
    return
  }
  try {
    const next = copyDashboard(draft.value),
      chart = JSON.parse(JSON.stringify(form.value)) as DashboardChart
    if (referenceReset.value) clearChartReferences(next, chart.id)
    next.charts = next.charts.filter(c => c.id !== chart.id)
    moveDashboardChart(next.charts, chart.id, chart)
    next.charts.push(chart)
    commit(next)
    if (previewCurrent.value && previewResult.value) {
      results.value[chart.id] = previewResult.value
      resultKeys.value[chart.id] = chartPreviewKey(chart)
    }
    dialog.value = false
    catalogGeneration++
  } catch (e) {
    formError.value = errorMessage(e)
  }
}
function remove(id: string) {
  if (!draft.value) return
  const related =
      draft.value.filters?.some(filter => filter.mappings.some(mapping => mapping.chartId === id)) ||
      draft.value.charts.some(chart => chart.id !== id && chart.links?.some(link => link.targetChartId === id)),
    apply = () => {
      if (!draft.value) return
      const next = copyDashboard(draft.value)
      clearChartReferences(next, id)
      next.charts = next.charts.filter(chart => chart.id !== id)
      commit(next)
    }
  if (!related) apply()
  else {
    Modal.confirm({
      title: '移除图表及其筛选、联动映射？',
      content: '对应的公共筛选映射和其他图指向此图的联动会一并删除。不再有映射的公共筛选也会移除。可通过撤销恢复。',
      okText: '移除图表和映射',
      okType: 'danger',
      onOk: apply
    })
  }
}
function duplicate(id: string) {
  if (!draft.value || !canEdit.value || busy.value) return
  try {
    commit(duplicateDashboardChart(draft.value, id, datasetKey('chart')))
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function gridSteps(grid: HTMLElement) {
  const style = getComputedStyle(grid)
  if (style.display !== 'grid') throw new Error('请在宽屏画布中调整网格布局')
  const columnGap = Number.parseFloat(style.columnGap),
    rowGap = Number.parseFloat(style.rowGap)
  return {
    x: (grid.getBoundingClientRect().width - columnGap * 11) / 12 + columnGap,
    y: Number.parseFloat(style.gridAutoRows) + rowGap
  }
}
function startDrag(event: DragEvent, chart: DashboardChart) {
  if (!canEdit.value || busy.value || !gridElement.value) {
    event.preventDefault()
    return
  }
  const tile = (event.currentTarget as HTMLElement).closest('article')!,
    rect = tile.getBoundingClientRect()
  dragOffset = { x: event.clientX - rect.left, y: event.clientY - rect.top }
  dragged = chart.id
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', chart.id)
  }
}
function chartLayout(chart: DashboardChart): Layout {
  return resizePreview.value?.id === chart.id ? resizePreview.value.layout : chart
}
function applyLayout(chart: DashboardChart, layout: Layout) {
  if (!draft.value || !canEdit.value || busy.value) return
  if (['x', 'y', 'w', 'h'].every(key => chart[key as keyof Layout] === layout[key as keyof Layout])) return
  commit({ ...copyDashboard(draft.value), charts: moveDashboardChart(draft.value.charts, chart.id, layout) })
}
function startResize(event: PointerEvent, chart: DashboardChart) {
  if (!draft.value || !gridElement.value || !canEdit.value || busy.value || event.button !== 0) return
  try {
    const steps = gridSteps(gridElement.value)
    resizeSession = {
      id: chart.id,
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      stepX: steps.x,
      stepY: steps.y,
      chart,
      draft: draft.value
    }
    resizePreview.value = { id: chart.id, layout: { x: chart.x, y: chart.y, w: chart.w, h: chart.h } }
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
    event.preventDefault()
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function resizeMove(event: PointerEvent) {
  const session = resizeSession
  if (!session || event.pointerId !== session.pointerId || session.draft !== draft.value) return
  const chart = session.chart,
    layout = {
      x: chart.x,
      y: chart.y,
      w: Math.max(3, Math.min(12 - chart.x, chart.w + Math.round((event.clientX - session.x) / session.stepX))),
      h: Math.max(2, Math.min(12, chart.h + Math.round((event.clientY - session.y) / session.stepY)))
    }
  try {
    moveDashboardChart(session.draft.charts, chart.id, layout)
    resizePreview.value = { id: chart.id, layout }
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function finishResize(event: PointerEvent, canceled = false) {
  const session = resizeSession,
    preview = resizePreview.value
  if (!session || event.pointerId !== session.pointerId) return
  resizeSession = undefined
  resizePreview.value = undefined
  const handle = event.currentTarget as HTMLElement
  if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId)
  if (!canceled && preview && session.draft === draft.value) applyLayout(session.chart, preview.layout)
}
function keyboardResize(event: KeyboardEvent, chart: DashboardChart) {
  const delta: Record<string, [number, number]> = {
    ArrowLeft: [-1, 0],
    ArrowRight: [1, 0],
    ArrowUp: [0, -1],
    ArrowDown: [0, 1]
  }
  const change = delta[event.key]
  if (!change) return
  event.preventDefault()
  try {
    applyLayout(chart, { ...chart, w: chart.w + change[0], h: chart.h + change[1] })
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function drop(event: DragEvent) {
  event.preventDefault()
  if (!draft.value || !dragged || !canEdit.value || busy.value) return
  const grid = event.currentTarget as HTMLElement,
    rect = grid.getBoundingClientRect(),
    chart = draft.value.charts.find(c => c.id === dragged)
  if (!chart) return
  try {
    const steps = gridSteps(grid),
      x = Math.max(0, Math.min(12 - chart.w, Math.round((event.clientX - rect.left - dragOffset.x) / steps.x))),
      y = Math.max(0, Math.round((event.clientY - rect.top - dragOffset.y) / steps.y))
    applyLayout(chart, { ...chart, x, y })
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    dragged = ''
  }
}
watch(() => route.query.id, load, { immediate: true })
watch(dialog, open => {
  if (!open) catalogGeneration++
})
onScopeDispose(() => {
  stopPreview()
  fieldTypeGeneration++
  resizeSession = undefined
  resizePreview.value = undefined
  generation++
  catalogGeneration++
  referenceGeneration++
})
onBeforeRouteLeave(() => {
  if (!dirty.value && !(dialog.value && formChanged.value) && !(settingsOpen.value && settingsChanged.value))
    return true
  return new Promise<boolean>(resolve =>
    Modal.confirm({
      title: '离开并放弃未保存的仪表板修改？',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  )
})
</script>
<template>
  <section v-if="runtimePreview && board" class="dashboard-page">
    <a-alert type="info" message="正在试用已保存的草稿，此操作不会发布。筛选、联动和下钻仅影响本次预览。" show-icon />
    <DashboardRuntime
      :transport="previewTransport"
      :context-key="board.id + ':' + board.revision"
      title="草稿交互预览"
      version-label="已保存草稿"
    >
      <template #leading-actions><a-button @click="runtimePreview = false">返回编辑</a-button></template>
    </DashboardRuntime>
  </section>
  <section v-else class="dashboard-page dashboard-design-page">
    <header class="dashboard-design-header">
      <div class="dashboard-design-context">
        <a-button type="text" size="small" @click="router.push('/nocode/report-center/dashboards')">
          <ArrowLeftOutlined />
          返回仪表板
        </a-button>
        <span class="dashboard-design-mode">仪表板设计</span>
      </div>
      <div class="dashboard-design-heading">
        <div class="dashboard-design-identity">
          <div class="dashboard-design-title">
            <h2>{{ draft?.name || '未命名仪表板' }}</h2>
            <a-tag v-if="board" :color="dirty ? 'warning' : undefined">
              {{ dirty ? '未保存' : board.modified ? '草稿已保存' : '与发布版本一致' }}
            </a-tag>
          </div>
          <a-tooltip v-if="draft?.description" :title="draft.description" placement="bottomLeft">
            <p class="dashboard-design-description" tabindex="0">{{ draft.description }}</p>
          </a-tooltip>
        </div>
        <div class="dashboard-design-actions">
          <a-button v-if="canEdit" :loading="busy" :disabled="!draft" @click="save">保存草稿</a-button>
          <a-button :disabled="busy || !draft?.charts.length" @click="tryInteractions">
            {{ dirty ? '保存并试用交互' : '试用交互' }}
          </a-button>
          <a-button v-if="canPublish" type="primary" :disabled="busy || !draft?.charts.length" @click="publish">
            发布并打开
          </a-button>
        </div>
      </div>
    </header>
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-alert v-if="board && !canEdit" message="当前可审核并发布已保存的草稿。" type="info" show-icon />
    <a-skeleton v-if="!draft && !error" active />
    <template v-if="draft">
      <section class="dashboard-design-tools" aria-label="画布工具栏">
        <div class="dashboard-design-tool-row">
          <div class="dashboard-design-tool-group">
            <a-dropdown v-if="canEdit" :trigger="['click']" :disabled="busy || draft.charts.length >= 30">
              <a-button :disabled="busy || draft.charts.length >= 30">
                <PlusOutlined />
                添加组件
                <DownOutlined />
              </a-button>
              <template #overlay>
                <a-menu>
                  <a-menu-item
                    v-for="display in dashboardDisplays"
                    :key="display.value"
                    :disabled="busy || draft.charts.length >= 30"
                    @click="openChart(display.value)"
                  >
                    {{ display.label }}
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
            <span class="dashboard-design-count">{{ draft.charts.length }} 个组件</span>
            <div v-if="canEdit" class="dashboard-design-history" role="group" aria-label="编辑历史">
              <a-tooltip title="撤销">
                <a-button type="text" aria-label="撤销" :disabled="busy || !undo.length" @click="history(true)">
                  <UndoOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip title="重做">
                <a-button type="text" aria-label="重做" :disabled="busy || !redo.length" @click="history(false)">
                  <RedoOutlined />
                </a-button>
              </a-tooltip>
            </div>
          </div>
          <div class="dashboard-design-tool-group">
            <a-button v-if="canEdit" type="text" :disabled="busy" @click="openSettings">
              <SettingOutlined />
              仪表板设置
            </a-button>
            <a-button type="text" :disabled="busy" @click="menuSettingsOpen = true">菜单设置</a-button>
            <a-button v-if="canGrant" type="text" :disabled="busy" @click="authorizationOpen = true">协作权限</a-button>
            <a-popover title="设计帮助" :trigger="['click']" placement="bottomRight">
              <template #content>
                <ol class="dashboard-design-help">
                  <li>添加组件，在右侧完成配置并应用到画布。</li>
                  <li>保存草稿后，可试用筛选、联动和下钻。</li>
                  <li>发布后，查看者才能看到本次修改。</li>
                </ol>
                <p class="dashboard-design-help-note">
                  拖动「移动」调整位置，拖动右下角调整大小；也可在图表配置的「布局」中设置。
                </p>
              </template>
              <a-button type="text">
                <QuestionCircleOutlined />
                设计帮助
              </a-button>
            </a-popover>
          </div>
        </div>
        <div v-if="canEdit || draft.filters?.length" class="dashboard-design-filters" aria-label="公共筛选摘要">
          <span class="dashboard-design-filter-label">
            <FilterOutlined />
            公共筛选
          </span>
          <div class="dashboard-design-filter-items">
            <template v-if="draft.filters?.length">
              <a-tooltip
                v-for="filter in visibleFilters"
                :key="filter.id"
                :title="`${filter.name} · 作用于 ${filter.mappings.length} 张图表`"
              >
                <a-tag class="dashboard-design-filter-tag" tabindex="0">{{ filter.name }}</a-tag>
              </a-tooltip>
              <a-button
                v-if="draft.filters.length > 3"
                type="link"
                size="small"
                :aria-expanded="filtersExpanded"
                @click="filtersExpanded = !filtersExpanded"
              >
                {{ filtersExpanded ? '收起' : `展开其余 ${draft.filters.length - 3} 项` }}
              </a-button>
            </template>
            <span v-else class="dashboard-design-count">未配置</span>
          </div>
          <a-button
            v-if="canEdit"
            type="link"
            size="small"
            aria-label="公共筛选"
            :disabled="busy || !draft.charts.length"
            @click="openFilters"
          >
            {{ draft.filters?.length ? '配置筛选' : '添加筛选' }}
          </a-button>
        </div>
      </section>
      <div ref="gridElement" class="dashboard-grid" @dragover.prevent @drop="drop">
        <div v-if="!draft.charts.length" class="dashboard-empty">
          <a-empty description="选择一种组件，开始制作你的仪表板" />
        </div>
        <article
          v-for="chart in draft.charts"
          :key="chart.id"
          class="dashboard-tile dashboard-editor-tile"
          :style="{
            gridColumn: `${chartLayout(chart).x + 1} / span ${chartLayout(chart).w}`,
            gridRow: `${chartLayout(chart).y + 1} / span ${chartLayout(chart).h}`
          }"
        >
          <header class="dashboard-tile-header">
            <h3>{{ chart.title }}</h3>
            <a-space v-if="canEdit">
              <span
                class="dashboard-drag"
                :draggable="!busy"
                role="button"
                :aria-label="'移动' + chart.title"
                @dragstart="startDrag($event, chart)"
                @dragend="dragged = ''"
              >
                移动
              </span>
              <a-button type="link" size="small" :disabled="busy" @click="openChart(chart.display, chart)">
                配置
              </a-button>
              <a-button
                type="link"
                size="small"
                :disabled="busy || draft.charts.length >= 30"
                @click="duplicate(chart.id)"
              >
                复制
              </a-button>
              <a-button type="link" danger size="small" :disabled="busy" @click="remove(chart.id)">移除</a-button>
            </a-space>
          </header>
          <a-alert v-if="chartErrors[chart.id]" :message="chartErrors[chart.id]" type="error" show-icon />
          <DashboardChartView v-else-if="results[chart.id] && board" :chart="chart" :result="results[chart.id]!" />
          <a-empty v-else description="配置已变更，请打开配置预览，或保存草稿刷新数据" />
          <button
            v-if="canEdit"
            class="dashboard-resize"
            :disabled="busy"
            :aria-label="'缩放' + chart.title"
            title="拖动调整宽高，也可使用方向键逐格调整"
            @pointerdown="startResize($event, chart)"
            @pointermove="resizeMove"
            @pointerup="finishResize($event)"
            @pointercancel="finishResize($event, true)"
            @keydown="keyboardResize($event, chart)"
          >
            ↘
          </button>
        </article>
      </div>
    </template>
    <DashboardMenuSettings
      v-if="draft"
      :open="menuSettingsOpen"
      :content="draft"
      :read-only="!canEdit || busy"
      @close="menuSettingsOpen = false"
      @apply="applyMenuSettings"
    />
    <OsModalForm
      :open="settingsOpen"
      title="仪表板设置"
      display-mode="drawer"
      :width="520"
      :allow-switch-display="false"
      :resizable="false"
      :wrap-form="false"
      ok-text="应用到画布"
      @ok="applySettings"
      @cancel="closeSettings"
    >
      <template #formItems>
        <a-form layout="vertical">
          <a-form-item
            label="仪表板名称"
            required
            :validate-status="settingsError ? 'error' : undefined"
            :help="settingsError || undefined"
          >
            <a-input
              v-model:value="settings.name"
              aria-label="仪表板名称"
              :maxlength="80"
              @change="settingsError = ''"
            />
          </a-form-item>
          <a-form-item label="说明">
            <a-textarea
              v-model:value="settings.description"
              aria-label="仪表板说明"
              placeholder="填写看板说明"
              :maxlength="1000"
              :rows="4"
              show-count
            />
          </a-form-item>
        </a-form>
        <p class="dialog-interaction-help">应用只更新当前画布，点击「保存草稿」后保存修改。</p>
      </template>
    </OsModalForm>
    <DashboardAuthorization
      v-if="authorizationOpen && board"
      :dashboard-id="board.id"
      @close="authorizationOpen = false"
    />
    <DashboardInteractionEditor
      v-if="draft"
      v-model:open="filterDialog"
      :charts="draft.charts"
      :filters="draft.filters"
      :fields="chartFields"
      :field-errors="referenceErrors"
      :loading="referenceBusy"
      @apply="applyFilters"
      @retry="loadChartFields"
    />
    <OsModalForm
      :open="dialog"
      title="图表配置"
      display-mode="drawer"
      :width="1200"
      :resizable="false"
      :loading="catalogBusy || referenceBusy"
      :wrap-form="false"
      :allow-switch-display="false"
      ok-text="应用到画布"
      @ok="applyChart"
      @cancel="closeChart"
    >
      <template #formItems>
        <div v-if="form" class="chart-config-workspace">
          <div ref="formElement" class="chart-config-fields">
            <a-alert v-if="formError" type="error" :message="formError" show-icon />
            <a-alert v-if="catalogError" type="error" :message="catalogError" show-icon>
              <template #action>
                <a-button size="small" :loading="catalogBusy" @click="retryCatalog?.()">重试数据来源</a-button>
              </template>
            </a-alert>
            <a-tabs v-model:active-key="configTab">
              <a-tab-pane key="data" tab="数据与显示" />
              <a-tab-pane key="interaction" tab="交互" />
              <a-tab-pane key="layout" tab="布局" />
            </a-tabs>
            <a-form layout="vertical" :disabled="catalogBusy || !!pendingVersion">
              <div v-show="configTab === 'data'">
                <a-alert
                  type="info"
                  :message="chartConfigurationGuide[form.display]"
                  show-icon
                  class="dialog-interaction-section"
                />
                <a-form-item label="图表类型" required>
                  <a-select
                    :value="form.display"
                    aria-label="图表类型"
                    :options="dashboardDisplays"
                    @change="(display: DashboardDisplay) => chooseDisplay(display)"
                  />
                </a-form-item>
                <a-form-item
                  label="图表标题"
                  required
                  :validate-status="submitted && validation.title ? 'error' : undefined"
                  :help="submitted ? validation.title : undefined"
                >
                  <a-input v-model:value="form.title" aria-label="图表标题" :maxlength="80" />
                </a-form-item>
                <a-form-item
                  label="数据集"
                  required
                  :validate-status="submitted && validation.dataset ? 'error' : undefined"
                  :help="submitted ? validation.dataset : undefined"
                >
                  <a-select
                    :value="selectedDataset || undefined"
                    aria-label="图表数据集"
                    placeholder="选择已发布的数据集"
                    :not-found-content="catalogBusy ? '加载中…' : '没有匹配的已发布数据集'"
                    :loading="catalogBusy"
                    :disabled="catalogBusy || !!pendingVersion"
                    show-search
                    option-filter-prop="label"
                    :options="datasets.map(d => ({ value: d.id, label: d.draft.name }))"
                    @change="(id: string) => chooseDataset(id)"
                  />
                </a-form-item>
                <a-form-item
                  label="发布版本"
                  required
                  :validate-status="submitted && validation.version ? 'error' : undefined"
                  :help="submitted ? validation.version : undefined"
                >
                  <a-select
                    :value="selectedVersion"
                    aria-label="图表数据集版本"
                    :disabled="catalogBusy || !!pendingVersion"
                    :placeholder="selectedDataset ? '选择发布版本' : '先选择数据集'"
                    :not-found-content="catalogBusy ? '加载中…' : '此数据集暂无发布版本'"
                    :options="
                      releases.map(v => ({
                        value: v.versionNo,
                        label: 'V' + v.versionNo + ' · ' + formatDateTime(v.createTime)
                      }))
                    "
                    @change="(versionNo: number) => chooseVersion(versionNo)"
                  />
                </a-form-item>
                <p class="dialog-interaction-help">{{ sourceHint }}</p>
                <a-alert
                  v-if="!catalogBusy && !catalogError && !datasets.length"
                  type="info"
                  message="暂无可用的已发布数据集，请先在数据集页面完成配置、发布并启用。"
                  show-icon
                />
                <a-alert
                  v-if="release && form.display !== 'METRIC' && !dimensions.length"
                  type="warning"
                  message="此版本没有可用维度，请在数据集中设置维度并发布，或使用无需分组的指标卡。"
                  show-icon
                />
                <a-form-item
                  v-if="form.display !== 'METRIC'"
                  :label="form.display === 'PIVOT' ? '行维度' : '分组维度'"
                  required
                  :validate-status="validation.dimensions ? 'error' : undefined"
                  :help="validation.dimensions"
                >
                  <a-select
                    :value="form.display === 'PIE' ? form.dimensions[0]?.fieldId : form.dimensions.map(d => d.fieldId)"
                    :mode="form.display === 'PIE' ? undefined : 'multiple'"
                    :disabled="!release || catalogBusy || !!pendingVersion"
                    placeholder="选择分组字段"
                    aria-label="图表维度"
                    :options="dimensionOptions()"
                    @change="
                      (value: string[] | string) => {
                        const ids = Array.isArray(value) ? value : [value]
                        form!.dimensions = ids.map(fieldId => ({
                          fieldId,
                          bucket: form!.dimensions.find(d => d.fieldId === fieldId)?.bucket || 'VALUE'
                        }))
                      }
                    "
                  />
                </a-form-item>
                <template v-if="form.display === 'PIVOT'">
                  <a-form-item label="列维度">
                    <a-select
                      :value="(form.columnDimensions || []).map(d => d.fieldId)"
                      mode="multiple"
                      aria-label="透视列维度"
                      :disabled="!release || catalogBusy || !!pendingVersion"
                      :options="dimensionOptions(true)"
                      @change="
                        (ids: string[]) => {
                          form!.columnDimensions = ids.map(fieldId => ({
                            fieldId,
                            bucket: form!.columnDimensions?.find(d => d.fieldId === fieldId)?.bucket || 'VALUE'
                          }))
                        }
                      "
                    />
                  </a-form-item>
                  <a-space v-if="form.pivot" wrap>
                    <a-checkbox v-model:checked="form.pivot.subtotals">显示小计</a-checkbox>
                    <a-checkbox v-model:checked="form.pivot.rowTotals">显示行合计</a-checkbox>
                    <a-checkbox v-model:checked="form.pivot.columnTotals">显示列合计</a-checkbox>
                    <span>占比口径</span>
                    <a-select
                      v-model:value="form.pivot.percent"
                      aria-label="透视占比"
                      :options="[
                        { value: 'NONE', label: '原始数值' },
                        { value: 'ROW', label: '行占比' },
                        { value: 'COLUMN', label: '列占比' },
                        { value: 'TOTAL', label: '总体占比' }
                      ]"
                    />
                  </a-space>
                  <p class="dialog-interaction-help">
                    行维度纵向排列，列维度横向展开。行占比以行合计为分母，列占比以列合计为分母，总体占比以总计为分母。
                  </p>
                  <a-form-item
                    v-if="form.pivot"
                    label="最多展示列组"
                    :validate-status="validation.pivot ? 'error' : undefined"
                    :help="validation.pivot || '限制横向显示的分组数量，合计仍按完整授权范围计算。'"
                  >
                    <a-input-number
                      v-model:value="form.pivot.maxColumnGroups"
                      :min="1"
                      :max="100"
                      aria-label="透视列组上限"
                    />
                  </a-form-item>
                </template>
                <a-space
                  v-for="dimension in [...form.dimensions, ...(form.columnDimensions || [])]"
                  :key="dimension.fieldId"
                >
                  <span>{{ dimensions.find(d => d.id === dimension.fieldId)?.name }}</span>
                  <a-select
                    v-model:value="dimension.bucket"
                    aria-label="图表分组粒度"
                    :options="bucketsFor(dimension.fieldId)"
                  />
                </a-space>
                <a-form-item
                  label="指标"
                  required
                  :validate-status="validation.metrics ? 'error' : undefined"
                  :help="validation.metrics"
                >
                  <a-select
                    :value="form.display === 'PIE' ? form.metricIds[0] : form.metricIds"
                    :mode="form.display === 'PIE' ? undefined : 'multiple'"
                    :disabled="!release || catalogBusy || !!pendingVersion"
                    placeholder="选择统计指标"
                    @change="(value: string[] | string) => (form!.metricIds = Array.isArray(value) ? value : [value])"
                    aria-label="图表指标"
                    :options="metricOptions"
                  />
                </a-form-item>
                <p v-if="release" class="dialog-interaction-help">
                  <span v-for="metric in metrics.filter(m => form!.metricIds.includes(m.id))" :key="metric.id">
                    {{ metric.name }}：{{
                      operationOptions.find(option => option.value === metric.operation)?.label || '计算指标'
                    }}
                    <template v-if="metric.fieldId">
                      （{{
                        release.definition.source?.fields.find(field => field.id === metric.fieldId)?.name ||
                        '来源字段'
                      }}）
                    </template>
                    ；
                  </span>
                  指标口径在数据集中维护，预览遵守固定条件和当前数据权限。
                </p>
              </div>
              <div v-show="configTab === 'interaction'">
                <a-empty
                  v-if="!interactionSupported"
                  description="当前图表类型不支持层级下钻和对外联动；仍可接收公共筛选。"
                />
                <template v-if="interactionSupported">
                  <a-card size="small" title="层级下钻" class="dialog-interaction-section">
                    <template #extra>
                      <a-button
                        type="link"
                        :disabled="!canDrill || (form.drillDimensions?.length || 0) >= 2"
                        @click="addDrill"
                      >
                        添加下钻层级
                      </a-button>
                    </template>
                    <p class="dialog-interaction-help">基础维度为第 1 层，按顺序配置后续层级，最多 3 层。</p>
                    <a-alert
                      v-if="!canDrill"
                      type="info"
                      message="柱图、折线图、饼图和表格只选择一个基础维度后，可启用层级下钻。"
                      show-icon
                    />
                    <div v-for="(layer, index) in form.drillDimensions || []" :key="index" class="dialog-drill-row">
                      <span>第 {{ index + 2 }} 层</span>
                      <a-select
                        v-model:value="layer.fieldId"
                        :aria-label="`第${index + 2}层维度`"
                        :options="dimensions.map(field => ({ value: field.id, label: field.name }))"
                        placeholder="选择层级维度"
                        show-search
                        option-filter-prop="label"
                      />
                      <a-select
                        v-model:value="layer.bucket"
                        :aria-label="`第${index + 2}层分组粒度`"
                        :options="bucketsFor(layer.fieldId)"
                      />
                      <a-space>
                        <a-button
                          size="small"
                          :disabled="index === 0"
                          :aria-label="`上移第${index + 2}层`"
                          @click="moveDrill(index, -1)"
                        >
                          上移
                        </a-button>
                        <a-button
                          size="small"
                          :disabled="index === (form.drillDimensions?.length || 0) - 1"
                          :aria-label="`下移第${index + 2}层`"
                          @click="moveDrill(index, 1)"
                        >
                          下移
                        </a-button>
                        <a-button
                          type="link"
                          danger
                          :aria-label="`移除第${index + 2}层`"
                          @click="form.drillDimensions?.splice(index, 1)"
                        >
                          移除
                        </a-button>
                      </a-space>
                    </div>
                  </a-card>
                  <a-card size="small" title="图表联动" class="dialog-interaction-section">
                    <template #extra>
                      <a-button
                        type="link"
                        :disabled="
                          !canLink || referenceBusy || (form.links?.length || 0) >= (draft?.charts.length || 1) - 1
                        "
                        @click="addLink"
                      >
                        添加图表联动
                      </a-button>
                    </template>
                    <p class="dialog-interaction-help">
                      点选当前图表的基础维度值，筛选指定目标图表。进入下钻层级后暂停联动。
                    </p>
                    <a-alert v-if="!canLink" type="info" message="联动需要一个使用原值分组的基础维度。" show-icon />
                    <a-skeleton v-if="referenceBusy" active :paragraph="{ rows: 2 }" />
                    <a-alert
                      v-if="Object.keys(referenceErrors).length"
                      type="warning"
                      message="部分图表的数据集字段加载失败，请重试或取消相应联动。"
                      show-icon
                    >
                      <template #action>
                        <a-button size="small" @click="loadChartFields">重新加载字段</a-button>
                      </template>
                    </a-alert>
                    <div v-for="(link, index) in form.links || []" :key="index" class="dialog-link-row">
                      <a-form-item label="目标图表" required>
                        <a-select
                          :value="link.targetChartId || undefined"
                          aria-label="联动目标图表"
                          :options="linkTargetOptions(link.targetChartId)"
                          placeholder="选择目标图表"
                          @change="
                            (chartId: string) => {
                              link.targetChartId = chartId
                              link.targetFieldId = ''
                            }
                          "
                        />
                      </a-form-item>
                      <a-form-item label="来源字段" required>
                        <a-select
                          v-model:value="link.sourceFieldId"
                          aria-label="联动来源字段"
                          :options="
                            form.dimensions.map(dimension => ({
                              value: dimension.fieldId,
                              label: dimensions.find(field => field.id === dimension.fieldId)?.name || dimension.fieldId
                            }))
                          "
                        />
                      </a-form-item>
                      <a-form-item label="目标字段" required>
                        <a-select
                          :value="link.targetFieldId || undefined"
                          aria-label="联动目标字段"
                          :options="linkFieldOptions(link.targetChartId, link.targetFieldId)"
                          :disabled="referenceBusy || !link.targetChartId || !!referenceErrors[link.targetChartId]"
                          placeholder="选择目标版本中的字段"
                          show-search
                          option-filter-prop="label"
                          @change="(fieldId: string) => (link.targetFieldId = fieldId)"
                        />
                      </a-form-item>
                      <a-button
                        type="link"
                        danger
                        :aria-label="`移除联动${index + 1}`"
                        @click="form.links?.splice(index, 1)"
                      >
                        移除联动
                      </a-button>
                    </div>
                  </a-card>
                </template>
              </div>
              <div v-show="configTab === 'layout'">
                <p class="dialog-interaction-help">画布每行 12 列，位置从 0 开始。可在画布拖动或缩放，也可精确填写。</p>
                <a-space wrap class="dialog-interaction-section">
                  <a-button @click="setChartWidth(4)">三分之一宽</a-button>
                  <a-button @click="setChartWidth(6)">半行宽</a-button>
                  <a-button @click="setChartWidth(12)">整行宽</a-button>
                </a-space>
                <a-space wrap>
                  <a-form-item label="列位置">
                    <a-input-number v-model:value="form.x" :min="0" :max="12 - form.w" aria-label="图表列位置" />
                  </a-form-item>
                  <a-form-item label="行位置">
                    <a-input-number v-model:value="form.y" :min="0" :max="200" aria-label="图表行位置" />
                  </a-form-item>
                  <a-form-item label="宽度">
                    <a-input-number v-model:value="form.w" :min="3" :max="12" aria-label="图表宽度" />
                  </a-form-item>
                  <a-form-item label="高度">
                    <a-input-number v-model:value="form.h" :min="2" :max="12" aria-label="图表高度" />
                  </a-form-item>
                </a-space>
              </div>
            </a-form>
          </div>
          <aside class="chart-config-preview" aria-label="图表配置预览">
            <div class="chart-preview-heading">
              <div>
                <h3>{{ form.title || '图表预览' }}</h3>
                <span>{{ previewBusy ? '正在更新预览…' : '自动预览 · 尚未保存' }}</span>
              </div>
              <a-space>
                <a-button
                  type="text"
                  :aria-expanded="previewExpanded"
                  aria-controls="chart-config-preview-body"
                  @click="previewExpanded = !previewExpanded"
                >
                  {{ previewExpanded ? '收起预览' : '展开预览' }}
                </a-button>
                <a-button
                  :loading="previewBusy"
                  :disabled="catalogBusy || !!Object.keys(validation).length"
                  @click="previewChart"
                >
                  刷新预览
                </a-button>
              </a-space>
            </div>
            <div v-show="previewExpanded" id="chart-config-preview-body">
              <p class="dialog-interaction-help">单图预览使用当前配置和真实数据，不叠加公共筛选、联动或下钻条件。</p>
              <a-alert v-if="previewError" type="error" :message="previewError" show-icon />
              <a-skeleton v-else-if="previewBusy" active :paragraph="{ rows: 6 }" />
              <div
                v-else-if="previewCurrent && previewResult && !Object.keys(validation).length"
                class="chart-preview-result"
              >
                <a-empty
                  v-if="previewResult.recordCount === 0"
                  description="当前条件下没有可见数据，请检查数据集固定条件、数据权限及来源记录。配置本身已通过校验。"
                />
                <DashboardChartView v-else :chart="form" :result="previewResult" />
              </div>
              <a-empty v-else :description="Object.values(validation)[0] || '配置已变更，正在准备预览…'" />
            </div>
          </aside>
        </div>
      </template>
      <template #footer>
        <span class="chart-config-footer-note">应用后需保存草稿</span>
        <a-button @click="closeChart">取消</a-button>
        <a-button type="primary" :loading="catalogBusy || referenceBusy" @click="applyChart">应用到画布</a-button>
      </template>
    </OsModalForm>
    <a-modal
      v-if="dialog && form && pendingVersion && versionImpact"
      :open="true"
      title="切换并重置配置？"
      :width="480"
      centered
      :mask-closable="false"
      ok-text="切换并重置"
      cancel-text="取消"
      @ok="acceptVersionChange"
      @cancel="cancelVersionChange"
    >
      <p class="chart-switch-target">
        {{ datasetName(form.dataset.id, release?.definition.name) }} V{{ form.dataset.versionNo }} →
        {{ datasetName(pendingVersion.datasetId, pendingVersion.target.definition.name) }} V{{
          pendingVersion.target.versionNo
        }}
      </p>
      <p>{{ versionImpact.summary }}</p>
      <details class="chart-switch-details">
        <summary>查看影响详情</summary>
        <div>
          <ul>
            <li v-for="item in versionImpact.reset" :key="item">{{ item }}</li>
          </ul>
          <p v-for="item in versionImpact.notices" :key="item">{{ item }}</p>
        </div>
      </details>
      <p class="chart-switch-note">标题和布局保留，应用到画布后生效。</p>
    </a-modal>
  </section>
</template>
<style src="./dashboard.css"></style>
<style scoped>
:global(.ant-drawer-content-wrapper:has(.chart-config-workspace)) {
  max-width: 100vw;
}
.chart-config-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--spacing-xl);
  align-items: start;
}
.chart-config-fields {
  min-width: 0;
}
.chart-switch-target {
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.chart-switch-details summary {
  color: var(--brand);
  cursor: pointer;
}
.chart-switch-details > div {
  max-height: 35vh;
  overflow: auto;
  margin-top: var(--spacing-sm);
}
.chart-switch-details ul {
  padding-left: var(--spacing-xl);
}
.chart-switch-note {
  margin-top: var(--spacing-lg);
  margin-bottom: 0;
  color: var(--text-secondary);
}
.chart-config-preview {
  position: sticky;
  top: 0;
  min-width: 0;
  max-height: calc(100dvh - var(--spacing-lg) * 10);
  overflow: auto;
  padding: var(--spacing-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-page);
}
.chart-preview-heading {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-lg);
}
.chart-preview-heading h3 {
  margin: 0;
  overflow-wrap: anywhere;
}
.chart-preview-heading span,
.chart-config-footer-note {
  color: var(--text-secondary);
}
.chart-preview-result {
  min-height: calc(var(--spacing-lg) * 20);
  overflow: auto;
}
@media (max-width: 768px) {
  .chart-config-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .chart-config-preview {
    position: static;
    max-height: none;
  }
}
.chart-config-footer-note {
  margin-right: auto;
  align-self: center;
}
.dashboard-design-header {
  margin-bottom: var(--spacing-lg);
}
.dashboard-design-context,
.dashboard-design-heading,
.dashboard-design-title,
.dashboard-design-actions,
.dashboard-design-tool-row,
.dashboard-design-tool-group,
.dashboard-design-history,
.dashboard-design-filters,
.dashboard-design-filter-items,
.dashboard-design-filter-label {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}
.dashboard-design-context {
  margin-bottom: var(--spacing-md);
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.dashboard-design-context > .ant-btn {
  padding-left: 0;
  color: var(--text-secondary);
}
.dashboard-design-mode {
  padding-left: var(--spacing-md);
  border-left: 1px solid var(--border);
}
.dashboard-design-heading,
.dashboard-design-tool-row {
  justify-content: space-between;
  gap: var(--spacing-lg);
}
.dashboard-design-identity {
  flex: 1;
  min-width: 0;
}
.dashboard-design-title {
  flex-wrap: wrap;
}
.dashboard-design-title h2 {
  margin: 0;
  font-size: calc(var(--table-body-font-size) + var(--spacing-xs));
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.dashboard-design-title > .ant-tag {
  margin: 0;
}
.dashboard-design-description {
  margin: var(--spacing-xs) 0 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dashboard-design-actions {
  flex-shrink: 0;
  flex-wrap: wrap;
}
.dashboard-design-tools {
  margin-bottom: var(--spacing-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--color-bg-container);
}
.dashboard-design-tool-row {
  padding: var(--spacing-md) var(--spacing-lg);
  flex-wrap: wrap;
}
.dashboard-design-tool-group {
  flex-wrap: wrap;
}
.dashboard-design-count {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.dashboard-design-history {
  gap: 0;
  padding-left: var(--spacing-sm);
  border-left: 1px solid var(--border);
}
.dashboard-design-filters {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-top: 1px solid var(--border);
  gap: var(--spacing-md);
}
.dashboard-design-filter-label {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.dashboard-design-filter-items {
  flex: 1;
  min-width: 0;
  flex-wrap: wrap;
}
.dashboard-design-filter-tag {
  max-width: 100%;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-color: transparent;
  background: var(--neutral-bg);
  color: var(--text-secondary);
}
.dashboard-design-help {
  padding-left: var(--spacing-lg);
  margin: 0;
  line-height: 2;
}
.dashboard-design-help-note {
  max-width: calc(var(--spacing-lg) * 20);
  margin: var(--spacing-sm) 0 0;
  color: var(--text-secondary);
}
@media (max-width: 1200px) {
  .dashboard-design-heading {
    align-items: stretch;
    flex-direction: column;
    gap: var(--spacing-md);
  }
}
@media (max-width: 520px) {
  .dashboard-design-filters {
    flex-wrap: wrap;
  }
  .dashboard-design-filter-items {
    flex-basis: 60%;
  }
}
@media (max-width: 520px) {
  .chart-preview-heading {
    flex-direction: column;
  }
  .chart-config-footer-note {
    display: none;
  }
}

.dialog-interaction-section {
  margin-bottom: var(--spacing-lg);
}
.dialog-interaction-help {
  margin-bottom: var(--spacing-lg);
  color: var(--text-secondary);
}
.dialog-drill-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-lg);
}
.dialog-drill-row > .ant-space {
  grid-column: 2 / -1;
  justify-content: flex-end;
}
.dialog-link-row {
  padding-top: var(--spacing-lg);
  border-top: 1px solid var(--border);
}
.dashboard-editor-tile {
  position: relative;
  padding-bottom: var(--spacing-xl);
}
.dashboard-resize {
  position: absolute;
  right: var(--spacing-xs);
  bottom: var(--spacing-xs);
  width: var(--spacing-lg);
  height: var(--spacing-lg);
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--bg-page);
  color: var(--text-secondary);
  cursor: nwse-resize;
  touch-action: none;
}
.dashboard-resize:focus-visible {
  outline: 2px solid var(--brand);
}
@media (max-width: 768px) {
  .dashboard-resize,
  .dashboard-drag {
    display: none;
  }
}
</style>
