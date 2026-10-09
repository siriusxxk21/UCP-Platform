import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const uiOnly = process.argv.includes('--ui-only')
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-formula-ui', ac.prefix)
ac.output = out
const checks = [],
  errors = []
let browser, page, failure
try {
  await ac.login()
  const object = await ac.object(
    'formula_ui',
    '公式试算界面' + ac.prefix,
    [
      ac.field('name', 'TEXT', '名称'),
      ac.field('qty', 'DECIMAL', '数量'),
      ac.field('price', 'DECIMAL', '单价'),
      ac.field('account', 'TEXT', '账户'),
      ac.field('direction', 'TEXT', '收支类型'),
      ac.field('serial', 'INTEGER', '流水序号'),
      ac.field('total', 'SUMMARY', '明细合计'),
      ac.field('amount', 'FORMULA', '行金额')
    ],
    {
      amount: ac.option({ expression: 'round(qty * price, 2)', resultType: 'DECIMAL' }),
      total: ac.option({ expression: 'sum(items.amount)', resultType: 'DECIMAL' })
    },
    [],
    [
      {
        id: null,
        code: 'items',
        name: '计算明细',
        tableName: `biz_${ac.prefix}_items`,
        state: 'ACTIVE',
        fields: [ac.field('amount', 'DECIMAL', '明细金额')],
        fieldOptions: {},
        indexes: []
      }
    ]
  )
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
  await page.goto(
    (process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173') + `/nocode/object/editor?id=${object.objectId}`
  )
  const drawer = page.locator('.ant-drawer-content:visible')
  const modal = page.locator('.ant-modal-content:visible').filter({ hasText: '配置计算公式' })
  await page
    .locator('.ant-table-tbody:visible tr')
    .filter({ hasText: '公式' })
    .getByRole('button', { name: /查看/ })
    .click()
  await drawer.getByRole('button', { name: '查看公式配置', exact: true }).click()
  await expect(modal.getByText('保留小数位数', { exact: true })).toBeVisible()
  await expect(modal.getByRole('button', { name: '应用到字段', exact: true })).toHaveCount(0)
  await modal.locator('.os-modal-form-toolbar button').last().click()
  await expect(modal).toBeHidden()
  await drawer.locator('.ant-drawer-close').click()
  await expect(drawer).toBeHidden()
  checks.push('已发布字段可以只读打开公式窗口并正常关闭，不显示应用入口')
  await page.getByRole('button', { name: '编辑新草稿', exact: true }).click()
  await page
    .locator('.ant-table-tbody:visible tr')
    .filter({ hasText: '公式' })
    .getByRole('button', { name: /配置/ })
    .click()
  await drawer.getByRole('button', { name: '配置公式', exact: true }).click()
  await expect(modal.getByText('保留小数位数', { exact: true })).toBeVisible()
  await modal.getByText('直接编辑表达式', { exact: true }).click()
  await modal.getByRole('textbox', { name: '计算表达式' }).fill('qty + price')
  await modal.getByRole('button', { name: /^取\s*消$/ }).click()
  await expect(modal).toBeHidden()
  await expect(drawer.locator('.formula-summary')).toContainText('round(qty * price, 2)')
  await drawer.getByRole('button', { name: '配置公式', exact: true }).click()
  await expect(modal.getByText('保留小数位数', { exact: true })).toBeVisible()
  await expect(modal).toContainText('留存计算结果')
  await page.screenshot({ path: resolve(out, '00-independent-editor.png'), fullPage: true, animations: 'disabled' })
  checks.push('独立公式大弹窗、规则与结果分栏、取消丢弃副本修改；重新打开原公式保持')
  if (!uiOnly) {
    await modal.getByText('填写样例值，试算结果', { exact: true }).click()
    await modal.getByRole('textbox', { name: '试算样例：数量' }).fill('3')
    await modal.getByRole('textbox', { name: '试算样例：单价' }).fill('12.345')
    const trialResponse = page.waitForResponse(response => response.url().includes('/nocode/design/formula-preview'))
    await modal.getByRole('button', { name: /^试\s*算$/ }).click()
    assert.equal((await (await trialResponse).json()).data.value, '37.04')
    await expect(modal.getByText('试算结果：37.04', { exact: true })).toBeVisible()
    await expect(modal.getByRole('textbox', { name: '试算样例：数量' })).toBeVisible()
    await page.screenshot({ path: resolve(out, '01-decimal-result.png'), fullPage: true, animations: 'disabled' })
    checks.push('选择式 ROUND 使用数字位数；真实服务试算 3 × 12.345 舍入为 37.04，并显示结果')
    await modal.getByRole('textbox', { name: '试算样例：数量' }).fill('')
    await expect(modal.getByText('试算结果：37.04', { exact: true })).toHaveCount(0)
    await modal.getByRole('button', { name: /^试\s*算$/ }).click()
    await expect(modal.getByText('结果为空；请检查样例值，或配置空值备用值。', { exact: true })).toBeVisible()
    checks.push('修改样例立即清理旧结果；空输入试算显示空值及处理方法')
    await modal.getByText('直接编辑表达式', { exact: true }).click()
    await modal.getByRole('textbox', { name: '计算表达式' }).fill('qty / price')
    await modal.getByRole('textbox', { name: '试算样例：数量' }).fill('3')
    await modal.getByRole('textbox', { name: '试算样例：单价' }).fill('0')
    await modal.getByRole('button', { name: /^试\s*算$/ }).click()
    await expect(modal.getByText(/除数不能为零/)).toBeVisible()
    await page.screenshot({ path: resolve(out, '02-zero-divisor.png'), fullPage: true, animations: 'disabled' })
    checks.push('切换表达式保留字段名称和样例，真实除零错误就地显示，无业务写入')
    await modal.getByRole('textbox', { name: '计算表达式' }).fill('if(price = 0, 0, qty / price)')
    await modal.getByRole('button', { name: /^试\s*算$/ }).click()
    await expect(modal.getByText('试算结果：0', { exact: true })).toBeVisible()
    checks.push('IF 比较与短路：除数为零时返回备用结果，不执行除法')
    const item = label =>
      modal
        .locator('.ant-form-item')
        .filter({ has: page.locator('.ant-form-item-label').filter({ hasText: new RegExp('^' + label + '$') }) })
    async function select(label, text) {
      await item(label).locator('.ant-select-selector').click()
      await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: text }).click()
      await item(label).locator('.ant-form-item-label').click()
    }
    await select('计算方式', '顺序计算')
    await select('顺序计算方式', '逐笔计算后累计')
    await select('顺序字段', '流水序号（serial）')
    await select('分组字段', '账户（account）')
    await item('固定初始值').getByRole('spinbutton').fill('1000')
    await item('固定初始值').getByRole('spinbutton').blur()
    await modal.getByRole('textbox', { name: '计算表达式' }).fill("if(direction = 'IN', price, -price)")
    await modal.locator('.os-modal-form-toolbar button').first().click()
    await expect(modal.locator('.os-modal-form-header')).toBeVisible()
    await modal.getByText('填写多行样例，查看分组与顺序计算结果', { exact: true }).click()
    const sample = async (row, name, value) =>
      modal.getByRole('textbox', { name: `样例 ${row}：${name}`, exact: true }).fill(value)
    for (const [row, account, serial, direction, price] of [
      [1, 'A', '2', 'OUT', '80'],
      [2, 'A', '1', 'IN', '200'],
      [3, 'B', '1', 'IN', '50']
    ]) {
      await sample(row, '账户', account)
      await sample(row, '流水序号', serial)
      await sample(row, '收支类型', direction)
      await sample(row, '单价', price)
    }
    const response = page.waitForResponse(res => res.url().includes('/nocode/design/formula-preview'))
    await modal.getByRole('button', { name: '按分组顺序试算', exact: true }).click()
    const result = await (await response).json()
    assert.equal(result.code, 0, result.msg)
    assert.deepEqual(
      result.data.rows.map(row => Number(row.value)),
      [1120, 1200, 1050]
    )
    await expect(modal.getByText('已计算 3 条样例。', { exact: false })).toBeVisible()
    await page.screenshot({ path: resolve(out, '03-sequence-preview.png'), fullPage: true, animations: 'disabled' })
    await sample(2, '单价', '100')
    await expect(modal.getByText('已计算 3 条样例。', { exact: false })).toHaveCount(0)
    const updated = page.waitForResponse(res => res.url().includes('/nocode/design/formula-preview'))
    await modal.getByRole('button', { name: '按分组顺序试算', exact: true }).click()
    assert.deepEqual(
      (await (await updated).json()).data.rows.map(row => Number(row.value)),
      [1020, 1100, 1050]
    )
    await modal.getByRole('button', { name: '应用到字段', exact: true }).click()
    await expect(modal).toBeHidden()
    await expect(drawer.locator('.formula-summary')).toContainText('逐笔计算后累计')
    await expect(drawer.locator('.formula-summary')).toContainText("if(direction = 'IN', price, -price)")
    await drawer.getByRole('button', { name: '配置公式', exact: true }).click()
    await expect(item('固定初始值').getByRole('spinbutton')).toHaveValue('1000')
    checks.push(
      '真实服务按账户/序号进行 IF 收支累计，乱序输入返回 1120/1200/1050；历史样例修改重算为1020/1100/1050，应用后重开配置保持'
    )
    await select('顺序计算方式', '相邻记录取值')
    await modal.getByText('直接编辑表达式', { exact: true }).click()
    await modal.getByRole('textbox', { name: '计算表达式' }).fill('price - coalesce(__previous_price, 0)')
    await modal.getByText('填写多行样例，查看分组与顺序计算结果', { exact: true }).click()
    for (const [row, account, serial, price] of [
      [1, 'A', '1', '10'],
      [2, 'A', '2', '20'],
      [3, 'B', '1', '30']
    ]) {
      await sample(row, '账户', account)
      await sample(row, '流水序号', serial)
      await sample(row, '单价', price)
    }
    const previous = page.waitForResponse(res => res.url().includes('/nocode/design/formula-preview'))
    await modal.getByRole('button', { name: '按分组顺序试算', exact: true }).click()
    assert.deepEqual(
      (await (await previous).json()).data.rows.map(row => Number(row.value)),
      [10, 10, 30]
    )
    await select('相邻记录', '下一条记录')
    const next = page.waitForResponse(res => res.url().includes('/nocode/design/formula-preview'))
    await modal.getByRole('button', { name: '按分组顺序试算', exact: true }).click()
    assert.deepEqual(
      (await (await next).json()).data.rows.map(row => Number(row.value)),
      [-10, 20, 30]
    )
    await page.screenshot({ path: resolve(out, '04-adjacent-preview.png'), fullPage: true, animations: 'disabled' })
    await modal.getByRole('button', { name: /^取\s*消$/ }).click()
    await expect(drawer.locator('.formula-summary')).toContainText('逐笔计算后累计')
    checks.push(
      '相邻多行试算：上一条差额10/10/30、下一条差额-10/20/30；首末空值备用与分组隔离正确，取消保留已应用的累计配置'
    )
    await drawer.getByRole('button', { name: '配置公式', exact: true }).click()
    await select('计算方式', '公式运算')
    await modal.getByRole('radio', { name: '包含计算结果', exact: true }).check()
    await modal.getByText('直接编辑表达式', { exact: true }).click()
    await expect(modal.getByRole('button', { name: '明细合计', exact: true })).toBeVisible()
    await modal.getByRole('textbox', { name: '计算表达式' }).fill('round(total * 0.9, 2)')
    await modal.getByText('填写样例值，试算结果', { exact: true }).click()
    await modal.getByRole('textbox', { name: '试算样例：明细合计', exact: true }).fill('100')
    const summaryTrial = page.waitForResponse(res => res.url().includes('/nocode/design/formula-preview'))
    await modal.getByRole('button', { name: /^试\s*算$/ }).click()
    const summaryResponse = await summaryTrial
    assert.equal(summaryResponse.request().postDataJSON().fieldTypes.total, 'DECIMAL')
    assert.equal(Number((await summaryResponse.json()).data.value), 90)
    await expect(modal.getByText('试算结果：90.00', { exact: true })).toBeVisible()
    await page.screenshot({
      path: resolve(out, '05-local-summary-preview.png'),
      fullPage: true,
      animations: 'disabled'
    })
    await modal.getByRole('radio', { name: '仅基础字段', exact: true }).check()
    await expect(modal.getByRole('button', { name: '明细合计', exact: true })).toHaveCount(0)
    await select('计算方式', '顺序计算')
    await expect(modal.getByRole('button', { name: '明细合计', exact: true })).toHaveCount(0)
    checks.push(
      '公式运算下包含计算结果可选择明细汇总，DECIMAL样例100乘0.9试算90；切回仅基础字段或顺序计算不提供SUMMARY候选'
    )
  }
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await page.screenshot({ path: resolve(out, 'failure.png'), fullPage: true }).catch(() => {})
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
