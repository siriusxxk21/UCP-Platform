<script setup lang="ts">
/**
 * 网盘工作区
 *
 * 日创外壳负责空间选择、概览与成员权限抽屉，文件区交给 DriveFolderBrowser（VueFinder 原生组件 + 驱动 + 详情抽屉）；
 * 外壳只给它当前空间的接口口子与几个开关，不改组件内部实现。
 * 个人空间、团队空间与业务空间的文件夹区共用本组件，差别只在空间来源与文案；
 * 业务空间模式嵌在「业务文件」页里，不读写地址栏，也不出成员与权限（业务空间不按人授权）。
 */
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CloudUploadOutlined, ReloadOutlined, TeamOutlined } from '@ant-design/icons-vue'
import { getBusinessSpaceList, getMySpaceList } from '@/api/drive/space'
import type { DriveId, DrivePermissionRole, DriveSpace } from '@/types/drive'
import { hasPermission } from '@/utils/access'
import { formatFileSize } from '@/utils/format'
import type { DriveGateway } from '../driver/drive-gateway'
import { createDriveGateway } from '../driver/drive-gateway'
import DriveFolderBrowser from './DriveFolderBrowser.vue'
import PermissionDrawer from './PermissionDrawer.vue'

const props = defineProps<{ mode: 'PERSONAL' | 'TEAM' | 'BIZ' }>()

const route = useRoute()
const router = useRouter()

const spacesLoading = ref(false)
const loadFailed = ref(false)
let spaceRequest = 0
const spaces = ref<DriveSpace[]>([])
const currentSpaceId = ref<DriveId | ''>('')
const currentPath = ref('/')
const currentRole = ref<DrivePermissionRole | undefined>(undefined)
/** 初始定位目录：列表页跳转过来时带 path，其它情况从根目录开始 */
const initialPath = ref('/')
/** initialPath 只在组件挂载时被读取，同空间内换目录只能重建实例 */
const finderEpoch = ref(0)

const browser = ref<InstanceType<typeof DriveFolderBrowser>>()
const permissionOpen = ref(false)
const permissionEntryId = ref<DriveId>(0)
const permissionEntryName = ref('')
const permissionCanEdit = ref(false)

const isTeam = computed(() => props.mode === 'TEAM')
const isBiz = computed(() => props.mode === 'BIZ')
/** 可能有多个空间可选的两种模式 */
const multiSpace = computed(() => isTeam.value || isBiz.value)
const modeLabel = computed(() => (isBiz.value ? '业务空间' : isTeam.value ? '团队空间' : '个人空间'))
const spaceList = computed(() => spaces.value.filter(space => space.type === props.mode))
const currentSpace = computed(() => spaceList.value.find(space => String(space.id) === String(currentSpaceId.value)))
const canFavorite = computed(() => hasPermission('drive:mark:update'))
const canManage = computed(
  () => currentSpace.value?.role === 'MANAGER' && !isBiz.value && hasPermission('drive:permission:query')
)
const canCreateSpace = computed(() => hasPermission('drive:space:create'))

const spaceOptions = computed(() => spaceList.value.map(space => ({ label: space.name, value: space.id })))
const emptyText = computed(() =>
  isBiz.value
    ? '你没有在这里浏览业务文件夹的权限。某条记录的文件夹请在对应表单下方查看。'
    : isTeam.value
      ? '暂无团队空间'
      : '暂无个人空间'
)

// 用量刷新保持驱动实例，防止正在上传的文件和路径缓存丢失。
const gateway = shallowRef<DriveGateway | null>(null)
watch(
  () => currentSpace.value?.id,
  id => {
    gateway.value = id == null ? null : createDriveGateway(id)
  },
  { immediate: true, flush: 'sync' }
)

/** 组件标识按空间固定：视图偏好等持久化状态跟随空间，实例重建时不丢 */
const finderId = computed(() => `drive-${props.mode}-${String(currentSpaceId.value)}`)
/** 组件实例按空间切换重建，避免上一个空间的路径与选中态残留 */
const finderKey = computed(() => `${finderId.value}-${finderEpoch.value}`)

