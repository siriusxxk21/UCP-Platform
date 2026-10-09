import { describe, expect, it } from 'vitest'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { changeFieldType } from './field-editing'
import { fieldSwitchFingerprint, fieldSwitchSnapshot, restoreFieldSwitch } from './field-switch-session'

describe('字段转换确认会话', () => {
  it('取消类型切换恢复选项和默认值，保留本轮其他字段输入', () => {
    const field = { ...newField(0, '状态'), type: 'SELECT' as const }
    const options = {
      ...defaultFieldOptions(),
      options: [{ code: 'todo', label: '待办', disabled: false }],
      defaultValue: 'todo'
    }
    const before = fieldSwitchSnapshot(field, options, null)
    changeFieldType(field, options, 'DECIMAL')
    field.name = '订单状态'
    options.description = '用户编辑的说明'
    options.classification = 'INTERNAL'
    restoreFieldSwitch(field, options, before)
    expect(field.type).toBe('SELECT')
    expect(field.name).toBe('订单状态')
    expect(options.defaultValue).toBe('todo')
    expect(options.options).toEqual([{ code: 'todo', label: '待办', disabled: false }])
    expect(options.description).toBe('用户编辑的说明')
    expect(options.classification).toBe('INTERNAL')
  })

  it('确认只绑定转换参数，修改精度、选项或引用目标必须重新确认', () => {
    const field = newField(0, '金额')
    const options = defaultFieldOptions()
    changeFieldType(field, options, 'DECIMAL')
    const before = fieldSwitchFingerprint(field, options, null)
    field.name = '采购金额'
    options.description = '说明'
    expect(fieldSwitchFingerprint(field, options, null)).toBe(before)
    field.scale = 4
    expect(fieldSwitchFingerprint(field, options, null)).not.toBe(before)
    const after = fieldSwitchFingerprint(field, options, '1')
    expect(fieldSwitchFingerprint(field, options, '2')).not.toBe(after)
  })
})
