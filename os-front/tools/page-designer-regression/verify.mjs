import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/page-designer-drag', String(Date.now()))
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })
page.setDefaultTimeout(10000)
const errors = [],
  checks = [],
  navigations = []
let failure, acceptance
page.on('pageerror', error => errors.push(error.stack || error.message))
page.on('framenavigated', frame => navigations.push(frame.url()))
await page.addInitScript(() => {
  window.designerMessages = []
  window.designerErrors = []
  addEventListener(
    'error',
    event =>
      window.designerErrors.push({ message: event.message, stack: event.error?.stack, target: event.target?.tagName }),
    true
  )
  addEventListener('unhandledrejection', event =>
    window.designerErrors.push({ message: String(event.reason), stack: event.reason?.stack })
  )
  addEventListener('message', event => {
    if (event.data?.channel === 'os-page-designer') window.designerMessages.push(event.data)
  })
})
const nodes = () => page.evaluate(() => window.designerRegression.nodes())
const children = async id => (await nodes()).find(node => node.id === id)?.children.map(node => node.id)
async function healthy() {
  assert.equal(
    await page.evaluate(() => window.designerRegression?.ready() ?? !document.querySelector('.page-designer .loading')),
    true,
    '拖动后仍能应用设计'
  )
  assert.deepEqual(errors, [], '没有未处理异常')
  await expect(page.getByRole('button', { name: '重新加载', exact: true })).toHaveCount(0)
}
// 引擎使用 mousedown/move/up 且要求拖动持续至少 200ms；dragTo 的瞬时移动不能覆盖真实换位。
async function drag(source, target, edge = 'bottom') {
  const a = await source.boundingBox(),
    b = await target.boundingBox()
  assert.ok(a && b, '拖动源与目标可见')
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2)
  await page.mouse.down()
  await page.mouse.move(a.x + a.width / 2 + 8, a.y + a.height / 2 + 8, { steps: 5 })
  await page.waitForTimeout(250)
  await page.mouse.move(
    b.x + b.width / 2,
    b.y + (edge === 'top' ? 2 : edge === 'bottom' ? b.height - 2 : b.height / 2),
    { steps: 25 }
  )
  await page.waitForTimeout(250)
  await page.mouse.up()
  await page.waitForTimeout(250)
  await healthy()
}
try {
  await page.goto(origin + '/tools/page-designer-regression/index.html')
  await page.waitForFunction(() => window.designerRegression?.ready(), {}, { timeout: 60000 })
  const editor = page.frame({ url: /index.html\?type=app/ }),
    canvas = page.frame({ url: /canvas.html/ })
  assert.ok(editor && canvas)
  const initialNavigations = navigations.length
  await expect(canvas.getByText('拖拽文字甲', { exact: true })).toBeVisible()
  assert.equal(await page.evaluate(() => window.designerRegression.dirty()), false)
  await page.screenshot({ path: resolve(output, '01-before.png'), fullPage: true })

  for (let i = 0; i < 3; i++) {
    await drag(canvas.getByText('拖拽文字甲', { exact: true }), canvas.getByText('拖拽文字乙', { exact: true }))
    assert.deepEqual(await children('card-a'), ['text-b', 'text-a'])
    await drag(canvas.getByText('拖拽文字甲', { exact: true }), canvas.getByText('拖拽文字乙', { exact: true }), 'top')
    assert.deepEqual(await children('card-a'), ['text-a', 'text-b'])
  }
  checks.push('真实鼠标连续六次上下换位，顺序正确且无加载遮罩')
  await drag(canvas.getByText('拖拽文字甲', { exact: true }), canvas.getByText('目标标题', { exact: true }))
  await expect.poll(() => children('card-b')).toEqual(['heading', 'text-a'])
  assert.deepEqual(await children('card-a'), ['text-b'])
  assert.equal(await page.evaluate(() => window.designerRegression.dirty()), true)
  checks.push('跨卡片移动保留节点标识与内容，正确标记未保存')

  await editor.locator('.redo-undo-wrap .undo').click()
  await expect.poll(() => children('card-a')).toEqual(['text-a', 'text-b'])
  await editor.locator('.redo-undo-wrap .redo').click()
  await expect.poll(() => children('card-b')).toEqual(['heading', 'text-a'])
  await healthy()
  checks.push('移动后撤销、重做恢复准确层级')

  await canvas.getByText('拖拽文字甲', { exact: true }).click()
  await page.locator('.properties textarea').fill('移动后继续编辑')
  await page.locator('.properties textarea').press('Tab')
  await expect(canvas.getByText('移动后继续编辑', { exact: true })).toBeVisible()
  await healthy()
  checks.push('移动后选择和属性编辑继续可用')

  await editor.getByTitle('大纲树', { exact: true }).click()
  const tree = editor.locator('.tree-row')
  await drag(tree.filter({ hasText: /^OsHeading$/ }), tree.filter({ hasText: /^OsCard$/ }).first(), 'middle')
  await expect.poll(() => children('card-a')).toEqual(['text-b', 'heading'])
  await expect.poll(() => children('card-b')).toEqual(['text-a'])
  checks.push('大纲树跨容器移动正常')

  await page.evaluate(() => window.designerRegression.toggleProperties())
  await page.setViewportSize({ width: 1000, height: 800 })
  await page.evaluate(() => window.designerRegression.toggleFocus())
  await page.waitForTimeout(250)
  await page.evaluate(() => window.designerRegression.toggleFocus())
  await page.setViewportSize({ width: 1500, height: 950 })
  await healthy()
  assert.equal(navigations.length, initialNavigations, '编辑期间宿主及两层 iframe 均未重新导航')
  checks.push('侧栏、专注模式和窗口尺寸切换正常，编辑期间零页面重载')
  await page.screenshot({ path: resolve(output, '02-after.png'), fullPage: true })
  await writeFile(resolve(output, 'nodes.json'), JSON.stringify(await nodes(), null, 2))

  if (process.env.NOCODE_VERIFY_SAVE === '1') {
    const savedNodes = await nodes()
    acceptance = new FormAcceptance(process.env.NOCODE_VERIFY_API)
    acceptance.output = output
    await acceptance.login()
    acceptance.app = await acceptance.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: acceptance.prefix + '_drag',
      name: '页面拖拽回归 ' + acceptance.prefix,
      definition: {
        objects: [],
        resources: [
          {
            id: 'page',
            kind: 'PAGE',
            code: 'page',
            name: '拖拽回归页面',
            config: { nodes: savedNodes, protocolVersion: 2, contextObjectId: null }
          }
        ]
      }
    })
    acceptance.owned.applications.push(acceptance.app.application.id)
    await acceptance.persist()
    const info = await acceptance.api('/system/auth/get-permission-info')
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
      },
      { token: acceptance.tokens.admin, info }
    )
    await page.goto(origin + '/nocode-app/workspace?id=' + acceptance.app.application.id)
    const open = async () => {
      await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
      await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务页面' }).click()
      await page
        .locator('.resources .ant-table-row')
        .filter({ has: page.getByRole('cell', { name: 'page', exact: true }) })
        .getByRole('button', { name: /配置$/ })
        .click()
      await expect(page.getByRole('button', { name: '应用到草稿', exact: true })).toBeEnabled({ timeout: 60000 })
      return page.frame({ url: /canvas.html/ })
    }
    const liveCanvas = await open()
    await drag(
      liveCanvas.getByText('目标标题', { exact: true }),
      liveCanvas.getByText('移动后继续编辑', { exact: true })
    )
    await page.getByRole('button', { name: '应用到草稿', exact: true }).click()
    await expect(page.locator('.page-designer')).toHaveCount(0)
    const [response] = await Promise.all([
      page.waitForResponse(res => res.url().includes('/nocode/application/save') && res.request().method() === 'POST'),
      page.getByRole('button', { name: /保存草稿$/ }).click()
    ])
    assert.equal((await response.json()).code, 0)
    const persisted = await acceptance.api('/nocode/application/get?id=' + acceptance.app.application.id)
    const actual = persisted.draft.resources.find(resource => resource.id === 'page').config.nodes
    assert.deepEqual(
      actual.find(node => node.id === 'card-a').children.map(node => node.id),
      ['text-b']
    )
    assert.deepEqual(
      actual.find(node => node.id === 'card-b').children.map(node => node.id),
      ['text-a', 'heading']
    )
    await page.reload()
    const reopened = await open()
    await expect(reopened.locator('[data-uid="card-b"] [data-uid="heading"]')).toBeVisible()
    await expect(reopened.getByText('移动后继续编辑', { exact: true })).toBeVisible()
    await healthy()
    checks.push('真实应用工作区拖动→应用到草稿→保存→刷新重开，服务端与画布层级一致')
    await page.screenshot({ path: resolve(output, '03-saved-reopened.png'), fullPage: true })
  }
} catch (error) {
  failure = error
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  if (acceptance) {
    await acceptance.persist()
    acceptance.tokens = {}
    acceptance.passwords = {}
  }
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        origin,
        checks,
        errors,
        navigations,
        frameErrors: await Promise.all(
          page
            .frames()
            .map(async frame => ({ url: frame.url(), errors: await frame.evaluate(() => window.designerErrors) }))
        ),
        failure: failure?.stack
      },
      null,
      2
    )
  )
  await browser.close()
  console.log(JSON.stringify({ output, checks, errors, failure: failure?.message }))
}
if (failure) throw failure
