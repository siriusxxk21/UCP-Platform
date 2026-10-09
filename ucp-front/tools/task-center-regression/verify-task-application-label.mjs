import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅在明确允许维护已有自有员工凭据后运行；不新建或修改任务、应用及业务记录。
if (process.env.TASK_APPLICATION_LABEL_RUN !== '1') {
  console.log(JSON.stringify({ mode: 'prepare-only', writes: 0 }))
  process.exit(0)
}
assert.ok(process.env.TASK_APPLICATION_LABEL_MANIFEST, '必须显式指定已完成验收的精确 manifest')
const manifestPath = resolve(process.env.TASK_APPLICATION_LABEL_MANIFEST)
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
assert.equal(manifest.source, 'task-data-policy')
assert.match(manifest.prefix, /^dp[a-z0-9]+$/)
const actor = manifest.users.find(user => user.username === `${manifest.prefix}b`)
assert.ok(actor?.id, '只允许本清单 B 员工')
const previous = JSON.parse(await readFile(resolve(dirname(manifestPath), 'result.json'), 'utf8'))
assert.equal(previous.checks.filter(check => check.status === 'passed').length, 20)
const rootId = previous.checks.find(check => check.name.startsWith('owner 配置')).detail.rootId
assert.ok(manifest.taskIds.includes(rootId))
const output = resolve(dirname(manifestPath), `application-label-followup-${Date.now()}`)
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api', output)
const expect = playwrightExpect.configure({ timeout: 20000 })
const report = {
  sourceManifest: manifestPath,
  userId: actor.id,
  username: actor.username,
  credentialResets: 0,
  requiredPasswordChanges: 0,
  accountDisabled: false,
  businessWrites: 0,
  blockedWrites: [],
  browserErrors: [],
  screenshots: []
}
let browser,
  verifiedActor = false,
  password,
  token,
  taskId
try {
  await ac.login()
  const user = await ac.api(`/system/user/get?id=${actor.id}`)
  assert.equal(String(user.id), actor.id)
  assert.equal(user.username, actor.username)
  assert.equal(user.status, 1, '恢复前必须为已停用自有夹具员工')
  verifiedActor = true
  const group = await ac.api('/nocode/tasks/detail', { id: rootId })
  const task = group.nodes.find(node => node.title === `${manifest.prefix} 施工复核`)
  assert.ok(task && manifest.taskIds.includes(task.id))
  assert.equal(String(task.assigneeId), actor.id)
  assert.equal(task.status, 'COMPLETED')
  assert.ok(manifest.applications.some(app => app.id === task.applicationId))
  taskId = task.id
  report.taskId = taskId
  report.beforeRevision = task.revision
  password = `Fa9${randomBytes(6).toString('hex')}`
  await ac.api('/system/user/update-password', { id: actor.id, password }, undefined, 'PUT')
  report.credentialResets++
  await ac.api('/system/user/update-status', { id: actor.id, status: 0 }, undefined, 'PUT')
  let session = await ac.api('/system/auth/login', { username: actor.username, password }, null)
  if (session.loginStatus === 'PASSWORD_CHANGE_REQUIRED') {
    password = `Fa9${randomBytes(6).toString('hex')}`
    session = await ac.api(
      '/system/auth/change-required-password',
      {
        passwordChangeToken: session.passwordChangeToken,
        newPassword: password
      },
      null,
      'PUT'
    )
    report.requiredPasswordChanges++
  }
  assert.ok(session.accessToken)
  token = session.accessToken
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  const apps = await ac.api('/nocode/runtime/mine', undefined, token)
  assert.ok(!apps.some(app => app.id === task.applicationId), '员工没有被补发原应用访问权限')
  browser = await chromium.launch({ channel: process.env.TASK_CENTER_BROWSER || 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.on('pageerror', error => report.browserErrors.push(error.message))
  await page.addInitScript(
    ({ token, info }) => {
      const flatten = (items, parentId = 0) =>
        items.flatMap((menu, index) =>
          menu.visible === false
            ? []
            : [
                {
                  id: menu.id,
                  name: menu.name,
                  path: menu.path || '',
                  component: menu.component,
                  icon: menu.icon,
                  parentId: menu.parentId ?? parentId,
                  sort: menu.sort ?? index
                },
                ...flatten(menu.children || [], menu.id)
              ]
        )
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(flatten(info.menus || [])))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info }
  )
  const reads = new Set([
    '/nocode/tasks/page',
    '/nocode/tasks/detail',
    '/nocode/tasks/readiness',
    '/nocode/tasks/entries/list',
    '/nocode/tasks/entries/page',
    '/nocode/tasks/entries/form',
    '/nocode/tasks/entries/materials'
  ])
  await page.route('**/api/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || reads.has(path)) return route.continue()
    report.blockedWrites.push({ path, method: request.method() })
    return route.abort('blockedbyclient')
  })
  const applicationResponse = page.waitForResponse(response => response.url().includes('/nocode/runtime/mine'))
  await page.goto(`${process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'}/nocode-app/task-center?taskId=${taskId}`)
  await applicationResponse
  await page.getByRole('tab', { name: '任务信息', exact: true }).click()
  const descriptions = page.locator('.task-hierarchy-drawer .ant-descriptions')
  const value = label =>
    descriptions
      .locator('.ant-descriptions-item-label')
      .filter({ hasText: new RegExp(`^${label}$`) })
      .locator('xpath=following-sibling::*[1]')
  await expect(value('所属应用')).toHaveText('所属应用当前不可访问')
  await expect(descriptions).not.toContainText('正在读取')
  await expect(value('预计开始')).toHaveText('2000-01-03 09:00')
  await expect(value('预计结束')).toHaveText('2000-01-04 09:00')
  await expect(value('实际开始')).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
  await expect(value('实际结束')).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
  await expect(page.locator('.task-detail__actions')).toContainText('已完成')
  await expect(page.locator('.ant-message-notice')).toHaveCount(0)
  const screenshot = resolve(output, 'completed-task-application-fallback.png')
  await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
  report.screenshots.push(screenshot)
  const after = (await ac.api('/nocode/tasks/detail', { id: taskId })).task
  assert.equal(after.revision, task.revision)
  assert.equal(after.status, 'COMPLETED')
  assert.deepEqual(report.blockedWrites, [])
  assert.deepEqual(report.browserErrors, [])
  report.passed = true
} catch (error) {
  report.passed = false
  report.error = error.stack || error.message
} finally {
  await browser?.close()
  if (verifiedActor) {
    try {
      await ac.api('/system/user/update-status', { id: actor.id, status: 1 }, undefined, 'PUT')
      const user = await ac.api(`/system/user/get?id=${actor.id}`)
      assert.equal(user.username, actor.username)
      assert.equal(user.status, 1)
      report.accountDisabled = true
    } catch (error) {
      report.passed = false
      report.cleanupError = error.message
    }
  }
  password = undefined
  token = undefined
  ac.tokens = {}
  await writeFile(resolve(output, 'result.json'), JSON.stringify(report, null, 2))
  console.log(
    JSON.stringify(
      {
        output,
        passed: report.passed,
        credentialResets: report.credentialResets,
        accountDisabled: report.accountDisabled,
        error: report.error,
        cleanupError: report.cleanupError
      },
      null,
      2
    )
  )
  process.exitCode = report.passed && report.accountDisabled ? 0 : 1
}
