/** C4：复用已登记来源夹具，经应用正式写入链路创建独立经营案例，再校准六类看板及真实交互。 */
import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20000 })
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-demo/c4'),
  manifestFile = resolve(output, 'current.json')
const original = JSON.parse(await readFile('.work/report-demo/current.json', 'utf8'))
assert.equal(String(original.dashboardId), '5')
assert.equal(String(original.datasetId), '981')
assert.deepEqual(original.objectIds.map(String), ['5915', '5914', '5913'])
assert.match(original.prefix, /^test_b1_[a-f0-9]+$/)
await mkdir(output, { recursive: true })
let manifest
try {
  manifest = JSON.parse(await readFile(manifestFile, 'utf8'))
} catch (error) {
  if (error.code !== 'ENOENT') throw error
  manifest = {
    schemaVersion: 1,
    prefix: 'report_c4_' + randomBytes(4).toString('hex'),
    status: 'PREPARING',
    sourceDatasetId: '981',
    sourceDashboardId: '5',
    objectIds: original.objectIds.map(String),
    rows: {},
    requestKeys: {},
    createdAt: new Date().toISOString()
  }
}
assert.match(manifest.prefix, /^report_c4_[a-f0-9]{8}$/)
assert.deepEqual(manifest.objectIds, ['5915', '5914', '5913'])
assert.equal(manifest.sourceDatasetId, '981')
assert.equal(manifest.sourceDashboardId, '5')
const prefix = manifest.prefix,
  tag = '【经营案例 ' + prefix.slice(-8) + '】'
const companies = [
  { key: 'a', name: tag + '星河科技' },
  { key: 'b', name: tag + '远山商贸' }
]
const accounts = [
  { key: 'a_ops', company: 'a', name: tag + '星河运营账户', amounts: [12000, 15000, 13500] },
  { key: 'a_sales', company: 'a', name: tag + '星河销售账户', amounts: [8000, 10000, 12000] },
  { key: 'b_ops', company: 'b', name: tag + '远山运营账户', amounts: [6000, 7200, 8400] },
  { key: 'b_sales', company: 'b', name: tag + '远山销售账户', amounts: [4500, 5500, 6500] }
]
const entries = accounts.flatMap(account =>
  account.amounts.map((amount, index) => ({
    key: account.key + '_' + (index + 1),
    account: account.key,
    name: tag + account.key + '2026年' + (index + 1) + '月资金流水',
    amount: amount.toFixed(2),
    business_day: '2026-0' + (index + 1) + '-15',
    occurred: '2026-0' + (index + 1) + '-15 10:00:00',
    checked: index < 2,
    state: index < 2 ? 'CLOSED' : 'OPEN'
  }))
)
const checks = [],
  errors = []
let token, browser, page
async function checkpoint() {
  await writeFile(manifestFile, JSON.stringify({ ...manifest, updatedAt: new Date().toISOString() }, null, 2))
}
async function request(path, body) {
  return fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}
