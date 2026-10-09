import { createRenderer, defineComponent, h, nextTick, type Component } from 'vue'

/** 在内存中执行真实 Vue 组件事件；控件替身只负责展示插槽和暴露原有事件。 */
export interface TestNode {
  type: string
  props: Record<string, any>
  children: TestNode[]
  parent: TestNode | null
  text: string
  style: Record<string, string>
  querySelector: () => null
  closest: () => null
}
const node = (type: string, text = ''): TestNode => ({
  type,
  text,
  style: {},
  props: {},
  children: [],
  parent: null,
  querySelector: () => null,
  closest: () => null
})
const renderer = createRenderer<TestNode, TestNode>({
  createElement: type => node(type),
  createText: text => node('#text', text),
  createComment: text => node('#comment', text),
  setText: (node, text) => {
    node.text = text
  },
  setElementText: (node, text) => {
    node.text = text
    node.children = []
  },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  patchProp: (node, key, _old, value) => {
    node.props[key] = value
  },
  insert(node, parent, anchor) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
  },
  remove(node) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = null
  }
})

export const modal = defineComponent({
  props: ['open', 'title'],
  setup(props, { slots, attrs }) {
    return () => (props.open ? h('modal', { ...attrs, title: props.title }, slots.formItems?.()) : null)
  }
})
export const table = defineComponent({
  props: ['dataSource', 'columns'],
  setup(props, { slots }) {
    return () =>
      h('table-page', [
        slots.actions?.(),
        ...(props.dataSource ?? []).map((record: any, index: number) =>
          h(
            'row',
            { record },
            (props.columns ?? []).map((column: any) => slots.bodyCell?.({ column, record, index }))
          )
        )
      ])
  }
})
export const empty = defineComponent({ setup: () => () => null })
export function mount(component: Component, props = {}) {
  const root = node('root')
  const app = renderer.createApp(component, props)
  const names = [
    'row',
    'col',
    'input',
    'input-number',
    'textarea',
    'select',
    'checkbox',
    'button',
    'tag',
    'tooltip',
    'alert',
    'form',
    'form-item',
    'space',
    'typography-text',
    'card',
    'tabs',
    'tab-pane',
    'collapse',
    'collapse-panel',
    'empty',
    'spin',
    'list',
    'list-item',
    'popconfirm',
    'divider',
    'descriptions',
    'descriptions-item',
    'switch',
    'radio-group',
    'radio-button'
  ]
  for (const name of names)
    app.component(
      `a-${name}`,
      defineComponent({
        inheritAttrs: false,
        setup(_, { attrs, slots }) {
          return () => h(`a-${name}`, attrs, [slots.default?.(), slots.action?.()])
        }
      })
    )
  app.config.warnHandler = message => {
    if (!message.includes('Failed to resolve component')) throw new Error(message)
  }
  app.mount(root)
  return { root, unmount: () => app.unmount() }
}
export async function flush() {
  for (let count = 0; count < 4; count++) {
    await Promise.resolve()
    await nextTick()
  }
}
export function findAll(root: TestNode, predicate: (node: TestNode) => boolean): TestNode[] {
  return [...(predicate(root) ? [root] : []), ...root.children.flatMap(child => findAll(child, predicate))]
}
export function find(root: TestNode, type: string, predicate: (node: TestNode) => boolean = () => true): TestNode {
  const results = findAll(root, node => node.type === type && predicate(node))
  if (results.length !== 1) throw new Error(`Expected one ${type}, found ${results.length}`)
  return results[0]!
}
export const text = (node: TestNode): string =>
  node.type === '#comment' ? '' : node.text + node.children.map(text).join('')
export function control(root: TestNode, label: string, type: string) {
  return find(
    find(root, 'a-form-item', n => n.props.label === label),
    type
  )
}
export async function event(node: TestNode, name: string, value?: unknown) {
  if (node.props.disabled) throw new Error(`Attempted ${name} on a disabled control`)
  if (typeof node.props[name] !== 'function') throw new Error(`Missing ${name} on ${node.type}`)
  await node.props[name](value)
  await flush()
}
