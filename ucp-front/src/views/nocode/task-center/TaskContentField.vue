<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { EditOutlined } from '@ant-design/icons-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { TASK_CONTENT_MAX_LENGTH, taskContentHtml, taskContentSummary } from '@/nocode/task-content'
import TaskContentDisplay from './TaskContentDisplay.vue'

const TiptapEditor = defineAsyncComponent(() => import('@/components/TiptapEditor.vue'))
const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    readonly?: boolean
    disabled?: boolean
    label?: string
  }>(),
  { label: '任务内容' }
)
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const open = ref(false)
const draft = ref('')
const initial = ref('')
const error = ref('')
const summary = computed(() => taskContentSummary(props.modelValue))
function edit() {
  if (props.disabled) return
  initial.value = taskContentHtml(props.modelValue)
  draft.value = initial.value
  error.value = ''
  open.value = true
}
function save() {
  if (props.readonly || props.disabled) return
  // 没有输入时不把历史纯文字静默转换成 HTML，也不改变历史版本内容。
  if (draft.value === initial.value) {
    open.value = false
    return
  }
  const content = taskContentHtml(draft.value)
  if (content.length > TASK_CONTENT_MAX_LENGTH) {
    error.value = `任务内容过长，最多 ${TASK_CONTENT_MAX_LENGTH} 个字符（含排版标记），请精简后再确定。`
    return
  }
  emit('update:modelValue', content)
  open.value = false
}
// 节点切换或权限变化后不得将旧编辑副本写回新的任务。
watch(
  () => [props.modelValue, props.readonly, props.disabled],
  () => {
    open.value = false
  }
)
</script>

<template>
  <button
    type="button"
    class="task-content-field"
    :class="{ 'task-content-field--empty': !summary }"
    :disabled="disabled"
    :aria-label="`${readonly ? '查看' : '编辑'}${label}`"
    :title="summary || '添加任务内容'"
    @click.stop="edit"
  >
    <span>{{ summary || (readonly ? '暂无内容' : '添加内容') }}</span>
    <EditOutlined v-if="!readonly" />
  </button>
  <OsModalForm
    v-if="open"
    :open="true"
    title="任务内容"
    :width="900"
    :wrap-form="false"
    :allow-switch-display="false"
    :mask-closable="false"
    :show-footer="!readonly"
    ok-text="确定"
    @ok="save"
    @cancel="open = false"
  >
    <template #formItems>
      <TaskContentDisplay v-if="readonly" :value="modelValue" />
      <template v-else>
        <TiptapEditor v-model="draft" placeholder="请输入任务内容" />
        <a-alert v-if="error" class="task-content-field__error" type="error" show-icon :message="error" />
      </template>
    </template>
  </OsModalForm>
</template>

<style scoped>
.task-content-field {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
  min-width: 0;
  height: var(--control-height);
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-container);
  color: var(--text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.task-content-field > span:first-child {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-content-field--empty,
.task-content-field > .anticon {
  color: var(--text-secondary);
}
.task-content-field:hover:not(:disabled),
.task-content-field:focus-visible {
  border-color: var(--brand);
}
.task-content-field:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.task-content-field:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.task-content-field__error {
  margin-top: var(--spacing-sm);
}
</style>
