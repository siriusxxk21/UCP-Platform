import type { RecordFolderApi } from '@/api/nocode/record-folder'
import type { DriveEntry, DriveId } from '@/types/drive'
import type {
  RecordFolderBackfillTotal,
  RecordFolderCredential,
  RecordFolderEntry,
  RecordFolderNameField,
  RecordFolderNameTemplate,
  RecordFolderTab
} from '@/types/nocode/record-folder'
import type { DriveGateway } from '@/views/drive/driver/drive-gateway'

/**
 * 记录文件夹：表单下方那一块的纯逻辑，以及把 /nocode/record-folder 接成网盘浏览器「接口口子」的那一份实现。
 *
 * 浏览器与网盘页面是同一个组件，这里只换取数的口子：每个请求都带凭据（应用、对象、记录、来源），
 * 根目录的编号恒为 0，由服务端换成这条记录的文件夹。
 */
export const RECORD_FOLDER_LOCKED_NOTE = '只读：不是经由这条记录放进去的'
export const RECORD_FOLDER_LOCKED_REASON = '这个文件不是经由这条记录放进去的，只能查看和下载，不能改名、移动或删除。'
/** 只读页签（不能改这条记录、在文件夹上也没有网盘编辑权限）的锁说明：此时每一项都带锁，原因不是来源而是这个人只能看 */
export const RECORD_FOLDER_VIEW_ONLY_NOTE = '只读：你只能查看这条记录的文件'
export const RECORD_FOLDER_VIEW_ONLY_REASON = '你只能查看和下载这条记录的文件，不能上传、新建、改名、移动或删除。'

const UPLOAD_PATH = '/nocode/record-folder/entry/upload'
const CONTENT_PATH = '/nocode/record-folder/entry/content'
const BACKFILL_PAGE = 100
const BACKFILL_FAILURES = 20
const NO_SOURCE_TTL = 5 * 60 * 1000

/** 凭据四项；应用编号为空（数据维护入口）时不带这一项 */
function credentialFields(credential: RecordFolderCredential): RecordFolderCredential {
  const { applicationId, objectId, recordId, sourceId } = credential
  return applicationId ? { applicationId, objectId, recordId, sourceId } : { objectId, recordId, sourceId }
}

function toDriveEntry(entry: RecordFolderEntry): DriveEntry {
  return {
    id: entry.id,
    spaceId: entry.spaceId,
    parentId: entry.parentId,
    name: entry.name,
    type: entry.type,
    size: entry.size ?? 0,
    mimeType: entry.mimeType ?? undefined,
    // 这组接口不回「是否继承上级授权」；文件夹里的节点由系统或表单建立，按默认的继承呈现
    inheritParent: true,
    role: entry.role ?? undefined,
    creator: entry.creator ?? undefined,
    createTime: entry.createTime ?? undefined,
    updateTime: entry.updateTime ?? undefined,
    trashedAt: entry.trashedAt ?? undefined,
    trashedBy: entry.trashedBy ?? undefined,
    modifiable: entry.modifiable
  }
}

