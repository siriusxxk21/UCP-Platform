/**
 * 系统管理相关类型定义（角色、部门、组织、租户）
 */

import type {PageParams, PageResult} from '../common'

// ==================== 角色 ====================

/** 角色 */
export interface Role {
  id: string
  name: string
  code: string
  sort: number
  status: number
  type?: number
  remark?: string
  dataScope?: number
  dataScopeDeptIds?: Array<string | number> | null
  /** 全局项目访问权（0=关闭 1=允许访问所有项目空间，只读） */
  globalProjectView?: number
  createTime?: string | number
  updateTime?: string
}

/** 角色查询参数 */
export interface RoleQueryParams extends PageParams {
  roleName?: string
  name?: string
  code?: string
  status?: number
}

/** 角色分页结果 */
export interface RolePageResult {
  list: Role[]
  total: number
  pageNum: number
  pageSize: number
}

/** 创建角色参数 */
export interface CreateRoleParams {
  roleName?: string
  roleCode?: string
  description?: string
  name?: string
  code?: string
  remark?: string
  sort?: number
  status: number
  /** 全局项目访问权 */
  globalProjectView?: number
}

/** 更新角色参数 */
export interface UpdateRoleParams {
  id: string | number
  roleName?: string
  roleCode?: string
  description?: string
  name?: string
  code?: string
  remark?: string
  sort?: number
  status?: number
  /** 全局项目访问权 */
  globalProjectView?: number
}

// ==================== 部门 ====================

/** 部门 */
export interface Department {
  id: string
  orgId: string
  orgName?: string
  deptCode: string
  deptName: string
  parentId?: string
  parentIds?: string
  level?: number
  leaderId?: string
  leaderName?: string
  phone?: string
  email?: string
  sortOrder?: number
  status: number
  /** 是否禁用选择（基于 status === 1 即 CommonStatusEnum.DISABLE） */
  disabled?: boolean
  createTime?: string
  children?: Department[]
}

/** 部门表单数据 */
export interface DepartmentFormData {
  id?: string
  orgId: string
  deptCode: string
  deptName: string
  parentId?: string
  leaderId?: string
  phone?: string
  email?: string
  sortOrder?: number
  status?: number
}

/** 部门用户 */
export interface UserDept {
  id?: string
  userId: string
  username: string
  nickname: string
  phone?: string
  email?: string
  userStatus: number
  post?: string
  isMain?: number
  createTime?: string
}

/** 添加用户到部门参数 */
export interface AddUserDeptParams {
  userId: string
  deptId?: string
  post?: string
  isMain?: boolean
}

/** 批量添加用户到部门参数 */
export interface BatchAddUsersParams {
  userIds: string[]
  post?: string
  isMain?: boolean
}

// ==================== 组织 ====================

/** 组织 */
export interface Organization {
  id: string
  orgCode: string
  orgName: string
  orgType: number
  orgTypeName?: string
  parentId?: string
  parentIds?: string
  level?: number
  leaderId?: string
  leaderName?: string
  phone?: string
  email?: string
  address?: string
  sortOrder?: number
  status: number
  createTime?: string
  children?: Organization[]
}

/** 组织表单数据 */
export interface OrganizationFormData {
  id?: string
  orgCode: string
  orgName: string
  orgType: number
  parentId?: string
  leaderId?: string
  phone?: string
  email?: string
  address?: string
  sortOrder?: number
  status?: number
}

// ==================== 租户 ====================

/** 租户 */
export interface Tenant {
  id: string
  tenantCode: string
  tenantName: string
  tenantType: number
  tenantTypeName?: string
  contactName?: string
  contactPhone?: string
  contactEmail?: string
  logoUrl?: string
  domain?: string
  isolationStrategy?: string
  status: number
  statusName?: string
  expireTime?: string
  maxUserCount?: number
  maxStorageSize?: number
  createTime?: string
  updateTime?: string
}

/** 租户信息（轻量版，用于 stores） */
export interface TenantInfo {
  id: string
  tenantCode: string
  tenantName: string
  tenantType: number
  tenantTypeName?: string
  status: number
  statusName?: string
  expireTime?: string
  domain?: string
  logoUrl?: string
}

/** 租户查询参数 */
export interface TenantQueryParams extends PageParams {
  tenantName?: string
  tenantType?: number
  status?: number
}

/** 租户分页结果 */
export interface TenantPageResult extends PageResult<Tenant> {}

/** 租户表单数据 */
export interface TenantFormData {
  id?: string
  tenantCode: string
  tenantName: string
  tenantType: number
  contactName?: string
  contactPhone?: string
  contactEmail?: string
  logoUrl?: string
  domain?: string
  isolationStrategy?: string
  status?: number
  expireTime?: string
  maxUserCount?: number
  maxStorageSize?: number
  // 租户管理员信息（仅创建时需要）
  adminUsername?: string
  adminPassword?: string
  adminNickname?: string
  adminPhone?: string
  adminEmail?: string
}
