// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { TASK_CONTENT_MAX_LENGTH, taskContentHtml, taskContentSummary } from './task-content'
import TaskContentField from '@/views/nocode/task-center/TaskContentField.vue'

const feedback = vi.hoisted(() => ({ warning: vi.fn(), error: vi.fn() }))
vi.mock('ant-design-vue', () => ({ message: feedback }))
vi.mock('@/components/TiptapEditor.vue', () => ({
  __esModule: true,
  default: defineComponent({
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          'data-task-editor': '',
          value: props.modelValue,
          disabled: props.disabled,
          onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
        })
  })
}))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title', 'showFooter', 'okText', 'cancelText'],
    emits: ['ok', 'cancel'],
    setup:
      (props, { emit, slots }) =>
      () =>
        props.open
          ? h('section', { role: 'dialog', 'aria-label': props.title }, [
              h('button', { 'aria-label': '关闭弹窗', onClick: () => emit('cancel') }, '关闭'),
              slots.formItems?.(),
              slots.default?.(),
              props.showFooter === false
                ? null
                : slots.footer?.() || [
                    h('button', { onClick: () => emit('cancel') }, props.cancelText || '取消'),
                    h('button', { onClick: () => emit('ok') }, props.okText || '确定')
                  ]
            ])
          : null
  })
}))