export function createRecordFolderGateway(
  api: RecordFolderApi,
  credential: RecordFolderCredential,
  auth: { apiBase: string; token: () => string },
  access: { canWrite?: boolean } = {}
): DriveGateway {
  const base = credentialFields(credential)
  /** 这个人在这个页签里能不能写（服务端 open 给的 canWrite）；不给按能写，由服务端判 */
  const canWrite = access.canWrite !== false
  /** 节点编号 → 能不能改：来自列表、搜索、详情的结果；自己新建的记为可改 */
  const modifiable = new Map<string, boolean>()
  const remember = (entry: RecordFolderEntry): DriveEntry => {
    modifiable.set(String(entry.id), entry.modifiable !== false)
    return toDriveEntry(entry)
  }
  /** 只读页签：工具栏、右键已不出写入口，键盘删除、拖入文件、拖动移动这几个组件不看开关的入口在这里先拦，一个请求都不发 */
  const requireWritable = () => {
    if (!canWrite) throw new Error(RECORD_FOLDER_VIEW_ONLY_REASON)
  }
  /** 先拦一道：已知不能改的节点不发请求。没列出过的照常发，由服务端判 */
  const requireModifiable = (ids: DriveId[]) => {
    requireWritable()
    if (ids.some(id => modifiable.get(String(id)) === false)) throw new Error(RECORD_FOLDER_LOCKED_REASON)
  }
  return {
    list: async parentId => (await api.list({ ...base, parentId })).map(remember),
    get: async id => remember(await api.get({ ...base, id })),
    async path(id) {
      // 根自身没有「所在目录」可问（服务端不许对根取路径）；它就是 /
      if (String(id) === '0') return '/'
      const names = await api.path({ ...base, id })
      return names.length ? `/${names.join('/')}` : '/'
    },
    async createFolder(parentId, name) {
      requireWritable()
      const id = await api.createFolder({ ...base, parentId, name })
      modifiable.set(String(id), true)
      return id
    },
    async rename(id, name) {
      requireModifiable([id])
      await api.rename({ ...base, id, name })
    },
    async move(id, targetParentId) {
      requireModifiable([id])
      await api.move({ ...base, id, targetParentId })
    },
    async copy(id, targetParentId) {
      requireWritable()
      const copied = await api.copy({ ...base, id, targetParentId })
      modifiable.set(String(copied), true)
    },
    async trash(ids) {
      requireModifiable(ids)
      await api.trash({ ...base, ids })
    },
    search: async (name, limit) => (await api.search({ ...base, name, limit })).map(remember),
    content: (id, inline) => api.content({ ...base, id }, inline),
    contentUrl(id, inline) {
      // 图片、音视频预览与下载由浏览器直接发起，带不上请求头：沿用网盘的做法，把访问令牌放在 token 查询参数上
      const params = new URLSearchParams({ ...base, id: String(id), inline: inline ? 'true' : 'false' })
      const token = auth.token()
      if (token) params.set('token', token)
      return `${auth.apiBase}${CONTENT_PATH}?${params.toString()}`
    },
    upload: {
      endpoint: UPLOAD_PATH,
      fields: parentId => ({ ...base, parentId: String(parentId) }),
      check: requireWritable
    },
    checkModifiable: requireModifiable
  }
}

/** 一步步驱动补建：每次调一页，回调进度；返回的 stop() 让它在当前这一页结束后停下 */
export function runRecordFolderBackfill(
  api: Pick<RecordFolderApi, 'backfill'>,
  objectId: string,
  sourceId: string,
  onProgress: (total: RecordFolderBackfillTotal) => void
): { done: Promise<RecordFolderBackfillTotal>; stop: () => void } {
  // 停止标记放在对象上：循环条件读的是它的属性，由外面的 stop() 改
  const control = { stopping: false }
  const total: RecordFolderBackfillTotal = {
    scanned: 0,
    created: 0,
    existing: 0,
    skipped: 0,
    failed: 0,
    failures: [],
    done: false,
    stopped: false
  }
  const snapshot = (): RecordFolderBackfillTotal => ({ ...total, failures: [...total.failures] })
  const run = async () => {
    let cursor: string | null = null
    while (!control.stopping) {
      const page = await api.backfill({ objectId, sourceId, cursor, limit: BACKFILL_PAGE })
      total.scanned += page.scanned
      total.created += page.created
      total.existing += page.existing
      total.skipped += page.skipped
      total.failed += page.failed
      total.failures.push(...(page.failures || []).slice(0, BACKFILL_FAILURES - total.failures.length))
      if (page.done) {
        total.done = true
        break
      }
      // 没扫完却没有往前走：再调只会原地打转
      if (!page.cursor || page.cursor === cursor) throw new Error('补建没有继续往下处理，请稍后重新开始')
      cursor = page.cursor
      onProgress(snapshot())
    }
    total.stopped = !total.done
    onProgress(snapshot())
    return snapshot()
  }
  return {
    done: run(),
    stop: () => {
      control.stopping = true
    }
  }
}

/** 子文件夹名称的示例：各段换成字段名或固定文字，用分隔符连起来 */
export function recordFolderNamePreview(
  template: RecordFolderNameTemplate | null | undefined,
  fields: RecordFolderNameField[]
): string {
  if (!template) return '记录名称'
  const names = new Map(fields.map(field => [field.fieldId, field.name]))
  return template.parts
    .map(part => (part.kind === 'TEXT' ? (part.text ?? '') : (names.get(part.fieldId ?? '') ?? '（字段已删除）')))
    .join(template.separator)
}

