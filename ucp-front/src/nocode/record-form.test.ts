import { describe, it, expect } from 'vitest'
import { recordPayload, recordRules, recordDefaults, recordFilterValue } from './record-form'
import type { ObjectField } from '@/types/nocode/object'
import type { TableModel } from '@/types/nocode/runtime'
const fields = [
  { id: '1', code: 'name', name: '名称', type: 'TEXT', required: true, length: 100 },
  { id: '2', code: 'amount', name: '金额', type: 'DECIMAL' },
  { id: '3', code: 'total', name: '总额', type: 'FORMULA' },
  { id: '4', code: 'enabled', name: '启用', type: 'BOOLEAN' }
] as ObjectField[]
const cap: TableModel = { writable: true, generatedKey: false, keyFieldId: '1', keyType: 'text' }
describe('业务表单和运行契约', () => {
  it('关联规则维护字段保留展示但不要求手填、不应用旧默认值，也不随表单回写', () => {
    const model = { ...cap, managedFieldIds: ['2'] }
    const managed = [{ ...fields[1], required: true }] as ObjectField[]
    const options = { '2': { defaultValue: '100' } } as any
    expect(recordDefaults(managed, options, model)).toEqual({})
    expect(recordPayload(managed, options, model, true, { '2': '100' })).toEqual({})
    expect(recordPayload(managed, options, model, false, { '2': '999' })).toEqual({})
    const [rule] = recordRules(managed, options, model, true)
    expect(rule?.props?.disabled).toBe(true)
    expect(rule?.props?.placeholder).toContain('由业务规则维护')
    expect(rule?.validate).toBeUndefined()
  })
  it('必填开关初始否值可提交，显式默认值与可选字段语义保留', () => {
    const required = [{ ...fields[3], required: true }]
    expect(recordDefaults(required, {}, cap)).toEqual({ '4': false })
    expect(recordDefaults(required, { '4': { defaultValue: 'true' } } as any, cap)).toEqual({ '4': true })
    expect(recordDefaults(required, {}, { ...cap, writable: false })).toEqual({})
    expect(recordDefaults([fields[3]], {}, cap)).toEqual({})
    expect(recordPayload(required, {}, cap, true, recordDefaults(required, {}, cap))).toEqual({ '4': false })
    expect(recordRules(required, {}, cap, true)[0].validate).toEqual([
      { required: true, type: 'boolean', message: '请填写启用' }
    ])
  })
  it('旧快照没有自动维护标记时仍按物理列排除审计字段，手工主键继续可填', () => {
    const audit = ['creator', 'create_time', 'updater', 'update_time', 'deleted'].map((column, index) => ({
      id: `audit${index}`,
      code: `renamed${index}`,
      name: column,
      type: 'TEXT',
      required: true
    })) as ObjectField[]
    const options = Object.fromEntries(
      audit.map((f, index) => [
        f.id!,
        { columnName: ['creator', 'create_time', 'updater', 'update_time', 'deleted'][index], generated: false }
      ])
    )
    const all = [...fields, ...audit]
    expect(recordRules(all, options as any, cap, true).map(r => r.field)).not.toContain('audit0')
    const editRules = recordRules(all, options as any, cap, false).filter(r => String(r.field).startsWith('audit'))
    expect(editRules).toHaveLength(5)
    expect(editRules.every(r => r.props?.disabled && !r.validate?.length)).toBe(true)
    expect(
      recordPayload(all, options as any, cap, true, {
        '1': 'MANUAL',
        ...Object.fromEntries(audit.map(f => [f.id!, '伪造值']))
      })
    ).toEqual({ '1': 'MANUAL' })
  })
  it('带时区日期使用包含偏移的格式，普通日期时间保持原协议', () => {
    const temporal = [
      { id: 'z', code: 'z', name: '带时区', type: 'DATETIME' },
      { id: 'n', code: 'n', name: '普通时间', type: 'DATETIME' }
    ] as ObjectField[]
    const rules = recordRules(temporal, { z: { nativeType: 'timestamp(6) with time zone' } } as any, cap, true)
    expect(rules[0].props?.valueFormat).toBe('YYYY-MM-DDTHH:mm:ssZ')
    expect(rules[1].props?.valueFormat).toBe('YYYY-MM-DDTHH:mm:ss')
    expect(
      recordPayload(temporal, {}, cap, false, { z: '2026-09-06T12:30:00+08:00', n: '2026-09-06T12:30:00' })
    ).toEqual({ z: '2026-09-06T12:30:00+08:00', n: '2026-09-06T12:30:00' })
  })
  it('地区和级联使用已有选项的多选控件，提交数组', () => {
    const choice = [
      { id: 'r', code: 'r', name: '地区', type: 'REGION', required: true },
      { id: 'c', code: 'c', name: '级联', type: 'CASCADE', required: true }
    ] as ObjectField[]
    const options = {
      r: { options: [{ code: 'cn', label: '中国', disabled: false }] },
      c: { options: [{ code: 'a', label: '分类A', disabled: false }] }
    }
    expect(
      recordRules(choice, options as any, cap, true).every(r => r.type === 'select' && r.props?.mode === 'multiple')
    ).toBe(true)
    for (const rule of recordRules(choice, options as any, cap, true))
      expect(rule.validate).toEqual([expect.objectContaining({ required: true, type: 'array' })])
    expect(recordPayload(choice, options as any, cap, true, { r: ['cn'], c: ['a'] })).toEqual({ r: ['cn'], c: ['a'] })
  })
  it('布尔筛选传布尔值，清空条件不会误伤 false，文本与大整数不被转换', () => {
    expect(recordFilterValue(fields[3], 'false')).toBe(false)
    expect(recordFilterValue(fields[3], 'true')).toBe(true)
    expect(recordFilterValue(fields[3], undefined)).toBeUndefined()
    expect(recordFilterValue(fields[0], 'false')).toBe('false')
    expect(recordFilterValue(fields[1], '9007199254740993.25')).toBe('9007199254740993.25')
    expect(() => recordFilterValue(fields[3], '其他')).toThrow('请选择是或否')
  })
  it('新建默认值保留小数精度且不会填入不可写字段', () => {
    const options = { '2': { defaultValue: '9007199254740993.25' }, '4': { defaultValue: 'false' } }
    expect(recordDefaults(fields, options as any, cap)).toEqual({ '2': '9007199254740993.25', '4': false })
    expect(recordDefaults(fields, options as any, { ...cap, writeFields: ['4'] })).toEqual({ '4': false })
  })
  it('只读字段不被旧表单值回写，允许字段仍可保存', () => {
    const restricted = { ...cap, writeFields: ['4'] }
    expect(recordPayload(fields, {}, restricted, false, { '2': '秘密金额', '4': false })).toEqual({ '4': false })
    expect(recordRules(fields, {}, restricted, false).find(r => r.field === '2')?.props?.disabled).toBe(true)
  })
  it('空可写字段清单不会退化成全部可写', () => {
    expect(recordPayload(fields, {}, { ...cap, writeFields: [] }, true, { '1': 'KEY', '2': '100' })).toEqual({})
  })
  it('编辑不回写主键与计算字段，并保留金额字符串和 false', () => {
    expect(
      recordPayload(fields, {}, cap, false, { '1': 'KEY', '2': '9007199254740993.25', '3': '100', '4': false })
    ).toEqual({ '2': '9007199254740993.25', '4': false })
  })
  it('新增未填写字段保留数据库默认值的机会', () => {
    expect(recordPayload(fields, {}, cap, true, { '1': 'KEY', '2': '', '4': false })).toEqual({
      '1': 'KEY',
      '4': false
    })
  })
  it('数值使用字符串输入，编辑主键禁用', () => {
    const rules = recordRules(fields, {}, cap, false)
    expect(rules.find(r => r.field === '2')?.type).toBe('input')
    expect(rules.find(r => r.field === '1')?.props?.disabled).toBe(true)
    expect(rules.find(r => r.field === '3')?.props?.disabled).toBe(true)
  })
})
