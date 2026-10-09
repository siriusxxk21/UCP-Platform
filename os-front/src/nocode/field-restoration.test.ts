import { describe, expect, it } from 'vitest'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import {
  fieldRestoreError,
  inactiveFieldCodeError,
  restoredFieldValue,
  type InactiveFieldCandidate
} from './field-restoration'

function candidate(): InactiveFieldCandidate {
  return {
    field: { ...newField(2, '联系电话'), id: '9007199254740995', key: '9007199254740995', code: 'phone' },
    options: {
      ...defaultFieldOptions(),
      state: 'INACTIVE',
      options: [{ code: 'a', label: '甲', disabled: false }],
      columnName: 'phone',
      description: '原配置'
    },
    restorable: true,
    blockedReason: null,
    persisted: true
  }
}
describe('停用字段恢复', () => {
  it('同编码指出停用字段及恢复入口，同名不同编码仍允许', () => {
    const inactive = candidate()
    expect(inactiveFieldCodeError({ ...newField(0), code: 'phone' }, [inactive])).toContain('联系电话')
    expect(inactiveFieldCodeError({ ...newField(0), code: 'phone' }, [inactive])).toContain('已停用字段')
    expect(inactiveFieldCodeError({ ...newField(0, '联系电话'), code: 'phone_new' }, [inactive])).toBeNull()
    expect(inactiveFieldCodeError(inactive.field, [inactive])).toBeNull()
    inactive.options.columnName = 'legacy_phone'
    expect(inactiveFieldCodeError({ ...newField(0), code: 'legacy_phone' }, [inactive])).toContain('联系电话')
  })
  it('阻止重复恢复、当前草稿编码冲突、字段上限及受保护字段', () => {
    const inactive = candidate()
    expect(fieldRestoreError(inactive, [inactive.field])).toContain('已在当前草稿')
    expect(fieldRestoreError(inactive, [{ ...newField(0), code: 'phone' }])).toContain('已使用编码')
    expect(
      fieldRestoreError(
        inactive,
        Array.from({ length: 200 }, (_, i) => newField(i))
      )
    ).toContain('200')
    expect(fieldRestoreError({ ...inactive, restorable: false, blockedReason: '请在对象关系中维护' }, [])).toContain(
      '对象关系'
    )
  })
  it('恢复保留大整数ID、编码、物理列与配置，且不修改原停用快照', () => {
    const inactive = candidate()
    const restored = restoredFieldValue(inactive)
    expect(restored.field).toEqual(inactive.field)
    expect(restored.options).toMatchObject({ state: 'ACTIVE', columnName: 'phone', description: '原配置' })
    restored.field.name = '新显示名'
    const choice = restored.options.options[0]
    if (!choice) throw new Error('原选项必须被保留')
    choice.label = '乙'
    expect(inactive.field.name).toBe('联系电话')
    expect(inactive.options.state).toBe('INACTIVE')
    expect(inactive.options.options[0]?.label).toBe('甲')
  })
})
