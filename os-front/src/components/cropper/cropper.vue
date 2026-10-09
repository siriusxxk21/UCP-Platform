<script lang="ts" setup>
import type { CSSProperties } from 'vue'
import { computed, nextTick, onMounted, onUnmounted, ref, useAttrs, watch } from 'vue'
import type { CropperProps } from './typing'
import { defaultOptions } from './typing'
import Cropper from 'cropperjs'

import 'cropperjs/dist/cropper.css'

defineOptions({ name: 'CropperImage' })

const AVATAR_MAX_DIMENSION = 512

const props = withDefaults(defineProps<CropperProps>(), {
  src: '',
  alt: '',
  circled: false,
  realTimePreview: false,
  height: '300px',
  crossorigin: undefined,
  options: () => ({})
})

const emit = defineEmits<{
  cropend: [value: { imgBase64: string; imgInfo: Cropper.Data }]
  ready: [value: Cropper]
  cropendError: []
}>()

const attrs = useAttrs()
const imgElRef = ref<HTMLImageElement | null>(null)
const cropper = ref<Cropper | null>(null)
const isReady = ref(false)

let previewTimer: number | null = null

const imageStyle = computed<CSSProperties>(() => ({
  height: typeof props.height === 'number' ? `${props.height}px` : props.height,
  maxWidth: '100%'
}))

const wrapperStyle = computed<CSSProperties>(() => ({
  height: typeof props.height === 'number' ? `${props.height}px` : props.height
}))

const classNames = computed(() => [
  attrs.class,
  {
    'cropper-image--circled': props.circled
  }
])

onMounted(() => {
  if (props.src) init()
})

watch(
  () => props.src,
  async () => {
    resetCropper()
    await nextTick()
  }
)

onUnmounted(() => {
  resetCropper()
})

function resetCropper() {
  if (previewTimer) {
    window.clearTimeout(previewTimer)
    previewTimer = null
  }
  isReady.value = false
  cropper.value?.destroy()
  cropper.value = null
}

function init() {
  const imgEl = imgElRef.value
  if (!imgEl || !props.src || cropper.value) return

  cropper.value = new Cropper(imgEl, {
    ...defaultOptions,
    ready: () => {
      isReady.value = true
      emit('ready', cropper.value!)
      cropped()
    },
    crop: scheduleCropped,
    zoom: scheduleCropped,
    cropmove: scheduleCropped,
    ...props.options
  })
}

function scheduleCropped() {
  if (!props.realTimePreview) return
  if (previewTimer) window.clearTimeout(previewTimer)
  previewTimer = window.setTimeout(() => {
    previewTimer = null
    cropped()
  }, 220)
}

function getCropImageBlob() {
  if (!cropper.value) return Promise.reject(new Error('Cropper not ready'))

  const croppedCanvas = getSizeLimitedCanvas()
  const sourceCanvas = props.circled ? getRoundedCanvas(croppedCanvas) : croppedCanvas
  return new Promise<Blob>((resolve, reject) => {
    sourceCanvas.toBlob(blob => {
      if (blob) resolve(blob)
      else reject(new Error('Crop result is empty'))
    }, 'image/png')
  })
}

async function getCropImage() {
  const blob = await getCropImageBlob()
  return new Promise<string>((resolve, reject) => {
    const fileReader = new FileReader()
    fileReader.readAsDataURL(blob)
    fileReader.onloadend = e => resolve(String(e.target?.result || ''))
    fileReader.onerror = () => reject(new Error('Read crop result failed'))
  })
}

function cropped() {
  if (!cropper.value) return

  const imgInfo = cropper.value.getData()
  getCropImage()
    .then(imgBase64 => {
      emit('cropend', { imgBase64, imgInfo })
    })
    .catch(() => {
      emit('cropendError')
    })
}

function getSizeLimitedCanvas() {
  return cropper.value!.getCroppedCanvas({
    maxWidth: AVATAR_MAX_DIMENSION,
    maxHeight: AVATAR_MAX_DIMENSION,
    imageSmoothingEnabled: true,
    imageSmoothingQuality: 'high'
  })
}

function getRoundedCanvas(sourceCanvas: HTMLCanvasElement) {
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')!
  const width = sourceCanvas.width
  const height = sourceCanvas.height
  canvas.width = width
  canvas.height = height
  context.imageSmoothingEnabled = true
  context.drawImage(sourceCanvas, 0, 0, width, height)
  context.globalCompositeOperation = 'destination-in'
  context.beginPath()
  context.arc(width / 2, height / 2, Math.min(width, height) / 2, 0, 2 * Math.PI, true)
  context.fill()
  return canvas
}

function handleImageError() {
  emit('cropendError')
}

defineExpose({
  getCropImage,
  getCropImageBlob
})
</script>

<template>
  <div :class="classNames" :style="wrapperStyle">
    <img
      v-show="isReady"
      ref="imgElRef"
      :alt="alt"
      :crossorigin="crossorigin"
      :src="src"
      :style="imageStyle"
      class="cropper-image"
      @error="handleImageError"
      @load="init"
    />
  </div>
</template>

<style scoped>
.cropper-image {
  display: block;
  max-width: 100%;
}
</style>

<style>
.cropper-image--circled .cropper-view-box,
.cropper-image--circled .cropper-face {
  border-radius: 50%;
}
</style>
