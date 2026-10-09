// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  computed,
  createApp,
  defineComponent,
  Fragment,
  getCurrentInstance,
  h,
  inject,
  isVNode,
  nextTick,
  provide,
  ref,
  watch,
  type App,
  type ComputedRef,
  type VNode
} from 'vue'
import TaskDetail from '@/views/nocode/task-center/TaskDetail.vue'
import { newTaskNode } from './task-center'
import type {
  TaskAdjustmentPreview,
  TaskDetail as Detail,
  TaskNodeInput,
  TaskRow,
  TaskStructureNode
} from '@/types/nocode/task-center'

const state = vi.hoisted(() => ({
  manage: true,
  detail: vi.fn(),
  members: vi.fn(),
  adjustPreview: vi.fn(),
  adjust: vi.fn(),
  close: vi.fn(),
  changed: vi.fn(),
  routeLeave: vi.fn(),
  routeUpdate: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: state, runtime: { mine: async () => [] } })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'employee' } }) }))
vi.mock('@/utils/access', () => ({ hasPermission: () => state.manage }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: state.routeLeave,
  onBeforeRouteUpdate: state.routeUpdate
}))
vi.mock('@/api/nocode/workflow-task-node', () => ({
  createWorkflowTaskNodeApi: () => ({ source: async () => null })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn(), warning: vi.fn() }, Modal: { confirm: vi.fn() } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title', 'displayMode', 'okText', 'cancelText', 'showFooter', 'loading'],
    emits: ['ok', 'cancel'],
    setup:
      (p, { slots, emit }) =>
      () =>
        p.open
          ? h('section', { 'data-surface': p.displayMode || 'modal', 'data-title': p.title }, [
              h('h2', p.title),
              h('button', { 'data-surface-close': '', onClick: () => emit('cancel') }, '关闭面板'),
              slots.formItems?.(),
              p.showFooter !== false
                ? slots.footer?.() || [
                    h('button', { disabled: p.loading, onClick: () => emit('ok') }, p.okText || '确定'),
                    h('button', { disabled: p.loading, onClick: () => emit('cancel') }, p.cancelText || '取消')
                  ]
                : null
            ])
          : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'customRow'],
    setup: (p, { slots }) => {
      const rows = (items: Array<TaskNodeInput & { children?: TaskNodeInput[] }>): VNode[] =>
        items.flatMap(record => [
          h(
            'div',
            { 'data-node-row': record.id, ...p.customRow?.(record) },
            p.columns.map((column: { key: string }) =>
              h('div', { 'data-node-column': column.key }, slots.bodyCell?.({ column, record }))
            )
          ),
          ...rows(record.children || [])
        ])
      return () => h('div', { 'data-node-table': '' }, [slots.actions?.(), ...rows(p.dataSource || [])])
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'readonly'],
    emits: ['update:modelValue'],
    setup:
      (p, { emit }) =>
      () =>
        h('div', [
          h('input', {
            'data-inspector-title': '',
            value: p.modelValue.title,
            disabled: p.readonly,
            onInput: (e: Event) =>
              emit('update:modelValue', { ...p.modelValue, title: (e.target as HTMLInputElement).value })
          }),
          h('input', {
            'data-inspector-duration': '',
            value: p.modelValue.schedule.durationDays,
            disabled: p.readonly,
            onInput: (e: Event) =>
              emit('update:modelValue', {
                ...p.modelValue,
                schedule: { ...p.modelValue.schedule, durationDays: Number((e.target as HTMLInputElement).value) }
              })
          })
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskDag.vue', () => ({
  default: defineComponent({
    props: ['nodes', 'editable', 'runtimeNodes', 'currentId'],
    emits: ['select', 'configure', 'link', 'unlink'],
    setup: (p, { emit, expose }) => {
      expose({ captureView: () => ({ zoom: 1, pan: { x: 0, y: 0 }, collapsedIds: [] }), restoreView: vi.fn() })
      return () =>
        h('div', { 'data-graph': '', 'data-editable': String(p.editable) }, [
          h('output', { 'data-runtime-nodes': '' }, JSON.stringify(p.runtimeNodes || [])),
          ...p.nodes.map((node: TaskNodeInput) =>
            h(
              'button',
              {
                'data-graph-node': node.id,
                'aria-pressed': String(p.currentId === node.id),
                onClick: () => emit('select', node.id)
              },
              node.title
            )
          ),
          ...p.nodes.map((node: TaskNodeInput) =>
            h(
              'button',
              { 'data-graph-configure': node.id, onClick: () => emit('configure', node.id) },
              `配置 ${node.title}`
            )
          ),
          ...p.nodes.flatMap((from: TaskNodeInput) =>
            p.nodes
              .filter((to: TaskNodeInput) => to.id !== from.id)
              .map((to: TaskNodeInput) => {
                const action = to.predecessorIds.includes(from.id) ? 'unlink' : 'link'
                return h(
                  'button',
                  {
                    'data-edge-from': from.id,
                    'data-edge-to': to.id,
                    'data-edge-action': action,
                    disabled: !p.editable,
                    onClick: () => emit(action, from.id, to.id)
                  },
                  `${action}:${from.id}:${to.id}`
                )
              })
          )
        ])
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskAssignmentFields.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskHierarchyCell.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots['branch-actions']?.(), slots.default?.(), slots.extra?.()])
  })
}))
vi.mock('@/views/nocode/task-center/TaskContext.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskEntryWorkspace.vue', () => ({
  default: defineComponent({
    props: ['task'],
    setup: (props, { expose }) => {
      expose({ prepareAction: async () => true, requestClose: async () => true })
      return () =>
        h('div', { 'data-business-cards': '', 'data-policy': JSON.stringify(props.task.dataPolicy) }, '业务办理卡片')
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskSubmissionMaterial.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskPlanDialog.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskAssignmentDialog.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskClaimDialog.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/UserSelector/index.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskPersonalSplit.vue', () => ({
  default: defineComponent({
    props: ['parent'],
    emits: ['close', 'saved'],
    setup:
      (p, { emit }) =>
      () =>
        h('section', { 'data-split-parent': p.parent.id }, [
          h('button', { onClick: () => emit('close') }, '取消拆分'),
          h('button', { onClick: () => emit('saved') }, '保存拆分')
        ])
  })
}))

function row(id: string, parentId: string | null): TaskRow {
  return {
    ...newTaskNode(parentId),
    id,
    rootId: 'root',
    title: id === 'root' ? '总任务' : '原分工',
    creatorId: 'creator',
    creatorName: '发起人',
    assigneeId: 'employee',
    assigneeName: '员工',
    assignmentMode: 'ASSIGNED',
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
    childCount: parentId ? 0 : 1,
    plans: [],
    canStart: true,
    canExecute: true,
    canEdit: true,
    blockedReason: null,
    templateId: null,
    templateVersion: null
  }
}
function detail(): Detail {
  const root = {
    ...row('root', null),
    dataPolicy: { version: 1 as const, business: 'GROUP' as const, feedback: 'GROUP' as const }
  }
  return { task: root, nodes: [root, row('child', 'root')], comments: [], events: [] }
}
const preview: TaskAdjustmentPreview = { changedIds: ['child'], addedIds: [], removedIds: [], affectedIds: ['child'] }
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 14; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少当前编排测试目标')
  return value
}
const button = (label: string, within: ParentNode = document.body) =>
  required(
    Array.from(within.querySelectorAll<HTMLButtonElement>('button')).find(
      el => el.textContent?.trim() === label || el.getAttribute('aria-label')?.startsWith(`${label}：`)
    )
  )
async function click(label: string, within?: ParentNode) {
  button(label, within).click()
  await flush()
}
async function fill(input: HTMLInputElement | HTMLTextAreaElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
const childInput = () => required(host.querySelector<HTMLInputElement>('[data-node-row="child"] input'))
const reasonInput = () => required(host.querySelector<HTMLTextAreaElement>('[data-form-label="调整原因"] textarea'))
const activeTab = () => host.querySelector('[role="tab"][aria-selected="true"]')?.textContent
async function edit() {
  await click('任务编排')
  await editChild()
}
async function editChild() {
  if (host.querySelector('[data-node-row="child"] input')) return
  required(host.querySelector<HTMLButtonElement>('[data-node-row="child"] [aria-label^="编辑任务名称"]')).click()
  await flush()
}

function flatten(nodes: VNode[]): VNode[] {
  return nodes.flatMap(node =>
    node.type === Fragment && Array.isArray(node.children) ? flatten(node.children.filter(isVNode)) : [node]
  )
}
async function mount(employeeView = false, id = 'root', initialTab?: 'arrangement', initialSelectedId?: string) {
  app = createApp(() =>
    h(TaskDetail, { id, employeeView, initialTab, initialSelectedId, onClose: state.close, onChanged: state.changed })
  )
  const plain = defineComponent({
    props: ['label', 'message', 'description'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', { 'data-form-label': p.label }, [
          p.label,
          p.message,
          p.description,
          slots.default?.(),
          slots.action?.()
        ])
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'AAlert',
    'ASpin',
    'ADescriptions',
    'ADescriptionsItem',
    'ATag',
    'ASpace',
    'AMenu',
    'ATimeline',
    'ATimelineItem',
    'AEmpty',
    'AList',
    'AListItem',
    'ASelect',
    'ACheckbox'
  ])
    app.component(name, plain)
  app.component(
    'ADropdown',
    defineComponent({
      setup: (_, { slots }) => {
        const open = ref(false)
        return () =>
          h('div', [
            h('span', { onClick: () => (open.value = !open.value) }, slots.default?.()),
            open.value ? slots.overlay?.() : null
          ])
      }
    })
  )
  app.component(
    'AMenuItem',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { role: 'menuitem', disabled: p.disabled }, slots.default?.())
    })
  )
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
  const input = (tag: 'input' | 'textarea') =>
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup: (p, { emit, expose }) => {
        expose({ focus: vi.fn() })
        return () =>
          h(tag, {
            value: p.value,
            disabled: p.disabled,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
      }
    })
  app.component('AInput', input('input'))
  app.component('ATextarea', input('textarea'))
  app.component('ADatePicker', input('input'))
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup: (p, { emit, slots }) => {
        provide(
          'workspace-radio-value',
          computed(() => p.value)
        )
        provide('workspace-radio-select', (value: string) => emit('update:value', value))
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup: (p, { slots }) => {
        const selected = inject<ComputedRef<string>>('workspace-radio-value')
        const select = inject<(value: string) => void>('workspace-radio-select')
        return () =>
          h(
            'button',
            {
              'aria-pressed': String(selected?.value === p.value),
              onClick: () => select?.(p.value)
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
      setup: (p, { emit, slots }) => {
        provide(
          'workspace-tabs-active',
          computed(() => p.activeKey)
        )
        return () => {
          const panes = flatten(slots.default?.() || []).filter(node => node.key != null)
          return h('div', [
            h(
              'nav',
              panes.map(node =>
                h(
                  'button',
                  {
                    role: 'tab',
                    'aria-selected': String(p.activeKey === node.key),
                    onClick: () => emit('update:activeKey', node.key)
                  },
                  node.props?.tab
                )
              )
            ),
            ...panes
          ])
        }
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup: (_, { slots }) => {
        const key = getCurrentInstance()?.vnode.key
        const active = inject<ComputedRef<string>>('workspace-tabs-active')
        const visited = ref(false)
        watch(
          () => active?.value === key,
          value => {
            if (value) visited.value = true
          },
          { immediate: true }
        )
        return () =>
          visited.value
            ? h(
                'section',
                { 'data-pane': key, style: { display: active?.value === key ? '' : 'none' } },
                slots.default?.()
              )
            : null
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
  state.manage = true
  state.detail.mockImplementation(async () => detail())
  state.members.mockResolvedValue([])
  state.adjustPreview.mockResolvedValue(preview)
  state.adjust.mockResolvedValue(detail())
})
afterEach(async () => {
  app?.unmount()
  await flush()
  host?.remove()
  app = undefined
  expect(document.querySelectorAll('[data-surface]').length).toBe(0)
})

describe('详情同页编排工作区', () => {
  it('已完成子任务不显示顶部刷新、删除或空操作栏', async () => {
    const value = detail()
    value.task = {
      ...value.nodes[1],
      status: 'COMPLETED',
      canDelete: false,
      deleteBlockedReason: '只能删除自己拆分的子任务'
    }
    state.detail.mockResolvedValue(value)
    await mount(true, 'child')
    expect(host.querySelector('.task-detail__actions')).toBeNull()
    expect(host.textContent).not.toContain('只能删除自己拆分的子任务')
    expect(activeTab()).toBe('任务概况')
  })

  it.each([false, true])('详情仅保留实际办理操作，员工视图：%s', async employeeView => {
    const value = detail()
    value.task = { ...value.nodes[1], canDelete: true }
    state.detail.mockResolvedValue(value)
    await mount(employeeView, 'child')
    const actions = required(host.querySelector('.task-detail__actions'))
    expect(button('开始执行', actions).disabled).toBe(false)
    expect(button('取消任务', actions)).toBeDefined()
    expect(actions.textContent).not.toContain('刷新')
    expect(actions.textContent).not.toContain('删除子任务')
  })

  function waitingDetail(): Detail {
    const own = {
      ...row('child', 'root'),
      title: '装修施工',
      predecessorIds: ['before'],
      canStart: false,
      blockedReason: '等待 现场勘察 完成'
    }
    const context = (id: string, title: string, parentId: string | null): TaskStructureNode => ({
      id,
      rootId: 'root',
      parentId,
      title,
      status: 'PENDING',
      assigneeName: '待领取',
      predecessorIds: [],
      expectedStart: '2026-10-03',
      expectedEnd: '2026-10-04',
      detailVisible: false
    })
    return {
      task: own,
      nodes: [own],
      comments: [],
      events: [],
      structure: [
        { ...context('root', '办公室装修', null), status: 'RUNNING', assigneeName: '主管' },
        context('before', '现场勘察', 'root'),
        { ...context('child', '装修施工', 'root'), predecessorIds: ['before'], detailVisible: true },
        { ...context('after', '竣工验收', 'root'), predecessorIds: ['child'], assigneeName: '同事' }
      ]
    }
  }
  it('员工从等待原因定位整组真实前置，摘要无详情、业务、拆分入口', async () => {
    state.manage = false
    state.detail.mockResolvedValue(waitingDetail())
    await mount(true, 'child')
    expect(button('开始执行').disabled).toBe(true)
    await click('查看任务编排')
    const before = required(host.querySelector<HTMLElement>('[data-node-row="before"]'))
    expect(before.getAttribute('aria-selected')).toBe('true')
    expect(before.textContent).toContain('现场勘察')
    expect(before.textContent).toContain('待领取')
    expect(before.textContent).toContain('去领取')
    expect(before.textContent).not.toContain('查看详情')
    expect(before.textContent).not.toContain('暂无内容')
    expect(host.textContent).not.toContain('等待 未命名任务 完成')
    expect(host.querySelector('[data-node-row="child"]')?.textContent).toContain('现场勘察')
    expect(host.querySelector('[data-node-row="after"]')?.textContent).toContain('同事')
    expect(host.querySelector('[data-form-label="调整原因"]')).toBeNull()
    expect(state.detail).toHaveBeenCalledOnce()
    expect(state.adjust).not.toHaveBeenCalled()
  })
  it('直接打开编排仍定位前置，图与列表使用相同关系和高亮，切回自己的任务才可拆分', async () => {
    state.manage = false
    state.detail.mockResolvedValue(waitingDetail())
    await mount(true, 'child', 'arrangement')
    expect(activeTab()).toBe('任务编排')
    await click('图上编辑')
    expect(host.querySelectorAll('[data-graph-node]')).toHaveLength(4)
    expect(host.querySelector('[data-graph-node="before"]')?.getAttribute('aria-pressed')).toBe('true')
    const edge = required(host.querySelector<HTMLButtonElement>('[data-edge-from="before"][data-edge-to="child"]'))
    expect(edge.getAttribute('data-edge-action')).toBe('unlink')
    expect(edge.disabled).toBe(true)
    expect(host.querySelector('.task-adjust__selection')?.textContent).not.toContain('拆分子任务')
    required(host.querySelector<HTMLButtonElement>('[data-graph-node="child"]')).click()
    await flush()
    expect(host.querySelector('.task-adjust__selection')?.textContent).toContain('拆分子任务')
    await click('列表')
    expect(host.querySelector('[data-node-row="child"]')?.getAttribute('aria-selected')).toBe('true')
    expect(state.detail).toHaveBeenCalledOnce()
  })
  it('从列表打开同事节点概要时优先定位指定节点，不被前置提示抢走选中', async () => {
    state.manage = false
    state.detail.mockResolvedValue(waitingDetail())
    await mount(true, 'child', 'arrangement', 'after')
    expect(activeTab()).toBe('任务编排')
    expect(host.querySelector('[data-node-row="after"]')?.getAttribute('aria-selected')).toBe('true')
    await click('图上编辑')
    expect(host.querySelector('[data-graph-node="after"]')?.getAttribute('aria-pressed')).toBe('true')
    expect(state.detail).toHaveBeenCalledOnce()
  })
  it('领取前预览用完整安全结构只读展示，不解析裁剪配置或获取人员权限', async () => {
    state.manage = false
    const value = waitingDetail()
    value.preview = { ...value.task }
    value.nodes = [{ ...value.task, schedule: null, sharing: null } as unknown as TaskRow]
    state.detail.mockResolvedValue(value)
    await mount(true, 'child', 'arrangement', 'before')
    expect(activeTab()).toBe('任务编排')
    expect(host.querySelectorAll('[data-node-row]')).toHaveLength(4)
    expect(host.querySelector('[data-node-row="before"]')?.getAttribute('aria-selected')).toBe('true')
    await click('图上编辑')
    expect(host.querySelectorAll('[data-graph-node]')).toHaveLength(4)
    expect(host.querySelector('[data-graph]')?.getAttribute('data-editable')).toBe('false')
    expect(state.members).not.toHaveBeenCalled()
    expect(host.querySelector('.task-adjust__selection')?.textContent).not.toContain('拆分子任务')
    expect(state.adjust).not.toHaveBeenCalled()
  })
  it('前置已完成的列表说明不再写成等待，同时不自动执行当前任务', async () => {
    state.manage = false
    const value = waitingDetail()
    value.structure!.find(node => node.id === 'before')!.status = 'COMPLETED'
    value.task.canStart = true
    value.task.blockedReason = null
    state.detail.mockResolvedValue(value)
    await mount(true, 'child')
    expect(button('开始执行').disabled).toBe(false)
    await click('任务编排')
    const dependencies = required(host.querySelector('[data-node-row="child"] [data-node-column="dependencies"]'))
    expect(dependencies.textContent).toContain('现场勘察 · 已完成')
    expect(dependencies.textContent).not.toContain('等待')
    expect(state.adjust).not.toHaveBeenCalled()
  })
  it.each([true, false])('员工视图=%s 时，时间安排直接表达计划开始、完成日期和工期', async employeeView => {
    const value = detail()
    value.task.expectedStart = '2026-10-03T00:00:00'
    value.task.expectedEnd = '2026-10-08T00:00:00'
    value.task.schedule = { ...value.task.schedule, mode: 'PLAN_START', offsetDays: 0, durationDays: 5 }
    state.detail.mockResolvedValue(value)
    await mount(employeeView)
    const schedule = required(host.querySelector('details [data-form-label="时间安排"]'))
    expect(schedule.textContent).toContain('计划 2026-10-03 开始，2026-10-08 完成 · 工期 5 天')
    expect(schedule.textContent).not.toMatch(/排期起点|延后 0 天/)
  })

  it('任务设置保留创建人、应用及排期，不恢复已移除的数据范围和模板版本说明', async () => {
    const value = detail()
    value.task.templateVersion = 3
    state.detail.mockResolvedValue(value)
    await mount(true)
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    expect(overview.textContent).toContain('状态未开始')
    expect(overview.textContent).toContain('负责人')
    expect(overview.textContent).toContain('预计开始暂未安排')
    expect(overview.textContent).toContain('预计完成暂未安排')
    expect(overview.textContent).not.toContain('紧急程度')
    expect(overview.textContent).toContain('优先级中')
    expect(overview.textContent).toContain('任务标准总工时未设置')
    expect(overview.querySelector('[data-form-label="实际开始时间"]')?.textContent).toBe('实际开始时间')
    expect(overview.querySelector('[data-form-label="实际完成时间"]')?.textContent).toBe('实际完成时间')
    expect(overview.textContent).toContain('任务归属当前为总任务')
    for (const label of ['验收人', '说明', '关联业务记录', '创建人', '时间安排', '业务数据'])
      expect(overview.querySelector(`[data-form-label="${label}"]`)).toBeNull()
    const more = required(host.querySelector<HTMLDetailsElement>('details[aria-label="任务设置与来源"]'))
    expect(more.open).toBe(false)
    expect(more.querySelector('summary')?.textContent).toBe('任务设置与来源')
    expect(more.querySelector('[data-form-label="创建人"]')?.textContent).toContain(value.task.creatorName)
    expect(more.textContent).toContain('所属应用独立任务')
    expect(more.textContent).toContain('时间安排暂不安排')
    expect(more.textContent).not.toContain('各业务关联项按配置范围授权')
    expect(JSON.parse(required(host.querySelector('[data-business-cards]')?.getAttribute('data-policy')))).toEqual(
      value.task.dataPolicy
    )
    expect(more.textContent).not.toContain('模板版本')
    required(more.querySelector('summary')).click()
    expect(more.open).toBe(true)
  })

  it('实际时间与验收人在执行信息中，富文本独立放在任务内容区域', async () => {
    const value = detail()
    Object.assign(value.task, {
      status: 'COMPLETED',
      expectedStart: '2030-04-01T09:00:00',
      expectedEnd: '2030-04-03T18:00:00',
      actualStart: '2030-04-01T09:30:00',
      actualEnd: '2030-04-03T17:20:00',
      acceptorId: 'manager',
      acceptorName: '验收主管',
      description: '核对现场尺寸并提交测量结果',
      effectiveWorkMinutes: 95,
      urgency: 'URGENT',
      priority: 'HIGH'
    })
    state.detail.mockResolvedValue(value)
    await mount(true)
    const overview = required(host.querySelector('[aria-label="任务执行信息"]'))
    expect(overview.textContent).toContain('预计开始2030-04-01')
    expect(overview.textContent).toContain('预计完成2030-04-03')
    expect(overview.textContent).toContain('实际开始时间2030-04-01 09:30')
    expect(overview.textContent).toContain('实际完成时间2030-04-03 17:20')
    expect(overview.textContent).toContain('任务标准总工时1 小时 35 分钟')
    expect(overview.textContent).toContain('验收人验收主管')
    expect(host.querySelector('[aria-label="任务内容"]')?.textContent).toContain('核对现场尺寸并提交测量结果')
    expect(overview.textContent).not.toContain('紧急程度')
    expect(overview.textContent).toContain('优先级高')
    const labels = Array.from(overview.querySelectorAll('[data-form-label]')).map(item =>
      item.getAttribute('data-form-label')
    )
    expect(labels.indexOf('实际开始时间')).toBeLessThan(labels.indexOf('优先级'))
    expect(labels.indexOf('实际完成时间')).toBeLessThan(labels.indexOf('优先级'))
  })

  it.each(['PENDING', 'RUNNING', 'PENDING_ACCEPTANCE', 'CANCELLED'] as const)(
    '%s 任务固定显示空完成时间，不将取消或验收前时间误作完成时间',
    async status => {
      const value = detail()
      value.task.status = status
      value.task.actualEnd = '2030-04-03T17:20:00'
      state.detail.mockResolvedValue(value)
      await mount(true)
      expect(host.querySelector('[data-form-label="实际完成时间"]')?.textContent).toBe('实际完成时间')
    }
  )

  it('直属上级与总任务相同只展示一次，受限上级只有文字且不请求详情', async () => {
    const child = {
      ...row('child', 'root'),
      ancestorContext: [
        {
          id: 'root',
          parentId: null,
          title: '受限总任务',
          assigneeName: '主管',
          status: 'RUNNING' as const,
          detailVisible: false
        }
      ]
    }
    state.detail.mockResolvedValue({ ...detail(), task: child, nodes: [child] })
    await mount(true, 'child')
    const location = required(host.querySelector('[data-form-label="任务归属"]'))
    expect(location.textContent?.match(/受限总任务/g)).toHaveLength(1)
    expect(location.querySelector('button')).toBeNull()
    expect(state.detail).toHaveBeenCalledExactlyOnceWith('child')
  })

  it('前置链接定位编排，兼容旧响应中不可见前置只保留文字', async () => {
    const value = detail()
    const predecessor = {
      ...row('before', 'root'),
      title: '现场确认',
      status: 'COMPLETED' as const,
      assigneeName: '王工'
    }
    value.task.predecessorIds = ['before', 'hidden']
    value.nodes.push(predecessor)
    state.detail.mockImplementation(async (id: string) => ({
      ...value,
      task: required(value.nodes.find(node => node.id === id))
    }))
    await mount(true)
    const predecessors = required(host.querySelector('[data-form-label="前置任务"]'))
    expect(predecessors.textContent).toContain('现场确认 · 已完成 · 王工')
    expect(predecessors.textContent).toContain('前置任务当前不可查看')
    expect(predecessors.querySelectorAll('button')).toHaveLength(1)
    expect(state.detail).toHaveBeenCalledExactlyOnceWith('root')
    required(predecessors.querySelector<HTMLButtonElement>('button')).click()
    await flush()
    expect(activeTab()).toBe('任务编排')
    expect(host.querySelector('[data-node-row="before"]')?.getAttribute('aria-selected')).toBe('true')
    expect(state.detail).toHaveBeenCalledOnce()
  })

  it.each([true, false])('员工视图=%s时历史默认范围明确，切换范围和任务都恢复最近十条', async employeeView => {
    const value = detail()
    value.events = ['root', 'child'].flatMap((taskId, group) =>
      Array.from({ length: 12 }, (_, i) => ({
        id: `${taskId}-${i}`,
        taskId,
        type: 'CREATED',
        actorId: 'employee',
        actorName: '员工',
        note: `记录-${taskId}-${i}`,
        createdAt: `2026-10-04T10:${String(group * 12 + i).padStart(2, '0')}:00`
      }))
    )
    state.detail.mockImplementation(async (id: string) => ({
      ...value,
      task: required(value.nodes.find(node => node.id === id))
    }))
    await mount(employeeView)
    const history = () => required(host.querySelector('aside[aria-label="操作历史"]'))
    expect(button(employeeView ? '当前任务' : '整组任务', history()).getAttribute('aria-pressed')).toBe('true')
    expect(history().textContent).toContain(employeeView ? '记录-root-11' : '记录-child-11')
    expect(history().textContent).not.toContain(employeeView ? '记录-child-' : '记录-root-')
    await click('当前任务', history())
    await click('查看更多历史', history())
    expect(history().textContent).toContain('记录-root-0')
    await click('整组任务', history())
    expect(history().textContent).not.toContain('记录-child-0')
    await click('查看更多历史', history())
    expect(history().textContent).toContain('记录-child-0')
    await click('当前任务', history())
    expect(history().textContent).not.toContain('记录-root-0')
    await click('查看更多历史', history())
    await click('任务编排')
    await click('图上编辑')
    required(host.querySelector<HTMLButtonElement>('[data-graph-node="child"]')).click()
    await flush()
    expect(activeTab()).toBe('任务编排')
    expect(state.detail).toHaveBeenCalledOnce()
    await click('查看详情', required(host.querySelector('[aria-label="当前选中任务"]')))
    await click('任务概况')
    expect(button(employeeView ? '当前任务' : '整组任务', history()).getAttribute('aria-pressed')).toBe('true')
    expect(history().textContent).toContain('记录-child-11')
    expect(history().textContent).not.toContain('记录-child-0')
    expect(state.detail).toHaveBeenCalledTimes(2)
  })

  it('当前任务没有历史时显示该范围空态，切换到可见整组仍能查看其他任务记录', async () => {
    const value = detail()
    value.events = [
      {
        id: 'child-created',
        taskId: 'child',
        type: 'CREATED',
        actorId: 'employee',
        actorName: '员工',
        note: '子任务创建记录',
        createdAt: '2026-10-04T10:00:00'
      }
    ]
    state.detail.mockResolvedValue(value)
    await mount(true)
    const history = required(host.querySelector('aside[aria-label="操作历史"]'))
    expect(history.textContent).toContain('当前任务暂无操作记录')
    expect(history.textContent).not.toContain('子任务创建记录')
    await click('整组任务', history)
    expect(history.textContent).toContain('子任务创建记录')
    expect(history.textContent).not.toContain('当前任务暂无操作记录')
    expect(state.detail).toHaveBeenCalledOnce()
  })

  it('老板详情不恢复已移除的待推进事项，只在编排中查看任务状态', async () => {
    const value = detail()
    value.nodes = [
      { ...value.task, status: 'RUNNING' },
      { ...row('open', 'root'), title: '等待领取', assignmentMode: 'OPEN' },
      { ...row('unassigned', 'root'), title: '安排负责人', assignmentMode: 'UNASSIGNED' },
      { ...row('acceptance', 'root'), title: '确认成果', status: 'PENDING_ACCEPTANCE' },
      {
        ...row('blocked', 'root'),
        title: '后续施工',
        blockedReason: '等待现场确认完成',
        ancestorContext: [
          {
            id: 'reference',
            parentId: null,
            title: '不可查看的上级',
            assigneeName: null,
            status: 'PENDING_ACCEPTANCE',
            detailVisible: false
          }
        ]
      },
      { ...row('closed', 'root'), title: '已取消的任务', status: 'CANCELLED', blockedReason: '旧阻塞原因' }
    ]
    state.detail.mockResolvedValue(value)
    await mount()
    expect(host.querySelector('[aria-label="当前可见任务待推进事项"]')).toBeNull()
    expect(state.detail).toHaveBeenCalledOnce()
    await click('任务编排')
    expect(host.querySelector('[data-node-row="acceptance"]')?.textContent).toContain('待验收')
  })

  it('员工任务概况不展示管理待推进事项', async () => {
    const value = detail()
    value.task.blockedReason = '等待前置任务完成'
    state.detail.mockResolvedValue(value)
    await mount(true)
    expect(host.querySelector('[aria-label="当前可见任务待推进事项"]')).toBeNull()
  })

  it('任务概况直接显示评论与右侧历史，不再单设页签；默认只展示最近十条历史', async () => {
    const value = detail()
    value.events = Array.from({ length: 12 }, (_, i) => ({
      id: `event-${i}`,
      taskId: 'root',
      type: 'CREATED',
      actorId: 'employee',
      actorName: '员工',
      note: `历史记录-${i}`,
      createdAt: `2026-10-04T10:${String(i).padStart(2, '0')}:00`
    }))
    state.detail.mockResolvedValue(value)
    await mount()
    expect(activeTab()).toBe('任务概况')
    const labels = Array.from(host.querySelectorAll('[role="tab"]')).map(item => item.textContent)
    expect(labels).toEqual(['任务概况', '任务编排'])
    expect(host.querySelector('[data-pane="overview"] [aria-label="任务评论"]')).not.toBeNull()
    const history = required(host.querySelector('aside[aria-label="操作历史"]'))
    expect(history.textContent).toContain('历史记录-11')
    expect(history.textContent).not.toContain('历史记录-0')
    await click('查看更多历史')
    expect(history.textContent).toContain('历史记录-0')
    await fill(required(host.querySelector<HTMLTextAreaElement>('[aria-label="评论内容"]')), '保留评论草稿')
    await click('任务编排')
    await click('任务概况')
    expect(required(host.querySelector<HTMLTextAreaElement>('[aria-label="评论内容"]')).value).toBe('保留评论草稿')
  })
  it('仅保留一个详情抽屉，业务卡片位于概况评论之前，进入编排不再叠加抽屉', async () => {
    await mount()
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(
      Array.from(host.querySelectorAll('[role="tab"]')).filter(tab => tab.textContent === '业务数据与反馈')
    ).toHaveLength(0)
    const overview = required(host.querySelector('[data-pane="overview"]'))
    const cards = required(overview.querySelector('[data-business-cards]'))
    const comments = required(overview.querySelector('[aria-label="任务评论"]'))
    expect(cards.compareDocumentPosition(comments) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
    expect(host.querySelector('.task-detail__actions')?.textContent).not.toContain('业务数据与反馈')
    await edit()
    expect(activeTab()).toBe('任务编排')
    expect(host.querySelector('[aria-label="调整任务安排"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('列表和同页图共享草稿，节点配置返回编排不保存也不丢输入', async () => {
    await mount()
    await edit()
    await fill(childInput(), '列表改名')
    await click('图上编辑')
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(host.querySelector('[data-graph-node="child"]')?.textContent).toBe('列表改名')
    required(host.querySelector<HTMLButtonElement>('[data-graph-node="child"]')).click()
    await flush()
    expect(host.querySelector('[data-inspector-title]')).toBeNull()
    required(host.querySelector<HTMLButtonElement>('[data-graph-configure="child"]')).click()
    await flush()
    await fill(required(host.querySelector<HTMLInputElement>('[data-inspector-title]')), '属性栏改名')
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(2)
    await click('返回编排')
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(host.querySelector('[data-graph-node="child"]')?.textContent).toBe('属性栏改名')
    await click('列表')
    await editChild()
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(childInput().value).toBe('属性栏改名')
    expect(state.adjustPreview).not.toHaveBeenCalled()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('管理端列表和图留在同一个详情页签内，切换不关闭详情或遮挡导航', async () => {
    await mount()
    await click('任务编排')
    const actions = required(host.querySelector('[data-node-row="child"] .task-node-editor__row-actions'))
    expect(actions.querySelectorAll('button')).toHaveLength(1)
    const more = required(actions.querySelector<HTMLButtonElement>('[aria-label^="更多操作："]'))
    expect(more.textContent?.trim()).toBe('')
    more.click()
    await flush()
    expect(Array.from(actions.querySelectorAll('[role="menuitem"]')).map(item => item.textContent?.trim())).toEqual([
      '批量拆分',
      '添加连续下级',
      '添加并行任务',
      '移动到其他任务下',
      '移除任务'
    ])
    expect(actions.textContent).not.toContain('任务配置')
    more.click()
    await flush()
    await click('图上编辑')
    expect(host.querySelector('[aria-label="任务编排工作区"] [data-graph]')).not.toBeNull()
    expect(host.querySelector('[aria-label="当前任务位置"]')).not.toBeNull()
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    await click('列表')
    expect(activeTab()).toBe('任务编排')
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(state.close).not.toHaveBeenCalled()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('员工详情也提供列表和同页只读图，不增加整组编辑权限或关系图抽屉', async () => {
    state.manage = false
    await mount(true)
    expect(host.querySelector('[data-graph]')).toBeNull()
    await click('任务编排')
    expect(host.querySelector('[data-node-table]')).not.toBeNull()
    expect(host.querySelector('[data-node-row="child"] input')).toBeNull()
    await click('图上编辑')
    expect(host.querySelectorAll('[data-graph]')).toHaveLength(1)
    expect(host.querySelector('[data-graph]')?.getAttribute('data-editable')).toBe('false')
    expect(host.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1)
    expect(host.textContent).not.toContain('调整任务安排')
    expect(host.textContent).not.toContain('图上编排')
    expect(host.textContent).toContain('原安排只读')
    expect(host.querySelector('[data-form-label="调整原因"]')).toBeNull()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('上级只有位置摘要时保留简明位置，不请求不可见总任务详情', async () => {
    const child = {
      ...row('child', 'root'),
      ancestorContext: [
        {
          id: 'root',
          parentId: null,
          title: '受限总任务',
          assigneeName: '主管',
          status: 'RUNNING' as const,
          detailVisible: false
        }
      ]
    }
    state.detail.mockResolvedValue({ ...detail(), task: child, nodes: [child] })
    await mount(true)
    await click('任务编排')
    await click('图上编辑')
    expect(host.textContent).toContain('当前位置：受限总任务 / 原分工')
    expect(host.textContent).toContain('仅展示你有权限查看的任务')
    expect(host.querySelector('[data-graph-node="root"]')).toBeNull()
    expect(state.detail).toHaveBeenCalledTimes(1)
  })

  it.each(['页签', '关闭'] as const)('%s时确认取消保留草稿，确认放弃才退出', async action => {
    await mount()
    await edit()
    await fill(childInput(), '未保存分工')
    const leave = async () => {
      if (action === '页签') await click('任务概况')
      else {
        required(host.querySelector<HTMLButtonElement>('[data-surface="drawer"] > [data-surface-close]')).click()
        await flush()
      }
    }
    await leave()
    expect(document.querySelectorAll('[data-surface="modal"]')).toHaveLength(1)
    await click('继续编辑')
    expect(activeTab()).toBe('任务编排')
    expect(childInput().value).toBe('未保存分工')
    expect(state.detail).toHaveBeenCalledOnce()
    expect(state.close).not.toHaveBeenCalled()
    await leave()
    await click('放弃修改')
    if (action !== '关闭') expect(host.querySelector('[data-form-label="调整原因"]')).toBeNull()
    if (action === '页签') expect(activeTab()).toBe('任务概况')
    if (action === '关闭') expect(state.close).toHaveBeenCalledOnce()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('预览请求中不允许离开或关闭，也不重复提交', async () => {
    let resolve: ((value: TaskAdjustmentPreview) => void) | undefined
    state.adjustPreview.mockReturnValueOnce(
      new Promise<TaskAdjustmentPreview>(yes => {
        resolve = yes
      })
    )
    await mount()
    await edit()
    await fill(childInput(), '预览中的分工')
    await fill(reasonInput(), '更新分工')
    await click('预览调整影响')
    await click('预览调整影响')
    await click('任务概况')
    required(host.querySelector<HTMLButtonElement>('[data-surface="drawer"] > [data-surface-close]')).click()
    await flush()
    expect(activeTab()).toBe('任务编排')
    expect(state.detail).toHaveBeenCalledOnce()
    expect(state.close).not.toHaveBeenCalled()
    expect(document.querySelector('[data-surface="modal"]')).toBeNull()
    expect(button('取消调整').disabled).toBe(true)
    expect(state.adjustPreview).toHaveBeenCalledOnce()
    required(resolve)(preview)
    await flush()
    expect(button('确认调整任务').disabled).toBe(false)
  })

  it('保存请求中锁住退出与重复确认，完成后才退出并刷新', async () => {
    let resolve: ((value: Detail) => void) | undefined
    state.adjust.mockReturnValueOnce(
      new Promise<Detail>(yes => {
        resolve = yes
      })
    )
    await mount()
    await edit()
    await fill(childInput(), '待保存分工')
    await fill(reasonInput(), '已确认调整')
    await click('预览调整影响')
    await click('确认调整任务')
    await click('确认调整任务')
    await click('任务概况')
    required(host.querySelector<HTMLButtonElement>('[data-surface="drawer"] > [data-surface-close]')).click()
    await flush()
    expect(state.adjust).toHaveBeenCalledOnce()
    expect(state.detail).toHaveBeenCalledOnce()
    expect(state.close).not.toHaveBeenCalled()
    expect(state.changed).not.toHaveBeenCalled()
    expect(activeTab()).toBe('任务编排')
    expect(host.querySelector('[data-node-row="child"]')?.textContent).toContain('待保存分工')
    expect(button('取消调整').disabled).toBe(true)
    expect(document.querySelector('[data-surface="modal"]')).toBeNull()
    required(resolve)(detail())
    await flush()
    expect(state.changed).toHaveBeenCalledOnce()
    expect(state.detail).toHaveBeenCalledTimes(2)
    expect(host.querySelector('[data-form-label="调整原因"]')).toBeNull()
  })

  it('非管理者仅查看列表和图，不出现调整入口或可编辑输入', async () => {
    state.manage = false
    await mount()
    await click('任务编排')
    expect(host.textContent).not.toContain('调整任务安排')
    expect(host.querySelector('[data-node-row="child"] input')).toBeNull()
    await click('图上编辑')
    expect(host.querySelector('[data-graph]')?.getAttribute('data-editable')).toBe('false')
    required(host.querySelector<HTMLButtonElement>('[data-graph-node="child"]')).click()
    await flush()
    expect(state.detail).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-graph-node="child"]')?.getAttribute('aria-pressed')).toBe('true')
    await click('查看详情', required(host.querySelector('[aria-label="当前选中任务"]')))
    expect(state.detail).toHaveBeenLastCalledWith('child')
    expect(host.querySelector('[data-inspector-title]')).toBeNull()
    expect(state.adjustPreview).not.toHaveBeenCalled()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('确认前仅预览，草稿变更立即使旧预览失效，再次预览后才保存', async () => {
    state.adjustPreview.mockResolvedValue({
      ...preview,
      schedule: {
        nodes: detail().nodes.map(node => ({
          id: node.id,
          title: node.title,
          expectedStart: '2026-10-12',
          expectedEnd: '2026-10-17',
          partial: node.id === 'root',
          warnings: node.id === 'root' ? ['部分下级未排期'] : []
        })),
        warnings: ['部分下级未排期']
      }
    })
    await mount()
    await edit()
    await fill(childInput(), '第一次调整')
    await fill(reasonInput(), '原因一')
    await click('预览调整影响')
    expect(state.adjustPreview).toHaveBeenCalledOnce()
    expect(state.adjust).not.toHaveBeenCalled()
    expect(button('确认调整任务')).toBeDefined()
    expect(host.querySelector('[aria-label="整组排期预览"]')?.textContent).toContain('2026-10-17')
    expect(host.querySelector('[aria-label="整组排期预览"]')?.textContent).toContain('部分未排期')
    await editChild()
    await fill(childInput(), '第二次调整')
    expect(host.querySelector('[aria-label="整组排期预览"]')).toBeNull()
    expect(button('预览调整影响')).toBeDefined()
    await click('预览调整影响')
    expect(state.adjustPreview).toHaveBeenCalledTimes(2)
    expect(state.adjustPreview.mock.calls[0]?.[0].nodes.find((node: TaskNodeInput) => node.id === 'child').title).toBe(
      '第一次调整'
    )
    expect(state.adjust).not.toHaveBeenCalled()
    await click('确认调整任务')
    expect(state.adjust).toHaveBeenCalledOnce()
    expect(state.adjust.mock.calls[0]?.[0].nodes.find((node: TaskNodeInput) => node.id === 'child').title).toBe(
      '第二次调整'
    )
    expect(state.changed).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-form-label="调整原因"]')).toBeNull()
  })

  it('时间、前置或结构变化使未开始的相对预测待重算，改名和执行中保留，撤销配置恢复', async () => {
    const original = detail()
    original.task.plannedStart = '2030-10-04T09:00:00'
    const scheduledNodes: TaskRow[] = [
      { ...original.task, schedule: { ...original.task.schedule, mode: 'PLAN_START' } },
      { ...row('child', 'root'), schedule: { ...newTaskNode().schedule, mode: 'PLAN_START' } },
      {
        ...row('after', 'root'),
        title: '后续分工',
        predecessorIds: ['child'],
        schedule: { ...newTaskNode().schedule, mode: 'PREDECESSOR' }
      },
      { ...row('legacy', 'root'), title: '旧相对规则', schedule: { ...newTaskNode().schedule, mode: 'T0' } },
      { ...row('fixed', 'root'), title: '固定日期', schedule: { ...newTaskNode().schedule, mode: 'FIXED' } },
      {
        ...row('running', 'root'),
        title: '进行中分工',
        status: 'RUNNING',
        actualStart: '2030-10-04T09:00:00',
        schedule: { ...newTaskNode().schedule, mode: 'PLAN_START' }
      }
    ]
    original.nodes = scheduledNodes.map(node => ({
      ...node,
      expectedStart: '2030-10-04T09:00:00',
      expectedEnd: '2030-10-05T09:00:00'
    }))
    const relativeIds = ['root', 'child', 'after', 'legacy']
    const assertRuntime = (stale: boolean) => {
      const runtime = JSON.parse(required(host.querySelector('[data-runtime-nodes]')?.textContent)) as Array<
        TaskRow & { expectedStale?: boolean }
      >
      expect(runtime.map(node => node.id)).toEqual(original.nodes.map(node => node.id))
      for (const node of runtime) {
        const expectedStale = stale && relativeIds.includes(node.id)
        expect(node.expectedStale === true, `${node.id} 是否待重算`).toBe(expectedStale)
        expect(node.expectedStart, `${node.id} 预计开始`).toBe(expectedStale ? null : '2030-10-04T09:00:00')
        expect(node.expectedEnd, `${node.id} 预计结束`).toBe(expectedStale ? null : '2030-10-05T09:00:00')
      }
    }
    state.detail.mockResolvedValue(original)
    await mount()
    await edit()
    await click('图上编辑')
    assertRuntime(false)
    required(host.querySelector<HTMLButtonElement>('[data-graph-configure="child"]')).click()
    await flush()
    await fill(required(host.querySelector<HTMLInputElement>('[data-inspector-title]')), '只改名称')
    assertRuntime(false)
    const duration = required(host.querySelector<HTMLInputElement>('[data-inspector-duration]'))
    const initialDuration = duration.value
    await fill(duration, '5')
    assertRuntime(true)
    await fill(duration, initialDuration)
    assertRuntime(false)

    const anchor = required(host.querySelector<HTMLInputElement>('[data-form-label="计划开始日期"] input'))
    await fill(anchor, '2030-10-06T09:00:00')
    assertRuntime(true)
    await fill(anchor, '2030-10-04T09:00:00')
    assertRuntime(false)

    await click('link:fixed:after')
    assertRuntime(true)
    await click('unlink:fixed:after')
    assertRuntime(false)

    await click('列表')
    await click('拆分子任务', required(host.querySelector('[data-node-row="child"]')))
    await click('图上编辑')
    assertRuntime(true)
    await click('列表')
    required(host.querySelector<HTMLButtonElement>('[aria-label="更多操作：未命名任务"]')).click()
    await flush()
    await click('取消新增')
    await click('图上编辑')
    assertRuntime(false)
    expect(state.adjustPreview).not.toHaveBeenCalled()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('从子任务打开时默认高亮该节点，列表和图互相保留选择，选中不改变详情操作对象', async () => {
    const value = detail()
    value.nodes.push({ ...row('other', 'root'), title: '同级分工' })
    state.detail.mockImplementation(async (id: string) => ({
      ...value,
      task: required(value.nodes.find(node => node.id === id))
    }))
    await mount(false, 'child')
    await click('任务编排')
    expect(host.querySelector('[data-node-row="child"]')?.getAttribute('aria-selected')).toBe('true')
    expect(host.querySelector('[aria-label="当前选中任务"]')?.textContent).toContain('当前选中：原分工')
    await click('图上编辑')
    expect(host.querySelector('[data-graph-node="child"]')?.getAttribute('aria-pressed')).toBe('true')
    required(host.querySelector<HTMLButtonElement>('[data-graph-node="other"]')).click()
    await flush()
    expect(host.querySelector('[data-graph-node="other"]')?.getAttribute('aria-pressed')).toBe('true')
    expect(host.querySelector('[aria-label="当前选中任务"]')?.textContent).toContain('当前选中：同级分工')
    expect(state.detail).toHaveBeenCalledExactlyOnceWith('child')
    await click('列表')
    expect(host.querySelector('[data-node-row="other"]')?.getAttribute('aria-selected')).toBe('true')
    required(host.querySelector<HTMLElement>('[data-node-row="root"]')).click()
    await flush()
    await click('图上编辑')
    expect(host.querySelector('[data-graph-node="root"]')?.getAttribute('aria-pressed')).toBe('true')
    expect(state.detail).toHaveBeenCalledOnce()
    await click('查看详情', required(host.querySelector('[aria-label="当前选中任务"]')))
    expect(state.detail).toHaveBeenLastCalledWith('root')
    expect(state.detail).toHaveBeenCalledTimes(2)
    expect(host.querySelector('[aria-label="当前任务位置"]')?.textContent).toContain('总任务')
    await click('返回上一任务')
    expect(host.querySelector('[aria-label="当前选中任务"]')?.textContent).toContain('当前选中：总任务')
    expect(host.querySelector('[data-graph-node="root"]')?.getAttribute('aria-pressed')).toBe('true')
  })

  it('员工可拆分自己未结束的节点，不能拆分同事或已结束节点，也不能调整整组原配置', async () => {
    state.manage = false
    const value = detail()
    value.nodes = [
      { ...value.task, assigneeId: 'manager', assigneeName: '主管' },
      { ...row('child', 'root'), status: 'RUNNING' },
      { ...row('colleague', 'root'), assigneeId: 'colleague', title: '同事任务' },
      { ...row('finished', 'root'), status: 'COMPLETED', title: '已结束任务' }
    ]
    state.detail.mockResolvedValue({ ...value, task: value.nodes[0] })
    await mount(true)
    await click('任务编排')
    const taskRow = (id: string) => required(host.querySelector(`[data-node-row="${id}"]`))
    for (const id of ['root', 'colleague', 'finished'])
      expect(taskRow(id).querySelector('[aria-label^="拆分子任务"]')).toBeNull()
    expect(host.querySelector('[aria-label^="编辑任务名称"]')).toBeNull()
    await click('拆分子任务', taskRow('child'))
    expect(host.querySelector('[data-split-parent="child"]')).not.toBeNull()
    await click('取消拆分')
    await click('图上编辑')
    required(host.querySelector<HTMLButtonElement>('[data-graph-node="child"]')).click()
    await flush()
    const selected = required(host.querySelector('[aria-label="当前选中任务"]'))
    expect(selected.textContent).toContain('已开始，原安排已锁定')
    await click('拆分子任务', selected)
    expect(host.querySelector('[data-split-parent="child"]')).not.toBeNull()
    expect(host.querySelector('[data-graph]')?.getAttribute('data-editable')).toBe('false')
    expect(state.adjustPreview).not.toHaveBeenCalled()
    expect(state.adjust).not.toHaveBeenCalled()
  })

  it('整组运行中仍能调整未开始节点，已开始节点原配置冻结；整组待验收后只读', async () => {
    const value = detail()
    value.nodes = [
      { ...value.task, status: 'RUNNING' },
      row('child', 'root'),
      { ...row('running', 'root'), title: '执行中工作', status: 'RUNNING' },
      { ...row('finished', 'root'), title: '历史工作', status: 'COMPLETED' }
    ]
    state.detail.mockResolvedValue({ ...value, task: value.nodes[0] })
    await mount()
    await edit()
    expect(childInput().disabled).toBe(false)
    for (const id of ['root', 'running', 'finished'])
      expect(host.querySelector(`[data-node-row="${id}"] [aria-label^="编辑任务名称"]`)).toBeNull()
    expect(button('拆分子任务', required(host.querySelector('[data-node-row="running"]'))).disabled).toBe(false)
    expect(button('拆分子任务', required(host.querySelector('[data-node-row="finished"]'))).disabled).toBe(true)
    value.nodes[0] = { ...value.nodes[0], status: 'PENDING_ACCEPTANCE' }
    state.detail.mockResolvedValue({ ...value, task: value.nodes[0] })
    app?.unmount()
    host.remove()
    await mount()
    await click('任务编排')
    expect(host.querySelector('[aria-label^="编辑任务名称"]')).toBeNull()
    expect(host.querySelector('[aria-label="当前选中任务"]')?.textContent).toContain(
      '整组任务已进入验收或已结束，编排只读'
    )
    await click('图上编辑')
    expect(host.querySelector('[data-graph]')?.getAttribute('data-editable')).toBe('false')
    expect(host.querySelector('[data-form-label="调整原因"]')).toBeNull()
    expect(state.adjust).not.toHaveBeenCalled()
  })
})
