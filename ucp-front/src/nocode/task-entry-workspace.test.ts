// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App } from 'vue'
import TaskEntryWorkspace from '@/views/nocode/task-center/TaskEntryWorkspace.vue'
import type { TaskRow } from '@/types/nocode/task-center'
import type { TaskWorkItem } from '@/types/nocode/task-work-entries'
import { storeTaskLink, taskLinkKey } from './task-link-recovery'

const api = vi.hoisted(() => ({
  entryList: vi.fn(),
  entryForm: vi.fn(),
  entryPage: vi.fn(),
  entryLink: vi.fn(),
  entryReceipt: vi.fn(),
  entryHandlingLocation: vi.fn(),
  entryMaterials: vi.fn()
}))
const actor = reactive({ id: 'workspace-user' })
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: actor }) }))
const confirm = vi.hoisted(() => vi.fn())
const editorClose = vi.hoisted(() => vi.fn())
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/nocode/task-confirmation', () => ({ useTaskConfirmation: () => ({ confirm }) }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn(), info: vi.fn() } }))
vi.mock('@/views/nocode/task-center/TaskEntryRecordEditor.vue', () => ({
  default: defineComponent({
    props: { target: Object, readonly: Boolean, snapshot: Object },
    setup: (props, { expose }) => {
      expose({ requestClose: editorClose })
      return () =>
        h('div', {
          'data-record-editor': props.target?.contributionId || props.target?.recordId || 'new',
          'data-readonly': String(props.readonly),
          'data-snapshot': props.snapshot?.record.revision
        })
    }
  })
}))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title'],
    emits: ['cancel'],
    setup:
      (p, { slots, emit }) =>
      () =>
        p.open
          ? h('section', { 'data-modal': p.title }, [
              h('button', { 'aria-label': '关闭办理窗口', onClick: () => emit('cancel') }, '关闭窗口'),
              slots.formItems?.()
            ])
          : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns'],
    setup:
      (p, { slots }) =>
      () =>
        h('section', [
          slots.search?.(),
          slots.actions?.(),
          ...p.columns.map((column: { key: string; title: string }) => h('strong', column.title)),
          ...p.dataSource.map((record: TaskWorkItem) =>
            h('div', { 'data-row': record.id }, [
              slots.bodyCell?.({ column: { key: 'status' }, record }),
              slots.bodyCell?.({ column: { key: 'actions' }, record })
            ])
          )
        ])
  })
}))
let app: App, host: HTMLElement
let workspace: InstanceType<typeof TaskEntryWorkspace>
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const resumed = vi.fn()
function deferredResult<T>() {
  let finish: ((value: T) => void) | undefined
  const promise = new Promise<T>(resolve => (finish = resolve))
  return {
    promise,
    resolve(value: T) {
      if (!finish) throw new Error('异步测试尚未初始化')
      finish(value)
    }
  }
}
async function mount(
  canWrite: boolean,
  canLink?: boolean,
  initialContributionId?: string,
  task?: TaskRow,
  openList = true,
  readonlyReason?: string
) {
  api.entryList.mockResolvedValue([
    {
      config: {
        key: 'review',
        name: '复核材料',
        dataMode: 'SOURCE_SHARED',
        required: true,
        requireOwnContribution: true
      },
      binding: { resource: { resourceId: 'object' } },
      canWrite,
      canLink
    }
  ])
  const updated = vi.fn()
  app = createApp(TaskEntryWorkspace, {
    task: task || ({ id: 'task', revision: 1 } as TaskRow),
    initialEntryKey: initialContributionId ? 'review' : undefined,
    initialContributionId,
    readonlyReason,
    onUpdated: updated,
    onResume: resumed
  })
  const plain = defineComponent({
    props: ['message'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.message, slots.default?.(), slots.action?.()])
  })
  for (const name of [
    'AAlert',
    'ASpin',
    'AEmpty',
    'ATabs',
    'ATabPane',
    'ASpace',
    'ATag',
    'ACheckbox',
    'AInputSearch',
    'ATypographyText',
    'ATimeline',
    'ATimelineItem',
    'ACollapse',
    'ACollapsePanel',
    'AForm',
    'AFormItem',
    'ASelect',
    'ATooltip',
    'AMenu'
  ])
    app.component(name, plain)
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
        (p, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, role: 'menuitem', disabled: p.disabled }, slots.default?.())
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['change'],
      setup:
        (p, { slots, emit }) =>
        () =>
          h(
            'div',
            {
              'data-scope': p.value,
              onClick: (event: MouseEvent) => {
                const value = (event.target as HTMLElement).closest('button')?.dataset.radio
                if (value) emit('change', { target: { value } })
              }
            },
            slots.default?.()
          )
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { 'data-radio': p.value }, slots.default?.())
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, disabled: p.disabled }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  workspace = app.mount(host) as InstanceType<typeof TaskEntryWorkspace>
  await flush()
  if (openList && !initialContributionId) {
    void workspace.select('review')
    await flush()
  }
  return updated
}
const linkButton = () =>
  Array.from(host.querySelectorAll('a, button')).find(el => el.textContent?.trim() === '关联到任务') as
    HTMLElement | undefined
