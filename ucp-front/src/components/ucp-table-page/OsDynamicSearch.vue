<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { PlusOutlined, GroupOutlined, SearchOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { v4 as uuidv4 } from 'uuid'
import { message, Drawer, Modal } from 'ant-design-vue'
import type {
  DynamicSearchField,
  DynamicSearchOperator,
  DynamicConditionItem,
  ConditionGroup,
  ConditionItem,
  DynamicSearchCondition,
  SerializedConditionGroup,
  SerializedItem,
  SavedSearchCondition
} from './types'
import { isConditionGroup, isConditionItem, serializeConditions } from './types'
import OsConditionGroupRenderer from './OsConditionGroupRenderer.vue'

// ===== Props & Emits =====

interface Props {
  /** 可查询字段配置 */
  fields: DynamicSearchField[]
  /** v-model 控制弹窗显隐 */
  open?: boolean
  /** 编辑时回填的已有条件 */
  modelValue?: DynamicSearchCondition | null
  /** 已保存的常用查询条件列表 */
  savedConditions?: SavedSearchCondition[]
  /** 当前在表格上方激活的常用查询标签 id */
  activeTagId?: string | null
  /** 配置统计口径时，禁止静默丢弃未填写完整的条件。 */
  strict?: boolean
  /** 调用方未接入常用查询存储时隐藏保存选项，避免提供无效操作。 */
  allowSave?: boolean
  /** 任务宿主统一使用抽屉，其他调用保持历史弹窗。 */
  displayMode?: 'modal' | 'drawer'
}

const props = withDefaults(defineProps<Props>(), {
  open: false,
  fields: () => [],
  modelValue: null,
  savedConditions: () => [],
  activeTagId: null,
  allowSave: true
})

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:modelValue', value: DynamicSearchCondition | null): void
  /**
   * 确认查询
   * @param conditions 序列化后的条件
   * @param saveDescription 保存描述（用户勾选了"记住"时非空，父组件按名称匹配决定新建还是更新）
   */
  (e: 'confirm', conditions: DynamicSearchCondition | null, saveDescription: string): void
  (e: 'cancel'): void
  /** 在弹窗内删除某条常用查询 */
  (e: 'remove-saved', id: string): void
}>()

// ===== 内部状态（根条件组） =====

const rootGroup = ref<ConditionGroup>(createEmptyGroup())

// ===== 保存相关 =====

const saveChecked = ref(false)
const saveDescription = ref('')

// ===== 当前侧边栏激活的条件 id =====

const internalActiveSavedId = ref<string | null>(null)

// ===== 工具函数 =====

function createEmptyGroup(): ConditionGroup {
  return {
    id: uuidv4(),
    type: 'group',
    logic: 'AND',
    items: []
  }
}

function createEmptyCondition(): DynamicConditionItem {
  const firstField = props.fields[0]
  const operator = firstField ? ((firstField.operators ?? [])[0] ?? 'eq') : 'eq'
  return {
    id: uuidv4(),
    type: 'condition',
    field: firstField?.field ?? '',
    operator,
    value: null
  }
}

// ===== 递归操作 =====

function addCondition(group: ConditionGroup) {
  group.items.push(createEmptyCondition())
}

function addSubGroup(group: ConditionGroup) {
  group.items.push(createEmptyGroup())
}

function removeItem(group: ConditionGroup | null, itemId: string) {
  if (group) {
    group.items = group.items.filter(item => item.id !== itemId)
  } else {
    rootGroup.value.items = rootGroup.value.items.filter(item => item.id !== itemId)
  }
}

function onFieldChange(condition: DynamicConditionItem) {
  const field = props.fields.find(f => f.field === condition.field)
  const operators: DynamicSearchOperator[] = field?.operators ?? (['eq'] as DynamicSearchOperator[])
  condition.operator = (operators[0] ?? 'eq') as DynamicSearchOperator
  condition.value = null
}

function onConditionChange() {
  // 弹窗模式下不需要每次 change 都 emit
}

// ===== 防止内部变更触发 watch 循环 =====

let internalChange = false

// ===== 弹窗打开/关闭 =====

function handleOpen() {
  // 弹窗打开时，从 modelValue 还原条件
  if (props.modelValue) {
    rootGroup.value = fromSerialized(props.modelValue)
  } else {
    rootGroup.value = createEmptyGroup()
  }
  // 同步父组件的激活标签状态
  internalActiveSavedId.value = props.activeTagId ?? null
  if (props.activeTagId) {
    const activeSaved = props.savedConditions.find(s => s.id === props.activeTagId)
    if (activeSaved) {
      // 当前有激活标签：自动勾选「记住」并预填名称
      saveChecked.value = true
      saveDescription.value = activeSaved.description
    } else {
      saveChecked.value = false
      saveDescription.value = ''
    }
  } else {
    saveChecked.value = false
    saveDescription.value = ''
  }
}

function handleCancel() {
  // 取消时重置弹窗状态到上次确认的条件（或空），避免残留编辑中的脏数据
  if (props.modelValue) {
    rootGroup.value = fromSerialized(props.modelValue)
  } else {
    rootGroup.value = createEmptyGroup()
  }
  saveChecked.value = false
  saveDescription.value = ''
  emit('update:open', false)
  emit('cancel')
}

