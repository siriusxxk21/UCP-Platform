import type { Dayjs } from 'dayjs'

export type DateRange = [Dayjs, Dayjs]

export const taskStatusOptions = [
  { label: '跳过', value: -2, color: 'default' },
  { label: '未开始', value: -1, color: 'default' },
  { label: '待审批', value: 0, color: 'processing' },
  { label: '审批中', value: 1, color: 'processing' },
  { label: '审批通过', value: 2, color: 'success' },
  { label: '审批不通过', value: 3, color: 'error' },
  { label: '已取消', value: 4, color: 'default' },
  { label: '已退回', value: 5, color: 'warning' },
  { label: '审批通过中', value: 7, color: 'processing' }
]

export function normalizePage<T>(page: { list?: T[]; records?: T[]; total?: number } | null | undefined) {
  const list = page?.list || page?.records || []
  return {
    list,
    total: page?.total || 0
  }
}

export function buildCreateTimeParam(range?: DateRange) {
  if (!range?.length) return undefined
  return [range[0].format('YYYY-MM-DD HH:mm:ss'), range[1].format('YYYY-MM-DD HH:mm:ss')]
}

export function formatSummary(summary?: Array<{ key: string; value: string }>) {
  if (!summary?.length) return '-'
  return summary.map(item => `${item.key} : ${item.value}`).join('\n')
}

export function formatDuration(milliseconds?: number) {
  if (!milliseconds || milliseconds < 0) return '-'

  const seconds = Math.floor(milliseconds / 1000)
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const restSeconds = seconds % 60
  const parts: string[] = []

  if (days) parts.push(`${days}天`)
  if (hours) parts.push(`${hours}小时`)
  if (minutes) parts.push(`${minutes}分钟`)
  if (!parts.length && restSeconds) parts.push(`${restSeconds}秒`)

  return parts.join('') || '0秒'
}

export function getTaskStatusMeta(status?: number) {
  return (
    taskStatusOptions.find(item => item.value === status) || {
      label: status === undefined || status === null ? '-' : String(status),
      color: 'default'
    }
  )
}
