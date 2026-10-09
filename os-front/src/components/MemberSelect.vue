<script setup lang="ts">
import { computed } from 'vue'
import AssigneeAvatar from '@/components/AssigneeAvatar.vue'

type MemberOption = {
  value: string
  label?: string
  name?: string
  username?: string
  avatar?: string
  color?: string
}

interface Props {
  value?: string | string[]
  options?: MemberOption[]
  mode?: 'multiple' | 'tags'
  placeholder?: string
  allowClear?: boolean
  disabled?: boolean
  maxTagCount?: number
  /** 是否支持输入过滤搜索，默认 true */
  showSearch?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  value: undefined,
  options: () => [],
  mode: undefined,
  placeholder: '请选择',
  allowClear: true,
  disabled: false,
  maxTagCount: 3,
  showSearch: true
})

const emit = defineEmits<{
  'update:value': [value: string | string[] | undefined]
}>()

function formatLabel(option: MemberOption): string {
  const name = option.name || option.label || option.value
  return option.username ? `${name}（${option.username}）` : name
}

const normalizedOptions = computed(() =>
  (props.options || []).map(option => ({
    ...option,
    label: formatLabel(option)
  }))
)

/** 根据输入过滤选项：匹配名称、用户名、label */
function filterMemberOption(inputValue: string, option: any): boolean {
  const keyword = (inputValue || '').trim().toLowerCase()
  if (!keyword) return true
  const searchText = [
    option.label || '',
    option.name || '',
    option.username || '',
    option.value || ''
  ].join(' ').toLowerCase()
  return searchText.includes(keyword)
}

/** 下拉菜单渲染到 body 层级，避免被容器 overflow 裁剪，同时防止滚轮事件冒泡导致父级滚动 */
function getPopupContainer(): HTMLElement {
  return document.body
}
</script>

<template>
  <a-select
    :value="value"
    :mode="mode"
    :placeholder="placeholder"
    :allow-clear="allowClear"
    :disabled="disabled"
    :max-tag-count="maxTagCount"
    option-label-prop="label"
    :show-search="showSearch"
    :filter-option="showSearch ? filterMemberOption : undefined"
    :get-popup-container="getPopupContainer"
    @update:value="emit('update:value', $event)"
  >
    <a-select-option
      v-for="option in normalizedOptions"
      :key="option.value"
      :value="option.value"
      :label="option.label"
    >
      <div class="member-option">
        <AssigneeAvatar
          :name="option.name || option.label || option.value"
          :src="option.avatar"
          :color="option.color"
          :size="24"
          :show-name="false"
        />
        <div class="member-label">
          <span class="member-name">{{ option.name || option.label }}</span>
          <span v-if="option.username" class="member-username">（{{ option.username }}）</span>
        </div>
      </div>
    </a-select-option>
  </a-select>
</template>

<style scoped>
.member-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.member-label {
  min-width: 0;
  white-space: nowrap;
}

.member-name {
  color: #111827;
}

.member-username {
  color: #6b7280;
}
</style>
