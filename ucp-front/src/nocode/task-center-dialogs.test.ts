// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createApp,
  defineComponent,
  getCurrentInstance,
  h,
  inject,
  isReactive,
  nextTick,
  provide,
  ref,
  watch,
  type App,
  type Component
} from 'vue'
import TaskPlanDialog from '@/views/nocode/task-center/TaskPlanDialog.vue'
import TaskNodeEditor from '@/views/nocode/task-center/TaskNodeEditor.vue'
import TaskTemplates from '@/views/nocode/task-center/TaskTemplates.vue'
import TaskDetail from '@/views/nocode/task-center/TaskDetail.vue'
import TaskAdjust from '@/views/nocode/task-center/TaskAdjust.vue'
import TaskQuickAction from '@/views/nocode/task-center/TaskQuickAction.vue'
import TaskLinkPicker from '@/views/nocode/task-center/TaskLinkPicker.vue'
import TaskLaunch from '@/views/nocode/task-center/TaskLaunch.vue'
import { newTaskNode } from './task-center'
import type { TaskAdjustmentPreview, TaskDetail as Detail, TaskRow } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  members: vi.fn(),
  templates: vi.fn(),
  templateInstances: vi.fn(async () => ({ list: [], total: 0 })),
  detail: vi.fn(),
  material: vi.fn(),
  plan: vi.fn(),
  checklistContext: vi.fn(),
  checklist: vi.fn(),
  comment: vi.fn(),
  transition: vi.fn(),
  transitionRecovery: vi.fn(),
  readiness: vi.fn(),
  recordLinkCandidates: vi.fn(),
  recordLink: vi.fn(),
  entryClose: vi.fn(),
  businessClose: vi.fn(),
  create: vi.fn(),
  schedulePreview: vi.fn(),
  mine: vi.fn(),
  application: vi.fn(),
  draftGet: vi.fn(),
  adjustPreview: vi.fn(),
  adjust: vi.fn(),
  saveTemplate: vi.fn()
}))
const workspace = vi.hoisted(() => ({
  resume: null as { taskId: string; entryKey: string; contributionId: string } | null
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: api, runtime: { mine: api.mine, application: api.application } })
}))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/api/nocode/workflow-task-node', () => ({
  createWorkflowTaskNodeApi: () => ({ source: () => Promise.resolve(null) })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: '2104779219579535361' } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/views/nocode/task-center/TaskBindingPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskRecordPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({
  default: defineComponent({
    props: ['task'],
    setup: (props, { expose }) => {
      expose({ requestClose: api.businessClose })
      return () =>
        h(
          'div',
          { 'data-business-form-task': props.task.id, 'data-business-can-execute': String(props.task.canExecute) },
          '真实业务表单插槽'
        )
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskEntryWorkspace.vue', () => ({
  default: defineComponent({
    props: ['task', 'initialEntryKey', 'initialContributionId'],
    emits: ['resume'],
    setup: (props, { expose, emit }) => {
      expose({ requestClose: api.entryClose, prepareAction: api.entryClose, select: vi.fn() })
      return () =>
        h(
          'div',
          {
            'data-business-cards': '',
            'data-task-id': props.task.id,
            'data-entry-key': props.initialEntryKey,
            'data-contribution-id': props.initialContributionId,
            'data-policy': JSON.stringify(props.task.dataPolicy)
          },
          [
            '业务办理卡片插槽',
            workspace.resume
              ? h('button', { onClick: () => emit('resume', workspace.resume) }, '回原任务继续办理')
              : null
          ]
        )
    }
  })
}))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: { render: () => h('div', '冻结业务材料插槽') }
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'customRow'],
    setup(props, { slots }) {
      const renderRows = (records: Array<TaskRow & { children?: TaskRow[] }>): ReturnType<typeof h>[] =>
        records.flatMap(record => [
          h(
            'div',
            { ...props.customRow?.(record), 'data-row-key': record.id },
            props.columns.map((column: { key: string }) => slots.bodyCell?.({ column, record }))
          ),
          ...renderRows(record.children || [])
        ])
      return () => h('div', [slots.actions?.(), ...renderRows(props.dataSource)])
    }
  })
}))
const row = (): TaskRow => ({
  ...newTaskNode(),
  id: 'root',
  rootId: 'root',
  title: '施工任务',
  creatorId: '2104779219579535361',
  assigneeId: '2104779219579535361',
  creatorName: '张伟',
  assigneeName: '张伟',
  status: 'RUNNING',
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
  canStart: false,
  canExecute: true,
  canEdit: true,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
const detail = (): Detail => ({ task: row(), nodes: [row()], comments: [], events: [] })
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  Array.from(host.querySelectorAll('button')).find(el => el.textContent?.trim() === label)!
const actionButton = (label: string) =>
  required(
    Array.from(host.querySelectorAll<HTMLButtonElement>('button:not([role])')).find(
      el => el.textContent?.trim() === label
    )
  )
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少当前测试目标')
  return value
}
async function openRootNameEditor() {
  required(host.querySelector<HTMLButtonElement>('[aria-label="编辑任务名称 1"]')).click()
  await flush()
  return required(host.querySelector<HTMLInputElement>('input[aria-label="总任务名称 1"]'))
}
async function changeRootName(title: string) {
  const name = await openRootNameEditor()
  name.value = title
  name.dispatchEvent(new Event('input'))
  await flush()
}
async function inspectRow(id: string) {
  button('任务编排').click()
  await flush()
  required(host.querySelector<HTMLElement>(`[data-row-key="${id}"]`)).click()
  await flush()
  required(host.querySelector<HTMLButtonElement>('.task-adjust__selection button')).click()
  await flush()
}
async function launchWithSchedule() {
  const start = required(host.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]'))
  start.value = '2026-10-12'
  start.dispatchEvent(new Event('input'))
  await flush()
  button('加入任务池').click()
  await flush()
  expect(api.create).not.toHaveBeenCalled()
  button('确认并加入任务池').click()
  await flush()
}
async function mount(component: Component, props: Record<string, unknown> = {}) {
  app = createApp(() => h(component, props))
  // 保留真实 OsModalForm，只替换 AntD 的传送和动画外壳；默认 slot 无法替代 formItems。
  const surface = defineComponent({
    props: ['open'],
    setup:
      (p, { slots }) =>
      () =>
        p.open ? h('section', [slots.title?.(), slots.default?.(), slots.footer?.()]) : null
  })
  app.component(
    'AModal',
    defineComponent({
      extends: surface,
      inheritAttrs: true,
      setup:
        (p, { slots }) =>
        () =>
          p.open
            ? h('section', { 'data-surface': 'modal' }, [slots.title?.(), slots.default?.(), slots.footer?.()])
            : null
    })
  )
  app.component(
    'ADrawer',
    defineComponent({
      extends: surface,
      inheritAttrs: true,
      setup:
        (p, { slots }) =>
        () =>
          p.open
            ? h('section', { 'data-surface': 'drawer' }, [
                slots.title?.(),
                slots.extra?.(),
                slots.default?.(),
                slots.footer?.()
              ])
            : null
    })
  )
  const plain = defineComponent({
    props: ['label', 'message'],
    setup: (p, { slots, expose }) => {
      expose({ validate: async () => true, resetFields: vi.fn() })
      return () => h('div', [p.label, p.message, slots.default?.()])
    }
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ARadio',
    'ASelect',
    'AInputNumber',
    'AInputSearch',
    'ATag',
    'AAlert',
    'ASpin',
    'ADescriptions',
    'ADescriptionsItem',
    'ASpace',
    'ADropdown',
    'AMenu',
    'AMenuItem',
    'ATimeline',
    'ATimelineItem',
    'AEmpty',
    'ATooltip',
    'APopconfirm',
    'AList',
    'AListItem',
    'ACollapse',
    'ACollapsePanel',
    'ATypographyText'
  ])
    app.component(name, plain)
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup(props, { slots, emit }) {
        provide('dialog-radio', { props, update: (value: string) => emit('update:value', value) })
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup(props, { slots }) {
        const group = required(inject<{ props: { value: string }; update: (value: string) => void }>('dialog-radio'))
        return () =>
          h(
            'button',
            {
              role: 'radio',
              'aria-checked': group.props.value === props.value,
              onClick: () => group.update(props.value)
            },
            slots.default?.()
          )
      }
    })
  )
  app.component(
    'ATabs',
    defineComponent({
      props: ['activeKey'],
      emits: ['update:activeKey'],
      setup(props, { slots, emit }) {
        provide('dialog-tabs', { props, update: (key: string) => emit('update:activeKey', key) })
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup(props, { slots }) {
        const tabs = required(inject<{ props: { activeKey: string }; update: (key: string) => void }>('dialog-tabs'))
        const key = String(required(getCurrentInstance()).vnode.key)
        const visited = ref(false)
        watch(
          () => tabs.props.activeKey,
          value => {
            if (value === key) visited.value = true
          },
          { immediate: true }
        )
        return () =>
          h('section', { 'data-pane': key }, [
            h(
              'button',
              { role: 'tab', 'aria-selected': tabs.props.activeKey === key, onClick: () => tabs.update(key) },
              props.tab
            ),
            visited.value
              ? h('div', { style: { display: tabs.props.activeKey === key ? '' : 'none' } }, slots.default?.())
              : null
          ])
      }
    })
  )
  app.component(
    'ADatePicker',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            value: p.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ACheckbox',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['update:checked'],
      setup:
        (p, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: p.checked,
              disabled: p.disabled,
              onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked)
            }),
            slots.default?.()
          ])
    })
  )
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
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value', 'pressEnter'],
      setup: (p, { emit, expose }) => {
        const input = ref<HTMLInputElement>()
        expose({ focus: () => input.value?.focus() })
        return () =>
          h('input', {
            ref: input,
            value: p.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value),
            onKeydown: (e: KeyboardEvent) => {
              if (e.key === 'Enter') emit('pressEnter', e)
            }
          })
      }
    })
  )
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('textarea', {
            value: p.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value)
          })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
