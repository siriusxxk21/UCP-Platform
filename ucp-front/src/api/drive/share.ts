import request from '@/utils/request'
import type { ListResult, PageParams } from '@/composables/useOsTablePage'
import type { DriveId, DriveShare, DriveShareQuery, DriveShareSave } from '@/types/drive'

export function createDriveShare(data: DriveShareSave) {
  return request.post<DriveId>('/drive/share/create', data)
}

export function updateDriveShare(data: DriveShareSave) {
  return request.put<boolean>('/drive/share/update', data)
}

export function revokeDriveShare(id: DriveId) {
  return request.delete<boolean>('/drive/share/revoke', { params: { id } })
}

/** 我发起的分享，支持按状态与创建时间筛选 */
export function getDriveSharePage({ pageNum, pageSize, ...params }: DriveShareQuery & PageParams) {
  return request.get<ListResult<DriveShare>>('/drive/share/page', {
    params: { ...params, pageNo: pageNum, pageSize }
  })
}

/** 节点上生效的分享 */
export function getDriveShareListByEntry(entryId: DriveId) {
  return request.get<DriveShare[]>('/drive/share/list-by-entry', { params: { entryId } })
}

/** 与我共享：含直接分享给我与分享给我所在部门的节点 */
export function getSharedToMeList() {
  return request.get<DriveShare[]>('/drive/share/shared-to-me')
}
