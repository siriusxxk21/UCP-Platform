import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 正式开发服务：仅创建本次唯一前缀的对象与应用，不改用户的体验配置。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
if (process.env.NOCODE_VERIFY_PREFIX) ac.prefix = process.env.NOCODE_VERIFY_PREFIX
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-reference', ac.prefix)
ac.output = out
const checks = []
const errors = []
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
let browser, page, failure
const shot = name => page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
const item = (scope, name) =>
  scope
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + name + '$') }) })

try {
  if (process.env.NOCODE_VERIFY_PREFIX) {
    const previous = JSON.parse(await readFile(resolve(out, 'http-result.json'), 'utf8'))
    assert.equal(previous.prefix, ac.prefix)
    ac.owned = previous.owned
  }
  await ac.login()
  const supplierName = `复核供应商${ac.prefix}`
  const purchaseName = `复核采购单${ac.prefix}`
  const supplier = await ac.object('supplier', supplierName, [ac.field('name', 'TEXT', '供应商名称')])
  const purchase = await ac.object('purchase', purchaseName, [ac.field('name', 'TEXT', '采购单号')])
  ac.app = ac.owned.applications.length
    ? await ac.api(`/nocode/application/get?id=${ac.owned.applications[0]}`)
    : await ac.api('/nocode/application/save', {
        id: null,
        expectedRevision: null,
        code: ac.prefix + '_reference',
        name: '对象关系与连续引用复核 ' + ac.prefix,
        description: '本轮可识别的 UI 复核夹具',
        definition: { objects: [], resources: [] }
      })
  if (!ac.owned.applications.includes(ac.app.application.id)) ac.owned.applications.push(ac.app.application.id)
  await ac.persist()

  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  const info = await ac.api('/system/auth/get-permission-info')
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(info.menus || []))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )

  await page.goto(origin + `/nocode/object/editor?id=${purchase.objectId}`)
  await expect(page.getByRole('heading', { name: '表单验收' + purchaseName, exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: /对象关系/ })).toBeVisible()
  // 标题先于完整设计/权限就绪；必须等到浏览态或草稿态操作出现，不能用瞬时 count 跳过编辑。
  await expect
    .poll(
      async () =>
        (await page.getByRole('button', { name: '编辑新草稿', exact: true }).count()) +
        (await page.getByRole('button', { name: /保存草稿$/ }).count())
    )
    .toBeGreaterThan(0)
  if (await page.getByRole('button', { name: '编辑新草稿', exact: true }).count())
    await page.getByRole('button', { name: '编辑新草稿', exact: true }).click()
  await page.getByRole('tab', { name: /对象关系/ }).click()
  await expect(page.getByRole('button', { name: /新增关系$/ })).toBeVisible()
  const existingRelation = page.getByText('配置', { exact: true }).filter({ visible: true })
  const editing = (await existingRelation.count()) > 0
  if (editing) await existingRelation.first().click()
  else await page.getByRole('button', { name: /新增关系$/ }).click()
  const relation = page.locator('.ant-modal-content:visible').filter({ hasText: '对象关系' })
  await item(relation, '关系名称').locator('input').fill('供应商')
  if (!editing) await item(relation, '关系编码').locator('input').fill('supplier')
  await expect(item(relation, '在哪里选择关联记录')).toContainText('主表')
  if (!editing) {
    const target = item(relation, '从哪份资料选择').locator('input')
    await target.fill(supplierName)
    await page.locator('.ant-select-dropdown:visible').getByText(new RegExp(supplierName)).click()
  }
  await relation.getByRole('button', { name: /确.*定$/ }).click()
  await expect(relation).toHaveCount(0)
  const saved = page.waitForResponse(
    response => response.url().includes('/nocode/design/save') && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  const savedResponse = await saved
  const body = savedResponse.request().postDataJSON()
  const result = await savedResponse.json()
  assert.equal(result.code, 0, '主表关系应保存成功')
  assert.equal(body.relations[0].sourceDetailId ?? null, null, '主表来源必须为空值，不能发送空字符串')
  const draft = await ac.api(`/nocode/design/get?id=${purchase.objectId}`)
  assert.equal(draft.relations[0].sourceDetailId ?? null, null)
  await shot('01-主表关系保存成功')
  checks.push('默认主表清晰显示；关系保存真实草稿成功；请求未发送空字符串，回读未绑定任何明细')

  await page.goto(origin + `/nocode-app/workspace?id=${ac.app.application.id}`)
  await page.getByRole('button', { name: /引用对象$/ }).click()
  const picker = page.locator('.ant-modal-content:visible').filter({ hasText: '引用已发布对象' })
  const search = picker.getByRole('textbox', { name: '查询可引用对象' })
  await search.fill(ac.prefix)
  await expect(picker.locator('tbody tr[data-row-key]')).toHaveCount(2)
  const supplierRow = picker.locator('tbody tr[data-row-key]').filter({ hasText: supplierName })
  await supplierRow.getByRole('button', { name: /^引\s*用$/ }).click()
  await expect(picker).toBeVisible()
  await expect(search).toHaveValue(ac.prefix)
  await expect(supplierRow.getByRole('button', { name: '已引用', exact: true })).toBeDisabled()
  const purchaseRow = picker.locator('tbody tr[data-row-key]').filter({ hasText: purchaseName })
  await purchaseRow.getByRole('button', { name: /^引\s*用$/ }).click()
  await expect(picker).toBeVisible()
  await expect(picker.getByText('当前已引用 2 个对象，可连续添加。')).toBeVisible()
  await expect(purchaseRow.getByRole('button', { name: '已引用', exact: true })).toBeDisabled()
  await shot('02-自动搜索与连续引用')
  await search.fill('不应存在的对象' + ac.prefix)
  await expect(picker.locator('tbody tr[data-row-key]')).toHaveCount(0)
  await search.fill(supplierName)
  await expect(picker.locator('tbody tr[data-row-key]')).toHaveCount(1)
  await expect(picker.getByRole('button', { name: '已引用', exact: true })).toBeDisabled()
  await picker.getByRole('button', { name: '完成选择', exact: true }).click()
  const appSaved = page.waitForResponse(
    response => response.url().includes('/nocode/application/save') && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await appSaved).json()).code, 0)
  await page.reload()
  await expect(page.getByText('表单验收' + supplierName, { exact: true })).toBeVisible()
  await expect(page.getByText('表单验收' + purchaseName, { exact: true })).toBeVisible()
  checks.push('输入自动搜索及无匹配反馈；连续引用两个对象不关窗、不丢关键词、禁止重复；完成选择后保存和刷新保留引用')
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
    JSON.stringify({ time: new Date().toISOString(), checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output: out, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
