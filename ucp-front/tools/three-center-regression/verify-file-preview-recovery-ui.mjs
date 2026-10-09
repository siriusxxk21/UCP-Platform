import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实开发页面；只对自有夹具的设计读取和文件元信息响应注入延迟/失败，不向数据库写入虚假文件。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-file-recovery-ui', ac.prefix)
ac.output = out
const checks = [],
  errors = []
let browser, page, failure, release
try {
  await ac.login()
  const object = await ac.object('file_ui', '文件预览恢复' + ac.prefix, [
    ac.field('name', 'TEXT', '名称'),
    ac.field('files', 'ATTACHMENT', '附件')
  ])
  browser = await chromium.launch({ headless: true, channel: 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
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
  await page.route('**/nocode/design/get?*', async route => {
    const response = await route.fetch(),
      json = await response.json()
    if (String(json.data?.draft?.id) === object.objectId)
      json.data.fieldOptions[object.ids.files].defaultValue = '["999999991"]'
    await route.fulfill({ response, json })
  })
  let requestCount = 0,
    mode = 'race'
  const pending = new Promise(resolve => {
    release = resolve
  })
  const metadata = [{ id: '999999991', name: '受控预览附件.pdf', configId: '1', path: 'controlled-preview.pdf' }]
  await page.route('**/infra/file/get-list?*', async route => {
    requestCount++
    if (mode === 'race' && requestCount > 1) await pending
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        mode === 'failure' ? { code: 500, msg: '受控读取失败' } : { code: 0, data: mode === 'missing' ? [] : metadata }
      )
    })
  })
  const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
  async function openEditor() {
    const current = await ac.api(`/nocode/design/get?id=${object.objectId}`)
    await page.goto(origin + `/nocode/object/editor?id=${object.objectId}`)
    const newDraft = page.getByRole('button', { name: '编辑新草稿', exact: true })
    if (current.draft.state === 'PUBLISHED') {
      await newDraft.click()
      await expect(newDraft).toHaveCount(0)
      // 新草稿 POST 返回真实空默认值；重新读取才注入本例的旧文件值。
      await page.reload()
    }
    await page
      .locator('.ant-table-tbody:visible tr')
      .filter({ hasText: '附件' })
      .getByRole('button', { name: /配置/ })
      .click()
    return page.locator('.ant-modal-content:visible').filter({ hasText: '字段配置' })
  }
  let modal = await openEditor()
  const defaults = modal
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^默认值$/ }) })
  await expect(defaults.getByText('受控预览附件.pdf')).toBeVisible()
  await defaults.locator('.ant-upload-list-item-actions button').click()
  release()
  await expect(modal.getByText('受控预览附件.pdf')).toHaveCount(0)
  await page.screenshot({
    path: resolve(out, '01-late-response-discarded.png'),
    fullPage: true,
    animations: 'disabled'
  })
  checks.push('真实附件默认值清空后，迟到的预览元信息响应不重新显示文件')
  mode = 'failure'
  modal = await openEditor()
  await expect(modal.getByText('暂时无法读取文件信息，已保留选择，请重新读取。').first()).toBeVisible()
  mode = 'missing'
  await modal.getByRole('button', { name: '重新读取' }).first().click()
  await expect(
    modal.getByText('有 1 个文件不存在或当前无权读取。已保留选择；可重试或移除失效文件。').first()
  ).toBeVisible()
  mode = 'success'
  await modal.getByRole('button', { name: '重新读取' }).first().click()
  await expect(modal.getByText('受控预览附件.pdf').first()).toBeVisible()
  while (await modal.getByRole('button', { name: '重新读取' }).count()) {
    await modal.getByRole('button', { name: '重新读取' }).first().click()
    await expect(modal.getByRole('button', { name: '重新读取' })).toHaveCount(0)
  }
  await expect(modal.getByText('受控预览附件.pdf')).toHaveCount(2)
  await expect(page.locator('.ant-notification-notice')).toHaveCount(0, { timeout: 10000 })
  await page.screenshot({ path: resolve(out, '02-retry-recovered.png'), fullPage: true, animations: 'disabled' })
  checks.push('读取失败与缺失文件各有明确提示；保留 ID、支持重试并恢复文件名，未保存虚假默认值')
  const real = await ac.api(`/nocode/design/get?id=${object.objectId}`)
  assert.equal(real.fieldOptions[object.ids.files].defaultValue ?? null, null)
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  release?.()
  if (page) {
    await page.screenshot({ path: resolve(out, 'failure.png'), fullPage: true }).catch(() => {})
    await writeFile(resolve(out, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  release?.()
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
