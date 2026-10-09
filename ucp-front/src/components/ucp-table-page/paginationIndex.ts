export interface PaginationSnapshot {
  current: number
  pageSize: number
}

/**
 * 序号列使用的分页快照。异步加载期间保留旧快照，避免新页码作用到旧页数据。
 */
export function resolvePaginationSnapshot(
  displayed: PaginationSnapshot,
  pagination: unknown,
  loading: boolean
): PaginationSnapshot {
  if (loading) return displayed

  const value = pagination && typeof pagination === 'object' ? (pagination as Record<string, unknown>) : {}
  return {
    current: positiveNumber(value.current, 1),
    pageSize: positiveNumber(value.pageSize, 0)
  }
}

export function resolveRowSequence(index: number, pagination: PaginationSnapshot) {
  if (!pagination.pageSize) return index + 1
  return (pagination.current - 1) * pagination.pageSize + index + 1
}

/** 分页目标发生变化时隐藏旧页数据；同一页重新加载时仍可保留当前数据。 */
export function isPaginationTransitionLoading(displayed: PaginationSnapshot, pagination: unknown, loading: boolean) {
  if (!loading) return false
  const requested = resolvePaginationSnapshot(displayed, pagination, false)
  return requested.current !== displayed.current || requested.pageSize !== displayed.pageSize
}

function positiveNumber(value: unknown, fallback: number) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : fallback
}
