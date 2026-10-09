// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import type { Editor } from '@tiptap/vue-3'
import { Slice } from '@tiptap/pm/model'
import TiptapEditor from '@/components/TiptapEditor.vue'

vi.mock('@/stores/user', () => ({ useUserStore: () => ({ token: 'test-editor-token' }) }))
vi.mock('ant-design-vue', () => ({ message: { error: vi.fn() } }))
let app: App | undefined, host: HTMLDivElement
const upload = vi.fn()
const originalRangeRect = Object.getOwnPropertyDescriptor(Range.prototype, 'getBoundingClientRect')
const originalRangeRects = Object.getOwnPropertyDescriptor(Range.prototype, 'getClientRects')
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (title: string) => host.querySelector<HTMLButtonElement>(`button[title="${title}"]`)!
const toolbar = [
  '粗体',
  '斜体',
  '下划线',
  '删除线',
  '高亮',
  '字号',
  '字体颜色',
  '标题 1',
  '标题 2',
  '标题 3',
  '无序列表',
  '有序列表',
  '任务列表',
  '左对齐',
  '居中对齐',
  '右对齐',
  '插入链接',
  '插入图片',
  '插入表格',
  '插入水平线',
  '代码块',
  '引用',
  '撤销',
  '重做'
]
async function mount(content = '<p>测试内容</p>', disabled = false) {
  const state = reactive({ content, disabled }),
    update = vi.fn((value: string) => {
      state.content = value
    })
  app = createApp(() =>
    h(TiptapEditor, {
      modelValue: state.content,
      disabled: state.disabled,
      placeholder: '测试富文本',
      'onUpdate:modelValue': update
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  const element = host.querySelector<HTMLElement>('.tiptap-content')! as HTMLElement & { editor: Editor }
  return { state, update, editor: element.editor, element }
}
beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  vi.stubGlobal('fetch', upload)
  upload
    .mockReset()
    .mockResolvedValue({ json: async () => ({ code: 0, data: { configId: 1, path: 'fixture/image.png' } }) })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(callback, 0, 0))
  vi.stubGlobal('cancelAnimationFrame', (id: number) => clearTimeout(id))
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect() })
  Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  for (const [name, descriptor] of [
    ['getBoundingClientRect', originalRangeRect],
    ['getClientRects', originalRangeRects]
  ] as const) {
    if (descriptor) Object.defineProperty(Range.prototype, name, descriptor)
    else Reflect.deleteProperty(Range.prototype, name)
  }
})

