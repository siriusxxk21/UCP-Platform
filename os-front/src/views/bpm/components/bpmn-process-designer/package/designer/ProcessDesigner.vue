<script lang="ts" setup>
import { computed, onMounted } from 'vue'

defineOptions({ name: 'MyProcessDesigner' })

const props = withDefaults(
  defineProps<{
    value?: string
    modelValue?: string
    processId?: string
    processName?: string
    model?: Record<string, any>
  }>(),
  {
    value: '',
    modelValue: '',
    processId: '',
    processName: '',
    model: () => ({})
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:value': [value: string]
  save: [value: string]
  'init-finished': [instance: Record<string, any>]
}>()

const xmlValue = computed({
  get: () => props.modelValue || props.value || '',
  set: (value: string) => {
    emit('update:modelValue', value)
    emit('update:value', value)
  }
})
// 本期任务节点由简单设计器配置；高级源码不允许移除等待标记而绕开任务完成条件。
const containsTaskCenterNode = computed(() => /<(?:[\w-]+:)?taskCenterConfig\b/.test(xmlValue.value))

onMounted(() => {
  emit('init-finished', {
    type: 'native-bpmn-placeholder',
    processId: props.processId,
    processName: props.processName,
    model: props.model
  })
})
</script>

<template>
  <div class="bpmn-designer">
    <div class="designer-toolbar">
      <div>
        <div class="designer-title">BPMN XML 配置</div>
        <div v-if="processId" class="designer-subtitle">{{ processId }}</div>
      </div>
      <span class="designer-subtitle">修改后使用顶部“保存草稿”</span>
    </div>
    <a-alert
      v-if="containsTaskCenterNode"
      type="info"
      show-icon
      message="此模型包含任务节点，BPMN 源码仅供查看，任务配置原样保留。本期请使用简单流程设计器新增和配置任务节点。"
    />
    <a-textarea
      v-model:value="xmlValue"
      :auto-size="{ minRows: 18 }"
      class="xml-editor"
      aria-label="BPMN XML 配置"
      :readonly="containsTaskCenterNode"
      placeholder="请输入或粘贴 BPMN XML"
    />
  </div>
</template>

<style scoped>
.bpmn-designer {
  min-height: 560px;
  border: 1px solid var(--border);
  background: #fff;
}

.designer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

.designer-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.designer-subtitle {
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 12px;
}

.xml-editor {
  border: 0;
  border-radius: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
}
</style>
