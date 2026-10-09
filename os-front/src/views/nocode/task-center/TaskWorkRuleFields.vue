<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TaskWorkRule, TaskWorkRuleMode } from '@/types/nocode/task-work-entries'
import type { TaskBinding } from '@/types/nocode/task-center'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { taskWorkRuleFields, taskWorkRuleOptions, taskWorkRulePreview } from '@/nocode/task-work-rule'
import TaskBindingPicker from './TaskBindingPicker.vue'
import TaskWorkDurationInput from './TaskWorkDurationInput.vue'

const model = defineModel<TaskWorkRule | null | undefined>()
const props = withDefaults(
  defineProps<{
    readonly?: boolean
    compact?: boolean
    binding?: TaskBinding | null
    fields?: ObjectField[]
    fieldOptions?: Record<string, FieldOptions>
    label?: string
  }>(),
  { readonly: false, compact: false, label: '标准工时' }
)
const loadedFields = ref<ObjectField[]>([])
const loadedOptions = ref<Record<string, FieldOptions>>({})
const fields = computed(() => props.fields || loadedFields.value)
const options = computed(() => props.fieldOptions || loadedOptions.value)
const availableFields = computed(() => taskWorkRuleFields(fields.value, model.value?.mode || 'RECORD_ONCE'))
const conditionField = computed(() => fields.value.find(field => field.id === model.value?.conditionFieldId))
const conditionOptions = computed(() =>
  (options.value[model.value?.conditionFieldId || '']?.options || []).map(option => ({
    value: option.code,
    label: option.label,
    disabled: option.disabled
  }))
)
const preview = computed(() => taskWorkRulePreview(model.value, fields.value, conditionOptions.value))
const compactModeOptions = [{ value: 'NONE', label: '不计工时' }, ...taskWorkRuleOptions]
const durationLabel = computed(() =>
  model.value?.mode === 'QUANTITY'
    ? '每单位数量计'
    : model.value?.mode === 'CONDITION'
      ? '每条达标记录计'
      : '每条记录计'
)
function update(patch: Partial<TaskWorkRule>) {
  if (props.readonly || !model.value) return
  model.value = { ...model.value, ...patch }
}
function changeMode(mode: TaskWorkRuleMode | 'NONE') {
  if (props.readonly) return
  if (mode === 'NONE') {
    model.value = null
    return
  }
  if (!taskWorkRuleOptions.some(option => option.value === mode)) return
  const next: TaskWorkRule = { ...(model.value || { minutes: 0 }), mode }
  delete next.quantityFieldId
  delete next.conditionFieldId
  delete next.conditionValue
  // 切换单位后原预计数量不能被静默解释成另一种工作量。
  if (next.mode !== model.value?.mode) delete next.plannedQuantity
  model.value = next
}
</script>
<template>
  <div v-if="model || compact" class="work-rule" :class="{ 'work-rule--compact': compact }">
    <TaskBindingPicker
      v-if="binding"
      class="work-rule__metadata"
      :model-value="binding"
      fields-only
      @rule-fields="loadedFields = $event"
      @field-options="loadedOptions = $event"
    />
    <div class="work-rule__controls">
      <div class="work-rule__method">
        <a-select
          v-if="compact"
          class="work-rule__mode-select"
          :value="model?.mode || 'NONE'"
          :disabled="readonly"
          :aria-label="`${label}计算方式`"
          :options="compactModeOptions"
          @update:value="changeMode"
        />
        <a-form-item v-else-if="model" label="怎么算工时">
          <a-radio-group
            :value="model.mode"
            class="work-rule__modes"
            aria-label="工时计算方式"
            :disabled="readonly"
            @update:value="changeMode"
          >
            <a-radio
              v-for="option in taskWorkRuleOptions"
              :key="option.value"
              :value="option.value"
              class="work-rule__mode"
              :class="{ 'work-rule__mode--selected': model.mode === option.value }"
            >
              <strong>{{ option.label }}</strong>
              <span>{{ option.description }}</span>
            </a-radio>
          </a-radio-group>
        </a-form-item>
        <a-select
          v-if="model?.mode === 'QUANTITY'"
          class="work-rule__quantity-field"
          :value="model.quantityFieldId"
          :disabled="readonly"
          aria-label="计工时的数量字段"
          :options="availableFields.map(field => ({ value: field.id, label: field.name }))"
          placeholder="选择数量字段"
          @update:value="update({ quantityFieldId: $event })"
        />
        <div v-if="model?.mode === 'CONDITION'" class="work-rule__condition">
          <a-select
            :value="model.conditionFieldId"
            :disabled="readonly"
            aria-label="计工时的条件字段"
            :options="availableFields.map(field => ({ value: field.id, label: field.name }))"
            placeholder="选择条件字段"
            @update:value="update({ conditionFieldId: $event, conditionValue: null })"
          />
          <span>等于</span>
          <a-select
            v-if="conditionField?.type === 'BOOLEAN'"
            :value="model.conditionValue"
            :disabled="readonly"
            aria-label="计工时的条件值"
            :options="[
              { value: true, label: '是' },
              { value: false, label: '否' }
            ]"
            @update:value="update({ conditionValue: $event })"
          />
          <a-select
            v-else-if="conditionField?.type === 'SELECT' && conditionOptions.length"
            :value="model.conditionValue"
            :disabled="readonly"
            aria-label="计工时的条件值"
            :options="conditionOptions"
            placeholder="选择条件值"
            @update:value="update({ conditionValue: $event })"
          />
          <a-input-number
            v-else-if="conditionField?.type === 'INTEGER' || conditionField?.type === 'DECIMAL'"
            :value="model.conditionValue"
            :disabled="readonly"
            aria-label="计工时的条件值"
            :precision="conditionField.type === 'INTEGER' ? 0 : undefined"
            @update:value="update({ conditionValue: $event })"
          />
          <a-date-picker
            v-else-if="conditionField?.type === 'DATE' || conditionField?.type === 'DATETIME'"
            :value="model.conditionValue"
            :disabled="readonly"
            aria-label="计工时的条件值"
            :show-time="conditionField.type === 'DATETIME'"
            :value-format="conditionField.type === 'DATE' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
            @update:value="update({ conditionValue: $event })"
          />
          <a-input
            v-else
            :value="model.conditionValue"
            :disabled="readonly"
            aria-label="计工时的条件值"
            placeholder="条件值"
            @update:value="update({ conditionValue: $event })"
          />
        </div>
      </div>
      <div v-if="model" class="work-rule__duration">
        <span v-if="!compact">{{ durationLabel }}</span>
        <TaskWorkDurationInput
          :model-value="model.minutes"
          :label="label"
          :disabled="readonly"
          @update:model-value="update({ minutes: $event || 0 })"
        />
      </div>
    </div>
    <div v-if="!compact" class="work-rule__preview" role="status" aria-live="polite" aria-label="工时计算预览">
      {{ preview }}
    </div>
    <details v-if="!compact && model" class="entry-config__notes">
      <summary>计量说明</summary>
      <p>标准工时用于折算工作量，不是实际耗时，也不代表任务已完成。</p>
      <p>同一节点、办理项、员工和记录不重复累计保存次数；不同员工分别计量，多人编辑同一记录可能分别计入。</p>
      <p v-if="model.mode === 'QUANTITY'">数量增加或减少时，相应调整已计工时。</p>
      <p v-else-if="model.mode === 'CONDITION'">首次满足条件后计入；之后条件变化不扣回，再次满足也不重复累计。</p>
      <p>只查看、仅关联、没有实际修改或保存失败不计；需要审批时，通过并保存后计入。删除数据会扣除对应计量。</p>
    </details>
  </div>
