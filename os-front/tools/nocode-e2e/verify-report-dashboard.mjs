import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })
export async function verifyDashboard({ page, api, id, output, checks }) {
  const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
  const dataset = await api('/nocode/report/dataset/get?id=' + id)
  const title = dataset.draft.name + ' · 经营看板'
  await page.goto(origin + '/nocode/report-center/dashboards')
  await page.getByRole('button', { name: '新建仪表板', exact: true }).click()
  await page.getByRole('textbox', { name: '仪表板名称', exact: true }).fill(title)
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^确\s*定$/ })
    .click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  const boardId = new URL(page.url()).searchParams.get('id')
  for (const [label, x, y] of [
    ['指标卡', 0, 0],
    ['柱状图', 0, 3],
    ['折线图', 6, 3],
    ['饼图', 0, 9],
    ['汇总表', 6, 9],
    ['透视表', 0, 15]
  ]) {
    await page.getByRole('button', { name: /添加组件/ }).click()
    await page.getByRole('menu').getByText(label, { exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('textbox', { name: '图表标题', exact: true }).fill('经营' + label)
    await dialog.locator('[aria-label="图表数据集"] .ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(dataset.draft.name, { exact: true }).click()
    await expect(dialog.locator('[aria-label="图表指标"]')).toContainText('金额合计')
    if (label !== '指标卡') {
      const chosen = dialog.locator('[aria-label="图表维度"]')
      if (label !== '饼图') await chosen.locator('.ant-select-selection-item-remove').click()
      await chosen.locator('.ant-select-selector').click()
      await page
        .locator('.ant-select-dropdown:visible')
        .getByTitle(/^公司(?: ·.*)?$/)
        .click()
      await dialog.getByRole('textbox', { name: '图表标题', exact: true }).click()
    }
    // 新版抽屉在应用前执行真实单图查询；随后进入布局页签设置网格。
    await expect(dialog.locator('.chart-config-preview .dashboard-scope')).toBeVisible()
    await dialog.getByRole('tab', { name: '布局', exact: true }).click()
    await dialog.getByRole('spinbutton', { name: '图表宽度', exact: true }).fill(label === '透视表' ? '12' : '6')
    await dialog.getByRole('spinbutton', { name: '图表列位置', exact: true }).fill(String(x))
    await dialog.getByRole('spinbutton', { name: '图表行位置', exact: true }).fill(String(y))
    await dialog.getByRole('button', { name: '应用到画布', exact: true }).click()
    await expect(dialog).not.toBeVisible()
  }
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.locator('.dashboard-tile')).toHaveCount(5)
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  const saved = page.waitForResponse(r => r.url().endsWith('/dashboard/save'))
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  assert.equal((await (await saved).json()).code, 0)
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  await expect(page.locator('.report-chart canvas')).toHaveCount(3)
  await page.reload()
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  const detail = await api('/nocode/report/dashboard/get?id=' + boardId)
  assert.equal(detail.draft.charts.length, 6)
  assert.deepEqual(
    new Set(detail.draft.charts.map(c => c.display)),
    new Set(['METRIC', 'BAR', 'LINE', 'PIE', 'TABLE', 'PIVOT'])
  )
  assert.deepEqual(
    detail.draft.charts.map(c => [c.x, c.y]),
    [
      [0, 0],
      [0, 3],
      [6, 3],
      [0, 9],
      [6, 9],
      [0, 15]
    ]
  )
  await page.getByRole('button', { name: '试用交互', exact: true }).click()
  await expect(page.getByRole('heading', { name: '草稿交互预览', exact: true })).toBeVisible()
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  await page.getByRole('button', { name: '返回编辑', exact: true }).click()
  checks.push(
    '独立仪表板真实页面完成六类组件抽屉配置、单图预览、双列网格、撤销重做、保存重开及草稿试用；指标卡19.25，三个图表引擎真实渲染'
  )
  await page.getByRole('button', { name: '发布并打开', exact: true }).click()
  await expect(page).toHaveURL(/dashboard-view/)
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  await expect(page.locator('.report-chart canvas')).toHaveCount(3)
  await page.screenshot({ path: resolve(output, 'dashboard-published.png'), fullPage: true })
  const published = await api('/nocode/report/dashboard/published?id=' + boardId)
  const current = await api('/nocode/report/dashboard/get?id=' + boardId)
  await api('/nocode/report/dashboard/save', {
    id: boardId,
    expectedRevision: current.revision,
    content: { ...current.draft, name: title + '草稿', charts: [] }
  })
  await page.getByRole('button', { name: '刷新数据', exact: true }).click()
  await expect(page.locator('.dashboard-tile')).toHaveCount(6)
  await expect(page.locator('.dashboard-metrics strong')).toHaveText('19.25')
  const unchanged = await api('/nocode/report/dashboard/published?id=' + boardId)
  assert.equal(unchanged.checksum, published.checksum)
  const deletion = await api('/nocode/report/dataset/delete-preview?id=' + id)
  assert.equal(deletion.canDelete, false)
  assert.ok(deletion.referenceCount > 0)
  await assert.rejects(
    () =>
      api('/nocode/report/dashboard/query', {
        id: boardId,
        chartId: 'forged',
        preview: false,
        versionNo: published.versionNo,
        checksum: published.checksum
      }),
    /不属于/
  )
  await assert.rejects(
    () =>
      api('/nocode/report/dashboard/query', {
        id: boardId,
        chartId: published.content.charts[0].id,
        preview: false,
        versionNo: published.versionNo,
        checksum: 'tampered'
      }),
    /校验和/
  )
  checks.push(
    '发布后独立运行保持固定六类看板；清空草稿不污染发布，数据集删除被历史看板依赖阻止，伪造组件和版本摘要被拒绝'
  )
  await page.goto(origin + '/nocode/report-center/dataset-editor?id=' + id)
  return boardId
}
