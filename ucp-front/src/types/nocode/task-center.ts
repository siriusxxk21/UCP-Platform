import type { Aggregate, RecordModel, SaveRecord } from './runtime'
import type { FormConfig } from './application-ui'
import type { ObjectReference } from './application'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import type { TaskWorkEntryConfig, TaskWorkMaterial } from './task-work-entries'

/** 仅兼容历史接口与记录；当前任务统一为 DAG，不据此区分产品类型或限制节点能力。 */
export type TaskKind = 'ORDINARY' | 'PROCESS'
export type TaskRole = 'ROOT' | 'NODE' | 'SUBTASK'
export type TaskState = 'PENDING' | 'RUNNING' | 'PAUSED' | 'PENDING_ACCEPTANCE' | 'COMPLETED' | 'CANCELLED'
/** 按稳定模板来源查找实例；默认包含各发布版本，分页数为总任务数。 */
export interface TaskTemplateInstanceQuery {
  templateId: string
  version?: number
  search?: string
  status?: TaskState
  pageNo: number
  pageSize: number
}
/** 根详情不可见时只返回原上级摘要，不能用模板维护权限打开根任务。 */
export interface TaskTemplateInstance {
  rootId: string
  title: string
  status: TaskState
  templateVersion: number | null
  root: TaskRow | null
  nodes: TaskRow[]
}
export type TaskTimeMode = 'AUTO' | 'UNSCHEDULED' | 'FIXED' | 'PLAN_START' | 'T0' | 'PREDECESSOR'
export type TaskAssignmentMode = 'UNASSIGNED' | 'ASSIGNED' | 'OPEN' | 'FOLLOW_ROOT'
export type TaskClaimOwnership = 'UNCLAIMED' | 'MINE' | 'ASSIGNED' | 'UNAVAILABLE' | 'RESTRICTED'

