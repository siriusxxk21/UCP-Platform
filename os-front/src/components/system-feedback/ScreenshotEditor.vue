<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { selectedArea, type Area } from './feedback'

const props = defineProps<{ source: string }>()
const emit = defineEmits<{ confirm: [file: File]; cancel: [] }>()
const canvas = ref<HTMLCanvasElement>()
const mode = ref<'crop' | 'mark' | 'hide'>('crop')
const busy = ref(false)
const undoStack: ImageData[] = []
let start: { x: number; y: number } | undefined
let before: ImageData | undefined
let area: Area | undefined
const canUndo = ref(false)

onMounted(async () => {
  const image = new Image()
  image.src = props.source
  try {
    await image.decode()
    await nextTick()
    if (!canvas.value) return
    canvas.value.width = image.naturalWidth
    canvas.value.height = image.naturalHeight
    canvas.value.getContext('2d')!.drawImage(image, 0, 0)
  } catch {
    message.error('图片无法读取，请重新截图')
    emit('cancel')
  }
})

function point(event: PointerEvent) {
  const element = canvas.value!
  const rect = element.getBoundingClientRect()
  return {
    x: Math.max(0, Math.min(element.width, ((event.clientX - rect.left) * element.width) / rect.width)),
    y: Math.max(0, Math.min(element.height, ((event.clientY - rect.top) * element.height) / rect.height))
  }
}
function down(event: PointerEvent) {
  if (event.button !== 0 || busy.value) return
  const element = canvas.value!
  start = point(event)
  before = element.getContext('2d')!.getImageData(0, 0, element.width, element.height)
  element.setPointerCapture(event.pointerId)
}
function move(event: PointerEvent) {
  if (!start || !before) return
  const context = canvas.value!.getContext('2d')!
  area = selectedArea(start, point(event))
  context.putImageData(before, 0, 0)
  if (mode.value === 'hide') {
    context.fillStyle = '#111827'
    context.fillRect(area.x, area.y, area.width, area.height)
  } else {
    context.strokeStyle = mode.value === 'crop' ? '#1677ff' : '#ef4444'
    context.lineWidth = Math.max(3, canvas.value!.width / 400)
    context.setLineDash(mode.value === 'crop' ? [8, 5] : [])
    context.strokeRect(area.x, area.y, area.width, area.height)
    context.setLineDash([])
  }
}
function up(event: PointerEvent) {
  if (!start || !before) return
  move(event)
  const element = canvas.value!
  const context = element.getContext('2d')!
  if (!area || area.width < 4 || area.height < 4) context.putImageData(before, 0, 0)
  else {
    undoStack.push(before)
    if (undoStack.length > 8) undoStack.shift()
    if (mode.value === 'crop') {
      context.putImageData(before, 0, 0)
      const crop = context.getImageData(area.x, area.y, area.width, area.height)
      element.width = crop.width
      element.height = crop.height
      element.getContext('2d')!.putImageData(crop, 0, 0)
      mode.value = 'mark'
    }
  }
  start = undefined
  before = undefined
  area = undefined
  canUndo.value = undoStack.length > 0
  if (element.hasPointerCapture(event.pointerId)) element.releasePointerCapture(event.pointerId)
}
function cancelPointer() {
  if (before && canvas.value) canvas.value.getContext('2d')!.putImageData(before, 0, 0)
  start = undefined
  before = undefined
  area = undefined
}
function undo() {
  const previous = undoStack.pop()
  if (!previous || !canvas.value) return
  canvas.value.width = previous.width
  canvas.value.height = previous.height
  canvas.value.getContext('2d')!.putImageData(previous, 0, 0)
  canUndo.value = undoStack.length > 0
}
async function confirm() {
  if (!canvas.value || busy.value || start) return
  busy.value = true
  const blob = await new Promise<Blob | null>(resolve => canvas.value!.toBlob(resolve, 'image/png'))
  busy.value = false
  if (!blob) {
    message.error('图片生成失败，请重试')
    return
  }
  emit('confirm', new File([blob], `反馈截图-${Date.now()}.png`, { type: 'image/png' }))
}
</script>

<template>
  <a-modal
    :open="true"
    centered
    title="编辑反馈图片"
    :width="1040"
    :z-index="12020"
    :mask-closable="false"
    :confirm-loading="busy"
    ok-text="添加到反馈"
    cancel-text="取消"
    @ok="confirm"
    @cancel="emit('cancel')"
  >
    <div class="screenshot-editor" data-feedback-overlay>
      <div class="screenshot-tools">
        <a-radio-group v-model:value="mode" button-style="solid" aria-label="图片编辑工具">
          <a-radio-button value="crop">区域裁剪</a-radio-button>
          <a-radio-button value="mark">框选标注</a-radio-button>
          <a-radio-button value="hide">遮挡内容</a-radio-button>
        </a-radio-group>
        <a-button :disabled="!canUndo" @click="undo">撤销</a-button>
        <span>在图片上拖动选择区域</span>
      </div>
      <div class="screenshot-stage">
        <canvas
          ref="canvas"
          aria-label="反馈截图编辑画布"
          @pointerdown="down"
          @pointermove="move"
          @pointerup="up"
          @pointercancel="cancelPointer"
        />
      </div>
    </div>
  </a-modal>
</template>

<style scoped>
.screenshot-tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.screenshot-tools span {
  color: #64748b;
  font-size: 13px;
}
.screenshot-stage {
  display: flex;
  justify-content: center;
  padding: 12px;
  background: #e8edf3;
  border-radius: 8px;
  max-height: 65vh;
  overflow: auto;
}
canvas {
  display: block;
  max-width: 100%;
  max-height: 60vh;
  object-fit: contain;
  cursor: crosshair;
  touch-action: none;
}
</style>
