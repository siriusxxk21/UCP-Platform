<script setup lang="ts">
import { useNocodePlatform } from '@/nocode/platform'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { v4 as uuid } from 'uuid'
import dayjs from 'dayjs'
import { message } from 'ant-design-vue'

import type {
  TaskAction,
  TaskComment,
  TaskDetail,
  TaskMember,
  TaskMaterial,
  TaskEvent,
  TaskRow,
  TaskReadiness,
  TaskUserId
} from '@/types/nocode/task-center'
import {
  taskStates,
  taskPriorities,
  taskTime,
  taskDate,
  taskAssignmentLabel,
  taskAssignmentActionLabel,
  taskNeedsAcceptance,
  taskDisplayState,
  taskPauseExplanation,
  taskResumeExplanation
} from '@/nocode/task-center'
import { taskContextAssignee, taskContextNodes } from '@/nocode/task-context'
import { instancePredecessors } from '@/nocode/task-instance-arrangement'
import { taskInstanceScheduleSummary } from '@/nocode/task-schedule-summary'
import type { TaskDagViewState } from '@/nocode/task-dag-view'
import { cancellationConfirmationRequired, taskCompletionReady } from '@/nocode/task-completion-confirmation'
import { errorMessage } from '@/nocode/data-center'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { taskStartsEarly, taskEarlyStartMessage } from '@/nocode/task-start'
import { useTaskTransitionRecovery } from '@/nocode/task-transition-recovery'
import { useUserStore } from '@/stores/user'
import { hasPermission } from '@/utils/access'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import TaskBusinessForm from './TaskBusinessForm.vue'
import TaskEntryWorkspace from './TaskEntryWorkspace.vue'
import TaskAdjust from './TaskAdjust.vue'
import TaskWorkTimeDialog from './TaskWorkTimeDialog.vue'
import TaskAssignmentDialog from './TaskAssignmentDialog.vue'
import TaskClaimDialog from './TaskClaimDialog.vue'
import TaskClaimEntry from './TaskClaimEntry.vue'
import { canLocateTaskClaim, type TaskClaimLocation } from '@/nocode/task-claim-entry'
import TaskDeleteSubtaskDialog from './TaskDeleteSubtaskDialog.vue'
import TaskReadinessPanel from './TaskReadinessPanel.vue'
import TaskSubmissionMaterial from './TaskSubmissionMaterial.vue'
import TaskContentDisplay from './TaskContentDisplay.vue'
import TaskScheduleNotice from './TaskScheduleNotice.vue'
import TaskWorkflowSource from './TaskWorkflowSource.vue'
import type { WorkflowTaskNodeView } from '@/types/nocode/workflow-task-node'
import { formatEffectiveWorkMinutes } from '@/nocode/task-work-duration'

const { confirmDiscard } = useTaskConfirmation()
const props = defineProps<{
  id: string
  commentId?: string
  initialTab?: 'overview' | 'business' | 'comments' | 'arrangement'
  initialSelectedId?: string
  initialEntryKey?: string
  initialContributionId?: string
  planReadonly?: boolean
  employeeView?: boolean
}>()
const emit = defineEmits<{ close: []; changed: []; select: [id: string] }>()
const resumedEntry = ref<{ taskId: string; entryKey: string; contributionId: string }>()
const platform = useNocodePlatform(),
  api = platform.taskCenter,
  detail = ref<TaskDetail>(),
  loading = ref(false),
  error = ref(''),
  membersError = ref(''),
  membersLoading = ref(false),
  tab = ref('overview'),
  busy = ref(false),
  members = ref<TaskMember[]>([])
const comment = ref(''),
  mentions = ref<TaskUserId[]>([]),
  reply = ref<TaskComment | null>(null),
  commentKey = ref(uuid()),
  commentBusy = ref(false),
  action = ref<TaskAction | null>(null),
  note = ref(''),
  actionKey = ref(uuid()),
  readiness = ref<TaskReadiness | null>(null),
  confirmCancelledChildren = ref(false),
  businessEpoch = ref(0)
const adjustmentForm = ref<InstanceType<typeof TaskAdjust>>()
const deleteTask = ref<TaskRow>()
const claimLocation = ref<TaskClaimLocation>()
const adjustment = computed(() => !!(adjustmentForm.value?.dirty || adjustmentForm.value?.busy))
const arrangementEpoch = ref(0)
const drawerSize = () => Math.max(1, window.innerWidth < 768 ? window.innerWidth : Math.round(window.innerWidth * 0.92))
const drawerWidth = ref(drawerSize())
const resizeDrawer = () => (drawerWidth.value = drawerSize())
onMounted(() => window.addEventListener('resize', resizeDrawer))
onBeforeUnmount(() => window.removeEventListener('resize', resizeDrawer))
const arrangementView = ref<'list' | 'graph'>('list')
const navigationElement = ref<HTMLElement>()
interface TaskVisit {
  id: string
  title: string
  tab: string
  arrangementView: 'list' | 'graph'
  arrangementSelection?: string
  scrollTop: number
  graph?: TaskDagViewState
}
const visits = ref<TaskVisit[]>([])
const navigating = ref(false)
const previousVisit = computed(() => visits.value.at(-1))
async function leaveArrangement() {
  const changed = adjustment.value
  if (adjustmentForm.value && !(await adjustmentForm.value.requestClose())) return false
  if (changed) arrangementEpoch.value++
  return true
}
async function changeTab(key: string | number) {
  if (tab.value === 'arrangement' && key !== 'arrangement' && !(await leaveArrangement())) return
  tab.value = String(key)
}
const selectedId = ref(props.id),
  originId = ref(props.id)
const transitionRecovery = useTaskTransitionRecovery(api, () => selectedId.value)
const pendingTransition = transitionRecovery.pending
const transitionNeedsConfirmation = transitionRecovery.needsConfirmation
watch(
  () => [transitionRecovery.identity.value, pendingTransition.value] as const,
  ([identity, command], previous) => {
    if (previous && identity !== previous[0]) {
      action.value = null
      note.value = ''
      readiness.value = null
      confirmCancelledChildren.value = false
    }
    if (command) {
      action.value = command.action
      note.value = command.note
      actionKey.value = command.requestKey
      confirmCancelledChildren.value = !!command.confirmCancelledChildren
    }
  },
  { immediate: true }
)
const workflowSourceLoading = ref(true),
  workflowReadOnlyReason = ref(''),
  workflowSourceEpoch = ref(0)
