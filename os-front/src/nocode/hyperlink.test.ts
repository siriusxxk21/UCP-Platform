import { describe, expect, it } from 'vitest'
import { hyperlinkError, hyperlinkHref, hyperlinkParts } from './hyperlink'
import { basicQuery } from './runtime-list'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType } from '@/types/nocode/enums'
describe('超链接与多值查询', () => {
  it.each([
    'javascript:alert(1)',
    'data:text/html,hello',
    'ftp://example.com',
    '//example.com',
    'https://user:pass@example.com',
    'https://example.com/a b',
    'https://example.com\\a'
  ])('拒绝不安全或无效地址 %s', address => {
    expect(hyperlinkError(address)).toBeTruthy()
    expect(hyperlinkHref(address)).toBeUndefined()
  })
  it('兼容旧字符串并保留显示文字和查询参数', () => {
    expect(hyperlinkHref({ link: ' https://example.com/a?q=1 ', text: '官网' })).toBe('https://example.com/a?q=1')
    expect(hyperlinkParts('https://example.com')).toEqual({ link: 'https://example.com', text: '' })
    expect(hyperlinkError({ link: '', text: '缺少地址' })).toBeTruthy()
    expect(hyperlinkError({ link: '', text: '' })).toBeNull()
    expect(hyperlinkError({ link: 'https://example.com', text: 'x'.repeat(501) })).toBeTruthy()
  })
  it('单值字段选择多个字典项使用 IN，并保留旧多对多关系契约', () => {
    const fields = [
      { id: 'status', type: FieldType.SELECT },
      { id: 'relation_1', type: FieldType.MULTI_SELECT }
    ] as ObjectField[]
    expect(basicQuery(fields, { status: ['A', 'B'], relation_1: ['10', '11'] })).toEqual({
      equal: { relation_1: ['10', '11'] },
      conditions: { logic: 'AND', items: [{ type: 'condition', field: 'status', operator: 'in', value: ['A', 'B'] }] }
    })
  })
})
