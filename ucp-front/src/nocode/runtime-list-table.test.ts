// @vitest-environment jsdom
// 运行端列表的表格：展开列的列头是空的（不显示成 [""]）；没有排序列时翻页就是翻页；每页不超过 10 条时整页的行都露出来。
// 用的是真实的 OsTablePage 与 ant 的表格（不打桩）：列头里到底渲染了什么、分页事件带什么参数，只有真实表格才看得出来。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, toDisplayString, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Antd from 'ant-design-vue'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  viewChildren: vi.fn(),
  viewModel: vi.fn(),
  selection: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'runtime-list-table-test' } }) }))
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
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/DataViewChildren.vue', () => ({ default: { render: () => null } }))
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'

// jsdom 没有 matchMedia / ResizeObserver；ant 的表格、栅格在挂载后会用到。
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
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
)
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
async function mount(component: Component, props: Record<string, unknown>, slots?: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props, slots))
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] }))
  app.use(Antd)
  app.mount(host)
  await flush()
}
const heads = () => Array.from(host.querySelectorAll<HTMLElement>('.ant-table-thead > tr > th'))
const headTexts = () => heads().map(cell => (cell.textContent || '').trim())
const expandHead = () => host.querySelector<HTMLElement>('.ant-table-thead > tr > th.ant-table-row-expand-icon-cell')

const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE'],
  readFields: ['name', 'memo'],
  writeFields: ['name', 'memo'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string) => ({ id, revision: '1', values: { name: '名称' + id, memo: '摘要' + id }, permissions })
const objectModel = (objectId: string) => ({
  writable: true,
  permissions,
  object: {
    objectId,
    objectName: '资金流水',
    titleFieldId: 'name',
    fields: [
      { id: 'name', name: '名称', type: 'TEXT' },
      { id: 'memo', name: '内容明细', type: 'TEXTAREA' }
    ],
    fieldOptions: {},
    details: [],
    relations: [],
    settings: {}
  },
  details: {}
})
/** 一共 25 条；按请求的页码与每页条数切。 */
const pageOf = (query: { pageNo?: number; pageSize?: number }) => {
  const size = query.pageSize || 10,
    start = ((query.pageNo || 1) - 1) * size
  return {
    list: Array.from({ length: Math.max(0, Math.min(size, 25 - start)) }, (_, index) => row(String(start + index + 1))),
    total: 25
  }
}
const view = (extra: Record<string, unknown> = {}) => ({
  objectId: 'object',
  formId: null,
  fieldIds: ['name', 'memo'],
  pageSize: 10,
  sortFieldId: null,
  descending: true,
  equal: {},
  query: null,
  composition: null,
  detailPageId: null,
  filterDictionaries: {},
  list: { batchDelete: false, columnWidths: {}, queryFieldIds: [], advancedFieldIds: null },
  interaction: { buttons: ['VIEW'], actionIds: [], editMode: 'DRAWER', detailMode: 'DRAWER' },
  ...extra
})
const composition = {
  grain: 'ROOT',
  detailId: null,
  sections: [
    {
      id: 'vouchers',
      name: '引用本流水的凭证',
      detailId: null,
      objectId: 'voucher',
      viewId: null,
      binding: { direction: 'INCOMING', relationId: 'flow' },
      fieldIds: [],
      conditions: null,
      pageSize: 10,
      showTable: true
    }
  ],
  columns: []
}

