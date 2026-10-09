import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { SelectionKind } from '@/types/nocode/selection'
import { definitionOptions, referenceTargetOf, type PublishedDefinition } from './field-rules'

/**
 * 条件行「固定值」的选项（业务方 2026-10-01）：来源字段是选项类（单选、多选）时，固定值从该字段的选项里选——显示名称、保存编码。
 * 原先是自由文本框，填了选项名称「已录入」而库里存的是编码，候选恒为空。
 * 选项集与后端发布校验 FieldRuleValidator.choiceCodes 同口径：局部选项、公共字典，挑取值取它指向的来源字段的选项。
 */
export interface ConditionChoice {
  value: string
  label: string
  disabled: boolean
}
/** partial：选项集只含启用项（公共字典接口不返回停用项），不在其中的已存值无法区分「已停用」与「从来不是选项」。 */
export interface ConditionChoiceSet {
  options: ConditionChoice[]
  partial: boolean
}
export interface ConditionChoiceDeps {
  /** 另一个对象的已发布定义（挑取值的来源对象）。 */
  definition: (objectId: string) => Promise<PublishedDefinition>
  /** 公共字典的启用项。 */
  dictionary: (type: string) => Promise<ConditionChoice[]>
}
type TypedField = Pick<ObjectField, 'type'>

const choiceKinds: readonly (string | undefined)[] = [
  undefined,
  SelectionKind.LOCAL_OPTIONS,
  SelectionKind.SYSTEM_DICTIONARY,
  SelectionKind.OBJECT_FIELD_OPTIONS
]
/** 固定值走选项下拉的字段：单选、多选，且来源是选项（多选配成组织目录的值是目录 ID，不在此列）。 */
export function isChoiceConditionField(
  definition: PublishedDefinition | null | undefined,
  field: (TypedField & Pick<ObjectField, 'id' | 'key'>) | null | undefined
): boolean {
  if (!field || (field.type !== FieldType.SELECT && field.type !== FieldType.MULTI_SELECT)) return false
  return choiceKinds.includes(definitionOptions(definition, field as ObjectField)?.selection?.kind)
}

export function loadConditionChoices(
  definition: PublishedDefinition,
  field: ObjectField,
  deps: ConditionChoiceDeps
): Promise<ConditionChoiceSet> {
  return loadFieldChoices(definitionOptions(definition, field), deps, definition)
}

/**
 * 按「字段的选项配置」取选项集：局部选项、公共字典，挑取值取它指向的来源字段的选项。
 * 条件行的固定值（来源对象上的字段）与数据联动「没有匹配记录时填入」（当前正在编辑的字段）共用这一份。
 * own：选项配置所在对象的已发布定义（有就省一次请求）；编辑中的当前字段没有，传空。
 */
export async function loadFieldChoices(
  fieldOptions: Pick<FieldOptions, 'selection' | 'options'> | null | undefined,
  deps: ConditionChoiceDeps,
  own?: PublishedDefinition | null
): Promise<ConditionChoiceSet> {
  let options = fieldOptions
  const picked = options?.selection
  if (picked?.kind === SelectionKind.OBJECT_FIELD_OPTIONS) {
    if (!picked.sourceObjectId || !picked.sourceFieldId) throw new Error('「挑取值」还没选来源对象或来源字段')
    const owner = own && picked.sourceObjectId === own.objectId ? own : await deps.definition(picked.sourceObjectId)
    const origin = owner.fields.find(item => item.id === picked.sourceFieldId)
    if (!origin) throw new Error(`「挑取值」的来源字段在「${owner.label}」中已不存在或已停用`)
    options = definitionOptions(owner, origin)
  }
  const selection = options?.selection
  if (selection?.kind === SelectionKind.SYSTEM_DICTIONARY)
    return { options: selection.dictionaryType ? await deps.dictionary(selection.dictionaryType) : [], partial: true }
  const seen = new Set<string>()
  return {
    options: (options?.options ?? [])
      .filter(item => !!item.code && !seen.has(item.code) && !!seen.add(item.code))
      .map(item => ({ value: item.code, label: item.label || item.code, disabled: !!item.disabled })),
    partial: false
  }
}

/**
 * 下拉里列出的项：只列启用的选项；已保存的值若已停用或不在选项中，补一项只用于回显（不可重新选入）。
 * 已停用的标「已停用」；选项里没有的（例如早先在自由文本框里填的名称）标「不是有效选项」。
 */
export function conditionChoiceOptions(
  set: ConditionChoiceSet,
  stored: unknown
): { value: string; label: string; disabled?: boolean }[] {
  const choices = set.options
  const missing = set.partial ? '已停用或不是有效选项' : '不是有效选项'
  const enabled = choices.filter(item => !item.disabled).map(item => ({ value: item.value, label: item.label }))
  const values = (Array.isArray(stored) ? stored : [stored])
    .filter(item => item !== null && item !== undefined && item !== '')
    .map(String)
  const echoed = [...new Set(values)]
    .filter(value => !enabled.some(item => item.value === value))
    .map(value => {
      const known = choices.find(item => item.value === value)
      return { value, label: known ? `${known.label}（已停用）` : `${value}（${missing}）`, disabled: true }
    })
  return [...enabled, ...echoed]
}

/**
 * 引用字段的固定值从目标对象的记录里选（业务方 2026-10-04）：单值引用字段（多值关系不在条件字段里）返回目标对象 ID，否则 null。
 * 原先落到自由文本框，填了名称「民宿管理」而库里存的是记录 ID，候选恒为空、保存被拦。
 */
export function referenceConditionTarget(
  definition: PublishedDefinition | null | undefined,
  field: ObjectField | null | undefined
): string | null {
  return definition && field ? referenceTargetOf(field, definition.relations) : null
}
/** 已存值连格式都不对：整数外键里存了文本（多半是早先按名称填的）。其余类型的主键交给候选接口判断。 */
export function referenceValueMalformed(fieldType: string | undefined, value: unknown): boolean {
  if (value == null || value === '') return false
  return fieldType === FieldType.INTEGER && !/^-?\d+$/.test(String(value).trim())
}
/** 与后端对象设计校验同一句话的前端版本（这里只知道条件字段名）。 */
export function notRecordMessage(index: number, fieldName: string, value: unknown): string {
  return `第 ${index + 1} 条条件：固定值「${String(value)}」不是「${fieldName}」里的记录（可能是早先按名称填写的文本，或记录已删除），请重新选择`
}
