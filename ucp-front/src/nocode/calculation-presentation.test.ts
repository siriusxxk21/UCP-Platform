import { describe, expect, it } from 'vitest'
import {
  calculationCategories,
  calculationCategory,
  calculationDescription,
  calculationUpdateDescription,
  calculationQueryReady,
  calculationValueField
} from './calculation-presentation'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import type { CalculationOptions } from '@/types/nocode/data-center'

const rule = (
  mode: CalculationOptions['mode'],
  aggregate: CalculationOptions['aggregate'] = 'SINGLE'
): CalculationOptions => ({
  mode,
  aggregate,
  updateMode: 'LIVE',
  targetObjectId: null,
  relationId: null,
  targetField: null,
  conditions: [],
  logic: 'AND',
  excludeCurrent: false
})
describe('旧计算配置的四入口投影', () => {
  it('有序 ON_SAVE 只有 READY 可查询，结果类型投影不改变原字段或 JSON', () => {
    const options = {
      ...defaultFieldOptions(),
      resultType: 'MONEY',
      calculation: { ...rule('RUNNING_TOTAL'), updateMode: 'ON_SAVE' as const }
    }
    const field = { ...newField(1), type: 'FORMULA' as const }
    for (const state of [undefined, 'PENDING', 'BACKFILLING', 'FAILED'])
      expect(calculationQueryReady(options, state)).toBe(false)
    expect(calculationQueryReady(options, 'READY')).toBe(true)
    expect(calculationQueryReady({ ...options, calculation: rule('RUNNING_TOTAL') }, 'READY')).toBe(false)
    expect(calculationQueryReady({ ...options, calculation: { ...rule('LOCAL'), updateMode: 'ON_SAVE' } })).toBe(true)
    expect(calculationValueField(field, options)).toMatchObject({ key: field.key, type: 'MONEY' })
    expect(field.type).toBe('FORMULA')
  })
  it('相同 ON_SAVE 编码区分有序联动与普通保存快照，LIVE 始终是读取时计算', () => {
    for (const mode of ['RUNNING_TOTAL', 'SEQUENCE'] as const) {
      expect(calculationUpdateDescription({ ...rule(mode), updateMode: 'ON_SAVE' })).toContain('同组联动')
      expect(calculationUpdateDescription(rule(mode))).toBe('读取时计算')
    }
    for (const mode of ['LOCAL', 'RELATION', 'LOOKUP', 'STATISTICS'] as const)
      expect(calculationUpdateDescription({ ...rule(mode), updateMode: 'ON_SAVE' })).toBe('本记录保存时重算')
  })
  it('一级入口只有四项，旧协议按计算目的投影且不填充默认属性', () => {
    expect(calculationCategories.map(item => item.label)).toEqual(['公式运算', '查找取值', '汇总统计', '顺序计算'])
    const cases: [CalculationOptions | null, string][] = [
      [null, 'FORMULA'],
      [rule('LOCAL'), 'FORMULA'],
      [rule('RELATION'), 'LOOKUP'],
      [rule('LOOKUP'), 'LOOKUP'],
      [rule('RELATION', 'SUM'), 'AGGREGATE'],
      [rule('LOOKUP', 'COUNT'), 'AGGREGATE'],
      [rule('STATISTICS', 'SUM'), 'AGGREGATE'],
      [rule('RUNNING_TOTAL', 'SUM'), 'SEQUENCE'],
      [
        { ...rule('SEQUENCE', 'SUM'), sequence: { orderField: 'serial', tieBreakerField: null, direction: 'NEXT' } },
        'SEQUENCE'
      ]
    ]
    for (const [input, expected] of cases) {
      const original = JSON.stringify(input)
      expect(calculationCategory(input)).toBe(expected)
      calculationDescription(input)
      expect(JSON.stringify(input)).toBe(original)
    }
  })
  it('摘要使用通用计算名称，基础公式、关系聚合和旧增减累计可区分', () => {
    expect(calculationDescription(null)).toBe('公式运算 · 仅基础字段')
    expect(calculationDescription(rule('LOCAL'))).toBe('公式运算 · 包含计算结果')
    expect(calculationDescription(rule('RELATION', 'SUM'))).toBe('汇总统计 · 关联记录')
    expect(calculationDescription(rule('RUNNING_TOTAL', 'SUM'))).toBe('顺序计算 · 增减值累计')
  })
})
