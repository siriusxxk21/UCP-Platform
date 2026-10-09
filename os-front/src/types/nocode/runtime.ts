import type { ApplicationRow, ApplicationDefinition, PublishedDefinition } from './application'
export interface RuntimeApplication {
  application: ApplicationRow
  versionNo: number
  checksum: string
  definition: ApplicationDefinition
  /** 固定版本与对象最新结构的差异，只提示不阻断运行。 */
  warnings: string[]
}
export interface TableModel {
  writable: boolean
  generatedKey: boolean
  keyFieldId: string | null
  keyType: string
  writeFields?: string[]
  /** 持续维护规则负责写入的字段，业务表单不可手动覆盖。 */
  managedFieldIds?: string[]
}
export interface RecordPermissions {
  actions: string[]
  readFields: string[]
  writeFields: string[]
  readDetails: string[]
  writeDetails: string[]
  readRelations?: string[]
  writeRelations?: string[]
}
export interface RecordModel extends TableModel {
  orderedStates?: import('./ordered-calculation').OrderedCalculationState[]
  object: PublishedDefinition
  details: Record<string, TableModel>
  permissions: RecordPermissions
}
export interface BusinessRow {
  parentId?: string | null
  clientRowKey?: string | null
  displayValues?: Record<string, string>
  id: string | null
  revision: string | null
  values: Record<string, unknown>
  permissions?: RecordPermissions
}
export interface Aggregate {
  record: BusinessRow
  details: Record<string, BusinessRow[]>
  relations?: Record<string, string[]>
  processes?: Array<{
    businessKey: string
    name: string
    instanceId: string
    status: string
    createTime: string
    endTime?: string
  }>
}
export interface RecordQuery {
  childFilters?: import('./data-view').ChildFilter[]
  conditions?: import('@/components/os-table-page/types').DynamicSearchCondition | null
  context?: RecordContext
  viewId?: string
  applicationId: string
  objectId: string
  pageNo: number
  pageSize: number
  search?: string
  equal?: Record<string, unknown>
  sortFieldId?: string
  descending: boolean
  /** 统计下钻：与该数据视图自身条件取交集。 */
  reportDrill?: import('./report').ReportDrill
  dashboardDrill?: import('./application-dashboard-runtime').ApplicationDashboardDrill
}
/** 下钻列表返回原范围计数；组合明细行与看板主记录粒度不同时显式标记。 */
export type RecordPage = import('./data-center').Page<BusinessRow> & {
  drillTotal?: number | null
  drillDifferentGrain?: boolean
}
export interface SaveRecord {
  relatedRecords?: Record<string, RelatedFormRow[]>
  requestKey?: string
  actionCode?: string
  formId?: string
  context?: RecordContext
  applicationId: string
  objectId: string
  id: string | null
  expectedRevision: string | null
  values: Record<string, unknown>
  details?: Record<string, BusinessRow[]>
  relations?: Record<string, string[]>
}
export interface RelatedFormRow {
  id: string | null
  expectedRevision: string | null
  values: Record<string, unknown>
  details?: Record<string, BusinessRow[]>
  unlink?: boolean
}
export interface RelatedFormQuery {
  applicationId: string
  objectId: string
  formId: string
  bindingId: string
  recordId?: string | null
  selectedId?: string | null
  search?: string
}
export interface RelatedFormResult {
  model: RecordModel
  form: import('./application-ui').FormConfig
  records: Aggregate[]
  multiple: boolean
  linkFieldId: string
  required: boolean
  truncated: boolean
}
/** 只定位页面上下文，关系条件由已发布页面和后端校验决定。 */
export interface RecordContext {
  pageId: string
  nodeId: string
  recordId: string
}

export interface SaveReceipt {
  requestKey: string
  status: 'SUCCEEDED' | 'NOT_FOUND'
  operationId: string | null
  recordId: string | null
  revision: string | null
  policyVersion: string | null
  result: Aggregate | null
}
