import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'

// HTTP 测试负责创建仅持对象共享权限的真实账号；不在浏览器伪造菜单、权限或接口结果。
const token = process.env.REPORT_TEST_TOKEN
const ownerToken = process.env.REPORT_TEST_OWNER_TOKEN
const id = process.env.REPORT_TEST_DATASET
assert.ok(token && ownerToken && id, '必须由报表 HTTP 测试提供受限账号与夹具')
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-data-admin', id)
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
const info = await api('/system/auth/get-permission-info')
assert.ok(info.permissions.includes('nocode:object:share'))
assert.ok(!info.permissions.some(p => p.startsWith('nocode:report:') || p === 'nocode:object:query'))
const original = await api('/nocode/report/dataset/get?id=' + id, undefined, ownerToken)
const release = (await api('/nocode/report/dataset/releases?id=' + id, undefined, ownerToken)).list[0]
const query = {
  datasetId: id,
  preview: false,
  versionNo: release.versionNo,
  checksum: release.checksum,
  dimensions: [],
  metricIds: ['rows'],
  limit: 20
}
const originalCeiling = (await api('/nocode/report/dataset/ceilings?id=' + id))[0]
const candidate = (await api('/nocode/report/dataset/authorization-objects?id=' + id)).find(
  o => o.id === originalCeiling.objectId
)
assert.ok(candidate?.definition)
const field = candidate.definition.fields.find(f => f.id === originalCeiling.permission.readFields[0])
assert.ok(field)
const browser = await chromium.launch({ headless: true, channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
page.setDefaultTimeout(20000)
const errors = [],
  checks = [],
  forbiddenRequests = []
page.on('pageerror', e => errors.push(e.message))
page.on('request', r => {
  const path = new URL(r.url()).pathname
  if (/\/nocode\/report\/dataset\/(get|page|source-objects?|query|data-policy|resource-policy)$/.test(path))
    forbiddenRequests.push(path)
})
try {
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
    { token, info }
  )
  await page.goto(origin + '/nocode/report-center/data-authorization')
  await expect(page.getByText('报表中心', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('数据授权', { exact: true }).first()).toBeVisible()
  await page.getByRole('textbox', { name: '搜索授权数据集', exact: true }).fill(original.draft.name)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  const row = page.getByRole('row').filter({ has: page.getByText(original.draft.name, { exact: true }) })
  await row.getByRole('button', { name: '设置对象上限', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('对象授权上限', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('checkbox', { name: '读取', exact: true })).toBeChecked()
  await dialog.getByRole('textbox', { name: '授权变更原因', exact: true }).fill('纯数据管理员浏览器保存')
  await dialog.getByRole('button', { name: '保存授权', exact: true }).click()
  await expect(dialog.getByRole('textbox', { name: '授权变更原因', exact: true })).toHaveValue('')
  assert.equal((await api('/nocode/report/dataset/ceilings?id=' + id))[0].revision, originalCeiling.revision + 1)
  checks.push('仅对象共享权限的真实账号从独立菜单搜索数据集并保存上限，未访问报表草稿或制作接口')

  await dialog.getByRole('button', { name: '撤销对象上限', exact: true }).click()
  await page.getByRole('button', { name: /^确\s*定$/ }).click()
  await dialog.getByRole('textbox', { name: '授权变更原因', exact: true }).fill('纯数据管理员浏览器撤权')
  await dialog.getByRole('button', { name: '保存授权', exact: true }).click()
  await expect(dialog.getByRole('textbox', { name: '授权变更原因', exact: true })).toHaveValue('')
  assert.equal((await api('/nocode/report/dataset/ceilings?id=' + id))[0].permission, null)
  assert.notEqual((await request('/nocode/report/dataset/query', query, ownerToken)).code, 0)
  checks.push('页面撤销上限后，原有已发布数据集查询立即被拒绝')

  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await page.reload()
  await page.getByRole('textbox', { name: '搜索授权数据集', exact: true }).fill(original.draft.name)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await row.getByRole('button', { name: '设置对象上限', exact: true }).click()
  await dialog.getByRole('button', { name: '配置对象授权上限', exact: true }).click()
  await dialog.locator('[aria-label="可读取字段"] .ant-select-selector').click()
  await page.getByTitle(field.name, { exact: true }).last().click()
  await dialog.getByRole('textbox', { name: '授权变更原因', exact: true }).fill('纯数据管理员浏览器恢复')
  await dialog.getByRole('button', { name: '保存授权', exact: true }).click()
  await expect(dialog.getByRole('textbox', { name: '授权变更原因', exact: true })).toHaveValue('')
  assert.equal((await api('/nocode/report/dataset/query', query, ownerToken)).recordCount, 2)
  assert.notEqual((await request('/nocode/report/dataset/query', query)).code, 0)
  assert.notEqual((await request('/nocode/report/dataset/get?id=' + id)).code, 0)
  checks.push('刷新后撤销状态保留；从页面重新选字段授权恢复查询；数据管理员自身仍不能查询或读取草稿')
  assert.deepEqual(forbiddenRequests, [])
  assert.deepEqual(errors, [])
  await page.screenshot({ path: resolve(output, 'authorization.png'), fullPage: true })
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors, forbiddenRequests }, null, 2))
  console.log('Report data admin browser: ' + checks.length + ' checks passed; ' + output)
} catch (e) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  throw e
} finally {
  await browser.close()
}
