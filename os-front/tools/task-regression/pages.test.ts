import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import Todo from '@/views/bpm/task/todo/index.vue'
import Done from '@/views/bpm/task/done/index.vue'
import Manager from '@/views/bpm/task/manager/index.vue'
import Copy from '@/views/bpm/task/copy/index.vue'

const calls = vi.hoisted(() => ({
  todo: vi.fn(),
  done: vi.fn(),
  manager: vi.fn(),
  copy: vi.fn(),
  withdraw: vi.fn(),
  push: vi.fn()
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: calls.push }) }))
vi.mock('@/api/bpm/task', () => ({
  getTaskTodoPage: calls.todo,
  getTaskDonePage: calls.done,
  getTaskManagerPage: calls.manager,
  withdrawTask: calls.withdraw
}))
vi.mock('@/api/bpm/processInstance', () => ({ getProcessInstanceCopyPage: calls.copy }))
vi.mock('@/api/bpm/category', () => ({ getCategorySimpleList: async () => [{ code: 'work', name: '工作' }] }))
vi.mock('@/api/bpm/definition', () => ({
  getSimpleProcessDefinitionList: async () => [{ key: 'demo', name: '样例流程' }]
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/views/bpm/task/FlowWorkDrawer.vue', () => ({
  default: defineComponent({
    props: ['open', 'initialState'],
    setup(props) {
      return () => (props.open ? h('aside', { 'data-work-state': props.initialState }, '流程工作记录') : null)
    }
  })
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'title', 'columnSettingsKey', 'resizable', 'showColumnSettings'],
    emits: ['search'],
    setup(props, { emit, slots }) {
      return () =>
        h('section', { 'data-settings': props.columnSettingsKey }, [
          slots.search?.({ triggerSearch: () => emit('search') }),
          slots.toolbar?.(),
          ...(props.dataSource || []).flatMap((record: unknown) =>
            (props.columns || []).map((column: unknown) => slots.bodyCell?.({ column, record }))
          )
        ])
    }
  })
}))
const dispose: Array<() => void> = []
async function settle() {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(text: string) {
  return Array.from(document.querySelectorAll('button')).find(b => b.textContent?.includes(text))!
}
async function click(text: string) {
  button(text).click()
  await settle()
}
async function mount(view: any) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(view)
  const wrapper = defineComponent({
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    }
  })
  for (const name of ['a-form', 'a-space', 'a-tag']) app.component(name, wrapper)
  app.component(
    'a-form-item',
    defineComponent({
      props: ['label'],
      setup(props, { slots }) {
        return () => h('label', [props.label, slots.default?.()])
      }
    })
  )
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled', 'loading'],
      setup(props, { slots }) {
        return () => h('button', { disabled: props.disabled, 'aria-busy': props.loading }, slots.default?.())
      }
    })
  )
  app.component(
    'a-input',
    defineComponent({
      props: ['value'],
      emits: ['update:value', 'pressEnter'],
      setup(props, { emit }) {
        return () =>
          h('input', {
            value: props.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value),
            onKeydown: (e: KeyboardEvent) => {
              if (e.key === 'Enter') emit('pressEnter')
            }
          })
      }
    })
  )
  app.component(
    'a-select',
    defineComponent({
      props: ['value', 'options', 'placeholder'],
      emits: ['update:value'],
      setup(props, { emit }) {
        return () =>
          h(
            'select',
            {
              value: props.value ?? '',
              'aria-label': props.placeholder,
              onChange: (e: Event) => {
                const value = (e.target as HTMLSelectElement).value
                emit('update:value', props.options?.find((o: any) => String(o.value) === value)?.value)
              }
            },
            [
              h('option', { value: '' }, '请选择'),
              ...props.options.map((o: any) => h('option', { value: o.value }, o.label))
            ]
          )
      }
    })
  )
  app.component('a-range-picker', wrapper)
  app.component(
    'a-popconfirm',
    defineComponent({
      props: ['disabled'],
      emits: ['confirm'],
      setup(props, { emit, slots }) {
        return () =>
          h(
            'div',
            {
              onClick: () => {
                if (!props.disabled) emit('confirm')
              }
            },
            slots.default?.()
          )
      }
    })
  )
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup(props, { slots }) {
        return () => h('aside', { role: 'alert' }, [props.message, slots.action?.()])
      }
    })
  )
  app.mount(host)
  dispose.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
}
async function select(label: string, value: string) {
  const el = document.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
  el.value = value
  el.dispatchEvent(new Event('change'))
  await settle()
}
beforeEach(() => {
  vi.clearAllMocks()
  const row = {
    id: 'task-1',
    name: '登记',
    processInstanceId: 'process-1',
    processInstance: { id: 'process-1', name: '登记流程' },
    status: 2,
    activityId: 'node-1',
    createTime: 1789010000000
  }
  for (const fetch of [calls.todo, calls.done, calls.manager, calls.copy])
    fetch.mockResolvedValue({ list: [row], total: 1 })
  calls.withdraw.mockResolvedValue(undefined)
})
afterEach(() => dispose.splice(0).forEach(fn => fn()))

