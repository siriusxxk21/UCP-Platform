<script setup lang="ts">
import { computed } from 'vue'
import { richTextSummary, safeRichTextHtml } from '@/nocode/rich-text'

const props = defineProps<{ value: unknown; compact?: boolean }>()
const html = computed(() => safeRichTextHtml(props.value))
const summary = computed(() => richTextSummary(props.value))
</script>

<template>
  <div
    class="rich-text-display"
    :class="{ 'rich-text-display--compact': compact }"
    :title="compact ? summary : undefined"
    v-html="html"
  />
</template>

<style scoped>
.rich-text-display {
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.rich-text-display--compact {
  max-height: 7.5em;
  overflow: auto;
}
.rich-text-display :deep(p),
.rich-text-display :deep(h1),
.rich-text-display :deep(h2),
.rich-text-display :deep(h3),
.rich-text-display :deep(blockquote) {
  margin: 0 0 0.25em;
}
.rich-text-display :deep(p:last-child),
.rich-text-display :deep(h1:last-child),
.rich-text-display :deep(h2:last-child),
.rich-text-display :deep(h3:last-child),
.rich-text-display :deep(blockquote:last-child) {
  margin-bottom: 0;
}
.rich-text-display :deep(h1),
.rich-text-display :deep(h2),
.rich-text-display :deep(h3) {
  font-size: 1em;
  font-weight: 600;
}
.rich-text-display :deep(ul),
.rich-text-display :deep(ol) {
  margin: 0;
  padding-left: 1.5em;
}
.rich-text-display :deep(table) {
  border-collapse: collapse;
}
.rich-text-display :deep(td),
.rich-text-display :deep(th) {
  border: 1px solid var(--border, #e5e7eb);
  padding: 2px 4px;
}
</style>
