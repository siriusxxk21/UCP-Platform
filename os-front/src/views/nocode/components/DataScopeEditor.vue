<script setup lang="ts">
import { computed } from 'vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { DataScope, ScopeCondition } from '@/types/nocode/data-scope'
import { emptyScope, scopeFields } from '@/nocode/data-scope'
import { isRelativeDate, relativeDateField, relativeDateOperator } from '@/nocode/relative-date'
import RelativeDateValue from './RelativeDateValue.vue'
const props = withDefaults(
  defineProps<{
    fields: ObjectField[]
    readonly?: boolean
    dynamic?: boolean
    simple?: boolean
    depth?: number
    choices?: (id: string) => { label: string; value: string }[]
    /** 结果会存下来的入口不给选相对日期：写明原因（相对日期置灰）；不传即可用。 */
    relativeBlocked?: string | null
  }>(),
  { depth: 0 }
)
const model = defineModel<DataScope>({ required: true })
defineSlots<{
  value?(props: { condition: ScopeCondition; multiple: boolean }): unknown
  afterValue?(props: { condition: ScopeCondition; multiple: boolean }): unknown
}>()
const fields = computed(() => scopeFields(props.fields).map(f => ({ label: f.name, value: f.id! })))
const field = (id: string) => props.fields.find(f => f.id === id)
const multi = (c: ScopeCondition) =>
  ['in', 'containsAny', 'containsAll'].includes(c.operator) || field(c.fieldId)?.type === FieldType.MULTI_SELECT
