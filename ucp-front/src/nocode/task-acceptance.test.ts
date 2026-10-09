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
  type App,
  type Component
} from 'vue'
import TaskDetailView from '@/views/nocode/task-center/TaskDetail.vue'
import TaskQuickAction from '@/views/nocode/task-center/TaskQuickAction.vue'
import TaskList from '@/views/nocode/task-center/TaskList.vue'
import type { TaskDetail, TaskRow } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'

const api = vi.hoisted(() => ({
  detail: vi.fn(),
  material: vi.fn(),
  members: vi.fn(),
  transition: vi.fn(),
  readiness: vi.fn(),
  page: vi.fn(),
  personalTreePage: vi.fn(),
  personalTreeChildren: vi.fn(),
  mine: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: api, runtime: { mine: api.mine } })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'acceptor' } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/api/nocode/workflow-task-node', () => ({
  createWorkflowTaskNodeApi: () => ({ source: () => Promise.resolve(null) })
}))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/nocode/task-confirmation', () => ({
  useTaskConfirmation: () => ({ confirmDiscard: async () => true })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/views/nocode/task-center/TaskContext.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskDag.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskEntryWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskSubmissionMaterial.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title', 'showFooter', 'okText', 'loading'],
    emits: ['ok', 'cancel'],
    setup:
      (props, { slots, emit }) =>
      () =>
        props.open
          ? h('section', { 'data-dialog': props.title }, [
              h('h2', props.title),
              slots.formItems?.(),
              slots.footer?.() ||
                (props.showFooter === false
                  ? null
                  : h('button', { disabled: props.loading, onClick: () => emit('ok') }, props.okText || '确定'))
            ])
          : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.search?.(), slots.actions?.(), slots.empty?.()])
  })
}))

