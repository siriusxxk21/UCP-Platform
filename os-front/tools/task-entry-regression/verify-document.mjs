import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'
import { verifyFormViewport } from '../nocode-e2e/form-viewport.mjs'
import { verifyFeedbackDraft } from '../nocode-e2e/feedback-draft.mjs'

// 采购只是一份通过公开设计 API 生成的验收配置。主从、文件和已办均使用正式通用入口。
const ac = new FormAcceptance(process.env.TASK_ENTRY_API)
const out = resolve(process.env.TASK_ENTRY_OUTPUT || '../os-server/os-nocode/.work/task-document-review', ac.prefix)
ac.output = out
const origin = process.env.TASK_ENTRY_URL || 'http://127.0.0.1:5173'
const root = '/nocode/task-entry'
const checks = [],
  errors = [],
  records = {}
let browser, activePage, applicationId, object, employee, policy, failed
const check = async (name, work) => {
  await work()
  checks.push(name)
  console.log('PASS ' + name)
}
const shot = (page, name) =>
  page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
const field = (page, scope, name) =>
  scope
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + name + '$') }) })
async function pageFor(user) {
  const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.stack || error.message))
  const token = ac.tokens[user] || ac.tokens.admin
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(info.menus || []))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info }
  )
  activePage = page
  return page
}

