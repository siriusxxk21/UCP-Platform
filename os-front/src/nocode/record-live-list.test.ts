// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, provide, ref, type App, type Ref } from 'vue'
import Antd from 'ant-design-vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { realtimeBus } from '@/realtime/bus'
import { realtimeClientId } from '@/realtime/client-id'
import { useRealtimeStore } from '@/stores/realtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { RecordsChanged } from '@/types/nocode/record-live'
import { applicationRefreshKey } from './application-context'
import { KeptPages } from './kept-pages'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  delete: vi.fn(),
  action: vi.fn(),
  viewModel: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'record-live-list-test' } }) }))
const confirmations = vi.hoisted(() => new Set<(topic: string) => void>())
vi.mock('@/realtime/runtime', () => ({
  initializeRealtimeRuntime: () => undefined,
  realtimeRuntime: {
    acquireChannel: () => () => undefined,
    onChannelSubscribed: (handler: (topic: string) => void) => {
      confirmations.add(handler)
      return () => confirmations.delete(handler)
    }
  }
}))
/** 服务端确认了本列表那个主题的订阅。 */
const confirmed = () => confirmations.forEach(handler => handler('nocode.records.app.object'))
const log: string[] = []
const editorRecords: unknown[] = []
// 表格回报翻页时带上当前页大小与排序（与真实表格一致），否则列表会当成换了页大小而回到第 1 页
const paging = vi.hoisted(() => ({ size: 0 }))
vi.mock('@/components/os-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h, ref, watch } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource', 'loading', 'selectedRowKeys', 'customRow'],
      emits: ['selection-change', 'change', 'batch-delete'],
      setup(props, { emit, slots }) {
        const spun = ref(false)
        watch(
          () => props.loading,
          value => {
            if (value) spun.value = true
          },
          { flush: 'sync' }
        )
        const rowClass = (row: unknown) => String(props.customRow?.(row)?.class || '')
        return () =>
          h(
            'div',
            {
              'data-table': true,
              'data-rows': props.dataSource.map((row: { id: string }) => row.id).join(','),
              'data-names': props.dataSource.map((row: { values: { name: string } }) => row.values.name).join(','),
              'data-selected': (props.selectedRowKeys || []).join(','),
              'data-spun': String(spun.value),
              'data-loading': String(!!props.loading),
              'data-live': props.dataSource
                .filter((row: unknown) => rowClass(row).includes('business-records__row--live'))
                .map((row: { id: string }) => row.id)
                .join(',')
            },
            [
              h('button', { 'data-select': true, onClick: () => emit('selection-change', ['A', 'B']) }, '全选'),
              h('button', { 'data-calm': true, onClick: () => (spun.value = false) }, '清 loading 记录'),
              h('button', {
                'data-page-two': true,
                onClick: () => emit('change', { current: 2, pageSize: paging.size }, null, { order: 'descend' })
              }),
              h('button', { 'data-batch': true, onClick: () => emit('batch-delete', props.selectedRowKeys) }),
              slots.toolbar?.(),
              ...props.dataSource.map((record: { id: string }) =>
                h('section', { 'data-row': record.id }, slots.bodyCell?.({ record, column: { key: 'actions' } }))
              )
            ]
          )
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordSurface.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', { 'data-surface': props.title }, slots.default?.()) : null
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: defineComponent({
    props: { record: Object, readOnly: Boolean },
    setup(props) {
      log.push('editor')
      onUnmounted(() => log.push('editor-gone'))
      return () => {
        editorRecords.push(props.record)
        return h('form', {
          'data-editor': props.record?.record.values.name ?? 'new',
          'data-read-only': String(!!props.readOnly)
        })
      }
    }
  })
}))
vi.mock('@/views/nocode/application/components/PageRenderer.vue', () => ({
  // 列表用异步组件加载它：标明是模块，Vue 才会取 default
  __esModule: true,
  default: defineComponent({
    props: { recordId: String, readOnly: Boolean },
    setup: props => () => h('div', { 'data-page': props.recordId, 'data-page-read-only': String(!!props.readOnly) })
  })
}))
vi.mock('@/views/nocode/application/components/DataViewChildren.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'

const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE', 'DELETE'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string, name = id, revision = '1') => ({ id, revision, values: { name }, permissions })
const record = (id: string, name = id, revision = '1') => ({ record: row(id, name, revision), details: {} })
const missing = () => Object.assign(new Error('记录不存在或不可访问'), { businessCode: 1_050_000_002 })
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
/** shared：处在运行页的共享刷新范围内（自己的写入会让本页硬刷新一次）。 */
async function mount(props: Record<string, unknown> = {}, { shared = false, connected = true } = {}) {
  host = document.createElement('div')
  document.body.append(host)
  refresh = ref(0)
  app = createApp(
    defineComponent({
      setup() {
        if (shared) provide(applicationRefreshKey, refresh)
        return () => h(BusinessRecords, { applicationId: 'app', objectId: 'object', ...props })
      }
    })
  )
  app.use(pinia)
  app.use(Antd)
  if (connected) useRealtimeStore().setConnectionStatus('connected')
  app.mount(host)
  await flush()
  paging.size = api.page.mock.calls[0][0].pageSize
}
const table = () => host.querySelector<HTMLElement>('[data-table]')!
function click(id: string, action: string) {
  Array.from(host.querySelectorAll<HTMLButtonElement>(`section[data-row="${id}"] button`))
    .find(button => button.textContent?.includes(action))!
    .click()
}
/** 点行上的「删除」并在气泡里确认。 */
async function removeRow(id: string) {
  click(id, '删除')
  await flush(300)
  document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary')!.click()
  await flush()
}
async function selectAll() {
  table().querySelector<HTMLElement>('[data-select]')!.click()
  table().querySelector<HTMLElement>('[data-calm]')!.click()
  await flush()
  expect(table().dataset.selected).toBe('A,B')
}

beforeEach(() => {
  vi.useFakeTimers()
  confirmations.clear()
  seq = 0
  log.length = editorRecords.length = 0
  pinia = createPinia()
  setActivePinia(pinia)
  api.model.mockResolvedValue({
    writable: true,
    permissions,
    object: {
      objectId: 'object',
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
  // 真实接口每次返回的都是新对象
  api.page.mockImplementation(async () => ({ list: [row('A'), row('B')], total: 2 }))
  api.get.mockImplementation(async (_app: string, _object: string, id: string) => record(id))
  api.delete.mockResolvedValue(true)
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
  vi.useRealTimers()
})

describe('列表随推送软刷新', () => {
  it('J1 来一帧：多取一次，页码、页大小、筛选、排序与上一次完全相同', async () => {
    await mount()
    table().querySelector<HTMLElement>('[data-page-two]')!.click()
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(api.page.mock.calls[1][0]).toMatchObject({ pageNo: 2, pageSize: paging.size, descending: true })

    push({ updated: ['A'] })
    await flush(399)
    expect(api.page).toHaveBeenCalledTimes(2)
    await flush(1)
    expect(api.page).toHaveBeenCalledTimes(3)
    expect(api.page.mock.calls[2][0]).toEqual(api.page.mock.calls[1][0])
    // 静默重取失败不弹全局错误通知；用户自己翻页的那次照旧（不带静默选项）
    expect(api.page.mock.calls[2][1]).toEqual({ quiet: true })
    expect(api.page.mock.calls[1]).toHaveLength(1)
  })

  it('J2 全程不转圈；仍在本页的勾选保留，不在了的去掉', async () => {
    await mount()
    await selectAll()
    const answer = deferred<{ list: unknown[]; total: number }>()
    api.page.mockReturnValue(answer.promise)
    push({ updated: ['A'] })
    await flush(400)
    // 请求还挂着：不遮挡、不清空、勾选还在
    expect(table().dataset.spun).toBe('false')
    expect(table().dataset.rows).toBe('A,B')
    expect(table().dataset.selected).toBe('A,B')

    answer.resolve({ list: [row('A', '改过'), row('C')], total: 2 })
    await flush()
    expect(table().dataset.rows).toBe('A,C')
    expect(table().dataset.names).toBe('改过,C')
    expect(table().dataset.selected).toBe('A')
    expect(table().dataset.spun).toBe('false')
  })

  it('J3 软刷新失败：旧行保留，页面不出现错误提示', async () => {
    await mount()
    await selectAll()
    api.page.mockRejectedValue(new Error('服务器开小差'))
    push({ updated: ['A'] })
    await flush(400)
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(table().dataset.rows).toBe('A,B')
    expect(table().dataset.selected).toBe('A,B')
    expect(document.body.textContent).not.toContain('服务器开小差')
  })

  it('J4 逐条列出的帧：重取后仍在本页的那些行淡色提示，约 2 秒后去掉', async () => {
    await mount()
    api.page.mockImplementation(async () => ({ list: [row('A', '改过'), row('B'), row('C')], total: 3 }))
    push({ created: ['C', 'Z'], updated: ['A'] })
    await flush(399)
    expect(table().dataset.live).toBe('')
    await flush(1)
    expect(table().dataset.rows).toBe('A,B,C')
    // Z 不在本页；B 没变
    expect(table().dataset.live).toBe('A,C')
    await flush(1_999)
    expect(table().dataset.live).toBe('A,C')
    await flush(1)
    expect(table().dataset.live).toBe('')
  })

  it('J4 不带记录的帧（整体刷新）：不提示', async () => {
    await mount()
    api.page.mockImplementation(async () => ({ list: [row('A', '改过'), row('B')], total: 2 }))
    push({ kind: 'object' })
    await flush(400)
    expect(table().dataset.names).toBe('改过,B')
    expect(table().dataset.live).toBe('')
  })

  it('J10 回归：手动刷新照旧转圈并清勾选', async () => {
    await mount()
    await selectAll()
    const answer = deferred<{ list: unknown[]; total: number }>()
    api.page.mockReturnValue(answer.promise)
    Array.from(table().querySelectorAll('button'))
      .find(button => button.textContent?.includes('刷新'))!
      .click()
    await flush()
    expect(table().dataset.loading).toBe('true')
    expect(table().dataset.selected).toBe('')
    // 用户主动刷新：失败时照旧弹全局错误通知（不带静默选项）
    expect(api.page.mock.calls.at(-1)).toHaveLength(1)
    answer.resolve({ list: [row('A'), row('B')], total: 2 })
    await flush()
    expect(table().dataset.loading).toBe('false')
  })

  it('可见的加载还在途时来帧：等它跑完再静默补一次，不叠在它上面', async () => {
    await mount()
    const answer = deferred<{ list: unknown[]; total: number }>()
    api.page.mockReturnValueOnce(answer.promise)
    table().querySelector<HTMLElement>('[data-page-two]')!.click()
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    push({ updated: ['A'] })
    await flush(1_000)
    // 那次可见的加载发出时数据可能还是旧的；它没回来之前不发第二个请求
    expect(api.page).toHaveBeenCalledTimes(2)
    answer.resolve({ list: [row('A'), row('B')], total: 2 })
    await flush()
    expect(api.page).toHaveBeenCalledTimes(3)
    expect(api.page.mock.calls[2][0]).toEqual(api.page.mock.calls[1][0])
  })

  it('断开期间不显示任何提示；重连后静默补取一次', async () => {
    await mount()
    await selectAll()
    const store = useRealtimeStore()
    store.setConnectionStatus('reconnecting')
    await flush(15_000)
    expect(document.body.textContent).not.toContain('实时更新')
    expect(document.body.textContent).not.toContain('重连')
    expect(api.page).toHaveBeenCalledTimes(1)
    store.setConnectionStatus('connected')
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(table().dataset.spun).toBe('false')
    expect(table().dataset.selected).toBe('A,B')
    expect(document.body.textContent).not.toContain('实时更新')
  })
})

describe('打开页面时分页请求发几次', () => {
  it('已连上、订阅在 1 秒内被确认：只发首次那一次', async () => {
    await mount()
    await flush(200)
    confirmed()
    await flush(60_000)
    expect(api.page).toHaveBeenCalledTimes(1)
  })

  it('已连上、订阅确认晚于 1 秒：静默补取一次，共两次', async () => {
    await mount()
    await selectAll()
    await flush(1_500)
    expect(api.page).toHaveBeenCalledTimes(1)
    confirmed()
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(table().dataset.spun).toBe('false')
    expect(table().dataset.selected).toBe('A,B')
    await flush(60_000)
    expect(api.page).toHaveBeenCalledTimes(2)
  })

  it('挂载时连接没连上、过了 1 秒才连上并确认订阅：静默补取一次，共两次', async () => {
    await mount({}, { connected: false })
    await flush(3_000)
    expect(api.page).toHaveBeenCalledTimes(1)
    useRealtimeStore().setConnectionStatus('connected')
    confirmed()
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(table().dataset.spun).toBe('false')
    await flush(60_000)
    expect(api.page).toHaveBeenCalledTimes(2)
  })

  it('整页刚打开、连接在 1 秒内连上并确认订阅：只发首次那一次', async () => {
    await mount({}, { connected: false })
    await flush(150)
    confirmed()
    useRealtimeStore().setConnectionStatus('connected')
    await flush(60_000)
    expect(api.page).toHaveBeenCalledTimes(1)
  })

  it('断线重连恢复订阅：静默补取一次', async () => {
    await mount()
    confirmed()
    await flush(5_000)
    const store = useRealtimeStore()
    store.setConnectionStatus('reconnecting')
    await flush(2_000)
    confirmed()
    store.setConnectionStatus('connected')
    await flush(60_000)
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(table().dataset.spun).toBe('false')
  })
})

describe('只读详情抽屉', () => {
  const detail = () => host.querySelector<HTMLElement>('aside [data-editor]')
  async function openDetail(id = 'A') {
    click(id, '查看')
    await flush()
    expect(detail()!.dataset.editor).toBe(id)
    expect(detail()!.dataset.readOnly).toBe('true')
  }

  it('J5 帧里改的是正在看的这条：静默重取并替换内容，抽屉不关', async () => {
    await mount()
    await openDetail()
    expect(api.get).toHaveBeenCalledTimes(1)
    api.get.mockImplementation(async (_app: string, _object: string, id: string) => record(id, '别人改过', '2'))
    push({ updated: ['A'] })
    await flush(400)
    expect(api.get).toHaveBeenCalledTimes(2)
    // 静默：出错也不弹全局通知
    expect(api.get.mock.calls[1]).toEqual(['app', 'object', 'A', { quiet: true }])
    expect(detail()!.dataset.editor).toBe('别人改过')
    // 同一个表单，没有被拆掉重建
    expect(log).toEqual(['editor'])
    expect(document.body.textContent).not.toContain('这条记录已被删除')
  })

  it('J5 帧里改的是别的记录：不重取这条', async () => {
    await mount()
    await openDetail()
    push({ updated: ['B'], created: ['C'], deleted: ['D'] })
    await flush(400)
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('J5 不带记录的帧：也重取这条', async () => {
    await mount()
    await openDetail()
    push({ kind: 'object' })
    await flush(400)
    expect(api.get).toHaveBeenCalledTimes(2)
  })

  it('J6 帧里删的是正在看的这条：抽屉不关，内容留着，顶上显示「这条记录已被删除」', async () => {
    await mount()
    await openDetail()
    api.page.mockImplementation(async () => ({ list: [row('B')], total: 1 }))
    push({ deleted: ['A'] })
    await flush(400)
    expect(table().dataset.rows).toBe('B')
    expect(detail()!.dataset.editor).toBe('A')
    expect(host.querySelector('aside [data-record-deleted]')!.textContent).toContain('这条记录已被删除')
    // 已经知道被删了：不再去取它
    expect(api.get).toHaveBeenCalledTimes(1)
    push({ kind: 'object' })
    await flush(5_000)
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('J6 不带记录的帧、重取时发现记录不存在：同样显示「这条记录已被删除」', async () => {
    await mount()
    await openDetail()
    api.get.mockRejectedValue(missing())
    push({ kind: 'object' })
    await flush(400)
    expect(detail()!.dataset.editor).toBe('A')
    expect(host.querySelector('aside [data-record-deleted]')!.textContent).toContain('这条记录已被删除')
    // 列表上方不出现「记录不存在」的报错
    expect(host.textContent).not.toContain('记录不存在')
  })

  it('J6 重取因别的原因失败：不当成被删除', async () => {
    await mount()
    await openDetail()
    api.get.mockRejectedValue(new Error('网络错误'))
    push({ updated: ['A'] })
    await flush(400)
    expect(host.querySelector('aside [data-record-deleted]')).toBeNull()
    expect(detail()!.dataset.editor).toBe('A')
  })

  it('J6 所在页面在后台期间这条记录被删：切回来静默重取时发现，同样提示，不弹「记录不存在」', async () => {
    const shown = ref('list')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeptPages, { pageKey: shown.value, max: 5 }, () =>
            shown.value === 'list'
              ? h(BusinessRecords, { key: 'list', applicationId: 'app', objectId: 'object' })
              : h('p', { key: 'other' }, '别的页面')
          )
      })
    )
    app.use(pinia)
    app.use(Antd)
    useRealtimeStore().setConnectionStatus('connected')
    app.mount(host)
    await flush()
    await openDetail()
    shown.value = 'other'
    await flush()
    api.get.mockRejectedValue(missing())
    push({ deleted: ['A'] })
    await flush(5_000)
    // 在后台：不取数
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.page).toHaveBeenCalledTimes(1)
    shown.value = 'list'
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(api.get.mock.calls[1]).toEqual(['app', 'object', 'A', { quiet: true }])
    expect(detail()!.dataset.editor).toBe('A')
    expect(host.querySelector('aside [data-record-deleted]')!.textContent).toContain('这条记录已被删除')
  })

  it('J6 换看另一条：上一条的「已被删除」不带过来', async () => {
    await mount()
    await openDetail()
    push({ deleted: ['A'] })
    await flush(400)
    expect(host.querySelector('aside [data-record-deleted]')).not.toBeNull()
    click('B', '查看')
    await flush()
    expect(detail()!.dataset.editor).toBe('B')
    expect(host.querySelector('aside [data-record-deleted]')).toBeNull()
  })

  it('J6 配了详情页的抽屉：被删后顶上同样提示，页面里的编辑、动作按钮随只读约束隐藏', async () => {
    const page: ApplicationResource = {
      id: 'detail-page',
      kind: ResourceKind.PAGE,
      code: 'detail-page',
      name: '详情页',
      config: { nodes: [] } as unknown as ApplicationResource['config']
    }
    await mount({
      resources: [page],
      view: { objectId: 'object', fieldIds: ['name'], detailPageId: 'detail-page' }
    })
    click('A', '查看')
    await flush()
    const rendered = () => host.querySelector<HTMLElement>('aside [data-page]')!
    expect(rendered().dataset.page).toBe('A')
    expect(rendered().dataset.pageReadOnly).toBe('false')
    // 改了：页面里的区块各自重取，抽屉这一层只确认记录还在
    push({ updated: ['A'] })
    await flush(400)
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(rendered().dataset.pageReadOnly).toBe('false')
    push({ deleted: ['A'] })
    await flush(5_000)
    expect(host.querySelector('aside [data-record-deleted]')!.textContent).toContain('这条记录已被删除')
    expect(rendered().dataset.page).toBe('A')
    expect(rendered().dataset.pageReadOnly).toBe('true')
  })
})

describe('编辑抽屉', () => {
  it('J7 编辑抽屉开着时来帧：交给编辑器的记录还是同一个对象，编辑器没有重新挂载', async () => {
    await mount()
    click('A', '编辑')
    await flush()
    expect(host.querySelector<HTMLElement>('aside [data-editor]')!.dataset.readOnly).toBe('false')
    const given = editorRecords.at(-1)
    expect(given).toBeTruthy()
    expect(api.get).toHaveBeenCalledTimes(1)

    api.get.mockImplementation(async (_app: string, _object: string, id: string) => record(id, '别人改过', '2'))
    api.page.mockImplementation(async () => ({ list: [row('A', '别人改过', '2'), row('B')], total: 2 }))
    push({ updated: ['A'] })
    await flush(400)
    push({ kind: 'object' })
    await flush(5_000)
    // 列表在背后更新了
    expect(table().dataset.names).toBe('别人改过,B')
    // 编辑器手里的记录没有被换掉（还是同一个对象），也没有为它去取数
    expect(editorRecords.every(value => value === given)).toBe(true)
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(host.querySelector<HTMLElement>('aside [data-editor]')!.dataset.editor).toBe('A')
    expect(log).toEqual(['editor'])
  })
})

describe('自己发起的变更', () => {
  it('J8 自己删一条：只因原有的硬刷新取一次，推送不再引发第二次', async () => {
    await mount({}, { shared: true })
    expect(api.page).toHaveBeenCalledTimes(1)
    await removeRow('A')
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(api.page).toHaveBeenCalledTimes(2)
    push({ deleted: ['A'], origin: realtimeClientId })
    await flush(10_000)
    expect(api.page).toHaveBeenCalledTimes(2)
  })

  it('J8 对照：同一帧若是别人发起的，会再取一次', async () => {
    await mount({}, { shared: true })
    await removeRow('A')
    expect(api.page).toHaveBeenCalledTimes(2)
    push({ deleted: ['A'], origin: 'someone-else' })
    await flush(400)
    expect(api.page).toHaveBeenCalledTimes(3)
  })

  it('J10 回归：自己删除后的硬刷新照旧转圈并清勾选', async () => {
    await mount({}, { shared: true })
    await selectAll()
    await removeRow('A')
    expect(table().dataset.spun).toBe('true')
    expect(table().dataset.selected).toBe('')
  })
})
