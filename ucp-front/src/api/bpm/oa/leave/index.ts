import type { PageParam, PageResult } from '@/types/request'

import request from '@/utils/request'

export namespace BpmOALeaveApi {
  export interface Leave {
    id: number
    status: number
    type: number
    reason: string
    processInstanceId: string
    startTime: number
    endTime: number
    createTime: Date
  }
}

/** 创建请假申请 */
export async function createLeave(data: BpmOALeaveApi.Leave) {
  return request.post('/bpm/oa/leave/create', data)
}

/** 获得请假申请 */
export async function getLeave(id: number) {
  return request.get<BpmOALeaveApi.Leave>(`/bpm/oa/leave/get?id=${id}`)
}

/** 获得请假申请分页 */
export async function getLeavePage(params: PageParam) {
  return request.get<PageResult<BpmOALeaveApi.Leave>>('/bpm/oa/leave/page', { params })
}
