import type { TableColumnType } from 'ant-design-vue'

// ===== 动态条件搜索相关类型 =====

/** 动态搜索字段配置 */
export interface DynamicSearchField {
  /** 业务选择器扩展，复用分组、运算符及条件管理。 */
  valueComponent?: import('vue').Component
  valueProps?: Record<string, unknown>
  /** 字段唯一标识（提交时的 key） */
  field: string
  /** 字段显示名称 */
  label: string
  /** 字段类型（决定值输入框类型） */
  type: 'text' | 'number' | 'select' | 'date' | 'dateRange' | 'datetimeRange'
  /** 运算符选项（不传则根据 type 使用默认运算符） */
  operators?: DynamicSearchOperator[]
  /** select 类型的选项列表 */
  options?: { label: string; value: any }[]
  /** 是否必填（有值才发送该条件） */
  required?: boolean
  /** 高精度数字以字符串提交；不影响既有系统列表。 */
  stringMode?: boolean
  /** 日期值的传输格式，可保留业务字段的时区。 */
  valueFormat?: string
  /** 自定义取值控件的值在条件摘要里的显示文字；返回 undefined 时按默认规则显示。 */
  formatValue?: (value: unknown) => string | undefined
}

/** 支持的运算符 */
export type DynamicSearchOperator =
  | 'isNull'
  | 'notNull'
  | 'eq' // 等于
  | 'neq' // 不等于
  | 'like' // 包含
  | 'notLike' // 不包含
  | 'startWith' // 开头是
  | 'endWith' // 结尾是
  | 'gt' // 大于
  | 'gte' // 大于等于
  | 'lt' // 小于
  | 'lte' // 小于等于
  | 'in' // 在范围内
  | 'containsAny' // 业务多选字段包含任意
  | 'containsAll' // 业务多选字段包含全部
  | 'between' // 在区间内（日期范围）

/** 运算符显示标签 */
export const OPERATOR_LABELS: Record<DynamicSearchOperator, string> = {
  isNull: '为空',
  notNull: '不为空',
  eq: '等于',
  neq: '不等于',
  like: '包含',
  notLike: '不包含',
  startWith: '开头是',
  endWith: '结尾是',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
  in: '在范围内',
  containsAny: '包含任意',
  containsAll: '包含全部',
  between: '在区间内'
}

/** 字段类型默认运算符 */
export const TYPE_DEFAULT_OPERATORS: Record<DynamicSearchField['type'], DynamicSearchOperator[]> = {
  text: ['eq', 'like', 'notLike', 'startWith', 'endWith', 'neq'],
  number: ['eq', 'neq', 'gt', 'gte', 'lt', 'lte'],
  select: ['eq', 'neq', 'in'],
  date: ['eq', 'gt', 'gte', 'lt', 'lte'],
  dateRange: ['between'],
  datetimeRange: ['between']
}

/** 单个条件项（叶子节点） */
export interface DynamicConditionItem {
  /** 唯一 id（前端用，非必要传后端） */
  id: string
  /** 类型标识 */
  type: 'condition'
  /** 字段标识 */
  field: string
  /** 运算符 */
  operator: DynamicSearchOperator
  /** 值 */
  value: any
}

/** 条件组（递归树型结构，支持嵌套分组） */
export interface ConditionGroup {
  /** 唯一 id（前端用） */
  id: string
  /** 类型标识 */
  type: 'group'
  /** 条件逻辑：AND / OR */
  logic: 'AND' | 'OR'
  /** 子项列表：可以是条件项，也可以是嵌套的条件组 */
  items: ConditionItem[]
}

/** 条件项联合类型（条件项 | 条件组） */
export type ConditionItem = DynamicConditionItem | ConditionGroup

// ===== 序列化类型（传给后端 DynamicConditionDTO.Item，无内部 id）=====

/**
 * 序列化的条件叶子节点（单个条件，无内部 id）
 *
 * 对应后端 DynamicConditionDTO.Item（type="condition"）：
 * field, operator, value
 */
export interface SerializedConditionLeaf {
  type: 'condition'
  field: string
  operator: DynamicSearchOperator
  value: any
}

/**
 * 序列化的条件组（嵌套分组，含 type: 'group' 标识，无内部 id）
 *
 * 对应后端 DynamicConditionDTO.Item（type="group"）：
 * groupLogic, groupItems（与后端字段名一致）
 */
export interface SerializedConditionGroup {
  type: 'group'
  /** 嵌套组的逻辑（对应后端 Item.groupLogic） */
  groupLogic: 'AND' | 'OR'
  /** 嵌套组的子项列表（对应后端 Item.groupItems） */
  groupItems: SerializedItem[]
}

/** 序列化条目联合类型 */
export type SerializedItem = SerializedConditionLeaf | SerializedConditionGroup

