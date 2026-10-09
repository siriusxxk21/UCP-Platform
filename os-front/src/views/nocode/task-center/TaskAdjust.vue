<script setup lang="ts">
import { useNocodePlatform } from '@/nocode/platform'
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'

import type { TaskDetail, TaskMember, TaskAdjustmentPreview, TaskRow } from '@/types/nocode/task-center'
import { taskNodeError, taskNodeInput } from '@/nocode/task-center'
import { errorMessage } from '@/nocode/data-center'
import { canLocateTaskClaim, type TaskClaimLocation } from '@/nocode/task-claim-entry'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import TaskNodeEditor from './TaskNodeEditor.vue'
import TaskPersonalSplit from './TaskPersonalSplit.vue'
import TaskSchedulePreviewPanel from './TaskSchedulePreview.vue'
import { useUserStore } from '@/stores/user'
import {
  canSplitInstanceNode,
  arrangementDisplayNodes,
  instanceStructure,
  instanceArrangementAccess,
  instanceNodeRestriction
} from '@/nocode/task-instance-arrangement'
const { confirmDiscard } = useTaskConfirmation()
const props = withDefaults(
  defineProps<{
    detail: TaskDetail
    canManage?: boolean
    readonly?: boolean
    employeeView?: boolean
    allowClaimLookup?: boolean
  }>(),
  {
    canManage: false,
    allowClaimLookup: true
  }
)
const view = defineModel<'list' | 'graph'>('view', { default: 'list' })
const emit = defineEmits<{
  close: []
  saved: []
  inspect: [id: string]
  deleteSubtask: [task: TaskRow]
  claim: [target: TaskClaimLocation]
}>()
const user = useUserStore()
const root = computed(() => props.detail.nodes.find(node => node.id === props.detail.task.rootId))
const structure = computed(() => instanceStructure(props.detail))
function claimLocation(id: string) {
  if (!props.allowClaimLookup) return undefined
  const node = props.detail.nodes.find(node => node.id === id) || structure.value.find(node => node.id === id)
  return canLocateTaskClaim(node) ? node : undefined
}
const referenceIds = computed(() =>
  structure.value.filter(node => !props.detail.nodes.some(item => item.id === node.id)).map(node => node.id)
)
const editable = computed(
  () =>
    !props.readonly &&
    !referenceIds.value.length &&
    instanceArrangementAccess(props.detail.nodes, props.detail.task.rootId, props.canManage)
)
const selectedId = ref(props.detail.task.id)
const editor = ref<InstanceType<typeof TaskNodeEditor>>()
const splitParent = ref<TaskDetail['task']>()
const selectedRuntime = computed(() => props.detail.nodes.find(node => node.id === selectedId.value))
const restriction = computed(() =>
  referenceIds.value.includes(selectedId.value)
    ? '同组任务 · 仅查看编排概要'
    : instanceNodeRestriction(selectedRuntime.value, root.value, editable.value)
)
const canSplit = (id: string) =>
  !props.readonly &&
  canSplitInstanceNode(
    props.detail.nodes.find(node => node.id === id),
    root.value,
    user.userInfo?.id
  )
function split(id: string) {
  if (canSplit(id)) splitParent.value = props.detail.nodes.find(node => node.id === id)
}
const api = useNocodePlatform().taskCenter,
  nodes = ref(props.detail.nodes.map(taskNodeInput)),
  plannedStart = ref(root.value?.plannedStart || null),
  members = ref<TaskMember[]>([]),
  reason = ref(''),
  busy = ref(false),
  error = ref(''),
  preview = ref<TaskAdjustmentPreview | null>(null),
  previewKey = ref('')
