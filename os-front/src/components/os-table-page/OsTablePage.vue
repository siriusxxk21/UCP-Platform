<script setup lang="ts">
import { computed, h, ref, useSlots, watch } from 'vue'
import type { FunctionalComponent } from 'vue'
import type { TableColumnType, PaginationProps, TableProps } from 'ant-design-vue'
import { Modal, Tag as ATag } from 'ant-design-vue'
import { useCompactViewport } from '@/composables/useCompactViewport'
import {
  CloseOutlined,
  DownloadOutlined,
  DownOutlined,
  FilterOutlined,
  SearchOutlined,
  SettingOutlined,
  UploadOutlined
} from '@ant-design/icons-vue'
import OsDynamicSearch from './OsDynamicSearch.vue'
import { summarizeConditions } from './conditionSummary'
import { useResizableColumns } from './hooks/useResizableColumns'
import { isPaginationTransitionLoading, resolvePaginationSnapshot, resolveRowSequence } from './paginationIndex'
import type { DynamicSearchCondition, DynamicSearchField, SavedSearchCondition } from './types'
import { loadSavedConditions, saveSavedConditions } from './types'

// 展开列等底座列的标题是 VNode 数组，插值会把空标题显示为 [""]。
const ColumnTitle = (props: { value: any }) => props.value
const compactViewport = useCompactViewport()

// ===== Props =====

interface Props {
  // --- 表格数据 ---
  columns: TableColumnType[]
  dataSource: any[]
  loading?: boolean
  rowKey?: string | ((record: any) => string | number)
  /** 自定义行属性，用于拖拽等交互；会与组件的行点击事件合并。 */
  customRow?: (record: any, index?: number) => Record<string, any>
  /** 是否显示自动序号列（默认 true） */
  showIndex?: boolean
  /** 序号列宽度（默认 60） */
  indexWidth?: number
  /** 序号列按需固定在左侧，与紧随其后的固定列连续排列。 */
  indexFixed?: 'left' | false
  /** 分页配置，传 false 不显示分页 */
  pagination?: object | false
  /** 当前页已由服务端截取；展开附加行不参与本地分页，总数仍取服务端结果。 */
  serverPagination?: boolean
  scroll?: { x?: number | string; y?: number | string }
  /**
   * 列宽严格按配置（固定表格布局）：内容超出列宽时由单元格自己截断或换行，不把列撑宽。
   * 默认关：横向按内容排（scroll.x 为 max-content 且有固定列时），列宽只是下限。
   */
  fixedLayout?: boolean
  size?: 'small' | 'middle' | 'large'
  /** 表格卡片标题 */
  title?: string

  // --- 批量选择 ---
  /** 开启行选择（true=默认配置，object=自定义配置） */
  rowSelection?: object | boolean
  /** 已选中的行 key 列表（配合 useOsTablePage 的 selectedRowKeys 使用） */
  selectedRowKeys?: (string | number)[]
  /** 已选中的行数据（配合批量操作栏使用） */
  selectedRows?: any[]

  // --- 导入/导出/模板 ---
  showExport?: boolean
  exportText?: string
  showImport?: boolean
  importText?: string
  /** 导入接受的文件类型 */
  importAccept?: string
  /** 开启后，导入按钮变为下拉菜单（含「下载模板」+「上传文件」） */
  showDownloadTemplate?: boolean
  templateText?: string

  // --- 查询区位置 ---
  /**
   * card = 表格上方单独一张查询卡片（默认，原样）；
   * header = 不单独成卡：查询栏（search 插槽 + 高级检索）进表格卡片标题行（标题之后、按钮之前），给了 searchTarget 就挂到那个外部节点；
   * 临时查询、常用查询、searchMore 插槽与静态高级区放在表格卡片正文顶部。
   */
  searchPlacement?: 'card' | 'header'
  /** header 模式的外部挂载点（如页签行右侧）；为空时查询栏留在标题行。 */
  searchTarget?: HTMLElement | null

  // --- 高级检索 ---
  showAdvancedSearch?: boolean
  /** 高级检索按钮文字（默认 '高级检索'） */
  advancedSearchText?: string
  /** 高级检索模式：static=固定表单（slot），dynamic=动态条件组合弹窗 */
  advancedSearchMode?: 'static' | 'dynamic'
  dynamicSearchDisplayMode?: 'modal' | 'drawer'
  /** dynamic 模式必填：可查询字段配置 */
  dynamicSearchFields?: DynamicSearchField[]
  /**
   * 动态检索持久化 key（需与 columnSettingsKey 不同）
   * 用于保存查询条件到 localStorage，不传则不持久化
   */
  searchStorageKey?: string

  // --- 列设置 ---
  showColumnSettings?: boolean
  /** 初始隐藏的列 key 列表 */
  hiddenColumnKeys?: string[]
  /**
   * 列设置的 localStorage 持久化 key（不传则不持久化）
   * 同一页面唯一即可，如：'user-list'，组件内部会拼接前缀
   */
  columnSettingsKey?: string

  // --- 列宽拖拽 ---
  resizable?: boolean

  // --- 树形表格 ---
  /** 树形表格：展开的行 key 列表（配合树形表格的展开/折叠管理） */
  expandedRowKeys?: (string | number)[]
  /** 树形表格：expandable 配置（如 { childrenColumnName: 'children' }） */
  expandable?: object
  /** 树形表格：是否默认展开所有行 */
  defaultExpandAllRows?: boolean

  // --- 批量操作栏 ---
  showBatchBar?: boolean
  batchDeleteText?: string
  /** 批量删除前是否弹出确认框（默认 true） */
  batchDeleteConfirm?: boolean
  /** 批量删除确认框标题（默认 '批量删除确认'） */
  batchDeleteConfirmTitle?: string
  /** 批量删除确认框内容，支持 {count} 占位符（默认 '确定要删除选中的 {count} 条记录吗？此操作不可恢复。'） */
  batchDeleteConfirmContent?: string

