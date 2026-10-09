import type { DataScope } from './data-scope'
/** 授权绑定底座身份，操作编码与后端枚举一致。 */
export const BusinessAction = {
  READ: 'READ',
  CREATE: 'CREATE',
  UPDATE: 'UPDATE',
  DELETE: 'DELETE',
  IMPORT: 'IMPORT',
  EXPORT: 'EXPORT',
  START_PROCESS: 'START_PROCESS'
} as const
export type BusinessAction = (typeof BusinessAction)[keyof typeof BusinessAction]
export const PrincipalKind = { USER: 'USER', ROLE: 'ROLE' } as const
export const RecordScope = { ALL: 'ALL', OWN: 'OWN' } as const
export interface ObjectGrant {
  objectId: string
  actions: BusinessAction[]
  scope: keyof typeof RecordScope
  readFields: string[]
  writeFields: string[]
  readDetails: string[]
  writeDetails: string[]
  readRelations?: string[]
  writeRelations?: string[]
  actionScopes?: Record<string, DataScope>
  /** 已停用，后端忽略：系统计算能否读取只看记录范围与查看条件，不再由人勾选。 */
  computeFields?: string[]
}
export interface ApplicationMember {
  principalKind: keyof typeof PrincipalKind
  principalId: string
  objects: ObjectGrant[]
}
export interface ApplicationPolicy {
  revision: number
  members: ApplicationMember[]
}

/** 独立于应用发布版本的对象共享授权上限。permission=null 表示撤销。 */
export interface ObjectSharingGrant {
  objectId: string
  applicationId: string
  applicationName: string
  revision: number
  permission: ObjectGrant | null
  reason: string
  updater: string
  updateTime: string
}

export const businessActionOptions = [
  { label: '查看', value: BusinessAction.READ },
  { label: '新增', value: BusinessAction.CREATE },
  { label: '修改', value: BusinessAction.UPDATE },
  { label: '删除', value: BusinessAction.DELETE },
  { label: '导入', value: BusinessAction.IMPORT },
  { label: '导出', value: BusinessAction.EXPORT },
  { label: '发起流程', value: BusinessAction.START_PROCESS }
]
