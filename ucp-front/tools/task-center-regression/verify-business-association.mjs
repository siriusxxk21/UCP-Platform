import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 使用真实开发接口和本机浏览器；只创建唯一前缀夹具，凭据不写入报告。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
const expect = playwrightExpect.configure({ timeout: 20000 })
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.TASK_CENTER_OUTPUT || '.work/task-business-association', ac.prefix)
ac.output = output
const checks = [],
  errors = [],
  tasks = [],
  applications = [],
  records = [],
  screenshots = []
let browser, page, failure, object, appA, appB, recordA, recordB
const name = suffix => `${ac.prefix} ${suffix}`
const api = (path, body) => ac.api('/nocode/tasks/' + path, body)
const query = search => ({ scope: 'VISIBLE', tab: 'ALL', search, pageNo: 1, pageSize: 100 })
const pageTasks = (app, recordId = null) =>
  api('page-tasks', {
    applicationId: app.application.id,
    pageId: recordId ? 'record-page' : 'app-page',
    nodeId: recordId ? 'record-tasks' : 'app-tasks',
    recordId,
    query: query(ac.prefix)
  })
const resource = (id, kind, title, config) => ({ id, kind, code: id.replaceAll('-', '_'), name: title, config })
const node = (id, type, resourceId, extra = {}) => ({ id, type, resourceId, children: [], ...extra })
const launch = () =>
  page.locator('.ant-drawer-content:visible').filter({ has: page.getByPlaceholder('填写可执行的任务名称') })

async function persistManifest() {
  await ac.persist()
  await writeFile(
    resolve(output, 'cleanup-manifest.json'),
    JSON.stringify(
      {
        prefix: ac.prefix,
        objects: ac.owned.objects,
        applications,
        tasks,
        records,
        source: 'task-business-association'
      },
      null,
      2
    )
  )
}
async function check(title, run) {
  await run()
  checks.push(title)
  console.log('PASS ' + title)
  await persistManifest()
}
async function screenshot(file) {
  const path = resolve(output, file)
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  screenshots.push(path)
}
async function application(suffix) {
  const app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: `${ac.prefix}_${suffix}`,
    name: name(`施工应用 ${suffix.toUpperCase()}`),
    description: '任务业务关联浏览器验收专用夹具',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        ...['record-form', 'feedback-form'].map(id =>
          resource(id, 'FORM', id === 'record-form' ? '施工业务资料' : '工程进度反馈', {
            objectId: object.objectId,
            nodes: object.definition.fields.map(field =>
              node(`field-${field.id}`, 'FIELD', null, { fieldId: field.id })
            ),
            detailIds: [],
            options: { layout: 'vertical', submitText: id === 'feedback-form' ? '保存反馈记录' : '保存业务资料' }
          })
        ),
        resource('app-page', 'PAGE', '应用任务页面', {
          nodes: [
            node('app-tasks', 'TASKS', null, {
              text: '应用任务',
              taskView: {
                columnKeys: ['title', 'owner', 'status', 'time'],
                sort: { field: 'createdAt', descending: true }
              }
            }),
            node('refresh-tasks', 'BUTTON', null, {
              text: '刷新应用任务',
              action: { kind: 'REFRESH', targetNodeId: 'app-tasks' }
            })
          ]
        }),
        resource('record-page', 'PAGE', '施工记录页面', {
          contextObjectId: object.objectId,
          nodes: [
            node('record-detail', 'DETAIL', 'record-form'),
            node('record-tasks', 'TASKS', 'record-form', { text: '当前施工业务的任务' })
          ]
        }),
        resource('app-menu', 'MENU', '应用任务', { targetId: 'app-page' }),
        resource('record-menu', 'MENU', '施工记录', { targetId: 'record-page' })
      ]
    }
  })
  applications.push({ id: app.application.id, code: app.application.code, name: app.application.name })
  ac.owned.applications.push({ id: app.application.id, code: app.application.code })
  ac.app = app
  await persistManifest()
  await ac.share(object, ac.grant(object))
  await ac.api('/nocode/application/publish', {
    id: app.application.id,
    expectedRevision: app.application.revision,
    reason: '验证无表单应用任务及记录任务边界'
  })
  return app
}
async function openBrowser() {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
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
async function openLaunch(path = '/nocode-app/task-center/manage') {
  await page.goto(origin + path)
  await page.getByRole('button', { name: '发起任务', exact: true }).click()
  await expect(launch()).toBeVisible()
}
async function selectApplication(app) {
  const selection = launch().locator('[aria-label="关联应用"]')
  await selection.click()
  const input = selection.getByRole('combobox')
  if (await input.count()) await input.fill(app.application.name)
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: app.application.name })
    .click()
  await expect(selection).toContainText(app.application.name)
}
async function selectFeedback(app) {
  await launch()
    .locator('.task-entries-editor')
    .getByRole('button', { name: /选择.*反馈/ })
    .click()
  const selector = page.locator('.ant-drawer-content:visible').filter({ has: page.locator('.task-entry-selector') })
  const filter = selector.locator('.ant-select').first().getByRole('combobox')
  await filter.click()
  await filter.fill(app.application.name)
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: app.application.name })
    .click()
  await selector.locator('tr[data-row-key]').filter({ hasText: '工程进度反馈' }).getByRole('checkbox').check()
  await selector.getByRole('button', { name: '确认选择', exact: true }).click()
  await expect(selector).toBeHidden()
}
async function submit() {
  const [response] = await Promise.all([
    page.waitForResponse(
      response => response.url().endsWith('/nocode/tasks/create') && response.request().method() === 'POST'
    ),
    launch().getByRole('button', { name: '发起任务', exact: true }).click()
  ])
  const result = await response.json()
  assert.equal(result.code, 0, result.msg)
  tasks.push(...result.data.nodes.map(item => ({ id: item.id, title: item.title, rootId: item.rootId })))
  await persistManifest()
  await expect(launch()).toHaveCount(0)
  return result.data
}
async function discard() {
  await launch().locator('.ant-drawer-close').click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(launch()).toHaveCount(0)
}
function noBusiness(task) {
  assert.equal(task.binding, null, '只关联应用/记录不能暗中绑定业务表单')
  assert.equal(task.business, null, '只关联应用/记录不能暗中创建业务资料')
}

