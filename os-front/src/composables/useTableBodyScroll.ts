import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/** Calculates an Ant Design Vue table body's usable height. */
export function useTableBodyScroll() {
  const tableViewportRef = ref<HTMLElement | null>(null)
  const tableScrollY = ref<number | undefined>()
  let resizeObserver: ResizeObserver | undefined
  let frameId: number | undefined

  function refresh() {
    if (frameId !== undefined) cancelAnimationFrame(frameId)
    frameId = requestAnimationFrame(() => {
      frameId = undefined
      const viewport = tableViewportRef.value
      if (!viewport || viewport.clientHeight === 0) {
        tableScrollY.value = undefined
        return
      }

      const tableHeader = viewport.querySelector<HTMLElement>('.ant-table-thead')
        ?? viewport.querySelector<HTMLElement>('.ant-table-header')
      const pagination = viewport.querySelector<HTMLElement>('.ant-table-pagination')
      const paginationStyle = pagination ? getComputedStyle(pagination) : undefined
      const paginationHeight = pagination
        ? pagination.offsetHeight + Number.parseFloat(paginationStyle?.marginTop ?? '0') + Number.parseFloat(paginationStyle?.marginBottom ?? '0')
        : 0
      const availableHeight = Math.floor(viewport.clientHeight - (tableHeader?.offsetHeight ?? 0) - paginationHeight - 8)

      // Keep natural layout when the parent does not provide usable height.
      // Do not assign an unchanged value: changing the table scroll config can
      // reset Ant Design Vue's body scroll position.
      const nextScrollY = availableHeight >= 80 ? availableHeight : undefined
      if (tableScrollY.value !== nextScrollY) tableScrollY.value = nextScrollY
    })
  }

  onMounted(() => {
    resizeObserver = new ResizeObserver(refresh)
    if (tableViewportRef.value) {
      resizeObserver.observe(tableViewportRef.value)
    }
    window.addEventListener('resize', refresh)
    void nextTick(refresh)
  })

  onBeforeUnmount(() => {
    if (frameId !== undefined) cancelAnimationFrame(frameId)
    resizeObserver?.disconnect()
    window.removeEventListener('resize', refresh)
  })

  return { tableViewportRef, tableScrollY, refreshTableBodyScroll: refresh }
}
