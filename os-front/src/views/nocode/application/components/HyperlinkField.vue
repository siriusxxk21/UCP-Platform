<script setup lang="ts">
import { computed } from 'vue'
import { hyperlinkParts, hyperlinkHref, hyperlinkError } from '@/nocode/hyperlink'
const props = defineProps<{ modelValue?: unknown; disabled?: boolean; readOnly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [unknown] }>()
const value = computed(() => hyperlinkParts(props.modelValue))
const error = computed(() => hyperlinkError(props.modelValue))
const href = computed(() => hyperlinkHref(props.modelValue))
function update(key: 'link' | 'text', text: string) {
  emit('update:modelValue', { ...value.value, [key]: text })
}
</script>
<template>
  <span v-if="readOnly">
    <a v-if="href" :href="href" target="_blank" rel="noopener noreferrer" @click.stop>{{ value.text || value.link }}</a>
    <span v-else>{{ value.text || value.link || '—' }}</span>
  </span>
  <div v-else class="hyperlink-input">
    <a-input
      :value="value.link"
      :disabled="disabled"
      placeholder="https://example.com"
      aria-label="链接地址"
      @update:value="update('link', $event)"
    />
    <a-input
      :value="value.text"
      :disabled="disabled"
      placeholder="显示文字（可选）"
      aria-label="链接显示文字"
      @update:value="update('text', $event)"
    />
    <span v-if="error" role="alert" class="link-error">{{ error }}</span>
  </div>
</template>
<style scoped>
.hyperlink-input {
  display: grid;
  gap: 8px;
}
.link-error {
  color: var(--error-color, #cf1322);
}
a {
  overflow-wrap: anywhere;
}
</style>
