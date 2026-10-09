<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { TaskClaimLocation } from '@/nocode/task-claim-entry'
import type { TaskClaimableItem } from '@/types/nocode/task-center'
import TaskClaimDialog from './TaskClaimDialog.vue'

const props = defineProps<{ target: TaskClaimLocation; planReadonly?: boolean }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const api = useNocodePlatform().taskCenter
const ready = ref<TaskClaimLocation & { revision?: number }>()
const loading = ref(false)
const error = ref('')
const unavailable = ref('')
let generation = 0
onBeforeUnmount(() => generation++)
function unavailableReason(item?: TaskClaimableItem) {
  if (item?.assigneeId != null) return `该任务已由${item.assigneeName || '其他同事'}负责，无需再次领取。`
  if (item && item.status !== 'PENDING') return '该任务当前不是待领取状态。'
  return '该任务当前不在你的可领取范围内，可返回查看负责人和任务安排。'
}
async function load() {
  const token = ++generation
  ready.value = undefined
  error.value = ''
  unavailable.value = ''
  loading.value = true
  try {
    if (props.target.id === props.target.rootId) {
      // 总任务沿用领取弹窗内的整组预览与修订校验。
      ready.value = { ...props.target }
    } else {
      const items = await api.claimableChildren(props.target.rootId)
      if (token !== generation) return
      const item = items.find(item => item.id === props.target.id && item.rootId === props.target.rootId)
      if (item?.canClaim) ready.value = item
      else unavailable.value = unavailableReason(item)
    }
  } catch (cause) {
    if (token === generation) error.value = errorMessage(cause)
  } finally {
    if (token === generation) loading.value = false
  }
}
watch([() => props.target.rootId, () => props.target.id], load, { immediate: true })
</script>

<template>
  <TaskClaimDialog
    v-if="ready"
    :key="ready.id"
    :task="ready"
    :plan-readonly="planReadonly"
    @close="emit('close')"
    @saved="emit('saved')"
  />
  <OsModalForm
    v-else
    :open="true"
    :title="`领取任务 · ${target.title}`"
    :width="560"
    :allow-switch-display="false"
    :wrap-form="false"
    :show-footer="false"
    @cancel="emit('close')"
  >
    <template #formItems>
      <a-spin v-if="loading" tip="正在查询可领取任务…" />
      <template v-else>
        <a-alert :type="error ? 'error' : 'info'" show-icon :message="error || unavailable" />
        <a-space class="task-claim-entry__actions">
          <a-button @click="emit('close')">返回任务</a-button>
          <a-button type="primary" @click="load">重新查询</a-button>
        </a-space>
      </template>
    </template>
  </OsModalForm>
</template>

<style scoped>
.task-claim-entry__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--spacing-lg);
}
</style>
