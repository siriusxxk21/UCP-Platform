<script lang="ts" setup>
import type { SimpleFlowNode } from '../consts'
import ProcessNodeTree from './process-node-tree.vue'
import { computed } from 'vue'
import { flowNodeStates } from '../../bpmn-process-designer/package/designer/bpmn-view'

defineOptions({ name: 'SimpleProcessViewer' })

const props = defineProps<{
  flowNode?: SimpleFlowNode
  tasks?: any[]
  processInstance?: Record<string, any>
  taskNodes?: import('@/types/nocode/workflow-task-node').WorkflowTaskNodeView[]
}>()
const emit = defineEmits<{ openTask: [nodeId: string] }>()
const taskNodeIds = computed(() =>
  (props.taskNodes || []).filter(item => item.canViewTask && item.taskId).map(item => item.nodeId),
)
const nodeStates = computed(() => {
  const states: Record<string, string> = flowNodeStates({ tasks: props.tasks })
  for (const item of props.taskNodes || []) {
    states[item.nodeId]
      = item.state === 'COMPLETED'
        ? 'finished'
        : item.state === 'INVALIDATED'
          ? 'invalidated'
          : item.error
            ? 'blocked'
            : 'running'
  }
  return states
})
</script>

<template>
  <div class="simple-viewer">
    <div v-if="processInstance?.name" class="process-title">{{ processInstance.name }}</div>
    <div v-if="flowNode" class="viewer-canvas">
      <ProcessNodeTree
        :flow-node="flowNode"
        :node-states="nodeStates"
        :task-node-ids="taskNodeIds"
        readonly
        @open-task="emit('openTask', $event)"
      />
    </div>
    <a-empty v-else description="暂无简单流程数据" />
  </div>
</template>

<style scoped>
.simple-viewer {
  min-width: 0;
  min-height: 360px;
}

.process-title {
  margin-bottom: 16px;
  color: #101828;
  font-size: 16px;
  font-weight: 600;
}

.viewer-canvas {
  max-height: 620px;
  min-height: 360px;
  overflow: auto;
  padding: 24px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background:
    linear-gradient(rgba(16, 24, 40, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(16, 24, 40, 0.04) 1px, transparent 1px);
  background-size: 18px 18px;
}
</style>
