// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { TableModel } from '@/types/nocode/runtime'
import { FieldType } from '@/types/nocode/enums'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { FORMULA_LOCKED_TEXT, LINKAGE_LOCKED_TEXT } from './business-field-rules'

vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn(async () => ({})) } }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: vi.fn(async () => []) }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: vi.fn(async () => []) }))
import RecordForm from '@/views/nocode/application/components/RecordForm.vue'

/**
 * 业务方 2026-09-29 裁定：公式默认值与只读联动的字段一律只读。
 * 编辑已有记录打开时不求值，按运行模型投影 rules.readOnly / rules.linkage.readOnly 直接锁定（主表与明细行一致）；
 * 显式关掉只读的联动仍可手改。
 */
const apps: App[] = []
const field = (id: string, name: string): ObjectField => ({
  ...newField(0, name),
  id,
  key: id,
  code: id,
  type: FieldType.TEXT
})
const options = (rules?: Record<string, unknown>): FieldOptions =>
  ({ ...defaultFieldOptions(), ...(rules ? { rules } : {}) }) as FieldOptions
const model: TableModel = { writable: true, generatedKey: true, keyFieldId: null, keyType: 'bigint' }
const fields = [field('total', '合计'), field('bank', '开户行'), field('memo', '备注'), field('name', '名称')]
const fieldOptions = {
  total: options({ dependsOn: ['name'], readOnly: true }),
  bank: options({ dependsOn: ['name'], linkage: { readOnly: true } }),
  memo: options({ dependsOn: ['name'], linkage: { readOnly: false } }),
  name: options()
}

async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount(props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.append(host)
  const value = ref<Record<string, unknown>>({ total: '100', bank: '招商', memo: '说明', name: '甲' })
  const app = createApp({
    setup: () => () =>
      h(RecordForm, {
        fields,
        options: fieldOptions,
        model,
        creating: false,
        ruleStates: {},
        modelValue: value.value,
        'onUpdate:modelValue': (next: Record<string, unknown>) => (value.value = next),
        ...props
      })
  })
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  return host
}
function item(host: HTMLElement, label: string) {
  const found = Array.from(host.querySelectorAll<HTMLElement>('.ant-form-item')).find(node =>
    node.querySelector('.ant-form-item-label')?.textContent?.includes(label)
  )
  if (!found) throw new Error('缺少表单项：' + label)
  return found
}
const input = (node: HTMLElement) => node.querySelector<HTMLInputElement>('input')!

beforeAll(() => {
  window.matchMedia = vi.fn().mockReturnValue({ matches: false, addListener() {}, removeListener() {} })
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

// 第一期契约 9.4：开了自动更新的只读联动字段，标签旁加标识，提示文字「系统自动更新」；只读行为不变。
describe('系统自动更新的字段：标签旁有标识', () => {
  const mark = (node: HTMLElement) => node.querySelector('.ant-form-item-label .nocode-auto-update-mark')
  it('只给开了自动更新的字段加标识；只读联动没开的、公式默认值、普通字段都没有', async () => {
    const host = mount({
      options: {
        ...fieldOptions,
        bank: options({ dependsOn: ['name'], readOnly: true, linkage: { readOnly: true, autoUpdate: true } })
      }
    })
    await flush()
    const bank = item(host, '开户行')
    expect(mark(bank)?.getAttribute('aria-label')).toBe('系统自动更新')
    expect(input(bank).disabled).toBe(true)
    expect(bank.textContent).toContain(LINKAGE_LOCKED_TEXT)
    for (const label of ['合计', '备注', '名称']) expect(mark(item(host, label))).toBeNull()
  })
  it('存量只读联动（投影里没有 autoUpdate 键）没有标识', async () => {
    const host = mount({})
    await flush()
    expect(host.querySelector('.nocode-auto-update-mark')).toBeNull()
  })
})

describe('编辑已有记录打开：只读规则字段直接锁定', () => {
  it('主表：公式默认值与只读联动禁用并注明来源，非只读联动与普通字段可填', async () => {
    const host = mount({})
    await flush()
    expect(input(item(host, '合计')).disabled).toBe(true)
    expect(item(host, '合计').textContent).toContain(FORMULA_LOCKED_TEXT)
    expect(input(item(host, '开户行')).disabled).toBe(true)
    expect(item(host, '开户行').textContent).toContain(LINKAGE_LOCKED_TEXT)
    expect(input(item(host, '备注')).disabled).toBe(false)
    expect(input(item(host, '名称')).disabled).toBe(false)
  })

  it('明细行（表格模式）：同样按投影锁定', async () => {
    const host = mount({ detailId: 'lines', detailRecordId: 'line-1', compact: true })
    await flush()
    expect(input(item(host, '合计')).disabled).toBe(true)
    expect(input(item(host, '开户行')).disabled).toBe(true)
    expect(input(item(host, '备注')).disabled).toBe(false)
  })
})
