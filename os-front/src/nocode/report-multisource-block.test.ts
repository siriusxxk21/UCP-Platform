// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import {
  example1Config,
  example1Result,
  example2Config,
  example3Config,
  example3Result,
  multiObjects
} from './report-multisource-fixture'
import { registerStubs } from './report-multisource-harness'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { ReportGrain, type ReportConfig, type ReportResult } from '@/types/nocode/report'

const api = vi.hoisted(() => ({
  report: vi.fn(),
  reportDetails: vi.fn(),
  reportExport: vi.fn(),
  model: vi.fn(),
  page: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-multisource-test' } }) }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    // 下钻视图经 defineAsyncComponent 加载：桩模块要标成 ES 模块，才会取 default
    __esModule: true,
    default: defineComponent({
      props: ['objectId', 'viewId', 'readOnly', 'reportDrill'],
      setup: props => () =>
        h('div', {
          'data-business-records': props.viewId,
          'data-object': props.objectId,
          'data-read-only': String(!!props.readOnly),
          'data-drill-metric': props.reportDrill?.metricId
        })
    })
  }
})

import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

const permissions = {
  actions: ['READ'],
  readFields: [],
  writeFields: [],
  readDetails: ['voucher_lines'],
  writeDetails: []
}
const model = (objectId: string) => ({
  writable: false,
  permissions,
  object: multiObjects[objectId].definition,
  details: {}
})
const resource = (config: ReportConfig): ApplicationResource => ({
  id: 'report',
  kind: ResourceKind.REPORT,
  name: '利润统计',
  code: 'report',
  config: config as unknown as Record<string, unknown>
})
const view = (id: string, objectId: string): ApplicationResource => ({
  id,
  kind: ResourceKind.VIEW,
  name: id,
  code: id,
  config: { objectId, fieldIds: [], equal: {} }
})
const expenseRow = (id: string, amount: string) => ({
  id,
  revision: '1',
  values: { expense_property: 'P1', expense_paid_on: '2026-08-20', expense_amount: amount },
  displayValues: { expense_property: '青山' }
})

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
const drawer = () => host.querySelector('[data-drawer]')
const modal = () => host.querySelector('[data-modal]')
const headers = () => Array.from(drawer()!.querySelectorAll('[data-antd-table] th')).map(th => th.textContent)
/** 第 row 行（0 起）、第 column 个列组（0 起：2026-07、2026-08、2026-09、合计）里指标 metric 的格子。 */
const cell = (row: number, column: number, metric: string) =>
  host.querySelectorAll('.report-pivot tbody tr')[row].querySelectorAll(`td[data-metric="${metric}"]`)[column]
async function clickCell(row: number, column: number, metric: string) {
  cell(row, column, metric).querySelector('button')!.click()
  await flush()
}

beforeEach(() => {
  api.report.mockResolvedValue(example1Result())
  api.reportDetails.mockResolvedValue({ list: [expenseRow('E2', '7000')], total: 1 })
  api.model.mockImplementation((_app: string, objectId: string) => Promise.resolve(model(objectId)))
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.clearAllMocks()
})

describe('F10 页脚按来源列条数', () => {
  it('例 1：当月 5 条 · 次月 5 条 · 支出 4 条 · 时区 …；没有「全部明细」', async () => {
    await mount(example1Config())
    expect(host.querySelector('[data-report-sources]')!.textContent).toBe(
      '当月 5 条 · 次月 5 条 · 支出 4 条 · 时区 Asia/Tokyo'
    )
    expect(host.querySelector('.report-footer')!.textContent).not.toContain('来源记录')
    expect(host.querySelector('.report-footer')!.textContent).not.toContain('全部明细')
  })
  it('例 3：两个明细粒度来源写「N 行（明细「分录」）」', async () => {
    api.report.mockResolvedValue(example3Result())
    await mount(example3Config())
    expect(host.querySelector('[data-report-sources]')!.textContent).toBe(
      '借方 5 行（明细「分录」） · 贷方 5 行（明细「分录」） · 时区 Asia/Tokyo'
    )
  })
  it('附加来源的对象模型各取一次（只读下钻表的列要用）', async () => {
    await mount(example1Config())
    expect(api.model.mock.calls.map(c => c[1]).sort()).toEqual(['expense', 'stay'])
  })
})