async function reopenDetail(id: string) {
  app?.unmount()
  host.remove()
  await mount(TaskDetail, { id, employeeView: true })
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] })
  sessionStorage.clear()
  vi.resetAllMocks()
  workspace.resume = null
  api.templateInstances.mockResolvedValue({ list: [], total: 0 })
  api.businessClose.mockResolvedValue(true)
  api.entryClose.mockResolvedValue(true)
  api.readiness.mockResolvedValue({
    taskId: 'root',
    revision: 1,
    canComplete: true,
    checks: [],
    canCancel: true,
    cancelBlockedReason: null,
    cancellationImpacts: []
  })
  api.mine.mockResolvedValue([])
  api.application.mockImplementation(async (id: string) => ({
    application: { id, name: '草稿关联施工应用' },
    definition: { resources: [] }
  }))
  api.members.mockResolvedValue([])
  api.templates.mockResolvedValue([])
  api.detail.mockResolvedValue(detail())
  api.plan.mockResolvedValue(true)
  api.checklistContext.mockResolvedValue({
    today: '2026-10-02',
    weekStart: '2026-09-28',
    weekEnd: '2026-10-04',
    items: [
      {
        taskId: 'root',
        title: '施工任务',
        status: 'PENDING',
        assigneeId: '2104779219579535361',
        version: 6,
        todayPlans: [],
        weekPlans: [],
        history: [],
        canAdd: true,
        canCancel: false,
        readOnly: false,
        reason: null,
        warnings: []
      }
    ]
  })
  api.checklist.mockResolvedValue({ changed: ['root'], unchanged: [] })
  api.create.mockResolvedValue(detail())
  api.schedulePreview.mockImplementation(async ({ nodes }) => ({
    nodes: nodes.map((node: { id: string; title: string }) => ({
      ...node,
      expectedStart: '2026-10-12T00:00:00',
      expectedEnd: '2026-10-13T00:00:00',
      partial: false,
      warnings: []
    })),
    warnings: []
  }))
  api.adjustPreview.mockResolvedValue({ changedIds: ['root'], addedIds: [], removedIds: [], affectedIds: ['root'] })
  api.adjust.mockResolvedValue(detail())
})
afterEach(async () => {
  app?.unmount()
  await flush()
  // 真实控件的双帧进退场回调应在当前 DOM 环境结束前清理。
  await vi.runAllTimersAsync()
  await flush()
  expect(vi.getTimerCount()).toBe(0)
  host?.remove()
  vi.useRealTimers()
  vi.restoreAllMocks()
})
describe('任务弹层与真实 OsModalForm 插槽契约', () => {
  it.each([true, false])('旧主表按需在单个大弹窗打开，保留canExecute=%s且关闭先保护未保存数据', async canExecute => {
    const value = detail()
    value.task.binding = { applicationId: 'app', formId: 'main', entryId: null }
    value.task.canExecute = canExecute
    value.nodes = [value.task]
    api.detail.mockResolvedValue(value)
    await mount(TaskDetail, { id: 'root' })
    expect(host.querySelector('[data-business-form-task]')).toBeNull()
    expect(host.querySelectorAll('[data-surface="modal"]')).toHaveLength(0)
    button('打开业务数据').click()
    await flush()
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-surface="modal"]')).toHaveLength(1)
    expect(host.querySelector('[data-business-form-task="root"]')?.getAttribute('data-business-can-execute')).toBe(
      String(canExecute)
    )
    const modal = required(host.querySelector('[data-surface="modal"]'))
    expect(Number(modal.getAttribute('width'))).toBe(Math.round(window.innerWidth * 0.92))
    api.businessClose.mockResolvedValueOnce(false).mockResolvedValue(true)
    modal.dispatchEvent(new Event('cancel'))
    await flush()
    expect(api.businessClose).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-business-form-task="root"]')).not.toBeNull()
    modal.dispatchEvent(new Event('cancel'))
    await flush()
    expect(api.businessClose).toHaveBeenCalledTimes(2)
    expect(host.querySelector('[data-business-form-task]')).toBeNull()
    expect(host.querySelectorAll('[data-surface="modal"]')).toHaveLength(0)
    expect(api.detail).toHaveBeenCalledOnce()
  })
  it('历史业务深链在概况打开旧主表弹窗，不恢复已删除的业务页签', async () => {
    const value = detail()
    value.task.binding = { applicationId: 'app', formId: 'main', entryId: null }
    value.nodes = [value.task]
    api.detail.mockResolvedValue(value)
    await mount(TaskDetail, { id: 'root', initialTab: 'business' })
    expect(host.querySelector('[data-business-form-task="root"]')).not.toBeNull()
    expect(host.querySelectorAll('[data-surface="modal"]')).toHaveLength(1)
    expect(host.querySelector('[aria-label="任务执行信息"]')).not.toBeNull()
    expect(host.textContent).not.toContain('业务数据与反馈')
  })
  it('任务详情不展示员工计划或提供清单操作', async () => {
    const value = detail()
    value.task.canPlan = true
    value.task.plans = [{ id: 'own-plan', mode: 'CHECKLIST', period: 'WEEK', date: '2030-09-30', canCancel: true }]
    api.detail.mockResolvedValue(value)
    await mount(TaskDetail, { id: 'root', planReadonly: true })
    expect(button('计划清单')).toBeUndefined()
    expect(button('查看计划记录')).toBeUndefined()
    expect(host.textContent).not.toContain('计划清单')
    expect(host.textContent).not.toContain('整体计划开始')
    expect(host.textContent).not.toContain('初始计划')
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(button('加入本周计划')).toBeUndefined()
    expect(button('移出此清单')).toBeUndefined()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it.each(['关闭', '切换'] as const)('%s任务被评论或反馈确认拒绝时不提前批准主表弃改', async intent => {
    const value = detail()
    value.task.binding = { applicationId: 'app', formId: 'main', entryId: null }
    value.task.entries = [
      {
        key: 'feedback',
        name: '施工记录',
        binding: null,
        dataMode: 'ROOT_SHARED',
        sourceNodeId: null,
        sourceEntryKey: null,
        readableFieldIds: null,
        writableFieldIds: null,
        required: false,
        allowAll: false
      }
    ]
    const child = { ...row(), id: 'child', parentId: 'root', title: '另一个任务', status: 'PENDING' as const }
    value.nodes = [value.task, child]
    api.detail.mockImplementation(async id => ({ ...value, task: id === 'child' ? child : value.task }))
    api.entryClose.mockResolvedValueOnce(false).mockResolvedValue(true)
    const close = vi.fn()
    await mount(TaskDetail, { id: 'root', initialTab: 'business', onClose: close })
    button('打开业务数据').click()
    await flush()
    const textarea = host.querySelector('textarea')!
    textarea.value = '尚未提交的评论'
    textarea.dispatchEvent(new Event('input'))
    await flush()
    const leave = async () => {
      if (intent === '关闭') host.querySelector('[data-surface="drawer"]')!.dispatchEvent(new Event('close'))
      else await inspectRow('child')
      await flush()
    }
    const confirm = async (label: string) => {
      const choice = Array.from(document.querySelectorAll<HTMLButtonElement>('[data-surface="modal"] button')).find(
        item => item.textContent?.trim() === label
      )
      if (!choice) throw new Error(`应出现确认按钮：${label}`)
      choice.click()
      await flush()
    }
    await leave()
    await confirm('继续编辑')
    expect(api.entryClose).not.toHaveBeenCalled()
    expect(api.businessClose).not.toHaveBeenCalled()
    expect(textarea.value).toBe('尚未提交的评论')
    expect(close).not.toHaveBeenCalled()
    expect(api.detail).toHaveBeenCalledOnce()
    await leave()
    await confirm('放弃修改')
    expect(api.entryClose).toHaveBeenCalledOnce()
    expect(api.businessClose).not.toHaveBeenCalled()
    expect(close).not.toHaveBeenCalled()
    expect(api.detail).toHaveBeenCalledOnce()
    await leave()
    await confirm('放弃修改')
    expect(api.businessClose).toHaveBeenCalledOnce()
    if (intent === '关闭') expect(close).toHaveBeenCalledOnce()
    else expect(api.detail).toHaveBeenLastCalledWith('child')
  })
  it('未返回关联记录时不铺空行或推断未关联，真实数据范围传递到业务卡片', async () => {
    const value = detail()
    value.task.dataPolicy = { version: 1, business: 'GROUP', feedback: 'ALL' }
    api.detail.mockResolvedValue(value)
    await mount(TaskDetail, { id: 'root' })
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    expect(overview.textContent).not.toContain('关联业务记录')
    expect(host.textContent).not.toContain('未关联具体记录')
    const more = required(host.querySelector<HTMLDetailsElement>('[aria-label="任务设置与来源"]'))
    expect(more.open).toBe(false)
    expect(more.textContent).not.toContain('全部业务数据')
    expect(JSON.parse(required(host.querySelector('[data-business-cards]')?.getAttribute('data-policy')))).toEqual({
      version: 1,
      business: 'GROUP',
      feedback: 'ALL'
    })
    expect(value.task.dataPolicy).toEqual({ version: 1, business: 'GROUP', feedback: 'ALL' })
  })
  it.each(['COMPLETE', 'CANCEL'] as const)('打开%s前反馈准备拒绝时不提前批准主业务弃改', async action => {
    const value = detail()
    value.task.binding = { applicationId: 'app', formId: 'main', entryId: null }
    value.task.entries = [
      {
        key: 'feedback',
        name: '施工记录',
        binding: null,
        dataMode: 'ROOT_SHARED',
        sourceNodeId: null,
        sourceEntryKey: null,
        readableFieldIds: null,
        writableFieldIds: null,
        required: false,
        allowAll: false
      }
    ]
    value.nodes = [value.task]
    api.detail.mockResolvedValue(value)
    api.entryClose.mockResolvedValueOnce(false).mockResolvedValue(true)
    await mount(TaskDetail, { id: 'root', initialTab: 'business' })
    button('打开业务数据').click()
    await flush()
    const label = action === 'COMPLETE' ? '完成任务' : '取消任务'
    button(label).click()
    await flush()
    expect(api.entryClose).toHaveBeenCalledOnce()
    expect(api.businessClose).not.toHaveBeenCalled()
    expect(api.readiness).not.toHaveBeenCalled()
    expect(api.transition).not.toHaveBeenCalled()
    button(label).click()
    await flush()
    expect(api.businessClose).toHaveBeenCalledOnce()
    expect(api.entryClose.mock.invocationCallOrder[1]).toBeLessThan(
      Number(api.businessClose.mock.invocationCallOrder[0])
    )
    expect(api.readiness).toHaveBeenCalledOnce()
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('完成条件加载失败保持禁止提交，原地重试后才允许确认', async () => {
    api.readiness.mockRejectedValueOnce(new Error('检查服务暂不可用'))
    await mount(TaskQuickAction, { task: row(), action: 'COMPLETE' })
    expect(button('确认完成').disabled).toBe(true)
    button('确认完成').click()
    expect(api.transition).not.toHaveBeenCalled()
    expect(host.textContent).toContain('检查服务暂不可用')
    button('重新检查').click()
    await flush()
    expect(button('确认完成').disabled).toBe(false)
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('必填反馈未满足时可跳到业务数据，不能误提交完成', async () => {
    api.readiness.mockResolvedValueOnce({
      taskId: 'root',
      revision: 1,
      canComplete: false,
      checks: [{ code: 'FEEDBACK', label: '施工日志', passed: false, reason: '请登记施工日志', entryKey: 'log' }],
      canCancel: true,
      cancelBlockedReason: null,
      cancellationImpacts: []
    })
    const inspect = vi.fn(),
      close = vi.fn()
    await mount(TaskQuickAction, { task: row(), action: 'COMPLETE', onInspect: inspect, onClose: close })
    expect(button('确认完成').disabled).toBe(true)
    expect(host.textContent).toContain('请登记施工日志')
    button('去处理').click()
    await flush()
    expect(inspect).toHaveBeenCalledExactlyOnceWith('business', 'log')
    expect(close).toHaveBeenCalledOnce()
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('完成预检版本不同于当前列表记录时要求查看最新任务', async () => {
    api.readiness.mockResolvedValueOnce({
      taskId: 'root',
      revision: 2,
      canComplete: true,
      checks: [],
      canCancel: true,
      cancelBlockedReason: null,
      cancellationImpacts: []
    })
    const inspect = vi.fn()
    await mount(TaskQuickAction, { task: row(), action: 'COMPLETE', onInspect: inspect })
    expect(button('确认完成').disabled).toBe(true)
    button('查看最新任务').click()
    await flush()
    expect(inspect).toHaveBeenCalledExactlyOnceWith('overview', undefined)
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('取消任务显示后续影响，确认前不改变任务状态', async () => {
    api.readiness.mockResolvedValueOnce({
      taskId: 'root',
      revision: 1,
      canComplete: false,
      checks: [],
      canCancel: true,
      cancelBlockedReason: null,
      cancellationImpacts: [{ taskId: 'next', title: '浇筑混凝土', direct: true, reason: '前置任务将被取消' }]
    })
    await mount(TaskDetail, { id: 'root' })
    button('取消任务').click()
    await flush()
    expect(host.textContent).toContain('取消影响预览')
    expect(host.textContent).toContain('浇筑混凝土')
    expect(button('确认取消任务').disabled).toBe(false)
    expect(api.transition).not.toHaveBeenCalled()
    button('暂不操作').click()
    await flush()
    expect(host.textContent).not.toContain('取消影响预览')
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('计划弹窗展示所选任务及实际周日期范围', async () => {
    await mount(TaskPlanDialog, {
      ids: ['root'],
      taskNames: ['三号楼施工'],
      initialPeriod: 'WEEK',
      initialDate: '2026-10-02'
    })
    // 展示服务端读取的最新任务名称，不重复铺陈入口快照。
    expect(host.textContent).toContain('施工任务')
    expect(host.textContent).not.toContain('三号楼施工')
    expect(host.textContent).toContain('2026-09-28')
    expect(host.textContent).toContain('2026-10-04')
    actionButton('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({
        action: 'ADD',
        period: 'WEEK',
        date: '2026-09-28',
        expectedVersions: { root: 6 }
      })
    )
  })
  it('领取前安全摘要在统一详情抽屉展示，不读取成员或渲染未授权配置', async () => {
    const preview = {
      ...row(),
      id: 'open-child',
      rootId: 'private-root',
      parentId: null,
      title: '可领取子任务',
      schedule: null,
      description: null,
      priority: null,
      assigneeId: null,
      assigneeName: null,
      status: 'PENDING',
      assignmentMode: 'OPEN',
      canEdit: false,
      canExecute: false,
      canStart: false,
      canClaim: false
    }
    api.detail.mockResolvedValue({ task: preview, nodes: [preview], comments: [], events: [], links: [] })
    await mount(TaskDetail, { id: 'open-child', employeeView: true })
    expect(host.textContent).toContain('可领取子任务')
    expect(host.textContent).toContain('领取前预览')
    expect(api.members).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="任务设置与来源"]')).toBeNull()
    expect(host.querySelector('[aria-label="任务评论"]')).toBeNull()
    expect(host.querySelector('[aria-label="操作历史"]')).toBeNull()
    expect(host.textContent).not.toContain('当前为总任务')
    expect(host.querySelector('[tab="任务编排"]')).toBeNull()
    expect(button('开始执行')).toBeUndefined()
    expect(button('分配任务')).toBeUndefined()
    expect(button('刷新')).toBeUndefined()
    expect(host.querySelector('.task-detail__actions')).toBeNull()

    // 领取或授权变化后，重新打开仍通过原详情接口恢复完整界面，不保留预览状态。
    const assigned = { ...row(), id: 'open-child', rootId: 'private-root', parentId: 'private-root' }
    api.detail.mockResolvedValue({ task: assigned, nodes: [assigned], comments: [], events: [] })
    await reopenDetail('open-child')
    expect(host.textContent).not.toContain('领取前预览')
    expect(host.querySelector('[aria-label="任务设置与来源"]')).not.toBeNull()
    expect(host.querySelector('[aria-label="任务评论"]')).not.toBeNull()
    expect(host.querySelector('[aria-label="操作历史"]')).not.toBeNull()
    expect(api.members).toHaveBeenCalledWith('open-child')
  })
  it('领取前工作要求复用详情排版，补充排期内容和验收而不授予操作权限', async () => {
    const summary = {
      ...row(),
      schedule: null,
      description: null,
      priority: null,
      assigneeId: null,
      assigneeName: null,
      status: 'PENDING',
      assignmentMode: 'OPEN'
    }
    const preview = {
      description: '<p>检查装修材料并提交结果</p>',
      priority: 'HIGH',
      schedule: { mode: 'FIXED', fixedStart: '2030-03-04T00:00:00', offsetDays: 0, durationDays: 2 },
      expectedStart: '2030-03-04T00:00:00',
      expectedEnd: '2030-03-06T23:59:59',
      actualStart: null,
      actualEnd: null,
      createdAt: '2030-03-01T08:00:00',
      acceptorId: 'acceptor',
      acceptorName: '王主管',
      effectiveWorkMinutes: 120,
      templateVersion: 3
    }
    api.detail.mockResolvedValue({ task: summary, nodes: [summary], preview, comments: [], events: [], links: [] })
    await mount(TaskDetail, { id: 'root', employeeView: true })
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    expect(host.querySelector('section[aria-label="任务内容"]')?.textContent).toContain('检查装修材料并提交结果')
    expect(overview.textContent).toContain('2030-03-04')
    expect(overview.textContent).toContain('2030-03-06')
    expect(overview.textContent).toContain('优先级高')
    expect(overview.textContent).toContain('任务标准总工时')
    expect(overview.textContent).toContain('验收人王主管')
    const settings = required(host.querySelector<HTMLDetailsElement>('[aria-label="任务设置与来源"]'))
    expect(settings.open).toBe(true)
    expect(settings.textContent).toContain('创建人张伟')
    expect(settings.textContent).toContain('2030-03-01')
    expect(preview.templateVersion).toBe(3)
    expect(settings.textContent).not.toContain('模板版本')
    expect(settings.textContent).not.toContain('独立任务')
    expect(settings.textContent).not.toContain('无需额外业务数据')
    expect(button('开始执行')).toBeUndefined()
    expect(button('取消任务')).toBeUndefined()
    expect(button('分配任务')).toBeUndefined()
    expect(api.members).not.toHaveBeenCalled()
    expect(api.transition).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="任务评论"]')).toBeNull()
    expect(host.querySelector('[aria-label="操作历史"]')).toBeNull()
    expect(host.querySelector('[data-business-cards]')).toBeNull()

    api.detail.mockResolvedValue({
      task: summary,
      nodes: [summary],
      comments: [],
      events: [],
      preview: { ...preview, acceptorId: null, acceptorName: null, effectiveWorkMinutes: null }
    })
    await reopenDetail('root')
    expect(host.textContent).toContain('不需验收，由负责人直接完成')
    expect(host.textContent).not.toContain('王主管')
  })
  it.each(['领取前预览', '已领取详情'])('%s 的任务内容在概况顶部独立展示富文本和空态', async mode => {
    const description =
      '<p><strong>装修要求</strong></p><ul><li>核对材料</li></ul>' +
      '<img src="/infra/file/1/get/task-content-fixture.png" alt="现场照片">' +
      '<table><tbody><tr><td>数量</td><td>3</td></tr></tbody></table>'
    const full = { ...row(), description }
    const preview = mode === '领取前预览'
    const response = {
      task: preview ? { ...full, schedule: null, description: null } : full,
      nodes: [],
      comments: [],
      events: [],
      ...(preview ? { preview: full } : {})
    }
    api.detail.mockResolvedValue(response)
    await mount(TaskDetail, { id: 'root', employeeView: true })
    const content = required(host.querySelector('section[aria-label="任务内容"]'))
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    expect(content.nextElementSibling).toBe(overview)
    expect(content.querySelector('h3')?.textContent).toBe('任务内容')
    expect(content.querySelector('strong')?.textContent).toBe('装修要求')
    expect(content.querySelector('li')?.textContent).toBe('核对材料')
    expect(content.querySelector('img')?.getAttribute('alt')).toBe('现场照片')
    expect(content.querySelectorAll('td')).toHaveLength(2)
    expect(overview.textContent).not.toContain('装修要求')

    for (const empty of [null, '', '<p><br></p>']) {
      api.detail.mockResolvedValue({
        ...response,
        task: { ...response.task, description: empty },
        ...(preview ? { preview: { ...full, description: empty } } : {})
      })
      await reopenDetail('root')
      const emptyContent = required(host.querySelector('section[aria-label="任务内容"]'))
      expect(emptyContent.textContent).toContain('暂无任务内容')
      expect(emptyContent.querySelector('img, table, strong')).toBeNull()
    }
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('任务详情首次加载失败可以就地重试', async () => {
    api.detail.mockRejectedValueOnce(new Error('网络暂不可用')).mockResolvedValueOnce(detail())
    await mount(TaskDetail, { id: 'root' })
    expect(host.textContent).toContain('网络暂不可用')
    button('重新加载任务').click()
    await flush()
    expect(host.textContent).toContain('施工任务')
    expect(api.detail).toHaveBeenCalledTimes(2)
  })
  it('提醒成员加载失败不阻断任务详情，并可独立重试', async () => {
    api.members.mockRejectedValueOnce(new Error('成员目录暂不可用')).mockResolvedValueOnce([])
    await mount(TaskDetail, { id: 'root' })
    expect(host.textContent).toContain('施工任务')
    expect(host.textContent).toContain('提醒成员暂不可用')
    button('重试加载成员').click()
    await flush()
    expect(api.detail).toHaveBeenCalledTimes(1)
    expect(api.members).toHaveBeenCalledTimes(2)
    expect(host.textContent).not.toContain('提醒成员暂不可用')
  })
  it('应用入口已有锁定ID和名称时不扫描全应用目录，无关目录错误不污染新建任务', async () => {
    api.mine.mockRejectedValue(new Error('应用不存在'))
    await mount(TaskLaunch, {
      embedded: true,
      initialApplicationId: 'construction',
      initialApplicationName: '施工应用'
    })
    button('业务关联').click()
    await flush()
    expect(host.textContent).toContain('施工应用')
    expect(api.mine).not.toHaveBeenCalled()
    expect(api.application).not.toHaveBeenCalled()
    expect(host.textContent).not.toContain('应用列表加载失败')
    expect(host.querySelector('[aria-label="关联应用"]')).toBeNull()
  })
  it('锁定应用草稿恢复只补查当前应用名称，不在恢复前扫描目录', async () => {
    api.mine.mockRejectedValue(new Error('应用不存在'))
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 2,
      content: {
        task: { ...newTaskNode(), title: '施工草稿' },
        applicationId: 'construction',
        requestKey: 'stable-key'
      }
    })
    await mount(TaskLaunch, { embedded: true, draftId: 'draft' })
    button('业务关联').click()
    await flush()
    expect(api.application).toHaveBeenCalledExactlyOnceWith('construction')
    expect(api.mine).not.toHaveBeenCalled()
    expect(host.textContent).toContain('草稿关联施工应用')
    expect(host.textContent).not.toContain('应用列表加载失败')
    expect(host.querySelector('[aria-label="关联应用"]')).toBeNull()
  })
  it('独立新建保留可选应用目录和真实加载失败提示', async () => {
    api.mine.mockRejectedValue(new Error('目录服务暂不可用'))
    await mount(TaskLaunch, { embedded: true })
    button('业务关联').click()
    await flush()
    expect(api.mine).toHaveBeenCalledTimes(1)
    expect(api.application).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="关联应用"]')).not.toBeNull()
    expect(host.textContent).toContain('应用列表加载失败：目录服务暂不可用')
  })
  it('未关联应用的草稿恢复仍加载可选应用目录', async () => {
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 1,
      content: { task: { ...newTaskNode(), title: '独立草稿' }, requestKey: 'stable-key' }
    })
    await mount(TaskLaunch, { embedded: true, draftId: 'draft' })
    button('业务关联').click()
    await flush()
    expect(api.mine).toHaveBeenCalledTimes(1)
    expect(api.application).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="关联应用"]')).not.toBeNull()
    expect(host.textContent).not.toContain('应用列表加载失败')
  })
  it('应用页面仅带入应用也能发起，不强制业务表单或反馈', async () => {
    await mount(TaskLaunch, {
      embedded: true,
      initialApplicationId: 'construction',
      initialApplicationName: '施工应用'
    })
    const input = await openRootNameEditor()
    input.value = '安排现场协调'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('业务关联').click()
    await flush()
    expect(host.textContent).toContain('施工应用')
    button('任务编排').click()
    await flush()
    await launchWithSchedule()
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        applicationId: 'construction',
        project: null,
        business: null,
        existingRecord: null,
        task: expect.objectContaining({ binding: null })
      })
    )
  })
  it('从业务记录页面发起沿用记录身份，不复制一条业务数据', async () => {
    const project = { applicationId: 'construction', objectId: 'project', recordId: 'r1', label: '一号工地' }
    await mount(TaskLaunch, { embedded: true, initialApplicationId: 'construction', initialProject: project })
    const input = await openRootNameEditor()
    input.value = '安排送货'
    input.dispatchEvent(new Event('input'))
    await flush()
    await launchWithSchedule()
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        applicationId: 'construction',
        project,
        business: null,
        existingRecord: null,
        task: expect.objectContaining({ binding: null })
      })
    )
  })
  it('计划弹窗呈现期间和日期并可提交', async () => {
    await mount(TaskPlanDialog, { ids: ['root'] })
    expect(host.querySelector('[data-surface="modal"]')).not.toBeNull()
    expect(host.querySelector('[data-surface="drawer"]')).toBeNull()
    expect(host.textContent).toContain('加入本周计划')
    expect(host.textContent).not.toContain('开始日期')
    expect(host.textContent).not.toContain('移出此计划')
    actionButton('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ ids: ['root'], period: 'WEEK', action: 'ADD', expectedVersions: { root: 6 } })
    )
  })
  it('节点配置弹窗显示负责人及时间规则', async () => {
    await mount(TaskNodeEditor, { modelValue: [newTaskNode()], members: [] })
    button('配置').click()
    await flush()
    expect(host.textContent).toContain('排期方式')
    expect(host.textContent).toContain('随任务顺序')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('暂不设置计划日期')
    expect(host.querySelector('section input[placeholder="填写可执行的任务名称"]')).not.toBeNull()
  })
  it('新增节点聚焦名称，Enter连续新增，Esc只撤销尚未填写的新增行', async () => {
    const nodes = ref<ReturnType<typeof newTaskNode>[]>([])
    await mount(
      defineComponent({
        setup: () => () =>
          h(TaskNodeEditor, {
            modelValue: nodes.value,
            members: [],
            'onUpdate:modelValue': value => {
              nodes.value = value
            }
          })
      })
    )
    button('拆分子任务').click()
    await flush()
    const input = host.querySelector<HTMLInputElement>('input')!
    expect(document.activeElement).toBe(input)
    input.value = '先准备材料'
    input.dispatchEvent(new Event('input'))
    await flush()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flush()
    expect(nodes.value).toHaveLength(2)
    const inputs = host.querySelectorAll<HTMLInputElement>('input')
    expect(document.activeElement).toBe(inputs[1])
    nodes.value[1]!.assigneeId = 'another-member'
    inputs[1]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(nodes.value).toHaveLength(2)
    nodes.value[1]!.assigneeId = null
    inputs[1]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(nodes.value.map(node => node.title)).toEqual(['先准备材料'])
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(nodes.value).toHaveLength(1)
  })
  it('直接发起单项任务不要求类型或子项，也不发送人工kind分类', async () => {
    await mount(TaskLaunch, { embedded: true })
    expect(host.textContent).toContain('新建任务')
    expect(host.textContent).toContain('从模板新建')
    expect(host.textContent).not.toContain('普通任务')
    expect(host.textContent).not.toContain('流程任务')
    const input = await openRootNameEditor()
    input.value = '今日工作'
    input.dispatchEvent(new Event('input'))
    await flush()
    await launchWithSchedule()
    expect(api.create).toHaveBeenCalledOnce()
    expect(api.create.mock.calls[0]?.[0]).not.toHaveProperty('kind')
    expect(api.create.mock.calls[0]?.[0]).toMatchObject({ task: { title: '今日工作' }, nodes: null })
  })
  it('模板草稿主工作区显示名称和任务编排，不再叠加抽屉', async () => {
    await mount(TaskTemplates)
    button('新建模板').click()
    await flush()
    expect(host.textContent).toContain('模板名称')
    expect(host.querySelector('[data-surface="drawer"]')).toBeNull()
    expect(host.textContent).toContain('保存草稿')
    button('业务关联').click()
    await flush()
    expect(host.textContent).toContain('业务办理项')
    expect(host.textContent).toContain('选择业务视图')
    expect(host.textContent).not.toContain('具体项目或订单在使用模板时关联')
    expect(host.textContent).not.toContain('关联应用或具体业务；独立任务可以留空')
    expect(host.textContent).not.toContain('关联项目仅确定任务归属')
    expect(host.querySelector('input')).not.toBeNull()
  })
  it('新模板总任务单独保存，允许无子任务，不伪造实际时间', async () => {
    api.saveTemplate.mockImplementation(async body => ({
      ...body,
      id: 'saved-template',
      revision: 1,
      publishedVersion: null,
      creatorId: '2104779219579535361',
      updatedAt: ''
    }))
    await mount(TaskTemplates)
    button('新建模板').click()
    await flush()
    const name = host.querySelector<HTMLInputElement>('input')!
    name.value = '项目交付模板'
    name.dispatchEvent(new Event('input'))
    await flush()
    button('保存草稿').click()
    await flush()
    expect(api.saveTemplate).toHaveBeenCalledOnce()
    const body = api.saveTemplate.mock.calls[0]![0]
    expect(body).toMatchObject({
      name: '项目交付模板',
      nodes: [],
      task: {
        title: '项目交付模板',
        dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
        schedule: { mode: 'AUTO' }
      }
    })
    expect(body.task).not.toHaveProperty('actualStart')
    expect(body.task).not.toHaveProperty('actualEnd')
  })
  it('详情抽屉最大化和还原保持同一个评论输入与草稿', async () => {
    await mount(TaskDetail, { id: 'root' })
    const originalWidth = String(Math.round(window.innerWidth * 0.92))
    expect(host.querySelector('[data-surface="drawer"]')?.getAttribute('width')).toBe(originalWidth)
    const input = required(host.querySelector<HTMLTextAreaElement>('[aria-label="评论内容"]'))
    input.value = '尚未发表的体验意见'
    input.dispatchEvent(new Event('input'))
    await flush()
    required(host.querySelector<HTMLButtonElement>('[aria-label="最大化抽屉"]')).click()
    await flush()
    expect(host.querySelector('[data-surface="drawer"]')?.getAttribute('width')).toBe('100vw')
    expect(host.querySelector('[aria-label="评论内容"]')).toBe(input)
    expect(input.value).toBe('尚未发表的体验意见')
    expect(host.querySelector('.os-modal-form-resize-handle-left')).toBeNull()
    required(host.querySelector<HTMLButtonElement>('[aria-label="还原抽屉"]')).click()
    await flush()
    expect(host.querySelector('[data-surface="drawer"]')?.getAttribute('width')).toBe(originalWidth)
    expect(host.querySelector('[aria-label="评论内容"]')).toBe(input)
    expect(input.value).toBe('尚未发表的体验意见')
    expect(api.comment).not.toHaveBeenCalled()
  })
  it('详情和完成操作均展示真实内容', async () => {
    await mount(TaskDetail, { id: 'root' })
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    for (const label of ['预计开始', '预计完成']) expect(overview.textContent).toContain(label)
    expect(overview.textContent).toContain('实际开始时间实际完成时间')
    expect(overview.textContent).not.toContain('实际结束')
    button('完成任务').click()
    await flush()
    expect(host.textContent).toContain('完成备注')
    expect(host.querySelectorAll('[data-surface="drawer"]').length).toBe(1)
    expect(host.querySelector('[data-surface="modal"]')).not.toBeNull()
  })
  it('已有实际执行时间时继续展示服务端精确时间', async () => {
    const value = detail()
    value.task.status = 'COMPLETED'
    value.task.actualStart = '2030-04-01T09:30:00'
    value.task.actualEnd = '2030-04-03T17:20:00'
    api.detail.mockResolvedValue(value)
    await mount(TaskDetail, { id: 'root' })
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    expect(overview.textContent).toContain('实际开始时间2030-04-01 09:30')
    expect(overview.textContent).toContain('实际完成时间2030-04-03 17:20')
  })
  it('管理者调整待开始实例，编辑后才显示原因和预览；原任务数据不随草稿修改', async () => {
    const value = detail()
    value.task.status = 'PENDING'
    value.nodes = [value.task]
    await mount(TaskAdjust, { detail: value, canManage: true })
    expect(host.textContent).not.toContain('调整原因')
    expect(button('预览调整影响')).toBeUndefined()
    await changeRootName('施工任务调整')
    expect(host.textContent).toContain('调整原因')
    expect(button('预览调整影响')).toBeDefined()
    expect(value.task.title).toBe('施工任务')
  })
  it('没有使用整体起点时隐藏输入，提交调整仍保留历史起点', async () => {
    const value = detail()
    value.task.plannedStart = '2030-03-01T08:15:30'
    value.task.status = 'PENDING'
    value.nodes = [value.task]
    await mount(TaskAdjust, { detail: value, canManage: true })
    expect(host.querySelector('[placeholder="相对时间的共同起点"]')).toBeNull()
    await changeRootName('施工任务调整')
    const reason = required(host.querySelector('textarea'))
    reason.value = '仅改名称'
    reason.dispatchEvent(new Event('input'))
    await flush()
    button('预览调整影响').click()
    await flush()
    expect(api.adjustPreview).toHaveBeenCalledWith(expect.objectContaining({ plannedStart: '2030-03-01T08:15:30' }))
  })
  it('只有子任务使用整体起点时仍显示输入，缺失起点不能提交预览', async () => {
    const value = detail()
    value.task.status = 'PENDING'
    value.nodes = [value.task]
    const child = { ...row(), id: 'child', parentId: 'root', status: 'PENDING' as const }
    child.schedule.mode = 'PLAN_START'
    value.nodes.push(child)
    await mount(TaskAdjust, { detail: value, canManage: true })
    expect(host.querySelector('[placeholder="相对时间的共同起点"]')).not.toBeNull()
    await changeRootName('施工任务调整')
    button('预览调整影响').click()
    await flush()
    expect(host.textContent).toContain('请选择计划开始日期')
    expect(api.adjustPreview).not.toHaveBeenCalled()
  })
  it('调整预览使用请求时的深快照，迟到输入使旧响应失效，重新预览后才能提交', async () => {
    let resolvePreview!: (value: TaskAdjustmentPreview) => void
    api.adjustPreview.mockImplementationOnce(
      () =>
        new Promise<TaskAdjustmentPreview>(resolve => {
          resolvePreview = resolve
        })
    )
    const value = detail()
    value.task.status = 'PENDING'
    value.nodes = [value.task]
    await mount(TaskAdjust, { detail: value, canManage: true })
    await changeRootName('施工任务第一版')
    const reason = required(host.querySelector('textarea'))
    reason.value = '调整第一版'
    reason.dispatchEvent(new Event('input'))
    await flush()
    button('预览调整影响').click()
    await flush()
    expect(api.adjustPreview).toHaveBeenCalledOnce()
    const requested = required(api.adjustPreview.mock.calls[0])[0]
    expect(requested.reason).toBe('调整第一版')
    expect(isReactive(requested.nodes)).toBe(false)
    expect(isReactive(requested.nodes[0])).toBe(false)
    expect(requested.nodes[0].title).toBe('施工任务第一版')
    expect(value.task.title).toBe('施工任务')
    expect(host.querySelector('input[aria-label="总任务名称 1"]')).toBeNull()
    expect(host.querySelector('button[aria-label^="拆分子任务："]')).toBeNull()
    // 模拟请求发出后到达的输入事件；表单只读不能替代请求快照与响应归属校验。
    reason.value = '调整第二版'
    reason.dispatchEvent(new Event('input'))
    await flush()
    expect(requested.reason).toBe('调整第一版')
    resolvePreview({ changedIds: ['root'], addedIds: [], removedIds: [], affectedIds: ['root'] })
    await flush()
    expect(button('确认调整任务')).toBeUndefined()
    expect(api.adjust).not.toHaveBeenCalled()
    button('预览调整影响').click()
    await flush()
    expect(api.adjustPreview).toHaveBeenCalledTimes(2)
    expect(required(api.adjustPreview.mock.calls[1])[0].reason).toBe('调整第二版')
    expect(api.adjust).not.toHaveBeenCalled()
    button('确认调整任务').click()
    await flush()
    expect(api.adjust).toHaveBeenCalledOnce()
    expect(required(api.adjust.mock.calls[0])[0]).toEqual(required(api.adjustPreview.mock.calls[1])[0])
  })
  it('完成历史打开冻结材料，读取事件材料而非当前记录', async () => {
    const value = detail()
    value.nodes[0]!.business = {
      resource: {
        applicationId: 'app',
        applicationVersion: 1,
        applicationChecksum: 'sum',
        resourceId: 'form',
        resourceKind: 'FORM'
      },
      object: { objectId: 'object', versionNo: 1, checksum: 'sum' },
      recordId: 'record',
      requestId: null
    }
    value.events = [
      {
        id: 'done',
        taskId: 'root',
        type: 'COMPLETED',
        actorId: '2104779219579535361',
        actorName: '张伟',
        note: '完成',
        createdAt: ''
      }
    ]
    api.detail.mockResolvedValue(value)
    api.material.mockResolvedValue({ binding: value.nodes[0]!.business, model: {}, record: {} })
    await mount(TaskDetail, { id: 'root' })
    button('查看当时材料').click()
    await flush()
    expect(api.material).toHaveBeenCalledWith('root', 'done')
    expect(host.textContent).toContain('冻结业务材料插槽')
  })
  it('新统一授权只配置业务资源即可加入任务池，不强迫创建时填写业务记录', async () => {
    await mount(TaskLaunch, { embedded: true, initialBinding: { applicationId: 'app', formId: 'form', entryId: null } })
    const input = await openRootNameEditor()
    input.value = '施工资料'
    input.dispatchEvent(new Event('input'))
    await flush()
    await launchWithSchedule()
    expect(host.textContent).not.toContain('真实业务表单插槽')
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        task: expect.objectContaining({
          binding: { applicationId: 'app', formId: 'form', entryId: null },
          dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' }
        }),
        business: null
      })
    )
  })
  it('管理入口只查看负责人计划，即使旧权限标志允许也不提供写入口', async () => {
    await mount(TaskPlanDialog, { ids: ['root'], target: 'ASSIGNEE' })
    expect(host.textContent).toContain('查看负责人计划')
    const labels = Array.from(host.querySelectorAll('button')).map(item => item.textContent?.trim())
    expect(labels).not.toContain('加入本周计划')
    expect(labels).not.toContain('移出此清单')
    expect(api.checklistContext).toHaveBeenCalledWith({ target: 'ASSIGNEE', ids: ['root'] })
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('列表就近完成失败保留备注，重试使用同一幂等键', async () => {
    api.transition.mockRejectedValueOnce(new Error('尚有未完成子任务')).mockResolvedValueOnce(detail())
    const close = vi.fn()
    await mount(TaskQuickAction, { task: row(), action: 'COMPLETE', onClose: close })
    expect(host.querySelector('[data-surface="modal"]')).not.toBeNull()
    expect(host.querySelector('[data-surface="drawer"]')).toBeNull()
    const input = host.querySelector('textarea')!
    input.value = '现场验收完毕'
    input.dispatchEvent(new Event('input'))
    button('确认完成').click()
    await flush()
    expect(host.textContent).toContain('尚有未完成子任务')
    expect(input.value).toBe('现场验收完毕')
    expect(input.disabled).toBe(true)
    button('确认原操作结果').click()
    await flush()
    expect(api.transition.mock.calls[1]?.[0]).toEqual(api.transition.mock.calls[0]?.[0])
    expect(close).toHaveBeenCalledOnce()
  })
  it('未知完成结果关闭重开后保留原备注和版本；失效修订只读确认后才能解除', async () => {
    api.transition
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockRejectedValueOnce({ businessCode: 409, message: '修订已变化' })
    api.transitionRecovery.mockResolvedValue({ applied: null, superseded: true })
    await mount(TaskQuickAction, { task: row(), action: 'COMPLETE' })
    const input = required(host.querySelector('textarea'))
    input.value = '原始完工说明'
    input.dispatchEvent(new Event('input'))
    button('确认完成').click()
    await flush()
    const original = api.transition.mock.calls[0]?.[0]
    app?.unmount()
    host.remove()
    await mount(TaskQuickAction, { task: { ...row(), revision: 9 }, action: 'COMPLETE' })
    expect(required(host.querySelector('textarea')).value).toBe('原始完工说明')
    expect(required(host.querySelector('textarea')).disabled).toBe(true)
    button('确认原操作结果').click()
    await flush()
    expect(api.transition.mock.calls[1]?.[0]).toEqual(original)
    expect(api.transitionRecovery).toHaveBeenCalledExactlyOnceWith(original)
    expect(host.textContent).toContain('原操作未生效，任务已更新')
    expect(required(host.querySelector('textarea')).disabled).toBe(false)
  })
  it('详情恢复已有成功回执，不因最新状态不再可完成而丢失原操作', async () => {
    const command = { id: 'root', expectedRevision: 1, action: 'COMPLETE', note: '原备注', requestKey: 'recovered' }
    sessionStorage.setItem('nocode.task.transition.pending:2104779219579535361:root', JSON.stringify(command))
    const applied = detail()
    applied.task = { ...applied.task, revision: 2, status: 'COMPLETED', canExecute: false }
    api.detail.mockResolvedValue(applied)
    api.transition.mockRejectedValueOnce({ businessCode: 409 })
    api.transitionRecovery.mockResolvedValueOnce({ applied, superseded: false })
    const changed = vi.fn()
    await mount(TaskDetail, { id: 'root', onChanged: changed })
    button('确认原操作结果').click()
    await flush()
    expect(api.transition).toHaveBeenCalledExactlyOnceWith(command)
    expect(api.transitionRecovery).toHaveBeenCalledExactlyOnceWith(command)
    expect(changed).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem('nocode.task.transition.pending:2104779219579535361:root')).toBeNull()
  })
  it('评论通知定位到指定评论并高亮，隐藏其他节点评论', async () => {
    const value = detail()
    value.comments = [
      {
        id: 'target',
        taskId: 'root',
        parentId: null,
        authorId: '2104779219579535361',
        authorName: '张伟',
        content: '请确认材料',
        mentionedUserIds: [],
        createdAt: ''
      },
      {
        id: 'other',
        taskId: 'other-node',
        parentId: null,
        authorId: 2,
        authorName: '其他',
        content: '其他节点评论',
        mentionedUserIds: [],
        createdAt: ''
      }
    ]
    api.detail.mockResolvedValue(value)
    await mount(TaskDetail, { id: 'root', commentId: 'target' })
    expect(host.querySelector('[data-comment-id="target"]')?.classList.contains('task-comment--target')).toBe(true)
    expect(host.textContent).not.toContain('其他节点评论')
  })
  it('关联已有任务只提交当前发布区块和任务版本，不替换业务记录', async () => {
    api.recordLinkCandidates.mockResolvedValue({ list: [row()], total: 1 })
    api.recordLink.mockResolvedValue(detail())
    const context = { applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record' }
    await mount(TaskLinkPicker, { context })
    button('关联').click()
    await flush()
    expect(api.recordLink).toHaveBeenCalledWith(
      expect.objectContaining({ context, taskId: 'root', expectedRevision: 1, include: true })
    )
    expect(api.recordLink.mock.calls[0]?.[0]).not.toHaveProperty('business')
  })
  it('有固定模板范围的任务表仅从该范围模板发起', async () => {
    await mount(TaskLaunch, { embedded: true, templateIds: ['allowed-template'] })
    expect(host.textContent).toContain('已发布模板')
    expect(host.textContent).not.toContain('普通任务')
    expect(host.textContent).not.toContain('列表编排')
  })
  it('首次详情已带所属应用时立即读取当前用户可见的应用名称', async () => {
    const value = detail()
    value.task.applicationId = 'construction-app'
    api.detail.mockResolvedValue(value)
    api.mine.mockResolvedValue([{ id: 'construction-app', name: '施工管理' }])
    await mount(TaskDetail, { id: 'root' })
    expect(api.mine).toHaveBeenCalledOnce()
    expect(host.textContent).toContain('所属应用施工管理')
    expect(host.textContent).not.toContain('正在读取')
    expect(api.application).not.toHaveBeenCalled()
  })
  it('任务可读但所属应用不在个人目录时显示稳定兜底，不追加应用访问请求', async () => {
    const value = detail()
    value.task.applicationId = 'private-app'
    api.detail.mockResolvedValue(value)
    api.mine.mockResolvedValue([])
    await mount(TaskDetail, { id: 'root' })
    expect(api.mine).toHaveBeenCalledOnce()
    expect(host.textContent).toContain('所属应用当前不可访问')
    expect(host.textContent).not.toContain('正在读取')
    expect(api.application).not.toHaveBeenCalled()
  })
  it('在详情查看整项树、切换后继并返回原办理项，操作权限随节点重新读取', async () => {
    const root = { ...row(), title: '项目总任务', kind: 'PROCESS' as const },
      first = {
        ...row(),
        id: 'first',
        parentId: 'root',
        title: '现场测量',
        kind: 'PROCESS' as const,
        role: 'NODE' as const
      },
      next = {
        ...row(),
        id: 'next',
        parentId: 'root',
        title: '基础施工',
        kind: 'PROCESS' as const,
        role: 'NODE' as const,
        predecessorIds: ['first'],
        canExecute: false
      }
    const nodes = [root, first, next]
    api.detail.mockImplementation(async (id: string) => ({
      task: nodes.find(node => node.id === id)!,
      nodes,
      comments: [],
      events: []
    }))
    await mount(TaskDetail, { id: 'first' })
    expect(host.querySelector('[aria-label="当前任务位置"]')?.textContent).toContain('项目总任务')
    expect(host.querySelector('[aria-current="page"]')?.textContent).toBe('现场测量')
    button('任务编排').click()
    await flush()
    expect(host.querySelector('[data-row-key="first"]')?.getAttribute('aria-selected')).toBe('true')
    const successor = required(host.querySelector<HTMLElement>('[data-row-key="next"]'))
    expect(successor.textContent).toContain('基础施工')
    expect(successor.textContent).toContain('进行中')
    successor.click()
    await flush()
    expect(successor.getAttribute('aria-selected')).toBe('true')
    expect(host.querySelector('[aria-current="page"]')?.textContent).toBe('现场测量')
    required(host.querySelector<HTMLButtonElement>('.task-adjust__selection button')).click()
    await flush()
    expect(api.detail).toHaveBeenLastCalledWith('next')
    expect(host.querySelector('[aria-current="page"]')?.textContent).toBe('基础施工')
    expect(button('完成任务')).toBeUndefined()
    button('返回上一任务').click()
    await flush()
    expect(api.detail).toHaveBeenLastCalledWith('first')
    expect(button('完成任务')).toBeDefined()
  })
  it.each([false, true])('跨节点继续办理先读取原任务，读取失败=%s 时不套用办理项和贡献记录', async failed => {
    const warnings = vi.spyOn(console, 'warn')
    const root: TaskRow = { ...row(), dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' } }
    const source = { ...root, id: 'origin-task', parentId: 'root', role: 'NODE' as const, title: '原办理任务' }
    const nodes = [root, source]
    api.detail.mockImplementation(async (id: string) => ({
      task: required(nodes.find(node => node.id === id)),
      nodes,
      comments: [],
      events: []
    }))
    workspace.resume = { taskId: source.id, entryKey: 'entry-source', contributionId: 'contribution-source' }
    await mount(TaskDetail, { id: 'root', employeeView: true })
    if (failed) api.detail.mockRejectedValueOnce(new Error('原任务已无权访问'))
    button('回原任务继续办理').click()
    await flush()
    expect(api.detail).toHaveBeenLastCalledWith(source.id)
    const cards = required(host.querySelector('[data-business-cards]'))
    expect(cards.getAttribute('data-task-id')).toBe(failed ? root.id : source.id)
    expect(cards.getAttribute('data-entry-key')).toBe(failed ? null : 'entry-source')
    expect(cards.getAttribute('data-contribution-id')).toBe(failed ? null : 'contribution-source')
    expect(host.querySelector('[aria-current="page"]')?.textContent).toBe(failed ? '施工任务' : '原办理任务')
    if (failed) expect(host.textContent).toContain('原任务已无权访问')
    expect(warnings.mock.calls.flat().join(' ')).not.toContain('Duplicate keys found')
    expect(api.transition).not.toHaveBeenCalled()
    expect(api.adjust).not.toHaveBeenCalled()
  })
  it('详情收尾必须确认取消范围并说明，确认框明确操作对象', async () => {
    api.detail.mockResolvedValue({
      ...detail(),
      task: { ...row(), childCount: 1, completionReason: '存在已取消子任务' }
    })
    api.readiness.mockResolvedValue({
      taskId: 'root',
      revision: 1,
      canComplete: false,
      canCancel: true,
      checks: [{ code: 'CHILDREN_CANCELLED', label: '确认取消后的交付范围', passed: false, reason: '已取消：安装' }]
    })
    api.transition.mockResolvedValue({ ...detail(), task: { ...row(), status: 'COMPLETED' } })
    await mount(TaskDetail, { id: 'root', employeeView: true })
    button('完成任务').click()
    await flush()
    const modal = required(host.querySelector<HTMLElement>('[data-surface="modal"]'))
    expect(modal.textContent).toContain('本次操作：施工任务')
    expect(button('确认完成').disabled).toBe(true)
    required(modal.querySelector<HTMLInputElement>('input[type="checkbox"]')).click()
    await flush()
    expect(button('确认完成').disabled).toBe(true)
    const note = required(modal.querySelector<HTMLTextAreaElement>('textarea'))
    note.value = '安装范围已取消，采购设备已交付'
    note.dispatchEvent(new Event('input'))
    await flush()
    expect(button('确认完成').disabled).toBe(false)
    button('确认完成').click()
    await flush()
    expect(api.transition).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'root', confirmCancelledChildren: true, note: note.value })
    )
  })
  it('员工关系图逐步返回真实上一任务，切换失败不丢失位置与画布', async () => {
    const root = { ...row(), title: '采购电脑' }
    const a = { ...row(), id: 'a', parentId: 'root', title: '采购', role: 'NODE' as const }
    const b = { ...row(), id: 'b', parentId: 'root', title: '安装', role: 'NODE' as const }
    const nodes = [root, a, b]
    api.detail.mockImplementation(async (id: string) => ({
      task: required(nodes.find(node => node.id === id)),
      nodes,
      comments: [],
      events: []
    }))
    await mount(TaskDetail, { id: 'root', employeeView: true })
    button('任务编排').click()
    await flush()
    button('图上编辑').click()
    await flush()
    const clickNode = async (id: string) => {
      button('任务编排').click()
      await flush()
      required(host.querySelector<SVGGElement>(`.task-dag [data-task-id="${id}"]`)).dispatchEvent(
        new MouseEvent('click', { bubbles: true })
      )
      await flush()
      required(host.querySelector<HTMLButtonElement>('.task-adjust__selection button')).click()
      await flush()
    }
    button('＋').click()
    await flush()
    const scene = required(host.querySelector('[data-scene]'))
    const transform = scene.getAttribute('transform')
    await clickNode('a')
    await clickNode('b')
    expect(host.querySelector('[aria-label="当前任务位置"] [aria-current="page"]')?.textContent).toBe('安装')
    expect(host.querySelector('[data-scene]')?.getAttribute('transform')).toBe(transform)
    api.detail.mockRejectedValueOnce(new Error('任务暂不可访问'))
    await clickNode('root')
    expect(host.querySelector('[aria-label="当前任务位置"] [aria-current="page"]')?.textContent).toBe('安装')
    expect(host.textContent).toContain('任务暂不可访问')
    expect(host.querySelector('[data-scene]')?.getAttribute('transform')).toBe(transform)
    button('返回上一任务').click()
    await flush()
    expect(api.detail).toHaveBeenLastCalledWith('a')
    expect(host.querySelector('[aria-label="当前任务位置"] [aria-current="page"]')?.textContent).toBe('采购')
    button('返回上一任务').click()
    await flush()
    expect(api.detail).toHaveBeenLastCalledWith('root')
    expect(button('返回上一任务')).toBeUndefined()
  })
  it('取消未发评论使用弹窗确认，继续编辑保留输入', async () => {
    const close = vi.fn()
    await mount(TaskQuickAction, { task: row(), action: 'COMMENT', onClose: close })
    const textarea = host.querySelector('textarea')!
    textarea.value = '请复核现场数量'
    textarea.dispatchEvent(new Event('input'))
    await flush()
    button('暂不操作').click()
    await flush()
    const confirmation = Array.from(document.querySelectorAll<HTMLElement>('[data-surface="modal"]')).find(node =>
      node.textContent?.includes('已保存的内容会保留')
    )!
    expect(confirmation).toBeTruthy()
    expect(
      Array.from(document.querySelectorAll<HTMLElement>('[data-surface="drawer"]')).some(node =>
        node.textContent?.includes('已保存的内容会保留')
      )
    ).toBe(false)
    Array.from(confirmation.querySelectorAll('button'))
      .find(node => node.textContent?.trim() === '继续编辑')!
      .click()
    await flush()
    expect(close).not.toHaveBeenCalled()
    expect(textarea.value).toBe('请复核现场数量')
  })
  it('子任务继承入口仍保留自身业务信息，反馈未确认不能关闭', async () => {
    const root = {
      ...row(),
      entries: [
        {
          key: 'feedback',
          name: '施工记录',
          binding: null,
          dataMode: 'ROOT_SHARED' as const,
          sourceNodeId: null,
          sourceEntryKey: null,
          readableFieldIds: null,
          writableFieldIds: null,
          required: false,
          allowAll: false
        }
      ]
    }
    const child = {
      ...row(),
      id: 'child',
      parentId: root.id,
      entries: [],
      binding: { applicationId: 'app', formId: 'main', entryId: null }
    }
    api.detail.mockResolvedValue({ ...detail(), task: child, nodes: [root, child] })
    api.entryClose.mockResolvedValue(false)
    const close = vi.fn()
    await mount(TaskDetail, { id: child.id, onClose: close })
    expect(host.textContent).not.toContain('真实业务表单插槽')
    button('打开业务数据').click()
    await flush()
    expect(host.textContent).toContain('真实业务表单插槽')
    expect(host.textContent).toContain('业务办理卡片插槽')
    const drawer = host.querySelector('[data-surface="drawer"]')!
    drawer.dispatchEvent(new Event('close'))
    await flush()
    expect(api.entryClose).toHaveBeenCalledOnce()
    expect(close).not.toHaveBeenCalled()
  })
})