async function openLinkMenu() {
  if (!host.querySelector('[role="menuitem"]')) {
    host.querySelector<HTMLButtonElement>('[aria-label="更多数据操作"]')?.click()
    await flush()
  }
}
async function clickLink() {
  await openLinkMenu()
  const button = linkButton()
  if (!button) throw new Error('未展示关联到任务菜单')
  button.click()
}
beforeEach(() => {
  vi.resetAllMocks()
  actor.id = 'workspace-user'
  storeTaskLink(taskLinkKey(actor.id, 'task'), null)
  storeTaskLink(taskLinkKey('other-user', 'task'), null)
  confirm.mockResolvedValue(true)
  editorClose.mockResolvedValue(true)
  api.entryForm.mockResolvedValue({
    model: { object: { objectName: '复核资料', fields: [] }, permissions: { actions: ['READ'], readFields: [] } }
  })
  api.entryPage.mockResolvedValue({
    list: [
      {
        id: 'source-contribution',
        status: 'EFFECTIVE',
        sources: [],
        record: { id: 'record', permissions: { actions: ['READ'] } }
      }
    ],
    total: 1
  })
  api.entryLink.mockResolvedValue({})
  api.entryReceipt.mockResolvedValue(null)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('业务数据状态与精简行操作', () => {
  it.each([
    ['EFFECTIVE', '已保存', true],
    ['SUBMITTED', '审批中', false],
    ['REJECTED', '审批未通过', true],
    ['CANCELED', '已撤回', true],
    ['APPLY_FAILED', '保存失败', false],
    ['FUTURE_STATE', '状态待确认', false]
  ])('状态 %s 展示为 %s，申请锁定时不显示编辑', async (status, label, editable) => {
    api.entryPage.mockResolvedValue({
      list: [
        { id: 'item', status, sources: [], record: { id: 'record', permissions: { actions: ['READ', 'UPDATE'] } } }
      ],
      total: 1
    })
    await mount(true)
    const row = host.querySelector('[data-row="item"]')
    if (!row) throw new Error('未展示数据行')
    expect(host.textContent).toContain('数据状态')
    expect(host.textContent).not.toContain('办理结果')
    expect(row.textContent).toContain(label)
    expect(row.textContent).not.toContain('已生效')
    expect(row.textContent?.includes('编辑')).toBe(editable)
    expect(row.querySelectorAll('a').length).toBe(editable ? 2 : 1)
    expect(row.querySelector('[aria-label="更多数据操作"]')).not.toBeNull()
    expect(row.querySelector('[role="menuitem"]')).toBeNull()
  })

  it('次要操作收进更多，任务来源可查看且只读时不开放关联和删除', async () => {
    api.entryPage.mockResolvedValue({
      list: [
        {
          id: 'item',
          status: 'EFFECTIVE',
          sources: [{ taskTitle: '设备登记任务', actorName: '张三', operation: 'CREATED' }],
          record: { id: 'record', permissions: { actions: ['READ', 'UPDATE', 'DELETE'] } }
        }
      ],
      total: 1
    })
    await mount(false)
    const row = host.querySelector('[data-row="item"]')
    if (!row) throw new Error('未展示数据行')
    ;(row.querySelector('[aria-label="更多数据操作"]') as HTMLButtonElement).click()
    await flush()
    const items = Array.from(row.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'))
    expect(items.map(item => item.textContent?.trim())).toEqual(['数据操作记录', '任务来源'])
    expect(row.textContent).not.toContain('编辑')
    const source = items.find(item => item.textContent?.trim() === '任务来源')
    if (!source) throw new Error('未展示任务来源入口')
    source.click()
    await flush()
    expect(host.querySelector('[data-modal="任务来源"]')?.textContent).toContain('设备登记任务 · 张三')
    expect(api.entryLink).not.toHaveBeenCalled()
  })
  it('待审批新增还没有业务记录时，不提供历史查询、关联或删除入口', async () => {
    api.entryPage.mockResolvedValue({
      list: [
        {
          id: 'pending',
          status: 'SUBMITTED',
          requestId: 'request',
          sources: [],
          record: { id: null, permissions: { actions: ['READ'] } }
        }
      ],
      total: 1
    })
    await mount(true)
    const row = host.querySelector('[data-row="pending"]')
    expect(row?.textContent).toContain('审批中')
    expect(row?.querySelector('[aria-label="更多数据操作"]')).toBeNull()
    expect(row?.textContent).not.toContain('编辑')
    row?.querySelector('a')?.click()
    await flush()
    expect(host.querySelector('[data-record-editor="pending"]')?.getAttribute('data-readonly')).toBe('true')
  })
})
describe('业务卡片与单层大办理窗口', () => {
  const button = (label: string) => {
    const found = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(
      item => item.textContent?.trim() === label
    )
    if (!found) throw new Error(`未找到按钮：${label}`)
    return found
  }
  const scopeEntry = (dataScope: 'GROUP' | 'ALL', key = 'review') => ({
    config: { key, name: '复核材料', dataMode: 'ROOT_SHARED', dataScope },
    effectivePolicy: dataScope,
    binding: { resource: { resourceId: 'object' } },
    canWrite: false,
    submitted: true
  })
  const clickScope = async (value: string) => {
    const button = host.querySelector<HTMLButtonElement>(`[data-radio="${value}"]`)
    expect(button).not.toBeNull()
    button?.click()
    await flush()
  }
  it('三段数据范围互斥：默认本组，本节点不是当前员工，全部不残留节点条件', async () => {
    api.entryList.mockResolvedValueOnce([scopeEntry('ALL')])
    await mount(false, false, undefined, { id: 'task', revision: 1, dataPolicy: {} } as TaskRow)
    expect(host.textContent).not.toContain('只看当前节点记录')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: false, onlyMine: false }))
    await clickScope('NODE')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: false, onlyMine: true, pageNo: 1 }))
    await clickScope('ALL')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: true, onlyMine: false }))
    await clickScope('GROUP')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: false, onlyMine: false }))
  })
  it('本组授权不展示全部业务数据，刷新保留本节点，切换办理项回到本组', async () => {
    api.entryList.mockResolvedValueOnce([scopeEntry('GROUP'), scopeEntry('GROUP', 'other')])
    await mount(false)
    expect(host.querySelector('[data-radio="ALL"]')).toBeNull()
    await clickScope('NODE')
    api.entryList.mockResolvedValue([scopeEntry('GROUP'), scopeEntry('GROUP', 'other')])
    button('刷新数据').click()
    await flush()
    expect(host.querySelector('[data-scope]')?.getAttribute('data-scope')).toBe('NODE')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: false, onlyMine: true }))
    await workspace.select('other')
    await flush()
    expect(api.entryPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ entryKey: 'other', all: false, onlyMine: false })
    )
  })
  it('权限收窄后刷新不继续请求全部业务数据', async () => {
    api.entryList.mockResolvedValueOnce([scopeEntry('ALL')])
    await mount(false)
    await clickScope('ALL')
    api.entryList.mockResolvedValue([scopeEntry('GROUP')])
    button('刷新数据').click()
    await flush()
    expect(host.querySelector('[data-radio="ALL"]')).toBeNull()
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ all: false, onlyMine: false }))
  })
  it('交付快照只读预览不切换当前办理项，返回仍保留本节点筛选', async () => {
    api.entryList.mockResolvedValueOnce([scopeEntry('GROUP')])
    api.entryMaterials.mockResolvedValue([
      {
        entryKey: 'other',
        name: '交付材料',
        records: [{ id: 'fact', record: { id: 'old-record', revision: 2, values: {} }, sources: [] }],
        submissions: [{ contributionId: 'fact', record: { record: { id: 'old-record', revision: 2 }, details: {} } }]
      }
    ])
    await mount(false)
    await clickScope('NODE')
    button('查看交付材料').click()
    await flush()
    button('查看完整表单与明细').click()
    await flush()
    expect(host.querySelector('[data-snapshot="2"]')?.getAttribute('data-readonly')).toBe('true')
    expect(host.textContent).toContain('只读 · 非当前数据')
    button('返回交付材料').click()
    await flush()
    expect(host.querySelector('[data-snapshot]')).toBeNull()
    expect(host.querySelector('[data-scope]')?.getAttribute('data-scope')).toBe('NODE')
    expect(api.entryPage).toHaveBeenLastCalledWith(expect.objectContaining({ entryKey: 'review', onlyMine: true }))
  })
  it('失效授权保留办理项并说明原因，点击和程序定位均不请求表单', async () => {
    api.entryList.mockResolvedValueOnce([
      {
        config: { key: 'review', name: '复核材料' },
        canWrite: false,
        unavailableReason: '业务授权已失效，请联系任务创建人检查视图、表单及共享权限'
      }
    ])
    await mount(false, false, undefined, undefined, false)
    const card = host.querySelector<HTMLButtonElement>('.task-business-card')!
    expect(card.disabled).toBe(true)
    expect(card.textContent).toContain('复核材料')
    expect(card.textContent).toContain('请联系任务创建人')
    card.click()
    await workspace.select('review')
    await flush()
    expect(api.entryForm).not.toHaveBeenCalled()
    expect(api.entryPage).not.toHaveBeenCalled()
  })
  const clickCard = async () => {
    host.querySelector<HTMLButtonElement>('.task-business-card')!.click()
    await flush()
  }
  const editableForm = () =>
    api.entryForm.mockResolvedValue({
      model: {
        object: { objectName: '复核资料', fields: [] },
        permissions: { actions: ['READ', 'CREATE', 'UPDATE'], readFields: [] }
      }
    })
  it('所属流程挂起时即使业务接口返回可写也仅允许查看', async () => {
    editableForm()
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    await mount(true, true, undefined, undefined, false, '所属流程已挂起')
    expect(host.textContent).not.toContain('进入办理')
    await clickCard()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(host.textContent).toContain('所属流程已挂起')
    expect(button('新增业务数据').disabled).toBe(true)
  })
  it('进入概况仅加载卡片目录，点击空数据可写卡片才加载并直接新增', async () => {
    editableForm()
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    await mount(true, true, undefined, undefined, false)
    expect(api.entryForm).not.toHaveBeenCalled()
    expect(api.entryPage).not.toHaveBeenCalled()
    expect(host.querySelector('[data-modal]')).toBeNull()
    expect(host.textContent).not.toContain('过程反馈')
    await clickCard()
    expect(api.entryForm).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-record-editor="new"]')).not.toBeNull()
    expect(host.querySelectorAll('[data-modal]')).toHaveLength(1)
    expect(Number(host.querySelector('[data-modal]')?.getAttribute('width'))).toBe(Math.round(window.innerWidth * 0.92))
    expect(host.querySelector('[data-readonly="false"]')).not.toBeNull()
  })
  it('打开卡片等待权限与数据时显示加载提示，不闪出空列表', async () => {
    let finish = () => {}
    api.entryForm.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = () =>
            resolve({
              model: {
                object: { objectName: '复核资料', fields: [] },
                permissions: { actions: ['READ', 'CREATE', 'UPDATE'], readFields: [] }
              }
            })
        })
    )
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    await mount(true, true, undefined, undefined, false)
    await clickCard()
    expect(host.querySelector('[role="status"]')?.textContent).toContain('正在打开业务数据')
    expect(host.textContent).not.toContain('新增业务数据')
    finish()
    await flush()
    expect(host.querySelector('[role="status"]')).toBeNull()
    expect(host.querySelector('[data-record-editor="new"]')).not.toBeNull()
  })
  it('单条记录直接进入表单，多条记录进入列表而不替用户选择记录', async () => {
    editableForm()
    await mount(true, true, undefined, undefined, false)
    await clickCard()
    expect(host.querySelector('[data-record-editor="source-contribution"]')).not.toBeNull()
    button('返回数据列表').click()
    await flush()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(host.querySelectorAll('[data-modal]')).toHaveLength(1)
    button('关闭窗口').click()
    await flush()
    api.entryPage.mockResolvedValue({
      list: [
        { id: 'one', sources: [], status: 'EFFECTIVE', record: { id: 'one', permissions: { actions: ['READ'] } } },
        { id: 'two', sources: [], status: 'EFFECTIVE', record: { id: 'two', permissions: { actions: ['READ'] } } }
      ],
      total: 2
    })
    await clickCard()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(Array.from(host.querySelectorAll('a')).filter(item => item.textContent === '查看')).toHaveLength(2)
  })
  it('只读卡片可以查看单条记录，但不出现可写编辑器', async () => {
    await mount(false, false, undefined, undefined, false)
    await clickCard()
    expect(host.querySelector('[data-record-editor="source-contribution"][data-readonly="true"]')).not.toBeNull()
  })
  it('没有数据的只读卡片展示列表和禁用原因，不直接进入新增', async () => {
    editableForm()
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    await mount(
      false,
      false,
      undefined,
      { id: 'task', revision: 1, status: 'PENDING', canExecute: true } as TaskRow,
      false
    )
    await clickCard()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(host.textContent).toContain('开始当前任务后可填写')
    expect(button('新增业务数据').disabled).toBe(true)
  })
  it('任务可写但单条记录没有UPDATE权限，直接打开时仍只读', async () => {
    await mount(true, true, undefined, undefined, false)
    await clickCard()
    expect(host.querySelector('[data-record-editor="source-contribution"][data-readonly="true"]')).not.toBeNull()
  })
  it('办理中祖先暂停立即把已打开的表单变为只读', async () => {
    editableForm()
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    const task = reactive({ id: 'task', revision: 1, status: 'RUNNING', canExecute: true } as TaskRow)
    await mount(true, true, undefined, task, false)
    await clickCard()
    expect(host.querySelector('[data-readonly="false"]')).not.toBeNull()
    task.pausedByTaskId = 'root'
    task.pauseReason = '上级任务已暂停'
    await flush()
    expect(host.querySelector('[data-readonly="true"]')).not.toBeNull()
  })
  it('关闭大窗口与返回列表都保护未保存数据，确认后仅关闭当前编辑器', async () => {
    editableForm()
    api.entryPage.mockResolvedValue({ list: [], total: 0 })
    await mount(true, true, undefined, undefined, false)
    await clickCard()
    editorClose.mockResolvedValue(false)
    button('关闭窗口').click()
    await flush()
    expect(host.querySelector('[data-record-editor="new"]')).not.toBeNull()
    button('返回数据列表').click()
    await flush()
    expect(host.querySelector('[data-record-editor="new"]')).not.toBeNull()
    editorClose.mockResolvedValue(true)
    button('返回数据列表').click()
    await flush()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(host.querySelectorAll('[data-modal]')).toHaveLength(1)
    button('关闭窗口').click()
    await flush()
    expect(host.querySelector('[data-modal]')).toBeNull()
  })
  it('卡片只展示服务端计量结果，0分钟不变成未设置，也不推算整体用时', async () => {
    api.entryList.mockResolvedValueOnce([
      {
        config: { key: 'review', name: '复核材料', workRule: { mode: 'RECORD_ONCE', minutes: 15 } },
        workSummary: { myMinutes: 0, myRecordCount: 0, totalMinutes: 45, totalRecordCount: 3 },
        binding: { resource: { resourceId: 'object' } },
        canWrite: true
      }
    ])
    await mount(true, true, undefined, undefined, false)
    expect(host.textContent).toContain('我的已计工时 0 分钟')
    expect(host.textContent).toContain('0 条计量记录')
    expect(host.textContent).toContain('当前节点合计 45 分钟')
    expect(host.textContent).not.toContain('未设置')
    expect(api.entryPage).not.toHaveBeenCalled()
  })
  it('缺少管理者汇总时不自行合计，也不通过贡献记录数伪造工时', async () => {
    api.entryList.mockResolvedValueOnce([
      {
        config: { key: 'review', name: '复核材料', workRule: { mode: 'RECORD_ONCE', minutes: 15 } },
        contributionCount: 99,
        workSummary: { myMinutes: 15, myRecordCount: 1 },
        binding: { resource: { resourceId: 'object' } },
        canWrite: true
      }
    ])
    await mount(true, true, undefined, undefined, false)
    expect(host.textContent).toContain('我的已计工时 15 分钟')
    expect(host.textContent).toContain('1 条计量记录')
    expect(host.textContent).not.toContain('当前节点合计')
    expect(host.textContent).not.toContain('99')
  })
})

