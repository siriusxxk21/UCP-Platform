/** 类型切换、跨数据集筛选和窄屏边界；仅修改并精确清理随机命名的看板副本。 */
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20000 })
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const prefix = 'rptBoundary' + randomBytes(6).toString('hex')
const output = resolve('.work/report-demo/editor-boundaries', prefix)
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
  await dialog.getByRole('textbox', { name: '仪表板操作原因', exact: true }).fill(prefix + ' 设计器边界实际验收')
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
  const pivot = board.draft.charts.find(chart => chart.display === 'PIVOT')
  assert.ok(pivot)
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + board.id)
  const tile = page
    .locator('.dashboard-editor-tile')
    .filter({ has: page.getByRole('heading', { name: pivot.title, exact: true }) })
  await tile.getByRole('button', { name: '配置', exact: true }).click()
  const dialog = page.getByRole('dialog').filter({ hasText: '图表配置' })
  async function selectType(name) {
    await dialog.locator('[aria-label="图表类型"] .ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(name, { exact: true }).click()
  }
  await expect(dialog.locator('[aria-label="图表类型"]')).toContainText('透视表')
  await selectType('柱状图')
  let confirm = page.getByRole('dialog').filter({ hasText: '切换图表类型？' })
  await expect(confirm).toContainText('透视列维度')
  await expect(confirm).toContainText('透视小计和显示设置')
  await confirm.getByRole('button', { name: /^取\s*消$/ }).click()
  await expect(dialog.locator('[aria-label="图表类型"]')).toContainText('透视表')
  await expect(dialog.locator('[aria-label="透视列维度"]')).toBeVisible()
  await selectType('柱状图')
  confirm = page.getByRole('dialog').filter({ hasText: '切换图表类型？' })
  await confirm.getByRole('button', { name: '切换类型', exact: true }).click()
  await expect(dialog.locator('[aria-label="图表类型"]')).toContainText('柱状图')
  await expect(dialog.locator('[aria-label="透视列维度"]')).toHaveCount(0)
  await dialog.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(dialog).toHaveCount(0)
  const save = page.waitForResponse(r => r.url().endsWith('/dashboard/save') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  const saved = await (await save).json()
  assert.equal(saved.code, 0, saved.msg)
  board = await api('/nocode/report/dashboard/get?id=' + board.id)
  const changed = board.draft.charts.find(c => c.id === pivot.id)
  assert.equal(changed.display, 'BAR')
  assert.deepEqual(changed.dimensions, pivot.dimensions)
  assert.deepEqual(changed.dataset, pivot.dataset)
  assert.deepEqual(changed.metricIds, pivot.metricIds)
  assert.ok(!changed.columnDimensions?.length)
  assert.ok(!changed.pivot)
  await page.reload()
  await tile.getByRole('button', { name: '配置', exact: true }).click()
  await expect(dialog.locator('[aria-label="图表类型"]')).toContainText('柱状图')
  await page.screenshot({ path: resolve(output, 'type-switch.png'), fullPage: true })
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  checks.push('真实透视转柱状：列维度与透视设置明确列出；取消保持；确认保留行维度/指标/固定版本；保存并重开一致')
  // 两个真实固定数据集：只编辑本次看板副本，不修改来源数据集。
  const other = await api('/nocode/report/dataset/get?id=1576')
  const otherReleases = await api('/nocode/report/dataset/releases?id=1576&pageNo=1&pageSize=100')
  const otherRelease = otherReleases.list.find(r => r.versionNo === other.publishedVersion)
  assert.ok(otherRelease)
  const firstMetric = board.draft.charts.find(c => c.display === 'METRIC')
  assert.ok(firstMetric)
  const secondMetric = structuredClone(firstMetric)
  secondMetric.id = 'cross_dataset'
  secondMetric.title = '第二数据集指标'
  secondMetric.y = 100
  secondMetric.dataset = { id: other.id, versionNo: otherRelease.versionNo, checksum: otherRelease.checksum }
  secondMetric.metricIds = firstMetric.metricIds.map(id => {
    const old = sourceDataset.draft.analysis.metrics.find(m => m.id === id)
    const matched = otherRelease.definition.analysis.metrics.find(
      m => m.name === old.name && m.operation === old.operation
    )
    assert.ok(matched)
    return matched.id
  })
  board = await api('/nocode/report/dashboard/save', {
    id: board.id,
    expectedRevision: board.revision,
    content: { ...board.draft, charts: [...board.draft.charts, secondMetric] }
  })
  await page.reload()
  let filtersDialog = await openDefaults()
  while (await filtersDialog.getByRole('button', { name: /^移除筛选\d+$/ }).count())
    await filtersDialog
      .getByRole('button', { name: /^移除筛选\d+$/ })
      .last()
      .click()
  await filtersDialog.getByRole('button', { name: '添加公共筛选', exact: true }).click()
  await filtersDialog.getByRole('textbox', { name: '筛选名称', exact: true }).fill('跨集公司')
  await chooseMapping(filtersDialog, '跨集公司映射' + firstMetric.title, '公司（维度）')
  await chooseMapping(filtersDialog, '跨集公司映射' + secondMetric.title, '公司（维度）')
  await filtersDialog.locator('[aria-label="筛选类型"] .ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByTitle('日期范围', { exact: true }).click()
  await filtersDialog.getByRole('button', { name: '应用公共筛选', exact: true }).click()
  const rejectedResponse = page.waitForResponse(
    r => r.url().endsWith('/dashboard/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  const rejected = await (await rejectedResponse).json()
  assert.notEqual(rejected.code, 0)
  assert.match(rejected.msg, /公共筛选类型与映射字段不匹配/)
  await expect(page.getByRole('alert').filter({ hasText: '公共筛选类型与映射字段不匹配' })).toBeVisible()
  assert.deepEqual((await api('/nocode/report/dashboard/get?id=' + board.id)).draft, board.draft)
  await page.screenshot({ path: resolve(output, 'cross-dataset-invalid-date.png'), fullPage: true })
  filtersDialog = await openDefaults()
  await filtersDialog.locator('[aria-label="筛选类型"] .ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByTitle('单选', { exact: true }).click()
  await filtersDialog.getByRole('button', { name: '应用公共筛选', exact: true }).click()
  const acceptedResponse = page.waitForResponse(
    r => r.url().endsWith('/dashboard/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  const accepted = await (await acceptedResponse).json()
  assert.equal(accepted.code, 0, accepted.msg)
  assert.equal(accepted.data.draft.filters[0].mappings.length, 2)
  checks.push(
    '跨981/1576两个固定数据集把公司文本映射成日期：真实保存拒绝并明确提示、服务端草稿不变；改回单选后保存两图显式映射'
  )
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByText('收起菜单', { exact: true }).click()
  await expect(page.getByRole('button', { name: '保存并预览', exact: true })).toBeVisible()
  await tile.getByRole('button', { name: '配置', exact: true }).click()
  await expect(dialog.getByRole('button', { name: /^取\s*消$/ })).toBeVisible()
  await expect(dialog.locator('[aria-label="图表类型"]')).toBeVisible()
  await expect(dialog.locator('[aria-label="图表类型"]')).not.toHaveClass(/ant-select-disabled/)
  await expect(dialog.locator('[aria-label="图表数据集版本"] .ant-select-selection-item')).not.toBeEmpty()
  await expect(dialog.locator('.ant-spin-spinning')).toHaveCount(0)
  await page.screenshot({ path: resolve(output, 'narrow-editor.png'), fullPage: true })
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  checks.push('390px窄屏收起二级菜单后，真实设计器和配置弹窗主要操作可见可用')
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=999999999')
  await expect(page.getByRole('alert').filter({ hasText: /不存在|无权|没有该操作权限/ })).toBeVisible()
  await page.screenshot({ path: resolve(output, 'missing-deep-link.png'), fullPage: true })
  checks.push('失效编辑器深链明确错误，未渲染旧看板结果')
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
          reason: prefix + ' 精确清理设计器验收副本'
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
    'Dashboard boundaries: ' +
      checks.length +
      ' groups; ' +
      (!failure && !cleanupErrors.length ? 'PASSED' : 'FAILED') +
      '; ' +
      output
  )
}
assert.deepEqual(cleanupErrors, [])
assert.equal(failure, undefined, failure)
