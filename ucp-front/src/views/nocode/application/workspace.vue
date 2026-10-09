<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  SendOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getUserInfo } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import '../management-tables.css'
import CategoryInput from '../components/CategoryInput.vue'
import CategoryTreePanel from '../components/CategoryTreePanel.vue'
import { managementCategoryLabel } from '@/nocode/management-category'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { formatDateTime } from '@/utils/format'
import type { ObjectRow } from '@/types/nocode/data-center'
import type { ApplicationDetail, ObjectReference, PublishedObject, Release } from '@/types/nocode/application'
import { ApplicationStatus } from '@/types/nocode/application'
import ResourceManager from './components/ResourceManager.vue'
import ApplicationMembers from './components/ApplicationMembers.vue'
import ApplicationObjectPermission from './components/ApplicationObjectPermission.vue'
import ApplicationPublishDialog from './components/ApplicationPublishDialog.vue'
import BusinessConfigManager from './components/BusinessConfigManager.vue'
import LinkageSyncPanel from './components/LinkageSyncPanel.vue'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { createRequestSession } from '@/nocode/request-session'
import { useApplicationDraft } from '@/nocode/application-draft'
import { useLatestObjectVersions } from '@/nocode/application-object-versions'
import { useApplicationObjectFollow } from '@/nocode/application-object-follow'
import { readableObjectsHint, useApplicationReadableObjects } from '@/nocode/application-readable-objects'
import ObjectFollowCell from './components/ObjectFollowCell.vue'
import { applicationPublishBlockReason, applicationRequiresPublishAndEnable } from '@/nocode/application-publish-mode'
import { changedLinkageFields, isLinkageForbidden, linkageFieldKey } from '@/nocode/linkage-sync'

const platform = useNocodePlatform(),
  api = platform.applications,
  route = useRoute(),
  router = useRouter()
const id = computed(() => String(route.query.id || ''))
const applicationDraft = useApplicationDraft(() => id.value, api)
const { detail, draft, dirty, error, loaded, objectIssues } = applicationDraft
const infoLoading = ref(false),
  syncingObject = ref(false)
const loading = computed(() => applicationDraft.loading.value || infoLoading.value),
  busy = computed(() => applicationDraft.submitting.value || syncingObject.value)