beforeEach(() => {
  api.model.mockImplementation(async (_applicationId: string, objectId: string) => objectModel(objectId))
  api.page.mockImplementation(async (query: { pageNo?: number; pageSize?: number }) => pageOf(query))
  api.viewModel.mockResolvedValue({ composition, fields: [], fieldOptions: {}, sections: {} })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('header of the expand column', () => {
  it('stays blank on the shared table when the caller renders titles as text, and data columns still use the caller title', async () => {
    await mount(
      OsTablePage,
      {
        columns: [{ title: '名称', key: 'name', width: 170 }],
        dataSource: [{ id: '1', name: '甲' }],
        rowKey: 'id',
        pagination: false,
        resizable: true
      },
      {
        // 调用方按文字渲染标题（模板里的 {{ column.title }} 就是这个函数）
        columnTitle: ({ column }: { column: { title: unknown } }) =>
          h('span', '〔' + toDisplayString(column.title) + '〕'),
        expandedRowRender: () => h('div', '子表')
      }
    )
    expect(expandHead()).toBeTruthy()
    expect(expandHead()!.textContent!.trim()).toBe('')
    expect(headTexts()).toEqual(['', '〔序号〕', '〔名称〕'])
  })

  it('stays blank on a runtime list whose view has a child table section, with rows and with an empty list', async () => {
    const props = { applicationId: 'app', objectId: 'object', viewId: 'view', view: view({ composition }) }
    await mount(BusinessRecords, props)
    expect(expandHead()).toBeTruthy()
    expect(headTexts()).toEqual(['', '序号', '名称', '内容明细', '操作'])
    expect(host.querySelectorAll('.ant-table-tbody tr.ant-table-row').length).toBe(10)

    app!.unmount()
    host.remove()
    api.page.mockResolvedValue({ list: [], total: 0 })
    await mount(BusinessRecords, props)
    expect(host.querySelectorAll('.ant-table-tbody tr.ant-table-row').length).toBe(0)
    expect(expandHead()).toBeTruthy()
    expect(headTexts()).toEqual(['', '序号', '名称', '内容明细', '操作'])
  })
})

describe('page turns on a runtime list that has no sort column', () => {
  const lastQuery = () => api.page.mock.calls.at(-1)![0] as Record<string, unknown>
  const firstSequence = () => host.querySelector('.ant-table-tbody tr.ant-table-row td')!.textContent!.trim()
  const pageItem = (page: number) => host.querySelector<HTMLElement>('.ant-pagination-item-' + page)!
  const sortHead = (title: string) => heads().find(cell => (cell.textContent || '').trim().startsWith(title))!

  it.each([
    ['a view that sorts by nothing, newest first', { viewId: 'view', view: view() }],
    ['a list without a view', {}]
  ])('goes to page 2 on the first click and keeps the default order: %s', async (_name, extra) => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object', ...extra })
    expect(lastQuery()).toMatchObject({ pageNo: 1, pageSize: 10, descending: true })
    expect(lastQuery().sortFieldId).toBeUndefined()

    pageItem(2).click()
    await flush()
    expect(api.page).toHaveBeenCalledTimes(2)
    expect(lastQuery()).toMatchObject({ pageNo: 2, pageSize: 10, descending: true })
    expect(lastQuery().sortFieldId).toBeUndefined()
    expect(pageItem(2).className).toContain('ant-pagination-item-active')
    expect(firstSequence()).toBe('11')

    pageItem(3).click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 3, descending: true })
    expect(firstSequence()).toBe('21')
  })

  it('still returns to page 1 when the sort really changes, and to the default order when the sort is cancelled', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    pageItem(2).click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 2 })

    // 升序
    sortHead('名称').click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 1, sortFieldId: 'name', descending: false })
    pageItem(2).click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 2, sortFieldId: 'name', descending: false })
    // 降序：方向变了，回第一页
    sortHead('名称').click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 1, sortFieldId: 'name', descending: true })
    pageItem(2).click()
    await flush()
    // 取消排序：回第一页，顺序回到打开时的默认方向
    sortHead('名称').click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 1, descending: true })
    expect(lastQuery().sortFieldId).toBeUndefined()
    expect(sortHead('名称').getAttribute('aria-sort')).toBeNull()
    pageItem(2).click()
    await flush()
    expect(lastQuery()).toMatchObject({ pageNo: 2, descending: true })
    expect(firstSequence()).toBe('11')
  })
})

describe('height of a full-page runtime list', () => {
  const section = () => host.querySelector<HTMLElement>('.business-records')!
  const FIT = 'business-records--fit'
  const lastPageSize = () => (api.page.mock.calls.at(-1)![0] as { pageSize: number }).pageSize

  it('shows every row of the page when the page holds at most 10 rows, and fills the window with an inner scroll beyond that', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object', standalone: true })
    expect(section().className).toContain('business-records--standalone')
    expect(section().classList.contains(FIT)).toBe(true)
    app!.unmount()
    host.remove()

    await mount(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      standalone: true,
      viewId: 'view',
      view: view({ pageSize: 5 })
    })
    expect(section().classList.contains(FIT)).toBe(true)
    app!.unmount()
    host.remove()

    await mount(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      standalone: true,
      viewId: 'view',
      view: view({ pageSize: 20 })
    })
    expect(lastPageSize()).toBe(20)
    expect(section().classList.contains(FIT)).toBe(false)
  })

  it('leaves a list embedded in a page alone', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    expect(section().classList.contains(FIT)).toBe(false)
  })
})
