import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// 使用当前开发服务及既有测试账号，仅创建/撤销本次登录会话；凭证不落盘。
const origin = process.env.AUTH_VERIFY_URL || 'http://127.0.0.1:5173'
const out = resolve('.work/auth-regression')
const env = Object.fromEntries(
  (await readFile('.env.test', 'utf8'))
    .split(/\r?\n/)
    .filter(line => /^E2E_(USERNAME|PASSWORD)=/.test(line))
    .map(line => {
      const pos = line.indexOf('=')
      return [
        line.slice(0, pos),
        line
          .slice(pos + 1)
          .trim()
          .replace(/^['"]|['"]$/g, '')
      ]
    })
)
const checks = []
let browser, context, page, refreshCredential
const record = name => {
  checks.push({ name, passed: true })
  console.log('PASS ' + name)
}
const protectedInfo = page =>
  page.evaluate(async () => {
    const response = await fetch('/api/system/auth/get-permission-info', {
      headers: { Authorization: 'Bearer ' + localStorage.getItem('token') }
    })
    const result = await response.json()
    return result.code
  })
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' })
  context = await browser.newContext()
  page = await context.newPage()
  await page.goto(origin + '/login')
  await page.getByPlaceholder('请输入用户名').fill(env.E2E_USERNAME)
  await page.getByPlaceholder('请输入密码').fill(env.E2E_PASSWORD)
  await page.locator('button[type=submit]').click()
  await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30000 })
  refreshCredential = await page.evaluate(() => localStorage.getItem('refreshToken'))
  assert.ok(refreshCredential)
  assert.equal(await page.evaluate(() => sessionStorage.getItem('refreshToken')), null)
  assert.equal(await protectedInfo(page), 0)
  record('真实登录后持久化刷新凭证，业务接口可访问')

  // storageState 仅留内存：销毁浏览器进程后恢复 localStorage，不恢复 sessionStorage。
  const state = await context.storageState()
  await browser.close()
  browser = await chromium.launch({ headless: true, channel: 'chrome' })
  context = await browser.newContext({ storageState: state })
  page = await context.newPage()
  await page.goto(origin + '/dashboard')
  await expect(page).toHaveURL(/\/dashboard$/)
  assert.equal(await protectedInfo(page), 0)
  record('关闭并重新启动浏览器后无需重新登录')

  await page.evaluate(() => {
    sessionStorage.setItem('lastActivityAt', '1')
    localStorage.setItem('expiresTime', '2000-01-01T00:00:00')
  })
  const oldAccess = await page.evaluate(() => localStorage.getItem('token'))
  await page.reload()
  await expect.poll(() => page.evaluate(() => localStorage.getItem('token'))).not.toBe(oldAccess)
  assert.equal(await protectedInfo(page), 0)
  record('模拟长时间闲置和访问凭证到期，真实续期接口恢复访问')

  // 强制访问凭证失效，并暂时阻断续期；恢复网络后仍可使用原刷新凭证。
  await page.evaluate(() => {
    localStorage.setItem('token', 'auth-verification-expired-access')
    localStorage.setItem('expiresTime', '2000-01-01T00:00:00')
  })
  await context.route('**/system/auth/refresh-token*', route => route.abort('internetdisconnected'))
  await page.reload()
  await page.waitForTimeout(1200)
  assert.equal(await page.evaluate(() => localStorage.getItem('refreshToken')), refreshCredential)
  await context.unroute('**/system/auth/refresh-token*')
  await page.reload()
  await expect.poll(() => protectedInfo(page)).toBe(0)
  record('续期网络中断保留凭证，网络恢复后自动续期')

  const second = await context.newPage()
  await second.goto(origin + '/dashboard')
  await page.evaluate(() => {
    localStorage.setItem('token', 'auth-verification-expired-access')
    localStorage.setItem('expiresTime', '2000-01-01T00:00:00')
  })
  await Promise.all([page.reload(), second.reload()])
  await expect.poll(() => protectedInfo(page)).toBe(0)
  await expect.poll(() => protectedInfo(second)).toBe(0)
  record('两个标签页同时到期后均保持登录')

  // 用真实退出 API 和 Store 清理操作，检查其他标签页同步退出和服务端撤销。
  const activeAccess = await page.evaluate(() => localStorage.getItem('token'))
  await page.evaluate(async () => {
    const { logout } = await import('/src/api/auth.ts')
    const { useUserStore } = await import('/src/stores/user.ts')
    await logout()
    useUserStore().logout()
  })
  await expect(second).toHaveURL(/\/login$/, { timeout: 15000 })
  await page.reload()
  await expect(page).toHaveURL(/\/login$/)
  assert.equal(await page.evaluate(() => localStorage.getItem('refreshToken')), null)
  const refresh = await context.request.post(origin + '/api/system/auth/refresh-token', {
    params: { refreshToken: refreshCredential }
  })
  assert.notEqual((await refresh.json()).code, 0)
  const access = await context.request.get(origin + '/api/system/auth/get-permission-info', {
    headers: { Authorization: 'Bearer ' + activeAccess }
  })
  assert.notEqual((await access.json()).code, 0)
  record('主动退出清除本地登录，其他标签页同步退出，服务端拒绝旧凭证')
  await mkdir(out, { recursive: true })
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify({ time: new Date().toISOString(), origin, checks }, null, 2)
  )
} finally {
  if (refreshCredential) {
    await fetch(origin + '/api/system/auth/logout', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + refreshCredential }
    }).catch(() => {})
  }
  await browser?.close()
}
