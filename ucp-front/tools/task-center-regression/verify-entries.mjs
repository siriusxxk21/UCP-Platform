import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 正式应用/对象/任务接口创建本轮可识别夹具；不改用户现有记录或使用浏览器模拟数据。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
const output = resolve(process.env.TASK_CENTER_OUTPUT || '.work/task-center-entries', ac.prefix)
ac.output = output
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  tasks = []
let browser, page, failure, root, object, appId
const api = (path, body) => ac.api('/nocode/tasks/' + path, body)
const entry = (path, body) => api('entries/' + path, body)
const name = label => `${ac.prefix} ${label}`
const check = async (title, fn) => {
  await fn()
  checks.push(title)
  console.log('PASS ' + title)
}
const node = (title, extra = {}) => ({
  id: randomUUID(),
  parentId: null,
  title: name(title),
  description: '',
  assigneeId: null,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'T0', offsetDays: 0, durationDays: 1, fixedStart: null },
  predecessorIds: [],
  binding: null,
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
  ...extra
})
const config = (key, title, extra = {}) => ({
  key,
  name: title,
  binding: { applicationId: appId, formId: 'feedbackform', entryId: null },
  dataMode: 'ROOT_SHARED',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: null,
  writableFieldIds: null,
  required: false,
  allowAll: false,
  ...extra
})
const list = (taskId, entryKey, extra = {}) =>
  entry('page', { taskId, entryKey, all: false, onlyMine: false, pageNo: 1, pageSize: 100, search: '', ...extra })
async function transition(id, action) {
  const current = await api('detail', { id })
  return api('transition', {
    id,
    expectedRevision: current.task.revision,
    action,
    note: '多入口验收',
    requestKey: randomUUID()
  })
}
async function child(parentId, title, entries) {
  const detail = await api('create', { parentId, task: node(title, { entries }), requestKey: randomUUID() })
  tasks.push(detail.task.id)
  return detail.task.id
}
const formTarget = (taskId, entryKey, recordId = null, contributionId = null) => ({
  taskId,
  entryKey,
  recordId,
  contributionId
})
const save = (taskId, entryKey, values, extra = {}) =>
  entry('save', {
    taskId,
    entryKey,
    contributionId: null,
    record: { ...ac.saveBody(object, values), formId: 'feedbackform', requestKey: randomUUID(), ...extra }
  })
