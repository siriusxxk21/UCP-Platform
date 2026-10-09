// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App, type Ref } from 'vue'
import { defaultReport } from './report'
import type { ReportConfig, ReportFilter, ReportResult } from '@/types/nocode/report'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { RecordContext } from '@/types/nocode/runtime'

const api = vi.hoisted(() => ({
  report: vi.fn(),
  reportDetails: vi.fn(),
  reportExport: vi.fn(),
  model: vi.fn(),
  viewModel: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  delete: vi.fn()
}))
const dashboard = vi.hoisted(() => ({
  current: undefined as undefined | { definitions: Ref<ReportFilter[]>; values: Ref<Record<string, unknown>> }
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => dashboard.current }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'report-drill-test' } }) }))
vi.mock('@/views/nocode/application/components/ReportFilterInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/DataViewChildren.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      emits: ['select'],
      setup:
        (_, { emit }) =>
        () =>
          h('button', { 'data-chart': true, onClick: () => emit('select', 0, 'in') }, '图表')
    })
  }
})
vi.mock('@/components/os-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource', 'showImport', 'showExport', 'rowSelection'],
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            {
              'data-list': true,
              'data-import': String(!!props.showImport),
              'data-export': String(!!props.showExport),
              'data-batch': String(!!props.rowSelection)
            },
            [
              slots.actions?.(),
              ...props.dataSource.map((record: { id: string }) =>
                h('section', { 'data-row': record.id }, slots.bodyCell?.({ record, column: { key: 'actions' } }))
              )
            ]
          )
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordSurface.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', { 'data-surface': props.title }, slots.default?.()) : null
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { record: Object, readOnly: Boolean },
      emits: ['saved'],
      setup:
        (props, { emit }) =>
        () =>
          h('form', { 'data-editor': props.record?.record.id || 'new', 'data-read-only': String(!!props.readOnly) }, [
            h('button', { type: 'button', 'data-save': true, onClick: () => emit('saved', props.record) }, '保存')
          ])
    })
  }
})
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'

const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE', 'DELETE', 'IMPORT', 'EXPORT'],
  readFields: ['company'],
  writeFields: ['company'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string) => ({ id, revision: '1', values: { company: id }, permissions })
