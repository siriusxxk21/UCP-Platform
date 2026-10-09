import dayjs from 'dayjs'
import type { TaskRow } from '@/types/nocode/task-center'

/** 只压缩已知的服务端提示，完整原因仍由界面保留；不在前端重新判断开始资格。 */
export function taskExecutionHint(task: Pick<TaskRow, 'status' | 'canStart' | 'blockedReason' | 'completionReason'>) {
  if (task.completionReason) return task.completionReason
  if (task.status !== 'PENDING') return ''
  if (task.canStart) return '可开始'
  const reason = task.blockedReason || ''
  const predecessor = reason.match(/^前置任务「([^」]+)」.*尚未完成$/)
  if (predecessor) return `等待 ${predecessor[1]} 完成`
  if (reason.startsWith('等待上级任务') && reason.endsWith('开始')) return '等待上级开始'
  if (reason.startsWith('尚未到预计开始时间')) return '等待计划开始'
  return reason
}

/** 本人处理进度和整项状态分开表达；完成上下文不重新计入待办。 */
export function taskPersonalProgress(task: Pick<TaskRow, 'myPendingCount' | 'myCompletedCount'>, done = false) {
  if (task.myPendingCount == null || task.myCompletedCount == null) return ''
  if (done)
    return task.myPendingCount
      ? `我已办 ${task.myCompletedCount} 项，另有 ${task.myPendingCount} 项待处理`
      : '我的部分已处理'
  return task.myPendingCount
    ? `我待处理 ${task.myPendingCount} 项 · 已办 ${task.myCompletedCount} 项`
    : '我的部分已处理'
}

/** 与待办快捷筛选一致按日提示；今天到期不计逾期，不改变排期或执行状态。 */
export function taskDueHint(task: Pick<TaskRow, 'status' | 'expectedEnd'>, today = dayjs()) {
  if (!task.expectedEnd || ['COMPLETED', 'CANCELLED'].includes(task.status)) return null
  const end = dayjs(task.expectedEnd)
  if (!end.isValid()) return null
  const days = end.startOf('day').diff(today.startOf('day'), 'day')
  if (days < 0) return { text: `已逾期 ${-days} 天`, tone: 'danger' }
  if (days === 0) return { text: '今天到期', tone: 'warning' }
  if (days === 1) return { text: '明天到期', tone: 'warning' }
  return null
}
