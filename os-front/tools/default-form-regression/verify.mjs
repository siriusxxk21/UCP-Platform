import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只创建并操作 manifest 登记的专属夹具；重新执行指定原输出目录可沿用同一批对象和应用。
const prefix = `df${Date.now().toString(36)}${randomBytes(2).toString('hex')}`
const output = resolve(process.env.DEFAULT_FORM_OUTPUT || `.work/default-form/${prefix}`)
const origin = process.env.DEFAULT_FORM_URL || 'http://127.0.0.1:5173'
const ac = new FormAcceptance(process.env.DEFAULT_FORM_API || 'http://127.0.0.1:8080/api', output)
await mkdir(output, { recursive: true })
let manifest
try {
  manifest = JSON.parse(await readFile(resolve(output, 'manifest.json'), 'utf8'))
} catch (error) {
  if (error.code !== 'ENOENT') throw error
  manifest = { prefix, owned: ac.owned, checks: [], records: [], steps: {} }
}
assert.match(manifest.prefix, /^df[a-z0-9]+$/, '仅接受本脚本生成的专属夹具标识')
ac.prefix = manifest.prefix
ac.owned = manifest.owned
const errors = [],
  blockedWrites = []
let browser, page, failure
const persist = async () => {
  manifest.owned = ac.owned
  manifest.updated = new Date().toISOString()
  await writeFile(resolve(output, 'manifest.json'), JSON.stringify(manifest, null, 2))
  await ac.persist()
}
const checked = async (name, detail) => {
  manifest.checks = manifest.checks.filter(check => check.name !== name)
  manifest.checks.push({ name, passed: true, detail })
  await persist()
}
const appId = () => ac.app.application.id
const definitionRef = object => ({ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum })
const viewResource = (id, object) => ({
  id,
  code: id,
  name: '旧版自动表单列表',
  kind: 'VIEW',
  config: {
    objectId: object.objectId,
    fieldIds: Object.values(object.ids),
    equal: {},
    sortFieldId: null,
    descending: true,
    pageSize: 20,
    formId: null
  }
})
const getApp = async () => (ac.app = await ac.api('/nocode/application/get?id=' + appId()))
const runtimeDefinition = async () => (await ac.api('/nocode/runtime/application?id=' + appId())).definition
const saveBody = definition => ({
  id: appId(),
  expectedRevision: ac.app.application.revision,
  code: ac.app.application.code,
  name: ac.app.application.name,
  description: ac.app.application.description,
  icon: ac.app.application.icon,
  category: ac.app.application.category,
  definition
})
const dialog = () =>
  page.locator('.ant-modal-content:visible').filter({
    has: page.getByRole('textbox', { name: '资源名称', exact: true })
  })
