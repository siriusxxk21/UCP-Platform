import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, origin, output, settle, save } from './session.mjs'
const { ac, browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(9000)
const prefix = `mob${Date.now().toString(36)}`
const results = []
const dialog = () => page.locator('.ant-modal:visible, .ant-drawer-content:visible').last()
const button = (scope, name) => scope.getByRole('button', { name: new RegExp(name.split('').join('\\s*')) }).first()
async function reply(path, action) {
  const promise = page.waitForResponse(
    r => new URL(r.url()).pathname === `/api${path}` && r.request().method() === 'POST',
    { timeout: 20000 }
  )
  promise.catch(() => {})
  await action()
  const r = await promise,
    body = await r.json()
  assert.equal(body.code, 0, body.msg)
  return { data: body.data, sent: r.request().postDataJSON() }
}
try {
  for (const applicationId of ['1143', '1427']) {
    const result = { applicationId, name: `${prefix}_${applicationId}`, operations: [] }
    let record, objectId
    try {
      await page.goto(origin + '/nocode-app/runtime?id=' + applicationId)
      await settle(page)
      await button(page.locator('.content-wrapper-dual'), '新增').click()
      await settle(page)
      const required = dialog()
        .locator('.ant-form-item')
        .filter({ has: page.locator('.ant-form-item-required') })
      const fields = []
      for (let i = 0; i < (await required.count()); i++) {
        const control = required
          .nth(i)
          .locator('input.ant-input:enabled:not([readonly]),textarea:enabled:not([readonly])')
          .first()
        if (!(await control.count())) continue
        const placeholder = await control.getAttribute('placeholder')
        await control.fill(result.name)
        fields.push(placeholder)
      }
      assert.ok(fields.length, '需可编辑的必填名称字段')
      const created = await reply('/nocode/handling/submit', () => button(dialog(), '保存记录').click())
      assert.equal(created.data.outcome, 'EFFECTIVE')
      record = created.data.result.record
      objectId = created.sent.objectId
      Object.assign(result, { id: record.id, objectId, fields })
      result.operations.push('create')
      await save('runtime.json', { prefix, results: [...results, result] })
      await settle(page)
      await page.getByRole('textbox', { name: '搜索业务记录', exact: true }).fill(result.name)
      await button(page.locator('.content-wrapper-dual'), '查询').click()
      await settle(page)
      let row = page.locator('tr.ant-table-row').filter({ hasText: result.name }).first()
      await row.waitFor({ state: 'visible' })
      result.operations.push('read')
      await button(row, '编辑').click()
      await settle(page)
      await dialog()
        .getByPlaceholder(fields[0], { exact: true })
        .fill(result.name + '改')
      await page.screenshot({ path: resolve(output, `runtime-${applicationId}-edit.png`) })
      const updated = await reply('/nocode/handling/submit', () => button(dialog(), '保存记录').click())
      record = updated.data.result.record
      result.operations.push('update')
      await settle(page)
      row = page
        .locator('tr.ant-table-row')
        .filter({ hasText: result.name + '改' })
        .first()
      await reply('/nocode/runtime/delete', async () => {
        await button(row, '删除').click()
        await page
          .locator('.ant-popover:visible, .ant-modal:visible')
          .last()
          .getByRole('button', { name: /确\s*定|确\s*认|删\s*除/ })
          .last()
          .click()
      })
      result.operations.push('delete')
      result.cleaned = true
      result.passed = true
    } catch (error) {
      result.error = error.message
      result.passed = false
      result.text = (await page.locator('body').innerText()).slice(-2500)
      await page.screenshot({ path: resolve(output, `runtime-${applicationId}-failure.png`) })
    } finally {
      if (record?.id && !result.cleaned) {
        try {
          await ac.api('/nocode/runtime/delete', {
            applicationId,
            objectId,
            id: record.id,
            expectedRevision: record.revision
          })
          result.cleaned = true
        } catch (error) {
          result.cleanupError = error.message
        }
      }
      results.push(result)
      await save('runtime.json', { prefix, results })
      console.log(applicationId, result.passed ? 'PASS C/R/U/D' : result.error)
    }
  }
} finally {
  await browser.close()
}
if (results.some(r => !r.passed)) process.exitCode = 1
