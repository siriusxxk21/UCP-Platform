// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import TaskViewEditor from '@/views/nocode/application/components/TaskViewEditor.vue'
import type { TaskViewConfig } from '@/types/nocode/application-ui'

vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: { templates: async () => [] } })
}))
vi.mock('@/components/ucp-table-page/OsDynamicSearch.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))

let app: App | undefined, host: HTMLElement
afterEach(() => {
  app?.unmount()
  host?.remove()
})
const flush = async () => {
  await Promise.resolve()
  await nextTick()
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少任务视图测试目标')
  return value
}
async function mount(initial: TaskViewConfig, readOnly = false) {
  const value = ref(JSON.stringify(initial)),
    change = vi.fn((next: string) => (value.value = next))
  app = createApp(() =>
    h(TaskViewEditor, { value: value.value, resources: [], objects: {}, readOnly, onChange: change })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AAlert', 'ASpace', 'ATag', 'ARadioGroup', 'ARadio']) app.component(name, plain)
  app.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (props, { slots }) =>
        () =>
          h('section', { 'aria-label': props.label }, [h('h3', props.label), slots.default?.()])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.())
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled', 'mode'],
      emits: ['change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: Array.isArray(props.value) ? props.value[0] || '' : props.value || '',
              disabled: props.disabled,
              onChange: (event: Event) => {
                const selected = (event.target as HTMLSelectElement).value
                emit('change', props.mode === 'multiple' ? (selected ? [selected] : []) : selected || undefined)
              }
            },
            [
              h('option', { value: '' }, '未设置'),
              ...(props.options || []).map((option: { value: string; label: string }) =>
                h('option', { value: option.value }, option.label)
              )
            ]
          )
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { value, change }
}
async function choose(label: string, value: string) {
  const select = required(host.querySelector<HTMLSelectElement>(`[aria-label="${label}"] select`))
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
}

describe('应用任务视图只配置优先级', () => {
  it('隐藏紧急筛选与历史紧急排序入口，编辑优先级仍保留历史存储字段', async () => {
    const initial: TaskViewConfig = {
      taskFilter: { statuses: ['PENDING'], urgencies: ['URGENT'], priorities: ['LOW'] },
      sort: { field: 'urgency', descending: false },
      columnKeys: ['title', 'priority']
    }
    const { value, change } = await mount(initial)
    expect(host.textContent).not.toContain('紧急')
    expect(host.querySelector('[aria-label="固定优先级"]')).not.toBeNull()
    expect(host.querySelector('[aria-label="默认排序"] select')?.querySelector('option[value="urgency"]')).toBeNull()
    expect(host.querySelector('[aria-label="默认排序"]')?.textContent).not.toContain('升序')
    expect(required(host.querySelector<HTMLSelectElement>('[aria-label="默认排序"] select')).value).toBe('')
    expect(change).not.toHaveBeenCalled()
    await choose('固定优先级', 'HIGH')
    expect(JSON.parse(value.value)).toEqual({
      ...initial,
      taskFilter: { ...initial.taskFilter, priorities: ['HIGH'] }
    })
  })

  it('可明确改用优先级排序，未修改的历史紧急筛选仍原样保存', async () => {
    const initial: TaskViewConfig = {
      taskFilter: { urgencies: ['URGENT'] },
      sort: { field: 'urgency', descending: false }
    }
    const { value } = await mount(initial)
    await choose('默认排序', 'priority')
    expect(JSON.parse(value.value)).toEqual({
      taskFilter: initial.taskFilter,
      sort: { field: 'priority', descending: true }
    })
    expect(host.querySelector('[aria-label="默认排序"]')?.textContent).toContain('升序')
  })

  it('只读视图保持控件禁用，即使收到编辑事件也不发出配置修改', async () => {
    const { change } = await mount({ taskFilter: { urgencies: ['URGENT'], priorities: ['LOW'] } }, true)
    expect(required(host.querySelector<HTMLSelectElement>('[aria-label="固定优先级"] select')).disabled).toBe(true)
    expect(host.textContent).not.toContain('紧急')
    await choose('固定优先级', 'HIGH')
    expect(change).not.toHaveBeenCalled()
  })
})
