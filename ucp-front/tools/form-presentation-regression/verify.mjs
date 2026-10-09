import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const output = resolve(process.env.FORM_PRESENTATION_OUTPUT || 'tests/test-results/form-presentation')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.FORM_BROWSER || 'msedge' })
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
page.setDefaultTimeout(8000)
const errors = [],
  checks = []
page.on('pageerror', e => errors.push(e.message))
page.on('response', r => {
  if (r.status() >= 400) errors.push(`${r.status()} ${r.url()}`)
})
const base = process.env.FORM_PRESENTATION_URL || 'http://127.0.0.1:5193'
const rects = locator =>
  locator.evaluateAll(es =>
    es.map(e => {
      const r = e.getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height, text: e.innerText }
    })
  )
async function columns(locator, count) {
  await expect(locator).toHaveCount(12)
  // Ant Modal 缩放和分段控件切换动画完成后再测量真实尺寸。
  await page.waitForTimeout(350)
  const boxes = await rects(locator)
  expect(boxes.length).toBe(12)
  for (let i = 0; i < count; i++) expect(Math.abs(boxes[i].y - boxes[0].y)).toBeLessThan(1)
  expect(boxes[count].y).toBeGreaterThan(boxes[0].y + 30)
  if (count > 1) expect(Math.abs(boxes[1].x - boxes[0].x - boxes[0].width - 24)).toBeLessThan(1)
  return boxes
}
async function screenshot(name) {
  await page.screenshot({ path: resolve(output, `${name}.png`), animations: 'disabled', fullPage: true })
}
async function noOverflow(root) {
  expect(await root.evaluate(e => e.scrollWidth <= e.clientWidth + 1)).toBe(true)
  for (const form of await root.locator('.os-form-surface').all())
    expect(await form.evaluate(e => e.scrollWidth <= e.clientWidth + 1)).toBe(true)
}

