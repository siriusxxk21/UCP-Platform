import type { ComputedRef, Ref, UnwrapNestedRefs } from 'vue'
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref } from 'vue'
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/constants'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'

// ===== 公共类型 =====

export interface PageResult<T> {
  records: T[]
  total: number
  size?: number
  current?: number
}

export interface ListResult<T> {
  list: T[]
  total: number
}

export interface PageParams {
  pageNum: number
  pageSize: number
}

export interface UseOsTablePageOptions<T, Q> {
  /** 数据获取函数 */
  fetchFn: (params: Q & PageParams) => Promise<PageResult<T> | ListResult<T> | T[]>
  /** 默认查询表单（工厂函数，确保每次 reset 都是全新对象） */
  defaultQuery: () => Q
  /** 是否在 onMounted 中自动加载数据（默认 true） */
  immediate?: boolean
  /** 分页响应数据字段格式（默认 auto 自动检测） */
  dataField?: 'records' | 'list' | 'auto'
  /** 默认分页大小（默认 10） */
  defaultPageSize?: number
  /** 分页大小选项 */
  pageSizeOptions?: string[]
  /** 数据加载前钩子：可修改参数（须返回新对象） */
  beforeFetch?: (params: Q & PageParams) => Q & PageParams
  /** 数据加载后钩子：可对数据做后处理 */
  afterFetch?: (data: T[]) => T[]
  /** 加载失败回调 */
  onError?: (error: unknown) => void
  /** 是否自动将 dynamicConditions 合并到请求参数的 conditions 字段（默认 true） */
  autoMergeDynamicConditions?: boolean
  /** 是否自动清除请求参数中值为 undefined / null / '' 的字段（默认 true） */
  stripEmptyParams?: boolean
  /** 显式查询后才应用表单条件，翻页和刷新沿用已提交条件。 */
  queryMode?: 'live' | 'submitted'
  /** 缓存页面重新进入时刷新，保留当前条件和页码。 */
  refreshOnActivated?: boolean
  /** 查询失败时清空旧结果，避免将旧数据误认为本次查询结果。 */
  clearDataOnError?: boolean
  /** 数据减少后自动回到最后一个有效页。 */
  correctOutOfRange?: boolean
}

export interface UseOsTablePageReturn<T, Q> {
  loading: Ref<boolean>
  tableData: Ref<T[]>
  pagination: UnwrapNestedRefs<{
    current: number
    pageSize: number
    total: number
    showSizeChanger: boolean
    showQuickJumper: boolean
    showTotal: (total: number) => string
    pageSizeOptions: string[]
  }>
  queryForm: UnwrapNestedRefs<Q>
  selectedRowKeys: Ref<(string | number)[]>
  selectedRows: Ref<T[]>
  hasSelection: ComputedRef<boolean>
  /** 动态检索条件（OsTablePage @dynamic-search 事件可直接绑定） */
  dynamicConditions: Ref<DynamicSearchCondition | null>
  handleQuery: () => void
  handleReset: () => void
  handleTableChange: (pag: any, filters?: any, sorter?: any) => void
  fetchData: () => Promise<void>
  clearSelection: () => void
  updateSelection: (keys: (string | number)[], rows: T[]) => void
  /** 更新动态检索条件（OsTablePage @dynamic-search 事件可直接绑定） */
  handleDynamicSearch: (conditions: DynamicSearchCondition | null) => void
  getSearchParams: () => Q & PageParams
  /** 获取导出参数（当前查询条件 + 动态检索条件，去除分页字段，已清洗空值） */
  getExportParams: () => Record<string, any>
}

