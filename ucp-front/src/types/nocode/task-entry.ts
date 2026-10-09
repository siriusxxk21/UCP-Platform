import type { ApplicationMember, ObjectGrant } from './authorization'
import type { ApplicationResource } from './application'
import type { RecordModel } from './runtime'

export const TaskEntryMode = { LIST: 'LIST', FORM: 'FORM' } as const
export type TaskEntryMode = (typeof TaskEntryMode)[keyof typeof TaskEntryMode]
export interface TaskEntryConfig {
  objectId: string
  viewId: string | null
  formId: string | null
  mode: TaskEntryMode
  category: string
  description: string | null
  icon: string | null
  sortOrder: number
  limits: ObjectGrant[]
}
export interface TaskEntryLocator {
  applicationId: string
  entryId: string
  version?: number
}
export interface TaskEntryCard extends TaskEntryLocator {
  applicationName: string
  name: string
  category: string
  description: string | null
  icon: string | null
  mode: TaskEntryMode
  sortOrder: number
  version: number
}
export interface TaskEntryContext {
  entry: TaskEntryCard
  config: TaskEntryConfig
  model: RecordModel
  resources: ApplicationResource[]
}
export interface TaskEntryPolicy {
  revision: number
  enabled: boolean
  members: ApplicationMember[]
}
export interface TaskDraftRef {
  id: string
  revision: number
}
export interface TaskDraftItem {
  id: string
  entry: TaskEntryLocator | null
  entryName: string
  applicationName: string
  updatedAt: number
  available: boolean
  blockedReason: string | null
}
export interface TaskEntryActivity {
  applicationId: string
  entryId: string
  objectId: string
  recordId: string
  recordName: string
  id: string
  time: string
  operation: 'CREATE' | 'UPDATE' | 'DELETE'
  entryName: string
  applicationName: string
}
