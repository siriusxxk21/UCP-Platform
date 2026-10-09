import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// 可重跑的只读浏览器验收。凭据从进程环境输入，不保存登录态、不修改示例授权。
const required = ['NOCODE_VERIFY_USER', 'NOCODE_VERIFY_PASSWORD', 'NOCODE_VERIFY_OBJECT', 'NOCODE_VERIFY_APP']
for (const name of required) if (!process.env[name]) throw new Error(`缺少环境变量 ${name}`)
const base = process.env.NOCODE_VERIFY_FRONT || 'http://127.0.0.1:5173'
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '../ucp-server/ucp-nocode/.work/sharing-ui')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'msedge' })
const page = await browser.newPage({ viewport: { width: 1600, height: 1050 } })
const errors = [],
  checks = []
page.on('pageerror', error => errors.push(error.message))
try {
  await page.goto(`${base}/login`)
  await page.getByPlaceholder('请输入用户名').fill(process.env.NOCODE_VERIFY_USER)
  await page.getByPlaceholder('请输入密码').fill(process.env.NOCODE_VERIFY_PASSWORD)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 30000 })
  await page.goto(`${base}/nocode/object/editor?id=${process.env.NOCODE_VERIFY_OBJECT}`)
  await page.getByRole('tab', { name: '应用共享授权', exact: true }).click({ timeout: 30000 })
  await expect(page.getByText('这里决定对象允许应用做什么。', { exact: false })).toBeVisible()
  await expect(page.getByRole('table').last().getByText('兼容迁移', { exact: false }).first()).toBeVisible()
  await page.getByRole('button', { name: '新增应用授权', exact: false }).click()
  await expect(page.getByText('请选择需要授权的应用', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存权限', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: /关\s*闭/ }).click()
  await page.getByRole('button', { name: '配置权限', exact: false }).first().click()
  await expect(page.locator('.ant-modal-title')).toContainText('对象权限')
  await expect(page.getByRole('combobox', { name: '授权应用', exact: false })).toBeEnabled()
  await expect(page.getByRole('button', { name: '保存权限', exact: true })).toBeEnabled()
  checks.push('数据对象展示新增应用授权、行内配置权限入口及已有授权范围')
  await page.screenshot({ path: resolve(output, 'object-sharing.png'), fullPage: true, animations: 'disabled' })
  await page.goto(`${base}/nocode-app/workspace?id=${process.env.NOCODE_VERIFY_APP}`)
  await expect(page.getByRole('tab', { name: '对象授权范围', exact: true })).toHaveCount(0)
  const row = page.locator(`.workspace tbody tr[data-row-key="${process.env.NOCODE_VERIFY_OBJECT}"]`)
  const table = page.locator('.workspace .nocode-embedded-table').first()
  await expect(table.locator('tbody tr[data-row-key]').first()).toBeVisible()
  while (!(await row.count())) {
    const next = table.locator('.ant-pagination-next:not(.ant-pagination-disabled)')
    if (!(await next.count())) throw new Error('指定应用未引用待验证对象')
    await next.click()
  }
  await row.getByRole('button', { name: '配置数据权限', exact: true }).click()
  const drawer = page.locator('.ant-drawer-content')
  await expect(drawer.getByText('允许操作', { exact: true })).toBeVisible()
  await expect(drawer.getByText('当前应用', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('combobox', { name: '授权应用', exact: false })).toHaveCount(0)
  checks.push('已引用对象行内直达当前应用授权，不必重复选择应用；本脚本只读查看，不保存修改')
  await page.screenshot({ path: resolve(output, 'application-sharing.png'), fullPage: true, animations: 'disabled' })
  await drawer.getByRole('button', { name: /关\s*闭/ }).click()
  await page.getByRole('tab', { name: '成员与权限', exact: true }).click()
  await expect(page.getByText('成员只能使用应用已获得的数据权限。', { exact: false })).toBeVisible()
  checks.push('成员配置显示共享上限约束说明')
  expect(errors).toEqual([])
  checks.push('验收过程无页面脚本异常')
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ capturedAt: new Date().toISOString(), checks, errors }, null, 2)
  )
  console.log(JSON.stringify({ passed: checks.length, errors }))
} catch (error) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true })
  console.error(error.message)
  process.exitCode = 1
} finally {
  await browser.close()
}
