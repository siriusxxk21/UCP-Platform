// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { Form } from 'ant-design-vue'
import TaskEfficiency from '@/views/nocode/task-center/TaskEfficiency.vue'
import { nocodePlatformKey, type NocodePlatform } from '@/nocode/platform'
import type { TaskEfficiencyOverview } from '@/types/nocode/task-efficiency'

vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({
  default: defineComponent({
    props: ['id'],
    setup: props => () => h('div', { 'data-task-detail': props.id }, '任务详情')
  })
}))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({
  default: defineComponent({
    props: ['config', 'result'],
    emits: ['select'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          { 'data-chart': props.config.display },
          props.result.groups.map((row: { labels: string[] }, index: number) =>
            h('button', { onClick: () => emit('select', index, 'standardHours') }, row.labels.join(' / '))
          )
        )
  })
}))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title'],
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', { 'data-drawer': props.title }, slots.formItems?.()) : null
  })
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource', 'title'],
    emits: ['change'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('section', { 'data-table': props.title }, [
          slots.search?.(),
          h(
            'button',
            {
              onClick: () =>
                emit(
                  'change',
                  { current: 1, pageSize: 10 },
                  {},
                  { columnKey: 'recordCount', order: 'ascend' },
                  { action: 'sort' }
                )
            },
            '记录数升序'
          ),
          h(
            'button',
            { onClick: () => emit('change', { current: 2, pageSize: 10 }, {}, {}, { action: 'paginate' }) },
            '下一页'
          ),
          ...props.dataSource.map((record: object) =>
            h(
              'div',
              { 'data-row': true },
              props.columns.map((column: { key: string; dataIndex?: string }) =>
                h(
                  'div',
                  slots.bodyCell?.({ column, record }) ||
                    (column.dataIndex ? String((record as Record<string, unknown>)[column.dataIndex]) : '')
                )
              )
            )
          ),
          !props.dataSource.length ? slots.empty?.() : null
        ])
  })
}))