const rows = () => page.locator('.resources .ant-table-row')
const rowByCode = code => rows().filter({ has: page.getByRole('cell', { name: code, exact: true }) })
const rowByName = name => rows().filter({ has: page.getByRole('cell', { name: new RegExp(`^${name}(\\s*默认)?$`) }) })
async function kind(name) {
  await page.locator('.resources .ant-segmented-item').filter({ hasText: name }).click()
}
async function workspace() {
  await page.goto(`${origin}/nocode-app/workspace?id=${appId()}`)
  await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
  await expect(page.locator('.resources')).toBeVisible()
}
async function openView(code) {
  await kind('数据视图')
  await rowByCode(code).getByRole('button', { name: /配置$/ }).click()
  await dialog().getByRole('tab', { name: '操作与页面', exact: true }).click()
}
async function apply(label = '应用到草稿') {
  await expect(dialog().getByRole('button', { name: label, exact: true })).toBeEnabled({ timeout: 60000 })
  await dialog().getByRole('button', { name: label, exact: true }).click()
  if (label === '应用到草稿') await expect(dialog()).toHaveCount(0)
  else await expect(dialog().getByRole('tab', { name: '操作与页面', exact: true })).toBeVisible()
}
async function closeResource(discard = false) {
  await dialog().locator('.ant-modal-close').click()
  if (discard) {
    const confirmation = page.locator('.ant-modal-confirm:visible')
    await expect(confirmation).toContainText('放弃尚未应用到草稿的修改')
    await confirmation.getByRole('button', { name: '放弃修改', exact: true }).click()
  }
  await expect(dialog()).toHaveCount(0)
}
async function changeForm(name, submitText) {
  await expect(dialog().locator('.form-design-toolbar')).toBeVisible()
  await dialog().getByRole('textbox', { name: '资源名称', exact: true }).fill(name)
  await dialog()
    .getByRole('button', { name: /表单设置$/ })
    .click()
  const settings = dialog().locator('.ant-drawer:visible')
  await settings
    .locator('.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: /^提交按钮名称$/ }) })
    .locator('input')
    .fill(submitText)
  await settings.locator('.ant-drawer-close').click()
}
async function saveDraft() {
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/application/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  await expect(page.locator('.workspace-header')).not.toContainText('有未保存修改')
  await getApp()
}
async function publish() {
  await page
    .locator('.workspace-header')
    .getByRole('button', { name: /发布应用$/ })
    .click()
  const publishDialog = page
    .locator('.ant-modal-content:visible')
    .filter({ has: page.getByRole('textbox', { name: '发布说明', exact: true }) })
  await publishDialog
    .getByRole('textbox', { name: '发布说明', exact: true })
    .fill('默认表单真实 UI 与版本边界验收 ' + ac.prefix)
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/application/publish') && r.request().method() === 'POST'
  )
  await expect(publishDialog.getByRole('button', { name: '发布应用', exact: true })).toBeEnabled()
  await publishDialog.getByRole('button', { name: '发布应用', exact: true }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0, result.msg)
  await expect(publishDialog).toHaveCount(0)
  await getApp()
}
async function formMenu(name, action) {
  await rowByName(name)
    .getByRole('button', { name: /更\s*多/ })
    .click()
  await page.locator('.ant-dropdown:visible').getByRole('menuitem', { name: action, exact: true }).click()
}
async function createView(code, name) {
  await kind('数据视图')
  await page.getByRole('button', { name: /新建数据视图$/ }).click()
  await dialog().getByRole('textbox', { name: '资源名称', exact: true }).fill(name)
  await dialog().getByRole('textbox', { name: '资源编码', exact: true }).fill(code)
  await dialog().getByRole('tab', { name: '操作与页面', exact: true }).click()
}
async function rejectDraft(name, mutate, pattern) {
  await getApp()
  const before = structuredClone(ac.app),
    changed = structuredClone(before.draft)
  mutate(changed)
  const response = await ac.request('/nocode/application/save', saveBody(changed))
  assert.notEqual(response.code, 0, name + ' 应拒绝')
  assert.match(response.msg, pattern)
  assert.deepEqual(await getApp(), before, name + ' 不得产生半保存')
  await checked(name, { code: response.code, message: response.msg })
}
try {
  await persist()
  await ac.login()
  manifest.main ||= await ac.object('main', '默认表单主对象', [
    ac.field('name', 'TEXT', '验收名称'),
    ac.field('note', 'TEXT', '验收备注')
  ])
  await persist()
  manifest.legacy ||= await ac.object('legacy', '默认表单兼容对象', [
    ac.field('name', 'TEXT', '验收名称'),
    ac.field('note', 'TEXT', '验收备注')
  ])
  await persist()
  if (manifest.applicationId) {
    ac.app = await ac.api('/nocode/application/get?id=' + manifest.applicationId)
    assert.equal(ac.app.application.code, ac.prefix + '_app', '不得误用非本次夹具应用')
  } else {
    ac.app = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: ac.prefix + '_app',
      name: '默认表单验收 ' + ac.prefix,
      description: '专属自动验收夹具，可按 manifest 精确识别；不包含用户体验数据',
      definition: {
        objects: [definitionRef(manifest.main), definitionRef(manifest.legacy)],
        resources: [viewResource('legacy_view', manifest.legacy)]
      }
    })
    manifest.applicationId = appId()
    ac.owned.applications.push(appId())
    await persist()
  }
  await ac.share(manifest.main, ac.grant(manifest.main))
  await ac.share(manifest.legacy, ac.grant(manifest.legacy))
  if (!manifest.steps.baseline) {
    if (!ac.app.application.publishedVersion)
      ac.app = await ac.api('/nocode/application/publish', {
        id: appId(),
        expectedRevision: ac.app.application.revision,
        reason: '旧版 null 表单兼容基线'
      })
    manifest.baseline = await runtimeDefinition()
    manifest.baselineDraft = structuredClone(ac.app.draft)
    manifest.baselineVersion = ac.app.application.publishedVersion
    manifest.steps.baseline = true
    await persist()
  }
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({
    headless: process.env.DEFAULT_FORM_HEADED !== '1',
    channel: process.env.DEFAULT_FORM_BROWSER || 'chrome'
  })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const request = route.request(),
      path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (
      request.method() === 'POST' &&
      [
        '/nocode/application/save',
        '/nocode/application/publish',
        '/nocode/handling/submit',
        '/nocode/runtime/save'
      ].includes(path)
    ) {
      const body = request.postDataJSON()
      if ((body.applicationId || body.id) !== appId()) {
        blockedWrites.push({ path, reason: '拒绝写入未登记应用' })
        await route.abort('blockedbyclient')
        return
      }
    }
    await route.continue()
  })
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

  if (!manifest.steps.configured) {
    await workspace()
    if (!ac.app.draft.resources.some(r => r.code === 'list_a')) {
      await createView('list_a', '默认沿用列表')
      await expect(dialog()).toContainText('应用到草稿时自动生成')
      await dialog()
        .getByRole('button', { name: /预\s*览/ })
        .click()
      const preview = page
        .locator('.ant-modal-content:visible')
        .filter({ has: page.locator('.ant-modal-title').filter({ hasText: /^表单预览$/ }) })
      await expect(preview).toContainText('验收名称')
      await preview.locator('.ant-modal-close').click()
      await apply()
      await kind('业务表单')
      await expect(rows()).toHaveCount(1)
      await createView('list_b', '独立配置列表')
      await expect(dialog()).toContainText('沿用默认：')
      await apply()
      await kind('业务表单')
      await expect(rows()).toHaveCount(1)
      await checked('真实 UI 新建两个列表只生成一份默认表单，首次预览可用')

      await openView('list_a')
      await dialog().getByRole('button', { name: '新建并使用', exact: true }).click()
      await changeForm('取消验收临时表单', '临时保存')
      await apply('完成并返回列表配置')
      await expect(dialog()).toContainText('取消验收临时表单')
      await closeResource(true)
      await kind('业务表单')
      await expect(rows()).toHaveCount(1)
      await expect(rows()).not.toContainText('取消验收临时表单')
      assert.deepEqual((await getApp()).draft, manifest.baselineDraft)
      await checked('嵌套新建完成后取消外层列表，不遗留表单或写入服务器')

      await openView('list_a')
      await dialog().getByRole('button', { name: '编辑表单', exact: true }).click()
      await changeForm('验收默认表单', '保存默认记录')
      await apply('完成并返回列表配置')
      await expect(dialog().getByRole('textbox', { name: '资源名称', exact: true })).toHaveValue('默认沿用列表')
      await expect(dialog()).toContainText('沿用默认：验收默认表单')
      await apply()
      await openView('list_b')
      await expect(dialog()).toContainText('沿用默认：验收默认表单')
      await dialog().getByRole('button', { name: '复制并使用', exact: true }).click()
      await changeForm('验收独立表单', '保存独立记录')
      await apply('完成并返回列表配置')
      await expect(dialog()).toContainText('指定表单：验收独立表单')
      await apply()
      await checked('从列表编辑默认表单可返回；另一列表即时复用；复制并使用只改当前绑定')

      await kind('业务表单')
      await formMenu('验收默认表单', '复制表单')
      await changeForm('验收替换默认表单', '保存替换默认')
      await apply()
      await expect(rows()).toHaveCount(3)
      await formMenu('验收替换默认表单', '设为默认')
      const confirmation = page.locator('.ant-modal-confirm:visible')
      await expect(confirmation).toContainText('默认沿用列表')
      await expect(confirmation).not.toContainText('独立配置列表')
      await confirmation.getByRole('button', { name: '设为默认', exact: true }).click()
      await openView('list_a')
      await expect(dialog()).toContainText('沿用默认：验收替换默认表单')
      await closeResource()
      await openView('list_b')
      await expect(dialog()).toContainText('指定表单：验收独立表单')
      await closeResource()
      await kind('业务表单')
      await rowByName('验收替换默认表单').getByRole('button', { name: /删除$/ }).click()
      await page
        .locator('.ant-popconfirm:visible')
        .getByRole('button', { name: /确\s*定/ })
        .click()
      await expect(page.locator('.ant-message')).toContainText('请先将同对象的其他表单设为默认')
      await expect(rows()).toHaveCount(3)
      await expect(page.locator('.ant-popconfirm:visible')).toHaveCount(0)
      await page.screenshot({ path: resolve(output, '01-managed-default-forms.png'), fullPage: true })
      await checked('更换默认影响沿用列表，固定绑定保持不变；默认表单删除有明确保护')
      await saveDraft()
    }
    const resources = ac.app.draft.resources
    manifest.ids = Object.fromEntries(
      resources.filter(r => ['list_a', 'list_b', 'legacy_view'].includes(r.code)).map(r => [r.code, r.id])
    )
    manifest.ids.default = resources.find(r => r.name === '验收替换默认表单').id
    manifest.ids.fixed = resources.find(r => r.name === '验收独立表单').id
    assert.equal(resources.filter(r => r.kind === 'FORM' && r.config.options?.defaultForObject).length, 1)
    assert.equal(resources.find(r => r.code === 'list_a').config.formId, null)
    assert.equal(resources.find(r => r.code === 'list_b').config.formId, manifest.ids.fixed)
    assert.deepEqual(await runtimeDefinition(), manifest.baseline)
    assert.equal(ac.app.application.publishedVersion, manifest.baselineVersion)
    await checked('保存草稿保留默认用途及显式绑定；发布前运行版本和资源完全不变')
    manifest.steps.configured = true
    await persist()
  }
  if (!manifest.steps.published) {
    await workspace()
    await publish()
    manifest.published = await runtimeDefinition()
    assert.equal(
      manifest.published.resources.find(r => r.id === manifest.ids.default).config.options.defaultForObject,
      true
    )
    manifest.steps.published = true
    await checked('通过真实 UI 发布默认表单配置，发布快照包含唯一默认标记')
  }

  async function enterRuntime(object, viewId) {
    await page.goto(`${origin}/nocode-app/runtime?id=${appId()}&objectId=${object.objectId}&viewId=${viewId}`)
    await expect(page.locator('.business-records .ant-table')).toBeVisible()
  }
  async function saveRecord(surface, label, expectedFormId, object, name, flow) {
    const response = page.waitForResponse(
      r => r.url().endsWith('/nocode/handling/submit') && r.request().method() === 'POST'
    )
    await surface.locator('.os-form-surface').first().locator('input.ant-input').first().fill(name)
    await page.screenshot({ path: resolve(output, `runtime-${flow}.png`), fullPage: true, animations: 'disabled' })
    await surface.getByRole('button', { name: label, exact: true }).click()
    const received = await response,
      request = received.request().postDataJSON(),
      result = await received.json()
    assert.equal(result.code, 0, result.msg)
    assert.equal(request.formId || null, expectedFormId)
    assert.equal(request.applicationId, appId())
    assert.equal(request.objectId, object.objectId)
    assert.equal(result.data.outcome, 'EFFECTIVE')
    const row = result.data.result.record
    assert.equal(row.values[object.ids.name], name)
    if (!manifest.records.some(record => record.objectId === object.objectId && record.id === row.id))
      manifest.records.push({ objectId: object.objectId, id: row.id, flow })
    await persist()
    await expect(surface).toHaveCount(0)
    const saved = await ac.get(object, row)
    assert.equal(saved.record.values[object.ids.name], name)
    return row
  }
  if (!manifest.steps.records) {
    for (const test of [
      {
        code: 'list_a',
        object: manifest.main,
        formId: manifest.ids.default,
        label: '保存替换默认',
        name: '沿用默认真实记录'
      },
      {
        code: 'list_b',
        object: manifest.main,
        formId: manifest.ids.fixed,
        label: '保存独立记录',
        name: '独立表单真实记录'
      },
      { code: 'legacy_view', object: manifest.legacy, formId: null, label: '保存记录', name: '旧版自动真实记录' }
    ]) {
      await enterRuntime(test.object, manifest.ids[test.code])
      let saved = manifest.records.find(record => record.flow === test.code)
      if (!saved) {
        await page.locator('.business-records').getByRole('button', { name: /新增$/ }).click()
        const surface = page.locator('.ant-drawer-content:visible').filter({ hasText: '新建记录' })
        saved = await saveRecord(surface, test.label, test.formId, test.object, test.name, test.code)
      }
      const current = await ac.get(test.object, saved)
      if (current.record.values[test.object.ids.name] !== test.name + '已编辑') {
        const row = page.locator(`.business-records .ant-table-row[data-row-key="${saved.id}"]`)
        await row.getByRole('button', { name: /编辑$/ }).click()
        const editing = page.locator('.ant-drawer-content:visible').filter({ hasText: '编辑记录' })
        await saveRecord(editing, test.label, test.formId, test.object, test.name + '已编辑', test.code)
      }
      await checked(test.code + ' 真实新增/编辑传递正确 formId，HTTP 回读业务数据一致', {
        formId: test.formId,
        recordId: saved.id,
        objectId: test.object.objectId
      })
    }
    await page.screenshot({ path: resolve(output, '02-runtime-records.png'), fullPage: true })
    manifest.steps.records = true
    await persist()
  }
  if (!manifest.steps.draftIsolation) {
    await workspace()
    await openView('list_a')
    await dialog().getByRole('button', { name: '编辑表单', exact: true }).click()
    await changeForm('验收替换默认表单', '草稿待发布按钮')
    await apply('完成并返回列表配置')
    await apply()
    await saveDraft()
    assert.deepEqual(await runtimeDefinition(), manifest.published)
    await enterRuntime(manifest.main, manifest.ids.list_a)
    await page.locator('.business-records').getByRole('button', { name: /新增$/ }).click()
    const surface = page.locator('.ant-drawer-content:visible').filter({ hasText: '新建记录' })
    await expect(surface.getByRole('button', { name: '保存替换默认', exact: true })).toBeVisible()
    await expect(surface.getByRole('button', { name: '草稿待发布按钮', exact: true })).toHaveCount(0)
    await surface.locator('.ant-drawer-close').click()
    manifest.steps.draftIsolation = true
    await checked('已发布默认表单后续只改草稿不改变运行按钮和发布配置')
  }
  await rejectDraft(
    '跨对象显式表单绑定拒绝保存',
    draft => {
      draft.resources.find(r => r.id === manifest.ids.legacy_view).config.formId = manifest.ids.default
    },
    /表单.*对象|对象.*表单|同一.*对象/
  )
  await rejectDraft(
    '同对象重复默认拒绝保存',
    draft => {
      draft.resources.find(r => r.id === manifest.ids.fixed).config.options.defaultForObject = true
    },
    /默认表单|默认.*表单/
  )
  await rejectDraft(
    '沿用中的默认表单拒绝直接删除',
    draft => {
      draft.resources = draft.resources.filter(r => r.id !== manifest.ids.default)
    },
    /默认表单|默认.*表单/
  )
  await rejectDraft(
    '固定绑定的业务表单拒绝直接删除',
    draft => {
      draft.resources = draft.resources.filter(r => r.id !== manifest.ids.fixed)
    },
    /表单不存在|表单.*不存在|表单.*失效|引用/
  )
  if (!manifest.steps.pageCreate) {
    await getApp()
    if (!ac.app.draft.resources.some(resource => resource.id === 'default_form_page')) {
      const definition = structuredClone(ac.app.draft)
      const node = (id, type, extra = {}) => ({
        id,
        type,
        fieldId: null,
        resourceId: null,
        text: null,
        span: null,
        children: [],
        ...extra
      })
      definition.resources.push(
        {
          id: 'default_form_page',
          code: 'default_form_page',
          kind: 'PAGE',
          name: '默认表单按钮验收页',
          config: {
            protocolVersion: 2,
            contextObjectId: null,
            filters: [],
            nodes: [
              node('create_default', 'BUTTON', {
                text: '页面新增默认记录',
                display: { buttonType: 'PRIMARY' },
                action: { kind: 'CREATE', targetNodeId: 'default_list', openMode: 'DRAWER' }
              }),
              node('default_list', 'VIEW', { resourceId: manifest.ids.list_a })
            ]
          }
        },
        {
          id: 'default_form_page_menu',
          code: 'default_form_page_menu',
          kind: 'MENU',
          name: '默认表单按钮验收',
          config: { targetId: 'default_form_page' }
        }
      )
      ac.app = await ac.api('/nocode/application/save', saveBody(definition))
    }
    const currentRuntime = await runtimeDefinition()
    if (!currentRuntime.resources.some(resource => resource.id === 'default_form_page')) {
      await workspace()
      await publish()
    }
    manifest.pagePublishedVersion = ac.app.application.publishedVersion
    const submitText = ac.app.draft.resources.find(resource => resource.id === manifest.ids.default).config.options
      .submitText
    await page.goto(`${origin}/nocode-app/runtime?id=${appId()}&menu=default_form_page_menu`)
    const create = page.getByRole('button', { name: '页面新增默认记录', exact: true })
    await expect(create).toBeEnabled()
    let saved = manifest.records.find(record => record.flow === 'page_create')
    if (!saved) {
      await create.click()
      const surface = page.locator('.ant-drawer-content:visible').filter({ hasText: '页面新增默认记录' })
      saved = await saveRecord(
        surface,
        submitText,
        manifest.ids.default,
        manifest.main,
        '页面按钮默认表单真实记录',
        'page_create'
      )
    }
    const current = await ac.get(manifest.main, saved)
    assert.equal(current.record.values[manifest.main.ids.name], '页面按钮默认表单真实记录')
    manifest.steps.pageCreate = true
    await checked('页面 CREATE 按钮沿用列表的对象默认表单，真实提交携带默认 formId 并回读一致', {
      formId: manifest.ids.default,
      objectId: manifest.main.objectId,
      recordId: saved.id,
      publishedVersion: manifest.pagePublishedVersion
    })
  }
  await workspace()
  await kind('业务表单')
  await expect(rows()).toHaveCount(3)
  await page.screenshot({
    path: resolve(output, '01-managed-default-forms.png'),
    fullPage: true,
    animations: 'disabled'
  })
  await openView('list_a')
  await expect(dialog()).toContainText('沿用默认：验收替换默认表单')
  await page.screenshot({
    path: resolve(output, '03-view-inherits-default.png'),
    fullPage: true,
    animations: 'disabled'
  })
  await closeResource()
  assert.deepEqual(errors, [])
  assert.deepEqual(blockedWrites, [])
  manifest.steps.complete = true
  await checked('全程只操作 manifest 所属应用，无页面异常与越界写入')
} catch (error) {
  failure = error.stack || error.message
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  process.exitCode = 1
} finally {
  await browser?.close()
  await persist()
  ac.tokens = {}
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        applicationId: manifest.applicationId,
        checks: manifest.checks,
        errors,
        blockedWrites,
        failure
      },
      null,
      2
    )
  )
  console.log(
    JSON.stringify(
      { output, applicationId: manifest.applicationId, checks: manifest.checks.length, errors, blockedWrites, failure },
      null,
      2
    )
  )
}
