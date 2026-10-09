/** P7 B1/B2：正式应用固定看板 API + Chrome 验收，只复制登记夹具，不改原资源与授权。 */
import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20000 })
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const verifyBusiness = process.env.NOCODE_VERIFY_SKIP_DASHBOARD_BUSINESS !== '1'
const verifyDifference = process.env.NOCODE_VERIFY_DASHBOARD_DIFFERENCE === '1'
const root = resolve('.work/report-demo/p7-application-dashboard')
await mkdir(root, { recursive: true })
// 同一开发环境只运行一份本脚本；中断后根据 lock/fixture 核查自有夹具再移除锁。
const lock = resolve(root, '.running')
// fixture 可续跑；已发布样例只复核原固定引用，不重置其草稿或历史。
const resumePath = process.env.NOCODE_APPLICATION_DASHBOARD_RESUME
const resumed = resumePath ? JSON.parse(await readFile(resolve(resumePath), 'utf8')) : null
if (resumed) {
  assert.match(resumed.prefix, /^p7app_[a-f0-9]{12}$/)
  assert.notEqual(String(resumed.datasetId), '981')
  assert.notEqual(String(resumed.dashboardId), '5')
}
const prefix = resumed?.prefix || 'p7app_' + randomBytes(6).toString('hex')
const username = prefix.replaceAll('_', '')
const output = resumed ? dirname(resolve(resumePath)) : resolve(root, prefix)
await mkdir(output, { recursive: true })
const startedAt = new Date().toISOString()
const fixture = { ...resumed, prefix, createdAt: resumed?.createdAt || new Date().toISOString(), retained: true }
const checks = [],
  errors = [],
  cleanupErrors = []
let token,
  viewerToken,
  userId,
  roleId,
  browser,
  page,
  viewerPage,
  failure,
  stage = '初始化'
let sourceBoard, sourceRelease, sourceDataset, sourceDataPolicy, sourceResourcePolicy, originalManifest
let originalViewConfig,
  viewScopeChanged = false
let ownerId,
  permissions,
  boundedPermissions,
  dataset,
  release,
  application,
  boundId,
  metric,
  table,
  companyFilter,
  independent
