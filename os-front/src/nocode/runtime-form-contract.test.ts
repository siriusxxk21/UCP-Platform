// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import { workEntryKey } from '@/nocode/work-context'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { businessFieldRules } from '@/nocode/business-field-rules'

const mocks = vi.hoisted(() => ({
  submit: vi.fn(),
  saveDraft: vi.fn(),
  relatedForm: vi.fn(),
  pendingUpload: false,
  failedUpload: false
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol.for('richuang.nocode.platform'),
  useNocodePlatform: () => ({ runtime: mocks, work: { saveDraft: mocks.saveDraft }, applications: {} })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'form-regression' } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordForm.vue', async () => {
  const { defineComponent, h } = await import('vue')
  const { default: FormDetailOutlet } = await import('@/views/nocode/application/components/FormDetailOutlet.vue')
  const detailNodes = (
    nodes: import('@/types/nocode/application-ui').UiNode[]
  ): import('@/types/nocode/application-ui').UiNode[] =>
    nodes.flatMap(node => (node.type === 'INTERNAL_DETAIL' ? [node] : detailNodes(node.children)))
  return {
    default: defineComponent({
      inheritAttrs: false,
      props: [
        'modelValue',
        'model',
        'fields',
        'options',
        'nodes',
        'relations',
        'detailId',
        'recordId',
        'detailRecordId',
        'applicationId',
        'objectId',
        'formId',
        'creating',
        'preview',
        'clientRowKey'
      ],
      emits: ['update:modelValue'],
      setup(props, { emit, expose }) {
        const validateUploads = () => {
          if (mocks.pendingUpload) throw new Error('请等待附件上传完成')
          if (mocks.failedUpload) throw new Error('有附件上传失败，请移除后重试')
        }
        expose({
          validateUploads,
          validate: async () => validateUploads(),
          get rowKey() {
            return props.clientRowKey
          }
        })
        return () =>
          h(
            'div',
            {
              class: 'form-probe',
              'data-detail': props.detailId || '',
              'data-contract': JSON.stringify({
                model: props.model,
                fields: props.fields,
                options: props.options,
                relations: props.relations,
                recordId: props.recordId,
                detailRecordId: props.detailRecordId,
                applicationId: props.applicationId,
                objectId: props.objectId,
                formId: props.formId,
                creating: props.creating,
                preview: props.preview
              })
            },
            [
              ...props.fields.map((field: any) =>
                h('input', {
                  'data-field': field.id,
                  disabled: !props.model.writable || !props.model.writeFields?.includes(field.id),
                  value: props.modelValue[field.id] || '',
                  onInput: (event: Event) =>
                    emit('update:modelValue', {
                      ...props.modelValue,
                      [field.id]: (event.target as HTMLInputElement).value
                    })
                })
              ),
              ...detailNodes(props.nodes || []).map(node =>
                h(FormDetailOutlet, { ...node.detail!, title: node.text || undefined })
              )
            ]
          )
      }
    })
  }
})
import RecordEditor from '@/views/nocode/application/components/RecordEditor.vue'
import RelatedFormEditor from '@/views/nocode/application/components/RelatedFormEditor.vue'
import FormPreview from '@/views/nocode/application/components/FormPreview.vue'
import RelatedFormPreview from '@/views/nocode/application/components/RelatedFormPreview.vue'

