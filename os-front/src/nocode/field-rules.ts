import { ref } from 'vue'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { FieldRules, RuleCondition } from '@/types/nocode/field-rules'
import { SelectionKind, type SelectionSource } from '@/types/nocode/selection'
import type { createDataCenterApi } from '@/api/nocode/data-center'
import { createRequestSession } from './request-session'
import { errorMessage } from './data-center'
import { relationForField } from './relation-editing'
import type { FormulaNode } from './formula-builder'
import { recordNumberError } from './record-number'
import { isRelativeDate, relativeDateError } from './relative-date'

/**
 * 数据对象 · 字段抽屉「值来源」的判据（设计稿第 2、3.5、7.2、10.1、15.3、15.4 章）。
 * 选项类只有「选项▾」，其它字段只有「默认值▾」，两块从不同时出现；档位由配置本体推出，不另存。
 */
export type ValueSourceBlock = 'OPTIONS' | 'DEFAULT' | 'NONE'
export const VALUE_SOURCE_MODES = [
  'CUSTOM',
  'OBJECT_FIELD_OPTIONS',
  'REFERENCE_LABEL',
  'REFERENCE_FILTER',
  'LINKAGE',
  'FORMULA'
] as const
export type ValueSourceMode = (typeof VALUE_SOURCE_MODES)[number]
export interface ValueSourceModes {
  block: ValueSourceBlock
  modes: ValueSourceMode[]
}
type RelationLike = { kind: string } | null | undefined
interface TypedField {
  type: string
}

export const optionTypes: readonly string[] = [
  FieldType.SELECT,
  FieldType.MULTI_SELECT,
  FieldType.REGION,
  FieldType.CASCADE
]
export const numericTypes: readonly string[] = [
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT
]
const textTypes: readonly string[] = [FieldType.TEXT, FieldType.TEXTAREA, FieldType.URL]
const dateTypes: readonly string[] = [FieldType.DATE, FieldType.DATETIME, FieldType.TIME]
const directoryTypes: readonly string[] = [
  FieldType.USER,
  FieldType.DEPARTMENT,
  FieldType.ORGANIZATION,
  FieldType.POST,
  FieldType.USER_GROUP
]
const linkageOnlyTypes: readonly string[] = [...dateTypes, FieldType.BOOLEAN, ...directoryTypes, FieldType.RICH_TEXT]
export const isOptionType = (type: string) => optionTypes.includes(type)

/** 能力矩阵：与后端 FieldRuleMatrix 共用 contract-baseline/field-rule-matrix.json 期望表。 */
export function valueSourceModesFor(type: string, relation?: RelationLike): ValueSourceModes {
  if (relation) {
    if (relation.kind === RelationType.MANY_TO_MANY) return { block: 'NONE', modes: [] }
    if (relation.kind === RelationType.MASTER_DETAIL) return { block: 'OPTIONS', modes: ['REFERENCE_LABEL'] }
    return { block: 'OPTIONS', modes: ['REFERENCE_LABEL', 'REFERENCE_FILTER', 'LINKAGE'] }
  }
  if (type === FieldType.SELECT || type === FieldType.MULTI_SELECT)
    return { block: 'OPTIONS', modes: ['CUSTOM', 'OBJECT_FIELD_OPTIONS', 'LINKAGE'] }
  if (type === FieldType.REGION || type === FieldType.CASCADE) return { block: 'OPTIONS', modes: ['CUSTOM', 'LINKAGE'] }
  if (type === FieldType.IMAGE || type === FieldType.ATTACHMENT) return { block: 'DEFAULT', modes: ['CUSTOM'] }
  if (textTypes.includes(type) || numericTypes.includes(type))
    return { block: 'DEFAULT', modes: ['CUSTOM', 'LINKAGE', 'FORMULA'] }
  if (linkageOnlyTypes.includes(type)) return { block: 'DEFAULT', modes: ['CUSTOM', 'LINKAGE'] }
  // 自动编号、计算公式、汇总、UUID 与没有关系的引用列不由用户填值。
  return { block: 'NONE', modes: [] }
}
export const valueSourceModeLabels: Record<ValueSourceMode, string> = {
  CUSTOM: '自定义',
  OBJECT_FIELD_OPTIONS: '关联其它表单数据·挑取值',
  REFERENCE_LABEL: '显示名字段',
  REFERENCE_FILTER: '引用筛选',
  LINKAGE: '数据联动',
  FORMULA: '公式编辑'
}

/** 默认值▾ 的当前档位：由配置推出；脏数据同时存在时按 联动 → 公式 → 自定义 的顺序定一档。 */
export function defaultValueMode(options?: FieldOptions | null): 'CUSTOM' | 'LINKAGE' | 'FORMULA' {
  if (options?.rules?.linkage) return 'LINKAGE'
  if (options?.rules?.defaultFormula) return 'FORMULA'
  return 'CUSTOM'
}

