import type { PageParam, PageResult } from '@/types/request'

import request from '@/utils/request'

export namespace BpmUserGroupApi {
  /** 用户组 */
  export interface UserGroup {
    id: number
    name: string
    description: string
    userIds: number[]
    status: number
    remark: string
    createTime: string
  }
}

/** 查询用户组分页 */
export async function getUserGroupPage(params: PageParam) {
  return request.get<PageResult<BpmUserGroupApi.UserGroup>>('/bpm/user-group/page', { params })
}

/** 查询用户组详情 */
export async function getUserGroup(id: number) {
  return request.get<BpmUserGroupApi.UserGroup>(`/bpm/user-group/get?id=${id}`)
}

/** 新增用户组 */
export async function createUserGroup(data: BpmUserGroupApi.UserGroup) {
  return request.post<number>('/bpm/user-group/create', data)
}

/** 修改用户组 */
export async function updateUserGroup(data: BpmUserGroupApi.UserGroup) {
  return request.put<boolean>('/bpm/user-group/update', data)
}

/** 删除用户组 */
export async function deleteUserGroup(id: number) {
  return request.delete<boolean>(`/bpm/user-group/delete?id=${id}`)
}

/** 查询用户组列表 */
export async function getUserGroupSimpleList() {
  return request.get<BpmUserGroupApi.UserGroup[]>(`/bpm/user-group/simple-list`)
}
