import dayjs from 'dayjs'
import { v4 as uuid } from 'uuid'
import type { TaskNodeInput, TaskRow, TaskPlan, TaskPeriod } from '@/types/nocode/task-center'

export const taskStates = {
  PENDING: '未开始',
  RUNNING: '进行中',
  PAUSED: '已暂停',
  PENDING_ACCEPTANCE: '待验收',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}
export const taskStateColors = {
  PENDING: 'default',
  RUNNING: 'processing',
  PAUSED: 'warning',
  PENDING_ACCEPTANCE: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'default'
}
export function taskNeedsAcceptance(task: Pick<TaskRow, 'id' | 'rootId' | 'acceptorId'>) {
  return task.id === task.rootId && task.acceptorId != null
}
export function taskAssignmentActionLabel(task: Pick<TaskRow, 'canTransfer' | 'canAssign' | 'assigneeId'>) {
  if (task.canTransfer) return '转交任务'
  return task.assigneeId != null ? '更换负责人' : '分配任务'
}
/** 暂停祖先冻结未结束节点，但不把已完成记录改成暂停，也不改写真实状态。 */
export function taskDisplayState(task: Pick<TaskRow, 'status' | 'pausedByTaskId'>) {
  return task.pausedByTaskId && !['COMPLETED', 'CANCELLED'].includes(task.status) ? 'PAUSED' : task.status
}
export const taskPauseExplanation =
  '暂停后，当前任务及其未结束的下级暂不能开始、完成或填写业务数据；已有进度和材料保留，计划日期不顺延。'
export const taskResumeExplanation =
  '恢复当前任务及受它影响的下级；下级独立暂停的任务仍需分别恢复。已有进度保留，计划日期不顺延。'
export const taskPriorities = { LOW: '低', MEDIUM: '中', HIGH: '高' }
export const taskTimeModes = {
  AUTO: '跟随任务顺序',
  UNSCHEDULED: '暂不安排',
  FIXED: '指定日期',
  PLAN_START: '按计划开始日期',
  T0: '实例创建后（旧规则）',
  PREDECESSOR: '接在前序任务后'
}
export const taskAssignmentModes = {
  UNASSIGNED: '待分配',
  OPEN: '待领取',
  ASSIGNED: '已分配',
  FOLLOW_ROOT: '随总任务负责人'
}
export function taskAssignmentLabel(node: Pick<TaskNodeInput, 'assignmentMode' | 'assigneeId'>, name?: string | null) {
  return node.assignmentMode === 'FOLLOW_ROOT'
    ? `随总任务负责人${name ? ` · ${name}` : ''}${node.assigneeId == null ? '（待承接）' : ''}`
    : node.assignmentMode === 'OPEN'
      ? '待领取'
      : node.assignmentMode === 'UNASSIGNED'
        ? '待分配'
        : name || '已分配'
}
export const taskPeriods = { DAY: '日计划', WEEK: '周计划', MONTH: '月计划' }
/** 已有总负责人只在新增时预填快照；未定人子项显式跟随，存量与手动选择不回填。 */
export function newTaskNode(parentId: string | null = null, root?: Pick<TaskRow, 'assigneeId'> | null): TaskNodeInput {
  const assigneeId = root?.assigneeId ?? null
  return {
    id: uuid(),
    parentId,
    title: '',
    description: '',
    assigneeId,
    acceptorId: null,
    assignmentMode: assigneeId != null ? 'ASSIGNED' : root || parentId ? 'FOLLOW_ROOT' : 'OPEN',
    candidateUserIds: [],
    urgency: 'NORMAL',
    priority: 'MEDIUM',
    schedule: { mode: 'UNSCHEDULED', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 1 },
    predecessorIds: [],
    binding: null,
    sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] }
  }
}
/** 新编排节点与个人拆分采用自动排期；存量草稿保持原有时间规则。 */
export function newAutoTaskNode(
  parentId: string | null = null,
  root?: Pick<TaskRow, 'assigneeId'> | null
): TaskNodeInput {
  const node = newTaskNode(parentId, root)
  node.schedule.mode = 'AUTO'
  return node
}
export function taskNodeInput(row: TaskRow): TaskNodeInput {
  return {
    ...(row.id === row.rootId && row.workTotalMode ? { workTotalMode: row.workTotalMode } : {}),
    ...(row.id === row.rootId && row.effectiveWorkMinutes != null
      ? { effectiveWorkMinutes: row.effectiveWorkMinutes }
      : {}),
    ...(row.dataPolicy && row.id === row.rootId ? { dataPolicy: { ...row.dataPolicy } } : {}),
    id: row.id,
    parentId: row.parentId,
    title: row.title,
    description: row.description || '',
    assigneeId: row.assigneeId,
    acceptorId: row.id === row.rootId ? (row.acceptorId ?? null) : null,
    ...(!row.legacyProtocol
      ? { assignmentMode: row.assignmentMode || 'ASSIGNED', candidateUserIds: [...(row.candidateUserIds || [])] }
      : {}),
    urgency: row.urgency,
    priority: row.priority,
    schedule: { ...row.schedule },
    predecessorIds: [...row.predecessorIds],
    binding: row.sharing.mode === 'SHARED' ? null : row.binding ? { ...row.binding } : null,
    sharing: { ...row.sharing, writableFieldIds: [...row.sharing.writableFieldIds] },
    entries: row.entries ? JSON.parse(JSON.stringify(row.entries)) : null
  }
}
export function planDate(period: TaskPeriod, date: string) {
  const parsed = dayjs(date)
  return (
    period === 'MONTH'
      ? parsed.startOf('month')
      : period === 'WEEK'
        ? parsed.subtract((parsed.day() + 6) % 7, 'day')
        : parsed
  ).format('YYYY-MM-DD')
}
export function hasTaskPlan(plans: TaskPlan[], period: TaskPeriod, date: string) {
  return plans.some(plan => plan.period === period && planDate(period, plan.date) === planDate(period, date))
}
export function taskTime(value: string | null | undefined) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'
}
/** 预计安排按日期展示；实际执行及操作历史继续使用精确时间。 */
export function taskDate(value: string | null | undefined) {
  return value ? dayjs(value).format('YYYY-MM-DD') : '—'
}

