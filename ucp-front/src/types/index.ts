/**
 * 类型统一导出入口
 *
 * 所有类型定义已按业务域拆分到对应子目录：
 * - common.ts           → 通用类型（分页、API响应）
 * - system/             → 系统管理（用户、菜单、角色、部门、组织、租户）
 * - message/            → 消息推送（消息、模板、场景、数据集）
 */

// ==================== 通用 ====================
export type { PageParams, PageResult, ApiResult } from './common'

// ==================== 认证 ====================
export type { LoginParams, LoginResult } from './auth'

// ==================== 用户 ====================
export type {
  User,
  UserQueryParams,
  UserPageResult,
  CreateUserParams,
  UpdateUserParams,
  UpdateProfileParams,
  UpdatePasswordParams
} from './system/user'

/** @deprecated UserInfo 已合并到 User，请使用 User 代替 */
export type { User as UserInfo } from './system/user'

// ==================== 菜单 ====================
export type { Menu, MenuQueryParams, CreateMenuParams, UpdateMenuParams } from './system/menu'

// ==================== 系统管理 ====================
export type {
  Role,
  RoleQueryParams,
  RolePageResult,
  CreateRoleParams,
  UpdateRoleParams,
  Department,
  DepartmentFormData,
  UserDept,
  AddUserDeptParams,
  BatchAddUsersParams,
  Organization,
  OrganizationFormData,
  Tenant,
  TenantInfo,
  TenantQueryParams,
  TenantPageResult,
  TenantFormData
} from './system/system'

// 从 constants 重新导出 TemplateType（保持向后兼容）
export type { TemplateType } from '@/constants'
