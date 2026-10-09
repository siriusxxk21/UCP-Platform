import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { nocodePlatformKey } from './platform'
import { storedOrderedCalculation } from './calculation-presentation'
import { errorMessage } from './data-center'
import type { PublishedObject } from '@/types/nocode/application'
import type { OrderedCalculationState } from '@/types/nocode/ordered-calculation'

export function orderedReadiness(states: OrderedCalculationState[] = []): Record<string, string> {
  return Object.fromEntries(states.map(state => [state.fieldId, state.state]))
}

export function orderedStateLabel(state?: string): string {
  return (
    ({ PENDING: '待校准', BACKFILLING: '校准中', READY: '已就绪', FAILED: '校准失败' } as Record<string, string>)[
      state || ''
    ] || '状态待核实'
  )
}

/** 设计器只查询状态；运行端直接使用 RecordModel 中按权限过滤后的状态。 */
export function useOrderedCalculationStates(objects: () => Record<string, PublishedObject>) {
  const platform = inject(nocodePlatformKey, null)
  const states = ref<Record<string, OrderedCalculationState[]>>({})
  const failure = ref('')
  const loading = ref(false)
  let generation = 0
  watch(
    () =>
      Object.values(objects())
        .filter(object => Object.values(object.definition.fieldOptions).some(storedOrderedCalculation))
        .map(object => ({ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum })),
    async references => {
      const current = ++generation
      states.value = {}
      failure.value = ''
      loading.value = false
      if (!references.length) return
      if (!platform || !platform.hasPermission('nocode:object:query')) {
        failure.value = '无法读取有序计算校准状态；就绪前不可配置这些字段的筛选或统计。'
        return
      }
      loading.value = true
      const results = await Promise.allSettled(
        references.map(async reference => ({
          objectId: reference.objectId,
          values: await platform.objectData.calculationStatus(reference.objectId)
        }))
      )
      if (current !== generation) return
      for (const result of results) {
        if (result.status === 'fulfilled') states.value[result.value.objectId] = result.value.values
        else failure.value = errorMessage(result.reason)
      }
      loading.value = false
    },
    { immediate: true, deep: true }
  )
  onBeforeUnmount(() => generation++)
  return {
    states,
    readiness: computed(() =>
      Object.fromEntries(Object.entries(states.value).map(([id, value]) => [id, orderedReadiness(value)]))
    ),
    failure,
    loading
  }
}
