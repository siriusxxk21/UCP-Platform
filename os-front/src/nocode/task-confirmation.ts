import { createVNode, getCurrentInstance, onBeforeUnmount, render } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'

/** 任务中心的轻量二次确认统一使用弹窗；组件卸载会收回确认，避免遗留承诺继续执行。 */
export function useTaskConfirmation() {
  const appContext = getCurrentInstance()?.appContext
  const pending = new Set<(result: boolean) => void>()
  let active: Promise<boolean> | undefined
  onBeforeUnmount(() => pending.forEach(finish => finish(false)))
  function confirm(title: string, content = '', okText = '确认', cancelText = '取消'): Promise<boolean> {
    if (active) return active
    active = new Promise(resolve => {
      const host = document.createElement('div')
      document.body.appendChild(host)
      const finish = (result: boolean) => {
        pending.delete(finish)
        active = undefined
        render(null, host)
        host.remove()
        resolve(result)
      }
      pending.add(finish)
      const vnode = createVNode(
        OsModalForm,
        {
          open: true,
          title,
          displayMode: 'modal',
          allowSwitchDisplay: false,
          resizable: false,
          width: 480,
          okText,
          cancelText,
          onOk: () => finish(true),
          onCancel: () => finish(false)
        },
        { formItems: () => content || '请确认是否继续此操作。' }
      )
      if (appContext) vnode.appContext = appContext
      render(vnode, host)
    })
    return active
  }
  const confirmDiscard = (changed: boolean, title = '放弃尚未保存的修改？') =>
    changed
      ? confirm(title, '已保存的内容会保留，尚未提交的输入将被放弃。', '放弃修改', '继续编辑')
      : Promise.resolve(true)
  return { confirm, confirmDiscard }
}
