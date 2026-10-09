import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import { DRIVE_ROOT_PARENT_ID } from '@/types/drive'
import type { DriveBreadcrumb, DriveEntry, DriveEntryQuery, DriveId, DriveTrashQuery } from '@/types/drive'

export const DRIVE_ENTRY_CONTENT_PATH = '/drive/entry/content'
export const DRIVE_ENTRY_UPLOAD_PATH = '/drive/entry/upload'

export function getDriveEntryList(params: DriveEntryQuery) {
  return request.get<DriveEntry[]>('/drive/entry/list', { params })
}

export function searchDriveEntry(params: { spaceId: DriveId; name: string; limit?: number }) {
  return request.get<DriveEntry[]>('/drive/entry/search', { params })
}

export function getDriveEntry(id: DriveId) {
  return request.get<DriveEntry>('/drive/entry/get', { params: { id } })
}

export function getDriveBreadcrumb(entryId: DriveId) {
  return request.get<DriveBreadcrumb[]>('/drive/entry/breadcrumb', { params: { entryId } })
}

/**
 * 把节点位置还原成工作区使用的名称路径
 *
 * VueFinder 的面包屑由路径字符串切分而来，目录必须以 /a/b 的名称路径表达；
 * 深链定位与搜索结果都需要这层转换，因此和驱动共用同一实现。
 */
export async function getDriveEntryPath(entryId: DriveId): Promise<string> {
  const chain = await getDriveBreadcrumb(entryId)
  const names = (chain || [])
    .filter(node => String(node.id) !== String(DRIVE_ROOT_PARENT_ID))
    .map(node => node.name)
    .filter(Boolean)
  return names.length ? `/${names.join('/')}` : '/'
}

export function createDriveFolder(data: { spaceId: DriveId; parentId: DriveId; name: string }) {
  return request.post<DriveId>('/drive/entry/create-folder', data)
}

export function renameDriveEntry(data: { id: DriveId; name: string }) {
  return request.put<boolean>('/drive/entry/rename', data)
}

export function moveDriveEntry(data: { id: DriveId; targetParentId: DriveId }) {
  return request.put<boolean>('/drive/entry/move', data)
}

export function updateDriveEntryInherit(data: { id: DriveId; inheritParent: boolean }) {
  return request.put<boolean>('/drive/entry/inherit', data)
}

export function copyDriveEntry(data: { id: DriveId; targetSpaceId: DriveId; targetParentId: DriveId }) {
  return request.post<DriveId>('/drive/entry/copy', data)
}

export function trashDriveEntries(ids: DriveId[]) {
  return request.put<boolean>('/drive/entry/trash', { ids })
}

export function restoreDriveEntry(id: DriveId) {
  return request.put<DriveEntry>('/drive/entry/restore', undefined, { params: { id } })
}

export function purgeDriveEntries(ids: DriveId[]) {
  return request.delete<boolean>('/drive/entry/purge', { data: { ids } })
}

export function getDriveTrashList(params: DriveTrashQuery) {
  return request.get<DriveEntry[]>('/drive/entry/trash-list', { params })
}

export function getDriveEntryContent(id: DriveId, inline = false) {
  return request.get<Blob>(DRIVE_ENTRY_CONTENT_PATH, {
    params: { id, inline },
    responseType: 'blob'
  })
}

/**
 * 拼接可直接交给浏览器加载的内容地址。
 *
 * 图片、音视频预览与下载链接由组件或浏览器直接发起，带不上 axios 的 Authorization 头，
 * 因此按平台既有约定把访问令牌放到 token 查询参数上（后端 Header > Parameter 均可识别）。
 */
export function buildDriveContentUrl(id: DriveId, inline = false) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = useUserStore().token || ''
  const params = new URLSearchParams({ id: String(id), inline: inline ? 'true' : 'false' })
  if (token) params.set('token', token)
  return `${base}${DRIVE_ENTRY_CONTENT_PATH}?${params.toString()}`
}
