<script setup lang="ts">
import TiptapEditor from './TiptapEditor.vue'

defineProps<{
  modelValue: string
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const handleUpdate = (value: string) => {
  emit('update:modelValue', value)
}
</script>

<template>
  <TiptapEditor
    :model-value="modelValue"
    :placeholder="placeholder"
    @update:model-value="handleUpdate"
  />
</template>

<style scoped>
/* 样式由 TiptapEditor 组件提供 */
</style>
