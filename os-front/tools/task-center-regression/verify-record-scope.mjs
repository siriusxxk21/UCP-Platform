import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 新鲜的独立夹具：无任何 FORM 资源，以页面当前对象/记录完成真实任务发起与关联。
// 不读取或改写施工体验应用 4430；不调用不支持任务数据的旧清理器。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.prefix = `rs${Date.now().toString(36)}${randomUUID().slice(0, 5)}`
const output = resolve(process.env.TASK_RECORD_SCOPE_OUTPUT || '.work/task-record-scope', ac.prefix)
ac.output = output
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const expect = playwrightExpect.configure({ timeout: 20000 })
const checks = [],
  screenshots = [],
  errors = [],
  blockedWrites = [],
  requests = [],
  taskIds = []
let browser, page, object, recordA, recordB, created, failure
const title = suffix => `${ac.prefix} ${suffix}`
const node = (id, type, extra = {}) => ({ id, type, children: [], ...extra })
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
const appId = () => ac.app.application.id
const api = (path, body) => ac.api('/nocode/tasks/' + path, body)
const query = { scope: 'MANAGE', tab: 'ALL', pageNo: 1, pageSize: 100, search: ac.prefix }
const context = recordId => ({ applicationId: appId(), pageId: 'record_page', nodeId: 'record_tasks', recordId })
const pageTasks = recordId => api('page-tasks', { ...context(recordId), query })
const application = () => page.locator('.application-runtime')
const taskRow = () => application().locator(`tr[data-row-key="${created.task.id}"]`)
const launch = () =>
  page.locator('.ant-drawer-content:visible').filter({ has: page.getByPlaceholder('填写可执行的任务名称') })

async function persist() {
  await ac.persist()
  await writeFile(
    resolve(output, 'manifest.json'),
    JSON.stringify(
      {
        prefix: ac.prefix,
        source: 'task-record-scope',
        application: ac.app?.application,
        objects: ac.owned.objects,
        records: [recordA, recordB]
          .filter(Boolean)
          .map(row => ({ objectId: object.objectId, id: row.id, name: row.values[object.ids.name] })),
        taskIds,
        retention: '仅保留本清单精确识别的验证夹具；未运行清理，未改写施工体验数据'
      },
      null,
      2
    )
  )
}
async function check(name, run) {
  const detail = await run()
  checks.push({ name, passed: true, detail })
  await persist()
  console.log('PASS ' + name)
}
async function screenshot(name) {
  const path = resolve(output, name + '.png')
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  screenshots.push(path)
}
async function openRecord(record) {
  await page.goto(`${origin}/nocode-app/runtime?id=${appId()}&menu=record_menu&recordId=${record.id}`)
  await expect(application().getByRole('button', { name: '发起任务', exact: true })).toBeVisible()
  await expect(application().locator('.ant-alert-warning,.ant-alert-error')).toHaveCount(0)
}
async function openBrowser() {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  const allowedReads = new Set([
    '/nocode/tasks/page',
    '/nocode/tasks/page-tasks',
    '/nocode/tasks/record-link-candidates',
    '/nocode/runtime/page',
    '/nocode/runtime/selection'
  ])
  await page.route('**/api/**', async route => {
    const request = route.request(),
      path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || allowedReads.has(path)) return route.continue()
    let allowed = false
    const body = request.postDataJSON()
    if (path === '/nocode/tasks/create') {
      allowed =
        body.applicationId === appId() &&
        body.project?.objectId === object.objectId &&
        body.project?.recordId === recordA.id &&
        body.task?.title === title('当前记录无表单任务') &&
        !body.task?.binding &&
        !body.business
    } else if (path === '/nocode/tasks/record-link') {
      allowed =
        body.context?.applicationId === appId() &&
        body.context?.pageId === 'record_page' &&
        body.context?.nodeId === 'record_tasks' &&
        body.context?.recordId === recordB.id &&
        taskIds.includes(body.taskId)
    }
    if (allowed) {
      requests.push({ path, body })
      await route.continue()
    } else {
      blockedWrites.push({ path, method: request.method() })
      await route.abort('blockedbyclient')
    }
  })
  const info = await ac.api('/system/auth/get-permission-info')
  await page.addInitScript(
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
    { token: ac.tokens.admin, info }
  )
}

