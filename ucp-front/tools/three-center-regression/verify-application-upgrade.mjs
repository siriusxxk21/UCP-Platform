import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅创建和更新本批精确登记的对象及 A/B 应用，不触碰用户体验数据，不创建额外账号或角色。
const fixtureArgument = process.argv.indexOf('--fixture')
const previous =
  fixtureArgument < 0 ? null : JSON.parse(await readFile(resolve(process.argv[fixtureArgument + 1]), 'utf8'))
const prefix = previous?.prefix || `e2efc${Date.now().toString(36)}${randomBytes(2).toString('hex')}`
const output = resolve(previous?.output || process.env.NOCODE_VERIFY_OUTPUT || `.work/application-upgrade/${prefix}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
ac.prefix = prefix
ac.owned = previous?.owned || ac.owned
const state = previous || { prefix, output, owned: ac.owned, apps: {}, records: [], checks: [] }
await mkdir(output, { recursive: true })
await ac.login()
async function persist() {
  state.owned = ac.owned
  await writeFile(resolve(output, 'fixture.json'), JSON.stringify(state, null, 2))
  await writeFile(
    resolve(output, 'cleanup-manifest.json'),
    JSON.stringify(
      {
        batchPrefix: prefix + '_',
        objects: ac.owned.objects.map(object => ({
          ...object,
          name: '表单验收字段转换 ' + prefix,
          tableName: 'biz_' + object.code
        })),
        applications: Object.values(state.apps).map(app => ({
          id: app.application.id,
          code: app.application.code,
          name: app.application.name
        }))
      },
      null,
      2
    )
  )
}
ac.persist = persist
async function checked(name, details) {
  state.checks = state.checks.filter(check => check.name !== name)
  state.checks.push({ name, details })
  await persist()
}
const revision = (app, reason) => ({ id: app.application.id, expectedRevision: app.application.revision, reason })
const application = id => ac.api(`/nocode/application/get?id=${id}`)
const design = () => ac.api(`/nocode/design/get?id=${state.source.objectId}`)
const rows = () => ac.api('/nocode/object-data/page', { objectId: state.source.objectId, pageNo: 1, pageSize: 100 })
function resources(object, name) {
  const ids = object.definition.fields.map(field => field.id)
  return [
    {
      id: 'form',
      kind: 'FORM',
      code: 'edit_form',
      name: name + '录入',
      config: {
        objectId: object.objectId,
        nodes: ids.map(fieldId => ({ id: 'field_' + fieldId, type: 'FIELD', fieldId, children: [], span: 12 })),
        detailIds: [],
        options: { layout: 'vertical', submitText: '保存' }
      }
    },
    {
      id: 'view',
      kind: 'VIEW',
      code: 'data_view',
      name: name + '列表',
      config: {
        objectId: object.objectId,
        fieldIds: ids,
        equal: {},
        sortFieldId: null,
        descending: false,
        pageSize: 10,
        formId: 'form',
        list: { queryFieldIds: [ids[0]], advancedFieldIds: [ids[0]], columnWidths: {}, batchDelete: false }
      }
    },
    { id: 'menu', kind: 'MENU', code: 'data_menu', name: name + '入口', config: { targetId: 'view' } }
  ]
}
let browser, page, failure
const pageErrors = []
try {
  state.source ||= await ac.object('source', '字段转换 ' + prefix, [
    ac.field('name', 'TEXT', '记录名称'),
    ac.field('empty_value', 'TEXT', '空白列'),
    ac.field('numeric_text', 'TEXT', '待转换数字'),
    ac.field('keep_note', 'TEXT', '保留备注')
  ])
  await persist()
  for (const name of ['A', 'B']) {
    if (!state.apps[name]) {
      state.apps[name] = await ac.api('/nocode/application/save', {
        id: null,
        expectedRevision: null,
        code: `${prefix}_app_${name.toLowerCase()}`,
        name: `字段升级应用 ${name} ${prefix}`,
        definition: {
          objects: [
            { objectId: state.source.objectId, versionNo: state.source.versionNo, checksum: state.source.checksum }
          ],
          resources: resources(state.source, `应用 ${name}`)
        }
      })
      ac.owned.applications.push(state.apps[name].application.id)
      await persist()
      ac.app = state.apps[name]
      await ac.share(state.source, ac.grant(state.source))
      state.apps[name] = await ac.api(
        '/nocode/application/publish',
        revision(state.apps[name], '独立维护升级验收：初始版本')
      )
      await persist()
    }
  }
  const source = state.source
  if (!state.seedComplete) {
    const model = await ac.api(`/nocode/object-data/model?objectId=${source.objectId}`)
    for (let index = state.records.length; index < 3; index++) {
      const result = await ac.api('/nocode/object-data/save', {
        objectId: source.objectId,
        versionNo: model.versionNo,
        checksum: model.checksum,
        id: null,
        expectedRevision: null,
        values: ac.values(source, {
          name: `维护升级记录 ${index + 1}`,
          empty_value: null,
          numeric_text: String((index + 1) * 12),
          keep_note: `必须保留 ${index + 1}`
        }),
        requestKey: randomUUID()
      })
      state.records.push(result.record)
      await persist()
    }
    state.seedComplete = true
    await persist()
  }
  if (!state.draftCreated) {
    const current = await design()
    if (current.draft.state !== 'DRAFT')
      await ac.api('/nocode/design/edit', {
        id: source.objectId,
        expectedLockVersion: current.draft.lockVersion,
        reason: '独立维护升级验收：编辑草稿'
      })
    state.draftCreated = true
    await persist()
  }
  if (process.argv.includes('--setup-only')) {
    console.log(
      JSON.stringify({
        fixture: resolve(output, 'fixture.json'),
        objectId: source.objectId,
        applicationIds: Object.fromEntries(Object.entries(state.apps).map(([key, app]) => [key, app.application.id]))
      })
    )
  } else {
    browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
    page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    page.on('pageerror', error => pageErrors.push(error.message))
    await page.addInitScript(token => {
      localStorage.setItem('token', token)
      for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    }, ac.tokens.admin)
    const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
    const dialog = title =>
      page.locator('.ant-modal-content:visible, .ant-drawer-content:visible').filter({
        has: page.locator('.ant-modal-title, .ant-drawer-title').filter({ hasText: title })
      })
    const screenshot = name =>
      page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled' })
    await page.goto(`${origin}/nocode/object/editor?id=${source.objectId}`)
    if (!state.changed) {
      const numericRow = page
        .locator('tr')
        .filter({ has: page.getByRole('textbox', { name: '第 3 行字段名称', exact: true }) })
      await numericRow.getByRole('button', { name: /配置/ }).click()
      const fieldDialog = dialog(/^字段配置$/)
      await fieldDialog
        .locator('.ant-form-item')
        .filter({ has: page.locator('label').filter({ hasText: /^字段类型$/ }) })
        .locator('.ant-select')
        .click()
      await page.locator('.ant-select-dropdown:visible').getByText('整数', { exact: true }).click()
      const review = dialog(/^检查字段变更/)
      await expect(review).toContainText('本列 3 条有值')
      await expect(review.getByRole('button', { name: '确认变更方案', exact: true })).toBeEnabled()
      await screenshot('01-字段转换保留旧值与应用影响')
      await review.getByRole('button', { name: '确认变更方案', exact: true }).click()
      await fieldDialog.getByRole('button', { name: '应用到草稿', exact: true }).click()
      const saved = page.waitForResponse(response => response.url().includes('/nocode/design/save'))
      await page.getByRole('button', { name: /保存草稿/ }).click()
      assert.equal((await (await saved).json()).code, 0)
      state.changed = true
      await checked('字段转换应用到草稿，保存尚不改变物理数据或运行应用', {
        A: (await application(state.apps.A.application.id)).application.status,
        B: (await application(state.apps.B.application.id)).application.status
      })
    }
    if (!state.published) {
      const current = await design()
      const firstPlan = await ac.api('/nocode/design/plan', {
        id: source.objectId,
        expectedLockVersion: current.draft.lockVersion
      })
      assert.equal(firstPlan.state, 'PENDING')
      assert.deepEqual(
        firstPlan.applicationUpgrades.map(app => app.applicationId).sort(),
        [state.apps.A.application.id, state.apps.B.application.id].sort()
      )
      const unconfirmed = await ac.request('/nocode/design/execute', {
        planId: firstPlan.id,
        reason: '验收遗漏暂停确认应被拒绝',
        clearFieldIds: [],
        suspendApplicationIds: []
      })
      assert.notEqual(unconfirmed.code, 0)
      assert.match(unconfirmed.msg, /确认暂停/)
      for (const app of Object.values(state.apps))
        assert.equal((await application(app.application.id)).application.status, 'ACTIVE')
      assert.equal((await design()).publishedVersion, 1)
      await checked('API 未确认暂停拒绝执行，A/B 仍运行、对象仍为 V1', {
        code: unconfirmed.code,
        message: unconfirmed.msg
      })

      await page.getByRole('button', { name: /发\s*布$/ }).click()
      const publishDialog = dialog(/^发布确认$/)
      await expect(publishDialog).toContainText('将暂停 2 个应用')
      await expect(publishDialog).toContainText(state.apps.A.application.name)
      await expect(publishDialog).toContainText(state.apps.B.application.name)
      await publishDialog.locator('textarea').fill('独立维护升级验收：明确暂停两个应用后转换字段')
      const executions = []
      const onExecute = request => {
        if (request.url().includes('/nocode/design/execute')) executions.push(request)
      }
      page.on('request', onExecute)
      await publishDialog.getByRole('button', { name: '暂停 2 个应用并发布', exact: true }).click()
      await expect(publishDialog).toContainText('确认暂停')
      assert.equal(executions.length, 0, '未勾选暂停确认时浏览器不得发出执行请求')
      page.off('request', onExecute)
      await screenshot('02-发布前逐应用暂停确认')
      await publishDialog.getByRole('checkbox', { name: /我确认暂停以上 2 个应用/ }).check()
      const executed = page.waitForResponse(response => response.url().includes('/nocode/design/execute'))
      await publishDialog.getByRole('button', { name: '暂停 2 个应用并发布', exact: true }).click()
      const result = await (await executed).json()
      assert.equal(result.code, 0)
      assert.equal(result.data.state, 'SUCCEEDED')
      await expect(page.getByText('对象已发布，受影响应用已停用', { exact: true })).toBeVisible()
      await screenshot('03-对象发布后应用分别处理入口')
      state.published = true
      await persist()
    }
    const appA = await application(state.apps.A.application.id)
    let appB = await application(state.apps.B.application.id)
    assert.equal(appA.application.status, 'DISABLED')
    if (!state.resumedB) assert.equal(appB.application.status, 'DISABLED')
    const deniedA = await ac.request(`/nocode/runtime/application?id=${appA.application.id}`)
    assert.notEqual(deniedA.code, 0)
    assert.match(deniedA.msg, /停用/)
    await checked('发布后 A/B 整个应用暂停，A 运行接口拒绝', { message: deniedA.msg })
    if (!state.resumedB) {
      const deniedB = await ac.request(`/nocode/runtime/application?id=${appB.application.id}`)
      assert.notEqual(deniedB.code, 0)
      assert.match(deniedB.msg, /停用/)
      await page.goto(`${origin}/nocode-app/workspace?id=${appB.application.id}`)
      await expect(page.getByRole('button', { name: /发布并启用/ })).toBeVisible()
      if (appB.draft.objects[0].versionNo < 2) {
        const sync = page.getByRole('button', { name: '同步最新版本', exact: true })
        await expect(sync).toBeEnabled()
        await sync.click()
        await expect(page.getByText('有未保存修改', { exact: true })).toBeVisible()
        const savedB = page.waitForResponse(response => response.url().includes('/nocode/application/save'))
        await page.getByRole('button', { name: /保存草稿/ }).click()
        assert.equal((await (await savedB).json()).code, 0)
      }
      await expect(page.getByText('有未保存修改', { exact: true })).toHaveCount(0)
      await page.getByRole('button', { name: /发布并启用/ }).click()
      const enableDialog = dialog(/^发布并启用应用$/)
      await expect(enableDialog).toContainText('启用整个应用')
      await enableDialog
        .getByRole('textbox', { name: '发布说明', exact: true })
        .fill('应用 B 已同步字段类型，独立发布并启用')
      await expect(enableDialog.getByRole('button', { name: '发布并启用', exact: true })).toBeEnabled()
      await screenshot('04-B同步后发布并启用整个应用')
      const enabled = page.waitForResponse(response =>
        response.url().includes('/nocode/application/publish-and-enable')
      )
      await enableDialog.getByRole('button', { name: '发布并启用', exact: true }).click()
      assert.equal((await (await enabled).json()).code, 0)
      state.resumedB = true
      await persist()
    }
    appB = await application(appB.application.id)
    assert.equal(appB.application.status, 'ACTIVE')
    assert.equal(appB.draft.objects[0].versionNo, 2)
    assert.equal((await application(appA.application.id)).application.status, 'DISABLED')
    const runningB = await ac.api(`/nocode/runtime/application?id=${appB.application.id}`)
    assert.equal(runningB.definition.objects[0].versionNo, 2)
    await page.goto(`${origin}/nocode-app/runtime?id=${appB.application.id}`)
    await expect(page.getByText('维护升级记录 1', { exact: true })).toBeVisible()
    await screenshot('05-B新版本可运行且共享记录保留')
    await page.goto(`${origin}/nocode-app/runtime?id=${appA.application.id}`)
    await expect(page.locator('.application-runtime .ant-alert')).toContainText('停用')
    await screenshot('06-A仍保持停用')
    const currentRows = await rows()
    const currentModel = await ac.api(`/nocode/object-data/model?objectId=${source.objectId}`)
    assert.equal(currentModel.columnTypes[source.ids.numeric_text], 'bigint')
    assert.equal(currentRows.total, state.records.length)
    for (const original of state.records) {
      const current = currentRows.list.find(record => record.id === original.id)
      assert.ok(current)
      assert.equal(current.values[source.ids.name], original.values[source.ids.name])
      assert.equal(current.values[source.ids.keep_note], original.values[source.ids.keep_note])
      assert.equal(String(current.values[source.ids.numeric_text]), String(original.values[source.ids.numeric_text]))
    }
    assert.deepEqual(pageErrors, [])
    await checked('B 单独恢复运行，A 保持暂停，原记录 ID、数字含义及其他列保持', {
      A: 'DISABLED',
      B: 'ACTIVE',
      recordCount: currentRows.total
    })
    await writeFile(
      resolve(output, 'result.json'),
      JSON.stringify(
        {
          time: new Date().toISOString(),
          passed: true,
          objectId: source.objectId,
          applicationIds: { A: appA.application.id, B: appB.application.id },
          checks: state.checks,
          pageErrors
        },
        null,
        2
      )
    )
    console.log(JSON.stringify({ passed: true, output, checks: state.checks.length }))
  }
} catch (error) {
  failure = error
  await page
    ?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true, animations: 'disabled' })
    .catch(() => {})
  await writeFile(
    resolve(output, 'failure.json'),
    JSON.stringify(
      { time: new Date().toISOString(), message: error.message, checks: state.checks, pageErrors },
      null,
      2
    )
  )
  console.error(error.message)
} finally {
  await browser?.close()
  await persist()
}
if (failure) throw failure
