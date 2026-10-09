/**
 * 网盘驱动：把 VueFinder 的 Driver 契约映射到 /drive 接口
 *
 * 组件用「名称路径」表达位置（面包屑由路径字符串切分而来，见 VueFinder 的 fs store），
 * 而后端以节点编号定位内容，因此驱动维护 路径 → 节点编号 的映射：
 * 每次列表与新建都写入映射，操作前按映射解析编号，未命中则沿名称逐级下钻补齐。
 *
 * 驱动只做协议转换与路径解析，不承载权限判断与事务：写操作由后端拒绝并透出；
 * 目录角色只用于把只读目录的写入口隐藏掉（组件按 read_only 决定是否展示）。
 *
 * 空间固定在实例上，切换空间由页面重建驱动并重新挂载组件。
 */
import XHRUpload from '@uppy/xhr-upload'
import type {
  DeleteParams,
  DeleteResult,
  DirEntry,
  Driver,
  FileContentResult,
  FileOperationResult,
  FsData,
  ListParams,
  RenameParams,
  SearchParams,
  TransferParams
} from 'vuefinder'
import { useUserStore } from '@/stores/user'
import { DRIVE_ROOT_PARENT_ID } from '@/types/drive'
import type { DriveEntry, DriveId, DrivePermissionRole } from '@/types/drive'
import type { DriveGateway } from './drive-gateway'

const ROOT = '/'
const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'
const UNSUPPORTED = '网盘暂不支持该操作'

interface CachedEntry {
  id: DriveId
  type: DriveEntry['type']
  mimeType?: string
  favorite?: boolean
  /** 仅限定子树的口子返回；false 表示这次调用不能改名/移动/删除它 */
  modifiable?: boolean
}

export interface DriveDriverOptions {
  /** 接口口子：驱动的全部取数与写入都经它 */
  gateway: DriveGateway
  /** 组件内的 storage 标识 */
  storageName: string
  /** 根目录上的有效角色，决定根目录是否只读 */
  getRootRole: () => DrivePermissionRole | undefined
  /** 写操作成功后刷新空间用量，不重建当前驱动。 */
  onChanged?: () => void
}

export interface DriveDriver extends Driver {
  /** 取已缓存的节点编号，用于选中项的详情与权限抽屉 */
  peekId: (path: string) => DriveId | undefined
  /** 取已列出目录上的有效角色，用于按目录角色控制操作菜单 */
  peekRole: (path: string) => DrivePermissionRole | undefined
  /** 取已缓存节点的收藏状态，用于把菜单项显示成收藏或取消收藏 */
  peekFavorite: (path: string) => boolean | undefined
  /** 收藏状态变化后同步缓存，避免菜单标签停留在旧状态 */
  markFavorite: (path: string, favorite: boolean) => void
  /** 取已缓存节点能不能改名/移动/删除；没有这个信息（普通网盘口子）返回 undefined */
  peekModifiable: (path: string) => boolean | undefined
}

/** 去掉 storage:// 前缀，统一为 /a/b 形式（根为 /） */
export function normalizePath(path: string): string {
  const stripped = path.includes('://') ? path.slice(path.indexOf('://') + 3) : path
  const merged = `/${stripped}`.replace(/\/{2,}/g, '/')
  return merged.length > 1 && merged.endsWith('/') ? merged.slice(0, -1) : merged
}

function parentOf(path: string): string {
  const normalized = normalizePath(path)
  const index = normalized.lastIndexOf('/')
  return index <= 0 ? ROOT : normalized.slice(0, index)
}

function joinPath(dir: string, name: string): string {
  return dir === ROOT ? `/${name}` : `${dir}/${name}`
}

function extensionOf(name: string): string {
  const index = name.lastIndexOf('.')
  return index > 0 ? name.slice(index + 1) : ''
}

/** 组件按 Unix 秒格式化时间，平台 JSON 是毫秒时间戳 */
function toUnixSeconds(value?: number | null): number | null {
  return typeof value === 'number' && value > 0 ? Math.floor(value / 1000) : null
}

