import type { RecordsChanged } from '@/types/nocode/record-live'

/** 一次软刷新要处理的变更。 */
export interface LiveChange {
  /** push 收到通知；resync 断线重连后补取；activated 回到眼前补取；local 本地主动声明过期 */
  reason: 'push' | 'resync' | 'activated' | 'local'
  /** true = 不知道具体哪些记录，整体刷新；这时三个集合为空 */
  object: boolean
  created: Set<string>
  updated: Set<string>
  deleted: Set<string>
}

export type LiveCadence = 'list' | 'report'

/** 去抖：静默期内没有新通知才取数；通知不停时最长等这么久也要取一次。统计比列表慢一拍。 */
export const LIVE_CADENCE: Record<LiveCadence, { quiet: number; maxWait: number }> = {
  list: { quiet: 400, maxWait: 2_000 },
  report: { quiet: 1_500, maxWait: 10_000 }
}
/**
 * 突发才节流：最近这么久之内已经因推送取过 LIVE_PUSH_BURST 次，再来的通知合并起来，等到其中较早那次满这么久再取一次。
 * 不足这个次数时只等去抖（别人手工改一条、紧接着再改一条，都是马上看到）；批量导入连着来时才被压到这个节奏。
 */
export const LIVE_PUSH_INTERVAL_MS = 5_000
export const LIVE_PUSH_BURST = 2
/** 离开眼前超过这么久，回来时不管有没有收到通知都补取一次。 */
export const LIVE_AWAY_MS = 60_000
/** 「刚变过的记录」保留这么久（行的淡色提示约 2 秒）。 */
export const LIVE_TOUCHED_MS = 2_000

export interface LiveSchedulerOptions {
  applicationId: () => string
  objectIds: () => string[]
  cadence?: LiveCadence
  /** 软刷新；返回的 Promise 结束前不会被再次调用。抛错或被拒只记过期，不向外抛。 */
  reload: (change: LiveChange) => void | Promise<void>
  /** 创建时在不在眼前；之后由 setVisible 告知 */
  visible: boolean
  /** 创建时连接是不是已就绪；之后由 setConnected 告知。创建时没连上不算「断过」，要不要补取由宿主看订阅确认的时刻决定（resync） */
  connected: boolean
  /** 这一帧是自己发起的、宿主已经自己刷新过（跳过自己）。只在眼前、且序号接续时才会问。 */
  own?: (frame: RecordsChanged) => boolean
  /** 所在页面被保活在后台：这时不取数也不攒；过期由宿主在切走时记给保活机制，回到前台由它补取 */
  parked?: () => boolean
  onStale?: (stale: boolean) => void
  onTouched?: (ids: ReadonlySet<string>) => void
}

export interface LiveScheduler {
  /** 收到一帧通知 */
  push: (frame: RecordsChanged) => void
  /** 本地主动声明数据过期：等同一帧整体刷新 */
  invalidate: () => void
  setVisible: (visible: boolean) => void
  setConnected: (connected: boolean) => void
  /** 订阅生效得晚了，其间的通知可能漏掉：整体补取一次（不在眼前则只记过期） */
  resync: () => void
  dispose: () => void
}

interface Pending extends LiveChange {
  firstAt: number
  lastAt: number
  immediate: boolean
}

const NONE: ReadonlySet<string> = new Set()

/**
 * 决定「什么时候软刷新一次」，不依赖 Vue，时间走全局定时器（用例里换成假时间）。
 *
 * - 在眼前收到通知：去抖后取一次；5 秒内已因推送取过两次时，再来的合并到窗口结束取一次（必补）。
 * - 上一次还没回来：等它回来再取，不并发。
 * - 不在眼前：只记过期，回到眼前补取一次。
 * - 连接断过：重连后补取一次。
 */
