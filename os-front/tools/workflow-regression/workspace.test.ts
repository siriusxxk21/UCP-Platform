import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import Workspace from '@/views/bpm/model/form/index.vue'
import { bindBusinessTask, businessTaskTemplate } from '@/nocode/flow-task-binding'

const calls = vi.hoisted(() => ({
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  deploy: vi.fn(),
  confirm: vi.fn(),
  validate: vi.fn()
}))
vi.mock('@/api/bpm/model', () => ({
  getModel: calls.get,
  createModel: calls.create,
  updateModel: calls.update,
  deployModel: calls.deploy
}))
vi.mock('@/api/bpm/category', () => ({ getCategorySimpleList: async () => [] }))
vi.mock('@/api/bpm/form', () => ({ getFormSimpleList: async () => [] }))
vi.mock('@/api/bpm/definition', () => ({ getProcessDefinition: calls.get }))
vi.mock('@/api/system/user', () => ({ getSimpleUserList: async () => [] }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: async () => [] }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 1 } }) }))
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn(), warning: vi.fn() },
  Modal: { confirm: calls.confirm }
}))
vi.mock('@/views/bpm/model/form/BusinessTaskConfig.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/bpm/model/form/BpmnNodeForms.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/bpm/components/bpmn-process-designer', () => ({ MyProcessDesigner: { render: () => null } }))

const dispose: Array<() => void> = []
const buttons = () => Array.from(document.querySelectorAll('button'))
function button(text: string) {
  const result = buttons().find(b => b.textContent?.includes(text))
  if (!result) throw new Error(`缺少按钮 ${text}`)
  return result
}
async function settle() {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
async function click(text: string) {
  button(text).click()
  await settle()
}
function editName(value: string) {
  const input = document.querySelector<HTMLInputElement>('input[placeholder="请输入流程名称"]')!
  input.value = value
  input.dispatchEvent(new Event('input'))
  return settle()
}
async function mount(type = 'update') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/bpm/model/form', component: Workspace },
      { path: '/bpm/model', component: { render: () => h('div', '模型列表') } }
    ]
  })
  await router.push({ path: '/bpm/model/form', query: { id: 'original', type } })
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(RouterView) }).use(router)
  const wrapper = defineComponent({
    setup(_, { slots }) {
      return () => h('div', [slots.default?.(), slots.action?.()])
    }
  })
  for (const name of [
    'a-modal',
    'a-card',
    'a-row',
    'a-col',
    'a-form-item',
    'a-radio-group',
    'a-radio',
    'a-select',
    'a-switch',
    'a-input-number',
    'a-divider',
    'a-spin'
  ])
    app.component(name, wrapper)
  app.component(
    'a-form',
    defineComponent({
      setup(_, { expose, slots }) {
        expose({ validate: calls.validate })
        return () => h('form', slots.default?.())
      }
    })
  )
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup(props, { slots }) {
        return () => h('div', { role: 'alert' }, [props.message, slots.action?.()])
      }
    })
  )
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled', 'loading'],
      setup(props, { slots }) {
        return () => h('button', { disabled: props.disabled }, slots.default?.())
      }
    })
  )
  const input = defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup(props, { emit }) {
      return () =>
        h('input', {
          value: props.value,
          onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
        })
    }
  })
  app.component('a-input', input)
  app.component('a-textarea', input)
  app.mount(host)
  dispose.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
  return router
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.validate.mockResolvedValue(undefined)
  calls.create.mockResolvedValue('new-copy')
  calls.update.mockResolvedValue(undefined)
  calls.deploy.mockResolvedValue(undefined)
  calls.get.mockResolvedValue({
    id: 'original',
    name: '原流程',
    key: 'original_key',
    category: 'test',
    type: 10,
    managerUserIds: [1],
    startUserIds: [1],
    startDeptIds: [2],
    formType: 10,
    formId: 10,
    bpmnXml: businessTaskTemplate('original_key', '原流程'),
    processIdRule: null,
    taskBeforeTriggerSetting: { url: 'https://example.com/task' }
  })
})
afterEach(() => {
  dispose.splice(0).forEach(fn => fn())
})