useUnsavedNavigation(() => dirty.value)
const objectInfo = reactive<Record<string, PublishedObject>>({})
const latestObjects = useLatestObjectVersions(api)
const objectPage = ref(1)
const objectPageSize = 10
const objectPagination = computed(() => ({
  current: objectPage.value,
  pageSize: objectPageSize,
  total: draft.definition.objects.length,
  showSizeChanger: false,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条`
}))
const visibleObjects = computed(() =>
  draft.definition.objects.slice((objectPage.value - 1) * objectPageSize, objectPage.value * objectPageSize)
)
const referenceRows = computed(() =>
  visibleObjects.value.map(reference => {
    const latest = latestObjects.versions[reference.objectId]
    return {
      ...reference,
      latestVersion: latest?.status === 'ready' ? latest.versionNo : undefined,
      latestError: latest?.status === 'error' ? latest.message : '',
      latestLoading: !latest || latest.status === 'loading',
      needsSync: latest?.status === 'ready' && latest.versionNo > reference.versionNo
    }
  })
)
const sharingObject = ref('')
const objectInfoSession = createRequestSession(),
  syncSession = createRequestSession()
let alive = true
const canEdit = computed(() => loaded.value && !loading.value && platform.hasPermission('nocode:app:update'))
const pickerOpen = ref(false),
  candidates = ref<ObjectRow[]>([]),
  candidateSearch = ref(''),
  picking = ref(false),
  addingObject = ref<string | null>(null),
  pickerError = ref('')
const candidateCategories = ref<string[]>([]),
  candidateCategory = ref<string>(),
  candidateCategoriesLoading = ref(false),
  candidateCategoryError = ref(''),
  candidatePage = ref(1),
  candidatePageSize = ref(10),
  candidateTotal = ref(0)
const candidateCategorySession = createRequestSession()
const candidatePagination = computed(() => ({
  current: candidatePage.value,
  pageSize: candidatePageSize.value,
  total: candidateTotal.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (total: number) => `共 ${total} 条`
}))
const candidateColumns = [
  { title: '对象名称', key: 'objectName', dataIndex: 'objectName', width: 210, ellipsis: true },
  { title: '对象编码', key: 'objectCode', dataIndex: 'objectCode', width: 180, ellipsis: true },
  { title: '数据对象分类', key: 'category', width: 150, ellipsis: true },
  { title: '发布版本', key: 'publishedVersion', width: 100 },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const }
]
const publishOpen = ref(false),
  activeTab = ref('objects')
/** 「自动更新」面板里要突出显示的字段：本次发布新开启或规则变了的。 */
const linkageHighlight = ref<string[]>([])
const restoreOpen = ref(false),
  restoreVersion = ref(0),
  restoreReason = ref(''),
  restoreError = ref('')
const statusOpen = ref(false),
  statusReason = ref(''),
  statusError = ref('')
const statusTarget = computed(() =>
  detail.value?.application.status === ApplicationStatus.ACTIVE ? ApplicationStatus.DISABLED : ApplicationStatus.ACTIVE
)
const recoveryPending = computed(() => !!detail.value?.application.recoveryPending)
const recoveryNeedsEdit = computed(() => !!detail.value?.application.recoveryNeedsEdit)
const publishAndEnable = computed(() => applicationRequiresPublishAndEnable(detail.value?.application))
const publishBlockReason = computed(() =>
  applicationPublishBlockReason(detail.value?.application, {
    publish: platform.hasPermission('nocode:app:publish'),
    manage: platform.hasPermission('nocode:app:manage')
  })
)
const columns = [
  { title: '数据对象', key: 'name', width: 220, ellipsis: true },
  { title: '固定版本', key: 'version', width: 110 },
  { title: '最新发布版本', key: 'latestVersion', width: 160 },
  { title: '物理主表', key: 'table', width: 240, ellipsis: true },
  { title: '操作', key: 'actions', width: 176, fixed: 'right' as const }
]
// 自动跟随：操作会让应用修订号前进，返回的应用详情走工作台现有的接收路径（与发布相同）。
const objectFollow = useApplicationObjectFollow({
  api,
  applicationId: () => id.value,
  dirty: () => dirty.value,
  canEdit: () => canEdit.value,
  suspended: () => !!detail.value && detail.value.application.status !== ApplicationStatus.ACTIVE,
  capture: () => applicationDraft.capturePublish(),
  accept: async value => {
    const requested = id.value
    if (applicationDraft.acceptPublished(value).kind === 'accepted')
      await loadInfo(() => alive && id.value === requested)
    else await load()
  },
  onError: text => {
    error.value = text
  }
})
const { unavailable: followUnavailable, follows: objectFollows, acting: followActing } = objectFollow
// 「自动跟随」列排在「最新发布版本」之后，表格挂载时就在（表格只认挂载时的列，后加的列不显示）；
// 跟随状态读取失败（含后端还没有这个接口）时整列不显示，其余功能照常。
const followColumns = columns.flatMap(column =>
  column.key === 'latestVersion' ? [column, { title: '自动跟随', key: 'follow', width: 240 }] : [column]
)
const objectColumns = computed(() => (followUnavailable.value ? columns : followColumns))
// 因关联而只读可读的对象：只用于下方一行说明，以及表单设计里按关系查目标对象的字段；不是「已引用对象」。
const { summary: readableSummary, objects: readableObjects } = useApplicationReadableObjects({
  api,
  applicationId: () => id.value,
  references: () => draft.definition.objects,
  enabled: () => loaded.value
})
const versionColumns = [
  { title: '版本', key: 'versionNo', dataIndex: 'versionNo', width: 140 },
  { title: '发布说明', key: 'reason', dataIndex: 'reason', width: 320, ellipsis: true },
  { title: '发布人', key: 'creator', dataIndex: 'creator', width: 140 },
  { title: '发布时间', key: 'createTime', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'actions', width: 160, fixed: 'right' as const }
]
// 发布记录独立分页查询；详情接口不再携带全量版本列表。
const releaseRows = ref<Release[]>([]),
  releasePageNo = ref(1),
  releasePageSize = ref(10),
  releaseTotal = ref(0),
  releaseLoading = ref(false),
  releaseError = ref('')
let releaseRequest = 0
const releasePagination = computed(() => ({
  current: releasePageNo.value,
  pageSize: releasePageSize.value,
  total: releaseTotal.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (total: number) => `共 ${total} 条`
}))
function changeReleasePage(pagination: { current?: number; pageSize?: number }) {
  releasePageNo.value = pagination.pageSize === releasePageSize.value ? pagination.current || 1 : 1
  releasePageSize.value = pagination.pageSize || 10
  void loadReleases()
}
async function loadReleases() {
  if (!id.value) return
  const request = ++releaseRequest
  releaseLoading.value = true
  releaseError.value = ''
  try {
    const result = await api.releases(id.value, {
      pageNo: releasePageNo.value,
      pageSize: releasePageSize.value
    })
    if (request !== releaseRequest) return
    releaseRows.value = result.list
    releaseTotal.value = result.total
  } catch (e) {
    if (request !== releaseRequest) return
    releaseRows.value = []
    releaseTotal.value = 0
    releaseError.value = errorMessage(e)
  } finally {
    if (request === releaseRequest) releaseLoading.value = false
  }
}
function refreshReleases() {
  releasePageNo.value = 1
  void loadReleases()
}
async function loadInfo(current: () => boolean) {
  for (const key of Object.keys(objectInfo))
    if (!draft.definition.objects.some(r => r.objectId === key)) delete objectInfo[key]
  for (const reference of [...draft.definition.objects]) {
    try {
      const result = await api.objectVersion(reference.objectId, reference.versionNo)
      if (!current()) return
      if (draft.definition.objects.includes(reference)) objectInfo[reference.objectId] = result
    } catch (e) {
      if (current()) error.value = errorMessage(e)
    }
  }
}
async function load() {
  const request = objectInfoSession.begin()
  const requested = id.value
  const current = () => request() && alive && id.value === requested
  infoLoading.value = true
  try {
    if ((await applicationDraft.load()) && current()) await loadInfo(current)
  } finally {
    if (current()) infoLoading.value = false
  }
}
async function save() {
  if (busy.value) return
  if (!loaded.value || loading.value) {
    error.value = '应用配置尚未加载成功，请先重试加载，避免保存不完整配置。'
    return
  }
  const result = await applicationDraft.save()
  if (result.kind === 'accepted')
    message.success(result.unchanged ? '应用草稿已保存' : '应用草稿已保存，保存期间的新修改尚未保存')
  return result
}
async function designObject(objectId?: string) {
  const navigate = () =>
    router.push({
      path: '/nocode/object/editor',
      query: { ...(objectId ? { id: objectId } : {}), returnApp: id.value }
    })
  if (!dirty.value) {
    await navigate()
    return
  }
  Modal.confirm({
    title: '先保存应用草稿，再进入对象设计？',
    content: '对象与关系仍由数据中心统一管理；发布后返回本应用选择或同步版本。',
    okText: '保存并进入',
    onOk: async () => {
      const result = await save()
      if (result?.kind !== 'accepted' || !result.unchanged) throw new Error(error.value || '草稿保存失败或仍有新修改')
      await navigate()
    }
  })
}
let candidateRequest = 0
let candidateTimer: ReturnType<typeof setTimeout> | undefined
const candidateComposing = ref(false)
function scheduleCandidateSearch() {
  clearTimeout(candidateTimer)
  candidatePage.value = 1
  candidates.value = []
  picking.value = pickerOpen.value
  // 输入一变化即使旧请求失效，避免防抖等待期间旧结果覆盖新关键词。
  candidateRequest++
  if (pickerOpen.value && !candidateComposing.value) candidateTimer = setTimeout(() => void loadCandidates(), 300)
}
function finishCandidateComposition() {
  candidateComposing.value = false
  scheduleCandidateSearch()
}
watch(candidateSearch, scheduleCandidateSearch, { flush: 'sync' })
function selectCandidateCategory(category: string | undefined) {
  candidateCategory.value = category
  candidatePage.value = 1
  clearTimeout(candidateTimer)
  void loadCandidates()
}
function changeCandidatePage(pagination: { current?: number; pageSize?: number }) {
  clearTimeout(candidateTimer)
  candidatePage.value = pagination.pageSize === candidatePageSize.value ? pagination.current || 1 : 1
  candidatePageSize.value = pagination.pageSize || 10
  void loadCandidates()
}
watch(pickerOpen, open => {
  if (!open) {
    clearTimeout(candidateTimer)
    candidateRequest++
    candidateCategorySession.invalidate()
    candidateComposing.value = false
  }
})
onBeforeUnmount(() => {
  alive = false
  objectInfoSession.invalidate()
  syncSession.invalidate()
  clearTimeout(candidateTimer)
  candidateRequest++
  candidateCategorySession.invalidate()
  releaseRequest++
})
async function loadCandidateCategories() {
  const current = candidateCategorySession.begin()
  candidateCategoriesLoading.value = true
  candidateCategoryError.value = ''
  try {
    const result = await platform.dataCenter.categories()
    if (current()) candidateCategories.value = result
  } catch (e) {
    if (current()) candidateCategoryError.value = errorMessage(e)
  } finally {
    if (current()) candidateCategoriesLoading.value = false
  }
}
async function loadCandidates() {
  const request = ++candidateRequest
  picking.value = true
  pickerError.value = ''
  candidates.value = []
  try {
    const result = await platform.dataCenter.objects({
      pageNo: candidatePage.value,
      pageSize: candidatePageSize.value,
      name: candidateSearch.value,
      category: candidateCategory.value,
      status: 'ACTIVE'
    })
    if (request === candidateRequest) {
      candidates.value = result.list.filter(o => o.publishedVersion != null)
      candidateTotal.value = result.total
    }
  } catch (e) {
    if (request === candidateRequest) {
      pickerError.value = errorMessage(e)
      candidateTotal.value = 0
    }
  } finally {
    if (request === candidateRequest) picking.value = false
  }
}
function openPicker() {
  pickerOpen.value = true
  clearTimeout(candidateTimer)
  void loadCandidateCategories()
  void loadCandidates()
}
async function addObject(object: ObjectRow) {
  if (addingObject.value || draft.definition.objects.some(r => r.objectId === object.id)) return
  addingObject.value = object.id
  const requestedApp = id.value
  pickerError.value = ''
  try {
    const selected = await api.objectVersion(object.id)
    if (!alive || requestedApp !== id.value) return
    if (draft.definition.objects.some(r => r.objectId === object.id)) throw new Error('已引用该对象')
    draft.definition.objects.push({
      objectId: selected.objectId,
      versionNo: selected.versionNo,
      checksum: selected.checksum
    })
    objectInfo[object.id] = selected
    latestObjects.accept(selected)
    dirty.value = true
    message.success(`已引用“${object.objectName}”，可继续选择其他对象`)
  } catch (e) {
    if (alive && requestedApp === id.value) pickerError.value = errorMessage(e)
  } finally {
    if (alive && requestedApp === id.value) addingObject.value = null
  }
}
function removeObject(reference: ObjectReference) {
  draft.definition.objects = draft.definition.objects.filter(r => r.objectId !== reference.objectId)
  delete objectInfo[reference.objectId]
  delete latestObjects.versions[reference.objectId]
  dirty.value = true
}
async function syncObject(reference: ObjectReference) {
  if (busy.value) return
  const request = syncSession.begin(),
    requested = id.value
  const current = () => request() && alive && requested === id.value
  syncingObject.value = true
  error.value = ''
  try {
    await synchronizeObject(reference.objectId)
    if (current()) message.success('已同步最新对象版本；保存并发布应用后用于运行')
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  } finally {
    if (current()) syncingObject.value = false
  }
}
async function synchronizeObject(objectId: string) {
  if (!canEdit.value) throw new Error('没有修改应用的权限')
  const reference = draft.definition.objects.find(r => r.objectId === objectId)
  if (!reference) throw new Error('应用尚未引用该对象')
  const requestedApp = id.value
  const latest = await api.objectVersion(objectId)
  if (!alive || requestedApp !== id.value || !draft.definition.objects.includes(reference))
    throw new Error('应用引用已变化，请重新打开表单')
  latestObjects.accept(latest)
  if (latest.versionNo < reference.versionNo) throw new Error('最新发布版本低于固定版本，请重新加载后核对')
  if (latest.versionNo === reference.versionNo && latest.checksum === reference.checksum) return latest
  Object.assign(reference, { versionNo: latest.versionNo, checksum: latest.checksum })
  objectInfo[objectId] = latest
  dirty.value = true
  return latest
}
function openPublish() {
  if (busy.value || dirty.value || !!publishBlockReason.value || !applicationDraft.capturePublish()) return
  publishOpen.value = true
}
function published(value: ApplicationDetail) {
  if (applicationDraft.acceptPublished(value).kind === 'accepted') {
    publishOpen.value = false
    if (activeTab.value === 'versions') refreshReleases()
    void revealLinkageSync()
    void refreshPublishedNavigation()
  }
}
/** 平台二级菜单由发布事务更新；发布成功后再从底座读取当前角色可见菜单。 */
async function refreshPublishedNavigation() {
  try {
    const info = await getUserInfo()
    useUserStore().applyPermissionInfo(info)
  } catch {
    message.warning('应用已发布，当前菜单刷新失败，请刷新页面查看新入口')
  }
}
/**
 * 发布成功后：发布版里有新开启或规则变了的自动更新字段时，打开「自动更新」并突出它们
 * （已有的记录要回填一次才会按新规则更新）。没有这类字段就什么都不做。
 */
async function revealLinkageSync() {
  if (!platform.hasPermission('nocode:app:manage')) return
  const requested = id.value
  try {
    const keys = changedLinkageFields(await api.linkageOverview(requested, 'PUBLISHED')).map(linkageFieldKey)
    if (!alive || requested !== id.value || !keys.length) return
    linkageHighlight.value = keys
    activeTab.value = 'linkage-sync'
  } catch (e) {
    // 不是应用创建者（接口 403）看不了自动更新，不打扰。
    if (alive && requested === id.value && !isLinkageForbidden(e))
      message.warning(`应用已发布。未能确认是否有需要回填的自动更新字段，请到「自动更新」里查看：${errorMessage(e)}`)
  }
}
function showSharing() {
  publishOpen.value = false
  activeTab.value = 'objects'
}
function openRestore(version: number) {
  restoreVersion.value = version
  restoreReason.value = ''
  restoreError.value = ''
  restoreOpen.value = true
}
async function restore() {
  if (busy.value) return
  restoreError.value = ''
  const result = await applicationDraft.restore({ sourceVersion: restoreVersion.value, reason: restoreReason.value })
  if (result.kind === 'accepted') {
    restoreOpen.value = false
    message.success('历史配置已作为新版本发布，草稿和业务数据保留')
    refreshReleases()
    void revealLinkageSync()
    void refreshPublishedNavigation()
  } else if (result.kind === 'failed') restoreError.value = result.message
}
function openStatus() {
  statusReason.value = ''
  statusError.value = ''
  statusOpen.value = true
}
async function changeStatus() {
  if (busy.value) return
  statusError.value = ''
  const result = await applicationDraft.changeStatus({ status: statusTarget.value, reason: statusReason.value })
  if (result.kind === 'accepted') {
    statusOpen.value = false
    message.success('应用状态已更新')
  } else if (result.kind === 'failed') statusError.value = result.message
}
watch(
  id,
  () => {
    pickerOpen.value = false
    syncSession.invalidate()
    syncingObject.value = false
    objectPage.value = 1
    sharingObject.value = ''
    linkageHighlight.value = []
    latestObjects.reset()
    releaseRequest++
    releaseRows.value = []
    releaseTotal.value = 0
    releasePageNo.value = 1
    releaseError.value = ''
    void load()
  },
  { immediate: true }
)
watch(activeTab, tab => {
  if (tab === 'versions') refreshReleases()
})
watch(
  () => draft.definition.objects.length,
  length => {
    objectPage.value = Math.min(objectPage.value, Math.max(1, Math.ceil(length / objectPageSize)))
  }
)
watch(
  () => (loaded.value ? visibleObjects.value.map(object => `${object.objectId}:${object.versionNo}`).join(',') : ''),
  () => {
    if (loaded.value) void latestObjects.refresh(visibleObjects.value)
  },
  { immediate: true }
)
// 应用加载、保存、发布、恢复之后引用和已发布版本都可能变，重新读取跟随状态。
watch(
  () =>
    loaded.value
      ? `${id.value}:${detail.value?.application.revision}:${detail.value?.application.publishedVersion}`
      : '',
  key => {
    if (key) void objectFollow.refresh()
  },
  { immediate: true }
)
</script>

<template>
  <section class="workspace">
    <header class="workspace-header">
      <div class="workspace-toolbar">
        <div class="workspace-identity">
          <a-button aria-label="返回应用列表" @click="router.push('/nocode-app/application')">
            <ArrowLeftOutlined />
          </a-button>
          <div class="workspace-title">
            <h2 :title="detail?.application.name || '应用设计'">{{ detail?.application.name || '应用设计' }}</h2>
            <a-tag
              v-if="loaded"
              :color="
                detail?.application.publishedVersion && detail.application.status === ApplicationStatus.ACTIVE
                  ? 'green'
                  : undefined
              "
            >
              {{
                detail?.application.publishedVersion
                  ? (detail.application.status === ApplicationStatus.ACTIVE ? '运行版本 V' : '最近发布 V') +
                    detail.application.publishedVersion
                  : '尚未发布'
              }}
            </a-tag>
            <a-tag v-if="dirty" color="orange">有未保存修改</a-tag>
            <a-tag v-if="loaded && detail?.application.status === ApplicationStatus.DISABLED" color="orange">
              已停用
            </a-tag>
          </div>
        </div>
        <a-space class="workspace-actions" wrap>
          <a-button v-if="canEdit" type="primary" :loading="busy" @click="save">
            <SaveOutlined />
            保存草稿
          </a-button>
          <a-button
            v-if="detail?.application.publishedVersion && detail.application.status === ApplicationStatus.ACTIVE"
            @click="router.push({ path: '/nocode-app/runtime', query: { id } })"
          >
            运行应用
          </a-button>
          <a-button
            v-if="platform.hasPermission('nocode:app:publish')"
            :disabled="dirty || busy || !loaded || !draft.definition.objects.length || !!publishBlockReason"
            :title="publishBlockReason || (dirty ? '请先保存当前修改' : undefined)"
            @click="openPublish"
          >
            <SendOutlined />
            {{ publishAndEnable ? '发布并启用' : '发布应用' }}
          </a-button>
        </a-space>
      </div>
      <dl v-if="loaded" class="workspace-summary">
        <div>
          <dt>应用编码</dt>
          <dd :title="draft.code">{{ draft.code }}</dd>
        </div>
      </dl>
    </header>
    <a-alert v-if="error" type="error" show-icon :message="error" class="notice" />
    <a-alert
      v-if="loaded && publishAndEnable && !recoveryPending"
      class="notice"
      type="info"
      show-icon
      message="应用已停用，可继续维护配置"
      description="同步对象版本、调整配置并保存后，使用“发布并启用”恢复整个应用。业务数据保留。"
    />
    <a-alert
      v-if="loaded && objectIssues.length"
      class="notice"
      type="warning"
      show-icon
      message="应用固定的数据对象版本与最新结构不兼容，发布前需处理"
    >
      <template #description>
        <ul class="object-issues">
          <li v-for="issue in objectIssues" :key="issue.objectId">
            {{ issue.objectName }}（固定 V{{ issue.versionNo }} · 最新 V{{ issue.latestVersionNo }}）：{{
              issue.messages.join('；')
            }}
          </li>
        </ul>
        <p>草稿可以继续保存；请同步对象版本并调整相关资源后再发布应用。</p>
      </template>
      <template #action><a-button size="small" @click="activeTab = 'objects'">去同步</a-button></template>
    </a-alert>
    <a-alert
      v-if="loaded && recoveryPending"
      class="notice"
      type="warning"
      show-icon
      message="应用已从回收站恢复，当前保持停用"
      :description="
        recoveryNeedsEdit
          ? '请先人工编辑并保存草稿，再点击“发布并启用”。'
          : '草稿已保存。确认配置后点击“发布并启用”，系统校验通过后才开放应用。'
      "
    />
    <a-spin :spinning="loading" class="workspace-body">
      <a-alert
        v-if="!loading && !loaded"
        type="error"
        show-icon
        message="应用配置未加载成功"
        description="请检查网络或登录状态后重试，当前不能修改或保存应用配置。"
      >
        <template #action><a-button @click="load">重新加载</a-button></template>
      </a-alert>
      <a-tabs v-if="loaded" v-model:active-key="activeTab" class="workspace-tabs">
        <a-tab-pane key="objects" tab="已引用对象">
          <OsTablePage
            class="nocode-embedded-table workspace-objects-table"
            :columns="objectColumns"
            :data-source="referenceRows"
            row-key="objectId"
            :pagination="objectPagination"
            :scroll="{ x: 'max-content' }"
            show-column-settings
            column-settings-key="nocode-app-objects-v3"
            resizable
            @change="pagination => (objectPage = pagination.current || 1)"
          >
            <template #actions>
              <a-tooltip title="刷新最新发布版本">
                <a-button aria-label="刷新最新发布版本" @click="latestObjects.refresh(visibleObjects)">
                  <ReloadOutlined />
                </a-button>
              </a-tooltip>
              <a-button v-if="canEdit && platform.hasPermission('nocode:object:create')" @click="designObject()">
                <PlusOutlined />
                新建数据对象
              </a-button>
              <a-button
                v-if="canEdit && platform.hasPermission('nocode:object:query')"
                type="primary"
                @click="openPicker"
              >
                <PlusOutlined />
                引用对象
              </a-button>
            </template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                {{ objectInfo[record.objectId]?.definition.objectName || '对象 ' + record.objectId }}
              </template>
              <template v-else-if="column.key === 'version'">V{{ record.versionNo }}</template>
              <template v-else-if="column.key === 'latestVersion'">
                <span v-if="record.latestLoading" class="muted">读取中…</span>
                <a-tooltip v-else-if="record.latestError" :title="record.latestError + '；点击重试'">
                  <a-button type="link" size="small" @click="latestObjects.refresh([record])">读取失败 · 重试</a-button>
                </a-tooltip>
                <span v-else class="object-version-comparison">
                  <span>V{{ record.latestVersion }}</span>
                  <a-tag v-if="record.needsSync" color="orange">待同步</a-tag>
                  <a-tag v-else-if="record.latestVersion === record.versionNo" color="success">最新</a-tag>
                </span>
              </template>
              <template v-else-if="column.key === 'follow'">
                <ObjectFollowCell
                  :follow="objectFollows[record.objectId]"
                  :readonly="!canEdit || busy"
                  :blocked-reason="objectFollow.blockedReason()"
                  :loading="followActing === record.objectId"
                  :suspended="objectFollow.suspended()"
                  @toggle="objectFollow.toggle(record.objectId, $event)"
                  @run="objectFollow.run(record.objectId)"
                />
              </template>
              <template v-else-if="column.key === 'table'">
                {{ objectInfo[record.objectId]?.definition.tableName || '—' }}
              </template>
              <template v-else-if="column.key === 'actions'">
                <div class="object-reference-actions">
                  <a-tooltip v-if="canEdit && platform.hasPermission('nocode:object:query')" title="设计字段与关系">
                    <a-button
                      type="text"
                      aria-label="设计字段与关系"
                      :disabled="busy"
                      @click="designObject(record.objectId)"
                    >
                      <EditOutlined />
                    </a-button>
                  </a-tooltip>
                  <a-tooltip
                    v-if="canEdit && objectFollow.showSync(record.objectId)"
                    :title="
                      record.needsSync
                        ? '同步最新版本，保存并发布应用后生效'
                        : record.latestError
                          ? '请先重试读取最新发布版本'
                          : record.latestLoading
                            ? '正在读取最新发布版本'
                            : '已是最新发布版本'
                    "
                  >
                    <span>
                      <a-button
                        type="text"
                        aria-label="同步最新版本"
                        :disabled="busy || !record.needsSync"
                        @click="syncObject(record)"
                      >
                        <ReloadOutlined />
                      </a-button>
                    </span>
                  </a-tooltip>
                  <a-tooltip
                    :title="
                      platform.hasPermission('nocode:object:share')
                        ? '配置当前应用的数据权限'
                        : '查看当前应用的数据权限（只读）'
                    "
                  >
                    <a-button
                      type="text"
                      :aria-label="platform.hasPermission('nocode:object:share') ? '配置数据权限' : '查看数据权限'"
                      :disabled="busy"
                      @click="sharingObject = record.objectId"
                    >
                      <SafetyCertificateOutlined />
                    </a-button>
                  </a-tooltip>
                  <a-tooltip v-if="canEdit" title="移除引用">
                    <a-popconfirm
                      title="从应用草稿移除此引用？业务对象和数据保留。"
                      :disabled="busy"
                      @confirm="removeObject(record)"
                    >
                      <a-button type="text" danger aria-label="移除引用" :disabled="busy"><DeleteOutlined /></a-button>
                    </a-popconfirm>
                  </a-tooltip>
                </div>
              </template>
            </template>
            <template #empty><a-empty description="先引用一个已发布的数据对象" /></template>
          </OsTablePage>
          <a-tooltip v-if="readableSummary" :title="readableObjectsHint" placement="topLeft">
            <p class="muted readable-objects">{{ readableSummary }}</p>
          </a-tooltip>
        </a-tab-pane>
        <a-tab-pane key="resources" tab="页面与导航">
          <ResourceManager
            v-model="draft.definition.resources"
            :application-id="id"
            :objects="objectInfo"
            :readable-objects="readableObjects"
            :synchronize-object="synchronizeObject"
            :read-only="!canEdit"
            @change="dirty = true"
          />
        </a-tab-pane>
        <a-tab-pane key="business" tab="业务配置">
          <BusinessConfigManager
            v-model="draft.definition.resources"
            :application-id="id"
            :objects="objectInfo"
            :read-only="!canEdit"
            @change="dirty = true"
          />
        </a-tab-pane>
        <a-tab-pane v-if="platform.hasPermission('nocode:app:manage')" key="members" tab="成员与权限">
          <ApplicationMembers :application-id="id" :objects="objectInfo" @focus-editor="activeTab = 'members'" />
        </a-tab-pane>
        <a-tab-pane key="settings" tab="基本设置">
          <a-form layout="vertical" class="settings" :disabled="!canEdit">
            <a-form-item label="应用名称" required>
              <a-input v-model:value="draft.name" aria-label="应用名称" :maxlength="160" @change="dirty = true" />
            </a-form-item>
            <a-form-item label="应用编码"><a-input :value="draft.code" disabled /></a-form-item>
            <a-form-item label="应用分类">
              <CategoryInput
                v-model="draft.category"
                label="应用分类"
                :load-categories="api.categories"
                :disabled="!canEdit"
                @update:model-value="dirty = true"
              />
            </a-form-item>
            <a-form-item label="说明">
              <a-textarea v-model:value="draft.description" :maxlength="2000" :rows="4" @change="dirty = true" />
            </a-form-item>
          </a-form>
          <div v-if="platform.hasPermission('nocode:app:manage')" class="settings">
            <h3>运行状态：{{ detail?.application.status === ApplicationStatus.ACTIVE ? '已启用' : '已停用' }}</h3>
            <p class="muted">停用后所有成员立即停止访问运行入口，数据和配置保留。有运行中审批时会阻止停用。</p>
            <p v-if="recoveryPending" class="muted">
              此应用从回收站恢复后保持停用，请人工编辑并保存，再使用顶部“发布并启用”。
            </p>
            <a-button v-else :disabled="dirty || busy" @click="openStatus">
              {{ statusTarget === ApplicationStatus.DISABLED ? '停用应用' : '启用应用' }}
            </a-button>
          </div>
        </a-tab-pane>
        <a-tab-pane key="versions" tab="版本与发布">
          <a-alert
            type="info"
            show-icon
            message="运行端使用已发布快照。草稿中的对象引用和页面调整，在发布前不会进入运行端。"
            class="notice"
          />
          <a-alert v-if="releaseError" type="error" :message="releaseError" show-icon class="notice">
            <template #action>
              <a-button size="small" @click="loadReleases">重新查询</a-button>
            </template>
          </a-alert>
          <OsTablePage
            title="发布记录"
            class="nocode-embedded-table"
            show-column-settings
            column-settings-key="nocode-app-releases"
            resizable
            :scroll="{ x: 'max-content' }"
            :columns="versionColumns"
            :data-source="releaseRows"
            :loading="releaseLoading"
            row-key="versionNo"
            :pagination="releasePagination"
            @change="changeReleasePage"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'versionNo'">
                V{{ record.versionNo }}
                <a-tag
                  v-if="record.versionNo === detail?.application.publishedVersion"
                  :color="detail?.application.status === ApplicationStatus.ACTIVE ? 'green' : undefined"
                >
                  {{ detail?.application.status === ApplicationStatus.ACTIVE ? '运行中' : '已停用' }}
                </a-tag>
              </template>
              <template v-else-if="column.dataIndex === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
              <div v-else-if="column.key === 'actions'" class="nocode-table-actions">
                <a-button
                  v-if="platform.hasPermission('nocode:app:publish')"
                  type="link"
                  :disabled="
                    dirty ||
                    busy ||
                    record.versionNo === detail?.application.publishedVersion ||
                    detail?.application.status !== ApplicationStatus.ACTIVE
                  "
                  @click="openRestore(record.versionNo)"
                >
                  <ReloadOutlined />
                  恢复并发布
                </a-button>
              </div>
            </template>
          </OsTablePage>
        </a-tab-pane>
        <a-tab-pane v-if="platform.hasPermission('nocode:app:manage')" key="linkage-sync" tab="自动更新">
          <LinkageSyncPanel
            :application-id="id"
            :published-version="detail?.application.publishedVersion ?? null"
            :active="activeTab === 'linkage-sync'"
            :highlight="linkageHighlight"
          />
        </a-tab-pane>
      </a-tabs>
    </a-spin>
    <OsModalForm
      :open="pickerOpen"
      title="引用已发布对象"
      :width="1120"
      :loading="picking"
      :allow-switch-display="false"
      :resizable="false"
      @cancel="pickerOpen = false"
    >
      <template #formItems>
        <div class="reference-picker">
          <CategoryTreePanel
            :model-value="candidateCategory"
            title="数据对象分类"
            :categories="candidateCategories"
            :loading="candidateCategoriesLoading"
            @update:model-value="selectCandidateCategory"
          />
          <div class="reference-picker-content">
            <a-alert v-if="candidateCategoryError" type="error" :message="candidateCategoryError" show-icon>
              <template #action><a-button size="small" @click="loadCandidateCategories">重试分类</a-button></template>
            </a-alert>
            <a-alert v-if="pickerError" type="error" :message="pickerError" show-icon>
              <template #action><a-button size="small" @click="loadCandidates">重新查询</a-button></template>
            </a-alert>
            <OsTablePage
              class="reference-picker-table"
              :columns="candidateColumns"
              :data-source="candidates"
              :loading="picking"
              :pagination="candidatePagination"
              :scroll="{ x: 800, y: '100%' }"
              row-key="id"
              resizable
              show-column-settings
              column-settings-key="nocode-application-reference-picker"
              @change="changeCandidatePage"
            >
              <template #title>
                <a-input
                  v-model:value="candidateSearch"
                  aria-label="查询可引用对象"
                  placeholder="输入对象名称自动搜索"
                  allow-clear
                  @compositionstart="candidateComposing = true"
                  @compositionend="finishCandidateComposition"
                />
              </template>
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'category'">
                  <span :title="managementCategoryLabel(record.category)">
                    {{ managementCategoryLabel(record.category) }}
                  </span>
                </template>
                <template v-else-if="column.key === 'publishedVersion'">V{{ record.publishedVersion }}</template>
                <a-button
                  v-else-if="column.key === 'actions'"
                  type="link"
                  :disabled="!!addingObject || draft.definition.objects.some(r => r.objectId === record.id)"
                  :loading="addingObject === record.id"
                  @click="addObject(record)"
                >
                  {{ draft.definition.objects.some(r => r.objectId === record.id) ? '已引用' : '引用' }}
                </a-button>
              </template>
            </OsTablePage>
          </div>
        </div>
      </template>
      <template #footer>
        <div class="picker-complete">
          <span>当前已引用 {{ draft.definition.objects.length }} 个对象，可连续添加。</span>
          <a-button type="primary" @click="pickerOpen = false">完成选择</a-button>
        </div>
      </template>
    </OsModalForm>
    <ApplicationObjectPermission
      v-if="sharingObject"
      :key="id + ':' + sharingObject"
      :application-id="id"
      :application-name="detail?.application.name || draft.name"
      :object-id="sharingObject"
      :object="objectInfo[sharingObject]"
      @closed="sharingObject = ''"
    />
    <ApplicationPublishDialog
      :open="publishOpen"
      :detail="detail"
      :objects="objectInfo"
      @cancel="publishOpen = false"
      @published="published"
      @show-sharing="showSharing"
    />
    <OsModalForm
      :open="restoreOpen"
      :title="'恢复 V' + restoreVersion + ' 的配置'"
      :loading="busy"
      layout="vertical"
      @ok="restore"
      @cancel="restoreOpen = false"
    >
      <template #formItems>
        <a-alert
          class="notice"
          type="info"
          show-icon
          message="检查历史配置与当前对象结构的兼容性，通过后生成新发布版本。当前草稿、业务记录、编号和成员授权保持不变。"
        />
        <a-alert v-if="restoreError" class="notice" type="error" show-icon :message="restoreError" />
        <a-form-item label="恢复说明" required>
          <a-textarea v-model:value="restoreReason" aria-label="恢复说明" :maxlength="900" :rows="3" />
        </a-form-item>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="statusOpen"
      :title="statusTarget === ApplicationStatus.DISABLED ? '停用应用' : '启用应用'"
      :loading="busy"
      layout="vertical"
      @ok="changeStatus"
      @cancel="statusOpen = false"
    >
      <template #formItems>
        <a-alert v-if="statusError" class="notice" type="error" show-icon :message="statusError" />
        <a-form-item label="操作说明" required>
          <a-textarea v-model:value="statusReason" aria-label="操作说明" :maxlength="1000" :rows="3" />
        </a-form-item>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.workspace {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.workspace-header {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.workspace-toolbar,
.workspace-identity,
.workspace-title {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.workspace-toolbar {
  justify-content: space-between;
}
.workspace-identity {
  flex: 1;
}
.workspace-identity > .ant-btn,
.workspace-actions {
  flex-shrink: 0;
}
.workspace-title {
  flex-wrap: wrap;
  gap: 6px 12px;
}
.workspace-title h2 {
  max-width: 480px;
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 18px;
  line-height: 28px;
}
.workspace-title :deep(.ant-tag) {
  margin-inline-end: 0;
}
.workspace-summary {
  margin: 0;
  font-size: 13px;
  line-height: 22px;
}
.workspace-summary > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.workspace-summary dt {
  flex-shrink: 0;
  color: var(--ant-color-text-secondary, #7b8492);
  font-size: 12px;
}
.workspace-summary dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workspace-body,
.workspace-body :deep(> .ant-spin-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.workspace-tabs {
  flex: 1;
  min-width: 0;
  padding: 0 16px 16px;
  border: 1px solid var(--ant-color-border-secondary, #e5e7eb);
  border-radius: 8px;
  background: var(--ant-color-bg-container, #fff);
}
.workspace-tabs :deep(> .ant-tabs-nav) {
  margin-bottom: 12px;
}
.workspace-objects-table :deep(> .os-table-page__table) {
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.workspace-objects-table :deep(> .os-table-page__table > .ant-card-head) {
  min-height: 32px;
  margin-bottom: 8px;
  padding: 0;
  border: 0;
  background: transparent;
}
.workspace-objects-table :deep(.ant-card-head-wrapper) {
  min-height: 32px;
  gap: 12px;
}
.workspace-objects-table :deep(.ant-card-head-title),
.workspace-objects-table :deep(.ant-card-extra),
.workspace-objects-table :deep(> .os-table-page__table > .ant-card-body) {
  padding: 0;
}
.object-version-comparison,
.object-reference-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}
.object-reference-actions {
  gap: 4px;
}
.object-reference-actions :deep(.ant-btn:not(:disabled):not(.ant-btn-dangerous)) {
  color: var(--primary-color, #4c46e6);
}
.muted {
  color: #64748b;
}
.readable-objects {
  margin: 12px 0 0;
  font-size: 12px;
}
.object-issues {
  margin: 0 0 4px;
  padding-left: 18px;
}
.object-issues + p {
  margin: 0;
}
.notice {
  margin: 16px 0;
}
.settings {
  max-width: 680px;
}
.picker-complete {
  display: flex;
  width: 100%;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  background: var(--bg-container, #fff);
}
.picker-complete span {
  color: var(--text-secondary, #64748b);
}
.reference-picker {
  display: flex;
  height: min(520px, calc(100dvh - 210px));
  min-height: 0;
  overflow: hidden;
  gap: 16px;
}
.reference-picker > .nocode-category-panel {
  flex-basis: 200px;
  width: 200px;
  border: 1px solid var(--ant-color-border-secondary, #e5e7eb);
}
.reference-picker-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 12px;
}
.reference-picker-content > :not(.reference-picker-table) {
  flex-shrink: 0;
}
.reference-picker-table {
  flex: 1;
  min-height: 0;
  height: auto;
}
.reference-picker-table :deep(.ant-table-body) {
  overscroll-behavior: contain;
}
.reference-picker-table :deep(.ant-card-body) {
  padding: 0 12px 8px;
}
@media (max-width: 700px) {
  .reference-picker {
    gap: 8px;
  }
  .reference-picker > .nocode-category-panel {
    flex-basis: 150px;
    width: 150px;
  }
}
.danger {
  color: #dc2626;
}
@media (max-width: 900px) {
  .workspace-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .workspace-actions {
    align-self: flex-end;
  }
}
</style>
