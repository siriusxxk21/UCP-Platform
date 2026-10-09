import { computed, inject, provide, type ComputedRef, type InjectionKey } from 'vue'

/**
 * 统计下钻「明细不允许编辑」时向下传递的只读约束：只能收不能放。
 * 子级列表、详情页、页面按钮读取它，隐藏或禁用写操作；服务端权限仍独立校验。
 */
export const recordReadOnlyKey: InjectionKey<ComputedRef<boolean>> = Symbol('nocode-record-read-only')
const writable = computed(() => false)

export function useInheritedReadOnly(): ComputedRef<boolean> {
  return inject(recordReadOnlyKey, writable)
}

/** 合并上级约束与本级开关，并继续向下提供；本级只能收紧。 */
export function provideReadOnly(own: () => boolean | undefined): ComputedRef<boolean> {
  const inherited = useInheritedReadOnly()
  const readOnly = computed(() => inherited.value || !!own())
  provide(recordReadOnlyKey, readOnly)
  return readOnly
}
