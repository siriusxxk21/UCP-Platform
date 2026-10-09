// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App, type Component } from 'vue'
import DashboardFilterControl from '@/views/nocode/report-center/components/DashboardFilterControl.vue'
import DashboardChartPanel from '@/views/nocode/report-center/components/DashboardChartPanel.vue'
import type { DatasetOptionPage } from '@/types/nocode/report-center'
import type {
  DashboardChart,
  DashboardDetailPage,
  DashboardFilter,
  DashboardFilterValue,
  DashboardQuery
} from '@/types/nocode/report-dashboard'
import type { ReportResult } from '@/types/nocode/report'

const api = vi.hoisted(() => ({ dashboardOptions: vi.fn(), dashboardDetails: vi.fn(), dashboardExport: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ reportCenter: api }) }))
vi.mock('@/nocode/data-center', () => ({ errorMessage: (error: Error) => error.message }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { open: Boolean },
      emits: ['cancel'],
      setup:
        (props, { slots, emit }) =>
        () =>
          props.open
            ? h('section', [h('button', { onClick: () => emit('cancel') }, '关闭明细'), slots.formItems?.()])
            : null
    })
  }
})
vi.mock('@/components/os-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['dataSource'],
      setup: props => () => h('div', { class: 'detail-rows' }, JSON.stringify(props.dataSource))
    })
  }
})
vi.mock('@/views/nocode/report-center/components/DashboardChartView.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      emits: ['select'],
      setup:
        (_props, { emit }) =>
        () =>
          h(
            'button',
            { onClick: () => emit('select', { group: ['company-a'], columnGroup: [], metricId: 'sum' }) },
            '点选图表数值'
          )
    })
  }
})

const mounted: { app: App; host: HTMLElement; active: boolean }[] = []
const browserURL = globalThis.URL
const createObjectURL = vi.fn(() => 'blob:dashboard-test')

