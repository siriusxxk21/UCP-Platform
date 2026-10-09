import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { DataScope } from '@/types/nocode/data-scope'
import { isRelativeDate, relativeDateError, relativeDateField, relativeDateOperator } from './relative-date'
export function scopeFields(fields: ObjectField[]) {
  return fields.filter(
    f =>
      !f.id?.startsWith('relation_') &&
      ![
        FieldType.FORMULA,
        FieldType.SUMMARY,
        FieldType.URL,
        FieldType.IMAGE,
        FieldType.ATTACHMENT,
        FieldType.REGION,
        FieldType.CASCADE,
        FieldType.RICH_TEXT
      ].some(t => t === f.type)
  )
}
export function emptyScope(): DataScope {
  return { logic: 'AND', conditions: [], groups: [] }
}
export function validateScope(scope: DataScope, fields: ObjectField[], depth = 0) {
  if (depth > 4 || (!scope.conditions.length && !scope.groups.length)) throw new Error('范围至少配置一个条件，最多四层')
  for (const condition of scope.conditions) {
    const field = scopeFields(fields).find(f => f.id === condition.fieldId)
    if (!field) throw new Error('请选择有效的范围字段')
    if (condition.valueSource && condition.valueSource !== 'CONSTANT') {
      condition.value = null
      continue
    }
    if (['isNull', 'notNull'].includes(condition.operator)) {
      condition.value = null
      continue
    }
    // 相对日期（今天、本月、过去 N 天……）：只用于日期字段与可组合的比较方式，由服务端每次执行时按当天换算。
    if (isRelativeDate(condition.value)) {
      if (!relativeDateField(field.type)) throw new Error(`相对日期只能用于日期或日期时间字段：${field.name}`)
      if (!relativeDateOperator(condition.operator)) throw new Error(`「${field.name}」的这种比较方式不能使用相对日期`)
      const error = relativeDateError(condition.value)
      if (error) throw new Error(error)
      continue
    }
    const many =
      ['in', 'containsAny', 'containsAll'].includes(condition.operator) || field.type === FieldType.MULTI_SELECT
    if (many && !Array.isArray(condition.value)) throw new Error('范围条件需要多个值')
    if (!many && (condition.value == null || condition.value === '')) throw new Error('请填写范围值')
    if (field.type === FieldType.BOOLEAN) {
      const convert = (v: unknown) =>
        v === true || v === 'true'
          ? true
          : v === false || v === 'false'
            ? false
            : (() => {
                throw new Error('请选择是或否')
              })()
      condition.value = many ? condition.value.map(convert) : convert(condition.value)
    }
  }
  scope.groups.forEach(g => validateScope(g, fields, depth + 1))
}