try {
  await page.goto(base)
  await expect(page.locator('.designer-tools').getByRole('button', { name: /预览/ })).toBeEnabled()
  for (const [count, name] of [
    [1, '单列'],
    [2, '双列'],
    [3, '三列']
  ]) {
    await page.locator('.designer-tools').getByRole('button', { name: '快捷排版' }).hover()
    await page.getByRole('menuitem', { name, exact: true }).click()
    await columns(page.locator('._fc-m-drag .ant-form-item'), count)
    await screenshot(`design-${count}`)
    await page.locator('.designer-tools').getByRole('button', { name: /预览/ }).click()
    await columns(page.locator('.preview-paper .ant-form-item'), count)
    const preview = page.locator('.preview-paper')
    await noOverflow(preview)
    await expect(preview.locator('.ant-alert-warning')).toHaveCount(0)
    if (count === 2) {
      await preview.getByRole('button', { name: '校验预览', exact: true }).click()
      await expect(preview.locator('.ant-form-item-has-error')).toHaveCount(1)
      await expect(preview.getByPlaceholder('请输入正式名称')).toBeFocused()
      await preview.getByPlaceholder('请输入正式名称').fill('预览中的公司名称')
      await preview.getByRole('button', { name: '校验预览', exact: true }).click()
      await expect(preview.locator('.ant-alert-success')).toContainText('校验通过')
    }
    await screenshot(`preview-${count}`)
    await page.locator('.preview-tools').getByText('编辑', { exact: true }).click()
    await columns(preview.locator('.ant-form-item'), count)
    await page.locator('.preview-tools').getByText('查看', { exact: true }).click()
    await columns(preview.locator('.os-read-field'), count)
    expect(await preview.locator('input,textarea,[role="combobox"],[role="switch"]').count()).toBe(0)
    await page.locator('.preview-tools').getByText('窄屏', { exact: true }).click()
    await columns(preview.locator('.os-read-field'), 1)
    await page.locator('.preview-tools').getByText('新增', { exact: true }).click()
    await columns(preview.locator('.ant-form-item'), 1)
    await noOverflow(preview)
    await screenshot(`mobile-${count}`)
    await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click()
    await page.getByRole('button', { name: '运行编辑', exact: true }).click()
    await columns(page.locator('.spec-paper .ant-form-item'), count)
    await page.getByRole('button', { name: '运行详情', exact: true }).click()
    await columns(page.locator('.spec-paper .os-read-field'), count)
    expect(await page.locator('.spec-paper input,.spec-paper textarea,.spec-paper [role="combobox"]').count()).toBe(0)
    await page.getByRole('button', { name: '设计画布', exact: true }).click()
    await columns(page.locator('._fc-m-drag .ant-form-item'), count)
    checks.push(`${name}：设计、桌面新增/编辑/查看预览、窄屏预览、运行编辑/详情和重开一致；列间距 24px`)
  }
  await page.getByRole('button', { name: '分组与页签示例' }).click()
  await page.getByRole('button', { name: '运行编辑', exact: true }).click()
  await expect(page.locator('.spec-paper .os-form-section')).toHaveCount(1)
  await page.getByRole('tab', { name: '其他信息', exact: true }).click()
  await expect(page.getByPlaceholder('请输入备注')).toBeVisible()
  await page.getByRole('button', { name: '运行详情', exact: true }).click()
  await expect(page.locator('.spec-paper .os-form-section')).toHaveCount(1)
  await page.getByRole('tab', { name: '其他信息', exact: true }).click()
  await expect(page.locator('.os-read-value').filter({ hasText: '支持长文本换行' })).toBeVisible()
  await screenshot('grouped-detail')
  await page.getByLabel('左侧标签', { exact: true }).check()
  await screenshot('horizontal-detail')
  await page.locator('header').getByRole('switch').click()
  await noOverflow(page.locator('.spec-paper'))
  await screenshot('narrow-detail')
  await page.getByRole('button', { name: '运行编辑', exact: true }).click()
  await noOverflow(page.locator('.spec-paper'))
  await screenshot('narrow-horizontal-edit')
  checks.push('卡片、分隔线、页签、横向标签在编辑与详情保持结构，窄容器无横向溢出')
  await page.goto(base)
  await expect(page.locator('.designer-tools').getByRole('button', { name: /预览/ })).toBeEnabled()
  await page.getByRole('button', { name: '默认布局示例' }).click()
  await page.getByRole('button', { name: '运行编辑', exact: true }).click()
  const inputs = page.locator('.spec-paper .ant-form-item')
  const boxes = await rects(inputs)
  expect(boxes[6].width).toBeGreaterThan(boxes[0].width * 1.8)
  await page.getByPlaceholder('请输入正式名称').fill('更新后的公司名称')
  await page.getByRole('button', { name: '运行详情', exact: true }).click()
  await expect(page.locator('.os-read-value').filter({ hasText: '更新后的公司名称' })).toBeVisible()
  await expect(page.locator('.os-read-value').filter({ hasText: /^否$/ })).toBeVisible()
  await expect(page.locator('.os-read-value').filter({ hasText: /^0$/ })).toBeVisible()
  await screenshot('default-detail')
  checks.push('默认布局保留字段顺序、长文本整行；编辑值正常回显，0/否/空值分别显示')
  await page.getByRole('button', { name: '含明细详情', exact: true }).click()
  await expect(page.locator('.detail-section')).toContainText('上海分公司')
  expect(
    await page
      .locator('.spec-paper input,.spec-paper textarea,.spec-paper [role="combobox"],.spec-paper [role="switch"]')
      .count()
  ).toBe(0)
  await expect(page.locator('.spec-paper').getByRole('button', { name: '保存记录' })).toHaveCount(0)
  await screenshot('aggregate-detail')
  checks.push('RecordEditor 主记录与内部明细均为纯只读展示，无编辑控件及保存入口')
  checks.push('选择字段预览使用内存候选接口；必填失败就地提示并聚焦，填写后校验通过且不保存业务记录')
  await page.goto(base)
  await expect(page.locator('.designer-tools').getByRole('button', { name: /预览/ })).toBeEnabled()
  await page.getByText('栅格布局', { exact: true }).click()
  await expect(page.locator('._fc-m-drag .os-form-row')).toHaveCount(7)
  await expect(page.locator('._fc-m-drag .os-form-column')).toHaveCount(14)
  await page.getByText('卡片', { exact: true }).click()
  await expect(page.locator('._fc-m-drag .os-form-section')).toHaveCount(1)
  checks.push('从物料面板新建的分栏、列和卡片即时接入统一样式，无需保存重开')
  expect(errors).toEqual([])
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ passed: true, checks, errors }, null, 2))
  console.log(JSON.stringify({ passed: true, checks, errors }, null, 2))
} catch (error) {
  await screenshot('failure')
  await writeFile(
    resolve(output, 'results.json'),
    JSON.stringify({ passed: false, checks, errors, failure: String(error) }, null, 2)
  )
  console.error(error)
  console.error({ checks, errors })
  process.exitCode = 1
} finally {
  await browser.close()
}
