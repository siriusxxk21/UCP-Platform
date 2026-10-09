import type { TaskRow } from './task-center'

/** 统计只读取服务端授予的管理范围，不通过前端传入管理人身份。 */
export interface TaskEfficiencyQuery {
  from: string
  to: string
  employeeId?: number
  templateId?: string
  rootTaskId?: string
  search?: string
  pageNo?: number
  pageSize?: number
  sortBy?: 'standardMinutes' | 'recordCount' | 'completedNodeCount' | 'overdueNodeCount'
  descending?: boolean
}

export interface TaskEfficiencyEmployee {
  employeeId: number
  employeeName: string
  standardMinutes: number
  recordCount: number
  participatedTaskCount: number
  completedNodeCount: number
  activeNodeCount: number
  overdueNodeCount: number
}

export interface TaskEfficiencyOverview {
  standardMinutes: number
  recordCount: number
  employeeCount: number
  completedNodeCount: number
  activeNodeCount: number
  overdueNodeCount: number
  trend: { date: string; standardMinutes: number; recordCount: number }[]
  employees: Pick<TaskEfficiencyEmployee, 'employeeId' | 'employeeName' | 'standardMinutes' | 'recordCount'>[]
}

export interface TaskEfficiencyTask {
  rootTaskId: string
  title: string
  templateId: string | null
  templateName: string | null
  templateVersion: number | null
  status: TaskRow['status']
  assigneeId: number | null
  assigneeName: string | null
  referenceMinutes: number | null
  standardMinutes: number
  recordCount: number
  employeeCount: number
  completedNodeCount: number
  totalNodeCount: number
  cancelledNodeCount: number
  overdueNodeCount: number
  expectedEnd: string | null
  actualStart: string | null
  actualEnd: string | null
  elapsedMinutes: number | null
}

export interface TaskEfficiencyRecord {
  taskId: string
  taskTitle: string
  rootTaskId: string
  rootTitle: string
  entryKey: string
  entryName: string
  employeeId: number
  employeeName: string
  recordId: string
  ruleMode: 'RECORD_ONCE' | 'QUANTITY' | 'CONDITION'
  quantity: number
  /** 混合历史单价时为空，不再以当前单价倒算实际数量。 */
  unitMinutes: number | null
  standardMinutes: number
  firstCountedAt: string
  lastHandledAt: string
}

export interface TaskEfficiencyOptions {
  employees: { id: number; name: string }[]
  templates: { id: string; name: string }[]
}
