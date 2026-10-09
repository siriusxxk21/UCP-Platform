<script setup lang="ts">
import { v4 as uuidv4 } from 'uuid'
import { businessFields, isRelationFieldId } from '@/nocode/business-fields'
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  SearchOutlined,
  SettingOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import '../../management-tables.css'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import {
  NodeKind,
  ViewButton,
  RecordOpenMode,
  ListOverflow,
  type ViewInteraction,
  type ViewListConfig,
  type UiNode,
  type ViewConfig,
  type FormConfig
} from '@/types/nocode/application-ui'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { selectionViewFormNames } from '@/nocode/selection'
import { useResourceCode } from '@/nocode/resource-code'
import { resourceSnapshot } from '@/nocode/application-resource'
import { isDefaultForm, resolveViewForm } from '@/nocode/default-form'
import {
  createObjectForm,
  copyObjectForm,
  formUsage,
  formRemovalReason,
  inheritedFormViews,
  needsDefaultForm,
  setObjectDefaultForm
} from '@/nocode/default-form-config'
import { objectResourceConfig } from '@/nocode/resource-object'
import {
  normalizeViewConfigForEdit,
  prepareViewConfig,
  prepareReportConfig,
  type EditableViewConfig
} from '@/nocode/resource-config'
import { FieldType, MemberState } from '@/types/nocode/enums'
import { businessFieldRules } from '@/nocode/business-field-rules'
import { formDesignModel } from '@/nocode/form-design'
import BusinessDesigner from './BusinessDesigner.vue'
import { internalDetailIds } from '@/nocode/form-detail-layout'
import RelatedFormSettings from './RelatedFormSettings.vue'
import DataViewSettings from './DataViewSettings.vue'
import FormObjectVersionNotice from './FormObjectVersionNotice.vue'
import FormPreview from './FormPreview.vue'
import TinyPageDesigner from './TinyPageDesigner.vue'
import { supportsAdvancedQuery } from '@/nocode/runtime-list'
import { calculationQueryReady, calculationValueField } from '@/nocode/calculation-presentation'
import { useOrderedCalculationStates } from '@/nocode/ordered-calculation'
import ReportConfigEditor from './ReportConfigEditor.vue'
import ApplicationDashboardConfigEditor from './ApplicationDashboardConfigEditor.vue'
import { defaultApplicationDashboard, applicationDashboardMatchesPage } from '@/nocode/application-dashboard'
import type { ApplicationDashboardConfig } from '@/types/nocode/application-dashboard'
import FixedFilterField from './FixedFilterField.vue'
import PageFilterConfig from './PageFilterConfig.vue'
import ViewQueryEditor from './ViewQueryEditor.vue'
import type { ViewQueryOptions } from '@/types/nocode/data-scope'
import { defaultReport, reportDetailId, reportFieldOptions } from '@/nocode/report'
import { reportFieldScope } from '@/nocode/report-presentation'
import { carryReportSources, reportSourcesBlocked, validateReportSources } from '@/nocode/report-sources'
import type { ReportConfig, ReportFilter } from '@/types/nocode/report'
import { getMenuList } from '@/api/system/menu'
import type { Menu } from '@/types/system/menu'
import { nocodePlatformKey } from '@/nocode/platform'
import { platformEntryDirectories } from '@/nocode/application-entry'
import { errorMessage } from '@/nocode/data-center'
import {
  pageNavigation,
  pageNavigationUnavailableReason,
  removePageAndNavigation,
  supportsPageNavigation
} from '@/nocode/application-navigation'
import PageMenuSettings from './PageMenuSettings.vue'
import ApplicationNavigationPreview from './ApplicationNavigationPreview.vue'

const props = defineProps<{
  objects: Record<string, PublishedObject>
  /** 因关联而只读可读、但没有被应用引用的对象：只转给按关系查目标对象字段的表单设计器，不进任何对象下拉。 */
  readableObjects?: Record<string, PublishedObject>
  readOnly: boolean
  applicationId?: string
  synchronizeObject?: (objectId: string) => Promise<PublishedObject>
}>()
const resources = defineModel<ApplicationResource[]>({ required: true })
const { readiness: orderedStates, failure: orderedFailure } = useOrderedCalculationStates(() => props.objects)
const emit = defineEmits<{ change: [] }>()
const platform = inject(nocodePlatformKey, null)
const selectedKind = ref<ResourceKind>(ResourceKind.VIEW)
const kinds = [
  { value: ResourceKind.VIEW, label: '数据视图' },
  { value: ResourceKind.REPORT, label: '统计视图' },
  { value: ResourceKind.REPORT_DASHBOARD, label: '报表看板' },
  { value: ResourceKind.FORM, label: '业务表单' },
  { value: ResourceKind.PAGE, label: '业务页面' }
]
const kindLabel = computed(() => kinds.find(k => k.value === selectedKind.value)?.label || '资源')
const groupOptions = [
  { value: ResourceKind.VIEW, label: '页面与视图' },
  { value: ResourceKind.FORM, label: '业务表单' },
  { value: ResourceKind.REPORT, label: '统计与看板' }
]
const selectedGroup = computed({
  get: () =>
    selectedKind.value === ResourceKind.PAGE
      ? ResourceKind.VIEW
      : selectedKind.value === ResourceKind.REPORT_DASHBOARD
        ? ResourceKind.REPORT
        : selectedKind.value,
  set: (kind: ResourceKind) => {
    selectedKind.value = kind
  }
})
const resourceSearch = ref('')
const visible = computed(() =>
  resources.value.filter(resource => {
    const inGroup =
      selectedGroup.value === ResourceKind.VIEW
        ? resource.kind === ResourceKind.PAGE || resource.kind === ResourceKind.VIEW
        : selectedGroup.value === ResourceKind.REPORT
          ? resource.kind === ResourceKind.REPORT || resource.kind === ResourceKind.REPORT_DASHBOARD
          : resource.kind === ResourceKind.FORM
    return (
      inGroup && `${resource.name} ${resource.code}`.toLowerCase().includes(resourceSearch.value.trim().toLowerCase())
    )
  })
)
const listTitle = computed(() => groupOptions.find(group => group.value === selectedGroup.value)?.label || '页面与视图')
const columns = computed(() => [
  { title: '名称', key: 'name', dataIndex: 'name', width: 190, ellipsis: true },
  { title: '类型', key: 'kind', width: 100 },
  ...(selectedGroup.value === ResourceKind.FORM
    ? [
        { title: '编码', key: 'code', dataIndex: 'code', width: 180, ellipsis: true },
        { title: '绑定', key: 'binding', width: 180, ellipsis: true }
      ]
    : [
        { title: '菜单位置', key: 'navigation', width: 230, ellipsis: true },
        { title: '首页', key: 'home', width: 70 }
      ]),
  {
    title: '操作',
    key: 'action',
    width: selectedGroup.value === ResourceKind.FORM ? 320 : 235,
    fixed: 'right' as const
  }
])
const directories = ref<Menu[]>([]),
  directoriesLoading = ref(false),
  directoryError = ref('')
const canQueryDirectories = computed(() => platform?.hasPermission('system:menu:query') === true)
const menuResource = ref<ApplicationResource>()
let directoryRequest = 0
async function loadDirectories() {
  const request = ++directoryRequest
  directoryError.value = ''
  if (!canQueryDirectories.value) {
    directories.value = []
    directoriesLoading.value = false
    return
  }
  directoriesLoading.value = true
  try {
    const menus = await getMenuList()
    if (request === directoryRequest) directories.value = platformEntryDirectories(menus)
  } catch (cause) {
    if (request === directoryRequest) directoryError.value = errorMessage(cause)
  } finally {
    if (request === directoryRequest) directoriesLoading.value = false
  }
}
watch(() => [props.applicationId, canQueryDirectories.value], loadDirectories, { immediate: true })
onBeforeUnmount(() => directoryRequest++)
function navigationLabel(resource: ApplicationResource): string {
  const entry = pageNavigation(resources.value, resource.id)
  if (!supportsPageNavigation(resource)) return '用于页面组合'
  if (!entry) return '不显示在菜单'
  if (entry.config.navigationVersion !== 2) return '待设置平台目录'
  if (!entry.config.showInMenu) return '不显示在菜单'
  const parent = directories.value.find(directory => directory.id === entry.config.platformParentId)
  return `${parent?.name || '目录待核验'} / ${entry.config.menuName || entry.name}`
}
function applyMenuSettings(updated: ApplicationResource[]) {
  if (props.readOnly || !canQueryDirectories.value) return
  resources.value = updated
  emit('change')
  message.success('已更新菜单设置，请保存并发布应用')
}
function createResource(kind: ResourceKind) {
  selectedKind.value = kind
  edit()
}
const editing = ref<ApplicationResource>(),
  open = ref(false),
  error = ref(''),
  designerKey = ref(0)
