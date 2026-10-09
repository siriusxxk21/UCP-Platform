<script setup lang="ts">
const scope = defineModel<'GROUP' | 'ALL'>({ required: true })
defineProps<{ label: string; readonly?: boolean }>()
</script>
<template>
  <a-form-item :label="label">
    <a-radio-group v-model:value="scope" :disabled="readonly" aria-label="业务数据范围">
      <a-radio value="GROUP">仅本组任务数据</a-radio>
      <a-radio value="ALL">全部业务数据</a-radio>
    </a-radio-group>
  </a-form-item>
</template>