describe('共享富文本编辑器能力合同', () => {
  it('回显保留所有 marks/nodes、图片、表头、任务项及自定义呈现', async () => {
    const { editor } = await mount(`<h1>一级</h1><h2>二级</h2><h3>三级</h3>
      <p><strong>粗体</strong><em>斜体</em><u>下划线</u><s>删除</s><code>行内代码</code>
      <a href="https://example.com">链接</a><span style="font-size:20px;color:rgb(22,119,255)">彩色字号</span>
      <mark data-color="#ffee00" style="background-color:#ffee00">高亮</mark><br>换行</p>
      <blockquote><p>引用</p></blockquote><pre><code>代码块</code></pre><hr>
      <ul><li><p>无序</p></li></ul><ol><li><p>有序</p></li></ol>
      <ul data-type="taskList"><li data-type="taskItem" data-checked="true"><p>任务</p></li></ul>
      <img src="data:image/png;base64,aGVsbG8=" alt="图片">
      <table><tbody><tr><th><p>表头</p></th></tr><tr><td><p>内容</p></td></tr></tbody></table>`)
    expect(Object.keys(editor.schema.marks)).toEqual(
      expect.arrayContaining(['bold', 'italic', 'underline', 'strike', 'code', 'link', 'textStyle', 'highlight'])
    )
    expect(Object.keys(editor.schema.nodes)).toEqual(
      expect.arrayContaining([
        'doc',
        'paragraph',
        'text',
        'heading',
        'blockquote',
        'bulletList',
        'orderedList',
        'listItem',
        'codeBlock',
        'horizontalRule',
        'hardBreak',
        'taskList',
        'taskItem',
        'image',
        'table',
        'tableRow',
        'tableCell',
        'tableHeader'
      ])
    )
    const html = editor.getHTML()
    for (const part of [
      '<h1>',
      '<h2>',
      '<h3>',
      '<u>',
      '<hr>',
      '<th',
      'data-type="taskList"',
      'data-checked="true"',
      'font-size: 20px',
      'color: rgb(22, 119, 255)',
      'tiptap-link',
      'tiptap-image',
      'tiptap-table',
      'data:image/png;base64'
    ])
      expect(html).toContain(part)
    expect(
      editor.extensionManager.extensions.some(
        extension => extension.name === 'link' && extension.options.openOnClick === false
      )
    ).toBe(true)
    expect(toolbar.every(title => !!button(title))).toBe(true)
    expect(host.querySelectorAll('.toolbar button[title]')).toHaveLength(24)
  })
  it('工具栏保留文本、列表、对齐和插入命令，重复扩展对应功能可撤销重做', async () => {
    const { editor, update } = await mount()
    const cases: Array<[string, () => boolean]> = [
      ['粗体', () => editor.isActive('bold')],
      ['斜体', () => editor.isActive('italic')],
      ['下划线', () => editor.isActive('underline')],
      ['删除线', () => editor.isActive('strike')],
      ['高亮', () => editor.isActive('highlight')],
      ['标题 1', () => editor.isActive('heading', { level: 1 })],
      ['标题 2', () => editor.isActive('heading', { level: 2 })],
      ['标题 3', () => editor.isActive('heading', { level: 3 })],
      ['无序列表', () => editor.isActive('bulletList')],
      ['有序列表', () => editor.isActive('orderedList')],
      ['任务列表', () => editor.isActive('taskList')],
      ['左对齐', () => editor.isActive({ textAlign: 'left' })],
      ['居中对齐', () => editor.isActive({ textAlign: 'center' })],
      ['右对齐', () => editor.isActive({ textAlign: 'right' })],
      ['代码块', () => editor.isActive('codeBlock')],
      ['引用', () => editor.isActive('blockquote')]
    ]
    for (const [title, active] of cases) {
      editor.commands.setContent('<p>正文</p>', { emitUpdate: false })
      editor.commands.setTextSelection({ from: 1, to: 3 })
      button(title).click()
      await flush()
      expect(active(), title).toBe(true)
    }
    editor.commands.setContent('<p>正文</p>', { emitUpdate: false })
    editor.commands.selectAll()
    vi.spyOn(window, 'prompt').mockReturnValue('https://example.com/new')
    button('插入链接').click()
    await flush()
    expect(editor.getHTML()).toContain('https://example.com/new')
    button('字号').click()
    await flush()
    Array.from(host.querySelectorAll<HTMLElement>('.popup-item'))
      .find(item => item.textContent?.trim() === '20px')!
      .click()
    await flush()
    button('字体颜色').click()
    await flush()
    host.querySelector<HTMLElement>('.color-item[title="蓝色"]')!.click()
    await flush()
    expect(editor.getHTML()).toContain('font-size: 20px')
    expect(editor.getHTML()).toContain('rgb(22, 119, 255)')
    button('插入表格').click()
    await flush()
    expect(editor.getHTML()).toContain('<table')
    editor.commands.setContent('<p>末尾</p>', { emitUpdate: false })
    editor.commands.setTextSelection(3)
    button('插入水平线').click()
    await flush()
    expect(editor.getHTML()).toContain('<hr>')
    button('撤销').click()
    await flush()
    expect(editor.getHTML()).not.toContain('<hr>')
    button('重做').click()
    await flush()
    expect(editor.getHTML()).toContain('<hr>')
    expect(update).toHaveBeenCalled()
  })
  it('外部内容更新不回写，禁用切换保持只读及既有 update 事件语义', async () => {
    const { editor, state, update, element } = await mount('<p>初值</p>', true)
    expect(element.getAttribute('contenteditable')).toBe('false')
    expect(toolbar.every(title => button(title).disabled)).toBe(true)
    state.content = '<p>外部更新</p>'
    await flush()
    expect(editor.getHTML()).toBe('<p>外部更新</p>')
    expect(update).not.toHaveBeenCalled()
    state.disabled = false
    await flush()
    expect(element.getAttribute('contenteditable')).toBe('true')
    expect(update).toHaveBeenLastCalledWith('<p>外部更新</p>')
    editor.commands.insertContent('新内容')
    await flush()
    expect(state.content).toContain('新内容')
    state.disabled = true
    await flush()
    expect(element.getAttribute('contenteditable')).toBe('false')
    expect(toolbar.every(title => button(title).disabled)).toBe(true)
  })
  it('图片粘贴、拖入和文件按钮沿用上传路径，禁用态不发起上传', async () => {
    const { editor, state } = await mount()
    const file = new File(['fixture'], 'image.png', { type: 'image/png' })
    const paste = new Event('paste', { cancelable: true }) as ClipboardEvent
    Object.defineProperty(paste, 'clipboardData', { value: { items: [{ type: file.type, getAsFile: () => file }] } })
    editor.options.editorProps.handlePaste!(editor.view, paste, Slice.empty)
    await flush()
    expect(paste.defaultPrevented).toBe(true)
    expect(upload).toHaveBeenLastCalledWith(
      expect.stringContaining('/infra/file/upload'),
      expect.objectContaining({
        method: 'POST',
        headers: { Authorization: 'Bearer test-editor-token' },
        body: expect.any(FormData)
      })
    )
    expect(editor.getHTML()).toContain('/infra/file/1/get/fixture/image.png')
    const drop = new Event('drop', { cancelable: true }) as DragEvent
    Object.defineProperty(drop, 'dataTransfer', { value: { files: [file] } })
    editor.options.editorProps.handleDrop!(editor.view, drop, Slice.empty, false)
    await flush()
    vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(function (this: HTMLInputElement) {
      Object.defineProperty(this, 'files', { value: [file] })
      this.onchange?.(new Event('change'))
    })
    button('插入图片').click()
    await flush()
    expect(upload).toHaveBeenCalledTimes(3)
    state.disabled = true
    await flush()
    editor.options.editorProps.handlePaste!(editor.view, paste, Slice.empty)
    editor.options.editorProps.handleDrop!(editor.view, drop, Slice.empty, false)
    button('插入图片').click()
    await flush()
    expect(upload).toHaveBeenCalledTimes(3)
  })
  it('每个扩展只注册一次且没有重复名称警告', async () => {
    const { editor } = await mount()
    const names = editor.extensionManager.extensions.map(extension => extension.name)
    expect(names).toHaveLength(new Set(names).size)
    expect(vi.mocked(console.warn).mock.calls.some(args => String(args[0]).includes('Duplicate extension names'))).toBe(
      false
    )
  })
})
