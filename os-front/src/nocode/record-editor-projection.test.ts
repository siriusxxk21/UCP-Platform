import { describe, expect, it } from 'vitest'
import { reactive, ref } from 'vue'
import { NodeKind, uiNode, type FormConfig, type RelatedFormBinding } from '@/types/nocode/application-ui'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import type { ObjectDetail, ObjectRelation } from '@/types/nocode/data-center'
import type { Aggregate, RecordModel, RelatedFormResult, TableModel } from '@/types/nocode/runtime'
import { defaultFieldOptions, newDesign } from './data-center'
import { newField } from './object-draft'
import { recordDefaults, recordPayload } from './record-form'
import { boundFields } from './application-ui'
import {
  useRecordEditorProjection,
  useRelatedFormProjection,
  type RelatedProjectionRow
} from './record-editor-projection'

const field = (id: string, type: FieldType = FieldType.TEXT) => ({ ...newField(0, id), id, key: id, code: id, type })
const node = (id: string, readOnly = false) => uiNode(NodeKind.FIELD, { fieldId: id, presentation: { readOnly } })
const table = (): TableModel => ({ writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' })
const binding = (): RelatedFormBinding => ({
  id: 'binding',
  sourceObjectId: 'parent',
  relationId: 'parent-link',
  direction: 'OUTGOING',
  formId: 'child-form',
  title: '关联数据'
})
function fixture() {
  const mainBinding = newDesign().mainBinding
  if (!mainBinding) throw new Error('夹具缺少主表绑定')
  const detail: ObjectDetail = {
    id: 'lines',
    code: 'lines',
    name: '明细',
    tableName: 'biz_lines',
    state: MemberState.ACTIVE,
    indexes: [],
    fields: [field('value'), field('locked'), field('hidden'), field('product', FieldType.INTEGER)],
    fieldOptions: {
      value: { ...defaultFieldOptions(), defaultValue: '默认明细' },
      locked: { ...defaultFieldOptions(), defaultValue: '不能初始化' },
      product: { ...defaultFieldOptions(), generated: true, defaultValue: '42' }
    }
  }
  const mainRelation: ObjectRelation = {
    id: 'parent-link',
    code: 'parent',
    name: '父记录',
    kind: RelationType.REFERENCE,
    targetObjectId: 'parents',
    fieldId: 'parent',
    targetFieldId: 'id',
    required: false,
    onDelete: 'RESTRICT'
  }
  const detailRelation: ObjectRelation = {
    ...mainRelation,
    id: 'product-link',
    fieldId: 'product',
    sourceDetailId: 'lines'
  }
  const allFields = ['name', 'locked', 'hidden', 'status', 'amount', 'parent', 'choice', 'denied']
  const model: RecordModel = {
    ...table(),
    permissions: {
      actions: ['READ', 'CREATE', 'UPDATE'],
      readFields: allFields,
      writeFields: allFields.filter(id => id !== 'denied'),
      readDetails: ['lines'],
      writeDetails: ['lines'],
      readRelations: [],
      writeRelations: []
    },
    object: {
      objectId: 'object',
      objectCode: 'object',
      objectName: '对象',
      schemaName: 'public',
      tableName: 'biz_object',
      source: 'GENERATED',
      readOnly: false,
      titleFieldId: 'name',
      mainBinding,
      settings: { icon: null, ownerId: null, organizationId: null, titleTemplate: null },
      fields: allFields.map(id =>
        field(id, id === 'amount' ? FieldType.DECIMAL : id === 'choice' ? FieldType.SELECT : FieldType.TEXT)
      ),
      fieldOptions: {
        name: { ...defaultFieldOptions(), defaultValue: '默认名称' },
        locked: { ...defaultFieldOptions(), defaultValue: '不能初始化' },
        amount: { ...defaultFieldOptions(), defaultValue: '9007199254740993.001' },
        parent: { ...defaultFieldOptions(), defaultValue: '7', generated: true },
        choice: {
          ...defaultFieldOptions(),
          defaultValue: 'old',
          options: [{ code: 'old', label: '旧选项', disabled: false }]
        }
      },
      relations: [mainRelation, detailRelation],
      details: [detail]
    },
    details: { lines: table() }
  }
  const form: FormConfig = {
    objectId: 'object',
    nodes: [
      uiNode(NodeKind.CARD, { children: allFields.filter(id => id !== 'hidden').map(id => node(id, id === 'locked')) })
    ],
    detailIds: ['lines'],
    detailNodes: {
      lines: [uiNode(NodeKind.ROW, { children: [node('value'), node('locked', true), node('product')] })]
    },
    relatedForms: [],
    options: { layout: 'vertical', submitText: '保存' }
  }
  const record: Aggregate = {
    record: {
      id: 'record',
      revision: '5',
      values: { name: null, amount: '', locked: '原值', status: 'draft' },
      permissions: structuredClone(model.permissions)
    },
    details: {
      lines: [
        {
          id: 'line',
          revision: '3',
          clientRowKey: 'stable',
          values: { value: '原明细', locked: '只读明细', hidden: '未布局', product: '42' }
        }
      ]
    }
  }
  return { model, form, record, detail, mainRelation, detailRelation }
}

describe('整单编辑器投影', () => {
  it('普通输入变化保持主表和明细模型、字段、关系选项引用，不写原输入', () => {
    const f = fixture()
    const source = reactive({ model: f.model, record: f.record, form: f.form })
    const projection = useRecordEditorProjection(source)
    const refs = () => [
      projection.effectiveModel.value,
      projection.mainOptions.value,
      projection.fields.value,
      projection.detailModel(f.detail),
      projection.detailFields(f.detail),
      projection.detailOptions(f.detail),
      projection.detailRelations(f.detail)
    ]
    const before = refs()
    source.record.record.values.name = '连续输入'
    const row = source.record.details.lines?.[0]
    if (!row) throw new Error('夹具缺少明细')
    row.values.value = '连续明细输入'
    const input = JSON.stringify(source.record)
    refs().forEach((value, index) => expect(value).toBe(before[index]))
    expect(JSON.stringify(source.record)).toBe(input)
    expect(projection.detailRelations(f.detail)).toEqual([f.detailRelation])
    expect(projection.detailOptions(f.detail).product?.generated).toBe(false)
    expect(source.model.object.details[0]?.fieldOptions.product?.generated).toBe(true)
  })

  it('主表和明细共用布局及权限，新增默认值与编辑清空语义保留', () => {
    const f = fixture()
    const projection = useRecordEditorProjection(f)
    const options = projection.mainOptions.value
    expect(recordDefaults(projection.fields.value, options, projection.effectiveModel.value, false)).toEqual({
      name: '默认名称',
      amount: '9007199254740993.001',
      parent: '7'
    })
    expect(
      recordDefaults(f.detail.fields, projection.detailOptions(f.detail), projection.detailModel(f.detail), false)
    ).toEqual({ value: '默认明细', product: '42' })
    expect(
      recordPayload(projection.fields.value, options, projection.effectiveModel.value, false, f.record.record.values)
    ).toMatchObject({ name: null, amount: '' })
    const row = f.record.details.lines?.[0]
    if (!row) throw new Error('夹具缺少明细')
    expect(
      recordPayload(
        f.detail.fields,
        projection.detailOptions(f.detail),
        projection.detailModel(f.detail),
        false,
        row.values
      )
    ).toEqual({ value: '原明细', product: '42' })
    expect(projection.effectiveModel.value.writeFields).not.toContain('locked')
    expect(projection.effectiveModel.value.writeFields).not.toContain('hidden')
    expect(projection.effectiveModel.value.writeFields).not.toContain('denied')
  })

  it('记录权限覆盖模型权限，CREATE/UPDATE和只读切换同步更新能力', () => {
    const f = fixture()
    f.record.record.permissions = {
      ...f.model.permissions,
      actions: ['CREATE'],
      writeFields: ['name'],
      writeDetails: []
    }
    const source = reactive({ ...f, readOnly: false })
    const projection = useRecordEditorProjection(source)
    expect(projection.effectiveModel.value.writable).toBe(false)
    source.record.record.id = null
    expect(projection.effectiveModel.value.writable).toBe(true)
    expect(projection.effectiveModel.value.writeFields).toEqual(['name'])
    expect(projection.detailModel(f.detail).writable).toBe(false)
    source.record.record.permissions = { ...source.model.permissions, actions: ['CREATE'] }
    expect(projection.detailModel(f.detail).writable).toBe(true)
    source.readOnly = true
    expect(projection.effectiveModel.value.writable).toBe(false)
    expect(projection.detailModel(f.detail).writable).toBe(false)
    source.readOnly = false
    source.form.options = { layout: 'vertical', submitText: '保存', readOnly: true }
    expect(projection.detailModel(f.detail).writable).toBe(false)
  })

  it('生命周期与上下文锁继续限制可写字段/明细，更新布局不修改记录', () => {
    const f = fixture()
    f.model.object.settings.documentPolicy = {
      rules: [],
      lifecycle: {
        fieldId: 'status',
        initialState: 'draft',
        actions: [],
        states: [{ code: 'draft', name: '草稿', lockedFields: ['amount'], lockedDetails: ['lines'], allowDelete: true }]
      }
    }
    const source = reactive({ ...f, lockedValues: { parent: '7' } })
    const projection = useRecordEditorProjection(source)
    const input = JSON.stringify(source.record)
    expect(projection.currentState.value).toBe('draft')
    expect(projection.effectiveModel.value.writeFields).toEqual(['name', 'choice'])
    expect(projection.detailModel(f.detail).writable).toBe(false)
    source.form.nodes = [node('name', true)]
    expect(projection.effectiveModel.value.writeFields).toEqual([])
    expect(JSON.stringify(source.record)).toBe(input)
  })

  it('旧布局补全授权多对多字段，显式关系布局及OUTGOING独立表单保持原排除范围', () => {
    const f = fixture()
    f.model.object.relations.push({ ...f.mainRelation, id: 'tags', fieldId: null, kind: RelationType.MANY_TO_MANY })
    f.record.record.permissions = { ...f.model.permissions, readRelations: ['tags'], writeRelations: ['tags'] }
    const source = reactive(f)
    const projection = useRecordEditorProjection(source)
    expect(boundFields(projection.formNodes.value || [])).toContain('relation_tags')
    expect(projection.effectiveModel.value.writeFields).toContain('relation_tags')
    source.form.options = { layout: 'vertical', submitText: '保存', relationLayout: true }
    expect(projection.effectiveModel.value.writeFields).not.toContain('relation_tags')
    source.form.relatedForms = [binding()]
    expect(projection.fields.value.map(value => value.id)).not.toContain('parent')
    expect(projection.mainOptions.value.parent?.generated).toBe(false)
  })
})

function relatedFixture() {
  const f = fixture()
  const result = ref<RelatedFormResult>({
    model: f.model,
    form: f.form,
    records: [f.record],
    multiple: true,
    linkFieldId: 'parent',
    required: false,
    truncated: false
  })
  const row = reactive<RelatedProjectionRow>({ key: 'row', aggregate: f.record })
  const rows = ref([row])
  const source = reactive({ binding: binding(), readOnly: false })
  const projection = useRelatedFormProjection(
    source,
    () => result.value,
    () => rows.value
  )
  return { ...f, result, row, rows, source, projection }
}

describe('独立关联表单投影', () => {
  it('关联行输入不重建模型/选项，不改变行身份、修订及填写值', () => {
    const f = relatedFixture()
    const refs = () => [
      f.projection.rowModel(f.row),
      f.projection.fields(f.row),
      f.projection.mainOptions.value,
      f.projection.detailModel(f.row, 'lines'),
      f.projection.detailRelations('lines'),
      f.projection.detailOptions('lines')
    ]
    const before = refs()
    f.row.aggregate.record.values.name = '手工填写'
    const row = f.row.aggregate.details.lines?.[0]
    if (!row) throw new Error('夹具缺少明细')
    row.values.value = '手工明细'
    refs().forEach((value, index) => expect(value).toBe(before[index]))
    expect(f.row.aggregate.record).toMatchObject({ id: 'record', revision: '5', values: { name: '手工填写' } })
    expect(row).toMatchObject({ id: 'line', revision: '3', clientRowKey: 'stable', values: { value: '手工明细' } })
  })

  it('逐行权限、目标主表/明细只读和静态布局共同决定写入能力', () => {
    const f = relatedFixture()
    f.row.aggregate.record.permissions = {
      ...f.model.permissions,
      actions: ['CREATE'],
      writeFields: ['name'],
      writeDetails: []
    }
    expect(f.projection.rowModel(f.row).writable).toBe(false)
    f.row.aggregate.record.id = null
    expect(f.projection.rowModel(f.row)).toMatchObject({ writable: true, writeFields: ['name'] })
    expect(f.projection.detailModel(f.row, 'lines').writable).toBe(false)
    f.row.aggregate.record.permissions = { ...f.model.permissions }
    expect(f.projection.detailModel(f.row, 'lines')).toMatchObject({
      writable: true,
      writeFields: ['value', 'product']
    })
    f.result.value.model.details.lines = { ...table(), writable: false }
    expect(f.projection.detailModel(f.row, 'lines').writable).toBe(false)
    f.result.value.model.details.lines = table()
    f.result.value.model.writable = false
    expect(f.projection.rowModel(f.row).writable).toBe(false)
    expect(f.projection.detailModel(f.row, 'lines').writable).toBe(false)
    f.result.value.model.writable = true
    f.source.readOnly = true
    expect(f.projection.detailModel(f.row, 'lines').writable).toBe(false)
  })

  it('INCOMING编辑排除连接字段，新增默认初始化范围保持；详情参考字段仍归一化', () => {
    const f = relatedFixture()
    f.source.binding.direction = 'INCOMING'
    expect(f.projection.fields(f.row).map(field => field.id)).not.toContain('parent')
    expect(f.projection.rowModel(f.row).writeFields).not.toContain('parent')
    expect(
      recordDefaults(f.model.object.fields, f.projection.mainOptions.value, f.projection.newModel(), false)
    ).toMatchObject({ parent: '7', amount: '9007199254740993.001' })
    expect(f.projection.detailOptions('lines').product?.generated).toBe(false)
    expect(f.projection.detailRelations('lines')).toEqual([f.detailRelation])
    f.result.value.form.detailNodes = { lines: [] }
    expect(f.projection.detailModel(f.row, 'lines').writeFields).toEqual([])
  })

  it('未加载上下文明确定义，加载后可用；目标缺少明细能力时仍不可写', () => {
    const f = fixture()
    const result = ref<RelatedFormResult>()
    const row: RelatedProjectionRow = { key: 'row', aggregate: f.record }
    const projection = useRelatedFormProjection(
      { binding: binding() },
      () => result.value,
      () => [row]
    )
    expect(projection.mainOptions.value).toEqual({})
    expect(projection.fields(row)).toEqual([])
    expect(() => projection.rowModel(row)).toThrow('上下文尚未就绪')
    result.value = {
      model: f.model,
      form: f.form,
      records: [],
      multiple: true,
      linkFieldId: 'parent',
      required: false,
      truncated: false
    }
    delete result.value.model.details.lines
    expect(projection.rowModel(row).writable).toBe(true)
    expect(projection.detailModel(row, 'lines').writable).toBe(false)
  })
})
