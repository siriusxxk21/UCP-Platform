import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-report', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  assets = []
let browser, page, failure
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
try {
  await ac.login()
  const object = await ac.object('report', '报表加载', [ac.field('name', 'TEXT', '名称')])
  const config = {
    objectId: object.objectId,
    dimensions: [{ fieldId: object.ids.name, relationPath: null, bucket: 'VALUE' }],
    metrics: [{ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Shanghai',
    display: 'BAR',
    sortMetricId: null,
    descending: false,
    limit: 20,
    detailViewId: null,
    chart: { barMode: 'GROUPED', horizontal: false, labels: true, legendPosition: 'TOP' }
  }
  const pageConfig = nodes => ({ protocolVersion: 2, contextObjectId: null, filters: [], nodes })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_report',
    name: '报表加载验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource(
          'welcome',
          'PAGE',
          '验收说明',
          pageConfig([{ id: 'intro', type: 'TEXT', text: '报表加载前的普通页面', children: [] }])
        ),
        resource('welcome_menu', 'MENU', '验收说明', { targetId: 'welcome' }),
        resource('report', 'REPORT', '数量统计', config),
        resource(
          'report_page',
          'PAGE',
          '统计图表',
          pageConfig([{ id: 'chart', type: 'REPORT', resourceId: 'report', children: [] }])
        ),
        resource('report_menu', 'MENU', '统计图表', { targetId: 'report_page' })
      ]
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(object, ac.grant(object))
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '报表加载真实回归夹具'
  })
  await ac.save(object, { name: '第一组' })
  await ac.save(object, { name: '第二组' })
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    const url = new URL(request.url())
    if (url.pathname.endsWith('.js')) assets.push(url.pathname)
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
  await page.goto(`${origin}/nocode-app/runtime?id=${ac.app.application.id}`)
  await expect(page.getByText('报表加载前的普通页面', { exact: true })).toBeVisible()
  const beforeReport = [...new Set(assets)]
  const simulateChunkFailure = process.env.NOCODE_VERIFY_CHART_FAILURE === '1'
  if (simulateChunkFailure)
    await page.route(/\/assets\/ReportChart-[^/]+\.js$/, route => route.abort('failed'), { times: 1 })
  const response = page.waitForResponse(response => response.url().endsWith('/nocode/runtime/report'))
  await page.getByRole('button', { name: '统计图表', exact: true }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0)
  assert.equal(result.data.groups.length, 2)
  assert.equal(Number(result.data.totals.count), 2)
  const block = page.locator('.report-block')
  if (simulateChunkFailure) {
    await expect(block.getByText('图表资源加载失败，请先保存未保存的内容，再刷新页面。', { exact: true })).toBeVisible()
    const defer = page.getByRole('button', { name: '稍后处理', exact: true })
    await expect(defer).toBeVisible()
    await defer.click()
    await block.getByRole('button', { name: /表格$/ }).click()
    await expect(block.getByRole('cell', { name: '第一组', exact: true })).toBeVisible()
    await expect(block.getByRole('cell', { name: '第二组', exact: true })).toBeVisible()
    await page.reload()
    await expect(block.locator('.report-chart canvas')).toBeVisible({ timeout: 20000 })
    checks.push('生产图表分块首次真实网络失败后，保留统计数据和表格切换；用户主动刷新后恢复图表')
  }
  await expect(block.locator('.report-chart canvas')).toBeVisible()
  await page.screenshot({ path: resolve(output, '01-chart.png'), fullPage: true, animations: 'disabled' })
  checks.push('真实发布应用与两条业务记录：报表 API 分组和合计正确，Chrome 实际渲染图表画布')
  const afterReport = [...new Set(assets)]
  await block.getByRole('button', { name: /表格$/ }).click()
  await expect(block.getByRole('cell', { name: '第一组', exact: true })).toBeVisible()
  await expect(block.getByRole('cell', { name: '第二组', exact: true })).toBeVisible()
  await block.getByText('查看明细', { exact: true }).first().click()
  const dialog = page.locator('.ant-drawer-content')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1)
  await dialog.locator('.ant-drawer-close').click()
  await block.getByRole('button', { name: /图表$/ }).click()
  await expect(block.locator('.report-chart canvas')).toBeVisible()
  checks.push('图表与表格双向切换，分组钻取真实明细后可返回图表')
  await block.getByRole('button', { name: /刷新$/ }).click()
  await expect(block.locator('.report-chart canvas')).toBeVisible()
  await page.reload()
  await expect(block.locator('.report-chart canvas')).toBeVisible()
  assert.deepEqual(errors, [])
  checks.push('刷新统计与浏览器重载后图表恢复，无页面脚本异常')
  await writeFile(
    resolve(output, 'assets.json'),
    JSON.stringify(
      { beforeReport, afterReport, added: afterReport.filter(asset => !beforeReport.includes(asset)) },
      null,
      2
    )
  )
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
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
