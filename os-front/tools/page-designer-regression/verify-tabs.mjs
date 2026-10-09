import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'

// 页签容器只能直接放页签（业务方 2026-10-04：画布上把页签容器拖进另一个页签容器、与页签并排，保存时才被后端拒）。
// 用真实宿主、构建好的编辑器与画布、真实鼠标拖动；夹具见 main.ts 的 ?fixture=tabs。默认模式不访问业务接口。
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/page-designer-tabs', String(Date.now()))
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })
page.setDefaultTimeout(10000)
const errors = [],
  checks = []
let failure
page.on('pageerror', error => errors.push(error.stack || error.message))
const nodes = () => page.evaluate(() => window.designerRegression.nodes())
const find = (list, id) => {
  for (const node of list) {
    if (node.id === id) return node
    const found = find(node.children || [], id)
    if (found) return found
  }
}
const children = async id => (id ? find(await nodes(), id)?.children.map(n => n.id) : (await nodes()).map(n => n.id))
/** 引擎要求拖动持续至少 200ms；终点给到具体坐标（页签之间的空隙、页签内的空白处）。 */
async function drag(source, target) {
  const a = await source.boundingBox()
  assert.ok(a, '拖动源可见')
  await page.mouse.move(a.x + 40, a.y + 10)
  await page.mouse.down()
  await page.mouse.move(a.x + 48, a.y + 18, { steps: 5 })
  await page.waitForTimeout(250)
  await page.mouse.move(target.x, target.y, { steps: 25 })
  await page.waitForTimeout(250)
  await page.mouse.up()
  await page.waitForTimeout(400)
  assert.deepEqual(errors, [], '没有未处理异常')
}
const warning = () => page.locator('.ant-message-notice').last()
try {
  await page.goto(origin + '/tools/page-designer-regression/index.html?fixture=tabs')
  await page.waitForFunction(() => window.designerRegression?.ready(), {}, { timeout: 90000 })
  const canvas = page.frame({ url: /canvas.html/ })
  assert.ok(canvas)
  const box = async uid => (await canvas.locator(`[data-uid="${uid}"]`).boundingBox())
  const tab1 = await box('tab-1'),
    tab2 = await box('tab-2')
  // 页签容器「民宿页签」里两个页签之间的空隙：悬停在页签容器上，引擎会改指向最后一个页签并按上方 / 下方放置。
  const gap = { x: tab1.x + tab1.width / 2, y: (tab1.y + tab1.height + tab2.y) / 2 }
  await page.screenshot({ path: resolve(output, '01-before.png'), fullPage: true })
  const before = JSON.stringify(await nodes())

  await drag(canvas.locator('[data-uid="tabs-b"]'), gap)
  await page.screenshot({ path: resolve(output, '02-tabs-into-tabs.png'), fullPage: true })
  assert.deepEqual(await children('tabs-a'), ['tab-1', 'tab-2'], '页签容器没有被放进页签容器')
  assert.deepEqual(await children(), ['tabs-a', 'tabs-b', 'card-a'])
  await expect(warning()).toContainText('「页签容器」不能直接放进「页签容器」：页签容器里只能放页签，请拖进某个页签里')
  assert.equal(await page.evaluate(() => window.designerRegression.dirty()), false, '被撤回后没有未保存修改')
  checks.push('页签容器拖到另一个页签容器的页签之间：撤回并提示')

  await drag(canvas.locator('[data-uid="loose-text"]'), gap)
  assert.deepEqual(await children('tabs-a'), ['tab-1', 'tab-2'])
  assert.deepEqual(await children('card-a'), ['loose-text'])
  await expect(warning()).toContainText('「说明文字」不能直接放进「页签容器」')
  checks.push('普通组件拖到页签之间：撤回并提示')

  const card = await box('card-a')
  await drag(canvas.locator('[data-uid="tab-3"]'), { x: card.x + card.width / 2, y: card.y + card.height - 30 })
  assert.deepEqual(await children('tabs-b'), ['tab-3'])
  await expect(warning()).toContainText('「页签」只能放在「页签容器」里')
  checks.push('页签拖出页签容器：撤回并提示')
  assert.equal(JSON.stringify(await nodes()), before, '三次被挡后结构与初始完全一致')

  const heading = await box('tab-2-heading')
  await drag(canvas.locator('[data-uid="loose-text"]'), { x: heading.x + heading.width / 2, y: heading.y + 3 })
  await page.screenshot({ path: resolve(output, '03-into-tab.png'), fullPage: true })
  assert.deepEqual(await children('tab-2'), ['loose-text', 'tab-2-heading'], '拖进某个页签里可以')
  assert.deepEqual(await children('card-a'), [])
  assert.equal(await page.evaluate(() => window.designerRegression.dirty()), true)
  checks.push('拖进某个页签里：正常放下，标记未保存')
  await writeFile(resolve(output, 'nodes.json'), JSON.stringify(await nodes(), null, 2))
} catch (error) {
  failure = error
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ ok: !failure, checks, errors, failure: failure && String(failure.stack || failure) }, null, 2)
  )
  await browser.close()
}
if (failure) throw failure
console.log(JSON.stringify({ ok: true, output, checks }, null, 2))