export function createDriveDriver(options: DriveDriverOptions): DriveDriver {
  /** 已列出的节点：路径 → 编号，重命名与移动后由各自的重新列表刷新 */
  const entryByPath = new Map<string, CachedEntry>()
  /** 目录路径上的角色，供外壳按当前目录控制操作菜单 */
  const roleByPath = new Map<string, DrivePermissionRole | undefined>()
  /** 目录编号 → 目录自身路径，供搜索结果还原父目录位置 */
  const dirPathByEntryId = new Map<string, string>()

  function toDirEntry(dir: string, entry: DriveEntry): DirEntry {
    const isFolder = entry.type === 'FOLDER'
    return {
      dir,
      basename: entry.name,
      extension: isFolder ? '' : extensionOf(entry.name),
      path: joinPath(dir, entry.name),
      storage: options.storageName,
      type: isFolder ? 'dir' : 'file',
      file_size: isFolder ? null : (entry.size ?? 0),
      last_modified: toUnixSeconds(entry.updateTime ?? entry.createTime),
      mime_type: isFolder ? null : (entry.mimeType ?? null),
      read_only: entry.modifiable === false,
      visibility: 'private'
    }
  }

  function rememberEntry(dir: string, entry: DriveEntry): void {
    roleByPath.set(joinPath(dir, entry.name), entry.role)
    entryByPath.set(joinPath(dir, entry.name), {
      id: entry.id,
      type: entry.type,
      mimeType: entry.mimeType,
      favorite: entry.favorite,
      modifiable: entry.modifiable
    })
    if (entry.type === 'FOLDER') dirPathByEntryId.set(String(entry.id), joinPath(dir, entry.name))
  }

  /** 目录改名、移动、删除后，整棵旧路径缓存都失效，不能继续按旧地址操作真实节点。 */
  function forgetPath(path: string): void {
    const normalized = normalizePath(path)
    for (const [cachedPath, entry] of entryByPath) {
      if (cachedPath === normalized || cachedPath.startsWith(`${normalized}/`)) {
        entryByPath.delete(cachedPath)
        dirPathByEntryId.delete(String(entry.id))
      }
    }
    for (const cachedPath of roleByPath.keys()) {
      if (cachedPath === normalized || cachedPath.startsWith(`${normalized}/`)) roleByPath.delete(cachedPath)
    }
  }

  async function fetchChildren(parentId: DriveId): Promise<DriveEntry[]> {
    return options.gateway.list(parentId)
  }

  /** 解析路径对应的节点编号；根目录固定为 0 */
  async function resolveId(path: string): Promise<DriveId> {
    const normalized = normalizePath(path)
    if (normalized === ROOT) return DRIVE_ROOT_PARENT_ID
    const cached = entryByPath.get(normalized)
    if (cached) return cached.id
    // 未命中说明该路径不是本次列表返回的项（如刚被重命名或来自其它目录），按名称逐级下钻补齐
    let parentId: DriveId = DRIVE_ROOT_PARENT_ID
    let current = ROOT
    for (const segment of normalized.split('/').filter(Boolean)) {
      const nextPath = joinPath(current, segment)
      const hit = entryByPath.get(nextPath)
      if (hit) {
        parentId = hit.id
        current = nextPath
        continue
      }
      const children = await fetchChildren(parentId)
      children.forEach(child => rememberEntry(current, child))
      const found = children.find(child => child.name === segment)
      if (!found) throw new Error(`未找到节点：${path}`)
      parentId = found.id
      current = nextPath
    }
    return parentId
  }

  /** 目录自身角色决定组件是否暴露写操作 */
  async function resolveReadOnly(dir: string, dirId: DriveId): Promise<boolean> {
    if (dirId === DRIVE_ROOT_PARENT_ID) {
      const rootRole = options.getRootRole()
      roleByPath.set(ROOT, rootRole)
      return rootRole !== 'EDITOR' && rootRole !== 'MANAGER'
    }
    // 刷新目录时同步权限，避免另一会话撤销编辑权后界面仍暴露写操作。
    const { role } = await options.gateway.get(dirId)
    roleByPath.set(dir, role)
    return role !== 'EDITOR' && role !== 'MANAGER'
  }

  async function reload(path: string): Promise<FileOperationResult> {
    const listing = await list({ path })
    // 写操作的结果契约把 read_only 视为必填，这里补成确定值
    return {
      files: listing.files,
      storages: listing.storages,
      dirname: listing.dirname,
      read_only: Boolean(listing.read_only)
    }
  }

  /** 搜索结果可能落在任意子目录，用面包屑把父节点还原成名称路径 */
  async function resolveDirPath(parentId: DriveId, currentDir: string, currentDirId: DriveId): Promise<string> {
    if (String(parentId) === String(currentDirId)) return currentDir
    if (String(parentId) === String(DRIVE_ROOT_PARENT_ID)) return ROOT
    const key = String(parentId)
    const cached = dirPathByEntryId.get(key)
    if (cached) return cached
    const path = await options.gateway.path(parentId)
    dirPathByEntryId.set(key, path)
    return path
  }

  async function list(params?: ListParams): Promise<FsData> {
    const dir = normalizePath(params?.path ?? ROOT)
    const dirId = await resolveId(dir)
    const entries = await fetchChildren(dirId)
    // 列表是当前目录的权威快照，清掉已删除、已改名或失去权限的节点及其子路径。
    const currentEntries = new Map(entries.map(entry => [joinPath(dir, entry.name), String(entry.id)]))
    for (const [path, cached] of entryByPath) {
      if (parentOf(path) === dir && currentEntries.get(path) !== String(cached.id)) forgetPath(path)
    }
    const files = entries.map(entry => toDirEntry(dir, entry))
    entries.forEach(entry => rememberEntry(dir, entry))
    dirPathByEntryId.set(String(dirId), dir)
    return {
      storages: [options.storageName],
      // VueFinder 将 dirname 解析为 storage://path；裸路径会被误显示为存储名称。
      dirname: `${options.storageName}://${dir.slice(1)}`,
      files,
      read_only: await resolveReadOnly(dir, dirId)
    }
  }

  return {
    list,

    peekId(path: string): DriveId | undefined {
      return entryByPath.get(normalizePath(path))?.id
    },

    peekRole(path: string): DrivePermissionRole | undefined {
      return roleByPath.get(normalizePath(path))
    },

    peekFavorite(path: string): boolean | undefined {
      return entryByPath.get(normalizePath(path))?.favorite
    },

    markFavorite(path: string, favorite: boolean): void {
      const cached = entryByPath.get(normalizePath(path))
      if (cached) cached.favorite = favorite
    },

    peekModifiable(path: string): boolean | undefined {
      return entryByPath.get(normalizePath(path))?.modifiable
    },

    async delete({ path, items }: DeleteParams): Promise<DeleteResult> {
      const dir = normalizePath(path)
      const ids: DriveId[] = []
      for (const item of items) {
        ids.push(await resolveId(item.path))
      }
      if (ids.length) {
        await options.gateway.trash(ids)
        options.onChanged?.()
        items.forEach(item => forgetPath(item.path))
      }
      return { ...(await reload(dir)), deleted: [] }
    },

    async rename({ path, item, name }: RenameParams): Promise<FileOperationResult> {
      const itemPath = normalizePath(item || path)
      const id = await resolveId(itemPath)
      await options.gateway.rename(id, name)
      forgetPath(itemPath)
      return reload(parentOf(itemPath))
    },

    async move({ path, sources, destination }: TransferParams): Promise<FileOperationResult> {
      const dir = normalizePath(path ?? ROOT)
      const targetParentId = await resolveId(destination)
      // 口子能「先拦」时整批先查一遍：有一个不能动就整批不发（与删除一致），不会先移走一部分
      if (options.gateway.checkModifiable) {
        const ids: DriveId[] = []
        for (const source of sources) ids.push(await resolveId(source))
        options.gateway.checkModifiable(ids)
      }
      for (const source of sources) {
        const id = await resolveId(source)
        await options.gateway.move(id, targetParentId)
        forgetPath(source)
      }
      return reload(dir)
    },

    async copy({ path, sources, destination }: TransferParams): Promise<FileOperationResult> {
      const dir = normalizePath(path ?? ROOT)
      const targetParentId = await resolveId(destination)
      for (const source of sources) {
        await options.gateway.copy(await resolveId(source), targetParentId)
        options.onChanged?.()
      }
      return reload(dir)
    },

    async createFolder({ path, name }): Promise<FileOperationResult> {
      const dir = normalizePath(path)
      const parentId = await resolveId(dir)
      const id = await options.gateway.createFolder(parentId, name)
      entryByPath.set(joinPath(dir, name), { id, type: 'FOLDER' })
      return reload(dir)
    },

    async createFile(): Promise<FileOperationResult> {
      throw new Error('网盘不支持在线新建文件，请直接上传')
    },

    async archive(): Promise<FileOperationResult> {
      throw new Error(UNSUPPORTED)
    },

    async unarchive(): Promise<FileOperationResult> {
      throw new Error(UNSUPPORTED)
    },

    async save(): Promise<string> {
      throw new Error(UNSUPPORTED)
    },

    async getContent({ path }): Promise<FileContentResult> {
      const normalized = normalizePath(path)
      const id = await resolveId(normalized)
      const blob = await options.gateway.content(id, true)
      return {
        content: await blob.text(),
        mimeType: entryByPath.get(normalized)?.mimeType || 'text/plain'
      }
    },

    getPreviewUrl({ path }): string {
      const cached = entryByPath.get(normalizePath(path))
      return cached ? options.gateway.contentUrl(cached.id, true) : ''
    },

    getDownloadUrl({ path }): string {
      const cached = entryByPath.get(normalizePath(path))
      return cached ? options.gateway.contentUrl(cached.id, false) : ''
    },

    async search({ path, filter, deep, size }: SearchParams): Promise<DirEntry[]> {
      const dir = normalizePath(path ?? ROOT)
      const keyword = filter.trim()
      if (!keyword) return []
      const currentDirId = await resolveId(dir)
      const results = await options.gateway.search(keyword, 100)
      const entries: DirEntry[] = []
      for (const entry of results) {
        // 搜索弹窗明确提供当前目录、是否含子目录和大小筛选，不能静默按全空间返回。
        if (!deep && String(entry.parentId) !== String(currentDirId)) continue
        if (size && size !== 'all') {
          if (entry.type !== 'FILE') continue
          const bytes = entry.size || 0
          const mb = 1024 * 1024
          if (size === 'small' && bytes >= mb) continue
          if (size === 'medium' && (bytes < mb || bytes > 10 * mb)) continue
          if (size === 'large' && bytes <= 10 * mb) continue
        }
        const parentPath = await resolveDirPath(entry.parentId, dir, currentDirId)
        if (dir !== ROOT && parentPath !== dir && !parentPath.startsWith(`${dir}/`)) continue
        const dirEntry = toDirEntry(parentPath, entry)
        rememberEntry(parentPath, entry)
        entries.push(dirEntry)
      }
      return entries
    },

    configureUploader(uppy, context) {
      uppy.use(XHRUpload, {
        endpoint: `${API_BASE}${options.gateway.upload.endpoint}`,
        method: 'POST',
        fieldName: 'file',
        bundle: false,
        formData: true,
        headers: () => ({ Authorization: `Bearer ${useUserStore().token || ''}` }),
        // 底座业务拒绝仍返回 HTTP 200，必须解析 Result，不能交给上传器当成成功。
        getResponseData(xhr: XMLHttpRequest) {
          let result
          try {
            result = xhr.responseType === 'json' ? xhr.response : JSON.parse(xhr.responseText)
          } catch {
            throw new Error('上传响应无效，请刷新后重试')
          }
          if (!result || typeof result.code !== 'number') throw new Error('上传响应无效，请刷新后重试')
          if (result.code !== 0) throw new Error(result.msg || '上传失败，请重试')
          return result
        }
      })
      // 平台上传接口按 spaceId + parentId 落库；两者作为元数据随表单一并提交。
      // 目标目录必然是刚列出的目录，直接用缓存解析，避免上传时与元数据写入竞争。
      uppy.on('upload', () => {
        options.gateway.upload.check?.()
        const targetPath = normalizePath(context.getTargetPath())
        const targetId = targetPath === ROOT ? DRIVE_ROOT_PARENT_ID : entryByPath.get(targetPath)?.id
        if (targetId === undefined) {
          throw new Error(`未定位到上传目录：${context.getTargetPath()}`)
        }
        const meta = options.gateway.upload.fields(targetId)
        uppy.getFiles().forEach((file: { id: string }) => uppy.setFileMeta(file.id, meta))
      })
      uppy.on('upload-success', () => options.onChanged?.())
    }
  }
}
