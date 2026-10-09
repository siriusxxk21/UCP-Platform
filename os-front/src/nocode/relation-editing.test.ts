import { describe, expect, it } from 'vitest'
import * as NC from '@/types/nocode/enums'
import { defaultFieldOptions, newDesign } from './data-center'
import { newField } from './object-draft'
import { designFieldsError } from './field-editing'
import {
  applyRelation,
  designRelationsError,
  fieldRelationConversionError,
  isRelationField,
  newFieldRelation,
  relationFieldRows,
  relationFieldLabel,
  relationManagedField,
  relationConfigurationError,
  relationForField,
  relationReferenceFields
} from './relation-editing'

describe('选择字段转为对象关系', () => {
  it('手动选择主表后归一为空值，保存重开仍可编辑；未知明细不能作为主表绕过校验', () => {
    const design = newDesign()
    const relation = { ...newFieldRelation(newField(1, '供应商')), targetObjectId: '20', sourceDetailId: '' }
    expect(applyRelation(design, relation, -1)).toBeNull()
    expect(design.relations[0].sourceDetailId).toBeNull()
    expect(applyRelation(design, { ...design.relations[0], sourceDetailId: '' }, 0)).toBeNull()
    expect(design.relations).toHaveLength(1)
    expect(applyRelation(design, { ...relation, code: 'other', sourceDetailId: 'missing' }, -1)).toContain('有效的来源')
  })
  for (const type of [NC.FieldType.SELECT, NC.FieldType.MULTI_SELECT]) {
    it(`${type} 配置后保留可见字段，添加其他字段保存不提交已替换的临时引用列`, () => {
      const design = newDesign()
      const field = { ...newField(1, '资产分类'), type }
      design.draft.fields.push(field)
      design.fieldOptions[field.key] = defaultFieldOptions()
      const relation = { ...newFieldRelation(field), targetObjectId: '20' }
      expect(relation.kind).toBe(
        type === NC.FieldType.SELECT ? NC.RelationType.REFERENCE : NC.RelationType.MANY_TO_MANY
      )
      expect(relationReferenceFields(design, -1, field)).not.toContain(field)
      // 兼容旧弹窗已误选自身的状态，转换时必须清除该临时 key。
      relation.fieldId = field.key
      expect(applyRelation(design, relation, -1, field)).toBeNull()
      design.draft.fields.push({ ...newField(2, '资产数量'), type: NC.FieldType.INTEGER, length: null })
      expect(designFieldsError(design)).toBeNull()
      expect(designRelationsError(design)).toBeNull()
      expect(design.relations[0].fieldId).toBeNull()
      expect(JSON.stringify(design)).not.toContain(field.key)
      const rows = relationFieldRows(design.draft.fields, design.relations)
      const row = rows.find(item => item.name === field.name)!
      expect(row.type).toBe(type)
      expect(isRelationField(row)).toBe(true)
      expect(relationForField(row, design.relations)).toBe(design.relations[0])
      expect(design.draft.fields).not.toContain(row)
    })
  }

  it('单选保存取得真实列后只展示一行，多对多保存重载后仍可见并能重新配置', () => {
    const field = { ...newField(1, '分类'), id: '40', key: '40', type: NC.FieldType.INTEGER }
    const single = { ...newFieldRelation(field), id: '50', fieldId: '40', targetObjectId: '20' }
    const multi = { ...single, id: '51', code: 'tags', name: '标签', fieldId: null, kind: NC.RelationType.MANY_TO_MANY }
    const rows = relationFieldRows([field], [single, multi])
    expect(rows).toHaveLength(2)
    expect(relationForField(rows[0], [single, multi])).toBe(single)
    expect(relationForField(rows[1], [single, multi])).toBe(multi)
    expect(rows[1].type).toBe(NC.FieldType.MULTI_SELECT)
  })

  it('打开及取消关系配置不修改字段；校验失败不移除字段', () => {
    const design = newDesign()
    const field = { ...newField(1, '分类'), type: NC.FieldType.SELECT }
    design.draft.fields.push(field)
    const before = JSON.stringify(design)
    const relation = newFieldRelation(field)
    relationReferenceFields(design, -1, field)
    relationFieldRows(design.draft.fields, design.relations)
    expect(JSON.stringify(design)).toBe(before)
    expect(applyRelation(design, relation, -1, field)).toContain('目标对象')
    expect(JSON.stringify(design)).toBe(before)
  })

  it('重复关系编码、已有列冲突及索引引用均在替换字段前拦截', () => {
    for (const conflict of ['relation', 'column', 'index']) {
      const design = newDesign()
      const field = { ...newField(1, '分类'), type: NC.FieldType.SELECT }
      design.draft.fields.push(field)
      const relation = { ...newFieldRelation(field), targetObjectId: '20' }
      if (conflict === 'relation') design.relations.push(relation)
      if (conflict === 'column') design.draft.fields.push({ ...newField(2, '重复列'), code: `${relation.code}_id` })
      if (conflict === 'index')
        design.indexes.push({ id: null, code: 'idx', name: '索引', unique: false, fieldIds: [field.key] })
      const before = JSON.stringify(design)
      expect(applyRelation(design, relation, -1, field)).not.toBeNull()
      expect(JSON.stringify(design)).toBe(before)
    }
  })

  it('多个未保存关系不能重复绑定同一列，编辑当前关系仍可保留其列', () => {
    const design = newDesign()
    const field = { ...newField(1, '引用'), type: NC.FieldType.INTEGER }
    design.draft.fields.push(field)
    design.relations.push({ ...newFieldRelation(field), fieldId: field.key, targetObjectId: '20' })
    expect(relationReferenceFields(design, -1)).not.toContain(field)
    expect(relationReferenceFields(design, 0)).toContain(field)
    expect(relationReferenceFields(design, 1)).not.toContain(field)
    expect(applyRelation(design, { ...design.relations[0], code: 'another' }, -1)).toContain('占用')
  })

  it('下拉候选排除系统列、生成列及不能映射的选项列', () => {
    const design = newDesign()
    const system = { ...newField(1, '系统'), code: 'creator' }
    const generated = newField(2, '生成')
    const choice = { ...newField(3, '单选'), type: NC.FieldType.SELECT }
    design.draft.fields.push(system, generated, choice)
    design.fieldOptions[generated.key] = { ...defaultFieldOptions(), generated: true }
    expect(relationReferenceFields(design, -1)).toEqual([design.draft.fields[0]])
  })

  it('断开的引用列在提交前可定位；多对多不保留旧单选列', () => {
    const design = newDesign()
    const relation = { ...newFieldRelation(newField(1, '关联')), fieldId: 'missing', targetObjectId: '20' }
    expect(applyRelation(design, relation, -1)).toContain('引用列已不存在')
    expect(design.relations).toHaveLength(0)
    expect(applyRelation(design, { ...relation, kind: NC.RelationType.MANY_TO_MANY }, -1)).toBeNull()
    expect(design.relations[0].fieldId).toBeNull()
  })

  it('已保存单选转引用保留字段与物理列身份，清理旧选择配置且交发布检查数据', () => {
    const design = newDesign()
    const field = { ...newField(1, '已有分类'), id: '41', key: '41', type: NC.FieldType.SELECT }
    design.draft.fields.push(field)
    design.fieldOptions[field.key] = {
      ...defaultFieldOptions(),
      columnName: 'original_choice',
      nativeType: 'varchar(80)',
      defaultValue: 'legacy',
      options: [{ code: 'legacy', label: '旧项', disabled: false }],
      selection: {
        kind: 'LOCAL_OPTIONS',
        directory: null,
        dictionaryType: null,
        rootIds: [],
        includeDescendants: false,
        organizationTypes: [],
        defaultMode: 'NONE'
      }
    }
    design.indexes.push({ id: 'ix1', code: 'choice', name: '索引', unique: false, fieldIds: ['41'] })
    expect(applyRelation(design, { ...newFieldRelation(field), targetObjectId: '20' }, -1, field)).toBeNull()
    expect(design.draft.fields.at(-1)).toMatchObject({ id: '41', key: '41', code: field.code, type: 'REFERENCE' })
    expect(design.fieldOptions['41']).toMatchObject({
      columnName: 'original_choice',
      nativeType: 'varchar(80)',
      defaultValue: null,
      selection: null,
      options: []
    })
    expect(design.relations[0]).toMatchObject({ fieldId: '41', targetObjectId: '20' })
    expect(relationManagedField(field, design.fieldOptions, design.relations)).toBe(true)
    expect(designRelationsError(design)).toBeNull()
    expect(designFieldsError(design)).toBeNull()
  })

  it('已有多选不能把数组列当作多对多存储，也不会改动原字段', () => {
    const design = newDesign()
    const field = { ...newField(1, '已有分类'), id: '41', key: '41', type: NC.FieldType.MULTI_SELECT }
    design.draft.fields.push(field)
    const before = JSON.stringify(design)
    expect(applyRelation(design, { ...newFieldRelation(field), targetObjectId: '20' }, -1, field)).toContain('多对多')
    expect(JSON.stringify(design)).toBe(before)
  })

  it('统一保存校验定位删列后的断开关系和重复绑定', () => {
    const design = newDesign()
    const field = design.draft.fields[0]
    const relation = { ...newFieldRelation(field), fieldId: field.key, targetObjectId: '20' }
    design.relations.push(relation)
    expect(designRelationsError(design)).toBeNull()
    design.relations.push({ ...relation, code: 'other', name: '重复引用' })
    expect(designRelationsError(design)).toContain('重复绑定')
    design.draft.fields = []
    expect(designRelationsError(design)).toContain('引用列已不存在')
  })

  it('生成引用列的名称和必填立即跟随关系编辑，展示过程不改写物理字段', () => {
    const field = { ...newField(0, '旧名称'), id: '40', key: '40', type: NC.FieldType.INTEGER }
    const relation = { ...newFieldRelation(field), id: '50', fieldId: '40', name: '新名称', required: true }
    const options = { '40': { ...defaultFieldOptions(), generated: true } }
    const row = relationFieldRows([field], [relation], options)[0]
    expect(row).toMatchObject({ name: '新名称', required: true, key: '40', type: NC.FieldType.INTEGER })
    expect(field.name).toBe('旧名称')
    expect(relationManagedField(row, options, [relation])).toBe(true)
    expect(relationManagedField(field, {}, [relation])).toBe(false)
    expect(relationFieldLabel(relation)).toBe('单选（对象引用）')
    expect(relationFieldLabel({ ...relation, kind: NC.RelationType.MANY_TO_MANY })).toBe('多选（对象引用）')
  })

  it('单选唯一约束对应一对一，多选唯一约束明确提示，不能静默丢弃', () => {
    const field = { ...newField(0, '选择'), type: NC.FieldType.SELECT, unique: true }
    expect(newFieldRelation(field).kind).toBe(NC.RelationType.ONE_TO_ONE)
    expect(fieldRelationConversionError(field)).toBeNull()
    expect(fieldRelationConversionError({ ...field, type: NC.FieldType.MULTI_SELECT })).toContain('唯一')
  })

  it('关系必填与清空策略冲突时留在配置内，原字段保持不变', () => {
    const design = newDesign()
    const field = { ...newField(1, '分类'), type: NC.FieldType.SELECT, required: true }
    design.draft.fields.push(field)
    const relation = { ...newFieldRelation(field), targetObjectId: '20', onDelete: NC.DeletePolicy.SET_NULL }
    const before = JSON.stringify(design)
    expect(applyRelation(design, relation, -1, field)).toContain('必填引用')
    expect(JSON.stringify(design)).toBe(before)
    expect(relationConfigurationError({ ...relation, required: false })).toBeNull()
    expect(relationConfigurationError({ ...relation, required: false, kind: NC.RelationType.MANY_TO_MANY })).toContain(
      '多对多'
    )
    expect(relationConfigurationError({ ...relation, onDelete: NC.DeletePolicy.CASCADE })).toContain('主从')
  })
  it('内部明细的引用字段使用明细自身的字段、索引和稳定来源，主表不被改写', () => {
    const design = newDesign()
    const field = { ...newField(1, '科目'), type: NC.FieldType.SELECT }
    const detail = {
      id: null,
      code: 'entries',
      name: '分录',
      tableName: 'biz_entries',
      state: NC.MemberState.ACTIVE,
      fields: [field],
      fieldOptions: { [field.key]: defaultFieldOptions() },
      indexes: []
    }
    design.details.push(detail)
    const mainBefore = JSON.stringify(design.draft.fields)
    const relation = { ...newFieldRelation(field), sourceDetailId: 'detail:entries', targetObjectId: '20' }
    expect(applyRelation(design, relation, -1, field)).toBeNull()
    expect(design.details[0].fields).toEqual([])
    expect(JSON.stringify(design.draft.fields)).toBe(mainBefore)
    expect(design.relations[0].sourceDetailId).toBe('detail:entries')
    expect(designRelationsError(design)).toBeNull()
    expect(relationConfigurationError({ ...relation, kind: NC.RelationType.MANY_TO_MANY })).toContain('内部明细')
    expect(relationConfigurationError({ ...relation, onDelete: NC.DeletePolicy.SET_NULL })).toContain('内部明细')
  })
  it('内部明细不能选主表字段作为自己的引用列', () => {
    const design = newDesign()
    design.details.push({
      id: 'd1',
      code: 'entries',
      name: '分录',
      tableName: 'biz_entries',
      state: NC.MemberState.ACTIVE,
      fields: [],
      fieldOptions: {},
      indexes: []
    })
    const field = design.draft.fields[0]
    expect(
      applyRelation(
        design,
        { ...newFieldRelation(field), sourceDetailId: 'd1', fieldId: field.key, targetObjectId: '20' },
        -1
      )
    ).toContain('引用列已不存在')
    expect(relationReferenceFields(design, -1, undefined, 'd1')).toEqual([])
  })
})
