import { describe, expect, it } from 'vitest'
import { recordDisplay } from './record-display'
import { defaultFieldOptions } from './data-center'
import { FieldType } from '@/types/nocode/enums'

describe('业务列表和统计明细显示', () => {
  it('保留 false 和零，区分空值并分隔多值名称', () => {
    const row = { id: '1', revision: '1', values: { boolean: false, zero: 0, empty: null, tags: ['a', 'b'] } }
    expect(recordDisplay(row, 'boolean')).toBe('否')
    expect(recordDisplay(row, 'zero')).toBe('0')
    expect(recordDisplay(row, 'empty')).toBe('—')
    expect(
      recordDisplay(row, 'tags', {
        ...defaultFieldOptions(),
        options: [
          { code: 'a', label: '甲', disabled: false },
          { code: 'b', label: '乙', disabled: false }
        ]
      })
    ).toBe('甲、乙')
  })
  it('优先使用按权限解析的服务端展示值', () => {
    const row = { id: '1', revision: '1', values: { user: '1' }, displayValues: { user: '管理员' } }
    expect(recordDisplay(row, 'user')).toBe('管理员')
  })
  it('金额使用统一财务格式且不经过浮点数', () => {
    const row = { id: '1', revision: '1', values: { amount: '9007199254740993.995', refund: '-1.005' } }
    expect(recordDisplay(row, 'amount', undefined, { type: FieldType.MONEY })).toBe('9,007,199,254,740,994.00')
    expect(recordDisplay(row, 'refund', undefined, { type: FieldType.MONEY })).toBe('-1.01')
    expect(
      recordDisplay(
        row,
        'amount',
        { ...defaultFieldOptions(), resultType: FieldType.MONEY },
        { type: FieldType.FORMULA }
      )
    ).toBe('9,007,199,254,740,994.00')
  })
})
