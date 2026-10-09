import type { Page, ObjectDetail, ObjectRelation, FieldOptions, TableBinding, ObjectSettings } from './data-center'
import type { ObjectField } from './object'
export type { Page }
export const ApplicationStatus = { ACTIVE: 'ACTIVE', DISABLED: 'DISABLED' } as const
export type ApplicationStatus = (typeof ApplicationStatus)[keyof typeof ApplicationStatus]
export const ResourceKind = {
  VIEW: 'VIEW',
  REPORT: 'REPORT',
  REPORT_DASHBOARD: 'REPORT_DASHBOARD',
  FORM: 'FORM',
  PAGE: 'PAGE',
  MENU: 'MENU',
  DICTIONARY: 'DICTIONARY',
  NUMBER_RULE: 'NUMBER_RULE',
  TASK_ENTRY: 'TASK_ENTRY',
  ACTION: 'ACTION',
  AUTOMATION: 'AUTOMATION'
} as const
export type ResourceKind = (typeof ResourceKind)[keyof typeof ResourceKind]
export interface ObjectReference {
  objectId: string
  versionNo: number
  checksum: string
}
export interface ApplicationResource {
  id: string
  kind: ResourceKind
  code: string
  name: string
  config: Record<string, unknown>
}
export interface ApplicationDefinition {
  objects: ObjectReference[]
  resources: ApplicationResource[]
}
export interface ApplicationRow {
  recoveryPending?: boolean
  recoveryNeedsEdit?: boolean
  category?: string
  id: string
  code: string
  name: string
  description: string | null
  icon: string | null
  status: ApplicationStatus
  revision: number
  publishedVersion: number | null
  updateTime: string
}
export interface RecycledApplicationRow extends ApplicationRow {
  deletedAt: string
  deletedBy: string
  deletedByName?: string | null
}
export interface ApplicationDeletePreview {
  application: ApplicationRow
  objectCount: number
  resourceCount: number
  taskEntryCount: number
  blockers: string[]
}
export interface ApplicationRevision {
  id: string
  expectedRevision: number
  reason: string
}
export interface Release {
  versionNo: number
  checksum: string
  reason: string
  creator: string
  createTime: string
}
/** 固定版本与对象当前结构的兼容差异；草稿允许保留，应用发布前必须处理。 */
export interface ObjectIssue {
  objectId: string
  objectName: string
  versionNo: number
  latestVersionNo: number
  messages: string[]
}
/** 发布记录由 releases 分页接口按需查询，详情不再附带全量版本列表。 */
export interface ApplicationDetail {
  application: ApplicationRow
  draft: ApplicationDefinition
  issues: ObjectIssue[]
}
export interface SaveApplication {
  category?: string
  id: string | null
  expectedRevision: number | null
  code: string
  name: string
  description: string | null
  icon: string | null
  definition: ApplicationDefinition
}
export interface PublishedDefinition {
  objectId: string
  objectCode: string
  objectName: string
  schemaName: string
  tableName: string
  source: string
  readOnly: boolean
  titleFieldId: string
  settings: ObjectSettings
  fields: ObjectField[]
  fieldOptions: Record<string, FieldOptions>
  relations: ObjectRelation[]
  details: ObjectDetail[]
  mainBinding: TableBinding
}
export interface PublishedObject extends ObjectReference {
  definition: PublishedDefinition
}
/** 应用对一个数据对象的「自动跟随」开关与状态；从没拨过开关也有一项（默认开，revision 为 0）。 */
export interface ObjectFollow {
  objectId: string
  enabled: boolean
  state: 'FOLLOWING' | 'PENDING'
  /** 已发布快照里固定的版本；从没发布过则是草稿里固定的版本。 */
  pinnedVersion: number
  /** 对象当前发布版本；读不到为 null。 */
  latestVersion: number | null
  pendingVersion: number | null
  pendingCode: 'IN_FLIGHT' | 'VALIDATION' | 'ERROR' | null
  /** 给人看的原因，可直接显示。 */
  pendingReason: string | null
  /** 最近一次跟上的时间（后端按底座口径返回毫秒时间戳）。 */
  followedAt: number | string | null
  /** 开关的修订号；没有状态行为 0。 */
  revision: number
}
/** 拨开关或「立即跟随 / 重试」的结果；拨成关时没有发生跟随，outcome 为 null。 */
export interface FollowRun {
  outcome: 'FOLLOWED' | 'UP_TO_DATE' | 'PENDING' | 'DRAFT_ONLY' | null
  follow: ObjectFollow
  /** 修订号已更新的应用详情，交给工作台接收。 */
  application: ApplicationDetail
  draftSynced: boolean
  draftReason: string | null
}
/** 一次对象发布里各应用的跟随结果。 */
export interface FollowResult {
  applicationId: string
  applicationName: string
  outcome: 'FOLLOWED' | 'PENDING'
  fromVersion: number
  toVersion: number
  applicationVersion: number | null
  reason: string | null
}
/** 没有被应用引用、因关联而只读可读的对象；不能为它建列表或表单。 */
export interface ReadableObject {
  /** 最新发布版（隐式可读的对象没有固定版本）。 */
  object: PublishedObject
  via: Array<{
    fromObjectId: string
    fromObjectName: string
    kind: 'RELATION' | 'LINKAGE' | 'REFERENCE_FILTER' | 'PICK' | 'CALCULATION'
    name: string
  }>
  /** 数据管理员撤销了本应用对它的授权。 */
  closed: boolean
}
