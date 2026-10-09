import { describe, expect, it, vi } from 'vitest'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import { newTaskNode } from './task-center'
import type { TaskQuery } from '@/types/nocode/task-center'

describe('个人任务分组传输契约', () => {
  it('无详情权限的摘要保留空排期、个人计数和可见完成数，分页和展开均可解码', async () => {
    const node = {
      task: {
        ...newTaskNode(),
        id: 'root',
        rootId: 'root',
        groupStatus: 'RUNNING',
        schedule: null,
        canEdit: false,
        canExecute: false,
        completionReason: null
      },
      matchingChildCount: 3,
      completedChildCount: 1,
      contextOnly: true,
      detailVisible: false,
      myPendingCount: 2,
      myCompletedCount: 1,
      anchorTaskId: 'mine',
      structure: {
        id: 'root',
        rootId: 'root',
        parentId: null,
        title: '完整总任务',
        status: 'RUNNING',
        assigneeName: '同事',
        predecessorIds: ['before'],
        expectedStart: '2026-10-01',
        expectedEnd: '2026-10-08'
      }
    }
    const post = vi
      .fn()
      .mockResolvedValueOnce({ list: [node], total: 21 })
      .mockResolvedValueOnce([node])
    const api = createTaskCenterApi({ get: vi.fn(), put: vi.fn(), post } as NocodeHttpClient)
    const query: TaskQuery = {
      scope: 'MINE',
      tab: 'TODO',
      personalScope: 'ACTION',
      date: '2026-10-05',
      pageNo: 2,
      pageSize: 10
    }
    const result = await api.personalTreePage(query)
    expect(post).toHaveBeenCalledWith('/nocode/tasks/personal-tree-page', query)
    expect(result.total).toBe(21)
    expect(result.list[0]).toMatchObject({
      contextOnly: true,
      anchorTaskId: 'mine',
      title: '完整总任务',
      assigneeName: '同事',
      expectedStart: '2026-10-01',
      predecessorIds: ['before'],
      detailVisible: false,
      myPendingCount: 2,
      myCompletedCount: 1,
      matchingChildCount: 3,
      completedChildCount: 1,
      schedule: null,
      canEdit: false,
      canExecute: false,
      completionReason: null
    })
    expect(node.task.schedule).toBeNull()
    expect((await api.personalTreeChildren(query, 'root'))[0]).toEqual(result.list[0])
    expect(post).toHaveBeenLastCalledWith('/nocode/tasks/personal-tree-children', { query, parentId: 'root' })
  })
})
