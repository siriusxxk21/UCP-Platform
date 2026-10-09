import type { BusinessRow } from '@/types/nocode/runtime'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType } from '@/types/nocode/enums'
import { formatFinancialAmount } from './money-display'

/** 列表与统计明细使用相同空值、布尔、多选和服务端名称解析规则。 */
export function recordDisplay(
  row: BusinessRow,
  key: string,
  options?: FieldOptions,
  field?: Pick<ObjectField, 'type'>
): string {
  const value = row.values[key]
  if (
    field?.type === FieldType.MONEY ||
    ([FieldType.FORMULA, FieldType.SUMMARY].some(type => type === field?.type) &&
      options?.resultType === FieldType.MONEY)
  )
    return formatFinancialAmount(value)
  if (row.displayValues && key in row.displayValues) return row.displayValues[key] || '—'
  if (value == null || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  const label = (item: unknown) => options?.options?.find(option => option.code === item)?.label || String(item)
  if (Array.isArray(value)) return value.map(label).join('、') || '—'
  if (typeof value === 'object') {
    if ('link' in value && typeof value.link === 'string')
      return ('text' in value && typeof value.text === 'string' && value.text) || value.link
    return JSON.stringify(value)
  }
  return label(value)
}
