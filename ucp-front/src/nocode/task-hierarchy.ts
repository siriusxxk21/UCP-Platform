/** 只从当前已授权、已加载节点派生展示层级；编号用于当前视图，不作为任务身份。 */
export interface TaskHierarchyNode {
  id: string
  parentId: string | null
  title: string
  rootId?: string
}

export interface TaskHierarchyItem {
  depth: number
  outline: string
  label: string
  parentTitle: string
  missingParent: boolean
  childCount: number
}

export function taskHierarchy(
  nodes: readonly TaskHierarchyNode[],
  options: { draft?: boolean; rootId?: string } = {}
): Map<string, TaskHierarchyItem> {
  const lookup = new Map(nodes.map(node => [node.id, node]))
  const groups = new Map<string | null, TaskHierarchyNode[]>()
  for (const node of lookup.values()) {
    const group = groups.get(node.parentId) || []
    group.push(node)
    groups.set(node.parentId, group)
  }
  const result = new Map<string, TaskHierarchyItem>()
  for (const node of lookup.values()) {
    const chain: TaskHierarchyNode[] = []
    const seen = new Set<string>()
    let cursor: TaskHierarchyNode | undefined = node
    let missingParent = false
    while (cursor) {
      if (seen.has(cursor.id)) {
        missingParent = true
        break
      }
      seen.add(cursor.id)
      chain.unshift(cursor)
      if (cursor.parentId && !lookup.has(cursor.parentId)) missingParent = true
      cursor = cursor.parentId ? lookup.get(cursor.parentId) : undefined
    }
    const isRoot = options.rootId
      ? node.id === options.rootId
      : !options.draft && (node.rootId ? node.id === node.rootId : !node.parentId)
    // 筛选结果中的子项可能缺少上级；不能据此赋予“总任务”身份或伪造完整编号。
    if (!options.draft && node.rootId && !lookup.has(node.rootId)) missingParent = true
    const outline = missingParent
      ? ''
      : chain.map(item => (groups.get(item.parentId) || []).findIndex(sibling => sibling.id === item.id) + 1).join('.')
    const parent = node.parentId ? lookup.get(node.parentId) : undefined
    result.set(node.id, {
      depth: Math.max(0, chain.length - 1),
      outline,
      label: isRoot ? '总任务' : options.draft && !node.parentId ? '一级子任务' : '子任务',
      parentTitle: parent
        ? parent.title.trim() || '未命名上级任务'
        : node.parentId || missingParent
          ? '上级任务未在当前视图中'
          : options.draft && !isRoot
            ? '发起时的总任务'
            : '',
      missingParent,
      childCount: groups.get(node.id)?.length || 0
    })
  }
  return result
}

/** 下拉项同时显示编号和直接上级，避免在同名节点之间误选。 */
export function taskHierarchyLabel(node: TaskHierarchyNode, item?: TaskHierarchyItem): string {
  const title = node.title.trim() || '未命名任务'
  if (!item) return title
  return `${item.label}${item.outline ? ` ${item.outline}` : ''} · ${title}${item.parentTitle ? `（上级：${item.parentTitle}）` : ''}`
}
