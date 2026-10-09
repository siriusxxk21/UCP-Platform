<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createFlowTaskApi, type FlowWorkspace } from '@/api/nocode/flow-task'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import WorkDraftPanel from './components/WorkDraftPanel.vue'
import FlowTaskContext from './components/FlowTaskContext.vue'
import type { WorkDraftContext } from '@/types/nocode/work'

const route = useRoute(),
  router = useRouter()
const workspace = ref<FlowWorkspace>(),
  error = ref(''),
  loading = ref(false)
const api = ref<ReturnType<typeof createFlowTaskApi>>()
let generation = 0
onBeforeUnmount(() => generation++)
function updateContext(work: WorkDraftContext) {
  if (workspace.value) workspace.value = { ...workspace.value, work }
}
async function load() {
  const stamp = ++generation
  workspace.value = undefined
  error.value = ''
  loading.value = true
  try {
    const taskId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
    if (!taskId) throw new Error('缺少流程任务编号，请从待办或已办任务进入')
    const current = createFlowTaskApi(request, taskId)
    const result = await current.open()
    if (stamp !== generation) return
    api.value = current
    workspace.value = result
  } catch (e) {
    if (stamp === generation) error.value = errorMessage(e)
  } finally {
    if (stamp === generation) loading.value = false
  }
}
function viewProcess() {
  if (workspace.value)
    router.push({
      name: 'TaskInstanceDetail',
      query: {
        id: workspace.value.processInstanceId,
        taskId: route.query.taskId
      }
    })
}
function viewRecords() {
  if (workspace.value)
    router.push({
      name: 'NocodeApplicationRuntime',
      query: {
        id: workspace.value.work.draft.resource.applicationId
      }
    })
}
watch(() => route.query.taskId, load, { immediate: true })
</script>
<template>
  <div class="flow-task-page">
    <div class="flow-task-heading">
      <div>
        <h1>流程任务办理</h1>
        <p>填写业务表单，随时查阅本次任务与流程信息。</p>
      </div>
      <a-button v-if="workspace" @click="viewProcess">查看完整流程</a-button>
    </div>
    <a-spin :spinning="loading" wrapper-class-name="flow-task-loading">
      <a-alert v-if="error" type="error" :message="error" show-icon>
        <template #action><a-button size="small" @click="load">重新读取</a-button></template>
      </a-alert>
      <div v-if="workspace && api" class="flow-workbench">
        <section class="flow-workbench-form" aria-label="业务表单">
          <WorkDraftPanel
            v-if="workspace && api"
            :key="workspace.work.draft.id"
            :id="workspace.work.draft.id"
            :api="api"
            task
            workbench
            @context="updateContext"
            @close="viewProcess"
          />
        </section>
        <FlowTaskContext
          :workspace="workspace"
          :task-id="String(route.query.taskId || '')"
          @records="viewRecords"
          @process="viewProcess"
        />
      </div>
    </a-spin>
  </div>
</template>
<style scoped>
.flow-task-page {
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.flow-task-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.flow-task-heading h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.flow-task-heading p {
  margin: 6px 0 0;
  color: var(--text-secondary, #64748b);
}
.flow-task-loading {
  flex: 1;
  min-height: 0;
}
.flow-task-loading :deep(> .ant-spin-container) {
  height: 100%;
}
.flow-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  height: calc(100dvh - 220px);
  min-height: 420px;
  min-width: 0;
}
.flow-workbench-form {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  background: var(--component-background, #fff);
}
@media (max-width: 1199px) {
  .flow-workbench {
    grid-template-columns: minmax(0, 1fr) 292px;
    gap: 12px;
  }
}
@media (max-width: 900px) {
  .flow-task-page {
    height: auto;
  }
  .flow-workbench {
    display: flex;
    flex-direction: column;
    height: auto;
    min-height: 0;
  }
  .flow-workbench-form {
    height: min(760px, calc(100dvh - 220px));
    min-height: 420px;
  }
  .flow-task-heading {
    align-items: flex-start;
  }
  .flow-task-heading p {
    display: none;
  }
}
</style>