async function locateClaim(target: TaskClaimLocation) {
  if (loading.value || busy.value || workflowReadOnlyReason.value || !(await leaveArrangement())) return
  claimLocation.value = target
}
// 新预览显式提供只读工作要求；仍兼容旧服务的空 schedule 摘要，不能据此请求业务资料。
const claimPreview = computed(() => !!detail.value && (!!detail.value.preview || detail.value.task.schedule == null))
const hasOverview = computed(() => !claimPreview.value || !!detail.value?.preview)
const workflowReadonly = computed(
  () => claimPreview.value || workflowSourceLoading.value || !!workflowReadOnlyReason.value
)
function applyWorkflowSource(source: WorkflowTaskNodeView | null) {
  workflowReadOnlyReason.value =
    source?.readOnlyReason || (source?.state === 'INVALIDATED' ? '所属流程已结束，任务仅供查看' : '')
}
const task = computed(() => {
  const original = detail.value?.task
  if (!original || !workflowReadonly.value) return original
  return {
    ...original,
    ...detail.value?.preview,
    canExecute: false,
    canStart: false,
    canClaim: false,
    canAssign: false,
    canDelegate: false,
    canTransfer: false,
    canAccept: false,
    canPause: false,
    canResume: false,
    canEdit: false,
    canDelete: original.canDelete === undefined ? undefined : false,
    deleteBlockedReason: workflowReadOnlyReason.value || '当前任务仅供查看'
  }
})
function captureVisit(): TaskVisit {
  return {
    id: selectedId.value,
    title: task.value?.title || '任务',
    tab: tab.value,
    arrangementView: arrangementView.value,
    arrangementSelection: adjustmentForm.value?.selectedId,
    scrollTop: navigationElement.value?.closest('.ant-drawer-body')?.scrollTop || 0,
    graph: adjustmentForm.value?.captureView()
  }
}
const canSubmitAction = computed(() => {
  if (!task.value || busy.value || loading.value || workflowReadonly.value) return false
  if (pendingTransition.value?.id === task.value.id) return true
  if (action.value === 'START') return task.value.canExecute && task.value.canStart
  if (action.value === 'PAUSE') return !!task.value.canPause
  if (action.value === 'RESUME') return !!task.value.canResume
  if (action.value === 'APPROVE' || action.value === 'REJECT')
    return (
      !!task.value.canAccept &&
      task.value.status === 'PENDING_ACCEPTANCE' &&
      (action.value !== 'REJECT' || !!note.value.trim())
    )
  if (readiness.value?.taskId !== task.value.id || readiness.value.revision !== task.value.revision) return false
  return action.value === 'COMPLETE'
    ? taskCompletionReady(readiness.value, confirmCancelledChildren.value, note.value)
    : action.value === 'CANCEL' && readiness.value.canCancel
})
const completionLabel = computed(() => (task.value && taskNeedsAcceptance(task.value) ? '提交验收' : '完成任务'))
const actionTitle = computed(
  () =>
    ({
      START: task.value && taskStartsEarly(task.value) ? '确认提前开始' : '开始执行',
      PAUSE: '暂停任务',
      RESUME: '恢复任务',
      COMPLETE: completionLabel.value,
      APPROVE: '验收通过',
      REJECT: '退回修改',
      CANCEL: '取消任务'
    })[action.value || 'START']
)
const actionConfirm = computed(() =>
  action.value === 'COMPLETE'
    ? completionLabel.value === '提交验收'
      ? '确认提交验收'
      : '确认完成'
    : action.value === 'PAUSE'
      ? '确认暂停'
      : action.value === 'RESUME'
        ? '确认恢复'
        : action.value === 'CANCEL'
          ? '确认取消任务'
          : actionTitle.value
)
const acceptanceEvents = computed(() =>
  (detail.value?.events || [])
    .filter(
      event =>
        event.taskId === task.value?.id && ['SUBMITTED_FOR_ACCEPTANCE', 'ACCEPTED', 'REJECTED'].includes(event.type)
    )
    // 底座返回毫秒时间戳；也兼容历史字符串，不能直接按字符串方法比较。
    .sort((a, b) => dayjs(b.createdAt).valueOf() - dayjs(a.createdAt).valueOf())
)
const latestSubmission = computed(() => acceptanceEvents.value.find(event => event.type === 'SUBMITTED_FOR_ACCEPTANCE'))
const latestAcceptance = computed(() => acceptanceEvents.value[0])
const visibleActions = computed(() => {
  const current = task.value
  return {
    start: !!current?.assigneeId && current.status === 'PENDING' && current.canExecute,
    claim: !!current?.canClaim,
    assign: !!(current?.canAssign || current?.canDelegate || current?.canTransfer),
    complete:
      current?.status === 'RUNNING' && current.canExecute && (!current.childCount || !!current.completionReason),
    accept: !!current?.canAccept,
    pause: !!current?.canPause,
    resume: !!current?.canResume,
    submission: current?.status === 'PENDING_ACCEPTANCE' && !!latestSubmission.value,
    cancel: !!current?.canEdit && ['PENDING', 'RUNNING'].includes(current.status)
  }
})
const hasVisibleActions = computed(() => Object.values(visibleActions.value).some(Boolean))
const assignmentOpen = ref(false),
  claimOpen = ref(false)
const contextNodes = computed(() => taskContextNodes(detail.value?.nodes || []))
const taskLocation = computed(() => {
  const lookup = new Map(contextNodes.value.map(node => [node.id, node]))
  const path: typeof contextNodes.value = []
  const seen = new Set<string>(task.value ? [task.value.id] : [])
  let id = task.value?.parentId
  while (id && !seen.has(id)) {
    seen.add(id)
    const node = lookup.get(id)
    if (!node) break
    path.unshift(node)
    id = node.parentId
  }
  return path
})
const predecessors = computed(() => {
  if (!detail.value) return []
  const found = instancePredecessors(detail.value)
  const ids = [...new Set([...(task.value?.predecessorIds || []), ...found.map(node => node.id)])]
  return ids.map(id => ({ id, node: found.find(node => node.id === id) }))
})
const waitingPredecessor = computed(() =>
  predecessors.value.find(item => item.node && item.node.status !== 'COMPLETED')
)
async function showArrangement(id = waitingPredecessor.value?.id || task.value?.id) {
  await changeTab('arrangement')
  await nextTick()
  if (tab.value === 'arrangement' && id) adjustmentForm.value?.restoreSelection(id)
}
// 未获详情权限的祖先仅用服务端已公开的位置摘要，不额外读取整组资料。
const limitedRelationPath = computed(() => {
  const ancestors = task.value?.ancestorContext || []
  return !detail.value?.structure?.length &&
    ancestors.some(node => !detail.value?.nodes.some(item => item.id === node.id))
    ? [...ancestors.map(node => node.title), task.value?.title].filter(Boolean).join(' / ')
    : ''
})
const applicationName = ref('')
watch(
  () => task.value?.applicationId,
  async id => {
    applicationName.value = ''
    if (!id) return
    try {
      const apps = await platform.runtime.mine()
      if (task.value?.applicationId === id)
        applicationName.value = apps.find(app => app.id === id)?.name || '所属应用当前不可访问'
    } catch {
      if (task.value?.applicationId === id) applicationName.value = '应用名称暂不可用'
    }
  }
)
const user = useUserStore(),
  businessForm = ref<InstanceType<typeof TaskBusinessForm>>(),
  entryWorkspace = ref<InstanceType<typeof TaskEntryWorkspace>>()