describe('F11 点基础指标：看所属来源的明细', () => {
  it('点 P1·08·支出：请求带 metricId = exp、不带 sourceId；抽屉标题「支出 · …」；表列按「支出」对象', async () => {
    await mount(example1Config())
    await clickCell(0, 1, 'exp')
    const request = api.reportDetails.mock.calls[0][0]
    expect(request).toMatchObject({ group: ['P1'], columnGroup: ['2026-08'], metricId: 'exp' })
    expect(request).not.toHaveProperty('sourceId')
    expect(drawer()!.getAttribute('data-drawer')).toBe('支出 · 利润统计 · 青山 · 2026-08 · 支出')
    expect(headers()).toEqual(['记录ID', '物件', '支出日期', '金额'])
    expect(Array.from(drawer()!.querySelectorAll('tbody td')).map(td => td.textContent)).toEqual([
      'E2',
      '青山',
      '2026-08-20',
      '7,000.00'
    ])
  })
  it('点来源 1 的指标：标题前是来源 1 的名称，表列按入住记录', async () => {
    api.reportDetails.mockResolvedValue({
      list: [{ id: 'S2', revision: '1', values: { stay_check_in: '2026-08-05', stay_cur_amount: '18000' } }],
      total: 1
    })
    await mount(example1Config())
    await clickCell(0, 1, 'cur')
    expect(drawer()!.getAttribute('data-drawer')).toBe('当月 · 利润统计 · 青山 · 2026-08 · 当月金额')
    expect(headers()).toEqual(['记录ID', '入住日', '当月金额'])
  })
})

describe('F12 点计算指标：指标计算依据', () => {
  it('利润 = 收入 − 支出：引用指标后带来源名；点「支出」再下钻', async () => {
    await mount(example1Config())
    await clickCell(0, 1, 'pro')
    expect(api.reportDetails).not.toHaveBeenCalled()
    expect(modal()!.getAttribute('data-modal')).toBe('指标计算依据')
    const lines = Array.from(modal()!.querySelectorAll('p')).map(p => p.textContent!.replace(/\s+/g, ' ').trim())
    expect(lines).toContain('左侧指标 / 分子： 收入 · 28,000')
    expect(lines).toContain('右侧指标 / 分母： 支出（支出） · 7,000')
    // 契约变更 C3：沿用现有弹层，只列左右引用指标（不另出「按来源分组」的列表）
    expect(modal()!.querySelector('[data-analysis-source]')).toBeNull()
    Array.from(modal()!.querySelectorAll('a'))
      .find(a => a.textContent!.includes('支出（支出）'))!
      .click()
    await flush()
    expect(api.reportDetails.mock.calls[0][0]).toMatchObject({ metricId: 'exp', group: ['P1'] })
    expect(drawer()!.getAttribute('data-drawer')!.startsWith('支出 · ')).toBe(true)
  })
  it('月收益 = 当月金额 + 次月金额：左右都带来源名', async () => {
    api.report.mockResolvedValue({
      ...example1Result(),
      metrics: example2Config().metrics,
      pivot: {
        ...example1Result().pivot!,
        cells: example1Result().pivot!.cells.map(c => ({ ...c, values: { ...c.values, rev: c.values.inc } }))
      }
    })
    await mount(example2Config())
    await clickCell(0, 1, 'rev')
    const lines = Array.from(modal()!.querySelectorAll('p')).map(p => p.textContent!.replace(/\s+/g, ' ').trim())
    expect(lines).toContain('左侧指标 / 分子： 当月金额（当月） · 18,000')
    expect(lines).toContain('右侧指标 / 分母： 次月金额（次月） · 10,000')
  })
})