onMounted(() => {
  void loadSpaces(true)
})

watch(
  () => [route.query.space, route.query.path],
  () => applyTargetSpace(true)
)

async function loadSpaces(useQuery = false, quiet = false) {
  const request = ++spaceRequest
  if (!quiet) spacesLoading.value = true
  try {
    const list = await (isBiz.value ? getBusinessSpaceList() : getMySpaceList())
    if (request !== spaceRequest) return
    spaces.value = list || []
    loadFailed.value = false
    applyTargetSpace(useQuery)
  } catch {
    if (request === spaceRequest && !quiet) loadFailed.value = true
  } finally {
    if (request === spaceRequest) spacesLoading.value = false
  }
}

/**
 * 选择当前空间并按查询参数定位目录
 *
 * 列表页的“打开所在目录”会带上 space 与 path；刷新与空间切换不读查询参数，
 * 否则会把用户已经走到的目录顶回跳转时的位置。
 */
function applyTargetSpace(useQuery: boolean) {
  // 业务空间模式不认地址栏：「业务文件」页有自己的深链参数
  if (isBiz.value) useQuery = false
  const candidates = spaceList.value
  const matched = candidates.find(space => String(space.id) === String(route.query.space ?? ''))
  const kept = candidates.find(space => String(space.id) === String(currentSpaceId.value))
  const target = (useQuery ? matched : undefined) ?? kept ?? candidates[0]

  const sameSpace = String(target?.id ?? '') === String(currentSpaceId.value)
  const queryPath = route.query.path
  const nextPath = typeof queryPath === 'string' && queryPath.startsWith('/') ? queryPath : ''

  currentSpaceId.value = target ? target.id : ''
  currentRole.value = target?.role
  if (!useQuery) return

  // 同空间内换目录时 key 不变，只能靠重建实例重新定位
  const shouldRemount = Boolean(nextPath) && sameSpace && nextPath !== initialPath.value
  initialPath.value = nextPath || '/'
  currentPath.value = initialPath.value
  if (shouldRemount) finderEpoch.value += 1
}

function handlePathChange(path: string) {
  currentPath.value = path
  // 列表请求已带回该目录的有效角色，取不到时按空间根角色兜底
  currentRole.value = browser.value?.peekRole(path) ?? currentSpace.value?.role
}

function handleExchangeSpace(value: DriveId) {
  initialPath.value = '/'
  currentSpaceId.value = value
  currentRole.value = currentSpace.value?.role
  currentPath.value = '/'
  if (isBiz.value) return
  // 地址栏跟随当前空间，避免刷新后又跳回跳转来源的空间
  const query = { ...route.query }
  delete query.path
  query.space = String(value)
  void router.replace({ path: route.path, query })
}

function openPermission(entryId: DriveId, name: string, role?: DrivePermissionRole) {
  permissionEntryId.value = entryId
  permissionEntryName.value = name
  permissionCanEdit.value = role === 'MANAGER' && hasPermission('drive:permission:update')
  permissionOpen.value = true
}

function openSpacePermission() {
  if (!currentSpace.value) return
  permissionEntryId.value = 0
  permissionEntryName.value = currentSpace.value.name
  permissionCanEdit.value = currentSpace.value.role === 'MANAGER' && hasPermission('drive:permission:update')
  permissionOpen.value = true
}

async function refreshCurrent() {
  await loadSpaces()
  browser.value?.reload()
}
function goSpaceManage() {
  void router.push('/drive/space')
}
</script>

