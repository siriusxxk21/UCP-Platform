import { describe, expect, it } from 'vitest'
import {
  applicationDashboardDetailViews,
  applicationDashboardInputFields,
  applicationDashboardMatchesPage,
  applicationDashboardRecordIdAvailable,
  prepareApplicationDashboard
} from './application-dashboard'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import type { ApplicationDashboardCatalog, ApplicationDashboardConfig } from '@/types/nocode/application-dashboard'
import { FieldType, MemberState } from '@/types/nocode/enums'

const reference = { id: '12', versionNo: 1, checksum: 'a'.repeat(64) }
const root = { objectId: 'orders', versionNo: 1, checksum: 'b'.repeat(64) }
const dataset = { id: '21', versionNo: 2, checksum: 'c'.repeat(64) }
const source = { schemaVersion: 1 as const, root, fields: [], relations: [] }
const catalog: ApplicationDashboardCatalog = {
  reference,
  content: {
    schemaVersion: 1,
    name: '订单看板',
    description: '',
    charts: [
      {
        id: 'chart',
        title: '订单金额',
        display: 'BAR',
        dataset,
        dimensions: [],
        metricIds: ['amount'],
        x: 0,
        y: 0,
        w: 6,
        h: 4
      }
    ],
    filters: [{ id: 'customer', name: '客户', kind: 'SELECT', mappings: [{ chartId: 'chart', fieldId: 'customer' }] }]
  },
  datasets: [
    {
      reference: dataset,
      content: { name: '订单', description: '', source },
      source: {
        source,
        objects: [root],
        fields: [
          {
            id: 'customer',
            name: '客户',
            role: 'DIMENSION',
            type: FieldType.TEXT,
            objectId: 'orders',
            objectVersion: 1,
            relationPath: [],
            sourceFieldId: 'customer'
          }
        ]
      }
    }
  ]
}
const objects = {
  customers: {
    objectId: 'customers',
    versionNo: 1,
    checksum: 'd'.repeat(64),
    definition: {
      fields: [
        { id: 'name', name: '名称', type: FieldType.TEXT },
        { id: 'amount', name: '金额', type: FieldType.DECIMAL },
        { id: 'inactive', name: '停用', type: FieldType.TEXT },
        { id: 'tags', name: '多选', type: FieldType.MULTI_SELECT }
      ],
      fieldOptions: { inactive: { state: MemberState.INACTIVE } }
    }
  }
} as unknown as Record<string, PublishedObject>
const resources: ApplicationResource[] = [
  { id: 'orders_view', kind: ResourceKind.VIEW, code: 'orders', name: '订单视图', config: { objectId: 'orders' } },
  { id: 'other_view', kind: ResourceKind.VIEW, code: 'other', name: '其他视图', config: { objectId: 'other' } },
  { id: 'orders_form', kind: ResourceKind.FORM, code: 'form', name: '订单表单', config: { objectId: 'orders' } }
]
function config(values: Partial<ApplicationDashboardConfig> = {}): ApplicationDashboardConfig {
  return { dashboard: { ...reference }, contextObjectId: null, inputBindings: [], detailViews: [], ...values }
}

