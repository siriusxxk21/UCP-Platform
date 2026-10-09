// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, find, findAll, event, text, table, empty } from '../../tools/selection-regression/renderer'
import ResourceManager from '@/views/nocode/application/components/ResourceManager.vue'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'
import { defaultViewList } from './runtime-list'

/**
 * 隐式可读对象在「页面与视图」里的边界：只转给表单设计器去查关系目标的字段，
 * 不进新建视图 / 表单 / 统计时的对象下拉。
 */
const calls = vi.hoisted(() => ({
  designer: undefined as undefined | Record<string, unknown>
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() }, Modal: { confirm: vi.fn() } }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/views/nocode/application/components/BusinessDesigner.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      props: ['objects', 'readableObjects'],
      setup(props) {
        calls.designer = props
        return () => null
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/TinyPageDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FormObjectVersionNotice.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/RelatedFormSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/DataViewSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/PageFilterConfig.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ReportConfigEditor.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ViewQueryEditor.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: empty }))

const published = (objectId: string, objectName: string): PublishedObject =>
  ({
    objectId,
    versionNo: 1,
    checksum: 'v1',
    definition: {
      objectId,
      objectName,
      fields: [{ id: 'name', name: '名称', type: FieldType.TEXT }],
      fieldOptions: {},
      relations: [],
      details: []
    }
  }) as unknown as PublishedObject
const objects = { orders: published('orders', '订单') }
const readableObjects = { customers: published('customers', '客户') }
const view: ApplicationResource = {
  id: 'view',
  kind: ResourceKind.VIEW,
  name: '订单列表',
  code: 'orders_view',
  config: {
    objectId: 'orders',
    fieldIds: ['name'],
    equal: {},
    query: { fixed: [], defaults: {}, candidates: {} },
    list: defaultViewList()
  }
}
const form: ApplicationResource = {
  id: 'form',
  kind: ResourceKind.FORM,
  name: '订单表单',
  code: 'orders_form',
  config: { objectId: 'orders', nodes: [], detailIds: [], options: { layout: 'vertical', submitText: '保存记录' } }
}
let page: ReturnType<typeof mount> | undefined
async function open(resource: ApplicationResource, extra: Record<string, unknown>) {
  const resources = ref([resource])
  page = mount(
    defineComponent({
      setup: () => () =>
        h(ResourceManager, {
          modelValue: resources.value,
          'onUpdate:modelValue': (value: ApplicationResource[]) => {
            resources.value = value
          },
          objects,
          readOnly: false,
          applicationId: 'app',
          ...extra
        })
    })
  )
  const root = page.root
  await event(find(root, 'a-segmented'), 'onUpdate:value', resource.kind)
  await event(
    find(root, 'a-button', node => text(node).trim() === '配置'),
    'onClick'
  )
  return root
}
/** 所有「选对象」的下拉：选项里出现了已引用对象的那些。 */
const objectSelects = (root: Parameters<typeof find>[0]) =>
  findAll(
    root,
    node =>
      node.type === 'a-select' &&
      Array.isArray(node.props.options) &&
      node.props.options.some((option: { value: string }) => option.value === 'orders')
  )
beforeEach(() => {
  vi.clearAllMocks()
  calls.designer = undefined
})
afterEach(() => {
  page?.unmount()
  page = undefined
})

describe('页面与视图里隐式可读对象的边界', () => {
  it('数据视图的对象下拉里没有隐式对象', async () => {
    const root = await open(view, { readableObjects })
    const selects = objectSelects(root)
    expect(selects.length).toBeGreaterThan(0)
    for (const select of selects) expect(select.props.options).toEqual([{ value: 'orders', label: '订单' }])
  })
  it('业务表单的对象下拉里没有隐式对象；隐式对象只单独交给表单设计器', async () => {
    const root = await open(form, { readableObjects })
    for (const select of objectSelects(root)) expect(select.props.options).toEqual([{ value: 'orders', label: '订单' }])
    expect(Object.keys(calls.designer?.objects as object)).toEqual(['orders'])
    expect(Object.keys((calls.designer?.readableObjects ?? {}) as object)).toEqual(['customers'])
  })
  it('没有隐式对象时表单设计器拿不到这张表（对照组）', async () => {
    await open(form, {})
    expect(Object.keys(calls.designer?.objects as object)).toEqual(['orders'])
    expect(calls.designer?.readableObjects).toBeUndefined()
  })
})
