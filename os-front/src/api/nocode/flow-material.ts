import type { NocodeHttpClient } from './object'
import type { FormConfig } from '@/types/nocode/application-ui'
import type { RecordModel } from '@/types/nocode/runtime'

export interface FlowMaterialQuery {
  processInstanceId: string
  taskId?: string
}
export interface FlowMaterialItem {
  id: string
  taskId?: string
  nodeId: string
  nodeName: string
  formName: string
  submitterName: string
  submittedAt: string | number
  revision: number
  kind: 'FLOW_FORM' | 'APPLICATION_FORM'
  state: 'CURRENT' | 'HISTORY' | 'UNAVAILABLE'
  required: boolean
  warning?: string
}
export interface FlowMaterialList {
  items: FlowMaterialItem[]
  reviewToken?: string
  reviewRequired: boolean
  blockedReason?: string
}
export interface FlowMaterialDetail {
  item: FlowMaterialItem
  flowForm?: { conf: string; fields: string[]; values: Record<string, unknown> }
  businessForm?: {
    applicationId: string
    recordId: string | null
    form: FormConfig
    model: RecordModel
    values: Record<string, unknown>
    displayValues?: Record<string, string>
  }
}
/** 材料查询依据流程任务授权；不会打开旧任务、创建草稿或获取额外业务权限。 */
export function createFlowMaterialApi(client: NocodeHttpClient) {
  return {
    list: (query: FlowMaterialQuery) => client.post<FlowMaterialList>('/nocode/flow-material/list', query),
    detail: (query: FlowMaterialQuery & { materialId: string }) =>
      client.post<FlowMaterialDetail>('/nocode/flow-material/detail', query)
  }
}
export type FlowMaterialApi = ReturnType<typeof createFlowMaterialApi>
