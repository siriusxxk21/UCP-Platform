/** C5-1：正式最小查看身份；仅临时加入自己的成员，finally 合并移除，不覆盖其他授权变化。 */
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { chromium, expect as playwrightExpect } from '@playwright/test'

const expect = playwrightExpect.configure({ timeout: 20000 })
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const originalManifest = await readFile('.work/report-demo/current.json', 'utf8')
const manifest = JSON.parse(originalManifest)
assert.equal(String(manifest.dashboardId), '5')
assert.equal(String(manifest.datasetId), '981')
assert.deepEqual(manifest.objectIds.map(String), ['5915', '5914', '5913'])
assert.match(manifest.prefix, /^test_b1_[a-f0-9]+$/)
const prefix = 'rptC5' + randomBytes(8).toString('hex')
const output = resolve('.work/report-demo/c5-sharing', prefix)
await mkdir(output, { recursive: true })
let token, viewerToken, roleId, userId, browser, page, failure
let resourceTouched = false,
  dataTouched = false
const checks = [],
  errors = [],
  cleanupErrors = []
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
async function request(path, body, session = token, method = body === undefined ? 'GET' : 'POST') {
  return fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(session ? { Authorization: 'Bearer ' + session } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}
async function api(path, body, session = token, method) {
  const result = await (await request(path, body, session, method)).json()
  assert.equal(result.code, 0, path + ': ' + result.msg)
  return result.data
}
async function denied(path, body, session = viewerToken) {
  const response = await request(path, body, session)
  assert.ok(response.headers.get('content-type')?.includes('json'), path + ': 拒绝不得交付文件')
  const result = await response.json()
  assert.equal(result.code, 403, path + ': 应由权限拒绝，实际 ' + result.code + '/' + result.msg)
}
const self = member => member.principalKind === 'USER' && String(member.principalId) === String(userId)
// 每次先读最新修订，只替换本次USER；冲突重读，其他人的并发变化始终保留。
async function changePolicy(kind, member) {
  const path = '/nocode/report/' + (kind === 'resource' ? 'dashboard/resource-policy' : 'dataset/data-policy')
  for (let attempt = 0; attempt < 3; attempt++) {
    const current = await api(path + '?id=' + (kind === 'resource' ? '5' : '981'))
    const members = current.members.filter(item => !self(item))
    if (member) members.push(member)
    if (kind === 'resource') resourceTouched = true
    else dataTouched = true
    const result = await (
      await request(path, {
        ...(kind === 'resource' ? { id: '5' } : { datasetId: '981' }),
        expectedRevision: current.revision,
        members,
        reason: prefix + ' 最小查看身份验收/合并恢复'
      })
    ).json()
    if (result.code === 0) return result.data
    if (attempt === 2 || !/修订|冲突|更新|重试/.test(result.msg || ''))
      assert.equal(result.code, 0, path + ': ' + result.msg)
  }
}
async function checkpoint() {
  await writeFile(
    resolve(output, 'fixture.json'),
    JSON.stringify({ prefix, userId, roleId, dashboardId: '5', datasetId: '981' }, null, 2)
  )
}
try {
  token = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD }, null))
    .accessToken
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  assert.ok(token)
  const info = await api('/system/auth/get-permission-info')
  const board = await api('/nocode/report/dashboard/get?id=5')
  const dataset = await api('/nocode/report/dataset/get?id=981')
  const release = await api('/nocode/report/dashboard/published?id=5')
  assert.equal(board.draft.name, '经营分析 · 核心体验看板')
  assert.equal(release.content.name, board.draft.name)
  assert.equal(dataset.draft.name, '报表体验 · 经营分析数据集')
  assert.equal(dataset.draft.source.root.objectId, '5915')
  assert.equal(String(board.ownerId), String(info.user.id), '必须由已登记看板拥有者执行')
  assert.ok(release.content.charts.every(chart => chart.dataset.id === '981'))
  const policy = await api('/nocode/report/dataset/data-policy?id=981')
  const owner = policy.members.find(
    member => member.principalKind === 'USER' && String(member.principalId) === String(dataset.ownerId)
  )
  assert.ok(owner?.objects.length, '必须复制已有拥有者精确数据grant，不能另造范围')
  assert.ok(owner.objects.every(grant => manifest.objectIds.includes(grant.objectId) && grant.actions.includes('READ')))
  assert.ok(
    owner.objects.every(grant => grant.actions.includes('EXPORT')),
    '夹具需已有数据EXPORT范围'
  )
  const datasetAcl = await api('/nocode/report/dataset/resource-policy?id=981')
  assert.ok(
    datasetAcl.members.every(member => !member.actions.includes('EXPORT')),
    '数据集资源动作不包含EXPORT'
  )
  const originalPolicy = await api('/nocode/report/dashboard/resource-policy?id=5')
  const metric = release.content.charts.find(chart => chart.display === 'METRIC')
  const table = release.content.charts.find(
    chart => chart.display === 'TABLE' && chart.links?.length && chart.drillDimensions?.length === 2
  )
  const companyFilter = release.content.filters.find(filter => filter.name === '公司筛选')
  assert.ok(metric && table && companyFilter, '必须保留C3真实公共筛选/联动/下钻配置')
  const query = {
    id: '5',
    chartId: metric.id,
    preview: false,
    versionNo: release.versionNo,
    checksum: release.checksum
  }
  assert.equal(Number((await api('/nocode/report/dashboard/query', query)).totals[metric.metricIds[0]]), 19.25)
  roleId = await api('/system/role/create', {
    name: prefix,
    code: prefix,
    sort: 999,
    status: 0,
    remark: '报表C5临时最小查看角色'
  })
  await checkpoint()
  const menus = await api('/system/menu/list')
  const menuIds = menus
    .filter(menu => menu.permission === 'nocode:report:query' || menu.path === '/nocode/report-center')
    .map(menu => menu.id)
  assert.ok(menuIds.length, '底座缺少报表查询权限菜单')
  await api('/system/permission/assign-role-menu', { roleId, menuIds })
  let password = 'T9' + randomBytes(7).toString('hex')
  userId = await api('/system/user/create', {
    username: prefix,
    nickname: prefix,
    password,
    remark: '报表C5临时最小查看身份'
  })
  await checkpoint()
  assert.ok(!originalPolicy.members.some(self) && !policy.members.some(self))
  await api('/system/permission/assign-user-role', { userId, roleIds: [roleId] })
  let login = await api('/system/auth/login', { username: prefix, password }, null)
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
  assert.equal(String(viewer.user.id), String(userId))
  assert.deepEqual(new Set(viewer.permissions.filter(Boolean)), new Set(['nocode:report:query']))
  const dataMember = exporting => ({
    principalKind: 'USER',
    principalId: String(userId),
    objects: owner.objects.map(original => {
      const grant = structuredClone(original)
      grant.actions = exporting ? ['READ', 'EXPORT'] : ['READ']
      grant.actionScopes = Object.fromEntries(
        Object.entries(grant.actionScopes || {}).filter(([action]) => grant.actions.includes(action))
      )
      return grant
    })
  })
  const resourceMember = exporting => ({
    principalKind: 'USER',
    principalId: String(userId),
    actions: exporting ? ['VIEW', 'EXPORT'] : ['VIEW']
  })
  await changePolicy('data', dataMember(false))
  await changePolicy('resource', resourceMember(false))
  const availablePath =
    '/nocode/report/dashboard/available-page?pageNo=1&pageSize=100&search=' + encodeURIComponent(release.content.name)
  const available = await api(availablePath, undefined, viewerToken)
  const row = available.list.find(item => item.id === '5')
  assert.ok(row)
  assert.equal(row.name, release.content.name)
  assert.equal(row.revision, null)
  assert.equal(row.modified, false)
  assert.ok(!('draft' in row) && !('checksum' in row))
  assert.equal(row.capabilities.canView, true)
  for (const capability of ['canEdit', 'canPublish', 'canGrant', 'canExport'])
    assert.equal(row.capabilities[capability], false)
  assert.equal(
    (await api('/nocode/report/dashboard/published?id=5', undefined, viewerToken)).checksum,
    release.checksum
  )
  const initialQuery = await api('/nocode/report/dashboard/query', query, viewerToken)
  assert.equal(Number(initialQuery.totals[metric.metricIds[0]]), 19.25)
  assert.equal(initialQuery.canExport, false)
  await denied('/nocode/report/dashboard/get?id=5')
  await denied('/nocode/report/dashboard/query', { ...query, preview: true, versionNo: undefined, checksum: undefined })
  await denied('/nocode/report/dashboard/resource-policy?id=5')
  await denied('/nocode/report/dataset/get?id=981')
  await denied('/nocode/report/dataset/query', {
    datasetId: '981',
    preview: false,
    versionNo: metric.dataset.versionNo,
    checksum: metric.dataset.checksum,
    metricIds: metric.metricIds,
    dimensions: [],
    limit: 200
  })
  checks.push(
    '真实最小report query角色；仅看板VIEW+精确数据READ即可摘要/发布组件取数，草稿/preview/授权管理/直接数据集均拒绝，无Dataset USE/VIEW_META'
  )
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
    { token: viewerToken, info: viewer }
  )
  const tile = title =>
    page.locator('.dashboard-tile').filter({ has: page.getByRole('heading', { name: title, exact: true }) })
  const amount = value => expect(tile(metric.title).locator('.dashboard-metrics strong').first()).toHaveText(value)
  async function action(name) {
    const radio = tile(table.title).getByRole('radio', { name, exact: true })
    await tile(table.title).getByText(name, { exact: true }).click()
    await expect(radio).toBeChecked()
  }
  const cell = value =>
    tile(table.title)
      .locator('tbody button')
      .filter({ hasText: new RegExp('^' + value.replace('.', '\\.') + '$') })
      .first()
  await page.goto(origin + '/nocode/report-center/dashboards')
  const listRow = page.locator('tr[data-row-key="5"]').first()
  await expect(listRow.getByRole('button', { name: '打开看板', exact: true })).toBeVisible()
  await expect(listRow.getByRole('button', { name: /^设\s*计$/ })).toHaveCount(0)
  await expect(listRow.getByRole('button', { name: '协作权限', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新建仪表板', exact: true })).toHaveCount(0)
  await listRow.getByRole('button', { name: '打开看板', exact: true }).click()
  await expect(page).toHaveURL(/dashboard-view\?id=5/)
  await amount('19.25')
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  await expect(tile(metric.title).getByRole('button', { name: '导出 Excel', exact: true })).toBeDisabled()
  const candidateResponse = page.waitForResponse(
    response =>
      response.url().endsWith('/dashboard/options') && response.request().postDataJSON().filterId === companyFilter.id
  )
  await page.locator('[aria-label="公司筛选"] .ant-select-selector').click()
  const candidates = (await (await candidateResponse).json()).data.list
  assert.ok(!candidates.some(option => option.value === '隐藏公司'))
  const companyIndex = candidates.findIndex(option => option.value === '可见公司')
  assert.ok(companyIndex >= 0)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').nth(companyIndex).click()
  await page.getByRole('heading', { name: release.content.name, exact: true }).click()
  await amount('10.25')
  await page.getByRole('button', { name: '重置筛选', exact: true }).click()
  await amount('19.25')
  await action('点击联动')
  await cell('9.00').click()
  await amount('9.00')
  const linkedQuery = { ...query, selections: [{ chartId: table.id, group: [null] }] }
  const linked = await api('/nocode/report/dashboard/query', linkedQuery, viewerToken)
  assert.equal(Number(linked.totals[metric.metricIds[0]]), 9)
  assert.equal(linked.canExport, false)
  const nullOptions = await api(
    '/nocode/report/dashboard/options',
    { query: linkedQuery, filterId: companyFilter.id, pageNo: 1, pageSize: 20 },
    viewerToken
  )
  assert.deepEqual(
    nullOptions.list.map(option => option.value),
    [null]
  )
  const detailsBody = {
    query: linkedQuery,
    group: [],
    columnGroup: [],
    metricId: metric.metricIds[0],
    pageNo: 1,
    pageSize: 20
  }
  const detailResponse = page.waitForResponse(response => response.url().endsWith('/dashboard/details'))
  await tile(metric.title).getByRole('button', { name: '查看明细', exact: true }).click()
  const details = (await (await detailResponse).json()).data
  assert.equal(details.total, 2)
  await expect(page.locator('.ant-drawer:visible')).toContainText('共 2 条')
  await expect(page.locator('.ant-drawer:visible').getByRole('button', { name: /^编\s*辑$/ })).toHaveCount(0)
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  await denied('/nocode/report/dashboard/export', linkedQuery)
  checks.push(
    '普通viewer真实UI列表仅打开入口；公共筛选19.25→10.25与受控候选，NULL原键联动9.00、同字段候选及两条只读明细保持范围'
  )
  await page.getByRole('button', { name: '清除联动', exact: true }).click()
  await amount('19.25')
  await action('层级下钻')
  await cell('10.25').click()
  await expect(tile(table.title).getByRole('columnheader', { name: '状态', exact: true })).toBeVisible()
  await cell('10.25').click()
  await expect(tile(table.title).getByRole('columnheader', { name: '业务日期', exact: true })).toBeVisible()
  await expect(tile(table.title)).toContainText('2026-01')
  const drilledResponse = page.waitForResponse(response => response.url().endsWith('/dashboard/details'))
  await cell('10.25').click()
  const drilled = await drilledResponse
  assert.deepEqual(drilled.request().postDataJSON().query.drillPath, ['可见公司', 'OPEN'])
  assert.equal((await drilled.json()).data.total, 1)
  await expect(page.locator('.ant-drawer:visible')).toContainText('共 1 条')
  await page.locator('.ant-drawer:visible .ant-drawer-close').click()
  await tile(table.title).getByRole('button', { name: '全部', exact: true }).click()
  await expect(tile(table.title).getByRole('columnheader', { name: '公司', exact: true })).toBeVisible()
  await action('点击联动')
  await cell('9.00').click()
  await amount('9.00')
  checks.push('普通VIEW身份公司→状态→月份三层真实下钻、原键路径与单条明细；上卷恢复，未获得制作权限')
  async function exportCapability(expected) {
    const result = await api('/nocode/report/dashboard/query', linkedQuery, viewerToken)
    assert.equal(Number(result.totals[metric.metricIds[0]]), 9)
    assert.equal(result.canExport, expected)
    if (!expected) await denied('/nocode/report/dashboard/export', linkedQuery)
    await page.getByRole('button', { name: '刷新数据', exact: true }).click()
    await amount('9.00')
    const button = tile(metric.title).getByRole('button', { name: '导出 Excel', exact: true })
    if (expected) await expect(button).toBeEnabled()
    else await expect(button).toBeDisabled()
  }
  await changePolicy('data', dataMember(true))
  await exportCapability(false)
  await changePolicy('data', dataMember(false))
  await changePolicy('resource', resourceMember(true))
  await exportCapability(false)
  await changePolicy('data', dataMember(true))
  await exportCapability(true)
  const download = page.waitForEvent('download')
  const exportResponse = page.waitForResponse(response => response.url().endsWith('/dashboard/export'))
  await tile(metric.title).getByRole('button', { name: '导出 Excel', exact: true }).click()
  const exported = await exportResponse
  assert.ok(exported.headers()['content-type'].includes('spreadsheet'))
  assert.deepEqual(exported.request().postDataJSON().selections, linkedQuery.selections)
  const xlsx = resolve(output, 'viewer-null.xlsx')
  await (await download).saveAs(xlsx)
  const xml = (await promisify(execFile)('/usr/bin/unzip', ['-p', xlsx, 'xl/worksheets/sheet1.xml'])).stdout
  assert.ok(xml.includes('<t>9.00</t>') && xml.includes('来源记录 2 条'))
  assert.ok(!xml.includes('<f>'))
  await page.screenshot({ path: resolve(output, 'viewer.png'), fullPage: true })
  await changePolicy('resource', resourceMember(false))
  await exportCapability(false)
  checks.push(
    'EXPORT四组合及撤销：看板EXPORT或数据EXPORT单独存在均拒绝；二者同时存在真实UI下载精确文本9.00/2条XLSX，撤销看板EXPORT后READ继续可用'
  )
  await changePolicy('resource', null)
  assert.ok(!(await api(availablePath, undefined, viewerToken)).list.some(item => item.id === '5'))
  for (const [path, body] of [
    ['/nocode/report/dashboard/published?id=5', undefined],
    ['/nocode/report/dashboard/query', linkedQuery],
    ['/nocode/report/dashboard/options', { query: linkedQuery, filterId: companyFilter.id, pageNo: 1, pageSize: 20 }],
    ['/nocode/report/dashboard/details', detailsBody],
    ['/nocode/report/dashboard/export', linkedQuery]
  ])
    await denied(path, body)
  await page.getByRole('button', { name: '刷新数据', exact: true }).click()
  await expect(page.locator('.dashboard-page .ant-alert')).toContainText(/权限|访问/)
  await expect(page.locator('.dashboard-tile')).toHaveCount(0)
  await page.screenshot({ path: resolve(output, 'revoked.png'), fullPage: true })
  await page.goto(origin + '/nocode/report-center/dashboards')
  await expect(page.locator('tr[data-row-key="5"]')).toHaveCount(0)
  assert.equal(String((await api('/system/auth/get-permission-info', undefined, viewerToken)).user.id), String(userId))
  checks.push(
    '撤销VIEW即时生效：摘要不再返回，published/query/options/details/export均403，已打开UI刷新显示权限错误并清除图表；会话仍有效'
  )
  const afterDatasetAcl = await api('/nocode/report/dataset/resource-policy?id=981')
  assert.ok(
    !afterDatasetAcl.members.some(
      member => self(member) || (member.principalKind === 'ROLE' && String(member.principalId) === String(roleId))
    )
  )
  const after = await api('/nocode/report/dashboard/get?id=5')
  assert.equal(after.revision, board.revision)
  assert.deepEqual(after.draft, board.draft)
  assert.equal((await api('/nocode/report/dashboard/published?id=5')).checksum, release.checksum)
  assert.equal(await readFile('.work/report-demo/current.json', 'utf8'), originalManifest)
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error.message
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
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
  if (browser) await clean('关闭浏览器', () => browser.close())
  if (userId && token && resourceTouched)
    await clean('仅移除自己看板成员', async () => {
      const policy = await changePolicy('resource', null)
      assert.ok(!policy.members.some(self))
    })
  if (userId && token && dataTouched)
    await clean('仅移除自己数据成员', async () => {
      const policy = await changePolicy('data', null)
      assert.ok(!policy.members.some(self))
    })
  if (viewerToken) await clean('注销查看会话', () => api('/system/auth/logout', {}, viewerToken))
  viewerToken = undefined
  if (userId && token)
    await clean('删除自己临时账号', async () => {
      assert.equal((await api('/system/user/get?id=' + userId)).username, prefix)
      await api('/system/user/delete?id=' + userId, undefined, token, 'DELETE')
    })
  if (roleId && token)
    await clean('删除自己临时角色', async () => {
      assert.equal((await api('/system/role/get?id=' + roleId)).code, prefix)
      await api('/system/role/delete?id=' + roleId, undefined, token, 'DELETE')
    })
  if (token) await clean('注销验证会话', () => api('/system/auth/logout', {}))
  token = undefined
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        prefix,
        dashboardId: '5',
        datasetId: '981',
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
    'C5 sharing: ' +
      checks.length +
      ' groups; ' +
      (!failure && !cleanupErrors.length ? 'PASSED' : 'FAILED') +
      '; evidence ' +
      output
  )
}
assert.deepEqual(cleanupErrors, [], '临时身份/授权清理必须全部完成')
assert.equal(failure, undefined, failure)
