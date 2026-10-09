// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'

vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'table-sort-test' } }) }))
window.matchMedia = ((query: string) => ({
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
    observe() {
      return undefined
    }
    unobserve() {
      return undefined
    }
    disconnect() {
      return undefined
    }
  }
)
const computedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = (element: Element) => computedStyle(element)
let app: App | undefined, host: HTMLElement
async function flush() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('真实表格的列宽拖拽与排序事件', () => {
  it('受控默认降序在双向循环中切换升序、降序，不被取消排序重置吞掉', async () => {
    const order = ref<'descend' | 'ascend'>('descend')
    const change = vi.fn((_pagination: unknown, _filters: unknown, sorter: { order?: 'descend' | 'ascend' }) => {
      order.value = sorter.order === 'ascend' ? 'ascend' : 'descend'
    })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() =>
      h(OsTablePage, {
        title: '任务工作量',
        rowKey: 'id',
        columns: [
          {
            key: 'standardMinutes',
            title: '期间标准工时',
            dataIndex: 'standardMinutes',
            width: 160,
            sorter: true,
            sortOrder: order.value,
            sortDirections: ['descend', 'ascend', 'descend']
          }
        ],
        dataSource: [{ id: 'task', standardMinutes: 30 }],
        pagination: { current: 1, pageSize: 10, total: 20 },
        serverPagination: true,
        resizable: true,
        onChange: change
      })
    )
    app.use(
      createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] })
    )
    app.use(Antd)
    app.mount(host)
    await flush()
    const header = () =>
      Array.from(host.querySelectorAll<HTMLElement>('th')).find(cell => cell.textContent?.includes('期间标准工时'))
    expect(header()?.getAttribute('aria-sort')).toBe('descending')
    header()?.click()
    await flush()
    expect(change).toHaveBeenCalledTimes(1)
    expect(change.mock.calls[0]?.[2]).toMatchObject({ order: 'ascend' })
    expect(order.value).toBe('ascend')
    expect(header()?.getAttribute('aria-sort')).toBe('ascending')
    header()?.click()
    await flush()
    expect(change).toHaveBeenCalledTimes(2)
    expect(change.mock.calls[1]?.[2]).toMatchObject({ order: 'descend' })
    expect(order.value).toBe('descend')
    expect(header()?.getAttribute('aria-sort')).toBe('descending')
  })
  it.each([false, true])('resizable=%s 时真实排序标题保持点击行为', async resizable => {
    const change = vi.fn()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() =>
      h(OsTablePage, {
        title: '任务工作量',
        rowKey: 'id',
        columns: [
          {
            key: 'standardMinutes',
            title: '标准工时',
            dataIndex: 'standardMinutes',
            width: 160,
            sorter: true,
            sortOrder: 'descend'
          },
          { key: 'recordCount', title: '计量记录', dataIndex: 'recordCount', width: 120, sorter: true, sortOrder: null }
        ],
        dataSource: [{ id: 'task', standardMinutes: 30, recordCount: 2 }],
        pagination: { current: 1, pageSize: 10, total: 20 },
        serverPagination: true,
        resizable,
        showColumnSettings: true,
        onChange: change
      })
    )
    app.use(
      createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] })
    )
    app.use(Antd)
    app.mount(host)
    await flush()
    const header = Array.from(host.querySelectorAll<HTMLElement>('th')).find(cell =>
      cell.textContent?.includes('计量记录')
    )
    expect(header).toBeTruthy()
    header?.click()
    await flush()
    expect(change).toHaveBeenCalledTimes(1)
    expect(change.mock.calls[0]?.[2]).toMatchObject({ columnKey: 'recordCount', order: 'ascend' })
    expect(change.mock.calls[0]?.[3]).toMatchObject({ action: 'sort' })
    change.mockClear()
    header?.querySelector<HTMLElement>('.ant-table-column-sorter')?.click()
    await flush()
    expect(change).toHaveBeenCalledTimes(1)
    expect(change.mock.calls[0]?.[3]).toMatchObject({ action: 'sort' })
    change.mockClear()
    header?.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Enter', keyCode: 13 }))
    await flush()
    expect(change).toHaveBeenCalledTimes(1)
    if (resizable) {
      change.mockClear()
      header?.querySelector<HTMLElement>('.la-resize-handle')?.click()
      await flush()
      expect(change).not.toHaveBeenCalled()
    }
  })
})
