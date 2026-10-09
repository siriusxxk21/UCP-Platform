// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
import BusinessPage from '@/views/drive/business.vue'
import type { BusinessFileDirectory, BusinessFileEntry, BusinessFileSpace } from '@/types/nocode/business-file'

const api = vi.hoisted(() => ({
  mine: vi.fn(),
  spaces: vi.fn(),
  directories: vi.fn(),
  files: vi.fn(),
  markedFiles: vi.fn(),
  locate: vi.fn(),
  favorite: vi.fn()
}))
const state = vi.hoisted(() => ({ maintenance: false, query: {} as Record<string, string> }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ runtime: { mine: api.mine }, bizFiles: api, hasPermission: () => state.maintenance })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: state.query, path: '/drive/business', fullPath: '/drive/business' }),
  useRouter: () => ({ push: vi.fn() })
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const space = (name: string): BusinessFileSpace => ({
  objectId: '1',
  objectName: `公司${name}`,
  spaceName: `空间${name}`,
  fileCount: 1,
  totalSize: 100,
  recordCount: 1,
  currentRuleVersion: 4
})
const file = (name: string): BusinessFileEntry => ({
  fileId: '1',
  entryId: '10',
  name: `${name}.pdf`,
  size: 100,
  recordId: '1',
  recordLabel: `业务${name}`,
  fieldId: 'file'
})
const directory = (label: string): BusinessFileDirectory => ({
  kind: 'RECORD',
  recordId: '1',
  label,
  fileCount: 1,
  totalSize: 100,
  recordCount: 1
})
const page = <T>(...list: T[]) => ({ list, total: list.length })
let app: App | undefined
let host: HTMLDivElement
async function flush() {
  for (let i = 0; i < 16; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(BusinessPage) })
  app.use(Antd)
  app.mount(host)
  await flush()
}
function node(title: string) {
  const result = Array.from(host.querySelectorAll<HTMLElement>('.business-navigation__node')).find(
    item => item.title === title
  )
  expect(
    result,
    `树节点 ${title}，当前节点：${Array.from(host.querySelectorAll<HTMLElement>('.business-navigation__node'))
      .map(item => item.title)
      .join(',')}`
  ).toBeTruthy()
  return result!
}
async function selectB() {
  node('应用B').click()
  await flush()
  node('公司B · 空间B').click()
  await flush()
}
async function button(text: string) {
  const result = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(
    item => item.textContent?.trim() === text
  )
  expect(result, `按钮 ${text}`).toBeTruthy()
  result!.click()
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  const originalStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => originalStyle(element))
  state.maintenance = false
  state.query = {}
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  api.mine.mockResolvedValue([
    { id: 'a', name: '应用A' },
    { id: 'b', name: '应用B' }
  ])
  api.spaces.mockImplementation((id?: string) => Promise.resolve([space(id === 'b' ? 'B' : 'A')]))
  api.directories.mockImplementation((q: { applicationId?: string; ruleVersion?: number }) =>
    Promise.resolve(page(directory(`目录${q.applicationId}`)))
  )
  api.files.mockImplementation((q: { applicationId?: string }) =>
    Promise.resolve(page(file(q.applicationId || 'maintenance')))
  )
  api.markedFiles.mockResolvedValue(page())
  api.locate.mockResolvedValue([])
})
afterEach(async () => {
  app?.unmount()
  app = undefined
  host?.remove()
  await flush()
  await new Promise(resolve => setTimeout(resolve, 50))
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('业务文件导航真实组件异步边界', () => {
  it('切换同一对象的应用入口后丢弃旧目录、文件、版本与收藏响应', async () => {
    const oldFiles = deferred<ReturnType<typeof page<BusinessFileEntry>>>()
    const oldDirs = deferred<ReturnType<typeof page<BusinessFileDirectory>>>()
    const oldMarks = deferred<ReturnType<typeof page<BusinessFileEntry>>>()
    api.files.mockImplementation((q: { applicationId?: string }) =>
      q.applicationId === 'a' ? oldFiles.promise : Promise.resolve(page(file('B当前文件')))
    )
    api.directories.mockImplementation((q: { applicationId?: string }) =>
      q.applicationId === 'a' ? oldDirs.promise : Promise.resolve(page(directory('B当前目录')))
    )
    api.markedFiles.mockImplementation((q: { applicationId?: string }) =>
      q.applicationId === 'a' ? oldMarks.promise : Promise.resolve(page())
    )
    await mount()
    await selectB()
    expect(host.textContent).toContain('B当前文件.pdf')
    oldFiles.resolve(page(file('A过期文件')))
    oldDirs.resolve(page(directory('A过期目录')))
    oldMarks.resolve(page(file('旧收藏')))
    await flush()
    expect(host.textContent).toContain('B当前目录')
    expect(host.textContent).not.toContain('A过期文件')
    expect(host.textContent).not.toContain('A过期目录')
    expect(host.textContent).not.toContain('取消收藏')
  })

  it('深链定位未完成时切换对象不被旧定位恢复旧面包屑', async () => {
    state.query = { applicationId: 'a', objectId: '1', recordId: 'old', fieldId: 'file', entryId: '10' }
    const locate = deferred<BusinessFileDirectory[]>()
    api.locate.mockReturnValue(locate.promise)
    await mount()
    await selectB()
    locate.resolve([{ ...directory('过期深链记录'), recordId: 'old' }])
    await flush()
    expect(host.textContent).toContain('业务b')
    expect(host.textContent).not.toContain('过期深链记录')
    expect(api.files.mock.calls.at(-1)?.[0]).toMatchObject({ applicationId: 'b', objectId: '1' })
    expect(api.files.mock.calls.at(-1)?.[0].recordId).toBeUndefined()
  })

  it('刷新发现权限撤销时立即清旧列表且不会重选失去权限的对象', async () => {
    await mount()
    expect(host.textContent).toContain('a.pdf')
    api.spaces.mockResolvedValue([])
    await button('刷新')
    expect(host.textContent).not.toContain('a.pdf')
    expect(host.textContent).not.toContain('公司A · 空间A')
    expect(host.textContent).toContain('暂无可见业务文件')
  })

  it('空入口失败重试恢复且没有管理权限不请求维护入口', async () => {
    api.mine.mockResolvedValue([{ id: 'a', name: '应用A' }])
    api.spaces.mockRejectedValueOnce(new Error('入口加载失败'))
    await mount()
    expect(host.textContent).toContain('重试')
    expect(api.spaces.mock.calls.map(args => args[0])).toEqual(['a'])
    api.spaces.mockResolvedValue([space('A')])
    await button('重试')
    expect(host.textContent).toContain('a.pdf')
    expect(host.textContent).not.toContain('数据维护')
  })
  it('再次选择当前叶子返回根目录并清除旧层级与文件搜索', async () => {
    await mount()
    const folder = Array.from(host.querySelectorAll<HTMLAnchorElement>('a')).find(
      item => item.textContent?.trim() === '目录a'
    )!
    folder.click()
    await flush()
    const input = host.querySelector<HTMLInputElement>('input[placeholder="文件名、业务标题或业务编号"]')!
    input.value = 'needle'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    await button('查询')
    expect(api.files.mock.calls.at(-1)?.[0]).toMatchObject({ recordId: '1', search: 'needle' })
    node('公司A · 空间A').click()
    await flush()
    expect(api.files.mock.calls.at(-1)?.[0].recordId).toBeUndefined()
    expect(api.files.mock.calls.at(-1)?.[0].search).toBeUndefined()
    expect(input.value).toBe('')
  })

  it('对象搜索展示未展开应用下的对象但不切换当前内容', async () => {
    await mount()
    expect(host.querySelector('.business-navigation')?.textContent).not.toContain('公司B')
    const input = host.querySelector<HTMLInputElement>('input[placeholder="搜索应用或业务对象"]')!
    input.value = '公司B'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(host.querySelector('.business-navigation')?.textContent).toContain('公司B')
    expect(host.querySelector('.business-content__context')?.textContent).toContain('应用A / 公司A')
    expect(host.textContent).toContain('a.pdf')
  })

  it('导航预加载最多四路并发且包含未展开的所有入口', async () => {
    api.mine.mockResolvedValue(Array.from({ length: 7 }, (_, i) => ({ id: `app${i}`, name: `应用${i}` })))
    const requests = Array.from({ length: 7 }, () => deferred<BusinessFileSpace[]>())
    let active = 0,
      maximum = 0
    api.spaces.mockImplementation((id: string) => {
      active++
      maximum = Math.max(maximum, active)
      return requests[Number(id.slice(3))]!.promise.finally(() => {
        active--
      })
    })
    await mount()
    expect(api.spaces).toHaveBeenCalledTimes(4)
    requests.slice(0, 4).forEach(request => request.resolve([]))
    await flush()
    expect(api.spaces).toHaveBeenCalledTimes(7)
    requests.slice(4).forEach(request => request.resolve([]))
    await flush()
    expect(maximum).toBe(4)
    expect(active).toBe(0)
  })
})
