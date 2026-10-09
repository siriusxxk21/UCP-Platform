import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { recordNumberError } from './record-number'

/** 读取电子表格复制的 TSV；引号内换行保留，列数不匹配不能静默错位。 */
export function clipboardRows(text: string): string[][] {
  if (text.length > 1_000_000) throw new Error('粘贴内容超过 1 MB，请分批录入')
  const rows: string[][] = []
  let row: string[] = [],
    cell = '',
    quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!
    if (c === '"' && (quoted || cell === '')) {
      if (quoted && text[i + 1] === '"') {
        cell += '"'
        i++
      } else quoted = !quoted
    } else if (!quoted && (c === '\t' || c === '\n' || c === '\r')) {
      row.push(cell)
      cell = ''
      if (c !== '\t') {
        rows.push(row)
        row = []
        if (c === '\r' && text[i + 1] === '\n') i++
      }
    } else cell += c
  }
  if (quoted) throw new Error('粘贴内容的引号未闭合')
  if (cell || row.length) {
    row.push(cell)
    rows.push(row)
  }
  return rows
}
export const pasteableTypes = new Set([
  'TEXT',
  'TEXTAREA',
  'INTEGER',
  'DECIMAL',
  'MONEY',
  'PERCENT',
  'BOOLEAN',
  'SELECT',
  'DATE'
])
export function previewDetailPaste(
  text: string,
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  existingCount: number
) {
  const errors: string[] = [],
    values: Record<string, unknown>[] = []
  const rows = clipboardRows(text)
  if (!fields.length) errors.push('请先选择粘贴列')
  if (!rows.length) errors.push('请先粘贴内容')
  if (existingCount + rows.length > 500) errors.push('每组明细最多 500 行，请减少本次粘贴行数')
  rows.slice(0, 500).forEach((row, index) => {
    if (row.length !== fields.length) {
      errors.push(`第 ${index + 1} 行有 ${row.length} 列，需要 ${fields.length} 列`)
      return
    }
    const result: Record<string, unknown> = {}
    fields.forEach((field, column) => {
      const raw = row[column]!,
        option = options[field.id!]
      let value: unknown = raw === '' ? null : raw
      let error = ''
      if (!pasteableTypes.has(field.type)) error = '请在表单中选择或填写此字段'
      else if (value == null) {
        if (field.required) error = '不能为空'
      } else if (field.type === 'BOOLEAN') {
        if (['true', '是', '1'].includes(raw.toLowerCase())) value = true
        else if (['false', '否', '0'].includes(raw.toLowerCase())) value = false
        else error = '请使用是／否或 true／false'
      } else if (field.type === 'SELECT') {
        const choices = (option?.options || []).filter(o => !o.disabled && (o.code === raw || o.label === raw))
        if (choices.length !== 1) error = '选项不存在或名称不唯一，请使用有效编码'
        else value = choices[0]!.code
      } else if (field.type === 'DATE') {
        const date = new Date(raw + 'T00:00:00Z')
        if (
          !/^\d{4}-\d{2}-\d{2}$/.test(raw) ||
          !Number.isFinite(date.getTime()) ||
          date.toISOString().slice(0, 10) !== raw
        )
          error = '请使用有效日期 YYYY-MM-DD'
      } else error = recordNumberError(field, option, value) || ''
      if (!error && ['TEXT', 'TEXTAREA'].includes(field.type) && field.length && raw.length > field.length)
        error = `最多 ${field.length} 个字符`
      if (error) errors.push(`第 ${index + 1} 行 · ${field.name}：${error}`)
      result[field.id!] = value
    })
    values.push(result)
  })
  return { rows: values, errors, count: rows.length }
}

/** Enter / Alt+方向键移动；输入法、文本域、选择器继续使用控件自身的键盘行为。 */
export function moveDetailFocus(event: KeyboardEvent, root: HTMLElement) {
  const input = event.target
  if (event.isComposing || !(input instanceof HTMLInputElement) || input.getAttribute('role') === 'combobox') return
  const rows = Array.from(root.querySelectorAll<HTMLElement>('[data-row-key]'))
  const row = input.closest<HTMLElement>('[data-row-key]')
  if (!row) return
  const controls = (element: HTMLElement) =>
    Array.from(
      element.querySelectorAll<HTMLInputElement>('input:not([disabled]):not([type="hidden"]):not([role="combobox"])')
    ).filter(control => control.getClientRects().length > 0)
  const inputs = rows.flatMap(controls),
    index = inputs.indexOf(input)
  let target: HTMLInputElement | undefined
  if (event.key === 'Enter' && !event.altKey && !event.ctrlKey && !event.metaKey)
    target = inputs[index + (event.shiftKey ? -1 : 1)]
  if (event.altKey && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
    const nextRow = rows[rows.indexOf(row) + (event.key === 'ArrowUp' ? -1 : 1)]
    if (nextRow) target = controls(nextRow)[controls(row).indexOf(input)]
  }
  if (target) {
    event.preventDefault()
    target.focus()
    target.select()
  }
}
