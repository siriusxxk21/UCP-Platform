import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实开发环境回归：只新建本次前缀夹具，不更改用户任务；凭据仅保存在内存。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.TASK_CENTER_OUTPUT || '.work/task-launch-entry', ac.prefix)
ac.output = output
const checks = [],
  errors = [],
  tasks = [],
  templates = []
let browser, page, failure
const name = suffix => `${ac.prefix} ${suffix}`
const task = suffix => ({
  id: randomUUID(),
  parentId: null,
  title: name(suffix),
  description: '',
  assigneeId: null,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'T0', fixedStart: null, offsetDays: 0, durationDays: 1 },
  predecessorIds: [],
  binding: null,
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] }
})
const check = async (title, run) => {
  await run()
  checks.push(title)
  console.log('PASS ' + title)
}
async function pageFor(token = ac.tokens.admin) {
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  const tab = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  tab.setDefaultTimeout(20000)
  tab.on('pageerror', error => errors.push(error.message))
  await tab.addInitScript(
    ({ token, info }) => {
      const flatten = (items, parentId = 0) =>
        items.flatMap((menu, index) =>
          menu.visible === false
            ? []
            : [
                {
                  id: menu.id,
                  name: menu.name,
                  path: menu.path || '',
                  component: menu.component,
                  icon: menu.icon,
                  parentId: menu.parentId ?? parentId,
                  sort: menu.sort ?? index
                },
                ...flatten(menu.children || [], menu.id)
              ]
        )
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(flatten(info.menus || [])))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info }
  )
  return tab
}
const launch = () =>
  page.locator('.ant-drawer-content:visible').filter({ has: page.getByPlaceholder('填写可执行的任务名称') })
