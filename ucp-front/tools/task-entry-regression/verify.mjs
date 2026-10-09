import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 复用已存在的 HTTP 夹具；凭据只在进程内。所有对象、应用和员工均有本次唯一前缀。
const out = resolve(process.env.TASK_ENTRY_OUTPUT || '../ucp-server/ucp-nocode/.work/task-entry-review')
const ac = new FormAcceptance(process.env.TASK_ENTRY_API, out)
const origin = process.env.TASK_ENTRY_URL || 'http://127.0.0.1:5173'
const root = '/nocode/task-entry'
const checks = [],
  errors = []
let browser, employee, published, object, app, entryPolicy
const shot = async (page, name) =>
  page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
try {
  await ac.login()
  object = await ac.object('task', '任务中心验收', [
    ac.field('name', 'TEXT', '事项名称'),
    ac.field('notes', 'TEXT', '工作说明')
  ])
  const ids = object.definition.fields.map(f => f.id)
  const grant = ac.grant(object, { actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'], scope: 'OWN' })
  const resource = (id, kind, name, config) => ({ id, code: 'task_demo_' + id, kind, name, config })
  const form = resource('form', 'FORM', '办理表单', {
    objectId: object.objectId,
    nodes: ids.map(fieldId => ({ id: 'field_' + fieldId, type: 'FIELD', fieldId, children: [], span: 12 })),
    detailIds: [],
    options: { layout: 'vertical', submitText: '保存记录' }
  })
  const view = resource('list', 'VIEW', '办理列表', {
    objectId: object.objectId,
    fieldIds: ids,
    equal: {},
    pageSize: 10,
    formId: 'form',
    list: { queryFieldIds: [ids[0]], advancedFieldIds: [], columnWidths: {}, batchDelete: false }
  })
  const entry = (id, mode, name, category, actions = grant.actions) =>
    resource(id, 'TASK_ENTRY', name, {
      objectId: object.objectId,
      viewId: mode === 'LIST' ? 'list' : null,
      formId: 'form',
      mode,
      category,
      description: mode === 'LIST' ? '维护本人登记的公司资料' : '登记本日施工进度，保存后形成工作记录',
      icon: null,
      sortOrder: 1,
      limits: [{ ...grant, actions }]
    })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_task',
    name: '任务中心验收示例',
    description: ac.prefix + ' 可识别的验收配置',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        form,
        view,
        entry('company', 'LIST', '维护公司资料', '行政'),
        entry('progress', 'FORM', '登记施工进度', '工程', ['READ', 'CREATE']),
        entry('readonly', 'LIST', '只读资料查询', '行政', ['READ'])
      ]
    }
  })
  app = ac.app.application.id
  ac.owned.applications.push(app)
  await ac.persist()
  await ac.share(object, ac.grant(object))
  employee = await ac.user('task', undefined)
  for (const id of ['company', 'progress', 'readonly']) {
    const actions = id === 'progress' ? ['READ', 'CREATE'] : id === 'readonly' ? ['READ'] : grant.actions
    const permission = { ...grant, actions, writeFields: id === 'readonly' ? [] : grant.writeFields }
    const p = await ac.api(root + '/policy', {
      applicationId: app,
      entryId: id,
      expectedRevision: 0,
      enabled: true,
      members: [ac.member(employee, [permission])]
    })
    if (id === 'company') entryPolicy = p
  }
  published = await ac.api('/nocode/application/publish', {
    id: app,
    expectedRevision: ac.app.application.revision,
    reason: '任务中心浏览器验收'
  })
  const cards = await ac.api(root + '/mine', undefined, ac.tokens[employee])
  assert.equal(cards.filter(c => c.applicationId === app).length, 3)
  const locator = id => ({ applicationId: app, entryId: id, version: cards.find(c => c.entryId === id).version })
  await ac.denied(
    `/nocode/runtime/model?applicationId=${app}&objectId=${object.objectId}`,
    undefined,
    ac.tokens[employee]
  )
  checks.push('真实 HTTP：员工可发现三个任务入口，普通应用 model API 拒绝访问')

  browser = await chromium.launch({ headless: true, channel: process.env.TASK_ENTRY_BROWSER || 'chrome' })
  const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(25000)
  page.on('pageerror', error => errors.push(error.message))
  const permission = await ac.api('/system/auth/get-permission-info', undefined, ac.tokens[employee])
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', '[]')
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens[employee], info: permission }
  )
  await page.goto(origin + '/nocode-app/task-center')
  await expect(page.locator('.task-card')).toHaveCount(3)
  await shot(page, '01-任务门户')
  await page.getByLabel('收藏登记施工进度', { exact: true }).click()
  await page.getByText('我的收藏', { exact: true }).click()
  await expect(page.locator('.task-card')).toHaveCount(1)
  await page.getByText('全部事项', { exact: true }).click()
  await page.getByLabel('搜索任务入口').fill('施工')
  await expect(page.locator('.task-card')).toHaveCount(1)
  await page.getByLabel('搜索任务入口').fill('')
  checks.push('门户分类、搜索和收藏')

  await page.locator('.task-card__main').filter({ hasText: '维护公司资料' }).click()
  const modal = page.locator('.task-host .ant-modal-content')
  await expect(modal).toBeVisible()
  await modal.getByRole('button', { name: /新增$/ }).click()
  const input = () => modal.locator('.os-form-surface input').first()
  await input().fill('任务验收公司 A')
  await modal.getByRole('button', { name: /全屏办理$/ }).click()
  await expect(input()).toHaveValue('任务验收公司 A')
  await modal.getByRole('button', { name: /还原窗口$/ }).click()
  await modal.locator('.ant-modal-close').click()
  await expect(page.getByText('放弃尚未保存的修改？', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /继续编辑$/ }).click()
  await expect(input()).toHaveValue('任务验收公司 A')
  await modal.getByRole('button', { name: /保存记录$/ }).click()
  await expect(modal.getByText('任务验收公司 A', { exact: true })).toBeVisible()
  assert.equal(await page.locator('.ant-modal-content:visible').count(), 1, '列表新增不叠加第二个窗口')
  await shot(page, '02-列表办理')
  checks.push('列表内新增、全屏切换保留输入、关闭取消保留输入、保存后刷新、只有一个办理窗口')
  await modal.getByRole('button', { name: /编辑$/ }).click()
  await input().fill('任务验收公司 B')
  await modal.getByRole('button', { name: /保存记录$/ }).click()
  await expect(modal.getByText('任务验收公司 B', { exact: true })).toBeVisible()
  await modal.locator('.ant-modal-close').click()
  await expect(modal).toHaveCount(0)
  await page.locator('.task-card__main').filter({ hasText: '只读资料查询' }).click()
  await expect(modal.getByText('任务验收公司 B', { exact: true })).toBeVisible()
  await expect(modal.getByRole('button', { name: /新增$/ })).toHaveCount(0)
  await expect(modal.getByRole('button', { name: /编辑$/ })).toHaveCount(0)
  const row = (
    await ac.api(root + '/page', { entry: locator('company'), query: ac.query(object) }, ac.tokens[employee])
  ).list[0]
  await ac.denied(
    root + '/save',
    {
      entry: locator('readonly'),
      record: { ...ac.saveBody(object, { name: '不应成功' }, row), requestKey: randomUUID() }
    },
    ac.tokens[employee]
  )
  checks.push('列表编辑成功；只读入口隐藏写按钮，伪造写 API 被拒绝')
  await modal.locator('.ant-modal-close').click()

  await page.locator('.task-card__main').filter({ hasText: '登记施工进度' }).click()
  await input().fill('施工进度暂存')
  await modal.getByRole('button', { name: /暂存草稿$/ }).click()
  await expect(page.getByText('已暂存；下次从这个入口新建时可恢复，不计入业务更新', { exact: true })).toBeVisible()
  await page.reload()
  await modal.getByRole('button', { name: /恢复上次暂存$/ }).click()
  await expect(input()).toHaveValue('施工进度暂存')
  await shot(page, '03-直接填写与暂存')
  await modal.getByText('保存后继续填写下一条', { exact: true }).click()
  await modal.getByRole('button', { name: /保存记录$/ }).click()
  await expect(input()).toHaveValue('')
  await input().fill('施工进度第二条')
  await modal.getByText('保存后继续填写下一条', { exact: true }).click()
  await modal.getByRole('button', { name: /保存记录$/ }).click()
  await expect(modal).toHaveCount(0)
  checks.push('直接填写、暂存不写业务、刷新深链后恢复、正式保存关闭草稿、连续新增')

  await page.getByText('我的已办', { exact: true }).click()
  await expect(page.locator('.task-activity .ant-list-item')).toHaveCount(4)
  await shot(page, '04-我的操作')
  const summaries = await ac.api(root + '/activity', { entry: locator('company') }, ac.tokens[employee])
  assert.equal(summaries.items.length, 2)
  assert.ok(summaries.items.every(item => !('before' in item) && !('after' in item)))
  await ac.api(
    root + '/delete',
    { entry: locator('company'), recordId: row.id, expectedRevision: row.revision },
    ac.tokens[employee]
  )
  const afterDelete = await ac.api(root + '/activity', { entry: locator('company') }, ac.tokens[employee])
  assert.equal(afterDelete.items.length, 3)
  checks.push('本人操作摘要来自成功变更；删除追加一条操作，历史值不直接暴露')
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const historyQuery = { start: today.toISOString(), applicationId: app }
  const summary = await ac.api('/nocode/record-history/query', historyQuery)
  const detail = await ac.api('/nocode/record-history/detail', {
    query: { ...historyQuery, end: summary.end },
    visibility: summary.visibility,
    objectId: object.objectId,
    recordId: row.id
  })
  assert.equal(detail.row.changes.filter(change => change.source?.entryId === 'company').length, 3)
  checks.push('老板历史详情实际包含入口来源，新增/编辑/删除共三条')

  await ac.api(root + '/policy', {
    applicationId: app,
    entryId: 'company',
    expectedRevision: entryPolicy.revision,
    enabled: false,
    members: entryPolicy.members
  })
  await page.goto(origin + `/nocode-app/task-center?app=${app}&entry=company`)
  await expect(page.getByText('当前事项暂不可办理', { exact: true })).toBeVisible()
  checks.push('停用立即使旧链接不可用，不导航到完整应用')
  await ac.api(root + '/policy', {
    applicationId: app,
    entryId: 'company',
    expectedRevision: entryPolicy.revision + 1,
    enabled: true,
    members: entryPolicy.members
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(origin + '/nocode-app/task-center')
  await page.getByText('全部事项', { exact: true }).click()
  await page.locator('.task-card__main').filter({ hasText: '登记施工进度' }).click()
  await expect(modal).toBeVisible()
  assert.ok(await modal.evaluate(node => node.getBoundingClientRect().width <= innerWidth))
  await shot(page, '05-移动端办理')
  assert.deepEqual(errors, [])
  checks.push('移动端窗口适配；浏览器无未捕获异常')

  const adminPage = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  const adminInfo = await ac.api('/system/auth/get-permission-info')
  adminPage.on('pageerror', error => errors.push(error.message))
  await adminPage.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', '[]')
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info: adminInfo }
  )
  await adminPage.goto(origin + `/nocode-app/workspace?id=${app}`)
  await adminPage.getByRole('tab', { name: '任务入口', exact: true }).click()
  await expect(adminPage.getByText('维护公司资料', { exact: true })).toBeVisible()
  await adminPage.getByRole('button', { name: /配置$/ }).first().click()
  const configModal = adminPage.locator('.ant-modal-content').filter({ hasText: '配置任务入口' })
  await expect(configModal).toBeVisible()
  await configModal.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(configModal).toHaveCount(0)
  await adminPage
    .getByRole('button', { name: /人员与开放$/ })
    .first()
    .click()
  await expect(adminPage.getByText('请先保存应用草稿，再配置入口授权', { exact: true })).toBeVisible()
  await shot(adminPage, '06-应用入口配置')
  assert.deepEqual(errors, [])
  checks.push('应用工作区入口列表、打开配置、应用到草稿、未保存时阻止误用旧授权配置')
} catch (error) {
  if (browser) {
    const page = browser.contexts()[0]?.pages()[0]
    if (page) {
      await shot(page, 'failure')
      await writeFile(resolve(out, 'failure.txt'), await page.locator('body').innerText())
    }
  }
  throw error
} finally {
  if (browser) await browser.close()
  for (const person of ac.owned.users) {
    await ac.api('/system/user/update-status', { id: person.id, status: 1 }, undefined, 'PUT')
    person.status = 1
  }
  ac.passwords = {}
  ac.tokens = {}
  await ac.persist()
  await mkdir(out, { recursive: true })
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify({ time: new Date().toISOString(), applicationId: app, checks, errors }, null, 2)
  )
  console.log(JSON.stringify({ checks: checks.length, applicationId: app, output: out }))
}
