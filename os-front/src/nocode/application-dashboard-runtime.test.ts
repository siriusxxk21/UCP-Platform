import { reactive } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { applicationDashboardQuery, createApplicationDashboardTransport } from './application-dashboard-runtime'
import type { ApplicationDashboardRuntimeApi } from '@/api/nocode/application-dashboard-runtime'
import type { ApplicationDashboardModel } from '@/types/nocode/application-dashboard-runtime'
import type { DashboardQuery } from '@/types/nocode/report-dashboard'

const model = {
  applicationId: 'app1',
  resourceId: 'board1',
  stamp: 'stamp1',
  boundFilterIds: []
} as unknown as ApplicationDashboardModel
const query: DashboardQuery = {
  id: 'untrusted-board',
  chartId: 'chart1',
  preview: true,
  versionNo: 99,
  checksum: 'untrusted',
  filterValues: [],
  selections: [],
  drillPath: [null]
}

describe('应用固定看板请求', () => {
  it('响应式参数保存为原值快照，移除独立入口身份与预览参数', () => {
    const parameters = reactive({ company: { values: [null, 'null'] } })
    const saved = applicationDashboardQuery(model, { applicationId: 'app1', resourceId: 'board1', parameters }, query)
    parameters.company.values.push('later')
    expect(saved.parameters).toEqual({ company: { values: [null, 'null'] } })
    expect(saved).not.toHaveProperty('id')
    expect(saved).not.toHaveProperty('versionNo')
    expect(saved).not.toHaveProperty('checksum')
    expect(saved).not.toHaveProperty('preview')
    expect(saved.drillPath).toEqual([null])
  })
  it('拒绝将一个应用模型用于另一个资源', () => {
    expect(() =>
      applicationDashboardQuery(model, { applicationId: 'app2', resourceId: 'board1', parameters: {} }, query)
    ).toThrow('上下文已变化')
  })
  it('明细、业务下钻与导出保留取图时的应用标记、记录和参数', async () => {
    const send = vi.fn().mockResolvedValue({}),
      details = vi.fn().mockResolvedValue({}),
      exporting = vi.fn().mockResolvedValue(new Blob())
    const api = {
      model: vi.fn().mockResolvedValue(model),
      query: send,
      details,
      export: exporting
    } as unknown as ApplicationDashboardRuntimeApi
    const context = {
      applicationId: 'app1',
      resourceId: 'board1',
      recordId: 'record1',
      parameters: { company: { values: ['company1'] } }
    }
    const business = vi.fn()
    const transport = createApplicationDashboardTransport(
      api,
      () => context,
      () => {},
      { available: id => id === 'chart1', open: business }
    )
    await transport.load()
    await transport.query(query)
    context.recordId = 'record2'
    context.parameters.company.values[0] = 'company2'
    await transport.details({ query: reactive(query), group: [null], columnGroup: [], pageNo: 1, pageSize: 20 })
    await transport.export(reactive(query))
    transport.businessDetails!({
      query: reactive(query),
      group: [null, 'null'],
      columnGroup: ['original'],
      metricId: 'amount',
      pageNo: 1,
      pageSize: 20
    })
    expect(transport.canBusinessDetails!('chart1')).toBe(true)
    expect(transport.canBusinessDetails!('other')).toBe(false)
    expect(business.mock.calls[0]![0]).toEqual({
      query: send.mock.calls[0]![0],
      group: [null, 'null'],
      columnGroup: ['original'],
      metricId: 'amount'
    })
    expect(details.mock.calls[0]![0].query).toEqual(send.mock.calls[0]![0])
    expect(exporting.mock.calls[0]![0]).toEqual(send.mock.calls[0]![0])
    expect(exporting.mock.calls[0]![0].recordId).toBe('record1')
    expect(exporting.mock.calls[0]![0].parameters.company.values).toEqual(['company1'])
  })
})
