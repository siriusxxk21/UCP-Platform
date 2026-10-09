import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只检查现有体验应用的配置呈现；不应用到草稿、不保存、不发布、不创建任务。
const root = resolve(process.env.CONSTRUCTION_OUTPUT || '.work/construction-experience')
const output = resolve(root, 'task-form-labels')
const origin = process.env.CONSTRUCTION_URL || 'http://127.0.0.1:5173'
const ac = new FormAcceptance(process.env.CONSTRUCTION_API || 'http://127.0.0.1:8080/api', output)
const checks = [],
  errors = [],
  blockedWrites = []
let browser, page, failure
await mkdir(output, { recursive: true })
try {
  const manifest = JSON.parse(await readFile(resolve(root, 'manifest.json'), 'utf8'))
  await ac.login()
  const before = await ac.api('/nocode/application/get?id=' + manifest.applicationId)
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && !['/nocode/runtime/selection'].includes(path)) {
      blockedWrites.push({ path, method: request.method() })
      await route.abort('blockedbyclient')
    } else await route.continue()
  })
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
  await page.goto(origin + '/nocode-app/workspace?id=' + manifest.applicationId)
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务页面' }).click()
  await page
    .locator('.resources .ant-table-row')
    .filter({ has: page.getByRole('cell', { name: 'project_detail', exact: true }) })
    .getByRole('button', { name: /配置$/ })
    .click()
  await expect(page.getByRole('button', { name: '应用到草稿', exact: true })).toBeEnabled({ timeout: 60000 })
  const canvas = page.frame({ url: /canvas.html/ })
  assert.ok(canvas)
  await canvas.getByText('本项目任务', { exact: true }).click()
  const taskBlock = canvas.locator('.os-block').filter({ has: canvas.getByText('本项目任务', { exact: true }) })
  await expect(taskBlock).toContainText('任务名称')
  await expect(taskBlock).toContainText('执行人')
  await expect(taskBlock).toContainText('执行状态')
  await expect(taskBlock).toContainText('运行时展示当前范围内有权查看的任务')
  await expect(taskBlock).not.toContainText('在右侧选择业务资源')
  const properties = page.locator('.page-designer .properties')
  const scopeItem = properties
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^任务范围$/ }) })
  await expect(scopeItem).toBeVisible()
  await expect(scopeItem).toContainText('当前页面记录')
  await expect(scopeItem.getByRole('combobox')).toHaveCount(0)
  await expect(properties).not.toContainText('任务关联表单')
  await expect(properties).not.toContainText('当前记录表单')
  await expect(properties).not.toContainText('任务关联范围：')
  await page.screenshot({ path: resolve(output, 'current-record-scope.png'), fullPage: true })
  checks.push('真实设计器显示只读任务范围：当前页面记录；无任务关联表单选择器；任务画布展示任务字段和范围提示')
  assert.deepEqual(await ac.api('/nocode/application/get?id=' + manifest.applicationId), before)
  assert.deepEqual(errors, [])
  assert.deepEqual(blockedWrites, [])
  checks.push('应用草稿、发布版本、修订与全部配置前后一致，无写请求和页面异常')
} catch (error) {
  failure = error.stack || error.message
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  process.exitCode = 1
} finally {
  await browser?.close()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ time: new Date().toISOString(), checks, errors, blockedWrites, failure }, null, 2)
  )
  console.log(JSON.stringify({ output, checks, errors, blockedWrites, failure }, null, 2))
}
