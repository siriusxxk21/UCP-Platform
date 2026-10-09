import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 显式指定当前开发环境；验收仅操作本次生成并记录的测试数据。
const origin = process.env.FOUR_CENTER_URL
const base = process.env.FOUR_CENTER_API
assert(origin && base && process.env.FOUR_CENTER_OUTPUT, '请配置开发环境 URL/API/OUTPUT')
const ac = new FormAcceptance(base)
const out = resolve(process.env.FOUR_CENTER_OUTPUT, ac.prefix)
ac.output = out
await mkdir(out, { recursive: true })
const checks = [],
  errors = [],
  policies = []
let browser, page, employee, failure
const check = async (name, work) => {
  await work()
  checks.push(name)
  console.log('PASS ' + name)
}
const shot = async name => {
  await page.waitForFunction(() => !document.querySelector('.ant-spin-blur'), null, { timeout: 3000 }).catch(() => {})
  return page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
}
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
try {
  await ac.login()
  const object = await ac.object(
    'orders',
    '多对象订单',
    [ac.field('name', 'TEXT', '订单名称')],
    {},
    [],
    ['items', 'costs'].map(code => ({
      id: null,
      code,
      name: code === 'items' ? '商品明细' : '费用明细',
      tableName: `biz_${ac.prefix}_${code}`,
      state: 'ACTIVE',
      fields: [ac.field('label', 'TEXT', '明细名称'), ac.field('qty', 'INTEGER', '数量')],
      fieldOptions: {},
      indexes: []
    }))
  )
  const payment = await ac.object('payments', '订单收款', [ac.field('name', 'TEXT', '收款说明')], {}, [
    {
      id: null,
      code: 'purchase',
      name: '所属订单',
      kind: 'REFERENCE',
      targetObjectId: object.objectId,
      fieldId: null,
      targetFieldId: null,
      required: false,
      onDelete: 'RESTRICT',
      sourceDetailId: null
    }
  ])
  const items = object.definition.details[0],
    costs = object.definition.details[1]
  const field = (d, code) => d.fields.find(f => f.code === code).id
  const sections = [items, costs].map(d => ({
    id: d.code,
    name: d.name,
    detailId: d.id,
    objectId: null,
    viewId: null,
    binding: null,
    fieldIds: d.fields.map(f => f.id),
    conditions: null,
    pageSize: 1,
    showTable: true
  }))
  sections.push({
    id: 'payments',
    name: '关联收款',
    detailId: null,
    objectId: payment.objectId,
    viewId: 'paymentview',
    binding: { relationId: payment.definition.relations[0].id, direction: 'INCOMING' },
    fieldIds: [payment.ids.name],
    conditions: null,
    pageSize: 10,
    showTable: true
  })
  const columns = [
    { id: 'view_items', name: '商品数量合计', sectionId: 'items', fieldId: field(items, 'qty'), kind: 'SUM' },
    { id: 'view_costs', name: '费用数量合计', sectionId: 'costs', fieldId: field(costs, 'qty'), kind: 'SUM' },
    { id: 'view_payments', name: '收款笔数', sectionId: 'payments', fieldId: null, kind: 'COUNT' }
  ]
  const form = {
    objectId: object.objectId,
    nodes: [{ id: 'name', type: 'FIELD', fieldId: object.ids.name, children: [] }],
    detailIds: [items.id, costs.id],
    options: { layout: 'vertical', submitText: '保存记录' }
  }
  const view = {
    objectId: object.objectId,
    fieldIds: [object.ids.name],
    equal: {},
    pageSize: 10,
    formId: 'form',
    composition: { grain: 'ROOT', detailId: null, sections, columns }
  }
  const rootGrant = ac.grant(object, {
    actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'],
    readDetails: [items.id, costs.id],
    writeDetails: [items.id, costs.id]
  })
  const relatedGrant = ac.grant(payment, { actions: ['READ'], writeFields: [], writeRelations: [] })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_app',
    name: '多对象数据视图验收',
    definition: {
      objects: [object, payment].map(o => ({ objectId: o.objectId, versionNo: o.versionNo, checksum: o.checksum })),
      resources: [
        resource('form', 'FORM', '订单填写', form),
        resource('mainview', 'VIEW', '订单及子表', view),
        resource('detailview', 'VIEW', '商品明细台账', {
          ...view,
          composition: {
            grain: 'DETAIL',
            detailId: items.id,
            sections,
            columns: [
              {
                id: 'view_label',
                name: '明细名称',
                sectionId: 'items',
                fieldId: field(items, 'label'),
                kind: 'DETAIL'
              },
              { id: 'view_qty', name: '明细数量', sectionId: 'items', fieldId: field(items, 'qty'), kind: 'DETAIL' }
            ]
          }
        }),
        resource('paymentview', 'VIEW', '收款列表', {
          objectId: payment.objectId,
          fieldIds: [payment.ids.name],
          equal: {},
          pageSize: 10
        }),
        resource('mainmenu', 'MENU', '主子表', { targetId: 'mainview' }),
        resource('detailmenu', 'MENU', '明细台账', { targetId: 'detailview' }),
        resource('entry', 'TASK_ENTRY', '订单查询办理', {
          objectId: object.objectId,
          viewId: 'mainview',
          formId: 'form',
          mode: 'LIST',
          category: '视图验收',
          sortOrder: 1,
          limits: [rootGrant, relatedGrant]
        })
      ]
    }
  })
  const app = ac.app.application.id
  ac.owned.applications.push({ id: app, code: ac.app.application.code })
  await ac.share(object, ac.grant(object, { readDetails: [items.id, costs.id], writeDetails: [items.id, costs.id] }))
  await ac.share(payment, ac.grant(payment))
  employee = await ac.user('viewer')
  const policy = await ac.api('/nocode/task-entry/policy', {
    applicationId: app,
    entryId: 'entry',
    expectedRevision: 0,
    enabled: true,
    members: [ac.member(employee, [rootGrant, relatedGrant])]
  })
  policies.push({ applicationId: app, entryId: 'entry', revision: policy.revision })
  await ac.api('/nocode/application/publish', {
    id: app,
    expectedRevision: ac.app.application.revision,
    reason: '开发环境多对象视图验收'
  })
  const line = (d, label, qty) => ({
    id: null,
    revision: null,
    values: { [field(d, 'label')]: label, [field(d, 'qty')]: String(qty) }
  })
  const a = await ac.api('/nocode/runtime/save', {
    ...ac.saveBody(object, { name: '订单 A' }),
    details: {
      [items.id]: [line(items, '商品 A1', 2), line(items, '商品 A2', 3)],
      [costs.id]: [line(costs, '运输费', 10), line(costs, '安装费', 20)]
    }
  })
  await ac.api('/nocode/runtime/save', {
    ...ac.saveBody(object, { name: '订单 B' }),
    details: { [items.id]: [line(items, '商品 B1', 9)] }
  })
  await ac.save(object, { name: '订单 C' })
  browser = await chromium.launch({ headless: true, channel: process.env.FOUR_CENTER_BROWSER || 'chrome' })
  async function open(token) {
    const p = await browser.newPage({ viewport: { width: 1512, height: 982 } })
    p.setDefaultTimeout(20000)
    p.on('pageerror', e => errors.push(e.stack || e.message))
    const info = await ac.api('/system/auth/get-permission-info', undefined, token)
    await p.addInitScript(
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
    return p
  }
  page = await open(ac.tokens.admin)
  await page.goto(`${origin}/nocode-app/runtime?id=${app}&menu=mainmenu`)
  const rootRow = () => page.locator(`.business-records > .os-table-page tr[data-row-key="${a.record.id}"]`)
  await check('主子表页面展示独立汇总和多组子表分页', async () => {
    await expect(rootRow()).toContainText('订单 A')
    await expect(rootRow()).toContainText('30')
    await rootRow().locator('.ant-table-row-expand-icon').click()
    const children = page.locator('.data-view-children')
    await expect(children).toContainText('商品 A1')
    await children.locator('.ant-pagination-next button').click()
    await expect(children).toContainText('商品 A2')
    await children.getByRole('tab', { name: '费用明细' }).click()
    await expect(children).toContainText('运输费')
    await shot('01-主记录与多组子表')
  })
  await check('子表筛选可独立显示或同时筛选主记录，清除可恢复', async () => {
    const children = page.locator('.data-view-children')
    await children.getByRole('tab', { name: '商品明细' }).click()
    await children.getByPlaceholder('搜索此子表').fill('商品 A2')
    await children.getByRole('checkbox', { name: '同时只显示含匹配子记录的主记录' }).check()
    await children.getByRole('button', { name: '应用筛选', exact: true }).click()
    await expect(page.locator('.business-records > .os-table-page')).not.toContainText('订单 B')
    await page.locator('.business-records > .ant-space .ant-tag-close-icon').click()
    await expect(page.locator('.business-records > .os-table-page')).toContainText('订单 B')
  })
  await check('关联子表新增自动绑定主记录并刷新汇总', async () => {
    if (!(await page.locator('.data-view-children').count()))
      await rootRow().locator('.ant-table-row-expand-icon').click()
    await page.locator('.data-view-children').getByRole('tab', { name: '关联收款' }).click()
    await page.getByRole('button', { name: '新增关联记录', exact: true }).click()
    await page.locator('.ant-modal:visible input[type="text"]').first().fill('首笔到账')
    await page.locator('.ant-modal:visible').getByRole('button', { name: '保存记录', exact: true }).click()
    await expect(page.locator('.data-view-children')).toContainText('首笔到账')
    const payments = await ac.page(payment)
    assert.equal(payments.list[0].values[payment.definition.relations[0].fieldId], a.record.id)
    await shot('02-关联子表新增')
  })
  await check('明细台账保留主记录定位，编辑回到整单', async () => {
    await page.getByRole('button', { name: '明细台账', exact: true }).click()
    await expect(page.locator('.business-records')).toContainText('商品 A2')
    const row = page.locator('.business-records tr').filter({ hasText: '商品 A2' }).last()
    await row.getByRole('button', { name: /编辑$/ }).click()
    await expect(page.locator('.ant-drawer:visible, .ant-modal:visible').first()).toContainText('商品明细')
    await shot('03-明细定位整单')
    await page
      .locator('.ant-drawer:visible, .ant-modal:visible')
      .first()
      .getByRole('button', { name: /取\s*消/ })
      .click()
  })
  await check('任务入口复用主子表且依赖对象保持只读，撤权即时生效', async () => {
    await page.close()
    page = await open(ac.tokens[employee])
    await page.goto(`${origin}/nocode-app/task-center?app=${app}&entry=entry`)
    await expect(rootRow()).toContainText('订单 A')
    await rootRow().locator('.ant-table-row-expand-icon').click()
    await page.locator('.data-view-children').getByRole('tab', { name: '关联收款' }).click()
    await expect(page.locator('.data-view-children')).toContainText('首笔到账')
    await expect(page.locator('.data-view-children').getByRole('button', { name: '新增关联记录' })).toHaveCount(0)
    await shot('04-任务入口关联读取')
    const cards = await ac.api('/nocode/task-entry/mine', undefined, ac.tokens[employee])
    const entry = { applicationId: app, entryId: 'entry', version: cards.find(c => c.applicationId === app).version }
    const changed = await ac.api('/nocode/task-entry/policy', {
      applicationId: app,
      entryId: 'entry',
      expectedRevision: policies[0].revision,
      enabled: true,
      members: []
    })
    policies[0].revision = changed.revision
    await ac.denied(
      '/nocode/task-entry/view-children',
      {
        entry,
        query: {
          applicationId: app,
          objectId: object.objectId,
          viewId: 'mainview',
          sectionId: 'payments',
          recordId: a.record.id,
          pageNo: 1,
          pageSize: 10
        }
      },
      ac.tokens[employee]
    )
  })
  assert.deepEqual(errors, [])
} catch (e) {
  failure = e
  if (page) {
    await shot('failure').catch(() => {})
    await writeFile(resolve(out, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  if (browser) await browser.close()
  for (const p of policies)
    await ac
      .api('/nocode/task-entry/policy', {
        applicationId: p.applicationId,
        entryId: p.entryId,
        expectedRevision: p.revision,
        enabled: true,
        members: []
      })
      .catch(e => errors.push(e.message))
  for (const user of ac.owned.users)
    await ac
      .api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
      .catch(e => errors.push(e.message))
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(resolve(out, 'result.json'), JSON.stringify({ checks, errors, failure: failure?.message }, null, 2))
  console.log(JSON.stringify({ checks: checks.length, output: out, failure: failure?.message }))
}
if (failure) throw failure
