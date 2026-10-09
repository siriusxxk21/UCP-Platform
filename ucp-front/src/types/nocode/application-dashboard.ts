import type { DashboardContent, DashboardChart } from './report-dashboard'
import type { DatasetContent, DatasetSource, ReportObjectReference } from './report-center'

/** 应用保存固定发布引用；运行请求不允许替换仪表板或数据集版本。 */
export interface ApplicationDashboardReference {
  id: string
  versionNo: number
  checksum: string
}
export interface ApplicationDashboardCandidate extends ApplicationDashboardReference {
  name: string
  chartCount: number
}
export const ApplicationDashboardInputSource = {
  PARAMETER: 'PARAMETER',
  RECORD_ID: 'RECORD_ID',
  RECORD_FIELD: 'RECORD_FIELD'
} as const
export type ApplicationDashboardInputSource =
  (typeof ApplicationDashboardInputSource)[keyof typeof ApplicationDashboardInputSource]
export interface ApplicationDashboardInputBinding {
  filterId: string
  source: ApplicationDashboardInputSource
  parameter?: string | null
  fieldId?: string | null
}
/** dashboard 为 null 仅用于尚未选择看板的编辑态，应用到草稿前必须补齐。 */
export interface ApplicationDashboardConfig {
  dashboard: ApplicationDashboardReference | null
  contextObjectId?: string | null
  inputBindings?: ApplicationDashboardInputBinding[] | null
  detailViews?: { chartId: string; viewId: string }[] | null
}
export interface ApplicationDashboardDatasetContract {
  reference: DashboardChart['dataset']
  content: DatasetContent
  source: {
    source: DatasetSource
    objects: ReportObjectReference[]
    fields: {
      id: string
      name: string
      role: string
      type: string
      objectId: string
      objectVersion: number
      relationPath: string[]
      sourceFieldId: string
    }[]
  }
}
/** 设计目录只携带逻辑来源和发布定义，不携带记录或物理存储信息。 */
export interface ApplicationDashboardCatalog {
  reference: ApplicationDashboardReference
  content: DashboardContent
  datasets: ApplicationDashboardDatasetContract[]
}