const legacyBusinessOpen = ref(false)
async function closeLegacyBusiness() {
  if (businessForm.value && !(await businessForm.value.requestClose())) return
  legacyBusinessOpen.value = false
}
const hasWorkEntries = computed(
  () => !claimPreview.value && (!!task.value?.dataPolicy || detail.value?.nodes.some(node => node.entries?.length))
)
const comments = computed(() => detail.value?.comments.filter(item => item.taskId === task.value?.id) || [])
const commentsSection = ref<HTMLElement>()
const historyLimit = ref(10)
const historyScope = ref<'current' | 'visible'>(props.employeeView ? 'current' : 'visible')
const history = computed(() =>
  (detail.value?.events || [])
    .filter(event => historyScope.value === 'visible' || event.taskId === task.value?.id)
    .sort((a, b) => dayjs(b.createdAt).valueOf() - dayjs(a.createdAt).valueOf())
)
const visibleHistory = computed(() => history.value.slice(0, historyLimit.value))
watch(historyScope, () => (historyLimit.value = 10))
watch([selectedId, () => props.employeeView], () => {
  historyScope.value = props.employeeView ? 'current' : 'visible'
  historyLimit.value = 10
})
const material = ref<TaskMaterial>(),
  materialEvent = ref<TaskEvent>(),
  materialOpen = ref(false),
  materialBusy = ref(false),
  materialError = ref('')
async function openMaterial(event: TaskEvent) {
  materialEvent.value = event
  material.value = undefined
  materialOpen.value = true
  materialBusy.value = true
  materialError.value = ''
  try {
    material.value = await api.material(event.taskId, event.id)
  } catch (e) {
    materialError.value = errorMessage(e)
  } finally {
    materialBusy.value = false
  }
}
const canManage = computed(
  () =>
    !claimPreview.value &&
    (hasPermission('nocode:task:manage-all') ||
      detail.value?.nodes.some(node => node.id === node.rootId && String(node.creatorId) === String(user.userInfo?.id)))
)
const workTimeOpen = ref(false)
const names = computed(() => new Map(detail.value?.nodes.map(n => [n.id, n.title]) || []))
const eventLabels: Record<string, string> = {
  CREATED: '新建任务',
  CREATE: '新建任务',
  CLAIMED: '领取任务',
  ASSIGNED: '安排负责人',
  SUBMITTED_FOR_ACCEPTANCE: '提交验收',
  ACCEPTED: '验收通过',
  REJECTED: '验收退回',
  STARTED: '开始执行',
  PAUSED: '暂停任务',
  RESUMED: '恢复任务',
  COMPLETED: '完成任务',
  ROLLUP_BLOCKED: '等待交付检查',
  CANCELLED: '取消任务',
  COMMENTED: '发表评论',
  ADJUSTED: '调整实例',
  SUBTASK_DELETED: '删除子任务',
  PLANNED: '安排计划',
  LINKED: '关联业务记录',
  UNLINKED: '解除业务记录关联',
  BUSINESS_SAVED: '业务数据操作'
}
let generation = 0,
  membersGeneration = 0
