import type { FormConfig } from './application-ui'
import type { BusinessRow, RecordModel } from './runtime'

export const WorkDraftState = { DRAFT: 'DRAFT', SUBMITTED: 'SUBMITTED' } as const
export type WorkDraftState = (typeof WorkDraftState)[keyof typeof WorkDraftState]
export interface PublishedResourceRef {
  applicationId: string
  applicationVersion: number
  applicationChecksum: string
  resourceId: string
  resourceKind: 'FORM'
}
export interface SaveWorkDraft {
  id: string | null
  expectedRevision: number | null
  resource: PublishedResourceRef
  objectId: string
  recordId: string | null
  baseRecordRevision: string | null
  values: Record<string, unknown>
  details?: Record<string, BusinessRow[]>
  relatedRecords?: Record<string, import('./runtime').RelatedFormRow[]>
}
export interface WorkDraft extends Omit<SaveWorkDraft, 'id' | 'expectedRevision'> {
  id: string
  revision: number
  state: WorkDraftState
  updatedAt: number
}
export interface SubmitWorkDraft {
  draftId: string
  expectedRevision: number
  idempotencyKey: string
}
export interface WorkSubmission {
  id: string
  draftId: string
  resource: PublishedResourceRef
  objectId: string
  recordId: string
  recordRevision: string
  values: Record<string, unknown>
  submittedAt: number
}
export interface WorkDraftContext {
  draft: WorkDraft
  submission: WorkSubmission | null
  formName: string
  form: FormConfig
  model: RecordModel
  currentRecord: BusinessRow | null
  writable: boolean
  blockedReason: string | null
  recordChanged: boolean
}
export interface WorkCursor {
  createdAt: string
  id: string
}
export interface WorkListItem {
  id: string
  state: WorkDraftState
  formName: string
  objectName: string
  applicationVersion: number
  recordId: string | null
  updatedAt: number
}
export interface WorkPage {
  items: WorkListItem[]
  before: WorkCursor | null
}
