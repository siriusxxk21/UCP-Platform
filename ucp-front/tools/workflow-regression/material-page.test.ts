import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onMounted, reactive } from 'vue'
const calls = vi.hoisted(() => ({
  route: null as any,
  detail: vi.fn(),
  post: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  validate: vi.fn(),
  message: vi.fn()
}))
vi.mock('vue-router', async importOriginal => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => calls.route,
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('@/utils/request', () => ({ default: { get: async () => [], post: calls.post } }))
vi.mock('@/api/bpm/processInstance', () => ({
  getApprovalDetail: calls.detail,
  getProcessInstanceBpmnModelView: async () => ({})
}))
vi.mock('@/api/bpm/task', () => ({
  approveTask: calls.approve,
  rejectTask: calls.reject,
  getTaskListByProcessInstanceId: async () => []
}))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/components/BusinessForm/registry', () => ({ businessFormViewer: () => undefined }))
vi.mock('@/views/bpm/components/bpmn-process-designer/package', () => ({ MyProcessViewer: { render: () => null } }))
vi.mock('@/views/bpm/components/simple-process-design', () => ({ SimpleProcessViewer: { render: () => null } }))
vi.mock('@/components/form-create', () => ({
  setConfAndFields2: (target: any, _conf: any, fields: any, value: any) => {
    target.value = { rule: fields.map(JSON.parse), option: {}, value }
  }
}))
vi.mock('@form-create/ant-design-vue', () => ({
  default: defineComponent({
    emits: ['update:api'],
    setup(_, { emit }) {
      onMounted(() => emit('update:api', { disabled: vi.fn(), hidden: vi.fn(), validate: calls.validate }))
      return () => h('div', '当前节点输入')
    }
  })
}))
vi.mock('@/views/bpm/processInstance/detail/FlowMaterialCard.vue', () => ({
  default: defineComponent({
    props: ['item', 'detail', 'error'],
    setup(props, { slots }) {
      return () =>
        h('article', { 'data-material-id': props.item.id }, [
          props.item.nodeName,
          props.detail ? String(props.detail.flowForm.values.name) : '',
          props.error || '',
          slots.versions?.()
        ])
    }
  })
}))
vi.mock('ant-design-vue', async importOriginal => ({
  ...(await importOriginal<typeof import('ant-design-vue')>()),
  message: { info: calls.message, warning: calls.message, error: calls.message, success: calls.message }
}))
const { Tabs } = await vi.importActual<typeof import('ant-design-vue')>('ant-design-vue')
import Detail from '@/views/bpm/processInstance/detail/index.vue'
const previous = {
  id: 'prior',
  taskId: 'prior-task',
  nodeId: 'source-node',
  nodeName: '公司登记',
  formName: '公司表单',
  submitterName: '张三',
  submittedAt: '2026-09-10',
  revision: 1,
  kind: 'FLOW_FORM',
  state: 'CURRENT',
  required: true
}
const approval = (source = 'NONE', mode = 'OVERRIDE') => ({
  processInstance: {
    id: 'process',
    name: '审批',
    status: 1,
    endTime: null,
    formVariables: { privateHistory: '不应回写' }
  },
  processDefinition: { formType: 10, formFields: ['{"type":"input","field":"privateHistory"}'], formConf: '{}' },
  todoTask: {
    id: 'task',
    name: '审核',
    taskDefinitionKey: 'approval',
    formBinding: { mode, source: { kind: source } },
    formConf: '{}',
    formFields: ['{"type":"input","field":"current"}'],
    formVariables: { current: '本次填写' }
  },
  formFieldsPermission: {},
  activityNodes: []
})
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const disposers: (() => void)[] = []
beforeEach(() => {
  vi.resetAllMocks()
  calls.route = reactive({ query: { id: 'process', taskId: 'task' } })
  calls.detail.mockResolvedValue(approval())
  calls.validate.mockResolvedValue(true)
  calls.post.mockImplementation(async (path: string) =>
    path.endsWith('/list')
      ? { items: [previous], reviewRequired: true, reviewToken: 'review-proof' }
      : {
          item: previous,
          flowForm: { conf: '{}', fields: ['{"type":"input","field":"name"}'], values: { name: '前序提交值' } }
        }
  )
  calls.approve.mockResolvedValue(true)
  calls.reject.mockResolvedValue(true)
})
afterEach(() => disposers.splice(0).forEach(fn => fn()))
async function mount() {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(Detail)
  for (const name of [
    'card',
    'space',
    'button',
    'tag',
    'select',
    'timeline',
    'timeline-item',
    'alert',
    'empty',
    'skeleton',
    'table',
    'textarea',
    'modal'
  ])
    app.component(
      `a-${name}`,
      defineComponent({
        inheritAttrs: false,
        setup(_, { attrs, slots }) {
          return () => {
            if (name === 'modal' && !attrs.open) return null
            if (name === 'select')
              return h(
                'select',
                {
                  'aria-label': attrs['aria-label'],
                  value: attrs.value,
                  onChange: (event: any) => (attrs.onChange as any)?.(event.target.value)
                },
                (attrs.options as any[]).map(option => h('option', { value: option.value }, option.label))
              )
            if (name === 'textarea')
              return h('textarea', {
                value: attrs.value,
                onInput: (event: any) => (attrs['onUpdate:value'] as any)?.(event.target.value),
                'aria-label': attrs['aria-label']
              })
            return h(
              name === 'button' ? 'button' : 'div',
              { ...attrs, class: name === 'modal' ? 'approval-modal' : attrs.class },
              [
                slots.default?.(),
                slots.action?.(),
                ['alert', 'empty'].includes(name) ? String(attrs.message || attrs.description || '') : '',
                name === 'modal' ? h('button', { onClick: attrs.onOk as any }, '确认命令') : null
              ]
            )
          }
        }
      })
    )
  app.component('a-tabs', Tabs)
  app.component('a-tab-pane', Tabs.TabPane)
  app.config.warnHandler = () => undefined
  app.mount(host)
  disposers.push(() => {
    app.unmount()
    host.remove()
  })
  await flush()
  return host
}
const button = (host: HTMLElement, label: string) =>
  [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === label)!
async function confirm(host: HTMLElement) {
  button(host, '通过审批').click()
  await flush()
  button(host, '确认命令').click()
  await flush()
}
describe('审批材料工作台命令与隔离', () => {
  it('NONE节点仍展示前序材料，审批只提交空变量和本次材料token', async () => {
    const host = await mount()
    expect(host.textContent).toContain('前序提交值')
    expect(host.textContent).toContain('当前节点无需填写表单')
    await confirm(host)
    expect(calls.approve).toHaveBeenCalledWith({
      id: 'task',
      reason: '同意',
      variables: {},
      materialReviewToken: 'review-proof'
    })
  })
  it('步骤页签仅显示当前材料，切换历史与目录时保留审批意见', async () => {
    const next = { ...previous, id: 'next', nodeId: 'next-node', nodeName: '合同登记', revision: 2 }
    const history = { ...next, id: 'old', state: 'HISTORY', required: false, revision: 1 }
    calls.post.mockImplementation(async (path: string, query: any) => {
      const rows = [previous, next, history]
      if (path.endsWith('/list')) return { items: rows, reviewRequired: true, reviewToken: 'review-proof' }
      const item = rows.find(item => item.id === query.materialId)!
      return { item, flowForm: { conf: '{}', fields: [], values: { name: `${item.id}的提交值` } } }
    })
    const host = await mount()
    const opinion = host.querySelector<HTMLTextAreaElement>('[aria-label="当前审批意见"]')!
    opinion.value = '已核对，请保留'
    opinion.dispatchEvent(new Event('input'))
    const step = [...host.querySelectorAll<HTMLElement>('[role="tab"]')].find(e => e.textContent?.includes('合同登记'))!
    step.click()
    await flush()
    expect(host.querySelectorAll('[data-material-id]')).toHaveLength(1)
    expect(host.textContent).toContain('next的提交值')
    expect(host.textContent).not.toContain('prior的提交值')
    const versions = host.querySelector<HTMLSelectElement>('[aria-label="提交版本"]')!
    versions.value = 'old'
    versions.dispatchEvent(new Event('change'))
    await flush()
    expect(host.textContent).toContain('old的提交值')
    host.querySelector<HTMLButtonElement>('.material-directory button')!.click()
    await flush()
    step.click()
    await flush()
    expect(host.textContent).toContain('old的提交值')
    expect(opinion.value).toBe('已核对，请保留')
    expect(calls.post.mock.calls.filter(([path]) => path.endsWith('/detail'))).toHaveLength(3)
  })
  it('本节点填写页签切出后保持表单实例，审批仍校验当前输入', async () => {
    calls.detail.mockResolvedValue(approval('FLOW_FORM'))
    const host = await mount()
    const inputs = [...host.querySelectorAll<HTMLElement>('[role="tab"]')].find(e => e.textContent === '本节点填写')!
    inputs.click()
    await flush()
    const form = host.querySelector('[aria-label="本节点表单"]')!
    host.querySelector<HTMLButtonElement>('.material-directory button')!.click()
    await flush()
    inputs.click()
    await flush()
    expect(host.querySelector('[aria-label="本节点表单"]')).toBe(form)
    await confirm(host)
    expect(calls.validate).toHaveBeenCalled()
    expect(calls.approve.mock.calls[0]![0].variables).toEqual({ current: '本次填写' })
  })
  it('必需材料失败禁用通过，但拒绝仍可提交', async () => {
    calls.post.mockImplementation(async (path: string) => {
      if (path.endsWith('/list')) return { items: [previous], reviewRequired: true, reviewToken: 'review-proof' }
      throw new Error('材料不可读')
    })
    const host = await mount()
    expect(button(host, '通过审批').disabled).toBe(true)
    expect(button(host, '拒绝').disabled).toBe(false)
    button(host, '拒绝').click()
    await flush()
    const textarea = host.querySelector<HTMLTextAreaElement>('.approval-modal textarea')!
    textarea.value = '资料不完整'
    textarea.dispatchEvent(new Event('input'))
    await flush()
    button(host, '确认命令').click()
    await flush()
    expect(calls.reject).toHaveBeenCalledWith({ id: 'task', reason: '资料不完整' })
    expect(calls.approve).not.toHaveBeenCalled()
  })
  it('继承表单没有可写字段时，不把流程全量历史变量重新提交', async () => {
    calls.detail.mockResolvedValue(approval('FLOW_FORM', 'INHERIT'))
    const host = await mount()
    await confirm(host)
    expect(calls.approve.mock.calls[0]![0].variables).toEqual({})
  })
  it('历史task链接不使用服务端返回的其他待办操作，材料查询保持历史task', async () => {
    calls.route.query.taskId = 'old-task'
    const host = await mount()
    expect(button(host, '通过审批')).toBeUndefined()
    expect(host.textContent).toContain('只读查看')
    expect(calls.post).toHaveBeenCalledWith('/nocode/flow-material/list', {
      processInstanceId: 'process',
      taskId: 'old-task'
    })
  })
  it('校验等待期间重复确认只有一个审批命令，变量只来自当前独立输入', async () => {
    calls.detail.mockResolvedValue(approval('FLOW_FORM'))
    let finish!: () => void
    calls.validate.mockImplementationOnce(() => new Promise<void>(resolve => (finish = resolve)))
    const host = await mount()
    button(host, '通过审批').click()
    await flush()
    button(host, '确认命令').click()
    button(host, '确认命令').click()
    await flush()
    expect(calls.approve).not.toHaveBeenCalled()
    finish()
    await flush()
    expect(calls.approve).toHaveBeenCalledTimes(1)
    expect(calls.approve.mock.calls[0]![0].variables).toEqual({ current: '本次填写' })
  })
  it('等待校验期间任务切换，旧任务命令不会提交', async () => {
    calls.detail.mockResolvedValue(approval('FLOW_FORM'))
    let finish!: () => void
    calls.validate.mockImplementationOnce(() => new Promise<void>(resolve => (finish = resolve)))
    const host = await mount()
    button(host, '通过审批').click()
    await flush()
    button(host, '确认命令').click()
    await flush()
    calls.route.query.taskId = 'another'
    await flush()
    finish()
    await flush()
    expect(calls.approve).not.toHaveBeenCalled()
    expect(calls.message).toHaveBeenCalledWith('当前任务已变化，请重新读取后办理')
  })
  it('审批时服务端发现材料变更，保留错误并要求刷新材料目录', async () => {
    calls.approve.mockRejectedValueOnce(new Error('材料集合已变化，请重新核对'))
    const host = await mount()
    await confirm(host)
    expect(calls.message).toHaveBeenCalledWith('材料集合已变化，请重新核对')
    expect(button(host, '通过审批').disabled).toBe(true)
    expect(host.textContent).toContain('材料集合已变化')
  })
})
