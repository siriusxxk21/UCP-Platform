// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, reactive, type App } from 'vue'
import { reportFieldOptions } from './report'
import { prepareReportConfig } from './resource-config'
import { detailPivotConfig, grainObjects, rootPivotConfig, staleRootConfig } from './report-detail-grain-fixture'
import { flush, optionLabels, pick, registerStubs } from './report-detail-grain-harness'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig, ReportMetric } from '@/types/nocode/report'

const api = vi.hoisted(() => ({ previewReport: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: api, runtime: api }) }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['label', 'entries'],
      setup: props => () =>
        h('div', {
          'data-condition': props.label,
          'data-entries': (props.entries || []).map((e: { value: string }) => e.value).join(',')
        })
    })
  }
})
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))

import ReportConfigEditor from '@/views/nocode/application/components/ReportConfigEditor.vue'

const M6 =
  '按明细行统计时，主表字段「入金」会按明细行数重复计算，不能求和、求平均或做非空计数。请改用明细「分录」里的字段，或把「统计粒度」改回「按主记录」'
const MONEY_HINT = (name: string) =>
  `「${name}」是数值字段。放在行或列里，会把每一个不同的数值当成一组（有多少种数值就有多少行或列）。要汇总它，请放到下面的「统计指标」里选「求和」。`
/**
 * 基线（xiaxihan@20a15fcb）的配置编辑器对 rootPivotConfig() 点「刷新预览」发出的 config，
 * 由 laneT-detail-report/frontend/golden/preview-request-root.base.json 抄入（同一份夹具在基线检出上跑出来的）。
 * 合入「统计排序与行数」之后的基线（local/release-r2）：新建统计默认不限制行数（limit 为 null），并多一个 sortBy 键（为 null）。
 */
const BASELINE_PREVIEW_CONFIG = {
  objectId: 'voucher',
  dimensions: [{ fieldId: 'memo', relationPath: null, bucket: 'VALUE' }],
  metrics: [
    { id: 'in', name: '入金合计', operation: 'SUM', fieldId: 'income', conditions: null, format: { financial: true } },
    { id: 'count', name: '记录数', operation: 'COUNT', fieldId: null, format: { financial: false } }
  ],
  equal: {},
  filterFieldIds: [],
  dateFieldId: null,
  timeZone: 'Asia/Shanghai',
  display: 'PIVOT',
  sortMetricId: null,
  descending: false,
  sortBy: null,
  limit: null,
  detailViewId: null,
  columnDimensions: [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }],
  pivot: null,
  detailEditable: null,
  chart: { barMode: 'GROUPED', horizontal: false, labels: false, legendPosition: 'TOP' }
}
// 合入「统计排序与行数」之后的基线（local/release-r2）多一个 sortBy 键，排在 descending 之后
const BASELINE_PREVIEW_KEYS = [
  'objectId',
  'dimensions',
  'metrics',
  'equal',
  'filterFieldIds',
  'dateFieldId',
  'timeZone',
  'display',
  'sortMetricId',
  'descending',
  'sortBy',
  'limit',
  'detailViewId',
  'columnDimensions',
  'pivot',
  'detailEditable',
  'chart'
]
const view: ApplicationResource = {
  id: 'voucher-view',
  kind: ResourceKind.VIEW,
  name: '凭证列表',
  code: 'voucher_view',
  config: { objectId: 'voucher', fieldIds: [], equal: {}, query: { fixed: [], defaults: {}, candidates: {} } }
}

