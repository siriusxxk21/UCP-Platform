/** Markdown 测试用例表格要求的固定表头。 */
export const TEST_CASE_MARKDOWN_HEADERS = ['名称', '优先级', '前置条件', '操作步骤', '预期结果'] as const

/** 单次批量导入允许的最大用例数。 */
export const MAX_TEST_CASE_IMPORT_COUNT = 200

/** Markdown 表格解析后的测试用例。 */
export interface ParsedTestCaseRow {
  sourceLine: number
  name: string
  priority: number
  priorityLabel: string
  precondition: string
  steps: string
  expectedResult: string
}

/** Markdown 表格解析结果，存在 errors 时不得提交后端。 */
export interface TestCaseMarkdownParseResult {
  rows: ParsedTestCaseRow[]
  errors: string[]
}

const PRIORITY_MAP: Record<string, { value: number; label: string }> = {
  p0: { value: 1, label: 'P0' },
  p1: { value: 2, label: 'P1' },
  p2: { value: 3, label: 'P2' },
  高: { value: 1, label: 'P0' },
  中: { value: 2, label: 'P1' },
  低: { value: 3, label: 'P2' }
}

/**
 * 解析从剪贴板粘贴的 Markdown 表格。
 *
 * 仅接收固定五列表头，避免列错位造成数据静默写入错误字段。单元格中的 `\|` 作为普通竖线处理，
 * `<br>`、`<br/>` 和 `<br />` 转换为换行，便于在步骤中表达多行内容。
 */
export function parseTestCaseMarkdown(markdown: string): TestCaseMarkdownParseResult {
  const lines = markdown
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map((content, index) => ({ content, lineNumber: index + 1 }))
    .filter(line => line.content.trim())

  if (!lines.length) {
    return { rows: [], errors: ['请粘贴包含表头、分隔行和至少一条数据的 Markdown 表格'] }
  }

  const errors: string[] = []
  const rows: ParsedTestCaseRow[] = []
  let tableCount = 0
  let currentTableHasRows = false
  let expectingSeparator = false
  let insideTable = false

  for (const line of lines) {
    const cells = splitMarkdownRow(line.content)
    if (isExpectedHeader(cells)) {
      if (insideTable && !currentTableHasRows) {
        errors.push(`第 ${line.lineNumber} 行之前的 Markdown 表格中没有可导入的数据行`)
      }
      tableCount += 1
      currentTableHasRows = false
      expectingSeparator = true
      insideTable = true
      continue
    }

    if (expectingSeparator) {
      if (!isSeparatorRow(cells)) {
        errors.push(`第 ${line.lineNumber} 行必须是五列 Markdown 分隔行，例如 |---|---|---|---|---|`)
        insideTable = false
      }
      expectingSeparator = false
      continue
    }

    if (!isMarkdownTableRow(line.content)) {
      if (insideTable && !currentTableHasRows) {
        errors.push(`第 ${line.lineNumber} 行之前的 Markdown 表格中没有可导入的数据行`)
      }
      insideTable = false
      continue
    }

    if (!insideTable) {
      errors.push(`第 ${line.lineNumber} 行表头必须为：${TEST_CASE_MARKDOWN_HEADERS.join(' | ')}`)
      continue
    }

    if (cells.length !== TEST_CASE_MARKDOWN_HEADERS.length) {
      errors.push(`第 ${line.lineNumber} 行应包含 5 列，实际为 ${cells.length} 列`)
      continue
    }
    currentTableHasRows = true

    const [name, priorityText, precondition, steps, expectedResult] = cells.map(normalizeCell)
    const priority = PRIORITY_MAP[priorityText.toLowerCase()]
    if (!name) {
      errors.push(`第 ${line.lineNumber} 行“名称”不能为空`)
    } else if (name.length > 255) {
      errors.push(`第 ${line.lineNumber} 行“名称”不能超过 255 个字符`)
    }
    if (!priority) {
      errors.push(`第 ${line.lineNumber} 行“优先级”仅支持 P0、P1、P2 或高、中、低`)
    }
    if (name && name.length <= 255 && priority) {
      rows.push({
        sourceLine: line.lineNumber,
        name,
        priority: priority.value,
        priorityLabel: priority.label,
        precondition,
        steps,
        expectedResult
      })
    }
  }

  if (expectingSeparator) {
    errors.push('Markdown 表格缺少五列分隔行，例如 |---|---|---|---|---|')
  }
  if (insideTable && !currentTableHasRows) {
    errors.push('Markdown 表格中没有可导入的数据行')
  }
  if (!tableCount) {
    errors.push(`第 ${lines[0].lineNumber} 行表头必须为：${TEST_CASE_MARKDOWN_HEADERS.join(' | ')}`)
  }
  if (rows.length > MAX_TEST_CASE_IMPORT_COUNT) {
    errors.push(`单次最多导入 ${MAX_TEST_CASE_IMPORT_COUNT} 条测试用例`)
  }

  return { rows: errors.length ? [] : rows, errors }
}

function splitMarkdownRow(line: string) {
  let content = line.trim()
  if (content.startsWith('|')) content = content.slice(1)
  if (content.endsWith('|') && !content.endsWith('\\|')) content = content.slice(0, -1)

  const cells: string[] = []
  let current = ''
  let escaped = false
  for (const char of content) {
    if (escaped) {
      current += char === '|' ? '|' : `\\${char}`
      escaped = false
    } else if (char === '\\') {
      escaped = true
    } else if (char === '|') {
      cells.push(current.trim())
      current = ''
    } else {
      current += char
    }
  }
  if (escaped) current += '\\'
  cells.push(current.trim())
  return cells
}

function normalizeCell(value: string) {
  return value.replace(/<br\s*\/?\s*>/gi, '\n').trim()
}

function isMarkdownTableRow(line: string) {
  return line.trim().startsWith('|')
}

function isExpectedHeader(cells: string[]) {
  return (
    cells.length === TEST_CASE_MARKDOWN_HEADERS.length &&
    cells.every((cell, index) => normalizeCell(cell) === TEST_CASE_MARKDOWN_HEADERS[index])
  )
}

function isSeparatorRow(cells: string[]) {
  return (
    cells.length === TEST_CASE_MARKDOWN_HEADERS.length &&
    cells.every(cell => /^:?-{3,}:?$/.test(cell.replace(/\s/g, '')))
  )
}
