import type {
  DynamicSearchCondition,
  DynamicSearchField,
  DynamicSearchOperator,
  SerializedItem
} from '@/components/os-table-page/types'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { ViewListConfig } from '@/types/nocode/application-ui'
import type { BusinessRow } from '@/types/nocode/runtime'
import { FieldType } from '@/types/nocode/enums'
import { arrayFieldTypes, dateTimeValueFormat, recordFilterValue } from './record-form'
import { isRelationFieldId } from './business-fields'
import { calculationValueField } from './calculation-presentation'
import { markRaw } from 'vue'
import RelativeDateValue from '@/views/nocode/components/RelativeDateValue.vue'
import { describeRelativeDate, isRelativeDate } from './relative-date'

export const numericQueryTypes = new Set<FieldType>([
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT
])
export const textQueryTypes = new Set<FieldType>([
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.RICH_TEXT,
  FieldType.AUTO_NUMBER
])
export const supportsAdvancedQuery = (field: ObjectField) =>
  field.type !== FieldType.SUMMARY && field.type !== FieldType.URL && !arrayFieldTypes.has(field.type)

export function defaultViewList(): ViewListConfig {
  return { queryFieldIds: [], advancedFieldIds: null, columnWidths: {}, batchDelete: false }
}

/** 条件搜索里日期字段除「在区间内」外的比较方式，都可配相对日期。 */
export const DATE_RELATIVE_SEARCH_OPERATORS: DynamicSearchOperator[] = ['eq', 'neq', 'lt', 'lte', 'gt', 'gte']

/** 字段元数据只适配底座控件；查询仍提交稳定字段 ID，数值不经过 Number。 */
export function dynamicQueryField(
  field: ObjectField,
  options?: FieldOptions,
  choices?: { label: string; value: unknown }[]
): DynamicSearchField {
  field = calculationValueField(field, options)
  const base = { field: field.id!, label: field.name }
  if (choices?.length || field.type === FieldType.SELECT || field.type === FieldType.BOOLEAN)
    return {
      ...base,
      type: 'select',
      operators: ['eq', 'neq', 'in'],
      options:
        field.type === FieldType.BOOLEAN
          ? [
              { label: '是', value: 'true' },
              { label: '否', value: 'false' }
            ]
          : choices || []
    }
  if (numericQueryTypes.has(field.type))
    return { ...base, type: 'number', stringMode: true, operators: ['eq', 'neq', 'gt', 'gte', 'lt', 'lte'] }
  // 日期 / 日期时间：在区间内（具体起止）之外，可用「等于 / 不等于 / 早于 / 晚于……」配具体日期或相对日期（今天、本月、过去 N 天……）；
  // 相对日期由服务端每次查询时按当天换算。「在区间内」只用具体起止（底座条件搜索按 [起, 止] 两格校验）。
  if (field.type === FieldType.DATE || field.type === FieldType.DATETIME) {
    const valueFormat = field.type === FieldType.DATE ? 'YYYY-MM-DD' : dateTimeValueFormat(options)
    return {
      ...base,
      type: field.type === FieldType.DATE ? 'dateRange' : 'datetimeRange',
      operators: ['between', ...DATE_RELATIVE_SEARCH_OPERATORS],
      valueFormat,
      valueComponent: markRaw(RelativeDateValue),
      formatValue: value => (isRelativeDate(value) ? describeRelativeDate(value) : undefined),
      valueProps: { fieldType: field.type, valueFormat, relativeOperators: DATE_RELATIVE_SEARCH_OPERATORS }
    }
  }
  return {
    ...base,
    type: 'text',
    operators: textQueryTypes.has(field.type) ? ['like', 'eq', 'neq', 'notLike', 'startWith', 'endWith'] : ['eq', 'neq']
  }
}

/** 常用文本条件包含匹配；多选字典包含任意值，关系和其他数组保留原契约。0、false 必须保留。 */
export function basicQuery(
  fields: ObjectField[],
  values: Record<string, unknown>,
  dictionaryFields: Set<string> = new Set()
) {
  const items: SerializedItem[] = []
  const equal: Record<string, unknown> = {}
  for (const field of fields) {
    const scope = values[field.id!]
    if (scope && typeof scope === 'object' && !Array.isArray(scope) && 'includeDescendants' in scope) {
      equal[field.id!] = scope
      continue
    }
    const value =
      Array.isArray(scope) && !arrayFieldTypes.has(field.type)
        ? scope.length
          ? scope.map(v => recordFilterValue(field, v))
          : undefined
        : recordFilterValue(field, scope)
    if (value === undefined) continue
    if (field.type === FieldType.MULTI_SELECT && !isRelationFieldId(field.id || field.key))
      items.push({ type: 'condition', field: field.id!, operator: 'containsAny', value })
    else if (arrayFieldTypes.has(field.type)) equal[field.id!] = value
    else
      items.push({
        type: 'condition',
        field: field.id!,
        operator: Array.isArray(value)
          ? 'in'
          : textQueryTypes.has(field.type) && !dictionaryFields.has(field.id!)
            ? 'like'
            : 'eq',
        value
      })
  }
  return { equal, conditions: items.length ? { logic: 'AND' as const, items } : null }
}

/** 配置查询与高级查询叠加；服务端再与固定视图和记录权限取交集。 */
export function combineConditions(
  basic: DynamicSearchCondition | null,
  advanced: DynamicSearchCondition | null
): DynamicSearchCondition | null {
  if (!basic) return advanced
  if (!advanced) return basic
  return {
    logic: 'AND',
    items: [basic, advanced].map(c => ({ type: 'group', groupLogic: c.logic, groupItems: c.items }))
  }
}

export function pageSizeOptions(size: number) {
  return [...new Set([10, 20, 50, 100, size])].sort((a, b) => a - b).map(String)
}

/** Select 使用字符串键；提交时恢复布尔值，并校验已收藏条件的字段仍可用。 */
export function normalizeAdvancedQuery(
  condition: DynamicSearchCondition | null,
  fields: ObjectField[]
): DynamicSearchCondition | null {
  if (!condition) return null
  function normalize(item: SerializedItem): SerializedItem {
    if (item.type === 'group') return { ...item, groupItems: item.groupItems.map(normalize) }
    const field = fields.find(f => f.id === item.field)
    if (!field) throw new Error('查询字段已不可用，请重置查询条件')
    if (field.type !== FieldType.BOOLEAN) return item
    const value =
      item.operator === 'in' && Array.isArray(item.value)
        ? item.value.map(v => recordFilterValue(field, v))
        : recordFilterValue(field, item.value)
    return { ...item, value }
  }
  return { ...condition, items: condition.items.map(normalize) }
}

export function listPreferenceKey(userId: string | number | undefined, app: string, object: string, view?: string) {
  return `nocode-records:${userId ?? 'anonymous'}:${app}:${object}:${view || 'default'}`
}

/** 批量操作允许部分成功；一条失败不能吞掉后续记录或被计为成功。 */
export async function deleteRecordBatch(rows: BusinessRow[], remove: (row: BusinessRow) => Promise<unknown>) {
  let succeeded = 0
  const failures: { id: string | null; error: unknown }[] = []
  for (const row of rows) {
    try {
      await remove(row)
      succeeded++
    } catch (error) {
      failures.push({ id: row.id, error })
    }
  }
  return { succeeded, failures }
}
