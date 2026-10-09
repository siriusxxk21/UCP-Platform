<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { EditOutlined } from '@ant-design/icons-vue'

const open = defineModel<boolean>({ default: false })
const props = defineProps<{
  label: string
  summary: string
  secondary?: string
  hint?: string
  readonly?: boolean
  externalEditor?: boolean
}>()
const editor = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
watch(
  () => props.readonly,
  value => {
    if (value) open.value = false
  }
)
watch([open, editor], async ([value], _, onCleanup) => {
  let focusFrame = 0
  let cancelled = false
  onCleanup(() => {
    cancelled = true
    cancelAnimationFrame(focusFrame)
  })
  if (!value || props.readonly || props.externalEditor) return
  await nextTick()
  if (cancelled) return
  // 等原位输入框渲染后聚焦；切换单元格或卸载时取消，避免焦点跳回旧字段。
  focusFrame = requestAnimationFrame(() => {
    focusFrame = requestAnimationFrame(() => {
      if (open.value && !props.readonly && !props.externalEditor)
        editor.value
          ?.querySelector<HTMLElement>('input:not([type="hidden"]), textarea, select, button')
          ?.focus({ preventScroll: true })
    })
  })
})
function changeOpen(value: boolean) {
  if (!props.readonly) open.value = value
}
async function close() {
  open.value = false
  await nextTick()
  trigger.value?.focus({ preventScroll: true })
}
function blur(event: FocusEvent) {
  if (!editor.value?.contains(event.relatedTarget as Node | null)) open.value = false
}
</script>

<template>
  <div
    v-if="open && !readonly && !externalEditor"
    ref="editor"
    class="task-editable-cell__editor"
    @focusout="blur"
    @keydown.esc.stop="close"
  >
    <slot :close="close" />
  </div>
  <component
    v-else
    :is="readonly ? 'div' : 'button'"
    ref="trigger"
    :type="readonly ? undefined : 'button'"
    class="task-editable-cell"
    :class="{ 'task-editable-cell--readonly': readonly, 'task-editable-cell--active': open && !readonly }"
    :aria-label="readonly ? label : `编辑${label}`"
    :aria-expanded="readonly ? undefined : open"
    :aria-haspopup="!readonly && externalEditor ? 'dialog' : undefined"
    :aria-description="[summary, secondary].filter(Boolean).join('，')"
    :title="hint || summary"
    @click="changeOpen(true)"
  >
    <span class="task-editable-cell__text">
      <span>{{ summary }}</span>
      <span v-if="secondary" class="task-editable-cell__secondary">{{ secondary }}</span>
    </span>
    <EditOutlined v-if="!readonly && externalEditor" class="task-editable-cell__icon" aria-hidden="true" />
  </component>
</template>

<style scoped>
.task-editable-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  width: 100%;
  min-width: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  padding: var(--spacing-xs);
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.task-editable-cell:not(.task-editable-cell--readonly):hover,
.task-editable-cell--active,
.task-editable-cell:focus-visible {
  border-color: var(--border-hover);
  background: var(--brand-light);
}
.task-editable-cell:focus-visible {
  outline: 2px solid var(--brand);
}
.task-editable-cell--readonly {
  cursor: default;
}
.task-editable-cell__text {
  display: grid;
  gap: var(--spacing-xs);
  min-width: 0;
  overflow-wrap: anywhere;
}
.task-editable-cell__secondary {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-editable-cell__icon {
  margin-left: auto;
  color: var(--text-secondary);
  opacity: 0.4;
  flex-shrink: 0;
}
.task-editable-cell:hover .task-editable-cell__icon,
.task-editable-cell:focus-visible .task-editable-cell__icon {
  opacity: 1;
  color: var(--brand);
}
.task-editable-cell__editor {
  display: grid;
  width: 100%;
  min-width: 0;
  text-align: left;
}
</style>
