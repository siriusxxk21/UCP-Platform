import type { UiNode } from '../types/nocode/application-ui'
/** 按稳定节点 ID 定位关联区块；关联含义仍由对象中的持久关系决定。 */
export function findPageNode(nodes: UiNode[], id: string): UiNode | undefined {
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findPageNode(node.children || [], id)
    if (found) return found
  }
}
