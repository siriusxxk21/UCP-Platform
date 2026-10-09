import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
// 三种生产加载模式可能同时启动，避免毫秒时间戳相同导致夹具撞名。
ac.prefix += randomBytes(3).toString('hex')
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-designer', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  assets = []
let browser, page, failure, releaseEngine
const fieldsIn = nodes => nodes.flatMap(node => (node.type === 'FIELD' ? [node] : fieldsIn(node.children || [])))
try {
  await ac.login()
  const object = await ac.object('designer', '设计器加载', [
    ac.field('name', 'TEXT', '名称'),
    ac.field('amount', 'INTEGER', '数量')
  ])
  const nodes = [
    {
      id: 'name_node',
      type: 'FIELD',
      fieldId: object.ids.name,
      children: [],
      presentation: { label: '验收名称', placeholder: '填写验收名称', help: '保留说明' }
    },
    { id: 'amount_node', type: 'FIELD', fieldId: object.ids.amount, children: [] }
  ]
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_designer',
    name: '设计器验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        {
          id: 'form',
          kind: 'FORM',
          code: 'form',
          name: '验收表单',
          config: {
            objectId: object.objectId,
            nodes,
            detailIds: [],
            detailNodes: {},
            relatedForms: [],
            options: { layout: 'vertical', submitText: '保存记录' }
          }
        }
      ]
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  const originalServerNodes = structuredClone(
    ac.app.draft.resources.find(resource => resource.id === 'form').config.nodes
  )
  const originalRevision = ac.app.application.revision
  await ac.persist()
  await ac.share(object, ac.grant(object))
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    const url = new URL(request.url())
    if (url.pathname.endsWith('.js')) assets.push(url.pathname)
  })
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
    { token: ac.tokens.admin, info }
  )
  await page.goto(origin + '/nocode-app/workspace?id=' + ac.app.application.id)
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务表单' }).click()
  const row = page
    .locator('.resources .ant-table-row')
    .filter({ has: page.getByRole('cell', { name: 'form', exact: true }) })
  await expect(row).toBeVisible()
  const beforeDesigner = [...new Set(assets)]
  const loadMode = process.env.NOCODE_VERIFY_DESIGNER_LOAD || 'normal'
  assert.ok(['normal', 'delay', 'failure'].includes(loadMode), '未知设计器加载验证模式')
  let intercepted = false
  if (loadMode !== 'normal') {
    const resumed = new Promise(resolveLoad => {
      releaseEngine = resolveLoad
    })
    await page.route(
      /\/assets\/index\.es-[^/]+\.js$/,
      async route => {
        intercepted = true
        if (loadMode === 'failure') await route.abort('failed')
        else {
          await resumed
          await route.continue()
        }
      },
      { times: 1 }
    )
  }
  await row.getByRole('button', { name: /配置$/ }).click()
  const dialog = page.locator('.nocode-form-workspace .ant-modal-content')
  const engine = dialog.locator('._fc-m-drag')
  if (loadMode !== 'normal') {
    await expect.poll(() => intercepted).toBe(true)
    await expect(dialog.getByRole('button', { name: '应用到草稿', exact: true })).toBeDisabled()
    if (loadMode === 'failure') {
      await expect(page.getByRole('button', { name: '稍后处理', exact: true })).toBeVisible()
      await page.getByRole('button', { name: '稍后处理', exact: true }).click()
      await expect(dialog.locator('.designer-engine .ant-alert-error')).toBeVisible()
    }
    await dialog.getByRole('textbox', { name: '资源名称', exact: true }).fill('加载期间的编辑')
    await dialog.locator('.ant-modal-close').click()
    await page.getByRole('button', { name: '继续设计', exact: true }).click()
    await expect(dialog.getByRole('textbox', { name: '资源名称', exact: true })).toHaveValue('加载期间的编辑')
    const unchanged = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
    assert.deepEqual(
      unchanged.draft.resources.find(resource => resource.id === 'form').config.nodes,
      originalServerNodes
    )
    assert.equal(unchanged.application.revision, originalRevision)
    await page.screenshot({ path: resolve(output, `00-${loadMode}.png`), fullPage: true, animations: 'disabled' })
    if (loadMode === 'failure') {
      await dialog.locator('.ant-modal-close').click()
      await page.getByRole('button', { name: '放弃修改', exact: true }).click()
      await page.reload()
      await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
      await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务表单' }).click()
      await row.getByRole('button', { name: /配置$/ }).click()
    } else releaseEngine()
    checks.push(
      loadMode === 'failure'
        ? '真实引擎分块失败禁止应用，局部编辑可继续/确认放弃，刷新重开保留原服务端设计'
        : '真实引擎分块延迟时禁止应用，关闭提示可继续保留编辑；服务端节点未被清空'
    )
  }
  await expect(engine).toBeVisible()
  await expect(engine.locator('.ant-form-item')).toHaveCount(2)
  await expect(engine.getByText('验收名称', { exact: true })).toBeVisible()
  await expect(engine.getByText('保留说明', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: /预览$/ })).toBeEnabled()
  checks.push('真实设计引擎载入原字段、稳定节点标识对应的标签与说明，初始化后可预览')
  const afterDesigner = [...new Set(assets)]
  await dialog.getByRole('button', { name: '快捷排版', exact: true }).hover()
  await page.getByRole('menuitem', { name: '双列', exact: true }).click()
  await expect
    .poll(async () => {
      const boxes = await engine
        .locator('.ant-form-item')
        .evaluateAll(items =>
          items.map(item => ({ x: item.getBoundingClientRect().x, y: item.getBoundingClientRect().y }))
        )
      return boxes.length === 2 && Math.abs(boxes[0].y - boxes[1].y) < 1 && boxes[1].x > boxes[0].x
    })
    .toBe(true)
  await dialog.getByRole('button', { name: /预览$/ }).click()
  const preview = page.locator('.nocode-form-preview .ant-modal-content')
  await preview.getByPlaceholder('填写验收名称', { exact: true }).fill('预览填写')
  await preview.getByRole('button', { name: '校验预览', exact: true }).click()
  await expect(preview.locator('.ant-alert-success')).toContainText('校验通过')
  await page.screenshot({ path: resolve(output, '01-preview.png'), fullPage: true, animations: 'disabled' })
  await preview.locator('.ant-modal-close').click()
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog).toBeHidden()
  const save = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/save') && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await save).json()).code, 0)
  ac.app = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  if (loadMode === 'delay')
    assert.equal(ac.app.draft.resources.find(resource => resource.id === 'form').name, '加载期间的编辑')
  const saved = fieldsIn(ac.app.draft.resources.find(resource => resource.id === 'form').config.nodes)
  assert.deepEqual(
    saved.map(node => [node.id, node.fieldId]),
    nodes.map(node => [node.id, node.fieldId])
  )
  assert.equal(saved[0].presentation.label, '验收名称')
  assert.equal(saved[0].presentation.placeholder, '填写验收名称')
  assert.equal(saved[0].presentation.help, '保留说明')
  checks.push('真实引擎快捷双列、填写预览校验、应用与保存回读保留字段 ID、节点 ID 和呈现设置')
  await page.reload()
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务表单' }).click()
  await row.getByRole('button', { name: /配置$/ }).click()
  await expect(engine.locator('.ant-form-item')).toHaveCount(2)
  await expect(engine.getByText('验收名称', { exact: true })).toBeVisible()
  await dialog.locator('.ant-modal-close').click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('放弃尚未应用到草稿的修改？', { exact: true })).toBeHidden()
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '设计器保存链路验收'
  })
  assert.equal(fieldsIn(ac.app.draft.resources.find(resource => resource.id === 'form').config.nodes).length, 2)
  checks.push('刷新重开真实画布保留设计，未改动关闭不误报脏状态，最终发布成功')
  assert.deepEqual(errors, [])
  await writeFile(
    resolve(output, 'assets.json'),
    JSON.stringify(
      { beforeDesigner, afterDesigner, added: afterDesigner.filter(asset => !beforeDesigner.includes(asset)) },
      null,
      2
    )
  )
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  releaseEngine?.()
  await browser?.close()
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
