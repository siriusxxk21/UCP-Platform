<script setup lang="ts">
import { InboxOutlined } from '@ant-design/icons-vue'
import { message, Upload, type UploadFile } from 'ant-design-vue'
import { computed, ref, watch } from 'vue'
import { uploadFile } from '@/api/infra/file'
import { validateUploadDirectory, validateUploadFileName } from './fileUploadValidation'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'success'): void
}>()

const fileList = ref<UploadFile[]>([])
const directory = ref('')
const uploading = ref(false)
const progress = ref(0)
const directoryError = computed(() => validateUploadDirectory(directory.value))

function resetForm(open: boolean) {
  if (open) {
    fileList.value = []
    directory.value = ''
    progress.value = 0
  }
}

watch(() => props.open, resetForm)

function beforeUpload(file: File) {
  const fileNameError = validateUploadFileName(file.name)
  if (fileNameError) {
    message.error(fileNameError)
    return Upload.LIST_IGNORE
  }
  fileList.value = [file as File & UploadFile]
  return false
}

function handleRemove() {
  fileList.value = []
  progress.value = 0
}

function handleCancel() {
  if (!uploading.value) emit('update:open', false)
}

async function handleUpload() {
  if (directoryError.value) {
    message.error(directoryError.value)
    return
  }

  const selected = fileList.value[0]?.originFileObj ?? fileList.value[0]
  if (!selected) {
    message.warning('请选择要上传的文件')
    return
  }

  uploading.value = true
  progress.value = 0
  try {
    function updateProgress(value: number) {
      progress.value = value
    }

    await uploadFile({ file: selected as File, directory: directory.value }, updateProgress)
    message.success('上传成功')
    emit('update:open', false)
    emit('success')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <a-modal
    :open="open"
    title="上传文件"
    :confirm-loading="uploading"
    :mask-closable="!uploading"
    :closable="!uploading"
    ok-text="上传"
    cancel-text="取消"
    @ok="handleUpload"
    @cancel="handleCancel"
  >
    <a-form layout="vertical">
      <a-form-item
        :help="directoryError || '仅支持安全的相对路径，例如 documents/2026'"
        :validate-status="directoryError ? 'error' : undefined"
        label="存储目录"
      >
        <a-input v-model:value="directory" :disabled="uploading" allow-clear placeholder="可选，例如 documents/2026" />
      </a-form-item>
      <a-form-item label="文件" required>
        <a-upload-dragger
          v-model:file-list="fileList"
          :before-upload="beforeUpload"
          :disabled="uploading"
          :max-count="1"
          :multiple="false"
          @remove="handleRemove"
        >
          <p class="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p class="ant-upload-text">点击或拖拽文件到此区域上传</p>
          <p class="ant-upload-hint">单次上传一个文件，文件类型由后端存储策略决定</p>
        </a-upload-dragger>
      </a-form-item>
      <a-progress v-if="uploading" :percent="progress" size="small" />
    </a-form>
  </a-modal>
</template>
