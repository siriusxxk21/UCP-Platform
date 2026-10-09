<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { v4 as uuid } from 'uuid'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { formatEffectiveWorkMinutes, validEffectiveWorkMinutes } from '@/nocode/task-work-duration'
import {
  formatTaskWorkQuantity,
  taskWorkBudgetError,
  taskWorkRuleError,
  taskWorkRuleMinutes,
  taskWorkRuleOptions
} from '@/nocode/task-work-rule'
import type { TaskWorkTimeContext, TaskWorkTimeChange, TaskWorkTimeEntry } from '@/types/nocode/task-work-time'
import TaskWorkDurationInput from './TaskWorkDurationInput.vue'
import TaskWorkBudgetFields from './TaskWorkBudgetFields.vue'

const props = defineProps<{ taskId: string }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const api = useNocodePlatform().taskCenter
const { confirmDiscard } = useTaskConfirmation()
const context = ref<TaskWorkTimeContext>()
const entries = ref<TaskWorkTimeEntry[]>([])
const totalMode = ref<'AUTO' | 'MANUAL' | null>('MANUAL')
const totalMinutes = ref<number | null>(null)
const reason = ref('')
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  reviewing = ref(false)
const pending = ref<TaskWorkTimeChange>()
let generation = 0
onBeforeUnmount(() => generation++)
const configs = computed(() => entries.value.map(entry => entry.config))
const changes = computed(() =>
  entries.value.flatMap(entry => {
    const before = context.value?.entries.find(
      item => item.taskId === entry.taskId && item.config.key === entry.config.key
    )?.config.workRule
    const after = entry.config.workRule
    if (!before || !after) return []
    const result: Array<{ key: string; name: string; before: string; after: string }> = []
    const key = `${entry.taskId}:${entry.config.key}`
    if (taskWorkRuleMinutes(before) !== taskWorkRuleMinutes(after))
      result.push({
        key: `${key}:rate`,
        name: `${entry.config.name} · 单位工时`,
        before: formatEffectiveWorkMinutes(taskWorkRuleMinutes(before)),
        after: formatEffectiveWorkMinutes(taskWorkRuleMinutes(after))
      })
    if ((before.plannedQuantity ?? null) !== (after.plannedQuantity ?? null))
      result.push({
        key: `${key}:quantity`,
        name: `${entry.config.name} · 预计工作量`,
        before: String(before.plannedQuantity ?? '未设置'),
        after: String(after.plannedQuantity ?? '未设置')
      })
    return result
  })
)
const budgetChanged = computed(
  () =>
    !!context.value &&
    (totalMode.value !== context.value.workTotalMode || totalMinutes.value !== context.value.effectiveWorkMinutes)
)
const changed = computed(() => changes.value.length > 0 || budgetChanged.value)
const dirty = computed(() => changed.value || !!reason.value.trim() || !!pending.value)
const readOnly = computed(() => !context.value?.canAdjust || reviewing.value || busy.value || !!pending.value)
const validation = computed(() => {
  for (const entry of entries.value) {
    const issue = taskWorkRuleError(entry.config.workRule)
    if (issue) return `${entry.config.name}：${issue}`
  }
  const budgetError = totalMode.value === 'AUTO' ? taskWorkBudgetError(configs.value) : ''
  if (budgetError) return budgetError
  if (!validEffectiveWorkMinutes(totalMinutes.value)) return '任务标准总工时超出可配置范围。'
  return ''
})
const columns = [
  { title: '业务办理项', key: 'entry', width: 200 },
  { title: '计时方式', key: 'mode', width: 150 },
  { title: '当前单位工时', key: 'before', width: 130 },
  { title: '调整后单位工时', key: 'minutes', width: 270 },
  { title: '预计工作量', key: 'quantity', width: 160 }
]
function originalRule(entry: TaskWorkTimeEntry) {
  return context.value?.entries.find(item => item.taskId === entry.taskId && item.config.key === entry.config.key)
    ?.config.workRule
}
function updateMinutes(entry: TaskWorkTimeEntry, minutes: number | null) {
  if (readOnly.value || !entry.config.workRule) return
  entry.config.workRule.adjustmentMinutes = (minutes ?? 0) - entry.config.workRule.minutes
}
async function load() {
  if (busy.value) return
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const next = await api.workTimeContext(props.taskId)
    if (token !== generation) return
    context.value = next
    entries.value = JSON.parse(JSON.stringify(next.entries))
    totalMode.value = next.workTotalMode
    totalMinutes.value = next.effectiveWorkMinutes
    reason.value = ''
    reviewing.value = false
    pending.value = undefined
  } catch (cause) {
    if (token === generation) error.value = errorMessage(cause)
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(() => props.taskId, load, { immediate: true })
async function canClose() {
  return !busy.value && (await confirmDiscard(dirty.value, pending.value ? '操作结果尚未确认，仍要关闭？' : undefined))
}
async function close() {
  if (await canClose()) emit('close')
}
useUnsavedNavigation(() => dirty.value || busy.value, { confirm: canClose })
function preview() {
  if (!context.value?.canAdjust || !changed.value || validation.value || !reason.value.trim()) return
  reviewing.value = true
}
async function save() {
  if (!context.value?.canAdjust || busy.value || !reviewing.value) return
  if (!pending.value) {
    if (!changed.value || validation.value || !reason.value.trim()) return
    pending.value = {
      rootId: context.value.rootId,
      expectedRevision: context.value.expectedRevision,
      requestKey: uuid(),
      reason: reason.value.trim(),
      workTotalMode: totalMode.value || 'MANUAL',
      effectiveWorkMinutes: totalMinutes.value,
      entries: entries.value
        .filter(entry => entry.config.workRule)
        .map(entry => ({
          taskId: entry.taskId,
          entryKey: entry.config.key,
          minutes: taskWorkRuleMinutes(entry.config.workRule!),
          plannedQuantity: entry.config.workRule!.plannedQuantity ?? null
        }))
    }
  }
  busy.value = true
  error.value = ''
  try {
    // 失败重试固定为同一份请求，响应丢失时不能以新键重复调整。
    await api.adjustWorkTime(pending.value)
    pending.value = undefined
    message.success('工时已调整，已计工时保持不变')
    emit('saved')
    emit('close')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <OsModalForm
    :open="true"
    title="调整工时"
    display-mode="modal"
    :allow-switch-display="false"
    :width="1120"
    :loading="busy"
    :wrap-form="false"
    @cancel="close"
  >
    <template #formItems>
      <a-spin :spinning="loading">
        <div class="work-time-dialog">
          <a-alert v-if="error" type="error" show-icon :message="error">
            <template v-if="!context" #action><a-button @click="load">重试加载</a-button></template>
          </a-alert>
          <template v-if="context">
            <div class="work-time-dialog__heading">
              <strong>{{ context.rootTitle }}</strong>
              <span>仅调整当前这组任务</span>
            </div>
            <a-alert
              v-if="!context.canAdjust"
              type="warning"
              show-icon
              :message="context.disabledReason || '当前任务不可调整工时'"
            />
            <a-alert
              type="info"
              show-icon
              message="已计工时保留，新办理使用新标准。"
              description="已提交审批的记录仍按提交时标准计算；预计工作量仅用于预算，不改变排期日期。"
            />
            <template v-if="!reviewing">
              <OsTablePage
                :data-source="entries"
                :columns="columns"
                :row-key="(row: TaskWorkTimeEntry) => `${row.taskId}:${row.config.key}`"
                :pagination="false"
                :show-index="false"
                :show-toolbar="false"
                :scroll="{ x: 910 }"
                class="nocode-embedded-table"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'entry'">
                    <strong>{{ record.config.name }}</strong>
                    <div v-if="record.taskId !== context.rootId" class="task-list__hint">{{ record.taskTitle }}</div>
                  </template>
                  <template v-else-if="column.key === 'mode'">
                    {{
                      taskWorkRuleOptions.find(option => option.value === record.config.workRule?.mode)?.label ||
                      '未设置工时'
                    }}
                  </template>
                  <template v-else-if="column.key === 'before'">
                    {{
                      originalRule(record)
                        ? formatEffectiveWorkMinutes(taskWorkRuleMinutes(originalRule(record)!))
                        : '—'
                    }}
                  </template>
                  <template v-else-if="column.key === 'minutes'">
                    <TaskWorkDurationInput
                      v-if="record.config.workRule"
                      :model-value="taskWorkRuleMinutes(record.config.workRule)"
                      :disabled="readOnly"
                      :label="`${record.config.name}单位工时`"
                      @update:model-value="updateMinutes(record, $event)"
                    />
                    <span v-else>—</span>
                  </template>
                  <template v-else-if="column.key === 'quantity'">
                    <a-input-number
                      v-if="record.config.workRule"
                      v-model:value="record.config.workRule.plannedQuantity"
                      :disabled="readOnly"
                      :min="0"
                      :max="999999"
                      :precision="record.config.workRule.mode === 'QUANTITY' ? 6 : 0"
                      :formatter="formatTaskWorkQuantity"
                      :aria-label="`${record.config.name}预计工作量`"
                      placeholder="未设置"
                    />
                    <span v-else>—</span>
                  </template>
                </template>
              </OsTablePage>
              <TaskWorkBudgetFields
                v-model:minutes="totalMinutes"
                v-model:mode="totalMode"
                :entries="configs"
                :readonly="readOnly"
              />
              <a-alert v-if="validation" type="warning" :message="validation" />
              <a-form-item label="调整原因" required>
                <a-textarea
                  v-model:value="reason"
                  :disabled="readOnly"
                  :maxlength="500"
                  :rows="2"
                  placeholder="例如：本次设备数量增加，调整预计工作量"
                />
              </a-form-item>
            </template>
            <template v-else>
              <h4>确认调整内容</h4>
              <div v-for="change in changes" :key="change.key" class="work-time-dialog__change">
                <span>{{ change.name }}</span>
                <span>
                  {{ change.before }} →
                  <strong>{{ change.after }}</strong>
                </span>
              </div>
              <div class="work-time-dialog__change">
                <span>任务标准总工时</span>
                <span>
                  {{ formatEffectiveWorkMinutes(context.effectiveWorkMinutes) }}（{{
                    context.workTotalMode === 'AUTO' ? '自动' : '手动'
                  }}） →
                  <strong>
                    {{ formatEffectiveWorkMinutes(totalMinutes) }}（{{ totalMode === 'AUTO' ? '自动' : '手动' }}）
                  </strong>
                </span>
              </div>
              <p>调整原因：{{ reason }}</p>
            </template>
          </template>
        </div>
      </a-spin>
    </template>
    <template #footer>
      <a-button :disabled="busy" @click="close">取消</a-button>
      <a-button v-if="reviewing && !pending" :disabled="busy" @click="reviewing = false">返回修改</a-button>
      <a-button v-if="reviewing" type="primary" :loading="busy" :disabled="!context?.canAdjust" @click="save">
        {{ pending ? '重试确认' : '确认调整' }}
      </a-button>
      <a-button
        v-else
        type="primary"
        :disabled="loading || !context?.canAdjust || !changed || !!validation || !reason.trim()"
        @click="preview"
      >
        查看调整内容
      </a-button>
    </template>
  </OsModalForm>
</template>
<style scoped>
.work-time-dialog {
  display: grid;
  gap: var(--spacing-lg);
}
.work-time-dialog__heading {
  display: flex;
  gap: var(--spacing-md);
  align-items: center;
}
.work-time-dialog__heading > span {
  color: var(--text-secondary);
}
.work-time-dialog__change {
  display: flex;
  justify-content: space-between;
  gap: var(--spacing-md);
  border-bottom: 1px solid var(--border-color);
  padding-bottom: var(--spacing-sm);
}
.work-time-dialog h4,
.work-time-dialog p {
  margin: 0;
}
</style>
