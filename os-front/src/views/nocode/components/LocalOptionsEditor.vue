<script setup lang="ts">
import { PlusOutlined } from '@ant-design/icons-vue'
import type { FieldOptions } from '@/types/nocode/data-center'

defineProps<{ disabled?: boolean }>()
const options = defineModel<FieldOptions['options']>({ required: true })
</script>

<template>
  <div class="local-options-editor">
    <div v-for="(item, index) in options" :key="index" class="option-row">
      <a-input v-model:value="item.code" placeholder="稳定编码" :disabled="disabled" />
      <a-input v-model:value="item.label" placeholder="显示名称" :disabled="disabled" />
      <a-checkbox v-model:checked="item.disabled" :disabled="disabled">停用</a-checkbox>
      <a-button danger :disabled="disabled" @click="options.splice(index, 1)">移除</a-button>
    </div>
    <a-button size="small" :disabled="disabled" @click="options.push({ code: '', label: '', disabled: false })">
      <PlusOutlined />
      添加选项
    </a-button>
  </div>
</template>

<style scoped>
.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.option-row .ant-checkbox-wrapper {
  flex-shrink: 0;
}
</style>
