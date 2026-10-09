import type { DataClassification, MemberState, RelationType, ObjectSource, StructureMode } from './enums'
import type { ObjectDraft, ObjectField, SaveObjectDraft } from './object'

/** 所有数据库与字段 ID 保持字符串，避免 JavaScript 大整数丢失精度。 */
export interface CalculationOptions {
  mode: 'LOCAL' | 'RELATION' | 'LOOKUP' | 'STATISTICS' | 'RUNNING_TOTAL' | 'SEQUENCE'
  updateMode: 'LIVE' | 'ON_SAVE'
  targetObjectId: string | null
  relationId: string | null
  targetField: string | null
  aggregate: 'SINGLE' | 'COUNT' | 'SUM' | 'AVG' | 'MIN' | 'MAX'
  logic: 'AND' | 'OR'
  conditions: { targetField: string; operator: string; localField: string | null; value: any }[]
  excludeCurrent: boolean
  /** 当前对象的基本单值字段 code，最多 5 个；留空表示整表。 */
  groupFields?: string[]
  /** 按升序累计；固定期初保留十进制字符串，与首笔字段期初互斥。 */
  runningTotal?: {
    orderField: string
    tieBreakerField: string | null
    subtractField: string | null
    initialValue: string | null
    initialField: string | null
  } | null
  /** 通用顺序计算：表达式可用 __previous_字段编码 引用同组相邻记录的基础字段。 */
  sequence?: {
    orderField: string
    tieBreakerField: string | null
    direction: 'PREVIOUS' | 'NEXT'
    operation?: 'ADJACENT' | 'CUMULATIVE'
    initialValue?: string
  } | null
}
/** 试算只使用调用者给定样例，十进制金额保持字符串。 */
export interface FormulaPreviewRequest {
  expression: string
  fieldCodes: string[]
  values: Record<string, string | null>
  fieldTypes?: Record<string, string>
  rows?: Record<string, string | null>[]
  sequence?: CalculationOptions['sequence']
  groupFields?: string[]
}
export interface FormulaPreviewResult {
  value: string | null
  resultType: string
  referencedFields: string[]
  rows?: { index: number; value: string | null; contribution: string | null; adjacentIndex: number | null }[]
}
/** 对象字段共享的编号规则，随对象版本发布。 */
export interface AutoNumberOptions {
  prefix: string
  dateFormat: '' | 'yyyy' | 'yyyyMM' | 'yyyyMMdd'
  sequenceLength: number
  startValue: number
  resetCycle: 'NONE' | 'YEAR' | 'MONTH' | 'DAY'
}
export interface FieldOptions {
  autoNumber?: AutoNumberOptions | null
  calculation?: CalculationOptions | null
  selection?: import('./selection').SelectionSource | null
  columnName?: string | null
  classification: DataClassification
  defaultValue?: string | null
  description?: string | null
  pattern?: string | null
  minimum?: string | null
  maximum?: string | null
  state: MemberState
  options: { code: string; label: string; disabled: boolean }[]
  expression?: string | null
  resultType?: string | null
  resolver: string
  nativeType?: string | null
  primaryKey?: boolean
  generated?: boolean
  /** 字段级对象规则（引用筛选、数据联动、公式默认值、取整方式），随对象版本冻结。 */
  rules?: import('./field-rules').FieldRules | null
}
/** 业务分组层：fieldId 引用主表字段；format 为空按字段类型取值，日期字段可选 YEAR/MONTH。 */
export interface BusinessFileGroup {
  fieldId: string
  format: 'YEAR' | 'MONTH' | null
}
/** 对象业务文件接入规则：随对象版本冻结，运行期按发布版本读取；旧配置的数组字段可能为空。 */
export interface BusinessFilePolicy {
  /** 新规则使用稳定业务空间编号；为空仅表示历史 name-only 规则。 */
  spaceId?: string | null
  spaceName: string
  fixedPath: string[]
  groups: BusinessFileGroup[]
  recordLabelFields: string[]
  fieldIds: string[]
}
export interface ObjectSettings {
  documentPolicy?: import('./document-policy').DocumentPolicy | null
  businessFilePolicy?: BusinessFilePolicy | null
  icon: string | null
  ownerId: string | null
  organizationId: string | null
  titleTemplate: string | null
}
export interface ObjectRelation {
  sourceDetailId?: string | null
  id: string | null
  code: string
  name: string
  kind: RelationType
  targetObjectId: string
  fieldId: string | null
  targetFieldId: string | null
  required: boolean
  onDelete: string
}
export interface ObjectIndex {
  id: string | null
  code: string
  name: string
  unique: boolean
  fieldIds: string[]
  parentScoped?: boolean
}
/** 保存原物理身份和能力边界；只读为服务端复核后的有效能力。 */
export interface TableBinding {
  source: ObjectSource
  schemaName: string
  keyColumn: string
  parentColumn: string | null
  structureMode: StructureMode
  readOnly: boolean
  repairBaseFields: boolean
  fingerprint: string | null
}
export interface ObjectDetail {
  id: string | null
  code: string
  name: string
  tableName: string
  state: string
  fields: ObjectField[]
  fieldOptions: Record<string, FieldOptions>
  indexes: ObjectIndex[]
  binding?: TableBinding
}
export interface ObjectVersion {
  versionNo: number
  state: string
  checksum: string
  createdAt: string
  publishedAt: string | null
  publishedBy: string | null
}
export interface Dependency {
  sourceKind: string
  sourceKey: string
  sourceName: string
  targetObjectId: string
  fieldIds: string[]
}
export interface ObjectDesign {
  draft: ObjectDraft
  settings: ObjectSettings
  fieldOptions: Record<string, FieldOptions>
  relations: ObjectRelation[]
  indexes: ObjectIndex[]
  source: string
  schemaName: string
  publishedVersion: number | null
  status: string
  readOnly: boolean
  versions: ObjectVersion[]
  dependencies: Dependency[]
  details: ObjectDetail[]
  mainBinding?: TableBinding
}
export interface SaveDesign {
  restoredFieldIds?: string[]
  draft: SaveObjectDraft
  settings: ObjectSettings
  fieldOptions: Record<string, FieldOptions>
  relations: ObjectRelation[]
  indexes: ObjectIndex[]
  details: ObjectDetail[]
  mainBinding?: TableBinding
}
/** 停用字段保留原身份与配置；是否允许恢复由服务端复核。 */
export interface InactiveField {
  field: ObjectField
  options: FieldOptions
  restorable: boolean
  blockedReason: string | null
}
export interface Revision {
  id: string
  expectedLockVersion: number
  reason?: string
}
export type ObjectOperation = 'enable' | 'disable' | 'delete' | 'disable_field' | 'restore_field'
export interface ObjectOperationPreviewRequest {
  objectId: string
  expectedLockVersion: number
  operation: ObjectOperation
  fieldId?: string
  detailId?: string | null
  proposed?: SaveDesign
}
export interface ObjectOperationPreview {
  objectId: string
  revision: number
  operation: ObjectOperation
  allowed: boolean
  summary: string
  impacts: {
    code: string
    blocking: boolean
    sourceKind: string
    sourceId: string | null
    sourceName: string
    fieldId: string | null
    location: string
    message: string
    resolution: string
    route: string | null
  }[]
  dataScopes: {
    kind: string
    name: string
    schemaName: string
    tableName: string
    columnName?: string | null
    rowCount: number | null
    nonNullCount?: number | null
    retained: boolean
    message: string
  }[]
  steps: string[]
}
export interface StructureCheck {
  code: string
  message: string
  blocking: boolean
}
/** 变更影响由发布计划复核；定位到失效配置后由用户自行处理。 */
export interface ConversionImpact {
  fieldId: string
  sourceKind: string
  sourceId: string
  sourceName: string
  location: string
  message: string
  route: string | null
  blocking: boolean
}
export interface FieldConversion {
  fieldId: string
  detailId: string | null
  fieldName: string
  sourceName: string
  fromType: string
  toType: string
  fromFieldType?: string
  toFieldType?: string
  affectedRows: number
  deletedRows: number
  action?: 'CLEAR_COLUMN' | 'PRESERVE_VALUES' | 'KEEP_COLUMN'
  conversionRule?: string | null
  failedRows?: number
  masked: boolean
  fingerprint: string
  clearAllowed: boolean
  impacts: ConversionImpact[]
}
export interface FieldConversionRow {
  id: string
  title: string | null
  parentId: string | null
  oldValue: unknown
  newValue?: unknown
  failureReason?: string | null
  failureCodes?: string[]
  deleted: boolean
}
export interface FieldConversionRows {
  rows: FieldConversionRow[]
  total: number
  pageNo: number
  pageSize: number
}
/** 编辑阶段的只读提示；真实清空与依赖复查仍由发布计划负责。 */
export interface FieldSwitchPreviewRequest {
  objectId: string
  detailId?: string | null
  fieldId: string
  targetType: string
  length?: number | null
  precision?: number | null
  scale?: number | null
  selection?: import('./selection').SelectionSource | null
  targetObjectId?: string | null
  detachRelation?: boolean
  targetRequired?: boolean
  targetUnique?: boolean
  targetMinimum?: string | null
  targetMaximum?: string | null
  targetPattern?: string | null
  targetDefaultValue?: string | null
  targetOptions?: FieldOptions['options'] | null
}
export interface FieldSwitchPreviewRowsRequest extends FieldSwitchPreviewRequest {
  pageNo: number
  pageSize: number
}
export interface PublishedFieldBaseline {
  type: string
  length: number | null
  precision: number | null
  scale: number | null
  required: boolean
  unique: boolean
  selection: import('./selection').SelectionSource | null
  targetObjectId: string | null
  minimum: string | null
  maximum: string | null
  pattern: string | null
  defaultValue?: string | null
  options?: FieldOptions['options']
}
export interface FieldSwitchPreview {
  objectId: string
  detailId: string | null
  fieldId: string
  fieldName: string
  sourceType: string
  targetType: string
  deploymentState: 'DEPLOYED' | 'UNPUBLISHED' | 'MISSING_COLUMN'
  totalRows: number | null
  valueRows: number | null
  deletedRows?: number | null
  decision: 'PRESERVE' | 'CLEAR_COLUMN' | 'BLOCKED' | 'UNPUBLISHED'
  explanation: string
  impacts: ConversionImpact[]
  failedRows?: number | null
  conversionRule?: string | null
  conflicts?: { code: string; count: number; message: string }[]
  storage?: {
    schemaName: string
    tableName: string
    columnName: string
    actualType: string | null
    targetType: string | null
    nullable: boolean | null
    defaultExpression: string | null
    primaryKey: boolean | null
    generated: boolean | null
    ddlRequired: boolean | null
    ddlExplanation: string
  } | null
}
export interface ApplicationUpgradeImpact {
  applicationId: string
  applicationName: string
  revision: number
  applicationVersion: number
  objectVersion: number
  reasons: string[]
  blockers: string[]
  route: string
}
export interface PublishPlan {
  id: string
  objectId: string
  revision: number
  versionNo: number
  state: string
  changes: { kind: string; message: string }[]
  checks: StructureCheck[]
  dependencies: Dependency[]
  conversions?: FieldConversion[]
  applicationUpgrades?: ApplicationUpgradeImpact[]
  createdAt: string
}
export interface PublishExecution {
  id: string
  objectId: string
  versionNo: number
  state: string
  reason: string | null
  error: string | null
  createdAt: string
  executedAt: string | null
}
export interface ObjectRow {
  category?: string
  id: string
  objectCode: string
  objectName: string
  tableName: string
  schemaName: string
  source: string
  status: string
  publishedVersion: number | null
  fieldCount: number
  detailCount: number
  relationCount: number
  lockVersion: number
  settings: ObjectSettings
  updatedAt: string
}
export interface TableRow {
  schemaName: string
  tableName: string
  comment: string | null
  kind: string
  management: string
  role: string
  system: boolean
  objectId: string | null
  objectName: string | null
  publishedVersion: number | null
  estimatedRows: number
  totalBytes: number
  structureState: string
  verifiedAt: string | null
}
export interface DatabaseColumn {
  ordinal: number
  name: string
  nativeType: string
  nullable: boolean
  defaultExpression: string | null
  identityKind: string
  generatedKind: string
  primaryKeyPosition: number
  comment: string | null
}
export interface TableStructure {
  relation: { schema: string; name: string; kind: string; comment: string | null }
  columns: DatabaseColumn[]
  constraints: { name: string; kind: string; definition: string; validated: boolean }[]
  indexes: { name: string; unique: boolean; primary: boolean; valid: boolean; definition: string }[]
  triggers: { name: string; enabled: string; definition: string }[]
  statistics: {
    estimatedRows: number
    tableBytes: number
    indexBytes: number
    totalBytes: number
    rowSecurity: boolean
    forceRowSecurity: boolean
    canSelect: boolean
    canWrite: boolean
  }
}
export interface TableDetail {
  table: TableRow
  structure: TableStructure
  fingerprint: string
  fieldMapping: Record<string, string>
  checks: StructureCheck[]
}
export interface AdoptionPreflight {
  schemaName: string
  tableName: string
  fingerprint: string
  allowed: boolean
  readOnly: boolean
  checks: StructureCheck[]
  titleColumns: string[]
  structure: TableStructure
}
export interface TablePreview {
  columns: string[]
  rows: Record<string, unknown>[]
  hasMore: boolean
  maskedColumns: string[]
}
export interface ImportColumn {
  code: string
  name: string
  type: string
  length: number | null
  precision: number | null
  scale: number | null
  required: boolean
  unique: boolean
}
export interface ImportPreview {
  columns: ImportColumn[]
  errors: { row: number; message: string }[]
}
export interface ObjectQuery {
  category?: string
  pageNo: number
  pageSize: number
  name?: string
  code?: string
  status?: string
  source?: string
  ownerId?: string
}
export interface TableQuery {
  pageNo: number
  pageSize: number
  schema?: string
  name?: string
  management?: string
  role?: string
  objectId?: string
  structureState?: string
  includeSystem?: boolean
}
export interface Page<T> {
  list: T[]
  total: number
}

export interface ReconcilePreview {
  id: string
  revision: number
  fingerprint: string
  allowed: boolean
  changes: { kind: string; message: string }[]
  checks: StructureCheck[]
}
