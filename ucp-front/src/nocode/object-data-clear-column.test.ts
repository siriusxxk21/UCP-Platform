// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import ObjectDataClearColumn from '@/views/nocode/components/ObjectDataClearColumn.vue'
import type { ObjectDataClearColumnPreview, ObjectDataModel } from '@/types/nocode/object-data'

const api = vi.hoisted(() => ({ model: vi.fn(), clearColumnPreview: vi.fn(), clearColumn: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ objectData: api, hasPermission: () => true })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', [slots.formItems?.(), slots.footer?.()])
    })
  }
})

const field = (id: string, name: string) => ({ key: id, id, code: id, name, type: 'TEXT' })
const model = {
  versionNo: 2,
  checksum: 'v2',
  model: { object: { fields: [field('note', '备注'), field('code', '编号')], details: [] } },
  columnTypes: { note: 'text', code: 'text' }
} as unknown as ObjectDataModel
const allowed = (extra: Partial<ObjectDataClearColumnPreview> = {}): ObjectDataClearColumnPreview => ({
  allowed: true,
  objectName: '订单',
  fieldName: '备注',
  columnType: 'text',
  versionNo: 3,
  checksum: 'v3',
  activeRows: 2,
  deletedRows: 1,
  blockers: [],
  message: '可以清空',
  impactToken: 'checked-1',
  ...extra
})
let app: App, host: HTMLDivElement
const cleared = vi.fn(),
  cancel = vi.fn()
async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(name: string) {
  const result = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === name)
  if (!result) throw new Error(`未找到按钮：${name}`)
  return result
}
async function mount(initialFieldId = 'note') {
  app = createApp(() =>
    h(ObjectDataClearColumn, { objectId: 'object', model, initialFieldId, onCleared: cleared, onCancel: cancel })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AFormItem', 'ADescriptions', 'ADescriptionsItem', 'ASpace', 'ASpin']) app.component(name, plain)
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup: props => () => h('div', { role: 'alert' }, [props.message, props.description])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.())
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              onChange: (event: Event) => emit('update:value', (event.target as HTMLSelectElement).value)
            },
            props.options.map((option: { label: string; value: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  host = document.createElement('div')
  document.body.appendChild(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.model.mockResolvedValue({ ...model, versionNo: 3, checksum: 'v3' })
  api.clearColumnPreview.mockResolvedValue(allowed())
  api.clearColumn.mockResolvedValue({ clearedActiveRows: 2, clearedDeletedRows: 1 })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('网格清空整列确认', () => {
  it('按最新发布结构预检，最终一次确认同时涵盖正常和逻辑删除值', async () => {
    await mount()
    expect(api.clearColumnPreview).toHaveBeenCalledWith({
      objectId: 'object',
      fieldId: 'note',
      versionNo: 3,
      checksum: 'v3'
    })
    expect(host.textContent).toContain('当前筛选、分页或定位记录不会缩小范围')
    expect(host.textContent).toContain('已有历史记录保留')
    expect(host.querySelectorAll('input[type=checkbox]')).toHaveLength(0)
    button('清空此列 3 个值').click()
    await flush()
    expect(api.clearColumn).toHaveBeenCalledExactlyOnceWith({
      objectId: 'object',
      fieldId: 'note',
      versionNo: 3,
      checksum: 'v3',
      impactToken: 'checked-1'
    })
    expect(cleared).toHaveBeenCalledWith({ clearedActiveRows: 2, clearedDeletedRows: 1 })
  })

  it('真实阻断位置常显，重复相同原因去重，不能发清空请求', async () => {
    api.clearColumnPreview.mockResolvedValue(
      allowed({
        allowed: false,
        blockers: ['订单 / 字段配置 / 必填：先取消必填', '订单 / 字段配置 / 必填：先取消必填']
      })
    )
    await mount()
    expect(host.querySelectorAll('li')).toHaveLength(1)
    expect(host.textContent).toContain('订单 / 字段配置 / 必填：先取消必填')
    expect(button('清空此列 3 个值').disabled).toBe(true)
    button('清空此列 3 个值').click()
    expect(api.clearColumn).not.toHaveBeenCalled()
  })

  it('影响过期失败保留目标列并废弃旧确认，重新检查后才可提交新凭据', async () => {
    api.clearColumn.mockRejectedValueOnce(new Error('列数据或依赖已变化，请重新检查并确认清空影响'))
    await mount()
    button('清空此列 3 个值').click()
    await flush()
    expect(host.textContent).toContain('列数据或依赖已变化，请重新检查并确认清空影响')
    expect(host.textContent).not.toContain('；请重新检查后再确认')
    expect(host.querySelector('select')?.value).toBe('note')
    expect(button('清空此列').disabled).toBe(true)
    expect(api.clearColumn).toHaveBeenCalledTimes(1)
    api.clearColumnPreview.mockResolvedValue(allowed({ activeRows: 4, impactToken: 'checked-2' }))
    button('重新检查').click()
    await flush()
    expect(api.clearColumn).toHaveBeenCalledTimes(1)
    button('清空此列 5 个值').click()
    await flush()
    expect(api.clearColumn.mock.lastCall?.[0].impactToken).toBe('checked-2')
  })

  it('快速切换列时迟到的预检不能覆盖当前列或误用凭据', async () => {
    let resolveFirst!: (value: ObjectDataClearColumnPreview) => void
    api.clearColumnPreview.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveFirst = resolve
        })
    )
    await mount()
    const select = host.querySelector('select')
    if (!select) throw new Error('未找到数据列选择器')
    select.value = 'code'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    api.clearColumnPreview.mockResolvedValue(allowed({ fieldName: '编号', activeRows: 4, impactToken: 'code-token' }))
    await flush()
    resolveFirst(allowed({ activeRows: 99, impactToken: 'stale-note-token' }))
    await flush()
    expect(host.textContent).not.toContain('100 个值')
    button('清空此列 5 个值').click()
    await flush()
    expect(api.clearColumn.mock.lastCall?.[0]).toMatchObject({ fieldId: 'code', impactToken: 'code-token' })
  })

  it('没有旧值时只提示结果，不执行无意义清空', async () => {
    api.clearColumnPreview.mockResolvedValue(allowed({ activeRows: 0, deletedRows: 0 }))
    await mount()
    expect(host.textContent).toContain('本列没有需要清空的值')
    expect(button('清空此列').disabled).toBe(true)
    expect(api.clearColumn).not.toHaveBeenCalled()
  })
})
