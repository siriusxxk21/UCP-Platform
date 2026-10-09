import { onScopeDispose, ref, shallowReactive, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import type { ApplicationApi } from '@/api/nocode/application'
import type { ApplicationDetail, FollowResult, FollowRun, ObjectFollow } from '@/types/nocode/application'
import { errorMessage } from './data-center'
import { createRequestSession } from './request-session'

/** 一行的显示状态：关 / 开且已是最新 / 开但落后（待跟随）/ 开且没跟上（跟随待处理）。 */
export type FollowDisplay = 'OFF' | 'CURRENT' | 'BEHIND' | 'PENDING'
export function followDisplay(follow: ObjectFollow): FollowDisplay {
  if (!follow.enabled) return 'OFF'
  if (follow.state === 'PENDING') return 'PENDING'
  return follow.latestVersion != null && follow.pinnedVersion < follow.latestVersion ? 'BEHIND' : 'CURRENT'
}

export const followSwitchHint = '对象发布新版本后，本应用自动同步并发布，不需要手动操作。'
export const followDirtyHint = '请先保存或放弃当前修改'
export const followSuspendedHint =
  '应用已停用，自动跟随不处理已停用的应用。请点这一行的“同步最新版本”，调整配置并保存，然后“发布并启用”。'

type FollowApi = Pick<ApplicationApi, 'objectFollows' | 'setObjectFollow' | 'runObjectFollow'>

/**
 * 应用工作台「已引用对象」的自动跟随开关与状态。
 * 开关独立于应用草稿与发布版本，拨动立即生效；操作会让应用修订号前进，
 * 所以只在工作台没有未保存修改时可用，并把返回的应用详情交回工作台接收。
 */
export function useApplicationObjectFollow(options: {
  api: FollowApi
  applicationId: () => string
  dirty: () => boolean
  canEdit: () => boolean
  /** 应用不是启用状态（被暂停或已停用）：自动跟随不处理它，恢复要走「同步 → 调整 → 发布并启用」。不传按启用处理。 */
  suspended?: () => boolean
  /** 操作前登记当前草稿；返回 false 表示工作台此刻不能接收新的应用详情。 */
  capture: () => boolean
  /** 把返回的应用详情交给工作台（走工作台现有的接收路径）。 */
  accept: (detail: ApplicationDetail) => void | Promise<void>
  onError?: (message: string) => void
}) {
  /** 状态已读取成功：按它决定各行显示什么、现有的同步按钮要不要隐藏。 */
  const available = ref(false)
  /** 最近一次读取失败（含后端还没有这个接口）：整列不显示。读取中不算失败——列要在表格挂载时就在，后加的列表格不显示。 */
  const unavailable = ref(false)
  const follows = shallowReactive<Record<string, ObjectFollow>>({})
  /** 应用已跟上、但草稿没能同步的对象：对这些行恢复显示同步按钮。 */
  const unsynced = shallowReactive<Record<string, string>>({})
  const acting = ref('')
  const loadSession = createRequestSession(),
    actionSession = createRequestSession()

  function clear(target: Record<string, unknown>) {
    for (const key of Object.keys(target)) delete target[key]
  }
  function reset() {
    loadSession.invalidate()
    actionSession.invalidate()
    available.value = false
    unavailable.value = false
    acting.value = ''
    clear(follows)
    clear(unsynced)
  }
  watch(options.applicationId, reset, { flush: 'sync' })
  onScopeDispose(reset)

  async function refresh() {
    const current = loadSession.begin(),
      requested = options.applicationId()
    if (!requested) return
    try {
      const rows = await options.api.objectFollows(requested)
      // 先发后到的旧结果、切换应用后的在途结果都不能回填。
      if (!current() || requested !== options.applicationId()) return
      clear(follows)
      for (const row of rows) follows[row.objectId] = row
      available.value = true
      unavailable.value = false
    } catch (cause) {
      if (!current() || requested !== options.applicationId()) return
      available.value = false
      unavailable.value = true
      clear(follows)
      console.warn('[nocode] 自动跟随状态读取失败，“自动跟随”列不显示', cause)
    }
  }

  /** 不能操作的原因；空串表示可以操作。 */
  const blockedReason = () => (options.dirty() ? followDirtyHint : '')
  const canOperate = () => options.canEdit() && !options.dirty() && !acting.value
  const suspended = () => !!options.suspended?.()
  /**
   * 现有的「同步最新版本」按钮：跟随关着（或状态读不到）时照常显示，开着时隐藏，草稿没同步上的行例外。
   * 应用不是启用状态时也照常显示：自动跟随不处理已停用的应用，恢复它不应该要先去关开关。
   */
  const showSync = (objectId: string) =>
    !available.value || !follows[objectId]?.enabled || objectId in unsynced || suspended()

  async function operate(objectId: string, call: (applicationId: string) => Promise<FollowRun>) {
    if (!canOperate() || !options.capture()) return
    const current = actionSession.begin(),
      requested = options.applicationId()
    const valid = () => current() && requested === options.applicationId()
    acting.value = objectId
    try {
      const result = await call(requested)
      if (!valid()) return
      follows[objectId] = result.follow
      await options.accept(result.application)
      if (!valid()) return
      if (result.draftSynced) delete unsynced[objectId]
      else {
        const reason = result.draftReason || '原因未返回'
        unsynced[objectId] = reason
        message.warning(`应用已跟上，但草稿没能同步：${reason}。请手工同步并修正后保存。`)
      }
      await refresh()
    } catch (cause) {
      if (!valid()) return
      options.onError?.(errorMessage(cause))
      // 失败多半是开关已被别人改过（修订号不符）：重新读取，下一次操作才带得上最新的修订号。
      await refresh()
    } finally {
      if (valid()) acting.value = ''
    }
  }
  function toggle(objectId: string, enabled: boolean) {
    const follow = follows[objectId]
    if (!follow || !canOperate() || follow.enabled === enabled) return
    const send = () =>
      operate(objectId, applicationId =>
        options.api.setObjectFollow({ applicationId, objectId, enabled, expectedRevision: follow.revision })
      )
    if (enabled) return send()
    Modal.confirm({
      title: '关闭自动跟随？',
      content: `关闭后本应用固定在 V${follow.pinnedVersion}，需要手动同步并发布。对象做不兼容的改动（例如停用字段）时，本应用可能被要求暂停。`,
      okText: '关闭',
      cancelText: '保持开启',
      onOk: send
    })
  }
  /** 「立即跟随」与「重试」是同一个操作。 */
  const run = (objectId: string) =>
    operate(objectId, applicationId => options.api.runObjectFollow({ applicationId, objectId }))

  return {
    available,
    unavailable,
    follows,
    unsynced,
    acting,
    refresh,
    reset,
    blockedReason,
    canOperate,
    suspended,
    showSync,
    toggle,
    run
  }
}

/** 对象发布成功后的跟随提示；查询失败只留一条控制台警告，绝不影响「已发布」的结论。 */
export async function announceFollowResult(
  load: () => Promise<FollowResult[]>,
  notify: { success: (text: string) => unknown; warning: (text: string) => unknown }
): Promise<void> {
  try {
    const results = await load()
    const pending = results.filter(item => item.outcome === 'PENDING')
    const followed = results.filter(item => item.outcome === 'FOLLOWED')
    if (pending.length)
      notify.warning(
        `对象已发布。${pending.length} 个应用暂时没跟上：${pending.map(item => item.applicationName).join('、')}。原因见各应用的“已引用对象”。`
      )
    else if (followed.length) notify.success(`对象已发布，${followed.length} 个应用已自动跟上。`)
  } catch (cause) {
    console.warn('[nocode] 自动跟随结果读取失败，不影响对象发布', cause)
  }
}
