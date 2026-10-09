import type { TaskBinding, TaskBusinessRef, TaskFormContext } from './task-center'
import type { BusinessRow, Aggregate } from './runtime'
import type { HistoryField, HistoryValues, HistoryDetailChange } from './record-history'
export type TaskEntryDataMode = 'ROOT_SHARED' | 'INDEPENDENT' | 'SOURCE_SHARED'
export type TaskWorkRuleMode = 'RECORD_ONCE' | 'QUANTITY' | 'CONDITION'
/** 标准工时不是实际计时，按任务节点、办理项、员工和数据去重。 */
export interface TaskWorkRule {
  mode: TaskWorkRuleMode
  minutes: number
  /** 仅本次发起的工时加减；minutes 保留模板基准。 */
  adjustmentMinutes?: number | null
  /** 仅用于预算合计，不代替办理记录中的实际数量；历史缺省不按 1 估算。 */
  plannedQuantity?: number | null
  quantityFieldId?: string | null
  conditionFieldId?: string | null
  conditionValue?: unknown
}
export interface TaskWorkEntryConfig {
  /** 本办理项的数据范围；缺省保留历史总任务策略或 allowAll，不自动扩大权限。 */
  dataScope?: 'GROUP' | 'ALL' | null
  workRule?: TaskWorkRule | null
  key: string
  name: string
  binding: TaskBinding | null
  dataMode: TaskEntryDataMode
  sourceNodeId: string | null
  sourceEntryKey: string | null
  readableFieldIds: string[] | null
  writableFieldIds: string[] | null
  required: boolean
  allowAll: boolean
}
export interface TaskWorkEntry {
  /** 授权失效时保留安全的办理项标题，禁止继续打开业务资源。 */
  unavailableReason?: string | null
  workSummary?: {
    myMinutes: number
    myRecordCount: number
    totalMinutes?: number | null
    totalRecordCount?: number | null
  }
  category?: 'BUSINESS' | 'FEEDBACK'
  effectivePolicy?: 'GROUP' | 'ALL' | null
  canDelete?: boolean
  /** 关联现有可读记录，不代表可以新增或修改业务数据。 */
  canLink?: boolean
  config: TaskWorkEntryConfig
  binding: TaskBusinessRef
  datasetId: string
  inherited: boolean
  canWrite: boolean
  contributionCount: number | null
  submitted: boolean
}
export interface TaskWorkSource {
  taskId: string
  taskTitle: string
  actorId: string | number
  actorName: string
  entryKey: string
  operation: string
  revision: string
  time: string
}
export interface TaskWorkItem {
  id: string
  record: BusinessRow | null
  requestId: string | null
  status: string
  sources: TaskWorkSource[]
}
export interface TaskWorkFormTarget {
  taskId: string
  entryKey: string
  recordId: string | null
  contributionId: string | null
}
export interface TaskWorkMaterial {
  binding?: TaskBusinessRef | null
  model?: import('./runtime').RecordModel | null
  entryKey: string
  name: string
  records: TaskWorkItem[]
  submissions: Array<{ contributionId: string; binding: TaskBusinessRef; record: Aggregate; sources: TaskWorkSource[] }>
}
export type TaskWorkFormContext = TaskFormContext

export type TaskWorkOperation = 'CREATED' | 'UPDATED' | 'DELETED' | 'LINKED'
/** 单次任务办理事实；与当前业务记录、手工反馈分别展示。 */
export interface TaskWorkHistoryRow {
  id: string
  taskId: string
  taskTitle: string
  entryKey: string
  entryName: string
  category: 'BUSINESS' | 'FEEDBACK'
  recordId: string
  recordLabel: string
  operation: TaskWorkOperation
  actorId: string | number
  actorName: string
  time: string
  detailAvailable: boolean
  historyKnown: boolean
}
export interface TaskWorkHistoryQuery {
  taskId: string
  entryKey?: string | null
  onlyCurrentTask: boolean
  recordId?: string | null
  operation?: TaskWorkOperation | null
  search?: string | null
  pageNo: number
  pageSize: number
}
export interface TaskWorkHistoryDetail {
  row: TaskWorkHistoryRow
  fields: HistoryField[]
  before: HistoryValues | null
  after: HistoryValues | null
  details: HistoryDetailChange[]
  beforeKnown: boolean
  afterKnown: boolean
  relatedUpdate: boolean
}