const designer = ref<InstanceType<typeof BusinessDesigner>>()
const designerReadyKey = ref(-1)
const creating = ref(false)
// 从列表进入表单设计时保留列表会话；所有表单变更随返回后的列表一起应用，取消不留资源。
const pendingForms = ref<ApplicationResource[]>([])
const parentView = ref<{
  resource: ApplicationResource
  creating: boolean
  filters: Array<{ fieldId: string; value: string }>
  dictionaries: Array<{ fieldId: string; dictionaryId: string }>
  initialResource: string
  initialFilters: string
  binding: 'keep' | 'specified' | 'inherit'
}>()
const effectiveResources = computed(() => [
  ...resources.value.filter(resource => !pendingForms.value.some(pending => pending.id === resource.id)),
  ...pendingForms.value
])
const editorKindLabel = computed(() => kinds.find(kind => kind.value === editing.value?.kind)?.label || kindLabel.value)
const previewResource = ref<ApplicationResource>()
const previewConfig = computed(() => previewResource.value?.config as unknown as FormConfig | undefined)
const codeSuggestion = useResourceCode({
  name: () => editing.value?.name || '',
  kind: () => editing.value?.kind || selectedKind.value,
  enabled: () => creating.value,
  setCode: code => {
    if (editing.value) editing.value.code = code
  }
})
const pageDesigner = ref<InstanceType<typeof TinyPageDesigner>>()
const dashboardEditor = ref<InstanceType<typeof ApplicationDashboardConfigEditor>>()
const canApply = computed(() => {
  if (editing.value?.kind === ResourceKind.FORM)
    return designerReadyKey.value === designerKey.value && !!designer.value?.isReady()
  if (editing.value?.kind === ResourceKind.PAGE)
    return designerReadyKey.value === designerKey.value && !!pageDesigner.value?.isReady()
  // 多个数据来源只用于透视表、汇总表（契约 laneM L1）：切到别的展示方式时不自动删来源，只是不能应用。
  if (editing.value?.kind === ResourceKind.REPORT)
    return !reportSourcesBlocked(editing.value.config as unknown as ReportConfig)
  if (editing.value?.kind === ResourceKind.REPORT_DASHBOARD) return !!dashboardEditor.value?.isReady()
  return true
})
const pageSettingsOpen = ref(false)
const isPageWorkspace = computed(() => editing.value?.kind === ResourceKind.PAGE)
function togglePageFocus() {
  pageDesigner.value?.toggleFocus()
  if (pageDesigner.value?.focusMode) pageSettingsOpen.value = false
}
const designerDirty = ref(false)
const formSettingsOpen = ref(false)
const viewConfigTab = ref('display')
// 固定范围变更会同时影响列表、统计和表单候选，编辑视图时提示仍在使用它的表单。
const viewFormReferences = computed(() =>
  editing.value?.kind === ResourceKind.VIEW ? selectionViewFormNames(resources.value, editing.value.id) : []
)
const layoutChanged = () => {
  if (editing.value?.kind === ResourceKind.FORM && designer.value) return designer.value.hasChanges()
  if (editing.value?.kind === ResourceKind.PAGE && pageDesigner.value) return pageDesigner.value.hasChanges()
  return designerDirty.value
}
let initialResource = ''
let initialFilters = ''
const editorChanged = () =>
  !!open.value &&
  !props.readOnly &&
  (layoutChanged() ||
    JSON.stringify(editing.value) !== initialResource ||
    JSON.stringify([filters.value, filterDictionaries.value]) !== initialFilters ||
    (!parentView.value && pendingForms.value.length > 0))
const changed = () =>
  editorChanged() ||
  (!!parentView.value &&
    (JSON.stringify(parentView.value.resource) !== parentView.value.initialResource ||
      JSON.stringify([parentView.value.filters, parentView.value.dictionaries]) !== parentView.value.initialFilters ||
      pendingForms.value.length > 0))
