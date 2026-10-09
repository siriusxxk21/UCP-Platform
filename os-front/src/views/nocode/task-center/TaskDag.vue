<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { PlusOutlined, EnterOutlined, MoreOutlined } from '@ant-design/icons-vue'
import type { TaskMember } from '@/types/nocode/task-center'
import {
  taskDagCardSummary,
  type TaskDagContext,
  type TaskDagDisplayNode,
  type TaskDagRuntimeInfo
} from '@/nocode/task-dag-summary'
import { taskHierarchy, taskHierarchyLabel } from '@/nocode/task-hierarchy'
import { layoutTaskDag, TASK_DAG_HEADER } from '@/nocode/task-dag-layout'
import type { TaskDagViewState } from '@/nocode/task-dag-view'

const props = withDefaults(
  defineProps<{
    nodes: TaskDagDisplayNode[]
    context?: TaskDagContext
    plannedStart?: string | null
    runtimeNodes?: TaskDagRuntimeInfo[]
    members?: TaskMember[]
    currentId?: string
    interactive?: boolean
    draft?: boolean
    rootId?: string
    editable?: boolean
    compact?: boolean
    preserveView?: boolean
    frozenIds?: string[]
    closedIds?: string[]
  }>(),
  {
    interactive: true,
    editable: false,
    frozenIds: () => [],
    closedIds: () => [],
    runtimeNodes: () => [],
    members: () => []
  }
)
const emit = defineEmits<{
  select: [id: string]
  addChild: [id: string]
  addNext: [id: string]
  addParallel: [id: string]
  batchSplit: [id: string]
  continuousChildren: [id: string]
  configure: [id: string]
  move: [id: string]
  remove: [id: string]
  link: [fromId: string, toId: string]
  unlink: [fromId: string, toId: string]
  insert: [fromId: string, toId: string]
  rename: [id: string, title: string]
}>()
const arrowId = `task-arrow-${useId()}`
const viewport = ref<SVGSVGElement>()
const collapsedIds = ref<string[]>([])
const selectedId = ref(props.currentId || '')
const selectedEdgeId = ref('')
const linkSource = ref('')
const renameTitle = ref('')
const renaming = ref(false)
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragPoint = ref<{ x: number; y: number } | null>(null)
let panOrigin: { x: number; y: number; clientX: number; clientY: number } | null = null
let portDrag = false
let resizeObserver: ResizeObserver | undefined
const lookup = computed(() => new Map(props.nodes.map(node => [node.id, node])))
const runtimeLookup = computed(() => new Map(props.runtimeNodes.map(node => [node.id, node])))
const displayContext = computed<TaskDagContext>(
  () =>
    props.context ||
    (props.draft ? 'draft' : props.nodes.some(node => node.status) || props.runtimeNodes.length ? 'instance' : 'draft')
)
const hierarchy = computed(() => taskHierarchy(props.nodes, { draft: props.draft, rootId: props.rootId }))
const root = (id: string) => (props.rootId ? id === props.rootId : hierarchy.value.get(id)?.label === '总任务')
const frozen = (id: string) => props.frozenIds.includes(id)
const parentClosed = (id: string) => props.closedIds.includes(lookup.value.get(id)?.parentId || props.rootId || '')
const selectable = computed(() => props.interactive || props.editable)
const layout = computed(() =>
  layoutTaskDag(props.nodes, {
    collapsedIds: collapsedIds.value,
    rootId: props.rootId || props.nodes.find(node => root(node.id))?.id
  })
)
const selected = computed(() => lookup.value.get(selectedId.value))
const selectedEdge = computed(() => layout.value.edges.find(edge => edge.id === selectedEdgeId.value))
const graphNodes = computed(() =>
  layout.value.nodes.map(box => {
    const node = lookup.value.get(box.id)!
    return {
      ...node,
      ...box,
      summary: taskDagCardSummary(node, {
        context: displayContext.value,
        nodes: props.nodes,
        members: props.members,
        runtime: runtimeLookup.value.get(box.id),
        plannedStart: props.plannedStart
      })
    }
  })
)
const hasRelativeStart = computed(() =>
  props.nodes.some(node => node.schedule.mode === 'PLAN_START' || node.schedule.mode === 'PREDECESSOR')
)
const hasAutoStart = computed(() => props.nodes.some(node => node.schedule.mode === 'AUTO'))
const hasLegacyStart = computed(() => props.nodes.some(node => node.schedule.mode === 'T0'))
const rootBox = computed(() => layout.value.nodes.find(box => root(box.id)))
const rootEmptyMessage = computed(() =>
  rootBox.value?.collapsed ? '子任务已收起，展开后可查看' : !rootBox.value?.childCount ? '尚未拆分子任务' : ''
)
const linkPath = computed(() => {
  const source = layout.value.nodes.find(node => node.id === linkSource.value)
  if (!source || !dragPoint.value) return ''
  return `M ${source.x + source.width} ${source.y + TASK_DAG_HEADER / 2} L ${dragPoint.value.x} ${dragPoint.value.y}`
})
function reveal(id: string) {
  const seen = new Set<string>()
  let parent = lookup.value.get(id)?.parentId
  while (parent && !seen.has(parent)) {
    seen.add(parent)
    parent = lookup.value.get(parent)?.parentId
  }
  collapsedIds.value = collapsedIds.value.filter(key => !seen.has(key))
}
watch(
  () => props.currentId,
  id => {
    if (!id) return
    selectedId.value = id
    renaming.value = false
    selectedEdgeId.value = ''
    if (!props.preserveView) reveal(id)
  }
)
watch(
  () => props.nodes.map(node => node.id).join('|'),
  () => {
    if (!lookup.value.has(selectedId.value)) selectedId.value = props.currentId || ''
    if (!lookup.value.has(linkSource.value)) cancelLink()
  }
)
// 只跟踪结构而非名称；断开依赖后节点会换列，必须重新适配以免任务移出画布。
watch(
  () =>
    JSON.stringify([
      props.rootId,
      props.nodes.map(node => [node.id, node.parentId, node.predecessorIds]),
      collapsedIds.value
    ]),
  async () => {
    await nextTick()
    if (!props.preserveView) fit()
  },
  { flush: 'post' }
)
function fit() {
  const width = viewport.value?.clientWidth || 900,
    height = viewport.value?.clientHeight || 520
  zoom.value = Math.max(0.01, Math.min(1, (width - 32) / layout.value.width, (height - 32) / layout.value.height))
  pan.value = { x: Math.max(16, (width - layout.value.width * zoom.value) / 2), y: 16 }
}
function captureView(): TaskDagViewState {
  return { zoom: zoom.value, pan: { ...pan.value }, collapsedIds: [...collapsedIds.value] }
}
function restoreView(state: TaskDagViewState) {
  if (![state.zoom, state.pan.x, state.pan.y].every(Number.isFinite)) return
  collapsedIds.value = state.collapsedIds.filter(id => lookup.value.has(id))
  zoom.value = Math.max(0.01, Math.min(2, state.zoom))
  pan.value = { ...state.pan }
}
async function locateCurrent() {
  reveal(props.currentId || selectedId.value)
  await nextTick()
  const box = layout.value.nodes.find(node => node.id === (props.currentId || selectedId.value))
  if (!box) return
  pan.value = {
    x: (viewport.value?.clientWidth || 900) / 2 - (box.x + box.width / 2) * zoom.value,
    y: (viewport.value?.clientHeight || 520) / 2 - (box.y + TASK_DAG_HEADER / 2) * zoom.value
  }
}
defineExpose({ captureView, restoreView, locateCurrent })
function scale(factor: number, clientX?: number, clientY?: number) {
  const rect = viewport.value?.getBoundingClientRect()
  const x = clientX === undefined ? (viewport.value?.clientWidth || 900) / 2 : clientX - (rect?.left || 0)
  const y = clientY === undefined ? (viewport.value?.clientHeight || 520) / 2 : clientY - (rect?.top || 0)
  const next = Math.max(0.01, Math.min(2, zoom.value * factor)),
    ratio = next / zoom.value
  pan.value = { x: x - (x - pan.value.x) * ratio, y: y - (y - pan.value.y) * ratio }
  zoom.value = next
}
function toggle(id: string) {
  collapsedIds.value = collapsedIds.value.includes(id)
    ? collapsedIds.value.filter(key => key !== id)
    : [...collapsedIds.value, id]
  selectedEdgeId.value = ''
}
function cancelLink() {
  linkSource.value = ''
  dragPoint.value = null
  portDrag = false
}
function cancelEditing() {
  cancelLink()
  renaming.value = false
}
function expandAll() {
  collapsedIds.value = []
  void nextTick(fit)
}
function connectTo(id: string) {
  if (!linkSource.value || root(id) || frozen(id) || id === linkSource.value) return
  emit('link', linkSource.value, id)
  cancelLink()
}
function select(id: string) {
  if (!selectable.value) return
  if (props.editable && linkSource.value) {
    connectTo(id)
    return
  }
  // 详情跳转可能被弃改确认或权限校验拒绝；只在新详情成功返回后改变当前标记。
  if (!props.preserveView || props.editable) selectedId.value = id
  selectedEdgeId.value = ''
  renaming.value = false
  emit('select', id)
}
function beginLink(id: string) {
  if (!props.editable || root(id)) return
  selectedEdgeId.value = ''
  linkSource.value = id
}
function point(event: PointerEvent) {
  const rect = viewport.value?.getBoundingClientRect()
  return {
    x: (event.clientX - (rect?.left || 0) - pan.value.x) / zoom.value,
    y: (event.clientY - (rect?.top || 0) - pan.value.y) / zoom.value
  }
}
function dragPort(event: PointerEvent, id: string) {
  if (event.button !== 0) return
  beginLink(id)
  if (!linkSource.value) return
  portDrag = true
  dragPoint.value = point(event)
}
function beginPan(event: PointerEvent) {
  if (
    event.button !== 0 ||
    (event.target as Element).closest('[data-task-id], [data-edge-id], [data-port], button, input')
  )
    return
  selectedEdgeId.value = ''
  panOrigin = { ...pan.value, clientX: event.clientX, clientY: event.clientY }
  dragging.value = true
  viewport.value?.setPointerCapture?.(event.pointerId)
}
function move(event: PointerEvent) {
  if (portDrag) dragPoint.value = point(event)
  if (panOrigin)
    pan.value = {
      x: panOrigin.x + event.clientX - panOrigin.clientX,
      y: panOrigin.y + event.clientY - panOrigin.clientY
    }
}
function endPointer(event: PointerEvent) {
  if (portDrag) {
    const target = (event.target as Element).closest('[data-task-id]')?.getAttribute('data-task-id')
    if (event.type === 'pointerup' && target) connectTo(target)
    if (event.type === 'pointercancel') cancelLink()
    portDrag = false
    dragPoint.value = null
  }
  panOrigin = null
  dragging.value = false
  if (viewport.value?.hasPointerCapture?.(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId)
}
function beginRename() {
  if (!selected.value || frozen(selected.value.id)) return
  renameTitle.value = selected.value.title
  renaming.value = true
}
function saveName() {
  if (!selected.value || frozen(selected.value.id) || !renameTitle.value.trim()) return
  emit('rename', selected.value.id, renameTitle.value.trim())
  renaming.value = false
}
function selectEdge(id: string) {
  if (!props.editable) return
  cancelLink()
  selectedEdgeId.value = id
}
function unlink() {
  if (!selectedEdge.value || frozen(selectedEdge.value.toId)) return
  emit('unlink', selectedEdge.value.fromId, selectedEdge.value.toId)
  selectedEdgeId.value = ''
}
function insert() {
  if (!selectedEdge.value || frozen(selectedEdge.value.toId) || parentClosed(selectedEdge.value.fromId)) return
  emit('insert', selectedEdge.value.fromId, selectedEdge.value.toId)
  selectedEdgeId.value = ''
}
onMounted(async () => {
  reveal(selectedId.value)
  await nextTick()
  fit()
  if (typeof ResizeObserver !== 'undefined' && viewport.value) {
    resizeObserver = new ResizeObserver(() => {
      if (!dragging.value && !props.preserveView) fit()
    })
    resizeObserver.observe(viewport.value)
  }
})
onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<template>
  <section class="task-dag" aria-label="任务编排图" @keydown.esc="cancelEditing">
    <div class="task-dag__toolbar">
      <span class="task-dag__legend">
        {{ compact ? '箭头表示先后顺序，高亮为当前任务' : '一个总任务大框 · 框内是子任务 · 箭头表示先后关系' }}
      </span>
      <div class="task-dag__tools">
        <a-button v-if="!editable && currentId" size="small" @click="locateCurrent">定位当前任务</a-button>
        <a-button size="small" aria-label="缩小关系图" @click="scale(1 / 1.2)">−</a-button>
        <span class="task-dag__zoom">{{ Math.round(zoom * 100) }}%</span>
        <a-button size="small" aria-label="放大关系图" @click="scale(1.2)">＋</a-button>
        <a-button size="small" @click="fit">适配画布</a-button>
        <a-button size="small" @click="expandAll">展开全部</a-button>
      </div>
    </div>
    <p v-if="!compact" class="task-dag__hint">
      总任务是整件事的容器，不是第一个步骤。开始、结束只是图示边界，不是实际任务。拖动画布可平移，滚轮可缩放。
      <span v-if="editable">选中任务可继续拆分；从右侧圆点拖到目标任务，或点击圆点后再点目标，设置先后关系。</span>
      <span v-else-if="interactive">点击任务查看详情。</span>
    </p>
    <p v-if="hasAutoStart || hasRelativeStart || hasLegacyStart" class="task-dag__hint" aria-label="时间规则图例">
      <span v-if="hasAutoStart">跟随任务顺序：按整体计划或前序最晚完成日期排期，父任务汇总下级日期。</span>
      <span v-if="hasRelativeStart">按整体计划开始日或前序实际完成日排期。</span>
      <span v-if="hasLegacyStart">历史创建时间规则仍以任务创建日为起点。</span>
      日期以任务计算结果为准；起点未定时不代用今天。
    </p>
    <div v-if="editable" class="task-dag__actions" aria-label="图形编辑操作">
      <template v-if="selectedEdge">
        <span>
          前置关系：{{ lookup.get(selectedEdge.fromId)?.title || '未命名任务' }} →
          {{ lookup.get(selectedEdge.toId)?.title || '未命名任务' }}
        </span>
        <a-button size="small" danger :disabled="frozen(selectedEdge.toId)" @click="unlink">断开依赖</a-button>
        <a-button
          size="small"
          :disabled="frozen(selectedEdge.toId) || parentClosed(selectedEdge.fromId)"
          @click="insert"
        >
          插入步骤
        </a-button>
        <span v-if="frozen(selectedEdge.toId)" class="task-dag__hint">目标任务已锁定，不能更改前置</span>
      </template>
      <template v-else-if="linkSource">
        <span role="status">已选择前置：{{ lookup.get(linkSource)?.title || '未命名任务' }}。请点击后续任务。</span>
        <a-button size="small" @click="cancelLink">取消连线</a-button>
      </template>
      <template v-else-if="selected">
        <span class="task-dag__selected-title">{{ selected.title || '未命名任务' }}</span>
        <a-tooltip title="拆分子任务">
          <a-button
            class="task-dag__icon-action"
            type="text"
            size="small"
            aria-label="拆分子任务"
            :disabled="closedIds.includes(selected.id)"
            @click="emit('addChild', selected.id)"
          >
            <PlusOutlined />
          </a-button>
        </a-tooltip>
        <a-tooltip v-if="!root(selected.id)" title="添加下一步（同级）">
          <a-button
            class="task-dag__icon-action"
            type="text"
            size="small"
            aria-label="添加下一步"
            :disabled="parentClosed(selected.id)"
            @click="emit('addNext', selected.id)"
          >
            <EnterOutlined />
          </a-button>
        </a-tooltip>
        <a-button size="small" @click="emit('configure', selected.id)">
          {{ frozen(selected.id) ? '查看配置' : '配置任务' }}
        </a-button>
        <a-button v-if="!root(selected.id)" size="small" @click="beginLink(selected.id)">连接后续任务</a-button>
        <a-dropdown :trigger="['click']">
          <a-button class="task-dag__icon-action" type="text" size="small" aria-label="图上更多操作" title="更多操作">
            <MoreOutlined />
          </a-button>
          <template #overlay>
            <a-menu>
              <a-menu-item :disabled="closedIds.includes(selected.id)" @click="emit('batchSplit', selected.id)">
                批量拆分
              </a-menu-item>
              <a-menu-item :disabled="closedIds.includes(selected.id)" @click="emit('continuousChildren', selected.id)">
                添加连续下级
              </a-menu-item>
              <a-menu-item
                v-if="!root(selected.id)"
                :disabled="parentClosed(selected.id)"
                @click="emit('addParallel', selected.id)"
              >
                添加并行任务
              </a-menu-item>
              <a-menu-item :disabled="frozen(selected.id)" @click="beginRename">改名</a-menu-item>
              <a-menu-item
                v-if="!root(selected.id)"
                :disabled="frozen(selected.id) || closedIds.includes(selected.id)"
                @click="emit('move', selected.id)"
              >
                移动到其他任务下
              </a-menu-item>
              <a-menu-item
                v-if="!root(selected.id)"
                danger
                :disabled="frozen(selected.id) || closedIds.includes(selected.id)"
                @click="emit('remove', selected.id)"
              >
                删除任务
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <div v-if="renaming" class="task-dag__rename">
          <input v-model="renameTitle" aria-label="任务名称" maxlength="160" @keydown.enter.prevent="saveName" />
          <a-button size="small" :disabled="!renameTitle.trim()" @click="saveName">确认改名</a-button>
          <a-button size="small" @click="renaming = false">取消</a-button>
        </div>
      </template>
      <span v-else class="task-dag__hint">选择一个任务开始编排；总任务下可以添加多个连续步骤。</span>
    </div>
    <svg
      ref="viewport"
      class="task-dag__viewport"
      :class="{ 'task-dag__viewport--dragging': dragging }"
      role="group"
      aria-label="任务依赖关系图"
      @pointerdown="beginPan"
      @pointermove="move"
      @pointerup="endPointer"
      @pointercancel="endPointer"
      @wheel.prevent="scale($event.deltaY < 0 ? 1.1 : 1 / 1.1, $event.clientX, $event.clientY)"
    >
      <defs>
        <marker :id="arrowId" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
          <path d="M 0 0 L 10 5 L 0 10 z" class="task-dag__arrow" />
        </marker>
      </defs>
      <g data-scene :transform="`translate(${pan.x},${pan.y}) scale(${zoom})`">
        <rect class="task-dag__background" :width="layout.width" :height="layout.height" />
        <g v-for="node in graphNodes" :key="`box:${node.id}`" :transform="`translate(${node.x},${node.y})`">
          <rect
            v-if="root(node.id) || (node.childCount && !node.collapsed)"
            class="task-dag__container"
            :class="{ 'task-dag__container--root': root(node.id) }"
            :data-container-id="node.id"
            :width="node.width"
            :height="node.height"
            rx="12"
          />
        </g>
        <path
          v-for="edge in layout.boundaryEdges"
          :key="`${edge.boundary}:${edge.taskId}`"
          :data-boundary-edge="edge.boundary"
          :data-boundary-task="edge.taskId"
          class="task-dag__edge task-dag__boundary-edge"
          :d="edge.path"
          fill="none"
          :marker-end="`url(#${arrowId})`"
        />
        <g
          v-for="boundary in layout.boundaries"
          :key="boundary.kind"
          :data-boundary="boundary.kind"
          :transform="`translate(${boundary.x},${boundary.y})`"
          role="img"
          :aria-label="`${boundary.kind === 'START' ? '开始' : '结束'}：图示边界，不是实际任务`"
        >
          <rect
            class="task-dag__boundary"
            :width="boundary.width"
            :height="boundary.height"
            :rx="boundary.height / 2"
          />
          <text
            :x="boundary.width / 2"
            :y="boundary.height / 2 + 5"
            text-anchor="middle"
            class="task-dag__boundary-title"
          >
            {{ boundary.kind === 'START' ? '开始' : '结束' }}
          </text>
        </g>
        <foreignObject
          v-if="rootBox && rootEmptyMessage"
          :x="rootBox.x + 112"
          :y="rootBox.y + TASK_DAG_HEADER + 22"
          :width="rootBox.width - 224"
          height="62"
        >
          <div class="task-dag__empty">
            <span>{{ rootEmptyMessage }}</span>
            <a-button
              v-if="editable && !rootBox.childCount && !closedIds.includes(rootBox.id)"
              type="link"
              size="small"
              @click.stop="emit('addChild', rootBox.id)"
            >
              拆分子任务
            </a-button>
          </div>
        </foreignObject>
        <g v-for="edge in layout.edges" :key="edge.id">
          <path
            :data-edge-id="edge.id"
            data-relation="predecessor"
            class="task-dag__edge"
            :class="{ 'task-dag__edge--selected': selectedEdgeId === edge.id }"
            :d="edge.path"
            fill="none"
            :marker-end="`url(#${arrowId})`"
            :data-projected="edge.projected || undefined"
          />
          <path
            v-if="editable"
            :data-edge-id="edge.id"
            class="task-dag__edge-hit"
            :d="edge.path"
            fill="none"
            role="button"
            tabindex="0"
            :aria-label="`选择依赖：${lookup.get(edge.fromId)?.title} → ${lookup.get(edge.toId)?.title}`"
            @click.stop="selectEdge(edge.id)"
            @keydown.enter.prevent="selectEdge(edge.id)"
            @keydown.space.prevent="selectEdge(edge.id)"
          >
            <title>点击查看或断开这条前置依赖{{ edge.projected ? '（端点在折叠分组内）' : '' }}</title>
          </path>
        </g>
        <path v-if="linkPath" class="task-dag__edge task-dag__edge--preview" :d="linkPath" fill="none" />
        <g
          v-for="node in graphNodes"
          :key="node.id"
          class="task-dag__node"
          :class="{
            'task-dag__node--root': root(node.id),
            'task-dag__node--completed': node.summary.state === 'COMPLETED',
            'task-dag__node--running': node.summary.state === 'RUNNING',
            'task-dag__node--link-source': linkSource === node.id
          }"
          :transform="`translate(${node.x},${node.y})`"
          :data-task-id="node.id"
          :data-hierarchy="hierarchy.get(node.id)?.outline || undefined"
          :tabindex="selectable ? 0 : undefined"
          :role="selectable ? 'button' : undefined"
          :aria-label="taskHierarchyLabel(node, hierarchy.get(node.id))"
          :aria-current="node.id === selectedId ? 'true' : undefined"
          :style="{ cursor: selectable ? 'pointer' : 'default' }"
          @click.stop="select(node.id)"
          @keydown.enter.self.prevent="select(node.id)"
          @keydown.space.self.prevent="select(node.id)"
        >
          <rect class="task-dag__card" :width="node.width" :height="TASK_DAG_HEADER - 8" rx="10" />
          <text x="16" y="21" class="task-dag__hierarchy">
            {{ hierarchy.get(node.id)?.label }}
          </text>
          <text
            v-if="!editable && node.id === currentId"
            :x="node.width - 52"
            y="21"
            text-anchor="end"
            class="task-dag__hierarchy"
          >
            当前查看
          </text>
          <foreignObject x="16" y="28" :width="node.width - 64" height="25">
            <div class="task-dag__title task-dag__ellipsis">{{ node.title || '未命名任务' }}</div>
          </foreignObject>
          <foreignObject x="16" y="56" :width="node.width - 32" height="22">
            <div class="task-dag__meta task-dag__ellipsis">
              {{ node.summary.status }} · 负责人：{{ node.summary.owner }}
            </div>
          </foreignObject>
          <foreignObject x="16" y="82" :width="node.width - 32" height="46">
            <div class="task-dag__time" :title="node.summary.time">{{ node.summary.time }}</div>
          </foreignObject>
          <foreignObject v-if="node.summary.rule" x="16" y="130" :width="node.width - 32" height="22">
            <div class="task-dag__meta task-dag__ellipsis" :title="node.summary.rule">{{ node.summary.rule }}</div>
          </foreignObject>
          <g
            v-if="node.childCount"
            role="button"
            tabindex="0"
            :aria-label="`${node.collapsed ? '展开' : '收起'}：${node.title || '未命名任务'}`"
            :aria-expanded="!node.collapsed"
            :transform="`translate(${node.width - 38},12)`"
            @click.stop="toggle(node.id)"
            @keydown.enter.stop.prevent="toggle(node.id)"
            @keydown.space.stop.prevent="toggle(node.id)"
          >
            <rect class="task-dag__fold" width="28" height="28" rx="5" />
            <text x="14" y="19" text-anchor="middle" class="task-dag__meta">{{ node.collapsed ? '+' : '−' }}</text>
            <title>{{ node.childCount }} 个直接子任务</title>
          </g>
          <text v-if="node.collapsed && node.childCount" x="16" :y="TASK_DAG_HEADER - 17" class="task-dag__meta">
            已收起 {{ node.childCount }} 个子任务
          </text>
          <g
            v-if="editable && !root(node.id)"
            data-port="output"
            role="button"
            tabindex="0"
            :aria-label="`从${node.title || '未命名任务'}连接后续任务`"
            :transform="`translate(${node.width},${TASK_DAG_HEADER / 2})`"
            @pointerdown.stop.prevent="dragPort($event, node.id)"
            @click.stop="beginLink(node.id)"
            @keydown.enter.stop.prevent="beginLink(node.id)"
            @keydown.space.stop.prevent="beginLink(node.id)"
          >
            <circle r="12" class="task-dag__port-hit" />
            <circle r="5" class="task-dag__port" />
          </g>
          <circle
            v-if="editable && !root(node.id) && !frozen(node.id)"
            data-port="input"
            class="task-dag__port"
            :cy="TASK_DAG_HEADER / 2"
            r="5"
            @click.stop="connectTo(node.id)"
          />
          <title>
            {{ taskHierarchyLabel(node, hierarchy.get(node.id)) }} · {{ node.summary.status }} · 负责人：{{
              node.summary.owner
            }}
            · {{ node.summary.time }} · {{ node.summary.rule }}
          </title>
        </g>
      </g>
    </svg>
  </section>