  // --- 列枚举 Tag 自动渲染 ---
  /**
   * 列枚举映射：key 为列的 dataIndex/key，value 为 枚举值→{label, color} 的映射表。
   * 配置后 OsTablePage 自动渲染 a-tag，业务页无需再写 bodyCell 插槽处理枚举列。
   *
   * 示例：
   * ```ts
   * tagMap: {
   *   status: { 1: { label: '启用', color: 'success' }, 0: { label: '禁用', color: 'error' } },
   *   userType: { 1: { label: '普通用户' }, 2: { label: '租户管理员', color: 'blue' } },
   * }
   * ```
   */
  tagMap?: Record<string, Record<string | number, { label: string; color?: string }>>

  // --- 表格样式 ---
  /** 是否显示表格边框（默认 false） */
  bordered?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  rowKey: 'id',
  customRow: undefined,
  showIndex: true,
  indexWidth: 60,
  indexFixed: false,
  fixedLayout: false,
  pagination: undefined,
  serverPagination: false,
  size: 'middle',
  rowSelection: false,
  selectedRowKeys: () => [],
  selectedRows: () => [],
  showExport: false,
  exportText: '导出',
  showImport: false,
  importText: '导入',
  importAccept: '.xlsx,.xls,.csv',
  showDownloadTemplate: false,
  templateText: '下载模板',
  searchPlacement: 'card',
  searchTarget: null,
  showAdvancedSearch: false,
  advancedSearchText: '高级检索',
  advancedSearchMode: 'static',
  dynamicSearchFields: () => [],
  showColumnSettings: false,
  hiddenColumnKeys: () => [],
  resizable: false,
  expandedRowKeys: () => [],
  expandable: undefined,
  defaultExpandAllRows: false,
  showBatchBar: true,
  batchDeleteText: '批量删除',
  batchDeleteConfirm: true,
  batchDeleteConfirmTitle: '批量删除确认',
  batchDeleteConfirmContent: '确定要删除选中的 {count} 条记录吗？此操作不可恢复。',

  // --- 表格样式 ---
  bordered: false
})

// ===== Emits =====

const emit = defineEmits<{
  (e: 'change', pagination: any, filters: any, sorter: any, extra: any): void
  /** 行点击事件 */
  (e: 'rowClick', record: any, index: number, event: MouseEvent): void
  (e: 'export'): void
  (e: 'import', file: File): void
  (e: 'downloadTemplate'): void
  (e: 'batchDelete', keys: (string | number)[], rows: any[]): void
  (e: 'selectionChange', keys: (string | number)[], rows: any[]): void
  (e: 'advancedSearchToggle', open: boolean): void
  (e: 'dynamicSearch', conditions: DynamicSearchCondition | null): void
  /** 动态检索确认查询时触发，可直接绑定到 useOsTablePage.handleQuery */
  (e: 'search'): void
  /** 树形表格：展开/折叠事件 */
  (e: 'expand', expanded: boolean, record: any): void
}>()

// ===== 标题栏 =====

const slots = useSlots()
const hasSearch = computed(() => !!slots.search || props.showAdvancedSearch)
const searchInHeader = computed(() => props.searchPlacement === 'header' && hasSearch.value)
const hasHeaderContent = () => props.title || slots.title || slots.actions || props.showImport
const showHeader = computed(() => hasHeaderContent() || searchInHeader.value)

// ===== 高级检索（静态模式） =====

const advancedOpen = ref(false)

function toggleAdvanced() {
  advancedOpen.value = !advancedOpen.value
  emit('advancedSearchToggle', advancedOpen.value)
}

// ===== 动态条件检索（弹窗模式） =====

const dynamicSearchOpen = ref(false)
const dynamicConditions = ref<DynamicSearchCondition | null>(null)
const dynamicSearchRef = ref<InstanceType<typeof OsDynamicSearch> | null>(null)
/** 当前激活的常用查询标签 id（null 表示未激活任何标签） */
const activeTagId = ref<string | null>(null)
/** 未收藏的条件仍参与查询，必须提供可见的编辑和清除入口。 */
const temporaryConditionSummary = computed(() =>
  activeTagId.value ? '' : summarizeConditions(dynamicConditions.value, props.dynamicSearchFields)
)

function openDynamicSearch() {
  dynamicSearchOpen.value = true
}

function handleDynamicConfirm(conditions: DynamicSearchCondition | null, saveDescription: string) {
  dynamicConditions.value = conditions
  emit('dynamicSearch', conditions)

  if (saveDescription && conditions) {
    // 以当前内存列表为基础（搜索 key 存在时已持久化，否则仅内存）
    const list = props.searchStorageKey ? loadSavedConditions(props.searchStorageKey) : [...savedConditions.value]

    // 按标签名称匹配：同名则更新查询条件，不存在则新建
    const existingIdx = list.findIndex(s => s.description === saveDescription)
    if (existingIdx >= 0) {
      // 同名标签已存在 → 更新其查询条件
      list[existingIdx] = { ...list[existingIdx], conditions }
      activeTagId.value = list[existingIdx].id
    } else {
      // 新建常用查询条件
      const saved: SavedSearchCondition = {
        id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        description: saveDescription,
        conditions,
        createdAt: Date.now()
      }
      list.unshift(saved)
      if (list.length > 20) list.length = 20
      activeTagId.value = saved.id
    }

    // 有持久化 key 时写入 localStorage，否则仅更新内存
    if (props.searchStorageKey) {
      saveSavedConditions(props.searchStorageKey, list)
    }
    savedConditions.value = [...list]
  } else {
    // 不保存时清除激活标签
    activeTagId.value = null
  }

  emit('search')
}

