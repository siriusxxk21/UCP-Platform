import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, origin, output, settle, geometry, save } from './session.mjs'
import { cleanupFixtures } from './cleanup.mjs'
const { ac, browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(10000)
const prefix = `mob${Date.now().toString(36)}`
const results = [],
  owned = []
const btn = (scope, name) => scope.getByRole('button', { name: new RegExp(name.split('').join('\\s*')) }).first()
const dialog = () => page.locator('.ant-modal:visible,.ant-drawer-content:visible').last()
async function response(path, method, action) {
  const pending = page.waitForResponse(
    r => new URL(r.url()).pathname === '/api' + path && r.request().method() === method,
    { timeout: 15000 }
  )
  pending.catch(() => {})
  await action()
  const body = await (await pending).json()
  assert.equal(body.code, 0, body.msg)
  return body.data
}
async function check(name, work) {
  if (process.env.MOBILE_CASES && !process.env.MOBILE_CASES.includes(name)) return
  try {
    await work()
    const dimensions = await geometry(page)
    assert.equal(dimensions.documentWidth, dimensions.viewport)
    results.push({ name, passed: true, dimensions })
    console.log('PASS', name)
  } catch (error) {
    results.push({
      name,
      passed: false,
      error: error.message,
      text: (await page.locator('body').innerText()).slice(-1800)
    })
    console.log('FAIL', name, error.message.slice(0, 180))
  }
  await page.screenshot({ path: resolve(output, name + '.png') })
  await save('operations.json', { prefix, results, owned })
}
try {
  await check('file-upload', async () => {
    await page.goto(origin + '/system/file')
    await settle(page)
    await btn(page, '上传文件').tap()
    await dialog()
      .locator('input[type=file]')
      .setInputFiles({ name: prefix + '.txt', mimeType: 'text/plain', buffer: Buffer.from('手机适配验收夹具') })
    const file = await response('/infra/file/upload', 'POST', () =>
      dialog()
        .getByRole('button', { name: /^上\s*传$/ })
        .tap()
    )
    const item = { kind: 'file', id: file.id }
    owned.push(item)
    await settle(page)
    await page.getByPlaceholder('请输入文件路径').fill(prefix)
    await btn(page, '查询').tap()
    await settle(page)
    const row = page.locator('tr.ant-table-row').filter({ hasText: prefix })
    assert.equal(await row.count(), 1)
    await btn(row, '删除').tap()
    await response('/infra/file/delete', 'DELETE', () => btn(page.locator('.ant-popover:visible'), '删除').tap())
    item.cleaned = true
  })
  await check('drive-marks-trash', async () => {
    const space = (await ac.api('/drive/space/my-list')).find(s => s.type === 'PERSONAL')
    const id = await ac.api('/drive/entry/create-folder', { spaceId: space.id, parentId: 0, name: prefix })
    const item = { kind: 'entry', id }
    owned.push(item)
    await ac.api('/drive/mark/favorite?entryId=' + id + '&favorite=true', undefined, undefined, 'PUT')
    await ac.api('/drive/mark/access?entryId=' + id, undefined, undefined, 'POST')
    await page.goto(origin + '/drive/recent')
    await settle(page)
    let row = page.locator('tr.ant-table-row').filter({ hasText: prefix })
    assert.equal(await row.count(), 1)
    await btn(row, '打开').tap()
    await settle(page)
    assert.ok(page.url().includes('/drive/my-file'))
    await page.goto(origin + '/drive/favorite')
    await settle(page)
    row = page.locator('tr.ant-table-row').filter({ hasText: prefix })
    await response('/drive/mark/favorite', 'PUT', () => btn(row, '取消收藏').tap())
    await ac.api('/drive/entry/trash', { ids: [id] }, undefined, 'PUT')
    item.trashed = true
    await page.goto(origin + '/drive/trash')
    await settle(page)
    await page.getByPlaceholder('请输入名称').fill(prefix)
    await btn(page, '查询').tap()
    await settle(page)
    row = page.locator('tr.ant-table-row').filter({ hasText: prefix })
    await btn(row, '还原').tap()
    await response('/drive/entry/restore', 'PUT', () => btn(page.locator('.ant-popover:visible'), '确定').tap())
    item.trashed = false
    await ac.api('/drive/entry/trash', { ids: [id] }, undefined, 'PUT')
    item.trashed = true
    await settle(page)
    await btn(page, '查询').tap()
    await settle(page)
    await btn(page.locator('tr.ant-table-row').filter({ hasText: prefix }), '彻底删除').tap()
    await response('/drive/entry/purge', 'DELETE', () => btn(page.locator('.ant-popover:visible'), '确定').tap())
    item.cleaned = true
  })
  await check('task-template', async () => {
    await page.goto(origin + '/nocode-app/task-center/templates')
    await settle(page)
    await btn(page, '新建模板').tap()
    await page.getByRole('textbox', { name: '模板名称', exact: true }).fill(prefix)
    const template = await response('/nocode/task-templates/save', 'POST', () => btn(page, '保存草稿').tap())
    const item = { kind: 'template', id: template.id, name: prefix, requiresDatabaseCleanup: true }
    owned.push(item)
    await settle(page)
    await page.getByRole('button', { name: '返回模板列表', exact: true }).tap()
    await settle(page)
    const row = page.locator('tr.ant-table-row').filter({ hasText: prefix })
    await btn(row, '编辑草稿').tap()
    await settle(page)
    await btn(page, '编辑基本信息').tap()
    assert.equal(await page.getByRole('textbox', { name: '模板名称', exact: true }).inputValue(), prefix)
    await page.getByRole('textbox', { name: '模板名称', exact: true }).fill(prefix + '改')
    await response('/nocode/task-templates/save', 'POST', () => btn(page, '保存草稿').tap())
    item.name = prefix + '改'
    for (const tab of ['业务关联', '过程反馈', '任务实例', '任务编排']) {
      await page.getByRole('tab', { name: tab, exact: true }).tap()
      await settle(page)
      assert.equal(await page.getByRole('tab', { name: tab, exact: true }).getAttribute('aria-selected'), 'true')
    }
    // 模板没有产品删除入口，独立夹具在开发环境中按精确 ID 清理。
    item.requiresDatabaseCleanup = true
  })
} finally {
  for (const item of owned.filter(i => !i.cleaned && i.kind !== 'template'))
    try {
      if (item.kind === 'file') await ac.api('/infra/file/delete?id=' + item.id, undefined, undefined, 'DELETE')
      if (item.kind === 'entry') {
        if (!item.trashed) await ac.api('/drive/entry/trash', { ids: [item.id] }, undefined, 'PUT')
        await ac.api('/drive/entry/purge', { ids: [item.id] }, undefined, 'DELETE')
      }
      item.cleaned = true
    } catch (error) {
      item.cleanupError = error.message
    }
  await save('operations.json', { prefix, results, owned })
  await browser.close()
  if (owned.some(i => i.requiresDatabaseCleanup && !i.cleaned))
    await cleanupFixtures(resolve(output, 'operations.json'))
}
if (results.some(r => !r.passed)) process.exitCode = 1
