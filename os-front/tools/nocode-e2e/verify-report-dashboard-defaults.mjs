/** 默认值验收只修改随机命名的看板副本；实际编辑、发布、清除、重置与复制均走页面。 */
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20000 })
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const prefix = 'rptDefault' + randomBytes(6).toString('hex')
const output = resolve('.work/report-demo/defaults', prefix)
const originalManifest = await readFile('.work/report-demo/current.json', 'utf8')
const manifest = JSON.parse(originalManifest)
assert.equal(String(manifest.dashboardId), '5')
assert.equal(String(manifest.datasetId), '981')
await mkdir(output, { recursive: true })
const checks = [],
  observations = [],
  errors = [],
  cleanupErrors = [],
  fixtures = []
const credentials = Object.fromEntries(
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
let token, browser, page, failure, source, sourceRelease, sourceDataset, sourceDatasetRelease

async function request(path, body) {
  return fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}
async function api(path, body) {
  const response = await (await request(path, body)).json()
  assert.equal(response.code, 0, path + ': ' + response.msg)
  return response.data
}
async function checkpoint() {
  await writeFile(
    resolve(output, 'fixtures.json'),
    JSON.stringify({ prefix, sourceDashboardId: '5', sourceDatasetId: '981', fixtures }, null, 2)
  )
}
async function list(id, search) {
  await page.goto(origin + '/nocode/report-center/dashboards')
  await page.getByPlaceholder('搜索仪表板').fill(search)
  await page.getByRole('button', { name: /^查\s*询$/ }).click()
  await expect(page.locator('tr[data-row-key="' + id + '"]')).toBeVisible()
}
async function copyViaUi(id, search, name) {
  await list(id, search)
  await page
    .locator('tr[data-row-key="' + id + '"]')
    .getByRole('button', { name: /^更\s*多$/ })
    .click()
  await page.getByRole('menuitem', { name: /^复\s*制$/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '副本名称', exact: true }).fill(name)
  await dialog.getByRole('textbox', { name: '仪表板操作原因', exact: true }).fill(prefix + ' 默认值实际验收')
  const response = page.waitForResponse(value => value.url().endsWith('/dashboard/copy'))
  await dialog.getByRole('button', { name: '创建副本', exact: true }).click()
  const copied = await (await response).json()
  assert.equal(copied.code, 0, copied.msg)
  assert.equal(copied.data.publishedVersion, null)
  fixtures.push({ id: copied.data.id, name, published: false, removed: false })
  await checkpoint()
  await expect(dialog).not.toBeVisible()
  return copied.data
}
function metric(chart) {
  return page
    .locator('.dashboard-tile, .dashboard-editor-tile')
    .filter({ has: page.getByRole('heading', { name: chart.title, exact: true }) })
    .locator('.dashboard-metrics strong')
}
async function amount(chart, expected) {
  await expect(metric(chart)).toHaveText(expected)
}
function metricResponse(id, chartId) {
  const response = page.waitForResponse(value => {
    if (!value.url().endsWith('/dashboard/query') || value.request().method() !== 'POST') return false
    const query = value.request().postDataJSON()
    return query.id === id && query.chartId === chartId && query.preview === false
  })
  void response.catch(() => {})
  return response
}
async function observed(response, chart, expected, step) {
  const returned = await response,
    body = await returned.json(),
    query = returned.request().postDataJSON()
  assert.equal(body.code, 0, body.msg)
  assert.equal(Number(body.data.totals[chart.metricIds[0]]), Number(expected))
  await amount(chart, expected)
  observations.push({ step, dashboardId: query.id, total: expected, filterValues: query.filterValues })
  return query
}
async function chooseMapping(dialog, label, field) {
  await dialog.locator('[aria-label="' + label + '"] .ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByTitle(field, { exact: true }).click()
  await dialog.getByRole('textbox', { name: '筛选名称', exact: true }).click()
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
}
async function openDefaults() {
  await page.getByRole('button', { name: '公共筛选', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('公共筛选配置')
  await expect(dialog.getByRole('button', { name: '添加公共筛选', exact: true })).toBeVisible()
  return dialog
}
async function assertConfiguredDefault(dialog) {
  await expect(dialog.getByRole('switch', { name: '默认公司启用默认值', exact: true })).toHaveAttribute(
    'aria-checked',
    'true'
  )
  await expect(dialog.getByRole('checkbox', { name: '匹配空值', exact: true })).toBeChecked()
  await expect(dialog.getByRole('textbox', { name: '默认公司默认值', exact: true })).toBeDisabled()
}
async function publishViaUi(board) {
  const chart = board.draft.charts.find(value => value.display === 'METRIC')
  assert.ok(chart)
  const query = metricResponse(board.id, chart.id)
  const response = page.waitForResponse(value => value.url().endsWith('/dashboard/publish'))
  await page.getByRole('button', { name: '发布并打开', exact: true }).click()
  const published = await (await response).json()
  assert.equal(published.code, 0, published.msg)
  fixtures.find(fixture => fixture.id === board.id).published = true
  await checkpoint()
  await expect(page).toHaveURL(/dashboard-view/)
  const initial = await observed(query, chart, '9.00', '首次打开')
  const filter = published.data.content.filters.find(value => value.name === '默认公司')
  assert.ok(filter)
  assert.deepEqual(filter.defaultValue.values, [null])
  assert.deepEqual(initial.filterValues.find(value => value.filterId === filter.id).values, [null])
  await expect(page.locator('[aria-label="默认公司"] .ant-select-selection-item')).toHaveText('空值')
  return { release: published.data, chart, filter }
}
async function sourcePreserved() {
  if (!source) return
  assert.deepEqual(await api('/nocode/report/dashboard/get?id=5'), source)
  assert.deepEqual(await api('/nocode/report/dashboard/published?id=5'), sourceRelease)
  assert.deepEqual(await api('/nocode/report/dataset/get?id=981'), sourceDataset)
  assert.deepEqual(await api('/nocode/report/dataset/releases?id=981&pageNo=1&pageSize=100'), sourceDatasetRelease)
  assert.equal(await readFile('.work/report-demo/current.json', 'utf8'), originalManifest)
}

try {
  token = (await api('/system/auth/login', { username: credentials.E2E_USERNAME, password: credentials.E2E_PASSWORD }))
    .accessToken
  delete credentials.E2E_USERNAME
  delete credentials.E2E_PASSWORD
  assert.ok(token)
  const info = await api('/system/auth/get-permission-info')
  source = await api('/nocode/report/dashboard/get?id=5')
  sourceRelease = await api('/nocode/report/dashboard/published?id=5')
  sourceDataset = await api('/nocode/report/dataset/get?id=981')
  sourceDatasetRelease = await api('/nocode/report/dataset/releases?id=981&pageNo=1&pageSize=100')
  assert.equal(source.draft.name, '经营分析 · 核心体验看板')
  assert.equal(String(source.ownerId), String(info.user.id))
  assert.ok(source.draft.charts.every(chart => chart.dataset.id === '981'))
  assert.ok(sourceDataset.draft.source.fields.some(field => field.name === '公司' && field.role === 'DIMENSION'))
  browser = await chromium.launch({
    channel: 'chrome',
    headless: process.env.NOCODE_VERIFY_HEADLESS === '1',
    args: ['--disable-dev-shm-usage']
  })
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
      document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style')
        style.textContent = '*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important}'
        document.head.appendChild(style)
      })
    },
    { token, info }
  )
  page = await context.newPage()
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  let board = await copyViaUi('5', source.draft.name, prefix)
  const firstChart = board.draft.charts.find(chart => chart.display === 'METRIC')
  assert.ok(firstChart)
  assert.ok(board.draft.charts.every(chart => !source.draft.charts.some(original => original.id === chart.id)))
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + board.id)
  await amount(firstChart, '19.25')
  let dialog = await openDefaults()
  while (await dialog.getByRole('button', { name: /^移除筛选\d+$/ }).count())
    await dialog
      .getByRole('button', { name: /^移除筛选\d+$/ })
      .last()
      .click()
  await dialog.getByRole('button', { name: '添加公共筛选', exact: true }).click()
  await dialog.getByRole('textbox', { name: '筛选名称', exact: true }).fill('默认公司')
  await dialog.getByRole('switch', { name: '默认公司启用默认值', exact: true }).click()
  await dialog.getByRole('button', { name: '应用公共筛选', exact: true }).click()
  await expect(dialog.getByRole('alert').filter({ hasText: '请填写「默认公司」的默认值，或关闭默认值' })).toBeVisible()
  await dialog.getByRole('checkbox', { name: '匹配空值', exact: true }).check()
  for (const chart of board.draft.charts) await chooseMapping(dialog, '默认公司映射' + chart.title, '公司（维度）')
  await assertConfiguredDefault(dialog)
  await dialog.getByRole('button', { name: '应用公共筛选', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  const saveResponse = page.waitForResponse(value => value.url().endsWith('/dashboard/save'))
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  const saved = await (await saveResponse).json()
  assert.equal(saved.code, 0, saved.msg)
  board = saved.data
  assert.deepEqual(board.draft.filters[0].defaultValue.values, [null])
  assert.equal(board.draft.filters[0].mappings.length, board.draft.charts.length)
  await amount(firstChart, '19.25')
  await page.reload()
  await amount(firstChart, '19.25')
  dialog = await openDefaults()
  await assertConfiguredDefault(dialog)
  await page.screenshot({ path: resolve(output, 'default-editor.png'), fullPage: true })
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  checks.push('实际编辑器启用默认值拒绝空配置；配置显式NULL与全部图表字段映射，保存重开保持开关及NULL选择')
  const { release, chart, filter } = await publishViaUi(board)
  await page.screenshot({ path: resolve(output, 'initial-null.png'), fullPage: true })
  let response = metricResponse(board.id, chart.id)
  const select = page.locator('[aria-label="默认公司"]')
  await select.hover()
  await select.locator('.ant-select-clear').click()
  let query = await observed(response, chart, '19.25', '清空默认条件')
  assert.deepEqual(query.filterValues.find(value => value.filterId === filter.id).values, [])
  await expect(select.locator('.ant-select-selection-placeholder')).toHaveText('全部')
  await page.screenshot({ path: resolve(output, 'cleared.png'), fullPage: true })
  response = metricResponse(board.id, chart.id)
  await page.getByRole('button', { name: '重置筛选', exact: true }).click()
  query = await observed(response, chart, '9.00', '重置恢复默认条件')
  assert.deepEqual(query.filterValues.find(value => value.filterId === filter.id).values, [null])
  await expect(select.locator('.ant-select-selection-item')).toHaveText('空值')
  checks.push('实际发布初始NULL条件9.00；清空产生values=[]恢复19.25；重置恢复NULL条件9.00')
  response = metricResponse(board.id, chart.id)
  await page.reload()
  await observed(response, chart, '9.00', '重开恢复默认条件')
  const identity = {
    id: board.id,
    chartId: chart.id,
    preview: false,
    versionNo: release.versionNo,
    checksum: release.checksum
  }
  for (const [filterValues, expected] of [
    [undefined, 19.25],
    [[], 19.25],
    [[{ filterId: filter.id, values: [null] }], 9]
  ]) {
    const result = await api('/nocode/report/dashboard/query', { ...identity, filterValues })
    assert.equal(Number(result.totals[chart.metricIds[0]]), expected)
  }
  checks.push('真实重开恢复默认值；独立API缺省或空筛选均19.25，显式NULL才为9.00，服务端未隐式施加默认值')
  const copied = await copyViaUi(board.id, prefix, prefix + '副本')
  assert.deepEqual(copied.draft.filters[0].defaultValue, release.content.filters[0].defaultValue)
  assert.notEqual(copied.draft.filters[0].id, filter.id)
  assert.ok(copied.draft.charts.every(value => !board.draft.charts.some(original => original.id === value.id)))
  for (const mapping of copied.draft.filters[0].mappings)
    assert.ok(copied.draft.charts.some(value => value.id === mapping.chartId))
  assert.deepEqual(
    copied.draft.charts.map(value => value.dataset),
    board.draft.charts.map(value => value.dataset)
  )
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + copied.id)
  await expect(page.getByRole('heading', { name: '仪表板设计', exact: true })).toBeVisible()
  dialog = await openDefaults()
  await assertConfiguredDefault(dialog)
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await publishViaUi(copied)
  await page.screenshot({ path: resolve(output, 'copied-default.png'), fullPage: true })
  checks.push('实际列表复制创建第二自有草稿，新组件与筛选ID重建、固定数据集pin和NULL默认值保留；编辑器重开及发布仍9.00')
  await sourcePreserved()
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error.message
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  delete credentials.E2E_USERNAME
  delete credentials.E2E_PASSWORD
  async function clean(label, action) {
    try {
      await action()
    } catch (error) {
      cleanupErrors.push(label + ': ' + error.message)
    }
  }
  if (browser) await clean('关闭浏览器', () => browser.close())
  for (const fixture of [...fixtures].reverse())
    if (!fixture.removed)
      await clean('精确删除自有看板 ' + fixture.id, async () => {
        assert.ok(fixture.name === prefix || fixture.name === prefix + '副本')
        assert.notEqual(fixture.id, '5')
        const current = await api('/nocode/report/dashboard/get?id=' + fixture.id)
        assert.equal(current.draft.name, fixture.name)
        const preview = await api('/nocode/report/dashboard/delete-preview?id=' + fixture.id)
        assert.equal(preview.canDelete, true)
        await api('/nocode/report/dashboard/delete', {
          id: fixture.id,
          expectedRevision: current.revision,
          reason: prefix + ' 精确清理默认值验收副本'
        })
        fixture.removed = true
        await checkpoint()
      })
  if (token) {
    await clean('复核来源和体验登记保持不变', sourcePreserved)
    await clean('注销验证会话', () => api('/system/auth/logout', {}))
  }
  token = undefined
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        prefix,
        fixtures,
        checks,
        observations,
        errors,
        cleanupErrors,
        failure,
        status: !failure && !cleanupErrors.length ? 'PASSED' : 'FAILED'
      },
      null,
      2
    )
  )
  console.log(
    'Dashboard defaults: ' +
      checks.length +
      ' groups; ' +
      (!failure && !cleanupErrors.length ? 'PASSED' : 'FAILED') +
      '; ' +
      output
  )
}
assert.deepEqual(cleanupErrors, [])
assert.equal(failure, undefined, failure)