/** 点击常用查询标签：互斥单选，再次点击取消激活 */
function applySavedCondition(saved: SavedSearchCondition) {
  if (activeTagId.value === saved.id) {
    // 再次点击已激活的标签 → 取消
    activeTagId.value = null
    dynamicConditions.value = null
    emit('dynamicSearch', null)
  } else {
    activeTagId.value = saved.id
    dynamicConditions.value = saved.conditions
    emit('dynamicSearch', saved.conditions)
  }
  emit('search')
}

/** 从弹窗侧边栏彻底删除常用查询条件 */
function removeSavedCondition(id: string) {
  const filtered = savedConditions.value.filter(s => s.id !== id)
  savedConditions.value = filtered
  // 有持久化 key 时同步写入 localStorage
  if (props.searchStorageKey) {
    saveSavedConditions(props.searchStorageKey, filtered)
  }
  // 若删除的是当前激活的标签，同步清除查询条件并刷新
  if (activeTagId.value === id) {
    activeTagId.value = null
    dynamicConditions.value = null
    emit('dynamicSearch', null)
    emit('search')
  }
}

/**
 * 清除高级查询激活状态（不触发查询，供外部仅清状态时调用）
 */
function clearAdvancedSearch() {
  activeTagId.value = null
  dynamicConditions.value = null
  emit('dynamicSearch', null)
}

/**
 * 普通查询触发入口：清除高级查询状态后触发搜索。
 * 通过 search 插槽的 triggerSearch slot-prop 传递给插槽内的查询按钮调用，
 * 确保普通查询不会携带高级查询条件。
 */
function handleNormalSearch() {
  clearAdvancedSearch()
  emit('search')
}

defineExpose({ clearAdvancedSearch, handleNormalSearch })

// ===== 保存的查询条件列表 =====

const savedConditions = ref<SavedSearchCondition[]>(
  props.searchStorageKey ? loadSavedConditions(props.searchStorageKey) : []
)

// ===== 批量选择 =====

const hasSelection = computed(() => props.selectedRowKeys.length > 0)

/** 跨页选中行数据缓存（key → row），解决翻页后 onChange 只返回当前页行数据的问题 */
const _selectedRowsMap = new Map<string | number, any>()

/** 从行数据解析 rowKey */
function _resolveRowKey(row: any): string | number {
  if (typeof props.rowKey === 'function') return props.rowKey(row)
  return row[String(props.rowKey)]
}

/** 当外部清除选中时，同步清空缓存 */
watch(
  () => props.selectedRowKeys,
  newKeys => {
    if (newKeys.length === 0) {
      _selectedRowsMap.clear()
    } else {
      // 清除已不在选中列表中的缓存
      const keySet = new Set(newKeys)
      for (const k of [..._selectedRowsMap.keys()]) {
        if (!keySet.has(k)) {
          _selectedRowsMap.delete(k)
        }
      }
    }
  }
)

const computedRowSelection = computed(() => {
  if (!props.rowSelection) return undefined
  const base = typeof props.rowSelection === 'object' ? props.rowSelection : {}
  return {
    selectedRowKeys: props.selectedRowKeys,
    preserveSelectedRowKeys: true,
    onChange: (keys: (string | number)[], currentPageRows: any[]) => {
      // 更新当前页的行数据（保持最新）
      for (const row of currentPageRows) {
        _selectedRowsMap.set(_resolveRowKey(row), row)
      }
      // 清除已取消选中的行缓存
      const keySet = new Set(keys)
      for (const k of [..._selectedRowsMap.keys()]) {
        if (!keySet.has(k)) {
          _selectedRowsMap.delete(k)
        }
      }
      // 按选中顺序构建完整行列表
      const allRows = keys.map(k => _selectedRowsMap.get(k)).filter(r => r !== undefined)
      emit('selectionChange', keys, allRows)
    },
    ...base
  }
})

// ===== 列设置 =====

/** 可控制显示/隐藏的列（排除序号列和固定列） */
const settableColumns = computed<TableColumnType[]>(() =>
  props.columns.filter(col => {
    const key = String((col as any).key ?? (col as any).dataIndex ?? '')
    return key && key !== '_index'
  })
)

// ----- localStorage 持久化 -----

function _getColStorageKey(): string | null {
  return props.columnSettingsKey ? `la-col-settings:${props.columnSettingsKey}` : null
}

function _loadColSettings(): string[] | null {
  const key = _getColStorageKey()
  if (!key) return null
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as string[]) : null
  } catch {
    return null
  }
}

function _saveColSettings(keys: Set<string>) {
  const key = _getColStorageKey()
  if (!key) return
  try {
    localStorage.setItem(key, JSON.stringify([...keys]))
  } catch {
    // 忽略存储失败
  }
}

// ----- 初始化可见列（优先从 localStorage 还原） -----

const _allColKeys = props.columns
  .map(col => String((col as any).key ?? (col as any).dataIndex ?? ''))
  .filter(k => k && k !== '_index')

const _savedColKeys = _loadColSettings()
const _initialVisibleKeys =
  _savedColKeys !== null
    ? // 取已保存中仍然有效的列 key
      new Set(_allColKeys.filter(k => _savedColKeys.includes(k)))
    : // 首次访问：排除初始隐藏列
      new Set(_allColKeys.filter(k => !props.hiddenColumnKeys.includes(k)))

/** 当前显示的列 key 集合 */
const visibleColumnKeys = ref<Set<string>>(_initialVisibleKeys.size > 0 ? _initialVisibleKeys : new Set(_allColKeys))

/** 列设置 Popover 显示状态 */
const columnSettingOpen = ref(false)