/**
 * 数据联动「当前字段只读」：新配联动默认开启；存量 readOnly 为 null 按开启（与后端 null 按 true 一致），
 * 只有显式 false 才是可手改的建议值（业务方 2026-09-29 裁定）。
 */
export const LINKAGE_READ_ONLY_DEFAULT = true
export const linkageReadOnly = (linkage?: Pick<NonNullable<FieldRules['linkage']>, 'readOnly'> | null): boolean =>
  linkage ? linkage.readOnly !== false : LINKAGE_READ_ONLY_DEFAULT

/* ── 多行匹配与取整 ── */
export const MULTI_ROW_MODES = ['CONCAT', 'FIRST', 'SUM', 'ERROR'] as const
export type MultiRowMode = (typeof MULTI_ROW_MODES)[number]
export const MULTI_ROW_DEFAULT: MultiRowMode = 'CONCAT'
export const multiRowLabels: Record<MultiRowMode, { label: string; hint: string }> = {
  CONCAT: { label: '拼接成一行', hint: '用英文逗号连起来，落成一个值；空值跳过' },
  FIRST: { label: '取第一行', hint: '按创建时间最早的一行取值' },
  SUM: { label: '求和', hint: '把命中行的数值相加；空值不当 0，金额按取整方式取整' },
  ERROR: { label: '报错', hint: '命中多于一行时不填值，明确提示' }
}
/** 数值来源：数值、金额、百分比，以及结果为整数或小数的计算字段。 */
export function isNumericSource(field: TypedField | null | undefined, options?: FieldOptions | null): boolean {
  if (!field) return false
  if (numericTypes.includes(field.type)) return true
  if (field.type === FieldType.FORMULA || field.type === FieldType.SUMMARY)
    return options?.resultType === FieldType.INTEGER || options?.resultType === FieldType.DECIMAL
  return false
}
/** 求和只对数值来源出现（非数值来源不列这一项）；多选目标不列拼接（设计稿 U5）。 */
export function multiRowModesFor(
  source: TypedField | null | undefined,
  sourceOptions: FieldOptions | null | undefined,
  targetType: string
): MultiRowMode[] {
  return MULTI_ROW_MODES.filter(
    mode =>
      (mode !== 'SUM' || isNumericSource(source, sourceOptions)) &&
      (mode !== 'CONCAT' || targetType !== FieldType.MULTI_SELECT)
  )
}

export const ROUNDING_MODES = ['HALF_UP', 'FLOOR', 'DOWN'] as const
export type RoundingMode = (typeof ROUNDING_MODES)[number]
/** 缺省向下取整；选缺省档时存 null。 */
export const ROUNDING_DEFAULT: RoundingMode = 'FLOOR'
export const roundingOptions: { value: RoundingMode; label: string; example: string }[] = [
  { value: 'HALF_UP', label: '四舍五入', example: '1.5 → 2；-1.5 → -2' },
  { value: 'FLOOR', label: '向下取整', example: '1.5 → 1；-1.5 → -2' },
  { value: 'DOWN', label: '去掉小数', example: '1.5 → 1；-1.5 → -1' }
]
export const roundingApplies = (type: string) => type === FieldType.MONEY
export const roundingOf = (rules?: FieldRules | null): RoundingMode => rules?.rounding ?? ROUNDING_DEFAULT
export const roundingCode = (mode: RoundingMode): FieldRules['rounding'] => (mode === ROUNDING_DEFAULT ? null : mode)

/* ── 条件与算子（7.2 算子词表） ── */
export const RECORD_KEY = '$record'
/** 条件值来源「当前记录」（第一期契约 2.2）：只用于开启了自动更新的数据联动。 */
export const CURRENT_RECORD = 'CURRENT_RECORD'
export const CONDITION_CAP = 20
// 条件字段以后端 RecordQueryOperatorEnum.supports 为准：URL、UUID 不能做条件字段。
const conditionTextTypes: readonly string[] = [FieldType.TEXT, FieldType.TEXTAREA, FieldType.AUTO_NUMBER]
const equalityTypes: readonly string[] = [FieldType.SELECT, FieldType.REFERENCE, FieldType.BOOLEAN, ...directoryTypes]
const notFilterableTypes: readonly string[] = [
  FieldType.URL,
  FieldType.UUID,
  FieldType.SUMMARY,
  FieldType.IMAGE,
  FieldType.ATTACHMENT,
  FieldType.RICH_TEXT,
  FieldType.REGION,
  FieldType.CASCADE
]
export const operatorLabels: Record<string, string> = {
  eq: '等于',
  neq: '不等于',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
  like: '包含',
  notLike: '不包含',
  between: '在范围内',
  containsAny: '包含任一',
  isNull: '为空',
  notNull: '不为空'
}
/**
 * 「为空 / 不为空」只看来源字段本身，不带比较值：值来源与取值控件不显示，保存出的条件 value 为 null、不带 formFieldId
 * （业务方 2026-10-01；与后端 FieldRuleMatrix.EMPTINESS 同一组编码）。
 */
