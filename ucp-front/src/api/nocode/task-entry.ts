import type { NocodeHttpClient } from './object'
import type { TaskEntryContext, TaskEntryLocator, TaskDraftRef, TaskEntryCard } from '@/types/nocode/task-entry'
import type { Aggregate, RecordQuery, SaveRecord, SaveReceipt, BusinessRow } from '@/types/nocode/runtime'
import type { Page } from '@/types/nocode/data-center'
import type { SelectionQuery, SelectionResult } from '@/types/nocode/selection'
import type { WorkDraft } from '@/types/nocode/work'

/** 专用入口端点没有普通应用 API 回退路径；身份及权限只由后端解析。 */
export function createTaskEntryApi(client: NocodeHttpClient) {
  const root = '/nocode/task-entry'
  return {
    relatedSelection: (
      entry: TaskEntryLocator,
      context: import('@/types/nocode/runtime').RelatedFormQuery,
      query: SelectionQuery
    ) => client.post<SelectionResult>(`${root}/related-selection`, { entry, request: { context, query } }),
    relatedFill: (
      entry: TaskEntryLocator,
      context: import('@/types/nocode/runtime').RelatedFormQuery,
      query: import('@/types/nocode/application-ui').FormFillQuery
    ) => client.post<Record<string, unknown>>(`${root}/related-fill`, { entry, request: { context, query } }),
    relatedForm: (entry: TaskEntryLocator, query: import('@/types/nocode/runtime').RelatedFormQuery) =>
      client.post<import('@/types/nocode/runtime').RelatedFormResult>(`${root}/related-form`, { entry, query }),
    viewModel: (entry: TaskEntryLocator, objectId: string, viewId: string) =>
      client.post<import('@/types/nocode/data-view').DataViewModel>(`${root}/view-model`, { entry, objectId, viewId }),
    viewChildren: (entry: TaskEntryLocator, query: import('@/types/nocode/data-view').ChildQuery) =>
      client.post<Page<BusinessRow>>(`${root}/view-children`, { entry, query }),
    formFill: (entry: TaskEntryLocator, query: import('@/types/nocode/application-ui').FormFillQuery) =>
      client.post<Record<string, unknown>>(`${root}/form-fill`, { entry, query }, { quiet: true }),
    fieldRules: (entry: TaskEntryLocator, query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(
        `${root}/field-rules`,
        { entry, query },
        { quiet: true }
      ),
    mine: () => client.get<TaskEntryCard[]>(`${root}/mine`),
    context: (entry: TaskEntryLocator) => client.post<TaskEntryContext>(`${root}/context`, entry),
    page: (entry: TaskEntryLocator, query: RecordQuery) =>
      client.post<Page<BusinessRow>>(`${root}/page`, { entry, query }),
    get: (entry: TaskEntryLocator, recordId: string) => client.post<Aggregate>(`${root}/get`, { entry, recordId }),
    save: (entry: TaskEntryLocator, record: SaveRecord, draft?: TaskDraftRef | null) =>
      client.post<Aggregate>(
        `${root}/save`,
        { entry, record, ...(draft ? { draft } : {}) },
        { quiet: true, timeout: 300000 }
      ),
    submit: (entry: TaskEntryLocator, record: SaveRecord, draft?: TaskDraftRef | null) =>
      client.post<import('@/types/nocode/handling').HandlingResult>(
        `${root}/submit`,
        { entry, record, ...(draft ? { draft } : {}) },
        { quiet: true, timeout: 300000 }
      ),
    submitReceipt: (entry: TaskEntryLocator, requestKey: string) =>
      client.post<import('@/types/nocode/handling').HandlingResult | null>(
        `${root}/submit-receipt`,
        { entry, requestKey },
        { quiet: true }
      ),
    draft: (entry: TaskEntryLocator) => client.post<WorkDraft | null>(`${root}/draft`, entry),
    saveDraft: (entry: TaskEntryLocator, record: SaveRecord, draft: TaskDraftRef | null) =>
      client.post<WorkDraft>(`${root}/draft/save`, { entry, record, draft }),
    delete: (entry: TaskEntryLocator, recordId: string, expectedRevision: string) =>
      client.post<boolean>(`${root}/delete`, { entry, recordId, expectedRevision }, { timeout: 300000 }),
    selection: (entry: TaskEntryLocator, query: SelectionQuery) =>
      client.post<SelectionResult>(`${root}/selection`, { entry, query }),
    receipt: (entry: TaskEntryLocator, requestKey: string) =>
      client.post<SaveReceipt>(`${root}/receipt`, { entry, requestKey }, { quiet: true })
  }
}
export type TaskEntryApi = ReturnType<typeof createTaskEntryApi>
