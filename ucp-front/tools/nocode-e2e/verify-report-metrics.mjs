import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })

/** 在来源专项的真实受限数据集上通过页面制作指标，结束时恢复原草稿供后续检查。 */
export async function verifyMetrics({ page, api, id, preview, save, output, checks, version }) {
  const original = await api('/nocode/report/dataset/get?id=' + id)
  async function selectIn(container, label, title) {
    await container.locator(`[aria-label="${label}"] .ant-select-selector`).click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(title, { exact: true }).last().click()
    await page.getByRole('textbox', { name: '数据集说明', exact: true }).click()
  }
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await page.getByRole('button', { name: '添加指标', exact: true }).click()
  await page.getByRole('textbox', { name: '指标名称', exact: true }).last().fill('达标笔数')
  const conditionPanel = page
    .locator('.metric-conditions .ant-collapse-item')
    .filter({ hasText: '达标笔数 · 指标条件' })
  await conditionPanel.getByRole('button', { name: /达标笔数 · 指标条件/ }).click()
  await page.getByRole('switch', { name: '达标笔数指标条件', exact: true }).click()
  await conditionPanel.getByRole('button', { name: '添加条件', exact: true }).click()
  await selectIn(conditionPanel, '范围字段', '金额')
  await selectIn(conditionPanel, '范围匹配方式', '大于等于')
  await conditionPanel.getByRole('textbox', { name: '范围值', exact: true }).fill('5')
  await page.getByRole('button', { name: '添加指标', exact: true }).click()
  await page.getByRole('textbox', { name: '指标名称', exact: true }).last().fill('每达标笔金额')
  const row = page.locator('.ant-tabs-tabpane-active .ant-table-row').last()
  await selectIn(row, '聚合方式', '聚合后四则')
  await selectIn(row, '左侧指标', '金额合计')
  await selectIn(row, '右侧指标', '达标笔数')
  await expect(
    page.locator('.ant-tabs-tabpane-active .ant-table-row').first().getByRole('button', { name: '移除', exact: true })
  ).toBeDisabled()
  await save()
  await page.screenshot({ path: resolve(output, 'metrics-editor.png'), fullPage: true })
  await page.reload()
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '指标名称', exact: true }).last()).toHaveValue('每达标笔金额')
  const definition = await api('/nocode/report/dataset/get?id=' + id)
  const [sum, count, ratio] = definition.draft.analysis.metrics
  assert.equal(String(count.conditions.conditions[0].value), '5')
  assert.deepEqual(ratio.formula, { operator: 'DIVIDE', left: sum.id, right: count.id })
  checks.push('页面配置带金额条件的 COUNT 与聚合后除法，保存重开保留稳定指标引用；被公式引用的指标禁止删除')
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await selectIn(page, '分组维度', '业务日期')
  await selectIn(page, '业务日期分组粒度', '按月')
  let result = await preview()
  assert.equal(result.recordCount, 3)
  assert.equal(result.totals[sum.id], '19.25')
  assert.equal(result.totals[count.id], '2')
  assert.equal(Number(result.totals[ratio.id]), 9.625)
  assert.deepEqual(Object.fromEntries(result.groups.map(g => [g.keys[0], Number(g.values[ratio.id])])), {
    '2026-01': 10.25,
    '2026-02': 9
  })
  await expect(page.locator('.ant-tabs-tabpane-active').getByText('9.625', { exact: true })).toBeVisible()
  await expect(page.locator('.ant-tabs-tabpane-active').getByText('9.6250000000000000', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: resolve(output, 'metrics-preview.png'), fullPage: true })
  checks.push('受第二层公司行权限裁剪后总额 19.25、达标笔数 2、总比率 9.625；总计重新聚合，不累加月比率 10.25 和 9')
  await page.getByRole('button', { name: /^发\s*布$/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '操作原因', exact: true }).fill('指标条件与四则页面验收')
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  const release = (await api('/nocode/report/dataset/releases?id=' + id)).list[0]
  const releasedQuery = {
    datasetId: id,
    preview: false,
    versionNo: release.versionNo,
    checksum: release.checksum,
    dimensions: [],
    metricIds: [ratio.id],
    limit: 1
  }
  result = await api('/nocode/report/dataset/query', releasedQuery)
  assert.deepEqual(Object.keys(result.totals), [ratio.id])
  assert.deepEqual(
    result.metrics.map(m => m.id),
    [ratio.id]
  )
  assert.equal(Number(result.totals[ratio.id]), 9.625)
  const legacy = await api('/nocode/report/dataset/query', {
    ...releasedQuery,
    versionNo: version.versionNo,
    checksum: version.checksum,
    metricIds: null
  })
  assert.equal(legacy.metrics.length, 1)
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await conditionPanel.getByRole('button', { name: /达标笔数 · 指标条件/ }).click()
  await conditionPanel.getByRole('textbox', { name: '范围值', exact: true }).fill('100')
  await save()
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  result = await preview()
  assert.equal(result.totals[count.id], '0')
  assert.equal(result.totals[ratio.id], null)
  assert.equal(Number((await api('/nocode/report/dataset/query', releasedQuery)).totals[ratio.id]), 9.625)
  checks.push(
    '页面发布后仅选派生指标自动读取受控依赖且不展示隐藏依赖；草稿修改至零分母返回空值，已发布公式及旧 V1 均保持原口径'
  )
  const beforeInvalid = await api('/nocode/report/dataset/get?id=' + id)
  for (const formula of [
    { operator: 'ADD', left: ratio.id, right: sum.id },
    { operator: 'DIVIDE', left: sum.id, right: 'missing' }
  ]) {
    const invalid = structuredClone(beforeInvalid.draft)
    invalid.analysis.metrics.find(m => m.id === ratio.id).formula = formula
    await assert.rejects(
      () => api('/nocode/report/dataset/save', { ...invalid, id, expectedRevision: beforeInvalid.revision }),
      /循环|不存在/
    )
    const unchanged = await api('/nocode/report/dataset/get?id=' + id)
    assert.equal(unchanged.revision, beforeInvalid.revision)
    assert.equal(unchanged.checksum, beforeInvalid.checksum)
  }
  checks.push('真实 HTTP 拒绝公式自引用与失效引用，失败不改变草稿修订或摘要')
  const current = await api('/nocode/report/dataset/get?id=' + id)
  await api('/nocode/report/dataset/save', { ...original.draft, id, expectedRevision: current.revision })
  await page.reload()
}
