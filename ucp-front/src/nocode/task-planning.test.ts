import { describe, expect, it } from 'vitest'
import { taskPlanInputError, taskPlanLabel, taskPlanRange } from './task-planning'
import type { TaskPlanContextItem } from '@/types/nocode/task-center'
const item = (patch: Partial<TaskPlanContextItem> = {}): TaskPlanContextItem => ({
  taskId: 'a',
  title: '采购',
  assigneeId: '2',
  version: 1,
  plans: [],
  constraints: [],
  history: [],
  canArrange: true,
  canCancel: false,
  readOnly: false,
  warnings: [],
  ...patch
})
describe('个人工作安排日期与主管范围', () => {
  it('具体日期和多天区间不改变精度', () => {
    expect(taskPlanRange('DAY', '2026-10-03')).toEqual({ date: '2026-10-03', endDate: '2026-10-03' })
    expect(taskPlanRange('DAY', '2026-10-03', '2026-10-06')).toEqual({ date: '2026-10-03', endDate: '2026-10-06' })
  })
  it('周从周一到周日，跨年也保留真实日期', () => {
    expect(taskPlanRange('WEEK', '2027-01-01')).toEqual({ date: '2026-12-28', endDate: '2027-01-03' })
    expect(taskPlanRange('WEEK', '2027-01-03')).toEqual({ date: '2026-12-28', endDate: '2027-01-03' })
  })
  it('月正确处理闰年，不把粗计划当每天的安排', () => {
    expect(taskPlanRange('MONTH', '2028-02-03')).toEqual({ date: '2028-02-01', endDate: '2028-02-29' })
    expect(taskPlanLabel({ period: 'MONTH', date: '2028-02-03' })).toContain('待细化到日期')
  })
  it('拒绝缺少和逆序日期', () => {
    expect(taskPlanInputError('DAY', '', '', [item()])).not.toBe('')
    expect(taskPlanInputError('DAY', '2026-10-04', '2026-10-03', [item()])).toContain('不能早于')
    expect(taskPlanInputError('DAY', '2026-02-31', '2026-03-04', [item()])).toContain('有效')
    expect(taskPlanInputError('DAY', '2026-02-01', '2026-02-31', [item()])).toContain('有效')
  })
  it('拒绝只读任务安排', () => {
    expect(
      taskPlanInputError('DAY', '2026-10-03', '2026-10-03', [item({ readOnly: true, reason: '任务已结束' })])
    ).toBe('任务已结束')
  })
  it('主管周安排只能细化到周内日期，不能扩范围', () => {
    const weekly = item({ constraints: [{ period: 'WEEK', date: '2026-09-28', endDate: '2026-10-04' }] })
    expect(taskPlanInputError('DAY', '2026-10-03', '2026-10-04', [weekly])).toBe('')
    expect(taskPlanInputError('DAY', '2026-10-03', '2026-10-05', [weekly])).toContain('需安排在')
    expect(taskPlanInputError('WEEK', '2026-10-03', '2026-10-03', [weekly])).toContain('细化到具体日期')
  })
  it('保留多个主管约束并检查交集', () => {
    const constrained = item({
      constraints: [
        { period: 'MONTH', date: '2026-10-01' },
        { period: 'WEEK', date: '2026-09-28' }
      ]
    })
    expect(taskPlanInputError('DAY', '2026-10-01', '2026-10-04', [constrained])).toBe('')
    expect(taskPlanInputError('DAY', '2026-09-30', '2026-10-04', [constrained])).toContain('需安排在')
  })
  it('每个批量任务均校验，不能只看第一个', () => {
    expect(taskPlanInputError('DAY', '2026-10-03', '2026-10-03', [item(), item({ canArrange: false })])).not.toBe('')
  })
})
