// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, provide, reactive, ref, type App, type PropType } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { realtimeBus } from '@/realtime/bus'
import { realtimeClientId } from '@/realtime/client-id'
import { useRealtimeStore } from '@/stores/realtime'
import type { RecordsChanged } from '@/types/nocode/record-live'
import { applicationRefreshKey } from './application-context'
import { KeptPages } from './kept-pages'
import { runtimeDataPolicy, useRuntimeDataRefresh } from './runtime-data'
import { taskEntrySessionKey } from './task-entry-context'
import { invalidateRecords, recordsChannel, useRecordLive, type LiveChange } from './record-live'

const channel = vi.hoisted(() => ({
  acquired: [] as Array<{ key: string; topic: string }>,
  released: [] as string[],
  fail: false,
  listeners: new Set<(topic: string) => void>()
}))
vi.mock('@/realtime/runtime', () => ({
  initializeRealtimeRuntime: () => undefined,
  realtimeRuntime: {
    acquireChannel: (descriptor: { key: string; topic: string }) => {
      if (channel.fail) throw new Error('RealtimeRuntime 尚未初始化')
      channel.acquired.push(descriptor)
      return () => channel.released.push(descriptor.key)
    },
    onChannelSubscribed: (handler: (topic: string) => void) => {
      if (channel.fail) throw new Error('RealtimeRuntime 尚未初始化')
      channel.listeners.add(handler)
      return () => channel.listeners.delete(handler)
    }
  }
}))
/** 服务端确认了这个主题的订阅。 */
const confirmed = (topic = 'nocode.records.app.obj') => channel.listeners.forEach(listener => listener(topic))

let seq = 0
function push(patch: Partial<RecordsChanged> = {}) {
  seq++
  realtimeBus.emit('nocode.records.changed', {
    applicationId: 'app',
    objectId: 'obj',
    kind: 'ids',
    many: false,
    created: [],
    updated: ['r' + seq],
    deleted: [],
    origin: null,
    epoch: 'e1',
    fromSeq: seq,
    seq,
    ...patch
  })
}

const state = reactive({ app: 'app', ids: ['obj'], enabled: true })
const reload = vi.fn((_change: LiveChange): void | Promise<void> => undefined)
const quiet = vi.fn()
const seen = { stale: false, touched: '' }
const Host = defineComponent({
  props: { cadence: String as PropType<'list' | 'report'> },
  setup(props) {
    const live = useRecordLive({
      applicationId: () => state.app,
      objectIds: () => state.ids,
      reload,
      enabled: () => state.enabled,
      cadence: props.cadence
    })
    // 真实宿主都登记了保活机制的静默重取：页面从后台回来时由它补取。
    useRuntimeDataRefresh({ interest: () => ({ applicationId: state.app, objectIds: state.ids }), refresh: quiet })
    return () => {
      seen.stale = live.stale.value
      seen.touched = [...live.touched.value].sort().join(',')
      return h('div', { 'data-host': true })
    }
  }
})
/** 处在运行页共享刷新范围内的宿主 */
const Shared = defineComponent({
  setup() {
    provide(applicationRefreshKey, ref(0))
    return () => h(Host)
  }
})
const TaskEntry = defineComponent({
  setup() {
    provide(taskEntrySessionKey, {
      key: 'entry',
      saveDraft: async () => ({}) as never,
      loadDraft: async () => null,
      checkDraft: async () => null
    })
    return () => h(Host)
  }
})

let app: App | undefined, pinia: Pinia
let visibility: DocumentVisibilityState = 'visible'
function show(value: DocumentVisibilityState) {
  visibility = value
  document.dispatchEvent(new Event('visibilitychange'))
}
function mount(component: Parameters<typeof h>[0] = Host, { withPinia = true, connected = true } = {}) {
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp(component as never)
  if (withPinia) {
    app.use(pinia)
    if (connected) useRealtimeStore().setConnectionStatus('connected')
  }
  app.mount(host)
}
const tick = async (ms: number) => {
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}

beforeEach(() => {
  vi.useFakeTimers()
  seq = 0
  Object.assign(state, { app: 'app', ids: ['obj'], enabled: true })
  channel.acquired.length = channel.released.length = 0
  channel.fail = false
  channel.listeners.clear()
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
  pinia = createPinia()
  setActivePinia(pinia)
  runtimeDataPolicy.refreshOnResume = true
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  runtimeDataPolicy.refreshOnResume = true
  vi.clearAllMocks()
  vi.useRealTimers()
})

