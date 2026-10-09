import { verifyDashboard } from './verify-report-dashboard.mjs'
import { verifyOptions } from './verify-report-options.mjs'
import { verifyMetrics } from './verify-report-metrics.mjs'
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })
const token = process.env.REPORT_TEST_TOKEN,
  id = process.env.REPORT_TEST_DATASET
const objects = JSON.parse(process.env.REPORT_TEST_SOURCES || '[]')
assert.ok(token && id && objects.length === 3)
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-sources', id)
await mkdir(output, { recursive: true })
async function api(path, body) {
  const response = await fetch(base + path, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  })
  const result = await response.json()
  assert.equal(result.code, 0, result.msg)
  return result.data
}
const names = objects.map(o => o.definition.objectName)
const info = await api('/system/auth/get-permission-info')
const browser = await chromium.launch({
  headless: true,
  channel: 'chrome',
  args: [
    '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows'
  ]
})
const page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
page.setDefaultTimeout(20000)
const checks = [],
  errors = []
page.on('pageerror', e => errors.push(e.message))
async function select(label, title) {
  await page.locator(`[aria-label="${label}"] .ant-select-selector`).click()
  await page.locator('.ant-select-dropdown:visible').getByTitle(title, { exact: true }).last().click()
  await page.getByRole('textbox', { name: '数据集说明', exact: true }).click()
}
async function preview() {
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/report/dataset/query') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /查询预览$/ }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  await expect(
    page.getByText(`匹配 ${result.data.recordCount} 条记录，共 ${result.data.totalGroups} 组，最多显示 100 组。`)
  ).toBeVisible()
  return result.data
}
async function save() {
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/report/dataset/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await response).json()).code, 0)
}
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
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
      document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style')
        style.textContent = '*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important}'
        document.head.appendChild(style)
      })
    },
    { token, info }
  )
  await page.goto(origin + '/nocode/report-center/dataset-editor?id=' + id)
  await page.locator('#report-source-object').fill(names[0])
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: names[0] }).click()
  for (const name of ['金额', '发生时间', '业务日期', '状态', '已核销'])
    await page.getByRole('checkbox', { name: '选用' + name, exact: true }).check()
  await select(names[0] + '添加关联', names[1])
  await page.getByRole('button', { name: new RegExp('关联：' + names[1]) }).click()
  await page.getByRole('checkbox', { name: '选用' + names[2], exact: true }).check()
  await page.getByRole('textbox', { name: names[2] + '分析名称', exact: true }).fill('公司原键')
  await select(names[1] + '添加关联', names[2])
  const companyPanel = page
    .locator('.ant-collapse-item')
    .filter({ has: page.getByRole('button', { name: new RegExp('关联：' + names[2]) }) })
  await companyPanel.getByRole('button', { name: new RegExp('关联：' + names[2]) }).click()
  await companyPanel.getByRole('checkbox', { name: '选用名称', exact: true }).check()
  await companyPanel.getByRole('textbox', { name: '名称分析名称', exact: true }).fill('公司')
  await expect(page.locator(`[aria-label="${names[2]}添加关联"]`)).toHaveCount(0)
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await page.getByRole('button', { name: '添加指标', exact: true }).click()
  await page.getByRole('textbox', { name: '指标名称', exact: true }).fill('金额合计')
  await select('聚合方式', '求和')
  await select('统计字段', '金额')
  await save()
  await page.reload()
  await expect(page.getByRole('checkbox', { name: '选用发生时间', exact: true })).toBeChecked()
  const detail = await api('/nocode/report/dataset/get?id=' + id)
  const source = detail.draft.source
  assert.equal(source.relations.length, 2)
  assert.equal(source.fields.find(f => f.name === '公司').path.length, 2)
  checks.push('真实页面建立流水→账户→公司两层关联，选择金额/日期/关联字段及 SUM 指标，保存重开保留固定来源版本')
  const grants = objects.map(o => ({
    objectId: o.objectId,
    actions: ['READ'],
    scope: 'ALL',
    readFields: o.definition.fields.map(f => f.id),
    writeFields: [],
    readDetails: [],
    writeDetails: []
  }))
  for (const grant of grants)
    await api('/nocode/report/dataset/ceiling', {
      datasetId: id,
      objectId: grant.objectId,
      expectedRevision: 0,
      permission: grant,
      reason: '两层日期上限'
    })
  let policy = await api('/nocode/report/dataset/data-policy', {
    datasetId: id,
    expectedRevision: 0,
    members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: grants }],
    reason: '两层日期成员'
  })
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await select('分组维度', '发生时间')
  await select('发生时间分组粒度', '按月')
  let result = await preview()
  const metric = result.metrics[0].id
  assert.deepEqual(Object.fromEntries(result.groups.map(g => [g.keys[0], Number(g.values[metric])])), {
    '2026-01': 10.25,
    '2026-02': 29.75
  })
  assert.equal(Number(result.totals[metric]), 40)
  await select('发生时间分组粒度', '按日')
  await expect(page.getByText('匹配 4 条记录，共 2 组，最多显示 100 组。')).toHaveCount(0)
  result = await preview()
  assert.deepEqual(Object.fromEntries(result.groups.map(g => [g.keys[0], Number(g.values[metric])])), {
    '2026-01-31': 10.25,
    '2026-02-01': 25.75,
    '2026-02-02': 4
  })
  await select('发生时间分组粒度', '按年')
  result = await preview()
  assert.equal(result.groups[0].keys[0], '2026')
  assert.equal(Number(result.totals[metric]), 40)
  checks.push('日期时间按月/日/年粒度的真实预览分别核对跨月金额，改变粒度清除旧结果，总额精确为 40')
  await select('分组维度', '公司')
  await select('发生时间分组粒度', '按月')
  const restricted = structuredClone(grants)
  const company = objects[2]
  restricted[2].actionScopes = {
    READ: {
      logic: 'AND',
      conditions: [
        { fieldId: company.definition.fields.find(f => f.code === 'name').id, operator: 'eq', value: '可见公司' }
      ],
      groups: []
    }
  }
  policy = await api('/nocode/report/dataset/data-policy', {
    datasetId: id,
    expectedRevision: policy.revision,
    members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: restricted }],
    reason: '隐藏第二层公司'
  })
  result = await preview()
  assert.equal(result.recordCount, 3)
  assert.equal(Number(result.totals[metric]), 19.25)
  assert.ok(!JSON.stringify(result).includes('隐藏公司'))
  const nullGroups = result.groups.filter(g => g.keys[1] === null)
  assert.equal(nullGroups.length, 1)
  assert.equal(Number(nullGroups[0].values[metric]), 9)
  await page.screenshot({ path: resolve(output, 'date-preview.png'), fullPage: true })
  checks.push('第二层公司被行权限隐藏时对应流水不混入空公司组；真正空账户/空公司仍保留，三条合计 19.25、空组合计 9')
  await verifyOptions({ page, api, id, source, preview, select, output, checks })
  // 临时条件通过页面输入；固定条件和权限交集另用真实 HTTP 验证失败路径。
  await page.getByRole('switch', { name: '临时筛选', exact: true }).click()
  const filterPanel = page.locator('.preview-filters')
  await filterPanel.getByRole('button', { name: '添加条件', exact: true }).click()
  await select('范围字段', '业务日期')
  await select('范围匹配方式', '大于等于')
  await filterPanel.getByRole('textbox', { name: '范围值', exact: true }).fill('2026-02-01')
  result = await preview()
  assert.equal(result.recordCount, 2)
  assert.equal(Number(result.totals[metric]), 9)
  await filterPanel.getByRole('textbox', { name: '范围值', exact: true }).fill('2026-02-02')
  await expect(page.getByText('匹配 2 条记录，共 1 组，最多显示 100 组。')).toHaveCount(0)
  result = await preview()
  assert.equal(Number(result.totals[metric]), 4)
  await select('范围字段', '金额')
  await select('范围匹配方式', '属于任意一个')
  const multiInput = filterPanel.locator('[aria-label="范围值"] input')
  for (const value of ['4', '10.25', '20.75']) {
    await multiInput.fill(value)
    await multiInput.press('Enter')
  }
  await page.getByRole('textbox', { name: '数据集说明', exact: true }).click()
  result = await preview()
  assert.equal(result.recordCount, 2)
  assert.equal(Number(result.totals[metric]), 14.25)
  await page.screenshot({ path: resolve(output, 'filters-preview.png'), fullPage: true })
  await page.getByRole('switch', { name: '临时筛选', exact: true }).click()
  result = await preview()
  assert.equal(Number(result.totals[metric]), 19.25)
  await expect(page.getByRole('button', { name: /保存草稿$/ })).toBeDisabled()
  checks.push(
    '页面日期范围与金额多选实时预览；隐藏公司的 20.75 始终不返回，改变条件清除旧结果，重置不扩大权限且不修改草稿'
  )
  const fieldId = name => source.fields.find(f => f.name === name).id
  const scope = (conditions, logic = 'AND', groups = []) => ({ logic, conditions, groups })
  const condition = (name, operator, value) => ({ fieldId: fieldId(name), operator, value })
  const previewQuery = { datasetId: id, preview: true, dimensions: [], limit: 1 }
  const queryWith = filters => api('/nocode/report/dataset/query', { ...previewQuery, filters })
  result = await queryWith(
    scope([
      condition('金额', 'gte', '5'),
      condition('金额', 'lt', '20'),
      condition('发生时间', 'gte', '2026-01-31 23:59:59'),
      condition('发生时间', 'lt', '2026-02-02 00:00:00')
    ])
  )
  assert.equal(Number(result.totals[metric]), 15.25)
  result = await queryWith(
    scope([], 'OR', [scope([condition('公司', 'eq', '隐藏公司')]), scope([condition('公司', 'isNull', null)])])
  )
  assert.equal(Number(result.totals[metric]), 9)
  result = await queryWith(scope([condition('金额', 'in', [])]))
  assert.equal(result.recordCount, 0)
  for (const filters of [
    scope([{ fieldId: 'unknown', operator: 'eq', value: 1 }]),
    scope([condition('公司', 'gt', 'x')]),
    scope([condition('金额', 'in', Array(101).fill('1'))]),
    scope([condition('业务日期', 'gte', '2026-02-30')]),
    scope([{ ...condition('金额', 'eq', null), valueSource: 'CURRENT_USER' }]),
    scope(Array(51).fill(condition('金额', 'eq', '1'))),
    scope([])
  ]) {
    const response = await fetch(base + '/nocode/report/dataset/query', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...previewQuery, filters })
    })
    const rejected = await response.json()
    assert.notEqual(rejected.code, 0)
    assert.match(rejected.msg, /字段|范围|日期|条件|身份|格式/)
  }
  checks.push(
    '真实查询覆盖金额开闭范围、日期时间边界、跨关联 OR/空值、空多选；非法字段/操作符/日期/动态身份和超限条件均拒绝'
  )
  await page.getByRole('button', { name: /^发\s*布$/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '操作原因', exact: true }).fill('两层日期验证发布')
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  const version = (await api('/nocode/report/dataset/releases?id=' + id)).list[0]
  const query = {
    datasetId: id,
    versionNo: version.versionNo,
    checksum: version.checksum,
    preview: false,
    dimensions: [{ fieldId: source.fields.find(f => f.name === '业务日期').id, bucket: 'MONTH' }],
    limit: 20
  }
  const dateResult = await api('/nocode/report/dataset/query', query)
  assert.deepEqual(Object.fromEntries(dateResult.groups.map(g => [g.keys[0], Number(g.values[metric])])), {
    '2026-01': 10.25,
    '2026-02': 9
  })
  const tampered = await fetch(base + '/nocode/report/dataset/query', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...query, timeZone: 'UTC' })
  })
  assert.notEqual((await tampered.json()).code, 0)
  let saved = await api('/nocode/report/dataset/get?id=' + id)
  saved = await api('/nocode/report/dataset/save', {
    ...saved.draft,
    id,
    expectedRevision: saved.revision,
    analysis: { ...saved.draft.analysis, fixedConditions: scope([condition('金额', 'gte', '5')]) }
  })
  const broad = scope([condition('金额', 'lt', '5'), condition('金额', 'gte', '5')], 'OR')
  assert.equal(Number((await queryWith(broad)).totals[metric]), 15.25)
  assert.equal(Number((await api('/nocode/report/dataset/query', { ...query, filters: broad })).totals[metric]), 19.25)
  const noAmount = structuredClone(restricted)
  noAmount[0].readFields = noAmount[0].readFields.filter(
    f => f !== objects[0].definition.fields.find(f => f.code === 'amount').id
  )
  policy = await api('/nocode/report/dataset/data-policy', {
    datasetId: id,
    expectedRevision: policy.revision,
    members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: noAmount }],
    reason: '筛选字段撤权'
  })
  const denied = await fetch(base + '/nocode/report/dataset/query', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...query,
      dimensions: [],
      metricIds: null,
      metrics: [{ id: 'only_count', name: '计数', operation: 'COUNT', fieldId: null }],
      filters: scope([condition('金额', 'gte', '1')])
    })
  })
  const deniedBody = await denied.json()
  assert.notEqual(deniedBody.code, 0)
  assert.match(deniedBody.msg, /权限|授权/)
  await assert.rejects(
    () =>
      api('/nocode/report/dataset/options', {
        datasetId: id,
        preview: true,
        fieldId: fieldId('金额'),
        pageNo: 1,
        pageSize: 20
      }),
    /权限|授权/
  )
  policy = await api('/nocode/report/dataset/data-policy', {
    datasetId: id,
    expectedRevision: policy.revision,
    members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: restricted }],
    reason: '恢复本次筛选夹具'
  })
  await api('/nocode/report/dataset/save', {
    ...saved.draft,
    id,
    expectedRevision: saved.revision,
    analysis: { ...saved.draft.analysis, fixedConditions: null }
  })
  await page.reload()
  checks.push('临时 OR 不能覆盖固定条件；旧发布版本仍保持原口径；仅用于筛选的金额字段被撤权后 COUNT 查询也拒绝')
  await verifyMetrics({ page, api, id, preview, save, output, checks, version })
  const dashboardId = await verifyDashboard({ page, api, id, output, checks })
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await select('统计时区', 'UTC')
  await save()
  const utc = await api('/nocode/report/dataset/query', {
    datasetId: id,
    preview: true,
    dimensions: query.dimensions,
    limit: 20,
    timeZone: 'UTC'
  })
  assert.deepEqual(utc.groups, dateResult.groups)
  assert.equal(utc.timeZone, 'UTC')
  assert.equal((await api('/nocode/report/dataset/query', query)).timeZone, 'Asia/Shanghai')
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  const noKey = structuredClone(restricted)
  noKey[1].readFields = noKey[1].readFields.filter(field => field !== objects[1].definition.relations[0].fieldId)
  await api('/nocode/report/dataset/data-policy', {
    datasetId: id,
    expectedRevision: policy.revision,
    members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: noKey }],
    reason: '撤销第二层关联键读取'
  })
  const failed = page.waitForResponse(r => r.url().endsWith('/nocode/report/dataset/query'))
  await page.getByRole('button', { name: /查询预览$/ }).click()
  assert.notEqual((await (await failed).json()).code, 0)
  await expect(page.locator('.ant-tabs-tabpane-active .ant-alert-error')).toBeVisible()
  checks.push('DATE 按月跨时区不偏移；请求不能篡改发布时区，草稿改 UTC 不改变 V1 上海时区；撤销关联键后两层查询拒绝')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: resolve(output, 'source-preview.png'), fullPage: true })
  if (process.env.REPORT_KEEP_DEMO === 'true') {
    const latestPolicy = await api('/nocode/report/dataset/data-policy?id=' + id)
    await api('/nocode/report/dataset/data-policy', {
      datasetId: id,
      expectedRevision: latestPolicy.revision,
      members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: restricted }],
      reason: '保留用户授权的可体验报表案例'
    })
    const board = await api('/nocode/report/dashboard/get?id=' + dashboardId)
    const release = await api('/nocode/report/dashboard/published?id=' + dashboardId)
    const prepared = await api('/nocode/report/dashboard/save', {
      id: dashboardId,
      expectedRevision: board.revision,
      content: {
        ...release.content,
        name: '经营分析 · 核心体验看板',
        description: '可操作体验数据：流水 → 账户 → 公司。包含指标卡、柱状图、折线图、饼图和汇总表；金额合计 19.25。'
      }
    })
    await api('/nocode/report/dashboard/publish', {
      id: dashboardId,
      expectedRevision: prepared.revision,
      requestId: crypto.randomUUID()
    })
    const dataset = await api('/nocode/report/dataset/get?id=' + id)
    await api('/nocode/report/dataset/save', {
      ...dataset.draft,
      id,
      expectedRevision: dataset.revision,
      name: '报表体验 · 经营分析数据集'
    })
    const directory = resolve('.work/report-demo')
    await mkdir(directory, { recursive: true })
    await writeFile(
      resolve(directory, 'current.json'),
      JSON.stringify(
        {
          dashboardId,
          datasetId: id,
          objectIds: objects.map(o => o.objectId),
          prefix: names[0].replace('来源流水', ''),
          createdAt: new Date().toISOString()
        },
        null,
        2
      )
    )
    await page.goto(origin + '/nocode/report-center/dashboard-view?id=' + dashboardId)
    await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
    await expect(page.locator('.report-chart canvas')).toHaveCount(3)
    await page.screenshot({ path: resolve(output, 'dashboard-demo.png'), fullPage: true })
    checks.push('保留已标识的可体验看板及来源对象，恢复有效读取策略；看板已发布可直接打开')
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors }, null, 2))
  console.log('Report sources browser: ' + checks.length + ' checks passed; ' + output)
} catch (e) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ checks, errors, message: e.message }, null, 2))
  throw e
} finally {
  await browser.close()
}
