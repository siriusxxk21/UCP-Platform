import { afterEach, describe, expect, it, vi } from 'vitest'
import { moveDetailFocus } from '@/nocode/detail-grid'
afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
function grid() {
  const root = document.createElement('div')
  root.innerHTML =
    '<div data-row-key="a"><input id="a1"><input disabled><input id="a2"></div><div data-row-key="b"><input id="b1"><input id="b2"></div>'
  document.body.append(root)
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as any)
  root.addEventListener('keydown', event => moveDetailFocus(event, root))
  return root
}
describe('明细键盘移动', () => {
  it('Enter 跳过禁用字段，Alt+向下移动到下一行同列', () => {
    const root = grid(),
      first = root.querySelector<HTMLInputElement>('#a1')!
    first.focus()
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    expect(document.activeElement?.id).toBe('a2')
    document.activeElement!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, bubbles: true })
    )
    expect(document.activeElement?.id).toBe('b2')
  })
  it('输入法确认和原生方向键不抢焦点', () => {
    const first = grid().querySelector<HTMLInputElement>('#a1')!
    first.focus()
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true }))
    expect(document.activeElement).toBe(first)
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    expect(document.activeElement).toBe(first)
  })
})