function toggleColumnVisible(key: string) {
  if (visibleColumnKeys.value.has(key)) {
    if (visibleColumnKeys.value.size <= 1) return
    visibleColumnKeys.value.delete(key)
  } else {
    visibleColumnKeys.value.add(key)
  }
  visibleColumnKeys.value = new Set(visibleColumnKeys.value)
  _saveColSettings(visibleColumnKeys.value)
}

// ===== 列宽拖拽 =====

const { handleResizeStart, getColWidth } = useResizableColumns()

/** 列头单元格要带的把手信息：列 key 与按下时的起始宽度。 */
interface ResizeGrip {
  key: string
  width: number
}

function renderResizeGrip(grip: ResizeGrip) {
  const onMousedown = (event: MouseEvent) => handleResizeStart(event, grip.key, grip.width)
  // 点在把手上不算点列头：可排序列的列头点击是排序。
  const onClick = (event: MouseEvent) => event.stopPropagation()
  return h('div', { class: 'la-resize-handle', onMousedown, onClick })
}

/**
 * 表头单元格：拖拽把手直接挂在 th 上，不放进标题里。
 * 可排序的列，标题外面包着排序容器（省略列还带 overflow:hidden）：把手放在标题里会被裁掉，也到不了单元格右缘。
 */
const ResizableHeaderCell: FunctionalComponent = (_props, { attrs, slots }) => {
  const { resizeGrip, ...cell } = attrs as { resizeGrip?: ResizeGrip }
  return h('th', cell, [slots.default?.(), resizeGrip ? renderResizeGrip(resizeGrip) : null])
}
const resizableComponents = { header: { cell: ResizableHeaderCell } }

interface KeyedColumn {
  key?: unknown
  dataIndex?: unknown
}

/** 给有 key 的列带上把手；列自己的 customHeaderCell 照旧生效。 */
function withResizeGrip(col: TableColumnType): TableColumnType {
  const { key, dataIndex } = col as KeyedColumn
  const id = key ?? dataIndex
  const grip = { resizeGrip: { key: String(id), width: typeof col.width === 'number' ? col.width : 100 } }
  const own = col.customHeaderCell
  const customHeaderCell: TableColumnType['customHeaderCell'] = column => Object.assign({}, own?.(column), grip)
  return id == null ? col : { ...col, customHeaderCell }
}

// ===== 列计算（含序号列、列设置过滤、列宽） =====

const displayedPagination = ref(resolvePaginationSnapshot({ current: 1, pageSize: 0 }, props.pagination, false))

const displayedDataSource = computed(() =>
  isPaginationTransitionLoading(displayedPagination.value, props.pagination, props.loading) ? [] : props.dataSource
)

const serverPageOptions = computed(() => (props.pagination || {}) as PaginationProps)
function changeServerPage(current: number, pageSize: number) {
  const page = { ...serverPageOptions.value, current, pageSize }
  if (pageSize !== serverPageOptions.value.pageSize) {
    page.current = 1
  }
  emit('change', page, {}, {}, { action: 'paginate', currentDataSource: displayedDataSource.value })
}
function changeTablePage(...[pagination, filters, sorter, extra]: Parameters<NonNullable<TableProps['onChange']>>) {
  // 关闭底座本地分页后，排序／筛选事件仍须携带真实服务端页码。
  const page = props.serverPagination
    ? { ...serverPageOptions.value, ...(extra?.action === 'filter' ? { current: 1 } : {}) }
    : pagination
  emit('change', page, filters, sorter, extra)
}

watch(
  [
    () => (props.pagination && (props.pagination as any).current) || 1,
    () => (props.pagination && (props.pagination as any).pageSize) || 0,
    () => props.loading
  ],
  () => {
    displayedPagination.value = resolvePaginationSnapshot(displayedPagination.value, props.pagination, props.loading)
  }
)

const computedColumns = computed<TableColumnType[]>(() => {
  let cols: TableColumnType[] = props.showColumnSettings
    ? props.columns.filter(col => {
        const key = String((col as any).key ?? (col as any).dataIndex ?? '')
        return !key || visibleColumnKeys.value.has(key)
      })
    : [...props.columns]

  // 默认居中对齐：未显式设置 align 的列统一居中
  cols = cols.map(col => (col.align === undefined ? { ...col, align: 'center' as const } : col))
  // 手机上多列固定会遮住整张表；改为表内触摸横滚，所有业务操作仍保留。
  if (compactViewport.value) cols = cols.map(col => ({ ...col, fixed: undefined }))

  if (props.resizable) {
    cols = cols.map(col => {
      const key = String((col as any).key ?? (col as any).dataIndex ?? '')
      const width = getColWidth(key, typeof col.width === 'number' ? col.width : undefined)
      return withResizeGrip(width ? { ...col, width } : col)
    })
  }

  // 列枚举 Tag 自动渲染：为 tagMap 中配置的列注入 customRender
  if (props.tagMap) {
    cols = cols.map(col => {
      const key = String((col as any).key ?? (col as any).dataIndex ?? '')
      const mapping = props.tagMap![key]
      // 无映射或列已有 customRender 则跳过（优先保留业务自定义渲染）
      if (!mapping || (col as any).customRender) return col
      return {
        ...col,
        customRender: ({ text }: { text: any }) => {
          const item = mapping[text]
          if (item) {
            return h(ATag, { color: item.color ?? 'default' }, () => item.label)
          }
          return text ?? '-'
        }
      }
    })
  }

  if (!props.showIndex) return cols

  const indexCol: TableColumnType = {
    title: '序号',
    key: '_index',
    width: props.resizable ? getColWidth('_index', props.indexWidth) : props.indexWidth,
    align: 'center',
    fixed: compactViewport.value ? undefined : props.indexFixed,
    customRender: ({ index }: { index: number }) => {
      return resolveRowSequence(index, displayedPagination.value)
    }
  }
  return [props.resizable ? withResizeGrip(indexCol) : indexCol, ...cols]
})

