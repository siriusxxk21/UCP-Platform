<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 支持多选、正选、反选的下拉选择器（类似 Excel 筛选器交互）。
 *
 * - 正选（多选）：勾选需要的项后立即提交并触发查询。
 * - 反选：一键翻转当前勾选状态，已选的取消，未选的选中。
 * - 全选：一键选中所有选项。
 * - 清除：一键清空所有选中。
 * - 点击确定再次提交当前值并关闭，点击取消或点击外部仅关闭面板。
 */
export interface SelectOption {
  value: string
  label: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: string[]
    options?: SelectOption[]
    placeholder?: string
    disabled?: boolean
  }>(),
  {
    modelValue: () => [],
    options: () => [],
    placeholder: '请选择',
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
  (e: 'change', value: string[]): void
}>()

// ── 内部状态 ──
const open = ref(false)
const innerValue = ref<string[]>([...props.modelValue])

watch(
  () => props.modelValue,
  val => {
    const arr = [...(val || [])]
    innerValue.value = arr
  },
  { deep: true }
)

// ── 派生 ──
const allValues = computed(() => props.options.map(o => o.value))
const selectedCount = computed(() => innerValue.value.length)

/** 触发器显示文本：简洁，最多展示一项 + 数量后缀 */
const triggerText = computed(() => {
  const cnt = selectedCount.value
  const total = allValues.value.length
  if (cnt === 0) return ''
  if (cnt === total && total > 0) return '全部'
  if (cnt === 1) {
    const opt = props.options.find(o => o.value === innerValue.value[0])
    return opt?.label || ''
  }
  return `${cnt} 项`
})

// ── 操作 ──
function toggleItem(val: string) {
  const idx = innerValue.value.indexOf(val)
  if (idx >= 0) {
    commitValue(innerValue.value.filter(v => v !== val))
  } else {
    commitValue([...innerValue.value, val])
  }
}

function handleSelectAll() {
  commitValue(allValues.value)
}

function handleClearAll() {
  commitValue([])
}

function handleInvert() {
  commitValue(allValues.value.filter(v => !innerValue.value.includes(v)))
}

function handleOpenChange(val: boolean) {
  open.value = val
}

function commitValue(value: string[]) {
  const result = [...value]
  innerValue.value = result
  emit('update:modelValue', result)
  emit('change', result)
}

function handleConfirm() {
  commitValue(innerValue.value)
  open.value = false
}

function handleCancel() {
  open.value = false
}

function getPopupContainer(): HTMLElement {
  return document.body
}
</script>

<template>
  <a-dropdown
    :open="open"
    :trigger="['click']"
    placement="bottomLeft"
    :disabled="disabled"
    :get-popup-container="getPopupContainer"
    @openChange="handleOpenChange"
  >
    <!-- 触发器 -->
    <div :class="{ 'is-open': open, 'is-disabled': disabled }" class="os-select-invert-trigger">
      <span v-if="triggerText" class="os-select-invert-text">{{ triggerText }}</span>
      <span v-else class="os-select-invert-placeholder">{{ placeholder }}</span>
      <span class="os-select-invert-arrow" :class="{ rotated: open }">
        <svg viewBox="0 0 1024 1024" width="14" height="14" fill="currentColor">
          <path
            d="M884 256h-75c-5.1 0-9.9 2.5-12.9 6.6L512 654.2 227.9 262.6c-3-4.1-7.8-6.6-12.9-6.6h-75c-6.5 0-10.3 7.4-6.5 12.7l352.6 486.1c12.8 17.6 39 17.6 51.7 0l352.6-486.1c3.9-5.3 0.1-12.7-6.4-12.7z"
          />
        </svg>
      </span>
    </div>

    <!-- 下拉面板 -->
    <template #overlay>
      <div class="os-select-invert-panel" @click.stop>
        <!-- 选项列表 -->
        <div class="os-select-invert-list">
          <div
            v-for="opt in options"
            :key="opt.value"
            class="os-select-invert-item"
            :class="{ checked: innerValue.includes(opt.value) }"
            @click="toggleItem(opt.value)"
          >
            <span class="os-select-invert-check">
              <svg v-if="innerValue.includes(opt.value)" viewBox="0 0 12 12" class="os-select-invert-check-icon">
                <path
                  d="M2 6l3 3 5-6"
                  fill="none"
                  stroke="currentColor"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                />
              </svg>
            </span>
            <span class="os-select-invert-label">{{ opt.label }}</span>
          </div>
        </div>
        <!-- 底部操作栏 -->
        <div class="os-select-invert-footer">
          <div class="os-select-invert-footer-links">
            <button class="os-select-invert-link" @click="handleSelectAll">全选</button>
            <button class="os-select-invert-link" @click="handleInvert">反选</button>
            <button class="os-select-invert-link" @click="handleClearAll">清除</button>
          </div>
          <div class="os-select-invert-footer-btns">
            <button class="os-select-invert-btn os-select-invert-btn-cancel" @click="handleCancel">取消</button>
            <button class="os-select-invert-btn os-select-invert-btn-confirm" @click="handleConfirm">确定</button>
          </div>
        </div>
      </div>
    </template>
  </a-dropdown>
