import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-editing', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure, releaseResponse
try {
  await ac.login()
  const app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_editing',
    name: '编辑会话验收 ' + ac.prefix,
    description: '本次唯一夹具',
    definition: { objects: [], resources: [] }
  })
  ac.app = app
  ac.owned.applications.push(app.application.id)
  await ac.persist()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
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
    },
    { token: ac.tokens.admin, info }
  )
  await page.goto(`${origin}/nocode-app/workspace?id=${app.application.id}`)
  await page.getByRole('tab', { name: '基本设置', exact: true }).click()
  const name = page.getByRole('textbox', { name: '应用名称', exact: true })
  const save = page.getByRole('button', { name: /保存草稿$/ })
  const before = '提交时名称 ' + ac.prefix
  const continued = '继续编辑名称 ' + ac.prefix
  await name.fill(before)
  const released = new Promise(resolveRelease => {
    releaseResponse = resolveRelease
  })
  let committed, forwardingError
  await page.route(
    '**/nocode/application/save',
    async route => {
      try {
        const response = await route.fetch({ timeout: 20000 })
        committed = await response.json()
        await released
        await route.fulfill({ response })
      } catch (error) {
        forwardingError = error
        await route.abort('failed').catch(() => {})
      }
    },
    { times: 1 }
  )
  await save.click()
  await expect.poll(() => !!committed || !!forwardingError, { timeout: 22000 }).toBe(true)
  if (forwardingError) throw forwardingError
  assert.equal(committed.code, 0)
  await expect(name).toBeEnabled()
  await name.fill(continued)
  releaseResponse()
  await expect(page.locator('.workspace-header')).toContainText('有未保存修改')
  await expect(name).toHaveValue(continued)
  await expect(save).not.toHaveClass(/ant-btn-loading/)
  const persisted = await ac.api(`/nocode/application/get?id=${app.application.id}`)
  assert.equal(persisted.application.name, before)
  await page.screenshot({
    path: resolve(output, '01-save-keeps-later-input.png'),
    fullPage: true,
    animations: 'disabled'
  })
  const next = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/save') && response.request().method() === 'POST'
  )
  await save.click()
  const nextResponse = await next
  assert.equal(nextResponse.request().postDataJSON().expectedRevision, persisted.application.revision)
  assert.equal((await nextResponse.json()).code, 0)
  await expect(page.locator('.workspace-header')).not.toContainText('有未保存修改')
  await page.reload()
  await page.getByRole('tab', { name: '基本设置', exact: true }).click()
  await expect(name).toHaveValue(continued)
  checks.push('真实保存响应延迟时保留后续输入；下次保存携带新修订号；刷新回读保留最终名称')

  const retryName = '失败后重试 ' + ac.prefix
  await name.fill(retryName)
  await page.route('**/nocode/application/save', route => route.abort('failed'), { times: 1 })
  await save.click()
  await expect(page.locator('.workspace > .ant-alert')).toBeVisible()
  await expect(save).not.toHaveClass(/ant-btn-loading/)
  await expect(name).toHaveValue(retryName)
  await expect(page.locator('.workspace-header')).toContainText('有未保存修改')
  assert.equal((await ac.api(`/nocode/application/get?id=${app.application.id}`)).application.name, continued)
  const retried = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/save') && response.request().method() === 'POST'
  )
  await save.click()
  assert.equal((await (await retried).json()).code, 0)
  await expect(page.locator('.workspace-header')).not.toContainText('有未保存修改')
  assert.equal((await ac.api(`/nocode/application/get?id=${app.application.id}`)).application.name, retryName)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  checks.push('未到达服务端的网络失败保留输入与dirty；正常重试后真实回读一致')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  releaseResponse?.()
  await browser?.close()
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await mkdir(output, { recursive: true })
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