export const VALUELESS_OPERATORS: readonly string[] = ['isNull', 'notNull']
export const isValuelessOperator = (operator?: string | null) => !!operator && VALUELESS_OPERATORS.includes(operator)
export function operatorLabel(operator: string, type?: string): string {
  if (type && dateTypes.includes(type) && operator === 'lt') return '早于'
  if (type && dateTypes.includes(type) && operator === 'gt') return '晚于'
  return operatorLabels[operator] ?? operator
}
/** 计算字段按结果类型取词表；LIVE 关联计算不可筛。 */
function conditionType(field: TypedField, options?: FieldOptions | null): string | null {
  if (field.type !== FieldType.FORMULA) return field.type
  if (options?.calculation && options.calculation.updateMode === 'LIVE') return null
  return options?.resultType ?? FieldType.DECIMAL
}
/**
 * 比较方式词表（与后端 FieldRuleMatrix.operators 同一张表，2026-10-01 扩充）：
 * 凡可作条件的字段都有「为空 / 不为空」；选项、关联、目录、布尔与文本另有「不等于」。
 * 「不等于」对空值安全：来源字段为空的记录算不等于，会被选中。「按记录匹配」仍只有「等于」。
 */
export function operatorsFor(field: TypedField | typeof RECORD_KEY, options?: FieldOptions | null): string[] {
  if (field === RECORD_KEY) return ['eq']
  const type = conditionType(field, options)
  if (!type || notFilterableTypes.includes(type)) return []
  if (conditionTextTypes.includes(type)) return ['eq', 'neq', 'like', 'notLike', ...VALUELESS_OPERATORS]
  if (dateTypes.includes(type)) return ['lt', 'gt', 'between', ...VALUELESS_OPERATORS]
  if (numericTypes.includes(type)) return ['eq', 'neq', 'gt', 'gte', 'lt', 'lte', ...VALUELESS_OPERATORS]
  if (type === FieldType.MULTI_SELECT) return ['containsAny', ...VALUELESS_OPERATORS]
  if (equalityTypes.includes(type)) return ['eq', 'neq', ...VALUELESS_OPERATORS]
  return []
}
const blank = (value: unknown) =>
  value === null ||
  value === undefined ||
  (typeof value === 'string' && !value.trim()) ||
  (Array.isArray(value) && !value.length)
export function conditionError(condition: RuleCondition, index: number): string | null {
  const prefix = `第 ${index + 1} 条条件：`
  if (!condition.fieldId) return prefix + '请选择来源字段'
  if (!condition.operator) return prefix + '请选择比较方式'
  if (condition.fieldId === RECORD_KEY && condition.operator !== 'eq') return prefix + '「按记录匹配」只能用「等于」'
  // 「当前记录」：来源关联字段指向的就是当前这条记录，只许「等于」，右侧没有值。
  if (condition.valueSource === CURRENT_RECORD) {
    if (condition.fieldId === RECORD_KEY) return prefix + '「按记录匹配」不能选「当前记录」，请改选来源对象上的关联字段'
    return condition.operator === 'eq' ? null : prefix + '「当前记录」只能用「等于」'
  }
  // 为空 / 不为空不需要右侧的值。
  if (isValuelessOperator(condition.operator)) return null
  // 相对日期（今天、本周、过去 N 天……）：值来源仍是 CONSTANT，只核编码与天数；字段类型与比较方式由条件行与服务端把关。
  if (condition.valueSource === 'CONSTANT' && isRelativeDate(condition.value)) {
    const error = relativeDateError(condition.value)
    return error ? prefix + error : null
  }
  if (condition.operator === 'between') {
    const range = condition.value
    if (condition.valueSource !== 'CONSTANT') return prefix + '「在范围内」只能填写固定值'
    if (!Array.isArray(range) || range.length !== 2 || range.some(blank)) return prefix + '请填写起止两个值'
    return null
  }
  if (condition.valueSource === 'FORM_FIELD') return condition.formFieldId ? null : prefix + '请选择当前字段'
  return blank(condition.value) ? prefix + '请填写固定值' : null
}
export function conditionsError(conditions?: RuleCondition[] | null): string | null {
  const list = conditions ?? []
  if (list.length > CONDITION_CAP) return `条件最多 ${CONDITION_CAP} 条`
  for (const [index, condition] of list.entries()) {
    const error = conditionError(condition, index)
    if (error) return error
  }
  if (list.filter(condition => condition.valueSource === CURRENT_RECORD).length > 1)
    return '「当前记录」的条件只能有一条'
  return null
}
export function isLinkageIncomplete(linkage?: FieldRules['linkage']): boolean {
  return !!linkage && (!linkage.sourceObjectId || !linkage.valueFieldId || !!conditionsError(linkage.conditions))
}
export function isReferenceIncomplete(reference?: FieldRules['reference']): boolean {
  return !!reference && !!conditionsError(reference.filter)
}
export function isObjectFieldOptionsIncomplete(selection?: SelectionSource | null): boolean {
  return (
    selection?.kind === SelectionKind.OBJECT_FIELD_OPTIONS && (!selection.sourceObjectId || !selection.sourceFieldId)
  )
}
/** 抽屉顶部提示与保存拦截共用同一判据。 */
export function fieldRulesError(options?: FieldOptions | null): string | null {
  if (isObjectFieldOptionsIncomplete(options?.selection)) return '挑取值配置不完整：请选择来源对象和来源字段'
  const rules = options?.rules
  if (isLinkageIncomplete(rules?.linkage))
    return '数据联动配置不完整：' + (conditionsError(rules?.linkage?.conditions) || '请选择来源对象和取值字段')
  if (isReferenceIncomplete(rules?.reference)) return '引用筛选配置不完整：' + conditionsError(rules?.reference?.filter)
  return null
}