async function api(path, body) {
  const result = await (await request(path, body)).json()
  assert.equal(result.code, 0, path + ': ' + result.msg)
  return result.data
}
const condition = (fieldId, operator, value) => ({ fieldId, operator, value })
const scope = conditions => ({ logic: 'AND', conditions, groups: [] })
const reference = object => ({ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum })
function grant(object, names, actions) {
  const allowed = scope([condition(object.ids.name, 'in', names)])
  return {
    objectId: object.objectId,
    actions,
    scope: 'ALL',
    readFields: object.definition.fields.map(field => field.id),
    writeFields: actions.includes('CREATE')
      ? object.definition.fields
          .filter(field => !['FORMULA', 'SUMMARY', 'AUTO_NUMBER'].includes(field.type))
          .map(field => field.id)
      : [],
    readDetails: [],
    writeDetails: [],
    readRelations: [],
    writeRelations: [],
    actionScopes: Object.fromEntries(actions.map(action => [action, allowed]))
  }
}
function appResources(objects) {
  return objects.flatMap((object, index) => {
    const code = ['entries', 'accounts', 'companies'][index],
      formId = code + '_form',
      viewId = code + '_view'
    return [
      {
        id: formId,
        code: formId,
        name: ['资金流水录入', '账户档案', '公司档案'][index],
        kind: 'FORM',
        config: {
          objectId: object.objectId,
          detailIds: [],
          nodes: object.definition.fields.map(field => ({
            id: 'field_' + field.id,
            type: 'FIELD',
            fieldId: field.id,
            children: []
          })),
          options: { layout: 'vertical', submitText: '保存' }
        }
      },
      {
        id: viewId,
        code: viewId,
        name: ['资金流水', '资金账户', '公司资料'][index],
        kind: 'VIEW',
        config: {
          objectId: object.objectId,
          fieldIds: object.definition.fields.map(field => field.id),
          equal: {},
          pageSize: 20,
          descending: false,
          formId
        }
      },
      {
        id: code + '_menu',
        code: code + '_menu',
        name: ['资金流水', '资金账户', '公司资料'][index],
        kind: 'MENU',
        config: { targetId: viewId }
      }
    ]
  })
}
async function findUnique(path, name, field = 'name') {
  const page = await api(path + '?pageNo=1&pageSize=100&search=' + encodeURIComponent(name))
  const matches = page.list.filter(item => (field === 'code' ? item.code : item.draft?.name || item.name) === name)
  assert.ok(matches.length <= 1, '案例资源重名，不能自动恢复：' + name)
  return matches[0]
}
async function setupApplication(objects, names, actor) {
  if (!manifest.applicationId) {
    const code = prefix + '_app'
    const found = await findUnique('/nocode/application/page', code, 'code')
    const app = found
      ? await api('/nocode/application/get?id=' + found.id)
      : await api('/nocode/application/save', {
          id: null,
          expectedRevision: null,
          code,
          name: '经营案例 · 资金录入',
          description: prefix + '；仅操作已登记来源中的本案例记录。',
          definition: { objects: objects.map(reference), resources: appResources(objects) }
        })
    assert.equal(app.application.code, code)
    manifest.applicationId = app.application.id
    await checkpoint()
  }
  let app = await api('/nocode/application/get?id=' + manifest.applicationId)
  assert.equal(app.application.code, prefix + '_app')
  const permissions = objects.map((object, index) => grant(object, names[index], ['READ', 'CREATE']))
  for (const permission of permissions) {
    const current = await api('/nocode/object-sharing/list?objectId=' + permission.objectId)
    await api('/nocode/object-sharing/save', {
      objectId: permission.objectId,
      applicationId: manifest.applicationId,
      expectedRevision: current.find(item => item.applicationId === manifest.applicationId)?.revision || 0,
      permission,
      reason: prefix + ' 精确案例录入范围'
    })
  }
  const policy = await api('/nocode/application/authorization?id=' + manifest.applicationId)
  await api('/nocode/application/authorization', {
    applicationId: manifest.applicationId,
    expectedRevision: policy.revision,
    members: [{ principalKind: 'USER', principalId: String(actor), objects: permissions }]
  })
  if (!app.application.publishedVersion)
    app = await api('/nocode/application/publish', {
      id: manifest.applicationId,
      expectedRevision: app.application.revision,
      reason: prefix + ' 正式应用录入链路'
    })
  manifest.applicationVersion = app.application.publishedVersion
  await checkpoint()
}
async function put(object, key, values) {
  const identity = { applicationId: manifest.applicationId, objectId: object.objectId }
  const expected = Object.fromEntries(Object.entries(values).map(([code, value]) => [object.ids[code], value]))
  assert.ok(
    Object.keys(expected).every(id => id && id !== 'undefined'),
    '记录字段必须来自固定对象定义'
  )
  if (!manifest.rows[key]) {
    const existing = await api('/nocode/runtime/page', {
      ...identity,
      pageNo: 1,
      pageSize: 20,
      equal: { [object.ids.name]: values.name },
      descending: false
    })
    assert.ok(existing.total <= 1, '本案例记录名称不唯一：' + values.name)
    if (existing.list[0]) manifest.rows[key] = existing.list[0].id
    else {
      manifest.requestKeys[key] ||= randomUUID()
      await checkpoint()
      const saved = await api('/nocode/runtime/save', {
        ...identity,
        id: null,
        expectedRevision: null,
        requestKey: manifest.requestKeys[key],
        values: expected,
        details: {},
        relations: {}
      })
      manifest.rows[key] = saved.record.id
    }
    await checkpoint()
  }
  const aggregate = await api(
    '/nocode/runtime/get?applicationId=' +
      identity.applicationId +
      '&objectId=' +
      identity.objectId +
      '&id=' +
      manifest.rows[key]
  )
  const row = aggregate.record
  assert.equal(row.id, manifest.rows[key])
  for (const [id, value] of Object.entries(expected)) {
    const type = object.definition.fields.find(field => field.id === id).type
    if (type === 'DECIMAL') assert.equal(Number(row.values[id]), Number(value))
    else if (type === 'DATETIME')
      assert.equal(Date.parse(String(row.values[id]).replace(' ', 'T')), Date.parse(String(value).replace(' ', 'T')))
    else assert.equal(String(row.values[id]), String(value), '案例已有记录被修改，不能自动覆盖：' + values.name)
  }
  return row.id
}
async function setupDataset(source, objects, names, actor) {
  if (!manifest.datasetId) {
    const copyName = '经营分析 · 资金流水数据集 · ' + prefix
    const found = await findUnique('/nocode/report/dataset/page', copyName)
    const dataset =
      found ||
      (await api('/nocode/report/dataset/copy', {
        id: '981',
        expectedRevision: source.revision,
        name: copyName,
        reason: prefix + ' 独立经营案例'
      }))
    manifest.datasetId = dataset.id
    await checkpoint()
  }
  let dataset = await api('/nocode/report/dataset/get?id=' + manifest.datasetId)
  assert.equal(dataset.draft.source.root.objectId, '5915')
  assert.ok(dataset.draft.name.endsWith(prefix) || dataset.draft.description.includes(prefix), '新案例数据集身份不一致')
  const content = structuredClone(dataset.draft),
    account = objects[1]
  const relation = content.source.relations.find(
    item => item.target.objectId === account.objectId && !item.parentPath.length
  )
  assert.ok(relation, '原数据集缺少流水到固定账户版本的关系')
  manifest.accountFieldId ||= 'field_' + randomUUID().replaceAll('-', '')
  if (!content.source.fields.some(field => field.id === manifest.accountFieldId))
    content.source.fields.push({
      id: manifest.accountFieldId,
      path: [relation.id],
      sourceFieldId: account.definition.titleFieldId,
      name: '账户',
      role: 'DIMENSION'
    })
  const field = name => {
    const value = content.source.fields.find(field => field.name === name)
    assert.ok(value, '案例数据集缺少字段：' + name)
    return value.id
  }
  manifest.fields = Object.fromEntries(
    ['公司', '账户', '金额', '业务日期', '已核销', '状态'].map(name => [name, field(name)])
  )
  const sum = content.analysis.metrics.find(metric => metric.operation === 'SUM' && metric.fieldId === field('金额'))
  assert.ok(sum, '复制数据集需保留金额求和指标')
  manifest.metricIds ||= {
    sum: sum.id,
    checked: 'metric_' + randomUUID().replaceAll('-', ''),
    pending: 'metric_' + randomUUID().replaceAll('-', ''),
    count: 'metric_' + randomUUID().replaceAll('-', '')
  }
  content.name = '经营分析 · 资金流水数据集'
  content.description =
    prefix +
    '；2026年一季度2家公司、4个账户、12笔真实案例流水。金额108600，已核销68200，待核销40400。只授权本案例精确流水名称。'
  content.analysis = {
    ...content.analysis,
    timeZone: 'Asia/Shanghai',
    fixedConditions: null,
    metrics: [
      { ...sum, name: '资金总额' },
      {
        id: manifest.metricIds.checked,
        name: '已核销金额',
        operation: 'SUM',
        fieldId: field('金额'),
        conditions: scope([condition(field('已核销'), 'eq', true)])
      },
      {
        id: manifest.metricIds.pending,
        name: '待核销金额',
        operation: 'SUM',
        fieldId: field('金额'),
        conditions: scope([condition(field('已核销'), 'eq', false)])
      },
      { id: manifest.metricIds.count, name: '流水笔数', operation: 'COUNT', fieldId: null }
    ]
  }
  dataset = await api('/nocode/report/dataset/save', { ...content, id: dataset.id, expectedRevision: dataset.revision })
  const ceilings = await api('/nocode/report/dataset/ceilings?id=' + dataset.id)
  const permissions = objects.map((object, index) => grant(object, names[index], ['READ', 'EXPORT']))
  for (const permission of permissions)
    await api('/nocode/report/dataset/ceiling', {
      datasetId: dataset.id,
      objectId: permission.objectId,
      expectedRevision: ceilings.find(item => item.objectId === permission.objectId)?.revision || 0,
      permission,
      reason: prefix + ' 独立案例读取/导出上限'
    })
  const policy = await api('/nocode/report/dataset/data-policy?id=' + dataset.id)
  await api('/nocode/report/dataset/data-policy', {
    datasetId: dataset.id,
    expectedRevision: policy.revision,
    members: [{ principalKind: 'USER', principalId: String(actor), objects: permissions }],
    reason: prefix + ' 仅本案例12笔流水'
  })
  const published = await api('/nocode/report/dataset/publish', {
    id: dataset.id,
    expectedRevision: dataset.revision,
    requestId: randomUUID(),
    reason: prefix + ' 经营案例固定版本'
  })
  manifest.datasetVersion = published.versionNo
  manifest.datasetChecksum = published.checksum
  await checkpoint()
}
async function setupDashboard(originalRelease) {
  const content = structuredClone(originalRelease.content),
    field = name => manifest.fields[name]
  const pinned = { id: manifest.datasetId, versionNo: manifest.datasetVersion, checksum: manifest.datasetChecksum }
  assert.deepEqual(content.charts.map(chart => chart.display).sort(), [
    'BAR',
    'LINE',
    'METRIC',
    'PIE',
    'PIVOT',
    'TABLE'
  ])
  const titles = {
    METRIC: '季度资金概览',
    BAR: '公司资金对比',
    LINE: '月度资金趋势',
    PIE: '公司资金占比',
    TABLE: '公司账户月份钻取',
    PIVOT: '公司账户月度透视'
  }
  for (const chart of content.charts) {
    chart.title = titles[chart.display]
    chart.dataset = pinned
    chart.metricIds = chart.display === 'METRIC' ? Object.values(manifest.metricIds) : [manifest.metricIds.sum]
    chart.drillDimensions = []
    chart.links = []
    chart.columnDimensions = []
    chart.pivot = null
    chart.dimensions =
      chart.display === 'METRIC'
        ? []
        : [
            {
              fieldId: field(chart.display === 'LINE' ? '业务日期' : '公司'),
              bucket: chart.display === 'LINE' ? 'MONTH' : 'VALUE'
            }
          ]
    if (chart.display === 'TABLE')
      chart.drillDimensions = [
        { fieldId: field('账户'), bucket: 'VALUE' },
        { fieldId: field('业务日期'), bucket: 'MONTH' }
      ]
    if (chart.display === 'PIVOT') {
      chart.dimensions.push({ fieldId: field('账户'), bucket: 'VALUE' })
      chart.columnDimensions = [{ fieldId: field('业务日期'), bucket: 'MONTH' }]
      chart.pivot = { subtotals: true, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 }
    }
  }
  const table = content.charts.find(chart => chart.display === 'TABLE')
  table.links = content.charts
    .filter(chart => chart.id !== table.id)
    .map(chart => ({ targetChartId: chart.id, sourceFieldId: field('公司'), targetFieldId: field('公司') }))
  content.filters = [
    ['company', '公司筛选', 'SELECT', '公司'],
    ['checked', '核销状态', 'SELECT', '已核销'],
    ['date', '日期范围', 'DATE_RANGE', '业务日期']
  ].map(([id, name, kind, source]) => ({
    id: 'filter_c4_' + id,
    name,
    kind,
    mappings: content.charts.map(chart => ({ chartId: chart.id, fieldId: field(source) }))
  }))
  content.name = '经营分析 · 公司资金看板'
  content.description =
    prefix +
    '；2026年一季度2家公司、4账户、12笔流水，总额108600。公司→账户→月份真实下钻；核销状态“是”为已核销68200，“否”为待核销40400。'
  if (!manifest.dashboardId) {
    const recoveryName = content.name + ' · ' + prefix
    const existing = await findUnique('/nocode/report/dashboard/page', recoveryName)
    const board =
      existing ||
      (await api('/nocode/report/dashboard/save', {
        id: null,
        expectedRevision: 0,
        content: { ...content, name: recoveryName }
      }))
    manifest.dashboardId = board.id
    await checkpoint()
  }
  let board = await api('/nocode/report/dashboard/get?id=' + manifest.dashboardId)
  assert.ok(board.draft.description.includes(prefix), '案例看板身份不一致')
  board = await api('/nocode/report/dashboard/save', { id: board.id, expectedRevision: board.revision, content })
  const release = await api('/nocode/report/dashboard/publish', {
    id: board.id,
    expectedRevision: board.revision,
    requestId: randomUUID()
  })
  manifest.dashboardVersion = release.versionNo
  manifest.dashboardChecksum = release.checksum
  await checkpoint()
}
async function calibrate(release) {
  const identity = { id: release.id, preview: false, versionNo: release.versionNo, checksum: release.checksum }
  const chart = display => release.content.charts.find(chart => chart.display === display)
  const current = await api('/nocode/report/dashboard/query', { ...identity, chartId: chart('METRIC').id })
  assert.equal(current.recordCount, 12)
  assert.equal(Number(current.totals[manifest.metricIds.sum]), 108600)
  assert.equal(Number(current.totals[manifest.metricIds.checked]), 68200)
  assert.equal(Number(current.totals[manifest.metricIds.pending]), 40400)
  assert.equal(Number(current.totals[manifest.metricIds.count]), 12)
  const monthly = await api('/nocode/report/dashboard/query', { ...identity, chartId: chart('LINE').id })
  assert.deepEqual(
    Object.fromEntries(monthly.groups.map(group => [group.keys[0], Number(group.values[manifest.metricIds.sum])])),
    { '2026-01': 30500, '2026-02': 37700, '2026-03': 40400 }
  )
  const company = await api('/nocode/report/dashboard/query', { ...identity, chartId: chart('TABLE').id })
  assert.deepEqual(
    Object.fromEntries(company.groups.map(group => [group.keys[0], Number(group.values[manifest.metricIds.sum])])),
    { [companies[0].name]: 70500, [companies[1].name]: 38100 }
  )
  const account = await api('/nocode/report/dashboard/query', {
    ...identity,
    chartId: chart('TABLE').id,
    drillPath: [companies[0].name]
  })
  assert.deepEqual(
    Object.fromEntries(account.groups.map(group => [group.keys[0], Number(group.values[manifest.metricIds.sum])])),
    { [accounts[0].name]: 40500, [accounts[1].name]: 30000 }
  )
  const months = await api('/nocode/report/dashboard/query', {
    ...identity,
    chartId: chart('TABLE').id,
    drillPath: [companies[0].name, accounts[0].name]
  })
  assert.deepEqual(
    Object.fromEntries(months.groups.map(group => [group.keys[0], Number(group.values[manifest.metricIds.sum])])),
    { '2026-01': 12000, '2026-02': 15000, '2026-03': 13500 }
  )
  checks.push(
    '正式应用写入12笔资金流水；总额108600、核销68200/40400、公司70500/38100、月份30500/37700/40400均由真实API校准'
  )
  return { identity, chart, current, monthly, company, account, months }
}
try {
  const env = Object.fromEntries(
    (await readFile('.env.test', 'utf8'))
      .split(/\r?\n/)
      .filter(line => /^(E2E_USERNAME|E2E_PASSWORD)=/.test(line))
      .map(line => {
        const index = line.indexOf('=')
        return [
          line.slice(0, index),
          line
            .slice(index + 1)
            .trim()
            .replace(/^(['"])(.*)\1$/, '$2')
        ]
      })
  )
  token = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD })).accessToken
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  const info = await api('/system/auth/get-permission-info')
  const baseDataset = await api('/nocode/report/dataset/get?id=981'),
    baseRelease = await api('/nocode/report/dashboard/published?id=5')
  assert.equal(baseDataset.draft.name, '报表体验 · 经营分析数据集')
  assert.equal(baseRelease.content.name, '经营分析 · 核心体验看板')
  const baselineChart = baseRelease.content.charts.find(chart => chart.display === 'METRIC')
  const baselineQuery = {
    id: '5',
    chartId: baselineChart.id,
    preview: false,
    versionNo: baseRelease.versionNo,
    checksum: baseRelease.checksum
  }
  assert.equal(
    Number((await api('/nocode/report/dashboard/query', baselineQuery)).totals[baselineChart.metricIds[0]]),
    19.25
  )
  const source = baseDataset.draft.source
  const refs = [
    source.root,
    ...['5914', '5913'].map(objectId => source.relations.find(relation => relation.target.objectId === objectId).target)
  ]
  const objects = await Promise.all(
    refs.map(async fixed => {
      const object = await api(
        '/nocode/application/object-version?id=' + fixed.objectId + '&versionNo=' + fixed.versionNo
      )
      assert.equal(object.checksum, fixed.checksum)
      assert.ok(object.definition.objectCode.startsWith(original.prefix), '只允许本轮已登记来源夹具')
      object.ids = Object.fromEntries(object.definition.fields.map(field => [field.code, field.id]))
      return object
    })
  )
  assert.deepEqual(
    objects.map(object => object.objectId),
    ['5915', '5914', '5913']
  )
  const names = [entries.map(row => row.name), accounts.map(row => row.name), companies.map(row => row.name)]
  if (manifest.status !== 'COMPLETE') {
    await setupApplication(objects, names, info.user.id)
    for (const company of companies) await put(objects[2], 'company_' + company.key, { name: company.name })
    const accountRelation = objects[1].definition.relations[0].fieldId,
      entryRelation = objects[0].definition.relations[0].fieldId
    const accountCode = objects[1].definition.fields.find(field => field.id === accountRelation).code,
      entryCode = objects[0].definition.fields.find(field => field.id === entryRelation).code
    for (const account of accounts)
      await put(objects[1], 'account_' + account.key, {
        name: account.name,
        [accountCode]: manifest.rows['company_' + account.company]
      })
    for (const entry of entries)
      await put(objects[0], 'entry_' + entry.key, {
        name: entry.name,
        amount: entry.amount,
        occurred: entry.occurred,
        business_day: entry.business_day,
        state: entry.state,
        checked: entry.checked,
        [entryCode]: manifest.rows['account_' + entry.account]
      })
    assert.equal(Object.keys(manifest.rows).length, 18)
    if (!manifest.datasetVersion) await setupDataset(baseDataset, objects, names, info.user.id)
    if (!manifest.dashboardVersion) await setupDashboard(baseRelease)
  }
  const release = await api('/nocode/report/dashboard/published?id=' + manifest.dashboardId)
  assert.ok(release.content.description.includes(prefix))
  assert.ok(
    release.content.charts.every(
      chart =>
        chart.dataset.id === manifest.datasetId &&
        chart.dataset.versionNo === manifest.datasetVersion &&
        chart.dataset.checksum === manifest.datasetChecksum
    )
  )
  const current = await api('/nocode/report/dashboard/get?id=' + manifest.dashboardId)
  assert.equal(current.modified, false, '已发布案例存在用户草稿编辑，请先自行决定是否发布；脚本不自动覆盖')
  const snapshots = await calibrate(release)
  await writeFile(resolve(output, 'api-calibration.json'), JSON.stringify({ prefix, checks, snapshots }, null, 2))
  if (manifest.status !== 'COMPLETE') manifest.status = 'PREPARED'
  await checkpoint()
  console.log(
    'C4 API calibrated; starting browser: dataset ' + manifest.datasetId + ', dashboard ' + manifest.dashboardId
  )
  browser = await chromium.launch({
    headless: true,
    channel: 'chrome',
    args: ['--disable-background-timer-throttling']
  })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      for (const [key, value] of Object.entries({
        userInfo: info.user,
        permissions: info.permissions,
        roles: info.roles,
        menus: info.menus
      }))
        localStorage.setItem(key, JSON.stringify(value))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info }
  )
  const tile = title =>
    page.locator('.dashboard-tile').filter({ has: page.getByRole('heading', { name: title, exact: true }) })
  const metric = snapshots.chart('METRIC'),
    table = snapshots.chart('TABLE'),
    pivot = snapshots.chart('PIVOT')
  const amount = value =>
    expect(tile(metric.title).locator('.dashboard-metrics strong').first()).toHaveText(
      new RegExp('^' + value.toLocaleString('en-US') + '(?:\\.00)?$')
    )
  await page.goto(origin + '/nocode/report-center/dataset-editor?id=' + manifest.datasetId)
  await expect(page.getByRole('textbox', { name: '数据集名称', exact: true })).toHaveValue('经营分析 · 资金流水数据集')
  const accountPanel = page
    .locator('.ant-collapse-item')
    .filter({ has: page.getByRole('button', { name: new RegExp('关联：' + objects[1].definition.objectName) }) })
    .first()
  await accountPanel.getByRole('button', { name: new RegExp('关联：' + objects[1].definition.objectName) }).click()
  await expect(accountPanel.getByRole('checkbox', { name: '选用名称', exact: true })).toBeChecked()
  await expect(accountPanel.getByRole('textbox', { name: '名称分析名称', exact: true })).toHaveValue('账户')
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + manifest.dashboardId)
  await amount(108600)
  await tile(table.title)
    .getByRole('button', { name: /^配\s*置$/ })
    .click()
  let dialog = page.getByRole('dialog')
  await expect(dialog.locator('[aria-label="第2层维度"]')).toContainText('账户')
  await expect(dialog.locator('[aria-label="第3层维度"]')).toContainText('业务日期')
  await expect(dialog.locator('[aria-label="第3层分组粒度"]')).toContainText('按月')
  await expect(dialog.locator('.dialog-link-row')).toHaveCount(5)
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await page.getByRole('button', { name: '公共筛选', exact: true }).click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('textbox', { name: '筛选名称', exact: true })).toHaveCount(3)
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  await amount(108600)
  await page.reload()
  await amount(108600)
  await page.getByRole('button', { name: '发布并打开', exact: true }).click()
  await expect(page).toHaveURL(/dashboard-view/)
  await amount(108600)
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  await expect(page.locator('.report-chart canvas')).toHaveCount(3)
  await expect(tile(pivot.title).locator('.report-pivot')).toContainText('2026-03')
  checks.push(
    '复制配置新增账户标题分析维度；真实设计器重开三筛选/五目标联动/账户月份层级，保存重开发布后六类组件真实运行'
  )
  const latest = await api('/nocode/report/dashboard/published?id=' + manifest.dashboardId)
  manifest.dashboardVersion = latest.versionNo
  manifest.dashboardChecksum = latest.checksum
  await checkpoint()
  async function pick(name, value) {
    const filter = latest.content.filters.find(filter => filter.name === name)
    const response = page.waitForResponse(
      response =>
        response.url().endsWith('/dashboard/options') && response.request().postDataJSON().filterId === filter.id
    )
    await page.locator('[aria-label="' + name + '"] .ant-select-selector').click()
    const candidates = (await (await response).json()).data.list,
      index = candidates.findIndex(candidate => candidate.value === value)
    assert.ok(index >= 0, name + '需提供原键：' + value)
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').nth(index).click()
    await page.getByRole('heading', { name: latest.content.name, exact: true }).click()
  }
  async function reset() {
    await page.getByRole('button', { name: '重置筛选', exact: true }).click()
    await amount(108600)
  }
  async function date(name, value) {
    const input = page.getByRole('textbox', { name, exact: true })
    await input.click()
    await input.fill(value)
    await input.press('Enter')
    await input.press('Escape')
  }
  await pick('公司筛选', companies[0].name)
  await amount(70500)
  await pick('核销状态', 'true')
  await amount(45000)
  await date('日期范围开始日期', '2026-02-01')
  await date('日期范围结束日期', '2026-02-28')
  await page.getByRole('button', { name: '应用日期范围', exact: true }).click()
  await amount(25000)
  await reset()
  await pick('核销状态', 'false')
  await amount(40400)
  await reset()
  checks.push('公共公司、Boolean核销状态及日期交集真实校准：108600→70500→45000→25000，待核销40400，重置回原口径')
  const cell = value =>
    tile(table.title)
      .locator('tbody button')
      .filter({ hasText: new RegExp('^' + value.toLocaleString('en-US') + '(?:\\.00)?$') })
      .first()
  const action = async name => {
    const radio = tile(table.title).getByRole('radio', { name, exact: true })
    await radio.locator('xpath=ancestor::label').click()
    await expect(radio).toBeChecked()
  }
  await action('点击联动')
  await cell(70500).click()
  await amount(70500)
  await expect(page.locator('[aria-label="生效联动"]')).toContainText(companies[0].name)
  await page.getByRole('button', { name: '清除联动', exact: true }).click()
  await amount(108600)
  await action('层级下钻')
  await cell(70500).click()
  await expect(tile(table.title).getByRole('columnheader', { name: '账户', exact: true })).toBeVisible()
  await cell(40500).click()
  await expect(tile(table.title).getByRole('columnheader', { name: '业务日期', exact: true })).toBeVisible()
  await expect(tile(table.title)).toContainText('2026-01')
  const detailResponse = page.waitForResponse(response => response.url().endsWith('/dashboard/details'))
  await cell(12000).click()
  const response = await detailResponse,
    detail = (await response.json()).data
  assert.equal(detail.total, 1)
  assert.deepEqual(response.request().postDataJSON().query.drillPath, [companies[0].name, accounts[0].name])
  assert.ok(detail.list[0].values.some(value => Number(value) === 12000))
  await expect(page.locator('.ant-drawer:visible')).toContainText('共 1 条')
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  const download = page.waitForEvent('download')
  await tile(table.title).getByRole('button', { name: '导出 Excel', exact: true }).click()
  const exportFile = resolve(output, 'account-months.xlsx')
  await (await download).saveAs(exportFile)
  const xml = (await promisify(execFile)('/usr/bin/unzip', ['-p', exportFile, 'xl/worksheets/sheet1.xml'])).stdout
  assert.ok(xml.includes('40500'), '导出应包含当前账户三个月的总额40500')
  await tile(table.title).getByRole('button', { name: '全部', exact: true }).click()
  await expect(tile(table.title).getByRole('columnheader', { name: '公司', exact: true })).toBeVisible()
  await amount(108600)
  checks.push(
    '点选公司显式联动五目标；公司→账户→月份三层真实下钻、月度单条只读明细及当前账户40500的Excel下载，返回根层恢复公司粒度'
  )
  await page.getByRole('heading', { name: latest.content.name, exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: resolve(output, 'dashboard-top.png'), fullPage: true })
  await tile(pivot.title).scrollIntoViewIfNeeded()
  await page.screenshot({ path: resolve(output, 'dashboard-pivot.png'), fullPage: true })
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  const width = () =>
    page.locator('.dashboard-page').evaluate(element => ({ scroll: element.scrollWidth, visible: element.clientWidth }))
  const expanded = await width()
  await page.getByRole('button', { name: '收起二级菜单', exact: true }).click()
  await expect
    .poll(async () => {
      const measured = await width()
      return measured.scroll <= measured.visible + 1
    })
    .toBe(true)
  const collapsed = await width()
  await writeFile(
    resolve(output, 'mobile-widths.json'),
    JSON.stringify({ viewport: 375, expanded, collapsed }, null, 2)
  )
  await page.getByRole('heading', { name: latest.content.name, exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: resolve(output, 'dashboard-mobile.png'), fullPage: true })
  checks.push(
    '1512及375视口均显示六类组件；375视口经真实UI收起二级菜单后看板无横向溢出，表格保留卡片内横向滚动。展开二级菜单宽度已单独记录'
  )
  assert.equal(
    Number((await api('/nocode/report/dashboard/query', baselineQuery)).totals[baselineChart.metricIds[0]]),
    19.25
  )
  assert.deepEqual(JSON.parse(await readFile('.work/report-demo/current.json', 'utf8')), original)
  assert.deepEqual(errors, [])
  manifest.status = 'COMPLETE'
  manifest.expected = {
    total: 108600,
    checked: 68200,
    pending: 40400,
    monthly: [30500, 37700, 40400],
    company: [70500, 38100],
    records: 12
  }
  manifest.names = {
    companies: companies.map(company => company.name),
    accounts: accounts.map(account => account.name),
    entries: entries.map(entry => entry.name)
  }
  await checkpoint()
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ manifest, checks, errors, snapshots }, null, 2))
  console.log(
    'C4 business: ' +
      checks.length +
      ' groups passed; dataset ' +
      manifest.datasetId +
      ', dashboard ' +
      manifest.dashboardId +
      ' V' +
      manifest.dashboardVersion
  )
} catch (error) {
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  await checkpoint()
  await writeFile(
    resolve(output, 'failure.json'),
    JSON.stringify({ prefix, manifest, checks, errors, message: error.message }, null, 2)
  )
  throw error
} finally {
  if (browser) await browser.close()
  if (token) await request('/system/auth/logout', {}).catch(() => {})
  token = undefined
}
