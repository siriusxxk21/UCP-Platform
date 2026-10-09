import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/data-center'
import type { SaveRecord, RelatedFormQuery, RelatedFormResult } from '@/types/nocode/runtime'
import type { HandlingResult } from '@/types/nocode/handling'
import type { FormFillQuery } from '@/types/nocode/application-ui'
import type { SelectionQuery, SelectionResult } from '@/types/nocode/selection'
import type * as T from '@/types/nocode/task-work-entries'
/** 执行任务业务列表的薄适配，表单仍使用公共 RecordEditor。 */
export function createTaskWorkEntriesApi(client: NocodeHttpClient) {
  const root = '/nocode/tasks/entries'
  return {
    entryHandlingLocation: (id: string) =>
      client.post<{ taskId: string; entryKey: string; contributionId: string } | null>(`${root}/handling-location`, {
        id
      }),
    entryList: (id: string) => client.post<T.TaskWorkEntry[]>(`${root}/list`, { id }),
    entryHistoryPage: (query: T.TaskWorkHistoryQuery) =>
      client.post<Page<T.TaskWorkHistoryRow>>(`${root}/history-page`, query),
    entryHistoryDetail: (body: { taskId: string; entryKey?: string | null; contributionId: string }) =>
      client.post<T.TaskWorkHistoryDetail>(`${root}/history-detail`, body),
    entryDelete: (body: {
      taskId: string
      entryKey: string
      recordId: string
      expectedRevision: string
      requestKey: string
    }) => client.post<boolean>(`${root}/delete`, body),
    entryPage: (query: {
      taskId: string
      entryKey: string
      all: boolean
      onlyMine: boolean
      pageNo: number
      pageSize: number
      search: string
    }) => client.post<Page<T.TaskWorkItem>>(`${root}/page`, query),
    entryForm: (target: T.TaskWorkFormTarget) => client.post<T.TaskWorkFormContext>(`${root}/form`, target),
    entryReceipt: (target: T.TaskWorkFormTarget, requestKey: string) =>
      client.post<{ contributionId: string; handling: HandlingResult } | null>(`${root}/receipt`, {
        taskId: target.taskId,
        entryKey: target.entryKey,
        requestKey
      }),
    entrySave: (target: T.TaskWorkFormTarget, record: SaveRecord) =>
      client.post<{ contributionId: string; handling: HandlingResult }>(`${root}/save`, {
        taskId: target.taskId,
        entryKey: target.entryKey,
        contributionId: target.contributionId,
        record
      }),
    entryLink: (taskId: string, entryKey: string, recordId: string, requestKey: string) =>
      client.post<{ contributionId: string; handling: HandlingResult }>(`${root}/link`, {
        taskId,
        entryKey,
        recordId,
        requestKey
      }),
    entryMaterials: (id: string) => client.post<T.TaskWorkMaterial[]>(`${root}/materials`, { id }),
    entrySelection: (target: T.TaskWorkFormTarget, query: SelectionQuery) =>
      client.post<SelectionResult>(`${root}/selection`, { target, query }),
    entryFill: (target: T.TaskWorkFormTarget, query: FormFillQuery) =>
      client.post<Record<string, unknown>>(`${root}/fill`, { target, query }),
    entryFieldRules: (
      target: T.TaskWorkFormTarget,
      query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery
    ) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(`${root}/field-rules`, {
        target,
        query
      }),
    entryRelatedFieldRules: (
      target: T.TaskWorkFormTarget,
      context: RelatedFormQuery,
      query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery
    ) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(`${root}/related-field-rules`, {
        target,
        query: { context, query }
      }),
    entryRelated: (target: T.TaskWorkFormTarget, query: RelatedFormQuery) =>
      client.post<RelatedFormResult>(`${root}/related`, { target, query }),
    entryRelatedSelection: (target: T.TaskWorkFormTarget, context: RelatedFormQuery, query: SelectionQuery) =>
      client.post<SelectionResult>(`${root}/related-selection`, { target, query: { context, query } }),
    entryRelatedFill: (target: T.TaskWorkFormTarget, context: RelatedFormQuery, query: FormFillQuery) =>
      client.post<Record<string, unknown>>(`${root}/related-fill`, { target, query: { context, query } })
  }
}
