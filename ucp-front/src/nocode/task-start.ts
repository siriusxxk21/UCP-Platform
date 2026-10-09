import dayjs from 'dayjs'
import type { TaskRow } from '@/types/nocode/task-center'

/** 预计日期是排期参考；是否可开始仍使用服务端资格，提前开始需明确提示。 */
export function taskStartsEarly(task: Pick<TaskRow, 'expectedStart' | 'status'>, now = dayjs()) {
  return task.status === 'PENDING' && !!task.expectedStart && dayjs(task.expectedStart).isAfter(now)
}

export function taskEarlyStartMessage(task: Pick<TaskRow, 'expectedStart' | 'title'>) {
  return `“${task.title}”预计于 ${dayjs(task.expectedStart).format('YYYY-MM-DD HH:mm')} 开始。现在开始会记录实际开始时间，原预计时间和计划清单保持不变；下级任务仍按原依赖顺序执行。`
}
