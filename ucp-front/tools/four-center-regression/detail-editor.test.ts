import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
const mocks = vi.hoisted(() => ({
  save: vi.fn(),
  submit: vi.fn(),
  submitReceipt: vi.fn(),
  formFill: vi.fn(),
  saved: vi.fn(),
  cancel: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol.for('ucp-platform.nocode.platform'),
  useNocodePlatform: () => ({ runtime: mocks })
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'sandbox-user' } }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({ default: { render: () => null } }))
import RecordEditor from '@/views/nocode/application/components/RecordEditor.vue'
import { nocodePlatformKey } from '@/nocode/platform'
const apps: App[] = []
beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(() => ({ matches: false, addListener() {}, removeListener() {} }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Element.prototype.scrollIntoView = vi.fn()
  if (!globalThis.CSS) vi.stubGlobal('CSS', { escape: (value: string) => value })
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  localStorage.clear()
  vi.clearAllMocks()
})
const flush = async () => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 50))
  await nextTick()
}
function button(label: string) {
  return Array.from(document.querySelectorAll('button')).find(b => b.textContent?.replace(/\s/g, '').includes(label))!
}
function mount(form?: any, handling?: any, record?: any, withSource = false) {
  const field = (id: string, type: string) => ({
    id,
    key: id,
    code: id,
    name: id === 'amount' ? '金额' : '名称',
    type,
    required: false,
    length: null,
    precision: 20,
    scale: 2,
    unique: false,
    sort: 0
  })
  const model: any = {
    writable: true,
    generatedKey: true,
    keyFieldId: null,
    keyType: 'bigint',
    permissions: {
      actions: ['READ', 'CREATE'],
      readFields: ['name'],
      writeFields: ['name'],
      readDetails: ['items'],
      writeDetails: ['items'],
      readRelations: [],
      writeRelations: []
    },
    object: {
      objectId: 'object',
      fields: [field('name', 'TEXT')],
      fieldOptions: {},
      settings: { documentPolicy: handling ? { rules: [], lifecycle: null, handling } : null },
      relations: [],
      details: [
        {
          id: 'items',
          code: 'items',
          name: '采购明细',
          state: 'ACTIVE',
          fields: [field('amount', 'DECIMAL'), ...(withSource ? [field('source', 'TEXT')] : [])],
          fieldOptions: {}
        }
      ]
    },
    details: { items: { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' } }
  }
  mocks.save.mockImplementation(async input => ({
    record: { id: 'saved', revision: '1', values: input.values },
    details: input.details
  }))
  mocks.submit.mockImplementation(async input => ({
    outcome: 'EFFECTIVE',
    result: await mocks.save(input),
    request: null
  }))
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({
    render: () =>
      h(RecordEditor, {
        applicationId: 'app',
        model,
        form,
        record,
        formId: form ? 'form' : undefined,
        onSaved: mocks.saved,
        onCancel: mocks.cancel
      })
  })
  app.use(Antd)
  app.provide(nocodePlatformKey, { runtime: mocks } as any)
  app.mount(host)
  apps.push(app)
  return host
}
describe('整单编辑器的真实粘贴交互', () => {
  it('复制明细不能跳过正在带入或失败状态，明确人工接管后才复制快照', async () => {
    const node = (fieldId: string, fill?: any) => ({
      id: 'node-' + fieldId,
      type: 'FIELD',
      fieldId,
      children: [],
      presentation: { fill }
    })
    const host = mount(
      {
        objectId: 'object',
        nodes: [node('name')],
        detailIds: ['items'],
        detailNodes: {
          items: [
            node('amount', { sourceFieldId: 'source', valueFieldId: 'amount', mode: 'SOURCE_CHANGE' }),
            node('source')
          ]
        }
      },
      undefined,
      {
        record: { id: null, revision: null, values: { name: '复制保护' } },
        details: {
          items: [{ id: null, revision: null, clientRowKey: 'copy-source', values: { amount: '8', source: 'A' } }]
        }
      },
      true
    )
    let reject!: (reason: Error) => void
    mocks.formFill.mockImplementation(
      () =>
        new Promise((_, no) => {
          reject = no
        })
    )
    await flush()
    const source = host.querySelectorAll<HTMLInputElement>('[data-row-key] input')[1]!
    source.value = 'B'
    source.dispatchEvent(new Event('input', { bubbles: true }))
    source.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    expect(mocks.formFill).toHaveBeenCalledTimes(1)
    button('复制').click()
    await flush()
    expect(host.querySelectorAll('[data-row-key]')).toHaveLength(1)
    expect(host.textContent).toContain('此行暂时不能复制')
    reject(new Error('来源读取失败'))
    await flush()
    button('复制').click()
    await flush()
    expect(host.querySelectorAll('[data-row-key]')).toHaveLength(1)
    button('已核对').click()
    await flush()
    button('复制').click()
    await flush()
    const keys = Array.from(host.querySelectorAll<HTMLElement>('[data-row-key]')).map(row => row.dataset.rowKey)
    expect(keys).toHaveLength(2)
    expect(new Set(keys).size).toBe(2)
    expect(host.querySelectorAll<HTMLInputElement>('[data-row-key] input')[2]!.value).toBe('8')
    expect(mocks.formFill).toHaveBeenCalledTimes(1)
  })
  it('恢复的新增整单仍使用新增权限，可以提交且不误判为更新', async () => {
    const host = mount(undefined, undefined, {
      record: { id: null, revision: null, values: { name: '恢复草稿' } },
      details: {}
    })
    await flush()
    expect(button('保存记录'), host.textContent || '').toBeTruthy()
    button('保存记录').click()
    await vi.waitFor(() => expect(mocks.save).toHaveBeenCalledOnce())
    expect(mocks.save.mock.calls[0]![0].id).toBeNull()
    expect(mocks.save.mock.calls[0]![0].values.name).toBe('恢复草稿')
  })
  it('提交审批显示真实语义，待审批不触发业务保存成功事件', async () => {
    const host = mount(undefined, { create: { mode: 'APPROVAL', processDefinitionId: 'flow', variables: {} } })
    await flush()
    mocks.submit.mockResolvedValue({
      outcome: 'SUBMITTED',
      result: null,
      request: { id: 'request', status: 'PENDING' }
    })
    expect(host.textContent).toContain('审批前原数据保持不变')
    button('提交审批').click()
    await vi.waitFor(() => expect(mocks.cancel).toHaveBeenCalledOnce())
    expect(mocks.saved).not.toHaveBeenCalled()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(Object.values(localStorage).some(value => String(value).includes('requestKey'))).toBe(false)
  })
  it('提交响应丢失通过原键恢复审批申请，避免再建一条记录', async () => {
    const host = mount(undefined, { create: { mode: 'CONDITIONAL', variables: {} } })
    await flush()
    mocks.submit.mockRejectedValue(new TypeError('network'))
    mocks.submitReceipt.mockResolvedValue({
      outcome: 'SUBMITTED',
      result: null,
      request: { id: 'restored', status: 'PENDING' }
    })
    button('提交办理').click()
    await vi.waitFor(() => expect(mocks.cancel).toHaveBeenCalledOnce())
    expect(mocks.submitReceipt).toHaveBeenCalledWith('app', 'object', mocks.submit.mock.calls[0]![0].requestKey)
    expect(mocks.saved).not.toHaveBeenCalled()
    expect(host.textContent).not.toContain('暂时无法确认')
  })
  it('逐行条件必填阻止整单保存；修正第二行后保留第一行输入', async () => {
    const node = (id: string, behavior?: any) => ({
      id: 'node-' + id,
      type: 'FIELD',
      fieldId: id,
      children: [],
      presentation: { behavior }
    })
    const host = mount({
      objectId: 'object',
      nodes: [node('name')],
      detailIds: ['items'],
      detailNodes: { items: [node('amount', { requiredWhen: { op: 'VALUE', value: true, args: [] } })] }
    })
    await flush()
    button('添加明细').click()
    button('添加明细').click()
    await flush()
    const inputs = Array.from(host.querySelectorAll<HTMLInputElement>('[data-row-key] input'))
    expect(inputs).toHaveLength(2)
    inputs[0]!.value = '3.20'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[0]!.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    button('保存记录').click()
    await vi.waitFor(() => expect(host.textContent).toContain('请检查'))
    expect(mocks.save).not.toHaveBeenCalled()
    expect(inputs[0]!.value).toBe('3.20')
    inputs[1] = host.querySelectorAll<HTMLInputElement>('[data-row-key] input')[1]!
    inputs[1]!.value = '4.10'
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1]!.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    button('保存记录').click()
    await vi.waitFor(() => expect(mocks.save, host.textContent || '').toHaveBeenCalledTimes(1))
    expect(mocks.save.mock.calls[0]![0].details.items.map((r: any) => r.values.amount)).toEqual(['3.20', '4.10'])
  })
  it('错误不写入整单；修正、切换卡片后保持两行数值和稳定行身份', async () => {
    const host = mount()
    await flush()
    button('粘贴多行').click()
    await flush()
    const textarea = document.querySelector<HTMLTextAreaElement>('textarea')!
    textarea.value = '12.345'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('检查内容').click()
    await flush()
    expect(button('添加到整单').disabled).toBe(true)
    expect(host.querySelectorAll('[data-row-key]')).toHaveLength(0)
    expect(mocks.save).not.toHaveBeenCalled()
    textarea.value = '12.30\n9007199254740993.12'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('检查内容').click()
    await flush()
    button('添加到整单').click()
    await flush()
    const keys = Array.from(host.querySelectorAll<HTMLElement>('[data-row-key]')).map(row => row.dataset.rowKey)
    expect(keys).toHaveLength(2)
    expect(new Set(keys).size).toBe(2)
    const cards = Array.from(host.querySelectorAll('label')).find(label => label.textContent?.includes('卡片'))!
    cards.click()
    await flush()
    expect(Array.from(host.querySelectorAll<HTMLElement>('[data-row-key]')).map(row => row.dataset.rowKey)).toEqual(
      keys
    )
    button('保存记录').click()
    await flush()
    expect(mocks.save).toHaveBeenCalledTimes(1)
    expect(mocks.save.mock.calls[0]![0].details.items.map((row: any) => row.values.amount)).toEqual([
      '12.30',
      '9007199254740993.12'
    ])
    expect(mocks.save.mock.calls[0]![0].details.items.map((row: any) => row.clientRowKey)).toEqual(keys)
  })
})
