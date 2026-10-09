/** 新看板真实业务编辑闭环：仅复用旧REPORT验收登记的自有对象/应用/记录。 */
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const expect = playwrightExpect.configure({ timeout: 20000 })
assert.ok(process.env.NOCODE_LEGACY_REPORT_FIXTURE, '需要明确指定自有旧REPORT fixture.json')
const legacyPath = resolve(process.env.NOCODE_LEGACY_REPORT_FIXTURE)
const legacy = JSON.parse(await readFile(legacyPath, 'utf8'))
assert.match(legacy.prefix, /^fa[a-z0-9]+$/)
assert.equal(legacy.rowIds.length, 3)
const output = resolve(dirname(legacyPath), 'dashboard-writeback')
await mkdir(output, { recursive: true })
const fixturePath = resolve(output, 'fixture.json')
const fixture = JSON.parse(await readFile(fixturePath, 'utf8').catch(() => '{}'))
Object.assign(fixture, {
  prefix: legacy.prefix,
  objectId: legacy.objectId,
  applicationId: legacy.applicationId,
  rowIds: legacy.rowIds,
  retained: true,
  retainedReason: '复用明确前缀旧REPORT的自有对象/应用/3条记录；新增自有固定数据集/看板并保留正式验收样例。'
})
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
ac.prefix = legacy.prefix
ac.owned = JSON.parse(await readFile(resolve(dirname(legacyPath), 'http-result.json'), 'utf8')).owned
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  cleanupErrors = []
let browser,
  page,
  standalone,
  object,
  failure,
  stage = '初始化'
async function checkpoint() {
  await ac.persist()
  await writeFile(fixturePath, JSON.stringify({ ...fixture, stage, updatedAt: new Date().toISOString() }, null, 2))
}
async function step(value) {
  stage = value
  await checkpoint()
  console.log('看板写回: ' + value)
}
const tile = target =>
  target.locator('.dashboard-tile').filter({ has: target.getByRole('heading', { name: '自有金额概览', exact: true }) })
const amount = (target, value) =>
  expect
    .poll(async () =>
      Number(
        (await tile(target).locator('.dashboard-metrics strong').first().textContent())?.replaceAll(',', '').trim()
      )
    )
    .toBe(value)
