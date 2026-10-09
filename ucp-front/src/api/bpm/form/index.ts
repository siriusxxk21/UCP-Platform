import type { PageParam, PageResult } from '@/types/request'

import request from '@/utils/request'

export namespace BpmFormApi {
  /** 流程表单 */
  export interface Form {
    id?: string | number
    name: string
    conf: string
    fields: string[]
    status: number
    remark: string
    createTime: string | number
  }
}

/** 获取表单分页列表 */
export async function getFormPage(params: PageParam) {
  return request.get<PageResult<BpmFormApi.Form>>('/bpm/form/page', {
    params
  })
}

/** 获取表单详情 */
export async function getForm(id: string | number) {
  return request.get<BpmFormApi.Form>(`/bpm/form/get?id=${id}`)
}

/** 创建表单 */
export async function createForm(data: BpmFormApi.Form) {
  return request.post('/bpm/form/create', data)
}

/** 更新表单 */
export async function updateForm(data: BpmFormApi.Form) {
  return request.put('/bpm/form/update', data)
}

/** 删除表单 */
export async function deleteForm(id: string | number) {
  return request.delete(`/bpm/form/delete?id=${id}`)
}

/** 获取表单简单列表 */
export async function getFormSimpleList() {
  return request.get<BpmFormApi.Form[]>('/bpm/form/simple-list')
}
