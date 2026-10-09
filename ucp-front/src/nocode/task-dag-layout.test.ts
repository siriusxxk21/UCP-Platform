import { describe, expect, it } from 'vitest'
import {
  layoutTaskDag,
  TASK_DAG_HEADER,
  TASK_DAG_WIDTH,
  type TaskDagBox,
  type TaskDagNode,
  type TaskDagLayout
} from './task-dag-layout'

const node = (id: string, parentId: string | null = null, predecessorIds: string[] = []): TaskDagNode => ({
  id,
  parentId,
  predecessorIds
})
function required<T>(value: T | undefined): T {
  if (value === undefined) throw new Error('预期布局节点不存在')
  return value
}
function inside(child: TaskDagBox, parent: TaskDagBox) {
  expect(child.x).toBeGreaterThan(parent.x)
  expect(child.y).toBeGreaterThanOrEqual(parent.y + TASK_DAG_HEADER)
  expect(child.x + child.width).toBeLessThan(parent.x + parent.width)
  expect(child.y + child.height).toBeLessThan(parent.y + parent.height)
}
function disjoint(a: TaskDagBox, b: TaskDagBox) {
  expect(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y).toBe(true)
}

function avoidsUnrelatedBoxes(layout: TaskDagLayout) {
  const lookup = new Map(layout.nodes.map(box => [box.id, box]))
  const ancestorOf = (parent: string, id: string | undefined) => {
    const seen = new Set<string>()
    let cursor = id ? lookup.get(id)?.parentId : null
    while (cursor && !seen.has(cursor)) {
      if (cursor === parent) return true
      seen.add(cursor)
      cursor = lookup.get(cursor)?.parentId
    }
    return false
  }
  const routes = [
    ...layout.edges.map(edge => ({ ...edge, from: edge.visibleFromId, to: edge.visibleToId })),
    ...layout.boundaryEdges.map(edge => ({
      ...edge,
      from: edge.boundary === 'END' ? edge.taskId : undefined,
      to: edge.boundary === 'START' ? edge.taskId : undefined
    }))
  ]
  for (const route of routes) {
    expect(route.path, `未找到避障路径：${route.from}→${route.to}`).not.toContain('C')
    const coordinates = (route.path.match(/-?\d+(?:\.\d+)?/g) || []).map(Number)
    expect(coordinates.length).toBeGreaterThanOrEqual(4)
    for (const box of layout.nodes) {
      if (box.id === route.from || box.id === route.to) continue
      const bottom =
        box.y + (ancestorOf(box.id, route.from) || ancestorOf(box.id, route.to) ? TASK_DAG_HEADER - 8 : box.height)
      for (let index = 2; index < coordinates.length; index += 2) {
        const x1 = required(coordinates[index - 2]),
          y1 = required(coordinates[index - 1])
        const x2 = required(coordinates[index]),
          y2 = required(coordinates[index + 1])
        expect(x1 === x2 || y1 === y2).toBe(true)
        const intersects =
          x1 === x2
            ? x1 > box.x &&
              x1 < box.x + box.width &&
              Math.max(Math.min(y1, y2), box.y) < Math.min(Math.max(y1, y2), bottom)
            : y1 > box.y &&
              y1 < bottom &&
              Math.max(Math.min(x1, x2), box.x) < Math.min(Math.max(x1, x2), box.x + box.width)
        expect(intersects, `${route.from || '开始'}→${route.to || '结束'} 穿过 ${box.id}: ${route.path}`).toBe(false)
      }
    }
  }
}

