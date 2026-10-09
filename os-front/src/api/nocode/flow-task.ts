import type { NocodeHttpClient } from './object'
import type { WorkDraft, WorkDraftContext, WorkSubmission, SaveWorkDraft, SubmitWorkDraft } from '@/types/nocode/work'
import type { SelectionQuery, SelectionResult } from '@/types/nocode/selection'

export interface FlowWorkspace {
  processInstanceId: string
  nodeId: string
  work: WorkDraftContext
}

/** 复用表单组件的接口形状；业务资源和来源只由服务端已部署节点确定。 */
export function createFlowTaskApi(client: NocodeHttpClient, taskId: string) {
  const open = () => client.post<FlowWorkspace>('/nocode/flow-task/open', { taskId })
  return {
    open,
    context: async (_draftId: string) => (await open()).work,
    saveDraft: (body: SaveWorkDraft) =>
      client.post<WorkDraft>('/nocode/flow-task/draft/save', {
        taskId,
        draftId: body.id,
        expectedRevision: body.expectedRevision,
        values: body.values
      }),
    submit: (body: SubmitWorkDraft) =>
      client.post<WorkSubmission>('/nocode/flow-task/submit', {
        taskId,
        ...body,
        reason: '提交业务材料'
      }),
    selection: (_draftId: string, query: SelectionQuery) =>
      client.post<SelectionResult>('/nocode/flow-task/selection', { taskId, query })
  }
}
