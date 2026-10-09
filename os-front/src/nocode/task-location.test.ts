import { describe, expect, it } from 'vitest'
import { moveTaskNode, taskLocation, type TaskArrangementNode } from './task-arrangement'

function fixture(): TaskArrangementNode[] {
  return [
    { id: 'root', parentId: null, title: '办公室装修', predecessorIds: [] },
    { id: 'a', parentId: 'root', title: '现场勘察', predecessorIds: [] },
    { id: 'b', parentId: 'root', title: '装修施工', predecessorIds: ['a'] },
    { id: 'b1', parentId: 'b', title: '水电施工', predecessorIds: [] },
    { id: 'b11', parentId: 'b1', title: '穿线', predecessorIds: [] },
    { id: 'c', parentId: 'root', title: '验收', predecessorIds: ['b'] }
  ]
}

describe('任务所属位置与显式移动', () => {
  it('显示完整真实路径；缺失节点不补造标题，坏循环也能结束', () => {
    expect(taskLocation(fixture(), 'b1')).toBe('办公室装修 / 装修施工 / 水电施工')
    expect(taskLocation(fixture(), 'missing')).toBe('未显示的上级任务')
    expect(taskLocation(fixture(), null)).toBe('')
    expect(taskLocation([{ id: 'a', title: '', parentId: 'a', predecessorIds: [] }], 'a')).toBe('未命名任务')
  })

  it('移动整支只改所选节点的parent，保留身份、下级、顺序和其他数据，不改原数组', () => {
    const nodes = fixture().map(node => ({ ...node, marker: `保留-${node.id}` }))
    const before = JSON.stringify(nodes)
    const result = moveTaskNode(nodes, 'b1', 'a', { rootId: 'root' })
    expect(result.error).toBeNull()
    expect(result.nodes).toEqual(nodes.map(node => (node.id === 'b1' ? { ...node, parentId: 'a' } : node)))
    expect(JSON.stringify(nodes)).toBe(before)
    expect(result.nodes?.find(node => node.id === 'b11')?.parentId).toBe('b1')
    expect(taskLocation(result.nodes || [], 'b11')).toBe('办公室装修 / 现场勘察 / 水电施工 / 穿线')
  })

  it('移回总任务下不生成第二根，也不自动生成执行依赖', () => {
    const result = moveTaskNode(fixture(), 'b1', 'root', { rootId: 'root' })
    expect(result.error).toBeNull()
    expect(result.nodes?.filter(node => !node.parentId).map(node => node.id)).toEqual(['root'])
    expect(result.nodes?.find(node => node.id === 'b1')).toMatchObject({ parentId: 'root', predecessorIds: [] })
  })

  it.each(['b', 'b1', 'b11'])('禁止把父项移到自身或下级%s', parentId => {
    expect(moveTaskNode(fixture(), 'b', parentId, { rootId: 'root' })).toMatchObject({ nodes: null })
  })

  it('禁止移动总任务、缺失节点及不完整总任务视图', () => {
    expect(moveTaskNode(fixture(), 'root', 'b', { rootId: 'root' }).nodes).toBeNull()
    expect(moveTaskNode(fixture(), 'b', 'missing', { rootId: 'root' }).nodes).toBeNull()
    expect(moveTaskNode(fixture(), 'missing', 'b', { rootId: 'root' }).nodes).toBeNull()
    expect(moveTaskNode(fixture(), 'b', 'a').nodes).toBeNull()
  })

  it('包含关系与原有前置会形成等待死锁时整次拒绝，不自动清除前置', () => {
    const nodes = fixture(),
      before = JSON.stringify(nodes)
    // B 等 A，若 A 成了 B 的子项，B 的完成和 A 的开始将相互等待。
    expect(moveTaskNode(nodes, 'a', 'b', { rootId: 'root' })).toEqual({
      nodes: null,
      error: '任务依赖存在循环或父子等待死锁，请调整前置关系'
    })
    expect(JSON.stringify(nodes)).toBe(before)
  })

  it.each(['b', 'b1', 'b11'])('当前或后代%s冻结时拒绝移动整支', frozenId => {
    expect(moveTaskNode(fixture(), 'b', 'a', { rootId: 'root', frozenIds: [frozenId] }).nodes).toBeNull()
  })

  it('只读、已结束目标、总任务或当前子树均不能移动', () => {
    expect(moveTaskNode(fixture(), 'b1', 'a', { rootId: 'root', readonly: true }).nodes).toBeNull()
    for (const id of ['a', 'root', 'b1', 'b11']) {
      expect(moveTaskNode(fixture(), 'b1', 'a', { rootId: 'root', closedIds: [id] }).nodes).toBeNull()
    }
  })

  it('即使候选混入其他总任务也不能跨根移动', () => {
    const nodes = [...fixture(), { id: 'other', parentId: null, title: '其他任务', predecessorIds: [] }]
    expect(moveTaskNode(nodes, 'b1', 'other', { rootId: 'root' }).nodes).toBeNull()
    expect(moveTaskNode(nodes, 'other', 'a', { rootId: 'root' }).nodes).toBeNull()
  })
})
