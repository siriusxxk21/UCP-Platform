/** A20：仅停用已登记的自有数据集，验证独立/应用旧固定引用运行边界，finally 正式恢复。 */
import assert from 'node:assert/strict'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 临时停用期间所有HTTP均有限时，异常可及时进入finally正式恢复。 */
class BoundedAcceptance extends FormAcceptance {
  async request(path, body, token = this.tokens.admin, method = body === undefined ? 'GET' : 'POST') {
    const response = await fetch(this.base + path, {
      method,
      signal: AbortSignal.timeout(30000),
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
    return { http: response.status, ...(await response.json()) }
  }
}

assert.ok(process.env.NOCODE_APPLICATION_DASHBOARD_RESUME, '需要明确指定已有P7自有fixture.json')
const fixturePath = resolve(process.env.NOCODE_APPLICATION_DASHBOARD_RESUME)
const fixture = JSON.parse(await readFile(fixturePath, 'utf8'))
assert.match(fixture.prefix, /^p7app_[a-f0-9]{12}$/)
assert.ok(fixture.datasetId && fixture.dashboardId && fixture.applicationId && fixture.dashboardReference)
assert.equal(fixture.dashboardReference.id, fixture.dashboardId)
assert.notEqual(fixture.datasetId, '981')
assert.notEqual(fixture.dashboardId, '5')
const output = resolve(dirname(fixturePath), 'source-status')
await mkdir(output, { recursive: true })
const ac = new BoundedAcceptance(process.env.NOCODE_VERIFY_API, output)
ac.prefix = fixture.prefix
const checks = [],
  errors = [],
  cleanupErrors = [],
  matrix = []
const startedAt = new Date().toISOString()
let stage = '初始化',
  dataset,
  board,
  standaloneRelease,
  baselineModel,
  baselinePolicy,
  sourceSnapshot,
  mustRestore = false,
  restored = false
const modelPath = '/nocode/runtime/dashboard?applicationId=' + fixture.applicationId + '&resourceId=free_dashboard'
async function step(value) {
  stage = value
  console.log('A20来源状态: ' + value)
}
async function sourceState() {
  const paths = [
    '/nocode/report/dashboard/get?id=5',
    '/nocode/report/dashboard/published?id=5',
    '/nocode/report/dashboard/resource-policy?id=5',
    '/nocode/report/dataset/get?id=981',
    '/nocode/report/dataset/data-policy?id=981',
    '/nocode/report/dataset/resource-policy?id=981',
    '/nocode/report/dataset/ceilings?id=981'
  ]
  const state = []
  for (const path of paths) state.push(await ac.api(path))
  return { state, manifest: await readFile('.work/report-demo/current.json', 'utf8') }
}
async function ownPolicies() {
  const policies = []
  for (const endpoint of ['data-policy', 'resource-policy', 'ceilings'])
    policies.push(await ac.api('/nocode/report/dataset/' + endpoint + '?id=' + fixture.datasetId))
  return policies
}
async function call(path, body) {
  const response = await fetch(ac.base + path, {
    method: body === undefined ? 'GET' : 'POST',
    signal: AbortSignal.timeout(30000),
    headers: { Authorization: 'Bearer ' + ac.tokens.admin, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (response.headers.get('content-type')?.includes('spreadsheet')) {
    const bytes = Buffer.from(await response.arrayBuffer())
    assert.equal(response.status, 200)
    assert.ok(bytes.length > 100 && bytes.subarray(0, 2).toString() === 'PK', '正向导出必须为真实XLSX')
    return { http: response.status, code: 0, binaryBytes: bytes.length }
  }
  return { http: response.status, ...(await response.json()) }
}
function routes(model) {
  const metric = model.dashboard.content.charts.find(chart => chart.display === 'METRIC')
  const standaloneMetric = standaloneRelease.content.charts.find(chart => chart.display === 'METRIC')
  const filter = model.dashboard.content.filters.find(item => item.name === '公司筛选')
  const independentFilter = standaloneRelease.content.filters.find(item => item.name === '公司筛选')
  assert.ok(metric && standaloneMetric && filter && independentFilter)
  assert.ok(model.dashboard.content.charts.every(chart => chart.dataset.id === fixture.datasetId))
  assert.ok(standaloneRelease.content.charts.every(chart => chart.dataset.id === fixture.datasetId))
  assert.equal(model.config.detailViews.find(item => item.chartId === metric.id)?.viewId, 'detail_view')
  const independent = {
    id: standaloneRelease.id,
    chartId: standaloneMetric.id,
    preview: false,
    versionNo: standaloneRelease.versionNo,
    checksum: standaloneRelease.checksum
  }
  const embedded = {
    applicationId: fixture.applicationId,
    resourceId: 'free_dashboard',
    chartId: metric.id,
    stamp: model.stamp
  }
  const details = query => ({ query, group: [], columnGroup: [], pageNo: 1, pageSize: 20 })
  return [
    { name: 'independent.model', path: '/nocode/report/dashboard/published?id=' + fixture.dashboardId, model: true },
    {
      name: 'independent.query',
      path: '/nocode/report/dashboard/query',
      body: independent,
      total: 19.25,
      metricId: standaloneMetric.metricIds[0]
    },
    {
      name: 'independent.options',
      path: '/nocode/report/dashboard/options',
      body: { query: independent, filterId: independentFilter.id, pageNo: 1, pageSize: 20 }
    },
    { name: 'independent.details', path: '/nocode/report/dashboard/details', body: details(independent) },
    { name: 'independent.export', path: '/nocode/report/dashboard/export', body: independent },
    { name: 'application.model', path: modelPath, model: true },
    {
      name: 'application.query',
      path: '/nocode/runtime/dashboard-query',
      body: embedded,
      total: 9,
      metricId: metric.metricIds[0]
    },
    {
      name: 'application.options',
      path: '/nocode/runtime/dashboard-options',
      body: { query: embedded, filterId: filter.id, pageNo: 1, pageSize: 20 }
    },
    { name: 'application.details', path: '/nocode/runtime/dashboard-details', body: details(embedded), rows: 2 },
    { name: 'application.export', path: '/nocode/runtime/dashboard-export', body: embedded },
    {
      name: 'application.business',
      path: '/nocode/runtime/page',
      body: {
        applicationId: fixture.applicationId,
        objectId: '5915',
        viewId: 'detail_view',
        pageNo: 1,
        pageSize: 20,
        equal: {},
        descending: false,
        dashboardDrill: { query: embedded, group: [], columnGroup: [], metricId: metric.metricIds[0] }
      },
      rows: 2
    }
  ]
}
async function inspect(label, cases, inactive) {
  for (const item of cases) {
    const result = await call(item.path, item.body)
    const row = {
      stage: label,
      endpoint: item.name,
      http: result.http,
      code: result.code,
      message: result.msg || '',
      outcome: result.code === 0 ? (item.model ? 'ALLOWED_METADATA' : 'ALLOWED_DATA') : 'DENIED'
    }
    if (result.binaryBytes) row.binaryBytes = result.binaryBytes
    matrix.push(row)
    if (inactive && item.model && result.code === 0) {
      // 模型若仍可读只能含发布配置/能力，不把元数据成功误记为取数穿透。
      assert.ok(result.data?.content || result.data?.dashboard?.content)
      for (const forbidden of ['list', 'groups', 'totals', 'records']) assert.equal(result.data[forbidden], undefined)
      continue
    }
    if (inactive) {
      assert.ok(
        [1050000001, 1050000007, 403].includes(result.code),
        item.name + ' 应由来源停用/版本/授权边界拒绝，实际 ' + result.code
      )
      assert.match(result.msg || '', /停用|失效|不可用|权限|授权|变更|更新|刷新/)
      assert.equal(result.binaryBytes, undefined)
    } else {
      assert.equal(result.code, 0, item.name + ': ' + result.msg)
      if (item.total !== undefined) assert.equal(Number(result.data.totals[item.metricId]), item.total)
      if (item.rows !== undefined) assert.equal(result.data.total, item.rows)
      if (item.name === 'application.business') assert.equal(result.data.drillTotal, 2)
      if (item.name === 'application.model')
        assert.deepEqual(
          {
            id: result.data.dashboard.id,
            versionNo: result.data.dashboard.versionNo,
            checksum: result.data.dashboard.checksum
          },
          fixture.dashboardReference
        )
    }
  }
}
const lock = resolve(dirname(fixturePath), '.running')
await mkdir(lock)
await writeFile(
  resolve(lock, 'run.json'),
  JSON.stringify({ prefix: fixture.prefix, pid: process.pid, output, startedAt }, null, 2)
)
try {
  await ac.login()
  await step('仅自有名称/拥有者预检与ACTIVE正向基线')
  const info = await ac.api('/system/auth/get-permission-info')
  dataset = await ac.api('/nocode/report/dataset/get?id=' + fixture.datasetId)
  board = await ac.api('/nocode/report/dashboard/get?id=' + fixture.dashboardId)
  const app = await ac.api('/nocode/application/get?id=' + fixture.applicationId)
  assert.equal(dataset.draft.name, fixture.prefix + ' 验收数据集')
  assert.equal(board.draft.name, fixture.prefix + ' 验收看板')
  assert.equal(String(dataset.ownerId), String(info.user.id))
  assert.equal(String(board.ownerId), String(info.user.id))
  assert.equal(app.application.code, fixture.prefix)
  assert.equal(dataset.status, 'ACTIVE', '只临时停用原本启用的自有数据集')
  sourceSnapshot = await sourceState()
  baselinePolicy = await ownPolicies()
  standaloneRelease = await ac.api('/nocode/report/dashboard/published?id=' + fixture.dashboardId)
  baselineModel = await ac.api(modelPath)
  await inspect('ACTIVE_BASELINE', routes(baselineModel), false)
  checks.push('自有名称/owner精确守卫，独立当前版19.25/应用固定旧版9.00；11类模型/取数/导出/业务路由正向基线通过')
  await step('正式临时INACTIVE并逐接口检查旧固定引用')
  mustRestore = true
  const disabled = await ac.api('/nocode/report/dataset/status', {
    id: dataset.id,
    expectedRevision: dataset.revision,
    status: 'INACTIVE',
    reason: fixture.prefix + ' A20自有来源暂时停用验收'
  })
  assert.equal(disabled.status, 'INACTIVE')
  assert.deepEqual(disabled.draft, dataset.draft)
  await inspect('INACTIVE', routes(baselineModel), true)
  checks.push(
    '自有数据集正式停用后，独立/应用query/options/details/export和application业务分页拒绝；两个模型端点行为逐项记录'
  )
} catch (error) {
  errors.push(stage + ': ' + error.message)
} finally {
  if (mustRestore && ac.tokens.admin) {
    try {
      await step('finally正式ACTIVE恢复及全路由正向复验')
      const current = await ac.api('/nocode/report/dataset/get?id=' + fixture.datasetId)
      assert.equal(current.draft.name, fixture.prefix + ' 验收数据集')
      assert.equal(current.ownerId, dataset.ownerId)
      if (current.status !== 'ACTIVE')
        await ac.api('/nocode/report/dataset/status', {
          id: current.id,
          expectedRevision: current.revision,
          status: 'ACTIVE',
          reason: fixture.prefix + ' A20验收finally恢复原启用状态'
        })
      const active = await ac.api('/nocode/report/dataset/get?id=' + fixture.datasetId)
      assert.equal(active.status, 'ACTIVE')
      assert.deepEqual(active.draft, dataset.draft)
      assert.equal(active.publishedVersion, dataset.publishedVersion)
      assert.equal(active.checksum, dataset.checksum)
      assert.deepEqual(await ownPolicies(), baselinePolicy)
      const model = await ac.api(modelPath)
      await inspect('ACTIVE_RESTORED', routes(model), false)
      assert.deepEqual(await sourceState(), sourceSnapshot)
      restored = true
      checks.push(
        'finally正式恢复ACTIVE，11类路由再次全部成功且固定pin不变，独立19.25/应用9.00/业务2条恢复；自有授权及原5/981/manifest均未改动'
      )
    } catch (error) {
      cleanupErrors.push('恢复/复验: ' + error.message)
    }
  }
  if (ac.tokens.admin)
    await ac.api('/system/auth/logout', {}).catch(error => cleanupErrors.push('注销: ' + error.message))
  ac.tokens = {}
  ac.passwords = {}
  await rm(lock, { recursive: true, force: true })
  const result = {
    prefix: fixture.prefix,
    applicationId: fixture.applicationId,
    datasetId: fixture.datasetId,
    dashboardId: fixture.dashboardId,
    applicationDashboardReference: fixture.dashboardReference,
    standaloneDashboardReference: standaloneRelease
      ? { id: standaloneRelease.id, versionNo: standaloneRelease.versionNo, checksum: standaloneRelease.checksum }
      : null,
    startedAt,
    finishedAt: new Date().toISOString(),
    stage,
    restored,
    checks,
    matrix,
    errors,
    cleanupErrors,
    status: checks.length === 3 && restored && !errors.length && !cleanupErrors.length ? 'PASSED' : 'FAILED'
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(
    'A20 application dashboard source status: ' + checks.length + ' groups; ' + result.status + '; evidence ' + output
  )
  if (result.status !== 'PASSED') process.exitCode = 1
}