describe('业务入口只读材料关联', () => {
  it('祖先暂停即刻冻结旧业务入口权限，即使子节点修订未变仍刷新权限', async () => {
    api.entryForm.mockResolvedValue({
      model: {
        object: { objectName: '复核资料', fields: [] },
        permissions: { actions: ['READ', 'CREATE', 'UPDATE'], readFields: [] }
      }
    })
    api.entryPage.mockResolvedValue({
      list: [
        {
          id: 'record-item',
          status: 'EFFECTIVE',
          sources: [],
          record: { id: 'record', permissions: { actions: ['READ', 'UPDATE'] } }
        }
      ],
      total: 1
    })
    const task = reactive({ id: 'task', revision: 1, status: 'RUNNING', canExecute: true } as TaskRow)
    await mount(true, true, undefined, task)
    expect(Array.from(host.querySelectorAll('a')).some(element => element.textContent?.trim() === '编辑')).toBe(true)
    const previousRequests = api.entryList.mock.calls.length
    task.pausedByTaskId = 'root'
    task.pauseReason = '上级任务已暂停'
    task.canExecute = false
    await flush()
    expect(api.entryList.mock.calls.length).toBeGreaterThan(previousRequests)
    expect(host.textContent).toContain('上级任务已暂停')
    expect(Array.from(host.querySelectorAll('a')).some(element => element.textContent?.trim() === '编辑')).toBe(false)
    const add = Array.from(host.querySelectorAll('button')).find(
      element => element.textContent?.trim() === '新增业务数据'
    )
    expect(add?.disabled).toBe(true)
  })
  it('canLink 允许只读材料显式关联，但不开放新增或改写', async () => {
    const updated = await mount(false, true)
    expect(
      Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === '新增业务数据')?.disabled
    ).toBe(true)
    expect(Array.from(host.querySelectorAll('a')).some(el => el.textContent === '编辑')).toBe(false)
    await clickLink()
    await flush()
    expect(confirm).toHaveBeenCalledWith('将此记录关联为本任务数据？', expect.stringContaining('关联不'), '关联记录')
    expect(api.entryLink).toHaveBeenCalledWith('task', 'review', 'record', expect.any(String))
    expect(api.entryPage).toHaveBeenCalledTimes(2)
    expect(updated).toHaveBeenCalledOnce()
  })
  it('明确禁止关联时不因可写权限开放入口', async () => {
    await mount(true, false)
    await openLinkMenu()
    expect(linkButton()).toBeUndefined()
    expect(api.entryLink).not.toHaveBeenCalled()
  })
  it('旧服务缺少 canLink 时沿用 canWrite', async () => {
    await mount(true)
    await openLinkMenu()
    expect(linkButton()).toBeDefined()
    await clickLink()
    await flush()
    expect(api.entryLink).toHaveBeenCalledOnce()
  })
  it('取消确认不产生本节点贡献', async () => {
    confirm.mockResolvedValue(false)
    const updated = await mount(false, true)
    await clickLink()
    await flush()
    expect(api.entryLink).not.toHaveBeenCalled()
    expect(updated).not.toHaveBeenCalled()
  })
  it('准备完成先保护未保存数据，确认弃改后销毁已批准离开的编辑器', async () => {
    await mount(true, true, 'own-contribution')
    expect(host.querySelector('[data-record-editor="own-contribution"]')).not.toBeNull()
    editorClose.mockResolvedValue(false)
    expect(await workspace.prepareAction()).toBe(false)
    await flush()
    expect(host.querySelector('[data-record-editor="own-contribution"]')).not.toBeNull()
    editorClose.mockResolvedValue(true)
    expect(await workspace.prepareAction()).toBe(true)
    await flush()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    await workspace.select('review')
    await flush()
    Array.from(host.querySelectorAll('a'))
      .find(el => el.textContent === '查看')
      ?.click()
    await flush()
    expect(host.querySelector('[data-record-editor="source-contribution"]')).not.toBeNull()
    editorClose.mockResolvedValue(false)
    expect(await workspace.prepareAction()).toBe(false)
    expect(editorClose).toHaveBeenCalledTimes(3)
  })
  it('关联已提交但响应丢失，查询原回执后只产生一次贡献', async () => {
    api.entryLink.mockRejectedValueOnce(new Error('响应丢失'))
    api.entryReceipt.mockResolvedValueOnce({ contributionId: 'saved' })
    const updated = await mount(false, true)
    await clickLink()
    await flush()
    const requestKey = api.entryLink.mock.calls[0]![3]
    expect(api.entryReceipt).toHaveBeenCalledWith(
      { taskId: 'task', entryKey: 'review', recordId: 'record', contributionId: null },
      requestKey
    )
    expect(api.entryLink).toHaveBeenCalledOnce()
    expect(updated).toHaveBeenCalledOnce()
    expect(host.textContent).not.toContain('关联结果尚未确认')
    expect(sessionStorage.getItem(taskLinkKey(actor.id, 'task'))).toBeNull()
  })
  it('结果未知阻止新的关联，重试冻结原任务、入口、记录和请求键', async () => {
    api.entryLink.mockRejectedValueOnce(new Error('连接中断'))
    const updated = await mount(false, true)
    await clickLink()
    await flush()
    expect(host.textContent).toContain('关联结果尚未确认')
    await clickLink()
    await flush()
    expect(api.entryLink).toHaveBeenCalledOnce()
    const original = [...api.entryLink.mock.calls[0]!]
    Array.from(host.querySelectorAll('button'))
      .find(el => el.textContent === '重试原关联')
      ?.click()
    await flush()
    expect(api.entryLink.mock.calls[1]).toEqual(original)
    expect(updated).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem(taskLinkKey(actor.id, 'task'))).toBeNull()
  })
  it('重开工作区仍使用会话中的原请求，其他账号不读取该请求', async () => {
    api.entryLink.mockRejectedValueOnce(new Error('连接中断'))
    await mount(false, true)
    await clickLink()
    await flush()
    const original = [...api.entryLink.mock.calls[0]!]
    app.unmount()
    host.remove()
    actor.id = 'other-user'
    await mount(false, true)
    expect(host.textContent).not.toContain('关联结果尚未确认')
    app.unmount()
    host.remove()
    actor.id = 'workspace-user'
    await mount(false, true)
    expect(host.textContent).toContain('关联结果尚未确认')
    Array.from(host.querySelectorAll('button'))
      .find(el => el.textContent === '重试原关联')
      ?.click()
    await flush()
    expect(api.entryLink.mock.calls[1]).toEqual(original)
  })
  it('首次明确拒绝允许纠正后重试，未知结果后的拒绝保留原请求', async () => {
    api.entryLink.mockRejectedValueOnce({ businessCode: 400, message: '已失去权限' })
    await mount(false, true)
    await clickLink()
    await flush()
    expect(host.textContent).not.toContain('关联结果尚未确认')
    api.entryLink.mockRejectedValueOnce(new Error('连接中断'))
    await clickLink()
    await flush()
    api.entryLink.mockRejectedValueOnce({ businessCode: 400, message: '任务已结束' })
    Array.from(host.querySelectorAll('button'))
      .find(el => el.textContent === '重试原关联')
      ?.click()
    await flush()
    expect(host.textContent).toContain('关联结果尚未确认')
    expect(api.entryLink.mock.calls[2]).toEqual(api.entryLink.mock.calls[1])
    expect(api.entryLink.mock.calls[1]![3]).not.toBe(api.entryLink.mock.calls[0]![3])
  })
  it.each(['切换账号', '卸载'])('重试查回执期间%s，迟到的空回执不能再发送原关联命令', async change => {
    const identity = taskLinkKey(actor.id, 'task')
    const original = { taskId: 'task', entryKey: 'review', recordId: 'record', requestKey: 'frozen-request' }
    storeTaskLink(identity, original)
    const receipt = deferredResult<null>()
    api.entryReceipt.mockImplementationOnce(() => receipt.promise)
    const updated = await mount(false, true)
    const retry = Array.from(host.querySelectorAll('button')).find(el => el.textContent === '重试原关联')
    if (!retry) throw new Error('未展示原请求恢复入口')
    retry.click()
    await flush()
    expect(api.entryReceipt).toHaveBeenCalledOnce()
    expect(api.entryReceipt).toHaveBeenCalledWith(expect.objectContaining(original), original.requestKey)
    expect(await workspace.requestClose()).toBe(false)
    if (change === '切换账号') actor.id = 'other-user'
    else app.unmount()
    await flush()
    receipt.resolve(null)
    await flush()
    expect(api.entryLink).not.toHaveBeenCalled()
    expect(updated).not.toHaveBeenCalled()
    expect(JSON.parse(sessionStorage.getItem(identity) || 'null')).toEqual(original)
    expect(sessionStorage.getItem(taskLinkKey('other-user', 'task'))).toBeNull()
    if (change === '切换账号') expect(host.textContent).not.toContain('关联结果尚未确认')
  })
  it('请求进行中重复点击不重复提交，关闭需等请求结束', async () => {
    let finish!: (result: object) => void
    api.entryLink.mockImplementationOnce(() => new Promise(resolve => (finish = resolve)))
    await mount(false, true)
    await clickLink()
    await clickLink()
    await flush()
    expect(api.entryLink).toHaveBeenCalledOnce()
    expect(await workspace.requestClose()).toBe(false)
    finish({ contributionId: 'saved' })
    await flush()
    expect(await workspace.requestClose()).toBe(true)
  })
  it('从共享节点重提申请时定位原节点和入口，不在当前节点打开可写表单', async () => {
    api.entryPage.mockResolvedValue({
      list: [{ id: 'rejected', requestId: 'request', status: 'REJECTED', sources: [], record: null }],
      total: 1
    })
    const location = { taskId: 'original-task', entryKey: 'original-entry', contributionId: 'rejected' }
    api.entryHandlingLocation.mockResolvedValue(location)
    await mount(true, true)
    Array.from(host.querySelectorAll('a'))
      .find(el => el.textContent?.trim() === '编辑重提')
      ?.click()
    await flush()
    expect(api.entryHandlingLocation).toHaveBeenCalledWith('request')
    expect(resumed).toHaveBeenCalledWith(location)
    expect(host.querySelector('[data-record-editor]')).toBeNull()
  })
  it('同一入口恢复原申请；办理定位失权时不能降级为当前节点重提', async () => {
    api.entryPage.mockResolvedValue({
      list: [{ id: 'rejected', requestId: 'request', status: 'REJECTED', sources: [], record: null }],
      total: 1
    })
    api.entryHandlingLocation.mockRejectedValueOnce(new Error('原任务已失权'))
    await mount(true, true)
    Array.from(host.querySelectorAll('a'))
      .find(el => el.textContent?.trim() === '编辑重提')
      ?.click()
    await flush()
    expect(resumed).not.toHaveBeenCalled()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(host.textContent).toContain('原任务已失权')
    api.entryHandlingLocation.mockResolvedValue({ taskId: 'task', entryKey: 'review', contributionId: 'rejected' })
    Array.from(host.querySelectorAll('a'))
      .find(el => el.textContent?.trim() === '编辑重提')
      ?.click()
    await flush()
    expect(host.querySelector('[data-record-editor="rejected"]')).not.toBeNull()
  })
  it('切换办理项后丢弃迟到的原申请定位，不打开旧节点编辑器', async () => {
    let resolveLocation!: (value: { taskId: string; entryKey: string; contributionId: string }) => void
    api.entryHandlingLocation.mockImplementationOnce(() => new Promise(resolve => (resolveLocation = resolve)))
    api.entryList.mockResolvedValueOnce(
      ['review', 'other'].map(key => ({
        config: { key, name: key },
        binding: { resource: { resourceId: 'object' } },
        canWrite: true
      }))
    )
    api.entryPage.mockResolvedValue({
      list: [{ id: 'rejected', requestId: 'request', status: 'REJECTED', sources: [], record: null }],
      total: 1
    })
    await mount(true)
    const edit = Array.from(host.querySelectorAll('a')).find(el => el.textContent?.trim() === '编辑重提')
    if (!edit) throw new Error('未展示重提操作')
    edit.click()
    await flush()
    await workspace.select('other')
    resolveLocation({ taskId: 'original', entryKey: 'review', contributionId: 'rejected' })
    await flush()
    expect(resumed).not.toHaveBeenCalled()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(host.querySelector('[data-modal="other"]')).not.toBeNull()
  })
  it('同入口先定位退回申请A，再编辑记录B，A迟到不能覆盖B或绕过弃改保护', async () => {
    const location = deferredResult<{ taskId: string; entryKey: string; contributionId: string }>()
    api.entryHandlingLocation.mockImplementationOnce(() => location.promise)
    api.entryPage.mockResolvedValue({
      list: [
        { id: 'a', requestId: 'request-a', status: 'REJECTED', sources: [], record: null },
        {
          id: 'b',
          status: 'EFFECTIVE',
          sources: [],
          record: { id: 'record-b', permissions: { actions: ['READ', 'UPDATE'] } }
        }
      ],
      total: 2
    })
    await mount(true, true)
    const openRow = (id: string, label: string) => {
      const action = Array.from(host.querySelectorAll<HTMLAnchorElement>(`[data-row="${id}"] a`)).find(
        el => el.textContent?.trim() === label
      )
      if (!action) throw new Error(`未展示 ${id} 的 ${label} 操作`)
      action.click()
    }
    openRow('a', '编辑重提')
    await flush()
    expect(api.entryHandlingLocation).toHaveBeenCalledExactlyOnceWith('request-a')
    openRow('b', '编辑')
    await flush()
    const editorB = host.querySelector('[data-record-editor="b"]')
    expect(editorB).not.toBeNull()
    expect(editorB?.getAttribute('data-readonly')).toBe('false')
    editorClose.mockResolvedValue(false)
    location.resolve({ taskId: 'task', entryKey: 'review', contributionId: 'a' })
    await flush()
    expect(host.querySelector('[data-record-editor="b"]')).toBe(editorB)
    expect(host.querySelector('[data-record-editor="a"]')).toBeNull()
    expect(resumed).not.toHaveBeenCalled()
    expect(await workspace.prepareAction()).toBe(false)
    expect(host.querySelector('[data-record-editor="b"]')).toBe(editorB)
  })
})

