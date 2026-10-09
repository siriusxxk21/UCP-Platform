import { describe, expect, it, vi } from 'vitest'
import dayjs from 'dayjs'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import type { TaskCreate, TaskNodeInput, TaskQuery, TaskRow } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'
import { taskCreateToWire, taskDraftFromWire, taskNodeFromWire, taskNodeToWire } from './task-center-wire'

describe('任务日期传输边界', () => {
  it('发起与调整预览共用日期边界，保留部分排期及冲突提示', async () => {
    const start = dayjs('2026-10-12T00:00:00').valueOf()
    const schedule = {
      nodes: [
        { id: 'root', title: '装修', expectedStart: start, expectedEnd: null, partial: true, warnings: ['下级未排期'] }
      ],
      warnings: ['下级未排期']
    }
    const post = vi
      .fn()
      .mockResolvedValueOnce(schedule)
      .mockResolvedValueOnce({ changedIds: [], addedIds: [], removedIds: [], affectedIds: [], schedule })
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    const node = { ...newTaskNode(), id: 'root' }
    node.schedule.mode = 'AUTO'
    const result = await api.schedulePreview({ nodes: [node], plannedStart: '2026-10-12' })
    expect(post).toHaveBeenNthCalledWith(
      1,
      '/nocode/tasks/schedule-preview',
      expect.objectContaining({ plannedStart: start, nodes: [taskNodeToWire(node)] })
    )
    expect(result.nodes[0]).toMatchObject({
      expectedStart: '2026-10-12T00:00:00',
      expectedEnd: null,
      partial: true,
      warnings: ['下级未排期']
    })
    const adjustment = await api.adjustPreview({
      rootId: 'root',
      expectedRevision: 1,
      reason: '调整',
      nodes: [node],
      plannedStart: '2026-10-12'
    })
    expect(adjustment.schedule).toEqual(result)
  })
  it('领取前详情解码工作要求日期但保留安全摘要和只读预览标记', async () => {
    const summary = { id: 'open', rootId: 'open', schedule: null }
    const wireDate = dayjs('2030-03-04T08:30:00').valueOf()
    const post = vi.fn().mockResolvedValue({
      task: summary,
      nodes: [summary],
      comments: [],
      events: [],
      preview: {
        description: '工作要求',
        priority: 'HIGH',
        schedule: { mode: 'FIXED', fixedStart: wireDate, fixedEnd: wireDate, offsetDays: 0, durationDays: 0 }
      }
    })
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    const detail = await api.detail('open')
    expect(detail.task.schedule).toBeNull()
    expect(detail.nodes[0]?.schedule).toBeNull()
    expect(detail.preview?.schedule).toMatchObject({
      fixedStart: '2030-03-04T08:30:00',
      fixedEnd: '2030-03-04T08:30:00'
    })
    expect(detail.preview?.description).toBe('工作要求')
  })
  it('原操作回执接口保留完整命令，成功详情仍经过统一日期解码', async () => {
    const command = {
      id: 'root',
      expectedRevision: 7,
      action: 'COMPLETE' as const,
      note: '原备注',
      requestKey: 'old-key'
    }
    const task = { ...newTaskNode(), id: 'root', rootId: 'root', plannedStart: 1893456000000 } as unknown as TaskRow
    const post = vi
      .fn()
      .mockResolvedValueOnce({ applied: { task, nodes: [task] }, superseded: false })
      .mockResolvedValueOnce({ applied: null, superseded: true })
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    const recovered = await api.transitionRecovery(command)
    expect(post).toHaveBeenNthCalledWith(1, '/nocode/tasks/transition-recovery', command)
    expect(recovered.applied?.task.plannedStart).toBe(dayjs(1893456000000).format('YYYY-MM-DDTHH:mm:ss'))
    expect(await api.transitionRecovery(command)).toEqual({ applied: null, superseded: true })
  })
  it('归组领取与预览使用独立安全契约，承接提交保留实例版本和请求键', async () => {
    const root = { ...newTaskNode(), id: 'root', rootId: 'root' } as TaskRow
    const post = vi
      .fn()
      .mockResolvedValueOnce({ list: [], total: 9 })
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce({ rootId: 'root', title: '现场施工', instanceRevision: 8, items: [] })
      .mockResolvedValueOnce({ task: root, nodes: [root] })
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    await api.claimableGroups({ pageNo: 2, pageSize: 10, search: '施工' })
    await api.claimableChildren('root')
    await api.claimPreview('root')
    await api.claimGroup({ rootId: 'root', expectedInstanceRevision: 8, requestKey: 'same-key' })
    expect(post).toHaveBeenNthCalledWith(1, '/nocode/tasks/claimable-groups', {
      pageNo: 2,
      pageSize: 10,
      search: '施工'
    })
    expect(post).toHaveBeenNthCalledWith(2, '/nocode/tasks/claimable-children', { rootId: 'root' })
    expect(post).toHaveBeenNthCalledWith(3, '/nocode/tasks/claim-preview', { rootId: 'root' })
    expect(post).toHaveBeenNthCalledWith(4, '/nocode/tasks/claim-group', {
      rootId: 'root',
      expectedInstanceRevision: 8,
      requestKey: 'same-key'
    })
  })
  it('整项领取显式传入 includeOpen，预览与提交使用相同模式且兼容旧调用', async () => {
    const root = { ...newTaskNode(), id: 'root', rootId: 'root' } as TaskRow
    const post = vi
      .fn()
      .mockResolvedValueOnce({ rootId: 'root', title: '现场施工', instanceRevision: 8, items: [] })
      .mockResolvedValueOnce({ task: root, nodes: [root] })
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    await api.claimPreview('root', true)
    await api.claimGroup({ rootId: 'root', expectedInstanceRevision: 8, requestKey: 'whole-key', includeOpen: true })
    expect(post).toHaveBeenNthCalledWith(1, '/nocode/tasks/claim-preview', { rootId: 'root', includeOpen: true })
    expect(post).toHaveBeenNthCalledWith(2, '/nocode/tasks/claim-group', {
      rootId: 'root',
      expectedInstanceRevision: 8,
      requestKey: 'whole-key',
      includeOpen: true
    })
  })
  it('个人树页与下级读取保留归并数量和真实层级，并统一解码任务日期', async () => {
    const task = {
      ...newTaskNode(),
      id: 'my-child',
      parentId: 'other-parent',
      rootId: 'root',
      plannedStart: dayjs('2030-03-04T08:30:00').valueOf()
    } as unknown as TaskRow
    const node = { task, matchingChildCount: 2 }
    const post = vi
      .fn()
      .mockResolvedValueOnce({ list: [node], total: 17 })
      .mockResolvedValueOnce([node])
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    const query: TaskQuery = { scope: 'MINE', tab: 'POOL', date: '2030-03-04', pageNo: 2, pageSize: 10 }
    const page = await api.personalTreePage(query)
    expect(page.total).toBe(17)
    expect(page.list[0]).toMatchObject({
      id: 'my-child',
      parentId: 'other-parent',
      rootId: 'root',
      matchingChildCount: 2,
      plannedStart: '2030-03-04T08:30:00'
    })
    expect(await api.personalTreeChildren(query, 'root')).toEqual(page.list)
    expect(post).toHaveBeenNthCalledWith(1, '/nocode/tasks/personal-tree-page', query)
    expect(post).toHaveBeenNthCalledWith(2, '/nocode/tasks/personal-tree-children', { query, parentId: 'root' })
  })
  it('完成与取消预检使用只读契约，不提交状态变化命令', async () => {
    const response = {
      taskId: 'task',
      revision: 1,
      checks: [],
      canComplete: true,
      canCancel: true,
      cancelBlockedReason: null,
      cancellationImpacts: []
    }
    const post = vi.fn().mockResolvedValue(response)
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    expect(await api.readiness('task')).toEqual(response)
    expect(post).toHaveBeenCalledExactlyOnceWith('/nocode/tasks/readiness', { id: 'task' })
  })
  const start = '2030-03-04T08:30:00',
    end = '2030-03-05T17:00:00'
  const content = (): TaskCreate => ({
    task: {
      ...newTaskNode(),
      title: '总任务',
      schedule: { mode: 'FIXED', fixedStart: start, fixedEnd: end, offsetDays: 0, durationDays: 0 }
    },
    nodes: [
      {
        ...newTaskNode(),
        title: '子任务',
        schedule: { mode: 'FIXED', fixedStart: start, fixedEnd: end, offsetDays: 0, durationDays: 0 }
      }
    ],
    plannedStart: start,
    requestKey: 'stable'
  })
  it('根/子/计划日期发毫秒时间戳，恢复为日期控件可读字符串且不改业务值', () => {
    const original = content()
    original.business = {
      applicationId: 'a',
      objectId: 'o',
      id: null,
      expectedRevision: null,
      values: { fixedStart: '业务原文' }
    }
    const wire = taskCreateToWire(original)
    expect(wire.plannedStart).toBe(dayjs(start).valueOf())
    expect(wire.task.schedule.fixedStart).toBe(dayjs(start).valueOf())
    expect(wire.nodes?.[0]?.schedule.fixedEnd).toBe(dayjs(end).valueOf())
    expect(wire.business?.values.fixedStart).toBe('业务原文')
    const draft = taskDraftFromWire({
      id: 'draft',
      revision: 2,
      title: '总任务',
      updatedAt: '',
      content: wire
    } as never)
    expect(draft.content).toEqual(original)
    expect(original.task.schedule.fixedStart).toBe(start)
  })
  it('创建/草稿/调整/模板 API 均经过同一局部编码，不依赖全局拦截器', async () => {
    const body = content(),
      wire = taskCreateToWire(body)
    const node = { ...body.task, rootId: body.task.id, plannedStart: dayjs(start).valueOf() }
    const post = vi.fn(async (path: string) =>
      path.endsWith('draft-save')
        ? { id: 'draft', revision: 1, content: wire }
        : path.includes('task-templates')
          ? { nodes: [node] }
          : path.endsWith('adjust-preview')
            ? {}
            : { task: node, nodes: [node] }
    )
    const api = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    await api.create(body)
    await api.draftSave({ id: 'draft', expectedRevision: 0, content: body })
    await api.adjust({
      rootId: 'root',
      expectedRevision: 1,
      nodes: [body.task],
      reason: '安排时间',
      plannedStart: start
    })
    await api.adjustPreview({
      rootId: 'root',
      expectedRevision: 1,
      nodes: [body.task],
      reason: '安排时间',
      plannedStart: start
    })
    await api.saveTemplate({
      id: null,
      expectedRevision: null,
      name: '模板',
      description: '',
      task: body.task,
      nodes: [body.task]
    })
    expect(post).toHaveBeenCalledWith('/nocode/tasks/create', wire)
    expect(post).toHaveBeenCalledWith('/nocode/tasks/draft-save', { id: 'draft', expectedRevision: 0, content: wire })
    for (const path of ['/nocode/tasks/adjust', '/nocode/tasks/adjust-preview'])
      expect(post).toHaveBeenCalledWith(
        path,
        expect.objectContaining({ plannedStart: dayjs(start).valueOf(), nodes: [wire.task] })
      )
    expect(post).toHaveBeenCalledWith(
      '/nocode/task-templates/save',
      expect.objectContaining({ task: wire.task, nodes: [wire.task] })
    )
  })
  it('新选预计开始按当天零点传输，预计结束覆盖当天末且恢复后再保存不丢毫秒', () => {
    const body = content()
    body.task.schedule.fixedStart = '2030-03-04'
    body.task.schedule.fixedEnd = '2030-03-04'
    body.nodes = [{ ...body.task, id: 'child', parentId: body.task.id }]
    const wire = taskCreateToWire(body)
    const startOfDay = dayjs('2030-03-04').startOf('day').valueOf()
    const endOfDay = dayjs('2030-03-04').endOf('day').valueOf()
    expect(wire.task.schedule).toMatchObject({ fixedStart: startOfDay, fixedEnd: endOfDay })
    expect(wire.nodes?.[0]?.schedule).toMatchObject({ fixedStart: startOfDay, fixedEnd: endOfDay })
    expect(body.task.schedule.fixedEnd).toBe('2030-03-04')
    const restored = taskNodeFromWire(wire.task as unknown as TaskNodeInput)
    expect(restored.schedule.fixedEnd).toBe('2030-03-04T23:59:59.999')
    expect(taskNodeToWire({ ...restored, title: '仅修改名称' }).schedule.fixedEnd).toBe(endOfDay)
  })
  it('旧精确时间与新日期可以混用，不把已有时分秒或毫秒改成当天末', () => {
    const node = content().task
    node.schedule.fixedEnd = '2030-03-05T17:20:40.123'
    const unchanged = taskNodeToWire(node)
    expect(unchanged.schedule.fixedStart).toBe(dayjs(start).valueOf())
    expect(unchanged.schedule.fixedEnd).toBe(dayjs('2030-03-05T17:20:40.123').valueOf())
    expect(taskNodeFromWire(unchanged as unknown as TaskNodeInput).schedule).toEqual(node.schedule)
    node.schedule.fixedEnd = '2030-03-04'
    expect(taskNodeToWire(node).schedule).toMatchObject({
      fixedStart: dayjs(start).valueOf(),
      fixedEnd: dayjs('2030-03-04').endOf('day').valueOf()
    })
  })
})
