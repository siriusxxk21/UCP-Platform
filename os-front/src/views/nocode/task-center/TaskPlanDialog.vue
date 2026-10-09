<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  checklistCommand,
  checklistPeriodLabel,
  checklistPlans,
  createChecklistAttempt,
  taskPlanHistoryLabel,
  taskPlanSourceLabel,
  isInheritedTaskPlan
} from '@/nocode/task-checklist'
import type {
  TaskChecklistContext,
  TaskChecklistItem,
  TaskChecklistChoice as ChecklistChoice,
  TaskPlan
} from '@/types/nocode/task-center'
import TaskChecklistChoice from './TaskChecklistChoice.vue'

const props = defineProps<{
  ids: string[]
  target?: 'SELF' | 'ASSIGNEE'
  taskNames?: string[]
  initialPeriod?: ChecklistChoice
  initialAction?: 'ADD' | 'REMOVE'
}>()
const emit = defineEmits<{ close: []; saved: [] }>()
const api = useNocodePlatform().taskCenter
const context = ref<TaskChecklistContext>(),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  uncertain = ref(false)
const period = ref<ChecklistChoice | 'LATER'>(props.initialPeriod || 'WEEK'),
  historyOpen = ref(false)
let attempt = createChecklistAttempt(),
  generation = 0
