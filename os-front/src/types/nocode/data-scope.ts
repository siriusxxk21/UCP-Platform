export type ScopeOperator =
  'eq' | 'neq' | 'in' | 'gt' | 'gte' | 'lt' | 'lte' | 'containsAny' | 'containsAll' | 'isNull' | 'notNull'
export type ScopeValueSource = 'CONSTANT' | 'CURRENT_USER' | 'CURRENT_DEPARTMENT' | 'CURRENT_DEPARTMENT_TREE'
export interface ScopeCondition {
  fieldId: string
  operator: ScopeOperator
  value: any
  valueSource?: ScopeValueSource | null
}
export interface DataScope {
  logic: 'AND' | 'OR'
  conditions: ScopeCondition[]
  groups: DataScope[]
}
export interface ViewQueryOptions {
  fixed: ScopeCondition[]
  defaults: Record<string, any>
  candidates: Record<string, string[]>
}
