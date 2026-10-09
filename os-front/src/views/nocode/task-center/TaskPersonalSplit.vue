<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { v4 as uuid } from 'uuid'
import type { TaskRow } from '@/types/nocode/task-center'
import { newTaskNode, taskNodeError } from '@/nocode/task-center'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import TaskScheduleFields from './TaskScheduleFields.vue'
import TaskContentField from './TaskContentField.vue'

const props = defineProps<{ parent: TaskRow }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const api = useNocodePlatform().taskCenter
const { confirmDiscard } = useTaskConfirmation()
const node = ref({
  ...newTaskNode(),
  assigneeId: props.parent.assigneeId,
  assignmentMode: 'ASSIGNED' as const,
  urgency: props.parent.urgency,
  priority: props.parent.priority
})
const initial = JSON.stringify(node.value)
const requestKey = uuid()
const busy = ref(false)
const error = ref('')
async function save() {
  if (busy.value) return
  error.value = taskNodeError([node.value]) || ''
  if (error.value) return
  busy.value = true
  try {
    await api.split({ task: node.value, parentId: props.parent.id, requestKey })
    message.success('子任务已添加，任务管理中同步可见')
    emit('saved')
    emit('close')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function close() {
  if (!busy.value && (await confirmDiscard(JSON.stringify(node.value) !== initial))) emit('close')
}
</script>
<template>
  <OsModalForm
    :open="true"
    title="拆分子任务"
    :width="640"
    :allow-switch-display="false"
    :mask-closable="false"
    :loading="busy"
    layout="vertical"
    ok-text="添加子任务"
    @ok="save"
    @cancel="close"
  >
    <template #formItems>
      <p>所属任务：{{ parent.title }}</p>
      <p class="task-list__hint">新子任务由你负责，继承原任务的数据权限和上级执行约束；不改变整组任务的先后关系。</p>
      <a-form-item label="任务名称" required>
        <a-input v-model:value="node.title" :maxlength="160" :disabled="busy" />
      </a-form-item>
      <a-form-item label="任务内容">
        <TaskContentField v-model="node.description" :disabled="busy" />
      </a-form-item>
      <TaskScheduleFields
        v-model="node.schedule"
        :planned-start="parent.plannedStart"
        :has-predecessors="false"
        :readonly="busy"
      />
      <a-alert v-if="error" type="error" show-icon :message="error" />
    </template>
  </OsModalForm>
</template>
