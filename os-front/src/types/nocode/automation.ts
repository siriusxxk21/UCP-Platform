import type { DataScope } from './data-scope'

/** 自动更新规则随应用版本发布，监听共享对象的有效数据变化；DATE = 按日期自动执行（每天到日子时执行一次赋值）。 */
export const AutomationMode = { EVENT: 'EVENT', MAINTAIN: 'MAINTAIN', DATE: 'DATE' } as const
export type AutomationMode = (typeof AutomationMode)[keyof typeof AutomationMode]
export const AutomationEvent = { CREATE: 'CREATE', UPDATE: 'UPDATE', DELETE: 'DELETE' } as const
export type AutomationEvent = (typeof AutomationEvent)[keyof typeof AutomationEvent]
export const AutomationValueKind = {
  VALUE: 'VALUE',
  FIELD: 'FIELD',
  COUNT: 'COUNT',
  SUM: 'SUM',
  MIN: 'MIN',
  MAX: 'MAX',
  EXISTS: 'EXISTS'
} as const
export type AutomationValueKind = (typeof AutomationValueKind)[keyof typeof AutomationValueKind]
export interface AutomationAssignment {
  fieldId: string
  kind: AutomationValueKind
  sourceFieldId: string | null
  value: unknown
  emptyValue: unknown
}
export interface AutomationConfig {
  objectId: string
  targetObjectId: string
  enabled: boolean
  mode: AutomationMode
  events: AutomationEvent[]
  conditions: DataScope | null
  /** SELF 只用于按日期自动执行：目标就是来源记录本身。 */
  binding: { relationId: string | null; direction: 'OUTGOING' | 'INCOMING' | 'SELF' }
  assignments: AutomationAssignment[]
  /** 按日期自动执行：来源上的日期 / 日期时间字段。 */
  dateFieldId?: string | null
  /** 按日期自动执行：执行日 = 日期 + offsetDays（负数 = 提前）。 */
  offsetDays?: number | null
}

/** 按日期自动执行的一条失败记录。 */
export interface DateTriggerFailure {
  sourceRecordId: string
  message: string
  at: string | null
}

/** 按日期自动执行：一条规则的账本与最近一个处理过的业务日的结果。 */
export interface DateTriggerStatus {
  resourceId: string
  armed: boolean
  closedDate: string | null
  lastScanAt: string | null
  lastTrigger: 'AUTO' | 'MANUAL' | null
  lastError: string | null
  businessDate: string | null
  success: number
  unchanged: number
  failed: number
  failures: DateTriggerFailure[]
}

export interface DateTriggerResult {
  businessDate: string
  success: number
  unchanged: number
  failed: number
  skipped: number
}
