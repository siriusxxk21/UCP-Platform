// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Antd from 'ant-design-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'

const nativeGetComputedStyle = window.getComputedStyle.bind(window)
let app: App | undefined, host: HTMLDivElement
const makeRows = (count: number, prefix = 'task') =>
  Array.from({ length: count }, (_, index) => ({ id: `${prefix}-${index}`, title: `${prefix} ${index}` }))
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(current = 3, total = 344, serverPagination = true) {
  const state = reactive({
    dataSource: makeRows(10),
    loading: false,
    pagination: { current, pageSize: 10, total, showSizeChanger: true, showTotal: (count: number) => `共 ${count} 条` }
  })
  const onChange = vi.fn((page: { current: number; pageSize: number }) => {
    state.pagination.current = page.current
    state.pagination.pageSize = page.pageSize
  })
  app = createApp(() =>
    h(OsTablePage, {
      ...state,
      columns: [{ key: 'title', dataIndex: 'title', title: '任务' }],
      serverPagination,
      onChange
    })
  )
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { state, onChange }
}
const rowIds = () =>
  Array.from(host.querySelectorAll('.ant-table-tbody > tr.ant-table-row')).map(row => row.getAttribute('data-row-key'))

beforeEach(() => {
  // jsdom 不实现伪元素样式；表格测量滚动条时只需普通元素样式。
  vi.stubGlobal('getComputedStyle', (element: Element) => nativeGetComputedStyle(element))
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn()
  }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.unstubAllGlobals()
})

describe('任务展开行与真实表格分页', () => {
  it.each([1, 3])('第 %s 页展开超过每页条数仍完整显示，收起不改变页码和总数', async current => {
    const { state, onChange } = await mount(current)
    const original = [...state.dataSource]
    state.dataSource.splice(2, 0, ...makeRows(3, 'child'))
    await flush()
    expect(rowIds()).toEqual(state.dataSource.map(row => row.id))
    expect(host.querySelector('.ant-pagination-item-active')?.textContent).toBe(String(current))
    expect(host.querySelector('.ant-pagination-total-text')?.textContent).toBe('共 344 条')
    state.dataSource = original
    await flush()
    expect(rowIds()).toEqual(original.map(row => row.id))
    expect(onChange).not.toHaveBeenCalled()
  })

  it('末页和展开行多于服务端总数时也不截断', async () => {
    const { state } = await mount(3, 24)
    state.dataSource = [...makeRows(4), ...makeRows(25, 'child')]
    await flush()
    expect(rowIds()).toHaveLength(29)
    expect(host.querySelector('.ant-pagination-item-active')?.textContent).toBe('3')
    expect(host.querySelector('.ant-pagination-total-text')?.textContent).toBe('共 24 条')
  })

  it('外部分页换页只发一次查询事件，保留服务端总数', async () => {
    const { onChange } = await mount()
    host.querySelector<HTMLElement>('.ant-pagination-item-4')!.click()
    await flush()
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange.mock.calls[0]?.[0]).toMatchObject({ current: 4, pageSize: 10, total: 344 })
  })

  it('调整每页数量返回第一页且只发一次事件', async () => {
    const { onChange } = await mount()
    host
      .querySelector<HTMLElement>('.ant-select-selector')!
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await new Promise(resolve => setTimeout(resolve, 100))
    await flush()
    const option = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(item =>
      item.textContent?.trim().startsWith('20')
    )
    expect(option).toBeDefined()
    option!.click()
    await flush()
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange.mock.calls[0]?.[0]).toMatchObject({ current: 1, pageSize: 20, total: 344 })
  })

  it('翻页加载期间隐藏旧页，同页刷新保留已有行', async () => {
    const { state } = await mount()
    state.loading = true
    await flush()
    expect(rowIds()).toHaveLength(10)
    state.pagination.current = 4
    await flush()
    expect(rowIds()).toHaveLength(0)
    state.dataSource = makeRows(12, 'next')
    state.loading = false
    await flush()
    expect(rowIds()).toEqual(state.dataSource.map(row => row.id))
  })

  it('未启用时保留原有本地分页，不影响其他表格', async () => {
    const { state } = await mount(2, 35, false)
    state.dataSource = makeRows(35)
    await flush()
    expect(rowIds()).toEqual(
      makeRows(35)
        .slice(10, 20)
        .map(row => row.id)
    )
    expect(host.querySelector('.os-table-page__pagination')).toBeNull()
  })
})
