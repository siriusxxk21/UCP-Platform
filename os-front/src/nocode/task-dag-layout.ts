/** 父子只决定容器嵌套，前置依赖才决定同层顺序；不创建任何隐含执行关系。 */
export interface TaskDagNode {
  id: string
  parentId: string | null
  predecessorIds: readonly string[]
}

export interface TaskDagBox {
  id: string
  parentId: string | null
  x: number
  y: number
  width: number
  height: number
  depth: number
  childCount: number
  collapsed: boolean
}

export interface TaskDagEdge {
  id: string
  fromId: string
  toId: string
  visibleFromId: string
  visibleToId: string
  projected: boolean
  path: string
}

/** 展示边界不属于任务集合，没有任务 ID，也不产生可编辑的业务依赖。 */
export interface TaskDagBoundary {
  kind: 'START' | 'END'
  x: number
  y: number
  width: number
  height: number
}

export interface TaskDagBoundaryEdge {
  boundary: 'START' | 'END'
  taskId: string
  path: string
}

export interface TaskDagLayout {
  nodes: TaskDagBox[]
  edges: TaskDagEdge[]
  boundaries: TaskDagBoundary[]
  boundaryEdges: TaskDagBoundaryEdge[]
  width: number
  height: number
}

// 摘要和折叠提示共用固定标题区，端口、容器内边距及避障一并使用同一尺寸。
export const TASK_DAG_HEADER = 184
export const TASK_DAG_WIDTH = 336
const WIDTH = TASK_DAG_WIDTH
const PADDING = 24
const COLUMN_GAP = 72
const ROW_GAP = 32
const BOUNDARY_WIDTH = 64
const BOUNDARY_HEIGHT = 36
const BOUNDARY_LANE = BOUNDARY_WIDTH + 40

function curve(startX: number, startY: number, endX: number, endY: number) {
  const bend = Math.max(24, Math.abs(endX - startX) / 2)
  return `M ${startX} ${startY} C ${startX + bend} ${startY}, ${endX - bend} ${endY}, ${endX} ${endY}`
}

interface Point {
  x: number
  y: number
}
interface Rectangle {
  left: number
  top: number
  right: number
  bottom: number
}
interface RouteState {
  key: number
  xi: number
  yi: number
  direction: number
  cost: number
  estimate: number
}

