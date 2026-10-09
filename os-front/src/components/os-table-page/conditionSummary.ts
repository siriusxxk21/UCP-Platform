import dayjs from 'dayjs'
import { OPERATOR_LABELS } from './types'
import type { DynamicSearchCondition, DynamicSearchField, SerializedItem } from './types'

/** 显示已生效的完整条件关系，避免未收藏的高级检索成为不可见的筛选。 */
export function summarizeConditions(conditions: DynamicSearchCondition | null, fields: DynamicSearchField[]): string {
  if (!conditions) return ''
  const fieldMap = new Map(fields.map(field => [field.field, field]))

  function formatValue(value: unknown, field?: DynamicSearchField): string {
    const custom = field?.formatValue?.(value)
    if (custom !== undefined) return custom
    const option = field?.options?.find(option => String(option.value) === String(value))
    if (option) return option.label
    if (dayjs.isDayjs(value)) {
      return value.format(field?.type === 'datetimeRange' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD')
    }
    if (typeof value === 'boolean') return value ? '是' : '否'
    return String(value ?? '')
  }

  function summarize(items: SerializedItem[], logic: 'AND' | 'OR'): string {
    return items
      .map(item => {
        if (item.type === 'group') return `（${summarize(item.groupItems, item.groupLogic)}）`
        const field = fieldMap.get(item.field)
        const value = Array.isArray(item.value)
          ? item.value.map(value => formatValue(value, field)).join(item.operator === 'between' ? ' 至 ' : '、')
          : formatValue(item.value, field)
        return `${field?.label ?? item.field} ${OPERATOR_LABELS[item.operator]}「${value}」`
      })
      .join(logic === 'AND' ? ' 且 ' : ' 或 ')
  }

  return summarize(conditions.items, conditions.logic)
}
