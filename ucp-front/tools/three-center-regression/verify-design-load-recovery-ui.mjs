import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 对自有夹具的首次读取模拟断网；真实开发页面重试后读取原配置，不提交空草稿。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-load-recovery', ac.prefix)
ac.output = out
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
try {
  await ac.login()
  const object = await ac.object('load', '读取恢复 ' + ac.prefix, [ac.field('name', 'TEXT', '采购单名称')])
  const app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_load',
    name: '读取恢复应用 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: []
    }
  })
  ac.owned.applications.push(app.application.id)
  browser = await chromium.launch({ headless: true, channel: 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  const info = await ac.api('/system/auth/get-permission-info')
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      for (const [key, value] of Object.entries({
        userInfo: info.user,
        permissions: info.permissions || [],
        roles: info.roles || [],
        menus: info.menus || []
      }))
        localStorage.setItem(key, JSON.stringify(value))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  await page.route('**/nocode/design/get?*', route => route.abort('failed'), { times: 1 })
  await page.goto(`${origin}/nocode/object/editor?id=${object.objectId}`)
  await expect(page.locator('.ant-result-title')).toHaveText('数据对象未加载成功')
  await expect(page.getByRole('tab', { name: '主表字段', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /保存草稿/ })).toHaveCount(0)
  await page.screenshot({ path: resolve(out, '01-object-load-error.png'), animations: 'disabled', fullPage: true })
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByRole('heading', { name: object.definition.objectName, exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '主表字段', exact: true })).toBeVisible()
  await expect(page.getByText('采购单名称', { exact: true }).first()).toBeVisible()
  checks.push('数据对象首次读取失败不伪装新建、不开放空草稿；重试恢复真实字段')
  await page.route('**/nocode/application/get?*', route => route.abort('failed'), { times: 1 })
  await page.goto(`${origin}/nocode-app/workspace?id=${app.application.id}`)
  await expect(page.getByText('应用配置未加载成功', { exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '已引用对象', exact: true })).toHaveCount(0)
  const save = page.getByRole('button', { name: /保存草稿/ })
  if (await save.count()) await expect(save).toBeDisabled()
  await page.screenshot({ path: resolve(out, '02-application-load-error.png'), animations: 'disabled', fullPage: true })
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByRole('heading', { name: app.application.name, exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '已引用对象', exact: true })).toBeVisible()
  await expect(page.getByText(object.definition.objectName, { exact: true }).first()).toBeVisible()
  checks.push('应用首次读取失败禁止保存并隐藏空配置；重试恢复已引用对象')
  const after = await ac.api(`/nocode/application/get?id=${app.application.id}`)
  assert.equal(after.application.revision, app.application.revision)
  assert.equal(after.draft.objects.length, 1)
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await page.screenshot({ path: resolve(out, 'failure.png'), fullPage: true }).catch(() => {})
    await writeFile(resolve(out, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  await browser?.close()
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await mkdir(out, { recursive: true })
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify({ time: new Date().toISOString(), checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output: out, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
