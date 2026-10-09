// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import ApplicationDeleteDialog from '@/views/nocode/application/components/ApplicationDeleteDialog.vue'
import type { ApplicationRow } from '@/types/nocode/application'

const api = vi.hoisted(() => ({ deletePreview: vi.fn(), deleteApplication: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: api }) }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', [slots.formItems?.(), slots.footer?.()]) : null
    })
  }
})
const row = { id: '21', name: '测试应用', revision: 1 } as ApplicationRow
const preview = {
  application: { ...row, revision: 4 },
  objectCount: 1,
  resourceCount: 2,
  taskEntryCount: 1,
  blockers: [] as string[]
}
let app: App, host: HTMLDivElement
const deleted = vi.fn()
const flush = async () => {
  for (let n = 0; n < 8; n++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (text: string) => {
  const found = Array.from(host.querySelectorAll('button')).find(node => node.textContent?.trim() === text)
  if (!found) throw new Error(`未找到按钮：${text}`)
  return found
}
async function mount() {
  const state = reactive<{ application?: ApplicationRow }>({ application: row })
  app = createApp(() => h(ApplicationDeleteDialog, { ...state, onDeleted: deleted }))
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  app.component('ASpace', plain)
  app.component('AFormItem', plain)
  app.component('ASpin', plain)
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.message, props.description, slots.description?.()])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('textarea', {
            value: props.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLTextAreaElement).value)
          })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return state
}
async function reason() {
  const field = host.querySelector('textarea')
  if (!field) throw new Error('未找到删除说明输入框')
  field.value = '停止使用此应用'
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.deletePreview.mockReset().mockResolvedValue(preview)
  api.deleteApplication.mockReset().mockResolvedValue(true)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('应用删除影响与并发确认', () => {
  it('依赖阻断时填写说明也不能删除', async () => {
    api.deletePreview.mockResolvedValue({ ...preview, blockers: ['采购流程仍绑定此应用'] })
    await mount()
    await reason()
    expect(host.textContent).toContain('采购流程仍绑定此应用')
    expect(button('移入回收站').disabled).toBe(true)
    expect(api.deleteApplication).not.toHaveBeenCalled()
  })
  it('读取失败不允许盲删，重试成功后才恢复提交', async () => {
    api.deletePreview.mockRejectedValueOnce(new Error('无法检查依赖'))
    await mount()
    await reason()
    expect(button('移入回收站').disabled).toBe(true)
    button('重新检查').click()
    await flush()
    expect(button('移入回收站').disabled).toBe(false)
  })
  it('使用预检返回修订，提交失败保留说明并要求重新检查', async () => {
    api.deleteApplication.mockRejectedValueOnce(new Error('应用已被其他操作修改'))
    await mount()
    await reason()
    button('移入回收站').click()
    await flush()
    expect(api.deleteApplication).toHaveBeenCalledWith({ id: '21', expectedRevision: 4, reason: '停止使用此应用' })
    expect(deleted).not.toHaveBeenCalled()
    expect(host.querySelector('textarea')?.value).toBe('停止使用此应用')
    expect(button('移入回收站').disabled).toBe(true)
    button('重新检查').click()
    await flush()
    button('移入回收站').click()
    await flush()
    expect(deleted).toHaveBeenCalledTimes(1)
  })
  it('切换删除对象时忽略旧预检结果', async () => {
    let finish: (value: unknown) => void = () => {
      throw new Error('预检请求尚未创建')
    }
    api.deletePreview.mockReturnValueOnce(
      new Promise(resolve => {
        finish = resolve
      })
    )
    const state = await mount()
    state.application = { ...row, id: '22', name: '另一应用' }
    api.deletePreview.mockResolvedValueOnce({ ...preview, application: { ...row, id: '22', revision: 5 } })
    await flush()
    finish({ ...preview, blockers: ['旧应用流程'] })
    await flush()
    await reason()
    button('移入回收站').click()
    await flush()
    expect(api.deleteApplication).toHaveBeenCalledWith({ id: '22', expectedRevision: 5, reason: '停止使用此应用' })
    expect(host.textContent).not.toContain('旧应用流程')
  })
})
