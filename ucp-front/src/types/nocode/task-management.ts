import type { TaskQuery, TaskUserId } from './task-center'

export type TaskManagementFocus = 'ACTIVE' | 'ALL' | 'UNASSIGNED' | 'OVERDUE' | 'PENDING_ACCEPTANCE'
export type TaskEmployeeMetric =
  'RELATED' | 'ALL' | 'PENDING' | 'RUNNING' | 'OVERDUE' | 'TODAY' | 'WEEK' | 'COORDINATION'

/** 员工身份只作筛选；可管理任务范围仍由服务端当前登录人决定。 */
export interface TaskManagementQuery {
  query: TaskQuery
  focus: TaskManagementFocus
  employeeId?: TaskUserId
  employeeMetric?: TaskEmployeeMetric
  /** 员工节点匹配后按所属总任务归并；不改变员工统计口径或旧扁平查询。 */
  groupByRoot?: boolean
}

export interface TaskEmployeeOverviewQuery {
  search?: string
  date: string
  pageNo: number
  pageSize: number
}

export interface TaskEmployeeOverviewRow {
  userId: TaskUserId
  userName: string
  pendingCount: number
  runningCount: number
  overdueCount: number
  todayCount: number
  weekCount: number
  coordinationCount: number
}

/** 保留汇总实际查询日期，避免下钻今日/本周清单时丢失日期上下文。 */
export interface TaskEmployeeSelection {
  userId: TaskUserId
  userName: string
  metric: TaskEmployeeMetric
  date: string
}
