import {
  ReportPivotPercent,
  type ReportMetric,
  type ReportPivotCell,
  type ReportPivotHeader,
  type ReportPivotOptions,
  type ReportPivotResult
} from '@/types/nocode/report'
import { formatReportValue } from './report-presentation'
import type { ReportSortTarget } from './report-sort'

export type PivotKeys = (string | null)[]
export type PivotLevel = 'leaf' | 'subtotal' | 'total'
/** 点击透视格发出的下钻意图：行键前缀 → group，列键前缀 → columnGroup。 */
export interface PivotSelection {
  rowKeys: PivotKeys
  columnKeys: PivotKeys
  metricId: string
  label: string
  values: Record<string, string | null>
}
export interface PivotColumnGroup {
  id: string
  keys: PivotKeys
  labels: string[]
  level: PivotLevel
}
export interface PivotHeaderCell {
  id: string
  text: string
  colspan: number
  rowspan: number
  level: PivotLevel | 'dimension' | 'metric'
  /** 可点击排序的表头格：行维度名称格按该层维度值；指标格按所在列组（含小计、合计列组）的该指标。 */
  sort?: ReportSortTarget
}
export interface PivotRowHeader {
  text: string
  rowspan: number
  colspan: number
  /** 所在行表头列序号；0 为固定首列。 */
  column: number
  /** 可折叠的上层行分组键（每一层非叶子行分组的表头格都有；叶子层没有）。 */
  toggle?: string
}
export interface PivotBodyRow {
  id: string
  keys: PivotKeys
  labels: string[]
  level: PivotLevel
  headers: PivotRowHeader[]
}

const id = (keys: PivotKeys) => JSON.stringify(keys)
export const cellId = (rowKeys: PivotKeys, columnKeys: PivotKeys) => JSON.stringify([rowKeys, columnKeys])

interface PivotBlock {
  keys: PivotKeys
  label: string
  labels: string[]
  items: ReportPivotHeader[]
}

/** 按第 level 层键分组（调用方保证 items 在前 level 层上相同），保持服务端给出的排序（首次出现顺序）。 */
function blocksAt(items: ReportPivotHeader[], level: number): PivotBlock[] {
  const groups = new Map<string, PivotBlock>()
  for (const item of items) {
    const keys = item.keys.slice(0, level + 1)
    const k = id(keys)
    const group = groups.get(k) ?? {
      keys,
      label: item.labels[level] ?? '',
      labels: item.labels.slice(0, level + 1),
      items: []
    }
    group.items.push(item)
    groups.set(k, group)
  }
  return [...groups.values()]
}

/**
 * 叶子列组 + 各层列组小计 + 行合计列组，顺序与表头一致：每个上层分组的小计紧随其最后一个叶子（同一叶子后先深层、再浅层）。
 * 列维度层数不限；服务端按各层原值排序，同一上层分组的叶子总是连续。
 */
export function pivotColumnGroups(pivot: ReportPivotResult, options: ReportPivotOptions): PivotColumnGroup[] {
  const depth = pivot.columnDimensionNames.length
  const groups: PivotColumnGroup[] = []
  if (!depth) return [{ id: id([]), keys: [], labels: [], level: 'leaf' }]
  const leaves = pivot.columns
  leaves.forEach((c, i) => {
    groups.push({ id: id(c.keys), keys: c.keys, labels: c.labels, level: 'leaf' })
    if (!options.subtotals) return
    for (let length = depth - 1; length >= 1; length--) {
      const prefix = c.keys.slice(0, length)
      const next = leaves[i + 1]
      if (!next || id(next.keys.slice(0, length)) !== id(prefix))
        groups.push({ id: id(prefix), keys: prefix, labels: c.labels.slice(0, length), level: 'subtotal' })
    }
  })
  if (options.rowTotals) groups.push({ id: id([]), keys: [], labels: [], level: 'total' })
  return groups
}

/** 列组在第 level 层的表头格：上层分组按键前缀归并；长度 = level 的小计/合计格落在本层并纵跨到指标层之上；更短的已被上方格覆盖。 */
function headerNode(group: PivotColumnGroup, level: number, depth: number) {
  if (group.keys.length > level)
    return {
      key: 'group:' + id(group.keys.slice(0, level + 1)),
      text: group.labels[level] ?? '',
      rowspan: 1,
      level: 'leaf' as PivotLevel
    }
  if (group.keys.length === level)
    return {
      key: 'summary:' + group.id + group.level,
      text: level === 0 ? '合计' : '小计',
      rowspan: depth - level,
      level: group.level
    }
  return undefined
}