</template>

<style scoped>
/* ── 触发器 ── */
.os-select-invert-trigger {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 11px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  user-select: none;
  font-size: 14px;
  color: #374151;
  min-width: 170px;
  max-width: 240px;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
  box-sizing: border-box;
}

.os-select-invert-trigger:hover {
  border-color: #4338ca;
}

.os-select-invert-trigger.is-open {
  border-color: #4338ca;
  box-shadow: 0 0 0 2px rgba(67, 56, 202, 0.12);
}

.os-select-invert-trigger.is-disabled {
  background: #f5f5f5;
  color: #bfbfbf;
  cursor: not-allowed;
}

.os-select-invert-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  line-height: 32px;
}

.os-select-invert-placeholder {
  color: #bfbfbf;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  line-height: 32px;
}

.os-select-invert-arrow {
  display: flex;
  align-items: center;
  color: #b5b3b3;
  flex-shrink: 0;
  margin-left: 4px;
  transition: transform 0.2s;
  line-height: 1;
}

.os-select-invert-arrow.rotated {
  transform: rotate(180deg);
}

/* ── 下拉面板 ── */
.os-select-invert-panel {
  background: #fff;
  border-radius: 8px;
  box-shadow:
    0 6px 16px rgba(0, 0, 0, 0.08),
    0 3px 6px rgba(0, 0, 0, 0.04);
  min-width: 180px;
  overflow: hidden;
}

/* ── 选项列表 ── */
.os-select-invert-list {
  max-height: 260px;
  overflow-y: auto;
  padding: 4px 0;
}

.os-select-invert-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  cursor: pointer;
  transition: background 0.1s;
  user-select: none;
}

.os-select-invert-item:hover {
  background: #f5f6f8;
}

.os-select-invert-check {
  width: 14px;
  height: 14px;
  border: 1.5px solid #d1d5db;
  border-radius: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
}

.os-select-invert-item.checked .os-select-invert-check {
  background: #4338ca;
  border-color: #4338ca;
}

.os-select-invert-check-icon {
  width: 10px;
  height: 10px;
  color: #fff;
}

.os-select-invert-label {
  font-size: 13px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── 底部操作栏 ── */
.os-select-invert-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-top: 1px solid #f0f0f0;
  background: #fafbfc;
}

.os-select-invert-footer-links {
  display: flex;
  align-items: center;
  gap: 4px;
}

.os-select-invert-link {
  background: none;
  border: none;
  font-size: 12px;
  color: #6b7280;
  cursor: pointer;
  padding: 3px 6px;
  border-radius: 4px;
  font-family: inherit;
  transition:
    color 0.15s,
    background 0.15s;
}

.os-select-invert-link:hover {
  color: #4338ca;
  background: #eef2ff;
}

.os-select-invert-footer-btns {
  display: flex;
  align-items: center;
  gap: 6px;
}

.os-select-invert-btn {
  border: none;
  border-radius: 4px;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  padding: 4px 14px;
  transition: all 0.15s;
  line-height: 18px;
}

.os-select-invert-btn-cancel {
  background: #fff;
  color: #555;
  border: 1px solid #d9d9d9;
}

.os-select-invert-btn-cancel:hover {
  color: #4338ca;
  border-color: #4338ca;
}

.os-select-invert-btn-confirm {
  background: #4338ca;
  color: #fff;
}

.os-select-invert-btn-confirm:hover {
  background: #3730a3;
}
</style>
