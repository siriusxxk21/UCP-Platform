import { v4 as uuid } from 'uuid'
import dayjs from 'dayjs'
import type { TaskCenterApi } from '@/api/nocode/task-center'
import type {
  TaskChecklistCommand,
  TaskChecklistContext,
  TaskChecklistItem,
  TaskChecklistChoice,
  TaskPlan
} from '@/types/nocode/task-center'

export const checklistPeriodLabel = (period: TaskChecklistChoice) =>
  ({ DAY: '今日计划', WEEK: '本周计划', NEXT_WEEK: '下周计划' })[period]
export const checklistPlans = (item: TaskChecklistItem, period: TaskChecklistChoice) =>
  period === 'DAY' ? item.todayPlans : period === 'NEXT_WEEK' ? item.nextWeekPlans || [] : item.weekPlans
/** 只展示后台已核准的继承来源，不根据前端树推断他人的个人计划。 */
export const isInheritedTaskPlan = (plan: TaskPlan) => plan.inherited === true || !!plan.inheritedFromTaskId
export function taskPlanSourceLabel(plan: TaskPlan) {
  if (isInheritedTaskPlan(plan)) return plan.inheritedFromTitle ? `随上级「${plan.inheritedFromTitle}」` : '随上级计划'
  return plan.source === 'MANAGER' ? '历史主管安排' : '自行加入'
}
export function taskPlanHistoryLabel(plan: TaskPlan) {
  const dates = plan.endDate && plan.endDate !== plan.date ? `${plan.date} 至 ${plan.endDate}` : plan.date
  const reason = plan.historyReason === 'TASK_DELETED' ? ' · 任务已删除' : ''
  return `${plan.mode === 'CHECKLIST' ? '过往清单' : '旧版区间安排'} · ${plan.period === 'DAY' ? '日' : plan.period === 'WEEK' ? '周' : '月'} · ${dates}${reason}`
}
/** 相对日期仅用于摘要文案；实际清单变更仍以服务端上下文锚点为准。 */
export function taskPlanSummaryLabel(plan: TaskPlan, today = dayjs().format('YYYY-MM-DD')) {
  if (plan.mode !== 'CHECKLIST') return taskPlanHistoryLabel(plan)
  const weekStart = dayjs(today)
    .subtract((dayjs(today).day() + 6) % 7, 'day')
    .format('YYYY-MM-DD')
  const current = plan.active !== false && plan.date === (plan.period === 'DAY' ? today : weekStart)
  const nextWeek = plan.active !== false && plan.date === dayjs(weekStart).add(7, 'day').format('YYYY-MM-DD')
  const label =
    plan.period === 'DAY' ? (current ? '今日计划' : '日计划') : current ? '本周计划' : nextWeek ? '下周计划' : '周计划'
  return `${label} · ${plan.date}`
}
/** 当前日周锚点来自服务端；只按真实成员身份移除，不推算或连带删除另一张清单。 */
export function checklistCommand(
  context: TaskChecklistContext,
  ids: string[],
  target: 'SELF' | 'ASSIGNEE',
  period: TaskChecklistChoice,
  action: 'ADD' | 'REMOVE',
  planIds?: string[]
): TaskChecklistCommand {
  const date = period === 'DAY' ? context.today : period === 'NEXT_WEEK' ? context.nextWeekStart : context.weekStart
  if (!date) throw new Error('下周计划上下文不可用，请刷新后重试')
  const unique = [...new Set(ids)]
  const items = unique.map(id => context.items.find(item => item.taskId === id))
  if (!unique.length || items.some(item => !item)) throw new Error('任务清单上下文不完整，请刷新后重试')
  if (action === 'ADD') {
    const blocked = items.find(item => !item?.canAdd)
    if (blocked) throw new Error(blocked.reason || `“${blocked.title}”当前不能加入计划`)
  } else {
    const permitted = items.flatMap(item =>
      item
        ? checklistPlans(item, period)
            .filter(plan => plan.canCancel && plan.id && !isInheritedTaskPlan(plan))
            .map(plan => plan.id)
        : []
    )
    if (!planIds?.length || planIds.some(id => !permitted.includes(id)))
      throw new Error('所选清单项当前不可移出，请刷新后重试')
  }
  return {
    ids: unique,
    target,
    period: period === 'DAY' ? 'DAY' : 'WEEK',
    action,
    date,
    ...(action === 'REMOVE' ? { planIds: [...new Set(planIds)] } : {}),
    expectedVersions: Object.fromEntries(items.flatMap(item => (item ? [[item.taskId, item.version]] : []))),
    requestKey: uuid()
  }
}
/** 结果未知时保留完整请求（含日期、版本、幂等键）；明确业务拒绝后才允许重新读取并提交。 */
export function createChecklistAttempt() {
  let pending: TaskChecklistCommand | null = null
  return {
    get pending() {
      return pending
    },
    async submit(
      api: Pick<TaskCenterApi, 'checklist'>,
      prepare: () => Promise<TaskChecklistCommand> | TaskChecklistCommand
    ) {
      if (!pending) pending = await prepare()
      try {
        const result = await api.checklist(pending)
        pending = null
        return result
      } catch (cause) {
        if (cause && typeof cause === 'object' && 'businessCode' in cause) pending = null
        throw cause
      }
    }
  }
}