/** 浏览器的视图偏好按「对象 + 来源」记：同一个对象的各条记录共用，换记录不丢 */
export function recordFolderFinderId(credential: RecordFolderCredential): string {
  return `record-folder-${credential.objectId}-${credential.sourceId}`
}

/**
 * 一个页签该显示什么：浏览器、「还没有文件」，还是一句原因；以及这个人在这里能不能写、锁标记的说明用哪一套。
 * 能不能写按服务端给的 canWrite（能改这条记录，或本人在文件夹上有网盘编辑权限）：不能写时上传、新建、改名、移动、删除的入口都关掉。
 */
export function recordFolderTabView(tab: RecordFolderTab): {
  kind: 'browser' | 'empty' | 'notice'
  rootRole?: 'VIEWER' | 'EDITOR'
  text?: string
  canWrite: boolean
  lockedNote: string
  lockedReason: string
} {
  const canWrite = tab.canWrite
  const rootRole = canWrite ? 'EDITOR' : 'VIEWER'
  const locks = canWrite
    ? { lockedNote: RECORD_FOLDER_LOCKED_NOTE, lockedReason: RECORD_FOLDER_LOCKED_REASON }
    : { lockedNote: RECORD_FOLDER_VIEW_ONLY_NOTE, lockedReason: RECORD_FOLDER_VIEW_ONLY_REASON }
  if (tab.state === 'READY') return { kind: 'browser', rootRole, canWrite, ...locks }
  if (tab.state === 'PENDING')
    return canWrite
      ? { kind: 'browser', rootRole, canWrite, ...locks }
      : { kind: 'empty', rootRole, text: '还没有文件', canWrite, ...locks }
  return { kind: 'notice', rootRole, text: tab.message || '', canWrite, ...locks }
}

const noSource = new Map<string, number>()
const noSourceKey = (objectId: string, applicationId?: string) => `${objectId}\u0000${applicationId ?? ''}`

/**
 * 「这个对象没有任何文件夹」的短时记忆：没配文件夹的对象不必每打开一条记录都问一次。
 * 5 分钟过期；同一个对象在不同应用里看到的页签可能不同，所以按「对象 + 应用」记；配置保存后按对象整体清除。
 */
export function recordFolderNoSourceCache(): {
  has: (objectId: string, applicationId?: string) => boolean
  mark: (objectId: string, applicationId?: string) => void
  clear: (objectId: string) => void
} {
  return {
    has(objectId, applicationId) {
      const key = noSourceKey(objectId, applicationId)
      const expires = noSource.get(key)
      if (expires === undefined) return false
      if (expires > Date.now()) return true
      noSource.delete(key)
      return false
    },
    mark(objectId, applicationId) {
      noSource.set(noSourceKey(objectId, applicationId), Date.now() + NO_SOURCE_TTL)
    },
    clear(objectId) {
      for (const key of [...noSource.keys()]) if (key.startsWith(`${objectId}\u0000`)) noSource.delete(key)
    }
  }
}

/** 挂在 body 上的类：有它时，文件浏览器组件的对话框层级抬到平台弹层之上（样式在 RecordFolderPanel.vue） */
export const RECORD_FOLDER_RAISED_CLASS = 'record-folder-finder-raised'
let raisedPanels = 0

/**
 * 文件浏览器组件的对话框（新建、改名、移动、删除确认、上传、搜索）传送到 body、自带层级只有 70，
 * 嵌在表单抽屉里时被抽屉（1000）压住。每块面板各拿一个开关：显示着、且页面在前台时打开；
 * 多块面板（保活的页签）各自登记，全部关掉才摘掉 body 上的类。网盘自己的页面从不打开它，表现不变。
 */
export function recordFolderDialogRaiser(target: { classList: DOMTokenList } = document.body): {
  set: (on: boolean) => void
} {
  let raised = false
  return {
    set(on) {
      if (on === raised) return
      raised = on
      raisedPanels += on ? 1 : -1
      target.classList.toggle(RECORD_FOLDER_RAISED_CLASS, raisedPanels > 0)
    }
  }
}
