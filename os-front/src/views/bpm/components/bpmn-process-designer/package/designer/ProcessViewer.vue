<script lang="ts" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import type Canvas from 'diagram-js/lib/core/Canvas'
import type ElementRegistry from 'diagram-js/lib/core/ElementRegistry'
import type GraphicsFactory from 'diagram-js/lib/core/GraphicsFactory'
import { flowNodeStates, prepareBpmnView } from './bpmn-view'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'

defineOptions({ name: 'MyProcessViewer' })
const props = withDefaults(defineProps<{ xml?: string; view?: Record<string, any> }>(), { xml: '', view: () => ({}) })
const xmlContent = computed(() => props.xml || String(props.view?.bpmnXml || ''))
const container = ref<HTMLElement>()
const error = ref(''),
  generated = ref(false),
  loading = ref(false),
  ready = ref(false),
  zoom = ref(100)
let viewer: NavigatedViewer | undefined,
  resize: ResizeObserver | undefined,
  generation = 0
function fit() {
  if (!ready.value || !container.value?.clientWidth) return
  const canvas = viewer?.get<Canvas>('canvas')
  canvas?.zoom('fit-viewport', { x: container.value.clientWidth / 2, y: container.value.clientHeight / 2 })
  zoom.value = Math.round((canvas?.zoom() || 1) * 100)
}
function scale(factor: number) {
  const canvas = viewer?.get<Canvas>('canvas')
  if (!ready.value || !canvas) return
  canvas.zoom(Math.max(0.2, Math.min(3, canvas.zoom() * factor)))
  zoom.value = Math.round(canvas.zoom() * 100)
}
function highlight() {
  if (!ready.value || !viewer) return
  const canvas = viewer.get<Canvas>('canvas'),
    registry = viewer.get<ElementRegistry>('elementRegistry')
  registry
    .getAll()
    .forEach(element =>
      ['running', 'finished', 'rejected', 'traversed'].forEach(state =>
        canvas.removeMarker(element.id, `flow-${state}`)
      )
    )
  Object.entries(flowNodeStates(props.view)).forEach(([id, state]) => {
    if (registry.get(id)) canvas.addMarker(id, `flow-${state}`)
  })
  for (const id of props.view?.finishedSequenceFlowActivityIds || [])
    if (registry.get(id)) canvas.addMarker(id, 'flow-traversed')
}
function fitChineseLabels(current: NavigatedViewer) {
  // 中文外置标签经浏览器字体测量后可能被收缩到逐字折行；只调整只读画布图元，不修改 DI。
  const registry = current.get<ElementRegistry>('elementRegistry')
  const graphics = current.get<GraphicsFactory>('graphicsFactory')
  registry.getAll().forEach(element => {
    if (element.type !== 'label' || element.width >= 90 || !/[\u2e80-\u9fff]/.test(element.businessObject?.name || ''))
      return
    element.x -= (90 - element.width) / 2
    element.width = 90
    graphics.update('shape', element, registry.getGraphics(element))
  })
}
async function render() {
  const stamp = ++generation
  error.value = ''
  generated.value = false
  ready.value = false
  loading.value = true
  viewer?.destroy()
  viewer = undefined
  container.value?.replaceChildren()
  try {
    const prepared = await prepareBpmnView(xmlContent.value)
    if (stamp !== generation || !container.value) return
    const host = document.createElement('div')
    host.style.height = '100%'
    container.value.replaceChildren(host)
    const current = new NavigatedViewer({ container: host })
    viewer = current
    await current.importXML(prepared.xml)
    if (stamp !== generation) return
    fitChineseLabels(current)
    generated.value = prepared.generated
    ready.value = true
    await nextTick()
    fit()
    highlight()
    current.on('canvas.viewbox.changed', () => {
      if (stamp === generation) zoom.value = Math.round(current.get<Canvas>('canvas').zoom() * 100)
    })
  } catch (e) {
    if (stamp === generation) {
      viewer?.destroy()
      viewer = undefined
      container.value?.replaceChildren()
      error.value = e instanceof Error ? e.message : '流程图暂时无法显示，请联系流程管理员检查模型'
    }
  } finally {
    if (stamp === generation) loading.value = false
  }
}
watch(xmlContent, render)
watch(() => props.view, highlight, { deep: true })
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined' && container.value) {
    resize = new ResizeObserver(fit)
    resize.observe(container.value)
  }
  render()
})
onBeforeUnmount(() => {
  generation++
  resize?.disconnect()
  viewer?.destroy()
})
</script>
<template>
  <div class="bpmn-viewer">
    <div class="viewer-toolbar">
      <span class="viewer-legend">
        <i class="running" />
        进行中
        <i class="finished" />
        已流转
        <i class="rejected" />
        已拒绝
      </span>
      <a-space>
        <a-button :disabled="!ready" size="small" aria-label="缩小流程图" @click="scale(1 / 1.2)">−</a-button>
        <span class="viewer-zoom">{{ zoom }}%</span>
        <a-button :disabled="!ready" size="small" aria-label="放大流程图" @click="scale(1.2)">＋</a-button>
        <a-button :disabled="!ready" size="small" @click="fit">适应画布</a-button>
      </a-space>
    </div>
    <a-alert
      v-if="generated"
      type="info"
      show-icon
      class="viewer-notice"
      message="当前为历史模型的自动布局视图"
      description="依据已部署流程临时排列节点，子流程折叠展示；注释、关联与消息线不包含在自动布局中。"
    />
    <a-alert v-if="error" type="warning" show-icon :message="error" class="viewer-notice">
      <template #action><a-button size="small" @click="render">重试</a-button></template>
    </a-alert>
    <a-spin :spinning="loading">
      <div ref="container" class="viewer-canvas" role="img" aria-label="BPMN流程图，可缩放和拖动画布" />
    </a-spin>
  </div>
</template>
<style scoped>
.bpmn-viewer {
  min-width: 0;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.viewer-toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}
.viewer-legend {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--text-secondary, #64748b);
}
.viewer-legend i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-left: 8px;
}
.viewer-legend .running {
  background: #5b3fd6;
}
.viewer-legend .finished {
  background: #16a34a;
}
.viewer-legend .rejected {
  background: #dc2626;
}
.viewer-zoom {
  display: inline-block;
  min-width: 42px;
  text-align: center;
  font-size: 12px;
}
.viewer-notice {
  margin: 12px 16px;
}
.viewer-canvas {
  height: 480px;
  min-width: 0;
  background: #fafbfc;
}
:deep(.flow-running .djs-visual > :first-child) {
  stroke: #5b3fd6 !important;
  stroke-width: 3px !important;
  fill: #f0edff !important;
}
:deep(.flow-finished .djs-visual > :first-child) {
  stroke: #16a34a !important;
  fill: #f0fdf4 !important;
}
:deep(.flow-rejected .djs-visual > :first-child) {
  stroke: #dc2626 !important;
  fill: #fef2f2 !important;
}
:deep(.flow-traversed .djs-visual > path) {
  stroke: #16a34a !important;
}
</style>