const apps: App[] = []
const field = (id: string, type = 'TEXT') => ({
  id,
  key: id,
  code: id,
  name: id,
  type,
  required: false,
  unique: false,
  sort: 0
})
const node = (id: string, readOnly = false) => uiNode(NodeKind.FIELD, { fieldId: id, presentation: { readOnly } })
const nested = (...children: any[]) => [uiNode(NodeKind.CARD, { children })]
const cap = { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' }
function fixture() {
  const permissions = {
    actions: ['CREATE', 'UPDATE', 'READ'],
    readFields: ['editable', 'locked', 'attachment'],
    writeFields: ['editable', 'locked', 'attachment'],
    readDetails: ['d'],
    writeDetails: ['d'],
    readRelations: [],
    writeRelations: []
  }
  const relation = {
    id: 'product-relation',
    fieldId: 'product',
    sourceDetailId: 'd',
    targetObjectId: 'products',
    kind: 'REFERENCE'
  }
  const object = {
    objectId: 'object',
    objectName: '对象',
    titleFieldId: 'editable',
    settings: {},
    fields: [field('editable'), field('locked'), field('attachment', 'ATTACHMENT')],
    fieldOptions: {},
    relations: [relation],
    details: [
      {
        id: 'd',
        name: '明细',
        state: 'ACTIVE',
        fields: [field('shown'), field('locked'), field('hidden'), field('product', 'INTEGER')],
        fieldOptions: { product: { generated: true } }
      }
    ]
  }
  const form = {
    objectId: 'object',
    nodes: nested(node('editable'), node('locked', true), node('attachment')),
    detailIds: ['d'],
    detailNodes: {
      d: nested(
        uiNode(NodeKind.FIELD, { fieldId: 'shown', presentation: { label: '明细自定义名称' } }),
        node('locked', true),
        node('product')
      )
    },
    relatedForms: [],
    options: {}
  }
  const model = { ...cap, permissions, object, details: { d: cap } }
  const record = {
    record: { id: 'record', revision: '5', values: { editable: '原值', locked: '原只读值' }, permissions },
    details: {
      d: [
        {
          id: 'line',
          revision: '3',
          clientRowKey: 'line-stable',
          values: { shown: '显示', locked: '明细只读', hidden: '未布局', product: '42' }
        }
      ]
    }
  }
  return { model, form, record, relation }
}
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  Array.from(document.querySelectorAll('button')).find(b => b.textContent?.replace(/\s/g, '').includes(label))!
function mount(component: any, props: any, work = false) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(() => h(component, props))
  app.use(Antd)
  if (work)
    app.provide(workEntryKey, { application: ref({ versionNo: 1, checksum: 'fixed' } as any), openDraft: vi.fn() })
  app.mount(host)
  apps.push(app)
  return host
}
function contract(host: HTMLElement, detail = 'd') {
  return JSON.parse(host.querySelector<HTMLElement>(`.form-probe[data-detail="${detail}"]`)!.dataset.contract!)
}
beforeAll(() => {
  window.matchMedia = vi.fn().mockReturnValue({ matches: false, addListener() {}, removeListener() {} })
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Element.prototype.scrollIntoView = vi.fn()
})
beforeEach(() => {
  mocks.pendingUpload = false
  mocks.failedUpload = false
  mocks.submit.mockResolvedValue({
    outcome: 'EFFECTIVE',
    result: { record: { id: 'saved', revision: '6', values: {} }, details: {} }
  })
  mocks.saveDraft.mockResolvedValue({ id: 'draft' })
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  sessionStorage.clear()
  vi.clearAllMocks()
})

