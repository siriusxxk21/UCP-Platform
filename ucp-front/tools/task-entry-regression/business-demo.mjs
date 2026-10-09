import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

export const output = resolve(process.env.TASK_BUSINESS_OUTPUT || '../ucp-server/ucp-nocode/.work/task-business-review')
const marker = '任务中心业务示例：财务与工程，非真实业务'
const appCode = 'task_business_demo_20260912'

/** 通过正式配置 API 建立可重复体验的样例；已有完整配置不覆盖，不复制共享业务对象。 */
export async function prepareBusinessDemo() {
  const ac = new FormAcceptance(process.env.TASK_ENTRY_API, output)
  await ac.login()
  let state = {}
  try {
    state = JSON.parse(await readFile(resolve(output, 'configuration.json'), 'utf8'))
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
  }
  const persist = async () => {
    await mkdir(output, { recursive: true })
    await writeFile(resolve(output, 'configuration.json'), JSON.stringify(state, null, 2))
  }
  const page = await ac.api('/nocode/application/page?pageNo=1&pageSize=100&search=' + appCode)
  const existing = page.list.find(a => a.code === appCode)
  if (existing && state.ready) {
    assert.equal(existing.id, state.applicationId, '配置登记与当前应用匹配')
    ac.app = await ac.api('/nocode/application/get?id=' + existing.id)
    return { ac, state }
  }
  if (existing && !state.applicationId) throw new Error('同编码应用已存在，但缺少本地登记；先核对已有配置，不能覆盖')

  const object = async id => {
    const value = await ac.api('/nocode/application/object-version?id=' + id)
    value.ids = Object.fromEntries(value.definition.fields.map(f => [f.code, f.id]))
    return value
  }
  const company = await object('2817'),
    account = await object('2818'),
    finance = await object('2819')
  assert.equal(finance.definition.objectCode, 'company_transaction')
  const engineeringCode = 'task_demo_engineering_daily'
  if (!state.engineeringId) {
    const objects = await ac.api('/nocode/object/page?pageNo=1&pageSize=100')
    assert.ok(!objects.list.some(o => o.objectCode === engineeringCode), '示例工程对象编码不能占用他人对象')
    const fields = [
      { ...ac.field('name', 'TEXT', '项目名称'), sort: 0 },
      { ...ac.field('report_date', 'DATE', '填报日期'), required: true, sort: 1 },
      { ...ac.field('progress', 'DECIMAL', '累计完成百分比'), precision: 5, scale: 2, required: true, sort: 2 },
      { ...ac.field('workers', 'INTEGER', '当日到岗人数'), sort: 3 },
      { ...ac.field('work_content', 'TEXTAREA', '今日完成工作'), required: true, sort: 4 },
      { ...ac.field('issues', 'TEXTAREA', '问题与风险'), sort: 5 },
      { ...ac.field('next_plan', 'TEXTAREA', '下一步计划'), sort: 6 }
    ]
    const draft = await ac.api('/nocode/design/save', {
      draft: {
        id: null,
        expectedLockVersion: null,
        objectCode: engineeringCode,
        objectName: '工程进度日报（示例）',
        description: marker,
        tableName: 'biz_task_demo_engineering_daily',
        titleFieldKey: 'name',
        fields,
        removedFieldIds: []
      },
      settings: {},
      relations: [],
      details: [],
      indexes: [],
      fieldOptions: { progress: ac.option({ minimum: '0', maximum: '100' }), workers: ac.option({ minimum: '0' }) }
    })
    state.engineeringId = draft.draft.id
    await persist()
  }
  const design = await ac.api('/nocode/design/get?id=' + state.engineeringId)
  if (!design.publishedVersion) {
    const plan = await ac.api('/nocode/design/plan', {
      id: state.engineeringId,
      expectedLockVersion: design.draft.lockVersion
    })
    assert.ok(plan.checks.every(c => !c.blocking))
    assert.equal((await ac.api('/nocode/design/execute', { planId: plan.id, reason: marker })).state, 'SUCCEEDED')
  }
  const engineering = await object(state.engineeringId)
  const financialCodes = [
    'summary',
    'transaction_date',
    'direction',
    'amount',
    'currency',
    'account_id',
    'counterparty',
    'notes'
  ]
  const financialIds = financialCodes.map(code => finance.ids[code])
  assert.ok(financialIds.every(Boolean), '复用流水对象字段必须存在')
  const financialLimit = ac.grant(finance, {
    actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'],
    scope: 'OWN',
    readFields: financialIds,
    writeFields: financialIds
  })
  const engineeringLimit = ac.grant(engineering, { actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'], scope: 'OWN' })
  const resource = (id, kind, name, config) => ({ id, code: 'task_business_' + id, kind, name, config })
  const form = (id, name, obj, ids, submitText) =>
    resource(id, 'FORM', name, {
      objectId: obj.objectId,
      detailIds: [],
      options: { layout: 'vertical', submitText },
      nodes: ids.map(fieldId => ({ id: 'field_' + fieldId, type: 'FIELD', fieldId, children: [], span: 12 }))
    })
  const view = (id, name, obj, fields, formId, sortFieldId) =>
    resource(id, 'VIEW', name, {
      objectId: obj.objectId,
      fieldIds: fields,
      equal: {},
      formId,
      pageSize: 10,
      sortFieldId,
      descending: true,
      list: { queryFieldIds: [fields[0]], advancedFieldIds: [], columnWidths: {}, batchDelete: false },
      interaction: {
        buttons: ['CREATE', 'VIEW', 'UPDATE', 'DELETE'],
        editMode: 'MODAL',
        detailMode: 'MODAL',
        actionIds: []
      }
    })
  const entry = (id, name, category, obj, limit, description, dependencies = []) =>
    resource(id, 'TASK_ENTRY', name, {
      objectId: obj.objectId,
      viewId: id + '_list',
      formId: id + '_form',
      mode: 'LIST',
      category,
      description,
      icon: id === 'finance' ? 'AccountBookOutlined' : 'BuildOutlined',
      sortOrder: id === 'finance' ? 1 : 2,
      limits: [limit, ...dependencies]
    })
  const readOnly = obj => ac.grant(obj, { actions: ['READ'], writeFields: [] })
  const objects = [company, account, finance, engineering]
  const definition = {
    objects: objects.map(o => ({ objectId: o.objectId, versionNo: o.versionNo, checksum: o.checksum })),
    resources: [
      form('finance_form', '财务收支填报表单', finance, financialIds, '保存收支记录'),
      view('finance_list', '我的收支记录', finance, financialIds, 'finance_form', finance.ids.transaction_date),
      entry(
        'finance',
        '财务收支登记',
        '财务',
        finance,
        financialLimit,
        '示例：登记并维护本人收支；只允许选择示例账户。',
        [readOnly(company), readOnly(account)]
      ),
      form('engineering_form', '工程每日进度表单', engineering, Object.values(engineering.ids), '保存工程进度'),
      view(
        'engineering_list',
        '我的工程日报',
        engineering,
        Object.values(engineering.ids),
        'engineering_form',
        engineering.ids.report_date
      ),
      entry(
        'engineering',
        '工程每日进度',
        '工程',
        engineering,
        engineeringLimit,
        '示例：按日期记录本人负责项目的进度、完成工作和风险；保存后可继续修改。'
      )
    ]
  }
  ac.app = await ac.api('/nocode/application/save', {
    id: state.applicationId || null,
    expectedRevision: existing?.revision ?? null,
    code: appCode,
    name: '任务中心业务示例',
    description: marker,
    icon: 'AppstoreOutlined',
    definition
  })
  state.applicationId = ac.app.application.id
  state.objects = { company, account, finance, engineering }
  state.limits = { finance: financialLimit, engineering: engineeringLimit }
  await persist()
  // 样例尚未向员工开放；先通过原有运行服务创建归属明确的示例公司和账户。
  for (const o of objects) await ac.share(o, ac.grant(o))
  ac.app = await ac.api('/nocode/application/publish', {
    id: state.applicationId,
    expectedRevision: ac.app.application.revision,
    reason: marker
  })
  const seed = async (key, obj, values) => {
    if (!state[key]) {
      state[key] = (await ac.save(obj, values)).id
      await persist()
    }
  }
  await seed('companyRecordId', company, {
    official_name: '【任务中心示例】日创演示公司',
    short_name: '任务示例公司',
    notes: marker
  })
  await seed('accountRecordId', account, {
    account_name: '【任务中心示例】收支演示账户',
    bank_name: '示例银行（非真实）',
    account_number: 'TASK-DEMO-20260912',
    currency: 'CNY',
    company_id: state.companyRecordId,
    notes: marker
  })
  const restricted = (obj, grant, code, value) => ({
    ...grant,
    actionScopes: Object.fromEntries(
      grant.actions.map(action => [action, ac.scope(ac.condition(obj, code, 'eq', value))])
    )
  })
  await ac.share(company, restricted(company, readOnly(company), 'official_name', '【任务中心示例】日创演示公司'))
  await ac.share(account, restricted(account, readOnly(account), 'account_number', 'TASK-DEMO-20260912'))
  await ac.share(finance, restricted(finance, { ...financialLimit, scope: 'ALL' }, 'account_id', state.accountRecordId))
  await ac.share(engineering, { ...engineeringLimit, scope: 'ALL' })
  for (const entryId of ['finance', 'engineering']) {
    const policy = await ac.api(`/nocode/task-entry/policy?applicationId=${state.applicationId}&entryId=${entryId}`)
    await ac.api('/nocode/task-entry/policy', {
      applicationId: state.applicationId,
      entryId,
      expectedRevision: policy.revision,
      enabled: true,
      members: policy.members
    })
  }
  const roles = await ac.api('/system/role/page?pageNo=1&pageSize=100&code=task_demo_employee')
  const role = roles.list.find(r => r.code === 'task_demo_employee')
  assert.ok(!role || role.remark === marker, '不改动同编码的非示例角色')
  state.employeeRoleId =
    role?.id ||
    (await ac.api('/system/role/create', {
      name: '任务中心示例员工',
      code: 'task_demo_employee',
      sort: 99,
      status: 0,
      remark: marker
    }))
  const menus = await ac.api('/system/menu/list')
  const menu = menus.find(m => m.name === '任务中心')
  assert.ok(menu, '复用已交付的任务中心菜单')
  const menuIds = [String(menu.id)]
  for (let parent = menu.parentId; parent && String(parent) !== '0';) {
    menuIds.push(String(parent))
    parent = menus.find(m => String(m.id) === String(parent))?.parentId
  }
  await ac.api('/system/permission/assign-role-menu', { roleId: state.employeeRoleId, menuIds })
  state.version = ac.app.application.publishedVersion
  state.ready = true
  await persist()
  return { ac, state }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { state } = await prepareBusinessDemo()
  console.log(
    JSON.stringify({
      applicationId: state.applicationId,
      engineeringId: state.engineeringId,
      ready: state.ready,
      output
    })
  )
}
