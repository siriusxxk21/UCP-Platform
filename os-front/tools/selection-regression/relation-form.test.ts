import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, find, event, text, flush } from './renderer'
import { FieldType, RelationType } from '@/types/nocode/enums'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { boundFields } from '@/nocode/application-ui'
const mocks = vi.hoisted(() => ({ save: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: mocks }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn(), info: vi.fn() } }))
vi.mock('@/views/nocode/application/components/RecordForm.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    setup(_, { attrs, expose }) {
      expose({ validate: async () => {} })
      return () => h('record-form', attrs)
    }
  })
}))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({
  default: defineComponent({
    setup(_, { attrs }) {
      return () => h('read-view', attrs)
    }
  })
}))
import RecordEditor from '@/views/nocode/application/components/RecordEditor.vue'
const mounted: ReturnType<typeof mount>[] = []
const permissions = {
  actions: ['CREATE', 'UPDATE', 'READ'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: [],
  readRelations: ['20'],
  writeRelations: ['20']
}
const object = {
  objectId: '10',
  fields: [{ id: 'name', code: 'c_name', name: '名称', type: FieldType.TEXT }],
  fieldOptions: {},
  details: [],
  relations: [
    {
      id: '20',
      code: 'c_types',
      name: '类型数组',
      kind: RelationType.MANY_TO_MANY,
      targetObjectId: '30',
      required: false
    }
  ]
}
const model = {
  object,
  permissions,
  writable: true,
  generatedKey: true,
  keyFieldId: null,
  keyType: 'bigint',
  details: {}
}
beforeEach(() => {
  mocks.save.mockImplementation(async (input: any) => ({
    record: { id: '1', revision: '2', values: input.values },
    relations: input.relations,
    details: {}
  }))
})
afterEach(() => {
  mounted.splice(0).forEach(m => m.unmount())
  vi.clearAllMocks()
})
describe('多选关系的真实表单容器', () => {
  it.each([false, true])('旧表单/新表单保存使用关系映射（新布局=%s）', async relationLayout => {
    const nodes = [uiNode(NodeKind.FIELD, { fieldId: 'name' })]
    if (relationLayout) nodes.push(uiNode(NodeKind.FIELD, { fieldId: 'relation_20' }))
    const record = {
      record: { id: '1', revision: '1', values: { name: '电脑' }, permissions },
      relations: { '20': ['100', '101'] },
      details: {}
    }
    const page = mount(RecordEditor, {
      applicationId: 'app',
      model,
      record,
      formId: 'form',
      form: { objectId: '10', nodes, detailIds: [], options: { relationLayout } }
    })
    mounted.push(page)
    await flush()
    const form = find(page.root, 'record-form')
    expect(boundFields(form.props.nodes)).toContain('relation_20')
    expect(form.props.modelValue.relation_20).toEqual(['100', '101'])
    await event(form, 'onUpdate:modelValue', { name: '电脑', relation_20: ['101'] })
    await event(
      find(page.root, 'a-button', n => text(n).includes('保存')),
      'onClick'
    )
    expect(mocks.save).toHaveBeenCalledWith(
      expect.objectContaining({ values: { name: '电脑' }, relations: { '20': ['101'] } })
    )
    expect(mocks.save.mock.calls[0]![0].values).not.toHaveProperty('relation_20')
  })
  it('关系设为本表单只读后不会随主字段保存而清空', async () => {
    const page = mount(RecordEditor, {
      applicationId: 'app',
      model,
      record: {
        record: { id: '1', revision: '1', values: { name: '电脑' }, permissions },
        relations: { '20': ['100'] },
        details: {}
      },
      form: {
        objectId: '10',
        detailIds: [],
        options: { relationLayout: true },
        nodes: [
          uiNode(NodeKind.FIELD, { fieldId: 'name' }),
          uiNode(NodeKind.FIELD, { fieldId: 'relation_20', presentation: { readOnly: true } })
        ]
      }
    })
    mounted.push(page)
    await flush()
    expect(find(page.root, 'record-form').props.model.writeFields).not.toContain('relation_20')
    await event(
      find(page.root, 'a-button', n => text(n).includes('保存')),
      'onClick'
    )
    expect(mocks.save.mock.calls[0]![0].relations).toEqual({})
  })
})
