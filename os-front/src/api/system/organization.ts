import request from '@/utils/request'
import type {Organization, OrganizationFormData,} from '@/types/system/system'

// 重新导出类型，保持使用方的 import 兼容
export type {
  Organization,
  OrganizationFormData,
}

// 获取组织树（当前租户）
export function getOrganizationTree(params?: { orgName?: string, status?: number }) {
  return request.get<Organization[]>('/system/organization/tree', { params })
}

// 获取当前租户下所有组织与部门的混合树
export function getOrgDeptTree() {
  return request.get<any[]>('/system/organization/org-dept-tree')
}

// 创建组织
export function createOrganization(data: OrganizationFormData) {
  return request.post<string>('/system/organization', data)
}

// 更新组织
export function updateOrganization(id: string, data: OrganizationFormData) {
  return request.put(`/system/organization/${id}`, data)
}

// 删除组织
export function deleteOrganization(id: string) {
  return request.delete(`/system/organization/${id}`)
}

// 修改组织状态
export function updateOrganizationStatus(id: string, status: number) {
  return request.put(`/system/organization/${id}/status`, null, { params: { status } })
}

// 检查编码是否存在
export function checkOrgCode(orgCode: string, excludeId?: string) {
  return request.get<boolean>('/system/organization/check-code', { params: { orgCode, excludeId } })
}

// 根据ID列表查询组织
export function getOrganizationsByIds(ids: string[]) {
  return request.post<Organization[]>('/system/organization/list-by-ids', ids)
}