function locateClaim(id: string) {
  const target = claimLocation(id)
  if (target && !busy.value) emit('claim', target)
}
function deleteSelectedSubtask() {
  if (props.employeeView && !props.readonly && !busy.value && selectedRuntime.value?.canDelete === true)
    emit('deleteSubtask', selectedRuntime.value)
}
const editorNodes = computed({
  get: () => (editable.value ? nodes.value : arrangementDisplayNodes(nodes.value, structure.value)),
  set: value => {
    if (editable.value) nodes.value = value
  }
})
const selected = computed(() => editorNodes.value.find(node => node.id === selectedId.value))
// 未重新选择时保留历史精确起点，避免仅调整其他字段也重排任务。
const plannedStartDate = computed({
  get: () => (plannedStart.value ? dayjs(plannedStart.value).format('YYYY-MM-DD') : null),
  set: (value: string | null) => (plannedStart.value = value)
})
const signature = computed(() =>
    JSON.stringify({ nodes: nodes.value, reason: reason.value, plannedStart: plannedStart.value })
  ),
  initial = ref(signature.value)
const dirty = computed(() => signature.value !== initial.value)
useUnsavedNavigation(() => dirty.value || busy.value, {
  confirm: changed => (busy.value ? Promise.resolve(false) : confirmDiscard(changed))
})
const scheduleSignature = (values: typeof nodes.value) =>
  JSON.stringify(
    values
      .map(node => ({
        id: node.id,
        parentId: node.parentId,
        predecessors: [...node.predecessorIds].sort(),
        schedule: node.schedule
      }))
      .sort((a, b) => a.id.localeCompare(b.id))
  )
