import type { TaskNodeInput } from '@/types/nocode/task-center'
import { taskStructureError } from './task-center'

export type TaskArrangementNode = Pick<TaskNodeInput, 'id' | 'title' | 'parentId' | 'predecessorIds'>

/** 归属路径只使用已加载节点，不补造权限外的任务名称。 */
export function taskLocation(nodes: TaskArrangementNode[], id: string | null | undefined): string {
  const lookup = new Map(nodes.map(node => [node.id, node]))
  const names: string[] = [],
    seen = new Set<string>()
  while (id && !seen.has(id)) {
    seen.add(id)
    const node = lookup.get(id)
    if (!node) {
      names.unshift('未显示的上级任务')
      break
    }
    names.unshift(node.title.trim() || '未命名任务')
    id = node.parentId
  }
  return names.join(' / ')
}

/** 移动只变更所属关系，整支子树和既有执行依赖不变；先校验整图再一次提交。 */
export function moveTaskNode<T extends TaskArrangementNode>(
  nodes: T[],
  id: string,
  parentId: string,
  options: { rootId?: string; frozenIds?: string[]; closedIds?: string[]; readonly?: boolean } = {}
): { nodes: T[]; error: null } | { nodes: null; error: string } {
  const current = nodes.find(node => node.id === id),
    parent = nodes.find(node => node.id === parentId)
  const root = nodes.find(node => node.id === options.rootId)
  if (!current || !parent) return { nodes: null, error: '任务已不存在，请重新选择' }
  if (!root) return { nodes: null, error: '请在完整总任务内调整所属位置' }
  if (id === root.id) return { nodes: null, error: '总任务不能移动到子任务下' }
  if (options.closedIds?.includes(root.id)) return { nodes: null, error: '总任务已结束，不能移动子任务' }
  const subtree = new Set([id])
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes) {
      if (node.parentId && subtree.has(node.parentId) && !subtree.has(node.id)) {
        subtree.add(node.id)
        changed = true
      }
    }
  }
  if (subtree.has(parentId)) return { nodes: null, error: '不能移入自身或自己的下级任务' }
  if (
    options.readonly ||
    [...subtree].some(key => options.frozenIds?.includes(key) || options.closedIds?.includes(key))
  )
    return { nodes: null, error: '当前任务或其下级已开始或已结束，不能移动' }
  const lookup = new Map(nodes.map(node => [node.id, node]))
  function inRoot(nodeId: string): boolean {
    const seen = new Set<string>()
    let cursor: string | null = nodeId
    while (cursor && !seen.has(cursor)) {
      if (cursor === root?.id) return true
      seen.add(cursor)
      cursor = lookup.get(cursor)?.parentId || null
    }
    return false
  }
  if (!inRoot(id) || !inRoot(parentId)) return { nodes: null, error: '只能在当前总任务内移动' }
  if (options.closedIds?.includes(parentId)) return { nodes: null, error: '不能移入已结束任务' }
  const result = nodes.map(node => (node.id === id ? { ...node, parentId } : node))
  const error = taskStructureError(result)
  return error ? { nodes: null, error } : { nodes: result, error: null }
}

/** 生成删除影响预览；删除连线不意味着自动连接前后步骤，业务引用必须由用户先处理。 */
export function planTaskRemoval(
  nodes: TaskNodeInput[],
  id: string,
  options: { rootId?: string; frozenIds?: string[]; closedIds?: string[]; readonly?: boolean } = {}
):
  { nodes: TaskNodeInput[]; error: null; removedIds: string[]; removedEdges: number } | { nodes: null; error: string } {
  if (!nodes.some(node => node.id === id)) return { nodes: null, error: '任务已不存在，请重新选择' }
  if (id === options.rootId) return { nodes: null, error: '总任务不能在编排图中删除' }
  if (options.readonly || (options.rootId && options.closedIds?.includes(options.rootId)))
    return { nodes: null, error: '当前任务编排不可删除' }
  const removed = new Set([id])
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes) {
      if (node.parentId && removed.has(node.parentId) && !removed.has(node.id)) {
        removed.add(node.id)
        changed = true
      }
    }
  }
  if ([...removed].some(key => options.frozenIds?.includes(key) || options.closedIds?.includes(key)))
    return { nodes: null, error: '当前任务或其下级已开始或已结束，不能删除' }
  const remaining = nodes.filter(node => !removed.has(node.id))
  const businessReference = remaining.find(
    node =>
      (node.sharing.sourceNodeId && removed.has(node.sharing.sourceNodeId)) ||
      node.entries?.some(entry => entry.sourceNodeId && removed.has(entry.sourceNodeId))
  )
  if (businessReference)
    return { nodes: null, error: `“${businessReference.title || '其他任务'}”仍引用其业务或反馈数据，请先调整引用` }
  const successors = remaining.filter(node => node.predecessorIds.some(key => removed.has(key)))
  if (successors.some(node => options.frozenIds?.includes(node.id) || options.closedIds?.includes(node.id)))
    return { nodes: null, error: '后续任务已开始或已结束，不能删除它等待的步骤' }
  if (
    successors.some(node => node.schedule.mode === 'PREDECESSOR' && node.predecessorIds.every(key => removed.has(key)))
  )
    return { nodes: null, error: '后续任务按前置完成时间安排，请先调整它的预计时间，再删除最后一项前置' }
  const result = remaining.map(node => ({
    ...node,
    predecessorIds: node.predecessorIds.filter(key => !removed.has(key))
  }))
  const error = taskStructureError(result)
  if (error) return { nodes: null, error }
  return {
    nodes: result,
    error: null,
    removedIds: [...removed],
    removedEdges: nodes.reduce(
      (sum, node) => sum + node.predecessorIds.filter(key => removed.has(node.id) || removed.has(key)).length,
      0
    )
  }
}

