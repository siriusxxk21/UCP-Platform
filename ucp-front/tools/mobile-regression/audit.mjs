import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, output, origin, settle, geometry, save } from './session.mjs'
const { menus, browser, context } = await session()
const inventory = menus.filter(m => m.component && m.path && !m.path.includes(':') && m.type !== 3 && m.menuType !== 2)
const unique = [...new Map(inventory.map(m => [m.path, m])).values()].filter(
  m => !process.env.MOBILE_PATHS || process.env.MOBILE_PATHS.split(',').includes(m.path)
)
await save(
  'menus.json',
  unique.map(({ name, path, component, id }) => ({ name, path, component, id }))
)
console.log(JSON.stringify(unique.map(m => ({ name: m.name, path: m.path }))))
const page = await context.newPage()
page.setDefaultTimeout(12000)
const results = []
const apiFailures = []
page.on('response', async r => {
  if (!r.url().includes('/api/')) return
  try {
    const b = await r.json()
    if (b.code !== undefined && b.code !== 0)
      apiFailures.push({ path: new URL(r.url()).pathname, code: b.code, message: b.msg })
  } catch {}
})
try {
  for (const m of unique) {
    const errors = []
    const handle = e => errors.push(e.message)
    page.on('pageerror', handle)
    try {
      await page.goto(origin + (m.path.startsWith('/') ? m.path : '/' + m.path), { waitUntil: 'domcontentloaded' })
      await page.waitForLoadState('networkidle', { timeout: 20000 })
      await settle(page)
      const query = page
        .locator('.content-wrapper-dual')
        .getByRole('button', { name: /查\s*询$/ })
        .first()
      const refresh = page
        .locator('.content-wrapper-dual')
        .getByRole('button', { name: /刷\s*新$/ })
        .first()
      let operation = '页面读取'
      if (await query.isVisible()) {
        await query.tap()
        operation = '查询'
      } else if (await refresh.isVisible()) {
        await refresh.tap()
        operation = '刷新'
      }
      await page.waitForLoadState('networkidle', { timeout: 20000 })
      await settle(page)
      const dimensions = await geometry(page)
      assert.equal(dimensions.documentWidth, dimensions.viewport, '页面横向溢出')
      assert.ok(dimensions.contentScrollWidth <= dimensions.contentWidth + 2, '内容横向溢出')
      const text = (await page.locator('.content-wrapper-dual').innerText()).slice(0, 350)
      await page.screenshot({ path: resolve(output, `page-${m.id}.png`), animations: 'disabled' })
      results.push({ name: m.name, path: m.path, id: m.id, operation, ...dimensions, text, errors })
      console.log(m.name, JSON.stringify(dimensions))
    } catch (e) {
      results.push({ name: m.name, path: m.path, error: e.message, errors })
      console.log('FAIL', m.name, e.message.slice(0, 100))
    }
    page.off('pageerror', handle)
    await save('audit.json', { pages: results, apiFailures })
  }
} finally {
  await browser.close()
}
if (apiFailures.length || results.some(row => row.error || row.errors.length)) process.exitCode = 1