/** 表头：列维度数层 + 指标层；行维度名称跨全部表头行。第 i 层上属于同一上层分组的相邻列组合并为一格。 */
export function pivotHeaderRows(pivot: ReportPivotResult, options: ReportPivotOptions, metrics: ReportMetric[]) {
  const depth = pivot.columnDimensionNames.length,
    size = metrics.length,
    groups = pivotColumnGroups(pivot, options)
  const rows: PivotHeaderCell[][] = Array.from({ length: depth + 1 }, () => [])
  pivot.rowDimensionNames.forEach((name, i) =>
    rows[0].push({
      id: 'dimension:' + i,
      text: name,
      colspan: 1,
      rowspan: depth + 1,
      level: 'dimension',
      sort: { dimension: i }
    })
  )
  for (let level = 0; level < depth; level++) {
    let start = 0
    while (start < groups.length) {
      const node = headerNode(groups[start], level, depth)
      if (!node) {
        start++
        continue
      }
      const key = node.key
      let end = start
      while (end + 1 < groups.length && headerNode(groups[end + 1], level, depth)?.key === key) end++
      rows[level].push({
        id: key,
        text: node.text,
        colspan: size * (end - start + 1),
        rowspan: node.rowspan,
        level: node.level
      })
      start = end + 1
    }
  }
  groups.forEach(g =>
    metrics.forEach(m =>
      rows[depth].push({
        id: 'metric:' + g.id + g.level + m.id,
        text: m.name,
        colspan: 1,
        rowspan: 1,
        level: 'metric',
        sort: { metricId: m.id, columnGroup: g.keys }
      })
    )
  )
  return rows
}

/** 全部上层行分组（长度 from..行维度数-1 的键前缀）的折叠键；from=1 即「全部折叠」，from=L 即「展开到第 L 层」。 */
export function pivotRowGroupIds(pivot: ReportPivotResult, from = 1) {
  const depth = pivot.rowDimensionNames.length
  const ids = new Set<string>()
  for (const r of pivot.rows)
    for (let length = Math.max(1, from); length < depth; length++) ids.add(id(r.keys.slice(0, length)))
  return ids
}

/** 行：叶子行、各层小计行（紧随其组，深层在前）、任意层可折叠；折叠的分组显示为一行该层小计。合计行另见 pivotTotalRow。 */
export function pivotBodyRows(pivot: ReportPivotResult, options: ReportPivotOptions, collapsed: Set<string>) {
  const depth = pivot.rowDimensionNames.length
  const leaf = (r: ReportPivotHeader): PivotBodyRow => ({
    id: id(r.keys),
    keys: r.keys,
    labels: r.labels,
    level: 'leaf',
    headers: [{ text: r.labels[depth - 1] ?? '', rowspan: 1, colspan: 1, column: Math.max(0, depth - 1) }]
  })
  if (depth <= 1) return pivot.rows.map(leaf)
  const build = (items: ReportPivotHeader[], level: number): PivotBodyRow[] => {
    if (level === depth - 1) return items.map(leaf)
    return blocksAt(items, level).flatMap(block => {
      const group = id(block.keys)
      const head = { text: block.label, rowspan: 1, colspan: 1, column: level, toggle: group }
      if (collapsed.has(group))
        return [
          {
            id: group + ':collapsed',
            keys: block.keys,
            labels: [...block.labels, '小计'],
            level: 'subtotal' as PivotLevel,
            headers: [
              head,
              {
                text: '小计（已折叠 ' + block.items.length + ' 行）',
                rowspan: 1,
                colspan: depth - level - 1,
                column: level + 1
              }
            ]
          }
        ]
      const inner = build(block.items, level + 1)
      const rows: PivotBodyRow[] = options.subtotals
        ? [
            ...inner,
            {
              id: group + ':subtotal',
              keys: block.keys,
              labels: [...block.labels, '小计'],
              level: 'subtotal',
              headers: [{ text: '小计', rowspan: 1, colspan: depth - level - 1, column: level + 1 }]
            }
          ]
        : inner
      return rows.map((row, i) =>
        i === 0 ? { ...row, headers: [{ ...head, rowspan: rows.length }, ...row.headers] } : row
      )
    })
  }
  return build(pivot.rows, 0)
}

