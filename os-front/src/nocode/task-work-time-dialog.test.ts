// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TaskWorkTimeDialog from '@/views/nocode/task-center/TaskWorkTimeDialog.vue'
import type { TaskWorkTimeContext, TaskWorkTimeEntry } from '@/types/nocode/task-work-time'

const mocks = vi.hoisted(() => ({ context: vi.fn(), adjust: vi.fn(), discard: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: { workTimeContext: mocks.context, adjustWorkTime: mocks.adjust } })
}))
vi.mock('@/nocode/task-confirmation', () => ({ useTaskConfirmation: () => ({ confirmDiscard: mocks.discard }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.formItems?.(), slots.footer?.()])
  })
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource'],
    setup:
      (props, { slots }) =>
      () =>
        h(
          'div',
          props.dataSource.map((record: TaskWorkTimeEntry) =>
            h(
              'article',
              props.columns.map((column: { key: string }) => slots.bodyCell?.({ column, record }))
            )
          )
        )
  })
}))
vi.mock('@/views/nocode/task-center/TaskWorkBudgetFields.vue', () => ({
  default: defineComponent({
    props: ['minutes'],
    setup: props => () => h('p', `任务标准总工时：${props.minutes}`)
  })
}))
let app: App, host: HTMLElement
const fixture = (): TaskWorkTimeContext => ({
  rootId: 'root',
  rootTitle: '工时验收',
  expectedRevision: 3,
  workTotalMode: 'MANUAL',
  effectiveWorkMinutes: 100,
  canAdjust: true,
  disabledReason: null,
  entries: [
    {
      taskId: 'root',
      taskTitle: '工时验收',
      config: {
        key: 'log',
        name: '施工日志',
        binding: null,
        dataMode: 'ROOT_SHARED',
        sourceNodeId: null,
        sourceEntryKey: null,
        readableFieldIds: null,
        writableFieldIds: null,
        required: false,
        allowAll: false,
        workRule: { mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 }
      }
    }
  ]
})
async function flush() {
  await Promise.resolve()
  await nextTick()
  await nextTick()
}
async function mount() {
  const close = vi.fn(),
    saved = vi.fn()
  app = createApp(() => h(TaskWorkTimeDialog, { taskId: 'root', onClose: close, onSaved: saved }))
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ASpin', 'AFormItem']) app.component(name, plain)
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup: props => () => h('p', [props.message, props.description])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      emits: ['click'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h('button', { disabled: props.disabled, onClick: () => emit('click') }, slots.default?.())
    })
  )
  const input = defineComponent({
    props: ['value', 'disabled'],
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit(
              'update:value',
              (event.target as HTMLInputElement).type === 'number'
                ? Number((event.target as HTMLInputElement).value)
                : (event.target as HTMLInputElement).value
            )
        })
  })
  app.component('ATextarea', input)
  app.component(
    'AInputNumber',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(input, { ...attrs, ...props, type: 'number', 'onUpdate:value': (v: number) => emit('update:value', v) })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { close, saved }
}
function button(text: string) {
  const target = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === text)
  if (!target) throw new Error(text)
  return target
}
async function click(text: string) {
  button(text).click()
  await flush()
}
async function input(selector: string, value: string) {
  const target = host.querySelector<HTMLInputElement>(selector)
  if (!target) throw new Error(selector)
  target.value = value
  target.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
async function fillChange() {
  await input('[aria-label="施工日志单位工时（分钟）"]', '20')
  await input('[placeholder="例如：本次设备数量增加，调整预计工作量"]', '测试工作量调整')
}
beforeEach(() => {
  mocks.context.mockResolvedValue(fixture())
  mocks.adjust.mockReset()
  mocks.adjust.mockResolvedValue(fixture())
  mocks.discard.mockResolvedValue(true)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.clearAllMocks()
})
describe('运行中调整工时', () => {
  it('先显示前后差异，确认才提交受限字段', async () => {
    const { saved } = await mount()
    expect(button('查看调整内容').disabled).toBe(true)
    await fillChange()
    await click('查看调整内容')
    expect(host.textContent).toContain('15 分钟 → 20 分钟')
    expect(mocks.adjust).not.toHaveBeenCalled()
    await click('确认调整')
    expect(mocks.adjust).toHaveBeenCalledWith(
      expect.objectContaining({
        rootId: 'root',
        expectedRevision: 3,
        reason: '测试工作量调整',
        entries: [{ taskId: 'root', entryKey: 'log', minutes: 20, plannedQuantity: 2 }]
      })
    )
    expect(saved).toHaveBeenCalledOnce()
  })
  it('无权限时不能改单位时长或提交', async () => {
    mocks.context.mockResolvedValue({ ...fixture(), canAdjust: false, disabledReason: '仅任务管理员可调整' })
    await mount()
    expect(host.textContent).toContain('仅任务管理员可调整')
    expect(host.querySelector<HTMLInputElement>('[aria-label="施工日志单位工时（分钟）"]')?.disabled).toBe(true)
    await click('查看调整内容')
    expect(mocks.adjust).not.toHaveBeenCalled()
  })
  it('失败重试复用同一请求和版本，不追加新调整', async () => {
    mocks.adjust.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(fixture())
    await mount()
    await fillChange()
    await click('查看调整内容')
    await click('确认调整')
    const first = JSON.parse(JSON.stringify(mocks.adjust.mock.calls[0]?.[0]))
    expect(host.textContent).toContain('连接中断')
    expect(host.textContent).not.toContain('返回修改')
    await click('重试确认')
    expect(mocks.adjust.mock.calls[1]?.[0]).toEqual(first)
  })
  it('取消与返回修改不会保存，原始配置不被表单修改', async () => {
    const original = fixture()
    mocks.context.mockResolvedValue(original)
    const { close } = await mount()
    await fillChange()
    await click('查看调整内容')
    await click('返回修改')
    expect(original.entries[0]?.config.workRule?.adjustmentMinutes).toBeUndefined()
    await click('取消')
    expect(close).toHaveBeenCalledOnce()
    expect(mocks.adjust).not.toHaveBeenCalled()
  })
  it('零单位工时可以停止后续计时，非整数条数仍阻止确认', async () => {
    await mount()
    await fillChange()
    await input('[aria-label="施工日志单位工时（分钟）"]', '0')
    expect(button('查看调整内容').disabled).toBe(false)
    await input('[aria-label="施工日志单位工时（分钟）"]', '20')
    await input('[aria-label="施工日志预计工作量"]', '1.5')
    expect(host.textContent).toContain('整数')
    expect(button('查看调整内容').disabled).toBe(true)
  })
})