describe('订阅频道', () => {
  it('H1 挂载时按「应用 + 对象」取得频道，卸载时释放', async () => {
    mount()
    expect(recordsChannel('41', '3057')).toEqual({ key: 'nocode.records:41:3057', topic: 'nocode.records.41.3057' })
    expect(channel.acquired).toEqual([{ key: 'nocode.records:app:obj', topic: 'nocode.records.app.obj' }])
    expect(channel.released).toEqual([])
    app!.unmount()
    app = undefined
    expect(channel.released).toEqual(['nocode.records:app:obj'])
    // 卸载之后的通知不再触发
    push()
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('H2 关心的对象换了：释放旧的、取得新的', async () => {
    mount()
    state.ids = ['other']
    await tick(0)
    expect(channel.released).toEqual(['nocode.records:app:obj'])
    expect(channel.acquired.map(item => item.key)).toEqual(['nocode.records:app:obj', 'nocode.records:app:other'])
    // 旧对象的通知不再理会，新对象的照常
    push({ objectId: 'obj' })
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    push({ objectId: 'other', fromSeq: 1, seq: 1 })
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('H2 多个对象各取各的，重复的只取一次；只少掉的那个被释放', async () => {
    state.ids = ['a', 'b', 'a']
    mount()
    expect(channel.acquired.map(item => item.key)).toEqual(['nocode.records:app:a', 'nocode.records:app:b'])
    state.ids = ['b']
    await tick(0)
    expect(channel.released).toEqual(['nocode.records:app:a'])
    expect(channel.acquired).toHaveLength(2)
  })

  it('H4 任务入口场景不订阅，通知也不触发', async () => {
    mount(TaskEntry)
    expect(channel.acquired).toEqual([])
    push()
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('H5 被关掉、没有应用、没有对象：都不订阅', async () => {
    state.enabled = false
    mount()
    expect(channel.acquired).toEqual([])
    push()
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    // 打开之后才订
    state.enabled = true
    await tick(0)
    expect(channel.acquired).toHaveLength(1)
    state.app = ''
    await tick(0)
    expect(channel.released).toEqual(['nocode.records:app:obj'])
    state.app = 'app'
    state.ids = ['']
    await tick(0)
    expect(channel.acquired).toHaveLength(1)
  })

  it('没装 Pinia 的独立夹具、实时通道还没初始化：照常挂载，只是不订阅', async () => {
    mount(Host, { withPinia: false })
    expect(channel.acquired).toEqual([])
    push()
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    app!.unmount()

    channel.fail = true
    mount()
    expect(channel.acquired).toEqual([])
    expect(document.querySelector('[data-host]')).not.toBeNull()
  })

  it('换过会话（总线重建）后重新连上：重新登记频道并接着收通知', async () => {
    mount()
    realtimeBus.reset()
    useRealtimeStore().reset()
    await tick(0)
    push()
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()

    useRealtimeStore().setConnectionStatus('connected')
    await tick(0)
    expect(channel.acquired.map(item => item.key)).toEqual(['nocode.records:app:obj', 'nocode.records:app:obj'])
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0].reason).toBe('resync')
    push({ fromSeq: 9, seq: 9 })
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(2)
    expect(reload.mock.calls[1][0].reason).toBe('push')
  })
})

describe('收到通知', () => {
  it('在眼前：去抖后软刷新一次，并给出过期与刚变过的记录', async () => {
    mount()
    push({ created: ['c1'], updated: ['u1'] })
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
    const change = reload.mock.calls[0][0]
    expect(change.reason).toBe('push')
    expect(change.object).toBe(false)
    expect([...change.created]).toEqual(['c1'])
    expect([...change.updated]).toEqual(['u1'])
    expect(seen.touched).toBe('c1,u1')
    await tick(2_000)
    expect(seen.touched).toBe('')
  })

  it('别的应用、别的对象的通知不理会', async () => {
    mount()
    push({ applicationId: 'another' })
    push({ objectId: 'other' })
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('软刷新失败：记过期，不向外抛', async () => {
    reload.mockRejectedValueOnce(new Error('请求失败'))
    mount()
    push()
    await tick(400)
    expect(seen.stale).toBe(true)
  })

  it('E13 主动声明某对象过期：关心它的实例软刷新一次，reason 是 local', async () => {
    mount()
    invalidateRecords('other')
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    invalidateRecords('obj')
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'local', object: true })
  })

  it('统计的节奏比列表慢', async () => {
    mount(defineComponent({ setup: () => () => h(Host, { cadence: 'report' }) }))
    push()
    await tick(400)
    expect(reload).not.toHaveBeenCalled()
    await tick(1_100)
    expect(reload).toHaveBeenCalledTimes(1)
  })
})

describe('H3 跳过自己', () => {
  it('自己发起、在眼前、处在共享刷新范围内：不软刷新', async () => {
    mount(Shared)
    push({ origin: realtimeClientId })
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('别人发起的：软刷新', async () => {
    mount(Shared)
    push({ origin: 'someone-else' })
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('不在共享刷新范围内（没有人替它硬刷新）：自己发起的也软刷新', async () => {
    mount(Host)
    push({ origin: realtimeClientId })
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('不在眼前（浏览器页签隐藏）：自己发起的也记过期，回来补取', async () => {
    mount(Shared)
    show('hidden')
    push({ origin: realtimeClientId })
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    expect(seen.stale).toBe(true)
    show('visible')
    await tick(0)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'activated', object: true })
  })

  it('不在眼前（所在页面被保活在后台）：自己发起的也记过期，回到前台由保活机制补取', async () => {
    // 回到前台只补取被标过期的，才看得出这一帧有没有被记下
    runtimeDataPolicy.refreshOnResume = false
    const shown = ref('page')
    mount(
      defineComponent({
        setup: () => () =>
          h(KeptPages, { pageKey: shown.value, max: 5 }, () =>
            shown.value === 'page' ? h(Shared, { key: 'page' }) : h('p', { key: 'other' })
          )
      })
    )
    shown.value = 'other'
    await tick(0)
    push({ origin: realtimeClientId })
    await tick(1_000)
    expect(quiet).not.toHaveBeenCalled()
    shown.value = 'page'
    await tick(0)
    expect(quiet).toHaveBeenCalledTimes(1)
    expect(reload).not.toHaveBeenCalled()
  })
})

describe('不在眼前', () => {
  function keptPage(shown: { value: string }) {
    return defineComponent({
      setup: () => () =>
        h(KeptPages, { pageKey: shown.value, max: 5 }, () =>
          shown.value === 'page' ? h(Host, { key: 'page' }) : h('p', { key: 'other' })
        )
    })
  }

  it('H6 所在页面被保活在后台：通知不引起取数；切回来由保活机制静默补取一次', async () => {
    runtimeDataPolicy.refreshOnResume = false
    const shown = ref('page')
    mount(keptPage(shown))
    shown.value = 'other'
    await tick(0)
    push()
    push()
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
    expect(quiet).not.toHaveBeenCalled()

    shown.value = 'page'
    await tick(0)
    expect(quiet).toHaveBeenCalledTimes(1)
    // 同一份数据不再另取一遍
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
    expect(quiet).toHaveBeenCalledTimes(1)
  })

  it('H6 在后台的页面不占订阅：切走时退订，切回来重新订上，并补取一次（即使其间没有收到通知）', async () => {
    // 回到前台只补取被标过期的：切走时就标了，所以其间没订阅也不会漏
    runtimeDataPolicy.refreshOnResume = false
    const shown = ref('page')
    mount(keptPage(shown))
    expect(channel.acquired.map(item => item.key)).toEqual(['nocode.records:app:obj'])
    shown.value = 'other'
    await tick(0)
    expect(channel.released).toEqual(['nocode.records:app:obj'])
    await tick(1_000)
    expect(quiet).not.toHaveBeenCalled()

    shown.value = 'page'
    await tick(0)
    expect(channel.acquired.map(item => item.key)).toEqual(['nocode.records:app:obj', 'nocode.records:app:obj'])
    expect(quiet).toHaveBeenCalledTimes(1)
    expect(reload).not.toHaveBeenCalled()
    // 回到前台之后的通知照常
    push()
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0].reason).toBe('push')
  })

  it('H6 去抖还没到页面就被切到后台：不取数，切回来补取', async () => {
    runtimeDataPolicy.refreshOnResume = false
    const shown = ref('page')
    mount(keptPage(shown))
    push()
    await tick(100)
    shown.value = 'other'
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
    shown.value = 'page'
    await tick(0)
    expect(quiet).toHaveBeenCalledTimes(1)
  })

  it('H6 在后台期间断线重连：不取数，切回来补取', async () => {
    runtimeDataPolicy.refreshOnResume = false
    const shown = ref('page')
    mount(keptPage(shown))
    shown.value = 'other'
    await tick(0)
    useRealtimeStore().setConnectionStatus('reconnecting')
    await tick(0)
    useRealtimeStore().setConnectionStatus('connected')
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    expect(quiet).not.toHaveBeenCalled()
    shown.value = 'page'
    await tick(0)
    expect(quiet).toHaveBeenCalledTimes(1)
  })

  it('E4 浏览器页签隐藏：通知只记过期；回到眼前软刷新一次', async () => {
    mount()
    show('hidden')
    push()
    push()
    await tick(30_000)
    expect(reload).not.toHaveBeenCalled()
    expect(seen.stale).toBe(true)
    show('visible')
    await tick(0)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'activated', object: true })
    expect(seen.stale).toBe(false)
  })
})

describe('连接状态', () => {
  it('E6 断线重连后在眼前的补取一次；恢复中（订阅还没全部恢复）不算连上', async () => {
    mount()
    const store = useRealtimeStore()
    store.setConnectionStatus('reconnecting')
    await tick(15_000)
    store.setConnectionStatus('recovering')
    await tick(0)
    expect(reload).not.toHaveBeenCalled()
    store.setConnectionStatus('connected')
    await tick(0)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'resync', object: true })
  })

  it('挂载时已连上、订阅在 1 秒内被确认：不补取（宿主刚取过数，不为这点空档再取一次）', async () => {
    mount()
    await tick(200)
    confirmed()
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('挂载时已连上、订阅确认晚于 1 秒（网络慢，或被拒后重试才成功）：补取一次', async () => {
    mount()
    await tick(1_001)
    expect(reload).not.toHaveBeenCalled()
    confirmed()
    await tick(0)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'resync', object: true })
    // 只看取得频道之后的第一次确认
    confirmed()
    await tick(60_000)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('挂载时已连上、订阅恰好在 1 秒时被确认：不补取', async () => {
    mount()
    await tick(1_000)
    confirmed()
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('挂载时连接还没连上、过了 1 秒才连上并确认订阅：补取一次', async () => {
    mount(Host, { connected: false })
    await tick(3_000)
    expect(reload).not.toHaveBeenCalled()
    useRealtimeStore().setConnectionStatus('connected')
    confirmed()
    await tick(0)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0].reason).toBe('resync')
    await tick(60_000)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('整页刚打开、连接在 1 秒内连上并确认订阅：不补取', async () => {
    mount(Host, { connected: false })
    await tick(150)
    useRealtimeStore().setConnectionStatus('recovering')
    confirmed()
    useRealtimeStore().setConnectionStatus('connected')
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('断线重连恢复订阅：补取一次（恢复时的再次确认不另算一次）', async () => {
    mount()
    confirmed()
    await tick(5_000)
    const store = useRealtimeStore()
    store.setConnectionStatus('reconnecting')
    await tick(2_000)
    store.setConnectionStatus('recovering')
    confirmed()
    store.setConnectionStatus('connected')
    await tick(60_000)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'resync', object: true })
  })

  it('别的主题的确认与本实例无关；换了对象之后按新频道取得的时刻算', async () => {
    mount()
    await tick(5_000)
    confirmed('nocode.records.app.other')
    await tick(0)
    expect(reload).not.toHaveBeenCalled()
    confirmed()
    await tick(0)
    expect(reload).toHaveBeenCalledTimes(1)

    state.ids = ['other']
    await tick(300)
    // 旧频道迟到的确认不算
    confirmed()
    confirmed('nocode.records.app.other')
    await tick(60_000)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('E7 挂载时已连上且之后没断过、订阅一直没被确认（如无权订阅）：不补取', async () => {
    mount()
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
  })
})
