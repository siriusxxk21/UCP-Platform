<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import type { TaskSchedule } from '@/types/nocode/task-center'
import type { TaskSplitPlan } from '@/nocode/task-split-plan'
import TaskChecklistChoice from './TaskChecklistChoice.vue'
import TaskScheduleFields from './TaskScheduleFields.vue'

const props = defineProps<{
  path: string
  plannedStart?: string | null
  planSummary?: string
  busy: boolean
  error?: string
  planError?: string
  retryBusy: boolean
}>()
const title = defineModel<string>('title', { required: true })
const plan = defineModel<TaskSplitPlan>('plan', { required: true })
const schedule = defineModel<TaskSchedule>('schedule', { required: true })
const emit = defineEmits<{ save: [continueAdding: boolean]; close: []; retry: []; later: [] }>()
const input = ref<{ focus: () => void }>()
const scheduleOpen = ref(false)
const planOpen = ref(false)
async function focus() {
  await nextTick()
  input.value?.focus()
}
onMounted(focus)
defineExpose({ focus })
function submit(continueAdding: boolean, event?: KeyboardEvent) {
  if (event?.isComposing || props.busy || props.planError || !title.value.trim()) return
  emit('save', continueAdding)
}
function onEnter(event: KeyboardEvent) {
  submit(true, event)
}
</script>

<template>
  <OsModalForm
    :open="true"
    title="拆分子任务"
    :width="560"
    :loading="busy || retryBusy"
    :wrap-form="false"
    :allow-switch-display="false"
    :resizable="false"
    :keyboard="!busy && !retryBusy"
    @cancel="emit('close')"
  >
    <template #formItems>
      <div class="task-split-dialog">
        <div class="task-split-dialog__location">
          <span>所属位置</span>
          <strong>{{ path }}</strong>
        </div>
        <a-alert v-if="planError" type="warning" show-icon :message="planError">
          <template #description>
            <a-space>
              <a-button :loading="retryBusy" @click="emit('retry')">重试纳入计划</a-button>
              <a-button :disabled="retryBusy" @click="emit('later')">稍后从任务行安排</a-button>
            </a-space>
          </template>
        </a-alert>
        <a-alert v-else-if="error" type="error" show-icon :message="error" />
        <div class="task-split-dialog__field">
          <label for="task-split-name">
            任务名称
            <span class="task-split-dialog__note">由我负责</span>
          </label>
          <a-input
            id="task-split-name"
            ref="input"
            v-model:value="title"
            :maxlength="160"
            :disabled="busy || !!planError"
            placeholder="填写子任务名称，Enter 添加并继续"
            aria-label="子任务名称"
            @press-enter="onEnter"
          />
        </div>
        <div class="task-split-dialog__field">
          <div class="task-split-dialog__plan">
            <span class="task-split-dialog__note">{{ planSummary || '自动随本人上级计划，无需重复安排' }}</span>
            <a-button
              type="link"
              :disabled="busy || !!planError"
              :aria-expanded="planOpen"
              @click="planOpen = !planOpen"
            >
              {{ planOpen ? '收起计划设置' : '单独加入计划' }}
            </a-button>
          </div>
          <TaskChecklistChoice
            v-if="planOpen"
            v-model="plan"
            allow-later
            compact
            later-label="随上级"
            :disabled="busy || !!planError"
          />
        </div>
        <div class="task-split-dialog__field">
          <a-button
            class="task-split-dialog__time"
            type="link"
            :disabled="busy || !!planError"
            :aria-expanded="scheduleOpen"
            @click="scheduleOpen = !scheduleOpen"
          >
            {{ scheduleOpen ? '收起时间设置' : '设置时间（可选）' }}
          </a-button>
          <TaskScheduleFields
            v-if="scheduleOpen"
            v-model="schedule"
            :readonly="busy || !!planError"
            :planned-start="plannedStart"
            :has-predecessors="false"
          />
        </div>
      </div>
    </template>
    <template #footer>
      <a-space>
        <a-button :disabled="busy || retryBusy" @click="emit('close')">取消</a-button>
        <a-button :disabled="busy || !!planError || !title.trim()" @click="submit(true)">添加并继续</a-button>
        <a-button type="primary" :loading="busy" :disabled="!!planError || !title.trim()" @click="submit(false)">
          添加
        </a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>

<style scoped>
.task-split-dialog {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}
.task-split-dialog__location {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  background: var(--neutral-bg);
  border-radius: var(--radius-sm);
  overflow-wrap: anywhere;
}
.task-split-dialog__location > span,
.task-split-dialog__note {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-split-dialog__note {
  margin-left: var(--spacing-sm);
}
.task-split-dialog__plan {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}
.task-split-dialog__plan .task-split-dialog__note {
  margin-left: 0;
}
.task-split-dialog__field {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--spacing-sm);
}
.task-split-dialog__field :deep(.task-list__hint) {
  margin: 0;
}
.task-split-dialog__time {
  align-self: flex-start;
  padding: 0;
  height: auto;
}
</style>
