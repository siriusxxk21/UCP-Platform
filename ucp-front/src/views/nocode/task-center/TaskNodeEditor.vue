<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, EnterOutlined, MoreOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import type { TaskMember, TaskNodeInput, TaskRow } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import {
  newTaskNode,
  newAutoTaskNode,
  taskTree,
  taskDate,
  taskAssignmentLabel,
  taskPriorities,
  taskStates,
  taskDisplayState,
  taskStructureError
} from '@/nocode/task-center'
import {
  arrangeTaskSibling,
  moveTaskNode,
  planTaskRemoval,
  orderTaskSiblings,
  taskDependencyIssue,
  taskLocation
} from '@/nocode/task-arrangement'
import { taskHierarchy } from '@/nocode/task-hierarchy'
import TaskNodeFields from './TaskNodeFields.vue'
import TaskDag from './TaskDag.vue'
import TaskGraphSurface from './TaskGraphSurface.vue'
import TaskHierarchyCell from './TaskHierarchyCell.vue'
import TaskAssignmentFields from './TaskAssignmentFields.vue'
import TaskAcceptanceFields from './TaskAcceptanceFields.vue'
import TaskScheduleFields from './TaskScheduleFields.vue'
import TaskEditableCell from './TaskEditableCell.vue'
import TaskContentField from './TaskContentField.vue'
import { taskScheduleSummary } from '@/nocode/task-schedule-summary'
import type { TaskDagRuntimeInfo } from '@/nocode/task-dag-summary'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
const { confirm, confirmDiscard } = useTaskConfirmation()
const nodes = defineModel<TaskNodeInput[]>({ required: true })
const view = defineModel<'list' | 'graph'>('view', { default: 'list' })
const selectedId = defineModel<string>('selectedId')
const emit = defineEmits<{ 'update:root': [node: TaskNodeInput] }>()
const props = withDefaults(
  defineProps<{
    members: TaskMember[]
    rootId?: string
    root?: TaskNodeInput
    plannedStart?: string | null
    templateEditing?: boolean
    autoSchedule?: boolean
    runtimeNodes?: Array<TaskDagRuntimeInfo & Pick<TaskRow, 'status'> & Partial<Pick<TaskRow, 'acceptorName'>>>
    referenceIds?: string[]
    rootEntries?: TaskWorkEntryConfig[]
    frozenIds?: string[]
    closedIds?: string[]
    readonly?: boolean
    inlineConfiguration?: boolean
    externalActions?: boolean
    alignedTable?: boolean
    dataReadonly?: boolean
    workAdjustment?: boolean
    templateInstance?: boolean
    bindingLocked?: boolean
    instanceWorkspace?: boolean
    applicationId?: string | null
  }>(),
  {
    alignedTable: true,
    frozenIds: () => [],
    closedIds: () => [],
    referenceIds: () => []
  }
)
const effectiveRootId = computed(() => props.root?.id || props.rootId)
// 新建时根任务仅作为视图上下文，不写回 nodes，避免服务端重复创建总任务。
const displayNodes = computed(() =>
  props.root
    ? [props.root, ...nodes.value.map(node => ({ ...node, parentId: node.parentId || props.root!.id }))]
    : nodes.value
)
const hierarchy = computed(() =>
  taskHierarchy(displayNodes.value, { draft: !effectiveRootId.value, rootId: effectiveRootId.value })
)
const expandableIds = computed(() =>
  displayNodes.value.filter(node => hierarchy.value.get(node.id)?.childCount).map(node => node.id)
)
const expanded = ref<string[]>([...expandableIds.value]),
  editingId = ref<string | null>(null),
  workspaceExpanded = ref(false)
const graphOpen = computed({ get: () => view.value === 'graph', set: value => (view.value = value ? 'graph' : 'list') })
function openGraph() {
  graphOpen.value = true
}
function expandAll() {
  expanded.value = [...expandableIds.value]
}
function collapseAll() {
  expanded.value = []
}
watch(graphOpen, open => {
  if (open) workspaceExpanded.value = false
})
const localSelectedId = ref(effectiveRootId.value)
const graphSelectedId = computed({
  get: () => selectedId.value || localSelectedId.value,
  set: value => {
    localSelectedId.value = value
    selectedId.value = value
  }
})
const graph = ref<InstanceType<typeof TaskDag>>()
watch(
  graphSelectedId,
  id => {
    const seen = new Set<string>()
    let parent = displayNodes.value.find(node => node.id === id)?.parentId
    while (parent && !seen.has(parent)) {
      seen.add(parent)
      parent = displayNodes.value.find(node => node.id === parent)?.parentId
    }
    expanded.value = [...new Set([...expanded.value, ...seen])]
  },
  { immediate: true }
)
const configurationWidth = ref(Math.min(720, window.innerWidth))
const resizeConfiguration = () => (configurationWidth.value = Math.min(720, window.innerWidth))
onMounted(() => window.addEventListener('resize', resizeConfiguration))
onBeforeUnmount(() => window.removeEventListener('resize', resizeConfiguration))
const batchOpen = ref(false),
  batchParent = ref<string | null>(null),
  batchText = ref(''),
  batchSequential = ref(true),
  batchError = ref('')
const relationError = ref('')
const movingId = ref(''),
  moveParentId = ref<string>(),
  moveError = ref('')
