import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 在当前开发环境创建有独立前缀的验收对象，不读取或修改已有业务记录。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/object-rules-pivot', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = []
const errors = []
let browser, page, failure
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
try {
  await ac.login()
  const object = await ac.object(
    'rulepivot',
    '对象规则与透视合并验收',
    [
      ac.field('name', 'TEXT', '名称'),
      ac.field('area', 'TEXT', '区域'),
      ac.field('department', 'TEXT', '部门'),
      ac.field('month', 'TEXT', '月份'),
      ac.field('amount', 'DECIMAL', '金额'),
      ac.field('computed', 'DECIMAL', '规则计算值')
    ],
    { computed: ac.option({ rules: { defaultFormula: '42' } }) }
  )
  const dimension = code => ({ fieldId: object.ids[code], relationPath: null, bucket: 'VALUE' })
  const config = {
    objectId: object.objectId,
    dimensions: [dimension('area'), dimension('department')],
    columnDimensions: [dimension('month')],
    metrics: [{ id: 'amount', name: '金额合计', operation: 'SUM', fieldId: object.ids.amount }],
    equal: {},
    filterFieldIds: [],
    dateFieldId: null,
    timeZone: 'Asia/Shanghai',
    display: 'PIVOT',
    sortMetricId: null,
    descending: false,
    limit: 20,
    detailViewId: null,
    detailEditable: false,
    pivot: { subtotals: false, rowTotals: true, columnTotals: true, percent: 'NONE', maxColumnGroups: 24 }
  }
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_pivot',
    name: '对象规则与透视验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource('report', 'REPORT', '交叉金额统计', config),
        resource('report_page', 'PAGE', '交叉统计', {
          protocolVersion: 2,
          contextObjectId: null,
          filters: [],
          nodes: [{ id: 'pivot', type: 'REPORT', resourceId: 'report', children: [] }]
        }),
        resource('report_menu', 'MENU', '交叉统计', { targetId: 'report_page' })
      ]
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(object, ac.grant(object))
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '外部开发功能合并真实验收'
  })
  let first
  for (const [name, department, month, amount] of [
    ['第一条', '销售一部', '一月', '10'],
    ['第二条', '销售一部', '二月', '20'],
    ['第三条', '销售二部', '一月', '5']
  ]) {
    const row = await ac.save(object, { name, area: '华东', department, month, amount, computed: '999' })
    assert.equal(Number(row.values[object.ids.computed]), 42, '客户端伪造公式字段值必须由服务端覆盖')
    first ||= row
  }
  const updated = await ac.save(object, { computed: '1000' }, first)
  assert.equal(Number(updated.values[object.ids.computed]), 42)
  checks.push('对象公式规则经设计、发布、应用引用后生效；新增与修改伪造值均不能覆盖只读计算结果')
  const query = { applicationId: ac.app.application.id, reportId: 'report', equal: {} }
  const result = await ac.api('/nocode/runtime/report', query)
  assert.equal(Number(result.totals.amount), 35)
  assert.equal(result.pivot.rows.length, 2)
  assert.equal(result.pivot.columns.length, 2)
  assert.equal(result.pivot.cells.filter(cell => cell.rowKeys.length === 1).length, 0)
  const leaf = result.pivot.cells.find(cell => cell.rowKeys.length === 2 && cell.columnKeys.length === 1)
  assert.ok(leaf)
  const details = await ac.api('/nocode/runtime/report-details', {
    ...query,
    group: leaf.rowKeys,
    columnGroup: leaf.columnKeys,
    metricId: 'amount',
    pageNo: 1,
    pageSize: 20
  })
  assert.equal(details.total, 1)
  assert.equal(details.list.length, 1)
  assert.deepEqual(
    ['area', 'department', 'month'].map(code => String(details.list[0].values[object.ids[code]])),
    [...leaf.rowKeys, ...leaf.columnKeys]
  )
  checks.push('真实透视查询交叉行列、合计金额正确；单元格下钻接口成功')
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(25000)
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      for (const [key, value] of Object.entries({
        userInfo: info.user,
        permissions: info.permissions,
        roles: info.roles,
        menus: info.menus
      })) {
        localStorage.setItem(key, JSON.stringify(value))
      }
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  await page.goto(`${origin}/nocode-app/runtime?id=${ac.app.application.id}`)
  const block = page.locator('.report-block')
  await expect(block.locator('.report-pivot')).toBeVisible()
  await expect(block.getByText('销售一部', { exact: true })).toBeVisible()
  await expect(block.getByText('销售二部', { exact: true })).toBeVisible()
  await expect(block.getByRole('button', { name: '全部折叠', exact: true })).toHaveCount(0)
  await expect(block.locator('.pivot-table tbody tr[data-row-level="leaf"]')).toHaveCount(2)
  const cells = block.locator('tbody td[data-column-level="leaf"] .pivot-value')
  const amounts = (await cells.allTextContents()).map(text => Number(text.replaceAll(',', '').trim()))
  assert.deepEqual(
    amounts.sort((a, b) => a - b),
    [5, 10, 20]
  )
  assert.equal(Number(await block.locator('tfoot td[data-column-level="total"] .pivot-value').textContent()), 35)
  await page.screenshot({ path: resolve(output, 'pivot.png'), fullPage: true, animations: 'disabled' })
  const detailResponse = page.waitForResponse(response => response.url().includes('/nocode/runtime/report-details'))
  await cells.first().click()
  const clickedDetails = (await (await detailResponse).json()).data
  assert.equal(clickedDetails.total, 1)
  const drawer = page.locator('.ant-drawer-content')
  await expect(drawer).toBeVisible()
  await expect(drawer.getByText('共 1 条', { exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(output, 'drill-details.png'), fullPage: true, animations: 'disabled' })
  assert.deepEqual(errors, [])
  checks.push('Chrome真实渲染两层行维度及金额，点击单元格打开一条匹配明细；关闭小计时无折叠入口，无页面脚本异常')
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
