import { describe, expect, it } from 'vitest'
import type { ApplicationResource, PublishedDefinition } from '@/types/nocode/application'
import { NodeKind, type FormConfig } from '@/types/nocode/application-ui'
import { FieldType, MemberState } from '@/types/nocode/enums'
import {
  createObjectForm,
  copyObjectForm,
  formUsage,
  formRemovalReason,
  setObjectDefaultForm,
  needsDefaultForm
} from './default-form-config'
import { isDefaultForm, resolveViewForm } from './default-form'
import { boundFields } from './application-ui'

const definition = {
  objectId: 'orders',
  objectName: '订单',
  readOnly: false,
  fields: [
    { id: 'name', name: '名称', type: FieldType.TEXT },
    { id: 'memo', name: '说明', type: FieldType.TEXTAREA },
    { id: 'old', name: '旧字段', type: FieldType.TEXT }
  ],
  fieldOptions: { old: { state: MemberState.INACTIVE } },
  relations: [],
  details: [
    {
      id: 'items',
      state: MemberState.ACTIVE,
      fields: [{ id: 'quantity', name: '数量', type: FieldType.INTEGER }],
      fieldOptions: {}
    }
  ]
} as unknown as PublishedDefinition
const view = (id: string, formId: string | null = null): ApplicationResource => ({
  id,
  kind: 'VIEW',
  name: id,
  code: id,
  config: { objectId: 'orders', formId }
})

describe('应用内默认表单配置边界', () => {
  it('用当前对象字段和启用明细生成独立资源，保留默认布局和稳定字段 ID', () => {
    const original = JSON.stringify(definition)
    const form = createObjectForm(definition, [], true)
    const config = form.config as unknown as FormConfig
    expect(isDefaultForm(form)).toBe(true)
    expect(boundFields(config.nodes)).toEqual(['name', 'memo'])
    expect(config.nodes.some(node => node.type === NodeKind.INTERNAL_DETAIL && node.detail?.detailId === 'items')).toBe(
      true
    )
    expect(boundFields(config.detailNodes!.items!)).toEqual(['quantity'])
    expect(JSON.stringify(definition)).toBe(original)
    expect(form.code).toMatch(/^[a-z][a-z0-9_]{0,63}$/)
    expect(createObjectForm(definition, [form], true).code).not.toBe(form.code)
  })
  it('复制保留全部表单配置但清除默认身份，改副本不会污染原表单', () => {
    const form = createObjectForm(definition, [], true)
    form.config.relatedForms = [{ id: 'related', formId: 'external' }]
    const copy = copyObjectForm(form, [form])
    expect(copy.id).not.toBe(form.id)
    expect(isDefaultForm(copy)).toBe(false)
    expect(copy.config.relatedForms).toEqual(form.config.relatedForms)
    ;(copy.config as unknown as FormConfig).nodes[0]!.children = []
    expect((form.config as unknown as FormConfig).nodes[0]!.children).not.toEqual([])
  })
  it('更换默认仅改变同对象默认标记，不改固定绑定、其他对象或原快照', () => {
    const first = createObjectForm(definition, [], true)
    const second = createObjectForm(definition, [first])
    const other = createObjectForm({ ...definition, objectId: 'customers' }, [], true)
    const inherited = view('inherited'),
      fixed = view('fixed', first.id)
    const resources = [first, second, other, inherited, fixed]
    const before = JSON.stringify(resources)
    const updated = setObjectDefaultForm(resources, second.id)
    expect(resolveViewForm(updated, 'orders')?.id).toBe(second.id)
    expect(resolveViewForm(updated, 'orders', first.id)?.id).toBe(first.id)
    expect(resolveViewForm(updated, 'customers')?.id).toBe(other.id)
    expect(updated.find(resource => resource.id === 'fixed')?.config.formId).toBe(first.id)
    expect(JSON.stringify(resources)).toBe(before)
  })
  it('引用识别覆盖列表沿用、显式绑定、页面按钮、任务和关联表单，不按名称误判', () => {
    const form = createObjectForm(definition, [], true)
    const page: ApplicationResource = {
      id: 'page',
      kind: 'PAGE',
      name: '页面',
      code: 'page',
      config: {
        nodes: [{ action: { resourceId: form.id } }, { taskView: { businessFormId: form.id } }]
      }
    }
    const related: ApplicationResource = {
      id: 'related',
      kind: 'FORM',
      name: '关联',
      code: 'related',
      config: {
        relatedForms: [{ formId: form.id }]
      }
    }
    const sameName = { ...view('unrelated', 'another'), name: form.id }
    const resources = [form, view('inherited'), view('fixed', form.id), page, related, sameName]
    expect(formUsage(resources, form).map(resource => resource.id)).toEqual(['inherited', 'fixed', 'page', 'related'])
    expect(formRemovalReason(resources, form)).toContain('设为默认')
    const ordinary = copyObjectForm(form, resources)
    expect(formRemovalReason([ordinary, view('fixed', ordinary.id)], ordinary)).toContain('fixed')
    expect(formRemovalReason([ordinary], ordinary)).toBe('')
  })
  it('只为可录入普通列表准备默认，不重复生成、不替换显式或只读配置', () => {
    const target = view('new')
    expect(needsDefaultForm([], target, definition)).toBe(true)
    expect(needsDefaultForm([createObjectForm(definition, [], true)], target, definition)).toBe(false)
    expect(needsDefaultForm([], view('fixed', 'form'), definition)).toBe(false)
    expect(needsDefaultForm([], target, { ...definition, readOnly: true })).toBe(false)
    target.config.interaction = { buttons: ['VIEW'] }
    expect(needsDefaultForm([], target, definition)).toBe(false)
    target.config.composition = { grain: 'DETAIL' }
    expect(needsDefaultForm([], target, definition)).toBe(false)
  })
})
