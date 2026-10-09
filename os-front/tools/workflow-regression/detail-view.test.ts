import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
vi.mock('@/utils/request', () => ({
  default: { get: async () => [], post: async () => ({ items: [], reviewRequired: false }) }
}))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/views/bpm/processInstance/detail/FlowMaterialCard.vue', () => ({ default: { render: () => null } }))
const api = vi.hoisted(() => ({
  detail: vi.fn(),
  model: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  list: vi.fn(),
  configure: vi.fn()
}))
vi.mock('@/api/bpm/processInstance', () => ({
  getApprovalDetail: api.detail,
  getProcessInstanceBpmnModelView: api.model
}))
vi.mock('@/api/bpm/task', () => ({
  approveTask: api.approve,
  rejectTask: api.reject,
  getTaskListByProcessInstanceId: api.list
}))
vi.mock('vue-router', async importOriginal => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: { id: 'pi', taskId: 'task' } }),
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('@form-create/ant-design-vue', () => ({
  default: defineComponent({ setup: () => () => h('div', { class: 'form-create' }) })
}))
vi.mock('@/components/form-create', () => ({ setConfAndFields2: api.configure }))
vi.mock('@/components/BusinessForm/registry', () => ({ businessFormViewer: () => undefined }))
vi.mock('@/views/bpm/components/bpmn-process-designer/package', () => ({
  MyProcessViewer: defineComponent({ setup: () => () => h('div', { class: 'bpmn-diagram' }) })
}))
vi.mock('@/views/bpm/components/simple-process-design', () => ({
  SimpleProcessViewer: defineComponent({ setup: () => () => h('div', { class: 'simple-diagram' }) })
}))
vi.mock('ant-design-vue', async importOriginal => ({
  ...(await importOriginal<typeof import('ant-design-vue')>()),
  message: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  Modal: { info: vi.fn() }
}))
import Detail from '@/views/bpm/processInstance/detail/index.vue'

const cleanup: (() => void)[] = []
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const response = (status = 1, source = 'NONE') => ({
  processInstance: {
    id: 'pi',
    name: '带发起材料的流程',
    status,
    endTime: status === 1 ? null : '2026-09-10',
    formVariables: { confidentialHistory: '不能重新提交' }
  },
  processDefinition: { modelType: 10, formType: 10, formConf: '{}', formFields: [] },
  todoTask: {
    id: 'task',
    taskDefinitionKey: 'approval',
    name: '审批',
    formBinding: { mode: 'OVERRIDE', source: { kind: source } }
  },
  activityNodes: [],
  formFieldsPermission: { confidentialHistory: '2' }
})
async function mount() {
  const el = document.createElement('div')
  document.body.append(el)
  const app = createApp(Detail)
  const tags = [
    'card',
    'space',
    'button',
    'tag',
    'empty',
    'row',
    'col',
    'avatar',
    'tabs',
    'tab-pane',
    'alert',
    'timeline',
    'timeline-item',
    'table',
    'textarea',
    'modal'
  ]
  for (const tag of tags)
    app.component(
      `a-${tag}`,
      defineComponent({
        inheritAttrs: false,
        setup(_, { attrs, slots }) {
          return () =>
            tag === 'modal' && !attrs.open
              ? null
              : h(
                  tag === 'button' ? 'button' : 'div',
                  { ...attrs, class: tag === 'modal' ? 'approval-modal' : attrs.class },
                  [
                    slots.title?.(),
                    slots.extra?.(),
                    slots.default?.(),
                    tag === 'empty'
                      ? String(attrs.description || '')
                      : tag === 'alert'
                        ? String(attrs.message || '')
                        : null,
                    tag === 'modal' ? h('button', { onClick: attrs.onOk as any }, '确认提交') : null
                  ]
                )
        }
      })
    )
  app.config.warnHandler = () => undefined
  app.mount(el)
  cleanup.push(() => {
    app.unmount()
    el.remove()
  })
  await flush()
  return el
}
beforeEach(() => {
  vi.resetAllMocks()
  api.detail.mockResolvedValue(response())
  api.model.mockResolvedValue({ bpmnXml: '<definitions />', tasks: [] })
  api.list.mockResolvedValue([])
  api.approve.mockResolvedValue(true)
})
afterEach(() => cleanup.splice(0).forEach(fn => fn()))
describe('流程详情表单与办理边界', () => {
  it('节点 NONE 优先于流程原始发起表单，审批仅提交空变量', async () => {
    const el = await mount()
    expect(el.textContent).toContain('当前节点无需填写表单')
    expect(el.querySelector('.form-create')).toBeNull()
    expect(api.configure).not.toHaveBeenCalled()
    ;[...el.querySelectorAll('button')].find(button => button.textContent?.trim() === '通过审批')!.click()
    await flush()
    el.querySelector<HTMLButtonElement>('.approval-modal button')!.click()
    await flush()
    expect(api.approve).toHaveBeenCalledWith({ id: 'task', reason: '同意', variables: {} })
  })
  it.each([2, 3, 4])('结束状态 %s 不暴露通过/拒绝入口，即使返回旧待办', async status => {
    api.detail.mockResolvedValue(response(status))
    const el = await mount()
    expect(
      [...el.querySelectorAll('button')].some(button => ['通过审批', '拒绝'].includes(button.textContent?.trim() || ''))
    ).toBe(false)
  })
  it('BPMN 页签使用只读组件并且没有源码 pre', async () => {
    const el = await mount()
    expect(el.querySelector('.bpmn-diagram')).not.toBeNull()
    expect(el.querySelector('pre')).toBeNull()
  })
  it('SIMPLE 模型使用简易只读流程树', async () => {
    const data = response()
    data.processDefinition.modelType = 20
    api.detail.mockResolvedValue(data)
    api.model.mockResolvedValue({ bpmnXml: '<definitions />', simpleModel: { id: 'start', type: 10 }, tasks: [] })
    const el = await mount()
    expect(el.querySelector('.simple-diagram')).not.toBeNull()
    expect(el.querySelector('.bpmn-diagram')).toBeNull()
  })
})
