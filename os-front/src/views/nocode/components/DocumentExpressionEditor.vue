<script setup lang="ts">
import { computed } from 'vue'
import type { ObjectField } from '@/types/nocode/object'
import type { ObjectDetail } from '@/types/nocode/data-center'
import type { DocumentExpression, DocumentOperator } from '@/types/nocode/document-policy'
const expression = defineModel<DocumentExpression>({ required: true })
const props = defineProps<{
  fields: ObjectField[]
  details: ObjectDetail[]
  disabled?: boolean
  depth?: number
  allowAggregates?: boolean
}>()
const operations: Array<{ value: DocumentOperator; label: string }> = [
  { value: 'VALUE', label: '固定值' },
  { value: 'FIELD', label: '字段值' },
  { value: 'SUM', label: '明细求和' },
  { value: 'COUNT', label: '明细行数' },
  { value: 'UNIQUE', label: '明细内不重复' },
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' },
  { value: 'GT', label: '大于' },
  { value: 'GE', label: '大于等于' },
  { value: 'LT', label: '小于' },
  { value: 'LE', label: '小于等于' },
  { value: 'AND', label: '全部满足' },
  { value: 'OR', label: '任一满足' },
  { value: 'NOT', label: '不满足' },
  { value: 'EMPTY', label: '为空' }
]
const key = (detail: ObjectDetail) => detail.id || `detail:${detail.code}`
const aggregate = computed(() => ['SUM', 'COUNT', 'UNIQUE'].includes(expression.value.op))
const selectedFields = computed(() =>
  aggregate.value ? props.details.find(d => key(d) === expression.value.detailId)?.fields || [] : props.fields
)
const literalKind = computed(() => (expression.value.value === null ? 'null' : typeof expression.value.value))
function changeLiteral(kind: string) {
  expression.value.value = kind === 'number' ? 0 : kind === 'boolean' ? true : kind === 'null' ? null : ''
}
function changeOperator(op: DocumentOperator) {
  const count = ['AND', 'OR', 'EQ', 'NE', 'GT', 'GE', 'LT', 'LE'].includes(op)
    ? 2
    : ['NOT', 'EMPTY'].includes(op)
      ? 1
      : 0
  expression.value = {
    op,
    fieldId: null,
    detailId: null,
    value: op === 'VALUE' ? true : null,
    args: Array.from({ length: count }, () => ({ op: 'VALUE', value: true, args: [] }))
  }
}
</script>
<template>
  <div class="expression-editor">
    <a-space wrap>
      <a-select
        :value="expression.op"
        :options="operations.filter(o => allowAggregates !== false || !['SUM', 'COUNT', 'UNIQUE'].includes(o.value))"
        :disabled="disabled"
        aria-label="条件类型"
        class="operator"
        @change="changeOperator"
      />
      <template v-if="expression.op === 'VALUE'">
        <a-select
          :value="literalKind"
          :disabled="disabled"
          aria-label="固定值类型"
          style="width: 94px"
          :options="[
            { value: 'string', label: '文本' },
            { value: 'number', label: '数字' },
            { value: 'boolean', label: '是否' },
            { value: 'null', label: '空值' }
          ]"
          @change="changeLiteral"
        />
        <a-input-number
          v-if="literalKind === 'number'"
          v-model:value="expression.value"
          :disabled="disabled"
          aria-label="固定数字"
        />
        <a-switch
          v-else-if="literalKind === 'boolean'"
          v-model:checked="expression.value"
          :disabled="disabled"
          checked-children="是"
          un-checked-children="否"
        />
        <a-input
          v-else-if="literalKind === 'string'"
          v-model:value="expression.value"
          :disabled="disabled"
          :maxlength="500"
          aria-label="固定文本"
        />
      </template>
      <a-select
        v-if="aggregate"
        v-model:value="expression.detailId"
        :disabled="disabled"
        placeholder="选择明细"
        style="min-width: 160px"
        :options="details.map(d => ({ value: key(d), label: d.name }))"
        @change="expression.fieldId = null"
      />
      <a-select
        v-if="expression.op === 'FIELD' || (aggregate && expression.op !== 'COUNT')"
        v-model:value="expression.fieldId"
        :disabled="disabled"
        placeholder="选择字段"
        style="min-width: 160px"
        show-search
        option-filter-prop="label"
        :options="selectedFields.map(f => ({ value: f.id || f.key, label: `${f.name} · ${f.code}` }))"
      />
    </a-space>
    <div v-if="expression.args.length && (depth || 0) < 16" class="expression-args">
      <div v-for="(_, index) in expression.args" :key="index" class="expression-arg">
        <DocumentExpressionEditor
          v-model="expression.args[index]!"
          :fields="fields"
          :details="details"
          :disabled="disabled"
          :depth="(depth || 0) + 1"
          :allow-aggregates="allowAggregates"
        />
        <a-button
          v-if="['AND', 'OR'].includes(expression.op) && expression.args.length > 2"
          type="text"
          danger
          :disabled="disabled"
          @click="expression.args.splice(index, 1)"
        >
          移除条件
        </a-button>
      </div>
      <a-button
        v-if="['AND', 'OR'].includes(expression.op) && expression.args.length < 20"
        size="small"
        :disabled="disabled"
        @click="expression.args.push({ op: 'VALUE', value: true, args: [] })"
      >
        添加条件
      </a-button>
    </div>
  </div>
</template>
<style scoped>
.expression-editor {
  min-width: 0;
}
.operator {
  width: 142px;
}
.expression-args {
  margin: 12px 0 0 12px;
  padding-left: 16px;
  border-left: 2px solid var(--os-border-color, #e8e8e8);
  display: grid;
  gap: 12px;
}
.expression-arg {
  min-width: 0;
}
</style>
