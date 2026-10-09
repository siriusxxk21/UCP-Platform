// @vitest-environment jsdom
// 视图配置「内容超出列宽时自动截断」在运行端列表上的效果：没开＝与原来一样；开＝列宽严格按配置、业务列统一单行省略。
// 用的是真实的 OsTablePage 与 ant 的表格（不打桩）：表格布局、列宽、单元格上的类名只有真实表格才看得出来。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App, type Component } from 'vue'
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
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'list-overflow-test' } }) }))
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
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props))
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] }))
  app.use(Antd)
  app.mount(host)
  await flush()
}

const LONG = '第一行\n第二行\n' + '很长的摘要'.repeat(40)
const RICH = '<p>富文本<strong>正文</strong></p><p>第二段</p>'
const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE'],
  readFields: ['name', 'memo', 'note', 'amount'],
  writeFields: ['name', 'memo', 'note', 'amount'],
  readDetails: [],
  writeDetails: []
}
const objectModel = (objectId: string) => ({
  writable: true,
  permissions,
  object: {
    objectId,
    objectName: '资金流水',
    titleFieldId: 'name',
    fields: [
      { id: 'name', name: '名称', type: 'TEXT' },
      { id: 'memo', name: '摘要', type: 'TEXTAREA' },
      { id: 'note', name: '说明', type: 'RICH_TEXT' },
      { id: 'amount', name: '金额', type: 'DECIMAL' }
    ],
    fieldOptions: {},
    details: [],
    relations: [],
    settings: {}
  },
  details: {}
})
const view = (list: Record<string, unknown> = {}) => ({
  objectId: 'object',
  formId: null,
  fieldIds: ['name', 'memo', 'note', 'amount'],
  pageSize: 10,
  sortFieldId: null,
  descending: true,
  equal: {},
  query: null,
  composition: null,
  detailPageId: null,
  filterDictionaries: {},
  list: { batchDelete: false, columnWidths: { memo: 300 }, queryFieldIds: [], advancedFieldIds: null, ...list },
  interaction: { buttons: [], actionIds: [], editMode: 'DRAWER', detailMode: 'DRAWER' }
})
const mountList = (list: Record<string, unknown> = {}) =>
  mount(BusinessRecords, { applicationId: 'app', objectId: 'object', viewId: 'view', view: view(list) })

const heads = () => Array.from(host.querySelectorAll<HTMLElement>('.ant-table-thead > tr > th'))
const column = (title: string) => heads().findIndex(cell => (cell.textContent || '').trim().startsWith(title))
/** 第一行里某一列的单元格。 */
const cell = (title: string) =>
  host.querySelectorAll<HTMLElement>('.ant-table-tbody > tr.ant-table-row')[0]!.querySelectorAll('td')[column(title)]!
const tableStyle = () => host.querySelector<HTMLElement>('.ant-table table')!.style
const colWidth = (title: string) =>
  (host.querySelector('.ant-table colgroup')!.children[column(title)] as HTMLElement).style.width
const ELLIPSIS = 'ant-table-cell-ellipsis',
  FIELDS = ['名称', '摘要', '说明', '金额']
/** 没开时的显示：横向按内容排，多行文本、富文本换行且限高，其余单行。 */
function expectLegacy() {
  expect(tableStyle().tableLayout).not.toBe('fixed')
  expect(tableStyle().width).toBe('max-content')
  expect(cell('名称').classList.contains(ELLIPSIS)).toBe(true)
  expect(cell('金额').classList.contains(ELLIPSIS)).toBe(true)
  expect(cell('摘要').classList.contains(ELLIPSIS)).toBe(false)
  expect(cell('说明').classList.contains(ELLIPSIS)).toBe(false)
  expect(cell('摘要').querySelector('span')!.className).toBe('nocode-table-multiline')
  expect(cell('说明').querySelector('.rich-text-display--compact')).toBeTruthy()
  for (const title of FIELDS)
    expect(cell(title).className.trim(), title).toMatch(/^ant-table-cell( ant-table-cell-ellipsis)?$/)
}

beforeEach(() => {
  api.model.mockImplementation(async (_applicationId: string, objectId: string) => objectModel(objectId))
  api.page.mockResolvedValue({
    list: [{ id: '1', revision: '1', values: { name: '甲', memo: LONG, note: RICH, amount: '12.50' }, permissions }],
    total: 1
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('内容超出列宽时自动截断', () => {
  it('没开：与原来一样——横向按内容排，多行文本、富文本换行且限高，其余单行', async () => {
    await mountList()
    expectLegacy()
  })

  it('开：列宽严格按配置，业务列统一单行省略，悬停能看到全文', async () => {
    await mountList({ overflow: 'ELLIPSIS' })
    expect(tableStyle().tableLayout).toBe('fixed')
    // 表宽 = 各列宽度之和：序号 60 + 名称 170 + 摘要 300 + 说明 170 + 金额 170 + 操作 250
    expect(tableStyle().width).toBe('1120px')
    for (const title of FIELDS) expect(cell(title).classList.contains(ELLIPSIS), title).toBe(true)
    // 多行文本不再按多行显示；全文在 title 里
    expect(cell('摘要').querySelector('.nocode-table-multiline')).toBeNull()
    expect(cell('摘要').querySelector('span')!.getAttribute('title')).toBe(LONG)
    // 富文本按摘要文字单行显示
    expect(cell('说明').querySelector('.rich-text-display')).toBeNull()
    expect(cell('说明').querySelector('span')!.getAttribute('title')).toContain('富文本正文')
    // 序号列、操作列不受影响
    expect(cell('序号').classList.contains(ELLIPSIS)).toBe(false)
    expect(cell('操作').classList.contains(ELLIPSIS)).toBe(false)
  })

  it('拖动列宽后表宽跟着变：按新的宽度重新截断', async () => {
    await mountList({ overflow: 'ELLIPSIS' })
    expect(colWidth('摘要')).toBe('300px')
    const grip = heads()[column('摘要')]!.querySelector<HTMLElement>(':scope > .la-resize-handle')!
    grip.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: 500 }))
    document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: 420 }))
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 420 }))
    await flush()
    expect(colWidth('摘要')).toBe('220px')
    expect(tableStyle().tableLayout).toBe('fixed')
    expect(tableStyle().width).toBe('1040px')
  })

  it.each(['WRAP', 'AUTO', null])('不是 ELLIPSIS 的值（含不再支持的 WRAP）按没开处理：%s', async value => {
    await mountList({ overflow: value })
    expectLegacy()
  })
})
