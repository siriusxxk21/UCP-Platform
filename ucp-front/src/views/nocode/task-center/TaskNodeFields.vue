<script setup lang="ts">
import { useNocodePlatform } from '@/nocode/platform'
import { computed, ref, watch } from 'vue'

import type { TaskNodeInput, TaskMember, TaskDataPolicy } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import { taskPriorities } from '@/nocode/task-center'
import { taskWorkBudgetEntries } from '@/nocode/task-work-rule'
import { taskHierarchy, taskHierarchyLabel } from '@/nocode/task-hierarchy'
import TaskEntriesEditor from './TaskEntriesEditor.vue'
import TaskAssignmentFields from './TaskAssignmentFields.vue'
import TaskAcceptanceFields from './TaskAcceptanceFields.vue'
import TaskScheduleFields from './TaskScheduleFields.vue'
import TaskContentField from './TaskContentField.vue'
const api = useNocodePlatform().taskCenter
const node = defineModel<TaskNodeInput>({ required: true })
const props = withDefaults(
  defineProps<{
    isRoot?: boolean
    hierarchyRootId?: string
    rootEntries?: TaskWorkEntryConfig[]
    rootDataPolicy?: TaskDataPolicy | null
    nodes?: TaskNodeInput[]
    members: TaskMember[]
    readonly?: boolean
    dataReadonly?: boolean
    workAdjustment?: boolean
    templateEditing?: boolean
    bindingLocked?: boolean
    applicationId?: string | null
    businessContextSummary?: string
    plannedStart?: string | null
    titleLabel?: string
    section?: 'all' | 'basic' | 'business' | 'feedback'
  }>(),
  {
    nodes: () => [],
    readonly: false,
    section: 'all',
    titleLabel: '任务名称'
  }
)
const hierarchy = computed(() => taskHierarchy(props.nodes, { draft: true, rootId: props.hierarchyRootId }))
const dataPolicy = computed(() => (props.isRoot ? node.value.dataPolicy : props.rootDataPolicy))
const inheritedData = computed(() => !props.isRoot && !!dataPolicy.value)
const dataReadonly = computed(() => props.readonly || props.dataReadonly)
const choices = computed(() =>
  props.nodes
    .filter(n => n.id !== node.value.id && n.id !== props.hierarchyRootId)
    .map(n => ({ value: n.id, label: taskHierarchyLabel(n, hierarchy.value.get(n.id)) }))
)
// 顶层子任务的持久化 parentId 可为空；业务来源选项仍需按当前工作区看到其总任务祖先。
const entryNodes = computed(() => [
  ...props.nodes.filter(n => n.id !== node.value.id),
  { ...node.value, parentId: props.isRoot ? node.value.parentId : node.value.parentId || props.hierarchyRootId || null }
])
const predecessorLabel = (id: string) => {
  const previous = props.nodes.find(item => item.id === id)
  return previous
    ? `${hierarchy.value.get(id)?.outline || ''} ${previous.title || '未命名任务'}`.trim()
    : '未显示的前置任务'
}
const predecessorSummary = computed(() => node.value.predecessorIds.map(predecessorLabel).join('、'))
const inheritedPredecessorSummary = computed(() => {
  const ids = new Set<string>()
  const visited = new Set<string>([node.value.id])
  let parentId = node.value.parentId || props.hierarchyRootId
  while (parentId && !visited.has(parentId)) {
    visited.add(parentId)
    const parent = props.nodes.find(item => item.id === parentId)
    parent?.predecessorIds.forEach(id => ids.add(id))
    parentId = parent?.parentId || undefined
  }
  return [...ids]
    .filter(id => !node.value.predecessorIds.includes(id))
    .map(predecessorLabel)
    .join('、')
})
const fields = ref<Array<{ value: string; label: string }>>([])
const workEntries = computed({
  get: (): TaskWorkEntryConfig[] => {
    const entries = node.value.entries || []
    if (!node.value.binding || entries.some(entry => entry.key === '__business')) return entries
    return [
      {
        key: '__business',
        name: '业务数据',
        binding: node.value.binding,
        dataMode: 'ROOT_SHARED',
        sourceNodeId: null,
        sourceEntryKey: null,
        readableFieldIds: null,
        writableFieldIds: null,
        required: false,
        allowAll: false
      },
      ...entries
    ]
  },
  set: value => {
    if (node.value.binding && !value.some(entry => entry.key === '__business') && !props.bindingLocked)
      node.value.binding = null
    node.value.entries = value
  }
})
const optionalOpen = ref<string[]>([])
function changeOptional(value: unknown) {
  optionalOpen.value = Array.isArray(value) ? value.map(String) : [String(value)]
}
const businessSummary = computed(() => {
  if (inheritedData.value) return '继承总任务'
  return (
    [
      props.businessContextSummary || (props.applicationId ? '已关联应用' : ''),
      workEntries.value.length ? `已配置 ${workEntries.value.length} 项业务办理` : '',
      node.value.sharing.mode === 'SHARED' ? '共享前序数据' : ''
    ]
      .filter(Boolean)
      .join(' · ') || '未关联业务'
  )
})
let fieldGeneration = 0
watch(
  () => [node.value.sharing.mode, node.value.sharing.sourceNodeId],
  async () => {
    const token = ++fieldGeneration
    if (props.dataReadonly || node.value.sharing.mode !== 'SHARED') return
    node.value.binding = null
    fields.value = []
    const source = props.nodes.find(n => n.id === node.value.sharing.sourceNodeId)
    if (!source?.binding) return
    try {
      const form = await api.formPreview(source.binding)
      if (token === fieldGeneration)
        fields.value = form.model.object.fields
          .filter(f => form.writableFieldIds.includes(f.id!))
          .map(f => ({ value: f.id!, label: f.name }))
    } catch {
      /* 来源权限或版本由提交校验返回，未能读取时不提供额外可写字段。 */
    }
  },
  { immediate: true }
)
</script>
<template>
  <div class="task-form-grid">
    <template v-if="section === 'all' || section === 'basic'">
      <div class="task-config-heading task-form-grid__full">
        <h4>任务基本信息</h4>
        <p>安排做什么、由谁负责、什么时候完成。</p>
      </div>
      <a-form-item :label="titleLabel" class="task-form-grid__full" required>
        <a-input v-model:value="node.title" :disabled="readonly" :maxlength="160" placeholder="填写可执行的任务名称" />
      </a-form-item>
      <a-form-item label="负责人安排" class="task-form-grid__full">
        <slot name="assignment" :node="node" :readonly="readonly">
          <TaskAssignmentFields
            v-model="node"
            :members="members"
            :readonly="readonly"
            :allow-follow="!isRoot"
            :root-assignee-id="nodes.find(item => item.id === hierarchyRootId)?.assigneeId"
          />
        </slot>
      </a-form-item>
      <a-form-item v-if="isRoot" label="验收人（可选）" class="task-form-grid__full">
        <slot name="acceptance" :node="node" :readonly="readonly">
          <TaskAcceptanceFields v-model="node" :members="members" :readonly="readonly" />
        </slot>
      </a-form-item>
      <a-form-item label="优先级">
        <a-select
          v-model:value="node.priority"
          :disabled="readonly"
          :options="Object.entries(taskPriorities).map(([value, label]) => ({ value, label }))"
        />
      </a-form-item>
      <TaskScheduleFields
        v-model="node.schedule"
        class="task-form-grid__full"
        :readonly="readonly"
        :planned-start="plannedStart"
        :template-editing="templateEditing"
        :has-predecessors="node.predecessorIds.length > 0"
        :has-inherited-predecessors="!!inheritedPredecessorSummary"
        :has-children="
          nodes.some(item => item.id !== node.id && (item.parentId === node.id || (isRoot && !item.parentId)))
        "
        :is-root="isRoot"
      >
        <template v-if="$slots['planned-start']" #planned-start><slot name="planned-start" /></template>
      </TaskScheduleFields>
      <a-form-item
        label="任务顺序"
        class="task-form-grid__full"
        v-if="!isRoot && (nodes.length || node.predecessorIds.length)"
      >
        <div aria-label="任务顺序" aria-live="polite">
          <p v-if="predecessorSummary">等待 {{ predecessorSummary }} 完成后开始</p>
          <p v-else>无单独前置任务，可与同级并行</p>
          <p v-if="inheritedPredecessorSummary" class="task-list__hint">
            受上级顺序约束：还需等待 {{ inheritedPredecessorSummary }} 完成
          </p>
        </div>
      </a-form-item>
      <a-form-item label="任务内容" class="task-form-grid__full">
        <TaskContentField :key="node.id" v-model="node.description" :readonly="readonly" />
      </a-form-item>
    </template>
    <a-collapse
      v-if="section !== 'basic'"
      :active-key="section === 'all' ? optionalOpen : [section]"
      :class="{ 'task-optional-sections--standalone': section !== 'all' }"
      class="task-form-grid__full task-optional-sections"
      @change="changeOptional"
    >
      <a-collapse-panel
        v-if="section === 'all' || section === 'business' || section === 'feedback'"
        :key="section === 'feedback' ? 'feedback' : 'business'"
        :force-render="true"
      >
        <template #header>
          <span class="task-optional-sections__title">业务关联（可选）</span>
          <span class="task-optional-sections__summary" :title="businessSummary">{{ businessSummary }}</span>
        </template>
        <div class="task-form-grid__full"><slot name="business-context" /></div>
        <a-alert
          v-if="inheritedData"
          class="task-form-grid__full"
          type="info"
          message="继承总任务各业务关联项的数据范围与授权，无需重复配置；历史关联继续保留原有范围，不扩大权限。"
        />
        <div v-if="!inheritedData" class="task-form-grid__full task-business-options task-panel">
          <p v-if="dataPolicy && !templateEditing" class="task-list__hint">
            关联项目仅确定任务归属；若要办理项目资料，请选择对应项目的业务资源。其他业务资源不会自动获得项目数据。
          </p>
          <TaskEntriesEditor
            v-model="workEntries"
            v-model:total-minutes="node.effectiveWorkMinutes"
            v-model:total-mode="node.workTotalMode"
            :show-work-total="isRoot"
            :budget-entries="isRoot ? taskWorkBudgetEntries(node, nodes) : undefined"
            :total-readonly="readonly || (dataReadonly && !workAdjustment)"
            :nodes="entryNodes"
            :hierarchy-root-id="hierarchyRootId"
            :current-id="node.id"
            :is-root="isRoot"
            :root-entries="rootEntries"
            :readonly="dataReadonly"
            :work-adjustment="workAdjustment && !readonly"
            :inherited="!isRoot && !!rootEntries?.length"
            :unified="!!dataPolicy"
            :legacy-policy="dataPolicy"
            :binding-locked="bindingLocked"
          />
          <slot name="business-record" />
          <a-form-item v-if="!dataPolicy && nodes.length" label="节点业务数据" class="task-form-grid__full">
            <a-radio-group v-model:value="node.sharing.mode" :disabled="dataReadonly">
              <a-radio value="INDEPENDENT">独立记录</a-radio>
              <a-radio value="SHARED">共享前序记录</a-radio>
            </a-radio-group>
          </a-form-item>
          <template v-if="!dataPolicy && node.sharing.mode === 'SHARED'">
            <a-form-item label="共享来源" required>
              <a-select
                v-model:value="node.sharing.sourceNodeId"
                :disabled="dataReadonly"
                :options="choices"
                placeholder="选择合法前序节点"
              />
            </a-form-item>
            <a-form-item label="可补充字段">
              <a-select
                v-model:value="node.sharing.writableFieldIds"
                :disabled="dataReadonly"
                mode="multiple"
                :options="fields"
                placeholder="默认全部只读"
              />
            </a-form-item>
            <p class="task-list__hint task-form-grid__full">共享不自动授权，实际读写范围仍由应用和字段权限控制。</p>
          </template>
        </div>
      </a-collapse-panel>
    </a-collapse>
  </div>
</template>

<style scoped>
.task-business-options {
  display: grid;
  gap: var(--spacing-lg);
}
.task-business-options > p {
  margin: 0;
}
.task-optional-sections--standalone,
.task-optional-sections--standalone :deep(> .ant-collapse-item),
.task-optional-sections--standalone :deep(> .ant-collapse-item > .ant-collapse-content) {
  border: 0;
  background: transparent;
}
.task-optional-sections--standalone :deep(> .ant-collapse-item > .ant-collapse-header) {
  display: none;
}
.task-optional-sections--standalone :deep(> .ant-collapse-item > .ant-collapse-content > .ant-collapse-content-box) {
  padding: 0;
}
.task-optional-sections__title {
  font-weight: 500;
}
.task-optional-sections__summary {
  margin-inline-start: 12px;
  color: var(--text-secondary, #64748b);
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
