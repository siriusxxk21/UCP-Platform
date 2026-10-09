<script setup lang="ts">
/**
 * 网盘节点详情抽屉
 *
 * 原生文件表格只展示名称与基础列，扩展信息（所属空间、所在目录、上传人、MIME）在此补齐；
 * 预览交给浏览器按内容地址直接加载，避免把文件整体读进内存。
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DownloadOutlined, StarFilled, StarOutlined } from '@ant-design/icons-vue'
import { DRIVE_ROOT_PARENT_ID } from '@/types/drive'
import type { DriveEntry, DriveId } from '@/types/drive'
import { hasPermission } from '@/utils/access'
import { formatDateTime, formatFileSize } from '@/utils/format'
import { useDriveUserNames } from '../composables/useDriveUserNames'
import { createDriveGateway } from '../driver/drive-gateway'
import type { DriveGateway } from '../driver/drive-gateway'

const props = defineProps<{
  open: boolean
  entryId: DriveId | null
  /** 已知节点名称，用于打开瞬间就有标题，避免空白等待 */
  entryName?: string
  /** 所属空间名称，列表接口不回填空间名 */
  spaceName?: string
  /** 接口口子；不给就是普通网盘接口 */
  gateway?: DriveGateway
}>()

const emit = defineEmits<{
  close: []
  /** 收藏等状态变化后通知外壳刷新列表 */
  changed: []
}>()

const loading = ref(false)
const favoriting = ref(false)
const detail = ref<DriveEntry | null>(null)
const parentPath = ref('')
const textContent = ref('')
const loadError = ref('')
const previewError = ref('')
let requestVersion = 0
/** 文本预览整份读入内存；超过 2 MiB 只提供下载，避免大文件卡住抽屉。 */
const MAX_TEXT_PREVIEW = 2 * 1024 * 1024

// 详情用到的几个方法都按节点编号取数，不带空间编号
const driveGateway = createDriveGateway(DRIVE_ROOT_PARENT_ID)
const gateway = computed(() => props.gateway ?? driveGateway)
const canFavorite = computed(() => hasPermission('drive:mark:update') && Boolean(gateway.value.favorite))
const { loadUserNames, userName } = useDriveUserNames()
const isFolder = computed(() => detail.value?.type === 'FOLDER')
const previewUrl = computed(() =>
  detail.value && !isFolder.value ? gateway.value.contentUrl(detail.value.id, true) : ''
)
const downloadUrl = computed(() =>
  detail.value && !isFolder.value ? gateway.value.contentUrl(detail.value.id, false) : ''
)

/** 图片、音视频与 PDF 用浏览器原生渲染，纯文本取回内容展示，其余类型只提供下载 */
const previewKind = computed<'image' | 'video' | 'audio' | 'pdf' | 'text' | 'large-text' | 'none'>(() => {
  const mime = detail.value?.mimeType || ''
  if (!mime) return 'none'
  if (mime.startsWith('image/')) return 'image'
  if (mime.startsWith('video/')) return 'video'
  if (mime.startsWith('audio/')) return 'audio'
  if (mime === 'application/pdf') return 'pdf'
  if (mime.startsWith('text/') || mime === 'application/json' || mime === 'application/xml') {
    return (detail.value?.size || 0) > MAX_TEXT_PREVIEW ? 'large-text' : 'text'
  }
  return 'none'
})

watch(
  () => [props.open, props.entryId] as const,
  ([open, entryId]) => {
    if (!open || entryId === null || entryId === undefined) {
      requestVersion++
      detail.value = null
      loading.value = false
      return
    }
    void load(entryId)
  },
  { immediate: true }
)

async function load(entryId: DriveId) {
  const request = ++requestVersion
  loading.value = true
  loadError.value = ''
  previewError.value = ''
  detail.value = null
  parentPath.value = ''
  textContent.value = ''
  void loadUserNames()
  try {
    const data = await gateway.value.get(entryId)
    if (request !== requestVersion) return
    detail.value = data
    // 路径从当前有权文件取面包屑，不另要求上级目录权限；路径失败也不阻断文件本身预览。
    try {
      const path = await resolveParentPath(data)
      if (request !== requestVersion) return
      parentPath.value = path
    } catch {
      if (request !== requestVersion) return
      parentPath.value = '目录信息暂不可用'
    }
    if (previewKind.value === 'text') {
      try {
        const text = await (await gateway.value.content(entryId, true)).text()
        if (request !== requestVersion) return
        textContent.value = text
      } catch {
        if (request !== requestVersion) return
        previewError.value = '文件预览加载失败，可重试或下载后查看。'
      }
    }
    // 最近使用按访问时间排序，记录失败不影响详情展示
    if (hasPermission('drive:mark:update')) void gateway.value.recordAccess?.(entryId).catch(() => undefined)
  } catch {
    if (request === requestVersion) loadError.value = '文件信息加载失败，文件可能已删除或访问权限发生变化。'
  } finally {
    if (request === requestVersion) loading.value = false
  }
}