const metrics = [{ id: 'in', name: '入金', operation: 'SUM' as const, fieldId: 'amount' }]
function reportConfig(display: ReportConfig['display'], extra: Partial<ReportConfig> = {}): ReportConfig {
  return {
    ...defaultReport('flow'),
    display,
    metrics,
    dimensions: [{ fieldId: 'company', relationPath: null, bucket: 'VALUE' }],
    columnDimensions: display === 'PIVOT' ? [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }] : [],
    pivot: null,
    detailViewId: 'drill-view',
    ...extra
  }
}
const pivotResult = (): ReportResult => ({
  dimensionNames: ['公司'],
  metrics,
  groups: [],
  totals: { in: '150' },
  totalGroups: 0,
  recordCount: 3,
  canExport: true,
  timeZone: 'Asia/Shanghai',
  pivot: {
    rowDimensionNames: ['公司'],
    columnDimensionNames: ['月份'],
    rows: [{ keys: ['A'], labels: ['甲公司'] }],
    columns: [{ keys: ['2026-08'], labels: ['2026-08'] }],
    cells: [
      { rowKeys: ['A'], columnKeys: ['2026-08'], values: { in: '100' } },
      { rowKeys: ['A'], columnKeys: [], values: { in: '100' } },
      { rowKeys: [], columnKeys: ['2026-08'], values: { in: '100' } },
      { rowKeys: [], columnKeys: [], values: { in: '100' } }
    ],
    rowsTruncated: false,
    columnsTruncated: false,
    totalRowGroups: 1,
    totalColumnGroups: 1
  }
})
const tableResult = (): ReportResult => ({
  dimensionNames: ['公司'],
  metrics,
  groups: [{ keys: ['A'], labels: ['甲公司'], values: { in: '100' } }],
  totals: { in: '100' },
  totalGroups: 1,
  recordCount: 1,
  canExport: false,
  timeZone: 'Asia/Shanghai'
})
const allButtons = ['CREATE', 'IMPORT', 'EXPORT', 'VIEW', 'UPDATE', 'DELETE']
const drillView = (buttons = allButtons): ApplicationResource => ({
  id: 'drill-view',
  kind: ResourceKind.VIEW,
  name: '流水明细',
  code: 'drill_view',
  config: {
    objectId: 'flow',
    fieldIds: ['company'],
    equal: {},
    sortFieldId: null,
    descending: true,
    pageSize: 20,
    formId: null,
    interaction: { buttons, actionIds: [], editMode: 'DRAWER', detailMode: 'DRAWER' },
    list: { queryFieldIds: [], advancedFieldIds: null, columnWidths: {}, batchDelete: true }
  }
})
const reportResource = (config: ReportConfig): ApplicationResource => ({
  id: 'report',
  kind: ResourceKind.REPORT,
  name: '资金透视',
  code: 'report',
  config: config as unknown as Record<string, unknown>
})

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await vi.dynamicImportSettled()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}
function register(target: App) {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.title?.(), slots.extra?.(), slots.default?.()])
  })
  for (const name of [
    'ACard',
    'ASpace',
    'ASpin',
    'AForm',
    'AFormItem',
    'AEmpty',
    'ATag',
    'ARangePicker',
    'ADropdown',
    'AMenu',
    'AMenuItem',
    'AUpload'
  ])
    target.component(name, plain)
  target.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (props, { attrs }) =>
        () =>
          h('aside', { role: 'alert', ...attrs }, props.message)
    })
  )
  target.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled }, slots.default?.())
    })
  )
  target.component(
    'APopconfirm',
    defineComponent({
      emits: ['confirm'],
      setup:
        (_, { slots, emit }) =>
        () =>
          h('span', [
            slots.default?.(),
            h('button', { type: 'button', 'data-confirm': true, onClick: () => emit('confirm') }, '确认')
          ])
    })
  )
  const overlay = (attribute: string) =>
    defineComponent({
      props: ['open', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', { [attribute]: props.title }, slots.default?.()) : null
    })
  target.component('ADrawer', overlay('data-drawer'))
  target.component('AModal', overlay('data-modal'))
  target.component(
    'ATable',
    defineComponent({
      props: ['dataSource', 'columns'],
      setup:
        (props, { slots }) =>
        () =>
          h(
            'table',
            { 'data-antd-table': true },
            (props.dataSource || []).map((record: unknown) =>
              h(
                'tr',
                (props.columns || []).map((column: { key: string; customRender?: (p: unknown) => unknown }) =>
                  h(
                    'td',
                    { 'data-key': column.key },
                    (column.customRender
                      ? column.customRender({ record })
                      : slots.bodyCell?.({ column, record })) as never
                  )
                )
              )
            )
          )
    })
  )
}
async function mount(
  config: ReportConfig,
  options: { resources?: ApplicationResource[]; context?: RecordContext } = {}
) {
  const resource = reportResource(config)
  app = createApp(() =>
    h(ReportBlock, {
      applicationId: 'app',
      resource,
      resources: options.resources ?? [resource, drillView()],
      context: options.context
    })
  )
  register(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const drawer = () => host.querySelector('[data-drawer]')
const buttons = (root: Element | null) =>
  Array.from(root?.querySelectorAll('button') || []).map(b => b.textContent?.trim() || '')
async function clickPivotLeaf() {
  host.querySelector<HTMLButtonElement>('tbody td button')!.click()
  await flush()
}
beforeEach(() => {
  dashboard.current = undefined
  api.report.mockResolvedValue(pivotResult())
  api.reportDetails.mockResolvedValue({ list: [row('legacy')], total: 1 })
  api.model.mockResolvedValue({
    writable: true,
    permissions,
    object: {
      objectId: 'flow',
      objectName: '流水',
      titleFieldId: 'company',
      fields: [{ id: 'company', name: '公司', type: 'TEXT' }],
      fieldOptions: {},
      details: [],
      relations: [],
      settings: {}
    },
    details: {}
  })
  api.page.mockResolvedValue({ list: [row('r1')], total: 1, drillTotal: 1 })
  api.get.mockImplementation((_app: string, _object: string, id: string) =>
    Promise.resolve({ record: row(id), details: {} })
  )
  api.delete.mockResolvedValue(true)
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.clearAllMocks()
})

describe('统计下钻：渲染下钻明细视图', () => {
  it('公共筛选变化时透视表重新查询；点格下钻把行/列键、指标与公共筛选一起作为 reportDrill 传给列表查询', async () => {
    dashboard.current = {
      definitions: ref<ReportFilter[]>([
        {
          id: 'company',
          name: '公司',
          objectId: 'flow',
          fieldId: 'company',
          dateRange: false,
          targets: { report: 'company' }
        },
        { id: 'period', name: '期间', objectId: 'flow', fieldId: 'date', dateRange: true, targets: { report: 'date' } }
      ]),
      values: ref<Record<string, unknown>>({ company: 'A' })
    }
    await mount(reportConfig('PIVOT'))
    expect(api.report).toHaveBeenCalledTimes(1)
    expect(api.report.mock.calls[0][0]).toMatchObject({ reportId: 'report', equal: { company: 'A' } })
    dashboard.current.values.value = { company: 'B', period: ['2026-08-01', '2026-09-30'] }
    await flush()
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(api.report.mock.calls[1][0]).toMatchObject({
      equal: { company: 'B' },
      dateFrom: '2026-08-01',
      dateTo: '2026-09-30'
    })
    await clickPivotLeaf()
    expect(drawer()!.getAttribute('data-drawer')).toContain('甲公司 · 2026-08')
    expect(api.page).toHaveBeenCalledTimes(1)
    expect(api.page.mock.calls[0][0]).toMatchObject({
      applicationId: 'app',
      objectId: 'flow',
      viewId: 'drill-view',
      reportDrill: {
        applicationId: 'app',
        reportId: 'report',
        group: ['A'],
        columnGroup: ['2026-08'],
        metricId: 'in',
        equal: { company: 'B' },
        dateFrom: '2026-08-01',
        dateTo: '2026-09-30'
      }
    })
    expect(api.reportDetails).not.toHaveBeenCalled()
  })
  it('报表放在记录详情页时，reportDrill 带上报表查询用的同一个 context（列表自身 context 不代替）', async () => {
    const context = { pageId: 'detail-page', nodeId: 'report-node', recordId: 'company-1' }
    await mount(reportConfig('PIVOT'), { context })
    expect(api.report.mock.calls[0][0].context).toEqual(context)
    await clickPivotLeaf()
    const query = api.page.mock.calls[0][0]
    expect(query.reportDrill).toMatchObject({ group: ['A'], columnGroup: ['2026-08'], context })
    expect(query.context).toBeUndefined()
  })
  it('不在记录页的报表下钻不带 context', async () => {
    await mount(reportConfig('PIVOT'))
    await clickPivotLeaf()
    expect(api.page.mock.calls[0][0].reportDrill).not.toHaveProperty('context')
  })
  it('合计格下钻发出空前缀：group=[] 且 columnGroup=[]', async () => {
    await mount(reportConfig('PIVOT'))
    host.querySelector<HTMLButtonElement>('tfoot td:last-child button')!.click()
    await flush()
    expect(api.page.mock.calls[0][0].reportDrill).toMatchObject({ group: [], columnGroup: [], metricId: 'in' })
  })
  it('明细允许编辑关闭（默认）：只留「查看」，无新建/编辑/删除/导入/批量；查看为只读', async () => {
    await mount(reportConfig('PIVOT'))
    await clickPivotLeaf()
    const list = drawer()!.querySelector('[data-list]')!
    expect(buttons(list)).toEqual(['查看'])
    expect(list.getAttribute('data-import')).toBe('false')
    expect(list.getAttribute('data-batch')).toBe('false')
    list.querySelector<HTMLButtonElement>('section[data-row="r1"] button')!.click()
    await flush()
    const editor = host.querySelector('[data-surface] form')!
    expect(editor.getAttribute('data-editor')).toBe('r1')
    expect(editor.getAttribute('data-read-only')).toBe('true')
  })
  it('明细允许编辑开启：按视图按钮与权限出现新增/编辑/删除/导入/批量', async () => {
    await mount(reportConfig('PIVOT', { detailEditable: true }))
    await clickPivotLeaf()
    const list = drawer()!.querySelector('[data-list]')!
    expect(buttons(list)).toEqual(expect.arrayContaining(['新增', '查看', '编辑', '删除']))
    expect(list.getAttribute('data-import')).toBe('true')
    expect(list.getAttribute('data-batch')).toBe('true')
  })
  it('开启编辑也不越过视图配置：视图没配删除/新建按钮就不出现（只能收不能放）', async () => {
    const report = reportResource(reportConfig('PIVOT', { detailEditable: true }))
    await mount(reportConfig('PIVOT', { detailEditable: true }), {
      resources: [report, drillView(['VIEW', 'UPDATE'])]
    })
    await clickPivotLeaf()
    const names = buttons(drawer()!.querySelector('[data-list]'))
    expect(names).toContain('编辑')
    expect(names).not.toContain('删除')
    expect(names).not.toContain('新增')
  })
  it('抽屉内保存成功后报表自动重新查询，抽屉保持打开', async () => {
    await mount(reportConfig('PIVOT', { detailEditable: true }))
    await clickPivotLeaf()
    expect(api.report).toHaveBeenCalledTimes(1)
    Array.from(drawer()!.querySelectorAll<HTMLButtonElement>('section[data-row="r1"] button'))
      .find(b => b.textContent?.includes('编辑'))!
      .click()
    await flush()
    host.querySelector<HTMLButtonElement>('[data-surface] [data-save]')!.click()
    await flush()
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(drawer()).not.toBeNull()
    expect(host.querySelector('.pivot-table')).not.toBeNull()
  })
  it('抽屉内删除成功后报表自动重新查询', async () => {
    await mount(reportConfig('PIVOT', { detailEditable: true }))
    await clickPivotLeaf()
    drawer()!.querySelector<HTMLButtonElement>('section[data-row="r1"] [data-confirm]')!.click()
    await flush()
    expect(api.delete).toHaveBeenCalledWith(expect.objectContaining({ id: 'r1', expectedRevision: '1' }))
    expect(api.report).toHaveBeenCalledTimes(2)
  })
  it('数据视图条数 < drillTotal 时在抽屉顶部提示被视图自身筛选排除的条数', async () => {
    api.page.mockResolvedValue({ list: [row('r1')], total: 1, drillTotal: 4 })
    await mount(reportConfig('PIVOT'))
    await clickPivotLeaf()
    const alert = drawer()!.querySelector('[data-drill-excluded]')!
    expect(alert.textContent).toBe('业务视图条件及当前搜索或筛选排除了 3 条（下钻范围共 4 条）')
    const first = drawer()!.querySelector('[role="alert"], [data-list]')
    expect(first).toBe(alert)
  })
  it('条数一致时不提示', async () => {
    await mount(reportConfig('PIVOT'))
    await clickPivotLeaf()
    expect(drawer()!.querySelector('[data-drill-excluded]')).toBeNull()
  })
  it('汇总表点指标也走新下钻（group = 分组键，无 columnGroup）', async () => {
    api.report.mockResolvedValue(tableResult())
    await mount(reportConfig('TABLE'))
    host.querySelector<HTMLAnchorElement>('[data-antd-table] td[data-key="in"] a')!.click()
    await flush()
    expect(api.page.mock.calls[0][0].reportDrill).toMatchObject({ group: ['A'], metricId: 'in' })
    expect(api.page.mock.calls[0][0].reportDrill.columnGroup).toBeUndefined()
    expect(api.reportDetails).not.toHaveBeenCalled()
  })
  it('图表点选也走新下钻', async () => {
    api.report.mockResolvedValue(tableResult())
    await mount(reportConfig('BAR'))
    host.querySelector<HTMLButtonElement>('[data-chart]')!.click()
    await flush()
    expect(api.page.mock.calls[0][0].reportDrill).toMatchObject({ group: ['A'], metricId: 'in' })
  })
})

describe('统计下钻：兼容的只读明细表', () => {
  it('未配置下钻明细视图时保留只读明细表，透视格的行/列键照常传给 report-details', async () => {
    await mount(reportConfig('PIVOT', { detailViewId: null }))
    await clickPivotLeaf()
    expect(api.page).not.toHaveBeenCalled()
    expect(api.reportDetails).toHaveBeenCalledTimes(1)
    expect(api.reportDetails.mock.calls[0][0]).toMatchObject({
      group: ['A'],
      columnGroup: ['2026-08'],
      metricId: 'in'
    })
    expect(drawer()!.querySelector('[data-antd-table]')).not.toBeNull()
  })
  it('未配置下钻视图且报表在记录页内：只读明细表同样带上报表的 context', async () => {
    const context = { pageId: 'page', nodeId: 'node', recordId: 'rec' }
    await mount(reportConfig('PIVOT', { detailViewId: null }), { context })
    await clickPivotLeaf()
    expect(api.page).not.toHaveBeenCalled()
    expect(api.reportDetails.mock.calls[0][0]).toMatchObject({ context, group: ['A'] })
  })
})

describe('应用固定看板业务明细计数', () => {
  async function mountDashboardDetails() {
    const BusinessRecords = (await import('@/views/nocode/application/components/BusinessRecords.vue')).default
    const view = drillView()
    const drill = {
      query: { applicationId: 'app', resourceId: 'board', chartId: 'chart', stamp: 'fixed', filterValues: [] },
      group: [null],
      columnGroup: []
    }
    app = createApp(() =>
      h(BusinessRecords, {
        applicationId: 'app',
        objectId: 'flow',
        viewId: view.id,
        view: view.config as unknown as import('@/types/nocode/application-ui').ViewConfig,
        resources: [view],
        dashboardDrill: drill
      })
    )
    register(app)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    await flush()
    return drill
  }
  it('传递完整下钻快照并说明视图条件排除数量，不暴露业务导出', async () => {
    api.page.mockResolvedValue({ list: [row('r1')], total: 1, drillTotal: 2, drillDifferentGrain: false })
    const drill = await mountDashboardDetails()
    expect(api.page.mock.calls[0]![0].dashboardDrill).toEqual(drill)
    expect(host.querySelector('[data-drill-excluded]')?.textContent).toContain('排除了 1 条（下钻范围共 2 条）')
    expect(host.querySelector('[data-list]')?.getAttribute('data-export')).toBe('false')
  })
  it('组合明细行与看板主记录粒度不同时分别说明，不相减', async () => {
    api.page.mockResolvedValue({ list: [row('r1')], total: 1, drillTotal: 2, drillDifferentGrain: true })
    await mountDashboardDetails()
    expect(host.querySelector('[data-drill-excluded]')).toBeNull()
    expect(host.querySelector('[data-drill-grain]')?.textContent).toBe(
      '看板范围命中 2 条主记录；当前业务视图按明细行展示，共 1 条，统计粒度不同。'
    )
  })
})
