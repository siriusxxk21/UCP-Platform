<script setup lang="ts">
import { computed } from 'vue'
import { taskContentHtml } from '@/nocode/task-content'

const props = defineProps<{ value?: string | null }>()
const html = computed(() => taskContentHtml(props.value))
</script>

<template>
  <div v-if="html" class="task-content-display" v-html="html" />
  <span v-else class="task-list__hint">暂无任务内容</span>
</template>

<style scoped>
.task-content-display {
  min-width: 0;
  overflow: auto;
  overflow-wrap: anywhere;
}
.task-content-display :deep(p),
.task-content-display :deep(blockquote) {
  margin: 0 0 var(--spacing-sm);
}
.task-content-display :deep(img) {
  max-width: 100%;
  height: auto;
}
.task-content-display :deep(table) {
  border-collapse: collapse;
}
.task-content-display :deep(td),
.task-content-display :deep(th) {
  border: 1px solid var(--border);
  padding: var(--spacing-xs) var(--spacing-sm);
}
.task-content-display :deep(pre) {
  white-space: pre-wrap;
}
.task-content-display :deep(ul),
.task-content-display :deep(ol) {
  padding-left: 1.5em;
}
.task-content-display :deep(blockquote) {
  border-left: 3px solid var(--border);
  padding-left: var(--spacing-md);
  color: var(--text-secondary);
}
.task-content-display :deep(li[data-type='taskItem']) {
  list-style: none;
}
.task-content-display :deep(li[data-type='taskItem'])::before {
  content: '☐ ';
}
.task-content-display :deep(li[data-checked='true'])::before {
  content: '☑ ';
}
</style>
