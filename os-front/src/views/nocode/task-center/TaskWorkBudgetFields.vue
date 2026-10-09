<script setup lang="ts">
import { computed, watch } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import { taskWorkBudgetMinutes } from '@/nocode/task-work-budget'
import { taskWorkBudgetError } from '@/nocode/task-work-rule'
import { formatEffectiveWorkMinutes } from '@/nocode/task-work-duration'
import TaskWorkDurationInput from './TaskWorkDurationInput.vue'

const props = withDefaults(defineProps<{ entries: TaskWorkEntryConfig[]; readonly?: boolean }>(), { readonly: false })
const minutes = defineModel<number | null>('minutes', { default: null })
const mode = defineModel<'AUTO' | 'MANUAL' | null>('mode', { default: null })
const selectedMode = computed(() => mode.value || 'MANUAL')
const automaticMinutes = computed(() => taskWorkBudgetMinutes(props.entries))
const budgetError = computed(() => taskWorkBudgetError(props.entries))
// 旧模板默认人工总额，只有明确选择自动合计后才将表单预计量写入总预算。
watch(
  [selectedMode, automaticMinutes, () => props.readonly],
  () => {
    if (!props.readonly && selectedMode.value === 'AUTO' && minutes.value !== automaticMinutes.value)
      minutes.value = automaticMinutes.value
  },
  { immediate: true }
)
function changeMode(value: 'AUTO' | 'MANUAL') {
  if (props.readonly || (value !== 'AUTO' && value !== 'MANUAL')) return
  mode.value = value
}
function updateManualMinutes(value: number | null) {
  if (props.readonly || selectedMode.value !== 'MANUAL' || value === minutes.value) return
  // 旧模板未存模式时，仅实际编辑总额才显式标记人工设置，防止发起时被模板基准覆盖。
  mode.value = 'MANUAL'
  minutes.value = value
}
</script>
<template>
  <section class="work-budget" aria-label="任务标准总工时">
    <div class="work-budget__title">
      <strong>任务标准总工时</strong>
      <a-tooltip
        :trigger="['hover', 'focus', 'click']"
        title="工时选填，不影响任务运行。自动合计需要各表单的工时和预计量，也可手动设置总额。员工工时按实际办理计算，不与总工时重复相加。"
      >
        <button type="button" class="work-budget__help" aria-label="任务标准总工时说明">
          <QuestionCircleOutlined />
        </button>
      </a-tooltip>
    </div>
    <div class="work-budget__controls">
      <a-radio-group
        :value="selectedMode"
        :disabled="readonly"
        aria-label="任务总工时计算方式"
        @update:value="changeMode"
      >
        <a-radio-button value="AUTO">自动合计</a-radio-button>
        <a-radio-button value="MANUAL">手动设置</a-radio-button>
      </a-radio-group>
      <strong v-if="selectedMode === 'AUTO'" class="work-budget__total" aria-label="自动合计总工时">
        {{ automaticMinutes == null ? '未设置' : formatEffectiveWorkMinutes(automaticMinutes) }}
      </strong>
      <TaskWorkDurationInput
        v-else
        :model-value="minutes"
        label="任务标准总工时"
        :disabled="readonly"
        @update:model-value="updateManualMinutes"
      />
    </div>
    <p v-if="selectedMode === 'AUTO' && budgetError" class="work-budget__warning" role="status">
      {{ budgetError }}
    </p>
  </section>
</template>
<style scoped>
.work-budget {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: var(--spacing-sm) var(--spacing-lg);
  padding: var(--spacing-lg);
  border: 1px solid var(--color-border-secondary, #e5e7eb);
  border-radius: var(--border-radius, 6px);
  background: var(--color-bg-container, #fff);
}
.work-budget__title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}
.work-budget__help,
.work-budget__warning {
  font-size: var(--table-font-sm);
  color: var(--text-secondary);
}
.work-budget__help {
  display: inline-flex;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: help;
}
.work-budget__help:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.work-budget__controls {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
}
.work-budget__controls :deep(.task-work-duration) {
  width: 260px;
  max-width: 100%;
}
.work-budget__total {
  font-size: var(--font-size-lg, 18px);
  color: var(--brand);
  font-variant-numeric: tabular-nums;
}
.work-budget__warning {
  margin: 0;
  grid-column: 1 / -1;
}
.work-budget__warning {
  color: var(--color-warning, #ad6800);
}
@media (max-width: 720px) {
  .work-budget {
    grid-template-columns: 1fr;
  }
  .work-budget__total {
    min-width: 0;
  }
}
</style>
