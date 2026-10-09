/** 对象完整性策略使用稳定字段 ID；配置和运行端共用此契约。 */
export type DocumentScope = 'FIELD' | 'ROW' | 'DETAIL' | 'DOCUMENT'
export type DocumentOperator =
  | 'VALUE'
  | 'FIELD'
  | 'SUM'
  | 'COUNT'
  | 'UNIQUE'
  | 'EQ'
  | 'NE'
  | 'GT'
  | 'GE'
  | 'LT'
  | 'LE'
  | 'AND'
  | 'OR'
  | 'NOT'
  | 'EMPTY'
export interface DocumentExpression {
  op: DocumentOperator
  fieldId?: string | null
  detailId?: string | null
  value?: string | number | boolean | null
  args: DocumentExpression[]
}
export interface DocumentRule {
  id: string
  name: string
  scope: DocumentScope
  detailId: string | null
  when: DocumentExpression | null
  assertion: DocumentExpression
  fieldId: string | null
  message: string
}
export interface DocumentState {
  code: string
  name: string
  lockedFields: string[]
  lockedDetails: string[]
  allowDelete: boolean
}
export interface DocumentAction {
  code: string
  name: string
  fromStates: string[]
  toState: string
  permission: 'CREATE' | 'UPDATE'
}
export interface DocumentPolicy {
  handling?: import('./handling').HandlingPolicy | null
  rules: DocumentRule[]
  lifecycle: null | {
    fieldId: string
    initialState: string
    states: DocumentState[]
    actions: DocumentAction[]
  }
}
export interface DocumentProblem {
  ruleId?: string | null
  scope: DocumentScope
  detailId?: string | null
  clientRowKey?: string | null
  recordId?: string | null
  fieldId?: string | null
  message: string
}
