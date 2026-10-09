import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

// 独立夹具验证模板固定资源与实例归属；不修改施工／采购体验应用或用户模板。
const ac = new TaskDataPolicyAcceptance()
const resume = process.env.TASK_TEMPLATE_CONTEXT_RESUME
if (resume) assert.match(resume, /^tc[a-z0-9]+$/)
ac.prefix = resume || `tc${Date.now().toString(36)}`
ac.output = resolve('.work/template-context', ac.prefix)
const expect = playwrightExpect.configure({ timeout: 20000 })
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const errors = [],
  blockedWrites = [],
  failures = [],
  checks = [],
  drafts = []
let browser, page, published, template, secondRecord, failure
const launch = () => page.locator('.ant-drawer-content:visible').filter({ has: page.locator('.task-launch') })
const templateRow = () => page.locator('tr[data-row-key]').filter({ hasText: template.name })
const resource = (id, kind, name, config) => ({ id, code: id, kind, name, config })
const context = recordId => ({
  applicationId: ac.applicationId,
  pageId: 'record_page',
  nodeId: 'record_tasks',
  recordId
})
const query = { scope: 'VISIBLE', tab: 'ALL', pageNo: 1, pageSize: 100 }
async function check(name, run) {
  await run()
  checks.push(name)
  console.log('PASS ' + name)
}
async function screenshot(name) {
  const path = resolve(ac.output, name + '.png')
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  ac.screenshots.push(path)
}
async function expand(scope, name) {
  const header = scope.locator('.ant-collapse-header').filter({ hasText: name }).first()
  if ((await header.getAttribute('aria-expanded')) !== 'true') await header.click()
}
async function select(control, label) {
  await control.click()
  const input = control.getByRole('combobox')
  if (await input.count()) await input.fill(label)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: label }).first().click()
}
async function closeLaunch() {
  await launch().locator('.ant-drawer-close').click()
  const discard = page.getByRole('button', { name: '放弃修改', exact: true })
  if (await discard.isVisible()) await discard.click()
  await expect(page.locator('.task-launch')).toHaveCount(0)
}
async function openTemplateList() {
  await page.goto(origin + '/nocode-app/task-center/templates')
  const search = page.locator('.ant-form-item').filter({ hasText: '模板名称' }).getByRole('textbox')
  await search.fill(template.name)
  await page.getByRole('button', { name: /查\s*询/, exact: true }).click()
  await expect(page.locator('tr[data-row-key]').filter({ hasText: template.name })).toHaveCount(1)
}
function fixed(body) {
  assert.equal(body.templateId, template.id)
  assert.equal(body.templateVersion, published.version)
  for (const key of ['binding', 'entries', 'dataPolicy']) assert.deepEqual(body.task[key], published.task[key])
}
async function prepareFixtures() {
  await ac.prepareApplication()
  const current = await ac.api('/nocode/application/get?id=' + ac.applicationId)
  const changed = await ac.api('/nocode/application/save', {
    id: ac.applicationId,
    expectedRevision: current.application.revision,
    code: current.application.code,
    name: current.application.name,
    description: current.application.description,
    definition: {
      ...current.draft,
      resources: [
        ...current.draft.resources,
        resource('record_page', 'PAGE', '记录任务', {
          contextObjectId: ac.business.objectId,
          nodes: [{ id: 'record_tasks', type: 'TASKS', text: '本记录任务', children: [] }]
        }),
        resource('record_menu', 'MENU', '记录任务', { targetId: 'record_page' })
      ]
    }
  })
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.applicationId,
    expectedRevision: changed.application.revision,
    reason: ac.title('模板归属验证')
  })
  secondRecord = await ac.save(ac.business, { name: ac.title('另一个业务记录'), quantity: 2 })
  await ac.rememberRecord(ac.business, secondRecord)
  const root = ac.node('本次模板工作', {
    binding: ac.binding('business'),
    dataPolicy: { version: 1, business: 'ALL', feedback: 'GROUP' },
    entries: [
      {
        key: 'feedback',
        name: '施工日志',
        binding: ac.binding('feedback'),
        dataMode: 'ROOT_SHARED',
        sourceNodeId: null,
        sourceEntryKey: null,
        readableFieldIds: null,
        writableFieldIds: null,
        required: true,
        allowAll: false
      }
    ]
  })
  template = await ac.api('/nocode/task-templates/save', {
    id: null,
    expectedRevision: null,
    name: ac.title('归属验证模板'),
    description: '独立模板归属回归夹具',
    task: root,
    nodes: []
  })
  ac.templates.push({ id: template.id, name: template.name })
  await ac.persist()
  published = await ac.api('/nocode/task-templates/publish', { id: template.id, expectedRevision: template.revision })
}
async function restoreFixtures() {
  const manifest = JSON.parse(await readFile(resolve(ac.output, 'manifest.json'), 'utf8'))
  assert.equal(manifest.prefix, ac.prefix)
  for (const key of ['objects', 'applications'])
    assert.ok(manifest[key].every(item => item.code.startsWith(ac.prefix + '_')))
  assert.ok(manifest.templates.every(item => item.name.startsWith(ac.prefix + ' ')))
  for (const key of ['objects', 'applications', 'users', 'roles', 'departments']) ac.owned[key] = manifest[key]
  ac.records = manifest.records
  ac.templates = manifest.templates
  ac.taskIds = manifest.taskIds
  ac.app = await ac.api('/nocode/application/get?id=' + manifest.applications[0].id)
  for (const key of ['business', 'feedback']) {
    const object = manifest.objects.find(item => item.code === `${ac.prefix}_${key}`)
    ac[key] = await ac.api('/nocode/application/object-version?id=' + object.id)
    ac[key].ids = Object.fromEntries(ac[key].definition.fields.map(field => [field.code, field.id]))
  }
  const rows = (await ac.page(ac.business)).list
  ac.existingBusiness = rows.find(row => row.values[ac.business.ids.name] === ac.title('任务前业务'))
  secondRecord = rows.find(row => row.values[ac.business.ids.name] === ac.title('另一个业务记录'))
  template = (await ac.api('/nocode/task-templates/list')).find(item => item.id === manifest.templates[0].id)
  assert.ok(ac.existingBusiness && secondRecord && template)
  published = await ac.api(`/nocode/task-templates/version?id=${template.id}&version=${template.publishedVersion}`)
}
async function prepare() {
  await ac.login()
  if (resume) await restoreFixtures()
  else await prepareFixtures()
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', async response => {
    if (!response.url().includes('/api/')) return
    const data = await response.json().catch(() => null)
    if (response.status() >= 400 || (data && data.code !== undefined && data.code !== 0))
      failures.push({ path: new URL(response.url()).pathname, status: response.status(), message: data?.msg })
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
  await page.route('**/api/**', async route => {
    const request = route.request(),
      path = new URL(request.url()).pathname
    if (
      request.method() !== 'GET' &&
      /\/(create|save|publish|delete|transition|draft-save|draft-publish)$/.test(path)
    ) {
      const body = request.postDataJSON()
      const command = path.endsWith('/draft-save') ? body.content : body
      const allowed =
        ['/api/nocode/tasks/create', '/api/nocode/tasks/draft-save'].includes(path) &&
        command.task?.title?.startsWith(ac.prefix) &&
        command.templateId === template.id
      if (!allowed) {
        blockedWrites.push(path)
        return route.abort()
      }
      fixed(command)
    }
    await route.continue()
  })
}

