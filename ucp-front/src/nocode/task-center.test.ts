import { describe, expect, it } from 'vitest'
import {
  hasTaskPlan,
  newAutoTaskNode,
  newTaskNode,
  planDate,
  taskNodeError,
  taskNodeInput,
  taskTree
} from './task-center'
import type { TaskRow } from '@/types/nocode/task-center'

describe('任务中心计划和编排规则', () => {
  it('仅新模板显式默认跟随顺序，个人拆分与已有输入不被自动升级', () => {
    const root = newAutoTaskNode()
    const child = newAutoTaskNode(root.id, { assigneeId: '9007199254740993' })
    expect(root.schedule).toEqual({ mode: 'AUTO', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 1 })
    expect(child.schedule.mode).toBe('AUTO')
    expect(child).toMatchObject({ parentId: root.id, assigneeId: '9007199254740993' })
    expect(newTaskNode(root.id).schedule.mode).toBe('UNSCHEDULED')
    const old = { ...newTaskNode(), id: 'old', rootId: 'old' } as TaskRow
    expect(taskNodeInput(old).schedule.mode).toBe('UNSCHEDULED')
  })
  it('自动排期叶节点必须填有效工期和间隔，父节点不用独立填写', () => {
    const root = { ...newAutoTaskNode(), title: '总任务' }
    root.schedule.durationDays = null as unknown as number
    expect(taskNodeError([root])).toContain('计划工期')
    const child = { ...newAutoTaskNode(root.id), title: '执行任务' }
    expect(taskNodeError([root, child])).toBeNull()
    child.schedule.offsetDays = Number.NaN
    expect(taskNodeError([root, child])).toContain('开始间隔')
    child.schedule.offsetDays = 0
    child.schedule.durationDays = 0
    expect(taskNodeError([root, child])).toBeNull()
    child.schedule.durationDays = 1.5
    expect(taskNodeError([root, child])).toContain('计划工期')
  })
  it('新子项明确随总任务，旧开放节点和已承接来源不被推断或清空', () => {
    const child = newTaskNode('root', { assigneeId: null })
    expect(child).toMatchObject({ assignmentMode: 'FOLLOW_ROOT', assigneeId: null, candidateUserIds: [] })
    expect(newTaskNode()).toMatchObject({ assignmentMode: 'OPEN', assigneeId: null })
    const old = { ...newTaskNode(), id: 'child', rootId: 'root', parentId: 'root', assignmentMode: 'OPEN' } as TaskRow
    expect(taskNodeInput(old).assignmentMode).toBe('OPEN')
    const taken = { ...old, assignmentMode: 'FOLLOW_ROOT', assigneeId: '9007199254740993' } as TaskRow
    expect(taskNodeInput(taken)).toMatchObject({ assignmentMode: 'FOLLOW_ROOT', assigneeId: '9007199254740993' })
  })
  it('调整时仅根提交统一授权，子行只读有效授权不转成覆盖配置', () => {
    const policy = { version: 1 as const, business: 'ALL' as const, feedback: 'GROUP' as const }
    const row = { ...newTaskNode(), id: 'root', rootId: 'root', dataPolicy: policy } as TaskRow
    const input = taskNodeInput(row)
    expect(input.dataPolicy).toEqual(policy)
    expect(input.dataPolicy).not.toBe(policy)
    expect(taskNodeInput({ ...row, id: 'child', parentId: 'root' })).not.toHaveProperty('dataPolicy')
  })
  it('旧运行节点保持原人员缺省协议和日期规则，不因调整名称而改变零工期截止', () => {
    const row = {
      ...newTaskNode(),
      legacyProtocol: true,
      assignmentMode: 'ASSIGNED',
      candidateUserIds: [],
      assigneeId: 'user',
      expectedEnd: '2030-03-01T08:00:00',
      schedule: { mode: 'FIXED', fixedStart: '2030-03-01T08:00:00', offsetDays: 0, durationDays: 0 }
    } as unknown as TaskRow
    const input = taskNodeInput(row)
    expect(input).not.toHaveProperty('assignmentMode')
    expect(input).not.toHaveProperty('candidateUserIds')
    expect(input.schedule).toEqual(row.schedule)
    expect(input.schedule).not.toHaveProperty('fixedEnd')
    expect(input.assigneeId).toBe('user')
  })
  it('新节点允许人员和时间后补；隐藏的固定日期不会阻止暂不安排', () => {
    const node = { ...newTaskNode(), title: '独立小任务' }
    expect(node).toMatchObject({
      assignmentMode: 'OPEN',
      candidateUserIds: [],
      assigneeId: null,
      schedule: { mode: 'UNSCHEDULED' }
    })
    expect(taskNodeError([node])).toBeNull()
    node.assignmentMode = 'UNASSIGNED'
    expect(taskNodeError([node])).toBeNull()
    node.assignmentMode = 'ASSIGNED'
    expect(taskNodeError([node])).toContain('负责人')
    node.assigneeId = '9007199254740993'
    node.schedule.fixedStart = '2030-01-05T08:00:00'
    node.schedule.fixedEnd = '2030-01-01T08:00:00'
    node.schedule.mode = 'FIXED'
    expect(taskNodeError([node])).toContain('预计结束不能早于预计开始')
    node.schedule.mode = 'UNSCHEDULED'
    expect(taskNodeError([node])).toBeNull()
  })
  it('跨月周计划始终以周一定位，移出日计划不混淆周计划', () => {
    expect(planDate('WEEK', '2026-10-04')).toBe('2026-09-28')
    expect(planDate('MONTH', '2026-10-04')).toBe('2026-10-01')
    const plans = [{ period: 'WEEK' as const, date: '2026-09-28' }]
    expect(hasTaskPlan(plans, 'WEEK', '2026-10-04')).toBe(true)
    expect(hasTaskPlan(plans, 'DAY', '2026-10-04')).toBe(false)
  })
  it('按日期截止包含整天，旧开始时刻配同日结束有效，但逆序日期仍拒绝', () => {
    const node = { ...newTaskNode(), title: '同日采购' }
    node.schedule.mode = 'FIXED'
    node.schedule.fixedStart = '2030-03-04T08:00:00'
    node.schedule.fixedEnd = '2030-03-04'
    expect(taskNodeError([node])).toBeNull()
    node.schedule.fixedStart = '2030-03-04'
    expect(taskNodeError([node])).toBeNull()
    node.schedule.fixedEnd = '2030-03-03'
    expect(taskNodeError([node])).toContain('预计结束不能早于预计开始')
    node.schedule.fixedStart = '2030-03-04T08:00:00'
    expect(taskNodeError([node])).toContain('预计结束不能早于预计开始')
  })
  it('旧双精确时间继续校验先后，不因日期展示放过同日的逆序时刻', () => {
    const node = { ...newTaskNode(), title: '旧计划' }
    node.schedule.mode = 'FIXED'
    node.schedule.fixedStart = '2030-03-04T08:00:00'
    node.schedule.fixedEnd = '2030-03-04T07:59:59'
    expect(taskNodeError([node])).toContain('预计结束不能早于预计开始')
    node.schedule.fixedEnd = '2030-03-04T08:00:00'
    expect(taskNodeError([node])).toBeNull()
  })
  it('允许多个顶层工序和合法嵌套，拒绝父子等待死锁及纯依赖环', () => {
    const a = { ...newTaskNode(), id: 'a', title: '总任务' }
    const b = { ...newTaskNode('a'), id: 'b', title: '子任务' }
    const c = { ...newTaskNode(), id: 'c', title: '另一工序', predecessorIds: ['b'] }
    expect(taskNodeError([a, b, c])).toBeNull()
    expect(taskNodeError([{ ...a, predecessorIds: ['c'] }, b, c])).toContain('死锁')
    expect(taskNodeError([a, { ...b, predecessorIds: ['a'] }, c])).toContain('死锁')
    expect(
      taskNodeError([
        { ...a, predecessorIds: ['c'] },
        { ...c, predecessorIds: ['a'] }
      ])
    ).toContain('循环')
  })
  it('前置时间、固定时间与空任务提供提交前的具体错误', () => {
    const node = newTaskNode()
    expect(taskNodeError([node])).toContain('名称')
    node.title = '施工'
    node.schedule.mode = 'PREDECESSOR'
    expect(taskNodeError([node])).toContain('配置前置任务')
    node.schedule.mode = 'FIXED'
    expect(taskNodeError([node])).toContain('预计开始或预计结束')
    node.schedule.fixedStart = '2026-09-29T08:00:00'
    expect(taskNodeError([node])).toBeNull()
  })
  it('独立保存父子关系，不把前驱边误当父级，页外父级不伪造', () => {
    const rows = [
      { id: 'a', parentId: null },
      { id: 'b', parentId: 'a' },
      { id: 'c', parentId: 'outside' }
    ]
    const tree = taskTree(rows)
    expect(tree.map(row => row.id)).toEqual(['a', 'c'])
    expect(tree[0]?.children?.map(row => row.id)).toEqual(['b'])
    expect(rows[0]).not.toHaveProperty('children')
  })
  it('共享节点调整保留共享配置，去除仅运行读取用的来源表单身份', () => {
    const row = {
      ...newTaskNode(),
      sharing: { mode: 'SHARED', sourceNodeId: 'source', writableFieldIds: ['quantity'] },
      binding: { applicationId: 'app', formId: 'form', entryId: null }
    } as TaskRow
    const input = taskNodeInput(row)
    expect(input.binding).toBeNull()
    expect(input.sharing).toEqual(row.sharing)
    input.sharing.writableFieldIds.push('other')
    expect(row.sharing.writableFieldIds).toEqual(['quantity'])
  })
  it('运行实例调整保留多入口配置并深拷贝字段范围', () => {
    const row = {
      ...newTaskNode(),
      entries: [
        {
          key: 'entry',
          name: '施工记录',
          binding: { applicationId: 'app', formId: 'form', entryId: null },
          dataMode: 'ROOT_SHARED',
          sourceNodeId: null,
          sourceEntryKey: null,
          readableFieldIds: null,
          writableFieldIds: ['quantity'],
          required: false,
          allowAll: false
        }
      ]
    } as TaskRow
    const input = taskNodeInput(row)
    expect(input.entries).toEqual(row.entries)
    input.entries![0]!.writableFieldIds!.push('other')
    expect(row.entries![0]!.writableFieldIds).toEqual(['quantity'])
  })
})