describe('应用看板固定引用与绑定校验', () => {
  it('拒绝未核验目录、版本和校验和替换', () => {
    expect(() => prepareApplicationDashboard(config(), undefined, objects, resources)).toThrow('加载并核验')
    expect(() =>
      prepareApplicationDashboard(config({ dashboard: { ...reference, versionNo: 2 } }), catalog, objects, resources)
    ).toThrow('加载并核验')
    expect(() =>
      prepareApplicationDashboard(
        config({ dashboard: { ...reference, checksum: 'e'.repeat(64) } }),
        catalog,
        objects,
        resources
      )
    ).toThrow('加载并核验')
  })
  it('仅提供标量兼容字段，记录编号需与每个筛选映射目标兼容', () => {
    const filter = catalog.content.filters![0]!
    expect(applicationDashboardInputFields(catalog, filter, objects.customers).map(field => field.id)).toEqual(['name'])
    expect(applicationDashboardRecordIdAvailable(catalog, filter)).toBe(true)
    const incompatible = JSON.parse(JSON.stringify(catalog)) as ApplicationDashboardCatalog
    incompatible.datasets[0]!.source.fields[0]!.type = FieldType.DATE
    expect(applicationDashboardRecordIdAvailable(incompatible, filter)).toBe(false)
    expect(applicationDashboardInputFields(incompatible, filter, objects.customers)).toEqual([])
    expect(applicationDashboardInputFields(catalog, { ...filter, kind: 'MULTISELECT' }, objects.customers)).toEqual([])
  })
  it('固定版本升级后失效筛选拒绝应用且保留输入', () => {
    const value = config({ inputBindings: [{ filterId: 'removed', source: 'PARAMETER', parameter: 'customerId' }] })
    const before = JSON.stringify(value)
    expect(() => prepareApplicationDashboard(value, catalog, objects, resources)).toThrow('失效或重复')
    expect(JSON.stringify(value)).toBe(before)
  })
  it('拒绝重复参数、没有上下文的记录输入与不兼容字段', () => {
    expect(() =>
      prepareApplicationDashboard(
        config({ inputBindings: [{ filterId: 'customer', source: 'RECORD_ID' }] }),
        catalog,
        objects,
        resources
      )
    ).toThrow('上下文对象')
    expect(() =>
      prepareApplicationDashboard(config({ contextObjectId: 'missing' }), catalog, objects, resources)
    ).toThrow('已加入应用')
    expect(() =>
      prepareApplicationDashboard(
        config({
          contextObjectId: 'customers',
          inputBindings: [{ filterId: 'customer', source: 'RECORD_FIELD', fieldId: 'amount' }]
        }),
        catalog,
        objects,
        resources
      )
    ).toThrow('不兼容')
    const twoFilters = JSON.parse(JSON.stringify(catalog)) as ApplicationDashboardCatalog
    twoFilters.content.filters!.push({ ...twoFilters.content.filters![0]!, id: 'customer2' })
    expect(() =>
      prepareApplicationDashboard(
        config({
          inputBindings: [
            { filterId: 'customer', source: 'PARAMETER', parameter: 'same' },
            { filterId: 'customer2', source: 'PARAMETER', parameter: 'same' }
          ]
        }),
        twoFilters,
        objects,
        resources
      )
    ).toThrow('唯一参数名')
  })
  it('业务明细候选只含同根对象的本应用 VIEW，页面上下文须匹配', () => {
    expect(applicationDashboardDetailViews(catalog, 'chart', resources).map(value => value.id)).toEqual(['orders_view'])
    expect(() =>
      prepareApplicationDashboard(
        config({ detailViews: [{ chartId: 'chart', viewId: 'other_view' }] }),
        catalog,
        objects,
        resources
      )
    ).toThrow('同一根对象')
    expect(() =>
      prepareApplicationDashboard(
        config({ detailViews: [{ chartId: 'chart', viewId: 'orders_form' }] }),
        catalog,
        objects,
        resources
      )
    ).toThrow('同一根对象')
    const dashboard: ApplicationResource = {
      id: 'dashboard',
      kind: ResourceKind.REPORT_DASHBOARD,
      code: 'dashboard',
      name: '看板',
      config: { contextObjectId: 'customers' }
    }
    expect(applicationDashboardMatchesPage(dashboard, 'customers')).toBe(true)
    expect(applicationDashboardMatchesPage(dashboard, 'orders')).toBe(false)
    expect(applicationDashboardMatchesPage(dashboard)).toBe(false)
    expect(applicationDashboardMatchesPage({ ...dashboard, config: {} })).toBe(true)
    expect(applicationDashboardMatchesPage(resources[0]!, 'orders')).toBe(false)
  })
  it('成功规范化产生独立副本，不修改固定输入和源对象引用', () => {
    const value = config({
      contextObjectId: 'customers',
      inputBindings: [{ filterId: 'customer', source: 'RECORD_FIELD', fieldId: 'name', parameter: '' }],
      detailViews: [{ chartId: 'chart', viewId: 'orders_view' }]
    })
    const before = JSON.stringify([value, objects, resources])
    const saved = prepareApplicationDashboard(value, catalog, objects, resources)
    expect(saved.inputBindings).toEqual([
      { filterId: 'customer', source: 'RECORD_FIELD', fieldId: 'name', parameter: null }
    ])
    expect(saved.dashboard).not.toBe(value.dashboard)
    expect(saved.detailViews?.[0]).not.toBe(value.detailViews?.[0])
    expect(JSON.stringify([value, objects, resources])).toBe(before)
  })
})
