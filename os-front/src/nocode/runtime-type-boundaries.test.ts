// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App, type Component } from 'vue'
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'
import DataViewChildren from '@/views/nocode/application/components/DataViewChildren.vue'
import ReportBlock from '@/views/nocode/application/components/ReportBlock.vue'
import type { BusinessRow, RecordModel } from '@/types/nocode/runtime'
import type { DataViewModel } from '@/types/nocode/data-view'
import type { ReportConfig, ReportGroup, ReportMetric } from '@/types/nocode/report'
import type { ObjectField } from '@/types/nocode/object'
import type { ApplicationResource } from '@/types/nocode/application'
import type { FormConfig } from '@/types/nocode/application-ui'
import { defaultFieldOptions, generatedBinding, newDesign } from './data-center'
import { newField } from './object-draft'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  delete: vi.fn(),
  viewChildren: vi.fn(),
  report: vi.fn(),
  reportDetails: vi.fn(),
  reportExport: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'type-boundary-test' } }) }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/nocode/directory-options', () => ({ directoryTypes: [], directoryOptions: async () => [] }))
const mounted: Array<{ app: App; host: HTMLElement }> = []
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function setup<T>(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div'),
    app = createApp({ ...component, render: () => null }, props)
  document.body.append(host)
  const vm = app.mount(host)
  mounted.push({ app, host })
  await flush()
  return (vm.$ as unknown as { setupState: T }).setupState
}
const field = (id: string | null, code = 'name'): ObjectField => ({
  ...newField(0, code),
  id,
  key: id || 'temporary-key',
  code
})
const permissions = () => ({
  actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: []
})
const row = (id: string | null, revision: string | null = '1'): BusinessRow => ({
  id,
  revision,
  values: {},
  permissions: permissions()
})
function model(): RecordModel {
  const design = newDesign()
  return {
    writable: true,
    generatedKey: true,
    keyFieldId: 'name',
    keyType: 'UUID',
    details: {},
    permissions: permissions(),
    object: {
      objectId: 'object',
      objectCode: 'object',
      objectName: '对象',
      schemaName: 'public',
      tableName: 'biz_object',
      source: 'GENERATED',
      readOnly: false,
      titleFieldId: 'name',
      settings: design.settings,
      fields: [field('name'), field(null, 'invalid')],
      fieldOptions: {},
      relations: [],
      details: [],
      mainBinding: generatedBinding('public')
    }
  }
}
function children(): DataViewModel {
  return {
    composition: {
      grain: 'ROOT',
      detailId: null,
      columns: [],
      sections: [
        {
          id: 'section',
          name: '子表',
          detailId: null,
          objectId: 'target',
          viewId: null,
          binding: { direction: 'INCOMING', relationId: 'relation' },
          fieldIds: ['name'],
          conditions: null,
          pageSize: 10,
          showTable: true
        }
      ]
    },
    fields: [],
    fieldOptions: {},
    sections: { section: { fields: [field('name'), field(null)], fieldOptions: {}, recordModel: model() } }
  }
}
const metric = (id: string): ReportMetric => ({ id, name: id, operation: 'COUNT', fieldId: null })
function reportConfig(): ReportConfig {
  return {
    objectId: 'object',
    dimensions: [],
    metrics: [
      metric('count'),
      { ...metric('ratio'), operation: 'FORMULA', formula: { operator: 'DIVIDE', left: 'count', right: 'missing' } }
    ],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Shanghai',
    display: 'METRIC',
    sortMetricId: null,
    descending: false,
    limit: 100,
    detailViewId: null
  }
}
interface RecordsState {
  fields: ObjectField[]
  columns: Array<{ key: string; ellipsis?: boolean }>
  display: (record: BusinessRow, key: string) => string
  choices: (id: string) => unknown[]
  showDetail: (record: BusinessRow) => Promise<void>
  edit: (record?: BusinessRow) => Promise<void>
  changePage: (page: { current?: number; pageSize?: number }, filters: unknown, sorter: unknown) => void
  batchDelete: (ids: string[]) => Promise<void>
  rows: BusinessRow[]
  detailOpen: boolean
  editorOpen: boolean
  error: string
  queryValues: Record<string, unknown>
  search: string
  query: () => void
}
interface ChildrenState {
  fields: ObjectField[]
  rootId: string | null
  edit: (record?: BusinessRow) => Promise<void>
  load: () => Promise<void>
  open: boolean
  error: string
  form: FormConfig | undefined
  formResolution: { resource?: ApplicationResource; error: string }
}
interface ReportState {
  detailRows: BusinessRow[]
  detailColumns: Array<{ key: string }>
  inspect: (group: ReportGroup | undefined, metricId: string) => void
  analysisOperands: Array<{ id: string; metric?: ReportMetric }>
  details: (group?: ReportGroup, metricId?: string) => Promise<void>
  error: string
  selectGroup: (index: number, metricId: string) => void
}
beforeEach(() => {
  vi.clearAllMocks()
  api.model.mockResolvedValue(model())
  api.page.mockResolvedValue({ list: [], total: 0 })
  api.viewChildren.mockResolvedValue({ list: [], total: 0 })
  api.get.mockImplementation(async (_app, _object, id) => ({ record: row(id), details: {} }))
  api.delete.mockResolvedValue(undefined)
  api.report.mockResolvedValue({
    dimensionNames: [],
    metrics: [metric('count')],
    groups: [],
    totals: { count: '3' },
    totalGroups: 0,
    recordCount: 3,
    canExport: true,
    timeZone: 'Asia/Shanghai'
  })
  api.reportDetails.mockResolvedValue({ list: [], total: 0 })
})
afterEach(() =>
  mounted.splice(0).forEach(({ app, host }) => {
    app.unmount()
    host.remove()
  })
)