function row(overrides: Partial<TaskRow> = {}): TaskRow {
  return {
    ...newTaskNode(),
    id: 'root',
    rootId: 'root',
    title: '采购设备',
    creatorId: 'owner',
    creatorName: '负责人',
    assigneeId: 'owner',
    assigneeName: '负责人',
    acceptorId: 'acceptor',
    acceptorName: '验收人',
    status: 'PENDING_ACCEPTANCE',
    project: null,
    business: null,
    baselineStart: null,
    baselineEnd: null,
    expectedStart: null,
    expectedEnd: null,
    actualStart: '2026-10-01T08:00:00',
    actualEnd: null,
    createdAt: '',
    revision: 7,
    instanceRevision: 7,
    childCount: 0,
    plans: [],
    canStart: false,
    canExecute: false,
    canEdit: false,
    canAccept: true,
    blockedReason: null,
    templateId: null,
    templateVersion: null,
    ...overrides
  }
}
function detail(task = row()): TaskDetail {
  return { task, nodes: [task], comments: [], events: [] }
}
let app: App | undefined, host: HTMLDivElement
async function flush() {
  for (let index = 0; index < 15; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少当前测试目标')
  return value
}
function button(label: string, root: ParentNode = host) {
  return required(
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      element => element.textContent?.trim() === label
    )
  )
}
function dialog(title: string) {
  return required(host.querySelector<HTMLElement>(`[data-dialog="${title}"]`))
}
async function enterNote(surface: ParentNode, text: string) {
  const field = required(surface.querySelector<HTMLTextAreaElement>('textarea'))
  field.value = text
  field.dispatchEvent(new Event('input'))
  await flush()
}
async function mount(component: Component, props: Record<string, unknown>) {
  app = createApp(() => h(component, props))
  const plain = defineComponent({
    props: ['label', 'message', 'description'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.label, p.message, p.description, slots.default?.()])
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'AAlert',
    'ASpin',
    'ADescriptions',
    'ADescriptionsItem',
    'ASpace',
    'ATag',
    'AEmpty',
    'ATimeline',
    'ATimelineItem',
    'AList',
    'AListItem',
    'ACollapse',
    'ACollapsePanel',
    'APopconfirm',
    'ATooltip',
    'ASelect',
    'AInputNumber',
    'ADatePicker',
    'ARadioGroup',
    'ARadioButton',
    'ACheckbox'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled || p.loading }, slots.default?.())
    })
  )
  for (const name of ['AInput', 'ATextarea'])
    app.component(
      name,
      defineComponent({
        props: ['value'],
        emits: ['update:value'],
        setup:
          (p, { emit, attrs }) =>
          () =>
            h(name === 'ATextarea' ? 'textarea' : 'input', {
              ...attrs,
              value: p.value,
              onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
            })
      })
    )
  app.component(
    'ATabs',
    defineComponent({
      emits: ['change', 'update:activeKey'],
      setup: (_, { emit, slots }) => {
        provide('acceptance-tabs', (key: string) => {
          emit('change', key)
          emit('update:activeKey', key)
        })
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup: (p, { slots }) => {
        const select = inject<(key: string) => void>('acceptance-tabs')
        const key = String(getCurrentInstance()?.vnode.key)
        return () => h('div', [h('button', { onClick: () => select?.(key) }, p.tab), slots.default?.()])
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.members.mockResolvedValue([])
  api.mine.mockResolvedValue([])
  api.detail.mockResolvedValue(detail())
  api.material.mockResolvedValue({ eventId: 'submit-2', binding: null, model: null, record: null, entries: [] })
  api.transition.mockResolvedValue(detail(row({ status: 'COMPLETED', canAccept: false })))
  api.readiness.mockResolvedValue({
    taskId: 'root',
    revision: 7,
    canComplete: true,
    checks: [],
    canCancel: false,
    cancellationImpacts: []
  })
  api.page.mockResolvedValue({ list: [], total: 0 })
  api.personalTreePage.mockResolvedValue({ list: [], total: 0 })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('任务验收的角色边界与操作提交', () => {
  it.each([true, false])('待验收操作严格服从服务端 canAccept=%s，不因管理权限开放', async canAccept => {
    api.detail.mockResolvedValue(detail(row({ canAccept })))
    await mount(TaskDetailView, { id: 'root' })
    const actions = host.querySelector('.task-detail__actions')?.textContent || ''
    expect(actions.includes('验收通过')).toBe(canAccept)
    expect(actions.includes('退回修改')).toBe(canAccept)
    expect(actions).not.toContain('完成任务')
    expect(actions).not.toContain('开始执行')
  })
  it('验收通过使用 APPROVE 和当前版本，不误用负责人完成操作', async () => {
    await mount(TaskDetailView, { id: 'root' })
    button('验收通过').click()
    await flush()
    const surface = dialog('验收通过')
    await enterNote(surface, '设备及发票核对通过')
    button('验收通过', surface).click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'root',
        expectedRevision: 7,
        action: 'APPROVE',
        note: '设备及发票核对通过',
        requestKey: expect.any(String)
      })
    )
    expect(api.readiness).not.toHaveBeenCalled()
  })
  it('空白退回原因不能提交，填写后提交 REJECT 并保留原因', async () => {
    api.transition.mockResolvedValue(detail(row({ status: 'RUNNING', canAccept: false })))
    await mount(TaskDetailView, { id: 'root' })
    button('退回修改').click()
    await flush()
    const surface = dialog('退回修改')
    const submit = button('退回修改', surface)
    expect(submit.disabled).toBe(true)
    await enterNote(surface, '  \n  ')
    expect(submit.disabled).toBe(true)
    submit.click()
    await flush()
    expect(api.transition).not.toHaveBeenCalled()
    await enterNote(surface, '请补齐设备序列号')
    expect(submit.disabled).toBe(false)
    submit.click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'root',
        expectedRevision: 7,
        action: 'REJECT',
        note: '请补齐设备序列号',
        requestKey: expect.any(String)
      })
    )
  })
  it('毫秒事件时间下二次提交展示最新说明，并打开第二轮提交材料', async () => {
    const value = detail()
    value.events = [
      {
        id: 'submit-1',
        taskId: 'root',
        type: 'SUBMITTED_FOR_ACCEPTANCE',
        actorId: 'owner',
        actorName: '负责人',
        note: '第一次提交设备资料',
        createdAt: Date.parse('2026-10-03T08:00:00+08:00') as unknown as string
      },
      {
        id: 'reject-1',
        taskId: 'root',
        type: 'REJECTED',
        actorId: 'acceptor',
        actorName: '验收人',
        note: '请补齐设备序列号',
        createdAt: Date.parse('2026-10-03T09:00:00+08:00') as unknown as string
      },
      {
        id: 'submit-2',
        taskId: 'root',
        type: 'SUBMITTED_FOR_ACCEPTANCE',
        actorId: 'owner',
        actorName: '负责人',
        note: '第二次提交，已补齐设备序列号',
        createdAt: Date.parse('2026-10-03T10:00:00+08:00') as unknown as string
      }
    ]
    api.detail.mockResolvedValue(value)
    await mount(TaskDetailView, { id: 'root' })
    const summary = required(host.querySelector('[aria-label="最近提交说明"]'))
    expect(summary.textContent).toContain('第二次提交，已补齐设备序列号')
    expect(summary.textContent).not.toContain('第一次提交设备资料')
    button('查看本次提交材料').click()
    await flush()
    expect(api.material).toHaveBeenCalledExactlyOnceWith('root', 'submit-2')
    expect(dialog('当次提交材料')).toBeDefined()
  })
  it('负责人快捷完成显示提交验收，检查完成条件后沿 COMPLETE 提交', async () => {
    await mount(TaskQuickAction, {
      task: row({ status: 'RUNNING', canExecute: true, canAccept: false }),
      action: 'COMPLETE'
    })
    expect(host.textContent).toContain('提交验收：采购设备')
    expect(host.textContent).toContain('通过后任务才会完成')
    expect(api.readiness).toHaveBeenCalledWith('root')
    button('确认提交验收').click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'root', expectedRevision: 7, action: 'COMPLETE' })
    )
  })
  it('我的计划包含相关验收工作，待验收按本人验收资格查询完整任务组', async () => {
    await mount(TaskList, { scope: 'MINE' })
    expect(api.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'ALL', planMode: 'CHECKLIST' })
    )
    expect(api.personalTreePage.mock.calls.at(-1)?.[0]).not.toHaveProperty('status')
    const tabs = Array.from(host.querySelectorAll('button')).map(item => item.textContent?.trim())
    expect(tabs).not.toContain('待我验收')
    expect(tabs).not.toContain('待他人验收')
    api.personalTreePage.mockClear()
    button('待验收').click()
    await flush()
    expect(api.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'ACCEPTANCE', status: 'PENDING_ACCEPTANCE' })
    )
    expect(api.page).not.toHaveBeenCalled()
    expect(api.personalTreeChildren).not.toHaveBeenCalled()
    button('我的计划').click()
    await flush()
    expect(api.personalTreePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE', tab: 'ALL', planMode: 'CHECKLIST' })
    )
    expect(api.personalTreePage.mock.calls.at(-1)?.[0]).not.toHaveProperty('status')
  })
})
