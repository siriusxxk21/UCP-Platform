<script setup lang="ts">
import { computed } from 'vue'
import { FieldType } from '@/types/nocode/enums'
import {
  COUNTED_CODES,
  MAX_RELATIVE_DAYS,
  RELATIVE_DATE_LABELS,
  RELATIVE_DATE_OPTIONS,
  RELATIVE_DATE_QUICK,
  datePickerPresets,
  isQuickPicked,
  rangePickerPresets,
  isRelativeDate,
  relativeDateError,
  relativeDateFromOption,
  relativeDateOperator,
  relativeDateOption,
  relativeDateValue
} from '@/nocode/relative-date'

/**
 * 日期 / 日期时间条件的取值：「具体日期」或「相对日期」（今天、本周、过去 N 天……）。
 * 具体日期沿用调用方原来的取值控件（默认插槽）；没有插槽时（底座条件搜索的自定义取值控件）用日期选择器，「在区间内」用区间选择器。
 * 相对日期存 { relative, n? }，由服务端每次执行时按当天换算。blockedReason 有值时（结果会存下来的入口）相对日期置灰并写明原因；
 * 比较方式不能与相对日期组合（属于任意一个等）时只剩具体日期。
 */
const props = defineProps<{
  operator?: string | null
  disabled?: boolean
  blockedReason?: string | null
  fieldType?: string | null
  valueFormat?: string
  multiple?: boolean
  /** 能用相对日期的比较方式；缺省为全部可组合的比较方式（含「在范围内」）。 */
  relativeOperators?: readonly string[]
}>()
const model = defineModel<unknown>()
const relative = computed(() => isRelativeDate(model.value))
const usable = computed(
  () =>
    !props.multiple &&
    (props.relativeOperators
      ? props.relativeOperators.includes(props.operator ?? '')
      : relativeDateOperator(props.operator))
)
const mode = computed(() => (relative.value ? 'RELATIVE' : 'CONCRETE'))
const modeOptions = computed(() => [
  { value: 'CONCRETE', label: '具体日期' },
  { value: 'RELATIVE', label: '相对日期', disabled: !!props.blockedReason, title: props.blockedReason || undefined }
])
/** 快捷按钮（今天 / 本周 / 本月）：在具体日期模式下也直接显示，一点即换成该相对日期。 */
const quick = computed(() => usable.value && !props.blockedReason && !props.disabled)
function pick(code: (typeof RELATIVE_DATE_QUICK)[number]) {
  if (quick.value) model.value = relativeDateValue(code)
}
function changeMode(next: unknown) {
  if (props.disabled) return
  if (next === 'RELATIVE' && !props.blockedReason) model.value = relativeDateValue('TODAY')
  else if (next === 'CONCRETE') model.value = props.operator === 'between' ? [] : null
}
const option = computed(() => (isRelativeDate(model.value) ? relativeDateOption(model.value) : undefined))
const counted = computed(() => isRelativeDate(model.value) && COUNTED_CODES.includes(model.value.relative))
function changeOption(next: unknown) {
  model.value = relativeDateFromOption(String(next), model.value)
}
function changeDays(next: unknown) {
  if (!isRelativeDate(model.value)) return
  const n = typeof next === 'number' ? next : Number(next)
  model.value = { relative: model.value.relative, n: Number.isFinite(n) ? n : undefined }
}
const problem = computed(() => {
  if (!relative.value) return ''
  if (props.blockedReason) return props.blockedReason
  if (!usable.value) return '这种比较方式不能使用相对日期，请改选「具体日期」'
  return relativeDateError(model.value) ?? ''
})
/** 内置区间选择器只认两格字符串；其它形状（空、旧的半填值）显示为空，不让选择器报错。 */
const rangeValue = computed(() =>
  Array.isArray(model.value) && model.value.length === 2 && model.value.every(v => typeof v === 'string' && v)
    ? (model.value as [string, string])
    : undefined
)
const datetime = computed(() => props.fieldType === FieldType.DATETIME)
const format = computed(() => props.valueFormat || (datetime.value ? 'YYYY-MM-DDTHH:mm:ss' : 'YYYY-MM-DD'))
</script>
<template>
  <div class="relative-date-value" :data-relative-mode="mode">
    <a-radio-group
      v-if="usable || relative"
      :value="mode"
      :options="modeOptions"
      option-type="button"
      size="small"
      :disabled="disabled"
      aria-label="日期取值方式"
      @update:value="changeMode"
    />
    <span v-if="quick" class="relative-date-quick" role="group" aria-label="常用相对日期">
      <a-button
        v-for="code in RELATIVE_DATE_QUICK"
        :key="code"
        size="small"
        :type="isQuickPicked(model, code) ? 'primary' : 'default'"
        :data-quick="code"
        @click="pick(code)"
      >
        {{ RELATIVE_DATE_LABELS[code] }}
      </a-button>
    </span>
    <template v-if="relative">
      <a-select
        :value="option"
        :options="RELATIVE_DATE_OPTIONS"
        :disabled="disabled"
        class="relative-date-select"
        aria-label="相对日期"
        @update:value="changeOption"
      />
      <a-input-number
        v-if="counted"
        :value="(model as { n?: number }).n"
        :min="1"
        :max="MAX_RELATIVE_DAYS"
        :precision="0"
        :disabled="disabled"
        addon-after="天"
        aria-label="天数"
        class="relative-date-days"
        @update:value="changeDays"
      />
    </template>
    <slot v-else>
      <a-range-picker
        :presets="rangePickerPresets(undefined, datetime)"
        v-if="operator === 'between'"
        :value="rangeValue"
        :show-time="datetime"
        :value-format="format"
        :disabled="disabled"
        class="relative-date-concrete"
        @update:value="model = $event"
      />
      <a-date-picker
        :presets="datetime ? undefined : datePickerPresets()"
        v-else
        :value="typeof model === 'string' && model ? model : undefined"
        :show-time="datetime"
        :value-format="format"
        :disabled="disabled"
        placeholder="请选择日期"
        class="relative-date-concrete"
        @update:value="model = $event"
      />
    </slot>
    <p v-if="problem" class="relative-date-problem" role="alert">{{ problem }}</p>
  </div>
</template>
<style scoped>
.relative-date-value {
  display: flex;
  flex: 1 1 260px;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.relative-date-quick {
  display: inline-flex;
  gap: 4px;
}
.relative-date-select {
  flex: 1 1 160px;
  min-width: 140px;
}
.relative-date-days {
  width: 120px;
}
.relative-date-concrete {
  flex: 1 1 160px;
}
.relative-date-value > :deep(.ant-input),
.relative-date-value > :deep(.ant-select) {
  flex: 1 1 160px;
  min-width: 0;
}
.relative-date-problem {
  flex-basis: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--ant-color-error, #ff4d4f);
}
</style>