let app: App | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let index = 0; index < 14; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少测试目标元素')
  return value
}
function trigger() {
  return required(host.querySelector<HTMLButtonElement>('button'))
}
function dialog() {
  return host.querySelector<HTMLElement>('[role="dialog"]')
}
function dialogButton(label: string) {
  return required(
    Array.from(required(dialog()).querySelectorAll<HTMLButtonElement>('button')).find(
      item => item.textContent?.trim() === label
    )
  )
}
async function changeContent(value: string) {
  const editor = required(host.querySelector<HTMLTextAreaElement>('[data-task-editor]'))
  editor.value = value
  editor.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
async function mount(
  options: { modelValue?: string | null; readonly?: boolean; disabled?: boolean; label?: string } = {}
) {
  const state = reactive({ modelValue: '', readonly: false, disabled: false, ...options })
  const update = vi.fn((value: string) => {
    state.modelValue = value
  })
  app = createApp(() => h(TaskContentField, { ...state, 'onUpdate:modelValue': update }))
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { attrs, slots }) =>
        () =>
          h('button', { ...attrs, disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup: props => () => h('div', { role: 'alert' }, [props.message, props.description])
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { state, update }
}
beforeEach(() => vi.clearAllMocks())
afterEach(async () => {
  app?.unmount()
  app = undefined
  await flush()
  host?.remove()
})

describe('任务内容纯文本兼容和安全展示', () => {
  it('兼容空值，不把空富文本占位作为任务正文', () => {
    for (const source of [undefined, null, '', '  ', '<p></p>', '<p><br></p>']) {
      expect(taskContentSummary(source)).toBe('')
      expect(taskContentHtml(source)).toBe('')
    }
  })

  it('旧纯文本中的比较符号、实体和换行不当作 HTML 执行', () => {
    const source = '检查数量 < 5 & 进度 > 2\n下一步：核对 &lt;原文&gt;'
    const html = taskContentHtml(source)
    const doc = new DOMParser().parseFromString(html, 'text/html')
    expect(doc.body.textContent).toContain('检查数量 < 5 & 进度 > 2')
    expect(doc.body.textContent).toContain('下一步：核对 &lt;原文&gt;')
    expect(taskContentSummary(source)).toBe(source)
    expect(taskContentSummary(html)).toContain('\n下一步')
  })

  it('保留常用富文本格式，列表摘要不泄露 HTML 标记', () => {
    const source = '<h2>交付要求</h2><p><strong>重点</strong><em>说明</em></p><ul><li>第一项</li></ul>'
    const doc = new DOMParser().parseFromString(taskContentHtml(source), 'text/html')
    expect(doc.querySelector('h2')?.textContent).toBe('交付要求')
    expect(doc.querySelector('strong')?.textContent).toBe('重点')
    expect(doc.querySelector('em')?.textContent).toBe('说明')
    expect(doc.querySelector('li')?.textContent).toBe('第一项')
    const summary = taskContentSummary(source)
    expect(summary).toContain('交付要求')
    expect(summary).toContain('重点说明')
    expect(summary).toContain('第一项')
    expect(summary).not.toContain('<')
  })

  it('移除存量内容的脚本、事件属性和危险协议', () => {
    const source =
      '<p onclick="alert(1)">正文</p><script>window.bad = true</script>' +
      '<a href="javascript:alert(1)">危险链接</a><img src="x" onerror="alert(1)">' +
      '<iframe src="https://example.com"></iframe>'
    const html = taskContentHtml(source)
    const doc = new DOMParser().parseFromString(html, 'text/html')
    expect(doc.querySelector('script,iframe,[onclick],[onerror]')).toBeNull()
    expect(html).not.toContain('javascript:')
    expect(taskContentSummary(source)).toContain('正文')
    expect(taskContentSummary(source)).not.toContain('window.bad')
  })

  it('安全图片、链接、表格结构保存后仍能回显', () => {
    const source =
      '<p><a href="https://example.com/requirements">验收要求</a></p>' +
      '<img src="/infra/file/1/get/task-content-fixture.png" alt="现场照片">' +
      '<table><tbody><tr><th colspan="2">检查项</th></tr><tr><td>数量</td><td>3</td></tr></tbody></table>'
    const doc = new DOMParser().parseFromString(taskContentHtml(source), 'text/html')
    expect(doc.querySelector('a')?.getAttribute('href')).toBe('https://example.com/requirements')
    expect(doc.querySelector('img')?.getAttribute('src')).toBe('/infra/file/1/get/task-content-fixture.png')
    expect(doc.querySelector('th')?.getAttribute('colspan')).toBe('2')
    expect(doc.querySelectorAll('tr')).toHaveLength(2)
    expect(taskContentSummary(source)).toContain('[图片]')
  })
})

describe('任务内容统一富文本弹窗', () => {
  it('列表只显示内容摘要，点击才打开编辑器并在确认时回写', async () => {
    const { update, state } = await mount({ modelValue: '<p><strong>既有内容</strong></p>' })
    expect(trigger().textContent).toContain('既有内容')
    expect(trigger().textContent).not.toContain('<strong>')
    expect(dialog()).toBeNull()
    expect(host.querySelector('[data-task-editor]')).toBeNull()
    trigger().click()
    await flush()
    expect(dialog()?.getAttribute('aria-label')).toContain('任务内容')
    expect(required(host.querySelector<HTMLTextAreaElement>('[data-task-editor]')).value).toContain('<strong>')
    await changeContent('<p><em>新内容</em></p>')
    expect(update).not.toHaveBeenCalled()
    dialogButton('确定').click()
    await flush()
    expect(update).toHaveBeenCalledTimes(1)
    expect(state.modelValue).toContain('<em>新内容</em>')
    expect(dialog()).toBeNull()
    expect(trigger().textContent).toContain('新内容')
  })

  it('空内容显示添加入口；取消丢弃草稿，下次打开恢复原值', async () => {
    const { update } = await mount()
    expect(trigger().textContent).toContain('添加')
    trigger().click()
    await flush()
    await changeContent('<p>尚未确认</p>')
    dialogButton('取消').click()
    await flush()
    expect(update).not.toHaveBeenCalled()
    expect(dialog()).toBeNull()
    trigger().click()
    await flush()
    expect(required(host.querySelector<HTMLTextAreaElement>('[data-task-editor]')).value).not.toContain('尚未确认')
  })

  it('打开存量纯文本未修改即确认，不静默改变历史值', async () => {
    const { update, state } = await mount({ modelValue: '历史纯文本\n第二行' })
    trigger().click()
    await flush()
    expect(required(host.querySelector<HTMLTextAreaElement>('[data-task-editor]')).value).toContain('<p>')
    dialogButton('确定').click()
    await flush()
    expect(update).not.toHaveBeenCalled()
    expect(state.modelValue).toBe('历史纯文本\n第二行')
    expect(dialog()).toBeNull()
  })

  it('只读任务可查看富文本但不能编辑或保存', async () => {
    const { update } = await mount({ modelValue: '<p><strong>历史内容</strong></p>', readonly: true })
    trigger().click()
    await flush()
    expect(dialog()).not.toBeNull()
    const editor = host.querySelector<HTMLTextAreaElement>('[data-task-editor]')
    if (editor) {
      expect(editor.disabled).toBe(true)
      expect(editor.value).toContain('历史内容')
    } else {
      expect(dialog()?.querySelector('strong')?.textContent).toBe('历史内容')
    }
    expect(Array.from(required(dialog()).querySelectorAll('button')).some(item => item.textContent === '确定')).toBe(
      false
    )
    required(host.querySelector<HTMLButtonElement>('[aria-label="关闭弹窗"]')).click()
    await flush()
    expect(update).not.toHaveBeenCalled()
    expect(dialog()).toBeNull()
  })

  it('禁用态不能打开任务内容弹窗', async () => {
    const { update } = await mount({ modelValue: '旧内容', disabled: true })
    expect(trigger().disabled).toBe(true)
    trigger().click()
    await flush()
    expect(dialog()).toBeNull()
    expect(update).not.toHaveBeenCalled()
  })

  it('切换任务或外部刷新正文时关闭旧草稿，避免把旧任务内容写回新任务', async () => {
    const { update, state } = await mount({ modelValue: '任务甲' })
    trigger().click()
    await flush()
    await changeContent('<p>任务甲未保存修改</p>')
    state.modelValue = '任务乙'
    await flush()
    expect(dialog()).toBeNull()
    expect(update).not.toHaveBeenCalled()
    expect(trigger().textContent).toContain('任务乙')
    trigger().click()
    await flush()
    expect(required(host.querySelector<HTMLTextAreaElement>('[data-task-editor]')).value).toContain('任务乙')
  })

  it.each(['readonly', 'disabled'] as const)('打开后 %s 失权会关闭未保存草稿', async key => {
    const { update, state } = await mount({ modelValue: '任务内容' })
    trigger().click()
    await flush()
    await changeContent('<p>失权前修改</p>')
    state[key] = true
    await flush()
    expect(dialog()).toBeNull()
    expect(update).not.toHaveBeenCalled()
  })

  it('包含 HTML 的保存长度受 4,000 字符限制，超限不回写也不关闭', async () => {
    const { update } = await mount()
    expect(TASK_CONTENT_MAX_LENGTH).toBe(4000)
    trigger().click()
    await flush()
    await changeContent(`<p>${'字'.repeat(TASK_CONTENT_MAX_LENGTH)}</p>`)
    dialogButton('确定').click()
    await flush()
    expect(update).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
    expect(host.textContent).toMatch(/4000|4,000|超出|超过/)
    await changeContent(`<p>${'字'.repeat(TASK_CONTENT_MAX_LENGTH - 7)}</p>`)
    dialogButton('确定').click()
    await flush()
    expect(update).toHaveBeenCalledTimes(1)
    expect(update.mock.calls[0]?.[0]).toHaveLength(TASK_CONTENT_MAX_LENGTH)
    expect(dialog()).toBeNull()
  })
})
