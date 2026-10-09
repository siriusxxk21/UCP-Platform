// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
import BusinessDesigner from '@/views/nocode/application/components/BusinessDesigner.vue'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import type { PublishedDefinition } from '@/types/nocode/application'

vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionPresentationEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      inheritAttrs: false,
      setup:
        (_, { attrs }) =>
        () =>
          h('span', {
            'data-selection-context': JSON.stringify({
              objectId: (attrs.definition as PublishedDefinition).objectId,
              resources: attrs.resources,
              sourceGroups: attrs.sourceGroups || attrs['source-groups']
            })
          })
    })
  }
})
vi.mock('@/views/nocode/application/components/FieldBehaviorEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: { render: () => null } }))

// 首个用例挂两份真实设计器并承担引擎首次加载，耗时本身就在 5 秒上下（dev 基线实测 4985ms 通过、5732ms 超时）。
// 默认 5 秒超时在负载高的机器上会偶发变红；这里只放宽超时，不改任何断言。
vi.setConfig({ testTimeout: 20_000 })

const mounted: { app: App; host: HTMLDivElement }[] = []
const flush = async () => {
  await vi.dynamicImportSettled()
  for (let i = 0; i < 12; i++) await nextTick()
}
async function mountDesigner(prefix: string) {
  const columns = ['甲', '乙'].map((name, i) =>
    uiNode(NodeKind.FIELD, {
      id: `${prefix}-column-${i}`,
      fieldId: `${prefix}_column_${i}`,
      presentation: { label: `${prefix}${name}` }
    })
  )
  const definition = {
    objectId: prefix,
    fields: [{ id: `${prefix}_name`, name: `${prefix}名称`, type: 'TEXT' }],
    fieldOptions: {},
    relations: [
      { fieldId: `${prefix}_column_0`, sourceDetailId: 'items', kind: 'REFERENCE', targetObjectId: 'target' }
    ],
    details: [
      {
        id: 'items',
        name: `${prefix}明细`,
        state: 'ACTIVE',
        fields: columns.map(node => ({ id: node.fieldId, name: node.presentation?.label, type: 'TEXT' })),
        fieldOptions: {}
      }
    ]
  } as unknown as PublishedDefinition
  const resources = [
    { id: `${prefix}_view`, code: `${prefix}_view`, name: `${prefix}视图`, kind: 'VIEW' as const, config: {} }
  ]
  const app = createApp(BusinessDesigner, {
    nodes: [uiNode(NodeKind.FIELD, { id: `${prefix}-main`, fieldId: `${prefix}_name` })],
    fields: [{ type: 'input', field: `${prefix}_name`, title: `${prefix}名称`, props: {} }],
    definition,
    resources,
    form: true,
    detailIds: ['items'],
    detailNodes: { items: columns }
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  const designer = app.mount(host) as unknown as {
    getNodes: () => UiNode[]
    getDetailNodes: () => Record<string, UiNode[]>
    reset: (nodes: UiNode[]) => void
    hasChanges: () => boolean
  }
  mounted.push({ app, host })
  await flush()
  return { host, designer, columns, resources }
}
async function selectColumn(host: HTMLElement, label: string) {
  // 不能预先框选：第一次点任意列就必须打开该列，且真实鼠标不能被引擎遮罩挡住。
  const detailTool = host.querySelector('.internal-detail-design')?.closest('._fd-drag-tool')
  expect(detailTool?.querySelector(':scope > ._fd-drag-mask')).toBeNull()
  const button = host.querySelector<HTMLElement>(`[aria-label="${label}"]`)
  expect(button).not.toBeNull()
  button?.click()
  await flush()
  await vi.waitFor(() => expect(host.querySelector('.detail-column-properties')).not.toBeNull())
}
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
})
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  vi.unstubAllGlobals()
})

describe('内部明细引擎控件上下文', () => {
  it('两个设计器共享全局控件时，画布、主表来源和列配置仍隔离', async () => {
    const first = await mountDesigner('一号')
    const second = await mountDesigner('二号')
    // 第二个设计器完成注册后重新创建第一份画布，不能使用最后注册的实例闭包。
    first.designer.reset(first.designer.getNodes())
    await flush()
    await selectColumn(first.host, '配置一号明细的一号甲列')
    expect(first.host.querySelector('.detail-column-properties strong')?.textContent).toBe('一号明细 · 一号甲')
    const context = JSON.parse(
      first.host
        .querySelector('.detail-column-properties [data-selection-context]')
        ?.getAttribute('data-selection-context') || '{}'
    )
    expect(context).toEqual({
      objectId: '一号',
      resources: first.resources,
      sourceGroups: [
        { label: '本行字段', fieldIds: ['一号_column_0', '一号_column_1'] },
        { label: '主表字段', fieldIds: ['一号_name'] }
      ]
    })
    const nameInput = first.host.querySelector<HTMLInputElement>('.detail-column-properties .ant-input')
    if (!nameInput) throw new Error('未找到明细显示名称输入框')
    nameInput.value = '第一份修改'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(first.designer.getDetailNodes().items?.[0]?.presentation?.label).toBe('第一份修改')
    expect(second.designer.getDetailNodes().items).toEqual(second.columns)
    expect(second.designer.hasChanges()).toBe(false)
    await selectColumn(second.host, '配置二号明细的二号甲列')
    expect(second.host.querySelector('.detail-column-properties strong')?.textContent).toBe('二号明细 · 二号甲')
    expect(first.host.querySelector('.detail-column-properties strong')?.textContent).toBe('一号明细 · 一号甲')
  })

  it('右侧切换列后再点击原画布列，属性和画布选中状态同步', async () => {
    const { host } = await mountDesigner('选择')
    await selectColumn(host, '配置选择明细的选择甲列')
    const selector = host.querySelector<HTMLElement>('.detail-settings-compact .ant-select-selector')
    expect(selector).not.toBeNull()
    selector?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const option = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
      item => item.title === '选择乙'
    )
    expect(option).toBeDefined()
    option?.click()
    await flush()
    expect(host.querySelector('.detail-column-properties strong')?.textContent).toBe('选择明细 · 选择乙')
    const firstColumn = host.querySelector<HTMLElement>('[aria-label="配置选择明细的选择甲列"]')
    firstColumn?.click()
    await flush()
    expect(host.querySelector('.detail-column-properties strong')?.textContent).toBe('选择明细 · 选择甲')
  })

  it('首次点击非首列及示例区域直接切换配置，反复选列保持各列修改', async () => {
    const { host, designer } = await mountDesigner('直点')
    await selectColumn(host, '配置直点明细的直点乙列')
    expect(host.querySelector('.detail-column-properties strong')?.textContent).toBe('直点明细 · 直点乙')
    const nameInput = host.querySelector<HTMLInputElement>('.detail-column-properties .ant-input')
    if (!nameInput) throw new Error('未找到明细显示名称输入框')
    nameInput.value = '乙列修改'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    host.querySelector<HTMLElement>('[aria-label="配置直点明细的直点甲列"] span')?.click()
    await flush()
    expect(host.querySelector('.detail-column-properties strong')?.textContent).toBe('直点明细 · 直点甲')
    expect(host.querySelector('[aria-label="配置直点明细的直点甲列"]')?.getAttribute('aria-pressed')).toBe('true')
    await selectColumn(host, '配置直点明细的乙列修改列')
    expect(host.querySelector('.detail-column-properties strong')?.textContent).toBe('直点明细 · 直点乙')
    expect(designer.getDetailNodes().items?.[1]?.presentation?.label).toBe('乙列修改')
  })
})
