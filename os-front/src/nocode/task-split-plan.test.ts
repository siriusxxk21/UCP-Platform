import { describe, expect, it } from 'vitest'
import { taskSplitPlanSummary, taskSplitPath } from './task-split-plan'
import { newTaskNode } from './task-center'
import type { TaskRow } from '@/types/nocode/task-center'

const row = (id: string, parentId: string | null = null): TaskRow => ({
  ...newTaskNode(),
  id,
  rootId: 'root',
  parentId,
  title: id,
  assigneeId: 1,
  status: 'PENDING',
  plans: [],
  creatorId: 1,
  creatorName: '测试用户',
  assigneeName: '测试用户',
  project: null,
  business: null,
  baselineStart: null,
  baselineEnd: null,
  expectedStart: null,
  expectedEnd: null,
  actualStart: null,
  actualEnd: null,
  createdAt: '',
  revision: 1,
  instanceRevision: 1,
  childCount: 0,
  canStart: true,
  canExecute: true,
  canEdit: true,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
const today = '2026-10-06'
const week = { mode: 'CHECKLIST' as const, period: 'WEEK' as const, date: '2026-10-05' }
describe('拆分时的继承计划摘要', () => {
  it('只展示上级当前有效计划，不根据页面标签强制写入子任务计划', () => {
    const parent = { ...row('root'), plans: [{ ...week, period: 'DAY' as const, date: today }] }
    expect(taskSplitPlanSummary(parent, 1, today)).toBe('随上级纳入今日计划')
  })
  it('全部视图识别最近本人下周计划，不把它当本周或今日', () => {
    const parent = { ...row('root'), plans: [{ ...week, date: '2026-10-12' }] }
    expect(taskSplitPlanSummary(parent, 1, today)).toBe('随上级纳入下周计划')
  })
  it('服务端返回的继承计划同样适用于新子任务', () => {
    const parent = { ...row('step', 'root'), plans: [{ ...week, inheritedFromTaskId: 'root' }] }
    expect(taskSplitPlanSummary(parent, 1, today)).toBe('随上级纳入本周计划')
  })
  it('同一节点今日优先，缺省兼容本人清单响应', () => {
    const parent = { ...row('root'), plans: [week, { ...week, period: 'DAY' as const, date: today }] }
    expect(taskSplitPlanSummary(parent, '1', today)).toBe('随上级纳入今日、本周计划')
  })
  it('历史、已移出、其他用户和旧区间计划不用于默认安排', () => {
    const parent = {
      ...row('root'),
      plans: [
        { ...week, date: '2026-09-28' },
        { ...week, active: false },
        { ...week, userId: 2 },
        { ...week, mode: 'SCHEDULE' as const }
      ]
    }
    expect(taskSplitPlanSummary(parent, 1, today)).toBe('自动随本人上级计划，无需重复安排')
  })
  it('不使用他人父任务清单，也不猜测未加载的祖先', () => {
    const root = { ...row('root'), assigneeId: 2, plans: [week] }
    expect(taskSplitPlanSummary(row('child', 'missing'), 1, today)).toBe('自动随本人上级计划，无需重复安排')
    expect(taskSplitPlanSummary(root, 1, today)).toBe('自动随本人上级计划，无需重复安排')
  })
  it('预选无副作用，不修改父子或兄弟清单', () => {
    const parent = { ...row('root'), plans: [week] }
    const before = JSON.stringify(parent)
    taskSplitPlanSummary(parent, 1, today)
    expect(JSON.stringify(parent)).toBe(before)
  })
  it('位置支持已加载多级和授权祖先摘要，异常环也能终止', () => {
    const root = row('办公室装修')
    const parent = row('装修施工', root.id)
    expect(taskSplitPath(parent, [root])).toBe('办公室装修 › 装修施工')
    expect(taskSplitPath({ ...parent, ancestorContext: [{ ...root, detailVisible: true }] }, [])).toBe(
      '办公室装修 › 装修施工'
    )
    expect(taskSplitPath({ ...parent, parentId: parent.id }, [parent])).toBe('装修施工')
  })
})
