import type { PublishedObject } from '@/types/nocode/application'
import { ReportDisplay, type ReportConfig, type ReportResult } from '@/types/nocode/report'
import { defaultReport } from './report'

/**
 * 「统计视图多个数据来源」用例共用的夹具（契约 laneM 第 14 章，字段 ID 用 14.5 的占位名）：
 * 物件（名称）、入住记录（物件 → 物件、入住日、退房日、当月金额、次月金额、入住状态、备注）、
 * 支出（物件 → 物件、支出日期、金额、经办时间）、会计科目（科目名称）、
 * 会计凭证（日期、公司；明细「分录」：借方科目 / 贷方科目 → 会计科目、借方金额、贷方金额）。
 */
const field = (id: string, name: string, type: string) => ({ id, key: id, code: id, name, type })
const relation = (
  id: string,
  name: string,
  fieldId: string,
  targetObjectId: string,
  sourceDetailId: string | null
) => ({
  id,
  code: id,
  name,
  kind: 'REFERENCE',
  targetObjectId,
  fieldId,
  targetFieldId: null,
  required: false,
  onDelete: 'RESTRICT',
  sourceDetailId
})
const object = (objectId: string, objectName: string, definition: Record<string, unknown>) => ({
  objectId,
  versionNo: 1,
  checksum: objectId + '-v1',
  definition: {
    objectId,
    objectName,
    titleFieldId: '',
    settings: {},
    fieldOptions: {},
    relations: [],
    details: [],
    ...definition
  }
})
const statusOptions = {
  state: 'ACTIVE',
  options: [
    { code: 'IN', label: '入住中', disabled: false },
    { code: 'OUT', label: '已退房', disabled: false }
  ]
}

export const multiObjects = {
  property: object('property', '物件', { fields: [field('property_name', '名称', 'TEXT')] }),
  stay: object('stay', '入住记录', {
    fields: [
      field('stay_property', '物件', 'REFERENCE'),
      field('stay_check_in', '入住日', 'DATE'),
      field('stay_check_out', '退房日', 'DATE'),
      field('stay_cur_amount', '当月金额', 'MONEY'),
      field('stay_next_amount', '次月金额', 'MONEY'),
      field('stay_status', '入住状态', 'SELECT'),
      field('stay_note', '备注', 'TEXT')
    ],
    fieldOptions: { stay_status: statusOptions },
    relations: [relation('rStayProperty', '物件', 'stay_property', 'property', null)]
  }),
  expense: object('expense', '支出', {
    fields: [
      field('expense_property', '物件', 'REFERENCE'),
      field('expense_paid_on', '支出日期', 'DATE'),
      field('expense_amount', '金额', 'MONEY'),
      field('expense_logged_at', '经办时间', 'DATETIME'),
      field('expense_kind', '支出类别', 'SELECT')
    ],
    fieldOptions: {
      expense_kind: {
        state: 'ACTIVE',
        options: [
          { code: 'IN', label: '水电', disabled: false },
          { code: 'OUT', label: '清扫', disabled: false }
        ]
      }
    },
    relations: [relation('rExpenseProperty', '物件', 'expense_property', 'property', null)]
  }),
  account: object('account', '会计科目', { fields: [field('account_name', '科目名称', 'TEXT')] }),
  voucher: object('voucher', '会计凭证', {
    fields: [field('voucher_date', '日期', 'DATE'), field('voucher_company', '公司', 'TEXT')],
    relations: [
      relation('rDebit', '借方科目', 'line_debit_account', 'account', 'voucher_lines'),
      relation('rCredit', '贷方科目', 'line_credit_account', 'account', 'voucher_lines')
    ],
    details: [
      {
        id: 'voucher_lines',
        code: 'voucher_lines',
        name: '分录',
        tableName: 'voucher_lines',
        state: 'ACTIVE',
        fields: [
          field('line_debit_account', '借方科目', 'REFERENCE'),
          field('line_debit_amount', '借方金额', 'MONEY'),
          field('line_credit_account', '贷方科目', 'REFERENCE'),
          field('line_credit_amount', '贷方金额', 'MONEY')
        ],
        fieldOptions: {},
        indexes: []
      }
    ]
  })
} as unknown as Record<string, PublishedObject>

