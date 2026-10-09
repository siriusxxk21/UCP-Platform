import dayjs from 'dayjs'
import type {
  TaskCreate,
  TaskDetail,
  TaskDraft,
  TaskNodeInput,
  TaskRow,
  TaskSchedulePreview
} from '@/types/nocode/task-center'

/** 底座 LocalDateTime 使用毫秒时间戳；表单使用本地日期字符串，仅在任务协议边界转换。 */
export function taskDateToWire(value: string | null | undefined, endOfDay = false) {
  if (value == null || value === '') return null
  const date = dayjs(value)
  if (!date.isValid()) throw new Error('任务日期格式无效，请重新选择日期')
  // 日期截止覆盖选定整天；历史精确时间保持原值，避免仅改标题也改写计划。
  return (endOfDay && /^\d{4}-\d{2}-\d{2}$/.test(value) ? date.endOf('day') : date).valueOf()
}
export function taskDateFromWire(value: string | number | null | undefined) {
  if (value == null || value === '') return null
  const date = dayjs(value)
  return date.format(date.millisecond() ? 'YYYY-MM-DDTHH:mm:ss.SSS' : 'YYYY-MM-DDTHH:mm:ss')
}
export function taskNodeToWire(node: TaskNodeInput) {
  return {
    ...node,
    schedule: {
      ...node.schedule,
      fixedStart: taskDateToWire(node.schedule.fixedStart),
      ...(node.schedule.fixedEnd !== undefined ? { fixedEnd: taskDateToWire(node.schedule.fixedEnd, true) } : {})
    }
  }
}
export function taskNodeFromWire<T extends TaskNodeInput>(node: T): T {
  // 无详情权限的任务摘要会隐藏排期；保留空值，不能按完整配置解码或补造排期。
  if (node.schedule == null) return { ...node }
  return {
    ...node,
    schedule: {
      ...node.schedule,
      fixedStart: taskDateFromWire(node.schedule.fixedStart),
      ...(node.schedule.fixedEnd !== undefined ? { fixedEnd: taskDateFromWire(node.schedule.fixedEnd) } : {})
    }
  }
}
export function taskCreateToWire(content: TaskCreate) {
  return {
    ...content,
    ...(content.plannedStart !== undefined ? { plannedStart: taskDateToWire(content.plannedStart) } : {}),
    task: taskNodeToWire(content.task),
    nodes: content.nodes?.map(taskNodeToWire) || content.nodes
  }
}
export function taskRowFromWire(row: TaskRow): TaskRow {
  return {
    ...taskNodeFromWire(row),
    ...(row.plannedStart !== undefined ? { plannedStart: taskDateFromWire(row.plannedStart) } : {})
  }
}
export function taskSchedulePreviewFromWire(preview: TaskSchedulePreview): TaskSchedulePreview {
  return {
    ...preview,
    nodes: preview.nodes.map(node => ({
      ...node,
      expectedStart: taskDateFromWire(node.expectedStart),
      expectedEnd: taskDateFromWire(node.expectedEnd)
    }))
  }
}
export function taskDetailFromWire(detail: TaskDetail): TaskDetail {
  return {
    ...detail,
    task: taskRowFromWire(detail.task),
    nodes: detail.nodes.map(taskRowFromWire),
    ...(detail.preview
      ? {
          preview: {
            ...detail.preview,
            schedule: {
              ...detail.preview.schedule,
              fixedStart: taskDateFromWire(detail.preview.schedule.fixedStart),
              ...(detail.preview.schedule.fixedEnd !== undefined
                ? { fixedEnd: taskDateFromWire(detail.preview.schedule.fixedEnd) }
                : {})
            }
          }
        }
      : {})
  }
}
export function taskDraftFromWire(draft: TaskDraft): TaskDraft {
  return {
    ...draft,
    content: {
      ...draft.content,
      ...(draft.content.plannedStart !== undefined
        ? { plannedStart: taskDateFromWire(draft.content.plannedStart) }
        : {}),
      task: taskNodeFromWire(draft.content.task),
      nodes: draft.content.nodes?.map(taskNodeFromWire) || draft.content.nodes
    }
  }
}
