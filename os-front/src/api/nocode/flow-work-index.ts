import type { NocodeHttpClient } from './object'
import type { WorkCursor, WorkDraftState } from '@/types/nocode/work'

/** 摘要来自本人来源草稿，服务器逐项复验当前任务资格及固定资源读取权限。 */
export interface FlowWorkItem {
  draftId: string
  taskId: string
  processInstanceId: string
  nodeId: string
  state: WorkDraftState
  formName: string
  objectName: string
  applicationId: string
  applicationVersion: number
  updatedAt: number
  submissionId: string | null
  writable: boolean
  blockedReason: string | null
}

export interface FlowWorkQuery {
  state: WorkDraftState
  before: WorkCursor | null
  limit: number
}

export interface FlowWorkPage {
  items: FlowWorkItem[]
  before: WorkCursor | null
}

export function createFlowWorkIndexApi(client: NocodeHttpClient) {
  return { page: (body: FlowWorkQuery) => client.post<FlowWorkPage>('/nocode/flow-task/work-page', body) }
}

export type FlowWorkIndexApi = ReturnType<typeof createFlowWorkIndexApi>
