import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive } from 'vue'
import Editor from '@/views/bpm/model/form/WorkflowTaskNodeEditor.vue'
import { newWorkflowTaskSetting } from '@/nocode/workflow-task-node'
import { newTaskNode } from '@/nocode/task-center'
import type { WorkflowTaskNodeSetting } from '@/types/nocode/workflow-task-node'

const api = vi.hoisted(() => ({
  members: vi.fn(),
  templates: vi.fn(),
  templateVersion: vi.fn(),
  templateVersions: vi.fn(),
  create: vi.fn(),
  confirm: vi.fn(),
  getForm: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/nocode/task-confirmation', () => ({ useTaskConfirmation: () => ({ confirm: api.confirm }) }))
vi.mock('@/api/bpm/form', () => ({ getForm: api.getForm }))
vi.mock('@/views/nocode/task-center/TaskNodeEditor.vue', () => ({
  default: {
    props: ['readonly', 'dataReadonly', 'templateInstance'],
    template: '<div data-testid="reused-task-editor" :data-readonly="String(readonly)" :data-business-readonly="String(dataReadonly)" :data-template-instance="String(templateInstance)" />'
  }
}))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: {
    props: ['readonly', 'dataReadonly', 'section'],
    template: '<div data-testid="reused-task-fields" :data-readonly="String(readonly)" :data-business-readonly="String(dataReadonly)" :data-section="section" />'
  }
}))
vi.mock('@/views/bpm/model/form/WorkflowTaskPersonField.vue', () => ({ default: { template: '<div />' } }))
const disposers: Array<() => void> = []
beforeEach(() => {
  api.members.mockResolvedValue([])
  api.templates.mockResolvedValue([
    { id: 'one', name: '装修模板', publishedVersion: 2 },
    { id: 'draft', name: '草稿', publishedVersion: null }
  ])
  api.templateVersions.mockResolvedValue([
    { version: 1, primary: false },
    { version: 2, primary: true }
  ])
  api.templateVersion.mockImplementation(async (id, version) => ({
    id,
    version: version || 2,
    name: '装修模板',
    task: { ...newTaskNode(), title: `装修 V${version || 2}` },
    nodes: []
  }))
  api.confirm.mockResolvedValue(true)
})
afterEach(() => {
  disposers.splice(0).forEach(dispose => dispose())
  vi.clearAllMocks()
})
async function settle() {
  for (let i = 0; i < 4; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(setting = newWorkflowTaskSetting()) {
  const state = reactive({ setting }),
    host = document.createElement('div'),
    busy = vi.fn()
  document.body.append(host)
  const app = createApp(() =>
    h(Editor, {
      modelValue: state.setting,
      'onUpdate:modelValue': (value: WorkflowTaskNodeSetting) => (state.setting = value),
      onBusy: busy
    })
  )
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['a-form-item', 'a-tabs', 'a-tab-pane', 'a-radio-group', 'a-radio-button'])
    app.component(name, wrapper)
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', [p.message, slots.action?.()])
    })
  )
  app.component(
    'a-button',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component(
    'a-select',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['change'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: p.value,
              disabled: p.disabled,
              onChange: (e: Event) => emit('change', (e.target as HTMLSelectElement).value)
            },
            [
              h('option', { value: '' }, '选择'),
              ...(p.options || []).map((o: { value: string | number; label: string }) =>
                h('option', { value: o.value }, o.label)
              )
            ]
          )
    })
  )
  app.mount(host)
  const dispose = () => {
    app.unmount()
    host.remove()
  }
  disposers.push(dispose)
  await settle()
  return { host, state, busy, dispose }
}
async function select(host: HTMLElement, label: string, value: string) {
  const control = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
  control.value = value
  control.dispatchEvent(new Event('change'))
  await settle()
}
describe('流程任务节点配置交互', () => {
  it('自定义任务只显示一个统一业务关联配置，保持可编辑', async () => {
    const { host } = await mount()
    expect(host.querySelector('[tab="过程反馈"]')).toBeNull()
    const sections = host.querySelectorAll('[data-testid="reused-task-fields"]')
    expect(sections).toHaveLength(1)
    expect(sections[0]?.getAttribute('data-section')).toBe('business')
    expect(sections[0]?.getAttribute('data-business-readonly')).toBe('false')
    expect(host.querySelector('[data-testid="reused-task-editor"]')?.getAttribute('data-business-readonly')).toBe('false')
  })
  it('从模板配置锁定业务资源与授权，人员排期编排仍可调整', async () => {
    const setting = newWorkflowTaskSetting()
    setting.source = 'TEMPLATE'
    const { host } = await mount(setting)
    await select(host, '任务模板', 'one')
    const editor = host.querySelector('[data-testid="reused-task-editor"]')!
    expect(editor.getAttribute('data-readonly')).toBe('false')
    expect(editor.getAttribute('data-business-readonly')).toBe('true')
    expect(editor.getAttribute('data-template-instance')).toBe('true')
    expect(host.querySelector('[data-testid="reused-task-fields"]')?.getAttribute('data-business-readonly')).toBe('true')
    expect(host.textContent).toContain('模板的业务资源与数据授权已固定')
  })
  it('模板选择默认读取主版本，版本下拉允许固定旧版本；配置全程不创建实例', async () => {
    const setting = newWorkflowTaskSetting()
    setting.source = 'TEMPLATE'
    const { host, state } = await mount(setting)
    expect(host.querySelector('select[aria-label="任务模板"]')?.textContent).not.toContain('草稿')
    await select(host, '任务模板', 'one')
    expect(api.templateVersion).toHaveBeenLastCalledWith('one', undefined)
    expect(state.setting.templateVersion).toBe(2)
    expect(host.querySelector('select[aria-label="模板版本"]')?.textContent).toContain('主版本')
    await select(host, '模板版本', '1')
    expect(api.templateVersion).toHaveBeenLastCalledWith('one', 1)
    expect(state.setting.task.title).toBe('装修 V1')
    expect(api.create).not.toHaveBeenCalled()
  })
  it('版本请求失败不会把旧快照混成新版本，也不丢用户配置', async () => {
    const setting = newWorkflowTaskSetting()
    setting.source = 'TEMPLATE'
    const { host, state } = await mount(setting)
    await select(host, '任务模板', 'one')
    state.setting.task.title = '本流程专属装修'
    api.templateVersion.mockRejectedValueOnce(new Error('网络中断'))
    await select(host, '模板版本', '1')
    expect(state.setting.templateVersion).toBe(2)
    expect(state.setting.task.title).toBe('本流程专属装修')
    expect(host.textContent).toContain('原配置未改动')
  })
  it('取消替换不触发版本请求', async () => {
    const setting = newWorkflowTaskSetting()
    setting.source = 'TEMPLATE'
    setting.task.title = '已有任务'
    const { host, state } = await mount(setting)
    api.confirm.mockResolvedValueOnce(false)
    await select(host, '任务模板', 'one')
    expect(api.templateVersion).not.toHaveBeenCalled()
    expect(state.setting.task.title).toBe('已有任务')
  })
  it('加载模板期间锁定控件与外层保存，组件离开后不应用迟到响应', async () => {
    const setting = newWorkflowTaskSetting()
    setting.source = 'TEMPLATE'
    const { host, state, busy, dispose } = await mount(setting)
    let resolve!: (value: unknown) => void
    api.templateVersion.mockReturnValueOnce(new Promise(r => (resolve = r)))
    await select(host, '任务模板', 'one')
    expect(busy).toHaveBeenLastCalledWith(true)
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="任务模板"]')?.disabled).toBe(true)
    dispose()
    disposers.pop()
    resolve({ id: 'one', version: 2, name: '模板', task: { ...newTaskNode(), title: '迟到结果' }, nodes: [] })
    await settle()
    expect(state.setting.templateVersion).toBeUndefined()
    expect(busy).toHaveBeenLastCalledWith(false)
  })
  it('删除子任务同时清理仅属于该子任务的动态人员来源', async () => {
    const setting = newWorkflowTaskSetting(),
      child = newTaskNode(setting.task.id)
    setting.nodes = [child]
    setting.people = [
      { nodeId: setting.task.id, role: 'ASSIGNEE', source: 'INITIATOR' },
      { nodeId: child.id, role: 'ASSIGNEE', source: 'FORM_FIELD', field: 'worker' }
    ]
    const { state } = await mount(setting)
    state.setting.nodes = []
    await settle()
    expect(state.setting.people).toEqual([{ nodeId: setting.task.id, role: 'ASSIGNEE', source: 'INITIATOR' }])
  })
})
