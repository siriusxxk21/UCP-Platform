import request from '@/utils/request'
import type {
    CreateUserParams,
    UpdatePasswordParams,
    UpdateProfileParams,
    UpdateUserParams,
    User,
    UserPageResult,
    UserQueryParams,
} from '@/types/system/user'

// 重新导出类型，保持使用方的 import 兼容
export type {
  User,
  UserQueryParams,
  UserPageResult,
  CreateUserParams,
  UpdateUserParams,
  UpdateProfileParams,
  UpdatePasswordParams,
}

// 获取用户列表（GET：通过属性路径序列化支持嵌套动态检索条件 conditions）
export function getUserList(params: UserQueryParams) {
  const { pageNum, phone, ...rest } = params as any
  return request
    .get<any>('/system/user/page', {
      params: {
        ...rest,
        pageNo: pageNum,
        mobile: rest.mobile ?? phone,
      },
      paramsSerializer: serializeUserQueryParams,
    })
    .then(normalizeUserPageResult)
}

/**
 * 将动态条件转换为 Spring WebDataBinder 可识别的属性路径。
 *
 * Axios 默认会将嵌套对象编码成 conditions[items][0][field]，该格式不能稳定绑定到
 * DynamicConditionDTO；这里统一使用 conditions.items[0].field。
 */
function serializeUserQueryParams(params: Record<string, unknown>): string {
  const searchParams = new URLSearchParams()
  const append = (key: string, value: unknown): void => {
    if (value === undefined || value === null || value === '') return
    if (isDayjsValue(value)) {
      searchParams.append(key, value.format('YYYY-MM-DD HH:mm:ss'))
      return
    }
    if (Array.isArray(value)) {
      value.forEach((item, index) => append(`${key}[${index}]`, item))
      return
    }
    if (typeof value === 'object') {
      Object.entries(value).forEach(([property, propertyValue]) => append(`${key}.${property}`, propertyValue))
      return
    }
    searchParams.append(key, String(value))
  }
  Object.entries(params).forEach(([key, value]) => append(key, value))
  return searchParams.toString()
}

function isDayjsValue(value: unknown): value is { format: (pattern: string) => string } {
  return typeof value === 'object'
    && value !== null
    && 'format' in value
    && typeof (value as { format?: unknown }).format === 'function'
}

/** 兼容后端 PageResult(list) 与部分旧接口 Page(records) 的分页结构 */
function normalizeUserPageResult(page: any): UserPageResult {
  const list = Array.isArray(page?.list) ? page.list : Array.isArray(page?.records) ? page.records : []
  return {
    list: list.map(normalizeUser),
    total: Number(page?.total ?? list.length ?? 0),
    pageNum: Number(page?.pageNum ?? page?.pageNo ?? page?.current ?? page?.currentPage ?? 1),
    pageSize: Number(page?.pageSize ?? page?.size ?? list.length ?? 0)
  }
}

function normalizeUser(user: any): User {
  if (!user) return user
  return {
    ...user,
    id: String(user.id),
    phone: user.phone ?? user.mobile,
    mobile: user.mobile ?? user.phone
  }
}

// 获取启用用户精简列表；传入关键词时由后端匹配账号、昵称及昵称拼音。
export function getSimpleUserList(keyword?: string) {
  return request
    .get<User[]>('/system/user/list-all-simple', {
      params: { keyword: keyword?.trim() || undefined },
    })
    .then(list => (Array.isArray(list) ? list.map(normalizeUser) : []))
}

// 获取用户详情
export function getUserById(id: string) {
  return request.get<User>('/system/user/get', { params: { id } }).then(normalizeUser)
}

// 创建用户
export function createUser(params: CreateUserParams) {
  const {phone, mobile, post, orgId, userType, dataScope, status, ...rest} = params as any
  return request.post('/system/user/create', {
    ...rest,
    mobile: mobile ?? phone,
  })
}

// 更新用户
export function updateUser(params: UpdateUserParams) {
  const {phone, mobile, post, orgId, userType, dataScope, status, ...rest} = params as any
  return request.put('/system/user/update', {
    ...rest,
    mobile: mobile ?? phone,
  })
}

// 删除用户
export function deleteUser(id: string) {
  return request.delete('/system/user/delete', { params: { id } })
}

// 批量删除用户
export function batchDeleteUser(ids: string[]) {
  return request.delete('/system/user/delete-list', { params: { ids } })
}

// 修改用户状态
export function updateUserStatus(id: string, status: number) {
  return request.put('/system/user/update-status', { id, status })
}

// 查询不在指定角色中的用户
export async function getUsersNotInRole(roleId: string, username?: string) {
  // 用户关键词交给后端处理，包含昵称全拼和首拼；前端只负责排除角色已有成员。
  const filteredUsers = await getSimpleUserList(username)

  const roleIdSetList = await Promise.all(filteredUsers.map(user => getUserRoleIds(user.id)))
  return filteredUsers.filter((_, index) => !roleIdSetList[index].includes(String(roleId)))
}

// 查询用户已分配角色
export function getUserRoleIds(userId: string) {
  return request.get<Array<string | number>>('/system/permission/list-user-roles', { params: { userId } })
    .then(roleIds => (roleIds || []).map(String))
}

// 覆盖分配用户角色
export function assignUserRoles(userId: string, roleIds: string[]) {
  return request.post('/system/permission/assign-user-role', { userId, roleIds })
}

// 获取当前用户个人信息
export function getProfile() {
  return request.get<User>('/system/user/profile/get')
}

// 更新当前用户个人信息
export function updateProfile(params: UpdateProfileParams) {
  return request.put('/system/user/profile/update', params)
}

// 修改密码
export function updatePassword(params: UpdatePasswordParams) {
  return request.put('/system/user/profile/update-password', params)
}

// 根据ID列表查询用户
export function getUsersByIds(ids: string[]) {
  return request
    .get<User[]>('/system/user/list', {
      params: { ids },
      paramsSerializer: { indexes: null }
    })
    .then(list => (Array.isArray(list) ? list.map(normalizeUser) : []))
}

// 管理员重置用户密码
export function resetUserPassword(id: string, newPassword: string) {
  return request.put('/system/user/update-password', { id, password: newPassword })
}

// 导出用户列表（返回 Blob）
export function exportUser(params?: Partial<UserQueryParams>) {
  return request.get<Blob>('/system/user/export-excel', {
    params,
    paramsSerializer: serializeUserQueryParams,
    responseType: 'blob',
  })
}

// 下载用户导入模板（返回 Blob）
export function downloadUserTemplate() {
  return request.get<Blob>('/system/user/get-import-template', { responseType: 'blob' })
}

// 批量导入用户（multipart/form-data）
export function importUser(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/system/user/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
