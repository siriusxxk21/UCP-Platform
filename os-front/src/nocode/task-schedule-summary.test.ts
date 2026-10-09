import { describe, expect, it } from 'vitest'
import type { TaskNodeInput, TaskSchedule } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'
import { taskInstanceScheduleSummary, taskScheduleSummary } from './task-schedule-summary'

const node = (id = 'a', schedule: Partial<TaskSchedule> = {}): TaskNodeInput => ({
  ...newTaskNode(),
  id,
  title: id,
  schedule: { ...newTaskNode().schedule, ...schedule }
})

describe('员工详情时间安排', () => {
  const summary = (
    schedule: Partial<TaskSchedule> = {},
    expectedStart: string | null = null,
    expectedEnd: string | null = null
  ) => taskInstanceScheduleSummary({ schedule: node('a', schedule).schedule, expectedStart, expectedEnd })

  it('自动排期详情只展示服务端日期，汇总父任务不显示独立默认工期', () => {
    const task = {
      schedule: node('root', { mode: 'AUTO', durationDays: 1 }).schedule,
      expectedStart: '2030-03-01',
      expectedEnd: '2030-03-10',
      childCount: 2
    }
    expect(taskInstanceScheduleSummary(task)).toBe('计划 2030-03-01 开始，2030-03-10 完成 · 按下级汇总')
    expect(taskInstanceScheduleSummary({ ...task, expectedStart: null, expectedEnd: null })).toBe('按下级汇总')
    expect(summary({ mode: 'AUTO', durationDays: 2 })).toBe('跟随任务顺序 · 工期 2 天')
    expect(summary({ mode: 'AUTO', offsetDays: 1, durationDays: 2 })).toBe('任务顺序确定的起点 1 天后开始 · 工期 2 天')
  })

  it('优先展示已计算的计划日期和配置工期，不显示排期公式', () => {
    expect(summary({ mode: 'PLAN_START', durationDays: 5 }, '2026-10-03T00:00:00', '2026-10-08T00:00:00')).toBe(
      '计划 2026-10-03 开始，2026-10-08 完成 · 工期 5 天'
    )
  })

  it.each([
    ['2026-10-03', null, '计划 2026-10-03 开始，完成日期待定'],
    [null, '2026-10-08', '开始日期待定，计划 2026-10-08 完成'],
    ['2026-10-03', '2026-10-03', '计划 2026-10-03 当天开始并完成']
  ])('指定日期只展示已有边界，不补算日期或展示无关的默认工期', (start, end, expected) => {
    expect(summary({ mode: 'FIXED', durationDays: 1 }, start, end)).toBe(expected)
  })

  it.each([
    ['PLAN_START', 0, '跟随整组计划开始'],
    ['PLAN_START', 2, '整组计划开始 2 天后开始'],
    ['PLAN_START', -1, '比整组计划提前 1 天开始'],
    ['PREDECESSOR', 0, '前序任务最晚完成后开始'],
    ['PREDECESSOR', 2, '前序任务最晚完成 2 天后开始'],
    ['T0', 0, '任务创建当天开始'],
    ['T0', 2, '任务创建 2 天后开始']
  ] as const)('尚无日期时将 %s / %s 表达为员工能理解的安排', (mode, offsetDays, text) => {
    expect(summary({ mode, offsetDays, durationDays: 3 })).toBe(`${text} · 工期 3 天`)
  })

  it('无排期不展示默认工期，固定日期尚未产生时也不补算', () => {
    expect(summary()).toBe('暂不安排')
    expect(summary({ mode: 'FIXED', fixedStart: '2026-10-03', durationDays: 5 })).toBe('计划日期待定')
  })

  it('预计日期不被实际执行时间或配置起点覆盖，不改变输入', () => {
    const task = {
      schedule: node('a', { mode: 'PREDECESSOR', offsetDays: 2, durationDays: 5 }).schedule,
      expectedStart: '2026-10-03',
      expectedEnd: '2026-10-08',
      actualStart: '2026-10-05',
      actualEnd: '2026-10-06'
    }
    const before = JSON.stringify(task)
    expect(taskInstanceScheduleSummary(task)).toBe('计划 2026-10-03 开始，2026-10-08 完成 · 工期 5 天')
    expect(JSON.stringify(task)).toBe(before)
  })

  it.each([null, undefined, Number.NaN, Infinity])('缺失或无效配置 %s 不变成零天安排', value => {
    expect(summary({ mode: 'PLAN_START', offsetDays: value as number, durationDays: value as number })).toBe(
      '计划日期待定'
    )
  })

  it('有效零天工期仍如实显示', () => {
    expect(summary({ mode: 'PLAN_START', durationDays: 0 })).toBe('跟随整组计划开始 · 工期 0 天')
  })
})

