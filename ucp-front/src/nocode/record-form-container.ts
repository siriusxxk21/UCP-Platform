import { cloneVNode, inject, isVNode, type ComputedRef, type InjectionKey } from 'vue'
import { Form } from 'ant-design-vue'

export const detailFormContainerKey: InjectionKey<ComputedRef<boolean>> = Symbol.for(
  'ucp-platform.nocode.detail-form-container'
)
type AntdFormSetup = Parameters<NonNullable<typeof Form.setup>>

/**
 * Ant Design Vue 4.x 的 Form 根节点固定为 form，不支持 tag/component 参数。
 * 保留其 setup 提供的字段注册、校验及样式，只把内部明细的根 VNode 改为 div。
 * 不改 DOM 位置；真实引擎回归验证此适配与已安装库的渲染结构兼容。
 */
export const RecordFormContainer = {
  ...Form,
  name: 'NocodeRecordFormContainer',
  setup(props: AntdFormSetup[0], context: AntdFormSetup[1]) {
    const detail = inject(detailFormContainerKey, undefined)
    const render = Form.setup?.(props, context)
    if (typeof render !== 'function') throw new Error('表单容器初始化失败，请检查组件版本')
    return () => {
      const node = render()
      if (!detail?.value) return node
      if (!isVNode(node) || node.type !== 'form') throw new Error('明细表单容器结构不兼容，请检查组件版本')
      const container = cloneVNode(node)
      container.type = 'div'
      return container
    }
  }
}
