import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, origin, output, settle, save } from './session.mjs'
const { ac, browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(9000)
const name = `mob${Date.now().toString(36)}`
let id,
  removed = false
const result = { name, operations: [] }
const btn = text => page.getByRole('button', { name: new RegExp(text.split('').join('\\s*')) }).first()
async function response(path, method, action) {
  const pending = page.waitForResponse(
    r => new URL(r.url()).pathname === '/api' + path && r.request().method() === method,
    { timeout: 16000 }
  )
  pending.catch(() => {})
  await action()
  const body = await (await pending).json()
  assert.equal(body.code, 0, body.msg)
  return body.data
}
try {
  await page.goto(origin + '/bpm/model')
  await settle(page)
  await btn('新建模型').click()
  await settle(page)
  await page.getByPlaceholder('请输入流程名称', { exact: true }).fill(name)
  await page.getByPlaceholder('请输入流程标识', { exact: true }).fill(name)
  const category = page
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^流程分类$/ }) })
  await category.locator('.ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').first().click()
  await btn('流程设计').click()
  await page.waitForTimeout(900)
  id = await response('/bpm/model/create', 'POST', () => btn('保存草稿').click())
  result.id = id
  result.operations.push('create')
  await settle(page)
  await btn('基本信息').click()
  assert.equal(await page.getByPlaceholder('请输入流程名称', { exact: true }).inputValue(), name)
  result.operations.push('read')
  await page.getByPlaceholder('请输入流程名称', { exact: true }).fill(name + '改')
  await response('/bpm/model/update', 'PUT', () => btn('保存草稿').click())
  result.operations.push('update')
  await page.screenshot({ path: resolve(output, 'model-editor.png') })
  await btn('返回').click()
  await settle(page)
  const row = page
    .locator('tr.ant-table-row')
    .filter({ hasText: name + '改' })
    .first()
  await row.getByRole('button', { name: /更多/ }).tap()
  await page.getByRole('menuitem', { name: /删除/ }).tap()
  await response('/bpm/model/delete', 'DELETE', () =>
    page
      .locator('.ant-modal-confirm:visible')
      .getByRole('button', { name: /确\s*定/ })
      .click()
  )
  removed = true
  result.operations.push('delete')
  result.passed = true
} catch (error) {
  result.error = error.message
  result.passed = false
  result.text = (await page.locator('body').innerText()).slice(-2500)
  await page.screenshot({ path: resolve(output, 'model-failure.png') })
} finally {
  if (id && !removed) {
    try {
      await ac.api('/bpm/model/delete?id=' + id, undefined, undefined, 'DELETE')
      removed = true
    } catch (error) {
      result.cleanupError = error.message
    }
  }
  result.cleaned = removed
  await save('model.json', result)
  await browser.close()
}
console.log(result.passed ? 'PASS model C/R/U/D' : result.error)
if (!result.passed) process.exitCode = 1
