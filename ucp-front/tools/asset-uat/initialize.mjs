import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomBytes } from 'node:crypto'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

export const BATCH = 'ASSET_UAT_20260911'
const PREFIX = 'asset_uat0911'
const option = extra => ({ classification: 'NORMAL', state: 'ACTIVE', options: [], resolver: 'NONE', ...extra })
const choices = (values, defaultValue) =>
  option({
    resolver: 'LOCAL_OPTIONS',
    defaultValue,
    options: Object.entries(values).map(([code, label]) => ({ code, label, disabled: false }))
  })
const field = (code, name, type = 'TEXT', required = false, extra = {}) => ({
  key: code,
  id: null,
  code,
  name,
  type,
  required,
  unique: false,
  sort: 0,
  length: type === 'TEXT' ? 200 : null,
  precision: ['MONEY', 'DECIMAL'].includes(type) ? 20 : null,
  scale: ['MONEY', 'DECIMAL'].includes(type) ? 2 : null,
  ...extra
})
const relation = (code, name, targetObjectId, required = true) => ({
  id: null,
  code,
  name,
  kind: 'REFERENCE',
  targetObjectId,
  fieldId: null,
  targetFieldId: null,
  required,
  onDelete: 'RESTRICT'
})
const local = expression =>
  option({
    expression,
    resultType: 'DECIMAL',
    calculation: { mode: 'LOCAL', updateMode: 'LIVE', conditions: [], logic: 'AND', aggregate: 'SUM' }
  })

/** 用正式 HTTP 入口初始化可继续使用的业务验收环境；重复运行不覆盖用户验收数据。 */
export class AssetUat extends FormAcceptance {
  constructor(base, output) {
    super(base, output)
    this.prefix = PREFIX
    this.catalog = {}
    this.seeded = {}
    this.credentials = []
  }

  async checkpoint() {
    await mkdir(this.output, { recursive: true })
    await writeFile(
      resolve(this.output, 'manifest.json'),
      JSON.stringify(
        {
          batch: BATCH,
          complete: Boolean(this.complete),
          applicationId: this.app?.application.id,
          objects: Object.fromEntries(
            Object.entries(this.catalog).map(([key, o]) => [
              key,
              {
                id: o.objectId,
                name: o.definition.objectName,
                version: o.versionNo,
                code: o.definition.objectCode,
                fields: o.ids,
                details: o.definition.details.map(d => ({ id: d.id, code: d.code }))
              }
            ])
          ),
          departments: this.departments,
          reader: this.reader,
          seeded: this.seeded,
          checkedAt: new Date().toISOString(),
          readiness: this.readiness
        },
        null,
        2
      )
    )
  }

  async version(id) {
    const o = await this.api(`/nocode/application/object-version?id=${id}`)
    o.ids = Object.fromEntries(o.definition.fields.map(f => [f.code, f.id]))
    return o
  }

  async writeInitialData() {
    const data = {
      application: this.app.application,
      reader: this.reader,
      departments: this.departments,
      objects: Object.entries(this.catalog).map(([key, o]) => ({
        key,
        id: o.objectId,
        name: o.definition.objectName,
        code: o.definition.objectCode,
        version: o.versionNo,
        fields: o.definition.fields.map(f => ({
          id: f.id,
          code: f.code,
          name: f.name,
          type: f.type,
          required: f.required,
          unique: f.unique
        })),
        relations: o.definition.relations,
        details: o.definition.details.map(d => ({ id: d.id, name: d.name, code: d.code, fields: d.fields }))
      })),
      records: {},
      readiness: this.readiness
    }
    for (const key of ['ledger', 'movement', 'repair', 'stocktake', 'count']) {
      const object = this.catalog[key]
      const page = await this.page(object)
      data.records[key] = page.list.map(r => ({
        id: r.id,
        ...Object.fromEntries(
          object.definition.fields.map(f => [
            f.code,
            {
              value: r.values[f.id],
              display: r.displayValues?.[f.id]
            }
          ])
        )
      }))
    }
    await writeFile(resolve(this.output, 'initial-data.json'), JSON.stringify(data, null, 2))
  }

