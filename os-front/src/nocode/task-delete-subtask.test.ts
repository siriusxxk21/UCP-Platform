// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App, type Component } from 'vue'
import TaskDeleteSubtaskDialog from '@/views/nocode/task-center/TaskDeleteSubtaskDialog.vue'
import TaskDetailView from '@/views/nocode/task-center/TaskDetail.vue'
import TaskAdjust from '@/views/nocode/task-center/TaskAdjust.vue'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import type { TaskDetail, TaskPlan, TaskRow } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'
import { taskPlanHistoryLabel } from './task-checklist'

const api = vi.hoisted(() => ({ detail: vi.fn(), members: vi.fn(), deleteSubtask: vi.fn() }))
const confirmation = vi.hoisted(() => ({ confirmDiscard: vi.fn() }))
const workflowApi = vi.hoisted(() => ({ source: vi.fn() }))
vi.mock('@/api/nocode/workflow-task-node', () => ({ createWorkflowTaskNodeApi: () => workflowApi }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'employee' } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: () => false }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/nocode/task-confirmation', () => ({ useTaskConfirmation: () => confirmation }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskEntryWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskSubmissionMaterial.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskNodeEditor.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'selectedId'],
    emits: ['update:selectedId'],
    setup: (props, { emit, expose }) => {
      expose({ captureView: () => undefined, restoreView: vi.fn() })
      return () =>
        h(
          'div',
          props.modelValue.map((node: TaskRow) =>
            h(
              'button',
              {
                'data-select-node': node.id,
                onClick: () => emit('update:selectedId', node.id)
              },
              `选中${node.title}`
            )
          )
        )
    }
  })
}))
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
    id: 'child',
    rootId: 'root',
    parentId: 'root',
    title: '个人拆分检查项',
    assigneeId: 'employee',
    creatorId: 'employee',
    status: 'PENDING',
    revision: 3,
    instanceRevision: 5,
    plans: [],
    canDelete: true,
    canExecute: true,
    canEdit: false,
    ...overrides
  } as TaskRow
}
const root = () => row({ id: 'root', parentId: null, title: '施工任务', creatorId: 'manager', canDelete: false })
const detail = (task = row(), nodes = [root(), task]): TaskDetail => ({ task, nodes, comments: [], events: [] })
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
const buttons = (label: string, surface: ParentNode = host) =>
  Array.from(surface.querySelectorAll('button')).filter(element => element.textContent?.trim() === label)
const button = (label: string, surface: ParentNode = host) => required(buttons(label, surface)[0])
const deleteDialog = () => required(host.querySelector('[data-dialog="删除子任务"]'))
async function mount(component: Component, properties: Record<string, unknown>) {
  const props = reactive(properties)
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
    'ADatePicker',
    'AForm',
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
  return props
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  confirmation.confirmDiscard.mockResolvedValue(true)
  api.members.mockResolvedValue([])
  api.deleteSubtask.mockResolvedValue(true)
  api.detail.mockResolvedValue(detail())
  workflowApi.source.mockResolvedValue(null)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  sessionStorage.clear()
})

