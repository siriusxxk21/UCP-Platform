// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { detailPivotConfig, grainObjects, rootPivotConfig } from './report-detail-grain-fixture'
import { registerStubs } from './report-detail-grain-harness'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig, ReportResult } from '@/types/nocode/report'

const api = vi.hoisted(() => ({
  report: vi.fn(),
  reportDetails: vi.fn(),
  reportExport: vi.fn(),
  model: vi.fn(),
  page: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-detail-grain-test' } }) }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('div', { 'data-business-records': true }) }) }
})
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['entries'],
      setup: props => () =>
        h('div', {
          'data-condition-entries': (props.entries || [])
            .map((e: { value: string; detailId?: string }) => e.value + (e.detailId ? '@' + e.detailId : ''))
            .join(',')
        })
    })
  }
})
// 筛选输入用真实的 ReportFilterInput；它里面的两种选择控件换成能读出属性的桩。
vi.mock('@/views/nocode/application/components/ReferenceField.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['targetObjectId'],
      setup: props => () => h('span', { 'data-reference-target': props.targetObjectId })
    })
  }
})
vi.mock('@/views/nocode/application/components/SelectionField.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['objectId', 'fieldId', 'detailId'],
      setup: props => () =>
        h('span', {
          'data-selection-field': props.fieldId,
          'data-selection-object': props.objectId,
          'data-selection-detail': props.detailId ?? ''
        })
    })
  }
})

import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