function handleConfirm() {
  const incomplete = (group: ConditionGroup): boolean =>
    group.items.some(item => {
      if (isConditionGroup(item)) return !item.items.length || incomplete(item)
      if (!item.field || !item.operator || !props.fields.some(f => f.field === item.field)) return true
      if (item.operator === 'isNull' || item.operator === 'notNull') return false
      if (item.operator === 'between')
        return !Array.isArray(item.value) || item.value.length !== 2 || item.value.some(v => !hasValue(v))
      if (Array.isArray(item.value)) return !item.value.length || item.value.some(v => !hasValue(v))
      return !hasValue(item.value)
    })
  if (props.strict && incomplete(rootGroup.value)) {
    message.warning('请补齐条件字段和值，或移除未完成的条件及空条件组')
    return
  }
  const validGroup = filterValidItems(rootGroup.value)
  const serialized = validGroup.items.length === 0 ? null : serializeConditions(validGroup)

  internalChange = true
  emit('update:modelValue', serialized)
  nextTick(() => {
    internalChange = false
  })

  // 父组件通过标签名称匹配决定新建还是更新，此处只传 saveDescription
  emit('confirm', serialized, saveChecked.value ? saveDescription.value.trim() : '')
  emit('update:open', false)
}

function handleReset() {
  rootGroup.value = createEmptyGroup()
  saveChecked.value = false
  saveDescription.value = ''
  internalActiveSavedId.value = null
}

// ===== 常用查询侧边栏 =====

/** 点击侧边栏中的常用查询，将条件加载到编辑器 */
function loadSavedCondition(saved: SavedSearchCondition) {
  rootGroup.value = fromSerialized(saved.conditions)
  internalActiveSavedId.value = saved.id
  // 自动勾选「记住」并预填该标签名称，方便用户直接修改后更新
  saveChecked.value = true
  saveDescription.value = saved.description
}

/** 在弹窗内删除某条常用查询 */
function handleRemoveSaved(id: string) {
  // 如果删除的是当前编辑器正在显示的条件，同步清空编辑器
  if (internalActiveSavedId.value === id) {
    internalActiveSavedId.value = null
    rootGroup.value = createEmptyGroup()
    saveChecked.value = false
    saveDescription.value = ''
  }
  emit('remove-saved', id)
}

// ===== 过滤有效条件 =====

function filterValidItems(group: ConditionGroup): ConditionGroup {
  const validItems: ConditionItem[] = []
  for (const item of group.items) {
    if (isConditionGroup(item)) {
      const filtered = filterValidItems(item)
      if (filtered.items.length > 0) {
        validItems.push(filtered)
      }
    } else if (isConditionItem(item)) {
      if (
        item.field &&
        item.operator &&
        (item.operator === 'isNull' || item.operator === 'notNull' || hasValue(item.value))
      ) {
        validItems.push(item)
      }
    }
  }
  return { ...group, items: validItems }
}

function hasValue(val: any): boolean {
  if (val === null || val === undefined) return false
  if (typeof val === 'string') return val.trim() !== ''
  return true
}

// ===== 计算属性 =====

const hasAnyItems = computed(() => rootGroup.value.items.length > 0)

const hasValidConditions = computed(() => {
  const valid = filterValidItems(rootGroup.value)
  return valid.items.length > 0
})

const fieldOptions = computed(() => props.fields.map(f => ({ label: f.label, value: f.field })))

// ===== 同步 modelValue（外部变更时） =====

watch(
  () => props.modelValue,
  val => {
    if (internalChange) return
    if (!val) {
      rootGroup.value = createEmptyGroup()
      return
    }
    rootGroup.value = fromSerialized(val)
  }
)

/**
 * 将序列化的条件还原为内部 ConditionGroup
 */
function fromSerialized(data: DynamicSearchCondition | SerializedConditionGroup): ConditionGroup {
  const isGroup = 'groupLogic' in data
  const logic: 'AND' | 'OR' = isGroup ? (data as SerializedConditionGroup).groupLogic : data.logic
  const items: SerializedItem[] = isGroup ? (data as SerializedConditionGroup).groupItems : data.items

  return {
    id: uuidv4(),
    type: 'group',
    logic,
    items: (items ?? []).map(item => {
      if (item.type === 'group') {
        return fromSerialized(item)
      }
      return {
        id: uuidv4(),
        type: 'condition' as const,
        field: item.field ?? '',
        operator: item.operator ?? 'eq',
        value: item.value ?? null
      }
    })
  }
}

defineExpose({ handleReset })
</script>

