export const SelectionKind = {
  LOCAL_OPTIONS: 'LOCAL_OPTIONS',
  SYSTEM_DICTIONARY: 'SYSTEM_DICTIONARY',
  DIRECTORY: 'DIRECTORY',
  OBJECT_RELATION: 'OBJECT_RELATION',
  /** 挑取值：候选来自来源对象字段配好的选项定义，不读业务行。 */
  OBJECT_FIELD_OPTIONS: 'OBJECT_FIELD_OPTIONS'
} as const
export interface SelectionSource {
  migrationMap?: Record<string, string[]> | null
  kind: keyof typeof SelectionKind
  directory: string | null
  dictionaryType: string | null
  rootIds: string[]
  includeDescendants: boolean
  organizationTypes: number[]
  defaultMode: 'NONE' | 'FIXED' | 'CURRENT_USER_ORGANIZATION'
  sourceObjectId?: string | null
  sourceFieldId?: string | null
}
export interface SelectionOption {
  value: string
  label: string
  code: string | null
  parentValue: string | null
  path: string | null
  disabled: boolean
  unavailable: boolean
}
export interface SelectionQuery {
  applicationId: string
  objectId: string
  fieldId: string
  detailId?: string
  recordId?: string
  detailRecordId?: string
  creating?: boolean
  formId?: string
  formValues?: Record<string, unknown>
  search?: string
  pageNo: number
  pageSize: number
  selected?: string[]
}
export interface SelectionResult {
  options: SelectionOption[]
  selected: SelectionOption[]
  total: number
  tree: boolean
  defaultValue: string | string[] | null
  defaultWarning?: string | null
  ruleState?: string | null
  ruleMessage?: string | null
  pendingFields?: string[] | null
}

export interface SelectionPreviewContext {
  applicationId: string
  objects: import('./application').ObjectReference[]
  form?: import('./application-ui').FormConfig
}

export interface SelectionPresentation {
  appearance?: 'AUTO' | 'SELECT' | 'TREE' | 'MODAL'
  rootIds?: string[] | null
  includeDescendants?: boolean
  linkFieldId?: string | null
  linkTargetFieldId?: string | null
  defaultValue?: string | string[] | null
  viewId?: string | null
}