  async design(key, name, fields, fieldOptions = {}, relations = [], details = [], configure) {
    const code = `${PREFIX}_${key}`
    const existing = this.existingObjects.find(o => o.objectCode === code)
    let saved
    if (existing) {
      saved = await this.api(`/nocode/design/get?id=${existing.id}`)
      assert.ok(saved.draft.description?.includes(BATCH), `编码 ${code} 已被其他对象使用`)
      if (saved.publishedVersion) {
        this.catalog[key] = await this.version(existing.id)
        await this.checkpoint()
        return this.catalog[key]
      }
    } else {
      saved = await this.api('/nocode/design/save', {
        draft: {
          id: null,
          expectedLockVersion: null,
          objectCode: code,
          objectName: name,
          description: `${BATCH} 办公固定资产业务验收。模拟数据，可按验收手册操作。`,
          tableName: `biz_${code}`,
          titleFieldKey: 'name',
          removedFieldIds: [],
          fields: fields.map((f, i) => ({ ...f, sort: i }))
        },
        settings: {},
        fieldOptions,
        relations,
        indexes: [],
        details
      })
    }
    if (configure) saved = await configure(saved)
    const plan = await this.api('/nocode/design/plan', {
      id: saved.draft.id,
      expectedLockVersion: saved.draft.lockVersion
    })
    assert.equal(plan.checks.filter(c => c.blocking).length, 0, `${name} 发布检查应通过`)
    assert.equal(
      (
        await this.api('/nocode/design/execute', {
          planId: plan.id,
          reason: `${BATCH} 初始化业务对象`
        })
      ).state,
      'SUCCEEDED'
    )
    this.catalog[key] = await this.version(saved.draft.id)
    await this.checkpoint()
    return this.catalog[key]
  }

  grant(o, extra = {}) {
    const g = super.grant(o, extra)
    g.readDetails = o.definition.details.map(d => d.id)
    g.writeDetails = [...g.readDetails]
    return g
  }

  async put(key, values, detailValues) {
    const o = this.catalog[key]
    const previous = await this.page(o, undefined, { equal: { [o.ids.name]: values.name } })
    assert.ok(previous.total <= 1, `验收名称不唯一：${values.name}`)
    let row = previous.list[0]
    if (!row) {
      const body = this.saveBody(o, values)
      if (detailValues)
        for (const [code, items] of Object.entries(detailValues)) {
          const d = o.definition.details.find(d => d.code === code)
          const ids = Object.fromEntries(d.fields.map(f => [f.code, f.id]))
          body.details[d.id] = items.map(v => ({
            id: null,
            revision: null,
            values: Object.fromEntries(Object.entries(v).map(([k, v]) => [ids[k], v]))
          }))
        }
      row = (await this.api('/nocode/runtime/save', body)).record
    }
    this.seeded[key] ||= {}
    this.seeded[key][values.name] = row.id
    await this.checkpoint()
    return row
  }

