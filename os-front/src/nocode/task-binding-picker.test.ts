// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import TaskBindingPicker from '@/views/nocode/task-center/TaskBindingPicker.vue'
import type { TaskBinding } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'

const api = vi.hoisted(() => ({ context: vi.fn(), mine: vi.fn() }))
const runtime = vi.hoisted(() => ({ mine: vi.fn(), application: vi.fn(), model: vi.fn() }))
const selector = vi.hoisted(() => ({ props: {} as Record<string, unknown>, result: [] as TaskWorkEntryConfig[] }))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => api }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime }) }))
vi.mock('@/views/nocode/task-center/TaskEntrySelector.vue', () => ({
  default: defineComponent({
    props: ['open', 'entries', 'multiple', 'applicationId'],
    emits: ['confirm', 'cancel'],
    setup: (props, { emit }) => {
      selector.props = props
      return () =>
        props.open
          ? h('div', [
              h('button', { onClick: () => emit('confirm', selector.result) }, '确认选择'),
              h('button', { onClick: () => emit('confirm', props.entries) }, '保留原选择'),
              h('button', { onClick: () => emit('cancel') }, '取消选择')
            ])
          : null
    }
  })
}))
let app: App, host: HTMLElement
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const click = async (text: string) => {
  const button = Array.from(host.querySelectorAll('button')).find(item => item.textContent === text)
  if (!button) throw new Error(`缺少按钮：${text}`)
  button.click()
  await flush()
}
const legacyContext = () => ({
  entry: { applicationName: '施工' },
  config: { formId: 'form' },
  resources: [{ id: 'form', kind: 'FORM', name: '施工工单', config: { objectId: 'object' } }],
  model: { object: { objectName: '工单', fields: [] }, permissions: { readFields: [], writeFields: [] } }
})
async function mountSelection(initial: TaskBinding | null = null, fieldsOnly = false) {
  const binding = ref<TaskBinding | null>(initial),
    applicationId = ref<string | undefined>('app'),
    readFields = vi.fn(),
    writeFields = vi.fn()
  app = createApp(() =>
    h(TaskBindingPicker, {
      modelValue: binding.value,
      applicationId: applicationId.value,
      fieldsOnly,
      onReadFields: readFields,
      onFields: writeFields,
      'onUpdate:modelValue': value => {
        binding.value = value
      }
    })
  )
  app.component('AAlert', defineComponent({ props: ['message'], setup: p => () => h('p', p.message) }))
  app.component(
    'ASpace',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.default?.())
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { binding, applicationId, readFields, writeFields }
}
beforeEach(() => {
  vi.resetAllMocks()
  selector.result = []
  runtime.mine.mockResolvedValue([])
  runtime.application.mockResolvedValue({
    application: { name: '施工' },
    definition: { resources: [{ id: 'form', kind: 'FORM', name: '施工工单', config: { objectId: 'object' } }] }
  })
  runtime.model.mockResolvedValue({
    object: { objectName: '工单', fields: [] },
    permissions: { readFields: [], writeFields: [] }
  })
  api.context.mockResolvedValue(legacyContext())
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('业务单选复用业务表单选择抽屉', () => {
  it('打开共用单选器，确认后才改变业务绑定；移除是明确操作', async () => {
    const { binding } = await mountSelection()
    expect(runtime.mine).not.toHaveBeenCalled()
    expect(runtime.application).not.toHaveBeenCalled()
    await click('选择业务视图')
    expect(selector.props).toMatchObject({ open: true, multiple: false, applicationId: 'app', entries: [] })
    selector.result = [{ binding: { applicationId: 'app', formId: 'form', entryId: null } } as TaskWorkEntryConfig]
    await click('取消选择')
    expect(binding.value).toBeNull()
    await click('选择业务视图')
    await click('确认选择')
    expect(binding.value).toEqual({ applicationId: 'app', formId: 'form', entryId: null })
    expect(selector.props.open).toBe(false)
    expect(host.textContent).toContain('施工工单')
    expect(host.textContent).toContain('数据对象：工单 · 所属应用：施工')
    await click('移除关联')
    expect(binding.value).toBeNull()
  })
  it('旧入口不在应用目录仍按原授权解析，保留三元组且不扫描业务列表', async () => {
    const original = { applicationId: 'app', formId: 'form', entryId: 'entry' }
    const { binding } = await mountSelection(original)
    expect(host.textContent).toContain('施工工单')
    expect(runtime.application).not.toHaveBeenCalled()
    expect(api.mine).not.toHaveBeenCalled()
    await click('更换业务视图')
    expect((selector.props.entries as TaskWorkEntryConfig[])[0]?.binding).toEqual(original)
    await click('保留原选择')
    expect(binding.value).toEqual(original)
    expect(api.context).toHaveBeenCalledTimes(1)
  })
  it('旧入口失败保留引用并清空字段候选，直接权限路径不作为回退', async () => {
    api.context.mockRejectedValue(new Error('当前无权访问'))
    const original = { applicationId: 'app', formId: 'form', entryId: 'entry' }
    const { binding, readFields, writeFields } = await mountSelection(original)
    expect(binding.value).toEqual(original)
    expect(host.textContent).toContain('保留原配置')
    expect(host.textContent).toContain('当前无权访问')
    expect(readFields).toHaveBeenLastCalledWith([])
    expect(writeFields).toHaveBeenLastCalledWith([])
    expect(runtime.application).not.toHaveBeenCalled()
  })
  it('旧入口被换绑时不显示新表单，不发出其字段', async () => {
    api.context.mockResolvedValue({
      ...legacyContext(),
      config: { formId: 'new' },
      resources: [{ id: 'new', kind: 'FORM', name: '新表单' }]
    })
    const original = { applicationId: 'app', formId: 'form', entryId: 'entry' }
    const { binding, readFields } = await mountSelection(original)
    expect(binding.value).toEqual(original)
    expect(host.textContent).toContain('表单已变更，原配置已保留')
    expect(host.querySelector('[aria-label="已选业务表单"]')?.textContent).not.toContain('新表单')
    expect(readFields).toHaveBeenLastCalledWith([])
  })
  it('切换应用时保留旧绑定供核对，丢弃旧入口字段迟到响应', async () => {
    let resolveEntry!: (value: unknown) => void
    api.context.mockImplementation(
      () =>
        new Promise(resolve => {
          resolveEntry = resolve
        })
    )
    const original = { applicationId: 'app', formId: 'form', entryId: 'entry' }
    const { applicationId, binding, readFields } = await mountSelection(original)
    applicationId.value = 'another-app'
    await flush()
    resolveEntry(legacyContext())
    await flush()
    expect(binding.value).toEqual(original)
    expect(host.textContent).toContain('不属于当前应用')
    expect(host.textContent).not.toContain('施工工单')
    expect(readFields).toHaveBeenLastCalledWith([])
  })
  it('切换表单或卸载后不发出旧字段响应', async () => {
    let resolveModel!: (value: unknown) => void
    runtime.model.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveModel = resolve
        })
    )
    const { binding, readFields } = await mountSelection({ applicationId: 'app', formId: 'form', entryId: null })
    binding.value = null
    await flush()
    const count = readFields.mock.calls.length
    app.unmount()
    resolveModel({
      object: { objectName: '旧对象', fields: [{ id: 'old', name: '旧字段' }] },
      permissions: { readFields: ['old'], writeFields: ['old'] }
    })
    await flush()
    expect(readFields).toHaveBeenCalledTimes(count)
  })
  it('目录失败或删除表单时不清空已有直接绑定', async () => {
    runtime.application.mockResolvedValue({ definition: { resources: [] } })
    const original = { applicationId: 'app', formId: 'form', entryId: null }
    const { binding } = await mountSelection(original)
    expect(binding.value).toEqual(original)
    expect(host.textContent).toContain('原业务表单已不可用，原配置已保留')
    expect(runtime.model).not.toHaveBeenCalled()
  })
  it('仅解析字段时不显示选择交互；分别按原入口的读写权限返回候选', async () => {
    api.context.mockResolvedValue({
      ...legacyContext(),
      model: {
        object: {
          objectName: '工单',
          fields: [
            { id: 'quantity', name: '数量' },
            { id: 'computed', name: '只读汇总' },
            { id: 'hidden', name: '隐藏字段' }
          ]
        },
        permissions: { readFields: ['quantity', 'computed'], writeFields: ['quantity'] }
      }
    })
    const { readFields, writeFields } = await mountSelection(
      { applicationId: 'app', formId: 'form', entryId: 'entry' },
      true
    )
    expect(host.querySelector('button')).toBeNull()
    expect(writeFields).toHaveBeenLastCalledWith([{ value: 'quantity', label: '数量' }])
    expect(readFields).toHaveBeenLastCalledWith([
      { value: 'quantity', label: '数量' },
      { value: 'computed', label: '只读汇总' }
    ])
    expect(runtime.mine).not.toHaveBeenCalled()
  })
})
