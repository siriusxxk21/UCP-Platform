import { describe, expect, it } from 'vitest'
import type { ObjectField } from '@/types/nocode/object'
import type { PublishedObject } from '@/types/nocode/application'
import type { ReportConfig } from '@/types/nocode/report'
import {
  alignment,
  normalizeReportSources,
  reportSources,
  reportSourcesError,
  reportSourcesFooter,
  sourceFilterKey,
  sourceFilterProblem,
  type AlignmentOwner
} from './report-sources'
import {
  example1Config,
  example1Result,
  example3Config,
  example3Result,
  multiObjects
} from './report-multisource-fixture'

/** 契约 laneM 第 5 章：每一类一个相容例，L8·R1–R6 各一个不相容例（原因逐字）。 */
const R = {
  R1: '一个是引用其它对象的字段，另一个不是',
  R2: '两者引用的不是同一个对象（「物件」与「会计科目」）',
  R3: '日期按值对齐时两边都须是日期字段（不含时间）；含时间的请按日、按月或按年分组',
  R4: '两者不是同一套选项',
  R5: '小数、金额、百分比字段只能与同一个字段对齐',
  R6: '字段类型不同'
}
/** 支出上的「支出类别」换成与「入住状态」同一套选项（编码→标签一致）的对象集合。 */
const sameOptionObjects = (() => {
  const copy = JSON.parse(JSON.stringify(multiObjects)) as Record<string, PublishedObject>
  copy.expense.definition.fieldOptions.expense_kind = JSON.parse(
    JSON.stringify(copy.stay.definition.fieldOptions.stay_status)
  )
  return copy
})()
const field = (objectId: string, id: string, detailId?: string, objects = multiObjects): ObjectField => {
  const definition = objects[objectId].definition
  const owner = detailId ? definition.details.find(d => d.id === detailId)! : definition
  return owner.fields.find(f => f.id === id)!
}
const owner = (objectId: string, detailId?: string, objects = multiObjects): AlignmentOwner => ({
  objectId,
  detailId,
  objects
})
const check = (a: [string, string, string?], b: [string, string, string?], bucket: string, objects = multiObjects) =>
  alignment(
    field(a[0], a[1], a[2], objects),
    owner(a[0], a[2], objects),
    field(b[0], b[1], b[2], objects),
    owner(b[0], b[2], objects),
    bucket
  )

describe('F1 alignment：对齐相容性（契约第 5 章）', () => {
  it('每一类各一个相容例', () => {
    expect(check(['stay', 'stay_cur_amount'], ['stay', 'stay_cur_amount'], 'VALUE')).toEqual({
      ok: true,
      reason: '',
      category: 'SAME_FIELD'
    })
    expect(check(['stay', 'stay_property'], ['expense', 'expense_property'], 'VALUE').category).toBe('REFERENCE')
    expect(check(['stay', 'stay_check_in'], ['expense', 'expense_logged_at'], 'MONTH').category).toBe('DATE_BUCKET')
    expect(check(['stay', 'stay_check_in'], ['expense', 'expense_paid_on'], 'VALUE').category).toBe('DATE')
    expect(check(['stay', 'stay_status'], ['expense', 'expense_kind'], 'VALUE', sameOptionObjects).category).toBe(
      'OPTIONS'
    )
    expect(check(['stay', 'stay_note'], ['property', 'property_name'], 'VALUE').category).toBe('VALUE')
    // 同一明细里的同一个字段（例 3 的「日期」是主表字段；这里用明细字段本身）
    expect(
      check(
        ['voucher', 'line_debit_amount', 'voucher_lines'],
        ['voucher', 'line_debit_amount', 'voucher_lines'],
        'VALUE'
      ).category
    ).toBe('SAME_FIELD')
    // 引用同一对象：例 3 的借方科目 ↔ 贷方科目（都在明细「分录」上，都指向会计科目）
    expect(
      check(
        ['voucher', 'line_debit_account', 'voucher_lines'],
        ['voucher', 'line_credit_account', 'voucher_lines'],
        'VALUE'
      ).category
    ).toBe('REFERENCE')
  })

  it('L8·R1–R6 各一个不相容例，原因逐字', () => {
    // 先比原因（断言失败时原文直接出现在报错里），再比 ok
    const bad = (result: ReturnType<typeof check>, reason: string) => {
      expect(result.reason).toBe(reason)
      expect(result.ok).toBe(false)
    }
    bad(check(['stay', 'stay_property'], ['expense', 'expense_amount'], 'VALUE'), R.R1)
    bad(check(['stay', 'stay_property'], ['voucher', 'line_debit_account', 'voucher_lines'], 'VALUE'), R.R2)
    bad(check(['stay', 'stay_check_in'], ['expense', 'expense_logged_at'], 'VALUE'), R.R3)
    bad(check(['stay', 'stay_status'], ['expense', 'expense_kind'], 'VALUE'), R.R4)
    bad(check(['stay', 'stay_status'], ['stay', 'stay_note'], 'VALUE'), R.R4)
    bad(check(['stay', 'stay_check_in'], ['expense', 'expense_amount'], 'MONTH'), R.R5)
    bad(check(['stay', 'stay_cur_amount'], ['stay', 'stay_next_amount'], 'VALUE'), R.R5)
    bad(check(['stay', 'stay_note'], ['expense', 'expense_paid_on'], 'VALUE'), R.R6)
  })
})

