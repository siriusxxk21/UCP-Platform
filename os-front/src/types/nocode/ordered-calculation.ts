/** 仅 RUNNING_TOTAL / SEQUENCE 的落库与校准状态；普通保存快照不在此范围。 */
export interface OrderedCalculationState {
  objectId: string
  fieldId: string
  signature: string
  state: 'PENDING' | 'BACKFILLING' | 'READY' | 'FAILED'
  cursor: Record<string, unknown>
  totalRows: number
  updatedRows: number
  completedGroups: number
  error: string | null
  revision: number
}

export interface OrderedCalibrationPreviewRequest {
  objectId: string
  versionNo: number
  checksum: string
  fieldIds: string[]
}

export interface OrderedCalibrationPreview {
  objectId: string
  versionNo: number
  checksum: string
  fields: {
    fieldId: string
    signature: string
    state: string
    groups: number
    rows: number
    nullRows: number
    changedRows: number
    fillRows: number
    incorrectRows: number
    validNullRows: number
  }[]
}

export interface OrderedCalibrationCommand extends OrderedCalibrationPreviewRequest {
  signatures: Record<string, string>
  requestId: string
  maxGroups: number
}

export interface OrderedCalibrationResult {
  requestId: string
  complete: boolean
  states: OrderedCalculationState[]
}
