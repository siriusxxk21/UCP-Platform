import type { NocodeHttpClient } from './object'
import type * as AC from '@/types/nocode/application'
import type * as LS from '@/types/nocode/linkage-sync'
import type {
  ApplicationDashboardCandidate,
  ApplicationDashboardCatalog,
  ApplicationDashboardReference
} from '@/types/nocode/application-dashboard'
import type {
  ApplicationPolicy,
  ApplicationMember,
  ObjectSharingGrant,
  ObjectGrant
} from '@/types/nocode/authorization'
/** 应用配置通过统一认证客户端访问，调用方不能传入操作者或数据源。 */
export function createApplicationApi(client: NocodeHttpClient) {
  return {
    /** 应用设计候选只检查仪表板 VIEW，数据授权仍由运行端按交集校验。 */
    dashboardCatalogPage: (params: { pageNo: number; pageSize: number; search?: string }) =>
      client.get<AC.Page<ApplicationDashboardCandidate>>('/nocode/application/dashboard-catalog-page', { params }),
    dashboardCatalog: (body: { reference: ApplicationDashboardReference; objects: AC.ObjectReference[] }) =>
      client.post<ApplicationDashboardCatalog>('/nocode/application/dashboard-catalog', body),
    previewReport: (body: {
      applicationId: string
      objects: AC.ObjectReference[]
      config: import('@/types/nocode/report').ReportConfig
      resources: AC.ApplicationResource[]
    }) => client.post<import('@/types/nocode/report').ReportResult>('/nocode/application/report-preview', body),
    previewSelection: (body: {
      query: import('@/types/nocode/selection').SelectionQuery
      objects: AC.ObjectReference[]
      form?: import('@/types/nocode/application-ui').FormConfig
      validate?: boolean
    }) =>
      client.post<import('@/types/nocode/selection').SelectionResult>('/nocode/application/selection-preview', body),
    previewFormFill: (body: {
      query: import('@/types/nocode/application-ui').FormFillPreviewQuery
      objects: AC.ObjectReference[]
      form: import('@/types/nocode/application-ui').FormConfig
    }) => client.post<Record<string, unknown>>('/nocode/application/form-fill-preview', body),
    previewFieldRules: (body: {
      query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery
      objects: AC.ObjectReference[]
      form: import('@/types/nocode/application-ui').FormConfig
    }) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(
        '/nocode/application/field-rules-preview',
        body,
        { quiet: true }
      ),
    /** applicationId：挑取值按该应用草稿固定的对象版本取候选；不带时挑取值字段没有选项。 */
    selectionOptions: (id: string, fieldId: string, search?: string, versionNo?: number, applicationId?: string) =>
      client.get<import('@/types/nocode/selection').SelectionOption[]>('/nocode/application/selection-options', {
        params: { id, fieldId, search, versionNo, applicationId }
      }),
    sharing: (id: string) => client.get<ObjectSharingGrant[]>('/nocode/object-sharing/application', { params: { id } }),
    objectSharing: (objectId: string) =>
      client.get<ObjectSharingGrant[]>('/nocode/object-sharing/list', { params: { objectId } }),
    sharingTargets: () => client.get<Array<{ id: string; name: string }>>('/nocode/object-sharing/targets'),
    sharingDefinition: (objectId: string) =>
      client.get<AC.PublishedObject>('/nocode/object-sharing/definition', { params: { objectId } }),
    saveObjectSharing: (body: {
      objectId: string
      applicationId: string
      expectedRevision: number
      permission: ObjectGrant | null
      reason: string
    }) => client.post<ObjectSharingGrant>('/nocode/object-sharing/save', body),
    authorization: (id: string) =>
      client.get<ApplicationPolicy>('/nocode/application/authorization', { params: { id } }),
    saveAuthorization: (body: { applicationId: string; expectedRevision: number; members: ApplicationMember[] }) =>
      client.post<ApplicationPolicy>('/nocode/application/authorization', body),
    categories: () => client.get<string[]>('/nocode/application/categories'),
    page: (params: { pageNo: number; pageSize: number; search?: string; category?: string }) =>
      client.get<AC.Page<AC.ApplicationRow>>('/nocode/application/page', { params }),
    get: (id: string) => client.get<AC.ApplicationDetail>('/nocode/application/get', { params: { id } }),
    releases: (id: string, params: { pageNo: number; pageSize: number }) =>
      client.get<AC.Page<AC.Release>>('/nocode/application/releases', { params: { id, ...params } }),
    recyclePage: (params: { pageNo: number; pageSize: number; search?: string }) =>
      client.get<AC.Page<AC.RecycledApplicationRow>>('/nocode/application/recycle-page', { params }),
    deletePreview: (id: string) =>
      client.get<AC.ApplicationDeletePreview>('/nocode/application/delete-preview', { params: { id } }),
    deleteApplication: (body: AC.ApplicationRevision) => client.post<boolean>('/nocode/application/delete', body),
    restoreRecycled: (body: AC.ApplicationRevision) =>
      client.post<AC.ApplicationDetail>('/nocode/application/recycle-restore', body),
    publishAndEnable: (body: AC.ApplicationRevision) =>
      client.post<AC.ApplicationDetail>('/nocode/application/publish-and-enable', body),
    save: (body: AC.SaveApplication) => client.post<AC.ApplicationDetail>('/nocode/application/save', body),
    publish: (body: { id: string; expectedRevision: number; reason: string }) =>
      client.post<AC.ApplicationDetail>('/nocode/application/publish', body),
    restore: (body: { id: string; expectedRevision: number; sourceVersion: number; reason: string }) =>
      client.post<AC.ApplicationDetail>('/nocode/application/restore', body),
    status: (body: { id: string; expectedRevision: number; reason: string; status: AC.ApplicationStatus }) =>
      client.post<AC.ApplicationDetail>('/nocode/application/status', body),
    /** 读取失败由调用方降级处理（整列不显示），不弹全局错误。 */
    objectFollows: (id: string) =>
      client.get<AC.ObjectFollow[]>('/nocode/application/object-follow', { params: { id }, quiet: true }),
    setObjectFollow: (body: { applicationId: string; objectId: string; enabled: boolean; expectedRevision: number }) =>
      client.post<AC.FollowRun>('/nocode/application/object-follow', body),
    runObjectFollow: (body: { applicationId: string; objectId: string }) =>
      client.post<AC.FollowRun>('/nocode/application/object-follow/run', body),
    /** 按日期自动执行的最近执行结果。读取失败由调用方降级处理（整列不显示），不弹全局错误。 */
    dateTriggerStatus: (id: string) =>
      client.get<import('@/types/nocode/automation').DateTriggerStatus[]>('/nocode/application/date-trigger/status', {
        params: { id },
        quiet: true
      }),
    /** 立即按今天执行一条按日期自动执行的业务动作：逐条保存，耗时随记录数增长，超时放到 5 分钟。 */
    runDateTrigger: (body: { id: string; resourceId: string }) =>
      client.post<import('@/types/nocode/automation').DateTriggerResult>('/nocode/application/date-trigger/run', body, {
        timeout: 300000
      }),
    /** 草稿里的引用可能还没保存；服务端按版本号与校验和核对。读取失败由调用方降级处理。 */
    readableObjects: (body: { applicationId: string; objects: AC.ObjectReference[] }) =>
      client.post<AC.ReadableObject[]>('/nocode/application/readable-objects', body, { quiet: true }),
    objectVersion: (id: string, versionNo?: number) =>
      client.get<AC.PublishedObject>('/nocode/application/object-version', { params: { id, versionNo } }),
    /** 数据联动「来源变化时自动更新」：总览、预告、回填（第一期契约第 6 章）。失败由调用处就地提示，不弹全局错误。 */
    linkageOverview: (applicationId: string, basis: LS.LinkageSyncBasis) =>
      client.get<LS.LinkageSyncOverview>('/nocode/application/linkage-sync/overview', {
        params: { applicationId, basis },
        quiet: true
      }),
    /**
     * 预告、回填的一页要逐条求值（回填还要逐条保存），耗时随每页条数与数据量增长，不能用默认的 10 秒请求超时：
     * 超时后界面报失败，而服务端那一页其实还在跑。与保存、导入等长耗时接口同样放到 5 分钟。
     */
    linkagePreview: (body: LS.LinkagePreviewRequest) =>
      client.post<LS.LinkagePreviewPage>('/nocode/application/linkage-sync/preview', body, {
        quiet: true,
        timeout: 300000
      }),
    linkageBackfill: (body: LS.LinkageBackfillRequest) =>
      client.post<LS.LinkageBackfillPage>('/nocode/application/linkage-sync/backfill', body, {
        quiet: true,
        timeout: 300000
      })
  }
}
export type ApplicationApi = ReturnType<typeof createApplicationApi>
