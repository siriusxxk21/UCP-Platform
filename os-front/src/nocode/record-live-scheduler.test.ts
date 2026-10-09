import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RecordsChanged } from '@/types/nocode/record-live'
import { createLiveScheduler, type LiveChange, type LiveSchedulerOptions } from './record-live-scheduler'

let seq = 0
function frame(patch: Partial<RecordsChanged> = {}): RecordsChanged {
  seq++
  return {
    applicationId: 'app',
    objectId: 'obj',
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
  }
}
function deferred() {
  let resolve!: () => void, reject!: (reason: unknown) => void
  const promise = new Promise<void>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
/** 记下每次 reload 的时刻与内容；集合换成排好序的数组便于比较。 */
function setup(options: Partial<LiveSchedulerOptions> = {}) {
  const calls: Array<{
    at: number
    reason: string
    object: boolean
    created: string[]
    updated: string[]
    deleted: string[]
  }> = []
  const state = { stale: false, touched: [] as string[] }
  const started = Date.now()
  const reload = vi.fn((change: LiveChange): void | Promise<void> => {
    calls.push({
      at: Date.now() - started,
      reason: change.reason,
      object: change.object,
      created: [...change.created].sort(),
      updated: [...change.updated].sort(),
      deleted: [...change.deleted].sort()
    })
  })
  const scheduler = createLiveScheduler({
    applicationId: () => 'app',
    objectIds: () => ['obj'],
    reload,
    visible: true,
    connected: true,
    onStale: value => (state.stale = value),
    onTouched: ids => (state.touched = [...ids].sort()),
    ...options
  })
  return { scheduler, reload, calls, state }
}
const tick = (ms: number) => vi.advanceTimersByTimeAsync(ms)

beforeEach(() => {
  seq = 0
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('去抖', () => {
  it('E1 在眼前来一帧：400 毫秒后恰好取一次，三个集合与帧一致', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ created: ['9001'], updated: ['88', '89'], deleted: ['7'] }))
    await tick(399)
    expect(calls).toEqual([])
    await tick(1)
    expect(calls).toEqual([
      { at: 400, reason: 'push', object: false, created: ['9001'], updated: ['88', '89'], deleted: ['7'] }
    ])
    await tick(60_000)
    expect(calls).toHaveLength(1)
  })

  it('E2 300 毫秒内连着 5 帧：只取一次，集合是并集', async () => {
    const { scheduler, calls } = setup()
    for (let index = 0; index < 5; index++) {
      scheduler.push(frame({ created: ['c' + index], updated: ['u' + index] }))
      await tick(60)
    }
    await tick(400)
    expect(calls).toHaveLength(1)
    expect(calls[0].created).toEqual(['c0', 'c1', 'c2', 'c3', 'c4'])
    expect(calls[0].updated).toEqual(['u0', 'u1', 'u2', 'u3', 'u4'])
  })

  it('E2 每 300 毫秒一帧不停：最迟 2 秒时取一次', async () => {
    const { scheduler, calls } = setup()
    for (let at = 0; at < 2_000; at += 300) {
      scheduler.push(frame({ updated: ['r' + at] }))
      await tick(300)
    }
    // 1800 毫秒时的那一帧之后静默期还没到，但最长等待到了
    expect(calls.map(call => call.at)).toEqual([2_000])
    expect(calls[0].updated).toHaveLength(7)
  })

  it('E10 统计的节奏：静默 1.5 秒、最长等 10 秒', async () => {
    const quiet = setup({ cadence: 'report' })
    quiet.scheduler.push(frame())
    await tick(1_499)
    expect(quiet.calls).toEqual([])
    await tick(1)
    expect(quiet.calls.map(call => call.at)).toEqual([1_500])

    const busy = setup({ cadence: 'report' })
    for (let at = 0; at < 10_000; at += 1_000) {
      busy.scheduler.push(frame({ objectId: 'obj', fromSeq: 100 + at, seq: 100 + at, epoch: 'e2' }))
      await tick(1_000)
    }
    expect(busy.calls.map(call => call.at)).toEqual([10_000])
  })

  it('同一条记录先改后删：算删除', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ created: ['a'], updated: ['b'] }))
    scheduler.push(frame({ updated: ['a'], deleted: ['b'] }))
    await tick(400)
    expect(calls[0]).toMatchObject({ created: ['a'], updated: [], deleted: ['b'] })
  })

  it('E11 别的对象、别的应用的帧不理会', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ objectId: 'other' }))
    scheduler.push(frame({ applicationId: 'another-app' }))
    await tick(60_000)
    expect(calls).toEqual([])
  })
})

