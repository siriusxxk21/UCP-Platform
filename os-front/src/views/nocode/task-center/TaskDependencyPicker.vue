<script setup lang="ts">
import { computed, ref } from 'vue'
import { taskDependencyIssue, orderTaskSiblings } from '@/nocode/task-arrangement'
import { taskHierarchy } from '@/nocode/task-hierarchy'
import type { TaskNodeInput } from '@/types/nocode/task-center'

const selected = defineModel<string[]>({ required: true })
const props = defineProps<{ nodes: TaskNodeInput[]; currentId: string; rootId?: string; readonly?: boolean }>()
const search = ref('')
const structure = computed(() =>
  props.nodes.map(node => ({
    ...node,
    parentId: node.parentId || (props.rootId && node.id !== props.rootId ? props.rootId : null),
    predecessorIds: node.id === props.currentId ? selected.value : node.predecessorIds
  }))
)
const hierarchy = computed(() => taskHierarchy(structure.value, { draft: !props.rootId, rootId: props.rootId }))
const current = computed(() => props.nodes.find(node => node.id === props.currentId))
const label = (id: string) => {
  const node = props.nodes.find(node => node.id === id)
  return `${hierarchy.value.get(id)?.outline || ''} ${node?.title || '未命名任务'}`.trim()
}
const candidates = computed(() =>
  orderTaskSiblings(structure.value)
    .filter(node => !search.value.trim() || label(node.id).toLowerCase().includes(search.value.trim().toLowerCase()))
    .map(node => ({
      node,
      issue: taskDependencyIssue(structure.value, node.id, props.currentId),
      item: hierarchy.value.get(node.id)
    }))
)
function toggle(id: string, checked: boolean) {
  if (props.readonly) return
  if (checked && taskDependencyIssue(structure.value, id, props.currentId)) return
  selected.value = checked ? [...new Set([...selected.value, id])] : selected.value.filter(value => value !== id)
}
</script>
<template>
  <section class="task-dependency-picker" aria-label="设置任务先后顺序">
    <div class="task-dependency-picker__summary" aria-live="polite">
      <strong>{{ current?.title || '当前任务' }}</strong>
      <span v-if="!selected.length">没有单独设置前置任务；仍遵守上级任务的开始条件。</span>
      <template v-else>
        <span>等待以下 {{ selected.length }} 项全部完成后开始：</span>
        <a-tag v-for="id in selected" :key="id" :closable="!readonly" @close.prevent="toggle(id, false)">
          {{ label(id) }}
        </a-tag>
      </template>
    </div>
    <a-input v-model:value="search" allow-clear placeholder="按任务名称或层级编号查找" aria-label="查找前置任务" />
    <p class="task-list__hint">勾选必须先完成的任务。包含关系由缩进表示，不代表先后顺序。</p>
    <div class="task-dependency-picker__tree">
      <div
        v-for="{ node, issue, item } in candidates"
        :key="node.id"
        class="task-dependency-picker__row"
        :style="{ paddingInlineStart: `${12 + Math.min(item?.depth || 0, 10) * 20}px` }"
        :data-task-id="node.id"
      >
        <a-checkbox
          :checked="selected.includes(node.id)"
          :disabled="readonly || (!!issue && !selected.includes(node.id))"
          @change="toggle(node.id, $event.target.checked)"
        >
          <span class="task-dependency-picker__number">{{ item?.outline }}</span>
          {{ node.title || '未命名任务' }}
        </a-checkbox>
        <span v-if="issue && !selected.includes(node.id)" class="task-dependency-picker__reason">
          {{ node.id === currentId ? '当前任务' : issue }}
        </span>
        <span v-else-if="item?.parentTitle" class="task-dependency-picker__reason">属于 {{ item.parentTitle }}</span>
      </div>
      <p v-if="!candidates.length" class="task-list__hint">没有匹配的任务</p>
    </div>
  </section>
</template>
<style scoped>
.task-dependency-picker {
  display: grid;
  gap: var(--spacing-sm);
  min-width: 0;
}
.task-dependency-picker__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: var(--brand-light);
  border-radius: var(--radius-sm);
}
.task-dependency-picker__tree {
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.task-dependency-picker__row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs) var(--spacing-md);
  align-items: center;
  padding: var(--spacing-sm) var(--spacing-md);
  border-bottom: 1px solid var(--border);
}
.task-dependency-picker__number {
  color: var(--brand);
  font-variant-numeric: tabular-nums;
}
.task-dependency-picker__reason {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
</style>
