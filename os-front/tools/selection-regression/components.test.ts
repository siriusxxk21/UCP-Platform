import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, defineComponent, h, nextTick, ref, provide, reactive } from 'vue'
import { selectionPreviewKey, selectionValuesKey } from '@/nocode/selection'
import SelectionField from '@/views/nocode/application/components/SelectionField.vue'
import BusinessFieldControl from '@/views/nocode/application/components/BusinessFieldControl.vue'
import SelectionPresentationEditor from '@/views/nocode/application/components/SelectionPresentationEditor.vue'
import FormPreview from '@/views/nocode/application/components/FormPreview.vue'
import FormObjectVersionNotice from '@/views/nocode/application/components/FormObjectVersionNotice.vue'
import SelectionSourceEditor from '@/views/nocode/components/SelectionSourceEditor.vue'
import { defaultFieldOptions } from '@/nocode/data-center'
import { FieldType } from '@/types/nocode/enums'
import { businessFieldRules } from '@/nocode/business-field-rules'
import { formDesignModel } from '@/nocode/form-design'

const calls = vi.hoisted(() => ({ runtime: vi.fn(), preview: vi.fn(), options: vi.fn(), objectVersion: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    runtime: { selection: calls.runtime },
    applications: {
      previewSelection: calls.preview,
      selectionOptions: calls.options,
      objectVersion: calls.objectVersion
    }
  })
}))
vi.mock('@/views/nocode/application/components/ReferenceField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: vi.fn().mockResolvedValue([]) }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn().mockResolvedValue([]) } }))
vi.mock('@/views/nocode/application/components/DirectoryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordForm.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      setup(_, { expose }) {
        expose({ validate: async () => true })
        return () => h('form')
      }
    })
  }
})