const dim = (fieldId: string, bucket: 'VALUE' | 'MONTH' = 'VALUE') => ({ fieldId, relationPath: null, bucket })

/** 契约 14.5 例 1 的配置原文（来源编码换成界面自动生成的 s2、s3，见报告「契约出入」）。 */
export const example1Config = (): ReportConfig => ({
  objectId: 'stay',
  display: ReportDisplay.PIVOT,
  timeZone: 'Asia/Tokyo',
  dimensions: [dim('stay_property')],
  columnDimensions: [dim('stay_check_in', 'MONTH')],
  metrics: [
    { id: 'cur', name: '当月金额', operation: 'SUM', fieldId: 'stay_cur_amount' },
    { id: 'nxt', name: '次月金额', operation: 'SUM', fieldId: 'stay_next_amount', sourceId: 's2' },
    { id: 'exp', name: '支出', operation: 'SUM', fieldId: 'expense_amount', sourceId: 's3' },
    {
      id: 'inc',
      name: '收入',
      operation: 'FORMULA',
      fieldId: null,
      formula: { operator: 'ADD', left: 'cur', right: 'nxt' }
    },
    {
      id: 'pro',
      name: '利润',
      operation: 'FORMULA',
      fieldId: null,
      formula: { operator: 'SUBTRACT', left: 'inc', right: 'exp' }
    }
  ],
  equal: {},
  filterFieldIds: [],
  dateFieldId: null,
  sortMetricId: 'pro',
  descending: true,
  sortBy: 'METRIC',
  limit: null,
  detailViewId: null,
  sourceName: '当月',
  extraSources: [
    {
      id: 's2',
      name: '次月',
      objectId: 'stay',
      dimensions: [dim('stay_property')],
      columnDimensions: [dim('stay_check_out', 'MONTH')]
    },
    {
      id: 's3',
      name: '支出',
      objectId: 'expense',
      dimensions: [dim('expense_property')],
      columnDimensions: [dim('expense_paid_on', 'MONTH')]
    }
  ],
  dimensionLabels: ['物件'],
  columnDimensionLabels: ['月份']
})
/** 例 2 = 例 1 去掉来源「支出」与指标 exp、pro，inc 改名 rev（「月收益」）；排序指标随 pro 一起去掉。 */
export const example2Config = (): ReportConfig => {
  const config = example1Config()
  return {
    ...config,
    metrics: [config.metrics[0], config.metrics[1], { ...config.metrics[3], id: 'rev', name: '月收益' }],
    sortMetricId: null,
    descending: false,
    sortBy: null,
    extraSources: [config.extraSources![0]]
  }
}
/** 契约 14.5 例 3 的配置原文（来源编码 credit → s2）。 */
export const example3Config = (): ReportConfig => ({
  objectId: 'voucher',
  grain: 'DETAIL',
  detailId: 'voucher_lines',
  display: ReportDisplay.PIVOT,
  timeZone: 'Asia/Tokyo',
  dimensions: [dim('line_debit_account')],
  columnDimensions: [dim('voucher_date', 'MONTH')],
  metrics: [
    { id: 'dr', name: '借方合计', operation: 'SUM', fieldId: 'line_debit_amount' },
    { id: 'cr', name: '贷方合计', operation: 'SUM', fieldId: 'line_credit_amount', sourceId: 's2' },
    {
      id: 'bal',
      name: '余额',
      operation: 'FORMULA',
      fieldId: null,
      formula: { operator: 'SUBTRACT', left: 'dr', right: 'cr' }
    }
  ],
  equal: {},
  filterFieldIds: [],
  dateFieldId: null,
  sortMetricId: null,
  descending: false,
  limit: null,
  detailViewId: null,
  sourceName: '借方',
  extraSources: [
    {
      id: 's2',
      name: '贷方',
      objectId: 'voucher',
      grain: 'DETAIL',
      detailId: 'voucher_lines',
      dimensions: [dim('line_credit_account')],
      columnDimensions: [dim('voucher_date', 'MONTH')]
    }
  ],
  dimensionLabels: ['科目']
})
/** 单来源的透视（存量形状）：入住记录，行 = 物件，列 = 入住日按月，指标 = 当月金额求和。 */
export const singleStayConfig = (): ReportConfig => ({
  ...defaultReport('stay'),
  display: ReportDisplay.PIVOT,
  dimensions: [dim('stay_property')],
  columnDimensions: [dim('stay_check_in', 'MONTH')],
  metrics: [{ id: 'cur', name: '当月金额', operation: 'SUM', fieldId: 'stay_cur_amount', conditions: null }]
})

