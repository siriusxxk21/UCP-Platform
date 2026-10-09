<script setup lang="ts">
import { computed } from 'vue'
import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'
import { fieldRelation } from '@/nocode/business-fields'
import { selectionSource } from '@/nocode/selection'
import { dateTimeValueFormat } from '@/nocode/record-form'
import SelectionField from './SelectionField.vue'

const props = defineProps<{
  definition: PublishedDefinition
  fieldId: string
  applicationId?: string
  disabled?: boolean
  multiple?: boolean
  placeholder?: string
}>()
const value = defineModel<any>()
const field = computed(() => props.definition.fields.find(f => f.id === props.fieldId))
const options = computed(() => props.definition.fieldOptions[props.fieldId])
const remote = computed(() => {
  if (!field.value) return false
  const source = selectionSource(field.value, options.value)
  return !!fieldRelation(props.definition.relations, props.fieldId) || (!!source && source.kind !== 'LOCAL_OPTIONS')
})
const choices = computed(() =>
  field.value?.type === FieldType.BOOLEAN
    ? [
        { label: '是', value: 'true' },
        { label: '否', value: 'false' }
      ]
    : (options.value?.options || []).filter(o => !o.disabled).map(o => ({ label: o.label, value: o.code }))
)
const selectionValue = computed({
  get: () =>
    field.value?.type === FieldType.BOOLEAN
      ? Array.isArray(value.value)
        ? value.value.map(String)
        : value.value == null
          ? undefined
          : String(value.value)
      : value.value,
  set: next => {
    value.value =
      field.value?.type === FieldType.BOOLEAN
        ? Array.isArray(next)
          ? next.map(item => item === 'true')
          : next == null
            ? null
            : next === 'true'
        : next
  }
})
const numeric = computed(() =>
  [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(t => t === field.value?.type)
)
</script>
<template>
  <SelectionField
    v-if="remote && applicationId"
    v-model="value"
    :application-id="applicationId"
    :object-id="definition.objectId"
    :field-id="fieldId"
    :multiple="multiple"
    :disabled="disabled"
    :placeholder="placeholder || '请选择记录或选项'"
    preview
  />
  <a-alert v-else-if="remote" type="warning" message="请先保存应用，再选择关联记录" />
  <a-select
    v-else-if="multiple || field?.type === FieldType.SELECT || field?.type === FieldType.BOOLEAN"
    v-model:value="selectionValue"
    :disabled="disabled"
    :options="choices"
    :mode="multiple ? (choices.length ? 'multiple' : 'tags') : undefined"
    :placeholder="placeholder || '请选择值'"
    show-search
    option-filter-prop="label"
    allow-clear
    class="value-control"
  />
  <a-input-number
    v-else-if="numeric"
    v-model:value="value"
    :disabled="disabled"
    :placeholder="placeholder || '请输入数值'"
    string-mode
    class="value-control"
  />
  <a-date-picker
    v-else-if="field?.type === FieldType.DATE || field?.type === FieldType.DATETIME"
    v-model:value="value"
    :disabled="disabled"
    :show-time="field.type === FieldType.DATETIME"
    :value-format="field.type === FieldType.DATE ? 'YYYY-MM-DD' : dateTimeValueFormat(options)"
    :placeholder="placeholder || '请选择日期'"
    class="value-control"
  />
  <a-time-picker
    v-else-if="field?.type === FieldType.TIME"
    v-model:value="value"
    :disabled="disabled"
    value-format="HH:mm:ss"
    class="value-control"
  />
  <a-input
    v-else
    v-model:value="value"
    :disabled="disabled || !field"
    :placeholder="placeholder || '留空表示清空字段'"
    allow-clear
  />
</template>
<style scoped>
.value-control {
  width: 100%;
}
</style>
