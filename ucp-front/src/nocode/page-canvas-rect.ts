interface CanvasRectApi {
  updateRect: (id?: unknown) => void
  getCurrent: () => { schema?: { id?: string } | null }
  getDocument: () => Document | undefined
  clearSelect: () => void
}

/** TinyEngine 2.11 拖动先删除再插入；选框必须等待内层画布 DOM 完成更新。 */
export function deferCanvasRect(api: CanvasRectApi, hasNode: (id: string) => boolean) {
  const original = api.updateRect
  let frame: number | undefined
  let requestedId: string | undefined
  let observer: MutationObserver | undefined
  let observedDocument: Document | undefined
  let disposed = false

  function stopObserving() {
    observer?.disconnect()
    observer = undefined
    observedDocument = undefined
  }
  function schedule() {
    if (!disposed && frame === undefined) frame = requestAnimationFrame(draw)
  }
  function draw() {
    frame = undefined
    const id = requestedId || api.getCurrent().schema?.id
    // 空选择/多选沿用引擎分支，启动时内层画布可能尚未建立。
    if (!id) {
      stopObserving()
      original.call(api, requestedId)
      return
    }
    const doc = api.getDocument()
    if (!doc) return
    if (!doc.querySelector(`[data-uid="${CSS.escape(id)}"]`)) {
      if (!hasNode(id)) {
        stopObserving()
        api.clearSelect()
        return
      }
      // 渲染器在另一个 iframe 中异步同步结构，宿主 nextTick 无法保证节点已落到 DOM。
      if (observedDocument !== doc) {
        stopObserving()
        observer = new MutationObserver(schedule)
        observer.observe(doc, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-uid'] })
        observedDocument = doc
      }
      return
    }
    stopObserving()
    original.call(api, requestedId)
  }
  const updateRect = (id?: unknown) => {
    requestedId = typeof id === 'string' && id ? id : undefined
    schedule()
  }
  api.updateRect = updateRect

  return () => {
    disposed = true
    if (frame !== undefined) cancelAnimationFrame(frame)
    stopObserving()
    if (api.updateRect === updateRect) api.updateRect = original
  }
}
