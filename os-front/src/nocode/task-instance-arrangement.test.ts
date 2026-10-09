import { describe, expect, it } from 'vitest'
import type { TaskDetail, TaskRow, TaskStructureNode } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'
import {
  arrangementDisplayNodes,
  canSplitInstanceNode,
  instanceArrangementAccess,
  instanceNodeRestriction,
  instancePredecessors,
  instanceStructure
} from './task-instance-arrangement'

const row = (id: string, status: TaskRow['status'] = 'PENDING') =>
  ({
    ...newTaskNode(),
    id,
    rootId: 'root',
    status,
    assigneeId: '42',
    canEdit: true
  }) as TaskRow

describe('实例编排按身份和节点状态控制，不按入口授予权限', () => {
  const context = (id: string, parentId: string | null = 'root'): TaskStructureNode => ({
    id,
    rootId: 'root',
    parentId,
    title: id,
    status: 'PENDING',
    assigneeName: '待领取',
    predecessorIds: [],
    expectedStart: null,
    expectedEnd: null,
    detailVisible: false
  })
  it('整组摘要补齐前置和真实父级，但不污染详情节点与提交模型', () => {
    const own = { ...row('own'), parentId: 'root', title: '我的任务', predecessorIds: ['before'] }
    const value: TaskDetail = {
      task: own,
      nodes: [own],
      comments: [],
      events: [],
      structure: [context('root', null), context('before'), context('own')]
    }
    const displayed = arrangementDisplayNodes([own], instanceStructure(value))
    expect(displayed.map(node => node.id)).toEqual(['root', 'before', 'own'])
    expect(displayed.find(node => node.id === 'own')).toBe(own)
    expect(displayed.find(node => node.id === 'before')).toMatchObject({ description: '', binding: null })
    expect(value.nodes).toEqual([own])
    expect(instancePredecessors(value).map(node => node.id)).toEqual(['before'])
    expect(
      canSplitInstanceNode(
        value.nodes.find(node => node.id === 'before'),
        undefined,
        '42'
      )
    ).toBe(false)
  })
  it('等待上级的前置也能定位，重复和循环路径不会无限遍历', () => {
    const own = { ...row('own'), parentId: 'parent', predecessorIds: ['before'] }
    const value: TaskDetail = {
      task: own,
      nodes: [own],
      comments: [],
      events: [],
      structure: [
        context('before'),
        context('other'),
        { ...context('parent', 'own'), predecessorIds: ['before', 'other'] }
      ]
    }
    expect(instancePredecessors(value).map(node => node.id)).toEqual(['before', 'other'])
  })
  it('兼容无结构摘要的响应，不伪造隐藏前置或丢失新增草稿节点', () => {
    const own = { ...row('own'), predecessorIds: ['missing'] }
    const value: TaskDetail = { task: own, nodes: [own], comments: [], events: [] }
    expect(instancePredecessors(value)).toEqual([])
    expect(arrangementDisplayNodes([own], [])).toEqual([own])
    expect(instanceStructure(value)).toHaveLength(1)
  })
  it('管理者可调整未开始或运行中的整组，执行人仅有canEdit也不能调整整组', () => {
    for (const status of ['PENDING', 'RUNNING'] as const) {
      expect(instanceArrangementAccess([row('root', status)], 'root', true)).toBe(true)
      expect(instanceArrangementAccess([row('root', status)], 'root', false)).toBe(false)
    }
  })
  it.each(['PAUSED', 'PENDING_ACCEPTANCE', 'COMPLETED', 'CANCELLED'] as const)('整组%s时锁定调整及拆分', status => {
    const root = row('root', status)
    expect(instanceArrangementAccess([root], 'root', true)).toBe(false)
    expect(canSplitInstanceNode(row('child'), root, '42')).toBe(false)
  })
  it('只有本人负责且canEdit节点可拆，隐藏根不额外探测或扩大权限', () => {
    expect(canSplitInstanceNode(row('child'), undefined, 42)).toBe(true)
    expect(canSplitInstanceNode(row('child'), undefined, 'other')).toBe(false)
    expect(canSplitInstanceNode({ ...row('child'), canEdit: false }, undefined, '42')).toBe(false)
    expect(canSplitInstanceNode(row('child'), undefined, undefined)).toBe(false)
    expect(instanceArrangementAccess([row('child')], 'root', true)).toBe(false)
  })
  it.each(['PAUSED', 'PENDING_ACCEPTANCE', 'COMPLETED', 'CANCELLED'] as const)('本人节点%s也不能继续拆分', status => {
    expect(canSplitInstanceNode(row('child', status), row('root', 'RUNNING'), '42')).toBe(false)
  })
  it('被上级暂停的未开始节点只读，不能以未开始为由继续拆分', () => {
    const child = { ...row('child'), pausedByTaskId: 'root' }
    expect(canSplitInstanceNode(child, undefined, '42')).toBe(false)
    expect(instanceNodeRestriction(child, undefined, true)).toContain('已暂停')
  })
  it('锁定原因与执行状态一致，运行节点不能显示可改配置', () => {
    expect(instanceNodeRestriction(row('a', 'RUNNING'), row('root', 'RUNNING'), true)).toContain('原安排已锁定')
    expect(instanceNodeRestriction(row('b'), row('root', 'RUNNING'), true)).toContain('尚未开始，可调整')
    expect(instanceNodeRestriction(row('b'), row('root'), false)).toContain('原安排只读')
  })
})