describe('F2 sourceFilterKey：筛选键映射（契约第 8 章）', () => {
  const config = (): ReportConfig => {
    const c = example1Config()
    c.extraSources![1].filterTargets = { stay_status: 'expense_kind' }
    return c
  }
  const source = (c: ReportConfig, id: string) => reportSources(c).find(s => s.id === id)!
  it('显式 filterTargets 优先', () => {
    const c = config()
    expect(sourceFilterKey(c, source(c, 's3'), 'stay_status')).toBe('expense_kind')
    // 显式的也压过「按维度推」
    c.extraSources![0].filterTargets = { stay_check_in: 'stay_check_in' }
    expect(sourceFilterKey(c, source(c, 's2'), 'stay_check_in')).toBe('stay_check_in')
  })
  it('键是来源 1 的第 i 个维度 ⇒ 本来源第 i 个维度（先于「同对象同粒度」）', () => {
    const c = config()
    expect(sourceFilterKey(c, source(c, 's2'), 'stay_check_in')).toBe('stay_check_out')
    expect(sourceFilterKey(c, source(c, 's3'), 'stay_check_in')).toBe('expense_paid_on')
    expect(sourceFilterKey(c, source(c, 's3'), 'stay_property')).toBe('expense_property')
  })
  it('同对象同粒度 ⇒ 同一个键；推不出 ⇒ 空', () => {
    const c = config()
    expect(sourceFilterKey(c, source(c, 's2'), 'stay_note')).toBe('stay_note')
    expect(sourceFilterKey(c, source(c, 's3'), 'stay_note')).toBeUndefined()
    // 同对象、粒度不同：推不出
    const v = example3Config()
    delete v.extraSources![0].grain
    delete v.extraSources![0].detailId
    expect(sourceFilterKey(v, reportSources(v)[1], 'voucher_company')).toBeUndefined()
    expect(sourceFilterKey(example3Config(), reportSources(example3Config())[1], 'voucher_company')).toBe(
      'voucher_company'
    )
  })
  it('来源 1 原样', () => {
    const c = config()
    expect(sourceFilterKey(c, reportSources(c)[0], 'stay_note')).toBe('stay_note')
  })
  it('页面公共筛选：推不出映射时给 L14 整句', () => {
    const c = example1Config()
    expect(sourceFilterProblem(c, 'stay_property', false, '物件')).toBeNull()
    expect(sourceFilterProblem(c, 'stay_status', false, '入住状态')).toBe(
      '用户可筛选字段「入住状态」在来源「支出」里没有对应字段，请在该来源的「筛选对应」里指定，或把它从可筛选字段里去掉'
    )
  })
})

describe('配置校验与保存整理', () => {
  it('三个例子的配置原文通过前端校验', () => {
    expect(reportSourcesError(example1Config(), multiObjects)).toBeNull()
    expect(reportSourcesError(example3Config(), multiObjects)).toBeNull()
  })
  it('拦下的情形与文案（L1、L6、L8、L11、L13、L14）', () => {
    expect(reportSourcesError({ ...example1Config(), display: 'BAR' }, multiObjects)).toBe(
      '多个数据来源目前只用于透视表和汇总表'
    )
    const empty = example1Config()
    empty.extraSources![1].columnDimensions = [{ fieldId: '', relationPath: null, bucket: 'MONTH' }]
    expect(reportSourcesError(empty, multiObjects)).toBe('来源「支出」要为每个行维度、列维度各指定一个对应字段')
    const money = example1Config()
    money.extraSources![1].columnDimensions = [{ fieldId: 'expense_amount', relationPath: null, bucket: 'MONTH' }]
    expect(reportSourcesError(money, multiObjects)).toBe(
      '来源「支出」的「金额」不能与「月份」对齐：小数、金额、百分比字段只能与同一个字段对齐'
    )
    const lonely = example1Config()
    lonely.metrics = lonely.metrics.filter(m => m.id !== 'exp' && m.id !== 'pro')
    expect(reportSourcesError(lonely, multiObjects)).toBe('来源「支出」还没有指标，请为它加一个指标或删除这个来源')
    expect(reportSourcesError({ ...example1Config(), dateFieldId: 'stay_check_in' }, multiObjects)).toBe(
      '统计开放了日期范围，请为来源「次月」指定日期范围字段'
    )
    expect(reportSourcesError({ ...example1Config(), filterFieldIds: ['stay_status'] }, multiObjects)).toBe(
      '用户可筛选字段「入住状态」在来源「支出」里没有对应字段，请在该来源的「筛选对应」里指定，或把它从可筛选字段里去掉'
    )
  })
  it('单来源整理后不带四个新键、指标不带 sourceId', () => {
    const single: ReportConfig = {
      ...example1Config(),
      extraSources: [],
      sourceName: '',
      dimensionLabels: ['', ''],
      columnDimensionLabels: null,
      metrics: [{ id: 'cur', name: '当月金额', operation: 'SUM', fieldId: 'stay_cur_amount', sourceId: null }]
    }
    const result = normalizeReportSources(single)
    for (const key of ['sourceName', 'extraSources', 'dimensionLabels', 'columnDimensionLabels'])
      expect(result).not.toHaveProperty(key)
    expect(result.metrics[0]).not.toHaveProperty('sourceId')
  })
})

describe('页脚文案（契约第 12 章）', () => {
  it('按来源列条数；明细粒度来源写「N 行（明细「…」）」；单来源返回 null', () => {
    expect(reportSourcesFooter(example1Result())).toBe('当月 5 条 · 次月 5 条 · 支出 4 条 · 时区 Asia/Tokyo')
    expect(reportSourcesFooter(example3Result())).toBe(
      '借方 5 行（明细「分录」） · 贷方 5 行（明细「分录」） · 时区 Asia/Tokyo'
    )
    expect(reportSourcesFooter({ ...example1Result(), sources: null })).toBeNull()
  })
})
