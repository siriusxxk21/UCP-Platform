import type { DynamicSearchCondition } from '@/components/os-table-page/types'
import type { RecordModel } from './runtime'

/** 数据维护固定使用当前已发布结构，不接受结构草稿或应用成员授权。 */
export interface ObjectDataModel {
  versionNo: number
  checksum: string
  model: RecordModel
  /** 当前物理列的数据库类型，__id 为主表主键；缺失时不能按业务类型推断。 */
  columnTypes: Record<string, string>
  readonlyReasons: Record<string, string>
  createRestriction: string | null
}

export interface ObjectDataQuery {
  objectId: string
  pageNo: number
  pageSize: number
  search?: string
  equal?: Record<string, unknown>
  sortFieldId?: string
  descending?: boolean
  conditions?: DynamicSearchCondition | null
  recordIds?: string[]
}

export interface ObjectDataSave {
  objectId: string
  versionNo: number
  checksum: string
  id: string | null
  expectedRevision: string | null
  values: Record<string, unknown>
  requestKey: string
}

export interface ObjectDataDelete {
  objectId: string
  versionNo: number
  checksum: string
  id: string
  expectedRevision: string
  impactToken?: string | null
}

export interface ObjectDataDeletePreview {
  impactToken: string
  allowed: boolean
  recordTitle: string
  impacts: Array<{
    objectId: string
    objectName: string
    recordId: string
    recordTitle: string
    relationName: string
    action: 'BLOCK' | 'CLEAR_REFERENCE' | 'DELETE'
    message: string
  }>
  message: string
}

/** 整列维护仅接受已发布结构和服务器预检凭据，不携带当前分页或筛选条件。 */
export interface ObjectDataClearColumn {
  objectId: string
  versionNo: number
  checksum: string
  detailId?: string
  fieldId: string
  impactToken?: string
}

export interface ObjectDataClearColumnPreview {
  allowed: boolean
  objectName: string
  fieldName: string
  detailName?: string | null
  columnType: string
  versionNo: number
  checksum: string
  activeRows: number
  deletedRows: number
  blockers: string[]
  message: string
  impactToken?: string | null
}

export interface ObjectDataClearColumnResult {
  clearedActiveRows: number
  clearedDeletedRows: number
}
