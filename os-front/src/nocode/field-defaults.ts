import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { hyperlinkError } from './hyperlink'

export const multiDefaultTypes: readonly string[] = [
  FieldType.MULTI_SELECT,
  FieldType.IMAGE,
  FieldType.ATTACHMENT,
  FieldType.REGION,
  FieldType.CASCADE
]
export function decodeFieldDefault(field: ObjectField, raw?: string | null): unknown {
  if (raw == null || raw === '') return null
  if (multiDefaultTypes.includes(field.type) || field.type === FieldType.URL) {
    try {
      return JSON.parse(raw)
    } catch {
      return field.type === FieldType.URL ? { link: raw, text: '' } : []
    }
  }
  return raw
}
export function encodeFieldDefault(field: ObjectField, value: unknown): string | null {
  if (value == null || value === '' || (Array.isArray(value) && !value.length)) return null
  return multiDefaultTypes.includes(field.type) || field.type === FieldType.URL ? JSON.stringify(value) : String(value)
}
/** 元数据保存前检查已有或手工录入的值，精度仍交给服务端无损校验。 */
export function fieldDefaultError(field: ObjectField, option: FieldOptions): string | null {
  const value = option.defaultValue
  if (value == null || value === '') return null
  if (field.type === FieldType.INTEGER && !/^-?\d+$/.test(value)) return '默认值须为整数'
  if (
    [FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(type => type === field.type) &&
    !/^-?(?:\d+(?:\.\d*)?|\.\d+)$/.test(value)
  )
    return '默认值须为有效数字'
  if (field.type === FieldType.BOOLEAN && !['true', 'false'].includes(value)) return '默认值须为是或否'
  if (field.type === FieldType.DATE) {
    const date = new Date(value + 'T00:00:00Z')
    if (
      !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
      !Number.isFinite(date.getTime()) ||
      date.toISOString().slice(0, 10) !== value
    )
      return '请选择有效的默认日期'
  }
  if (field.type === FieldType.DATETIME) {
    const normalized = value.replace(' ', 'T'),
      zoned = option.nativeType?.includes('with time zone')
    const match = normalized.match(
      zoned
        ? /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$/
        : /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})$/
    )
    if (
      !match ||
      fieldDefaultError({ ...field, type: FieldType.DATE }, { ...option, defaultValue: match[1] }) ||
      fieldDefaultError({ ...field, type: FieldType.TIME }, { ...option, defaultValue: match[2] })
    )
      return '请选择有效的默认日期时间'
    if (zoned && !Number.isFinite(new Date(normalized).getTime())) return '日期时间的时区偏移无效'
  }
  if (field.type === FieldType.URL) {
    const error = hyperlinkError(decodeFieldDefault(field, value))
    if (error) return error
  }
  if (field.type === FieldType.TIME && !/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(value))
    return '请选择有效的默认时间'
  if (field.type === FieldType.UUID && !/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(value))
    return '默认值须为有效 UUID'
  if (multiDefaultTypes.includes(field.type)) {
    try {
      const items = JSON.parse(value)
      if (!Array.isArray(items) || items.some(v => typeof v !== 'string') || items.length > 100)
        return '默认值须为最多 100 项的有效选项或文件'
    } catch {
      return '默认值格式不正确，请重新选择'
    }
  }
  if (
    (!option.selection || option.selection.kind === 'LOCAL_OPTIONS') &&
    [FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.REGION, FieldType.CASCADE].some(type => type === field.type)
  ) {
    const values = multiDefaultTypes.includes(field.type) ? JSON.parse(value) : [value]
    if (values.some((id: string) => !option.options.some(item => item.code === id && !item.disabled)))
      return '默认选项已移除或停用，请重新选择'
  }
  return null
}
