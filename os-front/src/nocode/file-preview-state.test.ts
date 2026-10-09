// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import InfraUpload from '@/components/InfraUpload.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), request: vi.fn(), message: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: { get: mocks.get, request: mocks.request } }))
vi.mock('ant-design-vue', () => ({ message: { error: mocks.message }, Upload: { LIST_IGNORE: 'ignore' } }))
let app: App | undefined, host: HTMLElement
let addPending: (() => void) | undefined
const events: string[][] = []
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function deferred() {
  let resolve!: (value: unknown) => void
  const promise = new Promise(r => {
    resolve = r
  })
  return { promise, resolve }
}
const file = (id: string, name = `附件${id}`) => ({ id, name, configId: '1', path: id + '.pdf' })
async function render(ids: string[]) {
  const props = reactive({ modelValue: ids })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    render: () => h(InfraUpload, { ...props, 'onUpdate:modelValue': (ids: string[]) => events.push(ids) })
  })
  app.component(
    'AUpload',
    defineComponent({
      props: ['fileList'],
      emits: ['update:fileList'],
      setup: (p, { emit }) => {
        addPending = () =>
          emit('update:fileList', [...p.fileList, { uid: 'upload-new', name: '正在上传', status: 'uploading' }])
        return () =>
          h(
            'div',
            { 'data-files': '' },
            p.fileList.map((item: { name: string }) => h('span', item.name))
          )
      }
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (p, { slots }) =>
        () =>
          h('section', [p.message, slots.action?.()])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.mount(host)
  await flush()
  return props
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  events.length = 0
  addPending = undefined
  vi.resetAllMocks()
})

describe('文件预览的异步状态与失败恢复', () => {
  it('清空后迟到的旧文件响应不能重新显示，也不修改模型', async () => {
    const old = deferred()
    mocks.get.mockReturnValue(old.promise)
    const props = await render(['1'])
    props.modelValue = []
    await flush()
    old.resolve([file('1')])
    await flush()
    expect(host.querySelector('[data-files]')!.textContent).toBe('')
    expect(props.modelValue).toEqual([])
    expect(events).toEqual([])
  })
  it('后一次选择优先，并保留读取期间开始上传的条目', async () => {
    const old = deferred(),
      current = deferred()
    mocks.get.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const props = await render(['1'])
    props.modelValue = ['2']
    await flush()
    addPending?.()
    await flush()
    current.resolve([file('2')])
    await flush()
    old.resolve([file('1')])
    await flush()
    expect(host.querySelector('[data-files]')!.textContent).toBe('附件2正在上传')
    expect(events).toEqual([])
  })
  it('失败及部分失效保留 ID，用户可重试恢复元信息', async () => {
    mocks.get
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([file('1')])
    const props = await render(['1'])
    expect(host.textContent).toContain('已保留选择，请重新读取')
    expect(host.textContent).toContain('文件信息未加载')
    host.querySelector('button')!.click()
    await flush()
    expect(host.textContent).toContain('有 1 个文件不存在或当前无权读取')
    host.querySelector('button')!.click()
    await flush()
    expect(host.textContent).toBe('附件1')
    expect(props.modelValue).toEqual(['1'])
    expect(events).toEqual([])
  })
  it('卸载后响应失效，不再触发宿主事件', async () => {
    const response = deferred()
    mocks.get.mockReturnValue(response.promise)
    await render(['1'])
    app!.unmount()
    app = undefined
    response.resolve([file('1')])
    await flush()
    expect(events).toEqual([])
    expect(host.textContent).toBe('')
  })
})
