// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App } from 'vue'
import type { Rule } from '@form-create/ant-design-vue'
import type { DragRule } from '@form-create/antd-designer'
import BusinessDesigner from '@/views/nocode/application/components/BusinessDesigner.vue'
import ResourceManager from '@/views/nocode/application/components/ResourceManager.vue'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'
import { pageSchema } from './page-schema'

const calls = vi.hoisted(() => ({
  load: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  guard: undefined as undefined | (() => boolean),
  forceApply: undefined as undefined | (() => void),
  synchronize: undefined as undefined | ((objectId: string) => Promise<PublishedObject>)
}))
vi.mock('@/nocode/form-designer-loader', () => ({ loadFormDesigner: calls.load }))
vi.mock('ant-design-vue', async importOriginal => ({
  ...(await importOriginal<typeof import('ant-design-vue')>()),
  Modal: { confirm: calls.confirm },
  message: { success: calls.success, warning: vi.fn(), info: vi.fn() }
}))
vi.mock('@/nocode/unsaved', () => ({
  useUnsavedNavigation: (guard: () => boolean) => {
    calls.guard = guard
  }
}))
vi.mock('@/components/InfraUpload.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionPresentationEditor.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/nocode/application/components/FieldBehaviorEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/DetailFormSettings.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RelatedFormSettings.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/DataViewSettings.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConfigEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/PageFilterConfig.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ViewQueryEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FormObjectVersionNotice.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      props: ['synchronize'],
      setup(props) {
        calls.synchronize = props.synchronize
        return () => null
      }
    })
  }
})
vi.mock('@/components/ucp-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', [
            slots.actions?.(),
            ...props.dataSource.map((record: ApplicationResource) =>
              slots.bodyCell?.({ column: { key: 'action' }, record })
            )
          ])
    })
  }
})

const registered = vi.fn(),
  initialized = vi.fn(),
  checkpoint = vi.fn()
