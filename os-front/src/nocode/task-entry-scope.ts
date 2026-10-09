import type { TaskDataPolicy } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig, TaskWorkEntry } from '@/types/nocode/task-work-entries'

/** 旧配置只读取原授权，不能因打开配置弹窗或重新多选而改写范围。 */
export function taskEntryScope(entry: TaskWorkEntryConfig, legacyPolicy?: TaskDataPolicy | null): 'GROUP' | 'ALL' {
  return (
    entry.dataScope ??
    (legacyPolicy
      ? entry.key === '__business'
        ? legacyPolicy.business
        : legacyPolicy.feedback
      : entry.allowAll
        ? 'ALL'
        : 'GROUP')
  )
}

export function taskEntryScopeLabel(scope: 'GROUP' | 'ALL'): string {
  return scope === 'ALL' ? '全部业务数据' : '仅本组任务数据'
}

/** 运行态以服务端与来源取交集后的结果为准，不能被旧 allowAll 重新放大。 */
export function taskEntryAllowsAll(entry: TaskWorkEntry): boolean {
  return (entry.effectivePolicy ?? taskEntryScope(entry.config)) === 'ALL'
}
