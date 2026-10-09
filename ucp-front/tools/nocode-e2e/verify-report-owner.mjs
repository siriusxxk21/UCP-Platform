import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })
const token = process.env.REPORT_TEST_TOKEN
const adminToken = process.env.REPORT_TEST_DATA_ADMIN_TOKEN
const ownerToken = process.env.REPORT_TEST_OWNER_TOKEN
const seedId = process.env.REPORT_TEST_DATASET
const roleId = process.env.REPORT_TEST_ROLE
assert.ok(token && adminToken && ownerToken && seedId && roleId)
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-owner', seedId)
await mkdir(output, { recursive: true })
async function request(path, body, sessionToken = token) {
  const response = await fetch(base + path, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: `Bearer ${sessionToken}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  })
  return response.json()
}
async function api(path, body, sessionToken = token) {
  const result = await request(path, body, sessionToken)
  assert.equal(result.code, 0, result.msg)
  return result.data
}
const original = await api('/nocode/report/dataset/get?id=' + seedId, undefined, ownerToken)
const info = await api('/system/auth/get-permission-info')
const adminInfo = await api('/system/auth/get-permission-info', undefined, adminToken)
assert.deepEqual(
  new Set(info.permissions.filter(Boolean)),
  new Set([
    'nocode:report:query',
    'nocode:report:create',
    'nocode:report:update',
    'nocode:report:publish',
    'nocode:report:manage',
    'nocode:report:authorize',
    'nocode:object:query'
  ])
)
assert.deepEqual(adminInfo.permissions.filter(Boolean), ['nocode:object:share'])
const object = await api('/nocode/report/dataset/source-object?id=' + original.draft.source.root.objectId)
const browser = await chromium.launch({
  headless: true,
  channel: 'chrome',
  args: [
    '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows'
  ]
})
const errors = [],
  checks = []
async function pageFor(sessionToken, identity) {
  const context = await browser.newContext({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
  const page = await context.newPage()
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
        style.textContent =
          '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }'
        document.head.appendChild(style)
      })
    },
    { token: sessionToken, info: identity }
  )
  return page
}
const page = await pageFor(token, info)
const admin = await pageFor(adminToken, adminInfo)
async function savePolicy(dialog, reason) {
  await dialog.getByRole('textbox', { name: '授权变更原因', exact: true }).fill(reason)
  await dialog.getByRole('button', { name: /保存授权$/ }).click()
  await expect(dialog.getByRole('textbox', { name: '授权变更原因', exact: true })).toHaveValue('')
}
async function preview(expected) {
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await page.getByRole('button', { name: /查询预览$/ }).click()
  if (expected === null)
    await expect(page.locator('.ant-tabs-tabpane-active .ant-alert-error')).toContainText('没有该操作权限')
  else await expect(page.getByText(`匹配 ${expected} 条记录，共 1 组，最多显示 100 组。`)).toBeVisible()
}
let id
try {
  await page.goto(origin + '/nocode/report-center/datasets')
  await page.getByRole('button', { name: /新建数据集/ }).click()
  const dialog = page.getByRole('dialog')
  const name = original.draft.name + ' 拥有者闭环'
  await dialog.getByRole('textbox', { name: '数据集名称', exact: true }).fill(name)
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page).toHaveURL(/dataset-editor\?id=/)
  id = new URL(page.url()).searchParams.get('id')
  await page.locator('#report-source-object').fill(object.name)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: object.name }).click()
  await page.getByRole('checkbox', { name: '选用字段0', exact: true }).check()
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await page.getByRole('button', { name: '添加指标', exact: true }).click()
  const saved = page.waitForResponse(
    r => r.url().endsWith('/nocode/report/dataset/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await saved).json()).code, 0)
  await page.reload()
  await expect(page.getByRole('checkbox', { name: '选用字段0', exact: true })).toBeChecked()
  assert.equal(String((await api('/nocode/report/dataset/get?id=' + id)).ownerId), String(info.user.id))
  await page.getByRole('button', { name: '授权管理', exact: true }).hover()
  await expect(page.getByRole('menuitem', { name: '对象授权上限', exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await preview(null)
  checks.push('普通拥有者从页面新建、选字段、保存重开；尚无对象上限时不能预览，也没有上限授权入口')

  // 上限由另一个仅有对象共享权的真实账号在独立页面设置。
  await admin.goto(origin + '/nocode/report-center/data-authorization')
  await admin.getByRole('textbox', { name: '搜索授权数据集', exact: true }).fill(name)
  await admin.getByRole('button', { name: /查\s*询/ }).click()
  await admin
    .getByRole('row')
    .filter({ has: admin.getByText(name, { exact: true }) })
    .getByRole('button', { name: '设置对象上限', exact: true })
    .click()
  const adminDialog = admin.getByRole('dialog')
  await adminDialog.getByRole('button', { name: '配置对象授权上限', exact: true }).click()
  await adminDialog.locator('[aria-label="可读取字段"] .ant-select-selector').click()
  await admin.getByTitle('字段0', { exact: true }).last().click()
  await savePolicy(adminDialog, '拥有者闭环：独立数据管理员设置上限')
  await adminDialog.getByRole('button', { name: /^取\s*消$/ }).click()
  assert.equal((await api('/nocode/report/dataset/ceilings?id=' + id))[0].permission.readFields.length, 1)
  assert.notEqual((await request('/nocode/report/dataset/get?id=' + id, undefined, adminToken)).code, 0)
  await preview(null)
  checks.push('独立数据管理员从页面定位普通用户新建的数据集并授予上限；上限不自动生成成员数据权')

  await page.getByRole('button', { name: '授权管理', exact: true }).hover()
  await page.getByRole('menuitem', { name: '成员数据权限', exact: true }).click()
  await dialog.getByRole('button', { name: '添加数据成员', exact: true }).click()
  await dialog.locator('[aria-label="成员类型"] .ant-select-selector').click()
  await page.getByTitle('系统角色', { exact: true }).last().click()
  await dialog.locator('[aria-label="成员角色"] .ant-select-selector').click()
  await page.getByTitle('报表普通制作验收', { exact: true }).last().click()
  await dialog.locator('[aria-label="可读取对象"] .ant-select-selector').click()
  await page.getByTitle(object.name, { exact: true }).last().click()
  await dialog.getByRole('textbox', { name: '授权变更原因', exact: true }).click()
  await dialog.getByRole('button', { name: new RegExp(object.name) }).click()
  await dialog.locator('[aria-label="可读取字段"] .ant-select-selector').click()
  await page.getByTitle('字段0', { exact: true }).last().click()
  await savePolicy(dialog, '拥有者闭环：显式成员读取授权')
  const policy = await api('/nocode/report/dataset/data-policy?id=' + id)
  assert.equal(policy.members.length, 1)
  assert.equal(policy.members[0].principalKind, 'ROLE')
  assert.equal(policy.members[0].principalId, roleId)
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await preview(3)
  checks.push('普通拥有者通过成员授权页面选择真实角色、对象和读取字段，授权后预览三条真实业务记录')

  await page.getByRole('button', { name: /^发\s*布$/ }).click()
  await dialog.getByRole('textbox', { name: '操作原因', exact: true }).fill('普通拥有者完整流程发布')
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  await page.reload()
  await expect(page.getByText('V1', { exact: true })).toBeVisible()
  await preview(3)
  const version = (await api('/nocode/report/dataset/releases?id=' + id)).list[0]
  const query = {
    datasetId: id,
    versionNo: version.versionNo,
    checksum: version.checksum,
    preview: false,
    dimensions: [],
    limit: 20
  }
  const result = await api('/nocode/report/dataset/query', query)
  assert.equal(result.recordCount, 3)
  assert.equal(result.canExport, false)
  await page.screenshot({ path: resolve(output, 'published.png'), fullPage: true })
  checks.push('同一普通用户新建的数据集完成授权、预览、V1 发布及刷新重开；发布查询真实返回三条且没有导出权')

  await page.getByRole('button', { name: '授权管理', exact: true }).hover()
  await page.getByRole('menuitem', { name: '成员数据权限', exact: true }).click()
  await dialog.getByRole('button', { name: '移除成员', exact: true }).click()
  await savePolicy(dialog, '拥有者闭环：撤销自身数据权')
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await preview(null)
  assert.notEqual((await request('/nocode/report/dataset/query', query)).code, 0)
  assert.equal((await api('/nocode/report/dataset/get?id=' + id)).publishedVersion, 1)
  checks.push('普通拥有者页面撤销自身成员权限后预览与 V1 查询拒绝，资源所有权和已发布版本仍保留')
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ id, checks, errors }, null, 2))
  console.log('Report owner browser: ' + checks.length + ' checks passed; ' + output)
} catch (e) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  await admin.screenshot({ path: resolve(output, 'admin-failure.png'), fullPage: true }).catch(() => {})
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ id, checks, errors, message: e.message }, null, 2))
  throw e
} finally {
  await browser.close()
}
