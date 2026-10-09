// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import Antd from 'ant-design-vue'
import RecordReadView from '@/views/nocode/application/components/RecordReadView.vue'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
const runtime = vi.hoisted(() => ({ selection: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime, applications: {} }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'rich-text-test' } }) }))
vi.mock('@/utils/request', () => ({ default: {} }))
let app: App, host: HTMLDivElement
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
})

describe('记录富文本只读展示', () => {
  it('真实Tiptap保留段落/强调/列表，禁用编辑并随记录值更新', async () => {
    const props = reactive({
      applicationId: 'app',
      objectId: 'object',
      relations: [],
      options: {},
      fields: [{ id: 'body', code: 'body', name: '正文', type: 'RICH_TEXT' }],
      nodes: [uiNode(NodeKind.FIELD, { fieldId: 'body' })],
      values: {
        body: '<p><strong>正文</strong></p><ul><li><p>清单</p></li></ul><script>window.unwanted = true</script>'
      }
    })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(RecordReadView, props as any)
    app.use(Antd)
    app.mount(host)
    await vi.waitFor(() => expect(host.querySelector('.tiptap-content strong')?.textContent).toBe('正文'), {
      timeout: 5000
    })
    expect(host.querySelector('.tiptap-content li')?.textContent).toBe('清单')
    expect(host.querySelector('.tiptap-content')?.getAttribute('contenteditable')).toBe('false')
    expect(host.querySelector('script')).toBeNull()
    expect(host.textContent).not.toContain('<strong>')
    props.values.body = '<p>另一条记录</p>'
    await nextTick()
    await vi.waitFor(() => expect(host.querySelector('.tiptap-content')?.textContent).toBe('另一条记录'))
    expect(runtime.selection).not.toHaveBeenCalled()
  })
})
