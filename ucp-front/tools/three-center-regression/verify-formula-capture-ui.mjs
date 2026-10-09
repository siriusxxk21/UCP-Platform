import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/formula-capture-ui', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
try {
  await ac.login()
  const object = await ac.object(
    'capture',
    '公式留存 ' + ac.prefix,
    [
      ac.field('name', 'TEXT', '名称'),
      ac.field('account', 'TEXT', '账户'),
      ac.field('serial', 'INTEGER', '流水序号'),
      ac.field('direction', 'TEXT', '收支类型'),
      ac.field('amount', 'DECIMAL', '发生金额'),
      ac.field('net', 'FORMULA', '本笔净变动'),
      ac.field('balance', 'FORMULA', '实时余额'),
      ac.field('confirmed', 'DECIMAL', '确认余额')
    ],
    {
      net: ac.option({
        expression: "if(direction = 'IN', amount, -amount)",
        resultType: 'DECIMAL',
        calculation: ac.calculation('LOCAL')
      }),
      balance: ac.option({
        expression: 'net',
        resultType: 'DECIMAL',
        calculation: ac.calculation('SEQUENCE', 'LIVE', {
          groupFields: ['account'],
          sequence: {
            orderField: 'serial',
            tieBreakerField: null,
            direction: 'PREVIOUS',
            operation: 'CUMULATIVE',
            initialValue: '1000'
          }
        })
      })
    }
  )
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_capture',
    name: '公式留存界面验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: []
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(object, ac.grant(object, { computeFields: object.definition.fields.map(field => field.id) }))
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
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
  await page.goto(origin + '/nocode-app/workspace?id=' + ac.app.application.id)
  await page.getByRole('tab', { name: '业务配置', exact: true }).click()
  await page.locator('.ant-segmented-item').filter({ hasText: '业务动作' }).click()
  await page.getByRole('button', { name: /新增业务动作$/ }).click()
  const modal = page.locator('.ant-modal-content:visible').filter({ hasText: '新增业务动作' })
  const item = label =>
    modal
      .locator('.ant-form-item')
      .filter({ has: page.locator('.ant-form-item-label').filter({ hasText: new RegExp('^' + label + '$') }) })
  async function select(label, text) {
    await item(label).locator('.ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: text }).click()
    await item(label).locator('.ant-form-item-label').click()
  }
  await item('名称').getByRole('textbox').fill('确认本笔余额')
  await item('编码').getByRole('textbox').fill('capture_balance')
  await select('数据对象', object.definition.objectName)
  await select('动作类型', '留存计算结果')
  await modal.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(modal.locator('.ant-alert-error')).toContainText('留存字段')
  await select('留存到字段', '确认余额')
  await select('来源公式', '实时余额')
  await expect(modal).toContainText('重复执行将被拒绝')
  await page.screenshot({
    path: resolve(output, '01-capture-configuration.png'),
    fullPage: true,
    animations: 'disabled'
  })
  await modal.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(modal).toBeHidden()
  const saving = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/save') && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await saving).json()).code, 0)
  ac.app = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  const action = ac.app.draft.resources.find(resource => resource.code === 'capture_balance')
  assert.equal(action.config.kind, 'CAPTURE_VALUES')
  assert.deepEqual(action.config.captures, { [object.ids.confirmed]: object.ids.balance })
  checks.push('真实Chrome新增手动留存动作；空映射阻断，选择实时余额→确认余额，应用和HTTP保存契约正确')
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '公式留存业务动作界面验收'
  })
  const first = await ac.save(object, { name: '第一笔', account: 'A', serial: '1', direction: 'IN', amount: '200' })
  let last = await ac.save(object, { name: '第二笔', account: 'A', serial: '2', direction: 'OUT', amount: '80' })
  const before = await ac.get(object, last)
  assert.equal(Number(before.record.values[object.ids.balance]), 1120)
  assert.equal(before.record.values[object.ids.confirmed] ?? null, null)
  await page.goto(origin + '/nocode-app/runtime?id=' + ac.app.application.id)
  async function execute(name) {
    const row = page.locator('.ant-table-row').filter({ hasText: name })
    await row.getByRole('button', { name: /确认本笔余额$/ }).click()
    const request = page.waitForResponse(response => response.url().endsWith('/nocode/runtime/action'))
    await page
      .locator('.ant-popconfirm:visible')
      .getByRole('button', { name: /^(确\s*定|是)$/ })
      .click()
    return (await request).json()
  }
  assert.equal((await execute('第二笔')).code, 0)
  let record = await ac.get(object, last)
  assert.equal(Number(record.record.values[object.ids.confirmed]), 1120)
  last = { ...last, revision: record.record.revision }
  await page.screenshot({ path: resolve(output, '02-confirmed-balance.png'), fullPage: true, animations: 'disabled' })
  await ac.save(object, { amount: '220' }, first)
  record = await ac.get(object, last)
  assert.equal(Number(record.record.values[object.ids.balance]), 1140)
  assert.equal(Number(record.record.values[object.ids.confirmed]), 1120)
  last = await ac.save(object, { name: '第二笔已备注' }, { ...last, revision: record.record.revision })
  record = await ac.get(object, last)
  assert.equal(Number(record.record.values[object.ids.confirmed]), 1120)
  await page.reload()
  const changed = page.locator('.ant-table-row').filter({ hasText: '第二笔已备注' })
  await expect(changed).toContainText('1140')
  await expect(changed).toContainText('1120')
  await page.locator('.ant-table-body').evaluate(element => {
    element.scrollLeft = element.scrollWidth
  })
  await page.screenshot({ path: resolve(output, '03-live-and-captured.png'), fullPage: true, animations: 'disabled' })
  const duplicate = await execute('第二笔已备注')
  assert.notEqual(duplicate.code, 0)
  assert.match(duplicate.msg, /已留存|重复/)
  record = await ac.get(object, last)
  assert.equal(Number(record.record.values[object.ids.confirmed]), 1120)
  await page.screenshot({ path: resolve(output, '04-duplicate-rejected.png'), fullPage: true, animations: 'disabled' })
  checks.push(
    '发布后真实运行业务动作将1120留存；历史入金变化实时余额1140而确认值1120不变，普通备注编辑不覆盖，重复留存被拒绝'
  )
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
    await writeFile(resolve(output, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  await browser?.close()
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await mkdir(output, { recursive: true })
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
