import request from '@/utils/request'
import type { DriveId, DriveSpace, DriveSpaceSave } from '@/types/drive'

/** 我的空间：含自动建立的个人空间与已获授权的团队空间 */
export function getMySpaceList() {
  return request.get<DriveSpace[]>('/drive/space/my-list')
}

export function getDriveSpace(id: DriveId) {
  return request.get<DriveSpace>('/drive/space/get', { params: { id } })
}

export function createDriveSpace(data: DriveSpaceSave) {
  return request.post<DriveId>('/drive/space/create', data)
}

export function updateDriveSpace(data: DriveSpaceSave) {
  return request.put<boolean>('/drive/space/update', data)
}

export function deleteDriveSpace(id: DriveId) {
  return request.delete<boolean>('/drive/space/delete', { params: { id } })
}

/** 我能进入的业务空间（文件夹区）：只含在空间根上有角色的业务空间 */
export function getBusinessSpaceList() {
  return request.get<DriveSpace[]>('/drive/space/business-list')
}

/** 空间治理列表：仅具有新建或编辑权限的管理用户调用。 */
export function getManageSpaceList() {
  return request.get<DriveSpace[]>('/drive/space/manage-list')
}

/** 业务空间独立新建契约，不建立成员 ACL，不与团队空间互转。 */
export function createBusinessSpace(data: Pick<DriveSpaceSave, 'name' | 'quotaBytes'>) {
  return request.post<DriveId>('/drive/space/create-business', data)
}
