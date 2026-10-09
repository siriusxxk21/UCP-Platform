<script setup lang="ts">
import { useNocodePlatform } from '@/nocode/platform'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import {
  SearchOutlined,
  ReloadOutlined,
  MoreOutlined,
  InfoCircleOutlined,
  ArrowLeftOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'

import type {
  TaskMember,
  TaskPageContext,
  TaskQuery,
  TaskRow,
  TaskState,
  TaskRecordRef,
  TaskChecklistChoice,
  TaskDetail as TaskDetailResult
} from '@/types/nocode/task-center'
import {
  newTaskNode,
  newAutoTaskNode,
  taskStates,
  taskStateColors,
  taskDisplayState,
  taskPriorities,
  taskTime,
  taskDate,
  taskAssignmentLabel,
  taskAssignmentActionLabel,
  taskAssignmentModes,
  taskNeedsAcceptance
} from '@/nocode/task-center'
import { taskHierarchy, type TaskHierarchyItem } from '@/nocode/task-hierarchy'
import { taskParentContextLabel } from '@/nocode/task-context'
import { taskDueHint, taskExecutionHint, taskPersonalProgress } from '@/nocode/task-role-presentation'
import {
  checklistCommand,
  checklistPeriodLabel,
  createChecklistAttempt,
  taskPlanSummaryLabel,
  taskPlanSourceLabel,
  isInheritedTaskPlan
} from '@/nocode/task-checklist'
import { planWorkspaceViews, planWorkspaceQuery, type PlanWorkspaceView } from '@/nocode/task-plan-workspace'
import { taskStartsEarly, taskEarlyStartMessage } from '@/nocode/task-start'
import { taskSplitPlanSummary, taskSplitPath, type TaskSplitPlan } from '@/nocode/task-split-plan'
import { errorMessage } from '@/nocode/data-center'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { useUserStore } from '@/stores/user'
import { hasPermission } from '@/utils/access'
import TaskDetail from './TaskDetail.vue'
import TaskQuickAction from './TaskQuickAction.vue'
import type { TaskViewConfig } from '@/types/nocode/application-ui'
import TaskPlanDialog from './TaskPlanDialog.vue'
import TaskRecordPicker from './TaskRecordPicker.vue'
import TaskLaunchDrawer from './TaskLaunchDrawer.vue'
import TaskHierarchyCell from './TaskHierarchyCell.vue'
import TaskScheduleNotice from './TaskScheduleNotice.vue'
import TaskDraftList from './TaskDraftList.vue'
import TaskAssignmentDialog from './TaskAssignmentDialog.vue'
import TaskClaimDialog from './TaskClaimDialog.vue'
import TaskClaimEntry from './TaskClaimEntry.vue'
import { canLocateTaskClaim, type TaskClaimLocation } from '@/nocode/task-claim-entry'
import TaskClaimableGroups from './TaskClaimableGroups.vue'
import TaskAssignmentFields from './TaskAssignmentFields.vue'
import TaskScheduleFields from './TaskScheduleFields.vue'
import TaskSplitDialog from './TaskSplitDialog.vue'
import TaskDeleteSubtaskDialog from './TaskDeleteSubtaskDialog.vue'
import TaskEmployeeOverview from './TaskEmployeeOverview.vue'
import type { TaskManagementFocus, TaskEmployeeSelection } from '@/types/nocode/task-management'
import './workspace.css'

const { confirm, confirmDiscard } = useTaskConfirmation()
const props = withDefaults(
  defineProps<{
    scope?: 'MINE' | 'MANAGE'
    embedded?: boolean
    context?: TaskPageContext
    refreshKey?: number
    view?: TaskViewConfig | null
    businessColumns?: Array<{ key: string; title: string }>
    businessValues?: Record<string, Record<string, unknown>>
  }>(),
  { scope: 'MINE', embedded: false, businessColumns: () => [], businessValues: () => ({}) }
)
const emit = defineEmits<{ loaded: [rows: TaskRow[]]; unlink: [row: TaskRow] }>()
const management = computed(() => props.scope === 'MANAGE' && !props.embedded)
const managementView = ref<'TASKS' | 'EMPLOYEES'>('TASKS')
const employeeOverviewMounted = ref(false)
const selectedEmployee = ref<TaskEmployeeSelection>()
const employeeRefreshKey = ref(0)
const employeeTasks = computed(
  () => management.value && managementView.value === 'EMPLOYEES' && !!selectedEmployee.value
)
const employeeOverview = computed(
  () => management.value && managementView.value === 'EMPLOYEES' && !selectedEmployee.value
)
const managementFocus = ref<TaskManagementFocus>('ACTIVE')
const managementFocusOptions: Array<{ value: TaskManagementFocus; label: string }> = [
  { value: 'ACTIVE', label: '未结束' },
  { value: 'UNASSIGNED', label: '未分配' },
  { value: 'OVERDUE', label: '已逾期' },
  { value: 'PENDING_ACCEPTANCE', label: '待验收' },
  { value: 'ALL', label: '全部任务' }
]
const employeeMetricLabels = {
  RELATED: '相关任务',
  ALL: '未结束工作',
  PENDING: '未开始',
  RUNNING: '进行中',
  OVERDUE: '已逾期',
  TODAY: '今日清单',
  WEEK: '本周清单',
  COORDINATION: '汇总协调'
} as const
const employeeMetricOptions = Object.entries(employeeMetricLabels).map(([value, label]) => ({ value, label }))
const employeeChecklist = computed(() => ['TODAY', 'WEEK'].includes(selectedEmployee.value?.metric || ''))
const employeePeriodLabel = computed(() => {
  const date = dayjs(selectedEmployee.value?.date)
  if (selectedEmployee.value?.metric !== 'WEEK') return date.format('YYYY-MM-DD')
  const start = date.subtract((date.day() + 6) % 7, 'day')
  return `${start.format('YYYY-MM-DD')} 至 ${start.add(6, 'day').format('MM-DD')}`
})
const employeeOwnsTask = (row: TaskRow) =>
  employeeTasks.value && !!row.assigneeId && String(row.assigneeId) === String(selectedEmployee.value?.userId)
// 管理入口只查看计划归属；负责人本人也须在“我的任务”维护清单。
const planReadonly = computed(() => props.scope === 'MANAGE')
const personal = computed(() => props.scope === 'MINE' && !props.embedded)
const personalTab = ref<'PLAN' | 'CLAIMABLE' | 'DONE' | 'SUBMITTED'>('PLAN')
const personalTree = computed(() => personal.value && personalTab.value !== 'CLAIMABLE')
const personalTreeQuery = ref<TaskQuery>()
const personalGroups = computed(() => personalTree.value)
const planWorkspaceView = ref<PlanWorkspaceView>('ALL')
const manageRoots = computed(() => management.value && !employeeTasks.value)
const groupedList = computed(() => management.value || personalGroups.value || props.embedded)
// 嵌入只改变任务查询范围和容器，不回退到旧版宽表格与操作布局。
const roleList = computed(() => personal.value || management.value || props.embedded)
const planView = computed(() => personal.value && personalTab.value === 'PLAN')
type ListTab = TaskQuery['tab'] | TaskState | 'PLAN' | 'DONE' | 'TEAM' | 'SUBMITTED'
// 业务字段异步来自发布表单，按配置重建表头并隔离不同页面的列偏好。
const tableKey = computed(() =>
  [
    employeeTasks.value
      ? 'task-center-employee-tasks-v3'
      : manageRoots.value
        ? 'task-center-management-v3'
        : personal.value
          ? 'task-center-personal-v9'
          : 'task-center-embedded-v5',
    JSON.stringify(props.view || null),
    props.scope,
    props.context?.applicationId || '',
    props.context?.pageId || '',
    props.context?.nodeId || '',
    ...props.businessColumns.map(column => column.key)
  ].join(':')
)
const platform = useNocodePlatform(),
  api = platform.taskCenter,
  route = useRoute(),
  router = useRouter()
const user = useUserStore()
const canLaunch = computed(
  () =>
    !props.embedded &&
    props.scope === 'MANAGE' &&
    hasPermission('nocode:task:create') &&
    hasPermission('nocode:task:query')
)
const launchOpen = ref(false),
  launchTemplateId = ref<string>(),
  launchKey = ref(0)
const draftOpen = ref(false),
  launchDraftId = ref<string>(),
  assignmentTask = ref<TaskRow>(),
  claimTask = ref<TaskRow>()
const deleteTask = ref<TaskRow>()
const claimLocation = ref<TaskClaimLocation>()
const claimableGroups = ref<InstanceType<typeof TaskClaimableGroups>>()
let launchRequest = 0
onBeforeUnmount(() => launchRequest++)
function openLaunch(templateId?: string) {
  if (!canLaunch.value) return
  launchTemplateId.value = templateId
  launchDraftId.value = undefined
  launchKey.value++
  launchOpen.value = true
}
function editDraft(id: string) {
  draftOpen.value = false
  launchTemplateId.value = undefined
  launchDraftId.value = id
  launchKey.value++
  launchOpen.value = true
}
function launched(detail: TaskDetailResult) {
  launchOpen.value = false
  // 新任务可能不匹配当前筛选，直接定位详情；列表只刷新，不改条件与页码。
  openDetail(detail.task.id)
  void refresh()
}
const ownsPlan = (row: TaskRow) => !!row.assigneeId && String(row.assigneeId) === String(user.userInfo?.id)
const canPlan = (row: TaskRow) =>
  !planReadonly.value &&
  row.detailVisible !== false &&
  ownsPlan(row) &&
  ['PENDING', 'RUNNING', 'PAUSED'].includes(row.status) &&
  row.canPlan !== false
const error = ref(''),
  listFailed = ref(false),
  hasAppliedFilters = ref(false),
  members = ref<TaskMember[]>([]),
  entries = ref<Array<{ value: string; label: string }>>([]),
  applicationNames = ref<Record<string, string>>({}),
  project = ref<TaskRecordRef | null>(null)
const entriesLoading = ref(false),
  entriesError = ref('')
let mounted = true,
  entriesLoaded = false
let entriesRequest: Promise<void> | undefined
onBeforeUnmount(() => {
  mounted = false
})
function loadEntryOptions() {
  if (!mounted || entriesLoaded) return
  if (entriesRequest) return entriesRequest
  entriesLoading.value = true
  entriesError.value = ''
  // 只读取任务实际引用的来源，包含存量绑定；不再扫描旧门户入口目录。
  entriesRequest = api
    .entryOptions()
    .then(options => {
      if (!mounted) return
      entries.value = options
      entriesLoaded = true
    })
    .catch(() => {
      if (mounted) entriesError.value = '业务数据来源加载失败，请重新展开重试'
    })
    .finally(() => {
      entriesRequest = undefined
      if (mounted) entriesLoading.value = false
    })
  return entriesRequest
}
const selectedTab = ref<TaskQuery['tab']>(props.embedded ? 'POOL' : 'ALL')
const activeTab = computed(() =>
  management.value ? managementView.value : personal.value ? personalTab.value : selectedTab.value
)
const groupRow = (row: TaskRow) => groupedList.value && row.id === row.rootId
const displayedStatus = (row: TaskRow) =>
  taskDisplayState(row) === 'PAUSED' ? 'PAUSED' : groupRow(row) ? row.groupStatus || row.status : row.status
const displayedStatusLabel = (row: TaskRow) => taskStates[displayedStatus(row)]
const statusHint = (row: TaskRow) =>
  [
    groupRow(row) ? '整组任务状态' : '',
    row.pauseReason || row.completionReason || row.blockedReason || taskExecutionHint(row)
  ]
    .filter(Boolean)
    .join('；')
// 进度只使用服务端授权后的数量；不把尚未加载或不可见的节点当作已完成。
const progressCount = (row: TaskRow) =>
  row.completedChildCount != null && displayedChildCount(row) > 0
    ? { completed: row.completedChildCount, total: displayedChildCount(row) }
    : null
const progressHint = (row: TaskRow) => {
  const progress = progressCount(row)
  return [
    progress ? `下级已完成 ${progress.completed}/${progress.total}` : '',
    taskPersonalProgress(row, personalTab.value === 'DONE')
  ]
    .filter(Boolean)
    .join('；')
}
const businessLabel = (row: TaskRow) =>
  row.project?.label || (row.applicationId ? applicationNames.value[row.applicationId] || '应用任务' : '独立任务')
const businessHint = (row: TaskRow) =>
  [businessLabel(row), row.project && row.applicationId ? applicationNames.value[row.applicationId] : '']
    .filter(Boolean)
    .join(' · ')
const ownerHint = (row: TaskRow) =>
  [
    `负责人：${taskAssignmentLabel(row, row.assigneeName)}`,
    row.acceptorId ? `验收人：${row.acceptorName || '已指定'}` : ''
  ]
    .filter(Boolean)
    .join('\n')
const readable = (row: TaskRow) =>
  row.detailVisible !== false && (selectedTab.value !== 'CLAIMABLE' || row.canEdit || row.canExecute)
const quickAction = ref<{ task: TaskRow; action: 'COMPLETE' | 'PAUSE' | 'RESUME' }>(),
  detailInitialTab = ref<'overview' | 'business' | 'comments' | 'arrangement'>('overview'),
  detailInitialEntryKey = ref<string>(),
  detailInitialSelectedId = ref<string>(),
  detailCommentId = ref('')
const planTarget = ref<'SELF' | 'ASSIGNEE'>('SELF')
const planPeriod = ref<TaskChecklistChoice>('WEEK'),
  planAction = ref<'ADD' | 'REMOVE'>('ADD')
const historyPlan = ref(false)
const planIds = ref<string[]>([]),
  detailId = ref(''),
  expanded = ref<string[]>([]),
  children = ref<Record<string, TaskRow[]>>({}),
  busyIds = ref<string[]>([])
function arrange(
  ids: string[],
  period: TaskChecklistChoice = inlinePeriod.value || 'WEEK',
  action: 'ADD' | 'REMOVE' = 'ADD'
) {
  planTarget.value =
    !planReadonly.value && ids.every(id => rows.value.some(row => row.id === id && ownsPlan(row))) ? 'SELF' : 'ASSIGNEE'
  planPeriod.value = period
  planAction.value = action
  planIds.value = ids
}
function openDetail(
  id: string,
  tab: 'overview' | 'business' | 'comments' | 'arrangement' = 'overview',
  entryKey?: string
) {
  detailInitialTab.value = tab
  detailInitialEntryKey.value = entryKey
  detailInitialSelectedId.value = undefined
  detailCommentId.value = ''
  detailId.value = id
}
function openStructure(anchorTaskId: string, selectedId: string) {
  openDetail(anchorTaskId, 'arrangement')
  detailInitialSelectedId.value = selectedId
}
function inspectRow(row: TaskRow) {
  if (readable(row)) openDetail(row.id)
  else if (row.anchorTaskId) openStructure(row.anchorTaskId, row.id)
}
interface RowAction {
  key: string
  label: string
  visible: boolean | undefined
  primary?: boolean
  loading?: boolean
  danger?: boolean
  disabled?: boolean
  reason?: string
  run: () => void
}
// 协调与执行分开：概要节点只展示服务端确认的协调动作，不放开详情和办理。
function rowActions(row: TaskRow) {
  const assignmentAction: RowAction = {
    key: 'assign',
    label: taskAssignmentActionLabel(row),
    primary: !personal.value,
    visible: !!(row.canAssign || row.canDelegate || row.canTransfer),
    run: () => {
      assignmentTask.value = row
    }
  }
  const deleteAction: RowAction = {
    key: 'delete-subtask',
    label: '删除子任务',
    visible:
      personal.value &&
      !!row.parentId &&
      (row.canDelete === true || (!row.contextOnly && row.detailVisible !== false && row.canDelete !== undefined)),
    danger: true,
    disabled: row.canDelete !== true,
    reason: row.canDelete === true ? undefined : row.deleteBlockedReason || '当前任务不可删除',
    run: () => {
      if (row.canDelete === true) deleteTask.value = row
    }
  }
  // 安全分组摘要与已办页的进行中上下文只有浏览意义，不能借分组获得执行权限。
  if (
    personal.value &&
    ((row.contextOnly && !(personalTab.value === 'PLAN' && (ownsPlan(row) || row.canAccept))) ||
      row.detailVisible === false ||
      personalTab.value === 'DONE' ||
      ['COMPLETED', 'CANCELLED'].includes(row.status))
  )
    return {
      primary: undefined,
      split: undefined,
      more: [
        ...(personalTab.value !== 'DONE' && !['COMPLETED', 'CANCELLED'].includes(row.status) ? [assignmentAction] : []),
        deleteAction
      ].filter(action => action.visible)
    }
  const actions: RowAction[] = [
    {
      key: 'resume',
      label: '恢复任务',
      primary: true,
      visible: row.canResume,
      run: () => {
        quickAction.value = { task: row, action: 'RESUME' }
      }
    },
    {
      key: 'start',
      label: row.canStart && taskStartsEarly(row) ? '提前开始' : '开始',
      primary: true,
      visible: !!row.assigneeId && row.canExecute && row.status === 'PENDING',
      disabled: !row.canStart,
      reason: row.canStart ? undefined : row.blockedReason || '当前条件未满足，请查看详情',
      loading: busyIds.value.includes(row.id),
      run: () => {
        void start(row)
      }
    },
    {
      key: 'claim',
      label: row.id === row.rootId ? '领取任务' : '只领这一项',
      primary: true,
      visible: row.canClaim,
      run: () => {
        claimTask.value = row
      }
    },
    {
      key: 'accept',
      label: '验收',
      primary: true,
      visible: readable(row) && row.canAccept,
      run: () => openDetail(row.id)
    },
    {
      key: 'work',
      label: '办理',
      primary: true,
      visible: readable(row) && !row.canAccept && row.status === 'RUNNING' && row.canExecute,
      run: () => openDetail(row.id, 'business')
    },
    assignmentAction,
    {
      key: 'split',
      label: personal.value ? (row.childCount > 0 ? '添加子任务' : '拆分子任务') : '＋ 子任务',
      visible:
        (personal.value ? ownsPlan(row) && !historyPlan.value : hasPermission('nocode:task:create')) &&
        (row.canEdit || row.canExecute) &&
        ['PENDING', 'RUNNING'].includes(row.status),
      run: () => {
        void split(row)
      }
    },
    {
      key: 'complete',
      label: taskNeedsAcceptance(row) ? '提交验收' : '完成',
      visible: !row.childCount && row.status === 'RUNNING' && row.canExecute,
      run: () => {
        quickAction.value = { task: row, action: 'COMPLETE' }
      }
    },
    { key: 'comments', label: '评论', visible: readable(row), run: () => openDetail(row.id, 'comments') },
    {
      key: 'pause',
      label: '暂停任务',
      visible: row.canPause,
      run: () => {
        quickAction.value = { task: row, action: 'PAUSE' }
      }
    },
    {
      key: 'plan',
      label: canPlan(row) ? '计划清单' : '查看计划记录',
      visible: canPlan(row) || row.plans.length > 0,
      run: () => arrange([row.id])
    },
    {
      key: 'today',
      label: '加入今日',
      visible: planView.value && selectedTab.value === 'WEEK' && canPlan(row),
      run: () => arrange([row.id], 'DAY')
    },
    {
      key: 'removeToday',
      label: '移出今日',
      visible:
        planView.value &&
        !planReadonly.value &&
        ownsPlan(row) &&
        selectedTab.value === 'TODAY' &&
        !historyPlan.value &&
        row.plans.some(plan => plan.mode === 'CHECKLIST' && plan.period === 'DAY' && plan.canCancel),
      run: () => arrange([row.id], 'DAY', 'REMOVE')
    },
    { key: 'unlink', label: '解除关联', visible: row.canUnlink, danger: true, run: () => emit('unlink', row) },
    deleteAction
  ]
  const available = actions.filter(action => action.visible)
  const primary =
    (management.value && !row.assigneeId ? available.find(action => action.key === 'assign') : undefined) ||
    available.find(action => action.primary)
  const splitAction = personal.value && !row.childCount ? available.find(action => action.key === 'split') : undefined
  const more = available.filter(action => action !== primary && action !== splitAction)
  if (splitAction && readable(row))
    more.unshift({ key: 'detail', label: '详情', visible: true, run: () => openDetail(row.id) })
  return { primary, split: splitAction, more }
}
const inlineParent = ref<TaskRow | null>(null),
  inlineTitle = ref(''),
  inlineIncludePlan = ref(false),
  inlineBusy = ref(false),
  inlineKey = ref(uuid()),
  inlineInput = ref<{ focus: () => void }>()
const inlineNode = ref(newTaskNode())
const splitPlan = ref<TaskSplitPlan>('LATER')
const splitError = ref('')
const splitDialog = ref<{ focus: () => void }>()
const listElement = ref<HTMLElement>()
const createdTaskId = ref('')
const inlineScheduleOpen = ref(false)
const inlineRootAssigneeId = ref<TaskRow['assigneeId']>(null)
const inlineRootKnown = ref(false)
const inlineSelfAssigned = computed(() => {
  const id =
    inlineNode.value.assigneeId ??
    (inlineNode.value.assignmentMode === 'FOLLOW_ROOT' ? inlineRootAssigneeId.value : null)
  return id != null && String(id) === String(user.userInfo?.id)
})
const inlinePeriod = computed<TaskChecklistChoice | null>(() =>
  selectedTab.value === 'TODAY'
    ? 'DAY'
    : selectedTab.value === 'WEEK'
      ? personal.value && planWorkspaceView.value === 'NEXT_WEEK'
        ? 'NEXT_WEEK'
        : 'WEEK'
      : null
)
const pendingInlinePlan = ref<{
  id: string
  title: string
  period: TaskChecklistChoice
  target: 'SELF'
  error: string
  continueAdding: boolean
}>()
const retryPlanBusy = ref(false)
let inlinePlanAttempt = createChecklistAttempt()
let childrenGeneration = 0
let expansionRequest = 0
const expansionRequests = new Map<string, number>()
const childLoads = new Map<string, Promise<boolean>>()
onBeforeUnmount(() => childrenGeneration++)
let splitRequest = 0
function emitRows() {
  emit('loaded', [
    ...new Map([...Object.values(children.value).flat(), ...tableData.value].map(row => [row.id, row])).values()
  ])
}
const defaultListQuery = () => ({
  search: '',
  category: '' as TaskQuery['category'],
  status: '' as TaskQuery['status'],
  assignmentMode: '' as TaskQuery['assignmentMode'],
  priority: '' as TaskQuery['priority'],
  entryId: '',
  from: '',
  to: '',
  date: dayjs().format('YYYY-MM-DD'),
  recentPeriod: 'DAY' as const,
  planFilter: 'PLANNED' as const,
  assigneeId: ''
})
const table = useOsTablePage<
  TaskRow,
  {
    search: string
    category: TaskQuery['category']
    status: TaskQuery['status']
    assignmentMode: TaskQuery['assignmentMode']
    priority: TaskQuery['priority']
    entryId: string
    from: string
    to: string
    date: string
    recentPeriod: 'DAY' | 'WEEK' | 'MONTH'
    planFilter: NonNullable<TaskQuery['planFilter']>
    assigneeId: string
  }
>({
  defaultQuery: defaultListQuery,
  fetchFn: async params => {
    const generation = ++childrenGeneration
    children.value = {}
    expanded.value = []
    busyIds.value = []
    personalTreeQuery.value = undefined
    error.value = ''
    listFailed.value = false
    // 领取目录独立采用安全组摘要，不能伪装成有完整权限的 TaskRow。
    if (personal.value && personalTab.value === 'CLAIMABLE') return { list: [], total: 0 }
    hasAppliedFilters.value = !!(
      params.search?.trim() ||
      params.category ||
      params.status ||
      params.assignmentMode ||
      params.priority ||
      params.entryId ||
      params.from ||
      params.to ||
      (management.value && params.assigneeId) ||
      project.value
    )
    const query: TaskQuery = {
      scope: props.scope,
      tab: management.value ? 'ALL' : selectedTab.value,
      date: params.date,
      recentPeriod: params.recentPeriod,
      project: selectedTab.value === 'CLAIMABLE' ? null : project.value,
      pageNo: params.pageNum,
      pageSize: params.pageSize,
      ...(params.search?.trim() ? { search: params.search.trim() } : {}),
      ...(params.category ? { category: params.category } : {}),
      ...(personal.value && personalTab.value === 'SUBMITTED'
        ? { status: 'PENDING_ACCEPTANCE' as const }
        : params.status
          ? { status: params.status }
          : {}),
      ...(planView.value
        ? {
            scheduleScope: 'PERSONAL' as const,
            planMode: 'CHECKLIST' as const,
            planFilter:
              planWorkspaceView.value === 'ALL'
                ? undefined
                : planWorkspaceView.value === 'UNPLANNED'
                  ? ('UNPLANNED' as const)
                  : ('PLANNED' as const)
          }
        : {}),
      ...(params.assignmentMode ? { assignmentMode: params.assignmentMode } : {}),
      ...(params.priority ? { priority: params.priority } : {}),
      ...(params.entryId && selectedTab.value !== 'CLAIMABLE' ? { entryId: params.entryId } : {}),
      ...(params.from ? { from: params.from } : {}),
      ...(params.to ? { to: params.to } : {})
    }
    if (props.context) return await api.pageTasks(props.context, query)
    if (management.value) {
      if (employeeOverview.value) return { list: [], total: 0 }
      const employee = employeeTasks.value ? selectedEmployee.value : undefined
      return await api.managementPage({
        query: {
          ...query,
          ...(employee ? { date: employee.date } : params.assigneeId ? { assigneeId: params.assigneeId } : {})
        },
        focus: employee ? 'ALL' : managementFocus.value,
        ...(employee ? { employeeId: employee.userId, employeeMetric: employee.metric, groupByRoot: true } : {})
      })
    }
    if (!personalTree.value) return await api.page(query)
    const result = await api.personalTreePage(query)
    // 展开沿用已提交的筛选，输入框未提交的变化和过期分页请求不能串入当前树。
    if (generation === childrenGeneration) personalTreeQuery.value = query
    return result
  },
  afterFetch: data => {
    // 仅发布表格已接纳的当前页，过期请求不可触发外层业务字段回填。
    emit('loaded', data)
    return data
  },
  onError: e => {
    listFailed.value = true
    error.value = errorMessage(e)
  },
  clearDataOnError: true,
  correctOutOfRange: true,
  queryMode: 'submitted'
})
const { queryForm, tableData, loading, pagination, selectedRowKeys, selectedRows } = table
watch(
  () => queryForm.entryId,
  value => {
    // 已有筛选值仍需解析名称，不能为了按需加载清空用户选择。
    if (value && !entries.value.some(entry => entry.value === value)) void loadEntryOptions()
  },
  { immediate: true }
)
const planUnit = computed(() => (selectedTab.value === 'WEEK' ? 'week' : 'day'))
const planUnitLabel = computed(() => ({ day: '天', week: '周', month: '月' })[planUnit.value])
const showPlanDate = computed(() => ['TODAY', 'WEEK'].includes(selectedTab.value))
function planInheritanceHint(row: TaskRow) {
  if ((employeeTasks.value ? !employeeOwnsTask(row) : !ownsPlan(row)) || row.detailVisible === false) return ''
  const date = dayjs(employeeTasks.value ? selectedEmployee.value?.date : queryForm.date).format('YYYY-MM-DD')
  return row.plans
    .filter(
      plan =>
        plan.mode === 'CHECKLIST' &&
        plan.active !== false &&
        isInheritedTaskPlan(plan) &&
        (plan.endDate || plan.date) >= date &&
        (plan.userId == null || String(plan.userId) === String(row.assigneeId))
    )
    .map(plan => `${taskPlanSummaryLabel(plan, date)} · ${taskPlanSourceLabel(plan)}`)
    .join('；')
}
function planMembershipLabel(row: TaskRow) {
  if ((employeeTasks.value ? !employeeOwnsTask(row) : !ownsPlan(row)) || row.detailVisible === false) return ''
  const date = dayjs(employeeTasks.value ? selectedEmployee.value?.date : queryForm.date)
  const week = date.subtract((date.day() + 6) % 7, 'day').format('YYYY-MM-DD')
  const plans = row.plans.filter(
    plan =>
      plan.mode === 'CHECKLIST' &&
      plan.active !== false &&
      (plan.userId == null || String(plan.userId) === String(row.assigneeId))
  )
  const today = plans.some(plan => plan.period === 'DAY' && plan.date === date.format('YYYY-MM-DD'))
  const weekly = plans.some(plan => plan.period === 'WEEK' && plan.date === week)
  const nextWeek = plans.some(
    plan => plan.period === 'WEEK' && plan.date === dayjs(week).add(7, 'day').format('YYYY-MM-DD')
  )
  if (showPlanDate.value) return (selectedTab.value === 'TODAY' ? today : weekly) ? '' : '未纳入当前计划，仅关联展示'
  const labels = [today ? '今日' : '', weekly ? '本周' : '', nextWeek ? '下周' : ''].filter(Boolean)
  if (labels.length) return labels.join(' · ')
  if (plans.some(plan => (plan.endDate || plan.date) >= date.format('YYYY-MM-DD'))) return '已安排'
  return ['PENDING', 'RUNNING', 'PAUSED'].includes(row.status) ? '未纳入' : ''
}
const showPlanColumn = computed(
  () =>
    employeeTasks.value ||
    (personal.value && personalTab.value === 'PLAN' && ['ALL', 'UNPLANNED'].includes(planWorkspaceView.value))
)
const contextHint = (row: TaskRow) => {
  if (!personal.value) return ''
  if (
    row.contextOnly &&
    (row.detailVisible === false || (!ownsPlan(row) && !row.canAccept && !row.canPause && !row.canResume))
  )
    return '关联任务，仅供参考'
  if (personalTab.value === 'PLAN' && showPlanDate.value) return planMembershipLabel(row)
  if (personalTab.value === 'PLAN' && planWorkspaceView.value === 'UNPLANNED' && row.contextOnly)
    return '此任务不属于未安排项，仅为展示同组未安排任务而保留'
  return ''
}
const periodTitle = computed(() => {
  const date = dayjs(queryForm.date)
  if (selectedTab.value === 'WEEK') {
    const start = date.subtract((date.day() + 6) % 7, 'day')
    return `${start.format('YYYY-MM-DD')} 至 ${start.add(6, 'day').format('MM-DD')}`
  }
  return date.format('YYYY-MM-DD')
})
async function changePlanDate(date: string) {
  if (!date || !(await cancelInline())) return
  queryForm.date = date
  table.clearSelection()
  table.handleQuery()
}
function shiftPlanDate(direction: number) {
  void changePlanDate(dayjs(queryForm.date).add(direction, planUnit.value).format('YYYY-MM-DD'))
}
const emptyDescription = computed(() => {
  if (loading.value) return '正在加载任务…'
  if (listFailed.value) return '任务列表加载失败'
  if (hasAppliedFilters.value) return '没有符合筛选条件的任务'
  if (management.value) return '没有符合当前条件的任务'
  if (selectedTab.value === 'CLAIMABLE') return '暂时没有可领取的任务'
  if (selectedTab.value === 'POOL' && props.scope === 'MINE') return '暂无需要你执行的未完成任务'
  if (personal.value && personalTab.value === 'DONE') return '还没有已办记录'
  if (personal.value && personalTab.value === 'SUBMITTED') return '暂无待你验收的任务'
  if (planView.value && planWorkspaceView.value === 'UNPLANNED') return '没有未纳入当前或未来计划的未完成任务'
  if (showPlanDate.value) return '这一期间还没有安排任务'
  if (selectedTab.value === 'RECENT') return '这一期间暂无处理记录'
  if (props.embedded)
    return selectedTab.value === 'POOL' ? '当前范围暂无未结束任务，可切换全部任务查看' : '当前范围暂无任务'
  return personal.value ? '暂无分配给你的任务' : '任务池暂无任务'
})
type ViewRow = TaskRow & { depth: number; inline?: boolean; expandedChild?: boolean }
const splitPath = computed(() =>
  inlineParent.value
    ? taskSplitPath(inlineParent.value, [...tableData.value, ...Object.values(children.value).flat()])
    : ''
)
const rows = computed<ViewRow[]>(() => {
  const result: ViewRow[] = [],
    seen = new Set<string>(),
    all = new Map<string, TaskRow>()
  Object.values(children.value)
    .flat()
    .forEach(row => all.set(row.id, row))
  tableData.value.forEach(row => all.set(row.id, row))
  const visit = (row: TaskRow, depth: number) => {
    if (seen.has(row.id)) return
    seen.add(row.id)
    result.push({ ...row, depth, expandedChild: !tableData.value.some(item => item.id === row.id) })
    if (!expanded.value.includes(row.id)) return
    // 只展开命中行的授权后代；显式关联到其他记录的任务，其子项仍属于此工作上下文。
    const nested = personalTree.value
      ? children.value[row.id] || []
      : [...all.values()].filter(n => n.parentId === row.id)
    nested.forEach(child => visit(child, depth + 1))
    if (!personal.value && inlineParent.value?.id === row.id)
      result.push({ ...row, id: `inline:${row.id}`, depth: depth + 1, inline: true })
  }
  tableData.value
    .filter(row =>
      personal.value
        ? true
        : management.value
          ? row.id === row.rootId
          : !row.parentId || !tableData.value.some(n => n.id === row.parentId)
    )
    .forEach(row => visit(row, 0))
  return result
})
const pageWorkCount = computed(() => tableData.value.filter(row => !row.childCount).length)
const pageSummaryCount = computed(() => tableData.value.length - pageWorkCount.value)
const hierarchy = computed(() => taskHierarchy(rows.value.filter(row => !row.inline)))
function rowHierarchy(row: ViewRow): TaskHierarchyItem {
  if (row.inline)
    return {
      depth: row.depth,
      outline: '',
      label: '新增子任务',
      parentTitle: inlineParent.value?.title || '未命名上级任务',
      missingParent: false,
      childCount: 0
    }
  const item = hierarchy.value.get(row.id)!
  return {
    ...item,
    // 已展开的树靠缩进表达归属；单独出现在待办的子任务仍保留上级名称。
    parentTitle: roleList.value
      ? row.depth && !item.missingParent
        ? ''
        : row.ancestorContext?.find(node => node.id === row.parentId)?.title || item.parentTitle
      : taskParentContextLabel(row) || item.parentTitle,
    depth: row.depth,
    childCount: displayedChildCount(row)
  }
}
function displayedChildCount(row: TaskRow) {
  return personalTree.value ? (row.matchingChildCount ?? row.childCount) : row.childCount
}
const titleWidth = computed(
  () => (roleList.value ? 280 : 360) + Math.min(3, Math.max(0, ...rows.value.map(row => row.depth))) * 20
)
const columns = computed(() => {
  const available = [
    {
      title: '任务 / 子任务',
      key: 'title',
      width: titleWidth.value,
      fixed: roleList.value ? ('left' as const) : undefined,
      align: 'left' as const
    },
    ...props.businessColumns.map(column => ({ title: column.title, key: `business:${column.key}`, width: 170 })),
    ...(!roleList.value
      ? [
          {
            title: '业务归属 / 负责人',
            key: 'owner',
            width: 185
          }
        ]
      : []),
    ...(groupedList.value ? [{ title: '进度', key: 'progress', width: 115 }] : []),
    {
      title: roleList.value ? '状态' : '执行状态',
      key: 'status',
      width: roleList.value ? 105 : 150
    },
    { title: '预计开始', key: 'expectedStart', width: roleList.value ? 125 : 170 },
    {
      title: selectedTab.value === 'RECENT' ? '处理时间' : roleList.value ? '预计完成' : '预计时间',
      key: 'time',
      width: roleList.value ? 135 : 185
    },
    ...(roleList.value ? [{ title: '负责人 / 业务', key: 'owner', width: 150 }] : []),
    ...(showPlanColumn.value ? [{ title: employeeTasks.value ? '个人清单' : '计划', key: 'plan', width: 105 }] : []),
    { title: '预计结束', key: 'expectedEnd', width: 170 },
    { title: '实际开始', key: 'actualStart', width: 170 },
    { title: '实际完成时间', key: 'actualEnd', width: 170 },
    { title: '优先级', key: 'priority', width: 90 },
    {
      title: '操作',
      key: 'actions',
      width: personal.value ? 245 : 235,
      fixed: 'right' as const,
      align: 'left' as const
    }
  ]
  const keys = props.view?.columnKeys
  const items = keys?.length
    ? [
        available[0]!,
        ...keys
          .filter(key => key !== 'title' && key !== 'actions')
          .map(key => available.find(column => column.key === key))
          .filter((column): column is (typeof available)[number] => !!column),
        available[available.length - 1]!
      ]
    : available
  return items.map((column, index) => ({
    ...column,
    customCell: (row: ViewRow) =>
      row.inline
        ? { colSpan: index === 0 ? items.length : 0 }
        : {
            class:
              [
                roleList.value && row.parentId ? 'task-list__child-cell' : '',
                roleList.value && row.status === 'COMPLETED' ? 'task-list__completed-cell' : '',
                column.key === 'title' && row.id === createdTaskId.value ? 'task-list__created-anchor' : ''
              ]
                .filter(Boolean)
                .join(' ') || undefined
          }
  }))
})
const tabs = computed<Array<{ key: ListTab; label: string }>>(() =>
  props.scope === 'MANAGE' || props.embedded
    ? [
        { key: 'POOL', label: '未结束' },
        { key: 'ALL', label: '全部任务' },
        { key: 'RECENT', label: '近期处理' }
      ]
    : [
        { key: 'PLAN', label: '我的计划' },
        { key: 'CLAIMABLE', label: '可领取任务' },
        { key: 'SUBMITTED', label: '待验收' },
        { key: 'DONE', label: '已办记录' }
      ]
)
async function refresh() {
  employeeRefreshKey.value++
  if (personal.value && personalTab.value === 'CLAIMABLE') {
    await claimableGroups.value?.refresh()
    return
  }
  const restore = [...expanded.value]
  childrenGeneration++
  children.value = {}
  const fetched = table.fetchData()
  const generation = childrenGeneration
  await fetched
  for (const id of restore) {
    if (generation !== childrenGeneration) break
    const row = [...tableData.value, ...Object.values(children.value).flat()].find(n => n.id === id)
    if (row && (await loadChildren(row))) expanded.value = [...new Set([...expanded.value, id])]
  }
}
async function afterPlanSaved() {
  // 已移入/移出的行可能不再命中筛选，清掉旧勾选再取回列表和已展开的子项。
  table.clearSelection()
  await refresh()
}
async function afterSubtaskDeleted(id: string) {
  deleteTask.value = undefined
  if (detailId.value === id) detailId.value = ''
  expanded.value = expanded.value.filter(expandedId => expandedId !== id)
  table.clearSelection()
  await refresh()
}
async function loadChildren(row: TaskRow, clearError = true) {
  const key = `${childrenGeneration}:${row.id}`,
    pending = childLoads.get(key)
  if (pending) return pending
  const request = fetchChildren(row, clearError)
  childLoads.set(key, request)
  try {
    return await request
  } finally {
    if (childLoads.get(key) === request) childLoads.delete(key)
  }
}
async function fetchChildren(row: TaskRow, clearError: boolean) {
  const token = childrenGeneration
  if (busyIds.value.includes(row.id)) return false
  if (clearError) error.value = ''
  busyIds.value = [...busyIds.value, row.id]
  try {
    if (personalTree.value) {
      const query = personalTreeQuery.value
      if (!query) return false
      const nodes = await api.personalTreeChildren(query, row.id)
      if (token !== childrenGeneration) return false
      children.value = { ...children.value, [row.id]: nodes }
      // 刷新后可用数量可能变化，不能留下只能空展开的箭头。
      const update = (node: TaskRow) => (node.id === row.id ? { ...node, matchingChildCount: nodes.length } : node)
      tableData.value = tableData.value.map(update)
      for (const [id, nodes] of Object.entries(children.value)) children.value[id] = nodes.map(update)
      emitRows()
      return true
    }
    const detail = await api.detail(row.id)
    if (token === childrenGeneration) {
      const current = new Map(detail.nodes.map(node => [node.id, node]))
      // 详情更新任务版本，但记录区块上的关联身份由 page-tasks 授权返回，不能被实例详情覆盖。
      tableData.value = tableData.value.map(node => {
        const updated = current.get(node.id)
        return updated
          ? {
              ...updated,
              explicitLinkId: node.explicitLinkId,
              canUnlink: node.canUnlink,
              lastHandledAt: node.lastHandledAt,
              groupStatus: updated.groupStatus ?? node.groupStatus
            }
          : node
      })
      for (const id of Object.keys(children.value))
        children.value[id] = children.value[id]!.map(node => current.get(node.id) || node)
      children.value = { ...children.value, [row.id]: detail.nodes.filter(n => n.id !== row.id) }
      emitRows()
      return true
    }
  } catch (e) {
    if (token === childrenGeneration) error.value = errorMessage(e)
  } finally {
    if (token === childrenGeneration) busyIds.value = busyIds.value.filter(id => id !== row.id)
  }
  return false
}
async function toggle(row: TaskRow) {
  if (busyIds.value.includes(row.id)) return
  if (expanded.value.includes(row.id)) {
    if (inlineParent.value?.id === row.id && !(await cancelInline())) return
    expansionRequests.set(row.id, ++expansionRequest)
    expanded.value = expanded.value.filter(id => id !== row.id)
  } else {
    await expandBranch(row)
  }
}
async function expandBranch(row: TaskRow) {
  const token = childrenGeneration,
    request = ++expansionRequest,
    seen = new Set<string>()
  expansionRequests.set(row.id, request)
  const current = (ancestors: string[]) =>
    token === childrenGeneration &&
    expansionRequests.get(row.id) === request &&
    ancestors.every(id => expanded.value.includes(id))
  const visit = async (node: TaskRow, ancestors: string[]) => {
    if (!current(ancestors) || seen.has(node.id)) return
    seen.add(node.id)
    // 个人树仅递归服务端公开的下级摘要；其他入口的授权详情已包含整组，不重复逐层请求。
    if ((personalTree.value || !ancestors.length) && !(await loadChildren(node, !ancestors.length))) return
    if (!current(ancestors)) return
    expanded.value = [...new Set([...expanded.value, node.id])]
    const nested = personalTree.value
      ? children.value[node.id] || []
      : [
          ...new Map(
            Object.values(children.value)
              .flat()
              .map(item => [item.id, item])
          ).values()
        ].filter(item => item.parentId === node.id)
    for (const child of nested) {
      if (displayedChildCount(child) > 0) await visit(child, [...ancestors, node.id])
    }
  }
  await visit(row, [])
}
async function split(row: TaskRow) {
  if (personal.value && !rowActions(row).split && !rowActions(row).more.some(action => action.key === 'split')) return
  if (pendingInlinePlan.value) {
    message.warning('请先重试上一项的计划，或选择稍后从任务行安排')
    return
  }
  if (inlineParent.value && !(await cancelInline())) return
  const request = ++splitRequest,
    generation = childrenGeneration
  let root = [row, ...tableData.value, ...Object.values(children.value).flat()].find(node => node.id === row.rootId)
  if (!root && !personal.value) {
    try {
      // 个人树可能只有当前子任务，读取当前任务的授权详情，不请求不可见总任务或用上级代替。
      const detail = await api.detail(row.id)
      root = detail.nodes.find(node => node.id === row.rootId)
    } catch (e) {
      if (mounted && request === splitRequest && generation === childrenGeneration)
        error.value = `总任务负责人加载失败，请重试添加子任务：${errorMessage(e)}`
      return
    }
  }
  if (!mounted || request !== splitRequest || generation !== childrenGeneration) return
  inlineParent.value = row
  inlineNode.value = newAutoTaskNode(row.id, root)
  if (personal.value) {
    inlineNode.value.assigneeId = row.assigneeId
    inlineNode.value.assignmentMode = 'ASSIGNED'
    inlineNode.value.candidateUserIds = []
    splitPlan.value = 'LATER'
  }
  splitError.value = ''
  inlineScheduleOpen.value = false
  inlineRootAssigneeId.value = root?.assigneeId ?? null
  inlineRootKnown.value = !!root
  inlineTitle.value = ''
  inlineIncludePlan.value = false
  inlineKey.value = uuid()
  expanded.value = [...new Set([...expanded.value, row.id])]
  await loadChildren(row)
  await nextTick()
  inlineInput.value?.focus()
}
async function cancelInline() {
  if (inlineBusy.value || retryPlanBusy.value) return false
  if (!(await confirmDiscard(!!inlineTitle.value.trim(), '放弃尚未添加的子任务？'))) return false
  splitRequest++
  inlineParent.value = null
  inlineTitle.value = ''
  return true
}
async function saveInline(continueAdding = true) {
  if (!inlineParent.value || !inlineTitle.value.trim() || inlineBusy.value || pendingInlinePlan.value) return
  if (!inlineRootKnown.value && inlineNode.value.assignmentMode === 'FOLLOW_ROOT') {
    error.value = '总负责人信息不可用，请为新分工选择指定负责人、开放领取或暂不分配'
    return
  }
  const parent = inlineParent.value,
    generation = childrenGeneration
  inlineBusy.value = true
  error.value = ''
  splitError.value = ''
  try {
    const task = {
      ...newTaskNode(),
      id: inlineKey.value,
      title: inlineTitle.value.trim(),
      assigneeId: inlineNode.value.assigneeId,
      assignmentMode: inlineNode.value.assignmentMode,
      candidateUserIds: inlineNode.value.candidateUserIds,
      urgency: inlineParent.value.urgency,
      priority: inlineParent.value.priority,
      schedule: inlineNode.value.schedule
    }
    const period = personal.value
      ? splitPlan.value === 'LATER'
        ? null
        : splitPlan.value
      : inlineIncludePlan.value
        ? inlinePeriod.value
        : null
    const plan =
      !planReadonly.value && inlineSelfAssigned.value && period
        ? {
            period,
            target: 'SELF' as const
          }
        : null
    const body = { task, parentId: parent.id, requestKey: inlineKey.value }
    const created = personal.value ? await api.split(body) : await api.create({ ...body, project: parent.project })
    // 创建成功后立即清掉创建输入和幂等键，安排计划失败只能重试计划，不能重建子任务。
    if (generation === childrenGeneration && inlineParent.value?.id === parent.id) {
      inlineTitle.value = ''
      inlineKey.value = uuid()
    }
    if (plan) {
      inlinePlanAttempt = createChecklistAttempt()
      try {
        await scheduleInline(created.task.id, plan.period, plan.target)
      } catch (e) {
        pendingInlinePlan.value = {
          id: created.task.id,
          title: task.title,
          ...plan,
          continueAdding,
          error: errorMessage(e)
        }
      }
    }
    if (generation !== childrenGeneration || inlineParent.value?.id !== parent.id) return
    if (personalTree.value) await refreshAfterSplit(created.task.id)
    else await loadChildren(parent)
    if (!pendingInlinePlan.value) {
      message.success(
        personal.value
          ? plan
            ? `子任务已添加并纳入${checklistPeriodLabel(plan.period)}`
            : '子任务已添加，自动随本人上级计划'
          : plan
            ? '子任务已添加并纳入我的计划'
            : '子任务已添加，可以继续输入下一项'
      )
      if (personal.value && !continueAdding) inlineParent.value = null
    }
    await nextTick()
    inlineInput.value?.focus()
  } catch (e) {
    if (generation === childrenGeneration) {
      if (personal.value) splitError.value = errorMessage(e)
      else error.value = errorMessage(e)
    }
  } finally {
    inlineBusy.value = false
    if (!pendingInlinePlan.value) void splitDialog.value?.focus()
  }
}
async function retryInlinePlan() {
  const pending = pendingInlinePlan.value
  if (!pending || retryPlanBusy.value) return
  retryPlanBusy.value = true
  try {
    await scheduleInline(pending.id, pending.period, pending.target)
    pendingInlinePlan.value = undefined
    message.success(`子任务已添加并纳入${checklistPeriodLabel(pending.period)}`)
    await refreshAfterSplit(pending.id)
    if (personal.value && !pending.continueAdding) inlineParent.value = null
    await nextTick()
    splitDialog.value?.focus()
  } catch (e) {
    pending.error = errorMessage(e)
  } finally {
    retryPlanBusy.value = false
  }
}
function deferInlinePlan() {
  const pending = pendingInlinePlan.value
  if (!pending || retryPlanBusy.value || inlineBusy.value) return
  pendingInlinePlan.value = undefined
  if (personal.value && !pending.continueAdding) inlineParent.value = null
  else void splitDialog.value?.focus()
}
async function refreshAfterSplit(id: string) {
  const body = listElement.value?.querySelector<HTMLElement>('.ant-table-body')
  const position = body ? { top: body.scrollTop, left: body.scrollLeft } : undefined
  await refresh()
  if (!mounted) return
  createdTaskId.value = id
  await nextTick()
  const updatedBody = listElement.value?.querySelector<HTMLElement>('.ant-table-body')
  if (!updatedBody || !position) return
  updatedBody.scrollTop = position.top
  updatedBody.scrollLeft = position.left
  // 仅滚动表格内部，避免新增节点后将整个页面拉走；不改变筛选和页码。
  // 名称单元格只作为定位点，不给整行叠加高亮，保持新增前后的表格样式一致。
  const cell = updatedBody.querySelector<HTMLElement>('.task-list__created-anchor')
  if (cell) {
    const bounds = updatedBody.getBoundingClientRect(),
      row = cell.getBoundingClientRect()
    if (row.bottom > bounds.bottom) updatedBody.scrollTop += row.bottom - bounds.bottom
    else if (row.top < bounds.top) updatedBody.scrollTop -= bounds.top - row.top
  }
}
async function scheduleInline(id: string, period: TaskChecklistChoice, target: 'SELF') {
  await inlinePlanAttempt.submit(api, async () => {
    const context = await api.checklistContext({ ids: [id], target })
    return checklistCommand(context, [id], target, period, 'ADD')
  })
}
async function start(row: TaskRow) {
  if (busyIds.value.includes(row.id) || !row.canStart || !row.canExecute) return
  busyIds.value = [...busyIds.value, row.id]
  try {
    if (taskStartsEarly(row) && !(await confirm('确认提前开始？', taskEarlyStartMessage(row), '提前开始', '暂不开始')))
      return
    await api.transition({ id: row.id, expectedRevision: row.revision, action: 'START', note: '', requestKey: uuid() })
    await refresh()
    message.success('已开始执行')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busyIds.value = busyIds.value.filter(id => id !== row.id)
  }
}
async function changeTab(value: string | number) {
  if (!(await cancelInline())) return
  childrenGeneration++
  if (personal.value) {
    personalTab.value = ['POOL', 'TODO'].includes(String(value)) ? 'PLAN' : (value as typeof personalTab.value)
    selectedTab.value =
      personalTab.value === 'PLAN'
        ? planWorkspaceQuery(planWorkspaceView.value).tab
        : personalTab.value === 'DONE'
          ? 'DONE'
          : personalTab.value === 'SUBMITTED'
            ? 'ACCEPTANCE'
            : personalTab.value === 'CLAIMABLE'
              ? 'CLAIMABLE'
              : 'ALL'
  } else selectedTab.value = value as TaskQuery['tab']
  if (planView.value) {
    queryForm.date = personal.value ? planWorkspaceQuery(planWorkspaceView.value).date! : dayjs().format('YYYY-MM-DD')
    historyPlan.value = false
  }
  queryForm.status = ''
  if (selectedTab.value === 'CLAIMABLE') {
    project.value = null
    queryForm.entryId = ''
  }
  children.value = {}
  expanded.value = []
  table.clearSelection()
  table.handleQuery()
}
async function togglePlanHistory() {
  if (historyPlan.value) {
    await changePlanWorkspace(planWorkspaceView.value)
  } else historyPlan.value = true
}
let taskQuerySnapshot: typeof queryForm | undefined
let taskProjectSnapshot: TaskRecordRef | null = null
async function changeManagementView(value: string | number) {
  if (!(await cancelInline())) return
  if (value === 'EMPLOYEES' && managementView.value === 'TASKS') {
    taskQuerySnapshot = { ...queryForm }
    taskProjectSnapshot = project.value
  }
  managementView.value = value === 'EMPLOYEES' ? 'EMPLOYEES' : 'TASKS'
  if (managementView.value === 'EMPLOYEES') employeeOverviewMounted.value = true
  selectedEmployee.value = undefined
  error.value = ''
  table.clearSelection()
  if (managementView.value === 'TASKS' && taskQuerySnapshot) {
    Object.assign(queryForm, taskQuerySnapshot)
    project.value = taskProjectSnapshot
  }
  table.handleQuery()
}
async function selectEmployee(selection: TaskEmployeeSelection) {
  if (!(await cancelInline())) return
  selectedEmployee.value = selection
  project.value = null
  Object.assign(queryForm, defaultListQuery(), { date: selection.date })
  table.clearSelection()
  table.handleQuery()
}
async function changeEmployeeMetric(metric: TaskEmployeeSelection['metric']) {
  if (!selectedEmployee.value) return
  await selectEmployee({ ...selectedEmployee.value, metric })
}
async function changeManagementFocus(focus: TaskManagementFocus) {
  if (!(await cancelInline())) return
  managementFocus.value = focus
  queryForm.status = ''
  table.clearSelection()
  table.handleQuery()
}
function queryTasks() {
  // 显式选择已完成等状态时切到全量，不与默认“未结束”产生互斥条件。
  if (manageRoots.value && queryForm.status) managementFocus.value = 'ALL'
  if (
    employeeTasks.value &&
    selectedEmployee.value &&
    queryForm.status &&
    ['ALL', 'PENDING', 'RUNNING'].includes(selectedEmployee.value.metric)
  ) {
    // 员工显式改筛状态时不再叠加旧快捷状态，避免“未开始 + 已完成”查询永远为空。
    selectedEmployee.value = { ...selectedEmployee.value, metric: 'RELATED' }
  }
  table.handleQuery()
}
async function changePlanWorkspace(value: PlanWorkspaceView) {
  if (!(await cancelInline())) return
  planWorkspaceView.value = value
  const selection = planWorkspaceQuery(value)
  selectedTab.value = selection.tab
  queryForm.date = selection.date!
  historyPlan.value = false
  children.value = {}
  expanded.value = []
  table.clearSelection()
  table.handleQuery()
}
function reset() {
  childrenGeneration++
  project.value = null
  children.value = {}
  expanded.value = []
  table.clearSelection()
  if (showPlanDate.value) {
    // 重置检索条件不改变所选本周、下周或历史日期。
    const date = queryForm.date
    Object.assign(queryForm, defaultListQuery(), { date })
    table.handleDynamicSearch(null)
    table.handleQuery()
  } else table.handleReset()
}
function textValue(value: unknown): string {
  return value == null
    ? '—'
    : typeof value === 'object'
      ? Array.isArray(value)
        ? value.map(textValue).join('、')
        : JSON.stringify(value)
      : String(value)
}
watch(
  () => [
    props.context?.applicationId,
    props.context?.pageId,
    props.context?.nodeId,
    props.context?.recordId,
    JSON.stringify(props.context?.conditions || null),
    props.refreshKey,
    JSON.stringify(props.view || null)
  ],
  (current, previous) => {
    childrenGeneration++
    children.value = {}
    expanded.value = []
    detailId.value = ''
    inlineParent.value = null
    inlineTitle.value = ''
    // 切换记录立即撤下旧数据和办理弹窗，不能在新请求返回前继续操作上一条记录。
    if (current.slice(0, 4).some((value, index) => value !== previous[index])) {
      splitRequest++
      tableData.value = []
      table.clearSelection()
      quickAction.value = undefined
      assignmentTask.value = undefined
      claimTask.value = undefined
      claimLocation.value = undefined
      deleteTask.value = undefined
      planIds.value = []
      pendingInlinePlan.value = undefined
    }
    table.handleQuery()
  }
)
watch(
  () => route.fullPath,
  () => {
    // 同页 URL 导航也先经过未保存保护；获准后收回旧编辑器，但列表条件和分页保留。
    if (props.embedded) return
    splitRequest++
    launchOpen.value = false
    inlineParent.value = null
    inlineTitle.value = ''
  }
)
watch(
  () => [route.query.launch, route.query.templateId],
  async ([launch, templateId]) => {
    if (launch !== '1' || props.embedded || props.scope !== 'MANAGE') return
    const token = ++launchRequest,
      path = route.path,
      query = { ...route.query }
    delete query.launch
    delete query.templateId
    // 先消费参数再挂载表单，内部 URL 整理不会触发新表单的未保存确认。
    const failure = await router.replace({ path, query, hash: route.hash })
    if (failure || token !== launchRequest || path !== route.path) return
    if (canLaunch.value) openLaunch(typeof templateId === 'string' ? templateId : undefined)
    else error.value = '当前没有新建任务权限'
  },
  { immediate: true }
)
watch(
  () => [route.query.taskId || route.query.task, route.query.commentId],
  ([value, commentId], previous) => {
    if (!props.embedded && typeof value === 'string') {
      detailId.value = value
      detailCommentId.value = typeof commentId === 'string' ? commentId : ''
    } else if (!props.embedded && typeof previous?.[0] === 'string') detailId.value = ''
  },
  { immediate: true }
)
useUnsavedNavigation(() => !!inlineTitle.value.trim(), { confirm: confirmDiscard })
onMounted(async () => {
  try {
    const applications = await platform.runtime.mine()
    if (mounted)
      applicationNames.value = Object.fromEntries(applications.map(application => [application.id, application.name]))
  } catch {
    // 名称仅用于补充归属提示，目录失权或加载失败不能阻止原任务列表使用。
    if (mounted) applicationNames.value = {}
  }
})
onMounted(async () => {
  try {
    const available = await api.members()
    if (mounted) members.value = available
  } catch {
    // 人员候选独立于来源目录加载，失败不阻断任务列表。
  }
})
defineExpose({ openDetail })
</script>
<template>
  <section ref="listElement" class="task-list nocode-list-page" :class="{ 'task-list--embedded': embedded }">
    <div v-show="!launchOpen" class="task-list__content nocode-list-page">
      <a-tabs v-if="management" :active-key="managementView" class="task-list__tabs" @change="changeManagementView">
        <a-tab-pane key="TASKS" tab="按任务" />
        <a-tab-pane key="EMPLOYEES" tab="按员工" />
      </a-tabs>
      <div v-else-if="embedded" class="task-list__workspace">
        <a-space wrap aria-label="任务范围筛选">
          <a-button
            v-for="tab in tabs"
            :key="tab.key"
            :type="activeTab === tab.key ? 'primary' : 'default'"
            :aria-pressed="activeTab === tab.key"
            @click="changeTab(tab.key)"
          >
            {{ tab.label }}
          </a-button>
        </a-space>
      </div>
      <a-tabs v-else :active-key="activeTab" class="task-list__tabs" @change="changeTab">
        <a-tab-pane v-for="tab in tabs" :key="tab.key" :tab="tab.label" />
      </a-tabs>
      <TaskEmployeeOverview
        v-if="management && employeeOverviewMounted"
        v-show="employeeOverview"
        :refresh-key="employeeRefreshKey"
        @select="selectEmployee"
      />
      <div v-if="manageRoots && !employeeOverview" class="task-list__workspace">
        <a-space wrap aria-label="任务关注筛选">
          <a-button
            v-for="option in managementFocusOptions"
            :key="option.value"
            :type="managementFocus === option.value ? 'primary' : 'default'"
            :aria-pressed="managementFocus === option.value"
            @click="changeManagementFocus(option.value)"
          >
            {{ option.label }}
          </a-button>
        </a-space>
      </div>
      <div v-if="employeeTasks && selectedEmployee" class="task-list__employee-toolbar">
        <div class="task-list__employee-identity">
          <a-button
            type="text"
            aria-label="返回员工汇总"
            title="返回员工汇总"
            @click="changeManagementView('EMPLOYEES')"
          >
            <template #icon><ArrowLeftOutlined /></template>
          </a-button>
          <strong>{{ selectedEmployee.userName }}</strong>
        </div>
        <div class="task-list__employee-filter">
          <span class="task-list__hint">查看范围</span>
          <a-select
            :value="selectedEmployee.metric"
            aria-label="员工任务筛选"
            :options="employeeMetricOptions"
            @change="changeEmployeeMetric"
          />
          <span v-if="employeeChecklist" class="task-list__hint">{{ employeePeriodLabel }}</span>
        </div>
        <a-tooltip
          title="按该员工负责的节点筛选任务组，展开查看组内协作；高亮为该员工负责，进度为整组可见下级进度。个人清单仅展示该员工的计划，由员工自行维护。"
        >
          <span class="task-list__employee-legend" tabindex="0">
            <i aria-hidden="true" />
            高亮为该员工负责
            <InfoCircleOutlined />
          </span>
        </a-tooltip>
      </div>
      <div v-if="personal && personalTab === 'PLAN'" class="task-list__workspace">
        <div class="task-list__period" aria-label="工作计划视图">
          <a-space wrap>
            <a-button
              v-for="view in planWorkspaceViews"
              :key="view.value"
              :type="planWorkspaceView === view.value ? 'primary' : 'default'"
              :aria-pressed="planWorkspaceView === view.value"
              @click="changePlanWorkspace(view.value)"
            >
              {{ view.label }}
            </a-button>
          </a-space>
          <a-space>
            <span v-if="showPlanDate && !historyPlan" class="task-list__hint">{{ periodTitle }}</span>
            <a-button v-if="showPlanDate" type="text" @click="togglePlanHistory">
              {{ historyPlan ? '返回当前计划' : '查看历史清单' }}
            </a-button>
          </a-space>
        </div>
      </div>
      <p v-if="personal && personalTab === 'DONE'" class="task-list__hint" aria-label="本页工作数量">
        <template v-if="personalTree">
          <template v-if="personalTab === 'DONE'">
            按所属总任务查看我的已办记录；整体仍在进行的任务会保留当前进度。
          </template>
          <template v-else>
            本页 {{ tableData.length }} 项任务入口；展开查看符合当前筛选的下级任务，分页总数按归并后的入口计算。
          </template>
        </template>
        <template v-else>
          本页 {{ pageWorkCount }} 项具体工作、{{ pageSummaryCount }} 项汇总协调；分页总数为任务项数。
        </template>
      </p>
      <div v-if="showPlanDate && historyPlan" class="task-list__period" aria-label="计划日期导航">
        <span>历史清单仅供回看；新增和移出仅操作当前今日或本周计划。</span>
        <strong>{{ periodTitle }}</strong>
        <a-space wrap>
          <a-button :disabled="loading" @click="shiftPlanDate(-1)">前一{{ planUnitLabel }}</a-button>
          <a-date-picker
            :value="queryForm.date"
            value-format="YYYY-MM-DD"
            :allow-clear="false"
            aria-label="查看计划日期"
            @change="(_value: unknown, date: string | string[]) => changePlanDate(String(date))"
          />
          <a-button :disabled="loading" @click="shiftPlanDate(1)">后一{{ planUnitLabel }}</a-button>
          <a-button @click="changePlanDate(dayjs().format('YYYY-MM-DD'))">
            回到{{ selectedTab === 'TODAY' ? '今天' : '本周' }}
          </a-button>
        </a-space>
      </div>
      <a-alert v-if="error" class="notice" type="error" show-icon :message="error" closable @close="error = ''" />
      <a-alert
        v-if="pendingInlinePlan && !(personal && inlineParent)"
        class="notice"
        type="warning"
        show-icon
        :message="`子任务「${pendingInlinePlan.title}」已创建，但未能纳入计划：${pendingInlinePlan.error}`"
      >
        <template #description>
          <a-space>
            <a-button :loading="retryPlanBusy" @click="retryInlinePlan">重试纳入计划</a-button>
            <a-button :disabled="retryPlanBusy" @click="deferInlinePlan">稍后从任务行安排</a-button>
          </a-space>
        </template>
      </a-alert>
      <div v-if="!planReadonly && selectedRowKeys.length" class="task-list__selection">
        <span>已选择 {{ selectedRowKeys.length }} 项</span>
        <a-button type="primary" @click="arrange(selectedRowKeys.map(String), 'WEEK')">加入本周计划</a-button>
        <a-button @click="arrange(selectedRowKeys.map(String), 'DAY')">加入今日计划</a-button>
        <a-button @click="arrange(selectedRowKeys.map(String), 'NEXT_WEEK')">加入下周计划</a-button>
        <a-button
          v-if="planView && showPlanDate && !historyPlan"
          @click="arrange(selectedRowKeys.map(String), inlinePeriod || 'WEEK', 'REMOVE')"
        >
          移出所选{{ checklistPeriodLabel(inlinePeriod || 'WEEK') }}
        </a-button>
        <a-button @click="table.clearSelection">取消选择</a-button>
      </div>
      <TaskClaimableGroups
        v-if="personal && personalTab === 'CLAIMABLE'"
        ref="claimableGroups"
        @detail="openDetail"
        @structure="openStructure"
      />
      <OsTablePage
        v-else-if="!employeeOverview"
        :key="tableKey"
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="pagination"
        server-pagination
        :show-index="false"
        :row-selection="
          planReadonly
            ? false
            : {
                getCheckboxProps: (row: ViewRow) => ({
                  disabled:
                    row.inline ||
                    (row.contextOnly && personalTab !== 'PLAN') ||
                    row.detailVisible === false ||
                    !ownsPlan(row) ||
                    (!canPlan(row) && !row.plans.some(plan => plan.mode === 'CHECKLIST' && plan.canCancel))
                })
              }
        "
        :selected-row-keys="selectedRowKeys"
        :selected-rows="selectedRows"
        :show-batch-bar="false"
        :scroll="{
          x:
            (roleList ? 1110 : 1200) +
            titleWidth -
            280 +
            businessColumns.length * 170 +
            (roleList ? 125 : 0) +
            (groupedList ? 70 : 0) +
            (showPlanColumn ? 105 : 0),
          y: embedded ? undefined : '100%'
        }"
        show-column-settings
        :hidden-column-keys="
          roleList
            ? ['expectedEnd', 'actualStart', 'actualEnd']
            : ['expectedStart', 'expectedEnd', 'actualStart', 'actualEnd']
        "
        resizable
        :column-settings-key="tableKey"
        show-advanced-search
        @change="table.handleTableChange"
        @selection-change="table.updateSelection"
        @search="queryTasks"
      >
        <template #search>
          <a-form layout="inline" @submit.prevent="queryTasks">
            <a-form-item label="名称">
              <a-input
                v-model:value="queryForm.search"
                :placeholder="management ? '搜索总任务或子任务名称' : '搜索任务名称'"
                :title="management ? '匹配任务时显示所属总任务，展开可查看组内协作' : undefined"
                allow-clear
                @press-enter="queryTasks"
              />
            </a-form-item>
            <a-form-item v-if="!roleList" label="业务关联">
              <a-select
                v-model:value="queryForm.category"
                style="width: 160px"
                :options="[
                  { value: '', label: '全部' },
                  { value: 'PROJECT', label: '有关联业务记录' },
                  { value: 'DAILY', label: '未关联业务记录' }
                ]"
              />
            </a-form-item>
            <a-form-item v-if="!(personal && ['DONE', 'SUBMITTED'].includes(personalTab))" label="状态">
              <a-select
                v-model:value="queryForm.status"
                aria-label="任务状态筛选"
                style="width: 120px"
                :options="[
                  { value: '', label: '全部' },
                  ...Object.entries(taskStates).map(([value, label]) => ({ value, label }))
                ]"
              />
            </a-form-item>
            <a-form-item v-if="manageRoots" label="负责人">
              <a-select
                v-model:value="queryForm.assigneeId"
                aria-label="负责人筛选"
                show-search
                option-filter-prop="label"
                style="width: 140px"
                :options="[
                  { value: '', label: '全部负责人' },
                  ...members.map(member => ({ value: String(member.id), label: member.name }))
                ]"
              />
            </a-form-item>
            <a-form-item v-if="!roleList" label="人员安排">
              <a-select
                v-model:value="queryForm.assignmentMode"
                aria-label="人员安排筛选"
                style="width: 130px"
                :options="[
                  { value: '', label: '全部' },
                  ...Object.entries(taskAssignmentModes).map(([value, label]) => ({ value, label }))
                ]"
              />
            </a-form-item>
            <a-button type="primary" @click="queryTasks">
              <SearchOutlined />
              查询
            </a-button>
            <a-button @click="reset">
              <ReloadOutlined />
              重置
            </a-button>
          </a-form>
        </template>
        <template #advancedSearch>
          <a-form layout="inline">
            <a-form-item v-if="roleList" label="业务关联">
              <a-select
                v-model:value="queryForm.category"
                style="width: 160px"
                :options="[
                  { value: '', label: '全部' },
                  { value: 'PROJECT', label: '有关联业务记录' },
                  { value: 'DAILY', label: '未关联业务记录' }
                ]"
              />
            </a-form-item>
            <a-form-item v-if="roleList" label="人员安排">
              <a-select
                v-model:value="queryForm.assignmentMode"
                aria-label="人员安排筛选"
                style="width: 130px"
                :options="[
                  { value: '', label: '全部' },
                  ...Object.entries(taskAssignmentModes).map(([value, label]) => ({ value, label }))
                ]"
              />
            </a-form-item>
            <a-form-item v-if="selectedTab !== 'CLAIMABLE'" label="关联业务记录">
              <TaskRecordPicker v-model="project" placeholder="筛选关联业务记录" />
            </a-form-item>
            <a-form-item label="优先级">
              <a-select
                v-model:value="queryForm.priority"
                style="width: 120px"
                :options="[
                  { value: '', label: '全部' },
                  ...Object.entries(taskPriorities).map(([value, label]) => ({ value, label }))
                ]"
              />
            </a-form-item>
            <a-form-item v-if="selectedTab !== 'CLAIMABLE'" label="业务数据来源" :help="entriesError || undefined">
              <a-select
                v-model:value="queryForm.entryId"
                aria-label="业务数据来源筛选"
                style="width: 180px"
                :loading="entriesLoading"
                :options="[{ value: '', label: '全部入口' }, ...entries]"
                @dropdown-visible-change="(open: boolean) => open && loadEntryOptions()"
              />
            </a-form-item>
            <a-form-item :label="selectedTab === 'RECENT' ? '处理日期' : '预计结束'">
              <a-date-picker
                v-model:value="queryForm.from"
                value-format="YYYY-MM-DD"
                placeholder="开始日期"
                aria-label="高级日期开始"
              />
              <span style="margin: 0 8px">至</span>
              <a-date-picker
                v-model:value="queryForm.to"
                value-format="YYYY-MM-DD"
                placeholder="结束日期"
                aria-label="高级日期结束"
              />
            </a-form-item>
            <a-form-item v-if="selectedTab === 'RECENT'" label="近期期间">
              <a-select
                v-model:value="queryForm.recentPeriod"
                style="width: 110px"
                :options="[
                  { value: 'DAY', label: '本日' },
                  { value: 'WEEK', label: '本周' },
                  { value: 'MONTH', label: '本月' }
                ]"
              />
            </a-form-item>
            <a-form-item v-if="selectedTab === 'RECENT'" label="查看哪一天所在期间">
              <a-date-picker v-model:value="queryForm.date" value-format="YYYY-MM-DD" :allow-clear="false" />
            </a-form-item>
          </a-form>
        </template>
        <template #title>
          {{
            management
              ? employeeTasks && selectedEmployee
                ? `${selectedEmployee.userName} · ${employeeMetricLabels[selectedEmployee.metric]}`
                : managementFocusOptions.find(option => option.value === managementFocus)?.label
              : personal && personalTab === 'PLAN'
                ? planWorkspaceViews.find(view => view.value === planWorkspaceView)?.label
                : tabs.find(tab => tab.key === activeTab)?.label
          }}
          <span
            v-if="management || personalTree"
            class="task-list__group-count"
            :aria-label="management ? '任务组数量' : '本页工作数量'"
          >
            {{ pagination.total }} 组
          </span>
        </template>
        <template #actions>
          <slot name="page-actions" />
          <a-button :loading="loading" @click="refresh">刷新</a-button>
          <a-button v-if="canLaunch" @click="draftOpen = true">我的草稿</a-button>
          <a-button v-if="canLaunch" type="primary" @click="openLaunch()">新建任务</a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="record.inline && column.key === 'title'">
            <TaskHierarchyCell :item="rowHierarchy(record)" :show-outline="false" :show-child-count="false" personal>
              <div class="task-list__inline">
                <a-input
                  ref="inlineInput"
                  v-model:value="inlineTitle"
                  :disabled="inlineBusy"
                  :maxlength="160"
                  placeholder="子任务名称，Enter 连续添加，Esc 取消"
                  aria-label="子任务名称"
                  @press-enter="saveInline()"
                  @keydown.esc="cancelInline"
                />
                <TaskAssignmentFields
                  v-if="!personal"
                  v-model="inlineNode"
                  :members="members"
                  :readonly="inlineBusy"
                  allow-follow
                  :root-assignee-id="inlineRootAssigneeId"
                  compact
                  table-editing
                  inline-candidates
                />
                <p v-if="!personal && !inlineRootKnown" class="task-list__hint">
                  总负责人信息不可用，请为新分工选择人员安排；不会读取或推断不可见的总任务资料。
                </p>
                <div v-if="personal" class="task-list__inline-help">
                  <span class="task-list__hint">由我负责，仍属于原总任务；需要协作时可再分工给同事。</span>
                  <a-button type="link" :disabled="inlineBusy" @click="inlineScheduleOpen = !inlineScheduleOpen">
                    {{ inlineScheduleOpen ? '收起时间设置' : '设置时间（可选）' }}
                  </a-button>
                </div>
                <TaskScheduleFields
                  v-if="!personal || inlineScheduleOpen"
                  v-model="inlineNode.schedule"
                  :readonly="inlineBusy"
                  :planned-start="inlineParent?.plannedStart"
                  :has-predecessors="false"
                />
                <a-checkbox
                  v-if="!planReadonly && inlinePeriod && inlineSelfAssigned"
                  v-model:checked="inlineIncludePlan"
                  :disabled="inlineBusy"
                  aria-label="子任务纳入当前计划"
                >
                  加入{{ inlinePeriod === 'DAY' ? '今日' : '本周' }}计划（仅此子任务）
                </a-checkbox>
                <a-space>
                  <a-button
                    type="primary"
                    :loading="inlineBusy"
                    :disabled="!inlineTitle.trim() || !!pendingInlinePlan"
                    @click="saveInline()"
                  >
                    添加
                  </a-button>
                  <a-button :disabled="inlineBusy" @click="cancelInline">取消</a-button>
                </a-space>
              </div>
            </TaskHierarchyCell>
          </template>
          <template v-else-if="!record.inline && column.key === 'title'">
            <TaskHierarchyCell
              :item="rowHierarchy(record)"
              :show-outline="false"
              :show-child-count="false"
              personal
              :class="{
                'task-list__compact-name': roleList,
                'task-list__compact-child': roleList && !!record.parentId,
                'task-list__completed-context': personal && record.status === 'COMPLETED',
                'task-list__personal-match': personal && !record.contextOnly,
                'task-list__personal-context': personal && record.contextOnly,
                'task-list__employee-owned': employeeOwnsTask(record),
                'task-list__employee-context': employeeTasks && !employeeOwnsTask(record)
              }"
              :expandable="
                (personalTree || readable(record)) &&
                !!(
                  displayedChildCount(record) ||
                  (personalTree
                    ? children[record.id]?.length
                    : children[record.id]?.some(n => n.parentId === record.id)) ||
                  inlineParent?.id === record.id
                )
              "
              :loading="busyIds.includes(record.id)"
              :expanded="expanded.includes(record.id)"
              :toggle-label="`${expanded.includes(record.id) ? '收起' : '展开'}子任务：${record.title}`"
              @toggle="toggle(record)"
            >
              <div class="task-list__name-line">
                <a-button
                  v-if="readable(record) || record.anchorTaskId"
                  type="link"
                  :title="readable(record) ? record.title : `查看任务编排：${record.title}`"
                  @click="inspectRow(record)"
                >
                  {{ record.title }}
                </a-button>
                <span v-else :title="record.title">{{ record.title }}</span>
                <a-tooltip v-if="contextHint(record)" :title="contextHint(record)">
                  <span class="task-list__context-hint" tabindex="0" :aria-label="contextHint(record)">
                    <InfoCircleOutlined />
                  </span>
                </a-tooltip>
              </div>
              <template #extra>
                <div v-if="record.expandedChild && !roleList" class="task-list__hint">由上级展开显示</div>
              </template>
            </TaskHierarchyCell>
          </template>
          <template v-else-if="!record.inline && column.key === 'owner'">
            <!-- 管理状态页只保留识别任务所需的两行；计划记录继续由原只读入口查看。 -->
            <div v-if="roleList" class="task-list__owner-summary">
              <div class="task-list__owner-line" :title="ownerHint(record)">
                {{ record.assigneeName || taskAssignmentLabel(record) }}
              </div>
              <div
                v-if="record.project || record.applicationId"
                class="task-list__hint task-list__owner-line"
                :title="businessHint(record)"
              >
                {{ businessLabel(record) }}
              </div>
            </div>
            <template v-else>
              {{ businessLabel(record) }}
              <div
                v-if="record.project && record.applicationId && applicationNames[record.applicationId]"
                class="task-list__hint"
              >
                {{ applicationNames[record.applicationId] }}
              </div>
              <div class="task-list__hint">{{ taskAssignmentLabel(record, record.assigneeName) }}</div>
              <div v-if="record.acceptorId" class="task-list__hint">验收人：{{ record.acceptorName || '已指定' }}</div>
              <div v-for="plan in record.plans" :key="plan.id || `${plan.period}:${plan.date}`" class="task-list__hint">
                <template v-if="planReadonly">{{ plan.userName || record.assigneeName || '负责人' }} ·</template>
                {{ taskPlanSummaryLabel(plan) }}
                ·
                {{ taskPlanSourceLabel(plan) }}
              </div>
            </template>
          </template>
          <template v-else-if="!record.inline && column.key === 'progress'">
            <a-tooltip :title="progressHint(record) || undefined">
              <div v-if="progressCount(record)" class="task-list__progress" :title="progressHint(record)" tabindex="0">
                <span class="task-list__progress-count">
                  {{ progressCount(record)!.completed }}/{{ progressCount(record)!.total }}
                </span>
                <span
                  class="task-list__progress-track"
                  role="progressbar"
                  aria-label="下级完成进度"
                  :aria-valuemin="0"
                  :aria-valuemax="progressCount(record)!.total"
                  :aria-valuenow="progressCount(record)!.completed"
                >
                  <span
                    :style="{
                      width: `${Math.min(100, (progressCount(record)!.completed / progressCount(record)!.total) * 100)}%`
                    }"
                  />
                </span>
              </div>
              <span v-else class="task-list__hint" :title="progressHint(record) || undefined">—</span>
            </a-tooltip>
          </template>
          <template v-else-if="!record.inline && column.key === 'plan'">
            <a-button
              v-if="canPlan(record)"
              type="link"
              size="small"
              :aria-label="`安排计划：${record.title}`"
              @click="arrange([record.id])"
            >
              {{ planMembershipLabel(record) || '安排计划' }}
            </a-button>
            <span
              v-else
              class="task-list__plan-label"
              :class="{ 'task-list__hint': !planMembershipLabel(record) || planMembershipLabel(record) === '未纳入' }"
            >
              {{ planMembershipLabel(record) || '—' }}
            </span>
            <div v-if="planInheritanceHint(record)" class="task-list__hint" :title="planInheritanceHint(record)">
              随上级
            </div>
            <div
              v-if="planWorkspaceView === 'UNPLANNED' && record.contextOnly"
              class="task-list__hint"
              :title="contextHint(record)"
            >
              关联展示
            </div>
          </template>
          <template v-else-if="!record.inline && column.key === 'status'">
            <a-tooltip :title="statusHint(record) || undefined">
              <span :title="statusHint(record) || undefined" tabindex="0">
                <a-tag :color="taskStateColors[displayedStatus(record)]">{{ displayedStatusLabel(record) }}</a-tag>
              </span>
            </a-tooltip>
            <div
              v-if="
                !roleList && groupRow(record) && displayedStatus(record) === 'RUNNING' && record.status === 'PENDING'
              "
              class="task-list__hint"
            >
              总任务未开始，下级已开始
            </div>
            <div
              v-else-if="!roleList && taskExecutionHint(record)"
              class="task-list__hint"
              :class="{ 'task-list__status-summary': roleList }"
              :title="record.completionReason || record.blockedReason || ''"
            >
              {{ taskExecutionHint(record) }}
            </div>
          </template>
          <template v-else-if="!record.inline && column.key === 'time'">
            <template v-if="selectedTab === 'RECENT'">
              {{ taskTime(record.lastHandledAt || record.actualEnd || record.actualStart) }}
              <div class="task-list__hint">预计结束 {{ taskDate(record.expectedEnd) }}</div>
            </template>
            <template v-else-if="roleList">
              <span :title="record.expectedStart ? `预计开始 ${taskDate(record.expectedStart)}` : undefined">
                {{ record.expectedEnd ? taskDate(record.expectedEnd) : '未安排' }}
              </span>
              <div
                v-if="taskDueHint(record)"
                class="task-list__hint"
                :class="`task-list__due--${taskDueHint(record)?.tone}`"
              >
                {{ taskDueHint(record)?.text }}
              </div>
            </template>
            <template v-else>
              预计开始 {{ taskDate(record.expectedStart) }}
              <div class="task-list__hint">预计结束 {{ taskDate(record.expectedEnd) }}</div>
            </template>
            <TaskScheduleNotice :summary="record.scheduleSummary" />
          </template>
          <template
            v-else-if="
              !record.inline &&
              ['expectedStart', 'expectedEnd', 'actualStart', 'actualEnd'].includes(String(column.key))
            "
          >
            {{
              column.key === 'expectedStart' || column.key === 'expectedEnd'
                ? taskDate(record[column.key as 'expectedStart' | 'expectedEnd'])
                : column.key === 'actualEnd'
                  ? record.status === 'COMPLETED' && record.actualEnd
                    ? taskTime(record.actualEnd)
                    : ''
                  : taskTime(record.actualStart)
            }}
            <TaskScheduleNotice v-if="column.key === 'expectedEnd'" :summary="record.scheduleSummary" />
          </template>
          <template v-else-if="!record.inline && column.key === 'priority'">
            {{ taskPriorities[record.priority as keyof typeof taskPriorities] || '—' }}
          </template>
          <template v-else-if="!record.inline && String(column.key).startsWith('business:')">
            {{ textValue(businessValues[record.id]?.[String(column.key).slice(9)]) }}
          </template>
          <template v-else-if="!record.inline && column.key === 'actions'">
            <div class="nocode-table-actions task-list__actions">
              <a-button
                v-if="canLocateTaskClaim(record) && !rowActions(record).primary && selectedTab !== 'RECENT'"
                type="link"
                @click="claimLocation = record"
              >
                去领取
              </a-button>
              <a-button
                v-if="rowActions(record).primary"
                class="task-list__primary-action"
                type="link"
                :loading="rowActions(record).primary?.loading"
                :disabled="rowActions(record).primary?.disabled"
                :title="rowActions(record).primary?.reason"
                @click="rowActions(record).primary?.run()"
              >
                {{ rowActions(record).primary?.label }}
              </a-button>
              <a-button v-if="rowActions(record).split" type="link" @click="rowActions(record).split?.run()">
                拆分子任务
              </a-button>
              <a-button
                v-if="readable(record) && !(personal && rowActions(record).split)"
                type="link"
                @click="openDetail(record.id)"
              >
                详情
              </a-button>
              <a-button v-else-if="!readable(record) && record.anchorTaskId" type="link" @click="inspectRow(record)">
                查看编排
              </a-button>
              <a-dropdown v-if="rowActions(record).more.length" :trigger="['click']" placement="bottomRight">
                <a-button
                  type="text"
                  class="task-list__more"
                  :aria-label="`更多操作：${record.title}`"
                  title="更多操作"
                  aria-haspopup="menu"
                >
                  <MoreOutlined />
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item
                      v-for="action in rowActions(record).more"
                      :key="action.key"
                      :danger="action.danger"
                      :disabled="action.loading || action.disabled"
                      :title="action.reason"
                      @click="!action.disabled && !action.loading && action.run()"
                    >
                      {{ action.disabled && action.reason ? `${action.label} · ${action.reason}` : action.label }}
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
            <a-button
              v-if="!management && rowActions(record).primary?.disabled"
              type="link"
              size="small"
              class="task-list__blocked-hint task-list__hint"
              :title="`${rowActions(record).primary?.reason || ''}；点击查看任务编排`"
              @click="openDetail(record.id, 'arrangement')"
            >
              {{ taskExecutionHint(record) || rowActions(record).primary?.reason }}
            </a-button>
          </template>
        </template>
        <template #empty>
          <a-empty :description="emptyDescription" />
          <template v-if="!loading">
            <a-button v-if="listFailed" @click="refresh">重新加载</a-button>
            <a-button v-else-if="hasAppliedFilters" type="link" @click="reset">清除筛选</a-button>
            <a-button
              v-else-if="personal && personalTab === 'PLAN' && planWorkspaceView !== 'ALL'"
              type="link"
              @click="changePlanWorkspace('ALL')"
            >
              查看全部任务
            </a-button>
            <a-button v-else-if="personal && personalTab === 'PLAN'" type="link" @click="changeTab('CLAIMABLE')">
              去领取任务
            </a-button>
          </template>
        </template>
      </OsTablePage>
    </div>
    <TaskSplitDialog
      v-if="personal && inlineParent"
      ref="splitDialog"
      v-model:title="inlineTitle"
      v-model:plan="splitPlan"
      v-model:schedule="inlineNode.schedule"
      :path="splitPath"
      :planned-start="inlineParent.plannedStart"
      :plan-summary="taskSplitPlanSummary(inlineParent, user.userInfo?.id)"
      :busy="inlineBusy"
      :error="splitError"
      :retry-busy="retryPlanBusy"
      :plan-error="
        pendingInlinePlan
          ? `子任务「${pendingInlinePlan.title}」已创建，但未能纳入计划：${pendingInlinePlan.error}`
          : undefined
      "
      @save="saveInline"
      @close="cancelInline"
      @retry="retryInlinePlan"
      @later="deferInlinePlan"
    />
    <TaskLaunchDrawer
      v-if="launchOpen"
      :key="launchKey"
      :initial-template-id="launchTemplateId"
      :draft-id="launchDraftId"
      @close="launchOpen = false"
      @created="launched"
    />
    <TaskDraftList v-if="draftOpen" @close="draftOpen = false" @edit="editDraft" />
    <TaskAssignmentDialog
      v-if="assignmentTask"
      :key="assignmentTask.id"
      :task="assignmentTask"
      :current-user-id="user.userInfo?.id"
      @close="assignmentTask = undefined"
      @saved="refresh"
    />
    <TaskClaimDialog
      v-if="claimTask"
      :key="claimTask.id"
      :task="claimTask"
      :plan-readonly="planReadonly"
      @close="claimTask = undefined"
      @saved="refresh"
    />
    <TaskClaimEntry
      v-if="claimLocation"
      :target="claimLocation"
      :plan-readonly="planReadonly"
      @close="claimLocation = undefined"
      @saved="refresh"
    />
    <TaskDetail
      v-if="detailId"
      :id="detailId"
      :plan-readonly="planReadonly"
      :comment-id="detailCommentId"
      :employee-view="personal"
      :initial-tab="detailInitialTab"
      :initial-entry-key="detailInitialEntryKey"
      :initial-selected-id="detailInitialSelectedId"
      @close="detailId = ''"
      @select="openDetail"
      @changed="refresh"
    />
    <TaskDeleteSubtaskDialog
      v-if="deleteTask"
      :key="deleteTask.id"
      :task="deleteTask"
      @close="deleteTask = undefined"
      @deleted="afterSubtaskDeleted"
    />
    <TaskQuickAction
      v-if="quickAction"
      :task="quickAction.task"
      :action="quickAction.action"
      @close="quickAction = undefined"
      @saved="refresh"
      @inspect="(tab, entryKey) => quickAction && openDetail(quickAction.task.id, tab, entryKey)"
    />
    <TaskPlanDialog
      v-if="planIds.length"
      :ids="planIds"
      :task-names="rows.filter(row => planIds.includes(row.id)).map(row => row.title)"
      :initial-period="planPeriod"
      :initial-action="planAction"
      :target="planTarget"
      @close="planIds = []"
      @saved="afterPlanSaved"
    />
  </section>
