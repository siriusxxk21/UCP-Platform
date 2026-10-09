// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deferCanvasRect } from './page-canvas-rect'

let callbacks: Map<number, FrameRequestCallback>
let dispose: (() => void) | undefined
let serial = 0
function renderFrame() {
  const pending = [...callbacks.values()]
  callbacks.clear()
  pending.forEach(callback => callback(0))
}
function fixture() {
  const doc = document.implementation.createHTMLDocument('画布')
  const state = { selected: 'moving', nodes: new Set(['moving', 'other']) }
  const update = vi.fn()
  const api = {
    getDocument: () => doc,
    getCurrent: () => ({ schema: { id: state.selected } }),
    clearSelect: vi.fn(),
    updateRect: update as (id?: unknown) => void
  }
  dispose = deferCanvasRect(api, id => state.nodes.has(id))
  const insert = (id: string) => {
    const el = doc.createElement('div')
    el.setAttribute('data-uid', id)
    doc.body.append(el)
    return el
  }
  return { doc, api, state, update, insert }
}
beforeEach(() => {
  callbacks = new Map()
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    callbacks.set(++serial, callback)
    return serial
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => callbacks.delete(id))
  vi.stubGlobal('CSS', { escape: (id: string) => id })
})
afterEach(() => {
  dispose?.()
  vi.unstubAllGlobals()
})

describe('页面拖动后的选框同步', () => {
  it('初始空选框不访问尚未建立的内层文档', () => {
    const { api, state, update } = fixture()
    state.selected = ''
    vi.spyOn(api, 'getDocument').mockImplementation(() => {
      throw new Error('内层画布尚未建立')
    })
    api.updateRect()
    expect(renderFrame).not.toThrow()
    expect(api.getDocument).not.toHaveBeenCalled()
    expect(update).toHaveBeenCalledOnce()
  })
  it('节点换位期间 DOM 暂缺，等待真实渲染后刷新且不重置选择', async () => {
    const { api, insert, update } = fixture()
    const old = insert('moving')
    old.remove()
    api.updateRect()
    renderFrame()
    expect(update).not.toHaveBeenCalled()
    expect(callbacks.size).toBe(0)
    insert('moving')
    await Promise.resolve()
    renderFrame()
    expect(update).toHaveBeenCalledOnce()
    expect(api.clearSelect).not.toHaveBeenCalled()
  })
  it('合并连续更新，尺寸事件使用当前选择，等待期间切换节点不会刷新旧选框', async () => {
    const { api, state, insert, update } = fixture()
    api.updateRect()
    api.updateRect(new Event('canvasResize'))
    expect(callbacks.size).toBe(1)
    renderFrame()
    state.selected = 'other'
    insert('other')
    await Promise.resolve()
    renderFrame()
    expect(update).toHaveBeenCalledExactlyOnceWith(undefined)
    expect(api.clearSelect).not.toHaveBeenCalled()
  })
  it('真正删除节点时清除失效选框，不无限等待 DOM', async () => {
    const { api, state, insert, update } = fixture()
    state.nodes.delete('moving')
    api.updateRect()
    renderFrame()
    expect(api.clearSelect).toHaveBeenCalledOnce()
    insert('other')
    await Promise.resolve()
    expect(callbacks.size).toBe(0)
    expect(update).not.toHaveBeenCalled()
  })
  it('新请求覆盖旧目标，显式节点与页面根节点沿用引擎语义', () => {
    const { api, state, insert, update } = fixture()
    insert('other')
    api.updateRect('moving')
    api.updateRect('other')
    renderFrame()
    expect(update).toHaveBeenCalledExactlyOnceWith('other')
    state.selected = ''
    api.updateRect(new Event('canvasResize'))
    renderFrame()
    expect(update).toHaveBeenLastCalledWith(undefined)
  })
  it('销毁后取消帧和 DOM 监听，并恢复原 API', async () => {
    const { api, insert, update } = fixture()
    api.updateRect()
    renderFrame()
    insert('moving')
    await Promise.resolve()
    expect(callbacks.size).toBe(1)
    dispose?.()
    renderFrame()
    insert('other')
    await Promise.resolve()
    expect(callbacks.size).toBe(0)
    expect(update).not.toHaveBeenCalled()
    expect(api.updateRect).toBe(update)
  })
  it('真实引擎异常继续上报，不用吞错掩盖其他故障', () => {
    const { api, insert, update } = fixture()
    insert('moving')
    update.mockImplementation(() => {
      throw new Error('真实画布故障')
    })
    api.updateRect()
    expect(renderFrame).toThrow('真实画布故障')
  })
})
