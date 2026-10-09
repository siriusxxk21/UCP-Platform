// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { reportDrillViewMatches } from './report'
import { detailPivotConfig, grainObjects, rootPivotConfig } from './report-detail-grain-fixture'
import { registerStubs } from './report-detail-grain-harness'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig, ReportResult } from '@/types/nocode/report'

/**
 * 按明细行统计的下钻挂「下钻明细视图」并按「允许编辑」开关编辑（laneDV）。
 * 下钻视图用桩替身：只记下统计块交给它的参数（视图、下钻条件、只读），保存后通过 changed 事件让统计块重查。
 */
const api = vi.hoisted(() => ({
  report: vi.fn(),
  reportDetails: vi.fn(),
  reportExport: vi.fn(),
  model: vi.fn(),
  page: vi.fn()
}))
const records = vi.hoisted(() => ({
  props: [] as Record<string, unknown>[],
  emitChanged: undefined as (() => void) | undefined
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-detail-drill-view-test' } }) }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    __esModule: true,
    default: defineComponent({
      props: ['viewId', 'view', 'reportDrill', 'readOnly', 'objectId'],
      emits: ['changed'],
      setup: (props, { emit }) => {
        records.props.push(props as unknown as Record<string, unknown>)
        records.emitChanged = () => emit('changed')
        return () =>
          h('div', {
            'data-business-records': props.viewId,
            'data-read-only': String(props.readOnly)
          })
      }
    })
  }
})

import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

const lineViewConfig = (detailId = 'lines') => ({
  objectId: 'voucher',
  fieldIds: ['memo'],
  equal: {},
  composition: { grain: 'DETAIL', detailId, sections: [], columns: [] }
})
const view = (id: string, config: Record<string, unknown>): ApplicationResource => ({
  id,
  kind: ResourceKind.VIEW,
  name: id,
  code: id.replace('-', '_'),
  config
})
const lineView = view('line-view', lineViewConfig())
const notesView = view('notes-view', lineViewConfig('notes'))
const plainView = view('voucher-view', { objectId: 'voucher', fieldIds: ['memo'], equal: {} })

describe('reportDrillViewMatches：哪个视图能当下钻明细视图', () => {
  it('明细粒度只认同一对象、按同一明细逐行的数据视图；主记录粒度认同一对象的任意视图', () => {
    const detail = detailPivotConfig()
    expect(reportDrillViewMatches(detail, lineViewConfig())).toBe(true)
    expect(reportDrillViewMatches(detail, lineViewConfig('notes'))).toBe(false)
    expect(reportDrillViewMatches(detail, plainView.config)).toBe(false)
    expect(
      reportDrillViewMatches(detail, { ...lineViewConfig(), composition: { grain: 'ROOT', detailId: null } })
    ).toBe(false)
    expect(reportDrillViewMatches(detail, { ...lineViewConfig(), objectId: 'plain' })).toBe(false)
    expect(reportDrillViewMatches(detail, undefined)).toBe(false)
    const root = rootPivotConfig()
    expect(reportDrillViewMatches(root, plainView.config)).toBe(true)
    expect(reportDrillViewMatches(root, lineViewConfig())).toBe(true)
    expect(reportDrillViewMatches(root, { ...plainView.config, objectId: 'plain' })).toBe(false)
  })
})

