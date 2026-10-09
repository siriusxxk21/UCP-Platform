import {reactive, ref, type Ref, watch} from 'vue'

/**
 * 列宽拖拽 Composable（带 RAF 优化 + 相邻列补偿）
 *
 * 使用方式：
 * ```ts
 * const { columnWidths, onResizeStart } = useColumnResize(columns)
 * ```
 * 在 headerCell 模板中通过 @mousedown="onResizeStart($event, col.key)" 绑定
 * 在列宽样式中使用 `:style="{ width: columnWidths[col.key] + 'px', minWidth: ... }"` 绑定
 */
export interface ColumnDef {
  key: string
  width: string
  [k: string]: any
}

/** 从列配置中解析像素宽度，默认 120 */
export function parseColWidth(w: string | number): number {
  const n = typeof w === 'number' ? w : parseInt(w, 10)
  return isNaN(n) || n <= 0 ? 120 : n
}

export function useColumnResize<T extends ColumnDef>(columns: Ref<T[]>) {
  /** 各列当前宽度 */
  const columnWidths = reactive<Record<string, number>>({})

  /** 自动同步列宽：新增列用默认值，移除列清理 */
  function syncColumnWidths() {
    const widths: Record<string, number> = {}
    for (const col of columns.value) {
      widths[col.key] = columnWidths[col.key] || parseColWidth(col.width)
    }
    for (const k of Object.keys(columnWidths)) {
      if (!(k in widths)) delete columnWidths[k]
    }
    Object.assign(columnWidths, widths)
  }

  watch(columns, syncColumnWidths, { immediate: true })

  // ── 拖拽状态 ──
  const resizing = ref<{
    colKey: string
    startX: number
    startW: number
    nextKey: string
    nextStartW: number
  } | null>(null)

  let rafId: number | null = null

  function onResizeStart(e: MouseEvent, colKey: string) {
    const cols = columns.value
    const idx = cols.findIndex(c => c.key === colKey)
    if (idx === -1 || idx >= cols.length - 1) return
    const nextCol = cols[idx + 1]
    resizing.value = {
      colKey,
      startX: e.clientX,
      startW: columnWidths[colKey] || parseColWidth(cols[idx].width),
      nextKey: nextCol.key,
      nextStartW: columnWidths[nextCol.key] || parseColWidth(nextCol.width),
    }
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'
    const tw = (e.target as HTMLElement)?.closest('.table-wrap')
    tw?.classList.add('is-resizing')
    window.addEventListener('mousemove', onResizeMove, { passive: true } as any)
    window.addEventListener('mouseup', onResizeEnd, { once: true })
  }

  function onResizeMove(e: MouseEvent) {
    if (!resizing.value || rafId) return
    rafId = requestAnimationFrame(() => {
      rafId = null
      const r = resizing.value!
      const delta = e.clientX - r.startX
      columnWidths[r.colKey] = Math.max(40, r.startW + delta)
      columnWidths[r.nextKey] = Math.max(40, r.nextStartW - delta)
    })
  }

  function onResizeEnd() {
    resizing.value = null
    if (rafId) { cancelAnimationFrame(rafId); rafId = null }
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    document.querySelector('.table-wrap.is-resizing')?.classList.remove('is-resizing')
    window.removeEventListener('mousemove', onResizeMove)
  }

  return {
    columnWidths,
    onResizeStart,
  }
}