describe('运行表单容器字段与保存合同', () => {
  it('多个画布明细独立切换呈现模式，保持一份主表及原行身份；插槽中的明细仍参与保存校验', async () => {
    const f = fixture()
    const second = { ...f.model.object.details[0]!, id: 'd2', name: '补充明细' }
    f.model.object.details.push(second)
    Object.assign(f.model.details, { d2: f.model.details.d })
    f.model.permissions.readDetails.push('d2')
    f.model.permissions.writeDetails.push('d2')
    f.form.detailIds.push('d2')
    Object.assign(f.form.detailNodes, { d2: f.form.detailNodes.d })
    Object.assign(f.record.details, { d2: [{ ...f.record.details.d[0]!, id: 'line2', clientRowKey: 'line-two' }] })
    f.form.nodes.push(
      uiNode(NodeKind.INTERNAL_DETAIL, { detail: { detailId: 'd', mode: 'CARDS' } }),
      uiNode(NodeKind.CARD, {
        children: [uiNode(NodeKind.INTERNAL_DETAIL, { detail: { detailId: 'd2', mode: 'GRID' } })]
      })
    )
    const host = mount(RecordEditor, {
      applicationId: 'app',
      formId: 'form',
      model: f.model,
      form: f.form,
      record: f.record
    })
    await flush()
    expect(host.querySelectorAll('.form-probe[data-detail=""]')).toHaveLength(1)
    const first = host.querySelector<HTMLElement>('[data-detail-id="d"]')!,
      other = host.querySelector<HTMLElement>('[data-detail-id="d2"]')!
    expect(first.classList.contains('detail-grid')).toBe(false)
    expect(other.classList.contains('detail-grid')).toBe(true)
    first.querySelector<HTMLInputElement>('input[type="radio"][value="GRID"]')!.click()
    await flush()
    expect(first.classList.contains('detail-grid')).toBe(true)
    expect(other.classList.contains('detail-grid')).toBe(true)
    other.querySelector<HTMLInputElement>('input[type="radio"][value="CARDS"]')!.click()
    await flush()
    expect(first.classList.contains('detail-grid')).toBe(true)
    expect(other.classList.contains('detail-grid')).toBe(false)
    mocks.failedUpload = true
    button('保存记录').click()
    await flush()
    expect(mocks.submit).not.toHaveBeenCalled()
    mocks.failedUpload = false
    button('保存记录').click()
    await flush()
    expect(mocks.submit.mock.calls[0]![0].details.d2[0]).toMatchObject({
      id: 'line2',
      revision: '3',
      clientRowKey: 'line-two'
    })
  })
  it('已有整单保存保留可写字段、关系、行身份和版本，排除嵌套只读及未布局字段', async () => {
    const f = fixture()
    const host = mount(RecordEditor, {
      applicationId: 'app',
      formId: 'form',
      model: f.model,
      form: f.form,
      record: f.record
    })
    await flush()
    expect(contract(host).model.writeFields).toEqual(['shown', 'product'])
    expect(Array.from(host.querySelectorAll('.detail-grid-head span')).map(el => el.textContent?.trim())).toEqual([
      '序号 / 操作',
      '明细自定义名称',
      'locked',
      'product'
    ])
    button('保存记录').click()
    await flush()
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.submit.mock.calls[0]![0]).toMatchObject({
      id: 'record',
      expectedRevision: '5',
      values: { editable: '原值' },
      details: {
        d: [{ id: 'line', revision: '3', clientRowKey: 'line-stable', values: { shown: '显示', product: '42' } }]
      }
    })
    expect(mocks.submit.mock.calls[0]![0].values).not.toHaveProperty('locked')
    expect(mocks.submit.mock.calls[0]![0].details.d[0].values).toEqual({ shown: '显示', product: '42' })
  })
  it.each(['pendingUpload', 'failedUpload'] as const)(
    '普通应用暂存阻断%s，移除/完成后仍可暂存非必填数据',
    async status => {
      const f = fixture()
      f.form.detailIds = []
      f.model.object.details = []
      f.model.permissions.readDetails = []
      f.model.permissions.writeDetails = []
      const onCancel = vi.fn()
      mount(RecordEditor, { applicationId: 'app', formId: 'form', model: f.model, form: f.form, onCancel }, true)
      await flush()
      mocks[status] = true
      button('暂存草稿').click()
      await flush()
      expect(mocks.saveDraft).not.toHaveBeenCalled()
      expect(onCancel).not.toHaveBeenCalled()
      mocks[status] = false
      button('暂存草稿').click()
      await flush()
      expect(mocks.saveDraft).toHaveBeenCalledOnce()
      expect(onCancel).toHaveBeenCalledOnce()
    }
  )
  it('关联表单修改主字段后，明细只提交已布局可写字段并补齐关系记录上下文', async () => {
    const f = fixture()
    mocks.relatedForm.mockResolvedValue({
      model: f.model,
      form: f.form,
      records: [f.record],
      multiple: false,
      linkFieldId: 'owner',
      required: false
    })
    const editor = ref<InstanceType<typeof RelatedFormEditor>>()
    const host = mount(RelatedFormEditor, {
      ref: editor,
      applicationId: 'app',
      objectId: 'parent',
      formId: 'parent-form',
      recordId: 'parent-record',
      binding: { id: 'binding', title: '关联数据', direction: 'OUTGOING', formId: 'child-form' }
    })
    await flush()
    const context = contract(host)
    expect(Array.from(host.querySelectorAll('.related-detail-head span')).map(el => el.textContent?.trim())).toEqual([
      '明细自定义名称',
      'locked',
      'product'
    ])
    expect(context).toMatchObject({
      relations: [f.relation],
      recordId: 'record',
      detailRecordId: 'line',
      formId: 'child-form',
      objectId: 'object'
    })
    const rule = businessFieldRules(context.fields, context.options, context.model, context.creating, context).find(
      r => r.field === 'product'
    )!
    expect(rule.props).toMatchObject({ selection: true, disabled: false })
    const input = host.querySelector<HTMLInputElement>('input[data-field="editable"]')!
    input.value = '修改后'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    const payload = await editor.value!.payload()
    expect(payload[0]!.values).toEqual({ editable: '修改后', attachment: null })
    expect(payload[0]!.details!.d![0]!.values).toEqual({ shown: '显示', product: '42' })
    expect(payload[0]!.details!.d![0]).toMatchObject({ id: 'line', revision: '3', clientRowKey: 'line-stable' })
  })
  it.each([false, true])('主/关联预览的明细关联选择与运行合同一致（关联预览=%s）', async related => {
    const f = fixture()
    const objects = { object: { objectId: 'object', versionNo: 1, checksum: 'fixed', definition: f.model.object } }
    const props = related
      ? {
          applicationId: 'app',
          objects,
          resources: [{ id: 'child-form', config: f.form }],
          binding: {
            sourceObjectId: 'object',
            direction: 'INCOMING',
            relationId: 'parent-link',
            formId: 'child-form',
            title: '关联预览'
          }
        }
      : { ...f.form, options: {}, definition: f.model.object, name: '预览', applicationId: 'app', objects }
    const host = mount(related ? RelatedFormPreview : FormPreview, props)
    await flush()
    button('添加明细').click()
    await flush()
    const header = Array.from(
      host.querySelectorAll(related ? '.related-preview-head span' : '.detail-grid-head span')
    ).map(el => el.textContent?.trim())
    expect(header).toEqual(
      related ? ['明细自定义名称', 'locked', 'product'] : ['序号 / 操作', '明细自定义名称', 'locked', 'product']
    )
    const context = contract(host)
    expect(context.relations).toEqual([f.relation])
    expect(context.fields.map((field: any) => field.id)).toEqual(['shown', 'locked', 'product'])
    expect(context.model.writeFields).toEqual(['shown', 'product'])
    const rule = businessFieldRules(context.fields, context.options, context.model, true, {
      ...context,
      mode: 'preview'
    }).find(r => r.field === 'product')!
    expect(rule.props).toMatchObject({ selection: true, disabled: false, mode: 'preview' })
  })
})
