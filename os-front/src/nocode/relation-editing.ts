import * as NC from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions, ObjectRelation, SaveDesign } from '@/types/nocode/data-center'
import { baseFieldNames } from './object-draft'
import { changeFieldType } from './field-editing'
import { defaultFieldOptions } from './data-center'

const relationKey = (relation: ObjectRelation) => `relation:${relation.id ?? relation.code}`
export const isRelationField = (field: ObjectField) => field.key.startsWith('relation:')
export const relationFieldLabel = (relation: ObjectRelation) =>
  relation.kind === NC.RelationType.MANY_TO_MANY ? '多选（对象引用）' : '单选（对象引用）'

export function relationForField(field: ObjectField, relations: ObjectRelation[] = []) {
  return relations.find(
    relation =>
      relationKey(relation) === field.key ||
      relation.fieldId === field.key ||
      (!!field.id && relation.fieldId === field.id)
  )
}

/** 关系生成的字段统一通过关系维护，避免字段编辑结果在保存时被关系定义覆盖。 */
export function relationManagedField(
  field: ObjectField,
  options: Record<string, FieldOptions>,
  relations: ObjectRelation[] = []
): boolean {
  return (
    isRelationField(field) ||
    ((!!options[field.key]?.generated || field.type === NC.FieldType.REFERENCE) && !!relationForField(field, relations))
  )
}

/** 未生成的单选引用和不占主表列的多选关系也展示为字段；展示行不进入物理字段保存请求。 */
export function relationFieldRows(
  fields: ObjectField[],
  relations: ObjectRelation[] = [],
  options: Record<string, FieldOptions> = {}
): ObjectField[] {
  return [
    ...fields.map(field => {
      const relation = relationForField(field, relations)
      return relation && relationManagedField(field, options, relations)
        ? {
            ...field,
            name: relation.name,
            required: relation.required || relation.kind === NC.RelationType.MASTER_DETAIL,
            unique: relation.kind === NC.RelationType.ONE_TO_ONE
          }
        : field
    }),
    ...relations
      .filter(relation => !relation.fieldId)
      .map((relation, index) => ({
        key: relationKey(relation),
        id: null,
        code: relation.code,
        name: relation.name,
        type: relation.kind === NC.RelationType.MANY_TO_MANY ? NC.FieldType.MULTI_SELECT : NC.FieldType.SELECT,
        length: null,
        precision: null,
        scale: null,
        required: relation.required || relation.kind === NC.RelationType.MASTER_DETAIL,
        unique: relation.kind === NC.RelationType.ONE_TO_ONE,
        sort: fields.length + index
      }))
  ]
}

/** 统一解析关系来源；未保存明细使用 detail:编码，保存后回写稳定 ID。 */
export function relationSource(design: SaveDesign, sourceDetailId?: string | null) {
  if (!sourceDetailId)
    return { fields: design.draft.fields, fieldOptions: design.fieldOptions, indexes: design.indexes }
  return design.details.find(detail => (detail.id || `detail:${detail.code}`) === sourceDetailId)
}

/** 只提供可映射的主表列；新关系没有 ID，需用编辑位置区分，不能把所有新关系视为同一条。 */
export function relationReferenceFields(
  design: SaveDesign,
  position: number,
  pending?: ObjectField,
  sourceDetailId?: string | null
): ObjectField[] {
  const source = relationSource(design, sourceDetailId)
  return (source?.fields || []).filter(field => {
    const option = source?.fieldOptions[field.key]
    return (
      field.key !== pending?.key &&
      !option?.primaryKey &&
      !option?.generated &&
      !baseFieldNames.has(option?.columnName ?? field.code) &&
      (
        [
          NC.FieldType.TEXT,
          NC.FieldType.TEXTAREA,
          NC.FieldType.INTEGER,
          NC.FieldType.UUID,
          NC.FieldType.REFERENCE
        ] as string[]
      ).includes(field.type) &&
      !design.relations.some((relation, index) => index !== position && relation.fieldId === field.key)
    )
  })
}

export function newFieldRelation(field: ObjectField): ObjectRelation {
  return {
    id: null,
    code: field.code,
    name: field.name,
    kind:
      field.type === NC.FieldType.MULTI_SELECT
        ? NC.RelationType.MANY_TO_MANY
        : field.unique
          ? NC.RelationType.ONE_TO_ONE
          : NC.RelationType.REFERENCE,
    targetObjectId: '',
    fieldId: null,
    targetFieldId: null,
    required: field.required,
    onDelete: NC.DeletePolicy.RESTRICT
  }
}

export function fieldRelationConversionError(field: ObjectField): string | null {
  if (field.id && field.type === NC.FieldType.MULTI_SELECT)
    return '已保存多选字段使用数组存储，当前暂不支持原列转为多对多关系；可新增多选对象关系'
  if (field.type === NC.FieldType.MULTI_SELECT && field.unique) return '业务对象多选不支持字段唯一约束，请先取消“唯一”'
  return null
}

