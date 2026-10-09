/**
 * 网盘接口口子：文件浏览器（驱动与详情抽屉）取数的唯一出口
 *
 * 浏览器本身不关心内容从哪组接口来：网盘页面用 createDriveGateway（/drive/entry/*，按空间取数），
 * 业务表单下方用另一份实现（按记录凭据取数，根目录由服务端锁定）。两边的根目录都是编号 0。
 */
import {
  DRIVE_ENTRY_UPLOAD_PATH,
  buildDriveContentUrl,
  copyDriveEntry,
  createDriveFolder,
  getDriveEntry,
  getDriveBreadcrumb,
  getDriveEntryContent,
  getDriveEntryList,
  getDriveEntryPath,
  moveDriveEntry,
  renameDriveEntry,
  searchDriveEntry,
  trashDriveEntries
} from '@/api/drive/entry'
import { recordDriveAccess, updateFavorite } from '@/api/drive/mark'
import type { DriveEntry, DriveId } from '@/types/drive'

export interface DriveGateway {
  list(parentId: DriveId): Promise<DriveEntry[]>
  get(id: DriveId): Promise<DriveEntry>
  /** 相对当前根的名称路径；根的直接子节点返回 '/' */
  path(id: DriveId): Promise<string>
  /** 从已授权节点获取父路径，避免额外要求上级目录读取权。 */
  parentPath?(id: DriveId): Promise<string>
  createFolder(parentId: DriveId, name: string): Promise<DriveId>
  rename(id: DriveId, name: string): Promise<void>
  move(id: DriveId, targetParentId: DriveId): Promise<void>
  copy(id: DriveId, targetParentId: DriveId): Promise<void>
  trash(ids: DriveId[]): Promise<void>
  search(name: string, limit: number): Promise<DriveEntry[]>
  content(id: DriveId, inline: boolean): Promise<Blob>
  contentUrl(id: DriveId, inline: boolean): string
  upload: {
    endpoint: string
    fields(parentId: DriveId): Record<string, string>
    /** 开始上传前先查一遍：抛出 ⇒ 这一批一个都不传，上传框里显示抛出的那句话。普通网盘口子不提供 */
    check?(): void
  }
  /**
   * 批量改动（移动）之前整批查一遍：有一个已知不能改就抛出、整批不发。普通网盘口子不提供（由服务端逐个判）。
   */
  checkModifiable?(ids: DriveId[]): void
  /** 没有收藏能力的口子不提供这两个 */
  favorite?(id: DriveId, favorite: boolean): Promise<void>
  recordAccess?(id: DriveId): Promise<void>
}

/** 普通网盘的口子：逐个包一层现有接口，行为与直接调用相同 */
export function createDriveGateway(spaceId: DriveId): DriveGateway {
  return {
    list: parentId => getDriveEntryList({ spaceId, parentId }),
    get: id => getDriveEntry(id),
    path: id => getDriveEntryPath(id),
    async parentPath(id) {
      const chain = await getDriveBreadcrumb(id)
      return (
        chain
          .slice(0, -1)
          .filter(node => String(node.id) !== '0')
          .map(node => node.name)
          .join(' / ') || '根目录'
      )
    },
    createFolder: (parentId, name) => createDriveFolder({ spaceId, parentId, name }),
    async rename(id, name) {
      await renameDriveEntry({ id, name })
    },
    async move(id, targetParentId) {
      await moveDriveEntry({ id, targetParentId })
    },
    async copy(id, targetParentId) {
      await copyDriveEntry({ id, targetSpaceId: spaceId, targetParentId })
    },
    async trash(ids) {
      await trashDriveEntries(ids)
    },
    search: (name, limit) => searchDriveEntry({ spaceId, name, limit }),
    content: (id, inline) => getDriveEntryContent(id, inline),
    contentUrl: (id, inline) => buildDriveContentUrl(id, inline),
    upload: {
      endpoint: DRIVE_ENTRY_UPLOAD_PATH,
      fields: parentId => ({ spaceId: String(spaceId), parentId: String(parentId) })
    },
    async favorite(id, favorite) {
      await updateFavorite(id, favorite)
    },
    async recordAccess(id) {
      await recordDriveAccess(id)
    }
  }
}
