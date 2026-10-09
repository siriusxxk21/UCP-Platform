// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, ref, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Antd from 'ant-design-vue'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { NodeKind } from '@/types/nocode/application-ui'
import type { DataViewModel } from '@/types/nocode/data-view'
import type { ReportResult } from '@/types/nocode/report'
import { defaultReport } from './report'
import { KeptPages } from './kept-pages'
import { invalidateRuntimeData } from './runtime-data'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  report: vi.fn(),
  reportDetails: vi.fn(),
  viewChildren: vi.fn(),
  viewModel: vi.fn(),
  selection: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'quiet-refresh-test' } }) }))
const log: string[] = []
vi.mock('@/components/os-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h, ref, watch } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource', 'loading', 'selectedRowKeys'],
      emits: ['selection-change'],
      setup(props, { emit }) {
        // 表格看到过几次「数据换了」、有没有出过 loading
        const changes = ref(0),
          spun = ref(false)
        watch(
          () => props.dataSource,
          () => changes.value++
        )
        watch(
          () => props.loading,
          value => {
            if (value) spun.value = true
          },
          { flush: 'sync' }
        )
        return () =>
          h(
            'div',
            {
              'data-table': true,
              'data-rows': props.dataSource.map((row: { id: string }) => row.id).join(','),
              'data-selected': (props.selectedRowKeys || []).join(','),
              'data-changes': changes.value,
              'data-spun': String(spun.value)
            },
            [
              h('button', { 'data-select': true, onClick: () => emit('selection-change', ['A', 'B']) }, '全选'),
              h('button', { 'data-calm': true, onClick: () => (spun.value = false) }, '清 loading 记录')
            ]
          )
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordSurface.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', slots.default?.()) : null
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: defineComponent({
    props: { record: Object, readOnly: Boolean },
    setup(props) {
      log.push('editor')
      onUnmounted(() => log.push('editor-gone'))
      return () =>
        h('form', { 'data-editor': props.record?.record.values.name, 'data-read-only': String(!!props.readOnly) })
    }
  })
}))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportFilterInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'
import BusinessBlock from '@/views/nocode/application/components/BusinessBlock.vue'
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'
import RecordExtras from '@/views/nocode/application/components/RecordExtras.vue'
import DataViewChildren from '@/views/nocode/application/components/DataViewChildren.vue'
import ReferenceField from '@/views/nocode/application/components/ReferenceField.vue'
import SelectionField from '@/views/nocode/application/components/SelectionField.vue'

const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string, name = id) => ({ id, revision: '1', values: { name }, permissions })
const record = (id: string, name = id, processes: unknown[] = []) => ({ record: row(id, name), details: {}, processes })
const objectModel = (objectId = 'object') => ({
  writable: true,
  permissions,
  object: {
    objectId,
    objectName: '对象',
    titleFieldId: 'name',
    fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
    fieldOptions: {},
    details: [],
    relations: [],
    settings: {}
  },
  details: {}
})
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

// jsdom 没有 matchMedia；ant 的列表、栅格在挂载后读断点。
window.matchMedia ||= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => undefined,
  removeListener: () => undefined,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  dispatchEvent: () => false
})) as typeof window.matchMedia

// jsdom 不支持带伪元素参数的 getComputedStyle；ant 的表格量滚动条宽度时会带上。
const computedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle

let app: App | undefined, host: HTMLDivElement
async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props))
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] }))
  app.use(Antd)
  app.mount(host)
  await flush()
}
/** 数据变了：与推送到达、页面回到前台走的是同一个入口。 */
async function changed(objectId?: string, recordIds?: string[]) {
  invalidateRuntimeData({ applicationId: 'app', objectId, recordIds })
  await flush()
}
const spinning = () => !!host.querySelector('.ant-spin-spinning')

