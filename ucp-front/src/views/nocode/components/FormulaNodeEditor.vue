<script setup lang="ts">
import { computed } from 'vue'
import { emptyFormula, formulaOperations, operationFormula, type FormulaNode } from '@/nocode/formula-builder'
import type { ObjectField } from '@/types/nocode/object'
import { dateArgumentLabel } from '@/nocode/formula-dates'
const props = withDefaults(
  defineProps<{ modelValue: FormulaNode; fields: ObjectField[]; disabled?: boolean; depth?: number; label?: string }>(),
  { depth: 0, label: '计算方式' }
)
const emit = defineEmits<{ 'update:modelValue': [FormulaNode] }>()
const mode = computed(() =>
  props.modelValue.kind === 'operation' ? props.modelValue.operation : props.modelValue.kind
)
const choices = computed(() => [
  { value: 'field', label: '选择字段' },
  { value: 'number', label: '固定数字' },
  { value: 'text', label: '固定文字' },
  { value: 'boolean', label: '是 / 否' },
  { value: 'null', label: '空值' },
  ...(props.depth < 4 ? formulaOperations : []).map(({ value, label }) => ({ value, label }))
])
function changeMode(value: string) {
  if (props.disabled) return
  const node = props.modelValue
  if (value === 'field' || value === 'number' || value === 'text' || value === 'null' || value === 'boolean')
    emit('update:modelValue', { kind: value, value: value === 'boolean' ? 'true' : '' })
  else if (
    node.kind === 'operation' &&
    ['+', '-', '*', '/', '||'].includes(node.operation) &&
    ['+', '-', '*', '/', '||'].includes(value)
  )
    emit('update:modelValue', { ...node, operation: value })
  else emit('update:modelValue', operationFormula(value, node))
}
function changeValue(value: string) {
  if (!props.disabled && props.modelValue.kind !== 'operation')
    emit('update:modelValue', { ...props.modelValue, value })
}
function changeArgument(index: number, value: FormulaNode) {
  if (!props.disabled && props.modelValue.kind === 'operation')
    emit('update:modelValue', {
      ...props.modelValue,
      args: props.modelValue.args.map((arg, i) => (i === index ? value : arg))
    })
}
function addArgument() {
  if (!props.disabled && props.modelValue.kind === 'operation')
    emit('update:modelValue', { ...props.modelValue, args: [...props.modelValue.args, emptyFormula()] })
}
function removeArgument(index: number) {
  if (!props.disabled && props.modelValue.kind === 'operation')
    emit('update:modelValue', { ...props.modelValue, args: props.modelValue.args.filter((_, i) => i !== index) })
}
function argumentLabel(index: number) {
  const dated = dateArgumentLabel(mode.value, index)
  if (dated) return dated
  if (mode.value === 'if')
    return ['如果（判断条件）', '那么（满足条件）', '否则（不满足或条件为空）'][index] || '计算项'
  if (['and', 'or', 'not'].includes(mode.value)) return `条件 ${index + 1}`
  if (mode.value === 'round') return index === 0 ? '需要处理的数值' : '保留位数'
  if (mode.value === 'coalesce') return index === 0 ? '优先取值' : `备用值 ${index}`
  return props.modelValue.kind === 'operation' && props.modelValue.args.length === 1
    ? '需要处理的内容'
    : `第 ${index + 1} 项`
}
</script>
<template>
  <div class="formula-node" :class="{ nested: depth > 0 }">
    <div class="node-heading">
      <span class="node-label">{{ label }}</span>
      <a-select :value="mode" :aria-label="label" :options="choices" :disabled="disabled" @change="changeMode" />
    </div>
    <template v-if="modelValue.kind !== 'operation'">
      <a-select
        v-if="modelValue.kind === 'field'"
        :value="modelValue.value || undefined"
        :aria-label="label + '字段'"
        placeholder="按名称搜索并选择字段"
        show-search
        option-filter-prop="label"
        :disabled="disabled"
        :options="fields.map(field => ({ value: field.code, label: field.name }))"
        @change="changeValue"
      />
      <a-input-number
        v-else-if="modelValue.kind === 'number'"
        :value="modelValue.value || undefined"
        string-mode
        :aria-label="label + '数字'"
        placeholder="输入数字，例如 100 或 0.13"
        :disabled="disabled"
        style="width: 100%"
        @update:value="changeValue($event == null ? '' : String($event))"
      />
      <a-input
        v-else-if="modelValue.kind === 'text'"
        :value="modelValue.value"
        :aria-label="label + '文字'"
        placeholder="输入要拼接或补齐的文字"
        :disabled="disabled"
        :maxlength="1000"
        @update:value="changeValue"
      />
      <a-select
        v-else-if="modelValue.kind === 'boolean'"
        :value="modelValue.value"
        :disabled="disabled"
        :options="[
          { value: 'true', label: '是' },
          { value: 'false', label: '否' }
        ]"
        @change="changeValue"
      />
      <span v-else class="node-label">返回空值</span>
    </template>
    <div v-else class="node-arguments">
      <div v-for="(arg, index) in modelValue.args" :key="index" class="node-argument">
        <div v-if="mode === 'round' && index === 1 && arg.kind === 'number'" class="round-precision">
          <span>保留小数位数</span>
          <a-input-number
            :value="arg.value || undefined"
            string-mode
            :min="-10"
            :max="10"
            :precision="0"
            :disabled="disabled"
            aria-label="保留小数位数"
            @update:value="changeArgument(index, { kind: 'number', value: $event == null ? '' : String($event) })"
          />
          <small>例如 2：12.345 → 12.35；0 表示取整。</small>
        </div>
        <FormulaNodeEditor
          v-else
          :model-value="arg"
          :fields="fields"
          :disabled="disabled"
          :depth="depth + 1"
          :label="argumentLabel(index)"
          @update:model-value="changeArgument(index, $event)"
        />
        <a-button
          v-if="['coalesce', 'and', 'or'].includes(mode) && modelValue.args.length > 2"
          size="small"
          :disabled="disabled"
          @click="removeArgument(index)"
        >
          移除此项
        </a-button>
      </div>
      <a-button
        v-if="['coalesce', 'and', 'or'].includes(mode) && modelValue.args.length < 8"
        size="small"
        :disabled="disabled"
        @click="addArgument"
      >
        {{ mode === 'coalesce' ? '添加备用值' : '添加条件' }}
      </a-button>
      <a-button
        v-if="mode === 'round' && modelValue.args.length === 1"
        size="small"
        :disabled="disabled"
        @click="addArgument"
      >
        设置保留位数
      </a-button>
    </div>
  </div>
</template>
<style scoped>
.formula-node {
  display: grid;
  gap: 10px;
  min-width: 0;
}
.nested {
  border-left: 2px solid var(--border-color, #e8e8ed);
  padding-left: 12px;
  grid-template-columns: minmax(160px, 1fr) minmax(180px, 1.4fr);
}
.node-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.node-heading .ant-select {
  flex: 1;
  min-width: 150px;
}
.node-label {
  font-size: 12px;
  color: var(--text-secondary, #666);
}
.node-arguments {
  display: grid;
  gap: 14px;
  grid-column: 1 / -1;
}
.node-argument {
  min-width: 0;
}
.round-precision {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background: var(--bg-secondary, #f7f8fc);
  border-radius: 6px;
}
.round-precision small {
  color: var(--text-secondary);
}
.formula-node > .ant-select {
  width: 100%;
}
@media (max-width: 600px) {
  .nested {
    padding-left: 8px;
    grid-template-columns: minmax(0, 1fr);
  }
  .node-heading .ant-select {
    min-width: 0;
    width: 100%;
    flex-basis: 100%;
  }
}
</style>