/**
 * 固定表格布局要有明确的表宽才生效（max-content 会被浏览器当成自动布局）：取各列宽度之和，拖宽后跟着变。
 * 容器更宽时表格仍占满容器（ant 给表格加了 min-width: 100%），多出的宽度按比例分给各列。
 */
const tableScroll = computed(() => {
  const x = computedColumns.value.reduce((sum, col) => sum + (typeof col.width === 'number' ? col.width : 0), 0)
  if (compactViewport.value) {
    const mobileWidth = computedColumns.value.reduce(
      (sum, col) => sum + (typeof col.width === 'number' ? col.width : 140),
      0
    )
    return { ...props.scroll, x: props.scroll?.x ?? Math.max(mobileWidth, x) }
  }
  return props.fixedLayout ? { ...props.scroll, x } : props.scroll
})

/** 批量删除按钮点击：默认弹出确认框，确认后触发 batchDelete 事件 */
function handleBatchDeleteClick() {
  if (props.batchDeleteConfirm) {
    const content = props.batchDeleteConfirmContent.replace('{count}', String(props.selectedRowKeys.length))
    Modal.confirm({
      title: props.batchDeleteConfirmTitle,
      content,
      okText: '确定删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => {
        emit('batchDelete', props.selectedRowKeys, props.selectedRows)
      }
    })
  } else {
    emit('batchDelete', props.selectedRowKeys, props.selectedRows)
  }
}

// ===== 导入处理 =====

function handleBeforeUpload(file: File) {
  emit('import', file)
  return false
}

// ===== 行点击 =====

const tableCustomRow = (record: any, index?: number) => {
  const externalRowProps = props.customRow?.(record, index) || {}
  const externalOnClick = externalRowProps.onClick
  return {
    ...externalRowProps,
    onClick: (event: MouseEvent) => {
      externalOnClick?.(event)
      emit('rowClick', record, index ?? 0, event)
    }
  }
}
</script>

