import type { DocumentExpression } from './document-policy'
import type { Aggregate, BusinessRow, SaveRecord } from './runtime'
import type { PublishedDefinition } from './application'

export type HandlingMode = 'DIRECT' | 'APPROVAL' | 'CONDITIONAL'
export interface HandlingRule {
  mode: HandlingMode
  processDefinitionId?: string | null
  condition?: DocumentExpression | null
  variables: Record<string, string>
}
export interface HandlingPolicy {
  create?: HandlingRule | null
  update?: HandlingRule | null
}
export type HandlingState = 'PENDING' | 'APPLY_PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED' | 'APPLY_FAILED'
export const handlingStateLabels: Record<HandlingState, string> = {
  PENDING: '审批中',
  APPLY_PENDING: '审批通过，待生效',
  APPROVED: '已生效',
  REJECTED: '已驳回',
  CANCELED: '已撤回',
  APPLY_FAILED: '审批通过，生效失败'
}
export interface HandlingRequest {
  id: string
  revision: number
  status: HandlingState
  applicationId: string
  applicationName: string
  objectId: string
  objectName: string
  entryId: string | null
  recordId: string | null
  operation: 'CREATE' | 'UPDATE'
  name: string
  processInstanceId: string
  processDefinitionId: string
  submissionId: string
  submittedAt: string
  updatedAt: string
  error: string | null
}
export interface HandlingResult {
  outcome: 'EFFECTIVE' | 'SUBMITTED' | 'REJECTED' | 'CANCELED' | 'APPLY_FAILED'
  result: Aggregate | null
  request: HandlingRequest | null
}
export interface HandlingDetail {
  request: HandlingRequest
  definition: PublishedDefinition
  material: {
    id: string
    values: Record<string, unknown>
    details: Record<string, BusinessRow[]>
    displayValues: Record<string, string>
    handling: { intent: SaveRecord; before: Aggregate | null; relations?: Record<string, string[]> }
  }
}
export interface HandlingReopen {
  model: import('./runtime').RecordModel
  form: import('./application-ui').FormConfig | null
  initial: Aggregate
  entry: import('./task-entry').TaskEntryContext | null
  formId: string | null
}
