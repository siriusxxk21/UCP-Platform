/**
 * 用户相关类型定义
 *
 * 合并了 api/user.ts 中的 User 和 types/index.ts 中的 UserInfo，
 * 消除字段重叠。User 现在是统一的用户类型。
 */

import type {PageParams} from '../common'

/**
 * 用户（统一类型，合并原 User + UserInfo）
 *
 * - 原 UserInfo 用于 stores/user.ts 存储登录用户信息
 * - 原 User 用于 api/user.ts 列表查询
 * - 两者字段高度重叠，现合并为一个类型
 */
export interface User {
  id: string
  username: string
  nickname: string
  nicknamePinyin?: string
  nicknamePinyinInitial?: string
  avatar?: string
  tenantId?: string
  orgId?: string
  orgName?: string
  deptId?: string
  deptName?: string
  remark?: string
  email?: string
  phone?: string
  mobile?: string
  post?: string
  postIds?: Array<string | number>
  userType?: number
  dataScope?: number
  sex?: number
  status?: number
  loginIp?: string
  loginDate?: string | number
  createTime?: string | number
  updateTime?: string | number
}

/** 用户查询参数 */
export interface UserQueryParams extends PageParams {
  keyword?: string
  username?: string
  nickname?: string
  phone?: string
  mobile?: string
  status?: number
  orgId?: string
  deptId?: string
  /** 高级检索动态条件（递归树型结构，与后端 DynamicConditionDTO 对应） */
  conditions?: any
  /** 导出时指定的用户编号列表 */
  ids?: Array<string | number>
}

/** 用户分页结果（列表接口专用格式） */
export interface UserPageResult {
  list: User[]
  total: number
  pageNum: number
  pageSize: number
}

/** 创建用户参数 */
export interface CreateUserParams {
  username: string
  nickname?: string
  password?: string
  avatar?: string
  remark?: string
  email?: string
  phone?: string
  mobile?: string
  orgId?: string
  deptId?: string
  postIds?: Array<string | number>
  userType?: number
  dataScope?: number
  post?: string
  sex?: number
  status: number
}

/** 更新用户参数 */
export interface UpdateUserParams {
  id: string
  username?: string
  nickname?: string
  avatar?: string
  remark?: string
  email?: string
  phone?: string
  mobile?: string
  orgId?: string
  deptId?: string
  postIds?: Array<string | number>
  userType?: number
  dataScope?: number
  post?: string
  sex?: number
  status?: number
}

/** 更新个人资料参数 */
export interface UpdateProfileParams {
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  mobile?: string
  sex?: number
}

/** 修改密码参数 */
export interface UpdatePasswordParams {
  oldPassword: string
  newPassword: string
}
