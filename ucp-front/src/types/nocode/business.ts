import type { FieldOptions } from './data-center'
export const NumberPeriod = { NONE: 'NONE', YEAR: 'YEAR', MONTH: 'MONTH', DAY: 'DAY' } as const
export const BusinessActionKind = {
  UPDATE_FIELDS: 'UPDATE_FIELDS',
  CAPTURE_VALUES: 'CAPTURE_VALUES',
  START_PROCESS: 'START_PROCESS'
} as const
export interface AppDictionary {
  sourceType: string | null
  items: FieldOptions['options']
}
export interface NumberRule {
  objectId: string
  fieldId: string
  prefix: string
  period: keyof typeof NumberPeriod
  width: number
}
export interface AppBusinessAction {
  objectId: string
  kind: keyof typeof BusinessActionKind
  values: Record<string, unknown>
  processDefinitionId: string | null
  variables: Record<string, string>
  /** 目标普通字段 ID → 同对象来源公式 ID；留存后普通保存不能覆盖。 */
  captures?: Record<string, string>
}