describe('正式任务页面接线', () => {
  it.each([
    [Todo, '我的流程草稿', 'DRAFT'],
    [Done, '我的流程材料', 'SUBMITTED']
  ] as const)('%s 从对应入口打开本人工作记录', async (view, label, state) => {
    await mount(view)
    expect(document.querySelector('[data-work-state]')).toBeNull()
    await click(label)
    expect(document.querySelector('[data-work-state]')?.getAttribute('data-work-state')).toBe(state)
  })
  it.each([
    [Todo, calls.todo, 'todo'],
    [Done, calls.done, 'done'],
    [Manager, calls.manager, 'manager'],
    [Copy, calls.copy, 'copy']
  ] as const)('%s 使用独立列设置并提供真实刷新', async (view, fetch, key) => {
    await mount(view)
    expect(document.querySelector('section')?.getAttribute('data-settings')).toBe(`bpm-task-${key}`)
    expect(fetch).toHaveBeenCalledTimes(1)
    await click('刷新')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('待办不显示无效流程状态；回车查询和办理保持任务身份', async () => {
    await mount(Todo)
    expect(document.body.textContent).not.toContain('流程状态')
    expect(document.body.textContent).toContain('任务创建时间')
    const input = document.querySelector('input')!
    input.value = ' 登记 '
    input.dispatchEvent(new Event('input'))
    await settle()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    await settle()
    expect(calls.todo.mock.lastCall?.[0]).toMatchObject({ name: '登记', pageNo: 1 })
    await click('办理')
    expect(calls.push).toHaveBeenCalledWith({
      name: 'TaskInstanceDetail',
      query: { id: 'process-1', taskId: 'task-1' }
    })
  })
  it.each([
    [Manager, calls.manager],
    [Done, calls.done]
  ] as const)('%s 提交所属流程、分类和办理状态筛选', async (view, fetch) => {
    await mount(view)
    await select('请选择流程定义', 'demo')
    await select('请选择流程分类', 'work')
    await select('请选择办理状态', '2')
    await click('查询')
    expect(fetch.mock.lastCall?.[0]).toMatchObject({
      processDefinitionKey: 'demo',
      category: 'work',
      status: 2,
      pageNo: 1
    })
    await click('历史')
    expect(calls.push).toHaveBeenCalledWith({
      name: 'TaskInstanceDetail',
      query: { id: 'process-1', taskId: 'task-1' }
    })
  })
  it('已取消任务无撤回入口；审批通过任务撤回成功后刷新', async () => {
    calls.done.mockResolvedValue({
      list: [
        { id: 'cancel', status: 4 },
        { id: 'approved', status: 2, withdrawable: true },
        { id: 'finished-process', status: 2, withdrawable: false },
        { id: 'suspended-process', status: 2, withdrawable: false },
        { id: 'old-server-result', status: 2 }
      ],
      total: 5
    })
    await mount(Done)
    expect(Array.from(document.querySelectorAll('button')).filter(b => b.textContent?.includes('撤回'))).toHaveLength(1)
    await click('撤回')
    expect(calls.withdraw).toHaveBeenCalledWith('approved')
    expect(calls.done).toHaveBeenCalledTimes(2)
  })
  it('抄送详情保留流程和抄送节点身份', async () => {
    await mount(Copy)
    await click('详情')
    expect(calls.push).toHaveBeenCalledWith({
      name: 'TaskInstanceDetail',
      query: { id: 'process-1', activityId: 'node-1' }
    })
  })
})
