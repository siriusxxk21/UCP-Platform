import { NodeKind, type UiNode } from '../types/nocode/application-ui'

/**
 * 运行页列表的查询栏放在哪：页签容器的某个页签里【直接】只放了一个列表时，这个列表的查询栏上到页签行右侧；
 * 其余情况（两个及以上列表、列表包在卡片 / 栅格里、没有页签）放在表格卡片标题行。
 */
const LIST_NODES: readonly string[] = [NodeKind.VIEW, NodeKind.RELATED]

/** 页签行上的查询栏归这个页签里的哪个列表节点；判不出归属（零个或多个列表）时返回 undefined。 */
export function tabSearchOwner(tab: UiNode): string | undefined {
  const lists = (tab.children || []).filter(child => LIST_NODES.includes(child.type) && !!child.resourceId)
  return lists.length === 1 ? lists[0].id : undefined
}

/** 页签行 / 标题行里最多放几个常用查询字段（关键词另算）；多出的收进「更多条件」。 */
export const INLINE_QUERY_FIELDS = 2

/** 常用查询字段分成行内与「更多条件」两段，顺序不变。 */
export function splitQueryFields<T>(fields: readonly T[]): { inline: T[]; more: T[] } {
  return { inline: fields.slice(0, INLINE_QUERY_FIELDS), more: fields.slice(INLINE_QUERY_FIELDS) }
}
