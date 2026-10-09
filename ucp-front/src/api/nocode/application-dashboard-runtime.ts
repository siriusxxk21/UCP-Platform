import type { NocodeHttpClient } from './object'
import type {
  ApplicationDashboardModel,
  ApplicationDashboardQuery,
  ApplicationDashboardOptions,
  ApplicationDashboardDetails
} from '@/types/nocode/application-dashboard-runtime'
import type { DashboardDetailPage } from '@/types/nocode/report-dashboard'
import type { DatasetOptionPage } from '@/types/nocode/report-center'
import type { ReportResult } from '@/types/nocode/report'

/** 应用运行使用 OS 登录客户端，服务端从指定应用资源推导固定看板与授权交集。 */
export function createApplicationDashboardRuntimeApi(client: NocodeHttpClient) {
  const base = '/nocode/runtime/dashboard'
  return {
    model: (applicationId: string, resourceId: string) =>
      client.get<ApplicationDashboardModel>(base, { params: { applicationId, resourceId }, quiet: true }),
    query: (body: ApplicationDashboardQuery, signal?: AbortSignal) =>
      client.post<ReportResult>(`${base}-query`, body, { signal, quiet: true, timeout: 40000 }),
    options: (body: ApplicationDashboardOptions, signal?: AbortSignal) =>
      client.post<DatasetOptionPage>(`${base}-options`, body, { signal, quiet: true, timeout: 40000 }),
    details: (body: ApplicationDashboardDetails, signal?: AbortSignal) =>
      client.post<DashboardDetailPage>(`${base}-details`, body, { signal, quiet: true, timeout: 40000 }),
    export: (body: ApplicationDashboardQuery, signal?: AbortSignal) =>
      client.post<Blob>(`${base}-export`, body, { signal, quiet: true, timeout: 60000, responseType: 'blob' })
  }
}
export type ApplicationDashboardRuntimeApi = ReturnType<typeof createApplicationDashboardRuntimeApi>