/* ── 来源变化时自动更新（2026-10-01 第一期契约 9.1、9.2） ── */
type Linkage = NonNullable<FieldRules['linkage']>
/**
 * 「来源变化时自动更新」是否开启：只有 autoUpdate 为 true 才算开。
 * 存量联动没有这个键，一律是关，由业务方逐条打开；这里绝不能照「当前字段只读」那样把缺省当成开。
 */
export const linkageAutoUpdate = (linkage?: Pick<Linkage, 'autoUpdate'> | null): boolean => linkage?.autoUpdate === true

export type LinkageAnchor = 'CURRENT_RECORD' | 'RECORD_KEY'
/**
 * 自动更新的锚点（按记录匹配的那条条件，与后端 FieldRuleValidator.anchor 同口径）：
 * 「来源对象的关联字段 等于 当前记录」优先，其次「按记录匹配 等于 当前字段」；没有锚点就无法确定哪些记录要更新。
 */
export function linkageAnchor(conditions?: RuleCondition[] | null): LinkageAnchor | null {
  const list = conditions ?? []
  if (list.some(condition => condition.valueSource === CURRENT_RECORD)) return 'CURRENT_RECORD'
  return list.some(
    condition =>
      condition.fieldId === RECORD_KEY &&
      condition.operator === 'eq' &&
      condition.valueSource === 'FORM_FIELD' &&
      !!condition.formFieldId
  )
    ? 'RECORD_KEY'
    : null
}
/** 一期可开自动更新的目标字段类型（关联字段不在其中）。 */
export const AUTO_UPDATE_TARGET_TYPES: readonly string[] = [
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT,
  FieldType.BOOLEAN,
  FieldType.DATE,
  FieldType.DATETIME,
  FieldType.TIME,
  FieldType.SELECT,
  FieldType.MULTI_SELECT
]
export interface AutoUpdateInput {
  /** 「当前字段只读」是否开着。 */
  readOnly: boolean
  /** 当前字段在内部明细里。 */
  detail: boolean
  fieldType: string
  /** 当前字段是关联字段。 */
  relation: boolean
  sourceObjectId: string
  /** 当前对象 ID；对象还没保存时为空。 */
  objectId?: string | null
  conditions: RuleCondition[]
  /** 已选的取值字段；还没选时为空。 */
  valueField?: TypedField | null
}
/**
 * 自动更新开不了的原因；能开返回 null。与发布校验一一对应，前端先拦：
 * 只读（S1）→ 主表字段（F1）→ 目标类型（F3）→ 来源不是本对象（F2）→ 有锚点（F4）→ 取值字段不是计算字段（F6）。
 */
export function autoUpdateBlocker(input: AutoUpdateInput): string | null {
  if (!input.readOnly) return '可手改的字段不会跟随来源变化'
  if (input.detail) return '明细字段暂不支持'
  if (input.relation || !AUTO_UPDATE_TARGET_TYPES.includes(input.fieldType)) return '这种类型的字段暂不支持'
  if (!!input.objectId && input.sourceObjectId === input.objectId) return '来源对象是本对象时暂不支持'
  if (!linkageAnchor(input.conditions))
    return '需要一条按记录匹配的条件：「来源对象的关联字段 等于 当前记录」或「按记录匹配 等于 当前字段」'
  const valueType = input.valueField?.type
  if (valueType === FieldType.FORMULA || valueType === FieldType.SUMMARY) return '带入的来源字段是计算字段时暂不支持'
  return null
}