try {
  await mkdir(output, { recursive: true })
  await ac.login()
  await check('新鲜独立应用无任何表单资源，记录任务区块可保存与发布', async () => {
    object = await ac.object('record', '当前记录任务范围', [ac.field('name', 'TEXT', '记录名称')])
    ac.app = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: ac.prefix + '_app',
      name: title('无表单记录任务'),
      description: title('真实开发环境专用夹具，不属于用户施工体验应用'),
      definition: {
        objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
        resources: [
          resource('record_page', 'PAGE', '当前记录任务页', {
            contextObjectId: object.objectId,
            nodes: [
              node('heading', 'HEADING', { text: '不配置表单的记录任务' }),
              node('record_tasks', 'TASKS', { text: '当前记录关联任务' })
            ]
          }),
          resource('record_menu', 'MENU', '记录任务', { targetId: 'record_page' })
        ]
      }
    })
    assert.notEqual(appId(), '4430')
    ac.owned.applications.push({ id: appId(), code: ac.app.application.code })
    await persist()
    await ac.share(object, ac.grant(object))
    ac.app = await ac.api('/nocode/application/publish', {
      id: appId(),
      expectedRevision: ac.app.application.revision,
      reason: title('验证当前记录任务不需要表单')
    })
    const runtime = await ac.api('/nocode/runtime/application?id=' + appId())
    assert.ok(runtime.definition.resources.every(item => item.kind !== 'FORM'))
    const config = runtime.definition.resources.find(item => item.id === 'record_page').config
    assert.equal(config.contextObjectId, object.objectId)
    assert.ok(!config.nodes.find(item => item.id === 'record_tasks').resourceId)
    recordA = await ac.save(object, { name: title('记录甲') })
    recordB = await ac.save(object, { name: title('记录乙') })
    assert.equal((await pageTasks(recordA.id)).total, 0)
    assert.equal((await pageTasks(recordB.id)).total, 0)
    return { applicationId: appId(), objectId: object.objectId, recordIds: [recordA.id, recordB.id], formResources: 0 }
  })
  await openBrowser()
  await check('真实记录页直接发起，自动传入当前对象与记录，未要求任何业务表单', async () => {
    await openRecord(recordA)
    await application().getByRole('button', { name: '发起任务', exact: true }).click()
    await expect(launch()).toContainText(ac.app.application.name)
    await expect(launch()).toContainText(title('记录甲'))
    await expect(launch()).not.toContainText('任务关联表单')
    await launch().getByPlaceholder('填写可执行的任务名称').fill(title('当前记录无表单任务'))
    await screenshot('launch-with-current-record')
    const [response] = await Promise.all([
      page.waitForResponse(
        response => response.url().endsWith('/nocode/tasks/create') && response.request().method() === 'POST'
      ),
      launch().getByRole('button', { name: '发起任务', exact: true }).click()
    ])
    const result = await response.json()
    assert.equal(result.code, 0, result.msg)
    created = result.data
    taskIds.push(...created.nodes.map(item => item.id))
    await persist()
    assert.equal(created.task.applicationId, appId())
    assert.equal(created.task.project.objectId, object.objectId)
    assert.equal(created.task.project.recordId, recordA.id)
    assert.equal(created.task.binding, null)
    assert.equal(created.task.business, null)
    await expect(launch()).toHaveCount(0)
    await expect(taskRow()).toContainText(title('当前记录无表单任务'))
    await page.reload()
    await expect(taskRow()).toBeVisible()
    await screenshot('record-a-task')
    return { taskId: created.task.id, project: created.task.project, binding: created.task.binding }
  })
  await check('HTTP 当前记录隔离；缺失记录、错误记录与伪节点拒绝而非全量回退', async () => {
    assert.deepEqual(
      (await pageTasks(recordA.id)).list.map(item => item.id),
      [created.task.id]
    )
    assert.equal((await pageTasks(recordB.id)).total, 0)
    const denied = []
    for (const change of [{ recordId: null }, { recordId: '999999999999999' }, { nodeId: 'heading' }]) {
      for (const path of ['page-tasks', 'record-link-candidates']) {
        const result = await ac.request('/nocode/tasks/' + path, { ...context(recordA.id), ...change, query })
        assert.notEqual(result.code, 0, `${path} 应拒绝无效当前记录`)
        assert.ok(result.http < 500, '不能以未处理服务端错误冒充业务拒绝')
        denied.push({ path, change, code: result.code, message: result.msg })
      }
    }
    const detail = await api('detail', { id: created.task.id })
    const invalidLink = await ac.request('/nocode/tasks/record-link', {
      context: context('999999999999999'),
      taskId: created.task.id,
      expectedRevision: detail.task.revision,
      include: true,
      requestKey: randomUUID()
    })
    assert.notEqual(invalidLink.code, 0)
    assert.deepEqual((await api('detail', { id: created.task.id })).links, detail.links)
    return denied
  })
  await check('无表单记录页真实候选选择与关联，保留原项目及业务材料', async () => {
    const candidates = await api('record-link-candidates', { ...context(recordB.id), query })
    assert.ok(candidates.list.some(row => row.id === created.task.id))
    await openRecord(recordB)
    await expect(taskRow()).toHaveCount(0)
    await application().getByRole('button', { name: '关联已有任务', exact: true }).click()
    const picker = page.locator('.ant-drawer-content:visible').filter({ has: page.getByPlaceholder('搜索已有任务') })
    await picker.getByPlaceholder('搜索已有任务').fill(ac.prefix)
    await picker.getByPlaceholder('搜索已有任务').press('Enter')
    const candidate = picker.locator(`tr[data-row-key="${created.task.id}"]`)
    await expect(candidate).toBeVisible()
    await screenshot('record-b-candidate')
    const [response] = await Promise.all([
      page.waitForResponse(
        response => response.url().endsWith('/nocode/tasks/record-link') && response.request().method() === 'POST'
      ),
      candidate.getByRole('button', { name: /关\s*联/ }).click()
    ])
    const result = await response.json()
    assert.equal(result.code, 0, result.msg)
    assert.deepEqual(result.data.task.project, created.task.project)
    assert.equal(result.data.task.binding, null)
    assert.equal(result.data.task.business, null)
    await expect(candidate).toHaveCount(0)
    await picker.locator('.ant-drawer-close').click()
    await expect(taskRow()).toBeVisible()
    assert.ok((await pageTasks(recordB.id)).list.find(item => item.id === created.task.id)?.canUnlink)
    assert.deepEqual(
      (await pageTasks(recordA.id)).list.map(item => item.id),
      [created.task.id]
    )
    await screenshot('record-b-linked')
  })
  await check('解除显式关联不删除任务，也不解除原项目自动关联', async () => {
    await taskRow().getByRole('button', { name: '解除关联', exact: true }).click()
    const modal = page.locator('.ant-modal:visible').filter({ hasText: '解除与当前记录的关联？' })
    const [response] = await Promise.all([
      page.waitForResponse(
        response => response.url().endsWith('/nocode/tasks/record-link') && response.request().method() === 'POST'
      ),
      modal.getByRole('button', { name: '解除关联', exact: true }).click()
    ])
    const result = await response.json()
    assert.equal(result.code, 0, result.msg)
    await expect(taskRow()).toHaveCount(0)
    assert.equal((await pageTasks(recordB.id)).total, 0)
    assert.deepEqual(
      (await pageTasks(recordA.id)).list.map(item => item.id),
      [created.task.id]
    )
    const detail = await api('detail', { id: created.task.id })
    assert.deepEqual(detail.task.project, created.task.project)
    assert.deepEqual(detail.links, [])
    assert.equal((await ac.page(object)).total, 2)
    assert.deepEqual((await ac.get(object, recordA)).record.values, recordA.values)
    assert.deepEqual((await ac.get(object, recordB)).record.values, recordB.values)
    assert.deepEqual(errors, [])
    assert.deepEqual(blockedWrites, [])
    assert.deepEqual(
      requests.map(item => item.path),
      ['/nocode/tasks/create', '/nocode/tasks/record-link', '/nocode/tasks/record-link']
    )
    await screenshot('record-b-unlinked')
    return { retainedTasks: 1, retainedRecords: 2, unrelatedBusinessWrites: 0 }
  })
} catch (error) {
  failure = error.stack || error.message
  if (page) {
    await screenshot('failure').catch(() => {})
    await writeFile(
      resolve(output, 'failure-text.txt'),
      await page
        .locator('body')
        .innerText()
        .catch(() => ''),
      'utf8'
    )
  }
  console.error(failure)
  process.exitCode = 1
} finally {
  await browser?.close()
  await persist()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        prefix: ac.prefix,
        applicationId: ac.app?.application.id,
        checks,
        errors,
        blockedWrites,
        requests,
        screenshots,
        failure
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output, passed: checks.length, failure: !!failure }, null, 2))
}