</template>

<style scoped>
.task-dag {
  width: 100%;
  overflow: hidden;
}
.task-dag__toolbar,
.task-dag__tools,
.task-dag__actions,
.task-dag__rename {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.task-dag__icon-action {
  width: var(--control-height-sm);
  height: var(--control-height-sm);
  padding: 0;
  color: var(--text-secondary);
}
.task-dag__icon-action:hover,
.task-dag__icon-action:focus-visible {
  color: var(--brand);
  background: var(--brand-light);
}
.task-dag__toolbar {
  justify-content: space-between;
}
.task-dag__legend {
  color: var(--text-primary);
  font-weight: 500;
}
.task-dag__hint {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
  margin: 8px 0;
}
.task-dag__zoom {
  min-width: 40px;
  text-align: center;
  color: var(--text-secondary);
}
.task-dag__actions {
  min-height: 42px;
  padding: 8px 12px;
  background: var(--neutral-bg);
  border: 1px solid var(--border-hover);
  border-radius: 8px 8px 0 0;
}
.task-dag__selected-title {
  max-width: 240px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-weight: 500;
}
.task-dag__rename {
  flex-basis: 100%;
}
.task-dag__rename input {
  width: min(100%, 320px);
  padding: 4px 8px;
  border: 1px solid var(--border-hover);
  border-radius: 4px;
  color: var(--text-primary);
  background: var(--neutral-bg);
}
.task-dag .task-dag__viewport {
  display: block;
  width: 100%;
  min-width: 0;
  height: 520px;
  max-height: 65vh;
  min-height: 300px;
  border: 1px solid var(--border-hover);
  border-radius: 8px;
  background: var(--neutral-bg);
  cursor: grab;
  touch-action: none;
  user-select: none;
  font-family: inherit;
}
.task-dag__viewport--dragging {
  cursor: grabbing !important;
}
.task-dag__background {
  fill: transparent;
}
.task-dag__container {
  fill: var(--neutral-bg);
  stroke: var(--border-hover);
  stroke-width: 1.5;
}
.task-dag__container--root {
  stroke: var(--brand);
  stroke-opacity: 0.5;
}
.task-dag__edge {
  stroke: var(--text-tertiary);
  stroke-width: 2;
  pointer-events: none;
}
.task-dag__edge--selected,
.task-dag__edge--preview {
  stroke: var(--brand);
  stroke-width: 3;
}
.task-dag__boundary-edge {
  stroke-dasharray: 4 3;
}
.task-dag__boundary {
  fill: var(--brand-light);
  stroke: var(--brand);
  stroke-width: 1.5;
}
.task-dag__boundary-title {
  fill: var(--brand);
  font-size: var(--table-font-sm);
  font-weight: 600;
}
.task-dag__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-dag__edge--preview {
  stroke-dasharray: 5 4;
}
.task-dag__edge-hit {
  stroke: transparent;
  stroke-width: 16;
  cursor: pointer;
}
.task-dag__edge-hit:focus {
  outline: none;
  stroke: var(--brand);
  stroke-opacity: 0.15;
}
.task-dag__arrow {
  fill: var(--text-tertiary);
}
.task-dag__card {
  fill: var(--neutral-bg);
  stroke: var(--border-hover);
}
.task-dag__node--root .task-dag__card,
.task-dag__node--running .task-dag__card {
  fill: var(--brand-light);
}
.task-dag__node--completed .task-dag__card {
  fill: var(--success-bg);
}
.task-dag__node[aria-current='true'] .task-dag__card,
.task-dag__node--link-source .task-dag__card {
  stroke: var(--brand);
  stroke-width: 2.5;
}
.task-dag__node:focus {
  outline: none;
}
.task-dag__node:focus > .task-dag__card {
  stroke: var(--brand);
  stroke-width: 3;
}
.task-dag__hierarchy,
.task-dag__title,
.task-dag__meta {
  font-size: var(--table-font-sm);
}
.task-dag__time {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: var(--table-font-sm);
  line-height: 22px;
}
.task-dag__hierarchy {
  fill: var(--brand);
  font-weight: 600;
}
.task-dag__title {
  color: var(--text-primary);
  font-weight: 600;
}
.task-dag__meta {
  fill: var(--text-secondary);
  color: var(--text-secondary);
}
.task-dag__ellipsis {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  line-height: 22px;
}
.task-dag__fold {
  fill: var(--neutral-bg);
  stroke: var(--border-hover);
  cursor: pointer;
}
.task-dag__port {
  fill: var(--neutral-bg);
  stroke: var(--brand);
  stroke-width: 2;
  cursor: crosshair;
}
.task-dag__port-hit {
  fill: transparent;
  cursor: crosshair;
}
</style>
