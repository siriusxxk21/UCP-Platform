import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'
import { createFixture, edit, inactive, publish, readDesign, saveBody } from './fixture.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix = `fu${Date.now().toString(36)}`
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/field-restore-ui', ac.prefix)
ac.output = out
const checks = [],
  errors = []
let browser, page, failure, id, phoneId, detailId, contactId, stage
const shot = name => page.screenshot({ path: resolve(out, `${name}.png`), fullPage: true, animations: 'disabled' })
const drawer = () => page.locator('.ant-drawer-content:visible')
const designer = () => page.locator('.field-designer:visible').first()
const fieldRow = stableId =>
  designer()
    .locator('.ant-table-tbody tr')
    .filter({ has: page.locator(`[data-field-key="${stableId}"]`) })
const openDrawer = async () => {
  await designer()
    .getByRole('button', { name: /已停用字段/ })
    .click()
  await expect(drawer().getByRole('textbox', { name: '搜索已停用字段' })).toBeVisible()
}
const closeDrawer = () =>
  drawer()
    .getByRole('button', { name: /^关\s*闭$/ })
    .click()
const restore = async name => {
  await openDrawer()
  await drawer()
    .locator('.ant-table-tbody tr')
    .filter({ hasText: name })
    .getByRole('button', { name: /恢\s*复$/ })
    .click()
  await closeDrawer()
}
const save = async () => {
  const response = page.waitForResponse(
    value => value.url().endsWith('/nocode/design/save') && value.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿/ }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
const detailTab = async () => {
  await page.getByRole('tab', { name: /内部明细/ }).click()
  const panel = page.locator('.ant-collapse-item').filter({ hasText: '联系明细 · items' })
  if (!(await panel.locator('.field-designer').isVisible())) await panel.locator('.ant-collapse-header').click()
}
try {
  await ac.login()
  let design = await createFixture(ac)
  id = design.draft.id
  await inactive(ac, id)
  await publish(ac, design)
  design = await edit(ac, id)
  phoneId = design.draft.fields.find(field => field.code === 'phone').id
  detailId = design.details.find(detail => detail.code === 'items').id
  contactId = design.details.find(detail => detail.id === detailId).fields.find(field => field.code === 'contact').id
  const body = saveBody(design)
  body.draft.fields = body.draft.fields.filter(field => field.id !== phoneId)
  body.draft.removedFieldIds = [phoneId]
  delete body.fieldOptions[phoneId]
  delete body.details.find(detail => detail.id === detailId).fieldOptions[contactId]
  body.details.find(detail => detail.id === detailId).fields = body.details
    .find(detail => detail.id === detailId)
    .fields.filter(field => field.id !== contactId)
  design = await ac.api('/nocode/design/save', body)
  await publish(ac, design)

  browser = await chromium.launch({ headless: true, channel: 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error =>
    errors.push({ stage, after: checks.at(-1), message: error.message, stack: error.stack })
  )
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
  await page.goto(`${process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'}/nocode/object/editor?id=${id}`)
  await openDrawer()
  await expect(drawer().getByText('联系电话', { exact: true })).toBeVisible()
  await expect(drawer().getByRole('button', { name: /恢\s*复$/ })).toBeDisabled()
  await shot('01-published-inactive-drawer')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(async () => {
      const box = await drawer().boundingBox()
      return !!box && box.x >= -1 && box.x + box.width <= 391
    })
    .toBe(true)
  assert.ok(await page.evaluate(() => document.body.scrollWidth <= window.innerWidth + 1), '窄屏抽屉不引起页面横向溢出')
  await expect
    .poll(async () => {
      const box = await drawer()
        .getByRole('button', { name: /刷\s*新$/ })
        .boundingBox()
      return !!box && box.x >= 0 && box.x + box.width <= 391
    })
    .toBe(true)
  await shot('01a-narrow-inactive-drawer')
  await page.setViewportSize({ width: 1512, height: 1050 })
  await closeDrawer()
  checks.push('已发布版本提供已停用字段查看入口，恢复按钮禁用')
  checks.push('390px 窄屏抽屉完整位于视口内，页面无横向溢出；字段表格独立横向滚动')

  const editing = page.waitForResponse(response => response.url().endsWith('/nocode/design/edit'))
  await page.getByRole('button', { name: '编辑新草稿', exact: true }).click()
  assert.equal((await (await editing).json()).code, 0)
  const before = await readDesign(ac, id)
  await restore('联系电话')
  await expect(fieldRow(phoneId)).toBeVisible()
  assert.equal((await readDesign(ac, id)).draft.lockVersion, before.draft.lockVersion)
  assert.equal(
    (await readDesign(ac, id)).draft.fields.some(field => field.id === phoneId),
    false
  )
  await page.reload()
  await openDrawer()
  await expect(drawer().getByText('联系电话', { exact: true })).toBeVisible()
  await shot('02-editable-inactive-drawer')
  await page.route('**/nocode/design/inactive-fields?*', route => route.abort('failed'), { times: 1 })
  await drawer()
    .getByRole('button', { name: /刷\s*新$/ })
    .click()
  await expect(drawer().locator('.ant-alert-error')).toBeVisible()
  await shot('02a-inactive-load-error')
  await drawer()
    .getByRole('button', { name: /刷\s*新$/ })
    .click()
  await expect(drawer().locator('.ant-alert-error')).toHaveCount(0)
  await expect(drawer().getByText('联系电话', { exact: true })).toBeVisible()
  await closeDrawer()
  checks.push('恢复只改变本地草稿；未保存直接刷新仍在停用列表，服务草稿版本未变化')
  checks.push('停用列表读取失败显示错误，刷新重试恢复真实列表且不丢失已保存字段')

  await designer()
    .getByRole('button', { name: /新增字段/ })
    .click()
  const added = designer()
    .locator('.ant-table-tbody tr')
    .filter({ has: page.getByRole('button', { name: /移\s*除$/ }) })
    .last()
  stage = '输入新增字段名称'
  await added.getByRole('textbox', { name: /字段名称/ }).fill('联系电话')
  stage = '输入新增字段编码'
  const codeInput = added.getByRole('textbox', { name: /字段编码/ })
  const originalInput = await codeInput.elementHandle()
  await codeInput.fill('phone')
  stage = '检查同编码错误'
  await expect(added.getByText(/已被停用字段.*占用/)).toBeVisible()
  assert.ok(
    await originalInput.evaluate(input => input.isConnected && document.activeElement === input),
    '错误提示出现时保留原输入节点及焦点'
  )
  const duplicateBefore = await readDesign(ac, id)
  let saveRequests = 0
  const observeSave = request => {
    if (request.url().endsWith('/nocode/design/save')) saveRequests++
  }
  page.on('request', observeSave)
  await page.getByRole('button', { name: /保存草稿/ }).click()
  await expect(page.getByText(/编码.*已被停用字段.*占用/).first()).toBeVisible()
  assert.equal((await readDesign(ac, id)).draft.lockVersion, duplicateBefore.draft.lockVersion)
  assert.equal(saveRequests, 0, '重复编码应在前端保存前阻断')
  page.off('request', observeSave)
  await shot('02b-reserved-code-error')
  await codeInput.fill('phone_new')
  await expect(added.getByText(/已被停用字段.*占用/)).not.toBeVisible()
  assert.ok(
    await originalInput.evaluate(input => input.isConnected && document.activeElement === input),
    '更换为可用编码后清除提示且继续保持输入焦点'
  )
  await added.getByRole('button', { name: /移\s*除$/ }).click()
  checks.push('新增同名同编码字段即时提示停用占用，保存前阻断且不发出写请求')

  await restore('联系电话')
  await fieldRow(phoneId)
    .getByRole('button', { name: /停\s*用$/ })
    .click()
  await page
    .locator('.ant-modal-confirm:visible')
    .getByRole('button', { name: /确\s*定|确\s*认|停\s*用/ })
    .click()
  await restore('联系电话')
  const saved = await save()
  assert.equal(saved.draft.fields.find(field => field.code === 'phone').id, phoneId)
  assert.equal(saved.fieldOptions[phoneId].description, '恢复后保留的原字段说明')
  await page.reload()
  await expect(fieldRow(phoneId)).toBeVisible()
  await shot('03-main-restored')
  checks.push('主表恢复后保存、刷新保持原稳定 ID 和配置')
  checks.push('主表恢复后再次停用、再次恢复、保存，恢复声明与停用声明正确抵消')

  await detailTab()
  await openDrawer()
  await expect(drawer().getByText('明细联系方式', { exact: true })).toBeVisible()
  await expect(drawer().getByText('联系电话', { exact: true })).toHaveCount(0)
  await shot('04-detail-inactive-drawer')
  await closeDrawer()
  await restore('明细联系方式')
  const detailSaved = await save()
  assert.equal(
    detailSaved.details.find(detail => detail.id === detailId).fields.find(field => field.code === 'contact').id,
    contactId
  )
  await page.reload()
  await detailTab()
  await expect(fieldRow(contactId)).toBeVisible()
  checks.push('内部明细独立入口仅显示所属字段；恢复保存与刷新保留原 ID')

  await fieldRow(contactId)
    .getByRole('button', { name: /停\s*用$/ })
    .click()
  await page
    .locator('.ant-modal-confirm:visible')
    .getByRole('button', { name: /确\s*定|确\s*认|停\s*用/ })
    .click()
  await openDrawer()
  await expect(drawer().getByText('待保存停用', { exact: true })).toBeVisible()
  await drawer()
    .getByRole('button', { name: /恢\s*复$/ })
    .click()
  await closeDrawer()
  await save()
  assert.equal(
    (await readDesign(ac, id)).details
      .find(detail => detail.id === detailId)
      .fields.some(field => field.id === contactId),
    true
  )
  checks.push('本地未保存停用可从抽屉撤销，保存不会误停用明细字段')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await shot('failure').catch(() => {})
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
    JSON.stringify({ time: new Date().toISOString(), objectId: id, checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output: out, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