try {
  await ac.login()
  await mkdir(output, { recursive: true })
  object = await ac.object('feedback', '多入口反馈验收', [
    ac.field('name', 'TEXT', '反馈内容'),
    ac.field('quantity', 'INTEGER', '数量')
  ])
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_entries',
    name: name('多入口应用'),
    description: '任务多入口专项测试夹具',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: ['feedbackform', 'reviewform'].map(formId => ({
        id: formId,
        kind: 'FORM',
        code: formId,
        name: formId === 'feedbackform' ? '反馈表单' : '复核表单',
        config: {
          objectId: object.objectId,
          nodes: object.definition.fields.map(f => ({ id: 'f_' + f.id, type: 'FIELD', fieldId: f.id, children: [] })),
          detailIds: [],
          options: { layout: 'vertical', submitText: '保存反馈记录' }
        }
      }))
    }
  })
  appId = ac.app.application.id
  ac.owned.applications.push({ id: appId, code: ac.app.application.code })
  await ac.share(object, ac.grant(object))
  await ac.api('/nocode/application/publish', {
    id: appId,
    expectedRevision: ac.app.application.revision,
    reason: '多入口真实验收'
  })
  const created = await api('create', {
    task: node('多入口总任务', {
      entries: [config('work', '施工反馈', { required: true }), config('cost', '费用反馈', { dataMode: 'INDEPENDENT' })]
    }),
    requestKey: randomUUID()
  })
  root = created.task.id
  tasks.push(root)
  await transition(root, 'START')
  const shared = await child(root, '继承反馈子任务')
  const isolated = await child(root, '独立反馈子任务', [config('work', '独立反馈', { dataMode: 'INDEPENDENT' })])
  const readonly = await child(root, '只读复核', [
    config('review', '前序共享', { dataMode: 'SOURCE_SHARED', sourceNodeId: root, sourceEntryKey: 'work' })
  ])
  await transition(shared, 'START')
  await transition(isolated, 'START')
  await transition(readonly, 'START')
  let first, second
  await check('一项任务两个入口，同对象保持不同集合；多条反馈持久化且幂等', async () => {
    assert.deepEqual(
      (await entry('list', { id: root })).map(item => item.config.key),
      ['work', 'cost']
    )
    assert.deepEqual(
      (await entry('list', { id: shared })).map(item => item.config.key),
      ['work', 'cost']
    )
    const record = {
      ...ac.saveBody(object, { name: name('现场反馈一'), quantity: 0 }),
      formId: 'feedbackform',
      requestKey: randomUUID()
    }
    first = await entry('save', { taskId: root, entryKey: 'work', contributionId: null, record })
    const retry = await entry('save', { taskId: root, entryKey: 'work', contributionId: null, record })
    assert.equal(first.contributionId, retry.contributionId)
    second = await save(root, 'work', { name: name('现场反馈二'), quantity: 2 })
    assert.equal((await list(root, 'work')).total, 2)
    assert.equal((await list(root, 'cost')).total, 0)
    await save(root, 'cost', { name: name('费用反馈一'), quantity: 7 })
    assert.equal((await list(root, 'cost')).total, 1)
  })
  await check('继承入口的子项共享数据、独立子项隔离，浏览不产生本任务贡献', async () => {
    assert.equal((await list(shared, 'work')).total, 2)
    assert.equal((await list(shared, 'work', { onlyMine: true })).total, 0)
    assert.equal((await list(isolated, 'work')).total, 0)
    await save(shared, 'work', { name: name('子任务反馈'), quantity: 3 })
    assert.equal((await list(root, 'work')).total, 3)
    assert.equal((await list(shared, 'work', { onlyMine: true })).total, 1)
    await save(isolated, 'work', { name: name('独立内容'), quantity: 4 })
    assert.equal((await list(isolated, 'work')).total, 1)
    assert.equal((await list(root, 'work')).total, 3)
  })
  await check('指定来源共享默认只读；伪造保存与数据集外记录访问被拒绝', async () => {
    assert.equal((await list(readonly, 'review')).total, 3)
    assert.equal((await entry('list', { id: readonly }))[0].canWrite, false)
    await ac.denied('/nocode/tasks/entries/save', {
      taskId: readonly,
      entryKey: 'review',
      contributionId: null,
      record: { ...ac.saveBody(object, { name: '禁止越权' }), formId: 'feedbackform', requestKey: randomUUID() }
    })
    const firstRecord =
      (await list(root, 'work')).list.find(item => item.id === first.contributionId)?.record ||
      first.handling.result.record
    await ac.denied('/nocode/tasks/entries/form', formTarget(isolated, 'work', firstRecord.id))
    const outside = await ac.request('/nocode/tasks/entries/page', {
      taskId: isolated,
      entryKey: 'work',
      all: true,
      onlyMine: false,
      pageNo: 1,
      pageSize: 10,
      search: ''
    })
    assert.equal(outside.code, 1050000001)
    assert.equal(outside.msg, '当前入口只允许任务关联数据')
  })
  await check('真实详情抽屉提供多入口列表，新增反馈、查看来源和零值显示', async () => {
    browser = await chromium.launch({ headless: true, channel: process.env.TASK_CENTER_BROWSER || 'chrome' })
    page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
    page.setDefaultTimeout(25000)
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(token => {
      localStorage.setItem('token', token)
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    }, ac.tokens.admin)
    await page.goto(`${origin}/nocode-app/task-center/manage?taskId=${root}`)
    await page.getByRole('tab', { name: '业务材料', exact: true }).click()
    await expect(page.getByRole('tab', { name: '施工反馈', exact: true })).toBeVisible()
    await expect(page.getByRole('tab', { name: '费用反馈', exact: true })).toBeVisible()
    await page.getByRole('tab', { name: '施工反馈', exact: true }).click()
    const workspace = page.locator('.task-entry-workspace')
    const firstRow = workspace.locator('tr[data-row-key]').filter({ hasText: name('现场反馈一') })
    await expect(firstRow.getByRole('cell', { name: '0', exact: true })).toBeVisible()
    await workspace.getByRole('button', { name: '新增反馈', exact: true }).click()
    const editor = page
      .locator('.ant-drawer-content')
      .filter({ has: page.getByRole('button', { name: '保存反馈记录', exact: true }) })
    await expect(editor).toBeVisible()
    const item = editor
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^反馈内容$/ }) })
    await item.locator('input').fill(name('浏览器新增反馈'))
    await editor.getByRole('button', { name: '保存反馈记录', exact: true }).click()
    await expect(editor).toBeHidden()
    await expect(workspace.getByText(name('浏览器新增反馈'), { exact: true })).toBeVisible()
    assert.equal((await list(root, 'work')).total, 4)
    await firstRow.getByText('来源', { exact: true }).click()
    const source = page.locator('.ant-drawer-content').filter({ hasText: '业务反馈来源' })
    await expect(source.getByText(name('多入口总任务') + ' · 管理员', { exact: true })).toBeVisible()
    await source.locator('.ant-drawer-close').click()
    await page.screenshot({
      path: resolve(output, 'multiple-entry-feedback.png'),
      fullPage: true,
      animations: 'disabled'
    })
    await page.getByRole('tab', { name: '费用反馈', exact: true }).click()
    await expect(workspace.getByText(name('费用反馈一'), { exact: true })).toBeVisible()
    await expect(workspace.getByText(name('现场反馈一'), { exact: true })).toBeHidden()
    await expect(page.locator('.ant-modal-content:visible')).toHaveCount(0)
  })
  await check('完成材料固定贡献版本，之后共享数据改动不覆盖历史', async () => {
    await transition(shared, 'COMPLETE')
    const before = await entry('materials', { id: shared })
    assert.ok(before.some(material => material.records.length > 0))
    const old = (await list(root, 'work')).list.find(
      item => item.record?.values[object.ids.name] === name('现场反馈一')
    )
    const form = await entry('form', formTarget(root, 'work', old.record.id, old.id))
    await save(
      root,
      'work',
      { name: name('完成后修改'), quantity: 9 },
      { id: old.record.id, expectedRevision: form.record.record.revision }
    )
    const after = await entry('materials', { id: shared })
    assert.deepEqual(after, before)
    await transition(isolated, 'COMPLETE')
    await transition(readonly, 'COMPLETE')
    await transition(root, 'COMPLETE')
    assert.equal((await api('detail', { id: root })).task.status, 'COMPLETED')
  })
  await check('真实发起一次多选两个入口，回显取消与重复确认保持配置', async () => {
    await page.goto(`${origin}/nocode-app/task-center/launch`)
    const launch = page.locator('.ant-drawer-content').filter({ has: page.locator('.task-entries-editor') })
    await expect(launch).toBeVisible()
    const titleField = launch
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^任务名称$/ }) })
    await titleField.locator('input').fill(name('界面配置多入口'))
    const configuration = launch.locator('.task-entries-editor')
    await configuration.getByRole('button', { name: '选择反馈入口', exact: true }).click()
    const selector = page.locator('.ant-drawer-content').filter({ has: page.locator('.task-entry-selector') })
    await expect(selector).toBeVisible()
    await page.setViewportSize({ width: 1200, height: 720 })
    const footerConfirm = selector.locator('.ant-drawer-footer').getByRole('button', { name: '确认选择', exact: true })
    await expect(footerConfirm).toBeInViewport()
    await page.screenshot({ path: resolve(output, 'entry-selector-1200.png'), fullPage: true, animations: 'disabled' })
    const appFilter = selector.locator('.ant-select').first().getByRole('combobox')
    await appFilter.click()
    await appFilter.fill(name('多入口应用'))
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: name('多入口应用') })
      .click()
    for (const label of ['反馈表单', '复核表单']) {
      const row = selector.locator('tr[data-row-key]').filter({ hasText: label })
      await expect(row).toBeVisible()
      await row.getByRole('checkbox').check()
    }
    await expect(footerConfirm).toBeInViewport()
    await selector.getByRole('button', { name: '确认选择', exact: true }).click()
    await expect(selector).toBeHidden()
    await page.setViewportSize({ width: 1512, height: 982 })
    await expect(configuration.locator('.ant-collapse-item')).toHaveCount(2)
    const firstPanel = configuration.locator('.ant-collapse-item').first()
    await firstPanel.locator('.ant-collapse-header').click()
    await firstPanel.getByText('完成任务前，此入口至少有一条有效反馈', { exact: true }).click()
    // 已选回显后取消，不能删入口或重置高级配置；再确认一次也不重复添加。
    await configuration.getByRole('button', { name: /选择反馈入口/ }).click()
    await expect(selector.locator('tr[data-row-key] input[type="checkbox"]:checked')).toHaveCount(2)
    await selector.getByRole('button', { name: /取\s*消/ }).click()
    await expect(configuration.locator('.ant-collapse-item')).toHaveCount(2)
    await configuration.getByRole('button', { name: /选择反馈入口/ }).click()
    await selector.getByRole('button', { name: '确认选择', exact: true }).click()
    await expect(configuration.locator('.ant-collapse-item')).toHaveCount(2)
    const response = page.waitForResponse(
      r => r.url().endsWith('/api/nocode/tasks/create') && r.request().method() === 'POST'
    )
    await launch.getByRole('button', { name: '发起任务', exact: true }).click()
    const body = await (await response).json()
    assert.equal(body.code, 0, body.msg)
    const id = body.data.task.id
    tasks.push(id)
    const configured = await entry('list', { id })
    assert.deepEqual(configured.map(item => item.config.name).sort(), ['反馈表单', '复核表单'])
    assert.notEqual(configured[0].datasetId, configured[1].datasetId)
    assert.equal(configured.filter(item => item.config.required).length, 1)
    await page.getByRole('tab', { name: '业务材料', exact: true }).click()
    await expect(page.getByRole('tab', { name: '反馈表单', exact: true })).toBeVisible()
    await expect(page.getByRole('tab', { name: '复核表单', exact: true })).toBeVisible()
    await page.screenshot({
      path: resolve(output, 'configured-multiple-entries.png'),
      fullPage: true,
      animations: 'disabled'
    })
  })
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
    await writeFile(resolve(output, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  if (browser) await browser.close()
  await ac.persist()
  await mkdir(output, { recursive: true })
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      { checks, errors, tasks, appId, failure: failure?.stack, finishedAt: new Date().toISOString() },
      null,
      2
    )
  )
  ac.tokens = {}
  console.log(JSON.stringify({ checks: checks.length, output, failure: failure?.message }))
}
if (failure) throw failure