describe('嵌套任务图布局', () => {
  it('扩展图卡摘要区同步改变容器、端口和避障，不压住折叠子项或跨层连线', () => {
    const result = layoutTaskDag(
      [
        node('root'),
        node('A', 'root'),
        node('A1', 'A'),
        node('B', 'root'),
        node('B1', 'B', ['A1']),
        node('C', 'root', ['B'])
      ],
      { rootId: 'root' }
    )
    const leaf = required(result.nodes.find(value => value.id === 'A1'))
    expect(leaf.width).toBe(TASK_DAG_WIDTH)
    expect(leaf.width).toBeGreaterThanOrEqual(320)
    expect(leaf.height).toBe(TASK_DAG_HEADER)
    expect(TASK_DAG_HEADER).toBeGreaterThanOrEqual(176)
    const edge = required(result.edges.find(value => value.fromId === 'A1'))
    expect(edge.path).toMatch(new RegExp('^M ' + (leaf.x + leaf.width) + ' ' + (leaf.y + TASK_DAG_HEADER / 2) + ' '))
    for (const child of result.nodes)
      if (child.parentId) inside(child, required(result.nodes.find(value => value.id === child.parentId)))
    avoidsUnrelatedBoxes(result)
    const folded = layoutTaskDag([node('root'), node('A', 'root'), node('A1', 'A'), node('B', 'root', ['A1'])], {
      rootId: 'root',
      collapsedIds: ['A']
    })
    expect(required(folded.nodes.find(value => value.id === 'A')).height).toBe(TASK_DAG_HEADER)
    avoidsUnrelatedBoxes(folded)
  })
  it('根容器内的连续步骤同层排列，各层兄弟互不重叠', () => {
    const input = [
      node('root'),
      node('A', 'root'),
      node('B', 'root', ['A']),
      node('C', 'root', ['B']),
      node('A1', 'A'),
      node('A2', 'A', ['A1']),
      node('B1', 'B'),
      node('B2', 'B'),
      node('leaf', 'A2')
    ]
    const result = layoutTaskDag(input, { rootId: 'root' })
    const boxes = new Map(result.nodes.map(box => [box.id, box]))
    for (const item of result.nodes) {
      if (item.parentId) inside(item, required(boxes.get(item.parentId)))
      for (const other of result.nodes)
        if (item.id !== other.id && item.parentId === other.parentId) disjoint(item, other)
    }
    expect(required(boxes.get('B')).x).toBeGreaterThan(required(boxes.get('A')).x + required(boxes.get('A')).width)
    expect(required(boxes.get('C')).x).toBeGreaterThan(required(boxes.get('B')).x + required(boxes.get('B')).width)
    expect(required(boxes.get('A')).parentId).toBe(required(boxes.get('B')).parentId)
    expect(result.edges).toHaveLength(3)
    expect(result.edges.every(edge => edge.fromId !== 'root' && edge.toId !== 'root')).toBe(true)
  })

  it('拓扑排列不依赖输入顺序，不用父子层级创建先后箭头', () => {
    const result = layoutTaskDag([node('C', null, ['B']), node('A'), node('B', null, ['A']), node('child', 'A')])
    const boxes = new Map(result.nodes.map(box => [box.id, box]))
    expect(required(boxes.get('A')).x).toBeLessThan(required(boxes.get('B')).x)
    expect(required(boxes.get('B')).x).toBeLessThan(required(boxes.get('C')).x)
    expect(result.edges.map(edge => [edge.fromId, edge.toId])).toEqual([
      ['B', 'C'],
      ['A', 'B']
    ])
  })

  it('多层嵌套只增加容器内边距，不把每级父子排成执行横链', () => {
    const input = Array.from({ length: 24 }, (_, index) => node(`level${index}`, index ? `level${index - 1}` : null))
    const result = layoutTaskDag(input, { rootId: 'level0' })
    expect(result.nodes).toHaveLength(24)
    expect(result.edges).toHaveLength(0)
    for (let index = 1; index < result.nodes.length; index++)
      inside(required(result.nodes[index]), required(result.nodes[index - 1]))
    expect(result.width).toBeLessThan(24 * 100)
  })

  it('跨层依赖提升分组顺序，箭头仍然连接原始节点', () => {
    const result = layoutTaskDag(
      [node('root'), node('B', 'root'), node('target', 'B', ['leaf']), node('A', 'root'), node('leaf', 'A')],
      { rootId: 'root' }
    )
    const boxes = new Map(result.nodes.map(box => [box.id, box]))
    expect(required(boxes.get('B')).x).toBeGreaterThan(required(boxes.get('A')).x + required(boxes.get('A')).width)
    expect(result.edges[0]).toMatchObject({
      fromId: 'leaf',
      toId: 'target',
      visibleFromId: 'leaf',
      visibleToId: 'target',
      projected: false
    })
  })

  it('折叠端点投影到可见祖先，断开时保留实际依赖身份', () => {
    const input = [
      node('root'),
      node('A', 'root'),
      node('nested', 'A'),
      node('leaf', 'nested'),
      node('B', 'root'),
      node('target', 'B', ['leaf'])
    ]
    const result = layoutTaskDag(input, { rootId: 'root', collapsedIds: ['A', 'nested', 'B'] })
    expect(result.nodes.map(box => box.id)).toEqual(['root', 'A', 'B'])
    expect(result.edges[0]).toMatchObject({
      fromId: 'leaf',
      toId: 'target',
      visibleFromId: 'A',
      visibleToId: 'B',
      projected: true
    })
    expect(layoutTaskDag(input, { rootId: 'root', collapsedIds: ['root'] }).edges).toEqual([])
  })

  it('局部授权视图不生成缺失父任务、缺失前置或根依赖', () => {
    const result = layoutTaskDag([node('root', null, ['a']), node('a', 'hidden', ['root', 'hidden']), node('b', 'a')], {
      rootId: 'root'
    })
    expect(result.nodes.map(box => box.id)).toEqual(['root', 'a', 'b'])
    expect(result.edges).toEqual([])
    expect(required(result.nodes.find(box => box.id === 'a')).parentId).toBeNull()
  })

  it('循环草稿和重复依赖可安全展示且不改写源数据', () => {
    const input = [node('A', 'B', ['C', 'C']), node('B', 'A', ['A']), node('C', null, ['B'])]
    const before = JSON.stringify(input)
    const result = layoutTaskDag(input)
    expect(result.nodes).toHaveLength(3)
    expect(result.edges).toHaveLength(3)
    expect(result.nodes.every(box => Number.isFinite(box.x) && Number.isFinite(box.y))).toBe(true)
    expect(JSON.stringify(input)).toBe(before)
  })

  it('无节点时仍有可交互画布尺寸', () => {
    expect(layoutTaskDag([])).toEqual({
      nodes: [],
      edges: [],
      boundaries: [],
      boundaryEdges: [],
      width: 360,
      height: 160
    })
  })

  it('一个总任务只画一对虚拟边界，顺序步骤只连接首尾且不写入业务节点', () => {
    const input = [node('root'), node('A', 'root'), node('B', 'root', ['A']), node('C', 'root', ['B']), node('A1', 'A')]
    const before = JSON.stringify(input)
    const result = layoutTaskDag(input, { rootId: 'root' })
    expect(result.boundaries.map(boundary => boundary.kind)).toEqual(['START', 'END'])
    expect(result.boundaries.every(boundary => !('id' in boundary))).toBe(true)
    expect(result.boundaryEdges.map(edge => [edge.boundary, edge.taskId])).toEqual([
      ['START', 'A'],
      ['END', 'C']
    ])
    expect(result.nodes).toHaveLength(input.length)
    expect(result.edges).toHaveLength(2)
    expect(JSON.stringify(input)).toBe(before)
    const root = required(result.nodes.find(box => box.id === 'root'))
    for (const boundary of result.boundaries) {
      expect(boundary.x).toBeGreaterThan(root.x)
      expect(boundary.y).toBeGreaterThan(root.y + TASK_DAG_HEADER)
      expect(boundary.x + boundary.width).toBeLessThan(root.x + root.width)
      expect(boundary.y + boundary.height).toBeLessThan(root.y + root.height)
    }
  })

  it('并行分支共享起终点且前后节点居中，读图能看出分出和汇合', () => {
    const result = layoutTaskDag(
      [
        node('root'),
        node('A', 'root'),
        node('B', 'root', ['A']),
        node('B2', 'root', ['A']),
        node('C', 'root', ['B', 'B2'])
      ],
      { rootId: 'root' }
    )
    const boxes = new Map(result.nodes.map(box => [box.id, box]))
    expect(required(boxes.get('B')).x).toBe(required(boxes.get('B2')).x)
    expect(required(boxes.get('A')).y).toBe((required(boxes.get('B')).y + required(boxes.get('B2')).y) / 2)
    expect(required(boxes.get('C')).y).toBe(required(boxes.get('A')).y)
    expect(result.boundaryEdges.map(edge => [edge.boundary, edge.taskId])).toEqual([
      ['START', 'A'],
      ['END', 'C']
    ])
    const parallel = layoutTaskDag([node('root'), node('A', 'root'), node('B', 'root')], { rootId: 'root' })
    expect(parallel.boundaryEdges.filter(edge => edge.boundary === 'START').map(edge => edge.taskId)).toEqual([
      'A',
      'B'
    ])
    expect(parallel.boundaryEdges.filter(edge => edge.boundary === 'END').map(edge => edge.taskId)).toEqual(['A', 'B'])
  })

  it('深层折叠不增加虚拟子流程，子项发出的边不取消父任务的结束连线', () => {
    const input = [node('root'), node('A', 'root'), node('A1', 'A'), node('B', 'root', ['A1'])]
    const result = layoutTaskDag(input, { rootId: 'root', collapsedIds: ['A'] })
    expect(result.boundaries).toHaveLength(2)
    expect(result.boundaryEdges.map(edge => [edge.boundary, edge.taskId])).toEqual([
      ['START', 'A'],
      ['END', 'A'],
      ['END', 'B']
    ])
    const closed = layoutTaskDag(input, { rootId: 'root', collapsedIds: ['root'] })
    expect(closed.nodes).toHaveLength(1)
    expect(closed.boundaries).toHaveLength(2)
    expect(closed.boundaryEdges).toEqual([])
    expect(closed.nodes[0]?.height).toBeGreaterThan(TASK_DAG_HEADER * 2)
  })

  it('子任务跨层依赖不伪装成整个父任务的先后关系', () => {
    const input = [node('root'), node('A', 'root'), node('A1', 'A'), node('B', 'root'), node('B1', 'B', ['A1'])]
    for (const collapsedIds of [[], ['A'], ['A', 'B']]) {
      const result = layoutTaskDag(input, { rootId: 'root', collapsedIds })
      expect(result.boundaryEdges.map(edge => [edge.boundary, edge.taskId])).toEqual([
        ['START', 'A'],
        ['END', 'A'],
        ['START', 'B'],
        ['END', 'B']
      ])
      expect(result.edges).toHaveLength(1)
      expect(result.edges[0]).toMatchObject({ fromId: 'A1', toId: 'B1' })
    }
  })

  it('顶级项存在未加载前置时不伪造无前置的开始连线', () => {
    const result = layoutTaskDag([node('root'), node('A', 'root', ['hidden'])], { rootId: 'root' })
    expect(result.boundaryEdges.map(edge => [edge.boundary, edge.taskId])).toEqual([['END', 'A']])
  })

  it('未拆分的总任务保留完整大框；缺失根的局部图不伪造开始结束', () => {
    const empty = layoutTaskDag([node('root')], { rootId: 'root' })
    expect(empty.nodes).toHaveLength(1)
    expect(empty.nodes[0]?.width).toBeGreaterThan(400)
    expect(empty.nodes[0]?.height).toBeGreaterThan(200)
    expect(empty.boundaries).toHaveLength(2)
    expect(empty.edges).toEqual([])
    expect(empty.boundaryEdges).toEqual([])
    expect(layoutTaskDag([node('child', 'root')], { rootId: 'root' }).boundaries).toEqual([])
  })

  it('三个独立分工仅A添加D时对齐A支路，B/C结束线不穿过D', () => {
    const input = [node('root'), node('A', 'root'), node('D', 'root', ['A']), node('B', 'root'), node('C', 'root')]
    const before = JSON.stringify(input)
    const result = layoutTaskDag(input, { rootId: 'root' })
    const a = required(result.nodes.find(box => box.id === 'A')),
      d = required(result.nodes.find(box => box.id === 'D'))
    expect(d.y).toBe(a.y)
    expect(result.edges.map(edge => [edge.fromId, edge.toId])).toEqual([['A', 'D']])
    expect(result.boundaryEdges.filter(edge => edge.boundary === 'END').map(edge => edge.taskId)).toEqual([
      'D',
      'B',
      'C'
    ])
    avoidsUnrelatedBoxes(result)
    expect(JSON.stringify(input)).toBe(before)
  })

  it('后继展开为大容器时，独立分工结束线绕过整个容器而非只绕标题', () => {
    const input = [
      node('root'),
      node('A', 'root'),
      node('D', 'root', ['A']),
      node('D1', 'D'),
      node('D2', 'D'),
      node('B', 'root'),
      node('C', 'root')
    ]
    for (const collapsedIds of [[], ['D']]) avoidsUnrelatedBoxes(layoutTaskDag(input, { rootId: 'root', collapsedIds }))
  })

  it('多列中的越级真实依赖和较早结束的独立分工都不穿越中间卡片', () => {
    const input = [
      node('root'),
      node('A', 'root'),
      node('B', 'root'),
      node('C', 'root'),
      node('A2', 'root', ['A']),
      node('B2', 'root', ['B']),
      node('A3', 'root', ['A2', 'B']),
      node('last', 'root', ['A3', 'B2', 'C'])
    ]
    const result = layoutTaskDag(input, { rootId: 'root' })
    avoidsUnrelatedBoxes(result)
    expect(result.edges).toHaveLength(7)
  })

  it('跨层端点可以进出自己的祖先，不能横穿无关展开容器或容器标题', () => {
    const input = [
      node('root'),
      node('A', 'root'),
      node('A1', 'A'),
      node('A2', 'A', ['A1']),
      node('middle', 'root'),
      node('middle1', 'middle'),
      node('middle2', 'middle'),
      node('B', 'root'),
      node('B1', 'B', ['A2']),
      node('end', 'root', ['A', 'B'])
    ]
    for (const collapsedIds of [[], ['A'], ['B'], ['middle'], ['A', 'B']]) {
      const result = layoutTaskDag(input, { rootId: 'root', collapsedIds })
      avoidsUnrelatedBoxes(result)
      expect(result.edges.some(edge => edge.fromId === 'A2' && edge.toId === 'B1')).toBe(true)
    }
  })

  it('起点并行和后续分支汇合都保持真实边且不会为避障移动任务顺序', () => {
    const input = [
      node('root'),
      node('A', 'root'),
      node('parallel', 'root'),
      node('B', 'root', ['A', 'parallel']),
      node('branch1', 'root', ['B']),
      node('branch2', 'root', ['B']),
      node('C', 'root', ['branch1', 'branch2'])
    ]
    const result = layoutTaskDag(input, { rootId: 'root' })
    avoidsUnrelatedBoxes(result)
    expect(result.nodes.map(box => box.id)).toEqual(input.map(item => item.id))
    expect(result.edges).toHaveLength(6)
    expect(result.boundaryEdges.filter(edge => edge.boundary === 'START').map(edge => edge.taskId)).toEqual([
      'A',
      'parallel'
    ])
  })

  it('确定性密集分支图的所有真实和边界路径均避开无关卡片', () => {
    const input = [node('root')]
    for (let index = 0; index < 36; index++)
      input.push(
        node(
          `N${index}`,
          'root',
          index >= 6 ? [...new Set([`N${index - 6}`, ...(index % 3 === 0 ? [`N${index - 5}`] : [])])] : []
        )
      )
    const result = layoutTaskDag(input, { rootId: 'root' })
    avoidsUnrelatedBoxes(result)
    expect(result.nodes).toHaveLength(37)
  })
})
