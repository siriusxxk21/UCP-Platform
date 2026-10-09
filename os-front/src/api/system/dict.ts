import request from '@/utils/request'
import type { ListResult, PageParams } from '@/composables/useOsTablePage'
import type {
  DictData,
  DictDataQuery,
  DictDataSave,
  DictId,
  DictType,
  DictTypeQuery,
  DictTypeSave
} from '@/types/system/dict'

/** 表格底座使用 pageNum，系统管理接口使用 pageNo，仅在 API 边界转换。 */
export function getDictTypePage({ pageNum, ...params }: DictTypeQuery & PageParams) {
  return request.get<ListResult<DictType>>('/system/dict-type/page', { params: { ...params, pageNo: pageNum } })
}

export function getDictType(id: DictId) {
  return request.get<DictType | null>('/system/dict-type/get', { params: { id } })
}

export function createDictType(data: DictTypeSave) {
  return request.post<DictId>('/system/dict-type/create', data)
}

export function updateDictType(data: DictTypeSave) {
  return request.put<boolean>('/system/dict-type/update', data)
}

export function deleteDictTypes(ids: DictId[]) {
  return request.delete<boolean>('/system/dict-type/delete-list', { params: { ids: ids.join(',') } })
}

export function exportDictTypes(params: DictTypeQuery) {
  return request.get<Blob>('/system/dict-type/export-excel', { params, responseType: 'blob' })
}

export function getDictDataPage({ pageNum, ...params }: DictDataQuery & PageParams) {
  return request.get<ListResult<DictData>>('/system/dict-data/page', { params: { ...params, pageNo: pageNum } })
}

export function getDictData(id: DictId) {
  return request.get<DictData | null>('/system/dict-data/get', { params: { id } })
}

export function createDictData(data: DictDataSave) {
  return request.post<DictId>('/system/dict-data/create', data)
}

export function updateDictData(data: DictDataSave) {
  return request.put<boolean>('/system/dict-data/update', data)
}

export function deleteDictData(ids: DictId[]) {
  return request.delete<boolean>('/system/dict-data/delete-list', { params: { ids: ids.join(',') } })
}

export function exportDictData(params: DictDataQuery) {
  return request.get<Blob>('/system/dict-data/export-excel', { params, responseType: 'blob' })
}
