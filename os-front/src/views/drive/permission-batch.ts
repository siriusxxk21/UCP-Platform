import type { DriveId } from '@/types/drive'

export interface PermissionSaveSubject {
  id: DriveId
  label: string
}

/**
 * 分拣批量授权结果。已成功主体从重试列表移除，失败项保留名称与原始错误。
 */
export function partitionPermissionSaveResults(
  subjects: PermissionSaveSubject[],
  results: PromiseSettledResult<unknown>[]
) {
  const succeededIds = new Set<string>()
  const failures: Array<{ subject: PermissionSaveSubject; reason: unknown }> = []
  results.forEach((result, index) => {
    const subject = subjects[index]
    if (!subject) return
    if (result.status === 'fulfilled') succeededIds.add(String(subject.id))
    else failures.push({ subject, reason: result.reason })
  })
  return { succeededIds, failures }
}
