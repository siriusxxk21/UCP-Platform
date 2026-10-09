import { describe, expect, it } from 'vitest'
import {
  chartDataErrors,
  chartPreviewKey,
  canKeepChartVersion,
  chartVersionImpact,
  chartConfigurationGuide
} from './report-dashboard-editor'
import type { DashboardChart } from '@/types/nocode/report-dashboard'
import type { DatasetRelease } from '@/types/nocode/report-center'
const chart: DashboardChart = {
  id: 'chart',
  title: '统计',
  display: 'BAR',
  dataset: { id: '1', versionNo: 1, checksum: 'fixed' },
  dimensions: [{ fieldId: 'name', bucket: 'VALUE' }],
  metricIds: ['sum'],
  x: 0,
  y: 0,
  w: 6,
  h: 6
}
const release: DatasetRelease = {
  datasetId: '1',
  versionNo: 1,
  checksum: 'fixed',
  reason: '',
  createTime: '',
  definition: {
    name: '数据',
    description: '',
    source: {
      schemaVersion: 1,
      root: { objectId: '1', versionNo: 1, checksum: 'object' },
      relations: [],
      fields: [{ id: 'name', name: '名称', path: [], sourceFieldId: 'origin', role: 'DIMENSION' }]
    },
    analysis: {
      schemaVersion: 1,
      metrics: [{ id: 'sum', name: '金额', operation: 'SUM', fieldId: 'amount' }],
      fixedConditions: null,
      fieldFormats: {},
      timeZone: 'Asia/Shanghai'
    }
  }
}
describe('图表配置预览与版本兼容', () => {
  it('布局与文案保留结果，固定版本、指标及透视口径变化使结果失效', () => {
    expect(chartPreviewKey({ ...chart, title: '新标题', x: 6, h: 8, links: [] })).toBe(chartPreviewKey(chart))
    for (const changed of [
      { ...chart, metricIds: ['count'] },
      { ...chart, dataset: { ...chart.dataset, checksum: 'new' } },
      { ...chart, dimensions: [{ fieldId: 'name', bucket: 'MONTH' as const }] }
    ])
      expect(chartPreviewKey(changed)).not.toBe(chartPreviewKey(chart))
  })
  it('配置错误定位到数据来源、指标和行列字段', () => {
    expect(chartDataErrors(chart, release)).toEqual({})
    expect(chartDataErrors({ ...chart, title: '', metricIds: [] }, release)).toMatchObject({
      title: expect.any(String),
      metrics: expect.any(String)
    })
    expect(
      chartDataErrors({ ...chart, dataset: { ...chart.dataset, checksum: 'wrong' } }, release).version
    ).toBeTruthy()
    expect(
      chartDataErrors({ ...chart, display: 'PIVOT', columnDimensions: chart.dimensions }, release).dimensions
    ).toContain('不能重复')
    expect(chartDataErrors({ ...chart, display: 'PIE', metricIds: ['sum', 'sum'] }, release).metrics).toContain('只能')
  })
  it('版本保留只接受相同固定来源和现存指标，不接受同名不同字段', () => {
    const next = structuredClone(release)
    next.versionNo = 2
    next.checksum = 'v2'
    expect(canKeepChartVersion(chart, release, next)).toBe(true)
    next.definition.source!.fields[0]!.sourceFieldId = 'other'
    expect(canKeepChartVersion(chart, release, next)).toBe(false)
    expect(canKeepChartVersion(chart, release, { ...release, datasetId: '2' })).toBe(false)
    expect(canKeepChartVersion({ ...chart, metricIds: ['missing'] }, release, release)).toBe(false)
  })
  it.each(['METRIC', 'BAR', 'LINE', 'PIE', 'TABLE', 'PIVOT'] as const)('%s 的必填引导与空指标校验一致', display => {
    const sample = {
      ...chart,
      display,
      dimensions: display === 'METRIC' ? [] : chart.dimensions,
      pivot: { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE' as const, maxColumnGroups: 24 }
    }
    expect(chartConfigurationGuide[display]).toContain('指标')
    expect(chartDataErrors(sample, release)).toEqual({})
    expect(chartDataErrors({ ...sample, metricIds: [] }, release).metrics).toBeTruthy()
    expect(!!chartDataErrors({ ...sample, dimensions: [] }, release).dimensions).toBe(display !== 'METRIC')
  })
  it('切换影响区分唯一映射与多图映射，兼容版本提醒指标口径变化', () => {
    const content = {
      schemaVersion: 1 as const,
      name: '看板',
      description: '',
      charts: [
        chart,
        {
          ...chart,
          id: 'other',
          title: '来源图',
          links: [{ targetChartId: chart.id, sourceFieldId: 'name', targetFieldId: 'name' }]
        }
      ],
      filters: [
        { id: 'single', name: '唯一筛选', kind: 'TEXT' as const, mappings: [{ chartId: chart.id, fieldId: 'name' }] },
        {
          id: 'shared',
          name: '共同筛选',
          kind: 'TEXT' as const,
          mappings: [
            { chartId: chart.id, fieldId: 'name' },
            { chartId: 'other', fieldId: 'name' }
          ]
        }
      ]
    }
    const next = structuredClone(release)
    next.versionNo = 2
    next.checksum = 'next'
    next.definition.analysis!.metrics[0]!.name = '新口径金额'
    const kept = chartVersionImpact(chart, release, next, content)
    expect(kept.keep).toBe(true)
    expect(kept.preserved.join()).toContain('唯一筛选')
    expect(kept.notices.join()).toContain('查询结果也可能不同')
    expect(chartVersionImpact(chart, release, next, content, true).reset.join()).toContain('此前待清理公共筛选')
    next.definition.source!.fields[0]!.sourceFieldId = 'different'
    const reset = chartVersionImpact(chart, release, next, content)
    expect(reset.keep).toBe(false)
    expect(reset.reset.join()).toContain('失去唯一映射，将移除整个筛选')
    expect(reset.reset.join()).toContain('仅移除此图映射')
    expect(reset.reset.join()).toContain('来源图')
    expect(content.filters).toHaveLength(2)
  })
})
