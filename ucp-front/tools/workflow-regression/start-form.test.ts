import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import StartForm from '@/views/bpm/processInstance/create/modules/form.vue'

const calls = vi.hoisted(() => ({ create: vi.fn(), approval: vi.fn(), definition: vi.fn(), push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: calls.push }) }))
vi.mock('@/api/bpm/definition', () => ({ getProcessDefinition: calls.definition }))
vi.mock('@/api/bpm/processInstance', () => ({ createProcessInstance: calls.create, getApprovalDetail: calls.approval }))
vi.mock('ant-design-vue', () => ({ message: { error: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
vi.mock('@form-create/ant-design-vue', () => ({ default: { render: () => h('div', '表单') } }))
vi.mock('@/views/bpm/components/simple-process-design', () => ({ SimpleProcessViewer: { render: () => null } }))
vi.mock('@/views/bpm/components/bpmn-process-designer/package', () => ({ MyProcessViewer: { render: () => null } }))
vi.mock('@/components/form-create', () => ({
  decodeFields: (fields: string[] = []) => fields.map(value => JSON.parse(value)),
  setConfAndFields2: (form: any, _: any, fields: string[] = [], values: any = {}) => {
    form.value = { rule: fields.map(value => JSON.parse(value)), option: {}, value: values }
  }
}))
const disposers: Array<() => void> = []
const tick = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
beforeEach(() => {
  vi.resetAllMocks()
  calls.approval.mockResolvedValue({ activityNodes: [] })
  calls.definition.mockResolvedValue({ id: 'definition', bpmnXml: '' })
  calls.create.mockResolvedValue('process')
})
afterEach(() => disposers.splice(0).forEach(dispose => dispose()))
function mount(formType = 0) {
  const host = document.createElement('div')
  document.body.append(host)
  const definition = { id: 'definition', name: '发起验收', formType, formFields: ['{"field":"old"}'] }
  const app = createApp(StartForm, { selectProcessDefinition: definition as any })
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.title?.(), slots.extra?.(), slots.default?.(), slots.action?.()])
  })
  for (const name of [
    'a-card',
    'a-space',
    'a-tabs',
    'a-tab-pane',
    'a-row',
    'a-col',
    'a-form',
    'a-form-item',
    'a-spin',
    'a-divider',
    'a-timeline',
    'a-timeline-item',
    'a-empty',
    'a-select'
  ])
    app.component(name, wrapper)
  app.component(
    'a-result',
    defineComponent({ props: ['title', 'subTitle'], setup: p => () => h('div', `${p.title} ${p.subTitle}`) })
  )
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [p.message, slots.action?.()])
    })
  )
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled || p.loading }, slots.default?.())
    })
  )
  const vm = app.mount(host) as unknown as { initProcessInfo: (row: any, variables?: any) => Promise<void> }
  disposers.push(() => {
    app.unmount()
    host.remove()
  })
  const submit = () => [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === '发起')!
  return { host, definition, vm, submit }
}
describe('可选发起表单', () => {
  it('无表单不挂载填写器，也不携带上一次流程的字段即可发起', async () => {
    const { host, definition, vm, submit } = mount()
    await vm.initProcessInfo(definition, { old: '旧值' })
    await tick()
    expect(host.textContent).toContain('此流程无需填写发起表单')
    expect(submit().disabled).toBe(false)
    submit().click()
    await tick()
    expect(calls.create).toHaveBeenCalledWith({
      processDefinitionId: 'definition',
      variables: {},
      startUserSelectAssignees: {}
    })
    expect(calls.push).toHaveBeenCalledWith('/bpm/instance')
  })
  it('无表单仍要求配置的发起人自选办理人', async () => {
    calls.approval.mockResolvedValue({
      activityNodes: [{ id: 'approval', name: '经理审批', candidateStrategy: 35, candidateUsers: [] }]
    })
    const { definition, vm, submit } = mount()
    await vm.initProcessInfo(definition)
    await tick()
    submit().click()
    await tick()
    expect(calls.create).not.toHaveBeenCalled()
  })
  it('审批预览失败时阻止发起，重读成功后恢复', async () => {
    calls.approval.mockRejectedValueOnce(new Error('审批预览暂不可用'))
    const { host, definition, vm, submit } = mount()
    await vm.initProcessInfo(definition)
    await tick()
    expect(host.textContent).toContain('审批预览暂不可用')
    expect(submit().disabled).toBe(true)
    submit().click()
    expect(calls.create).not.toHaveBeenCalled()
    await vm.initProcessInfo(definition)
    await tick()
    expect(submit().disabled).toBe(false)
  })
  it('发起中的重复点击只提交一次', async () => {
    let resolve!: (value: string) => void
    calls.create.mockReturnValue(
      new Promise(r => {
        resolve = r
      })
    )
    const { definition, vm, submit } = mount()
    await vm.initProcessInfo(definition)
    await tick()
    submit().click()
    submit().click()
    await tick()
    expect(calls.create).toHaveBeenCalledTimes(1)
    resolve('process')
    await tick()
  })
})