let initialLocationPending = true
onBeforeUnmount(() => {
  generation++
  membersGeneration++
})
async function loadMembers() {
  const token = ++membersGeneration,
    id = selectedId.value
  membersLoading.value = true
  membersError.value = ''
  members.value = []
  try {
    const people = await api.members(id)
    if (token === membersGeneration && id === selectedId.value) members.value = people
  } catch (e) {
    if (token === membersGeneration && id === selectedId.value) membersError.value = errorMessage(e)
  } finally {
    if (token === membersGeneration) membersLoading.value = false
  }
}
async function load(id = selectedId.value): Promise<boolean> {
  const token = ++generation
  loading.value = true
  error.value = ''
  try {
    const next = await api.detail(id)
    if (token === generation) {
      selectedId.value = id
      workflowSourceLoading.value = !next.preview && next.task.schedule != null
      workflowReadOnlyReason.value = ''
      workflowSourceEpoch.value++
      detail.value = next
      if (!claimPreview.value) void loadMembers()
      else {
        membersGeneration++
        members.value = []
        membersError.value = ''
        membersLoading.value = false
        tab.value = 'overview'
      }
      if (tab.value === 'business') tab.value = 'overview'
      if (
        initialLocationPending &&
        props.initialTab === 'arrangement' &&
        (!claimPreview.value || next.structure?.length)
      ) {
        tab.value = 'arrangement'
        await nextTick()
        const location = props.initialSelectedId || waitingPredecessor.value?.id
        if (location) adjustmentForm.value?.restoreSelection(location)
      }
      if (initialLocationPending && props.initialTab === 'business' && !hasWorkEntries.value)
        legacyBusinessOpen.value = !!(next.task.binding || next.task.business)
      if (initialLocationPending && props.commentId && selectedId.value === originId.value) {
        tab.value = 'overview'
        await nextTick()
        const element = Array.from(document.querySelectorAll<HTMLElement>('[data-comment-id]')).find(
          el => el.dataset.commentId === props.commentId
        )
        element?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
      } else if (initialLocationPending && props.initialTab === 'comments' && selectedId.value === originId.value) {
        tab.value = 'overview'
        await nextTick()
        commentsSection.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
      }
      initialLocationPending = false
      return true
    }
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  } finally {
    if (token === generation) loading.value = false
  }
  return false
}
watch(
  () => [props.id, props.commentId],
  () => {
    selectedId.value = props.id
    resumedEntry.value = undefined
    originId.value = props.id
    visits.value = []
    initialLocationPending = true
    detail.value = undefined
    comment.value = ''
    mentions.value = []
    reply.value = null
    tab.value = 'overview'
    legacyBusinessOpen.value = false
    load()
  },
  { immediate: true }
)
async function execute() {
  if (!task.value || !action.value || !canSubmitAction.value) return
  busy.value = true
  error.value = ''
  const attempt = transitionRecovery.begin(
    pendingTransition.value || {
      id: task.value.id,
      expectedRevision: task.value.revision,
      action: action.value,
      note: note.value,
      requestKey: actionKey.value,
      ...(action.value === 'COMPLETE' && confirmCancelledChildren.value ? { confirmCancelledChildren: true } : {})
    }
  )
  try {
    const applied = await api.transition(attempt.command)
    transitionRecovery.succeed(attempt)
    if (transitionRecovery.identity.value !== attempt.key) return
    detail.value = applied
    action.value = null
    emit('changed')
    message.success('任务状态已更新')
  } catch (e) {
    const result = await transitionRecovery.fail(attempt, e)
    if (transitionRecovery.identity.value !== attempt.key) return
    if (result.applied) {
      detail.value = result.applied
      action.value = null
      emit('changed')
      message.success('已确认原任务操作成功')
    } else if (result.superseded) {
      actionKey.value = uuid()
      await load()
      error.value = '原操作未生效，任务已更新。请核对最新情况后重新确认，原备注已保留。'
    } else {
      if (!pendingTransition.value) actionKey.value = uuid()
      error.value = errorMessage(e)
    }
  } finally {
    busy.value = false
  }
}
async function openAction(value: TaskAction) {
  if (busy.value || loading.value || workflowReadonly.value) return
  if (!(await leaveArrangement())) return
  if (entryWorkspace.value && !(await entryWorkspace.value.prepareAction())) return
  // 主表批准弃改后立即销毁，不能被后续拒绝检查留下失效的离开许可。
  if (businessForm.value && !(await businessForm.value.requestClose())) return
  businessEpoch.value++
  readiness.value = null
  confirmCancelledChildren.value = false
  if (pendingTransition.value) {
    action.value = pendingTransition.value.action
    note.value = pendingTransition.value.note
    confirmCancelledChildren.value = !!pendingTransition.value.confirmCancelledChildren
    return
  }
  action.value = value
  note.value = ''
  actionKey.value = uuid()
}
async function navigateCheck(check: TaskReadiness['checks'][number]) {
  if (busy.value || !(await confirmDiscard(!!note.value.trim(), '先处理任务条件？未提交的备注将放弃。'))) return
  action.value = null
  tab.value = check.code === 'CHILDREN' ? 'arrangement' : 'overview'
  await nextTick()
  if (check.entryKey) await entryWorkspace.value?.select(check.entryKey)
  else if (check.code !== 'CHILDREN' && !task.value?.dataPolicy && (task.value?.binding || task.value?.business))
    legacyBusinessOpen.value = true
}
async function postComment() {
  if (!comment.value.trim() || commentBusy.value || workflowReadonly.value) return
  commentBusy.value = true
  try {
    await api.comment({
      taskId: selectedId.value,
      parentId: reply.value?.id || null,
      content: comment.value.trim(),
      mentionedUserIds: mentions.value,
      requestKey: commentKey.value
    })
    comment.value = ''
    mentions.value = []
    reply.value = null
    commentKey.value = uuid()
    await load()
    emit('changed')
    message.success('评论已发表，提醒已交由系统消息中心发送')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    commentBusy.value = false
  }
}
async function select(id: string, returning = false) {
  if (id === selectedId.value || navigating.value || loading.value || busy.value || commentBusy.value) return
  navigating.value = true
  try {
    if (!(await confirmDiscard(!!comment.value.trim(), '切换任务并放弃尚未发表的评论？'))) return
    if (entryWorkspace.value && !(await entryWorkspace.value.prepareAction())) return
    const previous = captureVisit()
    if (!(await leaveArrangement())) return
    if (businessForm.value && !(await businessForm.value.requestClose())) return
    businessEpoch.value++
    const destination = returning ? previousVisit.value : undefined
    // 保留旧详情直到新节点读取成功，失败时操作对象与返回栈都不变。
    if (!(await load(id))) return
    if (returning) visits.value.pop()
    else visits.value.push(previous)
    comment.value = ''
    mentions.value = []
    reply.value = null
    if (destination) {
      tab.value =
        destination.tab === 'business' && !task.value?.binding && !task.value?.business && !hasWorkEntries.value
          ? 'overview'
          : destination.tab
      arrangementView.value = destination.arrangementView
    }
    await nextTick()
    const view = destination || previous
    if (destination?.arrangementSelection) adjustmentForm.value?.restoreSelection(destination.arrangementSelection)
    if (view.graph) adjustmentForm.value?.restoreView(view.graph)
    const body = navigationElement.value?.closest('.ant-drawer-body')
    if (body) body.scrollTop = view.scrollTop
  } finally {
    navigating.value = false
  }
}
async function back() {
  if (previousVisit.value) await select(previousVisit.value.id, true)
}
async function resumeEntry(location: { taskId: string; entryKey: string; contributionId: string }) {
  if (location.taskId !== selectedId.value) await select(location.taskId)
  if (location.taskId !== selectedId.value) return
  resumedEntry.value = location
  tab.value = 'overview'
}
async function close() {
  if (busy.value || commentBusy.value) return
  if (!(await confirmDiscard(!!comment.value.trim(), '放弃尚未发表的评论？'))) return
  if (entryWorkspace.value && !(await entryWorkspace.value.prepareAction())) return
  if (!(await leaveArrangement())) return
  if (businessForm.value && !(await businessForm.value.requestClose())) return
  emit('close')
}
async function openDeleteSubtask(target: TaskRow) {
  if (
    !props.employeeView ||
    workflowReadonly.value ||
    busy.value ||
    commentBusy.value ||
    loading.value ||
    target.canDelete !== true
  )
    return
  const deletingDetail = target.id === selectedId.value || target.id === originId.value
  if (deletingDetail && !(await confirmDiscard(!!comment.value.trim(), '删除任务并放弃尚未发表的评论？'))) return
  if (entryWorkspace.value && !(await entryWorkspace.value.prepareAction())) return
  if (!(await leaveArrangement())) return
  if (businessForm.value && !(await businessForm.value.requestClose())) return
  businessEpoch.value++
  deleteTask.value = target
}
async function afterSubtaskDeleted(id: string) {
  deleteTask.value = undefined
  visits.value = visits.value.filter(visit => visit.id !== id)
  emit('changed')
  // 当前详情或最初入口已删除时直接关闭，避免返回栈或外层列表仍指向失效节点。
  if (selectedId.value === id || originId.value === id) {
    generation++
    detail.value = undefined
    emit('close')
    return
  }
  await load()
  // 刷新失败也不能继续选中已确认删除的节点；刷新成功后再重建编辑器，避免捕获旧列表。
  if (detail.value) detail.value = { ...detail.value, nodes: detail.value.nodes.filter(node => node.id !== id) }
  arrangementEpoch.value++
}
function afterSaved() {
  load()
  emit('changed')
}
function afterAssignment(result: TaskDetail) {
  if (result.task.id === task.value?.id) afterSaved()
  else {
    // 受限分工后已失去原节点资料权，跳到服务端返回的安全任务，不再探测原节点。
    emit('changed')
    emit('select', result.task.id)
  }
}
async function afterAdjustment() {
  const selection = adjustmentForm.value?.selectedId
  const graph = adjustmentForm.value?.captureView()
  await load()
  emit('changed')
  await nextTick()
  if (selection) adjustmentForm.value?.restoreSelection(selection)
  if (graph) adjustmentForm.value?.restoreView(graph)
}
async function inspectArrangementNode(id: string) {
  await select(id)
  if (selectedId.value === id) await changeTab('overview')
}
async function closeAction() {
  if (!busy.value && (await confirmDiscard(!!note.value.trim(), '放弃尚未提交的操作说明？'))) action.value = null
}
function updateReadiness(value: TaskReadiness | null) {
  readiness.value = value
  if (!pendingTransition.value) confirmCancelledChildren.value = false
}
</script>
<template>
  <OsModalForm
    :wrap-form="false"
    :allow-switch-display="false"
    :open="true"
    :title="task?.title || '任务详情'"
    display-mode="drawer"
    maximizable
    :width="drawerWidth"
    root-class-name="task-hierarchy-drawer"
    :show-footer="false"
    @cancel="close"
  >
    <template #formItems>
      <a-spin v-if="loading && !detail" />
      <a-alert v-if="error" type="error" show-icon :message="error" closable @close="error = ''" />
      <a-button v-if="!loading && !detail" @click="load()">重新加载任务</a-button>
      <div v-if="detail && task" :inert="loading || navigating ? true : undefined" :aria-busy="loading || navigating">
        <nav ref="navigationElement" class="task-detail__navigation" aria-label="当前任务位置" :aria-busy="loading">
          <a-button
            v-if="previousVisit"
            size="small"
            :disabled="loading || busy"
            :title="`返回：${previousVisit.title}`"
            @click="back"
          >
            返回上一任务
          </a-button>
          <span class="task-detail__note">当前位置</span>
          <ol>
            <li v-for="node in taskLocation" :key="node.id">
              <button v-if="node.detailVisible" type="button" :disabled="loading || busy" @click="select(node.id)">
                {{ node.title }}
              </button>
              <span v-else>{{ node.title }}</span>
            </li>
            <li aria-current="page">
              <strong>{{ task.title }}</strong>
            </li>
          </ol>
          <span v-if="loading" role="status" class="task-detail__note">正在切换…</span>
        </nav>
        <TaskWorkflowSource
          v-if="!claimPreview"
          :key="`${task.id}:${workflowSourceEpoch}`"
          :task-id="task.id"
          @loaded="applyWorkflowSource"
          @loading="workflowSourceLoading = $event"
          @failed="workflowReadOnlyReason = '任务来源校验暂不可用，请重试加载后继续操作'"
        />
        <a-alert
          v-if="claimPreview"
          type="info"
          show-icon
          message="领取前预览 · 可查看任务要求与安排，当前仅供查看，不会领取或开始任务。"
        >
          <template #action>
            <a-button type="link" :disabled="loading" @click="locateClaim(task)">去领取</a-button>
          </template>
        </a-alert>
        <a-alert v-if="workflowReadOnlyReason" type="warning" show-icon :message="workflowReadOnlyReason" />
        <div v-if="hasVisibleActions" class="task-detail__actions">
          <a-button
            v-if="visibleActions.start"
            type="primary"
            :disabled="!task.canStart || loading"
            :title="task.blockedReason || undefined"
            @click="openAction('START')"
          >
            {{ task.canStart && taskStartsEarly(task) ? '提前开始' : '开始执行' }}
          </a-button>
          <a-button
            v-if="visibleActions.claim"
            type="primary"
            :disabled="adjustment || loading"
            @click="claimOpen = true"
          >
            {{ task.id === task.rootId ? '领取任务' : '只领这一项' }}
          </a-button>
          <a-button v-if="visibleActions.assign" :disabled="adjustment || loading" @click="assignmentOpen = true">
            {{ taskAssignmentActionLabel(task) }}
          </a-button>
          <a-button v-if="visibleActions.complete" type="primary" :disabled="loading" @click="openAction('COMPLETE')">
            {{ completionLabel }}
          </a-button>
          <a-button v-if="visibleActions.accept" type="primary" @click="openAction('APPROVE')">验收通过</a-button>
          <a-button v-if="visibleActions.accept" danger @click="openAction('REJECT')">退回修改</a-button>
          <a-button v-if="visibleActions.pause" :disabled="loading || busy" @click="openAction('PAUSE')">
            暂停任务
          </a-button>
          <a-button
            v-if="visibleActions.resume"
            type="primary"
            :disabled="loading || busy"
            @click="openAction('RESUME')"
          >
            恢复任务
          </a-button>
          <a-button v-if="visibleActions.submission && latestSubmission" @click="openMaterial(latestSubmission)">
            查看本次提交材料
          </a-button>
          <a-button v-if="visibleActions.cancel" danger @click="openAction('CANCEL')">取消任务</a-button>
        </div>
        <a-alert
          v-if="taskDisplayState(task) === 'PAUSED'"
          type="warning"
          show-icon
          :message="task.pauseReason || '任务已暂停，恢复后可以继续执行'"
        />
        <a-alert
          v-if="task.blockedReason && task.status === 'PENDING' && taskDisplayState(task) !== 'PAUSED'"
          type="info"
          show-icon
          :message="task.blockedReason"
        >
          <template v-if="!claimPreview" #action>
            <a-button
              v-if="canLocateTaskClaim(waitingPredecessor?.node) && !workflowReadonly"
              type="link"
              size="small"
              :title="`查看领取：${waitingPredecessor?.node?.title}`"
              @click="waitingPredecessor?.node && locateClaim(waitingPredecessor.node)"
            >
              去领取前置任务
            </a-button>
            <a-button type="link" size="small" @click="showArrangement()">查看任务编排</a-button>
          </template>
        </a-alert>
        <a-alert v-if="task.completionReason" type="warning" show-icon :message="task.completionReason" />
        <a-alert
          v-if="task.status === 'PENDING_ACCEPTANCE'"
          type="info"
          show-icon
          :message="
            task.canAccept ? '请查看本次提交材料，确认通过或填写原因退回修改。' : '已提交验收，等待验收人处理。'
          "
          :description="'验收人：' + (task.acceptorName || '已指定') + '；验收通过后才记录实际完成时间。'"
        />
        <a-alert
          v-else-if="task.status === 'RUNNING' && latestAcceptance?.type === 'REJECTED'"
          type="warning"
          show-icon
          message="验收已退回，请修改后重新提交"
          :description="latestAcceptance.note"
        />
        <p
          v-if="task.status === 'PENDING_ACCEPTANCE' && latestSubmission"
          class="task-detail__note"
          aria-label="最近提交说明"
        >
          {{ latestSubmission.actorName }} · {{ taskTime(latestSubmission.createdAt) }} 提交：{{
            latestSubmission.note || '未填写提交说明'
          }}
        </p>
        <a-tabs :active-key="tab" :destroy-inactive-tab-pane="false" @update:active-key="changeTab">
          <a-tab-pane key="overview" tab="任务概况">
            <div class="task-detail__overview" :class="{ 'task-detail__overview--preview': claimPreview }">
              <div class="task-detail__main">
                <section v-if="hasOverview" class="task-detail__content" aria-label="任务内容">
                  <h3>任务内容</h3>
                  <TaskContentDisplay :value="task.description" />
                </section>
                <a-descriptions
                  class="task-detail__information"
                  :column="2"
                  bordered
                  size="small"
                  aria-label="任务执行信息"
                >
                  <a-descriptions-item label="状态">{{ taskStates[taskDisplayState(task)] }}</a-descriptions-item>
                  <a-descriptions-item label="负责人">
                    {{ taskAssignmentLabel(task, task.assigneeName) }}
                  </a-descriptions-item>
                  <a-descriptions-item v-if="hasOverview" label="预计开始">
                    {{ task.expectedStart ? taskDate(task.expectedStart) : '暂未安排' }}
                  </a-descriptions-item>
                  <a-descriptions-item v-if="hasOverview" label="预计完成">
                    {{ task.expectedEnd ? taskDate(task.expectedEnd) : '暂未安排' }}
                    <TaskScheduleNotice :summary="task.scheduleSummary" />
                  </a-descriptions-item>
                  <a-descriptions-item v-if="hasOverview" label="实际开始时间">
                    {{ task.actualStart ? taskTime(task.actualStart) : claimPreview ? '—' : '' }}
                  </a-descriptions-item>
                  <a-descriptions-item v-if="hasOverview" label="实际完成时间">
                    {{
                      task.status === 'COMPLETED' && task.actualEnd ? taskTime(task.actualEnd) : claimPreview ? '—' : ''
                    }}
                  </a-descriptions-item>
                  <a-descriptions-item v-if="hasOverview" label="优先级" :span="task.id === task.rootId ? 1 : 2">
                    {{ taskPriorities[task.priority] }}
                  </a-descriptions-item>
                  <a-descriptions-item v-if="hasOverview && task.id === task.rootId" label="任务标准总工时">
                    <span title="整项任务的预计工时，不是实际计时或排期工期">
                      {{ formatEffectiveWorkMinutes(task.effectiveWorkMinutes) }}
                    </span>
                    <a-button
                      v-if="canManage && !workflowReadonly && ['PENDING', 'RUNNING', 'PAUSED'].includes(task.status)"
                      type="link"
                      size="small"
                      @click="workTimeOpen = true"
                    >
                      调整工时
                    </a-button>
                  </a-descriptions-item>
                  <a-descriptions-item v-if="taskNeedsAcceptance(task)" label="验收人" :span="2">
                    {{ task.acceptorName || '已指定' }}
                  </a-descriptions-item>
                  <a-descriptions-item label="任务归属" :span="2">
                    <template v-for="(node, index) in taskLocation" :key="node.id">
                      <span v-if="index">/</span>
                      <a-button v-if="node.detailVisible" type="link" @click="select(node.id)">
                        {{ node.title }}
                      </a-button>
                      <span v-else :title="`${taskContextAssignee(node)} · ${taskStates[node.status]} · 层级参考`">
                        {{ node.title }}
                      </span>
                    </template>
                    <span v-if="!taskLocation.length">
                      {{ task.id !== task.rootId ? '上级任务未在当前视图中' : '当前为总任务' }}
                    </span>
                  </a-descriptions-item>
                  <a-descriptions-item v-if="predecessors.length" label="前置任务" :span="2">
                    <a-space wrap>
                      <template v-for="predecessor in predecessors" :key="predecessor.id">
                        <a-button v-if="predecessor.node" type="link" @click="showArrangement(predecessor.id)">
                          {{ predecessor.node.title }} · {{ taskStates[predecessor.node.status] }} ·
                          {{ predecessor.node.assigneeName || '未指定负责人' }}
                        </a-button>
                        <span v-else class="task-list__hint">前置任务当前不可查看</span>
                      </template>
                    </a-space>
                  </a-descriptions-item>
                  <a-descriptions-item v-if="task.project" label="关联业务记录" :span="2">
                    {{ task.project.label || task.project.recordId }}
                  </a-descriptions-item>
                </a-descriptions>
                <details
                  v-if="hasOverview"
                  :key="`settings:${task.id}`"
                  :open="claimPreview"
                  class="task-detail__more"
                  aria-label="任务设置与来源"
                >
                  <summary>任务设置与来源</summary>
                  <a-descriptions class="task-detail__information" :column="2" bordered size="small">
                    <a-descriptions-item label="创建人">{{ task.creatorName }}</a-descriptions-item>
                    <a-descriptions-item v-if="claimPreview" label="创建时间">
                      {{ taskTime(task.createdAt) }}
                    </a-descriptions-item>
                    <a-descriptions-item v-else label="所属应用">
                      {{ task.applicationId ? applicationName || '正在读取' : '独立任务' }}
                    </a-descriptions-item>
                    <a-descriptions-item label="时间安排" :span="2">
                      {{ taskInstanceScheduleSummary(task) }}
                    </a-descriptions-item>
                    <a-descriptions-item v-if="!taskNeedsAcceptance(task)" label="验收安排" :span="2">
                      {{ task.id === task.rootId ? '不需验收，由负责人直接完成' : '由总负责人汇总后按总任务安排验收' }}
                    </a-descriptions-item>
                  </a-descriptions>
                </details>
                <template v-if="detail.links?.length">
                  <h4 style="margin-top: 20px">关联业务记录</h4>
                  <a-list :data-source="detail.links" size="small">
                    <template #renderItem="{ item }">
                      <a-list-item>
                        <span>{{ item.record.label || item.record.recordId }}</span>
                        <span class="task-list__hint">{{ item.creatorName }} · {{ taskTime(item.createdAt) }}</span>
                      </a-list-item>
                    </template>
                  </a-list>
                </template>
                <section v-if="!task.dataPolicy && (task.binding || task.business)" class="task-detail__business">
                  <h3>业务数据</h3>
                  <a-button @click="legacyBusinessOpen = true">打开业务数据</a-button>
                </section>
                <TaskEntryWorkspace
                  v-if="hasWorkEntries"
                  ref="entryWorkspace"
                  :key="`entries:${task.id}`"
                  class="task-detail__business"
                  :task="task"
                  :employee-view="employeeView"
                  :readonly-reason="workflowReadonly ? workflowReadOnlyReason || '正在校验任务来源' : undefined"
                  :initial-entry-key="
                    resumedEntry?.taskId === task.id
                      ? resumedEntry.entryKey
                      : task.id === originId
                        ? initialEntryKey
                        : undefined
                  "
                  :initial-contribution-id="
                    resumedEntry?.taskId === task.id
                      ? resumedEntry.contributionId
                      : task.id === originId
                        ? initialContributionId
                        : undefined
                  "
                  @updated="afterSaved"
                  @resume="resumeEntry"
                />
                <section v-if="!claimPreview" ref="commentsSection" class="task-detail__comments" aria-label="任务评论">
                  <h3>
                    评论
                    <span class="task-list__hint">{{ comments.length }}</span>
                  </h3>
                  <a-alert
                    v-if="membersError"
                    type="warning"
                    show-icon
                    :message="`提醒成员暂不可用：${membersError}`"
                  />
                  <a-button v-if="membersError" :loading="membersLoading" @click="loadMembers">重试加载成员</a-button>
                  <a-empty v-if="!comments.length" description="暂无评论，可以在下方记录进展或提出问题" />
                  <a-alert
                    v-if="commentId && selectedId === originId && !comments.some(item => item.id === commentId)"
                    type="info"
                    message="该评论已不可用或无权查看，以下为当前可见评论。"
                  />
                  <div
                    v-for="item in comments"
                    :key="item.id"
                    class="task-comment"
                    :data-comment-id="item.id"
                    :class="{ 'task-comment--target': item.id === commentId }"
                  >
                    <div class="task-comment__header">
                      <strong>{{ item.authorName }}</strong>
                      <time>{{ taskTime(item.createdAt) }}</time>
                      <a-button v-if="!workflowReadonly" type="link" size="small" @click="reply = item">回复</a-button>
                    </div>
                    <div v-if="item.parentId" class="task-comment__reply">
                      回复 {{ comments.find(c => c.id === item.parentId)?.authorName || '评论' }}
                    </div>
                    <div class="task-comment__content">{{ item.content }}</div>
                    <a-tag v-for="person in item.mentionedUserIds" :key="person">
                      @{{ members.find(m => String(m.id) === String(person))?.name || '成员' }}
                    </a-tag>
                  </div>
                  <div v-if="!workflowReadonly" class="task-comment__composer">
                    <a-alert
                      v-if="reply"
                      type="info"
                      :message="`回复 ${reply.authorName}：${reply.content.slice(0, 80)}`"
                      closable
                      @close="reply = null"
                    />
                    <a-textarea
                      v-model:value="comment"
                      :rows="3"
                      :maxlength="4000"
                      placeholder="记录进展、提出问题或补充完成说明"
                      aria-label="评论内容"
                    />
                    <a-select
                      v-model:value="mentions"
                      mode="multiple"
                      show-search
                      option-filter-prop="label"
                      :options="members.map(m => ({ value: m.id, label: m.name }))"
                      :loading="membersLoading"
                      :disabled="membersLoading || !!membersError"
                      placeholder="@ 提醒有权查看此任务的成员"
                      aria-label="提醒成员"
                    />
                    <a-button type="primary" :loading="commentBusy" :disabled="!comment.trim()" @click="postComment">
                      发表评论
                    </a-button>
                  </div>
                </section>
              </div>
              <aside v-if="!claimPreview" class="task-detail__history" aria-label="操作历史">
                <h3>操作历史</h3>
                <div class="task-detail__history-scope" role="group" aria-label="操作历史范围">
                  <a-button
                    size="small"
                    :type="historyScope === 'current' ? 'primary' : 'default'"
                    :aria-pressed="historyScope === 'current'"
                    @click="historyScope = 'current'"
                  >
                    当前任务
                  </a-button>
                  <a-button
                    size="small"
                    :type="historyScope === 'visible' ? 'primary' : 'default'"
                    :aria-pressed="historyScope === 'visible'"
                    title="查看本组内有权限查看的全部任务操作记录"
                    @click="historyScope = 'visible'"
                  >
                    整组任务
                  </a-button>
                </div>
                <a-empty
                  v-if="!history.length"
                  :description="historyScope === 'current' ? '当前任务暂无操作记录' : '整组任务暂无操作记录'"
                />
                <a-timeline>
                  <a-timeline-item v-for="event in visibleHistory" :key="event.id">
                    <strong>{{ eventLabels[event.type] || '任务活动' }}</strong>
                    · {{ event.actorName }}
                    <div class="task-list__hint">{{ taskTime(event.createdAt) }}</div>
                    <div v-if="event.taskId !== task.id" class="task-list__hint">
                      {{ names.get(event.taskId) || '实例节点' }}
                    </div>
                    <details v-if="event.type === 'BUSINESS_SAVED' && event.note" class="task-detail__audit">
                      <summary>查看操作详情</summary>
                      <p class="task-detail__note">{{ event.note }}</p>
                    </details>
                    <p v-else class="task-detail__note">{{ event.note }}</p>
                    <a-button
                      v-if="
                        ['SUBMITTED_FOR_ACCEPTANCE', 'ACCEPTED'].includes(event.type) ||
                        (event.type === 'COMPLETED' &&
                          detail.nodes.some(
                            node => node.id === event.taskId && (node.business || node.binding || node.entries?.length)
                          ))
                      "
                      type="link"
                      @click="openMaterial(event)"
                    >
                      查看当时材料
                    </a-button>
                  </a-timeline-item>
                </a-timeline>
                <a-button v-if="history.length > historyLimit" type="link" @click="historyLimit += 10">
                  查看更多历史
                </a-button>
              </aside>
            </div>
          </a-tab-pane>
          <a-tab-pane v-if="!claimPreview || detail.structure?.length" key="arrangement" tab="任务编排">
            <p v-if="limitedRelationPath" class="task-list__hint">
              当前位置：{{ limitedRelationPath }}。仅展示你有权限查看的任务，不可见的上级只提供位置说明。
            </p>
            <TaskAdjust
              ref="adjustmentForm"
              :key="`${task.id}:${task.instanceRevision}:${arrangementEpoch}`"
              :detail="claimPreview ? { ...detail, nodes: [] } : detail"
              :can-manage="canManage"
              :employee-view="employeeView"
              :readonly="workflowReadonly"
              :allow-claim-lookup="!workflowReadOnlyReason && (claimPreview || !workflowSourceLoading)"
              v-model:view="arrangementView"
              @saved="afterAdjustment"
              @inspect="inspectArrangementNode"
              @delete-subtask="openDeleteSubtask"
              @claim="locateClaim"
            />
          </a-tab-pane>
        </a-tabs>
      </div>
    </template>
  </OsModalForm>
  <TaskWorkTimeDialog
    v-if="workTimeOpen && task"
    :task-id="task.id"
    @close="workTimeOpen = false"
    @saved="afterSaved"
  />
  <TaskDeleteSubtaskDialog
    v-if="deleteTask"
    :key="deleteTask.id"
    :task="deleteTask"
    @close="deleteTask = undefined"
    @deleted="afterSubtaskDeleted"
  />
  <OsModalForm
    :allow-switch-display="false"
    display-mode="modal"
    v-if="action"
    :open="true"
    :title="actionTitle"
    :width="520"
    :resizable="false"
    :loading="busy"
    :ok-text="actionConfirm"
    cancel-text="暂不操作"
    @ok="execute"
    @cancel="closeAction"
    layout="vertical"
  >
    <template #formItems>
      <p class="task-detail__note">本次操作：{{ task?.title }}</p>
      <a-alert
        v-if="action === 'START' && task && taskStartsEarly(task)"
        type="info"
        show-icon
        :message="taskEarlyStartMessage(task)"
      />
      <a-alert
        v-if="transitionNeedsConfirmation"
        type="warning"
        show-icon
        message="原操作结果待确认。重试仅核对同一请求，备注暂不可修改；关闭后再次打开仍可继续确认。"
      />
      <TaskReadinessPanel
        v-if="task && (action === 'COMPLETE' || action === 'CANCEL')"
        :task-id="task.id"
        :revision="task.revision"
        :action="action"
        :completion-label="completionLabel"
        @loaded="updateReadiness"
        @navigate="navigateCheck"
      />
      <a-alert
        v-if="action === 'PAUSE' || action === 'RESUME'"
        type="info"
        show-icon
        :message="action === 'PAUSE' ? taskPauseExplanation : taskResumeExplanation"
      />
      <a-form-item
        v-if="action === 'COMPLETE' && cancellationConfirmationRequired(readiness)"
        label="确认取消范围"
        required
      >
        <a-checkbox v-model:checked="confirmCancelledChildren" :disabled="busy || !!pendingTransition">
          我已核对已取消子任务，确认按剩余范围完成
        </a-checkbox>
        <p class="task-detail__note">请在下方说明取消范围及收尾依据；其他未满足的完成条件仍需处理。</p>
      </a-form-item>
      <a-alert
        v-if="task && readiness && readiness.revision !== task.revision"
        type="warning"
        message="任务已有更新，请刷新任务后再确认。"
      >
        <template #action><a-button @click="load()">刷新任务</a-button></template>
      </a-alert>
      <a-alert
        v-if="action === 'COMPLETE' && task && taskNeedsAcceptance(task)"
        type="info"
        show-icon
        :message="'提交后由 ' + (task.acceptorName || '指定验收人') + ' 验收，通过后任务才会完成。'"
      />
      <template v-if="action === 'APPROVE' || action === 'REJECT'">
        <a-alert
          type="info"
          show-icon
          :message="
            action === 'APPROVE'
              ? '验收通过后，总任务完成并记录实际完成时间。'
              : '退回后总任务恢复进行中，已完成的子任务和历次材料会保留。'
          "
        />
        <a-button v-if="latestSubmission" type="link" @click="openMaterial(latestSubmission)">
          查看本次提交材料
        </a-button>
      </template>
      <a-form-item
        :label="
          action === 'PAUSE'
            ? '暂停原因（选填）'
            : action === 'RESUME'
              ? '恢复说明（选填）'
              : action === 'REJECT'
                ? '退回原因'
                : action === 'APPROVE'
                  ? '验收意见'
                  : action === 'COMPLETE'
                    ? completionLabel === '提交验收'
                      ? '提交说明'
                      : '完成备注'
                    : '操作说明'
        "
        :required="action === 'REJECT' || (action === 'COMPLETE' && cancellationConfirmationRequired(readiness))"
      >
        <a-textarea v-model:value="note" :rows="3" :maxlength="2000" :disabled="busy || !!pendingTransition" />
      </a-form-item>
      <a-alert v-if="error" type="error" :message="error" />
    </template>
    <template #footer>
      <a-button :disabled="busy" @click="closeAction">暂不操作</a-button>
      <a-button
        type="primary"
        :danger="action === 'CANCEL' || action === 'REJECT'"
        :loading="busy"
        :disabled="!canSubmitAction"
        @click="execute"
      >
        {{
          busy
            ? transitionNeedsConfirmation
              ? '正在确认结果'
              : '正在提交'
            : transitionNeedsConfirmation
              ? '确认原操作结果'
              : actionConfirm
        }}
      </a-button>
    </template>
  </OsModalForm>
  <OsModalForm
    v-if="legacyBusinessOpen && task"
    :open="true"
    title="业务数据"
    display-mode="modal"
    :width="drawerWidth"
    :allow-switch-display="false"
    :resizable="false"
    :wrap-form="false"
    :show-footer="false"
    :mask-closable="false"
    @cancel="closeLegacyBusiness"
  >
    <template #formItems>
      <TaskBusinessForm
        ref="businessForm"
        :key="`${task.id}:${businessEpoch}`"
        :task="task"
        :readonly="workflowReadonly"
        @saved="afterSaved"
      />
    </template>
  </OsModalForm>
  <TaskAssignmentDialog
    v-if="assignmentOpen && task && !workflowReadonly"
    :key="task.id"
    :task="task"
    :current-user-id="user.userInfo?.id"
    @close="assignmentOpen = false"
    @saved="afterAssignment"
  />
  <TaskClaimDialog
    v-if="claimOpen && task && !workflowReadonly"
    :key="task.id"
    :task="task"
    :plan-readonly="planReadonly"
    @close="claimOpen = false"
    @saved="afterSaved"
  />
  <TaskClaimEntry
    v-if="claimLocation"
    :target="claimLocation"
    :plan-readonly="planReadonly"
    @close="claimLocation = undefined"
    @saved="afterAdjustment"
  />
  <OsModalForm
    :allow-switch-display="false"
    v-if="materialOpen"
    :wrap-form="false"
    :open="true"
    title="当次提交材料"
    display-mode="drawer"
    maximizable
    :width="950"
    :show-footer="false"
    @cancel="materialOpen = false"
  >
    <template #formItems>
      <a-spin v-if="materialBusy" />
      <a-alert v-else-if="materialError" type="error" show-icon :message="materialError" />
      <template v-else-if="material">
        <p v-if="materialEvent" class="task-detail__note">
          {{ materialEvent.actorName }} · {{ taskTime(materialEvent.createdAt) }} ·
          {{ eventLabels[materialEvent.type] }}
        </p>
        <p v-if="materialEvent?.note" class="task-detail__note">{{ materialEvent.note }}</p>
        <p class="task-list__hint">查看本次提交时保存的内容，按当前数据权限展示；后续编辑不会改写此份材料。</p>
        <TaskSubmissionMaterial :material="material" />
      </template>
    </template>
  </OsModalForm>