const target = computed(() => props.target || 'SELF')
// 管理入口只查看；个人清单的写操作始终使用本人目标。
const viewOnly = computed(() => target.value === 'ASSIGNEE')
const currentPeriod = computed<ChecklistChoice>(() => (period.value === 'LATER' ? 'WEEK' : period.value))
const periodDates = computed(() => {
  if (!context.value) return ''
  const value = context.value
  return currentPeriod.value === 'DAY'
    ? value.today
    : currentPeriod.value === 'NEXT_WEEK'
      ? `${value.nextWeekStart || '—'} 至 ${value.nextWeekEnd || '—'}`
      : `${value.weekStart} 至 ${value.weekEnd}`
})
const items = computed(() => (context.value?.items || []).filter(item => props.ids.includes(item.taskId)))
const loaded = computed(
  () => !loading.value && !!context.value && props.ids.every(id => items.value.some(item => item.taskId === id))
)
const addable = computed(() =>
  viewOnly.value ? [] : items.value.filter(item => item.canAdd && !checklistPlans(item, currentPeriod.value).length)
)
const canAdd = computed(() => loaded.value && addable.value.length > 0)
const removable = computed(() =>
  items.value.flatMap(item =>
    checklistPlans(item, currentPeriod.value)
      .filter(plan => !viewOnly.value && plan.canCancel && plan.id && !isInheritedTaskPlan(plan))
      .map(plan => ({ item, plan }))
  )
)
const nonRemovableCount = computed(
  () => items.value.flatMap(item => checklistPlans(item, currentPeriod.value)).filter(plan => !plan.canCancel).length
)
const removeMode = computed(() => props.initialAction === 'REMOVE')
const canSubmit = computed(
  () => uncertain.value || (removeMode.value ? loaded.value && removable.value.length > 0 : canAdd.value)
)
const historyCount = computed(() => items.value.reduce((sum, item) => sum + item.history.length, 0))
async function load() {
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await api.checklistContext({ ids: props.ids, target: target.value })
    if (token === generation) context.value = result
  } catch (cause) {
    if (token === generation) {
      context.value = undefined
      error.value = errorMessage(cause)
    }
  } finally {
    if (token === generation) loading.value = false
  }
}
async function submit(remove?: { item: TaskChecklistItem; plan: TaskPlan }) {
  if (viewOnly.value || busy.value || loading.value || (!remove && !canSubmit.value) || !context.value) return
  busy.value = true
  error.value = ''
  const action = remove || removeMode.value ? 'REMOVE' : 'ADD'
  try {
    const result = await attempt.submit(api, () => {
      if (!context.value) throw new Error('请先读取任务清单')
      const chosen = remove ? [remove] : removable.value
      const ids =
        action === 'ADD' ? addable.value.map(item => item.taskId) : [...new Set(chosen.map(value => value.item.taskId))]
      const planIds = chosen.flatMap(value => (value.plan.id ? [value.plan.id] : []))
      return checklistCommand(context.value, ids, target.value, currentPeriod.value, action, planIds)
    })
    uncertain.value = false
    message.success(result.changed.length ? `${checklistPeriodLabel(currentPeriod.value)}已更新` : '计划清单没有变化')
    emit('saved')
    if (remove) await load()
    else emit('close')
  } catch (cause) {
    uncertain.value = !!attempt.pending
    error.value =
      errorMessage(cause) + (uncertain.value ? '。结果尚未确认，请重试原请求，不会重复加入。' : '。请刷新清单后重试。')
  } finally {
    busy.value = false
  }
}
watch(
  () => [props.ids.join(','), target.value],
  () => {
    attempt = createChecklistAttempt()
    uncertain.value = false
    context.value = undefined
    period.value = props.initialPeriod || 'WEEK'
    void load()
  },
  { immediate: true }
)
onBeforeUnmount(() => generation++)
</script>
<template>
  <OsModalForm
    :open="true"
    :title="viewOnly ? '查看负责人计划' : removeMode ? '移出计划清单' : '安排计划'"
    :loading="busy"
    :width="640"
    :allow-switch-display="false"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    @cancel="!busy && emit('close')"
    @ok="submit()"
  >
    <template #formItems>
      <a-spin :spinning="loading">
        <template v-if="viewOnly">
          <a-radio-group v-model:value="period" button-style="solid">
            <a-radio-button value="WEEK">本周计划</a-radio-button>
            <a-radio-button value="DAY">今日计划</a-radio-button>
            <a-radio-button value="NEXT_WEEK">下周计划</a-radio-button>
          </a-radio-group>
          <p class="task-list__hint">计划由负责人本人维护。</p>
        </template>
        <TaskChecklistChoice
          v-else
          v-model="period"
          :disabled="busy || uncertain || removeMode"
          :action="removeMode ? 'REMOVE' : 'ADD'"
        />
        <p v-if="context" class="task-list__hint">{{ checklistPeriodLabel(currentPeriod) }}：{{ periodDates }}</p>
        <a-alert v-if="error" type="error" show-icon :message="error" />
        <a-button v-if="error && !uncertain" :disabled="busy || loading" @click="load">刷新清单</a-button>
        <p v-if="removeMode && nonRemovableCount" class="task-list__hint">
          {{ nonRemovableCount }} 项当前不能移出，会保留；仅移出有权限的计划项。
        </p>
        <p v-if="!viewOnly && !removeMode" class="task-list__hint">
          加入后，本人负责的下级自动随计划纳入；子任务仍可单独加入今日计划。
        </p>
        <p v-if="!viewOnly && removeMode" class="task-list__hint">
          移出上级后，下级不再随其纳入；下级自行加入的计划保留。
        </p>
        <p v-if="!viewOnly && !removeMode && loaded && items.some(item => !item.canAdd)" class="task-list__hint">
          {{ items.filter(item => !item.canAdd).length }} 项当前不能加入；仅处理可加入的
          {{ addable.length }} 项，具体原因如下。
        </p>
        <div v-for="item in items" :key="item.taskId" class="task-checklist__item">
          <strong>{{ item.title }}</strong>
          <p v-if="!checklistPlans(item, currentPeriod).length">尚未加入{{ checklistPeriodLabel(currentPeriod) }}</p>
          <div
            v-for="plan in checklistPlans(item, currentPeriod)"
            :key="plan.id || `${plan.inheritedFromTaskId}:${plan.period}:${plan.date}`"
            class="task-checklist__line"
          >
            <span>
              <template v-if="viewOnly">{{ plan.userName || '负责人' }} ·</template>
              {{ checklistPeriodLabel(currentPeriod) }} · {{ taskPlanSourceLabel(plan) }}
            </span>
            <a-button
              v-if="!viewOnly && plan.canCancel && plan.id && !isInheritedTaskPlan(plan) && !removeMode"
              type="link"
              :disabled="busy || uncertain"
              @click="submit({ item, plan })"
            >
              移出此清单
            </a-button>
            <small v-else-if="!viewOnly && (!plan.canCancel || isInheritedTaskPlan(plan))">
              {{ isInheritedTaskPlan(plan) ? '随上级纳入，请从上级调整' : item.reason || '仅供查看，当前不能移出' }}
            </small>
          </div>
          <p v-if="item.reason && !viewOnly" class="task-list__hint">{{ item.reason }}</p>
          <p v-for="warning in item.warnings" :key="warning" class="task-list__hint">{{ warning }}</p>
        </div>
        <a-button v-if="historyCount" type="link" @click="historyOpen = !historyOpen">
          {{ historyOpen ? '收起' : '查看' }}历史计划（{{ historyCount }}）
        </a-button>
        <div v-if="historyOpen" class="task-checklist__history">
          <template v-for="item in items" :key="item.taskId">
            <p v-for="plan in item.history" :key="plan.id || `${plan.period}:${plan.date}`">
              {{ item.title }} · {{ plan.userName || '负责人' }} · {{ taskPlanHistoryLabel(plan) }} ·
              {{ plan.arrangedByName || '安排人' }}{{ plan.active ? '（保留原记录）' : '' }}
            </p>
          </template>
        </div>
      </a-spin>
    </template>
    <template #footer>
      <a-button :disabled="busy" @click="emit('close')">关闭</a-button>
      <a-button v-if="!viewOnly && canSubmit" type="primary" :loading="busy" :disabled="loading" @click="submit()">
        {{
          uncertain
            ? '重试原请求'
            : removeMode
              ? '移出所选' + checklistPeriodLabel(currentPeriod)
              : '加入' + checklistPeriodLabel(currentPeriod)
        }}
      </a-button>
    </template>
  </OsModalForm>
</template>
<style scoped>
.task-checklist__item {
  padding: var(--spacing-md);
  margin-block: var(--spacing-sm);
  background: var(--neutral-bg);
  border-radius: var(--radius-sm);
}
.task-checklist__line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-sm);
  margin-block: var(--spacing-sm);
}
.task-checklist__history {
  max-height: 240px;
  overflow: auto;
  font-size: var(--table-font-sm);
}
</style>
