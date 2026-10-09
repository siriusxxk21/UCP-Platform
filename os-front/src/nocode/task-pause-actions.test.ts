// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App, type Component } from 'vue'
import TaskQuickAction from '@/views/nocode/task-center/TaskQuickAction.vue'
import TaskDetailView from '@/views/nocode/task-center/TaskDetail.vue'
import type { TaskDetail, TaskRow } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'

const api = vi.hoisted(() => ({
  detail: vi.fn(),
  members: vi.fn(),
  transition: vi.fn(),
  transitionRecovery: vi.fn(),
  readiness: vi.fn()
}))
const workflowApi = vi.hoisted(() => ({ source: vi.fn() }))
vi.mock('@/api/nocode/workflow-task-node', () => ({ createWorkflowTaskNodeApi: () => workflowApi }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'pause-actions-user' } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/nocode/task-confirmation', () => ({
  useTaskConfirmation: () => ({ confirmDiscard: async () => true })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/views/nocode/task-center/TaskAdjust.vue', () => ({
  default: defineComponent({
    props: ['readonly'],
    setup: (props, { expose }) => {
      expose({ requestClose: async () => true, dirty: false, busy: false })
      return () => h('div', { 'data-arrangement-readonly': String(props.readonly) })
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskEntryWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskSubmissionMaterial.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title'],
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', { 'data-dialog': props.title }, [slots.formItems?.(), slots.footer?.()]) : null
  })
}))

function row(overrides: Partial<TaskRow> = {}): TaskRow {
  return {
    ...newTaskNode(),
    id: 'pause-root',
    rootId: 'pause-root',
    title: '办公室装修',
    status: 'RUNNING',
    revision: 3,
    plans: [],
    canPause: true,
    canResume: false,
    canExecute: true,
    canEdit: false,
    ...overrides
  } as TaskRow
}
const detail = (task = row()): TaskDetail => ({ task, nodes: [task], comments: [], events: [] })
let app: App | undefined
let host: HTMLDivElement
async function flush() {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少当前测试目标')
  return value
}
const button = (label: string, surface: ParentNode = host) =>
  required(Array.from(surface.querySelectorAll('button')).find(element => element.textContent?.trim() === label))
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
    'AAlert',
    'AFormItem',
    'ATag',
    'ASpin',
    'ADescriptions',
    'ADescriptionsItem',
    'AEmpty',
    'ATimeline',
    'ATimelineItem',
    'AList',
    'AListItem',
    'ACollapse',
    'ACollapsePanel',
    'ATooltip',
    'ASelect',
    'ARadioGroup',
    'ARadioButton',
    'ATabs',
    'ATabPane',
    'ASpace',
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
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('textarea', {
            value: p.value,
            disabled: p.disabled,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value)
          })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  api.members.mockResolvedValue([])
  workflowApi.source.mockResolvedValue(null)
  api.detail.mockResolvedValue(detail())
  api.transition.mockResolvedValue(detail(row({ status: 'PAUSED', canPause: false, canResume: true })))
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  sessionStorage.clear()
})

describe('任务暂停与恢复', () => {
  it.each([
    { state: 'INVALIDATED', readOnlyReason: undefined, reason: '所属流程已结束' },
    { state: 'WAITING', readOnlyReason: '所属流程已挂起，请恢复流程后操作', reason: '所属流程已挂起' }
  ])('来源流程不可写时锁定任务执行、编排和评论：$state', async ({ state, readOnlyReason, reason }) => {
    workflowApi.source.mockResolvedValue({ state, readOnlyReason, nodeName: '装修任务', canViewProcess: false })
    api.detail.mockResolvedValue(
      detail(row({ canExecute: true, canPause: true, canEdit: true, canClaim: true, canAssign: true }))
    )
    await mount(TaskDetailView, { id: 'pause-root' })
    expect(host.textContent).toContain(reason)
    expect(host.querySelector('.task-detail__actions')).toBeNull()
    expect(host.querySelector('.task-comment__composer')).toBeNull()
    expect(host.querySelector('[data-arrangement-readonly="true"]')).not.toBeNull()
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('快捷暂停说明影响，原因可空且不调用完成预检', async () => {
    await mount(TaskQuickAction, { task: row(), action: 'PAUSE' })
    expect(host.textContent).toContain('下级')
    expect(host.textContent).toContain('不顺延')
    expect(button('确认暂停').disabled).toBe(false)
    button('确认暂停').click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'pause-root',
        action: 'PAUSE',
        note: '',
        expectedRevision: 3
      })
    )
    expect(api.readiness).not.toHaveBeenCalled()
  })
  it.each(['PAUSE', 'RESUME'] as const)('快捷%s只接受服务端能力，不能凭前端管理权限提交', async action => {
    await mount(TaskQuickAction, { task: row({ canPause: false, canResume: false }), action })
    const submit = button(action === 'PAUSE' ? '确认暂停' : '确认恢复')
    expect(submit.disabled).toBe(true)
    submit.click()
    await flush()
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('恢复失败重试保留原命令、原因与请求键，不启动独立暂停的下级', async () => {
    api.transition.mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce(detail(row()))
    await mount(TaskQuickAction, {
      task: row({ status: 'PAUSED', canPause: false, canResume: true }),
      action: 'RESUME'
    })
    expect(host.textContent).toContain('独立暂停')
    const note = required(host.querySelector('textarea'))
    note.value = '材料已到场'
    note.dispatchEvent(new Event('input'))
    await flush()
    button('确认恢复').click()
    await flush()
    const command = required(api.transition.mock.calls[0])[0]
    expect(note.disabled).toBe(true)
    button('确认原操作结果').click()
    await flush()
    expect(api.transition).toHaveBeenLastCalledWith(command)
    expect(command).toMatchObject({ action: 'RESUME', note: '材料已到场' })
  })
  it('详情暂停/恢复按钮严格服从服务端能力', async () => {
    api.detail.mockResolvedValue(detail(row({ canPause: false, canResume: false })))
    await mount(TaskDetailView, { id: 'pause-root' })
    const actions = required(host.querySelector('.task-detail__actions'))
    expect(actions.textContent).not.toContain('暂停任务')
    expect(actions.textContent).not.toContain('恢复任务')
  })
  it('详情暂停确认通过已有状态命令提交，不要求完成条件', async () => {
    await mount(TaskDetailView, { id: 'pause-root' })
    button('暂停任务').click()
    await flush()
    const surface = required(host.querySelector('[data-dialog="暂停任务"]'))
    expect(surface.textContent).toContain('不顺延')
    button('确认暂停', surface).click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(expect.objectContaining({ action: 'PAUSE', expectedRevision: 3 }))
    expect(api.readiness).not.toHaveBeenCalled()
  })
  it('祖先暂停时详情显示有效暂停状态和原因，不能恢复祖先或完成子任务', async () => {
    api.detail.mockResolvedValue(
      detail(
        row({
          id: 'child',
          rootId: 'pause-root',
          status: 'RUNNING',
          canPause: false,
          canResume: false,
          canExecute: false,
          pausedByTaskId: 'pause-root',
          pauseReason: '上级任务已暂停，请先恢复上级任务'
        })
      )
    )
    await mount(TaskDetailView, { id: 'child' })
    expect(host.textContent).toContain('已暂停')
    expect(host.textContent).toContain('上级任务已暂停，请先恢复上级任务')
    expect(host.querySelector('.task-detail__actions')).toBeNull()
  })
})
