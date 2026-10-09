import { computed, inject, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import type { Subscription } from 'rxjs'
import { realtimeBus } from '@/realtime/bus'
import type { ChannelDescriptor } from '@/realtime/channel-registry'
import { realtimeClientId } from '@/realtime/client-id'
import { realtimeRuntime } from '@/realtime/runtime'
import { useRealtimeStore } from '@/stores/realtime'
import { applicationRefreshKey } from './application-context'
import { usePageActivity } from './page-activity'
import { createLiveScheduler, type LiveCadence, type LiveChange } from './record-live-scheduler'
import { piniaInstalled } from './runtime-application-cache'
import { markPageDataStale } from './runtime-data'
import { taskEntrySessionKey } from './task-entry-context'

export type { LiveChange } from './record-live-scheduler'

/** 某应用里某对象的「记录变了」通知频道。 */
export const recordsChannel = (applicationId: string, objectId: string): ChannelDescriptor => ({
  key: `nocode.records:${applicationId}:${objectId}`,
  topic: `nocode.records.${applicationId}.${objectId}`
})

export interface RecordLiveOptions {
  applicationId: () => string
  /** 响应式；变化时自动换订阅 */
  objectIds: () => string[]
  /** 软刷新：不出加载态、不清空、不清勾选、不关抽屉；返回的 Promise 结束前不会被再次调用 */
  reload: (change: LiveChange) => void | Promise<void>
  /** 默认 true */
  enabled?: () => boolean
  /** 默认 'list'；统计用 'report'（慢一拍） */
  cadence?: LiveCadence
}

export interface RecordLive {
  /** 数据可能已经过期（不在眼前时收到过通知，或上一次软刷新失败） */
  stale: Readonly<Ref<boolean>>
  /** 最近一次推送里新增和修改的记录，约 2 秒后清空 */
  touched: Readonly<Ref<ReadonlySet<string>>>
}

/**
 * 订阅确认到得比「取得频道」（宿主这时发出首次取数）晚多少以内，不必补取。
 * 这段时间里别人的变更会漏掉，由下一次通知自愈；超过了就整体补取一次。
 */
export const LIVE_SUBSCRIBE_GRACE_MS = 1_000

/** 这次变更涉不涉及某条记录。 */
export const liveChangeTouches = (change: LiveChange, recordId: string | null | undefined): boolean =>
  !!recordId && (change.object || change.updated.has(recordId) || change.deleted.has(recordId))

const instances = new Set<{ objectIds: () => string[]; invalidate: () => void }>()

/** 任何地方主动声明「这个对象的数据过期了」。效果等同收到一帧不带记录的通知。 */
export function invalidateRecords(objectId: string): void {
  for (const instance of instances) if (instance.objectIds().includes(objectId)) instance.invalidate()
}

/**
 * 让宿主在「某对象的记录变了」时软刷新。必须在组件 setup 里调用，随组件销毁自动退订。
 *
 * 在眼前收到通知：去抖后调用 reload。所在页面被保活在后台：退订、不取数，只告诉保活机制「过期了」，
 * 回到前台时重新订上，并由保活机制静默补取（宿主登记在 useRuntimeDataRefresh 上的那个重取）。
 * 浏览器页签隐藏：记过期，回来时调用 reload。断线重连后补取一次。
 * 订阅确认比取得频道晚了 1 秒以上（连接当时没连上、网络慢、被拒后重试才成功）也补取一次。
 *
 * 不订阅的情形：enabled() 为假；应用或对象为空；处在任务入口场景（权限来自入口授权，订不上）。
 */
export function useRecordLive(options: RecordLiveOptions): RecordLive {
  const stale = ref(false)
  const touched = shallowRef<ReadonlySet<string>>(new Set())
  const live: RecordLive = { stale, touched }
  // 独立夹具没装 Pinia 就没有实时连接。
  if (!piniaInstalled()) return live
  const store = useRealtimeStore()
  const activity = usePageActivity()
  const taskEntry = inject(taskEntrySessionKey, undefined)
  // 只有处在运行页的共享刷新范围内，本页签自己的写入才有人替它硬刷新一次。
  const shared = inject(applicationRefreshKey, null)
  const wanted = computed(() => {
    // 所在页面被保活在后台时不占订阅（服务端每个连接最多 50 个）：切走时记过期，回到前台重新订上并由保活机制补取。
    if (!activity.active.value) return []
    if (taskEntry || options.enabled?.() === false || !options.applicationId()) return []
    return [...new Set(options.objectIds().filter(Boolean))]
  })
  watch(
    () => activity.active.value,
    active => {
      if (!active) markPageDataStale(activity)
    },
    { flush: 'sync' }
  )
  const documentVisible = () => typeof document === 'undefined' || document.visibilityState !== 'hidden'
  const scheduler = createLiveScheduler({
    applicationId: options.applicationId,
    objectIds: () => wanted.value,
    cadence: options.cadence,
    reload: options.reload,
    visible: documentVisible(),
    connected: store.connectionStatus === 'connected',
    own: frame => frame.origin === realtimeClientId && !!shared,
    // 页面在后台时不取数；过期已在切走时记给保活机制（见上）。
    parked: () => !activity.active.value,
    onStale: value => (stale.value = value),
    onTouched: ids => (touched.value = ids)
  })

  const releases = new Map<string, () => void>()
  // 各频道是什么时候取得的（宿主也在这时发出首次取数）；等到服务端确认订阅时用来判断要不要补取。
  const awaiting = new Map<string, number>()
  function acquire(descriptor: ChannelDescriptor) {
    try {
      const release = realtimeRuntime.acquireChannel(descriptor)
      awaiting.set(descriptor.topic, Date.now())
      releases.set(descriptor.key, () => {
        awaiting.delete(descriptor.topic)
        release()
      })
    } catch {
      // 实时通道还没初始化（独立夹具）：不订阅，页面照常用。
    }
  }
  let stopConfirmations: (() => void) | undefined
  try {
    stopConfirmations = realtimeRuntime.onChannelSubscribed(topic => {
      const since = awaiting.get(topic)
      // 只看取得之后的第一次确认：之后断线重连的恢复，由「连接断过」那条路补取。
      if (since === undefined) return
      awaiting.delete(topic)
      // 订阅在首次取数之后很快就生效：不补取（分页是最重的请求，不为这一点空档每开一页多打一次）。
      if (Date.now() - since > LIVE_SUBSCRIBE_GRACE_MS) scheduler.resync()
    })
  } catch {
    // 同上：实时通道还没初始化。
  }
  function syncChannels() {
    const applicationId = options.applicationId()
    const descriptors = wanted.value.map(objectId => recordsChannel(applicationId, objectId))
    for (const [key, release] of releases)
      if (!descriptors.some(descriptor => descriptor.key === key)) {
        releases.delete(key)
        release()
      }
    for (const descriptor of descriptors) if (!releases.has(descriptor.key)) acquire(descriptor)
  }
  watch(() => [options.applicationId(), ...wanted.value].join('\n'), syncChannels, { immediate: true })

  let subscription: Subscription | undefined
  function listen() {
    subscription = realtimeBus.on('nocode.records.changed').subscribe(frame => scheduler.push(frame))
  }
  listen()
  watch(
    () => store.connectionStatus,
    status => {
      // 换过会话（退出登录、换账号）后总线和频道登记都重建过：旧的事件流已经结束，重新接上。
      if (status === 'connected' && subscription?.closed) {
        releases.clear()
        awaiting.clear()
        syncChannels()
        listen()
      }
      scheduler.setConnected(status === 'connected')
    },
    { flush: 'sync' }
  )

  const onVisibility = () => scheduler.setVisible(documentVisible())
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility)

  const instance = { objectIds: () => wanted.value, invalidate: () => scheduler.invalidate() }
  instances.add(instance)
  onScopeDispose(() => {
    instances.delete(instance)
    scheduler.dispose()
    subscription?.unsubscribe()
    stopConfirmations?.()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility)
    for (const release of releases.values()) release()
    releases.clear()
  })
  return live
}
