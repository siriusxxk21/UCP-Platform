// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, effectScope, h, nextTick, reactive, ref, type App, type Component } from 'vue'
import Antd from 'ant-design-vue'
import type { Rule } from '@form-create/ant-design-vue'
import type { DragRule } from '@form-create/antd-designer'
import BusinessDesigner from '@/views/nocode/application/components/BusinessDesigner.vue'
import SelectionPresentationEditor from '@/views/nocode/application/components/SelectionPresentationEditor.vue'
import {
  readableObjectsHint,
  readableObjectsSummary,
  useApplicationReadableObjects
} from './application-readable-objects'
import { formDesignIssues } from './form-design'
import { internalDetailDesignerKey } from './internal-detail-designer-context'
import { selectionPreviewKey } from './selection'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { ObjectReference, PublishedDefinition, PublishedObject, ReadableObject } from '@/types/nocode/application'

/**
 * 关联对象的隐式只读（契约第 15 章）在设计端的落点：
 * 一行只读小字的文案、拉取时机，以及「按关系查目标对象的字段」用得到隐式对象、「预览请求里的引用」用不到。
 */
const calls = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('@/nocode/form-designer-loader', () => ({ loadFormDesigner: calls.load }))
vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FieldBehaviorEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: { render: () => null } }))

function must<T>(value: T | null | undefined, what: string): T {
  if (value == null) throw new Error(`${what} 不存在`)
  return value
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const settle = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}

// 订单（已引用）有一个指向客户的关系字段；客户没有被应用引用，只是隐式可读。
const orders = {
  objectId: 'orders',
  objectName: '订单',
  fields: [
    { id: 'region', key: 'region', code: 'region', name: '地区', type: FieldType.TEXT },
    { id: 'customer', key: 'customer', code: 'customer', name: '客户', type: FieldType.REFERENCE }
  ],
  fieldOptions: { region: {}, customer: {} },
  relations: [
    {
      id: 'r-customer',
      name: '客户',
      code: 'customer',
      kind: RelationType.REFERENCE,
      fieldId: 'customer',
      targetObjectId: 'customers',
      sourceDetailId: null
    }
  ],
  details: [],
  settings: {}
} as unknown as PublishedDefinition
const customers = {
  objectId: 'customers',
  objectName: '客户',
  fields: [
    { id: 'name', key: 'name', code: 'name', name: '客户名称', type: FieldType.TEXT },
    { id: 'area', key: 'area', code: 'area', name: '所在地区', type: FieldType.TEXT },
    { id: 'credit', key: 'credit', code: 'credit', name: '信用额度', type: FieldType.MONEY }
  ],
  fieldOptions: { name: {}, area: {}, credit: {} },
  relations: [],
  details: [],
  settings: {}
} as unknown as PublishedDefinition
const object = (definition: PublishedDefinition, versionNo = 1): PublishedObject => ({
  objectId: definition.objectId,
  versionNo,
  checksum: `${definition.objectId}-v${versionNo}`,
  definition
})
const explicit = { orders: object(orders, 3) }
const implicit = { customers: object(customers, 5) }
const item = (patch: Partial<ReadableObject> = {}): ReadableObject => ({
  object: object(customers, 5),
  via: [{ fromObjectId: 'orders', fromObjectName: '订单', kind: 'RELATION', name: '客户' }],
  closed: false,
  ...patch
})
const linked = () => [
  uiNode(NodeKind.FIELD, { id: 'n-region', fieldId: 'region' }),
  uiNode(NodeKind.FIELD, {
    id: 'n-customer',
    fieldId: 'customer',
    presentation: { selection: { linkFieldId: 'region', linkTargetFieldId: 'area' } }
  })
]

let warn: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.clearAllMocks()
  warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
})
afterEach(() => warn.mockRestore())

