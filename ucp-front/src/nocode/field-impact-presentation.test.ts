import { describe, expect, it } from 'vitest'
import type { ConversionImpact, FieldConversion } from '@/types/nocode/data-center'
import { distinctFieldImpacts, fieldConversionConclusion } from './field-impact-presentation'

const impact: ConversionImpact = {
  fieldId: 'f1',
  sourceKind: 'OBJECT',
  sourceId: '21',
  sourceName: '订单',
  location: '索引 → 唯一编号',
  message: '索引使用此字段',
  route: '/nocode/object/editor?id=21&tab=indexes',
  blocking: true
}
const conversion: FieldConversion = {
  fieldId: 'f1',
  detailId: null,
  fieldName: '编号',
  sourceName: '订单',
  fromType: 'varchar(100)',
  toType: 'integer',
  affectedRows: 3,
  deletedRows: 0,
  action: 'CLEAR_COLUMN',
  masked: false,
  fingerprint: 'f',
  clearAllowed: true,
  impacts: []
}

describe('字段影响呈现', () => {
  it('相同提示只去除同一个来源、位置和处理动作的重复项', () => {
    const anotherObject = { ...impact, sourceId: '22' }
    const anotherLocation = { ...impact, location: '记录标题' }
    const anotherRoute = { ...impact, route: '/nocode/object/editor?id=21&tab=fields' }
    const warning = { ...impact, blocking: false }
    expect(
      distinctFieldImpacts([
        impact,
        { ...impact, message: ' 索引使用此字段 ' },
        anotherObject,
        anotherLocation,
        anotherRoute,
        warning
      ])
    ).toEqual([impact, anotherObject, anotherLocation, anotherRoute, warning])
  })
  it('不合并解决方式不同的影响', () => {
    const first = { ...impact, code: 'FIELD_REFERENCE', resolution: '修改索引' }
    const second = { ...first, resolution: '停用索引' }
    expect(distinctFieldImpacts([first, second])).toHaveLength(2)
  })
  it('清空结论必须写明整列范围和其他字段、记录保留', () => {
    expect(fieldConversionConclusion(conversion)).toEqual({
      type: 'warning',
      title: '发布时清空本列',
      description: '发布时清空本列全部 3 个旧值，整条记录和其他列保留。'
    })
    expect(fieldConversionConclusion({ ...conversion, action: undefined }).type).toBe('warning')
  })
  it('清空被禁止或有独立依赖时不能呈现可以转换', () => {
    expect(fieldConversionConclusion({ ...conversion, clearAllowed: false }).type).toBe('error')
    expect(fieldConversionConclusion({ ...conversion, impacts: [impact] }).type).toBe('error')
  })
  it('保留转换和约束变更的失败值仍以阻断结论呈现', () => {
    expect(fieldConversionConclusion({ ...conversion, action: 'PRESERVE_VALUES', failedRows: 1 }).type).toBe('error')
    expect(fieldConversionConclusion({ ...conversion, action: 'KEEP_COLUMN', failedRows: 1 }).type).toBe('error')
    expect(fieldConversionConclusion({ ...conversion, action: 'PRESERVE_VALUES', clearAllowed: false }).type).toBe(
      'success'
    )
  })
  it('空列不显示清空确认，约束修改说明默认值只用于新记录', () => {
    expect(fieldConversionConclusion({ ...conversion, affectedRows: 0, clearAllowed: false }).title).toBe(
      '本列没有旧值'
    )
    expect(fieldConversionConclusion({ ...conversion, action: 'KEEP_COLUMN' }).description).toContain(
      '默认值只对新记录生效'
    )
    expect(fieldConversionConclusion({ ...conversion, affectedRows: 0, failedRows: 3, clearAllowed: false }).type).toBe(
      'error'
    )
  })
})
