import type { TaskDetail, TaskNodeInput, TaskRow, TaskStructureNode, TaskUserId } from '@/types/nocode/task-center'
import { taskAssignmentLabel, taskDisplayState } from './task-center'

/** 结构与详情分开：仅从已获授权的 nodes 获取操作能力，摘要只用于定位和绘图。 */
export function instanceStructure(detail: TaskDetail): TaskStructureNode[] {
  const result = new Map((detail.structure || []).map(node => [node.id, node]))
  for (const node of detail.nodes) {
    result.set(node.id, {
      id: node.id,
      rootId: node.rootId,
      parentId: node.parentId,
      title: node.title,
      status: taskDisplayState(node),
      assigneeName: taskAssignmentLabel(node, node.assigneeName),
      predecessorIds: node.predecessorIds,
      expectedStart: node.expectedStart,
      expectedEnd: node.expectedEnd,
      detailVisible: node.detailVisible !== false
    })
  }
  return [...result.values()]
}

/** 补齐图和列表的结构端点；占位配置不写入可提交的编辑模型。 */
export function arrangementDisplayNodes(nodes: TaskNodeInput[], structure: TaskStructureNode[]): TaskNodeInput[] {
  const editable = new Map(nodes.map(node => [node.id, node]))
  const ordered = structure.map(
    node =>
      editable.get(node.id) || {
        id: node.id,
        parentId: node.parentId,
        title: node.title,
        description: '',
        assigneeId: null,
        acceptorId: null,
        urgency: 'NORMAL' as const,
        priority: 'MEDIUM' as const,
        schedule: { mode: 'UNSCHEDULED' as const, fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 0 },
        predecessorIds: [...node.predecessorIds],
        binding: null,
        sharing: { mode: 'INDEPENDENT' as const, sourceNodeId: null, writableFieldIds: [] }
      }
  )
  const known = new Set(structure.map(node => node.id))
  return [...ordered, ...nodes.filter(node => !known.has(node.id))]
}

/** 包含继承的等待条件，以便从阻塞提示定位真实前置节点。 */
export function instancePredecessors(detail: TaskDetail): TaskStructureNode[] {
  const structure = instanceStructure(detail)
  const lookup = new Map(structure.map(node => [node.id, node]))
  const ids = new Set<string>()
  const visited = new Set<string>()
  let node = lookup.get(detail.task.id)
  while (node && !visited.has(node.id)) {
    visited.add(node.id)
    node.predecessorIds.forEach(id => ids.add(id))
    node = node.parentId ? lookup.get(node.parentId) : undefined
  }
  return [...ids].flatMap(id => (lookup.get(id) ? [lookup.get(id)!] : []))
}

const active = (node?: TaskRow) => !!node && !node.pausedByTaskId && ['PENDING', 'RUNNING'].includes(node.status)

/** 入口不决定权限；整组调整沿用管理权限，员工拆分仍需服务端返回的节点权限。 */
export function instanceArrangementAccess(nodes: TaskRow[], rootId: string, manager: boolean) {
  const root = nodes.find(node => node.id === rootId)
  return !!root && manager && active(root)
}

export function canSplitInstanceNode(node: TaskRow | undefined, root: TaskRow | undefined, userId?: TaskUserId) {
  return (
    active(node) &&
    (!root || active(root)) &&
    !!node?.canEdit &&
    userId != null &&
    node.assigneeId != null &&
    String(node.assigneeId) === String(userId)
  )
}

export function instanceNodeRestriction(node: TaskRow | undefined, root: TaskRow | undefined, editable: boolean) {
  if (root?.status === 'PAUSED' || root?.pausedByTaskId) return root.pauseReason || '整组任务已暂停，恢复后再调整编排'
  if (node?.status === 'PAUSED' || node?.pausedByTaskId) return node.pauseReason || '任务已暂停，恢复后再执行或拆分'
  if (root && !active(root)) return '整组任务已进入验收或已结束，编排只读'
  if (!node) return '新任务，保存调整后生效'
  if (node.status === 'RUNNING') return '已开始，原安排已锁定；有权限时可继续拆分'
  if (node.status === 'PENDING_ACCEPTANCE') return '待验收，只能通过验收或退回处理'
  if (node.status === 'COMPLETED' || node.status === 'CANCELLED') return '已结束，保留历史记录，只读'
  return editable ? '尚未开始，可调整；保存后生效，不修改原模板' : '原安排只读；可按权限执行或拆分自己负责的任务'
}