describe('「因关联而可读取」一行的文案', () => {
  it('对象名、来源对象名与关系 / 规则名逐字；多个对象用顿号分隔', () => {
    expect(
      readableObjectsSummary([
        item(),
        item({
          object: object({ ...customers, objectId: 'currency', objectName: '币种' } as PublishedDefinition),
          via: [{ fromObjectId: 'contracts', fromObjectName: '合同', kind: 'PICK', name: '结算币种' }]
        })
      ])
    ).toBe('因关联而可读取（只读，不能为它们建列表或表单）：客户（订单的“客户”）、币种（合同的“结算币种”）')
  })
  it('数据管理员撤销了授权的对象带「（已关闭）」', () => {
    expect(readableObjectsSummary([item({ closed: true })])).toBe(
      '因关联而可读取（只读，不能为它们建列表或表单）：客户（订单的“客户”）（已关闭）'
    )
  })
  it('集合为空返回空串（整行不出现）', () => {
    expect(readableObjectsSummary([])).toBe('')
  })
  it('同一个对象有多个来源：去重后最多列 3 个，超出写“等”', () => {
    const via = ['客户', '客户', '客户备注', '客户等级', '客户额度'].map(name => ({
      fromObjectId: 'orders',
      fromObjectName: '订单',
      kind: 'LINKAGE' as const,
      name
    }))
    expect(readableObjectsSummary([item({ via })])).toBe(
      '因关联而可读取（只读，不能为它们建列表或表单）：客户（订单的“客户”、订单的“客户备注”、订单的“客户等级”等）'
    )
  })
  it('悬停说明逐字', () => {
    expect(readableObjectsHint).toBe(
      '这些对象没有被本应用引用。系统只读取它们来完成引用选择、名称显示和计算。要为它们建列表或表单，请点“引用对象”。'
    )
  })
})

describe('隐式可读对象的拉取', () => {
  function create(initial: ObjectReference[] = [{ objectId: 'orders', versionNo: 3, checksum: 'orders-v3' }]) {
    const api = { readableObjects: vi.fn(async (_body: unknown) => [item()]) }
    const state = reactive({ id: 'app', loaded: true, references: initial })
    const scope = effectScope()
    const module = must(
      scope.run(() =>
        useApplicationReadableObjects({
          api,
          applicationId: () => state.id,
          references: () => state.references,
          enabled: () => state.loaded
        })
      ),
      '隐式可读对象模块'
    )
    return { api, state, scope, module }
  }
  it('应用加载后按草稿里的引用拉取，结果给出对象表和一行文案', async () => {
    const { api, module } = create()
    await settle()
    expect(api.readableObjects).toHaveBeenCalledWith({
      applicationId: 'app',
      objects: [{ objectId: 'orders', versionNo: 3, checksum: 'orders-v3' }]
    })
    expect(Object.keys(module.objects.value)).toEqual(['customers'])
    expect(module.objects.value.customers?.definition.objectName).toBe('客户')
    expect(module.summary.value).toContain('客户（订单的“客户”）')
  })
  it('应用还没加载成功时不拉取', async () => {
    const { api, state, module } = create()
    api.readableObjects.mockClear()
    state.loaded = false
    await settle()
    expect(api.readableObjects).not.toHaveBeenCalled()
    expect(module.items.value).toEqual([])
  })
  it('草稿里加一个对象、移除一个对象、同步版本后都重新拉取', async () => {
    const { api, state } = create()
    await settle()
    expect(api.readableObjects).toHaveBeenCalledTimes(1)
    state.references = [...state.references, { objectId: 'contracts', versionNo: 1, checksum: 'c1' }]
    await settle()
    expect(api.readableObjects).toHaveBeenCalledTimes(2)
    state.references = state.references.slice(0, 1)
    await settle()
    expect(api.readableObjects).toHaveBeenCalledTimes(3)
    state.references = [{ objectId: 'orders', versionNo: 4, checksum: 'orders-v4' }]
    await settle()
    expect(api.readableObjects).toHaveBeenCalledTimes(4)
    expect(api.readableObjects).toHaveBeenLastCalledWith({
      applicationId: 'app',
      objects: [{ objectId: 'orders', versionNo: 4, checksum: 'orders-v4' }]
    })
  })
  it('引用没变（只是换了数组）不重复拉取', async () => {
    const { api, state } = create()
    await settle()
    state.references = state.references.map(reference => ({ ...reference }))
    await settle()
    expect(api.readableObjects).toHaveBeenCalledTimes(1)
  })
  it('草稿里没有任何引用时不发请求，集合为空', async () => {
    const { api, module } = create([])
    await settle()
    expect(api.readableObjects).not.toHaveBeenCalled()
    expect(module.summary.value).toBe('')
  })
  it('先发后到的旧结果不能覆盖新结果', async () => {
    const { api, state, module } = create()
    await settle()
    const old = deferred<ReadableObject[]>()
    api.readableObjects.mockReturnValueOnce(old.promise).mockResolvedValueOnce([])
    state.references = [{ objectId: 'orders', versionNo: 4, checksum: 'orders-v4' }]
    await settle()
    state.references = [{ objectId: 'orders', versionNo: 5, checksum: 'orders-v5' }]
    await settle()
    expect(module.items.value).toEqual([])
    old.resolve([item()])
    await settle()
    expect(module.items.value).toEqual([])
  })
  it('拉取失败：集合为空、不抛错，只留一条控制台警告', async () => {
    const { api, state, module } = create()
    await settle()
    expect(module.items.value).toHaveLength(1)
    api.readableObjects.mockRejectedValueOnce(new Error('404'))
    state.references = [{ objectId: 'orders', versionNo: 4, checksum: 'orders-v4' }]
    await settle()
    expect(module.items.value).toEqual([])
    expect(module.objects.value).toEqual({})
    expect(module.summary.value).toBe('')
    expect(warn).toHaveBeenCalledTimes(1)
  })
  it('拉取失败时不向外抛错（工作台其它功能不受影响）', async () => {
    const { api, module } = create()
    await settle()
    api.readableObjects.mockRejectedValueOnce(new Error('后端还没有这个接口'))
    await expect(module.refresh()).resolves.toBeUndefined()
    expect(module.items.value).toEqual([])
  })
  it('切换应用后忽略上一个应用仍在途的结果', async () => {
    const { api, state, module } = create()
    await settle()
    const old = deferred<ReadableObject[]>()
    api.readableObjects.mockReturnValueOnce(old.promise).mockResolvedValueOnce([])
    state.references = [{ objectId: 'orders', versionNo: 4, checksum: 'orders-v4' }]
    await settle()
    state.id = 'other'
    await settle()
    old.resolve([item()])
    await settle()
    expect(module.items.value).toEqual([])
  })
})

