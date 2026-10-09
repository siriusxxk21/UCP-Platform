/**
 * 后端一路（laneMB）产出的接口样例原文：`laneM-multisource/backend/api-samples.md` @ 后端分支 feat/multisource-pivot-backend 515e5f7d
 * （服务层真实调用、夹具 = 契约 14.1、库 os_mb_it）。这里只抄前端用例要用的几段，JSON 逐字未改。
 * 用途：钉住「前端拿到后端真实形状的返回后显示得对」；后端改了形状，重新抄一遍即可。
 */
export const backendSamples: Record<string, unknown> = {
  legend: {
    app: '400',
    objects: {
      物件: '732',
      入住记录: '733',
      会计科目: '735',
      会计凭证: '736',
      支出: '734'
    },
    fields: {
      '入住记录.物件（引用字段）': '4430',
      '入住记录.物件（关系 ID）': '4429',
      '入住记录.入住日': '4424',
      '入住记录.退房日': '4425',
      '入住记录.当月金额': '4426',
      '入住记录.次月金额': '4427',
      '支出.物件（引用字段）': '4436',
      '支出.支出日期': '4432',
      '支出.金额': '4433',
      '物件.名称': '4421',
      '会计凭证.日期': '4440',
      '分录（明细 ID）': '4442',
      '分录.借方科目': '4446',
      '分录.贷方科目': '4448',
      '分录.借方金额': '4443',
      '分录.贷方金额': '4444'
    },
    records: {
      P1: '1',
      P2: '2',
      P3: '3',
      S1: '1',
      S2: '2',
      S3: '3',
      S4: '4',
      S5: '5',
      E1: '1',
      E2: '2',
      E3: '3',
      E4: '4',
      A1: '1',
      A2: '2',
      A3: '3',
      V1: '1',
      L1: '1',
      L2: '2',
      V2: '2',
      L3: '3',
      V3: '3',
      L4: '4',
      L5: '5'
    }
  },
  'example1.runtime-config': {
    objectId: '733',
    dimensions: [
      {
        fieldId: '4430',
        relationPath: null,
        bucket: 'VALUE'
      }
    ],
    metrics: [
      {
        id: 'cur',
        name: '当月金额',
        operation: 'SUM',
        fieldId: '4426',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        }
      },
      {
        id: 'nxt',
        name: '次月金额',
        operation: 'SUM',
        fieldId: '4427',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        },
        sourceId: 'next'
      },
      {
        id: 'exp',
        name: '支出',
        operation: 'SUM',
        fieldId: '4433',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        },
        sourceId: 'expense'
      },
      {
        id: 'inc',
        name: '收入',
        operation: 'FORMULA',
        fieldId: null,
        conditions: null,
        formula: {
          operator: 'ADD',
          left: 'cur',
          right: 'nxt'
        },
        format: null
      },
      {
        id: 'pro',
        name: '利润',
        operation: 'FORMULA',
        fieldId: null,
        conditions: null,
        formula: {
          operator: 'SUBTRACT',
          left: 'inc',
          right: 'exp'
        },
        format: null
      }
    ],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Tokyo',
    display: 'PIVOT',
    sortMetricId: 'pro',
    descending: true,
    limit: null,
    detailViewId: null,
    conditions: null,
    chart: null,
    columnDimensions: [
      {
        fieldId: '4424',
        relationPath: null,
        bucket: 'MONTH'
      }
    ],
    pivot: {
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE',
      maxColumnGroups: 24,
      columnDescending: null
    },
    detailEditable: null,
    sortBy: 'METRIC',
    sourceName: '当月',
    extraSources: [
      {
        id: 'next',
        name: '次月',
        objectId: '733',
        dimensions: [
          {
            fieldId: '4430',
            relationPath: null,
            bucket: 'VALUE'
          }
        ],
        columnDimensions: [
          {
            fieldId: '4425',
            relationPath: null,
            bucket: 'MONTH'
          }
        ]
      },
      {
        id: 'expense',
        name: '支出',
        objectId: '734',
        dimensions: [
          {
            fieldId: '4436',
            relationPath: null,
            bucket: 'VALUE'
          }
        ],
        columnDimensions: [
          {
            fieldId: '4432',
            relationPath: null,
            bucket: 'MONTH'
          }
        ]
      }
    ],
    dimensionLabels: ['物件'],
    columnDimensionLabels: ['月份']
  },
  'example1.query.response': {
    dimensionNames: ['物件'],
    metrics: [
      {
        id: 'cur',
        name: '当月金额',
        operation: 'SUM',
        fieldId: '4426',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        }
      },
      {
        id: 'nxt',
        name: '次月金额',
        operation: 'SUM',
        fieldId: '4427',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        },
        sourceId: 'next'
      },
      {
        id: 'exp',
        name: '支出',
        operation: 'SUM',
        fieldId: '4433',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        },
        sourceId: 'expense'
      },
      {
        id: 'inc',
        name: '收入',
        operation: 'FORMULA',
        fieldId: null,
        conditions: null,
        formula: {
          operator: 'ADD',
          left: 'cur',
          right: 'nxt'
        },
        format: null
      },
      {
        id: 'pro',
        name: '利润',
        operation: 'FORMULA',
        fieldId: null,
        conditions: null,
        formula: {
          operator: 'SUBTRACT',
          left: 'inc',
          right: 'exp'
        },
        format: null
      }
    ],
    groups: [],
    totals: {
      cur: '83000.00',
      nxt: '26000.00',
      exp: '16000.00',
      inc: '109000.00',
      pro: '93000.00'
    },
    totalGroups: 4,
    recordCount: 14,
    canExport: true,
    timeZone: 'Asia/Tokyo',
    pivot: {
      rowDimensionNames: ['物件'],
      columnDimensionNames: ['月份'],
      rows: [
        {
          keys: ['1'],
          labels: ['青山']
        },
        {
          keys: ['2'],
          labels: ['白川']
        },
        {
          keys: ['3'],
          labels: ['青山']
        },
        {
          keys: [null],
          labels: ['未填写']
        }
      ],
      columns: [
        {
          keys: ['2026-07'],
          labels: ['2026-07']
        },
        {
          keys: ['2026-08'],
          labels: ['2026-08']
        },
        {
          keys: ['2026-09'],
          labels: ['2026-09']
        }
      ],
      cells: [
        {
          rowKeys: ['1'],
          columnKeys: ['2026-07'],
          values: {
            cur: '20000.00',
            nxt: null,
            exp: '5000.00',
            inc: '20000.00',
            pro: '15000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['1'],
          columnKeys: ['2026-08'],
          values: {
            cur: '18000.00',
            nxt: '10000.00',
            exp: '7000.00',
            inc: '28000.00',
            pro: '21000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['1'],
          columnKeys: [],
          values: {
            cur: '38000.00',
            nxt: '10000.00',
            exp: '12000.00',
            inc: '48000.00',
            pro: '36000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['2'],
          columnKeys: ['2026-08'],
          values: {
            cur: '15000.00',
            nxt: null,
            exp: null,
            inc: '15000.00',
            pro: '15000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['2'],
          columnKeys: ['2026-09'],
          values: {
            cur: '22000.00',
            nxt: '0.00',
            exp: '3000.00',
            inc: '22000.00',
            pro: '19000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['2'],
          columnKeys: [],
          values: {
            cur: '37000.00',
            nxt: '0.00',
            exp: '3000.00',
            inc: '37000.00',
            pro: '34000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['3'],
          columnKeys: ['2026-08'],
          values: {
            cur: '8000.00',
            nxt: null,
            exp: null,
            inc: '8000.00',
            pro: '8000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['3'],
          columnKeys: ['2026-09'],
          values: {
            cur: null,
            nxt: '16000.00',
            exp: null,
            inc: '16000.00',
            pro: '16000.00'
          },
          ratios: null
        },
        {
          rowKeys: ['3'],
          columnKeys: [],
          values: {
            cur: '8000.00',
            nxt: '16000.00',
            exp: null,
            inc: '24000.00',
            pro: '24000.00'
          },
          ratios: null
        },
        {
          rowKeys: [null],
          columnKeys: ['2026-08'],
          values: {
            cur: null,
            nxt: null,
            exp: '1000.00',
            inc: '0',
            pro: '-1000.00'
          },
          ratios: null
        },
        {
          rowKeys: [null],
          columnKeys: [],
          values: {
            cur: null,
            nxt: null,
            exp: '1000.00',
            inc: '0',
            pro: '-1000.00'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: ['2026-07'],
          values: {
            cur: '20000.00',
            nxt: null,
            exp: '5000.00',
            inc: '20000.00',
            pro: '15000.00'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: ['2026-08'],
          values: {
            cur: '41000.00',
            nxt: '10000.00',
            exp: '8000.00',
            inc: '51000.00',
            pro: '43000.00'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: ['2026-09'],
          values: {
            cur: '22000.00',
            nxt: '16000.00',
            exp: '3000.00',
            inc: '38000.00',
            pro: '35000.00'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: [],
          values: {
            cur: '83000.00',
            nxt: '26000.00',
            exp: '16000.00',
            inc: '109000.00',
            pro: '93000.00'
          },
          ratios: null
        }
      ],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 4,
      totalColumnGroups: 3
    },
    sources: [
      {
        id: 'main',
        name: '当月',
        objectId: '733',
        recordCount: 5
      },
      {
        id: 'next',
        name: '次月',
        objectId: '733',
        recordCount: 5
      },
      {
        id: 'expense',
        name: '支出',
        objectId: '734',
        recordCount: 4
      }
    ]
  },
  'example1.details.request': {
    applicationId: '400',
    reportId: 'profit',
    equal: null,
    dateFrom: null,
    dateTo: null,
    context: null,
    group: ['1'],
    pageNo: 1,
    pageSize: 100,
    conditions: null,
    metricId: 'exp',
    columnGroup: ['2026-08'],
    sort: null,
    sourceId: null
  },
  'example1.details.response': {
    total: 1,
    list: [
      {
        id: '2',
        revision: 'ea7f465d35125fe57a3ba91e933f09d8',
        values: {
          '4431': 'E2',
          '4432': '2026-08-20',
          '4433': '7000.00',
          '4436': '1'
        },
        permissions: {
          actions: ['READ', 'IMPORT', 'DELETE', 'CREATE', 'EXPORT', 'UPDATE', 'START_PROCESS'],
          readFields: ['4431', '4432', '4433', '4436'],
          writeFields: ['4431', '4432', '4433', '4436'],
          readDetails: [],
          writeDetails: [],
          readRelations: [],
          writeRelations: []
        },
        displayValues: {
          '4436': '青山'
        }
      }
    ]
  },
  'example3.saved-config': {
    objectId: '736',
    dimensions: [
      {
        fieldId: '4446',
        relationPath: null,
        bucket: 'VALUE'
      }
    ],
    metrics: [
      {
        id: 'dr',
        name: '借方合计',
        operation: 'SUM',
        fieldId: '4443',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        }
      },
      {
        id: 'cr',
        name: '贷方合计',
        operation: 'SUM',
        fieldId: '4444',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        },
        sourceId: 'credit'
      },
      {
        id: 'bal',
        name: '余额',
        operation: 'FORMULA',
        fieldId: null,
        conditions: null,
        formula: {
          operator: 'SUBTRACT',
          left: 'dr',
          right: 'cr'
        },
        format: null
      }
    ],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Tokyo',
    display: 'PIVOT',
    sortMetricId: null,
    descending: false,
    limit: null,
    detailViewId: null,
    conditions: null,
    chart: null,
    columnDimensions: [
      {
        fieldId: '4440',
        relationPath: null,
        bucket: 'MONTH'
      }
    ],
    pivot: {
      subtotals: true,
      rowTotals: true,
      columnTotals: true,
      percent: 'NONE',
      maxColumnGroups: 24,
      columnDescending: null
    },
    detailEditable: null,
    sortBy: null,
    grain: 'DETAIL',
    detailId: '4442',
    sourceName: '借方',
    extraSources: [
      {
        id: 'credit',
        name: '贷方',
        objectId: '736',
        grain: 'DETAIL',
        detailId: '4442',
        dimensions: [
          {
            fieldId: '4448',
            relationPath: null,
            bucket: 'VALUE'
          }
        ],
        columnDimensions: [
          {
            fieldId: '4440',
            relationPath: null,
            bucket: 'MONTH'
          }
        ]
      }
    ],
    dimensionLabels: ['科目']
  },
  'example3.query.response': {
    dimensionNames: ['科目'],
    metrics: [
      {
        id: 'dr',
        name: '借方合计',
        operation: 'SUM',
        fieldId: '4443',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        }
      },
      {
        id: 'cr',
        name: '贷方合计',
        operation: 'SUM',
        fieldId: '4444',
        conditions: null,
        formula: null,
        format: {
          unit: null,
          decimals: null,
          percent: false,
          color: null,
          financial: true
        },
        sourceId: 'credit'
      },
      {
        id: 'bal',
        name: '余额',
        operation: 'FORMULA',
        fieldId: null,
        conditions: null,
        formula: {
          operator: 'SUBTRACT',
          left: 'dr',
          right: 'cr'
        },
        format: null
      }
    ],
    groups: [],
    totals: {
      dr: '2050.00',
      cr: '2000.00',
      bal: '50.00'
    },
    totalGroups: 4,
    recordCount: 10,
    canExport: true,
    timeZone: 'Asia/Tokyo',
    pivot: {
      rowDimensionNames: ['科目'],
      columnDimensionNames: ['日期'],
      rows: [
        {
          keys: ['1'],
          labels: ['現金']
        },
        {
          keys: ['2'],
          labels: ['売上']
        },
        {
          keys: ['3'],
          labels: ['普通預金']
        },
        {
          keys: [null],
          labels: ['未填写']
        }
      ],
      columns: [
        {
          keys: ['2026-08'],
          labels: ['2026-08']
        },
        {
          keys: ['2026-09'],
          labels: ['2026-09']
        }
      ],
      cells: [
        {
          rowKeys: ['1'],
          columnKeys: ['2026-08'],
          values: {
            dr: '1000.00',
            cr: '300.00',
            bal: '700.00'
          },
          ratios: null
        },
        {
          rowKeys: ['1'],
          columnKeys: ['2026-09'],
          values: {
            dr: '250.00',
            cr: null,
            bal: '250.00'
          },
          ratios: null
        },
        {
          rowKeys: ['1'],
          columnKeys: [],
          values: {
            dr: '1250.00',
            cr: '300.00',
            bal: '950.00'
          },
          ratios: null
        },
        {
          rowKeys: ['2'],
          columnKeys: ['2026-08'],
          values: {
            dr: '300.00',
            cr: '1500.00',
            bal: '-1200.00'
          },
          ratios: null
        },
        {
          rowKeys: ['2'],
          columnKeys: [],
          values: {
            dr: '300.00',
            cr: '1500.00',
            bal: '-1200.00'
          },
          ratios: null
        },
        {
          rowKeys: ['3'],
          columnKeys: ['2026-08'],
          values: {
            dr: '500.00',
            cr: null,
            bal: '500.00'
          },
          ratios: null
        },
        {
          rowKeys: ['3'],
          columnKeys: ['2026-09'],
          values: {
            dr: null,
            cr: '200.00',
            bal: '-200.00'
          },
          ratios: null
        },
        {
          rowKeys: ['3'],
          columnKeys: [],
          values: {
            dr: '500.00',
            cr: '200.00',
            bal: '300.00'
          },
          ratios: null
        },
        {
          rowKeys: [null],
          columnKeys: ['2026-09'],
          values: {
            dr: null,
            cr: '0',
            bal: '0'
          },
          ratios: null
        },
        {
          rowKeys: [null],
          columnKeys: [],
          values: {
            dr: null,
            cr: '0',
            bal: '0'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: ['2026-08'],
          values: {
            dr: '1800.00',
            cr: '1800.00',
            bal: '0.00'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: ['2026-09'],
          values: {
            dr: '250.00',
            cr: '200.00',
            bal: '50.00'
          },
          ratios: null
        },
        {
          rowKeys: [],
          columnKeys: [],
          values: {
            dr: '2050.00',
            cr: '2000.00',
            bal: '50.00'
          },
          ratios: null
        }
      ],
      rowsTruncated: false,
      columnsTruncated: false,
      totalRowGroups: 4,
      totalColumnGroups: 2
    },
    sources: [
      {
        id: 'main',
        name: '借方',
        objectId: '736',
        recordCount: 5,
        detailName: '分录'
      },
      {
        id: 'credit',
        name: '贷方',
        objectId: '736',
        recordCount: 5,
        detailName: '分录'
      }
    ]
  }
}
