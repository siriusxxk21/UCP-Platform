<script setup lang="ts">
import { computed } from 'vue'
import type { TaskMember, TaskNodeInput } from '@/types/nocode/task-center'
import type { WorkflowTaskNodeSetting, WorkflowTaskPerson } from '@/types/nocode/workflow-task-node'
import { setWorkflowTaskPerson } from '@/nocode/workflow-task-node'
import TaskAssignmentFields from '@/views/nocode/task-center/TaskAssignmentFields.vue'
import TaskAcceptanceFields from '@/views/nocode/task-center/TaskAcceptanceFields.vue'

const props = defineProps<{
  setting: WorkflowTaskNodeSetting
  node: TaskNodeInput
  role: WorkflowTaskPerson['role']
  members: TaskMember[]
  fields: Array<{ value: string, label: string }>
  readonly?: boolean
}>()
const person = computed(() => props.setting.people?.find(p => p.nodeId === props.node.id && p.role === props.role))
const options = [
  { value: 'INITIATOR', label: '流程发起人' },
  { value: 'FORM_FIELD', label: '流程表单人员字段' },
]
const fieldOptions = computed(() => {
  const values = [...props.fields]
  if (person.value?.field && !values.some(item => item.value === person.value?.field))
    values.push({ value: person.value.field, label: `${person.value.field}（请确认字段仍存在）` })
  return values
})
function choose(value: string) {
  const source = value === 'INITIATOR' || value === 'FORM_FIELD' ? value : undefined
  setWorkflowTaskPerson(props.setting, props.node.id, props.role, source)
}
function chooseField(value: string) {
  setWorkflowTaskPerson(props.setting, props.node.id, props.role, 'FORM_FIELD', value)
}
</script>

<template>
  <div class="workflow-task-person">
    <TaskAssignmentFields
      v-if="role === 'ASSIGNEE'"
      :model-value="node"
      :members="members"
      :readonly="readonly"
      :allow-follow="node.id !== setting.task.id"
      :root-assignee-id="setting.task.assigneeId"
      :extra-options="options"
      :override-value="person?.source"
      compact
      @select="choose"
    />
    <TaskAcceptanceFields
      v-else
      :model-value="node"
      :members="members"
      :readonly="readonly"
      :extra-options="options"
      :override-value="person?.source"
      compact
      @select="choose"
    />
    <a-select
      v-if="person?.source === 'FORM_FIELD'"
      :value="person.field"
      :disabled="readonly"
      :options="fieldOptions"
      show-search
      option-filter-prop="label"
      placeholder="选择人员字段"
      aria-label="流程人员字段"
      @change="(value: unknown) => chooseField(String(value))"
    />
    <span v-if="person?.source === 'FORM_FIELD'" class="task-list__hint">字段值须为单个人员 ID</span>
  </div>
</template>

<style scoped>
.workflow-task-person {
  display: grid;
  gap: var(--spacing-xs);
  min-width: 0;
}
</style>
