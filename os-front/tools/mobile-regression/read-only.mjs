import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, origin, output, settle, geometry, save } from './session.mjs'
const { browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(9000)
const results = []
const btn = (scope, name) => scope.getByRole('button', { name: new RegExp(name.split('').join('\\s*')) }).first()
const dialog = () => page.locator('.ant-modal:visible,.ant-drawer-content:visible').last()
async function go(path) {
  await page.goto(origin + path)
  await page.waitForLoadState('networkidle')
  await settle(page)
}
async function check(name, work) {
  if (process.env.MOBILE_CASES && !process.env.MOBILE_CASES.includes(name)) return
  try {
    await work()
    const d = await geometry(page)
    assert.equal(d.documentWidth, d.viewport)
    assert.ok(d.contentScrollWidth <= d.contentWidth + 2)
    results.push({ name, passed: true, ...d })
    console.log('PASS', name)
  } catch (error) {
    results.push({
      name,
      passed: false,
      error: error.message,
      text: (await page.locator('body').innerText()).slice(-1300)
    })
    console.log('FAIL', name, error.message.slice(0, 140))
  }
  await page.screenshot({ path: resolve(output, name + '.png') })
  await save('read-only.json', results)
}
try {
  await check('process-start-form', async () => {
    for (const path of ['/bpm/instance/create', '/bpm/task/start']) {
      await go(path)
      await page.locator('.process-card').first().tap()
      await page.waitForLoadState('networkidle')
      await settle(page)
      assert.equal(await page.locator('.process-card').count(), 0)
      assert.ok((await page.locator('.content-wrapper-dual').innerText()).trim().length > 20)
    }
  })
  await check('touch-import-menu', async () => {
    await go('/system/user')
    await btn(page, '导入').tap()
    await page.getByRole('menuitem', { name: /模板/ }).waitFor({ state: 'visible' })
  })
  await check('process-detail', async () => {
    await go('/bpm/instance/manager')
    await btn(page.locator('tr.ant-table-row').first(), '详情').tap()
    await page.waitForLoadState('networkidle')
    await settle(page)
    assert.ok(page.url().includes('detail'))
    await page.getByText('审批详情', { exact: true }).first().waitFor()
    for (const name of ['流转记录', '审批详情']) {
      const tab = page.getByRole('tab', { name, exact: true })
      if (await tab.count()) await tab.tap()
    }
  })
  await check('data-table-detail', async () => {
    await go('/nocode/table')
    await btn(page.locator('tr.ant-table-row').first(), '结构').tap()
    await settle(page)
    await dialog().waitFor()
    const switcher = dialog().getByRole('button', { name: '切换显示方式', exact: true })
    if (await switcher.count()) {
      await switcher.tap()
      await page.getByRole('menuitem', { name: /抽屉/ }).tap()
      await settle(page)
      await page.locator('.ant-drawer:visible').waitFor()
    }
  })
  await check('message-composer', async () => {
    await go('/message/send')
    await page.getByPlaceholder('请输入消息标题').fill('手机适配验收草稿')
    await page.getByText('用户', { exact: true }).last().tap()
    await btn(page, '添加用户').tap()
    await settle(page)
    await dialog().waitFor()
    await btn(dialog(), '取消').tap()
    await btn(page, '重置').tap()
    assert.equal(await page.getByPlaceholder('请输入消息标题').inputValue(), '')
  })
  await check('feedback-detail-and-draft', async () => {
    await go('/system/feedback')
    await btn(page.locator('tr.ant-table-row').first(), '查看 / 处理').tap()
    await settle(page)
    await dialog().waitFor()
    await btn(dialog(), '关闭').tap()
    await page.getByRole('button', { name: '提交系统反馈', exact: true }).tap()
    await settle(page)
    await page.getByRole('textbox', { name: '反馈标题', exact: true }).fill('手机适配验收草稿')
    await page.getByRole('textbox', { name: '反馈描述', exact: true }).fill('仅验证手机编辑与关闭，不提交工单。')
    await page.getByRole('button', { name: '关闭反馈框并保留草稿', exact: true }).tap()
  })
  await check('profile', async () => {
    await go('/profile')
    const tabs = page.getByRole('tab')
    assert.ok((await tabs.count()) > 0)
    for (let i = 0; i < (await tabs.count()); i++) {
      await tabs.nth(i).tap()
      await settle(page)
      assert.equal(await tabs.nth(i).getAttribute('aria-selected'), 'true')
    }
  })
  await check('business-navigation', async () => {
    await go('/drive/business')
    await page.getByRole('textbox', { name: '搜索应用或业务对象', exact: true }).fill('公司资产管理')
    await page.locator('.business-navigation__label').filter({ hasText: '公司资产管理' }).first().tap()
    await settle(page)
    const leaf = page
      .locator('.business-navigation__node')
      .filter({ has: page.locator('.business-navigation__description') })
      .first()
    if (await leaf.count()) await leaf.tap()
    await btn(page, '收起业务导航').tap()
    await btn(page, '展开业务导航').tap()
    await settle(page)
    for (const name of ['收藏', '最近访问', '浏览']) {
      await page.getByText(name, { exact: true }).last().tap()
      await settle(page)
    }
  })
} finally {
  await browser.close()
}
if (results.some(r => !r.passed)) process.exitCode = 1