  resources() {
    const resources = []
    const columns = {
      ledger: ['asset_no', 'name', 'company_id', 'category_id', 'status', 'department', 'custodian', 'original_cost'],
      movement: [
        'name',
        'asset_id',
        'event_type',
        'event_date',
        'from_department',
        'to_department',
        'status',
        'price_snapshot'
      ],
      repair: ['name', 'asset_id', 'repair_date', 'status', 'total_cost'],
      stocktake: ['name', 'company_id', 'department', 'planned_date', 'status'],
      count: ['name', 'plan_id', 'asset_id', 'expected_qty', 'actual_qty', 'difference', 'result'],
      company: ['official_name', 'short_name'],
      category: ['zclxbm', 'zclxmc']
    }
    for (const key of ['ledger', 'movement', 'repair', 'stocktake', 'count', 'company', 'category']) {
      const o = this.catalog[key]
      const reusable = ['company', 'category'].includes(key)
      const order =
        key === 'ledger'
          ? [
              'asset_no',
              'name',
              'company_id',
              'category_id',
              'model',
              'serial_no',
              'quantity',
              'unit_price',
              'extra_cost',
              'original_cost',
              'status',
              'department',
              'custodian',
              'location',
              'received_date',
              'manual_link',
              'attachments',
              'notes'
            ]
          : o.definition.fields.map(f => f.code)
      const formFields = reusable ? columns[key] : order
      const nodes = []
      for (let i = 0; i < formFields.length; i += 2) {
        nodes.push({
          id: `${key}_row_${i}`,
          type: 'ROW',
          children: formFields.slice(i, i + 2).map(code => ({
            id: `${key}_col_${code}`,
            type: 'COLUMN',
            span: 12,
            children: [
              {
                id: `${key}_field_${code}`,
                type: 'FIELD',
                fieldId: o.ids[code],
                children: []
              }
            ]
          }))
        })
      }
      resources.push({
        id: `${key}_form`,
        kind: 'FORM',
        code: `${key}_form`,
        name: o.definition.objectName,
        config: {
          objectId: o.objectId,
          nodes,
          detailIds: o.definition.details.map(d => d.id),
          options: { layout: 'vertical', submitText: '保存', readOnly: reusable }
        }
      })
      resources.push({
        id: `${key}_view`,
        kind: 'VIEW',
        code: `${key}_view`,
        name: o.definition.objectName,
        config: {
          objectId: o.objectId,
          fieldIds: columns[key].map(k => o.ids[k]),
          equal: {},
          pageSize: 10,
          descending: false,
          formId: `${key}_form`,
          list: {
            queryFieldIds: [o.ids[key === 'ledger' ? 'asset_no' : formFields[0]]],
            advancedFieldIds: columns[key]
              .filter(k => !['FORMULA', 'SUMMARY'].includes(o.definition.fields.find(f => f.code === k).type))
              .map(k => o.ids[k]),
            columnWidths: {},
            batchDelete: false
          }
        }
      })
      resources.push({
        id: `${key}_menu`,
        kind: 'MENU',
        code: `${key}_menu`,
        name: {
          ledger: '资产台账',
          movement: '资产流转',
          repair: '维修记录',
          stocktake: '盘点任务',
          count: '盘点明细',
          company: '公司资料',
          category: '资产类型'
        }[key],
        config: { targetId: `${key}_view` }
      })
    }
    const o = this.catalog.ledger
    resources.push({
      id: 'status_report',
      kind: 'REPORT',
      code: 'status_report',
      name: '资产状态分布',
      config: {
        objectId: o.objectId,
        dimensions: [{ fieldId: o.ids.status, bucket: 'VALUE', relationPath: null }],
        metrics: [{ id: 'count', name: '资产数量', operation: 'COUNT', fieldId: null }],
        equal: {},
        filterFieldIds: [o.ids.company_id, o.ids.department],
        timeZone: 'Asia/Shanghai',
        display: 'TABLE',
        limit: 20,
        descending: false
      }
    })
    resources.push({
      id: 'report_page',
      kind: 'PAGE',
      code: 'report_page',
      name: '资产概览',
      config: {
        protocolVersion: 2,
        contextObjectId: null,
        filters: [],
        nodes: [
          {
            id: 'report_intro',
            type: 'TEXT',
            text: '办公固定资产业务验收。资产按一物一卡管理；流转单完成后，由管理员按验收手册更新台账。',
            children: []
          },
          { id: 'report_node', type: 'REPORT', resourceId: 'status_report', children: [] }
        ]
      }
    })
    resources.push({
      id: 'report_menu',
      kind: 'MENU',
      code: 'report_menu',
      name: '资产概览',
      config: { targetId: 'report_page' }
    })
    return resources
  }
}

