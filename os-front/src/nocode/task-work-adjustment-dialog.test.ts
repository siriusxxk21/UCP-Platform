// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, provide, ref, type App } from 'vue'
import TaskWorkAdjustmentDialog from '@/views/nocode/task-center/TaskWorkAdjustmentDialog.vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'

vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open'],
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', [slots.formItems?.(), slots.footer?.()]) : null
  })
}))

let app: App, host: HTMLElement
const flush = async () => {
  await nextTick()
  await nextTick()
}
function entry(adjustmentMinutes?: number): TaskWorkEntryConfig {
  return {
    key: 'wifi',
    name: 'Wi-Fi 配置',
    binding: null,
    dataMode: 'ROOT_SHARED',
    sourceNodeId: null,
    sourceEntryKey: null,
    readableFieldIds: null,
    writableFieldIds: null,
    required: false,
    allowAll: false,
    workRule: { mode: 'RECORD_ONCE', minutes: 15, adjustmentMinutes }
  }
}
async function mount(original = entry()) {
  const selected = ref<TaskWorkEntryConfig | null>(original),
    save = vi.fn(),
    cancel = vi.fn()
  app = createApp(() => h(TaskWorkAdjustmentDialog, { entry: selected.value, onSave: save, onCancel: cancel }))
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AForm', 'AFormItem', 'ASpace']) app.component(name, plain)
  app.component(
    'AAlert',
    defineComponent({ props: ['message'], setup: props => () => h('p', { role: 'alert' }, props.message) })
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
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup(props, { slots, emit }) {
        provide('adjust-radio', { props, update: (value: string) => emit('update:value', value) })
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup(props, { slots }) {
        const group = inject<{ props: { value: string }; update: (value: string) => void }>('adjust-radio')
        if (!group) throw new Error('缺少调整方式选择组')
        return () =>
          h(
            'button',
            {
              'data-mode': props.value,
              'aria-pressed': group.props.value === props.value,
              onClick: () => group.update(props.value)
            },
            slots.default?.()
          )
      }
    })
  )
  app.component(
    'AInputNumber',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            type: 'number',
            value: props.value,
            onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value))
          })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { selected, save, cancel }
}
async function click(text: string) {
  const button = Array.from(host.querySelectorAll('button')).find(item => item.textContent === text)
  if (!button) throw new Error(`缺少按钮：${text}`)
  button.click()
  await flush()
}
async function minutes(value: number) {
  const input = host.querySelector<HTMLInputElement>('[aria-label="本次调整时长（分钟）"]')
  if (!input) throw new Error('缺少分钟输入')
  input.value = String(value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})

it('默认沿用模板，无需逐项配置；增加只提交差额且显示最终工时', async () => {
  const original = entry(),
    { save } = await mount(original)
  expect(host.querySelector('input')).toBeNull()
  expect(host.querySelector('.work-adjustment__result')?.textContent).toContain('15 分钟 / 条')
  await click('＋ 增加')
  await minutes(5)
  expect(host.querySelector('.work-adjustment__baseline')?.textContent).toContain('15 分钟 / 条')
  expect(host.querySelector('.work-adjustment__result')?.textContent).toContain('20 分钟 / 条')
  expect(original.workRule?.adjustmentMinutes).toBeUndefined()
  await click('保存调整')
  expect(save).toHaveBeenCalledWith(5)
})

it('减少到零时本次不计工时，恢复沿用会清除本次调整', async () => {
  const { save } = await mount(entry(-5))
  expect(host.querySelector('.work-adjustment__result')?.textContent).toContain('10 分钟 / 条')
  await minutes(15)
  expect(host.querySelector('[role="alert"]')).toBeNull()
  await click('保存调整')
  expect(save).toHaveBeenCalledWith(-15)
  await click('沿用模板')
  expect(host.querySelector('[role="alert"]')).toBeNull()
  expect(host.querySelector('input')).toBeNull()
  await click('保存调整')
  expect(save).toHaveBeenCalledWith(0)
})

it('取消不改原数据，换办理项时不残留前一项的调整', async () => {
  const original = entry(),
    { selected, save, cancel } = await mount(original)
  await click('＋ 增加')
  await minutes(10)
  await click('取消')
  expect(cancel).toHaveBeenCalledOnce()
  expect(save).not.toHaveBeenCalled()
  expect(original.workRule?.adjustmentMinutes).toBeUndefined()
  selected.value = { ...entry(), key: 'room', name: '入住登记' }
  await flush()
  expect(host.querySelector('input')).toBeNull()
  expect(host.querySelector('.work-adjustment__result')?.textContent).toContain('15 分钟 / 条')
})
