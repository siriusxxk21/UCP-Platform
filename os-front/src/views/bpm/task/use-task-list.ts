import { onMounted, ref } from 'vue'
import { getCategorySimpleList } from '@/api/bpm/category'
import type { BpmCategoryApi } from '@/api/bpm/category'
import { getSimpleProcessDefinitionList } from '@/api/bpm/definition'
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import { useOsTablePage } from '@/composables/useOsTablePage'
import type { PageParam, PageResult } from '@/types/request'
import { buildCreateTimeParam, type DateRange } from './shared'

interface TaskQuery {
  name?: string
  processInstanceName?: string
  processDefinitionKey?: string
  category?: string
  status?: number
  createTime?: DateRange
}

function optionList<T>(value: T[] | { list?: T[]; records?: T[] } | null | undefined): T[] {
  return Array.isArray(value) ? value : value?.list || value?.records || []
}

/** BPM 只适配底座表格的 pageNo 和时间协议，继续使用既有任务权限接口。 */
export function useTaskList<T>(fetchPage: (params: PageParam) => Promise<PageResult<T>>, withOptions = true) {
  const loadError = ref('')
  const optionsError = ref('')
  const categoryOptions = ref<BpmCategoryApi.Category[]>([])
  const processDefinitionOptions = ref<BpmProcessDefinitionApi.ProcessDefinition[]>([])
  const optionsLoading = ref(false)
  const table = useOsTablePage<T, TaskQuery>({
    defaultQuery: () => ({
      name: undefined,
      processInstanceName: undefined,
      processDefinitionKey: undefined,
      category: undefined,
      status: undefined,
      createTime: undefined
    }),
    queryMode: 'submitted',
    refreshOnActivated: true,
    clearDataOnError: true,
    correctOutOfRange: true,
    fetchFn: ({ pageNum, ...query }) => {
      loadError.value = ''
      return fetchPage({
        ...query,
        pageNo: pageNum,
        name: query.name?.trim() || undefined,
        processInstanceName: query.processInstanceName?.trim() || undefined,
        createTime: buildCreateTimeParam(query.createTime)
      })
    },
    onError: error => {
      loadError.value = error instanceof Error ? error.message : '任务列表加载失败，请重试'
    }
  })

  async function loadOptions() {
    optionsLoading.value = true
    optionsError.value = ''
    const [categories, definitions] = await Promise.allSettled([
      getCategorySimpleList(),
      getSimpleProcessDefinitionList()
    ])
    if (categories.status === 'fulfilled') categoryOptions.value = optionList(categories.value)
    if (definitions.status === 'fulfilled') processDefinitionOptions.value = optionList(definitions.value)
    const failed = [
      categories.status === 'rejected' ? '流程分类' : '',
      definitions.status === 'rejected' ? '所属流程' : ''
    ]
      .filter(Boolean)
      .join('、')
    if (failed) optionsError.value = `${failed}选项加载失败，可重试；已有查询条件仍保留。`
    optionsLoading.value = false
  }

  if (withOptions) onMounted(loadOptions)

  return {
    ...table,
    handleSearch: table.handleQuery,
    loadData: table.fetchData,
    loadError,
    optionsError,
    optionsLoading,
    loadOptions,
    categoryOptions,
    processDefinitionOptions
  }
}