</template>
<style scoped>
.work-rule,
.work-rule__controls,
.work-rule__method,
.work-rule__duration {
  display: grid;
  gap: var(--spacing-sm);
  min-width: 0;
}
.work-rule__controls {
  gap: var(--spacing-md);
}
.work-rule--compact .work-rule__controls {
  grid-template-columns: minmax(180px, 200px) minmax(220px, 240px);
  align-items: start;
}
/* 表格内不让 Grid 拉伸 Select 的外框，否则箭头与 32px 的输入框不在同一行。 */
.work-rule--compact .work-rule__method {
  display: contents;
}
.work-rule--compact .work-rule__mode-select {
  grid-area: 1 / 1;
}
.work-rule--compact .work-rule__duration {
  grid-area: 1 / 2;
}
.work-rule--compact .work-rule__quantity-field {
  grid-column: 1;
}
.work-rule--compact .work-rule__condition {
  grid-column: 1 / -1;
}
.work-rule__metadata {
  display: none;
}
.work-rule--compact {
  text-align: left;
}
.work-rule--compact .work-rule__condition {
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
}
.work-rule__duration > span,
.work-rule__preview,
.entry-config__notes {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.work-rule__condition {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: var(--spacing-xs);
}
.work-rule__condition > span {
  color: var(--text-secondary);
}
.work-rule :deep(.ant-select),
.work-rule :deep(.ant-input-number),
.work-rule :deep(.ant-picker) {
  width: 100%;
  min-width: 0;
}
.work-rule :deep(.ant-form-item) {
  margin-bottom: 0;
}
.work-rule__modes {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--spacing-sm);
}
.work-rule__mode {
  display: flex;
  align-items: flex-start;
  margin: 0;
  padding: var(--spacing-md);
  border: 1px solid var(--color-border-secondary, #e5e7eb);
  border-radius: var(--border-radius, 6px);
}
.work-rule__mode--selected {
  border-color: var(--brand);
  background: var(--brand-light);
}
.work-rule__mode strong,
.work-rule__mode strong + span {
  display: block;
}
.work-rule__mode strong + span {
  margin-top: var(--spacing-xs);
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.work-rule__mode:focus-within {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.entry-config__notes summary {
  cursor: pointer;
  width: fit-content;
}
.entry-config__notes p {
  margin: var(--spacing-sm) 0 0;
  line-height: 1.7;
}
@media (max-width: 600px) {
  .work-rule__modes {
    grid-template-columns: 1fr;
  }
}
</style>
