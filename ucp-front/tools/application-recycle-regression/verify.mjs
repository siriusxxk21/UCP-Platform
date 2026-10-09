import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 使用当前开发服务和正常登录；仅修改本次唯一前缀登记的夹具，不清理用户数据。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix = `ar${Date.now().toString(36)}`
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/application-recycle-regression', ac.prefix)
ac.output = out
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure, employee, app, consumer, object, record, locator, before
const shot = name => page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
const layout = async buttons => {
  const dimensions = await page.evaluate(() => ({
    width: window.innerWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth
  }))
  assert.ok(
    dimensions.document <= dimensions.width + 1 && dimensions.body <= dimensions.width + 1,
    '页面不能产生整体横向溢出'
  )
  for (const button of buttons) {
    await expect(button).toBeVisible()
    const box = await button.boundingBox()
    assert.ok(box && box.x >= 0 && box.x + box.width <= dimensions.width + 1, '操作按钮必须处于页面可达区域')
  }
}
const revision = detail => ({
  id: detail.application.id,
  expectedRevision: detail.application.revision,
  reason: ac.prefix + ' 回收站验收'
})
const detail = () => ac.api(`/nocode/application/get?id=${app.application.id}`)
const releases = async () =>
  (await ac.api(`/nocode/application/releases?id=${app.application.id}&pageNo=1&pageSize=100`)).list
const recyclePage = () => ac.api(`/nocode/application/recycle-page?pageNo=1&pageSize=100&search=${ac.prefix}`)
const rejected = async (path, body, pattern, token) => {
  const result = await ac.request(path, body, token)
  assert.notEqual(result.code, 0, path + ' 必须拒绝')
  assert.ok(result.http < 500, path + ' 必须为受控业务拒绝')
  if (pattern) assert.match(result.msg, pattern)
  return { code: result.code, message: result.msg }
}
const hidden = async () => {
  assert.equal(
    (await ac.api(`/nocode/application/page?pageNo=1&pageSize=100&search=${ac.prefix}`)).list.some(
      row => row.id === app.application.id
    ),
    false
  )
  assert.equal(
    (await ac.api('/nocode/runtime/mine')).some(row => row.id === app.application.id),
    false
  )
  assert.equal(
    (await ac.api('/nocode/task-entry/mine')).some(row => row.applicationId === app.application.id),
    false
  )
}
const runtimeBlocked = async () => {
  await rejected(
    `/nocode/runtime/model?applicationId=${app.application.id}&objectId=${object.objectId}`,
    undefined,
    /不存在|停用|未发布|回收/
  )
  await rejected('/nocode/task-entry/context', locator, /不存在|停用|未发布|回收/)
  await rejected(
    '/nocode/runtime/save',
    { ...ac.saveBody(object, { name: '不应写入' }), applicationId: app.application.id },
    /不存在|停用|未发布|回收/
  )
}
const sharedRecord = async () => {
  const value = await ac.api(
    `/nocode/runtime/get?applicationId=${consumer.application.id}&objectId=${object.objectId}&id=${record.id}`
  )
  assert.equal(value.record.values[object.ids.name], ac.prefix + ' 保留业务记录')
  assert.equal((await ac.api(`/nocode/application/object-version?id=${object.objectId}`)).checksum, object.checksum)
}

