import dayjs from 'dayjs'
import type { ReportConfig, ReportResult } from '@/types/nocode/report'
import type { TaskEfficiencyOverview, TaskEfficiencyQuery } from '@/types/nocode/task-efficiency'

export function efficiencyPeriodError(from: string, to: string): string {
  if (
    ![from, to].every(
      value =>
        /^\d{4}-\d{2}-\d{2}$/.test(value || '') && dayjs(value).isValid() && dayjs(value).format('YYYY-MM-DD') === value
    )
  )
    return '请选择完整的统计日期'
  const days = dayjs(to).diff(dayjs(from), 'day')
  if (days < 0) return '结束日期不能早于开始日期'
  return days > 365 ? '一次最多查询 366 天，请缩小日期范围' : ''
}

/** 汇总分钟可为小数且可超出模板输入上限；未设置与真实零值分别展示。 */
export function efficiencyDuration(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const rounded = Math.round(value * 100) / 100
  const hours = Math.floor(rounded / 60)
  const minutes = Math.round((rounded - hours * 60) * 100) / 100
  return [hours ? `${hours.toLocaleString('zh-CN')} 小时` : '', minutes || !hours ? `${minutes} 分钟` : '']
    .filter(Boolean)
    .join(' ')
}

export function defaultEfficiencyQuery(): TaskEfficiencyQuery {
  return { from: dayjs().startOf('month').format('YYYY-MM-DD'), to: dayjs().format('YYYY-MM-DD') }
}

/** 复用报表图表渲染器；仅做显示单位换算，不在前端重新核算工时。 */
export function efficiencyChart(
  overview: TaskEfficiencyOverview,
  type: 'trend' | 'employees',
  period?: Pick<TaskEfficiencyQuery, 'from' | 'to'>
) {
  const metric = {
    id: 'standardHours',
    name: '标准工时',
    operation: 'SUM' as const,
    fieldId: null,
    format: { unit: ' 小时', decimals: 2 }
  }
  const config: ReportConfig = {
    objectId: 'task-efficiency',
    display: type === 'trend' ? 'LINE' : 'BAR',
    dimensions: [{ fieldId: type, relationPath: null, bucket: 'VALUE' }],
    metrics: [metric],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Shanghai',
    sortMetricId: null,
    descending: false,
    limit: null,
    detailViewId: null,
    chart: { barMode: 'GROUPED', horizontal: type === 'employees', labels: type === 'employees', legendPosition: 'TOP' }
  }
  // 分类轴需要连续日期，否则没有办理的日期会被吞掉，造成趋势持续增长的错觉。
  let trend = overview.trend
  if (type === 'trend' && period && !efficiencyPeriodError(period.from, period.to)) {
    const dates = new Map(trend.map(row => [row.date, row]))
    trend = Array.from({ length: dayjs(period.to).diff(dayjs(period.from), 'day') + 1 }, (_, index) => {
      const date = dayjs(period.from).add(index, 'day').format('YYYY-MM-DD')
      return dates.get(date) || { date, standardMinutes: 0, recordCount: 0 }
    })
  }
  const employees = overview.employees.slice(0, 8)
  const groups =
    type === 'trend'
      ? trend.map(row => ({
          keys: [row.date],
          labels: [row.date],
          values: { standardHours: String(row.standardMinutes / 60) }
        }))
      : employees.map(row => ({
          keys: [String(row.employeeId)],
          labels: [row.employeeName || '未留存姓名'],
          values: { standardHours: String(row.standardMinutes / 60) }
        }))
  const result: ReportResult = {
    dimensionNames: [type === 'trend' ? '计量日期' : '员工'],
    metrics: [metric],
    groups,
    totals: {
      standardHours: String(
        (type === 'trend'
          ? overview.standardMinutes
          : employees.reduce((total, row) => total + row.standardMinutes, 0)) / 60
      )
    },
    totalGroups: groups.length,
    recordCount: overview.recordCount,
    canExport: false,
    timeZone: 'Asia/Shanghai'
  }
  return { config, result }
}