/** 「没有匹配记录时填入」的控件种类。 */
export type EmptyValueKind = 'text' | 'choice' | 'integer' | 'decimal' | 'money' | 'boolean'
/** 单选的值是选项编码（不是目录 ID）的那几种候选来源。 */
const choiceSelectionKinds: readonly (string | undefined)[] = [
  undefined,
  SelectionKind.LOCAL_OPTIONS,
  SelectionKind.SYSTEM_DICTIONARY,
  SelectionKind.OBJECT_FIELD_OPTIONS
]
/** 目标字段支持哪种「没有匹配记录时填入」；null 表示这种类型一期不支持（契约 2.3），关联字段一律不支持。 */
export function emptyValueKind(
  type: string,
  relation?: RelationLike,
  options?: Pick<FieldOptions, 'selection'> | null
): EmptyValueKind | null {
  if (relation) return null
  if (type === FieldType.TEXT || type === FieldType.TEXTAREA) return 'text'
  if (type === FieldType.SELECT) return choiceSelectionKinds.includes(options?.selection?.kind) ? 'choice' : null
  if (type === FieldType.INTEGER) return 'integer'
  if (type === FieldType.DECIMAL || type === FieldType.PERCENT) return 'decimal'
  if (type === FieldType.MONEY) return 'money'
  if (type === FieldType.BOOLEAN) return 'boolean'
  return null
}
/** 没设长度的文本字段按这个上限校验（与后端 LinkageEmptyValues.DEFAULT_TEXT_LIMIT 相同）。 */
export const EMPTY_VALUE_TEXT_LIMIT = 4000
/**
 * 「没有匹配记录时填入」的字面量校验，前端先拦（后端发布时再核）：金额按日元只收整数，不取整；
 * 数值只认普通十进制写法（不收全角数字与 1e2 这类写法）；文本不超过字段长度。不填不算错。
 */
export function emptyValueError(kind: EmptyValueKind, raw: string, field: ObjectField): string | null {
  if (!raw.trim()) return null
  if (kind === 'text') {
    const limit = field.length ?? EMPTY_VALUE_TEXT_LIMIT
    return raw.length > limit ? `不能超过 ${limit} 个字` : null
  }
  if (kind === 'boolean') return raw === 'true' || raw === 'false' ? null : '请选择「是」或「否」'
  if (kind === 'choice') return null
  const integer = /^[+-]?\d+$/.test(raw)
  if (kind === 'integer') return integer ? recordNumberError(field, undefined, raw) : '请填写整数'
  if (!/^[+-]?\d+(\.\d+)?$/.test(raw)) return '请填写数字'
  if (kind === 'money' && !integer) return '金额按日元整数保存，请填写整数'
  return recordNumberError(field, undefined, raw)
}
export interface LinkageDraftValue {
  sourceObjectId: string
  conditions: RuleCondition[]
  valueFieldId: string
  multiRow: MultiRowMode | null
  readOnly: boolean
  /** 最终是否开启（意向为开且满足可开条件）。 */
  autoUpdate: boolean
  emptyValue: string
}
/**
 * 写出数据联动的唯一出口：开启才写 autoUpdate: true，关闭时不带这个键（不写 false）；
 * emptyValue 只在开启且填了值时才带。存量联动原样确定后不多出任何键。
 */
export function buildLinkage(draft: LinkageDraftValue): Linkage {
  const next: Linkage = {
    sourceObjectId: draft.sourceObjectId,
    conditions: draft.conditions,
    valueFieldId: draft.valueFieldId,
    multiRow: draft.multiRow,
    readOnly: draft.readOnly
  }
  if (!draft.autoUpdate) return next
  next.autoUpdate = true
  if (draft.emptyValue.trim()) next.emptyValue = draft.emptyValue
  return next
}

/* ── 归一：同一时刻只落一档，换档清掉残值（B35） ── */
function compactRules(rules: FieldRules, type: string): FieldRules | null {
  const out: FieldRules = {}
  const reference = rules.reference
  if (reference && (reference.labelFieldId || reference.filter?.length))
    out.reference = { labelFieldId: reference.labelFieldId || null, filter: reference.filter ?? [] }
  if (rules.linkage) out.linkage = rules.linkage
  if (rules.defaultFormula?.trim()) out.defaultFormula = rules.defaultFormula
  if (rules.rounding && roundingApplies(type)) out.rounding = rules.rounding
  return Object.keys(out).length ? out : null
}
/** 换成非挑取值来源后，旧的来源对象和字段不再保留（挑取值换来源等于换了值的含义）。 */
function withoutStaleSource(selection?: SelectionSource | null): SelectionSource | null | undefined {
  if (!selection || selection.kind === SelectionKind.OBJECT_FIELD_OPTIONS) return selection
  if (selection.sourceObjectId == null && selection.sourceFieldId == null) return selection
  const { sourceObjectId: _object, sourceFieldId: _field, ...rest } = selection
  return rest
}
function clearSelectionDefault(selection?: SelectionSource | null): SelectionSource | null | undefined {
  return selection && selection.defaultMode !== 'NONE' ? { ...selection, defaultMode: 'NONE' } : selection
}
/**
 * 切档时由这里清掉其它档的键；返回新对象，不修改入参。
 * rounding 跟着字段走、不跟着档位走（15.3.5）；非金额字段一律清掉。
 */
