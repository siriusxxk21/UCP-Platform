// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TaskWorkHistory from '@/views/nocode/task-center/TaskWorkHistory.vue'
import { createTaskWorkEntriesApi } from '@/api/nocode/task-work-entries'
import type { TaskRow } from '@/types/nocode/task-center'
import type { TaskWorkHistoryDetail, TaskWorkHistoryRow } from '@/types/nocode/task-work-entries'

const api = vi.hoisted(() => ({ entryHistoryPage: vi.fn(), entryHistoryDetail: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.formItems?.())
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource'],
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          slots.search?.(),
          ...props.dataSource.map((record: TaskWorkHistoryRow) =>
            h('article', [record.taskTitle, record.operation, slots.bodyCell?.({ column: { key: 'actions' }, record })])
          )
        ])
  })
}))
let app: App, host: HTMLElement
const row = (id = 'change'): TaskWorkHistoryRow => ({
  id,
  taskId: 'child',
  taskTitle: '询价',
  entryKey: '__business',
  entryName: '采购单',
  category: 'BUSINESS',
  recordId: 'record',
  recordLabel: '电视采购',
  operation: 'UPDATED',
  actorId: 'buyer',
  actorName: '采购员',
  time: '2026-10-03T10:00:00',
  detailAvailable: true,
  historyKnown: true
})
const detail = (extra: Partial<TaskWorkHistoryDetail> = {}): TaskWorkHistoryDetail => ({
  row: row(),
  fields: [
    { id: 'price', name: '采购金额' },
    { id: 'name', name: '名称' }
  ],
  before: { price: 2000, name: '电视' },
  after: { price: 1800, name: '电视' },
  details: [],
  beforeKnown: true,
  afterKnown: true,
  relatedUpdate: false,
  ...extra
})
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(parentId: string | null = null, initialRecordId?: string, employeeView = false) {
  app = createApp(TaskWorkHistory, {
    task: { id: 'task', parentId, revision: 1 } as TaskRow,
    entries: [],
    initialRecordId,
    employeeView
  })
  const plain = defineComponent({
    props: ['message'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.message, slots.default?.(), slots.action?.()])
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ASpace',
    'ATypographyText',
    'AAlert',
    'ATag',
    'ASpin',
    'ACollapse',
    'ACollapsePanel'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots, attrs }) =>
        () =>
          h('button', attrs, slots.default?.())
    })
  )
  app.component(
    'ACheckbox',
    defineComponent({
      props: ['checked'],
      emits: ['update:checked', 'change'],
      setup:
        (p, { slots, emit }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: p.checked,
              onChange: (e: Event) => {
                emit('update:checked', (e.target as HTMLInputElement).checked)
                emit('change')
              }
            }),
            slots.default?.()
          ])
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: p.value,
              onChange: (e: Event) => {
                emit('update:value', (e.target as HTMLSelectElement).value)
                emit('change')
              }
            },
            p.options.map((o: { value: string; label: string }) => h('option', { value: o.value }, o.label))
          )
    })
  )
  app.component('AInputSearch', plain)
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value', 'change'],
      setup:
        (p, { slots, emit }) =>
        () =>
          h(
            'div',
            {
              'data-node-scope': String(p.value),
              onClick: (event: MouseEvent) => {
                const value = (event.target as HTMLElement).closest('button')?.dataset.radio
                if (value != null) {
                  emit('update:value', value === 'true')
                  emit('change')
                }
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
          h('button', { 'data-radio': String(p.value) }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
async function click(text: string) {
  const button = Array.from(host.querySelectorAll('a,button')).find(item => item.textContent?.trim() === text)
  expect(button, text).toBeTruthy()
  ;(button as HTMLElement).click()
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  api.entryHistoryPage.mockResolvedValue({ list: [row()], total: 1 })
  api.entryHistoryDetail.mockResolvedValue(detail())
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('任务办理记录', () => {
  it('员工入口默认本节点，可以切换含下级，不冒称整组办理历史', async () => {
    await mount(null, undefined, true)
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(expect.objectContaining({ onlyCurrentTask: true }))
    expect(host.textContent).toContain('本节点及下级')
    expect(host.textContent).not.toContain('整组')
    await click('本节点及下级')
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ onlyCurrentTask: false, pageNo: 1 })
    )
    await click('本节点')
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(expect.objectContaining({ onlyCurrentTask: true }))
  })
  it('总任务默认汇总，子任务默认只看当前任务；记录反查保留准确范围', async () => {
    await mount()
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ taskId: 'task', onlyCurrentTask: false, entryKey: null, recordId: null })
    )
    app.unmount()
    host.remove()
    await mount('root', 'record')
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ onlyCurrentTask: true, recordId: 'record' })
    )
    await click('查看全部记录')
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ onlyCurrentTask: true, recordId: null })
    )
  })
  it('动作过滤传给服务端，删除记录无需出现在当前数据列表', async () => {
    await mount()
    const select = host.querySelector('[aria-label="数据操作记录类型"]') as HTMLSelectElement
    select.value = 'DELETED'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(api.entryHistoryPage).toHaveBeenLastCalledWith(expect.objectContaining({ operation: 'DELETED', pageNo: 1 }))
  })
  it('显示单次真实前后值，并保留完整快照入口', async () => {
    await mount()
    await click('查看当时数据')
    expect(host.textContent).toContain('历史快照 · 只读')
    expect(host.textContent).not.toContain('展示本次办理时的数据，不代表当前最新内容。')
    expect(api.entryHistoryDetail).toHaveBeenCalledWith({
      taskId: 'task',
      entryKey: '__business',
      contributionId: 'change'
    })
    const diff = host.querySelector('[aria-label="本次字段变化"]')
    expect(diff?.textContent).toContain('2000')
    expect(diff?.textContent).toContain('1800')
    expect(diff?.textContent).not.toContain('名称')
    expect(host.querySelectorAll('dl')).toHaveLength(2)
  })
  it('关联只显示关联时快照，历史未知不伪造字段变化', async () => {
    api.entryHistoryDetail.mockResolvedValue(
      detail({ row: { ...row(), operation: 'LINKED' }, before: null, beforeKnown: false })
    )
    await mount()
    await click('查看当时数据')
    expect(host.textContent).toContain('没有修改业务数据')
    expect(host.querySelector('[aria-label="本次字段变化"]')).toBeNull()
    await click('返回数据操作记录')
    api.entryHistoryDetail.mockResolvedValue(
      detail({
        row: { ...row(), historyKnown: false },
        before: null,
        beforeKnown: false,
        details: [
          {
            id: 'lines',
            name: '采购明细',
            fields: [{ id: 'note', name: '说明' }],
            before: {},
            after: { line: { note: '仅有单边快照' } },
            beforeOrder: [],
            afterOrder: ['line'],
            beforeKnown: false,
            afterKnown: true
          }
        ]
      })
    )
    await click('查看当时数据')
    expect(host.textContent).toContain('无法准确还原字段变化')
    expect(host.textContent).not.toContain('记录尚未创建')
    expect(host.textContent).toContain('仅有单边快照')
    expect(host.querySelector('.history-detail-group')).toBeNull()
  })
  it('失败不显示上次详情，返回后的迟到响应不能覆盖新操作', async () => {
    await mount()
    await click('查看当时数据')
    await click('返回数据操作记录')
    let resolveOld!: (value: TaskWorkHistoryDetail) => void
    api.entryHistoryDetail.mockReturnValueOnce(
      new Promise(resolve => {
        resolveOld = resolve
      })
    )
    await click('查看当时数据')
    await click('返回数据操作记录')
    api.entryHistoryDetail.mockRejectedValueOnce(new Error('当前无权查看'))
    await click('查看当时数据')
    resolveOld(detail())
    await flush()
    expect(host.textContent).toContain('当前无权查看')
    expect(host.textContent).not.toContain('2000')
  })
  it('办理历史适配保持 taskId、entryKey 与贡献身份，不从浏览器推断历史', async () => {
    const post = vi.fn().mockResolvedValue({})
    const transport = createTaskWorkEntriesApi({ post, get: vi.fn(), put: vi.fn() })
    await transport.entryHistoryPage({
      taskId: 'task',
      onlyCurrentTask: true,
      operation: 'DELETED',
      recordId: 'record',
      pageNo: 2,
      pageSize: 10
    })
    await transport.entryHistoryDetail({ taskId: 'task', contributionId: 'contribution', entryKey: '__business' })
    expect(post.mock.calls).toEqual([
      [
        '/nocode/tasks/entries/history-page',
        { taskId: 'task', onlyCurrentTask: true, operation: 'DELETED', recordId: 'record', pageNo: 2, pageSize: 10 }
      ],
      [
        '/nocode/tasks/entries/history-detail',
        { taskId: 'task', contributionId: 'contribution', entryKey: '__business' }
      ]
    ])
  })
})
