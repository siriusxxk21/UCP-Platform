import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import { summarizeConditions } from './conditionSummary'
import type { DynamicSearchField, SerializedConditionLeaf } from './types'

const fields: DynamicSearchField[] = [
  { field: 'name', label: '公司名称', type: 'text' },
  { field: 'amount', label: '金额', type: 'number' },
  { field: 'active', label: '状态', type: 'select', options: [{ label: '停用', value: 'false' }] },
  { field: 'created', label: '创建时间', type: 'datetimeRange' }
]
const leaf = (
  field: string,
  value: unknown,
  operator: SerializedConditionLeaf['operator'] = 'eq'
): SerializedConditionLeaf => ({ type: 'condition', field, value, operator })

describe('advanced search condition summary', () => {
  it('preserves nested AND / OR grouping and readable field names', () => {
    expect(
      summarizeConditions(
        {
          logic: 'AND',
          items: [
            leaf('name', '甲', 'like'),
            {
              type: 'group',
              groupLogic: 'OR',
              groupItems: [leaf('amount', 0), leaf('amount', '9007199254740993.01', 'gt')]
            }
          ]
        },
        fields
      )
    ).toBe('公司名称 包含「甲」 且 （金额 等于「0」 或 金额 大于「9007199254740993.01」）')
  })

  it('shows option labels for false and IN instead of stored codes', () => {
    expect(
      summarizeConditions({ logic: 'AND', items: [leaf('active', false), leaf('active', ['false'], 'in')] }, fields)
    ).toBe('状态 等于「停用」 且 状态 在范围内「停用」')
  })

  it('keeps both date range boundaries and time when system fields use Dayjs', () => {
    expect(
      summarizeConditions(
        {
          logic: 'AND',
          items: [leaf('created', [dayjs('2026-09-01 09:00:00'), dayjs('2026-09-07 18:30:00')], 'between')]
        },
        fields
      )
    ).toBe('创建时间 在区间内「2026-09-01 09:00:00 至 2026-09-07 18:30:00」')
  })

  it('has no temporary summary after conditions are cleared', () => {
    expect(summarizeConditions(null, fields)).toBe('')
    expect(summarizeConditions({ logic: 'AND', items: [] }, fields)).toBe('')
  })
})
