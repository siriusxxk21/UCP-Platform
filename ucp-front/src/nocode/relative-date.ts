import dayjs, { type Dayjs } from 'dayjs'
import { FieldType } from '@/types/nocode/enums'

/**
 * 条件里的相对日期（2026-10-03，与后端 RelativeDateEnum / RelativeDates 同一张表）。
 * 条件值存 { relative: 编码 } 或 { relative: 'PAST_N_DAYS', n: 7 }，每次执行时由服务端按当天（Asia/Shanghai）换算，不存换算后的日期。
 * 区间左闭右开；周从周一开始；「过去 / 未来 N 天」含今天、共 N 天。
 */
export const RELATIVE_DATE_CODES = [
  'TODAY',
  'YESTERDAY',
  'TOMORROW',
  'THIS_WEEK',
  'LAST_WEEK',
  'NEXT_WEEK',
  'THIS_MONTH',
  'LAST_MONTH',
  'NEXT_MONTH',
  'THIS_YEAR',
  'LAST_YEAR',
  'PAST_N_DAYS',
  'NEXT_N_DAYS'
] as const
export type RelativeDateCode = (typeof RELATIVE_DATE_CODES)[number]
export interface RelativeDateValue {
  relative: RelativeDateCode
  n?: number
}

export const RELATIVE_DATE_LABELS: Record<RelativeDateCode, string> = {
  TODAY: '今天',
  YESTERDAY: '昨天',
  TOMORROW: '明天',
  THIS_WEEK: '本周',
  LAST_WEEK: '上周',
  NEXT_WEEK: '下周',
  THIS_MONTH: '本月',
  LAST_MONTH: '上月',
  NEXT_MONTH: '下月',
  THIS_YEAR: '本年',
  LAST_YEAR: '去年',
  PAST_N_DAYS: '过去 N 天',
  NEXT_N_DAYS: '未来 N 天'
}
export const COUNTED_CODES: readonly RelativeDateCode[] = ['PAST_N_DAYS', 'NEXT_N_DAYS']
export const MAX_RELATIVE_DAYS = 3650

/** 下拉里的选项：常用的「过去 7 天 / 过去 30 天（最近一个月）/ 未来 7 天 / 未来 30 天」直接给出，也可选「过去 N 天」自己填天数。 */
export const RELATIVE_DATE_OPTIONS: { value: string; label: string }[] = [
  ...RELATIVE_DATE_CODES.filter(code => !COUNTED_CODES.includes(code)).map(code => ({
    value: code,
    label: RELATIVE_DATE_LABELS[code]
  })),
  { value: 'PAST_N_DAYS:7', label: '过去 7 天（含今天）' },
  { value: 'PAST_N_DAYS:30', label: '过去 30 天（最近一个月，含今天）' },
  { value: 'NEXT_N_DAYS:7', label: '未来 7 天（含今天）' },
  { value: 'NEXT_N_DAYS:30', label: '未来 30 天（含今天）' },
  { value: 'PAST_N_DAYS', label: '过去 N 天（含今天，自填天数）' },
  { value: 'NEXT_N_DAYS', label: '未来 N 天（含今天，自填天数）' }
]

/**
 * 筛选里最常用的几项（业务方 2026-10-03：「日期是筛选的是当天是当月」），在取值处直接列成按钮，一点即选，不必先切换再下拉。
 */
export const RELATIVE_DATE_QUICK: readonly RelativeDateCode[] = ['TODAY', 'THIS_WEEK', 'THIS_MONTH']
export const isQuickPicked = (value: unknown, code: RelativeDateCode) =>
  isRelativeDate(value) && value.relative === code