describe('运行列表与报表的生产类型边界', () => {
  it('关联子表新增编辑按目标对象继承默认，保留真实表单资源身份', async () => {
    const childForm: ApplicationResource = {
      id: 'child-form',
      code: 'child_form',
      name: '子表默认表单',
      kind: 'FORM',
      config: { objectId: 'target', nodes: [], detailIds: [], options: { defaultForObject: true } }
    }
    const state = await setup<ChildrenState>(DataViewChildren, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent: row('parent'),
      model: children(),
      filters: [],
      resources: [childForm]
    })
    await state.edit(row('child'))
    expect(state.open).toBe(true)
    expect(state.formResolution.resource?.id).toBe('child-form')
    expect(state.form?.objectId).toBe('target')
    expect(api.get).toHaveBeenLastCalledWith('app', 'target', 'child')
  })
  it('关联子表显式绑定错误时阻止开窗，不能退回默认；任务范围禁止隐式继承', async () => {
    const childModel = children()
    const section = childModel.composition?.sections[0]
    if (!section) throw new Error('测试夹具缺少关联子表')
    section.viewId = 'child-view'
    const resources = [
      { id: 'child-view', kind: 'VIEW', config: { objectId: 'target', formId: 'missing' } },
      { id: 'child-form', kind: 'FORM', config: { objectId: 'target', options: { defaultForObject: true } } }
    ]
    const props = {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent: row('parent'),
      model: childModel,
      filters: [],
      resources
    }
    const state = await setup<ChildrenState>(DataViewChildren, props)
    await state.edit(row('child'))
    expect(state.open).toBe(false)
    expect(state.error).toContain('指定的业务表单不存在')
    expect(api.get).not.toHaveBeenCalled()
    const task = await setup<ChildrenState>(DataViewChildren, {
      ...props,
      model: children(),
      inheritDefaultForm: false
    })
    expect(task.formResolution.resource).toBeUndefined()
    expect(task.form).toBeUndefined()
  })
  it('多行文本在应用列表保留换行且不使用单行省略', async () => {
    const metadata = model()
    metadata.object.fields.push({ ...field('notes'), type: 'TEXTAREA' })
    metadata.permissions.readFields.push('notes')
    api.model.mockResolvedValueOnce(metadata)
    const state = await setup<RecordsState>(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      view: { fieldIds: ['name', 'notes'] }
    })
    expect(state.columns.find(column => column.key === 'notes')?.ellipsis).toBe(false)
    expect(state.display({ ...row('record'), values: { notes: '第一行\n第二行' } }, 'notes')).toBe('第一行\n第二行')
  })
  it('富文本在应用列表保留段落且不使用单行省略', async () => {
    const metadata = model()
    metadata.object.fields.push({ ...field('notes'), type: 'RICH_TEXT' })
    metadata.permissions.readFields.push('notes')
    api.model.mockResolvedValueOnce(metadata)
    const state = await setup<RecordsState>(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      view: { fieldIds: ['name', 'notes'] }
    })
    expect(state.columns.find(column => column.key === 'notes')?.ellipsis).toBe(false)
    expect(state.display({ ...row('record'), values: { notes: '<p>第一段</p><p>第二段</p>' } }, 'notes')).toBe(
      '第一段\n第二段'
    )
  })
  it('运行字段仅使用发布ID；已失效查询字段不会被作为选择来源解析', async () => {
    const state = await setup<RecordsState>(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    expect(state.fields.map(f => f.id)).toEqual(['name'])
    expect(state.columns.map(c => c.key)).toEqual(['name', 'actions'])
    expect(state.choices('removed')).toEqual([])
  })
  it('列表查看缺失主键不请求；明细行仍按主记录打开', async () => {
    const state = await setup<RecordsState>(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    await state.showDetail(row(null))
    await state.edit(row(null))
    expect(api.get).not.toHaveBeenCalled()
    expect(state.detailOpen).toBe(false)
    expect(state.editorOpen).toBe(false)
    await state.showDetail({ ...row('child'), parentId: 'parent' })
    expect(api.get).toHaveBeenLastCalledWith('app', 'object', 'parent')
    expect(state.detailOpen).toBe(true)
  })
  it('有效选项保留禁用和候选范围，查询仍携带已发布视图与筛选条件', async () => {
    const metadata = model()
    metadata.object.fields.push({ ...field('status'), type: 'SELECT' })
    metadata.object.fieldOptions.status = {
      ...defaultFieldOptions(),
      options: [
        { code: 'a', label: '甲', disabled: false },
        { code: 'b', label: '乙', disabled: false },
        { code: 'c', label: '丙', disabled: true }
      ]
    }
    api.model.mockResolvedValueOnce(metadata)
    const state = await setup<RecordsState>(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'published-view',
      view: {
        fieldIds: ['name', 'status'],
        query: { candidates: { status: ['a', 'c'] } },
        list: { queryFieldIds: ['status'] }
      }
    })
    expect(state.choices('status')).toEqual([{ label: '甲', value: 'a' }])
    state.queryValues = { status: 'a' }
    state.search = '  条件  '
    state.query()
    await flush()
    expect(api.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        viewId: 'published-view',
        search: '条件',
        conditions: { logic: 'AND', items: [{ type: 'condition', field: 'status', operator: 'eq', value: 'a' }] }
      })
    )
  })
  it('排序有明确列键才提交；取消排序后仍可再次升序', async () => {
    const state = await setup<RecordsState>(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    state.changePage({ current: 3, pageSize: 20 }, {}, { order: 'ascend' })
    await flush()
    expect(api.page).toHaveBeenLastCalledWith(expect.objectContaining({ sortFieldId: undefined }))
    state.changePage({ current: 3, pageSize: 20 }, {}, { columnKey: 'name', order: 'descend' })
    await flush()
    expect(api.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 1, sortFieldId: 'name', descending: true })
    )
    state.changePage({ current: 1, pageSize: 20 }, {}, { columnKey: 'name', order: null })
    await flush()
    expect(api.page).toHaveBeenLastCalledWith(expect.objectContaining({ sortFieldId: undefined }))
    state.changePage({ current: 1, pageSize: 20 }, {}, { columnKey: 'name', order: 'ascend' })
    await flush()
    expect(api.page).toHaveBeenLastCalledWith(expect.objectContaining({ sortFieldId: 'name', descending: false }))
  })
  it('批删只提交具有主键与修订的授权主记录', async () => {
    const state = await setup<RecordsState>(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      view: { fieldIds: ['name'], list: { batchDelete: true } }
    })
    state.rows = [row('valid'), row('unversioned', null), { ...row('child'), parentId: 'parent' }]
    await state.batchDelete(['valid', 'unversioned', 'child'])
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(api.delete).toHaveBeenCalledWith({
      applicationId: 'app',
      objectId: 'object',
      id: 'valid',
      expectedRevision: '1'
    })
  })
  it('子表没有已保存主记录时不查询，相关行缺失ID时不发起编辑请求', async () => {
    const state = await setup<ChildrenState>(DataViewChildren, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent: row(null),
      model: children(),
      filters: []
    })
    expect(api.viewChildren).not.toHaveBeenCalled()
    await state.edit(row(null))
    expect(api.get).not.toHaveBeenCalled()
    expect(state.open).toBe(false)
  })
  it('合法关联子表保留根记录查询和编辑目标', async () => {
    const state = await setup<ChildrenState>(DataViewChildren, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent: { ...row('child'), parentId: 'parent' },
      model: children(),
      filters: []
    })
    expect(api.viewChildren).toHaveBeenLastCalledWith(
      expect.objectContaining({ recordId: 'parent', sectionId: 'section' })
    )
    await state.edit(row(null))
    expect(api.get).not.toHaveBeenCalled()
    await state.edit(row('related'))
    expect(api.get).toHaveBeenLastCalledWith('app', 'target', 'related')
    expect(state.open).toBe(true)
  })
  it('父记录改变时，原关联行的编辑响应不会打开新上下文', async () => {
    const parent = reactive(row('parent'))
    const state = await setup<ChildrenState>(DataViewChildren, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent,
      model: children(),
      filters: []
    })
    const finish = vi.fn<(value: { record: BusinessRow; details: Record<string, BusinessRow[]> }) => void>()
    api.get.mockReturnValueOnce(new Promise(resolve => finish.mockImplementation(resolve)))
    const editing = state.edit(row('related'))
    parent.id = 'new-parent'
    await flush()
    finish({ record: row('related'), details: {} })
    await editing
    expect(state.open).toBe(false)
    expect(api.viewChildren).toHaveBeenLastCalledWith(expect.objectContaining({ recordId: 'new-parent' }))
  })
  it('报表缺失指标只展示不可用信息，不以无效指标钻取；合法指标保持分组范围', async () => {
    const config = reportConfig()
    const state = await setup<ReportState>(ReportBlock, {
      applicationId: 'app',
      resource: { id: 'report', kind: 'REPORT', name: '报表', code: 'report', config }
    })
    state.inspect(undefined, 'missing')
    state.selectGroup(99, 'count')
    await flush()
    expect(api.reportDetails).not.toHaveBeenCalled()
    state.inspect(undefined, 'ratio')
    expect(state.analysisOperands.map(o => [o.id, o.metric?.id])).toEqual([
      ['count', 'count'],
      ['missing', undefined]
    ])
    const group = { keys: ['A'], labels: ['甲'], values: { count: '3' } }
    state.inspect(group, 'count')
    await flush()
    expect(api.reportDetails).toHaveBeenLastCalledWith(
      expect.objectContaining({ applicationId: 'app', reportId: 'report', group: ['A'], metricId: 'count' })
    )
    state.detailRows = [{ ...row('record'), values: { name: '名称', null: '不能冒充字段' } }]
    expect(state.detailColumns.map(c => c.key)).toEqual(['id', 'name'])
  })
})