export function normalizeValueSource(
  type: string,
  relation: RelationLike,
  options: FieldOptions,
  mode: ValueSourceMode
): FieldOptions {
  const { block, modes } = valueSourceModesFor(type, relation)
  const rules: FieldRules = { ...(options.rules ?? {}) }
  delete rules.dependsOn
  if (block === 'NONE') return { ...options, rules: null }
  if (!modes.includes('LINKAGE')) rules.linkage = null
  if (block === 'OPTIONS') {
    // 选项类没有默认值块：未作答与选中无法区分。
    return {
      ...options,
      selection: relation ? options.selection : withoutStaleSource(options.selection),
      defaultValue: null,
      rules: compactRules(
        { ...rules, defaultFormula: null, rounding: null, reference: relation ? rules.reference : null },
        type
      )
    }
  }
  const next: FieldRules = {
    ...rules,
    reference: null,
    linkage: mode === 'LINKAGE' ? rules.linkage : null,
    defaultFormula: mode === 'FORMULA' ? rules.defaultFormula : null
  }
  const custom = mode === 'CUSTOM' || !modes.includes(mode)
  return {
    ...options,
    defaultValue: custom && !blank(options.defaultValue) ? options.defaultValue : null,
    selection: custom ? options.selection : clearSelectionDefault(options.selection),
    rules: compactRules(custom ? { ...next, linkage: null, defaultFormula: null } : next, type)
  }
}
/** 保存前的最终清理：档位按配置推出；取整只用于数据联动或公式默认值。 */
export function cleanFieldRules(type: string, relation: RelationLike, options: FieldOptions): FieldOptions {
  const { block } = valueSourceModesFor(type, relation)
  const next = normalizeValueSource(type, relation, options, block === 'DEFAULT' ? defaultValueMode(options) : 'CUSTOM')
  const rules =
    next.rules?.rounding && !next.rules.linkage && !next.rules.defaultFormula
      ? compactRules({ ...next.rules, rounding: null }, type)
      : next.rules
  // 没有任何规则时不写 rules 键，未配置规则的字段保存内容与改动前一致。
  const { rules: _rules, ...rest } = next
  return rules ? { ...rest, rules } : rest
}
export function fieldRuleTags(options?: FieldOptions | null): string[] {
  const rules = options?.rules
  return [
    ...(rules?.linkage ? ['联动'] : []),
    ...(rules?.defaultFormula ? ['公式默认'] : []),
    ...(rules?.reference?.filter?.length ? ['引用筛选'] : [])
  ]
}

/* ── 条件行「当前字段」与取值字段的候选 ── */
export interface FieldChoice {
  value: string
  label: string
  field: ObjectField
  referenceTarget: string | null
}
export interface FieldChoiceGroup {
  label: string
  options: FieldChoice[]
}
export function referenceTargetOf(field: ObjectField, relations: ObjectRelation[] = []): string | null {
  const relation = relationForField(field, relations)
  return relation && relation.kind !== RelationType.MANY_TO_MANY ? relation.targetObjectId : null
}
const formFieldUsable = (field: ObjectField, selfKey: string) =>
  !!field.id && field.key !== selfKey && field.type !== FieldType.FORMULA && field.type !== FieldType.SUMMARY
/**
 * 「当前字段」下拉：主表字段一组；明细字段分「本行 · / 主表 ·」两组，不列其它明细（15.4.2、15.4.7）。
 * 只列已保存（有稳定 ID）的字段，计算字段与字段自身不列。
 */
export function formFieldGroups(input: {
  fields: ObjectField[]
  relations?: ObjectRelation[]
  selfKey: string
  master?: { fields: ObjectField[]; relations?: ObjectRelation[] } | null
}): FieldChoiceGroup[] {
  const choices = (fields: ObjectField[], relations: ObjectRelation[] | undefined, prefix: string) =>
    fields
      .filter(field => formFieldUsable(field, input.selfKey))
      .map(field => ({
        value: field.id ?? '',
        label: `${prefix} · ${field.name || field.code}`,
        field,
        referenceTarget: referenceTargetOf(field, relations)
      }))
  if (!input.master) return [{ label: '当前字段', options: choices(input.fields, input.relations, '当前字段') }]
  return [
    { label: '本行', options: choices(input.fields, input.relations, '本行') },
    { label: '主表', options: choices(input.master.fields, input.master.relations, '主表') }
  ]
}
const typeGroup = (type: string) =>
  numericTypes.includes(type)
    ? 'NUMBER'
    : conditionTextTypes.includes(type)
      ? 'TEXT'
      : type === FieldType.SELECT || type === FieldType.MULTI_SELECT
        ? 'CHOICE'
        : type
