import request from '@/utils/request'
import type {Tenant, TenantFormData, TenantPageResult, TenantQueryParams,} from '@/types/system/system'

// 重新导出类型，保持使用方的 import 兼容
export type {
  Tenant,
  TenantQueryParams,
  TenantPageResult,
  TenantFormData,
}

// 分页查询租户列表
export function getTenantList(params: TenantQueryParams) {
  return request.get<TenantPageResult>('/system/tenant/list', { params })
}

// 查询所有有效租户（下拉用）
export function getAllValidTenants() {
  return request.get<Tenant[]>('/system/tenant/all-valid')
}

// 根据ID查询租户
export function getTenantById(id: string) {
  return request.get<Tenant>(`/system/tenant/${id}`)
}

// 创建租户
export function createTenant(data: TenantFormData) {
  return request.post<string>('/system/tenant', data)
}

// 更新租户
export function updateTenant(id: string, data: TenantFormData) {
  return request.put(`/system/tenant/${id}`, data)
}

// 删除租户
export function deleteTenant(id: string) {
  return request.delete(`/system/tenant/${id}`)
}

// 修改租户状态
export function updateTenantStatus(id: string, status: number) {
  return request.put(`/system/tenant/${id}/status`, null, { params: { status } })
}

// 检查编码是否存在
export function checkTenantCode(tenantCode: string, excludeId?: string) {
  return request.get<boolean>('/system/tenant/check-code', { params: { tenantCode, excludeId } })
}
