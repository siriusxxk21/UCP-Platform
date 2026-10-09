import { describe, expect, it } from 'vitest'
import {
  defaultReport,
  operationOptions,
  reportDetailId,
  reportDetails,
  reportEntryOptions,
  reportFieldOptions,
  reportOperationOptions
} from './report'
import {
  metricDescription,
  metricGrainError,
  metricNeedsField,
  metricZeroWhenEmpty,
  reportFieldScope,
  reportGrainMessages,
  validateReport
} from './report-presentation'
import { prepareReportConfig } from './resource-config'
import { detailPivotConfig, grainObjects, rootPivotConfig, staleRootConfig } from './report-detail-grain-fixture'
import type { ReportConfig, ReportMetric } from '@/types/nocode/report'

const pairs = (entries: ReturnType<typeof reportFieldOptions>) => entries.map(e => [e.value, e.label])
const voucher = grainObjects.voucher.definition
const scope = (config: ReportConfig) => reportFieldScope(config, voucher)

/**
 * 基线（xiaxihan@20a15fcb）的 reportFieldOptions('voucher', grainObjects) 原样输出，
 * 由 laneT-detail-report/frontend/golden/field-options.base.json 抄入（同一份夹具在基线检出上跑出来的）。
 * 末尾四条就是缺口：明细「分录」上的关系被当成主表关系展开了。
 */
const BASELINE_ROOT_OPTIONS = [
  ['date', '日期'],
  ['memo', '摘要'],
  ['income', '入金'],
  ['outgo', '出金'],
  ['company', '公司'],
  ['period', '期间'],
  ['rDebit:accountCode', '借方科目 / 科目编码'],
  ['rDebit:accountName', '借方科目 / 科目名称'],
  ['rCredit:accountCode', '贷方科目 / 科目编码'],
  ['rCredit:accountName', '贷方科目 / 科目名称'],
  ['rCompany:companyName', '公司档案 / 公司名称']
]