/** 所在目录展示为名称路径，根目录直接给出中文 */
async function resolveParentPath(entry: DriveEntry): Promise<string> {
  // 上级为根节点时没有对应记录，直接取面包屑会被后端判为节点不存在
  if (String(entry.parentId) === String(DRIVE_ROOT_PARENT_ID)) return '根目录'
  if (gateway.value.parentPath) return gateway.value.parentPath(entry.id)
  const path = await gateway.value.path(entry.parentId)
  return path === '/' ? '根目录' : path.slice(1).split('/').join(' / ')
}

async function toggleFavorite() {
  if (!detail.value || favoriting.value) return
  favoriting.value = true
  const entryId = detail.value.id
  const next = !detail.value.favorite
  try {
    await gateway.value.favorite?.(entryId, next)
    if (detail.value?.id === entryId) detail.value.favorite = next
    message.success(next ? '已收藏' : '已取消收藏')
    emit('changed')
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    favoriting.value = false
  }
}

function download() {
  if (!detail.value || !downloadUrl.value) return
  const link = document.createElement('a')
  link.href = downloadUrl.value
  link.download = detail.value.name
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}
</script>

<template>
  <a-drawer
    :open="open"
    :title="detail?.name || entryName || '文件详情'"
    width="min(560px, 100vw)"
    class="drive-detail-drawer"
    @close="emit('close')"
  >
    <template #extra>
      <a-space>
        <a-button v-if="canFavorite && detail" :loading="favoriting" @click="toggleFavorite">
          <StarFilled v-if="detail.favorite" class="drive-detail-drawer__starred" />
          <StarOutlined v-else />
          {{ detail.favorite ? '已收藏' : '收藏' }}
        </a-button>
        <a-button v-if="detail && !isFolder" type="primary" @click="download">
          <DownloadOutlined />
          下载
        </a-button>
      </a-space>
    </template>
    <a-skeleton v-if="loading" active :paragraph="{ rows: 6 }" />
    <a-result v-else-if="loadError" status="warning" title="无法加载文件" :sub-title="loadError">
      <template #extra><a-button @click="entryId != null && load(entryId)">重试</a-button></template>
    </a-result>
    <div v-else-if="detail" class="drive-detail-drawer__body">
      <div v-if="!isFolder" class="drive-detail-drawer__preview">
        <a-alert v-if="previewError" type="warning" show-icon :message="previewError">
          <template #action><a-button size="small" @click="load(detail.id)">重试</a-button></template>
        </a-alert>
        <img
          v-else-if="previewKind === 'image'"
          :src="previewUrl"
          :alt="detail.name"
          @error="previewError = '图片加载失败，可重试或下载后查看。'"
        />
        <video
          v-else-if="previewKind === 'video'"
          :src="previewUrl"
          controls
          @error="previewError = '视频暂不能播放，请下载后查看。'"
        />
        <audio
          v-else-if="previewKind === 'audio'"
          :src="previewUrl"
          controls
          @error="previewError = '音频暂不能播放，请下载后查看。'"
        />
        <iframe v-else-if="previewKind === 'pdf'" :src="previewUrl" title="PDF 预览" />
        <pre v-else-if="previewKind === 'text'" class="drive-detail-drawer__text">{{ textContent }}</pre>
        <a-empty v-else-if="previewKind === 'large-text'" description="文本超过 2 MiB，下载后查看完整内容" />
        <a-empty v-else description="该类型暂不支持在线预览，请下载后查看" />
      </div>
      <a-descriptions bordered :column="1" size="small">
        <a-descriptions-item label="所属空间">{{ spaceName || '-' }}</a-descriptions-item>
        <a-descriptions-item label="所在目录">{{ parentPath || '-' }}</a-descriptions-item>
        <a-descriptions-item label="类型">{{ isFolder ? '目录' : '文件' }}</a-descriptions-item>
        <a-descriptions-item v-if="!isFolder" label="大小">
          {{ formatFileSize(detail.size || 0) }}
        </a-descriptions-item>
        <a-descriptions-item v-if="!isFolder" label="内容类型">{{ detail.mimeType || '-' }}</a-descriptions-item>
        <a-descriptions-item label="上传人">{{ userName(detail.creator) }}</a-descriptions-item>
        <a-descriptions-item label="上传时间">{{ formatDateTime(detail.createTime || 0) }}</a-descriptions-item>
        <a-descriptions-item label="最近修改">{{ formatDateTime(detail.updateTime || 0) }}</a-descriptions-item>
        <a-descriptions-item label="继承上级授权">{{ detail.inheritParent ? '是' : '否' }}</a-descriptions-item>
      </a-descriptions>
    </div>
  </a-drawer>
</template>

<style scoped>
.drive-detail-drawer__body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.drive-detail-drawer__starred {
  color: var(--warning);
}

.drive-detail-drawer__preview {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(var(--control-height) * 5);
  max-height: 45vh;
  padding: var(--spacing-sm);
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-page);
}

.drive-detail-drawer__preview img,
.drive-detail-drawer__preview video {
  max-width: 100%;
  max-height: 40vh;
  object-fit: contain;
}

.drive-detail-drawer__preview iframe {
  width: 100%;
  height: 40vh;
  border: 0;
}

.drive-detail-drawer__text {
  width: 100%;
  max-height: 40vh;
  margin: 0;
  overflow: auto;
  font-size: var(--table-font-sm);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.drive-detail-drawer__preview audio {
  max-width: 100%;
}
</style>
