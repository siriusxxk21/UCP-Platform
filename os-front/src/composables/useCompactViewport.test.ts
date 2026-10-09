// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type Ref } from 'vue'
import { useCompactViewport } from './useCompactViewport'

afterEach(() => vi.unstubAllGlobals())

describe('手机断点', () => {
  it('初始化及旋转屏幕使用同一个媒体查询，卸载时解除监听', async () => {
    let matches = true
    const listeners = new Set<() => void>()
    const media = {
      get matches() {
        return matches
      },
      addEventListener: vi.fn((_event, listener) => listeners.add(listener)),
      removeEventListener: vi.fn((_event, listener) => listeners.delete(listener))
    }
    const matchMedia = vi.fn(() => media)
    vi.stubGlobal('matchMedia', matchMedia)
    let compact: Ref<boolean>
    const app = createApp({
      setup() {
        compact = useCompactViewport()
        return () => h('div', String(compact.value))
      }
    })
    const host = document.createElement('div')
    app.mount(host)
    expect(matchMedia).toHaveBeenCalledWith('(max-width: 767px)')
    expect(host.textContent).toBe('true')
    matches = false
    listeners.forEach(listener => listener())
    await nextTick()
    expect(host.textContent).toBe('false')
    app.unmount()
    expect(listeners.size).toBe(0)
  })
  it('没有媒体查询的运行环境仍能挂载', () => {
    vi.stubGlobal('matchMedia', undefined)
    const app = createApp({
      setup() {
        const compact = useCompactViewport()
        return () => h('div', String(compact.value))
      }
    })
    const host = document.createElement('div')
    app.mount(host)
    expect(host.textContent).toBe('false')
    app.unmount()
  })
})