try {
  await mkdir(output, { recursive: true })
  await ac.login()
  object = await ac.object('association', '任务业务关联夹具', [ac.field('name', 'TEXT', '施工名称')])
  appA = await application('a')
  appB = await application('b')
  ac.app = appA
  recordA = await ac.save(object, { name: name('施工业务甲') })
  recordB = await ac.save(object, { name: name('施工业务乙') })
  records.push(
    ...[recordA, recordB].map(row => ({ applicationId: appA.application.id, objectId: object.objectId, id: row.id }))
  )
  await persistManifest()
  await openBrowser()

  await check('三个清晰分组；取消已选应用和反馈的任务不落库', async () => {
    const before = (await ac.page(object)).total
    await openLaunch()
    for (const title of ['任务基本信息', '业务关联', '过程反馈'])
      await expect(launch().getByRole('heading', { name: new RegExp(`^${title}`) })).toBeVisible()
    await expect(launch().getByRole('radio', { name: '应用表单', exact: true })).toHaveCount(0)
    await expect(launch().getByRole('radio', { name: '办理入口', exact: true })).toHaveCount(0)
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('取消的任务'))
    await selectApplication(appA)
    await selectFeedback(appB)
    await expect(launch().locator('[aria-label="关联应用"]')).toContainText(appA.application.name)
    await launch().getByRole('heading', { name: '任务基本信息', exact: true }).scrollIntoViewIfNeeded()
    await screenshot('launch-three-groups.png')
    const feedbackHeading = launch().getByRole('heading', { name: /^过程反馈/ })
    const scrolled = await feedbackHeading.evaluate(heading => {
      for (let container = heading.parentElement; container; container = container.parentElement) {
        if (
          container.scrollHeight <= container.clientHeight ||
          !/auto|scroll/.test(getComputedStyle(container).overflowY)
        )
          continue
        const before = container.scrollTop
        container.scrollTop +=
          heading.getBoundingClientRect().top - container.getBoundingClientRect().top - container.clientHeight / 3
        return { before, after: container.scrollTop, container: container.className }
      }
      return null
    })
    assert.ok(scrolled && scrolled.after > scrolled.before, '必须滚动真实抽屉容器，不能把相同截图当成反馈区验收')
    await expect(feedbackHeading).toBeInViewport()
    await screenshot('launch-business-feedback.png')
    await discard()
    assert.equal((await api('page', query(name('取消的任务')))).total, 0)
    assert.equal((await ac.page(object)).total, before)
  })

  await check('独立小任务不选应用、业务或反馈即可发起并完成', async () => {
    await openLaunch()
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('独立小任务'))
    const created = await submit()
    assert.equal(created.task.applicationId, null)
    assert.equal(created.task.project, null)
    noBusiness(created.task)
    assert.deepEqual(created.task.entries || [], [])
    for (const action of ['START', 'COMPLETE']) {
      const current = await api('detail', { id: created.task.id })
      await api('transition', {
        id: created.task.id,
        expectedRevision: current.task.revision,
        action,
        requestKey: randomUUID()
      })
    }
    assert.equal((await api('detail', { id: created.task.id })).task.status, 'COMPLETED')
  })

  await check('任务中心只选关联应用即可创建，无业务表单；跨应用反馈不改变归属', async () => {
    await openLaunch()
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('应用挂接及跨应用反馈'))
    await selectApplication(appA)
    await selectFeedback(appB)
    const created = await submit()
    assert.equal(created.task.applicationId, appA.application.id)
    noBusiness(created.task)
    const feedback = await api('entries/list', { id: created.task.id })
    assert.equal(feedback.length, 1)
    assert.equal(feedback[0].config.binding.applicationId, appB.application.id)
    assert.ok((await pageTasks(appA)).list.some(item => item.id === created.task.id))
    assert.ok((await pageTasks(appB)).list.every(item => item.id !== created.task.id))
  })

  await check('无表单 TASKS 在应用页面发起后原位回显，刷新和重载保持归属', async () => {
    const before = (await ac.page(object)).total
    await openLaunch(`/nocode-app/runtime?id=${appA.application.id}&menu=app-menu`)
    await expect(launch()).toContainText(appA.application.name)
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('应用页直接发起'))
    await screenshot('application-page-launch.png')
    const created = await submit()
    assert.equal(created.task.applicationId, appA.application.id)
    noBusiness(created.task)
    const row = page.locator(`tr[data-row-key="${created.task.id}"]`)
    await expect(row).toContainText(name('应用页直接发起'))
    await expect(row).toContainText(appA.application.name)
    await page.getByRole('button', { name: '刷新应用任务', exact: true }).click()
    await expect(row).toBeVisible()
    await page.reload()
    await expect(row).toBeVisible()
    await screenshot('application-tasks.png')
    assert.equal((await ac.page(object)).total, before)
    assert.ok((await pageTasks(appB)).list.every(item => item.id !== created.task.id))
  })

  await check('施工记录页发起自动挂接当前记录，无需重复填写业务资料', async () => {
    const before = (await ac.page(object)).total
    await openLaunch(`/nocode-app/runtime?id=${appA.application.id}&menu=record-menu&recordId=${recordA.id}`)
    await expect(launch()).toContainText(name('施工业务甲'))
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('施工记录分工'))
    await screenshot('record-page-launch.png')
    const created = await submit()
    assert.equal(created.task.applicationId, appA.application.id)
    assert.equal(created.task.project.recordId, recordA.id)
    assert.equal(created.task.project.objectId, object.objectId)
    noBusiness(created.task)
    await expect(page.locator(`tr[data-row-key="${created.task.id}"]`)).toBeVisible()
    await expect(page.locator(`tr[data-row-key="${created.task.id}"]`)).toContainText(appA.application.name)
    assert.ok((await pageTasks(appA, recordA.id)).list.some(item => item.id === created.task.id))
    assert.ok((await pageTasks(appA, recordB.id)).list.every(item => item.id !== created.task.id))
    assert.equal((await ac.page(object)).total, before)
    await screenshot('record-tasks.png')
  })
  await check('统一业务候选使用已有数据发起，主业务与真实过程反馈分别保存', async () => {
    const before = (await ac.page(object)).total
    await openLaunch()
    await launch().getByPlaceholder('填写可执行的任务名称').fill(name('已有业务与过程反馈'))
    await selectApplication(appA)
    await launch().getByText('补充业务数据（按需）', { exact: true }).click()
    await launch().locator('[aria-label="选择业务数据"]').click()
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '施工业务资料 · 表单' })
      .click()
    await launch().getByRole('radio', { name: '使用已有数据', exact: true }).check()
    const existing = launch()
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^已有记录$/ }) })
      .getByRole('combobox')
    await existing.click()
    await existing.fill(name('施工业务甲'))
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: name('施工业务甲') })
      .click()
    await selectFeedback(appB)
    const created = await submit()
    assert.equal(created.task.applicationId, appA.application.id)
    assert.equal(created.task.binding.applicationId, appA.application.id)
    assert.equal(created.task.binding.formId, 'record-form')
    assert.equal(created.task.binding.entryId, null)
    assert.equal(created.task.business.recordId, recordA.id)
    assert.equal((await ac.page(object)).total, before, '使用已有业务数据不能新增重复记录')
    await api('transition', {
      id: created.task.id,
      expectedRevision: created.task.revision,
      action: 'START',
      requestKey: randomUUID()
    })
    await page.goto(origin + `/nocode-app/task-center/manage?taskId=${created.task.id}`)
    await page.getByRole('tab', { name: '业务数据与反馈', exact: true }).click()
    const workspace = page.locator('.task-entry-workspace')
    await workspace.getByRole('button', { name: '新增反馈', exact: true }).click()
    const editor = page
      .locator('.ant-drawer-content:visible')
      .filter({ has: page.getByRole('button', { name: '保存反馈记录', exact: true }) })
    await editor
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^施工名称$/ }) })
      .locator('input')
      .fill(name('真实施工过程反馈'))
    const [response] = await Promise.all([
      page.waitForResponse(
        response => response.url().endsWith('/nocode/tasks/entries/save') && response.request().method() === 'POST'
      ),
      editor.getByRole('button', { name: '保存反馈记录', exact: true }).click()
    ])
    const saved = await response.json()
    assert.equal(saved.code, 0, saved.msg)
    await expect(editor).toBeHidden()
    await expect(workspace.getByText(name('真实施工过程反馈'), { exact: true })).toBeVisible()
    const feedback = await api('entries/list', { id: created.task.id })
    const feedbackRecords = await api('entries/page', {
      taskId: created.task.id,
      entryKey: feedback[0].config.key,
      all: false,
      onlyMine: false,
      pageNo: 1,
      pageSize: 100,
      search: ''
    })
    assert.equal(feedbackRecords.total, 1)
    const feedbackId = feedbackRecords.list[0].record.id
    records.push({ applicationId: appB.application.id, objectId: object.objectId, id: feedbackId })
    await persistManifest()
    assert.notEqual(feedbackId, recordA.id)
    const current = await api('detail', { id: created.task.id })
    assert.equal(current.task.applicationId, appA.application.id)
    assert.equal(current.task.business.recordId, recordA.id)
    assert.equal(feedback[0].config.binding.applicationId, appB.application.id)
    assert.equal((await ac.get(object, recordA)).record.values[object.ids.name], name('施工业务甲'))
    assert.equal((await ac.page(object)).total, before + 1)
    await screenshot('business-and-feedback.png')
  })
  await check('任务类别使用业务记录标签，应用归属与具体业务记录分别筛选', async () => {
    await page.goto(origin + '/nocode-app/task-center/manage')
    await page.getByPlaceholder('搜索任务名称').fill(ac.prefix)
    const category = page
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^类别$/ }) })
      .locator('.ant-select')
    for (const label of ['未关联业务记录', '有关联业务记录']) {
      await category.click()
      await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: label }).click()
      const [response] = await Promise.all([
        page.waitForResponse(
          response => response.url().endsWith('/nocode/tasks/page') && response.request().method() === 'POST'
        ),
        page.getByPlaceholder('搜索任务名称').press('Enter')
      ])
      const result = await response.json()
      assert.equal(result.code, 0, result.msg)
      const titles = result.data.list.map(row => row.title)
      if (label === '未关联业务记录') {
        assert.ok(titles.includes(name('独立小任务')))
        assert.ok(titles.includes(name('应用页直接发起')))
        assert.ok(!titles.includes(name('施工记录分工')))
      } else {
        assert.deepEqual(titles, [name('施工记录分工')])
      }
      await expect(category).toContainText(label)
    }
    await screenshot('record-category-filter.png')
  })
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await screenshot('failure.png').catch(() => {})
    await writeFile(resolve(output, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  if (browser) await browser.close()
  await persistManifest()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        checks,
        errors,
        screenshots,
        tasks,
        applications,
        records,
        failure: failure?.stack,
        finishedAt: new Date().toISOString()
      },
      null,
      2
    )
  )
  ac.tokens = {}
  console.log(JSON.stringify({ checks: checks.length, output, failure: failure?.message }))
}
if (failure) throw failure