const moveOpen = ref(false)
const moving = computed(() => displayNodes.value.find(node => node.id === movingId.value))
const editingLocation = computed(() =>
  taskLocation(displayNodes.value, displayNodes.value.find(node => node.id === editing.value?.id)?.parentId)
)
function moveResult(id: string, parentId: string) {
  return moveTaskNode(displayNodes.value, id, parentId, {
    rootId: effectiveRootId.value,
    frozenIds: props.frozenIds,
    closedIds: props.closedIds,
    readonly: props.readonly
  })
}
const moveChoices = computed(() =>
  displayNodes.value.map(node => {
    const error = moveResult(movingId.value, node.id).error
    return {
      value: node.id,
      label: taskLocation(displayNodes.value, node.id),
      disabled: !!error,
      title: error || undefined
    }
  })
)
function openMove(id: string) {
  if (isRoot(id) || frozen(id) || closed(id)) return
  const current = displayNodes.value.find(node => node.id === id)
  if (!current) return
  movingId.value = id
  moveParentId.value = current.parentId || effectiveRootId.value
  moveError.value = ''
  moveOpen.value = true
}
function applyMove() {
  if (!moveParentId.value) {
    moveError.value = '请选择移入任务'
    return
  }
  const result = moveResult(movingId.value, moveParentId.value)
  if (result.error) {
    moveError.value = result.error
    return
  }
  if (!result.nodes) return
  nodes.value = result.nodes
    .filter(node => !displayRoot(node.id))
    .map(node => ({
      ...node,
      parentId: isRoot(node.id) ? null : storedParent(node.parentId)
    }))
  expanded.value = [...new Set([...expanded.value, moveParentId.value])]
  graphSelectedId.value = movingId.value
  moveOpen.value = false
}
const addedIds = ref<string[]>([])
watch(expandableIds, (ids, previous) => {
  expanded.value = [
    ...new Set([...expanded.value.filter(id => ids.includes(id)), ...ids.filter(id => !previous.includes(id))])
  ]
})
function toggle(id: string) {
  expanded.value = expanded.value.includes(id) ? expanded.value.filter(value => value !== id) : [...expanded.value, id]
}
const editingHierarchy = computed(() => (editing.value ? hierarchy.value.get(editing.value.id) : undefined))
const addedSnapshots = new Map<string, string>()
const addedRelations = new Map<string, Array<{ id: string; before: string[]; after: string[] }>>()
const nameInputs = new Map<string, { focus: () => void }>()
function rememberInput(id: string, input: Element | ComponentPublicInstance | null) {
  if (input && 'focus' in input && typeof input.focus === 'function') nameInputs.set(id, input as { focus: () => void })
  else nameInputs.delete(id)
}
const graphNodes = computed(() =>
  displayNodes.value.map(node => ({
    ...node,
    // 图卡负责拼接人员安排来源；此处只传姓名，不能把摘要再次当作姓名。
    assigneeName: props.referenceIds.includes(node.id)
      ? props.runtimeNodes?.find(item => item.id === node.id)?.assigneeName
      : props.members.find(member => String(member.id) === String(node.assigneeId))?.name,
    referenceOnly: props.referenceIds.includes(node.id)
  }))
)
const activeCell = ref('')
const editingSection = ref<'all' | 'schedule' | 'business' | 'feedback'>('all')
const configurationTitle = computed(() => {
  if (editingSection.value === 'schedule') return frozen(editingId.value || '') ? '查看时间安排' : '设置时间安排'
  if (editingSection.value === 'business') return '业务关联'
  if (editingSection.value === 'feedback') return '业务关联'
  return frozen(editingId.value || '') ? '查看任务节点' : '配置任务节点'
})
const cellKey = (id: string, field: string) => `${id}:${field}`
function setCell(id: string, field: string, open: boolean) {
  const key = cellKey(id, field)
  if (open) activeCell.value = key
  else if (activeCell.value === key) activeCell.value = ''
}
function finishNewName(node?: TaskNodeInput) {
  if (props.inlineConfiguration && node?.title.trim() && activeCell.value === cellKey(node.id, 'title')) {
    activeCell.value = ''
  }
}
const scheduleSummaries = computed(
  () =>
    new Map(
      displayNodes.value.map(node => [node.id, taskScheduleSummary(node, displayNodes.value, props.plannedStart)])
    )
)
const titleWidth = computed(
  () =>
    (props.inlineConfiguration ? (props.alignedTable ? 320 : 260) : 310) +
    Math.max(0, ...[...hierarchy.value.values()].map(item => item.depth - 2)) * 20
)
const instanceColumnWidths: Record<string, number> = {
  title: 280,
  description: 150,
  status: 90,
  assignee: 175,
  acceptor: 135,
  dependencies: 160,
  schedule: 175,
  priority: 95
}
const columns = computed(() =>
  [
    {
      title: '任务层级 / 名称',
      key: 'title',
      width: titleWidth.value,
      align: 'left' as const,
      fixed: props.inlineConfiguration ? ('left' as const) : undefined
    },
    ...(props.inlineConfiguration
      ? [{ title: '任务内容', key: 'description', width: 200, align: 'left' as const }]
      : []),
    ...(props.instanceWorkspace ? [{ title: '执行状态', key: 'status', width: 115, align: 'left' as const }] : []),
    { title: '负责人', key: 'assignee', width: props.inlineConfiguration ? 195 : 150, align: 'left' as const },
    ...(props.inlineConfiguration
      ? [{ title: '验收人', key: 'acceptor', width: props.alignedTable ? 165 : 145, align: 'left' as const }]
      : []),
    { title: '任务顺序', key: 'dependencies', width: props.inlineConfiguration ? 180 : 220, align: 'left' as const },
    {
      title: props.templateEditing ? '时间规则 / 工期' : '预计时间',
      key: 'schedule',
      width: props.inlineConfiguration ? 235 : 160,
      align: 'left' as const
    },
    ...(props.inlineConfiguration
      ? [{ title: '优先级', key: 'priority', width: props.alignedTable ? 110 : 100, align: 'left' as const }]
      : []),
    {
      title: '操作',
      key: 'actions',
      width: props.inlineConfiguration ? (props.instanceWorkspace && props.readonly ? 100 : 64) : 220,
      fixed: 'right' as const,
      align:
        props.inlineConfiguration && !(props.instanceWorkspace && props.readonly)
          ? ('center' as const)
          : ('left' as const)
    }
  ].map(column => ({
    ...column,
    width: props.instanceWorkspace ? instanceColumnWidths[column.key] || column.width : column.width
  }))
)
const tree = computed(() =>
  taskTree(displayNodes.value.map(node => ({ ...node, hierarchy: hierarchy.value.get(node.id) })))
)
const source = (id: string) => (props.root?.id === id ? props.root : nodes.value.find(n => n.id === id)!)
const displayRoot = (id: string) => props.root?.id === id
function acceptanceSummary(node: TaskNodeInput) {
  if (!node.acceptorId) return '无需验收'
  return (
    props.members.find(member => String(member.id) === String(node.acceptorId))?.name ||
    props.runtimeNodes?.find(item => item.id === node.id)?.acceptorName ||
    '已指定验收人'
  )
}
const unifiedData = computed(
  () => !!(props.root?.dataPolicy || nodes.value.find(node => node.id === props.rootId)?.dataPolicy)
)
const isRoot = (id: string) => effectiveRootId.value === id
const frozen = (id: string) => props.readonly || props.referenceIds.includes(id) || props.frozenIds.includes(id)
const closed = (id: string | null | undefined) => props.readonly || (!!id && props.closedIds.includes(id))
function storedParent(parentId: string | null) {
  if (props.root) return parentId === props.root.id ? null : parentId
  return parentId || effectiveRootId.value || null
}
// 配置抽屉编辑同一份本地编排草稿，关闭仅退出配置，不单独向服务端保存。
const editing = computed({
  get: () => (editingId.value ? source(editingId.value) || null : null),
  set: value => {
    if (!value || frozen(value.id)) return
    const current = source(value.id)
    if (!current) return
    const updated = { ...value, parentId: current.parentId }
    if (displayRoot(updated.id)) emit('update:root', updated)
    else nodes.value = nodes.value.map(node => (node.id === updated.id ? updated : node))
  }
})
function makeNode(parentId: string | null, predecessorIds: string[] = []) {
  const root = displayNodes.value.find(item => item.id === effectiveRootId.value)
  const node = (props.templateEditing || props.autoSchedule ? newAutoTaskNode : newTaskNode)(
    storedParent(parentId),
    root
  )
  // 子任务只继承负责人安排；验收属于总任务的最终环节。
  node.acceptorId = null
  const parent = displayNodes.value.find(item => item.id === (parentId || effectiveRootId.value))
  if (parent) {
    node.urgency = parent.urgency
    node.priority = parent.priority
  }
  node.predecessorIds = [...predecessorIds]
  return node
}
async function add(
  parentId: string | null = effectiveRootId.value || null,
  after?: TaskNodeInput,
  predecessorIds: string[] = []
) {
  if (closed(parentId || effectiveRootId.value)) return
  const node = makeNode(parentId, predecessorIds)
  const index = after ? nodes.value.findIndex(item => item.id === after.id) : -1
  nodes.value =
    index < 0 ? [...nodes.value, node] : [...nodes.value.slice(0, index + 1), node, ...nodes.value.slice(index + 1)]
  addedIds.value.push(node.id)
  addedSnapshots.set(node.id, JSON.stringify(node))
  if (parentId) expanded.value = [...new Set([...expanded.value, parentId])]
  graphSelectedId.value = node.id
  if (props.inlineConfiguration) activeCell.value = cellKey(node.id, 'title')
  await nextTick()
  if (!graphOpen.value) nameInputs.get(node.id)?.focus()
}
async function addSibling(node: TaskNodeInput | undefined, next: boolean, successorId?: string) {
  if (!node) return
  if (isRoot(node.id)) return
  if (props.readonly || parentClosed(node)) return
  if (!node.title.trim()) return message.warning('请先填写当前任务名称')
  const added = makeNode(node.parentId)
  const result = arrangeTaskSibling(displayNodes.value, node.id, added, next ? 'NEXT' : 'PARALLEL', {
    rootId: effectiveRootId.value,
    successorId,
    frozenIds: props.frozenIds,
    closedIds: props.closedIds
  })
  if (result.error) {
    relationError.value = result.error
    message.warning(result.error)
    return
  }
  if (!result.nodes) return
  const relations = result.nodes.flatMap(item => {
    const previous = source(item.id)
    return previous && JSON.stringify(previous.predecessorIds) !== JSON.stringify(item.predecessorIds)
      ? [{ id: item.id, before: [...previous.predecessorIds], after: [...item.predecessorIds] }]
      : []
  })
  // 视图根不写入新建载荷；所有受影响后继与新节点一次更新，失败不留半成品。
  const nextNodes = result.nodes
    .filter(item => !displayRoot(item.id))
    .map(item => ({ ...item, parentId: isRoot(item.id) ? null : storedParent(item.parentId) }))
  nodes.value = nextNodes
  addedIds.value.push(added.id)
  addedSnapshots.set(added.id, JSON.stringify(nextNodes.find(item => item.id === added.id)))
  addedRelations.set(added.id, relations)
  relationError.value = ''
  const parentId = node.parentId || effectiveRootId.value
  if (parentId) expanded.value = [...new Set([...expanded.value, parentId])]
  graphSelectedId.value = added.id
  if (props.inlineConfiguration) activeCell.value = cellKey(added.id, 'title')
  await nextTick()
  if (!graphOpen.value) nameInputs.get(added.id)?.focus()
}
function parentClosed(node: TaskNodeInput) {
  return closed(node.parentId || effectiveRootId.value)
}
function nodeLabel(id: string) {
  const node = displayNodes.value.find(item => item.id === id)
  return node ? `${hierarchy.value.get(id)?.outline || ''} ${node.title || '未命名任务'}`.trim() : '未显示的前置任务'
}
function dependencyLabel(id: string) {
  const runtime = props.runtimeNodes?.find(node => node.id === id)
  return runtime?.status === 'COMPLETED' ? `${nodeLabel(id)} · 已完成` : `等待 ${nodeLabel(id)} 完成`
}
function referenceTime(id: string) {
  const node = props.runtimeNodes?.find(item => item.id === id)
  return node?.expectedStart || node?.expectedEnd
    ? `${taskDate(node.expectedStart)} 至 ${taskDate(node.expectedEnd)}`
    : '未安排'
}
function inheritedPredecessors(node: TaskNodeInput) {
  const result = new Set<string>(),
    visited = new Set<string>()
  let parent = node.parentId ? displayNodes.value.find(item => item.id === node.parentId) : undefined
  while (parent && !visited.has(parent.id)) {
    visited.add(parent.id)
    parent.predecessorIds.forEach(id => result.add(id))
    parent = parent.parentId ? displayNodes.value.find(item => item.id === parent?.parentId) : undefined
  }
  return [...result].filter(id => !node.predecessorIds.includes(id))
}
function setPredecessors(id: string, predecessors: string[]): string | null {
  const current = displayNodes.value.find(node => node.id === id)
  if (!current || frozen(id) || closed(id) || isRoot(id)) return '此任务的执行依赖不可修改'
  if (!predecessors.length && current.schedule.mode === 'PREDECESSOR')
    return '请先将预计时间改为“暂不安排”或“指定日期”，再移除最后一项前置任务'
  const issue = taskStructureError(
    displayNodes.value.map(node => (node.id === id ? { ...node, predecessorIds: predecessors } : node))
  )
  if (issue) return issue
  nodes.value = nodes.value.map(node =>
    node.id === id ? { ...node, predecessorIds: [...new Set(predecessors)] } : node
  )
  relationError.value = ''
  return null
}
async function closeBatch() {
  if (await confirmDiscard(!!batchText.value.trim())) batchOpen.value = false
}
function link(fromId: string, toId: string) {
  if (isRoot(fromId) || isRoot(toId)) {
    relationError.value = '总任务代表整件事，请在它包含的步骤之间安排先后'
    return
  }
  const issue = taskDependencyIssue(displayNodes.value, fromId, toId)
  relationError.value = issue || setPredecessors(toId, [...(source(toId)?.predecessorIds || []), fromId]) || ''
}
function unlink(fromId: string, toId: string) {
  relationError.value =
    setPredecessors(
      toId,
      (source(toId)?.predecessorIds || []).filter(id => id !== fromId)
    ) || ''
}
function rename(id: string, title: string) {
  const node = source(id)
  if (!node || frozen(id)) return
  if (displayRoot(id)) emit('update:root', { ...node, title: title.slice(0, 160) })
  else node.title = title.slice(0, 160)
}
function openBatch(parentId: string | null = effectiveRootId.value || null, sequential = true) {
  if (closed(parentId || effectiveRootId.value)) return
  batchParent.value = parentId
  batchSequential.value = sequential
  batchText.value = ''
  batchError.value = ''
  batchOpen.value = true
}
function addBatch() {
  if (closed(batchParent.value || effectiveRootId.value)) return
  const names = batchText.value
    .split(/\r?\n/)
    .map(name => name.trim())
    .filter(Boolean)
  if (!names.length) {
    batchError.value = '每行填写一个任务名称'
    return
  }
  if (names.length > 100 || names.some(name => name.length > 160)) {
    batchError.value = '每次最多添加 100 项，每个名称不超过 160 字'
    return
  }
  const added: TaskNodeInput[] = []
  for (const title of names) {
    const previous = added.at(-1)
    added.push({ ...makeNode(batchParent.value, batchSequential.value && previous ? [previous.id] : []), title })
  }
  nodes.value = [...nodes.value, ...added]
  if (batchParent.value) expanded.value = [...new Set([...expanded.value, batchParent.value])]
  graphSelectedId.value = added[0]?.id
  batchOpen.value = false
}
function orderSteps() {
  if (props.readonly) return
  const ordered = orderTaskSiblings(displayNodes.value)
    .filter(node => !displayRoot(node.id))
    .map(node => source(node.id))
  nodes.value = ordered
  message.success('已按前置关系整理同级任务顺序，包含关系未改变')
}
// 模板页将编排操作放入页签栏，仍由编辑器管理图视图和表格展开状态。
defineExpose({
  captureView: () => graph.value?.captureView(),
  restoreView: (value: Parameters<InstanceType<typeof TaskDag>['restoreView']>[0]) => graph.value?.restoreView(value),
  openGraph,
  expandAll,
  collapseAll,
  orderSteps,
  hasExpandableNodes: computed(() => expandableIds.value.length > 0),
  hasExpandedNodes: computed(() => expanded.value.length > 0)
})
function continueAdding(node: TaskNodeInput) {
  if (!node.title.trim()) return message.warning('请先填写当前任务名称')
  void add(node.parentId)
}
function isEmptyAdded(node: TaskNodeInput) {
  return addedIds.value.includes(node.id) && JSON.stringify(node) === addedSnapshots.get(node.id)
}
function cancelBlank(node: TaskNodeInput) {
  if (frozen(node.id) || isRoot(node.id)) return
  if (!isEmptyAdded(node)) return
  const relations = addedRelations.get(node.id) || []
  if (
    relations.some(
      relation =>
        frozen(relation.id) ||
        closed(relation.id) ||
        JSON.stringify(source(relation.id)?.predecessorIds) !== JSON.stringify(relation.after)
    )
  ) {
    message.warning('后续步骤已变更，不能自动恢复原连线，请在图上调整')
    return
  }
  if (
    nodes.value.some(
      item =>
        item.parentId === node.id ||
        (item.predecessorIds.includes(node.id) && !relations.some(relation => relation.id === item.id)) ||
        item.sharing.sourceNodeId === node.id ||
        item.entries?.some(entry => entry.sourceNodeId === node.id)
    )
  ) {
    message.warning('此节点已有子任务或引用，请先处理后再移除')
    return
  }
  nodes.value = nodes.value
    .filter(item => item.id !== node.id)
    .map(item => {
      const relation = relations.find(current => current.id === item.id)
      return relation ? { ...item, predecessorIds: [...relation.before] } : item
    })
  addedIds.value = addedIds.value.filter(id => id !== node.id)
  addedSnapshots.delete(node.id)
  addedRelations.delete(node.id)
}
function escapeNewName(node: TaskNodeInput) {
  cancelBlank(node)
  finishNewName(node)
}
function configure(node: TaskNodeInput, section: typeof editingSection.value = 'all') {
  if (!node) return
  activeCell.value = ''
  editingSection.value = section
  editingId.value = node.id
  graphSelectedId.value = node.id
}
function selectNode(id: string) {
  graphSelectedId.value = id
  // 只读图没有配置工具栏，沿用点击查看；可编辑图仅选中，避免阻断连线和拆分。
  if (props.readonly && !props.instanceWorkspace) configure(source(id))
}
function closeConfiguration() {
  editingId.value = null
}
async function remove(id: string) {
  if (isRoot(id) || frozen(id) || closed(id)) return
  const options = {
    rootId: effectiveRootId.value,
    frozenIds: props.frozenIds,
    closedIds: props.closedIds,
    readonly: props.readonly
  }
  const result = planTaskRemoval(displayNodes.value, id, options)
  if (result.error) {
    message.warning(result.error)
    return
  }
  if (!result.nodes) return
  const before = JSON.stringify([displayNodes.value, options])
  const title = source(id)?.title || '未命名任务'
  const content = `将删除“${title}”${result.removedIds.length > 1 ? `及其 ${result.removedIds.length - 1} 个下级任务` : ''}，共 ${result.removedIds.length} 项，移除 ${result.removedEdges} 条前置连线。不会自动连接前后步骤；后续任务的等待条件可能减少。仅修改当前编排，保存前不影响已保存内容。`
  if (!(await confirm('删除任务', content, '确认删除'))) return
  if (
    before !==
    JSON.stringify([
      displayNodes.value,
      {
        rootId: effectiveRootId.value,
        frozenIds: props.frozenIds,
        closedIds: props.closedIds,
        readonly: props.readonly
      }
    ])
  ) {
    message.warning('任务或执行状态已变化，请重新核对后删除')
    return
  }
  nodes.value = result.nodes
    .filter(node => !displayRoot(node.id))
    .map(node => ({ ...node, parentId: isRoot(node.id) ? null : storedParent(node.parentId) }))
  for (const key of result.removedIds) {
    addedSnapshots.delete(key)
    addedRelations.delete(key)
  }
  addedIds.value = addedIds.value.filter(key => !result.removedIds.includes(key))
  if (graphSelectedId.value && result.removedIds.includes(graphSelectedId.value))
    graphSelectedId.value = effectiveRootId.value
}
</script>
<template>
  <TaskGraphSurface :open="graphOpen" :readonly="readonly" :inline="instanceWorkspace" @close="graphOpen = false">
    <section
      class="task-arrangement-workspace"
      :class="{
        'task-arrangement-workspace--expanded': workspaceExpanded,
        'task-arrangement-workspace--inline': inlineConfiguration,
        'task-arrangement-workspace--aligned': inlineConfiguration && alignedTable
      }"
      aria-label="任务编排工作区"
      @keydown.esc="workspaceExpanded = false"
    >
      <p v-if="!inlineConfiguration" class="task-list__hint">
        拆分子任务用于细分工作，默认不设先后；添加下一步用于安排先后顺序。所有子任务均属于当前总任务。
      </p>
      <div class="task-arrangement-workspace__body">
        <div class="task-arrangement-workspace__main">
          <OsTablePage
            v-show="!graphOpen"
            class="nocode-embedded-table task-node-editor"
            :columns="columns"
            :data-source="tree"
            :pagination="false"
            :expanded-row-keys="expanded"
            :show-index="false"
            :scroll="{ x: columns.reduce((total, column) => total + column.width, 0) }"
            :custom-row="
              record => ({
                class: [
                  !record.parentId ? 'task-node-editor__row--top' : 'task-node-editor__row--child',
                  instanceWorkspace && record.id === graphSelectedId ? 'task-node-editor__row--selected' : ''
                ],
                'aria-selected': instanceWorkspace ? record.id === graphSelectedId : undefined,
                onClick: instanceWorkspace ? () => selectNode(record.id) : undefined
              })
            "
            @expand="(open, row) => (expanded = open ? [...expanded, row.id] : expanded.filter(id => id !== row.id))"
          >
            <template v-if="!inlineConfiguration" #title>任务编排</template>
            <template v-if="!externalActions" #actions>
              <a-space align="center" wrap>
                <a-button @click="openGraph">图上编排</a-button>
                <template v-if="inlineConfiguration">
                  <a-dropdown v-if="expandableIds.length" :trigger="['click']">
                    <a-button>表格操作</a-button>
                    <template #overlay>
                      <a-menu>
                        <a-menu-item :disabled="!expandableIds.length" @click="expandAll">展开全部</a-menu-item>
                        <a-menu-item :disabled="!expanded.length" @click="collapseAll">收起全部</a-menu-item>
                        <a-menu-item :disabled="readonly" @click="orderSteps">整理显示</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </template>
                <template v-else>
                  <a-button @click="workspaceExpanded = !workspaceExpanded">
                    {{ workspaceExpanded ? '收起工作区' : '展开工作区' }}
                  </a-button>
                  <a-button
                    :disabled="!expandableIds.length || expandableIds.every(id => expanded.includes(id))"
                    @click="expandAll"
                  >
                    展开全部
                  </a-button>
                  <a-button :disabled="!expanded.length" @click="collapseAll">收起全部</a-button>
                  <a-button :disabled="readonly" @click="orderSteps">整理显示</a-button>
                  <a-button :disabled="closed(effectiveRootId)" @click="openBatch()">添加连续步骤</a-button>
                  <a-button :disabled="closed(effectiveRootId)" @click="add()">拆分子任务</a-button>
                </template>
              </a-space>
            </template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'">
                <TaskHierarchyCell
                  v-if="record.hierarchy"
                  :item="record.hierarchy"
                  :show-outline="false"
                  :show-child-count="false"
                  :personal="false"
                  :expandable="!!record.children?.length"
                  :expanded="expanded.includes(record.id)"
                  :toggle-label="`${expanded.includes(record.id) ? '收起' : '展开'}：${record.title || '未命名任务'}`"
                  compact
                  @toggle="toggle(record.id)"
                >
                  <template v-if="inlineConfiguration" #branch-actions>
                    <slot v-if="instanceWorkspace && readonly" name="runtime-branch-actions" :node="record" />
                    <div v-else-if="!readonly" class="task-node-editor__branch-actions" @click.stop>
                      <a-tooltip :title="closed(record.id) ? '任务已结束，不能拆分子任务' : '拆分子任务'">
                        <a-button
                          type="text"
                          size="small"
                          :aria-label="`拆分子任务：${record.title || '未命名任务'}`"
                          :disabled="closed(record.id)"
                          @click.stop="add(record.id)"
                        >
                          <PlusOutlined />
                        </a-button>
                      </a-tooltip>
                      <a-tooltip
                        :title="
                          isRoot(record.id)
                            ? '总任务是整件事，请在子任务上添加下一步'
                            : parentClosed(record)
                              ? '上级任务已结束，不能添加下一步'
                              : '添加下一步（同级）'
                        "
                      >
                        <a-button
                          type="text"
                          size="small"
                          :aria-label="`添加下一步：${record.title || '未命名任务'}`"
                          :disabled="isRoot(record.id) || parentClosed(record)"
                          @click.stop="addSibling(source(record.id), true)"
                        >
                          <EnterOutlined />
                        </a-button>
                      </a-tooltip>
                    </div>
                  </template>
                  <TaskEditableCell
                    v-if="
                      inlineConfiguration &&
                      !(addedIds.includes(record.id) && (!record.title || activeCell === cellKey(record.id, 'title')))
                    "
                    v-slot="{ close }"
                    :class="{ 'task-node-editor__empty': !record.title }"
                    :model-value="activeCell === cellKey(record.id, 'title')"
                    :label="`任务名称 ${record.hierarchy.outline}`"
                    :summary="record.title || '填写任务名称'"
                    :readonly="frozen(record.id)"
                    @update:model-value="setCell(record.id, 'title', $event)"
                  >
                    <a-input
                      v-model:value="source(record.id).title"
                      :aria-label="`${record.hierarchy.label}名称 ${record.hierarchy.outline}`"
                      :maxlength="160"
                      @keydown.enter.prevent="close"
                    />
                  </TaskEditableCell>
                  <a-input
                    v-else-if="!frozen(record.id) && (!displayRoot(record.id) || inlineConfiguration)"
                    v-model:value="source(record.id).title"
                    class="task-node-editor__name"
                    :aria-label="`${record.hierarchy.label}名称 ${record.hierarchy.outline}`"
                    placeholder="任务名称，直接在列表中填写"
                    :maxlength="160"
                    :ref="(input: Element | ComponentPublicInstance | null) => rememberInput(record.id, input)"
                    @press-enter="isRoot(record.id) ? add(record.id) : continueAdding(source(record.id))"
                    @blur="finishNewName(source(record.id))"
                    @keydown.esc="escapeNewName(source(record.id))"
                  />
                  <span v-else class="task-node-editor__frozen">
                    {{ record.title || '未命名总任务' }}
                    <a-tag v-if="!readonly">{{ displayRoot(record.id) ? '在上方编辑总任务' : '配置已冻结' }}</a-tag>
                  </span>
                </TaskHierarchyCell>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag>
                  {{
                    taskStates[
                      taskDisplayState(runtimeNodes?.find(node => node.id === record.id) || { status: 'PENDING' })
                    ]
                  }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'description'">
                <span v-if="referenceIds.includes(record.id)" class="task-list__hint">仅查看编排概要</span>
                <TaskContentField
                  v-else
                  v-model="source(record.id).description"
                  :label="`任务内容 ${record.title || record.hierarchy.label}`"
                  :readonly="frozen(record.id)"
                />
              </template>
              <template v-else-if="column.key === 'assignee'">
                <span v-if="referenceIds.includes(record.id)">
                  {{ runtimeNodes?.find(node => node.id === record.id)?.assigneeName || '未指定负责人' }}
                </span>
                <slot v-else name="assignment" :node="source(record.id)" :readonly="frozen(record.id)">
                  <TaskAssignmentFields
                    v-if="inlineConfiguration"
                    :model-value="source(record.id)"
                    :members="members"
                    :allow-follow="!isRoot(record.id)"
                    :root-assignee-id="displayNodes.find(item => item.id === effectiveRootId)?.assigneeId"
                    :readonly="frozen(record.id)"
                    compact
                    table-editing
                    :quiet-supplement="alignedTable"
                    :inline-candidates="alignedTable"
                  />
                  <span v-else-if="displayRoot(record.id)">
                    {{
                      taskAssignmentLabel(record, members.find(m => String(m.id) === String(record.assigneeId))?.name)
                    }}
                  </span>
                  <TaskAssignmentFields
                    v-else
                    :model-value="source(record.id)"
                    :members="members"
                    :allow-follow="!isRoot(record.id)"
                    :root-assignee-id="displayNodes.find(item => item.id === effectiveRootId)?.assigneeId"
                    :readonly="frozen(record.id)"
                    compact
                  />
                </slot>
              </template>
              <template v-else-if="column.key === 'acceptor'">
                <span v-if="referenceIds.includes(record.id)" class="task-list__hint">—</span>
                <slot
                  v-else-if="isRoot(record.id)"
                  name="acceptance"
                  :node="source(record.id)"
                  :readonly="frozen(record.id)"
                >
                  <TaskAcceptanceFields
                    :model-value="source(record.id)"
                    :members="members"
                    :readonly="frozen(record.id)"
                    :acceptor-name="acceptanceSummary(record)"
                    compact
                    table-editing
                  />
                </slot>
                <span v-else class="task-list__hint" title="仅总任务可设置验收人">—</span>
              </template>
              <template v-else-if="column.key === 'dependencies'">
                <span v-if="isRoot(record.id)" class="task-list__hint">整件任务 · 下级完成后汇总</span>
                <div v-else class="task-node-editor__dependencies">
                  <span v-for="id in record.predecessorIds" :key="id">{{ dependencyLabel(id) }}</span>
                  <span v-if="inheritedPredecessors(record).length" class="task-list__hint">
                    随上级：{{ inheritedPredecessors(record).map(dependencyLabel).join('、') }}
                  </span>
                  <span v-else-if="!record.predecessorIds.length" class="task-list__hint">可从开始处开展</span>
                </div>
              </template>
              <template v-else-if="column.key === 'schedule'">
                <span v-if="referenceIds.includes(record.id)">{{ referenceTime(record.id) }}</span>
                <div v-else-if="inlineConfiguration" class="task-node-editor__schedule">
                  <TaskEditableCell
                    external-editor
                    :model-value="editingId === record.id && editingSection === 'schedule'"
                    label="时间安排"
                    :summary="
                      [
                        scheduleSummaries.get(record.id)?.primary || '暂不安排',
                        alignedTable ? scheduleSummaries.get(record.id)?.secondary : undefined
                      ]
                        .filter(Boolean)
                        .join(' · ')
                    "
                    :secondary="alignedTable ? undefined : scheduleSummaries.get(record.id)?.secondary"
                    :hint="scheduleSummaries.get(record.id)?.hint"
                    :readonly="frozen(record.id)"
                    @update:model-value="$event && configure(source(record.id), 'schedule')"
                  />
                </div>
                <template v-if="!inlineConfiguration && !referenceIds.includes(record.id)">
                  <span :title="scheduleSummaries.get(record.id)?.hint">
                    {{ scheduleSummaries.get(record.id)?.primary }}
                  </span>
                  <div v-if="scheduleSummaries.get(record.id)?.secondary" class="task-list__hint">
                    {{ scheduleSummaries.get(record.id)?.secondary }}
                  </div>
                </template>
              </template>
              <template v-else-if="column.key === 'priority'">
                <span v-if="referenceIds.includes(record.id)" class="task-list__hint">—</span>
                <a-select
                  v-else
                  v-model:value="source(record.id).priority"
                  class="task-node-editor__level-select"
                  :disabled="frozen(record.id)"
                  aria-label="优先级"
                  :options="Object.entries(taskPriorities).map(([value, label]) => ({ value, label }))"
                />
              </template>
              <template v-else-if="column.key === 'actions'">
                <slot v-if="instanceWorkspace && readonly" name="runtime-actions" :node="record" />
                <div v-else-if="inlineConfiguration" class="task-node-editor__row-actions">
                  <a-dropdown :trigger="['click']">
                    <a-button
                      type="text"
                      class="task-node-editor__more"
                      :aria-label="`更多操作：${record.title || '未命名任务'}`"
                      title="更多操作"
                    >
                      <MoreOutlined />
                    </a-button>
                    <template #overlay>
                      <a-menu>
                        <a-menu-item :disabled="closed(record.id)" @click="openBatch(record.id, false)">
                          批量拆分
                        </a-menu-item>
                        <a-menu-item :disabled="closed(record.id)" @click="openBatch(record.id, true)">
                          添加连续下级
                        </a-menu-item>
                        <a-menu-item
                          v-if="!displayRoot(record.id) && isEmptyAdded(source(record.id))"
                          @click="cancelBlank(source(record.id))"
                        >
                          取消新增
                        </a-menu-item>
                        <a-menu-item
                          v-if="!isRoot(record.id)"
                          :disabled="parentClosed(record)"
                          @click="addSibling(source(record.id), false)"
                        >
                          添加并行任务
                        </a-menu-item>
                        <a-menu-item v-if="!unifiedData" @click="configure(source(record.id), 'business')">
                          业务关联
                        </a-menu-item>
                        <a-menu-item
                          v-if="!isRoot(record.id)"
                          :disabled="frozen(record.id) || closed(record.id)"
                          @click="openMove(record.id)"
                        >
                          移动到其他任务下
                        </a-menu-item>
                        <a-menu-item
                          v-if="!isRoot(record.id)"
                          :disabled="frozen(record.id) || closed(record.id)"
                          danger
                          @click="remove(record.id)"
                        >
                          移除任务
                        </a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </div>
                <div v-else class="nocode-table-actions task-node-editor__actions">
                  <template v-if="!isRoot(record.id)">
                    <a-button type="link" :disabled="parentClosed(record)" @click="addSibling(source(record.id), true)">
                      添加下一步
                    </a-button>
                  </template>
                  <a-button type="link" :disabled="closed(record.id)" @click="add(record.id)">拆分子任务</a-button>
                  <a-button v-if="!displayRoot(record.id)" type="link" @click="configure(record)">
                    {{ frozen(record.id) ? '查看' : '配置' }}
                  </a-button>
                  <a-dropdown :trigger="['click']">
                    <a-button type="link" :aria-label="`更多操作：${record.title || '未命名任务'}`">更多</a-button>
                    <template #overlay>
                      <a-menu>
                        <a-menu-item
                          v-if="!isRoot(record.id)"
                          :disabled="parentClosed(record)"
                          @click="addSibling(source(record.id), false)"
                        >
                          添加并行任务
                        </a-menu-item>
                        <a-menu-item :disabled="closed(record.id)" @click="openBatch(record.id, false)">
                          批量拆分子任务
                        </a-menu-item>
                        <a-menu-item :disabled="closed(record.id)" @click="openBatch(record.id, true)">
                          添加下级连续步骤
                        </a-menu-item>
                        <a-menu-item
                          v-if="!isRoot(record.id)"
                          :disabled="frozen(record.id) || closed(record.id)"
                          @click="openMove(record.id)"
                        >
                          移动到其他任务下
                        </a-menu-item>
                        <a-menu-item
                          v-if="!isRoot(record.id)"
                          :disabled="frozen(record.id)"
                          danger
                          @click="remove(record.id)"
                        >
                          移除任务
                        </a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                  <a-button
                    v-if="!displayRoot(record.id) && isEmptyAdded(source(record.id))"
                    type="link"
                    @click="cancelBlank(source(record.id))"
                  >
                    取消新增
                  </a-button>
                </div>
              </template>
            </template>
          </OsTablePage>
          <template v-if="graphOpen">
            <a-alert
              v-if="relationError"
              type="error"
              :message="relationError"
              show-icon
              closable
              @close="relationError = ''"
            />
            <TaskDag
              ref="graph"
              :preserve-view="instanceWorkspace"
              :nodes="graphNodes"
              :current-id="graphSelectedId"
              :editable="!readonly"
              :draft="!effectiveRootId"
              :root-id="effectiveRootId"
              :context="templateEditing ? 'template' : runtimeNodes ? 'instance' : 'draft'"
              :planned-start="plannedStart"
              :runtime-nodes="runtimeNodes"
              :members="members"
              :frozen-ids="readonly ? displayNodes.map(item => item.id) : frozenIds"
              :closed-ids="readonly ? displayNodes.map(item => item.id) : closedIds"
              @select="selectNode"
              @rename="rename"
              @link="link"
              @unlink="unlink"
              @insert="(fromId, toId) => addSibling(source(fromId), true, toId)"
              @add-child="id => add(id)"
              @add-next="id => addSibling(source(id), true)"
              @add-parallel="id => addSibling(source(id), false)"
              @batch-split="id => openBatch(id, false)"
              @continuous-children="id => openBatch(id, true)"
              @configure="id => configure(source(id))"
              @move="openMove"
              @remove="remove"
            />
          </template>
          <p v-if="root?.dataPolicy || nodes.find(node => node.id === rootId)?.dataPolicy" class="task-list__hint">
            所有下级任务继承总任务的数据授权。
          </p>
        </div>
      </div>
    </section>
    <OsModalForm
      v-if="editing"
      :open="true"
      :title="configurationTitle"
      display-mode="drawer"
      maximizable
      :width="configurationWidth"
      :wrap-form="false"
      :allow-switch-display="false"
      :resizable="false"
      :mask-closable="false"
      @cancel="closeConfiguration"
    >
      <template #formItems>
        <section class="task-node-editor__configuration" aria-label="任务节点属性">
          <p class="task-list__hint">
            {{ frozen(editing.id) ? '当前节点仅供查看。' : '修改保留在当前编排草稿中，返回原页面统一保存后生效。' }}
          </p>
          <!-- 独立表单隔离外层布局；关闭抽屉不调用 resetFields，避免回退同一份草稿。 -->
          <a-form layout="vertical" :model="editing">
            <div v-if="editingHierarchy" class="task-node-editor__location">
              <strong>{{ editingHierarchy.label }} · {{ editing.title || '未命名任务' }}</strong>
              <span v-if="editingLocation" aria-label="所属位置">所属位置：{{ editingLocation }}</span>
            </div>
            <template v-if="editingSection === 'schedule'">
              <TaskScheduleFields
                v-model="editing.schedule"
                :template-editing="templateEditing"
                :readonly="frozen(editing.id)"
                :has-predecessors="editing.predecessorIds.length > 0"
                :has-inherited-predecessors="inheritedPredecessors(editing).length > 0"
                :has-children="!!hierarchy.get(editing.id)?.childCount"
                :is-root="isRoot(editing.id)"
                :planned-start="plannedStart"
              />
            </template>
            <TaskNodeFields
              v-else
              v-model="editing"
              :nodes="displayNodes"
              :members="members"
              :is-root="editing.id === effectiveRootId"
              :hierarchy-root-id="effectiveRootId"
              :planned-start="plannedStart"
              :template-editing="templateEditing"
              :section="
                editingSection !== 'all' ? editingSection : inlineConfiguration && unifiedData ? 'basic' : 'all'
              "
              :root-entries="rootEntries || nodes.find(node => node.id === rootId)?.entries || []"
              :root-data-policy="root?.dataPolicy || nodes.find(node => node.id === rootId)?.dataPolicy"
              :readonly="frozen(editing.id)"
              :data-readonly="dataReadonly || (templateInstance && editing.id !== effectiveRootId)"
              :work-adjustment="workAdjustment && !frozen(editing.id)"
              :binding-locked="bindingLocked && editing.id === effectiveRootId"
              :application-id="applicationId"
            >
              <template v-if="$slots.assignment" #assignment="slotProps">
                <slot name="assignment" v-bind="slotProps" />
              </template>
              <template v-if="$slots.acceptance" #acceptance="slotProps">
                <slot name="acceptance" v-bind="slotProps" />
              </template>
            </TaskNodeFields>
          </a-form>
        </section>
      </template>
      <template #footer>
        <a-button type="primary" @click="closeConfiguration">返回编排</a-button>
      </template>
    </OsModalForm>
  </TaskGraphSurface>
  <OsModalForm
    :open="batchOpen"
    :title="batchSequential ? '添加连续步骤' : '批量拆分子任务'"
    :width="680"
    :allow-switch-display="false"
    layout="vertical"
    ok-text="添加到编排"
    @ok="addBatch"
    @cancel="closeBatch"
  >
    <template #formItems>
      <p>
        添加到：
        <strong>{{ batchParent ? nodeLabel(batchParent) : '当前任务' }}</strong>
      </p>
      <a-form-item label="任务名称（每行一个）">
        <a-textarea
          v-model:value="batchText"
          :rows="7"
          placeholder="准备材料&#10;现场施工&#10;完工验收"
          aria-label="批量任务名称"
        />
      </a-form-item>
      <p class="task-list__hint">
        {{
          batchSequential
            ? '自动连接：第一项 → 第二项 → 第三项。每项仍可继续拆分子任务。'
            : '只建立包含关系，不自动添加前置依赖。'
        }}
      </p>
      <a-alert v-if="batchError" type="error" :message="batchError" show-icon />
    </template>
  </OsModalForm>
  <OsModalForm
    :open="moveOpen"
    title="移动到其他任务下"
    :width="600"
    :allow-switch-display="false"
    layout="vertical"
    ok-text="确认移动"
    @ok="applyMove"
    @cancel="moveOpen = false"
  >
    <template #formItems>
      <p>
        <strong>{{ moving?.title || '未命名任务' }}</strong>
      </p>
      <p>当前所属：{{ taskLocation(displayNodes, moving?.parentId) || '未显示的上级任务' }}</p>
      <a-form-item label="移入任务" required>
        <a-select
          v-model:value="moveParentId"
          :options="moveChoices"
          aria-label="移入任务"
          show-search
          option-filter-prop="label"
          @change="moveError = ''"
        />
      </a-form-item>
      <p v-if="moveParentId">移动后所属：{{ taskLocation(displayNodes, moveParentId) }}</p>
      <p class="task-list__hint">包含的子任务一起移动，原有先后关系不变；不能移入自身、下级或造成循环的位置。</p>
      <a-alert v-if="moveError" :message="moveError" type="error" show-icon />
    </template>
  </OsModalForm>
</template>

<style scoped>
.task-arrangement-workspace {
  min-width: 0;
  container-type: inline-size;
  background: var(--bg-container, #fff);
}
.task-arrangement-workspace--expanded {
  position: fixed;
  inset: 0;
  z-index: 1100;
  padding: var(--spacing-lg);
  overflow: auto;
}
.task-arrangement-workspace--inline {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.task-arrangement-workspace--inline .task-arrangement-workspace__body,
.task-arrangement-workspace--inline .task-arrangement-workspace__main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.task-arrangement-workspace--inline .task-node-editor {
  flex: 1;
  min-height: 0;
}
.task-arrangement-workspace--inline :deep(.os-table-page__table) {
  border: 0;
  box-shadow: none;
}
.task-arrangement-workspace--inline :deep(.os-table-page__table > .ant-card-head),
.task-arrangement-workspace--inline :deep(.os-table-page__table > .ant-card-body) {
  padding-inline: 0;
}
.task-arrangement-workspace__body {
  display: grid;
  gap: var(--spacing-lg);
  min-width: 0;
}
.task-arrangement-workspace__main {
  min-width: 0;
}
.task-node-editor__configuration {
  min-width: 0;
  container-type: inline-size;
}
/* 原生表格仍负责树行与展开状态，层级单元格统一绘制缩进和按钮，避免双缩进和全宽输入换行。 */
.task-node-editor :deep(.ant-table-row-indent),
.task-node-editor :deep(.ant-table-row-expand-icon) {
  display: none;
}
.task-node-editor :deep(.task-node-editor__row--top > td) {
  background: var(--bg-page);
}
.task-node-editor__name {
  width: 100%;
  min-width: 0;
}
.task-node-editor__level-select {
  width: 100%;
}
.task-node-editor :deep(.ant-table-tbody > tr > td) {
  padding-block: var(--spacing-sm);
}
/* 层级标签独占首行，其他列为同一行标签预留位置，辅助说明不会挤动主控件。 */
.task-arrangement-workspace--inline .task-node-editor :deep(.ant-table-tbody > tr.ant-table-row > td) {
  vertical-align: top;
}
.task-arrangement-workspace--inline
  .task-node-editor
  :deep(.ant-table-tbody > tr.ant-table-row > td:not(:first-child)) {
  padding-top: calc(var(--spacing-sm) + var(--control-height-sm) + 2px);
}
.task-arrangement-workspace--inline .task-node-editor :deep(.task-hierarchy__meta) {
  height: var(--control-height-sm);
  flex-wrap: nowrap;
}
.task-arrangement-workspace--inline .task-node-editor :deep(.task-editable-cell),
.task-arrangement-workspace--inline .task-node-editor :deep(.task-editable-cell__editor) {
  min-height: var(--control-height);
}
.task-node-editor__dependencies {
  display: grid;
  justify-items: start;
  gap: var(--spacing-xs);
}
.task-node-editor__dependencies :deep(.ant-btn) {
  height: auto;
  padding: 0;
  white-space: normal;
  text-align: left;
}
.task-node-editor__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  white-space: normal;
}
.task-node-editor__frozen {
  overflow-wrap: anywhere;
}
.task-node-editor__row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
}
.task-node-editor__more {
  width: var(--control-height);
  height: var(--control-height);
  padding: 0;
  color: var(--text-secondary);
}
.task-node-editor__more:hover,
.task-node-editor__more:focus-visible {
  color: var(--brand);
  background: var(--brand-light);
}
.task-node-editor__branch-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  height: var(--control-height);
}
.task-node-editor__branch-actions :deep(.ant-btn) {
  width: var(--control-height-sm);
  height: var(--control-height-sm);
  padding: 0;
  color: var(--text-secondary);
}
.task-node-editor__branch-actions :deep(.ant-btn:hover:not(:disabled)),
.task-node-editor__branch-actions :deep(.ant-btn:focus-visible) {
  color: var(--brand);
  background: var(--brand-light);
}
.task-node-editor__branch-actions :deep(.ant-btn:disabled) {
  color: var(--text-placeholder);
}
.task-node-editor__location {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-lg);
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.task-node-editor__location strong {
  color: var(--text-primary);
}
/* 树节点创建和领取设置都在主行，不再为第二排动作或数量说明预留高度。 */
.task-arrangement-workspace--inline.task-arrangement-workspace--aligned
  .task-node-editor
  :deep(.ant-table-tbody > tr.ant-table-row > td) {
  height: calc(var(--control-height) + 2 * var(--spacing-md));
  padding-block: var(--spacing-md);
  vertical-align: middle;
  background: var(--bg-container, #fff);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__node) {
  align-items: center;
  padding: 0;
  background: transparent;
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__content) {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  column-gap: var(--spacing-sm);
  row-gap: var(--spacing-xs);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__meta) {
  display: contents;
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__label) {
  grid-column: 1;
  grid-row: 1;
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__title) {
  grid-column: 2;
  grid-row: 1;
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__toggle),
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__leaf) {
  margin-top: 0;
  height: var(--control-height);
  line-height: var(--control-height);
  color: var(--text-secondary);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-hierarchy__joint) {
  top: 50%;
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-editable-cell) {
  height: var(--control-height);
  border-color: var(--border);
  background: var(--bg-container, #fff);
  padding-inline: var(--spacing-md);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-editable-cell:hover),
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-editable-cell--active) {
  border-color: var(--brand);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-editable-cell__text) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-node-editor__empty .task-editable-cell__text) {
  color: var(--text-placeholder);
}
.task-arrangement-workspace--aligned .task-node-editor :deep(.task-editable-cell__editor .ant-input) {
  height: var(--control-height);
  min-height: var(--control-height);
  resize: none;
}
.task-node-editor :deep(.ant-table-tbody > tr.task-node-editor__row--selected > td),
.task-node-editor :deep(.ant-table-tbody > tr.task-node-editor__row--selected:hover > td) {
  background: var(--brand-bg, #f0f2ff) !important;
}
</style>
