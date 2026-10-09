/** C2：只更新已登记的报表体验夹具；真实 UI 制作透视、明细和 XLSX 下载，令牌仅保存在进程内。 */
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })
const env = Object.fromEntries(
  (await readFile('.env.test', 'utf8'))
    .split(/\r?\n/)
    .filter(l => /^(E2E_USERNAME|E2E_PASSWORD)=/.test(l))
    .map(l => {
      const i = l.indexOf('=')
      return [
        l.slice(0, i),
        l
          .slice(i + 1)
          .trim()
          .replace(/^(['"])(.*)\1$/, '$2')
      ]
    })
)
const manifest = JSON.parse(await readFile('.work/report-demo/current.json', 'utf8'))
assert.match(manifest.prefix, /^test_b1_[a-f0-9]+$/)
const base = 'http://127.0.0.1:8080/api',
  origin = 'http://127.0.0.1:5173'
let token, browser, restorePolicy
async function request(path, body) {
  return fetch(base + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined
  })
}
async function api(path, body) {
  const result = await (await request(path, body)).json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
const output = resolve('.work/report-demo/c2')
await mkdir(output, { recursive: true })
const checks = [],
  errors = []
try {
  token = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD })).accessToken
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  const id = manifest.dashboardId,
    datasetId = manifest.datasetId
  let board = await api('/nocode/report/dashboard/get?id=' + id)
  const dataset = await api('/nocode/report/dataset/get?id=' + datasetId)
  assert.equal(board.draft.name, '经营分析 · 核心体验看板')
  assert.equal(dataset.draft.name, '报表体验 · 经营分析数据集')
  assert.ok(board.draft.charts.every(c => c.dataset.id === datasetId))
  assert.equal(dataset.draft.source.root.objectId, manifest.objectIds[0])
  const info = await api('/system/auth/get-permission-info')
  // 仅体验夹具补导出能力；READ 行条件保持，EXPORT 使用相同行条件，未触及用户业务资源。
  const ceilings = await api('/nocode/report/dataset/ceilings?id=' + datasetId)
  for (const c of ceilings) {
    assert.ok(manifest.objectIds.includes(c.objectId))
    if (!c.permission.actions.includes('EXPORT'))
      await api('/nocode/report/dataset/ceiling', {
        datasetId,
        objectId: c.objectId,
        expectedRevision: c.revision,
        permission: {
          ...c.permission,
          actions: [...c.permission.actions, 'EXPORT'],
          actionScopes: {
            ...c.permission.actionScopes,
            ...(c.permission.actionScopes?.READ ? { EXPORT: c.permission.actionScopes.READ } : {})
          }
        },
        reason: 'C2 已识别体验夹具启用受控导出'
      })
  }
  let policy = await api('/nocode/report/dataset/data-policy?id=' + datasetId)
  const members = policy.members.map(m => ({
    ...m,
    objects: m.objects.map(g => ({
      ...g,
      actions: [...new Set([...g.actions, 'EXPORT'])],
      actionScopes: { ...g.actionScopes, ...(g.actionScopes?.READ ? { EXPORT: g.actionScopes.READ } : {}) }
    }))
  }))
  if (JSON.stringify(members) !== JSON.stringify(policy.members))
    policy = await api('/nocode/report/dataset/data-policy', {
      datasetId,
      expectedRevision: policy.revision,
      members,
      reason: 'C2 体验成员导出与读取范围相同'
    })
  browser = await chromium.launch({
    headless: true,
    channel: 'chrome',
    args: ['--disable-background-timer-throttling']
  })
  const page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
  page.setDefaultTimeout(20000)
  page.on('pageerror', e => errors.push(e.message))
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
      document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style')
        style.textContent = '*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important}'
        document.head.appendChild(style)
      })
    },
    { token, info }
  )
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + id)
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  await page
    .getByRole('textbox', { name: '仪表板说明', exact: true })
    .fill('可操作体验数据：流水 → 账户 → 公司。六类展示、月份透视、只读明细及 Excel；金额合计 19.25。')
  const existing = board.draft.charts.find(c => c.display === 'PIVOT')
  if (existing)
    await page
      .locator('.dashboard-tile')
      .filter({ has: page.getByRole('heading', { name: existing.title, exact: true }) })
      .getByRole('button', { name: /^配\s*置$/ })
      .click()
  else await page.getByRole('button', { name: '透视表', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '图表标题', exact: true }).fill('公司与月度经营透视')
  if (!existing) {
    await dialog.locator('[aria-label="图表数据集"] .ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(dataset.draft.name, { exact: true }).click()
  }
  await expect(dialog.locator('[aria-label="图表指标"]')).toContainText('金额合计')
  async function chooseMany(label, names) {
    const control = dialog.locator(`[aria-label="${label}"]`)
    while (await control.locator('.ant-select-selection-item-remove').count())
      await control.locator('.ant-select-selection-item-remove').first().click()
    await control.locator('.ant-select-selector').click()
    for (const name of names)
      await page.locator('.ant-select-dropdown:visible').getByTitle(name, { exact: true }).click()
    await dialog.getByRole('textbox', { name: '图表标题', exact: true }).click()
  }
  await chooseMany('图表维度', ['公司', '状态'])
  await chooseMany('透视列维度', ['业务日期'])
  const bucket = dialog
    .locator('.ant-space')
    .filter({ has: page.locator('span').filter({ hasText: /^业务日期$/ }) })
    .filter({ has: page.locator('.ant-select') })
    .last()
  await bucket.locator('.ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByTitle('按月', { exact: true }).click()
  await dialog.getByRole('spinbutton', { name: '图表列位置', exact: true }).fill('0')
  await dialog.getByRole('spinbutton', { name: '图表行位置', exact: true }).fill('15')
  await dialog.getByRole('spinbutton', { name: '图表宽度', exact: true }).fill('12')
  await dialog.getByRole('spinbutton', { name: '图表高度', exact: true }).fill('8')
  await dialog.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  await expect(page.locator('.report-pivot')).toBeVisible()
  await expect(page.locator('.report-pivot')).toContainText('2026-01')
  await expect(page.locator('.report-pivot')).toContainText('2026-02')
  await expect(page.locator('.pivot-row--subtotal')).not.toHaveCount(0)
  await page.reload()
  await expect(page.locator('.report-pivot')).toBeVisible()
  await page.getByRole('button', { name: '发布并打开', exact: true }).click()
  await expect(page).toHaveURL(/dashboard-view/)
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  await expect(page.locator('.report-pivot')).toContainText('19.25')
  checks.push('真实设计器配置两层行维度、月度列维度；保存重开、发布及六类看板运行')
  const pivot = page
    .locator('.dashboard-tile')
    .filter({ has: page.getByRole('heading', { name: '公司与月度经营透视', exact: true }) })
  await pivot.getByRole('button', { name: '全部折叠', exact: true }).click()
  await expect(pivot.locator('tbody tr[data-row-level="leaf"]')).toHaveCount(0)
  await pivot.getByRole('button', { name: '全部展开', exact: true }).click()
  await expect(pivot.locator('tbody tr[data-row-level="leaf"]')).not.toHaveCount(0)
  const cell = pivot
    .locator('tbody tr[data-row-level="leaf"] button.pivot-value')
    .filter({ hasText: /^10\.25$/ })
    .first()
  const response = page.waitForResponse(r => r.url().endsWith('/dashboard/details'))
  await cell.click()
  const details = (await (await response).json()).data
  assert.equal(details.total, 1)
  assert.ok(details.list[0].values.includes('10.25'))
  await expect(page.locator('.ant-drawer:visible')).toContainText('共 1 条')
  await expect(page.locator('.ant-drawer:visible')).toContainText('10.25')
  for (const field of ['公司', '状态', '业务日期', '金额'])
    await expect(
      page.locator('.ant-drawer:visible').getByRole('columnheader', { name: field, exact: true })
    ).toBeVisible()
  await page.screenshot({ path: resolve(output, 'detail.png'), fullPage: true })
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  const total = pivot.locator('tfoot button.pivot-value').filter({ hasText: /^19\.25$/ })
  const totalResponse = page.waitForResponse(r => r.url().endsWith('/dashboard/details'))
  await total.click()
  assert.equal((await (await totalResponse).json()).data.total, 3)
  await expect(page.locator('.ant-drawer:visible')).toContainText('共 3 条')
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  checks.push('透视折叠展开、小计/合计渲染；月份叶子明细1条、总体明细3条与真实金额一致')
  async function download(tile, name) {
    const event = page.waitForEvent('download')
    await tile.getByRole('button', { name: '导出 Excel', exact: true }).click()
    await (await event).saveAs(resolve(output, name))
  }
  await download(pivot, 'pivot.xlsx')
  await download(
    page.locator('.dashboard-tile').filter({ has: page.getByRole('heading', { name: '公司金额汇总', exact: true }) }),
    'summary.xlsx'
  )
  checks.push('透视与汇总 Excel 经真实按钮下载')
  const release = await api('/nocode/report/dashboard/published?id=' + id)
  const chart = release.content.charts.find(c => c.display === 'PIVOT')
  const query = { id, chartId: chart.id, preview: false, versionNo: release.versionNo, checksum: release.checksum }
  const snapshot = await api('/nocode/report/dashboard/query', query)
  assert.equal(snapshot.totals[chart.metricIds[0]], '19.25')
  assert.equal(snapshot.canExport, true)
  assert.equal(snapshot.pivot.columnDimensionNames[0], '业务日期')
  const nullGroup = snapshot.pivot.rows.find(r => r.keys[0] === null)
  assert.ok(nullGroup)
  const nullDetail = await api('/nocode/report/dashboard/details', {
    query,
    group: [null],
    columnGroup: ['2026-02'],
    metricId: chart.metricIds[0],
    pageNo: 1,
    pageSize: 1
  })
  assert.equal(nullDetail.total, 2)
  assert.equal(nullDetail.list.length, 1)
  const nullNext = await api('/nocode/report/dashboard/details', {
    query,
    group: [null],
    columnGroup: ['2026-02'],
    metricId: chart.metricIds[0],
    pageNo: 2,
    pageSize: 1
  })
  assert.notEqual(nullNext.list[0].id, nullDetail.list[0].id)
  const forged = await (
    await request('/nocode/report/dashboard/details', {
      query: { ...query, checksum: 'forged' },
      group: [],
      columnGroup: [],
      pageNo: 1,
      pageSize: 20
    })
  ).json()
  assert.notEqual(forged.code, 0)
  checks.push('空值行前缀与月度键合取后两条记录，分页不重复，伪造版本明细被拒绝')
  policy = await api('/nocode/report/dataset/data-policy?id=' + datasetId)
  const removed = await api('/nocode/report/dataset/data-policy', {
    datasetId,
    expectedRevision: policy.revision,
    members: policy.members.map(m => ({
      ...m,
      objects: m.objects.map(g => ({
        ...g,
        actions: g.actions.filter(a => a !== 'EXPORT'),
        actionScopes: Object.fromEntries(Object.entries(g.actionScopes || {}).filter(([k]) => k !== 'EXPORT'))
      }))
    })),
    reason: 'C2 体验夹具导出撤权验证'
  })
  restorePolicy = {
    datasetId,
    expectedRevision: removed.revision,
    members: policy.members,
    reason: 'C2 验证结束恢复体验导出权限'
  }
  const stillReadable = await api('/nocode/report/dashboard/query', query)
  assert.equal(stillReadable.recordCount, 3)
  assert.equal(stillReadable.canExport, false)
  const denied = await (await request('/nocode/report/dashboard/export', query)).json()
  assert.notEqual(denied.code, 0)
  await pivot.getByRole('button', { name: '导出 Excel', exact: true }).click()
  await expect(pivot.locator('.ant-alert-error')).toBeVisible()
  await api('/nocode/report/dataset/data-policy', restorePolicy)
  restorePolicy = undefined
  checks.push('撤销 EXPORT 后读取仍可用、接口和页面导出均拒绝；已恢复体验权限')
  await page.reload()
  await expect(page.locator('.report-pivot')).toContainText('19.25')
  await pivot.scrollIntoViewIfNeeded()
  await page.screenshot({ path: resolve(output, 'pivot.png'), fullPage: true })
  await page.setViewportSize({ width: 1280, height: 900 })
  await expect(page.locator('.report-pivot')).toBeVisible()
  await page.screenshot({ path: resolve(output, 'pivot-1280.png'), fullPage: true })
  assert.deepEqual(errors, [])
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ dashboardId: id, version: release.versionNo, checks, errors, snapshot }, null, 2)
  )
  console.log('C2 browser: ' + checks.length + ' groups passed; dashboard ' + id + ' V' + release.versionNo)
} catch (error) {
  if (browser) {
    const pages = browser.contexts()[0]?.pages()
    if (pages?.[0]) await pages[0].screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  }
  throw error
} finally {
  if (restorePolicy) await api('/nocode/report/dataset/data-policy', restorePolicy)
  if (browser) await browser.close()
  if (token) await request('/system/auth/logout', {}).catch(() => {})
}