/** 能与相对日期组合的比较方式（与后端 RelativeDates.OPERATORS 一致）；属于任意一个、包含、为空等不能。 */
export const RELATIVE_DATE_OPERATORS: readonly string[] = ['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'between']

export function isRelativeDate(value: unknown): value is RelativeDateValue {
  return !!value && typeof value === 'object' && !Array.isArray(value) && 'relative' in value
}
export const relativeDateField = (type?: string | null) => type === FieldType.DATE || type === FieldType.DATETIME
export const relativeDateOperator = (operator?: string | null) =>
  !!operator && RELATIVE_DATE_OPERATORS.includes(operator)

export function relativeDateValue(code: RelativeDateCode, n?: number): RelativeDateValue {
  return COUNTED_CODES.includes(code) ? { relative: code, n: n ?? 7 } : { relative: code }
}

/** 下拉选项值 → 存储形状（'PAST_N_DAYS:7' 带天数；'PAST_N_DAYS' 保留已填的天数）。 */
export function relativeDateFromOption(option: string, previous?: unknown): RelativeDateValue {
  const [code, days] = option.split(':') as [RelativeDateCode, string | undefined]
  if (days) return relativeDateValue(code, Number(days))
  return relativeDateValue(code, isRelativeDate(previous) ? previous.n : undefined)
}
/** 存储形状 → 下拉选项值：命中预设的天数就选中预设，否则选「自填天数」。 */
export function relativeDateOption(value: RelativeDateValue): string {
  if (!COUNTED_CODES.includes(value.relative)) return value.relative
  const preset = `${value.relative}:${value.n}`
  return RELATIVE_DATE_OPTIONS.some(o => o.value === preset) ? preset : value.relative
}

/** 中文描述，用于条件摘要：「本月」「过去 7 天」。 */
export function describeRelativeDate(value: unknown): string {
  if (!isRelativeDate(value)) return ''
  const label = RELATIVE_DATE_LABELS[value.relative] ?? String(value.relative)
  return COUNTED_CODES.includes(value.relative) ? label.replace('N', String(value.n ?? '?')) : label
}

/** 校验：编码已知、天数为 1–3650 的整数。返回错误文案，合法返回 null。 */
export function relativeDateError(value: unknown): string | null {
  if (!isRelativeDate(value)) return '相对日期格式无效'
  if (!RELATIVE_DATE_CODES.includes(value.relative)) return `相对日期「${String(value.relative)}」无效`
  if (COUNTED_CODES.includes(value.relative)) {
    const n = value.n
    if (typeof n !== 'number' || !Number.isInteger(n) || n < 1 || n > MAX_RELATIVE_DAYS)
      return `「${RELATIVE_DATE_LABELS[value.relative]}」的天数应为 1–${MAX_RELATIVE_DAYS} 的整数`
  } else if ('n' in value && value.n != null) return `「${RELATIVE_DATE_LABELS[value.relative]}」不需要天数`
  return null
}

/** 各「结果会存下来」入口不给选相对日期时的说明（界面置灰处显示）。 */
export const RELATIVE_BLOCKED = {
  maintain:
    '持续维护不支持相对日期：维护结果写进记录、只在来源记录变化时重算，过了零点不会自己变。按当天看数请在视图或统计视图的条件里用「今天」「本月」等。',
  linkage: '数据联动不支持相对日期：联动取到的值会写进字段，过了零点不会自己变。',
  onSave: '「保存时计算」不支持相对日期：保存时的结果是快照，过了零点不会自己变；需要按当天计算请改为「读取时计算」。'
} as const

/* ── 运行端日期区间快捷项（页面筛选器、条件搜索「在区间内」）：点了直接填具体起止，按 Asia/Shanghai 的今天算，与服务端同一口径 ── */

/** Asia/Shanghai 的今天（YYYY-MM-DD）。上海没有夏令时，固定 +08:00。 */
export function shanghaiToday(now: number = Date.now()): string {
  return new Date(now + 8 * 3600_000).toISOString().slice(0, 10)
}
function addDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}
function addMonths(day: string, n: number): string {
  const d = new Date(`${day.slice(0, 8)}01T00:00:00Z`)
  d.setUTCMonth(d.getUTCMonth() + n)
  return d.toISOString().slice(0, 10)
}
/** 相对日期 → 具体起止 [起, 止]（都含）；与后端 RelativeDates.range 同一张表（后端是左闭右开，这里止 = 右端前一天）。 */
export function relativeDateRange(value: RelativeDateValue, today: string): [string, string] {
  const weekday = (new Date(`${today}T00:00:00Z`).getUTCDay() + 6) % 7 // 周一 = 0
  const monday = addDays(today, -weekday)
  const month = `${today.slice(0, 8)}01`
  const year = `${today.slice(0, 4)}-01-01`
  const span = (start: string, endExclusive: string): [string, string] => [start, addDays(endExclusive, -1)]
  const n = value.n ?? 1
  switch (value.relative) {
    case 'TODAY':
      return [today, today]
    case 'YESTERDAY':
      return span(addDays(today, -1), today)
    case 'TOMORROW':
      return span(addDays(today, 1), addDays(today, 2))
    case 'THIS_WEEK':
      return span(monday, addDays(monday, 7))
    case 'LAST_WEEK':
      return span(addDays(monday, -7), monday)
    case 'NEXT_WEEK':
      return span(addDays(monday, 7), addDays(monday, 14))
    case 'THIS_MONTH':
      return span(month, addMonths(month, 1))
    case 'LAST_MONTH':
      return span(addMonths(month, -1), month)
    case 'NEXT_MONTH':
      return span(addMonths(month, 1), addMonths(month, 2))
    case 'THIS_YEAR':
      return span(year, `${Number(today.slice(0, 4)) + 1}-01-01`)
    case 'LAST_YEAR':
      return span(`${Number(today.slice(0, 4)) - 1}-01-01`, year)
    case 'PAST_N_DAYS':
      return [addDays(today, -(n - 1)), today]
    case 'NEXT_N_DAYS':
      return [today, addDays(today, n - 1)]
  }
}

