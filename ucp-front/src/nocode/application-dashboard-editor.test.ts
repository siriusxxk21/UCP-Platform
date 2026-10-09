// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, find, event, text, flush } from '../../tools/selection-regression/renderer'
import Editor from '@/views/nocode/application/components/ApplicationDashboardConfigEditor.vue'
import type { ApplicationDashboardCatalog, ApplicationDashboardConfig } from '@/types/nocode/application-dashboard'
import type { PublishedObject } from '@/types/nocode/application'

const api = vi.hoisted(() => ({ dashboardCatalogPage: vi.fn(), dashboardCatalog: vi.fn(), confirm: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: api }) }))
vi.mock('ant-design-vue', () => ({ Modal: { confirm: api.confirm } }))
const fixed = { id: '12', versionNo: 1, checksum: 'a'.repeat(64) }
const latest = { ...fixed, versionNo: 2, checksum: 'b'.repeat(64) }
const object = {
  objectId: 'orders',
  versionNo: 3,
  checksum: 'c'.repeat(64),
  definition: { objectName: '订单', fields: [], fieldOptions: {} }
} as unknown as PublishedObject
function contract(reference = fixed): ApplicationDashboardCatalog {
  return {
    reference,
    datasets: [],
    content: {
      schemaVersion: 1,
      name: '订单看板',
      description: '',
      charts: [],
      filters: reference.versionNo === 1 ? [{ id: 'customer', name: '客户', kind: 'TEXT', mappings: [] }] : []
    }
  }
}
let page: ReturnType<typeof mount> | undefined
function open(
  value: ApplicationDashboardConfig = {
    dashboard: { ...fixed },
    contextObjectId: null,
    inputBindings: [],
    detailViews: []
  }
) {
  const model = ref(value),
    editor = ref<InstanceType<typeof Editor>>()
  page = mount(
    defineComponent({
      setup: () => () =>
        h(Editor, {
          ref: editor,
          modelValue: model.value,
          'onUpdate:modelValue': (value: ApplicationDashboardConfig) => {
            model.value = value
          },
          objects: { orders: object },
          resources: []
        })
    })
  )
  return { root: page.root, model, editor }
}
beforeEach(() => {
  vi.clearAllMocks()
  api.dashboardCatalogPage.mockResolvedValue({ list: [{ ...latest, name: '订单看板', chartCount: 0 }], total: 1 })
  api.dashboardCatalog.mockImplementation(async ({ reference }: { reference: typeof fixed }) => contract(reference))
})
afterEach(() => {
  page?.unmount()
  page = undefined
})

describe('应用看板配置使用受控目录接口', () => {
  it('候选刷新不自动升级，目录只提交固定引用与当前应用对象版本', async () => {
    const { model, editor } = open()
    await flush()
    expect(api.dashboardCatalogPage).toHaveBeenCalledWith({ pageNo: 1, pageSize: 20, search: undefined })
    expect(api.dashboardCatalog).toHaveBeenCalledWith({
      reference: fixed,
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }]
    })
    expect(model.value.dashboard).toEqual(fixed)
    expect(editor.value?.isReady()).toBe(true)
    expect(editor.value?.getConfig().dashboard).toEqual(fixed)
  })
  it('升级须显式确认，保留失效绑定并阻止应用', async () => {
    const { root, model, editor } = open({
      dashboard: { ...fixed },
      inputBindings: [{ filterId: 'customer', source: 'PARAMETER', parameter: 'customerId' }],
      detailViews: []
    })
    await flush()
    await event(
      find(root, 'a-button', node => text(node).includes('升级到')),
      'onClick'
    )
    expect(model.value.dashboard).toEqual(fixed)
    const confirm = api.confirm.mock.calls[0]![0] as { onOk: () => void }
    confirm.onOk()
    await flush()
    expect(model.value.dashboard).toEqual(latest)
    expect(model.value.inputBindings).toEqual([{ filterId: 'customer', source: 'PARAMETER', parameter: 'customerId' }])
    expect(editor.value?.isReady()).toBe(false)
    expect(() => editor.value?.getConfig()).toThrow('失效或重复')
  })
  it('固定目录加载失败可重试，失败不会清除输入', async () => {
    api.dashboardCatalog.mockRejectedValueOnce(new Error('应用未引用订单对象'))
    const { root, model, editor } = open()
    await flush()
    expect(editor.value?.isReady()).toBe(false)
    expect(() => editor.value?.getConfig()).toThrow('应用未引用订单对象')
    expect(model.value.dashboard).toEqual(fixed)
    await event(
      find(root, 'a-button', node => text(node) === '重新校验固定目录'),
      'onClick'
    )
    await flush()
    expect(editor.value?.isReady()).toBe(true)
  })
  it('旧固定目录返回不能覆盖新版本，切换时立即阻止应用旧目录', async () => {
    let resolveOld!: (value: ApplicationDashboardCatalog) => void
    api.dashboardCatalog.mockImplementationOnce(
      () =>
        new Promise<ApplicationDashboardCatalog>(resolve => {
          resolveOld = resolve
        })
    )
    const { model, editor } = open()
    await flush()
    expect(editor.value?.isReady()).toBe(false)
    model.value = { ...model.value, dashboard: { ...latest } }
    await flush()
    expect(editor.value?.getConfig().dashboard).toEqual(latest)
    resolveOld(contract())
    await flush()
    expect(editor.value?.getConfig().dashboard).toEqual(latest)
  })
})
