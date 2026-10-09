import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅为已完成 A/B 升级验收的自有夹具创建下一版草稿，浏览器阶段拦截所有持久写入。
const argument = process.argv.indexOf('--fixture')
assert.ok(argument >= 0, '必须提供本轮升级验收夹具文件')
const fixture = JSON.parse(await readFile(resolve(process.argv[argument + 1]), 'utf8'))
assert.match(fixture.prefix, /^e2efc[a-z0-9]+$/)
assert.equal(fixture.published, true)
assert.equal(fixture.resumedB, true)
const output = resolve(fixture.output)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
await ac.login()
const objectId = fixture.source.objectId
const getDesign = () => ac.api(`/nocode/design/get?id=${objectId}`)
const records = () => ac.api('/nocode/object-data/page', { objectId, pageNo: 1, pageSize: 100 })
let current = await getDesign()
assert.equal(current.draft.objectCode, fixture.prefix + '_source')
assert.equal(current.draft.objectName, '表单验收字段转换 ' + fixture.prefix)
assert.equal(current.publishedVersion, 2)
if (current.draft.state !== 'DRAFT') {
  await ac.api('/nocode/design/edit', {
    id: objectId,
    expectedLockVersion: current.draft.lockVersion,
    reason: '独立升级验收：仅检查新提示，不应用字段变更'
  })
  current = await getDesign()
}
assert.equal(current.draft.versionNo, 3)
const before = { design: current, records: await records() }
assert.equal(before.records.total, 3)
const fieldIndex = current.draft.fields.findIndex(field => field.code === 'numeric_text')
assert.ok(fieldIndex >= 0)
assert.equal(current.draft.fields[fieldIndex].type, 'INTEGER')
const errors = []
const blockedWrites = []
const previews = []
const pending = []
const readonlyPosts = new Set([
  '/nocode/design/field-switch-preview',
  '/nocode/design/field-switch-preview-rows',
  '/nocode/object-data/page'
])
const browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
page.setDefaultTimeout(20000)
try {
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (
      ['GET', 'HEAD', 'OPTIONS'].includes(request.method()) ||
      (request.method() === 'POST' && readonlyPosts.has(path))
    ) {
      await route.continue()
    } else {
      blockedWrites.push({ method: request.method(), path })
      await route.abort('blockedbyclient')
    }
  })
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', response => {
    if (response.url().includes('/nocode/design/field-switch-preview')) {
      pending.push(response.json().then(result => previews.push(result)))
    }
  })
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, ac.tokens.admin)
  await page.goto(`${process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'}/nocode/object/editor?id=${objectId}`)
  const row = page.locator('tr').filter({
    has: page.getByRole('textbox', { name: `第 ${fieldIndex + 1} 行字段名称`, exact: true })
  })
  await row.getByRole('button', { name: /配置/ }).click()
  const fieldDialog = page.locator('.ant-drawer-content:visible, .ant-modal-content:visible').filter({
    has: page.locator('.ant-drawer-title, .ant-modal-title').filter({ hasText: /^字段配置$/ })
  })
  await fieldDialog
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^字段类型$/ }) })
    .locator('.ant-select')
    .click()
  await page.locator('.ant-select-dropdown:visible').getByText('单行文本', { exact: true }).click()
  const modal = page.locator('.ant-modal-content:visible').filter({
    has: page.locator('.ant-modal-title').filter({ hasText: /^检查字段变更/ })
  })
  await expect(modal).toContainText('本列 3 条有值')
  await expect(modal).toContainText('字段升级应用 B')
  await expect(modal).not.toContainText('请先调整或删除此配置并发布应用')
  await expect(modal).not.toContainText('旧应用继续使用原对象版本')
  await expect(modal).toContainText('暂停')
  await modal.locator('.ant-modal-body').evaluate(element => {
    element.scrollTop = 0
  })
  await page.screenshot({
    path: resolve(output, '07-最新提示仅说明影响与暂停路径.png'),
    fullPage: true,
    animations: 'disabled'
  })
  await modal.getByRole('button', { name: '取消本次变更', exact: true }).click()
  await expect(fieldDialog.locator('.ant-select-selection-item').filter({ hasText: /^整数$/ })).toBeVisible()
  await fieldDialog.getByRole('button', { name: /取\s*消/, exact: true }).click()
  await Promise.all(pending)
  assert.ok(previews.length > 0)
  assert.ok(previews.every(result => result.code === 0))
  assert.doesNotMatch(JSON.stringify(previews), /请先调整或删除此配置并发布应用/)
  assert.deepEqual(blockedWrites, [])
  assert.deepEqual(errors, [])
  assert.deepEqual({ design: await getDesign(), records: await records() }, before)
  const model = await ac.api(`/nocode/object-data/model?objectId=${objectId}`)
  assert.equal(model.columnTypes[current.draft.fields[fieldIndex].id], 'bigint')
  const samples = []
  for (let id = 5768; id <= 5774; id++) {
    const design = await ac.api(`/nocode/design/get?id=${id}`)
    samples.push({
      id: String(id),
      name: design.draft.objectName,
      draftVersion: design.draft.versionNo,
      draftState: design.draft.state,
      publishedVersion: design.publishedVersion
    })
  }
  const example = samples.find(sample => sample.id === '5769')
  // 用户可同时编辑体验对象；只记录实际版本，不把此前版本假设当作可回退的状态。
  assert.ok(example)
  const result = {
    time: new Date().toISOString(),
    passed: true,
    objectId,
    draftVersion: 3,
    publishedVersion: 2,
    snapshotsUnchanged: true,
    recordCount: before.records.total,
    physicalType: 'bigint',
    errors,
    blockedWrites,
    previews: previews.map(item => item.data),
    exampleVersionMatchesEarlierExpectation: example.draftVersion === 2 && example.draftState === 'DRAFT',
    preservedExamples: samples
  }
  await writeFile(resolve(output, 'copy-result.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ passed: true, objectId, snapshotsUnchanged: true, preservedExamples: samples }))
} catch (error) {
  await page
    .screenshot({ path: resolve(output, 'copy-failure.png'), fullPage: true, animations: 'disabled' })
    .catch(() => {})
  await writeFile(
    resolve(output, 'copy-failure.json'),
    JSON.stringify({ message: error.message, errors, blockedWrites, previews }, null, 2)
  )
  throw error
} finally {
  await browser.close()
}