/** 原命令完整保存供未知结果恢复，重试不得替换修订、备注或请求键。 */
export interface TaskTransitionCommand {
  id: string
  expectedRevision: number
  action: TaskAction
  note: string
  requestKey: string
  confirmCancelledChildren?: boolean
}
export interface TaskTransitionRecovery {
  applied: TaskDetail | null
  superseded: boolean
}
/** 领取目录只投影安全摘要，不具有完整任务详情或业务数据的读取权限。 */
export interface TaskClaimableGroup {
  scheduleSummary?: TaskScheduleSummary
  rootId: string
  title: string
  status?: TaskState
  expectedStart?: string | null
  expectedEnd?: string | null
  priority?: TaskPriority
  childCount?: number
  completedChildCount?: number
  anchorTaskId?: string
  rootVisible: boolean
  canClaimGroup: boolean
  claimableCount: number
  followRootCount: number
  /** 当前用户整项领取时实际包含的节点数（含根）；0 表示总任务不可整领。旧服务可省略。 */
  wholeClaimCount?: number
  /** 领取目录中的安全归属摘要；旧响应省略时不可自行猜测负责人。 */
  ownership?: TaskClaimOwnership
  ownerName?: string | null
  claimableChildCount?: number
  /** 仅当前总负责人可一次补领的子任务数量，不含已归本人的总任务。 */
  remainingClaimCount?: number
}
export interface TaskClaimableItem {
  scheduleSummary?: TaskScheduleSummary
  id: string
  rootId: string
  parentId: string | null
  title: string
  status: TaskState
  assigneeId: TaskUserId | null
  assignmentMode: TaskAssignmentMode
  revision: number
  urgency: TaskUrgency
  priority: TaskPriority
  expectedStart: string | null
  expectedEnd: string | null
  canClaim: boolean
  assigneeName?: string | null
  detailVisible?: boolean
  childCount?: number
  completedChildCount?: number
  anchorTaskId?: string
}
export interface TaskClaimPreview {
  rootId: string
  title: string
  instanceRevision: number
  items: TaskClaimableItem[]
  /** 补领时 items 仅包含剩余可领子任务，不会再次领取总任务。 */
  remainingOnly?: boolean
}
export interface TaskClaimGroupCommand {
  rootId: string
  expectedInstanceRevision: number
  requestKey: string
  /** 显式整项领取开放子任务；省略时兼容历史仅随总负责人领取的行为。 */
  includeOpen?: boolean
}
export type TaskPeriod = 'DAY' | 'WEEK' | 'MONTH'
export type TaskAction = 'START' | 'PAUSE' | 'RESUME' | 'COMPLETE' | 'APPROVE' | 'REJECT' | 'CANCEL'
/** 只读操作预检；提交时仍由后端检查最新状态、权限和有效材料。 */
export interface TaskReadiness {
  taskId: string
  revision: number
  canComplete: boolean
  checks: Array<{
    code: 'STATE' | 'ASSIGNEE' | 'CHILDREN' | 'CHILDREN_CANCELLED' | 'BUSINESS' | 'FEEDBACK'
    label: string
    passed: boolean
    reason: string | null
    entryKey: string | null
  }>
  canCancel: boolean
  cancelBlockedReason: string | null
  cancellationImpacts: Array<{ taskId: string; title: string; direct: boolean; reason: string }>
}
export type TaskUrgency = 'NORMAL' | 'URGENT'
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH'
/** 成员雪花主键按服务端原值传递，禁止转为 Number 丢失精度。 */
export type TaskUserId = string | number
export interface TaskRecordRef {
  applicationId: string
  objectId: string
  recordId: string
  label: string
}
export interface TaskBinding {
  applicationId: string
  /** 新办理项固定普通视图及其表单；历史无视图引用继续沿用原授权。 */
  viewId?: string | null
  formId: string | null
  entryId: string | null
}
export interface TaskSchedule {
  mode: TaskTimeMode
  fixedStart: string | null
  fixedEnd?: string | null
  offsetDays: number
  durationDays: number
}
export interface TaskSharing {
  mode: 'INDEPENDENT' | 'SHARED'
  sourceNodeId: string | null
  writableFieldIds: string[]
}
/** 总任务统一授权；缺省沿用历史逐节点共享规则，不自动升级存量任务。 */
export interface TaskDataPolicy {
  version: 1
  business: 'GROUP' | 'ALL'
  feedback: 'GROUP' | 'ALL'
}
export interface TaskNodeInput {
  /** 模板总任务的参考有效工作量（整数分钟）；空表示未设置，实例沿用发起版本快照。 */
  effectiveWorkMinutes?: number | null
  /** 总任务预算汇总方式；存量未设置时保持手工总额。 */
  workTotalMode?: 'AUTO' | 'MANUAL' | null
  dataPolicy?: TaskDataPolicy | null
  entries?: TaskWorkEntryConfig[] | null
  id: string
  parentId: string | null
  title: string
  description: string
  assigneeId: TaskUserId | null
  /** 仅总任务可选；未配置时由负责人直接完成。 */
  acceptorId?: TaskUserId | null
  assignmentMode?: TaskAssignmentMode
  candidateUserIds?: TaskUserId[]
  urgency: TaskUrgency
  priority: TaskPriority
  schedule: TaskSchedule
  predecessorIds: string[]
  binding: TaskBinding | null
  sharing: TaskSharing
}
export interface TaskBusinessRef {
  resource: {
    applicationId: string
    applicationVersion: number
    applicationChecksum: string
    resourceId: string
    resourceKind: string
  }
  object: ObjectReference
  recordId: string | null
  requestId: string | null
}
export interface TaskPlan {
  /** 缺省仅兼容旧响应；不得把旧区间安排推断为清单成员。 */
  mode?: 'SCHEDULE' | 'CHECKLIST'
  id?: string | null
  period: TaskPeriod
  date: string
  endDate?: string
  active?: boolean
  canCancel?: boolean
  historyReason?: string | null
  userId?: TaskUserId
  userName?: string
  arrangedById?: TaskUserId
  arrangedByName?: string
  source?: 'SELF' | 'MANAGER'
  arrangedAt?: string
  /** 继承清单仅用于展示，不能用来源计划身份移除子任务。 */
  inherited?: boolean
  inheritedFromTaskId?: string | null
  inheritedFromTitle?: string | null
}
/** 同一工作安排的真实上下文；version 用于防止加载后发生改派或并发改期。 */
export interface TaskPlanContextItem {
  status?: TaskState
  taskId: string
  title: string
  assigneeId: TaskUserId | null
  version: number
  plans: TaskPlan[]
  constraints: TaskPlan[]
  history: TaskPlan[]
  canArrange: boolean
  canCancel: boolean
  readOnly: boolean
  reason?: string | null
  warnings: string[]
}
export interface TaskPlanContext {
  items: TaskPlanContextItem[]
}
export interface TaskScheduleCommand {
  ids: string[]
  target: 'SELF' | 'ASSIGNEE'
  action: 'ARRANGE' | 'CANCEL'
  period?: TaskPeriod
  date?: string
  endDate?: string
  planIds?: string[]
  expectedVersions: Record<string, number>
}
export interface TaskScheduleResult {
  changed: string[]
  unchanged: string[]
}
export type TaskChecklistPeriod = 'DAY' | 'WEEK'
/** 下周仍以 WEEK + 服务端日期写入，不新增持久化周期。 */
export type TaskChecklistChoice = TaskChecklistPeriod | 'NEXT_WEEK'
export interface TaskChecklistItem {
  taskId: string
  title: string
  status: TaskState
  assigneeId: TaskUserId | null
  version: number
  todayPlans: TaskPlan[]
  weekPlans: TaskPlan[]
  nextWeekPlans?: TaskPlan[]
  history: TaskPlan[]
  canAdd: boolean
  reason?: string | null
  warnings: string[]
}
export interface TaskChecklistContext {
  today: string
  weekStart: string
  weekEnd: string
  nextWeekStart?: string
  nextWeekEnd?: string
  items: TaskChecklistItem[]
}
export interface TaskChecklistCommand {
  ids: string[]
  target: 'SELF' | 'ASSIGNEE'
  action: 'ADD' | 'REMOVE'
  period: TaskChecklistPeriod
  date: string
  planIds?: string[]
  expectedVersions: Record<string, number>
  requestKey: string
}
/** 仅用于说明任务位置的上级摘要，不授予上级任务或业务数据的操作权限。 */
export interface TaskAncestorContext {
  id: string
  parentId: string | null
  title: string
  assigneeName: string | null
  status: TaskState
  detailVisible: boolean
}
export interface TaskRow extends Omit<TaskNodeInput, 'assigneeId'> {
  scheduleSummary?: TaskScheduleSummary
  /** 总任务至直属上级的有序摘要；历史响应可能尚未提供。 */
  ancestorContext?: TaskAncestorContext[]
  /** 历史配置沿旧人员/时间缺省语义保存，调整时不能无意升级协议。 */
  legacyProtocol?: boolean
  applicationId?: string | null
  kind?: TaskKind
  role?: TaskRole
  rootId: string
  status: TaskState
  /** 仅授权管理的根任务返回整体进度；不替代当前节点的真实执行状态。 */
  groupStatus?: TaskState | null
  creatorId: TaskUserId
  creatorName: string
  assigneeId: TaskUserId | null
  assigneeName: string | null
  plannedStart?: string | null
  acceptorName?: string | null
  canAccept?: boolean
  project: TaskRecordRef | null
  business: TaskBusinessRef | null
  baselineStart: string | null
  baselineEnd: string | null
  expectedStart: string | null
  expectedEnd: string | null
  actualStart: string | null
  actualEnd: string | null
  createdAt: string
  revision: number
  instanceRevision: number
  childCount: number
  /** 个人树命中任务组后的完整直属下级数，不代表个人工作量。 */
  matchingChildCount?: number
  /** 个人分组的上下文不属于本人的待办，详情资格与分组展开独立。 */
  contextOnly?: boolean
  detailVisible?: boolean
  /** 同组已授权详情入口；摘要节点只借此定位编排，不直接请求其私有详情。 */
  anchorTaskId?: string
  myPendingCount?: number
  myCompletedCount?: number
  completedChildCount?: number
  /** 服务端说明父任务尚未自动收尾的实际原因，不由客户端猜测。 */
  completionReason?: string | null
  plans: TaskPlan[]
  canStart: boolean
  canExecute: boolean
  /** 暂停/恢复沿用服务端任务权限，不从页面入口或管理菜单自行推断。 */
  canPause?: boolean
  canResume?: boolean
  /** 自身或最近暂停祖先；只表示执行冻结，不覆盖节点原始状态。 */
  pausedByTaskId?: string | null
  pauseReason?: string | null
  canEdit: boolean
  /** 仅服务端确认协调资格、个人拆分来源与删除边界时开放。 */
  canDelete?: boolean
  deleteBlockedReason?: string | null
  canPlan?: boolean
  canClaim?: boolean
  canDelegate?: boolean
  /** 在办子任务的显式交接能力，不等同于执行或业务资料访问权限。 */
  canTransfer?: boolean
  canAssign?: boolean
  explicitLinkId?: string | null
  canUnlink?: boolean
  lastHandledAt?: string | null
  blockedReason: string | null
  templateId: string | null
  templateVersion: number | null
}
export interface TaskQuery {
  planMode?: 'SCHEDULE' | 'CHECKLIST'
  scheduleScope?: 'PERSONAL' | 'TEAM'
  assigneeId?: TaskUserId
  planFilter?: 'UNPLANNED' | 'CARRYOVER' | 'COARSE' | 'PLANNED'
  kind?: TaskKind
  scope: 'MINE' | 'MANAGE'
  tab: 'TODO' | 'TODAY' | 'WEEK' | 'MONTH' | 'POOL' | 'RECENT' | 'ALL' | 'CLAIMABLE' | 'ACCEPTANCE' | 'DONE'
  personalScope?: 'ACTION' | 'FOLLOW_UP'
  /** 仅管理页 ALL 使用总任务分页，此时 status 筛选整体状态；个人与应用列表沿用节点语义。 */
  rootsOnly?: boolean
  assignmentMode?: TaskAssignmentMode | ''
  date: string
  recentPeriod?: TaskPeriod
  search?: string
  category?: 'PROJECT' | 'DAILY' | ''
  project?: TaskRecordRef | null
  status?: TaskState | ''
  urgency?: TaskUrgency | ''
  priority?: TaskPriority | ''
  entryId?: string
  from?: string
  to?: string
  pageNo: number
  pageSize: number
}
export interface TaskComment {
  id: string
  taskId: string
  parentId: string | null
  authorId: TaskUserId
  authorName: string
  content: string
  mentionedUserIds: TaskUserId[]
  createdAt: string
}
export interface TaskEvent {
  id: string
  taskId: string
  type: string
  actorId: TaskUserId
  actorName: string
  note: string
  createdAt: string
}
/** 可领取任务的只读工作要求，不包含业务记录或内部协作信息。 */
export type TaskDetailPreview = Pick<
  TaskRow,
  | 'description'
  | 'priority'
  | 'schedule'
  | 'expectedStart'
  | 'expectedEnd'
  | 'actualStart'
  | 'actualEnd'
  | 'createdAt'
  | 'acceptorId'
  | 'acceptorName'
  | 'effectiveWorkMinutes'
  | 'templateVersion'
