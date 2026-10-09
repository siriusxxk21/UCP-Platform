/**
 * 模板类型选项
 */
export const TEMPLATE_TYPE_OPTIONS = [
  { value: 'entity', label: 'entity' },
  { value: 'mapper', label: 'mapper' },
  { value: 'mapper-xml', label: 'mapper-xml' },
  { value: 'service', label: 'service' },
  { value: 'serviceImpl', label: 'serviceImpl' },
  { value: 'controller', label: 'controller' },
  { value: 'dto', label: 'dto' },
  { value: 'vo', label: 'vo' },
  { value: 'query', label: 'query' },
  // [CODEGEN-ADD] 前端模板类型
  { value: 'os_vue', label: 'os_vue（Os列表页）' },
  { value: 'os_drawer', label: 'os_drawer（Os表单抽屉）' },
  { value: 'os_modal', label: 'os_modal（Os子表弹窗）' },
  { value: 'os_data', label: 'os_data（Os数据配置）' },
  { value: 'os_api', label: 'os_api（Os接口定义）' },
  // 旧版模板类型（保留兼容）
  { value: 'vue', label: 'vue' },
  { value: 'vueForm', label: 'vueForm' },
  { value: 'api', label: 'api' },
  { value: 'types', label: 'types' },
] as const

// 从 TEMPLATE_TYPE_OPTIONS 推导模板类型
export type TemplateType = typeof TEMPLATE_TYPE_OPTIONS[number]['value']

/**
 * 状态选项
 */
export const STATUS_OPTIONS = [
  { value: 1, label: '启用', color: 'green' },
  { value: 0, label: '禁用', color: 'red' },
]

/**
 * 数据库类型选项
 */
export const DB_TYPE_OPTIONS = [
  { value: 'mysql', label: 'MySQL' },
  { value: 'oracle', label: 'Oracle' },
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'sqlserver', label: 'SQL Server' },
]

/**
 * 查询类型选项
 */
export const QUERY_TYPE_OPTIONS = [
  { value: 'eq', label: '等于' },
  { value: 'like', label: '模糊' },
  { value: 'gt', label: '大于' },
  { value: 'lt', label: '小于' },
  { value: 'between', label: '范围' },
  { value: 'in', label: '包含' },
]

/**
 * 表单类型选项
 */
export const FORM_TYPE_OPTIONS = [
  { value: 'input', label: '输入框' },
  { value: 'select', label: '下拉框' },
  { value: 'textarea', label: '文本域' },
  { value: 'datetime', label: '日期时间' },
  { value: 'radio', label: '单选框' },
  { value: 'checkbox', label: '复选框' },
  // [CODEGEN-ADD] 扩展表单类型
  { value: 'switch', label: '开关' },
  { value: 'number', label: '数字框' },
  { value: 'file', label: '文件上传' },
  { value: 'image', label: '图片上传' },
  { value: 'popup', label: '弹窗选择' },
  { value: 'dept', label: '部门选择' },
  { value: 'user', label: '用户选择' },
]

/**
 * 模板系统变量
 */
export const TEMPLATE_VARIABLES = {
  base: [
    'tableName',
    'tableComment',
    'className',
    'entityName',
    'instanceName',
    'moduleName',
    'businessName',
    'author',
    'packageName',
    'date',
    'year',
    'pkJavaType',
  ],
  project: ['projectName', 'basePackage'],
  field: ['fields', 'columns', 'pkField', 'hasPk'],
  masterSlave: [
    'tableMode',
    'isMaster',
    'isSlave',
    'hasSubTables',
    'masterTableName',
    'masterEntityName',
    'masterInstanceName',
    'masterModuleName',
    'foreignKeyField',
    'subTables',
  ],
}

/**
 * 字段对象属性（用于模板检查时识别 field.xxx 形式的变量）
 */
export const FIELD_OBJECT_PROPERTIES = [
  'columnName',
  'fieldName',
  'javaField',
  'columnComment',
  'columnType',
  'javaType',
  'jsType',
  'isPk',
  'isInsert',
  'isEdit',
  'isList',
  'isQuery',
  'queryType',
  'formType',
  // [CODEGEN-ADD] 字典类型编码
  'dictType',
  // [CODEGEN-ADD] 逻辑删除标识
  'isLogicalDelete',
  // [CODEGEN-ADD] 校验类型
  'validateType',
]

/**
 * 子表对象属性（用于模板检查时识别 subTable.xxx 形式的变量）
 */
export const SUBTABLE_OBJECT_PROPERTIES = [
  'relationType',
  'tableName',
  'entityName',
  'instanceName',
  'masterField',
  'slaveField',
  'propertyName',
  'cascadeDelete',
  'lazyLoad',
  'fields',
  // [CODEGEN-ADD] 子表逻辑删除标识
  'hasLogicalDelete',
]

/**
 * 分页配置
 */
export const DEFAULT_PAGE_SIZE = 10
export const PAGE_SIZE_OPTIONS = ['10', '20', '50', '100']

// ==================== 主子表关系常量 ====================

// [CODEGEN-ADD] 校验类型选项
export const VALIDATE_TYPE_OPTIONS = [
  { value: '', label: '无' },
  { value: 'notBlank', label: '非空(字符串)' },
  { value: 'notNull', label: '非空(通用)' },
  { value: 'size', label: '长度限制' },
  { value: 'email', label: '邮箱格式' },
  { value: 'pattern', label: '正则校验' },
]

// [CODEGEN-ADD] 逻辑删除选项
export const LOGICAL_DELETE_OPTIONS = [
  { value: false, label: '否' },
  { value: true, label: '是（逻辑删除）' },
]

/**
 * 全局表关系模式选项
 */
export const TABLE_MODE_OPTIONS = [
  { value: 'single', label: '单表模式', description: '独立的单表CRUD' },
  { value: 'tree', label: '树表模式', description: '支持层级结构的数据，如部门、菜单' },
  { value: 'masterSlave', label: '主子表模式', description: '一对多关系，如订单-订单项' },
] as const

/**
 * 表模式选项（单表配置用）
 */
export const SINGLE_TABLE_MODE_OPTIONS = [
  { value: 'single', label: '单表' },
  { value: 'master', label: '主表' },
  { value: 'slave', label: '从表' },
]

/**
 * 关系类型选项
 */
export const RELATION_TYPE_OPTIONS = [
  { value: 'oneToOne', label: '一对一', description: '一条主表数据对应一条从表数据' },
  { value: 'oneToMany', label: '一对多', description: '一条主表数据对应多条从表数据' },
]
