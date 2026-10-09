// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TinyPageDesigner from '@/views/nocode/application/components/TinyPageDesigner.vue'
import { pageSchema } from './page-schema'
import { emptyTabs, nestingMessage, nestingViolation, pageStructureError } from './page-nesting'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'

const warning = vi.hoisted(() => vi.fn())
vi.mock('ant-design-vue', async original => ({
  ...(await original<typeof import('ant-design-vue')>()),
  message: { warning, success: vi.fn(), error: vi.fn() }
}))
vi.mock('@/components/InfraUpload.vue', () => ({ default: { render: () => null } }))
vi.mock('@/nocode/page-image', () => ({ pageImageUrl: vi.fn(async () => '/mock-authorized-image') }))

const labels = {
  OsTabs: '页签容器',
  OsTab: '页签',
  OsRow: '分栏容器',
  OsCol: '分栏',
  OsView: '数据列表',
  OsCard: '卡片'
}
const node = (componentName: string, children: unknown[] = [], id = componentName + Math.random()) => ({
  id,
  componentName,
  props: { text: componentName === 'OsTabs' ? '民宿页签' : '' },
  children: children as never[]
})
const page = (...children: unknown[]) => node('Page', children, 'page')

// 业务方 2026-10-04：画布上把「页签容器」拖进另一个「页签容器」（与页签并排），保存时被后端拒「页签容器只能包含至少一个页签」。
describe('页签容器 / 分栏容器的父子约束', () => {
  it('页签容器里直接放了页签以外的东西：点名是谁放进了谁', () => {
    const inner = node('OsTabs', [node('OsTab')], 'inner')
    const v = nestingViolation(page(node('OsTabs', [node('OsTab'), inner], 'outer')))
    expect(v).toMatchObject({
      parent: 'OsTabs',
      child: 'OsTabs',
      required: 'OsTab',
      kind: 'child-only',
      parentId: 'outer',
      childId: 'inner'
    })
    expect(nestingMessage(v!, labels)).toBe(
      '「页签容器」不能直接放进「页签容器」：页签容器里只能放页签，请拖进某个页签里'
    )
  })
  it('放进某个页签里是合规的；分栏容器只收分栏', () => {
    expect(nestingViolation(page(node('OsTabs', [node('OsTab', [node('OsTabs', [node('OsTab')])])])))).toBeNull()
    const v = nestingViolation(page(node('OsRow', [node('OsCol'), node('OsView')])))
    expect(nestingMessage(v!, labels)).toBe(
      '「数据列表」不能直接放进「分栏容器」：分栏容器里只能放分栏，请拖进某个分栏里'
    )
  })
  it('页签、分栏只能放在各自的容器里', () => {
    const v = nestingViolation(page(node('OsCard', [node('OsTab')])))
    expect(v).toMatchObject({ kind: 'parent-only', parent: 'OsCard', child: 'OsTab', required: 'OsTabs' })
    expect(nestingMessage(v!, labels)).toBe('「页签」只能放在「页签容器」里，不能放进「卡片」')
    expect(nestingMessage(nestingViolation(page(node('OsCol')))!, labels)).toBe(
      '「分栏」只能放在「分栏容器」里，不能放进「页面」'
    )
  })
  it('空的页签容器单独报（删光页签时不撤回，应用到草稿时提示）', () => {
    expect(nestingViolation(page(node('OsTabs')))).toBeNull()
    expect(emptyTabs(page(node('OsCard', [node('OsTabs', [], 'empty')])))?.id).toBe('empty')
    expect(pageStructureError(page(node('OsTabs')), labels)).toBe(
      '页签容器「民宿页签」里至少要有一个页签，请添加页签或删除这个页签容器'
    )
    expect(pageStructureError(page(node('OsTabs', [node('OsTab')])), labels)).toBeNull()
  })
})

let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(nodes: UiNode[]) {
  app = createApp(TinyPageDesigner, { nodes, resources: [], objects: {} })
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AForm', 'AFormItem', 'ASpace', 'ATag', 'ASelect', 'ASegmented', 'ATextarea', 'AEmpty'])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component('AAlert', defineComponent({ props: ['message'], setup: p => () => h('p', p.message) }))
  host = document.createElement('div')
  document.body.append(host)
  const exposed = app.mount(host) as unknown as { getNodes: () => UiNode[] }
  await flush()
  const source = host.querySelector('iframe')!.contentWindow!
  const send = async (type: string, data: Record<string, unknown> = {}) => {
    window.dispatchEvent(
      new MessageEvent('message', {
        source,
        origin: location.origin,
        data: { channel: 'os-page-designer', type, ...data }
      })
    )
    await flush()
  }
  await send('READY')
  return { exposed, send }
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  warning.mockReset()
})

describe('页面设计器宿主：拖放被撤回时提示，应用到草稿前核对结构', () => {
  const tabs = () =>
    uiNode(NodeKind.TABS, {
      id: 'tabs',
      text: '民宿页签',
      children: [uiNode(NodeKind.TAB, { id: 'tab-a', text: '基本信息' })]
    })
  it('编辑器报告拖放不合规：提示说明原因', async () => {
    const { send } = await mount([tabs()])
    await send('REJECTED', { violation: { parent: 'OsTabs', child: 'OsTabs', required: 'OsTab', kind: 'child-only' } })
    expect(warning).toHaveBeenCalledWith('「页签容器」不能直接放进「页签容器」：页签容器里只能放页签，请拖进某个页签里')
  })
  it('结构里有不合规的父子关系或空的页签容器：应用到草稿时拦下并说明', async () => {
    const { exposed, send } = await mount([tabs()])
    const nested = [
      uiNode(NodeKind.TABS, {
        id: 'tabs',
        text: '民宿页签',
        children: [
          uiNode(NodeKind.TAB, { id: 'tab-a' }),
          uiNode(NodeKind.TABS, { id: 'inner', children: [uiNode(NodeKind.TAB, { id: 'tab-b' })] })
        ]
      })
    ]
    await send('CHANGE', { schema: pageSchema(nested) })
    expect(() => exposed.getNodes()).toThrow(
      '「页签容器」不能直接放进「页签容器」：页签容器里只能放页签，请拖进某个页签里'
    )
    await send('CHANGE', { schema: pageSchema([uiNode(NodeKind.TABS, { id: 'tabs', text: '民宿页签' })]) })
    expect(() => exposed.getNodes()).toThrow('页签容器「民宿页签」里至少要有一个页签')
    const fine = [
      uiNode(NodeKind.TABS, {
        id: 'tabs',
        children: [
          uiNode(NodeKind.TAB, {
            id: 'tab-a',
            children: [uiNode(NodeKind.TABS, { id: 'inner', children: [uiNode(NodeKind.TAB, { id: 'tab-b' })] })]
          })
        ]
      })
    ]
    await send('CHANGE', { schema: pageSchema(fine) })
    expect(exposed.getNodes()[0]!.children[0]!.children[0]!.id).toBe('inner')
  })
})
