// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import RecordQueryField from '@/views/nocode/application/components/RecordQueryField.vue'
import {
  RANGE_PRESETS,
  datePickerPresets,
  rangePickerPresets,
  relativeDateRange,
  shanghaiToday,
  type RelativeDateValue
} from './relative-date'
import { prepareViewConfig, type EditableViewConfig } from './resource-config'
import { newField } from './object-draft'

vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))

function field(type: string, id: string, name = type): ObjectField {
  return { ...newField(0, name), key: id, id, code: id, type: type as ObjectField['type'] }
}
const SAT = '2026-10-03'
const r = (value: RelativeDateValue, today = SAT) => relativeDateRange(value, today)

describe('相对日期 → 具体起止（与后端 RelativeDates.range 同一张表）', () => {
  it('星期六 10-03 的各项', () => {
    expect(r({ relative: 'TODAY' })).toEqual(['2026-10-03', '2026-10-03'])
    expect(r({ relative: 'YESTERDAY' })).toEqual(['2026-10-02', '2026-10-02'])
    expect(r({ relative: 'TOMORROW' })).toEqual(['2026-10-04', '2026-10-04'])
    expect(r({ relative: 'THIS_WEEK' })).toEqual(['2026-09-28', '2026-10-04'])
    expect(r({ relative: 'LAST_WEEK' })).toEqual(['2026-09-21', '2026-09-27'])
    expect(r({ relative: 'NEXT_WEEK' })).toEqual(['2026-10-05', '2026-10-11'])
    expect(r({ relative: 'THIS_MONTH' })).toEqual(['2026-10-01', '2026-10-31'])
    expect(r({ relative: 'LAST_MONTH' })).toEqual(['2026-09-01', '2026-09-30'])
    expect(r({ relative: 'NEXT_MONTH' })).toEqual(['2026-11-01', '2026-11-30'])
    expect(r({ relative: 'THIS_YEAR' })).toEqual(['2026-01-01', '2026-12-31'])
    expect(r({ relative: 'LAST_YEAR' })).toEqual(['2025-01-01', '2025-12-31'])
    expect(r({ relative: 'PAST_N_DAYS', n: 7 })).toEqual(['2026-09-27', '2026-10-03'])
    expect(r({ relative: 'PAST_N_DAYS', n: 30 })).toEqual(['2026-09-04', '2026-10-03'])
    expect(r({ relative: 'NEXT_N_DAYS', n: 7 })).toEqual(['2026-10-03', '2026-10-09'])
  })
  it('周一 / 周日、跨年、闰年', () => {
    expect(r({ relative: 'THIS_WEEK' }, '2026-09-28')).toEqual(['2026-09-28', '2026-10-04'])
    expect(r({ relative: 'THIS_WEEK' }, '2026-10-04')).toEqual(['2026-09-28', '2026-10-04'])
    expect(r({ relative: 'LAST_MONTH' }, '2027-01-31')).toEqual(['2026-12-01', '2026-12-31'])
    expect(r({ relative: 'YESTERDAY' }, '2028-03-01')).toEqual(['2028-02-29', '2028-02-29'])
    expect(r({ relative: 'LAST_MONTH' }, '2028-03-31')).toEqual(['2028-02-01', '2028-02-29'])
  })
  it('今天按上海时间：上海 00:30 已是新的一天，23:59 仍是当天', () => {
    expect(shanghaiToday(Date.UTC(2026, 9, 2, 16, 30))).toBe('2026-10-03')
    expect(shanghaiToday(Date.UTC(2026, 9, 2, 15, 59))).toBe('2026-10-02')
  })
})

