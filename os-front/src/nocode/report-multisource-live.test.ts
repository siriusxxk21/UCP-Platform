// @vitest-environment jsdom
// 实时刷新整条链路是真的（总线 → 频道登记 → useRecordLive → ReportBlock），假的只有 WebSocket 运行时（照 record-live-blocks.test.ts）。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, provide, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Antd from 'ant-design-vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { realtimeBus } from '@/realtime/bus'
import { useRealtimeStore } from '@/stores/realtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { RecordsChanged } from '@/types/nocode/record-live'
import { applicationRefreshKey } from './application-context'
import { example1Config, example1Result, multiObjects } from './report-multisource-fixture'

const api = vi.hoisted(() => ({ model: vi.fn(), report: vi.fn(), reportDetails: vi.fn() }))
const channels = vi.hoisted(() => [] as string[])
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-multisource-live-test' } }) }))
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
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportFilterInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

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

let seq = 0
function push(objectId: string) {
  seq++
  realtimeBus.emit('nocode.records.changed', {
    applicationId: 'app',
    objectId,
    kind: 'ids',
    many: false,
    created: [],
    updated: ['r1'],
    deleted: [],
    origin: null,
    epoch: 'e1',
    fromSeq: seq,
    seq
  } as RecordsChanged)
}
let app: App | undefined, host: HTMLDivElement, pinia: Pinia
const flush = async (ms = 0) => {
  await vi.advanceTimersByTimeAsync(ms)
  for (let index = 0; index < 6; index++) {
    await vi.advanceTimersByTimeAsync(0)
    await nextTick()
  }
}
const report: ApplicationResource = {
  id: 'report',
  kind: ResourceKind.REPORT,
  code: 'report',
  name: '利润统计',
  config: example1Config() as unknown as ApplicationResource['config']
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(
    defineComponent({
      setup() {
        provide(applicationRefreshKey, ref(0))
        return () => h(ReportBlock, { applicationId: 'app', resource: report })
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
beforeEach(() => {
  vi.useFakeTimers()
  seq = 0
  channels.length = 0
  pinia = createPinia()
  setActivePinia(pinia)
  api.model.mockImplementation(async (_app: string, objectId: string) => ({
    writable: false,
    permissions: { actions: ['READ'], readFields: [], writeFields: [], readDetails: [], writeDetails: [] },
    object: multiObjects[objectId].definition,
    details: {}
  }))
  api.report.mockImplementation(async () => example1Result())
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
  vi.useRealTimers()
})

describe('F9 运行端：实时刷新订阅全部来源的对象', () => {
  it('订阅入住记录与支出（三个来源、两个对象，去重）；任一对象的变更都触发静默刷新', async () => {
    await mount()
    expect([...channels].sort()).toEqual(['nocode.records.app.expense', 'nocode.records.app.stay'])
    expect(api.report).toHaveBeenCalledTimes(1)
    push('expense')
    await flush(1_500)
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(api.report.mock.calls[1][1]).toEqual({ quiet: true })
    push('stay')
    await flush(1_500)
    expect(api.report).toHaveBeenCalledTimes(3)
    expect(api.report.mock.calls[2][1]).toEqual({ quiet: true })
    // 别的对象的变更不触发
    push('voucher')
    await flush(1_500)
    expect(api.report).toHaveBeenCalledTimes(3)
  })
})