/** 与服务端关系约束一致，先在弹窗内校验，失败时不替换原选择字段。 */
export function relationConfigurationError(relation: ObjectRelation): string | null {
  if (!relation.name.trim() || relation.name.length > 128) return '请填写关系名称，最多 128 字符'
  if (!/^[a-z][a-z0-9_]{0,49}$/.test(relation.code))
    return '关系编码须以小写字母开头，仅含小写字母、数字、下划线，最多 50 字符'
  if (!relation.targetObjectId) return '请选择目标对象'
  if (
    relation.sourceDetailId &&
    (relation.kind !== NC.RelationType.REFERENCE || relation.onDelete !== NC.DeletePolicy.RESTRICT)
  )
    return '内部明细支持单值对象引用，删除策略为阻止删除'
  if (!Object.values(NC.RelationType).includes(relation.kind)) return '关系类型无效'
  if (!(Object.values(NC.DeletePolicy) as string[]).includes(relation.onDelete)) return '删除策略无效'
  if (relation.onDelete === NC.DeletePolicy.SET_NULL) {
    if (relation.required || relation.kind === NC.RelationType.MASTER_DETAIL)
      return '必填引用不能在删除时清空，请调整必填或删除策略'
    if (relation.kind === NC.RelationType.MANY_TO_MANY) return '多对多关系不支持清空引用列，请选择阻止删除'
  }
  if (relation.onDelete === NC.DeletePolicy.CASCADE && relation.kind !== NC.RelationType.MASTER_DETAIL)
    return '只有主从关系允许级联删除'
  return null
}

/** 主表删列等其他入口也必须检查关系，避免把断开的引用推迟到后端保存时报错。 */
export function designRelationsError(design: SaveDesign): string | null {
  const bound = new Set<string>()
  const codes = new Set<string>()
  for (const relation of design.relations) {
    const configurationError = relationConfigurationError(relation)
    if (configurationError) return `关系“${relation.name || '未命名'}”：${configurationError}`
    if (codes.has(relation.code)) return `关系“${relation.name}”的编码重复`
    codes.add(relation.code)
    if (relation.kind === NC.RelationType.MANY_TO_MANY || !relation.fieldId) continue
    const source = relationSource(design, relation.sourceDetailId)
    if (!source) return `关系“${relation.name}”的来源明细不存在`
    const field = source.fields.find(field => field.key === relation.fieldId || field.id === relation.fieldId)
    if (!field) return `关系“${relation.name}”的引用列已不存在，请重新配置引用列`
    if (bound.has(field.key)) return `关系“${relation.name}”与其他关系重复绑定了同一字段`
    bound.add(field.key)
  }
  return null
}

/** 配置只改草稿；已保存字段保留身份和物理列，历史值由发布计划检查并确认清空。 */
export function applyRelation(
  design: SaveDesign,
  relation: ObjectRelation,
  position: number,
  pending?: ObjectField
): string | null {
  const configurationError = relationConfigurationError(relation)
  if (configurationError) return configurationError
  if (design.relations.some((item, index) => index !== position && item.code === relation.code)) return '关系编码重复'
  const source = relationSource(design, relation.sourceDetailId)
  if (!source) return '请选择有效的来源主表或内部明细'
  const conversionError = pending && fieldRelationConversionError(pending)
  if (conversionError) return conversionError
  if (pending && !pending.id && source.indexes.some(index => index.fieldIds.includes(pending.key)))
    return '请先移除该选择字段的索引，再配置对象关系'
  const value = { ...relation, sourceDetailId: relation.sourceDetailId?.trim() || null }
  // 已保存单值字段复用原列；尚未保存的临时字段仍交给关系生成兼容列。
  const retained = pending?.id ? source.fields.find(field => field.id === pending.id) : undefined
  if (pending?.id && !retained) return '原字段已不存在，请重新打开字段配置'
  if (pending) value.fieldId = retained?.id ?? null
  if (value.kind === NC.RelationType.MANY_TO_MANY) value.fieldId = null
  if (value.fieldId && !source.fields.some(field => field.key === value.fieldId || field.id === value.fieldId))
    return '引用列已不存在，请重新选择主表字段或留空自动生成'
  if (
    !value.id &&
    value.fieldId &&
    !retained &&
    !relationReferenceFields(design, position, undefined, value.sourceDetailId).some(
      field => field.key === value.fieldId
    )
  )
    return '该字段不能作为引用列，或已被其他关系占用'
  if (
    !value.fieldId &&
    value.kind !== NC.RelationType.MANY_TO_MANY &&
    !value.id &&
    source.fields.some(field => field.key !== pending?.key && field.code === `${value.code}_id`)
  )
    return '关系生成的字段编码已存在，请修改关系编码'
  if (pending && retained) {
    const option = source.fieldOptions[retained.key] ?? defaultFieldOptions()
    changeFieldType(retained, option, NC.FieldType.REFERENCE)
    retained.name = value.name
    retained.required = value.required || value.kind === NC.RelationType.MASTER_DETAIL
    retained.unique = value.kind === NC.RelationType.ONE_TO_ONE
    // 保留现有数据库能力声明，目标真实主键由后端保存关系时校准。
    source.fieldOptions[retained.key] = option
  } else if (pending) {
    if (value.sourceDetailId) source.fields = source.fields.filter(field => field.key !== pending.key)
    else design.draft.fields = source.fields.filter(field => field.key !== pending.key)
    delete source.fieldOptions[pending.key]
  }
  if (position < 0) design.relations.push(value)
  else design.relations[position] = value
  return null
}