describe('F13 空值与 0 显示不同', () => {
  it('P1·07 次月金额（来源 2 那个月没有记录）是空格子；P2·09 次月金额（有记录、加起来为 0）显示 0', async () => {
    await mount(example1Config())
    expect(cell(0, 0, 'nxt').querySelector('button')!.textContent!.trim()).toBe('')
    expect(cell(1, 2, 'nxt').querySelector('button')!.textContent!.trim()).toBe('0')
    // 任何来源都没有记录的格（P2·07）：没有格子
    expect(cell(1, 0, 'nxt').querySelector('button')).toBeNull()
  })
})

describe('各来源各自的下钻明细视图（业务方 2026-10-04「加进去」）', () => {
  it('点支出：用来源「支出」挂的视图与它的「允许编辑」；点当月：用来源 1 的视图（只读）', async () => {
    const config: ReportConfig = {
      ...example1Config(),
      detailViewId: 'stay-view',
      detailEditable: null
    }
    config.extraSources![1] = { ...config.extraSources![1], detailViewId: 'expense-view', detailEditable: true }
    const report = resource(config)
    await mount(config, [report, view('stay-view', 'stay'), view('expense-view', 'expense')])
    await clickCell(0, 1, 'exp')
    const records = () => drawer()!.querySelector('[data-business-records]')!
    expect(records().getAttribute('data-business-records')).toBe('expense-view')
    expect(records().getAttribute('data-object')).toBe('expense')
    expect(records().getAttribute('data-read-only')).toBe('false')
    expect(records().getAttribute('data-drill-metric')).toBe('exp')
    expect(api.reportDetails).not.toHaveBeenCalled()
    app!.unmount()
    host.remove()
    await mount(config, [report, view('stay-view', 'stay'), view('expense-view', 'expense')])
    await clickCell(0, 1, 'cur')
    expect(records().getAttribute('data-business-records')).toBe('stay-view')
    expect(records().getAttribute('data-read-only')).toBe('true')
  })
  it('没挂视图的来源（次月）点进去仍是只读明细表', async () => {
    const config = { ...example1Config(), detailViewId: 'stay-view' }
    const report = resource(config)
    await mount(config, [report, view('stay-view', 'stay')])
    await clickCell(0, 1, 'nxt')
    expect(drawer()!.querySelector('[data-business-records]')).toBeNull()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
  })
})

