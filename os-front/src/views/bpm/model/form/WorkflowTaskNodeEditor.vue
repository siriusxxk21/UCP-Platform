<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getForm } from '@/api/bpm/form'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { newWorkflowTaskSetting, workflowTaskFromTemplate } from '@/nocode/workflow-task-node'
import type { TaskMember, TaskTemplate, TaskTemplateVersionSummary } from '@/types/nocode/task-center'
import type { WorkflowTaskNodeSetting } from '@/types/nocode/workflow-task-node'
import TaskNodeEditor from '@/views/nocode/task-center/TaskNodeEditor.vue'
import TaskNodeFields from '@/views/nocode/task-center/TaskNodeFields.vue'
import WorkflowTaskPersonField from './WorkflowTaskPersonField.vue'
import '@/views/nocode/task-center/workspace.css'

const props = defineProps<{ formId?: string | number }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const setting = defineModel<WorkflowTaskNodeSetting>({ required: true })
const api = useNocodePlatform().taskCenter
const { confirm } = useTaskConfirmation()
const activeTab = ref('arrangement')
const view = ref<'list' | 'graph'>('list')
const members = ref<TaskMember[]>([])
const templates = ref<TaskTemplate[]>([])
const versions = ref<TaskTemplateVersionSummary[]>([])
const fields = ref<Array<{ value: string, label: string }>>([])
const error = ref('')
const fieldError = ref('')
const busy = ref(false)
let generation = 0
let fieldGeneration = 0
let disposed = false
const allNodes = computed(() => [setting.value.task, ...setting.value.nodes])
const templateDataLocked = computed(() => setting.value.source === 'TEMPLATE')
const templateOptions = computed(() => {
  const result = templates.value.filter(t => t.publishedVersion).map(t => ({ value: t.id, label: t.name }))
  if (setting.value.templateId && !result.some(t => t.value === setting.value.templateId))
    result.push({ value: setting.value.templateId, label: '当前固定模板' })
  return result
})
const versionOptions = computed(() => {
  const result = versions.value.map(v => ({ value: v.version, label: `V${v.version}${v.primary ? ' · 主版本' : ''}` }))
  if (setting.value.templateVersion && !result.some(v => v.value === setting.value.templateVersion))
    result.push({ value: setting.value.templateVersion, label: `V${setting.value.templateVersion} · 当前固定版本` })
  return result
})
watch(busy, value => emit('busy', value))
watch(
  () => setting.value.nodes.map(n => n.id),
  (ids: string[]) => {
    const valid = new Set([setting.value.task.id, ...ids])
    setting.value.people = (setting.value.people || []).filter(p => valid.has(p.nodeId))
  },
)
async function changeSource(value: unknown) {
  if (busy.value || value === setting.value.source)
    return
  if (!(await confirm('更换任务来源？', '当前节点的任务配置将被替换，原任务模板不受影响。', '更换来源')))
    return
  generation++
  const next = newWorkflowTaskSetting()
  next.source = value === 'TEMPLATE' ? 'TEMPLATE' : 'CUSTOM'
  setting.value = next
  versions.value = []
  error.value = ''
}
async function selectTemplate(id: string, version?: number) {
  if (busy.value)
    return
  if (setting.value.task.title && !(await confirm('替换任务配置？', '将使用所选版本的快照替换当前编排。', '替换配置')))
    return
  const current = ++generation
  busy.value = true
  error.value = ''
  try {
    const [snapshot, catalog] = await Promise.all([api.templateVersion(id, version), api.templateVersions(id)])
    if (disposed || current !== generation)
      return
    setting.value = workflowTaskFromTemplate(snapshot)
    versions.value = catalog
  }
  catch (cause) {
    if (!disposed && current === generation)
      error.value = `模板加载失败，原配置未改动：${errorMessage(cause)}`
  }
  finally {
    if (current === generation)
      busy.value = false
  }
}
async function loadOptions() {
  const current = generation
  error.value = ''
  try {
    const [people, list] = await Promise.all([api.members(), api.templates()])
    if (disposed)
      return
    members.value = people
    templates.value = list
    if (setting.value.templateId) {
      const catalog = await api.templateVersions(setting.value.templateId)
      if (!disposed && current === generation)
        versions.value = catalog
    }
  }
  catch (cause) {
    if (!disposed)
      error.value = `人员或模板目录加载失败：${errorMessage(cause)}`
  }
}
watch(
  () => props.formId,
  async (id: string | number | undefined) => {
    const current = ++fieldGeneration
    fields.value = []
    fieldError.value = ''
    if (id == null)
      return
    try {
      const form = await getForm(id)
      const values: Array<{ value: string, label: string }> = []
      const visit = (rule: Record<string, unknown>) => {
        if (typeof rule.field === 'string' && rule.title)
          values.push({ value: rule.field, label: `${String(rule.title)}（${rule.field}）` })
        if (Array.isArray(rule.children))
          rule.children.forEach(visit)
      }
      form.fields.forEach(raw => visit(JSON.parse(raw)))
      if (!disposed && current === fieldGeneration)
        fields.value = values
    }
    catch (cause) {
      if (!disposed && current === fieldGeneration)
        fieldError.value = `流程字段加载失败：${errorMessage(cause)}`
    }
  },
  { immediate: true },
)
onMounted(loadOptions)
onBeforeUnmount(() => {
  disposed = true
  generation++
  fieldGeneration++
  emit('busy', false)
})
</script>

