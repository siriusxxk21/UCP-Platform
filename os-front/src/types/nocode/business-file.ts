import type { Page } from './data-center'

/** 服务端 Long 可能以数字或大整数转义后的字符串返回；控件统一按字符串归一化处理。 */
export type BusinessFileId = string | number

/** 名称状态与记录访问权限独立；标题缺值或配置失效不代表记录受限。 */
export type BusinessRecordLabelStatus = 'NORMAL' | 'EMPTY' | 'RESTRICTED' | 'INVALID'

/** 文件项：fileId 为表单字段值，entryId 为网盘节点身份（预览/下载/定位用）。 */
export interface BusinessFileEntry {
  fileId: BusinessFileId
  entryId: BusinessFileId
  spaceId?: BusinessFileId | null
  name: string
  size: number
  mimeType?: string | null
  recordId?: string | null
  recordLabel?: string | null
  recordRestricted?: boolean
  recordLabelStatus?: BusinessRecordLabelStatus
  fieldLabel?: string | null
  detailLabel?: string | null
  rowLabel?: string | null
  detailId?: string | null
  rowId?: string | null
  fieldId?: string | null
  submitter?: string | null
  uploadedAt?: string | null
}

/** 与后端 BusinessFiles.FileQuery 对齐；applicationId 为空表示数据维护上下文。 */
export interface BusinessFileQuery {
  applicationId?: string
  objectId: string
  ruleVersion?: number
  groupKeys?: string[]
  recordId?: string
  detailId?: string
  rowId?: string
  fieldId?: string
  search?: string
  pageNo: number
  pageSize: number
}

/** 与后端 BusinessFiles.DirectoryQuery 对齐；未给 ruleVersion 时列出规则版本根节点。 */
export interface BusinessFileDirectoryQuery {
  applicationId?: string
  objectId: string
  ruleVersion?: number
  groupKeys?: string[]
  recordId?: string
  detailId?: string
  rowId?: string
  pageNo: number
  pageSize: number
}

/** 服务端 Long 可能以字符串返回；位置身份与内容读取端点的查询参数保持一致。 */
export interface BusinessFileContentQuery {
  applicationId?: string
  objectId: string
  recordId: string
  detailId?: string
  rowId?: string
  fieldId: string
  entryId: BusinessFileId
}

/** 未保存内容的会话内读取身份，仅上传人在有效期内可读。 */
export interface BusinessTemporaryContentQuery {
  objectId: string
  detailId?: string
  fieldId: string
  sessionKey: string
  fileId: BusinessFileId
}

/** 与后端 BusinessFiles.FavoriteQuery 对齐；先重验可见绑定再写本人收藏标记。 */
export interface BusinessFileFavoriteQuery extends BusinessFileContentQuery {
  favorite: boolean
}

/** 与后端 BusinessFiles.MarkQuery 对齐；markType 取 FAVORITE 或 RECENT。 */
export interface BusinessFileMarkQuery {
  applicationId?: string
  objectId: string
  markType: 'FAVORITE' | 'RECENT'
}

/** 入口内业务空间；currentRuleVersion 为空表示规则已停用但历史绑定仍在。 */
export interface BusinessFileSpace {
  objectId: string
  objectName: string
  spaceName: string
  fixedPath?: string[] | null
  currentRuleVersion?: number | null
  fileCount: number
  totalSize: number
  recordCount: number
}

export interface BusinessFileConfigSpace {
  id: string | number
  name: string
  status: number
}

/** 目录项：kind=VERSION 规则版本根 / GROUP 业务分组 / RECORD 记录 / FIELD 附件字段 / REGION 明细区 / ROW 明细行。 */
export interface BusinessFileDirectory {
  kind: 'VERSION' | 'GROUP' | 'RECORD' | 'FIELD' | 'REGION' | 'ROW'
  ruleVersion?: number | null
  groupKey?: string | null
  recordId?: string | null
  detailId?: string | null
  rowId?: string | null
  fieldId?: string | null
  label: string
  restricted?: boolean
  labelStatus?: BusinessRecordLabelStatus
  fileCount: number
  totalSize: number
  recordCount: number
}

/** 与后端 BusinessFiles.UploadQuery 对齐；recordId 为空表示新增记录，detailId 为空表示主表字段。 */
export interface BusinessFileUploadQuery {
  applicationId?: string
  objectId: string
  recordId?: string
  detailId?: string
  fieldId: string
  sessionKey: string
  idempotencyKey?: string
}

export interface BusinessFileUploaded {
  fileId: BusinessFileId
  name: string
  size: number
  mimeType?: string | null
}

export type BusinessFilePage = Page<BusinessFileEntry>
export type BusinessFileDirectoryPage = Page<BusinessFileDirectory>
