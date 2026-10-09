import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/data-center'
import type * as R from '@/types/nocode/runtime'
import type { ApplicationRow } from '@/types/nocode/application'
import type { ReportQuery, ReportResult } from '@/types/nocode/report'
/** 后端业务码（os-nocode-api 的 NocodeErrorCodes）：记录不存在或不可访问 / 记录已被修改（修订号不符）。 */
export const RECORD_NOT_FOUND = 1_050_000_002
export const RECORD_CONFLICT = 1_050_000_004
/** 应用运行请求统一复用底座客户端；身份和授权只能由服务端确定。 */
export function createRuntimeApi(client: NocodeHttpClient) {
  return {
    relatedForm: (query: R.RelatedFormQuery) => client.post<R.RelatedFormResult>('/nocode/runtime/related-form', query),
    relatedSelection: (context: R.RelatedFormQuery, query: import('@/types/nocode/selection').SelectionQuery) =>
      client.post<import('@/types/nocode/selection').SelectionResult>('/nocode/runtime/related-selection', {
        context,
        query
      }),
    relatedFill: (context: R.RelatedFormQuery, query: import('@/types/nocode/application-ui').FormFillQuery) =>
      client.post<Record<string, unknown>>('/nocode/runtime/related-fill', { context, query }),
    viewModel: (applicationId: string, objectId: string, viewId: string) =>
      client.get<import('@/types/nocode/data-view').DataViewModel>('/nocode/runtime/view-model', {
        params: { applicationId, objectId, viewId }
      }),
    viewChildren: (query: import('@/types/nocode/data-view').ChildQuery) =>
      client.post<Page<R.BusinessRow>>('/nocode/runtime/view-children', query),
    formFill: (query: import('@/types/nocode/application-ui').FormFillQuery) =>
      client.post<Record<string, unknown>>('/nocode/runtime/form-fill', query, { quiet: true }),
    /** 数据联动与公式默认值求值：规则只从服务端固定版本读取，请求只带当前值。 */
    evaluateFieldRules: (query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(
        '/nocode/runtime/field-rules/evaluate',
        query,
        { quiet: true }
      ),
    history: (query: import('@/types/nocode/record-history').HistoryQuery) =>
      client.post<import('@/types/nocode/record-history').HistoryResult>('/nocode/record-history/query', query, {
        timeout: 30000
      }),
    historyPage: (query: import('@/types/nocode/record-history').HistoryPageQuery) =>
      client.post<import('@/types/nocode/record-history').HistoryPage>('/nocode/record-history/page', query, {
        timeout: 30000
      }),
    historyDetail: (query: import('@/types/nocode/record-history').HistoryDetailQuery) =>
      client.post<import('@/types/nocode/record-history').HistoryDetail>('/nocode/record-history/detail', query, {
        timeout: 30000
      }),
    /** 设计引擎区块：按当前用户对该记录的读/写权签发短期令牌（laneEG）。 */
    engineToken: (query: { applicationId: string; pageId: string; nodeId: string; recordId: string }) =>
      client.post<{ token: string; expiresAt: number; engineUrl: string; writable: boolean; project: string }>(
        '/nocode/runtime/engine/token',
        query
      ),
    selection: (query: import('@/types/nocode/selection').SelectionQuery) =>
      client.post<import('@/types/nocode/selection').SelectionResult>('/nocode/runtime/selection', query),
    /** options.quiet：静默重取（推送触发、回到前台补取）用，失败不弹全局错误通知；用户主动操作不传。下同。 */
    report: (query: ReportQuery, options?: { quiet?: boolean }) =>
      client.post<ReportResult>('/nocode/runtime/report', query, options),
    reportDetails: (query: ReportQuery, options?: { quiet?: boolean }) =>
      client.post<Page<R.BusinessRow>>('/nocode/runtime/report-details', query, options),
    reportExport: (query: ReportQuery) =>
      client.post<Blob>('/nocode/runtime/report-export', query, { responseType: 'blob', timeout: 60000 }),
    export: (query: R.RecordQuery) =>
      client.post<Blob>('/nocode/runtime/export', query, { responseType: 'blob', timeout: 60000 }),
    template: (applicationId: string, objectId: string) =>
      client.get<Blob>('/nocode/runtime/import-template', {
        params: { applicationId, objectId },
        responseType: 'blob'
      }),
    import: (applicationId: string, objectId: string, file: File, context?: R.RecordContext) => {
      const body = new FormData()
      body.append('applicationId', applicationId)
      body.append('objectId', objectId)
      body.append('file', file)
      if (context) for (const [key, value] of Object.entries(context)) body.append(key, value)
      return client.post<number>('/nocode/runtime/import', body, {
        timeout: 300000,
        headers: { 'Content-Type': 'multipart/form-data' }
      })
    },
    action: (body: {
      applicationId: string
      objectId: string
      actionId: string
      recordId: string
      expectedRevision: string
    }) => client.post<R.Aggregate>('/nocode/runtime/action', body, { timeout: 300000 }),
    processRecord: (businessKey: string) =>
      client.get<{ applicationId: string; objectId: string; recordId: string }>('/nocode/runtime/process-record', {
        params: { businessKey }
      }),
    mine: () => client.get<ApplicationRow[]>('/nocode/runtime/mine'),
    application: (id: string) => client.get<R.RuntimeApplication>('/nocode/runtime/application', { params: { id } }),
    model: (applicationId: string, objectId: string) =>
      client.get<R.RecordModel>('/nocode/runtime/model', { params: { applicationId, objectId } }),
    /** 带 reportDrill 时响应额外带 drillTotal（仅按下钻条件命中的记录数）。 */
    page: (body: R.RecordQuery, options?: { quiet?: boolean }) =>
      client.post<R.RecordPage>('/nocode/runtime/page', body, options),
    /** quiet：静默重取用，失败不弹全局错误通知（记录被删时由调用方自己提示）。 */
    get: (applicationId: string, objectId: string, id: string, options?: { quiet?: boolean }) =>
      client.get<R.Aggregate>('/nocode/runtime/get', { params: { applicationId, objectId, id }, ...options }),
    save: (body: R.SaveRecord) =>
      client.post<R.Aggregate>('/nocode/runtime/save', body, { quiet: true, timeout: 300000 }),
    submit: (body: R.SaveRecord) =>
      client.post<import('@/types/nocode/handling').HandlingResult>('/nocode/handling/submit', body, {
        quiet: true,
        timeout: 300000
      }),
    submitReceipt: (applicationId: string, objectId: string, requestKey: string) =>
      client.post<import('@/types/nocode/handling').HandlingResult | null>(
        '/nocode/handling/receipt',
        { applicationId, objectId, requestKey },
        { quiet: true }
      ),
    receipt: (applicationId: string, objectId: string, requestKey: string) =>
      client.get<R.SaveReceipt>('/nocode/runtime/save-receipt', {
        params: { applicationId, objectId, requestKey },
        quiet: true
      }),
    /** 「记录已被修改」由列表自己处理（取最新修订号再删一次，仍失败则在列表上方提示），不另弹全局错误通知。 */
    delete: (body: { applicationId: string; objectId: string; id: string; expectedRevision: string }) =>
      client.post<boolean>('/nocode/runtime/delete', body, { timeout: 300000, quietCodes: [RECORD_CONFLICT] })
  }
}
export type RuntimeApi = ReturnType<typeof createRuntimeApi>
