import type { TaskBinding } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { DataScope } from '@/types/nocode/data-scope'

/** 发布配置使用 fixed；兼容树形 scope，但不把常量内容或默认筛选误判为身份范围。 */
export function taskViewHasDynamicScope(config: Record<string, unknown>): boolean {
  if (!config.query || typeof config.query !== 'object') return false
  const query = config.query as { fixed?: unknown; scope?: unknown }
  const hasDynamic = (raw: unknown): boolean => {
    if (!raw || typeof raw !== 'object') return false
    const scope = raw as Partial<DataScope>
    return (
      (Array.isArray(scope.conditions) &&
        scope.conditions.some(condition => condition?.valueSource != null && condition.valueSource !== 'CONSTANT')) ||
      (Array.isArray(scope.groups) && scope.groups.some(hasDynamic))
    )
  }
  return hasDynamic({ conditions: query.fixed }) || hasDynamic(query.scope)
}

/** 入口身份不包含表单发布版本；再次选择不能重置实例中已有的固定引用和共享配置。 */
export function taskEntryIdentity(binding: TaskBinding | null | undefined): string | null {
  if (!binding?.applicationId) return null
  const resource = binding.viewId || binding.entryId || binding.formId
  return resource
    ? JSON.stringify([binding.applicationId, binding.viewId ? 'VIEW' : binding.entryId ? 'ENTRY' : 'FORM', resource])
    : null
}

export interface TaskEntryCandidate {
  id: string
  name: string
  applicationId: string
  applicationName: string
  source: 'ENTRY' | 'FORM' | 'VIEW'
  binding: TaskBinding
  available: boolean
  /** 已选历史项尚未加载所属应用，仅作核对提示，不是已确认异常。 */
  pendingVerification?: boolean
  objectName?: string
  resolvedFormId?: string
  formName?: string
  status?: string
}

export function selectedTaskEntryIds(entries: TaskWorkEntryConfig[]): string[] {
  return [
    ...new Set(
      entries
        .filter(e => e.dataMode !== 'SOURCE_SHARED')
        .map(e => taskEntryIdentity(e.binding))
        .filter((id): id is string => !!id)
    )
  ]
}

/** 仅明确加载成功的候选可以取消勾选；历史失效项和指定来源共享项须显式移除。 */
export function mergeTaskEntrySelection(
  existing: TaskWorkEntryConfig[],
  candidates: TaskEntryCandidate[],
  selected: string[],
  isRoot: boolean,
  key: () => string
): TaskWorkEntryConfig[] {
  const available = new Map(candidates.filter(c => c.available).map(c => [c.id, c]))
  const chosen = new Set(selected)
  const result = existing.filter(entry => {
    const id = taskEntryIdentity(entry.binding)
    return entry.dataMode === 'SOURCE_SHARED' || !id || !available.has(id) || chosen.has(id)
  })
  const retained = new Set(selectedTaskEntryIds(result))
  for (const id of chosen) {
    const candidate = available.get(id)
    if (!candidate || retained.has(id)) continue
    result.push({
      key: key(),
      name: candidate.name,
      binding: { ...candidate.binding },
      dataScope: 'GROUP',
      dataMode: isRoot ? 'ROOT_SHARED' : 'INDEPENDENT',
      sourceNodeId: null,
      sourceEntryKey: null,
      readableFieldIds: null,
      writableFieldIds: null,
      required: false,
      allowAll: false
    })
    retained.add(id)
  }
  if (result.length > 20) throw new Error('每个任务最多配置 20 个业务办理项，请减少选择后重试。')
  return result
}