beforeEach(() => {
  log.length = 0
  api.model.mockImplementation(async (_applicationId: string, objectId: string) => objectModel(objectId))
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('quiet reload of the record list', () => {
  const table = () => host.querySelector<HTMLElement>('[data-table]')!
  async function mountList() {
    // 真实接口每次返回的都是新对象
    api.page.mockImplementation(async () => ({ list: [row('A'), row('B')], total: 2 }))
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    table().querySelector<HTMLElement>('[data-select]')!.click()
    table().querySelector<HTMLElement>('[data-calm]')!.click()
    await flush()
    expect(table().dataset.selected).toBe('A,B')
  }

  it('shows no loading, keeps the selection, and swaps in the rows only when they changed', async () => {
    await mountList()
    const before = table().dataset.changes
    // 没变：界面一点不动
    await changed('object')
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(table().dataset.changes).toBe(before)

    const answer = deferred<{ list: unknown[]; total: number }>()
    api.page.mockReturnValue(answer.promise)
    await changed('object')
    // 请求还挂着：不遮挡、不清空、选中还在
    expect(table().dataset.spun).toBe('false')
    expect(table().dataset.rows).toBe('A,B')
    expect(table().dataset.selected).toBe('A,B')

    answer.resolve({ list: [row('A', '改过'), row('C')], total: 2 })
    await flush()
    expect(table().dataset.rows).toBe('A,C')
    // B 已经不在这一页，从选中里去掉；A 还选着
    expect(table().dataset.selected).toBe('A')
    expect(table().dataset.spun).toBe('false')
  })

  it('keeps what is on screen when the quiet reload fails', async () => {
    await mountList()
    api.page.mockRejectedValue(new Error('服务器开小差'))
    await changed('object')
    expect(table().dataset.rows).toBe('A,B')
    expect(table().dataset.selected).toBe('A,B')
    expect(host.textContent).not.toContain('服务器开小差')
  })

  it('reloads quietly when its kept page returns to the foreground, and not while it is in the background', async () => {
    api.page.mockResolvedValue({ list: [row('A'), row('B')], total: 2 })
    const shown = ref('list')
    await mount(
      defineComponent({
        setup: () => () =>
          h(KeptPages, { pageKey: shown.value, max: 5 }, () =>
            shown.value === 'list'
              ? h(BusinessRecords, { key: 'list', applicationId: 'app', objectId: 'object' })
              : h('p', { key: 'other' }, '别的页面')
          )
      }),
      {}
    )
    table().querySelector<HTMLElement>('[data-select]')!.click()
    table().querySelector<HTMLElement>('[data-calm]')!.click()
    await flush()
    const kept = table()

    shown.value = 'other'
    await flush()
    // 在后台：数据变了只记过期，不发请求
    await changed('object')
    expect(api.page).toHaveBeenCalledTimes(1)

    api.page.mockResolvedValue({ list: [row('A'), row('B'), row('C')], total: 3 })
    shown.value = 'list'
    await flush()
    expect(table()).toBe(kept)
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(kept.dataset.rows).toBe('A,B,C')
    expect(kept.dataset.selected).toBe('A,B')
    expect(kept.dataset.spun).toBe('false')
    // 对象结构没有重取
    expect(api.model).toHaveBeenCalledTimes(1)
  })

  it('ignores changes to other objects and other applications', async () => {
    await mountList()
    await changed('another-object')
    invalidateRuntimeData({ applicationId: 'another-app', objectId: 'object' })
    await flush()
    expect(api.page).toHaveBeenCalledTimes(1)
  })
})

describe('quiet reload of read-only blocks', () => {
  const view: ApplicationResource = {
    id: 'view',
    kind: ResourceKind.VIEW,
    code: 'view',
    name: '凭证',
    config: { objectId: 'object', fieldIds: ['name'] }
  }
  const form: ApplicationResource = {
    id: 'form',
    kind: ResourceKind.FORM,
    code: 'form',
    name: '凭证资料',
    config: { objectId: 'object', nodes: [], detailIds: [] }
  }

  it('updates the record count without a spinner', async () => {
    api.page.mockResolvedValue({ list: [], total: 5 })
    await mount(BusinessBlock, { applicationId: 'app', resources: [view], resourceId: 'view', metric: true })
    expect(host.querySelector('.ant-statistic-content')!.textContent).toBe('5')

    const answer = deferred<{ list: unknown[]; total: number }>()
    api.page.mockReturnValue(answer.promise)
    await changed('object')
    expect(spinning()).toBe(false)
    answer.resolve({ list: [], total: 6 })
    await flush()
    expect(host.querySelector('.ant-statistic-content')!.textContent).toBe('6')
  })

  it('refreshes a read-only detail in place and leaves it alone while it is being edited', async () => {
    api.get.mockResolvedValue(record('r1', '原值'))
    await mount(BusinessBlock, {
      applicationId: 'app',
      resources: [form],
      resourceId: 'form',
      recordId: 'r1',
      detail: true
    })
    expect(host.querySelector<HTMLElement>('[data-editor]')!.dataset.editor).toBe('原值')

    api.get.mockResolvedValue(record('r1', '别人改过'))
    await changed('object', ['r1'])
    expect(host.querySelector<HTMLElement>('[data-editor]')!.dataset.editor).toBe('别人改过')
    // 表单没有被拆掉重建
    expect(log).toEqual(['editor'])
    // 别的记录变了与它无关
    await changed('object', ['r2'])
    expect(api.get).toHaveBeenCalledTimes(2)

    Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent?.includes('编辑资料'))!
      .click()
    await flush()
    expect(host.querySelector<HTMLElement>('[data-editor]')!.dataset.readOnly).toBe('false')
    api.get.mockResolvedValue(record('r1', '又被别人改了'))
    await changed('object', ['r1'])
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(host.querySelector<HTMLElement>('[data-editor]')!.dataset.editor).toBe('别人改过')
  })

  it('does not overwrite a detail the user started editing while the quiet reload was on its way', async () => {
    api.get.mockResolvedValue(record('r1', '原值'))
    await mount(BusinessBlock, {
      applicationId: 'app',
      resources: [form],
      resourceId: 'form',
      recordId: 'r1',
      detail: true
    })
    const answer = deferred<unknown>()
    api.get.mockReturnValue(answer.promise)
    await changed('object', ['r1'])
    expect(api.get).toHaveBeenCalledTimes(2)
    // 请求还没回来，用户点了「编辑资料」
    Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent?.includes('编辑资料'))!
      .click()
    await flush()
    answer.resolve(record('r1', '别人改过'))
    await flush()
    expect(host.querySelector<HTMLElement>('[data-editor]')!.dataset.editor).toBe('原值')
    expect(host.querySelector<HTMLElement>('[data-editor]')!.dataset.readOnly).toBe('false')
  })

  it('refreshes the approval records of a record without a skeleton', async () => {
    api.get.mockResolvedValue(record('r1', 'r1', []))
    await mount(RecordExtras, {
      applicationId: 'app',
      form: form.config,
      recordId: 'r1',
      kind: NodeKind.PROCESSES
    })
    expect(host.textContent).toContain('当前记录尚无审批记录')

    const answer = deferred<unknown>()
    api.get.mockReturnValue(answer.promise)
    await changed('object', ['r1'])
    expect(host.querySelector('.ant-skeleton')).toBeNull()
    expect(host.textContent).toContain('当前记录尚无审批记录')
    answer.resolve(
      record('r1', 'r1', [
        { businessKey: 'k', name: '付款审批', instanceId: 'i', status: 'RUNNING', createTime: '2026-10-01 10:00:00' }
      ])
    )
    await flush()
    expect(host.textContent).toContain('付款审批')
  })
})

