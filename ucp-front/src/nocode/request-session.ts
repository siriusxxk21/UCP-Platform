/** 一次查询或弹窗会话只接受最新响应；关闭和卸载可使仍在途的回调失效。 */
export function createRequestSession() {
  let generation = 0
  return {
    begin() {
      const current = ++generation
      return () => current === generation
    },
    invalidate() {
      generation++
    }
  }
}
