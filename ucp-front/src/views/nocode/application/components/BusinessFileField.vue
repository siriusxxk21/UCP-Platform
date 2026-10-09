<script setup lang="ts">
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { FileOutlined, PictureOutlined, RedoOutlined, UploadOutlined } from '@ant-design/icons-vue'
import InfraUpload from '@/components/InfraUpload.vue'
import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import { useNocodePlatform } from '@/nocode/platform'
import { taskFormAccessKey } from '@/nocode/task-form-access'
import { errorMessage } from '@/nocode/data-center'
import {
  businessFileField,
  businessFileHint,
  BUSINESS_FILE_MAX_COUNT,
  BUSINESS_FILE_MAX_SIZE,
  businessSession,
  businessSessionId,
  forgetBusinessTempFile,
  loadBusinessSession,
  newBusinessSessionKey,
  rememberBusinessTempFile,
  appendBusinessFileId,
  canAppendBusinessFile
} from '@/nocode/business-file'
import {
  businessImageType,
  businessPreviewableType,
  downloadBusinessBlob,
  openBusinessBlob
} from '@/nocode/business-file-content'
import { resolveBusinessUserNames } from '@/nocode/business-file-names'
import { formatDateTime, formatFileSize } from '@/utils/format'
import type { BusinessFilePolicy } from '@/types/nocode/data-center'
import type {
  BusinessFileContentQuery,
  BusinessFileEntry,
  BusinessTemporaryContentQuery
} from '@/types/nocode/business-file'

const props = withDefaults(
  defineProps<{
    modelValue?: string[]
    image?: boolean
    disabled?: boolean
    readOnly?: boolean
    applicationId?: string
    objectId?: string
    recordId?: string
    detailId?: string
    detailRecordId?: string
    fieldId?: string
    businessPolicy?: BusinessFilePolicy | null
    hideBusinessPath?: boolean
    /** 记录详情与附件区块的增强展示：业务文件补充类型/上传人/时间与下载、网盘定位动作。 */
    detailed?: boolean
  }>(),
  { modelValue: () => [] }
)
const emit = defineEmits<{
  'update:modelValue': [value: string[]]
  'upload-status': [value: { pending: boolean; failed: boolean }]
}>()

const platform = useNocodePlatform()
const taskAccess = inject(taskFormAccessKey, undefined)
const user = useUserStore()
const router = useRouter()
// 网盘定位入口与菜单权限一致：没有业务文件入口权限的用户仍可查看附件，但不展示定位动作。
const canLocate = computed(() => !taskAccess && platform.hasPermission('drive:business:query'))
const businessUpload = computed(
  () => businessFileField(props.businessPolicy, props.fieldId) && !!props.objectId && !!props.fieldId
)
const businessRead = computed(() => !!props.objectId && !!props.recordId && !!props.fieldId)
const mixedMode = computed(() => businessUpload.value || businessRead.value)
const editable = computed(() => !props.disabled && !props.readOnly)
const hint = computed(() =>
  editable.value && businessUpload.value && !props.hideBusinessPath ? businessFileHint(props.businessPolicy) : ''
)
const accept = computed(() => (props.image ? 'image/png,image/jpeg,image/webp,image/gif' : undefined))
const ids = computed(() => (props.modelValue || []).map(String))
const localIds = ref<string[]>(ids.value)
const sessionId = computed(
  () =>
    businessSessionId(user.userInfo?.id, props.applicationId, props.objectId || '', props.recordId) +
    (taskAccess ? `:${taskAccess.scope()}` : '')
)
const userScope = computed(() => `${user.tenantInfo?.id || user.userInfo?.tenantId || '-'}:${user.userInfo?.id || '-'}`)

interface DisplayItem {
  id: string
  name: string
  size: number | null
  previewUrl?: string | null
  source: 'business' | 'temp' | 'legacy' | 'unavailable'
  note?: string
  entryId?: string | null
  mimeType?: string | null
  submitter?: string | null
  uploadedAt?: string | null
  content?:
    { kind: 'business'; query: BusinessFileContentQuery } | { kind: 'temporary'; query: BusinessTemporaryContentQuery }
}
interface UploadItem {
  uid: string
  name: string
  size: number
  percent: number
  status: 'uploading' | 'error'
  error: string
  idempotencyKey: string
  file: File
  mode: 'business' | 'legacy'
}

const stored = ref<DisplayItem[]>([])
const uploading = ref<UploadItem[]>([])
const expired = ref(0)
const names = ref(new Map<string, string>())
let alive = true
let loadSequence = 0
const objectUrls = new Set<string>()