<template>
  <div class="os-table-page">
    <!-- 搜索卡片 -->
    <a-card
      v-if="searchPlacement === 'card' && hasSearch"
      :body-style="{ padding: '16px' }"
      class="os-table-page__search"
    >
      <!-- 基础搜索区域 -->
      <div class="os-table-page__search-basic">
        <div class="os-table-page__search-form">
          <!-- trigger-search：普通查询入口，自动清除高级查询状态 -->
          <slot name="search" :trigger-search="handleNormalSearch" />
        </div>
        <!-- 高级检索入口 -->
        <!-- 动态模式：按钮打开弹窗 -->
        <a-button
          v-if="showAdvancedSearch && advancedSearchMode === 'dynamic'"
          type="link"
          class="os-table-page__advanced-btn"
          @click="openDynamicSearch"
        >
          <FilterOutlined />
          {{ advancedSearchText }}
        </a-button>
        <!-- 静态模式：展开/折叠 -->
        <a-button
          v-else-if="showAdvancedSearch"
          type="link"
          class="os-table-page__advanced-btn"
          @click="toggleAdvanced"
        >
          {{ advancedSearchText }}
          <template v-if="advancedOpen">▲</template>
          <template v-else>▼</template>
        </a-button>
      </div>

      <div v-if="temporaryConditionSummary" class="os-table-page__saved-conditions">
        <span class="os-table-page__saved-label">临时查询：</span>
        <span class="os-table-page__saved-tag os-table-page__temporary-tag is-active">
          <button
            type="button"
            class="os-table-page__temporary-edit"
            :title="`${temporaryConditionSummary}（点击编辑）`"
            @click="openDynamicSearch"
          >
            {{ temporaryConditionSummary }}
          </button>
          <button type="button" aria-label="清除高级检索条件" title="清除高级检索条件" @click="handleNormalSearch">
            <CloseOutlined />
          </button>
        </span>
      </div>

      <!-- 保存的查询条件标签 -->
      <div v-if="savedConditions.length > 0" class="os-table-page__saved-conditions">
        <span class="os-table-page__saved-label">常用查询：</span>
        <div class="os-table-page__saved-tags">
          <ATag
            v-for="saved in savedConditions"
            :key="saved.id"
            class="os-table-page__saved-tag"
            :class="{ 'is-active': activeTagId === saved.id }"
            @click="applySavedCondition(saved)"
          >
            <SearchOutlined style="margin-right: 4px" />
            {{ saved.description }}
          </ATag>
        </div>
      </div>

      <!-- 高级搜索区域（静态模式展开时显示） -->
      <div
        v-show="advancedOpen && showAdvancedSearch && advancedSearchMode !== 'dynamic'"
        class="os-table-page__search-advanced"
      >
        <slot name="advancedSearch" />
      </div>
    </a-card>

    <!-- 动态条件检索弹窗 -->
    <OsDynamicSearch
      v-if="showAdvancedSearch && advancedSearchMode === 'dynamic'"
      ref="dynamicSearchRef"
      v-model:open="dynamicSearchOpen"
      v-model:model-value="dynamicConditions"
      :fields="dynamicSearchFields"
      :display-mode="dynamicSearchDisplayMode"
      :saved-conditions="savedConditions"
      :active-tag-id="activeTagId"
      @confirm="handleDynamicConfirm"
      @remove-saved="removeSavedCondition"
    />

    <!-- 批量操作栏 -->
    <div v-if="showBatchBar && hasSelection" class="os-table-page__batch-bar">
      <span class="os-table-page__batch-info">
        已选择
        <strong>{{ selectedRowKeys.length }}</strong>
        项
      </span>
      <a-space size="small">
        <a-button danger size="small" @click="handleBatchDeleteClick">
          {{ batchDeleteText }}
        </a-button>
        <slot name="batchActions" />
      </a-space>
    </div>

    <!-- 表格卡片 -->
    <a-card
      class="os-table-page__table"
      :class="{ 'os-table-page__table--search-header': searchInHeader && !searchTarget }"
      :body-style="showHeader ? undefined : { paddingTop: 0 }"
      :head-style="showHeader ? undefined : { display: 'none' }"
    >
      <!-- 自定义标题：title 为空且没有 actions 插槽时隐藏标题栏 -->
      <template v-if="showHeader" #title>
        <div v-if="searchInHeader" class="os-table-page__title-row">
          <span class="os-table-page__title-text">
            <slot name="title">{{ title }}</slot>
          </span>
          <!-- 查询栏只渲染一份：有外部挂载点（页签行）时挂过去，没有就留在标题行。 -->
          <Teleport :to="searchTarget" :disabled="!searchTarget">
            <div class="os-table-page__search-bar" data-search-bar>
              <div class="os-table-page__search-inline">
                <slot name="search" :trigger-search="handleNormalSearch" />
              </div>
              <a-button
                v-if="showAdvancedSearch && advancedSearchMode === 'dynamic'"
                type="link"
                class="os-table-page__advanced-btn"
                @click="openDynamicSearch"
              >
                <FilterOutlined />
                {{ advancedSearchText }}
              </a-button>
              <a-button
                v-else-if="showAdvancedSearch"
                type="link"
                class="os-table-page__advanced-btn"
                @click="toggleAdvanced"
              >
                {{ advancedSearchText }}
                {{ advancedOpen ? '▲' : '▼' }}
              </a-button>
            </div>
          </Teleport>
        </div>
        <slot v-else name="title">{{ title }}</slot>
      </template>

      <!-- 操作按钮区 -->
      <template #extra>
        <a-space>
          <!-- 导入（含模板下载） -->
          <template v-if="showImport">
            <a-dropdown v-if="showDownloadTemplate" :trigger="['click']">
              <a-button>
                <UploadOutlined />
                {{ importText }}
                <DownOutlined />
              </a-button>
              <template #overlay>
                <a-menu>
                  <a-menu-item key="template" @click="$emit('downloadTemplate')">
                    <DownloadOutlined />
                    {{ templateText }}
                  </a-menu-item>
                  <a-menu-item key="upload">
                    <a-upload :accept="importAccept" :before-upload="handleBeforeUpload" :show-upload-list="false">
                      <span>
                        <UploadOutlined />
                        上传文件
                      </span>
                    </a-upload>
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>

            <a-upload v-else :accept="importAccept" :before-upload="handleBeforeUpload" :show-upload-list="false">
              <a-button>
                <UploadOutlined />
                {{ importText }}
              </a-button>
            </a-upload>
          </template>

          <!-- 导出 -->
          <a-button v-if="showExport" @click="$emit('export')">
            <DownloadOutlined />
            {{ exportText }}
          </a-button>

          <!-- 扩展工具栏 -->
          <slot name="toolbar" />

          <!-- 列设置 -->
          <a-popover
            v-if="showColumnSettings"
            v-model:open="columnSettingOpen"
            trigger="click"
            placement="bottomRight"
            :arrow="false"
          >
            <a-button title="列设置">
              <SettingOutlined />
            </a-button>
            <template #content>
              <div class="la-column-setting-panel">
                <div
                  v-for="col in settableColumns"
                  :key="String((col as any).key ?? (col as any).dataIndex)"
                  class="la-column-setting-item"
                >
                  <a-checkbox
                    :checked="visibleColumnKeys.has(String((col as any).key ?? (col as any).dataIndex))"
                    @change="() => toggleColumnVisible(String((col as any).key ?? (col as any).dataIndex))"
                  >
                    {{ col.title }}
                  </a-checkbox>
                </div>
              </div>
            </template>
          </a-popover>

          <!-- 业务操作按钮 -->
          <slot name="actions" />
        </a-space>
      </template>

      <!-- header 模式：查询栏之外的查询内容（更多条件、临时查询、常用查询、静态高级区）放在表格正文顶部，不单独成卡。 -->
      <div v-if="searchInHeader && $slots.searchMore" class="os-table-page__search-more" data-search-more>
        <slot name="searchMore" :trigger-search="handleNormalSearch" />
      </div>
      <div
        v-if="searchInHeader && temporaryConditionSummary"
        class="os-table-page__saved-conditions os-table-page__saved-conditions--inline"
      >
        <span class="os-table-page__saved-label">临时查询：</span>
        <span class="os-table-page__saved-tag os-table-page__temporary-tag is-active">
          <button
            type="button"
            class="os-table-page__temporary-edit"
            :title="`${temporaryConditionSummary}（点击编辑）`"
            @click="openDynamicSearch"
          >
            {{ temporaryConditionSummary }}
          </button>
          <button type="button" aria-label="清除高级检索条件" title="清除高级检索条件" @click="handleNormalSearch">
            <CloseOutlined />
          </button>
        </span>
      </div>
      <div
        v-if="searchInHeader && savedConditions.length > 0"
        class="os-table-page__saved-conditions os-table-page__saved-conditions--inline"
      >
        <span class="os-table-page__saved-label">常用查询：</span>
        <div class="os-table-page__saved-tags">
          <ATag
            v-for="saved in savedConditions"
            :key="saved.id"
            class="os-table-page__saved-tag"
            :class="{ 'is-active': activeTagId === saved.id }"
            @click="applySavedCondition(saved)"
          >
            <SearchOutlined style="margin-right: 4px" />
            {{ saved.description }}
          </ATag>
        </div>
      </div>
      <div
        v-if="searchInHeader"
        v-show="advancedOpen && showAdvancedSearch && advancedSearchMode !== 'dynamic'"
        class="os-table-page__search-advanced os-table-page__search-advanced--inline"
      >
        <slot name="advancedSearch" />
      </div>

      <!-- 表格 -->
      <a-table
        :class="{ 'os-table-page__ant-table--resizable': resizable }"
        :components="resizable ? resizableComponents : undefined"
        :bordered="bordered"
        :columns="computedColumns"
        :data-source="displayedDataSource"
        :loading="loading"
        :pagination="serverPagination ? false : (pagination ?? false)"
        :row-key="rowKey"
        :scroll="tableScroll"
        :table-layout="fixedLayout ? 'fixed' : undefined"
        :size="size"
        :row-selection="computedRowSelection"
        :custom-row="tableCustomRow"
        :expandable="expandable"
        :expanded-row-keys="expandedRowKeys"
        :default-expand-all-rows="defaultExpandAllRows"
        @change="changeTablePage"
        @expand="(expanded: any, record: any) => $emit('expand', expanded, record)"
      >
        <!-- 标题插槽保留列标题字符串供列设置使用，不替换排序与拖拽行为。 -->
        <template v-if="resizable || $slots.columnTitle" #headerCell="{ column }">
          <div
            class="la-resizable-th-content"
            :style="{
              justifyContent:
                (column as any).align === 'left'
                  ? 'flex-start'
                  : (column as any).align === 'right'
                    ? 'flex-end'
                    : 'center'
            }"
          >
            <!-- 展开列等底座列（没有 key）的标题是 VNode 数组，不交给标题插槽：调用方按文字渲染会显示成 [""]。 -->
            <slot
              v-if="(column as any).key != null || (column as any).dataIndex != null"
              name="columnTitle"
              :column="column"
            >
              <span><ColumnTitle :value="column.title" /></span>
            </slot>
            <span v-else><ColumnTitle :value="column.title" /></span>
          </div>
        </template>

        <!-- 自定义单元格 -->
        <template v-if="$slots.bodyCell" #bodyCell="slotProps">
          <slot name="bodyCell" v-bind="slotProps" />
        </template>

        <!-- 统计行 -->
        <template v-if="$slots.summary" #summary>
          <slot name="summary" />
        </template>

        <!-- 展开行 -->
        <template v-if="$slots.expandedRowRender" #expandedRowRender="slotProps">
          <slot name="expandedRowRender" v-bind="slotProps" />
        </template>

        <!-- 空状态 -->
        <template v-if="$slots.empty" #emptyText>
          <slot name="empty" />
        </template>
      </a-table>
      <a-pagination
        v-if="serverPagination && pagination"
        v-bind="serverPageOptions"
        class="os-table-page__pagination"
        @change="changeServerPage"
      />
    </a-card>
  </div>
