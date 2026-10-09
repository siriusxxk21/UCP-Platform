// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import type { Rule } from '@form-create/ant-design-vue'
import type { DragRule } from '@form-create/antd-designer'
import BusinessDesigner from '@/views/nocode/application/components/BusinessDesigner.vue'
import SelectionPresentationEditor from '@/views/nocode/application/components/SelectionPresentationEditor.vue'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import { objectRuleHint } from './object-rule-hint'
import { selectionPreviewKey } from './selection'
import { createApplicationApi } from '@/api/nocode/application'
import type { NocodeHttpClient } from '@/api/nocode/object'

/**
 * 关联带入已迁到数据对象·数据联动（实施设计稿 9.4、10.2）：表单设计器不再提供「关联带入」入口，
 * 选项类字段不再有「本表单默认值」，对象上配了规则的字段只读提示并给跳转。
 */
const calls = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('@/nocode/form-designer-loader', () => ({ loadFormDesigner: calls.load }))
vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FieldBehaviorEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: { render: () => null } }))

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

const recordCondition = { fieldId: '$record', operator: 'eq', valueSource: 'FORM_FIELD' as const, formFieldId: 'flow' }
const linkage = (readOnly: boolean) => ({
  sourceObjectId: 'flows',
  conditions: [recordCondition],
  valueFieldId: 'flow-amount',
  multiRow: 'ERROR' as const,
  readOnly
})
const definition = {
  objectId: 'vouchers',
  objectName: '凭证',
  fields: [
    { id: 'amount', key: 'amount', code: 'amount', name: '入金', type: FieldType.MONEY },
    { id: 'memo', key: 'memo', code: 'memo', name: '摘要', type: FieldType.TEXT },
    { id: 'status', key: 'status', code: 'status', name: '状态', type: FieldType.SELECT },
    { id: 'owner', key: 'owner', code: 'owner', name: '经办人', type: FieldType.USER }
  ],
  fieldOptions: {
    amount: { rules: { linkage: linkage(true) } },
    memo: {},
    status: {
      options: [{ code: 'A', label: '甲', disabled: false }],
      rules: { linkage: linkage(false) }
    },
    owner: {}
  },
  relations: [],
  details: [],
  // 表单设计器按对象设置判断字段是否为业务文件字段。
  settings: {}
} as unknown as PublishedDefinition
const fieldIds = ['amount', 'memo', 'status', 'owner']
const fields = definition.fields.map(f => ({ type: 'input', field: f.id, title: f.name, props: {} }))
const platform = {
  applications: { selectionOptions: vi.fn(async () => []), previewSelection: vi.fn() }
} as unknown as NocodePlatform

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 16; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount(component: Parameters<typeof createApp>[0], props: Record<string, unknown>, applicationId?: string) {
  app = createApp(component, props)
  app.use(Antd)
  app.provide(nocodePlatformKey, platform)
  if (applicationId) {
    const context = { applicationId, objects: [], form: { objectId: 'vouchers', nodes: [], detailIds: [] } }
    app.provide(selectionPreviewKey, computed(() => context) as never)
  }
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}
interface PropItem {
  type?: string
  title?: string
  props?: Record<string, unknown>
  children?: unknown[]
}
function propsOf(fieldId: string): PropItem[] {
  const rule = registered.mock.calls.map(([r]) => r as DragRule).find(r => r.name === 'field_' + fieldId)
  if (!rule) throw new Error('字段物料未注册：' + fieldId)
  return (rule.props as unknown as () => PropItem[])()
}
const text = (item: PropItem): string =>
  (item.children || []).map(c => (typeof c === 'string' ? c : text(c as PropItem))).join('')

beforeEach(() => {
  vi.clearAllMocks()
  calls.load.mockResolvedValue(Engine)
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() }))
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.unstubAllGlobals()
})

