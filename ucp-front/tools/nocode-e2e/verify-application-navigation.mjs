import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只建立并清理本轮登记的夹具；通过真实草稿、发布接口及浏览器验证页面导航。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix = `nav${Date.now().toString(36)}${randomBytes(2).toString('hex')}`
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/application-navigation-e2e', ac.prefix)
ac.output = output
ac.owned.menus = []
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  cleanup = []
let browser, page, failure, directoryId
const directoryName = '导航验收 ' + ac.prefix
const resource = (id, kind, name, config) => ({ id, code: id, kind, name, config })
const node = (id, type, text, extra = {}) => ({ id, type, text, children: [], ...extra })
const allMenus = () => ac.api('/system/menu/list')
const appMenus = async () =>
  (await allMenus()).filter(menu => menu.componentName?.startsWith(`NocodePage_${ac.app.application.id}_`))
const detail = async () => (ac.app = await ac.api('/nocode/application/get?id=' + ac.app.application.id))
const workspace = () => origin + '/nocode-app/workspace?id=' + ac.app.application.id
const runtime = suffix => origin + '/nocode-app/runtime?id=' + ac.app.application.id + suffix
async function enterWorkspace() {
  await page.goto(workspace())
  await page.getByRole('tab', { name: '页面与导航', exact: true }).click()
}
async function openSettings(name) {
  const row = page.locator('.resources .ant-table-row').filter({ has: page.getByRole('cell', { name, exact: true }) })
  await row.getByRole('button', { name: '菜单设置', exact: true }).click()
  const drawer = page.locator('.ant-drawer-content').filter({ hasText: '页面菜单设置' })
  await expect(drawer).toBeVisible()
  return drawer
}
async function saveDraft() {
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/application/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await response).json()).code, 0)
  await detail()
}
async function publish() {
  await page.getByRole('button', { name: /^发布应用$/ }).click()
  const dialog = page
    .locator('.ant-modal-content')
    .filter({ has: page.getByRole('textbox', { name: '发布说明', exact: true }) })
  await dialog.getByRole('textbox', { name: '发布说明', exact: true }).fill('页面与导航真实浏览器验收')
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/application/publish') && r.request().method() === 'POST'
  )
  await dialog.getByRole('button', { name: /^发布应用$/ }).click()
  assert.equal((await (await response).json()).code, 0)
  await expect(dialog).toBeHidden()
  await detail()
}
try {
  await ac.login()
  directoryId = String(
    await ac.api('/system/menu/create', {
      name: directoryName,
      parentId: '0',
      type: 1,
      sort: 9998,
      path: '/navigation-' + ac.prefix,
      icon: 'AppstoreOutlined',
      status: 0,
      visible: true,
      keepAlive: true,
      alwaysShow: true
    })
  )
  ac.owned.menus.push(directoryId)
  await ac.persist()
  const object = await ac.object('orders', '导航采购订单', [
    ac.field('name', 'TEXT', '订单名称'),
    ac.field('amount', 'INTEGER', '数量')
  ])
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_app',
    name: '采购管理 · 导航验收',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource('home', 'PAGE', '采购工作台', {
          protocolVersion: 2,
          filters: [],
          nodes: [
            node('title', 'HEADING', '采购工作台'),
            node('welcome', 'TEXT', '通过页面组织工作台内容，快捷入口直接打开业务页面。'),
            node('orders_button', 'BUTTON', '查看采购订单', { action: { kind: 'NAVIGATE', resourceId: 'orders' } }),
            node('guide_button', 'BUTTON', '查看采购指南', { action: { kind: 'NAVIGATE', resourceId: 'guide' } })
          ]
        }),
        resource('orders', 'VIEW', '采购订单', {
          objectId: object.objectId,
          fieldIds: [object.ids.name, object.ids.amount],
          pageSize: 20
        }),
        resource('guide', 'PAGE', '采购指南', {
          protocolVersion: 2,
          filters: [],
          nodes: [node('guide_heading', 'HEADING', '采购说明与流程')]
        }),
        resource('legacy_home', 'MENU', '采购工作台', { targetId: 'home' }),
        resource('legacy_orders', 'MENU', '采购订单', { targetId: 'orders' })
      ]
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(object, ac.grant(object))
  // 初次通过 API 发布旧配置，用于确认旧入口迁移不会破坏运行内容。
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '导航迁移验收初始版本'
  })
  await ac.save(object, { name: '采购验收订单 A', amount: 3 })
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      for (const [key, value] of Object.entries({
        userInfo: info.user,
        permissions: info.permissions,
        roles: info.roles,
        menus: info.menus
      }))
        if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify(value))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  await enterWorkspace()
  await expect(page.getByRole('tab', { name: '平台菜单入口', exact: true })).toHaveCount(0)
  await expect(page.getByRole('complementary', { name: '导航预览' })).toContainText('待设置平台目录')
  for (const [name, home, sort] of [
    ['采购工作台', true, 10],
    ['采购订单', false, 20]
  ]) {
    const drawer = await openSettings(name)
    const visibility = drawer.getByRole('switch', { name: '显示在平台菜单', exact: true })
    if ((await visibility.getAttribute('aria-checked')) !== 'true') await visibility.click()
    await drawer.locator('[aria-label="平台一级目录"] .ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible').getByText(directoryName, { exact: true }).click()
    await drawer.getByRole('spinbutton', { name: '菜单排序', exact: true }).fill(String(sort))
    if (home) await drawer.getByRole('switch', { name: '设为应用首页', exact: true }).click()
    if (!home)
      await page.screenshot({
        path: resolve(output, '02-page-menu-settings.png'),
        fullPage: true,
        animations: 'disabled'
      })
    await drawer.getByRole('button', { name: '保存设置', exact: true }).click()
    await expect(drawer).toBeHidden()
  }
  await expect(page.getByRole('complementary', { name: '导航预览' })).toContainText(directoryName)
  await expect(page.getByRole('complementary', { name: '导航预览' })).toContainText('未显示在菜单')
  await page.screenshot({
    path: resolve(output, '01-page-navigation-workspace.png'),
    fullPage: true,
    animations: 'disabled'
  })
  assert.equal((await appMenus()).length, 0)
  await saveDraft()
  assert.equal((await appMenus()).length, 0)
  assert.equal(ac.app.draft.resources.find(r => r.id === 'legacy_home').config.navigationVersion, 2)
  checks.push('页面设置复用旧 MENU 标识；设置及保存草稿均不提前改变系统菜单')
  await publish()
  const publishedMenus = await appMenus()
  assert.equal(publishedMenus.length, 2)
  assert.ok(publishedMenus.every(menu => String(menu.parentId) === directoryId && menu.visible))
  assert.ok(publishedMenus.some(menu => menu.path.endsWith('&page=orders')))
  checks.push('真实发布原子生成指定一级目录下的两个二级菜单')

  // 使用真实接口刷新权限菜单；不在浏览器中伪造测试菜单。
  const latestInfo = await ac.api('/system/auth/get-permission-info')
  await page.evaluate(info => localStorage.setItem('menus', JSON.stringify(info.menus)), latestInfo)
  // 初始脚本只用于登录，新页面继续使用实际发布后的菜单。
  await page.goto(runtime(''))
  await expect(page).toHaveURL(new RegExp('page=home'))
  await expect(page.getByText('通过页面组织工作台内容，快捷入口直接打开业务页面。', { exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '应用内导航', exact: true })).toHaveCount(0)
  await page.screenshot({ path: resolve(output, '03-published-home.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '查看采购订单', exact: true }).click()
  await expect(page).toHaveURL(new RegExp('page=orders'))
  await expect(page.getByRole('cell', { name: '采购验收订单 A', exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(output, '04-published-orders.png'), fullPage: true, animations: 'disabled' })
  checks.push('从应用默认进入组合首页，页面按钮直接打开发布的数据视图并显示真实记录')

  await page.goto(runtime('&menu=legacy_orders'))
  await expect(page.getByRole('cell', { name: '采购验收订单 A', exact: true })).toBeVisible()
  await page.goto(runtime('&page=guide'))
  await expect(page.getByText('采购说明与流程', { exact: true })).toBeVisible()
  await page.goto(runtime('&page=removed'))
  await expect(page.getByText('此页面已移除或当前无权访问，请从左侧菜单选择其他页面', { exact: true })).toBeVisible()
  checks.push('旧 MENU 深链继续可用；隐藏页可由页面链接打开；失效页显示明确空态')

  await enterWorkspace()
  let drawer = await openSettings('采购订单')
  await drawer.getByRole('switch', { name: '显示在平台菜单', exact: true }).click()
  await drawer.getByRole('button', { name: '保存设置', exact: true }).click()
  await saveDraft()
  assert.equal((await appMenus()).find(menu => menu.name === '采购订单').visible, true)
  await publish()
  const hidden = (await appMenus()).find(menu => menu.name === '采购订单')
  assert.equal(hidden.visible, false)
  assert.equal(String(hidden.id), String(publishedMenus.find(menu => menu.name === '采购订单').id))
  drawer = await openSettings('采购订单')
  await drawer.getByRole('switch', { name: '显示在平台菜单', exact: true }).click()
  await drawer.getByRole('button', { name: '保存设置', exact: true }).click()
  await saveDraft()
  await publish()
  const restored = (await appMenus()).find(menu => menu.name === '采购订单')
  assert.equal(restored.visible, true)
  assert.equal(String(restored.id), String(hidden.id))
  checks.push('隐藏/重新显示均在发布后生效，复用原系统菜单 ID')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  // 只回收本轮应用、菜单和对象，不按名称模糊搜索用户资源。
  if (ac.app) {
    try {
      await detail()
      assert.equal(ac.app.application.code, ac.prefix + '_app')
      await ac.api('/nocode/application/delete', {
        id: ac.app.application.id,
        expectedRevision: ac.app.application.revision,
        reason: '页面导航验收结束回收专用应用'
      })
      cleanup.push('已回收本轮验收应用')
      for (const menu of await appMenus()) {
        await ac.api('/system/menu/delete?id=' + menu.id, undefined, undefined, 'DELETE')
      }
    } catch (error) {
      cleanup.push('应用/页面菜单清理：' + error.message)
    }
  }
  if (directoryId) {
    try {
      const current = await ac.api('/system/menu/get?id=' + directoryId)
      assert.equal(current.name, directoryName)
      await ac.api('/system/menu/delete?id=' + directoryId, undefined, undefined, 'DELETE')
      cleanup.push('已删除本轮验收菜单目录')
    } catch (error) {
      cleanup.push('菜单目录清理：' + error.message)
    }
  }
  for (const owned of ac.owned.objects) {
    try {
      const design = await ac.api('/nocode/design/get?id=' + owned.id)
      assert.equal(design.draft.objectCode, ac.prefix + '_orders')
      await ac.api('/nocode/design/delete', {
        id: owned.id,
        expectedLockVersion: design.draft.lockVersion,
        reason: '页面导航验收结束回收专用对象'
      })
      cleanup.push('已回收本轮验收对象')
    } catch (error) {
      cleanup.push('对象清理：' + error.message)
    }
  }
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ checks, errors, cleanup, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, cleanup, failure: failure?.message }))
}
if (failure) throw failure
