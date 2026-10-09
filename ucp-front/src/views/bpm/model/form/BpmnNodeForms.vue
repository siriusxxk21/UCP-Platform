<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import NodeFormEditor from './NodeFormEditor.vue'
import { bpmnNodeForms, setBpmnNodeForm, formSourceSummary, type FormSource, type NodeFormBinding } from './node-form'
const props = defineProps<{ modelValue: string; inherited: FormSource }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; 'configure-start': [] }>()
const nodeId = ref<string>(),
  error = ref('')
const nodes = computed(() => {
  try {
    return bpmnNodeForms(props.modelValue)
  } catch {
    return []
  }
})
const selected = computed(() => nodes.value.find(node => node.id === nodeId.value))
watch(
  nodes,
  value => {
    if (!value.some(node => node.id === nodeId.value)) nodeId.value = value[0]?.id
  },
  { immediate: true }
)
function apply(binding: NodeFormBinding, manual: boolean) {
  try {
    emit('update:modelValue', setBpmnNodeForm(props.modelValue, nodeId.value!, binding, manual))
    error.value = ''
  } catch (e: any) {
    error.value = e.message
  }
}
</script>
<template>
  <a-card title="节点表单与业务办理" size="small" class="node-forms-card">
    <div class="starting-form-summary">
      <span>发起表单：{{ formSourceSummary(inherited) }}</span>
      <a-button type="link" @click="emit('configure-start')">配置发起表单</a-button>
    </div>
    <a-select
      v-model:value="nodeId"
      aria-label="配置表单的流程节点"
      :options="nodes.map(node => ({ value: node.id, label: node.name }))"
      placeholder="请选择人工节点"
      class="node-selector"
    />
    <a-alert v-if="selected?.error || error" type="error" :message="selected?.error || error" show-icon />
    <NodeFormEditor
      v-if="selected"
      :key="selected.id"
      :binding="selected.binding"
      :inherited="inherited"
      :disabled="!!selected.error"
      @apply="apply"
    />
    <a-empty v-else description="请先添加人工节点" />
  </a-card>
</template>
<style scoped>
.node-forms-card {
  margin-bottom: 16px;
}
.node-selector {
  width: 100%;
  max-width: 420px;
  margin-bottom: 12px;
}
.starting-form-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
</style>