describe('统计字段选项：按粒度给范围', () => {
  it('主记录粒度不展开明细上的关系：主表字段与主表关系路径逐项同基线，明细上的关系路径不再出现', () => {
    const options = reportFieldOptions('voucher', grainObjects)
    const values = options.map(o => o.value)
    expect(values).not.toContain('rCredit:accountName')
    expect(values).not.toContain('rDebit:accountName')
    expect(values.some(v => v.startsWith('rCredit') || v.startsWith('rDebit'))).toBe(false)
    // 逐项（顺序、value、label）= 基线输出去掉明细上的关系路径；主表字段六条与主表关系路径一条原样。
    expect(pairs(options)).toEqual(BASELINE_ROOT_OPTIONS.filter(([value]) => !/^r(Debit|Credit):/.test(value)))
    expect(pairs(options).slice(0, 6)).toEqual(BASELINE_ROOT_OPTIONS.slice(0, 6))
    expect(values).toContain('rCompany:companyName')
    expect(options.every(o => o.detailId === undefined && !('detailId' in o))).toBe(true)
    // 显式传 null / undefined 与不传相同
    expect(pairs(reportFieldOptions('voucher', grainObjects, {}, null))).toEqual(pairs(options))
    expect(pairs(reportFieldOptions('voucher', grainObjects, {}, undefined))).toEqual(pairs(options))
  })

  it('明细粒度的选项：主表字段 → 分录字段 → 主表关系路径 → 分录关系路径；没有另一个明细的字段', () => {
    const options = reportFieldOptions('voucher', grainObjects, {}, 'lines')
    expect(pairs(options)).toEqual([
      ['date', '日期'],
      ['memo', '摘要'],
      ['income', '入金'],
      ['outgo', '出金'],
      ['company', '公司'],
      ['period', '期间'],
      ['debitAccount', '分录 · 借方科目'],
      ['debitAmount', '分录 · 借方金额'],
      ['creditAccount', '分录 · 贷方科目'],
      ['creditAmount', '分录 · 贷方金额'],
      ['lineType', '分录 · 分录类型'],
      ['rCompany:companyName', '公司档案 / 公司名称'],
      ['rDebit:accountCode', '借方科目 / 科目编码'],
      ['rDebit:accountName', '借方科目 / 科目名称'],
      ['rCredit:accountCode', '贷方科目 / 科目编码'],
      ['rCredit:accountName', '贷方科目 / 科目名称']
    ])
    const labels = options.map(o => o.label)
    expect(labels).toContain('分录 · 贷方金额')
    expect(labels).toContain('贷方科目 / 科目名称')
    expect(labels).toContain('借方科目 / 科目名称')
    expect(options.some(o => ['note', 'noteAmount'].includes(o.value))).toBe(false)
    expect(labels.some(l => l.includes('附注'))).toBe(false)
  })

  it('换成另一个明细：只有那个明细的字段，分录上的关系路径不出现', () => {
    const options = reportFieldOptions('voucher', grainObjects, {}, 'notes')
    expect(pairs(options).slice(6)).toEqual([
      ['note', '附注 · 备注'],
      ['noteAmount', '附注 · 附注金额'],
      ['rCompany:companyName', '公司档案 / 公司名称']
    ])
  })

  it('条目带 detailId：只有粒度明细自己的字段带；reportEntryOptions 取到的是明细里的字段配置', () => {
    const options = reportFieldOptions('voucher', grainObjects, {}, 'lines')
    const owner = Object.fromEntries(options.map(o => [o.value, o.detailId]))
    expect(owner.creditAmount).toBe('lines')
    expect(owner.lineType).toBe('lines')
    expect(owner.income).toBeUndefined()
    expect(owner['rCredit:accountName']).toBeUndefined()
    expect(options.filter(o => o.detailId).every(o => o.objectId === 'voucher')).toBe(true)
    const lineType = options.find(o => o.value === 'lineType')!
    expect(reportEntryOptions(lineType, grainObjects)?.options.map(o => o.label)).toEqual(['正常', '调整'])
    // 不带 detailId 时取对象的字段配置：主表上没有这个字段的配置
    expect(reportEntryOptions({ ...lineType, detailId: undefined }, grainObjects)).toBeUndefined()
    expect(reportEntryOptions({ ...lineType, objectId: 'missing' }, grainObjects)).toBeUndefined()
  })

  it('停用的明细字段不出现；停用或不存在的明细按主记录粒度的范围给', () => {
    const options = reportFieldOptions('voucher', grainObjects, {}, 'lines')
    expect(options.some(o => o.value === 'lineRetired')).toBe(false)
    const root = pairs(reportFieldOptions('voucher', grainObjects))
    expect(pairs(reportFieldOptions('voucher', grainObjects, {}, 'retired'))).toEqual(root)
    expect(pairs(reportFieldOptions('voucher', grainObjects, {}, 'missing'))).toEqual(root)
    expect(reportDetails(voucher).map(d => d.name)).toEqual(['分录', '附注'])
    expect(reportDetails(undefined)).toEqual([])
  })

  it('reportDetailId：只有 DETAIL 且带明细 ID 才算明细粒度', () => {
    expect(reportDetailId(rootPivotConfig())).toBeUndefined()
    expect(reportDetailId({ grain: null, detailId: null })).toBeUndefined()
    expect(reportDetailId({ grain: 'ROOT', detailId: 'lines' })).toBeUndefined()
    expect(reportDetailId({ grain: 'DETAIL', detailId: null })).toBeUndefined()
    expect(reportDetailId(detailPivotConfig())).toBe('lines')
    expect(reportDetailId(undefined)).toBeUndefined()
    // 新建统计、换统计对象都从 defaultReport 起步：没有粒度两个键，即按主记录
    expect('grain' in defaultReport('voucher')).toBe(false)
    expect('detailId' in defaultReport('voucher')).toBe(false)
    expect(reportDetailId(defaultReport('voucher'))).toBeUndefined()
  })
})

