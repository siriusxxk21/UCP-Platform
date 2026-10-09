/**
 * 数据联动「来源变化时自动更新」的总览、预告与回填契约（与后端 API/api/LinkageSync.java 同形，第一期契约第 6 章）。
 * 基准：PUBLISHED = 应用当前发布版固定的对象版本；DRAFT = 应用草稿引用的对象版本（发布前预告用）。
 */
export type LinkageSyncBasis = 'PUBLISHED' | 'DRAFT'
export interface LinkageSyncDivergent {
  applicationId: string
  applicationName: string
  reason: 'DIFFERENT_RULE' | 'NO_RULE' | 'TARGET_NOT_PINNED'
}
export interface LinkageSyncField {
  targetObjectId: string
  targetObjectName: string
  targetObjectVersion: number
  targetFieldId: string
  targetFieldName: string
  sourceObjectId: string
  sourceObjectName: string
  anchor: 'CURRENT_RECORD' | 'RECORD_KEY'
  anchorFieldId: string
  anchorFieldName: string
  signature: string
  /** PUBLISHED：相对上一个发布版；DRAFT：相对当前发布版。 */
  change: 'NEW' | 'CHANGED' | 'UNCHANGED'
  /** 其它启用中的已发布应用里，也固定了这个来源对象、但对这个目标字段口径不同的。 */
  divergent: LinkageSyncDivergent[]
}
export interface LinkageSyncRemoved {
  targetObjectId: string
  targetObjectName: string
  targetFieldId: string
  targetFieldName: string
}
export interface LinkageSyncOverview {
  applicationId: string
  basis: LinkageSyncBasis
  /** DRAFT 基准时为 null。 */
  applicationVersion: number | null
  fields: LinkageSyncField[]
  /** 比较基准里有、现在没有的字段：已落库的值保留不清，只是不再跟随。 */
  removed: LinkageSyncRemoved[]
}
export interface LinkageSyncFailure {
  recordId: string
  reason: string
}
export interface LinkagePreviewRequest {
  applicationId: string
  basis: LinkageSyncBasis
  targetObjectId: string
  targetFieldId: string
  /** 上一页返回的 nextCursor；第一页为 null。 */
  cursor: string | null
  /** 1..500，缺省 200。 */
  limit: number
}
export interface LinkagePreviewPage {
  signature: string
  /** 目标记录总数：只在第一页给，其余页为 null。 */
  total: number | null
  scanned: number
  unchanged: number
  willFill: number
  willClear: number
  willChange: number
  failedCount: number
  /** 每页至多 20 条。 */
  failed: LinkageSyncFailure[]
  nextCursor: string | null
  done: boolean
}
export interface LinkageBackfillRequest {
  applicationId: string
  targetObjectId: string
  targetFieldId: string
  /** 来自总览或预告；与当前发布版登记的签名不一致即被拒绝（规则已变化）。 */
  signature: string
  cursor: string | null
  /** 1..200，缺省 100。 */
  limit: number
}
export interface LinkageBackfillPage {
  scanned: number
  updated: number
  unchanged: number
  failedCount: number
  failed: LinkageSyncFailure[]
  nextCursor: string | null
  done: boolean
}
