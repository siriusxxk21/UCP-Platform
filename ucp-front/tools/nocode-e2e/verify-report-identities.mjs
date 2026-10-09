import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
const expect = playwrightExpect.configure({ timeout: 20000 })

// 角色、会话、授权与数据均来自当前开发服务；禁止伪造菜单或拦截业务响应。
const token = process.env.REPORT_TEST_TOKEN
const ownerToken = process.env.REPORT_TEST_OWNER_TOKEN
const id = process.env.REPORT_TEST_DATASET
assert.ok(token && ownerToken && id)
const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-identities', id)
await mkdir(output, { recursive: true })
async function request(path, body, sessionToken = token) {
  const response = await fetch(base + path, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: `Bearer ${sessionToken}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  })
  return response.json()
}
async function api(path, body, sessionToken = token) {
  const result = await request(path, body, sessionToken)
  assert.equal(result.code, 0, result.msg)
  return result.data
}
const info = await api('/system/auth/get-permission-info')
assert.deepEqual(
  new Set(info.permissions.filter(Boolean)),
  new Set([
    'nocode:report:query',
    'nocode:report:create',
    'nocode:report:update',
    'nocode:report:publish',
    'nocode:object:query'
  ])
)
const original = await api('/nocode/report/dataset/get?id=' + id, undefined, ownerToken)
const originalPolicy = await api('/nocode/report/dataset/data-policy?id=' + id, undefined, ownerToken)
const originalAcl = await api('/nocode/report/dataset/resource-policy?id=' + id, undefined, ownerToken)
const object = await api('/nocode/report/dataset/source-object?id=' + original.draft.source.root.objectId)
async function dataPolicy(members) {
  const current = await api('/nocode/report/dataset/data-policy?id=' + id, undefined, ownerToken)
  return api(
    '/nocode/report/dataset/data-policy',
    {
      datasetId: id,
      expectedRevision: current.revision,
      members,
      reason: '普通制作身份矩阵授权'
    },
    ownerToken
  )
}
async function resourcePolicy(members) {
  const current = await api('/nocode/report/dataset/resource-policy?id=' + id, undefined, ownerToken)
  return api(
    '/nocode/report/dataset/resource-policy',
    {
      datasetId: id,
      expectedRevision: current.revision,
      members,
      reason: '普通制作身份矩阵协作'
    },
    ownerToken
  )
}
const browser = await chromium.launch({
  headless: true,
  channel: 'chrome',
  args: [
    '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows'
  ]
})
const page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
page.setDefaultTimeout(20000)
const checks = [],
  errors = []
page.on('pageerror', e => errors.push(e.message))
async function saveDraft() {
  const response = page.waitForResponse(
    r => r.url().endsWith('/nocode/report/dataset/save') && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  assert.equal((await (await response).json()).code, 0)
  await expect(page.getByRole('button', { name: /保存草稿$/ })).toBeDisabled()
}

try {
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
      // 功能验收禁用过渡动画，避免后台 Chrome 的动画帧节流影响可点击稳定性判断。
      document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style')
        style.textContent =
          '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }'
        document.head.appendChild(style)
      })
    },
    { token, info }
  )
  await page.goto(origin + '/nocode/report-center/datasets')
  await expect(page.getByText('报表中心', { exact: true }).first()).toBeVisible()
  await page.getByRole('textbox', { name: '搜索数据集', exact: true }).fill(original.draft.name)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await expect(page.getByRole('row').filter({ hasText: original.draft.name })).toHaveCount(0)
  assert.notEqual((await request('/nocode/report/dataset/get?id=' + id)).code, 0)
  await expect(page.getByRole('button', { name: '新建目录', exact: true })).toHaveCount(0)
  checks.push('普通角色未获协作授权时列表不暴露数据集，猜测 ID 读取拒绝，无目录管理入口')

  await page.getByRole('button', { name: /新建数据集/ }).click()
  const dialog = page.getByRole('dialog')
  const createdName = original.draft.name + ' 普通制作'
  await dialog.getByRole('textbox', { name: '数据集名称', exact: true }).fill(createdName)
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page).toHaveURL(/dataset-editor\?id=/)
  await expect(page.getByRole('textbox', { name: '数据集名称', exact: true })).toHaveValue(createdName)
  const createdId = new URL(page.url()).searchParams.get('id')
  assert.ok(createdId && createdId !== id)
  await page.locator('#report-source-object').fill(object.name)
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: object.name }).click()
  await page.getByRole('checkbox', { name: '选用字段0', exact: true }).check()
  await page.getByRole('tab', { name: '指标与筛选', exact: true }).click()
  await page.getByRole('button', { name: '添加指标', exact: true }).click()
  await saveDraft()
  await page.reload()
  await expect(page.getByRole('checkbox', { name: '选用字段0', exact: true })).toBeChecked()
  await expect(page.getByRole('button', { name: '授权管理', exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.locator('.ant-tabs-tabpane-active .ant-alert-error')).toContainText('没有该操作权限')
  await page.getByRole('button', { name: /^发\s*布$/ }).click()
  await dialog.getByRole('textbox', { name: '操作原因', exact: true }).fill('无上限不能发布')
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog.locator('.ant-alert-error')).toBeVisible()
  await dialog.getByRole('button', { name: /^取\s*消$/ }).click()
  assert.equal((await api('/nocode/report/dataset/get?id=' + createdId)).publishedVersion, null)
  checks.push('普通账号从页面新建、选来源字段、保存重开；拥有资源不产生数据权，无上限时预览和发布拒绝')

  await resourcePolicy([
    ...originalAcl.members,
    {
      principalKind: 'USER',
      principalId: String(info.user.id),
      actions: ['VIEW_META', 'EDIT', 'PUBLISH', 'USE']
    }
  ])
  await page.goto(origin + '/nocode/report-center/datasets')
  await page.getByRole('textbox', { name: '搜索数据集', exact: true }).fill(original.draft.name)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await page.getByText(original.draft.name, { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '选用字段0', exact: true })).toBeChecked()
  await page.getByRole('textbox', { name: '数据集说明', exact: true }).fill('普通协作者保存验证')
  await saveDraft()
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.locator('.ant-tabs-tabpane-active .ant-alert-error')).toContainText('没有该操作权限')
  // VIEW_META 可查看对象上限，但不能查看成员策略或变更任一层授权。
  for (const path of ['resource-policy', 'data-policy'])
    assert.notEqual((await request('/nocode/report/dataset/' + path + '?id=' + id)).code, 0)
  assert.notEqual(
    (
      await request('/nocode/report/dataset/data-policy', {
        datasetId: id,
        expectedRevision: originalPolicy.revision,
        members: originalPolicy.members,
        reason: '不应放权'
      })
    ).code,
    0
  )
  const ceiling = (await api('/nocode/report/dataset/ceilings?id=' + id))[0]
  assert.notEqual(
    (
      await request('/nocode/report/dataset/ceiling', {
        datasetId: id,
        objectId: ceiling.objectId,
        expectedRevision: ceiling.revision,
        permission: null,
        reason: '制作权限不能撤销上限'
      })
    ).code,
    0
  )
  assert.notEqual(
    (
      await request('/nocode/report/dataset/resource-policy', {
        datasetId: id,
        expectedRevision: 1,
        members: [],
        reason: '制作权限不能修改协作策略'
      })
    ).code,
    0
  )
  checks.push('只有协作权限的设计者可以编辑但不能预览，授权管理不可见且直接调用拒绝')

  const restrictedObjects = structuredClone(originalPolicy.members[0].objects)
  restrictedObjects[0].actionScopes = {
    READ: {
      logic: 'AND',
      conditions: [{ fieldId: original.draft.source.fields[0].sourceFieldId, operator: 'eq', value: 'A' }],
      groups: []
    }
  }
  await dataPolicy([
    ...originalPolicy.members,
    {
      principalKind: 'USER',
      principalId: String(info.user.id),
      objects: restrictedObjects
    }
  ])
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.getByText('匹配 0 条记录，共 1 组，最多显示 100 组。')).toBeVisible()
  checks.push('成员仅可读 A 与数据集固定 B 条件求交为空，资源所有者的宽权限不传递给协作者')

  await dataPolicy([
    ...originalPolicy.members,
    {
      principalKind: 'USER',
      principalId: String(info.user.id),
      objects: originalPolicy.members[0].objects
    }
  ])
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.getByText('匹配 2 条记录，共 1 组，最多显示 100 组。')).toBeVisible()
  await page.getByRole('button', { name: /^发\s*布$/ }).click()
  await dialog.getByRole('textbox', { name: '操作原因', exact: true }).fill('普通协作者浏览器发布')
  await dialog.getByRole('button', { name: /确.*定/ }).click()
  await expect(dialog).not.toBeVisible()
  const released = await api('/nocode/report/dataset/get?id=' + id)
  await page.reload()
  await expect(page.getByRole('textbox', { name: '数据集说明', exact: true })).toHaveValue('普通协作者保存验证')
  await expect(page.getByText('V' + released.publishedVersion, { exact: true })).toBeVisible()
  const query = {
    datasetId: id,
    preview: false,
    versionNo: released.publishedVersion,
    checksum: released.publishedChecksum,
    dimensions: [],
    metricIds: ['rows'],
    limit: 20
  }
  // 明确取发布摘要，不把草稿校验和当发布版本摘要。
  const versions = await api('/nocode/report/dataset/releases?id=' + id)
  query.checksum = versions.list.find(v => v.versionNo === released.publishedVersion).checksum
  assert.equal((await api('/nocode/report/dataset/query', query)).recordCount, 2)
  checks.push('显式授予成员数据权后普通协作者预览为两条 B，发布、刷新和固定版本查询通过')
  await page.getByRole('tab', { name: '数据预览', exact: true }).click()
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.getByText('匹配 2 条记录，共 1 组，最多显示 100 组。')).toBeVisible()
  await dataPolicy(originalPolicy.members)
  await page.getByRole('button', { name: '查询预览', exact: true }).click()
  await expect(page.locator('.ant-tabs-tabpane-active .ant-alert-error')).toContainText('没有该操作权限')
  await expect(page.getByText('匹配 2 条记录，共 1 组，最多显示 100 组。')).toHaveCount(0)
  assert.notEqual((await request('/nocode/report/dataset/query', query)).code, 0)
  checks.push('已打开页面撤销成员数据权后再次预览清除旧结果，原发布版本查询也被拒绝')
  await page.screenshot({ path: resolve(output, 'revoked-data.png'), fullPage: true })

  await page.getByRole('textbox', { name: '数据集说明', exact: true }).fill('撤权后不应写入')
  await resourcePolicy(originalAcl.members)
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  await expect(page.locator('.dataset-editor > .ant-alert-error')).toBeVisible()
  assert.equal(
    (await api('/nocode/report/dataset/get?id=' + id, undefined, ownerToken)).draft.description,
    '普通协作者保存验证'
  )
  await page.getByRole('button', { name: '返回列表', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索数据集', exact: true }).fill(original.draft.name)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await expect(page.getByText(original.draft.name, { exact: true })).toHaveCount(0)
  await page.goto(origin + '/nocode/report-center/dataset-editor?id=' + id)
  await expect(page.locator('.dataset-editor > .ant-alert-error')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '数据集名称', exact: true })).toHaveCount(0)
  checks.push('撤销资源协作权后旧页面保存拒绝，列表隐藏资源，直达旧链接不再显示草稿')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: resolve(output, 'revoked-resource.png'), fullPage: true })
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors }, null, 2))
  console.log('Report identities browser: ' + checks.length + ' checks passed; ' + output)
} catch (e) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ checks, errors, message: e.message }, null, 2))
  throw e
} finally {
  try {
    await dataPolicy(originalPolicy.members)
    await resourcePolicy(originalAcl.members)
  } finally {
    await browser.close()
  }
}
