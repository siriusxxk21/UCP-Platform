import type { SaveRecord } from '@/types/nocode/runtime'
import type { DocumentProblem } from '@/types/nocode/document-policy'
import { v4 as uuidv4 } from 'uuid'

export function documentProblems(error: unknown): DocumentProblem[] {
  const problems = (error as { details?: { problems?: unknown } })?.details?.problems
  if (!Array.isArray(problems)) return []
  return problems.filter(
    (p): p is DocumentProblem => !!p && typeof p.message === 'string' && typeof p.scope === 'string'
  )
}
export const isDocumentRejection = (error: unknown) =>
  typeof (error as { businessCode?: unknown })?.businessCode === 'number'
export const newRowKey = () => uuidv4()

/** 仅在当前浏览器会话中保留原提交意图，按账号与单据隔离，确认成功或明确拒绝后删除。 */
export function pendingDocumentKey(actor: string, app: string, object: string, record: string | null) {
  return `nocode.document.pending:${actor}:${app}:${object}:${record || 'new'}`
}
export function loadPendingDocument(key: string): SaveRecord | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) || 'null')
    return value &&
      typeof value.requestKey === 'string' &&
      typeof value.applicationId === 'string' &&
      typeof value.objectId === 'string' &&
      value.values &&
      typeof value.values === 'object'
      ? value
      : null
  } catch {
    return null
  }
}
export function storePendingDocument(key: string, command: SaveRecord | null) {
  try {
    if (command) sessionStorage.setItem(key, JSON.stringify(command))
    else sessionStorage.removeItem(key)
  } catch {
    /* 存储空间不足仍保留当前编辑器内存中的原请求，不能因此改键重试。 */
  }
}
