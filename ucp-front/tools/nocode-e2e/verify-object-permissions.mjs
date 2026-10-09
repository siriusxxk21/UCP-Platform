import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 直接在当前开发环境验证；只修改本次登记的对象和应用，已有对象仅用于只读引用分页。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/application-object-permissions', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, failure
const ref = ({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum })
const hasExternalReference = value =>
  value &&
  typeof value === 'object' &&
  Object.entries(value).some(([key, item]) => (key === 'targetObjectId' && !!item) || hasExternalReference(item))
try {
  await ac.login()
  const original = await ac.object('permissions', '引用对象权限', [
    ac.field('name', 'TEXT', '名称'),
    ac.field('status', 'TEXT', '状态')
  ])
  const design = await ac.api(`/nocode/design/get?id=${original.objectId}`)
  const edit = await ac.api('/nocode/design/edit', {
    id: design.draft.id,
    expectedLockVersion: design.draft.lockVersion,
    reason: '引用对象版本对照验收'
  })
  const saved = await ac.api('/nocode/design/save', {
    draft: {
      id: edit.draft.id,
      objectCode: edit.draft.objectCode,
      objectName: edit.draft.objectName,
      description: edit.draft.description,
      tableName: edit.draft.tableName,
      expectedLockVersion: edit.draft.lockVersion,
      titleFieldKey: edit.draft.titleFieldId,
      fields: [...edit.draft.fields.map(f => ({ ...f, key: f.id })), ac.field('note', 'TEXT', '备注')],
      removedFieldIds: []
    },
    settings: edit.settings,
    fieldOptions: edit.fieldOptions,
    relations: edit.relations,
    indexes: edit.indexes,
    details: edit.details
  })
  const plan = await ac.api('/nocode/design/plan', { id: saved.draft.id, expectedLockVersion: saved.draft.lockVersion })
  assert.equal(plan.checks.filter(check => check.blocking).length, 0)
  assert.equal(
    (await ac.api('/nocode/design/execute', { planId: plan.id, reason: '引用版本对照验收V2' })).state,
    'SUCCEEDED'
  )
  const latest = await ac.api(`/nocode/application/object-version?id=${original.objectId}`)
  assert.equal(latest.versionNo, 2)
  const candidates = await ac.api('/nocode/design/page?pageNo=1&pageSize=100&status=ACTIVE')
  const extra = []
  for (const candidate of candidates.list) {
    if (!candidate.publishedVersion || candidate.id === original.objectId) continue
    const published = await ac.request(`/nocode/application/object-version?id=${candidate.id}`)
    if (published.code === 0 && !hasExternalReference(published.data.definition)) extra.push(ref(published.data))
    if (extra.length === 10) break
  }
  assert.equal(extra.length, 10, '当前开发库需要十个可只读引用的已发布对象以验证第二页')
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_permissions',
    name: '引用对象权限验收 ' + ac.prefix,
    description: '仅本次验证夹具',
    definition: { objects: [ref(original), ...extra], resources: [] }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.share(original, ac.grant(original, { actions: ['READ'], writeFields: [] }))
  await ac.persist()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1600, height: 1050 } })
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
  await page.goto(`${origin}/nocode-app/workspace?id=${ac.app.application.id}`)
  const table = page.locator('.workspace .nocode-embedded-table').first()
  const rows = table.locator('tbody tr[data-row-key]')
  const first = () => table.locator(`tbody tr[data-row-key="${original.objectId}"]`)
  await expect(rows).toHaveCount(10)
  await expect(page.getByText('应用的数据基础', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '对象授权范围', exact: true })).toHaveCount(0)
  await expect(table.getByRole('columnheader', { name: '最新发布版本' })).toBeVisible()
  await expect(first()).toContainText('待同步')
  await expect(first().locator('td').nth(2)).toHaveText('V1')
  await expect(first().locator('td').nth(3)).toContainText('V2')
  await first().getByRole('button', { name: '配置数据权限', exact: true }).hover()
  await expect(page.getByRole('tooltip')).toContainText('配置当前应用的数据权限')
  await page.mouse.move(350, 150)
  await page.screenshot({ path: resolve(output, '01-references.png'), fullPage: true, animations: 'disabled' })
  await table.locator('.ant-pagination-item-2').click()
  await expect(rows).toHaveCount(1)
  await expect(rows.first().locator('td').first()).toHaveText('11')
  await table.locator('.ant-pagination-item-1').click()
  await expect(rows).toHaveCount(10)
  checks.push('真实11个引用：第一页10行、第二页序号11；图标悬浮说明、旧页签和说明已移除')

  const latestUrl = url =>
    url.pathname.endsWith('/nocode/application/object-version') &&
    url.searchParams.get('id') === original.objectId &&
    !url.searchParams.has('versionNo')
  await page.route(latestUrl, route => route.abort('failed'), { times: 1 })
  await table.getByRole('button', { name: '刷新最新发布版本', exact: true }).click()
  await expect(first().getByRole('button', { name: '读取失败 · 重试' })).toBeVisible()
  await expect(first().getByRole('button', { name: '同步最新版本', exact: true })).toBeDisabled()
  await first().getByRole('button', { name: '读取失败 · 重试' }).click()
  await expect(first()).toContainText('待同步')
  await first().getByRole('button', { name: '同步最新版本', exact: true }).click()
  await expect(first().locator('td').nth(2)).toHaveText('V2')
  await expect(first().getByRole('button', { name: '同步最新版本', exact: true })).toBeDisabled()
  await expect(page.locator('.workspace-header')).toContainText('有未保存修改')
  assert.equal((await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)).draft.objects[0].versionNo, 1)
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  await expect(page.locator('.workspace-header')).not.toContainText('有未保存修改')
  assert.equal((await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)).draft.objects[0].versionNo, 2)
  checks.push('真实V1/V2对照；最新读取失败不误报最新；重试可恢复；同步只改草稿、保存后才写入固定引用')

  await first().getByRole('button', { name: '配置数据权限', exact: true }).click()
  const editor = page.locator('.ant-drawer-content').filter({ has: page.getByText('当前应用', { exact: true }) })
  await expect(editor).toBeVisible()
  await expect(editor).toContainText(ac.app.application.name)
  await expect(editor.getByRole('combobox', { name: '授权应用', exact: true })).toHaveCount(0)
  await expect(editor.getByText('查看的记录条件', { exact: true })).not.toBeVisible()
  await editor.getByRole('checkbox', { name: '新增', exact: true }).check()
  await editor.getByRole('button', { name: /关\s*闭/ }).click()
  await expect(page.getByText('放弃未保存的权限修改？', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(editor.getByRole('checkbox', { name: '新增', exact: true })).toBeChecked()
  const readFields = editor
    .locator('.grant-field-select')
    .filter({ has: page.getByRole('combobox', { name: '可查看字段', exact: true }) })
  const writeFields = editor
    .locator('.grant-field-select')
    .filter({ has: page.getByRole('combobox', { name: '可填写和修改字段', exact: true }) })
  await readFields.getByRole('button', { name: '全选可用项', exact: true }).click()
  await writeFields.getByRole('button', { name: '全选可用项', exact: true }).click()
  await editor.getByLabel('变更说明', { exact: true }).fill('本次夹具：新增与字段批选验证')
  await page.screenshot({ path: resolve(output, '02-permission-editor.png'), fullPage: true, animations: 'disabled' })
  const saveResponse = page.waitForResponse(
    response => response.url().endsWith('/nocode/object-sharing/save') && response.request().method() === 'POST'
  )
  await editor.getByRole('button', { name: '保存权限', exact: true }).click()
  assert.equal((await (await saveResponse).json()).code, 0)
  await expect(editor).toHaveCount(0)
  const grants = await ac.api(`/nocode/object-sharing/application?id=${ac.app.application.id}`)
  const grant = grants.find(item => item.objectId === original.objectId)
  assert.deepEqual(new Set(grant.permission.actions), new Set(['READ', 'CREATE']))
  assert.equal(grant.permission.readFields.length, 3)
  assert.equal(grant.permission.writeFields.length, 3)
  await first().getByRole('button', { name: '配置数据权限', exact: true }).click()
  await expect(editor.getByRole('checkbox', { name: '新增', exact: true })).toBeChecked()
  await expect(editor.getByText('有未保存修改', { exact: true })).toHaveCount(0)
  await editor.getByText('高级设置 · 记录条件与计算取数', { exact: true }).click()
  await expect(editor.getByText('查看的记录条件', { exact: true })).toBeVisible()
  await editor.getByRole('button', { name: /关\s*闭/ }).click()
  await expect(editor).toHaveCount(0)
  await expect(page.getByText('放弃未保存的权限修改？', { exact: true })).toHaveCount(0)
  checks.push('当前应用对象直达授权；字段批选真实保存并回读；关闭保护、保存后无误报、展开高级设置不产生脏状态')

  await page.setViewportSize({ width: 1280, height: 900 })
  await first().getByRole('button', { name: '配置数据权限', exact: true }).click()
  await expect(editor.getByRole('button', { name: '保存权限', exact: true })).toBeInViewport()
  await page.screenshot({ path: resolve(output, '03-permission-1280.png'), fullPage: true, animations: 'disabled' })
  await editor.getByRole('button', { name: /关\s*闭/ }).click()
  // 模拟只有应用查询权限的前端身份，读取仍走真实API；这里只验证只读呈现，不声称后端越权验收。
  const limitedInfo = { ...info, permissions: ['nocode:app:query', 'nocode:object:query'], roles: [] }
  await page.route('**/system/auth/get-permission-info', route =>
    route.fulfill({ json: { code: 0, data: limitedInfo } })
  )
  await page.addInitScript(info => {
    localStorage.setItem('permissions', JSON.stringify(info.permissions))
    localStorage.setItem('roles', JSON.stringify(info.roles))
  }, limitedInfo)
  await page.reload()
  await first().getByRole('button', { name: '查看数据权限', exact: true }).click()
  const readonly = page.locator('.ant-drawer-content').filter({ has: page.getByText('查看数据权限', { exact: true }) })
  await expect(readonly).toContainText('当前为只读查看')
  await expect(readonly.getByRole('button', { name: '保存权限', exact: true })).toHaveCount(0)
  await expect(readonly.getByRole('list', { name: '可查看字段', exact: true })).toContainText('备注')
  await page.screenshot({ path: resolve(output, '04-readonly-permission.png'), fullPage: true, animations: 'disabled' })
  checks.push('1280窗口保存按钮可见；模拟无共享管理权限时只读查看真实授权，无保存入口')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  await mkdir(output, { recursive: true })
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ origin, api: ac.base, checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, errors, failure: failure?.message }))
}
if (failure) throw failure