>
export interface TaskDetail {
  preview?: TaskDetailPreview | null
  /** 同组编排的只读摘要；不授予节点详情、业务数据或操作权限。 */
  structure?: TaskStructureNode[]
  task: TaskRow
  nodes: TaskRow[]
  comments: TaskComment[]
  events: TaskEvent[]
  links?: Array<{ id: string; record: TaskRecordRef; creatorId: TaskUserId; creatorName: string; createdAt: string }>
}
export interface TaskStructureNode {
  scheduleSummary?: TaskScheduleSummary
  id: string
  rootId: string
  parentId: string | null
  title: string
  status: TaskState
  assigneeName: string | null
  predecessorIds: string[]
  expectedStart: string | null
  expectedEnd: string | null
  detailVisible: boolean
}
export interface TaskCreate {
  plannedStart?: string | null
  applicationId?: string | null
  kind?: TaskKind
  task: TaskNodeInput
  nodes?: TaskNodeInput[] | null
  parentId?: string | null
  templateId?: string | null
  templateVersion?: number | null
  project?: TaskRecordRef | null
  business?: SaveRecord | null
  existingRecord?: TaskRecordRef | null
  requestKey: string
}
export interface TaskDraftSummary {
  id: string
  revision: number
  title: string
  updatedAt: string
}
export interface TaskDraft extends TaskDraftSummary {
  content: TaskCreate
  publishedTaskId?: string | null
}
export interface TaskFormContext {
  binding: TaskBusinessRef
  model: RecordModel
  form: FormConfig
  record: Aggregate | null
  writableFieldIds: string[]
  handling: import('./handling').HandlingResult | null
}
export interface TaskTemplate {
  task?: TaskNodeInput | null
  kind?: TaskKind
  id: string
  name: string
  description: string
  revision: number
  publishedVersion: number | null
  primaryVersion?: number | null
  nodes: TaskNodeInput[]
  creatorId: TaskUserId
  updatedAt: string
}
export interface SaveTaskTemplate {
  task?: TaskNodeInput | null
  kind?: TaskKind
  id: string | null
  expectedRevision: number | null
  name: string
  description: string
  nodes: TaskNodeInput[]
}
export interface TaskTemplateVersion {
  task?: TaskNodeInput | null
  kind?: TaskKind
  id: string
  version: number
  name: string
  description: string
  nodes: TaskNodeInput[]
  publishedAt: string
}
/** 已发布快照的目录信息；主版本仅决定新建时的默认选择。 */
export interface TaskTemplateVersionSummary {
  version: number
  name: string
  description: string
  publishedAt: string
  nodeCount: number
  primary: boolean
}
export interface TaskAdjust {
  plannedStart?: string | null
  rootId: string
  expectedRevision: number
  nodes: TaskNodeInput[]
  reason: string
}
export interface TaskAdjustmentPreview {
  schedule?: TaskSchedulePreview
  changedIds: string[]
  addedIds: string[]
  removedIds: string[]
  affectedIds: string[]
}
/** 排期只使用服务端计算；前端不以不完整下级日期补造整组日期。 */
export interface TaskScheduleSummary {
  source: 'AUTO' | 'ROLLUP' | 'EXPLICIT' | 'UNSCHEDULED'
  partial: boolean
  warnings: string[]
}
export interface TaskSchedulePreviewNode {
  id: string
  title: string
  expectedStart: string | null
  expectedEnd: string | null
  partial: boolean
  warnings: string[]
}
export interface TaskSchedulePreview {
  nodes: TaskSchedulePreviewNode[]
  warnings: string[]
}
export interface TaskPageContext {
  applicationId: string
  pageId: string
  nodeId: string
  recordId?: string
  conditions?: DynamicSearchCondition | null
}
export interface TaskMember {
  id: TaskUserId
  name: string
}
export interface TaskMaterial {
  eventId: string
  binding: TaskBusinessRef | null
  model: RecordModel | null
  record: Aggregate | null
  entries?: TaskWorkMaterial[]
}
