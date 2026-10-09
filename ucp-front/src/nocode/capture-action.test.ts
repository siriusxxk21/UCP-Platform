import { describe, expect, it } from 'vitest'
import { captureCompatible, captureMappings, captureTarget } from './capture-action'
import { defaultFieldOptions } from './data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'

const field = (id: string, type: ObjectField['type']): ObjectField =>
  ({ id, key: id, code: id, name: id, type, required: false }) as ObjectField

describe('业务动作留存计算结果', () => {
  const source = field('live', FieldType.FORMULA)
  const target = field('saved', FieldType.MONEY)
  const definition: Pick<PublishedDefinition, 'fields' | 'fieldOptions' | 'relations'> = {
    fields: [source, target],
    fieldOptions: { live: { ...defaultFieldOptions(), resultType: FieldType.DECIMAL } },
    relations: []
  }

  it('按稳定字段标识保存映射，允许数值兼容类型', () => {
    expect(captureMappings([{ target: 'saved', source: 'live' }], definition)).toEqual({ saved: 'live' })
    expect(captureCompatible(source, definition.fieldOptions.live, field('int', FieldType.INTEGER))).toBe(false)
  })

  it('确认目标必须容许未确认状态且不能是受数据库维护字段', () => {
    expect(captureTarget({ ...target, required: true }, undefined)).toBe(false)
    expect(captureTarget(target, { ...defaultFieldOptions(), defaultValue: '0' })).toBe(false)
    expect(captureTarget(target, { ...defaultFieldOptions(), generated: true })).toBe(false)
    expect(captureTarget(target, { ...defaultFieldOptions(), primaryKey: true })).toBe(false)
    expect(captureTarget(target, undefined, true)).toBe(false)
    expect(captureTarget(source, undefined)).toBe(false)
  })

  it('拒绝空配置、重复目标和不是公式的来源', () => {
    expect(() => captureMappings([], definition)).toThrow('1 到 20')
    expect(() =>
      captureMappings(
        [
          { target: 'saved', source: 'live' },
          { target: 'saved', source: 'live' }
        ],
        definition
      )
    ).toThrow('不能重复')
    expect(() => captureMappings([{ target: 'saved', source: 'saved' }], definition)).toThrow('来源公式')
    expect(() => captureMappings([{ target: 'deleted', source: 'live' }], definition)).toThrow('留存字段')
  })
})
