import { chromium, expect as baseExpect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
const expect = baseExpect.configure({ timeout: 20000 })

// 独立会话中的关键选型验证。仅查询现有业务数据，表单提交停留在浏览器内。
const output = fileURLToPath(new URL('../../../os-server/os-nocode/.work/tinyengine-probe/', import.meta.url))
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: process.env.NOCODE_VERIFY_BROWSER || 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
if (process.env.NOCODE_VERIFY_TOKEN)
  await context.addInitScript(token => {
    sessionStorage.setItem('tinyengine-probe-token', token)
  }, process.env.NOCODE_VERIFY_TOKEN)
const page = await context.newPage()
page.setDefaultTimeout(9000)
page.setDefaultNavigationTimeout(45000)
const errors = [],
  report = { capturedAt: new Date().toISOString(), checks: [], errors }
page.on('pageerror', error => errors.push(error.stack || error.message))
const check = (name, passed, detail) => {
  report.checks.push({ name, passed, detail })
  console.log(JSON.stringify(report.checks.at(-1)))
}
async function attempt(name, action) {
  try {
    await action()
  } catch (error) {
    check(name, false, error.message.slice(0, 600))
  }
}
const schema = () => page.evaluate(() => window.probe.schema())
const find = (node, id) => (node?.id === id ? node : node?.children?.map(child => find(child, id)).find(Boolean))
const parent = (node, id) =>
  node?.children?.some(child => child.id === id)
    ? node.id
    : node?.children?.map(child => parent(child, id)).find(Boolean)
async function picture(target, name) {
  await target.screenshot({ path: `${output}${name}.png`, fullPage: true, animations: 'disabled' })
}
try {
  await page.goto('http://127.0.0.1:5188/')
  await page.waitForFunction(() => window.probe?.canvas().pageState.pageSchema?.children?.length)
  const canvas = page.frame({ url: /canvas.html/ })
  await canvas.getByText('订单经营工作台', { exact: true }).waitFor()
  check(
    'nested_containers',
    await canvas.getByText('form-create 表单', { exact: true }).isVisible(),
    '根卡片、两页签、分栏、业务列表与 form-create 表单在实际画布渲染'
  )
  await canvas.getByRole('button', { name: '刷新订单', exact: true }).click()
  check(
    'design_click_only_selects',
    (await canvas.locator('.os-business-list').getAttribute('data-business-clicks')) === '0',
    '设计态点击业务按钮只选择节点，不执行查询按钮事件'
  )
  await picture(page, '01-canvas')

  await attempt('property_edit_and_undo', async () => {
    await canvas.getByText('订单经营工作台', { exact: true }).click()
    const input = []
    for (const item of await page.locator('input').all())
      if ((await item.inputValue()) === '订单经营工作台') input.push(item)
    expect(input.length).toBeGreaterThan(0)
    await input[0].fill('容器验收草稿')
    await input[0].press('Tab')
    await expect(canvas.getByText('容器验收草稿', { exact: true })).toBeVisible()
    await page.locator('.redo-undo-wrap .undo').click()
    await expect(canvas.getByText('订单经营工作台', { exact: true })).toBeVisible()
    await page.locator('.redo-undo-wrap .redo').click()
    await expect(canvas.getByText('容器验收草稿', { exact: true })).toBeVisible()
    check('property_edit_and_undo', true, '真实属性面板修改标题，第一次修改即可撤销与重做')
  })

  const saved = await schema()
  await page.getByRole('button', { name: '保存验证草稿', exact: true }).click()
  await page.reload()
  await page.waitForFunction(() => window.probe?.canvas().pageState.pageSchema?.children?.length)
  check(
    'save_reload',
    JSON.stringify(await schema()) === JSON.stringify(saved),
    '完整 DSL 保存至独立 localStorage，刷新后逐项对比相同；不代表已经接入正式应用保存 API'
  )
  await page.getByTitle('大纲树', { exact: true }).click()
  await expect(page.locator('.tree-row').filter({ hasText: 'OsBusinessForm' })).toBeVisible()
  check('outline_hierarchy', (await page.locator('.tree-row').count()) === 14, '大纲显示全部 14 个页面及容器、业务节点')
  await attempt('hidden_tab_selection', async () => {
    await page.locator('.tree-row').filter({ hasText: 'OsText' }).click()
    const selected = await page.evaluate(() => window.probe.canvas().getCurrentSchema()?.id)
    const shown = await page
      .frame({ url: /canvas.html/ })
      .getByText('这是第二个页签，验证切换和保存恢复。', { exact: true })
      .isVisible()
    check(
      'hidden_tab_selection',
      selected === 'archived_text' && shown,
      `树选中=${selected}，隐藏页签自动显现=${shown}`
    )
  })
  await picture(page, '02-outline')
  await attempt('tree_reparent', async () => {
    const source = page.locator('.tree-row').filter({ hasText: 'OsBusinessForm' })
    const target = page
      .locator('.tree-row')
      .filter({ hasText: /^OsCard$/ })
      .nth(1)
    await source.dragTo(target)
    await page.waitForTimeout(150)
    const actualParent = parent(await schema(), 'order_form')
    check('tree_reparent', actualParent === 'list_card', `从表单卡片移入列表卡片，实际父节点=${actualParent}`)
  })
  await attempt('leaf_nesting_guard', async () => {
    // 合法换父后大纲可能折叠；重载相同草稿再单独验证叶子限制，避免依赖折叠状态。
    await page.getByRole('button', { name: '保存验证草稿', exact: true }).click()
    await page.reload()
    await page.waitForFunction(() => window.probe?.canvas().pageState.pageSchema?.children?.length)
    await page.getByTitle('大纲树', { exact: true }).click()
    const before = parent(await schema(), 'archived_text')
    await page
      .locator('.tree-row')
      .filter({ hasText: 'OsText' })
      .dragTo(page.locator('.tree-row').filter({ hasText: 'OsBusinessList' }))
    const actualParent = parent(await schema(), 'archived_text')
    check(
      'leaf_nesting_guard',
      actualParent === before,
      `业务列表是叶子，不接受内部节点；前后父节点=${before}/${actualParent}`
    )
  })
  await page.getByRole('button', { name: '保存验证草稿', exact: true }).click()
  const latest = await schema()
  const preview = await context.newPage()
  preview.setDefaultNavigationTimeout(45000)
  preview.on('pageerror', error => errors.push(error.stack || error.message))
  await preview.goto('http://127.0.0.1:5188/preview.html')
  await expect(preview.getByText(find(latest, 'page_root').props.title, { exact: true })).toBeVisible()
  check(
    'independent_runtime',
    !(await preview.getByText('保存验证草稿', { exact: true }).count()),
    '相同 DSL 经 TinyEngine 渲染器独立运行，不装载编辑器外壳'
  )
  await preview.getByRole('tab', { name: '归档订单', exact: true }).click()
  check('runtime_tabs', await preview.getByText('归档页签内容', { exact: true }).isVisible(), '独立运行页签切换正常')
  await preview.getByRole('tab', { name: '全部订单', exact: true }).click()
  await attempt('form_create_validation_submit', async () => {
    await preview.getByRole('button', { name: '验证表单提交', exact: true }).click()
    await expect(preview.getByText('请填写订单名称', { exact: true })).toBeVisible()
    await preview.getByPlaceholder('输入订单名称').fill('TinyEngine 表单兼容验收')
    await preview.getByRole('button', { name: '验证表单提交', exact: true }).click()
    await expect(preview.locator('.form-result')).toContainText('TinyEngine 表单兼容验收')
    check('form_create_validation_submit', true, '复用 form-create 的必填校验和受控提交；不写入业务数据库')
  })
  if (process.env.NOCODE_VERIFY_TOKEN)
    await attempt('existing_runtime_api', async () => {
      await expect(preview.locator('.os-business-list .ant-alert')).toHaveCount(0)
      await expect(preview.locator('.os-business-list small')).toContainText(/已读取 [1-9]/)
      check('existing_runtime_api', true, await preview.locator('.os-business-list small').innerText())
    })
  await picture(preview, '03-runtime')
  const vueErrors = (
    await Promise.all(page.frames().map(frame => frame.evaluate(() => window.probeErrors || [])))
  ).flat()
  vueErrors.push(...(await preview.evaluate(() => window.probeErrors || [])))
  errors.push(...vueErrors)
  check('no_runtime_errors', errors.length === 0, errors.join('; '))
} catch (error) {
  check('verification_completed', false, error.message.slice(0, 700))
  await picture(page, 'failure')
} finally {
  report.passed = report.checks.filter(c => c.passed).length
  report.gaps = report.checks.filter(c => !c.passed).length
  await writeFile(`${output}result.json`, JSON.stringify(report, null, 2))
  await browser.close()
  console.log(JSON.stringify({ passed: report.passed, gaps: report.gaps }))
  process.exitCode = report.gaps ? 1 : 0
}