export function createLiveScheduler(options: LiveSchedulerOptions): LiveScheduler {
  const cadence = LIVE_CADENCE[options.cadence || 'list']
  // 每个（应用, 对象）上一帧的序号；用来发现漏帧和服务重启。
  const sequences = new Map<string, { epoch: string; seq: number }>()
  let pending: Pending | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let touchedTimer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let disposed = false
  let stale = false
  // 取数期间又被记了过期：这次成功也不能把过期标记清掉。
  let staleDuringRun = false
  let visible = options.visible
  let connected = options.connected
  // 连上之后又断过没有；断过的，重连后补取一次。
  let lost = false
  let hiddenAt = Date.now()
  // 最近几次由推送引起的取数的发起时刻（只留 LIVE_PUSH_BURST 个）。
  const pushStarts: number[] = []

  const parked = () => !!options.parked?.()

  function setStale(value: boolean) {
    if (value) staleDuringRun = true
    if (stale === value) return
    stale = value
    options.onStale?.(value)
  }

  function matches(frame: RecordsChanged) {
    return frame.applicationId === options.applicationId() && options.objectIds().includes(frame.objectId)
  }

  /** 记下这一帧的序号；返回它与上一帧是否不接续（第一帧不判断）。 */
  function gap(frame: RecordsChanged) {
    const key = frame.applicationId + ':' + frame.objectId
    const last = sequences.get(key)
    sequences.set(key, { epoch: frame.epoch, seq: frame.seq })
    return !!last && (last.epoch !== frame.epoch || frame.fromSeq !== last.seq + 1)
  }

  function merge(change: Pick<LiveChange, 'reason' | 'object'>, frame?: RecordsChanged, immediate = false) {
    const now = Date.now()
    pending ||= {
      reason: change.reason,
      object: false,
      created: new Set(),
      updated: new Set(),
      deleted: new Set(),
      firstAt: now,
      lastAt: now,
      immediate: false
    }
    pending.lastAt = now
    if (immediate) pending.immediate = true
    // 推送以外的原因优先：它们不受「最快 5 秒一次」的约束。
    if (change.reason !== 'push') pending.reason = change.reason
    if (change.object) pending.object = true
    if (frame && !pending.object) {
      // 按到达顺序合并：新增后又改仍算新增；先改后删算删除。
      for (const id of frame.created) {
        pending.deleted.delete(id)
        pending.created.add(id)
      }
      for (const id of frame.updated) {
        pending.deleted.delete(id)
        if (!pending.created.has(id)) pending.updated.add(id)
      }
      for (const id of frame.deleted) {
        pending.created.delete(id)
        pending.updated.delete(id)
        pending.deleted.add(id)
      }
    }
    if (pending.object) {
      pending.created.clear()
      pending.updated.clear()
      pending.deleted.clear()
    }
    arm()
  }

  function arm() {
    if (timer) clearTimeout(timer)
    timer = undefined
    // 在途的结束后会再来排一次。
    if (!pending || running || disposed) return
    const now = Date.now()
    let due = pending.immediate ? now : Math.min(pending.lastAt + cadence.quiet, pending.firstAt + cadence.maxWait)
    // 最近 5 秒内已经因推送取过两次：等较早那次满 5 秒。
    if (pending.reason === 'push' && pushStarts.length >= LIVE_PUSH_BURST)
      due = Math.max(due, pushStarts[0] + LIVE_PUSH_INTERVAL_MS)
    timer = setTimeout(fire, Math.max(0, due - now))
  }

  function fire() {
    timer = undefined
    const change = pending
    if (!change || running || disposed) return
    pending = undefined
    // 去抖期间页面被切到后台、页签被隐藏：不取了，按「不在眼前」处理。
    if (away()) return
    running = true
    staleDuringRun = false
    if (change.reason === 'push') {
      pushStarts.push(Date.now())
      if (pushStarts.length > LIVE_PUSH_BURST) pushStarts.shift()
    }
    const { reason, object, created, updated, deleted } = change
    let outcome: void | Promise<void>
    try {
      outcome = options.reload({ reason, object, created, updated, deleted })
    } catch {
      finish(false, change)
      return
    }
    Promise.resolve(outcome).then(
      () => finish(true, change),
      () => finish(false, change)
    )
  }

  function finish(succeeded: boolean, change: LiveChange) {
    running = false
    if (disposed) return
    if (!succeeded) setStale(true)
    else {
      if (!staleDuringRun) setStale(false)
      if (change.reason === 'push') touch(change.object ? NONE : new Set([...change.created, ...change.updated]))
    }
    arm()
  }

  function touch(ids: ReadonlySet<string>) {
    if (touchedTimer) clearTimeout(touchedTimer)
    touchedTimer = undefined
    options.onTouched?.(ids)
    if (!ids.size) return
    touchedTimer = setTimeout(() => {
      touchedTimer = undefined
      options.onTouched?.(NONE)
    }, LIVE_TOUCHED_MS)
  }

  /** 现在不能取：页面在后台交给保活机制；页签隐藏则记过期。返回 true 表示已经处理完。 */
  function away() {
    if (parked()) return true
    if (!visible) {
      setStale(true)
      return true
    }
    return false
  }

  return {
    push(frame) {
      if (disposed || !matches(frame)) return
      const broken = gap(frame)
      if (away()) return
      // 序号断了说明中间漏过别人的变更，即使这一帧是自己的也不能跳过。
      if (!broken && options.own?.(frame)) return
      merge({ reason: 'push', object: broken || frame.kind === 'object' }, frame)
    },
    invalidate() {
      if (disposed || away()) return
      merge({ reason: 'local', object: true })
    },
    setVisible(value) {
      if (disposed || visible === value) return
      visible = value
      if (!value) {
        hiddenAt = Date.now()
        if (pending) {
          // 还没取的不取了，回来时整体补取。
          pending = undefined
          if (timer) clearTimeout(timer)
          timer = undefined
          setStale(true)
        }
        return
      }
      if (parked()) return
      if (stale || !connected || Date.now() - hiddenAt > LIVE_AWAY_MS)
        merge({ reason: 'activated', object: true }, undefined, true)
    },
    setConnected(value) {
      if (disposed || connected === value) return
      connected = value
      if (!value) {
        lost = true
        // 断线期间的通知收不到，序号不再可比。
        sequences.clear()
        return
      }
      if (!lost) return
      lost = false
      if (away()) return
      merge({ reason: 'resync', object: true }, undefined, true)
    },
    resync() {
      if (disposed || away()) return
      merge({ reason: 'resync', object: true }, undefined, true)
    },
    dispose() {
      disposed = true
      pending = undefined
      if (timer) clearTimeout(timer)
      if (touchedTimer) clearTimeout(touchedTimer)
      timer = touchedTimer = undefined
    }
  }
}
