import dayjs from 'dayjs'
import type { TaskChecklistChoice, TaskRow, TaskUserId } from '@/types/nocode/task-center'

export type TaskSplitPlan = TaskChecklistChoice | 'LATER'
/** 默认不另写子任务计划；展示服务端已确认的上级有效成员关系。 */
export function taskSplitPlanSummary(
  parent: TaskRow,
  userId: TaskUserId | undefined,
  today = dayjs().format('YYYY-MM-DD')
): string {
  if (userId == null || String(parent.assigneeId) !== String(userId) || parent.detailVisible === false)
    return '自动随本人上级计划，无需重复安排'
  const weekStart = dayjs(today)
    .subtract((dayjs(today).day() + 6) % 7, 'day')
    .format('YYYY-MM-DD')
  const plans = parent.plans.filter(
    plan =>
      plan.mode === 'CHECKLIST' &&
      plan.active !== false &&
      (plan.userId == null || String(plan.userId) === String(userId))
  )
  const labels = [
    plans.some(plan => plan.period === 'DAY' && plan.date === today) ? '今日' : '',
    plans.some(plan => plan.period === 'WEEK' && plan.date === weekStart) ? '本周' : '',
    plans.some(plan => plan.period === 'WEEK' && plan.date === dayjs(weekStart).add(7, 'day').format('YYYY-MM-DD'))
      ? '下周'
      : ''
  ].filter(Boolean)
  return labels.length ? `随上级纳入${labels.join('、')}计划` : '自动随本人上级计划，无需重复安排'
}

/** 位置只使用已授权的名称摘要；不为显示路径额外请求整组详情。 */
export function taskSplitPath(parent: TaskRow, known: TaskRow[]): string {
  const nodes = new Map(known.map(node => [node.id, node]))
  const seen = new Set<string>([parent.id])
  const titles = [parent.title]
  let parentId = parent.parentId
  while (parentId && !seen.has(parentId)) {
    seen.add(parentId)
    const node = nodes.get(parentId) || parent.ancestorContext?.find(item => item.id === parentId)
    if (!node) break
    titles.unshift(node.title)
    parentId = node.parentId
  }
  return titles.join(' › ')
}