/** 条件两侧类型兼容（发布时后端再按 7.2 复核）；引用两侧目标已知且不同则不列。 */
export function formFieldCompatible(
  condition: { type: string; options?: FieldOptions | null; referenceTarget?: string | null },
  choice: FieldChoice
): boolean {
  const conditionReference = !!condition.referenceTarget || condition.type === FieldType.REFERENCE
  const choiceReference = !!choice.referenceTarget || choice.field.type === FieldType.REFERENCE
  if (conditionReference || choiceReference)
    return (
      conditionReference &&
      choiceReference &&
      (!condition.referenceTarget || !choice.referenceTarget || condition.referenceTarget === choice.referenceTarget)
    )
  const type = conditionType(condition, condition.options)
  return !!type && typeGroup(type) === typeGroup(choice.field.type)
}

/**
 * 文本目标可接收的来源值类型（业务方 2026-10-01 裁定，与后端 FieldRuleValidator.TEXT_SOURCES 同一张表）：
 * 单行文本收文本、自动编号、链接；多行文本另收多行文本（多行内容不放进单行文本）。计算字段按结果类型折算。
 */
const textLinkageSources: Record<string, readonly string[]> = {
  [FieldType.TEXT]: [FieldType.TEXT, FieldType.AUTO_NUMBER, FieldType.URL],
  [FieldType.TEXTAREA]: [FieldType.TEXT, FieldType.TEXTAREA, FieldType.AUTO_NUMBER, FieldType.URL]
}
/**
 * 联动取值字段与目标字段的类型兼容（7.2「值类型兼容」行，前端先按类型过滤）。
 * target.objectId 是当前对象（已保存时才有）：用于认出「来源字段的挑取值正好指向当前字段」这一反方向。
 */
export function linkageValueCompatible(
  target: {
    field: ObjectField
    options?: FieldOptions | null
    referenceTarget?: string | null
    objectId?: string | null
  },
  source: { field: ObjectField; options?: FieldOptions | null; referenceTarget?: string | null },
  sourceObjectId: string
): boolean {
  if (target.referenceTarget) return source.referenceTarget === target.referenceTarget
  if (source.referenceTarget) return false
  const targetType = target.field.type,
    sourceType = source.field.type
  if (numericTypes.includes(targetType)) return isNumericSource(source.field, source.options)
  const textSources = textLinkageSources[targetType]
  if (textSources) {
    const calculated = sourceType === FieldType.FORMULA || sourceType === FieldType.SUMMARY
    return textSources.includes(calculated ? (source.options?.resultType ?? '') : sourceType)
  }
  if (targetType === FieldType.RICH_TEXT) return sourceType === FieldType.RICH_TEXT || sourceType === FieldType.TEXTAREA
  if (targetType === FieldType.SELECT || targetType === FieldType.MULTI_SELECT) {
    if (targetType === FieldType.MULTI_SELECT ? sourceType !== targetType : !isOptionType(sourceType)) return false
    const own = target.options?.selection,
      other = source.options?.selection
    // 反方向（2026-10-01）：来源字段挑的就是当前字段的选项，二者共用一套选项；单选对单选、多选对多选。
    if (
      other?.kind === SelectionKind.OBJECT_FIELD_OPTIONS &&
      sourceType === targetType &&
      !!target.objectId &&
      !!target.field.id &&
      other.sourceObjectId === target.objectId &&
      other.sourceFieldId === target.field.id
    )
      return true
    if (own?.kind === SelectionKind.OBJECT_FIELD_OPTIONS)
      return own.sourceObjectId === sourceObjectId && own.sourceFieldId === source.field.id
    return (
      own?.kind === SelectionKind.SYSTEM_DICTIONARY &&
      other?.kind === SelectionKind.SYSTEM_DICTIONARY &&
      own.dictionaryType === other.dictionaryType
    )
  }
  return sourceType === targetType
}

