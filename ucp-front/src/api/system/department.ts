import request from '@/utils/request'
import type {
  AddUserDeptParams,
  BatchAddUsersParams,
  Department,
  DepartmentFormData,
  UserDept
} from '@/types/system/system'
import type { PageParams, PageResult } from '@/types/common'

// 重新导出类型，保持使用方的 import 兼容
export type { Department, DepartmentFormData, UserDept, AddUserDeptParams, BatchAddUsersParams }
export type { PageParams, PageResult }

function normalizeDepartmentTree(list: any[]): Department[] {
  const flatten = (items: any[]): any[] => {
    const result: any[] = []
    ;(items || []).forEach(item => {
      result.push(item)
      if (item.children?.length) {
        result.push(...flatten(item.children))
      }
    })
    return result
  }

  const nodes = flatten(list).map(item => ({
    ...item,
    id: String(item.id),
    orgId: item.orgId == null ? item.orgId : String(item.orgId),
    parentId: item.parentId == null || String(item.parentId) === '0' ? undefined : String(item.parentId),
    deptName: item.deptName ?? item.name,
    deptCode: item.deptCode ?? '',
    leaderId: item.leaderId ?? (item.leaderUserId == null ? item.leaderUserId : String(item.leaderUserId)),
    sortOrder: item.sortOrder ?? item.sort,
    // CommonStatusEnum: 0=开启(ENABLE), 1=关闭(DISABLE)
    // Ant Design Vue TreeSelect 使用 disabled 属性来禁用节点
    disabled: item.status === 1,
    children: []
  }))
  const nodeMap = new Map<string, any>()
  nodes.forEach(node => nodeMap.set(String(node.id), node))

  const roots: any[] = []
  nodes.forEach(node => {
    const parentId = node.parentId == null ? '0' : String(node.parentId)
    const parent = parentId === '0' ? null : nodeMap.get(parentId)
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  })

  const sortTree = (items: any[]) => {
    items.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    items.forEach(item => sortTree(item.children || []))
  }
  sortTree(roots)
  return roots as Department[]
}

function toDeptSaveReq(data: DepartmentFormData) {
  return {
    id: data.id,
    orgId: data.orgId,
    deptCode: data.deptCode,
    name: data.deptName,
    parentId: data.parentId,
    leaderUserId: data.leaderId,
    phone: data.phone,
    email: data.email,
    sort: data.sortOrder ?? 0,
    status: data.status ?? 0
  }
}

// 获取当前租户的部门树
export async function getDepartmentTree() {
  const list = await request.get<any[]>('/system/dept/tree')
  return normalizeDepartmentTree(list)
}

// 根据组织ID获取部门树
export async function getDepartmentTreeByOrgId(orgId: string, params?: { deptName?: string; status?: number }) {
  const list = await request.get<any[]>(`/system/dept/tree/${orgId}`, {
    params: {
      name: params?.deptName?.trim() || undefined,
      status: params?.status
    }
  })
  return normalizeDepartmentTree(list)
}

// 创建部门
export function createDepartment(data: DepartmentFormData) {
  return request.post<string>('/system/dept', toDeptSaveReq(data))
}

// 更新部门
export function updateDepartment(id: string, data: DepartmentFormData) {
  return request.put(`/system/dept/${id}`, toDeptSaveReq({ ...data, id }))
}

// 删除部门
export function deleteDepartment(id: string) {
  return request.delete(`/system/dept/${id}`)
}

// 修改部门状态
export function updateDepartmentStatus(id: string, status: number) {
  return request.put(`/system/dept/${id}/status`, null, { params: { status } })
}

// 检查编码是否存在
export function checkDeptCode(deptCode: string, excludeId?: string) {
  void deptCode
  void excludeId
  return Promise.resolve(false)
}

// ==================== 部门用户管理 ====================

// 分页获取部门用户列表
export function getDeptUsersPage(deptId: string, params: PageParams & { username?: string }) {
  return request.get<any>(`/system/dept/${deptId}/users/page`, { params }).then(normalizePageResult<UserDept>)
}

// 获取部门用户列表
export function getDeptUsers(deptId: string) {
  return request.get<UserDept[]>(`/system/dept/${deptId}/users`)
}

// 添加用户到部门
export function addUserToDept(deptId: string, params: AddUserDeptParams) {
  return request.post(`/system/dept/${deptId}/users`, params)
}

// 批量添加用户到部门
export function batchAddUsersToDept(deptId: string, params: BatchAddUsersParams) {
  return request.post(`/system/dept/${deptId}/users/batch`, params)
}

// 移除部门用户
export function removeUserFromDept(id: string) {
  return request.delete(`/system/dept/users/${id}`)
}

// 设置为主部门
export function setAsMainDept(id: string, deptId: string) {
  return request.put(`/system/dept/users/${id}/main`, null, { params: { deptId } })
}

// 更新用户岗位
export function updateUserPost(id: string, post: string) {
  return request.put(`/system/dept/users/${id}/post`, null, { params: { post } })
}

// 分页获取可添加到部门的用户
export function getAvailableUsersPage(deptId: string, params: PageParams & { username?: string }) {
  return request.get<any>(`/system/dept/${deptId}/available-users/page`, { params }).then(normalizePageResult<UserDept>)
}

// 获取可添加到部门的用户
export function getAvailableUsers(deptId: string, username?: string) {
  return request.get<UserDept[]>(`/system/dept/${deptId}/available-users`, {
    params: { username }
  })
}

// 根据ID列表查询部门
export async function getDepartmentsByIds(ids: string[]) {
  const list = await request.post<any[]>('/system/dept/list-by-ids', ids)
  return normalizeDepartmentTree(list)
}

function normalizePageResult<T>(page: any): PageResult<T> {
  const records = page.records ?? page.list ?? []
  const total = page.total ?? records.length
  const current = page.current ?? page.pageNo ?? 1
  const size = page.size ?? page.pageSize ?? records.length
  return {
    records,
    total,
    current,
    size,
    pages: size > 0 ? Math.ceil(total / size) : 0
  }
}