export function useOsTablePage<T = any, Q extends Record<string, any> = Record<string, any>>(
  options: UseOsTablePageOptions<T, Q>
): UseOsTablePageReturn<T, Q> {
  const {
    fetchFn,
    defaultQuery,
    immediate = true,
    dataField = 'auto',
    defaultPageSize = DEFAULT_PAGE_SIZE,
    pageSizeOptions = [...PAGE_SIZE_OPTIONS],
    beforeFetch,
    afterFetch,
    onError,
    autoMergeDynamicConditions = true,
    stripEmptyParams = true,
    queryMode = 'live',
    refreshOnActivated = false,
    clearDataOnError = false,
    correctOutOfRange = false
  } = options

  // ===== 核心状态 =====
  const loading = ref(false)
  const tableData = ref<T[]>([]) as Ref<T[]>
  const queryForm = reactive<Q>(defaultQuery ? defaultQuery() : ({} as Q))
  let appliedQuery = { ...queryForm }
  let requestVersion = 0

  const pagination = reactive({
    current: 1,
    pageSize: defaultPageSize,
    total: 0,
    showSizeChanger: true,
    showQuickJumper: true,
    showTotal: (total: number) => `共 ${total} 条`,
    pageSizeOptions
  })

  // ===== 批量选择状态 =====
  const selectedRowKeys = ref<(string | number)[]>([])
  const selectedRows = ref<T[]>([]) as Ref<T[]>
  const hasSelection = computed(() => selectedRowKeys.value.length > 0)

  // ===== 动态检索条件状态 =====
  const dynamicConditions = ref<DynamicSearchCondition | null>(null)

  /** 更新动态检索条件（OsTablePage @dynamic-search 事件可直接绑定） */
  function handleDynamicSearch(conditions: DynamicSearchCondition | null): void {
    dynamicConditions.value = conditions
  }

  // ===== 数据加载 =====
  async function fetchData(): Promise<void> {
    const version = ++requestVersion
    loading.value = true
    try {
      let params: Q & PageParams = {
        ...(queryMode === 'submitted' ? appliedQuery : queryForm),
        pageNum: pagination.current,
        pageSize: pagination.pageSize
      } as Q & PageParams

      // 自动合并动态检索条件
      if (autoMergeDynamicConditions && dynamicConditions.value?.items?.length) {
        ;(params as any).conditions = dynamicConditions.value
      }

      // 自动清洗空值参数（undefined / null / ''）
      if (stripEmptyParams) {
        const cleaned: Record<string, any> = {}
        for (const key in params) {
          const val = (params as any)[key]
          if (val !== undefined && val !== null && val !== '') {
            cleaned[key] = val
          }
        }
        params = cleaned as Q & PageParams
      }

      if (beforeFetch) {
        params = beforeFetch(params)
      }

      const res = await fetchFn(params)
      // 快速查询或离开页面后返回的旧请求不能覆盖较新的结果和加载状态。
      if (version !== requestVersion) return
      let data: T[] = []

      if (Array.isArray(res)) {
        data = res
        pagination.total = res.length
      } else if (dataField === 'records' || (dataField === 'auto' && 'records' in res)) {
        data = (res as PageResult<T>).records || []
        pagination.total = (res as PageResult<T>).total || 0
      } else if (dataField === 'list' || (dataField === 'auto' && 'list' in res)) {
        data = (res as ListResult<T>).list || []
        pagination.total = (res as ListResult<T>).total || 0
      }

      if (afterFetch) {
        data = afterFetch(data)
      }

      const lastPage = Math.max(1, Math.ceil(pagination.total / pagination.pageSize))
      if (correctOutOfRange && pagination.current > lastPage) {
        pagination.current = lastPage
        await fetchData()
        return
      }
      tableData.value = data
    } catch (error) {
      if (version !== requestVersion) return
      if (clearDataOnError) {
        tableData.value = []
        pagination.total = 0
      }
      console.error('[useOsTablePage] 加载数据失败:', error)
      if (onError) onError(error)
    } finally {
      if (version === requestVersion) loading.value = false
    }
  }

  // ===== 查询操作 =====
  function handleQuery(): void {
    appliedQuery = { ...queryForm }
    pagination.current = 1
    fetchData()
  }

  function handleReset(): void {
    const defaults = defaultQuery ? defaultQuery() : {}
    Object.keys(defaults).forEach(key => {
      ;(queryForm as any)[key] = (defaults as any)[key]
    })
    // 重置时同步清除动态检索条件
    dynamicConditions.value = null
    appliedQuery = { ...queryForm }
    pagination.current = 1
    fetchData()
  }

  // ===== 分页/排序/筛选变化 =====
  function handleTableChange(pag: any, _filters?: any, _sorter?: any): void {
    pagination.current = pag.pageSize !== pagination.pageSize ? 1 : pag.current
    pagination.pageSize = pag.pageSize
    fetchData()
  }

  // ===== 批量选择操作 =====
  function clearSelection(): void {
    selectedRowKeys.value = []
    selectedRows.value = []
  }

  function updateSelection(keys: (string | number)[], rows: T[]): void {
    selectedRowKeys.value = keys
    selectedRows.value = rows
  }

  // ===== 工具方法 =====
  function getSearchParams(): Q & PageParams {
    return {
      ...(queryMode === 'submitted' ? appliedQuery : queryForm),
      pageNum: pagination.current,
      pageSize: pagination.pageSize
    } as Q & PageParams
  }

  /** 获取导出参数（当前查询条件 + 动态检索条件，去除分页字段，已清洗空值） */
  function getExportParams(): Record<string, any> {
    const params: Record<string, any> = { ...(queryMode === 'submitted' ? appliedQuery : queryForm) }

    // 合并动态检索条件
    if (autoMergeDynamicConditions && dynamicConditions.value?.items?.length) {
      params.conditions = dynamicConditions.value
    }

    // 清洗空值
    if (stripEmptyParams) {
      for (const key of Object.keys(params)) {
        const val = params[key]
        if (val === undefined || val === null || val === '') {
          delete params[key]
        }
      }
    }

    return params
  }

  // ===== 自动加载 =====
  if (immediate) {
    onMounted(() => {
      fetchData()
    })
  }

  if (refreshOnActivated) {
    let activated = false
    onActivated(() => {
      if (activated) void fetchData()
      activated = true
    })
    onDeactivated(() => {
      requestVersion++
      loading.value = false
    })
  }
  onBeforeUnmount(() => requestVersion++)

  return {
    loading,
    tableData,
    pagination,
    queryForm,
    selectedRowKeys,
    selectedRows,
    hasSelection,
    dynamicConditions,
    handleQuery,
    handleReset,
    handleTableChange,
    fetchData,
    clearSelection,
    updateSelection,
    handleDynamicSearch,
    getSearchParams,
    getExportParams
  }
}
