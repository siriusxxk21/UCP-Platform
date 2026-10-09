/**
 * 财务金额只接收服务端返回的十进制字符串，不经由浏览器 Number，避免大金额及小数精度丢失。
 * 展示口径：千位分隔、两位小数、绝对值四舍五入。币种由业务字段或展示上下文表达，金额字段本身不臆测币种。
 */
export function formatFinancialAmount(value: unknown): string {
  if (value == null || value === '') return '—'
  const raw = String(value)
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return raw

  const negative = raw.startsWith('-')
  const [integer, fraction = ''] = raw.replace(/^-/, '').split('.')
  let cents = BigInt(integer + (fraction + '00').slice(0, 2))
  if (fraction.length > 2 && fraction[2] >= '5') cents += 1n
  const normalized = cents.toString().padStart(3, '0')
  const whole = normalized.slice(0, -2).replace(/^0+(?=\d)/, '')
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const sign = negative && cents !== 0n ? '-' : ''
  return `${sign}${grouped}.${normalized.slice(-2)}`
}
