import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import type { Rule } from '@form-create/ant-design-vue'

/** 明细列随引擎历史栈撤销重做，发布时仍写入既有 detailNodes 契约。 */
export function detailColumnsFromRules(rules: Rule[]): Record<string, UiNode[]> {
  const result: Record<string, UiNode[]> = {}
  for (const rule of rules) {
    if (rule.type === 'nocodeInternalDetailDesign' && rule.props?.detailId && Array.isArray(rule.props._osDetailNodes))
      result[String(rule.props.detailId)] = rule.props._osDetailNodes as UiNode[]
    Object.assign(
      result,
      detailColumnsFromRules((rule.children || []).filter((r): r is Rule => typeof r === 'object' && r !== null))
    )
  }
  return result
}

/** 按画布顺序收集明细，不进入明细列的独立配置。重复值保留，供校验定位。 */
export function internalDetailIds(nodes: UiNode[]): string[] {
  return nodes.flatMap(node =>
    node.type === NodeKind.INTERNAL_DETAIL ? [node.detail?.detailId || ''] : internalDetailIds(node.children)
  )
}

/** 旧配置按原顺序投影到画布尾部。保存时由画布反推 detailIds，移除后不会被补回。 */
export function formLayoutNodes(nodes: UiNode[], detailIds: string[]): UiNode[] {
  const present = new Set(internalDetailIds(nodes))
  const usedIds = new Set<string>()
  const collect = (items: UiNode[]) =>
    items.forEach(node => {
      usedIds.add(node.id)
      collect(node.children)
    })
  collect(nodes)
  return [
    ...nodes,
    ...detailIds
      .filter(id => !present.has(id))
      .map(detailId => {
        let id = `internal-detail-${detailId}`
        while (usedIds.has(id)) id += '-layout'
        usedIds.add(id)
        return uiNode(NodeKind.INTERNAL_DETAIL, { id, detail: { detailId, mode: 'GRID' } })
      })
  ]
}
