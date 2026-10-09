// @vitest-environment jsdom
// 整条链路都是真的：传输层 → 协议 → 频道登记 → 适配 → 总线 → useRecordLive。假的只有 WebSocket。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { RealtimeRuntime } from '@/realtime/runtime'
import { FakeWebSocket } from '@/realtime/testing'
import { useRealtimeStore } from '@/stores/realtime'
import type { LiveChange } from './record-live'

// useRecordLive 用的是应用级的那个入口；这里把它接到本用例自己建的运行时上。
const active = vi.hoisted(() => ({ runtime: undefined as undefined | import('@/realtime/runtime').RealtimeRuntime }))
vi.mock('@/realtime/runtime', async original => {
  const actual = await original<typeof import('@/realtime/runtime')>()
  return {
    ...actual,
    realtimeRuntime: {
      acquireChannel: (descriptor: { key: string; topic: string }) => active.runtime!.acquireChannel(descriptor),
      onChannelSubscribed: (handler: (topic: string) => void) => active.runtime!.onChannelSubscribed(handler)
    }
  }
})
import { useRecordLive } from './record-live'

const TOPIC = 'nocode.records.41.3057'
const reload = vi.fn((_change: LiveChange): void => undefined)
const Host = defineComponent({
  setup() {
    useRecordLive({ applicationId: () => '41', objectIds: () => ['3057'], reload })
    return () => h('div')
  }
})

let app: App | undefined
let pinia: Pinia
let sockets: FakeWebSocket[]
let runtime: RealtimeRuntime
const socket = () => sockets.at(-1)!
/** 浏览器发给服务端的命令（去掉心跳）。 */
const commands = (from: FakeWebSocket = socket()) =>
  from.sent
    .filter(data => data !== 'ping')
    .flatMap(data => {
      const parsed = JSON.parse(data)
      return Array.isArray(parsed) ? parsed : [parsed]
    })
    .map((frame: { id: number; command: { type: string; topic?: string } }) => ({ id: frame.id, ...frame.command }))
