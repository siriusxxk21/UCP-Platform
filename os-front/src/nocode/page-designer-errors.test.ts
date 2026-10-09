// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { runInNewContext } from 'node:vm'
import editorHtml from '../../page-designer/index.html?raw'
import canvasHtml from '../../page-designer/canvas.html?raw'

function bootstrap(entry: string) {
  const html = entry === 'index' ? editorHtml : canvasHtml
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1]
  if (!script) throw new Error('缺少模块加载前的错误处理')
  const postMessage = vi.fn()
  const listeners = new Map<string, EventListener>()
  const listen = (type: string, listener: EventListener) => listeners.set(type, listener)
  runInNewContext(script, {
    window,
    document,
    location,
    parent: { postMessage },
    addEventListener: listen,
    HTMLScriptElement,
    HTMLLinkElement
  })
  return {
    post: postMessage,
    dispatch(event: Event, target: EventTarget = window) {
      Object.defineProperty(event, 'target', { value: target })
      listeners.get(event.type)?.(event)
    }
  }
}

describe.each(['index', 'canvas'])('%s 入口的加载错误边界', entry => {
  it('浏览器尺寸延迟通知不阻断编辑，但具有 Error 对象的真实故障仍上报', () => {
    const { post, dispatch } = bootstrap(entry)
    for (const message of [
      'ResizeObserver loop completed with undelivered notifications.',
      'ResizeObserver loop limit exceeded'
    ])
      dispatch(new ErrorEvent('error', { message }))
    expect(post).not.toHaveBeenCalled()
    dispatch(
      new ErrorEvent('error', { message: 'ResizeObserver loop limit exceeded', error: new Error('实际脚本异常') })
    )
    expect(post).toHaveBeenCalledWith(expect.objectContaining({ type: 'ERROR' }), location.origin)
  })
  it('普通运行错误、脚本/样式失败与未处理 Promise 都上报，展示图片失败不阻断', () => {
    const { post, dispatch } = bootstrap(entry)
    dispatch(new ErrorEvent('error', { message: 'TypeError: cannot read properties of null' }))
    for (const tag of ['script', 'link', 'img']) {
      const element = document.createElement(tag)
      dispatch(new Event('error'), element)
    }
    dispatch(new Event('unhandledrejection'))
    expect(post).toHaveBeenCalledTimes(4)
  })
})