/* ── 公式默认值 ── */
/** 收集公式里用到的函数名与字段编码（按 AST，不做子串匹配）。 */
export function formulaUsage(node: FormulaNode): { functions: Set<string>; fields: Set<string> } {
  const functions = new Set<string>(),
    fields = new Set<string>()
  const visit = (item: FormulaNode) => {
    if (item.kind === 'field') fields.add(item.value)
    if (item.kind !== 'operation') return
    if (/^[a-z]/i.test(item.operation)) functions.add(item.operation.toLowerCase())
    item.args.forEach(visit)
  }
  visit(node)
  return { functions, fields }
}
/** 金额目标的公式默认值不许写 round：取整统一由「取整方式」决定（15.3.3）。 */
export function defaultFormulaError(
  node: FormulaNode,
  input: { roundForbidden?: boolean; conflictCodes?: readonly string[] }
): string | null {
  const usage = formulaUsage(node)
  if (input.roundForbidden && usage.functions.has('round'))
    return '金额字段的公式里不能写 round()：取整统一由「取整方式」决定'
  const conflict = input.conflictCodes?.find(code => usage.fields.has(code))
  return conflict ? `公式引用「${conflict}」在主表和明细中重名，无法确定来源` : null
}
/** 明细字段的公式可引用本行字段和主表字段；两边重名的编码单独列出。 */
export function formulaFieldGroups(row: ObjectField[], master?: ObjectField[] | null) {
  if (!master) return { fields: row, groups: undefined, conflictCodes: [] as string[] }
  const rowCodes = new Set(row.map(field => field.code))
  const conflictCodes = master.map(field => field.code).filter(code => rowCodes.has(code))
  return {
    fields: [...row, ...master.filter(field => !rowCodes.has(field.code))],
    groups: [
      { label: '本行', fields: row },
      { label: '主表', fields: master }
    ],
    conflictCodes
  }
}

/* ── 来源对象目录与已发布定义 ── */
type DataCenterApi = ReturnType<typeof createDataCenterApi>
export interface PublishedObject {
  value: string
  label: string
  category: string
}
export interface PublishedDefinition {
  objectId: string
  label: string
  fields: ObjectField[]
  fieldOptions: Record<string, FieldOptions>
  relations: ObjectRelation[]
  titleTemplate: string | null
}
/** 已发布对象按分类分组，对应老系统逐级点亮的「分类 → 数据表」。 */
export function objectOptionGroups(objects: PublishedObject[]): { label: string; options: PublishedObject[] }[] {
  const groups = new Map<string, PublishedObject[]>()
  for (const item of objects) groups.set(item.category, [...(groups.get(item.category) ?? []), item])
  return [...groups.entries()].map(([label, options]) => ({ label, options }))
}
export function definitionOptions(definition: PublishedDefinition | null | undefined, field: ObjectField) {
  return definition?.fieldOptions[field.id ?? ''] ?? definition?.fieldOptions[field.key]
}
export async function loadPublishedDefinition(
  api: Pick<DataCenterApi, 'design' | 'version'>,
  id: string
): Promise<PublishedDefinition> {
  const design = await api.design(id)
  if (!design.publishedVersion) throw new Error('来源对象须先发布')
  const version = (await api.version(id, design.publishedVersion)) as {
    definition?: Record<string, unknown>
  } & Record<string, unknown>
  const definition = (version.definition ?? version) as {
    fields?: ObjectField[]
    fieldOptions?: Record<string, FieldOptions>
    relations?: ObjectRelation[]
    settings?: { titleTemplate?: string | null }
  }
  return {
    objectId: id,
    label: `${design.draft.objectName} · ${design.draft.objectCode}`,
    fields: (definition.fields ?? []).filter(field => definition.fieldOptions?.[field.id ?? '']?.state !== 'INACTIVE'),
    fieldOptions: definition.fieldOptions ?? {},
    relations: definition.relations ?? [],
    titleTemplate: definition.settings?.titleTemplate ?? null
  }
}
/** 来源对象下拉：按名称检索已发布对象，分页追加；迟到响应丢弃。 */
export function createObjectCatalog(api: Pick<DataCenterApi, 'objects'>) {
  const objects = ref<PublishedObject[]>([]),
    loading = ref(false),
    error = ref(''),
    more = ref(false)
  const session = createRequestSession()
  let page = 0,
    keyword = ''
  async function load(search = keyword, append = false) {
    if (append && (loading.value || !more.value)) return
    const current = session.begin()
    const next = append ? page + 1 : 1
    keyword = search
    loading.value = true
    error.value = ''
    try {
      const result = await api.objects({ pageNo: next, pageSize: 100, name: search || undefined })
      if (!current()) return
      const found = result.list
        .filter(item => item.publishedVersion)
        .map(item => ({
          value: item.id,
          label: `${item.objectName} · ${item.objectCode}`,
          category: item.category || '未分类'
        }))
      objects.value = append ? [...new Map([...objects.value, ...found].map(o => [o.value, o])).values()] : found
      page = next
      more.value = result.total > next * 100
    } catch (cause) {
      if (current()) error.value = errorMessage(cause)
    } finally {
      if (current()) loading.value = false
    }
  }
  return { objects, loading, error, more, load, dispose: () => session.invalidate() }
}
