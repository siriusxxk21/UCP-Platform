<script setup lang="ts">
/**
 * 网盘文件浏览器
 *
 * VueFinder 原生组件 + 驱动 + 右键菜单 + 详情抽屉。取数全部经「接口口子」，
 * 网盘页面与业务表单下方用的是同一个浏览器，差别只在口子与几个开关；
 * 空间选择、标题、地址栏、成员与权限抽屉留在宿主。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ContextMenuIds, contextMenuItems as defaultContextMenuItems, VueFinder } from 'vuefinder'
import type { DirEntry, Item, ItemDclickEvent } from 'vuefinder'
import 'vuefinder/dist/style.css'
// 组件自带主题与平台观感不一致，统一在平台侧覆盖（弹层传送到 body，必须全局生效）
import '../drive-finder.css'
import { hasPermission } from '@/utils/access'
import type { DriveId, DrivePermissionRole } from '@/types/drive'
import { createDriveDriver } from '../driver/drive-driver'
import type { DriveDriver } from '../driver/drive-driver'
import type { DriveGateway } from '../driver/drive-gateway'
import { ensureVueFinderInstalled, seedFinderState } from '../vuefinder-runtime'
import { READ_ONLY_FEATURES, lockedMenuItems } from './drive-folder-browser-menu'
import EntryDetailDrawer from './EntryDetailDrawer.vue'

const props = withDefaults(
  defineProps<{
    /** 组件标识：视图偏好按它持久化 */
    finderId: string
    gateway: DriveGateway
    storageName: string
    rootRole?: DrivePermissionRole
    /** 初始目录，默认 '/' */
    initialPath?: string
    /** 详情抽屉里显示的空间名 */
    spaceName?: string
    /** 是否显示「成员与权限」菜单项（由宿主处理） */
    allowPermission?: boolean
    /** 是否显示收藏菜单项与详情里的收藏按钮 */
    allowFavorite?: boolean
    /** 是否开启搜索，默认 true */
    allowSearch?: boolean
    /** 给了就启用「逐项只读」的菜单替换：选中项里有 modifiable === false 的，改名/移动/删除不显示，换成这句话 */
    lockedNote?: string
    /** 点那句话时弹出的完整说明；不给就弹那句话本身 */
    lockedReason?: string
    /** 能不能写，默认 true；false 时上传、新建、改名、移动、复制、删除的入口全部不出 */
    allowWrite?: boolean
    /** 普通网盘入口按底座权限隐藏写操作；记录文件夹沿用自己的授权入口。 */
    enforceDrivePermissions?: boolean
  }>(),
  { allowSearch: true, allowWrite: true }
)

const emit = defineEmits<{
  'path-change': [path: string]
  changed: []
  permission: [entryId: DriveId, name: string, role?: DrivePermissionRole]
}>()

/** VueFinder 的选项由 app.use 注入，未安装完成就渲染会取不到上下文 */
const finderReady = ref(false)
const currentPath = ref(props.initialPath || '/')
/** 初始定位目录：列表页跳转过来时带 path，其它情况从根目录开始 */
const initialPath = ref(props.initialPath || '/')
/** initialPath 只在组件挂载时被读取，同空间内换目录只能重建实例 */
const finderEpoch = ref(0)

const detailOpen = ref(false)
const detailEntryId = ref<DriveId | null>(null)
const detailEntryName = ref('')

const driver = computed<DriveDriver>(() =>
  createDriveDriver({
    gateway: props.gateway,
    storageName: props.storageName,
    getRootRole: () => props.rootRole,
    onChanged: () => emit('changed')
  })
)

/** 组件实例按空间切换重建，避免上一个空间的路径与选中态残留 */
const finderKey = computed(() => `${props.finderId}-${finderEpoch.value}`)

// 持久化状态必须在新实例挂载前写好，否则组件会先落回内置默认值
watch(
  () => props.finderId,
  id => seedFinderState(id),
  { immediate: true, flush: 'sync' }
)

const config = computed(() => ({
  loadingIndicator: 'linear' as const,
  showTreeView: false,
  initialPath: initialPath.value,
  // 网盘服务上限 1 GiB，客户端提前拒绝超限上传。
  maxFileSize: '1gb',
  // 行高对齐平台表格（内容 46 + 上下间距 2 = 50px，与表格数据行同高）；
  // 组件按这三个值同时计算虚拟列表行槽与图标尺寸，改尺寸只能走 config
  listItemHeight: 46,
  listItemGap: 2,
  listIconSize: 18
}))

/** 平台没有对应能力的功能直接隐去，避免点了才报错 */
const features = computed(() => ({
  archive: false,
  unarchive: false,
  history: false,
  pinned: false,
  newfile: false,
  edit: false,
  search: props.allowSearch,
  ...(props.enforceDrivePermissions
    ? {
        upload: hasPermission('drive:entry:upload'),
        newfolder: hasPermission('drive:entry:update'),
        rename: hasPermission('drive:entry:update'),
        move: hasPermission('drive:entry:update'),
        copy: hasPermission('drive:entry:update'),
        delete: hasPermission('drive:entry:delete')
      }
    : {}),
  ...(props.allowWrite ? {} : READ_ONLY_FEATURES)
}))