/** 业务方 2026-10-03 裁定的区间快捷项（顺序即显示顺序）。 */
export const RANGE_PRESETS: { label: string; value: RelativeDateValue }[] = [
  { label: '今天', value: { relative: 'TODAY' } },
  { label: '昨天', value: { relative: 'YESTERDAY' } },
  { label: '本周', value: { relative: 'THIS_WEEK' } },
  { label: '上周', value: { relative: 'LAST_WEEK' } },
  { label: '本月', value: { relative: 'THIS_MONTH' } },
  { label: '上月', value: { relative: 'LAST_MONTH' } },
  { label: '本年', value: { relative: 'THIS_YEAR' } },
  { label: '过去 7 天', value: { relative: 'PAST_N_DAYS', n: 7 } },
  { label: '过去 30 天', value: { relative: 'PAST_N_DAYS', n: 30 } },
  { label: '未来 7 天', value: { relative: 'NEXT_N_DAYS', n: 7 } }
]
/** 单个日期选择器的快捷项（只有落在一天上的才有意义）。 */
export const DAY_PRESETS: { label: string; value: RelativeDateValue }[] = [
  { label: '今天', value: { relative: 'TODAY' } },
  { label: '昨天', value: { relative: 'YESTERDAY' } }
]

/** antd 区间选择器的 presets：点了直接填具体起止（日期时间字段起 00:00:00、止 23:59:59）。 */
export function rangePickerPresets(today: string = shanghaiToday(), datetime = false) {
  return RANGE_PRESETS.map(p => {
    const [start, end] = relativeDateRange(p.value, today)
    return {
      label: p.label,
      value: [dayjs(datetime ? `${start}T00:00:00` : start), dayjs(datetime ? `${end}T23:59:59` : end)] as [
        Dayjs,
        Dayjs
      ]
    }
  })
}
/** antd 单日选择器的 presets。 */
export function datePickerPresets(today: string = shanghaiToday()) {
  return DAY_PRESETS.map(p => ({ label: p.label, value: dayjs(relativeDateRange(p.value, today)[0]) }))
}
