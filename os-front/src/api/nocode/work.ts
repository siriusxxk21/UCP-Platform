import type { NocodeHttpClient } from './object'
import type * as W from '@/types/nocode/work'
import type { SelectionQuery, SelectionResult } from '@/types/nocode/selection'

/** 复用底座认证和统一响应；个人归属及权限始终由服务端计算。 */
export function createWorkApi(client: NocodeHttpClient) {
  return {
    saveDraft: (body: W.SaveWorkDraft) => client.post<W.WorkDraft>('/nocode/work-draft/save', body),
    context: (id: string) => client.get<W.WorkDraftContext>('/nocode/work-draft/context', { params: { id } }),
    page: (body: { applicationId: string; state: W.WorkDraftState; before: W.WorkCursor | null; limit: number }) =>
      client.post<W.WorkPage>('/nocode/work-draft/page', body),
    submit: (body: W.SubmitWorkDraft) =>
      client.post<W.WorkSubmission>('/nocode/work-draft/submit', body, { timeout: 300000 }),
    selection: (draftId: string, query: SelectionQuery) =>
      client.post<SelectionResult>('/nocode/work-draft/selection', { draftId, query })
  }
}
export type WorkApi = ReturnType<typeof createWorkApi>
