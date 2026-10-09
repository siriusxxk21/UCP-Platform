/**
 * 网盘模块类型
 *
 * 平台雪花编号超过 JS 安全整数范围，响应里会被序列化成字符串，前端只做透传，
 * 一律用 DriveId（string | number）承载，比较时统一转字符串。
 * 时间字段沿用平台 JSON 口径：Long 毫秒时间戳。
 */
export type DriveId = string | number

export type DriveSpaceType = 'PERSONAL' | 'TEAM' | 'BIZ'
export type DriveEntryType = 'FOLDER' | 'FILE'
export type DriveTrashState = 'NORMAL' | 'TRASHED'
export type DrivePermissionRole = 'VIEWER' | 'EDITOR' | 'MANAGER'
export type DriveSubjectType = 'USER' | 'DEPT'
export type DriveShareStatus = 'ACTIVE' | 'REVOKED'
export type DriveMarkType = 'FAVORITE' | 'RECENT'

/** 空间根目录在接口里固定为父节点 0 */
export const DRIVE_ROOT_PARENT_ID = 0

export const DRIVE_ROLE_OPTIONS: Array<{ label: string; value: DrivePermissionRole }> = [
  { label: '可查看', value: 'VIEWER' },
  { label: '可编辑', value: 'EDITOR' },
  { label: '可管理', value: 'MANAGER' }
]

/** 分享只允许下放查看与编辑，不含管理 */
export const DRIVE_SHARE_ROLE_OPTIONS = DRIVE_ROLE_OPTIONS.filter(option => option.value !== 'MANAGER')

export const DRIVE_ROLE_LABELS: Record<DrivePermissionRole, string> = {
  VIEWER: '可查看',
  EDITOR: '可编辑',
  MANAGER: '可管理'
}

export const DRIVE_SUBJECT_TYPE_LABELS: Record<DriveSubjectType, string> = {
  USER: '用户',
  DEPT: '部门'
}

export const DRIVE_SPACE_TYPE_LABELS: Record<DriveSpaceType, string> = {
  PERSONAL: '个人空间',
  TEAM: '团队空间',
  BIZ: '业务空间'
}

export const DRIVE_SPACE_STATUS_OPTIONS = [
  { label: '开启', value: 0 },
  { label: '关闭', value: 1 }
]

export const DRIVE_SPACE_TAG_MAP = {
  type: {
    PERSONAL: { label: '个人空间', color: 'default' },
    TEAM: { label: '团队空间', color: 'purple' },
    BIZ: { label: '业务空间', color: 'blue' }
  },
  status: {
    0: { label: '开启', color: 'success' },
    1: { label: '关闭', color: 'default' }
  }
}

export const DRIVE_ENTRY_TAG_MAP = {
  type: {
    FOLDER: { label: '目录', color: 'blue' },
    FILE: { label: '文件', color: 'default' }
  }
}

export const DRIVE_SHARE_STATUS_OPTIONS: Array<{ label: string; value: DriveShareStatus }> = [
  { label: '生效中', value: 'ACTIVE' },
  { label: '已撤销', value: 'REVOKED' }
]

export const DRIVE_SHARE_TAG_MAP = {
  status: {
    ACTIVE: { label: '生效中', color: 'success' },
    REVOKED: { label: '已撤销', color: 'default' }
  },
  role: {
    VIEWER: { label: '可查看', color: 'default' },
    EDITOR: { label: '可编辑', color: 'blue' },
    MANAGER: { label: '可管理', color: 'purple' }
  },
  entryType: {
    FOLDER: { label: '目录', color: 'blue' },
    FILE: { label: '文件', color: 'default' }
  }
}

export interface DriveSpace {
  id: DriveId
  name: string
  type: DriveSpaceType
  ownerId?: DriveId
  ownerName?: string
  ownerDeptId?: DriveId
  ownerDeptName?: string
  /** 配额字节数，0 表示不限制 */
  quotaBytes: number
  /** 已用字节数，不含回收站内容 */
  usedBytes: number
  status: number
  /** 当前用户在该空间的有效角色 */
  role?: DrivePermissionRole
  createTime?: number
}

export interface DriveSpaceSave {
  id?: DriveId
  name: string
  ownerId?: DriveId
  ownerDeptId?: DriveId
  quotaBytes?: number
  status?: number
}

export interface DriveEntry {
  id: DriveId
  spaceId: DriveId
  parentId: DriveId
  name: string
  type: DriveEntryType
  fileId?: DriveId
  size: number
  mimeType?: string
  inheritParent: boolean
  trashState?: DriveTrashState
  trashedAt?: number
  trashedBy?: DriveId
  favorite?: boolean
  /** 当前用户在该节点上的有效角色，列表接口不回填 */
  role?: DrivePermissionRole
  creator?: DriveId
  createTime?: number
  updateTime?: number
  /** 仅限定子树的口子返回；false 表示这次调用不能改名/移动/删除它 */
  modifiable?: boolean
}

export interface DriveEntryQuery {
  spaceId: DriveId
  parentId: DriveId
}

export interface DriveTrashQuery {
  spaceId: DriveId
  name?: string
}

export interface DriveBreadcrumb {
  id: DriveId
  name: string
}

export interface DrivePermission {
  id: DriveId
  spaceId: DriveId
  entryId: DriveId
  entryName?: string
  subjectType: DriveSubjectType
  subjectId: DriveId
  subjectName?: string
  role: DrivePermissionRole
  includeChildren: boolean
  createTime?: number
}

export interface DrivePermissionSave {
  spaceId: DriveId
  entryId: DriveId
  subjectType: DriveSubjectType
  subjectId: DriveId
  role: DrivePermissionRole
  includeChildren?: boolean
}

export interface DrivePermissionSource {
  id?: DriveId
  subjectType: DriveSubjectType
  subjectId: DriveId
  subjectName?: string
  role: DrivePermissionRole
  includeChildren?: boolean
  sourceEntryId: DriveId
  sourceEntryName?: string
  ownerGrant?: boolean
}

export interface DriveShareSubject {
  subjectType: DriveSubjectType
  subjectId: DriveId
  subjectName?: string
}

export interface DriveShare {
  id: DriveId
  spaceId: DriveId
  spaceName?: string
  entryId: DriveId
  entryName?: string
  entryType?: DriveEntryType
  role: DrivePermissionRole
  /** 为空表示长期有效 */
  expireTime?: number
  status: DriveShareStatus
  creator?: DriveId
  creatorName?: string
  createTime: number
  subjects?: DriveShareSubject[]
}

export interface DriveShareSave {
  id?: DriveId
  spaceId: DriveId
  entryId: DriveId
  role: DrivePermissionRole
  expireTime?: number
  subjects: DriveShareSubject[]
}

export interface DriveShareQuery {
  status?: DriveShareStatus
}

export interface DriveMarkedEntry {
  entryId: DriveId
  spaceId: DriveId
  spaceName?: string
  parentId: DriveId
  name: string
  type: DriveEntryType
  size: number
  mimeType?: string
  favorite: boolean
  accessTime?: number
}
