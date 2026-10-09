<script setup lang="ts">
import { PlusOutlined, DeleteOutlined, GroupOutlined } from '@ant-design/icons-vue'
import type { DynamicSearchField, DynamicConditionItem, ConditionGroup, DynamicSearchOperator } from './types'
import { OPERATOR_LABELS, TYPE_DEFAULT_OPERATORS } from './types'

// 递归组件需要注册 name 才能在 <template> 中自引用
defineOptions({ name: 'OsConditionGroupRenderer' })

// ===== Props & Emits =====

const props = defineProps<{
  group: ConditionGroup
  fields: DynamicSearchField[]
  fieldOptions: { label: string; value: string }[]
  depth?: number
}>()

const emit = defineEmits<{
  (e: 'addCondition', group: ConditionGroup): void
  (e: 'addSubGroup', group: ConditionGroup): void
  (e: 'removeItem', group: ConditionGroup | null, itemId: string): void
  (e: 'fieldChange', condition: DynamicConditionItem): void
  (e: 'conditionChange'): void
  (e: 'logicChange'): void
}>()

// ===== 工具方法 =====

function getOperatorOptions(fieldKey: string) {
  const field = props.fields.find(f => f.field === fieldKey)
  if (!field) return [{ label: '等于', value: 'eq' }]
  const operators: DynamicSearchOperator[] =
    field.operators ?? TYPE_DEFAULT_OPERATORS[field.type] ?? (['eq'] as DynamicSearchOperator[])
  return operators.map(op => ({ label: OPERATOR_LABELS[op] || op, value: op }))
}

function getFieldType(fieldKey: string): DynamicSearchField['type'] {
  return props.fields.find(f => f.field === fieldKey)?.type ?? 'text'
}

function isSelectType(fieldKey: string) {
  return getFieldType(fieldKey) === 'select'
}
function isNumberType(fieldKey: string) {
  return getFieldType(fieldKey) === 'number'
}
function isDateRangeType(fieldKey: string) {
  const t = getFieldType(fieldKey)
  return t === 'dateRange' || t === 'datetimeRange'
}
function isDateType(fieldKey: string) {
  return getFieldType(fieldKey) === 'date'
}
function getSelectOptions(fieldKey: string) {
  return props.fields.find(f => f.field === fieldKey)?.options ?? []
}

function changeOperator(item: DynamicConditionItem) {
  item.value = null
  emit('conditionChange')
}

function isConditionGroup(item: any): item is ConditionGroup {
  return item.type === 'group'
}
function isConditionItem(item: any): item is DynamicConditionItem {
  return item.type === 'condition'
}
</script>

