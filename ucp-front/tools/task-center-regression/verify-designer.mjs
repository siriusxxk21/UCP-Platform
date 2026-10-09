import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 使用当前开发环境，仅新建并登记本次 fa 前缀夹具；不改用户配置。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.TASK_CENTER_OUTPUT || '.work/task-center-designer', ac.prefix)
ac.output = output
const checks = [],
  errors = []
let browser, page, failure
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
try {
  await ac.login()
  await mkdir(output, { recursive: true })
  const work = await ac.object('task_view', '任务视图设计器夹具', [
    ac.field('name', 'TEXT', '任务内容'),
    ac.field('quantity', 'INTEGER', '数量')
  ])
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_task_view',
    name: ac.prefix + ' 任务视图配置验收',
    definition: {
      objects: [{ objectId: work.objectId, versionNo: work.versionNo, checksum: work.checksum }],
      resources: [
        resource('form', 'FORM', '任务业务表单', {
          objectId: work.objectId,
          nodes: work.definition.fields.map(field => ({
            id: 'f_' + field.id,
            type: 'FIELD',
            fieldId: field.id,
            children: []
          })),
          detailIds: []
        }),
        resource('page', 'PAGE', '任务配置页面', {
          nodes: [{ id: 'tasks', type: 'TASKS', resourceId: 'form', text: '设计器任务表', children: [] }]
        }),
        resource('menu', 'MENU', '任务视图', { targetId: 'page' })
      ]
    }
  })
  ac.owned.applications.push({ id: ac.app.application.id, code: ac.app.application.code })
  await ac.share(work, ac.grant(work))
  await ac.persist()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1660, height: 1050 } })
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
        localStorage.setItem(key, JSON.stringify(value))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  await page.goto(origin + '/nocode-app/workspace?id=' + ac.app.application.id)
  async function open() {
    await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
    await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务页面' }).click()
    await page
      .locator('.resources .ant-table-row')
      .filter({ has: page.getByRole('cell', { name: 'page', exact: true }) })
      .getByRole('button', { name: /配置$/ })
      .click()
    await expect(page.getByRole('button', { name: '应用到草稿', exact: true })).toBeEnabled({ timeout: 60000 })
    const canvas = page.frame({ url: /canvas.html/ })
    assert.ok(canvas)
    await canvas.getByText('设计器任务表', { exact: true }).click()
    await expect(page.locator('.task-view-editor')).toBeVisible()
  }
  await open()
  const editor = page.locator('.task-view-editor')
  async function choose(label, text) {
    await editor
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + label + '$') }) })
      .locator('.ant-select')
      .click()
    await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText(text, { exact: true }).click()
    await editor.locator('.ant-form-item-label').first().click()
  }
  await choose('固定任务状态', '进行中')
  await choose('默认排序', '数量')
  await editor.getByLabel('升序', { exact: true }).check()
  await editor
    .locator('.task-view-editor__column')
    .filter({ has: page.getByText('数量', { exact: true }) })
    .getByRole('button', { name: /上\s*移/ })
    .click()
  await editor.getByRole('button', { name: '配置条件', exact: true }).click()
  const dialog = page.locator('.ant-modal-content').filter({ hasText: '高级检索' })
  await dialog.getByRole('button', { name: /添加条件/ }).click()
  await dialog.getByPlaceholder('请输入值', { exact: true }).fill('验收')
  await dialog.getByRole('button', { name: '确认查询', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.screenshot({ path: resolve(output, '01-configured.png'), fullPage: true })
  await page.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(page.locator('.page-designer')).toHaveCount(0)
  const [response] = await Promise.all([
    page.waitForResponse(res => res.url().includes('/nocode/application/save') && res.request().method() === 'POST'),
    page.getByRole('button', { name: /保存草稿$/ }).click()
  ])
  assert.equal((await response.json()).code, 0)
  const saved = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  const taskView = saved.draft.resources.find(value => value.id === 'page').config.nodes[0].taskView
  assert.deepEqual(taskView.taskFilter.statuses, ['RUNNING'])
  assert.deepEqual(taskView.sort, { field: 'business:' + work.ids.quantity, descending: false })
  assert.deepEqual(taskView.columnKeys.slice(0, 3), [
    'title',
    'business:' + work.ids.quantity,
    'business:' + work.ids.name
  ])
  assert.equal(taskView.conditions.items[0].value, '验收')
  checks.push('真实宿主属性→画布PROPS→应用到草稿→服务端保存，固定条件/列顺序/排序完整保留')
  await ac.api('/nocode/application/publish', {
    id: saved.application.id,
    expectedRevision: saved.application.revision,
    reason: '任务配置设计器真实验收'
  })
  const published = await ac.api('/nocode/runtime/application?id=' + saved.application.id)
  const definition = published.definition || published
  const resources = definition.resources || published.resources
  assert.ok(resources, '运行应用返回已发布资源')
  assert.deepEqual(resources.find(value => value.id === 'page').config.nodes[0].taskView, taskView)
  checks.push('发布版本保留相同任务视图配置')
  await page.reload()
  await open()
  await expect(editor.getByText('进行中', { exact: true })).toBeVisible()
  await expect(editor.getByText('已配置', { exact: true })).toBeVisible()
  await expect(editor.getByLabel('升序', { exact: true })).toBeChecked()
  await page.screenshot({ path: resolve(output, '02-reopened.png'), fullPage: true })
  checks.push('刷新后重开设计器，已保存固定筛选和排序仍可继续编辑')
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'task-view.json'), JSON.stringify(taskView, null, 2))
} catch (error) {
  failure = error
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors, failure: failure?.stack }, null, 2))
  await browser?.close()
  console.log(JSON.stringify({ output, checks, errors, failure: failure?.message }))
}
if (failure) throw failure