const permissions = { actions: ['READ'], readFields: [], writeFields: [], readDetails: ['lines'], writeDetails: [] }
const metrics = detailPivotConfig().metrics
const result = (credit = '1000'): ReportResult => ({
  dimensionNames: ['贷方科目 / 科目名称'],
  metrics,
  groups: [],
  totals: { credit, rows: '2', roots: '1' },
  totalGroups: 0,
  recordCount: 2,
  canExport: true,
  timeZone: 'Asia/Shanghai',
  detailName: '分录',
  pivot: {
    rowDimensionNames: ['贷方科目 / 科目名称'],
    columnDimensionNames: ['日期'],
    rows: [{ keys: ['销售收入'], labels: ['销售收入'] }],
    columns: [{ keys: ['2026-07'], labels: ['2026-07'] }],
    cells: [
      { rowKeys: ['销售收入'], columnKeys: ['2026-07'], values: { credit, rows: '2', roots: '1' } },
      { rowKeys: ['销售收入'], columnKeys: [], values: { credit, rows: '2', roots: '1' } },
      { rowKeys: [], columnKeys: ['2026-07'], values: { credit, rows: '2', roots: '1' } },
      { rowKeys: [], columnKeys: [], values: { credit, rows: '2', roots: '1' } }
    ],
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: 1,
    totalColumnGroups: 1
  }
})
const lineRow = (lineId: string, amount: string) => ({
  id: 'V1:' + lineId,
  parentId: 'V1',
  revision: '3',
  values: { date: '2026-07-05', memo: '七月销售', creditAmount: amount }
})

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await vi.dynamicImportSettled()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}
async function mount(config: ReportConfig, views: ApplicationResource[]) {
  const report: ApplicationResource = {
    id: 'report',
    kind: ResourceKind.REPORT,
    name: '科目汇总',
    code: 'report',
    config: config as unknown as Record<string, unknown>
  }
  app = createApp(() => h(ReportBlock, { applicationId: 'app', resource: report, resources: [report, ...views] }))
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const drawer = () => host.querySelector('[data-drawer]')
async function clickPivotLeaf() {
  host.querySelector<HTMLButtonElement>('tbody td button')!.click()
  await flush()
}

beforeEach(() => {
  records.props = []
  records.emitChanged = undefined
  api.report.mockResolvedValue(result())
  api.reportDetails.mockResolvedValue({ list: [lineRow('L1', '600'), lineRow('L2', '400')], total: 2 })
  api.model.mockImplementation((_app: string, objectId: string) =>
    Promise.resolve({ writable: true, permissions, object: grainObjects[objectId].definition, details: {} })
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.clearAllMocks()
})

describe('统计块：明细粒度的下钻明细视图', () => {
  it('挂了按同一明细逐行的视图：下钻渲染该视图，带着格子的下钻条件；允许编辑 = 不只读；不再取只读明细行', async () => {
    await mount({ ...detailPivotConfig(), detailViewId: 'line-view', detailEditable: true }, [lineView])
    await clickPivotLeaf()
    expect(drawer()!.querySelector('[data-business-records="line-view"]')).not.toBeNull()
    expect(drawer()!.querySelector('[data-antd-table]')).toBeNull()
    expect(api.reportDetails).not.toHaveBeenCalled()
    const props = records.props.at(-1)!
    expect(props.readOnly).toBe(false)
    expect(props.view).toEqual(lineViewConfig())
    expect(props.reportDrill).toMatchObject({
      applicationId: 'app',
      reportId: 'report',
      group: ['销售收入'],
      columnGroup: ['2026-07'],
      metricId: 'credit'
    })
  })

  it('没开「允许编辑」：同一个视图只读打开', async () => {
    await mount({ ...detailPivotConfig(), detailViewId: 'line-view', detailEditable: null }, [lineView])
    await clickPivotLeaf()
    expect(drawer()!.querySelector('[data-business-records="line-view"]')!.getAttribute('data-read-only')).toBe('true')
    expect(records.props.at(-1)!.readOnly).toBe(true)
  })

  it('在下钻视图里改完保存（changed）：统计静默重查，结果更新，抽屉不关', async () => {
    await mount({ ...detailPivotConfig(), detailViewId: 'line-view', detailEditable: true }, [lineView])
    await clickPivotLeaf()
    expect(api.report).toHaveBeenCalledTimes(1)
    api.report.mockResolvedValue(result('1300'))
    records.emitChanged!()
    await flush()
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(host.querySelector('tbody td button')!.textContent).toContain('1,300')
    expect(drawer()!.querySelector('[data-business-records="line-view"]')).not.toBeNull()
  })

  it('视图不按同一明细逐行（另一个明细 / 一行一张凭证）：退回只读明细行，与存量一致', async () => {
    for (const bad of [notesView, plainView]) {
      await mount({ ...detailPivotConfig(), detailViewId: bad.id, detailEditable: true }, [bad])
      await clickPivotLeaf()
      expect(drawer()!.querySelector('[data-business-records]')).toBeNull()
      expect(api.reportDetails).toHaveBeenCalledTimes(1)
      app!.unmount()
      host.remove()
      vi.clearAllMocks()
      api.report.mockResolvedValue(result())
      api.reportDetails.mockResolvedValue({ list: [lineRow('L1', '600')], total: 1 })
      api.model.mockImplementation((_app: string, objectId: string) =>
        Promise.resolve({ writable: true, permissions, object: grainObjects[objectId].definition, details: {} })
      )
    }
  })

  it('存量：明细粒度没有下钻视图 ⇒ 只读明细行', async () => {
    await mount(detailPivotConfig(), [lineView])
    await clickPivotLeaf()
    expect(drawer()!.querySelector('[data-business-records]')).toBeNull()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
  })

  it('主记录粒度照旧：任意同对象视图（含明细粒度视图）都渲染', async () => {
    api.report.mockResolvedValue({ ...result(), metrics: rootPivotConfig().metrics, detailName: undefined })
    await mount({ ...rootPivotConfig(), detailViewId: 'voucher-view' }, [plainView])
    await clickPivotLeaf()
    expect(drawer()!.querySelector('[data-business-records="voucher-view"]')).not.toBeNull()
    expect(records.props.at(-1)!.readOnly).toBe(true)
  })
})
