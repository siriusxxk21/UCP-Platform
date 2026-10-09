import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { response, today } from './fixture.mjs'

// 使用正式组件及同一 HTTP 契约；夹具不写业务库，重复运行不覆盖既有验收截图。
const out = resolve(process.env.HISTORY_OUTPUT || '../ucp-server/ucp-nocode/.work/history-daily-review')
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.HISTORY_BROWSER || 'chrome' })
const page = await browser.newPage({ viewport: { width: 1600, height: 1040 } })
page.setDefaultTimeout(15000)
const errors = [],
  checks = [],
  requests = []
page.on('pageerror', e => errors.push(e.message))
let fail = false,
  failDetail = false,
  delayNextDetail = false
let releaseDetail
page.on('console', message => {
  if (message.type() === 'error' && !message.text().includes('status of 500')) errors.push(message.text())
})
await page.route('**/fixture/nocode/record-history/*', async route => {
  const q = route.request().postDataJSON()
  const endpoint = '/' + new URL(route.request().url()).pathname.split('/').at(-1)
  requests.push({ endpoint, ...q })
  if (endpoint === '/detail' && delayNextDetail) {
    delayNextDetail = false
    await new Promise(resolve => {
      releaseDetail = resolve
    })
  }
  return route.fulfill(
    fail || (failDetail && endpoint === '/detail') ? { status: 500, body: 'failure' } : { json: response(endpoint, q) }
  )
})
const metrics = page.locator('.history-metrics')
const openCompany = () => page.getByRole('button', { name: '公司档案 · 查看变化', exact: true }).click()
const openRow = () => page.getByRole('button', { name: '记录 r1 · 查看变化详情', exact: true }).click()
const closeDrawer = () => page.locator('.ant-drawer-close').click()
const back = () => page.getByRole('button', { name: /返回(业务|人员)动态/ }).click()
const shot = name =>
  page.screenshot({ path: resolve(out, name + '.png'), fullPage: !name.includes('移动端详情'), animations: 'disabled' })
