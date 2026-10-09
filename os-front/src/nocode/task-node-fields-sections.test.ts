// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { compileStyle, parse } from 'vue/compiler-sfc'
import Antd, { Modal } from 'ant-design-vue'
import TaskNodeFields from '@/views/nocode/task-center/TaskNodeFields.vue'
import fieldsSource from '@/views/nocode/task-center/TaskNodeFields.vue?raw'
import { newTaskNode } from './task-center'
import type { TaskNodeInput } from '@/types/nocode/task-center'

vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: { formPreview: vi.fn() } }) }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('@/components/UserSelector/index.vue', () => ({ default: { render: () => null } }))
// 保留真实卡片、工时表、弹窗和完成要求，只替换远端资源候选列表。
vi.mock('@/views/nocode/task-center/TaskEntrySelector.vue', () => ({
  default: defineComponent({
    props: ['open'],
    setup: props => () => (props.open ? h('section', { 'data-entry-selector': '' }, '业务资源候选') : null)
  })
}))

let app: App | undefined
let host: HTMLDivElement | undefined
let style: HTMLStyleElement | undefined
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (!value) throw new Error('缺少配置入口')
  return value
}
function action(label: string, root: ParentNode = required(host)) {
  return required(
    Array.from(root.querySelectorAll('button')).find(
      button => button.textContent?.replace(/\s/g, '') === label.replace(/\s/g, '')
    )
  )
}
async function mount(section: 'business' | 'feedback', node: TaskNodeInput, readonly = false) {
  const model = ref(node)
  app = createApp(() =>
    h(TaskNodeFields, {
      modelValue: model.value,
      'onUpdate:modelValue': (value: TaskNodeInput) => (model.value = value),
      section,
      members: [],
      isRoot: true,
      templateEditing: true,
      readonly
    })
  )
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  // Vitest 不注入单文件组件样式：使用组件自己的 scopeId 编译真实样式，验证最终 DOM 可见性。
  const sourceStyle = required(parse(fieldsSource).descriptor.styles[0])
  const compiled = compileStyle({
    source: sourceStyle.content,
    filename: 'TaskNodeFields.vue',
    scoped: true,
    id: (TaskNodeFields as unknown as { __scopeId: string }).__scopeId
  })
  expect(compiled.errors).toEqual([])
  style = document.createElement('style')
  style.textContent = compiled.code
  document.head.append(style)
  await flush()
  return model
}
const root = (): TaskNodeInput => ({
  ...newTaskNode(),
  title: '装修模板',
  dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' }
})
function legacyEntryNode(): TaskNodeInput {
  return {
    ...root(),
    entries: [
      {
        key: 'feedback',
        name: '施工日志',
        binding: null,
        dataMode: 'ROOT_SHARED',
        sourceNodeId: null,
        sourceEntryKey: null,
        readableFieldIds: null,
        writableFieldIds: null,
        required: false,
        allowAll: false
      }
    ]
  }
}
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    })
  })
  const nativeStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => nativeStyle(element))
})
afterEach(async () => {
  app?.unmount()
  app = undefined
  host?.remove()
  style?.remove()
  Modal.destroyAll()
  await flush()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('模板业务关联页签的真实卡片与配置弹窗', () => {
  it('业务页签直接展示办理项，隐藏重复折叠标题但保留可见的视图选择入口', async () => {
    await mount('business', root())
    const outer = required(
      host?.querySelector<HTMLElement>(
        '.task-optional-sections--standalone > .ant-collapse-item > .ant-collapse-header'
      )
    )
    expect(getComputedStyle(outer).display).toBe('none')
    const panel = required(host?.querySelector<HTMLElement>('.task-business-options'))
    expect(getComputedStyle(panel).display).not.toBe('none')
    expect(panel.querySelector('.ant-collapse-header')).toBeNull()
    expect(panel.textContent).toContain('业务办理项')
    const picker = action('选择业务视图')
    expect(getComputedStyle(picker).display).not.toBe('none')
    picker.click()
    await flush()
    expect(host?.querySelector('[data-entry-selector]')).not.toBeNull()
    expect(host?.textContent).not.toContain('选择反馈表单')
    expect(host?.querySelector('.business-items__work-table')).toBeNull()
  })
  it('历史反馈入口兼容到业务卡片，点击卡片配置并保存完成要求，不丢失原关联字段', async () => {
    const node = legacyEntryNode()
    const originalEntry = structuredClone(required(node.entries?.[0]))
    const model = await mount('feedback', node)
    const card = required(host?.querySelector<HTMLElement>('.business-item'))
    expect(host?.querySelector('.business-items__work-table')).not.toBeNull()
    expect(getComputedStyle(card).display).not.toBe('none')
    expect(card.querySelector('[aria-label="配置：施工日志"]')).not.toBeNull()
    expect(Array.from(card.querySelectorAll('button')).some(button => button.textContent?.trim() === '配置')).toBe(
      false
    )
    expect(host?.textContent).not.toContain('选择反馈表单')
    card.click()
    await flush()
    const dialog = required(document.querySelector<HTMLElement>('[role="dialog"]'))
    expect(dialog.textContent).toContain('配置业务办理项')
    required(dialog.querySelector<HTMLInputElement>('.entry-config input[type="checkbox"]')).click()
    await flush()
    expect(model.value.entries?.[0]?.required).toBe(false)
    action('保存配置', dialog).click()
    await flush()
    expect(model.value.entries?.[0]).toEqual({ ...originalEntry, required: true })
    expect(model.value.entries?.[0]?.required).toBe(true)
    expect(card.textContent).toContain('必办')
    action('选择业务视图').click()
    await flush()
    expect(host?.querySelector('[data-entry-selector]')).not.toBeNull()
  })
  it('只读模板可点击查看配置，但不能添加、移除或修改办理要求', async () => {
    const node = legacyEntryNode()
    const before = JSON.stringify(node)
    const model = await mount('business', node, true)
    expect(host?.querySelector('[aria-label^="移除关联："]')).toBeNull()
    expect(host?.textContent).not.toContain('选择业务视图')
    required(host?.querySelector<HTMLButtonElement>('[aria-label="查看配置：施工日志"]')).click()
    await flush()
    const dialog = required(document.querySelector<HTMLElement>('[role="dialog"]'))
    const checkbox = required(dialog.querySelector<HTMLInputElement>('.entry-config input[type="checkbox"]'))
    expect(checkbox.disabled).toBe(true)
    checkbox.click()
    await flush()
    expect(dialog.textContent).not.toContain('保存配置')
    expect(JSON.stringify(model.value)).toBe(before)
  })
  it('X 只打开移除确认而不打开配置，取消后原卡片和数据不变', async () => {
    const node = legacyEntryNode()
    const before = JSON.stringify(node)
    const model = await mount('business', node)
    const card = required(host?.querySelector<HTMLElement>('.business-item'))
    required(card.querySelector<HTMLButtonElement>('[aria-label="移除关联：施工日志"]')).click()
    await flush()
    const dialog = required(document.querySelector<HTMLElement>('[role="dialog"]'))
    expect(dialog.textContent).toContain('移除“施工日志”？')
    expect(dialog.textContent).toContain('不删除已经存在的业务数据')
    expect(dialog.querySelector('.entry-config')).toBeNull()
    expect(JSON.stringify(model.value)).toBe(before)
    action('保留', dialog).click()
    await flush()
    expect(JSON.stringify(model.value)).toBe(before)
    expect(host?.querySelectorAll('.business-item')).toHaveLength(1)
  })
})
