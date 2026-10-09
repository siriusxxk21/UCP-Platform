<script setup lang="ts">
import { computed, ref } from 'vue'
import SelectionField from './SelectionField.vue'
import RelativeDateValue from '../../components/RelativeDateValue.vue'
import { selectionSource } from '@/nocode/selection'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { FieldType } from '@/types/nocode/enums'
import { arrayFieldTypes, dateTimeValueFormat } from '@/nocode/record-form'
import { textQueryTypes } from '@/nocode/runtime-list'
const props = defineProps<{
  applicationId?: string
  objectId?: string
  reference?: boolean
  field: ObjectField
  options?: FieldOptions
  choices: { label: string; value: string }[]
  allowedValues?: string[]
  /** 日期 / 日期时间字段可选相对日期（今天、本周、本月……，按当天换算）；只由列表常用查询与视图「默认查询」传入。 */
  relativeDates?: boolean
}>()
const value = defineModel<any>()
const emit = defineEmits<{ search: [] }>()
const hierarchy = computed(() =>
  ['ORGANIZATION', 'DEPARTMENT'].includes(selectionSource(props.field, props.options)?.directory || '')
)
const includeDescendants = ref(false)
const multiple = computed(() => arrayFieldTypes.has(props.field.type) || (props.allowedValues?.length || 0) > 1)
const selected = computed({
  get: () => {
    const current = value.value?.includeDescendants ? value.value.value : value.value
    // 固定多值范围会把单值字段的查询控件切为多选，兼容已保存的单个默认查询值。
    return multiple.value
      ? current == null || current === ''
        ? []
        : Array.isArray(current)
          ? current
          : [current]
      : current
  },
  set: v => {
    value.value =
      includeDescendants.value && v != null && (!Array.isArray(v) || v.length)
        ? { value: v, includeDescendants: true }
        : v
  }
})
function toggle(checked: boolean) {
  const v = selected.value
  includeDescendants.value = checked
  selected.value = v
}
</script>
<template>
  <div
    v-if="
      applicationId &&
      objectId &&
      (reference ||
        (selectionSource(field, options)?.kind && selectionSource(field, options)?.kind !== 'LOCAL_OPTIONS'))
    "
  >
    <SelectionField
      v-model="selected"
      :application-id="applicationId!"
      :object-id="objectId!"
      :field-id="field.id!"
      :allowed-values="allowedValues"
      :multiple="multiple"
      compact
      :placeholder="reference && arrayFieldTypes.has(field.type) ? '包含任一选中记录' : '全部'"
    />
    <a-checkbox v-if="hierarchy" :checked="includeDescendants" @change="toggle(!!$event.target.checked)">
      包含下级
    </a-checkbox>
  </div>
  <a-select
    style="width: 100%"
    v-else-if="field.type === FieldType.BOOLEAN"
    v-model:value="value"
    allow-clear
    placeholder="全部"
    :aria-label="field.name"
    :options="[
      { label: '是', value: 'true' },
      { label: '否', value: 'false' }
    ]"
  />
  <RelativeDateValue
    v-else-if="relativeDates && (field.type === FieldType.DATE || field.type === FieldType.DATETIME)"
    v-model="value"
    operator="eq"
    :field-type="field.type"
    :value-format="field.type === FieldType.DATE ? 'YYYY-MM-DD' : dateTimeValueFormat(options)"
    :aria-label="field.name"
  />
  <a-date-picker
    v-else-if="field.type === FieldType.DATE || field.type === FieldType.DATETIME"
    v-model:value="value"
    :aria-label="field.name"
    :show-time="field.type === FieldType.DATETIME"
    :value-format="field.type === FieldType.DATE ? 'YYYY-MM-DD' : dateTimeValueFormat(options)"
  />
  <a-time-picker
    v-else-if="field.type === FieldType.TIME"
    v-model:value="value"
    :aria-label="field.name"
    value-format="HH:mm:ss"
  />
  <a-select
    style="width: 100%"
    v-else-if="
      allowedValues != null || choices.length || field.type === FieldType.SELECT || arrayFieldTypes.has(field.type)
    "
    v-model:value="selected"
    :aria-label="field.name"
    :options="choices"
    allow-clear
    placeholder="全部"
    :mode="multiple ? (allowedValues != null || choices.length ? 'multiple' : 'tags') : undefined"
    max-tag-count="responsive"
    show-search
    option-filter-prop="label"
  />
  <a-input
    v-else
    v-model:value="value"
    :aria-label="field.name"
    allow-clear
    :placeholder="textQueryTypes.has(field.type) ? '包含' + field.name : '请输入' + field.name"
    @press-enter="emit('search')"
  />
</template>
