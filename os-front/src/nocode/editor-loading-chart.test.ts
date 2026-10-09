// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onMounted, reactive, type App } from 'vue'
import { defaultReport } from './report'
import { ReportDisplay, type ReportConfig, type ReportResult } from '@/types/nocode/report'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { FieldType } from '@/types/nocode/enums'

const api = vi.hoisted(() => ({ report: vi.fn(), model: vi.fn(), previewReport: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api, applications: api }) }))
vi.mock('@/nocode/report-context', () => ({ useReportDashboard: () => undefined }))
vi.mock('@/nocode/application-context', () => ({ useApplicationRefresh: () => ({ value: 0 }) }))
vi.mock('@/views/nocode/application/components/ReportFilterInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: { render: () => null } }))
const chartPath = '@/views/nocode/application/components/ReportChart.vue'
const chartMounted = vi.fn()
const FakeChart = defineComponent({
  props: ['config', 'result', 'compact'],
  emits: ['select'],
  setup(props, { emit }) {
    onMounted(chartMounted)
    return () =>
      h(
        'button',
        { 'data-chart': true, 'data-compact': props.compact, onClick: () => emit('select', 2, 'metric') },
        `${props.config.display}/${props.result.groups[0]?.labels[0]}`
      )
  }
})
const chartModule = { default: FakeChart }
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const config = (): ReportConfig => ({
  ...defaultReport('orders'),
  display: ReportDisplay.BAR,
  dimensions: [{ fieldId: 'company', relationPath: null, bucket: 'VALUE' }]
})
const result = (): ReportResult => ({
  dimensionNames: ['公司'],
  metrics: config().metrics,
  groups: [{ keys: ['a'], labels: ['原数据'], values: { metric: '3' } }],
  totals: { metric: '3' },
  totalGroups: 1,
  recordCount: 3,
  canExport: false,
  timeZone: 'Asia/Shanghai'
})
const objects = {
  orders: {
    objectId: 'orders',
    versionNo: 1,
    checksum: 'v1',
    definition: { objectId: 'orders', objectName: '订单', fields: [], fieldOptions: {}, relations: [], details: [] }
  }
} as unknown as Record<string, PublishedObject>
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  Array.from(host.querySelectorAll('button')).find(element => element.textContent?.trim() === label)!
function start(render: () => ReturnType<typeof h> | null) {
  app = createApp(render)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.extra?.()])
  })
  for (const name of [
    'ACard',
    'ASpace',
    'ASpin',
    'AForm',
    'AFormItem',
    'AEmpty',
    'ATag',
    'ADivider',
    'AModal',
    'ATabs',
    'ATabPane',
    'ASelect',
    'AInput',
    'AInputNumber',
    'ARadioGroup',
    'ARadioButton',
    'ARangePicker',
    'ASegmented',
    'ADrawer'
  ])
    app.component(name, plain)
  app.component(
    'ACheckbox',
    defineComponent({
      props: { checked: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked)
            }),
            slots.default?.()
          ])
    })
  )
  app.component(
    'ATable',
    defineComponent({
      props: ['dataSource'],
      setup: props => () => h('div', { 'data-result-table': true }, JSON.stringify(props.dataSource))
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (props, { slots }) =>
        () =>
          h('aside', { role: 'alert' }, [props.message, slots.action?.()])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}
beforeEach(() => {
  vi.clearAllMocks()
  api.report.mockResolvedValue(result())
  api.previewReport.mockResolvedValue(result())
  api.model.mockResolvedValue({
    object: objects.orders!.definition,
    permissions: { actions: [], readFields: [], writeFields: [] }
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.doUnmock(chartPath)
})

describe('图表按需加载及失败保护', () => {
  it('隐藏时不加载，等待期间更新的 config/result 在就绪时使用，并透传 select', async () => {
    const pending = deferred<typeof chartModule>(),
      factory = vi.fn(() => pending.promise)
    vi.doMock(chartPath, factory)
    const AsyncChart = (await import('@/views/nocode/application/components/AsyncReportChart.vue')).default
    const state = reactive({ visible: false, config: config(), result: result(), compact: false }),
      select = vi.fn()
    start(() =>
      state.visible
        ? h(AsyncChart, { config: state.config, result: state.result, compact: state.compact, onSelect: select })
        : null
    )
    await flush()
    expect(factory).not.toHaveBeenCalled()
    state.visible = true
    await flush()
    expect(host.textContent).toContain('图表加载中')
    state.config.display = ReportDisplay.LINE
    state.compact = true
    state.result.groups[0]!.labels[0] = '最新数据'
    await flush()
    pending.resolve(chartModule)
    await flush()
    expect(host.querySelector('[data-chart]')!.textContent).toBe('LINE/最新数据')
    expect(host.querySelector('[data-chart]')?.getAttribute('data-compact')).toBe('true')
    state.compact = false
    await flush()
    expect(host.querySelector('[data-chart]')?.getAttribute('data-compact')).toBe('false')
    host.querySelector<HTMLButtonElement>('[data-chart]')!.click()
    expect(select).toHaveBeenCalledWith(2, 'metric')
    expect(host.textContent).not.toContain('图表加载中')
  })
  it('加载失败提示先保存再刷新，保留数据且不提供无效的局部重试', async () => {
    const pending = deferred<typeof chartModule>(),
      factory = vi.fn(() => pending.promise)
    vi.doMock(chartPath, factory)
    const AsyncChart = (await import('@/views/nocode/application/components/AsyncReportChart.vue')).default
    const state = reactive({ config: config(), result: result() })
    const snapshot = JSON.stringify(state)
    start(() => h(AsyncChart, state))
    await flush()
    pending.reject(new Error('模拟分块网络错误'))
    await flush()
    expect(host.querySelector('[role="alert"]')!.textContent).toBe(
      '图表资源加载失败，请先保存未保存的内容，再刷新页面。'
    )
    expect(host.querySelector('button')).toBeNull()
    expect(host.querySelector('[aria-busy]')!.getAttribute('aria-busy')).toBe('false')
    expect(JSON.stringify(state)).toBe(snapshot)
    state.result.groups[0]!.labels[0] = '保留的新数据'
    await flush()
    expect(state.result.groups[0]!.labels[0]).toBe('保留的新数据')
    expect(host.querySelector('[data-chart]')).toBeNull()
    expect(factory).toHaveBeenCalledOnce()
    expect(chartMounted).not.toHaveBeenCalled()
  })
  it('隐藏或卸载后迟到的模块不会挂载旧图表，重新显示使用新实例', async () => {
    const pending = deferred<typeof chartModule>()
    vi.doMock(chartPath, () => pending.promise)
    const AsyncChart = (await import('@/views/nocode/application/components/AsyncReportChart.vue')).default
    const state = reactive({ visible: true, config: config(), result: result() })
    start(() => (state.visible ? h(AsyncChart, { config: state.config, result: state.result }) : null))
    await flush()
    state.visible = false
    await flush()
    pending.resolve(chartModule)
    await flush()
    expect(chartMounted).not.toHaveBeenCalled()
    expect(host.querySelector('[data-chart]')).toBeNull()
    state.result.groups[0]!.labels[0] = '重新打开'
    state.visible = true
    await flush()
    expect(chartMounted).toHaveBeenCalledOnce()
    expect(host.textContent).toContain('重新打开')
  })
  it('运行报表的指标卡和结果表不加载引擎，切到图表才加载', async () => {
    const factory = vi.fn(() => chartModule)
    vi.doMock(chartPath, factory)
    const ReportBlock = (await import('@/views/nocode/application/components/ReportBlock.vue')).default
    const reportConfig = config()
    reportConfig.display = ReportDisplay.METRIC
    const resource = reactive({
      id: 'report',
      kind: ResourceKind.REPORT,
      name: '统计',
      code: 'report',
      config: reportConfig
    }) as unknown as ApplicationResource
    start(() => h(ReportBlock, { applicationId: 'application', resource }))
    await flush()
    expect(factory).not.toHaveBeenCalled()
    resource.config.display = ReportDisplay.TABLE
    await flush()
    expect(factory).not.toHaveBeenCalled()
    resource.config.display = ReportDisplay.BAR
    await flush()
    expect(factory).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-chart]')).not.toBeNull()
  })
  it('配置预览按需加载，图表失败后保留配置并允许查看结果表', async () => {
    const pending = deferred<typeof chartModule>(),
      factory = vi.fn(() => pending.promise)
    vi.doMock(chartPath, factory)
    const ReportConfigEditor = (await import('@/views/nocode/application/components/ReportConfigEditor.vue')).default
    const state = reactive({ config: config() })
    start(() =>
      h(ReportConfigEditor, {
        modelValue: state.config,
        applicationId: 'application',
        objects,
        resources: [],
        'onUpdate:modelValue': (value: ReportConfig) => {
          state.config = value
        }
      })
    )
    await flush()
    expect(factory).not.toHaveBeenCalled()
    const snapshot = JSON.stringify(state.config)
    button('刷新预览').click()
    await flush()
    expect(api.previewReport).toHaveBeenCalledOnce()
    expect(factory).toHaveBeenCalledOnce()
    pending.reject(new Error('模拟预览图表分块网络错误'))
    await flush()
    expect(host.querySelector('.async-report-chart [role="alert"]')?.textContent).toContain(
      '请先保存未保存的内容，再刷新页面'
    )
    expect(host.textContent).not.toContain('重新加载图表')
    expect(JSON.stringify(state.config)).toBe(snapshot)
    const resultTableToggle = Array.from(host.querySelectorAll('label')).find(label =>
      label.textContent?.includes('显示结果表')
    )!
    resultTableToggle.querySelector<HTMLInputElement>('input')!.click()
    await flush()
    expect(host.querySelector('[data-result-table]')!.textContent).toContain('原数据')
    expect(host.querySelector('[data-result-table]')!.textContent).toContain('"metric":"3"')
    expect(JSON.stringify(state.config)).toBe(snapshot)
    expect(api.previewReport).toHaveBeenCalledOnce()
    expect(factory).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-chart]')).toBeNull()
  })
  it('金额格式随异步对象、聚合方式和字段切换更新，保留单位与自定义精度', async () => {
    const ReportConfigEditor = (await import('@/views/nocode/application/components/ReportConfigEditor.vue')).default
    const state = reactive({ config: config(), objects: {} as Record<string, PublishedObject> })
    state.config.metrics = [
      { id: 'money', name: '金额', operation: 'SUM', fieldId: 'amount', format: { unit: 'USD', decimals: 4 } }
    ]
    start(() =>
      h(ReportConfigEditor, {
        modelValue: state.config,
        applicationId: 'application',
        objects: state.objects,
        resources: []
      })
    )
    await flush()
    const metric = state.config.metrics[0]
    expect(metric.format?.financial).toBe(false)
    state.objects = structuredClone(objects)
    state.objects.orders.definition.fields = [
      { id: 'amount', key: 'amount', code: 'amount', name: '金额', type: FieldType.MONEY },
      { id: 'quantity', key: 'quantity', code: 'quantity', name: '数量', type: FieldType.INTEGER }
    ].map(field => ({ ...field, length: null, precision: 30, scale: 4, required: false, unique: false, sort: 0 }))
    await flush()
    expect(metric.format).toEqual({ unit: 'USD', decimals: 4, financial: true })
    metric.operation = 'COUNT_FIELD'
    await flush()
    expect(metric.format?.financial).toBe(false)
    metric.operation = 'SUM'
    await flush()
    expect(metric.format?.financial).toBe(true)
    metric.fieldId = 'quantity'
    await flush()
    expect(metric.format?.financial).toBe(false)
    metric.fieldId = 'amount'
    await flush()
    metric.format!.percent = true
    await flush()
    expect(metric.format?.financial).toBe(false)
    metric.format!.percent = false
    await flush()
    expect(metric.format?.financial).toBe(true)
    state.objects.orders.definition.fields[0].type = FieldType.DECIMAL
    await flush()
    expect(metric.format).toEqual({ unit: 'USD', decimals: 4, financial: false, percent: false })
    expect(api.previewReport).not.toHaveBeenCalled()
  })
})