function deferred<T>() {
  let resolve: ((value: T) => void) | undefined
  const promise = new Promise<T>(done => {
    resolve = done
  })
  return {
    promise,
    resolve(value: T) {
      if (!resolve) throw new Error('请求尚未初始化')
      resolve(value)
    }
  }
}
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const buttonStub = defineComponent({
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  setup:
    (props, { slots, emit }) =>
    () =>
      h(
        'button',
        { disabled: props.disabled || props.loading, onClick: event => emit('click', event) },
        slots.default?.()
      )
})
const selectStub = defineComponent({
  props: ['value', 'options', 'mode', 'loading'],
  emits: ['change', 'dropdownVisibleChange', 'search'],
  setup(props, { emit, slots }) {
    const open = ref(false)
    return () =>
      h('div', [
        h(
          'button',
          {
            onClick: () => {
              open.value = !open.value
              emit('dropdownVisibleChange', open.value)
            }
          },
          '打开候选'
        ),
        ...(open.value
          ? slots.dropdownRender?.({
              menuNode: h(
                'div',
                (props.options || []).map((option: { value: string; label: string }) =>
                  h(
                    'button',
                    {
                      onClick: () =>
                        emit(
                          'change',
                          props.mode === 'multiple' ? [...(props.value || []), option.value] : option.value
                        )
                    },
                    option.label
                  )
                )
              )
            }) || []
          : [])
      ])
  }
})
async function mount(component: Component, props: Record<string, unknown>) {
  const state = reactive(props),
    host = document.createElement('div'),
    app = createApp(() => h(component, state))
  const plain = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  app.component('AButton', buttonStub)
  app.component('ASelect', selectStub)
  app.component('ASpace', plain)
  app.component('ATooltip', plain)
  app.component('ASkeleton', plain)
  for (const name of ['AInputSearch', 'ADatePicker', 'AInput', 'ARadioGroup', 'ARadioButton'])
    app.component(name, plain)
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', [h('p', props.message), slots.action?.()])
    })
  )
  app.component(
    'ADrawer',
    defineComponent({
      props: { open: Boolean },
      setup:
        (drawer, { slots }) =>
        () =>
          drawer.open ? h('section', slots.default?.()) : null
    })
  )
  document.body.append(host)
  app.mount(host)
  const entry = { app, host, active: true }
  mounted.push(entry)
  await flush()
  return {
    host,
    props: state,
    unmount() {
      app.unmount()
      entry.active = false
      host.remove()
    }
  }
}
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(
    item => item.textContent?.trim() === text
  )
  if (!found) throw new Error(`未找到按钮：${text}`)
  return found
}
function query(patch: Partial<DashboardQuery> = {}): DashboardQuery {
  return { id: 'board', chartId: 'table', preview: false, versionNo: 1, checksum: 'release-one', ...patch }
}
const filter: DashboardFilter = {
  id: 'company-filter',
  name: '公司',
  kind: 'MULTISELECT',
  mappings: [{ chartId: 'table', fieldId: 'company' }]
}
const chart: DashboardChart = {
  id: 'table',
  title: '公司金额',
  display: 'TABLE',
  dataset: { id: 'dataset', versionNo: 1, checksum: 'dataset-one' },
  dimensions: [{ fieldId: 'company', bucket: 'VALUE' }],
  metricIds: ['sum'],
  x: 0,
  y: 0,
  w: 6,
  h: 6
}
const result: ReportResult = {
  dimensionNames: ['公司'],
  metrics: [{ id: 'sum', name: '金额', operation: 'SUM', fieldId: 'amount' }],
  groups: [],
  totals: {},
  totalGroups: 0,
  recordCount: 0,
  canExport: true,
  timeZone: 'Asia/Shanghai'
}
function options(label: string, total = 1): DatasetOptionPage {
  return { list: [{ value: label, label }], total, pageNo: 1, pageSize: 50 }
}
function details(label: string): DashboardDetailPage {
  return {
    columns: [{ id: 'company', name: '公司' }],
    list: [{ id: label, values: [label], labels: [label] }],
    total: 1,
    pageNo: 1,
    pageSize: 20
  }
}
beforeEach(() => {
  api.dashboardOptions.mockReset()
  api.dashboardDetails.mockReset()
  api.dashboardExport.mockReset()
  createObjectURL.mockClear()
  vi.stubGlobal(
    'URL',
    class extends browserURL {
      static createObjectURL = createObjectURL
      static revokeObjectURL = vi.fn()
    }
  )
})
afterEach(() => {
  for (const entry of mounted.splice(0)) {
    if (entry.active) entry.app.unmount()
    entry.host.remove()
  }
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('仪表板公共筛选候选的异步交互', () => {
  it('多选首项后保持下拉打开，自动按新条件重查第一页以便继续选择', async () => {
    api.dashboardOptions.mockResolvedValueOnce(options('甲公司', 2)).mockResolvedValueOnce(options('乙公司'))
    const view = await mount(DashboardFilterControl, { filter, query: query() })
    view.props.onChange = (value: DashboardFilterValue) => {
      view.props.value = value
      view.props.query = query({ filterValues: [value] })
    }
    await flush()
    button(view.host, '打开候选').click()
    await flush()
    button(view.host, '甲公司').click()
    await flush()
    expect(api.dashboardOptions).toHaveBeenCalledTimes(2)
    expect(api.dashboardOptions.mock.calls[1]?.[0]).toMatchObject({
      pageNo: 1,
      query: { filterValues: [{ filterId: 'company-filter', values: ['甲公司'] }] }
    })
    expect(button(view.host, '乙公司')).toBeDefined()
    expect(button(view.host, '甲公司')).toBeDefined()
  })

  it('条件变化中止旧候选；即使旧请求忽略中止且晚返回，也不覆盖新候选', async () => {
    const old = deferred<DatasetOptionPage>(),
      current = deferred<DatasetOptionPage>()
    api.dashboardOptions.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const view = await mount(DashboardFilterControl, { filter, query: query() })
    button(view.host, '打开候选').click()
    await flush()
    view.props.query = query({ selections: [{ chartId: 'source', group: ['new-company'] }] })
    await flush()
    expect(api.dashboardOptions.mock.calls[0]?.[1].aborted).toBe(true)
    current.resolve(options('新候选'))
    await flush()
    old.resolve(options('旧候选'))
    await flush()
    expect(view.host.textContent).toContain('新候选')
    expect(view.host.textContent).not.toContain('旧候选')
  })

  it('加载第二页失败后再试仍请求第二页，保留第一页候选', async () => {
    api.dashboardOptions
      .mockResolvedValueOnce(options('第一页', 100))
      .mockRejectedValueOnce(new Error('候选暂时不可用'))
      .mockResolvedValueOnce(options('第二页', 2))
    const view = await mount(DashboardFilterControl, { filter, query: query() })
    button(view.host, '打开候选').click()
    await flush()
    button(view.host, '加载更多候选').click()
    await flush()
    expect(view.host.textContent).toContain('候选暂时不可用')
    expect(view.host.textContent).toContain('第一页')
    button(view.host, '加载更多候选').click()
    await flush()
    expect(api.dashboardOptions.mock.calls.map(call => call[0].pageNo)).toEqual([1, 2, 2])
    expect(view.host.textContent).toContain('第一页')
    expect(view.host.textContent).toContain('第二页')
  })
})

describe('仪表板明细和导出请求的生命周期', () => {
  it('关闭公共明细抽屉中止查询，晚返回不重新打开', async () => {
    const request = deferred<DashboardDetailPage>()
    api.dashboardDetails.mockReturnValueOnce(request.promise)
    const view = await mount(DashboardChartPanel, { chart, result, query: query() })
    button(view.host, '查看明细').click()
    await flush()
    button(view.host, '关闭明细').click()
    await flush()
    expect(api.dashboardDetails.mock.calls[0]?.[1].aborted).toBe(true)
    request.resolve(details('已关闭记录'))
    await flush()
    expect(view.host.querySelector('section')).toBeNull()
  })
  it('明细失败可按原单元格、筛选和下钻口径重试', async () => {
    api.dashboardDetails.mockRejectedValueOnce(new Error('明细暂时不可用')).mockResolvedValueOnce(details('恢复记录'))
    const view = await mount(DashboardChartPanel, {
      chart,
      result,
      query: query({ drillPath: ['current-company'] })
    })
    button(view.host, '点选图表数值').click()
    await flush()
    expect(view.host.textContent).toContain('明细暂时不可用')
    button(view.host, '重试明细').click()
    await flush()
    expect(api.dashboardDetails.mock.calls[1]?.[0]).toEqual(api.dashboardDetails.mock.calls[0]?.[0])
    expect(view.host.textContent).toContain('恢复记录')
    expect(view.host.textContent).not.toContain('明细暂时不可用')
  })

  it('取消后可立即重新导出，旧文件晚到不下载且不覆盖新请求状态', async () => {
    const old = deferred<Blob>(),
      current = deferred<Blob>()
    const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    api.dashboardExport.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const view = await mount(DashboardChartPanel, { chart, result, query: query() })
    button(view.host, '导出 Excel').click()
    await flush()
    button(view.host, '取消导出').click()
    await flush()
    expect(api.dashboardExport.mock.calls[0]?.[1].aborted).toBe(true)
    expect(view.host.textContent).toContain('已取消本次下载')
    button(view.host, '导出 Excel').click()
    await flush()
    old.resolve(new Blob(['old'], { type: 'application/octet-stream' }))
    await flush()
    expect(download).not.toHaveBeenCalled()
    expect(button(view.host, '取消导出')).toBeDefined()
    current.resolve(new Blob(['current'], { type: 'application/octet-stream' }))
    await flush()
    expect(download).toHaveBeenCalledTimes(1)
    expect(view.host.textContent).toContain('文件已生成并发起下载')
  })

  it('导出失败提供重试，查询切换清理旧失败及下载提示', async () => {
    api.dashboardExport.mockRejectedValueOnce(new Error('导出暂时不可用')).mockRejectedValueOnce(new Error('重试失败'))
    const view = await mount(DashboardChartPanel, { chart, result, query: query() })
    button(view.host, '导出 Excel').click()
    await flush()
    button(view.host, '重试导出').click()
    await flush()
    expect(api.dashboardExport).toHaveBeenCalledTimes(2)
    expect(view.host.textContent).toContain('重试失败')
    view.props.query = query({ drillPath: ['new-company'] })
    await flush()
    expect(view.host.textContent).not.toContain('重试失败')
    expect(view.host.textContent).not.toContain('重试导出')
  })

  it('组件卸载后中止导出，忽略晚返回文件且不触发浏览器下载', async () => {
    const request = deferred<Blob>(),
      download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    api.dashboardExport.mockReturnValueOnce(request.promise)
    const view = await mount(DashboardChartPanel, { chart, result, query: query({ drillPath: ['company-a'] }) })
    button(view.host, '导出 Excel').click()
    await flush()
    expect(api.dashboardExport.mock.calls[0]?.[0]).toMatchObject({ drillPath: ['company-a'] })
    view.unmount()
    expect(api.dashboardExport.mock.calls[0]?.[1].aborted).toBe(true)
    request.resolve(
      new Blob(['old spreadsheet'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    )
    await flush()
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(download).not.toHaveBeenCalled()
  })

  it('钻取口径变化中止旧明细，晚返回记录不覆盖重新打开后的当前明细', async () => {
    const old = deferred<DashboardDetailPage>(),
      current = deferred<DashboardDetailPage>()
    api.dashboardDetails.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const view = await mount(DashboardChartPanel, { chart, result, query: query() })
    button(view.host, '点选图表数值').click()
    await flush()
    expect(api.dashboardDetails.mock.calls[0]?.[0]).toMatchObject({ group: ['company-a'], metricId: 'sum' })
    view.props.query = query({ drillPath: ['company-a'] })
    await flush()
    expect(api.dashboardDetails.mock.calls[0]?.[1].aborted).toBe(true)
    button(view.host, '查看明细').click()
    await flush()
    expect(api.dashboardDetails.mock.calls[1]?.[0]).toMatchObject({
      query: { drillPath: ['company-a'] },
      group: [],
      columnGroup: []
    })
    current.resolve(details('当前层记录'))
    await flush()
    old.resolve(details('上一层记录'))
    await flush()
    expect(view.host.textContent).toContain('当前层记录')
    expect(view.host.textContent).not.toContain('上一层记录')
  })
})
