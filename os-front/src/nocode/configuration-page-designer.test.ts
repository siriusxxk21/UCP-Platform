// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TinyPageDesigner from '@/views/nocode/application/components/TinyPageDesigner.vue'
import { pageSchema, pageNodes, type PageSchemaNode } from './page-schema'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'

vi.mock('@/components/InfraUpload.vue', () => ({ default: { render: () => null } }))
vi.mock('@/nocode/page-image', () => ({ pageImageUrl: vi.fn(async () => '/mock-authorized-image') }))
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  Array.from(host.querySelectorAll('button')).find(el => el.textContent?.trim() === label)!
async function mount() {
  const nodes = [uiNode(NodeKind.TEXT, { id: 'intro', text: '原布局' })]
  app = createApp(TinyPageDesigner, { nodes, resources: [], objects: {} })
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ASpace',
    'ATag',
    'ASelect',
    'ASegmented',
    'ARadioGroup',
    'ARadioButton',
    'ATextarea',
    'ASlider',
    'ARadio',
    'AInput',
    'AInputNumber',
    'ACheckbox',
    'ACollapsePanel',
    'ACollapse',
    'AEmpty'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  app.component('AAlert', defineComponent({ props: ['message'], setup: p => () => h('p', p.message) }))
  host = document.createElement('div')
  document.body.append(host)
  const exposed = app.mount(host) as unknown as { getNodes: () => UiNode[]; hasChanges: () => boolean }
  await flush()
  return { nodes, exposed }
}
function frame() {
  return host.querySelector('iframe')!
}
async function message(
  source: Window,
  type: string,
  data: Record<string, unknown> = {},
  origin = location.origin,
  channel = 'os-page-designer'
) {
  window.dispatchEvent(new MessageEvent('message', { source, origin, data: { channel, type, ...data } }))
  await flush()
}
async function ready(iframe: HTMLIFrameElement) {
  const post = vi.spyOn(iframe.contentWindow!, 'postMessage')
  await message(iframe.contentWindow!, 'READY')
  return post
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.restoreAllMocks()
})

describe('页面设计器受控 iframe 恢复', () => {
  it('CHANGE → ERROR → 重试 → READY 恢复最后合法快照，旧 iframe 无法覆盖', async () => {
    const { nodes, exposed } = await mount()
    const first = frame(),
      oldSource = first.contentWindow!
    const initialPost = await ready(first)
    expect(initialPost).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'INIT', schema: pageSchema(nodes) }),
      location.origin
    )
    await message(oldSource, 'CHANGE', { schema: pageSchema(nodes) })
    expect(exposed.hasChanges()).toBe(false)
    const edited = [
      uiNode(NodeKind.TEXT, { id: 'intro', text: '本次未应用布局' }),
      uiNode(NodeKind.HEADING, { id: 'title', text: '新标题' })
    ]
    await message(oldSource, 'CHANGE', { schema: pageSchema(edited) })
    expect(exposed.getNodes()).toEqual(pageNodes(pageSchema(edited)))
    expect(exposed.hasChanges()).toBe(true)
    await message(oldSource, 'ERROR', { message: '模拟画布运行错误' })
    expect(host.textContent).toContain('模拟画布运行错误')
    expect(() => exposed.getNodes()).toThrow('尚未就绪')
    button('重新加载').click()
    await flush()
    const second = frame()
    expect(second).not.toBe(first)
    const secondPost = vi.spyOn(second.contentWindow!, 'postMessage')
    await message(oldSource, 'READY')
    expect(secondPost).not.toHaveBeenCalled()
    await message(oldSource, 'CHANGE', { schema: pageSchema(nodes) })
    await message(oldSource, 'ERROR', { message: '旧画布错误' })
    expect(host.textContent).not.toContain('旧画布错误')
    const restoredPost = await ready(second)
    const init = restoredPost.mock.calls
      .map(([data]) => data as { type: string; schema?: PageSchemaNode })
      .find(data => data.type === 'INIT')!
    expect(pageNodes(init.schema!)).toEqual(pageNodes(pageSchema(edited)))
    await message(second.contentWindow!, 'CHANGE', { schema: init.schema })
    expect(exposed.getNodes()).toEqual(pageNodes(pageSchema(edited)))
    expect(exposed.hasChanges()).toBe(true)
    expect(nodes[0]!.text).toBe('原布局')
  })
  it('非法 schema、错误 origin 和 channel 都不能覆盖上一次合法布局', async () => {
    const { nodes, exposed } = await mount()
    const current = frame(),
      source = current.contentWindow!
    await ready(current)
    const changed = pageSchema([uiNode(NodeKind.TEXT, { id: 'intro', text: '不应被采纳' })])
    await message(source, 'CHANGE', { schema: changed }, 'https://untrusted.example')
    await message(source, 'CHANGE', { schema: changed }, location.origin, 'unrelated-channel')
    expect(exposed.getNodes()).toEqual(pageNodes(pageSchema(nodes)))
    await message(source, 'CHANGE', {
      schema: { componentName: 'Page', children: [{ componentName: 'script', id: 'bad' }] }
    })
    expect(() => exposed.getNodes()).toThrow()
    await message(source, 'ERROR', { message: '重新恢复合法布局' })
    button('重新加载').click()
    await flush()
    const post = await ready(frame())
    const init = post.mock.calls
      .map(([data]) => data as { type: string; schema?: PageSchemaNode })
      .find(data => data.type === 'INIT')!
    expect(pageNodes(init.schema!)).toEqual(pageNodes(pageSchema(nodes)))
    expect(exposed.hasChanges()).toBe(false)
  })
})
