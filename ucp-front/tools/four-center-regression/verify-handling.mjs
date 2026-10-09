import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实无代码与 BPM 联动验收，仅允许调用显式指定的隔离服务。
const origin = process.env.FOUR_CENTER_URL,
  base = process.env.FOUR_CENTER_API
assert(origin && base && process.env.FOUR_CENTER_OUTPUT, '请指定隔离 URL、API 和产物目录')
const ac = new FormAcceptance(base),
  out = resolve(process.env.FOUR_CENTER_OUTPUT, ac.prefix)
ac.output = out
await mkdir(out, { recursive: true })
const checks = [],
  errors = [],
  entries = []
let browser, page, employee, reviewer, roleId, modelId, failure
const check = async (name, work) => {
  await work()
  checks.push(name)
  console.log('PASS ' + name)
}
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
async function shot(name) {
  await page.waitForFunction(() => !document.querySelector('.ant-spin-blur'), null, { timeout: 3000 }).catch(() => {})
  await page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
}
async function open(token) {
  const p = await browser.newPage({ viewport: { width: 1440, height: 980 } })
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
try {
  await ac.login()
  employee = await ac.user('applicant')
  reviewer = await ac.user('reviewer')
  const menus = await ac.api('/system/menu/list')
  const requiredPermissions = ['bpm:task:update', 'bpm:task:query', 'bpm:process-instance:query']
  for (const permission of requiredPermissions) {
    assert(
      menus.some(m => m.permission === permission && m.status === 0),
      `缺少已启用的 ${permission} 权限定义；请核对正式迁移（审批操作由 V028 补齐）`
    )
  }
  const permissions = menus.filter(m => requiredPermissions.includes(m.permission) && m.status === 0).map(m => m.id)
  roleId = await ac.api('/system/role/create', {
    name: ac.prefix + '审批验收',
    code: ac.prefix + '_review',
    sort: 1,
    status: 0,
    dataScope: 1
  })
  await ac.api('/system/permission/assign-role-menu', { roleId, menuIds: permissions })
  await ac.api('/system/permission/assign-user-role', { userId: reviewer, roleIds: [roleId] })
  const key = ac.prefix + '_approval'
  const xml = `<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="nocode_acceptance"><process id="${key}" name="${ac.prefix}无代码审批" isExecutable="true"><startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="review"/><userTask id="review" name="审核申请" flowable:assignee="${reviewer}" flowable:candidateStrategy="30" flowable:candidateParam="${reviewer}" flowable:approveType="1"/><sequenceFlow id="b" sourceRef="review" targetRef="end"/><endEvent id="end"/></process></definitions>`
  modelId = await ac.api('/bpm/model/create', {
    key,
    name: ac.prefix + '无代码审批',
    type: 10,
    formType: 20,
    visible: false,
    managerUserIds: [1],
    startUserIds: [],
    startDeptIds: [],
    allowCancelRunningProcess: true,
    autoApprovalType: 0,
    formCustomCreatePath: '/nocode-app/task-center',
    formCustomViewPath: '/nocode-app/process-record',
    bpmnXml: xml
  })
  await ac.api('/bpm/model/update-bpmn', { id: modelId, bpmnXml: xml }, undefined, 'PUT')
  await ac.api('/bpm/model/deploy?id=' + modelId, {})
  const deployed = (await ac.api('/bpm/process-definition/simple-list')).find(
    p => p.key === key || p.name === ac.prefix + '无代码审批'
  )
  assert(deployed, '流程应已发布')
  const processId = deployed.id
  const vendor = await ac.object('vendors', '供应商', [ac.field('name', 'TEXT', '供应商名称')])
  const object = await ac.object(
    'purchase',
    '采购审批',
    [
      ac.field('name', 'TEXT', '采购事项'),
      ac.field('amount', 'DECIMAL', '申请金额'),
      ac.field('files', 'ATTACHMENT', '申请附件')
    ],
    {},
    [
      {
        id: null,
        code: 'vendor',
        name: '供应商',
        kind: 'REFERENCE',
        targetObjectId: vendor.objectId,
        fieldId: null,
        targetFieldId: null,
        required: false,
        onDelete: 'RESTRICT',
        sourceDetailId: null
      }
    ],
    [
      {
        id: null,
        code: 'lines',
        name: '采购明细',
        tableName: `biz_${ac.prefix}_lines`,
        state: 'ACTIVE',
        fields: [ac.field('description', 'TEXT', '商品说明'), ac.field('quantity', 'DECIMAL', '采购数量')],
        fieldOptions: {},
        indexes: []
      }
    ]
  )
  const lines = object.definition.details[0],
    quantity = lines.fields.find(f => f.code === 'quantity').id,
    description = lines.fields.find(f => f.code === 'description').id,
    vendorField = object.definition.relations[0].fieldId
  let design = await ac.api('/nocode/design/get?id=' + object.objectId)
  design = await ac.api('/nocode/design/edit', {
    id: object.objectId,
    expectedLockVersion: design.draft.lockVersion,
    reason: '配置审批规则'
  })
  const rule = {
    mode: 'CONDITIONAL',
    processDefinitionId: processId,
    condition: {
      op: 'GE',
      args: [
        { op: 'FIELD', fieldId: object.ids.amount, args: [] },
        { op: 'VALUE', value: 100, args: [] }
      ]
    },
    variables: {}
  }
  design = await ac.api('/nocode/design/save', {
    draft: {
      id: object.objectId,
      expectedLockVersion: design.draft.lockVersion,
      objectCode: design.draft.objectCode,
      objectName: design.draft.objectName,
      description: design.draft.description,
      tableName: design.draft.tableName,
      titleFieldKey: design.draft.titleFieldId,
      fields: design.draft.fields,
      removedFieldIds: []
    },
    settings: {
      ...design.settings,
      documentPolicy: {
        rules: [],
        lifecycle: null,
        handling: { create: rule, update: { mode: 'APPROVAL', processDefinitionId: processId, variables: {} } }
      }
    },
    fieldOptions: design.fieldOptions,
    relations: design.relations,
    indexes: design.indexes,
    details: design.details
  })
  const plan = await ac.api('/nocode/design/plan', {
    id: object.objectId,
    expectedLockVersion: design.draft.lockVersion
  })
  assert.equal(plan.checks.filter(c => c.blocking).length, 0)
  assert.equal((await ac.api('/nocode/design/execute', { planId: plan.id, reason: '隔离审批配置' })).state, 'SUCCEEDED')
  const version = await ac.api('/nocode/application/object-version?id=' + object.objectId)
  const grant = ac.grant(object, {
    scope: 'OWN',
    actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'],
    readDetails: [lines.id],
    writeDetails: [lines.id]
  })
  const vendorGrant = ac.grant(vendor, { actions: ['READ'], writeFields: [] })
  const form = resource('purchase_form', 'FORM', '采购申请', {
    objectId: object.objectId,
    detailIds: [lines.id],
    nodes: object.definition.fields.map(f => ({ id: 'field_' + f.id, type: 'FIELD', fieldId: f.id, children: [] }))
  })
  const view = resource('purchase_view', 'VIEW', '采购数据', {
    objectId: object.objectId,
    fieldIds: object.definition.fields.map(f => f.id),
    equal: {},
    pageSize: 20,
    formId: form.id
  })
  const entry = (id, mode) =>
    resource(id, 'TASK_ENTRY', mode === 'FORM' ? '填写采购申请' : '维护采购数据', {
      objectId: object.objectId,
      formId: form.id,
      viewId: mode === 'LIST' ? view.id : null,
      mode,
      category: '审批验收',
      description: '隔离验收采购事项',
      sortOrder: 1,
      limits: [{ ...grant, actions: mode === 'FORM' ? ['READ', 'CREATE'] : grant.actions }, vendorGrant]
    })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_app',
    name: ac.prefix + '采购办理',
    definition: {
      objects: [version, vendor].map(v => ({ objectId: v.objectId, versionNo: v.versionNo, checksum: v.checksum })),
      resources: [form, view, entry('purchase_create', 'FORM'), entry('purchase_edit', 'LIST')]
    }
  })
  const app = ac.app.application.id
  ac.owned.applications.push(app)
  await ac.share(object, ac.grant(object, { readDetails: [lines.id], writeDetails: [lines.id] }))
  await ac.share(vendor, ac.grant(vendor))
  await ac.api('/nocode/application/publish', {
    id: app,
    expectedRevision: ac.app.application.revision,
    reason: '审批办理验收'
  })
  const vendorRecord = await ac.api('/nocode/runtime/save', {
    applicationId: app,
    objectId: vendor.objectId,
    id: null,
    expectedRevision: null,
    requestKey: crypto.randomUUID(),
    values: { [vendor.ids.name]: '华东供应商' },
    details: {},
    relations: {}
  })
  const locator = id => ({ applicationId: app, entryId: id, version: 1 })
  async function authorize(enabled = true) {
    for (const entryId of ['purchase_create', 'purchase_edit']) {
      const p = await ac.api(`/nocode/task-entry/policy?applicationId=${app}&entryId=${entryId}`)
      await ac.api('/nocode/task-entry/policy', {
        applicationId: app,
        entryId,
        expectedRevision: p.revision,
        enabled,
        members: [
          ac.member(employee, [
            { ...grant, actions: entryId === 'purchase_create' ? ['READ', 'CREATE'] : grant.actions },
            vendorGrant
          ])
        ]
      })
      if (!entries.includes(entryId)) entries.push(entryId)
    }
  }
  await authorize()
  const command = (name, amount, row) => ({
    entry: locator(row ? 'purchase_edit' : 'purchase_create'),
    record: {
      applicationId: app,
      objectId: object.objectId,
      formId: form.id,
      id: row?.id || null,
      expectedRevision: row?.revision || null,
      requestKey: crypto.randomUUID(),
      values: { [object.ids.name]: name, [object.ids.amount]: String(amount) },
      details: {},
      relations: {}
    }
  })
  const submit = c => ac.api('/nocode/task-entry/submit', c, ac.tokens[employee])
  const detail = id => ac.api('/nocode/handling/detail?id=' + id, undefined, ac.tokens[employee])
  const record = id =>
    ac.api('/nocode/task-entry/get', { entry: locator('purchase_edit'), recordId: id }, ac.tokens[employee])
  const rows = () =>
    ac.api(
      '/nocode/task-entry/page',
      {
        entry: locator('purchase_edit'),
        query: { applicationId: app, objectId: object.objectId, pageNo: 1, pageSize: 100, descending: false }
      },
      ac.tokens[employee]
    )
  async function task(request) {
    const result = await ac.api('/bpm/task/todo-page?pageNo=1&pageSize=100', undefined, ac.tokens[reviewer])
    const found = result.list.find(
      t => t.processInstanceId === request.processInstanceId || t.processInstance?.id === request.processInstanceId
    )
    assert(found, '真实流程应生成指定审批人的待办')
    return found
  }
  async function finish(request, approve = true) {
    const t = await task(request)
    await ac.api(
      approve ? '/bpm/task/approve' : '/bpm/task/reject',
      { id: t.id, reason: approve ? '隔离验收通过' : '隔离验收驳回', variables: {} },
      ac.tokens[reviewer],
      'PUT'
    )
    return detail(request.id)
  }
  browser = await chromium.launch({ headless: true, channel: process.env.FOUR_CENTER_BROWSER || 'chrome' })
  page = await open(ac.tokens[employee])
  let first, pending
  await check('任务表单直接办理与条件审批，真实浏览器提交不提前写业务记录', async () => {
    first = await submit(command('无需审批的小额采购', 80))
    assert.equal(first.outcome, 'EFFECTIVE')
    await page.goto(`${origin}/nocode-app/task-center?app=${app}&entry=purchase_create`)
    await expect(page.getByRole('button', { name: '提交办理', exact: true })).toBeVisible()
    const modal = page.locator('.task-host .ant-modal-content')
    await modal.locator('.os-business-form input').nth(0).fill('需要审批的采购')
    await modal.locator('.os-business-form input').nth(1).fill('150')
    await page.getByRole('button', { name: '提交办理', exact: true }).click()
    await expect(page.locator('.task-host')).not.toBeVisible()
    const mine = await ac.api('/nocode/handling/mine', { pageNo: 1, pageSize: 20 }, ac.tokens[employee])
    pending = mine.list.find(r => r.applicationId === app)
    assert(pending)
    assert.equal(pending.status, 'PENDING')
    assert.equal((await rows()).total, 1)
    await page.getByText('我的申请', { exact: true }).click()
    await page.getByRole('button', { name: '查看申请', exact: true }).first().click()
    await expect(page.getByText('需要审批的采购', { exact: true })).toBeVisible()
    await shot('01-待审批申请材料')
    const forbidden = await ac.request(
      `/nocode/runtime/model?applicationId=${app}&objectId=${object.objectId}`,
      undefined,
      ac.tokens[employee]
    )
    assert.notEqual(forbidden.code, 0)
  })
  await check('真实审批人可审阅封存材料，BPM 通过后业务与申请状态同时生效', async () => {
    const t = await task(pending),
      reviewerPage = await open(ac.tokens[reviewer])
    const old = page
    page = reviewerPage
    await page.goto(`${origin}/nocode-app/task-center`)
    await page.getByText('我的待办', { exact: true }).click()
    await page.getByRole('button', { name: /办理$/ }).first().click()
    await expect(page).toHaveURL(/\/task\/instance\/detail/)
    await expect(page.getByText('需要审批的采购', { exact: true })).toBeVisible()
    await shot('02-审批人查看材料')
    const result = await finish(pending)
    assert.equal(result.request.status, 'APPROVED', result.request.error)
    assert.equal((await rows()).total, 2)
    await reviewerPage.close()
    page = old
  })
  await check('变更审批保留旧值，驳回与撤回不修改原记录', async () => {
    const before = first.result.record,
      result = await submit(command('变更申请', 200, before))
    assert.equal(result.outcome, 'SUBMITTED')
    assert.equal((await record(before.id)).record.values[object.ids.amount], '80.0000')
    const rejected = await finish(result.request, false)
    assert.equal(rejected.request.status, 'REJECTED')
    assert.equal((await record(before.id)).record.values[object.ids.name], '无需审批的小额采购')
    const next = await submit(command('撤回申请', 210, before))
    const withdrawn = await ac.api(
      '/nocode/handling/withdraw',
      { id: next.request.id, expectedRevision: next.request.revision, reason: '重新核对' },
      ac.tokens[employee]
    )
    assert.equal(withdrawn.status, 'CANCELED')
    assert.equal((await rows()).total, 2)
  })
  await check('入口撤权后审批通过显示生效失败，恢复权限后重试只写一次', async () => {
    const c = command('撤权后重试', 180),
      result = await submit(c)
    await authorize(false)
    const failed = await finish(result.request)
    assert.equal(failed.request.status, 'APPLY_FAILED')
    await authorize(true)
    assert.equal((await rows()).total, 2)
    const retried = await ac.api(
      '/nocode/handling/retry',
      { id: failed.request.id, expectedRevision: failed.request.revision },
      ac.tokens[employee]
    )
    assert.equal(retried.status, 'APPROVED', retried.error)
    const repeated = await submit(c)
    assert.equal(repeated.request?.id || result.request.id, result.request.id)
    assert.equal((await rows()).total, 3)
    await page.reload()
    await page.getByText('我的申请', { exact: true }).click()
    await shot('03-真实申请状态列表')
  })
  await check('门户恢复整单草稿，成功办理后从 FORM 已办定位原记录', async () => {
    const input = command('从门户恢复的采购', 70)
    const draft = await ac.api('/nocode/task-entry/draft/save', input, ac.tokens[employee])
    const list = await ac.api('/nocode/task-entry/drafts', { before: null, limit: 20 }, ac.tokens[employee])
    assert(list.items.some(item => item.id === draft.id && item.available))
    await page.goto(`${origin}/nocode-app/task-center`)
    await page.getByText('我的草稿', { exact: true }).click()
    await page.getByRole('button', { name: '继续填写', exact: true }).click()
    await expect(page.locator('.task-host .os-business-form input').nth(0)).toHaveValue('从门户恢复的采购')
    await shot('04-门户直接恢复草稿')
    await page.getByRole('button', { name: '提交办理', exact: true }).click()
    await expect(page.locator('.task-host')).not.toBeVisible()
    assert.equal(
      (await ac.api('/nocode/task-entry/drafts', { before: null, limit: 20 }, ac.tokens[employee])).items.length,
      0
    )
    await page.getByText('我的已办', { exact: true }).click()
    const item = page.locator('.ant-list-item').filter({ hasText: '从门户恢复的采购' })
    await item.getByRole('button', { name: '查看业务记录', exact: true }).click()
    await expect(page.locator('.task-host').getByText('从门户恢复的采购', { exact: true })).toBeVisible()
    await expect(page.locator('.task-host').getByRole('button', { name: '提交办理', exact: true })).toHaveCount(0)
    await shot('05-FORM历史只读定位')
  })
  await check('审批封存关联名称、附件和高精度明细，审批生效后整单保持一致', async () => {
    const upload = new FormData()
    upload.append('file', new Blob(['isolated approval attachment'], { type: 'text/plain' }), '采购申请附件.txt')
    const uploaded = await (
      await fetch(base + '/infra/file/upload', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + ac.tokens[employee] },
        body: upload
      })
    ).json()
    assert.equal(uploaded.code, 0)
    const fileId = String(uploaded.data.id)
    const input = command('带明细与附件的审批', 230)
    input.record.values[vendorField] = vendorRecord.record.id
    input.record.values[object.ids.files] = [fileId]
    input.record.details[lines.id] = [
      {
        id: null,
        revision: null,
        clientRowKey: 'line-one',
        values: { [description]: '精密设备', [quantity]: '9007199254740993.1200' }
      }
    ]
    const result = await submit(input),
      snapshot = await detail(result.request.id)
    assert.equal(snapshot.material.displayValues[vendorField], '华东供应商')
    assert.equal(String(snapshot.material.details[lines.id][0].values[quantity]), '9007199254740993.1200')
    await page.goto(`${origin}/nocode-app/task-center`)
    await page.getByText('我的申请', { exact: true }).click()
    await page.getByRole('button', { name: '查看申请', exact: true }).first().click()
    await expect(page.getByText('华东供应商', { exact: true })).toBeVisible()
    await expect(page.getByText('采购申请附件.txt', { exact: true })).toBeVisible()
    await expect(page.getByText('9007199254740993.1200', { exact: true })).toBeVisible()
    await shot('06-审批整单材料')
    const effective = await finish(result.request)
    assert.equal(effective.request.status, 'APPROVED', effective.request.error)
    const saved = await record(effective.request.recordId)
    assert.equal(saved.details[lines.id][0].values[quantity], '9007199254740993.1200')
    assert.deepEqual(saved.record.values[object.ids.files], [fileId])
  })
  await check('驳回后从原材料继续修改，新申请与原申请分别保留', async () => {
    const rejected = (await finish((await submit(command('需要修正的申请', 240))).request, false)).request
    await page.goto(`${origin}/nocode-app/task-center`)
    await page.getByText('我的申请', { exact: true }).click()
    await page.getByRole('button', { name: '查看申请', exact: true }).first().click()
    await page.getByRole('button', { name: '修改后重新提交', exact: true }).click()
    const editor = page.locator('.ant-modal-content').filter({ has: page.getByText('修改申请材料', { exact: true }) })
    await expect(editor.locator('.os-business-form input').nth(0)).toHaveValue('需要修正的申请')
    await editor.locator('.os-business-form input').nth(0).fill('修正后重新提交的申请')
    await editor.getByRole('button', { name: '提交办理', exact: true }).click()
    await expect(editor).not.toBeVisible()
    const mine = await ac.api('/nocode/handling/mine', { pageNo: 1, pageSize: 20 }, ac.tokens[employee])
    const current = mine.list.find(r => r.id !== rejected.id && r.status === 'PENDING')
    assert(current)
    assert.equal((await detail(rejected.id)).material.values[object.ids.name], '需要修正的申请')
    assert.equal((await detail(current.id)).material.values[object.ids.name], '修正后重新提交的申请')
    assert.equal((await finish(current)).request.status, 'APPROVED')
    await shot('07-驳回后重新提交')
  })
  await check('数据中心展示关系目标名称和整单保存边界，窄屏收起菜单后说明可阅读', async () => {
    const adminPage = await open(ac.tokens.admin)
    const old = page
    page = adminPage
    await page.goto(`${origin}/nocode/object/editor?id=${object.objectId}`)
    await page.getByRole('tab', { name: /关系/ }).click()
    await expect(page.locator('.relation-overview')).toBeVisible()
    await expect(page.locator('.relation-link').filter({ hasText: vendor.definition.objectName })).toBeVisible()
    await expect(page.locator('.relation-guide')).toContainText('两边独立保存')
    await shot('08-数据关系说明')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('button', { name: '收起二级菜单', exact: true }).click()
    await expect
      .poll(async () => (await page.locator('.relation-guide').boundingBox())?.width || 0)
      .toBeGreaterThan(200)
    await expect(page.locator('.relation-guide article').first()).toBeVisible()
    assert.equal(await page.locator('.relation-guide').evaluate(el => el.scrollWidth <= el.clientWidth), true)
    await shot('09-窄屏关系说明')
    await adminPage.close()
    page = old
  })
  assert.deepEqual(errors, [], '浏览器不应产生运行时错误')
} catch (error) {
  failure = error
  console.error(error.stack || error)
  if (page) await shot('failure').catch(() => {})
} finally {
  for (const entryId of entries) {
    try {
      const app = ac.app.application.id,
        p = await ac.api(`/nocode/task-entry/policy?applicationId=${app}&entryId=${entryId}`)
      await ac.api('/nocode/task-entry/policy', {
        applicationId: app,
        entryId,
        expectedRevision: p.revision,
        enabled: false,
        members: []
      })
    } catch {}
  }
  for (const id of [employee, reviewer].filter(Boolean)) {
    try {
      await ac.api('/system/user/update-status', { id, status: 1 }, undefined, 'PUT')
    } catch {}
  }
  await writeFile(
    resolve(out, 'result.json'),
    JSON.stringify(
      {
        checks,
        errors,
        modelId,
        roleId,
        applicationId: ac.app?.application.id,
        owned: ac.owned,
        failure: failure?.message
      },
      null,
      2
    )
  )
  await browser?.close()
}
if (failure) process.exitCode = 1
