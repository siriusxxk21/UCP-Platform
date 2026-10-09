import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType, MemberState } from '@/types/nocode/enums'

const numeric = new Set<string>([FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT])

/** 留存是明确的一次取值；目标必须允许尚未确认的空值。 */
export function captureTarget(field: ObjectField, options: FieldOptions | undefined, relation = false): boolean {
  return (
    !!field.id &&
    (numeric.has(field.type) || [FieldType.TEXT, FieldType.TEXTAREA].some(t => t === field.type)) &&
    !field.required &&
    !options?.generated &&
    !options?.primaryKey &&
    options?.defaultValue == null &&
    options?.state !== MemberState.INACTIVE &&
    !relation
  )
}

export function captureCompatible(
  source: ObjectField,
  options: FieldOptions | undefined,
  target: ObjectField
): boolean {
  if (source.type !== FieldType.FORMULA || options?.state === MemberState.INACTIVE) return false
  const type = options?.resultType
  if (!type) return false
  return (
    (numeric.has(type) &&
      numeric.has(target.type) &&
      !(type !== FieldType.INTEGER && target.type === FieldType.INTEGER)) ||
    (type === FieldType.TEXT && (target.type === FieldType.TEXT || target.type === FieldType.TEXTAREA))
  )
}

export function captureMappings(
  rows: Array<{ target: string; source: string }>,
  definition: Pick<PublishedDefinition, 'fields' | 'fieldOptions' | 'relations'>
): Record<string, string> {
  if (!rows.length || rows.length > 20) throw new Error('请配置 1 到 20 组留存字段')
  if (new Set(rows.map(row => row.target)).size !== rows.length) throw new Error('留存目标字段不能重复')
  for (const row of rows) {
    const target = definition.fields.find(field => field.id === row.target)
    const source = definition.fields.find(field => field.id === row.source)
    if (
      !target ||
      !captureTarget(
        target,
        definition.fieldOptions[row.target],
        definition.relations.some(r => r.fieldId === row.target)
      )
    )
      throw new Error('请选择可空、无默认值的普通留存字段')
    if (!source || !captureCompatible(source, definition.fieldOptions[row.source], target))
      throw new Error('请选择与目标类型兼容的来源公式')
  }
  return Object.fromEntries(rows.map(row => [row.target, row.source]))
}