describe('计算方式与指标说明', () => {
  it('主记录粒度的计算方式下拉原样（没有「主记录数」）；明细粒度下「记录计数」叫「明细行数」，「去重计数」后多「主记录数」', () => {
    expect(reportOperationOptions(false)).toBe(operationOptions)
    expect(operationOptions.map(o => o.label)).toEqual([
      '记录计数',
      '非空计数',
      '去重计数',
      '求和',
      '平均值',
      '最小值',
      '最大值',
      '指标计算'
    ])
    expect(reportOperationOptions(true).map(o => [o.value, o.label])).toEqual([
      ['COUNT', '明细行数'],
      ['COUNT_FIELD', '非空计数'],
      ['COUNT_DISTINCT', '去重计数'],
      ['COUNT_ROOT', '主记录数'],
      ['SUM', '求和'],
      ['AVG', '平均值'],
      ['MIN', '最小值'],
      ['MAX', '最大值'],
      ['FORMULA', '指标计算']
    ])
  })

  it('「主记录数」不需要字段、无数据时为 0；指标说明随粒度', () => {
    const roots: ReportMetric = { id: 'roots', name: '主记录数', operation: 'COUNT_ROOT', fieldId: null }
    const count: ReportMetric = { id: 'rows', name: '行数', operation: 'COUNT', fieldId: null }
    expect(metricNeedsField(roots)).toBe(false)
    expect(metricZeroWhenEmpty(roots)).toBe(true)
    expect(metricNeedsField({ ...roots, operation: 'SUM' })).toBe(true)
    expect(metricDescription(roots, detailPivotConfig())).toBe(
      '涉及的主记录数，同一条主记录只算一次 · 沿用公共统计范围'
    )
    expect(metricDescription(count, detailPivotConfig())).toBe('明细行数 · 沿用公共统计范围')
    expect(metricDescription(count, rootPivotConfig())).toBe('记录计数 · 沿用公共统计范围')
  })
})

describe('validateReport：统计粒度', () => {
  const m6 =
    '按明细行统计时，主表字段「入金」会按明细行数重复计算，不能求和、求平均或做非空计数。请改用明细「分录」里的字段，或把「统计粒度」改回「按主记录」'

  it('主记录粒度的存量配置照旧通过（带不带 scope 都一样）', () => {
    expect(() => validateReport(rootPivotConfig())).not.toThrow()
    expect(() => validateReport(rootPivotConfig(), scope(rootPivotConfig()))).not.toThrow()
    expect(() => validateReport({ ...rootPivotConfig(), grain: null, detailId: null })).not.toThrow()
    // 主记录粒度 + 明细上的关系路径：前端不拦（界面给引导，服务端报 M1）
    expect(() => validateReport(staleRootConfig(), scope(staleRootConfig()))).not.toThrow()
    expect(() => validateReport(detailPivotConfig(), scope(detailPivotConfig()))).not.toThrow()
  })

  it('M6：明细粒度下对主表字段求和、求平均、非空计数被拦；极值与去重计数放行', () => {
    const config = (operation: ReportMetric['operation'], fieldId = 'income'): ReportConfig => ({
      ...detailPivotConfig(),
      metrics: [{ id: 'm', name: '指标', operation, fieldId }]
    })
    for (const operation of ['SUM', 'AVG', 'COUNT_FIELD'] as const) {
      expect(() => validateReport(config(operation), scope(config(operation)))).toThrow(m6)
      expect(metricGrainError(config(operation).metrics[0], config(operation), scope(config(operation)))).toBe(m6)
    }
    for (const operation of ['MIN', 'MAX', 'COUNT_DISTINCT'] as const)
      expect(() => validateReport(config(operation), scope(config(operation)))).not.toThrow()
    // 明细自己的字段可以求和
    expect(() => validateReport(config('SUM', 'creditAmount'), scope(config('SUM', 'creditAmount')))).not.toThrow()
    // 同一条指标在主记录粒度下不拦
    const root = { ...config('SUM'), grain: null, detailId: null }
    expect(metricGrainError(root.metrics[0], root, scope(root))).toBeNull()
    expect(reportGrainMessages.M6('入金', '分录')).toBe(m6)
    expect(m6).not.toContain('未授权')
  })

  it('M8：明细粒度可以带下钻明细视图（视图形状在配置器与保存时判）；句子与服务端逐字相同', () => {
    expect(() => validateReport({ ...detailPivotConfig(), detailViewId: 'view', detailEditable: true })).not.toThrow()
    expect(reportGrainMessages.M8('分录')).toBe(
      '按明细行统计时，下钻明细视图须是按明细「分录」逐行显示的数据视图（视图设置里「一行表示」选「一条内部明细」、明细来源选「分录」）'
    )
  })

  it('M9：主记录粒度下用了「主记录数」', () => {
    const config: ReportConfig = {
      ...rootPivotConfig(),
      metrics: [{ id: 'roots', name: '主记录数', operation: 'COUNT_ROOT', fieldId: null }]
    }
    expect(() => validateReport(config)).toThrow('「主记录数」只用于按明细行统计；按主记录统计时请用「记录计数」')
  })

  it('M10：「主记录数」带了字段', () => {
    const config: ReportConfig = {
      ...detailPivotConfig(),
      metrics: [{ id: 'roots', name: '主记录数', operation: 'COUNT_ROOT', fieldId: 'creditAmount' }]
    }
    expect(() => validateReport(config)).toThrow('「主记录数」无需指定字段')
  })

  it('M11b：明细粒度没选明细', () => {
    expect(() => validateReport({ ...detailPivotConfig(), detailId: null })).toThrow('按明细行统计需要选择一个内部明细')
    expect(() => validateReport({ ...detailPivotConfig(), detailId: '' })).toThrow('按明细行统计需要选择一个内部明细')
  })
})

