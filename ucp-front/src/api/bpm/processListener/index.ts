import type { PageParam, PageResult } from '@/types/request'

import request from '@/utils/request'

export namespace BpmProcessListenerApi {
  /** 流程监听器 */
  export interface ProcessListener {
    id: number // 编号
    name: string // 监听器名字
    type: string // 监听器类型
    status: number // 监听器状态
    event: string // 监听事件
    valueType: string // 监听器值类型
    value: string // 监听器值
  }
}

/** 查询流程监听器分页 */
export async function getProcessListenerPage(params: PageParam) {
  return request.get<PageResult<BpmProcessListenerApi.ProcessListener>>('/bpm/process-listener/page', { params })
}

/** 查询流程监听器详情 */
export async function getProcessListener(id: number) {
  return request.get<BpmProcessListenerApi.ProcessListener>(`/bpm/process-listener/get?id=${id}`)
}

/** 新增流程监听器 */
export async function createProcessListener(data: BpmProcessListenerApi.ProcessListener) {
  return request.post<number>('/bpm/process-listener/create', data)
}

/** 修改流程监听器 */
export async function updateProcessListener(data: BpmProcessListenerApi.ProcessListener) {
  return request.put<boolean>('/bpm/process-listener/update', data)
}

/** 删除流程监听器 */
export async function deleteProcessListener(id: number) {
  return request.delete<boolean>(`/bpm/process-listener/delete?id=${id}`)
}
