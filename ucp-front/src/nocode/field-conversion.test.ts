import { describe, expect, it } from 'vitest'
import type { FieldConversion } from '@/types/nocode/data-center'
import {
  conversionClearFieldIds,
  conversionConfirmationError,
  conversionImpactRoute,
  conversionPublishLabel,
  conversionTypeLabel
} from './field-conversion'

const conversion = (fieldId: string, affectedRows: number): FieldConversion => ({
  fieldId,
  detailId: null,
  fieldName: '分类',
  sourceName: '主表',
  fromType: 'varchar(50)',
  toType: 'bigint',
  affectedRows,
  deletedRows: 0,
  masked: false,
  fingerprint: 'revision',
  clearAllowed: true,
  impacts: []
})

describe('字段清空转换确认', () => {
  it('相同物理类型仍能区分整数与对象引用，旧计划兼容物理类型标签', () => {
    expect(conversionTypeLabel('INTEGER', 'bigint')).toBe('整数')
    expect(conversionTypeLabel('REFERENCE', 'bigint')).toBe('单选（对象引用）')
    expect(conversionTypeLabel('SELECT', 'varchar(200)')).toBe('单选')
    expect(conversionTypeLabel(undefined, 'varchar(200)')).toBe('单行文本')
  })
  it('空列不要求清空权限，发布只授权当前非空清列计划且拒绝计划外字段', () => {
    expect(conversionConfirmationError([conversion('1', 0)], [], false)).toBeNull()
    const changes = [conversion('1', 2), conversion('2', 1)]
    expect(conversionConfirmationError(changes, ['1'], true)).toContain('不一致')
    expect(conversionConfirmationError(changes, ['1', '2'], false)).toContain('管理权限')
    expect(conversionConfirmationError(changes, ['1', '2'], true)).toBeNull()
    expect(conversionConfirmationError(changes, ['1', '2', '3'], true)).toContain('不一致')
  })
  it('最终发布明确全部清空和暂停后果，只传待清空列而非保留值或空列', () => {
    const changes = [
      conversion('1', 2),
      conversion('2', 1),
      conversion('3', 0),
      { ...conversion('4', 10), action: 'PRESERVE_VALUES' as const }
    ]
    expect(conversionClearFieldIds(changes)).toEqual(['1', '2'])
    expect(conversionPublishLabel(changes, 2)).toBe('清空 2 列共 3 个值、暂停 2 个应用并发布')
    expect(conversionPublishLabel([conversion('1', 3)])).toBe('清空本列 3 个值并发布')
    expect(conversionPublishLabel([], 1)).toBe('暂停 1 个应用并发布')
    expect(conversionPublishLabel([])).toBe('确认发布')
  })
  it('有其他影响不能靠勾选清空绕过，处理入口仅接受站内管理地址', () => {
    expect(conversionConfirmationError([{ ...conversion('1', 2), clearAllowed: false }], ['1'], true)).toContain('影响')
    expect(conversionImpactRoute('/nocode/application/editor?id=2')).toBe('/nocode/application/editor?id=2')
    expect(conversionImpactRoute('/nocode-app/workspace?id=2')).toBe('/nocode-app/workspace?id=2')
    expect(conversionImpactRoute('https://example.com')).toBeNull()
    expect(conversionImpactRoute('//example.com')).toBeNull()
  })
  it('保留值转换不请求清空授权，失败值阻止发布', () => {
    const preserved = { ...conversion('1', 2), action: 'PRESERVE_VALUES' as const, conversionRule: '精确保留' }
    expect(conversionConfirmationError([preserved], [], false)).toBeNull()
    expect(conversionConfirmationError([preserved], ['1'], true)).toContain('不一致')
    expect(conversionConfirmationError([{ ...preserved, failedRows: 1 }], [], true)).toContain('无法')
    expect(
      conversionConfirmationError([{ ...conversion('2', 2), action: 'CLEAR_COLUMN', failedRows: 1 }], ['2'], true)
    ).toBeNull()
  })
  it('纯约束调整保持旧值，无需清空权限且不能用确认清空绕过冲突', () => {
    const constrained = { ...conversion('1', 2), action: 'KEEP_COLUMN' as const }
    expect(conversionConfirmationError([constrained], [], false)).toBeNull()
    expect(conversionConfirmationError([constrained], ['1'], true)).toContain('不一致')
    expect(conversionConfirmationError([{ ...constrained, failedRows: 1 }], [], true)).toContain('无法')
  })
})
