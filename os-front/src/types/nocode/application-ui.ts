import { v4 as uuidv4 } from 'uuid'
/** 开源编辑器的规则经适配后持久化；业务配置不保存任意脚本或组件源码。 */
export const NodeKind = {
  ROW: 'ROW',
  COLUMN: 'COLUMN',
  CARD: 'CARD',
  TABS: 'TABS',
  TAB: 'TAB',
  DIVIDER: 'DIVIDER',
  DETAIL: 'DETAIL',
  INTERNAL_DETAIL: 'INTERNAL_DETAIL',
  RELATED: 'RELATED',
  ATTACHMENTS: 'ATTACHMENTS',
  PROCESSES: 'PROCESSES',
  TASKS: 'TASKS',
  TEXT: 'TEXT',
  HEADING: 'HEADING',
  IMAGE: 'IMAGE',
  ALERT: 'ALERT',
  BUTTON: 'BUTTON',
  FLEX: 'FLEX',
  SPACER: 'SPACER',
  VIEW: 'VIEW',
  REPORT: 'REPORT',
  REPORT_DASHBOARD: 'REPORT_DASHBOARD',
  FORM: 'FORM',
  FIELD: 'FIELD',
  METRIC: 'METRIC',
  /** 设计引擎：iframe 嵌入外部设计服务，固定绑定页面当前记录（laneEG）。 */
  ENGINE: 'ENGINE'
} as const
export type NodeKind = (typeof NodeKind)[keyof typeof NodeKind]
/** 发布任务视图在服务端叠加固定范围；运行时筛选不能放宽该范围。 */
export interface TaskViewConfig {
  businessFormId?: string | null
  columnKeys?: string[] | null
  conditions?: import('@/components/os-table-page/types').DynamicSearchCondition | null
  taskFilter?: {
    statuses?: import('./task-center').TaskState[] | null
    urgencies?: import('./task-center').TaskUrgency[] | null
    priorities?: import('./task-center').TaskPriority[] | null
    category?: 'PROJECT' | 'DAILY' | null
  } | null
  templateIds?: string[] | null
  sort?: { field: string; descending: boolean } | null
}
export interface UiNode {
  id: string
  type: NodeKind
  fieldId: string | null
  resourceId: string | null
  text: string | null
  span: number | null
  children: UiNode[]
  /** 表单内的内部明细区块；DETAIL 仍表示业务页面的记录详情。 */
  detail?: { detailId: string; mode?: 'GRID' | 'CARDS' } | null
  binding?: { relationId: string; direction: 'INCOMING' | 'OUTGOING' } | null
  presentation?: {
    label?: string
    placeholder?: string
    help?: string
    readOnly?: boolean
    /** 附件字段保存位置提示的显隐；缺省表示展示，仅接入业务网盘的字段可配置。 */
    showBusinessPath?: boolean
    selection?: import('./selection').SelectionPresentation
    behavior?: FieldBehavior | null
    fill?: FormFillBinding | null
  } | null
  style?: PageStyle | null
  display?: PageDisplay | null
  action?: PageAction | null
  taskView?: TaskViewConfig | null
  engine?: EngineBlockConfig | null
}
/** 设计引擎区块配置；字段映射的值都是对象字段 ID。 */
export interface EngineLibraryConfig {
  objectId: string
  fields: Partial<
    Record<'name' | 'manufacturer' | 'model' | 'specification' | 'unit' | 'unitPrice' | 'methodCode', string>
  >
}
export interface EngineWritebackConfig {
  objectId: string
  recordFieldId: string
  keyFieldId: string
  fields: Partial<
    Record<'quantity' | 'material' | 'name' | 'unit' | 'unitPrice' | 'methodCode' | 'basis' | 'space', string>
  >
}
export interface EngineBlockConfig {
  /** 站内路径；空 = 部署默认（/engine01/）。 */
  engineUrl?: string | null
  materials?: EngineLibraryConfig | null
  components?: EngineLibraryConfig | null
  bom?: EngineWritebackConfig | null
}
export interface FieldBehavior {
  showWhen?: import('./document-policy').DocumentExpression | null
  requiredWhen?: import('./document-policy').DocumentExpression | null
  readOnlyWhen?: import('./document-policy').DocumentExpression | null
  clearWhenHidden?: boolean
}
export const FormFillMode = { DEFAULT: 'DEFAULT', SOURCE_CHANGE: 'SOURCE_CHANGE' } as const
export interface FormFillBinding {
  sourceFieldId: string
  valueFieldId: string
  mode: keyof typeof FormFillMode
  /** 显式开启才随来源清空，旧配置默认保留快照。 */
  clearOnSourceEmpty?: boolean
}
export interface FormFillQuery {
  applicationId: string
  objectId: string
  formId: string
  sourceFieldId: string
  selectedId: string | null
  detailId?: string
  recordId?: string
}
export type FormFillPreviewQuery = Omit<FormFillQuery, 'formId'>
export const PageActionKind = {
  CREATE: 'CREATE',
  EDIT: 'EDIT',
  VIEW: 'VIEW',
  REFRESH: 'REFRESH',
  OPEN_PAGE: 'OPEN_PAGE',
  NAVIGATE: 'NAVIGATE',
  EXECUTE_ACTION: 'EXECUTE_ACTION'
} as const
export type PageActionKind = (typeof PageActionKind)[keyof typeof PageActionKind]
export const PageAlign = { START: 'START', CENTER: 'CENTER', END: 'END', SPACE_BETWEEN: 'SPACE_BETWEEN' } as const
export const PageDirection = { ROW: 'ROW', COLUMN: 'COLUMN' } as const
export const PageAlertType = { INFO: 'INFO', SUCCESS: 'SUCCESS', WARNING: 'WARNING', ERROR: 'ERROR' } as const
export const PageButtonType = { PRIMARY: 'PRIMARY', DEFAULT: 'DEFAULT', TEXT: 'TEXT' } as const
export const PageImageFit = { CONTAIN: 'CONTAIN', COVER: 'COVER' } as const
export interface PageStyle {
  padding?: number
  gap?: number
  marginBottom?: number
  minHeight?: number
  radius?: number
  background?: string
  color?: string
  border?: boolean
  align?: keyof typeof PageAlign
  direction?: keyof typeof PageDirection
}
export interface PageDisplay {
  headingLevel?: number
  alertType?: keyof typeof PageAlertType
  buttonType?: keyof typeof PageButtonType
  imageFileId?: string
  imageAlt?: string
  imageFit?: keyof typeof PageImageFit
  imageHeight?: number
}
export interface PageAction {
  kind: PageActionKind
  targetNodeId?: string
  resourceId?: string
  openMode?: RecordOpenMode
  confirmText?: string
}
export interface ViewConfig {
  composition?: import('./data-view').DataViewComposition | null
  query?: import('./data-scope').ViewQueryOptions | null
  objectId: string
  fieldIds: string[]
  equal: Record<string, unknown>
  sortFieldId: string | null
  descending: boolean
  pageSize: number
  formId: string | null
  filterDictionaries?: Record<string, string>
  detailPageId?: string | null
  interaction?: ViewInteraction | null
  list?: ViewListConfig | null
}
/** 平台统一列表的业务配置；用户列偏好单独保存，不回写发布配置。 */
export interface ViewListConfig {
  queryFieldIds: string[]
  advancedFieldIds: string[] | null
  columnWidths: Record<string, number>
  batchDelete: boolean
  /** 内容超出列宽时的显示方式。没有这个键＝没有配置：沿用按字段类型的既有显示（多行文本、富文本换行，其余单行、列随内容变宽）。 */
  overflow?: ListOverflow | null
}
/** 目前只有自动截断：列宽严格按配置（不再被内容撑宽），单行显示、超出部分省略。 */
export const ListOverflow = { ELLIPSIS: 'ELLIPSIS' } as const
export type ListOverflow = (typeof ListOverflow)[keyof typeof ListOverflow]
export const ViewButton = {
  CREATE: 'CREATE',
  IMPORT: 'IMPORT',
  EXPORT: 'EXPORT',
  VIEW: 'VIEW',
  UPDATE: 'UPDATE',
  DELETE: 'DELETE'
} as const
export type ViewButton = (typeof ViewButton)[keyof typeof ViewButton]
export const RecordOpenMode = { DRAWER: 'DRAWER', MODAL: 'MODAL' } as const
export type RecordOpenMode = (typeof RecordOpenMode)[keyof typeof RecordOpenMode]
/** 仅决定界面呈现，服务端对象/操作权限始终独立校验。空列表表示不展示该类操作。 */
export interface ViewInteraction {
  buttons: ViewButton[]
  actionIds: string[]
  editMode: RecordOpenMode
  detailMode: RecordOpenMode
}
export interface FormConfig {
  relatedForms?: RelatedFormBinding[]
  objectId: string
  nodes: UiNode[]
  detailIds: string[]
  detailNodes?: Record<string, UiNode[]>
  options?: {
    layout: 'vertical' | 'horizontal'
    submitText: string
    readOnly?: boolean
    relationLayout?: boolean
    /** 仅在当前应用的同一对象内作为列表默认表单；缺省保持旧自动布局。 */
    defaultForObject?: boolean
  } | null
}
/** 独立记录仍保存在自己的对象中，关系由发布配置确定。 */
export interface RelatedFormBinding {
  id: string
  sourceObjectId: string
  relationId: string
  direction: 'INCOMING' | 'OUTGOING'
  formId: string
  title: string
}
export interface PageConfig {
  filters?: import('./report').ReportFilter[] | null
  nodes: UiNode[]
  contextObjectId?: string | null
  protocolVersion?: number | null
}
export interface MenuConfig {
  targetId: string
  /** 缺省为旧应用内导航；第二版入口随应用草稿和发布快照管理。 */
  navigationVersion?: 2
  showInMenu?: boolean
  platformParentId?: string
  menuName?: string
  icon?: string
  sort?: number
  defaultHome?: boolean
}
export function uiNode(type: NodeKind, values: Partial<UiNode> = {}): UiNode {
  return {
    id: uuidv4(),
    type,
    fieldId: null,
    resourceId: null,
    text: null,
    span: null,
    children: [],
    ...values
  }
}
