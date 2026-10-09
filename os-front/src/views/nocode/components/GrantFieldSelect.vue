<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'

/** 权限范围保留完整已选清单；折叠只改变展示，不裁剪或扩大授权数据。 */
const props = defineProps<{
  label: string
  options: Array<{ label: string; value: string; disabled?: boolean }>
  readonly?: boolean
  placeholder?: string
  /** 外层已提供「全部」时不再显示「全选可用项」；默认显示。 */
  hideSelectAll?: boolean
}>()
const value = defineModel<string[]>({ default: () => [] })
const emit = defineEmits<{ change: [] }>()
const expanded = ref(false)
const listId = useId()
const selected = computed(() =>
  value.value.map(id => ({
    value: id,
    label: props.options.find(option => option.value === id)?.label || `不可用字段（${id}）`
  }))
)
function remove(id: string) {
  if (props.readonly) return
  value.value = value.value.filter(value => value !== id)
  emit('change')
}
function selectAvailable() {
  if (props.readonly) return
  value.value = [
    ...new Set([...value.value, ...props.options.filter(option => !option.disabled).map(option => option.value)])
  ]
  emit('change')
}
function clearSelected() {
  if (props.readonly) return
  value.value = []
  emit('change')
}
</script>

<template>
  <div class="grant-field-select" @keydown.esc.stop>
    <label v-if="!readonly" class="grant-selector-label" :for="`${listId}-input`">{{ label }}</label>
    <a-select
      v-if="!readonly"
      :id="`${listId}-input`"
      v-model:value="value"
      :aria-label="label"
      mode="multiple"
      max-tag-count="responsive"
      :max-tag-text-length="18"
      show-search
      option-filter-prop="label"
      allow-clear
      :options="options"
      :placeholder="placeholder || '搜索并选择字段'"
      @change="emit('change')"
    />
    <div class="grant-selection-summary">
      <span>已选 {{ value.length }} 项</span>
      <span v-if="!readonly" class="grant-bulk-actions">
        <a-button
          v-if="!hideSelectAll"
          type="link"
          size="small"
          :disabled="!options.some(option => !option.disabled)"
          @click="selectAvailable"
        >
          全选可用项
        </a-button>
        <a-button type="link" size="small" :disabled="!value.length" @click="clearSelected">清空</a-button>
      </span>
      <a-button
        v-if="!readonly && value.length"
        type="link"
        size="small"
        :aria-expanded="expanded"
        :aria-controls="listId"
        :aria-label="`${expanded ? '收起' : '展开'}${label}已选清单`"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起清单' : '查看全部' }}
      </a-button>
    </div>
    <ul
      v-if="(readonly || expanded) && selected.length"
      :id="listId"
      class="grant-selection-list"
      :aria-label="label"
      tabindex="0"
    >
      <li v-for="item in selected" :key="item.value">
        <span>{{ item.label }}</span>
        <a-button
          v-if="!readonly"
          type="text"
          size="small"
          :aria-label="`移除${item.label}`"
          @click="remove(item.value)"
        >
          <CloseOutlined />
        </a-button>
      </li>
    </ul>
    <span v-else-if="readonly" class="grant-selection-empty">未授权</span>
  </div>
</template>

<style scoped>
.grant-field-select,
.grant-field-select .ant-select {
  width: 100%;
  min-width: 0;
}
.grant-selector-label {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.grant-selection-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 28px;
  color: var(--text-secondary);
  font-size: 12px;
}
.grant-selection-list {
  max-height: 160px;
  overflow: auto;
  overscroll-behavior: contain;
  margin: 0;
  padding: 4px 8px;
  list-style: none;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-page);
}
.grant-selection-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 28px;
}
.grant-selection-list li > span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.grant-selection-list .ant-btn {
  flex: none;
}
.grant-selection-empty {
  color: var(--text-secondary);
}
</style>