let app: App | undefined, host: HTMLElement
const permitted = ref(true)
const summary: TaskEfficiencyOverview = {
  standardMinutes: 90,
  recordCount: 2,
  employeeCount: 1,
  completedNodeCount: 3,
  activeNodeCount: 4,
  overdueNodeCount: 1,
  trend: [{ date: '2026-10-07', standardMinutes: 90, recordCount: 2 }],
  employees: [{ employeeId: 17, employeeName: '张三', standardMinutes: 90, recordCount: 2 }]
}
const employee = {
  employeeId: 17,
  employeeName: '张三',
  standardMinutes: 90,
  recordCount: 2,
  participatedTaskCount: 1,
  completedNodeCount: 3,
  activeNodeCount: 4,
  overdueNodeCount: 1
}
const task = {
  rootTaskId: 'root',
  title: '安装网络',
  templateId: 'tpl',
  templateName: '房间配置',
  templateVersion: 3,
  status: 'RUNNING',
  referenceMinutes: 240,
  standardMinutes: 90,
  recordCount: 2,
  employeeCount: 1,
  completedNodeCount: 1,
  totalNodeCount: 3,
  cancelledNodeCount: 1,
  overdueNodeCount: 1,
  elapsedMinutes: null,
  actualEnd: null,
  assigneeName: '张三'
}
const record = {
  taskId: 'child',
  taskTitle: '配置 WiFi',
  rootTaskId: 'root',
  rootTitle: '安装网络',
  entryKey: 'wifi',
  entryName: 'WiFi 配置',
  employeeId: 17,
  employeeName: '张三',
  recordId: 'record-1',
  ruleMode: 'RECORD_ONCE',
  quantity: 1,
  unitMinutes: 90,
  standardMinutes: 90,
  firstCountedAt: '2026-10-07T10:00:00',
  lastHandledAt: '2026-10-07T11:00:00'
}
const api = {
  efficiencyOverview: vi.fn(),
  efficiencyEmployees: vi.fn(),
  efficiencyTasks: vi.fn(),
  efficiencyRecords: vi.fn(),
  efficiencyOptions: vi.fn()
}
const flush = async () => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
const click = async (text: string, root: ParentNode = host) => {
  const button = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    item => item.textContent?.trim() === text
  )
  expect(button, `按钮 ${text}`).toBeTruthy()
  if (!button) throw new Error(`未找到按钮 ${text}`)
  button.click()
  await flush()
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(TaskEfficiency)
  app.provide(nocodePlatformKey, { taskCenter: api, hasPermission: () => permitted.value } as unknown as NocodePlatform)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ASpace', 'AFormItem', 'ASpin', 'APopover', 'ATag', 'ASkeleton']) app.component(name, plain)
  // 使用真实 Form，验证 model、原生提交与 finish 链路，而不是替身直接发事件。
  app.component('AForm', Form)
  app.component(
    'AButton',
    defineComponent({
      props: ['htmlType'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, type: props.htmlType || 'button' }, slots.default?.())
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()])
    })
  )
  app.component('AEmpty', defineComponent({ props: ['description'], setup: props => () => h('p', props.description) }))
  app.component('AResult', defineComponent({ props: ['title'], setup: props => () => h('p', props.title) }))
  app.component('ATabPane', plain)
  app.component(
    'ATabs',
    defineComponent({
      props: ['activeKey'],
      emits: ['update:activeKey'],
      setup:
        (props, { slots, emit }) =>
        () => {
          const children = slots.default?.() || []
          return h('div', [
            h(
              'nav',
              children.map(child =>
                h('button', { onClick: () => emit('update:activeKey', child.key) }, child.props?.tab)
              )
            ),
            children.filter(child => child.key === props.activeKey)
          ])
        }
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'search', 'dropdownVisibleChange'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('div', [
            h(
              'select',
              {
                ...attrs,
                value: props.value ?? '',
                onFocus: () => emit('dropdownVisibleChange', true),
                onChange: (e: Event) =>
                  emit(
                    'update:value',
                    props.options.find(
                      (row: { value: string | number }) => String(row.value) === (e.target as HTMLSelectElement).value
                    )?.value
                  )
              },
              [
                h('option', { value: '' }, '全部'),
                ...props.options.map((row: { value: string | number; label: string }) =>
                  h('option', { value: row.value }, row.label)
                )
              ]
            ),
            h('input', {
              'aria-label': `搜索${attrs['aria-label']}`,
              onInput: (event: Event) => emit('search', (event.target as HTMLInputElement).value)
            })
          ])
    })
  )
  app.component(
    'ARangePicker',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            'aria-label': '统计日期',
            value: props.value?.join(','),
            onChange: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value.split(','))
          })
    })
  )
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.mount(host)
  await flush()
}
beforeEach(() => {
  permitted.value = true
  vi.clearAllMocks()
  api.efficiencyOverview.mockResolvedValue(summary)
  api.efficiencyEmployees.mockResolvedValue({ list: [employee], total: 21 })
  api.efficiencyTasks.mockResolvedValue({ list: [task], total: 1 })
  api.efficiencyRecords.mockResolvedValue({ list: [record], total: 1 })
  api.efficiencyOptions.mockResolvedValue({
    employees: [{ id: 17, name: '张三' }],
    templates: [{ id: 'tpl', name: '房间配置' }]
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('老板能效统计工作区', () => {
  it('无管理入口权限时不发请求、不展示统计数据', async () => {
    permitted.value = false
    await mount()
    expect(host.textContent).toContain('暂无能效统计权限')
    expect(api.efficiencyOverview).not.toHaveBeenCalled()
    expect(api.efficiencyOptions).not.toHaveBeenCalled()
  })
  it('核心指标与双图展示真实工时，参考时长仅在任务表独立展示', async () => {
    await mount()
    expect(host.querySelectorAll('.efficiency-metric')).toHaveLength(4)
    expect(host.textContent).toContain('1 小时 30 分钟')
    expect(host.querySelectorAll('[data-chart]')).toHaveLength(2)
    await click('任务分析')
    expect(host.querySelector('[data-table="任务工作量"]')?.textContent).toContain('4 小时')
    expect(host.querySelector('[data-table="任务工作量"]')?.textContent).toContain('1 小时 30 分钟')
    expect(host.querySelector('[data-table="任务工作量"]')?.textContent).toContain('另有 1 个已取消节点')
    expect(host.querySelectorAll('.efficiency-metric')).toHaveLength(0)
  })
  it('日期、员工、模板共同应用到总览与列表，分页排序只传服务端约定参数', async () => {
    await mount()
    const date = host.querySelector<HTMLInputElement>('[aria-label="统计日期"]')!
    date.value = '2026-09-01,2026-09-30'
    date.dispatchEvent(new Event('change'))
    for (const [label, value] of [
      ['员工', '17'],
      ['模板', 'tpl']
    ]) {
      const select = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
      select.value = value
      select.dispatchEvent(new Event('change'))
    }
    await click('查询')
    expect(api.efficiencyOverview).toHaveBeenLastCalledWith({
      from: '2026-09-01',
      to: '2026-09-30',
      employeeId: 17,
      templateId: 'tpl'
    })
    await click('员工分析')
    await click('记录数升序')
    expect(api.efficiencyEmployees).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 1, sortBy: 'recordCount', descending: false })
    )
    await click('下一页')
    expect(api.efficiencyEmployees).toHaveBeenLastCalledWith({
      from: '2026-09-01',
      to: '2026-09-30',
      employeeId: 17,
      templateId: 'tpl',
      pageNo: 2,
      pageSize: 10,
      sortBy: 'recordCount',
      descending: false
    })
  })
  it('刷新和切换页签保留页码与排序，重新排序才回第一页', async () => {
    await mount()
    await click('员工分析')
    await click('记录数升序')
    await click('下一页')
    await click('刷新')
    expect(api.efficiencyEmployees).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 2, sortBy: 'recordCount', descending: false })
    )
    await click('任务分析')
    await click('员工分析')
    expect(api.efficiencyEmployees).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 2, sortBy: 'recordCount', descending: false })
    )
    await click('记录数升序')
    expect(api.efficiencyEmployees).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1 }))
  })
  it('员工看任务沿用已查询范围，不夹带尚未查询的日期，也不重复加载任务列表', async () => {
    await mount()
    const original = api.efficiencyOverview.mock.calls[0][0]
    await click('员工分析')
    const date = host.querySelector<HTMLInputElement>('[aria-label="统计日期"]')!
    date.value = '2026-09-01,2026-09-30'
    date.dispatchEvent(new Event('change'))
    await click('看任务')
    expect(api.efficiencyTasks).toHaveBeenCalledTimes(1)
    expect(api.efficiencyTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: original.from, to: original.to, employeeId: 17 })
    )
    expect(host.textContent).toContain('筛选已修改')
  })
  it('员工图下钻传递员工和期间范围，明细可打开真实任务节点详情', async () => {
    await mount()
    await click('张三', host.querySelector('[data-chart="BAR"]')!)
    expect(api.efficiencyRecords).toHaveBeenCalledWith(
      expect.objectContaining({ employeeId: 17, pageNo: 1, pageSize: 10 })
    )
    const drawer = host.querySelector('[data-drawer]')!
    expect(drawer.textContent).toContain('WiFi 配置')
    await click('任务详情', drawer)
    expect(host.querySelector('[data-task-detail="child"]')).not.toBeNull()
  })
  it('任务工时下钻只加任务组约束，不丢失共享筛选', async () => {
    await mount()
    await click('任务分析')
    await click('工时明细')
    expect(api.efficiencyRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ rootTaskId: 'root', pageNo: 1, pageSize: 10 })
    )
    expect(host.querySelector('[data-drawer]')?.getAttribute('data-drawer')).toBe('安装网络 · 工时明细')
    const drawer = host.querySelector('[data-drawer]')!
    const search = drawer.querySelector<HTMLInputElement>('input[placeholder="员工、任务或办理项"]')!
    search.value = 'WiFi'
    search.dispatchEvent(new Event('input'))
    await flush()
    expect(api.efficiencyRecords).toHaveBeenCalledTimes(1)
    await click('查询', drawer)
    expect(api.efficiencyRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ rootTaskId: 'root', search: 'WiFi', pageNo: 1 })
    )
  })
  it('总览可追溯全部计量记录，不注入员工或任务组限制', async () => {
    await mount()
    await click('查看计量明细')
    expect(api.efficiencyRecords).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, pageSize: 10 }))
    const query = api.efficiencyRecords.mock.calls.at(-1)![0]
    expect(query.employeeId).toBeUndefined()
    expect(query.rootTaskId).toBeUndefined()
    expect(host.querySelector('[data-drawer]')?.textContent).toContain('1 × 1 小时 30 分钟')
  })
  it('撤销权限后隐藏明细和统计，迟到的员工数据不会在重新授权后泄漏旧结果', async () => {
    await mount()
    let resolveOld: (value: object) => void = () => undefined
    api.efficiencyEmployees.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveOld = resolve
        })
    )
    await click('员工分析')
    permitted.value = false
    await flush()
    expect(host.textContent).toContain('暂无能效统计权限')
    resolveOld({ list: [{ ...employee, employeeName: '过期数据' }], total: 1 })
    await flush()
    expect(host.textContent).not.toContain('过期数据')
    permitted.value = true
    await flush()
    expect(host.textContent).toContain('张三')
    expect(host.textContent).not.toContain('过期数据')
  })
  it('调整前后混合单价显示分段计时，不显示虚假的零单价', async () => {
    api.efficiencyRecords.mockResolvedValue({
      list: [{ ...record, unitMinutes: null, quantity: 5, standardMinutes: 80 }],
      total: 1
    })
    await mount()
    await click('查看计量明细')
    const drawer = host.querySelector('[data-drawer]')!
    expect(drawer.textContent).toContain('5 · 分段计时')
    expect(drawer.textContent).not.toContain('5 × 0')
  })
  it('失败清除旧指标、呈现重试，不把错误显示成零工时', async () => {
    await mount()
    api.efficiencyOverview.mockRejectedValueOnce(new Error('统计暂不可用'))
    await click('刷新')
    expect(host.textContent).toContain('统计暂不可用')
    expect(host.querySelector('.efficiency-metric--primary strong')?.textContent).toBe('—')
    await click('重新加载')
    expect(host.querySelector('.efficiency-metric--primary strong')?.textContent).toBe('1 小时 30 分钟')
  })
  it('无记录有明确空态，无效日期阻止请求', async () => {
    api.efficiencyOverview.mockResolvedValueOnce({
      ...summary,
      standardMinutes: 0,
      recordCount: 0,
      employees: [],
      trend: []
    })
    await mount()
    expect(host.textContent).toContain('此期间暂无计量记录')
    const date = host.querySelector<HTMLInputElement>('[aria-label="统计日期"]')!
    date.value = '2024-01-01,2026-01-01'
    date.dispatchEvent(new Event('change'))
    await click('查询')
    expect(host.textContent).toContain('366 天')
    expect(api.efficiencyOverview).toHaveBeenCalledTimes(1)
  })
  it('快速刷新时迟到请求不能覆盖最新统计', async () => {
    await mount()
    let resolveOld: (value: TaskEfficiencyOverview) => void = () => undefined
    api.efficiencyOverview.mockImplementationOnce(() => new Promise(resolve => (resolveOld = resolve)))
    await click('刷新')
    api.efficiencyOverview.mockResolvedValueOnce({ ...summary, standardMinutes: 180 })
    await click('刷新')
    resolveOld({ ...summary, standardMinutes: 15 })
    await flush()
    expect(host.querySelector('.efficiency-metric--primary strong')?.textContent).toBe('3 小时')
  })
  it('两个候选搜索并行且返回乱序时不会相互覆盖，模板候选不带自身条件', async () => {
    await mount()
    const template = host.querySelector<HTMLSelectElement>('select[aria-label="模板"]')!
    template.value = 'tpl'
    template.dispatchEvent(new Event('change'))
    await click('查询')
    template.dispatchEvent(new Event('focus'))
    await flush()
    expect(api.efficiencyOptions).toHaveBeenLastCalledWith(expect.objectContaining({ templateId: undefined }))
    let resolveEmployee: (value: object) => void = () => undefined
    let resolveTemplate: (value: object) => void = () => undefined
    api.efficiencyOptions.mockImplementation(
      (query: { search?: string }) =>
        new Promise(resolve => {
          if (query.search === '李四') resolveEmployee = resolve
          else resolveTemplate = resolve
        })
    )
    for (const [label, value] of [
      ['员工', '李四'],
      ['模板', '设备']
    ]) {
      const input = host.querySelector<HTMLInputElement>(`input[aria-label="搜索${label}"]`)!
      input.value = value
      input.dispatchEvent(new Event('input'))
    }
    await new Promise(resolve => setTimeout(resolve, 280))
    resolveTemplate({ employees: [], templates: [{ id: 'equipment', name: '设备登记' }] })
    await flush()
    resolveEmployee({ employees: [{ id: 18, name: '李四' }], templates: [] })
    await flush()
    expect(host.querySelector('select[aria-label="员工"]')?.textContent).toContain('李四')
    expect(host.querySelector('select[aria-label="模板"]')?.textContent).toContain('设备登记')
    expect(host.querySelector('select[aria-label="模板"]')?.textContent).toContain('房间配置')
  })
  it('任务关键字只在提交后用于任务列表，排序翻页刷新保留，概览不带该条件', async () => {
    await mount()
    await click('任务分析')
    const input = host.querySelector<HTMLInputElement>('input[placeholder="搜索整组任务"]')!
    input.value = '网络'
    input.dispatchEvent(new Event('input'))
    await click('刷新')
    expect(api.efficiencyTasks).toHaveBeenLastCalledWith(expect.objectContaining({ search: undefined }))
    await click('查询任务')
    expect(api.efficiencyTasks).toHaveBeenLastCalledWith(expect.objectContaining({ search: '网络', pageNo: 1 }))
    await click('记录数升序')
    expect(api.efficiencyTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ search: '网络', sortBy: 'recordCount' })
    )
    expect(api.efficiencyOverview).toHaveBeenCalledTimes(1)
  })
})
