import { computed, ref, watch } from 'vue'
import type { TaskCenterApi } from '@/api/nocode/task-center'
import { useUserStore } from '@/stores/user'
import { isDocumentRejection } from './document-save'
import { useUnsavedNavigation } from './unsaved'

type Command = Parameters<TaskCenterApi['transition']>[0]
interface Attempt {
  command: Command
  key: string
  uncertain: boolean
}
const memory = new Map<string, Command>()
const volatile = new Set<string>()
const storageKey = (actor: string, taskId: string) =>
  `nocode.task.transition.pending:${encodeURIComponent(actor)}:${encodeURIComponent(taskId)}`

/** 原命令按账号和任务隔离；会话存储失败时保留内存并启用离开保护。 */
function read(key: string, id: string): Command | null {
  if (volatile.has(key)) return memory.get(key) || null
  try {
    const value = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (
      value?.id === id &&
      ['START', 'PAUSE', 'RESUME', 'COMPLETE', 'CANCEL', 'APPROVE', 'REJECT'].includes(value.action) &&
      Number.isInteger(value.expectedRevision) &&
      value.expectedRevision >= 0 &&
      typeof value.note === 'string' &&
      typeof value.requestKey === 'string' &&
      value.requestKey
    )
      return value
    memory.delete(key)
  } catch {
    return memory.get(key) || null
  }
  return null
}
function write(key: string, value: Command | null): boolean {
  if (value) memory.set(key, value)
  else memory.delete(key)
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value))
    else sessionStorage.removeItem(key)
    volatile.delete(key)
    return true
  } catch {
    volatile.add(key)
    return false
  }
}

export function useTaskTransitionRecovery(api: TaskCenterApi, taskId: () => string) {
  const user = useUserStore()
  const key = computed(() => storageKey(String(user.userInfo?.id || ''), taskId()))
  const pending = ref<Command | null>(null)
  const uncertain = ref(false)
  // 请求发出前先留存命令用于恢复，但仅结果不确定时才展示待确认提示。
  const needsConfirmation = computed(() => !!pending.value && uncertain.value)
  const durable = ref(true)
  watch(
    key,
    value => {
      pending.value = taskId() && user.userInfo?.id ? read(value, taskId()) : null
      uncertain.value = !!pending.value
      durable.value = !pending.value || write(value, pending.value)
    },
    { immediate: true, flush: 'sync' }
  )
  useUnsavedNavigation(() => !!pending.value && !durable.value, {
    title: '浏览器无法保存待确认操作，离开后将无法恢复原请求。仍要离开？'
  })
  function begin(command: Command): Attempt {
    const snapshot = Object.freeze({ ...command })
    const attempt = { command: snapshot, key: key.value, uncertain: uncertain.value }
    pending.value = snapshot
    durable.value = write(attempt.key, snapshot)
    return attempt
  }
  function succeed(attempt: Attempt) {
    // 迟到的响应只能清除自己的命令，不能清掉另一个页面随后保存的新操作。
    if (read(attempt.key, attempt.command.id)?.requestKey === attempt.command.requestKey) write(attempt.key, null)
    if (key.value === attempt.key && pending.value?.requestKey === attempt.command.requestKey) {
      pending.value = null
      uncertain.value = false
    }
  }
  async function fail(attempt: Attempt, error: unknown) {
    if (isDocumentRejection(error)) {
      if (!attempt.uncertain) {
        succeed(attempt)
        return { applied: null, superseded: false }
      }
      try {
        const recovery = await api.transitionRecovery(attempt.command)
        if (recovery.applied || recovery.superseded) {
          succeed(attempt)
          return recovery
        }
      } catch {
        /* 无法读取回执或已经失权时，不能把明确拒绝当作原请求未提交。 */
      }
    }
    if (key.value === attempt.key) uncertain.value = true
    return { applied: null, superseded: false }
  }
  return { identity: key, pending, needsConfirmation, begin, succeed, fail }
}