</template>

<style scoped lang="less">
.os-table-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  box-sizing: border-box;
  gap: 12px;
}

// ===== 搜索区 =====

.os-table-page__search {
  flex-shrink: 0;
}

.os-table-page__search-basic {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.os-table-page__search-form {
  flex: 1;
  min-width: 0;

  :deep(.ant-form-inline) {
    flex-wrap: wrap;
    gap: 8px 0;
  }
}

.os-table-page__advanced-btn {
  flex-shrink: 0;
  padding: 0 4px;
}

// ===== 保存的查询条件 =====

.os-table-page__saved-conditions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #f0f0f0;
}

.os-table-page__saved-label {
  font-size: 13px;
  color: #8c8c8c;
  flex-shrink: 0;
  line-height: 24px;
}

.os-table-page__saved-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.os-table-page__saved-tag {
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
  // 未激活：描边样式（蓝色轮廓，白色背景）
  color: var(--brand) !important;
  background: #fff !important;
  border-color: #91caff !important;

  &:hover {
    border-color: var(--brand) !important;
    background: var(--brand-light) !important;
  }

  // 激活：实色填充
  &.is-active {
    color: #fff !important;
    background: var(--brand) !important;
    border-color: var(--brand) !important;
    box-shadow: 0 2px 6px rgba(67, 56, 202, 0.35);
  }
}

.os-table-page__temporary-tag {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  border: 1px solid;
  border-radius: 4px;

  button {
    flex-shrink: 0;
    padding: 2px 8px;
    border: 0;
    background: transparent;
    color: inherit;
    font: inherit;
    font-size: 13px;
    line-height: 20px;
    cursor: pointer;

    &:focus-visible {
      outline: 2px solid currentColor;
      outline-offset: -3px;
    }
  }

  .os-table-page__temporary-edit {
    flex-shrink: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: left;
  }
}

// ===== 高级搜索（静态模式） =====

.os-table-page__search-advanced {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #f0f0f0;
}

// ===== 查询栏在标题行 / 外部挂载点（header 模式） =====

// 标题行（查询栏留在标题行时；挂到页签行时标题行与原来一样）：标题 → 查询栏 → 按钮。三者一行放不下时按钮整组换到下一行（靠右）；标题加查询栏仍放不下时，查询栏整组换到标题下方。
// 标题按内容宽度占位（不是 ant 默认的 flex: 1 平分剩余宽度），否则查询栏会被按钮挤成窄窄的一列。
.os-table-page__table--search-header {
  // 标题区换成多行时上下留白（单行时仍是原来的 56px 最小高度）。
  > :deep(.ant-card-head) {
    padding-top: 8px;
    padding-bottom: 8px;
  }

  > :deep(.ant-card-head .ant-card-head-wrapper) {
    flex-wrap: wrap;
    row-gap: 8px;
  }

  > :deep(.ant-card-head .ant-card-head-title) {
    flex: 0 1 auto;
    min-width: 0;
    overflow: visible;
    white-space: normal;
  }

  > :deep(.ant-card-head .ant-card-extra) {
    margin-inline-start: auto;
  }
}