try {
  await ac.login()
  const ready = await recyclePage()
  assert.ok(Array.isArray(ready.list), '必须先启动本轮后端实现再创建夹具')
  object = await ac.object('data', '应用回收站 ' + ac.prefix, [ac.field('name', 'TEXT', '业务名称')])
  const grant = ac.grant(object)
  const entryGrant = { ...grant, actions: ['READ', 'CREATE'] }
  const reference = { objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }
  const resource = (id, kind, name, config) => ({ id, code: `${ac.prefix}_${id}`, kind, name, config })
  const form = resource('form', 'FORM', '回收站办理表单', {
    objectId: object.objectId,
    nodes: [{ id: 'name', type: 'FIELD', fieldId: object.ids.name, children: [], span: 12 }],
    detailIds: [],
    options: { layout: 'vertical', submitText: '保存记录' }
  })
  const entry = resource('entry', 'TASK_ENTRY', '回收站事项 ' + ac.prefix, {
    objectId: object.objectId,
    viewId: null,
    formId: 'form',
    mode: 'FORM',
    category: '回收站验收',
    description: '仅此批验收',
    icon: null,
    sortOrder: 1,
    limits: [entryGrant]
  })
  const create = async (suffix, resources) => {
    const result = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${ac.prefix}_${suffix}`,
      name: `回收站验收 ${suffix} ${ac.prefix}`,
      description: ac.prefix + ' 自有验收夹具',
      category: '回收站验收',
      definition: { objects: [reference], resources }
    })
    ac.owned.applications.push(result.application.id)
    ac.app = result
    await ac.persist()
    await ac.share(object, grant)
    return result
  }
  app = await create('main', [form, entry])
  const adminInfo = await ac.api('/system/auth/get-permission-info')
  await ac.api('/nocode/task-entry/policy', {
    applicationId: app.application.id,
    entryId: 'entry',
    expectedRevision: 0,
    enabled: true,
    members: [ac.member(adminInfo.user.id, [entryGrant])]
  })
  app = await ac.api('/nocode/application/publish', revision(app))
  ac.app = app
  record = await ac.save(object, { name: ac.prefix + ' 保留业务记录' })
  consumer = await create('shared', [])
  consumer = await ac.api('/nocode/application/publish', revision(consumer))
  ac.app = app
  before = await detail()
  const beforeReleases = await releases()
  const cards = await ac.api('/nocode/task-entry/mine')
  const card = cards.find(row => row.applicationId === app.application.id && row.entryId === 'entry')
  assert.ok(card, '删除前任务入口应可使用')
  locator = { applicationId: app.application.id, entryId: 'entry', version: card.version }
  await ac.api('/nocode/task-entry/context', locator)
  await sharedRecord()
  checks.push('真实发布应用与任务入口，另一应用引用同对象并可读取自有业务记录')

  employee = await ac.user('noadmin', undefined)
  await rejected('/nocode/application/recycle-page', undefined, /权限|访问|允许/, ac.tokens[employee])
  await rejected(
    '/nocode/application/delete-preview?id=' + app.application.id,
    undefined,
    /权限|访问|允许/,
    ac.tokens[employee]
  )
  await rejected('/nocode/application/delete', revision(app), /权限|访问|允许/, ac.tokens[employee])
  await rejected(
    '/nocode/application/delete',
    { ...revision(app), expectedRevision: app.application.revision - 1 },
    /变化|修改|刷新|修订/
  )
  assert.equal((await detail()).application.revision, before.application.revision)
  checks.push('无管理权限不能查看回收站、预览或删除；旧修订删除拒绝且不改变应用')

  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(25000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => {
    if (message.type() === 'error') errors.push(message.text())
  })
  page.on('response', response => {
    if (response.status() >= 400 && !response.url().includes('favicon'))
      errors.push(`HTTP ${response.status()} ${new URL(response.url()).pathname}`)
  })
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    // 正式 bootstrap 规范化 API 树形菜单；不把原始菜单直接写入平面 Store 缓存。
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, ac.tokens.admin)
  await page.goto(origin + '/nocode-app/application')
  await page.getByRole('textbox', { name: '搜索应用', exact: true }).fill(ac.prefix + '_missing')
  await page.getByRole('button', { name: /查询$/ }).click()
  await expect(page.locator('tr.ant-table-row')).toHaveCount(0)
  const resetResponse = page.waitForResponse(
    response =>
      response.url().includes('/nocode/application/page?') && !new URL(response.url()).searchParams.get('search')
  )
  await page.getByRole('button', { name: /重置$/ }).click()
  assert.equal((await (await resetResponse).json()).code, 0)
  await expect(page.getByRole('textbox', { name: '搜索应用', exact: true })).toHaveValue('')
  await page.getByRole('textbox', { name: '搜索应用', exact: true }).fill(ac.prefix + '_main')
  await page.getByRole('button', { name: /查询$/ }).click()
  const row = () => page.locator('tr.ant-table-row').filter({ hasText: app.application.code })
  await expect(row()).toHaveCount(1)
  await row().getByRole('button', { name: /删除$/ }).click()
  const dialog = () => page.locator('.ant-modal-content:visible')
  await expect(dialog().getByText(/业务数据保留/)).toBeVisible()
  await expect(dialog().getByRole('button', { name: '移入回收站' })).toBeDisabled()
  await dialog()
    .getByRole('button', { name: /^取\s*消$/ })
    .click()
  assert.equal((await detail()).application.revision, before.application.revision)
  await row().getByRole('button', { name: /删除$/ }).click()
  await dialog()
    .getByLabel('删除说明')
    .fill(ac.prefix + ' 应用下线验收')
  await expect(dialog().getByRole('button', { name: '移入回收站' })).toBeEnabled()
  await shot('01-delete-preview')
  const deletedResponse = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/delete') && response.request().method() === 'POST'
  )
  await dialog().getByRole('button', { name: '移入回收站' }).click()
  assert.equal((await (await deletedResponse).json()).code, 0)
  await expect(dialog()).toHaveCount(0)
  await expect(row()).toHaveCount(0)
  await hidden()
  await runtimeBlocked()
  await sharedRecord()
  checks.push('真实界面取消不删除；确认后管理、我的应用和任务入口移除，旧读写链接失效，共享数据保留')

  await page.getByRole('button', { name: /回收站$/ }).click()
  await expect(page.getByRole('textbox', { name: '搜索回收站应用' })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索回收站应用' }).fill(ac.prefix)
  await page.getByRole('button', { name: /查询$/ }).click()
  await expect(row()).toHaveCount(1)
  await expect(row()).toContainText('回收站验收')
  const recycled = (await recyclePage()).list.find(value => value.id === app.application.id)
  assert.ok(recycled.deletedAt && recycled.deletedBy && recycled.deletedByName)
  assert.equal(recycled.category, before.application.category)
  await page.reload()
  await expect(page.getByRole('textbox', { name: '搜索回收站应用' })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索回收站应用' }).fill(ac.prefix)
  await page.getByRole('button', { name: /查询$/ }).click()
  await expect(row()).toHaveCount(1)
  await page.setViewportSize({ width: 1280, height: 900 })
  await layout([page.getByRole('button', { name: /返回应用列表$/ }), row().getByRole('button', { name: /恢复$/ })])
  await page.getByTitle('列设置', { exact: true }).click()
  await page.getByRole('checkbox', { name: '应用编码', exact: true }).uncheck()
  await page.getByTitle('列设置', { exact: true }).click()
  await expect(page.getByRole('columnheader', { name: '应用编码', exact: true })).toHaveCount(0)
  await page.getByTitle('列设置', { exact: true }).click()
  await page.getByRole('checkbox', { name: '应用编码', exact: true }).check()
  await page.getByTitle('列设置', { exact: true }).click()
  await expect(page.getByRole('columnheader', { name: '应用编码', exact: true })).toBeVisible()
  await shot('02-recycle-bin')
  await rejected(
    '/nocode/application/recycle-restore',
    { id: recycled.id, expectedRevision: recycled.revision, reason: ac.prefix },
    /权限|访问|允许/,
    ac.tokens[employee]
  )
  await rejected(
    '/nocode/application/recycle-restore',
    { id: recycled.id, expectedRevision: recycled.revision - 1, reason: ac.prefix },
    /变化|修改|刷新|修订/
  )
  await row().getByRole('button', { name: /恢复$/ }).click()
  await dialog()
    .getByLabel('恢复说明')
    .fill(ac.prefix + ' 验证恢复后停用')
  const restoredResponse = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/recycle-restore') && response.request().method() === 'POST'
  )
  await dialog().getByRole('button', { name: '恢复并保持停用' }).click()
  assert.equal((await (await restoredResponse).json()).code, 0)
  await expect(dialog()).toHaveCount(0)
  await expect(row()).toHaveCount(0)
  const restored = await detail()
  assert.equal(restored.application.status, 'DISABLED')
  assert.equal(restored.application.recoveryPending, true)
  assert.equal(restored.application.recoveryNeedsEdit, true)
  assert.deepEqual(restored.draft, before.draft)
  assert.deepEqual(await releases(), beforeReleases)
  assert.equal(restored.application.publishedVersion, before.application.publishedVersion)
  await runtimeBlocked()
  await sharedRecord()
  checks.push('回收站刷新保留页面、显示原分类及删除审计；恢复保留草稿/版本/业务数据，状态停用且旧入口仍拒绝')

  await rejected('/nocode/application/status', { ...revision(restored), status: 'ACTIVE' }, /发布|编辑|恢复/)
  await rejected('/nocode/application/publish', revision(restored), /发布|编辑|恢复|停用/)
  await rejected('/nocode/application/restore', { ...revision(restored), sourceVersion: 1 }, /发布|编辑|恢复|停用/)
  await rejected('/nocode/application/publish-and-enable', revision(restored), /编辑|保存/)
  assert.equal((await detail()).application.revision, restored.application.revision)
  checks.push('直接启用、普通发布、历史版本恢复及未编辑发布启用都无法绕过恢复门禁')

  const unchanged = await ac.api('/nocode/application/save', {
    id: restored.application.id,
    expectedRevision: restored.application.revision,
    code: restored.application.code,
    name: restored.application.name,
    category: restored.application.category,
    description: restored.application.description,
    icon: restored.application.icon,
    definition: restored.draft
  })
  assert.equal(unchanged.application.recoveryNeedsEdit, true)
  await rejected('/nocode/application/publish-and-enable', revision(unchanged), /编辑|保存/)
  checks.push('原样保存草稿不会解除人工编辑门禁')

  await page.getByRole('button', { name: '前往编辑', exact: true }).click()
  await expect(page.getByRole('heading', { name: app.application.name, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /发布并启用$/ })).toBeDisabled()
  await layout([page.getByRole('button', { name: /发布并启用$/ }), page.getByRole('button', { name: /保存草稿$/ })])
  await shot('03-restored-workspace-1280')
  await expect(page.getByRole('button', { name: '运行应用', exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '基本设置', exact: true }).click()
  await page.getByRole('textbox', { name: '应用名称', exact: true }).fill(app.application.name + ' 已复核')
  const savedResponse = page.waitForResponse(
    response => response.url().endsWith('/nocode/application/save') && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await savedResponse).json()).code, 0)
  const edited = await detail()
  assert.equal(edited.application.status, 'DISABLED')
  assert.equal(edited.application.recoveryPending, true)
  assert.equal(edited.application.recoveryNeedsEdit, false)
  const editedReleases = await releases()
  await runtimeBlocked()
  await ac.share(object, null)
  await rejected('/nocode/application/publish-and-enable', revision(edited), /共享|授权/)
  const failedPublication = await detail()
  assert.equal(failedPublication.application.status, 'DISABLED')
  assert.equal(failedPublication.application.recoveryPending, true)
  assert.equal(failedPublication.application.revision, edited.application.revision)
  assert.deepEqual(await releases(), editedReleases)
  await sharedRecord()
  await ac.share(object, grant)
  checks.push('撤销自有应用共享授权后发布启用失败，停用状态/修订/版本完整回滚，另一应用仍能读取业务数据')
  await shot('03-restored-edited-disabled')
  await page.getByRole('button', { name: /发布并启用$/ }).click()
  await dialog()
    .getByLabel('发布说明')
    .fill(ac.prefix + ' 人工复核后重新发布启用')
  const publishedResponse = page.waitForResponse(
    response =>
      response.url().endsWith('/nocode/application/publish-and-enable') && response.request().method() === 'POST'
  )
  await dialog().getByRole('button', { name: '发布并启用', exact: true }).click()
  assert.equal((await (await publishedResponse).json()).code, 0)
  await expect(dialog()).toHaveCount(0)
  await expect(page.getByRole('button', { name: '运行应用', exact: true })).toBeVisible()
  const active = await detail()
  assert.equal(active.application.status, 'ACTIVE')
  assert.equal(active.application.recoveryPending, false)
  assert.equal(active.application.recoveryNeedsEdit, false)
  assert.equal(active.application.publishedVersion, before.application.publishedVersion + 1)
  assert.equal(
    (await ac.api('/nocode/runtime/mine')).some(value => value.id === app.application.id),
    true
  )
  const activeCard = (await ac.api('/nocode/task-entry/mine')).find(value => value.applicationId === app.application.id)
  assert.equal(activeCard.version, active.application.publishedVersion)
  await ac.api('/nocode/task-entry/context', { ...locator, version: activeCard.version })
  await sharedRecord()
  await shot('04-republished-enabled')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  assert.deepEqual(errors, [])
  checks.push('真实人工编辑保存后仍停用，发布启用生成新版本再开放应用及任务入口；共享业务数据完整')

  // 独立空应用批次确保回收站有两页；只恢复本批第二页唯一一项。
  for (let index = 0; index < 11; index++) {
    const blank = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${ac.prefix}_page_${index}`,
      name: `回收站分页 ${ac.prefix} ${index}`,
      category: '回收站验收',
      description: ac.prefix + ' 分页自有夹具',
      definition: { objects: [], resources: [] }
    })
    ac.owned.applications.push(blank.application.id)
    await ac.persist()
    await ac.api('/nocode/application/delete', revision(blank))
  }
  await page.goto(origin + '/nocode-app/application?recycle=1')
  await page.getByRole('textbox', { name: '搜索回收站应用' }).fill(ac.prefix + '_page_')
  await page.getByRole('button', { name: /查询$/ }).click()
  await expect(page.locator('tr.ant-table-row')).toHaveCount(10)
  await page.locator('.ant-pagination-item-2').click()
  await expect(page.locator('tr.ant-table-row')).toHaveCount(1)
  await page.locator('tr.ant-table-row').getByRole('button', { name: /恢复$/ }).click()
  await dialog()
    .getByLabel('恢复说明')
    .fill(ac.prefix + ' 分页回退验收')
  await dialog().getByRole('button', { name: '恢复并保持停用' }).click()
  await expect(dialog()).toHaveCount(0)
  await expect(page.locator('.ant-pagination-item-active')).toHaveAttribute('title', '1')
  await expect(page.locator('tr.ant-table-row')).toHaveCount(10)
  await layout([page.getByRole('button', { name: /返回应用列表$/ })])
  await shot('05-recycle-pagination-recovery-1280')
  await page.getByRole('textbox', { name: '搜索回收站应用' }).fill(ac.prefix + '_missing')
  await page.getByRole('button', { name: /查询$/ }).click()
  await expect(page.locator('tr.ant-table-row')).toHaveCount(0)
  const recycleReset = page.waitForResponse(
    response =>
      response.url().includes('/nocode/application/recycle-page?') &&
      !new URL(response.url()).searchParams.get('search')
  )
  await page.getByRole('button', { name: /重置$/ }).click()
  assert.equal((await (await recycleReset).json()).code, 0)
  await expect(page.getByRole('textbox', { name: '搜索回收站应用' })).toHaveValue('')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  assert.deepEqual(errors, [])
  checks.push('1280 宽回收站和恢复工作区无页面横向溢出且按钮可达；查询重置、列设置及第二页恢复末项自动返回第一页')
} catch (error) {
  failure = error
  if (page) {
    await shot('failure').catch(() => {})
    await writeFile(resolve(out, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  await browser?.close()
  if (employee)
    await ac.api('/system/user/update-status', { id: employee, status: 1 }, undefined, 'PUT').catch(error => {
      errors.push('临时成员停用失败：' + error.message)
      failure ||= error
    })
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await mkdir(out, { recursive: true })
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify(
      { time: new Date().toISOString(), prefix: ac.prefix, checks, errors, failure: failure?.message },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output: out, checks: checks.length, errors, failure: failure?.message }))
}
if (failure) throw failure
