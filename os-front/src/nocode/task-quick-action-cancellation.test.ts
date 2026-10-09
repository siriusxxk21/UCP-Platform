// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TaskQuickAction from '@/views/nocode/task-center/TaskQuickAction.vue'
import type { TaskReadiness, TaskRow } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'

const api = vi.hoisted(() => ({ readiness: vi.fn(), transition: vi.fn(), transitionRecovery: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'cancellation-test-user' } }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.formItems?.(), slots.footer?.()])
  })
}))
const task = {
  ...newTaskNode(),
  id: 'cancellation-root',
  rootId: 'cancellation-root',
  title: '装修交付',
  status: 'RUNNING',
  revision: 1,
  canExecute: true
} as TaskRow
const readiness = (): TaskReadiness => ({
  taskId: task.id,
  revision: 1,
  canComplete: false,
  canCancel: true,
  cancelBlockedReason: null,
  cancellationImpacts: [],
  checks: [
    {
      code: 'CHILDREN_CANCELLED',
      label: '确认取消后的交付范围',
      passed: false,
      reason: '补漆已取消，请确认剩余范围',
      entryKey: null
    }
  ]
})
let app: App | undefined
let host: HTMLDivElement
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少测试目标')
  return value
}
const button = (text: string) =>
  required(Array.from(host.querySelectorAll('button')).find(el => el.textContent?.trim() === text))
async function mount() {
  app = createApp(() => h(TaskQuickAction, { task, action: 'COMPLETE' }))
  const plain = defineComponent({
    props: ['label', 'message'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.label, props.message, slots.default?.()])
  })
  for (const name of ['AAlert', 'AFormItem', 'ATag', 'ASpin']) app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('textarea', {
            value: props.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value)
          })
    })
  )
  app.component(
    'ACheckbox',
    defineComponent({
      props: ['checked'],
      emits: ['update:checked'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked)
            }),
            slots.default?.()
          ])
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
async function confirmRange() {
  const checkbox = required(host.querySelector<HTMLInputElement>('input[type="checkbox"]'))
  checkbox.checked = true
  checkbox.dispatchEvent(new Event('change'))
  const note = required(host.querySelector('textarea'))
  note.value = '补漆由客户取消，剩余工作已交付'
  note.dispatchEvent(new Event('input'))
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  api.readiness.mockResolvedValue(readiness())
  api.transition.mockResolvedValue({})
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  sessionStorage.clear()
})

describe('快捷完成取消范围确认', () => {
  it('展示范围，未勾选或无说明不能提交，完成时明确发送确认', async () => {
    await mount()
    expect(host.textContent).toContain('补漆已取消')
    expect(button('确认完成').disabled).toBe(true)
    const checkbox = required(host.querySelector<HTMLInputElement>('input[type="checkbox"]'))
    checkbox.checked = true
    checkbox.dispatchEvent(new Event('change'))
    await flush()
    expect(button('确认完成').disabled).toBe(true)
    await confirmRange()
    expect(button('确认完成').disabled).toBe(false)
    button('确认完成').click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(
      expect.objectContaining({
        action: 'COMPLETE',
        confirmCancelledChildren: true,
        note: '补漆由客户取消，剩余工作已交付'
      })
    )
  })
  it('确认后重新检查范围必须重新勾选，不能沿用旧确认', async () => {
    await mount()
    await confirmRange()
    button('重新检查').click()
    await flush()
    expect(required(host.querySelector<HTMLInputElement>('input[type="checkbox"]')).checked).toBe(false)
    expect(button('确认完成').disabled).toBe(true)
    expect(required(host.querySelector('textarea')).value).toBe('补漆由客户取消，剩余工作已交付')
  })
  it('不确定结果重试保留原取消确认、备注与请求键', async () => {
    api.transition.mockRejectedValueOnce(new Error('network timeout')).mockResolvedValueOnce({})
    await mount()
    await confirmRange()
    button('确认完成').click()
    await flush()
    const original = required(api.transition.mock.calls[0])[0]
    button('确认原操作结果').click()
    await flush()
    expect(api.transition).toHaveBeenLastCalledWith(original)
    expect(original.confirmCancelledChildren).toBe(true)
  })
})
