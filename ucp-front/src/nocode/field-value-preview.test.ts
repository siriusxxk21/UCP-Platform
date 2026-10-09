// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import FieldValueEditor from '@/views/nocode/components/FieldValueEditor.vue'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { FieldType } from '@/types/nocode/enums'

const mocks = vi.hoisted(() => ({ users: vi.fn(), organizations: vi.fn(), departments: vi.fn(), get: vi.fn() }))
vi.mock('@/api/system/user', () => ({ getUsersByIds: mocks.users }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: mocks.organizations }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: mocks.departments }))
vi.mock('@/utils/request', () => ({ default: { get: mocks.get } }))
vi.mock('@/components/UserSelectorTrigger.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { image: Boolean, modelValue: Array, disabled: Boolean },
      setup: p => () => h('div', { 'data-image': p.image, 'data-disabled': p.disabled }, p.modelValue?.join(','))
    })
  }
})

let app: App | undefined
let host: HTMLElement
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.resetAllMocks()
})
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function render(type: FieldType, value: string, options = defaultFieldOptions(), displayOnly = true) {
  const props = reactive({ field: { ...newField(0), type }, options, modelValue: value, displayOnly })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(FieldValueEditor, props) })
  for (const name of ['AInputNumber', 'ATextarea', 'AInput'])
    app.component(name, defineComponent({ setup: () => () => h('input') }))
  for (const name of ['ADatePicker', 'ATimePicker'])
    app.component(
      name,
      defineComponent({ props: ['value'], setup: p => () => h('input', { 'data-picker': '', value: p.value }) })
    )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['options'],
      setup: p => () =>
        h(
          'select',
          p.options?.map((o: { value: string; label: string }) => h('option', { value: o.value }, o.label))
        )
    })
  )
  app.mount(host)
  await flush()
  return props
}

describe('字段配置列表预览沿用真实值展示', () => {
  it('旧无效日期不会传给日历，明确保留原值并允许重新选择', async () => {
    const props = await render(FieldType.DATE, '明天', defaultFieldOptions(), false)
    expect((host.querySelector('[data-picker]') as HTMLInputElement).value).toBe('')
    expect(host.textContent).toContain('原值“明天”无效，请重新选择')
    expect(host.querySelector('button')?.textContent).toBe('清空无效值')
    props.modelValue = '2026-09-13'
    await flush()
    expect((host.querySelector('[data-picker]') as HTMLInputElement).value).toBe('2026-09-13')
    expect(host.textContent).not.toContain('无效')
  })
  it('选择字段显示名称，富文本安全保留基本排版，零与否不变为空值', async () => {
    const props = await render(FieldType.MULTI_SELECT, '["a","b"]', {
      ...defaultFieldOptions(),
      options: [
        { code: 'a', label: '甲', disabled: false },
        { code: 'b', label: '乙', disabled: false }
      ]
    })
    expect(host.textContent).toBe('甲、乙')
    expect(host.querySelector('select,input')).toBeNull()
    props.field.type = FieldType.RICH_TEXT
    props.modelValue = '<p><strong>采购</strong><em>说明</em></p><p>下一行</p><script>alert(1)</script>'
    await flush()
    expect(host.querySelector('strong')?.textContent).toBe('采购')
    expect(host.querySelector('em')?.textContent).toBe('说明')
    expect(host.querySelectorAll('p')).toHaveLength(2)
    expect(host.querySelector('script')).toBeNull()
    props.field.type = FieldType.INTEGER
    props.modelValue = '0'
    await flush()
    expect(host.textContent).toBe('0')
    props.field.type = FieldType.BOOLEAN
    props.modelValue = 'false'
    await flush()
    expect(host.textContent).toBe('否')
  })
  it('链接使用可访问文字，图片和附件使用同一只读文件组件', async () => {
    const props = await render(FieldType.URL, '{"link":"https://example.com/purchase","text":"采购规范"}')
    expect(host.querySelector('a')?.textContent).toBe('采购规范')
    expect(host.querySelector('a')?.href).toBe('https://example.com/purchase')
    expect(host.querySelector('input')).toBeNull()
    props.field.type = FieldType.IMAGE
    props.modelValue = '["101"]'
    await flush()
    expect(host.querySelector('[data-image="true"][data-disabled="true"]')).not.toBeNull()
    props.field.type = FieldType.ATTACHMENT
    await flush()
    expect(host.querySelector('[data-image="false"][data-disabled="true"]')).not.toBeNull()
  })
  it('人员读取真实名称，值改变后重新解析；旧响应不覆盖当前预览', async () => {
    let first: ((value: unknown) => void) | undefined
    mocks.users
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            first = resolve
          })
      )
      .mockResolvedValue([{ id: '2', nickname: '李采购' }])
    const props = await render(FieldType.USER, '1')
    props.modelValue = '2'
    await flush()
    expect(host.textContent).toBe('李采购')
    first?.([{ id: '1', nickname: '旧选择' }])
    await flush()
    expect(host.textContent).toBe('李采购')
    expect(mocks.users).toHaveBeenLastCalledWith(['2'])
  })
  it('组织字段即使没有显式来源配置也使用组织候选控件', async () => {
    mocks.organizations.mockResolvedValue([{ id: '1', orgName: '总部', status: 0, orgType: 'COMPANY', children: [] }])
    await render(FieldType.ORGANIZATION, '1', defaultFieldOptions(), false)
    expect(host.querySelector('select')?.textContent).toBe('总部')
    expect(mocks.organizations).toHaveBeenCalledOnce()
  })
})