describe('业务入口切换隔离异步响应', () => {
  const entry = (key: string) => ({
    config: { key, name: key, dataMode: 'INDEPENDENT', allowAll: true },
    binding: { resource: { resourceId: 'same-object' } },
    canWrite: true,
    canLink: true
  })
  const form = {
    model: { object: { objectName: '反馈对象', fields: [] }, permissions: { actions: ['READ'], readFields: [] } }
  }
  const page = (key: string) => ({
    list: [
      {
        id: `${key}-contribution`,
        status: 'EFFECTIVE',
        sources: [],
        record: { id: `${key}-record`, permissions: { actions: ['READ', 'UPDATE'] } }
      }
    ],
    total: 1
  })
  function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (reason: Error) => void
    const promise = new Promise<T>((yes, no) => {
      resolve = yes
      reject = no
    })
    return { promise, resolve, reject }
  }

  it('用户切到 B 后，初始 A 表单晚返回不能打开 A 深链编辑器', async () => {
    const firstForm = deferred<typeof form>()
    api.entryList.mockResolvedValueOnce([entry('review'), entry('B')])
    api.entryForm.mockImplementation(({ entryKey }: { entryKey: string }) =>
      entryKey === 'review' ? firstForm.promise : Promise.resolve(form)
    )
    api.entryPage.mockResolvedValue(page('B'))
    await mount(true, true, 'initial-A')

    await workspace.select('B')
    await flush()
    firstForm.resolve(form)
    await flush()

    expect(host.querySelector('[data-modal="B"]')).not.toBeNull()
    expect(host.querySelector('[data-record-editor]')).toBeNull()
    expect(api.entryPage.mock.calls.every(([query]) => query.entryKey === 'B')).toBe(true)
    Array.from(host.querySelectorAll('a'))
      .find(el => el.textContent === '查看')
      ?.click()
    await flush()
    expect(host.querySelector('[data-record-editor="B-contribution"]')).not.toBeNull()
  })

  it('切到 B 且其表单仍加载时，A 分页晚返回不能展示或误关联旧行', async () => {
    const firstPage = deferred<ReturnType<typeof page>>()
    const secondForm = deferred<typeof form>()
    api.entryList.mockResolvedValueOnce([entry('review'), entry('B')])
    api.entryForm.mockImplementation(({ entryKey }: { entryKey: string }) =>
      entryKey === 'B' ? secondForm.promise : Promise.resolve(form)
    )
    api.entryPage.mockImplementation(({ entryKey }: { entryKey: string }) =>
      entryKey === 'review' ? firstPage.promise : Promise.resolve(page('B'))
    )
    await mount(true, true)
    expect(api.entryPage.mock.calls[0]?.[0]?.entryKey).toBe('review')

    const changing = workspace.select('B')
    await flush()
    firstPage.resolve(page('A'))
    await flush()
    expect(linkButton()).toBeUndefined()
    expect(host.querySelectorAll('a')).toHaveLength(0)
    expect(api.entryLink).not.toHaveBeenCalled()

    secondForm.resolve(form)
    await changing
    await flush()
    host.querySelector<HTMLButtonElement>('[aria-label="更多数据操作"]')?.click()
    await flush()
    expect(linkButton()).toBeDefined()
    await clickLink()
    await flush()
    expect(api.entryLink).toHaveBeenCalledWith('task', 'B', 'B-record', expect.any(String))
  })

  it('旧入口分页失败不能覆盖当前入口的权限错误', async () => {
    const firstPage = deferred<ReturnType<typeof page>>()
    api.entryList.mockResolvedValueOnce([entry('review'), entry('B')])
    api.entryForm.mockImplementation(({ entryKey }: { entryKey: string }) =>
      entryKey === 'B' ? Promise.reject(new Error('B 入口已失去权限')) : Promise.resolve(form)
    )
    api.entryPage.mockImplementationOnce(() => firstPage.promise)
    await mount(true, true)
    await workspace.select('B')
    await flush()

    firstPage.reject(new Error('A 入口旧请求失败'))
    await flush()
    expect(host.textContent).toContain('B 入口已失去权限')
    expect(host.textContent).not.toContain('A 入口旧请求失败')
    expect(host.querySelectorAll('a')).toHaveLength(0)
  })
})
