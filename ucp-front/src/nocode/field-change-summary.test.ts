import { describe, expect, it } from 'vitest'
import { fieldConfigurationChanges } from './field-change-summary'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'

describe('字段配置影响说明', () => {
  it('重开已保存草稿仍展示相对已发布定义的净变化', () => {
    const field = { ...newField(0, '金额'), id: '10', type: 'DECIMAL' as const, required: true }
    const options = defaultFieldOptions()
    const changes = fieldConfigurationChanges(field, options, field, options, {
      type: 'TEXT',
      length: 200,
      precision: null,
      scale: null,
      required: false,
      unique: false,
      minimum: null,
      maximum: null,
      pattern: null,
      selection: null,
      targetObjectId: null
    })
    expect(changes).toContainEqual({ label: '字段类型', before: '单行文本', after: '小数' })
    expect(changes).toContainEqual({ label: '必填', before: '允许为空', after: '必填' })
  })
  it('选项调整明确展示稳定编码，默认值只说明新记录用途', () => {
    const field = { ...newField(0, '状态'), type: 'SELECT' as const }
    const before = {
      ...defaultFieldOptions(),
      options: [{ code: 'a', label: '有效', disabled: false }],
      defaultValue: 'a'
    }
    const after = { ...before, options: [{ code: 'b', label: '有效', disabled: false }], defaultValue: 'b' }
    expect(fieldConfigurationChanges(field, before, field, after)).toEqual([
      { label: '自定义选项', before: '有效（a）', after: '有效（b）' },
      { label: '默认值（仅新记录）', before: '已设置', after: '使用新的默认值' }
    ])
  })
  it('已发布关系改回普通字段明确展示解除目标，不因本次草稿未改而遗漏', () => {
    const field = { ...newField(0, '客户'), id: '10', type: 'TEXT' as const }
    const options = defaultFieldOptions()
    const changes = fieldConfigurationChanges(field, options, field, options, {
      type: 'REFERENCE',
      length: null,
      precision: null,
      scale: null,
      required: false,
      unique: false,
      minimum: null,
      maximum: null,
      pattern: null,
      selection: null,
      targetObjectId: '21'
    })
    expect(changes).toContainEqual({ label: '关联对象', before: '21', after: '未设置' })
  })
  it('整数存储的关系生成列保留相同目标，不误报解除引用', () => {
    const field = { ...newField(0, '客户'), id: '10', type: 'INTEGER' as const, length: null }
    const options = { ...defaultFieldOptions(), generated: true }
    expect(fieldConfigurationChanges(field, options, field, options, undefined, '21', '21')).toEqual([])
    expect(fieldConfigurationChanges(field, options, field, options, undefined, '21', '22')).toEqual([
      { label: '关联对象', before: '21', after: '22' }
    ])
  })
})
