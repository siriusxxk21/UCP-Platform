<script lang="ts" setup>
import type { CSSProperties } from 'vue'
import { computed, ref, watch, watchEffect } from 'vue'
import type { CropperAvatarProps } from './typing'

import { CloudUploadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'

import CropperModal from './cropper-modal.vue'

defineOptions({ name: 'CropperAvatar' })

const props = withDefaults(defineProps<CropperAvatarProps>(), {
  width: 200,
  value: '',
  showBtn: true,
  btnText: '',
  uploadApi: () => Promise.resolve(),
  size: 5
})

const emit = defineEmits<{
  'update:value': [value: string]
  change: [value: { data: any; source: string }]
}>()

const sourceValue = ref(props.value || '')
const modalOpen = ref(false)

const width = computed(() => `${`${props.width}`.replace(/px/, '')}px`)
const iconWidth = computed(() => `${Number.parseInt(`${props.width}`.replace(/px/, '')) / 2}px`)
const style = computed<CSSProperties>(() => ({ width: width.value }))
const imageWrapperStyle = computed<CSSProperties>(() => ({ height: width.value, width: width.value }))

watchEffect(() => {
  sourceValue.value = props.value || ''
})

watch(
  () => sourceValue.value,
  value => {
    emit('update:value', value)
  }
)

function handleUploadSuccess({ data, source }: { data: any; source: string }) {
  const avatarUrl = typeof data === 'string' ? data : data?.url || data?.path || ''
  if (avatarUrl) sourceValue.value = avatarUrl
  emit('change', { data, source })
  message.success('头像上传成功')
}

function handleUploadError({ msg }: { msg: string }) {
  message.error(msg || '头像上传失败')
}

function openModal() {
  modalOpen.value = true
}
</script>

<template>
  <div :style="style" class="cropper-avatar">
    <div :style="imageWrapperStyle" class="avatar-wrapper" @click="openModal">
      <div :style="imageWrapperStyle" class="avatar-mask">
        <CloudUploadOutlined :style="{ fontSize: iconWidth }" />
      </div>
      <img v-if="sourceValue" :src="sourceValue" alt="avatar" class="avatar-image" />
      <div v-else class="avatar-placeholder">U</div>
    </div>

    <a-button v-if="showBtn" class="avatar-button" @click="openModal">
      {{ btnText || '选择图片' }}
    </a-button>

    <CropperModal
      v-model:open="modalOpen"
      :size="size"
      :src="sourceValue"
      :upload-api="uploadApi"
      @upload-success="handleUploadSuccess"
      @upload-error="handleUploadError"
    />
  </div>
</template>

<style scoped>
.cropper-avatar {
  display: inline-block;
  text-align: center;
}

.avatar-wrapper {
  position: relative;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid #e5e7eb;
  border-radius: 50%;
  background: #f9fafb;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.86);
  background: rgba(0, 0, 0, 0.42);
  opacity: 0;
  transition: opacity 0.2s;
}

.avatar-wrapper:hover .avatar-mask {
  opacity: 1;
}

.avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  font-size: 28px;
  font-weight: 600;
}

.avatar-button {
  margin-top: 8px;
}
</style>
