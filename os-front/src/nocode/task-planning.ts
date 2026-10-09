import dayjs from 'dayjs'
import type { TaskPeriod, TaskPlan, TaskPlanContextItem } from '@/types/nocode/task-center'

/** 日期区间只用于工作安排，不写回任务预计或实际起止时间。 */
export function taskPlanRange(period: TaskPeriod, date: string, endDate?: string) {
  const start = dayjs(date)
  if (period === 'WEEK') {
    const monday = start.subtract((start.day() + 6) % 7, 'day')
    return { date: monday.format('YYYY-MM-DD'), endDate: monday.add(6, 'day').format('YYYY-MM-DD') }
  }
  if (period === 'MONTH')
    return { date: start.startOf('month').format('YYYY-MM-DD'), endDate: start.endOf('month').format('YYYY-MM-DD') }
  return { date, endDate: endDate || date }
}
export function taskPlanLabel(plan: Pick<TaskPlan, 'period' | 'date' | 'endDate'>) {
  const range = taskPlanRange(plan.period, plan.date, plan.endDate)
  const dates = range.date === range.endDate ? range.date : `${range.date} 至 ${range.endDate}`
  return `${dates}${plan.period === 'DAY' ? '' : ' · 待细化到日期'}`
}
/** 仅改善交互；权限、主管约束与并发版本由服务端最终校验。 */
export function taskPlanInputError(period: TaskPeriod, date: string, endDate: string, items: TaskPlanContextItem[]) {
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date) || dayjs(date).format('YYYY-MM-DD') !== date)
    return '请选择有效的工作安排日期'
  if (period === 'DAY' && (!endDate || dayjs(endDate).format('YYYY-MM-DD') !== endDate)) return '请选择有效的结束日期'
  if (period === 'DAY' && endDate < date) return '结束日期不能早于开始日期'
  const range = taskPlanRange(period, date, endDate)
  for (const item of items) {
    if (!item.canArrange || item.readOnly) return item.reason || `“${item.title}”当前不能调整安排`
    for (const constraint of item.constraints) {
      const limit = taskPlanRange(constraint.period, constraint.date, constraint.endDate)
      if (range.date < limit.date || range.endDate > limit.endDate)
        return `“${item.title}”需安排在主管指定的 ${limit.date} 至 ${limit.endDate} 内`
      if (constraint.period === 'WEEK' && period !== 'DAY') return '主管已安排到周，请细化到具体日期'
      if (constraint.period === 'MONTH' && period === 'MONTH') return '主管已安排到月，请细化到周或具体日期'
    }
  }
  return ''
}