describe('不并发', () => {
  it('E3 取数在途时来 3 帧：在途的结束后再取一次，且只一次', async () => {
    const first = deferred()
    let active = 0,
      peak = 0
    const { scheduler, reload } = setup()
    reload.mockImplementation(async () => {
      active++
      peak = Math.max(peak, active)
      if (reload.mock.calls.length === 1) await first.promise
      active--
    })
    scheduler.push(frame({ updated: ['a'] }))
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
    for (const id of ['b', 'c', 'd']) {
      scheduler.push(frame({ updated: [id] }))
      await tick(700)
    }
    // 三帧各自的静默期早过了，但上一次还没回来：不叠加
    expect(reload).toHaveBeenCalledTimes(1)
    // 连「最快 5 秒一次」的窗口也过了，上一次仍没回来：还是不叠加
    await tick(10_000)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(peak).toBe(1)
    first.resolve()
    await tick(10_000)
    expect(reload).toHaveBeenCalledTimes(2)
    expect([...reload.mock.calls[1][0].updated].sort()).toEqual(['b', 'c', 'd'])
    expect(peak).toBe(1)
    await tick(60_000)
    expect(reload).toHaveBeenCalledTimes(2)
  })
})

describe('不在眼前', () => {
  it('E4 页签隐藏时来帧：不取数、记过期；回到眼前取一次', async () => {
    const { scheduler, calls, state } = setup()
    scheduler.setVisible(false)
    scheduler.push(frame({ updated: ['a'] }))
    scheduler.push(frame({ updated: ['b'] }))
    await tick(30_000)
    expect(calls).toEqual([])
    expect(state.stale).toBe(true)
    scheduler.setVisible(true)
    await tick(0)
    expect(calls).toEqual([{ at: 30_000, reason: 'activated', object: true, created: [], updated: [], deleted: [] }])
    expect(state.stale).toBe(false)
    await tick(60_000)
    expect(calls).toHaveLength(1)
  })

  it('E4 去抖还没到就被隐藏：不取数，回来补一次', async () => {
    const { scheduler, calls, state } = setup()
    scheduler.push(frame({ updated: ['a'] }))
    await tick(100)
    scheduler.setVisible(false)
    await tick(5_000)
    expect(calls).toEqual([])
    expect(state.stale).toBe(true)
    scheduler.setVisible(true)
    await tick(0)
    expect(calls.map(call => call.reason)).toEqual(['activated'])
  })

  it('E4 所在页面被保活在后台时来帧：不取数，交给保活机制回到前台时补取', async () => {
    let parked = true
    const { scheduler, calls, state } = setup({ parked: () => parked })
    scheduler.push(frame({ updated: ['a'] }))
    scheduler.push(frame({ updated: ['b'] }))
    await tick(60_000)
    expect(calls).toEqual([])
    // 不算本模块的「过期」：过期由宿主在切走时记给保活机制
    expect(state.stale).toBe(false)
    // 回到前台由保活机制取，本模块不再另取一次
    parked = false
    await tick(60_000)
    expect(calls).toEqual([])
    // 回到前台之后的帧照常
    scheduler.push(frame({ updated: ['c'] }))
    await tick(400)
    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ reason: 'push', object: false, updated: ['c'] })
  })

  it('E4 去抖还没到页面就被切到后台：不取数，交给保活机制', async () => {
    let parked = false
    const { scheduler, calls } = setup({ parked: () => parked })
    scheduler.push(frame({ updated: ['a'] }))
    await tick(100)
    parked = true
    await tick(60_000)
    expect(calls).toEqual([])
    // 回到前台后也不补那一次（由保活机制取）
    parked = false
    await tick(60_000)
    expect(calls).toEqual([])
  })

  it('E5 没来任何帧：离开超过 60 秒回来取一次；不足 60 秒且一直连着不取', async () => {
    const short = setup()
    short.scheduler.setVisible(false)
    await tick(60_000)
    short.scheduler.setVisible(true)
    await tick(0)
    expect(short.calls).toEqual([])

    const long = setup()
    long.scheduler.setVisible(false)
    await tick(60_001)
    long.scheduler.setVisible(true)
    await tick(0)
    expect(long.calls.map(call => call.reason)).toEqual(['activated'])
  })

  it('E5 回到眼前时连接断着：取一次', async () => {
    const { scheduler, calls } = setup()
    scheduler.setVisible(false)
    scheduler.setConnected(false)
    await tick(1_000)
    scheduler.setVisible(true)
    await tick(0)
    expect(calls.map(call => call.reason)).toEqual(['activated'])
  })
})

