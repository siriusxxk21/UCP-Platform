// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, resolveComponent, type App } from 'vue'
import Antd, { Input } from 'ant-design-vue'
import TaskEditableCell from '@/views/nocode/task-center/TaskEditableCell.vue'

let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) await nextTick()
  await vi.advanceTimersByTimeAsync(100)
  for (let i = 0; i < 8; i++) await nextTick()
}
function required<T>(value: T | null): T {
  if (!value) throw new Error('缺少编辑测试目标')
  return value
}
const trigger = () => required(host.querySelector<HTMLButtonElement>('[aria-label="编辑测试字段"]'))
async function mount(select = false, externalEditor = false) {
  const state = reactive({ open: false, readonly: false, summary: '普通', secondary: '', value: 'NORMAL' })
  app = createApp(() =>
    h('table', [
      h('tbody', [
        h('tr', [
          h('td', [
            h(
              TaskEditableCell,
              {
                label: '测试字段',
                summary: state.summary,
                secondary: state.secondary,
                modelValue: state.open,
                readonly: state.readonly,
                externalEditor,
                'onUpdate:modelValue': (value: boolean) => (state.open = value)
              },
              {
                default: () =>
                  select
                    ? h(resolveComponent('ASelect'), {
                        value: state.value,
                        'aria-label': '测试选项',
                        options: [
                          { value: 'NORMAL', label: '普通' },
                          { value: 'URGENT', label: '紧急' }
                        ],
                        'onUpdate:value': (value: string) => (state.value = value)
                      })
                    : h(Input, {
                        value: state.summary,
                        'aria-label': '测试输入',
                        'onUpdate:value': (value: string) => (state.summary = value)
                      })
              }
            )
          ])
        ])
      ])
    ])
  )
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  const trigger = required(host.querySelector<HTMLButtonElement>('[aria-label="编辑测试字段"]'))
  trigger.click()
  await flush()
  return { state, trigger }
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] })
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
})
afterEach(async () => {
  app?.unmount()
  await nextTick()
  await vi.runAllTimersAsync()
  host?.remove()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('任务单元格原位编辑', () => {
  it('点击后在原单元格填写，不再弹出浮层或要求额外点击完成', async () => {
    const { state } = await mount()
    expect(state.open).toBe(true)
    const input = required(host.querySelector<HTMLInputElement>('[aria-label="测试输入"]'))
    expect(input.closest('td')).not.toBeNull()
    expect(document.querySelector('.ant-popover')).toBeNull()
    expect(host.textContent).not.toContain('完成')
    input.value = '编辑中较长的任务说明'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(state.open).toBe(false)
    expect(trigger().textContent).toContain('编辑中较长的任务说明')
    expect(document.activeElement).toBe(trigger())
  })
  it('Escape 收起但保留修改，重新打开可继续输入', async () => {
    const { state } = await mount()
    const input = required(host.querySelector<HTMLInputElement>('input'))
    expect(document.activeElement).toBe(input)
    input.value = '已修改'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(state.summary).toBe('已修改')
    expect(trigger().getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger())
    trigger().click()
    await flush()
    expect(required(host.querySelector<HTMLInputElement>('input')).value).toBe('已修改')
  })
  it('原位下拉只弹出选项列表，选择直接更新值，不出现额外编辑浮层', async () => {
    const { state } = await mount(true)
    required(host.querySelector<HTMLElement>('.ant-select-selector')).dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true })
    )
    await flush()
    const option = required(
      Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
        item => item.textContent === '紧急'
      ) || null
    )
    option.click()
    await flush()
    expect(state.value).toBe('URGENT')
    expect(state.open).toBe(true)
    expect(host.querySelector('.ant-select')?.closest('td')).not.toBeNull()
    expect(document.querySelector('.ant-popover')).toBeNull()
  })
  it('失焦保留已填写值，只读切换后关闭并禁止再次编辑', async () => {
    const { state } = await mount()
    state.summary = '已填写内容'
    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    await flush()
    outside.remove()
    expect(state.open).toBe(false)
    expect(state.summary).toBe('已填写内容')
    state.open = true
    await flush()
    state.readonly = true
    await flush()
    expect(state.open).toBe(false)
    expect(host.querySelector('button')).toBeNull()
    expect(host.querySelector('input')).toBeNull()
  })
  it('外部配置入口只通知打开抽屉，不重复弹出编辑浮层', async () => {
    const { state, trigger } = await mount(false, true)
    expect(state.open).toBe(true)
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(document.querySelector('[role="dialog"][aria-hidden="false"]')).toBeNull()
    expect(host.querySelector('input')).toBeNull()
  })
})
