import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { session, origin, output, settle, geometry, save } from './session.mjs'
const { browser } = await session()
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
const page = await context.newPage()
const results = []
try {
  for (const [width, height] of [
    [320, 568],
    [390, 844],
    [844, 390],
    [375, 420]
  ]) {
    await page.setViewportSize({ width, height })
    await page.goto(origin + '/login')
    await page.getByPlaceholder('请输入用户名').waitFor()
    const login = page.locator('.login-btn')
    await login.scrollIntoViewIfNeeded()
    assert.ok(await login.isVisible())
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width)
    const rect = await login.boundingBox()
    assert.ok(rect.x >= 0 && rect.x + rect.width <= width && rect.y >= 0 && rect.y + rect.height <= height)
    await page.screenshot({ path: resolve(output, `login-${width}-${height}.png`) })
    results.push({ name: `登录布局 ${width}×${height}`, passed: true })
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.locator('.login-btn').click()
  assert.ok((await page.locator('.ant-form-item-explain-error').count()) > 0)
  const env = Object.fromEntries(
    (await readFile('.env.test', 'utf8'))
      .split(/\r?\n/)
      .filter(l => /^E2E_(USERNAME|PASSWORD)=/.test(l))
      .map(l => {
        const i = l.indexOf('=')
        return [
          l.slice(0, i),
          l
            .slice(i + 1)
            .trim()
            .replace(/^['"]|['"]$/g, '')
        ]
      })
  )
  await page.getByPlaceholder('请输入用户名').fill(env.E2E_USERNAME)
  await page.getByPlaceholder('请输入密码').fill(env.E2E_PASSWORD)
  await page.locator('.login-btn').click()
  await page.waitForURL('**/dashboard')
  await settle(page)
  const reminder = page.locator('.ant-modal-confirm:visible').filter({ hasText: '密码即将过期' })
  if (await reminder.count()) await reminder.getByRole('button', { name: /暂\s*不/ }).tap()
  results.push({ name: '手机表单必填校验与真实登录', passed: true })
  for (const [group, menu, path] of [
    ['系统', '用户管理', '/system/user'],
    ['数据中心', '数据对象', '/nocode/object'],
    ['应用中心', '应用管理', '/nocode-app/application']
  ]) {
    await page.getByRole('button', { name: '打开导航菜单', exact: true }).tap()
    await page.locator('.mobile-menu-groups').getByRole('button', { name: group, exact: true }).tap()
    await page.screenshot({ path: resolve(output, `navigation-${menu}.png`) })
    await page.locator('.mobile-menu-pages').getByRole('button', { name: menu, exact: true }).tap()
    await page.waitForURL(origin + path)
    await page.locator('.mobile-menu').waitFor({ state: 'hidden' })
    await settle(page)
    assert.equal((await geometry(page)).contentScrollWidth, 390)
    results.push({ name: `触屏导航 ${group}/${menu}`, passed: true })
  }
  await page.getByRole('button', { name: '账号菜单', exact: true }).tap()
  await page.getByText('个人中心', { exact: true }).last().waitFor({ state: 'visible' })
  results.push({ name: '触屏账号菜单', passed: true })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(origin + '/system/user')
  await settle(page)
  assert.equal(await page.getByRole('button', { name: '打开导航菜单' }).count(), 0)
  assert.ok(await page.locator('.sidebar-l2').isVisible())
  await page.screenshot({ path: resolve(output, 'desktop-user.png') })
  results.push({ name: '桌面导航布局回归', passed: true })
} catch (error) {
  results.push({ passed: false, error: error.message })
  await page.screenshot({ path: resolve(output, 'shell-failure.png') })
  console.error(error.message)
  process.exitCode = 1
} finally {
  await save('shell.json', results)
  await browser.close()
}
console.log(JSON.stringify(results))
