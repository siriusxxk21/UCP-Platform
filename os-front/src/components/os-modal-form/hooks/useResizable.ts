import { ref, onBeforeUnmount } from 'vue'
import type { Ref, ComputedRef } from 'vue'

export interface UseResizableOptions {
  minWidth?: number
  maxWidth?: number
  minHeight?: number
  maxHeight?: number
  onResize?: (width: number, height: number) => void
}

/** 兼容 Ref / ComputedRef / 任意带 .value 的响应式对象 */
type ResizableRef = Ref<HTMLElement | undefined> | ComputedRef<HTMLElement | undefined>

export function useResizable(
  containerRef: ResizableRef,
  options: UseResizableOptions = {},
) {
  const {
    minWidth = 400,
    maxWidth = window.innerWidth,
    minHeight = 300,
    maxHeight = window.innerHeight,
    onResize,
  } = options

  const isResizing = ref(false)
  const currentDirection = ref<'bottom-right' | 'left'>('bottom-right')
  let startX = 0
  let startY = 0
  let startWidth = 0
  let startHeight = 0
  let rafId: number | null = null

  function startResize(event: MouseEvent, direction: 'bottom-right' | 'left') {
    event.preventDefault()
    isResizing.value = true
    currentDirection.value = direction
    startX = event.clientX
    startY = event.clientY

    const el = containerRef.value
    if (el) {
      startWidth = el.offsetWidth
      startHeight = el.offsetHeight
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    document.body.style.userSelect = 'none'
  }

  function onMouseMove(event: MouseEvent) {
    if (!isResizing.value) return

    // 使用 requestAnimationFrame 优化性能
    if (rafId) cancelAnimationFrame(rafId)
    
    rafId = requestAnimationFrame(() => {
      const dx = event.clientX - startX
      const dy = event.clientY - startY

      let newWidth = startWidth + dx
      let newHeight = startHeight + dy

      // 根据方向区分缩放行为
      if (currentDirection.value === 'left') {
        // 抽屉模式：手柄在左侧，向左拖拽（dx<0）增大宽度
        newWidth = Math.max(minWidth, Math.min(maxWidth, startWidth - dx))
        if (onResize) {
          onResize(newWidth, startHeight) // 高度不变
        }
      } else {
        // 弹窗模式：同时改变宽高
        newWidth = Math.max(minWidth, Math.min(maxWidth, newWidth))
        newHeight = Math.max(minHeight, Math.min(maxHeight, newHeight))
        if (onResize) {
          onResize(newWidth, newHeight)
        }
      }
      
      rafId = null
    })
  }

  function onMouseUp() {
    isResizing.value = false
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
    document.body.style.userSelect = ''
  }

  onBeforeUnmount(() => {
    if (rafId) {
      cancelAnimationFrame(rafId)
    }
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  })

  return {
    isResizing,
    startResize,
  }
}