export async function initialize(a) {
  a.existingObjects = (await a.api('/nocode/object/page?pageNo=1&pageSize=100')).list
  a.catalog.company = await a.version('2817')
  a.catalog.category = await a.version('3289')
  const oldApps = (await a.api('/nocode/application/page?pageNo=1&pageSize=100')).list
  const existingApp = oldApps.find(x => x.code === `${PREFIX}_app`)
  let manifest
  try {
    manifest = JSON.parse(await readFile(resolve(a.output, 'manifest.json'), 'utf8'))
  } catch (e) {
    if (e.code !== 'ENOENT') throw e
  }
  if (manifest?.complete) {
    assert.equal(manifest.batch, BATCH)
    assert.equal(manifest.applicationId, existingApp?.id)
    a.app = await a.api(`/nocode/application/get?id=${existingApp.id}`)
    for (const [key, o] of Object.entries(manifest.objects)) a.catalog[key] = await a.version(o.id)
    a.departments = manifest.departments
    a.reader = manifest.reader
    a.seeded = manifest.seeded
    a.complete = true
    return { reused: true, applicationId: existingApp.id, manifest }
  }

  const organizations = await a.api('/system/organization/tree')
  const activeOrg = organizations.find(o => String(o.id) === '2064658626164346881')
  assert.ok(activeOrg, '需要已有有效组织 LY')
  const departments = await a.api('/system/dept/list')
  a.departments = {}
  for (const [key, name] of [
    ['admin', '资产验收·行政部'],
    ['rd', '资产验收·研发部']
  ]) {
    const found = departments.find(d => d.name === name && String(d.orgId) === String(activeOrg.id))
    a.departments[key] =
      found?.id ||
      String(
        await a.api('/system/dept', {
          orgId: activeOrg.id,
          name,
          parentId: '0',
          sort: 900,
          status: 0
        })
      )
  }
  const users = await a.api('/system/user/page?pageNo=1&pageSize=100&username=assetuatreader')
  const reader = users.list.find(u => u.username === 'assetuatreader')
  if (reader) {
    assert.ok(reader.remark?.includes(BATCH), '验收账号名称已被占用')
    a.reader = { id: String(reader.id), username: reader.username }
  } else {
    const password = `Au9${randomBytes(6).toString('hex')}`
    const id = String(
      await a.api('/system/user/create', {
        username: 'assetuatreader',
        nickname: '资产验收·研发查看员',
        password,
        deptId: a.departments.rd,
        remark: `${BATCH} 仅查看本部门资产，不授予系统管理角色`,
        sex: 0,
        postIds: []
      })
    )
    a.reader = { id, username: 'assetuatreader' }
    a.credentials.push({ username: a.reader.username, initialPassword: password, forceChangeOnFirstLogin: true })
  }
  await a.checkpoint()
  const ledger = await a.design(
    'ledger',
    '验收·资产台账',
    [
      field('name', '资产名称', 'TEXT', true),
      field('asset_no', '资产编号', 'TEXT', true, { unique: true, length: 40 }),
      field('model', '品牌型号'),
      field('serial_no', '设备序列号', 'TEXT', false, { unique: true }),
      field('quantity', '数量', 'INTEGER', true),
      field('unit_price', '采购单价（元）', 'MONEY', true),
      field('extra_cost', '附加费用（元）', 'MONEY'),
      field('original_cost', '购置总额（元）', 'FORMULA'),
      field('status', '资产状态', 'SELECT', true),
      field('department', '保管部门', 'DEPARTMENT', true),
      field('custodian', '保管人', 'USER'),
      field('location', '存放位置', 'TEXT', true),
      field('received_date', '入库日期', 'DATE', true),
      field('manual_link', '说明书链接', 'URL'),
      field('attachments', '资产附件', 'ATTACHMENT'),
      field('notes', '备注', 'TEXTAREA')
    ],
    {
      quantity: option({ minimum: '1', maximum: '1', defaultValue: '1', description: '一物一卡，每件资产单独编号。' }),
      unit_price: option({ minimum: '0' }),
      extra_cost: option({ minimum: '0', defaultValue: '0' }),
      original_cost: local('round(quantity * unit_price + coalesce(extra_cost, 0), 2)'),
      status: choices({ IN_STOCK: '闲置在库', IN_USE: '使用中', REPAIR: '维修中', SCRAPPED: '已报废' }, 'IN_STOCK'),
      notes: option({ description: '流转记录完成后人工核对并更新状态、部门、保管人和位置。' })
    },
    [
      relation('company', '所属公司', a.catalog.company.objectId),
      relation('category', '资产类型', a.catalog.category.objectId)
    ]
  )
  await a.design(
    'movement',
    '验收·资产流转',
    [
      field('name', '流转单号', 'TEXT', true, { unique: true }),
      field('event_type', '业务类型', 'SELECT', true),
      field('event_date', '业务日期', 'DATE', true),
      field('from_department', '原保管部门', 'DEPARTMENT'),
      field('to_department', '接收部门', 'DEPARTMENT'),
      field('from_user', '原保管人', 'USER'),
      field('to_user', '接收人', 'USER'),
      field('to_location', '接收位置'),
      field('status', '办理状态', 'SELECT', true),
      field('handler', '经办人', 'USER', true),
      field('reason', '业务原因', 'TEXTAREA', true),
      field('notes', '备注', 'TEXTAREA')
    ],
    {
      event_type: choices({ RECEIVE: '入库', ASSIGN: '领用', TRANSFER: '调拨', RETURN: '归还', SCRAP: '报废' }),
      status: choices({ DRAFT: '待办理', DONE: '已办理', CANCELLED: '已取消' }, 'DRAFT'),
      notes: option({ description: '本单记录办理过程；保存本单不会自动更新资产台账，也不会自动发起审批。' })
    },
    [relation('asset', '关联资产', ledger.objectId)],
    [],
    async s => {
      if (s.draft.fields.some(f => f.code === 'price_snapshot')) return s
      const r = s.relations.find(r => r.code === 'asset')
      return a.api('/nocode/design/save', {
        draft: {
          id: s.draft.id,
          objectCode: s.draft.objectCode,
          objectName: s.draft.objectName,
          description: s.draft.description,
          tableName: s.draft.tableName,
          expectedLockVersion: s.draft.lockVersion,
          titleFieldKey: s.draft.titleFieldId,
          fields: [
            ...s.draft.fields.map(f => ({ ...f, key: f.id })),
            field('price_snapshot', '保存时采购单价（元）', 'FORMULA')
          ],
          removedFieldIds: []
        },
        settings: s.settings,
        relations: s.relations,
        indexes: s.indexes,
        details: s.details,
        fieldOptions: {
          ...s.fieldOptions,
          price_snapshot: option({
            resultType: 'DECIMAL',
            description: '每次保存流转单时重新取值；仅修改资产后刷新本单，保留上次保存值。',
            calculation: {
              mode: 'RELATION',
              updateMode: 'ON_SAVE',
              relationId: r.id,
              targetObjectId: ledger.objectId,
              targetField: 'unit_price',
              aggregate: 'SINGLE',
              logic: 'AND',
              conditions: [],
              excludeCurrent: false
            }
          })
        }
      })
    }
  )
  await a.design(
    'repair',
    '验收·维修记录',
    [
      field('name', '维修单号', 'TEXT', true, { unique: true }),
      field('repair_date', '报修日期', 'DATE', true),
      field('issue', '故障描述', 'TEXTAREA', true),
      field('vendor', '维修单位'),
      field('handler', '经办人', 'USER'),
      field('status', '维修状态', 'SELECT', true),
      field('total_cost', '维修费用合计（元）', 'SUMMARY'),
      field('receipt_link', '维修凭证链接', 'URL'),
      field('notes', '备注', 'TEXTAREA')
    ],
    {
      status: choices({ PENDING: '待维修', REPAIRING: '维修中', DONE: '已完成' }, 'PENDING'),
      total_cost: option({ expression: 'sum(costs.amount)', resultType: 'DECIMAL' })
    },
    [relation('asset', '关联资产', ledger.objectId)],
    [
      {
        id: null,
        code: 'costs',
        name: '维修费用明细',
        state: 'ACTIVE',
        tableName: `biz_${PREFIX}_repair_costs`,
        fields: [field('name', '费用项目', 'TEXT', true), field('amount', '金额（元）', 'MONEY', true, { sort: 1 })],
        fieldOptions: { amount: option({ minimum: '0' }) },
        indexes: []
      }
    ]
  )
  const plan = await a.design(
    'stocktake',
    '验收·盘点任务',
    [
      field('name', '盘点任务号', 'TEXT', true, { unique: true }),
      field('department', '盘点部门', 'DEPARTMENT', true),
      field('planned_date', '计划日期', 'DATE', true),
      field('owner', '盘点负责人', 'USER', true),
      field('status', '任务状态', 'SELECT', true),
      field('notes', '盘点说明', 'TEXTAREA')
    ],
    { status: choices({ PLANNED: '待盘点', COUNTING: '盘点中', DONE: '已完成' }, 'PLANNED') },
    [relation('company', '所属公司', a.catalog.company.objectId)]
  )
  await a.design(
    'count',
    '验收·盘点明细',
    [
      field('name', '盘点明细号', 'TEXT', true, { unique: true }),
      field('expected_qty', '账面数量', 'INTEGER', true),
      field('actual_qty', '实盘数量', 'INTEGER'),
      field('difference', '数量差异', 'FORMULA'),
      field('result', '盘点结论', 'SELECT', true),
      field('actual_location', '实盘位置'),
      field('counted_by', '盘点人', 'USER'),
      field('counted_date', '盘点日期', 'DATE'),
      field('notes', '差异说明', 'TEXTAREA')
    ],
    {
      expected_qty: option({ minimum: '1', maximum: '1', defaultValue: '1' }),
      actual_qty: option({ minimum: '0', maximum: '1' }),
      difference: local('actual_qty - expected_qty'),
      result: choices({ PENDING: '待盘点', MATCH: '账实相符', MISSING: '盘亏', MOVED: '位置不符' }, 'PENDING'),
      notes: option({ description: '数量差异自动计算；盘点结论由盘点人结合数量、位置和现场情况确认。' })
    },
    [relation('plan', '所属盘点任务', plan.objectId), relation('asset', '盘点资产', ledger.objectId)]
  )

  const definition = {
    objects: Object.values(a.catalog).map(({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum })),
    resources: a.resources()
  }
  if (existingApp) {
    a.app = await a.api(`/nocode/application/get?id=${existingApp.id}`)
    assert.ok(a.app.application.description?.includes(BATCH), '应用编码已被其他应用占用')
  } else
    a.app = await a.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${PREFIX}_app`,
      name: '公司资产管理·业务验收',
      description: `${BATCH} 办公固定资产场景，验收结果由用户记录。`,
      icon: 'AppstoreOutlined',
      definition
    })
  for (const [key, o] of Object.entries(a.catalog)) {
    const basic = ['company', 'category'].includes(key)
    const read = key === 'company' ? [o.ids.official_name, o.ids.short_name] : o.definition.fields.map(f => f.id)
    await a.share(
      o,
      a.grant(o, {
        ...(basic ? { actions: ['READ'], readFields: read, writeFields: [] } : {}),
        computeFields: key === 'ledger' ? [o.ids.unit_price] : []
      })
    )
  }
  if (!a.app.application.publishedVersion)
    a.app = await a.api('/nocode/application/publish', {
      id: a.app.application.id,
      expectedRevision: a.app.application.revision,
      reason: `${BATCH} 业务验收入口`
    })
  const visible = [
    'name',
    'asset_no',
    'model',
    'serial_no',
    'quantity',
    'status',
    'department',
    'custodian',
    'location',
    'received_date',
    'manual_link',
    'company_id',
    'category_id'
  ]
  await a.authorize([
    a.member(a.reader.id, [
      a.grant(ledger, {
        actions: ['READ'],
        readFields: visible.map(k => ledger.ids[k]),
        writeFields: [],
        scope: 'ALL',
        computeFields: [],
        actionScopes: { READ: a.scope(a.condition(ledger, 'department', 'eq', null, 'CURRENT_DEPARTMENT')) }
      }),
      a.grant(a.catalog.company, {
        actions: ['READ'],
        readFields: [a.catalog.company.ids.official_name, a.catalog.company.ids.short_name],
        writeFields: []
      }),
      a.grant(a.catalog.category, { actions: ['READ'], writeFields: [] })
    ])
  ])
  await a.checkpoint()

  const samples = [
    ['ZC-001', '验收笔记本01', '1', '2', '6800', '200', 'IN_STOCK', 'admin', null, 'A库房'],
    ['ZC-002', '验收笔记本02', '1', '2', '6500', '0', 'IN_USE', 'rd', a.reader.id, '研发区A01'],
    ['ZC-003', '验收笔记本03', '1', '2', '7000', '0', 'IN_USE', 'rd', a.reader.id, '研发区A02'],
    ['ZC-004', '验收服务器01', '1', '4', '18000', '500', 'IN_STOCK', 'admin', null, 'A库房'],
    ['ZC-005', '验收笔记本05', '1', '2', '5500', '0', 'REPAIR', 'rd', a.reader.id, '维修区'],
    ['ZC-006', '验收笔记本06', '2', '2', '6000', '0', 'IN_USE', 'admin', '1', '行政区B01'],
    ['ZC-007', '验收服务器02', '2', '4', '16000', '0', 'SCRAPPED', 'admin', null, '报废暂存区'],
    ['ZC-008', '验收笔记本08', '2', '2', '7200', '300', 'IN_STOCK', 'admin', null, 'B库房']
  ]
  const rows = {}
  for (const [
    asset_no,
    name,
    company_id,
    category_id,
    unit_price,
    extra_cost,
    status,
    dept,
    custodian,
    location
  ] of samples)
    rows[asset_no] = await a.put('ledger', {
      asset_no,
      name,
      company_id,
      category_id,
      unit_price,
      extra_cost,
      quantity: 1,
      status,
      department: a.departments[dept],
      custodian,
      location,
      received_date: '2026-09-01',
      model: category_id === '4' ? '验收用2U服务器' : '验收用14英寸笔记本',
      serial_no: `UAT-SN-${asset_no}`,
      manual_link: { link: 'https://example.com', text: '模拟说明书' },
      notes: `${BATCH} 模拟资产。`
    })
  const flowSamples = [
    ['LC-001', 'ZC-001', 'RECEIVE', null, 'admin', 'A库房', '采购验收入库'],
    ['LC-002', 'ZC-002', 'ASSIGN', 'admin', 'rd', '研发区A01', '研发人员领用'],
    ['LC-003', 'ZC-003', 'TRANSFER', 'admin', 'rd', '研发区A02', '部门设备调拨'],
    ['LC-004', 'ZC-004', 'RETURN', 'rd', 'admin', 'A库房', '项目结束归还'],
    ['LC-005', 'ZC-007', 'SCRAP', 'admin', 'admin', '报废暂存区', '设备损坏，模拟已完成报废手续']
  ]
  for (const [name, assetNo, event_type, from, to, to_location, reason] of flowSamples)
    await a.put('movement', {
      name,
      asset_id: rows[assetNo].id,
      event_type,
      event_date: '2026-09-05',
      from_department: from ? a.departments[from] : null,
      to_department: a.departments[to],
      to_user: to === 'rd' ? a.reader.id : null,
      status: 'DONE',
      handler: '1',
      to_location,
      reason,
      notes: `${BATCH} 已办理示例；对应台账初始状态已由初始化过程配套填入。`
    })
  await a.put(
    'repair',
    {
      name: 'WX-001',
      asset_id: rows['ZC-005'].id,
      repair_date: '2026-09-08',
      issue: '键盘故障',
      vendor: '模拟维修服务商',
      handler: '1',
      status: 'REPAIRING',
      notes: BATCH
    },
    {
      costs: [
        { name: '键盘配件', amount: '280' },
        { name: '人工维修', amount: '120' }
      ]
    }
  )
  await a.put(
    'repair',
    {
      name: 'WX-002',
      asset_id: rows['ZC-006'].id,
      repair_date: '2026-09-07',
      issue: '风扇清洁',
      vendor: '模拟维修服务商',
      handler: '1',
      status: 'DONE',
      notes: BATCH
    },
    { costs: [{ name: '清洁维护', amount: '80' }] }
  )
  const stock = await a.put('stocktake', {
    name: 'PD-202609-01',
    company_id: '1',
    department: a.departments.rd,
    planned_date: '2026-09-11',
    owner: '1',
    status: 'COUNTING',
    notes: `${BATCH} 盘点研发部的3件资产；实盘结果由用户填写。`
  })
  for (const [i, code] of ['ZC-002', 'ZC-003', 'ZC-005'].entries())
    await a.put('count', {
      name: `PDMX-00${i + 1}`,
      plan_id: stock.id,
      asset_id: rows[code].id,
      expected_qty: 1,
      actual_qty: null,
      result: 'PENDING',
      actual_location: null,
      counted_by: null,
      counted_date: null,
      notes: BATCH
    })
  a.complete = true
  a.readiness = { counts: {}, formula: {} }
  for (const [key, o] of Object.entries(a.catalog)) a.readiness.counts[key] = (await a.page(o)).total
  const first = (await a.get(ledger, rows['ZC-001'])).record
  assert.equal(Number(first.values[ledger.ids.original_cost]), 7000)
  a.readiness.formula.ledger001 = first.values[ledger.ids.original_cost]
  const repairs = await a.page(a.catalog.repair)
  a.readiness.formula.repairs = repairs.list.map(r => ({
    name: r.values[a.catalog.repair.ids.name],
    total: r.values[a.catalog.repair.ids.total_cost]
  }))
  assert.equal(JSON.stringify(a.readiness.formula.repairs.map(r => Number(r.total))), '[400,80]')
  await a.checkpoint()
  await a.writeInitialData()
  return {
    reused: false,
    applicationId: a.app.application.id,
    counts: a.readiness.counts,
    reader: a.reader,
    credentials: a.credentials
  }
}

// CLI 正常登录；凭据只输出到当前终端，不写入清单或仓库。
if (
  typeof process !== 'undefined' &&
  process.argv[1] &&
  resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))
) {
  const a = new AssetUat('http://127.0.0.1:8080/api', resolve('tests/test-results/asset-uat-20260911'))
  await a.login()
  try {
    console.log(JSON.stringify(await initialize(a), null, 2))
  } finally {
    a.tokens = {}
    a.credentials = []
  }
}