type Node = { type: string; props: Record<string, any>; children: Node[]; parent?: Node; text?: string }
const node = (type: string): Node => ({ type, props: {}, children: [] })
const renderer = createRenderer<Node, Node>({
  createElement: node,
  createText: text => ({ ...node('#text'), text }),
  createComment: node,
  setText: (n, text) => {
    n.text = text
  },
  setElementText: (n, text) => {
    n.text = text
    n.children = []
  },
  parentNode: n => n.parent || null,
  nextSibling: n => n.parent?.children[(n.parent?.children.indexOf(n) ?? -1) + 1] || null,
  patchProp: (n, key, _old, value) => {
    n.props[key] = value
  },
  insert: (n, parent, anchor) => {
    if (n.parent) n.parent.children = n.parent.children.filter(c => c !== n)
    n.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(n)
    else parent.children.splice(index, 0, n)
  },
  remove: n => {
    if (n.parent) n.parent.children = n.parent.children.filter(c => c !== n)
    n.parent = undefined
  }
})
function descendants(n: Node): Node[] {
  return [n, ...n.children.flatMap(descendants)]
}
async function settle() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(component: any, props: Record<string, unknown>) {
  const root = node('root')
  const app = renderer.createApp(component, props)
  for (const name of [
    'a-select',
    'a-tree-select',
    'a-tooltip',
    'a-button',
    'a-modal',
    'a-table',
    'a-input-search',
    'a-typography-text',
    'a-alert',
    'a-checkbox',
    'a-segmented',
    'a-empty',
    'a-card',
    'a-space',
    'a-row',
    'a-col',
    'a-form-item'
  ])
    app.component(
      name,
      defineComponent({
        inheritAttrs: false,
        setup:
          (_, { attrs, slots }) =>
          () =>
            h(name, attrs, [...(slots.default?.() || []), ...(slots.action?.() || [])])
      })
    )
  app.provide(
    selectionPreviewKey,
    ref({ applicationId: 'app', objects: [{ objectId: 'obj', versionNo: 1, checksum: 'v1' }] })
  )
  app.mount(root)
  await settle()
  return { app, nodes: () => descendants(root) }
}
const option = {
  value: 'org-a',
  label: '授权组织A',
  code: 'A',
  parentValue: null,
  path: '授权组织A',
  disabled: false,
  unavailable: false
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.runtime.mockResolvedValue({
    options: [option],
    selected: [option],
    total: 1,
    tree: false,
    defaultValue: 'org-a'
  })
  calls.preview.mockImplementation(calls.runtime.getMockImplementation()!)
  calls.options.mockResolvedValue([option])
})
describe('正式选择组件回归', () => {
  it('整数存储的资产类型引用在设计、预览、运行中均使用名称下拉单选，并只提交一个稳定 ID', async () => {
    const assetType = {
      id: 'asset_type',
      key: 'asset_type',
      name: '资产类型',
      code: 'asset_type_id',
      type: FieldType.INTEGER
    } as any
    const typeOption = { ...option, value: '9007199254740993', label: '办公设备', code: 'office', path: null }
    calls.runtime.mockResolvedValue({
      options: [typeOption],
      selected: [typeOption],
      total: 1,
      tree: false,
      defaultValue: null
    })
    calls.preview.mockImplementation(calls.runtime.getMockImplementation()!)
    for (const mode of ['design', 'preview', 'runtime'] as const) {
      const rule = businessFieldRules(
        [assetType],
        { asset_type: { ...defaultFieldOptions(), generated: true } },
        formDesignModel,
        true,
        {
          mode,
          applicationId: 'app',
          objectId: 'obj',
          relations: [{ fieldId: 'asset_type', targetObjectId: 'asset_types' } as any]
        }
      )[0]!
      expect(rule.type).toBe('nocodeBusinessField')
      expect(rule.props).toMatchObject({ kind: FieldType.REFERENCE, selection: true, multiple: false, disabled: false })
      const changed = vi.fn()
      const mounted = await mount(BusinessFieldControl, {
        ...rule.props,
        modelValue: typeOption.value,
        'onUpdate:modelValue': changed
      })
      const select = mounted.nodes().find(n => n.type === 'a-select')!
      expect(select.props.mode).toBeUndefined()
      expect(select.props.options).toContainEqual(
        expect.objectContaining({ value: typeOption.value, label: '办公设备' })
      )
      expect(select.props.disabled).toBe(mode === 'design')
      if (mode !== 'design') {
        select.props.onChange(typeOption.value)
        expect(changed).toHaveBeenLastCalledWith('9007199254740993')
        select.props.onChange(undefined)
        expect(changed).toHaveBeenLastCalledWith(null)
      }
      mounted.app.unmount()
    }
  })
  it('多选字段切换到系统组织目录时保持多选，切换回来也不丢失选择数量', async () => {
    const field = reactive({
      id: 'org',
      key: 'org',
      code: 'org',
      name: '所属组织',
      type: FieldType.MULTI_SELECT,
      length: null,
      precision: null,
      scale: null,
      required: false,
      unique: false,
      sort: 0
    })
    const options = reactive({ ...defaultFieldOptions(), defaultValue: '["old"]' })
    const mounted = await mount(SelectionSourceEditor, { field, options })
    const kind = () =>
      mounted
        .nodes()
        .find(n => n.type === 'a-select' && n.props.value === (options.selection?.kind || 'LOCAL_OPTIONS'))!
    kind().props.onChange('DIRECTORY')
    await settle()
    expect(field.type).toBe(FieldType.MULTI_SELECT)
    expect(options.selection?.directory).toBe('ORGANIZATION')
    expect(options.defaultValue).toBeNull()
    expect(mounted.nodes().some(n => n.type === 'a-select' && n.props.value === true)).toBe(true)
    kind().props.onChange('LOCAL_OPTIONS')
    await settle()
    expect(field.type).toBe(FieldType.MULTI_SELECT)
    mounted.app.unmount()
  })
  it('表单直接提示新版新增字段，只有点击同步才请求更改应用引用', async () => {
    const previous = {
      objectId: 'company',
      versionNo: 2,
      checksum: 'v2',
      definition: {
        objectName: '公司',
        fields: [{ id: 'name', name: '名称' }],
        fieldOptions: {}
      }
    }
    const latest = {
      ...previous,
      versionNo: 4,
      checksum: 'v4',
      definition: {
        ...previous.definition,
        fields: [...previous.definition.fields, { id: 'org', name: '所属组织', type: 'MULTI_SELECT' }]
      }
    }
    calls.objectVersion.mockResolvedValue(latest)
    const synchronize = vi.fn().mockResolvedValue(latest)
    const mounted = await mount(FormObjectVersionNotice, { object: previous, synchronize })
    const alert = mounted.nodes().find(n => n.type === 'a-alert')!
    expect(alert.props.message).toContain('引用 V2，最新已发布 V4')
    expect(alert.props.description).toContain('新增 1 个字段：所属组织')
    expect(synchronize).not.toHaveBeenCalled()
    await mounted
      .nodes()
      .find(n => n.type === 'a-button' && n.props.type === 'primary')!
      .props.onClick()
    expect(synchronize).toHaveBeenCalledWith('company')
    expect(previous.versionNo).toBe(2)
    mounted.app.unmount()
  })
  it('检查版本失败只展示原因，不把旧结构冒充最新或开放同步', async () => {
    calls.objectVersion.mockRejectedValue(new Error('读取失败'))
    const synchronize = vi.fn()
    const mounted = await mount(FormObjectVersionNotice, {
      object: {
        objectId: 'company',
        versionNo: 2,
        checksum: 'v2',
        definition: { objectName: '公司', fields: [], fieldOptions: {} }
      },
      synchronize
    })
    expect(mounted.nodes().find(n => n.type === 'a-alert')!.props.description).toContain('无法检查对象更新')
    expect(mounted.nodes().some(n => n.type === 'a-button' && n.props.type === 'primary')).toBe(false)
    expect(synchronize).not.toHaveBeenCalled()
    mounted.app.unmount()
  })
  it('用户默认值不在候选首屏时仍按固定引用解析名称', async () => {
    calls.preview.mockResolvedValue({
      options: [],
      selected: [{ ...option, value: '42', label: '分页外用户', path: '分页外用户' }],
      total: 0,
      tree: false,
      defaultValue: null
    })
    const mounted = await mount(SelectionPresentationEditor, {
      definition: { objectId: 'obj', fields: [{ id: 'user', type: 'USER' }], fieldOptions: {}, relations: [] },
      fieldId: 'user',
      versionNo: 1,
      modelValue: { defaultValue: '42' }
    })
    expect(calls.preview.mock.calls[0]?.[0].query.selected).toEqual(['42'])
    expect(
      mounted
        .nodes()
        .filter(n => n.type === 'a-select')
        .at(-1)!.props.options
    ).toEqual(expect.arrayContaining([expect.objectContaining({ value: '42', label: '分页外用户' })]))
    mounted.app.unmount()
  })
  it('关系字段的限定候选视图只列出绑定引用目标对象的视图，可清空并对失效引用给出提示', async () => {
    const definition = {
      objectId: 'obj',
      fields: [{ id: 'customer', name: '客户', type: FieldType.REFERENCE }],
      fieldOptions: {},
      relations: [{ id: 'r1', fieldId: 'customer', targetObjectId: 'customer_obj', kind: 'REFERENCE' }]
    }
    const objects = {
      customer_obj: {
        objectId: 'customer_obj',
        versionNo: 1,
        checksum: 'v1',
        definition: { objectName: '客户', fields: [], fieldOptions: {}, relations: [], details: [] }
      }
    }
    const resources = [
      { id: 'view_keep', kind: 'VIEW', code: 'keep', name: '有效客户', config: { objectId: 'customer_obj' } },
      { id: 'view_other', kind: 'VIEW', code: 'other', name: '其它对象视图', config: { objectId: 'other_obj' } },
      { id: 'form_keep', kind: 'FORM', code: 'form', name: '客户表单', config: { objectId: 'customer_obj' } }
    ] as any
    const changed = vi.fn()
    const mounted = await mount(SelectionPresentationEditor, {
      definition,
      fieldId: 'customer',
      objects,
      resources,
      modelValue: { viewId: 'view_keep' },
      'onUpdate:modelValue': changed
    })
    const viewSelect = mounted
      .nodes()
      .filter(n => n.type === 'a-select')
      .find(n => JSON.stringify(n.props.options || '').includes('view_keep'))!
    expect(viewSelect.props.options).toEqual([{ value: 'view_keep', label: '有效客户' }])
    expect(viewSelect.props.value).toBe('view_keep')
    viewSelect.props.onChange(null)
    expect(changed).toHaveBeenCalledWith({ viewId: null })
    mounted.app.unmount()
    const stale = await mount(SelectionPresentationEditor, {
      definition,
      fieldId: 'customer',
      objects,
      resources: [],
      modelValue: { viewId: 'removed' },
      'onUpdate:modelValue': vi.fn()
    })
    expect(
      stale.nodes().some(n => n.type === 'a-alert' && n.props.message === '限定视图已不存在，请重新选择或清空后保存')
    ).toBe(true)
    stale.app.unmount()
  })
  it('上游默认值异步到达后仍能初始化尚未填写的下游默认值', async () => {
    const values = ref<Record<string, unknown>>({})
    const changed = vi.fn()
    const host = defineComponent({
      setup() {
        provide(selectionValuesKey, values)
        return () =>
          h(SelectionField, {
            applicationId: 'app',
            objectId: 'obj',
            fieldId: 'org',
            presentation: { linkFieldId: 'scope' },
            creating: true,
            'onUpdate:modelValue': changed
          })
      }
    })
    const mounted = await mount(host, {})
    expect(calls.runtime).not.toHaveBeenCalled()
    values.value = { scope: 'root' }
    await settle()
    expect(changed.mock.calls).toEqual([['org-a']])
    mounted.app.unmount()
  })
  it('版本切换后的迟到响应不能恢复旧候选和默认值，读取失败会清空候选', async () => {
    const context = ref({ applicationId: 'app', objects: [{ objectId: 'obj', versionNo: 1, checksum: 'v1' }] })
    const pending: Array<{ resolve: (v: unknown) => void; reject: (e: Error) => void }> = []
    calls.preview.mockImplementation(() => new Promise((resolve, reject) => pending.push({ resolve, reject })))
    const changed = vi.fn()
    const host = defineComponent({
      setup() {
        provide(selectionPreviewKey, context)
        return () =>
          h(SelectionField, {
            applicationId: 'app',
            objectId: 'obj',
            fieldId: 'org',
            preview: true,
            creating: true,
            'onUpdate:modelValue': changed
          })
      }
    })
    const mounted = await mount(host, {})
    context.value = { applicationId: 'app', objects: [{ objectId: 'obj', versionNo: 2, checksum: 'v2' }] }
    await settle()
    pending[1]!.resolve({
      options: [{ ...option, value: 'v2', label: '新版候选' }],
      selected: [],
      total: 1,
      tree: false,
      defaultValue: 'v2'
    })
    await settle()
    pending[0]!.resolve({ options: [option], selected: [], total: 1, tree: false, defaultValue: 'org-a' })
    await settle()
    expect(changed.mock.calls).toEqual([['v2']])
    expect(mounted.nodes().find(n => n.type === 'a-select')!.props.options[0].value).toBe('v2')
    context.value = { applicationId: 'app', objects: [{ objectId: 'obj', versionNo: 3, checksum: 'v3' }] }
    await settle()
    pending[2]!.reject(new Error('没有字段读取权限'))
    await settle()
    expect(mounted.nodes().find(n => n.type === 'a-select')!.props.options).toEqual([])
    mounted.app.unmount()
  })
  it('失效默认值留空并显示服务端原因', async () => {
    calls.runtime.mockResolvedValue({
      options: [],
      selected: [],
      total: 0,
      tree: false,
      defaultValue: null,
      defaultWarning: '默认值已失效或超出当前可选范围，请重新选择'
    })
    const changed = vi.fn()
    const mounted = await mount(SelectionField, {
      applicationId: 'app',
      objectId: 'obj',
      fieldId: 'org',
      creating: true,
      'onUpdate:modelValue': changed
    })
    expect(changed).not.toHaveBeenCalled()
    expect(mounted.nodes().find(n => n.type === 'a-alert')!.props.message).toContain('默认值已失效')
    mounted.app.unmount()
  })
  it('完整预览校验只发送服务端接受的只读协议', async () => {
    const definition = {
      objectId: 'obj',
      fields: [{ id: 'org', key: 'org', type: 'ORGANIZATION', name: '组织', required: false }],
      fieldOptions: {},
      relations: [],
      details: []
    }
    const mounted = await mount(FormPreview, {
      applicationId: 'app',
      definition,
      objects: { obj: { objectId: 'obj', versionNo: 1, checksum: 'v1', definition } },
      nodes: [{ id: 'org_node', type: 'FIELD', fieldId: 'org', children: [] }],
      options: {},
      detailIds: [],
      name: '测试'
    })
    await mounted
      .nodes()
      .find(n => n.type === 'a-button' && n.props.type === 'primary')!
      .props.onClick()
    const body = calls.preview.mock.calls.at(-1)?.[0]
    expect(Object.keys(body).sort()).toEqual(['form', 'objects', 'query', 'validate'])
    expect(body.query).toMatchObject({ applicationId: 'app', fieldId: 'org' })
    expect(body.validate).toBe(true)
    mounted.app.unmount()
  })
  it('预览查询真实候选并传递草稿版本，显式 null 不会被默认值覆盖', async () => {
    const changed = vi.fn()
    const mounted = await mount(BusinessFieldControl, {
      kind: 'ORGANIZATION',
      selection: true,
      mode: 'preview',
      applicationId: 'app',
      objectId: 'obj',
      fieldId: 'org',
      creating: true,
      modelValue: null,
      'onUpdate:modelValue': changed
    })
    expect(calls.runtime).not.toHaveBeenCalled()
    expect(calls.preview.mock.calls[0]?.[0].objects).toEqual([{ objectId: 'obj', versionNo: 1, checksum: 'v1' }])
    expect(mounted.nodes().find(n => n.type === 'a-select')?.props.options[0].label).toBe('授权组织A')
    expect(changed).not.toHaveBeenCalled()
    mounted.app.unmount()
  })
  it('新明细保留父记录身份并请求新建默认值', async () => {
    const changed = vi.fn()
    const mounted = await mount(SelectionField, {
      applicationId: 'app',
      objectId: 'obj',
      fieldId: 'org',
      detailId: 'items',
      recordId: 'parent',
      creating: true,
      'onUpdate:modelValue': changed
    })
    expect(calls.runtime.mock.calls[0]?.[0]).toMatchObject({ recordId: 'parent', detailId: 'items', creating: true })
    expect(changed).toHaveBeenCalledWith('org-a')
    mounted.app.unmount()
  })
  it('弹窗清空只修改草稿，确认才把 null 交给表单', async () => {
    const changed = vi.fn()
    const mounted = await mount(SelectionField, {
      applicationId: 'app',
      objectId: 'obj',
      fieldId: 'org',
      modelValue: 'org-a',
      presentation: { appearance: 'MODAL' },
      'onUpdate:modelValue': changed
    })
    mounted
      .nodes()
      .find(n => n.type === 'a-button' && n.props.block !== undefined)!
      .props.onClick()
    await settle()
    const clear = mounted.nodes().find(n => n.type === 'a-button' && n.props.type === 'link')!
    clear.props.onClick()
    await settle()
    expect(changed).not.toHaveBeenCalled()
    mounted
      .nodes()
      .find(n => n.type === 'a-modal')!
      .props.onOk()
    expect(changed).toHaveBeenCalledWith(null)
    mounted.app.unmount()
  })
  it('属性面板请求固定版本候选', async () => {
    const mounted = await mount(SelectionPresentationEditor, {
      definition: { objectId: 'obj', fields: [{ id: 'org', type: 'ORGANIZATION' }], fieldOptions: {}, relations: [] },
      fieldId: 'org',
      versionNo: 3
    })
    expect(calls.options).toHaveBeenCalledWith('obj', 'org', undefined, 3)
    mounted.app.unmount()
  })
})
