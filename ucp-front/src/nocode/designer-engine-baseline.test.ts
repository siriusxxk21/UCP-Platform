// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import Antd from 'ant-design-vue'
import BusinessDesigner from '@/views/nocode/application/components/BusinessDesigner.vue'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import { boundFields, nodesToRules, rulesToNodes } from './application-ui'
import type { PublishedDefinition } from '@/types/nocode/application'
import { internalDetailIds } from './form-detail-layout'

vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionPresentationEditor.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/nocode/application/components/FieldBehaviorEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: { render: () => null } }))

let app: App | undefined, host: HTMLDivElement
const fields = [
  { type: 'input', field: 'name', title: '名称', props: {} },
  { type: 'input', field: 'code', title: '编号', props: {} }
]
const originalNodes = () => [uiNode(NodeKind.FIELD, { id: 'name-node', fieldId: 'name' })]
const flush = async () => {
  await vi.dynamicImportSettled()
  for (let i = 0; i < 12; i++) await nextTick()
}
async function mount(readOnly = false, extra: Record<string, unknown> = {}) {
  const state = reactive({ nodes: originalNodes(), readOnly })
  const changed = vi.fn()
  app = createApp(BusinessDesigner, { ...state, fields, form: true, resources: [], onChange: changed, ...extra })
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  const designer = app.mount(host) as unknown as {
    getNodes: () => UiNode[]
    hasChanges: () => boolean
    validate: () => void
    reset: (nodes: UiNode[]) => void
    getDetailNodes: () => Record<string, UiNode[]>
  }
  await flush()
  return { designer, state, changed }
}
function click(selector: string) {
  const element = host.querySelector<HTMLElement>(selector)
  if (!element) throw new Error(`未找到控件 ${selector}`)
  element.click()
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

describe('真实表单引擎基线', () => {
  it('对象换版后的失效字段在嵌套画布保留占位，可定位删除并恢复合法表单', async () => {
    const definition = {
      objectId: 'ledger',
      fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
      fieldOptions: {},
      relations: [],
      details: []
    } as unknown as PublishedDefinition
    const missing = uiNode(NodeKind.FIELD, {
      id: 'old-summary-node',
      fieldId: 'old-summary-field',
      presentation: { label: '内容汇总', help: '旧字段说明', behavior: { clearWhenHidden: true } }
    })
    const nodes = [
      uiNode(NodeKind.TABS, {
        children: [
          uiNode(NodeKind.TAB, {
            text: '流水信息',
            children: [uiNode(NodeKind.CARD, { text: '旧版布局', children: [...originalNodes(), missing] })]
          })
        ]
      })
    ]
    const { designer } = await mount(false, { definition, fields: fields.slice(0, 1), nodes })
    expect(designer.getNodes()).toEqual(nodes)
    expect(designer.hasChanges()).toBe(false)
    const placeholder = host.querySelector<HTMLElement>('[data-unavailable-field-id="old-summary-field"]')
    expect(placeholder?.textContent).toContain('内容汇总（字段已不可用）')
    expect(placeholder?.closest('.ant-card')?.textContent).toContain('旧版布局')
    expect(() => designer.validate()).toThrow('内容汇总')
    await flush()
    const locate = host.querySelector<HTMLElement>('.design-issues button')
    expect(locate).not.toBeNull()
    // 引擎维护定位树有 300ms 防抖，等待树就绪后检查实际选中行为。
    await vi.waitFor(() => {
      locate?.click()
      expect(
        host.querySelector('[data-unavailable-field-id]')?.closest('._fd-drag-tool')?.classList.contains('active')
      ).toBe(true)
    })
    await flush()
    expect(designer.getNodes()).toEqual(nodes)
    const active = host.querySelector('[data-unavailable-field-id]')?.closest<HTMLElement>('._fd-drag-tool')
    const remove = active?.querySelector<HTMLElement>('._fd-drag-danger')
    expect(remove).not.toBeNull()
    remove?.click()
    await flush()
    expect(boundFields(designer.getNodes())).toEqual(['name'])
    expect(() => designer.validate()).not.toThrow()
    expect(() => nodesToRules(designer.getNodes(), fields.slice(0, 1))).not.toThrow()
    expect(host.querySelector('[data-unavailable-field-id]')).toBeNull()
    click('.icon-pre-step')
    await flush()
    expect(designer.getNodes()).toEqual(nodes)
    expect(designer.hasChanges()).toBe(false)
    expect(() => designer.validate()).toThrow('内容汇总')
  })
  it('旧明细进入画布后不标脏，点击列打开统一属性，新增另一明细支持撤销重做', async () => {
    const definition = {
      objectId: 'orders',
      fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
      fieldOptions: {},
      relations: [],
      details: ['items', 'fees'].map((id, i) => ({
        id,
        name: i ? '费用明细' : '商品明细',
        state: 'ACTIVE',
        fields: [{ id: id + '_name', name: '名称', type: 'TEXT' }],
        fieldOptions: {}
      }))
    } as unknown as PublishedDefinition
    const column = uiNode(NodeKind.FIELD, {
      id: 'items-column',
      fieldId: 'items_name',
      presentation: { label: '既有列名', help: '保留此规则' }
    })
    const { designer } = await mount(false, { definition, detailIds: ['items'], detailNodes: { items: [column] } })
    expect(designer.hasChanges()).toBe(false)
    expect(internalDetailIds(designer.getNodes())).toEqual(['items'])
    expect(designer.getDetailNodes().items).toEqual([column])
    click('[aria-label="配置商品明细的既有列名列"]')
    await flush()
    await vi.waitFor(() => expect(host.textContent).toContain('明细标题'))
    expect(host.textContent).toContain('商品明细 · 名称')
    const second = Array.from(host.querySelectorAll<HTMLElement>('._fc-l-item')).find(el =>
      el.textContent?.includes('费用明细')
    )
    expect(second).toBeDefined()
    second?.click()
    await flush()
    expect(internalDetailIds(designer.getNodes())).toEqual(['items', 'fees'])
    expect(designer.getDetailNodes().items).toEqual([column])
    click('.icon-pre-step')
    await flush()
    expect(internalDetailIds(designer.getNodes())).toEqual(['items'])
    expect(designer.hasChanges()).toBe(false)
    click('.icon-next-step')
    await flush()
    expect(internalDetailIds(designer.getNodes())).toEqual(['items', 'fees'])
    expect(designer.getDetailNodes().items).toEqual([column])
  })
  it('注册字段物料、导入设计并保持初始快照，添加字段后支持撤销和重做', async () => {
    const { designer, state, changed } = await mount()
    const original = rulesToNodes(nodesToRules(state.nodes, fields, true))
    expect(designer.getNodes()).toEqual(original)
    expect(designer.hasChanges()).toBe(false)
    expect(() => designer.validate()).not.toThrow()
    const item = Array.from(host.querySelectorAll<HTMLElement>('._fc-l-item')).find(element =>
      element.textContent?.includes('编号')
    )
    expect(item).toBeDefined()
    item?.click()
    await flush()
    expect(designer.getNodes().map(node => node.fieldId)).toEqual(['name', 'code'])
    expect(designer.hasChanges()).toBe(true)
    expect(changed).toHaveBeenCalled()
    click('.icon-pre-step')
    await flush()
    expect(designer.getNodes()).toEqual(original)
    expect(designer.hasChanges()).toBe(false)
    click('.icon-next-step')
    await flush()
    expect(designer.getNodes().map(node => node.fieldId)).toEqual(['name', 'code'])
    expect(state.nodes).toEqual(originalNodes())
  })
  it('只读引擎保留既有布局与预览，并隐藏快捷排版入口', async () => {
    const { designer, state } = await mount(true)
    const original = designer.getNodes()
    expect(designer.getNodes()).toEqual(original)
    expect(designer.hasChanges()).toBe(false)
    expect(state.nodes).toEqual(originalNodes())
    expect(host.querySelector('.business-designer.readonly')).not.toBeNull()
    expect(host.textContent).not.toContain('快捷排版')
    const preview = Array.from(host.querySelectorAll('button')).find(element => element.textContent?.includes('预览'))
    expect(preview?.disabled).toBe(false)
  })
})
