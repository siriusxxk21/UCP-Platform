// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import FormCreate from '@form-create/ant-design-vue'
import FormDetailProvider from '@/views/nocode/application/components/FormDetailProvider.vue'
import FormDetailOutlet from '@/views/nocode/application/components/FormDetailOutlet.vue'
import RecordReadView from '@/views/nocode/application/components/RecordReadView.vue'
import RecordForm from '@/views/nocode/application/components/RecordForm.vue'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType } from '@/types/nocode/enums'
import { formLayoutNodes } from './form-detail-layout'

vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol.for('ucp-platform.nocode.platform'),
  useNocodePlatform: () => ({ runtime: {}, applications: {} })
}))

const field = (id: string, required = false): ObjectField => ({
  id,
  key: id,
  code: id,
  name: id,
  type: FieldType.TEXT,
  required,
  unique: false,
  sort: 0,
  length: null,
  precision: null,
  scale: null
})
const fieldNode = (id: string) => uiNode(NodeKind.FIELD, { fieldId: id })
const detailNode = (id: string, mode: 'GRID' | 'CARDS' = 'GRID') =>
  uiNode(NodeKind.INTERNAL_DETAIL, { detail: { detailId: id, mode }, text: '明细-' + id })
const model = { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' }
let app: App | undefined, host: HTMLDivElement | undefined
const flush = async () => {
  for (let i = 0; i < 10; i++) await nextTick()
}
function mount(component: ReturnType<typeof defineComponent>) {
  app = createApp(component)
  app.use(Antd)
  app.use(FormCreate)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  return host
}
beforeEach(() => {
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
  vi.stubGlobal('CSS', { escape: (value: string) => value })
  Element.prototype.scrollIntoView = vi.fn()
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.unstubAllGlobals()
})

describe('内部明细在统一布局中原位呈现', () => {
  it('只读卡片中的明细处于字段之间，未授权节点不泄露，旧配置按兼容顺序追加', async () => {
    const visible = ref(['lines'])
    const nodes = [
      fieldNode('before'),
      uiNode(NodeKind.CARD, { text: '分组', children: [detailNode('lines', 'CARDS'), detailNode('secret')] }),
      fieldNode('after')
    ]
    mount(
      defineComponent({
        setup: () => () =>
          h(
            FormDetailProvider,
            { detailIds: visible.value },
            {
              default: () =>
                h(RecordReadView, {
                  nodes: formLayoutNodes(nodes, ['lines', 'legacy']),
                  fields: [field('before'), field('after')],
                  values: { before: '前置内容', after: '后置内容' },
                  options: {},
                  applicationId: 'app',
                  relations: []
                }),
              detail: (context: { detailId: string; mode?: string; title?: string }) =>
                h(
                  'span',
                  { 'data-detail': context.detailId, 'data-mode': context.mode },
                  context.detailId === 'lines' ? '真实明细内容' : '保密内容'
                )
            }
          )
      })
    )
    await flush()
    expect(host!.textContent!.indexOf('前置内容')).toBeLessThan(host!.textContent!.indexOf('真实明细内容'))
    expect(host!.textContent!.indexOf('真实明细内容')).toBeLessThan(host!.textContent!.indexOf('后置内容'))
    expect(host!.querySelector('.ant-card [data-detail="lines"]')?.getAttribute('data-mode')).toBe('CARDS')
    expect(host!.textContent).not.toContain('保密内容')
    visible.value = ['lines', 'legacy']
    await flush()
    expect(host!.querySelectorAll('[data-detail]')).toHaveLength(2)
    expect(host!.textContent!.indexOf('后置内容')).toBeLessThan(host!.textContent!.indexOf('保密内容'))
  })
  it('关联记录使用最近的provider，不会借用外层同名明细或显示外层授权节点', async () => {
    mount(
      defineComponent({
        setup: () => () =>
          h(
            FormDetailProvider,
            { detailIds: ['same', 'outerOnly'] },
            {
              default: () => [
                h(FormDetailOutlet, { detailId: 'same' }),
                h(
                  FormDetailProvider,
                  { detailIds: ['same'] },
                  {
                    default: () => [
                      h(FormDetailOutlet, { detailId: 'same' }),
                      h(FormDetailOutlet, { detailId: 'outerOnly' })
                    ],
                    detail: () => h('span', '关联记录明细')
                  }
                )
              ],
              detail: () => h('span', '主单明细')
            }
          )
      })
    )
    await flush()
    expect(host!.textContent).toBe('主单明细关联记录明细')
  })
  it('真实表单引擎保持单一主表实例，未访问页签也挂载明细校验并能定位失败页签', async () => {
    const main = ref<InstanceType<typeof RecordForm>>(),
      detail = ref<InstanceType<typeof RecordForm>>()
    const values = ref<Record<string, unknown>>({ before: '主表输入', after: '保留' })
    const child = ref<Record<string, unknown>>({})
    const nodes = [
      fieldNode('before'),
      uiNode(NodeKind.TABS, {
        children: [
          uiNode(NodeKind.TAB, { id: 'first-tab', text: '首个页签', children: [fieldNode('after')] }),
          uiNode(NodeKind.TAB, { id: 'detail-tab', text: '明细页签', children: [detailNode('lines')] })
        ]
      })
    ]
    mount(
      defineComponent({
        setup: () => () =>
          h(
            FormDetailProvider,
            { detailIds: ['lines'] },
            {
              default: () =>
                h(RecordForm, {
                  ref: main,
                  'data-form': 'main',
                  fields: [field('before'), field('after')],
                  options: {},
                  model,
                  creating: false,
                  nodes,
                  modelValue: values.value,
                  'onUpdate:modelValue': value => {
                    values.value = value
                  }
                }),
              detail: () =>
                h(RecordForm, {
                  ref: detail,
                  'data-form': 'detail',
                  detailId: 'lines',
                  fields: [field('requiredDetail', true)],
                  options: {},
                  model,
                  creating: true,
                  nodes: [fieldNode('requiredDetail')],
                  modelValue: child.value,
                  'onUpdate:modelValue': value => {
                    child.value = value
                  }
                })
            }
          )
      })
    )
    await flush()
    expect(host!.querySelectorAll('[data-form="main"]')).toHaveLength(1)
    expect(host!.querySelectorAll('[data-form="detail"]')).toHaveLength(1)
    expect(host!.querySelectorAll('form')).toHaveLength(1)
    expect(host!.querySelector('form form')).toBeNull()
    const detailContainer = host!.querySelector<HTMLElement>('[data-form="detail"] .ant-form')
    expect(detailContainer?.tagName).toBe('DIV')
    expect(detail.value).toBeDefined()
    await expect(detail.value!.validate()).rejects.toThrow('requiredDetail')
    await flush()
    expect(host!.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toContain('明细页签')
    expect(values.value).toEqual({ before: '主表输入', after: '保留' })
    const input = detailContainer?.querySelector('input')
    if (!input) throw new Error('未找到明细输入框')
    const moved = vi.fn()
    host!.addEventListener('keydown', moved)
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.dispatchEvent(enter)
    expect(enter.defaultPrevented).toBe(true)
    expect(moved).toHaveBeenCalledOnce()
    const composing = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true })
    input.dispatchEvent(composing)
    expect(composing.defaultPrevented).toBe(false)
    input.value = '填写完整'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    await expect(detail.value!.validate()).resolves.toBeUndefined()
    expect(child.value.requiredDetail).toBe('填写完整')
    const submitted = vi.fn()
    host!.querySelector('form')?.addEventListener('submit', submitted)
    const submit = new Event('submit', { bubbles: true, cancelable: true })
    host!.querySelector('[data-form="detail"] input')?.dispatchEvent(submit)
    await flush()
    expect(submit.defaultPrevented).toBe(true)
    expect(submitted).not.toHaveBeenCalled()
  })
})
