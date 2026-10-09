import { onBeforeUnmount, onMounted, ref } from 'vue'

/** 与响应式样式共用断点；旋转屏幕时更新，离开页面后移除监听。 */
export function useCompactViewport(query = '(max-width: 767px)') {
  const media =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query) : null
  const compact = ref(media?.matches ?? false)
  const update = () => {
    compact.value = media?.matches ?? false
  }
  onMounted(() => media?.addEventListener('change', update))
  onBeforeUnmount(() => media?.removeEventListener('change', update))
  return compact
}
