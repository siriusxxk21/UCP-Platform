/**
 * 统一日期工具函数
 *
 * 封装 dayjs，提供一致的日期解析与格式化能力：
 * - 后端返回的 "yyyy-MM-dd" 字符串按本地时区解析（避免 new Date(string) 跨浏览器差异）
 * - 前端提交时统一格式化为 "YYYY-MM-DD" 字符串
 * - 所有日期展示经过统一入口，方便未来替换或扩展
 */
import dayjs from 'dayjs'

/**
 * 将后端返回的日期字符串（"yyyy-MM-dd"）解析为 dayjs 对象
 * 解析时按本地时区处理，确保"2026-06-29"在任何浏览器/时区下都代表同一天
 *
 * @param val 后端返回的日期字符串，如 "2026-06-29"
 * @returns dayjs 对象或 undefined（当值无效时）
 */
export function parseDateStr(val: string | undefined | null): dayjs.Dayjs | undefined {
  if (!val) return undefined
  // 明确指定格式避免跨浏览器 ISO 8601 解析歧义：
  // 部分环境将 "2026-06-30" 视为 UTC 午夜，在 UTC+8 下可能得到前一天
  const d = dayjs(val, 'YYYY-MM-DD')
  return d.isValid() ? d : undefined
}

/**
 * 将 dayjs 对象或字符串格式化为后端需要的 "yyyy-MM-dd" 字符串
 *
 * @param val dayjs 对象或日期字符串
 * @returns 格式化后的日期字符串，如 "2026-06-29"
 */
export function toDateStr(val: dayjs.Dayjs | string | undefined | null): string {
  if (!val) return ''
  if (typeof val === 'string') {
    // 明确指定格式，避免跨浏览器 ISO 解析歧义
    const d = dayjs(val, 'YYYY-MM-DD')
    return d.isValid() ? d.format('YYYY-MM-DD') : val
  }
  return val.isValid() ? val.format('YYYY-MM-DD') : ''
}

/**
 * 将任意日期值安全格式化为展示字符串
 * 支持 Date 对象、dayjs 对象、日期字符串、数字时间戳
 *
 * @param val 日期值
 * @returns 展示用的日期字符串，无效值返回空字符串
 */
export function formatDisplay(val: any): string {
  if (!val) return ''
  // 字符串输入时明确指定格式，避免跨浏览器 ISO 解析歧义
  const d = typeof val === 'string' ? dayjs(val, 'YYYY-MM-DD') : dayjs(val)
  return d.isValid() ? d.format('YYYY-MM-DD') : ''
}

/**
 * 将任意日期值安全格式化为日期时间展示字符串
 *
 * @param val 日期值
 * @returns 展示用的日期时间字符串
 */
export function formatDisplayDatetime(val: any): string {
  if (!val) return ''
  const d = dayjs(val)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : ''
}

/**
 * 计算两个 dayjs 对象之间的天数差（绝对值）
 *
 * @param start 开始日期
 * @param end 结束日期
 * @returns 天数差
 */
export function calcDaysDiff(start: dayjs.Dayjs, end: dayjs.Dayjs): number {
  return Math.abs(end.startOf('day').diff(start.startOf('day'), 'day'))
}

/**
 * 计算两个日期之间相隔的天数（end 减 start，可正可负）
 *
 * @param start 开始日期字符串
 * @param end 结束日期字符串
 * @returns 相隔天数
 */
export function diffDays(start: string, end: string): number {
  return dayjs(end).startOf('day').diff(dayjs(start).startOf('day'), 'day')
}

/**
 * 获取今天日期（dayjs 对象）
 */
export function today(): dayjs.Dayjs {
  return dayjs().startOf('day')
}

/**
 * 将 Date 对象安全转为 dayjs（避免 new Date(string) 跨浏览器差异）
 */
export function dateToDayjs(d: Date): dayjs.Dayjs {
  return dayjs(d)
}

/**
 * 校验一对开始/结束日期，结束时间不能早于开始时间。
 * 支持 dayjs 对象、Date 对象、字符串、null、undefined。
 *
 * @returns 错误消息（校验通过返回 null）
 */
export function validateDateRange(
  start: dayjs.Dayjs | Date | string | null | undefined,
  end: dayjs.Dayjs | Date | string | null | undefined,
  startLabel: string,
  endLabel: string
): string | null {
  if (!start || !end) return null
  const startDate = dayjs.isDayjs(start) ? start : dayjs(start)
  const endDate = dayjs.isDayjs(end) ? end : dayjs(end)
  if (!startDate.isValid() || !endDate.isValid()) return null
  if (endDate.isBefore(startDate, 'day')) {
    return `「${endLabel}」不能早于「${startLabel}」`
  }
  return null
}