/** 新增动作直接维护流程边；整体校验成功后才提交，避免插入一半或改到已冻结步骤。 */
export function arrangeTaskSibling<T extends TaskArrangementNode>(
  nodes: T[],
  anchorId: string,
  added: T,
  mode: 'NEXT' | 'PARALLEL',
  options: { rootId?: string; successorId?: string; frozenIds?: string[]; closedIds?: string[] } = {}
): { nodes: T[]; error: null } | { nodes: null; error: string } {
  const anchor = nodes.find(node => node.id === anchorId)
  if (!anchor) return { nodes: null, error: '当前任务已不存在，请重新选择' }
  if (anchor.id === options.rootId || !anchor.parentId)
    return { nodes: null, error: '总任务是整件事的范围，请在框内添加工作或步骤' }
  if (options.closedIds?.includes(anchor.parentId)) return { nodes: null, error: '上级任务已结束，不能新增步骤' }
  const successors = nodes.filter(
    node => node.predecessorIds.includes(anchorId) && (!options.successorId || node.id === options.successorId)
  )
  if (options.successorId && (mode !== 'NEXT' || successors.length !== 1))
    return { nodes: null, error: '这条流程连线已不存在，请重新选择' }
  if (successors.some(node => options.frozenIds?.includes(node.id) || options.closedIds?.includes(node.id)))
    return {
      nodes: null,
      error: `后续步骤已开始或已结束，不能${mode === 'NEXT' ? '插入步骤' : '添加并行任务'}`
    }
  const successorIds = new Set(successors.map(node => node.id))
  const created = {
    ...added,
    parentId: anchor.parentId,
    predecessorIds: mode === 'NEXT' ? [anchorId] : [...anchor.predecessorIds]
  }
  const result = nodes.flatMap(node => {
    const updated = successorIds.has(node.id)
      ? {
          ...node,
          predecessorIds: [
            ...new Set(
              mode === 'NEXT'
                ? node.predecessorIds.map(id => (id === anchorId ? created.id : id))
                : [...node.predecessorIds, created.id]
            )
          ]
        }
      : node
    return node.id === anchorId ? [updated, created] : [updated]
  })
  const error = taskStructureError(result)
  return error ? { nodes: null, error } : { nodes: result, error: null }
}

/** 列表选择和画布连线使用同一校验，包含父任务汇总等待与祖先门控造成的死锁。 */
export function taskDependencyIssue(nodes: TaskArrangementNode[], fromId: string, toId: string): string | null {
  if (fromId === toId) return '不能等待自己完成'
  if (!nodes.some(node => node.id === fromId) || !nodes.some(node => node.id === toId))
    return '任务已不存在，请重新选择'
  return taskStructureError(
    nodes.map(node =>
      node.id === toId ? { ...node, predecessorIds: [...new Set([...node.predecessorIds, fromId])] } : node
    )
  )
}

/** 只整理兄弟任务的显示顺序，不把“排列在后”自动变成执行依赖。 */
export function orderTaskSiblings<T extends TaskArrangementNode>(nodes: T[]): T[] {
  const lookup = new Map(nodes.map(node => [node.id, node]))
  const groups = new Map<string | null, T[]>()
  for (const node of nodes) {
    const key = node.parentId && lookup.has(node.parentId) ? node.parentId : null
    groups.set(key, [...(groups.get(key) || []), node])
  }
  const result: T[] = [],
    visited = new Set<string>()
  function visit(parentId: string | null) {
    const pending = [...(groups.get(parentId) || [])]
    while (pending.length) {
      const ready = pending.findIndex(node => !node.predecessorIds.some(id => pending.some(item => item.id === id)))
      const node = pending.splice(ready < 0 ? 0 : ready, 1)[0]
      if (!node || visited.has(node.id)) continue
      visited.add(node.id)
      result.push(node)
      visit(node.id)
    }
  }
  visit(null)
  // 异常的历史层级仍保留可见，不因整理丢弃任何节点。
  return [...result, ...nodes.filter(node => !visited.has(node.id))]
}
