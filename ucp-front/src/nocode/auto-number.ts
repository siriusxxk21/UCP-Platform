import type { AutoNumberOptions } from '@/types/nocode/data-center'

/** 仅以发布快照中的稳定字段身份判断；保存过但尚未发布的新字段仍可配置。 */
export function legacyAutoNumberFields(value: Record<string, unknown>): string[] {
  interface Table {
    fields?: { id: string; type: string }[]
    fieldOptions?: Record<string, { autoNumber?: unknown }>
  }
  const main = value as Table & { details?: Table[] }
  return [main, ...(main.details ?? [])].flatMap(table =>
    (table.fields ?? [])
      .filter(field => field.type === 'AUTO_NUMBER' && !table.fieldOptions?.[field.id]?.autoNumber)
      .map(field => field.id)
  )
}

export function defaultAutoNumber(): AutoNumberOptions {
  return { prefix: '', dateFormat: '', sequenceLength: 6, startValue: 1, resetCycle: 'NONE' }
}

/** 重置周期必须出现在编号中，避免跨周期生成相同编号。 */
export function autoNumberError(rule?: AutoNumberOptions | null): string | null {
  if (!rule) return null
  if (
    typeof rule.prefix !== 'string' ||
    rule.prefix.length > 40 ||
    [...rule.prefix].some(char => char.charCodeAt(0) < 32 || (char.charCodeAt(0) >= 127 && char.charCodeAt(0) <= 159))
  )
    return '编号前缀最多 40 字符，不能包含换行或控制字符'
  if (!['', 'yyyy', 'yyyyMM', 'yyyyMMdd'].includes(rule.dateFormat)) return '请选择有效的编号日期格式'
  if (!Number.isInteger(rule.sequenceLength) || rule.sequenceLength < 1 || rule.sequenceLength > 12)
    return '流水号位数须为 1–12 的整数'
  if (!Number.isSafeInteger(rule.startValue) || rule.startValue < 1 || rule.startValue > 999999999999)
    return '起始值须为 1–999999999999 的整数'
  const precision = { NONE: 0, YEAR: 4, MONTH: 6, DAY: 8 }[rule.resetCycle]
  if (precision === undefined) return '请选择有效的流水号重置周期'
  if (rule.dateFormat.length < precision) return '日期格式须包含重置周期：按日重置需年月日，按月需年月，按年需年份'
  return null
}

/** 格式示例使用北京时间和起始值，不读取或消耗真实流水号。 */
export function autoNumberPreview(rule: AutoNumberOptions, date = new Date(), offset = 0): string {
  if (autoNumberError(rule)) return '请先完善编号规则'
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(date)
  const part = (type: string) => parts.find(p => p.type === type)?.value ?? ''
  const stamp = `${part('year')}${part('month')}${part('day')}`.slice(0, rule.dateFormat.length)
  return `${rule.prefix}${stamp}${String(rule.startValue + offset).padStart(rule.sequenceLength, '0')}`
}