/** 有收藏开关、且口子有收藏能力时才出收藏入口 */
const canFavorite = computed(() => Boolean(props.allowFavorite && props.gateway.favorite))

const menuItems = computed<Item[]>(() => [
  ...lockedMenuItems(
    defaultContextMenuItems,
    ContextMenuIds,
    item => driver.value.peekModifiable(item.path) === false,
    props.lockedNote,
    text => void message.info(props.lockedReason || text)
  ),
  {
    id: 'drive-detail',
    title: () => '查看详情',
    order: 300,
    show: (_app, ctx) => ctx.items.length === 1 && Boolean(entryIdOf(ctx.items[0])),
    action: (_app, items) => openDetail(items[0])
  },
  {
    id: 'drive-permission',
    title: () => '成员与权限',
    order: 310,
    show: (_app, ctx) => ctx.items.length === 1 && Boolean(props.allowPermission) && Boolean(entryIdOf(ctx.items[0])),
    action: (_app, items) => openPermission(items[0])
  },
  {
    id: 'drive-favorite',
    title: () => '收藏',
    order: 320,
    show: (_app, ctx) => ctx.items.length === 1 && canFavorite.value && !isFavorite(ctx.items[0]),
    action: (_app, items) => void toggleFavorite(items[0], true)
  },
  {
    id: 'drive-unfavorite',
    title: () => '取消收藏',
    order: 330,
    show: (_app, ctx) => ctx.items.length === 1 && canFavorite.value && isFavorite(ctx.items[0]),
    action: (_app, items) => void toggleFavorite(items[0], false)
  }
])

onMounted(async () => {
  try {
    await ensureVueFinderInstalled()
    finderReady.value = true
  } catch {
    message.error('文件组件加载失败，请刷新后重试')
  }
})

function entryIdOf(item?: DirEntry | null): DriveId | undefined {
  return item ? driver.value?.peekId(item.path) : undefined
}

function isFavorite(item?: DirEntry | null): boolean {
  return Boolean(item && driver.value?.peekFavorite(item.path))
}

function handlePathChange(path: string) {
  currentPath.value = path
  emit('path-change', path)
}

/** 双击文件默认进组件自带预览，改为打开日创详情抽屉；目录仍按原生行为进入 */
function handleFileDclick(event: ItemDclickEvent) {
  event.preventDefault()
  openDetail(event.item)
}

function openDetail(item: DirEntry) {
  const entryId = entryIdOf(item)
  if (entryId === undefined) {
    message.warning('目录列表已更新，请刷新后重试')
    return
  }
  detailEntryId.value = entryId
  detailEntryName.value = item.basename
  detailOpen.value = true
}

function openPermission(item: DirEntry) {
  const entryId = entryIdOf(item)
  if (entryId === undefined) {
    message.warning('目录列表已更新，请刷新后重试')
    return
  }
  emit('permission', entryId, item.basename, driver.value.peekRole(item.path))
}

async function toggleFavorite(item: DirEntry, favorite: boolean) {
  const entryId = entryIdOf(item)
  if (entryId === undefined) {
    message.warning('目录列表已更新，请刷新后重试')
    return
  }
  try {
    await props.gateway.favorite?.(entryId, favorite)
    driver.value?.markFavorite(item.path, favorite)
    message.success(favorite ? '已收藏' : '已取消收藏')
  } catch {
    /* 统一请求层展示错误 */
  }
}

/** 取已列出目录上的有效角色，宿主按当前目录控制自己的操作入口 */
function peekRole(path: string): DrivePermissionRole | undefined {
  return driver.value.peekRole(path)
}

/** 重新取当前目录的列表：组件没有对外的刷新方法，照现有做法以当前目录为起点重建实例 */
function reload() {
  const path = currentPath.value
  initialPath.value = path.includes('://') ? `/${path.slice(path.indexOf('://') + 3)}` : path || '/'
  finderEpoch.value += 1
}

defineExpose({ reload, currentPath, peekRole })
</script>

<template>
  <VueFinder
    v-if="driver && finderReady"
    :id="finderId"
    :key="finderKey"
    :driver="driver"
    :config="config"
    :features="features"
    :context-menu-items="menuItems"
    locale="zhCN"
    @path-change="handlePathChange"
    @file-dclick="handleFileDclick"
  />
  <EntryDetailDrawer
    :open="detailOpen"
    :entry-id="detailEntryId"
    :entry-name="detailEntryName"
    :space-name="spaceName"
    :gateway="gateway"
    @changed="reload"
    @close="detailOpen = false"
  />
</template>