async function discard() {
  await launch().locator('.ant-drawer-close').click()
  const confirm = page.getByRole('button', { name: '放弃修改', exact: true })
  await expect(confirm).toBeVisible()
  await confirm.click()
  await expect(page.getByPlaceholder('填写可执行的任务名称')).toBeHidden()
}
async function submit() {
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/tasks/create') && r.request().method() === 'POST'
  )
  await launch().getByRole('button', { name: '发起任务', exact: true }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  tasks.push(result.data.task.id)
  await expect(page.getByPlaceholder('填写可执行的任务名称')).toBeHidden()
  return result.data
}
try {
  await mkdir(output, { recursive: true })
  await ac.login()
  for (let i = 0; i < 21; i++) {
    const result = await ac.api('/nocode/tasks/create', { task: task(`分页 ${i}`), requestKey: randomUUID() })
    tasks.push(result.task.id)
  }
  const draft = await ac.api('/nocode/task-templates/save', {
    id: null,
    expectedRevision: null,
    name: name('入口模板'),
    description: '入口回归夹具',
    nodes: [task('模板节点')]
  })
  templates.push(draft.id)
  await ac.api('/nocode/task-templates/publish', { id: draft.id, expectedRevision: draft.revision })
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await pageFor()
  await check('三个子菜单；我的任务无重复发起，管理页原地打开', async () => {
    await page.goto(origin + '/nocode-app/task-center')
    await expect(page.locator('.sidebar-l2 .l2-item')).toHaveText(['我的任务', '任务管理', '任务模板'])
    await expect(page.getByRole('button', { name: '发起任务', exact: true })).toHaveCount(0)
    await page.locator('.sidebar-l2 .l2-item').filter({ hasText: '任务管理' }).click()
    await expect(page).toHaveURL(origin + '/nocode-app/task-center/manage')
    await page.getByRole('button', { name: '发起任务', exact: true }).click()
    await expect(launch()).toBeVisible()
    await expect(page).toHaveURL(origin + '/nocode-app/task-center/manage')
    await expect(launch().getByText('新建任务', { exact: true })).toBeVisible()
    await expect(launch().getByText('从模板发起', { exact: true })).toBeVisible()
    await launch().locator('.ant-drawer-close').click()
    await expect(launch()).toHaveCount(0)
  })
  await check('取消及创建保留搜索、状态、第二页；筛选外新任务可定位', async () => {
    const search = page.getByPlaceholder('搜索任务名称')
    await search.fill(name('分页'))
    const status = page
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^状态$/ }) })
      .first()
    await status.locator('.ant-select').click()
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: /^待处理$/ })
      .click()
    await page.getByRole('button', { name: /查\s*询/ }).click()
    await expect(page.locator('.ant-pagination-item-2')).toBeVisible()
    await page.locator('.ant-pagination-item-2').click()
    await expect(page.locator('.ant-pagination-item-active')).toHaveText('2')
    await expect(page.locator('tr[data-row-key]')).toHaveCount(10)
    const before = await page
      .locator('tr[data-row-key]')
      .evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key')))
    await page.getByRole('button', { name: '发起任务', exact: true }).click()
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('放弃的输入'))
    await launch().locator('.ant-drawer-close').click()
    await page.getByRole('button', { name: '继续编辑', exact: true }).click()
    await expect(launch().getByPlaceholder('填写可执行的任务名称')).toHaveValue(name('放弃的输入'))
    await discard()
    await expect(search).toHaveValue(name('分页'))
    await expect(page.locator('.ant-pagination-item-active')).toHaveText('2')
    assert.deepEqual(
      await page.locator('tr[data-row-key]').evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key'))),
      before
    )
    await page.getByRole('button', { name: '发起任务', exact: true }).click()
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('筛选外新任务'))
    const created = await submit()
    await expect(
      page.locator('.ant-drawer-content:visible').getByText(created.task.title, { exact: true }).first()
    ).toBeVisible()
    await page.locator('.ant-drawer-content:visible .ant-drawer-close').last().click()
    await expect(search).toHaveValue(name('分页'))
    await expect(status).toContainText('待处理')
    await expect(page.locator('.ant-pagination-item-active')).toHaveText('2')
    await page.screenshot({
      path: resolve(output, 'manage-list-preserved.png'),
      fullPage: true,
      animations: 'disabled'
    })
  })
  await check('模板快捷发起保留固定模板与原页面，成功不跳走', async () => {
    await page.goto(origin + '/nocode-app/task-center/templates')
    const search = page.locator('.ant-form-item').filter({ hasText: '模板名称' }).locator('input').first()
    await search.fill(name('入口模板'))
    await page.getByRole('button', { name: /查\s*询/ }).click()
    const row = page.locator('tr[data-row-key]').filter({ hasText: name('入口模板') })
    await row.getByRole('button', { name: '使用模板', exact: true }).click()
    await expect(launch().getByPlaceholder('填写可执行的任务名称')).toHaveValue(name('入口模板'))
    await discard()
    await expect(page).toHaveURL(origin + '/nocode-app/task-center/templates')
    await expect(search).toHaveValue(name('入口模板'))
    await row.getByRole('button', { name: '使用模板', exact: true }).click()
    const created = await submit()
    assert.equal(created.task.templateId, draft.id)
    assert.equal(created.task.templateVersion, 1)
    await expect(page).toHaveURL(origin + '/nocode-app/task-center/templates')
    await expect(search).toHaveValue(name('入口模板'))
    await page.locator('.ant-drawer-content:visible .ant-drawer-close').last().click()
  })
  await check('旧模板发起链接兼容，关闭并刷新不重复打开', async () => {
    await page.goto(origin + `/nocode-app/task-center/launch?templateId=${draft.id}`)
    await expect(page).toHaveURL(origin + '/nocode-app/task-center/manage')
    await expect(launch().getByPlaceholder('填写可执行的任务名称')).toHaveValue(name('入口模板'))
    await page.screenshot({
      path: resolve(output, 'legacy-template-launch.png'),
      fullPage: true,
      animations: 'disabled'
    })
    await discard()
    await page.reload()
    await expect(page.getByPlaceholder('搜索任务名称')).toBeVisible()
    await expect(launch()).toHaveCount(0)
  })
  await check('真实只读角色保留查询但没有发起按钮，旧链接也不打开', async () => {
    const menus = await ac.api('/system/menu/list')
    const mine = menus.find(menu => menu.path === '/nocode-app/task-center')
    const manage = menus.find(menu => menu.path === '/nocode-app/task-center/manage')
    const menuIds = new Set([mine.id, manage.id])
    for (const leaf of [mine, manage]) {
      let parent = leaf.parentId
      while (parent && menus.some(menu => String(menu.id) === String(parent))) {
        const menu = menus.find(menu => String(menu.id) === String(parent))
        menuIds.add(menu.id)
        parent = menu.parentId
      }
    }
    const roleId = await ac.api('/system/role/create', {
      name: name('只读角色'),
      code: ac.prefix + '_readonly',
      sort: 1,
      status: 0,
      dataScope: 1
    })
    ac.owned.roles.push({ id: roleId })
    await ac.api('/system/permission/assign-role-menu', { roleId, menuIds: [...menuIds] })
    const userId = await ac.user('launchreadonly')
    await ac.api('/system/permission/assign-user-role', { userId, roleIds: [roleId] })
    const readonly = await pageFor(ac.tokens[userId])
    await readonly.goto(origin + '/nocode-app/task-center/launch')
    await expect(readonly).toHaveURL(origin + '/nocode-app/task-center/manage')
    await expect(readonly.getByPlaceholder('搜索任务名称')).toBeVisible()
    await expect(readonly.getByRole('button', { name: '发起任务', exact: true })).toHaveCount(0)
    await expect(readonly.getByPlaceholder('填写可执行的任务名称')).toHaveCount(0)
    await ac.denied('/nocode/tasks/create', { task: task('不应创建'), requestKey: randomUUID() }, ac.tokens[userId])
    await readonly.close()
  })
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page)
    await page
      .screenshot({ path: resolve(output, 'failure.png'), fullPage: true, animations: 'disabled' })
      .catch(() => {})
} finally {
  if (browser) await browser.close()
  for (const user of ac.owned.users)
    await ac
      .api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
      .catch(error => errors.push(error.message))
  await ac.persist()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      { checks, errors, tasks, templates, failure: failure?.stack, finishedAt: new Date().toISOString() },
      null,
      2
    )
  )
  console.log(JSON.stringify({ checks: checks.length, output, failure: failure?.message }))
}
if (failure) throw failure
