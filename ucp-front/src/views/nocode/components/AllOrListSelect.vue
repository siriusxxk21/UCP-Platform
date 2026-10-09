<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { Modal } from 'ant-design-vue'
import { ALL, isAll, staleItems } from '@/nocode/selection-all'
import GrantFieldSelect from './GrantFieldSelect.vue'

/**
 * 授权清单的「全部 / 清单」二选一。「全部」存成 ['*']，随上一层自动同步，框里不列单项；
 * 清单状态复用 GrantFieldSelect。已停用的项不显示、不在打开时改数据，用户下一次增删时才不再带上。
 */
interface Option {
  label: string
  value: string
  disabled?: boolean
  title?: string
}
const props = withDefaults(
  defineProps<{
    /** 旧数据可能没有这个清单，按空清单处理。 */
    modelValue?: string[]
    label: string
    options: Option[]
    upperName: string
    readonly?: boolean
    placeholder?: string
    allowAll?: boolean
  }>(),
  { allowAll: true }
)
const emit = defineEmits<{ 'update:modelValue': [value: string[]]; change: [] }>()
const expanded = ref(false)
const listId = useId()
const all = computed(() => isAll(props.modelValue))
const included = computed(() => props.options.filter(option => !option.disabled))
const stale = computed(() =>
  staleItems(
    props.modelValue,
    props.options.map(option => option.value)
  )
)
/** 交给清单控件的值：滤掉已停用的项，任何情况下不显示裸 id。 */
const listed = computed(() => (all.value ? [] : (props.modelValue || []).filter(id => !stale.value.includes(id))))
const listOptions = computed(() =>
  props.options.map(option => ({
    ...option,
    title: option.title ?? (option.disabled ? '上一层未允许' : undefined)
  }))
)
function commit(value: string[]) {
  emit('update:modelValue', value)
  emit('change')
}
function toggle(checked: boolean) {
  if (props.readonly) return
  if (!checked) {
    commit(included.value.map(option => option.value))
    return
  }
  const added = included.value.filter(option => !listed.value.includes(option.value))
  if (!added.length) {
    commit([ALL])
    return
  }
  Modal.confirm({
    title: '改为“全部”？',
    content: `将增加 ${added.length} 项：${added
      .slice(0, 8)
      .map(option => option.label)
      .join('、')}${added.length > 8 ? '等' : ''}。以后新增的也会自动包含。`,
    okText: '改为全部',
    cancelText: '保持现状',
    onOk: () => commit([ALL])
  })
}
</script>

<template>
  <div class="all-or-list-select">
    <div v-if="allowAll && !readonly" class="all-or-list-toggle">
      <a-checkbox :checked="all" @change="toggle($event.target.checked)">全部</a-checkbox>
      <span v-if="all" class="all-or-list-hint">随{{ upperName }}自动同步</span>
    </div>
    <template v-if="all">
      <span v-if="readonly" class="all-or-list-text">全部（当前 {{ included.length }} 项）</span>
      <a-select
        v-else
        :aria-label="label"
        mode="multiple"
        :disabled="true"
        :value="[ALL]"
        :options="[{ value: ALL, label: `全部 · 当前 ${included.length} 项` }]"
      />
      <div class="all-or-list-summary">
        <a-button
          type="link"
          size="small"
          :disabled="false"
          :aria-expanded="expanded"
          :aria-controls="listId"
          @click="expanded = !expanded"
        >
          {{ expanded ? '收起清单' : '查看当前包含哪些' }}
        </a-button>
      </div>
      <ul v-if="expanded" :id="listId" class="all-or-list-items" :aria-label="label" tabindex="0">
        <li v-for="option in included" :key="option.value">{{ option.label }}</li>
      </ul>
    </template>
    <template v-else>
      <GrantFieldSelect
        :model-value="listed"
        :label="label"
        :options="listOptions"
        :readonly="readonly"
        :placeholder="placeholder"
        hide-select-all
        @update:model-value="emit('update:modelValue', $event)"
        @change="emit('change')"
      />
      <p v-if="stale.length && !readonly" class="all-or-list-hint">
        另有 {{ stale.length }} 个已停用的项，保存时自动移除
      </p>
    </template>
  </div>
</template>

<style scoped>
.all-or-list-select,
.all-or-list-select .ant-select {
  width: 100%;
  min-width: 0;
}
.all-or-list-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
}
.all-or-list-hint,
.all-or-list-text {
  color: var(--text-secondary);
  font-size: 12px;
}
.all-or-list-text {
  font-size: 14px;
}
p.all-or-list-hint {
  margin: 0;
}
.all-or-list-summary {
  display: flex;
  align-items: center;
  min-height: 28px;
}
.all-or-list-items {
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
.all-or-list-items li {
  min-height: 28px;
  line-height: 28px;
  overflow-wrap: anywhere;
}
</style>
