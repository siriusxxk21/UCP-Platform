/** C5-2 只操作随机副本与自建目录，正式API精确清理，不改历史体验数据。 */
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { verifyDashboardLayout } from './verify-report-dashboard-layout.mjs'
const expect = playwrightExpect.configure({ timeout: 20000 })
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const prefix = 'rptLife' + randomBytes(6).toString('hex')
const output = resolve('.work/report-demo/c5-governance', prefix)
await mkdir(output, { recursive: true })
const checks = [],
  errors = [],
  cleanupErrors = [],
  folders = []
const originalManifest = await readFile('.work/report-demo/current.json', 'utf8')
let token,
  browser,
  page,
  id,
  failure,
  source,
  sourceRelease,
  published = false,
  removed = false
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
async function list() {
  await page.goto(origin + '/nocode/report-center/dashboards')
  await page.getByPlaceholder('搜索仪表板').fill(prefix)
  await page.getByRole('button', { name: /^查\s*询$/ }).click()
  await expect(page.locator('tr[data-row-key="' + id + '"]')).toBeVisible()
}
async function more(name) {
  await page
    .locator('tr[data-row-key="' + id + '"]')
    .getByRole('button', { name: /^更\s*多$/ })
    .click()
  await page.getByRole('menuitem', { name }).click()
}
async function reason(value = prefix + ' 实际页面验收') {
  await page.getByRole('dialog').getByRole('textbox', { name: '仪表板操作原因', exact: true }).fill(value)
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
  assert.equal(source.draft.name, '经营分析 · 核心体验看板')
  assert.equal(String(source.ownerId), String(info.user.id))
  browser = await chromium.launch({ channel: 'chrome', headless: false, args: ['--disable-dev-shm-usage'] })
  const context = await browser.newContext({ viewport: { width: 1512, height: 982 } })
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
    },
    { token, info }
  )
  page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(origin + '/nocode/report-center/dashboards')
  await expect(page.locator('tr[data-row-key="5"]')).toBeVisible()
  await page
    .locator('tr[data-row-key="5"]')
    .getByRole('button', { name: /^更\s*多$/ })
    .click()
  await page.getByRole('menuitem', { name: /^复\s*制$/ }).click()
  await page.getByRole('textbox', { name: '副本名称', exact: true }).fill(prefix)
  await reason()
  const copyResponse = page.waitForResponse(response => response.url().endsWith('/dashboard/copy'))
  await page.getByRole('button', { name: '创建副本', exact: true }).click()
  const copy = await (await copyResponse).json()
  assert.equal(copy.code, 0)
  id = copy.data.id
  await writeFile(resolve(output, 'fixture.json'), JSON.stringify({ prefix, dashboardId: id }, null, 2))
  assert.equal(copy.data.publishedVersion, null)
  assert.ok(copy.data.draft.charts.every(chart => !source.draft.charts.some(original => original.id === chart.id)))
  assert.deepEqual((await api('/nocode/report/dashboard/resource-policy?id=' + id)).members, [])
  assert.equal((await (await request('/nocode/report/dashboard/favorite', { id, favorite: true })).json()).code, 403)
  checks.push('真实列表复制独立草稿，新组件/映射ID、无发布或协作授权；未发布副本不能收藏')
  await page.getByRole('button', { name: '新建目录', exact: true }).click()
  await page.getByRole('textbox', { name: '目录名称', exact: true }).fill(prefix + '目录')
  await page.getByRole('textbox', { name: '目录操作原因', exact: true }).fill(prefix)
  const folderResponse = page.waitForResponse(response => response.url().endsWith('/folder/save'))
  await page.getByRole('button', { name: '保存目录', exact: true }).click()
  const folder = await (await folderResponse).json()
  assert.equal(folder.code, 0)
  folders.push(folder.data)
  const child = await api('/nocode/report/folder/save', {
    id: null,
    expectedRevision: 0,
    resourceKind: 'DASHBOARD',
    parentId: folder.data.id,
    name: prefix + '子目录',
    sortNo: 0,
    reason: prefix
  })
  folders.push(child)
  assert.ok(!(await api('/nocode/report/folder/tree')).some(item => folders.some(own => own.id === item.id)))
  await list()
  await more(/^移\s*动$/)
  await page.locator('[aria-label="目标目录"] .ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByText(child.name, { exact: true }).click()
  await reason()
  await page.getByRole('button', { name: '确认移动', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  assert.equal((await api('/nocode/report/dashboard/get?id=' + id)).folderId, child.id)
  await page.locator('.dashboard-directories').getByText(folder.data.name, { exact: true }).click()
  await expect(page.locator('tr[data-row-key="' + id + '"]')).toBeVisible()
  assert.equal(
    (await api('/nocode/report/dashboard/available-page?pageNo=1&pageSize=100&folderId=' + folder.data.id)).list.some(
      item => item.id === id
    ),
    true
  )
  checks.push('真实仪表板目录新建/移动/祖先包含子目录，DATASET树保持类型隔离')
  let current = await verifyDashboardLayout({ page, api, id, origin, output, checks })
  let release = await api('/nocode/report/dashboard/publish', {
    id,
    expectedRevision: current.revision,
    requestId: randomBytes(12).toString('hex')
  })
  published = true
  await page.goto(origin + '/nocode/report-center/dashboard-view?id=' + id)
  await expect(page.locator('.dashboard-metrics strong').first()).toHaveText('19.25')
  await expect
    .poll(async () => (await api('/nocode/report/dashboard/preference?id=' + id)).lastVisitedAt)
    .not.toBeNull()
  await page
    .locator('.dashboard-toolbar')
    .getByRole('button', { name: /^收\s*藏$/ })
    .click()
  await expect(page.locator('.dashboard-toolbar').getByRole('button', { name: '取消收藏', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '报表工作台', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索看板', exact: true }).fill(prefix)
  await page.getByRole('button', { name: /^查\s*询$/ }).click()
  for (const name of ['我的收藏', '最近访问']) {
    const view = name === '我的收藏' ? 'FAVORITE' : 'RECENT'
    const response = page.waitForResponse(value => {
      const url = new URL(value.url())
      return url.pathname.endsWith('/dashboard/preference-page') && url.searchParams.get('view') === view
    })
    await page.getByRole('tab', { name, exact: true }).click()
    const data = await (await response).json()
    assert.equal(data.code, 0)
    assert.ok(data.data.list.some(item => item.dashboard.id === id))
    await expect(page.getByRole('tab', { name, exact: true })).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.report-home .ant-spin-spinning')).toHaveCount(0)
    await expect(page.locator('tr[data-row-key="' + id + '"]')).toBeVisible()
  }
  await page.screenshot({ path: resolve(output, 'workbench.png'), fullPage: true })
  checks.push('真实成功访问登记recent，查看页收藏持久化，工作台收藏/最近均返回已发布副本')
  await page.goto(origin + '/nocode/report-center/dashboard-view?id=' + id)
  await expect(page.locator('.dashboard-metrics strong').first()).toHaveText('19.25')
  current = await api('/nocode/report/dashboard/get?id=' + id)
  current = await api('/nocode/report/dashboard/save', {
    id,
    expectedRevision: current.revision,
    content: { ...current.draft, description: prefix + '新的发布说明' }
  })
  const next = await api('/nocode/report/dashboard/publish', {
    id,
    expectedRevision: current.revision,
    requestId: randomBytes(12).toString('hex')
  })
  const query = {
    id,
    chartId: release.content.charts[0].id,
    preview: false,
    versionNo: release.versionNo,
    checksum: release.checksum
  }
  const filter = release.content.filters.find(value => ['SELECT', 'MULTISELECT'].includes(value.kind))
  for (const [path, body] of [
    ['query', query],
    [
      'options',
      { query: { ...query, chartId: filter.mappings[0].chartId }, filterId: filter.id, pageNo: 1, pageSize: 10 }
    ],
    ['details', { query, group: [], columnGroup: [], pageNo: 1, pageSize: 10 }],
    ['export', query]
  ])
    assert.equal((await (await request('/nocode/report/dashboard/' + path, body)).json()).code, 1050000007)
  await page.locator('.dashboard-tile').first().getByRole('button', { name: '查看明细', exact: true }).click()
  await expect(page.locator('.dashboard-page > .ant-alert')).toContainText('已发布新版本')
  await expect(page.locator('.dashboard-tile')).toHaveCount(0)
  await page.getByRole('button', { name: '刷新数据', exact: true }).click()
  await expect(page.locator('.dashboard-metrics strong').first()).toHaveText('19.25')
  await page.screenshot({ path: resolve(output, 'current-version.png'), fullPage: true })
  checks.push(
    '打开期间新发布后旧query/options/details/export均VERSION_CHANGED，真实明细触发全页清空，手动刷新完整新版本'
  )
  await list()
  await more('历史版本')
  await page
    .getByRole('dialog')
    .locator('.ant-radio-wrapper')
    .filter({ hasText: new RegExp('V' + release.versionNo + '\\s*·') })
    .click()
  await reason()
  await page.getByRole('button', { name: '恢复为草稿', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  assert.equal((await api('/nocode/report/dashboard/get?id=' + id)).draft.description, release.content.description)
  assert.equal((await api('/nocode/report/dashboard/published?id=' + id)).versionNo, next.versionNo)
  await more('停用')
  await reason()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^停\s*用$/ })
    .click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  assert.equal((await api('/nocode/report/dashboard/get?id=' + id)).status, 'INACTIVE')
  assert.ok(
    !(
      await api('/nocode/report/dashboard/preference-page?pageNo=1&pageSize=100&view=FAVORITE&search=' + prefix)
    ).list.some(item => item.dashboard.id === id)
  )
  await more('启用')
  await reason()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^启\s*用$/ })
    .click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  assert.equal((await api('/nocode/report/dashboard/preference?id=' + id)).favorite, true)
  checks.push('真实历史恢复只写草稿而不回拨发布；启停全版本且工作台隐藏，重新启用保留个人收藏')
  await more(/^删\s*除$/)
  await page.getByRole('dialog').getByRole('checkbox').check()
  await reason()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  removed = true
  await expect(page.locator('tr[data-row-key="' + id + '"]')).toHaveCount(0)
  checks.push('真实删除预检及确认关闭自有副本，来源与原看板保留')
  assert.deepEqual(await api('/nocode/report/dashboard/get?id=5'), source)
  assert.deepEqual(await api('/nocode/report/dashboard/published?id=5'), sourceRelease)
  assert.equal(await readFile('.work/report-demo/current.json', 'utf8'), originalManifest)
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
  if (id && !removed)
    await clean('精确删除自有副本', async () => {
      let current = await api('/nocode/report/dashboard/get?id=' + id)
      assert.equal(current.draft.name, prefix)
      if (current.status === 'INACTIVE')
        current = await api('/nocode/report/dashboard/status', {
          id,
          expectedRevision: current.revision,
          status: 'ACTIVE',
          reason: prefix + '清理'
        })
      if (published) await api('/nocode/report/dashboard/favorite', { id, favorite: false })
      await api('/nocode/report/dashboard/delete', { id, expectedRevision: current.revision, reason: prefix + '清理' })
    })
  for (const folder of folders.reverse())
    await clean('删除自有目录', async () => {
      assert.ok(folder.name.startsWith(prefix))
      await api('/nocode/report/folder/delete', {
        id: folder.id,
        expectedRevision: folder.revision,
        resourceKind: 'DASHBOARD',
        reason: prefix + '清理'
      })
    })
  if (token) await clean('注销验证会话', () => api('/system/auth/logout', {}))
  token = undefined
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        prefix,
        dashboardId: id,
        checks,
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
    'C5 governance: ' +
      checks.length +
      ' groups; ' +
      (!failure && !cleanupErrors.length ? 'PASSED' : 'FAILED') +
      '; ' +
      output
  )
}
assert.deepEqual(cleanupErrors, [])
assert.equal(failure, undefined, failure)