<template>
  <section class="workflow-task-editor">
    <a-alert
      show-icon
      type="info"
      message="流程抵达本节点时创建任务；整件任务正常完成后，流程自动继续。"
      description="执行、拆分和验收均在任务中心处理。不自动开始执行，也不自动加入个人计划。"
    />
    <div class="workflow-task-editor__source">
      <a-radio-group
        :value="setting.source"
        :disabled="busy"
        button-style="solid"
        @change="(event: { target: { value: string } }) => changeSource(event.target.value)"
      >
        <a-radio-button value="CUSTOM">
          新建任务
        </a-radio-button>
        <a-radio-button value="TEMPLATE">
          从模板配置
        </a-radio-button>
      </a-radio-group>
      <template v-if="setting.source === 'TEMPLATE'">
        <a-form-item label="任务模板">
          <a-select
            :value="setting.templateId"
            :options="templateOptions"
            :loading="busy"
            :disabled="busy"
            show-search
            option-filter-prop="label"
            placeholder="选择已发布模板"
            aria-label="任务模板"
            @change="(id: unknown) => selectTemplate(String(id))"
          />
        </a-form-item>
        <a-form-item label="模板版本">
          <a-select
            :value="setting.templateVersion"
            :options="versionOptions"
            :disabled="busy || !setting.templateId"
            placeholder="默认主版本"
            aria-label="模板版本"
            @change="(version: unknown) => selectTemplate(setting.templateId!, Number(version))"
          />
        </a-form-item>
      </template>
    </div>
    <p class="task-list__hint">
      当前配置随流程发布固定；后续修改模板或切换主版本不影响已发布流程及历史任务。
    </p>
    <a-alert v-if="error" type="error" :message="error" show-icon>
      <template #action>
        <a-button size="small" @click="loadOptions">
          重试加载
        </a-button>
      </template>
    </a-alert>
    <a-alert v-if="fieldError" type="warning" :message="fieldError" show-icon />
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="arrangement" tab="任务编排">
        <p class="task-list__hint">
          负责人和总任务验收人可选择固定人员、流程发起人或流程表单人员字段。相对排期从抵达此节点时计算。
        </p>
        <TaskNodeEditor
          v-model="setting.nodes"
          v-model:root="setting.task"
          v-model:view="view"
          :members="members"
          :readonly="busy || (setting.source === 'TEMPLATE' && !setting.templateVersion)"
          :data-readonly="templateDataLocked"
          :template-instance="templateDataLocked"
          template-editing
          inline-configuration
        >
          <template #assignment="{ node, readonly }">
            <WorkflowTaskPersonField
              :setting="setting"
              :node="node"
              role="ASSIGNEE"
              :members="members"
              :fields="fields"
              :readonly="readonly"
            />
          </template>
          <template #acceptance="{ node, readonly }">
            <WorkflowTaskPersonField
              :setting="setting"
              :node="node"
              role="ACCEPTOR"
              :members="members"
              :fields="fields"
              :readonly="readonly"
            />
          </template>
        </TaskNodeEditor>
      </a-tab-pane>
      <a-tab-pane key="business" tab="业务关联">
        <p v-if="templateDataLocked" class="task-list__hint">
          模板的业务资源与数据授权已固定；人员、排期仍可调整。如需修改业务配置，请修改模板并重新发布。
        </p>
        <TaskNodeFields
          v-model="setting.task"
          :members="members"
          :nodes="allNodes"
          :hierarchy-root-id="setting.task.id"
          :readonly="busy"
          :data-readonly="templateDataLocked"
          is-root
          template-editing
          section="business"
        />
      </a-tab-pane>
    </a-tabs>
  </section>
</template>

<style scoped>
.workflow-task-editor {
  display: grid;
  gap: var(--spacing-md);
  min-width: 0;
}
.workflow-task-editor__source {
  display: flex;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
  align-items: end;
}
.workflow-task-editor__source :deep(.ant-form-item) {
  margin-bottom: 0;
  min-width: 220px;
}
.workflow-task-editor :deep(.task-arrangement-workspace) {
  min-height: 440px;
}
.workflow-task-editor :deep(.task-arrangement-workspace--expanded) {
  min-height: 0;
}
.workflow-task-editor p {
  margin: 0;
}
</style>
