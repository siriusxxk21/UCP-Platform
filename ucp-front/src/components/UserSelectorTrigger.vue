<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import type { User } from '@/api/system/user'
import { getUsersByIds } from '@/api/system/user'
import UserSelector from '@/components/UserSelector/index.vue'

interface SelectorItem {
  id: string | number
  name: string
  code?: string
  path?: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: Array<string | number>
    selectorType: 'user' | 'dept' | 'role' | 'org'
    mode?: 'single' | 'multiple'
    title?: string
    placeholder?: string
    maxCount?: number
    maxTagCount?: number
    width?: number | string
    disabled?: boolean
  }>(),
  {
    modelValue: () => [],
    mode: 'multiple',
    title: '',
    placeholder: '请选择',
    maxCount: 0,
    maxTagCount: 3,
    width: 1120,
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: Array<string | number>): void
  (e: 'change', items: SelectorItem[]): void
}>()

const selectorVisible = ref(false)
const selectedIds = ref<Array<string | number>>([...props.modelValue])
const selectedItems = ref<SelectorItem[]>([])

const selectedUsers = computed<User[]>(() => {
  return selectedItems.value.map(
    item =>
      ({
        id: String(item.id),
        username: item.code || '',
        nickname: item.name
      }) as User
  )
})

// 显示的项目（限制数量）
const displayItems = computed(() => {
  return selectedItems.value.slice(0, props.maxTagCount)
})

// 监听外部值变化，同步到内部
watch(
  () => props.modelValue,
  val => {
    // 避免重复更新
    if (JSON.stringify(val) !== JSON.stringify(selectedIds.value)) {
      selectedIds.value = [...val]
      loadSelectedUsers()
    }
  },
  { deep: true }
)

// 打开选择器
function handleOpenSelector() {
  if (props.disabled) return
  if (props.selectorType !== 'user') {
    message.warning('当前选择器仅支持人员选择')
    return
  }
  selectorVisible.value = true
}

// 确认选择（来自 UserSelector 组件）
function handleConfirm(users: User[]) {
  const limitedUsers = props.maxCount > 0 ? users.slice(0, props.maxCount) : users
  if (props.maxCount > 0 && users.length > props.maxCount) message.warning(`最多只能选择 ${props.maxCount} 项`)
  const items = limitedUsers.map(normalizeUserItem)
  selectedItems.value = items
  const ids = items.map(item => item.id)
  // 只有值真正变化时才 emit
  if (JSON.stringify(ids) !== JSON.stringify(props.modelValue)) {
    emit('update:modelValue', ids)
  }
  emit('change', items)
}

// 移除项
function handleRemoveItem(item: SelectorItem) {
  selectedIds.value = selectedIds.value.filter(id => id !== item.id)
  selectedItems.value = selectedItems.value.filter(i => i.id !== item.id)
  emit('update:modelValue', selectedIds.value)
  emit('change', selectedItems.value)
}

function normalizeUserItem(user: User): SelectorItem {
  return {
    id: user.id,
    name: user.nickname || user.username || String(user.id),
    code: user.username,
    path: user.deptName
  }
}

async function loadSelectedUsers() {
  if (props.selectorType !== 'user' || !selectedIds.value.length) {
    selectedItems.value = []
    return
  }
  try {
    const users = await getUsersByIds(selectedIds.value.map(String))
    selectedItems.value = users.map(normalizeUserItem)
  } catch (error) {
    console.error('加载已选人员失败:', error)
  }
}

loadSelectedUsers()
</script>

<template>
  <div class="user-selector-trigger">
    <div class="trigger-tags" @click="handleOpenSelector">
      <template v-if="selectedItems.length > 0">
        <a-tag v-for="item in displayItems" :key="item.id" closable @close.stop="handleRemoveItem(item)">
          {{ item.name }}
        </a-tag>
        <a-tag v-if="selectedItems.length > maxTagCount" class="more-tag">
          +{{ selectedItems.length - maxTagCount }}
        </a-tag>
      </template>
      <span v-else class="placeholder">{{ placeholder }}</span>
    </div>
    <UserSelector
      v-model:visible="selectorVisible"
      :multiple="mode === 'multiple'"
      :selected-users="selectedUsers"
      :show-multiple-toggle="false"
      :title="title || '选择人员'"
      :width="width"
      @confirm="handleConfirm"
    />
  </div>
</template>

<style scoped>
.user-selector-trigger {
  width: 100%;
}

.trigger-tags {
  min-height: 32px;
  padding: 4px 11px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  transition: all 0.2s;
}

.trigger-tags:hover {
  border-color: var(--brand);
}

.placeholder {
  color: #bfbfbf;
  font-size: 14px;
}

.more-tag {
  background: #f0f0f0;
  border: none;
}
</style>