describe('流程工作区交互与持久化', () => {
  it('保存留在编辑页，保留范围及隐藏设置并清除未保存状态', async () => {
    const router = await mount()
    await editName('新名称')
    expect(document.body.textContent).toContain('有未保存修改')
    await click('保存草稿')
    expect(calls.update).toHaveBeenCalledOnce()
    expect(calls.update.mock.calls[0]?.[0]).toMatchObject({
      name: '新名称',
      startUserIds: ['1'],
      startDeptIds: ['2'],
      processIdRule: null,
      taskBeforeTriggerSetting: { url: 'https://example.com/task' }
    })
    expect(router.currentRoute.value.path).toBe('/bpm/model/form')
    expect(document.body.textContent).toContain('草稿已保存')
  })
  it('复制保存后刷新地址及身份，重复保存只更新同一副本', async () => {
    const router = await mount('copy')
    await click('保存草稿')
    await click('保存草稿')
    expect(calls.create).toHaveBeenCalledOnce()
    expect(calls.update).toHaveBeenCalledOnce()
    expect(calls.update.mock.calls[0]?.[0].id).toBe('new-copy')
    expect(router.currentRoute.value.query).toMatchObject({ type: 'update', id: 'new-copy' })
  })
  it('发布失败后草稿保留且可重试，重试不再创建副本', async () => {
    calls.deploy.mockRejectedValueOnce(new Error('资源权限校验失败'))
    const router = await mount('copy')
    await click('发布流程')
    expect(document.body.textContent).toContain('草稿已保存，发布未成功：资源权限校验失败')
    expect(router.currentRoute.value.path).toBe('/bpm/model/form')
    await click('发布流程')
    expect(calls.create).toHaveBeenCalledOnce()
    expect(calls.deploy).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.path).toBe('/bpm/model')
  })
  it('保存期间阻止重复提交，失败保留输入和未保存状态', async () => {
    let reject!: (error: Error) => void
    calls.update.mockReturnValueOnce(
      new Promise((_, fail) => {
        reject = fail
      })
    )
    const router = await mount()
    await editName('等待重试')
    await click('保存草稿')
    await click('保存草稿')
    expect(calls.update).toHaveBeenCalledOnce()
    expect(button('发布流程').disabled).toBe(true)
    await router.push('/bpm/model')
    expect(router.currentRoute.value.path).toBe('/bpm/model/form')
    reject(new Error('网络中断'))
    await settle()
    expect(document.body.textContent).toContain('网络中断')
    expect(document.body.textContent).toContain('有未保存修改')
  })
  it('离开时可取消并保留修改，也可明确放弃返回', async () => {
    const router = await mount()
    await editName('未保存')
    calls.confirm.mockImplementationOnce(options => options.onCancel())
    await click('返回')
    expect(router.currentRoute.value.path).toBe('/bpm/model/form')
    calls.confirm.mockImplementationOnce(options => options.onOk())
    await click('返回')
    expect(router.currentRoute.value.path).toBe('/bpm/model')
  })
  it('模型加载失败禁止保存，防止空模型覆盖存量', async () => {
    calls.get.mockRejectedValueOnce(new Error('加载失败'))
    await mount()
    expect(button('保存草稿').disabled).toBe(true)
    expect(button('发布流程').disabled).toBe(true)
    expect(document.body.textContent).toContain('加载失败')
  })
  it('业务任务自动去重冲突在发布前定位设计步骤，草稿仍可保存', async () => {
    const model = await calls.get()
    model.bpmnXml = bindBusinessTask(model.bpmnXml, 'business_work', {
      resource: {
        applicationId: '1',
        applicationVersion: 3,
        applicationChecksum: 'fixed',
        resourceId: 'form',
        resourceKind: 'FORM'
      },
      operation: 'CREATE',
      objectId: 'company'
    })
    model.autoApprovalType = 2
    calls.get.mockResolvedValue(model)
    await mount()
    await click('发布流程')
    expect(calls.deploy).not.toHaveBeenCalled()
    expect(calls.update).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('关闭自动去重')
    expect(button('流程设计').getAttribute('aria-current')).toBe('step')
    await click('保存草稿')
    expect(calls.update).toHaveBeenCalledOnce()
  })
})
