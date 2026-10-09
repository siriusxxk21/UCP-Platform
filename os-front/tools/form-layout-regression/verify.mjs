import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const output = resolve(process.env.FORM_LAYOUT_OUTPUT || 'tests/test-results/form-layout')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.FORM_LAYOUT_BROWSER || 'msedge' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.setDefaultTimeout(10000)
const errors = [],
  checks = []
page.on('pageerror', error => errors.push(error.message))
page.on('response', response => {
  if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`)
})
const base = process.env.FORM_LAYOUT_URL || 'http://127.0.0.1:5191'

async function switchSection(name) {
  await page.getByRole('button', { name, exact: true }).click()
}
async function visibleFooter() {
  const bounds = await page.locator('.ant-modal-content').last().boundingBox()
  const footer = await page.locator('.ant-modal-footer').last().boundingBox()
  expect(bounds.y).toBeGreaterThanOrEqual(0)
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(page.viewportSize().width + 1)
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(page.viewportSize().height + 1)
  expect(footer.y + footer.height).toBeLessThanOrEqual(page.viewportSize().height + 1)
}
async function noSelectOverlap(container) {
  const violations = await container.evaluate(root => {
    const issues = []
    for (const select of root.querySelectorAll('.ant-select-multiple')) {
      const box = select.querySelector('.ant-select-selector').getBoundingClientRect()
      for (const item of select.querySelectorAll('.ant-select-selection-overflow-item')) {
        const style = getComputedStyle(item)
        if (style.position === 'absolute' || style.visibility === 'hidden' || style.display === 'none') continue
        const rect = item.getBoundingClientRect()
        if (rect.height && (rect.bottom > box.bottom + 2 || rect.top < box.top - 2)) issues.push('多选内容越出边框')
      }
      const following = select.parentElement.querySelector('.following-field')?.getBoundingClientRect()
      if (following && following.top < box.bottom - 1) issues.push('多选与后续内容重叠')
    }
    return issues
  })
  expect(violations).toEqual([])
}

try {
  await page.goto(base)
  await page.getByRole('button', { name: '配置权限', exact: false }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  for (const size of [
    { width: 1440, height: 900 },
    { width: 918, height: 670 },
    { width: 768, height: 512 },
    { width: 480, height: 720 }
  ]) {
    await page.setViewportSize(size)
    await visibleFooter()
    await noSelectOverlap(page.getByRole('dialog'))
    await page.screenshot({ path: resolve(output, `sharing-${size.width}.png`), animations: 'disabled' })
    checks.push(`共享授权 ${size.width}×${size.height}：内容无重叠，标题和操作区在视口内`)
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  const read = page.getByRole('combobox', { name: '可查看字段', exact: true })
  await read.click()
  await read.fill('法人代表')
  await expect(page.locator('.ant-select-dropdown:visible').getByTitle('法人代表', { exact: true })).toBeVisible()
  await read.fill('')
  await read.press('Escape')
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '展开可查看字段已选清单', exact: true }).click()
  const list = page.getByRole('list', { name: '可查看字段', exact: true })
  await expect(list.getByRole('listitem')).toHaveCount(45)
  await list.getByRole('button', { name: '移除法人代表', exact: true }).click()
  await expect(list.getByRole('listitem')).toHaveCount(44)
  await page.getByRole('button', { name: '展开可填写和修改字段已选清单', exact: true }).click()
  await expect(
    page.getByRole('list', { name: '可填写和修改字段', exact: true }).getByText('法人代表', { exact: true })
  ).toHaveCount(0)
  await noSelectOverlap(page.getByRole('dialog'))
  checks.push('中文名称搜索、45 项完整清单、移除查看项同步移除对应修改权限')
  await page.getByRole('button', { name: '保存共享授权', exact: true }).click()
  await expect(page.getByText('请填写变更说明，说明本次授权调整的原因')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '变更说明', exact: false })).toBeFocused()
  await expect(page.getByTestId('save-count')).toHaveText('0')
  await page.getByRole('button', { name: /取\s*消/ }).click()
  await page.getByRole('button', { name: '配置权限', exact: false }).click()
  await page.getByRole('button', { name: '展开可查看字段已选清单', exact: true }).click()
  await expect(page.getByRole('list', { name: '可查看字段', exact: true }).getByRole('listitem')).toHaveCount(45)
  checks.push('必填说明就地报错并聚焦；取消后再次打开恢复已保存授权')
  await page.getByRole('textbox', { name: '变更说明', exact: false }).fill('回归夹具保存')
  await page.getByRole('button', { name: '保存共享授权', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByTestId('save-count')).toHaveText('1')
  await page.getByRole('checkbox', { name: '模拟保存失败' }).check()
  await page.getByRole('button', { name: '配置权限', exact: false }).click()
  await page.getByRole('textbox', { name: '变更说明', exact: false }).fill('失败时保留输入')
  await page.getByRole('button', { name: '保存共享授权', exact: true }).click()
  await expect(page.getByText('回归夹具：保存失败，请重试')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '变更说明', exact: false })).toHaveValue('失败时保留输入')
  await page.getByRole('button', { name: /取\s*消/ }).click()
  checks.push('内存接口保存成功关闭并刷新；失败保留输入与错误提示')
  await page.getByRole('checkbox', { name: '模拟保存失败' }).uncheck()
  await page.getByRole('button', { name: '配置权限', exact: false }).click()
  await page.getByRole('button', { name: '撤销共享授权', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '变更说明', exact: false })).toBeFocused()
  await expect(page.getByTestId('save-count')).toHaveText('1')
  await page.getByRole('textbox', { name: '变更说明', exact: false }).fill('回归夹具撤销')
  await page.getByRole('button', { name: '撤销共享授权', exact: true }).click()
  await page.getByRole('button', { name: '确认撤销', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: '重新授权', exact: false }).click()
  await expect(page.getByRole('button', { name: '撤销共享授权', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: /取\s*消/ }).click()
  checks.push('撤销先校验原因再确认；已撤销记录不再提供重复撤销按钮（内存夹具）')
  await switchSection('readonly')
  await expect(page.getByRole('list', { name: '可查看字段', exact: true }).getByRole('listitem')).toHaveCount(45)
  await expect(page.getByRole('combobox')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /^移除/ })).toHaveCount(0)
  await page.getByRole('list', { name: '可查看字段', exact: true }).focus()
  await page.keyboard.press('End')
  await page.screenshot({ path: resolve(output, 'readonly.png'), animations: 'disabled' })
  checks.push('只读范围完整显示，可聚焦滚动，无编辑入口')
  await switchSection('restricted')
  await page.getByRole('combobox', { name: '可查看字段', exact: true }).click()
  await page.getByRole('combobox', { name: '可查看字段', exact: true }).fill('英文名')
  await expect(page.locator('.ant-select-dropdown:visible .ant-select-item-option-disabled')).toContainText('英文名')
  await page.keyboard.press('Escape')
  checks.push('成员授权超出应用上限的选项保持禁用')
  await switchSection('controls')
  await expect(page.getByTestId('generated-form').locator('.ant-select-selection-item')).toHaveCount(45)
  await noSelectOverlap(page.getByTestId('generated-form'))
  const generated = page.getByTestId('generated-form').getByRole('combobox')
  await generated.click()
  await generated.fill('法人代表')
  await expect(page.locator('.ant-select-dropdown:visible').getByTitle('法人代表', { exact: true })).toBeVisible()
  await generated.press('Escape')
  expect(
    (await page.getByTestId('advanced-query').locator('.ant-select-multiple .ant-select-selector').boundingBox()).height
  ).toBeLessThan(40)
  checks.push('实际 RecordForm 生成表单的45项回填与名称搜索、高级条件多选折叠正常')
  for (const width of [1440, 918, 480]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.getByTestId('multiple-middle').locator('.ant-select-selection-item')).toHaveCount(45)
    await noSelectOverlap(page.locator('.control-grid'))
    for (const [size, height] of [
      ['small', 28],
      ['middle', 32],
      ['large', 40]
    ]) {
      const box = await page.getByTestId(`single-${size}`).locator('.ant-select-selector').boundingBox()
      expect(box.height).toBe(height)
    }
    expect((await page.getByTestId('textarea').locator('textarea').boundingBox()).height).toBeGreaterThan(70)
    expect((await page.getByTestId('query').locator('.ant-select-selector').boundingBox()).height).toBeLessThan(45)
    checks.push(`${width}px：普通/大小尺寸/树/标签/禁用/空值/成员/查询多选无溢出；单选高度与多行备注正常`)
  }
  await page.setViewportSize({ width: 918, height: 670 })
  await switchSection('modal')
  await page.getByRole('button', { name: '打开底座长表单' }).click()
  await visibleFooter()
  expect(await page.locator('.ant-modal-body').evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true)
  await noSelectOverlap(page.getByRole('dialog'))
  await page.screenshot({ path: resolve(output, 'base-modal.png'), animations: 'disabled' })
  await page.getByRole('button', { name: /取\s*消/ }).click()
  checks.push('底座长表单在指定高度超过视口时仍可滚动，底部取消可操作')
  expect(errors).toEqual([])
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ capturedAt: new Date().toISOString(), checks, errors }, null, 2)
  )
  console.log(JSON.stringify({ passed: checks.length, errors, output }))
} catch (error) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true })
  console.error(error)
  console.error(JSON.stringify(errors))
  process.exitCode = 1
} finally {
  await browser.close()
}