</template>
<style scoped>
.task-detail__navigation {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  min-height: 28px;
}
.task-detail__navigation ol {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--spacing-sm);
  padding: 0;
  margin: 0;
  list-style: none;
}
.task-detail__navigation li {
  overflow-wrap: anywhere;
}
.task-detail__navigation li + li::before {
  content: '/';
  margin-right: var(--spacing-sm);
  color: var(--text-secondary);
}
.task-detail__navigation li button {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--color-primary);
  font: inherit;
  cursor: pointer;
}
.task-detail__navigation li button:disabled {
  cursor: wait;
}
.task-detail__overview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 24px;
}
.task-detail__main {
  min-width: 0;
}
/* 两块信息表共用列宽，避免字段长度和跨列内容改变各自的分隔线位置。 */
.task-detail__information :deep(.ant-descriptions-view > table) {
  table-layout: fixed;
}
.task-detail__information :deep(.ant-descriptions-item-label) {
  width: 25%;
}
.task-detail__information :deep(.ant-descriptions-item-label),
.task-detail__information :deep(.ant-descriptions-item-content) {
  vertical-align: middle;
  overflow-wrap: anywhere;
}
.task-detail__information :deep(.ant-btn-link) {
  height: auto;
  padding: 0;
  white-space: normal;
  text-align: left;
}
.task-detail__overview--preview {
  grid-template-columns: minmax(0, 1fr);
}
.task-detail__content {
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--border);
}
.task-detail__content h3 {
  margin: 0 0 var(--spacing-md);
  font-size: var(--ant-font-size-lg, 16px);
  font-weight: 600;
}
.task-detail__more {
  margin-top: var(--spacing-md);
}
.task-detail__more > summary {
  cursor: pointer;
  color: var(--text-secondary);
  padding: var(--spacing-sm) 0;
}
.task-detail__business {
  margin-top: var(--spacing-lg);
}
.task-detail__comments {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--ant-color-border-secondary, #f0f0f0);
}
.task-detail__history {
  min-width: 0;
  border-left: 1px solid var(--ant-color-border-secondary, #f0f0f0);
  padding-left: 24px;
}
.task-detail__history h3 {
  margin-bottom: var(--spacing-sm);
}
.task-detail__history-scope {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-lg);
}
.task-detail__history :deep(.ant-timeline-item-content) {
  overflow-wrap: anywhere;
}
@media (max-width: 1000px) {
  .task-detail__overview {
    grid-template-columns: minmax(0, 1fr);
  }
  .task-detail__history {
    border-left: 0;
    border-top: 1px solid var(--ant-color-border-secondary, #f0f0f0);
    padding: 20px 0 0;
  }
}
</style>
