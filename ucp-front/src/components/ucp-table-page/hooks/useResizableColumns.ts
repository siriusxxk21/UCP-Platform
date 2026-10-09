import { ref, reactive } from 'vue'

/**
 * 列宽拖拽 Hook
 * 使用方式：在 OsTablePage 的 #headerCell slot 中放置 resize handle，
 * 通过 handleResizeStart 监听拖拽开始，colWidths 追踪各列宽度。
 */
export function useResizableColumns() {
  /** 各列当前宽度（key → width px） */
  const colWidths = reactive<Record<string, number>>({})

  let _startX = 0
  let _startWidth = 0
  let _currentKey = ''
  let _isResizing = false

  function handleResizeMove(e: MouseEvent) {
    if (!_isResizing) return
    const delta = e.clientX - _startX
    const newWidth = Math.max(50, _startWidth + delta)
    colWidths[_currentKey] = newWidth
  }

  /** 吃掉一次 click：不让它到达列头。 */
  function swallowClick(e: MouseEvent) {
    e.stopPropagation()
    e.preventDefault()
  }

  function handleResizeEnd() {
    _isResizing = false
    document.removeEventListener('mousemove', handleResizeMove)
    document.removeEventListener('mouseup', handleResizeEnd)
    // 松开后浏览器会在「按下处与松开处的共同祖先」上补发一次 click：指针落在同一个列头里时它就是列头的点击，
    // 可排序列会被当成排序。只拦紧跟着松开的这一次；没有补发时下一轮事件循环就撤掉，不影响之后的点击。
    window.addEventListener('click', swallowClick, true)
    setTimeout(() => window.removeEventListener('click', swallowClick, true))
  }

  /**
   * 在列头 resize handle 的 mousedown 事件中调用
   * @param e MouseEvent
   * @param colKey 列唯一标识（column.key 或 column.dataIndex）
   * @param currentWidth 当前列宽（px）
   */
  function handleResizeStart(e: MouseEvent, colKey: string, currentWidth: number) {
    e.stopPropagation()
    e.preventDefault()
    _startX = e.clientX
    _startWidth = currentWidth
    _currentKey = colKey
    _isResizing = true
    document.addEventListener('mousemove', handleResizeMove)
    document.addEventListener('mouseup', handleResizeEnd)
  }

  /**
   * 获取列当前宽度
   * @param colKey 列 key
   * @param defaultWidth 默认宽度
   */
  function getColWidth(colKey: string, defaultWidth?: number): number | undefined {
    return colWidths[colKey] ?? defaultWidth
  }

  return {
    colWidths,
    handleResizeStart,
    getColWidth
  }
}
