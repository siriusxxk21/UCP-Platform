<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import { parseDateStr, toDateStr } from '@/utils/dateUtils'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import MemberSelect from '@/components/MemberSelect.vue'

/** 通用字段配置（解耦 workItem 模块） */
export interface FieldConfig {
  key: string
  label: string
  type: 'input' | 'textarea' | 'select' | 'date' | 'datetime' | 'number' | 'switch'
  placeholder?: string
  mode?: 'multiple' | 'tags' // select 多选模式
  options?: (
    string | { value: string; label: string; name?: string; username?: string; avatar?: string; color?: string }
  )[]
  /** 标记为只读自动派生字段 */
  autoDerived?: boolean
  /** 字段标签旁的提示信息 */
  tooltip?: string
  /** 是否必填（标签前显示红色星号） */
  required?: boolean
}

interface Props {
  field: FieldConfig
  getValue: (key: string) => any
  setValue: (key: string, value: any) => void
  /** 锁定只读模式（用于自动派生字段） */
  locked?: boolean
}

const props = defineProps<Props>()

/** 标准化 select 选项，兼容 string[] 和 {value,label}[] 两种格式 */
const resolvedSelectOptions = computed<
  { value: string; label: string; name?: string; username?: string; avatar?: string; color?: string }[]
>(() => {
  const opts = props.field.options
  if (!opts || opts.length === 0) return []
  if (typeof opts[0] === 'string') {
    return (opts as string[]).map(s => ({ value: s, label: s }))
  }
  return opts as { value: string; label: string; name?: string; username?: string; avatar?: string; color?: string }[]
})

const isMemberSelect = computed(() =>
  resolvedSelectOptions.value.some(option => !!option.name || !!option.username || !!option.avatar || !!option.color)
)
</script>

<template>
  <!-- input -->
  <a-form-item v-if="field.type === 'input'" class="field-item field-item-col">
    <template #label>
      <span class="field-label">{{ field.label }}</span>
    </template>
    <a-input
      :placeholder="field.placeholder"
      :value="props.getValue(field.key)"
      class="field-input"
      @update:value="props.setValue(field.key, $event)"
    />
  </a-form-item>

  <!-- textarea (full width) -->
  <a-form-item v-else-if="field.type === 'textarea'" class="field-item field-item-full">
    <template #label>
      <span class="field-label">{{ field.label }}</span>
    </template>
    <a-textarea
      :value="props.getValue(field.key)"
      @update:value="props.setValue(field.key, $event)"
      :placeholder="field.placeholder"
      :auto-size="{ minRows: 2, maxRows: 6 }"
      class="field-textarea"
    />
  </a-form-item>

  <!-- select -->
  <a-form-item v-else-if="field.type === 'select' && !props.locked" class="field-item field-item-col">
    <template #label>
      <span class="field-label">{{ field.label }}</span>
    </template>
    <MemberSelect
      v-if="isMemberSelect"
      :value="
        field.mode === 'multiple' || field.mode === 'tags' ? props.getValue(field.key) || [] : props.getValue(field.key)
      "
      @update:value="props.setValue(field.key, $event)"
      :options="resolvedSelectOptions"
      :placeholder="field.placeholder || '请选择'"
      :mode="field.mode || undefined"
      class="field-select"
    />
    <a-select
      v-else
      :value="
        field.mode === 'multiple' || field.mode === 'tags' ? props.getValue(field.key) || [] : props.getValue(field.key)
      "
      @update:value="props.setValue(field.key, $event)"
      :options="resolvedSelectOptions"
      :placeholder="field.placeholder || '请选择'"
      :mode="field.mode || undefined"
      class="field-select"
      allow-clear
      show-search
      option-filter-prop="label"
      :max-tag-count="3"
    />
  </a-form-item>

  <!-- select (locked: 自动派生，只读显示) -->
  <a-form-item v-else-if="field.type === 'select' && props.locked" class="field-item field-item-col">
    <template #label>
      <span class="field-label">
        {{ field.label }}
        <span class="auto-fill-tag">（已自动填充）</span>
      </span>
    </template>
    <div class="locked-field">
      {{
        resolvedSelectOptions.length > 0
          ? resolvedSelectOptions.find(o => o.value === getValue(field.key))?.label || getValue(field.key) || '—'
          : getValue(field.key) || '—'
      }}
    </div>
  </a-form-item>

  <!-- date -->
  <a-form-item v-else-if="field.type === 'date'" class="field-item field-item-col" :required="field.required">
    <template #label>
      <span class="field-label">{{ field.label }}</span>
    </template>
    <a-date-picker
      :value="parseDateStr(props.getValue(field.key))"
      @update:value="props.setValue(field.key, $event ? toDateStr($event) : '')"
      placeholder="选择日期"
      style="width: 100%"
      class="field-input"
    />
  </a-form-item>

  <!-- datetime -->
  <a-form-item v-else-if="field.type === 'datetime'" class="field-item field-item-col">
    <template #label>
      <span class="field-label">{{ field.label }}</span>
    </template>
    <a-date-picker
      show-time
      :value="parseDateStr(props.getValue(field.key))"
      @update:value="props.setValue(field.key, $event ? dayjs($event).format('YYYY-MM-DD HH:mm:ss') : '')"
      placeholder="选择时间"
      style="width: 100%"
      class="field-input"
    />
  </a-form-item>

  <!-- number -->
  <a-form-item v-else-if="field.type === 'number'" class="field-item field-item-col" :required="field.required">
    <template #label>
      <span class="field-label">
        {{ field.label }}
        <a-tooltip v-if="field.tooltip" placement="top" overlay-class-name="field-tooltip-overlay">
          <template #title>
            <div style="white-space: pre-line; max-width: 320px; font-size: 13px; line-height: 1.6">
              {{ field.tooltip }}
            </div>
          </template>
          <span class="field-tooltip-icon"><QuestionCircleOutlined /></span>
        </a-tooltip>
      </span>
    </template>
    <a-input-number
      :placeholder="field.placeholder"
      :value="props.getValue(field.key)"
      class="field-input"
      style="width: 100%"
      @update:value="props.setValue(field.key, $event)"
    />
  </a-form-item>

  <!-- switch -->
  <a-form-item v-else-if="field.type === 'switch'" class="field-item field-item-col field-item-switch">
    <template #label>
      <span class="field-label">{{ field.label }}</span>
    </template>
    <a-switch :checked="props.getValue(field.key)" @update:checked="props.setValue(field.key, $event)" />
  </a-form-item>
</template>

<style scoped>
.locked-field {
  display: flex;
  align-items: center;
  height: 40px;
  padding: 0 12px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  border-radius: 6px;
  color: #065f46;
  font-size: 14px;
  font-weight: 500;
}
.auto-fill-tag {
  font-size: 12px;
  color: #059669;
  font-weight: 400;
  margin-left: 4px;
}
.field-tooltip-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-left: 5px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: #6366f1;
  background: rgba(99, 102, 241, 0.08);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
  vertical-align: -1px;
}
.field-tooltip-icon:hover {
  color: #fff;
  background: #6366f1;
}
</style>
