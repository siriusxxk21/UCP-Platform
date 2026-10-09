import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix += randomBytes(3).toString('hex')
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-table-calculation', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
const baseFields = [
  ac.field('name', 'TEXT', '名称'),
  ac.field('account', 'TEXT', '账户'),
  ac.field('booked', 'DATE', '记账日期'),
  ac.field('sequence', 'INTEGER', '同日序号'),
  ac.field('incoming', 'DECIMAL', '入金'),
  ac.field('outgoing', 'DECIMAL', '出金'),
  ac.field('opening', 'DECIMAL', '期初')
]
const formItem = (root, label) =>
  root.locator('.ant-form-item').filter({
    has: page.locator('.ant-form-item-label').filter({ hasText: new RegExp(`^${label}$`) })
  })
async function select(root, label, option) {
  const item = formItem(root, label)
  await item.locator('.ant-select-selector').click()
  const listId = await item.getByRole('combobox').getAttribute('aria-controls')
  assert.ok(listId)
  const popup = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${listId}"]`) })
  await popup.locator('.ant-select-item-option').filter({ hasText: option }).click()
  await item.locator('.ant-form-item-label').click()
}
async function addFormula(code, name) {
  await page.getByRole('button', { name: /新增字段$/ }).click()
  const row = page.locator('.field-designer .ant-table-row').last()
  await row.getByRole('textbox', { name: /字段名称$/ }).fill(name)
  await row.getByRole('textbox', { name: /字段编码$/ }).fill(code)
  await row.locator('.ant-select-selector').click()
  const choice = page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: /^公式$/ })
  await page.locator('.ant-select-dropdown:visible .rc-virtual-list-holder').hover()
  await page.mouse.wheel(0, 2000)
  await choice.click()
  await row.getByRole('button', { name: /配置$/ }).click()
  await page.locator('.ant-drawer-content:visible').getByRole('button', { name: '配置公式', exact: true }).click()
  return page.locator('.ant-modal-content:visible').filter({ hasText: '配置计算公式' })
}
async function reopen(code) {
  const row = page.locator('.field-designer .ant-table-row').filter({ has: page.locator(`input[value="${code}"]`) })
  await row.getByRole('button', { name: /配置$/ }).click()
  await page.locator('.ant-drawer-content:visible').getByRole('button', { name: '配置公式', exact: true }).click()
  return page.locator('.ant-modal-content:visible').filter({ hasText: '配置计算公式' })
}
async function confirm(drawer) {
  await drawer.getByRole('button', { name: '应用到字段', exact: true }).click()
  await expect(drawer).toBeHidden()
  const field = page.locator('.ant-drawer-content:visible')
  await field.getByRole('button', { name: '确 定', exact: true }).click()
  await expect(field).toBeHidden()
}
try {
  await ac.login()
  const draft = await ac.api('/nocode/design/save', {
    draft: {
      id: null,
      expectedLockVersion: null,
      objectCode: ac.prefix + '_calc',
      objectName: '全表计算验收 ' + ac.prefix,
      description: '全表统计与累计余额独立验收夹具',
      tableName: 'biz_' + ac.prefix + '_calc',
      titleFieldKey: 'name',
      fields: baseFields,
      removedFieldIds: []
    },
    settings: {},
    fieldOptions: {},
    relations: [],
    indexes: [],
    details: []
  })
  ac.owned.objects.push({ id: draft.draft.id, code: draft.draft.objectCode })
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
  await page.goto(origin + '/nocode/object/editor?id=' + draft.draft.id)
  await expect(page.locator('.field-designer .ant-table-row')).toHaveCount(baseFields.length)
  let drawer = await addFormula('total', '入金统计')
  await select(drawer, '计算方式', '汇总统计')
  await drawer.getByRole('button', { name: '应用到字段', exact: true }).click()
  await expect(drawer).toContainText('请选择基本数值类型的取值字段')
  await select(drawer, '结果处理', '记录数')
  await expect(formItem(drawer, '取值字段')).toHaveCount(0)
  await expect(formItem(drawer, '结果类型')).toContainText('整数')
  await select(drawer, '结果处理', '求和')
  await select(drawer, '取值字段', '入金（incoming）')
  await select(drawer, '分组字段', '账户（account）')
  await expect(formItem(drawer, '结果类型')).toContainText('小数')
  await page.screenshot({ path: resolve(output, '01-statistics-config.png'), fullPage: true, animations: 'disabled' })
  await confirm(drawer)
  checks.push('真实控件配置全表统计、分组、COUNT/SUM结果类型切换；缺少取值字段禁止确认')

  drawer = await addFormula('balance', '累计余额')
  await select(drawer, '计算方式', '顺序计算')
  await select(drawer, '顺序计算方式', '增减值累计')
  await select(drawer, '增加值字段', '入金（incoming）')
  await drawer.getByRole('button', { name: '应用到字段', exact: true }).click()
  await expect(drawer).toContainText('请选择有效的累计顺序字段')
  await select(drawer, '减少值字段', '出金（outgoing）')
  await select(drawer, '累计顺序字段', '记账日期（booked）')
  await select(drawer, '同序字段', '同日序号（sequence）')
  await select(drawer, '分组字段', '账户（account）')
  await drawer.getByRole('radio', { name: '本组第一条记录的字段', exact: true }).check()
  await select(drawer, '首条初始值字段', '期初（opening）')
  await drawer.getByRole('radio', { name: '固定初始值', exact: true }).check()
  await formItem(drawer, '固定初始值').getByRole('spinbutton').fill('100')
  await formItem(drawer, '固定初始值').getByRole('spinbutton').blur()
  await expect(formItem(drawer, '更新方式').getByRole('radio')).toHaveCount(2)
  await expect(formItem(drawer, '更新方式').getByRole('radio').first()).toBeEnabled()
  await expect(formItem(drawer, '更新方式').getByRole('radio').first()).toBeChecked()
  await formItem(drawer, '更新方式').getByRole('radio', { name: '读取时计算', exact: true }).check()
  await formItem(drawer, '更新方式').getByRole('radio', { name: '保存时落库，同组联动', exact: true }).check()
  await expect(formItem(drawer, '结果处理').locator('.ant-select')).toHaveClass(/ant-select-disabled/)
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.screenshot({ path: resolve(output, '02-running-config.png'), fullPage: true, animations: 'disabled' })
  await confirm(drawer)
  const saving = page.waitForResponse(
    response => response.url().endsWith('/nocode/design/save') && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await saving).json()).code, 0)
  const saved = await ac.api('/nocode/design/get?id=' + draft.draft.id)
  const savedFields = Object.fromEntries(saved.draft.fields.map(field => [field.code, field.id]))
  const balance = saved.fieldOptions[savedFields.balance].calculation
  assert.deepEqual(balance.runningTotal, {
    orderField: 'booked',
    tieBreakerField: 'sequence',
    subtractField: 'outgoing',
    initialValue: '100',
    initialField: null
  })
  assert.deepEqual(balance.groupFields, ['account'])
  assert.equal(saved.fieldOptions[savedFields.total].calculation.mode, 'STATISTICS')
  await page.reload()
  drawer = await reopen('balance')
  await expect(formItem(drawer, '固定初始值').getByRole('spinbutton')).toHaveValue('100')
  await expect(formItem(drawer, '累计顺序字段')).toContainText('记账日期（booked）')
  await expect(formItem(drawer, '分组字段')).toContainText('账户（account）')
  await confirm(drawer)
  assert.equal(balance.updateMode, 'ON_SAVE')
  checks.push('真实控件验证累计顺序必填、默认落库且可选LIVE、SUM锁定、期初互斥；HTTP保存及刷新重开配置保持一致')

  const object = await ac.object('calc', '全表计算', baseFields)
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_calc',
    name: '全表计算验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: []
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(
    object,
    ac.grant(object, {
      computeFields: object.definition.fields.filter(field => field.type !== 'FORMULA').map(field => field.id)
    })
  )
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '全表计算独立验收'
  })
  const late = await ac.save(object, {
    name: '晚笔',
    account: 'A',
    booked: '2026-09-02',
    sequence: 1,
    incoming: '0.2',
    outgoing: '20'
  })
  const first = await ac.save(object, {
    name: '补录首笔',
    account: 'A',
    booked: '2026-09-01',
    sequence: 1,
    incoming: '10.1',
    outgoing: '0'
  })
  const other = await ac.save(object, {
    name: '独立账户',
    account: 'B',
    booked: '2026-09-01',
    sequence: 1,
    incoming: '5'
  })
  let result = await ac.get(object, late)
  assert.equal(result.record.values[object.ids.balance], '90.3000000000')
  assert.equal(result.record.values[object.ids.total], '10.3000000000')
  assert.equal((await ac.get(object, other)).record.values[object.ids.balance], '105.0000000000')
  const subset = await ac.page(object, undefined, {
    search: '晚笔',
    pageSize: 1,
    sortFieldId: object.ids.booked,
    descending: true
  })
  assert.equal(subset.list.length, 1)
  assert.equal(subset.list[0].values[object.ids.balance], '90.3000000000')
  checks.push('真实对象发布、应用固定版本、共享计算授权、补录历史流水与账户隔离；筛选分页不改变90.3余额')
  await page.goto(origin + '/nocode-app/runtime?id=' + ac.app.application.id)
  await expect(page.locator('.application-runtime')).toContainText('晚笔')
  const lateRow = page.locator('.ant-table-row').filter({ hasText: '晚笔' })
  await expect(lateRow).toContainText('90.3')
  await expect(lateRow).toContainText('10.3')
  await page.screenshot({ path: resolve(output, '03-runtime-balances.png'), fullPage: true, animations: 'disabled' })
  const edited = await ac.save(object, { incoming: '30.1' }, first)
  result = await ac.get(object, late)
  assert.equal(result.record.values[object.ids.balance], '110.3000000000')
  await ac.remove(object, edited)
  result = await ac.get(object, late)
  assert.equal(result.record.values[object.ids.balance], '80.2000000000')
  await page.reload()
  await expect(page.locator('.ant-table-row').filter({ hasText: '晚笔' })).toContainText('80.2')
  await page.screenshot({
    path: resolve(output, '04-after-history-deletion.png'),
    fullPage: true,
    animations: 'disabled'
  })
  checks.push('实际应用页面显示统计和余额；历史修改后110.3、删除后80.2，刷新展示一致')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
