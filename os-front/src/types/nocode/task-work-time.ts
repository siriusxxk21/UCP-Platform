import type { TaskWorkEntryConfig } from './task-work-entries'

export interface TaskWorkTimeEntry {
  taskId: string
  taskTitle: string
  config: TaskWorkEntryConfig
}
export interface TaskWorkTimeContext {
  rootId: string
  rootTitle: string
  expectedRevision: number
  workTotalMode: 'AUTO' | 'MANUAL'
  effectiveWorkMinutes: number | null
  entries: TaskWorkTimeEntry[]
  canAdjust: boolean
  disabledReason: string | null
}
/** 运行中只允许改变单价和预算，不发送模式、字段、绑定或授权。 */
export interface TaskWorkTimeChange {
  rootId: string
  expectedRevision: number
  requestKey: string
  reason: string
  workTotalMode: 'AUTO' | 'MANUAL'
  effectiveWorkMinutes: number | null
  entries: Array<{ taskId: string; entryKey: string; minutes: number; plannedQuantity: number | null }>
}