try {
  await ac.login()
  object = await ac.object(
    'purchase',
    '采购历史（自动已办验收）',
    [ac.field('name', 'TEXT', '采购事项'), ac.field('attachment', 'ATTACHMENT', '采购附件')],
    {},
    [],
    [
      {
        id: null,
        code: 'items',
        name: '采购明细',
        tableName: `biz_${ac.prefix}_items`,
        state: 'ACTIVE',
        fields: [
          ac.field('product', 'TEXT', '物品名称'),
          { ...ac.field('quantity', 'INTEGER', '采购数量'), required: true }
        ],
        fieldOptions: {},
        indexes: []
      }
    ]
  )
  const detail = object.definition.details[0]
  const qty = detail.fields.find(f => f.code === 'quantity').id
  const ids = object.definition.fields.map(f => f.id)
  const permission = ac.grant(object, {
    actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'],
    scope: 'OWN',
    readDetails: [detail.id],
    writeDetails: [detail.id]
  })
  const resource = (id, kind, name, config) => ({ id, kind, name, code: 'task_document_' + id, config })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_document',
    name: '采购登记与自动已办（验收）',
    description: ac.prefix + ' 通用主从办理验收，非真实采购',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource('form', 'FORM', '采购填写', {
          objectId: object.objectId,
          nodes: ids.map(fieldId => ({ id: 'f_' + fieldId, type: 'FIELD', fieldId, children: [], span: 12 })),
          detailIds: [detail.id],
          detailNodes: {
            [detail.id]: detail.fields.map(f => ({
              id: 'line_' + f.id,
              type: 'FIELD',
              fieldId: f.id,
              children: [],
              presentation:
                f.code === 'product'
                  ? {
                      behavior: {
                        requiredWhen: {
                          op: 'GE',
                          args: [
                            { op: 'FIELD', fieldId: qty, args: [] },
                            { op: 'VALUE', value: 3, args: [] }
                          ]
                        }
                      }
                    }
                  : null
            }))
          },
          options: { layout: 'vertical', submitText: '保存记录' }
        }),
        resource('view', 'VIEW', '采购历史', {
          objectId: object.objectId,
          fieldIds: [object.ids.name],
          equal: {},
          pageSize: 10,
          formId: 'form'
        }),
        resource('purchase', 'TASK_ENTRY', '采购历史登记（验收）', {
          objectId: object.objectId,
          viewId: 'view',
          formId: 'form',
          mode: 'LIST',
          category: '采购验收',
          description: '登记一次，同时形成个人已办与老板业务动态',
          sortOrder: 1,
          limits: [permission]
        })
      ]
    }
  })
  applicationId = ac.app.application.id
  ac.owned.applications.push({ id: applicationId, code: ac.app.application.code })
  await ac.share(object, ac.grant(object, { readDetails: [detail.id], writeDetails: [detail.id] }))
  employee = await ac.user('document')
  policy = await ac.api(root + '/policy', {
    applicationId,
    entryId: 'purchase',
    expectedRevision: 0,
    enabled: true,
    members: [ac.member(employee, [permission])]
  })
  const version = await ac.api('/nocode/application/publish', {
    id: applicationId,
    expectedRevision: ac.app.application.revision,
    reason: '整单办理及自动已办验收'
  })
  const cards = await ac.api(root + '/mine', undefined, ac.tokens[employee])
  const entry = {
    applicationId,
    entryId: 'purchase',
    version: cards.find(card => card.applicationId === applicationId && card.entryId === 'purchase').version
  }
  const api = (path, body) => ac.api(root + path, body, ac.tokens[employee])
  const activity = () => api('/activity', { entry })
  const list = () => api('/page', { entry, query: ac.query(object) })
  const start = new Date().toISOString()
  browser = await chromium.launch({ headless: true, channel: process.env.TASK_ENTRY_BROWSER || 'chrome' })
  const page = await pageFor(employee)
  const url = origin + `/nocode-app/task-center?app=${applicationId}&entry=purchase`
  const modal = page.locator('.ant-drawer-content').filter({ has: page.locator('.task-host__tools') })
  await page.goto(url)
  await check('单表登记只保存一次，自动生成本人已办并可原地定位业务', async () => {
    await modal.getByRole('button', { name: /新增$/ }).click()
    await field(page, modal, '采购事项').locator('input').fill('单表采购历史登记')
    await modal.getByRole('button', { name: /保存记录$/ }).click()
    await expect(modal.getByText('单表采购历史登记', { exact: true })).toBeVisible()
    records.simple = (await list()).list[0]
    const work = (await activity()).items
    assert.equal(work.length, 1)
    assert.equal(work[0].recordId, records.simple.id)
    assert.equal(work[0].recordName, '单表采购历史登记')
    await modal.locator('.ant-drawer-close').click()
    await page.getByText('我的已办', { exact: true }).click()
    await expect(page.getByText('新增 · 单表采购历史登记', { exact: true })).toBeVisible()
    await shot(page, '01-新增自动已办')
    await page.getByRole('button', { name: '查看业务记录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp('recordId=' + records.simple.id))
    await expect(modal.getByRole('button', { name: '返回列表', exact: true })).toBeVisible()
    await expect(modal.locator('strong').filter({ hasText: '单表采购历史登记' })).toBeVisible()
    await modal.locator('.ant-drawer-close').click()
  })
  await check('主从及附件暂存后刷新恢复，暂存与必填失败不形成已办', async () => {
    await page.goto(url)
    await modal.getByRole('button', { name: /新增$/ }).click()
    await field(page, modal, '采购事项').locator('input').fill('主从采购登记与附件')
    await modal.getByRole('button', { name: '添加明细', exact: true }).click()
    await field(page, modal.locator('.detail-row').first(), '物品名称').locator('input').fill('验收用品')
    await modal.locator('input[type=file]').setInputFiles({
      name: ac.prefix + '-purchase.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('仅任务中心验收附件，无真实采购内容')
    })
    await expect(modal.locator('.ant-upload-list-item-done')).toHaveCount(1)
    await modal.getByRole('button', { name: '暂存草稿', exact: true }).click()
    await expect(page.getByText('已暂存；下次从这个入口新建时可恢复，不计入业务更新', { exact: true })).toBeVisible()
    const savedDraft = await api('/draft', entry)
    assert.equal(savedDraft.details[detail.id].length, 1)
    assert.equal(savedDraft.values[object.ids.attachment].length, 1)
    assert.equal((await activity()).items.length, 1)
    await page.reload()
    await modal.getByRole('button', { name: /新增$/ }).click()
    await modal.getByRole('button', { name: '恢复上次暂存', exact: true }).click()
    await expect(field(page, modal, '采购事项').locator('input')).toHaveValue('主从采购登记与附件')
    await expect(field(page, modal.locator('.detail-row').first(), '物品名称').locator('input')).toHaveValue('验收用品')
    await expect(modal.locator('.ant-upload-list-item-done')).toHaveCount(1)
    const restored = await api('/draft', entry)
    assert.equal(restored.details[detail.id][0].clientRowKey, savedDraft.details[detail.id][0].clientRowKey)
    await modal.getByRole('button', { name: /保存记录$/ }).click()
    await expect(modal.getByText('请检查“采购数量”的填写内容', { exact: true })).toBeVisible()
    assert.equal((await activity()).items.length, 1)
    assert.equal((await list()).list.length, 1)
    await shot(page, '02-恢复明细附件与错误定位')
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(modal.locator('.detail-grid-head')).toBeHidden()
    await expect(field(page, modal.locator('.detail-row').first(), '物品名称').locator('input')).toBeVisible()
    await field(page, modal.locator('.detail-row').first(), '物品名称').locator('input').scrollIntoViewIfNeeded()
    await page.locator('.ant-message-notice').filter({ hasText: '已恢复上次暂存' }).waitFor({ state: 'hidden' })
    await shot(page, '02a-手机明细卡片')
    await verifyFormViewport(modal.locator('.ant-drawer-body'), out, 'viewport-390')
    for (const width of [320, 768, 1024, 1512]) {
      await page.setViewportSize({ width, height: 900 })
      await field(page, modal.locator('.detail-row').first(), '采购数量').locator('input').scrollIntoViewIfNeeded()
      await shot(page, 'viewport-' + width)
      await verifyFormViewport(modal.locator('.ant-drawer-body'), out, 'viewport-' + width)
      if (width === 320 || width === 1512) {
        await verifyFeedbackDraft(page, ac.prefix, out, width)
        await expect(field(page, modal, '采购事项').locator('input')).toHaveValue('主从采购登记与附件')
      }
    }
    await page.setViewportSize({ width: 1512, height: 982 })
    await field(page, modal.locator('.detail-row').first(), '采购数量').locator('input').fill('3')
    await expect(field(page, modal.locator('.detail-row').first(), '采购数量').locator('input')).toBeFocused()
    await field(page, modal.locator('.detail-row').first(), '物品名称').locator('input').fill('')
    await modal.getByRole('button', { name: /保存记录$/ }).click()
    await expect(modal.getByText('请检查“物品名称”的填写内容', { exact: true })).toBeVisible()
    assert.equal((await list()).list.length, 1)
    await expect(field(page, modal.locator('.detail-row').first(), '采购数量').locator('input')).toHaveValue('3')
    await field(page, modal.locator('.detail-row').first(), '物品名称').locator('input').fill('验收用品')
    await modal.getByRole('button', { name: /保存记录$/ }).click()
    await expect(modal.getByText('主从采购登记与附件', { exact: true })).toBeVisible()
    records.document = (await list()).list.find(row => row.values[object.ids.name] === '主从采购登记与附件')
    assert.ok(records.document)
    assert.equal((await activity()).items.length, 2)
    assert.equal(await api('/draft', entry), null)
    await modal.locator('.ant-drawer-close').click()
  })
  await check('只改明细生成一次更新，重复请求和失败请求均不多记', async () => {
    const current = await api('/get', { entry, recordId: records.document.id })
    const row = current.details[detail.id][0]
    const command = {
      entry,
      record: {
        ...ac.saveBody(object, { name: '主从采购登记与附件' }, current.record),
        details: { [detail.id]: [{ ...row, values: { ...row.values, [qty]: '7' } }] },
        requestKey: randomUUID()
      }
    }
    const updated = await api('/save', command)
    assert.equal((await api('/save', command)).record.id, updated.record.id)
    assert.equal((await activity()).items.length, 3)
    const conflict = await ac.request(
      root + '/save',
      { ...command, record: { ...command.record, requestKey: randomUUID() } },
      ac.tokens[employee]
    )
    assert.notEqual(conflict.code, 0)
    assert.match(conflict.msg, /记录已被修改/)
    assert.equal((await activity()).items.length, 3)
    const query = { start, applicationId }
    const summary = await ac.api('/nocode/record-history/query', query)
    const table = summary.tables.find(t => t.objectId === object.objectId)
    assert.equal(table.counts.create, 2)
    assert.equal(table.counts.update, 1)
    const history = await ac.api('/nocode/record-history/detail', {
      query: { ...query, end: summary.end },
      visibility: summary.visibility,
      objectId: object.objectId,
      recordId: current.record.id
    })
    const changed = history.row.changes.find(c => c.operation === 'UPDATE')
    assert.equal(changed.details[0].before[row.id][qty], '3')
    assert.equal(changed.details[0].after[row.id][qty], '7')
    assert.ok(history.row.changes.every(c => c.source.entryId === 'purchase'))
    records.document = updated.record
  })
  await check('老板在业务动态查看采购新增和明细数量前后变化', async () => {
    const boss = await pageFor('admin')
    await boss.goto(origin + '/nocode-app/record-history')
    // 开发库有其他业务和多轮夹具，先走真实搜索，避免目标被虚拟表格移出可见窗口。
    await boss.getByRole('textbox', { name: '搜索表格或业务', exact: true }).fill(object.definition.objectName)
    await boss
      .locator(`tr[data-row-key="${object.objectId}"]`)
      .getByRole('button', { name: object.definition.objectName + ' · 查看变化', exact: true })
      .click()
    await boss.getByRole('button', { name: `记录 ${records.document.id} · 查看变化详情`, exact: true }).click()
    const drawer = boss.locator('.history-drawer')
    await drawer.getByRole('tab', { name: '修改过程 2', exact: true }).click()
    const changed = drawer.locator('.history-event').filter({ hasText: '修改' }).locator('.history-detail-group')
    await expect(changed.getByText('采购数量', { exact: true })).toBeVisible()
    await expect(changed.locator('.history-before').filter({ hasText: /^3$/ })).toBeVisible()
    await expect(changed.locator('.history-after').filter({ hasText: /^7$/ })).toBeVisible()
    await shot(boss, '03-老板查看同一业务明细变化')
  })
  await check('只读及撤权立即生效，已办定位不授予完整应用访问权', async () => {
    await ac.denied(
      `/nocode/runtime/model?applicationId=${applicationId}&objectId=${object.objectId}`,
      undefined,
      ac.tokens[employee]
    )
    policy = await ac.api(root + '/policy', {
      applicationId,
      entryId: 'purchase',
      expectedRevision: policy.revision,
      enabled: true,
      members: [ac.member(employee, [{ ...permission, actions: ['READ'], writeFields: [], writeDetails: [] }])]
    })
    await ac.denied(
      root + '/save',
      { entry, record: { ...ac.saveBody(object, { name: '不可写' }), requestKey: randomUUID() } },
      ac.tokens[employee]
    )
    assert.equal((await activity()).items.length, 3)
    policy = await ac.api(root + '/policy', {
      applicationId,
      entryId: 'purchase',
      expectedRevision: policy.revision,
      enabled: true,
      members: []
    })
    await ac.denied(root + '/activity', { entry }, ac.tokens[employee])
    await ac.denied(root + '/get', { entry, recordId: records.document.id }, ac.tokens[employee])
  })
  assert.deepEqual(errors, [])
} catch (error) {
  failed = error
  if (activePage) {
    await shot(activePage, 'failure').catch(() => {})
    await writeFile(resolve(out, 'failure.txt'), await activePage.locator('body').innerText()).catch(() => {})
  }
} finally {
  if (browser) await browser.close()
  // 测试员工停用，入口配置保留给管理员体验；从不给正式用户添加验收授权。
  if (applicationId && policy)
    await ac
      .api(root + '/policy', {
        applicationId,
        entryId: 'purchase',
        expectedRevision: policy.revision,
        enabled: true,
        members: []
      })
      .catch(e => errors.push(e.message))
  for (const user of ac.owned.users)
    await ac.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await mkdir(out, { recursive: true })
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        applicationId,
        objectId: object?.objectId,
        employee,
        checks,
        errors,
        records,
        failure: failed?.message
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ applicationId, checks: checks.length, output: out, failure: failed?.message }))
}
if (failed) throw failed
