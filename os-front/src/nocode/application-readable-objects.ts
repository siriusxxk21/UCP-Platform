import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import type { ApplicationApi } from '@/api/nocode/application'
import type { ObjectReference, PublishedObject, ReadableObject } from '@/types/nocode/application'
import { createRequestSession } from './request-session'

export const readableObjectsHint =
  '这些对象没有被本应用引用。系统只读取它们来完成引用选择、名称显示和计算。要为它们建列表或表单，请点“引用对象”。'

/** 一行只读小字；集合为空返回空串（整行不出现）。同一个对象的来源最多列 3 个，超出写“等”。 */
export function readableObjectsSummary(items: readonly ReadableObject[]): string {
  if (!items.length) return ''
  return (
    '因关联而可读取（只读，不能为它们建列表或表单）：' +
    items
      .map(item => {
        const via = [...new Set(item.via.map(source => `${source.fromObjectName}的“${source.name}”`))]
        const sources = via.length ? `（${via.slice(0, 3).join('、')}${via.length > 3 ? '等' : ''}）` : ''
        return `${item.object.definition.objectName}${sources}${item.closed ? '（已关闭）' : ''}`
      })
      .join('、')
  )
}

/**
 * 应用因关联而只读可读的对象（没有被引用，不进「已引用对象」）。
 * 只给两处用：工作台的一行只读小字；设计端按关系 / 规则查「目标 / 来源对象的字段」。
 * 挑选对象、判断资源归属的地方一律不用它。
 */
export function useApplicationReadableObjects(options: {
  api: Pick<ApplicationApi, 'readableObjects'>
  applicationId: () => string
  /** 当前草稿里的引用（可能还没保存）。 */
  references: () => readonly ObjectReference[]
  /** 应用配置加载成功后才拉取。 */
  enabled: () => boolean
}) {
  const items = shallowRef<ReadableObject[]>([])
  const session = createRequestSession()

  async function refresh() {
    const current = session.begin(),
      requested = options.applicationId()
    const objects = options.references().map(({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum }))
    if (!options.enabled() || !requested || !objects.length) {
      items.value = []
      return
    }
    const valid = () => current() && requested === options.applicationId()
    try {
      const result = await options.api.readableObjects({ applicationId: requested, objects })
      // 先发后到的旧结果、切换应用后的在途结果都不能回填。
      if (valid()) items.value = result
    } catch (cause) {
      if (!valid()) return
      items.value = []
      console.warn('[nocode] 因关联而可读取的对象读取失败，不显示这一行', cause)
    }
  }
  // 草稿里的引用一变（增、删、同步版本）就重新拉取；键的写法同工作台对「最新发布版本」的监听。
  watch(
    () =>
      options.enabled()
        ? `${options.applicationId()}|${options
            .references()
            .map(object => `${object.objectId}:${object.versionNo}`)
            .join(',')}`
        : '',
    () => void refresh(),
    { immediate: true }
  )
  onScopeDispose(() => session.invalidate())

  return {
    items: computed(() => items.value),
    /** 对象 id → 定义；只含隐式可读的对象。 */
    objects: computed<Record<string, PublishedObject>>(() =>
      Object.fromEntries(items.value.map(item => [item.object.objectId, item.object]))
    ),
    summary: computed(() => readableObjectsSummary(items.value)),
    refresh
  }
}
