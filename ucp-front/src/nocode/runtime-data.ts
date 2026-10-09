import { onBeforeUnmount, watch } from 'vue'
import { usePageActivity, type PageActivity } from './page-activity'

/** 一次数据变更。服务器推送到达、页面回到前台，都走 invalidateRuntimeData 这一个入口。 */
export interface RuntimeDataChange {
  applicationId: string
  /** 省略 = 该应用下所有对象（说不清影响范围时） */
  objectId?: string
  /** 省略或空数组 = 该对象下任意记录 */
  recordIds?: string[]
}

/** 订阅方当前关心的范围；每次失效时现算。 */
export interface RuntimeDataInterest {
  applicationId: string
  /** 不给 = 关心该应用任意对象 */
  objectIds?: string[]
  /** 不给 = 关心对象下任意记录 */
  recordIds?: string[]
}

export interface RuntimeDataSubscription {
  /** 返回 undefined = 现在没有可刷新的东西（还没加载完、正在编辑），这次失效直接忽略 */
  interest: () => RuntimeDataInterest | undefined
  /** 静默重取：不出 loading、不清空已有内容、结果没变就不更新界面、不覆盖正在编辑的内容 */
  refresh: () => void | Promise<void>
}

/**
 * 推送上线前为 true：页面回到前台时视为「全部过期」并静默重取，否则用户得自己点刷新。
 * 推送上线后置 false：回到前台只补取在后台期间被标记过期的。
 */
export const runtimeDataPolicy = { refreshOnResume: true }

interface Entry {
  subscription: RuntimeDataSubscription
  activity: PageActivity
  stale: boolean
  missed: number
  queued: boolean
  running: boolean
  again: boolean
  disposed: boolean
}
const entries = new Set<Entry>()
// 页面在后台期间错过的本地刷新信号；回到前台时据此补取。
const missedSignals = new WeakMap<PageActivity, number>()

function matches(change: RuntimeDataChange, interest: RuntimeDataInterest): boolean {
  if (change.applicationId !== interest.applicationId) return false
  if (change.objectId && interest.objectIds && !interest.objectIds.includes(change.objectId)) return false
  const changed = change.recordIds
  if (changed?.length && interest.recordIds && !interest.recordIds.some(id => changed.includes(id))) return false
  return true
}

async function run(entry: Entry) {
  if (entry.disposed) return
  if (!entry.activity.active.value) {
    entry.stale = true
    return
  }
  // 上一次静默重取还没回来：等它回来后再取一次，不并发叠加。
  if (entry.running) {
    entry.again = true
    return
  }
  entry.stale = false
  if (!entry.subscription.interest()) return
  entry.running = true
  try {
    await entry.subscription.refresh()
  } catch {
    // 静默重取失败不打扰用户：界面保留现有内容，下一次失效或手动刷新时再取。
  } finally {
    entry.running = false
    if (entry.again) {
      entry.again = false
      schedule(entry)
    }
  }
}

// 同一拍里的多次失效合并成一次。
function schedule(entry: Entry) {
  if (entry.queued) return
  entry.queued = true
  queueMicrotask(() => {
    entry.queued = false
    void run(entry)
  })
}

/**
 * 全局入口：使数据失效。
 * 在前台的订阅方立即静默重取；在后台（保活但没显示）的只标记过期，回到前台时补取。
 */
export function invalidateRuntimeData(change: RuntimeDataChange): void {
  for (const entry of entries) {
    const interest = entry.subscription.interest()
    // 后台页面这会儿说不清自己关心什么（比如还在加载）：保守地记为过期。
    if (interest ? !matches(change, interest) : entry.activity.active.value) continue
    if (entry.activity.active.value) schedule(entry)
    else entry.stale = true
  }
}

/** 页面在后台时错过了一次本地刷新信号：回到前台时它下面的订阅方要补取。 */
export function markPageDataStale(activity: PageActivity): void {
  missedSignals.set(activity, (missedSignals.get(activity) || 0) + 1)
}

/** 当前有数据订阅的应用；推送通知里拿不到应用编号时，可以对这些应用各调一次 invalidateRuntimeData。 */
export function openRuntimeApplications(): string[] {
  const ids = new Set<string>()
  for (const entry of entries) {
    const id = entry.subscription.interest()?.applicationId
    if (id) ids.add(id)
  }
  return [...ids]
}

/** 组件 setup 里注册一份数据订阅；卸载时自动注销。 */
export function useRuntimeDataRefresh(subscription: RuntimeDataSubscription): void {
  const activity = usePageActivity()
  const entry: Entry = {
    subscription,
    activity,
    stale: false,
    missed: missedSignals.get(activity) || 0,
    queued: false,
    running: false,
    again: false,
    disposed: false
  }
  entries.add(entry)
  onBeforeUnmount(() => {
    entry.disposed = true
    entries.delete(entry)
  })
  watch(activity.resumed, () => {
    const missed = missedSignals.get(activity) || 0
    if (!runtimeDataPolicy.refreshOnResume && !entry.stale && missed === entry.missed) return
    entry.missed = missed
    schedule(entry)
  })
}
