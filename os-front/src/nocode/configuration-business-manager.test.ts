// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App, type PropType } from 'vue'
import BusinessConfigManager from '@/views/nocode/application/components/BusinessConfigManager.vue'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { BusinessActionKind } from '@/types/nocode/business'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { AutomationConfig } from '@/types/nocode/automation'

interface AutomationEditorProps {
  modelValue: AutomationConfig
  objects: Record<string, PublishedObject>
  applicationId?: string
  readOnly: boolean
}

function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('测试所需的组件或数据未就绪')
  return value
}

const calls = vi.hoisted(() => ({
  get: vi.fn(),
  validate: vi.fn(),
  confirmDiscard: vi.fn(),
  guard: undefined as undefined | (() => boolean),
  recordProps: undefined as any,
  automationProps: undefined as AutomationEditorProps | undefined,
  preview: undefined as any
}))
vi.mock('@/utils/request', () => ({ default: { get: calls.get } }))
vi.mock('@/api/bpm/definition', () => ({ getProcessDefinition: vi.fn() }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/nocode/unsaved', () => ({
  confirmDiscard: calls.confirmDiscard,
  useUnsavedNavigation: (guard: () => boolean) => {
    calls.guard = guard
  }
}))
vi.mock('@/views/nocode/application/components/RecordForm.vue', async () => {
  const { defineComponent, h, inject } = await import('vue')
  const { selectionPreviewKey } = await import('@/nocode/selection')
  return {
    default: defineComponent({
      props: {
        modelValue: null,
        fields: null,
        options: null,
        model: null,
        creating: Boolean,
        applicationId: String,
        objectId: String,
        relations: null,
        preview: Boolean
      },
      emits: ['update:modelValue'],
      setup(props, { emit, expose }) {
        calls.recordProps = props
        calls.preview = inject(selectionPreviewKey)
        expose({ validate: calls.validate })
        return () =>
          h(
            'button',
            {
              disabled: props.model?.writable === false,
              onClick: () => emit('update:modelValue', { ...props.modelValue, tags: ['alpha', 'beta'] })
            },
            '选择多选动作值'
          )
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/AutomationConfigEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: {
        modelValue: { type: Object as PropType<AutomationConfig>, required: true },
        objects: { type: Object as PropType<Record<string, PublishedObject>>, required: true },
        applicationId: String,
        readOnly: Boolean
      },
      emits: ['update:modelValue'],
      setup(props, { emit }) {
        calls.automationProps = props
        return () =>
          h('div', [
            h(
              'button',
              {
                disabled: props.readOnly,
                onClick: () =>
                  emit('update:modelValue', {
                    ...props.modelValue,
                    objectId: 'orders',
                    targetObjectId: 'customers',
                    enabled: false,
                    binding: { relationId: 'customer-relation', direction: 'OUTGOING' },
                    assignments: [
                      { fieldId: 'hasOrders', kind: 'EXISTS', sourceFieldId: null, value: true, emptyValue: false }
                    ]
                  })
              },
              '填写自动更新配置'
            ),
            h('span', { 'data-automation-value': true }, JSON.stringify(props.modelValue))
          ])
      }
    })
  }
})
vi.mock('@/components/os-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource', 'columns'],
      setup(props, { slots }) {
        // 表格列设置在挂载时建立；切换不同列结构的分段必须重新建立表格会话。
        const columns = props.columns
        return () =>
          h('div', [
            slots.actions?.(),
            ...props.dataSource.map((record: ApplicationResource) =>
              h('div', { 'data-resource': record.id }, [
                h('span', record.name),
                ...columns.map((column: { key: string }) =>
                  h('div', { 'data-column': column.key }, slots.bodyCell?.({ column, record }))
                )
              ])
            )
          ])
      }
    })
  }
})

