import type { TaskAncestorContext, TaskRow } from '@/types/nocode/task-center'
import { taskAssignmentLabel, taskDisplayState, taskStates } from './task-center'

export interface TaskContextNode extends TaskAncestorContext {
  rootId: string
  referenceOnly: boolean
  task?: TaskRow
}

/** 摘要只进入位置视图，不混入详情节点、执行操作、业务数据或工作量集合。 */
export function taskContextNodes(nodes: readonly TaskRow[]): TaskContextNode[] {
  const lookup = new Map<string, TaskContextNode>()
  for (const node of nodes) {
    for (const ancestor of node.ancestorContext || []) {
      if (!lookup.has(ancestor.id)) lookup.set(ancestor.id, { ...ancestor, rootId: node.rootId, referenceOnly: true })
    }
    lookup.set(node.id, {
      id: node.id,
      parentId: node.parentId,
      rootId: node.rootId,
      title: node.title,
      status: taskDisplayState(node),
      assigneeName: node.assigneeName,
      detailVisible: true,
      referenceOnly: false,
      task: node
    })
  }
  return [...lookup.values()]
}

export function taskContextAssignee(node: TaskContextNode): string {
  return node.task ? taskAssignmentLabel(node.task, node.assigneeName) : node.assigneeName || '未指定负责人'
}

/** 分页入口保留真实直属上级，不将最近匹配的显示父项冒充业务父项。 */
export function taskParentContextLabel(node: TaskRow): string | undefined {
  const parent = node.ancestorContext?.find(item => item.id === node.parentId)
  return parent
    ? `${parent.title} · ${parent.assigneeName || '未指定负责人'} · ${taskStates[parent.status]}`
    : undefined
}
