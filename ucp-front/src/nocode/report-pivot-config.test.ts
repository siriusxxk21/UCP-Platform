// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { MAX_PIVOT_DIMENSIONS, defaultReport, pivotDimensionLimitMessage } from './report'
import { validateReport } from './report-presentation'
import { prepareReportConfig } from './resource-config'
import { ReportDisplay, type ReportConfig } from '@/types/nocode/report'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'

const api = vi.hoisted(() => ({ previewReport: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: api, runtime: api }) }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['label'],
      setup: props => () => h('div', { 'data-condition': props.label })
    })
  }
})
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/AsyncReportChart.vue', () => ({ default: { render: () => null } }))

import ReportConfigEditor from '@/views/nocode/application/components/ReportConfigEditor.vue'

const objects = {
  flow: {
    objectId: 'flow',
    versionNo: 1,
    checksum: 'v1',
    definition: {
      objectId: 'flow',
      objectName: '公司流水',
      fields: [
        { id: 'company', name: '公司', type: 'TEXT' },
        { id: 'date', name: '日期', type: 'DATE' },
        { id: 'amount', name: '金额', type: 'MONEY' },
        // 多维度用例需要 10 个互不相同的字段；追加在末尾，不影响「默认挑第一个字段」等既有行为。
        ...['部门', '项目', '客户', '区域', '渠道', '类别', '负责人'].map((name, i) => ({
          id: 'f' + (i + 1),
          name,
          type: 'TEXT'
        }))
      ],
      fieldOptions: {},
      relations: [],
      details: []
    }
  }
} as unknown as Record<string, PublishedObject>
const view = (id: string, config: Record<string, unknown> = {}): ApplicationResource => ({
  id,
  kind: ResourceKind.VIEW,
  name: id === 'filtered' ? '带固定筛选的视图' : '全部流水',
  code: id,
  config: { objectId: 'flow', fieldIds: [], equal: {}, query: { fixed: [], defaults: {}, candidates: {} }, ...config }
})
const resources = [
  view('plain'),
  view('filtered', {
    query: { fixed: [{ fieldId: 'company', operator: 'eq', value: 'A' }], defaults: {}, candidates: {} }
  }),
  view('equal', { equal: { company: 'B' } })
]
const config = (): ReportConfig => ({
  ...defaultReport('flow'),
  display: ReportDisplay.BAR,
  dimensions: [{ fieldId: 'company', relationPath: null, bucket: 'VALUE' }],
  metrics: [{ id: 'in', name: '入金', operation: 'SUM', fieldId: 'amount', conditions: null }]
})

