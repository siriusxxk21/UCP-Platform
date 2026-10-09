// @vitest-environment jsdom
// 页签容器把标签栏右侧的挂载点交给「页签里唯一那个列表」：只交给它、只在它的页签被选中时显示；判不出归属的页签不留挂载点。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
import { NodeKind, type UiNode } from '@/types/nocode/application-ui'

const blocks = vi.hoisted(() => new Map<string, { target: unknown }>())
vi.mock('@/views/nocode/application/components/BusinessBlock.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['resourceId', 'searchTarget'],
      setup(props) {
        return () => {
          blocks.set(props.resourceId, { target: props.searchTarget })
          return h('div', { 'data-block': props.resourceId })
        }
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/ReportBlock.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportDashboardFilters.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordExtras.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/PageImage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/PageActionButton.vue', () => ({ default: { render: () => null } }))
// dev 的任务列表块（设计端 OsTasks → 运行时 NodeKind.TASKS）：带 resourceId，但不是列表，不该拿页签行挂载点。
const taskBlocks = vi.hoisted(() => new Map<string, Record<string, unknown>>())
vi.mock('@/views/nocode/application/components/PageTasks.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      inheritAttrs: false,
      props: ['nodeId'],
      setup(props, { attrs }) {
        return () => {
          taskBlocks.set(props.nodeId, { ...attrs })
          return h('div', { 'data-tasks': props.nodeId })
        }
      }
    })
  }
})
vi.mock('@/nocode/application-context', () => ({
  blockRefreshId: () => 'x',
  usePageRefresh: () => ({ value: {} })
}))
import PageRenderer from '@/views/nocode/application/components/PageRenderer.vue'

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

let app: App | undefined, host: HTMLDivElement
/** 取不到就让用例失败（不用非空断言）。 */
function must<T>(value: T | null | undefined, what = '元素'): T {
  if (value == null) throw new Error('没有找到' + what)
  return value
}
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  blocks.clear()
  taskBlocks.clear()
})
const node = (id: string, type: string, extra: Partial<UiNode> = {}): UiNode =>
  ({ id, type, text: id, span: 24, children: [], resourceId: null, ...extra }) as unknown as UiNode
const view = (id: string) => node(id, NodeKind.VIEW, { resourceId: 'view-' + id })
async function render(nodes: UiNode[]) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(PageRenderer, { nodes, applicationId: 'app', resources: [], pageId: 'page' }))
  app.use(Antd)
  app.mount(host)
  await flush()
}
const clickTab = async (text: string) => {
  must(Array.from(host.querySelectorAll<HTMLElement>('.ant-tabs-tab-btn')).find(e => e.textContent === text)).click()
  await flush()
}

describe('tab row search target', () => {
  const tabs = node('tabs', NodeKind.TABS, {
    children: [
      node('订单明细', NodeKind.TAB, { children: [view('orders')] }),
      node('两张表', NodeKind.TAB, { children: [view('a'), view('b')] }),
      node('卡片里', NodeKind.TAB, { children: [node('card', NodeKind.CARD, { children: [view('c')] })] }),
      node('房间', NodeKind.TAB, { children: [node('t', NodeKind.TEXT), view('rooms')] })
    ]
  })

  it('gives the target in the tab bar extra area to the only list of the tab, and to nobody else', async () => {
    await render([tabs])
    const extra = must(host.querySelector('.page-tabs > .ant-tabs-nav > .ant-tabs-extra-content'))
    expect(extra).toBeTruthy()
    // 只有「订单明细」「房间」两个页签有归属；另两个不留挂载点
    expect(Array.from(extra.querySelectorAll('[data-tab-search]')).map(e => e.getAttribute('data-tab-search'))).toEqual(
      ['订单明细', '房间']
    )
    const ordersTarget = extra.querySelector('[data-tab-search="订单明细"]')
    expect(must(blocks.get('view-orders')).target).toBe(ordersTarget)
    await clickTab('两张表')
    expect(must(blocks.get('view-a')).target).toBeUndefined()
    expect(must(blocks.get('view-b')).target).toBeUndefined()
    await clickTab('卡片里')
    expect(must(blocks.get('view-c')).target).toBeUndefined()
    await clickTab('房间')
    expect(must(blocks.get('view-rooms')).target).toBe(extra.querySelector('[data-tab-search="房间"]'))
  })

  it('shows only the target of the selected tab', async () => {
    await render([tabs])
    const shown = () =>
      Array.from(host.querySelectorAll<HTMLElement>('[data-tab-search]'))
        .filter(e => e.style.display !== 'none')
        .map(e => e.getAttribute('data-tab-search'))
    expect(shown()).toEqual(['订单明细'])
    await clickTab('两张表')
    expect(shown()).toEqual([])
    await clickTab('房间')
    expect(shown()).toEqual(['房间'])
    await clickTab('订单明细')
    expect(shown()).toEqual(['订单明细'])
  })

  it('leaves tab containers without an owned list exactly as before (no extra area)', async () => {
    await render([
      node('tabs', NodeKind.TABS, { children: [node('两张表', NodeKind.TAB, { children: [view('a'), view('b')] })] })
    ])
    expect(host.querySelector('.ant-tabs-extra-content')).toBeNull()
    expect(host.querySelector('.page-tabs--search')).toBeNull()
    expect(must(blocks.get('view-a')).target).toBeUndefined()
  })

  it('ignores task list blocks (TASKS): a tab with a task list and one list gives the bar to the list; a task list alone gets nothing', async () => {
    await render([
      node('tabs', NodeKind.TABS, {
        children: [
          node('任务与订单', NodeKind.TAB, {
            children: [node('tasks', NodeKind.TASKS, { resourceId: 'task-view' }), view('orders')]
          }),
          node('只有任务', NodeKind.TAB, { children: [node('tasks2', NodeKind.TASKS, { resourceId: 'task-view' })] })
        ]
      })
    ])
    const extra = must(host.querySelector('.page-tabs > .ant-tabs-nav > .ant-tabs-extra-content'))
    expect(Array.from(extra.querySelectorAll('[data-tab-search]')).map(e => e.getAttribute('data-tab-search'))).toEqual(
      ['任务与订单']
    )
    expect(must(blocks.get('view-orders')).target).toBe(extra.querySelector('[data-tab-search="任务与订单"]'))
    expect(host.querySelector('[data-tasks="tasks"]')).toBeTruthy()
    expect(must(taskBlocks.get('tasks')).searchTarget).toBeUndefined()
    await clickTab('只有任务')
    expect(host.querySelector('[data-tasks="tasks2"]')).toBeTruthy()
    expect(
      Array.from(host.querySelectorAll<HTMLElement>('[data-tab-search]')).filter(e => e.style.display !== 'none')
    ).toHaveLength(0)
  })

  it('does not hand a target to lists outside tab containers', async () => {
    await render([view('alone')])
    expect(must(blocks.get('view-alone')).target).toBeUndefined()
  })
})