/**
 * 动态搜索条件（顶层结构，传给后端 DynamicConditionDTO）
 *
 * 根级别字段名 logic / items 与后端 DynamicConditionDTO 一致。
 * 嵌套组使用 groupLogic / groupItems 与后端 DynamicConditionDTO.Item 一致。
 *
 * 传给后端的序列化格式：
 * ```json
 * {
 *   "logic": "OR",
 *   "items": [
 *     { "type": "group", "groupLogic": "AND", "groupItems": [
 *       { "type": "condition", "field": "username", "operator": "like", "value": "admin" },
 *       { "type": "condition", "field": "status", "operator": "eq", "value": 1 }
 *     ]},
 *     { "type": "group", "groupLogic": "AND", "groupItems": [
 *       { "type": "condition", "field": "nickname", "operator": "like", "value": "测试" }
 *     ]}
 *   ]
 * }
 * ```
 */
export interface DynamicSearchCondition {
  /** 条件逻辑（对应后端 DynamicConditionDTO.logic） */
  logic: 'AND' | 'OR'
  /** 子项列表（对应后端 DynamicConditionDTO.items） */
  items: SerializedItem[]
}

/** 类型守卫：判断是否为条件组 */
export function isConditionGroup(item: ConditionItem): item is ConditionGroup {
  return item.type === 'group'
}

/** 类型守卫：判断是否为条件项 */
export function isConditionItem(item: ConditionItem): item is DynamicConditionItem {
  return item.type === 'condition'
}

/**
 * 将内部 ConditionGroup（带 id）序列化为传后端的格式（去掉内部 id）
 *
 * - 条件叶子：{ type: 'condition', field, operator, value }
 * - 嵌套分组：{ type: 'group', groupLogic, groupItems: [...] }  （与后端 Item 字段名一致）
 * - 根级别：{ logic, items: [...] }  （与后端 DynamicConditionDTO 字段名一致）
 */
export function serializeConditions(group: ConditionGroup): DynamicSearchCondition {
  function serializeItem(item: ConditionItem): SerializedItem {
    if (isConditionGroup(item)) {
      return {
        type: 'group',
        groupLogic: item.logic,
        groupItems: item.items.map(serializeItem)
      }
    }
    return {
      type: 'condition',
      field: item.field,
      operator: item.operator,
      value: item.value
    }
  }

  return {
    logic: group.logic,
    items: group.items.map(serializeItem)
  }
}

// ===== 保存的查询条件（localStorage 持久化）=====

/** 保存的查询条件 */
export interface SavedSearchCondition {
  /** 唯一标识 */
  id: string
  /** 用户输入的查询条件概述 */
  description: string
  /** 保存的查询条件 */
  conditions: DynamicSearchCondition
  /** 保存时间戳 */
  createdAt: number
}

/** 保存条件的 localStorage 辅助函数 */
export function loadSavedConditions(storageKey: string): SavedSearchCondition[] {
  if (!storageKey) return []
  try {
    const raw = localStorage.getItem(`la-saved-search:${storageKey}`)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

export function saveSavedConditions(storageKey: string, items: SavedSearchCondition[]) {
  if (!storageKey) return
  try {
    localStorage.setItem(`la-saved-search:${storageKey}`, JSON.stringify(items))
  } catch {
    // 忽略存储失败
  }
}

// ===== os-table-page Props 类型 =====

export interface OsTablePageProps {
  // ===== 表格数据 =====
  columns: TableColumnType[]
  dataSource: any[]
  loading?: boolean
  rowKey?: string | ((record: any) => string | number)
  showIndex?: boolean
  indexWidth?: number
  indexFixed?: 'left' | false
  pagination?: object | false
  scroll?: { x?: number | string; y?: number | string }
  size?: 'small' | 'middle' | 'large'
  title?: string

  // ===== 批量选择 =====
  rowSelection?: object | boolean
  selectedRowKeys?: (string | number)[]
  selectedRows?: any[]

  // ===== 导入/导出/模板 =====
  showExport?: boolean
  exportText?: string
  showImport?: boolean
  importText?: string
  importAccept?: string
  showDownloadTemplate?: boolean
  templateText?: string

  // ===== 高级检索 =====
  showAdvancedSearch?: boolean
  advancedSearchDefaultOpen?: boolean
  advancedSearchText?: string
  advancedSearchMode?: 'static' | 'dynamic'
  dynamicSearchFields?: DynamicSearchField[]

  // ===== 列设置 =====
  showColumnSettings?: boolean
  hiddenColumnKeys?: string[]

  // ===== 列宽拖拽 =====
  resizable?: boolean

  // ===== 批量操作栏 =====
  showBatchBar?: boolean
  batchDeleteText?: string
}
