import { describe, expect, it } from 'vitest'
import {
  editDraft,
  newDraft,
  newField,
  recordTitleFields,
  reconcileRecordTitle,
  removeField,
  setFieldType,
  validateDraft
} from './object-draft'
import type { ObjectDraft } from '@/types/nocode/object'

function saved(): ObjectDraft {
  return {
    id: '9007199254740993',
    objectCode: 'company',
    objectName: '公司',
    description: null,
    tableName: 'biz_company',
    titleFieldId: '9007199254740994',
    state: 'DRAFT',
    lockVersion: 7,
    versionNo: 1,
    updatedAt: '2026-09-05T22:00:00+08:00',
    fields: [{ ...newField(0), key: '9007199254740994', id: '9007199254740994', code: 'name', name: '公司名称' }]
  }
}
describe('object draft editing contract', () => {
  it('preserves a saved category when editing and keeps legacy objects uncategorized', () => {
    expect(newDraft().category).toBe('')
    expect(editDraft(saved()).category).toBe('')
    const source = { ...saved(), category: '基础资料' }
    const draft = editDraft(source)
    expect(draft.category).toBe('基础资料')
    draft.category = ''
    expect(source.category).toBe('基础资料')
  })
  it('repairs an asset type title removed when converting a new field to a relation', () => {
    const draft = editDraft(saved())
    const name = draft.fields[0]
    name.name = '资产名称'
    draft.fields.push({ ...newField(1), code: 'count', name: '数量', type: 'INTEGER', length: null })
    const type = { ...newField(2), code: 'asset_type', name: '资产类型' }
    draft.fields.push(type)
    draft.titleFieldKey = type.key
    // 新关系在保存草稿时生成真实引用列，原临时字段先从主表字段移除。
    draft.fields = draft.fields.filter(field => field.key !== type.key)
    expect(reconcileRecordTitle(draft)).toBe(true)
    expect(draft.titleFieldKey).toBe(name.key)
    expect(validateDraft(draft)).toBeNull()
    draft.fields.push({ ...newField(2), code: 'asset_type_id', name: '资产类型', type: 'INTEGER', length: null })
    expect(recordTitleFields(draft).map(field => field.name)).toEqual(['资产名称'])
    expect(reconcileRecordTitle(draft)).toBe(false)
  })
  it('preserves an explicit valid title and clears invalid titles when replacements are ambiguous', () => {
    const draft = editDraft(saved())
    draft.fields.push({ ...newField(1), code: 'alias', name: '别名' })
    const selected = draft.titleFieldKey
    expect(reconcileRecordTitle(draft)).toBe(false)
    expect(draft.titleFieldKey).toBe(selected)
    draft.titleFieldKey = 'removed-field'
    expect(reconcileRecordTitle(draft)).toBe(true)
    expect(draft.titleFieldKey).toBe('')
    expect(validateDraft(draft)).toContain('标题')
    draft.fields = []
    expect(reconcileRecordTitle(draft)).toBe(false)
  })
  it('clears a title after a type change and treats blank templates like the server', () => {
    const draft = editDraft(saved())
    setFieldType(draft.fields[0], 'INTEGER')
    draft.titleTemplate = '  '
    expect(recordTitleFields(draft)).toEqual([])
    expect(validateDraft(draft)).toContain('单行文本')
    expect(reconcileRecordTitle(draft)).toBe(true)
    expect(draft.titleFieldKey).toBe('')
    draft.titleTemplate = '{{name}}'
    expect(reconcileRecordTitle(draft)).toBe(true)
    expect(validateDraft(draft)).toBeNull()
    draft.titleFieldKey = 'missing'
    expect(validateDraft(draft)).toContain('存在')
  })
  it('requires generated table names to use the biz namespace', () => {
    const draft = editDraft(saved())
    for (const name of ['b_company', 'nocode_object', 'biz_', 'biz_1company', 'nocode_data_company']) {
      draft.tableName = name
      expect(validateDraft(draft)).toContain('biz_')
    }
    draft.tableName = 'biz_company'
    expect(validateDraft(draft)).toBeNull()
  })
  it('preserves large stable IDs and version without mutating the loaded response', () => {
    const source = saved()
    const draft = editDraft(source)
    draft.fields[0].name = '更名'
    expect(source.fields[0].name).toBe('公司名称')
    expect(draft.fields[0].id).toBe('9007199254740994')
    expect(draft.expectedLockVersion).toBe(7)
  })
  it('only preserves the exact previously saved legacy physical name', () => {
    const draft = editDraft({ ...saved(), tableName: 'nocode_data_company' })
    expect(validateDraft(draft, false, 'nocode_data_company')).toBeNull()
    draft.tableName = 'nocode_data_other'
    expect(validateDraft(draft, false, 'nocode_data_company')).toContain('biz_')
    draft.tableName = 'biz_company'
    expect(validateDraft(draft, false, 'nocode_data_company')).toBeNull()
  })
  it('explicitly removes an existing field once and invalidates its title pointer', () => {
    const draft = editDraft(saved())
    removeField(draft, draft.titleFieldKey)
    removeField(draft, '9007199254740994')
    expect(draft.removedFieldIds).toEqual(['9007199254740994'])
    expect(draft.titleFieldKey).toBe('')
    expect(validateDraft(draft)).not.toBeNull()
  })
  it('removing a new field never submits a fabricated database ID', () => {
    const draft = newDraft()
    removeField(draft, draft.titleFieldKey)
    expect(draft.removedFieldIds).toEqual([])
  })
  it('switching field types clears incompatible numeric and text constraints', () => {
    const field = newField(0)
    setFieldType(field, 'DECIMAL')
    expect([field.length, field.precision, field.scale]).toEqual([null, 18, 2])
    setFieldType(field, 'DATE')
    expect([field.length, field.precision, field.scale]).toEqual([null, null, null])
  })
  it('detects duplicate field codes and a title whose type changed', () => {
    const draft = editDraft(saved())
    expect(validateDraft(draft)).toBeNull()
    draft.fields.push({ ...newField(1), code: 'name', name: '重复' })
    expect(validateDraft(draft)).toContain('重复')
    draft.fields.pop()
    setFieldType(draft.fields[0], 'INTEGER')
    expect(validateDraft(draft)).toContain('标题')
  })
  it('allows a new text field to replace the title before the first save', () => {
    const draft = editDraft(saved())
    const replacement = { ...newField(1), code: 'title', name: '新标题' }
    removeField(draft, draft.titleFieldKey)
    draft.fields.push(replacement)
    draft.titleFieldKey = replacement.key
    expect(validateDraft(draft)).toBeNull()
    expect(draft.removedFieldIds).toEqual(['9007199254740994'])
  })
})