describe('任务编排时间的人话摘要', () => {
  it('自动排期叶节点摘要始终跟随顺序，不用位置排序猜前置关系', () => {
    const first = node('first', { mode: 'AUTO' })
    const parallel = node('parallel', { mode: 'AUTO', durationDays: 3 })
    const summary = taskScheduleSummary(parallel, [first, parallel], '2030-03-01')
    expect(summary).toMatchObject({ primary: '跟随任务顺序', secondary: '工期 3 天' })
    expect(summary.hint).toContain('跟随整体计划开始日 2030-03-01，并行任务同日开始')
    expect(summary.hint).not.toContain('前序任务')
    expect(taskScheduleSummary(parallel, [parallel, first], '2030-03-01')).toEqual(summary)
  })
  it('自动排期识别祖先前置，父任务不显示其独立工期和间隔', () => {
    const parent = { ...node('parent', { mode: 'AUTO', durationDays: 20, offsetDays: 5 }), predecessorIds: ['before'] }
    const child = { ...node('child', { mode: 'AUTO', durationDays: 2, offsetDays: 1 }), parentId: parent.id }
    const nodes = [parent, child, node('before')]
    expect(taskScheduleSummary(parent, nodes)).toMatchObject({
      primary: '按下级汇总',
      secondary: '下级日期自动汇总'
    })
    expect(taskScheduleSummary(child, nodes)).toMatchObject({
      primary: '跟随任务顺序',
      secondary: '工期 2 天 · 间隔 1 天'
    })
    expect(taskScheduleSummary(child, nodes).hint).toContain('上级前序任务的最晚完成日期')
    expect(taskScheduleSummary(child, nodes).hint).toContain('已完成取实际日期，未完成取预计日期')
  })
  it('自动排期尚未填写工期与间隔不伪造零天，不改写输入', () => {
    const task = node('a', { mode: 'AUTO', durationDays: null as unknown as number, offsetDays: Number.NaN })
    expect(taskScheduleSummary(task, [])).toMatchObject({
      primary: '跟随任务顺序',
      secondary: '工期待填写 · 间隔待填写'
    })
    expect(task.schedule.durationDays).toBeNull()
    expect(task.schedule.offsetDays).toBeNaN()
  })
  it('暂不安排不伪造工期或日期', () => {
    expect(taskScheduleSummary(node(), [])).toEqual({
      primary: '暂不安排',
      hint: '不设置预计日期，不影响任务的先后顺序。'
    })
  })

  it.each([
    [0, '整体计划开始当天'],
    [2, '整体计划开始后 2 天'],
    [-1, '整体计划开始前 1 天']
  ])('整体起点偏移 %s 天不显示 T0，也不把计划开始说成实际开始', (offsetDays, primary) => {
    const task = node('a', { mode: 'PLAN_START', offsetDays, durationDays: 3 })
    expect(taskScheduleSummary(task, [task], '2030-03-05T10:30:00')).toEqual({
      primary,
      secondary: '预计用时 3 天',
      hint: '整体计划开始日：2030-03-05；不代表任务的实际开始时间。'
    })
    expect(taskScheduleSummary(task, [task]).hint).toContain('整体计划开始日待确定')
  })

  it('已设置前序但仍用整体起点时解释两者区别，不改写用户的时间规则', () => {
    const task = {
      ...node('b', { mode: 'PLAN_START', offsetDays: 0, durationDays: 3 }),
      predecessorIds: ['a']
    }
    const before = JSON.stringify(task)
    const summary = taskScheduleSummary(task, [node('a'), task])
    expect(summary.primary).toBe('整体计划开始当天')
    expect(summary.hint).toBe('执行仍需等待前序完成；预计日期按整体计划开始日计算。整体计划开始日待确定。')
    expect(JSON.stringify(task)).toBe(before)
  })

  it('继承上级的执行门控也提示与日期规则无关，异常循环不造成死循环', () => {
    const parent = { ...node('parent'), predecessorIds: ['before'], parentId: 'child' }
    const child = { ...node('child', { mode: 'PLAN_START' }), parentId: 'parent' }
    expect(taskScheduleSummary(child, [parent, child]).hint).toContain('执行仍需等待前序完成')
    expect(taskScheduleSummary(child, [{ ...parent, predecessorIds: [] }, child]).hint).not.toContain('执行仍需等待')
  })

  it('前序日期区分预计与实际，不把排期表示为自动开始', () => {
    const first = { ...node('prepare'), title: '现场勘察' }
    const task = {
      ...node('build', { mode: 'PREDECESSOR', offsetDays: 0, durationDays: 3 }),
      predecessorIds: ['prepare']
    }
    expect(taskScheduleSummary(task, [task, first])).toEqual({
      primary: '现场勘察完成后',
      secondary: '预计用时 3 天',
      hint: '以前序任务最晚完成日期接续；未完成取预计日期，已完成取实际日期，仍需负责人点击开始。'
    })
    expect(taskScheduleSummary({ ...task, schedule: { ...task.schedule, offsetDays: 2 } }, [first]).primary).toBe(
      '现场勘察完成后 2 天'
    )
  })

  it('多前序摘要按最晚完成日期，提示中列出任务名称和日期来源', () => {
    const first = { ...node('prepare'), title: '现场勘察' }
    const second = { ...node('purchase'), title: '材料采购' }
    const task = {
      ...node('build', { mode: 'PREDECESSOR', offsetDays: 1, durationDays: 3 }),
      predecessorIds: ['prepare', 'purchase', 'prepare']
    }
    expect(taskScheduleSummary(task, [first, second])).toEqual({
      primary: '2 项前序任务最晚完成后 1 天',
      secondary: '预计用时 3 天',
      hint: '前序任务：现场勘察、材料采购。以前序任务最晚完成日期接续；未完成取预计日期，已完成取实际日期，仍需负责人点击开始。'
    })
  })

  it('未载入或未配置前序不伪造名字、日期或正常排期提示', () => {
    const task = node('build', { mode: 'PREDECESSOR', durationDays: 3 })
    expect(taskScheduleSummary(task, []).hint).toBe('尚未设置前序任务，暂无法按完成时间排期。')
    expect(taskScheduleSummary({ ...task, predecessorIds: ['hidden'] }, []).primary).toBe('未载入的前序任务完成后')
  })

  it.each([
    [{ fixedStart: '2030-04-03T08:30:00', fixedEnd: '2030-04-07T17:30:00' }, '2030-04-03 至 2030-04-07'],
    [{ fixedStart: '2030-04-03T08:30:00', fixedEnd: '2030-04-03T17:30:00' }, '2030-04-03'],
    [{ fixedStart: '2030-04-03T08:30:00' }, '开始 2030-04-03'],
    [{ fixedStart: null, fixedEnd: '2030-04-07T17:30:00' }, '截止 2030-04-07'],
    [{ fixedStart: null }, '指定日期（待填写）']
  ] as const)('指定日期不补算缺失边界 %j', (schedule, primary) => {
    const task = node('a', { mode: 'FIXED', durationDays: 3, ...schedule })
    expect(taskScheduleSummary(task, []).primary).toBe(primary)
    expect(taskScheduleSummary(task, []).secondary).toBeUndefined()
  })

  it('历史创建时间规则与整体计划开始日严格区分', () => {
    const task = node('a', { mode: 'T0', offsetDays: 0, durationDays: 2 })
    expect(taskScheduleSummary(task, [task], '2030-04-01')).toEqual({
      primary: '创建任务当天',
      secondary: '预计用时 2 天',
      hint: '历史规则：以任务实际创建时间为起点；不使用整体计划开始日。'
    })
    expect(taskScheduleSummary({ ...task, schedule: { ...task.schedule, offsetDays: 1 } }, []).primary).toBe(
      '创建任务后 1 天'
    )
  })

  it.each([null, undefined, Number.NaN, Infinity, -Infinity])('空白或无效偏移 %s 不当作零天', value => {
    for (const mode of ['PLAN_START', 'PREDECESSOR', 'T0'] as const) {
      const task = node('a', { mode, offsetDays: value as number, durationDays: 3 })
      const summary = taskScheduleSummary(task, [])
      expect(summary.primary).toContain('偏移待填写')
      expect(summary.primary).not.toContain('当天')
      expect(summary.secondary).toBe('预计用时 3 天')
      expect(task.schedule.offsetDays).toBe(value)
    }
  })

  it.each([null, undefined, Number.NaN, Infinity, -Infinity])('空白或无效工期 %s 只提示待填写', value => {
    const task = node('a', { mode: 'PLAN_START', offsetDays: 0, durationDays: value as number })
    const summary = taskScheduleSummary(task, [])
    expect(summary.primary).toBe('整体计划开始当天')
    expect(summary.secondary).toBe('工期待填写')
    expect(task.schedule.durationDays).toBe(value)
  })

  it('明确填写零天保留零天，缺少偏移和工期时分别提示', () => {
    const task = node('a', { mode: 'PLAN_START', offsetDays: 0, durationDays: 0 })
    expect(taskScheduleSummary(task, [])).toMatchObject({
      primary: '整体计划开始当天',
      secondary: '预计用时 0 天'
    })
    task.schedule.offsetDays = null as unknown as number
    task.schedule.durationDays = null as unknown as number
    expect(taskScheduleSummary(task, [])).toMatchObject({
      primary: '整体计划开始（偏移待填写）',
      secondary: '工期待填写'
    })
  })
})
