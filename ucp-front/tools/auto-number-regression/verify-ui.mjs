import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 当前开发环境的字段配置交互复核；仅创建自有草稿并保存规则，不发布或写业务记录。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/auto-number-ui', ac.prefix)
ac.output = out
const checks = [],
  errors = []
let browser, page, failure
try {
  await ac.login()
  const code = `${ac.prefix}_number_ui`
  const object = await ac.api('/nocode/design/save', {
    draft: {
      id: null,
      expectedLockVersion: null,
      objectCode: code,
      objectName: `自动编号交互验收 ${ac.prefix}`,
      description: '自动编号规则 UI 专用夹具',
      tableName: `biz_${code}`,
      titleFieldKey: 'name',
      fields: [ac.field('name', 'TEXT', '名称'), ac.field('serial', 'TEXT', '主表编号')],
      removedFieldIds: []
    },
    settings: {},
    fieldOptions: {},
    relations: [],
    indexes: [],
    details: [
      {
        id: null,
        code: 'items',
        name: '编号明细',
        tableName: `biz_${ac.prefix}_items`,
        state: 'ACTIVE',
        fields: [ac.field('detail_serial', 'TEXT', '明细编号')],
        fieldOptions: {},
        indexes: []
      }
    ]
  })
  ac.owned.objects.push({ id: object.draft.id, code })
  await ac.persist()
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.setDefaultTimeout(15000)
  page.on('pageerror', e => errors.push(e.message))
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
    (process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173') + `/nocode/object/editor?id=${object.draft.id}`
  )
  const modal = page.locator('.ant-modal-content:visible').filter({ hasText: '字段配置' })
  async function choose(locator, text) {
    const select = locator.locator(
      'xpath=ancestor-or-self::*[contains(concat(" ",normalize-space(@class)," ")," ant-select ")]'
    )
    await select.click()
    if (text === '自动编号' || text === '单行文本') {
      const input = select.getByRole('combobox')
      const active = page.locator(
        '.ant-select-dropdown:visible .ant-select-item-option-active .ant-select-item-option-content'
      )
      for (let i = 0; i < 35; i++) {
        const previous = await active.innerText()
        if (previous === text) break
        await input.press('ArrowDown')
        await expect(active).not.toHaveText(previous)
      }
      await expect(active).toHaveText(text)
      await input.press('Enter')
      await expect(select.locator('.ant-select-selection-item')).toHaveText(text)
      return
    }
    const dropdown = page.locator('.ant-select-dropdown:visible')
    if (!(await dropdown.getByText(text, { exact: true }).count())) {
      const holder = dropdown.locator('.rc-virtual-list-holder')
      await holder.evaluate((el, last) => {
        el.scrollTop = last ? el.scrollHeight : 0
      }, text !== '单行文本')
    }
    await page.locator('.ant-select-dropdown:visible').getByText(text, { exact: true }).click()
  }
  async function closeCancel() {
    await modal.getByRole('button', { name: /^取\s*消$/ }).click()
    const confirm = page.locator('.ant-modal-confirm:visible')
    if (await confirm.count()) await confirm.getByRole('button', { name: /放弃|不保存|确认离开/ }).click()
    await expect(modal).toHaveCount(0)
  }
  const main = page
    .locator('.ant-table-tbody:visible tr')
    .filter({ has: page.getByRole('textbox', { name: '第 2 行字段名称', exact: true }) })
  await choose(main.getByRole('combobox'), '自动编号')
  await main.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('')
  await modal.getByRole('textbox', { name: '编号固定前缀' }).fill('CANCEL-')
  await closeCancel()
  await main.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('')
  checks.push('主表切换自动编号默认规则可见；取消配置未写回字段')
  await modal.getByRole('textbox', { name: '编号固定前缀' }).fill('HT-')
  await choose(modal.locator('[aria-label="流水号重置周期"]'), '每天重新计数')
  await expect(modal.getByText(/日期格式须包含重置周期/).first()).toBeVisible()
  await modal.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(modal).toBeVisible()
  await choose(modal.locator('[aria-label="编号日期格式"]'), '年月日 · 20260914')
  await modal.getByRole('spinbutton', { name: '流水号位数', exact: true }).fill('4')
  await modal.getByRole('spinbutton', { name: '流水号起始值' }).fill('7')
  await expect(modal.locator('.ant-alert-error')).toHaveCount(0)
  const stamp = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  })
    .format(new Date())
    .replaceAll('-', '')
  await expect(modal.locator('.auto-number-preview strong')).toHaveText(`HT-${stamp}0007`)
  await expect(modal.getByText(`下一条：HT-${stamp}0008`, { exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(out, '01-main-rule.png'), fullPage: true, animations: 'disabled' })
  await modal.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(modal).toHaveCount(0)
  await main.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('HT-')
  await expect(modal.getByRole('spinbutton', { name: '流水号起始值' })).toHaveValue('7')
  await modal.getByRole('button', { name: /^取\s*消$/ }).click()
  checks.push('无日期按日重置保存被拦截；合法格式实时展示两条示例，确定后重开保留规则')
  await choose(main.getByRole('combobox'), '单行文本')
  await choose(main.getByRole('combobox'), '自动编号')
  await main.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('')
  await expect(modal.getByRole('spinbutton', { name: '流水号起始值' })).toHaveValue('1')
  await modal.getByRole('button', { name: /^取\s*消$/ }).click()
  checks.push('切换到文本后再切回自动编号，旧规则被清理并采用默认值')
  await page.getByRole('tab', { name: '内部明细（1）' }).click()
  await page.getByRole('button', { name: '编号明细 · items' }).click()
  const detail = page
    .locator('.ant-table-tbody:visible tr')
    .filter({ has: page.getByRole('textbox', { name: '第 1 行字段名称', exact: true }) })
  await choose(detail.getByRole('combobox'), '自动编号')
  await detail.getByRole('button', { name: /配置/ }).click()
  await modal.getByRole('textbox', { name: '编号固定前缀' }).fill('LINE-')
  await expect(modal.locator('.auto-number-preview strong')).toHaveText('LINE-000001')
  await expect(modal.getByText(/内部明细中的字段/)).toBeVisible()
  await page.setViewportSize({ width: 900, height: 850 })
  await page.screenshot({ path: resolve(out, '02-detail-rule-900.png'), fullPage: true, animations: 'disabled' })
  await modal.getByRole('button', { name: /^确\s*定$/ }).click()
  await detail.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('LINE-')
  checks.push('内部明细同样可配置和重开；900 宽度下确定操作可达')
  await modal.getByRole('button', { name: /^取\s*消$/ }).click()
  await page.setViewportSize({ width: 1440, height: 1000 })
  const [savedResponse] = await Promise.all([
    page.waitForResponse(
      response => response.url().includes('/nocode/design/save') && response.request().method() === 'POST'
    ),
    page.getByRole('button', { name: /保存草稿/ }).click()
  ])
  assert.equal((await savedResponse.json()).code, 0)
  await page.reload()
  await page.getByRole('tab', { name: '主表字段', exact: true }).click()
  await main.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('')
  await expect(modal.getByRole('spinbutton', { name: '流水号位数', exact: true })).toHaveValue('6')
  await expect(modal.getByRole('spinbutton', { name: '流水号起始值' })).toHaveValue('1')
  await expect(modal.locator('.auto-number-preview strong')).toHaveText('000001')
  await modal.getByRole('button', { name: /^取\s*消$/ }).click()
  await page.getByRole('tab', { name: '内部明细（1）' }).click()
  await page.getByRole('button', { name: '编号明细 · items' }).click()
  await detail.getByRole('button', { name: /配置/ }).click()
  await expect(modal.getByRole('textbox', { name: '编号固定前缀' })).toHaveValue('LINE-')
  await expect(modal.locator('.auto-number-preview strong')).toHaveText('LINE-000001')
  await page.screenshot({ path: resolve(out, '03-rule-after-refresh.png'), fullPage: true, animations: 'disabled' })
  checks.push('真实点击保存草稿后刷新页面，主表默认规则和明细 LINE- 规则从 API 恢复')
  assert.deepEqual(errors, [])
} catch (e) {
  failure = e
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
    JSON.stringify(
      {
        time: new Date().toISOString(),
        checks,
        errors,
        failure: failure?.message,
        boundary: '规则通过真实保存草稿持久化并刷新回显；未发布对象或写入业务记录。'
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output: out, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