describe('表单应用前检查：引用字段的联动匹配字段', () => {
  const selectionIssues = (readable?: Record<string, PublishedObject>) =>
    formDesignIssues(linked(), orders, [], explicit, undefined, readable).filter(issue => issue.fieldId === 'customer')
  it('目标对象只是隐式可读：给了「已引用 ∪ 隐式」的对象表就不再报缺对象', () => {
    expect(selectionIssues({ ...implicit, ...explicit })).toEqual([])
  })
  it('只给已引用的对象表时照旧报「未选择关联对象中的匹配字段」（对照组）', () => {
    expect(selectionIssues().map(issue => issue.message)).toEqual(['“客户”的联动未选择关联对象中的匹配字段'])
  })
  it('隐式对象上类型不兼容的匹配字段照旧报不兼容', () => {
    const nodes = linked()
    Object.assign(must(nodes[1]?.presentation?.selection, '选择器设置'), { linkTargetFieldId: 'credit' })
    expect(
      formDesignIssues(nodes, orders, [], explicit, undefined, { ...implicit, ...explicit }).map(i => i.message)
    ).toEqual(['“客户”的联动来源与匹配字段类型或引用对象不兼容'])
  })
})

describe('选择器设置：关联对象中与来源相等的字段', () => {
  let app: App | undefined, host: HTMLDivElement
  const platform = {
    applications: { selectionOptions: vi.fn(async () => []), previewSelection: vi.fn() }
  } as unknown as NocodePlatform
  /** 保留真实 setup，不渲染模板。 */
  async function setup(objects: Record<string, PublishedObject>) {
    app = createApp(
      { ...(SelectionPresentationEditor as Component), render: () => null },
      {
        modelValue: { linkFieldId: 'region', linkTargetFieldId: 'area' },
        definition: orders,
        fieldId: 'customer',
        objects,
        resources: [],
        availableFieldIds: ['region', 'customer']
      }
    )
    app.use(Antd)
    app.provide(nocodePlatformKey, platform)
    host = document.createElement('div')
    document.body.append(host)
    const vm = app.mount(host)
    await settle()
    return (
      vm.$ as unknown as {
        setupState: { targetFields: Array<{ value: string; label: string }>; missingTarget: boolean; error: string }
      }
    ).setupState
  }
  afterEach(() => {
    app?.unmount()
    app = undefined
    host?.remove()
  })
  it('目标对象只是隐式可读：能列出它的兼容字段，不报「请先在应用中引用关系目标对象版本」', async () => {
    const state = await setup({ ...implicit, ...explicit })
    expect(state.targetFields).toEqual([
      { value: 'name', label: '客户名称' },
      { value: 'area', label: '所在地区' }
    ])
    expect(state.missingTarget).toBe(false)
    expect(state.error).toBe('')
  })
  it('对象表里没有目标对象时列不出字段并提示（对照组）', async () => {
    const state = await setup(explicit)
    expect(state.targetFields).toEqual([])
    expect(state.missingTarget).toBe(true)
    expect(state.error).toBe('请先在应用中引用关系目标对象版本')
  })
})

