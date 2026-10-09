import type { TaskMember, TaskNodeInput, TaskRow, TaskSchedule, TaskState } from '@/types/nocode/task-center'
import { taskAssignmentLabel, taskDisplayState, taskStates } from './task-center'
import { taskScheduleDateRange, taskScheduleSummary } from './task-schedule-summary'

export type TaskDagContext = 'template' | 'draft' | 'instance'
/** 运行快照只用于显示；不能覆盖正在编排的负责人、前置关系或时间规则。 */
export type TaskDagRuntimeInfo = Pick<TaskRow, 'id'> &
  Partial<
    Pick<
      TaskRow,
      | 'status'
      | 'pausedByTaskId'
      | 'assigneeId'
      | 'assigneeName'
      | 'expectedStart'
      | 'expectedEnd'
      | 'schedule'
      | 'plannedStart'
      | 'predecessorIds'
      | 'scheduleSummary'
    >
  > & { expectedStale?: boolean }
export type TaskDagDisplayNode = TaskNodeInput &
  Partial<
    Pick<
      TaskRow,
      'status' | 'pausedByTaskId' | 'assigneeName' | 'rootId' | 'expectedStart' | 'expectedEnd' | 'scheduleSummary'
    >
  > & {
    referenceOnly?: boolean
  }
export interface TaskDagCardSummary {
  status: string
  state?: TaskState
  owner: string
  time: string
  rule: string
}

function scheduleKey(schedule: TaskSchedule) {
  if (schedule.mode === 'UNSCHEDULED') return ['UNSCHEDULED']
  return schedule.mode === 'FIXED'
    ? [
        schedule.mode,
        schedule.fixedStart || null,
        schedule.fixedEnd || null,
        schedule.fixedEnd ? null : schedule.durationDays
      ]
    : [schedule.mode, schedule.offsetDays, schedule.durationDays]
}
/** 图卡只表达配置与服务端预计值，不复制排期引擎或将草稿规则当成已运行日期。 */
export function taskDagCardSummary(
  node: TaskDagDisplayNode,
  options: {
    context: TaskDagContext
    nodes: readonly TaskDagDisplayNode[]
    members?: readonly TaskMember[]
    runtime?: TaskDagRuntimeInfo
    plannedStart?: string | null
  }
): TaskDagCardSummary {
  const { context, runtime } = options
  const actualState = runtime?.status || node.status
  const state =
    context === 'instance' && actualState
      ? taskDisplayState({ status: actualState, pausedByTaskId: runtime?.pausedByTaskId ?? node.pausedByTaskId })
      : undefined
  const status =
    context === 'template' ? '模板配置' : state ? taskStates[state] : context === 'instance' ? '草稿（新增）' : '草稿'
  // 同组摘要不具备人员安排及时间规则配置，不能用编辑占位值冒充真实配置。
  if (node.referenceOnly) {
    const expected = taskScheduleDateRange(runtime?.expectedStart, runtime?.expectedEnd)
    return {
      status,
      state,
      owner: runtime?.assigneeName || node.assigneeName || '未指定负责人',
      time: expected ? `预计：${expected}` : '预计时间未安排',
      rule: '同组任务 · 仅查看编排概要'
    }
  }
  const ownerId =
    node.assigneeId ??
    (node.assignmentMode === 'FOLLOW_ROOT' ? options.nodes.find(item => !item.parentId)?.assigneeId : null)
  const memberName = options.members?.find(member => String(member.id) === String(ownerId))?.name
  const runtimeName =
    runtime?.assigneeId != null && String(runtime.assigneeId) === String(node.assigneeId)
      ? runtime.assigneeName
      : undefined
  const changedAssignee = runtime?.assigneeId !== undefined && String(runtime.assigneeId) !== String(node.assigneeId)
  const owner =
    node.assignmentMode === undefined && node.assigneeId == null
      ? '待分配'
      : taskAssignmentLabel(node, memberName || (!changedAssignee ? node.assigneeName : undefined) || runtimeName)
  const summary = taskScheduleSummary(node, options.nodes, options.plannedStart)
  const time = [summary.primary, summary.secondary].filter(Boolean).join(' · ')
  const changedSchedule =
    !!runtime?.schedule && JSON.stringify(scheduleKey(node.schedule)) !== JSON.stringify(scheduleKey(runtime.schedule))
  const changedPredecessors =
    !!runtime?.predecessorIds &&
    JSON.stringify([...new Set(node.predecessorIds)].sort()) !==
      JSON.stringify([...new Set(runtime.predecessorIds)].sort())
  const changedStart =
    (node.schedule.mode === 'PLAN_START' || node.schedule.mode === 'AUTO') &&
    options.plannedStart !== undefined &&
    runtime?.plannedStart !== undefined &&
    (runtime.plannedStart || null) !== (options.plannedStart || null)
  const changed =
    context === 'instance' && (runtime?.expectedStale || changedSchedule || changedPredecessors || changedStart)
  const expected =
    !changed && context === 'instance'
      ? taskScheduleDateRange(
          runtime ? runtime.expectedStart : node.expectedStart,
          runtime ? runtime.expectedEnd : node.expectedEnd
        )
      : ''
  const scheduleState = !changed && context === 'instance' ? runtime?.scheduleSummary || node.scheduleSummary : null
  const warning = scheduleState?.warnings?.[0] || (scheduleState?.partial ? '部分预计日期待确定' : '')
  return {
    status,
    state,
    owner,
    time: expected ? `预计：${expected}${scheduleState?.partial ? '（部分）' : ''}` : time,
    rule: changed
      ? '待保存后重新计算预计日期'
      : warning ||
        (expected ? (node.schedule.mode === 'FIXED' ? '规则：指定日期' : `规则：${time}`) : summary.hint || '')
  }
}
