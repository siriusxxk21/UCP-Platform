import request from '@/utils/request'
import type { DriveId, DriveMarkedEntry } from '@/types/drive'

export function getFavoriteList() {
  return request.get<DriveMarkedEntry[]>('/drive/mark/favorite-list')
}

export function getRecentList(limit?: number) {
  return request.get<DriveMarkedEntry[]>('/drive/mark/recent-list', { params: { limit } })
}

export function updateFavorite(entryId: DriveId, favorite: boolean) {
  return request.put<boolean>('/drive/mark/favorite', undefined, { params: { entryId, favorite } })
}

/** 记录访问，用于「最近使用」列表；重复访问只刷新时间 */
export function recordDriveAccess(entryId: DriveId) {
  return request.post<boolean>('/drive/mark/access', undefined, { params: { entryId } })
}
