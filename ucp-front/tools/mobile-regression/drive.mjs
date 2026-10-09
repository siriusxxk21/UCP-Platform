import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, origin, output, settle, save } from './session.mjs'
const { ac, browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(10000)
const prefix = `mob${Date.now().toString(36)}`
const results = [],
  owned = []
async function response(path, method, action) {
  const pending = page.waitForResponse(
    r => new URL(r.url()).pathname === '/api' + path && r.request().method() === method,
    { timeout: 14000 }
  )
  pending.catch(() => {})
  await action()
  const body = await (await pending).json()
  assert.equal(body.code, 0, body.msg)
  return body.data
}
async function menu(group, item) {
  await page.getByText(group, { exact: true }).first().tap()
  await page.getByText(item, { exact: true }).last().tap()
}
async function check(name, work) {
  try {
    await work()
    results.push({ name, passed: true })
    console.log('PASS', name)
  } catch (error) {
    results.push({
      name,
      passed: false,
      error: error.message,
      text: (await page.locator('body').innerText()).slice(-1800)
    })
    await page.screenshot({ path: resolve(output, name + '-failure.png') })
    console.log('FAIL', name, error.message.slice(0, 180))
  }
  await save('drive.json', { prefix, results, owned })
}
try {
  for (const route of ['/drive/my-file', '/drive/team'])
    await check(route.split('/').at(-1), async () => {
      let space
      if (route === '/drive/my-file') space = (await ac.api('/drive/space/my-list')).find(s => s.type === 'PERSONAL')
      else {
        const id = await ac.api('/drive/space/create', {
          name: prefix + route.split('/').at(-1),
          quotaBytes: 0
        })
        space = { id }
        owned.push({ kind: 'space', id })
      }
      const name = prefix + route.split('/').at(-1)
      await page.goto(origin + route + '?space=' + space.id)
      await settle(page)
      // VueFinder 的文件与编辑菜单同样可触摸操作，不依赖鼠标右键。
      await menu('文件', '新文件夹')
      await page.locator('.vuefinder__new-folder-modal__input').fill(name)
      const id = await response('/drive/entry/create-folder', 'POST', () =>
        page.locator('.vuefinder__modal-layout__body').getByRole('button', { name: '创建', exact: true }).tap()
      )
      const fixture = { kind: 'entry', id, spaceId: space.id }
      owned.push(fixture)
      await settle(page)
      await page.locator('.vuefinder__explorer__item-name').getByText(name, { exact: true }).tap()
      await menu('编辑', '重命名')
      await page.locator('.vuefinder__rename-modal__input').fill(name + '改')
      await response('/drive/entry/rename', 'PUT', () =>
        page.locator('.vuefinder__modal-layout__body').getByRole('button', { name: '重命名', exact: true }).tap()
      )
      await settle(page)
      assert.equal((await ac.api('/drive/entry/get?id=' + id)).name, name + '改')
      await page.screenshot({ path: resolve(output, route.split('/').at(-1) + '.png') })
      await page
        .locator('.vuefinder__explorer__item-name')
        .getByText(name + '改', { exact: true })
        .tap()
      await menu('编辑', '删除')
      await page.locator('.vuefinder__modal-layout__body input[type="checkbox"]').check()
      await response('/drive/entry/trash', 'PUT', () =>
        page.locator('.vuefinder__modal-layout__body').getByRole('button', { name: '确定，删除！', exact: true }).tap()
      )
      fixture.trashed = true
    })
} finally {
  for (const item of owned.filter(i => i.kind === 'entry')) {
    try {
      if (!item.trashed) await ac.api('/drive/entry/trash', { ids: [item.id] }, undefined, 'PUT')
      await ac.api('/drive/entry/purge', { ids: [item.id] }, undefined, 'DELETE')
      item.cleaned = true
    } catch (error) {
      item.cleanupError = error.message
    }
  }
  for (const item of owned.filter(i => i.kind === 'space')) {
    try {
      await ac.api('/drive/space/delete?id=' + item.id, undefined, undefined, 'DELETE')
      item.cleaned = true
    } catch (error) {
      item.cleanupError = error.message
    }
  }
  await save('drive.json', { prefix, results, owned })
  await browser.close()
}
if (results.some(r => !r.passed) || owned.some(i => !i.cleaned)) process.exitCode = 1