/** 正交可见网格只走障碍边缘及空隙，不让虚拟结束线被无关任务遮挡成假依赖。 */
function clearRoute(
  start: Point,
  end: Point,
  boxes: Rectangle[],
  bounds: Rectangle,
  clearance: number
): Point[] | null {
  const obstacles = boxes.map(box => ({
    left: box.left - clearance,
    right: box.right + clearance,
    top: box.top - clearance,
    bottom: box.bottom + clearance
  }))
  const xs = [
    ...new Set([bounds.left, bounds.right, start.x, end.x, ...obstacles.flatMap(box => [box.left, box.right])])
  ]
    .filter(x => x >= bounds.left && x <= bounds.right)
    .sort((a, b) => a - b)
  const ys = [
    ...new Set([bounds.top, bounds.bottom, start.y, end.y, ...obstacles.flatMap(box => [box.top, box.bottom])])
  ]
    .filter(y => y >= bounds.top && y <= bounds.bottom)
    .sort((a, b) => a - b)
  const inside = (x: number, y: number) =>
    obstacles.some(
      box => x > box.left + 0.001 && x < box.right - 0.001 && y > box.top + 0.001 && y < box.bottom - 0.001
    )
  const startX = xs.indexOf(start.x),
    startY = ys.indexOf(start.y),
    endX = xs.indexOf(end.x),
    endY = ys.indexOf(end.y)
  if (startX < 0 || startY < 0 || endX < 0 || endY < 0 || inside(start.x, start.y) || inside(end.x, end.y)) return null
  const queue: RouteState[] = [],
    costs = new Map<number, number>(),
    previous = new Map<number, number>()
  const freePoints = new Map<number, boolean>()
  function push(state: RouteState) {
    queue.push(state)
    let index = queue.length - 1
    while (index > 0) {
      const parent = Math.floor((index - 1) / 2)
      const item = queue[parent]
      if (!item || item.estimate <= state.estimate) break
      queue[index] = item
      index = parent
    }
    queue[index] = state
  }
  function pop(): RouteState | undefined {
    const head = queue[0],
      tail = queue.pop()
    if (!head || !tail || !queue.length) return head
    let index = 0
    while (index * 2 + 1 < queue.length) {
      let child = index * 2 + 1
      const left = queue[child],
        right = queue[child + 1]
      if (left && right && right.estimate < left.estimate) child++
      const next = queue[child]
      if (!next || tail.estimate <= next.estimate) break
      queue[index] = next
      index = child
    }
    queue[index] = tail
    return head
  }
  const origin = (startY * xs.length + startX) * 3 + 1
  costs.set(origin, 0)
  push({ key: origin, xi: startX, yi: startY, direction: 1, cost: 0, estimate: 0 })
  while (queue.length) {
    const current = pop()
    if (!current || current.cost !== costs.get(current.key)) continue
    if (current.xi === endX && current.yi === endY) {
      const points: Point[] = []
      let key: number | undefined = current.key
      while (key !== undefined) {
        const index = Math.floor(key / 3)
        points.unshift({ x: xs[index % xs.length] || 0, y: ys[Math.floor(index / xs.length)] || 0 })
        key = previous.get(key)
      }
      return points
    }
    const x = xs[current.xi] || 0,
      y = ys[current.yi] || 0
    for (const [dx, dy, direction] of [
      [-1, 0, 1],
      [1, 0, 1],
      [0, -1, 2],
      [0, 1, 2]
    ]) {
      const xi = current.xi + (dx || 0),
        yi = current.yi + (dy || 0)
      if (xi < 0 || yi < 0 || xi >= xs.length || yi >= ys.length) continue
      const nextX = xs[xi] || 0,
        nextY = ys[yi] || 0,
        pointKey = yi * xs.length + xi
      if (!freePoints.has(pointKey)) freePoints.set(pointKey, !inside(nextX, nextY))
      if (!freePoints.get(pointKey) || inside((x + nextX) / 2, (y + nextY) / 2)) continue
      const nextDirection = direction || 1
      const key = pointKey * 3 + nextDirection
      const cost =
        current.cost + Math.abs(nextX - x) + Math.abs(nextY - y) + (current.direction === nextDirection ? 0 : 16)
      if (cost >= (costs.get(key) ?? Infinity)) continue
      costs.set(key, cost)
      previous.set(key, current.key)
      push({
        key,
        xi,
        yi,
        direction: nextDirection,
        cost,
        estimate: cost + Math.abs(end.x - nextX) + Math.abs(end.y - nextY)
      })
    }
  }
  return null
}

function orthogonalPath(start: Point, end: Point, boxes: Rectangle[], bounds: Rectangle) {
  const escape = { x: start.x + 12, y: start.y },
    entry = { x: end.x - 12, y: end.y }
  const route = clearRoute(escape, entry, boxes, bounds, 8) || clearRoute(escape, entry, boxes, bounds, 0)
  // 仅异常草稿（例如重叠或不成立的端点）保留可查看连线；有效层级的空隙始终可达。
  if (!route) return curve(start.x, start.y, end.x, end.y)
  const points: Point[] = []
  for (const point of [start, ...route, end]) {
    const previous = points.at(-1),
      before = points.at(-2)
    if (previous?.x === point.x && previous.y === point.y) continue
    if (
      previous &&
      before &&
      ((before.x === previous.x && previous.x === point.x) || (before.y === previous.y && previous.y === point.y))
    )
      points.pop()
    points.push(point)
  }
  return points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' ')
}

