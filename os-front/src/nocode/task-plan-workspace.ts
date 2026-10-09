import dayjs from 'dayjs'
import type { TaskQuery } from '@/types/nocode/task-center'

export type PlanWorkspaceView = 'ALL' | 'UNPLANNED' | 'TODAY' | 'WEEK' | 'NEXT_WEEK'
export const planWorkspaceViews: Array<{ value: PlanWorkspaceView; label: string }> = [
  { value: 'ALL', label: '全部任务' },
  { value: 'UNPLANNED', label: '未纳入计划' },
  { value: 'TODAY', label: '今日计划' },
  { value: 'WEEK', label: '本周计划' },
  { value: 'NEXT_WEEK', label: '下周计划' }
]
/** 查询日期可由本地导航产生；写入日期必须取服务端 checklistContext。 */
export function planWorkspaceQuery(
  view: PlanWorkspaceView,
  today = dayjs().format('YYYY-MM-DD')
): Pick<TaskQuery, 'tab' | 'date'> {
  return {
    tab: view === 'TODAY' ? 'TODAY' : view === 'WEEK' || view === 'NEXT_WEEK' ? 'WEEK' : 'ALL',
    date: view === 'NEXT_WEEK' ? dayjs(today).add(7, 'day').format('YYYY-MM-DD') : today
  }
}
