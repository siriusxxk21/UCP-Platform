import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/application'
import type { ReportResult } from '@/types/nocode/report'
import type * as RC from '@/types/nocode/report-center'
import type * as DB from '@/types/nocode/report-dashboard'

/** 独立数据集接口复用 OS 认证客户端；资源、对象上限和成员策略分别使用自己的修订号。 */
export function createReportCenterApi(client: NocodeHttpClient) {
  const base = '/nocode/report/dataset'
  const dashboard = '/nocode/report/dashboard'
  return {
    dashboardAvailablePage: (params: RC.ReportPageQuery & { status?: RC.ReportResourceStatus }) =>
      client.get<Page<DB.DashboardAvailableItem>>(`${dashboard}/available-page`, { params }),
    dashboardResourcePolicy: (id: string) =>
      client.get<DB.DashboardResourcePolicy>(`${dashboard}/resource-policy`, { params: { id } }),
    saveDashboardResourcePolicy: (body: {
      id: string
      expectedRevision: number
      members: DB.DashboardResourcePolicy['members']
      reason: string
    }) => client.post<DB.DashboardResourcePolicy>(`${dashboard}/resource-policy`, body),
    dashboardPreferencePage: (params: RC.ReportPageQuery & { view: 'ALL' | 'FAVORITE' | 'RECENT' }) =>
      client.get<Page<DB.DashboardPreferenceItem>>(`${dashboard}/preference-page`, { params }),
    dashboardPreference: (id: string) =>
      client.get<DB.DashboardPreferenceState>(`${dashboard}/preference`, { params: { id }, quiet: true }),
    dashboardFavorite: (body: { id: string; favorite: boolean }) =>
      client.post<DB.DashboardPreferenceState>(`${dashboard}/favorite`, body, { quiet: true }),
    dashboardVisit: (id: string) =>
      client.post<DB.DashboardPreferenceState>(`${dashboard}/visit`, { id }, { quiet: true }),
    dashboardCopy: (body: RC.DatasetRevision & { name: string }) =>
      client.post<DB.DashboardDetail>(`${dashboard}/copy`, body),
    dashboardMove: (body: RC.DatasetRevision & { folderId: string | null }) =>
      client.post<DB.DashboardDetail>(`${dashboard}/move`, body),
    dashboardStatus: (body: RC.DatasetRevision & { status: RC.ReportResourceStatus }) =>
      client.post<DB.DashboardDetail>(`${dashboard}/status`, body),
    dashboardRestore: (body: RC.DatasetRevision & { versionNo: number }) =>
      client.post<DB.DashboardDetail>(`${dashboard}/restore`, body),
    dashboardReleases: (id: string, params: { pageNo: number; pageSize: number }) =>
      client.get<Page<DB.DashboardRelease>>(`${dashboard}/releases`, { params: { id, ...params } }),
    dashboardDeletePreview: (id: string) =>
      client.get<DB.DashboardDeletePreview>(`${dashboard}/delete-preview`, { params: { id } }),
    dashboardDelete: (body: RC.DatasetRevision) =>
      client.post<{ id: string; revision: number; deleted: boolean }>(`${dashboard}/delete`, body),
    dashboardPage: (params: RC.ReportPageQuery) =>
      client.get<Page<DB.DashboardDetail>>(`${dashboard}/page`, { params }),
    dashboardGet: (id: string) => client.get<DB.DashboardDetail>(`${dashboard}/get`, { params: { id } }),
    dashboardSave: (body: {
      id: string | null
      expectedRevision: number
      content: DB.DashboardContent
      folderId?: string | null
    }) => client.post<DB.DashboardDetail>(`${dashboard}/save`, body),
    dashboardPublish: (body: { id: string; expectedRevision: number; requestId: string }) =>
      client.post<DB.DashboardRelease>(`${dashboard}/publish`, body),
    dashboardPublished: (id: string, versionNo?: number, checksum?: string) =>
      client.get<DB.DashboardRelease>(`${dashboard}/published`, { params: { id, versionNo, checksum } }),
    dashboardDetails: (body: DB.DashboardDetails, signal?: AbortSignal) =>
      client.post<DB.DashboardDetailPage>(`${dashboard}/details`, body, { timeout: 40000, signal, quiet: true }),
    dashboardExport: (body: DB.DashboardQuery, signal?: AbortSignal) =>
      client.post<Blob>(`${dashboard}/export`, body, { responseType: 'blob', timeout: 60000, signal, quiet: true }),
    dashboardChartPreview: (body: DB.DashboardChart, signal?: AbortSignal) =>
      client.post<ReportResult>(`${dashboard}/chart-preview`, body, { timeout: 40000, signal, quiet: true }),
    dashboardQuery: (body: DB.DashboardQuery, signal?: AbortSignal) =>
      client.post<ReportResult>(`${dashboard}/query`, body, { timeout: 40000, signal, quiet: true }),
    dashboardOptions: (body: DB.DashboardOptions, signal?: AbortSignal) =>
      client.post<RC.DatasetOptionPage>(`${dashboard}/options`, body, { timeout: 40000, signal, quiet: true }),
    page: (params: RC.ReportPageQuery) => client.get<Page<RC.DatasetDetail>>(`${base}/page`, { params }),
    get: (id: string) => client.get<RC.DatasetDetail>(`${base}/get`, { params: { id } }),
    save: (body: RC.DatasetSave) => client.post<RC.DatasetDetail>(`${base}/save`, body),
    folders: (resourceKind?: 'DATASET' | 'DASHBOARD') =>
      client.get<RC.ReportFolder[]>('/nocode/report/folder/tree', { params: { resourceKind } }),
    saveFolder: (body: RC.ReportFolderSave & { resourceKind?: 'DATASET' | 'DASHBOARD' }) =>
      client.post<RC.ReportFolder>('/nocode/report/folder/save', body),
    deleteFolder: (body: RC.DatasetRevision & { resourceKind?: 'DATASET' | 'DASHBOARD' }) =>
      client.post<{ id: string; revision: number }>('/nocode/report/folder/delete', body),
    move: (body: RC.DatasetRevision & { folderId: string | null }) =>
      client.post<RC.DatasetDetail>(`${base}/move`, body),
    copy: (body: RC.DatasetRevision & { name: string }) => client.post<RC.DatasetDetail>(`${base}/copy`, body),
    deletePreview: (id: string) => client.get<RC.DatasetDeletePreview>(`${base}/delete-preview`, { params: { id } }),
    delete: (body: RC.DatasetRevision) =>
      client.post<{ id: string; revision: number; deleted: boolean }>(`${base}/delete`, body),
    publish: (body: RC.DatasetRevision & { requestId: string }) =>
      client.post<RC.DatasetRelease>(`${base}/publish`, body),
    releases: (id: string, params: { pageNo: number; pageSize: number }) =>
      client.get<Page<RC.DatasetRelease>>(`${base}/releases`, { params: { id, ...params } }),
    restore: (body: RC.DatasetRevision & { versionNo: number }) =>
      client.post<RC.DatasetDetail>(`${base}/restore`, body),
    status: (body: RC.DatasetRevision & { status: RC.ReportResourceStatus }) =>
      client.post<RC.DatasetDetail>(`${base}/status`, body),
    sourceObjects: (params: RC.ReportPageQuery) =>
      client.get<Page<RC.ReportObjectItem>>(`${base}/source-objects`, { params }),
    sourceObject: (id: string, versionNo?: number) =>
      client.get<RC.ReportObjectVersion>(`${base}/source-object`, { params: { id, versionNo } }),
    authorizationTargets: (params: RC.ReportPageQuery) =>
      client.get<Page<RC.DatasetAuthorizationTarget>>(`${base}/authorization-targets`, { params }),
    authorizationObjects: (id: string) =>
      client.get<RC.DatasetAuthorizationObject[]>(`${base}/authorization-objects`, { params: { id } }),
    resourcePolicy: (id: string) => client.get<RC.DatasetResourcePolicy>(`${base}/resource-policy`, { params: { id } }),
    saveResourcePolicy: (body: RC.DatasetPolicyRevision & { members: RC.DatasetResourceMember[] }) =>
      client.post<RC.DatasetResourcePolicy>(`${base}/resource-policy`, body),
    ceilings: (id: string) => client.get<RC.DatasetObjectCeiling[]>(`${base}/ceilings`, { params: { id } }),
    saveCeiling: (body: RC.DatasetPolicyRevision & Pick<RC.DatasetObjectCeiling, 'objectId' | 'permission'>) =>
      client.post<RC.DatasetObjectCeiling>(`${base}/ceiling`, body),
    dataPolicy: (id: string) => client.get<RC.DatasetDataPolicy>(`${base}/data-policy`, { params: { id } }),
    saveDataPolicy: (body: RC.DatasetPolicyRevision & Pick<RC.DatasetDataPolicy, 'members'>) =>
      client.post<RC.DatasetDataPolicy>(`${base}/data-policy`, body),
    options: (body: RC.DatasetOptions) =>
      client.post<RC.DatasetOptionPage>(`${base}/options`, body, { timeout: 40000 }),
    query: (body: RC.DatasetQuery) => client.post<ReportResult>(`${base}/query`, body, { timeout: 40000 })
  }
}
export type ReportCenterApi = ReturnType<typeof createReportCenterApi>
