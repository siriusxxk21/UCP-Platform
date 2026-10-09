import { describe, expect, it } from 'vitest'
import { recordNumberError } from './record-number'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
const field = { name: '金额', type: FieldType.MONEY, precision: 20, scale: 2 } as ObjectField
describe('对象数值约束', () => {
  it('金额校验保留超过 Number 安全范围的字符串精度', () => {
    expect(recordNumberError(field, { maximum: '9007199254740993.01' }, '9007199254740993.01')).toBeNull()
    expect(recordNumberError(field, { maximum: '9007199254740993.01' }, '9007199254740993.02')).toContain('大于')
    expect(recordNumberError(field, { minimum: '-0.01' }, '-0.02')).toContain('小于')
  })
  it('精度、小数位和科学计数法与对象小数类型一致', () => {
    expect(recordNumberError(field, {}, '1.2300')).toBeNull()
    expect(recordNumberError(field, {}, '1e-2')).toBeNull()
    expect(recordNumberError(field, {}, '1e-3')).toContain('2 位小数')
    expect(recordNumberError(field, {}, '1e18')).toContain('20 位数值精度')
    expect(recordNumberError(field, {}, 'not-a-number')).toContain('有效数值')
    expect(recordNumberError(field, {}, '')).toBeNull()
  })
  it('64 位整数边界不会因 Number 舍入放过越界值', () => {
    const integer = { ...field, type: FieldType.INTEGER }
    for (const value of ['9223372036854775807', '-9223372036854775808', '00001'])
      expect(recordNumberError(integer, {}, value)).toBeNull()
    for (const value of ['9223372036854775808', '-9223372036854775809'])
      expect(recordNumberError(integer, {}, value)).toContain('64 位整数范围')
    for (const value of ['1.2', '1e3']) expect(recordNumberError(integer, {}, value)).toContain('整数')
  })
})