let failInitialize = false
const Engine = Object.assign(
  defineComponent({
    props: ['config', 'height'],
    setup(_, { expose }) {
      const rules = ref<Rule[]>([])
      expose({
        addMenu: vi.fn(),
        addComponent: (rule: DragRule) => registered(rule),
        setRule: (next: Rule[]) => {
          if (failInitialize) throw new Error('已有字段暂不可用')
          initialized(next)
          rules.value = next
        },
        getRule: () => rules.value,
        addOperationRecord: checkpoint,
        mergeOptions: vi.fn(),
        triggerActive: vi.fn()
      })
      return () => h('div', { 'data-engine': true }, rules.value.map(rule => rule.name).join(','))
    }
  }),
  { component: vi.fn() }
)
const engineComponent = Engine
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const fields = [{ type: 'input', field: 'name', title: '名称', props: {} }]
const formNodes = () => [uiNode(NodeKind.FIELD, { id: 'existing-field', fieldId: 'name' })]
const objects = Object.fromEntries(
  ['orders', 'customers'].map(objectId => [
    objectId,
    {
      objectId,
      versionNo: 1,
      checksum: 'v1',
      definition: {
        objectId,
        objectName: objectId,
        fields: [{ id: 'name', key: 'name', code: 'name', name: '名称', type: FieldType.TEXT }],
        fieldOptions: {},
        relations: [],
        details: []
      }
    }
  ])
) as unknown as Record<string, PublishedObject>
function resource(kind: ResourceKind): ApplicationResource {
  return {
    id: 'resource',
    kind,
    name: '已有设计',
    code: 'existing_design',
    config:
      kind === ResourceKind.PAGE
        ? {
            nodes: [uiNode(NodeKind.TEXT, { id: 'text', text: '已有页面' })],
            filters: [],
            contextObjectId: null,
            protocolVersion: 2
          }
        : { objectId: 'orders', nodes: formNodes(), detailIds: [], options: { layout: 'vertical', submitText: '保存' } }
  }
}
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 16; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string) {
  const element = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === label)
  if (!element) throw new Error(`未找到按钮 ${label}`)
  return element
}
function start(render: () => ReturnType<typeof h> | null) {
  app = createApp(render)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.extra?.()])
  })
  for (const name of [
    'ASpace',
    'ASpin',
    'AForm',
    'AFormItem',
    'ATag',
    'ARow',
    'ACol',
    'ADivider',
    'APopconfirm',
    'ACheckbox',
    'ACheckboxGroup',
    'ADropdown',
    'AMenu',
    'AMenuItem',
    'ADrawer',
    'ACollapse',
    'ACollapsePanel',
    'AEmpty',
    'ATextarea',
    'ASlider',
    'AInputNumber',
    'ARadio',
    'ARadioGroup',
    'ARadioButton',
    'ASwitch',
    'ATabs',
    'ATabPane'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.())
    })
  )
  app.component(
    'AAlert',
    defineComponent({ props: ['message'], setup: props => () => h('aside', { role: 'alert' }, props.message) })
  )
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['options', 'value'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
              }
            },
            (props.options || []).map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.component(
    'ASegmented',
    defineComponent({
      props: ['options'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            props.options.map((option: { value: string; label: string }) =>
              h('button', { onClick: () => emit('update:value', option.value) }, option.label)
            )
          )
    })
  )
  app.component(
    'AModal',
    defineComponent({
      props: ['open', 'footer', 'okButtonProps'],
      emits: ['ok', 'cancel'],
      setup:
        (props, { emit, slots }) =>
        () => {
          if (!props.open) return null
          calls.forceApply = () => emit('ok')
          return h('section', { 'data-modal': true }, [
            slots.title?.(),
            slots.default?.(),
            h('button', { onClick: () => emit('cancel') }, '关闭配置'),
            props.footer === null
              ? null
              : h('button', { disabled: props.okButtonProps?.disabled, onClick: () => emit('ok') }, '应用到草稿')
          ])
        }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}
async function mountResource(kind: ResourceKind = ResourceKind.FORM) {
  const state = reactive({ resources: [resource(kind)] }),
    changed = vi.fn(),
    synchronizeObject = vi.fn(async (id: string) => objects[id]!)
  start(() =>
    h(ResourceManager, {
      objects,
      readOnly: false,
      applicationId: 'app',
      synchronizeObject,
      modelValue: state.resources,
      'onUpdate:modelValue': (value: ApplicationResource[]) => {
        state.resources = value
      },
      onChange: changed
    })
  )
  button(kind === ResourceKind.PAGE ? '页面与视图' : '业务表单').click()
  await flush()
  button(kind === ResourceKind.PAGE ? '设计' : '配置').click()
  await flush()
  return { state, changed, synchronizeObject }
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.load.mockReset()
  calls.forceApply = undefined
  calls.guard = undefined
  calls.synchronize = undefined
  failInitialize = false
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('设计器按需加载与应用门禁', () => {
  it('隐藏时不请求引擎；加载中 API 拒绝空结果，完成注册和快照后使用最新 props', async () => {
    const pending = deferred<typeof engineComponent>(),
      factory = vi.fn(() => pending.promise)
    calls.load.mockImplementation(factory)
    const state = reactive({ visible: false, nodes: formNodes() }),
      api = ref<InstanceType<typeof BusinessDesigner>>()
    const ready = vi.fn()
    start(() =>
      state.visible
        ? h(BusinessDesigner, { ref: api, nodes: state.nodes, fields, resources: [], form: true, onReady: ready })
        : null
    )
    await flush()
    expect(factory).not.toHaveBeenCalled()
    state.visible = true
    await flush()
    await vi.waitFor(() => expect(factory).toHaveBeenCalledOnce())
    expect(host.textContent).toContain('正在加载表单设计器')
    expect(api.value?.isReady()).toBe(false)
    expect(() => api.value?.getNodes()).toThrow('尚未就绪')
    expect(() => api.value?.validate()).toThrow('尚未就绪')
    expect(() => api.value?.reset([])).toThrow('尚未就绪')
    expect(api.value?.hasChanges()).toBe(false)
    state.nodes = [uiNode(NodeKind.FIELD, { id: 'latest-field', fieldId: 'name' })]
    pending.resolve(engineComponent)
    await flush()
    expect(api.value?.isReady()).toBe(true)
    expect(api.value?.getNodes()[0]?.id).toBe('latest-field')
    expect(registered).toHaveBeenCalledWith(expect.objectContaining({ name: 'field_name' }))
    expect(checkpoint).toHaveBeenCalledOnce()
    expect(ready).toHaveBeenCalledOnce()
    expect(api.value?.hasChanges()).toBe(false)
  })
  it('关闭旧实例后迟到的引擎只初始化重新打开的设计', async () => {
    const pending = deferred<typeof engineComponent>(),
      factory = vi.fn(() => pending.promise)
    calls.load.mockImplementation(factory)
    const state = reactive({ visible: true, key: 1, nodes: formNodes() }),
      ready = vi.fn()
    start(() =>
      state.visible
        ? h(BusinessDesigner, { key: state.key, nodes: state.nodes, fields, form: true, resources: [], onReady: ready })
        : null
    )
    await flush()
    await vi.waitFor(() => expect(factory).toHaveBeenCalledOnce())
    state.visible = false
    await flush()
    state.nodes = [uiNode(NodeKind.FIELD, { id: 'new-session', fieldId: 'name' })]
    state.key++
    state.visible = true
    await flush()
    pending.resolve(engineComponent)
    await flush()
    expect(initialized).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-engine]')?.textContent).toBe('new-session')
    expect(ready).toHaveBeenCalledOnce()
  })
  it('资源加载失败时保留原表单，不提供假重试且禁止绕过禁用按钮应用', async () => {
    const pending = deferred<typeof engineComponent>(),
      factory = vi.fn(() => pending.promise)
    calls.load.mockImplementation(factory)
    const { state, changed } = await mountResource(),
      original = JSON.stringify(state.resources)
    expect(button('应用到草稿').disabled).toBe(true)
    await vi.waitFor(() => expect(factory).toHaveBeenCalledOnce())
    calls.forceApply?.()
    await flush()
    expect(host.textContent).toContain('尚未就绪')
    pending.reject(new Error('chunk unavailable'))
    await flush()
    expect(host.textContent).toContain('表单设计器资源加载失败，请先保存未保存的内容，再刷新页面。')
    expect(host.textContent).not.toContain('重新加载')
    expect(button('应用到草稿').disabled).toBe(true)
    calls.forceApply?.()
    await flush()
    expect(JSON.stringify(state.resources)).toBe(original)
    expect(changed).not.toHaveBeenCalled()
    button('关闭配置').click()
    await flush()
    expect(calls.confirm).not.toHaveBeenCalled()
    expect(host.querySelector('[data-modal]')).toBeNull()
  })
  it('初始化失败同样阻止空设计应用，保留尚未应用的名称并通过原关闭确认保护', async () => {
    failInitialize = true
    calls.load.mockResolvedValue(engineComponent)
    const { state, changed } = await mountResource()
    await flush()
    expect(host.textContent).toContain('已有字段暂不可用')
    expect(button('应用到草稿').disabled).toBe(true)
    const name = host.querySelector<HTMLInputElement>('input[aria-label="资源名称"]')
    if (!name) throw new Error('未找到名称')
    name.value = '尚未应用'
    name.dispatchEvent(new Event('input'))
    await flush()
    expect(calls.guard?.()).toBe(true)
    button('关闭配置').click()
    await flush()
    expect(calls.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '放弃尚未应用到草稿的修改？' }))
    expect(host.querySelector('[data-modal]')).not.toBeNull()
    calls.confirm.mock.calls.at(-1)?.[0].onOk()
    await flush()
    expect(state.resources[0]?.name).toBe('已有设计')
    expect(state.resources[0]?.config.nodes).toEqual(formNodes())
    expect(changed).not.toHaveBeenCalled()
  })
  it('加载期间同步版本保留节点，切对象需要确认，旧会话不可应用新对象的空配置', async () => {
    const pending = deferred<typeof engineComponent>(),
      factory = vi.fn(() => pending.promise)
    calls.load.mockImplementation(factory)
    const { state, changed, synchronizeObject } = await mountResource()
    await vi.waitFor(() => expect(factory).toHaveBeenCalledOnce())
    await calls.synchronize?.('orders')
    await flush()
    expect(synchronizeObject).toHaveBeenCalledWith('orders')
    expect(state.resources[0]?.config.nodes).toEqual(formNodes())
    const objectSelect = host.querySelector<HTMLSelectElement>('select[aria-label="数据对象"]')
    if (!objectSelect) throw new Error('未找到对象选择')
    objectSelect.value = 'customers'
    objectSelect.dispatchEvent(new Event('change'))
    await flush()
    expect(calls.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '更换数据对象并重建表单？' }))
    expect(button('应用到草稿').disabled).toBe(true)
    calls.confirm.mock.calls.at(-1)?.[0].onOk()
    await flush()
    calls.forceApply?.()
    await flush()
    expect(changed).not.toHaveBeenCalled()
    pending.resolve(engineComponent)
    await flush()
    expect(initialized).toHaveBeenCalledOnce()
    expect(button('应用到草稿').disabled).toBe(false)
    button('应用到草稿').click()
    await flush()
    expect(changed).toHaveBeenCalledOnce()
    expect(state.resources[0]?.config.objectId).toBe('customers')
    expect((state.resources[0]?.config.nodes as UiNode[]).map(node => node.fieldId)).toEqual(['name'])
  })
  it('页面 iframe 初始未就绪和 ERROR 时都禁用应用，保留已有节点', async () => {
    const factory = vi.fn(() => engineComponent)
    calls.load.mockImplementation(factory)
    const { state, changed } = await mountResource(ResourceKind.PAGE),
      original = JSON.stringify(state.resources)
    expect(factory).not.toHaveBeenCalled()
    expect(button('应用到草稿').disabled).toBe(true)
    calls.forceApply?.()
    await flush()
    expect(changed).not.toHaveBeenCalled()
    const source = host.querySelector('iframe')?.contentWindow
    if (!source) throw new Error('未找到 iframe')
    const message = async (type: string, data = {}) => {
      window.dispatchEvent(
        new MessageEvent('message', {
          source,
          origin: location.origin,
          data: { channel: 'os-page-designer', type, ...data }
        })
      )
      await flush()
    }
    await message('READY')
    expect(button('应用到草稿').disabled).toBe(false)
    await message('CHANGE', { schema: pageSchema(state.resources[0]?.config.nodes as UiNode[]) })
    await message('ERROR', { message: '模拟画布错误' })
    expect(button('应用到草稿').disabled).toBe(true)
    calls.forceApply?.()
    await flush()
    expect(changed).not.toHaveBeenCalled()
    expect(JSON.stringify(state.resources)).toBe(original)
  })
})
