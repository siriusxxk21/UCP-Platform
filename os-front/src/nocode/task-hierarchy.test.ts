import { describe, expect, it } from 'vitest'
import { taskHierarchy, taskHierarchyLabel, type TaskHierarchyNode } from './task-hierarchy'

const nodes: TaskHierarchyNode[] = [
  { id: 'root', rootId: 'root', parentId: null, title: '项目施工' },
  { id: 'a', rootId: 'root', parentId: 'root', title: '现场验收' },
  { id: 'b', rootId: 'root', parentId: 'root', title: '现场验收' },
  { id: 'a1', rootId: 'root', parentId: 'a', title: '复查' }
]

describe('任务展示层级', () => {
  it('区分总任务、同级与多层子任务，同名节点仍以 ID 识别归属', () => {
    const tree = taskHierarchy(nodes)
    expect(tree.get('root')).toMatchObject({ label: '总任务', depth: 0, outline: '1', childCount: 2 })
    expect(tree.get('a')).toMatchObject({ label: '子任务', depth: 1, outline: '1.1', parentTitle: '项目施工' })
    expect(tree.get('b')).toMatchObject({ depth: 1, outline: '1.2' })
    expect(tree.get('a1')).toMatchObject({ depth: 2, outline: '1.1.1', parentTitle: '现场验收' })
    expect(taskHierarchyLabel(nodes[3]!, tree.get('a1'))).toBe('子任务 1.1.1 · 复查（上级：现场验收）')
  })

  it('模板分工首层属于发起后的总任务，调整实例则能识别实际总任务', () => {
    const draft = [
      { id: 'part', parentId: null, title: '准备' },
      { id: 'sub', parentId: 'part', title: '材料' }
    ]
    expect(taskHierarchy(draft, { draft: true }).get('part')).toMatchObject({
      label: '一级子任务',
      outline: '1',
      parentTitle: '发起时的总任务'
    })
    expect(taskHierarchy(nodes, { draft: true, rootId: 'root' }).get('root')).toMatchObject({
      label: '总任务',
      parentTitle: ''
    })
  })

  it('筛选或授权范围只返回子树时，不伪装为总任务或编造完整层级编号', () => {
    const filtered = taskHierarchy(nodes.filter(n => n.id === 'a' || n.id === 'a1'))
    expect(filtered.get('a')).toMatchObject({
      label: '子任务',
      depth: 0,
      outline: '',
      missingParent: true,
      parentTitle: '上级任务未在当前视图中'
    })
    expect(filtered.get('a1')).toMatchObject({
      label: '子任务',
      depth: 1,
      outline: '',
      missingParent: true,
      parentTitle: '现场验收'
    })
  })

  it('调整父节点或重命名后重新计算展示，不改变原节点或将依赖关系当作层级', () => {
    const moved = nodes.map(n =>
      n.id === 'a1' ? { ...n, parentId: 'b' } : n.id === 'b' ? { ...n, title: '竣工验收' } : n
    )
    expect(taskHierarchy(moved).get('a1')).toMatchObject({ outline: '1.2.1', parentTitle: '竣工验收' })
    expect(nodes[3]!.parentId).toBe('a')
  })

  it('异常循环不会卡住，也不展示伪造编号', () => {
    const broken = taskHierarchy([
      { id: 'a', parentId: 'b', title: '甲' },
      { id: 'b', parentId: 'a', title: '乙' }
    ])
    expect([...broken.values()].every(n => n.missingParent && !n.outline && n.label === '子任务')).toBe(true)
  })
})
