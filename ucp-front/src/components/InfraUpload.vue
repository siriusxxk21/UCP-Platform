<template>
  <div>
    <a-upload
      v-model:file-list="fileList"
      :disabled="disabled"
      :accept="accept"
      :max-count="maxCount"
      :list-type="listType"
      :custom-request="handleUpload"
      :before-upload="beforeUpload"
      @remove="handleRemove"
    >
      <slot />
    </a-upload>
    <a-alert v-if="metadataError" type="warning" show-icon :message="metadataError">
      <template #action>
        <a-button size="small" :loading="metadataLoading" @click="loadFileInfos">重新读取</a-button>
      </template>
    </a-alert>
    <a-alert
      v-if="fileList.some(file => file.status === 'error')"
      type="error"
      show-icon
      message="有文件上传失败，请移除后重试。失败文件尚未关联到业务记录。"
    />
  </div>
</template>

<script lang="ts" setup>
import { ref, watch, onScopeDispose } from 'vue'
import { message, Upload } from 'ant-design-vue'
import request from '@/utils/request'

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'

const props = withDefaults(
  defineProps<{
    modelValue?: string[]
    disabled?: boolean
    /** 按 MIME 精确限制可选素材，普通附件保留底座的默认行为。 */
    accept?: string
    maxCount?: number
    listType?: 'text' | 'picture' | 'picture-card'
  }>(),
  {
    modelValue: () => [],
    disabled: false,
    listType: 'text'
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
  'upload-status': [value: { pending: boolean; failed: boolean }]
}>()

const fileList = ref<any[]>([])
const committedIds = ref<string[]>([...props.modelValue])
const metadataError = ref(''),
  metadataLoading = ref(false)
let metadataSequence = 0,
  alive = true
const removedUploads = new Set<string>()
// 上传状态提供给宿主表单，防止仍在上传或失败时误认为文件已保存。
watch(
  fileList,
  files =>
    emit('upload-status', {
      pending: files.some(file => file.status === 'uploading'),
      failed: files.some(file => file.status === 'error')
    }),
  { deep: true }
)

/** 将 FileDO 数组转换为 a-upload 的 file-list 格式 */
function toFileList(list: any[]): any[] {
  return (Array.isArray(list) ? list : []).map((f: any) => ({
    uid: String(f.id),
    name: f.name,
    status: 'done',
    url: `${baseURL}/infra/file/${f.configId}/get/${f.path}`,
    response: f
  }))
}

/** 从后端加载文件信息 */
async function loadFileInfos() {
  const sequence = ++metadataSequence
  const ids = [...props.modelValue]
  const current = () => alive && sequence === metadataSequence && ids.join(',') === props.modelValue.join(',')
  const transient = () => fileList.value.filter(file => file.status === 'uploading' || file.status === 'error')
  metadataError.value = ''
  metadataLoading.value = !!ids.length
  // 新值立即移除旧值的预览；查询失败仍保留真实 ID，不修改业务模型。
  fileList.value = [
    ...fileList.value.filter(
      file =>
        ids.includes(String(file.response?.id || file.uid)) && file.status !== 'uploading' && file.status !== 'error'
    ),
    ...transient()
  ]
  if (!ids.length) return
  try {
    const list: any[] = await request.get('/infra/file/get-list', { params: { ids: ids.join(',') } })
    if (!current()) return
    const known = toFileList(list).filter(file => ids.includes(file.uid))
    const missing = ids.filter(id => !known.some(file => file.uid === id))
    fileList.value = [
      ...ids.map(id => known.find(file => file.uid === id) || { uid: id, name: '文件信息不可用', status: 'done' }),
      ...transient()
    ]
    if (missing.length)
      metadataError.value = `有 ${missing.length} 个文件不存在或当前无权读取。已保留选择；可重试或移除失效文件。`
  } catch {
    if (!current()) return
    const known = fileList.value
    fileList.value = [
      ...ids.map(
        id =>
          known.find(file => String(file.response?.id || file.uid) === id) || {
            uid: id,
            name: '文件信息未加载',
            status: 'done'
          }
      ),
      ...transient()
    ]
    metadataError.value = '暂时无法读取文件信息，已保留选择，请重新读取。'
  } finally {
    if (current()) metadataLoading.value = false
  }
}

watch(
  () => props.modelValue.join(','),
  () => {
    committedIds.value = [...props.modelValue]
    void loadFileInfos()
  },
  { immediate: true }
)
onScopeDispose(() => {
  alive = false
  metadataSequence++
})

function beforeUpload(file: File) {
  if (
    props.accept &&
    !props.accept
      .split(',')
      .map(v => v.trim())
      .includes(file.type)
  ) {
    message.error('请选择支持的文件格式')
    return Upload.LIST_IGNORE
  }
  const sizeAllowed = file.size <= 50 * 1024 * 1024
  if (!sizeAllowed) {
    message.error('文件大小不能超过 50MB')
    return Upload.LIST_IGNORE
  }
  const pending = fileList.value.filter(item => item.status === 'uploading').length
  if (props.maxCount && committedIds.value.length + pending >= props.maxCount) {
    message.error(`最多上传 ${props.maxCount} 个文件`)
    return Upload.LIST_IGNORE
  }
  return true
}

function handleUpload(options: any) {
  const { file, onSuccess, onError } = options
  const formData = new FormData()
  formData.append('file', file)
  request
    .request({
      url: '/infra/file/upload',
      baseURL: baseURL,
      method: 'POST',
      data: formData,
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    .then(res => {
      if (!alive || removedUploads.has(String(file.uid))) return
      const result: any = (res as any).data || res
      const fileDo: any = result.data || result
      const idStr = String(fileDo.id)
      const newIds =
        props.maxCount === 1
          ? [idStr]
          : committedIds.value.includes(idStr)
            ? [...committedIds.value]
            : [...committedIds.value, idStr]
      committedIds.value = newIds
      emit('update:modelValue', newIds)
      onSuccess(fileDo)
      // 为 Ant Design 自动创建的条目补充 url 用于预览下载
      const entry = fileList.value.find(f => f.uid === idStr || f.uid === file.uid)
      if (entry) {
        entry.url = `${baseURL}/infra/file/${fileDo.configId}/get/${fileDo.path}`
      }
    })
    .catch(err => {
      if (!alive || removedUploads.has(String(file.uid))) return
      onError(err)
      message.error('上传失败')
    })
}

function handlePreview(file: any) {
  if (file.url) {
    window.open(file.url, '_blank')
  }
}

function handleRemove(info: any) {
  removedUploads.add(String(info.uid))
  const id = String(info.response?.id || info.uid)
  const newIds = committedIds.value.filter(v => v !== id)
  committedIds.value = newIds
  emit('update:modelValue', newIds)
}
</script>

<style scoped>
:deep(.ant-upload-list-item-name) {
  color: #1890ff;
  cursor: pointer;
}
:deep(.ant-upload-list-item-name:hover) {
  text-decoration: underline;
}
</style>
