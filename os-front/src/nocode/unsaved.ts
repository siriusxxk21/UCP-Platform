import { Modal } from 'ant-design-vue'
import { onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, type RouteLocationNormalized } from 'vue-router'
import { usePageActivity } from './page-activity'

/** 复用底座确认框，组件分别提供真实脏状态，避免保存成功后仍误报。 */
export function confirmDiscard(changed: boolean, title = '放弃尚未保存的修改？'): Promise<boolean> {
  if (!changed) return Promise.resolve(true)
  return new Promise(resolve =>
    Modal.confirm({
      title,
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => {
        resolve(true)
      },
      onCancel: () => {
        resolve(false)
      }
    })
  )
}
export function useUnsavedNavigation(
  changed: () => boolean,
  options?: {
    title?: string
    continueEditing?: () => void
    confirm?: (changed: boolean, title?: string) => Promise<boolean>
  }
) {
  const page = usePageActivity()
  const guard = async (to: RouteLocationNormalized) => {
    // 保活页面只是被切到后台：表单原样留着，不问。真要销毁（关页签、退出登录）才问。
    if (page.released() || page.survives(to)) return true
    const leave = await (options?.confirm || confirmDiscard)(changed(), options?.title)
    if (!leave) options?.continueEditing?.()
    return leave
  }
  onBeforeRouteLeave(guard)
  onBeforeRouteUpdate(guard)
  const unload = (event: BeforeUnloadEvent) => {
    if (changed()) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  window.addEventListener('beforeunload', unload)
  const untrack = page.trackUnsaved(changed)
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', unload)
    untrack()
  })
}