describe('断线重连', () => {
  it('E6 在眼前：重连后补取一次', async () => {
    const { scheduler, calls } = setup()
    scheduler.setConnected(false)
    await tick(20_000)
    expect(calls).toEqual([])
    scheduler.setConnected(true)
    await tick(0)
    expect(calls).toEqual([{ at: 20_000, reason: 'resync', object: true, created: [], updated: [], deleted: [] }])
    // 没再断过：再报一次已连接不重复取
    scheduler.setConnected(true)
    await tick(60_000)
    expect(calls).toHaveLength(1)
  })

  it('E6 不在眼前：重连只记过期，回来再取', async () => {
    const hidden = setup()
    hidden.scheduler.setVisible(false)
    hidden.scheduler.setConnected(false)
    hidden.scheduler.setConnected(true)
    await tick(1_000)
    expect(hidden.calls).toEqual([])
    expect(hidden.state.stale).toBe(true)
    hidden.scheduler.setVisible(true)
    await tick(0)
    expect(hidden.calls.map(call => call.reason)).toEqual(['activated'])

    const parked = setup({ parked: () => true })
    parked.scheduler.setConnected(false)
    parked.scheduler.setConnected(true)
    await tick(1_000)
    expect(parked.calls).toEqual([])
    expect(parked.state.stale).toBe(false)
  })

  it('E7 创建时还没连上：连上本身不引起补取（要不要补由订阅确认的时刻决定）；创建时已连上且没断过：不取', async () => {
    const late = setup({ connected: false })
    await tick(3_000)
    late.scheduler.setConnected(true)
    await tick(60_000)
    expect(late.calls).toEqual([])
    // 连上之后再断、再连：补取一次
    late.scheduler.setConnected(false)
    late.scheduler.setConnected(true)
    await tick(0)
    expect(late.calls.map(call => call.reason)).toEqual(['resync'])

    const steady = setup({ connected: true })
    steady.scheduler.setConnected(true)
    await tick(60_000)
    expect(steady.calls).toEqual([])
  })

  it('E7 订阅生效得晚了（宿主告知）：在眼前立即整体补取一次；不在眼前只记过期', async () => {
    const front = setup()
    front.scheduler.resync()
    await tick(0)
    expect(front.calls).toEqual([{ at: 0, reason: 'resync', object: true, created: [], updated: [], deleted: [] }])

    const hidden = setup()
    hidden.scheduler.setVisible(false)
    hidden.scheduler.resync()
    await tick(1_000)
    expect(hidden.calls).toEqual([])
    expect(hidden.state.stale).toBe(true)

    const parked = setup({ parked: () => true })
    parked.scheduler.resync()
    await tick(1_000)
    expect(parked.calls).toEqual([])
  })
})

describe('序号', () => {
  it('E8 接续的帧按记录处理；第一帧不判断', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ fromSeq: 118, seq: 120, updated: ['a'] }))
    scheduler.push(frame({ fromSeq: 121, seq: 121, updated: ['b'] }))
    await tick(400)
    expect(calls).toEqual([{ at: 400, reason: 'push', object: false, created: [], updated: ['a', 'b'], deleted: [] }])
  })

  it('E8 序号不接续：这一帧按整体刷新处理', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ fromSeq: 1, seq: 2, updated: ['a'] }))
    scheduler.push(frame({ fromSeq: 4, seq: 4, updated: ['b'] }))
    await tick(400)
    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ reason: 'push', object: true })
  })

  it('E8 服务重启过（epoch 变了）：按整体刷新处理', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ epoch: 'e1', fromSeq: 1, seq: 1, updated: ['a'] }))
    scheduler.push(frame({ epoch: 'e2', fromSeq: 2, seq: 2, updated: ['b'] }))
    await tick(400)
    expect(calls[0]).toMatchObject({ object: true })
  })

  it('E8 断线后清空记录：重连后的第一帧不判断', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ fromSeq: 1, seq: 1, updated: ['a'] }))
    await tick(400)
    scheduler.setConnected(false)
    scheduler.setConnected(true)
    await tick(0)
    expect(calls.map(call => call.reason)).toEqual(['push', 'resync'])
    await tick(6_000)
    scheduler.push(frame({ fromSeq: 50, seq: 50, updated: ['b'] }))
    await tick(400)
    expect(calls[2]).toMatchObject({ reason: 'push', object: false, updated: ['b'] })
  })

  it('各对象的序号各记各的', async () => {
    const { scheduler, calls } = setup({ objectIds: () => ['obj', 'other'] })
    scheduler.push(frame({ objectId: 'obj', fromSeq: 1, seq: 1, updated: ['a'] }))
    scheduler.push(frame({ objectId: 'other', fromSeq: 7, seq: 7, updated: ['b'] }))
    scheduler.push(frame({ objectId: 'obj', fromSeq: 2, seq: 2, updated: ['c'] }))
    await tick(400)
    expect(calls[0]).toMatchObject({ object: false, updated: ['a', 'b', 'c'] })
  })
})

