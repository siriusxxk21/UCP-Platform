import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import { nocodePlatformKey } from '@/nocode/platform'
import { selectionPreviewKey } from '@/nocode/selection'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
import RecordForm from '@/views/nocode/application/components/RecordForm.vue'
const apps: App[] = []
beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(() => ({ matches: false, addListener() {}, removeListener() {} }))
  Element.prototype.scrollIntoView = vi.fn()
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})
const flush = async () => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 30))
  await nextTick()
}
describe('公共表单的真实关联带入', () => {
  it('单行失败不会被另一来源成功掩盖，必须重试成功或显式接管才可保存', async () => {
    const values = ref<Record<string, unknown>>({ customer: null, address: '旧地址', employee: null, phone: null })
    const form = ref<InstanceType<typeof RecordForm>>()
    const formFill = vi
      .fn()
      .mockImplementation((query: { sourceFieldId: string }) =>
        query.sourceFieldId === 'customer'
          ? Promise.reject(new Error('网络中断'))
          : Promise.resolve({ phone: '新号码' })
      )
    const nodes = Object.keys(values.value).map(id => uiNode(NodeKind.FIELD, { fieldId: id }))
    nodes[1]!.presentation = {
      fill: { sourceFieldId: 'customer', valueFieldId: 'address', mode: 'SOURCE_CHANGE', clearOnSourceEmpty: true }
    }
    nodes[3]!.presentation = { fill: { sourceFieldId: 'employee', valueFieldId: 'phone', mode: 'SOURCE_CHANGE' } }
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      render: () =>
        h(RecordForm, {
          ref: form,
          fields: Object.keys(values.value).map(id => ({ id, code: id, name: id, type: 'TEXT' as const })),
          options: {},
          nodes,
          creating: true,
          applicationId: 'app',
          objectId: 'object',
          detailId: 'lines',
          formId: 'form',
          model: { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' },
          modelValue: values.value,
          'onUpdate:modelValue': v => {
            values.value = v
          }
        })
    })
    app.use(Antd)
    app.provide(nocodePlatformKey, { runtime: { formFill } } as any)
    app.mount(host)
    apps.push(app)
    await flush()
    values.value.customer = 'C1'
    await flush()
    values.value.employee = 'E1'
    await flush()
    expect(values.value.phone).toBe('新号码')
    expect(values.value.address).toBe('旧地址')
    await expect(form.value!.validate()).rejects.toThrow('关联带入失败')
    expect(host.textContent).toContain('网络中断')
    formFill.mockResolvedValue({ address: '新地址' })
    ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('重试带入'))!.click()
    await flush()
    expect(values.value.address).toBe('新地址')
    await expect(form.value!.validate()).resolves.toBeUndefined()
    formFill.mockRejectedValue(new Error('再次中断'))
    values.value.customer = 'C2'
    await flush()
    values.value.address = '人工核对地址'
    ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('已核对'))!.click()
    await flush()
    await expect(form.value!.validate()).resolves.toBeUndefined()
    expect(values.value.address).toBe('人工核对地址')
    values.value.customer = null
    await flush()
    expect(values.value.address).toBeNull()
    expect(formFill.mock.calls[0]![0].detailId).toBe('lines')
  })
  it('选择触发带入，等待时阻止提前保存，手动输入得到保留', async () => {
    let resolve!: (result: Record<string, unknown>) => void
    const formFill = vi.fn().mockImplementation(
      () =>
        new Promise(r => {
          resolve = r
        })
    )
    const values = ref<Record<string, unknown>>({ material: null, price: null }),
      form = ref<InstanceType<typeof RecordForm>>()
    const fields = ['material', 'price'].map(id => ({ id, code: id, name: id, type: 'TEXT' as const }))
    const nodes = [
      uiNode(NodeKind.FIELD, { fieldId: 'material' }),
      uiNode(NodeKind.FIELD, {
        fieldId: 'price',
        presentation: {
          fill: { sourceFieldId: 'material', valueFieldId: 'source-price', mode: 'SOURCE_CHANGE' }
        }
      })
    ]
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      render: () =>
        h(RecordForm, {
          ref: form,
          fields,
          options: {},
          nodes,
          creating: true,
          applicationId: 'app',
          objectId: 'object',
          formId: 'form',
          model: { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' },
          modelValue: values.value,
          'onUpdate:modelValue': v => {
            values.value = v
          }
        })
    })
    app.use(Antd)
    app.provide(nocodePlatformKey, { runtime: { formFill } } as any)
    app.mount(host)
    apps.push(app)
    await flush()
    expect(formFill).not.toHaveBeenCalled()
    values.value.material = 'A'
    await flush()
    expect(formFill).toHaveBeenCalledWith({
      applicationId: 'app',
      objectId: 'object',
      formId: 'form',
      sourceFieldId: 'material',
      selectedId: 'A'
    })
    await expect(form.value!.validate()).rejects.toThrow('正在带入')
    values.value.price = '人工价格'
    resolve({ price: '自动价格' })
    await flush()
    expect(values.value.price).toBe('人工价格')
    await expect(form.value!.validate()).resolves.toBeUndefined()
    values.value.material = 'B'
    await flush()
    resolve({ price: 'B 的价格' })
    await flush()
    expect(values.value.price).toBe('B 的价格')
  })
  it('单向链式带入逐跳写入，第二跳由第一跳的结果触发', async () => {
    const calls: string[] = []
    const formFill = vi.fn().mockImplementation((query: { sourceFieldId: string }) => {
      calls.push(query.sourceFieldId)
      return Promise.resolve(query.sourceFieldId === 'material' ? { account: 'ACC-1' } : { name: '账户甲' })
    })
    const values = ref<Record<string, unknown>>({ material: null, account: null, name: null }),
      form = ref<InstanceType<typeof RecordForm>>()
    const fields = ['material', 'account', 'name'].map(id => ({ id, code: id, name: id, type: 'TEXT' as const }))
    const nodes = [
      uiNode(NodeKind.FIELD, { fieldId: 'material' }),
      uiNode(NodeKind.FIELD, {
        fieldId: 'account',
        presentation: { fill: { sourceFieldId: 'material', valueFieldId: 'source-account', mode: 'SOURCE_CHANGE' } }
      }),
      uiNode(NodeKind.FIELD, {
        fieldId: 'name',
        presentation: { fill: { sourceFieldId: 'account', valueFieldId: 'source-name', mode: 'SOURCE_CHANGE' } }
      })
    ]
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      render: () =>
        h(RecordForm, {
          ref: form,
          fields,
          options: {},
          nodes,
          creating: true,
          applicationId: 'app',
          objectId: 'object',
          formId: 'form',
          model: { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' },
          modelValue: values.value,
          'onUpdate:modelValue': v => {
            values.value = v
          }
        })
    })
    app.use(Antd)
    app.provide(nocodePlatformKey, { runtime: { formFill } } as any)
    app.mount(host)
    apps.push(app)
    await flush()
    values.value.material = 'S1'
    await flush()
    await flush()
    expect(values.value.account).toBe('ACC-1')
    expect(values.value.name).toBe('账户甲')
    expect(calls).toEqual(['material', 'account'])
  })
  it('设计预览无已发布表单时使用当前草稿上下文带入', async () => {
    const previewFormFill = vi.fn().mockResolvedValue({ price: '草稿预览价格' })
    const values = ref<Record<string, unknown>>({ material: null, price: null })
    const fields = ['material', 'price'].map(id => ({ id, code: id, name: id, type: 'TEXT' as const }))
    const nodes = [
      uiNode(NodeKind.FIELD, { fieldId: 'material' }),
      uiNode(NodeKind.FIELD, {
        fieldId: 'price',
        presentation: {
          fill: { sourceFieldId: 'material', valueFieldId: 'source-price', mode: 'SOURCE_CHANGE' }
        }
      })
    ]
    const objects = [{ objectId: 'object', versionNo: 3, checksum: 'draft-v3' }]
    const form = {
      objectId: 'object',
      nodes,
      detailIds: [],
      options: { layout: 'vertical' as const, submitText: '保存' }
    }
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      render: () =>
        h(RecordForm, {
          fields,
          options: {},
          nodes,
          creating: true,
          applicationId: 'app',
          objectId: 'object',
          preview: true,
          model: { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' },
          modelValue: values.value,
          'onUpdate:modelValue': v => {
            values.value = v
          }
        })
    })
    app.use(Antd)
    app.provide(nocodePlatformKey, { applications: { previewFormFill }, runtime: { formFill: vi.fn() } } as any)
    app.provide(selectionPreviewKey, ref({ applicationId: 'app', objects, form }))
    app.mount(host)
    apps.push(app)
    await flush()
    values.value.material = 'M-1'
    await flush()
    expect(previewFormFill).toHaveBeenCalledWith({
      objects,
      form,
      query: {
        applicationId: 'app',
        objectId: 'object',
        sourceFieldId: 'material',
        selectedId: 'M-1',
        detailId: undefined,
        recordId: undefined
      }
    })
    expect(values.value.price).toBe('草稿预览价格')
  })
})