<template>
  <section class="drive-workspace">
    <header class="drive-workspace__header">
      <div class="drive-workspace__title-row">
        <div class="drive-workspace__title">
          <h2 :title="currentSpace?.name">
            {{ currentSpace?.name || (isBiz ? '业务空间' : isTeam ? '团队空间' : '我的文件') }}
          </h2>
          <a-tag v-if="currentSpace" :color="isBiz ? 'blue' : isTeam ? 'purple' : undefined">
            {{ modeLabel }}
          </a-tag>
          <span v-if="currentSpace" class="drive-workspace__quota">
            已用 {{ formatFileSize(currentSpace.usedBytes || 0) }}
            <template v-if="currentSpace.quotaBytes">/ {{ formatFileSize(currentSpace.quotaBytes) }}</template>
          </span>
        </div>
        <a-space class="drive-workspace__actions" wrap>
          <a-select
            v-if="multiSpace && spaceOptions.length > 1"
            :value="currentSpaceId"
            :aria-label="`当前${modeLabel}`"
            class="drive-workspace__space-select"
            :options="spaceOptions"
            :placeholder="`选择${modeLabel}`"
            @change="handleExchangeSpace"
          />
          <a-button v-if="currentSpace && canManage" @click="openSpacePermission">
            <TeamOutlined />
            成员与权限
          </a-button>
          <a-button :loading="spacesLoading" @click="refreshCurrent">
            <ReloadOutlined />
            刷新
          </a-button>
        </a-space>
      </div>
      <p class="drive-workspace__desc">
        {{
          isBiz
            ? '业务文件夹：在这里建好文件夹，再到数据对象的「业务文件」页签里关联。附件字段的文件在「附件」视图。'
            : isTeam
              ? '团队文件集中管理，协作成果有序沉淀；在目录右键菜单里查看详情与成员权限。'
              : '集中管理个人文件与资料；双击文件可预览，删除后可到回收站还原。'
        }}
      </p>
    </header>

    <a-spin :spinning="spacesLoading" tip="加载中..." :delay="300">
      <div class="drive-workspace__body">
        <a-result v-if="loadFailed" status="error" title="空间加载失败" sub-title="请重试加载，当前数据不会被修改。">
          <template #extra><a-button :loading="spacesLoading" @click="refreshCurrent">重新加载</a-button></template>
        </a-result>
        <DriveFolderBrowser
          v-else-if="currentSpace && gateway"
          ref="browser"
          :key="finderKey"
          :finder-id="finderId"
          :gateway="gateway"
          :storage-name="currentSpace.name"
          :root-role="currentSpace.role"
          :initial-path="initialPath"
          :space-name="currentSpace.name"
          :allow-permission="!isBiz && hasPermission('drive:permission:query')"
          enforce-drive-permissions
          :allow-favorite="canFavorite"
          @path-change="handlePathChange"
          @permission="openPermission"
          @changed="loadSpaces(false, true)"
        />
        <a-empty v-else-if="!spacesLoading" :description="emptyText">
          <a-space>
            <a-button v-if="multiSpace && canCreateSpace" type="primary" @click="goSpaceManage">
              <CloudUploadOutlined />
              去空间管理创建
            </a-button>
            <a-button @click="refreshCurrent">刷新</a-button>
          </a-space>
        </a-empty>
      </div>
    </a-spin>

    <PermissionDrawer
      :open="permissionOpen"
      :space-id="currentSpace?.id ?? null"
      :entry-id="permissionEntryId"
      :entry-name="permissionEntryName"
      :can-edit="permissionCanEdit"
      @close="permissionOpen = false"
    />
  </section>
</template>

<style scoped>
.drive-workspace {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.drive-workspace__header {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.drive-workspace__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.drive-workspace__title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.drive-workspace__title h2 {
  max-width: 480px;
  margin: 0;
  overflow: hidden;
  font-size: 18px;
  line-height: 28px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drive-workspace__quota {
  color: var(--text-secondary);
  font-size: 12px;
}

.drive-workspace__actions {
  flex-shrink: 0;
}

.drive-workspace__space-select {
  width: 200px;
}

.drive-workspace__desc {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.drive-workspace__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 360px;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--ant-color-bg-container, #fff);
}

/* a-spin 的两层容器默认是块级元素，会把文件区的 flex:1 挡在中间，这里接回高度链路 */
.drive-workspace :deep(.ant-spin-nested-loading),
.drive-workspace :deep(.ant-spin-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
</style>