async function loadNames() {
  const scope = userScope.value
  const resolved = await resolveBusinessUserNames(scope)
  if (alive && scope === userScope.value) names.value = resolved
}

function uploaderName(id: string) {
  return names.value.get(String(id)) || String(id)
}

/** 展示增强只作用于业务文件；普通系统附件没有网盘归属信息，保持原样。 */
function detailedMeta(item: DisplayItem) {
  return !!props.detailed && item.source === 'business'
}

// 上传状态提供给宿主表单，防止仍在上传或失败时误认为文件已保存。
watch(
  uploading,
  list =>
    emit('upload-status', {
      pending: list.some(item => item.status === 'uploading'),
      failed: list.some(item => item.status === 'error')
    }),
  { deep: true, immediate: true }
)

function legacyUrl(file: { configId?: number | string; path?: string }) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return file.configId != null && file.path
    ? `${base}/infra/file/${file.configId}/get/${file.path.split('/').map(encodeURIComponent).join('/')}`
    : null
}

function releaseObjectUrls() {
  for (const url of objectUrls) URL.revokeObjectURL(url)
  objectUrls.clear()
}

/**
 * 混合回显：同一字段内按文件身份解析——已保存的业务文件、本会话待保存的临时上传、
 * 未接入前的普通附件分别读取各自的内容与名称；解析失败的保留原始编号，不静默丢值。
 */
async function refresh() {
  const sequence = ++loadSequence
  const current = ids.value
  if (!current.length) {
    releaseObjectUrls()
    stored.value = []
    expired.value = 0
    return
  }
  if (!mixedMode.value) {
    // 未接入业务模式的普通附件由底座上传组件自行读取，本控件不重复查询。
    stored.value = []
    expired.value = 0
    return
  }
  const session = loadBusinessSession(sessionStorage, sessionId.value)
  const sessionFiles = session?.files || {}
  const found = new Map<string, DisplayItem>()
  let remaining = current
  if (businessRead.value && props.recordId && props.objectId && props.fieldId) {
    try {
      const page = await platform.bizFiles.files({
        applicationId: props.applicationId,
        objectId: props.objectId,
        recordId: props.recordId,
        detailId: props.detailId,
        rowId: props.detailRecordId,
        fieldId: props.fieldId,
        pageNo: 1,
        pageSize: 100
      })
      if (sequence !== loadSequence || !alive) return
      const byId = new Map(page.list.map(entry => [String(entry.fileId), entry]))
      // 编辑保存后临时缓存仍可能存在；服务端鉴权返回的已绑定身份优先，不能误判为会话过期。
      for (const id of remaining) {
        const entry = byId.get(id)
        if (entry) found.set(id, businessItem(entry))
      }
      if (props.detailed && page.list.some(entry => entry.submitter)) void loadNames()
      remaining = remaining.filter(id => !found.has(id))
    } catch {
      // 业务位置解析失败时不能降级到普通文件接口，否则可能绕过业务记录授权。
      for (const id of remaining) {
        found.set(id, {
          id,
          name: '文件信息未加载',
          size: null,
          source: 'unavailable',
          note: '业务文件授权校验失败，请重试'
        })
      }
      remaining = remaining.filter(id => !!sessionFiles[id])
    }
  }
  const tempIds = remaining.filter(id => sessionFiles[id])
  if (tempIds.length) {
    let active = true
    if (session) {
      try {
        active = await platform.bizFiles.renew(session.key)
      } catch {
        active = true
      }
    }
    if (sequence !== loadSequence || !alive) return
    for (const id of tempIds) {
      const file = sessionFiles[id]
      found.set(id, {
        id,
        name: file.name,
        size: file.size,
        previewUrl: null,
        source: active ? 'temp' : 'unavailable',
        note: active ? '待保存' : '上传已过期，请移除后重新上传',
        mimeType: file.mimeType,
        ...(active
          ? {
              content: {
                kind: 'temporary' as const,
                query: {
                  objectId: props.objectId!,
                  ...(taskAccess && props.detailId ? { detailId: props.detailId } : {}),
                  fieldId: props.fieldId!,
                  sessionKey: session!.key,
                  fileId: id
                }
              }
            }
          : {})
      })
    }
    expired.value = active ? 0 : tempIds.length
    remaining = remaining.filter(id => !found.has(id))
  } else {
    expired.value = 0
  }
  const legacy = remaining.length ? await legacyItems(remaining) : []
  if (sequence !== loadSequence || !alive) return
  for (const item of legacy) found.set(item.id, item)
  releaseObjectUrls()
  stored.value = current.map(id => found.get(id)).filter((item): item is DisplayItem => !!item)
  if (props.image) void loadThumbnails(sequence)
}

