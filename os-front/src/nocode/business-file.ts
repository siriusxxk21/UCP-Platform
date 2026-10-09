import { v4 as uuidv4 } from 'uuid'
import type { BusinessFilePolicy } from '@/types/nocode/data-center'
import { businessFileEnabled } from './business-file-policy'

export const BUSINESS_FILE_MAX_COUNT = 100
export const BUSINESS_FILE_MAX_SIZE = 50 * 1024 * 1024

export function canAppendBusinessFile(current: readonly string[], pending: number, size: number) {
  return current.length + pending < BUSINESS_FILE_MAX_COUNT && size <= BUSINESS_FILE_MAX_SIZE
}

/** 上传完成回调可能并发返回，始终基于调用时的最新本地快照追加，避免后返回覆盖先返回。 */
export function appendBusinessFileId(current: readonly string[], fileId: string) {
  return current.includes(fileId) ? [...current] : [...current, fileId]
}

/** 参与判定与服务端 BusinessFilePolicies 一致：启用规则且字段在参与清单内。 */
export function businessFileField(policy: BusinessFilePolicy | null | undefined, fieldId: string | null | undefined) {
  return businessFileEnabled(policy) && !!fieldId && (policy!.fieldIds ?? []).includes(fieldId)
}

/** 保存位置提示：只展示已确定的前缀目录；分组与记录目录保存时按记录生成，不作过度承诺。 */
export function businessFileHint(policy: BusinessFilePolicy | null | undefined): string {
  if (!businessFileEnabled(policy)) return ''
  const segments = [policy!.spaceName.trim(), ...(policy!.fixedPath ?? []).map(level => level.trim()).filter(Boolean)]
  const dynamic = (policy!.groups?.length ?? 0) > 0 || (policy!.recordLabelFields?.length ?? 0) > 0
  return `保存后归入：${segments.join(' / ')}${dynamic ? ' / …（其余目录保存时按记录生成）' : ''}`
}

const SESSION_KEY = /^[A-Za-z0-9_-]{1,64}$/

/** 会话标识只允许服务端接受的字符集；前缀便于在请求日志中识别。 */
export function newBusinessSessionKey() {
  return `os-${uuidv4()}`
}
export const validBusinessSessionKey = (value: string) => SESSION_KEY.test(value)

export interface BusinessTempFile {
  fileId: string
  name: string
  size: number
  mimeType?: string | null
  fieldId: string
}
export interface BusinessUploadSession {
  key: string
  files: Record<string, BusinessTempFile>
}

/** 按账号、入口与记录隔离：同一浏览器会话内不同记录互不可见，避免串用待保存文件。 */
export function businessSessionId(
  actor: string | number | null | undefined,
  applicationId: string | null | undefined,
  objectId: string,
  recordId: string | null | undefined
) {
  return `os.bizfile.session.${actor || 'anonymous'}:${applicationId || '-'}:${objectId}:${recordId || 'new'}`
}

export function loadBusinessSession(storage: Storage, id: string): BusinessUploadSession | null {
  try {
    const value = JSON.parse(storage.getItem(id) || 'null') as BusinessUploadSession | null
    return value &&
      typeof value.key === 'string' &&
      SESSION_KEY.test(value.key) &&
      value.files &&
      typeof value.files === 'object'
      ? value
      : null
  } catch {
    return null
  }
}
export function saveBusinessSession(storage: Storage, id: string, session: BusinessUploadSession) {
  try {
    storage.setItem(id, JSON.stringify(session))
  } catch {
    /* 存储不可用时保留内存态；会话键仍随上传请求生效，仅失去刷新后的重连提示。 */
  }
}
/** 取得本记录的上传会话；尚未创建时生成会话键并持久化，刷新页面后仍可续期与预览。 */
export function businessSession(storage: Storage, id: string): BusinessUploadSession {
  const existing = loadBusinessSession(storage, id)
  if (existing) return existing
  const session: BusinessUploadSession = { key: newBusinessSessionKey(), files: {} }
  saveBusinessSession(storage, id, session)
  return session
}
export function rememberBusinessTempFile(
  storage: Storage,
  id: string,
  session: BusinessUploadSession,
  file: BusinessTempFile
) {
  session.files[file.fileId] = file
  saveBusinessSession(storage, id, session)
}
/** 明确失效的临时文件不再作为待保存项展示；内容仍由服务端 TTL 与保留引用规则清理。 */
export function forgetBusinessTempFile(storage: Storage, id: string, session: BusinessUploadSession, fileId: string) {
  delete session.files[fileId]
  saveBusinessSession(storage, id, session)
}
