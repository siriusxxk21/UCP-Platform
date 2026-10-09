/**
 * 授权清单里的「全部」：恰好一个元素 '*'。语义与后端 Selections 一一对应。
 * 只用于设计端的授权配置；运行端拿到的权限已由后端展开，不经过这里。
 */
export const ALL = '*'

/** 恰好一个 '*' 才是「全部」；没填、空清单、与具体项混用都不是。 */
export const isAll = (stored?: readonly string[] | null): boolean => stored?.length === 1 && stored[0] === ALL

/** 全部 ⇒ 全集原顺序；清单 ⇒ 清单里仍在全集的项（保持清单顺序）。 */
export function resolveSelection(stored: readonly string[] | null | undefined, universe: readonly string[]): string[] {
  if (isAll(stored)) return [...universe]
  return (stored || []).filter(id => universe.includes(id))
}

/** 全部 ⇒ 真；没填或空清单 ⇒ 假。 */
export function includesSelection(stored: readonly string[] | null | undefined, id: string): boolean {
  return isAll(stored) || (stored || []).includes(id)
}

/** 清单里已不在全集的项（已停用或已不存在）；全部 ⇒ 没有。 */
export function staleItems(stored: readonly string[] | null | undefined, universe: readonly string[]): string[] {
  if (isAll(stored)) return []
  return (stored || []).filter(id => !universe.includes(id))
}

/** 两层取交集：全部 ∩ X = X；X ∩ 全部 = X；全部 ∩ 全部 = 全部；否则普通交集（保持 a 的顺序）。 */
export function intersectSelection(a: readonly string[] | undefined, b: readonly string[] | undefined): string[] {
  if (isAll(a)) return [...(b || [])]
  if (isAll(b)) return [...(a || [])]
  return (a || []).filter(id => (b || []).includes(id))
}
