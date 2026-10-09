// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createApp,
  defineComponent,
  h,
  nextTick,
  onUnmounted,
  provide,
  ref,
  type App,
  type Component,
  type Ref
} from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Antd from 'ant-design-vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { realtimeBus } from '@/realtime/bus'
import { useRealtimeStore } from '@/stores/realtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { NodeKind } from '@/types/nocode/application-ui'
import type { RecordsChanged } from '@/types/nocode/record-live'
import type { ReportResult } from '@/types/nocode/report'
import { applicationRefreshKey } from './application-context'
import { KeptPages } from './kept-pages'
import { defaultReport } from './report'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  report: vi.fn(),
  reportDetails: vi.fn()
}))
const channels = vi.hoisted(() => [] as string[])
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'record-live-blocks-test' } }) }))
vi.mock('@/realtime/runtime', () => ({
  initializeRealtimeRuntime: () => undefined,
  realtimeRuntime: {
    acquireChannel: (descriptor: { topic: string }) => {
      channels.push(descriptor.topic)
      return () => undefined
    },
    onChannelSubscribed: () => () => undefined
  }
}))
const log: string[] = []
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: defineComponent({
    props: { record: Object, readOnly: Boolean },
    setup(props) {
      log.push('editor')
      onUnmounted(() => log.push('editor-gone'))
      return () =>
        h('form', { 'data-editor': props.record?.record.values.name, 'data-read-only': String(!!props.readOnly) })
    }
  })
}))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportFilterInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
import BusinessBlock from '@/views/nocode/application/components/BusinessBlock.vue'
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'
import RecordExtras from '@/views/nocode/application/components/RecordExtras.vue'

const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string, name = id) => ({ id, revision: '1', values: { name }, permissions })
const record = (id: string, name = id, processes: unknown[] = []) => ({ record: row(id, name), details: {}, processes })
const missing = () => Object.assign(new Error('记录不存在或不可访问'), { businessCode: 1_050_000_002 })
const objectModel = (objectId = 'object') => ({
  writable: true,
  permissions,
  object: {
    objectId,
    objectName: '对象',
    titleFieldId: 'name',
    fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
    fieldOptions: {},
    details: [],
    relations: [],
    settings: {}
  },
  details: {}
})
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
window.matchMedia ||= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => undefined,
  removeListener: () => undefined,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  dispatchEvent: () => false
})) as typeof window.matchMedia
const computedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle

let seq = 0
function push(patch: Partial<RecordsChanged> = {}) {
  seq++
  realtimeBus.emit('nocode.records.changed', {
    applicationId: 'app',
    objectId: 'object',
    kind: 'ids',
    many: false,
    created: [],
    updated: [],
    deleted: [],
    origin: null,
    epoch: 'e1',
    fromSeq: seq,
    seq,
    ...patch
  })
}

let app: App | undefined, host: HTMLDivElement, pinia: Pinia, refresh: Ref<number>
const flush = async (ms = 0) => {
  await vi.advanceTimersByTimeAsync(ms)
  for (let index = 0; index < 6; index++) {
    await vi.advanceTimersByTimeAsync(0)
    await nextTick()
  }
}
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  refresh = ref(0)
  app = createApp(
    defineComponent({
      setup() {
        provide(applicationRefreshKey, refresh)
        return () => h(component, props)
      }
    })
  )
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] }))
  app.use(pinia)
  app.use(Antd)
  useRealtimeStore().setConnectionStatus('connected')
  app.mount(host)
  await flush()
}
const spinning = () => !!host.querySelector('.ant-spin-spinning')
const view: ApplicationResource = {
  id: 'view',
  kind: ResourceKind.VIEW,
  code: 'view',
  name: '凭证',
  config: { objectId: 'object', fieldIds: ['name'] }
}
const form: ApplicationResource = {
  id: 'form',
  kind: ResourceKind.FORM,
  code: 'form',
  name: '凭证资料',
  config: { objectId: 'object', nodes: [], detailIds: [] }
}

beforeEach(() => {
  vi.useFakeTimers()
  seq = 0
  log.length = channels.length = 0
  pinia = createPinia()
  setActivePinia(pinia)
  api.model.mockImplementation(async (_applicationId: string, objectId: string) => objectModel(objectId))
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
  vi.useRealTimers()
})