async function legacyItems(list: string[]): Promise<DisplayItem[]> {
  if (!list.length) return []
  try {
    const files: Array<{ id: string | number; name: string; size: number; configId?: number; path?: string }> =
      await request.get('/infra/file/get-list', { params: { ids: list.join(',') } })
    return list.map(id => {
      const file = files.find(item => String(item.id) === id)
      return file
        ? { id, name: file.name, size: file.size, previewUrl: legacyUrl(file), source: 'legacy' as const }
        : { id, name: '文件信息不可用', size: null, previewUrl: null, source: 'unavailable' as const }
    })
  } catch {
    return list.map(id => ({
      id,
      name: '文件信息未加载',
      size: null,
      previewUrl: null,
      source: 'unavailable' as const
    }))
  }
}

function businessItem(entry: BusinessFileEntry): DisplayItem {
  const location = {
    applicationId: props.applicationId,
    objectId: props.objectId!,
    recordId: props.recordId!,
    detailId: props.detailId,
    rowId: props.detailRecordId,
    fieldId: props.fieldId!,
    entryId: entry.entryId
  }
  return {
    id: String(entry.fileId),
    name: entry.name,
    size: entry.size,
    previewUrl: null,
    source: 'business',
    entryId: String(entry.entryId),
    mimeType: entry.mimeType,
    submitter: entry.submitter,
    uploadedAt: entry.uploadedAt,
    content: { kind: 'business', query: location }
  }
}

async function content(item: DisplayItem): Promise<Blob | null> {
  if (!item.content) return null
  return item.content.kind === 'business'
    ? platform.bizFiles.content(item.content.query)
    : platform.bizFiles.temporaryContent(item.content.query)
}

async function loadThumbnails(sequence: number) {
  for (const item of stored.value) {
    if (!item.content || !businessImageType(item.mimeType)) continue
    try {
      const blob = await content(item)
      if (!blob || !businessImageType(blob.type) || sequence !== loadSequence || !alive) continue
      const url = URL.createObjectURL(blob)
      objectUrls.add(url)
      item.previewUrl = url
    } catch {
      /* 缩略图失败不阻断文件名和显式下载操作 */
    }
  }
}

watch(
  () => ids.value.join(','),
  () => {
    localIds.value = ids.value
    void refresh()
  },
  { immediate: true }
)
watch([mixedMode, () => props.recordId, () => props.detailRecordId], () => void refresh())
watch(userScope, () => {
  names.value = new Map()
  if (props.detailed && stored.value.some(item => item.submitter)) void loadNames()
})
onBeforeUnmount(() => {
  alive = false
  loadSequence++
  releaseObjectUrls()
})

function beforeUpload(file: File) {
  if (accept.value && !accept.value.split(',').some(type => type.trim() === file.type)) {
    message.error('请选择支持的文件格式')
    return false
  }
  if (file.size > BUSINESS_FILE_MAX_SIZE) {
    message.error('文件大小不能超过 50MB')
    return false
  }
  if (!canAppendBusinessFile(localIds.value, uploading.value.length, file.size)) {
    message.error('每个字段最多上传 100 个文件')
    return false
  }
  return true
}

function customUpload(options: { file: File; onSuccess?: (body: unknown) => void; onError?: (error: Error) => void }) {
  if (!canAppendBusinessFile(localIds.value, uploading.value.length, options.file.size)) {
    const error = new Error('每个字段最多上传 100 个文件')
    message.error(error.message)
    options.onError?.(error)
    return
  }
  // 回调和列表必须操作同一响应式对象，否则进度/失败状态不触发更新。
  const item = reactive<UploadItem>({
    uid: String((options.file as File & { uid?: string }).uid ?? `u-${newBusinessSessionKey()}`),
    name: options.file.name,
    size: options.file.size,
    percent: 0,
    status: 'uploading',
    error: '',
    // 重试沿用同一幂等键：服务端按（上传人，幂等键）返回同一文件，不会重复占用存储。
    idempotencyKey: newBusinessSessionKey(),
    file: options.file,
    mode: businessUpload.value ? 'business' : 'legacy'
  })
  uploading.value.push(item)
  void send(item, options)
}

