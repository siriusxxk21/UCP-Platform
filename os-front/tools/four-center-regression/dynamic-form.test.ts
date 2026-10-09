import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
vi.mock('@/views/nocode/application/components/BusinessFieldControl.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/HyperlinkField.vue', () => ({ default: { render: () => null } }))
import RecordForm from '@/views/nocode/application/components/RecordForm.vue'

const apps: App[] = []
beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(() => ({ matches: false, addListener() {}, removeListener() {} }))
  Element.prototype.scrollIntoView = vi.fn()
  if (!globalThis.CSS) vi.stubGlobal('CSS', { escape: (text: string) => text })
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
function mount(clearWhenHidden = false, writable = true) {
  const values = ref<Record<string, unknown>>({ type: '采购', note: '已输入说明' })
  const form = ref<InstanceType<typeof RecordForm>>()
  const fields = ['type', 'note'].map(id => ({
    id,
    code: id,
    name: id === 'type' ? '类型' : '说明',
    type: 'TEXT' as const
  }))
  const nodes = [
    uiNode(NodeKind.FIELD, { fieldId: 'type' }),
    uiNode(NodeKind.FIELD, {
      fieldId: 'note',
      presentation: {
        behavior: {
          showWhen: {
            op: 'EQ',
            args: [
              { op: 'FIELD', fieldId: 'type', args: [] },
              { op: 'VALUE', value: '采购', args: [] }
            ]
          },
          requiredWhen: { op: 'VALUE', value: true, args: [] },
          clearWhenHidden
        }
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
        nodes,
        options: {},
        creating: true,
        model: { writable, generatedKey: true, keyFieldId: null, keyType: 'bigint' },
        modelValue: values.value,
        'onUpdate:modelValue': v => {
          values.value = v
        }
      })
  })
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  return { values, form, host }
}
describe('真实表单引擎的动态条件', () => {
  it('条件切换隐藏控件，保留输入，再显示时恢复输入', async () => {
    const { values, host, form } = mount()
    await flush()
    expect(host.querySelectorAll('input')).toHaveLength(2)
    values.value.type = '其他'
    await flush()
    expect(values.value.note).toBe('已输入说明')
    await expect(form.value!.validate()).resolves.toBeUndefined()
    values.value.type = '采购'
    await flush()
    expect(values.value.note).toBe('已输入说明')
    expect([...host.querySelectorAll('input')].some(input => input.value === '已输入说明')).toBe(true)
  })
  it('显式清空只作用于可写表单，隐藏后不再阻塞必填校验', async () => {
    const { values, form } = mount(true)
    await flush()
    values.value.type = '其他'
    await flush()
    expect(values.value.note).toBeNull()
    await expect(form.value!.validate()).resolves.toBeUndefined()
    values.value.type = '采购'
    await flush()
    await expect(form.value!.validate()).rejects.toThrow('说明')
  })
  it('只读预览不会因隐藏清空而修改业务输入', async () => {
    const { values } = mount(true, false)
    await flush()
    values.value.type = '其他'
    await flush()
    expect(values.value.note).toBe('已输入说明')
  })
})
