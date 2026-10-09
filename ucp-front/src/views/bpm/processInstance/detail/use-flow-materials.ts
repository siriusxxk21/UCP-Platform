import { computed, onScopeDispose, reactive, ref } from 'vue'
import type {
  FlowMaterialApi,
  FlowMaterialDetail,
  FlowMaterialItem,
  FlowMaterialQuery
} from '@/api/nocode/flow-material'
import { errorMessage } from '@/nocode/data-center'

export function useFlowMaterials(api: FlowMaterialApi, query: () => FlowMaterialQuery) {
  const items = ref<FlowMaterialItem[]>([]),
    loading = ref(false),
    loaded = ref(false),
    error = ref(''),
    blockedReason = ref(''),
    reviewToken = ref<string>(),
    reviewRequired = ref(false)
  const activeId = ref('')
  const selectedByNode = reactive<Record<string, string>>({})
  const details = reactive<Record<string, FlowMaterialDetail>>({}),
    detailErrors = reactive<Record<string, string>>({}),
    detailLoading = reactive<Record<string, boolean>>({})
  let generation = 0
  const pending = new Map<string, Promise<boolean>>()
  const currentItems = computed(() => items.value.filter(item => item.state !== 'HISTORY'))
  const activeItem = computed(() => items.value.find(item => item.id === activeId.value))
  const groups = computed(() => {
    const result: { nodeId: string; current: FlowMaterialItem[]; history: FlowMaterialItem[] }[] = []
    for (const item of items.value) {
      let group = result.find(group => group.nodeId === item.nodeId)
      if (!group) {
        group = { nodeId: item.nodeId, current: [], history: [] }
        result.push(group)
      }
      ;(item.state === 'HISTORY' ? group.history : group.current).push(item)
    }
    return result
  })
  const approvalBlocked = computed(() => {
    if (loading.value || !loaded.value) return error.value || '正在读取审批材料'
    if (error.value) return error.value
    if (blockedReason.value) return blockedReason.value
    if (reviewRequired.value && !reviewToken.value) return '审批材料凭据缺失，请刷新后重试'
    const unavailable = currentItems.value.find(
      item => item.required && (item.state === 'UNAVAILABLE' || detailErrors[item.id])
    )
    return unavailable ? `必需材料“${unavailable.nodeName}”暂不可读，请重试或联系管理员` : ''
  })
  function reset() {
    generation++
    items.value = []
    activeId.value = ''
    loaded.value = false
    error.value = ''
    blockedReason.value = ''
    reviewToken.value = undefined
    reviewRequired.value = false
    pending.clear()
    for (const store of [details, detailErrors, detailLoading, selectedByNode])
      for (const id of Object.keys(store)) delete store[id]
  }
  async function loadMaterial(id: string): Promise<boolean> {
    if (details[id]) return true
    if (pending.has(id)) return pending.get(id)!
    const item = items.value.find(item => item.id === id)
    if (!item || item.state === 'UNAVAILABLE') return false
    const stamp = generation,
      scope = query()
    detailLoading[id] = true
    delete detailErrors[id]
    const request = (async () => {
      try {
        const result = await api.detail({ ...scope, materialId: id })
        if (stamp !== generation) return false
        if (result.item?.id !== id || (!result.flowForm && !result.businessForm))
          throw new Error(result.item?.warning || '材料内容不可用，请重试或联系管理员')
        if (result.item.state === 'UNAVAILABLE') throw new Error(result.item.warning || '该材料暂不可读取')
        if (result.item.revision !== item.revision) throw new Error('材料版本已发生变化，请刷新材料目录后重新核对')
        if (result.item.state !== item.state || result.item.kind !== item.kind)
          throw new Error('材料状态已发生变化，请刷新材料目录后重新核对')
        if (result.flowForm) {
          try {
            const conf = JSON.parse(result.flowForm.conf || '{}')
            if (!conf || typeof conf !== 'object' || Array.isArray(conf) || !Array.isArray(result.flowForm.fields))
              throw new Error()
            for (const field of result.flowForm.fields) {
              const rule = JSON.parse(field)
              if (!rule || typeof rule !== 'object') throw new Error()
            }
          } catch {
            throw new Error('材料表单结构不完整，请重新读取或联系管理员')
          }
        }
        details[id] = result
        return true
      } catch (e) {
        if (stamp === generation) detailErrors[id] = errorMessage(e)
        return false
      } finally {
        if (stamp === generation) {
          detailLoading[id] = false
          pending.delete(id)
        }
      }
    })()
    pending.set(id, request)
    return request
  }
  async function load() {
    reset()
    loading.value = true
    const stamp = generation
    try {
      const result = await api.list(query())
      if (stamp !== generation) return
      if (!Array.isArray(result.items)) throw new Error('材料目录响应不完整，请重试')
      items.value = result.items
      reviewToken.value = result.reviewToken
      reviewRequired.value = result.reviewRequired
      blockedReason.value = result.blockedReason || ''
      loaded.value = true
      const first = groups.value[0]
      if (first) await selectStep(first.nodeId)
    } catch (e) {
      if (stamp === generation) error.value = errorMessage(e)
    } finally {
      if (stamp === generation) loading.value = false
    }
  }
  async function select(id: string) {
    const item = items.value.find(item => item.id === id)
    if (!item) return false
    activeId.value = id
    selectedByNode[item.nodeId] = id
    return loadMaterial(id)
  }
  async function selectStep(nodeId: string) {
    const group = groups.value.find(group => group.nodeId === nodeId)
    const id = selectedByNode[nodeId] || group?.current[0]?.id || group?.history[0]?.id
    return id ? select(id) : false
  }
  async function prepareApproval() {
    if (approvalBlocked.value) return false
    const required = currentItems.value.filter(item => item.required)
    const results = await Promise.all(required.map(item => loadMaterial(item.id)))
    return results.every(Boolean) && !approvalBlocked.value
  }
  function renderFailed(id: string, reason: string) {
    delete details[id]
    detailErrors[id] = reason
  }
  function invalidateReview(reason: string) {
    blockedReason.value = reason
    reviewToken.value = undefined
  }
  onScopeDispose(() => generation++)
  return {
    items,
    currentItems,
    groups,
    loading,
    loaded,
    error,
    blockedReason,
    reviewToken,
    reviewRequired,
    activeId,
    activeItem,
    details,
    detailErrors,
    detailLoading,
    approvalBlocked,
    reset,
    load,
    loadMaterial,
    select,
    selectStep,
    prepareApproval,
    renderFailed,
    invalidateReview
  }
}