function choices(id: string) {
  return field(id)?.type === FieldType.BOOLEAN
    ? [
        { label: '是', value: true },
        { label: '否', value: false }
      ]
    : props.choices?.(id) || []
}
function operators(id: string) {
  const f = field(id)
  const items =
    f?.type === FieldType.MULTI_SELECT
      ? [
          { label: '包含任意', value: 'containsAny' },
          { label: '包含全部', value: 'containsAll' },
          { label: '完全等于', value: 'eq' }
        ]
      : [
          { label: '等于', value: 'eq' },
          { label: '属于任意一个', value: 'in' },
          { label: '不等于', value: 'neq' }
        ]
  if (
    f &&
    [
      FieldType.INTEGER,
      FieldType.DECIMAL,
      FieldType.MONEY,
      FieldType.PERCENT,
      FieldType.DATE,
      FieldType.DATETIME,
      FieldType.TIME
    ].some(t => t === f.type)
  )
    items.push(
      { label: '大于', value: 'gt' },
      { label: '大于等于', value: 'gte' },
      { label: '小于', value: 'lt' },
      { label: '小于等于', value: 'lte' }
    )
  return [...items, { label: '为空', value: 'isNull' }, { label: '不为空', value: 'notNull' }]
}
/** 换比较方式：多值清成空数组；相对日期在两个都能用它的比较方式之间切换时保留，其余清空。 */
function operatorChanged(c: ScopeCondition) {
  c.value = multi(c) ? [] : isRelativeDate(c.value) && relativeDateOperator(c.operator) ? c.value : null
}
const dateField = (id: string) => relativeDateField(field(id)?.type)
function changed(c: ScopeCondition) {
  c.operator = field(c.fieldId)?.type === FieldType.MULTI_SELECT ? 'containsAny' : 'eq'
  c.valueSource = 'CONSTANT'
  c.value = multi(c) ? [] : null
}
function sourceChanged(c: ScopeCondition) {
  c.value = null
  if (c.valueSource === 'CURRENT_DEPARTMENT_TREE')
    c.operator = field(c.fieldId)?.type === FieldType.MULTI_SELECT ? 'containsAny' : 'in'
}
function allOptions(c: ScopeCondition) {
  c.operator = field(c.fieldId)?.type === FieldType.MULTI_SELECT ? 'containsAny' : 'in'
  c.value = choices(c.fieldId).map(v => v.value)
}
</script>
<template>
  <div class="data-scope-editor">
    <a-select
      v-if="!simple"
      v-model:value="model.logic"
      :disabled="readonly"
      :options="[
        { label: '全部满足（AND）', value: 'AND' },
        { label: '任一满足（OR）', value: 'OR' }
      ]"
      aria-label="范围条件关系"
    />
    <div v-for="(c, index) in model.conditions" :key="index" class="scope-condition">
      <div class="scope-inputs">
        <a-select
          v-model:value="c.fieldId"
          :disabled="readonly"
          :options="fields"
          show-search
          option-filter-prop="label"
          placeholder="选择字段"
          aria-label="范围字段"
          @change="changed(c)"
        />
        <a-select
          v-model:value="c.operator"
          :disabled="readonly"
          :options="operators(c.fieldId)"
          aria-label="范围匹配方式"
          @change="operatorChanged(c)"
        />
        <a-select
          v-if="dynamic"
          v-model:value="c.valueSource"
          :disabled="readonly"
          :options="[
            { label: '固定值', value: 'CONSTANT' },
            { label: '当前用户', value: 'CURRENT_USER' },
            { label: '本部门', value: 'CURRENT_DEPARTMENT' },
            { label: '本部门及下级', value: 'CURRENT_DEPARTMENT_TREE' }
          ]"
          placeholder="固定值"
          aria-label="范围值来源"
          @change="sourceChanged(c)"
        />
        <template
          v-if="(!c.valueSource || c.valueSource === 'CONSTANT') && !['isNull', 'notNull'].includes(c.operator)"
        >
          <slot name="value" :condition="c" :multiple="multi(c)">
            <a-select
              v-if="multi(c) || choices(c.fieldId).length"
              v-model:value="c.value"
              :disabled="readonly"
              :options="choices(c.fieldId)"
              :mode="multi(c) ? (choices(c.fieldId).length ? 'multiple' : 'tags') : undefined"
              placeholder="选择值；无候选时输入后回车"
              aria-label="范围值"
            />
            <RelativeDateValue
              v-else-if="dateField(c.fieldId)"
              v-model="c.value"
              :operator="c.operator"
              :disabled="readonly"
              :blocked-reason="relativeBlocked"
            >
              <a-input v-model:value="c.value" :disabled="readonly" placeholder="固定值" aria-label="范围值" />
            </RelativeDateValue>
            <a-input v-else v-model:value="c.value" :disabled="readonly" placeholder="固定值" aria-label="范围值" />
          </slot>
        </template>
        <slot name="afterValue" :condition="c" :multiple="multi(c)" />
        <a-button :disabled="readonly" @click="model.conditions.splice(index, 1)">移除</a-button>
      </div>
      <a-button
        v-if="!dynamic && choices(c.fieldId).length"
        type="link"
        size="small"
        :disabled="readonly"
        @click="allOptions(c)"
      >
        使用全部字典项
      </a-button>
    </div>
    <div v-for="(group, index) in model.groups" :key="index" class="scope-group">
      <DataScopeEditor
        v-model="model.groups[index]!"
        :fields="props.fields"
        :choices="props.choices"
        :dynamic="dynamic"
        :readonly="readonly"
        :relative-blocked="relativeBlocked"
        :depth="depth + 1"
      >
        <template v-if="$slots.value" #value="slotProps">
          <slot name="value" v-bind="slotProps" />
        </template>
        <template v-if="$slots.afterValue" #afterValue="slotProps">
          <slot name="afterValue" v-bind="slotProps" />
        </template>
      </DataScopeEditor>
      <a-button :disabled="readonly" @click="model.groups.splice(index, 1)">移除条件组</a-button>
    </div>
    <a-space>
      <a-button
        :disabled="readonly"
        @click="model.conditions.push({ fieldId: '', operator: 'eq', value: null, valueSource: 'CONSTANT' })"
      >
        添加条件
      </a-button>
      <a-button v-if="!simple && depth < 3" :disabled="readonly" @click="model.groups.push(emptyScope())">
        添加条件组
      </a-button>
    </a-space>
  </div>
</template>
<style scoped>
.data-scope-editor {
  display: grid;
  gap: 12px;
}
.scope-inputs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
}
.scope-inputs > .ant-select {
  flex: 1 1 150px;
  min-width: 0;
}
.scope-inputs > .ant-input {
  flex: 1 1 200px;
  min-width: 0;
}
.scope-inputs > .relative-date-value {
  flex: 1 1 260px;
}
.scope-inputs > :deep(.selection-field) {
  flex: 1 1 200px;
  min-width: 0;
}
.scope-group {
  padding: 12px;
  border-left: 2px solid var(--border-color, #d9d9d9);
}
</style>
