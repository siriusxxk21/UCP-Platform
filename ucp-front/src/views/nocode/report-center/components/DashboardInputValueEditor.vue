<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardFilterKind, DashboardInputValue } from '@/types/nocode/report-dashboard'
const props = withDefaults(
  defineProps<{
    modelValue?: DashboardInputValue | null
    kind: DashboardFilterKind
    label: string
    disabled?: boolean
  }>(),
  { disabled: false }
)
const emit = defineEmits<{ 'update:modelValue': [value: DashboardInputValue] }>()
const text = computed({
  get: () => props.modelValue?.values?.find(value => value !== null) || '',
  set: value => emit('update:modelValue', { values: value ? [value] : [] })
})
const multi = computed({
  get: () => (props.modelValue?.values || []).filter((value): value is string => value !== null),
  set: (value: string[]) => emit('update:modelValue', { values: [...value, ...(nullValue.value ? [null] : [])] })
})
const nullValue = computed({
  get: () => !!props.modelValue?.values?.includes(null),
  set: value =>
    emit('update:modelValue', {
      values: props.kind === 'MULTISELECT' ? [...multi.value, ...(value ? [null] : [])] : value ? [null] : []
    })
})
const from = computed({
  get: () => props.modelValue?.from || '',
  set: value => emit('update:modelValue', { from: value || null, to: props.modelValue?.to || null })
})
const to = computed({
  get: () => props.modelValue?.to || '',
  set: value => emit('update:modelValue', { from: props.modelValue?.from || null, to: value || null })
})
</script>
<template>
  <div class="dashboard-input-value">
    <a-space v-if="['NUMBER_RANGE', 'DATE_RANGE'].includes(kind)">
      <a-input
        v-model:value="from"
        :disabled="disabled"
        :type="kind === 'DATE_RANGE' ? 'date' : 'text'"
        :aria-label="label + '起始值'"
        placeholder="起始值"
      />
      <span>至</span>
      <a-input
        v-model:value="to"
        :disabled="disabled"
        :type="kind === 'DATE_RANGE' ? 'date' : 'text'"
        :aria-label="label + '结束值'"
        placeholder="结束值"
      />
    </a-space>
    <a-select
      v-else-if="kind === 'MULTISELECT'"
      v-model:value="multi"
      :disabled="disabled"
      mode="tags"
      :aria-label="label"
      placeholder="输入值后回车，可填写多个值"
    />
    <a-input
      v-else
      v-model:value="text"
      :disabled="disabled || nullValue"
      :aria-label="label"
      placeholder="请输入筛选值"
    />
    <a-checkbox v-if="['SELECT', 'MULTISELECT'].includes(kind)" v-model:checked="nullValue" :disabled="disabled">
      {{ kind === 'MULTISELECT' ? '包含空值' : '匹配空值' }}
    </a-checkbox>
  </div>
</template>
<style scoped>
.dashboard-input-value {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}
.dashboard-input-value :deep(.ant-select) {
  min-width: calc(var(--spacing-lg) * 12);
}
</style>
