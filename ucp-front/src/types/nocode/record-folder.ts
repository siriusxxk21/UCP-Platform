import type { DriveEntryType, DriveId, DrivePermissionRole } from '@/types/drive'

/**
 * 记录文件夹：对象上的文件夹来源配置，以及表单下方嵌入的文件夹浏览。
 *
 * 文件夹就是网盘里的真实文件夹；浏览接口里 parentId / targetParentId 为 0 表示「这个文件夹的根」，
 * 根自身的编号不出现在任何返回值里。网盘节点编号沿用 DriveId，其余 ID 都是字符串。
 */
export type RecordFolderKind = 'FOLDER' | 'RELATION'
export type RecordFolderPlacement = 'DIRECT' | 'RECORD_SUBFOLDER'
export type RecordFolderState = 'READY' | 'PENDING' | 'RELATION_EMPTY' | 'UNAVAILABLE'
export type RecordFolderCreateMode = 'ON_FIRST_WRITE' | 'ON_SAVE'
export type RecordFolderNamePartKind = 'FIELD' | 'TEXT'

/** kind = FIELD 时用 fieldId；kind = TEXT 时用 text */
export interface RecordFolderNamePart {
  kind: RecordFolderNamePartKind
  fieldId?: string | null
  text?: string | null
}
/** 子文件夹的命名模板；配置里为 null 表示用记录名称 */
export interface RecordFolderNameTemplate {
  separator: string
  parts: RecordFolderNamePart[]
}
export interface RecordFolderNameField {
  fieldId: string
  name: string
  type: string
}

/** 保存时提交的一个来源：只含这些字段，服务端不接受多余属性 */
export interface RecordFolderSourceInput {
  id?: string | null
  kind: RecordFolderKind
  placement: RecordFolderPlacement
  label: string
  spaceId?: DriveId | null
  entryId?: DriveId | null
  relationFieldId?: string | null
  targetSourceId?: string | null
  createMode?: RecordFolderCreateMode | null
  nameTemplate?: RecordFolderNameTemplate | null
}
/** 读取配置时的一个来源：多出的都是回显字段 */
export interface RecordFolderSource extends RecordFolderSourceInput {
  id: string
  objectId: string
  displayLabel?: string | null
  folderPath?: string | null
  relationName?: string | null
  targetObjectId?: string | null
  targetObjectName?: string | null
  targetLabel?: string | null
  /** 非空表示这一行的配置已失效，内容是原因 */
  problem?: string | null
}

export interface RecordFolderCandidateSource {
  id: string
  label: string
}
export interface RecordFolderCandidate {
  relationFieldId: string
  relationName: string
  targetObjectId: string
  targetObjectName: string
  sources: RecordFolderCandidateSource[]
  disabledReason?: string | null
}

export interface RecordFolderBackfillQuery {
  objectId: string
  sourceId: string
  cursor?: string | null
  limit?: number
}
export interface RecordFolderBackfillFailure {
  recordId: string
  message: string
}
/** 补建的一页 */
export interface RecordFolderBackfillResult {
  cursor: string | null
  done: boolean
  scanned: number
  created: number
  existing: number
  skipped: number
  failed: number
  failures: RecordFolderBackfillFailure[]
}
/** 补建的累计：failures 最多留 20 条 */
export interface RecordFolderBackfillTotal {
  scanned: number
  created: number
  existing: number
  skipped: number
  failed: number
  failures: RecordFolderBackfillFailure[]
  /** 全部扫完 */
  done: boolean
  /** 被人为停止 */
  stopped: boolean
}

export interface RecordFolderOpenQuery {
  applicationId?: string
  objectId: string
  recordId: string
}
export interface RecordFolderTab {
  sourceId: string
  label: string
  state: RecordFolderState
  message?: string | null
  /** 凭这条记录能写（能改这条记录，且文件夹已建或可以建） */
  writable: boolean
  /** 本人在这个页签里能不能写：writable，或文件夹已建、本人在它上面的网盘角色达到可编辑（与服务端写操作同一口径） */
  canWrite: boolean
}
export interface RecordFolderOpened {
  tabs: RecordFolderTab[]
}

/** 凭据：每个浏览请求都带，服务端每次重新校验「能看 / 能改这条记录」 */
export interface RecordFolderCredential {
  applicationId?: string
  objectId: string
  recordId: string
  sourceId: string
}
/** 凭据四项 + 各操作自己的参数 */
export interface RecordFolderEntryQuery extends RecordFolderCredential {
  id?: DriveId
  parentId?: DriveId
  targetParentId?: DriveId
  ids?: DriveId[]
  name?: string
  limit?: number
}
export interface RecordFolderEntry {
  id: DriveId
  spaceId: DriveId
  parentId: DriveId
  name: string
  type: DriveEntryType
  size?: number | null
  mimeType?: string | null
  role?: DrivePermissionRole | null
  creator?: DriveId | null
  createTime?: number | null
  updateTime?: number | null
  trashedAt?: number | null
  trashedBy?: DriveId | null
  /** 这次调用能不能改名 / 移动 / 删除这个节点（仅供界面呈现，服务端每次操作仍重新判定） */
  modifiable: boolean
}
