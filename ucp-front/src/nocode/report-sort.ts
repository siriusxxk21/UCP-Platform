import type { ReportConfig, ReportSort } from '@/types/nocode/report'

/** 列头点击的排序目标：某个指标（透视表里带所点列组的列键前缀，[] = 行合计列组），或第 dimension 个行维度（0 起）。 */
export type ReportSortTarget = { metricId: string; columnGroup?: (string | null)[] } | { dimension: number }

const columnKey = (keys?: (string | null)[] | null) => JSON.stringify(keys ?? [])

export function sameSortTarget(sort: ReportSort | null | undefined, target: ReportSortTarget): boolean {
  if (!sort) return false
  if ('metricId' in target)
    return sort.metricId === target.metricId && columnKey(sort.columnGroup) === columnKey(target.columnGroup)
  return sort.metricId == null && sort.dimension === target.dimension
}

/** 三态：点一次升序，再点降序，第三次恢复默认（null，按配置的排序）；点另一列从升序重新开始。 */
export function nextReportSort(current: ReportSort | null | undefined, target: ReportSortTarget): ReportSort | null {
  if (!current || !sameSortTarget(current, target))
    return 'metricId' in target
      ? { metricId: target.metricId, columnGroup: target.columnGroup ?? [], descending: false }
      : { dimension: target.dimension, descending: false }
  return current.descending ? null : { ...current, descending: true }
}

/** 列头上的排序状态；未按该列排序时为 undefined。 */
export const reportSortDirection = (sort: ReportSort | null | undefined, target: ReportSortTarget) =>
  sort && sameSortTarget(sort, target) ? (sort.descending ? 'descending' : 'ascending') : undefined

/** 配置变了（指标被删、行维度变少）以后，原来点的排序可能已经不成立；不成立就当作没点过。 */
export function validReportSort(
  sort: ReportSort | null | undefined,
  config: Pick<ReportConfig, 'metrics' | 'dimensions'>
): ReportSort | null {
  if (!sort) return null
  if (sort.metricId != null) return config.metrics.some(m => m.id === sort.metricId) ? sort : null
  return sort.dimension != null && sort.dimension >= 0 && sort.dimension < config.dimensions.length ? sort : null
}