const relation = {
  id: 'customer-relation',
  fieldId: 'customer',
  kind: RelationType.REFERENCE,
  targetObjectId: 'customers'
}
const objects = {
  orders: {
    objectId: 'orders',
    versionNo: 4,
    checksum: 'orders-v4',
    definition: {
      objectId: 'orders',
      objectName: '订单',
      fields: [
        { id: 'tags', name: '标签', type: FieldType.MULTI_SELECT },
        { id: 'customer', name: '客户', type: FieldType.INTEGER }
      ],
      fieldOptions: {
        tags: {
          options: [
            { code: 'alpha', label: '甲' },
            { code: 'beta', label: '乙' }
          ]
        },
        customer: { generated: true }
      },
      relations: [relation],
      details: []
    }
  },
  customers: {
    objectId: 'customers',
    versionNo: 2,
    checksum: 'customers-v2',
    definition: {
      objectId: 'customers',
      objectName: '客户',
      fields: [{ id: 'hasOrders', name: '有订单', type: FieldType.BOOLEAN }],
      fieldOptions: {},
      relations: [],
      details: []
    }
  }
} as unknown as Record<string, PublishedObject>
const actionResource = (): ApplicationResource => ({
  id: 'action',
  kind: ResourceKind.ACTION,
  code: 'set_tags',
  name: '更新标签',
  config: {
    objectId: 'orders',
    kind: BusinessActionKind.UPDATE_FIELDS,
    values: { tags: ['alpha'], customer: '7' },
    variables: {},
    processDefinitionId: null
  }
})
const automationResource = (): ApplicationResource => ({
  id: 'automation',
  kind: ResourceKind.AUTOMATION,
  code: 'has_orders',
  name: '维护客户成交状态',
  config: {
    objectId: 'orders',
    targetObjectId: 'customers',
    enabled: true,
    mode: 'MAINTAIN',
    events: ['CREATE', 'UPDATE', 'DELETE'],
    conditions: null,
    binding: { relationId: 'customer-relation', direction: 'OUTGOING' },
    assignments: [{ fieldId: 'hasOrders', kind: 'EXISTS', sourceFieldId: null, value: true, emptyValue: false }]
  }
})
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  Array.from(host.querySelectorAll('button')).find(el => el.textContent?.trim() === label)!
async function mount(readOnly = false, entries = [actionResource()], openFirst = true) {
  const state = reactive({ resources: entries })
  const changed = vi.fn()
  app = createApp(() =>
    h(BusinessConfigManager, {
      modelValue: state.resources,
      objects,
      applicationId: 'app-orders',
      readOnly,
      'onUpdate:modelValue': (value: ApplicationResource[]) => {
        state.resources = value
      },
      onChange: changed
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AForm', 'ARow', 'ACol', 'ASpace', 'APopconfirm', 'ACheckbox', 'AInputNumber', 'ATag'])
    app.component(name, plain)
  app.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (p, { slots }) =>
        () =>
          h('label', { 'data-label': p.label }, slots.default?.())
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  app.component('AAlert', defineComponent({ props: ['message'], setup: p => () => h('p', p.message) }))
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            value: p.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'mode', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              value: p.value,
              multiple: p.mode === 'multiple',
              disabled: p.disabled,
              onChange: (event: Event) => {
                const select = event.target as HTMLSelectElement
                const value =
                  p.mode === 'multiple' ? Array.from(select.selectedOptions).map(option => option.value) : select.value
                emit('update:value', value)
                emit('change', value)
              }
            },
            (p.options || []).map((option: { label: string; value: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'div',
            { 'data-execution': p.value },
            p.options.map((option: { label: string; value: string }) =>
              h('button', { disabled: p.disabled, onClick: () => emit('update:value', option.value) }, option.label)
            )
          )
    })
  )
  app.component(
    'ASegmented',
    defineComponent({
      props: ['options', 'value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'div',
            { 'data-sections': true },
            p.options.map((option: { label: string; value: string }) =>
              h('button', { onClick: () => emit('update:value', option.value) }, option.label)
            )
          )
    })
  )
  app.component(
    'AModal',
    defineComponent({
      props: ['open', 'confirmLoading', 'closable', 'footer'],
      emits: ['cancel', 'ok'],
      setup:
        (p, { slots, emit }) =>
        () =>
          p.open
            ? h('section', { 'data-modal': true }, [
                slots.default?.(),
                h('button', { disabled: p.closable === false, onClick: () => emit('cancel') }, '关闭配置'),
                p.footer === null
                  ? null
                  : h('button', { disabled: p.confirmLoading, onClick: () => emit('ok') }, '应用到草稿')
              ])
            : null
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  button('业务动作').click()
  await flush()
  if (openFirst) {
    button(readOnly ? '查看' : '配置').click()
    await flush()
  }
  return { state, changed }
}
async function rename(value: string) {
  const input = host.querySelector<HTMLInputElement>('[data-label="名称"] input')!
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flush()
}
async function editRow(id: string, readOnly = false) {
  const row = required(host.querySelector(`[data-resource="${id}"]`))
  required(
    Array.from(row.querySelectorAll('button')).find(item => item.textContent?.trim() === (readOnly ? '查看' : '配置'))
  ).click()
  await flush()
}
async function selectFields(ids: string[]) {
  const select = required(host.querySelector<HTMLSelectElement>('[data-label="更新字段"] select'))
  for (const option of Array.from(select.options)) option.selected = ids.includes(option.value)
  select.dispatchEvent(new Event('change'))
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.get.mockResolvedValue([])
  calls.validate.mockResolvedValue(undefined)
  calls.confirmDiscard.mockResolvedValue(true)
  calls.guard = undefined
  calls.recordProps = undefined
  calls.automationProps = undefined
  calls.preview = undefined
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('业务配置本地编辑会话', () => {
  it('自动执行的动作可上移以调整执行先后（按日期：同日退房排在入住之后）', async () => {
    const dated = (id: string, name: string): ApplicationResource => ({
      ...automationResource(),
      id,
      code: id,
      name,
      config: { ...automationResource().config, mode: 'DATE', events: [], dateFieldId: null, offsetDays: 0 }
    })
    const { state, changed } = await mount(
      false,
      [actionResource(), dated('checkout', '退房日'), dated('checkin', '入住日')],
      false
    )
    const row = required(host.querySelector('[data-resource="checkin"]'))
    expect(row.textContent).toContain('按日期自动执行')
    expect(host.querySelector('[data-resource="checkout"]')?.textContent).not.toContain('上移')
    expect(host.querySelector('[data-resource="action"]')?.textContent).not.toContain('上移')
    required(Array.from(row.querySelectorAll('button')).find(item => item.textContent?.trim() === '上移')).click()
    await flush()
    expect(state.resources.map(r => r.id)).toEqual(['action', 'checkin', 'checkout'])
    expect(changed).toHaveBeenCalled()
  })
  it('业务动作同表展示手动和自动执行，自动更新不再占用顶部分段', async () => {
    await mount(false, [actionResource(), automationResource()], false)
    const sections = required(host.querySelector('[data-sections]'))
    expect(Array.from(sections.querySelectorAll('button')).map(item => item.textContent)).toEqual([
      '应用字典',
      '业务编号',
      '业务动作'
    ])
    expect(host.querySelector('[data-resource="action"]')?.textContent).toContain('手动执行')
    expect(host.querySelector('[data-resource="action"]')?.textContent).toContain('更新当前记录字段')
    expect(host.querySelector('[data-resource="automation"]')?.textContent).toContain('数据变化后自动执行')
    expect(host.querySelector('[data-resource="automation"]')?.textContent).toContain('更新关联数据 · 持续维护')
    expect(host.querySelector('[data-resource="automation"]')?.textContent).toContain('已启用')
    expect(button('新增业务动作')).toBeDefined()
  })
  it('已有自动动作继续按原协议编辑保存，不污染手动动作或原始资源', async () => {
    const { state, changed } = await mount(false, [actionResource(), automationResource()], false)
    const original = required(state.resources[1]),
      manual = JSON.stringify(state.resources[0])
    await editRow('automation')
    expect(host.querySelector('[data-execution]')?.getAttribute('data-execution')).toBe(ResourceKind.AUTOMATION)
    expect(button('手动执行').disabled).toBe(true)
    button('填写自动更新配置').click()
    await rename('客户成交情况')
    button('应用到草稿').click()
    await flush()
    expect(state.resources[1]).toMatchObject({
      id: 'automation',
      kind: ResourceKind.AUTOMATION,
      code: 'has_orders',
      name: '客户成交情况',
      config: { enabled: false }
    })
    expect(required(state.resources[1]).config).not.toHaveProperty('values')
    expect(original.config.enabled).toBe(true)
    expect(JSON.stringify(state.resources[0])).toBe(manual)
    expect(calls.validate).not.toHaveBeenCalled()
    expect(changed).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-resource="automation"]')?.textContent).toContain('已停用')
  })
  it('已有手动动作执行方式固定，编辑后保留 ACTION 身份和字段值协议', async () => {
    const { state } = await mount(false, [actionResource(), automationResource()])
    expect(button('数据变化后自动执行').disabled).toBe(true)
    button('数据变化后自动执行').click()
    await flush()
    expect(host.querySelector('[data-execution]')?.getAttribute('data-execution')).toBe(ResourceKind.ACTION)
    button('选择多选动作值').click()
    button('应用到草稿').click()
    await flush()
    expect(state.resources[0]).toMatchObject({
      id: 'action',
      kind: ResourceKind.ACTION,
      config: { values: { tags: ['alpha', 'beta'], customer: '7' } }
    })
    expect(required(state.resources[0]).config).not.toHaveProperty('assignments')
    expect(state.resources[1]).toMatchObject(automationResource())
  })
  it('统一新增入口默认手动执行，可按旧字段赋值协议新增', async () => {
    const { state, changed } = await mount(false, [], false)
    button('新增业务动作').click()
    await flush()
    await rename('设置订单标签')
    await selectFields(['tags'])
    button('选择多选动作值').click()
    button('应用到草稿').click()
    await flush()
    expect(state.resources).toHaveLength(1)
    expect(state.resources[0]).toMatchObject({
      kind: ResourceKind.ACTION,
      name: '设置订单标签',
      config: { objectId: 'orders', kind: BusinessActionKind.UPDATE_FIELDS, values: { tags: ['alpha', 'beta'] } }
    })
    expect(required(state.resources[0]).config).not.toHaveProperty('events')
    expect(changed).toHaveBeenCalledOnce()
  })
  it('手动发起流程继续保留流程版本与变量映射，不进入自动更新配置', async () => {
    const process = actionResource()
    process.config = {
      objectId: 'orders',
      kind: BusinessActionKind.START_PROCESS,
      values: {},
      processDefinitionId: 'process-v2',
      variables: { nc_customer: 'customer' }
    }
    const { state } = await mount(false, [process])
    expect(host.querySelector('[data-resource="action"]')?.textContent).toContain('发起流程')
    await rename('发起订单流程')
    button('应用到草稿').click()
    await flush()
    expect(state.resources[0]).toMatchObject({
      kind: ResourceKind.ACTION,
      name: '发起订单流程',
      config: process.config
    })
    expect(calls.validate).not.toHaveBeenCalled()
    expect(calls.automationProps).toBeUndefined()
  })
  it('统一新增入口可改为自动执行，提交时不会夹带手动字段值', async () => {
    const { state } = await mount(false, [actionResource()], false)
    const original = JSON.stringify(state.resources[0])
    button('新增业务动作').click()
    await flush()
    await selectFields(['tags'])
    button('选择多选动作值').click()
    button('数据变化后自动执行').click()
    await flush()
    button('填写自动更新配置').click()
    await rename('维护客户是否有订单')
    button('应用到草稿').click()
    await flush()
    expect(state.resources).toHaveLength(2)
    expect(state.resources[1]).toMatchObject({
      kind: ResourceKind.AUTOMATION,
      config: {
        objectId: 'orders',
        targetObjectId: 'customers',
        assignments: [{ fieldId: 'hasOrders', value: true, emptyValue: false }]
      }
    })
    expect(required(state.resources[1]).config).not.toHaveProperty('values')
    expect(required(state.resources[1]).config).not.toHaveProperty('processDefinitionId')
    expect(JSON.stringify(state.resources[0])).toBe(original)
    expect(calls.validate).not.toHaveBeenCalled()
  })
  it('新建时来回切换分别保留输入，取消后下一次新增不继承上次配置', async () => {
    const { state, changed } = await mount(false, [], false)
    button('新增业务动作').click()
    await flush()
    await selectFields(['tags'])
    button('选择多选动作值').click()
    button('数据变化后自动执行').click()
    await flush()
    button('填写自动更新配置').click()
    await flush()
    button('手动执行').click()
    await flush()
    expect(calls.recordProps.modelValue).toEqual({ tags: ['alpha', 'beta'] })
    expect(calls.recordProps.fields.map((f: { id: string }) => f.id)).toEqual(['tags'])
    button('数据变化后自动执行').click()
    await flush()
    expect(required(calls.automationProps).modelValue).toMatchObject({
      enabled: false,
      targetObjectId: 'customers',
      assignments: [{ fieldId: 'hasOrders' }]
    })
    button('关闭配置').click()
    await flush()
    expect(state.resources).toEqual([])
    expect(changed).not.toHaveBeenCalled()
    button('新增业务动作').click()
    await flush()
    button('数据变化后自动执行').click()
    await flush()
    expect(required(calls.automationProps).modelValue).toMatchObject({
      enabled: true,
      targetObjectId: '',
      assignments: []
    })
  })
  it('只读成员在统一列表查看两类动作，不能新增、切换或应用修改', async () => {
    const { state, changed } = await mount(true, [actionResource(), automationResource()], false)
    const before = JSON.stringify(state.resources)
    expect(button('新增业务动作')).toBeUndefined()
    await editRow('automation', true)
    expect(button('手动执行').disabled).toBe(true)
    expect(button('填写自动更新配置').disabled).toBe(true)
    expect(required(calls.automationProps).readOnly).toBe(true)
    expect(button('应用到草稿')).toBeUndefined()
    button('填写自动更新配置').click()
    button('关闭配置').click()
    await flush()
    await editRow('action', true)
    expect(button('数据变化后自动执行').disabled).toBe(true)
    expect(calls.recordProps.model.writable).toBe(false)
    expect(JSON.stringify(state.resources)).toBe(before)
    expect(changed).not.toHaveBeenCalled()
  })
  it('多选动作值经过真实响应式草稿仍可应用，并且不改原资源', async () => {
    const { state, changed } = await mount()
    const original = state.resources[0]!
    button('选择多选动作值').click()
    await flush()
    expect(calls.guard?.()).toBe(true)
    button('应用到草稿').click()
    await flush()
    expect(calls.validate).toHaveBeenCalledOnce()
    expect(changed).toHaveBeenCalledOnce()
    expect(state.resources[0]!.config.values).toEqual({ tags: ['alpha', 'beta'], customer: '7' })
    expect(original.config.values).toEqual({ tags: ['alpha'], customer: '7' })
    expect(host.querySelector('[data-modal]')).toBeNull()
    expect(calls.guard?.()).toBe(false)
  })
  it('把对象关系、应用身份与引用版本传到动作值选择预览', async () => {
    await mount()
    expect(calls.recordProps).toMatchObject({
      applicationId: 'app-orders',
      objectId: 'orders',
      relations: [relation],
      preview: true
    })
    expect(calls.recordProps.fields.map((field: { id: string }) => field.id)).toEqual(['tags', 'customer'])
    expect(calls.recordProps.options.customer.generated).toBe(false)
    expect(calls.preview.value).toEqual({
      applicationId: 'app-orders',
      objects: [
        { objectId: 'orders', versionNo: 4, checksum: 'orders-v4' },
        { objectId: 'customers', versionNo: 2, checksum: 'customers-v2' }
      ]
    })
  })
  it('未改动关闭不标记脏，拒绝放弃保留编辑，确认放弃不污染草稿', async () => {
    const { state, changed } = await mount()
    expect(calls.guard?.()).toBe(false)
    button('关闭配置').click()
    await flush()
    expect(calls.confirmDiscard).toHaveBeenLastCalledWith(false)
    expect(host.querySelector('[data-modal]')).toBeNull()
    button('配置').click()
    await flush()
    await rename('未应用名称')
    calls.confirmDiscard.mockResolvedValueOnce(false)
    button('关闭配置').click()
    await flush()
    expect(calls.confirmDiscard).toHaveBeenLastCalledWith(true)
    expect(host.querySelector<HTMLInputElement>('[data-label="名称"] input')!.value).toBe('未应用名称')
    expect(calls.guard?.()).toBe(true)
    button('关闭配置').click()
    await flush()
    expect(host.querySelector('[data-modal]')).toBeNull()
    expect(state.resources[0]!.name).toBe('更新标签')
    expect(changed).not.toHaveBeenCalled()
    expect(calls.guard?.()).toBe(false)
  })
  it('等待动作校验时阻止重复应用及关闭，完成后只提交一次', async () => {
    let finish!: () => void
    calls.validate.mockImplementation(
      () =>
        new Promise<void>(resolve => {
          finish = resolve
        })
    )
    const { changed } = await mount()
    button('应用到草稿').click()
    await flush()
    expect(calls.guard?.()).toBe(true)
    expect(button('关闭配置').disabled).toBe(true)
    button('应用到草稿').click()
    button('关闭配置').click()
    await flush()
    expect(calls.validate).toHaveBeenCalledOnce()
    expect(calls.confirmDiscard).not.toHaveBeenCalled()
    finish()
    await flush()
    expect(changed).toHaveBeenCalledOnce()
    expect(calls.guard?.()).toBe(false)
  })
})
