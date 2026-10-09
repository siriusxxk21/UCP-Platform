import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { session, origin, output, settle, geometry, save } from './session.mjs'
const { ac, browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(10000)
const prefix = `mob${Date.now().toString(36)}`
const results = [],
  owned = []
const button = (scope, text) => scope.getByRole('button', { name: new RegExp(text.split('').join('\\s*')) }).first()
const content = () => page.locator('.content-wrapper-dual')
const dialog = () => page.locator('.ant-modal:visible, .ant-drawer-content:visible').last()
async function response(path, action, method = 'POST') {
  const promise = page.waitForResponse(
    r => new URL(r.url()).pathname === `/api${path}` && r.request().method() === method,
    { timeout: 14000 }
  )
  promise.catch(() => {})
  await action()
  const body = await (await promise).json()
  assert.equal(body.code, 0, body.msg)
  return body.data
}
async function screenshot(name) {
  await page.screenshot({ path: resolve(output, `${name}.png`) })
  return geometry(page)
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
      text: (await page.locator('body').innerText()).slice(-2200)
    })
    await screenshot(`${name}-failure`)
    console.log('FAIL', name, error.message.slice(0, 150))
  }
  await save('workspaces.json', { prefix, results, owned })
}
try {
  if (!process.env.MOBILE_CASES || process.env.MOBILE_CASES.includes('object'))
    await check('object', async () => {
      const name = prefix + '对象'
      await page.goto(origin + '/nocode/object')
      await settle(page)
      await button(content(), '新建对象').click()
      await page.getByRole('textbox', { name: '对象名称', exact: true }).fill(name)
      await page.getByRole('textbox', { name: '对象编码', exact: true }).fill(prefix + '_object')
      await page.getByRole('textbox', { name: '主表名称', exact: true }).fill('biz_' + prefix + '_object')
      const created = await response('/nocode/design/save', () => button(content(), '保存草稿').click())
      const id = created.draft.id
      owned.push({ kind: 'object', id })
      await settle(page)
      const details = await ac.api(`/nocode/design/get?id=${id}`)
      assert.equal(details.draft.objectName, name)
      await button(content(), '编辑基本信息').click()
      await page.getByRole('textbox', { name: '对象名称', exact: true }).fill(name + '改')
      await response('/nocode/design/save', () => button(content(), '保存草稿').click())
      await settle(page)
      await screenshot('object-editor')
      await page.getByRole('button', { name: '返回对象列表', exact: true }).click()
      await settle(page)
      await page.getByRole('textbox', { name: '查询对象名称', exact: true }).fill(name)
      await button(content(), '查询').click()
      await settle(page)
      const row = page.locator('tr.ant-table-row').filter({ hasText: name + '改' })
      await button(row, '删除').click()
      await settle(page)
      await dialog().locator('textarea').fill('手机适配验收夹具清理')
      await response('/nocode/design/delete', () => button(dialog(), '确认删除对象').click())
      owned.find(i => i.id === id).cleaned = true
    })
  if (!process.env.MOBILE_CASES || process.env.MOBILE_CASES.includes('app'))
    await check('app', async () => {
      const name = prefix + '应用'
      await page.goto(origin + '/nocode-app/application')
      await settle(page)
      await button(content(), '新建应用').click()
      await settle(page)
      await page.getByRole('textbox', { name: '应用名称', exact: true }).fill(name)
      await page.getByRole('textbox', { name: '应用编码', exact: true }).fill(prefix + '_app')
      const created = await response('/nocode/application/save', () => button(dialog(), '确定').click())
      const id = created.application.id
      owned.push({ kind: 'app', id })
      await settle(page)
      await page.waitForTimeout(1200)
      await page.getByRole('tab', { name: '基本设置', exact: true }).tap()
      await page.getByRole('textbox', { name: '应用名称', exact: true }).fill(name + '改')
      await response('/nocode/application/save', () => button(content(), '保存草稿').click())
      assert.equal((await ac.api(`/nocode/application/get?id=${id}`)).application.name, name + '改')
      await screenshot('application-workspace')
      await page.getByRole('button', { name: '返回应用列表', exact: true }).click()
      await settle(page)
      await page.getByRole('textbox', { name: '搜索应用', exact: true }).fill(name)
      await button(content(), '查询').click()
      await settle(page)
      await button(page.locator('tr.ant-table-row').filter({ hasText: name + '改' }), '删除').click()
      await settle(page)
      await page.getByRole('textbox', { name: '删除说明', exact: true }).fill('手机适配验收夹具清理')
      await response('/nocode/application/delete', () => button(dialog(), '移入回收站').click())
      owned.find(i => i.id === id).cleaned = true
    })
  if (!process.env.MOBILE_CASES || process.env.MOBILE_CASES.includes('task'))
    await check('task-draft', async () => {
      const name = prefix + '任务'
      await page.goto(origin + '/nocode-app/task-center/manage')
      await settle(page)
      await button(content(), '新建任务').click()
      await settle(page)
      await page
        .getByRole('button', { name: /^编辑任务名称/ })
        .first()
        .click()
      await page.getByRole('textbox', { name: /^总任务名称/ }).fill(name)
      const created = await response('/nocode/tasks/draft-save', () => button(content(), '保存草稿').click())
      const id = created.id
      owned.push({ kind: 'task-draft', id })
      await screenshot('task-authoring')
      await page.getByRole('button', { name: '返回任务列表', exact: true }).click()
      await settle(page)
      await button(content(), '我的草稿').click()
      await settle(page)
      const row = dialog().locator('tr.ant-table-row').filter({ hasText: name })
      await button(row, '继续编辑').click()
      await settle(page)
      await page
        .getByRole('button', { name: /^编辑任务名称/ })
        .first()
        .click()
      await page.getByRole('textbox', { name: /^总任务名称/ }).fill(name + '改')
      await response('/nocode/tasks/draft-save', () => button(content(), '保存草稿').click())
      await page.getByRole('button', { name: '返回任务列表', exact: true }).click()
      await settle(page)
      await button(content(), '我的草稿').click()
      await settle(page)
      await button(
        dialog()
          .locator('tr.ant-table-row')
          .filter({ hasText: name + '改' }),
        '删除'
      ).click()
      await response('/nocode/tasks/draft-delete', () =>
        dialog().getByRole('button', { name: '删除草稿', exact: true }).click()
      )
      owned.find(i => i.id === id).cleaned = true
    })
} finally {
  for (const item of owned.filter(i => !i.cleaned)) {
    try {
      if (item.kind === 'object') {
        const d = await ac.api(`/nocode/design/get?id=${item.id}`)
        await ac.api('/nocode/design/delete', {
          id: item.id,
          expectedLockVersion: d.draft.lockVersion,
          reason: '手机验收夹具清理'
        })
      }
      if (item.kind === 'app') {
        const d = await ac.api(`/nocode/application/get?id=${item.id}`)
        await ac.api('/nocode/application/delete', {
          id: item.id,
          expectedRevision: d.application.revision,
          reason: '手机验收夹具清理'
        })
      }
      if (item.kind === 'task-draft') {
        const d = await ac.api('/nocode/tasks/draft-get', { id: item.id })
        await ac.api('/nocode/tasks/draft-delete', { id: item.id, expectedRevision: d.revision })
      }
      item.cleaned = true
    } catch (error) {
      item.cleanupError = error.message
    }
  }
  await save('workspaces.json', { prefix, results, owned })
  await browser.close()
}
if (results.some(r => !r.passed) || owned.some(i => !i.cleaned)) process.exitCode = 1
