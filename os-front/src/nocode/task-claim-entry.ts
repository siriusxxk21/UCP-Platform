import type { TaskRow, TaskStructureNode } from '@/types/nocode/task-center'

export type TaskClaimLocation = Pick<TaskRow, 'id' | 'rootId' | 'title'>

/** 摘要只提供查询入口，不能从“待领取”推定实际领取权限。 */
export function canLocateTaskClaim(node?: TaskRow | TaskStructureNode): boolean {
  if (!node || node.status !== 'PENDING') return false
  if ('assigneeId' in node) {
    if (node.assigneeId != null || node.pausedByTaskId) return false
    return !!node.canClaim || node.assignmentMode === 'OPEN' || node.assignmentMode === 'FOLLOW_ROOT'
  }
  return node.assigneeName === '待领取' || node.assigneeName === '随总负责人（待承接）'
}
