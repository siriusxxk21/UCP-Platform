import type { DashboardRuntimeModel } from './dashboard-runtime'
import type { DashboardFilterValue, DashboardLinkSelection, DashboardSelection } from './report-dashboard'
import type { ApplicationDashboardConfig } from './application-dashboard'

export interface ApplicationDashboardModel extends DashboardRuntimeModel {
  applicationId: string
  resourceId: string
  stamp: string
  boundFilterIds: string[]
  config?: ApplicationDashboardConfig
}
export interface ApplicationDashboardInputValue {
  values?: (string | null)[] | null
  from?: string | null
  to?: string | null
}
/** 固定引用只能由应用发布资源还原，请求没有自由的看板/数据集ID或版本参数。 */
export interface ApplicationDashboardQuery {
  applicationId: string
  resourceId: string
  chartId: string
  stamp: string
  parameters?: Record<string, ApplicationDashboardInputValue> | null
  recordId?: string | null
  filterValues?: DashboardFilterValue[]
  selections?: DashboardLinkSelection[]
  drillPath?: (string | null)[]
}
export interface ApplicationDashboardOptions {
  query: ApplicationDashboardQuery
  filterId: string
  pageNo: number
  pageSize: number
  search?: string
}
export interface ApplicationDashboardDetails extends DashboardSelection {
  query: ApplicationDashboardQuery
  pageNo: number
  pageSize: number
}

/** 业务视图的范围来自已成功取图的完整应用查询快照。 */
export interface ApplicationDashboardDrill extends DashboardSelection {
  query: ApplicationDashboardQuery
}
