/** 关联只保留原请求身份，不缓存业务材料；未知结果跨抽屉重开后继续查询同一回执。 */
export interface PendingTaskLink {
  taskId: string
  entryKey: string
  recordId: string
  requestKey: string
}

const memory = new Map<string, PendingTaskLink>()
export const taskLinkKey = (actor: string, taskId: string) =>
  `nocode.task.link.pending:${encodeURIComponent(actor)}:${encodeURIComponent(taskId)}`

export function loadTaskLink(key: string, taskId: string): PendingTaskLink | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (
      value?.taskId === taskId &&
      ['entryKey', 'recordId', 'requestKey'].every(field => typeof value[field] === 'string' && value[field])
    ) {
      memory.set(key, value)
      return value
    }
  } catch {
    /* 存储不可用时仍保留当前页面会话内的原请求。 */
  }
  return memory.get(key) || null
}

export function storeTaskLink(key: string, command: PendingTaskLink | null) {
  if (command) memory.set(key, command)
  else memory.delete(key)
  try {
    if (command) sessionStorage.setItem(key, JSON.stringify(command))
    else sessionStorage.removeItem(key)
  } catch {
    /* 不能因存储失败重新生成请求键。 */
  }
}
