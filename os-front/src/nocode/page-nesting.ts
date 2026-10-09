/**
 * 页面设计器的父子约束：页签容器只能直接放页签、分栏容器只能直接放分栏；页签只能放在页签容器里、分栏只能放在分栏容器里。
 * 与后端 ApplicationPageValidator（TABS 只收 TAB、ROW 只收 COLUMN）、物料 nestingRule 同一张表（materials.js 从这里取）。
 * 业务方 2026-10-04：画布上把页签容器拖进另一个页签容器（与页签并排），保存时才被后端拒，且看不出改哪里。
 */
export const CHILD_ONLY: Readonly<Record<string, string>> = { OsTabs: 'OsTab', OsRow: 'OsCol' }
export const PARENT_ONLY: Readonly<Record<string, string>> = { OsTab: 'OsTabs', OsCol: 'OsRow' }

export interface NestingNode {
  id?: string
  componentName: string
  props?: Record<string, unknown>
  children?: NestingNode[]
}
/** parent / child 是组件名；required 是该位置要求的组件名。 */
export interface NestingViolation {
  parent: string
  child: string
  required: string
  /** child-only：父容器只收某一种子节点；parent-only：子节点只能放在某一种父容器里。 */
  kind: 'child-only' | 'parent-only'
  parentId?: string
  childId?: string
}

/** 深度优先找第一处父子不合规；根节点（Page）下面只看 parent-only。 */
export function nestingViolation(root: NestingNode): NestingViolation | null {
  for (const child of root.children || []) {
    const onlyChild = CHILD_ONLY[root.componentName]
    if (onlyChild && child.componentName !== onlyChild)
      return {
        parent: root.componentName,
        child: child.componentName,
        required: onlyChild,
        kind: 'child-only',
        parentId: root.id,
        childId: child.id
      }
    const onlyParent = PARENT_ONLY[child.componentName]
    if (onlyParent && root.componentName !== onlyParent)
      return {
        parent: root.componentName,
        child: child.componentName,
        required: onlyParent,
        kind: 'parent-only',
        parentId: root.id,
        childId: child.id
      }
    const nested = nestingViolation(child)
    if (nested) return nested
  }
  return null
}

/** 没有任何页签的页签容器（后端要求至少一个页签）。 */
export function emptyTabs(root: NestingNode): NestingNode | null {
  for (const child of root.children || []) {
    if (child.componentName === 'OsTabs' && !(child.children || []).length) return child
    const nested = emptyTabs(child)
    if (nested) return nested
  }
  return null
}

const name = (labels: Record<string, string>, component: string) =>
  component === 'Page' ? '页面' : labels[component] || component

export function nestingMessage(violation: NestingViolation, labels: Record<string, string>): string {
  const parent = name(labels, violation.parent),
    child = name(labels, violation.child),
    required = name(labels, violation.required)
  if (violation.kind === 'child-only')
    return `「${child}」不能直接放进「${parent}」：${parent}里只能放${required}，请拖进某个${required}里`
  return `「${child}」只能放在「${required}」里，不能放进「${parent}」`
}

/** 应用到草稿前的整页核对：父子不合规或有空的页签容器时返回说明，否则 null。 */
export function pageStructureError(root: NestingNode, labels: Record<string, string>): string | null {
  const violation = nestingViolation(root)
  if (violation) return nestingMessage(violation, labels)
  const empty = emptyTabs(root)
  if (empty) {
    const text = String(empty.props?.text || '').trim()
    return `页签容器${text ? `「${text}」` : ''}里至少要有一个页签，请添加页签或删除这个页签容器`
  }
  return null
}
