import { describe, expect, it } from 'vitest'
import { newTaskNode, taskNodeInput } from './task-center'
import type { TaskRow } from '@/types/nocode/task-center'

describe('新增节点的总任务负责人预填', () => {
  it('总任务已定人时仍一次预填快照，不把其他总任务配置带给子任务', () => {
    const root = { ...newTaskNode(), assigneeId: '9007199254740993', candidateUserIds: ['candidate'], acceptorId: '2' }
    const child = newTaskNode('direct-parent', root)
    expect(child).toMatchObject({
      parentId: 'direct-parent',
      assigneeId: '9007199254740993',
      assignmentMode: 'ASSIGNED',
      candidateUserIds: [],
      acceptorId: null,
      predecessorIds: []
    })
    root.assigneeId = 'next-owner'
    expect(child.assigneeId).toBe('9007199254740993')
    child.assigneeId = 'manual-owner'
    child.assignmentMode = 'ASSIGNED'
    expect(root.assigneeId).toBe('next-owner')
    expect(newTaskNode('direct-parent', root).assigneeId).toBe('next-owner')
    expect(child.assigneeId).toBe('manual-owner')
  })

  it.each([undefined, null, { assigneeId: null }])('缺少总负责人仍明确跟随，不推断当前用户：%j', root => {
    expect(newTaskNode('parent', root)).toMatchObject({
      assigneeId: null,
      assignmentMode: 'FOLLOW_ROOT',
      candidateUserIds: []
    })
  })

  it('还原既有节点仍保留其手工负责人和分配方式', () => {
    const row = {
      ...newTaskNode('root'),
      rootId: 'root',
      assigneeId: 'manual-owner',
      assignmentMode: 'ASSIGNED'
    } as TaskRow
    expect(taskNodeInput(row)).toMatchObject({ assigneeId: 'manual-owner', assignmentMode: 'ASSIGNED' })
    expect(newTaskNode('root', { assigneeId: 'root-owner' }).assigneeId).toBe('root-owner')
    expect(row.assigneeId).toBe('manual-owner')
  })
})
