import { describe, expect, it, vi } from 'vitest'
import { createTaskEfficiencyApi } from '@/api/nocode/task-efficiency'
import type { NocodeHttpClient } from '@/api/nocode/object'
import { efficiencyChart, efficiencyDuration, efficiencyPeriodError } from './task-efficiency'
import type { TaskEfficiencyOverview } from '@/types/nocode/task-efficiency'

describe('任务能效统计口径与传输', () => {
  it('保留缺失与真实零值区别，支持小数、汇总大时长，不套用模板输入上限', () => {
    expect(efficiencyDuration(null)).toBe('—')
    expect(efficiencyDuration(0)).toBe('0 分钟')
    expect(efficiencyDuration(75.5)).toBe('1 小时 15.5 分钟')
    expect(efficiencyDuration(600000)).toBe('10,000 小时')
  })
  it('包含首尾最多 366 天，拒绝缺日期、倒序与超范围查询', () => {
    expect(efficiencyPeriodError('2026-01-01', '2027-01-01')).toBe('')
    expect(efficiencyPeriodError('2026-01-01', '2027-01-02')).toContain('366')
    expect(efficiencyPeriodError('', '2026-01-01')).toContain('完整')
    expect(efficiencyPeriodError('2026-02-01', '2026-01-01')).toContain('早于')
    expect(efficiencyPeriodError('2026-02-30', '2026-03-01')).toContain('完整')
  })
  it('图表只转换显示单位，保持员工下钻标识与工时总量，不构造效率得分', () => {
    const data = {
      standardMinutes: 90,
      recordCount: 2,
      trend: [{ date: '2026-10-07', standardMinutes: 90, recordCount: 2 }],
      employees: [{ employeeId: 17, employeeName: '张三', standardMinutes: 90, recordCount: 2 }]
    } as TaskEfficiencyOverview
    const trend = efficiencyChart(data, 'trend')
    const staff = efficiencyChart(data, 'employees')
    expect(trend.result.groups[0].values.standardHours).toBe('1.5')
    expect(staff.result.groups[0].keys).toEqual(['17'])
    expect(staff.result.totals.standardHours).toBe('1.5')
    expect(staff.config.metrics).toHaveLength(1)
  })
  it('统计接口复用客户端与稳定 POST 契约', async () => {
    const post = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const api = createTaskEfficiencyApi({ post } as unknown as NocodeHttpClient)
    const query = { from: '2026-10-01', to: '2026-10-07', employeeId: 17, pageNo: 2, pageSize: 10 }
    for (const [method, path] of [
      ['efficiencyOverview', 'overview'],
      ['efficiencyEmployees', 'employees'],
      ['efficiencyTasks', 'tasks'],
      ['efficiencyRecords', 'records'],
      ['efficiencyOptions', 'options']
    ] as const) {
      await api[method](query)
      expect(post).toHaveBeenLastCalledWith(`/nocode/tasks/efficiency/${path}`, query)
    }
  })
  it('趋势补齐整个查询期间的零值日期，跨月且保留原始汇总', () => {
    const data = {
      standardMinutes: 90,
      recordCount: 2,
      employeeCount: 0,
      completedNodeCount: 0,
      activeNodeCount: 0,
      overdueNodeCount: 0,
      employees: [],
      trend: [
        { date: '2026-09-30', standardMinutes: 30, recordCount: 1 },
        { date: '2026-10-02', standardMinutes: 60, recordCount: 1 }
      ]
    } satisfies TaskEfficiencyOverview
    const chart = efficiencyChart(data, 'trend', { from: '2026-09-29', to: '2026-10-03' })
    expect(chart.result.groups.map(row => row.keys[0])).toEqual([
      '2026-09-29',
      '2026-09-30',
      '2026-10-01',
      '2026-10-02',
      '2026-10-03'
    ])
    expect(chart.result.groups.map(row => row.values.standardHours)).toEqual(['0', '0.5', '0', '1', '0'])
    expect(chart.result.totals.standardHours).toBe('1.5')
  })
})