describe('指标卡', () => {
  it('K1 来帧：重取总数；重取期间旧数字不消失、不转圈', async () => {
    api.page.mockResolvedValue({ list: [], total: 5 })
    await mount(BusinessBlock, { applicationId: 'app', resources: [view], resourceId: 'view', metric: true })
    expect(channels).toEqual(['nocode.records.app.object'])
    expect(host.querySelector('.ant-statistic-content')!.textContent).toBe('5')

    const answer = deferred<{ list: unknown[]; total: number }>()
    api.page.mockReturnValue(answer.promise)
    push({ created: ['n1'] })
    await flush(400)
    expect(api.page).toHaveBeenCalledTimes(2)
    // 首次加载照旧；静默重取失败不弹全局错误通知
    expect(api.page.mock.calls[0]).toHaveLength(1)
    expect(api.page.mock.calls[1][1]).toEqual({ quiet: true })
    expect(spinning()).toBe(false)
    expect(host.querySelector('.ant-statistic-content')!.textContent).toBe('5')
    answer.resolve({ list: [], total: 6 })
    await flush()
    expect(host.querySelector('.ant-statistic-content')!.textContent).toBe('6')
  })
})

describe('详情区', () => {
  const detail = () => host.querySelector<HTMLElement>('[data-editor]')!
  async function mountDetail() {
    api.get.mockResolvedValue(record('r1', '原值'))
    await mount(BusinessBlock, {
      applicationId: 'app',
      resources: [form],
      resourceId: 'form',
      recordId: 'r1',
      detail: true
    })
    expect(detail().dataset.editor).toBe('原值')
    expect(api.get).toHaveBeenCalledTimes(1)
  }
  const editButton = () =>
    Array.from(host.querySelectorAll('button')).find(button => button.textContent?.includes('编辑资料'))

  it('K2 帧涉及这条记录且不在编辑：静默重取，表单不重建', async () => {
    await mountDetail()
    api.get.mockResolvedValue(record('r1', '别人改过'))
    push({ updated: ['r1'] })
    await flush(400)
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(detail().dataset.editor).toBe('别人改过')
    expect(spinning()).toBe(false)
    expect(log).toEqual(['editor'])
  })

  it('K2 帧不涉及这条记录：不取', async () => {
    await mountDetail()
    push({ updated: ['r2'], created: ['r3'], deleted: ['r4'] })
    await flush(5_000)
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('K2 正在编辑资料：不取、不动正在改的内容', async () => {
    await mountDetail()
    editButton()!.click()
    await flush()
    expect(detail().dataset.readOnly).toBe('false')
    api.get.mockResolvedValue(record('r1', '别人改过'))
    push({ updated: ['r1'] })
    await flush(400)
    push({ kind: 'object' })
    await flush(6_000)
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(detail().dataset.editor).toBe('原值')
  })

  it('这条记录被别人删了：内容留着，显示「这条记录已被删除」，「编辑资料」不再出现', async () => {
    await mountDetail()
    expect(editButton()).toBeTruthy()
    push({ deleted: ['r1'] })
    await flush(400)
    expect(detail().dataset.editor).toBe('原值')
    expect(host.querySelector('[data-record-deleted]')!.textContent).toContain('这条记录已被删除')
    expect(editButton()).toBeUndefined()
    // 已经知道被删了：不去取它
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('不带记录的帧、重取时发现记录不存在：同样显示「这条记录已被删除」', async () => {
    await mountDetail()
    api.get.mockRejectedValue(missing())
    push({ kind: 'object' })
    await flush(400)
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(api.get.mock.calls[1]).toEqual(['app', 'object', 'r1', { quiet: true }])
    expect(detail().dataset.editor).toBe('原值')
    expect(host.querySelector('[data-record-deleted]')).not.toBeNull()
    expect(editButton()).toBeUndefined()
    expect(host.textContent).not.toContain('记录不存在')
  })

  it('所在页面在后台期间这条记录被删：切回来静默重取时发现，同样提示', async () => {
    api.get.mockResolvedValue(record('r1', '原值'))
    const shown = ref('page')
    await mount(
      defineComponent({
        setup: () => () =>
          h(KeptPages, { pageKey: shown.value, max: 5 }, () =>
            shown.value === 'page'
              ? h(BusinessBlock, {
                  key: 'page',
                  applicationId: 'app',
                  resources: [form],
                  resourceId: 'form',
                  recordId: 'r1',
                  detail: true
                })
              : h('p', { key: 'other' }, '别的页面')
          )
      }),
      {}
    )
    shown.value = 'other'
    await flush()
    api.get.mockRejectedValue(missing())
    push({ deleted: ['r1'] })
    await flush(5_000)
    expect(api.get).toHaveBeenCalledTimes(1)
    shown.value = 'page'
    await flush()
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(detail().dataset.editor).toBe('原值')
    expect(host.querySelector('[data-record-deleted]')).not.toBeNull()
    expect(editButton()).toBeUndefined()
  })

  it('表单区（新增表单）不订阅', async () => {
    await mount(BusinessBlock, { applicationId: 'app', resources: [form], resourceId: 'form' })
    expect(channels).toEqual([])
    push({ kind: 'object' })
    await flush(5_000)
    expect(api.get).not.toHaveBeenCalled()
  })
})

const result = (total: string): ReportResult => ({
  dimensionNames: [],
  metrics: [{ id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount' }],
  groups: [],
  totals: { in: total },
  totalGroups: 0,
  recordCount: 1,
  canExport: false,
  timeZone: 'Asia/Tokyo'
})
const report: ApplicationResource = {
  id: 'report',
  kind: ResourceKind.REPORT,
  code: 'report',
  name: '入金合计',
  config: {
    ...defaultReport('object'),
    display: 'METRIC',
    metrics: [{ id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount' }]
  } as unknown as ApplicationResource['config']
}

describe('统计', () => {
  async function mountReportWithDrill() {
    api.report.mockImplementation(async () => result('100'))
    api.reportDetails.mockImplementation(async () => ({ list: [row('D1')], total: 1 }))
    await mount(ReportBlock, { applicationId: 'app', resource: report })
    expect(channels).toEqual(['nocode.records.app.object'])
    host.querySelector<HTMLElement>('.metric-cell')!.click()
    await flush()
    expect(document.querySelector('.ant-drawer-open')).not.toBeNull()
    expect(api.report).toHaveBeenCalledTimes(1)
  }

  it('K3 来帧：比列表慢一拍地重查；只读下钻抽屉保持打开；新结果到达前旧结果不消失', async () => {
    await mountReportWithDrill()
    const cell = host.querySelector('.metric-cell')!
    const answer = deferred<ReportResult>()
    api.report.mockReturnValue(answer.promise)
    push({ updated: ['r1'] })
    await flush(1_499)
    // 统计的静默期是 1.5 秒
    expect(api.report).toHaveBeenCalledTimes(1)
    await flush(1)
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(spinning()).toBe(false)
    expect(host.querySelector('.metric-cell')).toBe(cell)
    expect(host.querySelector('.metric-cell strong')!.textContent).toBe('100')
    expect(document.querySelector('.ant-drawer-open')).not.toBeNull()

    answer.resolve(result('250'))
    await flush()
    expect(host.querySelector('.metric-cell strong')!.textContent).toBe('250')
    expect(document.querySelector('.ant-drawer-open')).not.toBeNull()
    // 首次查询与点开下钻照旧；静默重查（统计与只读明细）失败不弹全局错误通知
    expect(api.report.mock.calls[0]).toHaveLength(1)
    expect(api.report.mock.calls[1][1]).toEqual({ quiet: true })
    expect(api.reportDetails).toHaveBeenCalledTimes(2)
    expect(api.reportDetails.mock.calls[0]).toHaveLength(1)
    expect(api.reportDetails.mock.calls[1][1]).toEqual({ quiet: true })
    // 只重查数字，不重取对象结构
    expect(api.model).toHaveBeenCalledTimes(1)
  })

  it('K4 回归：原有的硬刷新仍会关掉只读下钻抽屉', async () => {
    await mountReportWithDrill()
    refresh.value++
    await flush()
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(document.querySelector('.ant-drawer-open')).toBeNull()
  })
})

describe('附加信息', () => {
  async function mountExtras() {
    api.get.mockResolvedValue(record('r1', 'r1', []))
    await mount(RecordExtras, { applicationId: 'app', form: form.config, recordId: 'r1', kind: NodeKind.PROCESSES })
    expect(host.textContent).toContain('当前记录尚无审批记录')
    expect(channels).toEqual(['nocode.records.app.object'])
  }

  it('K5 帧涉及这条记录：静默重取，不出骨架屏', async () => {
    await mountExtras()
    const answer = deferred<unknown>()
    api.get.mockReturnValue(answer.promise)
    push({ updated: ['r1'] })
    await flush(400)
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(host.querySelector('.ant-skeleton')).toBeNull()
    expect(host.textContent).toContain('当前记录尚无审批记录')
    answer.resolve(
      record('r1', 'r1', [
        { businessKey: 'k', name: '付款审批', instanceId: 'i', status: 'RUNNING', createTime: '2026-10-01 10:00:00' }
      ])
    )
    await flush()
    expect(host.textContent).toContain('付款审批')
  })

  it('K5 帧不涉及这条记录：不取；记录被删：内容留着、不报错', async () => {
    await mountExtras()
    push({ updated: ['r2'] })
    await flush(5_000)
    expect(api.get).toHaveBeenCalledTimes(1)
    api.get.mockRejectedValue(missing())
    push({ kind: 'object' })
    await flush(5_000)
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(api.get.mock.calls[1]).toEqual(['app', 'object', 'r1', { quiet: true }])
    expect(host.textContent).toContain('当前记录尚无审批记录')
    expect(host.textContent).not.toContain('记录不存在')
  })
})
