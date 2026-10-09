// @vitest-environment jsdom
import { mount, find, event, text, flush } from '../../tools/selection-regression/renderer'
import { describe, expect, it, vi } from 'vitest'
import DashboardRuntime from '@/views/nocode/report-center/components/DashboardRuntime.vue'
import { dashboardDefaultFilterValues } from './report-dashboard'
import type { DashboardContent, DashboardRelease } from '@/types/nocode/report-dashboard'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
import type { ReportResult } from '@/types/nocode/report'

vi.mock('@/views/nocode/report-center/components/DashboardFilterControl.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      setup:
        (_, { attrs }) =>
        () =>
          h('filter-control', attrs)
    })
  }
})
vi.mock('@/views/nocode/report-center/components/DashboardChartPanel.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('chart-panel') }) }
})

const content: DashboardContent = {
  schemaVersion: 1,
  name: '默认筛选',
  description: '',
  charts: [
    {
      id: 'chart',
      title: '金额',
      display: 'METRIC',
      dataset: { id: 'data', versionNo: 1, checksum: 'data' },
      dimensions: [],
      metricIds: ['sum'],
      x: 0,
      y: 0,
      w: 6,
      h: 3
    }
  ],
  filters: [
    {
      id: 'company',
      name: '公司',
      kind: 'MULTISELECT',
      mappings: [{ chartId: 'chart', fieldId: 'company' }],
      defaultValue: { values: [null, 'null'] }
    }
  ]
}
const release = { id: 'board', versionNo: 1, checksum: 'board', content } as DashboardRelease

describe('公共筛选默认值', () => {
  it('默认值快照保留NULL与字面null，排除应用强制绑定且不修改发布定义', () => {
    const initial = dashboardDefaultFilterValues(content)
    initial[0]!.values!.push('later')
    expect(content.filters![0]!.defaultValue!.values).toEqual([null, 'null'])
    expect(dashboardDefaultFilterValues(content, ['company'])).toEqual([])
    expect(dashboardDefaultFilterValues({ ...content, filters: undefined })).toEqual([])
  })
  it('首次打开应用默认值，主动清空后查询不补默认，重置恢复发布默认值', async () => {
    const query = vi.fn().mockResolvedValue({ groups: [] } as unknown as ReportResult)
    const transport = {
      load: vi.fn().mockResolvedValue({ dashboard: release }),
      query
    } as unknown as DashboardRuntimeTransport
    const wrapper = mount(DashboardRuntime, { transport, contextKey: 'board' })
    await flush()
    expect(query.mock.calls[0]![0].filterValues).toEqual([{ filterId: 'company', values: [null, 'null'] }])
    await event(find(wrapper.root, 'filter-control'), 'onChange', { filterId: 'company', values: [] })
    await flush()
    expect(query.mock.calls.at(-1)![0].filterValues).toEqual([{ filterId: 'company', values: [] }])
    await event(
      find(wrapper.root, 'a-button', node => text(node) === '重置筛选'),
      'onClick'
    )
    await flush()
    expect(query.mock.calls.at(-1)![0].filterValues).toEqual([{ filterId: 'company', values: [null, 'null'] }])
    wrapper.unmount()
  })
})
