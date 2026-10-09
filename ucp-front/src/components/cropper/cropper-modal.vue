<script lang="ts" setup>
import type { CropendResult, CropperModalProps, CropperType } from './typing'
import type CropperImage from './cropper.vue'
import CropperImageComponent from './cropper.vue'

import {
  ReloadOutlined,
  RotateLeftOutlined,
  RotateRightOutlined,
  SwapOutlined,
  UploadOutlined,
  ZoomInOutlined,
  ZoomOutOutlined
} from '@ant-design/icons-vue'
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'

defineOptions({ name: 'CropperModal' })

const props = withDefaults(defineProps<CropperModalProps>(), {
  open: false,
  circled: true,
  size: 0,
  src: '',
  uploadApi: () => Promise.resolve()
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  uploadSuccess: [value: { data: any; source: string }]
  uploadError: [value: { msg: string }]
}>()

let filename = 'avatar.png'
let scaleX = 1
let scaleY = 1

const src = ref(props.src || '')
const previewSource = ref('')
const cropper = ref<CropperType>()
const cropperImageRef = ref<InstanceType<typeof CropperImage> | null>(null)
const confirmLoading = ref(false)
const modalLoading = ref(false)
const renderKey = ref(0)

watch(
  () => props.src,
  value => {
    if (!props.open || !src.value) src.value = value || ''
  }
)

watch(
  () => props.open,
  isOpen => {
    if (isOpen) {
      src.value = props.src || ''
      previewSource.value = src.value
      renderKey.value += 1
      modalLoading.value = !!src.value
    } else {
      previewSource.value = ''
      modalLoading.value = false
      cropper.value = undefined
      cropperImageRef.value = null
    }
  }
)

function dataURLtoBlob(dataUrl: string) {
  const [header, data] = dataUrl.split(',')
  const mime = header.match(/:(.*?);/)?.[1] || 'image/png'
  const binary = window.atob(data)
  const array = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) array[i] = binary.charCodeAt(i)
  return new Blob([array], { type: mime })
}

function handleBeforeUpload(file: File) {
  if (props.size > 0 && file.size > 1024 * 1024 * props.size) {
    emit('uploadError', { msg: `图片不能超过 ${props.size}MB` })
    return false
  }

  if (src.value && src.value.startsWith('blob:')) URL.revokeObjectURL(src.value)
  src.value = URL.createObjectURL(file)
  previewSource.value = src.value
  filename = file.name
  modalLoading.value = true
  return false
}

function handleCropend({ imgBase64 }: CropendResult) {
  previewSource.value = imgBase64
}

function handleReady(cropperInstance: CropperType) {
  cropper.value = cropperInstance
  modalLoading.value = false
}

function handleCropperError() {
  modalLoading.value = false
  previewSource.value = src.value
}

function handlerToolbar(event: 'reset' | 'rotate' | 'scaleX' | 'scaleY' | 'zoom', arg?: number) {
  if (event === 'scaleX') scaleX = arg = scaleX === -1 ? 1 : -1
  if (event === 'scaleY') scaleY = arg = scaleY === -1 ? 1 : -1
  ;(cropper.value as any)?.[event]?.(arg)
}

async function handleOk() {
  if (!cropperImageRef.value) {
    message.warn('未选择图片')
    return
  }

  try {
    confirmLoading.value = true
    const dataUrl = await cropperImageRef.value.getCropImage()
    const blob = dataURLtoBlob(dataUrl)
    const data = await props.uploadApi({ file: blob, filename, name: 'file' })
    emit('uploadSuccess', { data, source: dataUrl })
    emit('update:open', false)
  } catch {
    message.error('头像上传失败')
  } finally {
    confirmLoading.value = false
  }
}

function handleCancel() {
  emit('update:open', false)
}
</script>

<template>
  <a-modal
    :confirm-loading="confirmLoading"
    :open="open"
    cancel-text="取消"
    ok-text="确认上传"
    title="裁剪头像"
    width="760px"
    @cancel="handleCancel"
    @ok="handleOk"
  >
    <a-spin :spinning="modalLoading">
      <div class="cropper-modal-content">
        <div class="cropper-editor">
          <div class="cropper-stage">
            <CropperImageComponent
              v-if="src"
              :key="`${src}-${renderKey}`"
              ref="cropperImageRef"
              :circled="circled"
              :src="src"
              height="300px"
              real-time-preview
              @cropend="handleCropend"
              @ready="handleReady"
              @cropend-error="handleCropperError"
            />
          </div>

          <div class="cropper-toolbar">
            <a-upload :before-upload="handleBeforeUpload" :file-list="[]" accept="image/*">
              <a-tooltip placement="bottom" title="选择图片">
                <a-button type="primary">
                  <UploadOutlined />
                </a-button>
              </a-tooltip>
            </a-upload>
            <a-space>
              <a-tooltip placement="bottom" title="重置">
                <a-button :disabled="!src" size="small" @click="handlerToolbar('reset')">
                  <ReloadOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip placement="bottom" title="向左旋转">
                <a-button :disabled="!src" size="small" @click="handlerToolbar('rotate', -45)">
                  <RotateLeftOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip placement="bottom" title="向右旋转">
                <a-button :disabled="!src" size="small" @click="handlerToolbar('rotate', 45)">
                  <RotateRightOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip placement="bottom" title="水平翻转">
                <a-button :disabled="!src" size="small" @click="handlerToolbar('scaleX')">
                  <SwapOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip placement="bottom" title="垂直翻转">
                <a-button :disabled="!src" class="vertical-swap-btn" size="small" @click="handlerToolbar('scaleY')">
                  <SwapOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip placement="bottom" title="放大">
                <a-button :disabled="!src" size="small" @click="handlerToolbar('zoom', 0.1)">
                  <ZoomInOutlined />
                </a-button>
              </a-tooltip>
              <a-tooltip placement="bottom" title="缩小">
                <a-button :disabled="!src" size="small" @click="handlerToolbar('zoom', -0.1)">
                  <ZoomOutOutlined />
                </a-button>
              </a-tooltip>
            </a-space>
          </div>
        </div>

        <div class="cropper-preview-panel">
          <div class="cropper-preview-main">
            <img v-if="previewSource" :src="previewSource" alt="头像预览" />
          </div>
          <div v-if="previewSource" class="cropper-preview-list">
            <a-avatar :src="previewSource" size="large" />
            <a-avatar :size="48" :src="previewSource" />
            <a-avatar :size="64" :src="previewSource" />
            <a-avatar :size="80" :src="previewSource" />
          </div>
        </div>
      </div>
    </a-spin>
  </a-modal>
</template>

<style scoped>
.cropper-modal-content {
  display: flex;
  gap: 16px;
  min-height: 360px;
}

.cropper-editor {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
}

.cropper-stage {
  position: relative;
  height: 300px;
  overflow: hidden;
  background: linear-gradient(180deg, #f8fafc 0%, #e5e7eb 100%);
}

.cropper-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.vertical-swap-btn :deep(.anticon) {
  transform: rotate(90deg);
}

.cropper-preview-panel {
  display: flex;
  width: 260px;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.cropper-preview-main {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 224px;
  height: 224px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 50%;
  background: #f9fafb;
}

.cropper-preview-main img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cropper-preview-list {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-around;
  border-top: 1px solid #e5e7eb;
  padding-top: 12px;
}

@media (max-width: 640px) {
  .cropper-modal-content {
    flex-direction: column;
  }

  .cropper-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .cropper-preview-panel {
    width: 100%;
  }
}
</style>
