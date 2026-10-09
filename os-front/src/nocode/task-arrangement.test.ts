import { describe, expect, it } from 'vitest'
import {
  arrangeTaskSibling,
  orderTaskSiblings,
  taskDependencyIssue,
  type TaskArrangementNode
} from './task-arrangement'
import { taskStructureError } from './task-center'

const node = (id: string, parentId: string | null = 'root', predecessorIds: string[] = []): TaskArrangementNode => ({
  id,
  title: id,
  parentId,
  predecessorIds
})

describe('用操作生成流程，不额外勾选先后顺序', () => {
  it('添加下一步插入所有直接后继之前，保留汇合的其他前置与递归包含关系', () => {
    const nodes = [...fixture(), node('p'), node('d', 'root', ['a', 'p'])]
    const before = JSON.stringify(nodes)
    const result = arrangeTaskSibling(nodes, 'a', node('x'), 'NEXT', { rootId: 'root' })
    expect(result.error).toBeNull()
    expect(result.nodes?.find(item => item.id === 'x')).toMatchObject({ parentId: 'root', predecessorIds: ['a'] })
    expect(result.nodes?.find(item => item.id === 'b')?.predecessorIds).toEqual(['x'])
    expect(result.nodes?.find(item => item.id === 'd')?.predecessorIds).toEqual(['x', 'p'])
    expect(result.nodes?.find(item => item.id === 'a1')).toBe(nodes.find(item => item.id === 'a1'))
    expect(JSON.stringify(nodes)).toBe(before)
  })
  it('在线上插入只替换所选真实边，保留其他分支', () => {
    const nodes = [...fixture(), node('d', 'root', ['a'])]
    const result = arrangeTaskSibling(nodes, 'a', node('x'), 'NEXT', { rootId: 'root', successorId: 'b' })
    expect(result.nodes?.find(item => item.id === 'b')?.predecessorIds).toEqual(['x'])
    expect(result.nodes?.find(item => item.id === 'd')?.predecessorIds).toEqual(['a'])
  })
  it('第一步添加并行工作从同一起点分出，并在原下一步汇合', () => {
    const result = arrangeTaskSibling(fixture(), 'a', node('p'), 'PARALLEL', { rootId: 'root' })
    expect(result.error).toBeNull()
    expect(result.nodes?.find(item => item.id === 'p')?.predecessorIds).toEqual([])
    expect(result.nodes?.find(item => item.id === 'b')?.predecessorIds).toEqual(['a', 'p'])
    expect(result.nodes?.find(item => item.id === 'c')?.predecessorIds).toEqual(['b'])
  })
  it('中间步骤的并行工作共享前置，后续等待两项', () => {
    const result = arrangeTaskSibling(fixture(), 'b', node('p'), 'PARALLEL', { rootId: 'root' })
    expect(result.nodes?.find(item => item.id === 'p')?.predecessorIds).toEqual(['a'])
    expect(result.nodes?.find(item => item.id === 'c')?.predecessorIds).toEqual(['b', 'p'])
  })
  it('末尾并行不凭空新建结束任务或后继', () => {
    const result = arrangeTaskSibling(fixture(), 'c', node('p'), 'PARALLEL', { rootId: 'root' })
    expect(result.nodes).toHaveLength(fixture().length + 1)
    expect(result.nodes?.find(item => item.id === 'p')?.predecessorIds).toEqual(['b'])
  })
  it.each(['NEXT', 'PARALLEL'] as const)('%s 操作遇到任一冻结后继则全拒绝，不部分改图', mode => {
    const nodes = fixture()
    const before = JSON.stringify(nodes)
    expect(arrangeTaskSibling(nodes, 'a', node('x'), mode, { frozenIds: ['b'] })).toMatchObject({ nodes: null })
    expect(JSON.stringify(nodes)).toBe(before)
    expect(arrangeTaskSibling(nodes, 'a', node('x'), mode, { closedIds: ['b'] }).error).toContain('已结束')
  })
  it('不为总任务、已结束父级、缺失/陈旧边创建半成品', () => {
    expect(arrangeTaskSibling(fixture(), 'root', node('x'), 'NEXT', { rootId: 'root' }).error).toContain('总任务')
    expect(arrangeTaskSibling(fixture(), 'a', node('x'), 'NEXT', { closedIds: ['root'] }).error).toContain('上级')
    expect(arrangeTaskSibling(fixture(), 'missing', node('x'), 'NEXT').error).toContain('不存在')
    expect(arrangeTaskSibling(fixture(), 'a', node('x'), 'NEXT', { successorId: 'c' }).error).toContain('连线')
  })
  it('新节点重复标识或已有非法结构不会落入模型', () => {
    expect(arrangeTaskSibling(fixture(), 'a', node('b'), 'NEXT').nodes).toBeNull()
    const nodes = fixture().map(item => (item.id === 'a' ? { ...item, predecessorIds: ['c'] } : item))
    expect(arrangeTaskSibling(nodes, 'b', node('x'), 'NEXT').error).toContain('循环')
  })
})
function fixture() {
  return [
    node('root', null),
    node('a'),
    node('b', 'root', ['a']),
    node('c', 'root', ['b']),
    node('a1', 'a'),
    node('b1', 'b'),
    node('a11', 'a1')
  ]
}

describe('统一任务编排结构', () => {
  it('总任务独立命名，步骤同级且可递归拆分，不要求先填人员或时间', () => {
    expect(taskStructureError(fixture())).toBeNull()
    expect(taskStructureError(fixture().map(item => ({ ...item, title: '' })))).toBeNull()
  })
  it('拒绝自循环、反向循环以及父子等待死锁', () => {
    expect(taskDependencyIssue(fixture(), 'a', 'a')).toContain('自己')
    expect(taskDependencyIssue(fixture(), 'c', 'a')).toContain('循环')
    expect(taskDependencyIssue(fixture(), 'a', 'a1')).toContain('死锁')
    expect(taskDependencyIssue(fixture(), 'b1', 'a11')).toContain('死锁')
  })
  it('合法跨层前置保持精确节点，不提升成整个上级任务', () => {
    const nodes = fixture().map(item => (item.id === 'b' ? { ...item, predecessorIds: [] } : item))
    expect(taskDependencyIssue(nodes, 'a1', 'b1')).toBeNull()
    expect(nodes.find(item => item.id === 'b')?.predecessorIds).toEqual([])
  })
  it('同级按依赖整理，保留层级及原对象，不凭排列添加依赖', () => {
    const nodes = fixture(),
      unordered = [nodes[0]!, nodes[3]!, nodes[2]!, nodes[5]!, nodes[1]!, nodes[4]!, nodes[6]!]
    const snapshot = JSON.stringify(unordered)
    const ordered = orderTaskSiblings(unordered)
    expect(ordered.map(item => item.id)).toEqual(['root', 'a', 'a1', 'a11', 'b', 'b1', 'c'])
    expect(JSON.stringify(unordered)).toBe(snapshot)
    expect(ordered[1]).toBe(nodes[1])
  })
  it('历史缺少上级或循环的节点仍保留，不因布局丢失', () => {
    const nodes = [node('a', 'missing'), node('b', 'c'), node('c', 'b')]
    expect(orderTaskSiblings(nodes)).toHaveLength(3)
    expect(taskDependencyIssue(nodes, 'missing', 'a')).toContain('不存在')
  })
})
