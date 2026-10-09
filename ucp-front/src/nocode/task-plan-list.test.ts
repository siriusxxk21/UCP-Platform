// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, getCurrentInstance, h, inject, nextTick, provide, type App } from 'vue'
import TaskList from '@/views/nocode/task-center/TaskList.vue'
import type { TaskQuery } from '@/types/nocode/task-center'
import dayjs from 'dayjs'

const api = vi.hoisted(() => ({
  page: vi.fn(),
  managementPage: vi.fn(),
  claimableGroups: vi.fn(),
  personalTreePage: vi.fn(),
  pageTasks: vi.fn(),
  members: vi.fn(),
  entryOptions: vi.fn(),
  mine: vi.fn(),
  legacy: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api, runtime: { mine: api.mine } }) }))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => ({ mine: api.legacy }) }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'employee-a' } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskEmployeeOverview.vue', () => ({
  default: defineComponent({
    emits: ['select'],
    setup:
      (_, { emit }) =>
      () =>
        h('section', { 'data-employee-overview': '' }, [
          h(
            'button',
            {
              onClick: () =>
                emit('select', { userId: 'employee-b', userName: '团队员工', metric: 'WEEK', date: '2030-10-04' })
            },
            '查看团队员工本周清单'
          )
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskPlanDialog.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskRecordPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskLaunchDrawer.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['pagination'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [
          h('h2', slots.title?.()),
          slots.search?.(),
          slots.advancedSearch?.(),
          slots.actions?.(),
          slots.empty?.(),
          h('span', { 'data-page-total': '' }, String(p.pagination?.total))
        ])
  })
}))
let app: App | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 14; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string): HTMLButtonElement {
  const found = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === label)
  if (!found) throw new Error(`缺少按钮：${label}`)
  return found
}
function query(): TaskQuery {
  return (api.pageTasks.mock.calls.at(-1)?.[1] ?? api.page.mock.calls.at(-1)?.[0]) as TaskQuery
}
function required<T>(value: T | null): T {
  if (value === null) throw new Error('缺少测试目标元素')
  return value
}
async function mount(scope: 'MINE' | 'MANAGE' = 'MINE', embedded = false) {
  app = createApp(() =>
    h(TaskList, {
      scope,
      embedded,
      ...(embedded ? { context: { applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record' } } : {})
    })
  )
  const plain = defineComponent({
    props: ['label', 'message', 'description'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.label, p.message, p.description, slots.default?.(), slots.description?.()])
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ASpace',
    'ATag',
    'AAlert',
    'AEmpty',
    'AInputNumber',
    'ACheckbox',
    'ARadio',
    'ATooltip'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (p, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, disabled: p.disabled || p.loading }, slots.default?.())
    })
  )
  app.component(
    'ATabs',
    defineComponent({
      emits: ['change'],
      setup: (_, { emit, slots }) => {
        provide('plan-tab', (key: unknown) => emit('change', key))
        return () => h('div', { role: 'tablist' }, slots.default?.())
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup: p => {
        const choose = inject<(key: unknown) => void>('plan-tab')
        const key = getCurrentInstance()?.vnode.key
        return () => h('button', { role: 'tab', onClick: () => choose?.(key) }, p.tab)
      }
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value', 'change'],
      setup: (_, { emit, slots }) => {
        provide('plan-period', (value: unknown) => {
          emit('update:value', value)
          emit('change', { target: { value } })
        })
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup: (p, { slots }) => {
        const choose = inject<(key: unknown) => void>('plan-period')
        return () => h('button', { onClick: () => choose?.(p.value) }, slots.default?.())
      }
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (p, { attrs, emit }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: p.value,
              disabled: p.disabled,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value, value)
              }
            },
            (p.options || []).map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  const input = defineComponent({
    props: ['value', 'disabled'],
    emits: ['update:value', 'change'],
    setup:
      (p, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: p.value,
          disabled: p.disabled,
          onChange: (event: Event) => {
            const value = (event.target as HTMLInputElement).value
            emit('update:value', value)
            emit('change', value)
          }
        })
  })
  app.component('AInput', input)
  app.component('ADatePicker', input)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.page.mockResolvedValue({ list: [], total: 0 })
  api.managementPage.mockResolvedValue({ list: [], total: 0 })
  api.claimableGroups.mockResolvedValue({ list: [], total: 0 })
  api.personalTreePage.mockImplementation((query: TaskQuery) => api.page(query))
  api.pageTasks.mockResolvedValue({ list: [], total: 0 })
  api.members.mockResolvedValue([
    { id: 'employee-a', name: '当前员工' },
    { id: 'employee-b', name: '团队员工' }
  ])
  api.mine.mockResolvedValue([])
  api.entryOptions.mockResolvedValue([])
  api.legacy.mockResolvedValue([])
})
afterEach(async () => {
  app?.unmount()
  await flush()
  host?.remove()
})

describe('统一个人计划查询契约', () => {
  it('个人默认全部任务，移除重复待办入口并保留其他入口', async () => {
    await mount()
    for (const label of [
      '我的计划',
      '可领取任务',
      '待验收',
      '已办记录',
      '全部任务',
      '未纳入计划',
      '本周计划',
      '今日计划'
    ])
      expect(button(label)).toBeDefined()
    expect(host.textContent).not.toContain('我的待办')
    expect(host.textContent).not.toContain('人员工作安排')
    expect(query()).toMatchObject({ scope: 'MINE', tab: 'ALL', planMode: 'CHECKLIST' })
    expect(query().planFilter).toBeUndefined()
    expect(query().scheduleScope).not.toBe('TEAM')
    expect(query().rootsOnly).not.toBe(true)
    button('可领取任务').click()
    await flush()
    expect(api.claimableGroups).toHaveBeenCalled()
    button('已办记录').click()
    await flush()
    expect(query()).toMatchObject({ scope: 'MINE', tab: 'DONE' })
    expect(query().status).toBeUndefined()
    button('我的计划').click()
    await flush()
    expect(query().tab).toBe('ALL')
    expect(query().status).not.toBe('COMPLETED')
  })

  it('今日、本周清单带日期提示；历史日期回看不成为新增安排日期', async () => {
    await mount()
    button('本周计划').click()
    await flush()
    expect(query()).toMatchObject({
      scope: 'MINE',
      scheduleScope: 'PERSONAL',
      tab: 'WEEK',
      planFilter: 'PLANNED',
      planMode: 'CHECKLIST'
    })
    const date = query().date
    button('本周计划').click()
    await flush()
    expect(query()).toMatchObject({ scheduleScope: 'PERSONAL', tab: 'WEEK', date })
    button('查看历史清单').click()
    await flush()
    button('后一周').click()
    await flush()
    expect(query().date).not.toBe(date)
    const historyDate = query().date
    button('重置').click()
    await flush()
    expect(query().date).toBe(historyDate)
    expect(button('返回当前计划')).toBeDefined()
    button('今日计划').click()
    await flush()
    expect(query()).toMatchObject({ scheduleScope: 'PERSONAL', tab: 'TODAY', date })
    expect(query().rootsOnly).not.toBe(true)
    expect(host.textContent).toContain(date)
    expect(host.textContent).not.toContain('月计划')
    expect(host.querySelector('[aria-label="计划日期导航"]')).toBeNull()
  })

  it('未纳入计划独立筛选，切换全部任务或已办记录不遗留计划条件', async () => {
    await mount()
    expect(host.querySelector('[aria-label="计划范围筛选"]')).toBeNull()
    const date = query().date
    button('未纳入计划').click()
    await flush()
    expect(query()).toMatchObject({ planFilter: 'UNPLANNED', date, tab: 'ALL', planMode: 'CHECKLIST' })
    expect(query().status).toBeUndefined()
    expect(host.textContent).not.toContain('可直接开始办理，不必先安排计划')
    button('全部任务').click()
    await flush()
    expect(query()).toMatchObject({ tab: 'ALL', planMode: 'CHECKLIST' })
    expect(query().planFilter).toBeUndefined()
    button('已办记录').click()
    await flush()
    expect(query()).toMatchObject({ tab: 'DONE' })
    expect(query().status).toBeUndefined()
    expect(query().planFilter).toBeUndefined()
  })
  it('下周查询传 WEEK 与下周日期，重置、跨页签返回及退出历史均保留下周', async () => {
    await mount()
    const nextDate = dayjs(query().date).add(7, 'day').format('YYYY-MM-DD')
    button('下周计划').click()
    await flush()
    expect(query()).toMatchObject({ tab: 'WEEK', date: nextDate, planFilter: 'PLANNED' })
    button('重置').click()
    await flush()
    expect(query().date).toBe(nextDate)
    button('已办记录').click()
    await flush()
    button('我的计划').click()
    await flush()
    expect(query()).toMatchObject({ tab: 'WEEK', date: nextDate })
    button('查看历史清单').click()
    await flush()
    button('前一周').click()
    await flush()
    button('返回当前计划').click()
    await flush()
    expect(query()).toMatchObject({ tab: 'WEEK', date: nextDate })
  })

  it('按员工从汇总下钻清单，返回按任务恢复原状态且不携带员工计划筛选', async () => {
    await mount('MANAGE')
    expect(api.managementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ focus: 'ACTIVE', query: expect.objectContaining({ scope: 'MANAGE', tab: 'ALL' }) })
    )
    expect(Array.from(host.querySelectorAll('[role="tab"]')).map(item => item.textContent)).toEqual([
      '按任务',
      '按员工'
    ])
    expect(host.textContent).not.toContain('人员工作安排')
    expect(host.textContent).not.toContain('团队计划')
    const status = required(host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]'))
    status.value = 'RUNNING'
    status.dispatchEvent(new Event('change'))
    await flush()
    button('查询').click()
    await flush()
    const requests = api.managementPage.mock.calls.length
    button('按员工').click()
    await flush()
    expect(api.managementPage).toHaveBeenCalledTimes(requests)
    expect(host.querySelector('[data-employee-overview]')).not.toBeNull()
    expect(host.querySelector('h2')).toBeNull()
    button('查看团队员工本周清单').click()
    await flush()
    const selected = api.managementPage.mock.calls.at(-1)?.[0]
    expect(selected).toMatchObject({
      focus: 'ALL',
      employeeId: 'employee-b',
      employeeMetric: 'WEEK',
      query: { scope: 'MANAGE', tab: 'ALL', date: '2030-10-04', pageNo: 1 }
    })
    for (const key of ['status', 'scheduleScope', 'assigneeId', 'planMode', 'planFilter'])
      expect(selected.query).not.toHaveProperty(key)
    expect(host.querySelector('h2')?.textContent?.trim()).toContain('团队员工 · 本周清单')
    expect(host.querySelector('[aria-label="任务组数量"]')?.textContent?.trim()).toBe('0 组')
    expect(selected).toHaveProperty('groupByRoot', true)
    expect(host.querySelector('[title*="个人清单"]')?.getAttribute('title')).toContain('由员工自行维护')
    button('按任务').click()
    await flush()
    const returned = api.managementPage.mock.calls.at(-1)?.[0]
    expect(returned).toMatchObject({ focus: 'ALL', query: { scope: 'MANAGE', tab: 'ALL', status: 'RUNNING' } })
    expect(returned).not.toHaveProperty('employeeId')
    expect(returned).not.toHaveProperty('employeeMetric')
    for (const key of ['scheduleScope', 'assigneeId', 'planFilter']) expect(returned.query).not.toHaveProperty(key)
    expect(required(host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]')).value).toBe('RUNNING')
    expect(api.page).not.toHaveBeenCalled()
  })

  it.each(['MINE', 'MANAGE'] as const)('%s 嵌入组件默认未结束，范围切换不带个人计划语义', async scope => {
    await mount(scope, true)
    expect(host.textContent).not.toContain('我的待办')
    expect(host.textContent).not.toContain('人员工作安排')
    expect(host.querySelector('[aria-label="负责人筛选"]')).toBeNull()
    expect(query()).toMatchObject({ scope, tab: 'POOL' })
    expect(query().scheduleScope).toBeUndefined()
    expect(query().planMode).toBeUndefined()
    expect(query().rootsOnly).not.toBe(true)
    button('全部任务').click()
    await flush()
    expect(query()).toMatchObject({ scope, tab: 'ALL' })
    button('近期处理').click()
    await flush()
    expect(query()).toMatchObject({ scope, tab: 'RECENT' })
    for (const [context, request] of api.pageTasks.mock.calls) {
      expect(context).toEqual({ applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record' })
      expect(request).not.toHaveProperty('scheduleScope')
      expect(request).not.toHaveProperty('planMode')
      expect(request).not.toHaveProperty('rootsOnly')
    }
    expect(api.page).not.toHaveBeenCalled()
    expect(api.managementPage).not.toHaveBeenCalled()
  })

  it('统一视图明确分页按任务组计算，展开不重复计数', async () => {
    api.page.mockResolvedValue({ list: [], total: 123 })
    await mount()
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('123')
    button('本周计划').click()
    await flush()
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('123')
    expect(host.querySelector('[aria-label="本页工作数量"]')?.textContent?.trim()).toBe('123 组')
    expect(host.textContent).not.toContain('展开子任务不增加分页数量')
  })
})