/** 例 1 的运行端结果（契约 14.3 目标表里的 P1、P2 两行、7–9 月三列；只放用例用到的格）。 */
export function example1Result(): ReportResult {
  const values = (cur: string | null, nxt: string | null, exp: string | null, inc: string, pro: string) => ({
    cur,
    nxt,
    exp,
    inc,
    pro
  })
  const metrics = example1Config().metrics
  return {
    dimensionNames: ['物件'],
    metrics,
    groups: [],
    totals: values('83000', '26000', '16000', '109000', '93000'),
    totalGroups: 0,
    recordCount: 14,
    canExport: true,
    timeZone: 'Asia/Tokyo',
    sources: [
      { id: 'main', name: '当月', objectId: 'stay', recordCount: 5 },
      { id: 's2', name: '次月', objectId: 'stay', recordCount: 5 },
      { id: 's3', name: '支出', objectId: 'expense', recordCount: 4 }
    ],
    pivot: {
      rowDimensionNames: ['物件'],
      columnDimensionNames: ['月份'],
      rows: [
        { keys: ['P1'], labels: ['青山'] },
        { keys: ['P2'], labels: ['白川'] }
      ],
      columns: [
        { keys: ['2026-07'], labels: ['2026-07'] },
        { keys: ['2026-08'], labels: ['2026-08'] },
        { keys: ['2026-09'], labels: ['2026-09'] }
      ],
      cells: [
        { rowKeys: ['P1'], columnKeys: ['2026-07'], values: values('20000', null, '5000', '20000', '15000') },
        { rowKeys: ['P1'], columnKeys: ['2026-08'], values: values('18000', '10000', '7000', '28000', '21000') },
        { rowKeys: ['P2'], columnKeys: ['2026-08'], values: values('15000', null, null, '15000', '15000') },
        { rowKeys: ['P2'], columnKeys: ['2026-09'], values: values('22000', '0', '3000', '22000', '19000') },
        { rowKeys: ['P1'], columnKeys: [], values: values('38000', '10000', '12000', '48000', '36000') },
        { rowKeys: ['P2'], columnKeys: [], values: values('37000', '0', '3000', '37000', '34000') },
        { rowKeys: [], columnKeys: [], values: values('83000', '26000', '16000', '109000', '93000') }
      ],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 2,
      totalColumnGroups: 3
    }
  }
}
/** 例 3 的运行端结果（只要页脚：两个明细粒度来源）。 */
export function example3Result(): ReportResult {
  return {
    ...example1Result(),
    metrics: example3Config().metrics,
    totals: { dr: '2050', cr: '2000', bal: '50' },
    recordCount: 10,
    sources: [
      { id: 'main', name: '借方', objectId: 'voucher', recordCount: 5, detailName: '分录' },
      { id: 's2', name: '贷方', objectId: 'voucher', recordCount: 5, detailName: '分录' }
    ],
    pivot: {
      rowDimensionNames: ['科目'],
      columnDimensionNames: ['日期'],
      rows: [{ keys: ['A1'], labels: ['現金'] }],
      columns: [{ keys: ['2026-08'], labels: ['2026-08'] }],
      cells: [
        { rowKeys: ['A1'], columnKeys: ['2026-08'], values: { dr: '1000', cr: '300', bal: '700' } },
        { rowKeys: [], columnKeys: [], values: { dr: '2050', cr: '2000', bal: '50' } }
      ],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 1,
      totalColumnGroups: 1
    }
  }
}
