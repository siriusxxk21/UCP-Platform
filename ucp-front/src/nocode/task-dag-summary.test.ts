import { describe, expect, it } from 'vitest'
import { newTaskNode } from './task-center'
import { taskDagCardSummary, type TaskDagDisplayNode, type TaskDagRuntimeInfo } from './task-dag-summary'
import type { TaskSchedule } from '@/types/nocode/task-center'

const node = (id = 'a', schedule: Partial<TaskSchedule> = {}): TaskDagDisplayNode => ({
  ...newTaskNode(),
  id,
  title: id,
  schedule: { ...newTaskNode().schedule, ...schedule }
})
describe('图卡状态负责人及时间摘要', () => {
  it('自动规则沿用列表摘要，父任务只汇总下级日期，整体起点改动不显示旧预计', () => {
    const root = node('root', { mode: 'AUTO', durationDays: 30 })
    const child = { ...node('child', { mode: 'AUTO', durationDays: 2 }), parentId: root.id }
    expect(taskDagCardSummary(root, { context: 'template', nodes: [root, child] }).time).toBe(
      '按下级汇总 · 下级日期自动汇总'
    )
    expect(taskDagCardSummary(child, { context: 'template', nodes: [root, child] }).time).toBe(
      '跟随任务顺序 · 工期 2 天'
    )
    const summary = taskDagCardSummary(child, {
      context: 'instance',
      nodes: [root, child],
      plannedStart: '2030-04-02',
      runtime: {
        id: child.id,
        plannedStart: '2030-04-01',
        expectedStart: '2030-04-01',
        expectedEnd: '2030-04-03'
      }
    })
    expect(summary.rule).toBe('待保存后重新计算预计日期')
    expect(summary.time).not.toContain('2030-04-01')
  })
  it('不完整汇总明确提示部分日期，已有警告使用服务端原因，配置改动后不沿用旧警告', () => {
    const task = node('root', { mode: 'AUTO' })
    const runtime: TaskDagRuntimeInfo = {
      id: task.id,
      schedule: task.schedule,
      expectedStart: '2030-04-01',
      expectedEnd: '2030-04-03',
      scheduleSummary: { source: 'ROLLUP', partial: true, warnings: ['有下级尚未排期'] }
    }
    const summary = taskDagCardSummary(task, { context: 'instance', nodes: [task], runtime })
    expect(summary.time).toBe('预计：2030-04-01 至 2030-04-03（部分）')
    expect(summary.rule).toBe('有下级尚未排期')
    expect(
      taskDagCardSummary(task, { context: 'instance', nodes: [task], runtime: { ...runtime, expectedStale: true } })
        .rule
    ).toBe('待保存后重新计算预计日期')
  })
  it('同组只读概要显示真实负责人与状态，不把占位配置当作真实规则', () => {
    const summary = taskDagCardSummary(
      { ...node(), referenceOnly: true },
      {
        context: 'instance',
        nodes: [],
        runtime: {
          id: 'a',
          status: 'PENDING',
          assigneeName: '待领取',
          expectedStart: '2026-10-03',
          expectedEnd: '2026-10-05'
        }
      }
    )
    expect(summary).toMatchObject({ status: '未开始', owner: '待领取', rule: '同组任务 · 仅查看编排概要' })
    expect(summary.time).toContain('2026-10-03')
    expect(summary.time).not.toContain('暂不安排')
  })
  it('模板无运行状态、草稿未运行；实例六种状态保留真实语义', () => {
    expect(taskDagCardSummary({ ...node(), status: 'RUNNING' }, { context: 'template', nodes: [] })).toMatchObject({
      status: '模板配置',
      state: undefined
    })
    expect(taskDagCardSummary(node(), { context: 'draft', nodes: [] }).status).toBe('草稿')
    expect(taskDagCardSummary(node(), { context: 'instance', nodes: [] }).status).toBe('草稿（新增）')
    for (const [state, status] of Object.entries({
      PENDING: '未开始',
      RUNNING: '进行中',
      PAUSED: '已暂停',
      PENDING_ACCEPTANCE: '待验收',
      COMPLETED: '已完成',
      CANCELLED: '已取消'
    })) {
      const runtime = { id: 'a', status: state } as TaskDagRuntimeInfo
      expect(taskDagCardSummary(node(), { context: 'instance', nodes: [], runtime })).toMatchObject({ state, status })
    }
  })
  it('受上级暂停的节点显示已暂停，但不覆盖真实节点状态', () => {
    const runtime = { id: 'a', status: 'RUNNING' as const, pausedByTaskId: 'root' }
    expect(taskDagCardSummary(node(), { context: 'instance', nodes: [], runtime })).toMatchObject({
      state: 'PAUSED',
      status: '已暂停'
    })
    expect(runtime.status).toBe('RUNNING')
  })
  it('负责人按精确字符串ID解析；OPEN/UNASSIGNED不展示旧姓名，改负责人不冒用旧名称', () => {
    const assigned = { ...node(), assignmentMode: 'ASSIGNED' as const, assigneeId: '9007199254740993' }
    expect(
      taskDagCardSummary(assigned, { context: 'draft', nodes: [], members: [{ id: '9007199254740993', name: '王工' }] })
        .owner
    ).toBe('王工')
    const runtime = { id: 'a', assigneeId: '9007199254740993', assigneeName: '王工' }
    expect(
      taskDagCardSummary({ ...assigned, assignmentMode: 'OPEN' }, { context: 'instance', nodes: [], runtime }).owner
    ).toBe('待领取')
    expect(
      taskDagCardSummary({ ...assigned, assignmentMode: 'UNASSIGNED' }, { context: 'instance', nodes: [], runtime })
        .owner
    ).toBe('待分配')
    expect(
      taskDagCardSummary(
        { ...assigned, assigneeId: '9007199254740994', assigneeName: '王工' },
        { context: 'instance', nodes: [], runtime }
      ).owner
    ).toBe('已分配')
  })
  it('整体排期相对规则不以今天或创建时间推算，明确自然日偏移与工期', () => {
    const task = node('a', { mode: 'PLAN_START', offsetDays: 2, durationDays: 3 })
    const summary = taskDagCardSummary(task, {
      context: 'template',
      nodes: [task],
      plannedStart: '2030-03-05T10:30:00'
    })
    expect(summary.time).toBe('整体计划开始后 2 天 · 预计用时 3 天')
    expect(summary.rule).toBe('整体计划开始日：2030-03-05；不代表任务的实际开始时间。')
    expect(summary.time).not.toContain('2030-03-07')
    expect(taskDagCardSummary(task, { context: 'draft', nodes: [task] }).rule).toContain('整体计划开始日待确定')
  })
  it('旧T0始终指实例创建，绝不改解释为整体排期起点', () => {
    const task = node('a', { mode: 'T0', offsetDays: 0, durationDays: 2 })
    const summary = taskDagCardSummary(task, { context: 'template', nodes: [task], plannedStart: '2030-04-01' })
    expect(summary.time).toBe('创建任务当天 · 预计用时 2 天')
    expect(summary.rule).toBe('历史规则：以任务实际创建时间为起点；不使用整体计划开始日。')
    expect(summary.rule).not.toContain('2030-04-01')
  })
  it('前置按具体名称展示最晚完成规则，缺失节点不伪造名称或预计日期', () => {
    const first = { ...node('prepare'), title: '现场准备' },
      second = { ...node('build'), title: '施工' }
    const task = {
      ...node('a', { mode: 'PREDECESSOR', offsetDays: 1, durationDays: 2 }),
      predecessorIds: ['prepare', 'build']
    }
    const summary = taskDagCardSummary(task, { context: 'template', nodes: [task, first, second] })
    expect(summary.time).toBe('2 项前序任务最晚完成后 1 天 · 预计用时 2 天')
    expect(summary.rule).toBe(
      '前序任务：现场准备、施工。以前序任务最晚完成日期接续；未完成取预计日期，已完成取实际日期，仍需负责人点击开始。'
    )
    expect(
      taskDagCardSummary({ ...task, predecessorIds: ['prepare'] }, { context: 'draft', nodes: [first] }).time
    ).toBe('现场准备完成后 1 天 · 预计用时 2 天')
    expect(taskDagCardSummary({ ...task, predecessorIds: [] }, { context: 'draft', nodes: [] }).rule).toBe(
      '尚未设置前序任务，暂无法按完成时间排期。'
    )
    expect(taskDagCardSummary(task, { context: 'draft', nodes: [] }).rule).toContain('未载入的前序任务')
  })
  it.each([
    [{ fixedStart: '2030-04-03T08:30:00', fixedEnd: '2030-04-07T17:30:00' }, '2030-04-03 至 2030-04-07'],
    [{ fixedStart: '2030-04-03T08:30:00' }, '开始 2030-04-03'],
    [{ fixedStart: null, fixedEnd: '2030-04-07T17:30:00' }, '截止 2030-04-07'],
    [{ fixedStart: null }, '指定日期（待填写）']
  ] as const)('指定日期仅显示已填写边界，不为单边日期补另一端 %j', (schedule, expected) => {
    const task = node('a', { mode: 'FIXED', ...schedule })
    expect(taskDagCardSummary(task, { context: 'draft', nodes: [] }).time).toBe(expected)
  })
  it('实例服务端预计日期优先且保留规则，未排期不生成日期', () => {
    const task = node('a', { mode: 'PREDECESSOR', offsetDays: 2, durationDays: 4 })
    const summary = taskDagCardSummary(task, {
      context: 'instance',
      nodes: [],
      runtime: { id: 'a', status: 'RUNNING', expectedStart: '2030-04-07T17:30:00', expectedEnd: '2030-04-11T17:30:00' }
    })
    expect(summary.time).toBe('预计：2030-04-07 至 2030-04-11')
    expect(summary.rule).toContain('前序任务完成后 2 天 · 预计用时 4 天')
    expect(taskDagCardSummary(node(), { context: 'draft', nodes: [] }).time).toBe('暂不安排')
    expect(
      taskDagCardSummary(node('fixed', { mode: 'FIXED', fixedStart: '2030-04-07', fixedEnd: '2030-04-11' }), {
        context: 'instance',
        nodes: [],
        runtime: { id: 'fixed', expectedStart: '2030-04-07', expectedEnd: '2030-04-11' }
      })
    ).toMatchObject({ time: '预计：2030-04-07 至 2030-04-11', rule: '规则：指定日期' })
  })
  it('改规则或前置时不展示旧预计；仅改隐藏无关字段不误判时间已变', () => {
    const task = node('a', { mode: 'PLAN_START', offsetDays: 2, durationDays: 4 })
    const runtime: TaskDagRuntimeInfo = {
      id: 'a',
      status: 'PENDING',
      schedule: { ...task.schedule, offsetDays: 1 },
      expectedStart: '2030-04-07',
      expectedEnd: '2030-04-11'
    }
    const changed = taskDagCardSummary(task, { context: 'instance', nodes: [], runtime })
    expect(changed.time).toBe('整体计划开始后 2 天 · 预计用时 4 天')
    expect(changed.rule).toBe('待保存后重新计算预计日期')
    expect(
      taskDagCardSummary(
        { ...task, predecessorIds: ['b'] },
        { context: 'instance', nodes: [], runtime: { ...runtime, schedule: task.schedule, predecessorIds: [] } }
      ).rule
    ).toBe('待保存后重新计算预计日期')
    expect(
      taskDagCardSummary(node(), {
        context: 'instance',
        nodes: [],
        runtime: { ...runtime, schedule: { ...node().schedule, fixedStart: '2030-01-01' } }
      }).rule
    ).not.toContain('待保存')
  })
  it('整体起点改变时隐藏旧预计；父组件移除传递影响日期不会从node旧字段回填', () => {
    const task = {
      ...node('a', { mode: 'PLAN_START', offsetDays: 2, durationDays: 4 }),
      expectedStart: '2030-04-07',
      expectedEnd: '2030-04-11'
    }
    const runtime = {
      id: 'a',
      plannedStart: '2030-04-01',
      schedule: task.schedule,
      expectedStart: '2030-04-07',
      expectedEnd: '2030-04-11'
    }
    expect(taskDagCardSummary(task, { context: 'instance', nodes: [], plannedStart: '2030-04-02', runtime }).rule).toBe(
      '待保存后重新计算预计日期'
    )
    expect(taskDagCardSummary(task, { context: 'instance', nodes: [], runtime: { id: 'a' } }).time).not.toContain(
      '2030-04-07'
    )
  })
  it('未提供可选起点不视为清空；父组件标记上游改动可使下游预计显式失效', () => {
    const task = node('a', { mode: 'PLAN_START', offsetDays: 2, durationDays: 3 })
    const runtime = {
      id: 'a',
      plannedStart: '2030-04-01',
      schedule: task.schedule,
      expectedStart: '2030-04-03',
      expectedEnd: '2030-04-06'
    }
    expect(taskDagCardSummary(task, { context: 'instance', nodes: [], runtime }).time).toBe(
      '预计：2030-04-03 至 2030-04-06'
    )
    expect(taskDagCardSummary(task, { context: 'instance', nodes: [], runtime, plannedStart: null }).rule).toBe(
      '待保存后重新计算预计日期'
    )
    expect(
      taskDagCardSummary(task, { context: 'instance', nodes: [], runtime: { ...runtime, expectedStale: true } }).rule
    ).toBe('待保存后重新计算预计日期')
  })
  it('展示摘要不改写配置或运行快照', () => {
    const task = node('a', { mode: 'PLAN_START', offsetDays: 2 }),
      runtime = { id: 'a', status: 'RUNNING' as const }
    const before = JSON.stringify({ task, runtime })
    taskDagCardSummary(task, { context: 'instance', nodes: [task], runtime })
    expect(JSON.stringify({ task, runtime })).toBe(before)
  })
})
