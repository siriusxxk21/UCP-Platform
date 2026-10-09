import { computed, inject, ref, watch, type InjectionKey, type Ref } from 'vue'
import { usePageActivity } from './page-activity'
import { markPageDataStale } from './runtime-data'
/** 同一应用页面的业务块共享刷新信号；只影响当前页面，不重建底座事件或连接系统。 */
export const applicationRefreshKey: InjectionKey<Ref<number>> = Symbol('nocode-application-refresh')
export const pageRefreshKey: InjectionKey<Ref<Record<string, number>>> = Symbol('nocode-page-refresh')
export const blockRefreshId = (pageId?: string, recordId?: string, nodeId?: string) =>
  JSON.stringify([pageId || '', recordId || '', nodeId || ''])
export function usePageRefresh() {
  return inject(pageRefreshKey, ref<Record<string, number>>({}))
}
export function useApplicationRefresh(): Ref<number> {
  const signal = inject(applicationRefreshKey, ref(0))
  const activity = usePageActivity()
  if (!activity.kept) return signal
  // 保活页面在后台时不跟随刷新信号：否则别的页面一保存，后台的列表全部重查并清掉选中。
  // 错过的信号记为过期，回到前台时静默补取。
  const seen = ref(signal.value)
  watch(
    signal,
    value => {
      if (activity.active.value) seen.value = value
      else markPageDataStale(activity)
    },
    { flush: 'sync' }
  )
  return computed({
    get: () => seen.value,
    set: () => {
      signal.value++
    }
  })
}