<template>
  <div
    class="la-condition-group"
    :style="{
      marginLeft: (depth ?? 0) > 0 ? '20px' : '0',
      borderLeft: (depth ?? 0) > 0 ? '2px solid #d9d9d9' : 'none',
      paddingLeft: (depth ?? 0) > 0 ? '12px' : '0'
    }"
  >
    <!-- 分组头（子组显示逻辑切换和删除） -->
    <div v-if="(depth ?? 0) > 0" class="la-condition-group__header">
      <a-radio-group v-model:value="group.logic" size="small" button-style="solid" @change="emit('logicChange')">
        <a-radio-button value="AND">AND</a-radio-button>
        <a-radio-button value="OR">OR</a-radio-button>
      </a-radio-group>
      <a-button type="text" size="small" danger @click="emit('removeItem', null, group.id)">
        <DeleteOutlined />
        删除分组
      </a-button>
    </div>

    <!-- 子项列表 -->
    <div class="la-condition-group__items">
      <template v-for="(item, index) in group.items" :key="item.id">
        <!-- 逻辑连接词 -->
        <div v-if="index > 0" class="la-condition-group__connector">
          <a-tag :color="group.logic === 'AND' ? 'blue' : 'orange'" size="small">
            {{ group.logic }}
          </a-tag>
        </div>

        <!-- 条件项 -->
        <div v-if="isConditionItem(item)" class="la-dynamic-search__row">
          <!-- 字段选择 -->
          <a-select
            v-model:value="(item as any).field"
            :options="fieldOptions"
            placeholder="选择字段"
            style="width: 130px"
            size="small"
            @change="emit('fieldChange', item)"
          />

          <!-- 运算符选择 -->
          <a-select
            v-model:value="(item as any).operator"
            :options="getOperatorOptions((item as any).field)"
            placeholder="运算符"
            style="width: 110px"
            size="small"
            @change="changeOperator(item)"
          />

          <span v-if="item.operator === 'isNull' || item.operator === 'notNull'">无需填写值</span>
          <component
            v-else-if="fields.find(f => f.field === item.field)?.valueComponent"
            :is="fields.find(f => f.field === item.field)?.valueComponent"
            v-model="item.value"
            v-bind="fields.find(f => f.field === item.field)?.valueProps"
            :multiple="item.operator === 'in'"
            :operator="item.operator"
            style="flex: 1; min-width: 120px"
            @update:model-value="emit('conditionChange')"
          />
          <!-- select 类型 -->
          <a-select
            v-else-if="isSelectType((item as any).field)"
            v-model:value="(item as any).value"
            :options="getSelectOptions((item as any).field)"
            :mode="item.operator === 'in' ? 'multiple' : undefined"
            max-tag-count="responsive"
            show-search
            option-filter-prop="label"
            placeholder="请选择"
            style="flex: 1; min-width: 120px"
            size="small"
            allow-clear
            @change="emit('conditionChange')"
          />

          <!-- 数字类型 -->
          <a-input-number
            v-else-if="isNumberType((item as any).field)"
            v-model:value="(item as any).value"
            placeholder="请输入数值"
            :string-mode="fields.find(f => f.field === item.field)?.stringMode"
            style="flex: 1; min-width: 120px"
            size="small"
            @change="emit('conditionChange')"
          />

          <!-- 日期范围类型 -->
          <a-range-picker
            v-else-if="isDateRangeType((item as any).field)"
            v-model:value="(item as any).value"
            :show-time="getFieldType((item as any).field) === 'datetimeRange'"
            :value-format="fields.find(f => f.field === item.field)?.valueFormat"
            style="flex: 1; min-width: 200px"
            size="small"
            @change="emit('conditionChange')"
          />

          <!-- 日期类型 -->
          <a-date-picker
            v-else-if="isDateType((item as any).field)"
            v-model:value="(item as any).value"
            placeholder="请选择日期"
            :value-format="fields.find(f => f.field === item.field)?.valueFormat"
            style="flex: 1; min-width: 130px"
            size="small"
            @change="emit('conditionChange')"
          />

          <!-- 默认文本类型 -->
          <a-input
            v-else
            v-model:value="(item as any).value"
            placeholder="请输入值"
            style="flex: 1; min-width: 120px"
            size="small"
            @change="emit('conditionChange')"
          />

          <!-- 删除条件 -->
          <a-button type="text" size="small" danger @click="emit('removeItem', group, item.id)">
            <DeleteOutlined />
          </a-button>
        </div>

        <!-- 嵌套条件组（递归） -->
        <OsConditionGroupRenderer
          v-else-if="isConditionGroup(item)"
          :group="item"
          :fields="fields"
          :field-options="fieldOptions"
          :depth="(depth ?? 0) + 1"
          @add-condition="(g: ConditionGroup) => emit('addCondition', g)"
          @add-sub-group="(g: ConditionGroup) => emit('addSubGroup', g)"
          @remove-item="(g: ConditionGroup | null, id: string) => emit('removeItem', g !== null ? g : group, id)"
          @field-change="(c: DynamicConditionItem) => emit('fieldChange', c)"
          @condition-change="emit('conditionChange')"
          @logic-change="emit('logicChange')"
        />
      </template>
    </div>

    <!-- 分组底部操作 -->
    <div class="la-condition-group__actions">
      <a-button type="dashed" @click="emit('addCondition', group)">
        <PlusOutlined />
        条件
      </a-button>
      <a-button type="dashed" @click="emit('addSubGroup', group)">
        <GroupOutlined />
        分组
      </a-button>
    </div>
  </div>
</template>

<style scoped lang="less">
.la-condition-group {
  margin-bottom: 4px;
}

.la-condition-group__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  gap: 8px;
}

.la-condition-group__items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.la-condition-group__connector {
  display: flex;
  align-items: center;
  padding: 2px 0;
}

.la-dynamic-search__row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.la-condition-group__actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}
</style>
