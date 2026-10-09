import { NodeKind, PageActionKind, type PageConfig, type UiNode } from '../types/nocode/application-ui'
import { ResourceKind, type ApplicationResource } from '../types/nocode/application'
import { findPageNode } from './page-context'
import { resolveViewForm } from './default-form'
/** 按已发布节点定位目标，不在按钮中重复保存对象、表单和关联定义。 */
export function resolvePageAction(node: UiNode, page: PageConfig | undefined, resources: ApplicationResource[]) {
  const action = node.action
  const target = action?.targetNodeId && page ? findPageNode(page.nodes, action.targetNodeId) : undefined
  const resource = resources.find(r => r.id === (target?.resourceId || action?.resourceId))
  const view = resource?.kind === ResourceKind.VIEW ? resource : undefined
  try {
    const form =
      resource?.kind === ResourceKind.FORM
        ? resource
        : view
          ? resolveViewForm(resources, String(view.config.objectId), view.config.formId as string | null)
          : undefined
    return { target, resource, view, form, error: '' }
  } catch (cause) {
    return { target, resource, view, form: undefined, error: cause instanceof Error ? cause.message : String(cause) }
  }
}
export function pageActionTargets(nodes: UiNode[], kind?: string): UiNode[] {
  const flat: UiNode[] = []
  const visit = (list: UiNode[]) =>
    list.forEach(n => {
      flat.push(n)
      visit(n.children || [])
    })
  visit(nodes)
  const kinds =
    kind === PageActionKind.CREATE
      ? [NodeKind.VIEW, NodeKind.RELATED]
      : kind === PageActionKind.EDIT || kind === PageActionKind.VIEW
        ? [NodeKind.FORM, NodeKind.DETAIL]
        : [
            NodeKind.VIEW,
            NodeKind.RELATED,
            NodeKind.DETAIL,
            NodeKind.METRIC,
            NodeKind.REPORT,
            NodeKind.REPORT_DASHBOARD,
            NodeKind.ATTACHMENTS,
            NodeKind.PROCESSES,
            NodeKind.TASKS
          ]
  return flat.filter(n => kinds.some(k => k === n.type))
}
