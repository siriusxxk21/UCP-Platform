/** C3：只制作已登记的体验看板；公共筛选、联动、三层钻取和异步恢复使用真实页面与接口。 */
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile, stat } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20000 })
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
const manifest = JSON.parse(await readFile('.work/report-demo/current.json', 'utf8'))
assert.match(manifest.prefix, /^test_b1_[a-f0-9]+$/)
assert.equal(String(manifest.dashboardId), '5')
assert.equal(String(manifest.datasetId), '981')
const base = 'http://127.0.0.1:8080/api'
const origin = 'http://127.0.0.1:5173'
const output = resolve('.work/report-demo/c3')
await mkdir(output, { recursive: true })
let token,
  browser,
  page,
  releaseDelayedResponse,
  abortSuppressed = false
const checks = [],
  errors = [],
  observations = []

async function request(path, body) {
  return fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}
async function api(path, body) {
  const result = await (await request(path, body)).json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
function deferred() {
  let finish
  const promise = new Promise(resolvePromise => {
    finish = resolvePromise
  })
  return { promise, finish }
}
async function bounded(promise, label) {
  let timer
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label + '等待超时')), 20000)
      })
    ])
  } finally {
    clearTimeout(timer)
  }
}

try {
  token = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD })).accessToken
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  const id = String(manifest.dashboardId),
    datasetId = String(manifest.datasetId)
  const original = await api('/nocode/report/dashboard/get?id=' + id)
  const dataset = await api('/nocode/report/dataset/get?id=' + datasetId)
  assert.equal(original.draft.name, '经营分析 · 核心体验看板')
  assert.equal(dataset.draft.name, '报表体验 · 经营分析数据集')
  assert.equal(dataset.draft.source.root.objectId, manifest.objectIds[0])
  assert.ok(original.draft.charts.every(chart => chart.dataset.id === datasetId))
  const table = original.draft.charts.find(chart => chart.display === 'TABLE')
  const metric = original.draft.charts.find(chart => chart.display === 'METRIC')
  assert.ok(table && metric, '已登记体验看板需保留汇总表与指标卡')
  const fields = dataset.draft.source.fields
  const field = name => {
    const value = fields.find(value => value.name === name)
    assert.ok(value, '体验数据集缺少维度：' + name)
    return value.id
  }
  // 现有夹具选入公司、状态和业务日期；账户是关联路径节点，没有账户分析维度。
  const hierarchy = ['公司', '状态', '业务日期']
  hierarchy.forEach(field)
  const info = await api('/system/auth/get-permission-info')
  browser = await chromium.launch({
    headless: true,
    channel: 'chrome',
    args: ['--disable-background-timer-throttling']
  })
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
      document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style')
        style.textContent = '*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important}'
        document.head.appendChild(style)
      })
    },
    { token, info }
  )
  const tile = title =>
    page.locator('.dashboard-tile').filter({ has: page.getByRole('heading', { name: title, exact: true }) })
  const metricTile = () => tile(metric.title)
  const tableTile = () => tile(table.title)
  const amount = value => expect(metricTile().locator('.dashboard-metrics strong')).toHaveText(value)
  async function choose(scope, label, value) {
    await scope.locator(`[aria-label="${label}"] .ant-select-selector`).last().click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(value, { exact: true }).click()
  }
  async function closeSelect(scope) {
    await scope.getByRole('textbox', { name: '图表标题', exact: true }).click()
  }
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + id)
  await amount('19.25')
  await tableTile()
    .getByRole('button', { name: /^配\s*置$/ })
    .click()
  let dialog = page.getByRole('dialog')
  // 替换本图已有交互配置，重复执行不生成额外层级或重复联动。
  while (await dialog.getByRole('button', { name: /^移除第[23]层$/ }).count())
    await dialog
      .getByRole('button', { name: /^移除第[23]层$/ })
      .last()
      .click()
  for (const [index, name] of hierarchy.slice(1).entries()) {
    await dialog.getByRole('button', { name: '添加下钻层级', exact: true }).click()
    await choose(dialog, `第${index + 2}层维度`, name)
    if (name === '业务日期') await choose(dialog, `第${index + 2}层分组粒度`, '按月')
    await closeSelect(dialog)
  }
  while (await dialog.getByRole('button', { name: /^移除联动\d+$/ }).count())
    await dialog
      .getByRole('button', { name: /^移除联动\d+$/ })
      .last()
      .click()
  for (const target of original.draft.charts.filter(chart => chart.id !== table.id)) {
    await dialog.getByRole('button', { name: '添加图表联动', exact: true }).click()
    await choose(dialog, '联动目标图表', target.title)
    await choose(dialog, '联动来源字段', '公司')
    await choose(dialog, '联动目标字段', '公司')
    await closeSelect(dialog)
  }
  await dialog.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '公共筛选', exact: true }).click()
  dialog = page.getByRole('dialog')
  while (await dialog.getByRole('button', { name: /^移除筛选\d+$/ }).count())
    await dialog
      .getByRole('button', { name: /^移除筛选\d+$/ })
      .last()
      .click()
  for (const [index, [name, type, source, role]] of [
    ['公司筛选', '单选', '公司', '维度'],
    ['状态筛选', '多选', '状态', '维度'],
    ['公司搜索', '文本搜索', '公司', '维度'],
    ['金额范围', '数字范围', '金额', '度量'],
    ['日期范围', '日期范围', '业务日期', '维度']
  ].entries()) {
    await dialog.getByRole('button', { name: '添加公共筛选', exact: true }).click()
    await dialog.getByRole('textbox', { name: '筛选名称', exact: true }).nth(index).fill(name)
    const typeControl = dialog.locator('[aria-label="筛选类型"]').nth(index)
    await typeControl.locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(type, { exact: true }).click()
    for (const chart of original.draft.charts)
      await choose(dialog, name + '映射' + chart.title, source + '（' + role + '）')
    await dialog.getByRole('textbox', { name: '筛选名称', exact: true }).nth(index).click()
  }
  await dialog.getByRole('button', { name: '应用公共筛选', exact: true }).click()
  await page.getByRole('button', { name: '保存并预览', exact: true }).click()
  await amount('19.25')
  await page.reload()
  await amount('19.25')
  const saved = await api('/nocode/report/dashboard/get?id=' + id)
  assert.equal(saved.draft.filters.length, 5)
  assert.deepEqual(saved.draft.charts.find(chart => chart.id === table.id).drillDimensions, [
    { fieldId: field('状态'), bucket: 'VALUE' },
    { fieldId: field('业务日期'), bucket: 'MONTH' }
  ])
  assert.equal(saved.draft.charts.find(chart => chart.id === table.id).links.length, original.draft.charts.length - 1)
  await page.getByRole('button', { name: '发布并打开', exact: true }).click()
  await expect(page).toHaveURL(/dashboard-view/)
  await amount('19.25')
  const release = await api('/nocode/report/dashboard/published?id=' + id)
  const companyFilter = release.content.filters.find(filter => filter.name === '公司筛选')
  const stateFilter = release.content.filters.find(filter => filter.name === '状态筛选')
  const textFilter = release.content.filters.find(filter => filter.name === '公司搜索')
  const numberFilter = release.content.filters.find(filter => filter.name === '金额范围')
  const dateFilter = release.content.filters.find(filter => filter.name === '日期范围')
  const identity = { id, chartId: metric.id, preview: false, versionNo: release.versionNo, checksum: release.checksum }
  checks.push('真实UI配置五类公共筛选及六图显式映射，汇总表联动和公司→状态→月份三层；保存重开、发布保持配置')

  async function options(name, filterId) {
    const response = page.waitForResponse(
      response =>
        response.url().endsWith('/dashboard/options') && response.request().postDataJSON().filterId === filterId
    )
    await page.locator(`[aria-label="${name}"] .ant-select-selector`).click()
    const payload = await (await response).json()
    assert.equal(payload.code, 0, payload.msg)
    assert.ok(!JSON.stringify(payload.data).includes('隐藏公司'))
    return payload.data
  }
  async function pick(name, filterId, value) {
    const candidates = await options(name, filterId)
    const index = candidates.list.findIndex(option => option.value === value)
    assert.ok(index >= 0, name + '未提供受控原键：' + value)
    const popup = page.locator('.ant-select-dropdown:visible')
    await expect(popup.locator('.ant-select-item-option')).toHaveCount(candidates.list.length)
    await popup.locator('.ant-select-item-option').nth(index).click()
    await page.getByRole('heading', { name: release.content.name, exact: true }).click()
    return candidates
  }
  const reset = async () => {
    await page.getByRole('button', { name: '重置筛选', exact: true }).click()
    await amount('19.25')
  }
  await pick('公司筛选', companyFilter.id, '可见公司')
  await amount('10.25')
  await reset()
  await pick('公司筛选', companyFilter.id, null)
  await amount('9.00')
  const nullStates = await options('状态筛选', stateFilter.id)
  assert.deepEqual(
    nullStates.list.map(option => option.value),
    ['CLOSED']
  )
  await page.getByRole('heading', { name: release.content.name, exact: true }).click()
  await reset()
  await pick('状态筛选', stateFilter.id, 'OPEN')
  await amount('10.25')
  await pick('公司筛选', companyFilter.id, '可见公司')
  await amount('10.25')
  await reset()
  const impossible = await api('/nocode/report/dashboard/query', {
    ...identity,
    filterValues: [
      { filterId: companyFilter.id, values: ['可见公司'] },
      { filterId: stateFilter.id, values: ['CLOSED'] }
    ]
  })
  assert.equal(impossible.recordCount, 0)
  checks.push('公共筛选实际口径19.25→10.25/9.00；NULL与状态原键不混淆，候选随其他条件收窄，重置保留数据权限')

  function metricResponse(filterId) {
    const pending = page.waitForResponse(response => {
      if (!response.url().endsWith('/dashboard/query')) return false
      const body = response.request().postDataJSON()
      return body.chartId === metric.id && body.filterValues?.some(value => value.filterId === filterId)
    })
    void pending.catch(() => {})
    return pending
  }
  async function applied(response, expected) {
    const returned = await response
    const body = await returned.json()
    assert.equal(body.code, 0, body.msg)
    assert.equal(body.data.totals[metric.metricIds[0]], expected)
    await amount(expected)
    return returned.request().postDataJSON()
  }
  await page.getByRole('textbox', { name: '公司搜索', exact: true }).fill('见公')
  let filtered = metricResponse(textFilter.id)
  await page.getByRole('button', { name: '应用公司搜索', exact: true }).click()
  let body = await applied(filtered, '10.25')
  assert.deepEqual(body.filterValues.find(value => value.filterId === textFilter.id).values, ['见公'])
  await reset()
  await page.getByRole('textbox', { name: '金额范围下限', exact: true }).fill('0')
  await page.getByRole('textbox', { name: '金额范围上限', exact: true }).fill('10')
  filtered = metricResponse(numberFilter.id)
  await page.getByRole('button', { name: '应用金额范围', exact: true }).click()
  body = await applied(filtered, '9.00')
  assert.deepEqual(
    body.filterValues.find(value => value.filterId === numberFilter.id),
    {
      filterId: numberFilter.id,
      from: '0',
      to: '10'
    }
  )
  await reset()
  async function dateInput(name, value) {
    const input = page.getByRole('textbox', { name, exact: true })
    await input.click()
    await input.fill(value)
    await input.press('Enter')
    await input.press('Escape')
  }
  for (const [from, to, expected] of [
    ['2026-01-31', '2026-01-31', '10.25'],
    ['2026-02-01', '2026-02-02', '9.00']
  ]) {
    await dateInput('日期范围开始日期', from)
    await dateInput('日期范围结束日期', to)
    filtered = metricResponse(dateFilter.id)
    await page.getByRole('button', { name: '应用日期范围', exact: true }).click()
    body = await applied(filtered, expected)
    assert.deepEqual(
      body.filterValues.find(value => value.filterId === dateFilter.id),
      {
        filterId: dateFilter.id,
        from,
        to
      }
    )
    await reset()
  }
  checks.push('文本包含“见公”命中公司金额10.25；数字闭区间0至10为9.00；日期首尾日实际查询与序列化均保持10.25/9.00')

  function rootCell(value) {
    return tableTile()
      .locator('tbody button')
      .filter({ hasText: new RegExp('^' + value.replace('.', '\\.') + '$') })
      .first()
  }
  await tableTile().getByText('点击联动', { exact: true }).click()
  await rootCell('10.25').click()
  await amount('10.25')
  const linkedCompanies = await options('公司筛选', companyFilter.id)
  assert.deepEqual(
    linkedCompanies.list.map(option => option.value),
    ['可见公司']
  )
  await page.getByRole('heading', { name: release.content.name, exact: true }).click()
  await rootCell('10.25').click()
  await amount('19.25')
  await rootCell('9.00').click()
  await amount('9.00')
  const linkedNull = await options('公司筛选', companyFilter.id)
  assert.deepEqual(
    linkedNull.list.map(option => option.value),
    [null]
  )
  await page.getByRole('heading', { name: release.content.name, exact: true }).click()
  await pick('状态筛选', stateFilter.id, 'CLOSED')
  await amount('9.00')
  const linkedQuery = {
    ...identity,
    selections: [{ chartId: table.id, group: [null] }],
    filterValues: [{ filterId: stateFilter.id, values: ['CLOSED'] }]
  }
  const linkedResult = await api('/nocode/report/dashboard/query', linkedQuery)
  assert.equal(linkedResult.recordCount, 2)
  assert.equal(linkedResult.totals[metric.metricIds[0]], '9.00')
  checks.push('TABLE原始键点选联动六图，重复点击取消；同字段候选保留其他来源联动，NULL键条件与未选择分别处理')

  const detailResponse = page.waitForResponse(response => response.url().endsWith('/dashboard/details'))
  await metricTile().getByRole('button', { name: '查看明细', exact: true }).click()
  const detailCall = await detailResponse
  assert.deepEqual(detailCall.request().postDataJSON().query.selections, linkedQuery.selections)
  assert.deepEqual(detailCall.request().postDataJSON().query.filterValues, linkedQuery.filterValues)
  const detail = (await detailCall.json()).data
  assert.equal(detail.total, 2)
  await expect(page.locator('.ant-drawer:visible')).toContainText('共 2 条')
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  const exportCall = page.waitForRequest(request => request.url().endsWith('/dashboard/export'))
  const download = page.waitForEvent('download')
  await metricTile().getByRole('button', { name: '导出 Excel', exact: true }).click()
  const exportBody = (await exportCall).postDataJSON()
  assert.deepEqual(exportBody.selections, linkedQuery.selections)
  assert.deepEqual(exportBody.filterValues, linkedQuery.filterValues)
  await (await download).saveAs(resolve(output, 'linked-null.xlsx'))
  assert.ok((await stat(resolve(output, 'linked-null.xlsx'))).size > 1000)
  await page.getByRole('button', { name: '清除联动', exact: true }).click()
  await amount('9.00')
  await reset()
  checks.push(
    '筛选与联动下明细真实两条记录、XLSX实际下载；请求保留filterValues/selections，清除联动保留公共筛选，重置恢复19.25'
  )

  await tableTile().getByText('层级下钻', { exact: true }).click()
  await rootCell('10.25').click()
  await expect(tableTile().getByRole('columnheader', { name: '状态', exact: true })).toBeVisible()
  await expect(tableTile()).toContainText('正常')
  await rootCell('10.25').click()
  await expect(tableTile().getByRole('columnheader', { name: '业务日期', exact: true })).toBeVisible()
  await expect(tableTile()).toContainText('2026-01')
  const drilledDetail = page.waitForResponse(response => response.url().endsWith('/dashboard/details'))
  await tableTile().getByRole('button', { name: '查看明细', exact: true }).click()
  const drilledCall = await drilledDetail
  assert.deepEqual(drilledCall.request().postDataJSON().query.drillPath, ['可见公司', 'OPEN'])
  assert.equal((await drilledCall.json()).data.total, 1)
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  await tableTile().getByRole('button', { name: '返回上层', exact: true }).click()
  await expect(tableTile().getByRole('columnheader', { name: '状态', exact: true })).toBeVisible()
  await tableTile().getByRole('button', { name: '全部', exact: true }).click()
  await expect(tableTile().getByRole('columnheader', { name: '公司', exact: true })).toBeVisible()
  await tableTile().getByText('层级下钻', { exact: true }).click()
  await rootCell('9.00').click()
  await expect(tableTile().getByRole('columnheader', { name: '状态', exact: true })).toBeVisible()
  await rootCell('9.00').click()
  await expect(tableTile()).toContainText('2026-02')
  const nullDrill = await api('/nocode/report/dashboard/query', {
    ...identity,
    chartId: table.id,
    drillPath: [null, 'CLOSED']
  })
  assert.equal(nullDrill.recordCount, 2)
  assert.equal(nullDrill.totals[metric.metricIds[0]], '9.00')
  await tableTile().getByRole('button', { name: '全部', exact: true }).click()
  await amount('19.25')
  checks.push('公司→状态→月份三层真查询、原键路径和NULL路径；明细保留drillPath，返回上层/根层恢复正确粒度')

  // 此段暂时禁止浏览器取消传输，直接验证请求代次屏障；结束恢复原生 abort。
  // 只延迟一张图的真实响应，让后一次筛选先返回，不以伪造数值替代实际聚合。
  await page.evaluate(() => {
    window.__reportC3OriginalAbort = AbortController.prototype.abort
    AbortController.prototype.abort = () => {}
  })
  abortSuppressed = true
  const intercepted = deferred(),
    releaseOld = deferred(),
    delivered = deferred()
  releaseDelayedResponse = releaseOld.finish
  let delayed = false
  const queryPattern = '**/api/nocode/report/dashboard/query'
  await page.route(queryPattern, async route => {
    const body = route.request().postDataJSON()
    if (
      !delayed &&
      body.chartId === metric.id &&
      body.filterValues?.some(value => value.filterId === companyFilter.id && value.values?.includes('可见公司'))
    ) {
      delayed = true
      const response = await route.fetch()
      intercepted.finish()
      await releaseOld.promise
      await route.fulfill({ response })
      delivered.finish()
    } else await route.continue()
  })
  await pick('公司筛选', companyFilter.id, '可见公司')
  await bounded(intercepted.promise, '旧图表请求')
  await pick('公司筛选', companyFilter.id, null)
  await amount('9.00')
  releaseOld.finish()
  await bounded(delivered.promise, '迟到图表响应交付')
  releaseDelayedResponse = undefined
  await page.waitForTimeout(150)
  await amount('9.00')
  await page.unroute(queryPattern)
  await page.evaluate(() => {
    AbortController.prototype.abort = window.__reportC3OriginalAbort
    delete window.__reportC3OriginalAbort
  })
  abortSuppressed = false
  observations.push('仅延迟验证段禁止abort，旧真实响应实际交付后仍未覆盖NULL公司新结果9.00；随后已恢复原生取消行为')
  await reset()
  let failed = false
  await page.route(queryPattern, async route => {
    if (!failed && route.request().postDataJSON().chartId === metric.id) {
      failed = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 1, msg: 'C3单图可恢复验收错误', data: null })
      })
    } else await route.continue()
  })
  await page.getByRole('button', { name: '刷新数据', exact: true }).click()
  await expect(metricTile().locator('.ant-alert-error')).toContainText('C3单图可恢复验收错误')
  await expect(tableTile()).toContainText('19.25')
  await expect(metricTile().locator('.dashboard-metrics strong')).toHaveCount(0)
  await page.unroute(queryPattern)
  await metricTile().getByRole('button', { name: '重试图表', exact: true }).click()
  await amount('19.25')
  await expect(metricTile().locator('.ant-alert-error')).toHaveCount(0)
  checks.push('迟到真实响应不覆盖新筛选；单图失败保留其他图表且不显示0，单图重试恢复')
  await page.screenshot({ path: resolve(output, 'interactions.png'), fullPage: true })
  await page.setViewportSize({ width: 1280, height: 900 })
  await amount('19.25')
  await page.screenshot({ path: resolve(output, 'interactions-1280.png'), fullPage: true })
  assert.deepEqual(errors, [])
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      { dashboardId: id, datasetId, version: release.versionNo, hierarchy, checks, errors, observations },
      null,
      2
    )
  )
  console.log('C3 browser: ' + checks.length + ' groups passed; dashboard ' + id + ' V' + release.versionNo)
} catch (error) {
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  throw error
} finally {
  releaseDelayedResponse?.()
  if (abortSuppressed && page && !page.isClosed())
    await page
      .evaluate(() => {
        AbortController.prototype.abort = window.__reportC3OriginalAbort
        delete window.__reportC3OriginalAbort
      })
      .catch(() => {})
  if (browser) await browser.close()
  if (token) await request('/system/auth/logout', {}).catch(() => {})
}