describe('prepareReportConfig：保存前按粒度归一', () => {
  /**
   * 基线（xiaxihan@20a15fcb）对 rootPivotConfig() 的 prepareReportConfig 输出的键（含顺序），
   * 由 laneT-detail-report/frontend/golden/prepared-root.base.json 抄入；合入「统计排序与行数」之后的基线
   * （local/release-r2）多一个 sortBy 键，排在 descending 之后。
   */
  const BASELINE_PREPARED_KEYS = [
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
    'detailEditable'
  ]

  it('主记录粒度：输出不带 grain / detailId 两个键，键与基线逐个相同', () => {
    const entries = reportFieldOptions('voucher', grainObjects)
    const legacy = prepareReportConfig(rootPivotConfig(), entries, [])
    expect(Object.keys(legacy)).toEqual(BASELINE_PREPARED_KEYS)
    // 编辑过程中留下的 null / ROOT 也归一掉
    for (const leftover of [
      { grain: null, detailId: null },
      { grain: 'ROOT' as const, detailId: null },
      { grain: 'ROOT' as const, detailId: 'lines' }
    ]) {
      const prepared = prepareReportConfig({ ...rootPivotConfig(), ...leftover }, entries, [])
      expect('grain' in prepared).toBe(false)
      expect('detailId' in prepared).toBe(false)
      expect(prepared).toEqual(legacy)
    }
  })

  it('明细粒度：保留 grain / detailId，下钻视图与下钻编辑原样保存（laneDV）', () => {
    const config = { ...detailPivotConfig(), detailViewId: 'view', detailEditable: true }
    const prepared = prepareReportConfig(
      config,
      reportFieldOptions('voucher', grainObjects, {}, 'lines'),
      [],
      scope(config)
    )
    expect(prepared.grain).toBe('DETAIL')
    expect(prepared.detailId).toBe('lines')
    expect(prepared.detailViewId).toBe('view')
    expect(prepared.detailEditable).toBe(true)
    // 没选下钻视图时「允许编辑」照旧落成 null（只读）
    const plain = prepareReportConfig(
      { ...detailPivotConfig(), detailEditable: true },
      reportFieldOptions('voucher', grainObjects, {}, 'lines'),
      [],
      scope(config)
    )
    expect(plain.detailViewId).toBeNull()
    expect(plain.detailEditable).toBeNull()
  })

  it('明细粒度：主表字段求和在保存时被 M6 拦住；固定筛选可以用明细字段', () => {
    const entries = reportFieldOptions('voucher', grainObjects, {}, 'lines')
    const bad: ReportConfig = {
      ...detailPivotConfig(),
      metrics: [{ id: 'in', name: '入金合计', operation: 'SUM', fieldId: 'income' }]
    }
    expect(() => prepareReportConfig(bad, entries, [], scope(bad))).toThrow('主表字段「入金」会按明细行数重复计算')
    const prepared = prepareReportConfig(
      detailPivotConfig(),
      entries,
      [{ fieldId: 'lineType', value: 'NORMAL' }],
      scope(detailPivotConfig())
    )
    expect(prepared.equal).toEqual({ lineType: 'NORMAL' })
    // 主记录粒度的字段范围里没有明细字段：同一条固定筛选保存不了
    expect(() =>
      prepareReportConfig(rootPivotConfig(), reportFieldOptions('voucher', grainObjects), [
        { fieldId: 'lineType', value: 'NORMAL' }
      ])
    ).toThrow('报表固定筛选字段已不可用')
  })
})