</template>
<style scoped>
.task-list--embedded {
  container: task-embedded / inline-size;
}
.task-list--embedded,
.task-list--embedded .task-list__content {
  height: auto;
}
.task-list--embedded :deep(.os-table-page__search-form) {
  overflow-x: visible;
}
.task-list--embedded :deep(.os-table-page__search-form .ant-form-inline),
.task-list--embedded :deep(.os-table-page__search-advanced .ant-form-inline) {
  flex-wrap: wrap;
  gap: 8px 12px;
  white-space: normal;
}
/* 按实际区块宽度适配抽屉与多列页面，桌面上的窄容器也不能挤出查询/重置按钮。 */
@container task-embedded (max-width: 640px) {
  .task-list__content :deep(.os-table-page__search-basic) {
    flex-wrap: wrap;
  }
  .task-list__content :deep(.os-table-page__search-form) {
    flex-basis: 100%;
  }
  .task-list__content :deep(.os-table-page__search-form .ant-form-item) {
    flex: 1 1 180px;
    min-width: 0;
    margin-right: 0;
  }
  .task-list__content :deep(.os-table-page__search-form .ant-select) {
    width: 100% !important;
  }
}
.task-list__content {
  min-height: 0;
  min-width: 0;
}
.task-list__workspace {
  padding-bottom: var(--spacing-sm);
}
.task-list__workspace .task-list__period {
  justify-content: space-between;
  margin-bottom: var(--spacing-sm);
}
.task-list__employee-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm) var(--spacing-xl);
  padding: 0 0 var(--spacing-md);
}
.task-list__employee-identity,
.task-list__employee-filter,
.task-list__employee-legend {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
}
.task-list__employee-identity {
  min-width: 0;
}
.task-list__employee-identity > strong {
  overflow: hidden;
  max-width: 180px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-list__employee-filter {
  flex-wrap: wrap;
}
.task-list__employee-filter > .ant-select {
  width: 152px;
}
.task-list__employee-legend {
  margin-left: auto;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-list__employee-legend > i {
  width: var(--spacing-sm);
  height: var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--brand);
}
.task-list__employee-owned :deep(.task-hierarchy__node) {
  border-radius: var(--radius-sm);
  background: var(--brand-light);
}
.task-list__employee-owned :deep(.task-hierarchy__title .ant-btn-link) {
  font-weight: 600;
}
.task-list__employee-context.task-hierarchy--root :deep(.task-hierarchy__node) {
  background: var(--neutral-bg);
}
.task-list__group-count {
  margin-left: var(--spacing-md);
  font-size: var(--table-font-sm);
  font-weight: normal;
  color: var(--text-secondary);
}
.task-list__name-line {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  min-width: 0;
}
.task-list__compact-name .task-list__name-line > :first-child {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-list__compact-name :deep(.task-hierarchy__node) {
  padding-block: var(--spacing-xs);
  min-height: var(--control-height);
}
.task-list__compact-name :deep(.task-hierarchy__footer) {
  margin-top: 0;
  line-height: 1.25;
}
/* 子任务名称与右侧标签垂直居中，保持紧凑行高及原有层级缩进。 */
.task-list__content :deep(.ant-table-tbody > tr > td.task-list__child-cell) {
  height: 56px;
  padding-block: var(--spacing-sm);
}
.task-list__compact-child :deep(.task-hierarchy__node) {
  padding-block: 0;
}
.task-list__compact-child :deep(.task-hierarchy__footer) {
  margin-top: 0;
  line-height: 1.25;
}
.task-list__context-hint {
  flex: none;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-list__progress {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  max-width: 100%;
}
.task-list__progress-count {
  flex: none;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.task-list__progress-track {
  display: block;
  width: 40px;
  height: var(--spacing-xs);
  overflow: hidden;
  background: var(--border);
  border-radius: var(--radius-sm);
}
.task-list__progress-track > span {
  display: block;
  height: 100%;
  background: var(--brand);
}
.task-list__plan-label {
  white-space: nowrap;
  font-size: var(--table-font-sm);
  color: var(--brand);
}
.task-list__plan-label.task-list__hint {
  color: var(--text-secondary);
}
.task-list__completed-context :deep(.task-hierarchy__title .ant-btn-link) {
  color: var(--text-secondary);
}
.task-list__completed-context :deep(.task-hierarchy__node) {
  background: var(--neutral-bg);
}
.task-list__personal-match :deep(.task-hierarchy__node) {
  background: var(--brand-light);
}
.task-list__personal-context :deep(.task-hierarchy__node) {
  background: var(--neutral-bg);
}
.task-list__personal-context :deep(.task-hierarchy__title .ant-btn-link) {
  color: var(--text-secondary);
  font-weight: normal;
}
:deep(.task-list__completed-cell) {
  color: var(--text-secondary);
}
.task-list__team-group {
  margin-block: var(--spacing-sm);
  padding: var(--spacing-sm);
  color: var(--text-primary);
  background: var(--neutral-bg);
  font-weight: 600;
}
.task-list__status-summary {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  white-space: normal;
}
.task-list__blocked-hint {
  max-width: 225px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-list__due--danger {
  color: var(--error);
}
.task-list__due--warning {
  color: var(--warning);
}
.task-list__actions > .task-list__primary-action {
  flex-basis: auto;
}
.task-list__actions > .task-list__more {
  width: var(--control-height-sm);
  height: var(--control-height-sm);
  padding: 0;
  color: var(--text-secondary);
}
.task-list__actions > .task-list__more:hover,
.task-list__actions > .task-list__more:focus-visible {
  color: var(--brand);
  background: var(--brand-light);
}
.task-list__inline-help {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
</style>
