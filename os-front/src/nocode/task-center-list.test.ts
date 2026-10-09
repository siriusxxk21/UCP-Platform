// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createApp,
  defineComponent,
  getCurrentInstance,
  h,
  inject,
  nextTick,
  provide,
  reactive,
  ref,
  type App
} from 'vue'
import TaskList from '@/views/nocode/task-center/TaskList.vue'
import { newTaskNode } from './task-center'
import dayjs from 'dayjs'
import type { TaskDetail, TaskPageContext, TaskQuery, TaskRow } from '@/types/nocode/task-center'
import type { TaskEmployeeSelection, TaskManagementQuery } from '@/types/nocode/task-management'

const calls = vi.hoisted(() => ({
  canCreate: true,
  page: vi.fn(),
  managementPage: vi.fn(),
  employeeSelection: { userId: 2, userName: '李志航', metric: 'TODAY', date: '2030-10-04' } as TaskEmployeeSelection,
  personalTreePage: vi.fn(),
  personalTreeChildren: vi.fn(),
  pageTasks: vi.fn(),
  detail: vi.fn(),
  create: vi.fn(),
  split: vi.fn(),
  deleteSubtask: vi.fn(),
  claim: vi.fn(),
  claimableGroups: vi.fn(),
  claimableChildren: vi.fn(),
  transition: vi.fn(),
  plan: vi.fn(),
  checklistContext: vi.fn(),
  checklist: vi.fn(),
  entryOptions: vi.fn(),
  legacyEntries: vi.fn(),
  members: vi.fn(),
  applications: vi.fn(async () => [] as Array<{ id: string; name: string }>),
  route: { query: {} },
  message: { success: vi.fn(), warning: vi.fn() }
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: calls, runtime: { mine: calls.applications } })
}))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => ({ mine: calls.legacyEntries }) }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 1 } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: (key: string) => key !== 'nocode:task:create' || calls.canCreate }))
vi.mock('vue-router', () => ({
  useRoute: () => calls.route,
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('ant-design-vue', () => ({
  message: calls.message,
  Modal: { confirm: (options: { onOk: () => void }) => options.onOk() }
}))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({
  default: defineComponent({
    props: ['id', 'initialTab', 'initialSelectedId'],
    setup: p => () =>
      h('div', { 'data-detail-id': p.id, 'data-detail-tab': p.initialTab, 'data-detail-selected': p.initialSelectedId })
  })
}))
vi.mock('@/views/nocode/task-center/TaskEmployeeOverview.vue', () => ({
  default: defineComponent({
    props: ['refreshKey'],
    emits: ['select'],
    setup:
      (p, { emit }) =>
      () =>
        h('section', { 'data-employee-overview': '', 'data-refresh-key': p.refreshKey }, [
          h('button', { onClick: () => emit('select', { ...calls.employeeSelection }) }, '选择员工任务')
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskPlanDialog.vue', () => ({
  default: defineComponent({
    props: ['initialDate', 'initialPeriod', 'initialAction', 'ids', 'target'],
    emits: ['saved', 'close'],
    setup:
      (p, { emit }) =>
      () =>
        h(
          'div',
          {
            'data-plan-dialog': '',
            'data-initial-date': p.initialDate,
            'data-initial-period': p.initialPeriod,
            'data-action': p.initialAction,
            'data-target': p.target,
            'data-ids': p.ids?.join(',')
          },
          [
            h(
              'button',
              {
                onClick: () => {
                  emit('saved')
                  emit('close')
                }
              },
              '模拟计划保存成功'
            )
          ]
        )
  })
}))
vi.mock('@/views/nocode/task-center/TaskRecordPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskLaunchDrawer.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'okText', 'loading'],
    emits: ['ok', 'cancel'],
    setup:
      (p, { slots, emit }) =>
      () =>
        p.open
          ? h('section', { 'data-dialog': '' }, [
              slots.formItems?.(),
              slots.footer?.() || [
                h('button', { disabled: p.loading, onClick: () => emit('ok') }, p.okText),
                h('button', { onClick: () => emit('cancel') }, '关闭弹窗')
              ]
            ])
          : null
  })
}))
vi.mock('@/components/UserSelector/index.vue', () => ({
  default: defineComponent({
    props: ['visible', 'multiple', 'candidateUserIds', 'enabledOnly'],
    emits: ['confirm'],
    setup:
      (_props, { emit }) =>
      () =>
        h(
          'button',
          { onClick: () => emit('confirm', [{ id: '1', nickname: '张伟', username: 'zhang' }]) },
          '确认测试负责人'
        )
  })
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource', 'columns', 'pagination', 'rowSelection', 'selectedRowKeys'],
      emits: ['change', 'selection-change'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h('div', [
            h('h2', { 'data-table-title': '' }, slots.title?.()),
            slots.search?.(),
            slots.advancedSearch?.(),
            slots.actions?.(),
            h('span', { 'data-page-total': '' }, String(props.pagination?.total)),
            h('button', { onClick: () => emit('change', { current: 2, pageSize: 10 }) }, '测试翻到第二页'),
            !props.dataSource.length ? slots.empty?.() : null,
            ...props.dataSource.map((record: TaskRow) =>
              h('div', { 'data-task': record.id }, [
                props.rowSelection
                  ? h('input', {
                      type: 'checkbox',
                      'aria-label': `勾选任务：${record.title}`,
                      checked: props.selectedRowKeys.includes(record.id),
                      disabled: props.rowSelection.getCheckboxProps(record).disabled,
                      onChange: (event: Event) => {
                        const keys = (event.target as HTMLInputElement).checked
                          ? [...props.selectedRowKeys, record.id]
                          : props.selectedRowKeys.filter((id: string) => id !== record.id)
                        emit(
                          'selection-change',
                          keys,
                          props.dataSource.filter((row: TaskRow) => keys.includes(row.id))
                        )
                      }
                    })
                  : null,
                ...props.columns.map(
                  (column: {
                    key: string
                    title: string
                    fixed?: string
                    customCell?: (row: TaskRow) => { class?: string }
                  }) =>
                    h(
                      'div',
                      {
                        'data-column': column.key,
                        'aria-label': column.title,
                        'data-fixed': column.fixed,
                        class: column.customCell?.(record).class
                      },
                      slots.bodyCell?.({ column, record })
                    )
                )
              ])
            )
          ])
    })
  }
})
const parent = (): TaskRow => ({
  ...newTaskNode(),
  id: 'parent',
  rootId: 'parent',
  title: '施工总任务',
  assigneeId: 1,
  assigneeName: '张伟',
  creatorId: 1,
  creatorName: '张伟',
  status: 'PENDING',
  project: null,
  business: null,
  baselineStart: null,
  baselineEnd: null,
  expectedStart: null,
  expectedEnd: null,
  actualStart: null,
  actualEnd: null,
  createdAt: '',
  revision: 1,
  instanceRevision: 1,
  childCount: 0,
  plans: [],
  canStart: true,
  canExecute: true,
  canEdit: true,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
const detail = (nodes = [parent()]): TaskDetail => ({ task: parent(), nodes, comments: [], events: [] })
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const actionLabel = (element: Element) =>
  element.getAttribute('aria-label')?.startsWith('更多操作：') ? '更多' : element.textContent?.trim()
const button = (label: string) => Array.from(host.querySelectorAll('button')).find(el => actionLabel(el) === label)!
function required<T>(value: T | undefined | null): T {
  if (value == null) throw new Error('缺少测试目标元素')
  return value
}
async function openRowMenu(id = 'parent') {
  const row = required(host.querySelector(`[data-task="${id}"] [data-column="actions"]`))
  if (!row.querySelector('[role="menu"]')) {
    required(row.querySelector<HTMLButtonElement>('[aria-label^="更多操作："]')).click()
    await flush()
  }
  return row
}
async function menuAction(label: string, id = 'parent') {
  const row = await openRowMenu(id)
  required(
    Array.from(row.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')).find(
      el => el.textContent?.trim() === label
    )
  ).click()
  await flush()
}
async function selectStatus(value: string) {
  const select = required(host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]'))
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
  button('查询').click()
  await flush()
}
async function selectEmployeeMetric(value: TaskEmployeeSelection['metric']) {
  const select = required(host.querySelector<HTMLSelectElement>('[aria-label="员工任务筛选"]'))
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
}
async function mount(
  context?: TaskPageContext,
  scope: 'MINE' | 'MANAGE' = 'MANAGE',
  extra: {
    view?: import('@/types/nocode/application-ui').TaskViewConfig
    businessColumns?: Array<{ key: string; title: string }>
    businessValues?: Record<string, Record<string, unknown>>
  } = {}
) {
  const props = reactive({ scope, context, embedded: !!context, ...extra })
  app = createApp(() => h(TaskList, props))
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AForm', 'ASpace', 'ATag', 'AInputNumber', 'ARadioButton', 'ATooltip']) app.component(name, plain)
  app.component('AMenu', plain)
  app.component(
    'AMenuItem',
    defineComponent({
      emits: ['click'],
      setup: (_, { slots, emit }) => {
        const close = inject<() => void>('close-task-menu')
        return () =>
          h(
            'button',
            {
              role: 'menuitem',
              onClick: () => {
                close?.()
                emit('click')
              }
            },
            slots.default?.()
          )
      }
    })
  )
  app.component(
    'ADropdown',
    defineComponent({
      setup: (_, { slots }) => {
        const open = ref(false)
        // 模拟菜单的选择关闭行为，不依赖重渲染后的 DOM 冒泡时序。
        provide('close-task-menu', () => {
          open.value = false
        })
        return () =>
          h('span', [
            h(
              'span',
              {
                onClick: () => {
                  open.value = !open.value
                }
              },
              slots.default?.()
            ),
            open.value
              ? h(
                  'section',
                  {
                    role: 'menu',
                    onClick: () => {
                      open.value = false
                    }
                  },
                  slots.overlay?.()
                )
              : null
          ])
      }
    })
  )
  app.component(
    'ADatePicker',
    defineComponent({
      props: ['value'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            value: p.value,
            onInput: (event: Event) => {
              const value = (event.target as HTMLInputElement).value
              emit('update:value', value)
              emit('change', value, value)
            }
          })
    })
  )
  app.component(
    'AFormItem',
    defineComponent({
      props: ['help'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', [slots.default?.(), p.help])
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (p, { slots, emit, attrs }) =>
        () =>
          h(
            'div',
            {
              ...attrs,
              'data-radio-value': p.value,
              onClick: (event: MouseEvent) => {
                const value = (event.target as HTMLElement).closest('[value]')?.getAttribute('value')
                if (!p.disabled && value) emit('update:value', value)
              }
            },
            slots.default?.()
          )
    })
  )
  app.component(
    'AEmpty',
    defineComponent({
      props: ['description'],
      setup: p => () => h('div', p.description)
    })
  )
  app.component(
    'ATabs',
    defineComponent({
      emits: ['change'],
      setup: (_, { emit, slots }) => {
        provide('task-tabs', (value: unknown) => emit('change', value))
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup: p => {
        const change = inject<(value: unknown) => void>('task-tabs'),
          key = getCurrentInstance()!.vnode.key
        return () => h('button', { onClick: () => change?.(key) }, p.tab)
      }
    })
  )
  app.component('ARadio', plain)
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled', 'loading'],
      emits: ['update:value', 'change', 'select', 'dropdownVisibleChange'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: p.value,
              disabled: p.disabled,
              'data-loading': String(!!p.loading),
              onFocus: () => emit('dropdownVisibleChange', true),
              onBlur: () => emit('dropdownVisibleChange', false),
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
                emit('select', value)
              }
            },
            (p.options || []).map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', [p.message, slots.description?.()])
    })
  )
  app.component(
    'ACheckbox',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['update:checked'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            type: 'checkbox',
            checked: p.checked,
            disabled: p.disabled,
            onChange: (e: Event) => emit('update:checked', (e.target as HTMLInputElement).checked)
          })
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
  app.component(
    'AInput',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value', 'pressEnter'],
      setup: (p, { emit, expose }) => {
        expose({ focus: vi.fn() })
        return () =>
          h('input', {
            value: p.value,
            disabled: p.disabled,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value),
            onKeydown: (event: KeyboardEvent) => {
              if (event.key === 'Enter') emit('pressEnter', event)
            }
          })
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return props
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.canCreate = true
  calls.page.mockResolvedValue({ list: [parent()], total: 1 })
  calls.managementPage.mockImplementation(({ query }: TaskManagementQuery) => calls.page(query))
  calls.employeeSelection = { userId: 2, userName: '李志航', metric: 'TODAY', date: '2030-10-04' }
  calls.claimableGroups.mockResolvedValue({ list: [], total: 0 })
  calls.claimableChildren.mockResolvedValue([])
  calls.pageTasks.mockResolvedValue({ list: [parent()], total: 1 })
  calls.detail.mockResolvedValue(detail())
  calls.deleteSubtask.mockResolvedValue(true)
  calls.personalTreePage.mockImplementation((query: TaskQuery) => calls.page(query))
  calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, parentId: string) => {
    const result = (await calls.detail(parentId)) as TaskDetail
    return result.nodes.filter(row => row.parentId === parentId && row.assigneeId === 1)
  })
  calls.members.mockResolvedValue([{ id: 1, name: '张伟' }])
  calls.entryOptions.mockResolvedValue([])
  calls.legacyEntries.mockResolvedValue([])
  calls.checklistContext.mockImplementation(async ({ ids }: { ids: string[] }) => ({
    today: '2030-10-04',
    weekStart: '2030-09-30',
    nextWeekStart: '2030-10-07',
    weekEnd: '2030-10-06',
    items: ids.map(taskId => ({ taskId, version: 4, canAdd: true }))
  }))
  calls.checklist.mockResolvedValue({ changed: [], unchanged: [] })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('真实任务列表交互边界', () => {
  it('个人计划列表通过更多删除本人拆分项，成功后刷新同一视图及上级进度', async () => {
    const child = { ...parent(), id: 'child', parentId: 'parent', title: '本人拆分项', canDelete: true }
    calls.page.mockResolvedValue({ list: [{ ...parent(), matchingChildCount: 1, childCount: 1 }], total: 1 })
    calls.detail.mockResolvedValue(detail([parent(), child]))
    await mount(undefined, 'MINE')
    const expand = required(host.querySelector<HTMLButtonElement>('[data-task="parent"] .task-hierarchy__toggle'))
    expand.click()
    await flush()
    await menuAction('删除子任务', 'child')
    const before = calls.personalTreePage.mock.calls.length
    calls.detail.mockResolvedValue(detail([parent()]))
    button('确认删除').click()
    await flush()
    expect(calls.deleteSubtask).toHaveBeenCalledWith({
      id: 'child',
      expectedRevision: 1,
      requestKey: expect.any(String)
    })
    expect(calls.personalTreePage.mock.calls.length).toBeGreaterThan(before)
    expect(host.querySelector('[data-task="child"]')).toBeNull()
    expect(host.querySelector('[data-dialog]')).toBeNull()
  })
  it('个人列表未获服务端删除能力时展示禁用原因，不根据本人归属推断权限', async () => {
    const child = {
      ...parent(),
      id: 'child',
      parentId: 'parent',
      canDelete: false,
      deleteBlockedReason: '原始模板任务不可删除'
    }
    calls.page.mockResolvedValue({ list: [child], total: 1 })
    await mount(undefined, 'MINE')
    const menu = await openRowMenu('child')
    const remove = required(
      Array.from(menu.querySelectorAll<HTMLButtonElement>('button')).find(item =>
        item.textContent?.includes('删除子任务')
      )
    )
    expect(remove.disabled).toBe(true)
    expect(remove.textContent).toContain('原始模板任务不可删除')
    remove.click()
    await flush()
    expect(calls.deleteSubtask).not.toHaveBeenCalled()
    expect(host.querySelector('[data-dialog]')).toBeNull()
  })
  it('删除失败保留确认与后端原因，不刷新或静默移除行', async () => {
    const child = { ...parent(), id: 'child', parentId: 'parent', canDelete: true }
    calls.page.mockResolvedValue({ list: [child], total: 1 })
    calls.deleteSubtask.mockRejectedValueOnce(new Error('子任务已被其他人开始，不能删除'))
    await mount(undefined, 'MINE')
    await menuAction('删除子任务', 'child')
    const before = calls.personalTreePage.mock.calls.length
    button('确认删除').click()
    await flush()
    expect(required(host.querySelector('[data-dialog]')).textContent).toContain('子任务已被其他人开始，不能删除')
    expect(calls.personalTreePage).toHaveBeenCalledTimes(before)
    expect(host.querySelector('[data-task="child"]')).not.toBeNull()
  })
  it('管理列表不增加员工删除入口，保留原管理调整', async () => {
    calls.page.mockResolvedValue({
      list: [{ ...parent(), canDelete: true }],
      total: 1
    })
    await mount()
    const menu = await openRowMenu('parent')
    expect(menu.textContent).not.toContain('删除子任务')
  })
  it.each(['全部任务', '未纳入计划', '本周计划', '今日计划', '下周计划'])(
    '%s 均可开始本人任务，执行后刷新同一视图',
    async view => {
      calls.transition.mockResolvedValue(detail())
      await mount(undefined, 'MINE')
      button(view).click()
      await flush()
      const query = calls.personalTreePage.mock.calls.at(-1)?.[0]
      const before = calls.personalTreePage.mock.calls.length
      button('开始').click()
      await flush()
      expect(calls.transition).toHaveBeenCalledWith(
        expect.objectContaining({ id: parent().id, action: 'START', expectedRevision: 1 })
      )
      expect(calls.personalTreePage.mock.calls.length).toBeGreaterThan(before)
      expect(calls.personalTreePage.mock.calls.at(-1)?.[0]).toMatchObject({ tab: query.tab, date: query.date })
    }
  )
  it('提前开始先确认，取消无写入，确认后不提交任何排期修改', async () => {
    calls.personalTreePage.mockResolvedValue({
      list: [{ ...parent(), expectedStart: '2099-10-12T00:00:00' }],
      total: 1
    })
    calls.transition.mockResolvedValue(detail())
    await mount(undefined, 'MINE')
    button('提前开始').click()
    await flush()
    const confirmation = () => required(document.body.querySelector('[data-dialog]'))
    expect(confirmation().textContent).toContain('原预计时间和计划清单保持不变')
    expect(calls.transition).not.toHaveBeenCalled()
    required(Array.from(confirmation().querySelectorAll('button')).find(el => el.textContent === '关闭弹窗')).click()
    await flush()
    expect(calls.transition).not.toHaveBeenCalled()
    button('提前开始').click()
    await flush()
    required(Array.from(confirmation().querySelectorAll('button')).find(el => el.textContent === '提前开始')).click()
    await flush()
    expect(calls.transition).toHaveBeenCalledOnce()
    expect(calls.transition.mock.calls[0]?.[0]).toMatchObject({ action: 'START' })
    expect(calls.transition.mock.calls[0]?.[0]).not.toHaveProperty('expectedStart')
    expect(calls.checklist).not.toHaveBeenCalled()
  })
  it('纳入计划成功后重取未安排列表、清除勾选，并保留新响应中的必要父级上下文', async () => {
    await mount(undefined, 'MINE')
    button('未纳入计划').click()
    await flush()
    const check = required(host.querySelector<HTMLInputElement>('[aria-label="勾选任务：施工总任务"]'))
    check.checked = true
    check.dispatchEvent(new Event('change'))
    await flush()
    button('加入本周计划').click()
    await flush()
    const week = dayjs()
      .subtract((dayjs().day() + 6) % 7, 'day')
      .format('YYYY-MM-DD')
    calls.personalTreePage.mockResolvedValue({
      list: [
        {
          ...parent(),
          contextOnly: true,
          childCount: 1,
          matchingChildCount: 1,
          plans: [{ mode: 'CHECKLIST', period: 'WEEK', date: week }]
        }
      ],
      total: 1
    })
    const before = calls.personalTreePage.mock.calls.length
    button('模拟计划保存成功').click()
    await flush()
    expect(calls.personalTreePage.mock.calls.length).toBeGreaterThan(before)
    expect(calls.personalTreePage.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'ALL', planFilter: 'UNPLANNED' })
    expect(host.textContent).not.toContain('已选择 1 项')
    expect(host.querySelector('[data-task="parent"] [data-column="plan"]')?.textContent).toContain('本周')
    expect(host.querySelector('[data-task="parent"] [data-column="plan"]')?.textContent).toContain('关联展示')
    calls.personalTreePage.mockResolvedValue({ list: [], total: 0 })
    button('刷新').click()
    await flush()
    expect(host.querySelector('[data-task="parent"]')).toBeNull()
  })
  it.each(['PENDING', 'RUNNING'] as const)(
    '他人概要的 %s 节点仅凭服务端协调能力提供人员动作，不增加办理权限',
    async status => {
      const node: TaskRow = {
        ...parent(),
        id: 'delegated',
        parentId: 'parent',
        rootId: 'parent',
        title: '已分工给柯伟',
        assigneeId: 2,
        assigneeName: '柯伟',
        status,
        contextOnly: true,
        detailVisible: false,
        canExecute: false,
        canEdit: false,
        canAssign: false,
        canDelegate: status === 'PENDING',
        canTransfer: status === 'RUNNING',
        canDelete: status === 'PENDING',
        canStart: false,
        childCount: 0,
        matchingChildCount: 0
      }
      calls.personalTreePage.mockResolvedValue({ list: [node], total: 1 })
      await mount(undefined, 'MINE')
      const actions = await openRowMenu('delegated')
      const labels = Array.from(actions.querySelectorAll('[role="menuitem"]')).map(actionLabel)
      expect(labels).toContain(status === 'PENDING' ? '更换负责人' : '转交任务')
      expect(labels.includes('删除子任务')).toBe(status === 'PENDING')
      for (const label of ['开始', '办理', '拆分子任务', '评论', '计划清单']) expect(labels).not.toContain(label)
      expect(host.querySelector('[data-detail-id]')).toBeNull()
      expect(calls.transition).not.toHaveBeenCalled()
    }
  )
  it('暂停入口只使用后端授权；暂停任务可继续安排计划，恢复为首要操作', async () => {
    const running = { ...parent(), status: 'RUNNING' as const, canPause: true, canResume: false }
    calls.personalTreePage.mockResolvedValue({ list: [running], total: 1 })
    await mount(undefined, 'MINE')
    await openRowMenu()
    expect(button('暂停任务')).toBeDefined()
    expect(button('恢复任务')).toBeUndefined()
    const paused = {
      ...running,
      status: 'PAUSED' as const,
      canExecute: false,
      canPause: false,
      canResume: true,
      pausedByTaskId: running.id,
      pauseReason: '任务已暂停',
      canPlan: true
    }
    calls.personalTreePage.mockResolvedValue({ list: [paused], total: 1 })
    button('刷新').click()
    await flush()
    expect(host.querySelector('[data-column="status"]')?.textContent?.trim()).toBe('已暂停')
    expect(button('恢复任务')).toBeDefined()
    expect(button('办理')).toBeUndefined()
    expect(button('拆分子任务')).toBeUndefined()
    expect(host.querySelector<HTMLInputElement>('[aria-label="勾选任务：施工总任务"]')?.disabled).toBe(false)
  })
  it('祖先暂停的子项只显示暂停状态及原因，不提供越权恢复或执行', async () => {
    const row = {
      ...parent(),
      id: 'paused-child',
      parentId: 'parent',
      status: 'RUNNING' as const,
      canExecute: false,
      canEdit: false,
      canPause: false,
      canResume: false,
      pausedByTaskId: 'parent',
      pauseReason: '上级任务已暂停，请先恢复上级任务'
    }
    calls.personalTreePage.mockResolvedValue({ list: [row], total: 1 })
    await mount(undefined, 'MINE')
    expect(host.querySelector('[data-column="status"]')?.textContent?.trim()).toBe('已暂停')
    expect(host.querySelector('[data-column="status"] [title]')?.getAttribute('title')).toContain(row.pauseReason)
    await openRowMenu(row.id)
    for (const label of ['恢复任务', '暂停任务', '办理', '开始', '拆分子任务']) expect(button(label)).toBeUndefined()
    expect(calls.transition).not.toHaveBeenCalled()
  })
  it('个人任务名称精简，完成数量独立为进度列，状态只显示一个标签', async () => {
    const root = {
      ...parent(),
      childCount: 3,
      matchingChildCount: 3,
      completedChildCount: 1,
      myPendingCount: 2,
      myCompletedCount: 1,
      blockedReason: '等待上级任务开始',
      canStart: false
    }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    await mount(undefined, 'MINE')
    const row = required(host.querySelector('[data-task="parent"]'))
    expect(row.querySelector('[data-column="title"]')?.textContent?.trim()).toBe(root.title)
    expect(row.querySelector('[data-column="progress"]')?.textContent).toContain('1/3')
    expect(row.querySelector('[data-column="progress"] [role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('1')
    expect(row.querySelector('[data-column="status"]')?.textContent?.trim()).toBe('未开始')
    expect(row.querySelector('[data-column="status"] [title]')?.getAttribute('title')).toContain(root.blockedReason)
    expect(row.querySelector('[data-column="plan"]')?.textContent).toContain('未纳入')
  })
  it.each(['本周计划', '今日计划'])('%s不重复显示当前计划归属，展开关联子项仍标明仅供参考', async view => {
    const now = dayjs()
    const root: TaskRow = {
      ...parent(),
      childCount: 1,
      matchingChildCount: 1,
      completedChildCount: 0,
      plans: [
        { mode: 'CHECKLIST', period: 'WEEK', date: now.subtract((now.day() + 6) % 7, 'day').format('YYYY-MM-DD') },
        { mode: 'CHECKLIST', period: 'DAY', date: now.format('YYYY-MM-DD') }
      ]
    }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([
      { ...parent(), id: 'context-child', parentId: root.id, contextOnly: true }
    ])
    await mount(undefined, 'MINE')
    button(view).click()
    await flush()
    const row = required(host.querySelector('[data-task="parent"]'))
    expect(row.querySelector('[data-column="title"]')?.textContent?.trim()).toBe(root.title)
    expect(row.querySelector('[data-column="plan"]')).toBeNull()
    expect(row.textContent).not.toContain(`已纳入${view}`)
    required(row.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="context-child"] [aria-label="未纳入当前计划，仅关联展示"]')).not.toBeNull()
  })
  it('我的计划统一四个视图并采用服务端分组分页，不前端过滤总数', async () => {
    calls.personalTreePage.mockResolvedValue({ list: [parent()], total: 31 })
    await mount(undefined, 'MINE')
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tab: 'ALL', planMode: 'CHECKLIST' })
    )
    expect(calls.personalTreePage.mock.calls.at(-1)?.[0].planFilter).toBeUndefined()
    expect(calls.personalTreePage.mock.calls.at(-1)?.[0]).not.toHaveProperty('personalScope')
    for (const label of ['我的待办', '待我处理', '我负责跟进']) expect(button(label)).toBeUndefined()
    for (const label of ['全部任务', '未纳入计划', '本周计划', '今日计划']) expect(button(label)).toBeDefined()
    button('测试翻到第二页').click()
    await flush()
    button('未纳入计划').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tab: 'ALL', planMode: 'CHECKLIST', planFilter: 'UNPLANNED', pageNo: 1 })
    )
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('31')
    button('本周计划').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tab: 'WEEK', planMode: 'CHECKLIST', planFilter: 'PLANNED', pageNo: 1 })
    )
    button('今日计划').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tab: 'TODAY', planMode: 'CHECKLIST', planFilter: 'PLANNED', pageNo: 1 })
    )
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('31')
  })
  it('已完成子任务保留原顺序和完成进度，不显示动态序号或执行操作', async () => {
    const root = {
      ...parent(),
      childCount: 3,
      matchingChildCount: 3,
      completedChildCount: 1,
      myPendingCount: 2,
      myCompletedCount: 1
    }
    const completed: TaskRow = {
      ...parent(),
      id: 'done',
      parentId: root.id,
      title: '准备开始',
      status: 'COMPLETED',
      contextOnly: true
    }
    const active = { ...parent(), id: 'active', parentId: root.id, title: '动手' }
    const waiting = { ...parent(), id: 'waiting', parentId: root.id, title: '验收' }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([completed, active, waiting])
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(Array.from(host.querySelectorAll('[data-task]')).map(el => el.getAttribute('data-task'))).toEqual([
      'parent',
      'done',
      'active',
      'waiting'
    ])
    expect(host.querySelector('[data-task="parent"] [data-column="progress"]')?.textContent).toContain('1/3')
    expect(
      host.querySelector('[data-task="parent"] [data-column="progress"] [title]')?.getAttribute('title')
    ).toContain('我待处理 2 项 · 已办 1 项')
    expect(host.querySelectorAll('.task-hierarchy__outline')).toHaveLength(0)
    expect(host.querySelector('[data-task="parent"] .task-hierarchy__label')).toBeNull()
    expect(host.querySelector('[data-task="done"] .task-hierarchy__footer .task-hierarchy__label')?.textContent).toBe(
      '子任务'
    )
    expect(host.querySelector('[data-task="done"] .task-list__completed-context')).not.toBeNull()
    expect(host.querySelector('[data-task="done"] [data-column="actions"]')?.textContent?.trim()).toBe('详情')
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('1')
  })
  it('总任务安全分组可以展开但无详情和执行权限，不拿其加载业务详情', async () => {
    const root = { ...parent(), childCount: 1, matchingChildCount: 1, contextOnly: true, detailVisible: false }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([{ ...parent(), id: 'child', parentId: root.id }])
    await mount(undefined, 'MINE')
    const row = required(host.querySelector('[data-task="parent"]'))
    expect(row.querySelector('[data-column="actions"]')?.textContent).toBe('')
    expect(Array.from(row.querySelectorAll('button')).some(el => el.textContent === root.title)).toBe(false)
    required(row.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="child"]')).not.toBeNull()
    expect(calls.detail).not.toHaveBeenCalled()
  })
  it.each(['全部任务', '未纳入计划', '今日计划', '本周计划', '下周计划', '待验收', '已办记录'])(
    '%s 保留完整协作树，摘要只定位编排且不获得计划或办理权限',
    async tab => {
      const root: TaskRow = {
        ...parent(),
        childCount: 2,
        matchingChildCount: 2,
        completedChildCount: 1,
        assigneeId: 2,
        detailVisible: false,
        contextOnly: true,
        anchorTaskId: 'mine'
      }
      const mine: TaskRow = {
        ...parent(),
        id: 'mine',
        parentId: root.id,
        rootId: root.id,
        title: '我的步骤',
        contextOnly: false
      }
      const other: TaskRow = {
        ...mine,
        id: 'other',
        title: '同事步骤',
        assigneeId: 2,
        assigneeName: '同事',
        contextOnly: true,
        detailVisible: false,
        anchorTaskId: 'mine',
        status: 'COMPLETED',
        canEdit: false,
        canExecute: false
      }
      calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
      calls.personalTreeChildren.mockResolvedValue([other, mine])
      await mount(undefined, 'MINE')
      if (tab !== '全部任务') {
        button(tab).click()
        await flush()
      }
      required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
      await flush()
      expect(Array.from(host.querySelectorAll('[data-task]')).map(el => el.getAttribute('data-task'))).toEqual([
        'parent',
        'other',
        'mine'
      ])
      expect(host.querySelector('[data-task="parent"] [data-column="progress"]')?.textContent).toContain('1/2')
      expect(host.querySelector('[data-task="mine"] .task-list__personal-match')).not.toBeNull()
      const context = required(host.querySelector('[data-task="other"]'))
      expect(context.querySelector('.task-list__personal-context')).not.toBeNull()
      expect(context.querySelector('[data-column="actions"]')?.textContent?.trim()).toBe('查看编排')
      expect(context.querySelector('[aria-label="安排计划：同事步骤"]')).toBeNull()
      required(
        Array.from(context.querySelectorAll<HTMLButtonElement>('button')).find(
          el => el.textContent?.trim() === '查看编排'
        )
      ).click()
      await flush()
      const drawer = required(host.querySelector('[data-detail-id]'))
      expect(drawer.getAttribute('data-detail-id')).toBe('mine')
      expect(drawer.getAttribute('data-detail-tab')).toBe('arrangement')
      expect(drawer.getAttribute('data-detail-selected')).toBe('other')
      expect(calls.detail).not.toHaveBeenCalled()
      expect(calls.transition).not.toHaveBeenCalled()
      expect(calls.plan).not.toHaveBeenCalled()
      expect(host.querySelector('[data-page-total]')?.textContent).toBe('1')
    }
  )
  it('周计划展开保留未入清单和已完成子项，父子勾选与清单成员互不联动', async () => {
    const today = dayjs()
    const weekStart = today.subtract((today.day() + 6) % 7, 'day').format('YYYY-MM-DD')
    const root: TaskRow = {
      ...parent(),
      matchingChildCount: 3,
      childCount: 3,
      completedChildCount: 1,
      plans: [{ id: 'root-week', mode: 'CHECKLIST', period: 'WEEK', date: weekStart, canCancel: true }]
    }
    const child: TaskRow = {
      ...parent(),
      id: 'own-child',
      parentId: root.id,
      title: '未纳入清单的本人步骤',
      contextOnly: true,
      canPlan: true,
      canExecute: true,
      canEdit: true
    }
    const other: TaskRow = {
      ...child,
      id: 'other-child',
      title: '同事步骤',
      assigneeId: 2,
      assigneeName: '李志航',
      canPlan: false,
      canStart: false,
      canExecute: false,
      canEdit: false
    }
    const done: TaskRow = {
      ...child,
      id: 'done-child',
      title: '已完成步骤',
      status: 'COMPLETED',
      canPlan: false,
      canStart: false,
      canExecute: false,
      canEdit: false
    }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([child, other, done])
    await mount(undefined, 'MINE')
    button('本周计划').click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(calls.personalTreeChildren).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'WEEK', planMode: 'CHECKLIST', planFilter: 'PLANNED' }),
      root.id
    )
    expect(host.querySelector('[data-task="parent"]')?.textContent).not.toContain('已纳入本周计划')
    expect(host.querySelector('[data-task="own-child"] [aria-label="未纳入当前计划，仅关联展示"]')).not.toBeNull()
    expect(host.querySelector('[data-task="parent"] .task-hierarchy__label')).toBeNull()
    expect(host.querySelectorAll('.task-hierarchy__footer .task-hierarchy__label')).toHaveLength(3)
    expect(host.querySelector('[data-task="own-child"] [data-column="actions"]')?.textContent).toContain('开始')
    expect(host.querySelector('[data-task="own-child"] [data-column="actions"]')?.textContent).toContain('拆分子任务')
    for (const id of ['other-child', 'done-child']) {
      expect(host.querySelector(`[data-task="${id}"] [data-column="actions"]`)?.textContent?.trim()).toBe('详情')
      expect(host.querySelector<HTMLInputElement>(`[data-task="${id}"] input[type="checkbox"]`)?.disabled).toBe(true)
    }
    const rootSelection = required(host.querySelector<HTMLInputElement>('[aria-label="勾选任务：施工总任务"]'))
    const childSelection = required(
      host.querySelector<HTMLInputElement>('[aria-label="勾选任务：未纳入清单的本人步骤"]')
    )
    expect(childSelection.disabled).toBe(false)
    rootSelection.click()
    await flush()
    expect(childSelection.checked).toBe(false)
    expect(host.textContent).toContain('已选择 1 项')
    button('取消选择').click()
    await flush()
    childSelection.click()
    await flush()
    expect(rootSelection.checked).toBe(false)
    button('加入本周计划').click()
    await flush()
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-ids')).toBe('own-child')
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-initial-period')).toBe('WEEK')
    expect(calls.checklist).not.toHaveBeenCalled()
    expect(root.plans).toHaveLength(1)
    expect(child.plans).toHaveLength(0)
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('1')
  })
  it('已办记录中的本人进行中上下文也仅供查看，不保留计划工作区的执行入口', async () => {
    const root = {
      ...parent(),
      contextOnly: true,
      status: 'RUNNING' as const,
      canPlan: true,
      canExecute: true,
      canEdit: true
    }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    await mount(undefined, 'MINE')
    expect(host.querySelector('[data-task="parent"] [data-column="actions"]')?.textContent).toContain('办理')
    button('已办记录').click()
    await flush()
    expect(host.querySelector('[data-task="parent"] [data-column="actions"]')?.textContent?.trim()).toBe('详情')
    expect(host.querySelector<HTMLInputElement>('[aria-label="勾选任务：施工总任务"]')?.disabled).toBe(true)
    expect(calls.checklist).not.toHaveBeenCalled()
    expect(calls.transition).not.toHaveBeenCalled()
  })
  it('已办记录按总任务请求，显示我的部分已完成与整体仍进行中', async () => {
    const root = {
      ...parent(),
      status: 'RUNNING' as const,
      groupStatus: 'RUNNING' as const,
      myPendingCount: 0,
      myCompletedCount: 2,
      contextOnly: true
    }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    await mount(undefined, 'MINE')
    button('已办记录').click()
    await flush()
    const query = calls.personalTreePage.mock.calls.at(-1)?.[0]
    expect(query).toMatchObject({ scope: 'MINE', tab: 'DONE' })
    expect(query).not.toHaveProperty('status')
    expect(query).not.toHaveProperty('personalScope')
    expect(
      host.querySelector('[data-task="parent"] [data-column="progress"] [title]')?.getAttribute('title')
    ).toContain('我的部分已处理')
    expect(host.querySelector('[data-task="parent"] [data-column="status"]')?.textContent?.replace(/\s/g, '')).toBe(
      '进行中'
    )
    expect(button('办理')).toBeUndefined()
  })
  it('已有子任务按真实结构显示添加子任务并收进更多，不依赖当前加载子项', async () => {
    calls.personalTreePage.mockResolvedValue({
      list: [{ ...parent(), childCount: 3, matchingChildCount: 0 }],
      total: 1
    })
    await mount(undefined, 'MINE')
    expect(button('拆分子任务')).toBeUndefined()
    await openRowMenu()
    const add = required(
      Array.from(host.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(el => el.textContent === '添加子任务')
    )
    add.click()
    await flush()
    expect(host.querySelector('[aria-label="子任务名称"]')).not.toBeNull()
  })
  it('本人进行中的汇总父任务也可进入办理，收尾规则仍由后端控制', async () => {
    const root = { ...parent(), status: 'RUNNING' as const, childCount: 2, matchingChildCount: 2 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    await mount(undefined, 'MINE')
    expect(button('办理')).toBeDefined()
    calls.personalTreePage.mockResolvedValue({
      list: [{ ...root, completionReason: '待补交付资料：施工照片' }],
      total: 1
    })
    button('刷新').click()
    await flush()
    expect(button('办理')).toBeDefined()
    expect(host.querySelector('[data-column="status"] [title]')?.getAttribute('title')).toContain(
      '待补交付资料：施工照片'
    )
  })
  it('员工先看状态和截止，拆分常驻时详情收进更多且完整等待原因仍可查看', async () => {
    const reason = '前置任务「A」（负责人：张伟）尚未完成'
    calls.personalTreePage.mockResolvedValue({
      list: [{ ...parent(), canStart: false, blockedReason: reason }],
      total: 1
    })
    await mount(undefined, 'MINE')
    const row = required(host.querySelector('[data-task="parent"]'))
    expect(
      Array.from(row.querySelectorAll('[data-column]'))
        .slice(0, 5)
        .map(el => el.getAttribute('data-column'))
    ).toEqual(['title', 'progress', 'status', 'expectedStart', 'time'])
    const status = required(row.querySelector('[data-column="status"]'))
    expect(status.textContent?.trim()).toBe('未开始')
    expect(status.querySelector('[title]')?.getAttribute('title')).toContain(reason)
    const actions = required(row.querySelector('[data-column="actions"]'))
    expect(Array.from(actions.querySelectorAll('button')).map(actionLabel)).toEqual([
      '开始',
      '拆分子任务',
      '更多',
      '等待 A 完成'
    ])
    expect(button('开始').disabled).toBe(true)
    expect(button('开始').title).toBe(reason)
    await openRowMenu()
    expect(Array.from(actions.querySelectorAll('[role="menuitem"]')).map(el => el.textContent?.trim())).toContain(
      '详情'
    )
    button('等待 A 完成').click()
    await flush()
    expect(host.querySelector('[data-detail-id="parent"]')?.getAttribute('data-detail-tab')).toBe('arrangement')
    expect(calls.transition).not.toHaveBeenCalled()
  })
  it('老板展开子任务不重复上级说明，仅保留优先级且终态无逾期提醒', async () => {
    const root = { ...parent(), childCount: 1 }
    const child = {
      ...parent(),
      id: 'child',
      parentId: root.id,
      title: '子步骤',
      status: 'COMPLETED' as const,
      expectedEnd: '2000-01-01',
      canStart: false
    }
    calls.page.mockResolvedValue({ list: [root], total: 1 })
    calls.detail.mockResolvedValue(detail([root, child]))
    await mount()
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    const row = required(host.querySelector('[data-task="child"]'))
    expect(row.textContent).not.toContain('由上级展开显示')
    expect(row.textContent).not.toContain('上级：')
    expect(row.textContent).not.toContain('独立任务')
    expect(row.textContent).not.toContain('已逾期')
    expect(row.querySelector('[aria-label="紧急程度"]')).toBeNull()
    expect(row.querySelector('[aria-label="优先级"]')).not.toBeNull()
    expect(row.querySelector('.task-list__compact-child .task-hierarchy__footer')?.textContent).toContain('子任务')
    expect(row.querySelectorAll('.task-list__child-cell').length).toBe(row.querySelectorAll('[data-column]').length)
    expect(host.querySelector('[data-task="parent"] .task-list__child-cell')).toBeNull()
  })
  it('员工计划工作区只保留执行摘要，拆分常驻且不要求新建总任务权限', async () => {
    calls.canCreate = false
    const root = { ...parent(), childCount: 1, matchingChildCount: 1 }
    const child = {
      ...parent(),
      id: 'child',
      parentId: root.id,
      title: '当前工作',
      assignmentMode: 'FOLLOW_ROOT' as const,
      plans: [{ mode: 'CHECKLIST' as const, period: 'WEEK' as const, date: '2030-09-30', source: 'SELF' as const }]
    }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([child])
    calls.split.mockResolvedValue({ ...detail(), task: { ...child, id: 'new-child' } })
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    const item = required(host.querySelector('[data-task="child"]'))
    for (const obsolete of [
      '查看整体任务路径',
      '由上级展开显示',
      '汇总 / 协调任务',
      '随总任务负责人',
      '自行加入',
      '上级：'
    ])
      expect(item.textContent).not.toContain(obsolete)
    expect(item.querySelector('[aria-label="预计完成"]')?.textContent).toContain('未安排')
    expect(item.querySelector('[aria-label="紧急程度"]')).toBeNull()
    expect(item.querySelector('[aria-label="优先级"]')).not.toBeNull()
    expect(item.querySelector('[data-column="title"]')?.getAttribute('data-fixed')).toBe('left')
    required(
      Array.from(item.querySelectorAll('button')).find(node => node.textContent?.trim() === '拆分子任务')
    ).click()
    await flush()
    expect(host.querySelector('[aria-label="负责人安排"]')).toBeNull()
    expect(host.textContent).toContain('设置时间（可选）')
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = '自己细分的一步'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.split.mock.calls[0]?.[0]).toMatchObject({
      parentId: 'child',
      task: { title: '自己细分的一步', assigneeId: 1, assignmentMode: 'ASSIGNED' }
    })
    expect(calls.create).not.toHaveBeenCalled()
  })
  it.each([
    { assigneeId: 2 },
    { status: 'PENDING_ACCEPTANCE' },
    { status: 'COMPLETED' },
    { status: 'CANCELLED' },
    { canExecute: false, canEdit: false }
  ])('员工计划工作区不提供不符合资格的拆分入口：%j', async overrides => {
    calls.personalTreePage.mockResolvedValue({ list: [{ ...parent(), ...overrides }], total: 1 })
    await mount(undefined, 'MINE')
    expect(button('拆分子任务')).toBeUndefined()
    expect(calls.split).not.toHaveBeenCalled()
  })
  it.each([
    [
      '待领取',
      { assigneeId: null, canClaim: true, canAssign: true },
      ['分配任务', '详情', '更多'],
      ['领取任务', '＋ 子任务', '评论']
    ],
    ['待分配', { assigneeId: null, canAssign: true }, ['分配任务', '详情', '更多'], ['＋ 子任务', '评论']],
    [
      '他人已分配',
      { assigneeId: 2, canExecute: false, canAssign: true },
      ['更换负责人', '详情', '更多'],
      ['＋ 子任务', '评论']
    ],
    [
      '本人进行中',
      { status: 'RUNNING', canAssign: true },
      ['办理', '详情', '更多'],
      ['更换负责人', '＋ 子任务', '完成', '评论']
    ],
    [
      '待本人验收',
      { status: 'PENDING_ACCEPTANCE', canExecute: false, canAccept: true },
      ['验收', '详情', '更多'],
      ['评论']
    ],
    ['已完成', { status: 'COMPLETED', canExecute: false, canEdit: false }, ['详情', '更多'], ['评论']]
  ] satisfies Array<[string, Partial<TaskRow>, string[], string[]]>)(
    '%s按资格收纳，主动作不在更多中重复',
    async (_name, overrides, direct, more) => {
      calls.page.mockResolvedValue({ list: [{ ...parent(), ...overrides }], total: 1 })
      await mount()
      const actions = required(host.querySelector('[data-task="parent"] [data-column="actions"]'))
      expect(Array.from(actions.querySelectorAll('button')).map(actionLabel)).toEqual(direct)
      await openRowMenu()
      expect(Array.from(actions.querySelectorAll('[role="menuitem"]')).map(item => item.textContent?.trim())).toEqual(
        more
      )
    }
  )
  it('操作列只常驻主要操作、详情和更多，人员安排筛选保留原名', async () => {
    calls.page.mockResolvedValue({ list: [{ ...parent(), canAssign: true }], total: 1 })
    await mount()
    const actions = required(host.querySelector('[data-task="parent"] [data-column="actions"]'))
    expect(Array.from(actions.querySelectorAll('button')).map(actionLabel)).toEqual(['开始', '详情', '更多'])
    expect(host.textContent).not.toContain('已有负责人')
    const labels = Array.from(host.querySelectorAll('option')).map(item => item.textContent)
    expect(labels).toEqual(expect.arrayContaining(['待分配', '待领取', '已分配']))
    button('更多').click()
    await flush()
    expect(Array.from(actions.querySelectorAll('[role="menuitem"]')).map(item => item.textContent?.trim())).toEqual([
      '更换负责人',
      '＋ 子任务',
      '评论'
    ])
    button('更换负责人').click()
    await flush()
    expect(host.querySelector('[data-dialog]')).not.toBeNull()
    // 打开分工弹窗会重渲染表格，检查当前连接的行，不能断言已脱离页面的旧节点。
    expect(host.querySelector('[data-task="parent"] [data-column="actions"] [role="menu"]')).toBeNull()
  })
  it.each(['PENDING', 'RUNNING', 'PENDING_ACCEPTANCE', 'COMPLETED', 'CANCELLED'])(
    '%s的业务归属负责人列只显示两行，不铺开计划记录',
    async tab => {
      calls.applications.mockResolvedValueOnce([{ id: 'construction', name: '施工应用完整名称' }])
      const row: TaskRow = {
        ...parent(),
        assignmentMode: 'ASSIGNED',
        applicationId: 'construction',
        project: { applicationId: 'construction', objectId: 'project', recordId: 'one', label: '办公室装修项目' },
        acceptorId: 2,
        acceptorName: '验收主管',
        plans: [
          { id: 'today', mode: 'CHECKLIST', period: 'DAY', date: '2030-10-04', source: 'SELF' },
          {
            id: 'week',
            mode: 'CHECKLIST',
            period: 'WEEK',
            date: '2030-09-30',
            source: 'MANAGER',
            arrangedByName: '安排人'
          },
          { id: 'legacy', mode: 'SCHEDULE', period: 'MONTH', date: '2000-01-01', endDate: '2000-01-31' }
        ]
      }
      calls.page.mockResolvedValue({ list: [row], total: 1 })
      await mount()
      await selectStatus(tab)
      expect(calls.managementPage).toHaveBeenLastCalledWith(
        expect.objectContaining({ focus: 'ALL', query: expect.objectContaining({ status: tab }) })
      )
      const owner = required(host.querySelector('[data-task="parent"] [data-column="owner"]'))
      const lines = owner.querySelectorAll('.task-list__owner-line')
      expect(Array.from(lines).map(line => line.textContent?.trim())).toEqual(['张伟', '办公室装修项目'])
      expect(lines[1].getAttribute('title')).toContain('施工应用完整名称')
      expect(lines[0].getAttribute('title')).toContain('验收主管')
      for (const hidden of ['今日计划', '本周计划', '旧版区间', '自行加入', '安排人', '2000-01-01', '验收主管'])
        expect(owner.textContent).not.toContain(hidden)
      await menuAction('查看计划记录')
      expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-target')).toBe('ASSIGNEE')
      expect(row.plans).toHaveLength(3)
      expect(calls.checklist).not.toHaveBeenCalled()
    }
  )
  it('个人计划列不重复铺开计划记录，仍可从计划清单查看', async () => {
    calls.page.mockResolvedValue({
      list: [
        {
          ...parent(),
          assignmentMode: 'ASSIGNED',
          plans: [{ mode: 'CHECKLIST', period: 'WEEK', date: '2030-09-30', source: 'SELF' }]
        }
      ],
      total: 1
    })
    await mount(undefined, 'MINE')
    button('我的计划').click()
    await flush()
    const owner = required(host.querySelector('[data-task="parent"] [data-column="owner"]'))
    expect(owner.textContent).toContain('张伟')
    expect(owner.textContent).not.toContain('周计划')
    expect(owner.textContent).not.toContain('自行加入')
    await menuAction('计划清单')
    expect(host.querySelector('[data-plan-dialog]')).not.toBeNull()
  })
  it.each([1, 2])('管理入口查看负责人 %s 的计划，即使是本人也不提供计划操作', async assigneeId => {
    calls.page.mockResolvedValue({
      list: [
        {
          ...parent(),
          assigneeId,
          assigneeName: '李志航',
          canPlan: true,
          plans: [{ id: 'employee-plan', mode: 'CHECKLIST', period: 'DAY', date: '2030-10-04', canCancel: true }]
        }
      ],
      total: 1
    })
    await mount()
    const labels = Array.from(host.querySelectorAll('button')).map(item => item.textContent?.trim())
    expect(labels).not.toContain('计划清单')
    expect(labels).not.toContain('加入今日')
    expect(labels).not.toContain('移出今日')
    await menuAction('查看计划记录')
    await flush()
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-target')).toBe('ASSIGNEE')
    expect(calls.checklist).not.toHaveBeenCalled()
  })
  it('未纳入计划使用服务端过滤和分页，切回全部不保留计划过滤', async () => {
    calls.page.mockResolvedValue({ list: [parent()], total: 31 })
    await mount(undefined, 'MINE')
    for (const label of ['全部待办', '未安排', '即将到期', '已逾期']) expect(button(label)).toBeUndefined()
    button('未纳入计划').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({
      scope: 'MINE',
      tab: 'ALL',
      planMode: 'CHECKLIST',
      planFilter: 'UNPLANNED',
      pageNo: 1
    })
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('31')
    button('测试翻到第二页').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({
      pageNo: 2,
      planFilter: 'UNPLANNED'
    })
    button('全部任务').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'ALL', pageNo: 1 })
    expect(calls.page.mock.calls.at(-1)?.[0].planFilter).toBeUndefined()
    for (const key of ['from', 'to']) expect(calls.page.mock.calls.at(-1)?.[0]).not.toHaveProperty(key)
  })
  it('切换其他页签不携带计划范围，返回我的计划时保留当前视图', async () => {
    await mount(undefined, 'MINE')
    for (const label of ['未纳入计划', '本周计划', '今日计划']) {
      button(label).click()
      await flush()
      button('已办记录').click()
      await flush()
      expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'DONE' })
      for (const key of ['planFilter', 'from', 'to']) expect(calls.page.mock.calls.at(-1)?.[0]).not.toHaveProperty(key)
      button('我的计划').click()
      await flush()
      expect(button(label).getAttribute('aria-pressed')).toBe('true')
    }
  })
  it('高级预计结束日期直接传入查询，切换计划视图和已办记录保留日期条件', async () => {
    await mount(undefined, 'MINE')
    const from = host.querySelector<HTMLInputElement>('[aria-label="高级日期开始"]')
    const to = host.querySelector<HTMLInputElement>('[aria-label="高级日期结束"]')
    if (!from || !to) throw new Error('缺少高级日期输入')
    from.value = dayjs().add(2, 'day').format('YYYY-MM-DD')
    to.value = dayjs().add(10, 'day').format('YYYY-MM-DD')
    from.dispatchEvent(new Event('input'))
    to.dispatchEvent(new Event('input'))
    button('查询').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({
      from: from.value,
      to: to.value
    })
    expect(to.value).toBe(dayjs().add(10, 'day').format('YYYY-MM-DD'))
    button('本周计划').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'WEEK', from: from.value, to: to.value })
    button('已办记录').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'DONE', from: from.value, to: to.value })
  })
  it('重置清除高级日期条件，但保留正在浏览的计划视图', async () => {
    await mount(undefined, 'MINE')
    const from = host.querySelector<HTMLInputElement>('[aria-label="高级日期开始"]')
    if (!from) throw new Error('缺少高级日期输入')
    from.value = dayjs().add(20, 'day').format('YYYY-MM-DD')
    from.dispatchEvent(new Event('input'))
    button('本周计划').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'WEEK', from: from.value })
    expect(from.value).toBe(dayjs().add(20, 'day').format('YYYY-MM-DD'))
    button('重置').click()
    await flush()
    expect(button('本周计划').getAttribute('aria-pressed')).toBe('true')
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'WEEK', planFilter: 'PLANNED' })
    for (const key of ['from', 'to']) expect(calls.page.mock.calls.at(-1)?.[0]).not.toHaveProperty(key)
  })
  it('计划视图调整单项既有安排时不拿浏览日期覆盖真实安排', async () => {
    calls.page.mockResolvedValue({ list: [{ ...parent(), plans: [{ period: 'WEEK', date: '2030-01-07' }] }], total: 1 })
    await mount(undefined, 'MINE')
    button('我的计划').click()
    await flush()
    await menuAction('计划清单')
    await flush()
    const dialog = host.querySelector('[data-plan-dialog]')
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('data-initial-date')).toBeNull()
    expect(dialog?.getAttribute('data-initial-period')).toBe('WEEK')
  })
  it('计划视图加入清单仅带期间，不以浏览日期作为新增日期', async () => {
    await mount(undefined, 'MINE')
    button('我的计划').click()
    await flush()
    button('本周计划').click()
    await flush()
    await menuAction('计划清单')
    await flush()
    const dialog = host.querySelector('[data-plan-dialog]')
    expect(dialog?.getAttribute('data-initial-date')).toBeNull()
    expect(dialog?.getAttribute('data-initial-period')).toBe('WEEK')
  })
  it('周计划可直接选择加入今日，仅带当前任务与DAY，不改变父子任务关系', async () => {
    await mount(undefined, 'MINE')
    button('本周计划').click()
    await flush()
    await menuAction('加入今日')
    await flush()
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-initial-period')).toBe('DAY')
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-ids')).toBe(parent().id)
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-action')).toBe('ADD')
    expect(calls.create).not.toHaveBeenCalled()
    expect(calls.transition).not.toHaveBeenCalled()
  })
  it('今日仅对后台可移的真实清单项提供移出入口', async () => {
    calls.page.mockResolvedValue({
      list: [
        {
          ...parent(),
          plans: [{ id: 'today-own', mode: 'CHECKLIST', period: 'DAY', date: '2030-10-04', canCancel: true }]
        }
      ],
      total: 1
    })
    await mount(undefined, 'MINE')
    button('我的计划').click()
    await flush()
    button('今日计划').click()
    await flush()
    await menuAction('移出今日')
    await flush()
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-initial-period')).toBe('DAY')
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-action')).toBe('REMOVE')
  })
  it('来源筛选按需加载并合并并发打开，人员不等待来源目录', async () => {
    let resolveOptions!: (value: Array<{ value: string; label: string }>) => void
    calls.entryOptions.mockReturnValueOnce(new Promise(resolve => (resolveOptions = resolve)))
    calls.members.mockResolvedValueOnce([{ id: 1, name: '及时加载的成员' }])
    await mount()
    expect(calls.entryOptions).not.toHaveBeenCalled()
    expect(calls.legacyEntries).not.toHaveBeenCalled()
    expect(calls.members).toHaveBeenCalledOnce()
    const source = host.querySelector<HTMLSelectElement>('[aria-label="业务数据来源筛选"]')!
    source.dispatchEvent(new Event('focus'))
    source.dispatchEvent(new Event('blur'))
    source.dispatchEvent(new Event('focus'))
    await flush()
    expect(calls.entryOptions).toHaveBeenCalledOnce()
    expect(calls.legacyEntries).not.toHaveBeenCalled()
    expect(source.dataset.loading).toBe('true')
    await menuAction('＋ 子任务')
    await flush()
    const assignment = host.querySelector<HTMLSelectElement>('[aria-label="负责人安排"]')!
    assignment.value = 'ASSIGNED'
    assignment.dispatchEvent(new Event('change'))
    await flush()
    button('确认测试负责人').click()
    await flush()
    expect(assignment.value).toBe('ASSIGNED')
    expect(button('确认测试负责人')).toBeUndefined()
    resolveOptions([{ value: 'app:FORM:purchase', label: '采购表单' }])
    await flush()
    expect(source.textContent).toContain('采购表单')
    expect(source.dataset.loading).toBe('false')
    source.dispatchEvent(new Event('focus'))
    await flush()
    expect(calls.entryOptions).toHaveBeenCalledOnce()
  })
  it('来源失败保留原筛选值，重试从实际任务来源恢复存量绑定', async () => {
    calls.entryOptions.mockRejectedValueOnce(new Error('目录超时')).mockResolvedValueOnce([
      { value: 'app:ENTRY:purchase', label: '采购业务表单' },
      { value: 'app:FORM:feedback', label: '反馈表单' }
    ])
    await mount()
    const source = host.querySelector<HTMLSelectElement>('[aria-label="业务数据来源筛选"]')!
    const restoredOption = new Option('已选业务表单', 'app:ENTRY:purchase')
    source.add(restoredOption)
    source.value = 'app:ENTRY:purchase'
    source.dispatchEvent(new Event('change'))
    await flush()
    expect(host.textContent).toContain('业务数据来源加载失败')
    expect(calls.entryOptions).toHaveBeenCalledOnce()
    button('查询').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0].entryId).toBe('app:ENTRY:purchase')
    source.dispatchEvent(new Event('blur'))
    source.dispatchEvent(new Event('focus'))
    await flush()
    expect(calls.legacyEntries).not.toHaveBeenCalled()
    expect(calls.entryOptions).toHaveBeenCalledTimes(2)
    // 移除模拟恢复值的 DOM 占位，只检查本次请求实际生成的候选。
    restoredOption.remove()
    source.value = 'app:ENTRY:purchase'
    expect(source.value).toBe('app:ENTRY:purchase')
    expect(Array.from(source.options).map(option => option.textContent)).toEqual([
      '全部入口',
      '采购业务表单',
      '反馈表单'
    ])
    expect(host.textContent).not.toContain('业务数据来源加载失败')
  })
  it('非空筛选值先于展开恢复时仍解析来源名称，不清空或自动提交筛选', async () => {
    calls.entryOptions.mockResolvedValueOnce([{ value: 'app:FORM:purchase', label: '采购表单' }])
    await mount()
    const source = host.querySelector<HTMLSelectElement>('[aria-label="业务数据来源筛选"]')!
    // 模拟已保存筛选恢复到选择器，尚未触发 dropdownVisibleChange。
    source.add(new Option('已有来源', 'app:FORM:purchase'))
    source.value = 'app:FORM:purchase'
    source.dispatchEvent(new Event('change'))
    await flush()
    expect(calls.entryOptions).toHaveBeenCalledOnce()
    expect(calls.legacyEntries).not.toHaveBeenCalled()
    expect(source.value).toBe('app:FORM:purchase')
    expect(source.textContent).toContain('采购表单')
    expect(calls.page).toHaveBeenCalledOnce()
  })
  it('卸载后迟到的来源响应不再解析或写入候选', async () => {
    let resolveOptions!: (value: Array<{ value: string; label: string }>) => void
    calls.entryOptions.mockReturnValueOnce(new Promise(resolve => (resolveOptions = resolve)))
    await mount()
    host.querySelector('[aria-label="业务数据来源筛选"]')!.dispatchEvent(new Event('focus'))
    await flush()
    app!.unmount()
    app = undefined
    const readValue = vi.fn(() => 'late')
    resolveOptions([
      {
        get value() {
          return readValue()
        },
        label: '迟到来源'
      }
    ])
    await flush()
    expect(readValue).not.toHaveBeenCalled()
    expect(host.textContent).toBe('')
  })
  it('任务管理默认未结束，通过独立管理接口按总任务分页，快捷筛选重置页码', async () => {
    calls.page.mockResolvedValue({ list: [parent()], total: 21 })
    await mount()
    for (const label of ['按任务', '按员工', '未结束', '未分配', '已逾期', '待验收', '全部任务'])
      expect(button(label)).toBeDefined()
    for (const label of ['未开始', '进行中', '已完成', '已取消', '人员安排']) expect(button(label)).toBeUndefined()
    expect(button('任务池')).toBeUndefined()
    expect(button('未完成任务')).toBeUndefined()
    expect(button('近期处理')).toBeUndefined()
    expect(calls.managementPage.mock.calls[0]?.[0]).toMatchObject({
      focus: 'ACTIVE',
      query: { scope: 'MANAGE', tab: 'ALL', pageNo: 1, pageSize: 10 }
    })
    expect(calls.managementPage.mock.calls[0]?.[0].query).not.toHaveProperty('status')
    expect(calls.personalTreePage).not.toHaveBeenCalled()
    expect(calls.pageTasks).not.toHaveBeenCalled()
    button('测试翻到第二页').click()
    await flush()
    expect(calls.managementPage.mock.calls.at(-1)?.[0].query.pageNo).toBe(2)
    for (const [label, focus] of [
      ['未分配', 'UNASSIGNED'],
      ['已逾期', 'OVERDUE'],
      ['待验收', 'PENDING_ACCEPTANCE'],
      ['全部任务', 'ALL'],
      ['未结束', 'ACTIVE']
    ] as const) {
      button(label).click()
      await flush()
      expect(calls.managementPage.mock.calls.at(-1)?.[0]).toMatchObject({
        focus,
        query: { tab: 'ALL', pageNo: 1 }
      })
      expect(calls.managementPage.mock.calls.at(-1)?.[0].query).not.toHaveProperty('status')
      expect(button(label).getAttribute('aria-pressed')).toBe('true')
    }
    expect(calls.managementPage.mock.calls.at(-1)?.[0].query).not.toHaveProperty('groupState')
  })
  it('管理列表使用新接口并聚焦进度和时间，等待信息仅保留在状态提示中', async () => {
    const reason = '等待前置任务完成后开始'
    calls.managementPage.mockResolvedValue({
      list: [{ ...parent(), childCount: 4, completedChildCount: 2, canStart: false, blockedReason: reason }],
      total: 7
    })
    await mount()
    expect(calls.managementPage).toHaveBeenCalledOnce()
    expect(calls.page).not.toHaveBeenCalled()
    const row = required(host.querySelector('[data-task="parent"]'))
    expect(
      Array.from(row.querySelectorAll('[data-column]'))
        .slice(0, 6)
        .map(el => el.getAttribute('data-column'))
    ).toEqual(['title', 'progress', 'status', 'expectedStart', 'time', 'owner'])
    expect(row.querySelector('[data-column="progress"]')?.textContent?.trim()).toBe('2/4')
    expect(row.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('2')
    expect(row.querySelector('[data-column="plan"]')).toBeNull()
    expect(row.querySelector('[data-column="status"]')?.textContent?.trim()).toBe('未开始')
    expect(row.querySelector('[data-column="status"] [title]')?.getAttribute('title')).toContain(reason)
    expect(row.textContent).not.toContain(reason)
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('7')
  })
  it('显式状态改为全量查询，再选快捷关注时清除互斥状态并回到第一页', async () => {
    calls.page.mockResolvedValue({ list: [parent()], total: 25 })
    await mount()
    button('测试翻到第二页').click()
    await flush()
    await selectStatus('COMPLETED')
    expect(calls.managementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ focus: 'ALL', query: expect.objectContaining({ status: 'COMPLETED', pageNo: 1 }) })
    )
    expect(button('全部任务').getAttribute('aria-pressed')).toBe('true')
    button('已逾期').click()
    await flush()
    expect(calls.managementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ focus: 'OVERDUE', query: expect.objectContaining({ pageNo: 1 }) })
    )
    expect(calls.managementPage.mock.calls.at(-1)?.[0].query).not.toHaveProperty('status')
    expect(required(host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]')).value).toBe('')
  })
  it('员工下钻按相关总任务分页，展开混合负责人并保留整组进度和员工清单归属', async () => {
    calls.employeeSelection = { userId: 2, userName: '李志航', metric: 'RELATED', date: '2030-10-04' }
    await mount()
    const before = calls.managementPage.mock.calls.length
    button('按员工').click()
    await flush()
    expect(host.querySelector('[data-employee-overview]')).not.toBeNull()
    expect(host.querySelector('[data-task]')).toBeNull()
    expect(calls.managementPage).toHaveBeenCalledTimes(before)
    const root: TaskRow = {
      ...parent(),
      childCount: 3,
      completedChildCount: 1,
      groupStatus: 'RUNNING',
      plans: [{ id: 'root-day', mode: 'CHECKLIST', userId: 1, period: 'DAY', date: '2030-10-04' }]
    }
    const child: TaskRow = {
      ...parent(),
      id: 'employee-node',
      parentId: 'parent',
      assigneeId: 2,
      assigneeName: '李志航',
      title: '员工具体工作',
      plans: [{ id: 'employee-day', mode: 'CHECKLIST', userId: 2, period: 'DAY', date: '2030-10-04' }]
    }
    const completed: TaskRow = {
      ...child,
      id: 'employee-completed',
      title: '员工已完成工作',
      status: 'COMPLETED',
      canExecute: false,
      plans: []
    }
    const coworker: TaskRow = {
      ...child,
      id: 'coworker-node',
      title: '同事协作工作',
      assigneeId: 3,
      assigneeName: '王同事',
      plans: [{ id: 'coworker-day', mode: 'CHECKLIST', userId: 3, period: 'DAY', date: '2030-10-04' }]
    }
    calls.managementPage.mockResolvedValue({ list: [root], total: 19 })
    calls.detail.mockResolvedValue(detail([root, child, completed, coworker]))
    button('选择员工任务').click()
    await flush()
    expect(calls.managementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        focus: 'ALL',
        employeeId: 2,
        employeeMetric: 'RELATED',
        groupByRoot: true,
        query: expect.objectContaining({ scope: 'MANAGE', tab: 'ALL', date: '2030-10-04', pageNo: 1 })
      })
    )
    const rootRow = required(host.querySelector('[data-task="parent"]'))
    expect(rootRow.querySelector('.task-list__employee-context')).not.toBeNull()
    expect(rootRow.querySelector('[data-column="status"]')?.textContent).toContain('进行中')
    expect(rootRow.querySelector('[data-column="progress"]')?.textContent?.trim()).toBe('1/3')
    expect(rootRow.querySelector('[data-column="plan"]')?.textContent?.trim()).toBe('—')
    expect(host.querySelector('[data-task="employee-node"]')).toBeNull()
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('19')
    expect(host.querySelector('[aria-label="任务组数量"]')?.textContent?.trim()).toBe('19 组')
    expect(calls.detail).not.toHaveBeenCalled()
    required(rootRow.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(calls.detail).toHaveBeenCalledWith('parent')
    const row = required(host.querySelector('[data-task="employee-node"]'))
    expect(row.textContent).toContain('员工具体工作')
    expect(row.querySelector('.task-list__employee-owned')).not.toBeNull()
    expect(row.querySelector('[data-column="plan"]')?.textContent?.trim()).toBe('今日')
    expect(host.querySelector('[data-task="employee-completed"] .task-list__employee-owned')).not.toBeNull()
    expect(host.querySelector('[data-task="employee-completed"] [data-column="status"]')?.textContent).toContain(
      '已完成'
    )
    expect(host.querySelector('[data-task="coworker-node"] .task-list__employee-context')).not.toBeNull()
    expect(host.querySelector('[data-task="coworker-node"] [data-column="plan"]')?.textContent?.trim()).toBe('—')
    expect(host.querySelectorAll('[data-task]')).toHaveLength(4)
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('19')
    expect(host.querySelector('[aria-label="任务组数量"]')?.textContent?.trim()).toBe('19 组')
    expect(host.querySelector('[data-column="plan"] button')).toBeNull()
    expect(calls.personalTreeChildren).not.toHaveBeenCalled()
    button('测试翻到第二页').click()
    await flush()
    expect(calls.managementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        employeeMetric: 'RELATED',
        groupByRoot: true,
        query: expect.objectContaining({ pageNo: 2 })
      })
    )
  })
  it('员工筛选使用单个下拉框，相关任务与未结束工作独立，仅日周清单展示对应日期', async () => {
    calls.employeeSelection = { userId: 2, userName: '李志航', metric: 'RELATED', date: '2030-10-04' }
    await mount()
    button('按员工').click()
    await flush()
    button('选择员工任务').click()
    await flush()
    const toolbar = required(host.querySelector('.task-list__employee-toolbar'))
    expect(toolbar.textContent).toContain('李志航')
    expect(toolbar.textContent).toContain('查看范围')
    expect(toolbar.querySelector('[aria-label="返回员工汇总"]')).not.toBeNull()
    const filter = required(toolbar.querySelector<HTMLSelectElement>('[aria-label="员工任务筛选"]'))
    expect(filter.value).toBe('RELATED')
    expect(Array.from(filter.options).map(option => [option.value, option.textContent])).toEqual([
      ['RELATED', '相关任务'],
      ['ALL', '未结束工作'],
      ['PENDING', '未开始'],
      ['RUNNING', '进行中'],
      ['OVERDUE', '已逾期'],
      ['TODAY', '今日清单'],
      ['WEEK', '本周清单'],
      ['COORDINATION', '汇总协调']
    ])
    expect(toolbar.querySelectorAll('select')).toHaveLength(1)
    expect(toolbar.querySelectorAll('button')).toHaveLength(1)
    expect(toolbar.textContent).not.toContain('2030-10-04')
    for (const metric of ['ALL', 'PENDING', 'RUNNING', 'OVERDUE', 'COORDINATION', 'RELATED'] as const) {
      await selectEmployeeMetric(metric)
      expect(calls.managementPage).toHaveBeenLastCalledWith(
        expect.objectContaining({
          employeeId: 2,
          employeeMetric: metric,
          groupByRoot: true,
          query: expect.objectContaining({ pageNo: 1 })
        })
      )
      expect(toolbar.textContent).not.toContain('2030-10-04')
    }
    await selectEmployeeMetric('TODAY')
    expect(toolbar.textContent).toContain('2030-10-04')
    await selectEmployeeMetric('WEEK')
    expect(toolbar.textContent).toContain('2030-09-30 至 10-06')
    expect(calls.managementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        employeeId: 2,
        employeeMetric: 'WEEK',
        groupByRoot: true,
        query: expect.objectContaining({ date: '2030-10-04' })
      })
    )
  })
  it.each(['ALL', 'PENDING', 'RUNNING', 'TODAY', 'WEEK', 'OVERDUE', 'COORDINATION'] as const)(
    '员工 %s 范围显式查询状态时，清除互斥快捷状态但保留清单和协调等范围',
    async metric => {
      calls.employeeSelection = { userId: 2, userName: '李志航', metric, date: '2030-10-04' }
      calls.managementPage.mockResolvedValue({ list: [parent()], total: 21 })
      await mount()
      button('按员工').click()
      await flush()
      button('选择员工任务').click()
      await flush()
      button('测试翻到第二页').click()
      await flush()
      const expectedMetric = ['ALL', 'PENDING', 'RUNNING'].includes(metric) ? 'RELATED' : metric
      await selectStatus('COMPLETED')
      expect(calls.managementPage).toHaveBeenLastCalledWith(
        expect.objectContaining({
          focus: 'ALL',
          employeeId: 2,
          employeeMetric: expectedMetric,
          groupByRoot: true,
          query: expect.objectContaining({ status: 'COMPLETED', date: '2030-10-04', pageNo: 1 })
        })
      )
      expect(required(host.querySelector<HTMLSelectElement>('[aria-label="员工任务筛选"]')).value).toBe(expectedMetric)
      expect(required(host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]')).value).toBe('COMPLETED')
    }
  )
  it.each([1, 2])('员工下钻仅只读显示负责人 %s 的清单，即使本人也不能增删计划', async assigneeId => {
    calls.employeeSelection = { userId: assigneeId, userName: '员工', metric: 'TODAY', date: '2030-10-04' }
    const row: TaskRow = {
      ...parent(),
      assigneeId,
      canPlan: true,
      plans: [
        { id: 'day', mode: 'CHECKLIST', userId: assigneeId, period: 'DAY', date: '2030-10-04', canCancel: true },
        { id: 'week', mode: 'CHECKLIST', userId: assigneeId, period: 'WEEK', date: '2030-09-30', canCancel: true }
      ]
    }
    calls.managementPage.mockResolvedValue({ list: [row], total: 1 })
    await mount()
    button('按员工').click()
    await flush()
    button('选择员工任务').click()
    await flush()
    const plan = required(host.querySelector('[data-column="plan"]'))
    expect(plan.getAttribute('aria-label')).toBe('个人清单')
    expect(plan.textContent?.trim()).toBe('今日 · 本周')
    expect(plan.querySelector('button')).toBeNull()
    expect(host.querySelector('[aria-label^="勾选任务"]')).toBeNull()
    const menu = await openRowMenu()
    for (const label of ['计划清单', '加入今日', '加入本周计划', '移出今日', '移出本周'])
      expect(Array.from(menu.querySelectorAll('button')).map(actionLabel)).not.toContain(label)
    await menuAction('查看计划记录')
    expect(host.querySelector('[data-plan-dialog]')?.getAttribute('data-target')).toBe('ASSIGNEE')
    expect(calls.checklist).not.toHaveBeenCalled()
    expect(calls.checklistContext).not.toHaveBeenCalled()
    expect(calls.plan).not.toHaveBeenCalled()
  })
  it('员工相关任务中的协调父节点也高亮，但高亮不推导管理或执行权限', async () => {
    calls.employeeSelection = { userId: '2', userName: '李志航', metric: 'RELATED', date: '2030-10-04' }
    const root: TaskRow = {
      ...parent(),
      assigneeId: 2,
      assigneeName: '李志航',
      childCount: 1,
      completedChildCount: 1,
      canStart: false,
      canExecute: false,
      canEdit: false,
      canAssign: false,
      canDelegate: false
    }
    const child: TaskRow = {
      ...parent(),
      id: 'managed-coworker',
      parentId: 'parent',
      title: '有管理权限的协作工作',
      assigneeId: 3,
      assigneeName: '王同事',
      canExecute: false,
      canStart: false,
      canEdit: true,
      canAssign: true
    }
    calls.managementPage.mockResolvedValue({ list: [root], total: 1 })
    calls.detail.mockResolvedValue(detail([root, child]))
    await mount()
    button('按员工').click()
    await flush()
    button('选择员工任务').click()
    await flush()
    expect(host.querySelector('[data-task="parent"] .task-list__employee-owned')).not.toBeNull()
    const rootMenu = await openRowMenu('parent')
    for (const label of ['开始', '提前开始', '办理', '完成', '更换负责人', '＋ 子任务'])
      expect(Array.from(rootMenu.querySelectorAll('button')).map(actionLabel)).not.toContain(label)
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="managed-coworker"] .task-list__employee-context')).not.toBeNull()
    const coworkerMenu = await openRowMenu('managed-coworker')
    const actions = Array.from(coworkerMenu.querySelectorAll('button')).map(actionLabel)
    expect(actions).toContain('更换负责人')
    expect(actions).toContain('＋ 子任务')
    expect(actions).not.toContain('开始')
    expect(calls.transition).not.toHaveBeenCalled()
    expect(calls.plan).not.toHaveBeenCalled()
  })
  it('员工展开未分配协作节点时优先分配，没有分配权限则不显示分配入口', async () => {
    calls.employeeSelection = { userId: 2, userName: '李志航', metric: 'RELATED', date: '2030-10-04' }
    const root: TaskRow = { ...parent(), assigneeId: 2, childCount: 2 }
    const assignable: TaskRow = {
      ...parent(),
      id: 'assignable-child',
      parentId: 'parent',
      title: '可分配协作节点',
      assigneeId: null,
      assigneeName: null,
      canExecute: false,
      canEdit: false,
      canAssign: true,
      canClaim: true,
      canDelegate: false
    }
    const claimable: TaskRow = { ...assignable, id: 'claimable-child', title: '只可领取协作节点', canAssign: false }
    calls.managementPage.mockResolvedValue({ list: [root], total: 1 })
    calls.detail.mockResolvedValue(detail([root, assignable, claimable]))
    await mount()
    button('按员工').click()
    await flush()
    button('选择员工任务').click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="assignable-child"] .task-list__primary-action')?.textContent?.trim()).toBe(
      '分配任务'
    )
    const assignableMenu = await openRowMenu('assignable-child')
    expect(Array.from(assignableMenu.querySelectorAll('[role="menuitem"]')).map(actionLabel)).toContain('只领这一项')
    expect(host.querySelector('[data-task="claimable-child"] .task-list__primary-action')?.textContent?.trim()).toBe(
      '只领这一项'
    )
    const claimableMenu = await openRowMenu('claimable-child')
    expect(Array.from(claimableMenu.querySelectorAll('button')).map(actionLabel)).not.toContain('分配任务')
    expect(calls.claim).not.toHaveBeenCalled()
    expect(calls.transition).not.toHaveBeenCalled()
  })
  it.each(['筛选', '员工'] as const)('切换%s后迟到的子节点响应不会覆盖或展开当前任务组', async change => {
    calls.employeeSelection = { userId: 2, userName: '李志航', metric: 'RELATED', date: '2030-10-04' }
    const root: TaskRow = { ...parent(), childCount: 1, title: '旧筛选总任务', completedChildCount: 0 }
    calls.managementPage.mockResolvedValue({ list: [root], total: 1 })
    await mount()
    button('按员工').click()
    await flush()
    button('选择员工任务').click()
    await flush()
    let resolveChildren!: (response: TaskDetail) => void
    calls.detail.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveChildren = resolve
        })
    )
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：旧筛选总任务"]')).click()
    await flush()
    const currentRoot: TaskRow = { ...root, title: '当前筛选总任务', completedChildCount: 1 }
    const currentChild: TaskRow = {
      ...parent(),
      id: 'current-child',
      parentId: 'parent',
      title: '当前员工子任务',
      assigneeId: change === '员工' ? 3 : 2
    }
    calls.managementPage.mockResolvedValue({ list: [currentRoot], total: 7 })
    if (change === '筛选') {
      await selectEmployeeMetric('RUNNING')
    } else {
      required(host.querySelector<HTMLButtonElement>('[aria-label="返回员工汇总"]')).click()
      await flush()
      expect(host.querySelector('[data-task]')).toBeNull()
      calls.employeeSelection = { userId: 3, userName: '王同事', metric: 'RELATED', date: '2030-10-04' }
      button('选择员工任务').click()
      await flush()
    }
    resolveChildren(detail([root, { ...parent(), id: 'stale-child', parentId: 'parent', title: '迟到的子任务' }]))
    await flush()
    expect(host.querySelector('[data-task="parent"]')?.textContent).toContain('当前筛选总任务')
    expect(host.querySelector('[data-task="parent"] [data-column="progress"]')?.textContent?.trim()).toBe('1/1')
    expect(host.querySelector('[data-task="stale-child"]')).toBeNull()
    expect(host.querySelectorAll('[data-task]')).toHaveLength(1)
    expect(host.querySelector('[aria-label="任务组数量"]')?.textContent?.trim()).toBe('7 组')
    calls.detail.mockResolvedValue(detail([currentRoot, currentChild]))
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：当前筛选总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="current-child"] .task-list__employee-owned')).not.toBeNull()
    expect(host.querySelector('[data-task="stale-child"]')).toBeNull()
    expect(calls.detail).toHaveBeenCalledTimes(2)
  })
  it('按任务筛选与员工下钻隔离，切回按任务恢复原搜索、负责人、日期和关注', async () => {
    await mount()
    const search = required(host.querySelector<HTMLInputElement>('[placeholder="搜索总任务或子任务名称"]'))
    search.value = '施工搜索'
    search.dispatchEvent(new Event('input'))
    const owner = required(host.querySelector<HTMLSelectElement>('[aria-label="负责人筛选"]'))
    owner.value = '1'
    owner.dispatchEvent(new Event('change'))
    const from = required(host.querySelector<HTMLInputElement>('[aria-label="高级日期开始"]'))
    from.value = '2030-10-01'
    from.dispatchEvent(new Event('input'))
    await selectStatus('COMPLETED')
    button('按员工').click()
    await flush()
    button('选择员工任务').click()
    await flush()
    const employeeQuery = calls.managementPage.mock.calls.at(-1)?.[0]
    expect(employeeQuery).toMatchObject({ focus: 'ALL', employeeId: 2, employeeMetric: 'TODAY', groupByRoot: true })
    for (const key of ['search', 'assigneeId', 'status', 'from', 'planMode', 'planFilter'])
      expect(employeeQuery.query).not.toHaveProperty(key)
    button('按任务').click()
    await flush()
    const taskQuery = calls.managementPage.mock.calls.at(-1)?.[0]
    expect(taskQuery).toMatchObject({
      focus: 'ALL',
      query: { search: '施工搜索', assigneeId: '1', status: 'COMPLETED', from: '2030-10-01', pageNo: 1 }
    })
    expect(taskQuery).not.toHaveProperty('employeeId')
    expect(taskQuery).not.toHaveProperty('employeeMetric')
    expect(button('全部任务').getAttribute('aria-pressed')).toBe('true')
    expect(host.querySelector('[data-column="plan"]')).toBeNull()
  })
  it('员工下钻失败显示失败态，重试保留同一员工、指标和日期', async () => {
    await mount()
    button('按员工').click()
    await flush()
    calls.managementPage.mockRejectedValueOnce(new Error('员工任务暂不可用'))
    button('选择员工任务').click()
    await flush()
    const failedQuery = calls.managementPage.mock.calls.at(-1)?.[0]
    expect(host.textContent).toContain('员工任务暂不可用')
    expect(host.textContent).toContain('任务列表加载失败')
    expect(host.querySelector('[data-task]')).toBeNull()
    button('重新加载').click()
    await flush()
    expect(calls.managementPage.mock.calls.at(-1)?.[0]).toEqual(failedQuery)
    expect(host.textContent).toContain('施工总任务')
    expect(host.textContent).not.toContain('员工任务暂不可用')
    expect(calls.checklist).not.toHaveBeenCalled()
  })
  it('切入员工下钻后迟到的整组任务响应不能覆盖员工任务', async () => {
    await mount()
    let resolveOld!: (result: { list: TaskRow[]; total: number }) => void
    calls.managementPage.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveOld = resolve
        })
    )
    button('已逾期').click()
    await flush()
    button('按员工').click()
    await flush()
    const child = {
      ...parent(),
      id: 'current-employee',
      rootId: 'current-employee',
      title: '员工当前工作',
      assigneeId: 2
    }
    calls.managementPage.mockResolvedValue({ list: [child], total: 1 })
    button('选择员工任务').click()
    await flush()
    resolveOld({ list: [{ ...parent(), title: '迟到的整组响应' }], total: 99 })
    await flush()
    expect(host.querySelector('[data-task="current-employee"]')?.textContent).toContain('员工当前工作')
    expect(host.textContent).not.toContain('迟到的整组响应')
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('1')
  })
  it('根尚未开始但后代开始时显示整体进行中，不改真实状态或开始操作', async () => {
    const root = { ...parent(), childCount: 1, groupStatus: 'RUNNING' as const }
    const child = { ...parent(), id: 'child', parentId: root.id, status: 'RUNNING' as const, title: '正在施工' }
    calls.page.mockResolvedValue({ list: [root], total: 8 })
    calls.detail.mockResolvedValue(detail([parent(), child]))
    await mount()
    expect(host.querySelector('[data-task="parent"] [data-column="status"]')?.textContent).toContain('进行中')
    expect(host.querySelector('[data-task="parent"] [data-column="status"]')?.textContent).not.toContain(
      '总任务未开始，下级已开始'
    )
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    expect(host.querySelectorAll('[data-task="parent"]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-task="child"]')).toHaveLength(1)
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('8')
    expect(host.querySelector('[data-task="parent"] [data-column="status"]')?.textContent).toContain('进行中')
    button('开始').click()
    await flush()
    expect(calls.transition).toHaveBeenCalledWith(expect.objectContaining({ id: 'parent', action: 'START' }))
    expect(root.status).toBe('PENDING')
  })
  it('管理搜索子任务返回所属总任务，可展开且不会把子任务计作分页根', async () => {
    const root = { ...parent(), childCount: 1, groupStatus: 'PENDING' as const }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '测量放线' }
    calls.page.mockResolvedValue({ list: [root], total: 1 })
    calls.detail.mockResolvedValue(detail([root, child]))
    await mount()
    const search = host.querySelector<HTMLInputElement>('input[placeholder="搜索总任务或子任务名称"]')
    expect(search).not.toBeNull()
    if (!search) throw new Error('缺少管理任务搜索框')
    search.value = '测量'
    search.dispatchEvent(new Event('input'))
    button('查询').click()
    await flush()
    expect(calls.managementPage.mock.calls.at(-1)?.[0]).toMatchObject({
      focus: 'ACTIVE',
      query: { search: '测量' }
    })
    expect(host.querySelector('[placeholder="搜索总任务或子任务名称"]')?.getAttribute('title')).toContain(
      '显示所属总任务'
    )
    expect(host.querySelector('[data-task="child"]')).toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    expect(host.querySelector('[data-task="child"]')?.textContent).toContain('测量放线')
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('1')
  })
  it('个人全部任务使用个人树分页，非本人上级下的子任务仍为入口', async () => {
    const child = { ...parent(), id: 'child', parentId: 'parent', title: '我的施工步骤' }
    calls.page.mockResolvedValue({ list: [child], total: 1 })
    await mount(undefined, 'MINE')
    expect(calls.page.mock.calls[0]?.[0]).toMatchObject({ scope: 'MINE', tab: 'ALL', planMode: 'CHECKLIST' })
    expect(calls.personalTreePage).toHaveBeenCalledOnce()
    expect(calls.managementPage).not.toHaveBeenCalled()
    expect(button('按员工')).toBeUndefined()
    expect(calls.page.mock.calls[0]?.[0]).not.toHaveProperty('rootsOnly')
    expect(calls.page.mock.calls[0]?.[0]).not.toHaveProperty('status')
    expect(host.querySelector('[data-task="child"]')?.textContent).toContain('我的施工步骤')
    expect(host.querySelector('[data-task="parent"]')).toBeNull()
    expect(button('我的待办')).toBeUndefined()
    expect(button('我的计划')).toBeDefined()
    expect(button('可领取任务')).toBeDefined()
  })
  it('全部任务同时展示执行和验收任务，各自操作正确并使用同一分页', async () => {
    const acceptance: TaskRow = {
      ...parent(),
      id: 'acceptance',
      rootId: 'acceptance',
      title: '需要我验收的设备',
      status: 'PENDING_ACCEPTANCE',
      assigneeId: 2,
      assigneeName: '李志航',
      acceptorId: 1,
      acceptorName: '张伟',
      canStart: false,
      canExecute: false,
      canEdit: false,
      canAccept: true
    }
    calls.personalTreePage.mockResolvedValue({ list: [parent(), acceptance], total: 21 })
    await mount(undefined, 'MINE')
    const work = host.querySelector('[data-task="parent"]')
    const review = host.querySelector('[data-task="acceptance"]')
    expect(work?.textContent).toContain('开始')
    expect(review?.textContent).toContain('待验收')
    expect(Array.from(review?.querySelectorAll('button') || []).map(item => item.textContent?.trim())).toContain('验收')
    for (const label of ['开始', '办理', '完成', '计划', '提交验收'])
      expect(Array.from(review?.querySelectorAll('button') || []).map(item => item.textContent?.trim())).not.toContain(
        label
      )
    expect(button('待我验收')).toBeUndefined()
    expect(button('待他人验收')).toBeUndefined()
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('21')
    button('测试翻到第二页').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'ALL', planMode: 'CHECKLIST', pageNo: 2, pageSize: 10 })
    )
    expect(calls.page).not.toHaveBeenCalled()
  })
  it('全部任务可筛选验收状态，待验收查询待我验收的任务组', async () => {
    await mount(undefined, 'MINE')
    const select = host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]')!
    select.value = 'PENDING_ACCEPTANCE'
    select.dispatchEvent(new Event('change'))
    await flush()
    button('查询').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'ALL', planMode: 'CHECKLIST', status: 'PENDING_ACCEPTANCE' })
    )
    button('待验收').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'ACCEPTANCE', status: 'PENDING_ACCEPTANCE' })
    )
    button('我的计划').click()
    await flush()
    expect(calls.personalTreePage.mock.calls.at(-1)?.[0]).not.toHaveProperty('status')
  })
  it('上级未开始时本人子任务提前可见，展示真实上级人员状态与等待原因', async () => {
    const child: TaskRow = {
      ...parent(),
      id: 'child',
      parentId: 'stage',
      rootId: 'root',
      title: '墙面施工',
      canStart: false,
      blockedReason: '等待上级任务“二层施工”（施工主管）开始',
      ancestorContext: [
        {
          id: 'root',
          parentId: null,
          title: '施工项目',
          assigneeName: '项目经理',
          status: 'RUNNING',
          detailVisible: false
        },
        {
          id: 'stage',
          parentId: 'root',
          title: '二层施工',
          assigneeName: '施工主管',
          status: 'PENDING',
          detailVisible: false
        }
      ]
    }
    calls.personalTreePage.mockResolvedValue({ list: [child], total: 1 })
    await mount(undefined, 'MINE')
    const item = host.querySelector('[data-task="child"]')!
    expect(item.textContent).toContain('上级：二层施工')
    expect(item.textContent).not.toContain('二层施工 · 施工主管 · 待处理')
    expect(item.querySelector('[data-column="status"]')?.textContent?.trim()).toBe('未开始')
    expect(item.querySelector('[data-column="status"] [title]')?.getAttribute('title')).toBe(child.blockedReason)
    const blockedStart = required(
      Array.from(item.querySelectorAll('button')).find(node => node.textContent?.trim() === '开始')
    )
    expect(blockedStart.disabled).toBe(true)
    blockedStart.click()
    expect(calls.transition).not.toHaveBeenCalled()
    expect(host.querySelector('[data-task="stage"]')).toBeNull()
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('1')
    expect(calls.transition).not.toHaveBeenCalled()
  })

  it('个人树一次展开全部子孙，分支可单独收起且分页入口不重复', async () => {
    const root = { ...parent(), childCount: 2, matchingChildCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '我的施工', matchingChildCount: 1 }
    const leaf = { ...parent(), id: 'leaf', parentId: child.id, title: '我的验收', matchingChildCount: 0 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 21 })
    calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) =>
      id === root.id ? [child] : [leaf]
    )
    await mount(undefined, 'MINE')
    expect(host.querySelectorAll('[data-task]')).toHaveLength(1)
    expect(host.querySelector('[data-task="parent"]')?.textContent).not.toContain('1 项下级')
    expect(host.querySelector('[aria-label="展开子任务：施工总任务"]')).not.toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    expect(host.querySelectorAll('[data-task]')).toHaveLength(3)
    expect(host.querySelector('[data-task="leaf"] .task-hierarchy')?.getAttribute('data-depth')).toBe('2')
    expect(calls.personalTreeChildren.mock.calls.map(([, id]) => id)).toEqual(['parent', 'child'])
    required(host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：我的施工"]')).click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：我的施工"]')).click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).not.toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')?.click()
    await flush()
    expect(host.querySelectorAll('[data-task]')).toHaveLength(1)
    expect(host.querySelector('[data-page-total]')?.textContent).toBe('21')
    expect(calls.detail).not.toHaveBeenCalled()
    button('测试翻到第二页').click()
    await flush()
    expect(calls.personalTreePage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 2 }))
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
  })
  it('个人树跨过非本人中间层时沿服务端归并展开，不改真实上级', async () => {
    const root = { ...parent(), childCount: 1, matchingChildCount: 1 }
    const leaf = { ...parent(), id: 'leaf', parentId: 'other-owner', title: '我的跨层任务', matchingChildCount: 0 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([leaf])
    await mount(undefined, 'MINE')
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    expect(host.querySelector('[data-task="leaf"] .task-hierarchy')?.getAttribute('data-depth')).toBe('1')
    expect(host.querySelector('[data-task="leaf"]')?.textContent).toContain('上级任务未在当前视图中')
    expect(host.querySelector('[data-task="other-owner"]')).toBeNull()
    expect(leaf.parentId).toBe('other-owner')
    host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')?.click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
  })
  it.each(['全部任务', '未纳入计划', '今日计划', '本周计划', '下周计划', '待验收', '已办记录'])(
    '%s 点击灰色总任务即可看到深层本人任务，不获取摘要详情或修改计划',
    async tab => {
      const root = { ...parent(), matchingChildCount: 1, detailVisible: false, contextOnly: true, assigneeId: 2 }
      const middle = {
        ...root,
        id: 'middle',
        parentId: root.id,
        title: '同事负责的中间层',
        matchingChildCount: 1
      }
      const leaf = { ...parent(), id: 'leaf', parentId: middle.id, title: '我负责的末级任务', matchingChildCount: 0 }
      calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
      calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) =>
        id === root.id ? [middle] : [leaf]
      )
      await mount(undefined, 'MINE')
      if (tab !== '全部任务') {
        button(tab).click()
        await flush()
      }
      required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
      await flush()
      expect(host.querySelector('[data-task="leaf"] .task-hierarchy')?.getAttribute('data-depth')).toBe('2')
      expect(host.querySelector('[data-task="middle"] .task-list__personal-context')).not.toBeNull()
      expect(host.querySelector('[data-task="leaf"] .task-list__personal-match')).not.toBeNull()
      expect(calls.personalTreeChildren.mock.calls.map(([, id]) => id)).toEqual(['parent', 'middle'])
      expect(calls.detail).not.toHaveBeenCalled()
      expect(calls.plan).not.toHaveBeenCalled()
      expect(calls.checklist).not.toHaveBeenCalled()
    }
  )
  it('刷新保留手动收起的分支，显式重新展开总任务时再次展开所有后代', async () => {
    const root = { ...parent(), matchingChildCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '中间任务', matchingChildCount: 1 }
    const leaf = { ...parent(), id: 'leaf', parentId: child.id, title: '末级任务', matchingChildCount: 0 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) =>
      id === root.id ? [child] : [leaf]
    )
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：中间任务"]')).click()
    await flush()
    button('刷新').click()
    await flush()
    expect(host.querySelector('[data-task="child"]')).not.toBeNull()
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
    required(host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')).click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).not.toBeNull()
    button('刷新').click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).not.toBeNull()
  })
  it('深层加载失败不丢失已加载同组任务，失败分支可原地重试', async () => {
    const root = { ...parent(), matchingChildCount: 2 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '中间任务', matchingChildCount: 1 }
    const sibling = { ...parent(), id: 'sibling', parentId: root.id, title: '同级任务', matchingChildCount: 0 }
    const leaf = { ...parent(), id: 'leaf', parentId: child.id, title: '末级任务', matchingChildCount: 0 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) => {
      if (id === root.id) return [child, sibling]
      throw new Error('此分支暂时读取失败')
    })
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="sibling"]')).not.toBeNull()
    expect(host.textContent).toContain('此分支暂时读取失败')
    calls.personalTreeChildren.mockResolvedValueOnce([leaf])
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：中间任务"]')).click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).not.toBeNull()
    expect(host.textContent).not.toContain('此分支暂时读取失败')
  })
  it('刷新未完成时切换视图，不把旧视图的展开选择恢复到新视图', async () => {
    const root = { ...parent(), matchingChildCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '原展开任务', matchingChildCount: 0 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([child])
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    let finish!: (page: { list: TaskRow[]; total: number }) => void
    calls.personalTreePage.mockImplementationOnce(() => new Promise(resolve => (finish = resolve)))
    button('刷新').click()
    await flush()
    button('今日计划').click()
    await flush()
    finish({ list: [root], total: 1 })
    await flush()
    expect(host.querySelector('[data-task="child"]')).toBeNull()
    expect(calls.personalTreeChildren).toHaveBeenCalledOnce()
  })
  it('展开过程中收起总任务或切换页签，迟到的孙级结果不会重新展开或继续请求', async () => {
    const root = { ...parent(), matchingChildCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '中间任务', matchingChildCount: 1 }
    const leaf = { ...parent(), id: 'leaf', parentId: child.id, title: '末级任务', matchingChildCount: 1 }
    let finish!: (rows: TaskRow[]) => void
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) =>
      id === root.id ? [child] : new Promise<TaskRow[]>(resolve => (finish = resolve))
    )
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')).click()
    await flush()
    finish([leaf])
    await flush()
    expect(host.querySelector('[data-task="child"]')).toBeNull()
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
    expect(calls.personalTreeChildren).toHaveBeenCalledTimes(2)
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    button('待验收').click()
    await flush()
    finish([leaf])
    await flush()
    expect(host.querySelector('[data-task="child"]')).toBeNull()
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
    expect(calls.personalTreeChildren).toHaveBeenCalledTimes(4)
  })
  it('重复与循环摘要不会重复展开请求，空分支不留下可空展开的箭头', async () => {
    const root = { ...parent(), matchingChildCount: 2 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '循环分支', matchingChildCount: 1 }
    const empty = { ...parent(), id: 'empty', parentId: root.id, title: '空分支', matchingChildCount: 1 }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) =>
      id === root.id ? [child, child, empty] : id === child.id ? [root] : []
    )
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(calls.personalTreeChildren.mock.calls.map(([, id]) => id)).toEqual(['parent', 'child', 'empty'])
    expect(host.querySelectorAll('[data-task="parent"]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-task="child"]')).toHaveLength(1)
    expect(host.querySelector('[data-task="empty"] .task-hierarchy__toggle')).toBeNull()
  })
  it('孙级请求未完成时收起再展开上级，复用在途请求且最终恢复完整子树', async () => {
    const root = { ...parent(), matchingChildCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '中间任务', matchingChildCount: 1 }
    const leaf = { ...parent(), id: 'leaf', parentId: child.id, title: '末级任务', matchingChildCount: 0 }
    let finish!: (rows: TaskRow[]) => void
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockImplementation(async (_query: TaskQuery, id: string) =>
      id === root.id ? [child] : new Promise<TaskRow[]>(resolve => (finish = resolve))
    )
    await mount(undefined, 'MINE')
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')).click()
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')).click()
    await flush()
    expect(calls.personalTreeChildren.mock.calls.map(([, id]) => id)).toEqual(['parent', 'child', 'parent'])
    finish([leaf])
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).not.toBeNull()
  })
  it('个人树展开沿用已提交查询，失败后可直接重试且过期结果不串入其他页签', async () => {
    const root = { ...parent(), matchingChildCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '我的施工' }
    calls.personalTreePage.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockRejectedValueOnce(new Error('下级暂时读取失败')).mockResolvedValueOnce([child])
    await mount(undefined, 'MINE')
    const search = host.querySelector<HTMLInputElement>('input[placeholder="搜索任务名称"]')
    if (!search) throw new Error('缺少个人任务搜索框')
    search.value = '尚未提交'
    search.dispatchEvent(new Event('input'))
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    expect(host.textContent).toContain('下级暂时读取失败')
    expect(host.querySelector('[aria-label="展开子任务：施工总任务"]')).not.toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    expect(calls.personalTreeChildren.mock.calls.at(-1)?.[0]).not.toHaveProperty('search')
    expect(host.querySelector('[data-task="child"]')).not.toBeNull()
    expect(host.textContent).not.toContain('下级暂时读取失败')
    host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')?.click()
    await flush()
    let finish!: (rows: TaskRow[]) => void
    calls.personalTreeChildren.mockImplementationOnce(() => new Promise<TaskRow[]>(resolve => (finish = resolve)))
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')?.click()
    await flush()
    button('可领取任务').click()
    await flush()
    finish([child])
    await flush()
    expect(host.querySelector('[data-task="child"]')).toBeNull()
    expect(calls.claimableGroups).toHaveBeenCalled()
  })
  it('只读人员查看进行中任务显示详情，不误导为可办理', async () => {
    calls.page.mockResolvedValue({
      list: [{ ...parent(), status: 'RUNNING', canExecute: false, canEdit: false }],
      total: 1
    })
    await mount()
    expect(button('办理')).toBeUndefined()
    expect(button('详情')).toBeDefined()
  })
  it('加载失败显示重新加载而非无任务，重试后恢复结果', async () => {
    calls.page.mockRejectedValueOnce(new Error('网络暂不可用')).mockResolvedValueOnce({ list: [parent()], total: 1 })
    await mount()
    expect(host.textContent).toContain('任务列表加载失败')
    expect(host.textContent).not.toContain('没有符合条件的任务')
    button('重新加载').click()
    await flush()
    expect(host.textContent).toContain('施工总任务')
  })
  it('全部任务空态提供领取入口；历史日期导航折叠且可回到当前周', async () => {
    calls.page.mockResolvedValue({ list: [], total: 0 })
    await mount(undefined, 'MINE')
    button('本周计划').click()
    await flush()
    const first = calls.page.mock.calls.at(-1)![0].date
    expect(host.querySelector('[aria-label="计划日期导航"]')).toBeNull()
    button('查看历史清单').click()
    await flush()
    button('后一周').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)![0].date).not.toBe(first)
    button('返回当前计划').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)![0].date).toBe(first)
    button('全部任务').click()
    await flush()
    expect(host.textContent).toContain('暂无分配给你的任务')
    button('去领取任务').click()
    await flush()
    expect(calls.claimableGroups).toHaveBeenCalled()
    expect(host.textContent).toContain('暂时没有可领取的任务')
  })
  it('可选列分别展示预计时间、实际开始与实际完成时间；未完成留空', async () => {
    calls.page.mockResolvedValue({
      list: [
        {
          ...parent(),
          expectedStart: '2030-01-01T08:00:00',
          expectedEnd: '2030-01-03T17:00:00',
          actualStart: '2030-01-01T09:00:00',
          actualEnd: null
        }
      ],
      total: 1
    })
    await mount()
    for (const [key, label, text] of [
      ['expectedStart', '预计开始', '2030-01-01'],
      ['expectedEnd', '预计结束', '2030-01-03'],
      ['actualStart', '实际开始', '2030-01-01 09:00'],
      ['actualEnd', '实际完成时间', '']
    ]) {
      const cell = host.querySelector(`[data-column="${key}"]`)!
      expect(cell.getAttribute('aria-label')).toBe(label)
      expect(cell.textContent?.trim()).toBe(text)
    }
  })
  it.each(['COMPLETED', 'CANCELLED', 'IN_PROGRESS'] as const)(
    '实际完成列只为已完成任务显示结束时间：%s',
    async status => {
      calls.page.mockResolvedValue({
        list: [{ ...parent(), status, actualEnd: '2030-01-03T17:00:00' }],
        total: 1
      })
      await mount()
      const cell = required(host.querySelector('[data-column="actualEnd"]'))
      expect(cell.getAttribute('aria-label')).toBe('实际完成时间')
      expect(cell.textContent?.trim()).toBe(status === 'COMPLETED' ? '2030-01-03 17:00' : '')
    }
  )
  it('未分配不显示开始；可领取页仅公开摘要，领取不调用开始接口且失败重试同键', async () => {
    const open: TaskRow = {
      ...parent(),
      id: 'open-child',
      parentId: 'parent',
      assigneeId: null,
      assigneeName: null,
      assignmentMode: 'OPEN',
      canClaim: true,
      canAssign: false,
      canEdit: false,
      canExecute: false,
      canStart: false
    }
    calls.page.mockResolvedValue({ list: [open], total: 1 })
    calls.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'parent',
          title: '可领取部分所属任务',
          rootVisible: false,
          canClaimGroup: false,
          claimableCount: 1,
          followRootCount: 0
        }
      ],
      total: 1
    })
    calls.claimableChildren.mockResolvedValue([open])
    calls.claim.mockRejectedValueOnce(new Error('回执丢失')).mockResolvedValueOnce(detail())
    await mount(undefined, 'MINE')
    button('可领取任务').click()
    await flush()
    expect(calls.claimableGroups).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(calls.claimableGroups.mock.calls.at(-1)?.[0]).not.toHaveProperty('entryId')
    expect(button('开始')).toBeUndefined()
    expect(button('详情')).toBeUndefined()
    expect(button('评论')).toBeUndefined()
    expect(button('施工总任务')).toBeUndefined()
    button('选择子任务').click()
    await flush()
    button('只领这一项').click()
    await flush()
    host.querySelector<HTMLButtonElement>('[data-dialog] button')!.click()
    await flush()
    expect(host.textContent).toContain('回执丢失')
    host.querySelector<HTMLButtonElement>('[data-dialog] button')!.click()
    await flush()
    expect(calls.claim.mock.calls[1]![0]).toEqual(calls.claim.mock.calls[0]![0])
    expect(calls.claim.mock.calls[0]![0]).toMatchObject({ id: 'open-child', expectedRevision: 1 })
    expect(calls.transition).not.toHaveBeenCalled()
    button('我的计划').click()
    await flush()
    expect(calls.page.mock.calls.at(-1)?.[0]).toMatchObject({ tab: 'ALL', planMode: 'CHECKLIST' })
  })
  it('管理任务池筛选待分配、待领取、已分配，草稿有独立入口', async () => {
    await mount()
    expect(button('未结束')).toBeDefined()
    expect(button('我的草稿')).toBeDefined()
    const select = host.querySelector<HTMLSelectElement>('[aria-label="人员安排筛选"]')!
    expect(Array.from(select.options).map(option => option.textContent)).toEqual([
      '全部',
      '待分配',
      '待领取',
      '已分配',
      '随总任务负责人'
    ])
    for (const mode of ['UNASSIGNED', 'OPEN', 'ASSIGNED']) {
      select.value = mode
      select.dispatchEvent(new Event('change'))
      await flush()
      button('查询').click()
      await flush()
      expect(calls.managementPage.mock.calls.at(-1)?.[0]).toMatchObject({
        focus: 'ACTIVE',
        query: { assignmentMode: mode }
      })
    }
  })
  it('多层展开显示完整归属，收起上级隐藏整棵子树', async () => {
    const root = { ...parent(), childCount: 1 }
    const child = { ...parent(), id: 'child', parentId: root.id, title: '材料准备', childCount: 1 }
    const leaf = { ...parent(), id: 'leaf', parentId: child.id, title: '材料复核' }
    calls.page.mockResolvedValue({ list: [root], total: 1 })
    calls.detail.mockResolvedValue(detail([root, child, leaf]))
    await mount()
    host.querySelector<HTMLButtonElement>('[aria-label="展开子任务：施工总任务"]')!.click()
    await flush()
    expect(host.querySelector('[data-task="child"] .task-hierarchy')?.getAttribute('data-depth')).toBe('1')
    expect(host.querySelector('[data-task="child"]')?.textContent).not.toContain('上级：施工总任务')
    expect(host.querySelector('[aria-label="收起子任务：材料准备"]')).not.toBeNull()
    expect(calls.detail).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-task="leaf"] .task-hierarchy')?.getAttribute('data-depth')).toBe('2')
    expect(host.querySelector('[data-task="leaf"] .task-hierarchy__outline')).toBeNull()
    expect(host.querySelector('[data-task="leaf"]')?.textContent).not.toContain('上级：材料准备')
    host.querySelector<HTMLButtonElement>('[aria-label="收起子任务：施工总任务"]')!.click()
    await flush()
    expect(host.querySelector('[data-task="leaf"]')).toBeNull()
    expect(host.querySelector('[data-task="child"]')).toBeNull()
  })
  it('应用筛选只命中子任务时仍显示子任务身份，不自动补入同实例无关行', async () => {
    const child = { ...parent(), id: 'child', parentId: 'parent', title: '筛选命中子项' }
    calls.pageTasks.mockResolvedValue({ list: [child], total: 1 })
    await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    expect(button('全部任务')).toBeDefined()
    expect(button('未结束')).toBeDefined()
    expect(calls.pageTasks.mock.calls[0]?.[1].tab).toBe('POOL')
    expect(calls.pageTasks.mock.calls[0]?.[1]).not.toHaveProperty('rootsOnly')
    expect(calls.managementPage).not.toHaveBeenCalled()
    expect(button('按员工')).toBeUndefined()
    expect(host.querySelector('[data-task="child"] .task-hierarchy__label')?.textContent).toBe('子任务')
    expect(host.querySelector('[data-task="child"]')?.textContent).toContain('上级任务未在当前视图中')
    expect(host.querySelector('[data-task="child"] .task-hierarchy')?.getAttribute('data-hierarchy')).toBeNull()
    expect(host.querySelector('[data-task="parent"]')).toBeNull()
    expect(calls.detail).not.toHaveBeenCalled()
  })
  it('查询不发送空枚举，并保留页码协议', async () => {
    await mount()
    const query = calls.managementPage.mock.calls[0]?.[0].query
    expect(query).toMatchObject({
      scope: 'MANAGE',
      tab: 'ALL',
      pageNo: 1,
      pageSize: 10
    })
    expect(query).not.toHaveProperty('urgency')
    expect(query).not.toHaveProperty('status')
    expect(query).not.toHaveProperty('priority')
    expect(query).not.toHaveProperty('pageNum')
  })
  it('应用任务共用任务中心紧凑表格和范围操作，办理后仍刷新当前页面记录', async () => {
    const context = { applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record-a' }
    await mount(context)
    expect(host.querySelector('.task-list--embedded')).not.toBeNull()
    expect(host.querySelector('[data-task="parent"] .task-list__compact-name')).not.toBeNull()
    expect(host.querySelector('[data-column="title"]')?.getAttribute('data-fixed')).toBe('left')
    expect(host.querySelector('[data-column="owner"]')?.getAttribute('aria-label')).toBe('负责人 / 业务')
    expect(host.querySelector('[data-column="time"]')?.getAttribute('aria-label')).toBe('预计完成')
    expect(button('任务池')).toBeUndefined()
    button('全部任务').click()
    await flush()
    expect(calls.pageTasks.mock.calls.at(-1)).toEqual([context, expect.objectContaining({ tab: 'ALL' })])
    calls.transition.mockResolvedValue(detail())
    button('开始').click()
    await flush()
    expect(calls.transition).toHaveBeenCalledWith(expect.objectContaining({ id: 'parent', action: 'START' }))
    expect(calls.pageTasks.mock.calls.at(-1)).toEqual([context, expect.objectContaining({ tab: 'ALL' })])
    expect(calls.page).not.toHaveBeenCalled()
    expect(calls.managementPage).not.toHaveBeenCalled()
    button('详情').click()
    await flush()
    expect(host.querySelector('[data-detail-id="parent"]')).not.toBeNull()
  })
  it('应用配置的业务列和业务筛选继续叠加在当前记录范围', async () => {
    const context: TaskPageContext = {
      applicationId: 'app',
      pageId: 'page',
      nodeId: 'tasks',
      recordId: 'record-a',
      conditions: { logic: 'AND', items: [{ type: 'condition', field: 'amount', operator: 'gt', value: 100 }] }
    }
    await mount(context, 'MANAGE', {
      view: { columnKeys: ['title', 'business:amount', 'status', 'actions'] },
      businessColumns: [{ key: 'amount', title: '金额' }],
      businessValues: { parent: { amount: 240 } }
    })
    expect(
      Array.from(host.querySelectorAll('[data-task="parent"] [data-column]')).map(cell =>
        cell.getAttribute('data-column')
      )
    ).toEqual(['title', 'business:amount', 'status', 'actions'])
    expect(host.querySelector('[data-column="business:amount"]')?.textContent).toBe('240')
    await selectStatus('RUNNING')
    expect(calls.pageTasks.mock.calls.at(-1)).toEqual([
      context,
      expect.objectContaining({ tab: 'POOL', status: 'RUNNING' })
    ])
    button('重置').click()
    await flush()
    expect(calls.pageTasks.mock.calls.at(-1)?.[0]).toEqual(context)
    expect(calls.pageTasks.mock.calls.at(-1)?.[1]).not.toHaveProperty('status')
  })
  it('记录切换请求未返回时立即撤下旧任务，旧详情入口不可继续操作', async () => {
    const props = await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'one' })
    button('详情').click()
    await flush()
    expect(host.querySelector('[data-detail-id="parent"]')).not.toBeNull()
    let resolve!: (value: { list: TaskRow[]; total: number }) => void
    calls.pageTasks.mockImplementationOnce(
      () =>
        new Promise(yes => {
          resolve = yes
        })
    )
    props.context = { ...props.context!, recordId: 'two' }
    await flush()
    expect(host.querySelector('[data-task="parent"]')).toBeNull()
    expect(host.querySelector('[data-detail-id="parent"]')).toBeNull()
    expect(button('开始')).toBeUndefined()
    resolve({ list: [{ ...parent(), id: 'second', rootId: 'second', title: '第二项目任务' }], total: 1 })
    await flush()
    expect(host.querySelector('[data-task="second"]')).not.toBeNull()
    expect(calls.pageTasks.mock.calls.at(-1)?.[0].recordId).toBe('two')
  })
  it('应用范围的未结束为空不误报从未关联任务，失败重试仍保留原范围', async () => {
    calls.pageTasks.mockResolvedValue({ list: [], total: 0 })
    const context = { applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record-a' }
    await mount(context)
    expect(host.textContent).toContain('当前范围暂无未结束任务，可切换全部任务查看')
    expect(host.textContent).not.toContain('这里还没有关联任务')
    calls.pageTasks.mockRejectedValueOnce(new Error('连接中断'))
    button('全部任务').click()
    await flush()
    expect(host.textContent).toContain('任务列表加载失败')
    expect(host.textContent).not.toContain('当前范围暂无任务')
    button('重新加载').click()
    await flush()
    expect(host.textContent).toContain('当前范围暂无任务')
    expect(calls.pageTasks.mock.calls.at(-1)).toEqual([context, expect.objectContaining({ tab: 'ALL' })])
    expect(calls.page).not.toHaveBeenCalled()
  })
  it('父行直接新增，失败保留输入及幂等键，成功后可连续输入', async () => {
    await mount()
    await menuAction('＋ 子任务')
    await flush()
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    expect(input.closest('.task-hierarchy')?.textContent).toContain('新增子任务')
    expect(input.closest('.task-hierarchy')?.textContent).toContain('上级：施工总任务')
    input.value = '测量放线'
    input.dispatchEvent(new Event('input'))
    await flush()
    calls.create.mockRejectedValueOnce(new Error('网络异常')).mockResolvedValueOnce(detail())
    button('添加').click()
    await flush()
    expect(input.value).toBe('测量放线')
    const key = calls.create.mock.calls[0]?.[0].requestKey
    button('添加').click()
    await flush()
    expect(calls.create.mock.calls[1]?.[0]).toMatchObject({
      parentId: 'parent',
      requestKey: key,
      task: { title: '测量放线', assigneeId: 1, assignmentMode: 'ASSIGNED' }
    })
    expect(calls.create.mock.calls[1]?.[0]).toEqual(calls.create.mock.calls[0]?.[0])
    expect(input.value).toBe('')
    button('取消').click()
    await flush()
    expect(host.querySelector('[aria-label="子任务名称"]')).toBeNull()
    expect(calls.create).toHaveBeenCalledTimes(2)
  })
  it('员工拆自己子任务直接给本人，不受总负责人影响，也不读取整组详情', async () => {
    const root = { ...parent(), id: 'root', rootId: 'root', assigneeId: 'root-owner' }
    const child = { ...parent(), id: 'child', rootId: root.id, parentId: root.id, title: '本人负责的下级' }
    calls.page.mockResolvedValue({ list: [child], total: 1 })
    calls.detail.mockResolvedValue({ ...detail(), task: child, nodes: [root, child] })
    calls.split.mockResolvedValue(detail([root, child]))
    calls.personalTreeChildren.mockResolvedValue([])
    await mount(undefined, 'MINE')
    button('拆分子任务').click()
    await flush()
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = '更深一层'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.detail).not.toHaveBeenCalled()
    expect(calls.detail).not.toHaveBeenCalledWith(root.id)
    expect(calls.split.mock.calls[0]?.[0]).toMatchObject({
      parentId: child.id,
      task: { assigneeId: 1, assignmentMode: 'ASSIGNED', candidateUserIds: [] }
    })
    expect(calls.create).not.toHaveBeenCalled()
    expect(calls.split.mock.calls[0]?.[0]).not.toHaveProperty('project')
  })
  it('管理人员拆分时总任务不可见仍要求显式分工，不猜测负责人', async () => {
    const child = { ...parent(), id: 'child', rootId: 'hidden-root', parentId: 'hidden-root' }
    calls.page.mockResolvedValue({ list: [child], total: 1 })
    calls.pageTasks.mockResolvedValue({ list: [child], total: 1 })
    calls.detail.mockResolvedValue({ ...detail(), task: child, nodes: [child] })
    calls.create.mockResolvedValue(detail([child]))
    await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    await menuAction('＋ 子任务', child.id)
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = '保持原默认'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.create).not.toHaveBeenCalled()
    expect(host.textContent).toContain('总负责人信息不可用')
    const select = required(host.querySelector<HTMLSelectElement>('[aria-label="负责人安排"]'))
    select.value = 'OPEN'
    select.dispatchEvent(new Event('change'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.create.mock.calls[0]?.[0].task).toMatchObject({ assigneeId: null, assignmentMode: 'OPEN' })
    expect(calls.detail).not.toHaveBeenCalledWith('hidden-root')
  })
  it('人员预填只发生一次，手动改成待分配后刷新总负责人不覆盖输入', async () => {
    await mount()
    await menuAction('＋ 子任务')
    const assignment = required(host.querySelector<HTMLSelectElement>('[aria-label="负责人安排"]'))
    expect(assignment.value).toBe('ASSIGNED')
    assignment.value = 'UNASSIGNED'
    assignment.dispatchEvent(new Event('change'))
    calls.detail.mockResolvedValue(detail([{ ...parent(), assigneeId: 'new-root-owner' }]))
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = '手动另行分配'
    input.dispatchEvent(new Event('input'))
    calls.create.mockResolvedValue(detail())
    await flush()
    button('添加').click()
    await flush()
    expect(calls.create.mock.calls[0]?.[0].task).toMatchObject({ assigneeId: null, assignmentMode: 'UNASSIGNED' })
    expect(assignment.value).toBe('UNASSIGNED')
  })
  it('总负责人加载失败不会展示错误默认，重新操作可重试', async () => {
    const root = { ...parent(), id: 'root', rootId: 'root', assigneeId: 'root-owner' }
    const child = { ...parent(), id: 'child', rootId: root.id, parentId: root.id }
    calls.page.mockResolvedValue({ list: [child], total: 1 })
    calls.pageTasks.mockResolvedValue({ list: [child], total: 1 })
    calls.detail
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockResolvedValue({ ...detail(), task: child, nodes: [root, child] })
    await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    await menuAction('＋ 子任务', child.id)
    expect(host.querySelector('[aria-label="子任务名称"]')).toBeNull()
    expect(host.textContent).toContain('总任务负责人加载失败，请重试添加子任务')
    await menuAction('＋ 子任务', child.id)
    expect(host.querySelector('[aria-label="子任务名称"]')).not.toBeNull()
    expect(calls.create).not.toHaveBeenCalled()
  })
  it('先发起的总负责人查询晚返回时不会覆盖后来选择的拆分目标', async () => {
    const a = { ...parent(), id: 'a', rootId: 'root-a', parentId: 'root-a', title: '第一项' }
    const b = { ...parent(), id: 'b', rootId: 'root-b', parentId: 'root-b', title: '第二项' }
    const rootA = { ...parent(), id: 'root-a', rootId: 'root-a', assigneeId: 'owner-a' }
    const rootB = { ...parent(), id: 'root-b', rootId: 'root-b', assigneeId: 'owner-b' }
    let resolveFirst!: (result: TaskDetail) => void
    calls.page.mockResolvedValue({ list: [a, b], total: 2 })
    calls.pageTasks.mockResolvedValue({ list: [a, b], total: 2 })
    calls.detail.mockImplementation((id: string) =>
      id === a.id
        ? new Promise<TaskDetail>(resolve => {
            resolveFirst = resolve
          })
        : Promise.resolve({ ...detail(), task: b, nodes: [rootB, b] })
    )
    calls.create.mockResolvedValue({ ...detail(), task: b, nodes: [rootB, b] })
    await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    await menuAction('＋ 子任务', a.id)
    await menuAction('＋ 子任务', b.id)
    resolveFirst({ ...detail(), task: a, nodes: [rootA, a] })
    await flush()
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    expect(input.closest('.task-hierarchy')?.textContent).toContain('上级：第二项')
    input.value = '晚响应不覆盖'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.create.mock.calls[0]?.[0]).toMatchObject({
      parentId: b.id,
      task: { assigneeId: 'owner-b', assignmentMode: 'ASSIGNED' }
    })
  })
  it('切换项目后丢弃前一个项目未返回的展开结果', async () => {
    const props = await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'one' })
    let resolve!: (result: TaskDetail) => void
    calls.detail.mockImplementationOnce(
      () =>
        new Promise<TaskDetail>(yes => {
          resolve = yes
        })
    )
    await menuAction('＋ 子任务')
    await flush()
    const second = { ...parent(), id: 'second', rootId: 'second', title: '第二项目任务' }
    calls.pageTasks.mockResolvedValue({ list: [second], total: 1 })
    props.context = { ...props.context!, recordId: 'two' }
    await flush()
    resolve(detail([parent(), { ...parent(), id: 'old-child', parentId: 'parent', title: '旧项目子任务' }]))
    await flush()
    expect(host.textContent).toContain('第二项目任务')
    expect(host.textContent).not.toContain('旧项目子任务')
    expect(calls.pageTasks.mock.calls.at(-1)?.[0].recordId).toBe('two')
  })
  it.each([
    ['本周计划', 'WEEK', '2030-09-30'],
    ['下周计划', 'NEXT_WEEK', '2030-10-07']
  ])('员工拆分单独选择%s，计划失败只重试计划，不重复新增', async (label, choice, date) => {
    const root = {
      ...parent(),
      expectedStart: '2026-09-29T08:00:00',
      schedule: { ...parent().schedule, durationDays: 3 }
    }
    const child = { ...root, id: 'created-child', parentId: root.id, title: '测量放线' }
    calls.page.mockResolvedValue({ list: [root], total: 1 })
    calls.detail.mockResolvedValue({ ...detail(), task: root, nodes: [root, child] })
    calls.split.mockResolvedValue({ ...detail(), task: child, nodes: [root, child] })
    calls.checklist
      .mockRejectedValueOnce(new Error('计划服务暂不可用'))
      .mockResolvedValueOnce({ changed: ['created-child'], unchanged: [] })
    await mount(undefined, 'MINE')
    button(label).click()
    await flush()
    button('拆分子任务').click()
    await flush()
    const input = host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]')!
    input.value = '测量放线'
    input.dispatchEvent(new Event('input'))
    expect(host.querySelector('[aria-label="负责人安排"]')).toBeNull()
    expect(host.querySelector('[aria-label="加入哪份计划"]')).toBeNull()
    button('单独加入计划').click()
    await flush()
    required(host.querySelector<HTMLElement>(`[aria-label="加入哪份计划"] [value="${choice}"]`)).click()
    await flush()
    expect(host.querySelector('[aria-label="加入哪份计划"]')?.getAttribute('data-radio-value')).toBe(choice)
    expect(input.closest('[data-dialog]')).not.toBeNull()
    expect(host.querySelector('[data-task="inline:parent"]')).toBeNull()
    await flush()
    button('添加').click()
    await flush()
    expect(calls.split.mock.calls[0]?.[0].task.schedule).toEqual({
      mode: 'AUTO',
      fixedStart: null,
      fixedEnd: null,
      offsetDays: 0,
      durationDays: 1
    })
    expect(input.value).toBe('')
    expect(host.textContent).toContain('已创建，但未能纳入计划：计划服务暂不可用')
    button('添加').click()
    button('重试纳入计划').click()
    await flush()
    expect(calls.split).toHaveBeenCalledOnce()
    expect(calls.create).not.toHaveBeenCalled()
    expect(calls.plan).not.toHaveBeenCalled()
    expect(calls.checklistContext).toHaveBeenCalledOnce()
    expect(calls.checklist).toHaveBeenCalledTimes(2)
    expect(calls.checklist.mock.calls[1]?.[0]).toEqual(calls.checklist.mock.calls[0]?.[0])
    expect(calls.checklist.mock.calls[1]?.[0]).toMatchObject({
      ids: ['created-child'],
      period: 'WEEK',
      date,
      action: 'ADD',
      expectedVersions: { 'created-child': 4 },
      target: 'SELF'
    })
    expect(host.textContent).not.toContain('未能纳入计划')
    expect(calls.message.success).toHaveBeenLastCalledWith(`子任务已添加并纳入${label}`)
  })
  it('今日拆分默认随上级，添加后关闭弹窗，不另写子任务清单', async () => {
    const root = parent()
    const child = { ...root, id: 'created-child', parentId: root.id, title: '今天不做的新项' }
    calls.split.mockResolvedValue({ ...detail(), task: child, nodes: [root, child] })
    await mount(undefined, 'MINE')
    button('今日计划').click()
    await flush()
    button('拆分子任务').click()
    await flush()
    expect(host.querySelector('[aria-label="加入哪份计划"]')).toBeNull()
    expect(host.textContent).toContain('自动随本人上级计划')
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = child.title
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.split).toHaveBeenCalledOnce()
    expect(calls.checklist).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="子任务名称"]')).toBeNull()
    expect(calls.message.success).toHaveBeenCalledWith('子任务已添加，自动随本人上级计划')
  })
  it('添加并继续保留计划选择，仅清空名称；历史清单不提供拆分', async () => {
    const root = parent()
    const child = { ...root, id: 'created-child', parentId: root.id }
    calls.split.mockResolvedValue({ ...detail(), task: child, nodes: [root, child] })
    await mount(undefined, 'MINE')
    button('本周计划').click()
    await flush()
    button('拆分子任务').click()
    await flush()
    button('单独加入计划').click()
    await flush()
    required(host.querySelector<HTMLElement>('[aria-label="加入哪份计划"] [value="WEEK"]')).click()
    await flush()
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = '第一步'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加并继续').click()
    await flush()
    expect(input.value).toBe('')
    expect(host.querySelector('[aria-label="加入哪份计划"]')?.getAttribute('data-radio-value')).toBe('WEEK')
    expect(calls.checklist.mock.calls[0]?.[0]).toMatchObject({ ids: [child.id], period: 'WEEK', target: 'SELF' })
    button('取消').click()
    await flush()
    button('查看历史清单').click()
    await flush()
    expect(button('拆分子任务')).toBeUndefined()
  })
  it('拆分输入法确认不误提交，创建失败保留输入，同键重试后连续添加', async () => {
    const child = { ...parent(), id: 'created-child', parentId: 'parent', title: '现场测量' }
    calls.split.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValue({ ...detail(), task: child })
    await mount(undefined, 'MINE')
    button('拆分子任务').click()
    await flush()
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = child.title
    input.dispatchEvent(new Event('input'))
    await flush()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true }))
    await flush()
    expect(calls.split).not.toHaveBeenCalled()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    await flush()
    expect(input.value).toBe(child.title)
    expect(host.querySelector('[data-dialog]')?.textContent).toContain('连接中断')
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    await flush()
    expect(calls.split).toHaveBeenCalledTimes(2)
    expect(calls.split.mock.calls[0]?.[0]).toEqual(calls.split.mock.calls[1]?.[0])
    expect(input.value).toBe('')
    expect(host.querySelector('[aria-label="子任务名称"]')).not.toBeNull()
    expect(calls.checklist).not.toHaveBeenCalled()
  })
  it('全部任务显示随上级今日，不复制计划，新增成功只保留名称定位点、不装饰整行并保留展开', async () => {
    const root = {
      ...parent(),
      plans: [{ mode: 'CHECKLIST' as const, period: 'DAY' as const, date: dayjs().format('YYYY-MM-DD') }]
    }
    const child = { ...parent(), id: 'created-child', parentId: root.id, title: '新增子项' }
    calls.page.mockResolvedValue({ list: [root], total: 1 })
    calls.personalTreeChildren.mockResolvedValue([child])
    calls.split.mockResolvedValue({ ...detail(), task: child })
    await mount(undefined, 'MINE')
    button('拆分子任务').click()
    await flush()
    expect(host.querySelector('[aria-label="加入哪份计划"]')).toBeNull()
    expect(host.textContent).toContain('随上级纳入今日计划')
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="子任务名称"]'))
    input.value = child.title
    input.dispatchEvent(new Event('input'))
    await flush()
    button('添加').click()
    await flush()
    expect(calls.checklist).not.toHaveBeenCalled()
    const createdRow = required(host.querySelector('[data-task="created-child"]'))
    expect(createdRow.querySelectorAll('.task-list__created-anchor')).toHaveLength(1)
    expect(createdRow.querySelector('.task-list__created-anchor')?.textContent).toContain(child.title)
    expect(host.querySelector('.task-list__created-cell')).toBeNull()
    expect(host.querySelector('[data-task="parent"] .task-list__created-anchor')).toBeNull()
    expect(host.querySelector('[aria-label="子任务名称"]')).toBeNull()
  })
  it('业务条件改变后重新请求当前配置页范围', async () => {
    const props = await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    props.context!.conditions = {
      logic: 'AND',
      items: [{ type: 'condition', field: 'amount', operator: 'gt', value: 100 }]
    }
    await flush()
    expect(calls.pageTasks).toHaveBeenCalledTimes(2)
    expect(calls.pageTasks.mock.calls.at(-1)?.[0].conditions.items[0]).toMatchObject({ field: 'amount', value: 100 })
  })
  it('展开实例时保留配置记录上的显式关联身份和解除权限', async () => {
    calls.pageTasks.mockResolvedValue({ list: [{ ...parent(), explicitLinkId: 'link', canUnlink: true }], total: 1 })
    await mount({ applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record' })
    await openRowMenu()
    expect(button('解除关联')).toBeDefined()
    button('更多').click()
    await flush()
    calls.detail.mockResolvedValue(
      detail([parent(), { ...parent(), id: 'child', parentId: 'parent', title: '关联任务下的子任务' }])
    )
    await menuAction('＋ 子任务')
    await flush()
    await openRowMenu()
    expect(button('解除关联')).toBeDefined()
    expect(host.textContent).toContain('关联任务下的子任务')
  })
  it('统一任务列表不按历史类型分类或限制，查询不发送kind', async () => {
    calls.page.mockResolvedValue({
      list: [{ ...parent(), kind: 'ORDINARY', templateId: 'ordinary-template', childCount: 3 }],
      total: 1
    })
    await mount()
    expect(host.querySelector('[data-task="parent"]')).not.toBeNull()
    expect(host.textContent).not.toContain('普通任务')
    expect(host.textContent).not.toContain('流程任务')
    expect(host.querySelector('[aria-label="任务类型"]')).toBeNull()
    expect(calls.page.mock.calls.at(-1)?.[0]).not.toHaveProperty('kind')
  })
  it('仅归属应用的任务显示应用名称，不误标为独立任务', async () => {
    calls.applications.mockResolvedValueOnce([{ id: 'construction', name: '施工应用' }])
    calls.page.mockResolvedValue({ list: [{ ...parent(), applicationId: 'construction' }], total: 1 })
    await mount()
    expect(host.querySelector('[data-task="parent"]')?.textContent).toContain('施工应用')
    expect(host.querySelector('[data-task="parent"]')?.textContent).not.toContain('独立任务')
  })
})
