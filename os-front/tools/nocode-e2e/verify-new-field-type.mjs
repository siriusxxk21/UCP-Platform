import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只编辑新建页内存中的字段，不保存对象、发布或修改既有业务数据。
const output = resolve(`.work/new-field-type/${new Date().toISOString().replace(/[:.]/g, '-')}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
const checks = [],
  errors = [],
  requests = []
let browser, page, failure
try {
  await mkdir(output, { recursive: true })
  await ac.login()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
  page.setDefaultTimeout(15000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/nocode/'))
      requests.push(new URL(request.url()).pathname)
  })
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
  await page.goto((process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173') + '/nocode/object/editor')
  await expect(page.locator('.object-editor h2')).toHaveText('新建数据对象')
  await page.getByRole('button', { name: /新增字段/ }).click()
  const row = page.locator('.field-designer .ant-table-tbody > tr[data-row-key]').last()
  await row.locator('.field-type-select').click()
  await page.locator('.ant-select-dropdown:visible .rc-virtual-list-holder').hover()
  await page.mouse.wheel(0, 2000)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="公式"]').click()
  const drawer = page
    .locator('.ant-drawer-content:visible')
    .filter({ has: page.locator('.ant-drawer-title').filter({ hasText: /^字段配置$/ }) })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByRole('button', { name: '配置公式', exact: true })).toBeVisible()
  await expect(page.locator('.ant-modal-content:visible').filter({ hasText: '检查字段变更' })).toHaveCount(0)
  checks.push('新建未命名字段从默认文本改公式，直接显示字段配置，无转换检查弹窗')
  await page.screenshot({ path: resolve(output, '01-new-formula.png'), fullPage: true, animations: 'disabled' })

  const nameItem = drawer
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^字段名称$/ }) })
  await nameItem.locator('input').fill('公式体验字段')
  await drawer.getByRole('button', { name: '配置公式', exact: true }).click()
  const formula = page.locator('.ant-modal-content:visible').filter({ hasText: '配置计算公式 · 公式体验字段' })
  await expect(formula).toBeVisible()
  await expect(page.locator('.ant-modal-content:visible').filter({ hasText: '检查字段变更' })).toHaveCount(0)
  checks.push('补全名称后可正常进入公式来源、规则及结果配置')
  await page.screenshot({ path: resolve(output, '02-formula-editor.png'), fullPage: true, animations: 'disabled' })
  await formula.getByRole('button', { name: /^取\s*消$/ }).click()

  const typeItem = drawer
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^字段类型$/ }) })
  await typeItem.locator('.ant-select').click()
  await page.locator('.ant-select-dropdown:visible .rc-virtual-list-holder').hover()
  await page.mouse.wheel(0, -2000)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="整数"]').click()
  await expect(page.locator('.ant-modal-content:visible').filter({ hasText: '检查字段变更' })).toHaveCount(0)
  await drawer.getByRole('button', { name: /^确\s*定$/ }).click()
  await expect(drawer).toHaveCount(0)
  await expect(row.getByRole('textbox', { name: /字段名称$/ })).toHaveValue('公式体验字段')
  await expect(row.locator('.field-type-select')).toContainText('整数')
  checks.push('同一未保存新字段再次改类型并确认，直接写入页面本地草稿')

  await row.locator('.field-type-select').click()
  await page.locator('.ant-select-dropdown:visible .rc-virtual-list-holder').hover()
  await page.mouse.wheel(0, 2000)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="公式"]').click()
  await expect(drawer.getByRole('button', { name: '配置公式', exact: true })).toBeVisible()
  await expect(page.locator('.ant-modal-content:visible').filter({ hasText: '检查字段变更' })).toHaveCount(0)
  await drawer.getByRole('button', { name: /^取\s*消$/ }).click()
  await expect(row.locator('.field-type-select')).toContainText('整数')
  checks.push('重新编辑本地新字段不误触转换审核，取消后保留原类型')
  assert.deepEqual(requests, [], '本场景不得发起转换检查、草稿保存或发布请求')
  assert.deepEqual(errors, [])
  checks.push('无业务 POST 请求、无页面异常，未创建或更改服务端数据')
} catch (error) {
  failure = error
  errors.push(error.message)
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  ac.tokens = {}
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors, requests }, null, 2))
  console.log(JSON.stringify({ output, checks, errors }))
}
if (failure) process.exitCode = 1