describe('明细粒度的来源挂下钻视图（R6 衔接：laneDV 放开顶层，附加来源同样放开）', () => {
  const lineView = (id: string, composition: boolean): ApplicationResource => ({
    id,
    kind: ResourceKind.VIEW,
    name: id,
    code: id,
    config: {
      objectId: 'voucher',
      fieldIds: [],
      equal: {},
      ...(composition ? { composition: { grain: 'DETAIL', detailId: 'voucher_lines', sections: [], columns: [] } } : {})
    }
  })
  const withCreditView = (viewId: string) => {
    const config = example3Config()
    config.extraSources![0] = { ...config.extraSources![0], detailViewId: viewId, detailEditable: true }
    return config
  }
  it('例 3 点 A1·08·贷方：进来源「贷方」挂的分录逐行视图（允许编辑）；点借方（来源 1 没挂）仍是只读明细行', async () => {
    api.report.mockResolvedValue(example3Result())
    api.reportDetails.mockResolvedValue({ list: [], total: 0 })
    const config = withCreditView('line-view')
    const report = resource(config)
    await mount(config, [report, lineView('line-view', true)])
    await clickCell(0, 0, 'cr')
    const records = () => drawer()!.querySelector('[data-business-records]')
    expect(records()!.getAttribute('data-business-records')).toBe('line-view')
    expect(records()!.getAttribute('data-object')).toBe('voucher')
    expect(records()!.getAttribute('data-read-only')).toBe('false')
    expect(records()!.getAttribute('data-drill-metric')).toBe('cr')
    expect(drawer()!.getAttribute('data-drawer')!.startsWith('贷方 · ')).toBe(true)
    expect(api.reportDetails).not.toHaveBeenCalled()
    app!.unmount()
    host.remove()
    await mount(config, [report, lineView('line-view', true)])
    await clickCell(0, 0, 'dr')
    expect(records()).toBeNull()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
    expect(api.reportDetails.mock.calls[0][0]).toMatchObject({ metricId: 'dr' })
  })
  it('视图形状按所点指标的来源判：来源 1 是入住记录、按明细行统计的附加来源是会计凭证 ⇒ 仍进该来源的分录逐行视图', async () => {
    const config = example1Config()
    config.extraSources![1] = {
      ...config.extraSources![1],
      objectId: 'voucher',
      grain: ReportGrain.DETAIL,
      detailId: 'voucher_lines',
      detailViewId: 'line-view',
      detailEditable: true
    }
    const report = resource(config)
    await mount(config, [report, lineView('line-view', true)])
    await clickCell(0, 1, 'exp')
    const records = drawer()!.querySelector('[data-business-records]')
    expect(records?.getAttribute('data-business-records')).toBe('line-view')
    expect(records?.getAttribute('data-object')).toBe('voucher')
    expect(api.reportDetails).not.toHaveBeenCalled()
  })
  it('来源「贷方」挂的视图不是按分录逐行显示（存量或后来被改）：退回只读明细行', async () => {
    api.report.mockResolvedValue(example3Result())
    api.reportDetails.mockResolvedValue({ list: [], total: 0 })
    const config = withCreditView('voucher-view')
    const report = resource(config)
    await mount(config, [report, lineView('voucher-view', false)])
    await clickCell(0, 0, 'cr')
    expect(drawer()!.querySelector('[data-business-records]')).toBeNull()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
    expect(api.reportDetails.mock.calls[0][0]).toMatchObject({ metricId: 'cr' })
  })
})

describe('单来源不变', () => {
  it('单来源结果（没有 sources）页脚仍是「来源记录 N 条」，只取一次模型', async () => {
    const single: ReportResult = { ...example1Result(), sources: null, recordCount: 5 }
    api.report.mockResolvedValue(single)
    const config = example1Config()
    delete config.extraSources
    delete config.sourceName
    config.metrics = config.metrics.filter(m => m.id === 'cur')
    await mount(config)
    expect(host.querySelector('.report-footer span')!.textContent!.trim()).toBe('来源记录 5 条 · 时区 Asia/Tokyo')
    expect(host.querySelector('.report-footer')!.textContent).toContain('全部明细')
    expect(api.model).toHaveBeenCalledTimes(1)
  })
})

describe('导出：前端不生成口径行，只确认请求仍带 sort', () => {
  it('多来源透视表点列头按「利润」排序后导出：导出请求带同一个 sort', async () => {
    api.reportExport.mockResolvedValue(new Blob(['x']))
    await mount(example1Config())
    const heads = Array.from(host.querySelectorAll<HTMLButtonElement>('.report-pivot thead button.pivot-sort'))
    // 表头排序按钮：先是行维度「物件」，再是第一个列组下的五个指标
    heads[5].click()
    await flush()
    const sort = api.report.mock.calls.at(-1)![0].sort
    expect(sort).toMatchObject({ metricId: 'pro' })
    const anchor = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    URL.createObjectURL ||= () => 'blob:test'
    URL.revokeObjectURL ||= () => undefined
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => b.textContent!.includes('导出'))!
      .click()
    await flush()
    expect(api.reportExport).toHaveBeenCalledTimes(1)
    expect(api.reportExport.mock.calls[0][0].sort).toEqual(sort)
    anchor.mockRestore()
  })
})
