import { describe, expect, it, vi } from 'vitest'
import {
  checklistCommand,
  createChecklistAttempt,
  taskPlanHistoryLabel,
  taskPlanSummaryLabel,
  taskPlanSourceLabel
} from './task-checklist'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import type { TaskChecklistContext, TaskChecklistItem, TaskPlan } from '@/types/nocode/task-center'

const plan = (id: string, canCancel = true): TaskPlan => ({
  id,
  mode: 'CHECKLIST',
  period: 'WEEK',
  date: '2030-09-30',
  active: true,
  canCancel
})
const item = (id: string): TaskChecklistItem => ({
  taskId: id,
  title: id,
  status: 'PENDING',
  assigneeId: '9007199254740993',
  version: 7,
  todayPlans: [],
  weekPlans: [],
  history: [],
  canAdd: true,
  reason: null,
  warnings: []
})
const context = (): TaskChecklistContext => ({
  today: '2030-10-04',
  weekStart: '2030-09-30',
  weekEnd: '2030-10-06',
  nextWeekStart: '2030-10-07',
  nextWeekEnd: '2030-10-13',
  items: [item('child'), item('parent')]
})
function taskItem(ctx: TaskChecklistContext, id: string) {
  const found = ctx.items.find(value => value.taskId === id)
  if (!found) throw new Error(`测试上下文缺少 ${id}`)
  return found
}
describe('清单命令与回执边界', () => {
  it('继承来源可读，不泄露不可见名称；即使权限字段错误也不移除上级计划', () => {
    const inherited = { ...plan('parent-plan'), inheritedFromTaskId: 'parent', inheritedFromTitle: '办公室装修' }
    expect(taskPlanSourceLabel(inherited)).toBe('随上级「办公室装修」')
    expect(taskPlanSourceLabel({ ...inherited, inheritedFromTitle: null })).toBe('随上级计划')
    const ctx = context()
    taskItem(ctx, 'child').weekPlans = [inherited]
    expect(() => checklistCommand(ctx, ['child'], 'SELF', 'WEEK', 'REMOVE', ['parent-plan'])).toThrow('不可移出')
    expect(checklistCommand(ctx, ['child'], 'SELF', 'DAY', 'ADD').ids).toEqual(['child'])
  })
  it('摘要区分今日、本周、下周；历史和已移出记录保留真实日期', () => {
    const today = '2030-10-04'
    expect(taskPlanSummaryLabel(plan('week'), today)).toBe('本周计划 · 2030-09-30')
    expect(taskPlanSummaryLabel({ ...plan('day'), period: 'DAY', date: today }, today)).toBe('今日计划 · 2030-10-04')
    expect(taskPlanSummaryLabel({ ...plan('old-week'), date: '2030-09-23' }, today)).toBe('周计划 · 2030-09-23')
    expect(taskPlanSummaryLabel({ ...plan('old-day'), period: 'DAY', date: '2030-10-03' }, today)).toBe(
      '日计划 · 2030-10-03'
    )
    expect(taskPlanSummaryLabel({ ...plan('future-week'), date: '2030-10-07' }, today)).toBe('下周计划 · 2030-10-07')
    expect(taskPlanSummaryLabel({ ...plan('removed'), active: false }, today)).toBe('周计划 · 2030-09-30')
    expect(taskPlanSummaryLabel({ ...plan('legacy'), mode: 'SCHEDULE' }, today)).toContain('旧版区间安排')
  })
  it('下周写入沿用 WEEK 协议和服务端锚点，移出不触及本周项', () => {
    const ctx = context()
    taskItem(ctx, 'child').weekPlans = [plan('week')]
    taskItem(ctx, 'child').nextWeekPlans = [{ ...plan('next'), date: ctx.nextWeekStart! }]
    expect(checklistCommand(ctx, ['child'], 'SELF', 'NEXT_WEEK', 'ADD')).toMatchObject({
      period: 'WEEK',
      date: '2030-10-07'
    })
    expect(checklistCommand(ctx, ['child'], 'SELF', 'NEXT_WEEK', 'REMOVE', ['next'])).toMatchObject({
      period: 'WEEK',
      date: '2030-10-07',
      planIds: ['next']
    })
    expect(() => checklistCommand(ctx, ['child'], 'SELF', 'NEXT_WEEK', 'REMOVE', ['week'])).toThrow('不可移出')
    delete ctx.nextWeekStart
    expect(() => checklistCommand(ctx, ['child'], 'SELF', 'NEXT_WEEK', 'ADD')).toThrow('上下文不可用')
  })
  it('只处理显式所选任务，当前日周日期来自后台，不构造父子联动', () => {
    const ctx = context()
    const command = checklistCommand(ctx, ['child', 'child'], 'SELF', 'DAY', 'ADD')
    expect(command).toMatchObject({ ids: ['child'], date: ctx.today, period: 'DAY', expectedVersions: { child: 7 } })
    expect(command).not.toHaveProperty('planIds')
    expect(command).not.toHaveProperty('endDate')
  })
  it('REMOVE仅接受所选期间真实可移身份，拒绝主管/其他日周/其他任务身份', () => {
    const ctx = context()
    taskItem(ctx, 'child').weekPlans = [plan('mine'), plan('protected', false)]
    taskItem(ctx, 'child').todayPlans = [{ ...plan('daily'), period: 'DAY', date: ctx.today }]
    taskItem(ctx, 'parent').weekPlans = [plan('parent-plan')]
    expect(checklistCommand(ctx, ['child'], 'SELF', 'WEEK', 'REMOVE', ['mine'])).toMatchObject({
      planIds: ['mine'],
      date: ctx.weekStart
    })
    for (const id of ['protected', 'daily', 'parent-plan', 'missing'])
      expect(() => checklistCommand(ctx, ['child'], 'SELF', 'WEEK', 'REMOVE', [id])).toThrow('不可移出')
  })
  it('空集合、缺上下文、不可加入均关闭提交，不猜权限', () => {
    expect(() => checklistCommand(context(), [], 'SELF', 'WEEK', 'ADD')).toThrow('不完整')
    expect(() => checklistCommand(context(), ['missing'], 'SELF', 'WEEK', 'ADD')).toThrow('不完整')
    const ctx = context()
    taskItem(ctx, 'child').canAdd = false
    taskItem(ctx, 'child').reason = '任务待验收'
    expect(() => checklistCommand(ctx, ['child'], 'SELF', 'DAY', 'ADD')).toThrow('任务待验收')
  })
  it('未知结果按同一body取回原回执，成功清理pending再准备新变更', async () => {
    const attempt = createChecklistAttempt()
    const prepare = vi.fn(() => checklistCommand(context(), ['child'], 'SELF', 'DAY', 'ADD'))
    const checklist = vi
      .fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue({ changed: ['child'], unchanged: [] })
    await expect(attempt.submit({ checklist }, prepare)).rejects.toThrow('network')
    const pending = attempt.pending
    expect(pending).not.toBeNull()
    await attempt.submit({ checklist }, prepare)
    expect(prepare).toHaveBeenCalledOnce()
    expect(checklist.mock.calls[1]?.[0]).toBe(pending)
    expect(attempt.pending).toBeNull()
    await attempt.submit({ checklist }, prepare)
    expect(prepare).toHaveBeenCalledTimes(2)
  })
  it('明确业务拒绝后不保留失效版本；下一次重新准备', async () => {
    const attempt = createChecklistAttempt()
    const prepare = vi.fn(() => checklistCommand(context(), ['child'], 'SELF', 'WEEK', 'ADD'))
    const checklist = vi
      .fn()
      .mockRejectedValueOnce(Object.assign(new Error('stale'), { businessCode: 409 }))
      .mockResolvedValue({ changed: [], unchanged: [] })
    await expect(attempt.submit({ checklist }, prepare)).rejects.toThrow('stale')
    expect(attempt.pending).toBeNull()
    await attempt.submit({ checklist }, prepare)
    expect(prepare).toHaveBeenCalledTimes(2)
    expect(checklist.mock.calls[1]?.[0].requestKey).not.toBe(checklist.mock.calls[0]?.[0].requestKey)
  })
  it('上下文读取失败不产生待重试写命令', async () => {
    const attempt = createChecklistAttempt(),
      checklist = vi.fn()
    await expect(
      attempt.submit({ checklist }, async () => {
        throw new Error('read failed')
      })
    ).rejects.toThrow()
    expect(attempt.pending).toBeNull()
    expect(checklist).not.toHaveBeenCalled()
  })
  it('新API保持LocalDate和字符串雪花身份，不发送旧schedule或plan', async () => {
    const post = vi.fn().mockResolvedValue(context())
    const api = createTaskCenterApi({ post } as unknown as NocodeHttpClient)
    await api.checklistContext({ ids: ['child'], target: 'SELF' })
    const command = checklistCommand(context(), ['child'], 'SELF', 'DAY', 'ADD')
    await api.checklist(command)
    expect(post).toHaveBeenNthCalledWith(1, '/nocode/tasks/checklist-context', { ids: ['child'], target: 'SELF' })
    expect(post).toHaveBeenNthCalledWith(2, '/nocode/tasks/checklist', command)
  })
  it('缺mode仅识别为旧安排，不推断成新清单', () => {
    expect(taskPlanHistoryLabel({ ...plan('old'), mode: undefined, period: 'MONTH' })).toContain('旧版区间安排 · 月')
    expect(taskPlanHistoryLabel(plan('past'))).toContain('过往清单 · 周')
  })
})
