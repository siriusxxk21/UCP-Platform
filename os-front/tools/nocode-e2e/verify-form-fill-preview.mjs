import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 关联带入预览：设计器预览使用当前草稿表单，选中关联记录后带入来源字段值且不写回草稿。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix += randomBytes(3).toString('hex')
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-form-fill-preview', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
const fieldNode = (id, fieldId, fill) => ({
  id,
  type: 'FIELD',
  fieldId,
  children: [],
  ...(fill ? { presentation: { fill } } : {})
})
try {
  await ac.login()
  const supplier = await ac.object('fill_supplier', '带入供应商', [ac.field('name', 'TEXT', '名称')])
  const order = await ac.object(
    'fill_order',
    '带入订单',
    [ac.field('name', 'TEXT', '名称'), ac.field('note', 'TEXT', '带入备注')],
    {},
    [
      {
        id: null,
        code: 'supplier',
        name: '供应商',
        kind: 'REFERENCE',
        targetObjectId: supplier.objectId,
        fieldId: null,
        targetFieldId: null,
        required: false,
        onDelete: 'RESTRICT'
      }
    ]
  )
  const relation = order.definition.relations[0]
  const plainNodes = [
    fieldNode('name_node', order.ids.name),
    fieldNode('supplier_node', relation.fieldId),
    fieldNode('note_node', order.ids.note)
  ]
  const form = nodes => ({
    objectId: order.objectId,
    nodes,
    detailIds: [],
    detailNodes: {},
    relatedForms: [],
    options: { layout: 'vertical', submitText: '保存' }
  })
  const definition = resources => ({
    objects: [supplier, order].map(item => ({
      objectId: item.objectId,
      versionNo: item.versionNo,
      checksum: item.checksum
    })),
    resources
  })
  const orderForm = {
    id: 'order_form',
    code: 'order_form',
    kind: 'FORM',
    name: '带入订单表单',
    config: form(plainNodes)
  }
  const orderView = {
    id: 'order_view',
    code: 'order_view',
    kind: 'VIEW',
    name: '带入订单列表',
    config: {
      objectId: order.objectId,
      fieldIds: [order.ids.name],
      equal: {},
      sortFieldId: null,
      descending: false,
      pageSize: 20,
      formId: 'order_form'
    }
  }
  const orderMenu = {
    id: 'order_menu',
    code: 'order_menu',
    kind: 'MENU',
    name: '带入订单',
    config: { targetId: 'order_view' }
  }
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_fill_preview',
    name: '关联带入预览 ' + ac.prefix,
    definition: definition([orderForm, orderView, orderMenu])
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(supplier, ac.grant(supplier))
  await ac.share(order, ac.grant(order))
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '关联带入预览验收：先发布无带入表单'
  })
  const saved = await ac.save(supplier, { name: '预览供应商' })
  const fillNodes = structuredClone(plainNodes)
  fillNodes[2].presentation = {
    fill: { sourceFieldId: relation.fieldId, valueFieldId: supplier.ids.name, mode: 'SOURCE_CHANGE' }
  }
  const resources = structuredClone(ac.app.draft.resources)
  resources.find(resource => resource.id === 'order_form').config = form(fillNodes)
  ac.app = await ac.api('/nocode/application/save', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    code: ac.app.application.code,
    name: ac.app.application.name,
    definition: definition(resources)
  })
  await ac.persist()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
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
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务表单' }).click()
  await page
    .locator('.resources .ant-table-row')
    .filter({ has: page.getByRole('cell', { name: 'order_form', exact: true }) })
    .getByRole('button', { name: /配置$/ })
    .click()
  const dialog = page.locator('.nocode-form-workspace .ant-modal-content')
  await dialog.getByRole('button', { name: /预览$/ }).click()
  const preview = page.locator('.nocode-form-preview .ant-modal-content')
  await expect(preview).toContainText('带入订单表单')
  await preview.locator('.selection-field .ant-select-selector').first().click()
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: '预览供应商' }).click()
  await expect(preview.getByRole('textbox', { name: '带入备注', exact: true })).toHaveValue('预览供应商')
  await expect(preview.locator('.ant-alert-error')).toHaveCount(0)
  await page.screenshot({ path: resolve(output, '01-preview-fill.png'), fullPage: true, animations: 'disabled' })
  checks.push('设计器预览选中关联记录后按当前草稿表单带入来源字段值，无错误提示')
  await preview.locator('.ant-modal-close').click()
  await expect(preview).toBeHidden()
  await dialog.locator('.ant-modal-close').click()
  await page
    .locator('.ant-modal-confirm:visible')
    .getByRole('button', { name: '放弃修改', exact: true })
    .click({ timeout: 3000 })
    .catch(() => {})
  const server = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  assert.equal(server.application.revision, ac.app.application.revision)
  assert.equal(
    server.draft.resources
      .find(resource => resource.id === 'order_form')
      .config.nodes.find(node => node.id === 'note_node').presentation.fill.valueFieldId,
    supplier.ids.name
  )
  checks.push('预览只读：带入不写回服务端草稿，修订号不变')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  if (failure) checks.push('存在失败项：' + (failure.message || String(failure)))
  await writeFile(
    resolve(output, 'run.json'),
    JSON.stringify({ prefix: ac.prefix, checks, errors, failure: failure?.message || null }, null, 2)
  )
  await ac.persist()
}
if (failure) {
  console.error(failure)
  process.exit(1)
}
console.log(JSON.stringify({ prefix: ac.prefix, output, checks }, null, 2))