async function send(
  item: UploadItem,
  options?: { onSuccess?: (body: unknown) => void; onError?: (error: Error) => void }
) {
  item.status = 'uploading'
  item.percent = 0
  item.error = ''
  try {
    const result =
      item.mode === 'business'
        ? await platform.bizFiles.upload(
            {
              applicationId: props.applicationId,
              objectId: props.objectId!,
              recordId: props.recordId,
              detailId: props.detailId,
              fieldId: props.fieldId!,
              sessionKey: businessSession(sessionStorage, sessionId.value).key,
              idempotencyKey: item.idempotencyKey
            },
            item.file,
            { onProgress: (loaded, total) => (item.percent = total ? Math.round((loaded * 100) / total) : 0) }
          )
        : await uploadLegacy(item)
    if (!alive) return
    const fileId = String(result.fileId)
    if (item.mode === 'business') {
      rememberBusinessTempFile(sessionStorage, sessionId.value, businessSession(sessionStorage, sessionId.value), {
        fileId,
        name: result.name || item.name,
        size: result.size,
        mimeType: result.mimeType,
        fieldId: props.fieldId!
      })
    }
    uploading.value = uploading.value.filter(entry => entry.uid !== item.uid)
    options?.onSuccess?.(result)
    const nextIds = appendBusinessFileId(localIds.value, fileId)
    if (nextIds.length !== localIds.value.length) {
      localIds.value = nextIds
      emit('update:modelValue', localIds.value)
    }
  } catch (error) {
    if (!alive) return
    item.status = 'error'
    item.error = errorMessage(error)
    options?.onError?.(error as Error)
  }
}

async function uploadLegacy(item: UploadItem) {
  const body = new FormData()
  body.append('file', item.file)
  const result = await request.post<{
    id: string | number
    name?: string
    size?: number
    type?: string | null
  }>('/infra/file/upload', body, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
    quiet: true,
    onUploadProgress: progress => {
      item.percent = progress.total ? Math.round((progress.loaded * 100) / progress.total) : 0
    }
  })
  return {
    fileId: result.id,
    name: result.name || item.name,
    size: result.size ?? item.size,
    mimeType: result.type || item.file.type || null
  }
}

