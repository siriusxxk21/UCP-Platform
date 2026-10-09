import type {
  DashboardRelease,
  DashboardQuery,
  DashboardOptions,
  DashboardDetails,
  DashboardDetailPage,
  DashboardPreferenceState
} from './report-dashboard'
import type { DatasetOptionPage } from './report-center'
import type { ReportResult } from './report'

/** 共用展示只接收受控传输接口；应用适配器不发送组件中的独立版本身份。 */
export interface DashboardRuntimeModel {
  dashboard: DashboardRelease
  boundFilterIds?: string[]
}
export interface DashboardRuntimeTransport {
  load: () => Promise<DashboardRuntimeModel>
  query: (query: DashboardQuery, signal?: AbortSignal) => Promise<ReportResult>
  options: (query: DashboardOptions, signal?: AbortSignal) => Promise<DatasetOptionPage>
  details: (query: DashboardDetails, signal?: AbortSignal) => Promise<DashboardDetailPage>
  export: (query: DashboardQuery, signal?: AbortSignal) => Promise<Blob>
  canBusinessDetails?: (chartId: string) => boolean
  businessDetails?: (query: DashboardDetails) => void
  preference?: (id: string) => Promise<DashboardPreferenceState>
  favorite?: (body: { id: string; favorite: boolean }) => Promise<DashboardPreferenceState>
  visit?: (id: string) => Promise<DashboardPreferenceState>
}
