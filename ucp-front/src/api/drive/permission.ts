import request from '@/utils/request'
import type { DriveId, DrivePermission, DrivePermissionSave, DrivePermissionSource } from '@/types/drive'

export function getDrivePermissionList(params: { spaceId: DriveId; entryId: DriveId }) {
  return request.get<DrivePermission[]>('/drive/permission/list', { params })
}

/** 授权来源：含空间归属主体与继承而来的授权，用于说明有效权限出处 */
export function getDrivePermissionSourceList(params: { spaceId: DriveId; entryId: DriveId }) {
  return request.get<DrivePermissionSource[]>('/drive/permission/source-list', { params })
}

export function saveDrivePermission(data: DrivePermissionSave) {
  return request.post<DriveId>('/drive/permission/save', data)
}

export function deleteDrivePermission(id: DriveId) {
  return request.delete<boolean>('/drive/permission/delete', { params: { id } })
}
