// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive } from 'vue'

const api = vi.hoisted(() => ({
  getDriveEntry: vi.fn(),
  getDriveEntryContent: vi.fn(),
  getDriveBreadcrumb: vi.fn(),
  updateFavorite: vi.fn()
}))
vi.mock('@/api/drive/entry', () => ({
  ...api,
  DRIVE_ENTRY_UPLOAD_PATH: '/drive/entry/upload',
  buildDriveContentUrl: (id: string) => '/content/' + id
}))
vi.mock('@/api/drive/mark', () => ({
  updateFavorite: api.updateFavorite,
  recordDriveAccess: vi.fn().mockResolvedValue(true)
}))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('@/views/drive/composables/useDriveUserNames', () => ({
  useDriveUserNames: () => ({ loadUserNames: vi.fn(), userName: () => '测试用户' })
}))
import EntryDetailDrawer from '@/views/drive/components/EntryDetailDrawer.vue'

const Container = defineComponent({ template: '<div><slot name="extra"/><slot/><slot name="action"/></div>' })
const Result = defineComponent({
  props: ['title', 'subTitle'],
  template: '<div>{{title}}{{subTitle}}<slot name="extra"/></div>'
})
function render() {
  const props = reactive({ open: true, entryId: 'a' })
  const app = createApp({ render: () => h(EntryDetailDrawer, props) })
  const components = {
    'a-drawer': Container,
    'a-space': Container,
    'a-button': defineComponent({ template: '<button><slot/></button>' }),
    'a-skeleton': defineComponent({ template: '<div>加载中</div>' }),
    'a-result': Result,
    'a-alert': Container,
    'a-descriptions': Container,
    'a-descriptions-item': Container,
    'a-empty': defineComponent({ props: ['description'], template: '<p>{{description}}</p>' })
  }
  for (const [name, component] of Object.entries(components)) app.component(name, component)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  return {
    setProps: async (next: Partial<typeof props>) => {
      Object.assign(props, next)
      await nextTick()
    },
    text: () => host.textContent || '',
    find: (selector: string) => ({
      text: () => host.querySelector(selector)?.textContent,
      trigger: async (event: string) => {
        host.querySelector(selector)?.dispatchEvent(new Event(event, { bubbles: true }))
        await nextTick()
      }
    }),
    unmount: () => {
      app.unmount()
      host.remove()
    }
  }
}
async function flushPromises() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const file = (id: string, size = 20) => ({
  id,
  parentId: '0',
  name: id + '.txt',
  size,
  type: 'FILE',
  mimeType: 'text/plain'
})

describe('网盘详情抽屉可用性', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getDriveEntryContent.mockResolvedValue({ text: async () => '实际文件内容' })
  })

  it('快速切换文件时，先打开文件的慢响应不能覆盖当前文件', async () => {
    let resolveOld!: (value: unknown) => void
    api.getDriveEntry.mockImplementation((id: string) =>
      id === 'a'
        ? new Promise(resolve => {
            resolveOld = resolve
          })
        : Promise.resolve(file(id))
    )
    const view = render()
    await view.setProps({ entryId: 'b' })
    await flushPromises()
    resolveOld(file('a'))
    await flushPromises()
    expect(api.getDriveEntryContent).toHaveBeenCalledTimes(1)
    expect(api.getDriveEntryContent).toHaveBeenCalledWith('b', true)
    expect(view.find('pre').text()).toBe('实际文件内容')
    view.unmount()
  })

  it('关闭抽屉后不继续发起旧文件的预览请求', async () => {
    let resolveOld!: (value: unknown) => void
    api.getDriveEntry.mockImplementation(
      () =>
        new Promise(resolve => {
          resolveOld = resolve
        })
    )
    const view = render()
    await view.setProps({ open: false })
    resolveOld(file('a'))
    await flushPromises()
    expect(api.getDriveEntryContent).not.toHaveBeenCalled()
    view.unmount()
  })

  it('超大文本保留下载入口，不整份拉取渲染', async () => {
    api.getDriveEntry.mockResolvedValue(file('a', 3 * 1024 * 1024))
    const view = render()
    await flushPromises()
    expect(view.text()).toContain('文本超过 2 MiB')
    expect(view.text()).toContain('下载')
    expect(api.getDriveEntryContent).not.toHaveBeenCalled()
    view.unmount()
  })

  it('加载失败显示明确错误并支持重试', async () => {
    api.getDriveEntry.mockRejectedValueOnce(new Error('无权访问')).mockResolvedValue(file('a'))
    const view = render()
    await flushPromises()
    expect(view.text()).toContain('无法加载文件')
    await view.find('button').trigger('click')
    await flushPromises()
    expect(view.find('pre').text()).toBe('实际文件内容')
    view.unmount()
  })

  it('文件路径根据自身读取权获取，不额外依赖上级目录授权', async () => {
    api.getDriveEntry.mockResolvedValue({ ...file('a'), parentId: '10' })
    api.getDriveBreadcrumb.mockResolvedValue([
      { id: '0', name: '全部文件' },
      { id: '10', name: '授权目录' },
      { id: 'a', name: 'a.txt' }
    ])
    const view = render()
    await flushPromises()
    expect(api.getDriveBreadcrumb).toHaveBeenCalledWith('a')
    expect(view.text()).toContain('授权目录')
    expect(view.find('pre').text()).toBe('实际文件内容')
    view.unmount()
  })
})
