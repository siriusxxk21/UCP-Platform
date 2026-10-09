import { describe, it, expect } from 'vitest'
import { defaultReport } from './report'
import {
  defaultReportChart,
  formatReportValue,
  financialReportMetric,
  reportChartOption,
  validateReport,
  duplicateReportMetrics
} from './report-presentation'
import type { ReportMetric, ReportConfig, ReportResult } from '@/types/nocode/report'
import { FieldType } from '@/types/nocode/enums'

const count: ReportMetric = { id: 'count', name: '数量', operation: 'COUNT', fieldId: null }
const config = (): ReportConfig => ({
  ...defaultReport('asset'),
  display: 'BAR',
  dimensions: [{ fieldId: 'department', relationPath: null, bucket: 'VALUE' }],
  metrics: [count],
  chart: defaultReportChart()
})
const result = (c: ReportConfig): ReportResult => ({
  dimensionNames: ['部门'],
  metrics: c.metrics,
  groups: [{ keys: ['dev'], labels: ['研发部'], values: { count: '6' } }],
  totals: { count: '6' },
  recordCount: 6,
  totalGroups: 1,
  canExport: true,
  timeZone: 'Asia/Shanghai'
})
describe('统计图表的口径与精度', () => {
  it('仅金额聚合自动启用金额格式，计数与普通数值清除遗留标记', () => {
    const operations: ReportMetric['operation'][] = ['SUM', 'AVG', 'MIN', 'MAX']
    for (const operation of operations) {
      const metric = { ...count, operation, format: { financial: true } }
      expect(financialReportMetric(metric, FieldType.MONEY)).toBe(true)
      for (const type of [FieldType.INTEGER, FieldType.DECIMAL, FieldType.PERCENT, undefined])
        expect(financialReportMetric(metric, type)).toBe(false)
      expect(financialReportMetric({ ...metric, format: { percent: true } }, FieldType.MONEY)).toBe(false)
    }
    const counts: ReportMetric['operation'][] = ['COUNT', 'COUNT_FIELD', 'COUNT_DISTINCT']
    for (const operation of counts)
      expect(financialReportMetric({ ...count, operation, format: { financial: true } }, FieldType.MONEY)).toBe(false)
    expect(financialReportMetric({ ...count, operation: 'FORMULA', format: { financial: true } })).toBe(true)
    expect(financialReportMetric({ ...count, operation: 'FORMULA' }, FieldType.MONEY)).toBe(false)
    expect(financialReportMetric({ ...count, operation: 'FORMULA', format: { financial: true, percent: true } })).toBe(
      false
    )
  })
  it('金额固定两位且保留显式单位，空值无单位，舍入不产生负零', () => {
    const metric = { ...count, format: { financial: true, unit: 'USD', decimals: 8 } }
    expect(formatReportValue('9007199254740993.995', metric)).toBe('9,007,199,254,740,994.00USD')
    expect(formatReportValue('-0.004', metric)).toBe('0.00USD')
    expect(formatReportValue('-0.005', metric)).toBe('-0.01USD')
    for (const empty of [null, undefined, '']) expect(formatReportValue(empty, metric)).toBe('—')
  })
  it.each(['BAR', 'LINE', 'PIE'] as const)('旧配置的 %s 标签和提示采用结果格式且不修改快照', display => {
    const c = config()
    c.display = display
    c.metrics = [{ ...count, operation: 'SUM', fieldId: 'money', format: { unit: '元', decimals: 4 } }]
    const data = result(c)
    data.metrics = [{ ...c.metrics[0], format: { ...c.metrics[0].format, financial: true } }]
    data.groups[0].values.count = '1234.5678'
    const before = JSON.stringify([c, data])
    const option = reportChartOption(c, data) as any
    const point = option.series[0].data[0]
    expect(option.tooltip.formatter({ data: point })).toBe('研发部\n数量：1,234.57元')
    expect(option.series[0].label.formatter({ name: point.name, data: point })).toContain('1,234.57元')
    expect(JSON.stringify([c, data])).toBe(before)
  })
  it('提交时拒绝同时启用百分比和金额格式', () => {
    const c = config()
    c.metrics = [{ ...count, format: { financial: true, percent: true } }]
    expect(() => validateReport(c)).toThrow('金额格式不能同时设为百分比')
  })
  it('饼图负数明确提示，百分比堆叠不把空平均值转换为零', () => {
    const c = config()
    const data = result(c)
    c.display = 'PIE'
    data.groups[0].values.count = '-1'
    expect(() => reportChartOption(c, data)).toThrow('负数')
    c.display = 'BAR'
    c.chart!.barMode = 'PERCENT'
    c.metrics[0] = { ...count, operation: 'AVG', fieldId: 'quantity' }
    data.groups[0].values.count = null
    expect((reportChartOption(c, data) as any).series[0].data[0].value).toBeNull()
  })
  it('大金额的展示舍入不丢失整数精度，比例和除零有明确显示', () => {
    expect(formatReportValue('9007199254740993.995', { ...count, format: { decimals: 2, unit: '元' } })).toBe(
      '9,007,199,254,740,994.00元'
    )
    expect(formatReportValue('-1.005', { ...count, format: { decimals: 2 } })).toBe('-1.01')
    expect(formatReportValue('0.44444444444444444444', { ...count, format: { decimals: 2, percent: true } })).toBe(
      '44.44%'
    )
    expect(formatReportValue(null, { ...count, format: { percent: true } })).toBe('—')
    expect(formatReportValue('0', { ...count, format: { decimals: 0 } })).toBe('0')
    expect(formatReportValue('1234.5', { ...count, format: { financial: true } })).toBe('1,234.50')
  })
  it('改名不改变计算，重复指标提示忽略名称和外观', () => {
    expect(duplicateReportMetrics([count, { ...count, id: 'x', name: '损坏数量', format: { color: '#ff0000' } }])).toBe(
      true
    )
    expect(
      duplicateReportMetrics([
        count,
        {
          ...count,
          id: 'x',
          conditions: {
            logic: 'AND',
            items: [{ type: 'condition', field: 'status', operator: 'eq', value: 'damaged' }]
          }
        }
      ])
    ).toBe(false)
  })
  it('图形点保留后端指标身份，颜色不会因顺序变化而漂移', () => {
    const c = config()
    c.metrics.push({ ...count, id: 'idle', name: '空闲' })
    const data = result(c)
    data.groups[0].values.idle = '2'
    const series = (reportChartOption(c, data) as any).series
    expect(series[0].data[0]).toMatchObject({ groupIndex: 0, metricId: 'count', raw: '6', value: 6 })
    c.metrics.reverse()
    expect((reportChartOption(c, data) as any).series[1].itemStyle.color).toBe(series[0].itemStyle.color)
  })
  it('缺失组合的计数补零，平均值保持空，来源结果不被改变', () => {
    const c = config()
    c.dimensions.push({ fieldId: 'status', relationPath: null, bucket: 'VALUE' })
    c.metrics.push({ id: 'average', name: '平均', operation: 'AVG', fieldId: 'quantity' })
    const data = result(c)
    data.groups = [
      { keys: ['dev', 'used'], labels: ['研发部', '在用'], values: { count: '6', average: '6' } },
      { keys: ['admin', 'idle'], labels: ['行政部', '空闲'], values: { count: '2', average: '2' } }
    ]
    const before = JSON.stringify(data)
    const series = (reportChartOption(c, data) as any).series
    expect(series[0].data[1].value).toBe(0)
    expect(series[2].data[1].value).toBeNull()
    expect(JSON.stringify(data)).toBe(before)
  })
  it('百分比堆叠计算构成比例但仍保留原值，拒绝负数', () => {
    const c = config()
    c.chart!.barMode = 'PERCENT'
    c.metrics.push({ ...count, id: 'idle', name: '空闲' })
    const data = result(c)
    data.groups[0].values.idle = '2'
    const option = reportChartOption(c, data) as any
    expect(option.series[0].data[0]).toMatchObject({ value: 75, raw: '6' })
    expect(option.series[1].data[0].value).toBe(25)
    data.groups[0].values.idle = '-2'
    expect(() => reportChartOption(c, data)).toThrow('负数')
  })
  it('循环、失效引用和不适合堆叠的比例在保存前阻断', () => {
    const c = config()
    c.metrics.push({
      id: 'ratio',
      name: '比率',
      operation: 'FORMULA',
      fieldId: null,
      formula: { operator: 'DIVIDE', left: 'count', right: 'ratio' }
    })
    expect(() => validateReport(c)).toThrow('循环')
    c.metrics[1].formula!.right = 'missing'
    expect(() => validateReport(c)).toThrow('不存在')
    c.metrics[1].formula!.right = 'count'
    expect(() => validateReport(c)).not.toThrow()
    c.chart!.barMode = 'STACKED'
    expect(() => validateReport(c)).toThrow('堆叠')
  })
  it('数量和比率使用标明单位的独立坐标轴，横向图保持相同规则', () => {
    const c = config()
    c.metrics.push({ ...count, id: 'rate', format: { percent: true } })
    const data = result(c)
    data.groups[0].values.rate = '0.6'
    const vertical = reportChartOption(c, data) as any
    expect(vertical.yAxis[1].name).toBe('比例（%）')
    expect(vertical.series[1]).toMatchObject({ yAxisIndex: 1 })
    expect(vertical.series[1].data[0].value).toBe(60)
    c.chart!.horizontal = true
    expect((reportChartOption(c, data) as any).series[1].xAxisIndex).toBe(1)
  })
  it('紧凑横向图限制单柱高度、保留数值空间并采用容器主题色', () => {
    const c = config()
    c.chart!.horizontal = true
    c.metrics = [{ ...count, format: { decimals: 2, unit: ' 小时' } }]
    const data = result(c)
    data.groups[0].values.count = '12345.25'
    const before = JSON.stringify([c, data])
    const option = reportChartOption(c, data, { compact: true, primaryColor: '#6554c0' }) as any
    expect(option.series[0]).toMatchObject({
      barMaxWidth: 24,
      itemStyle: { color: '#6554c0' },
      label: { show: true, position: 'right' }
    })
    expect(option.series[0].label.formatter({ data: option.series[0].data[0] })).toBe('12,345.25 小时')
    expect(option.grid.right).toBeGreaterThanOrEqual(100)
    expect(option.legend.show).toBe(false)
    expect(option.yAxis.inverse).toBe(true)
    expect(option.series[0].data[0]).toMatchObject({ groupIndex: 0, metricId: 'count', raw: '12345.25' })
    expect(JSON.stringify([c, data])).toBe(before)
  })
  it('未显式启用紧凑模式的既有报表外观不变', () => {
    const c = config()
    c.chart!.horizontal = true
    const option = reportChartOption(c, result(c)) as any
    expect(option.grid).toMatchObject({ right: 28, top: 50 })
    expect(option.series[0].barMaxWidth).toBeUndefined()
    expect(option.series[0].label.show).toBe(false)
    expect(option.legend.show).toBeUndefined()
    expect(option.yAxis.inverse).toBeUndefined()
  })
  it('紧凑模式不隐藏多系列图例、不替换系列配色，也不改变折线图布局', () => {
    const c = config()
    c.chart!.horizontal = true
    c.metrics.push({ ...count, id: 'idle', name: '空闲' })
    const data = result(c)
    data.groups[0].values.idle = '2'
    const multi = reportChartOption(c, data, { compact: true, primaryColor: '#6554c0' }) as any
    const legacy = reportChartOption(c, data) as any
    expect(multi.legend.show).toBeUndefined()
    expect(multi.series.map((series: any) => series.itemStyle.color)).toEqual(
      legacy.series.map((series: any) => series.itemStyle.color)
    )
    c.display = 'LINE'
    const line = reportChartOption(c, data, { compact: true, primaryColor: '#6554c0' }) as any
    expect(line.series[0].barMaxWidth).toBeUndefined()
    expect(line.grid).toMatchObject({ right: 28, top: 50 })
    expect(line.series[0].label.show).toBe(false)
  })
})
