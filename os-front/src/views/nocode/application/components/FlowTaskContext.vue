<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getApprovalDetail, type BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import type { FlowWorkspace } from '@/api/nocode/flow-task'
import { formatDateTime } from '@/utils/format'
import { errorMessage } from '@/nocode/data-center'
import { getTaskStatusMeta } from '@/views/bpm/task/shared'

const props = defineProps<{ workspace: FlowWorkspace; taskId: string }>()
defineEmits<{ records: []; process: [] }>()
const activeTab = ref('process')
const detail = ref<BpmProcessInstanceApi.ApprovalDetailRespVO>()
const error = ref('')
const loading = ref(false)
const work = computed(() => props.workspace.work)
const nodeName = computed(
  () =>
    detail.value?.activityNodes?.find(node => node.id === props.workspace.nodeId)?.name ||
    (detail.value?.todoTask?.taskDefinitionKey === props.workspace.nodeId
      ? detail.value.todoTask.name
      : props.workspace.nodeId)
)
let generation = 0
async function load() {
  const stamp = ++generation
  error.value = ''
  detail.value = undefined
  loading.value = true
  try {
    const result = await getApprovalDetail({
      processInstanceId: props.workspace.processInstanceId,
      taskId: props.taskId
    })
    if (stamp === generation) detail.value = result
  } catch (e) {
    if (stamp === generation) error.value = errorMessage(e)
  } finally {
    if (stamp === generation) loading.value = false
  }
}
watch(() => [props.taskId, work.value.submission?.id], load, { immediate: true })
onBeforeUnmount(() => generation++)
</script>
<template>
  <aside class="flow-context" aria-label="任务与流程信息">
    <div class="context-heading">
      <h2>本次任务</h2>
      <a-tag :color="work.submission ? 'success' : work.writable ? 'processing' : 'default'">
        {{ work.submission ? '已完成' : work.writable ? '待办理' : '只读' }}
      </a-tag>
    </div>
    <dl class="context-summary">
      <dt>业务表单</dt>
      <dd>{{ work.formName }}</dd>
      <dt>业务对象</dt>
      <dd>{{ work.model.object.objectName }}</dd>
      <dt>使用版本</dt>
      <dd>应用 V{{ work.draft.resource.applicationVersion }}</dd>
    </dl>
    <a-tabs v-model:active-key="activeTab" class="context-tabs" :destroy-inactive-tab-pane="false">
      <a-tab-pane key="process" tab="流程信息">
        <a-spin :spinning="loading">
          <a-alert v-if="error" type="warning" :message="error" show-icon>
            <template #action><a-button size="small" @click="load">重试</a-button></template>
          </a-alert>
          <dl class="context-summary">
            <dt>流程名称</dt>
            <dd>{{ detail?.processInstance?.name || '—' }}</dd>
            <dt>发起人</dt>
            <dd>{{ detail?.processInstance?.startUser?.nickname || '—' }}</dd>
            <dt>发起时间</dt>
            <dd>{{ formatDateTime(detail?.processInstance?.createTime || '') }}</dd>
            <dt>办理节点</dt>
            <dd>{{ nodeName }}</dd>
          </dl>
          <div v-if="detail?.activityNodes?.length" class="context-progress">
            <h3>流转进度</h3>
            <ol>
              <li v-for="node in detail.activityNodes" :key="node.id" :class="{ running: node.status === 1 }">
                <span>{{ node.name }}</span>
                <small>{{ getTaskStatusMeta(node.status).label }}</small>
              </li>
            </ol>
          </div>
          <a-button block @click="$emit('process')">查看完整流程</a-button>
        </a-spin>
      </a-tab-pane>
      <a-tab-pane key="material" tab="提交材料">
        <template v-if="work.submission">
          <a-alert
            type="success"
            show-icon
            message="本次材料已留存"
            description="左侧展示本次提交内容，可与最新业务记录分别查看。"
          />
          <dl class="context-summary">
            <dt>提交时间</dt>
            <dd>{{ formatDateTime(work.submission.submittedAt) }}</dd>
            <dt>材料编号</dt>
            <dd>{{ work.submission.id }}</dd>
            <dt>提交版本</dt>
            <dd>应用 V{{ work.submission.resource.applicationVersion }}</dd>
          </dl>
        </template>
        <a-empty v-else description="尚未提交材料">
          <p class="context-help">暂存只保存草稿。完成任务后，这里将显示本次提交信息。</p>
        </a-empty>
      </a-tab-pane>
      <a-tab-pane key="record" tab="业务记录">
        <dl class="context-summary">
          <dt>业务对象</dt>
          <dd>{{ work.model.object.objectName }}</dd>
          <dt>记录编号</dt>
          <dd>{{ work.submission?.recordId || work.draft.recordId || '提交后生成' }}</dd>
        </dl>
        <a-alert
          v-if="work.recordChanged"
          type="warning"
          show-icon
          message="业务记录已有更新"
          description="左侧材料保留提交时的字段值。"
        />
        <p class="context-help">
          {{
            work.submission || work.draft.recordId
              ? '进入业务应用查看当前有权访问的记录。'
              : '当前正在新增记录，暂存草稿不会生成正式业务记录。'
          }}
        </p>
        <a-button block @click="$emit('records')">进入业务应用</a-button>
      </a-tab-pane>
    </a-tabs>
    <details class="context-identifiers">
      <summary>任务标识</summary>
      <dl class="context-summary">
        <dt>任务编号</dt>
        <dd>{{ taskId }}</dd>
        <dt>流程编号</dt>
        <dd>{{ workspace.processInstanceId }}</dd>
      </dl>
    </details>
  </aside>
</template>
<style scoped>
.flow-context {
  min-width: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 20px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  background: var(--component-background, #fff);
}
.context-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.context-heading h2 {
  margin: 0;
  font-size: 16px;
}
.context-summary {
  margin: 16px 0;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px 12px;
  font-size: 13px;
}
.context-summary dt {
  color: var(--text-secondary, #64748b);
}
.context-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.context-help {
  margin: 12px 0;
  color: var(--text-secondary, #64748b);
  font-size: 13px;
  line-height: 1.7;
}
.context-tabs :deep(.ant-tabs-tab) {
  font-size: 13px;
  padding: 12px 0;
}
.context-tabs :deep(.ant-tabs-tab + .ant-tabs-tab) {
  margin-left: 16px;
}
.context-identifiers {
  border-top: 1px solid var(--border-color, #e5e7eb);
  padding-top: 16px;
  margin-top: 20px;
  color: var(--text-secondary, #64748b);
  font-size: 12px;
}
.context-identifiers summary {
  cursor: pointer;
}
.context-progress h3 {
  font-size: 13px;
}
.context-progress ol {
  list-style: none;
  padding: 0;
}
.context-progress li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0 8px 14px;
  border-left: 2px solid var(--border-color, #e5e7eb);
}
.context-progress li.running {
  border-color: var(--ant-primary-color, #5b3fd6);
  font-weight: 600;
}
.context-progress small {
  flex-shrink: 0;
  color: var(--text-secondary, #64748b);
}
@media (max-width: 900px) {
  .flow-context {
    overflow: visible;
  }
}
</style>
