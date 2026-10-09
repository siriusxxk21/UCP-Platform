import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onMounted } from 'vue'
import Detail from '@/views/bpm/processInstance/detail/index.vue'

const calls = vi.hoisted(() => ({ detail: vi.fn(), disabled: vi.fn(), hidden: vi.fn() }))
vi.mock('@/utils/request', () => ({
  default: { get: async () => [], post: async () => ({ items: [], reviewRequired: false }) }
}))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/views/bpm/processInstance/detail/FlowMaterialCard.vue', () => ({ default: { render: () => null } }))
vi.mock('vue-router', async importOriginal => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: { id: 'process', taskId: 'task' } }),
  useRouter: () => ({})
}))
vi.mock('@/api/bpm/processInstance', () => ({
  getApprovalDetail: calls.detail,
  getProcessInstanceBpmnModelView: async () => ({})
}))
vi.mock('@/api/bpm/task', () => ({
  approveTask: vi.fn(),
  rejectTask: vi.fn(),
  getTaskListByProcessInstanceId: async () => []
}))
vi.mock('@/components/BusinessForm/registry', () => ({ businessFormViewer: () => undefined }))
vi.mock('@/views/bpm/components/bpmn-process-designer/package', () => ({ MyProcessViewer: { render: () => null } }))
vi.mock('@/views/bpm/components/simple-process-design', () => ({ SimpleProcessViewer: { render: () => null } }))
vi.mock('@/components/form-create', () => ({
  setConfAndFields2: (target: any, _conf: any, fields: string[], values: any) => {
    target.value.rule = fields.map(field => JSON.parse(field))
    target.value.option = {}
    target.value.value = values
  }
}))
vi.mock('@form-create/ant-design-vue', () => ({
  default: defineComponent({
    emits: ['update:api'],
    setup(_, { emit }) {
      onMounted(() => emit('update:api', { disabled: calls.disabled, hidden: calls.hidden }))
      return () => h('div', '实际表单已挂载')
    }
  })
}))
vi.mock('ant-design-vue', async importOriginal => ({
  ...(await importOriginal<typeof import('ant-design-vue')>()),
  message: {},
  Modal: { info: vi.fn() }
}))
const disposers: Array<() => void> = []
afterEach(() => {
  disposers.splice(0).forEach(dispose => dispose())
  vi.clearAllMocks()
})
async function mount(independent: boolean, ended = false) {
  calls.detail.mockResolvedValue({
    processInstance: {
      id: 'process',
      status: ended ? 2 : 1,
      endTime: ended ? '2026-09-10 13:00:00' : null,
      formVariables: {}
    },
    processDefinition: { formType: 10, formConf: '{}', formFields: ['{"field":"old"}'] },
    todoTask: {
      id: 'task',
      taskDefinitionKey: 'node',
      formConf: '{}',
      formFields: ['{"field":"edit"}', '{"field":"read"}', '{"field":"secret"}'],
      ...(independent ? { formBinding: { mode: 'OVERRIDE', source: { kind: 'FLOW_FORM', formId: '2' } } } : {})
    },
    formFieldsPermission: { read: '1', secret: '3', ...(ended ? { edit: '2' } : {}) }
  })
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(Detail)
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of [
    'a-space',
    'a-empty',
    'a-skeleton',
    'a-avatar',
    'a-tag',
    'a-tabs',
    'a-tab-pane',
    'a-select',
    'a-alert',
    'a-row',
    'a-col',
    'a-timeline',
    'a-timeline-item',
    'a-table',
    'a-modal',
    'a-form',
    'a-form-item',
    'a-textarea'
  ])
    app.component(name, wrapper)
  app.component(
    'a-card',
    defineComponent({
      props: ['loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', props.loading ? '加载中' : slots.default?.())
    })
  )
  app.component('a-button', wrapper)
  app.mount(host)
  disposers.push(() => {
    app.unmount()
    host.remove()
  })
  for (let index = 0; index < 15; index++) {
    await Promise.resolve()
    await nextTick()
  }
  return host
}
describe('流程任务表单的延迟挂载权限', () => {
  it('独立节点表单挂载后允许填写，并限制只读和隐藏字段', async () => {
    const host = await mount(true)
    expect(host.textContent).toContain('实际表单已挂载')
    expect(calls.disabled).toHaveBeenCalledWith(false)
    expect(calls.disabled).toHaveBeenCalledWith(true, 'read')
    expect(calls.hidden).toHaveBeenCalledWith(true, 'secret')
  })
  it('传统审批表单挂载后仍默认只读', async () => {
    await mount(false)
    expect(calls.disabled).toHaveBeenCalledWith(true)
    expect(calls.disabled).not.toHaveBeenCalledWith(false)
  })
  it('流程结束后独立表单保持只读，即使旧待办仍返回可写字段权限', async () => {
    const host = await mount(true, true)
    expect(host.textContent).toContain('实际表单已挂载')
    expect(calls.disabled).toHaveBeenCalledWith(true)
    expect(calls.disabled).not.toHaveBeenCalledWith(false)
    expect(calls.disabled).not.toHaveBeenCalledWith(false, 'edit')
    expect(calls.hidden).toHaveBeenCalledWith(true, 'secret')
  })
})
