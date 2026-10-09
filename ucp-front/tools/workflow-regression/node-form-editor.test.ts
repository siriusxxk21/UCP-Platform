import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive } from 'vue'
import Editor from '@/views/bpm/model/form/NodeFormEditor.vue'
import type { FormSource, NodeFormBinding } from '@/views/bpm/model/form/node-form'
const api = vi.hoisted(() => ({ application: vi.fn() }))
vi.mock('@/api/bpm/form', () => ({ getFormSimpleList: async () => [{ id: 1, name: '申请表' }] }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    runtime: {
      mine: async () => [
        { id: 'a', name: '应用 A' },
        { id: 'b', name: '应用 B' }
      ],
      application: api.application
    }
  })
}))
const disposers: Array<() => void> = []
afterEach(() => {
  disposers.splice(0).forEach(dispose => dispose())
  vi.clearAllMocks()
})
async function settle() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
function pending() {
  let resolve!: (value: any) => void
  const promise = new Promise(r => {
    resolve = r
  })
  return { promise, resolve }
}
function release(version: number) {
  return {
    application: { id: 'a' },
    versionNo: version,
    checksum: `hash${version}`,
    definition: { resources: [{ id: `f${version}`, name: `表单${version}`, kind: 'FORM', config: { objectId: '10' } }] }
  }
}
async function mount() {
  const apply = vi.fn(),
    host = document.createElement('div')
  document.body.append(host)
  const inputs = reactive({
    binding: {
      mode: 'OVERRIDE',
      source: {
        kind: 'APPLICATION_RESOURCE',
        configuration: {
          resource: {
            applicationId: 'a',
            applicationVersion: 9,
            applicationChecksum: 'old',
            resourceId: 'old',
            resourceKind: 'FORM'
          },
          objectId: '10',
          operation: 'CREATE'
        }
      }
    } as NodeFormBinding,
    inherited: { kind: 'FLOW_FORM', formId: '1' } as FormSource,
    onApply: apply
  })
  const app = createApp(() => h(Editor, inputs))
  const wrapper = defineComponent({
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    }
  })
  for (const name of ['a-tag', 'a-form-item', 'a-space', 'a-radio-group', 'a-checkbox']) app.component(name, wrapper)
  app.component(
    'a-radio',
    defineComponent({
      props: ['value'],
      setup:
        (p, { slots }) =>
        () =>
          h('label', [h('input', { type: 'radio', value: p.value }), slots.default?.()])
    })
  )
  app.component('a-alert', defineComponent({ props: ['message'], setup: p => () => h('div', p.message) }))
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  app.component(
    'a-select-option',
    defineComponent({
      props: ['value', 'disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('option', { value: p.value, disabled: p.disabled }, slots.default?.())
    })
  )
  app.component(
    'a-select',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['change', 'update:value'],
      setup:
        (p, { slots, emit }) =>
        () =>
          h(
            'select',
            {
              value: p.value,
              disabled: p.disabled,
              onChange: (e: Event) => {
                const value = (e.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
              }
            },
            p.options
              ? p.options.map((o: any) => h('option', { value: o.value, disabled: o.disabled }, o.label))
              : slots.default?.()
          )
    })
  )
  app.mount(host)
  disposers.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
  return { host, apply, inputs }
}
function button(host: HTMLElement, name: string) {
  return Array.from(host.querySelectorAll('button')).find(button => button.textContent === name)!
}
async function select(host: HTMLElement, name: string, value: string) {
  const el = host.querySelector<HTMLSelectElement>(`select[aria-label="${name}"]`)!
  el.value = value
  el.dispatchEvent(new Event('change'))
  await settle()
}
describe('共用节点表单配置交互', () => {
  it('同一节点的等值绑定重新解析不丢弃尚未应用的材料权限', async () => {
    const { host, apply, inputs } = await mount()
    button(host, '配置节点表单').click()
    await settle()
    await select(host, '前序材料查阅权限', 'TASK')
    inputs.binding = JSON.parse(JSON.stringify(inputs.binding))
    await settle()
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="前序材料查阅权限"]')!.value).toBe('TASK')
    expect(button(host, '应用节点配置')).toBeDefined()
    button(host, '应用节点配置').click()
    await settle()
    expect(apply.mock.calls[0]?.[0].materialReview).toEqual({ scope: 'PREVIOUS', access: 'TASK' })
    expect(apply.mock.calls[0]?.[0].source.configuration.resource.applicationVersion).toBe(9)
  })
  it('只读材料查阅与无需填写独立保存，切换来源不丢失已选授权', async () => {
    const { host, apply } = await mount()
    button(host, '配置节点表单').click()
    await settle()
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="前序材料查阅权限"]')!.value).toBe('BUSINESS')
    await select(host, '前序材料查阅权限', 'TASK')
    host.querySelector<HTMLInputElement>('input[value="APPROVAL"]')!.click()
    await settle()
    host.querySelector<HTMLInputElement>('input[value="OVERRIDE"]')!.click()
    await settle()
    await select(host, '节点表单来源', 'NONE')
    button(host, '应用节点配置').click()
    await settle()
    expect(apply).toHaveBeenCalledWith(
      expect.objectContaining({
        source: { kind: 'NONE' },
        materialReview: { scope: 'PREVIOUS', access: 'TASK' }
      }),
      false
    )
  })
  it('取消材料授权修改后恢复旧权限，重新应用旧绑定不自动扩权', async () => {
    const { host, apply } = await mount()
    button(host, '配置节点表单').click()
    await settle()
    await select(host, '前序材料查阅权限', 'TASK')
    button(host, '取消').click()
    await settle()
    button(host, '配置节点表单').click()
    await settle()
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="前序材料查阅权限"]')!.value).toBe('BUSINESS')
    button(host, '应用节点配置').click()
    await settle()
    expect(apply.mock.calls[0]?.[0].materialReview).toBeUndefined()
  })
  it('业务任务改为审批并独立配置时，不再带入不兼容的业务来源', async () => {
    const { host, apply } = await mount()
    button(host, '配置节点表单').click()
    await settle()
    host.querySelector<HTMLInputElement>('input[value="APPROVAL"]')!.click()
    await settle()
    host.querySelector<HTMLInputElement>('input[value="OVERRIDE"]')!.click()
    await settle()
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="节点表单来源"]')!.value).toBe('FLOW_FORM')
    await select(host, '节点流程表单', '1')
    button(host, '应用节点配置').click()
    await settle()
    expect(apply).toHaveBeenCalledWith(
      expect.objectContaining({ mode: 'OVERRIDE', taskMode: 'APPROVAL', source: { kind: 'FLOW_FORM', formId: '1' } }),
      false
    )
  })
  it('打开和取消旧绑定不会查最新资源或提交修改', async () => {
    const { host, apply } = await mount()
    button(host, '配置节点表单').click()
    await settle()
    button(host, '取消').click()
    await settle()
    expect(api.application).not.toHaveBeenCalled()
    expect(apply).not.toHaveBeenCalled()
    expect(host.textContent).toContain('V9')
  })
  it('同一应用的旧响应不能覆盖较新选择，必须选完资源才允许应用', async () => {
    const first = pending(),
      second = pending(),
      last = pending()
    api.application
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(last.promise)
    const { host, apply } = await mount()
    button(host, '配置节点表单').click()
    await settle()
    await select(host, '节点业务应用', 'a')
    await select(host, '节点业务应用', 'b')
    await select(host, '节点业务应用', 'a')
    last.resolve(release(11))
    await settle()
    first.resolve(release(10))
    second.resolve(release(8))
    await settle()
    expect(host.querySelector('select[aria-label="节点应用表单"]')?.textContent).toContain('表单11')
    button(host, '应用节点配置').click()
    await settle()
    expect(apply).not.toHaveBeenCalled()
    await select(host, '节点应用表单', 'f11')
    button(host, '应用节点配置').click()
    await settle()
    expect(apply.mock.calls[0]?.[0].source.configuration.resource.applicationVersion).toBe(11)
  })
})
