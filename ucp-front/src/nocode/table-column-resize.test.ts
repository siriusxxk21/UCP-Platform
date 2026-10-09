// @vitest-environment jsdom
// 表格列宽拖拽：每个非固定列的表头右缘都有把手；可排序列既能点排序也能拖宽；拖完的宽度不因翻页 / 刷新 / 静默重取而丢。
// 用的是真实的 OsTablePage 与 ant 的表格（不打桩）：把手挂在哪个节点上、点击会不会冒到排序，只有真实结构才看得出来。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Antd from 'ant-design-vue'
import { invalidateRuntimeData } from './runtime-data'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  viewChildren: vi.fn(),
  viewModel: vi.fn(),
  selection: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'column-resize-test' } }) }))
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
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props))
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] }))
  app.use(Antd)
  app.mount(host)
  await flush()
}

const heads = () => Array.from(host.querySelectorAll<HTMLElement>('.ant-table-thead > tr > th'))
const head = (title: string) => {
  const th = heads().find(cell => (cell.textContent || '').trim().startsWith(title))
  if (!th) throw new Error('没有这个列头：' + title)
  return th
}
/** 把手必须是表头单元格的直接子节点：放进标题里的把手会被排序容器 / 省略裁掉（样式层的事，jsdom 看不出来）。 */
const handle = (title: string) => head(title).querySelector<HTMLElement>(':scope > .la-resize-handle')
/** 该列当前生效的宽度：ant 把列宽写在 colgroup 里。 */
const width = (title: string) => {
  const index = heads().indexOf(head(title))
  return host
    .querySelector('.ant-table colgroup')!
    .children[index]?.getAttribute('style')
    ?.match(/(?:^|;\s*)width:\s*([^;]+)/)?.[1]
}
/**
 * 拖把手：按下、移动、松开；随后按浏览器的行为在「按下处与松开处的共同祖先」上补发一次 click
 * （松开时指针还在把手上 ⇒ 落在把手；已经移到同一个列头的别处 ⇒ 落在列头）。
 */
async function drag(title: string, dx: number, releaseOn: 'handle' | 'head' = 'handle') {
  const grip = handle(title)
  if (!grip) throw new Error('这个列头没有把手：' + title)
  grip.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: 500 }))
  document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: 500 + dx }))
  document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 500 + dx }))
  ;(releaseOn === 'handle' ? grip : head(title)).dispatchEvent(
    new MouseEvent('click', { bubbles: true, cancelable: true })
  )
  await flush()
}

afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('column resize handles on the shared table', () => {
  const changed = vi.fn()
  const columns = [
    { title: '编号', key: 'code', width: 120 },
    { title: '名称', key: 'name', width: 170, ellipsis: true, sorter: true },
    { title: '摘要', key: 'memo', width: 170, sorter: true },
    { title: '金额', key: 'amount', width: 170, ellipsis: true },
    { title: '操作', key: 'actions', width: 250, fixed: 'right' as const }
  ]
  const rows = [{ id: '1', code: 'A', name: '甲', memo: '很长的摘要', amount: 1 }]
  const mountTable = (extra: Record<string, unknown> = {}) =>
    mount(OsTablePage, {
      columns,
      dataSource: rows,
      rowKey: 'id',
      pagination: false,
      scroll: { x: 'max-content' },
      resizable: true,
      onChange: changed,
      ...extra
    })
  beforeEach(() => changed.mockReset())

  it('puts a handle on the header cell itself for every non-fixed column, sortable and ellipsis ones included', async () => {
    await mountTable()
    for (const title of ['序号', '编号', '名称', '摘要', '金额']) {
      expect(handle(title), title).toBeTruthy()
      // 不在排序容器、标题容器里
      expect(handle(title)!.closest('.ant-table-column-sorters, .ant-table-column-title'), title).toBeNull()
    }
    // 整张表里没有别处的把手（比如留在标题里的旧把手）
    expect(host.querySelectorAll('.la-resize-handle').length).toBe(
      host.querySelectorAll('.ant-table-thead > tr > th > .la-resize-handle').length
    )
  })

  it('leaves tables that are not resizable without handles', async () => {
    await mountTable({ resizable: false })
    expect(host.querySelector('.la-resize-handle')).toBeNull()
    expect(head('名称').className).toContain('ant-table-column-has-sorters')
  })

  it('changes the width of plain, sortable and index columns when their handle is dragged', async () => {
    await mountTable()
    expect(width('名称')).toBe('170px')
    await drag('名称', 60)
    expect(width('名称')).toBe('230px')
    await drag('编号', -30)
    expect(width('编号')).toBe('90px')
    await drag('摘要', 45)
    expect(width('摘要')).toBe('215px')
    expect(width('序号')).toBe('60px')
    await drag('序号', 20)
    expect(width('序号')).toBe('80px')
    // 其它列不受影响
    expect(width('金额')).toBe('170px')
    expect(width('名称')).toBe('230px')
  })

  it('does not sort when a sortable column is resized, and still sorts on a plain click', async () => {
    await mountTable()
    // 松开时指针还在把手上
    await drag('名称', 60, 'handle')
    expect(changed, '拖完在把手上松开').not.toHaveBeenCalled()
    // 松开时指针已经移到同一个列头的别处：浏览器把 click 发在列头上（只有「拦掉松开后补发的那一次」挡得住）
    await drag('名称', -20, 'head')
    await drag('摘要', 30, 'head')
    expect(changed, '拖完在列头别处松开').not.toHaveBeenCalled()
    // 只点一下把手、不拖（只有「把手上的点击不冒泡」挡得住）
    handle('名称')!.click()
    await flush()
    expect(changed, '只点把手不拖').not.toHaveBeenCalled()
    expect(head('名称').getAttribute('aria-sort')).toBeNull()
    expect(width('名称')).toBe('210px')

    // 之后正常点列头：照常排序（拖动留下的拦截不会吃掉后面的点击）
    head('名称').click()
    await flush()
    expect(changed).toHaveBeenCalledTimes(1)
    expect(changed.mock.calls[0][2]).toMatchObject({ columnKey: 'name', order: 'ascend' })
    expect(head('名称').getAttribute('aria-sort')).toBe('ascending')
    // 排序不动列宽
    expect(width('名称')).toBe('210px')
  })
})

