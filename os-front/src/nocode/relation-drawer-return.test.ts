// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, ref, type App, type Component, type ComponentPublicInstance } from 'vue'
import Antd, { Modal } from 'ant-design-vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { relationFieldRows } from './relation-editing'

/**
 * 业务方 2026-09-29：「配置完以后，退回到上一层的配置界面，现在点保存以后，直接上一个配置的界面就直接关闭了。」
 * 字段抽屉进入对象关系时抽屉保持打开；关系保存后经 focusRelation 回到该关系引用字段的抽屉；
 * 未保存关系的占位行点「配置」给出「请先保存」提示，不悄悄弹关系窗口。
 */
const api = vi.hoisted(() => ({ objects: vi.fn(), design: vi.fn(), version: vi.fn() }))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => api }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: vi.fn(async () => []) }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: vi.fn(async () => []) }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h: render } = await import('vue')
  return {
    default: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open ? render('div', { class: 'drawer-stub' }, slots.formItems?.()) : null
    })
  }
})
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: { render: () => null } }))

const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function field(type: string, id: string, name: string, patch: Partial<ObjectField> = {}): ObjectField {
  return { ...newField(0, name), key: id, id, code: id, type: type as ObjectField['type'], ...patch }
}
const options = (patch: Partial<FieldOptions> = {}): FieldOptions => ({ ...defaultFieldOptions(), ...patch })
const relation = (patch: Partial<ObjectRelation> = {}): ObjectRelation => ({
  id: null,
  code: 'customer',
  name: '客户',
  kind: RelationType.REFERENCE,
  targetObjectId: 'customers',
  fieldId: null,
  targetFieldId: null,
  required: false,
  onDelete: 'RESTRICT',
  ...patch
})
async function mount(props: Record<string, unknown>) {
  const instance = ref<ComponentPublicInstance>()
  const events = { relation: vi.fn(), configure: vi.fn(), focused: vi.fn() }
  const reactiveProps = reactive({ ...props })
  const app = createApp({
    setup: () => () =>
      h(FieldDesigner as Component, {
        ref: instance,
        ...reactiveProps,
        onRelation: events.relation,
        onConfigureRelation: events.configure,
        onRelationFocused: events.focused
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  const state = (instance.value!.$ as unknown as { setupState: Record<string, any> }).setupState
  return { host, state, events, props: reactiveProps }
}

beforeEach(() => {
  vi.clearAllMocks()
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
  api.objects.mockResolvedValue({ list: [], total: 0 })
  api.design.mockResolvedValue({ publishedVersion: 1, draft: { objectName: '客户', objectCode: 'kh' } })
  api.version.mockResolvedValue({ definition: { fields: [], fieldOptions: {}, relations: [] } })
})
afterEach(async () => {
  await new Promise(resolve => setTimeout(resolve, 50))
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-modal-root').forEach(node => node.remove())
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('字段抽屉进入对象关系后回到抽屉', () => {
  it('抽屉内「改为对象关系」：抽屉保持打开，以 drawer 来源请求关系窗口', async () => {
    const pick = field(FieldType.SELECT, 'pick', '客户', { id: null })
    const { host, state, events } = await mount({ modelValue: [pick], options: { pick: options() } })
    state.show(pick)
    await flush()
    state.configureRelation()
    await flush()
    expect(events.relation).toHaveBeenCalledWith(expect.objectContaining({ key: 'pick' }), 'drawer')
    expect(state.open).toBe(true)
    expect(host.querySelector('.drawer-stub')).not.toBeNull()
  })

  it('关系保存后 focusRelation 指向该关系：打开生成的引用字段抽屉，显示显示名字段与引用筛选', async () => {
    const pick = field(FieldType.SELECT, 'pick', '客户', { id: null })
    const { host, state, events, props } = await mount({
      modelValue: [pick],
      options: { pick: options() },
      relations: []
    })
    state.show(pick)
    await flush()
    state.configureRelation()
    expect(events.relation).toHaveBeenCalledWith(expect.objectContaining({ key: 'pick' }), 'drawer')
    // 模拟对象编辑器保存成功后回填：选择字段被替换为关系生成的引用列。
    const generated = field(FieldType.REFERENCE, 'customer_id', '客户')
    props.modelValue = [generated]
    props.options = { customer_id: options({ generated: true }) }
    props.relations = [relation({ id: 'rel-1', fieldId: 'customer_id' })]
    props.focusRelation = { code: 'customer', seq: 1 }
    await flush()
    expect(state.open).toBe(true)
    expect(state.key).toBe('customer_id')
    const drawer = host.querySelector('.drawer-stub')!
    const labels = Array.from(drawer.querySelectorAll('.ant-form-item-label')).map(node => node.textContent?.trim())
    expect(labels).toContain('显示名字段')
    expect(labels).toContain('引用筛选')
    expect(events.focused).toHaveBeenCalledTimes(1)
    // 同一请求不重复处理。
    state.closeField()
    props.relations = [...(props.relations as ObjectRelation[])]
    await flush()
    expect(state.open).toBe(false)
  })

  it('focusRelation 不属于本设计器的关系时不动', async () => {
    const name = field(FieldType.TEXT, 'name', '名称')
    const { state, events, props } = await mount({ modelValue: [name], options: { name: options() }, relations: [] })
    props.focusRelation = { code: 'customer', seq: 1 }
    await flush()
    expect(state.open).toBe(false)
    expect(events.focused).not.toHaveBeenCalled()
  })
})

describe('未保存关系的占位行', () => {
  it('点「配置」给出「请先保存」提示并提供保存，不悄悄弹关系窗口', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(() => ({ destroy() {}, update() {} }) as never)
    const relations = [relation()]
    const { state, events } = await mount({ modelValue: [], options: {}, relations })
    const [placeholder] = relationFieldRows([], relations, {})
    expect(placeholder!.key).toBe('relation:customer')
    state.show(placeholder)
    await flush()
    expect(events.relation).not.toHaveBeenCalled()
    expect(state.open).toBe(false)
    expect(confirm).toHaveBeenCalledTimes(1)
    const config = confirm.mock.calls[0]![0] as { title: string; okText: string; onOk: () => void }
    expect(config.title).toBe('请先保存，再配置显示名字段和引用筛选')
    expect(config.okText).toBe('保存')
    config.onOk()
    expect(events.configure).toHaveBeenCalledWith('customer')
  })

  it('已保存的多选关系（没有引用列）仍直接打开对象关系窗口', async () => {
    const confirm = vi.spyOn(Modal, 'confirm')
    const relations = [relation({ id: 'rel-2', code: 'tags', kind: RelationType.MANY_TO_MANY })]
    const { state, events } = await mount({ modelValue: [], options: {}, relations })
    const [placeholder] = relationFieldRows([], relations, {})
    state.show(placeholder)
    expect(confirm).not.toHaveBeenCalled()
    expect(events.relation).toHaveBeenCalledWith(expect.objectContaining({ key: 'relation:rel-2' }))
  })
})
