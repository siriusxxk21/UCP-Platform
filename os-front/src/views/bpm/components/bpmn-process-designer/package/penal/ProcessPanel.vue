<script lang="ts" setup>
defineOptions({ name: 'MyProcessPenal' })

defineProps<{
  bpmnModeler?: Record<string, any>
  prefix?: string
  model?: Record<string, any>
}>()
</script>

<template>
  <aside class="process-panel__container">
    <a-descriptions :column="1" bordered size="small">
      <a-descriptions-item label="扩展前缀">{{ prefix || '-' }}</a-descriptions-item>
      <a-descriptions-item label="模型名称">{{ model?.name || model?.key || '-' }}</a-descriptions-item>
      <a-descriptions-item label="设计器">{{ bpmnModeler?.type || 'native' }}</a-descriptions-item>
    </a-descriptions>
  </aside>
</template>

<style scoped>
.process-panel__container {
  width: 300px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  background: #fff;
}
</style>