<template>
  <component
    :is="displayMode === 'drawer' ? Drawer : Modal"
    :open="open"
    title="高级检索"
    :width="savedConditions.length > 0 ? 920 : 720"
    :mask-closable="false"
    :destroy-on-close="false"
    @after-open-change="
      (visible: boolean) => {
        if (visible) handleOpen()
      }
    "
    @cancel="handleCancel"
    @close="handleCancel"
  >
    <div class="la-dynamic-search-layout">
      <!-- 左侧：常用查询列表 -->
      <div v-if="savedConditions.length > 0" class="la-dynamic-search__sidebar">
        <div class="la-dynamic-search__sidebar-header">常用查询</div>
        <div class="la-dynamic-search__sidebar-body">
          <div
            v-for="saved in savedConditions"
            :key="saved.id"
            class="la-dynamic-search__sidebar-item"
            :class="{ 'is-active': saved.id === internalActiveSavedId }"
            @click="loadSavedCondition(saved)"
          >
            <SearchOutlined class="sidebar-icon" />
            <span class="sidebar-text">{{ saved.description }}</span>
            <a-tooltip title="删除该查询条件">
              <DeleteOutlined class="sidebar-delete" @click.stop="handleRemoveSaved(saved.id)" />
            </a-tooltip>
          </div>
        </div>
      </div>

      <!-- 右侧：条件编辑区 -->
      <div class="la-dynamic-search__editor">
        <!-- 顶部工具栏 -->
        <div class="la-dynamic-search__toolbar">
          <div class="la-dynamic-search__logic">
            <span class="logic-label">根逻辑：</span>
            <a-radio-group v-model:value="rootGroup.logic" size="small" button-style="solid">
              <a-radio-button value="AND">且（AND）</a-radio-button>
              <a-radio-button value="OR">或（OR）</a-radio-button>
            </a-radio-group>
          </div>
          <a-space>
            <a-button @click="addCondition(rootGroup)">
              <PlusOutlined />
              添加条件
            </a-button>
            <a-button @click="addSubGroup(rootGroup)">
              <GroupOutlined />
              添加分组
            </a-button>
            <a-button v-if="hasAnyItems" size="small" @click="handleReset">清空</a-button>
          </a-space>
        </div>

        <!-- 条件树（递归渲染） -->
        <OsConditionGroupRenderer
          :group="rootGroup"
          :fields="fields"
          :field-options="fieldOptions"
          :depth="0"
          @add-condition="addCondition"
          @add-sub-group="addSubGroup"
          @remove-item="removeItem"
          @field-change="onFieldChange"
          @condition-change="onConditionChange"
          @logic-change="() => {}"
        />

        <!-- 空状态提示 -->
        <div v-if="!hasAnyItems" class="la-dynamic-search__empty">
          <span>暂无查询条件，点击"添加条件"或"添加分组"开始配置</span>
        </div>

        <!-- 保存选项 -->
        <div v-if="allowSave" class="la-dynamic-search__save">
          <a-checkbox v-model:checked="saveChecked">记住本次查询条件</a-checkbox>
          <a-input
            v-if="saveChecked"
            v-model:value="saveDescription"
            placeholder="输入查询条件概述，如：最近一个月活跃用户"
            size="small"
            allow-clear
            class="la-dynamic-search__save-desc"
          />
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <template #footer>
      <a-button @click="handleCancel">取消</a-button>
      <a-button type="primary" :disabled="!hasValidConditions" @click="handleConfirm">确认查询</a-button>
    </template>
  </component>
</template>

<style scoped lang="less">
// ===== 整体布局 =====

.la-dynamic-search-layout {
  display: flex;
  gap: 0;
  min-height: 280px;
}

// ===== 左侧：常用查询侧边栏 =====

.la-dynamic-search__sidebar {
  width: 186px;
  flex-shrink: 0;
  border-right: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
  margin-right: 16px;
  padding-right: 12px;
}

.la-dynamic-search__sidebar-header {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
  padding: 0 4px 10px 4px;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 8px;
  flex-shrink: 0;
}

.la-dynamic-search__sidebar-body {
  flex: 1;
  overflow-y: auto;
  max-height: 340px;
}

.la-dynamic-search__sidebar-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #595959;
  transition: all 0.15s;
  position: relative;

  &:hover {
    background: #f5f5f5;
    color: var(--brand);

    .sidebar-delete {
      opacity: 1;
    }
  }

  &.is-active {
    background: #e6f7ff;
    color: var(--brand);
    font-weight: 500;

    .sidebar-delete {
      opacity: 1;
    }
  }

  .sidebar-icon {
    font-size: 12px;
    flex-shrink: 0;
    opacity: 0.6;
  }

  .sidebar-text {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sidebar-delete {
    flex-shrink: 0;
    font-size: 12px;
    opacity: 0;
    color: #ff4d4f;
    transition: opacity 0.15s;
    padding: 2px;
    border-radius: 3px;

    &:hover {
      background: rgba(255, 77, 79, 0.1);
    }
  }
}

// ===== 右侧：条件编辑区 =====

.la-dynamic-search__editor {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.la-dynamic-search__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.la-dynamic-search__logic {
  display: flex;
  align-items: center;
  gap: 8px;

  .logic-label {
    font-size: 13px;
    color: #595959;
    flex-shrink: 0;
  }
}

.la-dynamic-search__empty {
  padding: 8px 0;
  color: #bfbfbf;
  font-size: 13px;
}

.la-dynamic-search__save {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.la-dynamic-search__save-desc {
  flex: 1;
  max-width: 360px;
}
</style>
