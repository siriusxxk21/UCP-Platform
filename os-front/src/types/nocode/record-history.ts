export type HistoryValues = Record<string, unknown>
export interface HistoryField {
  id: string
  name: string
}
export interface HistoryDetailChange {
  id: string
  name: string
  fields: HistoryField[]
  before: Record<string, HistoryValues>
  after: Record<string, HistoryValues>
  beforeOrder: string[]
  afterOrder: string[]
  beforeKnown: boolean
  afterKnown: boolean
}
export interface HistoryChange {
  source?: {
    kind: string
    applicationId: string
    entryId?: string
    ruleId?: string
    sourceObjectId?: string
    sourceRecordId?: string
    version: number
    /** LINKAGE 时是本次被系统写入的字段名，多个用「、」连接。 */
    name: string
    relatedUpdate?: boolean
    /** LINKAGE：本次被系统写入的字段 ID。 */
    fieldIds?: string[]
    /** LINKAGE：只在存量回填时出现，此时没有 sourceObjectId / sourceRecordId。 */
    backfill?: boolean
    /** DATE_TRIGGER：按日期自动执行对应的业务日 yyyy-MM-dd。 */
    businessDate?: string
    /** DATE_TRIGGER：由「立即按今天执行」触发。 */
    manual?: boolean
  } | null
  id: string
  operation: 'CREATE' | 'UPDATE' | 'DELETE'
  time: string
  employeeId: string
  employeeName: string
  before: HistoryValues | null
  after: HistoryValues | null
  fields: HistoryField[]
  details?: HistoryDetailChange[]
}
export interface HistoryRow {
  id: string
  startValues: HistoryValues | null
  endValues: HistoryValues | null
  values: HistoryValues
  changedFields: string[]
  changes: HistoryChange[]
  changeCount?: number
  deleted: boolean
  createdInRange: boolean
  restored: boolean
}
export interface HistoryTable {
  objectId: string
  name: string
  applicationIds: string[]
  applicationNames: string[]
  coveredFrom: string
  complete: boolean
  fields: HistoryField[]
  rows: HistoryRow[]
}
export interface HistoryQuery {
  start: string
  end?: string
  applicationId?: string
  employeeId?: string
}
export interface HistoryResult {
  start: string
  end: string
  visibility: string
  tables: HistoryTableSummary[]
}

export interface HistoryCounts {
  records: number
  operations: number
  create: number
  update: number
  delete: number
  employees: number
}
export interface HistoryTableSummary extends Omit<HistoryTable, 'fields' | 'rows'> {
  counts: HistoryCounts
  employees: { id: string; name: string; counts: HistoryCounts }[]
}
export interface HistoryPageQuery {
  query: HistoryQuery
  visibility: string
  objectId: string
  changesOnly: boolean
  pageNo: number
  pageSize: number
}
export interface HistoryPage {
  table: HistoryTable
  total: number
  pageNo: number
  pageSize: number
}
export interface HistoryDetailQuery {
  query: HistoryQuery
  visibility: string
  objectId: string
  recordId: string
}
export interface HistoryDetail {
  row: HistoryRow
  fields: HistoryField[]
}
