import type { TaskReadiness } from '@/types/nocode/task-center'

export function cancellationConfirmationRequired(readiness: TaskReadiness | null | undefined) {
  return !!readiness?.checks.some(check => check.code === 'CHILDREN_CANCELLED' && !check.passed)
}

/** 取消范围必须显式确认且说明，不能借此绕过其他资料、资格和状态检查。 */
export function taskCompletionReady(readiness: TaskReadiness | null | undefined, confirmed: boolean, note: string) {
  if (!readiness) return false
  if (!cancellationConfirmationRequired(readiness)) return readiness.canComplete
  return (
    confirmed && !!note.trim() && readiness.checks.every(check => check.passed || check.code === 'CHILDREN_CANCELLED')
  )
}