.os-table-page__title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  min-width: 0;
}

.os-table-page__title-text {
  flex: none;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

// 查询栏可能被挂到页签行（不在卡片标题里）：字号、字重自己定，不继承标题的粗体。
// 高级检索紧跟在查询表单右侧、与第一行对齐（同原查询卡片）；放不下时是表单里面的控件换行。
.os-table-page__search-bar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
  font-size: 14px;
  font-weight: normal;
  line-height: 1.5715;

  :deep(.ant-form-inline) {
    flex-wrap: wrap;
    gap: 8px 12px;
  }

  :deep(.ant-form-inline .ant-form-item) {
    max-width: 100%;
    margin-inline-end: 0;
    margin-bottom: 0;
  }

  // 很窄时（如 375 宽）输入框按可用宽度收窄，不伸出卡片。
  :deep(.ant-form-item-control) {
    min-width: 0;
  }

  :deep(.ant-form-item-control-input-content > *) {
    max-width: 100%;
  }
}

.os-table-page__search-inline {
  flex: 0 1 auto;
  min-width: 0;
}

.os-table-page__search-bar > .os-table-page__advanced-btn {
  flex: none;
}

.os-table-page__search-more {
  flex-shrink: 0;
  margin-bottom: 8px;

  :deep(.ant-form-inline) {
    flex-wrap: wrap;
    gap: 8px 12px;
  }

  :deep(.ant-form-inline .ant-form-item) {
    margin-inline-end: 0;
    margin-bottom: 0;
  }
}

.os-table-page__saved-conditions--inline {
  flex-shrink: 0;
  margin: 0 0 8px;
  padding-top: 0;
  border-top: 0;
}

.os-table-page__search-advanced--inline {
  flex-shrink: 0;
  margin: 0 0 8px;
  padding-top: 0;
  border-top: 0;
}

// 窄屏：查询栏独占标题下一行、占满宽度；右侧按钮再换到下一行。
@media (max-width: 768px) {
  .os-table-page__table--search-header {
    > :deep(.ant-card-head .ant-card-head-title) {
      flex: 1 1 100%;
    }
  }

  .os-table-page__search-bar {
    flex: 1 1 100%;
  }
}

// ===== 批量操作栏 =====

.os-table-page__batch-bar {
  flex-shrink: 0;
  padding: 8px 16px;
  background: var(--brand-light);
  border: 1px solid rgba(67, 56, 202, 0.15);
  border-radius: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.os-table-page__batch-info {
  font-size: 13px;
  color: #262626;

  strong {
    color: var(--brand);
  }
}

// ===== 表格卡片 =====

.os-table-page__pagination {
  align-self: flex-end;
  margin-block: var(--spacing-lg);
}

.os-table-page__table {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.ant-card-body) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    padding: 12px 16px;
  }

  :deep(.ant-table-wrapper) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.ant-spin-nested-loading) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.ant-spin-container) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  :deep(.ant-table) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.ant-pagination) {
    flex-shrink: 0;
  }

  :deep(.ant-table-container) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.ant-table-content) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.ant-table-header) {
    flex-shrink: 0;
  }

  // 纵向滚动沿用 ant 自己的 overflow-y: scroll（始终留着滚动条的位置）：表头是按「表体有滚动条」排的，
  // 这里改成 auto 的话，行数少、没有滚动条时表头的固定列会比表体靠左一个滚动条宽，盖住前一列的右缘和它的拖拽把手。
  :deep(.ant-table-body) {
    flex: 1;
    min-height: 0;
  }

  :deep(.os-table-page__ant-table--resizable .ant-table-thead > tr > th:not(:last-child)::before) {
    display: none;
  }

  // 修复：ellipsis + fixed 列的表头被 ant-table-cell-content 包裹后 overflow:hidden 导致 resize 手柄被裁剪
  :deep(.os-table-page__ant-table--resizable .ant-table-thead .ant-table-cell-ellipsis .ant-table-cell-content) {
    overflow: visible;
  }

  // ===== 固定列右侧白边修复 =====
  // Ant Design 5 固定列使用 position:sticky 在单元格级别实现，
  // 最后一个固定列单元格需要确保紧贴容器右边缘
  :deep(.ant-table-cell-fix-right.ant-table-cell-fix-right-last) {
    right: 0 !important;
    box-shadow: none !important;
  }
}

// ===== 列宽拖拽 =====

.la-resizable-th-content {
  position: relative;
  display: flex;
  align-items: center;
  user-select: none;
}

// 把手是 th 的直接子节点（由 ResizableHeaderCell 渲染，不带本组件的 scoped 标记），贴着单元格右缘、占满单元格高度。
:deep(.la-resize-handle) {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 8px;
  cursor: col-resize;
  z-index: 1;

  &::after {
    content: '';
    position: absolute;
    right: 4px;
    top: 50%;
    height: 14px;
    margin-top: -7px;
    width: 2px;
    background: #d9d9d9;
    border-radius: 1px;
    transition: background 0.2s;
  }

  &:hover::after {
    background: var(--brand);
  }
}

// ===== 列设置面板 =====

:global(.la-column-setting-panel) {
  min-width: 130px;
  max-height: 320px;
  overflow-y: auto;
  padding: 4px 0;
}

:global(.la-column-setting-item) {
  padding: 5px 12px;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s;

  &:hover {
    background: #f5f5f5;
  }

  .ant-checkbox-wrapper {
    width: 100%;
    white-space: nowrap;
  }
}

:deep(.ant-table-placeholder),
:deep(.ant-table-tbody > tr.ant-table-placeholder > td) {
  border-bottom: none !important;
}
</style>