try {
  await page.goto(process.env.HISTORY_URL || 'http://127.0.0.1:5199', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('radio', { name: '业务动态' })).toBeChecked()
  await expect(page.getByRole('button', { name: '公司档案 · 查看变化', exact: true })).toHaveCount(1)
  await expect(metrics).toContainText(/涉及记录\s*3\s*条/)
  await expect(metrics).toContainText('修改次数2')
  await expect(page.getByText('共享表 · 计一次', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '后一天' })).toBeDisabled()
  await shot('01-业务动态-v4')
  checks.push('默认业务优先、共享表只展示一次、去重计数、今日不进入未来')

  await page.getByRole('button', { name: /删除次数/ }).click()
  await expect(page.getByRole('button', { name: '资产台账 · 查看变化', exact: true })).toHaveCount(0)
  await expect(page.getByText('当前只列出发生过删除的表格；进入后仍可查看该表全部变化。')).toBeVisible()
  await page.getByRole('button', { name: /删除次数/ }).click()
  await page.getByRole('textbox', { name: '搜索表格或业务' }).fill('公司')
  const calls = requests.filter(r => r.endpoint === '/query').length
  await page.locator('.history-view-toolbar').getByText('人员动态', { exact: true }).click()
  await expect(page.getByRole('textbox', { name: '搜索表格或业务' })).toHaveValue('公司')
  expect(requests.filter(r => r.endpoint === '/query')).toHaveLength(calls)
  await shot('02-人员动态-v4')
  await page.getByRole('button', { name: '张三 · 公司档案 · 查看变化', exact: true }).click()
  await expect(page.locator('.history-table-heading')).toContainText('张三涉及')
  await openRow()
  await expect(page.locator('.history-drawer')).toContainText('最终值已恢复')
  await expect(page.getByRole('tab', { name: '最终变化', exact: true })).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('tab', { name: '修改过程 2', exact: true }).click()
  await expect(page.locator('.history-event')).toHaveCount(2)
  await expect(page.locator('.history-selected-person')).toContainText('张三')
  await expect(page.locator('.history-event')).toContainText(['张三', '李四'])
  await shot('03-人员修改过程-v4')
  await closeDrawer()
  await back()
  await expect(page.getByRole('radio', { name: '人员动态' })).toBeChecked()
  await expect(page.getByRole('textbox', { name: '搜索表格或业务' })).toHaveValue('公司')
  checks.push('切换视角不重查、筛选保留、员工下钻、全员过程和返回上下文')

  await page.getByRole('button', { name: '清除筛选', exact: true }).click()
  await page.locator('.history-view-toolbar').getByText('业务动态', { exact: true }).click()
  await openCompany()
  await expect(page.getByText('新增后删除', { exact: true })).toBeVisible()
  await openRow()
  await shot('04-最终变化-v4')
  await closeDrawer()
  await page.getByRole('tab', { name: '截至当时的全表', exact: true }).click()
  await expect(page.getByText('共 13 条', { exact: true })).toBeVisible()
  await page.getByTitle('2', { exact: true }).click()
  await expect(page.getByText('公司档案 12', { exact: true })).toBeVisible()
  await expect(page.locator('.history-table-section')).not.toContainText('临时公司')
  await back()
  checks.push('新增后删除、最终恢复原值、历史全表分页且排除已删除记录')

  await page.getByRole('checkbox', { name: '仅看有变化的表格' }).uncheck()
  await expect(page.getByText('共 14 张表格', { exact: true })).toBeVisible()
  await page.getByTitle('2', { exact: true }).click()
  const lastButton = page.getByRole('button', { name: '基础资料 12 · 查看变化', exact: true })
  await expect(lastButton).toBeVisible()
  await lastButton.click()
  await back()
  await expect(lastButton).toBeVisible()
  await page.getByRole('checkbox', { name: '仅看有变化的表格' }).check()
  checks.push('无变化表可显式查看、摘要分页和返回位置保留')

  await page.getByRole('button', { name: '前一天', exact: true }).click()
  await expect(page.getByText('当前条件下没有留存变化', { exact: true })).toBeVisible()
  expect(Date.parse(requests.filter(r => r.endpoint === '/query').at(-1).end)).toBeLessThan(today.getTime())
  await page.getByRole('button', { name: '后一天', exact: true }).click()
  await expect(metrics).toContainText(/涉及记录\s*3\s*条/)
  await page.getByRole('button', { name: '近7天', exact: true }).click()
  const q = requests.filter(r => r.endpoint === '/query').at(-1)
  expect(Date.parse(q.start)).toBeLessThan(today.getTime() - 5 * 86400000)
  await page.getByRole('button', { name: /今\s*日/ }).click()
  checks.push('前后一天、昨日空态、近7天使用真实时间参数')

  fail = true
  await page.getByRole('button', { name: /刷\s*新/ }).click()
  await expect(page.getByText('加载失败，请重试', { exact: true })).toBeVisible()
  await expect(metrics).toHaveCount(0)
  fail = false
  await page.getByRole('button', { name: /重\s*试/ }).click()
  await expect(metrics).toBeVisible()
  await openCompany()
  failDetail = true
  await openRow()
  await expect(page.locator('.history-drawer')).toContainText('加载失败，请重试')
  failDetail = false
  await page
    .locator('.history-drawer')
    .getByRole('button', { name: /重\s*试/ })
    .click()
  await expect(page.locator('.history-drawer')).toContainText('修改后恢复了原值')
  await closeDrawer()
  checks.push('摘要失败清空旧结果、详情失败重试')

  delayNextDetail = true
  await openRow()
  await expect(page.getByText('正在加载修改过程')).toBeVisible()
  await closeDrawer()
  releaseDetail()
  await expect(page.locator('.ant-drawer-open')).toHaveCount(0)
  await back()
  await expect(metrics).toContainText(/涉及记录\s*3\s*条/)
  checks.push('关闭抽屉后迟到响应不重新打开或污染详情')

  await page.setViewportSize({ width: 390, height: 844 })
  await shot('05-移动端业务-v4')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await openCompany()
  await openRow()
  await expect(page.locator('.ant-drawer-content-wrapper')).toHaveCSS('width', '390px')
  await expect.poll(async () => Math.round((await page.locator('.ant-drawer-content-wrapper').boundingBox()).x)).toBe(0)
  await shot('06-移动端详情-v4')
  const drawer = await page.locator('.ant-drawer-content-wrapper').boundingBox()
  expect(drawer.y).toBe(0)
  expect(drawer.x).toBeGreaterThanOrEqual(0)
  expect(drawer.x + drawer.width).toBeLessThanOrEqual(390)
  checks.push('390px 页面不溢出、详情抽屉和对比内容可读')
  expect(errors).toEqual([])
  await writeFile(
    resolve(out, 'browser-result.json'),
    JSON.stringify(
      {
        checks,
        errors,
        requests: requests.map(r => ({ endpoint: r.endpoint, query: r.query, start: r.start, end: r.end }))
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ checks, errors, output: out }))
} catch (error) {
  await shot('failure')
  await writeFile(
    resolve(out, 'failure.json'),
    JSON.stringify({ message: String(error), checks, errors, requests }, null, 2)
  )
  throw error
} finally {
  releaseDetail?.()
  await browser.close()
}
