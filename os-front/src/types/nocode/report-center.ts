import type { ApplicationMember, ObjectGrant, PrincipalKind } from './authorization'
import type { DataScope } from './data-scope'
import type { ReportBucket, ReportMetric, ReportOperation } from './report'

export type DatasetFieldRole = 'DIMENSION' | 'MEASURE'
export type ReportResourceStatus = 'ACTIVE' | 'INACTIVE'
export type DatasetResourceAction = 'VIEW_META' | 'EDIT' | 'PUBLISH' | 'USE' | 'AUTHORIZE_DATA' | 'GRANT' | 'DELETE'
export interface ReportObjectReference {
  objectId: string
  versionNo: number
  checksum: string
}
export interface DatasetSource {
  schemaVersion: 1
  root: ReportObjectReference
  relations: { id: string; parentPath: string[]; relationId: string; target: ReportObjectReference }[]
  fields: { id: string; path: string[]; sourceFieldId: string; name: string; role: DatasetFieldRole }[]
}
export interface DatasetMetric {
  id: string
  name: string
  operation: ReportOperation
  fieldId: string | null
  format?: ReportMetric['format']
  conditions?: DataScope | null
  formula?: ReportMetric['formula']
}
export interface DatasetAnalysis {
  schemaVersion: 1
  metrics: DatasetMetric[]
  fixedConditions: DataScope | null
  fieldFormats: Record<string, NonNullable<ReportMetric['format']>>
  timeZone: string
}
export interface DatasetContent {
  name: string
  description: string
  source: DatasetSource | null
  /** 旧来源快照没有此属性；更新时省略/null 保留已有配置，清空须提交显式空分析配置。 */
  analysis?: DatasetAnalysis | null
}
export interface DatasetSave extends DatasetContent {
  folderId?: string | null
  id: string | null
  expectedRevision: number
}
export interface DatasetDetail {
  folderId: string | null
  id: string
  ownerId: string
  status: ReportResourceStatus
  revision: number
  publishedVersion: number | null
  checksum: string
  modified: boolean
  draft: DatasetContent
}
export interface DatasetRelease {
  datasetId: string
  versionNo: number
  checksum: string
  definition: DatasetContent
  reason: string
  createTime: string
}
export interface DatasetRevision {
  id: string
  expectedRevision: number
  reason: string
}
export interface ReportPageQuery {
  /** 省略查询全部，0 表示未分类，其他 ID 包含子目录。 */
  folderId?: string
  pageNo: number
  pageSize: number
  search?: string
}
export interface ReportObjectItem {
  id: string
  code: string
  name: string
  publishedVersion: number
}
export interface ReportObjectVersion {
  reference: ReportObjectReference
  name: string
  fields: { id: string; code: string; name: string; type: string; measure: boolean }[]
  relations: { id: string; name: string; fieldId: string; targetObjectId: string }[]
}
export interface DatasetAuthorizationTarget {
  id: string
  name: string
  status: ReportResourceStatus
  sources: ReportObjectReference[]
}
export interface DatasetResourceMember {
  principalKind: keyof typeof PrincipalKind
  principalId: string
  actions: DatasetResourceAction[]
}
export interface DatasetResourcePolicy {
  revision: number
  members: DatasetResourceMember[]
}
export interface DatasetObjectCeiling {
  objectId: string
  revision: number
  permission: ObjectGrant | null
  reason: string
}
export interface DatasetDataPolicy {
  revision: number
  members: ApplicationMember[]
}
export interface DatasetPolicyRevision {
  datasetId: string
  expectedRevision: number
  reason: string
}
/** 预览与固定版本二选一；复用指标和临时指标二选一，均不接收操作者、源对象或 SQL。 */
export type DatasetQuery = {
  datasetId: string
  dimensions: { fieldId: string; bucket: ReportBucket }[]
  equal?: Record<string, unknown> | null
  /** 临时筛选不会覆盖固定条件或数据权限。 */
  filters?: DataScope | null
  limit: number
  timeZone?: string | null
} & ({ preview: true; versionNo?: null; checksum?: null } | { preview: false; versionNo: number; checksum: string }) &
  ({ metrics: DatasetMetric[]; metricIds?: never } | { metrics?: null; metricIds?: string[] | null })

/** 引用数量仅用于预检提示；删除请求仍在服务端重新检查。 */
export interface DatasetDeletePreview {
  id: string
  revision: number
  referenceCount: number
  canDelete: boolean
}

/** 数据管理员只读取授权所需的对象结构；失效对象仍可撤销已有上限。 */
export interface DatasetAuthorizationObject {
  id: string
  name: string
  definition: ReportObjectVersion | null
  unavailableReason: string | null
}

export interface ReportFolder {
  id: string
  parentId: string | null
  name: string
  sortNo: number
  revision: number
}
export interface ReportFolderSave {
  id: string | null
  expectedRevision: number
  parentId: string | null
  name: string
  sortNo: number
  reason: string
}

/** 候选字段的自身临时条件由服务端排除；固定条件和权限仍有效。 */
export interface DatasetOptions {
  datasetId: string
  versionNo?: number | null
  checksum?: string | null
  preview: boolean
  fieldId: string
  filters?: DataScope | null
  pageNo: number
  pageSize: number
  search?: string
}
export interface DatasetOptionPage {
  list: { value: string | null; label: string }[]
  total: number
  pageNo: number
  pageSize: number
}
