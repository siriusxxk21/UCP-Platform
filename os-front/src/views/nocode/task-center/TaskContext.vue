<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { TaskRow } from '@/types/nocode/task-center'
import { taskStates, taskStateColors } from '@/nocode/task-center'
import { taskContextAssignee, taskContextNodes, type TaskContextNode } from '@/nocode/task-context'
import { taskHierarchy, taskHierarchyLabel } from '@/nocode/task-hierarchy'
import TaskHierarchyCell from './TaskHierarchyCell.vue'

const props = defineProps<{ nodes: TaskRow[]; currentId: string; originId: string }>()
const emit = defineEmits<{ select: [id: string]; graph: [] }>()
const collapsed = ref<string[]>([]),
  expanded = ref(true),
  host = ref<HTMLElement>()
const contextNodes = computed(() => taskContextNodes(props.nodes))
const lookup = computed(() => new Map(contextNodes.value.map(node => [node.id, node])))
const hierarchy = computed(() => taskHierarchy(contextNodes.value))
const current = computed(() => lookup.value.get(props.currentId))
const root = computed(() => (current.value ? lookup.value.get(current.value.rootId) : undefined))
const path = computed(() => {
  const result: TaskContextNode[] = [],
    seen = new Set<string>()
  let node = current.value
  while (node && !seen.has(node.id)) {
    seen.add(node.id)
    result.unshift(node)
    node = node.parentId ? lookup.value.get(node.parentId) : undefined
  }
  return result
})
const rows = computed(() => {
  const result: Array<{ node: TaskContextNode; depth: number; children: number }> = [],
    seen = new Set<string>()
  const visit = (node: TaskContextNode, depth: number) => {
    if (seen.has(node.id)) return
    seen.add(node.id)
    const children = contextNodes.value.filter(item => item.parentId === node.id)
    result.push({ node, depth, children: children.length })
    if (!collapsed.value.includes(node.id)) children.forEach(child => visit(child, depth + 1))
  }
  contextNodes.value.filter(node => !node.parentId || !lookup.value.has(node.parentId)).forEach(node => visit(node, 0))
  return result
})
const counts = computed(() => ({
  completed: props.nodes.filter(node => node.id !== root.value?.id && node.status === 'COMPLETED').length,
  cancelled: props.nodes.filter(node => node.id !== root.value?.id && node.status === 'CANCELLED').length,
  total: props.nodes.filter(node => node.id !== root.value?.id).length
}))
const predecessors = computed(
  () =>
    current.value?.task?.predecessorIds
      .map(id => props.nodes.find(node => node.id === id))
      .filter((node): node is TaskRow => !!node) || []
)
const successors = computed(() => props.nodes.filter(node => node.predecessorIds.includes(props.currentId)))
function toggle(id: string) {
  collapsed.value = collapsed.value.includes(id)
    ? collapsed.value.filter(value => value !== id)
    : [...collapsed.value, id]
}
async function locate() {
  expanded.value = true
  collapsed.value = collapsed.value.filter(id => !path.value.some(node => node.id === id))
  await nextTick()
  host.value?.querySelector<HTMLElement>('[aria-current="true"]')?.scrollIntoView?.({ block: 'nearest' })
}
watch(
  () => props.currentId,
  () => {
    void locate()
  },
  { immediate: true }
)
</script>
<template>
  <nav class="task-context__path" aria-label="任务位置">
    <span>任务位置</span>
    <span v-if="path[0] && hierarchy.get(path[0].id)?.missingParent">上级任务未在当前视图中 /</span>
    <template v-for="(node, index) in path" :key="node.id">
      <span v-if="index">/</span>
      <a-button
        v-if="node.detailVisible"
        type="link"
        :disabled="node.id === currentId"
        @click="emit('select', node.id)"
      >
        {{ node.title }}
      </a-button>
      <span v-else :title="`${taskContextAssignee(node)} · ${taskStates[node.status]} · 仅作层级参考`">
        {{ node.title }}
      </span>
    </template>
    <a-button v-if="originId !== currentId" size="small" @click="emit('select', originId)">返回最初办理的任务</a-button>
  </nav>
  <section class="task-context" aria-label="整项任务概况">
    <div class="task-context__heading">
      <div>
        <div class="task-context__identity">
          <strong>{{ root ? '整项任务' : '当前可见任务' }}</strong>
          <a-button v-if="root?.detailVisible" type="link" @click="emit('select', root.id)">{{ root.title }}</a-button>
          <span v-else-if="root">{{ root.title }}</span>
          <a-tag v-if="root" color="blue">总任务</a-tag>
          <a-tag v-if="root?.referenceOnly">层级参考</a-tag>
          <a-tag v-if="root" :color="taskStateColors[root.status]">{{ taskStates[root.status] }}</a-tag>
        </div>
        <div class="task-list__hint">
          <template v-if="root">总任务负责人 {{ taskContextAssignee(root) }} · 可见下级</template>
          <template v-else>当前可见范围内</template>
          正常完成 {{ counts.completed }} / {{ counts.total }}
          <span v-if="counts.cancelled">· 已取消 {{ counts.cancelled }}</span>
        </div>
        <div
          v-if="current && current.id !== root?.id"
          class="task-context__identity task-context__current"
          aria-label="当前任务概况"
        >
          <strong>当前任务</strong>
          <span>{{ current.title }}</span>
          <a-tag color="blue">{{ hierarchy.get(current.id)?.label || (current.parentId ? '子任务' : '总任务') }}</a-tag>
          <a-tag :color="taskStateColors[current.status]">{{ taskStates[current.status] }}</a-tag>
        </div>
      </div>
      <a-space wrap>
        <a-button size="small" @click="locate">定位当前任务</a-button>
        <a-button size="small" @click="emit('graph')">查看关系图</a-button>
        <a-button type="link" @click="expanded = !expanded">{{ expanded ? '收起任务树' : '展开任务树' }}</a-button>
      </a-space>
    </div>
    <p v-if="contextNodes.some(node => node.referenceOnly)" class="task-list__hint">
      层级参考用于说明所属位置，不计入你的待办；可操作范围以当前任务权限为准。
    </p>
    <div v-if="expanded" ref="host" class="task-context__tree" role="tree" aria-label="整体任务树">
      <div
        v-for="row in rows"
        :key="row.node.id"
        class="task-context__row"
        :class="{ 'task-context__row--current': row.node.id === currentId }"
        role="treeitem"
        :aria-level="row.depth + 1"
        :aria-expanded="row.children ? !collapsed.includes(row.node.id) : undefined"
        :aria-current="row.node.id === currentId ? 'true' : undefined"
      >
        <TaskHierarchyCell
          class="task-context__hierarchy"
          :item="hierarchy.get(row.node.id)!"
          :expandable="row.children > 0"
          :expanded="!collapsed.includes(row.node.id)"
          :toggle-label="`${collapsed.includes(row.node.id) ? '展开' : '收起'}：${row.node.title}`"
          @toggle="toggle(row.node.id)"
        >
          <a-button v-if="row.node.detailVisible" type="link" @click="emit('select', row.node.id)">
            {{ row.node.title }}
          </a-button>
          <span v-else>{{ row.node.title }}</span>
          <template #extra>
            <a-space wrap>
              <a-tag v-if="row.node.id === currentId" color="blue">当前任务</a-tag>
              <a-tag v-if="row.node.referenceOnly">层级参考</a-tag>
              <span class="task-list__hint">{{ taskContextAssignee(row.node) }}</span>
              <!-- 总任务和当前任务的状态已在概况展示，任务树仅补充其他节点的状态。 -->
              <a-tag
                v-if="row.node.id !== root?.id && row.node.id !== currentId"
                :color="taskStateColors[row.node.status]"
              >
                {{ taskStates[row.node.status] }}
              </a-tag>
            </a-space>
          </template>
        </TaskHierarchyCell>
      </div>
    </div>
    <div v-if="predecessors.length || successors.length" class="task-context__relations">
      <div>
        <span>前置任务</span>
        <a-button
          v-for="node in predecessors"
          :key="node.id"
          :data-task-id="node.id"
          type="link"
          @click="emit('select', node.id)"
        >
          {{ taskHierarchyLabel(node, hierarchy.get(node.id)) }} · {{ taskStates[node.status] }}
        </a-button>
        <span v-if="!predecessors.length">无</span>
      </div>
      <div>
        <span>后续任务</span>
        <a-button
          v-for="node in successors"
          :key="node.id"
          :data-task-id="node.id"
          type="link"
          @click="emit('select', node.id)"
        >
          {{ taskHierarchyLabel(node, hierarchy.get(node.id)) }} · {{ taskStates[node.status] }}
        </a-button>
        <span v-if="!successors.length">无</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.task-context__identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-xs);
}
.task-context__current {
  margin-top: var(--spacing-sm);
}
.task-context__row {
  padding-left: var(--spacing-md);
}
.task-context__hierarchy {
  flex: 1;
  min-width: 0;
}
</style>