describe('员工删除本人拆分子任务', () => {
  it('删除归档的计划保留日期并显示任务已删除原因', () => {
    expect(
      taskPlanHistoryLabel({
        mode: 'CHECKLIST',
        period: 'WEEK',
        date: '2026-10-05',
        historyReason: 'TASK_DELETED'
      } as TaskPlan)
    ).toBe('过往清单 · 周 · 2026-10-05 · 任务已删除')
  })
  it('提交显式删除命令，携带并发版本和幂等键，不调用整组调整', async () => {
    const post = vi.fn().mockResolvedValue(true)
    const client = { post } as unknown as NocodeHttpClient
    const command = { id: 'child', expectedRevision: 3, requestKey: 'stable-delete-request' }
    expect(await createTaskCenterApi(client).deleteSubtask(command)).toBe(true)
    expect(post).toHaveBeenCalledExactlyOnceWith('/nocode/tasks/delete-subtask', command)
  })
  it('确认明确命名和删除范围，提交成功后通知调用方刷新并关闭', async () => {
    const onDeleted = vi.fn(),
      onClose = vi.fn()
    await mount(TaskDeleteSubtaskDialog, { task: row(), onDeleted, onClose })
    expect(host.textContent).toContain('个人拆分检查项')
    expect(host.textContent).toContain('不会级联删除其他任务，也不会删除业务数据')
    expect(api.deleteSubtask).not.toHaveBeenCalled()
    button('确认删除').click()
    await flush()
    expect(api.deleteSubtask).toHaveBeenCalledWith({ id: 'child', expectedRevision: 3, requestKey: expect.any(String) })
    expect(onDeleted).toHaveBeenCalledExactlyOnceWith('child')
    expect(onClose).toHaveBeenCalledTimes(1)
  })
  it.each([false, undefined])('服务端未允许删除（%s）时即使本人且待处理也不发送请求', async canDelete => {
    await mount(TaskDeleteSubtaskDialog, { task: row({ canDelete, deleteBlockedReason: '原始模板任务不可删除' }) })
    expect(host.textContent).toContain('原始模板任务不可删除')
    expect(button('确认删除').disabled).toBe(true)
    button('确认删除').click()
    await flush()
    expect(api.deleteSubtask).not.toHaveBeenCalled()
  })
  it('协调者删除已分给同事的安全子任务时明确展示当前负责人', async () => {
    await mount(TaskDeleteSubtaskDialog, {
      task: row({ assigneeId: 'coworker', assigneeName: '柯伟', canDelete: true })
    })
    expect(host.textContent).toContain('当前负责人：柯伟')
    expect(button('确认删除').disabled).toBe(false)
    button('确认删除').click()
    await flush()
    expect(api.deleteSubtask).toHaveBeenCalledOnce()
  })
  it('失败保留弹窗和原因，重试沿用同一请求身份', async () => {
    api.deleteSubtask.mockRejectedValueOnce(new Error('任务已有办理记录，不可删除')).mockResolvedValueOnce(true)
    const onClose = vi.fn()
    await mount(TaskDeleteSubtaskDialog, { task: row(), onClose })
    button('确认删除').click()
    await flush()
    expect(deleteDialog().textContent).toContain('任务已有办理记录，不可删除')
    expect(onClose).not.toHaveBeenCalled()
    const command = required(api.deleteSubtask.mock.calls[0])[0]
    button('确认删除').click()
    await flush()
    expect(api.deleteSubtask).toHaveBeenLastCalledWith(command)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
  it('请求进行中拒绝重复提交和关闭，未确认成功不关闭', async () => {
    let resolve!: (deleted: boolean) => void
    api.deleteSubtask.mockReturnValue(
      new Promise<boolean>(done => {
        resolve = done
      })
    )
    const onClose = vi.fn(),
      onDeleted = vi.fn()
    await mount(TaskDeleteSubtaskDialog, { task: row(), onClose, onDeleted })
    button('确认删除').click()
    button('确认删除').click()
    await flush()
    expect(button('取消').disabled).toBe(true)
    expect(api.deleteSubtask).toHaveBeenCalledTimes(1)
    resolve(false)
    await flush()
    expect(host.textContent).toContain('未能确认删除结果')
    expect(onClose).not.toHaveBeenCalled()
    expect(onDeleted).not.toHaveBeenCalled()
  })
  it('编排删除当前节点后关闭详情并刷新，不继续读取已删除节点', async () => {
    const onClose = vi.fn(),
      onChanged = vi.fn()
    await mount(TaskDetailView, { id: 'child', employeeView: true, onClose, onChanged })
    button('删除子任务', required(host.querySelector('.task-adjust__selection'))).click()
    await flush()
    button('确认删除', deleteDialog()).click()
    await flush()
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(api.detail).toHaveBeenCalledTimes(1)
    expect(host.textContent).not.toContain('当前选中：个人拆分检查项')
  })
  it('详情顶部不再出现子任务删除入口与禁用说明，编排保留原权限边界', async () => {
    api.detail.mockResolvedValue(detail(row({ canDelete: false, deleteBlockedReason: '原始模板任务不可删除' })))
    const props = await mount(TaskDetailView, { id: 'child', employeeView: true })
    const actions = required(host.querySelector('.task-detail__actions'))
    expect(buttons('删除子任务', actions)).toHaveLength(0)
    expect(actions.textContent).not.toContain('原始模板任务不可删除')
    expect(button('删除子任务', required(host.querySelector('.task-adjust__selection'))).disabled).toBe(true)
    props.employeeView = false
    await flush()
    expect(buttons('删除子任务')).toHaveLength(0)
    expect(api.deleteSubtask).not.toHaveBeenCalled()
  })
  it('流程已锁定时不能从任务详情或编排绕过只读边界', async () => {
    workflowApi.source.mockResolvedValue({
      state: 'INVALIDATED',
      readOnlyReason: '所属流程已结束',
      canViewProcess: false
    })
    await mount(TaskDetailView, { id: 'child', employeeView: true })
    for (const action of buttons('删除子任务')) expect(action.disabled).toBe(true)
    expect(host.textContent).toContain('所属流程已结束')
    expect(api.deleteSubtask).not.toHaveBeenCalled()
  })
  it('编排列表和图共用服务端选中节点能力，不允许误删其他节点', async () => {
    const template = row({
      id: 'template-child',
      title: '模板节点',
      canDelete: false,
      deleteBlockedReason: '原始模板任务不可删除'
    })
    const onDeleteSubtask = vi.fn()
    const props = await mount(TaskAdjust, {
      detail: detail(row(), [root(), row(), template]),
      employeeView: true,
      onDeleteSubtask
    })
    button('删除子任务').click()
    expect(onDeleteSubtask).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'child', canDelete: true }))
    required(host.querySelector<HTMLButtonElement>('[data-select-node="template-child"]')).click()
    await flush()
    expect(button('删除子任务').disabled).toBe(true)
    expect(host.textContent).toContain('原始模板任务不可删除')
    props.view = 'graph'
    await flush()
    expect(button('删除子任务').disabled).toBe(true)
    expect(onDeleteSubtask).toHaveBeenCalledTimes(1)
  })
  it('从总任务编排删除其他节点，保留总任务详情并刷新编排选中状态', async () => {
    api.detail.mockResolvedValueOnce(detail(root(), [root(), row()])).mockResolvedValueOnce(detail(root(), [root()]))
    const onClose = vi.fn(),
      onChanged = vi.fn()
    await mount(TaskDetailView, { id: 'root', employeeView: true, onClose, onChanged })
    required(host.querySelector<HTMLButtonElement>('[data-select-node="child"]')).click()
    await flush()
    button('删除子任务', required(host.querySelector('.task-adjust__selection'))).click()
    await flush()
    button('确认删除', deleteDialog()).click()
    await flush()
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
    expect(api.detail).toHaveBeenNthCalledWith(2, 'root')
    expect(host.querySelector('[data-select-node="child"]')).toBeNull()
    expect(host.textContent).toContain('当前选中：施工任务')
  })
  it('删除后刷新失败也清除已删除的编排节点，保留失败提示供重试', async () => {
    api.detail.mockResolvedValueOnce(detail(root(), [root(), row()])).mockRejectedValueOnce(new Error('刷新任务失败'))
    const onChanged = vi.fn()
    await mount(TaskDetailView, { id: 'root', employeeView: true, onChanged })
    required(host.querySelector<HTMLButtonElement>('[data-select-node="child"]')).click()
    await flush()
    button('删除子任务', required(host.querySelector('.task-adjust__selection'))).click()
    await flush()
    button('确认删除', deleteDialog()).click()
    await flush()
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('刷新任务失败')
    expect(host.querySelector('[data-select-node="child"]')).toBeNull()
    expect(host.textContent).toContain('当前选中：施工任务')
  })
  it('取消放弃评论时不进入删除确认，更不发送删除请求', async () => {
    await mount(TaskDetailView, { id: 'child', employeeView: true })
    const comment = required(host.querySelector<HTMLTextAreaElement>('.task-comment__composer textarea'))
    comment.value = '待保存的现场记录'
    comment.dispatchEvent(new Event('input'))
    await flush()
    confirmation.confirmDiscard.mockResolvedValueOnce(false)
    button('删除子任务', required(host.querySelector('.task-adjust__selection'))).click()
    await flush()
    expect(confirmation.confirmDiscard).toHaveBeenCalledWith(true, '删除任务并放弃尚未发表的评论？')
    expect(host.querySelector('[data-dialog="删除子任务"]')).toBeNull()
    expect(comment.value).toBe('待保存的现场记录')
    expect(api.deleteSubtask).not.toHaveBeenCalled()
  })
})
