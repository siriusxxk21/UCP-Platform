/** 旧REPORT兼容闭环：自建对象/应用/记录，真实公共筛选、业务编辑、Excel与发布草稿隔离。 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const expect = playwrightExpect.configure({ timeout: 20000 })
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const resumePath = process.env.NOCODE_LEGACY_REPORT_RESUME
const resumed = resumePath ? JSON.parse(await readFile(resolve(resumePath), 'utf8')) : null
if (resumed) {
  assert.match(resumed.prefix, /^fa[a-z0-9]+$/)
  ac.prefix = resumed.prefix
  ac.owned = JSON.parse(await readFile(resolve(dirname(resolve(resumePath)), 'http-result.json'), 'utf8')).owned
}
const output = resumed
  ? dirname(resolve(resumePath))
  : resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/legacy-report-compatibility', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  cleanupErrors = []
const fixture = { ...resumed, prefix: ac.prefix, retained: true, rowIds: resumed?.rowIds || [] }
let browser,
  page,
  object,
  failure,
  stage = '初始化'
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
async function checkpoint() {
  await ac.persist()
  await writeFile(
    resolve(output, 'fixture.json'),
    JSON.stringify({ ...fixture, stage, updatedAt: new Date().toISOString() }, null, 2)
  )
}
async function step(value) {
  stage = value
  await checkpoint()
  console.log('旧REPORT: ' + value)
}
const amount = value =>
  expect
    .poll(async () =>
      Number((await page.locator('.report-block .report-totals strong').textContent())?.replaceAll(',', '').trim())
    )
    .toBe(value)
const formItem = (scope, label) =>
  scope
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + label + '$') }) })
async function selectCompany(label) {
  const filter = page.locator('.dashboard-filters')
  await filter.locator('.ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByText(label, { exact: true }).click()
  const response = page.waitForResponse(item => item.url().endsWith('/runtime/report'))
  await filter.getByRole('button', { name: /^查\s*询$/ }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
async function resetFilter() {
  const response = page.waitForResponse(item => item.url().endsWith('/runtime/report'))
  await page
    .locator('.dashboard-filters')
    .getByRole('button', { name: /^重\s*置$/ })
    .click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
try {
  await ac.login()
  await step('自建前缀对象、旧REPORT应用及3条业务记录')
  object = await ac.object(
    'legacyreport',
    '旧报表兼容 ' + ac.prefix,
    [ac.field('name', 'TEXT', '名称'), ac.field('company', 'SELECT', '公司'), ac.field('amount', 'DECIMAL', '金额')],
    {
      company: ac.option({
        options: [
          { code: 'EAST', label: '华东', disabled: false },
          { code: 'NORTH', label: '华北', disabled: false }
        ]
      })
    }
  )
  fixture.objectId = object.objectId
  assert.equal(object.definition.objectCode, ac.prefix + '_legacyreport', '仅操作登记的自有对象')
  const config = {
    objectId: object.objectId,
    dimensions: [{ fieldId: object.ids.company, relationPath: null, bucket: 'VALUE' }],
    metrics: [{ id: 'amount_sum', name: '金额合计', operation: 'SUM', fieldId: object.ids.amount }],
    equal: {},
    filterFieldIds: [object.ids.company],
    dateFieldId: null,
    timeZone: 'Asia/Shanghai',
    display: 'BAR',
    sortMetricId: null,
    descending: false,
    limit: 20,
    detailViewId: 'ledger_view',
    detailEditable: true
  }
  // 故意省略较新的columnDimensions/pivot/chart属性，复核存量JSON缺省语义。
  const definition = {
    objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
    resources: [
      resource('ledger_form', 'FORM', '兼容流水表单', {
        objectId: object.objectId,
        detailIds: [],
        nodes: ['name', 'company', 'amount'].map(code => ({
          id: 'field_' + code,
          type: 'FIELD',
          fieldId: object.ids[code],
          children: []
        })),
        options: { layout: 'vertical', submitText: '保存记录' }
      }),
      resource('ledger_view', 'VIEW', '兼容流水视图', {
        objectId: object.objectId,
        fieldIds: ['name', 'company', 'amount'].map(code => object.ids[code]),
        equal: {},
        sortFieldId: null,
        descending: false,
        pageSize: 20,
        formId: 'ledger_form'
      }),
      resource('report', 'REPORT', '旧统计', config),
      resource('report_page', 'PAGE', '兼容统计页', {
        protocolVersion: 2,
        contextObjectId: null,
        filters: [
          {
            id: 'company_filter',
            name: '公司公共筛选',
            objectId: object.objectId,
            fieldId: object.ids.company,
            dateRange: false,
            targets: { report: object.ids.company }
          }
        ],
        nodes: [{ id: 'report_node', type: 'REPORT', resourceId: 'report', children: [] }]
      }),
      resource('report_menu', 'MENU', '旧REPORT运行', { targetId: 'report_page' })
    ]
  }
  const existing = fixture.applicationId ? await ac.api('/nocode/application/get?id=' + fixture.applicationId) : null
  if (existing) {
    assert.equal(existing.application.code, ac.prefix + '_legacyreport')
    // 同一自有应用可追加写回验收看板；旧REPORT续跑保留其已登记固定引用。
    const ids = new Set(definition.resources.map(resource => resource.id))
    definition.resources.push(...existing.draft.resources.filter(resource => !ids.has(resource.id)))
  }
  ac.app = await ac.api('/nocode/application/save', {
    id: fixture.applicationId || null,
    expectedRevision: existing?.application.revision ?? null,
    code: ac.prefix + '_legacyreport',
    name: '旧REPORT兼容验收 ' + ac.prefix,
    description: ac.prefix + '；仅自有3条验收流水，可明确识别。',
    definition
  })
  if (!ac.owned.applications.includes(ac.app.application.id)) ac.owned.applications.push(ac.app.application.id)
  fixture.applicationId = ac.app.application.id
  await checkpoint()
  await ac.share(object, ac.grant(object))
  ac.app = await ac.api('/nocode/application/publish', {
    id: fixture.applicationId,
    expectedRevision: ac.app.application.revision,
    reason: ac.prefix + ' 旧REPORT存量JSON正式发布'
  })
  fixture.applicationVersion = ac.app.application.publishedVersion
  const rows = []
  for (const [index, [name, company, value]] of [
    ['华东一', 'EAST', '10'],
    ['华东二', 'EAST', '20'],
    ['华北一', 'NORTH', '5']
  ].entries()) {
    const previous = fixture.rowIds[index] ? (await ac.get(object, { id: fixture.rowIds[index] })).record : null
    if (previous) assert.equal(previous.values[object.ids.name], ac.prefix + name, '只重置自身验收记录')
    const row = await ac.save(object, { name: ac.prefix + name, company, amount: value }, previous)
    rows.push(row)
    fixture.rowIds[index] = row.id
    await checkpoint()
  }
  const baseQuery = { applicationId: fixture.applicationId, reportId: 'report', equal: {} }
  assert.equal(Number((await ac.api('/nocode/runtime/report', baseQuery)).totals.amount_sum), 35)
  checks.push('自有对象与3条10/20/5记录，旧REPORT省略新属性仍正式发布并取总额35，未触及已有对象或记录')
  await step('Chrome公共筛选与真实Excel')
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
  page.setDefaultTimeout(20000)
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
    { token: ac.tokens.admin, info }
  )
  const runtime = origin + '/nocode-app/runtime?id=' + fixture.applicationId
  await page.goto(runtime)
  await amount(35)
  await expect(page.locator('.report-block .report-chart canvas')).toBeVisible()
  let selected = await selectCompany('华东')
  assert.equal(Number(selected.totals.amount_sum), 30)
  assert.equal(selected.recordCount, 2)
  await amount(30)
  const download = page.waitForEvent('download'),
    exportResponse = page.waitForResponse(item => item.url().endsWith('/runtime/report-export'))
  await page
    .locator('.report-block')
    .getByRole('button', { name: /导\s*出/ })
    .click()
  const exported = await exportResponse
  assert.ok(exported.headers()['content-type']?.includes('spreadsheet'))
  assert.equal(exported.request().postDataJSON().equal[object.ids.company], 'EAST')
  const xlsx = resolve(output, 'legacy-east.xlsx')
  await (await download).saveAs(xlsx)
  const xml = (await promisify(execFile)('/usr/bin/unzip', ['-p', xlsx, 'xl/worksheets/sheet1.xml'])).stdout
  assert.match(xml, /<t>30(?:\.0+)?<\/t>/)
  assert.ok(xml.includes('来源记录 2 条') && !xml.includes('<f>'))
  selected = await selectCompany('华北')
  assert.equal(Number(selected.totals.amount_sum), 5)
  assert.equal(selected.recordCount, 1)
  await amount(5)
  await page.screenshot({ path: resolve(output, 'public-filter.png'), fullPage: true })
  checks.push('旧REPORT Chrome公共SELECT原键EAST/NORTH切换总额35→30→5；真实筛选Excel包含30/来源2条且无公式注入')
  await step('旧REPORT业务下钻编辑自有记录并刷新统计')
  const businessResponse = page.waitForResponse(
    item => item.url().endsWith('/runtime/page') && !!item.request().postDataJSON()?.reportDrill
  )
  await page.locator('.report-block').getByText('全部明细', { exact: true }).click()
  const actual = await businessResponse
  assert.equal((await actual.json()).data.total, 1)
  assert.equal(actual.request().postDataJSON().reportDrill.equal[object.ids.company], 'NORTH')
  const business = page.locator('.ant-drawer-open').filter({ hasText: '旧统计 · 全部' })
  const row = business.locator('tr[data-row-key="' + rows[2].id + '"]')
  await row.getByRole('button', { name: /编辑$/ }).click()
  const editor = page.locator('.ant-drawer-open').filter({ hasText: '编辑记录 ·' }).last()
  const amountInput = formItem(editor, '金额').locator('input')
  await expect(amountInput).toHaveCount(1)
  await expect.poll(async () => Number(await amountInput.inputValue())).toBe(5)
  await amountInput.fill('7')
  const saveResponse = page.waitForResponse(
    item => item.url().endsWith('/handling/submit') && item.request().postDataJSON()?.id === rows[2].id
  )
  await editor.getByRole('button', { name: '保存记录', exact: true }).click()
  const saved = await (await saveResponse).json()
  assert.equal(saved.code, 0, saved.msg)
  assert.equal(saved.data.outcome, 'EFFECTIVE')
  assert.equal(Number(saved.data.result.record.values[object.ids.amount]), 7)
  assert.equal(String(saved.data.result.record.values[object.ids.name]), ac.prefix + '华北一')
  await expect(editor).toHaveCount(0)
  await business.locator('.ant-drawer-close').click()
  await amount(7)
  const aggregate = await ac.get(object, rows[2])
  assert.equal(Number(aggregate.record.values[object.ids.amount]), 7)
  assert.equal(Number((await resetFilter()).totals.amount_sum), 37)
  await amount(37)
  await page.screenshot({ path: resolve(output, 'business-edited.png'), fullPage: true })
  checks.push(
    '旧REPORT公共筛选随reportDrill进入既有VIEW/FORM，真实Chrome编辑自有北部记录5→7，服务端回读与统计7/总体37一致'
  )
  await step('未发布草稿与旧发布版本Chrome隔离、正式新发布')
  const before = await ac.api('/nocode/application/get?id=' + fixture.applicationId)
  const updated = structuredClone(before.draft)
  const report = updated.resources.find(resource => resource.id === 'report')
  report.name = '旧统计 · 版本二'
  report.config.metrics[0].name = '新版金额合计'
  ac.app = await ac.api('/nocode/application/save', {
    id: fixture.applicationId,
    expectedRevision: before.application.revision,
    code: before.application.code,
    name: before.application.name,
    description: before.application.description,
    definition: updated
  })
  assert.equal(ac.app.application.publishedVersion, fixture.applicationVersion)
  await page.reload()
  await amount(37)
  await expect(page.locator('.report-block .ant-card-head-title')).toHaveText('旧统计')
  await expect(page.locator('.report-block .report-totals')).toContainText('金额合计 · 总体')
  assert.ok(
    (await ac.api('/nocode/runtime/application?id=' + fixture.applicationId)).definition.resources.find(
      resource => resource.id === 'report'
    ).name === '旧统计'
  )
  checks.push('应用仅保存草稿后，真实浏览器重载仍使用旧发布REPORT名称与字段缺省配置，现有公共筛选与总额37保持可用')
  ac.app = await ac.api('/nocode/application/publish', {
    id: fixture.applicationId,
    expectedRevision: ac.app.application.revision,
    reason: ac.prefix + ' 旧REPORT新配置正式发布'
  })
  fixture.applicationVersion = ac.app.application.publishedVersion
  await page.reload()
  await amount(37)
  await expect(page.locator('.report-block .ant-card-head-title')).toHaveText('旧统计 · 版本二')
  await expect(page.locator('.report-block .report-totals')).toContainText('新版金额合计 · 总体')
  const filtered = await selectCompany('华东')
  assert.equal(Number(filtered.totals.amount_sum), 30)
  await amount(30)
  await page.screenshot({ path: resolve(output, 'published-version.png'), fullPage: true })
  checks.push('新发布后Chrome重载显示新版REPORT名称/指标；历史配置与旧公共筛选契约持续可用，华东仍为30')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = stage + ': ' + error.message
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  if (ac.tokens.admin)
    try {
      await ac.api('/system/auth/logout', {})
    } catch (error) {
      cleanupErrors.push('注销验收会话: ' + error.message)
    }
  ac.tokens = {}
  ac.passwords = {}
  fixture.retainedReason = '自有对象/应用/3条记录保留为明确前缀旧REPORT兼容验收样例；未写入其他业务对象。'
  await checkpoint()
  const result = {
    ...fixture,
    stage,
    checks,
    errors,
    cleanupErrors,
    failure,
    status: !failure && !cleanupErrors.length ? 'PASSED' : 'FAILED'
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log('旧REPORT compatibility: ' + checks.length + ' groups; ' + result.status + '; evidence ' + output)
}
assert.deepEqual(cleanupErrors, [])
assert.equal(failure, undefined, failure)
