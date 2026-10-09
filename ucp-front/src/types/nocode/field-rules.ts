/**
 * 字段级对象规则契约（与后端 API/api/FieldRules.java 同形）。
 * 逐字来源：实施设计稿第 12 章「路 D · 提供的契约」，并按第 15.1 章「TS 契约增量」修订；
 * 2026-10-01「来源变化时自动更新」第一期契约 9.1 追加 CURRENT_RECORD、autoUpdate、emptyValue。
 */
export interface RuleCondition {
  fieldId: string
  operator: string
  /**
   * CURRENT_RECORD（只用于开启了自动更新的数据联动）：fieldId 是来源对象上指向本对象的单选关联字段，
   * 含义是「来源那一行的这个关联字段指向的就是当前这条记录」；只许 eq，不带 value 与 formFieldId。
   */
  valueSource: 'CONSTANT' | 'FORM_FIELD' | 'CURRENT_RECORD'
  value?: unknown
  formFieldId?: string | null
}
export interface FieldRules {
  reference?: { labelFieldId?: string | null; filter?: RuleCondition[] } | null
  linkage?: {
    sourceObjectId: string
    conditions: RuleCondition[]
    valueFieldId: string
    multiRow?: 'CONCAT' | 'FIRST' | 'SUM' | 'ERROR' | null
    readOnly?: boolean | null
    /** 来源变化时自动更新：只写 true；关闭时不带这个键（存量联动没有这个键即关，读取时只认 === true）。 */
    autoUpdate?: boolean | null
    /** 没有匹配记录时填入的值（字面量字符串，单选存选项编码）；不填时不带这个键。运行模型投影里永不下发。 */
    emptyValue?: string | null
  } | null
  defaultFormula?: string | null
  rounding?: 'HALF_UP' | 'FLOOR' | 'DOWN' | null
  dependsOn?: string[]
  /** 运行模型投影：字段有只读联动（linkage.readOnly 为 true 或 null）或公式默认值时为 true（业务方 2026-09-29 裁定）。 */
  readOnly?: boolean | null
}
export interface FieldRuleEvaluateQuery {
  applicationId: string
  objectId: string
  formId?: string
  recordId?: string
  creating?: boolean
  values: Record<string, unknown>
  changed?: string[]
  overridable?: string[]
  details?: {
    detailId: string
    rows: {
      rowKey: string
      detailRecordId?: string
      creating?: boolean
      values: Record<string, unknown>
      changed?: string[]
      overridable?: string[]
    }[]
  }[]
}
export interface FieldRuleResult {
  fieldId: string
  kind: 'LINKAGE' | 'DEFAULT_FORMULA' | 'REFERENCE'
  state: string
  value: unknown
  matchedRows: number
  readOnly: boolean
  message?: string | null
  pendingFields: string[]
  detailId?: string | null
  rowKey?: string | null
  inScope?: boolean | null
}
