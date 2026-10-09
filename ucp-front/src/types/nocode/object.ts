import type { FieldType } from './enums'
export type { FieldType } from './enums'

export interface ObjectField {
  key: string
  id: string | null
  code: string
  name: string
  type: FieldType
  length: number | null
  precision: number | null
  scale: number | null
  required: boolean
  unique: boolean
  sort: number
}
export interface ObjectDraft {
  category?: string
  id: string
  objectCode: string
  objectName: string
  description: string | null
  tableName: string
  titleFieldId: string
  state: string
  lockVersion: number
  versionNo: number
  updatedAt: string
  fields: ObjectField[]
}
export interface SaveObjectDraft {
  category?: string
  id: string | null
  expectedLockVersion: number | null
  objectCode: string
  objectName: string
  description: string | null
  tableName: string
  titleFieldKey: string
  fields: ObjectField[]
  removedFieldIds: string[]
  titleTemplate?: string | null
}
export interface ObjectSummary {
  id: string
  objectCode: string
  objectName: string
  tableName: string
  state: string
  lockVersion: number
  fieldCount: number
  updatedAt: string
}
export interface ObjectQuery {
  pageNo: number
  pageSize: number
  name?: string
  code?: string
}
export interface ObjectPage {
  list: ObjectSummary[]
  total: number
}