describe('表单设计器把哪张对象表交给谁', () => {
  let app: App | undefined, host: HTMLDivElement
  const registered = vi.fn()
  const Engine = Object.assign(
    defineComponent({
      props: ['config', 'height'],
      setup(_, { expose }) {
        const rules = ref<Rule[]>([])
        expose({
          addMenu: vi.fn(),
          addComponent: (rule: DragRule) => registered(rule),
          setRule: (next: Rule[]) => {
            rules.value = next
          },
          getRule: () => rules.value,
          addOperationRecord: vi.fn(),
          mergeOptions: vi.fn(),
          triggerActive: vi.fn()
        })
        return () => h('div')
      }
    }),
    { component: vi.fn() }
  )
  interface PropItem {
    type?: string
    props?: Record<string, unknown>
  }
  interface Instance {
    provides: Record<symbol, unknown>
    setupState: { issues: Array<{ fieldId?: string; message: string }> }
  }
  const fields = orders.fields.map(f => ({ type: 'input', field: f.id, title: f.name, props: {} }))
  async function mount(props: Record<string, unknown>) {
    calls.load.mockResolvedValue(Engine)
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() }))
    )
    app = createApp(BusinessDesigner, {
      nodes: linked(),
      fields,
      resources: [],
      form: true,
      definition: orders,
      applicationId: 'app',
      ...props
    })
    app.use(Antd)
    app.provide(nocodePlatformKey, {
      applications: { selectionOptions: vi.fn(async () => []), previewSelection: vi.fn() }
    } as unknown as NocodePlatform)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    for (let i = 0; i < 4; i++) await settle()
    await vi.waitFor(() => expect(registered).toHaveBeenCalledWith(expect.objectContaining({ name: 'field_customer' })))
    return (app as unknown as { _instance: Instance })._instance
  }
  const selectionProps = () => {
    const rule = must(
      registered.mock.calls.map(([r]) => r as DragRule).find(r => r.name === 'field_customer'),
      '客户字段物料'
    )
    const items = (rule.props as unknown as () => PropItem[])()
    return must(items.find(i => i.type === 'nocodeSelectionPresentation')?.props, '选择器设置')
  }
  beforeEach(() => registered.mockClear())
  afterEach(() => {
    app?.unmount()
    app = undefined
    host?.remove()
    vi.unstubAllGlobals()
  })
  it('选择器设置、内部明细列设置、应用前检查：用「已引用 ∪ 因关联而可读取」', async () => {
    const instance = await mount({ objects: explicit, readableObjects: implicit })
    expect(Object.keys(selectionProps().objects as object).sort()).toEqual(['customers', 'orders'])
    const detail = instance.provides[internalDetailDesignerKey as symbol] as {
      objects: { value: Record<string, unknown> }
    }
    expect(Object.keys(detail.objects.value).sort()).toEqual(['customers', 'orders'])
    expect(instance.setupState.issues.filter(issue => issue.fieldId === 'customer')).toEqual([])
  })
  it('边界：预览请求里的引用清单只有已引用的对象（隐式对象没有固定版本，不能当引用发出去）', async () => {
    const instance = await mount({ objects: explicit, readableObjects: implicit })
    const preview = instance.provides[selectionPreviewKey as symbol] as { value: { objects: ObjectReference[] } }
    expect(preview.value.objects).toEqual([{ objectId: 'orders', versionNo: 3, checksum: 'orders-v3' }])
    // 表单自己的对象版本仍取已引用的那一份。
    expect(selectionProps().versionNo).toBe(3)
  })
  it('同一个对象既被引用又在隐式表里时，以已引用的固定版本为准', async () => {
    const stale = { orders: object({ ...orders, objectName: '订单（最新发布版）' } as PublishedDefinition, 9) }
    await mount({ objects: explicit, readableObjects: { ...implicit, ...stale } })
    const objects = selectionProps().objects as Record<string, PublishedObject>
    expect(objects.orders?.versionNo).toBe(3)
  })
  it('没有隐式对象表时与原来一样：目标对象没被引用就报缺对象（对照组）', async () => {
    const instance = await mount({ objects: explicit })
    expect(Object.keys(selectionProps().objects as object)).toEqual(['orders'])
    expect(instance.setupState.issues.filter(issue => issue.fieldId === 'customer').map(i => i.message)).toEqual([
      '“客户”的联动未选择关联对象中的匹配字段'
    ])
  })
})