const permissions = { actions: ['READ'], readFields: [], writeFields: [], readDetails: ['lines'], writeDetails: [] }
const model = (objectId: string, definition: Record<string, unknown> = {}) => ({
  writable: false,
  permissions,
  object: { ...grainObjects[objectId].definition, ...definition },
  details: {}
})
const metrics = detailPivotConfig().metrics
const result = (extra: Partial<ReportResult> = {}): ReportResult => ({
  dimensionNames: ['贷方科目 / 科目名称'],
  metrics,
  groups: [],
  totals: { credit: '2050', rows: '6', roots: '5' },
  totalGroups: 0,
  recordCount: 6,
  canExport: true,
  timeZone: 'Asia/Shanghai',
  pivot: {
    rowDimensionNames: ['贷方科目 / 科目名称'],
    columnDimensionNames: ['日期'],
    rows: [{ keys: ['销售收入'], labels: ['销售收入'] }],
    columns: [{ keys: ['2026-07'], labels: ['2026-07'] }],
    cells: [
      { rowKeys: ['销售收入'], columnKeys: ['2026-07'], values: { credit: '1000', rows: '2', roots: '1' } },
      { rowKeys: ['销售收入'], columnKeys: [], values: { credit: '1000', rows: '2', roots: '1' } },
      { rowKeys: [], columnKeys: ['2026-07'], values: { credit: '1000', rows: '2', roots: '1' } },
      { rowKeys: [], columnKeys: [], values: { credit: '1000', rows: '2', roots: '1' } }
    ],
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: 1,
    totalColumnGroups: 1
  },
  ...extra
})
/** 主记录粒度的同形结果：指标换成主记录粒度那张统计自己的。 */
const rootResult = (): ReportResult => {
  const values = { in: '1000', count: '2' }
  const base = result({ recordCount: 5 })
  return {
    ...base,
    dimensionNames: ['摘要'],
    metrics: rootPivotConfig().metrics,
    totals: values,
    pivot: {
      ...base.pivot!,
      rowDimensionNames: ['摘要'],
      rows: [{ keys: ['七月销售'], labels: ['七月销售'] }],
      cells: base.pivot!.cells.map(cell => ({
        ...cell,
        rowKeys: cell.rowKeys.length ? ['七月销售'] : [],
        values
      }))
    }
  }
}
/** 明细粒度的 report-details：id = 主记录ID:明细行ID，parentId = 主记录ID；值 = 主表字段 ∪ 明细字段。 */
const lineRow = (lineId: string, amount: string) => ({
  id: 'V1:' + lineId,
  parentId: 'V1',
  revision: '3',
  values: { date: '2026-07-05', memo: '七月销售', creditAccount: 'A1', creditAmount: amount, lineType: 'NORMAL' },
  displayValues: { creditAccount: '销售收入' }
})
const rootRow = (id: string) => ({
  id,
  revision: '1',
  values: { date: '2026-07-05', memo: '七月销售', income: '1000' }
})
const resource = (config: ReportConfig): ApplicationResource => ({
  id: 'report',
  kind: ResourceKind.REPORT,
  name: '科目汇总',
  code: 'report',
  config: config as unknown as Record<string, unknown>
})
const drillView: ApplicationResource = {
  id: 'voucher-view',
  kind: ResourceKind.VIEW,
  name: '凭证列表',
  code: 'voucher_view',
  config: { objectId: 'voucher', fieldIds: ['memo'], equal: {} }
}

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await vi.dynamicImportSettled()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}
async function mount(config: ReportConfig, resources?: ApplicationResource[]) {
  const report = resource(config)
  app = createApp(() => h(ReportBlock, { applicationId: 'app', resource: report, resources: resources ?? [report] }))
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const footer = () => host.querySelector('.report-footer span')!.textContent!.replace(/\s+/g, ' ').trim()
const drawer = () => host.querySelector('[data-drawer]')
async function clickPivotLeaf() {
  host.querySelector<HTMLButtonElement>('tbody td button')!.click()
  await flush()
}
const headers = () => Array.from(drawer()!.querySelectorAll('[data-antd-table] th')).map(th => th.textContent)
const rowKeys = () =>
  Array.from(drawer()!.querySelectorAll('[data-antd-table] tbody tr')).map(tr => tr.getAttribute('data-row-key'))
const cells = (row: number) =>
  Array.from(drawer()!.querySelectorAll('[data-antd-table] tbody tr')[row].querySelectorAll('td')).map(
    td => td.textContent
  )

beforeEach(() => {
  api.report.mockResolvedValue(result({ detailName: '分录' }))
  api.reportDetails.mockResolvedValue({ list: [lineRow('L1', '600'), lineRow('L2', '400')], total: 2 })
  api.model.mockImplementation((_app: string, objectId: string) => Promise.resolve(model(objectId)))
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.clearAllMocks()
})

describe('统计块：页脚', () => {
  it('主记录粒度的页脚与基线逐字相同', async () => {
    api.report.mockResolvedValue(rootResult())
    await mount(rootPivotConfig())
    expect(footer()).toBe('来源记录 5 条 · 时区 Asia/Shanghai')
    expect(host.querySelector('[data-report-source]')).toBeNull()
  })

  it('明细粒度写「来源明细行 N 行（明细「分录」）」', async () => {
    await mount(detailPivotConfig())
    expect(footer()).toBe('来源明细行 6 行（明细「分录」）· 时区 Asia/Shanghai')
    expect(host.querySelector('.report-footer')!.textContent).not.toContain('来源记录')
  })
})

describe('统计块：只读下钻表', () => {
  it('明细粒度：列 = 主记录ID、分录字段、主表字段；每行一条明细，行键互不相同', async () => {
    await mount(detailPivotConfig())
    await clickPivotLeaf()
    expect(drawer()!.getAttribute('data-drawer')).toBe('科目汇总 · 销售收入 · 2026-07 · 贷方金额合计')
    expect(headers()).toEqual(['主记录ID', '贷方科目', '贷方金额', '分录类型', '日期', '摘要'])
    expect(rowKeys()).toEqual(['V1:L1', 'V1:L2'])
    expect(new Set(rowKeys()).size).toBe(2)
    // 主记录ID 取 parentId；引用字段显示名称；单选显示选项文字（取的是明细自己的字段配置）
    expect(cells(0)).toEqual(['V1', '销售收入', '600.00', '正常', '2026-07-05', '七月销售'])
    expect(cells(1)[2]).toBe('400.00')
    expect(drawer()!.querySelector('.detail-hint')!.textContent).toContain(
      '按明细行统计：下面每一行是一条明细，带着所属主记录的信息。'
    )
    expect(drawer()!.querySelector('[data-business-records]')).toBeNull()
  })

  it('主记录粒度的列与基线相同：记录ID + 主表字段；提示句不多出明细那一句', async () => {
    api.report.mockResolvedValue(rootResult())
    api.reportDetails.mockResolvedValue({ list: [rootRow('V1'), rootRow('V2')], total: 2 })
    await mount(rootPivotConfig())
    await clickPivotLeaf()
    expect(headers()).toEqual(['记录ID', '日期', '摘要', '入金'])
    expect(rowKeys()).toEqual(['V1', 'V2'])
    expect(cells(0)).toEqual(['V1', '2026-07-05', '七月销售', '1,000.00'])
    expect(drawer()!.querySelector('.detail-hint')!.textContent!.trim()).toBe(
      '明细沿用公共筛选、当前分组、所选指标条件和实时权限。去重计数显示参与计算的原始记录，记录条数可能多于唯一值数。'
    )
  })

  it('明细粒度不渲染下钻视图：即使配置里残留了 detailViewId 也走只读明细行', async () => {
    const report = resource({ ...detailPivotConfig(), detailViewId: 'voucher-view' })
    await mount(report.config as unknown as ReportConfig, [report, drillView])
    await clickPivotLeaf()
    expect(drawer()!.querySelector('[data-business-records]')).toBeNull()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
    expect(rowKeys()).toEqual(['V1:L1', 'V1:L2'])
  })

  it('下钻请求与主记录粒度同形状：group / columnGroup / metricId，没有多余键', async () => {
    await mount(detailPivotConfig())
    await clickPivotLeaf()
    const detail = api.reportDetails.mock.calls[0][0]
    expect(detail).toMatchObject({
      applicationId: 'app',
      reportId: 'report',
      group: ['销售收入'],
      columnGroup: ['2026-07'],
      metricId: 'credit',
      pageNo: 1
    })
    app!.unmount()
    host.remove()
    vi.clearAllMocks()
    api.report.mockResolvedValue(rootResult())
    api.reportDetails.mockResolvedValue({ list: [rootRow('V1')], total: 1 })
    await mount(rootPivotConfig())
    await clickPivotLeaf()
    const root = api.reportDetails.mock.calls[0][0]
    expect(Object.keys(detail)).toEqual(Object.keys(root))
    expect(Object.keys(detail).sort()).toEqual(
      [
        'applicationId',
        'reportId',
        'context',
        'conditions',
        'equal',
        'dateFrom',
        'dateTo',
        'group',
        'columnGroup',
        'metricId',
        'pageNo',
        'pageSize'
      ].sort()
    )
    expect(detail).not.toHaveProperty('grain')
    expect(detail).not.toHaveProperty('detailId')
  })
})

describe('统计块：明细字段的筛选输入', () => {
  it('分录的引用字段渲染引用选择；分录的单选字段渲染选项选择并带 detailId；主表字段不带', async () => {
    await mount({
      ...detailPivotConfig(),
      filterFieldIds: ['creditAccount', 'lineType', 'company', 'memo']
    })
    const items = Array.from(host.querySelectorAll('.report-filters [data-form-item]'))
    expect(items.map(i => i.getAttribute('data-form-item'))).toEqual([
      '摘要',
      '公司',
      '分录 · 贷方科目',
      '分录 · 分录类型',
      '组合筛选'
    ])
    const item = (label: string) => items.find(i => i.getAttribute('data-form-item') === label)!
    expect(
      item('分录 · 贷方科目').querySelector('[data-reference-target]')!.getAttribute('data-reference-target')
    ).toBe('account')
    const selection = item('分录 · 分录类型').querySelector('[data-selection-field]')!
    expect(selection.getAttribute('data-selection-field')).toBe('lineType')
    expect(selection.getAttribute('data-selection-object')).toBe('voucher')
    expect(selection.getAttribute('data-selection-detail')).toBe('lines')
    expect(item('公司').querySelector('[data-reference-target]')!.getAttribute('data-reference-target')).toBe('company')
    expect(item('摘要').querySelector('[data-selection-field]')).toBeNull()
    // 组合筛选拿到的条目同样带着明细归属
    expect(host.querySelector('[data-condition-entries]')!.getAttribute('data-condition-entries')).toBe(
      'memo,company,creditAccount@lines,lineType@lines'
    )
  })

  it('主记录粒度：同名的筛选字段只在主表范围里找，明细字段不出现', async () => {
    api.report.mockResolvedValue(rootResult())
    await mount({ ...rootPivotConfig(), filterFieldIds: ['lineType', 'memo'] })
    const labels = Array.from(host.querySelectorAll('.report-filters [data-form-item]')).map(i =>
      i.getAttribute('data-form-item')
    )
    expect(labels).toEqual(['摘要', '组合筛选'])
    expect(host.querySelector('[data-selection-field]')).toBeNull()
  })
})

describe('统计块：可读明细不含粒度明细', () => {
  it('运行端模型里没有这个明细时不崩：页脚仍按服务端给的明细名，下钻表只剩主记录ID与主表字段', async () => {
    api.model.mockImplementation((_app: string, objectId: string) =>
      Promise.resolve(
        objectId === 'voucher'
          ? model('voucher', {
              details: [],
              relations: grainObjects.voucher.definition.relations.filter(r => !r.sourceDetailId)
            })
          : model(objectId)
      )
    )
    await mount({ ...detailPivotConfig(), filterFieldIds: ['lineType', 'memo'] })
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(footer()).toBe('来源明细行 6 行（明细「分录」）· 时区 Asia/Shanghai')
    expect(
      Array.from(host.querySelectorAll('.report-filters [data-form-item]')).map(i => i.getAttribute('data-form-item'))
    ).toEqual(['摘要', '组合筛选'])
    await clickPivotLeaf()
    expect(headers()).toEqual(['主记录ID', '日期', '摘要'])
    expect(rowKeys()).toEqual(['V1:L1', 'V1:L2'])
  })

  it('模型对象上根本没有 details 字段（旧形状）也不崩', async () => {
    api.model.mockImplementation((_app: string, objectId: string) =>
      Promise.resolve(model(objectId, { details: undefined }))
    )
    await mount(detailPivotConfig())
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(footer()).toBe('来源明细行 6 行（明细「分录」）· 时区 Asia/Shanghai')
  })
})