useUnsavedNavigation(() => !!changed())
function restoreView() {
  const parent = parentView.value
  if (!parent) return
  editing.value = parent.resource
  creating.value = parent.creating
  filters.value = parent.filters
  filterDictionaries.value = parent.dictionaries
  initialResource = parent.initialResource
  initialFilters = parent.initialFilters
  parentView.value = undefined
  designerDirty.value = false
  designerKey.value++
  formSettingsOpen.value = false
  error.value = ''
  viewConfigTab.value = 'interaction'
  codeSuggestion.changeCode(editing.value.code)
}
function finishClose() {
  if (parentView.value) restoreView()
  else {
    open.value = false
    pendingForms.value = []
  }
}
function close() {
  if (editorChanged())
    Modal.confirm({
      title: '放弃尚未应用到草稿的修改？',
      okText: '放弃修改',
      cancelText: '继续设计',
      onOk: () => {
        finishClose()
      }
    })
  else finishClose()
}
const detailPageOptions = computed(() =>
  resources.value
    .filter(r => r.kind === ResourceKind.PAGE && r.config.contextObjectId === editing.value?.config.objectId)
    .map(r => ({ label: r.name, value: r.id }))
)
const objectOptions = computed(() =>
  Object.values(props.objects).map(o => ({ value: o.objectId, label: o.definition.objectName }))
)
const object = computed(() => props.objects[String(editing.value?.config.objectId)]?.definition)
function viewChoices(id: string) {
  const dictionaryId = filterDictionaries.value.find(v => v.fieldId === id)?.dictionaryId
  const items = dictionaryId
    ? (resources.value.find(r => r.id === dictionaryId)?.config.items as
        { code: string; label: string; disabled?: boolean }[] | undefined)
    : object.value?.fieldOptions[id]?.options
  return (items || []).filter(v => !v.disabled).map(v => ({ label: v.label, value: v.code }))
}
function dictionaryScope(binding: { fieldId: string; dictionaryId: string }) {
  const query = editing.value?.config.query as ViewQueryOptions | undefined
  if (!query || !binding.fieldId || !binding.dictionaryId) return
  query.fixed = query.fixed.filter(c => c.fieldId !== binding.fieldId)
  query.fixed.push({
    fieldId: binding.fieldId,
    operator: fields.value.find(f => f.id === binding.fieldId)?.type === FieldType.MULTI_SELECT ? 'containsAny' : 'in',
    value: viewChoices(binding.fieldId).map(v => v.value)
  })
}
async function synchronizeFormObject(objectId: string) {
  if (!props.synchronizeObject || props.readOnly) throw new Error('没有同步对象版本的权限')
  const resource = editing.value
  const updated = await props.synchronizeObject(objectId)
  if (open.value && resource === editing.value && resource?.config.objectId === objectId) {
    // 重新注册新版本的字段物料，同时保留尚未应用到草稿的字段、布局和呈现配置。
    if (designer.value?.isReady()) {
      resource.config.nodes = designer.value.getNodes()
      resource.config.detailIds = internalDetailIds(resource.config.nodes as UiNode[])
      resource.config.detailNodes = designer.value.getDetailNodes()
    }
    designerKey.value++
    message.success('对象引用已同步；新增字段可从“仅未使用”添加。请保存应用草稿。')
  }
  return updated
}
const fields = computed(
  () =>
    (object.value ? businessFields(object.value) : []).filter(
      f => object.value?.fieldOptions[f.id!]?.state !== MemberState.INACTIVE
    ) || []
)
const fieldOptions = computed(() => fields.value.map(f => ({ value: f.id!, label: f.name })))
const queryValueFields = computed(() =>
  fields.value
    .filter(field =>
      calculationQueryReady(
        object.value?.fieldOptions[field.id!],
        orderedStates.value[object.value?.objectId || '']?.[field.id!]
      )
    )
    .map(field => calculationValueField(field, object.value?.fieldOptions[field.id!]))
)
const advancedFieldOptions = computed(() =>
  fields.value
    .filter(
      f =>
        supportsAdvancedQuery(f) &&
        calculationQueryReady(
          object.value?.fieldOptions[f.id!],
          orderedStates.value[object.value?.objectId || '']?.[f.id!]
        )
    )
    .map(f => ({ value: f.id!, label: f.name }))
)
const listConfig = computed(() => editing.value?.config.list as ViewListConfig | undefined)
const defaultAdvanced = computed({
  get: () => listConfig.value?.advancedFieldIds == null,
  set: (value: boolean) => {
    if (listConfig.value) listConfig.value.advancedFieldIds = value ? null : []
  }
})
/** 「内容超出列宽时自动截断」：关＝不写这个键（存量视图的定义不变），开＝ELLIPSIS。 */
const truncateOverflow = computed({
  get: () => listConfig.value?.overflow === ListOverflow.ELLIPSIS,
  set: (value: boolean) => {
    if (!listConfig.value) return
    if (value) listConfig.value.overflow = ListOverflow.ELLIPSIS
    else delete listConfig.value.overflow
  }
})
const widthFields = computed(() =>
  ((editing.value?.config.fieldIds || []) as string[])
    .map(id => fields.value.find(f => f.id === id))
    .filter((f): f is NonNullable<typeof f> => !!f)
)
/** 统计的字段选项（随统计粒度）：固定筛选的字段下拉与取值输入共用。 */
const reportEntries = computed(() =>
  editing.value?.kind === ResourceKind.REPORT
    ? reportFieldOptions(
        String(editing.value.config.objectId),
        props.objects,
        orderedStates.value,
        reportDetailId(editing.value.config as unknown as ReportConfig)
      )
    : []
)
const queryFieldOptions = computed(() =>
  editing.value?.kind === ResourceKind.REPORT
    ? reportEntries.value
    : fields.value
        .filter(
          f =>
            f.type !== FieldType.SUMMARY &&
            f.type !== FieldType.URL &&
            calculationQueryReady(
              object.value?.fieldOptions[f.id!],
              orderedStates.value[object.value?.objectId || '']?.[f.id!]
            )
        )
        .map(f => ({ value: f.id!, label: f.name }))
)
const fieldRules = computed(() =>
  object.value
    ? businessFieldRules(fields.value, object.value.fieldOptions, formDesignModel, true, {
        mode: 'design',
        applicationId: props.applicationId,
        objectId: object.value.objectId,
        relations: object.value.relations
      })
    : []
)
const nodes = computed(() => (editing.value?.config.nodes || []) as UiNode[])
const formOptions = computed(() =>
  effectiveResources.value
    .filter(r => r.kind === ResourceKind.FORM && r.config.objectId === editing.value?.config.objectId)
    .map(r => ({ value: r.id, label: `指定表单：${r.name}${isDefaultForm(r) ? '（默认）' : ''}` }))
)
const viewFormState = computed(() => {
  if (editing.value?.kind !== ResourceKind.VIEW) return { resource: undefined, error: '' }
  try {
    return {
      resource: resolveViewForm(
        effectiveResources.value,
        String(editing.value.config.objectId),
        editing.value.config.formId as string | null
      ),
      error: ''
    }
  } catch (cause) {
    return { resource: undefined, error: cause instanceof Error ? cause.message : String(cause) }
  }
})
const defaultFormLabel = computed(() => {
  if (editing.value?.kind !== ResourceKind.VIEW) return ''
  try {
    const form = resolveViewForm(effectiveResources.value, String(editing.value.config.objectId))
    return form
      ? `沿用默认：${form.name}`
      : creating.value && needsDefaultForm(effectiveResources.value, editing.value, object.value)
        ? '沿用默认：应用到草稿时自动生成'
        : '自动生成（未保存）'
  } catch (cause) {
    return cause instanceof Error ? cause.message : String(cause)
  }
})
const editingFormUsages = computed(() =>
  editing.value?.kind === ResourceKind.FORM ? formUsage(effectiveResources.value, editing.value) : []
)
const defaultFormImpact = computed(() =>
  inheritedFormViews(resources.value, String(editing.value?.config.objectId)).filter(
    resource => resource.id !== editing.value?.id
  )
)
function previewViewForm() {
  if (!object.value || viewFormState.value.error) return
  previewResource.value = resourceSnapshot(
    viewFormState.value.resource || createObjectForm(object.value, effectiveResources.value)
  )
}
function editViewForm(mode: 'edit' | 'copy' | 'new') {
  const view = editing.value
  if (
    !view ||
    view.kind !== ResourceKind.VIEW ||
    !object.value ||
    props.readOnly ||
    (mode !== 'new' && viewFormState.value.error)
  )
    return
  const current = viewFormState.value.resource
  const form =
    mode === 'copy' && current
      ? copyObjectForm(current, effectiveResources.value)
      : mode === 'edit' && current
        ? current
        : createObjectForm(object.value, effectiveResources.value, mode === 'edit')
  parentView.value = {
    resource: view,
    creating: creating.value,
    filters: filters.value,
    dictionaries: filterDictionaries.value,
    initialResource,
    initialFilters,
    binding: mode === 'edit' ? (current ? 'keep' : 'inherit') : 'specified'
  }
  edit(form, true)
  codeSuggestion.changeCode(form.code)
}
function showFormUsage(form: ApplicationResource) {
  const usages = formUsage(resources.value, form)
  Modal.info({
    title: `${form.name} · 使用位置`,
    content: usages.length ? usages.map(resource => resource.name).join('、') : '暂未被其他资源使用'
  })
}
function makeDefault(form: ApplicationResource) {
  if (props.readOnly || isDefaultForm(form)) return
  const views = inheritedFormViews(resources.value, String(form.config.objectId))
  Modal.confirm({
    title: `将“${form.name}”设为默认表单？`,
    content: views.length
      ? `发布后，以下列表将沿用此表单：${views.map(view => view.name).join('、')}。指定其他表单的列表保持原配置。`
      : '后续新建的同对象列表将默认沿用此表单，保存并发布应用后生效。',
    okText: '设为默认',
    cancelText: '取消',
    onOk: () => {
      resources.value = setObjectDefaultForm(resources.value, form.id)
      emit('change')
    }
  })
}
function duplicateForm(form: ApplicationResource) {
  if (props.readOnly) return
  const copy = copyObjectForm(form, resources.value)
  edit(copy)
  codeSuggestion.changeCode(copy.code)
}
const filters = ref<Array<{ fieldId: string; value: string }>>([])
const filterDictionaries = ref<Array<{ fieldId: string; dictionaryId: string }>>([])
const dictionaryOptions = computed(() =>
  resources.value.filter(r => r.kind === ResourceKind.DICTIONARY).map(r => ({ value: r.id, label: r.name }))
)
const actionOptions = computed(() =>
  resources.value
    .filter(r => r.kind === ResourceKind.ACTION && r.config.objectId === editing.value?.config.objectId)
    .map(r => ({ label: r.name, value: r.id }))
)
const buttonOptions = [
  { label: '新增', value: ViewButton.CREATE },
  { label: '导入', value: ViewButton.IMPORT },
  { label: '导出', value: ViewButton.EXPORT },
  { label: '查看', value: ViewButton.VIEW },
  { label: '编辑', value: ViewButton.UPDATE },
  { label: '删除', value: ViewButton.DELETE }
]
const modeOptions = [
  { label: '侧边抽屉', value: RecordOpenMode.DRAWER },
  { label: '弹窗', value: RecordOpenMode.MODAL }
]
function interactionDefaults(): ViewInteraction {
  return {
    buttons: Object.values(ViewButton),
    actionIds: actionOptions.value.map(a => a.value),
    editMode: RecordOpenMode.DRAWER,
    detailMode: RecordOpenMode.DRAWER
  }
}
function objectChanged() {
  if (!editing.value) return
  if (editing.value.kind === ResourceKind.VIEW) pendingForms.value = []
  if (editing.value.kind === ResourceKind.REPORT) {
    // 多个数据来源：附加来源不动（维度对应随来源 1 的维度一并清空），见 carryReportSources。
    editing.value.config = carryReportSources(
      editing.value.config as unknown as ReportConfig,
      defaultReport(String(editing.value.config.objectId))
    ) as unknown as Record<string, unknown>
    filters.value = []
    return
  }
  editing.value.config = objectResourceConfig(editing.value, object.value, interactionDefaults())
  filters.value = []
  filterDictionaries.value = []
  designerKey.value++
}
function changeFormObject(value: unknown) {
  if (!editing.value || value === editing.value.config.objectId || props.readOnly) return
  if (parentView.value || isDefaultForm(editing.value) || editingFormUsages.value.length) {
    error.value = '此表单已用于当前对象，请先替换默认及使用位置，或为新对象新建表单'
    return
  }
  const replace = () => {
    if (!editing.value) return
    editing.value.config.objectId = String(value)
    objectChanged()
  }
  if ((designer.value?.isReady() ? designer.value.getNodes() : nodes.value).length)
    Modal.confirm({
      title: '更换数据对象并重建表单？',
      content: '当前字段、布局和内部明细配置会被替换为新对象的初始表单。',
      okText: '更换并重建',
      cancelText: '保留当前设计',
      onOk: replace
    })
  else replace()
}
function edit(resource?: ApplicationResource, nested = false) {
  if (!nested) {
    pendingForms.value = []
    parentView.value = undefined
  }
  creating.value = !resource
  codeSuggestion.reset()
  error.value = ''
  formSettingsOpen.value = false
  viewConfigTab.value = 'display'
  if (resource) editing.value = resourceSnapshot(resource)
  else {
    const kind = selectedKind.value
    editing.value = {
      id: uuidv4(),
      kind,
      code: '',
      name: '',
      config: {}
    }
    if (kind === ResourceKind.VIEW)
      editing.value.config = {
        objectId: objectOptions.value[0]?.value || '',
        fieldIds: [],
        equal: {},
        sortFieldId: null,
        descending: true,
        pageSize: DEFAULT_PAGE_SIZE,
        formId: null
      }
    if (kind === ResourceKind.REPORT)
      editing.value.config = defaultReport(objectOptions.value[0]?.value || '') as unknown as Record<string, unknown>
    if (kind === ResourceKind.REPORT_DASHBOARD)
      editing.value.config = defaultApplicationDashboard() as unknown as Record<string, unknown>
    if (kind === ResourceKind.FORM)
      editing.value.config = {
        objectId: objectOptions.value[0]?.value || '',
        nodes: [],
        detailIds: [],
        options: { layout: 'vertical', submitText: '保存记录' }
      }
    if (kind === ResourceKind.PAGE)
      editing.value.config = {
        nodes: [],
        contextObjectId: null,
        protocolVersion: 2
      }
    if (kind === ResourceKind.VIEW || kind === ResourceKind.FORM) objectChanged()
  }
  filters.value = Object.entries((editing.value.config.equal || {}) as Record<string, unknown>).map(
    ([fieldId, value]) => ({ fieldId, value: Array.isArray(value) ? JSON.stringify(value) : String(value ?? '') })
  )
  designerKey.value++
  if (editing.value.kind === ResourceKind.PAGE) editing.value.config.filters ||= []
  if (editing.value.kind === ResourceKind.FORM)
    editing.value.config.options ||= { layout: 'vertical', submitText: '保存记录' }
  if (editing.value.kind === ResourceKind.VIEW) {
    editing.value.config = normalizeViewConfigForEdit(
      editing.value.config as unknown as ViewConfig,
      fields.value,
      interactionDefaults()
    ) as unknown as Record<string, unknown>
  }
  initialResource = JSON.stringify(editing.value)
  designerDirty.value = false
  pageSettingsOpen.value = !resource
  open.value = true
  filterDictionaries.value = Object.entries(
    (editing.value.config.filterDictionaries || {}) as Record<string, string>
  ).map(([fieldId, dictionaryId]) => ({ fieldId, dictionaryId }))
  initialFilters = JSON.stringify([filters.value, filterDictionaries.value])
}
function apply() {
  const value = editing.value
  if (!value) return
  error.value = ''
  try {
    if (!value.name.trim()) throw new Error('请填写资源名称')
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(value.code)) {
      if (value.kind === ResourceKind.FORM) formSettingsOpen.value = true
      throw new Error('编码使用小写字母开头、数字和下划线')
    }
    if (effectiveResources.value.some(r => r.id !== value.id && r.code === value.code)) {
      if (value.kind === ResourceKind.FORM) formSettingsOpen.value = true
      throw new Error('资源编码已存在，请修改编码，例如添加 _2 后缀')
    }
    if (value.kind === ResourceKind.FORM) {
      if (!canApply.value || !designer.value) throw new Error('表单设计器尚未就绪，请等待加载完成后再应用')
      designer.value.validate()
      value.config.nodes = designer.value.getNodes()
      value.config.detailIds = internalDetailIds(value.config.nodes as UiNode[])
      value.config.options = { ...(value.config.options as FormConfig['options']), relationLayout: true }
      value.config.detailNodes = Object.fromEntries(
        Object.entries(designer.value.getDetailNodes() || {}).filter(
          ([id, nodes]) => nodes && (value.config.detailIds as string[]).includes(id)
        )
      )
    }
    if (value.kind === ResourceKind.PAGE) {
      if (!canApply.value || !pageDesigner.value) throw new Error('页面设计器尚未就绪，请等待加载完成后再应用')
      value.config.nodes = pageDesigner.value.getNodes()
      value.config.protocolVersion = 2
      value.config.contextObjectId ||= null
      const visit = (nodes: UiNode[]) => {
        for (const node of nodes) {
          if (node.type === NodeKind.REPORT_DASHBOARD) {
            const resource = resources.value.find(resource => resource.id === node.resourceId)
            if (!resource || !applicationDashboardMatchesPage(resource, String(value.config.contextObjectId || '')))
              throw new Error('报表看板须选择与业务页面当前记录对象一致的看板资源')
          }
          visit(node.children || [])
        }
      }
      visit(value.config.nodes as UiNode[])
    }
    if (value.kind === ResourceKind.REPORT_DASHBOARD) {
      if (!dashboardEditor.value) throw new Error('看板配置尚未加载')
      value.config = dashboardEditor.value.getConfig() as unknown as Record<string, unknown>
    }
    if (value.kind === ResourceKind.REPORT) {
      value.config = prepareReportConfig(
        value.config as unknown as ReportConfig,
        reportFieldOptions(
          String(value.config.objectId),
          props.objects,
          orderedStates.value,
          reportDetailId(value.config as unknown as ReportConfig)
        ),
        filters.value,
        reportFieldScope(
          value.config as unknown as ReportConfig,
          props.objects[String(value.config.objectId)]?.definition
        )
      ) as unknown as Record<string, unknown>
      validateReportSources(value.config as unknown as ReportConfig, props.objects, orderedStates.value)
    }
    if (value.kind === ResourceKind.VIEW) {
      value.config = prepareViewConfig(
        value.config as unknown as EditableViewConfig,
        queryValueFields.value,
        filterDictionaries.value
      ) as unknown as Record<string, unknown>
    }
    const entry = pageNavigation(resources.value, value.id)
    if (
      supportsPageNavigation(value) &&
      entry?.config.navigationVersion === 2 &&
      (entry.config.showInMenu || entry.config.defaultHome) &&
      pageNavigationUnavailableReason(value)
    )
      throw new Error('此页面已设置菜单或首页，请先关闭入口后再配置记录上下文')
    const snapshot = resourceSnapshot(value)
    if (parentView.value) {
      pendingForms.value = [...pendingForms.value.filter(form => form.id !== snapshot.id), snapshot]
      if (parentView.value.binding === 'specified') parentView.value.resource.config.formId = snapshot.id
      if (parentView.value.binding === 'inherit') parentView.value.resource.config.formId = null
      restoreView()
      return
    }
    let updated = [...effectiveResources.value]
    if (creating.value && needsDefaultForm(updated, snapshot, object.value))
      updated.push(createObjectForm(object.value!, updated, true))
    const index = updated.findIndex(r => r.id === snapshot.id)
    if (index < 0) updated.push(snapshot)
    else updated = updated.map(r => (r.id === snapshot.id ? snapshot : r))
    resources.value = updated
    pendingForms.value = []
    emit('change')
    open.value = false
    message.success('已更新本地草稿，请保存应用后发布')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    if (isPageWorkspace.value) pageSettingsOpen.value = true
    if (value.kind === ResourceKind.VIEW) viewConfigTab.value = 'display'
  }
}
function remove(id: string) {
  const resource = resources.value.find(resource => resource.id === id)
  if (props.readOnly || !resource) return
  if (resource.kind === ResourceKind.FORM) {
    const reason = formRemovalReason(resources.value, resource)
    if (reason) {
      message.error(reason)
      return
    }
  }
  try {
    resources.value = supportsPageNavigation(resource)
      ? removePageAndNavigation(resources.value, id)
      : resources.value.filter(r => r.id !== id)
    emit('change')
  } catch (cause) {
    message.error(errorMessage(cause))
  }
}
</script>
<template>
  <div class="resources">
    <div class="navigation-heading">
      <h3>页面与导航</h3>
      <p class="hint">管理业务页面，并设置它们在平台菜单中的位置。菜单和首页设置随应用发布生效。</p>
    </div>
    <div class="resource-tools">
      <a-segmented v-model:value="selectedGroup" :options="groupOptions" />
    </div>
    <div class="navigation-layout">
      <div class="navigation-content">
        <OsTablePage
          :key="selectedGroup"
          :title="listTitle"
          class="nocode-embedded-table"
          show-column-settings
          :column-settings-key="'nocode-app-page-navigation-' + selectedGroup"
          resizable
          :scroll="{ x: 'max-content' }"
          :data-source="visible"
          row-key="id"
          :pagination="false"
          :columns="columns"
        >
          <template #actions>
            <a-space wrap>
              <a-input
                v-model:value="resourceSearch"
                aria-label="搜索页面名称"
                placeholder="搜索名称或编码"
                allow-clear
                class="resource-search"
              >
                <template #prefix><SearchOutlined /></template>
              </a-input>
              <template v-if="!readOnly">
                <template v-if="selectedGroup === ResourceKind.VIEW">
                  <a-button type="primary" @click="createResource(ResourceKind.PAGE)">
                    <PlusOutlined />
                    新建页面
                  </a-button>
                  <a-button @click="createResource(ResourceKind.VIEW)">
                    <PlusOutlined />
                    新建数据视图
                  </a-button>
                </template>
                <template v-else-if="selectedGroup === ResourceKind.REPORT">
                  <a-button type="primary" @click="createResource(ResourceKind.REPORT)">
                    <PlusOutlined />
                    新建统计视图
                  </a-button>
                  <a-button @click="createResource(ResourceKind.REPORT_DASHBOARD)">
                    <PlusOutlined />
                    添加报表看板
                  </a-button>
                </template>
                <a-button v-else type="primary" @click="createResource(ResourceKind.FORM)">
                  <PlusOutlined />
                  新建业务表单
                </a-button>
              </template>
            </a-space>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              {{ record.name }}
              <a-tag v-if="isDefaultForm(record)" color="blue">默认</a-tag>
            </template>
            <template v-else-if="column.key === 'kind'">
              {{ kinds.find(kind => kind.value === record.kind)?.label }}
            </template>
            <template v-else-if="column.key === 'navigation'">
              <span :class="{ 'menu-location-secondary': !pageNavigation(resources, record.id)?.config.showInMenu }">
                {{ navigationLabel(record) }}
              </span>
            </template>
            <template v-else-if="column.key === 'home'">
              <a-tag v-if="pageNavigation(resources, record.id)?.config.defaultHome" color="purple">首页</a-tag>
              <span v-else>—</span>
            </template>
            <template v-else-if="column.key === 'binding'">
              {{ objects[record.config.objectId]?.definition.objectName || '自定义布局' }}
            </template>
            <div v-if="column.key === 'action'" class="nocode-table-actions">
              <a-button type="link" @click="edit(record)">
                <EyeOutlined v-if="readOnly" />
                <EditOutlined v-else />
                {{ readOnly ? '查看' : record.kind === ResourceKind.PAGE ? '设计' : '配置' }}
              </a-button>
              <a-button v-if="supportsPageNavigation(record)" type="link" @click="menuResource = record">
                菜单设置
              </a-button>
              <a-button v-if="record.kind === ResourceKind.FORM" type="link" @click="showFormUsage(record)">
                使用位置
              </a-button>
              <template v-if="!readOnly">
                <a-dropdown v-if="record.kind === ResourceKind.FORM">
                  <a-button type="link">更多</a-button>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item key="copy" @click="duplicateForm(record)">复制表单</a-menu-item>
                      <a-menu-item key="default" :disabled="isDefaultForm(record)" @click="makeDefault(record)">
                        设为默认
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
                <a-popconfirm
                  title="从草稿删除资源及对应入口？已发布版本将在下次发布后更新。"
                  @confirm="remove(record.id)"
                >
                  <a-button type="link" danger>
                    <DeleteOutlined />
                    删除
                  </a-button>
                </a-popconfirm>
              </template>
            </div>
          </template>
        </OsTablePage>
        <p v-if="selectedGroup === ResourceKind.FORM" class="hint">
          数据视图可直接使用默认表单，也可在此配置独立表单。表单用于业务操作，不单独显示为菜单。
        </p>
        <p v-if="selectedGroup === ResourceKind.REPORT" class="hint">
          统计视图用于组合业务页面；无记录上下文的报表看板可直接配置菜单入口。
        </p>
      </div>
      <div class="navigation-sidebar">
        <ApplicationNavigationPreview
          :resources="resources"
          :directories="directories"
          :loading="directoriesLoading"
          @settings="menuResource = $event"
        />
        <a-alert v-if="directoryError" type="warning" :message="directoryError" show-icon>
          <template #action><a-button size="small" @click="loadDirectories">重试目录</a-button></template>
        </a-alert>
        <p v-if="!canQueryDirectories" class="hint">目录信息需要平台菜单查询权限。页面设计与现有配置不受影响。</p>
      </div>
    </div>
    <PageMenuSettings
      v-if="menuResource"
      :open="!!menuResource"
      :resource="menuResource"
      :resources="resources"
      :directories="directories"
      :directories-loading="directoriesLoading"
      :directory-error="directoryError"
      :can-query-directories="canQueryDirectories"
      :read-only="readOnly"
      @close="menuResource = undefined"
      @apply="applyMenuSettings"
      @reload="loadDirectories"
    />
    <OsModalForm
      v-if="editing?.kind === ResourceKind.REPORT_DASHBOARD"
      :open="open"
      title="配置报表看板"
      :form-data="editing"
      layout="vertical"
      :disabled="readOnly"
      :width="960"
      destroy-on-close
      :show-footer="!readOnly"
      @ok="apply"
      @cancel="close"
    >
      <template #formItems>
        <a-alert v-if="error" type="error" :message="error" show-icon class="notice" />
        <a-row :gutter="16">
          <a-col :xs="24" :md="12">
            <a-form-item label="名称" required>
              <a-input v-model:value="editing.name" aria-label="资源名称" :maxlength="160" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-item label="编码" required>
              <a-input
                :value="editing.code"
                aria-label="资源编码"
                :maxlength="64"
                placeholder="按名称自动生成，可手动修改"
                @update:value="codeSuggestion.changeCode"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <ApplicationDashboardConfigEditor
          v-if="open"
          :key="designerKey"
          ref="dashboardEditor"
          :model-value="editing.config as unknown as ApplicationDashboardConfig"
          :objects="objects"
          :resources="resources"
          :read-only="readOnly"
          @update:model-value="editing.config = $event as unknown as Record<string, unknown>"
        />
      </template>
      <template #footer>
        <a-button @click="close">取消</a-button>
        <a-button type="primary" :disabled="!canApply" @click="apply">应用到草稿</a-button>
      </template>
    </OsModalForm>
    <a-modal
      v-else
      :open="open"
      :title="isPageWorkspace ? undefined : '配置' + editorKindLabel"
      :width="
        editing?.kind === ResourceKind.PAGE || editing?.kind === ResourceKind.FORM
          ? 'calc(100vw - 48px)'
          : editing?.kind === ResourceKind.REPORT
            ? 'min(1540px, calc(100vw - 48px))'
            : 760
      "
      :style="{ top: '24px' }"
      :destroy-on-close="true"
      :footer="readOnly || isPageWorkspace ? null : undefined"
      @ok="apply"
      @cancel="close"
      :mask-closable="false"
      :wrap-class-name="
        editing?.kind === ResourceKind.FORM
          ? 'nocode-design-workspace nocode-form-workspace'
          : editing?.kind === ResourceKind.PAGE
            ? 'nocode-page-workspace'
            : editing?.kind === ResourceKind.VIEW
              ? 'os-scroll-modal nocode-view-config'
              : 'os-scroll-modal'
      "
      :ok-text="parentView ? '完成并返回列表配置' : '应用到草稿'"
      :ok-button-props="{ disabled: !canApply }"
      :cancel-text="parentView ? '返回列表配置' : '取消'"
    >
      <template v-if="isPageWorkspace" #title>
        <div class="page-workspace-header">
          <div class="page-workspace-identity">
            <span>页面设计</span>
            <span class="page-workspace-name" :title="editing?.name">{{ editing?.name || '未命名业务页面' }}</span>
            <a-tag :color="changed() ? 'orange' : undefined">
              {{ readOnly ? '只读' : changed() ? '未应用' : '草稿' }}
            </a-tag>
          </div>
          <div class="page-workspace-actions">
            <a-button :aria-expanded="pageSettingsOpen" @click="pageSettingsOpen = !pageSettingsOpen">
              {{ pageSettingsOpen ? '收起设置' : '页面设置' }}
            </a-button>
            <a-button :aria-pressed="pageDesigner?.propertiesOpen" @click="pageDesigner?.toggleProperties()">
              节点属性
            </a-button>
            <a-button @click="togglePageFocus">
              {{ pageDesigner?.focusMode ? '恢复面板' : '专注画布' }}
            </a-button>
            <a-button v-if="!readOnly" type="primary" :disabled="!canApply" @click="apply">应用到草稿</a-button>
          </div>
        </div>
      </template>
      <template v-if="editing">
        <a-alert v-if="error" type="error" :message="error" show-icon class="notice" />
        <a-alert v-if="orderedFailure" type="warning" :message="orderedFailure" show-icon class="notice" />
        <a-form
          v-if="editing.kind !== ResourceKind.FORM"
          v-show="!isPageWorkspace || pageSettingsOpen"
          layout="vertical"
          :disabled="readOnly"
          :class="{
            'page-workspace-settings': isPageWorkspace,
            'view-config-form': editing.kind === ResourceKind.VIEW
          }"
        >
          <a-row :gutter="16">
            <a-col :span="8">
              <a-form-item label="名称" required>
                <a-input v-model:value="editing.name" aria-label="资源名称" :maxlength="160" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="编码" required>
                <a-input
                  :value="editing.code"
                  aria-label="资源编码"
                  :maxlength="64"
                  placeholder="按名称自动生成，可手动修改"
                  @update:value="codeSuggestion.changeCode"
                />
              </a-form-item>
            </a-col>
            <a-col
              v-if="[ResourceKind.VIEW, ResourceKind.FORM, ResourceKind.REPORT].some(k => k === editing!.kind)"
              :span="8"
            >
              <a-form-item label="数据对象" required>
                <a-select v-model:value="editing.config.objectId" :options="objectOptions" @change="objectChanged" />
              </a-form-item>
            </a-col>
            <a-col v-if="editing.kind === ResourceKind.PAGE" :span="8">
              <a-form-item label="当前记录对象（详情页必选）">
                <a-select
                  v-model:value="editing.config.contextObjectId"
                  :options="objectOptions"
                  allow-clear
                  placeholder="普通工作台无需选择"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <template v-if="editing.kind === ResourceKind.REPORT">
            <ReportConfigEditor
              :ordered-readiness="orderedStates"
              :application-id="applicationId || ''"
              :fixed-filters="filters"
              :model-value="editing.config as unknown as ReportConfig"
              :objects="objects"
              :resources="resources"
              @update:model-value="editing.config = $event as unknown as Record<string, unknown>"
            />
            <a-divider orientation="left">固定筛选（同时限定统计与明细）</a-divider>
            <div v-for="(filter, index) in filters" :key="index" class="filter">
              <a-select
                v-model:value="filter.fieldId"
                :options="queryFieldOptions"
                placeholder="字段"
                class="report-filter-field"
              />
              <span>等于</span>
              <FixedFilterField
                v-if="reportEntries.some(f => f.value === filter.fieldId)"
                :key="filter.fieldId"
                v-model="filter.value"
                :application-id="applicationId"
                :entry="reportEntries.find(f => f.value === filter.fieldId)!"
                :objects="objects"
              />
              <a-input v-else disabled placeholder="请先选择字段" />
              <a-button @click="filters.splice(index, 1)">移除</a-button>
            </div>
            <a-button @click="filters.push({ fieldId: '', value: '' })">添加固定筛选</a-button>
          </template>
          <PageFilterConfig
            :ordered-readiness="orderedStates"
            v-if="editing.kind === ResourceKind.PAGE"
            :model-value="editing.config.filters as ReportFilter[]"
            :objects="objects"
            :resources="resources"
            @update:model-value="editing.config.filters = $event"
          />
          <a-tabs
            v-if="editing.kind === ResourceKind.VIEW"
            v-model:active-key="viewConfigTab"
            :animated="false"
            class="view-config-tabs"
          >
            <a-tab-pane key="display" tab="显示与查询">
              <a-form-item label="显示字段（按选择顺序排列）" required>
                <a-select
                  v-model:value="editing.config.fieldIds"
                  mode="multiple"
                  show-search
                  option-filter-prop="label"
                  :options="fieldOptions"
                />
              </a-form-item>
              <a-collapse :bordered="false" class="list-column-widths">
                <a-collapse-panel key="widths" header="默认列宽">
                  <div class="list-width-fields">
                    <a-form-item v-for="field in widthFields" :key="field.id" :label="field.name">
                      <a-input-number
                        v-model:value="listConfig!.columnWidths[field.id!]"
                        :aria-label="field.name + '默认列宽'"
                        :min="80"
                        :max="800"
                        :precision="0"
                        placeholder="170"
                        addon-after="px"
                      />
                    </a-form-item>
                  </div>
                </a-collapse-panel>
              </a-collapse>
              <a-form-item label="内容超出列宽时自动截断">
                <a-switch v-model:checked="truncateOverflow" aria-label="内容超出列宽时自动截断" />
                <p class="hint">
                  开启后列宽严格按配置，内容单行显示、超出部分省略，鼠标悬停看全文；关闭时沿用原来的显示（多行文本、富文本换行，其余单行、列随内容变宽）。
                </p>
              </a-form-item>
              <a-divider orientation="left">查询条件</a-divider>
              <a-form-item label="常用查询字段（最多 6 个，按选择顺序展示）">
                <a-select
                  v-model:value="listConfig!.queryFieldIds"
                  mode="multiple"
                  show-search
                  option-filter-prop="label"
                  :options="queryFieldOptions"
                  aria-label="常用查询字段"
                  placeholder="未配置时仍可使用关键词搜索"
                />
              </a-form-item>
              <p class="hint">
                多选对象关系的常用查询按“包含任一选中记录”匹配；多选字典按“包含任一选中值”匹配，其他数组字段按完整数组匹配。多值字段暂不支持高级检索和统计分组，多选对象关系也不支持排序。
              </p>
              <a-form-item label="高级检索使用全部可查询字段">
                <a-switch v-model:checked="defaultAdvanced" />
              </a-form-item>
              <a-form-item v-if="!defaultAdvanced" label="高级检索字段（留空则不显示入口）">
                <a-select
                  v-model:value="listConfig!.advancedFieldIds"
                  mode="multiple"
                  show-search
                  option-filter-prop="label"
                  :options="advancedFieldOptions"
                  aria-label="高级检索字段"
                  placeholder="选择允许组合检索的字段"
                />
              </a-form-item>
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="默认排序">
                    <a-select
                      v-model:value="editing.config.sortFieldId"
                      allow-clear
                      :options="queryFieldOptions.filter(f => !isRelationFieldId(f.value))"
                    />
                  </a-form-item>
                </a-col>
                <a-col :span="6">
                  <a-form-item label="倒序"><a-switch v-model:checked="editing.config.descending" /></a-form-item>
                </a-col>
                <a-col :span="6">
                  <a-form-item label="每页数量">
                    <a-input-number v-model:value="editing.config.pageSize" :min="1" :max="100" />
                  </a-form-item>
                </a-col>
              </a-row>
              <div v-for="(value, id) in editing.config.equal as Record<string, unknown>" :key="id" class="filter">
                <span>保留旧固定范围：{{ fields.find(f => f.id === id)?.name }} = {{ value }}</span>
                <a-button @click="delete (editing.config.equal as Record<string, unknown>)[id]">移除</a-button>
              </div>
              <h4>用户筛选使用的应用字典</h4>
              <p class="muted">选择字典后，将当前可用项设为固定范围，可在下方调整。</p>
              <div v-for="(binding, index) in filterDictionaries" :key="index" class="filter">
                <a-select
                  v-model:value="binding.fieldId"
                  @change="dictionaryScope(binding)"
                  :options="
                    fieldOptions.filter(o =>
                      fields.some(f => f.id === o.value && ['TEXT', 'SELECT', 'MULTI_SELECT'].includes(f.type))
                    )
                  "
                  placeholder="筛选字段"
                  style="width: 220px"
                />
                <a-select
                  v-model:value="binding.dictionaryId"
                  :options="dictionaryOptions"
                  @change="dictionaryScope(binding)"
                  placeholder="应用字典"
                  style="width: 260px"
                />
                <a-button @click="filterDictionaries.splice(index, 1)">移除</a-button>
              </div>
              <a-button @click="filterDictionaries.push({ fieldId: '', dictionaryId: '' })">添加字典筛选</a-button>
              <ViewQueryEditor
                v-if="editing.config.query"
                v-model="editing.config.query as ViewQueryOptions"
                :fields="queryValueFields"
                :choices="viewChoices"
                :application-id="applicationId"
                :object-id="String(editing.config.objectId)"
                :objects="objects"
              />
              <a-alert
                v-if="viewFormReferences.length"
                type="info"
                show-icon
                :message="`该视图固定范围同时限定表单候选：${viewFormReferences.join('、')}。修改范围后请检查这些表单。`"
              />
            </a-tab-pane>
            <a-tab-pane key="composition" tab="主子表与关联列">
              <DataViewSettings
                :model-value="(editing.config as unknown as ViewConfig).composition"
                :object-id="String(editing.config.objectId)"
                :objects="objects"
                :resources="resources"
                @update:model-value="editing.config.composition = $event"
              />
            </a-tab-pane>
            <a-tab-pane key="interaction" tab="操作与页面">
              <a-form-item label="新建 / 编辑使用表单">
                <a-select
                  :value="editing.config.formId || ''"
                  aria-label="列表使用表单"
                  :options="[{ value: '', label: defaultFormLabel }, ...formOptions]"
                  @update:value="editing.config.formId = $event || null"
                />
                <a-alert v-if="viewFormState.error" type="error" :message="viewFormState.error" />
                <a-space wrap class="view-form-actions">
                  <a-button :disabled="!object || !!viewFormState.error" @click="previewViewForm">预览</a-button>
                  <template v-if="!readOnly">
                    <a-button :disabled="!object || !!viewFormState.error" @click="editViewForm('edit')">
                      {{ viewFormState.resource ? '编辑表单' : '生成并编辑默认表单' }}
                    </a-button>
                    <a-button v-if="viewFormState.resource" @click="editViewForm('copy')">复制并使用</a-button>
                    <a-button :disabled="!object" @click="editViewForm('new')">新建并使用</a-button>
                  </template>
                </a-space>
                <p v-if="viewFormState.resource && isDefaultForm(viewFormState.resource)" class="hint">
                  修改此表单会影响所有使用它的位置；只调整本列表时请选择“复制并使用”。
                </p>
                <p v-else-if="!viewFormState.resource && !viewFormState.error" class="hint">
                  {{
                    creating && object && needsDefaultForm(effectiveResources, editing, object)
                      ? '应用到草稿时生成可编辑的默认表单；后续同对象列表自动沿用。'
                      : '当前按对象字段自动显示，尚无可管理的默认表单。生成后随应用发布生效。'
                  }}
                </p>
                <p v-if="!viewFormState.resource && !viewFormState.error && defaultFormImpact.length" class="hint">
                  生成默认表单并发布后，以下列表也会沿用：{{
                    defaultFormImpact.map(resource => resource.name).join('、')
                  }}。
                </p>
              </a-form-item>
              <a-form-item label="查看记录使用详情页面">
                <a-select
                  v-model:value="editing.config.detailPageId"
                  allow-clear
                  :options="detailPageOptions"
                  placeholder="默认记录详情抽屉"
                />
                <p class="hint">先创建“当前记录对象”相同的业务页面，可组合基本信息和相关列表。</p>
              </a-form-item>
              <a-divider orientation="left">列表操作与打开方式</a-divider>
              <a-form-item label="显示的常用按钮">
                <a-checkbox-group
                  v-model:value="(editing.config.interaction as ViewInteraction).buttons"
                  :options="buttonOptions"
                />
              </a-form-item>
              <a-form-item label="允许批量删除">
                <a-switch v-model:checked="listConfig!.batchDelete" />
                <p class="hint">同时开启删除按钮并具备删除权限时生效；只选择当前页记录，逐条校验并反馈结果。</p>
              </a-form-item>
              <a-form-item label="显示的业务动作">
                <a-select
                  v-model:value="(editing.config.interaction as ViewInteraction).actionIds"
                  mode="multiple"
                  show-search
                  option-filter-prop="label"
                  :options="actionOptions"
                  placeholder="选择当前对象的业务动作"
                />
              </a-form-item>
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="新增 / 编辑打开方式">
                    <a-radio-group
                      v-model:value="(editing.config.interaction as ViewInteraction).editMode"
                      :options="modeOptions"
                    />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="查看详情打开方式">
                    <a-radio-group
                      v-model:value="(editing.config.interaction as ViewInteraction).detailMode"
                      :options="modeOptions"
                    />
                  </a-form-item>
                </a-col>
              </a-row>
              <p class="hint">显示的操作还需通过成员与对象共享授权；隐藏按钮不等于禁止 API 操作。</p>
            </a-tab-pane>
          </a-tabs>
        </a-form>
        <template v-if="editing.kind === ResourceKind.FORM">
          <div class="form-design-toolbar">
            <a-form layout="inline" :disabled="readOnly" class="form-design-identity">
              <a-form-item label="名称" required>
                <a-input
                  v-model:value="editing.name"
                  aria-label="资源名称"
                  :maxlength="160"
                  placeholder="填写表单名称"
                />
              </a-form-item>
              <a-form-item label="数据对象" required>
                <a-select
                  :value="editing.config.objectId"
                  aria-label="数据对象"
                  :disabled="!!parentView || isDefaultForm(editing) || !!editingFormUsages.length"
                  :options="objectOptions"
                  @change="changeFormObject"
                />
              </a-form-item>
            </a-form>
            <a-button :aria-expanded="formSettingsOpen" @click="formSettingsOpen = true">
              <SettingOutlined />
              表单设置
            </a-button>
          </div>
          <a-alert
            v-if="editingFormUsages.length"
            type="info"
            show-icon
            class="notice"
            :message="`使用位置：${editingFormUsages.map(resource => resource.name).join('、')}。修改随应用发布生效。`"
          />
          <FormObjectVersionNotice
            v-if="objects[String(editing.config.objectId)]"
            :object="objects[String(editing.config.objectId)]!"
            :read-only="readOnly"
            :synchronize="synchronizeObject ? synchronizeFormObject : undefined"
          />
          <div class="form-design-stage">
            <!-- 设置面板独立开合，不重建设计器，保留画布、选择与撤销记录。 -->
            <BusinessDesigner
              :key="designerKey"
              ref="designer"
              class="form-main-designer"
              form
              :fields="fieldRules"
              :definition="object"
              :application-id="applicationId"
              :objects="objects"
              :readable-objects="readableObjects"
              :form-options="editing.config.options as FormConfig['options']"
              :detail-ids="editing.config.detailIds as string[]"
              :detail-nodes="editing.config.detailNodes as FormConfig['detailNodes']"
              :related-forms="editing.config.relatedForms as FormConfig['relatedForms']"
              :name="editing.name"
              :nodes="nodes"
              :resources="effectiveResources"
              :read-only="readOnly"
              @change="designerDirty = true"
              @ready="designerReadyKey = designerKey"
            />
            <div
              v-if="(editing.config.relatedForms as FormConfig['relatedForms'])?.length"
              class="form-related-overview"
            >
              <strong>关联对象录入区</strong>
              <a-tag
                v-for="related in (editing.config.relatedForms as FormConfig['relatedForms']) || []"
                :key="related.id"
                color="blue"
              >
                独立关联 · {{ related.title }}
              </a-tag>
              <a-button type="link" @click="formSettingsOpen = true">配置关联对象</a-button>
              <p class="hint">关联对象保持独立记录，与当前表单一起保存。内部明细可直接从画布左侧拖入。</p>
            </div>
            <a-drawer
              v-model:open="formSettingsOpen"
              title="表单设置"
              :width="360"
              :get-container="false"
              :root-style="{ position: 'absolute' }"
            >
              <a-form layout="vertical" :disabled="readOnly">
                <a-form-item label="编码" required>
                  <a-input
                    :value="editing.code"
                    aria-label="资源编码"
                    :maxlength="64"
                    placeholder="例如：公司 → form_gs"
                    @update:value="codeSuggestion.changeCode"
                  />
                </a-form-item>
                <a-form-item label="表单布局">
                  <a-radio-group
                    v-model:value="(editing.config.options as FormConfig['options'])!.layout"
                    :options="[
                      { label: '标签在上', value: 'vertical' },
                      { label: '标签在左', value: 'horizontal' }
                    ]"
                  />
                </a-form-item>
                <a-form-item label="允许编辑本表单">
                  <a-switch
                    :checked="!(editing.config.options as FormConfig['options'])?.readOnly"
                    @change="(editing.config.options as FormConfig['options'])!.readOnly = !$event"
                  />
                  <p class="hint">关闭后，本表单用于只读展示；字段权限和记录权限仍分别校验。</p>
                </a-form-item>
                <a-form-item label="提交按钮名称">
                  <a-input
                    v-model:value="(editing.config.options as FormConfig['options'])!.submitText"
                    :maxlength="30"
                  />
                </a-form-item>
                <p class="hint">内部明细已统一到画布中：从左侧“内部明细”拖入，点击表头或列，在右侧配置。</p>
                <RelatedFormSettings
                  v-model="editing.config.relatedForms as FormConfig['relatedForms']"
                  :object-id="String(editing.config.objectId)"
                  :objects="objects"
                  :resources="resources"
                />
              </a-form>
            </a-drawer>
          </div>
        </template>
        <TinyPageDesigner
          v-if="editing.kind === ResourceKind.PAGE"
          :key="designerKey"
          ref="pageDesigner"
          :application-id="applicationId"
          :nodes="nodes"
          :resources="resources"
          :objects="objects"
          :read-only="readOnly"
          :context-object-id="String(editing.config.contextObjectId || '')"
          @change="designerDirty = true"
          @ready="designerReadyKey = designerKey"
        />
      </template>
    </a-modal>
    <a-modal
      :open="!!previewResource"
      title="表单预览"
      width="min(1200px, 94vw)"
      :footer="null"
      destroy-on-close
      @cancel="previewResource = undefined"
    >
      <FormPreview
        v-if="previewResource && previewConfig && objects[previewConfig.objectId]"
        :definition="objects[previewConfig.objectId]!.definition"
        :nodes="previewConfig.nodes"
        :options="previewConfig.options"
        :detail-ids="previewConfig.detailIds"
        :detail-nodes="previewConfig.detailNodes"
        :related-forms="previewConfig.relatedForms"
        :resources="effectiveResources"
        :name="previewResource.name"
        :application-id="applicationId || ''"
        :objects="objects"
      />
    </a-modal>
  </div>
