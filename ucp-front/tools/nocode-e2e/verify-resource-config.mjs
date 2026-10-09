import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix += randomBytes(3).toString('hex')
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-resource-config', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
const resource = (id, kind, name, config) => ({ id, code: id, kind, name, config })
try {
  await ac.login()
  const object = await ac.object('config', '配置职责', [
    ac.field('name', 'TEXT', '名称'),
    ac.field('amount', 'INTEGER', '数量')
  ])
  const report = {
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
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_config',
    name: '资源配置验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource('view', 'VIEW', '配置列表', {
          objectId: object.objectId,
          fieldIds: [object.ids.name, object.ids.amount],
          equal: { [object.ids.name]: '第一组' },
          sortFieldId: object.ids.amount,
          descending: true,
          pageSize: 20,
          formId: null
        }),
        resource('report', 'REPORT', '配置报表', report),
        resource('page', 'PAGE', '统计页面', {
          protocolVersion: 2,
          contextObjectId: null,
          filters: [],
          nodes: [{ id: 'report_node', type: 'REPORT', resourceId: 'report', children: [] }]
        }),
        resource('menu', 'MENU', '统计页面', { targetId: 'page' })
      ]
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(object, ac.grant(object))
  const original = structuredClone(ac.app)
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
  const workspace = origin + '/nocode-app/workspace?id=' + ac.app.application.id
  await page.goto(workspace)
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  async function openResource(kind, code) {
    await page.locator('.resources .ant-segmented-item').filter({ hasText: kind }).click()
    const row = page
      .locator('.resources .ant-table-row')
      .filter({ has: page.getByRole('cell', { name: code, exact: true }) })
    await row.getByRole('button', { name: /配置$/ }).click()
    return page
      .locator('.ant-modal-content')
      .filter({ has: page.getByRole('textbox', { name: '资源编码', exact: true }) })
  }
  async function save() {
    const response = page.waitForResponse(
      r => r.url().endsWith('/nocode/application/save') && r.request().method() === 'POST'
    )
    await page.getByRole('button', { name: /保存草稿$/ }).click()
    assert.equal((await (await response).json()).code, 0)
    ac.app = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  }
  let dialog = await openResource('数据视图', 'view')
  await dialog.getByRole('textbox', { name: '资源名称', exact: true }).fill('保留的列表名称')
  await dialog.getByRole('textbox', { name: '资源编码', exact: true }).fill('Invalid')
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog.locator('.ant-alert-error')).toContainText('编码使用小写字母开头')
  await expect(dialog.getByRole('textbox', { name: '资源名称', exact: true })).toHaveValue('保留的列表名称')
  const unchanged = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  assert.deepEqual(unchanged.draft.resources, original.draft.resources)
  assert.equal(unchanged.application.revision, original.application.revision)
  checks.push('资源编码校验失败保留当前输入，真实服务端资源与修订号不变')

  await dialog.getByRole('textbox', { name: '资源编码', exact: true }).fill('view')
  await dialog.getByText('默认列宽', { exact: true }).click()
  await dialog.getByRole('spinbutton', { name: '数量默认列宽', exact: true }).fill('230')
  await dialog
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^每页数量$/ }) })
    .getByRole('spinbutton')
    .fill('17')
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog).toBeHidden()
  await save()
  const view = ac.app.draft.resources.find(r => r.id === 'view')
  assert.equal(view.name, '保留的列表名称')
  assert.equal(view.config.pageSize, 17)
  assert.equal(view.config.list.columnWidths[object.ids.amount], 230)
  assert.deepEqual(view.config.fieldIds, [object.ids.name, object.ids.amount])
  assert.equal(view.config.sortFieldId, object.ids.amount)
  assert.deepEqual(view.config.equal, {})
  assert.ok(
    view.config.query.fixed.some(c => c.fieldId === object.ids.name && c.operator === 'eq' && c.value === '第一组')
  )
  await page.reload()
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  dialog = await openResource('数据视图', 'view')
  await expect(dialog.getByRole('textbox', { name: '资源名称', exact: true })).toHaveValue('保留的列表名称')
  await dialog.getByText('默认列宽', { exact: true }).click()
  await expect(dialog.getByRole('spinbutton', { name: '数量默认列宽', exact: true })).toHaveValue('230')
  await dialog.locator('.ant-modal-close').click()
  await expect(dialog).toBeHidden()
  checks.push('列表配置经真实应用/保存/重开保留字段顺序、排序、列宽及分页，旧标量固定范围迁移保持原值')

  dialog = await openResource('统计视图', 'report')
  await dialog.getByRole('textbox', { name: '指标名称', exact: true }).fill('')
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog.locator('.ant-alert-error')).toContainText('请补齐分组和指标')
  await dialog.getByRole('textbox', { name: '指标名称', exact: true }).fill('筛选记录数')
  await dialog.getByRole('button', { name: '添加固定筛选', exact: true }).click()
  const filter = dialog.locator('.filter').last()
  await filter.locator('.ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByText('名称', { exact: true }).click()
  await filter.locator('input.ant-input').fill('第一组')
  await page.screenshot({ path: resolve(output, '01-report-config.png'), fullPage: true, animations: 'disabled' })
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog).toBeHidden()
  await save()
  const configured = ac.app.draft.resources.find(r => r.id === 'report').config
  assert.equal(configured.metrics[0].id, 'count')
  assert.equal(configured.metrics[0].name, '筛选记录数')
  assert.equal(configured.metrics[0].operation, 'COUNT')
  assert.deepEqual(configured.equal, { [object.ids.name]: '第一组' })
  assert.equal(configured.dimensions[0].fieldId, object.ids.name)
  checks.push('报表缺指标名称时拒绝应用；补齐后真实保存保留指标ID、分组及固定筛选')

  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '资源配置职责拆分验收'
  })
  await ac.save(object, { name: '第一组', amount: '3' })
  await ac.save(object, { name: '第二组', amount: '9' })
  const response = page.waitForResponse(r => r.url().endsWith('/nocode/runtime/report'))
  await page.goto(origin + '/nocode-app/runtime?id=' + ac.app.application.id)
  const result = await (await response).json()
  assert.equal(result.code, 0)
  assert.equal(result.data.groups.length, 1)
  assert.equal(Number(result.data.totals.count), 1)
  const block = page.locator('.report-block')
  await expect(block.locator('.report-chart canvas')).toBeVisible()
  await block.getByRole('button', { name: /表格$/ }).click()
  await expect(block.getByRole('cell', { name: '第一组', exact: true })).toBeVisible()
  await expect(block.getByRole('cell', { name: '第二组', exact: true })).toHaveCount(0)
  await page.screenshot({ path: resolve(output, '02-published-report.png'), fullPage: true, animations: 'disabled' })
  checks.push('真实发布后固定筛选作用于统计结果，Chrome图表和表格仅显示目标组')
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
