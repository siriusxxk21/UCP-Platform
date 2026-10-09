import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })
export async function verifyOptions({ page, api, id, source, preview, select, output, checks }) {
  const field = name => source.fields.find(f => f.name === name).id
  const options = (name, extra = {}) =>
    api('/nocode/report/dataset/options', {
      datasetId: id,
      preview: true,
      fieldId: field(name),
      pageNo: 1,
      pageSize: 20,
      ...extra
    })
  const states = await options('状态', { search: '正常' })
  assert.deepEqual(states.list, [
    { value: 'CLOSED', label: '正常' },
    { value: 'OPEN', label: '正常' }
  ])
  assert.equal((await options('状态', { search: '隐藏' })).total, 0)
  assert.equal((await options('状态', { search: '未使用' })).total, 0)
  assert.deepEqual((await options('已核销', { search: '是' })).list, [{ value: 'true', label: '是' }])
  const grouped = await api('/nocode/report/dataset/query', {
    datasetId: id,
    preview: true,
    dimensions: [{ fieldId: field('状态'), bucket: 'VALUE' }],
    limit: 20
  })
  assert.deepEqual(new Set(grouped.groups.map(g => g.keys[0])), new Set(['OPEN', 'CLOSED']))
  assert.ok(grouped.groups.every(g => g.labels[0] === '正常'))
  assert.equal(grouped.recordCount, 3)
  const companies = await options('公司')
  assert.equal(companies.total, 2)
  assert.ok(companies.list.some(item => item.value === null && item.label === '未填写'))
  assert.ok(!JSON.stringify(companies).includes('隐藏公司'))
  const references = await options('公司原键', { search: '可见公司' })
  assert.equal(references.total, 1)
  assert.equal(references.list[0].label, '可见公司')
  assert.match(references.list[0].value, /^[0-9]+$/)
  assert.equal((await options('公司原键', { search: '隐藏公司' })).total, 0)
  const relationGroups = await api('/nocode/report/dataset/query', {
    datasetId: id,
    preview: true,
    dimensions: [{ fieldId: field('公司原键'), bucket: 'VALUE' }],
    limit: 20
  })
  assert.ok(relationGroups.groups.some(g => g.keys[0] === references.list[0].value && g.labels[0] === '可见公司'))
  assert.equal(relationGroups.recordCount, 3)
  await page.getByRole('switch', { name: '临时筛选', exact: true }).click()
  const relationFilters = page.locator('.preview-filters')
  await relationFilters.getByRole('button', { name: '添加条件', exact: true }).click()
  await select('范围字段', '公司原键')
  await relationFilters.getByRole('button', { name: '选择候选', exact: true }).click()
  const relationDialog = page.getByRole('dialog')
  await relationDialog.getByRole('textbox', { name: '搜索候选', exact: true }).fill('可见公司')
  await relationDialog.getByRole('button', { name: /^搜\s*索$/ }).click()
  await expect(relationDialog.getByText('共 1 个值', { exact: true })).toBeVisible()
  await relationDialog.getByRole('radio', { name: /可见公司/ }).check()
  await page.screenshot({ path: resolve(output, 'relation-title-picker.png'), fullPage: true })
  await relationDialog.getByRole('button', { name: '使用所选值', exact: true }).click()
  await expect(relationFilters.getByRole('textbox', { name: '范围值', exact: true })).toHaveValue(
    references.list[0].value
  )
  const relationResult = await preview()
  assert.equal(Number(Object.values(relationResult.totals)[0]), 10.25)
  await page.getByRole('switch', { name: '临时筛选', exact: true }).click()
  checks.push('两层关系原键按固定目标标题显示和搜索，候选回填真实 ID 后合计 10.25；隐藏目标不返回，NULL 仍独立')
  checks.push(
    '枚举按固定来源定义显示名称，同名 OPEN/CLOSED 保持独立原键；标签搜索不返回隐藏或未使用值，布尔标签和关联空值正确'
  )
  await page.getByRole('switch', { name: '临时筛选', exact: true }).click()
  const filters = page.locator('.preview-filters')
  await filters.getByRole('button', { name: '添加条件', exact: true }).click()
  await select('范围字段', '状态')
  await filters.getByRole('button', { name: '选择候选', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('共 2 个值', { exact: true })).toBeVisible()
  await dialog.getByRole('textbox', { name: '搜索候选', exact: true }).fill('正常')
  await dialog.getByRole('button', { name: /^搜\s*索$/ }).click()
  await expect(dialog.getByRole('radio', { name: /正常\s*（CLOSED）/ })).toBeVisible()
  await dialog.getByRole('radio', { name: /正常\s*（CLOSED）/ }).check()
  await dialog.getByRole('button', { name: '使用所选值', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(filters.getByRole('textbox', { name: '范围值', exact: true })).toHaveValue('CLOSED')
  let result = await preview()
  assert.equal(Number(Object.values(result.totals)[0]), 9)
  await filters.getByRole('button', { name: '选择候选', exact: true }).click()
  await expect(dialog.getByText('共 2 个值', { exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(output, 'options-picker.png'), fullPage: true })
  let releaseDelayed
  let reportStarted
  const started = new Promise(resolve => {
    reportStarted = resolve
  })
  const gate = new Promise(resolve => {
    releaseDelayed = resolve
  })
  const delay = async route => {
    const response = await route.fetch()
    if (route.request().postDataJSON().search === '隐藏') {
      reportStarted()
      await gate
    }
    await route.fulfill({ response })
  }
  await page.route('**/nocode/report/dataset/options', delay)
  await dialog.getByRole('textbox', { name: '搜索候选', exact: true }).fill('隐藏')
  await dialog.getByRole('button', { name: /^搜\s*索$/ }).click()
  await started
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await filters.getByRole('button', { name: '选择候选', exact: true }).click()
  await expect(dialog.getByText('共 2 个值', { exact: true })).toBeVisible()
  const delayed = page.waitForResponse(
    r => r.url().endsWith('/dataset/options') && r.request().postDataJSON().search === '隐藏'
  )
  releaseDelayed()
  await delayed
  await expect(dialog.getByText('共 2 个值', { exact: true })).toBeVisible()
  await page.unroute('**/nocode/report/dataset/options', delay)
  checks.push('真实候选请求延迟返回时，关闭重开后的新结果不被旧的空搜索结果覆盖')
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  await page.getByRole('switch', { name: '临时筛选', exact: true }).click()
  result = await preview()
  assert.equal(Number(Object.values(result.totals)[0]), 19.25)
  const conditions = {
    logic: 'AND',
    groups: [],
    conditions: [
      { fieldId: field('状态'), operator: 'eq', value: 'OPEN' },
      { fieldId: field('金额'), operator: 'lt', value: '10' }
    ]
  }
  assert.deepEqual((await options('状态', { filters: conditions })).list, [{ value: 'CLOSED', label: '正常' }])
  checks.push(
    '页面搜索和选择候选按原始键回填并查询真实结果；重新打开排除自身条件，其他金额条件继续收窄，取消不修改业务数据'
  )
}