try {
  await prepare()
  await check('模板编辑明确具体记录在使用时关联，查看取消不改变模板', async () => {
    await openTemplateList()
    await templateRow().getByRole('button', { name: '编辑草稿', exact: true }).click()
    const editor = page.locator('.ant-drawer-content:visible')
    await expand(editor, '业务关联')
    await expect(editor.getByText('配置可复用的业务表单与数据范围；具体项目或订单在使用模板时关联。')).toBeVisible()
    await expect(editor.getByText('关联应用或具体业务；独立任务可以留空。')).toHaveCount(0)
    await screenshot('template-edit')
    await editor.getByRole('button', { name: /取\s*消/, exact: true }).click()
    await expect(editor).toBeHidden()
  })
  await check('从模板列表发起可手选应用和已有记录，宽窄布局正常并真实创建', async () => {
    await templateRow().getByRole('button', { name: '使用模板', exact: true }).click()
    await expand(launch(), '业务关联')
    await select(launch().locator('[aria-label="关联应用"]'), ac.app.application.name)
    await expand(launch(), '关联具体业务记录')
    await select(launch().locator('.task-record-picker .ant-select').first(), ac.business.definition.objectName)
    await select(launch().locator('.task-record-picker .ant-select').last(), ac.title('任务前业务'))
    await expect(launch().getByRole('button', { name: '更换业务表单', exact: true })).toHaveCount(0)
    for (const width of [1512, 1100]) {
      await page.setViewportSize({ width, height: width === 1512 ? 1050 : 760 })
      await expect
        .poll(async () => {
          const box = await launch().boundingBox()
          return !!box && box.x >= -1 && box.x + box.width <= width + 1
        })
        .toBe(true)
      await screenshot('launch-record-' + width)
    }
    const responsePromise = page.waitForResponse(response => response.url().endsWith('/nocode/tasks/create'))
    await launch().getByRole('button', { name: '加入任务池', exact: true }).click()
    const response = await responsePromise,
      body = response.request().postDataJSON(),
      result = await response.json()
    assert.equal(result.code, 0, result.msg)
    await ac.remember(result.data)
    fixed(body)
    assert.equal(body.applicationId, ac.applicationId)
    assert.equal(body.project.recordId, ac.existingBusiness.id)
    const task = result.data.task
    assert.equal(task.project.recordId, ac.existingBusiness.id)
    assert.equal(task.status, 'PENDING')
    const selected = await ac.taskApi('page-tasks', { ...context(ac.existingBusiness.id), query })
    const other = await ac.taskApi('page-tasks', { ...context(secondRecord.id), query })
    assert.ok(selected.list.some(row => row.id === task.id))
    assert.ok(other.list.every(row => row.id !== task.id))
  })
  await check('业务记录页使用同一模板保留自动关联且不能改为其他记录', async () => {
    await page.goto(`${origin}/nocode-app/runtime?id=${ac.applicationId}&menu=record_menu&recordId=${secondRecord.id}`)
    await page.getByRole('button', { name: '新建任务', exact: true }).click()
    await launch().getByText('从模板新建', { exact: true }).click()
    await select(
      launch().locator('.ant-form-item').filter({ hasText: '已发布模板' }).locator('.ant-select'),
      template.name
    )
    await expand(launch(), '业务关联')
    await expect(launch().getByText(ac.title('另一个业务记录'), { exact: true })).toBeVisible()
    await expect(launch().locator('[aria-label="关联应用"]')).toHaveCount(0)
    await expect(launch().locator('.task-record-picker')).toHaveCount(0)
    await screenshot('record-page-template')
    await closeLaunch()
  })
  await check('模板发起草稿保存并刷新恢复具体记录与固定配置', async () => {
    await openTemplateList()
    await templateRow().getByRole('button', { name: '使用模板', exact: true }).click()
    await expand(launch(), '业务关联')
    await select(launch().locator('[aria-label="关联应用"]'), ac.app.application.name)
    await expand(launch(), '关联具体业务记录')
    await select(launch().locator('.task-record-picker .ant-select').first(), ac.business.definition.objectName)
    await select(launch().locator('.task-record-picker .ant-select').last(), ac.title('任务前业务'))
    const responsePromise = page.waitForResponse(response => response.url().endsWith('/nocode/tasks/draft-save'))
    await launch().getByRole('button', { name: '保存草稿', exact: true }).click()
    const response = await responsePromise,
      result = await response.json()
    assert.equal(result.code, 0, result.msg)
    drafts.push(result.data.id)
    const stored = await ac.taskApi('draft-get', { id: result.data.id })
    fixed(stored.content)
    assert.equal(stored.content.project.recordId, ac.existingBusiness.id)
    await page.goto(`${origin}/nocode-app/task-center/manage`)
    await page.getByRole('button', { name: '我的草稿', exact: true }).click()
    await page.locator(`tr[data-row-key="${stored.id}"]`).getByRole('button', { name: '继续编辑', exact: true }).click()
    await expand(launch(), '业务关联')
    await expect(launch().getByText(ac.title('任务前业务'), { exact: true })).toBeVisible()
    await screenshot('restored-draft')
    await closeLaunch()
  })
  await check('错误记录与篡改模板授权被服务端拒绝，原模板及业务记录不变', async () => {
    const create = extra => ({
      task: { ...published.task, id: randomUUID() },
      templateId: template.id,
      templateVersion: published.version,
      applicationId: ac.applicationId,
      requestKey: randomUUID(),
      ...extra
    })
    const invalidRecord = await ac.taskRequest(
      'create',
      create({
        project: {
          applicationId: ac.applicationId,
          objectId: ac.business.objectId,
          recordId: '999999999999',
          label: '不存在'
        }
      })
    )
    assert.notEqual(invalidRecord.code, 0)
    const invalidPolicy = await ac.taskRequest(
      'create',
      create({
        task: {
          ...published.task,
          id: randomUUID(),
          dataPolicy: { version: 1, business: 'ALL', feedback: 'ALL' }
        }
      })
    )
    assert.notEqual(invalidPolicy.code, 0)
    assert.deepEqual(
      await ac.api(`/nocode/task-templates/version?id=${template.id}&version=${published.version}`),
      published
    )
    assert.equal((await ac.page(ac.business)).total, 2)
    assert.equal((await ac.page(ac.feedback)).total, 1)
    assert.equal(errors.length, 0)
    assert.equal(blockedWrites.length, 0)
    assert.equal(failures.length, 0)
  })
} catch (error) {
  failure = error.stack || error.message
  console.error(failure)
  if (page) await screenshot('failure').catch(() => {})
} finally {
  for (const id of drafts) {
    const draft = await ac.taskApi('draft-get', { id })
    assert.ok(draft.content.task.title.startsWith(ac.prefix))
    await ac.taskApi('draft-delete', { id, expectedRevision: draft.revision })
  }
  if (browser) await browser.close()
  await ac.finish()
  await writeFile(
    resolve(ac.output, 'result.json'),
    JSON.stringify(
      {
        checks,
        failure,
        errors,
        blockedWrites,
        failures,
        cleanup: ac.cleanup,
        deletedDrafts: drafts,
        screenshots: ac.screenshots
      },
      null,
      2
    )
  )
  console.log('RESULT ' + resolve(ac.output, 'result.json'))
}
if (
  failure ||
  errors.length ||
  blockedWrites.length ||
  failures.length ||
  ac.cleanup.some(item => item.status === 'failed')
)
  process.exitCode = 1
