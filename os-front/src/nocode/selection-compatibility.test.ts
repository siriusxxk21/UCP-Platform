import { describe, expect, it } from 'vitest'
import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import { selectionLinkCompatible } from './selection-compatibility'

function definition(type: FieldType, options: object = {}, targetObjectId?: string): PublishedDefinition {
  return {
    fields: [{ id: 'value', name: '值', type }],
    fieldOptions: { value: { state: MemberState.ACTIVE, options: [], ...options } },
    relations: targetObjectId ? [{ fieldId: 'value', kind: RelationType.REFERENCE, targetObjectId }] : []
  } as unknown as PublishedDefinition
}
const compatible = (source: PublishedDefinition, target: PublishedDefinition) =>
  selectionLinkCompatible(source, 'value', target, 'value')
const choices = [
  { code: 'A', label: '甲' },
  { code: 'B', label: '乙' }
]

describe('联动字段的比较兼容性', () => {
  it('引用必须指向同一对象，不能用无关数字或其他对象的记录ID比较', () => {
    const source = definition(FieldType.INTEGER, {}, 'category')
    expect(compatible(source, definition(FieldType.INTEGER, {}, 'category'))).toBe(true)
    expect(compatible(source, definition(FieldType.INTEGER, {}, 'department'))).toBe(false)
    expect(compatible(source, definition(FieldType.INTEGER))).toBe(false)
    const many = definition(FieldType.INTEGER, {}, 'category')
    many.relations[0]!.kind = RelationType.MANY_TO_MANY
    expect(compatible(source, many)).toBe(false)
  })
  it('数值兼容且计算字段使用结果类型，文本与数字不兼容', () => {
    expect(compatible(definition(FieldType.INTEGER), definition(FieldType.MONEY))).toBe(true)
    expect(
      compatible(definition(FieldType.FORMULA, { resultType: FieldType.DECIMAL }), definition(FieldType.MONEY))
    ).toBe(true)
    expect(compatible(definition(FieldType.FORMULA), definition(FieldType.FORMULA))).toBe(false)
    expect(compatible(definition(FieldType.TEXT), definition(FieldType.MONEY))).toBe(false)
    expect(compatible(definition(FieldType.TEXT), definition(FieldType.TEXT))).toBe(true)
  })
  it('单选使用一致编码和名称或同一系统字典', () => {
    const source = definition(FieldType.SELECT, { options: choices })
    expect(
      compatible(
        source,
        definition(FieldType.SELECT, { options: [...choices].reverse().map(c => ({ ...c, disabled: true })) })
      )
    ).toBe(true)
    expect(
      compatible(source, definition(FieldType.SELECT, { options: [{ code: 'A', label: '别的含义' }, choices[1]] }))
    ).toBe(false)
    const dictionary = (dictionaryType: string) =>
      definition(FieldType.SELECT, { selection: { kind: 'SYSTEM_DICTIONARY', dictionaryType } })
    expect(compatible(dictionary('unit'), dictionary('unit'))).toBe(true)
    expect(compatible(dictionary('unit'), dictionary('status'))).toBe(false)
  })
  it('已停用、多值和缺失字段不能成为联动比较字段', () => {
    expect(compatible(definition(FieldType.TEXT, { state: MemberState.INACTIVE }), definition(FieldType.TEXT))).toBe(
      false
    )
    expect(compatible(definition(FieldType.MULTI_SELECT), definition(FieldType.MULTI_SELECT))).toBe(false)
    expect(selectionLinkCompatible(definition(FieldType.TEXT), 'missing', definition(FieldType.TEXT), 'value')).toBe(
      false
    )
  })
})
