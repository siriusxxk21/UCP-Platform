// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, find, event, text, table, empty, type TestNode } from '../../tools/selection-regression/renderer'
import ResourceManager from '@/views/nocode/application/components/ResourceManager.vue'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import type { ViewConfig } from '@/types/nocode/application-ui'
import type { ReportConfig } from '@/types/nocode/report'
import type { ViewQueryOptions } from '@/types/nocode/data-scope'
import { FieldType, RelationType } from '@/types/nocode/enums'
import { defaultViewList } from './runtime-list'
import { defaultReport } from './report'

const calls = vi.hoisted(() => ({
  confirm: vi.fn(),
  success: vi.fn(),
  view: undefined as undefined | { modelValue: ViewQueryOptions },
  report: undefined as undefined | { modelValue: ReportConfig },
  guard: undefined as undefined | (() => boolean)
}))
vi.mock('ant-design-vue', () => ({ message: { success: calls.success }, Modal: { confirm: calls.confirm } }))
vi.mock('@/nocode/unsaved', () => ({
  useUnsavedNavigation: (guard: () => boolean) => {
    calls.guard = guard
  }
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/views/nocode/application/components/BusinessDesigner.vue', async () => {
  const { defineComponent, onMounted } = await import('vue')
  return {
    default: defineComponent({
      props: ['nodes', 'detailNodes'],
      emits: ['ready'],
      setup(props, { expose, emit }) {
        expose({
          isReady: () => true,
          hasChanges: () => false,
          validate: () => {},
          getNodes: () => props.nodes,
          getDetailNodes: () => props.detailNodes || {}
        })
        onMounted(() => emit('ready'))
        return () => null
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/FormPreview.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/TinyPageDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FormObjectVersionNotice.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/DetailFormSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/RelatedFormSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/DataViewSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/PageFilterConfig.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ReportConfigEditor.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      props: ['modelValue'],
      setup(props) {
        calls.report = props
        return () => null
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/ViewQueryEditor.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      props: ['modelValue'],
      setup(props) {
        calls.view = props
        return () => null
      }
    })
  }
})

const fields = [
  { id: 'name', name: '名称', type: FieldType.TEXT },
  { id: 'active', name: '启用', type: FieldType.BOOLEAN },
  { id: 'tags', name: '标签', type: FieldType.MULTI_SELECT },
  { id: 'url', name: '链接', type: FieldType.URL },
  { id: 'amount', name: '金额', type: FieldType.DECIMAL },
  { id: 'department', name: '部门', type: FieldType.TEXT }
]
const objects = {
  orders: {
    objectId: 'orders',
    versionNo: 1,
    checksum: 'v1',
    definition: {
      objectId: 'orders',
      objectName: '订单',
      fields,
      fieldOptions: {},
      relations: [],
      details: []
    }
  }
} as unknown as Record<string, PublishedObject>
function view(config: Record<string, unknown> = {}): ApplicationResource {
  return {
    id: 'view',
    kind: ResourceKind.VIEW,
    name: '订单列表',
    code: 'orders_view',
    config: {
      objectId: 'orders',
      fieldIds: ['name', 'active', 'tags'],
      equal: {},
      query: { fixed: [], defaults: {}, candidates: {} },
      list: defaultViewList(),
      ...config
    }
  }
}
function report(config: Partial<ReportConfig> = {}): ApplicationResource {
  return {
    id: 'report',
    kind: ResourceKind.REPORT,
    name: '部门统计',
    code: 'orders_report',
    config: {
      ...defaultReport('orders'),
      display: 'BAR',
      dimensions: [{ fieldId: 'department', relationPath: null, bucket: 'VALUE' }],
      ...config
    }
  }
}
let page: ReturnType<typeof mount> | undefined
async function openResource(
  resource: ApplicationResource,
  others: ApplicationResource[] = [],
  publishedObjects: Record<string, PublishedObject> = objects
) {
  const resources = ref([resource, ...others]),
    changed = vi.fn()
  page = mount(
    defineComponent({
      setup: () => () =>
        h(ResourceManager, {
          modelValue: resources.value,
          'onUpdate:modelValue': (value: ApplicationResource[]) => {
            resources.value = value
          },
          objects: publishedObjects,
          readOnly: false,
          applicationId: 'app',
          onChange: changed
        })
    })
  )
  const root = page.root
  await event(find(root, 'a-segmented'), 'onUpdate:value', resource.kind)
  await event(
    find(
      find(root, 'row', node => node.props.record.id === resource.id),
      'a-button',
      node => text(node).trim() === '配置'
    ),
    'onClick'
  )
  return { root, resources, changed }
}
function modal(root: TestNode) {
  return find(root, 'a-modal', node => node.props['ok-text'] === '应用到草稿')
}
function error(root: TestNode) {
  return find(root, 'a-alert', node => node.props.type === 'error').props.message
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.guard = undefined
  calls.view = undefined
  calls.report = undefined
})
afterEach(() => {
  page?.unmount()
  page = undefined
})

describe('ResourceManager 配置准备和校验基线', () => {
  it('列表仅修改字典配置后进入未改动表单，路由离开仍有未保存保护', async () => {
    const { root, resources } = await openResource(view())
    await event(
      find(root, 'a-button', node => text(node).trim() === '添加字典筛选'),
      'onClick'
    )
    expect(calls.guard?.()).toBe(true)
    await event(
      find(root, 'a-button', node => text(node).trim() === '生成并编辑默认表单'),
      'onClick'
    )
    expect(calls.guard?.()).toBe(true)
    await event(
      find(root, 'a-modal', node => node.props['ok-text'] === '完成并返回列表配置'),
      'onCancel'
    )
    expect(find(root, 'a-select', node => node.props.placeholder === '应用字典')).toBeTruthy()
    expect(resources.value).toHaveLength(1)
  })
  it('新建列表生成默认表单，第二个列表复用；编辑旧列表不隐式生成', async () => {
    const { root, resources } = await openResource(view())
    await event(modal(root), 'onOk')
    expect(resources.value.filter(resource => resource.kind === 'FORM')).toHaveLength(0)
    for (const name of ['新列表一', '新列表二']) {
      await event(
        find(root, 'a-button', node => text(node).trim() === '新建数据视图'),
        'onClick'
      )
      await event(
        find(root, 'a-input', node => node.props['aria-label'] === '资源名称'),
        'onUpdate:value',
        name
      )
      await event(modal(root), 'onOk')
    }
    expect(resources.value.filter(resource => resource.kind === 'FORM')).toHaveLength(1)
    const form = resources.value.find(resource => resource.kind === 'FORM')!
    expect(form.config.options).toMatchObject({ defaultForObject: true })
    expect(resources.value.filter(resource => resource.kind === 'VIEW')).toHaveLength(3)
  })

  it('从旧列表生成并编辑默认表单，返回保留列表输入，外层取消不留表单', async () => {
    const original = view()
    const { root, resources, changed } = await openResource(original)
    await event(
      find(root, 'a-input', node => node.props['aria-label'] === '资源名称'),
      'onUpdate:value',
      '尚未保存的列表'
    )
    await event(
      find(root, 'a-button', node => text(node).trim() === '生成并编辑默认表单'),
      'onClick'
    )
    await event(
      find(root, 'a-input', node => node.props['aria-label'] === '资源名称'),
      'onUpdate:value',
      '定制默认表单'
    )
    await event(
      find(root, 'a-modal', node => node.props['ok-text'] === '完成并返回列表配置'),
      'onOk'
    )
    expect(find(root, 'a-input', node => node.props['aria-label'] === '资源名称').props.value).toBe('尚未保存的列表')
    expect(resources.value).toEqual([original])
    expect(changed).not.toHaveBeenCalled()
    await event(modal(root), 'onCancel')
    calls.confirm.mock.calls.at(-1)![0].onOk()
    expect(resources.value).toEqual([original])
  })

  it('复制并使用与列表一起提交，原表单及另一列表不受影响', async () => {
    const form: ApplicationResource = {
      id: 'shared',
      kind: 'FORM',
      name: '共用表单',
      code: 'shared',
      config: {
        objectId: 'orders',
        nodes: [],
        detailIds: [],
        options: { layout: 'vertical', submitText: '保存', defaultForObject: true }
      }
    }
    const other = { ...view(), id: 'other', code: 'other', name: '其他列表' }
    const { root, resources } = await openResource(view(), [form, other])
    await event(
      find(root, 'a-button', node => text(node).trim() === '复制并使用'),
      'onClick'
    )
    await event(
      find(root, 'a-modal', node => node.props['ok-text'] === '完成并返回列表配置'),
      'onOk'
    )
    expect(resources.value).toHaveLength(3)
    await event(modal(root), 'onOk')
    expect(resources.value).toHaveLength(4)
    const copied = resources.value.find(resource => resource.kind === 'FORM' && resource.id !== 'shared')!
    expect(copied.config.options).toMatchObject({ defaultForObject: false })
    expect(resources.value.find(resource => resource.id === 'view')?.config.formId).toBe(copied.id)
    expect(resources.value.find(resource => resource.id === 'other')).toEqual(other)
    expect(resources.value.find(resource => resource.id === 'shared')).toEqual(form)
  })

  it('旧指定表单失效时可以新建并替换，不发生无反馈点击', async () => {
    const { root, resources } = await openResource(view({ formId: 'deleted' }))
    await event(
      find(root, 'a-button', node => text(node).trim() === '新建并使用'),
      'onClick'
    )
    await event(
      find(root, 'a-modal', node => node.props['ok-text'] === '完成并返回列表配置'),
      'onOk'
    )
    await event(modal(root), 'onOk')
    expect(resources.value[0]?.config.formId).not.toBe('deleted')
    expect(resources.value.filter(resource => resource.kind === 'FORM')).toHaveLength(1)
  })

  it.each([
    [
      '默认查询',
      view({
        list: { ...defaultViewList(), queryFieldIds: ['active', 'removed'] },
        query: { fixed: [], defaults: { active: 'false', removed: '保留我的查询值' }, candidates: {} }
      }),
      '默认查询字段已不可用'
    ],
    ['报表固定筛选', report({ equal: { active: 'false', removed: '保留我的筛选值' } }), '报表固定筛选字段已不可用']
  ] as const)('%s 的缺失字段拒绝应用并保留原输入', async (_, original, expected) => {
    const { root, resources, changed } = await openResource(original)
    const input = () => (original.kind === ResourceKind.VIEW ? calls.view?.modelValue : calls.report?.modelValue)
    const snapshot = JSON.stringify(input())
    expect(calls.guard?.()).toBe(false)
    await event(modal(root), 'onOk')
    expect(error(root)).toBe(expected)
    expect(JSON.stringify(input())).toBe(snapshot)
    expect(calls.guard?.()).toBe(false)
    expect(resources.value[0]).toEqual(original)
    expect(changed).not.toHaveBeenCalled()
  })

  it('视图末尾校验失败保留输入的查询值与列宽，不留下半规范化状态或误报修改', async () => {
    const original = view({
      list: {
        ...defaultViewList(),
        queryFieldIds: ['active', 'name'],
        columnWidths: { name: 240, active: null, removed: 300 }
      },
      query: {
        fixed: [
          { fieldId: 'active', operator: 'eq', value: 'false' },
          { fieldId: 'name', operator: 'isNull', value: '尚未清理的输入' }
        ],
        defaults: { active: 'true', name: '' },
        candidates: {}
      },
      filterDictionaries: { name: '' }
    })
    const { root, changed } = await openResource(original)
    const query = JSON.stringify(calls.view?.modelValue)
    expect(calls.guard?.()).toBe(false)
    await event(modal(root), 'onOk')
    expect(error(root)).toContain('筛选字典需要指定字段和字典')
    expect(JSON.stringify(calls.view?.modelValue)).toBe(query)
    expect(find(root, 'a-input-number', node => node.props['aria-label'] === '启用默认列宽').props.value).toBeNull()
    expect(calls.guard?.()).toBe(false)
    expect(changed).not.toHaveBeenCalled()
  })

  it('报表末尾校验失败保留输入的固定范围及空绑定，不因尝试应用而标记修改', async () => {
    const original = report({
      display: 'PIE',
      equal: { active: 'false' },
      dateFieldId: '',
      sortMetricId: '',
      detailViewId: '',
      metrics: [
        { id: 'count', name: '数量', operation: 'COUNT', fieldId: null },
        { id: 'sum', name: '金额', operation: 'SUM', fieldId: 'amount' }
      ]
    })
    const { root, changed } = await openResource(original)
    const input = JSON.stringify(calls.report?.modelValue)
    expect(calls.guard?.()).toBe(false)
    await event(modal(root), 'onOk')
    expect(error(root)).toBe('饼图需要一个分组和一个指标')
    expect(JSON.stringify(calls.report?.modelValue)).toBe(input)
    expect(calls.guard?.()).toBe(false)
    expect(changed).not.toHaveBeenCalled()
  })

  it('旧标量范围迁入 query，数组及特殊字段保留，并规范化列宽/布尔默认值后独立应用', async () => {
    const original = view({
      equal: { name: '甲公司', tags: ['a', 'b'], url: { link: 'https://example.com', text: '官网' } },
      query: { fixed: [], defaults: { active: 'false', name: '' }, candidates: { tags: ['a', 'b'] } },
      list: {
        ...defaultViewList(),
        queryFieldIds: ['active', 'name'],
        columnWidths: { name: 280, active: null, removed: 400 }
      },
      formId: '',
      sortFieldId: '',
      detailPageId: 'page-detail',
      composition: { protocolMarker: 'keep' }
    })
    const snapshot = JSON.stringify(original)
    const { root, resources, changed } = await openResource(original)
    expect(calls.guard?.()).toBe(false)
    expect(JSON.stringify(original)).toBe(snapshot)
    await event(modal(root), 'onOk')
    const saved = resources.value[0]?.config as unknown as ViewConfig
    expect(saved.query).toEqual({
      fixed: [{ fieldId: 'name', operator: 'eq', value: '甲公司', valueSource: 'CONSTANT' }],
      defaults: { active: false },
      candidates: { tags: ['a', 'b'] }
    })
    expect(saved.equal).toEqual({ tags: ['a', 'b'], url: { link: 'https://example.com', text: '官网' } })
    expect(saved.list?.columnWidths).toEqual({ name: 280 })
    expect(saved.formId).toBeNull()
    expect(saved.sortFieldId).toBeNull()
    expect(saved.detailPageId).toBe('page-detail')
    expect(saved.composition).toEqual({ protocolMarker: 'keep' })
    expect(JSON.stringify(original)).toBe(snapshot)
    expect(changed).toHaveBeenCalledOnce()
    expect(modal(root).props.open).toBe(false)
  })

  it('应用字典只带可用项，固定条件使用稳定字段 ID，应用后再次打开不会改变已应用快照', async () => {
    const dictionary: ApplicationResource = {
      id: 'dictionary',
      kind: ResourceKind.DICTIONARY,
      code: 'tags',
      name: '标签字典',
      config: {
        items: [
          { code: 'a', label: '甲' },
          { code: 'b', label: '乙' },
          { code: 'disabled', label: '停用', disabled: true }
        ]
      }
    }
    const { root, resources } = await openResource(view(), [dictionary])
    await event(
      find(root, 'a-button', node => text(node) === '添加字典筛选'),
      'onClick'
    )
    const field = find(root, 'a-select', node => node.props.placeholder === '筛选字段')
    await event(field, 'onUpdate:value', 'tags')
    await event(field, 'onChange', 'tags')
    const source = find(root, 'a-select', node => node.props.placeholder === '应用字典')
    await event(source, 'onUpdate:value', 'dictionary')
    await event(source, 'onChange', 'dictionary')
    await event(modal(root), 'onOk')
    const saved = resources.value[0]?.config as unknown as ViewConfig
    expect(saved.query?.fixed).toEqual([{ fieldId: 'tags', operator: 'containsAny', value: ['a', 'b'] }])
    expect(saved.filterDictionaries).toEqual({ tags: 'dictionary' })
    const snapshot = JSON.stringify(saved)
    await event(
      find(root, 'a-button', node => text(node).trim() === '配置'),
      'onClick'
    )
    expect(calls.guard?.()).toBe(false)
    await event(
      find(root, 'a-input', node => node.props['aria-label'] === '资源名称'),
      'onUpdate:value',
      '未应用名称'
    )
    expect(JSON.stringify(saved)).toBe(snapshot)
    await event(modal(root), 'onCancel')
    expect(calls.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '放弃尚未应用到草稿的修改？' }))
    expect(modal(root).props.open).toBe(true)
  })

  it.each([
    [
      '过多常用查询',
      {
        list: {
          ...defaultViewList(),
          queryFieldIds: ['name', 'active', 'tags', 'url', 'amount', 'department', 'extra']
        }
      },
      '常用查询最多配置 6 个字段'
    ],
    ['无显示字段', { fieldIds: [] }, '至少选择一个显示字段'],
    [
      '重复固定范围',
      {
        query: {
          fixed: [
            { fieldId: 'name', operator: 'eq', value: '甲' },
            { fieldId: 'name', operator: 'eq', value: '乙' }
          ],
          defaults: {},
          candidates: {}
        }
      },
      '固定范围字段不能重复'
    ],
    [
      '默认值缺少常用字段',
      { query: { fixed: [], defaults: { active: 'true' }, candidates: {} } },
      '默认查询字段需要同时配置为常用查询字段'
    ],
    ['不完整字典', { filterDictionaries: { name: '' } }, '筛选字典需要指定字段和字典']
  ] as const)('%s 校验失败保留草稿，错误定位回显示页签', async (_, config, expected) => {
    const original = view(config),
      snapshot = JSON.stringify(original)
    const { root, resources, changed } = await openResource(original)
    await event(find(root, 'a-tabs'), 'onUpdate:activeKey', 'interaction')
    await event(modal(root), 'onOk')
    expect(error(root)).toContain(expected)
    expect(find(root, 'a-tabs').props['active-key']).toBe('display')
    expect(modal(root).props.open).toBe(true)
    expect(changed).not.toHaveBeenCalled()
    expect(JSON.stringify(resources.value[0])).toBe(snapshot)
  })

  it('报表固定范围规范化布尔值和空值，不改变指标身份、独立条件、精度格式与发布配置', async () => {
    const original = report({
      equal: { active: 'false', amount: '9007199254740993.25', name: '' },
      dateFieldId: '',
      sortMetricId: '',
      detailViewId: '',
      metrics: [
        {
          id: 'amount_total',
          name: '总金额',
          operation: 'SUM',
          fieldId: 'amount',
          conditions: { logic: 'AND', items: [{ type: 'condition', field: 'active', operator: 'eq', value: false }] },
          format: { decimals: 2, unit: '元' }
        }
      ]
    })
    const snapshot = JSON.stringify(original)
    const { root, resources, changed } = await openResource(original)
    await event(modal(root), 'onOk')
    const saved = resources.value[0]?.config as unknown as ReportConfig
    expect(saved.equal).toEqual({ active: false, amount: '9007199254740993.25' })
    expect(saved.metrics).toEqual(original.config.metrics)
    expect(saved.dimensions).toEqual(original.config.dimensions)
    expect(saved.dateFieldId).toBeNull()
    expect(saved.sortMetricId).toBeNull()
    expect(saved.detailViewId).toBeNull()
    expect(JSON.stringify(original)).toBe(snapshot)
    expect(changed).toHaveBeenCalledOnce()
  })

  it('报表两级关联字段按完整稳定路径规范化，保留分组、指标和筛选引用', async () => {
    const published = Object.fromEntries(
      ['orders', 'customers', 'regions'].map((objectId, index) => [
        objectId,
        {
          objectId,
          versionNo: 1,
          checksum: 'v1',
          definition: {
            objectId,
            objectName: objectId,
            fields,
            fieldOptions: {},
            details: [],
            relations:
              index === 2
                ? []
                : [
                    {
                      id: index === 0 ? 'customer' : 'region',
                      name: index === 0 ? '客户' : '区域',
                      kind: RelationType.REFERENCE,
                      targetObjectId: index === 0 ? 'customers' : 'regions'
                    }
                  ]
          }
        }
      ])
    ) as unknown as Record<string, PublishedObject>
    const original = report({
      dimensions: [{ fieldId: 'name', relationPath: 'customer/region', bucket: 'VALUE' }],
      equal: { 'customer/region:active': 'false' },
      filterFieldIds: ['customer/region:active'],
      metrics: [{ id: 'regional_total', name: '区域金额', operation: 'SUM', fieldId: 'customer/region:amount' }]
    })
    const { root, resources, changed } = await openResource(original, [], published)
    await event(modal(root), 'onOk')
    const saved = resources.value[0]?.config as unknown as ReportConfig
    expect(saved.equal).toEqual({ 'customer/region:active': false })
    expect(saved.dimensions).toEqual(original.config.dimensions)
    expect(saved.metrics).toEqual(original.config.metrics)
    expect(saved.filterFieldIds).toEqual(['customer/region:active'])
    expect(changed).toHaveBeenCalledOnce()
  })

  it.each([
    ['指标卡带分组', { display: 'METRIC' }, '指标卡不设置分组'],
    ['图表无分组', { dimensions: [] }, '至少添加一个分组'],
    ['指标缺字段', { metrics: [{ id: 'sum', name: '金额', operation: 'SUM', fieldId: '' }] }, '请补齐分组和指标'],
    ['无指标', { metrics: [] }, '请选择统计对象并配置 1～5 个指标']
  ] as const)('%s 的报表拒绝应用且保留原配置', async (_, config, expected) => {
    const original = report(config as Partial<ReportConfig>),
      snapshot = JSON.stringify(original)
    const { root, resources, changed } = await openResource(original)
    await event(modal(root), 'onOk')
    expect(error(root)).toContain(expected)
    expect(modal(root).props.open).toBe(true)
    expect(JSON.stringify(resources.value[0])).toBe(snapshot)
    expect(changed).not.toHaveBeenCalled()
  })

  it('资源名称、编码和重复编码仍按原顺序拦截，不影响其他资源', async () => {
    const original = view(),
      other: ApplicationResource = {
        id: 'other',
        kind: ResourceKind.MENU,
        name: '导航',
        code: 'existing_code',
        config: { targetId: 'view' }
      }
    const { root, resources, changed } = await openResource(original, [other])
    const name = find(root, 'a-input', node => node.props['aria-label'] === '资源名称')
    const code = find(root, 'a-input', node => node.props['aria-label'] === '资源编码')
    await event(name, 'onUpdate:value', ' ')
    await event(code, 'onUpdate:value', 'INVALID')
    await event(modal(root), 'onOk')
    expect(error(root)).toBe('请填写资源名称')
    await event(name, 'onUpdate:value', '新名称')
    await event(modal(root), 'onOk')
    expect(error(root)).toContain('编码使用小写字母开头')
    await event(code, 'onUpdate:value', 'existing_code')
    await event(modal(root), 'onOk')
    expect(error(root)).toContain('资源编码已存在')
    expect(changed).not.toHaveBeenCalled()
    expect(resources.value).toEqual([original, other])
  })
})