const formItem = (scope, label) =>
  scope
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + label + '$') }) })
async function settledDashboard(target, total, northAmount) {
  await amount(target, total)
  await expect(target.locator('.dashboard-tile')).toHaveCount(2)
  await expect(
    target.locator(
      '.dashboard-page .ant-skeleton, .dashboard-page .ant-alert-error, .dashboard-page .ant-alert-warning'
    )
  ).toHaveCount(0)
  const table = target
    .locator('.dashboard-tile')
    .filter({ has: target.getByRole('heading', { name: '自有公司金额', exact: true }) })
  await expect(table.locator('tbody tr[data-row-key]')).toHaveCount(2)
  await expect
    .poll(async () =>
      (await table.locator('tbody .ant-btn-link').allTextContents())
        .map(value => Number(value.replaceAll(',', '').trim()))
        .sort((a, b) => a - b)
    )
    .toEqual([total - northAmount, northAmount].sort((a, b) => a - b))
  await expect(target.locator('.ant-spin-spinning, .ant-spin-blur, .ant-btn-loading')).toHaveCount(0)
  await expect
    .poll(() =>
      target
        .locator('.ant-spin-container')
        .evaluateAll(nodes => nodes.every(node => Number(getComputedStyle(node).opacity) === 1))
    )
    .toBe(true)
}
async function initPage(context) {
  const result = await context.newPage()
  result.setDefaultTimeout(20000)
  result.on('pageerror', error => errors.push(error.message))
  return result
}
const lock = resolve(output, '.running')
await mkdir(lock)
try {
  await ac.login()
  await step('确认自有对象/应用/记录与新数据集固定发布')
  object = await ac.api('/nocode/application/object-version?id=' + fixture.objectId)
  object.ids = Object.fromEntries(object.definition.fields.map(field => [field.code, field.id]))
  assert.equal(object.definition.objectCode, fixture.prefix + '_legacyreport')
  assert.equal(ac.owned.objects.find(item => item.id === fixture.objectId)?.code, object.definition.objectCode)
  ac.app = await ac.api('/nocode/application/get?id=' + fixture.applicationId)
  assert.equal(ac.app.application.code, fixture.prefix + '_legacyreport')
  assert.ok(ac.owned.applications.includes(fixture.applicationId))
  const rows = []
  for (const [index, name] of ['华东一', '华东二', '华北一'].entries()) {
    const row = (await ac.get(object, { id: fixture.rowIds[index] })).record
    assert.equal(row.values[object.ids.name], fixture.prefix + name)
    assert.equal(row.values[object.ids.company], index < 2 ? 'EAST' : 'NORTH')
    rows.push(row)
  }
  const before = rows[2],
    baseline = rows.reduce((sum, row) => sum + Number(row.values[object.ids.amount]), 0),
    next = Number(before.values[object.ids.amount]) + 2
  fixture.before = {
    recordId: before.id,
    revision: before.revision,
    value: before.values[object.ids.amount],
    total: baseline
  }
  await checkpoint()
  const info = await ac.api('/system/auth/get-permission-info')
  const readGrant = ac.grant(object, {
    actions: ['READ', 'EXPORT'],
    writeFields: [],
    writeDetails: [],
    writeRelations: []
  })
  if (!fixture.datasetId) {
    const dataset = await ac.api('/nocode/report/dataset/save', {
      id: null,
      expectedRevision: 0,
      name: fixture.prefix + ' 自有编辑数据集',
      description: fixture.prefix + '；仅自有3条记录，用于真实看板业务编辑写回验收。',
      source: {
        schemaVersion: 1,
        root: { objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum },
        relations: [],
        fields: [
          { id: 'field_company', path: [], sourceFieldId: object.ids.company, name: '公司', role: 'DIMENSION' },
          { id: 'field_amount', path: [], sourceFieldId: object.ids.amount, name: '金额', role: 'MEASURE' }
        ]
      },
      analysis: {
        schemaVersion: 1,
        metrics: [{ id: 'metric_amount', name: '金额合计', operation: 'SUM', fieldId: 'field_amount' }],
        fixedConditions: null,
        fieldFormats: {},
        timeZone: 'Asia/Shanghai'
      }
    })
    fixture.datasetId = dataset.id
    await checkpoint()
  }
  const dataset = await ac.api('/nocode/report/dataset/get?id=' + fixture.datasetId)
  assert.equal(dataset.draft.name, fixture.prefix + ' 自有编辑数据集')
  assert.equal(dataset.draft.source.root.objectId, object.objectId)
  const ceilings = await ac.api('/nocode/report/dataset/ceilings?id=' + dataset.id)
  await ac.api('/nocode/report/dataset/ceiling', {
    datasetId: dataset.id,
    objectId: object.objectId,
    expectedRevision: ceilings.find(item => item.objectId === object.objectId)?.revision || 0,
    permission: readGrant,
    reason: fixture.prefix + ' 自有记录只读数据集上限'
  })
  const policy = await ac.api('/nocode/report/dataset/data-policy?id=' + dataset.id)
  await ac.api('/nocode/report/dataset/data-policy', {
    datasetId: dataset.id,
    expectedRevision: policy.revision,
    members: [
      ...policy.members.filter(
        member => !(member.principalKind === 'USER' && member.principalId === String(info.user.id))
      ),
      ac.member(info.user.id, [readGrant])
    ],
    reason: fixture.prefix + ' 自有记录读取/导出'
  })
  if (!fixture.datasetReference) {
    const released = await ac.api('/nocode/report/dataset/publish', {
      id: dataset.id,
      expectedRevision: dataset.revision,
      requestId: randomUUID(),
      reason: fixture.prefix + ' 自有编辑固定版'
    })
    fixture.datasetReference = { id: dataset.id, versionNo: released.versionNo, checksum: released.checksum }
    await checkpoint()
  }
  if (!fixture.dashboardId) {
    const content = {
      schemaVersion: 1,
      name: fixture.prefix + ' 自有业务编辑看板',
      description: fixture.prefix + '；已授权可写业务视图编辑自有记录，独立/嵌入共用固定发布。',
      filters: [],
      charts: [
        {
          id: 'write_metric',
          title: '自有金额概览',
          display: 'METRIC',
          dataset: fixture.datasetReference,
          dimensions: [],
          metricIds: ['metric_amount'],
          x: 0,
          y: 0,
          w: 12,
          h: 4,
          drillDimensions: [],
          links: []
        },
        {
          id: 'write_table',
          title: '自有公司金额',
          display: 'TABLE',
          dataset: fixture.datasetReference,
          dimensions: [{ fieldId: 'field_company', bucket: 'VALUE' }],
          metricIds: ['metric_amount'],
          x: 0,
          y: 4,
          w: 12,
          h: 5,
          drillDimensions: [],
          links: []
        }
      ]
    }
    const board = await ac.api('/nocode/report/dashboard/save', { id: null, expectedRevision: 0, content })
    fixture.dashboardId = board.id
    await checkpoint()
  }
  let board = await ac.api('/nocode/report/dashboard/get?id=' + fixture.dashboardId)
  assert.equal(board.draft.name, fixture.prefix + ' 自有业务编辑看板')
  assert.equal(board.draft.charts.length, 2)
  assert.ok(board.draft.charts.every(chart => chart.dataset.id === fixture.datasetId))
  const layouts = { write_metric: { x: 0, y: 0, w: 12, h: 4 }, write_table: { x: 0, y: 4, w: 12, h: 5 } }
  if (
    board.draft.charts.some(chart => Object.entries(layouts[chart.id]).some(([key, value]) => chart[key] !== value))
  ) {
    const content = structuredClone(board.draft)
    for (const chart of content.charts) Object.assign(chart, layouts[chart.id])
    board = await ac.api('/nocode/report/dashboard/save', { id: board.id, expectedRevision: board.revision, content })
    fixture.dashboardReference = undefined
    await checkpoint()
  }
  if (!fixture.dashboardReference) {
    const released = await ac.api('/nocode/report/dashboard/publish', {
      id: board.id,
      expectedRevision: board.revision,
      requestId: randomUUID()
    })
    fixture.dashboardReference = { id: released.id, versionNo: released.versionNo, checksum: released.checksum }
    await checkpoint()
  }
  await step('应用追加固定看板与既有可写VIEW正式发布')
  const app = await ac.api('/nocode/application/get?id=' + fixture.applicationId),
    definition = structuredClone(app.draft)
  const view = definition.resources.find(resource => resource.id === 'ledger_view')
  assert.equal(view.kind, 'VIEW')
  assert.equal(view.config.objectId, fixture.objectId)
  assert.equal(view.config.formId, 'ledger_form')
  definition.resources = definition.resources.filter(
    resource => !['write_dashboard', 'write_dashboard_menu'].includes(resource.id)
  )
  definition.resources.push(
    {
      id: 'write_dashboard',
      code: 'write_dashboard',
      kind: 'REPORT_DASHBOARD',
      name: '自有写回看板',
      config: {
        dashboard: fixture.dashboardReference,
        inputBindings: [],
        detailViews: ['write_metric', 'write_table'].map(chartId => ({ chartId, viewId: 'ledger_view' }))
      }
    },
    {
      id: 'write_dashboard_menu',
      code: 'write_dashboard_menu',
      kind: 'MENU',
      name: '自有写回验收',
      config: { targetId: 'write_dashboard' }
    }
  )
  ac.app = await ac.api('/nocode/application/save', {
    id: fixture.applicationId,
    expectedRevision: app.application.revision,
    code: app.application.code,
    name: app.application.name,
    description: app.application.description,
    definition
  })
  const shares = await ac.api('/nocode/object-sharing/list?objectId=' + object.objectId)
  const permission = shares.find(item => item.applicationId === fixture.applicationId)?.permission
  assert.ok(
    permission.actions.includes('UPDATE') && permission.writeFields.includes(object.ids.amount),
    '既有应用写权限必须存在，不由看板赋予'
  )
  ac.app = await ac.api('/nocode/application/publish', {
    id: fixture.applicationId,
    expectedRevision: ac.app.application.revision,
    reason: fixture.prefix + ' 正式固定可写明细验收'
  })
  fixture.applicationVersion = ac.app.application.publishedVersion
  await checkpoint()
  const model = await ac.api(
    '/nocode/runtime/dashboard?applicationId=' + fixture.applicationId + '&resourceId=write_dashboard'
  )
  assert.deepEqual(
    { id: model.dashboard.id, versionNo: model.dashboard.versionNo, checksum: model.dashboard.checksum },
    fixture.dashboardReference
  )
  const embeddedQuery = {
    applicationId: fixture.applicationId,
    resourceId: 'write_dashboard',
    chartId: 'write_metric',
    stamp: model.stamp
  }
  const standaloneQuery = { ...fixture.dashboardReference, preview: false, chartId: 'write_metric' }
  assert.equal(Number((await ac.api('/nocode/runtime/dashboard-query', embeddedQuery)).totals.metric_amount), baseline)
  assert.equal(Number((await ac.api('/nocode/report/dashboard/query', standaloneQuery)).totals.metric_amount), baseline)
  checks.push(
    '同一自有对象/3条记录，READ/EXPORT数据集上限，应用保留既有UPDATE字段权；正式固定单METRIC/TABLE看板，独立/嵌入金额一致'
  )
  await step('Chrome业务下钻真实编辑与父看板自动刷新')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
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
    { token: ac.tokens.admin, info }
  )
  page = await initPage(context)
  standalone = await initPage(context)
  await standalone.goto(origin + '/nocode/report-center/dashboard-view?id=' + fixture.dashboardId)
  await amount(standalone, baseline)
  await page.goto(origin + '/nocode-app/runtime?id=' + fixture.applicationId + '&menu=write_dashboard_menu')
  await amount(page, baseline)
  const businessResponse = page.waitForResponse(
    response => response.url().endsWith('/runtime/page') && !!response.request().postDataJSON()?.dashboardDrill
  )
  await tile(page).getByRole('button', { name: '业务明细', exact: true }).click()
  const businessRequest = await businessResponse,
    result = await businessRequest.json()
  assert.equal(result.code, 0, result.msg)
  assert.equal(result.data.total, 3)
  assert.equal(result.data.drillTotal, 3)
  assert.equal(businessRequest.request().postDataJSON().dashboardDrill.query.stamp, model.stamp)
  const business = page.locator('.ant-drawer-open').filter({ hasText: '兼容流水视图 · 看板下钻' })
  await expect(business.locator('tbody tr[data-row-key]')).toHaveCount(3)
  await expect(business.locator('.ant-spin-spinning')).toHaveCount(0)
  await expect(business.getByRole('button', { name: /导出/ })).toHaveCount(0)
  await business
    .locator('tr[data-row-key="' + before.id + '"]')
    .getByRole('button', { name: /编辑$/ })
    .click()
  const editor = page.locator('.ant-drawer-open').filter({ hasText: '编辑记录 ·' }).last()
  const input = formItem(editor, '金额').locator('input')
  await expect.poll(async () => Number(await input.inputValue())).toBe(Number(before.values[object.ids.amount]))
  await input.fill(String(next))
  const startedAt = new Date(Date.now() - 1000).toISOString()
  const submitResponse = page.waitForResponse(
    response => response.url().endsWith('/handling/submit') && response.request().postDataJSON()?.id === before.id
  )
  const refreshedResponse = page.waitForResponse(
    response =>
      response.url().endsWith('/runtime/dashboard-query') &&
      response.request().postDataJSON()?.chartId === 'write_metric' &&
      response.request().postDataJSON()?.resourceId === 'write_dashboard'
  )
  await editor.getByRole('button', { name: '保存记录', exact: true }).click()
  const submitted = await submitResponse,
    saved = await submitted.json()
  assert.equal(saved.code, 0, saved.msg)
  assert.equal(submitted.request().postDataJSON().expectedRevision, before.revision)
  assert.equal(saved.data.outcome, 'EFFECTIVE')
  const after = saved.data.result.record
  assert.equal(after.id, before.id)
  assert.equal(Number(after.values[object.ids.amount]), next)
  assert.equal(typeof after.revision, 'string')
  assert.notEqual(after.revision, before.revision, '真实写回须产生新的并发修订标识')
  const refreshed = await (await refreshedResponse).json()
  assert.equal(refreshed.code, 0, refreshed.msg)
  assert.equal(Number(refreshed.data.totals.metric_amount), baseline + 2)
  await expect(editor).toHaveCount(0)
  await expect(business).toHaveCount(0)
  await settledDashboard(page, baseline + 2, next)
  await expect(page.locator('.ant-drawer-mask:visible')).toHaveCount(0)
  await page.screenshot({ path: resolve(output, 'embedded-after-write.png'), fullPage: true, animations: 'disabled' })
  const reloaded = (await ac.get(object, before)).record
  assert.equal(reloaded.revision, after.revision)
  assert.equal(Number(reloaded.values[object.ids.amount]), next)
  fixture.after = {
    recordId: after.id,
    revision: after.revision,
    value: after.values[object.ids.amount],
    total: baseline + 2
  }
  await checkpoint()
  checks.push(
    '真实Chrome经新看板businessDrill编辑登记的北部记录，handling提交原revision并正式生成新revision，父嵌入看板自动查询并刷新总额+2'
  )
  await step('独立/嵌入/旧REPORT一致与正式更新历史')
  const standaloneResponse = standalone.waitForResponse(
    response =>
      response.url().endsWith('/dashboard/query') && response.request().postDataJSON()?.chartId === 'write_metric'
  )
  await standalone.getByRole('button', { name: '刷新数据', exact: true }).click()
  assert.equal(Number((await (await standaloneResponse).json()).data.totals.metric_amount), baseline + 2)
  await settledDashboard(standalone, baseline + 2, next)
  await standalone.screenshot({
    path: resolve(output, 'standalone-after-write.png'),
    fullPage: true,
    animations: 'disabled'
  })
  assert.equal(
    Number((await ac.api('/nocode/runtime/dashboard-query', embeddedQuery)).totals.metric_amount),
    baseline + 2
  )
  assert.equal(
    Number((await ac.api('/nocode/report/dashboard/query', standaloneQuery)).totals.metric_amount),
    baseline + 2
  )
  assert.equal(
    Number(
      (await ac.api('/nocode/runtime/report', { applicationId: fixture.applicationId, reportId: 'report', equal: {} }))
        .totals.amount_sum
    ),
    baseline + 2
  )
  const query = { start: startedAt, applicationId: fixture.applicationId }
  const summary = await ac.api('/nocode/record-history/query', query)
  const history = await ac.api('/nocode/record-history/detail', {
    query: { ...query, end: summary.end },
    visibility: summary.visibility,
    objectId: fixture.objectId,
    recordId: before.id
  })
  const updated = history.row.changes.find(
    change =>
      change.operation === 'UPDATE' &&
      Number(change.before?.[object.ids.amount]) === Number(before.values[object.ids.amount]) &&
      Number(change.after?.[object.ids.amount]) === next
  )
  assert.ok(updated, '业务写回必须留下真实UPDATE前后金额历史')
  assert.equal(updated.employeeId, String(info.user.id))
  fixture.audit = {
    id: updated.id,
    operation: updated.operation,
    time: updated.time,
    employeeId: updated.employeeId,
    source: updated.source ?? null,
    before: updated.before[object.ids.amount],
    after: updated.after[object.ids.amount]
  }
  await writeFile(
    resolve(output, 'write-history.json'),
    JSON.stringify(
      { recordId: before.id, revisionBefore: before.revision, revisionAfter: after.revision, audit: fixture.audit },
      null,
      2
    )
  )
  checks.push(
    '独立看板Chrome刷新、应用固定看板API、旧REPORT总额全部一致；正式record-history记录同操作者UPDATE前后金额、并发修订前后标识，无共享业务数据写入'
  )
} catch (error) {
  failure = error
  errors.push(error.message)
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close().catch(error => cleanupErrors.push(error.message))
  if (ac.tokens.admin) await ac.api('/system/auth/logout', {}).catch(error => cleanupErrors.push(error.message))
  ac.tokens = {}
  ac.passwords = {}
  await checkpoint()
  await rm(lock, { recursive: true, force: true })
  const result = {
    ...fixture,
    stage,
    checks,
    errors,
    cleanupErrors,
    finishedAt: new Date().toISOString(),
    status: failure || errors.length || cleanupErrors.length ? 'FAILED' : 'PASSED'
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log('Dashboard business writeback: ' + checks.length + ' groups; ' + result.status + '; evidence ' + output)
  if (failure || errors.length || cleanupErrors.length) process.exitCode = 1
}