function retry(item: UploadItem) {
  void send(item)
}
function dropUpload(item: UploadItem) {
  uploading.value = uploading.value.filter(entry => entry.uid !== item.uid)
}
function remove(id: string) {
  if (!editable.value) return
  const session = loadBusinessSession(sessionStorage, sessionId.value)
  if (session?.files[id]) forgetBusinessTempFile(sessionStorage, sessionId.value, session, id)
  localIds.value = localIds.value.filter(value => value !== id)
  emit('update:modelValue', localIds.value)
}
async function open(item: DisplayItem) {
  if (item.source === 'legacy' && item.previewUrl) {
    window.open(item.previewUrl, '_blank', 'noopener,noreferrer')
    return
  }
  try {
    const blob = await content(item)
    if (!blob) return
    if (!businessPreviewableType(blob.type) || !openBusinessBlob(blob)) {
      downloadBusinessBlob(blob, item.name)
      message.info('此文件类型不支持在线预览，已转为安全下载')
    }
  } catch (error) {
    message.error(errorMessage(error))
  }
}
async function download(item: DisplayItem) {
  try {
    const blob = await content(item)
    if (blob) downloadBusinessBlob(blob, item.name)
  } catch (error) {
    message.error(errorMessage(error))
  }
}
/** 定位携带完整位置身份，由业务文件入口按同一授权链重新解析；入口页负责高亮与返回业务记录。 */
function locate(item: DisplayItem) {
  if (!canLocate.value || !item.entryId || !props.objectId || !props.recordId || !props.fieldId) return
  void router.push({
    path: '/drive/business',
    query: {
      applicationId: props.applicationId || undefined,
      objectId: props.objectId,
      recordId: props.recordId,
      detailId: props.detailId || undefined,
      rowId: props.detailRecordId || undefined,
      fieldId: props.fieldId,
      entryId: item.entryId
    }
  })
}
</script>
<template>
  <div class="business-file-field">
    <template v-if="!mixedMode">
      <span v-if="disabled && !modelValue?.length">—</span>
      <InfraUpload
        v-else
        :model-value="modelValue || []"
        :disabled="disabled || readOnly"
        :accept="accept"
        :list-type="image ? 'picture' : 'text'"
        :max-count="100"
        @update:model-value="value => emit('update:modelValue', value)"
        @upload-status="value => emit('upload-status', value)"
      >
        <a-button v-if="!disabled && !readOnly">{{ image ? '选择图片' : '选择文件' }}</a-button>
      </InfraUpload>
    </template>
    <template v-else>
      <div v-if="editable && localIds.length + uploading.length < BUSINESS_FILE_MAX_COUNT" class="biz-upload-actions">
        <a-upload
          :show-upload-list="false"
          :multiple="true"
          :accept="accept"
          :max-count="BUSINESS_FILE_MAX_COUNT"
          :before-upload="beforeUpload"
          :custom-request="customUpload"
        >
          <a-button>
            <UploadOutlined />
            {{ image ? '上传图片' : '上传文件' }}
          </a-button>
        </a-upload>
      </div>
      <p v-if="hint" class="biz-path-hint">{{ hint }}</p>
      <ul v-if="stored.length || uploading.length" class="biz-file-list">
        <li v-for="item in stored" :key="item.id" class="biz-file-item">
          <img v-if="image && item.previewUrl" :src="item.previewUrl" class="biz-thumb" :alt="item.name" />
          <PictureOutlined v-else-if="image" class="biz-icon" />
          <FileOutlined v-else class="biz-icon" />
          <div class="biz-file-main">
            <a v-if="item.previewUrl || item.content" class="biz-name" @click="open(item)">{{ item.name }}</a>
            <span v-else class="biz-name">{{ item.name }}</span>
            <div v-if="detailedMeta(item)" class="biz-detail-meta">
              <span v-if="item.mimeType">{{ item.mimeType }}</span>
              <span v-if="item.size != null">{{ formatFileSize(item.size) }}</span>
              <span v-if="item.submitter">{{ uploaderName(item.submitter) }}</span>
              <span v-if="item.uploadedAt">{{ formatDateTime(item.uploadedAt) }}</span>
            </div>
          </div>
          <span v-if="!detailedMeta(item) && item.size != null" class="biz-meta">{{ formatFileSize(item.size) }}</span>
          <a-tag v-if="item.source === 'temp'" color="blue">{{ item.note || '待保存' }}</a-tag>
          <a-tag v-else-if="item.source === 'unavailable' && item.note" color="red">{{ item.note }}</a-tag>
          <template v-if="detailedMeta(item)">
            <a-button v-if="item.previewUrl || item.content" type="link" size="small" @click="open(item)">
              预览
            </a-button>
            <a-button v-if="item.content" type="link" size="small" @click="download(item)">下载</a-button>
            <a-button v-if="canLocate" type="link" size="small" @click="locate(item)">在网盘中查看</a-button>
          </template>
          <a-button v-if="editable" type="link" size="small" danger @click="remove(item.id)">移除</a-button>
        </li>
        <li v-for="item in uploading" :key="'upload-' + item.uid" class="biz-file-item">
          <FileOutlined class="biz-icon" />
          <span class="biz-name">{{ item.name }}</span>
          <template v-if="item.status === 'uploading'">
            <a-progress :percent="item.percent" size="small" class="biz-progress" />
            <span class="biz-meta">上传中</span>
          </template>
          <template v-else>
            <span class="biz-error">{{ item.error || '上传失败' }}</span>
            <a-button type="link" size="small" @click="retry(item)">
              <RedoOutlined />
              重试
            </a-button>
            <a-button type="link" size="small" danger @click="dropUpload(item)">移除</a-button>
          </template>
        </li>
      </ul>
      <p v-else class="biz-empty">{{ image ? '尚未上传图片' : '尚未上传文件' }}</p>
      <a-alert
        v-if="expired"
        type="warning"
        show-icon
        :message="`有 ${expired} 个文件的上传会话已过期（超过 24 小时未保存），请移除后重新上传；过期内容不会被保留。`"
      />
    </template>
  </div>
</template>
<style scoped>
.biz-upload-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.biz-path-hint {
  margin: 8px 0 0;
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
.biz-file-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}
.biz-file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px solid var(--color-split-line, #f0f0f0);
}
.biz-file-item:last-child {
  border-bottom: none;
}
.biz-thumb {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
}
.biz-icon {
  color: var(--text-color-secondary, #8c8c8c);
}
.biz-file-main {
  flex: 1;
  min-width: 0;
}
.biz-name {
  word-break: break-all;
}
.biz-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 2px;
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
a.biz-name {
  color: var(--primary-color, #1677ff);
  cursor: pointer;
}
a.biz-name:hover {
  text-decoration: underline;
}
.biz-meta {
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
.biz-progress {
  width: 120px;
  margin: 0;
}
.biz-error {
  color: var(--error-color, #ff4d4f);
  font-size: 12px;
}
.biz-empty {
  margin: 8px 0 0;
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
</style>
