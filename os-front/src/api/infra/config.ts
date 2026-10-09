import request from '@/utils/request'

export const INFRA_CONFIG_TYPE = {
  SYSTEM: 1,
  CUSTOM: 2
} as const

export type InfraConfigType = (typeof INFRA_CONFIG_TYPE)[keyof typeof INFRA_CONFIG_TYPE]
export type InfraConfigId = string | number

/** 平台参数配置。type 由后端维护，创建时固定为自定义配置。 */
export interface InfraConfig {
  id?: InfraConfigId
  category: string
  name: string
  key: string
  value: string
  type?: InfraConfigType
  visible: boolean
  remark?: string
  createTime?: string
}

export interface InfraConfigPageResult {
  list: InfraConfig[]
  total: number
}

export interface InfraConfigQuery {
  pageNum: number
  pageSize: number
  name?: string
  key?: string
  type?: InfraConfigType
  createTime?: [string, string]
}

export type InfraConfigSaveParams = Omit<InfraConfig, 'createTime' | 'type'>

/** 查询参数配置分页列表。 */
export function getConfigPage(params: InfraConfigQuery) {
  const { pageNum, ...rest } = params
  return request.get<InfraConfigPageResult>('/infra/config/page', {
    params: { ...rest, pageNo: pageNum },
    paramsSerializer: { indexes: null }
  })
}

/** 查询参数配置详情。 */
export function getConfig(id: InfraConfigId) {
  return request.get<InfraConfig>('/infra/config/get', { params: { id } })
}

/** 根据参数键名查询可见的参数值。 */
export function getConfigValueByKey(key: string) {
  return request.get<string | null>('/infra/config/get-value-by-key', { params: { key } })
}

/** 创建自定义参数配置。 */
export function createConfig(data: InfraConfigSaveParams) {
  return request.post<InfraConfigId>('/infra/config/create', data)
}

/** 更新参数配置。 */
export function updateConfig(data: InfraConfigSaveParams) {
  return request.put<boolean>('/infra/config/update', data)
}

/** 删除自定义参数配置。 */
export function deleteConfig(id: InfraConfigId) {
  return request.delete<boolean>('/infra/config/delete', { params: { id } })
}

/** 批量删除自定义参数配置。 */
export function deleteConfigList(ids: InfraConfigId[]) {
  return request.delete<boolean>('/infra/config/delete-list', {
    params: { ids: ids.join(',') }
  })
}

/** 导出符合当前查询条件的参数配置。 */
export function exportConfig(params: Omit<InfraConfigQuery, 'pageNum' | 'pageSize'>) {
  return request.get<Blob>('/infra/config/export-excel', {
    params,
    paramsSerializer: { indexes: null },
    responseType: 'blob'
  })
}