let app: App | undefined, host: HTMLDivElement, state: { config: ReportConfig }
async function mount(
  initial: ReportConfig,
  options: { resources?: ApplicationResource[]; fixedFilters?: Array<{ fieldId: string; value: unknown }> } = {}
) {
  state = reactive({ config: initial })
  app = createApp(() =>
    h(ReportConfigEditor, {
      modelValue: state.config,
      'onUpdate:modelValue': (value: ReportConfig) => {
        state.config = value
      },
      applicationId: 'app',
      objects: grainObjects,
      resources: options.resources || [],
      fixedFilters: options.fixedFilters
    })
  )
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const section = (name: string) => host.querySelector(`[data-section="${name}"]`)
const button = (label: string, root: Element | null = host) =>
  Array.from(root?.querySelectorAll('button') || []).find(b => b.textContent?.trim() === label)
const radio = (grain: 'ROOT' | 'DETAIL') =>
  host.querySelector<HTMLInputElement>(`[data-report-grain] [data-radio="${grain}"] input`)!
async function chooseGrain(grain: 'ROOT' | 'DETAIL') {
  radio(grain).dispatchEvent(new Event('change'))
  await flush()
}
const detailSelect = () => host.querySelector<HTMLSelectElement>('[data-report-detail] select')
const formSelect = (label: string) => host.querySelector<HTMLSelectElement>(`[data-form-item="${label}"] select`)!
const metricEditors = () => Array.from(host.querySelectorAll<HTMLElement>('.metric-editor'))
/** 指标那一行的下拉：第 0 个是计算方式，第 1 个（有的话）是统计字段。 */
const metricSelects = (index: number) =>
  Array.from(metricEditors()[index].querySelector('.config-row')!.querySelectorAll('select'))
const alerts = () => Array.from(host.querySelectorAll('[role="alert"]')).map(a => a.textContent)
async function refresh() {
  button('刷新预览')!.click()
  await flush()
}
const previewResult = (extra: Record<string, unknown> = {}) => ({
  dimensionNames: ['摘要'],
  metrics: [] as ReportMetric[],
  groups: [],
  totals: {},
  totalGroups: 0,
  recordCount: 6,
  canExport: false,
  timeZone: 'Asia/Shanghai',
  pivot: null,
  ...extra
})
beforeEach(() => {
  vi.clearAllMocks()
  api.previewReport.mockResolvedValue(previewResult())
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('配置器：统计粒度', () => {
  it('粒度控件：默认「按主记录」；选「按明细行」后出现明细来源并默认第一个启用的明细；说明常显', async () => {
    await mount(rootPivotConfig())
    const grain = host.querySelector('[data-report-grain]')!
    expect(host.querySelector('[data-form-item="统计粒度"]')).not.toBeNull()
    expect(grain.querySelector('[data-radio="ROOT"]')!.textContent).toBe('按主记录（每条凭证算一行）')
    expect(grain.querySelector('[data-radio="DETAIL"]')!.textContent!.trim()).toBe(
      '按明细行（每条明细算一行，带出主表信息）'
    )
    expect(radio('ROOT').checked).toBe(true)
    expect(radio('DETAIL').checked).toBe(false)
    expect(radio('DETAIL').disabled).toBe(false)
    expect(host.querySelector('[data-report-grain-empty]')).toBeNull()
    expect(detailSelect()).toBeNull()
    expect(host.textContent).toContain(
      '按明细行统计时，可以用明细里的字段分组和求和；主表上的金额不能求和（会按明细行数重复计算）。'
    )
    await chooseGrain('DETAIL')
    expect(state.config.grain).toBe('DETAIL')
    expect(state.config.detailId).toBe('lines')
    expect(radio('DETAIL').checked).toBe(true)
    expect(host.querySelector('[data-report-detail]')!.textContent).toContain('明细来源')
    // 只列启用的明细
    expect(optionLabels(detailSelect()!)).toEqual(['分录', '附注'])
    expect(detailSelect()!.value).toBe('lines')
    // 维度下拉换成了明细粒度的范围
    expect(optionLabels(section('rows')!.querySelector('select')!)).toContain('贷方科目 / 科目名称')
    expect(optionLabels(section('rows')!.querySelector('select')!)).toContain('分录 · 贷方金额')
  })

  it('对象没有启用的明细：第二项禁用并说明', async () => {
    await mount({ ...rootPivotConfig(), objectId: 'plain', dimensions: [], columnDimensions: [], display: 'METRIC' })
    expect(radio('DETAIL').disabled).toBe(true)
    expect(host.querySelector('[data-report-grain-empty]')!.textContent).toBe('当前对象没有内部明细')
    expect(host.querySelector('[data-radio="ROOT"]')!.textContent).toBe('按主记录（每条流水算一行）')
  })

  it('主记录粒度的字段下拉不再出现明细上的关系路径；主表上的关系路径仍在', async () => {
    await mount(rootPivotConfig())
    const labels = optionLabels(section('rows')!.querySelector('select')!)
    expect(labels).not.toContain('贷方科目 / 科目名称')
    expect(labels).not.toContain('借方科目 / 科目名称')
    expect(labels).toEqual(['日期', '摘要', '入金', '出金', '公司', '期间', '公司档案 / 公司名称'])
    // 条件编辑器与用户可筛选字段是同一份范围
    expect(host.querySelector('[data-condition="设置固定条件"]')!.getAttribute('data-entries')).toBe(
      'date,memo,income,outgo,company,period,rCompany:companyName'
    )
    expect(optionLabels(formSelect('用户可筛选字段'))).toEqual(labels)
  })

  it('主记录粒度发出的预览请求体不带粒度，其余键与基线逐键相同', async () => {
    await mount(rootPivotConfig())
    await refresh()
    expect(api.previewReport).toHaveBeenCalledTimes(1)
    const sent = api.previewReport.mock.calls[0][0].config
    expect('grain' in sent).toBe(false)
    expect('detailId' in sent).toBe(false)
    expect(Object.keys(sent)).toEqual(BASELINE_PREVIEW_KEYS)
    expect(sent).toEqual(BASELINE_PREVIEW_CONFIG)
    expect(JSON.stringify(sent)).toBe(JSON.stringify(BASELINE_PREVIEW_CONFIG))
    // 编辑中的配置对象本身也没有多出键
    expect(Object.keys(state.config)).toEqual(BASELINE_PREVIEW_KEYS)
    // 页脚说法不变
    expect(host.querySelector('[data-preview-source]')).toBeNull()
    expect(host.querySelector('.report-preview')!.textContent).toContain('来源 6 条 · 显示 0 / 0 组 ·')
  })

  it('切到按明细行再切回按主记录：请求体与保存出的配置仍不带粒度两个键', async () => {
    await mount({ ...rootPivotConfig(), metrics: [{ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }] })
    await chooseGrain('DETAIL')
    await refresh()
    expect(api.previewReport.mock.calls[0][0].config).toMatchObject({ grain: 'DETAIL', detailId: 'lines' })
    await chooseGrain('ROOT')
    expect('grain' in state.config).toBe(false)
    expect('detailId' in state.config).toBe(false)
    await refresh()
    const sent = api.previewReport.mock.calls[1][0].config
    expect('grain' in sent).toBe(false)
    expect('detailId' in sent).toBe(false)
    expect(Object.keys(sent)).toEqual(BASELINE_PREVIEW_KEYS)
    const prepared = prepareReportConfig(
      JSON.parse(JSON.stringify(state.config)),
      reportFieldOptions('voucher', grainObjects),
      []
    )
    expect('grain' in prepared).toBe(false)
    expect('detailId' in prepared).toBe(false)
  })

  it('明细粒度的预览：请求体带 grain / detailId；页脚写「来源明细行 N 行（明细「分录」）」', async () => {
    api.previewReport.mockResolvedValue(previewResult({ detailName: '分录' }))
    await mount(detailPivotConfig())
    await refresh()
    expect(api.previewReport.mock.calls[0][0].config).toMatchObject({
      grain: 'DETAIL',
      detailId: 'lines',
      detailViewId: null
    })
    expect(host.querySelector('[data-preview-source]')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      '来源明细行 6 行（明细「分录」）· 显示 0 / 0 组 · Asia/Shanghai'
    )
  })

  it('预览报错原样显示服务端返回的整句（不截断、不替换）', async () => {
    const m1 =
      '「贷方科目」是明细「分录」里的字段，当前统计按主记录汇总，不能用它分组、筛选或计算。请把「统计粒度」改为「按明细行 · 分录」'
    api.previewReport.mockRejectedValue(new Error(m1))
    await mount(staleRootConfig())
    await refresh()
    expect(alerts()).toContain(m1)
  })
})

describe('配置器：明细粒度的指标', () => {
  it('计算方式有「明细行数」「主记录数」；求和只列分录的数值字段；最大值两组都有；主记录数没有字段下拉', async () => {
    await mount(detailPivotConfig())
    const [operation, field] = metricSelects(0)
    expect(optionLabels(operation)).toEqual([
      '明细行数',
      '非空计数',
      '去重计数',
      '主记录数',
      '求和',
      '平均值',
      '最小值',
      '最大值',
      '指标计算'
    ])
    // 求和：只有分录里的数值字段（引用字段、文本、单选都不是数值）；没有主表的入金、出金、期间
    expect(optionLabels(field)).toEqual(['借方金额', '贷方金额'])
    await pick(operation, 'MAX')
    expect(optionLabels(metricSelects(0)[1])).toEqual([
      '分录: 借方金额',
      '分录: 贷方金额',
      '主表: 入金',
      '主表: 出金',
      '主表: 期间'
    ])
    expect(state.config.metrics[0].fieldId).toBe('creditAmount')
    await pick(metricSelects(0)[0], 'COUNT_DISTINCT')
    expect(optionLabels(metricSelects(0)[1])).toEqual([
      '分录: 借方科目',
      '分录: 借方金额',
      '分录: 贷方科目',
      '分录: 贷方金额',
      '分录: 分录类型',
      '主表: 日期',
      '主表: 摘要',
      '主表: 入金',
      '主表: 出金',
      '主表: 公司',
      '主表: 期间'
    ])
    await pick(metricSelects(0)[0], 'COUNT_FIELD')
    expect(optionLabels(metricSelects(0)[1])).toEqual(['借方科目', '借方金额', '贷方科目', '贷方金额', '分录类型'])
    // 「明细行数」「主记录数」都没有字段下拉
    expect(metricSelects(1)).toHaveLength(1)
    expect(metricSelects(2)).toHaveLength(1)
    expect(metricSelects(2)[0].value).toBe('COUNT_ROOT')
    await pick(metricSelects(0)[0], 'COUNT_ROOT')
    expect(metricSelects(0)).toHaveLength(1)
    expect(state.config.metrics[0].fieldId).toBeNull()
    // 明细粒度下不提供「部门资产模板」
    expect(button('部门资产模板')).toBeUndefined()
    expect(host.textContent).toContain('涉及的主记录数，同一条主记录只算一次')
  })

  it('主记录粒度的指标界面不变：计算方式原样、字段是主表字段的平铺列表、「部门资产模板」仍在', async () => {
    await mount(rootPivotConfig())
    const [operation, field] = metricSelects(0)
    expect(optionLabels(operation)).toEqual([
      '记录计数',
      '非空计数',
      '去重计数',
      '求和',
      '平均值',
      '最小值',
      '最大值',
      '指标计算'
    ])
    expect(optionLabels(field)).toEqual(['入金', '出金', '期间'])
    expect(field.querySelector('optgroup')).toBeNull()
    expect(button('部门资产模板')).toBeDefined()
    expect(host.querySelector('[data-metric-grain-error]')).toBeNull()
  })

  it('切到明细粒度后主表求和被拦：那一条标红并显示 M6，点「刷新预览」不发请求', async () => {
    await mount(rootPivotConfig())
    await chooseGrain('DETAIL')
    // 字段不动
    expect(state.config.metrics.map(m => [m.operation, m.fieldId])).toEqual([
      ['SUM', 'income'],
      ['COUNT', null]
    ])
    expect(state.config.dimensions).toEqual(rootPivotConfig().dimensions)
    const marked = metricEditors().map(e => e.querySelector('[data-metric-grain-error]')?.textContent)
    expect(marked).toEqual([M6, undefined])
    await refresh()
    expect(api.previewReport).not.toHaveBeenCalled()
    expect(alerts().filter(text => text === M6)).toHaveLength(2)
    // 换成分录里的金额后放行
    await pick(metricSelects(0)[1], 'creditAmount')
    expect(host.querySelector('[data-metric-grain-error]')).toBeNull()
    await refresh()
    expect(api.previewReport).toHaveBeenCalledTimes(1)
  })
})

describe('配置器：切换粒度时的清理与告知', () => {
  const rich = (): ReportConfig => ({
    ...detailPivotConfig(),
    dimensions: [
      { fieldId: 'accountName', relationPath: 'rCredit', bucket: 'VALUE' },
      { fieldId: 'memo', relationPath: null, bucket: 'VALUE' }
    ],
    columnDimensions: [{ fieldId: 'lineType', relationPath: null, bucket: 'VALUE' }],
    metrics: [
      { id: 'credit', name: '贷方金额合计', operation: 'SUM', fieldId: 'creditAmount', conditions: null },
      {
        id: 'rows',
        name: '明细行数',
        operation: 'COUNT',
        fieldId: null,
        conditions: {
          logic: 'AND',
          items: [
            { type: 'condition', field: 'lineType', operator: 'eq', value: 'NORMAL' },
            { type: 'condition', field: 'memo', operator: 'like', value: '收入' }
          ]
        }
      },
      { id: 'roots', name: '主记录数', operation: 'COUNT_ROOT', fieldId: null },
      {
        id: 'average',
        name: '每张凭证贷方',
        operation: 'FORMULA',
        fieldId: null,
        formula: { operator: 'DIVIDE', left: 'credit', right: 'roots' }
      }
    ],
    sortMetricId: 'credit',
    filterFieldIds: ['lineType', 'memo', 'rCredit:accountName'],
    conditions: {
      logic: 'AND',
      items: [
        { type: 'condition', field: 'creditAmount', operator: 'gt', value: 0 },
        {
          type: 'group',
          groupLogic: 'OR',
          groupItems: [{ type: 'condition', field: 'rDebit:accountCode', operator: 'eq', value: '1001' }]
        }
      ]
    }
  })

  it('切回主记录粒度会清理并告知：分录字段的维度、指标、筛选、条件项被移除，提示里列出移除了什么', async () => {
    await mount(rich())
    await chooseGrain('ROOT')
    expect('grain' in state.config).toBe(false)
    expect('detailId' in state.config).toBe(false)
    expect(state.config.grain ?? null).toBeNull()
    expect(state.config.dimensions).toEqual([{ fieldId: 'memo', relationPath: null, bucket: 'VALUE' }])
    expect(state.config.columnDimensions).toEqual([])
    // 用了分录字段的指标、「主记录数」、以及引用它们的计算指标都移除；只剩「明细行数」（主记录粒度下即记录计数）
    expect(state.config.metrics.map(m => m.id)).toEqual(['rows'])
    expect(state.config.metrics[0].conditions).toEqual({
      logic: 'AND',
      items: [{ type: 'condition', field: 'memo', operator: 'like', value: '收入' }]
    })
    expect(state.config.sortMetricId).toBeNull()
    expect(state.config.filterFieldIds).toEqual(['memo'])
    expect(state.config.conditions).toBeNull()
    const notice = host.querySelector('[data-grain-notice]')!.textContent!
    expect(notice).toContain('已移除不再可用的字段：')
    for (const name of [
      '贷方科目 / 科目名称',
      '分录 · 分录类型',
      '分录 · 贷方金额',
      '借方科目 / 科目编码',
      '贷方金额合计',
      '主记录数',
      '每张凭证贷方'
    ])
      expect(notice).toContain(name)
    expect(notice).not.toContain('摘要')
    await refresh()
    expect(api.previewReport).toHaveBeenCalledTimes(1)
    expect('grain' in api.previewReport.mock.calls[0][0].config).toBe(false)
  })

  it('换成另一个明细：原明细的字段被移除并告知；主表字段保留', async () => {
    await mount(rich())
    await pick(detailSelect()!, 'notes')
    expect(state.config.grain).toBe('DETAIL')
    expect(state.config.detailId).toBe('notes')
    expect(state.config.dimensions).toEqual([{ fieldId: 'memo', relationPath: null, bucket: 'VALUE' }])
    expect(state.config.metrics.map(m => m.id)).toEqual(['rows', 'roots'])
    expect(state.config.filterFieldIds).toEqual(['memo'])
    expect(host.querySelector('[data-grain-notice]')!.textContent).toContain('已移除不再可用的字段：')
    expect(optionLabels(section('rows')!.querySelector('select')!)).toContain('附注 · 附注金额')
  })

  it('移空时补回默认指标与默认维度并告知；固定筛选不替人删，只提醒', async () => {
    await mount(
      {
        ...detailPivotConfig(),
        metrics: [{ id: 'credit', name: '贷方金额合计', operation: 'SUM', fieldId: 'creditAmount', conditions: null }]
      },
      { fixedFilters: [{ fieldId: 'lineType', value: 'NORMAL' }] }
    )
    await chooseGrain('ROOT')
    expect(state.config.metrics).toEqual([
      { id: 'count', name: '记录数', operation: 'COUNT', fieldId: null, format: { financial: false } }
    ])
    expect(state.config.dimensions).toHaveLength(1)
    expect(state.config.dimensions[0].relationPath).toBeNull()
    const notice = host.querySelector('[data-grain-notice]')!.textContent!
    expect(notice).toContain('已补回默认的「记录数」指标')
    expect(notice).toContain('下方「固定筛选」里还有 1 项用到这些字段，请手动移除')
    // 固定筛选没有被删，所以它的字段不算在「已移除」里
    expect(notice).not.toContain('分录类型')
  })

  it('没有东西被移除时不出提示', async () => {
    await mount({ ...rootPivotConfig(), metrics: [{ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }] })
    await chooseGrain('DETAIL')
    expect(host.querySelector('[data-grain-notice]')).toBeNull()
    await chooseGrain('ROOT')
    expect(host.querySelector('[data-grain-notice]')).toBeNull()
  })

  it('明细粒度可选下钻视图：只列按同一明细逐行的数据视图；换粒度 / 换明细时不匹配的视图被取消并提示（laneDV）', async () => {
    const lineView: ApplicationResource = {
      id: 'line-view',
      kind: ResourceKind.VIEW,
      name: '分录逐行',
      code: 'line_view',
      config: {
        objectId: 'voucher',
        fieldIds: [],
        equal: {},
        composition: { grain: 'DETAIL', detailId: 'lines', sections: [], columns: [] }
      }
    }
    await mount(
      { ...rootPivotConfig(), detailViewId: 'voucher-view', detailEditable: true },
      { resources: [view, lineView] }
    )
    // 主记录粒度照旧：同一对象的视图都能选
    expect(optionLabels(formSelect('下钻明细视图'))).toEqual(['凭证列表', '分录逐行'])
    expect(host.querySelector('[data-detail-grain-drill]')).toBeNull()
    await chooseGrain('DETAIL')
    expect(state.config.detailViewId).toBeNull()
    expect(state.config.detailEditable).toBeNull()
    expect(host.querySelector('[data-grain-notice]')!.textContent).toContain(
      '下钻明细视图「凭证列表」不是按这一粒度逐行显示的，已取消，请重新选择'
    )
    expect(formSelect('下钻明细视图').disabled).toBe(false)
    expect(optionLabels(formSelect('下钻明细视图'))).toEqual(['分录逐行'])
    expect(host.querySelector('[data-detail-grain-drill]')!.textContent!.replace(/\s+/g, '')).toBe(
      '按明细行统计时，只能选「一行表示一条内部明细」且明细来源为「分录」的数据视图；不选则下钻显示命中的明细行（只读）。'
    )
    expect(host.querySelector('[data-detail-grain-drill-empty]')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('[data-detail-editable] input')!.disabled).toBe(true)
    await pick(formSelect('下钻明细视图'), 'line-view')
    expect(state.config.detailViewId).toBe('line-view')
    const editable = host.querySelector<HTMLInputElement>('[data-detail-editable] input')!
    expect(editable.disabled).toBe(false)
    editable.checked = true
    editable.dispatchEvent(new Event('change'))
    await flush()
    expect(state.config.detailEditable).toBe(true)
    expect(host.querySelector('[data-drill-view-error]')).toBeNull()
    // 换到另一个明细：分录逐行的视图不再匹配，被取消；「允许编辑」随之回到只读；附注没有逐行视图，给出怎么建的提示
    await pick(detailSelect()!, 'notes')
    expect(state.config.detailId).toBe('notes')
    expect(state.config.detailViewId).toBeNull()
    expect(state.config.detailEditable).toBeNull()
    expect(optionLabels(formSelect('下钻明细视图'))).toEqual([])
    expect(host.querySelector('[data-detail-grain-drill-empty]')!.textContent!.replace(/\s+/g, '')).toBe(
      '还没有这样的视图：在应用里新建一个数据视图，「一行表示」选「一条内部明细」、明细来源选「附注」。'
    )
    // 回到主记录粒度：明细粒度视图对主记录粒度照旧可选，不会被取消
    await pick(detailSelect()!, 'lines')
    await pick(formSelect('下钻明细视图'), 'line-view')
    await chooseGrain('ROOT')
    expect(state.config.detailViewId).toBe('line-view')
  })

  it('已选的下钻视图不按当前明细逐行（存量或视图后来改了形状）：显示服务端同一句报错', async () => {
    await mount({ ...detailPivotConfig(), detailViewId: 'voucher-view' }, { resources: [view] })
    expect(host.querySelector('[data-drill-view-error]')!.textContent).toBe(
      '按明细行统计时，下钻明细视图须是按明细「分录」逐行显示的数据视图（视图设置里「一行表示」选「一条内部明细」、明细来源选「分录」）'
    )
  })
})

describe('配置器：存量坏配置的引导', () => {
  it('主记录粒度 + 维度是明细上的关系路径：显示警告与按钮；点了切到按明细行，原维度保留', async () => {
    await mount(staleRootConfig())
    const stale = host.querySelector('[data-grain-stale]')!
    expect(stale.querySelector('[role="alert"]')!.textContent).toBe(
      '「贷方科目」是明细「分录」里的字段，按主记录统计时不能使用。'
    )
    // 框里仍显示它原来的名字（不露出内部键），但这一项是禁用的，不能再新选
    const rowSelect = section('rows')!.querySelector('select')!
    expect(rowSelect.value).toBe('rCredit:accountName')
    expect(rowSelect.selectedOptions[0].textContent).toBe('贷方科目 / 科目名称')
    expect(rowSelect.selectedOptions[0].disabled).toBe(true)
    expect(optionLabels(rowSelect)).toEqual([
      '日期',
      '摘要',
      '入金',
      '出金',
      '公司',
      '期间',
      '公司档案 / 公司名称',
      '贷方科目 / 科目名称'
    ])
    button('改为按明细行 · 分录', stale)!.click()
    await flush()
    expect(state.config.grain).toBe('DETAIL')
    expect(state.config.detailId).toBe('lines')
    expect(state.config.dimensions).toEqual(staleRootConfig().dimensions)
    expect(host.querySelector('[data-grain-stale]')).toBeNull()
    expect(host.querySelector('[data-grain-notice]')).toBeNull()
    expect(section('rows')!.querySelector('select')!.value).toBe('rCredit:accountName')
  })

  it('筛选、条件里用了明细字段同样引导；直接点「按明细行」时优先切到被用到的那个明细', async () => {
    await mount({
      ...rootPivotConfig(),
      filterFieldIds: ['noteAmount'],
      metrics: [{ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }]
    })
    expect(host.querySelector('[data-grain-stale] [role="alert"]')!.textContent).toBe(
      '「附注金额」是明细「附注」里的字段，按主记录统计时不能使用。'
    )
    await chooseGrain('DETAIL')
    expect(state.config.detailId).toBe('notes')
    expect(state.config.filterFieldIds).toEqual(['noteAmount'])
  })

  it('已停用明细里的字段：只提示，不给切换按钮', async () => {
    await mount({ ...rootPivotConfig(), filterFieldIds: ['retiredText'] })
    const stale = host.querySelector('[data-grain-stale]')!
    expect(stale.textContent).toContain('「旧字段」是明细「旧明细」里的字段')
    expect(stale.querySelector('button')).toBeNull()
  })

  it('配置里的粒度明细已停用或不存在：照服务端同一句提示', async () => {
    await mount({ ...detailPivotConfig(), detailId: 'retired', dimensions: rootPivotConfig().dimensions })
    expect(host.querySelector('[data-grain-error]')!.textContent).toBe('统计所选的内部明细「旧明细」已停用')
    app!.unmount()
    host.remove()
    await mount({ ...detailPivotConfig(), detailId: 'gone', dimensions: rootPivotConfig().dimensions })
    expect(host.querySelector('[data-grain-error]')!.textContent).toBe('统计所选的内部明细不存在')
  })

  it('正常的主记录粒度配置没有引导', async () => {
    await mount(rootPivotConfig())
    expect(host.querySelector('[data-grain-stale]')).toBeNull()
    expect(host.querySelector('[data-grain-error]')).toBeNull()
  })
})

describe('配置器：数值字段放进行 / 列的提示', () => {
  const countOnly = (): ReportConfig => ({
    ...rootPivotConfig(),
    metrics: [{ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }]
  })
  const hints = (name: string) => Array.from(section(name)!.querySelectorAll('[data-dimension-money-hint]'))

  // 提示用普通块（.dimension-hint），不用警告框；任务书原写「显示警告」，对话层 2026-10-03 裁定接受普通块。
  it('行维度选「入金」出现提示；点「改为指标（求和）」后该维度消失、指标多一条求和', async () => {
    await mount(countOnly())
    expect(host.querySelector('[data-dimension-money-hint]')).toBeNull()
    await pick(section('rows')!.querySelector('select')!, 'income')
    expect(hints('rows')).toHaveLength(1)
    expect(hints('rows')[0].querySelector('.dimension-hint-text')!.textContent).toBe(MONEY_HINT('入金'))
    const convert = button('改为指标（求和）', hints('rows')[0])!
    expect(convert.disabled).toBe(false)
    expect(hints('rows')[0].querySelector('[data-dimension-money-reason]')).toBeNull()
    convert.click()
    await flush()
    expect(state.config.dimensions.some(d => d.fieldId === 'income')).toBe(false)
    // 透视表至少一个行维度：移空后自动补一个默认维度
    expect(state.config.dimensions).toHaveLength(1)
    expect(state.config.metrics.map(m => [m.operation, m.fieldId, m.name])).toEqual([
      ['COUNT', null, '记录数'],
      ['SUM', 'income', '入金合计']
    ])
    expect(host.querySelector('[data-dimension-money-hint]')).toBeNull()
  })

  it('列维度同样提示；已有同一字段的求和时不重复添加', async () => {
    await mount(rootPivotConfig())
    await pick(section('columns')!.querySelector('select')!, 'outgo')
    expect(hints('columns')[0].textContent).toContain(MONEY_HINT('出金'))
    await pick(section('columns')!.querySelector('select')!, 'income')
    button('改为指标（求和）', hints('columns')[0])!.click()
    await flush()
    expect(state.config.columnDimensions).toEqual([])
    expect(state.config.metrics.filter(m => m.operation === 'SUM' && m.fieldId === 'income')).toHaveLength(1)
  })

  it('指标已满 5 个时按钮禁用并说明', async () => {
    await mount({
      ...countOnly(),
      dimensions: [{ fieldId: 'income', relationPath: null, bucket: 'VALUE' }],
      metrics: Array.from({ length: 5 }, (_, i) => ({
        id: 'm' + i,
        name: '指标' + i,
        operation: 'COUNT' as const,
        fieldId: null,
        conditions: { logic: 'AND' as const, items: [{ type: 'condition', field: 'memo', operator: 'eq', value: i }] }
      })) as ReportMetric[]
    })
    expect(button('改为指标（求和）', hints('rows')[0])!.disabled).toBe(true)
    expect(hints('rows')[0].querySelector('[data-dimension-money-reason]')!.textContent!.trim()).toBe('指标已满 5 个')
    button('改为指标（求和）', hints('rows')[0])!.click()
    await flush()
    expect(state.config.dimensions[0].fieldId).toBe('income')
    expect(state.config.metrics).toHaveLength(5)
  })

  it('明细粒度下对主表的「入金」：按钮禁用并显示 M6；分录里的金额可以一键改为指标', async () => {
    await mount({
      ...detailPivotConfig(),
      columnDimensions: [
        { fieldId: 'income', relationPath: null, bucket: 'VALUE' },
        { fieldId: 'debitAmount', relationPath: null, bucket: 'VALUE' }
      ]
    })
    const [income, debit] = hints('columns')
    expect(income.querySelector('.dimension-hint-text')!.textContent).toBe(MONEY_HINT('入金'))
    expect(button('改为指标（求和）', income)!.disabled).toBe(true)
    expect(income.querySelector('[data-dimension-money-reason]')!.textContent!.trim()).toBe(M6)
    expect(debit.querySelector('.dimension-hint-text')!.textContent).toBe(MONEY_HINT('借方金额'))
    expect(button('改为指标（求和）', debit)!.disabled).toBe(false)
    button('改为指标（求和）', debit)!.click()
    await flush()
    expect(state.config.columnDimensions!.map(d => d.fieldId)).toEqual(['income'])
    expect(state.config.metrics.at(-1)).toMatchObject({
      operation: 'SUM',
      fieldId: 'debitAmount',
      name: '借方金额合计'
    })
  })

  it('整数字段、文本字段、日期字段不提示', async () => {
    await mount(countOnly())
    for (const fieldId of ['period', 'memo', 'date', 'company']) {
      await pick(section('rows')!.querySelector('select')!, fieldId)
      expect(state.config.dimensions[0].fieldId).toBe(fieldId)
      expect(host.querySelector('[data-dimension-money-hint]')).toBeNull()
    }
  })
})