describe('column widths on the runtime record list', () => {
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
      objectName: '凭证',
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
  const pageOf = (query: { pageNo?: number; pageSize?: number }) => {
    const size = query.pageSize || 10,
      start = ((query.pageNo || 1) - 1) * size
    return {
      list: Array.from({ length: Math.min(size, 25 - start) }, (_, index) => row(String(start + index + 1))),
      total: 25
    }
  }
  beforeEach(() => {
    api.model.mockImplementation(async (_applicationId: string, objectId: string) => objectModel(objectId))
    api.page.mockImplementation(async (query: { pageNo?: number; pageSize?: number }) => pageOf(query))
  })
  const mountList = () => mount(BusinessRecords, { applicationId: 'app', objectId: 'object' })
  const button = (text: string) =>
    Array.from(host.querySelectorAll<HTMLElement>('button')).find(item => (item.textContent || '').trim() === text)!

  it('gives the sortable text column and the long-text column a handle, and resizing them sends no sort request', async () => {
    await mountList()
    expect(head('名称').className).toContain('ant-table-column-has-sorters')
    expect(head('内容明细').className).toContain('ant-table-column-has-sorters')
    expect(handle('序号')).toBeTruthy()
    expect(handle('名称')).toBeTruthy()
    expect(handle('内容明细')).toBeTruthy()
    const requests = api.page.mock.calls.length
    await drag('名称', 60, 'head')
    await drag('内容明细', 80, 'head')
    expect(width('名称')).toBe('230px')
    expect(width('内容明细')).toBe('250px')
    // 拖宽度不重查、不排序
    expect(api.page.mock.calls.length).toBe(requests)
    expect(head('名称').getAttribute('aria-sort')).toBeNull()
  })

  it('keeps the dragged widths across a manual refresh, a sort, a page change and a quiet reload', async () => {
    await mountList()
    await drag('名称', 60)
    await drag('内容明细', 80)
    const kept = () => [width('名称'), width('内容明细')]
    expect(kept()).toEqual(['230px', '250px'])
    let requests = api.page.mock.calls.length
    const reloaded = () => {
      const more = api.page.mock.calls.length - requests
      requests = api.page.mock.calls.length
      return more
    }

    // 软刷新（列表上的「刷新」按钮）
    button('刷新').click()
    await flush()
    expect(reloaded()).toBe(1)
    expect(kept()).toEqual(['230px', '250px'])

    // 点列头排序
    head('名称').click()
    await flush()
    expect(reloaded()).toBe(1)
    expect(api.page.mock.calls.at(-1)![0]).toMatchObject({ sortFieldId: 'name', descending: false })
    expect(head('名称').getAttribute('aria-sort')).toBe('ascending')
    expect(kept()).toEqual(['230px', '250px'])

    // 翻页
    host.querySelector<HTMLElement>('.ant-pagination-item-2')!.click()
    await flush()
    expect(reloaded()).toBe(1)
    expect(api.page.mock.calls.at(-1)![0]).toMatchObject({ pageNo: 2, sortFieldId: 'name' })
    expect(kept()).toEqual(['230px', '250px'])

    // 推送 / 回到前台触发的静默重取
    invalidateRuntimeData({ applicationId: 'app', objectId: 'object' })
    await flush()
    expect(reloaded()).toBe(1)
    expect(kept()).toEqual(['230px', '250px'])
  })

  it('keeps the dragged widths while the list sits in a kept-alive page that is switched away and back', async () => {
    const { KeepAlive, ref } = await import('vue')
    const shown = ref(true)
    const Page = defineComponent({
      setup: () => () =>
        h(KeepAlive, null, () =>
          shown.value ? h(BusinessRecords, { applicationId: 'app', objectId: 'object' }) : null
        )
    })
    await mount(Page, {})
    await drag('名称', 60)
    expect(width('名称')).toBe('230px')
    shown.value = false
    await flush()
    expect(host.querySelector('.ant-table')).toBeNull()
    shown.value = true
    await flush()
    expect(width('名称')).toBe('230px')
  })
})