const env = Object.fromEntries(
  (await readFile('.env.test', 'utf8'))
    .split(/\r?\n/)
    .filter(line => /^(E2E_USERNAME|E2E_PASSWORD)=/.test(line))
    .map(line => {
      const i = line.indexOf('=')
      return [
        line.slice(0, i),
        line
          .slice(i + 1)
          .trim()
          .replace(/^(['"])(.*)\1$/, '$2')
      ]
    })
)
const self = member => member.principalKind === 'USER' && String(member.principalId) === String(userId)
async function checkpoint() {
  await writeFile(resolve(output, 'fixture.json'), JSON.stringify({ ...fixture, userId, roleId, stage }, null, 2))
}
async function step(value) {
  stage = value
  await checkpoint()
  console.log('P7: ' + value)
}
async function request(path, body, session = token, method = body === undefined ? 'GET' : 'POST') {
  return fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(session ? { Authorization: 'Bearer ' + session } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}
async function api(path, body, session = token, method) {
  const response = await request(path, body, session, method)
  const result = await response.json()
  assert.equal(result.code, 0, path + ': ' + result.msg)
  return result.data
}
async function rejected(path, body, code, session = token) {
  const response = await request(path, body, session)
  assert.ok(response.headers.get('content-type')?.includes('json'), '拒绝请求不得交付文件：' + path)
  const result = await response.json()
  assert.equal(result.code, code, path + ': ' + result.code + '/' + result.msg)
}
// 所有范围在原 grant 上增加 AND 条件，原字段、关系和行条件完整保留。
function restrict(grants, fieldId, operator, value) {
  return grants.map(original => {
    const grant = structuredClone(original)
    if (grant.objectId === '5915') {
      for (const action of grant.actions) {
        const previous = grant.actionScopes?.[action]
        grant.actionScopes ||= {}
        grant.actionScopes[action] = {
          logic: 'AND',
          conditions: [{ fieldId, operator, value }],
          groups: previous ? [previous] : []
        }
      }
    }
    return grant
  })
}
const exporting = (grants, enabled) =>
  grants.map(original => {
    const grant = structuredClone(original)
    grant.actions = enabled ? ['READ', 'EXPORT'] : ['READ']
    grant.actionScopes = Object.fromEntries(
      Object.entries(grant.actionScopes || {}).filter(([action]) => grant.actions.includes(action))
    )
    return grant
  })
async function memberPolicy(kind, member) {
  const paths = {
    application: '/nocode/application/authorization',
    data: '/nocode/report/dataset/data-policy',
    resource: '/nocode/report/dashboard/resource-policy'
  }
  const path = paths[kind],
    id = kind === 'application' ? fixture.applicationId : kind === 'data' ? fixture.datasetId : fixture.dashboardId
  for (let attempt = 0; attempt < 3; attempt++) {
    const policy = await api(path + '?id=' + id)
    const members = policy.members.filter(item => !self(item))
    if (member) members.push(member)
    const result = await (
      await request(path, {
        ...(kind === 'application' ? { applicationId: id } : kind === 'data' ? { datasetId: id } : { id }),
        expectedRevision: policy.revision,
        members,
        ...(kind === 'application' ? {} : { reason: prefix + ' 临时成员合并修改/恢复' })
      })
    ).json()
    if (!result.code) return result.data
    if (attempt === 2 || !/修订|冲突|更新|重试/.test(result.msg || ''))
      assert.equal(result.code, 0, path + ': ' + result.msg)
  }
}
async function authenticatedPage(session, info) {
  const context = await browser.newContext({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
  await context.addInitScript(
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
    { token: session, info }
  )
  const result = await context.newPage()
  result.setDefaultTimeout(20000)
  result.on('pageerror', error => errors.push(error.message))
  return result
}
const tile = (target, title) =>
  target.locator('.dashboard-tile').filter({ has: target.getByRole('heading', { name: title, exact: true }) })
const amount = (target, value) =>
  expect(tile(target, metric.title).locator('.dashboard-metrics strong').first()).toHaveText(value)
async function settledTable(scope) {
  await expect(scope.locator('.ant-spin-spinning, .ant-spin-blur, .ant-btn-loading')).toHaveCount(0)
  await expect
    .poll(() =>
      scope
        .locator('.ant-spin-container')
        .evaluateAll(nodes => nodes.every(node => Number(getComputedStyle(node).opacity) === 1))
    )
    .toBe(true)
}
const runtimePath = menu => origin + '/nocode-app/runtime?id=' + fixture.applicationId + '&menu=' + menu
const modelPath = resourceId =>
  '/nocode/runtime/dashboard?applicationId=' + fixture.applicationId + '&resourceId=' + resourceId
function query(model, chartId = metric.id, parameters) {
  return {
    applicationId: fixture.applicationId,
    resourceId: model.resourceId,
    chartId,
    stamp: model.stamp,
    ...(parameters ? { parameters } : {})
  }
}
function businessQuery(reportQuery, changes = {}) {
  return {
    applicationId: fixture.applicationId,
    objectId: '5915',
    viewId: 'detail_view',
    pageNo: 1,
    pageSize: 20,
    equal: {},
    descending: false,
    dashboardDrill: { query: reportQuery, group: [], columnGroup: [], metricId: metric.metricIds[0] },
    ...changes
  }
}
async function select(container, label, option) {
  await container
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + label + '$') }) })
    .locator('.ant-select-selector')
    .click()
  await page.locator('.ant-select-dropdown:visible').getByText(option, { exact: true }).click()
}
async function saveApplication(definition) {
  const current = await api('/nocode/application/get?id=' + fixture.applicationId)
  return api('/nocode/application/save', {
    id: fixture.applicationId,
    expectedRevision: current.application.revision,
    code: current.application.code,
    name: current.application.name,
    description: current.application.description,
    definition
  })
}
async function publishBusinessView(config, reason) {
  const current = await api('/nocode/application/get?id=' + fixture.applicationId)
  assert.equal(current.application.code, prefix, '仅调整自有验收应用')
  const definition = structuredClone(current.draft)
  const view = definition.resources.find(resource => resource.id === 'detail_view' && resource.kind === 'VIEW')
  assert.ok(view && view.config.objectId === '5915')
  view.config = config
  const saved = await saveApplication(definition)
  application = await api('/nocode/application/publish', {
    id: fixture.applicationId,
    expectedRevision: saved.application.revision,
    reason: prefix + ' ' + reason
  })
  fixture.applicationVersion = application.application.publishedVersion
  await checkpoint()
}
await mkdir(lock)
await writeFile(resolve(lock, 'run.json'), JSON.stringify({ prefix, pid: process.pid, output, startedAt }, null, 2))
try {
  await step('只读预检与自有副本')
  token = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD }, null))
    .accessToken
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  assert.ok(token)
  const info = await api('/system/auth/get-permission-info')
  ownerId = String(info.user.id)
  originalManifest = await readFile('.work/report-demo/current.json', 'utf8')
  const registered = JSON.parse(originalManifest)
  assert.equal(String(registered.dashboardId), '5')
  assert.equal(String(registered.datasetId), '981')
  assert.deepEqual(registered.objectIds.map(String), ['5915', '5914', '5913'])
  assert.match(registered.prefix, /^test_b1_[a-f0-9]+$/)
  sourceBoard = await api('/nocode/report/dashboard/get?id=5')
  sourceRelease = await api('/nocode/report/dashboard/published?id=5')
  sourceDataset = await api('/nocode/report/dataset/get?id=981')
  sourceDataPolicy = await api('/nocode/report/dataset/data-policy?id=981')
  sourceResourcePolicy = await api('/nocode/report/dashboard/resource-policy?id=5')
  assert.equal(String(sourceBoard.ownerId), ownerId)
  assert.equal(String(sourceDataset.ownerId), ownerId)
  assert.equal(sourceBoard.draft.name, '经营分析 · 核心体验看板')
  permissions = structuredClone(
    sourceDataPolicy.members.find(member => member.principalKind === 'USER' && String(member.principalId) === ownerId)
      ?.objects
  )
  assert.ok(
    permissions?.length === 3 &&
      permissions.every(
        grant =>
          registered.objectIds.includes(grant.objectId) &&
          grant.actions.includes('READ') &&
          grant.actions.includes('EXPORT')
      )
  )
  const amountField = sourceDataset.draft.source.fields.find(field => field.name === '金额')
  assert.ok(amountField && !amountField.path.length)
  boundedPermissions = restrict(permissions, amountField.sourceFieldId, 'lte', '5')
  if (resumed?.applicationVersion) {
    dataset = await api('/nocode/report/dataset/get?id=' + fixture.datasetId)
    assert.equal(dataset.draft.name, prefix + ' 验收数据集')
    const ownBoard = await api('/nocode/report/dashboard/get?id=' + fixture.dashboardId)
    assert.equal(ownBoard.draft.name, prefix + ' 验收看板')
    const ref = fixture.dashboardReference
    release = (await api(modelPath(fixture.boundResourceId))).dashboard
    assert.deepEqual({ id: release.id, versionNo: release.versionNo, checksum: release.checksum }, ref)
    application = await api('/nocode/application/get?id=' + fixture.applicationId)
    assert.equal(application.application.code, prefix)
    const resource = application.draft.resources.find(item => item.id === fixture.boundResourceId)
    assert.deepEqual(resource?.config.dashboard, fixture.dashboardReference)
    boundId = fixture.boundResourceId
    metric = release.content.charts.find(chart => chart.display === 'METRIC')
    table = release.content.charts.find(
      chart => chart.display === 'TABLE' && chart.links?.length && chart.drillDimensions?.length === 2
    )
    companyFilter = release.content.filters.find(filter => filter.name === '公司筛选')
    assert.ok(metric && table && companyFilter)
    independent = {
      id: release.id,
      chartId: metric.id,
      preview: false,
      versionNo: release.versionNo,
      checksum: release.checksum
    }
    browser = await chromium.launch({
      channel: 'chrome',
      headless: process.env.NOCODE_VERIFY_HEADED !== '1',
      args: ['--disable-background-timer-throttling']
    })
    page = await authenticatedPage(token, info)
    await page.goto(origin + '/nocode-app/workspace?id=' + fixture.applicationId)
    await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
    await page.locator('.resources').getByText('报表看板', { exact: true }).click()
    await page
      .locator('tr[data-row-key="' + boundId + '"]')
      .getByRole('button', { name: /配置/ })
      .click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('固定版本 v' + ref.versionNo, { exact: true })).toBeVisible()
    await expect(dialog.getByRole('textbox', { name: '资源编码', exact: true })).toHaveValue('bound_dashboard')
    await expect(dialog.getByPlaceholder('字母开头，例如 customerId')).toHaveValue('company')
    await expect(dialog.getByText('验收流水明细视图', { exact: true })).toBeVisible()
    await page.screenshot({ path: resolve(output, 'configured-resumed.png'), fullPage: true })
    await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
    checks.push(
      '复用明确前缀已发布样例；Chrome配置再次确认固定旧看板版本、PARAMETER company与detailViews，保持原发布引用'
    )
  } else {
    dataset = fixture.datasetId
      ? await api('/nocode/report/dataset/get?id=' + fixture.datasetId)
      : await api('/nocode/report/dataset/copy', {
          id: '981',
          expectedRevision: sourceDataset.revision,
          name: prefix + ' 验收数据集',
          reason: prefix + ' 自有固定看板案例'
        })
    assert.equal(dataset.draft.name, prefix + ' 验收数据集', '恢复仅允许自身数据集')
    fixture.datasetId = dataset.id
    await checkpoint()
    const ceilings = await api('/nocode/report/dataset/ceilings?id=' + dataset.id)
    for (const permission of permissions)
      await api('/nocode/report/dataset/ceiling', {
        datasetId: dataset.id,
        objectId: permission.objectId,
        expectedRevision: ceilings.find(item => item.objectId === permission.objectId)?.revision || 0,
        permission,
        reason: prefix + ' 精确复制原拥有者范围'
      })
    const dataPolicy = await api('/nocode/report/dataset/data-policy?id=' + dataset.id)
    await api('/nocode/report/dataset/data-policy', {
      datasetId: dataset.id,
      expectedRevision: dataPolicy.revision,
      members: [{ principalKind: 'USER', principalId: ownerId, objects: permissions }],
      reason: prefix
    })
    const datasetRelease = await api('/nocode/report/dataset/publish', {
      id: dataset.id,
      expectedRevision: dataset.revision,
      requestId: randomUUID(),
      reason: prefix
    })
    const copied = fixture.dashboardId
      ? await api('/nocode/report/dashboard/get?id=' + fixture.dashboardId)
      : await api('/nocode/report/dashboard/copy', {
          id: '5',
          expectedRevision: sourceBoard.revision,
          name: prefix + ' 验收看板',
          reason: prefix
        })
    assert.equal(copied.draft.name, prefix + ' 验收看板', '恢复仅允许自身看板')
    fixture.dashboardId = copied.id
    await checkpoint()
    const fieldMap = Object.fromEntries(
      sourceDataset.draft.source.fields.map(field => [
        field.id,
        dataset.draft.source.fields.find(item => item.name === field.name && item.sourceFieldId === field.sourceFieldId)
          ?.id
      ])
    )
    for (const field of dataset.draft.source.fields) fieldMap[field.id] = field.id
    const metricMap = Object.fromEntries(
      sourceDataset.draft.analysis.metrics.map(metric => [
        metric.id,
        dataset.draft.analysis.metrics.find(item => item.name === metric.name && item.operation === metric.operation)
          ?.id
      ])
    )
    for (const metric of dataset.draft.analysis.metrics) metricMap[metric.id] = metric.id
    assert.ok(
      Object.values(fieldMap).every(Boolean) && Object.values(metricMap).every(Boolean),
      '副本字段/指标重映射须完整'
    )
    const content = structuredClone(copied.draft)
    for (const chart of content.charts) {
      chart.dataset = { id: dataset.id, versionNo: datasetRelease.versionNo, checksum: datasetRelease.checksum }
      chart.metricIds = chart.metricIds.map(id => metricMap[id])
      for (const key of ['dimensions', 'columnDimensions', 'drillDimensions'])
        if (chart[key]) chart[key] = chart[key].map(value => ({ ...value, fieldId: fieldMap[value.fieldId] }))
      if (chart.links)
        chart.links = chart.links.map(link => ({
          ...link,
          sourceFieldId: fieldMap[link.sourceFieldId],
          targetFieldId: fieldMap[link.targetFieldId]
        }))
    }
    for (const filter of content.filters || [])
      filter.mappings = filter.mappings.map(mapping => ({ ...mapping, fieldId: fieldMap[mapping.fieldId] }))
    const board = await api('/nocode/report/dashboard/save', {
      id: copied.id,
      expectedRevision: copied.revision,
      content
    })
    release = await api('/nocode/report/dashboard/publish', {
      id: copied.id,
      expectedRevision: board.revision,
      requestId: randomUUID()
    })
    fixture.dashboardReference = { id: release.id, versionNo: release.versionNo, checksum: release.checksum }
    await checkpoint()
    metric = release.content.charts.find(chart => chart.display === 'METRIC')
    table = release.content.charts.find(
      chart => chart.display === 'TABLE' && chart.links?.length && chart.drillDimensions?.length === 2
    )
    companyFilter = release.content.filters.find(filter => filter.name === '公司筛选')
    assert.ok(metric && table && companyFilter)
    independent = {
      id: release.id,
      chartId: metric.id,
      preview: false,
      versionNo: release.versionNo,
      checksum: release.checksum
    }
    assert.equal(Number((await api('/nocode/report/dashboard/query', independent)).totals[metric.metricIds[0]]), 19.25)
    const objects = [dataset.draft.source.root, ...dataset.draft.source.relations.map(relation => relation.target)]
    const rootDefinition = await api('/nocode/application/object-version?id=5915&versionNo=' + objects[0].versionNo)
    if (fixture.applicationId) {
      const existing = await api('/nocode/application/get?id=' + fixture.applicationId)
      assert.equal(existing.application.code, prefix, '恢复仅允许自身应用')
      assert.equal(existing.application.publishedVersion, null, '已发布样例不能重置')
      application = existing
    }
    application = await api('/nocode/application/save', {
      id: fixture.applicationId || null,
      expectedRevision: application?.application.revision ?? null,
      code: prefix,
      name: prefix + ' 固定看板验收',
      description: prefix + '；自有可保留验收样例，仅引用登记体验对象，不新增业务记录。',
      definition: {
        objects,
        resources: [
          {
            id: 'detail_view',
            code: 'detail_view',
            name: '验收流水明细视图',
            kind: 'VIEW',
            config: {
              objectId: '5915',
              fieldIds: permissions[0].readFields.filter(id =>
                rootDefinition.definition.fields.some(field => field.id === id)
              ),
              equal: {},
              pageSize: 20,
              descending: false
            }
          }
        ]
      }
    })
    fixture.applicationId = application.application.id
    await checkpoint()
    for (const permission of boundedPermissions) {
      const current = await api('/nocode/object-sharing/list?objectId=' + permission.objectId)
      await api('/nocode/object-sharing/save', {
        objectId: permission.objectId,
        applicationId: fixture.applicationId,
        expectedRevision: current.find(item => item.applicationId === fixture.applicationId)?.revision || 0,
        permission,
        reason: prefix + ' 金额<=5 AND原范围'
      })
    }
    const applicationPolicy = await api('/nocode/application/authorization?id=' + fixture.applicationId)
    await api('/nocode/application/authorization', {
      applicationId: fixture.applicationId,
      expectedRevision: applicationPolicy.revision,
      members: [{ principalKind: 'USER', principalId: ownerId, objects: boundedPermissions }]
    })
    checks.push('自有数据集/看板副本保留全部字段和原精确数据范围；独立正式看板19.25；应用共享增加金额<=5')

    await step('Chrome配置固定引用、参数、明细视图并保存')
    browser = await chromium.launch({
      channel: 'chrome',
      headless: process.env.NOCODE_VERIFY_HEADED !== '1',
      args: ['--disable-background-timer-throttling']
    })
    page = await authenticatedPage(token, info)
    await page.goto(origin + '/nocode-app/workspace?id=' + fixture.applicationId)
    await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
    await page.locator('.resources').getByText('报表看板', { exact: true }).click()
    await page.getByRole('button', { name: /新建报表看板/ }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('配置报表看板', { exact: true })).toBeVisible()
    await dialog.getByRole('textbox', { name: '资源名称', exact: true }).fill('参数看板')
    await dialog.getByRole('textbox', { name: '资源编码', exact: true }).fill('bound_dashboard')
    await select(dialog, '已发布看板', release.content.name + ' · v' + release.versionNo + ' · 6 个图表')
    await expect(dialog.getByText('固定版本 v' + release.versionNo, { exact: true })).toBeVisible()
    await dialog.getByRole('button', { name: '添加输入绑定', exact: true }).click()
    await select(dialog, '看板筛选', '公司筛选')
    await dialog.getByPlaceholder('字母开头，例如 customerId').fill('company')
    await dialog.getByRole('button', { name: '添加业务明细视图', exact: true }).click()
    await select(dialog, '图表', metric.title)
    await select(dialog, '本应用数据视图', '验收流水明细视图')
    await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    const savedResponse = page.waitForResponse(response => response.url().endsWith('/application/save'))
    await page.getByRole('button', { name: /保存草稿/ }).click()
    const saved = await (await savedResponse).json()
    assert.equal(saved.code, 0, saved.msg)
    application = saved.data
    const bound = application.draft.resources.find(resource => resource.code === 'bound_dashboard')
    assert.ok(bound)
    boundId = bound.id
    fixture.boundResourceId = boundId
    await checkpoint()
    assert.deepEqual(bound.config.dashboard, fixture.dashboardReference)
    assert.deepEqual(bound.config.inputBindings, [
      { filterId: companyFilter.id, source: 'PARAMETER', parameter: 'company', fieldId: null }
    ])
    assert.deepEqual(bound.config.detailViews, [{ chartId: metric.id, viewId: 'detail_view' }])
    await page.screenshot({ path: resolve(output, 'configured.png'), fullPage: true })
    const resources = [
      ...application.draft.resources,
      {
        id: 'free_dashboard',
        code: 'free_dashboard',
        name: '权限交集看板',
        kind: 'REPORT_DASHBOARD',
        config: { ...bound.config, inputBindings: [] }
      },
      {
        id: 'dashboard_page',
        code: 'dashboard_page',
        name: '内嵌分析页',
        kind: 'PAGE',
        config: {
          contextObjectId: null,
          nodes: [{ id: 'dashboard_node', type: 'REPORT_DASHBOARD', resourceId: 'free_dashboard', children: [] }]
        }
      },
      {
        id: 'page_menu',
        code: 'page_menu',
        name: '页面内嵌看板',
        kind: 'MENU',
        config: { targetId: 'dashboard_page' }
      },
      {
        id: 'direct_menu',
        code: 'direct_menu',
        name: '看板直接入口',
        kind: 'MENU',
        config: { targetId: 'free_dashboard' }
      },
      { id: 'bound_menu', code: 'bound_menu', name: '参数看板入口', kind: 'MENU', config: { targetId: boundId } }
    ]
    application = await saveApplication({ objects, resources })
    await page.reload()
    await page.getByRole('button', { name: /发布应用/ }).click()
    const publishDialog = page.getByRole('dialog')
    await expect(publishDialog.getByText('发布应用', { exact: true }).first()).toBeVisible()
    await publishDialog.getByRole('textbox', { name: '发布说明', exact: true }).fill(prefix + ' B1固定看板正式验收')
    const publishResponse = page.waitForResponse(response => response.url().endsWith('/application/publish'))
    await expect(publishDialog.getByRole('button', { name: '发布应用', exact: true })).toBeEnabled()
    await publishDialog.getByRole('button', { name: '发布应用', exact: true }).click()
    const published = await (await publishResponse).json()
    assert.equal(published.code, 0, published.msg)
    application = published.data
    fixture.applicationVersion = application.application.publishedVersion
    await checkpoint()
    checks.push(
      '真实Chrome新建看板资源，候选固定版本、PARAMETER输入与detailViews配置，应用到草稿/保存/正式发布成功；PAGE和MENU均引用正式资源'
    )
  }

  await step('PAGE/MENU运行、参数拒绝、候选、明细与Excel')
  const freeModel = await api(modelPath('free_dashboard')),
    boundModel = await api(modelPath(boundId))
  const freeQuery = query(freeModel),
    nullQuery = query(boundModel, metric.id, { company: { values: [null] } })
  assert.equal(Number((await api('/nocode/runtime/dashboard-query', freeQuery)).totals[metric.metricIds[0]]), 9)
  for (const parameters of [
    undefined,
    {},
    { company: { values: [] } },
    { company: { values: [''] } },
    { company: { values: [null] }, other: { values: ['x'] } }
  ])
    await rejected('/nocode/runtime/dashboard-query', { ...query(boundModel), parameters }, 1050000001)
  await rejected(
    '/nocode/runtime/dashboard-query',
    { ...nullQuery, filterValues: [{ filterId: companyFilter.id, values: ['可见公司'] }] },
    1050000001
  )
  for (const spoof of [
    { id: '5' },
    { datasetId: '981' },
    { versionNo: 99 },
    { dashboard: fixture.dashboardReference },
    { scope: 'ALL' }
  ])
    await rejected('/nocode/runtime/dashboard-query', { ...freeQuery, ...spoof }, 1050000001)
  assert.equal(Number((await api('/nocode/runtime/dashboard-query', nullQuery)).totals[metric.metricIds[0]]), 9)
  const candidates = await api('/nocode/runtime/dashboard-options', {
    query: freeQuery,
    filterId: companyFilter.id,
    pageNo: 1,
    pageSize: 20
  })
  assert.deepEqual(
    candidates.list.map(option => option.value),
    [null]
  )
  const detailsBody = {
    query: nullQuery,
    group: [],
    columnGroup: [],
    metricId: metric.metricIds[0],
    pageNo: 1,
    pageSize: 20
  }
  assert.equal((await api('/nocode/runtime/dashboard-details', detailsBody)).total, 2)
  await page.goto(runtimePath('page_menu'))
  await amount(page, '9.00')
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  await page.locator('[aria-label="应用内导航"]').getByRole('button', { name: '看板直接入口', exact: true }).click()
  await amount(page, '9.00')
  await page.locator('[aria-label="应用内导航"]').getByRole('button', { name: '参数看板入口', exact: true }).click()
  await expect(page.locator('.application-dashboard-inputs')).toBeVisible()
  await expect(page.locator('.dashboard-tile')).toHaveCount(0)
  await page.getByRole('button', { name: '应用参数', exact: true }).click()
  await expect(page.locator('.application-dashboard-inputs .ant-alert')).toContainText('公司筛选')
  await page.getByRole('checkbox', { name: '匹配空值', exact: true }).check()
  await page.getByRole('button', { name: '应用参数', exact: true }).click()
  await amount(page, '9.00')
  const detailResponse = page.waitForResponse(response => response.url().endsWith('/runtime/dashboard-details'))
  await tile(page, metric.title).getByRole('button', { name: '查看明细', exact: true }).click()
  assert.equal((await (await detailResponse).json()).data.total, 2)
  await expect(page.locator('.ant-drawer-open')).toContainText('共 2 条')
  await expect(page.locator('.ant-drawer-open').getByRole('button', { name: /编辑$/ })).toHaveCount(0)
  await page.locator('.ant-drawer-open .ant-drawer-close').click()
  const download = page.waitForEvent('download')
  await tile(page, metric.title).getByRole('button', { name: '导出 Excel', exact: true }).click()
  const xlsx = resolve(output, 'owner-null.xlsx')
  await (await download).saveAs(xlsx)
  const xml = (await promisify(execFile)('/usr/bin/unzip', ['-p', xlsx, 'xl/worksheets/sheet1.xml'])).stdout
  assert.ok(xml.includes('<t>9.00</t>') && xml.includes('来源记录 2 条') && !xml.includes('<f>'))
  await page.screenshot({ path: resolve(output, 'page-and-parameters.png'), fullPage: true })
  checks.push(
    '独立19.25/应用9.00交集，PAGE与直接MENU均运行六组件；必填空输入和覆盖绑定/伪造pin拒绝，NULL原键9.00、候选仅NULL、2条只读明细、真实XLSX9.00/2条'
  )
  if (verifyBusiness) {
    await step('业务明细真实SQL分页与Chrome入口')
    const body = businessQuery(nullQuery),
      rootAmount = amountField.sourceFieldId
    const ownerRows = await api('/nocode/runtime/page', body)
    assert.equal(ownerRows.total, 2)
    if (verifyDifference) assert.equal(ownerRows.drillTotal, 2)
    assert.deepEqual(
      ownerRows.list.map(row => Number(row.values[rootAmount])).sort((a, b) => a - b),
      [4, 5]
    )
    for (const [pageNo, expected] of [
      [1, 4],
      [2, 5]
    ]) {
      const rows = await api('/nocode/runtime/page', { ...body, pageNo, pageSize: 1, sortFieldId: rootAmount })
      assert.equal(rows.total, 2)
      assert.equal(rows.list.length, 1)
      assert.equal(Number(rows.list[0].values[rootAmount]), expected)
    }
    assert.equal((await api('/nocode/runtime/page', { ...body, search: prefix + '不存在的业务记录' })).total, 0)
    for (const invalid of [
      { ...body, viewId: 'unbound_view' },
      { ...body, objectId: '5914' },
      {
        ...body,
        dashboardDrill: { ...body.dashboardDrill, query: { ...nullQuery, parameters: { company: { values: [] } } } }
      },
      { ...body, dashboardDrill: { ...body.dashboardDrill, query: { ...nullQuery, chartId: table.id } } },
      { ...body, dashboardDrill: { ...body.dashboardDrill, query: { ...nullQuery, applicationId: '0' } } }
    ])
      await rejected('/nocode/runtime/page', invalid, 1050000001)
    await rejected(
      '/nocode/runtime/page',
      { ...body, dashboardDrill: { ...body.dashboardDrill, query: { ...nullQuery, stamp: '0'.repeat(64) } } },
      1050000007
    )
    await rejected('/nocode/runtime/export', body, 1050000001)
    const businessResponse = page.waitForResponse(
      response => response.url().endsWith('/runtime/page') && !!response.request().postDataJSON()?.dashboardDrill
    )
    await tile(page, metric.title).getByRole('button', { name: '业务明细', exact: true }).click()
    const actual = await businessResponse
    assert.equal((await actual.json()).data.total, 2)
    assert.deepEqual(actual.request().postDataJSON().dashboardDrill.query.parameters, { company: { values: [null] } })
    const drawer = page.locator('.ant-drawer-open')
    await expect(drawer).toContainText('验收流水明细视图 · 看板下钻')
    await expect(drawer.locator('tbody tr[data-row-key]')).toHaveCount(2)
    for (const label of [/新建/, /导出/, /导入/, /编辑$/, /删除$/])
      await expect(drawer.getByRole('button', { name: label })).toHaveCount(0)
    await drawer.getByRole('textbox', { name: '搜索业务记录', exact: true }).fill(prefix + '不存在的业务记录')
    const searchResponse = page.waitForResponse(
      response =>
        response.url().endsWith('/runtime/page') && response.request().postDataJSON()?.search?.includes(prefix)
    )
    await drawer.getByRole('button', { name: /查\s*询/ }).click()
    assert.equal((await (await searchResponse).json()).data.total, 0)
    await expect(drawer.locator('tbody tr[data-row-key]')).toHaveCount(0)
    if (verifyDifference)
      await expect(drawer.locator('[data-drill-excluded]')).toContainText('排除了 2 条（下钻范围共 2 条）')
    const resetResponse = page.waitForResponse(
      response => response.url().endsWith('/runtime/page') && !response.request().postDataJSON()?.search
    )
    await drawer.getByRole('button', { name: /重\s*置/ }).click()
    assert.equal((await (await resetResponse).json()).data.total, 2)
    await expect(drawer.locator('tbody tr[data-row-key]')).toHaveCount(2)
    await settledTable(drawer)
    await page.screenshot({ path: resolve(output, 'business-owner.png'), fullPage: true })
    await drawer.locator('.ant-drawer-close').click()
    checks.push(
      'B2固定detailViews真实业务列表金额4/5共2条，SQL范围分页pageSize1仍总数2；搜索追加约束，伪造视图/对象/图表/参数/应用/旧stamp拒绝，业务列表导出拒绝；Chrome参数原快照下钻、搜索/重置保持范围且无写入入口'
    )
  }

  await step('无报表菜单身份、三层导出权限及即时撤权')
  roleId = await api('/system/role/create', {
    name: prefix,
    code: prefix,
    sort: 999,
    status: 0,
    remark: 'P7临时无报表菜单验收身份'
  })
  await checkpoint()
  await api('/system/permission/assign-role-menu', { roleId, menuIds: [] })
  let password = 'T9' + randomBytes(7).toString('hex')
  userId = await api('/system/user/create', {
    username,
    nickname: prefix,
    password,
    remark: 'P7应用固定看板临时最小成员'
  })
  await checkpoint()
  await api('/system/permission/assign-user-role', { userId, roleIds: [roleId] })
  let login = await api('/system/auth/login', { username, password }, null)
  password = undefined
  if (login.loginStatus === 'PASSWORD_CHANGE_REQUIRED')
    login = await api(
      '/system/auth/change-required-password',
      { passwordChangeToken: login.passwordChangeToken, newPassword: 'K8' + randomBytes(7).toString('hex') },
      null,
      'PUT'
    )
  viewerToken = login.accessToken
  login = undefined
  assert.ok(viewerToken)
  const viewer = await api('/system/auth/get-permission-info', undefined, viewerToken)
  assert.deepEqual(viewer.permissions.filter(Boolean), [])
  const viewerPermissions = restrict(permissions, amountField.sourceFieldId, 'gte', '5')
  const dataMember = enabled => ({
    principalKind: 'USER',
    principalId: String(userId),
    objects: exporting(viewerPermissions, enabled)
  })
  const appMember = enabled => ({
    principalKind: 'USER',
    principalId: String(userId),
    objects: exporting(boundedPermissions, enabled)
  })
  const boardMember = enabled => ({
    principalKind: 'USER',
    principalId: String(userId),
    actions: enabled ? ['VIEW', 'EXPORT'] : ['VIEW']
  })
  await memberPolicy('application', appMember(false))
  await memberPolicy('data', dataMember(false))
  await memberPolicy('resource', boardMember(false))
  const viewerModel = await api(modelPath('free_dashboard'), undefined, viewerToken),
    viewerQuery = query(viewerModel)
  const viewerDetails = { ...detailsBody, query: viewerQuery }
  assert.equal(
    Number((await api('/nocode/runtime/dashboard-query', viewerQuery, viewerToken)).totals[metric.metricIds[0]]),
    5
  )
  assert.equal((await api('/nocode/runtime/dashboard-details', viewerDetails, viewerToken)).total, 1)
  for (const path of [
    '/nocode/report/dashboard/published?id=' + release.id,
    '/nocode/report/dashboard/get?id=' + release.id,
    '/nocode/report/dataset/get?id=' + dataset.id
  ])
    await rejected(path, undefined, 403, viewerToken)
  viewerPage = await authenticatedPage(viewerToken, viewer)
  await viewerPage.goto(runtimePath('page_menu'))
  await amount(viewerPage, '5.00')
  if (verifyBusiness) {
    const body = businessQuery(viewerQuery),
      rootAmount = amountField.sourceFieldId
    const rows = await api('/nocode/runtime/page', body, viewerToken)
    assert.equal(rows.total, 1)
    assert.equal(Number(rows.list[0].values[rootAmount]), 5)
    const businessResponse = viewerPage.waitForResponse(
      response => response.url().endsWith('/runtime/page') && !!response.request().postDataJSON()?.dashboardDrill
    )
    await tile(viewerPage, metric.title).getByRole('button', { name: '业务明细', exact: true }).click()
    assert.equal((await (await businessResponse).json()).data.total, 1)
    const drawer = viewerPage.locator('.ant-drawer-open')
    await expect(drawer.locator('tbody tr[data-row-key]')).toHaveCount(1)
    for (const label of [/新建/, /导出/, /导入/, /编辑$/, /删除$/])
      await expect(drawer.getByRole('button', { name: label })).toHaveCount(0)
    await settledTable(drawer)
    await viewerPage.screenshot({ path: resolve(output, 'business-viewer.png'), fullPage: true })
    await drawer.locator('.ant-drawer-close').click()
    await rejected('/nocode/runtime/export', body, 1050000001, viewerToken)
    checks.push(
      'B2无report菜单身份业务下钻SQL交集仅金额5/1条；真实Chrome入口与数据范围一致，无新建/编辑/删除/导入/业务导出入口，API业务导出拒绝'
    )
  }
  async function capability(enabled) {
    const result = await api('/nocode/runtime/dashboard-query', viewerQuery, viewerToken)
    assert.equal(Number(result.totals[metric.metricIds[0]]), 5)
    assert.equal(result.canExport, enabled)
    if (!enabled) await rejected('/nocode/runtime/dashboard-export', viewerQuery, 403, viewerToken)
    await viewerPage.getByRole('button', { name: '刷新数据', exact: true }).click()
    await amount(viewerPage, '5.00')
    const button = tile(viewerPage, metric.title).getByRole('button', { name: '导出 Excel', exact: true })
    if (enabled) await expect(button).toBeEnabled()
    else await expect(button).toBeDisabled()
  }
  await capability(false)
  await memberPolicy('resource', boardMember(true))
  await capability(false)
  await memberPolicy('data', dataMember(true))
  await capability(false)
  await memberPolicy('application', appMember(true))
  await capability(true)
  const viewerDownload = viewerPage.waitForEvent('download')
  await tile(viewerPage, metric.title).getByRole('button', { name: '导出 Excel', exact: true }).click()
  const viewerXlsx = resolve(output, 'viewer-intersection.xlsx')
  await (await viewerDownload).saveAs(viewerXlsx)
  const viewerXml = (await promisify(execFile)('/usr/bin/unzip', ['-p', viewerXlsx, 'xl/worksheets/sheet1.xml'])).stdout
  assert.ok(viewerXml.includes('<t>5.00</t>') && viewerXml.includes('来源记录 1 条') && !viewerXml.includes('<f>'))
  await memberPolicy('data', dataMember(false))
  await capability(false)
  await viewerPage.screenshot({ path: resolve(output, 'viewer-intersection.png'), fullPage: true })
  await memberPolicy('resource', null)
  for (const [path, body] of [
    [modelPath('free_dashboard'), undefined],
    ['/nocode/runtime/dashboard-query', viewerQuery],
    ['/nocode/runtime/dashboard-options', { query: viewerQuery, filterId: companyFilter.id, pageNo: 1, pageSize: 20 }],
    ['/nocode/runtime/dashboard-details', viewerDetails],
    ['/nocode/runtime/dashboard-export', viewerQuery]
  ])
    await rejected(path, body, 403, viewerToken)
  if (verifyBusiness) await rejected('/nocode/runtime/page', businessQuery(viewerQuery), 403, viewerToken)
  await viewerPage.getByRole('button', { name: '刷新数据', exact: true }).click()
  await expect(viewerPage.locator('.application-dashboard .ant-alert')).toContainText(/权限|访问/)
  await expect(viewerPage.locator('.dashboard-tile')).toHaveCount(0)
  await viewerPage.screenshot({ path: resolve(output, 'viewer-revoked.png'), fullPage: true })
  checks.push(
    '无report菜单/权限真实身份仍可应用PAGE取数5.00，应用<=5 ∩ 数据集>=5；1条明细；看板/数据集/应用三层EXPORT缺一拒绝，全有真实5.00/1条Excel，撤数据EXPORT保留READ；撤看板VIEW后五类接口403、UI清空'
  )

  await step('独立新版与应用固定旧版、应用发布stamp失效')
  const currentBoard = await api('/nocode/report/dashboard/get?id=' + release.id)
  const changed = await api('/nocode/report/dashboard/save', {
    id: release.id,
    expectedRevision: currentBoard.revision,
    content: { ...currentBoard.draft, description: prefix + ' 独立看板新版，应用仍固定旧版' }
  })
  const newer = await api('/nocode/report/dashboard/publish', {
    id: release.id,
    expectedRevision: changed.revision,
    requestId: randomUUID()
  })
  assert.ok(newer.versionNo > release.versionNo)
  await rejected('/nocode/report/dashboard/query', independent, 1050000007)
  assert.equal((await api(modelPath('free_dashboard'))).dashboard.versionNo, release.versionNo)
  assert.equal(Number((await api('/nocode/runtime/dashboard-query', freeQuery)).totals[metric.metricIds[0]]), 9)
  await page.goto(origin + '/nocode/report-center/dashboard-view?id=' + release.id)
  await amount(page, '19.25')
  await page.goto(runtimePath('direct_menu'))
  await amount(page, '9.00')
  const currentApp = await api('/nocode/application/get?id=' + fixture.applicationId)
  const updatedApp = await saveApplication(currentApp.draft)
  application = await api('/nocode/application/publish', {
    id: fixture.applicationId,
    expectedRevision: updatedApp.application.revision,
    reason: prefix + ' stamp正式发布更新'
  })
  fixture.applicationVersion = application.application.publishedVersion
  await checkpoint()
  for (const [path, body] of [
    ['query', freeQuery],
    ['options', { query: freeQuery, filterId: companyFilter.id, pageNo: 1, pageSize: 20 }],
    ['details', { ...detailsBody, query: freeQuery }],
    ['export', freeQuery]
  ])
    await rejected('/nocode/runtime/dashboard-' + path, body, 1050000007)
  if (verifyBusiness) await rejected('/nocode/runtime/page', businessQuery(freeQuery), 1050000007)
  await tile(page, metric.title).getByRole('button', { name: '查看明细', exact: true }).click()
  await expect(page.locator('.application-dashboard > .ant-alert')).toContainText('配置已更新')
  await expect(page.locator('.dashboard-tile')).toHaveCount(0)
  await page.getByRole('button', { name: '刷新数据', exact: true }).click()
  await amount(page, '9.00')
  await page.screenshot({ path: resolve(output, 'fixed-old-version.png'), fullPage: true })
  checks.push(
    '自有独立看板发布新版19.25，应用仍固定旧版9.00；应用新发布后旧stamp的query/options/details/export全VERSION_CHANGED，真实UI清空并刷新恢复'
  )
  if (verifyBusiness && verifyDifference) {
    await step('业务VIEW固定条件与下钻总数差异提示')
    const current = await api('/nocode/application/get?id=' + fixture.applicationId)
    originalViewConfig = structuredClone(current.draft.resources.find(resource => resource.id === 'detail_view').config)
    viewScopeChanged = true
    await publishBusinessView(
      {
        ...originalViewConfig,
        query: {
          ...(originalViewConfig.query || {}),
          fixed: [
            ...(originalViewConfig.query?.fixed || []),
            { fieldId: amountField.sourceFieldId, operator: 'gte', value: '5' }
          ]
        }
      },
      '自有业务VIEW金额>=5差异验收'
    )
    const nextModel = await api(modelPath('free_dashboard')),
      nextQuery = query(nextModel)
    const result = await api('/nocode/runtime/page', businessQuery(nextQuery))
    assert.equal(result.drillTotal, 2)
    assert.equal(result.total, 1)
    assert.equal(Number(result.list[0].values[amountField.sourceFieldId]), 5)
    await page.goto(runtimePath('direct_menu'))
    await amount(page, '9.00')
    const response = page.waitForResponse(
      item => item.url().endsWith('/runtime/page') && !!item.request().postDataJSON()?.dashboardDrill
    )
    await tile(page, metric.title).getByRole('button', { name: '业务明细', exact: true }).click()
    const actual = (await (await response).json()).data
    assert.equal(actual.drillTotal, 2)
    assert.equal(actual.total, 1)
    const drawer = page.locator('.ant-drawer-open')
    await expect(drawer.locator('tbody tr[data-row-key]')).toHaveCount(1)
    await expect(drawer.locator('[data-drill-excluded]')).toContainText('排除了 1 条（下钻范围共 2 条）')
    await settledTable(drawer)
    await page.screenshot({ path: resolve(output, 'business-view-difference.png'), fullPage: true })
    await drawer.locator('.ant-drawer-close').click()
    await publishBusinessView(originalViewConfig, '恢复自有业务VIEW原范围')
    viewScopeChanged = false
    const restoredModel = await api(modelPath('free_dashboard'))
    const restored = await api('/nocode/runtime/page', businessQuery(query(restoredModel)))
    assert.equal(restored.total, 2)
    assert.equal(restored.drillTotal, 2)
    checks.push(
      'B2同一授权范围drillTotal2；业务VIEW固定amount>=5时total1且Chrome明确提示排除1条；当前搜索排除2条也提示，验后正式恢复VIEW原配置并发布，2/2范围再次验证'
    )
  }
  assert.deepEqual(errors, [])
} catch (error) {
  failure = stage + ': ' + error.message
  for (const [name, target] of [
    ['failure', page],
    ['viewer-failure', viewerPage]
  ])
    if (target) await target.screenshot({ path: resolve(output, name + '.png'), fullPage: true }).catch(() => {})
} finally {
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  async function clean(label, operation) {
    try {
      await operation()
    } catch (error) {
      cleanupErrors.push(label + ': ' + error.message)
    }
  }
  if (browser) await clean('关闭Chrome', () => browser.close())
  if (token && viewScopeChanged && originalViewConfig)
    await clean('恢复自有业务VIEW原配置并发布', async () => {
      await publishBusinessView(originalViewConfig, '失败后恢复自有业务VIEW原范围')
      viewScopeChanged = false
    })
  if (userId && token) {
    for (const kind of ['resource', 'data', 'application'])
      if (fixture[kind === 'resource' ? 'dashboardId' : kind === 'data' ? 'datasetId' : 'applicationId'])
        await clean('移除自有临时' + kind + '成员', async () => {
          const policy = await memberPolicy(kind, null)
          assert.ok(!policy.members.some(self))
        })
  }
  if (viewerToken) await clean('注销查看会话', () => api('/system/auth/logout', {}, viewerToken))
  viewerToken = undefined
  if (userId && token)
    await clean('删除自有临时账号', async () => {
      assert.equal((await api('/system/user/get?id=' + userId)).username, username)
      await api('/system/user/delete?id=' + userId, undefined, token, 'DELETE')
    })
  if (roleId && token)
    await clean('删除自有临时角色', async () => {
      assert.equal((await api('/system/role/get?id=' + roleId)).code, prefix)
      await api('/system/role/delete?id=' + roleId, undefined, token, 'DELETE')
    })
  if (token && sourceBoard)
    await clean('原资源/发布/授权/登记清单保持原样', async () => {
      const originalBoard = await api('/nocode/report/dashboard/get?id=5'),
        originalDataset = await api('/nocode/report/dataset/get?id=981')
      assert.equal(originalBoard.revision, sourceBoard.revision)
      assert.deepEqual(originalBoard.draft, sourceBoard.draft)
      assert.equal(originalDataset.revision, sourceDataset.revision)
      assert.deepEqual(originalDataset.draft, sourceDataset.draft)
      assert.equal((await api('/nocode/report/dashboard/published?id=5')).checksum, sourceRelease.checksum)
      assert.deepEqual(await api('/nocode/report/dataset/data-policy?id=981'), sourceDataPolicy)
      assert.deepEqual(await api('/nocode/report/dashboard/resource-policy?id=5'), sourceResourcePolicy)
      assert.equal(await readFile('.work/report-demo/current.json', 'utf8'), originalManifest)
    })
  if (token) await clean('注销验收会话', () => api('/system/auth/logout', {}))
  token = undefined
  fixture.temporaryIdentityRemoved = !cleanupErrors.length
  fixture.retainedReason = fixture.applicationVersion
    ? '应用历史固定引用受依赖保护；保留带随机前缀的正式验收样例，不绕过API删除保护。未新增业务记录。'
    : '保留明确前缀的未发布定位样例，可从fixture续跑或按正式API回收；未新增业务记录。'
  await checkpoint()
  const previousResult = await readFile(resolve(output, 'result.json'), 'utf8').catch(() => null)
  if (previousResult) await writeFile(resolve(output, 'prior-result-' + Date.now() + '.json'), previousResult)
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        ...fixture,
        stage,
        userId,
        roleId,
        startedAt,
        finishedAt: new Date().toISOString(),
        checks,
        errors,
        cleanupErrors,
        failure,
        status: !failure && !cleanupErrors.length ? 'PASSED' : 'FAILED',
        boundaries: [
          ...(verifyBusiness ? [] : ['本次显式跳过B2业务视图下钻验证。']),
          ...(verifyBusiness && !verifyDifference ? ['本次未开启B2下钻总数与业务VIEW条件差异提示专项验证。'] : []),
          '本次未覆盖公共筛选defaultValue；由独立默认值专项验收负责。'
        ]
      },
      null,
      2
    )
  )
  await rm(lock, { recursive: true, force: true })
  console.log(
    'P7 application dashboard: ' +
      checks.length +
      ' groups; ' +
      (!failure && !cleanupErrors.length ? 'PASSED' : 'FAILED') +
      '; evidence ' +
      output
  )
}
assert.deepEqual(cleanupErrors, [], '验收身份/成员清理及原资源保护必须完成')
assert.equal(failure, undefined, failure)