describe('ResourceManager 视图「内容超出列宽时自动截断」', () => {
  const toggle = (root: TestNode) =>
    find(root, 'a-switch', node => node.props['aria-label'] === '内容超出列宽时自动截断')
  const savedList = (resources: { value: ApplicationResource[] }) =>
    (resources.value[0]?.config as unknown as ViewConfig).list as NonNullable<ViewConfig['list']>
  const configure = (root: TestNode) =>
    event(
      find(root, 'a-button', node => text(node).trim() === '配置'),
      'onClick'
    )

  it('存量视图没有这个键：开关是关的，应用后定义里仍然没有这个键', async () => {
    const { root, resources } = await openResource(view())
    expect(toggle(root).props.checked).toBe(false)
    await event(modal(root), 'onOk')
    expect('overflow' in savedList(resources)).toBe(false)
    expect(Object.keys(savedList(resources)).sort()).toEqual(Object.keys(defaultViewList()).sort())
  })

  it('打开后随草稿保存为 ELLIPSIS，再次打开仍是开的；关掉后这个键从定义里去掉', async () => {
    const { root, resources, changed } = await openResource(view())
    await event(toggle(root), 'onUpdate:checked', true)
    expect(toggle(root).props.checked).toBe(true)
    await event(modal(root), 'onOk')
    expect(savedList(resources).overflow).toBe('ELLIPSIS')
    expect(changed).toHaveBeenCalledOnce()

    await configure(root)
    expect(toggle(root).props.checked).toBe(true)
    await event(toggle(root), 'onUpdate:checked', false)
    expect(toggle(root).props.checked).toBe(false)
    await event(modal(root), 'onOk')
    expect('overflow' in savedList(resources)).toBe(false)
  })

  it('定义里不是 ELLIPSIS 的旧值（含不再支持的 WRAP）：开关是关的，保存结果里不带', async () => {
    for (const stale of ['WRAP', 'AUTO']) {
      const { root, resources } = await openResource(view({ list: { ...defaultViewList(), overflow: stale } }))
      expect(toggle(root).props.checked, stale).toBe(false)
      await event(modal(root), 'onOk')
      expect('overflow' in savedList(resources), stale).toBe(false)
      page?.unmount()
      page = undefined
    }
  })
})
