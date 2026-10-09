import { NodeKind, type UiNode } from '../types/nocode/application-ui'

const hasAppearance = (node: UiNode) =>
  Object.values(node.style || {}).some(value => value != null) ||
  Object.values(node.display || {}).some(value => value != null)

/** 兼容早期自动生成的单列表包装，只调整呈现，不改写发布快照或业务节点身份。 */
export function runtimePageNodes(nodes: UiNode[]): UiNode[] {
  const root = nodes[0]
  if (
    nodes.length === 1 &&
    root?.type === NodeKind.CARD &&
    (!root.text?.trim() || root.text === '业务工作台') &&
    !hasAppearance(root) &&
    !root.action &&
    !root.binding &&
    !root.resourceId &&
    root.children.length === 1 &&
    root.children[0]?.type === NodeKind.VIEW
  )
    return root.children
  return nodes
}

/** 仅独占页面的普通列表使用表体滚动；显式外观和复合页面继续按设计布局展开。 */
export function isStandaloneRuntimeList(nodes: UiNode[]): boolean {
  const node = nodes[0]
  return nodes.length === 1 && node?.type === NodeKind.VIEW && !hasAppearance(node)
}
