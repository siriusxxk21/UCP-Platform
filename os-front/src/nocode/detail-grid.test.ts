import { describe, expect, it } from 'vitest'
import { clipboardRows, previewDetailPaste } from './detail-grid'
import type { ObjectField } from '@/types/nocode/object'
const defaults = { key: 'test', length: null, precision: null, scale: null, required: false, unique: false, sort: 0 }
const fields: ObjectField[] = [
  { ...defaults, id: 'name', code: 'name', name: '名称', type: 'TEXT', length: 20 },
  { ...defaults, id: 'amount', code: 'amount', name: '金额', type: 'DECIMAL', precision: 30, scale: 2, required: true }
]
describe('明细粘贴预检查', () => {
  it('保留空单元格、引号内换行与制表符', () => {
    expect(clipboardRows('"第一行\n第二行"\t"a\tb"\t\r\n')).toEqual([['第一行\n第二行', 'a\tb', '']])
    expect(clipboardRows('"a""b"\t1')).toEqual([['a"b', '1']])
    expect(() => clipboardRows('"未闭合')).toThrow('未闭合')
  })
  it('混合有效与无效行时反馈具体行列；不允许列错位', () => {
    const result = previewDetailPaste('正常\t12.30\n缺列\n错误\t12.345', fields, {}, 0)
    expect(result.errors).toHaveLength(2)
    expect(result.errors[0]).toContain('第 2 行')
    expect(result.errors[1]).toContain('第 3 行 · 金额')
  })
  it('金额保持十进制字符串和大整数精度', () => {
    const result = previewDetailPaste('原料\t9007199254740993.12', fields, {}, 0)
    expect(result.errors).toEqual([])
    expect(result.rows[0]).toEqual({ name: '原料', amount: '9007199254740993.12' })
  })
  it('既有行与新增行合计受 500 行限制', () => {
    expect(previewDetailPaste('a\t1\nb\t2', fields, {}, 499).errors).toContain(
      '每组明细最多 500 行，请减少本次粘贴行数'
    )
    expect(previewDetailPaste('a\t1', fields, {}, 499).errors).toEqual([])
  })
  it('布尔、选项、日期使用明确格式并拒绝歧义', () => {
    const fields: ObjectField[] = [
      { ...defaults, id: 'enabled', code: 'enabled', name: '启用', type: 'BOOLEAN' },
      { ...defaults, id: 'date', code: 'date', name: '日期', type: 'DATE' },
      { ...defaults, id: 'status', code: 'status', name: '状态', type: 'SELECT' }
    ]
    const options = { status: { options: [{ code: 'A', label: '进行中', disabled: false }] } } as any
    expect(previewDetailPaste('否\t2026-09-12\t进行中', fields, options, 0).rows[0]).toEqual({
      enabled: false,
      date: '2026-09-12',
      status: 'A'
    })
    expect(previewDetailPaste('未知\t2026-02-30\t无效', fields, options, 0).errors).toHaveLength(3)
  })
  it('预检查拒绝过大输入、无列和空内容', () => {
    expect(() => clipboardRows('a'.repeat(1_000_001))).toThrow('1 MB')
    expect(previewDetailPaste('', [], {}, 0).errors).toHaveLength(2)
    expect(previewDetailPaste('名称\t', fields, {}, 0).errors[0]).toContain('金额')
  })
})