/** 局部授权视图不补齐缺失节点；折叠连线保留原始端点，避免断开错误依赖。 */
export function layoutTaskDag(
  input: readonly TaskDagNode[],
  options: { collapsedIds?: readonly string[]; rootId?: string } = {}
): TaskDagLayout {
  const lookup = new Map(input.map(node => [node.id, node]))
  const parents = new Map<string, string | null>()
  for (const node of lookup.values()) {
    const seen = new Set([node.id])
    let cursor = node.parentId
    while (cursor && lookup.has(cursor) && !seen.has(cursor)) {
      seen.add(cursor)
      cursor = lookup.get(cursor)!.parentId
    }
    parents.set(node.id, cursor && seen.has(cursor) ? null : lookup.has(node.parentId || '') ? node.parentId : null)
  }
  const groups = new Map<string | null, string[]>()
  for (const id of lookup.keys()) {
    const parent = parents.get(id) || null
    groups.set(parent, [...(groups.get(parent) || []), id])
  }
  const chain = (id: string) => {
    const ids = [id]
    let parent = parents.get(id)
    while (parent) {
      ids.unshift(parent)
      parent = parents.get(parent)
    }
    return ids
  }
  const dependencies = [...lookup.values()].flatMap(node =>
    [...new Set(node.predecessorIds || [])]
      .filter(id => lookup.has(id) && id !== node.id && id !== options.rootId && node.id !== options.rootId)
      .map(fromId => ({ fromId, toId: node.id }))
  )
  const siblingEdges = new Map<string | null, Array<[string, string]>>()
  for (const edge of dependencies) {
    const from = chain(edge.fromId),
      to = chain(edge.toId)
    let index = 0
    while (from[index] && from[index] === to[index]) index++
    // 跨层连线提升至共同父级下的两个分组，不能把父节点当成前置步骤。
    if (!from[index] || !to[index]) continue
    const parent = index ? from[index - 1]! : null
    siblingEdges.set(parent, [...(siblingEdges.get(parent) || []), [from[index]!, to[index]!]])
  }
  const collapsed = new Set(options.collapsedIds || [])
  const sizes = new Map<string, { width: number; height: number }>()
  const offsets = new Map<string, { x: number; y: number }>()
  const arrange = (parent: string | null): { width: number; height: number } => {
    const children = groups.get(parent) || []
    const incoming = new Map(children.map(id => [id, 0]))
    const ranks = new Map(children.map(id => [id, 0]))
    const edges = siblingEdges.get(parent) || []
    for (const [, to] of edges) incoming.set(to, incoming.get(to)! + 1)
    const queue = children.filter(id => !incoming.get(id))
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index]!
      for (const [from, to] of edges) {
        if (from !== id) continue
        ranks.set(to, Math.max(ranks.get(to)!, ranks.get(id)! + 1))
        incoming.set(to, incoming.get(to)! - 1)
        if (!incoming.get(to)) queue.push(to)
      }
    }
    // 未通过业务校验的循环草稿也能呈现；业务校验由编排编辑器统一负责。
    const columns = new Map<number, string[]>()
    for (const id of children) {
      const inside = !collapsed.has(id) && groups.has(id) ? arrange(id) : null
      sizes.set(
        id,
        id === options.rootId
          ? {
              width: Math.max(WIDTH, inside?.width || 0) + PADDING * 2 + BOUNDARY_LANE * 2,
              height: TASK_DAG_HEADER + Math.max(TASK_DAG_HEADER, inside?.height || 0) + PADDING
            }
          : inside
            ? { width: Math.max(WIDTH, inside.width + PADDING * 2), height: TASK_DAG_HEADER + inside.height + PADDING }
            : { width: WIDTH, height: TASK_DAG_HEADER }
      )
      const rank = ranks.get(id)!
      columns.set(rank, [...(columns.get(rank) || []), id])
    }
    let x = 0,
      height = 0
    const columnHeights = new Map<number, number>()
    for (const rank of [...columns.keys()].sort((a, b) => a - b)) {
      let y = 0,
        width = 0
      for (const id of columns.get(rank)!) {
        const size = sizes.get(id)!
        offsets.set(id, { x, y })
        width = Math.max(width, size.width)
        y += size.height + ROW_GAP
      }
      height = Math.max(height, y - ROW_GAP)
      columnHeights.set(rank, y - ROW_GAP)
      x += width + COLUMN_GAP
    }
    // 各列居中，让并行分支自然从相同起点分出、向相同后继汇合。
    for (const [rank, ids] of columns) {
      const shift = (height - (columnHeights.get(rank) || 0)) / 2
      for (const id of ids) {
        const offset = offsets.get(id)
        if (offset) offsets.set(id, { ...offset, y: offset.y + shift })
      }
    }
    // 后续列跟随自身前置所在支路，不把唯一后继居中到另一条独立支路。
    for (const rank of [...columns.keys()].sort((a, b) => a - b)) {
      if (rank === 0) continue
      const ids = columns.get(rank) || []
      const prefixes: number[] = []
      const blocks: Array<{ first: number; last: number; mean: number; weight: number }> = []
      let prefix = 0
      ids.forEach((id, index) => {
        prefixes.push(prefix)
        const sources = [...new Set(edges.filter(([, to]) => to === id).map(([from]) => from))].filter(
          from => (ranks.get(from) || 0) < rank
        )
        const desired = sources.length
          ? sources.reduce((sum, from) => sum + (offsets.get(from)?.y || 0), 0) / sources.length
          : offsets.get(id)?.y || 0
        blocks.push({ first: index, last: index, mean: desired - prefix, weight: 1 })
        while (blocks.length > 1) {
          const current = blocks.at(-1),
            previous = blocks.at(-2)
          if (!current || !previous || previous.mean <= current.mean) break
          blocks.pop()
          blocks.pop()
          const weight = previous.weight + current.weight
          blocks.push({
            first: previous.first,
            last: current.last,
            weight,
            mean: (previous.mean * previous.weight + current.mean * current.weight) / weight
          })
        }
        prefix += (sizes.get(id)?.height || 0) + ROW_GAP
      })
      for (const block of blocks)
        for (let index = block.first; index <= block.last; index++) {
          const id = ids[index]
          if (!id) continue
          const offset = offsets.get(id)
          if (!offset) continue
          const y = Math.max(0, block.mean) + (prefixes[index] || 0)
          offsets.set(id, { ...offset, y })
          height = Math.max(height, y + (sizes.get(id)?.height || 0))
        }
    }
    return { width: Math.max(0, x - COLUMN_GAP), height }
  }
  const overall = arrange(null)
  const nodes: TaskDagBox[] = []
  const place = (parent: string | null, x: number, y: number, depth: number) => {
    for (const id of groups.get(parent) || []) {
      const offset = offsets.get(id)!,
        size = sizes.get(id)!
      const node = {
        id,
        parentId: parent,
        x: x + offset.x,
        y: y + offset.y,
        ...size,
        depth,
        childCount: groups.get(id)?.length || 0,
        collapsed: collapsed.has(id)
      }
      nodes.push(node)
      if (!node.collapsed)
        place(id, node.x + PADDING + (id === options.rootId ? BOUNDARY_LANE : 0), node.y + TASK_DAG_HEADER, depth + 1)
    }
  }
  place(null, PADDING, PADDING, 0)
  const visible = new Map(nodes.map(node => [node.id, node]))
  const representative = (id: string) => chain(id).find(key => collapsed.has(key)) || id
  const edges: TaskDagEdge[] = dependencies.flatMap(({ fromId, toId }, index) => {
    const visibleFromId = representative(fromId),
      visibleToId = representative(toId)
    if (visibleFromId === visibleToId) return []
    const from = visible.get(visibleFromId),
      to = visible.get(visibleToId)
    if (!from || !to) return []
    const startX = from.x + from.width,
      startY = from.y + TASK_DAG_HEADER / 2
    const endX = to.x,
      endY = to.y + TASK_DAG_HEADER / 2
    return [
      {
        id: `${fromId}:${toId}:${index}`,
        fromId,
        toId,
        visibleFromId,
        visibleToId,
        projected: visibleFromId !== fromId || visibleToId !== toId,
        path: curve(startX, startY, endX, endY)
      }
    ]
  })
  const boundaries: TaskDagBoundary[] = [],
    boundaryEdges: TaskDagBoundaryEdge[] = []
  const root = options.rootId ? visible.get(options.rootId) : undefined
  if (root) {
    const y = root.y + TASK_DAG_HEADER + (root.height - TASK_DAG_HEADER - PADDING - BOUNDARY_HEIGHT) / 2
    const start: TaskDagBoundary = {
      kind: 'START',
      x: root.x + PADDING,
      y,
      width: BOUNDARY_WIDTH,
      height: BOUNDARY_HEIGHT
    }
    const end: TaskDagBoundary = { ...start, kind: 'END', x: root.x + root.width - PADDING - BOUNDARY_WIDTH }
    boundaries.push(start, end)
    if (!root.collapsed) {
      for (const id of groups.get(root.id) || []) {
        const task = visible.get(id)
        if (!task) continue
        // 子节点跨层依赖只影响布局，不能推导成整个父任务的先后关系。
        if (!lookup.get(id)?.predecessorIds.length)
          boundaryEdges.push({
            boundary: 'START',
            taskId: id,
            path: curve(start.x + start.width, y + BOUNDARY_HEIGHT / 2, task.x, task.y + TASK_DAG_HEADER / 2)
          })
        if (![...lookup.values()].some(node => node.predecessorIds.includes(id)))
          boundaryEdges.push({
            boundary: 'END',
            taskId: id,
            path: curve(task.x + task.width, task.y + TASK_DAG_HEADER / 2, end.x, y + BOUNDARY_HEIGHT / 2)
          })
      }
    }
  }
  const canvas: Rectangle = {
    left: 8,
    top: 8,
    right: overall.width + PADDING * 2 - 8,
    bottom: overall.height + PADDING * 2 - 8
  }
  const route = (start: Point, end: Point, fromId?: string, toId?: string) => {
    const fromChain = fromId ? chain(fromId) : root ? [root.id] : []
    const toChain = toId ? chain(toId) : root ? [root.id] : []
    const ancestors = new Set([...fromChain.slice(0, -1), ...toChain.slice(0, -1)])
    // 出入某个容器内部时允许穿过其空白，不允许穿过其标题或无关容器。
    if (!fromId && root) ancestors.add(root.id)
    if (!toId && root) ancestors.add(root.id)
    let commonId: string | undefined
    for (let index = 0; index < fromChain.length && fromChain[index] === toChain[index]; index++)
      commonId = fromChain[index]
    const common = commonId && commonId !== fromId && commonId !== toId ? visible.get(commonId) : undefined
    const bounds = common
      ? {
          left: common.x + 8,
          top: common.y + TASK_DAG_HEADER,
          right: common.x + common.width - 8,
          bottom: common.y + common.height - 8
        }
      : canvas
    const boxes: Rectangle[] = nodes.map(node => ({
      left: node.x,
      top: node.y,
      right: node.x + node.width,
      bottom: node.y + (ancestors.has(node.id) ? TASK_DAG_HEADER - 8 : node.height)
    }))
    boxes.push(
      ...boundaries.map(boundary => ({
        left: boundary.x,
        top: boundary.y,
        right: boundary.x + boundary.width,
        bottom: boundary.y + boundary.height
      }))
    )
    return orthogonalPath(start, end, boxes, bounds)
  }
  for (const edge of edges) {
    const from = visible.get(edge.visibleFromId),
      to = visible.get(edge.visibleToId)
    if (from && to)
      edge.path = route(
        { x: from.x + from.width, y: from.y + TASK_DAG_HEADER / 2 },
        { x: to.x, y: to.y + TASK_DAG_HEADER / 2 },
        from.id,
        to.id
      )
  }
  for (const edge of boundaryEdges) {
    const task = visible.get(edge.taskId),
      boundary = boundaries.find(item => item.kind === edge.boundary)
    if (!task || !boundary) continue
    edge.path =
      edge.boundary === 'START'
        ? route(
            { x: boundary.x + boundary.width, y: boundary.y + boundary.height / 2 },
            { x: task.x, y: task.y + TASK_DAG_HEADER / 2 },
            undefined,
            task.id
          )
        : route(
            { x: task.x + task.width, y: task.y + TASK_DAG_HEADER / 2 },
            { x: boundary.x, y: boundary.y + boundary.height / 2 },
            task.id
          )
  }
  return {
    nodes,
    edges,
    boundaries,
    boundaryEdges,
    width: Math.max(360, overall.width + PADDING * 2),
    height: Math.max(160, overall.height + PADDING * 2)
  }
}
