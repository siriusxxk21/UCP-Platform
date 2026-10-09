import { describe, expect, it } from 'vitest'
import { autoNumberError, autoNumberPreview, defaultAutoNumber, legacyAutoNumberFields } from './auto-number'
import { changeFieldType, copyFieldOptions, designFieldsError } from './field-editing'
import { defaultFieldOptions, generatedBinding, newDesign } from './data-center'
import { newField } from './object-draft'

describe('数据对象自动编号规则', () => {
  it('仅锁定已发布主表与明细的旧整数编号，未发布新增字段不被误锁', () => {
    expect(
      legacyAutoNumberFields({
        fields: [
          { id: 'old', type: 'AUTO_NUMBER' },
          { id: 'text', type: 'TEXT' },
          { id: 'configured', type: 'AUTO_NUMBER' }
        ],
        fieldOptions: { configured: { autoNumber: defaultAutoNumber() } },
        details: [{ fields: [{ id: 'detail-old', type: 'AUTO_NUMBER' }], fieldOptions: {} }]
      })
    ).toEqual(['old', 'detail-old'])
  })
  it('格式示例按北京时间跨日，补零且不截断较长流水号', () => {
    const rule = { ...defaultAutoNumber(), prefix: 'HT-', dateFormat: 'yyyyMMdd' as const }
    expect(autoNumberPreview(rule, new Date('2026-09-13T16:00:00Z'))).toBe('HT-20260914000001')
    expect(
      autoNumberPreview({ ...rule, sequenceLength: 2, startValue: 999 }, new Date('2026-09-13T15:59:59Z'), 1)
    ).toBe('HT-202609131000')
    expect(autoNumberPreview(defaultAutoNumber())).toBe('000001')
  })

  it('阻止缺少周期标识的重置和非法数字，兼容无配置整数自增', () => {
    expect(autoNumberError(null)).toBeNull()
    for (const resetCycle of ['DAY', 'MONTH', 'YEAR'] as const) {
      expect(autoNumberError({ ...defaultAutoNumber(), resetCycle })).toContain('日期格式')
      expect(autoNumberError({ ...defaultAutoNumber(), dateFormat: 'yyyyMMdd', resetCycle })).toBeNull()
    }
    expect(autoNumberError({ ...defaultAutoNumber(), dateFormat: 'yyyy', resetCycle: 'MONTH' })).toContain('日期格式')
    for (const startValue of [0, 1.5, Number.NaN, 1000000000000])
      expect(autoNumberError({ ...defaultAutoNumber(), startValue })).toContain('起始值')
    for (const sequenceLength of [0, 13, 1.5])
      expect(autoNumberError({ ...defaultAutoNumber(), sequenceLength })).toContain('位数')
    expect(autoNumberError({ ...defaultAutoNumber(), prefix: 'HT-\n' })).toContain('前缀')
  })

  it('字段转换初始化规则，取消编辑不污染嵌套规则，转换离开清理规则', () => {
    const field = newField(0, '编号')
    const options = defaultFieldOptions()
    changeFieldType(field, options, 'AUTO_NUMBER')
    expect(options.autoNumber).toEqual(defaultAutoNumber())
    const copy = copyFieldOptions(options)
    if (!copy.autoNumber || !options.autoNumber) throw new Error('应初始化编号规则')
    copy.autoNumber.prefix = 'HT-'
    expect(options.autoNumber.prefix).toBe('')
    changeFieldType(field, options, 'AUTO_NUMBER')
    expect(options.autoNumber).toEqual(defaultAutoNumber())
    changeFieldType(field, options, 'TEXT')
    expect(options.autoNumber).toBeNull()
  })

  it('主表与内部明细统一保存都校验编号规则，避免绕过弹窗', () => {
    const design = newDesign()
    const field = newField(1, '编号')
    const options = defaultFieldOptions()
    changeFieldType(field, options, 'AUTO_NUMBER')
    if (!options.autoNumber) throw new Error('应初始化编号规则')
    options.autoNumber.resetCycle = 'DAY'
    design.draft.fields.push(field)
    design.fieldOptions[field.key] = options
    expect(designFieldsError(design)).toMatchObject({ tab: 'fields', message: expect.stringContaining('日期格式') })
    design.draft.fields.pop()
    design.details.push({
      id: null,
      code: 'items',
      name: '明细',
      tableName: 'biz_test_items',
      state: 'ACTIVE',
      fields: [field],
      fieldOptions: { [field.key]: options },
      indexes: [],
      binding: generatedBinding('public', true)
    })
    expect(designFieldsError(design)).toMatchObject({ tab: 'details', message: expect.stringContaining('日期格式') })
    options.autoNumber.dateFormat = 'yyyyMMdd'
    expect(designFieldsError(design)).toBeNull()
  })
})