describe('突发才节流：5 秒内已因推送取过两次，第三次起推迟到窗口结束', () => {
  it('单独一帧立即取（只等去抖）', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ updated: ['a'] }))
    await tick(400)
    expect(calls.map(call => call.at)).toEqual([400])
  })

  it('5 秒内第二帧也立即取（别人连着手工改两条，都是马上看到）', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ updated: ['a'] }))
    await tick(1_500)
    scheduler.push(frame({ updated: ['b'] }))
    await tick(400)
    expect(calls.map(call => call.at)).toEqual([400, 1_900])
    expect(calls[1]).toMatchObject({ reason: 'push', object: false, updated: ['b'] })
  })

  it('E9 量大的一帧照常取，紧跟着的一帧也立即取', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ kind: 'object', many: true }))
    await tick(400)
    expect(calls).toEqual([{ at: 400, reason: 'push', object: true, created: [], updated: [], deleted: [] }])
    scheduler.push(frame({ updated: ['a'] }))
    await tick(400)
    expect(calls.map(call => call.at)).toEqual([400, 800])
  })

  it('第三帧起节流：推迟到「两次里较早那次」满 5 秒', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ updated: ['a'] }))
    await tick(1_000)
    scheduler.push(frame({ updated: ['b'] }))
    await tick(1_000)
    expect(calls.map(call => call.at)).toEqual([400, 1_400])
    scheduler.push(frame({ updated: ['c'] }))
    await tick(3_399)
    // 第三帧的去抖早就到了，但 5 秒内已经取过两次
    expect(calls).toHaveLength(2)
    await tick(1)
    expect(calls.map(call => call.at)).toEqual([400, 1_400, 5_400])
  })

  it('窗口结束必补一次：窗口内到达的帧合并，到点取一次且只一次', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ updated: ['a'] }))
    await tick(400)
    scheduler.push(frame({ updated: ['b'] }))
    await tick(400)
    expect(calls).toHaveLength(2)
    scheduler.push(frame({ updated: ['c'] }))
    await tick(1_000)
    scheduler.push(frame({ created: ['d'] }))
    await tick(3_599)
    expect(calls).toHaveLength(2)
    await tick(1)
    expect(calls).toHaveLength(3)
    expect(calls[2]).toMatchObject({ at: 5_400, reason: 'push', object: false, created: ['d'], updated: ['c'] })
    await tick(60_000)
    expect(calls).toHaveLength(3)
  })

  it('导入连发 6 批（每秒一批、每批 200 行、many=false）：最多取 3 次', async () => {
    const { scheduler, calls } = setup()
    for (let batch = 0; batch < 6; batch++) {
      scheduler.push(frame({ created: Array.from({ length: 200 }, (_, index) => `b${batch}-${index}`) }))
      await tick(1_000)
    }
    await tick(30_000)
    // 头两批各取一次，后四批合成一次
    expect(calls.map(call => call.at)).toEqual([400, 1_400, 5_400])
    expect(calls[2].created).toHaveLength(800)
  })

  it('帧一直不停：任意连续三次取数的跨度都不小于 5 秒（5 秒内最多两次）', async () => {
    const { scheduler, calls } = setup()
    for (let at = 0; at < 12_000; at += 1_000) {
      scheduler.push(frame({ created: ['batch-' + at] }))
      await tick(1_000)
    }
    await tick(10_000)
    expect(calls.map(call => call.at)).toEqual([400, 1_400, 5_400, 6_400, 10_400, 11_400])
    for (let index = 2; index < calls.length; index++)
      expect(calls[index].at - calls[index - 2].at).toBeGreaterThanOrEqual(5_000)
  })

  it('节流只管推送：重连补取、主动声明过期在窗口内照常', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame({ updated: ['a'] }))
    await tick(400)
    scheduler.push(frame({ updated: ['b'] }))
    await tick(400)
    // 窗口已满：这一帧要等到 5.4 秒
    scheduler.push(frame({ updated: ['c'] }))
    await tick(400)
    expect(calls).toHaveLength(2)
    scheduler.setConnected(false)
    scheduler.setConnected(true)
    await tick(0)
    // 整体补取把等着的那一帧一并带走
    expect(calls.map(call => [call.at, call.reason])).toEqual([
      [400, 'push'],
      [800, 'push'],
      [1_200, 'resync']
    ])
    scheduler.invalidate()
    await tick(400)
    expect(calls.map(call => call.reason)).toEqual(['push', 'push', 'resync', 'local'])
    await tick(60_000)
    expect(calls).toHaveLength(4)
  })
})

