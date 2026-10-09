/**
 * 认证相关类型定义
 *
 * 跨域共用，放在 types 根目录
 */

import type {TenantInfo} from './system/system'

/** 登录请求参数 */
export interface LoginParams {
  username: string
  password: string
  captchaVerification?: string
  tenantCode?: string
}

/** 正式登录成功响应 */
export interface LoginSuccessResult {
  loginStatus: 'SUCCESS'
  userId: number | string
  accessToken: string
  refreshToken: string
  expiresTime: string
}

/** 必须先修改密码的预认证响应 */
export interface PasswordChangeRequiredResult {
  loginStatus: 'PASSWORD_CHANGE_REQUIRED'
  userId: number | string
  passwordChangeToken: string
  accessToken?: never
  refreshToken?: never
  expiresTime?: never
}

/** 登录响应结果 */
export type LoginResult = LoginSuccessResult | PasswordChangeRequiredResult

/** 登录用户权限信息 */
export interface AuthPermissionInfo {
  user: {
    id: number | string
    username: string
    nickname: string
    avatar?: string
    deptId?: number | string
    email?: string
    /** 是否必须修改密码（初始密码或已过期） */
    mustChangePassword?: boolean
    /** 密码剩余有效天数，null/undefined 表示永不过期 */
    passwordRemainDays?: number | null
    /** 密码临期提醒天数 */
    passwordRemindDays?: number
  }
  roles: string[]
  permissions: string[]
  menus: AuthPermissionMenu[]
}

/** 登录用户菜单 */
export interface AuthPermissionMenu {
  id: number | string
  parentId: number | string
  name: string
  path: string
  component?: string
  componentName?: string
  icon?: string
  visible?: boolean
  keepAlive?: boolean
  alwaysShow?: boolean
  children?: AuthPermissionMenu[]
  sort?: number
}

/** 兼容旧登录接口返回结构 */
export interface LegacyLoginResult {
  token: string
  userInfo: AuthPermissionInfo['user']
  menus: AuthPermissionMenu[]
  tenantInfo?: TenantInfo
}