const tick = async (ms: number) => {
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}
/** 服务端对还没回复的命令逐个回复成功（除了 except 里的主题）。 */
const replied = new Set<number>()
async function answer(except: string[] = []) {
  for (const command of commands()) {
    if (replied.has(command.id) || (command.topic && except.includes(command.topic))) continue
    replied.add(command.id)
    const reply =
      command.type === 'subscribe'
        ? { type: 'subscribe', topic: command.topic, recovered: false }
        : { type: command.type }
    socket().receive(JSON.stringify({ frame: 'reply', id: command.id, ok: true, reply }))
    await tick(0)
  }
}
function mount() {
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp(Host)
  app.use(pinia)
  app.mount(host)
}
/** 后端一路交付的真实帧形状（laneR-realtime/backend/frame-samples.md）。 */
function changed(seq: number, patch: Record<string, unknown> = {}) {
  socket().receive(
    JSON.stringify({
      frame: 'event',
      eventId: 'event-' + seq,
      topic: TOPIC,
      event: 'records.changed',
      timestamp: 1790875913476,
      replay: false,
      data: {
        objectId: '3057',
        kind: 'ids',
        many: false,
        created: ['9001'],
        updated: ['88', '89'],
        deleted: [],
        origin: '0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44',
        epoch: 'pjo4rb3j',
        fromSeq: seq,
        seq,
        ...patch
      }
    })
  )
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  sockets = []
  replied.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  runtime = new RealtimeRuntime({
    getToken: () => 'token',
    buildUrl: () => 'ws://test.invalid/ws',
    transport: {
      socketFactory: () => {
        const created = new FakeWebSocket()
        sockets.push(created)
        return created
      },
      random: () => 0
    }
  })
  active.runtime = runtime
})
afterEach(() => {
  app?.unmount()
  app = undefined
  // 不用 destroy：它会把应用级的事件总线永久关掉，后面的用例就收不到事件了
  runtime.resetSession()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

/** 连接建立、握手完成、已有的订阅都恢复好。 */
async function connect() {
  runtime.start()
  socket().open()
  await tick(0)
  await answer()
  await answer()
}

describe('从 WebSocket 到软刷新的整条链路', () => {
  it('订阅命令的形状与后端样例一致；收到 records.changed 后去抖软刷新一次', async () => {
    await connect()
    mount()
    await tick(0)
    expect(commands().filter(command => command.topic === TOPIC)).toEqual([
      { id: expect.any(Number), type: 'subscribe', topic: TOPIC }
    ])
    // 命令里不带 offset、不带 data
    const raw = socket().sent.find(data => data.includes(TOPIC))!
    expect(JSON.parse(raw).command).toEqual({ type: 'subscribe', topic: TOPIC })
    await answer()

    changed(1)
    await tick(399)
    expect(reload).not.toHaveBeenCalled()
    await tick(1)
    expect(reload).toHaveBeenCalledTimes(1)
    const change = reload.mock.calls[0][0]
    expect(change.reason).toBe('push')
    expect([...change.created]).toEqual(['9001'])
    expect([...change.updated].sort()).toEqual(['88', '89'])

    // 卸载：退订
    app!.unmount()
    app = undefined
    await tick(0)
    expect(commands().at(-1)).toMatchObject({ type: 'unsubscribe', topic: TOPIC })
  })

  it('不合格的帧（对象与主题不符）被丢弃，不引起软刷新；同一事件重复送达只算一次', async () => {
    await connect()
    mount()
    await tick(0)
    await answer()
    changed(1, { objectId: '9999' })
    await tick(1_000)
    expect(reload).not.toHaveBeenCalled()
    changed(2)
    changed(2)
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('已连上时打开页面、订阅马上被确认：一次都不补取', async () => {
    await connect()
    mount()
    await tick(30)
    await answer()
    await tick(60_000)
    expect(useRealtimeStore().connectionStatus).toBe('connected')
    expect(reload).not.toHaveBeenCalled()
  })

  it('已连上时打开页面、订阅确认晚于 1 秒：补取一次', async () => {
    await connect()
    mount()
    await tick(1_500)
    expect(reload).not.toHaveBeenCalled()
    await answer()
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'resync', object: true })
  })

  it('订阅被拒、重试才成功（晚于 1 秒）：补取一次', async () => {
    await connect()
    mount()
    await tick(0)
    const first = commands().find(command => command.topic === TOPIC)!
    replied.add(first.id)
    socket().receive(
      JSON.stringify({
        frame: 'reply',
        id: first.id,
        ok: false,
        error: { code: 'INVALID_COMMAND', message: '订阅数超过上限', snapshotRequired: false }
      })
    )
    await tick(0)
    expect(reload).not.toHaveBeenCalled()
    // 1 秒后重试
    await tick(1_000)
    expect(commands().filter(command => command.topic === TOPIC)).toHaveLength(2)
    await tick(50)
    await answer()
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0].reason).toBe('resync')
  })

  it('整页刚打开：页面先挂载、连接随后在 1 秒内连上并恢复订阅：不补取', async () => {
    mount()
    runtime.start()
    await tick(100)
    socket().open()
    await tick(0)
    await answer()
    await answer()
    expect(useRealtimeStore().connectionStatus).toBe('connected')
    expect(commands().some(command => command.type === 'subscribe' && command.topic === TOPIC)).toBe(true)
    await tick(60_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('页面挂载时连接没连上、过了 1 秒才连上：补取一次', async () => {
    mount()
    runtime.start()
    await tick(3_000)
    socket().open()
    await tick(0)
    await answer()
    await answer()
    expect(useRealtimeStore().connectionStatus).toBe('connected')
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reload.mock.calls[0][0]).toMatchObject({ reason: 'resync', object: true })
    await tick(60_000)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('断线重连：重新订阅并补取一次；重连后的第一帧不因序号对不上而再整体刷新', async () => {
    await connect()
    mount()
    await tick(0)
    await answer()
    changed(7)
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)

    const dropped = socket()
    dropped.remoteClose()
    await tick(0)
    expect(useRealtimeStore().connectionStatus).toBe('reconnecting')
    await tick(1_000)
    expect(sockets).toHaveLength(2)
    socket().open()
    await tick(0)
    await answer()
    await answer()
    expect(useRealtimeStore().connectionStatus).toBe('connected')
    expect(commands().some(command => command.type === 'subscribe' && command.topic === TOPIC)).toBe(true)
    expect(reload).toHaveBeenCalledTimes(2)
    expect(reload.mock.calls[1][0]).toMatchObject({ reason: 'resync', object: true })

    // 服务重启过：epoch 变了、序号从头来。断线时已清空记录，这一帧按记录处理
    await tick(6_000)
    changed(1, { epoch: 'restart1', created: [], updated: ['5'] })
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(3)
    expect(reload.mock.calls[2][0]).toMatchObject({ reason: 'push', object: false })
  })
})
