<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { createWorkflowTaskNodeApi } from '@/api/nocode/workflow-task-node'
import type { WorkflowTaskNodeView } from '@/types/nocode/workflow-task-node'
import { errorMessage } from '@/nocode/data-center'

const props = defineProps<{ taskId: string }>()
const emit = defineEmits<{
  loaded: [source: WorkflowTaskNodeView | null]
  loading: [value: boolean]
  failed: []
}>()
const api = createWorkflowTaskNodeApi(request),
  router = useRouter()
const source = ref<WorkflowTaskNodeView | null>(null),
  error = ref('')
let generation = 0
async function load() {
  const current = ++generation
  source.value = null
  error.value = ''
  emit('loading', true)
  try {
    const result = await api.source(props.taskId)
    if (current === generation) {
      source.value = result
      emit('loaded', result)
    }
  } catch (cause) {
    if (current === generation) {
      error.value = errorMessage(cause)
      emit('failed')
    }
  } finally {
    if (current === generation) emit('loading', false)
  }
}
function openProcess() {
  if (source.value?.canViewProcess)
    void router.push({ name: 'BpmInstanceDetail', query: { id: source.value.processInstanceId } })
}
watch(() => props.taskId, load, { immediate: true })
onBeforeUnmount(() => generation++)
</script>

<template>
  <div v-if="source" class="task-workflow-source">
    <span>来源：流程任务 · {{ source.nodeName }}</span>
    <a-button v-if="source.canViewProcess" type="link" size="small" @click="openProcess">查看来源流程</a-button>
    <span v-if="source.readOnlyReason || source.state === 'INVALIDATED'" class="task-list__hint">
      {{ source.readOnlyReason || '所属流程已结束，任务仅供查看' }}
    </span>
  </div>
  <div v-else-if="error" class="task-workflow-source task-list__hint">
    <span :title="error">流程来源暂不可用</span>
    <a-button type="link" size="small" @click="load">重试</a-button>
  </div>
</template>

<style scoped>
.task-workflow-source {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  color: var(--text-secondary);
}
</style>
