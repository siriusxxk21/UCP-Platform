import type { PublishedObject } from '@/types/nocode/application'
import { ReportDisplay, type ReportConfig } from '@/types/nocode/report'
import { defaultReport } from './report'

/**
 * 「按明细行统计」用例共用的夹具（与后端任务书 5.0 同形状）：
 * 凭证（主表：日期、摘要、入金、出金、公司、期间；明细「分录」「附注」，另有一个已停用的明细）、科目、公司档案。
 * 关系：分录上的「借方科目」「贷方科目」→ 科目（sourceDetailId = 分录）；主表上的「公司档案」→ 公司档案。
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

export const grainObjects = {
  voucher: object('voucher', '凭证', {
    fields: [
      field('date', '日期', 'DATE'),
      field('memo', '摘要', 'TEXT'),
      field('income', '入金', 'MONEY'),
      field('outgo', '出金', 'MONEY'),
      field('company', '公司', 'REFERENCE'),
      field('period', '期间', 'INTEGER')
    ],
    relations: [
      relation('rDebit', '借方科目', 'debitAccount', 'account', 'lines'),
      relation('rCredit', '贷方科目', 'creditAccount', 'account', 'lines'),
      relation('rCompany', '公司档案', 'company', 'company', null)
    ],
    details: [
      {
        id: 'lines',
        code: 'lines',
        name: '分录',
        tableName: 'voucher_lines',
        state: 'ACTIVE',
        fields: [
          field('debitAccount', '借方科目', 'REFERENCE'),
          field('debitAmount', '借方金额', 'MONEY'),
          field('creditAccount', '贷方科目', 'REFERENCE'),
          field('creditAmount', '贷方金额', 'MONEY'),
          field('lineType', '分录类型', 'SELECT'),
          field('lineRetired', '已停用的分录字段', 'TEXT')
        ],
        fieldOptions: {
          lineType: {
            state: 'ACTIVE',
            options: [
              { code: 'NORMAL', label: '正常', disabled: false },
              { code: 'ADJUST', label: '调整', disabled: false }
            ]
          },
          lineRetired: { state: 'INACTIVE', options: [] }
        },
        indexes: []
      },
      {
        id: 'notes',
        code: 'notes',
        name: '附注',
        tableName: 'voucher_notes',
        state: 'ACTIVE',
        fields: [field('note', '备注', 'TEXT'), field('noteAmount', '附注金额', 'MONEY')],
        fieldOptions: {},
        indexes: []
      },
      {
        id: 'retired',
        code: 'retired',
        name: '旧明细',
        tableName: 'voucher_retired',
        state: 'INACTIVE',
        fields: [field('retiredText', '旧字段', 'TEXT')],
        fieldOptions: {},
        indexes: []
      }
    ]
  }),
  account: object('account', '科目', {
    fields: [field('accountCode', '科目编码', 'TEXT'), field('accountName', '科目名称', 'TEXT')]
  }),
  company: object('company', '公司档案', {
    fields: [field('companyName', '公司名称', 'TEXT')]
  }),
  /** 没有任何内部明细的对象：粒度第二项应禁用。 */
  plain: object('plain', '流水', {
    fields: [field('title', '标题', 'TEXT'), field('amount', '金额', 'MONEY')]
  })
} as unknown as Record<string, PublishedObject>

/** 主记录粒度的存量形状：透视表，行 = 摘要，列 = 日期按月，指标 = 求和入金 + 记录计数。不带 grain / detailId。 */
export const rootPivotConfig = (): ReportConfig => ({
  ...defaultReport('voucher'),
  display: ReportDisplay.PIVOT,
  dimensions: [{ fieldId: 'memo', relationPath: null, bucket: 'VALUE' }],
  columnDimensions: [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }],
  metrics: [
    { id: 'in', name: '入金合计', operation: 'SUM', fieldId: 'income', conditions: null },
    { id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }
  ]
})

/** 明细粒度：行 = 贷方科目 / 科目名称，列 = 日期按月，指标 = 求和贷方金额、明细行数、主记录数。 */
export const detailPivotConfig = (): ReportConfig => ({
  ...defaultReport('voucher'),
  display: ReportDisplay.PIVOT,
  grain: 'DETAIL',
  detailId: 'lines',
  dimensions: [{ fieldId: 'accountName', relationPath: 'rCredit', bucket: 'VALUE' }],
  columnDimensions: [{ fieldId: 'date', relationPath: null, bucket: 'MONTH' }],
  metrics: [
    { id: 'credit', name: '贷方金额合计', operation: 'SUM', fieldId: 'creditAmount', conditions: null },
    { id: 'rows', name: '明细行数', operation: 'COUNT', fieldId: null },
    { id: 'roots', name: '主记录数', operation: 'COUNT_ROOT', fieldId: null }
  ]
})

/** 业务方现在这张：主记录粒度，行维度却是明细「分录」上的关系路径「贷方科目 / 科目名称」，列维度放了入金、出金。 */
export const staleRootConfig = (): ReportConfig => ({
  ...defaultReport('voucher'),
  display: ReportDisplay.PIVOT,
  dimensions: [{ fieldId: 'accountName', relationPath: 'rCredit', bucket: 'VALUE' }],
  columnDimensions: [
    { fieldId: 'income', relationPath: null, bucket: 'VALUE' },
    { fieldId: 'outgo', relationPath: null, bucket: 'VALUE' }
  ]
})
