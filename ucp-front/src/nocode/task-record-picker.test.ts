// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import TaskRecordPicker from '@/views/nocode/task-center/TaskRecordPicker.vue'
import type { TaskBinding, TaskRecordRef } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  mine: vi.fn(),
  model: vi.fn(),
  page: vi.fn(),
  preview: vi.fn(),
  context: vi.fn(),
  entryPage: vi.fn(),
  application: vi.fn()
}))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/api/nocode/task-entry', () => ({
  createTaskEntryApi: () => ({ context: api.context, page: api.entryPage })
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    runtime: { mine: api.mine, model: api.model, page: api.page, application: api.application },
    taskCenter: { formPreview: api.preview }
  })
}))
let app: App, host: HTMLElement
const model = (id: string) => ({ object: { objectId: id, objectName: `对象${id}`, titleFieldId: 'title' } })
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount(
  binding: ReturnType<typeof ref<TaskBinding | null>>,
  record = ref<TaskRecordRef | null>(null),
  applicationId = ref<string | undefined>()
) {
  app = createApp({
    render: () =>
      h(TaskRecordPicker, {
        binding: binding.value,
        applicationId: applicationId.value,
        modelValue: record.value,
        'onUpdate:modelValue': (v: TaskRecordRef | null) => {
          record.value = v
        }
      })
  })
  app.component(
    'ASelect',
    defineComponent({
      props: ['options', 'value', 'disabled', 'placeholder'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              disabled: p.disabled,
              value: p.value,
              'aria-label': p.placeholder,
              onChange: (e: Event) => {
                const value = (e.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
              }
            },
            [
              h('option', { value: '' }, '请选择'),
              ...(p.options || []).map((o: { value: string; label: string }) =>
                h('option', { value: o.value }, o.label)
              )
            ]
          )
    })
  )
  app.component(
    'AAlert',
    defineComponent({ props: ['message'], setup: p => () => h('p', { role: 'alert' }, p.message) })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  return record
}
beforeEach(() => {
  vi.resetAllMocks()
  api.mine.mockResolvedValue([])
  api.page.mockResolvedValue({ list: [], total: 0 })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('任务关联已有业务记录', () => {
  it('已选所属应用时直接选业务对象，切换应用丢弃旧对象候选与记录', async () => {
    let resolveOld!: (value: unknown) => void
    api.application.mockImplementation(id =>
      id === 'old'
        ? new Promise(resolve => {
            resolveOld = resolve
          })
        : Promise.resolve({ definition: { objects: [{ objectId: 'new-object' }] } })
    )
    api.model.mockImplementation(async (_app, id) => model(id))
    const applicationId = ref<string | undefined>('old')
    const record = mount(
      ref(null),
      ref({ applicationId: 'old', objectId: 'old-object', recordId: 'r1', label: '原记录' }),
      applicationId
    )
    await flush()
    applicationId.value = 'new'
    await flush()
    resolveOld({ definition: { objects: [{ objectId: 'old-object' }] } })
    await flush()
    expect(record.value).toBeNull()
    expect(api.mine).not.toHaveBeenCalled()
    expect(host.querySelector('select[aria-label="所属应用"]')).toBeNull()
    expect(host.textContent).toContain('对象new-object')
    expect(host.textContent).not.toContain('对象old-object')
  })
  it('沿用已选表单的对象，不要求重选应用和对象，零值标题正常展示', async () => {
    api.preview.mockResolvedValue({ model: model('o1') })
    api.page.mockResolvedValue({ list: [{ id: 'r1', values: { title: 0 } }], total: 1 })
    const record = mount(ref({ applicationId: 'app1', formId: 'form', entryId: null }))
    await flush()
    expect(api.mine).not.toHaveBeenCalled()
    expect(api.page).toHaveBeenCalledWith(expect.objectContaining({ applicationId: 'app1', objectId: 'o1' }))
    expect(host.querySelectorAll('select')).toHaveLength(1)
    expect(host.textContent).toContain('对象o1')
    expect(host.querySelector('option[value="r1"]')?.textContent).toBe('0')
    const select = host.querySelector('select')!
    select.value = 'r1'
    select.dispatchEvent(new Event('change'))
    expect(record.value).toEqual({ applicationId: 'app1', objectId: 'o1', recordId: 'r1', label: '0' })
  })
  it('入口候选使用入口授权查询，不回退到应用全表', async () => {
    api.context.mockResolvedValue({ config: { mode: 'LIST' }, entry: { version: 3 }, model: model('o2') })
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    mount(ref({ applicationId: 'app2', entryId: 'entry', formId: 'form' }))
    await flush()
    expect(api.entryPage).toHaveBeenCalledWith(
      { applicationId: 'app2', entryId: 'entry', version: 3 },
      expect.objectContaining({ objectId: 'o2' })
    )
    expect(api.page).not.toHaveBeenCalled()
    expect(api.model).not.toHaveBeenCalled()
  })
  it('仅填写新记录的入口不能用应用全表代替已有记录候选', async () => {
    api.context.mockResolvedValue({ config: { mode: 'FORM' }, entry: { version: 3 }, model: model('o2') })
    mount(ref({ applicationId: 'app2', entryId: 'entry', formId: 'form' }))
    await flush()
    expect(host.textContent).toContain('仅支持填写新记录')
    expect(host.querySelector('select')?.disabled).toBe(true)
    expect(api.page).not.toHaveBeenCalled()
    expect(api.entryPage).not.toHaveBeenCalled()
  })
  it('切换业务定义后清除旧关联，晚到的旧响应不能污染新候选', async () => {
    let resolveOld!: (value: unknown) => void
    api.preview.mockImplementation((binding: TaskBinding) =>
      binding.applicationId === 'old'
        ? new Promise(resolve => {
            resolveOld = resolve
          })
        : Promise.resolve({ model: model('new-object') })
    )
    const binding = ref<TaskBinding | null>({ applicationId: 'old', formId: 'f', entryId: null })
    const record = mount(
      binding,
      ref({ applicationId: 'old', objectId: 'old-object', recordId: 'old-row', label: '旧记录' })
    )
    await flush()
    binding.value = { applicationId: 'new', formId: 'f', entryId: null }
    await flush()
    resolveOld({ model: model('old-object') })
    await flush()
    expect(record.value).toBe(null)
    expect(host.textContent).toContain('对象new-object')
    expect(host.textContent).not.toContain('对象old-object')
    expect(api.page).toHaveBeenCalledTimes(1)
    expect(api.page).toHaveBeenCalledWith(expect.objectContaining({ applicationId: 'new', objectId: 'new-object' }))
  })
})
