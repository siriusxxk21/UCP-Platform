import { describe, expect, it } from 'vitest'
import { defaultFieldOptions, editDesign, newDesign } from './data-center'
import { baseFieldNames, editDraft, validateDraft } from './object-draft'
import type { ObjectDesign } from '@/types/nocode/data-center'

describe('data center editing boundaries', () => {
  it('keeps internal details separate from global object creation', () => {
    const value = newDesign()
    expect(value.details).toEqual([])
    expect(value.relations).toEqual([])
    expect(value.draft.id).toBeNull()
  })
  it('does not mutate nested published field options or detail fields during editing', () => {
    const draft = newDesign().draft
    const source: ObjectDesign = {
      draft: {
        ...draft,
        id: '9007199254740993',
        objectCode: 'sample',
        objectName: '对象',
        tableName: 'nocode_data_sample',
        titleFieldId: draft.titleFieldKey,
        state: 'DRAFT',
        versionNo: 2,
        lockVersion: 5,
        updatedAt: ''
      },
      settings: { icon: null, ownerId: null, organizationId: null, titleTemplate: null },
      fieldOptions: {
        [draft.titleFieldKey]: {
          ...defaultFieldOptions(),
          options: [{ code: 'active', label: '启用', disabled: false }]
        }
      },
      details: [
        {
          id: '9007199254740994',
          code: 'items',
          name: '明细',
          tableName: 'nocode_data_sample_items',
          state: 'ACTIVE',
          fields: [...draft.fields],
          fieldOptions: {},
          indexes: []
        }
      ],
      relations: [],
      indexes: [],
      source: 'GENERATED',
      status: 'ACTIVE',
      schemaName: 'public',
      publishedVersion: 1,
      readOnly: false,
      versions: [],
      dependencies: []
    }
    const edit = editDesign(source)
    expect(edit.mainBinding?.keyColumn).toBe('id')
    expect(edit.details[0].binding?.parentColumn).toBe('parent_id')
    edit.details[0].fields[0].name = '改名'
    edit.fieldOptions[draft.titleFieldKey].options[0].label = '编辑中'
    expect(source.details[0].fields[0].name).toBe('名称')
    expect(source.fieldOptions[draft.titleFieldKey].options[0].label).toBe('启用')
    expect(edit.draft.expectedLockVersion).toBe(5)
    for (const name of baseFieldNames) {
      const changed = editDraft(source.draft)
      changed.fields.push({ ...changed.fields[0], key: 'another', code: name })
      expect(validateDraft(changed, false, source.draft.tableName)).toContain('底座公共字段')
    }
  })
  it('preserves a mixed binding and parent scoped indexes without mutating the source', () => {
    const draft = newDesign().draft
    const binding = {
      source: 'ADOPTED' as const,
      schemaName: 'legacy',
      keyColumn: 'line_key',
      parentColumn: 'order_key',
      structureMode: 'RETAIN' as const,
      readOnly: true,
      repairBaseFields: false,
      fingerprint: 'verified'
    }
    const source: ObjectDesign = {
      draft: {
        ...draft,
        id: '1',
        titleFieldId: draft.titleFieldKey,
        state: 'DRAFT',
        versionNo: 1,
        lockVersion: 1,
        updatedAt: ''
      },
      settings: newDesign().settings,
      fieldOptions: {},
      relations: [],
      indexes: [
        { id: '2', code: 'line_unique', name: '同订单编码', unique: true, fieldIds: ['3'], parentScoped: true }
      ],
      source: 'GENERATED',
      schemaName: 'public',
      publishedVersion: null,
      status: 'ACTIVE',
      readOnly: false,
      versions: [],
      dependencies: [],
      details: [
        {
          id: '4',
          code: 'items',
          name: '明细',
          tableName: 'order_lines',
          state: 'ACTIVE',
          fields: [],
          fieldOptions: {},
          indexes: [],
          binding
        }
      ]
    }
    const edit = editDesign(source)
    expect(edit.details[0].binding).toEqual(binding)
    expect(edit.indexes[0].parentScoped).toBe(true)
    edit.details[0].binding!.readOnly = false
    expect(source.details[0].binding?.readOnly).toBe(true)
  })
})
