import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive } from 'vue'
import BusinessTaskConfig from '@/views/bpm/model/form/BusinessTaskConfig.vue'
import { bindBusinessTask, businessTaskTemplate, inspectBusinessTasks } from '@/nocode/flow-task-binding'

const calls = vi.hoisted(() => ({ application: vi.fn(), changed: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    runtime: {
      application: calls.application,
      mine: async () => [
        { id: 'a', name: '应用 A' },
        { id: 'b', name: '应用 B' }
      ]
    }
  })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
const config = {
  resource: {
    applicationId: 'a',
    applicationVersion: 7,
    applicationChecksum: 'fixed',
    resourceId: 'old-form',
    resourceKind: 'FORM' as const
  },
  objectId: 'company',
  operation: 'CREATE' as const,
  future: { keep: true }
}
const dispose: Array<() => void> = []
const release = (versionNo: number) => ({
  application: { id: 'a', name: '应用 A' },
  versionNo,
  checksum: `v${versionNo}`,
  definition: { resources: [{ id: 'new-form', name: '新表单', kind: 'FORM', config: { objectId: 'company' } }] }
})
async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
function button(text: string) {
  return Array.from(document.querySelectorAll('button')).find(b => b.textContent?.includes(text))!
}
async function select(label: string, value: string) {
  const el = document.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
  el.value = value
  el.dispatchEvent(new Event('change'))
  await settle()
}
async function mount() {
  const props = reactive({
    modelValue: bindBusinessTask(businessTaskTemplate('task_test', '业务流程'), 'business_work', config),
    processKey: 'task_test',
    processName: '业务流程'
  })
  const app = createApp({
    render: () =>
      h(BusinessTaskConfig, {
        ...props,
        'onUpdate:modelValue': value => {
          props.modelValue = value
          calls.changed(value)
        }
      })
  })
  const wrap = defineComponent({
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    }
  })
  for (const name of ['a-card', 'a-space', 'a-descriptions', 'a-descriptions-item']) app.component(name, wrap)
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup(props) {
        return () => h('div', props.message)
      }
    })
  )
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled'],
      setup(props, { slots }) {
        return () => h('button', { disabled: props.disabled }, slots.default?.())
      }
    })
  )
  app.component(
    'a-select',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup(props, { emit }) {
        return () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (e: Event) => {
                const value = (e.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
              }
            },
            [
              h('option', { value: '' }, '请选择'),
              ...(props.options || []).map((o: any) => h('option', { value: o.value }, o.label))
            ]
          )
      }
    })
  )
  const host = document.createElement('div')
  document.body.appendChild(host)
  app.mount(host)
  dispose.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
  return props
}
beforeEach(() => {
  vi.resetAllMocks()
  calls.application.mockResolvedValue(release(9))
})
afterEach(() => dispose.splice(0).forEach(fn => fn()))

it('打开旧绑定仅回显固定版本，不请求或自动改绑当前发布版', async () => {
  await mount()
  expect(document.body.textContent).toContain('V7')
  expect(document.body.textContent).toContain('old-form')
  expect(calls.application).not.toHaveBeenCalled()
  expect(calls.changed).not.toHaveBeenCalled()
})
it('选择候选表单不写模型，明确应用新绑定后保留其他扩展配置', async () => {
  const props = await mount()
  button('更换绑定表单').click()
  await settle()
  await select('业务应用', 'a')
  await select('业务表单', 'new-form')
  expect(calls.changed).not.toHaveBeenCalled()
  button('应用新绑定').click()
  await settle()
  expect(calls.changed).toHaveBeenCalledOnce()
  const saved = inspectBusinessTasks(props.modelValue)[0]?.configuration as any
  expect(saved.resource.applicationVersion).toBe(9)
  expect(saved.future).toEqual({ keep: true })
})
it('应用 A→B→A 的旧响应不能覆盖最后选择的发布版', async () => {
  const pending: Array<(data: any) => void> = []
  calls.application.mockImplementation(() => new Promise(resolve => pending.push(resolve)))
  const props = await mount()
  button('更换绑定表单').click()
  await settle()
  await select('业务应用', 'a')
  await select('业务应用', 'b')
  await select('业务应用', 'a')
  pending[2]!(release(12))
  await settle()
  pending[0]!(release(9))
  pending[1]!(release(10))
  await settle()
  await select('业务表单', 'new-form')
  button('应用新绑定').click()
  await settle()
  expect(inspectBusinessTasks(props.modelValue)[0]?.configuration?.resource.applicationVersion).toBe(12)
})
