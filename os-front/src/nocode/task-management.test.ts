import { describe, expect, it, vi } from 'vitest'
import dayjs from 'dayjs'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import type { TaskManagementQuery } from '@/types/nocode/task-management'

describe('任务管理工作台传输契约', () => {
  it('员工汇总独立分页并原样保留字符串人员 ID，不混入底座 pageNum', async () => {
    const id = '9007199254740993123'
    const row = {
      userId: id,
      userName: '张三',
      pendingCount: 2,
      runningCount: 1,
      overdueCount: 0,
      todayCount: 1,
      weekCount: 3,
      coordinationCount: 1
    }
    const post = vi.fn().mockResolvedValue({ list: [row], total: 21 })
    const api = createTaskCenterApi({ post } as unknown as NocodeHttpClient)
    const query = { search: '张', date: '2026-10-07', pageNo: 2, pageSize: 10 }
    const result = await api.managementEmployees(query)
    expect(post).toHaveBeenCalledWith('/nocode/tasks/management/employees', query)
    expect(result.list[0].userId).toBe(id)
    expect(result.total).toBe(21)
  })
  it('管理下钻日期位于 query，响应继续经过公共任务日期适配且不扩大参数', async () => {
    const stamp = dayjs('2026-10-07T09:30:00').valueOf()
    const post = vi.fn().mockResolvedValue({ list: [{ id: 'task', schedule: null, plannedStart: stamp }], total: 1 })
    const api = createTaskCenterApi({ post } as unknown as NocodeHttpClient)
    const request: TaskManagementQuery = {
      query: { scope: 'MANAGE', tab: 'ALL', date: '2026-10-07', pageNo: 1, pageSize: 10 },
      focus: 'ACTIVE',
      employeeId: '9007199254740993123',
      employeeMetric: 'TODAY',
      groupByRoot: true
    }
    const result = await api.managementPage(request)
    expect(post).toHaveBeenCalledWith('/nocode/tasks/management/page', request)
    expect(result.list[0].plannedStart).toBe('2026-10-07T09:30:00')
    expect(result.list[0].schedule).toBeNull()
  })
  it.each(['RELATED', 'ALL'] as const)('员工 %s 指标保留独立语义，分组标志显式传递且兼容原扁平调用', async metric => {
    const post = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const api = createTaskCenterApi({ post } as unknown as NocodeHttpClient)
    const request: TaskManagementQuery = {
      query: { scope: 'MANAGE', tab: 'ALL', date: '2026-10-07', pageNo: 1, pageSize: 10 },
      focus: 'ALL',
      employeeId: '9007199254740993123',
      employeeMetric: metric
    }
    await api.managementPage({ ...request, groupByRoot: true })
    expect(post).toHaveBeenLastCalledWith('/nocode/tasks/management/page', { ...request, groupByRoot: true })
    await api.managementPage(request)
    expect(post).toHaveBeenLastCalledWith('/nocode/tasks/management/page', request)
    expect(post.mock.calls.at(-1)?.[1]).not.toHaveProperty('groupByRoot')
  })
})
