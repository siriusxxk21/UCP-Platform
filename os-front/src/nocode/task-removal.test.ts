import { describe, expect, it } from 'vitest'
import { newTaskNode } from './task-center'
import { planTaskRemoval } from './task-arrangement'

function fixture() {
  return [
    { ...newTaskNode(), id: 'root', title: '整件工作' },
    { ...newTaskNode('root'), id: 'a', title: '准备' },
    { ...newTaskNode('root'), id: 'b', title: '施工', predecessorIds: ['a'] },
    { ...newTaskNode('b'), id: 'b1', title: '水电' },
    { ...newTaskNode('root'), id: 'c', title: '验收', predecessorIds: ['b'] },
    { ...newTaskNode('root'), id: 'other', title: '独立工作' }
  ]
}

describe('画布与列表共用删除影响预览', () => {
  it('整支删除包含下级，移除入出连线但不偷偷桥接，不修改原模型', () => {
    const nodes = fixture(),
      before = JSON.stringify(nodes)
    const result = planTaskRemoval(nodes, 'b', { rootId: 'root' })
    expect(result.error).toBeNull()
    if (!result.nodes) throw new Error(result.error)
    expect(result.removedIds).toEqual(['b', 'b1'])
    expect(result.removedEdges).toBe(2)
    expect(result.nodes.map(node => node.id)).toEqual(['root', 'a', 'c', 'other'])
    expect(result.nodes.find(node => node.id === 'c')?.predecessorIds).toEqual([])
    expect(result.nodes.find(node => node.id === 'other')).toEqual(nodes.find(node => node.id === 'other'))
    expect(JSON.stringify(nodes)).toBe(before)
  })

  it('多前置只删对应引用，保留其他等待条件和其他属性', () => {
    const nodes = fixture()
    nodes[4]!.predecessorIds = ['b', 'other']
    nodes[4]!.schedule.mode = 'PREDECESSOR'
    const result = planTaskRemoval(nodes, 'b', { rootId: 'root' })
    expect(result.error).toBeNull()
    expect(result.nodes?.find(node => node.id === 'c')).toEqual({ ...nodes[4], predecessorIds: ['other'] })
  })

  it.each(['root', 'missing'])('不能删除总任务或不存在的%s', id => {
    expect(planTaskRemoval(fixture(), id, { rootId: 'root' }).nodes).toBeNull()
  })

  it.each(['b', 'b1', 'c'])('待删除子树或受影响后继%s已执行，整次拒绝', id => {
    const nodes = fixture(),
      before = JSON.stringify(nodes)
    expect(planTaskRemoval(nodes, 'b', { rootId: 'root', frozenIds: [id] }).nodes).toBeNull()
    expect(planTaskRemoval(nodes, 'b', { rootId: 'root', closedIds: [id] }).nodes).toBeNull()
    expect(JSON.stringify(nodes)).toBe(before)
  })

  it('只读或已结束总任务不允许删除任何内部工作', () => {
    expect(planTaskRemoval(fixture(), 'b', { rootId: 'root', readonly: true }).nodes).toBeNull()
    expect(planTaskRemoval(fixture(), 'b', { rootId: 'root', closedIds: ['root'] }).nodes).toBeNull()
  })

  it('相对前置时间失去最后依据时不静默清空时间安排', () => {
    const nodes = fixture()
    nodes[4]!.schedule.mode = 'PREDECESSOR'
    const result = planTaskRemoval(nodes, 'b', { rootId: 'root' })
    expect(result.nodes).toBeNull()
    expect(result.error).toContain('预计时间')
    expect(nodes[4]!.schedule.mode).toBe('PREDECESSOR')
  })

  it('外部节点的共享业务引用指向任一下级时，不自动清理业务关系', () => {
    const nodes = fixture()
    nodes[5]!.sharing = { mode: 'SHARED', sourceNodeId: 'b1', writableFieldIds: [] }
    expect(planTaskRemoval(nodes, 'b', { rootId: 'root' }).error).toContain('独立工作')
    expect(nodes[5]!.sharing.sourceNodeId).toBe('b1')
  })

  it('外部反馈引用也保护；同支内部共享引用不阻止整支删除', () => {
    const nodes = fixture()
    nodes[3]!.entries = [
      {
        key: 'entry',
        name: '反馈',
        binding: null,
        dataMode: 'SOURCE_SHARED',
        sourceNodeId: 'b',
        sourceEntryKey: 'source',
        readableFieldIds: null,
        writableFieldIds: null,
        required: false,
        allowAll: false
      }
    ]
    expect(planTaskRemoval(nodes, 'b', { rootId: 'root' }).error).toBeNull()
    nodes[5]!.entries = nodes[3]!.entries
    expect(planTaskRemoval(nodes, 'b', { rootId: 'root' }).error).toContain('反馈数据')
  })
})
