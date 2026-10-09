<script setup lang="ts">
import { computed, ref } from 'vue'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { TaskRow } from '@/types/nocode/task-center'

const props = defineProps<{ task: TaskRow }>()
const emit = defineEmits<{ close: []; deleted: [id: string] }>()
const api = useNocodePlatform().taskCenter
const busy = ref(false),
  error = ref('')
// 重试固定到同一节点、版本及幂等键，响应丢失不能扩大成另一次删除。
const command = { id: props.task.id, expectedRevision: props.task.revision, requestKey: uuid() }
const allowed = computed(() => props.task.id === command.id && props.task.canDelete === true)
async function remove() {
  if (busy.value || !allowed.value) return
  busy.value = true
  error.value = ''
  try {
    const deleted = await api.deleteSubtask(command)
    if (!deleted) throw new Error('未能确认删除结果，请刷新任务后重试')
    message.success('子任务已删除')
    emit('deleted', command.id)
    emit('close')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
function close() {
  if (!busy.value) emit('close')
}
</script>
<template>
  <OsModalForm
    :open="true"
    title="删除子任务"
    display-mode="modal"
    :allow-switch-display="false"
    :resizable="false"
    :width="480"
    :loading="busy"
    @cancel="close"
  >
    <template #formItems>
      <p>确认删除子任务「{{ task.title }}」？</p>
      <p>当前负责人：{{ task.assigneeName || '待领取' }}</p>
      <p class="task-list__hint">仅删除这一项，不会级联删除其他任务，也不会删除业务数据。删除后不能恢复。</p>
      <a-alert v-if="!allowed" type="warning" show-icon :message="task.deleteBlockedReason || '当前任务不可删除'" />
      <a-alert v-if="error" type="error" show-icon :message="error" />
    </template>
    <template #footer>
      <a-button :disabled="busy" @click="close">取消</a-button>
      <a-button type="primary" danger :loading="busy" :disabled="busy || !allowed" @click="remove">确认删除</a-button>
    </template>
  </OsModalForm>
</template>
