import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { FieldType } from '@/types/nocode/enums'

// 数值保持十进制字符串，不经 Number 转换，避免金额和 64 位整数丢失精度。
function decimal(raw: string) {
  const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(raw)
  if (!match) return null
  const exponent = Number(match[5] || 0)
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 100000) return null
  const fraction = match[3] || match[4] || ''
  let digits = ((match[2] || '') + fraction).replace(/^0+/, '')
  let scale = fraction.length - exponent
  if (!digits) return { sign: 0, digits: '0', scale: 0 }
  const trimmed = digits.replace(/0+$/, '')
  scale -= digits.length - trimmed.length
  digits = trimmed
  return { sign: match[1] === '-' ? -1 : 1, digits, scale }
}
type Decimal = NonNullable<ReturnType<typeof decimal>>
/** 条件比较沿用录入的十进制精度，不能把大整数和金额转成浮点数。 */
export function compareRecordNumbers(a: unknown, b: unknown): number | null {
  const left = decimal(String(a)),
    right = decimal(String(b))
  return left && right ? compare(left, right) : null
}
function compare(left: Decimal, right: Decimal) {
  if (left.sign !== right.sign) return Math.sign(left.sign - right.sign)
  if (!left.sign) return 0
  const magnitude = left.digits.length - left.scale - (right.digits.length - right.scale)
  if (magnitude) return Math.sign(magnitude) * left.sign
  const length = Math.max(left.digits.length, right.digits.length)
  const a = left.digits.padEnd(length, '0'),
    b = right.digits.padEnd(length, '0')
  return (a === b ? 0 : a < b ? -1 : 1) * left.sign
}

/** 与对象写入的整数范围、DECIMAL 精度及上下限保持一致；空值交给必填规则。 */
export function recordNumberError(
  field: ObjectField,
  options: Pick<FieldOptions, 'minimum' | 'maximum'> | undefined,
  value: unknown
): string | null {
  if (value == null || value === '') return null
  if (![FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(t => t === field.type))
    return null
  const raw = String(value),
    parsed = decimal(raw)
  if (!parsed || (field.type === FieldType.INTEGER && !/^[+-]?\d+$/.test(raw)))
    return `${field.name}必须是${field.type === FieldType.INTEGER ? '整数' : '有效数值'}`
  if (field.type === FieldType.INTEGER) {
    if (compare(parsed, decimal('-9223372036854775808')!) < 0 || compare(parsed, decimal('9223372036854775807')!) > 0)
      return `${field.name}超出 64 位整数范围`
  } else {
    if (field.scale != null && parsed.scale > field.scale) return `${field.name}最多保留 ${field.scale} 位小数`
    const precision = parsed.sign ? parsed.digits.length + Math.max(0, (field.scale ?? parsed.scale) - parsed.scale) : 1
    if (field.precision != null && precision > field.precision)
      return `${field.name}超出对象定义的 ${field.precision} 位数值精度`
  }
  for (const [bound, lower] of [
    [options?.minimum, true],
    [options?.maximum, false]
  ] as const) {
    const limit = bound != null ? decimal(bound) : null
    if (limit && (lower ? compare(parsed, limit) < 0 : compare(parsed, limit) > 0))
      return `${field.name}不能${lower ? '小于' : '大于'} ${bound}`
  }
  return null
}
