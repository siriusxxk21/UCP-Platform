import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// UX-075 表单引用字段按视图固定范围限定候选：真实设计器选择/清空限定视图、发布校验与运行时候选。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix += randomBytes(3).toString('hex')
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/nocode-form-view-limit', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
const resource = (id, kind, name, config) => ({ id, code: id, kind, name, config })
const fieldNode = (id, fieldId, presentation) => ({
  id,
  type: 'FIELD',
  fieldId,
  children: [],
  ...(presentation ? { presentation } : {})
})
try {
  await ac.login()
  const target = await ac.object('view_target', '限定候选分类', [ac.field('name', 'TEXT', '名称')])
  const other = await ac.object('view_other', '无关分类', [ac.field('name', 'TEXT', '名称')])
  const source = await ac.object('view_source', '限定候选来源', [ac.field('name', 'TEXT', '名称')], {}, [
    {
      id: null,
      code: 'related',
      name: '业务关联',
      kind: 'REFERENCE',
      targetObjectId: target.objectId,
      fieldId: null,
      targetFieldId: null,
      required: false,
      onDelete: 'RESTRICT'
    }
  ])
  const relation = source.definition.relations[0]
  const referenceCode = source.definition.fields.find(field => field.id === relation.fieldId).code
  const formNodes = [
    fieldNode('name_node', source.ids.name),
    fieldNode('ref_node', relation.fieldId, {
      selection: { appearance: 'SELECT', rootIds: [], includeDescendants: false }
    })
  ]
  const view = (object, fixed) => ({
    objectId: object.objectId,
    fieldIds: [object.ids.name],
    equal: {},
    sortFieldId: null,
    descending: false,
    pageSize: 20,
    ...(fixed ? { query: { fixed, defaults: {}, candidates: {} } } : {})
  })
  const definition = {
    objects: [target, other, source].map(item => ({
      objectId: item.objectId,
      versionNo: item.versionNo,
      checksum: item.checksum
    })),
    resources: [
      resource(
        'limit_view',
        'VIEW',
        '限定候选视图',
        view(target, [{ fieldId: target.ids.name, operator: 'eq', value: '甲' }])
      ),
      resource('other_view', 'VIEW', '无关分类视图', view(other)),
      resource('source_form', 'FORM', '来源表单', {
        objectId: source.objectId,
        nodes: formNodes,
        detailIds: [],
        detailNodes: {},
        relatedForms: [],
        options: { layout: 'vertical', submitText: '保存' }
      }),
      resource('source_view', 'VIEW', '来源列表', {
        objectId: source.objectId,
        fieldIds: [source.ids.name, relation.fieldId],
        equal: {},
        sortFieldId: null,
        descending: false,
        pageSize: 20,
        formId: 'source_form'
      }),
      resource('source_menu', 'MENU', '限定候选来源', { targetId: 'source_view' })
    ]
  }
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_view_limit',
    name: '限定候选验收 ' + ac.prefix,
    definition
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(target, ac.grant(target))
  await ac.share(source, ac.grant(source))
  await ac.share(other, ac.grant(other))
  const publish = async reason => {
    ac.app = await ac.api('/nocode/application/publish', {
      id: ac.app.application.id,
      expectedRevision: ac.app.application.revision,
      reason
    })
  }
  await publish('限定候选验收：先发布未限定表单')
  const inside = await ac.save(target, { name: '甲' })
  const outside = await ac.save(target, { name: '乙' })
  await ac.save(other, { name: '无关记录' })
  const legacy = await ac.save(source, { name: '历史越界引用', [referenceCode]: outside.id })
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
  const save = page.waitForResponse.bind(page)
  async function saveDraft() {
    const response = save(r => r.url().endsWith('/nocode/application/save') && r.request().method() === 'POST')
    await page.getByRole('button', { name: /保存草稿$/ }).click()
    assert.equal((await (await response).json()).code, 0)
    ac.app = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  }
  async function openDesigner() {
    await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
    await page.locator('.resources .ant-segmented-item').filter({ hasText: '业务表单' }).click()
    await page
      .locator('.resources .ant-table-row')
      .filter({ has: page.getByRole('cell', { name: 'source_form', exact: true }) })
      .getByRole('button', { name: /配置$/ })
      .click()
    const dialog = page.locator('.nocode-form-workspace .ant-modal-content')
    await expect(dialog.locator('._fc-m-drag .ant-form-item')).toHaveCount(2)
    return dialog
  }
  // 设计器画布字段渲染为禁用预览控件，用 force 命中表单项以打开右侧选择器设置。
  async function selectReferenceField(dialog) {
    await dialog.locator('._fc-m-drag .ant-form-item').filter({ hasText: '业务关联' }).click({ force: true })
    const selection = dialog.locator('._fc-r .selection-presentation')
    await expect(selection).toBeVisible()
    return selection
  }
  const viewSelect = selection =>
    selection.locator(
      "xpath=.//label[normalize-space()='限定视图']/following-sibling::div[contains(@class,'ant-select')][1]"
    )
  const option = name => page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: name })

  const workspace = origin + '/nocode-app/workspace?id=' + ac.app.application.id
  await page.goto(workspace)
  let dialog = await openDesigner()
  let selection = await selectReferenceField(dialog)
  await viewSelect(selection).click()
  const viewOptions = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
  await expect(viewOptions).toHaveCount(1)
  assert.deepEqual(
    await viewOptions.evaluateAll(items => items.map(item => item.textContent.trim())),
    ['限定候选视图'],
    '限定视图只列出绑定引用目标对象的视图'
  )
  checks.push('设计器限定视图只列出引用目标对象的视图，不出现无关对象视图')
  await option('限定候选视图').click()
  await expect(viewSelect(selection).locator('.ant-select-selection-item')).toHaveText('限定候选视图')
  await page.screenshot({ path: resolve(output, '01-designer-limit.png'), fullPage: true, animations: 'disabled' })
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog).toBeHidden()
  await saveDraft()
  let node = ac.app.draft.resources
    .find(r => r.id === 'source_form')
    .config.nodes.find(n => n.fieldId === relation.fieldId)
  assert.equal(node.presentation.selection.viewId, 'limit_view')
  checks.push('设计器选择限定视图并保存草稿后，节点呈现设置写入视图 ID')

  dialog = await openDesigner()
  selection = await selectReferenceField(dialog)
  await expect(viewSelect(selection).locator('.ant-select-selection-item')).toHaveText('限定候选视图')
  await viewSelect(selection).hover()
  await viewSelect(selection).locator('.ant-select-clear').click()
  await expect(viewSelect(selection).locator('.ant-select-selection-item')).toHaveCount(0)
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog).toBeHidden()
  await saveDraft()
  node = ac.app.draft.resources.find(r => r.id === 'source_form').config.nodes.find(n => n.fieldId === relation.fieldId)
  assert.ok(!node.presentation.selection.viewId, '清空后不再保留限定视图')
  checks.push('重开设计器回读已保存的限定视图，清空后保存草稿不再保留引用')

  dialog = await openDesigner()
  selection = await selectReferenceField(dialog)
  await viewSelect(selection).click()
  await option('限定候选视图').click()
  await dialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
  await expect(dialog).toBeHidden()
  await saveDraft()

  const broken = structuredClone(ac.app)
  const formResource = broken.draft.resources.find(r => r.id === 'source_form')
  formResource.config.nodes.find(n => n.fieldId === relation.fieldId).presentation.selection.viewId = 'other_view'
  const saveBody = source => ({
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    code: ac.app.application.code,
    name: ac.app.application.name,
    definition: { objects: source.draft.objects, resources: source.draft.resources }
  })
  const mismatched = await ac.request('/nocode/application/save', saveBody(broken))
  assert.notEqual(mismatched.code, 0)
  assert.match(mismatched.msg, /限定视图必须与引用目标对象一致/)
  const dropped = structuredClone(broken)
  dropped.draft.resources = dropped.draft.resources.filter(r => r.id !== 'limit_view')
  dropped.draft.resources
    .find(r => r.id === 'source_form')
    .config.nodes.find(n => n.fieldId === relation.fieldId).presentation.selection.viewId = 'limit_view'
  const missing = await ac.request('/nocode/application/save', saveBody(dropped))
  assert.notEqual(missing.code, 0)
  assert.match(missing.msg, /限定视图不存在或已删除/)
  const untouched = await ac.api('/nocode/application/get?id=' + ac.app.application.id)
  assert.equal(untouched.application.revision, ac.app.application.revision)
  checks.push('目标对象不一致与视图已删除的引用在保存时被拦截，草稿与修订号不变')

  await publish('限定候选验收：发布限定视图表单')
  const write = (name, targetId) => ({
    applicationId: ac.app.application.id,
    objectId: source.objectId,
    formId: 'source_form',
    id: null,
    expectedRevision: null,
    values: { [source.ids.name]: name, [relation.fieldId]: targetId },
    details: {}
  })
  const rejected = await ac.request('/nocode/runtime/save', write('越界写入', outside.id))
  assert.notEqual(rejected.code, 0)
  assert.match(rejected.msg, /视图范围/)
  assert.equal((await ac.request('/nocode/runtime/save', write('范围内写入', inside.id))).code, 0)
  checks.push('发布后越界写入被拒绝、范围内写入通过')

  const closeSurface = async surface => {
    await surface.locator('.ant-drawer-close, .ant-modal-close').click()
    const discard = page.locator('.ant-modal-confirm:visible').getByRole('button', { name: '放弃修改', exact: true })
    await discard.click({ timeout: 2000 }).catch(() => {})
    await expect(surface).toHaveCount(0)
  }
  await page.goto(origin + '/nocode-app/runtime?id=' + ac.app.application.id)
  await page.getByRole('button', { name: /新增$/ }).first().click()
  const created = page.locator('.ant-drawer-content:visible').filter({ hasText: '新建记录' })
  await expect(created).toContainText('业务关联')
  await created.locator('.selection-field .ant-select-selector').click()
  const options = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
  await expect(options).toHaveCount(1)
  await expect(options).toHaveText(['甲'])
  await page.keyboard.press('Escape')
  await page.screenshot({ path: resolve(output, '02-runtime-new.png'), fullPage: true, animations: 'disabled' })
  await closeSurface(created)
  checks.push('运行时新增记录的引用候选只保留视图固定范围内的记录')

  await page.locator('.ant-table-row').filter({ hasText: '历史越界引用' }).getByText('编辑', { exact: true }).click()
  const edit = page.locator('.ant-drawer-content:visible').filter({ hasText: '编辑记录' })
  await expect(edit.locator('.selection-field .ant-select-selection-item')).toContainText('乙')
  await edit.locator('.selection-field .ant-select-selector').click()
  await expect(options).toHaveCount(2)
  await expect(page.locator('.ant-select-dropdown:visible .ant-select-item-option-disabled')).toHaveText(['乙'])
  await expect(
    page.locator('.ant-select-dropdown:visible .ant-select-item-option:not(.ant-select-item-option-disabled)')
  ).toHaveText(['甲'])
  await page.screenshot({ path: resolve(output, '03-runtime-legacy.png'), fullPage: true, animations: 'disabled' })
  await page.keyboard.press('Escape')
  await edit.getByRole('button', { name: /^保\s*存$/ }).click()
  await expect(edit).toHaveCount(0)
  const kept = await ac.get(source, legacy)
  assert.equal(kept.record.values[relation.fieldId], outside.id)
  checks.push('超范围历史引用仍回显且可保存无关编辑，候选下拉中该值标记为不可重新选入')
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
