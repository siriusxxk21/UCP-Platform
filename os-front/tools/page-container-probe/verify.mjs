import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

// 复跑时先启动本目录 Vite。使用独立浏览器上下文及模拟数据，不读取用户浏览器会话。
const output = fileURLToPath(new URL('../../../os-server/os-nocode/.work/page-container-probe/', import.meta.url))
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'msedge' })
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
page.setDefaultTimeout(7000)
const errors = []
page.on('pageerror', error => errors.push(error.message))
const report = { checks: [], errors }
async function read() {
  return page.locator('body').innerText()
}
async function record(name, passed, detail) {
  report.checks.push({ name, passed, detail })
  console.log(JSON.stringify(report.checks.at(-1)))
}
async function shot(name) {
  await page.screenshot({ path: `${output}${name}.png`, fullPage: true })
}
async function pointerClick(locator) {
  await locator.scrollIntoViewIfNeeded()
  const b = await locator.boundingBox()
  await page.mouse.click(b.x + b.width / 2, b.y + b.height / 2)
}
try {
  await page.goto('http://127.0.0.1:5187/')
  await page.getByText('跨页签节点保留：true', { exact: false }).waitFor()
  await record(
    'nested_canvas',
    await page.getByText('华东实业集团', { exact: true }).first().isVisible(),
    '根卡片、页签、栅格和业务列表/表单嵌套'
  )
  await shot('01-canvas')
  await pointerClick(page.getByRole('button', { name: '新增公司', exact: true }).first())
  await record(
    'design_click_only_selects',
    (await read()).includes('当前选择：list_all；业务按钮执行：0'),
    await page.locator('.status').innerText()
  )
  await page.getByText('大纲', { exact: true }).click()
  const tree = page.locator('._fd-tree-node__content')
  await tree.first().waitFor()
  await tree.last().click()
  await record(
    'hidden_tab_tree_selection',
    (await read()).includes('当前选择：list_archived'),
    await page.locator('.status').innerText()
  )
  await record(
    'hidden_tab_canvas_revealed',
    (await page.getByRole('tab', { name: '停用公司', exact: true }).getAttribute('aria-selected')) === 'true',
    '结构树选择隐藏页签中的列表后，画布是否自动切换'
  )
  await shot('02-hidden-selection')
  await page.getByRole('tab', { name: '停用公司', exact: true }).click()
  await record('tab_switch', await page.getByText('停用列表容器', { exact: true }).isVisible(), '直接点击页签可切换')
  await page.getByRole('tab', { name: '全部公司', exact: true }).click()
  await pointerClick(page.getByText('公司列表容器', { exact: true }))
  await page.getByRole('textbox', { name: '容器标题', exact: true }).fill('列表容器已修改')
  await page.getByRole('textbox', { name: '容器标题', exact: true }).press('Tab')
  await page.getByText('列表容器已修改', { exact: true }).waitFor()
  await record('container_property', true, '修改容器标题后画布同步')
  await page.getByRole('button', { name: '保存验证配置', exact: true }).click()
  await expect(page.locator('.status')).toContainText('验证配置已保存到本地')
  await expect(page.locator('pre')).toContainText('列表容器已修改')
  const before = JSON.parse(await page.locator('pre').textContent())
  await page.reload()
  await page.getByText('跨页签节点保留：true', { exact: false }).waitFor()
  await page.getByRole('button', { name: '重载验证配置', exact: true }).click()
  await page.getByText('保存恢复：稳定结构一致', { exact: true }).waitFor()
  await record('reload_roundtrip', true, `重新加载浏览器后恢复保存配置；${before.length} 个根节点`)
  await page.getByRole('button', { name: '运行预览', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByText('华东实业集团', { exact: true }).first().waitFor()
  await dialog.getByRole('button', { name: '新增公司', exact: true }).first().click()
  await record('runtime_interaction', (await dialog.innerText()).includes('业务按钮执行：1'), '运行态业务按钮执行一次')
  await dialog.getByRole('tab', { name: '停用公司', exact: true }).click()
  await record('runtime_tabs', await dialog.getByText('停用列表容器', { exact: true }).isVisible(), '运行态页签可切换')
  await shot('03-runtime')
  await dialog.getByRole('button', { name: 'Close', exact: true }).click()
  // 独立验证结构树拖动：配置要求列表只能放卡片，目标却选择栅格列。
  await page.reload()
  await page.getByText('跨页签节点保留：true', { exact: false }).waitFor()
  await page.getByText('大纲', { exact: true }).click()
  await tree.first().waitFor()
  await page.waitForTimeout(200) // 第三方结构树在挂载后 60 ms 才初始化 Sortable。
  const sourceBox = await tree.nth(6).boundingBox(),
    targetBox = await tree.nth(7).boundingBox()
  await page.mouse.move(sourceBox.x + sourceBox.width - 50, sourceBox.y + sourceBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(sourceBox.x + sourceBox.width - 45, sourceBox.y + sourceBox.height / 2 + 5, { steps: 5 })
  await page.waitForTimeout(120)
  await page.mouse.move(targetBox.x + targetBox.width - 50, targetBox.y + targetBox.height / 2, { steps: 15 })
  await page.waitForTimeout(150)
  await shot('04-tree-drag-in-progress')
  await page.mouse.up()
  await page.waitForTimeout(200)
  await page.getByRole('button', { name: '检查结构', exact: true }).click()
  await expect(page.locator('pre')).toContainText('page_root')
  const moved = JSON.parse(await page.locator('pre').textContent())
  function findParent(rules, target, parent) {
    for (const rule of rules) {
      if (rule.name === target) return parent
      const found = findParent(rule.children || [], target, rule.name)
      if (found) return found
    }
  }
  const parent = findParent(moved, 'list_all')
  await record(
    'tree_drop_constraint',
    parent === 'list_card',
    `列表父节点=${parent}；${await page.locator('.status').innerText()}`
  )
  await shot('04-tree-drag')
  const undo = page.locator('._fc-m-tools .icon-pre-step')
  const undoClass = await undo.getAttribute('class')
  await undo.click()
  await page.waitForTimeout(350)
  await page.getByRole('button', { name: '检查结构', exact: true }).click()
  await page.waitForTimeout(50)
  await expect(page.locator('pre')).toContainText('page_root')
  const undoParent = findParent(JSON.parse(await page.locator('pre').textContent()), 'list_all')
  await record('undo_tree_move', undoParent === 'list_card', `撤销后父节点=${undoParent}；撤销按钮类=${undoClass}`)
  // 用纯公开配置重新加载固定 activeKey，复现设计态页签键与业务键不一致。
  await page.goto('http://127.0.0.1:5187/?fixedTab=1')
  await page.getByText('跨页签节点保留：true', { exact: false }).waitFor()
  const selectedTabs = await page
    .getByRole('tab')
    .evaluateAll(items => items.filter(x => x.getAttribute('aria-selected') === 'true').map(x => x.textContent))
  await record(
    'stable_active_tab_key',
    selectedTabs.includes('全部公司'),
    `业务 activeKey=all；选中页签=${JSON.stringify(selectedTabs)}`
  )
  await shot('05-stable-tab-key')
} catch (e) {
  report.failure = e.message
  await shot('failure')
  console.log('FAILURE', e.message)
} finally {
  report.summary = {
    supported: report.checks.filter(x => x.passed).length,
    gaps: report.checks.filter(x => !x.passed).length
  }
  await writeFile(`${output}report.json`, JSON.stringify(report, null, 2))
  console.log('SUMMARY', JSON.stringify(report.summary))
  await browser.close()
}
if (report.failure || errors.length) process.exitCode = 1