export function pivotTotalRow(pivot: ReportPivotResult): PivotBodyRow {
  return {
    id: 'total',
    keys: [],
    labels: ['合计'],
    level: 'total',
    headers: [{ text: '合计', rowspan: 1, colspan: Math.max(1, pivot.rowDimensionNames.length), column: 0 }]
  }
}

/** 显示文本：null 与缺格显示为空（不是 0）；占比为 0..1，按百分比沿用指标小数位。 */
export function pivotCellText(cell: ReportPivotCell | undefined, metric: ReportMetric, percent: ReportPivotPercent) {
  if (!cell) return ''
  if (percent !== ReportPivotPercent.NONE) {
    const ratio = cell.ratios?.[metric.id]
    return ratio == null
      ? ''
      : formatReportValue(ratio, { ...metric, format: { decimals: metric.format?.decimals ?? null, percent: true } })
  }
  const value = cell.values[metric.id]
  return value == null ? '' : formatReportValue(value, metric)
}

/** 行数超过这个数才窗口化渲染（只为看得见的行建 DOM）；不超过时整表渲染，与原先完全一致。 */
export const PIVOT_WINDOW_THRESHOLD = 200
/**
 * 窗口化时的行高（像素）：--data-table-* 的字号 13px × 行高 1.5 + 上下内边距 12px + 下边框 1px。
 * 行内不换行，每行一样高；组件挂载后会按实际行高校准。
 */
export const PIVOT_ROW_HEIGHT = 32.5
/** 可见区域上下各多渲染的行数，滚动时不露白。 */
export const PIVOT_WINDOW_OVERSCAN = 15

export interface PivotRowWindow {
  start: number
  end: number
  /** 窗口之前、之后被省略的行所占的高度（像素），用占位行撑开滚动条。 */
  before: number
  after: number
}

/** 按滚动位置算出要渲染的行区间 [start, end)。 */
export function pivotRowWindow(
  count: number,
  scrollTop: number,
  viewport: number,
  rowHeight = PIVOT_ROW_HEIGHT
): PivotRowWindow {
  const size = Math.ceil(viewport / rowHeight) + PIVOT_WINDOW_OVERSCAN * 2
  const start = Math.max(0, Math.min(Math.floor(scrollTop / rowHeight) - PIVOT_WINDOW_OVERSCAN, count - size))
  const end = Math.min(count, start + size)
  return { start, end, before: start * rowHeight, after: (count - end) * rowHeight }
}

export interface PivotRowSpan {
  start: number
  end: number
  header: PivotRowHeader
}

/** 多层行维度时跨多行合并的上层行表头（rowspan > 1）各自覆盖的行区间。 */
export function pivotRowSpans(rows: PivotBodyRow[]): PivotRowSpan[] {
  const spans: PivotRowSpan[] = []
  rows.forEach((row, index) =>
    row.headers.forEach(header => {
      if (header.rowspan > 1) spans.push({ start: index, end: index + header.rowspan, header })
    })
  )
  return spans
}

/**
 * 窗口内的行。合并的上层行表头如果起点在窗口之前，补到窗口首行；跨度一律截到窗口内，避免表格被撑出窗口之外的行。
 * 行的键、层级、数值格都不变，所以下钻、折叠与整表渲染时相同。
 */
export function pivotWindowRows(rows: PivotBodyRow[], spans: PivotRowSpan[], start: number, end: number) {
  const open = spans
    .filter(span => span.start < start && span.end > start)
    .sort((a, b) => a.header.column - b.header.column)
    .map(span => ({ ...span.header, rowspan: Math.min(span.end, end) - start }))
  return rows.slice(start, end).map((row, offset) => {
    const room = end - start - offset
    const clipped = row.headers.some(header => header.rowspan > room)
    if (!clipped && (offset > 0 || !open.length)) return row
    const headers = row.headers.map(header => (header.rowspan > room ? { ...header, rowspan: room } : header))
    return { ...row, headers: offset === 0 ? [...open, ...headers] : headers }
  })
}