</template>
<style scoped>
.navigation-heading h3 {
  margin: 0 0 8px;
}
.navigation-heading {
  margin-bottom: var(--spacing-lg, 16px);
}
.navigation-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 300px);
  gap: var(--spacing-lg, 16px);
  align-items: start;
}
.menu-location-secondary {
  color: var(--text-secondary, #64748b);
}
.navigation-content,
.navigation-sidebar {
  min-width: 0;
}
.navigation-sidebar {
  display: grid;
  gap: var(--spacing-md, 12px);
}
.resource-search {
  width: 190px;
}
@media (max-width: 1250px) {
  .navigation-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
.view-form-actions {
  margin-top: 8px;
}
.form-related-overview {
  padding: 12px 16px;
  border-top: 1px solid var(--border-color, #e5e7eb);
  flex-shrink: 0;
  max-height: min(180px, 30dvh);
  overflow-y: auto;
}
.form-related-overview strong {
  margin-right: 12px;
}
/* 数据视图复用底座滚动弹窗，基本信息、页签和提交区固定，仅页签内容滚动。 */
:global(.nocode-view-config) {
  --os-modal-height: min(820px, calc(100dvh - 48px));
}
:global(.nocode-view-config .ant-modal-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
}
:global(.nocode-view-config .notice) {
  flex-shrink: 0;
}
.view-config-form {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
.view-config-form > :deep(.ant-row) {
  flex-shrink: 0;
}
.view-config-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}
.view-config-tabs {
  flex: 1;
  min-height: 0;
}
.view-config-tabs :deep(.ant-tabs-content-holder) {
  min-height: 0;
}
.view-config-tabs :deep(.ant-tabs-content) {
  height: 100%;
}
.view-config-tabs :deep(.ant-tabs-tabpane) {
  height: 100%;
  padding: 2px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
/* 沿用现有 Modal 的焦点、关闭和草稿保护，页面设计按视口分配剩余空间。 */
:global(.nocode-page-workspace .ant-modal) {
  top: 0 !important;
  width: 100vw !important;
  max-width: none;
  margin: 0;
  padding: 0;
}
:global(.nocode-page-workspace .ant-modal-content) {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  padding: 0;
  border-radius: 0;
  overflow: hidden;
}
:global(.nocode-page-workspace .ant-modal-header) {
  flex: none;
  margin: 0;
  padding: 10px 52px 10px 16px;
  border-bottom: 1px solid #e5e7eb;
}
:global(.nocode-page-workspace .ant-modal-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.page-workspace-header,
.page-workspace-identity,
.page-workspace-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.page-workspace-header {
  justify-content: space-between;
}
.page-workspace-identity > :not(.page-workspace-name),
.page-workspace-actions {
  flex-shrink: 0;
}
.page-workspace-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 400;
  color: #64748b;
}
.page-workspace-actions {
  gap: 8px;
}
.page-workspace-settings {
  flex: none;
  max-height: 36dvh;
  overflow: auto;
  padding: 12px 16px;
  border-bottom: 1px solid #e5e7eb;
}
.page-workspace-settings :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.page-workspace-settings :deep(.page-filter-config) {
  margin-bottom: 0;
}
@media (max-width: 800px) {
  .page-workspace-header {
    flex-wrap: wrap;
    gap: 8px;
  }
}
:global(.nocode-design-workspace .ant-modal) {
  top: 12px !important;
  width: calc(100vw - 24px) !important;
  max-width: none;
  padding-bottom: 0;
}
:global(.nocode-design-workspace .ant-modal-body) {
  max-height: calc(100vh - 160px);
  overflow-y: auto;
}
:global(.nocode-design-workspace .ant-modal-header) {
  border-bottom: 1px solid #eef0f3;
  padding-bottom: 12px;
}
:global(.nocode-design-workspace .ant-modal-footer) {
  padding-top: 12px;
  border-top: 1px solid #eef0f3;
}
:global(.nocode-form-workspace .ant-modal-content) {
  height: calc(100dvh - 24px);
  display: flex;
  flex-direction: column;
  padding: 16px 20px;
  overflow: hidden;
}
:global(.nocode-form-workspace .ant-modal-body) {
  flex: 1;
  min-height: 0;
  max-height: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
:global(.nocode-form-workspace .ant-modal-header),
:global(.nocode-form-workspace .ant-modal-footer) {
  flex-shrink: 0;
}
:global(.nocode-form-workspace .notice) {
  flex-shrink: 0;
  margin: 0;
}
.form-design-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
}
.form-design-identity {
  flex: 1;
  min-width: 0;
  gap: 8px 16px;
}
.form-design-identity :deep(.ant-form-item) {
  flex: 1;
  min-width: 200px;
  max-width: 420px;
  margin: 0;
}
.form-design-stage {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 主表画布只占明细入口之外的剩余空间，避免其 100% 高度把入口推到裁剪区域。 */
.form-main-designer {
  flex: 1 1 0;
  height: auto;
  min-height: 0;
}
.resource-tools {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
.report-filter-field {
  min-width: 220px;
}
.list-column-widths {
  margin-bottom: 16px;
}
.list-width-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
}
.list-width-fields :deep(.ant-form-item) {
  margin: 0;
}
.hint {
  color: #64748b;
  margin: 16px 0;
}
.danger {
  color: #dc2626;
}
.notice {
  margin-bottom: 16px;
}
.filter {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
</style>