describe('跳过自己', () => {
  it('自己发起的帧不取数，但序号照记', async () => {
    const { scheduler, calls } = setup({ own: change => change.origin === 'me' })
    scheduler.push(frame({ origin: 'me', fromSeq: 1, seq: 1, updated: ['a'] }))
    await tick(1_000)
    expect(calls).toEqual([])
    // 接着别人的一帧：序号接续，按记录处理
    scheduler.push(frame({ origin: 'someone', fromSeq: 2, seq: 2, updated: ['b'] }))
    await tick(400)
    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ object: false, updated: ['b'] })
  })

  it('自己的帧但序号断了（中间漏了别人的）：不能跳过', async () => {
    const { scheduler, calls } = setup({ own: change => change.origin === 'me' })
    scheduler.push(frame({ origin: 'someone', fromSeq: 1, seq: 1 }))
    await tick(400)
    await tick(5_000)
    scheduler.push(frame({ origin: 'me', fromSeq: 5, seq: 5 }))
    await tick(400)
    expect(calls).toHaveLength(2)
    expect(calls[1]).toMatchObject({ reason: 'push', object: true })
  })

  it('不在眼前时自己的帧也记过期', async () => {
    const { scheduler, calls, state } = setup({ own: change => change.origin === 'me' })
    scheduler.setVisible(false)
    scheduler.push(frame({ origin: 'me' }))
    expect(state.stale).toBe(true)
    scheduler.setVisible(true)
    await tick(0)
    expect(calls.map(call => call.reason)).toEqual(['activated'])
  })
})

describe('失败与主动失效', () => {
  it('E12 取数抛错或被拒：记过期、不向外抛；下一帧还能再触发', async () => {
    const { scheduler, reload, state } = setup()
    reload.mockImplementationOnce(() => {
      throw new Error('同步抛错')
    })
    scheduler.push(frame())
    await tick(400)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(state.stale).toBe(true)

    reload.mockImplementationOnce(() => Promise.reject(new Error('请求失败')))
    scheduler.push(frame())
    await tick(5_000)
    expect(reload).toHaveBeenCalledTimes(2)
    expect(state.stale).toBe(true)

    scheduler.push(frame())
    await tick(5_000)
    expect(reload).toHaveBeenCalledTimes(3)
    expect(state.stale).toBe(false)
  })

  it('E13 主动声明过期：等同一帧整体刷新，reason 是 local', async () => {
    const { scheduler, calls } = setup()
    scheduler.invalidate()
    await tick(399)
    expect(calls).toEqual([])
    await tick(1)
    expect(calls).toEqual([{ at: 400, reason: 'local', object: true, created: [], updated: [], deleted: [] }])
  })

  it('E13 不在眼前时主动声明过期：只记过期', async () => {
    const hidden = setup()
    hidden.scheduler.setVisible(false)
    hidden.scheduler.invalidate()
    await tick(1_000)
    expect(hidden.calls).toEqual([])
    expect(hidden.state.stale).toBe(true)

    const parked = setup({ parked: () => true })
    parked.scheduler.invalidate()
    await tick(1_000)
    expect(parked.calls).toEqual([])
    expect(parked.state.stale).toBe(false)
  })

  it('E14 取回后给出这次新增和修改的记录，2 秒后清空', async () => {
    const { scheduler, state } = setup()
    scheduler.push(frame({ created: ['c'], updated: ['u'], deleted: ['d'] }))
    await tick(400)
    expect(state.touched).toEqual(['c', 'u'])
    await tick(1_999)
    expect(state.touched).toEqual(['c', 'u'])
    await tick(1)
    expect(state.touched).toEqual([])
  })

  it('E14 整体刷新的那次不给出记录', async () => {
    const { scheduler, state } = setup()
    scheduler.push(frame({ kind: 'object' }))
    await tick(400)
    expect(state.touched).toEqual([])
  })

  it('注销之后不再取数', async () => {
    const { scheduler, calls } = setup()
    scheduler.push(frame())
    scheduler.dispose()
    scheduler.push(frame())
    scheduler.setConnected(false)
    scheduler.setConnected(true)
    await tick(60_000)
    expect(calls).toEqual([])
  })
})
