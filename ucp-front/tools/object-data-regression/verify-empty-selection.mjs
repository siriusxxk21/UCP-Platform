import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 只在独立浏览器中更改临时字段配置，浏览器写请求使用只读白名单兜底。 */
const argument = (name, fallback) => {
  const index = process.argv.indexOf(name)
  return index < 0 ? fallback : process.argv[index + 1]
}
const objectId = argument('--object-id', '5769')
const fieldCode = argument('--field-code', 'empty_value')
assert.match(objectId, /^\d+$/)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || `.work/empty-selection/${objectId}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
await ac.login()
async function snapshot() {
  const design = await ac.api(`/nocode/design/get?id=${objectId}`)
  const records = []
  let total = 0
  for (let pageNo = 1; ; pageNo++) {
    const result = await ac.api('/nocode/object-data/page', { objectId, pageNo, pageSize: 100 })
    total = result.total
    assert.ok(total <= 1000, '只读体验验收仅对最多 1000 条记录的对象执行全量前后对比')
    records.push(...result.list)
    if (records.length >= total) break
    assert.ok(result.list.length, '分页读取不得在未读完时返回空页')
  }
  records.sort((left, right) => String(left.id).localeCompare(String(right.id)))
  return { design, total, records }
}
const before = await snapshot()
const field = before.design.draft.fields.find(item => item.code === fieldCode)
assert.ok(field, `字段 ${fieldCode} 必须存在`)
assert.equal(field.type, 'TEXT', '验收开始时应为文本字段')
assert.equal(before.design.draft.state, 'DRAFT', '已有草稿才能只编辑本地配置，不创建新草稿')
const index = before.design.draft.fields.findIndex(item => item.id === field.id)
const readonlyPosts = new Set([
  '/nocode/design/field-switch-preview',
  '/nocode/design/field-switch-preview-rows',
  '/nocode/object-data/page'
])
const checks = []
const blockedWrites = []
const errors = []
const previews = []
const pendingPreviews = []
let failure
const browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
let page
try {
  page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (
      ['GET', 'HEAD', 'OPTIONS'].includes(request.method()) ||
      (request.method() === 'POST' && readonlyPosts.has(path))
    ) {
      await route.continue()
      return
    }
    blockedWrites.push({ method: request.method(), path })
    await route.abort('blockedbyclient')
  })
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', response => {
    if (!new URL(response.url()).pathname.endsWith('/nocode/design/field-switch-preview')) return
    pendingPreviews.push(
      response.json().then(result => {
        previews.push({ request: response.request().postDataJSON(), response: result })
      })
    )
  })
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, ac.tokens.admin)
  const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
  await page.goto(`${origin}/nocode/object/editor?id=${objectId}`)
  const row = page
    .locator('tr')
    .filter({ has: page.getByRole('textbox', { name: `第 ${index + 1} 行字段名称`, exact: true }) })
  await row.getByRole('button', { name: /配置/ }).click()
  const drawer = page.locator('.ant-drawer-content:visible, .ant-modal-content:visible').filter({
    has: page.locator('.ant-drawer-title, .ant-modal-title').filter({ hasText: /^字段配置$/ })
  })
  const fieldType = drawer
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^字段类型$/ }) })
    .locator('.ant-select')
  const modal = page.locator('.ant-modal-content:visible').filter({
    has: page.locator('.ant-modal-title').filter({ hasText: /^检查字段变更/ })
  })
  const impact = modal.getByRole('region', { name: '字段变更影响' })
  async function selectType(valueRows = 0) {
    await fieldType.click()
    const dropdown = page.locator('.ant-select-dropdown:visible')
    await expect(dropdown).toBeVisible()
    await expect(dropdown.locator('.ant-select-item-option-selected')).toBeInViewport()
    const option = dropdown.getByText('单选', { exact: true })
    await expect
      .poll(async () => {
        if (await option.count()) return true
        await dropdown.locator('.rc-virtual-list-holder').evaluate(element => {
          element.scrollTop += 160
        })
        return false
      })
      .toBe(true)
    await option.click()
    await expect(modal).toBeVisible()
    await expect(impact).toContainText(`本列 ${valueRows} 条有值`)
  }
  await selectType()
  await expect(impact.locator('.ant-alert-info')).toHaveCount(1)
  await expect(impact.locator('.ant-alert-error')).toHaveCount(0)
  await expect(impact.locator('.ant-alert')).toHaveCount(1)
  await expect(impact).toContainText('待补齐')
  await expect(modal.getByRole('button', { name: '确认变更方案', exact: true })).toBeDisabled()
  await page.screenshot({ path: resolve(output, '01-空列待补齐选项.png'), fullPage: true, animations: 'disabled' })
  checks.push('空白文本切单选：0 个历史值、仅一条配置提示、确认禁用')
  await modal.getByRole('button', { name: '取消本次变更', exact: true }).click()
  await expect(modal).toHaveCount(0)
  await expect(fieldType.locator('.ant-select-selection-item')).toHaveText('单行文本')
  checks.push('取消本次变更恢复 TEXT')

  await selectType()
  await modal.getByRole('button', { name: /添加选项/ }).click()
  await modal.getByPlaceholder('稳定编码', { exact: true }).fill('experience_ready')
  await modal.getByPlaceholder('显示名称', { exact: true }).fill('体验选项')
  await expect(modal.getByRole('button', { name: '确认变更方案', exact: true })).toBeEnabled({ timeout: 15000 })
  await expect(impact.locator('.ant-alert-success')).toHaveCount(1)
  await expect(impact.locator('.ant-alert-error')).toHaveCount(0)
  await expect(impact.locator('.ant-alert')).toHaveCount(1)
  await expect(impact).toContainText('本列没有历史值，可以转换')
  await expect(impact).toContainText('本列 0 条有值')
  await page.screenshot({
    path: resolve(output, '02-补齐选项自动检查通过.png'),
    fullPage: true,
    animations: 'disabled'
  })
  checks.push('弹窗补齐稳定编码和名称后自动复检通过，无需手动重新检查')
  await modal.getByRole('button', { name: '确认变更方案', exact: true }).click()
  await expect(modal).toHaveCount(0)
  await expect(fieldType.locator('.ant-select-selection-item')).toHaveText('单选')
  await expect(drawer.getByRole('button', { name: '应用到草稿', exact: true })).toBeVisible()
  await expect(drawer.getByPlaceholder('稳定编码', { exact: true })).toHaveValue('experience_ready')
  checks.push('确认方案返回字段配置，保留临时选项；未应用或保存草稿')
  await drawer.getByRole('button', { name: /^取\s*消$/ }).click()
  await expect(drawer).toHaveCount(0)
  await expect(row.locator('.field-type-select .ant-select-selection-item')).toHaveText('单行文本')
  checks.push('取消字段配置后主表仍为 TEXT')

  // 同对象有历史值的列只检查风险是否仍直接展示，不确认方案或触发发布。
  const riskField = before.design.draft.fields.find(item => item.code === 'numeric_text' && item.type === 'TEXT')
  if (riskField) {
    const riskIndex = before.design.draft.fields.findIndex(item => item.id === riskField.id)
    const riskRow = page.locator('tr').filter({
      has: page.getByRole('textbox', { name: `第 ${riskIndex + 1} 行字段名称`, exact: true })
    })
    const count = before.records.filter(record => record.values[riskField.id] != null).length
    await riskRow.getByRole('button', { name: /配置/ }).click()
    await selectType(count)
    await modal.getByRole('button', { name: /添加选项/ }).click()
    await modal.getByPlaceholder('稳定编码', { exact: true }).fill('x')
    await modal.getByPlaceholder('显示名称', { exact: true }).fill('X')
    const riskPreview = () =>
      previews.findLast(
        item =>
          item.request.fieldId === riskField.id &&
          item.request.targetOptions?.some(option => option.code === 'x' && option.label === 'X') &&
          item.response.code === 0
      )
    await expect
      .poll(() => riskPreview()?.response.data.decision, { timeout: 15000 })
      .toMatch(/^(CLEAR_COLUMN|BLOCKED)$/)
    if (riskPreview().response.data.decision === 'CLEAR_COLUMN') {
      await expect(impact.locator('.clear-choice')).toBeVisible()
      await expect(impact.getByRole('checkbox', { name: new RegExp(`选择发布时清空本列 ${count} 个值并转换`) })).toBeVisible()
      await expect(impact.locator('.clear-choice')).toContainText('整条记录和其他列保留')
      await expect(modal.getByRole('button', { name: '确认变更方案', exact: true })).toBeDisabled()
      checks.push(`有值列 ${count} 个旧值：清空确认及仅清本列风险直接可见，未勾选不能确认`)
    } else {
      await expect(impact.locator('.ant-alert-error').first()).toBeVisible()
      await expect(modal.getByRole('button', { name: '确认变更方案', exact: true })).toBeDisabled()
      checks.push('有值列存在独立阻断，保留准确阻断及禁用状态，详情见预检 API 结果')
    }
    await page.screenshot({ path: resolve(output, '03-历史值风险常显.png'), fullPage: true, animations: 'disabled' })
    await modal.getByRole('button', { name: '取消本次变更', exact: true }).click()
    await expect(fieldType.locator('.ant-select-selection-item')).toHaveText('单行文本')
    await drawer.getByRole('button', { name: /^取\s*消$/ }).click()
    await expect(riskRow.locator('.field-type-select .ant-select-selection-item')).toHaveText('单行文本')
    checks.push('有值列取消后也恢复 TEXT，不改变原值')
  }
  await Promise.all(pendingPreviews)
  assert.ok(
    previews.some(item => item.response.code === 0 && item.response.data.valueRows === 0),
    '真实检查接口应核实空列'
  )
  assert.ok(
    previews.some(item => item.response.code === 0 && item.response.data.decision === 'PRESERVE'),
    '补齐后的真实检查应允许保留转换'
  )
  assert.deepEqual(errors, [], '页面不应有脚本异常')
  assert.deepEqual(blockedWrites, [], '交互不应尝试发出持久写入')
} catch (error) {
  failure = error
  await page
    ?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true, animations: 'disabled' })
    .catch(() => {})
} finally {
  await Promise.allSettled(pendingPreviews)
  await browser.close()
  const after = await snapshot()
  const unchanged = JSON.stringify(after) === JSON.stringify(before)
  if (!unchanged && !failure) failure = new Error('前后对象设计或业务数据发生变化，请检查并发操作；本脚本未发出写入')
  if (unchanged) checks.push('前后设计快照与全部业务记录完全一致')
  const result = {
    time: new Date().toISOString(),
    objectId,
    fieldId: field.id,
    fieldCode,
    passed: !failure,
    checks,
    blockedWrites,
    errors,
    snapshotsUnchanged: unchanged,
    recordCount: before.total,
    previews,
    failure: failure?.message
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ ...result, previews: previews.length, output }))
}
if (failure) throw failure
