// @vitest-environment jsdom
// 用后端一路的真实接口样例（report-multisource-backend-samples.ts，@515e5f7d）驱动运行端统计块：形状对得上、显示对。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { backendSamples } from './report-multisource-backend-samples'
import { registerStubs } from './report-multisource-harness'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig } from '@/types/nocode/report'

const api = vi.hoisted(() => ({ report: vi.fn(), reportDetails: vi.fn(), reportExport: vi.fn(), model: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-multisource-samples-test' } }) }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  __esModule: true,
  default: { render: () => null }
}))
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

const sample = <T>(key: string) => JSON.parse(JSON.stringify(backendSamples[key])) as T
const field = (id: string, name: string, type: string) => ({ id, key: id, code: id, name, type })
/** 样例 legend 里的对象与字段（ID 是后端那次运行生成的）；支出上的 4431 是样例里出现、legend 没列的编号字段。 */
const definitions: Record<string, unknown> = {
  '733': {
    objectId: '733',
    objectName: '入住记录',
    fields: [
      field('4430', '物件', 'REFERENCE'),
      field('4424', '入住日', 'DATE'),
      field('4425', '退房日', 'DATE'),
      field('4426', '当月金额', 'MONEY'),
      field('4427', '次月金额', 'MONEY')
    ],
    fieldOptions: {},
    relations: [{ id: '4429', fieldId: '4430', targetObjectId: '732', kind: 'REFERENCE', sourceDetailId: null }],
    details: [],
    settings: {}
  },
  '734': {
    objectId: '734',
    objectName: '支出',
    fields: [
      field('4436', '物件', 'REFERENCE'),
      field('4432', '支出日期', 'DATE'),
      field('4433', '金额', 'MONEY'),
      field('4431', '编号', 'TEXT')
    ],
    fieldOptions: {},
    relations: [{ id: '4435', fieldId: '4436', targetObjectId: '732', kind: 'REFERENCE', sourceDetailId: null }],
    details: [],
    settings: {}
  },
  '736': {
    objectId: '736',
    objectName: '会计凭证',
    fields: [field('4440', '日期', 'DATE'), field('4439', '凭证号', 'TEXT')],
    fieldOptions: {},
    relations: [],
    details: [
      {
        id: '4442',
        name: '分录',
        state: 'ACTIVE',
        fields: [
          field('4446', '借方科目', 'REFERENCE'),
          field('4443', '借方金额', 'MONEY'),
          field('4448', '贷方科目', 'REFERENCE'),
          field('4444', '贷方金额', 'MONEY')
        ],
        fieldOptions: {}
      }
    ],
    settings: {}
  }
}
const model = (objectId: string) => ({
  writable: false,
  permissions: { actions: ['READ'], readFields: [], writeFields: [], readDetails: ['4442'], writeDetails: [] },
  object: definitions[objectId],
  details: {}
})
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await vi.dynamicImportSettled()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}
async function mount(config: ReportConfig) {
  const resource: ApplicationResource = {
    id: 'profit',
    kind: ResourceKind.REPORT,
    name: '利润',
    code: 'profit',
    config: config as unknown as Record<string, unknown>
  }
  app = createApp(() => h(ReportBlock, { applicationId: '400', resource, resources: [resource] }))
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const rows = () =>
  Array.from(host.querySelectorAll('.report-pivot tbody tr')).map(tr => tr.querySelector('th')!.textContent!.trim())
const cell = (row: number, column: number, metric: string) =>
  host.querySelectorAll('.report-pivot tbody tr')[row].querySelectorAll(`td[data-metric="${metric}"]`)[column]
beforeEach(() => {
  api.model.mockImplementation((_app: string, objectId: string) => Promise.resolve(model(objectId)))
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.clearAllMocks()
})

describe('后端真实样例 @515e5f7d', () => {
  it('例 1：页脚按来源；行序与空 / 0；点 P1·08·支出 的请求与后端样例同形，抽屉按「支出」出列', async () => {
    api.report.mockResolvedValue(sample('example1.query.response'))
    api.reportDetails.mockResolvedValue(sample('example1.details.response'))
    await mount(sample<ReportConfig>('example1.runtime-config'))
    expect(host.querySelector('[data-report-sources]')!.textContent).toBe(
      '当月 5 条 · 次月 5 条 · 支出 4 条 · 时区 Asia/Tokyo'
    )
    expect(rows()).toEqual(['青山', '白川', '青山', '未填写'])
    expect(cell(0, 0, 'nxt').textContent!.trim()).toBe('')
    expect(cell(1, 2, 'nxt').textContent!.trim()).toBe('0.00')
    cell(0, 1, 'exp').querySelector('button')!.click()
    await flush()
    const request = api.reportDetails.mock.calls[0][0]
    const expected = sample<Record<string, unknown>>('example1.details.request')
    for (const key of ['applicationId', 'reportId', 'group', 'columnGroup', 'metricId', 'pageNo'])
      expect(request[key]).toEqual(expected[key])
    expect(request).not.toHaveProperty('sourceId')
    const drawer = host.querySelector('[data-drawer]')!
    expect(drawer.getAttribute('data-drawer')).toBe('支出 · 利润 · 青山 · 2026-08 · 支出')
    expect(Array.from(drawer.querySelectorAll('th')).map(th => th.textContent)).toEqual([
      '记录ID',
      '物件',
      '支出日期',
      '金额',
      '编号'
    ])
    expect(Array.from(drawer.querySelectorAll('tbody td')).map(td => td.textContent)).toEqual([
      '2',
      '青山',
      '2026-08-20',
      '7,000.00',
      'E2'
    ])
  })
  it('例 3：两个明细粒度来源的页脚', async () => {
    api.report.mockResolvedValue(sample('example3.query.response'))
    await mount(sample<ReportConfig>('example3.saved-config'))
    expect(host.querySelector('[data-report-sources]')!.textContent).toBe(
      '借方 5 行（明细「分录」） · 贷方 5 行（明细「分录」） · 时区 Asia/Tokyo'
    )
  })
})