describe('运行端日期控件的快捷项（业务方：今天、昨天、本周、上周、本月、上月、本年、过去 7 天、过去 30 天、未来 7 天）', () => {
  it('区间快捷项按裁定顺序，点了直接是具体起止', () => {
    const presets = rangePickerPresets(SAT)
    expect(presets.map(p => p.label)).toEqual([
      '今天',
      '昨天',
      '本周',
      '上周',
      '本月',
      '上月',
      '本年',
      '过去 7 天',
      '过去 30 天',
      '未来 7 天'
    ])
    expect(RANGE_PRESETS).toHaveLength(10)
    const month = presets.find(p => p.label === '本月')
    expect(month?.value.map(d => d.format('YYYY-MM-DD'))).toEqual(['2026-10-01', '2026-10-31'])
    const stamp = rangePickerPresets(SAT, true).find(p => p.label === '今天')
    expect(stamp?.value.map(d => d.format('YYYY-MM-DDTHH:mm:ss'))).toEqual([
      '2026-10-03T00:00:00',
      '2026-10-03T23:59:59'
    ])
  })
  it('单日快捷项只有今天、昨天', () => {
    expect(datePickerPresets(SAT).map(p => [p.label, p.value.format('YYYY-MM-DD')])).toEqual([
      ['今天', '2026-10-03'],
      ['昨天', '2026-10-02']
    ])
  })
})

describe('视图「默认查询」存相对日期', () => {
  const fields = [field(FieldType.DATE, 'checkout', '退房日'), field(FieldType.TEXT, 'memo', '备注')]
  const config = (defaults: Record<string, unknown>) =>
    ({
      objectId: 'o',
      fieldIds: ['checkout', 'memo'],
      equal: {},
      list: { queryFieldIds: ['checkout', 'memo'], advancedFieldIds: null, columnWidths: {}, batchDelete: false },
      query: { fixed: [], defaults, candidates: {} },
      interaction: { buttons: [], actionIds: [], editMode: 'DRAWER', detailMode: 'DRAWER' }
    }) as unknown as EditableViewConfig
  it('日期字段存 { relative }，原样保留；存量具体日期照旧', () => {
    expect(prepareViewConfig(config({ checkout: { relative: 'THIS_MONTH' } }), fields, []).query.defaults).toEqual({
      checkout: { relative: 'THIS_MONTH' }
    })
    expect(prepareViewConfig(config({ checkout: '2026-10-03' }), fields, []).query.defaults).toEqual({
      checkout: '2026-10-03'
    })
  })
  it('非日期字段、天数不对拒绝', () => {
    expect(() => prepareViewConfig(config({ memo: { relative: 'TODAY' } }), fields, [])).toThrow('日期或日期时间字段')
    expect(() => prepareViewConfig(config({ checkout: { relative: 'PAST_N_DAYS', n: 0 } }), fields, [])).toThrow(
      '1–3650'
    )
  })
})

describe('列表常用查询的日期字段', () => {
  const mounted: App[] = []
  afterEach(() => mounted.splice(0).forEach(app => app.unmount()))
  async function mount(props: Record<string, unknown>) {
    const value = ref<unknown>(null)
    const app = createApp({
      setup: () => () =>
        h(RecordQueryField, {
          modelValue: value.value,
          'onUpdate:modelValue': (v: unknown) => (value.value = v),
          field: field(FieldType.DATE, 'checkout', '退房日'),
          choices: [],
          ...props
        })
    })
    app.use(Antd)
    const host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    mounted.push(app)
    for (let i = 0; i < 6; i++) await nextTick()
    return { host, value }
  }
  it('列表上传 relative-dates：今天 / 本周 / 本月一点即选，存相对表达', async () => {
    const { host, value } = await mount({ relativeDates: true })
    host.querySelector<HTMLButtonElement>('[data-quick="THIS_MONTH"]')?.click()
    for (let i = 0; i < 4; i++) await nextTick()
    expect(value.value).toEqual({ relative: 'THIS_MONTH' })
  })
  it('不传时（统计固定筛选等）仍是原来的日期选择器', async () => {
    const { host } = await mount({})
    expect(host.querySelector('[data-quick]')).toBeNull()
    expect(host.querySelector('.ant-picker')).not.toBeNull()
  })
})