let app: App | undefined, host: HTMLDivElement, state: { config: ReportConfig }
const flush = async () => {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function register(target: App) {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ATabs', 'ATabPane', 'ASpace', 'AModal', 'AEmpty', 'ATable', 'AInput'])
    target.component(name, plain)
  target.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-form-item': props.label }, slots.default?.())
    })
  )
  target.component(
    'AAlert',
    defineComponent({ props: ['message'], setup: props => () => h('aside', { role: 'alert' }, props.message) })
  )
  target.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  target.component(
    'ASegmented',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            { 'data-segmented': true },
            props.options.map((o: { value: string; label: string }) =>
              h(
                'button',
                {
                  type: 'button',
                  'data-display': o.value,
                  onClick: () => {
                    emit('update:value', o.value)
                    emit('change', o.value)
                  }
                },
                o.label
              )
            )
          )
    })
  )
  target.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'mode'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value ?? '',
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value || undefined
                emit('update:value', value)
                emit('change', value)
              }
            },
            [
              h('option', { value: '' }, ''),
              ...(props.options || []).map((o: { value: string; label: string }) =>
                h('option', { value: o.value }, o.label)
              )
            ]
          )
    })
  )
  target.component(
    'ACheckbox',
    defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit, slots, attrs }) =>
        () =>
          h('label', attrs, [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              disabled: props.disabled,
              onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked)
            }),
            slots.default?.()
          ])
    })
  )
  target.component(
    'AInputNumber',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'number',
            value: props.value,
            onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value))
          })
    })
  )
}
async function mount(initial = config()) {
  state = reactive({ config: initial })
  app = createApp(() =>
    h(ReportConfigEditor, {
      modelValue: state.config,
      'onUpdate:modelValue': (value: ReportConfig) => {
        state.config = value
      },
      applicationId: 'app',
      objects,
      resources
    })
  )
  register(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const choose = async (display: string) => {
  host.querySelector<HTMLButtonElement>(`[data-display="${display}"]`)!.click()
  await flush()
}
const section = (name: string) => host.querySelector(`[data-section="${name}"]`)
const buttonIn = (root: Element | null, label: string) =>
  Array.from(root?.querySelectorAll('button') || []).find(b => b.textContent?.trim() === label)!
const checkbox = (label: string) =>
  Array.from(host.querySelectorAll('label'))
    .find(l => l.textContent?.includes(label))!
    .querySelector('input')!
async function pick(select: HTMLSelectElement, value: string) {
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
}
const formSelect = (label: string) => host.querySelector<HTMLSelectElement>(`[data-form-item="${label}"] select`)!
beforeEach(() => {
  vi.clearAllMocks()
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('配置器：透视表行列维度与开关', () => {
  it('展示方式出现「透视表」；选中后拆出行维度、列维度两栏与透视选项，默认值与约定一致', async () => {
    await mount()
    expect(host.querySelector('[data-display="PIVOT"]')!.textContent).toBe('透视表')
    expect(section('columns')).toBeNull()
    expect(section('pivot')).toBeNull()
    await choose('PIVOT')
    expect(state.config.display).toBe('PIVOT')
    expect(section('rows')!.querySelector('h4')!.textContent).toContain('行维度')
    expect(section('columns')!.querySelector('h4')!.textContent).toContain('列维度')
    expect(state.config.columnDimensions).toEqual([])
    expect(state.config.pivot).toEqual({
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE',
      maxColumnGroups: 24
    })
  })
  it('行维度至少 1 个、不再限 2 个；列维度可加到第 3、第 4 个；新增时默认挑行列都未用过的字段', async () => {
    await mount()
    await choose('PIVOT')
    expect(buttonIn(section('rows'), '移除').disabled).toBe(true)
    for (let i = 0; i < 3; i++) {
      buttonIn(section('rows'), '添加行维度').click()
      await flush()
    }
    // 新增维度的默认字段跳过数值字段（金额 amount 不再被默认挑进行维度）：对话层 2026-10-03 裁定
    expect(state.config.dimensions.map(d => d.fieldId)).toEqual(['company', 'date', 'f1', 'f2'])
    expect(buttonIn(section('rows'), '添加行维度').disabled).toBe(false)
    expect(section('rows')!.querySelectorAll('.config-row')).toHaveLength(4)
    buttonIn(section('columns'), '添加列维度').click()
    await flush()
    expect(state.config.columnDimensions).toEqual([{ fieldId: 'f3', relationPath: null, bucket: 'VALUE' }])
    for (let i = 0; i < 3; i++) {
      buttonIn(section('columns'), '添加列维度').click()
      await flush()
    }
    expect(state.config.columnDimensions!.map(d => d.fieldId)).toEqual(['f3', 'f4', 'f5', 'f6'])
    expect(section('columns')!.querySelectorAll('.config-row')).toHaveLength(4)
    expect(buttonIn(section('columns'), '添加列维度').disabled).toBe(false)
    // 日期字段仍可改分桶；同一日期字段不同分桶可同时作为行、列维度
    const [field, bucket] = Array.from(section('columns')!.querySelectorAll('select'))
    await pick(field, 'date')
    await pick(bucket, 'MONTH')
    expect(state.config.columnDimensions![0]).toEqual({ fieldId: 'date', relationPath: null, bucket: 'MONTH' })
    expect(section('columns')!.querySelector('[role="alert"]')).toBeNull()
    expect(() => validateReport(JSON.parse(JSON.stringify(state.config)))).not.toThrow()
  })
  it('行 + 列合计到 10 个时两个「添加」按钮都禁用并提示原因；移除一个即恢复', async () => {
    await mount()
    await choose('PIVOT')
    for (let i = 0; i < 5; i++) {
      buttonIn(section('rows'), '添加行维度').click()
      await flush()
    }
    for (let i = 0; i < 3; i++) {
      buttonIn(section('columns'), '添加列维度').click()
      await flush()
    }
    expect(state.config.dimensions.length + state.config.columnDimensions!.length).toBe(9)
    expect(host.querySelector('[data-pivot-limit]')).toBeNull()
    expect(buttonIn(section('rows'), '添加行维度').disabled).toBe(false)
    buttonIn(section('columns'), '添加列维度').click()
    await flush()
    expect(state.config.dimensions).toHaveLength(6)
    expect(state.config.columnDimensions).toHaveLength(4)
    expect(buttonIn(section('rows'), '添加行维度').disabled).toBe(true)
    expect(buttonIn(section('columns'), '添加列维度').disabled).toBe(true)
    const notice = host.querySelector('[data-pivot-limit]')!.textContent!
    expect(notice).toContain('合计最多 10 个')
    expect(notice).toContain('(行维度数+1)×(列维度数+1)')
    // 10 个字段各用一次，没有重复：行一栏无提示，列一栏只有上限提示
    expect(section('rows')!.querySelectorAll('[role="alert"]')).toHaveLength(0)
    expect(section('columns')!.querySelectorAll('[role="alert"]')).toHaveLength(1)
    expect(new Set([...state.config.dimensions, ...state.config.columnDimensions!].map(d => d.fieldId)).size).toBe(10)
    expect(() => validateReport(JSON.parse(JSON.stringify(state.config)))).not.toThrow()
    buttonIn(section('columns'), '移除').click()
    await flush()
    expect(buttonIn(section('columns'), '添加列维度').disabled).toBe(false)
    expect(host.querySelector('[data-pivot-limit]')).toBeNull()
  })
  it('非透视展示方式仍最多 2 个分组：添加按钮在 2 个时禁用；从透视表切走时只保留前两个行维度', async () => {
    await mount()
    for (const display of ['TABLE', 'BAR', 'LINE']) {
      await choose(display)
      if (state.config.dimensions.length < 2) {
        buttonIn(section('rows'), '添加分组').click()
        await flush()
        await pick(section('rows')!.querySelectorAll('select')[2] as HTMLSelectElement, 'date')
      }
      expect(state.config.dimensions.map(d => d.fieldId)).toEqual(['company', 'date'])
      expect(buttonIn(section('rows'), '添加分组').disabled).toBe(true)
    }
    await choose('PIVOT')
    buttonIn(section('rows'), '添加行维度').click()
    buttonIn(section('rows'), '添加行维度').click()
    await flush()
    // 新增维度的默认字段跳过数值字段（金额 amount 不再被默认挑进行维度）：对话层 2026-10-03 裁定
    expect(state.config.dimensions.map(d => d.fieldId)).toEqual(['company', 'date', 'f1', 'f2'])
    await choose('TABLE')
    expect(state.config.dimensions.map(d => d.fieldId)).toEqual(['company', 'date'])
    expect(host.textContent).toContain('非透视展示最多两个分组')
    expect(() => validateReport(JSON.parse(JSON.stringify(state.config)))).not.toThrow()
  })
  it('同一侧重复维度（同字段同分桶）提示并被保存校验拒绝', async () => {
    await mount()
    await choose('PIVOT')
    buttonIn(section('rows'), '添加行维度').click()
    await flush()
    await pick(section('rows')!.querySelectorAll('select')[2] as HTMLSelectElement, 'company')
    expect(section('rows')!.querySelector('[role="alert"]')!.textContent).toContain('行维度不能重复')
    expect(() => validateReport(JSON.parse(JSON.stringify(state.config)))).toThrow('透视表行维度不能重复')
  })
  it('行列用同一字段同一分桶时报错提示，保存校验拒绝', async () => {
    await mount()
    await choose('PIVOT')
    buttonIn(section('columns'), '添加列维度').click()
    await flush()
    expect(section('columns')!.querySelector('[role="alert"]')).toBeNull()
    await pick(section('columns')!.querySelector('select')!, 'company')
    expect(section('columns')!.querySelector('[role="alert"]')!.textContent).toContain('不能同时作为行维度和列维度')
    expect(() => validateReport(JSON.parse(JSON.stringify(state.config)))).toThrow('同一字段不能同时作为行维度和列维度')
  })
  it('透视选项写回配置：小计、行合计、列合计、占比基准、列组上限', async () => {
    await mount()
    await choose('PIVOT')
    checkbox('显示小计').click()
    checkbox('行合计').click()
    checkbox('列合计').click()
    await flush()
    await pick(formSelect('占比基准'), 'COLUMN')
    const limit = host.querySelector<HTMLInputElement>('[data-form-item="列组上限"] input')!
    limit.value = '12'
    limit.dispatchEvent(new Event('input'))
    await flush()
    expect(state.config.pivot).toEqual({
      subtotals: false,
      rowTotals: false,
      columnTotals: false,
      percent: 'COLUMN',
      maxColumnGroups: 12
    })
  })
  it('切走 PIVOT 清空 columnDimensions 与 pivot；切回按默认值重建', async () => {
    await mount()
    await choose('PIVOT')
    buttonIn(section('columns'), '添加列维度').click()
    checkbox('显示小计').click()
    await flush()
    await choose('TABLE')
    expect(state.config.columnDimensions).toEqual([])
    expect(state.config.pivot).toBeNull()
    expect(section('columns')).toBeNull()
    expect(() => validateReport(JSON.parse(JSON.stringify(state.config)))).not.toThrow()
    await choose('PIVOT')
    expect(state.config.pivot!.subtotals).toBe(true)
    await choose('METRIC')
    expect(state.config.columnDimensions).toEqual([])
    expect(state.config.pivot).toBeNull()
  })
  it('PIVOT 下固定条件、指标条件、用户可筛选字段、日期范围字段照常显示可编辑', async () => {
    await mount()
    await choose('PIVOT')
    expect(host.querySelector('[data-condition="设置固定条件"]')).not.toBeNull()
    expect(host.querySelector('[data-condition="指标条件"]')).not.toBeNull()
    expect(host.querySelector('[data-form-item="用户可筛选字段"] select')).not.toBeNull()
    await pick(formSelect('日期范围字段'), 'date')
    expect(state.config.dateFieldId).toBe('date')
  })
})

describe('配置器：明细允许编辑', () => {
  it('未选下钻视图时禁用且默认关；选了视图可开启，取消视图回到 null', async () => {
    await mount()
    const toggle = () => host.querySelector<HTMLInputElement>('[data-detail-editable] input')!
    expect(toggle().disabled).toBe(true)
    expect(toggle().checked).toBe(false)
    await pick(formSelect('下钻明细视图'), 'plain')
    expect(state.config.detailViewId).toBe('plain')
    expect(toggle().disabled).toBe(false)
    expect(toggle().checked).toBe(false)
    expect(state.config.detailEditable ?? false).toBe(false)
    toggle().click()
    await flush()
    expect(state.config.detailEditable).toBe(true)
    await pick(formSelect('下钻明细视图'), '')
    expect(state.config.detailViewId).toBeFalsy()
    expect(state.config.detailEditable).toBeNull()
    expect(toggle().disabled).toBe(true)
  })
  it('所选下钻视图带固定筛选（query.fixed 或 equal）时给灰色说明，不是警告', async () => {
    await mount()
    const note = () => host.querySelector('[data-detail-view-filtered]')
    await pick(formSelect('下钻明细视图'), 'plain')
    expect(note()).toBeNull()
    await pick(formSelect('下钻明细视图'), 'filtered')
    expect(note()!.textContent).toBe('统计会按该视图的固定筛选收窄。')
    expect(note()!.tagName).toBe('P')
    expect(note()!.classList.contains('hint')).toBe(true)
    expect(host.querySelector('[data-form-item="下钻明细视图"] [role="alert"]')).toBeNull()
    await pick(formSelect('下钻明细视图'), 'equal')
    expect(note()).not.toBeNull()
  })
})

describe('配置保存规范化与校验', () => {
  const fields = [{ value: 'company', field: { id: 'company', name: '公司', type: 'TEXT' } }] as never
  it('非透视表保存时列维度与透视选项归空；明细允许编辑只随下钻视图保存', () => {
    const saved = prepareReportConfig(
      { ...config(), columnDimensions: undefined, pivot: undefined, detailEditable: undefined },
      fields,
      []
    )
    expect(saved.columnDimensions).toEqual([])
    expect(saved.pivot).toBeNull()
    expect(saved.detailEditable).toBeNull()
    expect(prepareReportConfig({ ...config(), detailViewId: 'plain' }, fields, []).detailEditable).toBe(false)
  })
  it('透视表保存时 pivot 为 null 按默认值补齐', () => {
    const saved = prepareReportConfig({ ...config(), display: 'PIVOT', pivot: null }, fields, [])
    expect(saved.pivot).toMatchObject({ subtotals: true, percent: 'NONE', maxColumnGroups: 24 })
  })
  it('校验拒绝：非透视表带列维度、明细允许编辑却无下钻视图、列组上限越界', () => {
    expect(() =>
      validateReport({
        ...config(),
        columnDimensions: [{ fieldId: 'date', relationPath: null, bucket: 'VALUE' }]
      })
    ).toThrow('仅透视表可设置列维度')
    expect(() => validateReport({ ...config(), detailEditable: true })).toThrow('请先选择下钻明细视图')
    expect(() =>
      validateReport({
        ...config(),
        display: 'PIVOT',
        pivot: { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 101 }
      })
    ).toThrow('列组上限')
    expect(() => validateReport({ ...config(), display: 'PIVOT', dimensions: [] })).toThrow()
  })
  it('校验：透视表行 + 列 = 10 通过、= 11 拒绝并说明原因；非透视仍拒绝第 3 个分组', () => {
    const dims = (ids: string[]) => ids.map(fieldId => ({ fieldId, relationPath: null, bucket: 'VALUE' as const }))
    const ids = ['company', 'date', 'amount', 'f1', 'f2', 'f3', 'f4', 'f5', 'f6', 'f7', 'f8']
    const pivot = (rows: string[], columns: string[]): ReportConfig => ({
      ...config(),
      display: ReportDisplay.PIVOT,
      dimensions: dims(rows),
      columnDimensions: dims(columns),
      pivot: null
    })
    expect(MAX_PIVOT_DIMENSIONS).toBe(10)
    expect(() => validateReport(pivot(ids.slice(0, 5), ids.slice(5, 10)))).not.toThrow()
    expect(() => validateReport(pivot(ids.slice(0, 9), ids.slice(9, 10)))).not.toThrow()
    expect(() => validateReport(pivot(ids.slice(0, 10), []))).not.toThrow()
    expect(() => validateReport(pivot(ids.slice(0, 6), ids.slice(6, 11)))).toThrow(pivotDimensionLimitMessage)
    expect(pivotDimensionLimitMessage).toContain('维度过多，请减少')
    expect(() => validateReport(pivot(ids.slice(0, 2), ['f1', 'f1']))).toThrow('透视表列维度不能重复')
    for (const display of [ReportDisplay.TABLE, ReportDisplay.BAR, ReportDisplay.LINE])
      expect(() => validateReport({ ...config(), display, dimensions: dims(ids.slice(0, 3)) })).toThrow('最多两个')
    expect(() =>
      validateReport({ ...config(), display: ReportDisplay.TABLE, dimensions: dims(ids.slice(0, 2)) })
    ).not.toThrow()
  })
})
