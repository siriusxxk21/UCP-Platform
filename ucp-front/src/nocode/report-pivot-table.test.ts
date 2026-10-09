// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import ReportPivotTable from '@/views/nocode/application/components/ReportPivotTable.vue'
import { defaultReport } from './report'
import type { PivotSelection } from './report-pivot'
import {
  ReportDisplay,
  type ReportConfig,
  type ReportMetric,
  type ReportPivotCell,
  type ReportPivotOptions,
  type ReportPivotResult,
  type ReportResult
} from '@/types/nocode/report'

const metrics: ReportMetric[] = [
  { id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount', format: { decimals: 0 } },
  { id: 'out', name: '出金', operation: 'SUM', fieldId: 'amount', format: { decimals: 1 } }
]
const cell = (
  rowKeys: (string | null)[],
  columnKeys: (string | null)[],
  values: Record<string, string | null>,
  ratios?: Record<string, string | null>
): ReportPivotCell => ({ rowKeys, columnKeys, values, ...(ratios ? { ratios } : {}) })
function config(pivot: Partial<ReportPivotOptions> = {}): ReportConfig {
  return {
    ...defaultReport('flow'),
    display: ReportDisplay.PIVOT,
    metrics,
    dimensions: [{ fieldId: 'company', relationPath: null, bucket: 'VALUE' }],
    columnDimensions: [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }],
    pivot: {
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE',
      maxColumnGroups: 24,
      ...pivot
    }
  }
}
/** 行 = 公司，列 = 月份；每个列组下挂 入金 / 出金。 */
function monthly(): ReportPivotResult {
  return {
    rowDimensionNames: ['公司'],
    columnDimensionNames: ['月份'],
    rows: [
      { keys: ['A'], labels: ['甲公司'] },
      { keys: ['B'], labels: ['乙公司'] }
    ],
    columns: [
      { keys: ['2026-08'], labels: ['2026-08'] },
      { keys: ['2026-09'], labels: ['2026-09'] }
    ],
    cells: [
      cell(['A'], ['2026-08'], { in: '100', out: '40' }),
      cell(['A'], ['2026-09'], { in: '0', out: null }),
      cell(['B'], ['2026-08'], { in: '50', out: '10' }),
      cell(['A'], [], { in: '100', out: '40' }),
      cell(['B'], [], { in: '50', out: '10' }),
      cell([], ['2026-08'], { in: '150', out: '50' }),
      cell([], ['2026-09'], { in: '0', out: null }),
      cell([], [], { in: '150', out: '50' })
    ],
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: 2,
    totalColumnGroups: 2
  }
}
/** 行两层（公司 → 部门）、列两层（年 → 月）。 */
function nested(): ReportPivotResult {
  return {
    rowDimensionNames: ['公司', '部门'],
    columnDimensionNames: ['年', '月'],
    rows: [
      { keys: ['A', 'd1'], labels: ['甲公司', '一部'] },
      { keys: ['A', 'd2'], labels: ['甲公司', '二部'] },
      { keys: ['B', 'd1'], labels: ['乙公司', '一部'] }
    ],
    columns: [
      { keys: ['2026', '08'], labels: ['2026', '8月'] },
      { keys: ['2026', '09'], labels: ['2026', '9月'] }
    ],
    cells: [
      cell(['A', 'd1'], ['2026', '08'], { in: '1', out: '1' }),
      cell(['A'], ['2026', '08'], { in: '3', out: '2' }),
      cell(['A', 'd1'], ['2026'], { in: '7', out: '5' }),
      cell(['A'], ['2026'], { in: '9', out: '8' }),
      cell([], [], { in: '99', out: '88' })
    ],
    rowsTruncated: true,
    columnsTruncated: true,
    totalRowGroups: 40,
    totalColumnGroups: 36
  }
}
/** 行三层（公司 → 部门 → 组）、列三层（年 → 月 → 类别）；列组 4 个叶子，行 4 个叶子。 */
function deep(): ReportPivotResult {
  const header = (keys: string[], labels: string[]) => ({ keys, labels })
  return {
    rowDimensionNames: ['公司', '部门', '组'],
    columnDimensionNames: ['年', '月', '类别'],
    rows: [
      header(['A', 'd1', 'g1'], ['甲公司', '一部', '一组']),
      header(['A', 'd1', 'g2'], ['甲公司', '一部', '二组']),
      header(['A', 'd2', 'g1'], ['甲公司', '二部', '一组']),
      header(['B', 'd1', 'g1'], ['乙公司', '一部', '一组'])
    ],
    columns: [
      header(['2025', '12', 'x'], ['2025', '12月', '现金']),
      header(['2026', '01', 'x'], ['2026', '01月', '现金']),
      header(['2026', '01', 'y'], ['2026', '01月', '转账']),
      header(['2026', '02', 'x'], ['2026', '02月', '现金'])
    ],
    cells: [
      cell(['A', 'd1', 'g1'], ['2025', '12', 'x'], { in: '1', out: '1' }),
      cell(['A', 'd1', 'g1'], ['2025'], { in: '2', out: '2' }),
      cell(['A', 'd1'], ['2026', '01'], { in: '5', out: '4' }),
      cell(['A', 'd1'], ['2026'], { in: '6', out: '5' }),
      cell(['A'], ['2026'], { in: '9', out: '8' }),
      cell(['A'], [], { in: '11', out: '10' }),
      cell([], ['2026', '01', 'y'], { in: '3', out: '3' }),
      cell([], [], { in: '99', out: '88' })
    ],
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: 4,
    totalColumnGroups: 4
  }
}
const result = (pivot: ReportPivotResult): ReportResult => ({
  dimensionNames: [],
  metrics,
  groups: [],
  totals: {},
  totalGroups: 0,
  recordCount: 3,
  canExport: false,
  timeZone: 'Asia/Shanghai',
  pivot
})

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(pivot: ReportPivotResult, options: Partial<ReportPivotOptions> = {}) {
  const select = vi.fn<(selection: PivotSelection) => void>()
  app = createApp(() =>
    h(ReportPivotTable, { config: config(options), result: result(pivot), pivot, onSelect: select })
  )
  app.component(
    'AAlert',
    defineComponent({ props: ['message'], setup: props => () => h('aside', { role: 'alert' }, props.message) })
  )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', { type: 'button' }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return select
}
const headerRows = () =>
  Array.from(host.querySelectorAll('thead tr')).map(tr =>
    Array.from(tr.querySelectorAll('th')).map(th => ({
      text: th.textContent!.trim(),
      colspan: Number(th.getAttribute('colspan') || 1),
      rowspan: Number(th.getAttribute('rowspan') || 1)
    }))
  )
