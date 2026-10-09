import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只读复验已登记夹具的默认列宽，不创建数据或修改用户个人设置。
const prefix = process.argv[2]
assert.match(prefix || '', /^ar[a-z0-9]+$/, '请指定原验收批次前缀')
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/application-recycle-regression', prefix)
const manifest = JSON.parse(await readFile(resolve(out, 'http-result.json'), 'utf8'))
assert.equal(manifest.prefix, prefix)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, out)
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const errors = [],
  checks = []
let browser, failure
let applicationApi
try {
  await ac.login()
  const application = await ac.api(`/nocode/application/get?id=${manifest.applicationId}`)
  const preview = await ac.api(`/nocode/application/delete-preview?id=${manifest.applicationId}`)
  assert.equal(application.application.recoveryPending, false)
  assert.equal(application.application.recoveryNeedsEdit, false)
  assert.equal(application.application.status, 'ACTIVE')
  assert.equal(application.application.publishedVersion, 2)
  assert.equal(preview.application.id, manifest.applicationId)
  assert.ok(Array.isArray(preview.blockers))
  applicationApi = { application: application.application, deletePreview: preview }
  const recycled = await ac.api(`/nocode/application/recycle-page?pageNo=1&pageSize=100&search=${prefix}_page_`)
  assert.equal(recycled.total, 10)
  assert.ok(recycled.list.every(row => manifest.owned.applications.includes(row.id)))
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  page.setDefaultTimeout(25000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
  }, ac.tokens.admin)
  await page.goto(origin + '/nocode-app/application?recycle=1')
  await page.getByRole('textbox', { name: '搜索回收站应用' }).fill(prefix + '_page_')
  await page.getByRole('button', { name: /查询$/ }).click()
  await expect(page.locator('tr.ant-table-row')).toHaveCount(10)
  const inspect = async (width, filename) => {
    await page.setViewportSize({ width, height: 900 })
    const dateHeader = page.getByRole('columnheader', { name: '删除时间', exact: true })
    await expect(dateHeader).toBeVisible()
    const headerIndex = await dateHeader.evaluate(node => Array.from(node.parentElement.children).indexOf(node))
    const row = page.locator('tr.ant-table-row').first()
    const dateCell = row.locator('td').nth(headerIndex)
    const action = row.getByRole('button', { name: /恢复$/ })
    await expect(dateCell).toContainText(/20\d{2}[-/]\d{1,2}[-/]\d{1,2}/)
    await expect(action).toBeVisible()
    const cells = await row.locator('td').evaluateAll(nodes =>
      nodes.map(node => ({
        text: node.textContent.trim(),
        x: node.getBoundingClientRect().x,
        width: node.getBoundingClientRect().width,
        clientWidth: node.clientWidth,
        scrollWidth: node.scrollWidth
      }))
    )
    const date = await dateCell.boundingBox()
    const actionCell = await row.locator('td').last().boundingBox()
    const actionBox = await action.boundingBox()
    assert.ok(date && actionCell && actionBox)
    assert.ok(date.x + date.width <= actionCell.x + 1, '删除时间列不能被固定操作列覆盖')
    assert.ok(date.x >= 0 && date.x + date.width <= width, '删除时间列必须完整处于视口')
    assert.ok(actionBox.x >= 0 && actionBox.x + actionBox.width <= width, '恢复按钮必须完整处于视口')
    const dimensions = await page.evaluate(() => ({
      viewport: innerWidth,
      document: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
      tables: Array.from(document.querySelectorAll('.ant-table-body,.ant-table-content')).map(node => ({
        width: node.clientWidth,
        scrollWidth: node.scrollWidth,
        scrollLeft: node.scrollLeft
      }))
    }))
    assert.ok(dimensions.document <= width + 1 && dimensions.body <= width + 1)
    await expect(page.locator('.ant-message-notice-content')).toHaveCount(0)
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(out, filename), fullPage: true, animations: 'disabled' })
    checks.push({ width, date: await dateCell.innerText(), cells, dimensions, screenshot: filename })
  }
  await page.getByTitle('列设置', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '删除时间', exact: true })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: '应用编码', exact: true })).toBeChecked()
  await page.getByTitle('列设置', { exact: true }).click()
  await inspect(1280, '06-final-recycle-1280.png')
  await inspect(1600, '07-final-recycle-1600.png')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
} finally {
  await browser?.close()
  ac.tokens = {}
  await writeFile(
    resolve(out, 'final-layout.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        prefix,
        readOnly: true,
        applicationApi,
        checks,
        errors,
        failure: failure?.message
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output: out, checks: checks.length, errors, failure: failure?.message }))
}
if (failure) throw failure
