<script setup lang="ts">
import { computed } from 'vue'
import { validEffectiveWorkMinutes } from '@/nocode/task-work-duration'

const props = withDefaults(defineProps<{ modelValue?: number | null; disabled?: boolean; label?: string }>(), {
  modelValue: null,
  disabled: false,
  label: '有效工作时长'
})
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()
const hours = computed(() => (props.modelValue == null ? null : Math.floor(props.modelValue / 60)))
const minutes = computed(() => (props.modelValue == null ? null : props.modelValue % 60))

function update(part: 'hours' | 'minutes', value: number | string | null) {
  if (props.disabled) return
  const amount = value == null || value === '' ? 0 : Number(value)
  if (!Number.isInteger(amount) || amount < 0 || amount > (part === 'hours' ? 9999 : 59)) return
  const total = part === 'hours' ? amount * 60 + (minutes.value || 0) : (hours.value || 0) * 60 + amount
  if (validEffectiveWorkMinutes(total)) emit('update:modelValue', total || null)
}
</script>

<template>
  <div class="task-work-duration" role="group" :aria-label="label">
    <a-input-number
      :value="hours"
      :disabled="disabled"
      :min="0"
      :max="9999"
      :precision="0"
      placeholder="0"
      addon-after="小时"
      :aria-label="`${label}（小时）`"
      @update:value="update('hours', $event)"
    />
    <a-input-number
      :value="minutes"
      :disabled="disabled"
      :min="0"
      :max="59"
      :precision="0"
      placeholder="0"
      addon-after="分钟"
      :aria-label="`${label}（分钟）`"
      @update:value="update('minutes', $event)"
    />
  </div>
</template>

<style scoped>
.task-work-duration {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--spacing-sm);
  min-width: 0;
}
.task-work-duration :deep(.ant-input-number-group-wrapper),
.task-work-duration :deep(.ant-input-number) {
  width: 100%;
  min-width: 0;
}
</style>
