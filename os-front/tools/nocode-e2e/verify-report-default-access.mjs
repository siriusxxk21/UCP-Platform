import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })
// 复用 HTTP 集成测试创建和精确清理的夹具，真实会话只在进程内传递。
const token = process.env.REPORT_TEST_TOKEN
const id = process.env.REPORT_TEST_DATASET
assert.ok(token && id)
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-default-access', id)
await mkdir(output, { recursive: true })
async function api(path) {
  const response = await fetch(base + path, { headers: { Authorization: `Bearer ${token}` } })
  const result = await response.json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
const info = await api('/system/auth/get-permission-info')
const original = await api('/nocode/report/dataset/get?id=' + id)
const browser = await chromium.launch({ headless: true, channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
const errors = [],
  checks = []
page.on('pageerror', error => errors.push(error.message))
try {
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
    },
    { token, info }
  )
  await page.goto(origin + '/nocode/report-center/dataset-editor?id=' + id)
  await expect(page.getByRole('textbox', { name: '数据集名称', exact: true })).toHaveValue(original.draft.name)
  await expect(page.getByRole('button', { name: '协作权限', exact: true })).toBeVisible()
  await expect(page.getByText('数据授权', { exact: true })).toHaveCount(0)
  await expect(page.getByText('对象授权上限', { exact: true })).toHaveCount(0)
  await expect(page.getByText('成员数据权限', { exact: true })).toHaveCount(0)
  checks.push('默认菜单和设计器移除对象及成员数据权限配置')
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.getByText('匹配 2 条记录，共 1 组，最多显示 100 组。')).toBeVisible()
  checks.push('没有对象上限或成员策略时真实预览成功，固定筛选仍生效')
  await page.getByRole('button', { name: /^发\s*布$/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '操作原因', exact: true }).fill('默认对象权限浏览器发布验证')
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByText('V1', { exact: true })).toBeVisible()
  checks.push('无需额外授权即可由真实发布弹窗发布 V1')
  await page.getByRole('button', { name: '协作权限', exact: true }).click()
  await expect(dialog.getByText('资源协作权限', { exact: true })).toBeVisible()
  await expect(dialog.getByText('添加协作成员', { exact: true })).toBeVisible()
  await expect(dialog.getByText('分配数据权限', { exact: true })).toHaveCount(0)
  await dialog.getByRole('button', { name: /取\s*消/ }).click()
  checks.push('资源协作权限继续可访问')
  await page.screenshot({ path: resolve(output, 'published.png'), fullPage: true })
  await page.goto(origin + '/nocode/report-center/data-authorization')
  await expect(page.getByText('数据集无需单独配置对象权限', { exact: true })).toBeVisible()
  checks.push('旧授权地址显示说明且不再提供授权表单')
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors }, null, 2))
  console.log(JSON.stringify({ checks, errors, output }))
} finally {
  await browser.close()
}
