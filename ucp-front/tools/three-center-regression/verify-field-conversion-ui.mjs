import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 对已完成发布的本批结果只读复核；保留原运行报告，系统 ID 由 Spring 按 manifest 单独核验。 */
async function verifyResult(directory) {
  const previous = JSON.parse(await readFile(resolve(directory, 'result.json'), 'utf8'))
  const manifest = JSON.parse(await readFile(resolve(directory, 'cleanup-manifest.json'), 'utf8'))
  assert.equal(manifest.batchPrefix, previous.prefix + '_')
  const owned = manifest.objects.find(item => item.id === previous.sourceId)
  assert.equal(owned.code, previous.prefix + '_source')
  assert.equal(owned.tableName, 'biz_' + owned.code)
  const read = new FormAcceptance(process.env.NOCODE_VERIFY_API, directory)
  let browser
  try {
    await read.login()
    const current = await read.api(`/nocode/design/get?id=${owned.id}`)
    const first = await read.api(`/nocode/design/version?id=${owned.id}&versionNo=1`)
    assert.equal(current.draft.objectCode, owned.code)
    assert.equal(current.draft.state, 'PUBLISHED')
    const original = first.fields.find(field => field.code === 'choice')
    const choice = current.draft.fields.find(field => field.code === 'choice')
    const finalType = previous.finalType || 'REFERENCE'
    assert.equal(choice.type, finalType)
    assert.equal(choice.id, original.id)
    assert.equal(current.fieldOptions[choice.id].columnName, first.fieldOptions[original.id].columnName)
    if (finalType === 'REFERENCE')
      assert.equal(current.relations.find(relation => relation.fieldId === choice.id).targetObjectId, previous.targetId)
    else assert.ok(!current.relations.some(relation => relation.fieldId === choice.id))
    const preview = await read.api(`/nocode/table/preview?schema=public&name=${owned.tableName}&pageNo=1&pageSize=50`)
    assert.equal(preview.rows.length, previous.recordIds.length)
    assert.ok(preview.rows.every(row => row.choice == null))
    assert.deepEqual(preview.rows.map(row => row.keep_value).sort(), ['保留甲', '保留乙'].sort())
    assert.deepEqual(
      preview.rows.map(row => row.name).sort(),
      [previous.prefix + ' 第一条', previous.prefix + ' 第二条'].sort()
    )
    browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
    const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
    await page.addInitScript(token => {
      localStorage.setItem('token', token)
      for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    }, read.tokens.admin)
    await page.goto((process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173') + `/nocode/object/editor?id=${owned.id}`)
    await expect(page.getByRole('button', { name: '编辑新草稿', exact: true })).toBeVisible()
    await page.screenshot({
      path: resolve(directory, '02-原字段转换发布成功.png'),
      fullPage: true,
      animations: 'disabled'
    })
    const result = {
      time: new Date().toISOString(),
      prefix: previous.prefix,
      checks: [
        ...previous.checks,
        `转换后只读API与浏览器核验：原字段ID、编码、列名不变，最终类型${finalType}；2条记录名称及保留列原值不变，仅choice列为NULL`
      ],
      originalFieldId: original.id,
      originalColumn: first.fieldOptions[original.id].columnName,
      recordIdVerification: '预览遮蔽系统ID，等待Spring按cleanup-manifest核验原记录ID',
      passed: true
    }
    await writeFile(resolve(directory, 'verification-result.json'), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result))
  } finally {
    await browser?.close()
    read.tokens = {}
    read.passwords = {}
  }
}
if (process.argv[2] === '--verify-result') {
  assert.ok(process.argv[3], '必须指定本脚本输出目录')
  await verifyResult(resolve(process.argv[3]))
  process.exit(0)
}

// 只通过当前开发服务创建唯一前缀夹具。物理清理由 Spring 工具按 manifest 的精确 ID 与编码执行。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const verifyReverse = process.argv.includes('--reverse')
const verifyConstraints = process.argv.includes('--constraints')
const verifyLifecycle = process.argv.includes('--lifecycle')
const verifyPermissions = process.argv.includes('--permissions')
ac.prefix = `e2efc${Date.now().toString(36)}`
const out = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/field-conversion-ui', ac.prefix)
ac.output = out
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = []
let browser, page, source, target, seeded, failure
let applicationDetached = false
const cleanupObjects = new Map(),
  cleanupApplications = new Map()
const persist = ac.persist.bind(ac)
ac.persist = async () => {
  await persist()
  for (const owned of ac.owned.objects) {
    if (cleanupObjects.has(owned.id)) continue
    const value = await ac.api(`/nocode/design/get?id=${owned.id}`)
    assert.equal(value.draft.objectCode, owned.code)
    cleanupObjects.set(owned.id, {
      id: String(owned.id),
      code: owned.code,
      name: value.draft.objectName,
      tableName: value.draft.tableName
    })
  }
  if (ac.app) {
    const app = ac.app.application
    cleanupApplications.set(app.id, { id: String(app.id), code: app.code, name: app.name })
  }
  await writeFile(
    resolve(out, 'cleanup-manifest.json'),
    JSON.stringify(
      {
        batchPrefix: ac.prefix + '_',
        objects: [...cleanupObjects.values()],
        applications: [...cleanupApplications.values()]
      },
      null,
      2
    )
  )
}
const revision = app => ({
  id: app.application.id,
  expectedRevision: app.application.revision,
  reason: ac.prefix + ' 转换验收'
})
const fieldItem = (scope, label) =>
  scope.locator('.ant-form-item').filter({
    has: page.locator('label').filter({ hasText: new RegExp('^' + label + '$') })
  })
const screenshot = name =>
  page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
const dialog = title =>
  page.locator('.ant-modal-content:visible, .ant-drawer-content:visible').filter({
    has: page.locator('.ant-modal-title, .ant-drawer-title').filter({ hasText: new RegExp('^' + title + '$') })
  })
async function choose(scope, label) {
  await scope
    .locator('xpath=ancestor-or-self::*[contains(concat(" ",normalize-space(@class)," ")," ant-select ")]')
    .click()
  const dropdown = page.locator('.ant-select-dropdown:visible')
  await expect(dropdown).toBeVisible()
  const selected = dropdown.locator('.ant-select-item-option-selected')
  // 等候虚拟列表定位当前选项完成，再滚回顶部，避免被初始化滚动覆盖。
  await expect(selected).toBeInViewport()
  if (!(await dropdown.getByText(label, { exact: true }).count())) {
    await dropdown.locator('.rc-virtual-list-holder').evaluate(element => {
      element.scrollTop = 0
    })
  }
  await dropdown.getByText(label, { exact: true }).click()
}
async function confirmSwitchImpact(totalRows, valueRows, clear = false) {
  const drawer = dialog('字段配置')
  if (!(await switchDialog().isVisible())) await drawer.getByRole('button', { name: '查看变更影响' }).click()
  const impact = switchDialog().locator('.switch-impact')
  await expect(impact).toBeVisible()
  await expect(impact).toContainText(`共 ${totalRows} 条记录，本列 ${valueRows} 条有值`)
  if (clear && valueRows > 0) {
    await expect(impact).toContainText('确认清空本列后可转换')
    await impact.getByRole('checkbox', { name: /选择发布时清空本列/ }).check()
  }
  await switchDialog().getByRole('button', { name: '确认变更方案' }).click()
  await drawer.getByRole('button', { name: '应用到草稿' }).click()
  await expect(drawer).toHaveCount(0)
}
function switchDialog() {
  return page.locator('.ant-modal-content:visible').filter({
    has: page.locator('.ant-modal-title').filter({ hasText: /^检查字段变更/ })
  })
}
async function design() {
  return ac.api(`/nocode/design/get?id=${source.objectId}`)
}
async function rows() {
  return (await ac.api(`/nocode/table/preview?schema=public&name=${source.definition.tableName}&pageNo=1&pageSize=50`))
    .rows
}
async function saveDraft() {
  const response = page.waitForResponse(r => r.url().includes('/nocode/design/save') && r.request().method() === 'POST')
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  const result = await response
  const payload = result.request().postDataJSON()
  assert.equal((await result.json()).code, 0, '浏览器保存草稿必须成功')
  await expect(page.getByRole('button', { name: /发\s*布$/ })).toBeEnabled()
  return payload
}
async function openPlan() {
  const response = page.waitForResponse(r => r.url().includes('/nocode/design/plan') && r.request().method() === 'POST')
  await page.getByRole('button', { name: /发\s*布$/ }).click()
  const result = await (await response).json()
  assert.equal(result.code, 0)
  await expect(dialog('发布确认')).toBeVisible()
  return result.data
}
async function executePlan(name) {
  const response = page.waitForResponse(
    r => r.url().includes('/nocode/design/execute') && r.request().method() === 'POST'
  )
  await dialog('发布确认').getByRole('button', { name, exact: true }).click()
  const result = await response
  const execution = await result.json()
  assert.equal(execution.code, 0)
  assert.equal(execution.data.state, 'SUCCEEDED')
  await expect(dialog('发布确认')).toHaveCount(0)
  return result.request().postDataJSON()
}
async function enterDraft() {
  await expect(page.getByRole('button', { name: '编辑新草稿', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '编辑新草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: /保存草稿$/ })).toBeVisible()
}

try {
  console.log(JSON.stringify({ prefix: ac.prefix, cleanupManifest: resolve(out, 'cleanup-manifest.json') }))
  await ac.login()
  target = await ac.object('target', '转换目标 ' + ac.prefix, [ac.field('name', 'TEXT', '名称')])
  source = await ac.object(
    'source',
    '字段转换 ' + ac.prefix,
    [
      ac.field('name', 'TEXT', '名称'),
      ac.field('empty_value', 'TEXT', '本列为空'),
      ac.field('choice', 'SELECT', '原单选'),
      ac.field('keep_value', 'TEXT', '保留列')
    ],
    {
      choice: ac.option({
        options: [
          { code: 'legacy_a', label: '旧选项甲', disabled: false },
          { code: 'legacy_b', label: '旧选项乙', disabled: false }
        ]
      })
    }
  )
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_seed',
    name: '字段转换写入夹具 ' + ac.prefix,
    definition: {
      objects: [source, target].map(o => ({ objectId: o.objectId, versionNo: o.versionNo, checksum: o.checksum })),
      resources: []
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(source, ac.grant(source))
  await ac.share(target, ac.grant(target))
  ac.app = await ac.api('/nocode/application/publish', revision(ac.app))
  seeded = [
    await ac.save(source, { name: ac.prefix + ' 第一条', empty_value: null, choice: 'legacy_a', keep_value: '保留甲' }),
    await ac.save(source, { name: ac.prefix + ' 第二条', empty_value: null, choice: 'legacy_b', keep_value: '保留乙' })
  ]
  // 应用至少引用一个对象；先移除来源对象并发布，再回收应用，业务记录留给实际转换验证。
  ac.app = await ac.api('/nocode/application/save', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    code: ac.app.application.code,
    name: ac.app.application.name,
    definition: {
      objects: [{ objectId: target.objectId, versionNo: target.versionNo, checksum: target.checksum }],
      resources: []
    }
  })
  ac.app = await ac.api('/nocode/application/publish', revision(ac.app))
  await ac.api('/nocode/application/delete', revision(ac.app))
  applicationDetached = true
  const original = await design()
  const originalField = original.draft.fields.find(f => f.code === 'choice')
  const originalColumn = original.fieldOptions[originalField.id].columnName || originalField.code
  const keepColumn = original.fieldOptions[source.ids.keep_value].columnName || 'keep_value'

  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    // 由正式 bootstrap 规范化权限菜单，不能把 API 树形菜单直接当作 Store 菜单。
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, ac.tokens.admin)
  await page.goto(origin + `/nocode/object/editor?id=${source.objectId}`)
  await enterDraft()
  const emptyRow = page.locator('.ant-table-tbody:visible tr').filter({
    has: page.getByRole('textbox', { name: '第 2 行字段名称', exact: true })
  })
  if (verifyConstraints) {
    await emptyRow.getByRole('checkbox', { name: '第 2 行必填', exact: true }).click()
    const drawer = dialog('字段配置')
    await drawer.getByRole('button', { name: '查看变更影响' }).click()
    await expect(switchDialog().locator('.switch-impact')).toContainText('2 条记录不符合目标要求')
    await expect(switchDialog().locator('.switch-impact')).toContainText('本列 0 条有值')
    await expect(switchDialog().getByRole('button', { name: '确认变更方案', exact: true })).toBeDisabled()
    await expect(switchDialog().locator('.switch-impact .ant-table-tbody tr[data-row-key]')).toHaveCount(2)
    await screenshot('05-必填空值预检与记录定位')
    await switchDialog().getByRole('button', { name: '取消本次变更' }).click()
    await drawer.getByRole('button', { name: /^取\s*消$/ }).click()
    await expect(emptyRow.getByRole('checkbox', { name: '第 2 行必填', exact: true })).not.toBeChecked()
    checks.push('行内开启必填进入统一预检：准确显示2条NULL冲突及记录，不能确认；取消保留原草稿')

    const keepRow = page.locator('.ant-table-tbody:visible tr').filter({
      has: page.getByRole('textbox', { name: '第 4 行字段名称', exact: true })
    })
    await keepRow.getByRole('button', { name: /配置/ }).click()
    await dialog('字段配置').getByRole('textbox', { name: '正则校验', exact: true }).fill('^INVALID$')
    await dialog('字段配置').getByRole('button', { name: '查看变更影响' }).click()
    await expect(switchDialog().locator('.switch-impact')).toContainText('2 条记录不符合目标要求')
    await expect(switchDialog().getByRole('button', { name: '确认变更方案', exact: true })).toBeDisabled()
    await switchDialog().getByRole('button', { name: '返回字段配置' }).click()
    await dialog('字段配置').getByRole('textbox', { name: '正则校验', exact: true }).fill('^保留[甲乙]$')
    await confirmSwitchImpact(2, 2)
    await keepRow.getByRole('checkbox', { name: '第 4 行唯一', exact: true }).click()
    await dialog('字段配置').getByRole('button', { name: '查看变更影响' }).click()
    await expect(switchDialog().locator('.configuration-changes')).toContainText('值不能重复')
    await confirmSwitchImpact(2, 2)
    await saveDraft()
    const rulePlan = await openPlan()
    assert.equal(rulePlan.state, 'PENDING', JSON.stringify(rulePlan.checks))
    assert.equal(rulePlan.conversions.find(c => c.fieldId === source.ids.keep_value).action, 'KEEP_COLUMN')
    await expect(dialog('发布确认').getByRole('checkbox')).toHaveCount(0)
    await fieldItem(dialog('发布确认'), '操作原因')
      .locator('textarea')
      .fill(ac.prefix + ' 仅约束变更保留历史')
    assert.deepEqual((await executePlan('确认发布')).clearFieldIds, [])
    assert.deepEqual((await rows()).map(row => row.keep_value).sort(), ['保留乙', '保留甲'].sort())
    checks.push('纯格式约束冲突不能误清空；修正格式并开启唯一后以KEEP_COLUMN发布，旧值保持且无需清空授权')
    await enterDraft()
  }
  await choose(emptyRow.getByRole('combobox'), '整数')
  await confirmSwitchImpact(2, 0)
  await saveDraft()
  const emptyPlan = await openPlan()
  assert.equal(emptyPlan.state, 'PENDING')
  assert.ok(emptyPlan.conversions.every(c => c.affectedRows === 0))
  await expect(dialog('发布确认').getByRole('checkbox')).toHaveCount(0)
  await fieldItem(dialog('发布确认'), '操作原因')
    .locator('textarea')
    .fill(ac.prefix + ' 有记录空列转换')
  assert.deepEqual((await executePlan('确认发布')).clearFieldIds, [])
  const emptyAfter = await design()
  assert.equal(emptyAfter.draft.fields.find(f => f.id === source.ids.empty_value).type, 'INTEGER')
  assert.equal((await rows()).length, 2)
  checks.push('表中已有2条记录，本列全空可从文本转整数，不要求清空确认，真实发布成功')

  await enterDraft()
  const choiceRow = page.locator('.ant-table-tbody:visible tr').filter({
    has: page.getByRole('textbox', { name: '第 3 行字段名称', exact: true })
  })
  await choiceRow.getByRole('button', { name: /配置/ }).click()
  await choose(fieldItem(dialog('字段配置'), '数据来源').locator('.ant-select'), '业务数据对象（配置对象关系）')
  const relation = switchDialog()
  await expect(relation).toBeVisible()
  // 对象候选搜索按名称；名称中包含本轮唯一前缀。
  await fieldItem(relation, '从哪份资料选择')
    .locator('input')
    .fill('转换目标 ' + ac.prefix)
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText(new RegExp(ac.prefix + '_target'))
    .click()
  await confirmSwitchImpact(2, 2, true)
  const payload = await saveDraft()
  assert.equal(payload.draft.fields.find(f => f.id === originalField.id).type, 'REFERENCE')
  assert.equal(payload.relations.find(r => r.fieldId === originalField.id).targetObjectId, target.objectId)
  const configured = await design()
  assert.equal(configured.fieldOptions[originalField.id].columnName || originalField.code, originalColumn)
  assert.equal(configured.draft.fields.find(f => f.id === originalField.id).code, originalField.code)
  let plan = await openPlan()
  assert.equal(plan.state, 'PENDING', JSON.stringify(plan.checks))
  assert.equal(plan.conversions.find(c => c.fieldId === originalField.id).affectedRows, 2)
  await dialog('发布确认').getByRole('button', { name: '查看受影响记录与原值', exact: true }).click()
  await expect(dialog('发布确认').getByText('legacy_a', { exact: true })).toBeVisible()
  await expect(dialog('发布确认').getByText('legacy_b', { exact: true })).toBeVisible()
  await screenshot('01-列值影响与确认')
  await fieldItem(dialog('发布确认'), '操作原因')
    .locator('textarea')
    .fill(ac.prefix + ' 取消发布不得清空')
  let executions = 0
  page.on('request', request => {
    if (request.url().includes('/nocode/design/execute')) executions++
  })
  await expect(dialog('发布确认').getByRole('checkbox', { name: /我已核对影响/ })).toHaveCount(0)
  assert.equal(executions, 0)
  await dialog('发布确认').getByRole('button', { name: '返回修改', exact: true }).click()
  const cancelledRows = await rows()
  assert.deepEqual(cancelledRows.map(row => row[originalColumn]).sort(), ['legacy_a', 'legacy_b'])
  checks.push('保存单选可直接配置对象引用并保留ID/编码/列；发布可查2条原值，不重复勾选，取消发布保留原值')

  plan = await openPlan()
  assert.equal(plan.state, 'PENDING')
  await fieldItem(dialog('发布确认'), '操作原因')
    .locator('textarea')
    .fill(ac.prefix + ' 确认仅清空本列')
  const execution = await executePlan('清空本列 2 个值并发布')
  assert.deepEqual(execution.clearFieldIds, [originalField.id])
  const after = await design()
  const afterRows = await rows()
  assert.equal(after.draft.fields.find(f => f.id === originalField.id).code, originalField.code)
  assert.equal(after.fieldOptions[originalField.id].columnName || originalField.code, originalColumn)
  assert.equal(after.relations.find(r => r.fieldId === originalField.id).targetObjectId, target.objectId)
  assert.equal(afterRows.length, seeded.length)
  assert.deepEqual(afterRows.map(row => row.name).sort(), seeded.map(row => row.values[source.ids.name]).sort())
  assert.ok(afterRows.every(row => row[originalColumn] == null))
  assert.deepEqual(afterRows.map(row => row[keepColumn]).sort(), ['保留乙', '保留甲'].sort())
  await screenshot('02-原字段转换发布成功')
  checks.push(
    '确认清空后原字段与关系真实发布成功；记录数量及名称不变，仅本列为NULL，保留列原值不变；系统ID由Spring单独核验'
  )
  if (verifyReverse) {
    await enterDraft()
    const referenceRow = page.locator('.ant-table-tbody:visible tr').filter({
      has: page.getByRole('textbox', { name: '第 3 行字段名称', exact: true })
    })
    await referenceRow.getByRole('button', { name: /配置/ }).click()
    await screenshot('03-对象引用字段类型选择')
    await choose(fieldItem(dialog('字段配置'), '字段类型').locator('.ant-select'), '单行文本')
    await screenshot('04-目标类型确认')
    await confirmSwitchImpact(2, 0)
    const reversedDraft = await saveDraft()
    assert.equal(reversedDraft.draft.fields.find(f => f.id === originalField.id).type, 'TEXT')
    assert.ok(!reversedDraft.relations.some(r => r.fieldId === originalField.id))
    const reversePlan = await openPlan()
    assert.equal(reversePlan.state, 'PENDING', JSON.stringify(reversePlan.checks))
    assert.ok(
      reversePlan.checks.every(check => !check.blocking),
      JSON.stringify(reversePlan.checks)
    )
    await fieldItem(dialog('发布确认'), '操作原因')
      .locator('textarea')
      .fill(ac.prefix + ' 解除单值引用')
    await executePlan('确认发布')
    const reversed = await design()
    const reversedField = reversed.draft.fields.find(f => f.id === originalField.id)
    assert.equal(reversedField.type, 'TEXT')
    assert.equal(reversedField.code, originalField.code)
    assert.equal(reversed.fieldOptions[originalField.id].columnName || originalField.code, originalColumn)
    assert.ok(!reversed.relations.some(r => r.fieldId === originalField.id))
    assert.equal((await rows()).length, seeded.length)
    checks.push('真实页面从对象引用改回普通文本并发布：字段ID/编码/列名与2条记录保留，关系已解除')
  }
  if (verifyLifecycle) {
    await enterDraft()
    const nameRow = page.locator('.ant-table-tbody:visible tr').filter({
      has: page.getByRole('textbox', { name: '第 1 行字段名称', exact: true })
    })
    await nameRow.getByRole('button', { name: /停用/ }).click()
    const titleStop = dialog('停用字段“名称”')
    await expect(titleStop.locator('.operation-impact')).toContainText('标题')
    await expect(titleStop.getByRole('button', { name: '确认并应用到草稿', exact: true })).toBeDisabled()
    await screenshot('06-停用标题字段明确阻断')
    await titleStop.getByRole('button', { name: /^取\s*消$/ }).click()
    const keepRow = page.locator('.ant-table-tbody:visible tr').filter({
      has: page.getByRole('textbox', { name: '第 4 行字段名称', exact: true })
    })
    await keepRow.getByRole('button', { name: /停用/ }).click()
    const stop = dialog('停用字段“保留列”')
    await expect(stop.locator('.operation-impact')).toContainText('本列 2 条有值')
    await expect(stop.getByRole('button', { name: '确认并应用到草稿', exact: true })).toBeEnabled()
    await stop.getByRole('button', { name: '确认并应用到草稿', exact: true }).click()
    await saveDraft()
    const stopPlan = await openPlan()
    assert.equal(stopPlan.state, 'PENDING', JSON.stringify(stopPlan.checks))
    await fieldItem(dialog('发布确认'), '操作原因')
      .locator('textarea')
      .fill(ac.prefix + ' 停用保留原列')
    await executePlan('确认发布')
    assert.ok(!(await design()).draft.fields.some(field => field.id === source.ids.keep_value))
    await enterDraft()
    await page.getByRole('button', { name: '已停用字段', exact: true }).click()
    await dialog('已停用字段')
      .getByRole('button', { name: /恢\s*复/ })
      .click()
    const restore = dialog('恢复字段“保留列”')
    await expect(restore.locator('.operation-impact')).toContainText('本列 2 条有值')
    await expect(restore.getByRole('button', { name: '确认并应用到草稿', exact: true })).toBeEnabled()
    await screenshot('07-恢复原列数据预览')
    await restore.getByRole('button', { name: '确认并应用到草稿', exact: true }).click()
    await dialog('已停用字段')
      .getByRole('button', { name: /^关\s*闭$/ })
      .click()
    await saveDraft()
    const restorePlan = await openPlan()
    assert.equal(restorePlan.state, 'PENDING', JSON.stringify(restorePlan.checks))
    await fieldItem(dialog('发布确认'), '操作原因')
      .locator('textarea')
      .fill(ac.prefix + ' 恢复原列原值')
    await executePlan('确认发布')
    assert.equal((await design()).draft.fields.find(field => field.code === 'keep_value').id, source.ids.keep_value)
    assert.deepEqual((await rows()).map(row => row.keep_value).sort(), ['保留乙', '保留甲'].sort())
    checks.push('停用标题字段在确认前定位阻断；普通字段停用/恢复均展示2条旧值，发布后原ID和历史值保持')

    await page.goto(origin + '/nocode/object')
    await page.getByRole('textbox', { name: '查询对象编码', exact: true }).fill(ac.prefix + '_source')
    await page.getByRole('button', { name: /查\s*询/ }).click()
    const objectRow = page.locator('.ant-table-tbody tr').filter({ hasText: ac.prefix + '_source' })
    await objectRow.getByRole('button', { name: /删\s*除/ }).click()
    const deletion = dialog('删除数据对象')
    await expect(deletion.locator('.operation-impact')).toContainText('2 条记录')
    await expect(deletion.getByRole('button', { name: '确认删除对象', exact: true })).toBeDisabled()
    await screenshot('08-删除对象列出保留数据范围')
    await deletion.getByRole('button', { name: /^取\s*消$/ }).click()
    assert.equal((await rows()).length, 2)
    checks.push('删除有业务数据的对象提前列出表与记录数量，禁止确认且没有删除任何记录')
  }
  if (verifyPermissions) {
    const flattenDepartments = items => items.flatMap(item => [item, ...flattenDepartments(item.children || [])])
    const department = flattenDepartments(await ac.api('/system/dept/tree')).find(item => item.status === 0)
    assert.ok(department?.id, '权限测试账号需要一个现有启用部门')
    const userId = await ac.user('denied', department.id)
    const token = ac.tokens[userId]
    const current = await design()
    const deniedObject = await ac.denied(
      '/nocode/design/operation-preview',
      { objectId: source.objectId, expectedLockVersion: current.draft.lockVersion, operation: 'delete' },
      token
    )
    const deniedField = await ac.denied(
      '/nocode/design/operation-preview',
      {
        objectId: source.objectId,
        expectedLockVersion: current.draft.lockVersion,
        operation: 'disable_field',
        fieldId: source.ids.keep_value
      },
      token
    )
    assert.equal(deniedObject.code, 403)
    assert.equal(deniedField.code, 403)
    checks.push('真实无管理角色账号调用对象/字段操作预检均被403拒绝，没有获得数据范围或原值')
  }
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) {
    await screenshot('failure').catch(() => {})
    await writeFile(resolve(out, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  await browser?.close()
  // 仅关闭本批创建的测试账号，沿用其他验收脚本的清理方式。
  for (const user of ac.owned.users) {
    assert.ok(user.username.startsWith(ac.prefix))
    await ac.api('/system/permission/assign-user-role', { userId: user.id, roleIds: [] })
    await ac.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
    user.status = 1
    user.roleIds = []
  }
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  await mkdir(out, { recursive: true })
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        prefix: ac.prefix,
        checks,
        errors,
        failure: failure?.message,
        cleanup: '等待 Spring 工具根据 cleanup-manifest.json 精确核验并清理本轮对象、应用及物理表',
        applicationDetached,
        sourceId: source?.objectId,
        targetId: target?.objectId,
        recordIds: seeded?.map(row => row.id),
        finalType: verifyReverse ? 'TEXT' : 'REFERENCE'
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output: out, prefix: ac.prefix, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
