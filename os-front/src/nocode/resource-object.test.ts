import { describe, expect, it } from 'vitest'
import { objectResourceConfig } from './resource-object'
import type { ApplicationResource, PublishedDefinition } from '@/types/nocode/application'
import { RecordOpenMode, ViewButton, type FormConfig, type ViewConfig } from '@/types/nocode/application-ui'
import { FieldType, MemberState } from '@/types/nocode/enums'

const definition = {
  objectId: 'new',
  fields: [{ id: 'new_field', key: 'new_field', name: '新字段', code: 'name', type: FieldType.TEXT }],
  fieldOptions: {},
  details: [{ id: 'new_detail', state: MemberState.ACTIVE }],
  relations: []
} as unknown as PublishedDefinition
const interaction = {
  buttons: [ViewButton.VIEW, ViewButton.UPDATE],
  actionIds: ['new_action'],
  editMode: RecordOpenMode.MODAL,
  detailMode: RecordOpenMode.DRAWER
}

describe('资源切换对象的完整配置边界', () => {
  it('重建表单绑定而保留展示选项，不把旧关联、明细或布局带到新对象', () => {
    const resource: ApplicationResource = {
      id: 'form',
      kind: 'FORM',
      name: '资料',
      code: 'form',
      config: {
        objectId: 'new',
        nodes: [{ fieldId: 'old_field' }],
        detailIds: ['old_detail'],
        detailNodes: { old_detail: [{ fieldId: 'old_detail_field' }] },
        relatedForms: [{ relationId: 'old_relation' }],
        options: { layout: 'horizontal', submitText: '登记', readOnly: true, relationLayout: true }
      }
    }
    const config = objectResourceConfig(resource, definition, interaction) as unknown as FormConfig
    expect(config.nodes.map(n => n.fieldId)).toEqual(['new_field'])
    expect(config.detailIds).toEqual(['new_detail'])
    expect(config.detailNodes).toEqual({})
    expect(config.relatedForms).toEqual([])
    expect(config.options).toEqual(resource.config.options)
    expect(config.options).not.toBe(resource.config.options)
    expect(resource.config.relatedForms).toEqual([{ relationId: 'old_relation' }])
  })
  it('视图清除旧组合、关联表单和筛选，保持分页及排序方向能力', () => {
    const resource: ApplicationResource = {
      id: 'view',
      kind: 'VIEW',
      name: '列表',
      code: 'view',
      config: {
        objectId: 'new',
        fieldIds: ['old'],
        pageSize: 50,
        descending: false,
        composition: { grain: 'DETAIL', detailId: 'old_detail' },
        formId: 'old_form',
        detailPageId: 'old_page',
        equal: { old: 'value' },
        filterDictionaries: { old: 'dictionary' },
        query: { defaults: { old: 'value' } }
      }
    }
    const config = objectResourceConfig(resource, definition, interaction) as unknown as ViewConfig
    expect(config).toMatchObject({
      objectId: 'new',
      fieldIds: ['new_field'],
      pageSize: 50,
      descending: false,
      composition: null,
      formId: null,
      detailPageId: null,
      sortFieldId: null,
      equal: {},
      filterDictionaries: {},
      query: { fixed: [], defaults: {}, candidates: {} },
      interaction
    })
    expect(JSON.stringify(config)).not.toContain('old')
  })
})
