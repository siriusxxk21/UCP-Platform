import type { PageParam, PageResult } from '@/types/request'

import request from '@/utils/request'

type BpmId = string | number

export namespace BpmCategoryApi {
  /** 流程分类 */
  export interface Category {
    id: BpmId
    name: string
    code: string
    status: number
    description?: string
    sort: number
    createTime?: string | number
  }
}

/** 查询流程分类分页 */
export async function getCategoryPage(params: PageParam) {
  return request.get<PageResult<BpmCategoryApi.Category>>('/bpm/category/page', { params })
}

/** 查询流程分类详情 */
export async function getCategory(id: BpmId) {
  return request.get<BpmCategoryApi.Category>(`/bpm/category/get?id=${id}`)
}

/** 新增流程分类 */
export async function createCategory(data: BpmCategoryApi.Category) {
  return request.post<number>('/bpm/category/create', data)
}

/** 修改流程分类 */
export async function updateCategory(data: BpmCategoryApi.Category) {
  return request.put<boolean>('/bpm/category/update', data)
}

/** 删除流程分类 */
export async function deleteCategory(id: BpmId) {
  return request.delete<boolean>(`/bpm/category/delete?id=${id}`)
}

/** 查询流程分类列表 */
export async function getCategorySimpleList() {
  return request.get<BpmCategoryApi.Category[]>(`/bpm/category/simple-list`)
}

/** 批量修改流程分类的排序 */
export async function updateCategorySortBatch(ids: BpmId[]) {
  const params = ids.join(',')
  return request.put<boolean>(`/bpm/category/update-sort-batch?ids=${params}`)
}