/** 同一页上的父子关系保持树状；页外父节点不伪造，详情负责加载完整实例。 */
export function taskTree<T extends { id: string; parentId: string | null }>(
  rows: T[]
): Array<T & { children?: Array<T & { children?: unknown[] }> }> {
  const nodes = new Map(rows.map(row => [row.id, { ...row, children: [] as Array<T & { children?: unknown[] }> }]))
  const roots: Array<T & { children?: Array<T & { children?: unknown[] }> }> = []
  for (const row of rows) {
    const node = nodes.get(row.id)!,
      parent = row.parentId ? nodes.get(row.parentId) : undefined
    if (parent && parent !== node) parent.children.push(node)
    else roots.push(node)
  }
  for (const node of nodes.values()) if (!node.children.length) delete (node as { children?: unknown[] }).children
  return roots
}

export function taskNodeError(nodes: TaskNodeInput[]): string | null {
  const index = new Map(nodes.map(node => [node.id, node]))
  if (!nodes.length) return '至少保留一个任务节点'
  if (index.size !== nodes.length) return '节点标识重复，请重新加载'
  for (const node of nodes) {
    if (!node.title.trim()) return '请填写每个任务的名称'
    if (node.parentId && !index.has(node.parentId)) return '子任务的父节点不存在'
    if (node.predecessorIds.some(id => !index.has(id) || id === node.id)) return `“${node.title}”的前置任务无效`
    if (node.assignmentMode === 'ASSIGNED' && !node.assigneeId) return `请选择“${node.title}”的负责人`
    if (node.acceptorId != null && node.parentId) return `“${node.title}”是子任务，由负责人直接完成，不单独配置验收人`
    if (node.acceptorId != null && String(node.acceptorId) === String(node.assigneeId))
      return `“${node.title}”的负责人和验收人不能是同一人`
    if (node.schedule.mode === 'FIXED' && !node.schedule.fixedStart && !node.schedule.fixedEnd)
      return `请填写“${node.title}”的预计开始或预计结束，或选择暂不安排`
    if (
      node.schedule.mode === 'FIXED' &&
      node.schedule.fixedStart &&
      node.schedule.fixedEnd &&
      dayjs(node.schedule.fixedEnd).isBefore(
        dayjs(node.schedule.fixedStart),
        /^\d{4}-\d{2}-\d{2}$/.test(node.schedule.fixedEnd) ? 'day' : undefined
      )
    )
      return `“${node.title}”的预计结束不能早于预计开始`
    if (node.schedule.mode === 'PREDECESSOR' && !node.predecessorIds.length)
      return `“${node.title}”选择前置完成时间后需要配置前置任务`
    if (node.schedule.mode === 'AUTO' && !nodes.some(item => item.parentId === node.id)) {
      if (!Number.isInteger(node.schedule.durationDays) || node.schedule.durationDays < 0)
        return `请填写“${node.title}”的计划工期（不小于 0 的整数天数）`
      if (!Number.isInteger(node.schedule.offsetDays) || node.schedule.offsetDays < 0)
        return `请填写“${node.title}”的开始间隔（不小于 0 的整数天数）`
    }
    if (node.sharing.mode === 'SHARED' && !node.sharing.sourceNodeId) return `请选择“${node.title}”的共享来源`
    if (node.schedule.mode !== 'AUTO' && (node.schedule.durationDays < 0 || node.schedule.offsetDays < 0))
      return '时间偏移和持续天数不能小于 0'
  }
  return taskStructureError(nodes)
}

/** 编排时允许暂未填写人员、名称与时间，但父子层级和执行等待关系必须始终合法。 */
export function taskStructureError(
  nodes: Array<Pick<TaskNodeInput, 'id' | 'title' | 'parentId' | 'predecessorIds'>>
): string | null {
  const index = new Map(nodes.map(node => [node.id, node]))
  if (index.size !== nodes.length) return '节点标识重复，请重新加载'
  for (const node of nodes) {
    if (node.parentId && !index.has(node.parentId)) return '子任务的父节点不存在'
    if (node.predecessorIds.some(id => !index.has(id) || id === node.id))
      return `“${node.title || '当前任务'}”的前置任务无效`
  }
  const visiting = new Set<string>(),
    visited = new Set<string>()
  function cyclic(id: string): boolean {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    // 父节点的完成需要等待其子节点，与前驱一同检查才能发现父子死锁。
    const required = [...(index.get(id)?.predecessorIds || []), ...nodes.filter(n => n.parentId === id).map(n => n.id)]
    // 下级还要等待祖先开始；祖先的前置完成依赖也会阻塞下级，不能只检查完成边。
    const ancestors = new Set<string>()
    let parentId = index.get(id)?.parentId
    while (parentId && !ancestors.has(parentId)) {
      ancestors.add(parentId)
      const parent = index.get(parentId)
      required.push(...(parent?.predecessorIds || []))
      parentId = parent?.parentId
    }
    if (required.some(cyclic)) return true
    visiting.delete(id)
    visited.add(id)
    return false
  }
  return nodes.some(node => cyclic(node.id)) ? '任务依赖存在循环或父子等待死锁，请调整前置关系' : null
}
