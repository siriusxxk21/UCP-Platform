import type { TaskNodeInput, TaskRow } from '@/types/nocode/task-center'
import { taskDate } from './task-center'

export interface TaskScheduleSummary {
  primary: string
  secondary?: string
  hint?: string
}

/** 仅格式化已有日期，不在前端补算缺失的预计开始或结束。 */
export function taskScheduleDateRange(start?: string | null, end?: string | null): string {
  if (start && end)
    return taskDate(start) === taskDate(end) ? taskDate(start) : `${taskDate(start)} 至 ${taskDate(end)}`
  if (start) return `开始 ${taskDate(start)}`
  if (end) return `截止 ${taskDate(end)}`
  return ''
}

/** 实例详情优先解释服务端已确定的计划日期，不用配置或实际执行时间补算缺失日期。 */
export function taskInstanceScheduleSummary(
  task: Pick<TaskRow, 'schedule' | 'expectedStart' | 'expectedEnd'> & Partial<Pick<TaskRow, 'childCount'>>
): string {
  const { schedule, expectedStart, expectedEnd } = task
  let dates = ''
  if (expectedStart && expectedEnd) {
    const start = taskDate(expectedStart)
    const end = taskDate(expectedEnd)
    dates = start === end ? `计划 ${start} 当天开始并完成` : `计划 ${start} 开始，${end} 完成`
  } else if (expectedStart) {
    dates = `计划 ${taskDate(expectedStart)} 开始，完成日期待定`
  } else if (expectedEnd) {
    dates = `开始日期待定，计划 ${taskDate(expectedEnd)} 完成`
  }
  if (schedule.mode === 'UNSCHEDULED') return dates || '暂不安排'
  if (schedule.mode === 'FIXED') return dates || '计划日期待定'
  if (schedule.mode === 'AUTO' && task.childCount) return dates ? `${dates} · 按下级汇总` : '按下级汇总'

  const duration =
    Number.isFinite(schedule.durationDays) && schedule.durationDays >= 0 ? ` · 工期 ${schedule.durationDays} 天` : ''
  if (dates) return dates + duration
  if (!Number.isFinite(schedule.offsetDays)) return '计划日期待定' + duration

  const sameDay = {
    AUTO: '跟随任务顺序',
    PLAN_START: '跟随整组计划开始',
    PREDECESSOR: '前序任务最晚完成后开始',
    T0: '任务创建当天开始'
  }
  if (schedule.offsetDays === 0) return sameDay[schedule.mode] + duration
  const source = {
    AUTO: '任务顺序确定的起点',
    PLAN_START: '整组计划开始',
    PREDECESSOR: '前序任务最晚完成',
    T0: '任务创建'
  }
  const start =
    schedule.offsetDays > 0
      ? `${source[schedule.mode]} ${schedule.offsetDays} 天后开始`
      : `比${schedule.mode === 'PLAN_START' ? '整组计划' : source[schedule.mode]}提前 ${Math.abs(schedule.offsetDays)} 天开始`
  return start + duration
}

function relativeDay(days: number, sameDay: string): string {
  // 数字框允许用户清空草稿；空值和无效值不能被当成已明确填写的零天。
  if (!Number.isFinite(days)) return '（偏移待填写）'
  if (!days) return sameDay
  return days < 0 ? `前 ${Math.abs(days)} 天` : `后 ${days} 天`
}

function waitsForPredecessors(node: TaskNodeInput, nodes: readonly TaskNodeInput[]): boolean {
  const lookup = new Map(nodes.map(item => [item.id, item]))
  const visited = new Set<string>()
  let current: TaskNodeInput | undefined = node
  while (current && !visited.has(current.id)) {
    visited.add(current.id)
    if (current.predecessorIds.length) return true
    current = current.parentId ? lookup.get(current.parentId) : undefined
  }
  return false
}

/** 表格与图卡共享配置摘要；日期基准与执行依赖分开表达，不改变保存值或复制排期引擎。 */
export function taskScheduleSummary(
  node: TaskNodeInput,
  nodes: readonly TaskNodeInput[],
  plannedStart?: string | null
): TaskScheduleSummary {
  const schedule = node.schedule
  const secondary = Number.isFinite(schedule.durationDays) ? `预计用时 ${schedule.durationDays} 天` : '工期待填写'
  const waits = waitsForPredecessors(node, nodes)
  switch (schedule.mode) {
    case 'AUTO': {
      if (nodes.some(item => item.parentId === node.id))
        return {
          primary: '按下级汇总',
          secondary: '下级日期自动汇总',
          hint: '按下级任务的最早开始和最晚完成汇总，不单独计算父任务的工期与间隔。'
        }
      const duration = Number.isFinite(schedule.durationDays) ? `工期 ${schedule.durationDays} 天` : '工期待填写'
      const gap = !Number.isFinite(schedule.offsetDays)
        ? '间隔待填写'
        : schedule.offsetDays
          ? `间隔 ${schedule.offsetDays} 天`
          : ''
      const source = waits
        ? '跟随当前任务及上级前序任务的最晚完成日期；已完成取实际日期，未完成取预计日期。'
        : `跟随整体计划开始${plannedStart ? `日 ${taskDate(plannedStart)}` : '日'}，并行任务同日开始。`
      return {
        primary: '跟随任务顺序',
        secondary: [duration, gap].filter(Boolean).join(' · '),
        hint: `${source}由服务端统一排期，不代表自动开始执行。`
      }
    }
    case 'UNSCHEDULED':
      return { primary: '暂不安排', hint: '不设置预计日期，不影响任务的先后顺序。' }
    case 'FIXED':
      return {
        primary: taskScheduleDateRange(schedule.fixedStart, schedule.fixedEnd) || '指定日期（待填写）',
        hint: waits ? '预计日期固定；执行仍需等待前序任务完成。' : '按指定日期安排，不自动开始任务。'
      }
    case 'PLAN_START': {
      const start = plannedStart ? `整体计划开始日：${taskDate(plannedStart)}` : '整体计划开始日待确定'
      return {
        primary: `整体计划开始${relativeDay(schedule.offsetDays, '当天')}`,
        secondary,
        hint: waits
          ? `执行仍需等待前序完成；预计日期按整体计划开始日计算。${start}。`
          : `${start}；不代表任务的实际开始时间。`
      }
    }
    case 'T0':
      return {
        primary: `创建任务${relativeDay(schedule.offsetDays, '当天')}`,
        secondary,
        hint: '历史规则：以任务实际创建时间为起点；不使用整体计划开始日。'
      }
    case 'PREDECESSOR': {
      const predecessors = [...new Set(node.predecessorIds)].map(
        id => nodes.find(item => item.id === id)?.title.trim() || '未载入的前序任务'
      )
      const source =
        predecessors.length > 1 ? `${predecessors.length} 项前序任务最晚完成` : `${predecessors[0] || '前序任务'}完成`
      const names = predecessors.length > 1 ? `前序任务：${predecessors.join('、')}。` : ''
      return {
        primary: `${source}${relativeDay(schedule.offsetDays, '后')}`,
        secondary,
        hint: predecessors.length
          ? `${names}以前序任务最晚完成日期接续；未完成取预计日期，已完成取实际日期，仍需负责人点击开始。`
          : '尚未设置前序任务，暂无法按完成时间排期。'
      }
    }
  }
}
