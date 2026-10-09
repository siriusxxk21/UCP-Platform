<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import request from '@/utils/request'
import { createWorkflowTaskNodeApi } from '@/api/nocode/workflow-task-node'
import { errorMessage } from '@/nocode/data-center'
import { taskStateColors, taskStates } from '@/nocode/task-center'
import type { WorkflowTaskNodeView } from '@/types/nocode/workflow-task-node'
import TaskDetail from '@/views/nocode/task-center/TaskDetail.vue'
import '@/views/nocode/task-center/workspace.css'

const props = defineProps<{ processInstanceId: string, refreshKey?: number }>()
const emit = defineEmits<{ updated: [items: WorkflowTaskNodeView[]], changed: [] }>()
const api = createWorkflowTaskNodeApi(request)
const items = ref<WorkflowTaskNodeView[]>([])
const error = ref('')
const busyId = ref('')
const selectedTask = ref<string>()
const waiting = computed(() =>
  items.value.some(item => !item.readOnlyReason && ['CREATING', 'WAITING'].includes(item.state)),
)
let generation = 0
async function load() {
  const current = ++generation
  error.value = ''
  try {
    const result = await api.list(props.processInstanceId)
    if (current === generation) {
      items.value = result
      emit('updated', result)
    }
  }
  catch (cause) {
    if (current === generation)
      error.value = errorMessage(cause)
  }
}
async function retry(item: WorkflowTaskNodeView) {
  if (!item.canRetry || busyId.value)
    return
  busyId.value = item.executionId
  error.value = ''
  try {
    await api.retry(item.executionId)
    await load()
    emit('changed')
  }
  catch (cause) {
    error.value = errorMessage(cause)
  }
  finally {
    busyId.value = ''
  }
}
function openNode(nodeId: string) {
  const item = [...items.value].reverse().find(row => row.nodeId === nodeId && row.canViewTask && row.taskId)
  if (item?.taskId)
    selectedTask.value = item.taskId
}
async function taskChanged() {
  await load()
  emit('changed')
}
watch(
  () => [props.processInstanceId, props.refreshKey],
  () => {
    items.value = []
    selectedTask.value = undefined
    if (props.processInstanceId)
      void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => generation++)
defineExpose({ openNode })
</script>

<template>
  <a-card v-if="items.length || error" size="small" title="流程任务" class="workflow-task-nodes">
    <template #extra>
      <a-button type="link" size="small" @click="load">
        刷新任务
      </a-button>
    </template>
    <p v-if="waiting" class="task-list__hint">
      任务及验收在任务中心处理，整件任务正常完成后自动进入下一个流程节点。
    </p>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <div v-for="item in items" :key="`${item.executionId}:${item.nodeId}`" class="workflow-task-nodes__item">
      <div class="workflow-task-nodes__info">
        <strong>{{ item.nodeName }}</strong>
        <a-tag v-if="item.state === 'COMPLETED'" color="success">
          节点已完成
        </a-tag>
        <a-tag v-else-if="item.state === 'INVALIDATED'">
          节点已结束
        </a-tag>
        <a-tag v-else-if="item.taskState" :color="taskStateColors[item.taskState]">
          {{ taskStates[item.taskState] }}
        </a-tag>
        <a-tag v-else-if="item.state === 'WAITING'" color="processing">
          等待任务完成
        </a-tag>
        <a-tag v-else :color="item.error ? 'error' : 'processing'">
          {{ item.error ? '创建受阻' : '正在创建任务' }}
        </a-tag>
        <span v-if="item.error" class="workflow-task-nodes__error">{{ item.error }}</span>
        <span v-if="item.readOnlyReason" class="task-list__hint">{{ item.readOnlyReason }}</span>
        <span v-else-if="item.taskState === 'CANCELLED' && item.state === 'WAITING'" class="task-list__hint">
          任务已取消，流程不会自动继续，请联系流程管理人员。
        </span>
      </div>
      <a-space>
        <a-button
          v-if="item.canViewTask && item.taskId"
          type="primary"
          size="small"
          @click="selectedTask = item.taskId"
        >
          查看任务
        </a-button>
        <a-button
          v-if="item.canRetry"
          size="small"
          :loading="busyId === item.executionId"
          :disabled="!!busyId"
          @click="retry(item)"
        >
          重试同步
        </a-button>
        <span v-if="!item.canViewTask && item.state !== 'CREATING'" class="task-list__hint">无任务详情权限</span>
      </a-space>
    </div>
  </a-card>
  <TaskDetail
    v-if="selectedTask"
    :id="selectedTask"
    @close="selectedTask = undefined"
    @select="selectedTask = $event"
    @changed="taskChanged"
  />
</template>

<style scoped>
.workflow-task-nodes {
  margin-block: var(--spacing-md);
}
.workflow-task-nodes__item,
.workflow-task-nodes__info {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
.workflow-task-nodes__item {
  justify-content: space-between;
  padding-block: var(--spacing-sm);
}
.workflow-task-nodes__info {
  flex: 1;
  min-width: 0;
}
.workflow-task-nodes__error {
  flex-basis: 100%;
  color: var(--error-color, #c43d3d);
}
</style>
