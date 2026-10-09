import request from '@/utils/request'
import type {User} from '@/types/system/user'
import {getUsersByIds} from '@/api/system/user'
import type {CreateRoleParams, Role, RolePageResult, RoleQueryParams, UpdateRoleParams,} from '@/types/system/system'

// 重新导出类型，保持使用方的 import 兼容
export type {
  Role,
  RoleQueryParams,
  RolePageResult,
  CreateRoleParams,
  UpdateRoleParams,
}
export type { User }

function toRoleSavePayload(params: CreateRoleParams | UpdateRoleParams) {
  const {
    roleName,
    roleCode,
    description,
    ...rest
  } = params as any

  return {
    ...rest,
    name: rest.name ?? roleName,
    code: rest.code ?? roleCode,
    remark: rest.remark ?? description,
    sort: rest.sort ?? 0,
  }
}

// 获取角色列表
export function getRoleList(params: RoleQueryParams) {
  const {pageNum, roleName, ...rest} = params as any
  return request.get<RolePageResult>('/system/role/page', {
    params: {
      ...rest,
      pageNo: pageNum,
      name: rest.name ?? roleName,
    },
  })
}

// 获取角色详情
export function getRoleById(id: string) {
  return request.get<Role>('/system/role/get', { params: { id } })
}

// 创建角色
export function createRole(params: CreateRoleParams) {
  return request.post('/system/role/create', toRoleSavePayload(params))
}

// 更新角色
export function updateRole(params: UpdateRoleParams) {
  return request.put('/system/role/update', toRoleSavePayload(params))
}

// 删除角色
export function deleteRole(id: string) {
  return request.delete('/system/role/delete', { params: { id } })
}

// 批量删除角色
export function batchDeleteRole(ids: string[]) {
  return request.delete('/system/role/delete-list', { params: { ids } })
}

// 获取角色的菜单权限
export function getRoleMenus(roleId: string) {
  return request.get<Array<string | number>>('/system/permission/list-role-menus', { params: { roleId } })
    .then(menuIds => (menuIds || []).map(String))
}

// 更新角色的菜单权限
export function updateRoleMenus(roleId: string, menuIds: string[]) {
  return request.post('/system/permission/assign-role-menu', { roleId, menuIds })
}

// ==================== 角色用户管理 ====================

// 获取角色的用户列表
export async function getRoleUsers(roleId: string) {
  const userIds = await request.get<Array<string | number>>('/system/permission/list-role-users', {
    params: { roleId },
  })
  return userIds.length ? getUsersByIds(userIds.map(String)) : []
}

// 添加用户到角色
// 从角色中移除用户
export function assignRoleUsers(roleId: string, userIds: string[]) {
  return request.post('/system/permission/assign-role-users', { roleId, userIds })
}

// 根据ID列表查询角色
export function getRolesByIds(ids: string[]) {
  return request.post<Role[]>('/system/role/list-by-ids', ids)
}

// 切换角色全局项目访问权（允许操作系统内置角色）
export function toggleRoleGlobalProjectView(id: string | number, globalProjectView: number) {
  return request.put('/system/role/toggle-global-project-view', { id, globalProjectView })
}
