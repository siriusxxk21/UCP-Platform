import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { BpmModelApi } from '@/api/bpm/model'
import type { BpmCategoryApi } from '@/api/bpm/category'

export const ALL_MODELS = 'all'
export const UNCLASSIFIED_MODELS = 'unclassified'
export const categoryKey = (id: string | number) => `category:${id}`

/** 分类计数始终来自完整列表；名称过滤不能改变分类删除与全量排序的判断。 */
export function groupModels(models: BpmModelApi.Model[], categories: BpmCategoryApi.Category[]) {
  const groups = categories.map(category => ({
    ...category,
    key: categoryKey(category.id),
    models: [] as BpmModelApi.Model[]
  }))
  const unclassified: BpmModelApi.Model[] = []
  for (const model of models) {
    const group = model.category
      ? groups.find(category => category.code === model.category)
      : groups.find(category => category.name === model.categoryName)
    ;(group?.models || unclassified).push(model)
  }
  return { groups, unclassified }
}

/** 现有模型接口返回完整授权列表，分页与分类过滤在本页处理，不向接口伪造分页参数。 */
export function useModelCatalog(api: {
  models: () => Promise<BpmModelApi.Model[]>
  categories: () => Promise<BpmCategoryApi.Category[]>
}) {
  const models = ref<BpmModelApi.Model[]>([])
  const categories = ref<BpmCategoryApi.Category[]>([])
  const selectedKey = ref(ALL_MODELS)
  const queryName = ref('')
  const appliedName = ref('')
  const current = ref(1)
  const pageSize = ref(10)
  const loading = ref(false)
  const error = ref('')
  const sortIds = ref<(string | number)[] | null>(null)
  let requestId = 0
  const grouped = computed(() => groupModels(models.value, categories.value))
  const selectedCategory = computed(() => grouped.value.groups.find(category => category.key === selectedKey.value))
  const categoryModels = computed(() => {
    if (selectedKey.value === ALL_MODELS) return models.value
    if (selectedKey.value === UNCLASSIFIED_MODELS) return grouped.value.unclassified
    return selectedCategory.value?.models || []
  })
  const filteredModels = computed(() => {
    if (sortIds.value) {
      const rows = new Map(models.value.map(row => [String(row.id), row]))
      return sortIds.value.map(id => rows.get(String(id))).filter((row): row is BpmModelApi.Model => !!row)
    }
    const keyword = appliedName.value.toLocaleLowerCase()
    return categoryModels.value.filter(model => !keyword || model.name.toLocaleLowerCase().includes(keyword))
  })
  const tableData = computed(() =>
    filteredModels.value.slice((current.value - 1) * pageSize.value, current.value * pageSize.value)
  )
  const pagination = computed(() => ({
    current: current.value,
    pageSize: pageSize.value,
    total: filteredModels.value.length,
    showSizeChanger: true,
    showQuickJumper: true,
    pageSizeOptions: ['10', '20', '50', '100'],
    showTotal: (total: number) => `共 ${total} 条`
  }))
  watch([() => filteredModels.value.length, pageSize], () => {
    current.value = Math.max(1, Math.min(current.value, Math.ceil(filteredModels.value.length / pageSize.value)))
  })
  async function loadData() {
    const id = ++requestId
    loading.value = true
    error.value = ''
    try {
      const [nextModels, nextCategories] = await Promise.all([api.models(), api.categories()])
      if (id !== requestId) return
      models.value = nextModels
      categories.value = nextCategories
      if (
        selectedKey.value !== ALL_MODELS &&
        selectedKey.value !== UNCLASSIFIED_MODELS &&
        !nextCategories.some(category => categoryKey(category.id) === selectedKey.value)
      )
        selectedKey.value = ALL_MODELS
      if (selectedKey.value === UNCLASSIFIED_MODELS && !grouped.value.unclassified.length)
        selectedKey.value = ALL_MODELS
    } catch (reason: unknown) {
      if (id !== requestId) return
      models.value = []
      categories.value = []
      error.value = reason instanceof Error ? reason.message : '流程模型加载失败，请重试'
    } finally {
      if (id === requestId) loading.value = false
    }
  }
  function selectCategory(key: string) {
    selectedKey.value = key
    current.value = 1
  }
  function search() {
    appliedName.value = queryName.value.trim()
    current.value = 1
  }
  function reset() {
    queryName.value = ''
    appliedName.value = ''
    selectedKey.value = ALL_MODELS
    current.value = 1
  }
  function changePage(page: { current?: number; pageSize?: number }) {
    const nextSize = page.pageSize || pageSize.value
    current.value = nextSize === pageSize.value ? page.current || 1 : 1
    pageSize.value = nextSize
  }
  onBeforeUnmount(() => requestId++)
  return {
    models,
    categories,
    selectedKey,
    queryName,
    appliedName,
    current,
    pageSize,
    loading,
    error,
    sortIds,
    grouped,
    selectedCategory,
    categoryModels,
    filteredModels,
    tableData,
    pagination,
    loadData,
    selectCategory,
    search,
    reset,
    changePage
  }
}