describe('表单设计器撤掉关联带入入口', () => {
  it('不注册关联带入组件，任何字段的属性面板都没有「关联带入」', async () => {
    mount(BusinessDesigner, {
      nodes: [uiNode(NodeKind.FIELD, { id: 'n1', fieldId: 'memo' })],
      fields,
      resources: [],
      form: true,
      definition,
      objects: {}
    })
    await flush()
    await vi.waitFor(() => expect(registered).toHaveBeenCalledWith(expect.objectContaining({ name: 'field_memo' })))
    const componentNames = Engine.component.mock.calls.map(([name]) => name)
    expect(componentNames).toContain('nocodeSelectionPresentation')
    expect(componentNames).not.toContain('nocodeFormFill')
    for (const id of fieldIds) {
      const items = propsOf(id)
      expect(items.map(i => i.type)).not.toContain('nocodeFormFill')
      expect(items.map(i => i.title)).not.toContain('关联带入')
    }
  })

  it('对象上配了数据联动的非选择字段在属性面板提示一行并给跳转；选择类字段交给选择器设置提示', async () => {
    mount(BusinessDesigner, {
      nodes: [uiNode(NodeKind.FIELD, { id: 'n1', fieldId: 'memo' })],
      fields,
      resources: [],
      form: true,
      definition,
      objects: {}
    })
    await flush()
    await vi.waitFor(() => expect(registered).toHaveBeenCalledWith(expect.objectContaining({ name: 'field_amount' })))
    const hints = (id: string) => propsOf(id).filter(i => text(i).includes('此字段在数据对象上配置了'))
    expect(hints('amount').map(text)).toEqual([
      '此字段在数据对象上配置了数据联动（只读：是），修改请到数据对象 去数据对象修改'
    ])
    const link = hints('amount')[0]?.children?.[1] as PropItem | undefined
    expect(link?.props).toEqual(
      expect.objectContaining({ href: '/nocode/object/editor?id=vouchers', target: '_blank' })
    )
    expect(hints('memo')).toEqual([])
    expect(hints('status')).toEqual([])
  })
})

describe('选择器设置：选项类不再有表单默认值，对象规则只读提示', () => {
  it('选项类字段不显示「本表单默认值」，并提示对象上的数据联动', async () => {
    mount(SelectionPresentationEditor, { definition, fieldId: 'status', modelValue: {} })
    await flush()
    expect(host.textContent).not.toContain('本表单默认值')
    expect(host.textContent).toContain('此字段在数据对象上配置了数据联动（只读：否），修改请到数据对象')
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/nocode/object/editor?id=vouchers')
  })

  it('非选项类的目录字段仍可设置表单默认值，没有规则时不提示', async () => {
    mount(SelectionPresentationEditor, { definition, fieldId: 'owner', modelValue: {} })
    await flush()
    expect(host.textContent).toContain('本表单默认值')
    expect(host.textContent).not.toContain('此字段在数据对象上配置了')
  })

  it('提示文案覆盖引用筛选，没有规则或规则为空时不提示', () => {
    const withReference = {
      ...definition,
      fieldOptions: {
        ...definition.fieldOptions,
        memo: { rules: { reference: { labelFieldId: null, filter: [recordCondition] } } }
      }
    } as unknown as PublishedDefinition
    expect(objectRuleHint(withReference, 'memo')).toBe('此字段在数据对象上配置了引用筛选，修改请到数据对象')
    expect(objectRuleHint(definition, 'memo')).toBeNull()
    expect(objectRuleHint(undefined, 'memo')).toBeNull()
  })
})

describe('应用设计器的选项预览带上当前应用（路 B：挑取值按应用草稿固定的对象版本取候选）', () => {
  it('接口请求带 applicationId 参数', async () => {
    const get = vi.fn(async () => [])
    const api = createApplicationApi({ get } as unknown as NocodeHttpClient)
    await api.selectionOptions('vouchers', 'owner', 'x', 3, 'app-1')
    expect(get).toHaveBeenCalledWith('/nocode/application/selection-options', {
      params: { id: 'vouchers', fieldId: 'owner', search: 'x', versionNo: 3, applicationId: 'app-1' }
    })
  })

  it('选择器设置读取候选时带上正在设计的应用', async () => {
    mount(SelectionPresentationEditor, { definition, fieldId: 'owner', modelValue: {}, versionNo: 3 }, 'app-1')
    await flush()
    expect(platform.applications.selectionOptions).toHaveBeenCalledWith('vouchers', 'owner', undefined, 3, 'app-1')
  })
})
