<script setup lang="ts">
/**
 * 网盘「业务文件」浏览入口
 *
 * 单业务入口：应用入口沿用发布版本与运行授权，数据维护入口沿用对象管理授权，切换入口整体重载。
 * 浏览位置由规则版本与面包屑承载，目录与文件都在当前入口的完整授权链内读取；
 * 收藏与最近访问只写当前用户的标记，列表仍按入口授权重新过滤。
 * 深链与「在网盘中查看」共用同一位置身份，由服务端重新解析导航链后逐级下钻。
 */
import { computed, defineAsyncComponent, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { TableColumnType } from 'ant-design-vue'
import {
  DownloadOutlined,
  EyeOutlined,
  FileOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  LinkOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PaperClipOutlined,
  ReloadOutlined,
  SearchOutlined,
  StarFilled,
  StarOutlined
} from '@ant-design/icons-vue'
import { businessPreviewableType, downloadBusinessBlob, openBusinessBlob } from '@/nocode/business-file-content'
import {
  BUSINESS_DIRECTORY_KINDS,
  BUSINESS_MAINTENANCE_ENTRY,
  businessApplicationId,
  businessCrumbTrail,
  businessDeepLink,
  businessDirectoryKey,
  businessEntryOptions,
  businessFavoriteKey,
  businessFavoriteKeys,
  businessFileAccessible,
  businessFileLocation,
  businessFileSource,
  businessLabelNotice,
  businessLocation,
  businessMarkType,
  businessRecordTarget,
  businessVersionOptions,
  splitLocatedPath
} from '@/nocode/business-file-browse'
import type { BusinessDeepLink, BusinessView } from '@/nocode/business-file-browse'
import { errorMessage } from '@/nocode/data-center'
import { useNocodePlatform } from '@/nocode/platform'
import type { ApplicationRow } from '@/types/nocode/application'
import type { BusinessFileDirectory, BusinessFileEntry } from '@/types/nocode/business-file'
import { formatDateTime, formatFileSize } from '@/utils/format'
import BusinessFileNavigation from './components/BusinessFileNavigation.vue'
import {
  businessNavigationSelection,
  type BusinessNavigationEntry,
  type BusinessNavigationSelection
} from '@/nocode/business-file-navigation'
import './drive-list.css'
import './business.css'

defineOptions({ name: 'DriveBusiness' })

const PAGE_SIZE = 20

const platform = useNocodePlatform()
const route = useRoute()
const router = useRouter()

const entries = ref<ApplicationRow[]>([])
const entryKey = ref<string>()
const navigationEntries = ref<BusinessNavigationEntry[]>([])
const navigationCollapsed = ref(false)
const spaceId = ref<string>()
const versionNodes = ref<BusinessFileDirectory[]>([])
const ruleVersion = ref<number>()
const crumbs = ref<BusinessFileDirectory[]>([])
const view = ref<BusinessView>('browse')
const section = ref<'files' | 'folders'>('files')
// 文件夹视图要用网盘的文件浏览器（体积大），切到它时才加载；只看附件时不加载
const DriveWorkspace = defineAsyncComponent(() => import('./components/DriveWorkspace.vue'))
const search = ref('')
const highlightEntryId = ref<string>()

const directories = ref<BusinessFileDirectory[]>([])
const files = ref<BusinessFileEntry[]>([])
const favoriteIds = ref<Set<string>>(new Set())

const entryLoading = ref(false)
const navigationLoading = ref(false)
const dirLoading = ref(false)
const fileLoading = ref(false)
const versionLoading = ref(false)

const dirPagination = reactive({ current: 1, pageSize: PAGE_SIZE, total: 0 })
const filePagination = reactive({ current: 1, pageSize: PAGE_SIZE, total: 0 })

let dirRequest = 0
let fileRequest = 0
let versionRequest = 0
let favoriteRequest = 0
let contextRequest = 0
let navigationRequest = 0
const entryRequests = new Map<string, number>()
let mounted = false
let changingSpace = false

const canMaintenance = computed(
  () => platform.hasPermission('nocode:object:query') && platform.hasPermission('nocode:object:manage')
)
const entryOptions = computed(() => businessEntryOptions(entries.value, canMaintenance.value))
const applicationId = computed(() => businessApplicationId(entryKey.value))
const currentEntry = computed(() => navigationEntries.value.find(item => item.key === entryKey.value))
const space = computed(() => currentEntry.value?.spaces.find(item => item.objectId === spaceId.value))
const versionOptions = computed(() => businessVersionOptions(versionNodes.value))
const location = computed(() => businessLocation(ruleVersion.value, crumbs.value))
const crumbTrail = computed(() => (space.value ? businessCrumbTrail(space.value, ruleVersion.value, crumbs.value) : []))
const atFieldLeaf = computed(() => !!location.value.fieldId)
const showDirectories = computed(() => view.value === 'browse' && !!spaceId.value && !atFieldLeaf.value)

const directoryColumns: TableColumnType[] = [
  { title: '名称', dataIndex: 'label', key: 'label', width: 420, align: 'left' as const, ellipsis: true },
  { title: '类型', dataIndex: 'kind', key: 'kind', width: 110 },
  { title: '文件数', dataIndex: 'fileCount', key: 'fileCount', width: 100 },
  { title: '大小', dataIndex: 'totalSize', key: 'totalSize', width: 120 },
  { title: '操作', key: 'action', width: 220, fixed: 'right' as const }
]

const fileColumns: TableColumnType[] = [
  { title: '文件名', dataIndex: 'name', key: 'name', width: 320, align: 'left' as const, ellipsis: true },
  { title: '大小', dataIndex: 'size', key: 'size', width: 110 },
  {
    title: '所属业务',
    dataIndex: 'recordLabel',
    key: 'recordLabel',
    width: 220,
    align: 'left' as const,
    ellipsis: true
  },
  { title: '附件来源', key: 'source', width: 200, ellipsis: true },
  { title: '提交人', dataIndex: 'submitter', key: 'submitter', width: 140, ellipsis: true },
  { title: '上传时间', dataIndex: 'uploadedAt', key: 'uploadedAt', width: 170 },
  { title: '操作', key: 'action', width: 340, fixed: 'right' as const }
]

const isFavorite = (item: BusinessFileEntry) => favoriteIds.value.has(businessFavoriteKey(item.entryId))

/** 一次刷新重取所有入口；独立鉴权且最多四路请求，失败入口不阻断其他应用。 */
async function loadNavigation(link?: BusinessDeepLink) {
  const request = ++navigationRequest
  const previous = { entryKey: entryKey.value, objectId: spaceId.value }
  resetLocation()
  const context = contextRequest
  entryKey.value = undefined
  spaceId.value = undefined
  navigationEntries.value = []
  navigationLoading.value = true
  entryLoading.value = true
  try {
    const applications = await platform.runtime.mine()
    if (request !== navigationRequest) return
    entries.value = applications
  } catch (error) {
    if (request !== navigationRequest) return
    entries.value = []
    message.error(errorMessage(error))
  } finally {
    if (request === navigationRequest) entryLoading.value = false
  }
  if (request !== navigationRequest) return
  navigationEntries.value = [
    ...entryOptions.value.filter(entry => entry.value !== BUSINESS_MAINTENANCE_ENTRY),
    ...entryOptions.value.filter(entry => entry.value === BUSINESS_MAINTENANCE_ENTRY)
  ].map(entry => ({
    key: entry.value,
    label: entry.label,
    spaces: [],
    loading: true
  }))
  const queue = navigationEntries.value.slice()
  await Promise.all(
    Array.from({ length: Math.min(4, queue.length) }, async () => {
      while (queue.length) {
        const item = queue.shift()
        if (!item || request !== navigationRequest) return
        await loadNavigationEntry(item.key, request)
      }
    })
  )
  if (request !== navigationRequest) return
  navigationLoading.value = false
  if (context !== contextRequest) return
  const preferred = link ? link.applicationId || BUSINESS_MAINTENANCE_ENTRY : previous.entryKey || entries.value[0]?.id
  const selection =
    businessNavigationSelection(navigationEntries.value, preferred, link?.objectId || previous.objectId) ||
    (!link ? businessNavigationSelection(navigationEntries.value, preferred) : undefined)
  if (selection) await selectSpace(selection, link)
  else if (link) message.warning('定位的业务对象不在当前可访问范围内，请从左侧重新选择')
}

async function loadNavigationEntry(key: string, generation = navigationRequest) {
  const entry = navigationEntries.value.find(item => item.key === key)
  if (!entry) return
  const request = (entryRequests.get(key) ?? 0) + 1
  entryRequests.set(key, request)
  entry.loading = true
  entry.error = undefined
  try {
    const spaces = await platform.bizFiles.spaces(businessApplicationId(key))
    if (generation !== navigationRequest || request !== entryRequests.get(key)) return
    entry.spaces = spaces
  } catch (error) {
    if (generation !== navigationRequest || request !== entryRequests.get(key)) return
    entry.spaces = []
    entry.error = errorMessage(error)
  } finally {
    if (generation === navigationRequest && request === entryRequests.get(key)) entry.loading = false
  }
}

async function retryEntry(key: string) {
  const generation = navigationRequest
  const context = contextRequest
  await loadNavigationEntry(key, generation)
  if (generation !== navigationRequest || context !== contextRequest || spaceId.value) return
  const selection = businessNavigationSelection(navigationEntries.value, key)
  if (selection) await selectSpace(selection)
}

/** 规则版本清单：服务端按当前/历史标注，未接入规则的对象没有版本节点 */
async function loadVersions() {
  if (!spaceId.value) {
    versionNodes.value = []
    return
  }
  const request = ++versionRequest
  versionLoading.value = true
  try {
    const page = await platform.bizFiles.directories({
      applicationId: applicationId.value,
      objectId: spaceId.value,
      pageNo: 1,
      pageSize: 100
    })
    if (request !== versionRequest) return
    versionNodes.value = page.list
  } catch {
    if (request === versionRequest) versionNodes.value = []
  } finally {
    if (request === versionRequest) versionLoading.value = false
  }
}

async function loadDirectories() {
  const request = ++dirRequest
  if (!spaceId.value || view.value !== 'browse' || atFieldLeaf.value) {
    dirLoading.value = false
    directories.value = []
    dirPagination.total = 0
    return
  }
  dirLoading.value = true
  try {
    const page = await platform.bizFiles.directories({
      applicationId: applicationId.value,
      objectId: spaceId.value,
      ruleVersion: location.value.ruleVersion,
      groupKeys: location.value.groupKeys.length ? location.value.groupKeys : undefined,
      recordId: location.value.recordId,
      detailId: location.value.detailId,
      rowId: location.value.rowId,
      pageNo: dirPagination.current,
      pageSize: dirPagination.pageSize
    })
    if (request !== dirRequest) return
    directories.value = page.list
    dirPagination.total = page.total
  } catch (error) {
    if (request !== dirRequest) return
    directories.value = []
    dirPagination.total = 0
    message.error(errorMessage(error))
  } finally {
    if (request === dirRequest) dirLoading.value = false
  }
}

async function loadFiles() {
  if (!spaceId.value) {
    files.value = []
    filePagination.total = 0
    return
  }
  const mode = view.value
  const request = ++fileRequest
  fileLoading.value = true
  try {
    const page =
      mode === 'browse'
        ? await platform.bizFiles.files({
            applicationId: applicationId.value,
            objectId: spaceId.value,
            ruleVersion: location.value.ruleVersion,
            groupKeys: location.value.groupKeys.length ? location.value.groupKeys : undefined,
            recordId: location.value.recordId,
            detailId: location.value.detailId,
            rowId: location.value.rowId,
            fieldId: location.value.fieldId,
            search: search.value.trim() || undefined,
            pageNo: filePagination.current,
            pageSize: filePagination.pageSize
          })
        : await platform.bizFiles.markedFiles({
            applicationId: applicationId.value,
            objectId: spaceId.value,
            markType: businessMarkType(mode)
          })
    if (request !== fileRequest) return
    files.value = page.list
    filePagination.total = page.total
  } catch (error) {
    if (request !== fileRequest) return
    files.value = []
    filePagination.total = 0
    message.error(errorMessage(error))
  } finally {
    if (request === fileRequest) fileLoading.value = false
  }
}

async function loadFavorites() {
  const request = ++favoriteRequest
  if (!spaceId.value) {
    favoriteIds.value = new Set()
    return
  }
  try {
    const page = await platform.bizFiles.markedFiles({
      applicationId: applicationId.value,
      objectId: spaceId.value,
      markType: 'FAVORITE'
    })
    if (request === favoriteRequest) favoriteIds.value = businessFavoriteKeys(page.list)
  } catch {
    /* 收藏状态读取失败不阻断浏览与内容操作 */
  }
}

function resetLocation() {
  contextRequest++
  dirRequest++
  fileRequest++
  versionRequest++
  favoriteRequest++
  dirLoading.value = false
  fileLoading.value = false
  versionLoading.value = false
  crumbs.value = []
  ruleVersion.value = undefined
  search.value = ''
  highlightEntryId.value = undefined
  directories.value = []
  files.value = []
  versionNodes.value = []
  favoriteIds.value = new Set()
  dirPagination.current = 1
  dirPagination.total = 0
  filePagination.current = 1
  filePagination.total = 0
}

async function reload() {
  await Promise.all([view.value === 'browse' ? loadDirectories() : Promise.resolve(), loadFiles()])
}

/** 叶子身份包含入口与对象；切换即失效旧内容，异步定位不得回写到新入口。 */
async function selectSpace(selection: BusinessNavigationSelection, link?: BusinessDeepLink) {
  const entry = navigationEntries.value.find(item => item.key === selection.entryKey)
  if (!entry?.spaces.some(item => item.objectId === selection.objectId)) return
  resetLocation()
  const context = contextRequest
  entryKey.value = selection.entryKey
  spaceId.value = selection.objectId
  changingSpace = true
  view.value = 'browse'
  changingSpace = false
  ruleVersion.value = space.value?.currentRuleVersion ?? undefined
  if (link) {
    try {
      const path = await platform.bizFiles.locate({
        applicationId: link.applicationId,
        objectId: link.objectId,
        recordId: link.recordId,
        detailId: link.detailId,
        rowId: link.rowId,
        fieldId: link.fieldId,
        entryId: link.entryId
      })
      if (context !== contextRequest) return
      const located = splitLocatedPath(path)
      if (located.ruleVersion != null) ruleVersion.value = located.ruleVersion
      crumbs.value = located.crumbs
      highlightEntryId.value = link.entryId
    } catch (error) {
      if (context !== contextRequest) return
      message.warning(`定位的附件已不可访问：${errorMessage(error)}`)
    }
  }
  if (context !== contextRequest) return
  await Promise.all([loadVersions(), loadDirectories(), loadFiles(), loadFavorites()])
}

async function onVersionChange() {
  crumbs.value = []
  highlightEntryId.value = undefined
  dirPagination.current = 1
  filePagination.current = 1
  await reload()
}

function enterDirectory(item: BusinessFileDirectory) {
  if (item.kind === 'VERSION') {
    ruleVersion.value = item.ruleVersion ?? undefined
    crumbs.value = []
  } else {
    crumbs.value = [...crumbs.value, item]
  }
  highlightEntryId.value = undefined
  dirPagination.current = 1
  filePagination.current = 1
  void reload()
}

function goToCrumb(index: number) {
  crumbs.value = index < 0 ? [] : crumbs.value.slice(0, index + 1)
  highlightEntryId.value = undefined
  dirPagination.current = 1
  filePagination.current = 1
  void reload()
}

function queryFiles() {
  filePagination.current = 1
  void loadFiles()
}

function resetSearch() {
  search.value = ''
  filePagination.current = 1
  void loadFiles()
}

async function refresh() {
  await loadNavigation()
}

function onDirTableChange(pagination: { current?: number; pageSize?: number }) {
  dirPagination.current = pagination.current || 1
  dirPagination.pageSize = pagination.pageSize || PAGE_SIZE
  void loadDirectories()
}

function onFileTableChange(pagination: { current?: number; pageSize?: number }) {
  filePagination.current = pagination.current || 1
  filePagination.pageSize = pagination.pageSize || PAGE_SIZE
  void loadFiles()
}

function fileRowClass(record: BusinessFileEntry) {
  return String(record.entryId) === highlightEntryId.value ? 'business-row-highlight' : ''
}

function directoryKind(record: BusinessFileDirectory) {
  return BUSINESS_DIRECTORY_KINDS[record.kind]
}

/** 预览/下载前登记最近访问；登记失败不影响读取主流程 */
function recordAccess(item: BusinessFileEntry) {
  if (!spaceId.value || !businessFileAccessible(item)) return
  void platform.bizFiles.access(businessFileLocation(applicationId.value, spaceId.value, item)).catch(() => undefined)
}

async function preview(item: BusinessFileEntry) {
  if (!spaceId.value || !businessFileAccessible(item)) return
  recordAccess(item)
  try {
    const blob = await platform.bizFiles.content(businessFileLocation(applicationId.value, spaceId.value, item))
    if (!businessPreviewableType(blob.type) || !openBusinessBlob(blob)) {
      downloadBusinessBlob(blob, item.name)
      message.info('此文件类型不支持在线预览，已转为安全下载')
    }
  } catch (error) {
    message.error(errorMessage(error))
  }
}

async function download(item: BusinessFileEntry) {
  if (!spaceId.value || !businessFileAccessible(item)) return
  recordAccess(item)
  try {
    const blob = await platform.bizFiles.content(businessFileLocation(applicationId.value, spaceId.value, item))
    downloadBusinessBlob(blob, item.name)
  } catch (error) {
    message.error(errorMessage(error))
  }
}

async function toggleFavorite(item: BusinessFileEntry) {
  if (!spaceId.value || !businessFileAccessible(item)) return
  const next = !isFavorite(item)
  const context = contextRequest
  try {
    await platform.bizFiles.favorite({
      ...businessFileLocation(applicationId.value, spaceId.value, item),
      favorite: next
    })
    if (context !== contextRequest) return
    const ids = new Set(favoriteIds.value)
    if (next) ids.add(businessFavoriteKey(item.entryId))
    else ids.delete(businessFavoriteKey(item.entryId))
    favoriteIds.value = ids
    message.success(next ? '已加入收藏' : '已取消收藏')
    if (view.value === 'favorite') await loadFiles()
  } catch (error) {
    if (context === contextRequest) message.error(errorMessage(error))
  }
}

/** 返回业务记录：应用入口打开运行时应用，数据维护入口打开对象数据维护页 */
function openRecord(recordId?: string | null) {
  if (!recordId || !spaceId.value) return
  void router.push(businessRecordTarget(applicationId.value, spaceId.value, recordId))
}

watch(
  view,
  async () => {
    if (changingSpace) return
    filePagination.current = 1
    highlightEntryId.value = undefined
    await reload()
  },
  { flush: 'sync' }
)

onMounted(async () => {
  mounted = true
  await loadNavigation(businessDeepLink(route.query as Record<string, unknown>))
})
watch(
  () => route.fullPath,
  async () => {
    if (!mounted || route.path !== '/drive/business') return
    const link = businessDeepLink(route.query as Record<string, unknown>)
    if (link) await loadNavigation(link)
  }
)
onUnmounted(() => {
  mounted = false
  navigationRequest++
  resetLocation()
})
</script>

<template>
  <!-- 附件 / 文件夹 切换：文件夹区是业务空间里的真实目录，交给网盘工作区；附件视图保持挂载，切回来还是离开时的样子 -->
  <a-radio-group v-model:value="section" button-style="solid" style="flex: 0 0 auto; margin-bottom: 12px">
    <a-radio-button value="files">附件</a-radio-button>
    <a-radio-button value="folders">文件夹</a-radio-button>
  </a-radio-group>
  <DriveWorkspace v-if="section === 'folders'" mode="BIZ" />
  <div v-show="section === 'files'" class="drive-page business-page">
    <BusinessFileNavigation
      v-show="!navigationCollapsed"
      :entries="navigationEntries"
      :selected-entry-key="entryKey"
      :selected-object-id="spaceId"
      :loading="navigationLoading"
      @select="selection => selectSpace(selection)"
      @retry="retryEntry"
      @refresh="loadNavigation()"
    />
    <section class="business-content" aria-label="业务文件内容">
      <a-card size="small" class="business-toolbar">
        <a-space wrap>
          <a-button
            :aria-label="navigationCollapsed ? '展开业务导航' : '收起业务导航'"
            @click="navigationCollapsed = !navigationCollapsed"
          >
            <MenuUnfoldOutlined v-if="navigationCollapsed" />
            <MenuFoldOutlined v-else />
            {{ navigationCollapsed ? '展开导航' : '收起导航' }}
          </a-button>
          <a-select
            v-if="view === 'browse' && space"
            v-model:value="ruleVersion"
            class="business-select business-select--version"
            placeholder="选择规则版本"
            :options="versionOptions"
            :loading="versionLoading"
            @change="onVersionChange"
          />
          <a-radio-group v-model:value="view" button-style="solid">
            <a-radio-button value="browse">浏览</a-radio-button>
            <a-radio-button value="favorite">收藏</a-radio-button>
            <a-radio-button value="recent">最近访问</a-radio-button>
          </a-radio-group>
          <a-input
            v-if="view === 'browse'"
            v-model:value="search"
            class="business-search-input"
            placeholder="文件名、业务标题或业务编号"
            allow-clear
            @press-enter="queryFiles"
          />
          <a-button v-if="view === 'browse'" type="primary" :loading="fileLoading" @click="queryFiles">
            <SearchOutlined />
            查询
          </a-button>
          <a-button v-if="view === 'browse'" @click="resetSearch">
            <ReloadOutlined />
            重置
          </a-button>
          <a-button :loading="dirLoading || fileLoading || navigationLoading" @click="refresh">
            <ReloadOutlined />
            刷新
          </a-button>
        </a-space>
      </a-card>

      <template v-if="space">
        <div class="business-content__context">
          <strong>{{ currentEntry?.label }} / {{ space.objectName }}</strong>
          <span>{{ space.spaceName }}</span>
        </div>
        <div class="business-head">
          <a-breadcrumb v-if="view === 'browse'">
            <a-breadcrumb-item v-for="item in crumbTrail" :key="item.key">
              <a v-if="item.index < crumbs.length - 1" class="business-crumb-link" @click="goToCrumb(item.index)">
                {{ item.label }}
              </a>
              <span v-else>{{ item.label }}</span>
            </a-breadcrumb-item>
          </a-breadcrumb>
          <span v-else class="business-head__meta">
            {{ view === 'favorite' ? '我的收藏' : '最近访问' }}：仅显示当前空间内仍可见的文件，最多 100 条
          </span>
          <span class="business-head__meta">
            空间内可见文件 {{ space.fileCount }} 个 · {{ formatFileSize(space.totalSize) }}
          </span>
        </div>

        <a-table
          v-if="showDirectories"
          class="business-table"
          :columns="directoryColumns"
          :data-source="directories"
          :loading="dirLoading"
          :pagination="dirPagination"
          :row-key="businessDirectoryKey"
          :scroll="{ x: 'max-content' }"
          size="small"
          @change="onDirTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'label'">
              <span class="drive-name">
                <PaperClipOutlined v-if="record.kind === 'FIELD'" class="drive-name__icon" />
                <FolderOutlined v-else class="drive-name__icon" />
                <span class="business-identity">
                  <span class="business-identity__title">
                    <a class="drive-name__text" :title="record.label" @click="enterDirectory(record)">
                      {{ record.label }}
                    </a>
                    <a-tooltip
                      v-if="businessLabelNotice(record.labelStatus, record.restricted)"
                      :title="businessLabelNotice(record.labelStatus, record.restricted)?.description"
                    >
                      <a-tag :color="businessLabelNotice(record.labelStatus, record.restricted)?.color">
                        {{ businessLabelNotice(record.labelStatus, record.restricted)?.label }}
                      </a-tag>
                    </a-tooltip>
                  </span>
                  <span v-if="record.kind === 'RECORD'" class="business-identity__meta">
                    {{ space.objectName }} · ID {{ record.recordId }}
                  </span>
                </span>
              </span>
            </template>
            <template v-else-if="column.key === 'kind'">
              <a-tag :color="directoryKind(record).color">
                {{ directoryKind(record).label }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'totalSize'">
              {{ formatFileSize(record.totalSize || 0) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="drive-actions">
                <a-button type="link" @click="enterDirectory(record)">
                  <FolderOpenOutlined />
                  {{ record.kind === 'RECORD' ? '查看附件' : '进入' }}
                </a-button>
                <a-button v-if="record.kind === 'RECORD'" type="link" @click="openRecord(record.recordId)">
                  <LinkOutlined />
                  查看业务记录
                </a-button>
              </div>
            </template>
          </template>
        </a-table>

        <a-table
          class="business-table"
          :columns="fileColumns"
          :data-source="files"
          :loading="fileLoading"
          :pagination="view === 'browse' ? filePagination : false"
          row-key="entryId"
          :row-class-name="fileRowClass"
          :scroll="{ x: 'max-content' }"
          size="small"
          @change="onFileTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <span class="drive-name">
                <FileOutlined class="drive-name__icon" />
                <a v-if="businessFileAccessible(record)" class="drive-name__text" @click="preview(record)">
                  {{ record.name }}
                </a>
                <span v-else class="drive-name__text">{{ record.name }}</span>
              </span>
            </template>
            <template v-else-if="column.key === 'size'">
              {{ formatFileSize(record.size || 0) }}
            </template>
            <template v-else-if="column.key === 'recordLabel'">
              <div class="business-identity">
                <span class="business-identity__title">
                  <a v-if="record.recordId" :title="record.recordLabel" @click="openRecord(record.recordId)">
                    {{ record.recordLabel || '业务记录' }}
                  </a>
                  <span v-else>{{ record.recordLabel || '-' }}</span>
                  <a-tooltip
                    v-if="businessLabelNotice(record.recordLabelStatus, record.recordRestricted)"
                    :title="businessLabelNotice(record.recordLabelStatus, record.recordRestricted)?.description"
                  >
                    <a-tag :color="businessLabelNotice(record.recordLabelStatus, record.recordRestricted)?.color">
                      {{ businessLabelNotice(record.recordLabelStatus, record.recordRestricted)?.label }}
                    </a-tag>
                  </a-tooltip>
                </span>
                <span v-if="record.recordId" class="business-identity__meta">
                  {{ space.objectName }} · ID {{ record.recordId }}
                </span>
              </div>
            </template>
            <template v-else-if="column.key === 'source'">
              <span :title="businessFileSource(record)">{{ businessFileSource(record) }}</span>
            </template>
            <template v-else-if="column.key === 'submitter'">
              {{ record.submitter || '-' }}
            </template>
            <template v-else-if="column.key === 'uploadedAt'">
              {{ record.uploadedAt ? formatDateTime(record.uploadedAt) : '-' }}
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="drive-actions">
                <a-button v-if="businessFileAccessible(record)" type="link" @click="preview(record)">
                  <EyeOutlined />
                  预览
                </a-button>
                <a-button v-if="businessFileAccessible(record)" type="link" @click="download(record)">
                  <DownloadOutlined />
                  下载
                </a-button>
                <a-button v-if="record.recordId" type="link" @click="openRecord(record.recordId)">
                  <LinkOutlined />
                  查看业务记录
                </a-button>
                <a-button v-if="businessFileAccessible(record)" type="link" @click="toggleFavorite(record)">
                  <StarFilled v-if="isFavorite(record)" />
                  <StarOutlined v-else />
                  {{ isFavorite(record) ? '取消收藏' : '收藏' }}
                </a-button>
              </div>
            </template>
          </template>
        </a-table>
      </template>
      <a-empty
        v-else-if="!entryLoading && !navigationLoading"
        class="business-empty"
        :description="navigationEntries.length ? '从左侧选择业务对象查看文件' : '暂无可浏览的业务入口'"
      />
    </section>
  </div>
</template>
