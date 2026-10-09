import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, isReactive, nextTick, onMounted } from 'vue'
import Antd from 'ant-design-vue'
import { useFlowMaterials } from '@/views/bpm/processInstance/detail/use-flow-materials'
import MaterialFormView from '@/views/bpm/processInstance/detail/MaterialFormView.vue'
import FlowMaterialCard from '@/views/bpm/processInstance/detail/FlowMaterialCard.vue'
import { reviewItems, reviewMaterial } from '../workflow-workbench-preview/review-fixtures'
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ user: {}, permissions: [] }) }))
const dispose: (() => void)[] = []
const flush = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: () => ({
      matches: false,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {}
    })
  })
})
afterEach(() => {
  dispose.splice(0).forEach(fn => fn())
  vi.unstubAllGlobals()
})
async function mountMaterial(id: string) {
  const item = reviewItems.find(item => item.id === id)!,
    failed = vi.fn()
  const detail = reviewMaterial(id)
  // 历史预览夹具缺少当前对象契约的 settings；正式材料响应始终提供该对象。
  if (detail.businessForm) detail.businessForm.model.object.settings ??= {}
  let state!: ReturnType<typeof useFlowMaterials>
  const component = defineComponent({
    setup() {
      state = useFlowMaterials(
        { list: async () => ({ items: [item], reviewRequired: false }), detail: async () => detail },
        () => ({ processInstanceId: 'review', taskId: 'approval' })
      )
      onMounted(() => state.load())
      return () => (state.details[id] ? h(MaterialFormView, { detail: state.details[id], onFailed: failed }) : null)
    }
  })
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(component)
  app.use(Antd)
  app.mount(host)
  dispose.push(() => {
    app.unmount()
    host.remove()
  })
  await flush()
  return { host, state, failed }
}
describe('材料读取到真实只读表单的完整 Vue 链路', () => {
  it.each([false, true])('材料缺失只显示具体原因，审批提示按当前可办理状态控制：%s', async approvalRequired => {
    const warning = '历史发起表单未封存初始提交值，不能以当前流程变量代替历史材料'
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp(FlowMaterialCard, {
      item: { ...reviewItems[0]!, state: 'UNAVAILABLE', warning },
      ordinal: 1,
      approvalRequired
    }).use(Antd)
    app.mount(host)
    dispose.push(() => {
      app.unmount()
      host.remove()
    })
    await flush()
    expect(host.querySelectorAll('.ant-alert')).toHaveLength(1)
    expect(host.textContent).toContain(warning)
    expect(host.textContent).not.toContain('权限')
    expect(host.textContent?.includes('恢复可读后才能通过')).toBe(approvalRequired)
    expect(host.textContent).toContain('暂未取得提交内容')
  })
  it('权限拒绝显示权限原因，不伪装为历史材料未封存', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp(FlowMaterialCard, {
      item: { ...reviewItems[0]!, state: 'UNAVAILABLE', warning: '当前没有查阅该材料的权限' },
      ordinal: 1
    }).use(Antd)
    app.mount(host)
    dispose.push(() => {
      app.unmount()
      host.remove()
    })
    await flush()
    expect(host.textContent).toContain('当前没有查阅该材料的权限')
    expect(host.textContent).not.toContain('未封存')
    expect(host.textContent).not.toContain('才能通过')
  })
  it('reactive缓存中的流程表单能够挂载真实FormCreate，字段值不因Proxy clone失败而丢失', async () => {
    const { host, state, failed } = await mountMaterial('finance-material')
    expect(isReactive(state.details['finance-material']!.flowForm!.values)).toBe(true)
    expect(failed).not.toHaveBeenCalled()
    const inputs = [...host.querySelectorAll('input')]
    expect(inputs.map(input => input.value)).toEqual(['预算已确认', '按合同季度支付'])
    expect(inputs.every(input => input.disabled)).toBe(true)
    expect(state.details['finance-material']!.flowForm!.values).toEqual({
      budget: '预算已确认',
      payee: '按合同季度支付'
    })
  })
  it('业务材料沿用真实RecordReadView展示授权快照，不出现可编辑输入', async () => {
    const { host, failed } = await mountMaterial('company-material')
    expect(failed).not.toHaveBeenCalled()
    expect(host.textContent).toContain('上海示例科技有限公司')
    expect(host.textContent).toContain('统一社会信用代码')
    expect(host.querySelector('input')).toBeNull()
  })
})
