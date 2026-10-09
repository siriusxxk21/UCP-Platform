<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ObjectDetail } from '@/types/nocode/data-center'
const props = defineProps<{ modelValue?: string | null; details: ObjectDetail[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [string | null]; 'result-type': [string] }>()
const detailCode = ref(''),
  aggregate = ref('count'),
  fieldCode = ref(''),
  advanced = ref(false)
const choices = [
  { value: 'count', label: '统计明细条数' },
  { value: 'sum', label: '求和' },
  { value: 'avg', label: '平均值' },
  { value: 'min', label: '最小值' },
  { value: 'max', label: '最大值' }
]
const details = computed(() => props.details.filter(item => item.state === 'ACTIVE'))
const current = computed(() => details.value.find(item => item.code === detailCode.value))
const fields = computed(
  () =>
    current.value?.fields.filter(
      field =>
        (['INTEGER', 'DECIMAL', 'MONEY', 'PERCENT'].includes(field.type) ||
          (field.type === 'FORMULA' &&
            ['INTEGER', 'DECIMAL', 'MONEY'].includes(current.value?.fieldOptions[field.key]?.resultType || ''))) &&
        current.value?.fieldOptions[field.key]?.state !== 'INACTIVE'
    ) || []
)
let emitted: string | null | undefined
watch(
  () => props.modelValue,
  value => {
    if (value === emitted) {
      emitted = undefined
      return
    }
    const match = value?.match(/^(count|sum|avg|min|max)\(([a-z][a-z0-9_]*)(?:\.([a-z][a-z0-9_]*))?\)$/)
    advanced.value = !!value && !match
    aggregate.value = match?.[1] || 'count'
    detailCode.value = match?.[2] || ''
    fieldCode.value = match?.[3] || ''
  },
  { immediate: true }
)
const error = computed(() =>
  advanced.value
    ? '当前表达式无法转为选择式配置，请使用支持的明细汇总规则'
    : !current.value
      ? '请选择要汇总的内部明细'
      : aggregate.value !== 'count' && !fields.value.some(field => field.code === fieldCode.value)
        ? '请选择明细中的数值字段'
        : ''
)
function update() {
  if (props.disabled) return
  emitted = error.value
    ? null
    : `${aggregate.value}(${detailCode.value}${aggregate.value === 'count' ? '' : '.' + fieldCode.value})`
  emit('update:modelValue', emitted)
  const source = fields.value.find(field => field.code === fieldCode.value)
  const sourceType = source?.type === 'FORMULA' ? current.value?.fieldOptions[source.key]?.resultType : source?.type
  emit('result-type', aggregate.value === 'count' ? 'INTEGER' : sourceType === 'MONEY' ? 'MONEY' : 'DECIMAL')
}
function rebuild() {
  advanced.value = false
  detailCode.value = ''
  fieldCode.value = ''
  update()
}
function changeDetail(value: string) {
  detailCode.value = value
  fieldCode.value = ''
  update()
}
defineExpose({ validate: () => error.value })
</script>
<template>
  <div class="summary-editor">
    <template v-if="advanced">
      <a-textarea :value="modelValue" aria-label="原汇总表达式" disabled />
      <a-alert type="warning" show-icon :message="error" />
      <a-button :disabled="disabled" @click="rebuild">重新选择汇总规则</a-button>
    </template>
    <template v-else>
      <a-select
        :value="detailCode || undefined"
        aria-label="汇总内部明细"
        placeholder="选择内部明细"
        :options="details.map(item => ({ value: item.code, label: item.name }))"
        :disabled="disabled"
        @change="changeDetail"
      />
      <a-select
        v-model:value="aggregate"
        aria-label="明细汇总方式"
        :options="choices"
        :disabled="disabled"
        @change="update"
      />
      <a-select
        v-if="aggregate !== 'count'"
        v-model:value="fieldCode"
        aria-label="明细数值字段"
        placeholder="选择要汇总的数值字段"
        :options="fields.map(field => ({ value: field.code, label: field.name }))"
        :disabled="disabled"
        @change="update"
      />
      <p v-if="!details.length">请先在“内部明细”中添加明细表，再配置汇总。</p>
      <p v-else-if="!error">
        {{ current?.name }} · {{ choices.find(item => item.value === aggregate)?.label
        }}{{ aggregate === 'count' ? '' : ' · ' + fields.find(field => field.code === fieldCode)?.name }}
      </p>
    </template>
  </div>
</template>
<style scoped>
.summary-editor {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--border-color, #e6e6ed);
  border-radius: 8px;
}
.summary-editor p {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 12px;
}
</style>
