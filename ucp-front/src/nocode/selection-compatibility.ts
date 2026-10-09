import type { PublishedDefinition } from '@/types/nocode/application'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import { fieldRelation, linkableField } from './business-fields'
import { selectFillCompatible } from './form-fill'

const numericTypes: string[] = [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT]

/** 联动比较记录身份与值编码，不能因为两个无关引用都存储数字就允许互相筛选。 */
export function selectionLinkCompatible(
  sourceDefinition: PublishedDefinition,
  sourceFieldId: string,
  targetDefinition: PublishedDefinition,
  targetFieldId: string
): boolean {
  const source = sourceDefinition.fields.find(f => f.id === sourceFieldId)
  const target = targetDefinition.fields.find(f => f.id === targetFieldId)
  if (
    !source ||
    !target ||
    !linkableField(source) ||
    !linkableField(target) ||
    sourceDefinition.fieldOptions[sourceFieldId]?.state === MemberState.INACTIVE ||
    targetDefinition.fieldOptions[targetFieldId]?.state === MemberState.INACTIVE
  )
    return false
  const sourceRelation = fieldRelation(sourceDefinition.relations, sourceFieldId)
  const targetRelation = fieldRelation(targetDefinition.relations, targetFieldId)
  if (sourceRelation || targetRelation)
    return (
      !!sourceRelation &&
      !!targetRelation &&
      sourceRelation.kind !== RelationType.MANY_TO_MANY &&
      targetRelation.kind !== RelationType.MANY_TO_MANY &&
      sourceRelation.targetObjectId === targetRelation.targetObjectId
    )
  const sourceType =
    source.type === FieldType.FORMULA ? sourceDefinition.fieldOptions[sourceFieldId]?.resultType : source.type
  const targetType =
    target.type === FieldType.FORMULA ? targetDefinition.fieldOptions[targetFieldId]?.resultType : target.type
  if (!sourceType || !targetType) return false
  if (sourceType === FieldType.SELECT || targetType === FieldType.SELECT)
    return (
      sourceType === FieldType.SELECT &&
      targetType === FieldType.SELECT &&
      selectFillCompatible(sourceDefinition.fieldOptions[sourceFieldId], targetDefinition.fieldOptions[targetFieldId])
    )
  return sourceType === targetType || (numericTypes.includes(sourceType) && numericTypes.includes(targetType))
}
