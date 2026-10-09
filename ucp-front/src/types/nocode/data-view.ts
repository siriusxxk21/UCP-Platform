import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import type { ObjectField } from './object'
import type { FieldOptions } from './data-center'
import type { RecordModel } from './runtime'
import type { UiNode } from './application-ui'

export interface DataViewSection {
  id: string
  name: string
  detailId: string | null
  objectId: string | null
  viewId: string | null
  binding: NonNullable<UiNode['binding']> | null
  fieldIds: string[]
  conditions: DynamicSearchCondition | null
  pageSize: number
  showTable: boolean
}
export interface DataViewColumn {
  id: string
  name: string
  sectionId: string
  fieldId: string | null
  kind: 'LOOKUP' | 'DETAIL' | 'COUNT' | 'SUM' | 'MIN' | 'MAX'
  type?: string | null
}
export interface DataViewComposition {
  grain: 'ROOT' | 'DETAIL'
  detailId: string | null
  sections: DataViewSection[]
  columns: DataViewColumn[]
}
export interface ChildFilter {
  sectionId: string
  search?: string
  equal?: Record<string, unknown>
  conditions?: DynamicSearchCondition | null
  requireMatch: boolean
}
export interface ChildQuery {
  applicationId: string
  objectId: string
  viewId: string
  sectionId: string
  recordId: string
  pageNo: number
  pageSize: number
  search?: string
  equal?: Record<string, unknown>
  conditions?: DynamicSearchCondition | null
  sortFieldId?: string
  descending: boolean
}
export interface DataViewModel {
  composition: DataViewComposition | null
  fields: ObjectField[]
  fieldOptions: Record<string, FieldOptions>
  sections: Record<
    string,
    { fields: ObjectField[]; fieldOptions: Record<string, FieldOptions>; recordModel: RecordModel | null }
  >
}