const result = (total: string): ReportResult => ({
  dimensionNames: [],
  metrics: [{ id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount' }],
  groups: [],
  totals: { in: total },
  totalGroups: 0,
  recordCount: 1,
  canExport: false,
  timeZone: 'Asia/Tokyo'
})
const report: ApplicationResource = {
  id: 'report',
  kind: ResourceKind.REPORT,
  code: 'report',
  name: '入金合计',
  config: {
    ...defaultReport('object'),
    display: 'METRIC',
    metrics: [{ id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount' }]
  } as unknown as ApplicationResource['config']
}

describe('quiet reload of statistics', () => {
  it('replaces the numbers without a spinner and only when they changed', async () => {
    api.report.mockResolvedValue(result('100'))
    await mount(ReportBlock, { applicationId: 'app', resource: report })
    expect(host.querySelector('.metric-cell strong')!.textContent).toBe('100')
    const cell = host.querySelector('.metric-cell')!

    const answer = deferred<ReportResult>()
    api.report.mockReturnValue(answer.promise)
    await changed('object')
    expect(spinning()).toBe(false)
    expect(host.querySelector('.metric-cell')).toBe(cell)
    answer.resolve(result('250'))
    await flush()
    expect(host.querySelector('.metric-cell strong')!.textContent).toBe('250')
    // 统计只重查数字，不重取对象结构
    expect(api.model).toHaveBeenCalledTimes(1)
  })
})

describe('quiet reload of the legacy drill-down table', () => {
  it('reloads the open read-only detail rows too and keeps the drawer open', async () => {
    api.report.mockImplementation(async () => result('100'))
    api.reportDetails.mockImplementation(async () => ({ list: [row('D1')], total: 1 }))
    await mount(ReportBlock, { applicationId: 'app', resource: report })
    host.querySelector<HTMLElement>('.metric-cell')!.click()
    await flush()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.ant-drawer-open')).not.toBeNull()
    expect(document.querySelectorAll('.ant-drawer .ant-table-tbody .ant-table-row')).toHaveLength(1)

    api.reportDetails.mockImplementation(async () => ({ list: [row('D1'), row('D2')], total: 2 }))
    await changed('object')
    expect(api.reportDetails).toHaveBeenCalledTimes(2)
    expect(document.querySelector('.ant-drawer-open')).not.toBeNull()
    expect(document.querySelectorAll('.ant-drawer .ant-table-tbody .ant-table-row')).toHaveLength(2)
  })
})

describe('quiet reload of an expanded child table', () => {
  const model: DataViewModel = {
    composition: {
      grain: 'ROOT',
      detailId: null,
      columns: [],
      sections: [
        {
          id: 'lines',
          name: '分录',
          detailId: null,
          objectId: 'line',
          viewId: null,
          binding: null,
          fieldIds: ['name'],
          conditions: null,
          pageSize: 10,
          showTable: true
        }
      ]
    },
    fields: [],
    fieldOptions: {},
    sections: { lines: { fields: [], fieldOptions: {}, recordModel: null } }
  }
  const table = () => host.querySelector<HTMLElement>('[data-table]')!

  it('reloads the current page of the child object only, without a loading state', async () => {
    api.viewChildren.mockResolvedValue({ list: [row('L1')], total: 1 })
    await mount(DataViewChildren, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent: row('P'),
      model,
      filters: []
    })
    table().querySelector<HTMLElement>('[data-calm]')!.click()
    await flush()
    // 主对象变了与子表无关
    await changed('object')
    expect(api.viewChildren).toHaveBeenCalledTimes(1)

    api.viewChildren.mockResolvedValue({ list: [row('L1'), row('L2')], total: 2 })
    await changed('line')
    expect(table().dataset.rows).toBe('L1,L2')
    expect(table().dataset.spun).toBe('false')
  })
})

describe('reference candidates', () => {
  it('are only marked outdated and reloaded the next time the list is opened', async () => {
    api.page.mockResolvedValue({ list: [row('T1')], total: 1 })
    await mount(ReferenceField, { applicationId: 'app', targetObjectId: 'target', modelValue: null })
    expect(api.page).toHaveBeenCalledTimes(1)

    await changed('target')
    await changed('target')
    // 表单里的选择器不当场重载
    expect(api.page).toHaveBeenCalledTimes(1)

    const select = host.querySelector<HTMLElement>('.ant-select-selector')!
    select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
  })

  it('of a selection field wait for the next time it is opened as well', async () => {
    api.selection.mockResolvedValue({
      options: [{ value: 'T1', label: '候选一' }],
      selected: [],
      total: 1,
      tree: false,
      defaultValue: null
    })
    await mount(SelectionField, { applicationId: 'app', objectId: 'object', fieldId: 'ref', modelValue: null })
    expect(api.selection).toHaveBeenCalledTimes(1)

    await changed('any-object')
    expect(api.selection).toHaveBeenCalledTimes(1)

    const select = host.querySelector<HTMLElement>('.ant-select-selector')!
    select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    expect(api.selection).toHaveBeenCalledTimes(2)
    // 没有新的变更，再展开不重取
    select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    expect(api.selection).toHaveBeenCalledTimes(2)
  })
})
