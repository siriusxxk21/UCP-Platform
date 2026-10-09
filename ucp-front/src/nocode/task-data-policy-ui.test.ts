// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, getCurrentInstance, h, inject, nextTick, provide, ref, type App } from 'vue'
import TaskEntryWorkspace from '@/views/nocode/task-center/TaskEntryWorkspace.vue'
import TaskNodeFields from '@/views/nocode/task-center/TaskNodeFields.vue'
import { newTaskNode } from './task-center'
import type { TaskRow } from '@/types/nocode/task-center'
import type { TaskWorkEntry, TaskWorkItem } from '@/types/nocode/task-work-entries'

const api = vi.hoisted(() => ({ entryList: vi.fn(), entryPage: vi.fn(), entryForm: vi.fn(), entryDelete: vi.fn() }))
const confirm = vi.hoisted(() => vi.fn())
const editorClose = vi.hoisted(() => vi.fn())
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'policy-user' } }) }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/nocode/task-confirmation', () => ({ useTaskConfirmation: () => ({ confirm }) }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/views/nocode/task-center/TaskEntryRecordEditor.vue', () => ({
  default: defineComponent({
    props: ['target', 'readonly'],
    setup: (props, { expose }) => {
      expose({ requestClose: editorClose })
      return () => h('div', { 'data-editor': JSON.stringify(props.target), 'data-readonly': String(props.readonly) })
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskBindingPicker.vue', () => ({
  default: { render: () => h('div', '业务资源选择器') }
}))
vi.mock('@/views/nocode/task-center/TaskEntriesEditor.vue', () => ({
  default: defineComponent({
    props: ['readonly', 'legacyPolicy'],
    setup: props => () =>
      h(
        'div',
        { 'data-config-readonly': String(props.readonly), 'data-legacy-policy': JSON.stringify(props.legacyPolicy) },
        '业务办理项配置'
      )
  })
}))
vi.mock('@/views/nocode/task-center/TaskAssignmentFields.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskScheduleFields.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title'],
    setup:
      (p, { slots }) =>
      () =>
        p.open ? h('section', [h('h3', p.title), slots.formItems?.()]) : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource'],
    setup:
      (p, { slots }) =>
      () =>
        h('section', [
          slots.search?.(),
          slots.title?.(),
          slots.actions?.(),
          ...p.dataSource.map((record: TaskWorkItem) =>
            h('div', { 'data-record': record.record?.id }, slots.bodyCell?.({ column: { key: 'actions' }, record }))
          )
        ])
  })
}))
const policy = { version: 1 as const, business: 'ALL' as const, feedback: 'GROUP' as const }
const task = (): TaskRow => ({
  ...newTaskNode(),
  id: 'child',
  rootId: 'root',
  dataPolicy: policy,
  status: 'RUNNING',
  creatorId: 1,
  creatorName: '管理者',
  assigneeName: '员工',
  project: null,
  business: null,
  baselineStart: null,
  baselineEnd: null,
  expectedStart: null,
  expectedEnd: null,
  actualStart: null,
  actualEnd: null,
  createdAt: '',
  revision: 2,
  instanceRevision: 2,
  childCount: 0,
  plans: [],
  canStart: false,
  canExecute: true,
  canEdit: false,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
const entry = (key: string, category: 'BUSINESS' | 'FEEDBACK', effectivePolicy: 'GROUP' | 'ALL'): TaskWorkEntry => ({
  config: {
    key,
    name: category === 'BUSINESS' ? '施工项目' : '施工日志',
    binding: null,
    dataMode: 'ROOT_SHARED',
    sourceNodeId: null,
    sourceEntryKey: null,
    readableFieldIds: null,
    writableFieldIds: null,
    required: false,
    allowAll: false
  },
  binding: {
    resource: {
      applicationId: 'app',
      applicationVersion: 1,
      applicationChecksum: 'sum',
      resourceId: key,
      resourceKind: 'FORM'
    },
    object: { objectId: key, versionNo: 1, checksum: 'sum' },
    recordId: null,
    requestId: null
  },
  datasetId: 'group',
  category,
  effectivePolicy,
  canWrite: true,
  canDelete: true,
  inherited: true,
  contributionCount: 1,
  submitted: false
})
let app: App | undefined, host: HTMLDivElement
let workspace: InstanceType<typeof TaskEntryWorkspace>
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(component: typeof TaskEntryWorkspace | typeof TaskNodeFields, props: Record<string, unknown>) {
  app = createApp(component, props)
  const plain = defineComponent({
    props: ['label', 'message', 'description'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.label, p.message, p.description, slots.header?.(), slots.default?.()])
  })
  for (const name of [
    'AAlert',
    'ASpin',
    'AEmpty',
    'ASpace',
    'ATag',
    'ACheckbox',
    'AInputSearch',
    'ATypographyText',
    'ATimeline',
    'ATimelineItem',
    'AForm',
    'AFormItem',
    'ARadio',
    'AInput',
    'ATextarea',
    'ACollapse',
    'ACollapsePanel'
  ])
    app.component(name, plain)
  app.component('AMenu', plain)
  app.component(
    'ADropdown',
    defineComponent({
      setup(_, { slots }) {
        const open = ref(false)
        return () =>
          h('div', [
            h('div', { onClick: () => (open.value = !open.value) }, slots.default?.()),
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
        (p, { attrs, slots }) =>
        () =>
          h('button', { ...attrs, role: 'menuitem', disabled: p.disabled }, slots.default?.())
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['change'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: p.value,
              onChange: (event: Event) => emit('change', (event.target as HTMLSelectElement).value)
            },
            (p.options || []).map((item: { value: string; label: string }) =>
              h('option', { value: item.value }, item.label)
            )
          )
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      emits: ['change'],
      setup: (_, { emit, slots }) => {
        provide('radio-change', (value: string) => emit('change', { target: { value } }))
        return () => h('div', { role: 'radiogroup' }, slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup: (p, { slots }) => {
        const change = inject<(value: string) => void>('radio-change')
        return () => h('button', { onClick: () => change?.(p.value) }, slots.default?.())
      }
    })
  )
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
        provide('tabs', (value: unknown) => emit('change', value))
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup: p => {
        const change = inject<(value: unknown) => void>('tabs'),
          key = getCurrentInstance()!.vnode.key
        return () => h('button', { onClick: () => change?.(key) }, p.tab)
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  const instance = app.mount(host)
  if (component === TaskEntryWorkspace) workspace = instance as InstanceType<typeof TaskEntryWorkspace>
  await flush()
  return instance
}
async function openList(key = '__business') {
  await workspace.select(key)
  await flush()
}
const action = (label: string) => {
  const found = Array.from(host.querySelectorAll<HTMLButtonElement>('button,a')).find(
    item => item.textContent?.trim() === label
  )
  if (!found) throw new Error(`找不到操作：${label}`)
  return found
}
async function menuAction(label: string) {
  if (!host.querySelector('[role="menuitem"]')) {
    host.querySelector<HTMLButtonElement>('[aria-label="更多数据操作"]')!.click()
    await flush()
  }
  action(label).click()
}
beforeEach(() => {
  vi.resetAllMocks()
  confirm.mockResolvedValue(true)
  editorClose.mockResolvedValue(true)
  api.entryList.mockResolvedValue([entry('__business', 'BUSINESS', 'ALL'), entry('log', 'FEEDBACK', 'GROUP')])
  api.entryForm.mockResolvedValue({
    model: { object: { fields: [] }, permissions: { actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'], readFields: [] } }
  })
  api.entryPage.mockResolvedValue({
    list: [
      {
        id: 'contribution',
        status: 'EFFECTIVE',
        sources: [],
        record: { id: 'record', revision: '9', values: {}, permissions: { actions: ['READ', 'UPDATE', 'DELETE'] } }
      }
    ],
    total: 1
  })
  api.entryDelete.mockResolvedValue(true)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('总任务统一数据授权 UI', () => {
  it('折叠标题说明已有业务上下文，不把收起的配置误报为未关联', async () => {
    await mount(TaskNodeFields, {
      modelValue: { ...newTaskNode(), binding: { applicationId: 'app', formId: 'form', entryId: null } },
      isRoot: true,
      members: [],
      businessContextSummary: '施工管理 · 幸福小区 3 号楼'
    })
    const summaries = Array.from(host.querySelectorAll('.task-optional-sections__summary')).map(
      item => item.textContent
    )
    expect(summaries).toEqual(['施工管理 · 幸福小区 3 号楼 · 已配置 1 项业务办理'])
  })
  it('子任务的折叠摘要明确继承，不声称需要重新配置', async () => {
    await mount(TaskNodeFields, { modelValue: newTaskNode('root'), rootDataPolicy: policy, members: [] })
    const summaries = Array.from(host.querySelectorAll('.task-optional-sections__summary')).map(
      item => item.textContent
    )
    expect(summaries).toEqual(['继承总任务'])
  })
  it('入口仍在加载时不误报未配置，失败可以就地重试', async () => {
    let reject!: (error: Error) => void
    api.entryList.mockImplementationOnce(
      () =>
        new Promise((_resolve, no) => {
          reject = no
        })
    )
    await mount(TaskEntryWorkspace, { task: task() })
    expect(host.textContent).not.toContain('未配置')
    expect(host.textContent).toContain('正在加载业务关联项')
    reject(new Error('读取入口失败'))
    await flush()
    action('刷新').click()
    await flush()
    expect(api.entryList).toHaveBeenCalledTimes(2)
    expect(host.textContent).not.toContain('读取入口失败')
  })
  it('无可访问入口不误称总任务未配置，待开始时给出新增禁用原因', async () => {
    api.entryList.mockResolvedValueOnce([])
    await mount(TaskEntryWorkspace, { task: task() })
    expect(host.textContent).toContain('当前任务暂无可访问的业务关联项')
    app?.unmount()
    host.remove()
    api.entryList.mockResolvedValue([{ ...entry('__business', 'BUSINESS', 'GROUP'), canWrite: false }])
    await mount(TaskEntryWorkspace, { task: { ...task(), status: 'PENDING' } })
    await openList()
    expect(host.textContent).toContain('开始当前任务后可填写')
    expect((action('新增业务数据') as HTMLButtonElement).disabled).toBe(true)
  })
  it.each([
    { category: 'BUSINESS' as const, unified: true, expected: '任务来源' },
    { category: 'FEEDBACK' as const, unified: true, expected: '任务来源' },
    { category: 'FEEDBACK' as const, unified: false, expected: '任务来源' }
  ])('旧分类统一显示业务数据来源：$category / unified=$unified', async ({ category, unified, expected }) => {
    const key = category === 'BUSINESS' ? '__business' : 'log'
    api.entryList.mockResolvedValue([entry(key, category, 'GROUP')])
    api.entryPage.mockResolvedValue({
      list: [
        {
          id: 'contribution',
          status: 'EFFECTIVE',
          record: { id: 'record', revision: '1', values: {}, permissions: { actions: ['READ'] } },
          sources: [
            {
              taskId: 'child',
              taskTitle: '施工任务',
              actorId: 'worker',
              actorName: '李工',
              entryKey: key,
              operation: 'CREATED',
              revision: '1',
              time: '2030-03-04T08:30:00'
            }
          ]
        }
      ],
      total: 1
    })
    await mount(TaskEntryWorkspace, { task: { ...task(), dataPolicy: unified ? policy : null } })
    await openList(key)
    await menuAction('任务来源')
    await flush()
    expect(Array.from(host.querySelectorAll('h3')).some(item => item.textContent === expected)).toBe(true)
  })
  it.each([new Date(2030, 2, 4, 8, 30).getTime(), '2030-03-04T08:30:00'])(
    '来源侧栏保留人员与任务，格式化毫秒或字符串时间：%s',
    async time => {
      api.entryPage.mockResolvedValue({
        list: [
          {
            id: 'contribution',
            status: 'EFFECTIVE',
            record: { id: 'record', revision: '9', values: {}, permissions: { actions: ['READ'] } },
            sources: [
              {
                taskId: 'preceding',
                taskTitle: '前序现场检查',
                actorId: 'worker',
                actorName: '李工',
                entryKey: 'log',
                operation: 'UPDATED',
                revision: '9',
                time
              }
            ]
          }
        ],
        total: 1
      })
      await mount(TaskEntryWorkspace, { task: task() })
      await openList()
      await menuAction('任务来源')
      await flush()
      expect(host.textContent).toContain('前序现场检查 · 李工')
      expect(host.textContent).toContain('修改 · 2030-03-04 08:30')
      expect(host.textContent).not.toContain(String(time))
    }
  )
  it('完成条件定位在入口加载中仍保留目标业务项，不被默认入口覆盖', async () => {
    let resolve!: (value: TaskWorkEntry[]) => void
    api.entryList.mockImplementationOnce(
      () =>
        new Promise<TaskWorkEntry[]>(yes => {
          resolve = yes
        })
    )
    const instance = (await mount(TaskEntryWorkspace, { task: task() })) as InstanceType<typeof TaskEntryWorkspace>
    await instance.select('log')
    resolve([entry('__business', 'BUSINESS', 'ALL'), entry('log', 'FEEDBACK', 'GROUP')])
    await flush()
    expect(api.entryForm).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'log' }))
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'log' }))
    expect(host.textContent).not.toContain('新增反馈')
    expect(host.textContent).toContain('新增业务数据')
  })
  it('统一卡片展示旧分类，范围筛选真实传all；GROUP无全部选项且切换回到本组', async () => {
    await mount(TaskEntryWorkspace, { task: task() })
    expect(host.querySelector('select')).toBeNull()
    expect(host.querySelectorAll('.task-business-card')).toHaveLength(2)
    expect(api.entryForm).not.toHaveBeenCalled()
    await openList()
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: '__business', all: false }))
    action('全部业务数据').click()
    await flush()
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: '__business', all: true }))
    await openList('log')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'log', all: false }))
    expect(host.textContent).not.toContain('全部业务数据')
    expect(host.textContent).toContain('本组任务数据')
    expect(host.querySelector('select[aria-label="选择业务数据表单"]')).not.toBeNull()
  })
  it('旧逐节点入口的原allowAll不得覆盖服务端按来源收紧后的GROUP', async () => {
    const item = entry('log', 'FEEDBACK', 'GROUP')
    item.config.allowAll = true
    item.config.dataScope = 'ALL'
    api.entryList.mockResolvedValue([item])
    await mount(TaskEntryWorkspace, { task: { ...task(), dataPolicy: null } })
    expect(host.textContent).not.toContain('授权范围内业务数据')
    await openList('log')
    expect(host.textContent).not.toContain('全部业务数据')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: false }))
  })
  it.each([true, false])('多个业务卡片切换保留未保存输入保护（统一授权=%s）', async unified => {
    const expense = entry('expense', 'FEEDBACK', 'GROUP')
    expense.config.name = '费用登记'
    api.entryList.mockResolvedValue([entry('log', 'FEEDBACK', 'GROUP'), expense])
    await mount(TaskEntryWorkspace, { task: { ...task(), dataPolicy: unified ? policy : null } })
    expect(Array.from(host.querySelectorAll('.task-business-card strong')).map(item => item.textContent)).toEqual([
      '施工日志',
      '费用登记'
    ])
    await openList('log')
    action('新增业务数据').click()
    await flush()
    editorClose.mockResolvedValue(false)
    await openList('expense')
    expect(api.entryForm).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'log' }))
    expect(host.querySelector('[data-editor]')).not.toBeNull()
    editorClose.mockResolvedValue(true)
    await openList('expense')
    expect(api.entryForm).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'expense' }))
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'expense', all: false }))
    expect(host.querySelector('[data-editor]')).toBeNull()
  })
  it('业务新增与修改复用表单，全部列表编辑不伪造贡献；删除含版本并保留失败重试键', async () => {
    await mount(TaskEntryWorkspace, { task: task() })
    await openList()
    action('新增业务数据').click()
    await flush()
    expect(JSON.parse(host.querySelector('[data-editor]')!.getAttribute('data-editor')!)).toMatchObject({
      taskId: 'child',
      entryKey: '__business',
      recordId: null
    })
    action('返回数据列表').click()
    await flush()
    action('全部业务数据').click()
    await flush()
    action('编辑').click()
    await flush()
    expect(JSON.parse(host.querySelector('[data-editor]')!.getAttribute('data-editor')!)).toMatchObject({
      recordId: 'record',
      contributionId: null
    })
    action('返回数据列表').click()
    await flush()
    api.entryDelete.mockRejectedValueOnce(new Error('网络中断'))
    await menuAction('删除数据')
    await flush()
    const first = api.entryDelete.mock.calls[0]![0]
    expect(first).toMatchObject({
      taskId: 'child',
      entryKey: '__business',
      recordId: 'record',
      expectedRevision: '9',
      requestKey: expect.any(String)
    })
    await menuAction('删除数据')
    await flush()
    expect(api.entryDelete.mock.calls[1]![0]).toEqual(first)
  })
  it('服务端禁止删除时不提供删除操作', async () => {
    api.entryList.mockResolvedValue([{ ...entry('__business', 'BUSINESS', 'GROUP'), canDelete: false }])
    await mount(TaskEntryWorkspace, { task: task() })
    await openList()
    host.querySelector<HTMLButtonElement>('[aria-label="更多数据操作"]')!.click()
    await flush()
    expect(Array.from(host.querySelectorAll('a,button')).some(item => item.textContent?.trim() === '删除数据')).toBe(
      false
    )
    expect(api.entryDelete).not.toHaveBeenCalled()
  })
  it('取消删除确认不请求删除，保留记录', async () => {
    confirm.mockResolvedValue(false)
    await mount(TaskEntryWorkspace, { task: task() })
    await openList()
    await menuAction('删除数据')
    await flush()
    expect(confirm).toHaveBeenCalledWith('删除这条业务记录？', expect.stringContaining('不只是'), '删除记录')
    expect(api.entryDelete).not.toHaveBeenCalled()
    expect(host.querySelector('[data-record="record"]')).not.toBeNull()
  })
  it('子节点只读继承各关联项授权，不挂载业务选择和共享字段高级配置', async () => {
    await mount(TaskNodeFields, { modelValue: newTaskNode('root'), rootDataPolicy: policy, members: [] })
    expect(host.textContent).toContain('继承总任务各业务关联项的数据范围与授权')
    expect(host.textContent).toContain('历史关联继续保留原有范围')
    for (const text of ['业务资源选择器', '业务办理项配置', '共享来源', '可补充字段'])
      expect(host.textContent).not.toContain(text)
  })
  it('总任务范围由各办理项配置，旧策略只传给卡片做兼容且不显示全局开关', async () => {
    await mount(TaskNodeFields, { modelValue: { ...newTaskNode(), dataPolicy: policy }, isRoot: true, members: [] })
    expect(host.textContent).not.toContain('业务数据范围')
    expect(host.textContent).not.toContain('过程反馈范围')
    expect(host.textContent).toContain('业务办理项配置')
    expect(JSON.parse(host.querySelector('[data-legacy-policy]')!.getAttribute('data-legacy-policy')!)).toEqual(policy)
    app!.unmount()
    host.remove()
    await mount(TaskNodeFields, {
      modelValue: { ...newTaskNode(), sharing: { mode: 'SHARED', sourceNodeId: null, writableFieldIds: [] } },
      members: []
    })
    expect(host.textContent).toContain('共享来源')
    expect(host.textContent).toContain('可补充字段')
  })
  it('模板冻结数据配置保留只读卡片，但任务名称仍可编辑', async () => {
    await mount(TaskNodeFields, {
      modelValue: {
        ...newTaskNode(),
        dataPolicy: policy,
        binding: { applicationId: 'app', formId: 'form', entryId: null }
      },
      isRoot: true,
      dataReadonly: true,
      members: []
    })
    expect(host.textContent).not.toContain('业务资源选择器')
    expect(host.querySelector('[data-config-readonly="true"]')).not.toBeNull()
    expect(host.textContent).not.toContain('业务数据范围')
    expect(host.textContent).toContain('任务名称')
  })
})
