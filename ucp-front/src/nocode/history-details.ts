import { changedKeys } from './record-history'
import type { HistoryDetailChange } from '@/types/nocode/record-history'

export const DetailChangeKind = { CREATE: '新增', UPDATE: '修改', DELETE: '删除', MOVE: '调整顺序' } as const

/** 比较持久化行 ID；插入一行导致的整体位移不误记为其他行全部移动。 */
export function detailRowChanges(group: HistoryDetailChange) {
  if (!group.beforeKnown || !group.afterKnown) return []
  const commonBefore = group.beforeOrder.filter(id => id in group.after)
  const commonAfter = group.afterOrder.filter(id => id in group.before)
  const ids = [
    ...new Set([...group.afterOrder, ...group.beforeOrder, ...Object.keys(group.after), ...Object.keys(group.before)])
  ]
  return ids.flatMap(id => {
    const before = group.before[id],
      after = group.after[id]
    const fields = changedKeys(before ?? null, after ?? null)
    const moved = !!before && !!after && commonBefore.indexOf(id) !== commonAfter.indexOf(id)
    const kind = !before ? 'CREATE' : !after ? 'DELETE' : fields.length ? 'UPDATE' : moved ? 'MOVE' : null
    return kind
      ? [
          {
            id,
            before,
            after,
            fields,
            moved,
            kind: kind as keyof typeof DetailChangeKind,
            beforePosition: group.beforeOrder.indexOf(id) + 1,
            afterPosition: group.afterOrder.indexOf(id) + 1
          }
        ]
      : []
  })
}