const bodyRows = () => Array.from(host.querySelectorAll('tbody tr'))
const values = (row: Element) => Array.from(row.querySelectorAll('td')).map(td => td.textContent!.trim())
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('透视表格渲染', () => {
  it('两层列表头：列组（月份）→ 指标，列组各跨指标数，最右合计列组', async () => {
    await mount(monthly())
    expect(headerRows()).toEqual([
      [
        { text: '公司', colspan: 1, rowspan: 2 },
        { text: '2026-08', colspan: 2, rowspan: 1 },
        { text: '2026-09', colspan: 2, rowspan: 1 },
        { text: '合计', colspan: 2, rowspan: 1 }
      ],
      ['入金', '出金', '入金', '出金', '入金', '出金'].map(text => ({ text, colspan: 1, rowspan: 1 }))
    ])
  })
  it('按列组 × 指标取值；null 与缺格显示为空而不是 0，真实的 0 照常显示', async () => {
    await mount(monthly())
    const [a, b] = bodyRows()
    expect(a.querySelector('th')!.textContent!.trim()).toBe('甲公司')
    expect(values(a)).toEqual(['100', '40.0', '0', '', '100', '40.0'])
    // 乙公司 9 月无记录：缺格为空，不补 0。
    expect(values(b)).toEqual(['50', '10.0', '', '', '50', '10.0'])
    expect(b.querySelectorAll('td')[2].querySelector('button')).toBeNull()
  })
  it('合计行在表尾（tfoot），不混在数据行里', async () => {
    await mount(monthly())
    expect(bodyRows().map(r => r.getAttribute('data-row-level'))).toEqual(['leaf', 'leaf'])
    const total = host.querySelector('tfoot tr')!
    expect(total.querySelector('th')!.textContent!.trim()).toBe('合计')
    expect(values(total)).toEqual(['150', '50.0', '0', '', '150', '50.0'])
  })
  it('关闭行合计 / 列合计后不渲染对应列组与合计行', async () => {
    await mount(monthly(), { rowTotals: false, columnTotals: false })
    expect(headerRows()[0].map(h => h.text)).toEqual(['公司', '2026-08', '2026-09'])
    expect(host.querySelector('tfoot')).toBeNull()
  })
  it('行两层合并单元格 + 小计行；列两层时再加一层表头并带列组小计', async () => {
    await mount(nested())
    expect(headerRows()).toEqual([
      [
        { text: '公司', colspan: 1, rowspan: 3 },
        { text: '部门', colspan: 1, rowspan: 3 },
        { text: '2026', colspan: 6, rowspan: 1 },
        { text: '合计', colspan: 2, rowspan: 2 }
      ],
      [
        { text: '8月', colspan: 2, rowspan: 1 },
        { text: '9月', colspan: 2, rowspan: 1 },
        { text: '小计', colspan: 2, rowspan: 1 }
      ],
      ['入金', '出金', '入金', '出金', '入金', '出金', '入金', '出金'].map(text => ({ text, colspan: 1, rowspan: 1 }))
    ])
    const rows = bodyRows()
    expect(rows.map(r => r.getAttribute('data-row-level'))).toEqual(['leaf', 'leaf', 'subtotal', 'leaf', 'subtotal'])
    const merged = rows[0].querySelector('th')!
    expect(merged.textContent).toContain('甲公司')
    expect(merged.getAttribute('rowspan')).toBe('3')
    expect(rows[1].querySelectorAll('th')).toHaveLength(1)
    expect(rows[2].querySelector('th')!.textContent!.trim()).toBe('小计')
    // 小计列组位于该年的叶子列组之后、合计列组之前。
    expect(Array.from(rows[0].querySelectorAll('td')).map(td => td.getAttribute('data-column-level'))).toEqual([
      'leaf',
      'leaf',
      'leaf',
      'leaf',
      'subtotal',
      'subtotal',
      'total',
      'total'
    ])
    expect(values(rows[0])).toEqual(['1', '1.0', '', '', '7', '5.0', '', ''])
    expect(values(rows[2])).toEqual(['3', '2.0', '', '', '9', '8.0', '', ''])
  })
  it('关闭小计后不出小计行与小计列组，合并跨度随之缩小', async () => {
    await mount(nested(), { subtotals: false })
    expect(bodyRows().map(r => r.getAttribute('data-row-level'))).toEqual(['leaf', 'leaf', 'leaf'])
    expect(bodyRows()[0].querySelector('th')!.getAttribute('rowspan')).toBe('2')
    expect(headerRows()[1].map(h => h.text)).toEqual(['8月', '9月'])
  })
  it('行分组可折叠为小计行，再展开恢复明细行', async () => {
    await mount(nested())
    const toggle = host.querySelector<HTMLButtonElement>('button[aria-label="折叠 甲公司"]')!
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    toggle.click()
    await flush()
    expect(bodyRows().map(r => r.getAttribute('data-row-level'))).toEqual(['subtotal', 'leaf', 'subtotal'])
    expect(bodyRows()[0].textContent).toContain('已折叠 2 行')
    expect(values(bodyRows()[0])).toEqual(['3', '2.0', '', '', '9', '8.0', '', ''])
    host.querySelector<HTMLButtonElement>('button[aria-label="展开 甲公司"]')!.click()
    await flush()
    expect(bodyRows()).toHaveLength(5)
  })
  it('截断时在表格上方明确提示「列组仅显示前 N 个，共 M 个」，行同理', async () => {
    await mount(nested())
    const alerts = Array.from(host.querySelectorAll('[role="alert"]')).map(a => a.textContent)
    expect(alerts).toEqual(['列组仅显示前 2 个，共 36 个', '行仅显示前 3 个，共 40 个'])
    const notice = host.querySelector('.pivot-notices')!
    expect(notice.compareDocumentPosition(host.querySelector('table')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
  it('无列维度：后端给 columns=[{keys:[],labels:[]}]，只出指标一层表头，按 columnKeys=[] 取值与下钻', async () => {
    const pivot: ReportPivotResult = {
      ...monthly(),
      columnDimensionNames: [],
      columns: [{ keys: [], labels: [] }],
      cells: [cell(['A'], [], { in: '100', out: '40' }), cell([], [], { in: '150', out: null })]
    }
    const select = await mount(pivot)
    expect(headerRows()).toEqual([
      [
        { text: '公司', colspan: 1, rowspan: 1 },
        { text: '入金', colspan: 1, rowspan: 1 },
        { text: '出金', colspan: 1, rowspan: 1 }
      ]
    ])
    expect(values(bodyRows()[0])).toEqual(['100', '40.0'])
    expect(values(bodyRows()[1])).toEqual(['', ''])
    expect(values(host.querySelector('tfoot tr')!)).toEqual(['150', ''])
    bodyRows()[0].querySelector<HTMLButtonElement>('td button')!.click()
    expect(select.mock.calls[0][0]).toMatchObject({ rowKeys: ['A'], columnKeys: [], metricId: 'in' })
  })
  it('未截断时不提示', async () => {
    await mount(monthly())
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })
  it('占比显示 ratios（0..1）为百分比，沿用指标小数位；分母为 0 的 null 显示为空', async () => {
    const pivot = monthly()
    pivot.cells = [
      cell(['A'], ['2026-08'], { in: '100', out: '40' }, { in: '0.6667', out: '0.25' }),
      cell(['A'], ['2026-09'], { in: '0', out: null }, { in: null, out: null }),
      cell(['A'], [], { in: '150', out: '40' }, { in: '1', out: '1' })
    ]
    await mount(pivot, { percent: 'ROW' })
    // 入金 decimals=0 → 67%；出金 decimals=1 → 25.0%。
    expect(values(bodyRows()[0])).toEqual(['67%', '25.0%', '', '', '100%', '100.0%'])
    expect(bodyRows()[0].querySelector('td')!.getAttribute('title')).toBe('100')
  })
})

describe('透视格下钻', () => {
  it('叶子格：rowKeys → group，columnKeys → columnGroup，指标 → metricId', async () => {
    const select = await mount(monthly())
    bodyRows()[1].querySelectorAll('td')[1].querySelector('button')!.click()
    expect(select).toHaveBeenCalledOnce()
    expect(select.mock.calls[0][0]).toMatchObject({
      rowKeys: ['B'],
      columnKeys: ['2026-08'],
      metricId: 'out',
      label: '乙公司 · 2026-08'
    })
  })
  it('行小计格发出行键前缀，列小计格发出列键前缀，合计格发出空前缀', async () => {
    const select = await mount(nested())
    const rows = bodyRows()
    rows[2].querySelectorAll('td')[0].querySelector('button')!.click()
    rows[0].querySelectorAll('td')[4].querySelector('button')!.click()
    rows[2].querySelectorAll('td')[5].querySelector('button')!.click()
    host.querySelector('tfoot')!.querySelectorAll('td')[6].querySelector('button')!.click()
    expect(select.mock.calls.map(([s]) => [s.rowKeys, s.columnKeys, s.metricId])).toEqual([
      [['A'], ['2026', '08'], 'in'],
      [['A', 'd1'], ['2026'], 'in'],
      [['A'], ['2026'], 'out'],
      [[], [], 'in']
    ])
  })
  it('行合计列组的格发出 columnGroup=[]，列合计行的格发出 group=[]', async () => {
    const select = await mount(monthly())
    bodyRows()[0].querySelectorAll('td')[4].querySelector('button')!.click()
    host.querySelector('tfoot')!.querySelectorAll('td')[1].querySelector('button')!.click()
    expect(select.mock.calls.map(([s]) => [s.rowKeys, s.columnKeys, s.metricId])).toEqual([
      [['A'], [], 'in'],
      [[], ['2026-08'], 'out']
    ])
  })
})

describe('透视表任意层数（3 层行 × 3 层列）', () => {
  const levels = () => bodyRows().map(r => r.getAttribute('data-row-level'))
  const rowHeads = (row: Element) =>
    Array.from(row.querySelectorAll('th')).map(th => ({
      text: th.textContent!.replace(/[▾▸]/g, '').trim(),
      colspan: Number(th.getAttribute('colspan') || 1),
      rowspan: Number(th.getAttribute('rowspan') || 1)
    }))
  const click = async (label: string) => {
    host.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!.click()
    await flush()
  }
  const tool = async (text: string) => {
    Array.from(host.querySelectorAll<HTMLButtonElement>('.pivot-tools button'))
      .find(b => b.textContent!.trim() === text)!
      .click()
    await flush()
  }

  it('表头 4 层（3 层列维度 + 指标）：上层横跨其下全部列组与该组小计，各层小计/合计纵跨到指标层之上', async () => {
    await mount(deep())
    const cell = (text: string, colspan: number, rowspan = 1) => ({ text, colspan, rowspan })
    expect(headerRows()).toEqual([
      [cell('公司', 1, 4), cell('部门', 1, 4), cell('组', 1, 4), cell('2025', 6), cell('2026', 12), cell('合计', 2, 3)],
      [cell('12月', 4), cell('小计', 2, 2), cell('01月', 6), cell('02月', 4), cell('小计', 2, 2)],
      [
        cell('现金', 2),
        cell('小计', 2),
        cell('现金', 2),
        cell('转账', 2),
        cell('小计', 2),
        cell('现金', 2),
        cell('小计', 2)
      ],
      Array.from({ length: 20 }, (_, i) => cell(i % 2 ? '出金' : '入金', 1))
    ])
    // 列组区的跨度：第 1 层 6+12+2 = 20 = 10 个列组 × 2 个指标
    expect(
      headerRows()[0]
        .slice(3)
        .reduce((sum, h) => sum + h.colspan, 0)
    ).toBe(20)
    // 小计列组：月小计紧随该月叶子，年小计紧随该年最后一个月小计，合计在最右
    expect(
      Array.from(bodyRows()[0].querySelectorAll('td'))
        .filter((_, i) => i % 2 === 0)
        .map(td => td.getAttribute('data-column-level'))
    ).toEqual(['leaf', 'subtotal', 'subtotal', 'leaf', 'leaf', 'subtotal', 'leaf', 'subtotal', 'subtotal', 'total'])
    expect(values(bodyRows()[0])).toEqual(['1', '1.0', '', '', '2', '2.0', ...Array(12).fill(''), '', ''])
  })

  it('行三层：每层上层分组合并单元格，小计行紧随其组（深层先、浅层后），小计表头跨剩余行表头列', async () => {
    await mount(deep())
    expect(levels()).toEqual([
      'leaf',
      'leaf',
      'subtotal',
      'leaf',
      'subtotal',
      'subtotal',
      'leaf',
      'subtotal',
      'subtotal'
    ])
    const rows = bodyRows()
    expect(rowHeads(rows[0])).toEqual([
      { text: '甲公司', colspan: 1, rowspan: 6 },
      { text: '一部', colspan: 1, rowspan: 3 },
      { text: '一组', colspan: 1, rowspan: 1 }
    ])
    expect(rowHeads(rows[1])).toEqual([{ text: '二组', colspan: 1, rowspan: 1 }])
    expect(rowHeads(rows[2])).toEqual([{ text: '小计', colspan: 1, rowspan: 1 }])
    expect(rowHeads(rows[3])).toEqual([
      { text: '二部', colspan: 1, rowspan: 2 },
      { text: '一组', colspan: 1, rowspan: 1 }
    ])
    expect(rowHeads(rows[5])).toEqual([{ text: '小计', colspan: 2, rowspan: 1 }])
    expect(rowHeads(rows[6])).toEqual([
      { text: '乙公司', colspan: 1, rowspan: 3 },
      { text: '一部', colspan: 1, rowspan: 2 },
      { text: '一组', colspan: 1, rowspan: 1 }
    ])
    // 第 2 层行小计 × 第 2 层列小计、第 1 层行小计 × 第 1 层列小计
    expect(values(rows[2]).slice(10, 12)).toEqual(['5', '4.0'])
    expect(values(rows[5]).slice(16, 20)).toEqual(['9', '8.0', '11', '10.0'])
    expect(rowHeads(host.querySelector('tfoot tr')!)).toEqual([{ text: '合计', colspan: 3, rowspan: 1 }])
  })

  it('关闭小计：不出任何层的小计行与小计列组，表头与行头跨度随之缩小', async () => {
    await mount(deep(), { subtotals: false })
    expect(levels()).toEqual(['leaf', 'leaf', 'leaf', 'leaf'])
    expect(rowHeads(bodyRows()[0])[0]).toEqual({ text: '甲公司', colspan: 1, rowspan: 3 })
    expect(headerRows()[0].map(h => [h.text, h.colspan])).toEqual([
      ['公司', 1],
      ['部门', 1],
      ['组', 1],
      ['2025', 2],
      ['2026', 6],
      ['合计', 2]
    ])
    expect(headerRows()[2].map(h => h.text)).toEqual(['现金', '现金', '转账', '现金'])
  })

  it('任意层折叠/展开：折叠第 2 层分组为一行该层小计；折叠第 1 层；全部折叠后逐层展开；展开到「部门」', async () => {
    await mount(deep())
    await click('折叠 一部')
    expect(levels()).toEqual(['subtotal', 'leaf', 'subtotal', 'subtotal', 'leaf', 'subtotal', 'subtotal'])
    expect(rowHeads(bodyRows()[0])).toEqual([
      { text: '甲公司', colspan: 1, rowspan: 4 },
      { text: '一部', colspan: 1, rowspan: 1 },
      { text: '小计（已折叠 2 行）', colspan: 1, rowspan: 1 }
    ])
    expect(values(bodyRows()[0]).slice(10, 12)).toEqual(['5', '4.0'])
    await click('展开 一部')
    expect(bodyRows()).toHaveLength(9)
    await click('折叠 甲公司')
    expect(levels()).toEqual(['subtotal', 'leaf', 'subtotal', 'subtotal'])
    expect(rowHeads(bodyRows()[0])).toEqual([
      { text: '甲公司', colspan: 1, rowspan: 1 },
      { text: '小计（已折叠 3 行）', colspan: 2, rowspan: 1 }
    ])
    expect(values(bodyRows()[0]).slice(16, 20)).toEqual(['9', '8.0', '11', '10.0'])
    await tool('全部折叠')
    expect(bodyRows().map(r => r.textContent)).toEqual([
      expect.stringContaining('已折叠 3 行'),
      expect.stringContaining('已折叠 1 行')
    ])
    await click('展开 甲公司')
    // 展开第 1 层后第 2 层仍为折叠：一部、二部各一行小计 + 甲公司小计
    expect(levels()).toEqual(['subtotal', 'subtotal', 'subtotal', 'subtotal'])
    expect(rowHeads(bodyRows()[0])[0]).toEqual({ text: '甲公司', colspan: 1, rowspan: 3 })
    expect(bodyRows()[1].textContent).toContain('已折叠 1 行')
    await tool('展开到「部门」')
    expect(bodyRows().map(r => Array.from(r.querySelectorAll('th')).at(-1)!.textContent!.trim())).toEqual([
      '小计（已折叠 2 行）',
      '小计（已折叠 1 行）',
      '小计',
      '小计（已折叠 1 行）',
      '小计'
    ])
    await tool('全部展开')
    expect(bodyRows()).toHaveLength(9)
  })

  it('任意层小计格下钻：行键前缀 → group，列键前缀 → columnGroup（含折叠行）', async () => {
    const select = await mount(deep())
    const rows = bodyRows()
    rows[2].querySelectorAll('td')[10].querySelector('button')!.click()
    rows[5].querySelectorAll('td')[17].querySelector('button')!.click()
    rows[0].querySelectorAll('td')[4].querySelector('button')!.click()
    host.querySelector('tfoot')!.querySelectorAll('td')[8].querySelector('button')!.click()
    expect(select.mock.calls.map(([s]) => [s.rowKeys, s.columnKeys, s.metricId])).toEqual([
      [['A', 'd1'], ['2026', '01'], 'in'],
      [['A'], ['2026'], 'out'],
      [['A', 'd1', 'g1'], ['2025'], 'in'],
      [[], ['2026', '01', 'y'], 'in']
    ])
    expect(select.mock.calls[0][0].label).toBe('甲公司 / 一部 / 小计 · 2026 / 01月 小计')
    await click('折叠 一部')
    bodyRows()[0].querySelectorAll('td')[11].querySelector('button')!.click()
    expect(select.mock.calls[4][0]).toMatchObject({ rowKeys: ['A', 'd1'], columnKeys: ['2026', '01'], metricId: 'out' })
  })
})
