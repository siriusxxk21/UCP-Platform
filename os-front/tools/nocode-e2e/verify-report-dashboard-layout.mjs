/** 实际鼠标操作验证画布；调用方只传入自己创建的看板夹具。 */
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })

export async function verifyDashboardLayout({ page, api, id, origin, output, checks }) {
  const before = await api('/nocode/report/dashboard/get?id=' + id)
  const metric = before.draft.charts.find(chart => chart.display === 'METRIC' && chart.w >= 6 && chart.h >= 3)
  assert.ok(metric, '夹具需要可缩小的指标组件')
  await page.goto(origin + '/nocode/report-center/dashboard-editor?id=' + id)
  await expect(page.getByRole('heading', { name: '仪表板设计', exact: true })).toBeVisible()
  const tile = title =>
    page.locator('.dashboard-editor-tile').filter({ has: page.getByRole('heading', { name: title, exact: true }) })
  await tile(metric.title)
    .getByRole('button', { name: /^复\s*制$/ })
    .click()
  const cloneTitle = (metric.title + ' 副本').slice(0, 80)
  await expect(tile(cloneTitle)).toHaveCount(1)
  await page.getByRole('button', { name: /^撤\s*销$/ }).click()
  await expect(tile(cloneTitle)).toHaveCount(0)
  await page.getByRole('button', { name: /^重\s*做$/ }).click()
  await expect(tile(cloneTitle)).toHaveCount(1)
  const save = async () => {
    const response = page.waitForResponse(
      value => value.url().endsWith('/dashboard/save') && value.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '保存并预览', exact: true }).click()
    assert.equal((await (await response).json()).code, 0)
    await expect(page.getByRole('button', { name: '保存并预览', exact: true })).not.toHaveClass(/ant-btn-loading/)
    return api('/nocode/report/dashboard/get?id=' + id)
  }
  let current = await save()
  const copied = current.draft.charts.find(chart => chart.title === cloneTitle)
  assert.ok(copied && copied.id !== metric.id)
  assert.deepEqual(copied.dataset, metric.dataset)
  for (const filter of before.draft.filters || []) {
    const mapping = filter.mappings.find(value => value.chartId === metric.id)
    if (mapping)
      assert.deepEqual(
        current.draft.filters.find(value => value.id === filter.id).mappings.find(value => value.chartId === copied.id),
        { ...mapping, chartId: copied.id }
      )
  }
  for (const source of before.draft.charts) {
    const incoming = source.links?.find(link => link.targetChartId === metric.id)
    if (incoming)
      assert.deepEqual(
        current.draft.charts.find(chart => chart.id === source.id).links.find(link => link.targetChartId === copied.id),
        { ...incoming, targetChartId: copied.id }
      )
  }
  checks.push('组件复制使用新ID和空白位置，公共筛选与双向联动映射延续；实际撤销/重做与保存保持固定数据集pin')
  const steps = await page.locator('.dashboard-grid').evaluate(grid => {
    const style = getComputedStyle(grid),
      gap = parseFloat(style.columnGap)
    return {
      x: (grid.getBoundingClientRect().width - 11 * gap) / 12 + gap,
      y: parseFloat(style.gridAutoRows) + parseFloat(style.rowGap)
    }
  })
  const handle = tile(cloneTitle).getByRole('button', { name: '缩放' + cloneTitle, exact: true })
  await handle.scrollIntoViewIfNeeded()
  let box = await handle.boundingBox()
  assert.ok(box)
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 - steps.x * 3, box.y + box.height / 2 - steps.y, { steps: 12 })
  await page.mouse.up()
  current = await save()
  let resized = current.draft.charts.find(chart => chart.id === copied.id)
  assert.equal(resized.w, copied.w - 3)
  assert.equal(resized.h, copied.h - 1)
  await page.getByRole('button', { name: /^撤\s*销$/ }).click()
  await page.getByRole('button', { name: /^重\s*做$/ }).click()
  const moving = tile(cloneTitle).getByRole('button', { name: '移动' + cloneTitle, exact: true })
  await moving.scrollIntoViewIfNeeded()
  box = await moving.boundingBox()
  assert.ok(box)
  const targetX = resized.x === 0 ? 12 - resized.w : 0
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + (targetX - resized.x) * steps.x, box.y + box.height / 2, { steps: 16 })
  await page.mouse.up()
  current = await save()
  resized = current.draft.charts.find(chart => chart.id === copied.id)
  assert.equal(resized.x, targetX, '实际原生拖动需要落在目标列')
  assert.equal(resized.y, copied.y)
  assert.equal(resized.w, copied.w - 3)
  assert.equal(resized.h, copied.h - 1)
  await page.reload()
  await expect(tile(cloneTitle)).toHaveCount(1)
  assert.deepEqual(
    (await api('/nocode/report/dashboard/get?id=' + id)).draft.charts.find(chart => chart.id === copied.id),
    resized
  )
  await page.screenshot({ path: resolve(output, 'layout.png'), fullPage: true })
  checks.push('真实鼠标缩放及原生拖动逐格落点正确，一次手势形成一次撤销；保存重开布局和映射不丢失')
  return current
}