const originalSchedule = scheduleSignature(nodes.value)
const scheduleChanged = computed(
  () => plannedStart.value !== (root.value?.plannedStart || null) || scheduleSignature(nodes.value) !== originalSchedule
)
// 上游规则或结构变化可能影响下游，前端不复制排期引擎；未开始的相对日期待保存后由服务端重算。
const runtimeNodes = computed(() => [
  ...props.detail.nodes.map(node => {
    const calculated =
      previewKey.value === signature.value
        ? preview.value?.schedule?.nodes.find(item => item.id === node.id)
        : undefined
    if (calculated)
      return {
        ...node,
        schedule: nodes.value.find(item => item.id === node.id)?.schedule || node.schedule,
        predecessorIds: nodes.value.find(item => item.id === node.id)?.predecessorIds || node.predecessorIds,
        plannedStart: plannedStart.value,
        expectedStart: calculated.expectedStart,
        expectedEnd: calculated.expectedEnd,
        scheduleSummary: {
          source: node.scheduleSummary?.source || ('EXPLICIT' as const),
          partial: calculated.partial,
          warnings: calculated.warnings
        }
      }
    return scheduleChanged.value &&
      node.status === 'PENDING' &&
      ['AUTO', 'PLAN_START', 'PREDECESSOR', 'T0', 'UNSCHEDULED'].includes(node.schedule.mode)
      ? { ...node, expectedStart: null, expectedEnd: null, expectedStale: true }
      : node
  }),
  ...(previewKey.value === signature.value ? preview.value?.schedule?.nodes || [] : []).flatMap(calculated => {
    const draft = nodes.value.find(node => node.id === calculated.id)
    if (!draft || props.detail.nodes.some(node => node.id === calculated.id)) return []
    return [{ ...draft, ...calculated, status: 'PENDING' as const, plannedStart: plannedStart.value }]
  }),
  ...structure.value.filter(node => referenceIds.value.includes(node.id))
])
const needsPlannedStart = computed(() => nodes.value.some(node => ['AUTO', 'PLAN_START'].includes(node.schedule.mode)))
const frozenIds = computed(() =>
  props.detail.nodes.filter(n => n.status !== 'PENDING' || n.pausedByTaskId).map(n => n.id)
)
const closedIds = computed(() =>
  props.detail.nodes
    .filter(n => n.pausedByTaskId || ['PAUSED', 'PENDING_ACCEPTANCE', 'COMPLETED', 'CANCELLED'].includes(n.status))
    .map(n => n.id)
)
const command = () => ({
  rootId: props.detail.task.rootId,
  expectedRevision: props.detail.task.instanceRevision,
  nodes: nodes.value,
  plannedStart: plannedStart.value,
  reason: reason.value.trim()
})
onMounted(async () => {
  if (props.detail.preview) return
  try {
    members.value = await api.members(props.detail.task.id)
  } catch (e) {
    error.value = errorMessage(e)
  }
})
async function inspect() {
  if (busy.value || !editable.value) return
  error.value =
    taskNodeError(nodes.value) ||
    (needsPlannedStart.value && !plannedStart.value ? '请选择计划开始日期' : '') ||
    (!reason.value.trim() ? '请填写调整原因' : '')
  if (error.value) return
  const requestedSignature = signature.value
  const requestedCommand = JSON.parse(JSON.stringify(command())) as ReturnType<typeof command>
  busy.value = true
  try {
    const result = await api.adjustPreview(requestedCommand)
    if (signature.value === requestedSignature) {
      preview.value = result
      previewKey.value = requestedSignature
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function save() {
  if (busy.value || !editable.value) return
  if (!preview.value || previewKey.value !== signature.value) {
    await inspect()
    return
  }
  busy.value = true
  try {
    await api.adjust(command())
    initial.value = signature.value
    message.success('任务安排已更新，原模板不变')
    emit('saved')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function close() {
  if (await requestClose()) {
    nodes.value = props.detail.nodes.map(taskNodeInput)
    plannedStart.value = root.value?.plannedStart || null
    reason.value = ''
    preview.value = null
    error.value = ''
    initial.value = signature.value
  }
}
async function requestClose() {
  if (busy.value || splitParent.value) return false
  return confirmDiscard(dirty.value, '离开任务编排并放弃尚未保存的调整？')
}
defineExpose({
  requestClose,
  busy,
  dirty,
  selectedId,
  restoreSelection: (id: string) => {
    if (editorNodes.value.some(node => node.id === id)) selectedId.value = id
  },
  captureView: () => editor.value?.captureView(),
  restoreView: (value: Parameters<InstanceType<typeof TaskNodeEditor>['restoreView']>[0]) =>
    editor.value?.restoreView(value)
})
</script>
<template>
  <section class="task-adjust" aria-label="调整任务安排">
    <a-form layout="vertical" :disabled="busy">
      <div class="task-adjust__toolbar">
        <a-radio-group v-model:value="view" button-style="solid" aria-label="编排视图">
          <a-radio-button value="list">列表</a-radio-button>
          <a-radio-button value="graph">图上编辑</a-radio-button>
        </a-radio-group>
        <a-space>
          <a-button v-if="view === 'list'" @click="editor?.expandAll()">展开全部</a-button>
          <a-button v-if="view === 'list'" @click="editor?.collapseAll()">收起全部</a-button>
        </a-space>
      </div>
      <div class="task-adjust__selection" aria-label="当前选中任务">
        <strong>当前选中：{{ selected?.title || '未命名任务' }}</strong>
        <span class="task-list__hint">{{ restriction }}</span>
        <a-button v-if="claimLocation(selectedId)" type="link" :disabled="busy" @click="locateClaim(selectedId)">
          去领取
        </a-button>
        <a-button
          v-if="selectedRuntime && selectedRuntime.detailVisible !== false"
          type="link"
          @click="emit('inspect', selectedId)"
        >
          查看详情
        </a-button>
        <a-button v-if="!editable && canSplit(selectedId)" type="link" @click="split(selectedId)">拆分子任务</a-button>
        <template v-if="employeeView && selectedRuntime?.parentId && selectedRuntime.canDelete !== undefined">
          <a-button
            type="link"
            danger
            :disabled="readonly || busy || selectedRuntime.canDelete !== true"
            :title="selectedRuntime.deleteBlockedReason || undefined"
            @click="deleteSelectedSubtask"
          >
            删除子任务
          </a-button>
          <span v-if="!selectedRuntime.canDelete" class="task-list__hint">
            {{ selectedRuntime.deleteBlockedReason || '当前任务不可删除' }}
          </span>
        </template>
      </div>
      <a-form-item v-if="editable && needsPlannedStart" label="计划开始日期" required>
        <a-date-picker
          v-model:value="plannedStartDate"
          :allow-clear="false"
          format="YYYY-MM-DD"
          value-format="YYYY-MM-DD"
          placeholder="相对时间的共同起点"
        />
        <p class="task-list__hint">调整计算起点不代表任务已经开始；已执行节点保持原配置。</p>
      </a-form-item>
      <TaskNodeEditor
        ref="editor"
        v-model="editorNodes"
        v-model:view="view"
        v-model:selected-id="selectedId"
        inline-configuration
        external-actions
        instance-workspace
        :auto-schedule="!!plannedStart && nodes.some(node => node.schedule.mode === 'AUTO')"
        :members="members"
        :root-id="detail.task.rootId"
        :frozen-ids="frozenIds"
        :closed-ids="closedIds"
        :readonly="busy || !editable"
        :data-readonly="detail.nodes.some(node => node.status !== 'PENDING')"
        :planned-start="plannedStart"
        :runtime-nodes="runtimeNodes"
        :reference-ids="referenceIds"
      >
        <template #runtime-branch-actions="{ node }">
          <a-tooltip v-if="!editable && canSplit(node.id)" title="拆分子任务">
            <a-button
              class="task-adjust__split"
              type="text"
              size="small"
              :disabled="busy"
              :aria-label="`拆分子任务：${node.title || '未命名任务'}`"
              @click.stop="split(node.id)"
            >
              <PlusOutlined />
            </a-button>
          </a-tooltip>
        </template>
        <template #runtime-actions="{ node }">
          <a-space>
            <a-button v-if="claimLocation(node.id)" type="link" :disabled="busy" @click="locateClaim(node.id)">
              去领取
            </a-button>
            <a-button
              v-if="detail.nodes.some(item => item.id === node.id && item.detailVisible !== false)"
              type="link"
              @click="emit('inspect', node.id)"
            >
              查看详情
            </a-button>
            <span v-else-if="!claimLocation(node.id)" class="task-list__hint">仅概要</span>
          </a-space>
        </template>
      </TaskNodeEditor>
      <a-form-item v-if="editable && dirty" label="调整原因" required>
        <a-textarea v-model:value="reason" :maxlength="1000" :rows="2" />
      </a-form-item>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert
        v-if="preview && previewKey === signature"
        type="warning"
        show-icon
        :message="`修改 ${preview.changedIds.length} 项，新增 ${preview.addedIds.length} 项，移除 ${preview.removedIds.length} 项，影响 ${preview.affectedIds.length} 项`"
      />
      <TaskSchedulePreviewPanel
        v-if="preview?.schedule && previewKey === signature"
        :preview="preview.schedule"
        :nodes="nodes"
      />
    </a-form>
    <div v-if="editable && dirty" class="task-adjust__footer">
      <a-button :disabled="busy" @click="close">取消调整</a-button>
      <a-button type="primary" :loading="busy" :disabled="busy" @click="save">
        {{ preview && previewKey === signature ? '确认调整任务' : '预览调整影响' }}
      </a-button>
    </div>
  </section>
  <TaskPersonalSplit
    v-if="splitParent && !readonly"
    :key="splitParent.id"
    :parent="splitParent"
    @close="splitParent = undefined"
    @saved="emit('saved')"
  />
</template>

<style scoped>
.task-adjust__toolbar,
.task-adjust__selection {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}
.task-adjust__toolbar {
  justify-content: space-between;
}
.task-adjust__selection {
  min-height: var(--control-height);
}
.task-adjust__selection .task-list__hint {
  margin: 0;
}
.task-adjust__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-sm);
  position: sticky;
  bottom: 0;
  z-index: 2;
  padding: var(--spacing-md) 0;
  background: var(--bg-container, #fff);
  border-top: 1px solid var(--border-color);
}
.task-adjust__split {
  flex: 0 0 var(--control-height-sm);
  width: var(--control-height-sm);
  height: var(--control-height-sm);
  margin-block: calc((var(--control-height) - var(--control-height-sm)) / 2);
  padding: 0;
  color: var(--text-secondary);
}
.task-adjust__split:hover,
.task-adjust__split:focus-visible {
  color: var(--brand);
  background: var(--brand-light);
}
</style>
