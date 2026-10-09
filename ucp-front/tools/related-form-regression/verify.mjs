import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(
  process.env.NOCODE_VERIFY_API,
  process.env.NOCODE_VERIFY_OUTPUT || '.work/related-form-regression'
)
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const baseGrant = ac.grant.bind(ac)
ac.grant = (object, extra = {}) =>
  baseGrant(object, {
    readDetails: object.definition.details.map(d => d.id),
    writeDetails:
      !extra.actions || extra.actions.some(a => ['CREATE', 'UPDATE'].includes(a))
        ? object.definition.details.map(d => d.id)
        : [],
    ...extra
  })
ac.prefix = `rf${Date.now().toString(36)}`
ac.output = resolve(ac.output, ac.prefix)
const node = field => ({
  id: randomUUID(),
  type: 'FIELD',
  fieldId: field.id,
  resourceId: null,
  text: null,
  span: null,
  children: []
})
const form = (id, object) => ({
  id,
  kind: 'FORM',
  code: id,
  name: id,
  config: {
    objectId: object.objectId,
    nodes: object.definition.fields.filter(f => !object.definition.relations.some(r => r.fieldId === f.id)).map(node),
    detailIds: object.definition.details.map(d => d.id),
    options: { layout: 'vertical', submitText: '联合保存', relationLayout: true }
  }
})
const relation = (code, target, kind = 'REFERENCE') => ({
  id: null,
  code,
  name: code,
  kind,
  targetObjectId: target.objectId,
  fieldId: null,
  targetFieldId: null,
  required: false,
  onDelete: 'RESTRICT',
  sourceDetailId: null
})
let browser
try {
  await ac.login()
  const profile = await ac.object(
    'profile',
    '关联档案',
    [ac.field('name', 'TEXT', '档案名称'), ac.field('note', 'TEXT', '档案说明')],
    {},
    [],
    [
      {
        id: null,
        code: 'lines',
        name: '档案明细',
        tableName: `biz_${ac.prefix}_profile_lines`,
        state: 'ACTIVE',
        fields: [ac.field('name', 'TEXT', '明细名称')],
        fieldOptions: {},
        indexes: []
      }
    ]
  )
  const profileDetail = profile.definition.details[0]
  const profileDetailField = profileDetail.fields.find(f => f.code === 'name')
  const parent = await ac.object('parent', '主业务', [ac.field('name', 'TEXT', '业务名称')], {}, [
    relation('profile', profile, 'ONE_TO_ONE')
  ])
  const child = await ac.object(
    'child',
    '关联子记录',
    [ac.field('name', 'TEXT', '子记录名称'), ac.field('note', 'TEXT', '子记录说明')],
    {},
    [relation('parent', parent), relation('secondary_parent', parent)]
  )
  const profileLink = parent.definition.relations.find(r => r.code === 'profile')
  const childLink = child.definition.relations.find(r => r.code === 'parent')
  const secondaryLink = child.definition.relations.find(r => r.code === 'secondary_parent')
  const parentForm = form('parent_form', parent),
    profileForm = form('profile_form', profile),
    childForm = form('child_form', child)
  parentForm.config.relatedForms = [
    {
      id: 'profile_binding',
      sourceObjectId: parent.objectId,
      relationId: profileLink.id,
      direction: 'OUTGOING',
      formId: 'profile_form',
      title: '关联档案'
    },
    {
      id: 'child_binding',
      sourceObjectId: child.objectId,
      relationId: childLink.id,
      direction: 'INCOMING',
      formId: 'child_form',
      title: '关联子记录'
    },
    {
      id: 'secondary_child_binding',
      sourceObjectId: child.objectId,
      relationId: secondaryLink.id,
      direction: 'INCOMING',
      formId: 'child_form',
      title: '另一个关联区域'
    }
  ]
  const entry = {
    id: 'related_task',
    kind: 'TASK_ENTRY',
    code: 'related_task',
    name: '关联联合登记',
    config: {
      objectId: parent.objectId,
      mode: 'FORM',
      formId: 'parent_form',
      viewId: null,
      category: '联合表单验收',
      description: '直接生效的联合填写',
      sortOrder: 0,
      limits: [
        ac.grant(parent, { actions: ['READ', 'CREATE'] }),
        ac.grant(profile, { actions: ['READ', 'CREATE', 'UPDATE'] }),
        ac.grant(child, { actions: ['READ', 'CREATE', 'UPDATE'] })
      ]
    }
  }
  const resources = [
    profileForm,
    childForm,
    parentForm,
    {
      id: 'parent_view',
      kind: 'VIEW',
      code: 'parent_view',
      name: '主业务列表',
      config: {
        objectId: parent.objectId,
        fieldIds: [parent.ids.name],
        equal: {},
        descending: false,
        pageSize: 20,
        formId: 'parent_form'
      }
    },
    { id: 'parent_menu', kind: 'MENU', code: 'parent_menu', name: '联合表单验收', config: { targetId: 'parent_view' } },
    entry,
    {
      ...entry,
      id: 'related_list_task',
      code: 'related_list_task',
      name: '关联联合维护',
      config: {
        ...entry.config,
        mode: 'LIST',
        viewId: 'parent_view',
        limits: [ac.grant(parent, { actions: ['READ', 'CREATE', 'UPDATE'] }), ...entry.config.limits.slice(1)]
      }
    }
  ]
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_app',
    name: '联合表单验收 ' + ac.prefix,
    definition: {
      objects: [profile, parent, child].map(({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum })),
      resources
    }
  })
  ac.owned.applications.push({ id: ac.app.application.id, code: ac.app.application.code })
  await ac.persist()
  for (const object of [profile, parent, child]) await ac.share(object, ac.grant(object))
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '联合表单真实验收'
  })
  const row = (object, name, existing = null, extra = {}) => ({
    id: existing?.id || null,
    expectedRevision: existing?.revision || null,
    values: ac.values(object, { name, ...extra }),
    details: {}
  })
  const command = (name, profiles, children, existing = null) => ({
    ...ac.saveBody(parent, { name }, existing),
    requestKey: randomUUID(),
    formId: 'parent_form',
    relatedRecords: { profile_binding: profiles, child_binding: children }
  })
  const submit = body => ac.api('/nocode/handling/submit', body)
  const query = (bindingId, recordId, extra = {}) => ({
    applicationId: ac.app.application.id,
    objectId: parent.objectId,
    formId: 'parent_form',
    bindingId,
    recordId,
    ...extra
  })
  let root, p, children, original
  await ac.record('一对一与一对多新增联合保存', async () => {
    original = command('联合主记录', [row(profile, '联合档案')], [row(child, '子记录A'), row(child, '子记录B')])
    original.relatedRecords.profile_binding[0].details[profileDetail.id] = [
      {
        id: null,
        revision: null,
        clientRowKey: randomUUID(),
        values: { [profileDetailField.id]: '关联对象里的内部明细' }
      }
    ]
    root = (await submit(original)).result.record
    p = (await ac.api('/nocode/runtime/related-form', query('profile_binding', root.id))).records[0].record
    children = (await ac.api('/nocode/runtime/related-form', query('child_binding', root.id))).records.map(
      r => r.record
    )
    assert.equal(children.length, 2)
    assert.equal(root.values[profileLink.fieldId], p.id)
    assert.ok(children.every(r => String(r.values[childLink.fieldId]) === root.id))
    assert.equal(
      (await ac.get(profile, p)).details[profileDetail.id][0].values[profileDetailField.id],
      '关联对象里的内部明细'
    )
    return { parent: root.id, profile: p.id, children: children.map(r => r.id) }
  })
  await ac.record('同请求重试不重复写入且内容变化拒绝', async () => {
    assert.equal((await submit(original)).result.record.id, root.id)
    const changed = structuredClone(original)
    changed.relatedRecords.child_binding[0].values[child.ids.name] = '篡改重试'
    const rejected = await ac.request('/nocode/handling/submit', changed)
    assert.notEqual(rejected.code, 0)
    assert.match(rejected.msg, /请求|内容/)
    assert.equal((await ac.page(parent)).total, 1)
    assert.equal((await ac.page(child)).total, 2)
    return rejected.msg
  })
  await ac.record('关联子记录校验失败整体回滚含先写档案', async () => {
    const before = [(await ac.page(parent)).total, (await ac.page(profile)).total, (await ac.page(child)).total]
    const bad = command('不应存在', [row(profile, '也不应存在')], [row(child, '')])
    const rejected = await ac.request('/nocode/handling/submit', bad)
    assert.notEqual(rejected.code, 0)
    assert.deepEqual(
      [(await ac.page(parent)).total, (await ac.page(profile)).total, (await ac.page(child)).total],
      before
    )
    return rejected.msg
  })
  await ac.record('自动关联字段仅联合编排可写且失败后不残留授权', async () => {
    const direct = command('客户端越界关联', [], [])
    direct.relatedRecords = {}
    direct.values[profileLink.fieldId] = p.id
    const denied = await ac.request('/nocode/runtime/save', direct)
    assert.notEqual(denied.code, 0)
    assert.match(denied.msg, /字段|无权/)
    assert.equal((await ac.page(parent)).total, 1)
    return denied.msg
  })
  await ac.record('关联已有记录修改与主记录同步', async () => {
    root = (
      await submit(
        command('联合主记录改', [row(profile, '联合档案改', p)], [row(child, '子记录A改', children[0])], root)
      )
    ).result.record
    p = (await ac.get(profile, p)).record
    children[0] = (await ac.get(child, children[0])).record
    assert.equal(p.values[profile.ids.name], '联合档案改')
    assert.equal(children[0].values[child.ids.name], '子记录A改')
  })
  await ac.record('子记录修订冲突回滚主记录', async () => {
    const stale = { ...children[0] }
    children[0] = await ac.save(child, { name: '并发更新' }, children[0])
    const rejected = await ac.request(
      '/nocode/handling/submit',
      command(
        '不得改变主名称',
        [{ id: p.id, expectedRevision: p.revision, values: {} }],
        [row(child, '过期修改', stale)],
        root
      )
    )
    assert.notEqual(rejected.code, 0)
    assert.equal((await ac.get(parent, root)).record.values[parent.ids.name], '联合主记录改')
    return rejected.msg
  })
  await ac.record('解除关联保留独立记录', async () => {
    root = (
      await submit(
        command(
          '解除一条',
          [{ id: p.id, expectedRevision: p.revision, values: {} }],
          [{ id: children[1].id, expectedRevision: children[1].revision, values: {}, unlink: true }],
          root
        )
      )
    ).result.record
    const detached = (await ac.get(child, children[1])).record
    assert.equal(detached.values[childLink.fieldId] ?? null, null)
    assert.equal((await ac.api('/nocode/runtime/related-form', query('child_binding', root.id))).records.length, 1)
    assert.equal((await ac.page(child)).total, 2)
  })
  await ac.record('不能挪用其他主记录下关联数据', async () => {
    const rejected = await ac.request(
      '/nocode/handling/submit',
      command('错误归属', [], [row(child, '挪用', children[0])])
    )
    assert.notEqual(rejected.code, 0)
    assert.match(rejected.msg, /归属|其他主记录/)
    assert.equal((await ac.page(parent)).total, 1)
    return rejected.msg
  })
  await ac.record('未发布关联区不能由请求注入', async () => {
    const forged = command('注入对象', [], [])
    forged.relatedRecords.unconfigured = [row(profile, '非法')]
    const rejected = await ac.request('/nocode/handling/submit', forged)
    assert.notEqual(rejected.code, 0)
    assert.equal((await ac.page(parent)).total, 1)
    return rejected.msg
  })
  const organizations = await ac.api('/system/organization/tree')
  const dept = await ac.api('/system/dept', {
    name: ac.prefix + '验收',
    parentId: 0,
    sort: 0,
    status: 0,
    orgId: organizations[0].id
  })
  ac.owned.departments.push(dept)
  await ac.persist()
  const member = await ac.user('member', dept)
  await ac.authorize([
    ac.member(member, [
      ac.grant(parent),
      ac.grant(profile, { actions: ['READ'], writeFields: [] }),
      ac.grant(child, { actions: ['READ'], writeFields: [] })
    ])
  ])
  await ac.record('普通成员缺少目标修改权整体拒绝', async () => {
    const denied = await ac.request(
      '/nocode/handling/submit',
      command('越权主名称', [row(profile, '越权档案', p)], [], root),
      ac.tokens[member]
    )
    assert.notEqual(denied.code, 0)
    assert.match(denied.msg, /权限|授权|允许/)
    assert.equal((await ac.get(parent, root)).record.values[parent.ids.name], '解除一条')
    return denied.msg
  })
  const policy = await ac.api(`/nocode/task-entry/policy?applicationId=${ac.app.application.id}&entryId=related_task`)
  await ac.api('/nocode/task-entry/policy', {
    applicationId: ac.app.application.id,
    entryId: 'related_task',
    expectedRevision: policy.revision,
    enabled: true,
    members: [ac.member(member, entry.config.limits)]
  })
  const locator = {
    applicationId: ac.app.application.id,
    entryId: 'related_task',
    version: ac.app.application.publishedVersion
  }
  await ac.record('任务专用入口联合保存与候选读取', async () => {
    const denied = await ac.request(
      '/nocode/task-entry/related-form',
      { entry: locator, query: query('child_binding', root.id) },
      ac.tokens[member]
    )
    assert.notEqual(denied.code, 0, 'FORM入口不能通过关联接口探查其他来源的主记录')
    const cmd = command(
      '任务联合主记录',
      [row(profile, '任务档案')],
      [row(child, '任务子记录1'), row(child, '任务子记录2')]
    )
    const saved = await ac.api('/nocode/task-entry/submit', { entry: locator, record: cmd }, ac.tokens[member])
    assert.equal(saved.outcome, 'EFFECTIVE')
    const related = await ac.api(
      '/nocode/task-entry/related-form',
      { entry: locator, query: query('child_binding', saved.result.record.id) },
      ac.tokens[member]
    )
    assert.equal(related.records.length, 2)
    const history = await ac.api('/nocode/task-entry/activity', { entry: locator }, ac.tokens[member])
    assert.equal(history.items.filter(r => r.recordId === saved.result.record.id).length, 1)
    return { id: saved.result.record.id, activityCount: history.items.length }
  })
  await ac.record('自动关联赋值仍要求入口字段写权限且拒绝时整体回滚', async () => {
    // 任务权限可并入已有应用权限；先移除这个夹具的普通应用授权，验证有效权限确实缺少外键。
    await ac.authorize([])
    const totals = [(await ac.page(parent)).total, (await ac.page(profile)).total, (await ac.page(child)).total]
    const path = `/nocode/task-entry/policy?applicationId=${ac.app.application.id}&entryId=related_task`
    const current = await ac.api(path)
    const restricted = entry.config.limits.map(g =>
      g.objectId === parent.objectId ? { ...g, writeFields: g.writeFields.filter(f => f !== profileLink.fieldId) } : g
    )
    const changed = await ac.api('/nocode/task-entry/policy', {
      applicationId: ac.app.application.id,
      entryId: 'related_task',
      expectedRevision: current.revision,
      enabled: true,
      members: [ac.member(member, restricted)]
    })
    const denied = await ac.request(
      '/nocode/task-entry/submit',
      { entry: locator, record: command('无权关联不落库', [row(profile, '不可遗留档案')], []) },
      ac.tokens[member]
    )
    assert.notEqual(denied.code, 0)
    assert.match(denied.msg, /字段|无权/)
    assert.deepEqual(
      [(await ac.page(parent)).total, (await ac.page(profile)).total, (await ac.page(child)).total],
      totals
    )
    await ac.api('/nocode/task-entry/policy', {
      applicationId: ac.app.application.id,
      entryId: 'related_task',
      expectedRevision: changed.revision,
      enabled: true,
      members: [ac.member(member, entry.config.limits)]
    })
    return denied.msg
  })
  let relatedDraft
  const draftRecord = command(
    '',
    [row(profile, '', null, { note: '未填完档案' })],
    [row(child, '', null, { note: '未填完子记录' })]
  )
  draftRecord.relatedRecords.profile_binding[0].details[profileDetail.id] = [
    { id: null, revision: null, clientRowKey: randomUUID(), values: { [profileDetailField.id]: '' } }
  ]
  const taskDraft = () => ac.api('/nocode/task-entry/draft', locator, ac.tokens[member])
  const saveDraft = (record, draft = relatedDraft) =>
    ac.api(
      '/nocode/task-entry/draft/save',
      {
        entry: locator,
        record,
        draft: draft ? { id: draft.id, revision: draft.revision } : null
      },
      ac.tokens[member]
    )
  await ac.record('任务关联草稿保留未填完输入且不写业务数据', async () => {
    const totals = [(await ac.page(parent)).total, (await ac.page(profile)).total, (await ac.page(child)).total]
    relatedDraft = await saveDraft(draftRecord)
    const restored = await taskDraft()
    assert.deepEqual(restored.relatedRecords, relatedDraft.relatedRecords)
    assert.equal(restored.relatedRecords.child_binding[0].values[child.ids.note], '未填完子记录')
    assert.deepEqual(
      [(await ac.page(parent)).total, (await ac.page(profile)).total, (await ac.page(child)).total],
      totals
    )
    return { draftId: relatedDraft.id, revision: relatedDraft.revision }
  })
  await ac.record('关联草稿撤权后拒绝恢复但保留原输入', async () => {
    const path = `/nocode/task-entry/policy?applicationId=${ac.app.application.id}&entryId=related_task`
    const current = await ac.api(path)
    const restricted = entry.config.limits.map(g =>
      g.objectId === child.objectId ? { ...g, actions: ['READ'], writeFields: [] } : g
    )
    const changed = await ac.api('/nocode/task-entry/policy', {
      applicationId: ac.app.application.id,
      entryId: 'related_task',
      expectedRevision: current.revision,
      enabled: true,
      members: [ac.member(member, restricted)]
    })
    const denied = await ac.request('/nocode/task-entry/draft', locator, ac.tokens[member])
    assert.notEqual(denied.code, 0)
    await ac.api('/nocode/task-entry/policy', {
      applicationId: ac.app.application.id,
      entryId: 'related_task',
      expectedRevision: changed.revision,
      enabled: true,
      members: [ac.member(member, entry.config.limits)]
    })
    assert.deepEqual((await taskDraft()).relatedRecords, relatedDraft.relatedRecords)
    return denied.msg
  })
  await ac.record('任务草稿提交不能丢失关联输入，补齐后原子保存并关闭草稿', async () => {
    const missing = { ...command('丢弃关联草稿', [], []), relatedRecords: {} }
    assert.notEqual(
      (
        await ac.request(
          '/nocode/task-entry/submit',
          { entry: locator, record: missing, draft: { id: relatedDraft.id, revision: relatedDraft.revision } },
          ac.tokens[member]
        )
      ).code,
      0
    )
    const complete = command(
      '草稿联合提交',
      [row(profile, '草稿档案', null, { note: '未填完档案' })],
      [row(child, '草稿子记录', null, { note: '未填完子记录' })]
    )
    complete.relatedRecords.profile_binding[0].details[profileDetail.id] = [
      { id: null, revision: null, clientRowKey: randomUUID(), values: { [profileDetailField.id]: '暂存恢复明细' } }
    ]
    const saved = await ac.api(
      '/nocode/task-entry/submit',
      { entry: locator, record: complete, draft: { id: relatedDraft.id, revision: relatedDraft.revision } },
      ac.tokens[member]
    )
    assert.equal(await taskDraft(), null)
    const retry = { entry: locator, record: complete, draft: { id: relatedDraft.id, revision: relatedDraft.revision } }
    assert.equal((await ac.api('/nocode/task-entry/save', retry, ac.tokens[member])).record.id, saved.result.record.id)
    const forgedRetry = structuredClone(retry)
    forgedRetry.record.relatedRecords.child_binding[0].values[child.ids.name] = '冒充重试'
    assert.notEqual(
      (await ac.request('/nocode/task-entry/save', forgedRetry, ac.tokens[member])).code,
      0,
      '底层save端点同样校验已提交草稿关联意图摘要'
    )
    const related = await ac.api(
      '/nocode/task-entry/related-form',
      { entry: locator, query: query('child_binding', saved.result.record.id) },
      ac.tokens[member]
    )
    assert.equal(related.records[0].record.values[child.ids.note], '未填完子记录')
    assert.equal(
      (await ac.api('/nocode/task-entry/activity', { entry: locator }, ac.tokens[member])).items.filter(
        r => r.recordId === saved.result.record.id
      ).length,
      1
    )
    return { recordId: saved.result.record.id }
  })
  await ac.record('列表任务只改关联数据有一条已办，重试无变化失败均不重复', async () => {
    const listEntry = resources.find(r => r.id === 'related_list_task')
    const current = await ac.api(
      `/nocode/task-entry/policy?applicationId=${ac.app.application.id}&entryId=related_list_task`
    )
    await ac.api('/nocode/task-entry/policy', {
      applicationId: ac.app.application.id,
      entryId: 'related_list_task',
      expectedRevision: current.revision,
      enabled: true,
      members: [ac.member(member, listEntry.config.limits)]
    })
    const listLocator = { ...locator, entryId: 'related_list_task' }
    const taskSubmit = record => ac.api('/nocode/task-entry/submit', { entry: listLocator, record }, ac.tokens[member])
    const activity = () => ac.api('/nocode/task-entry/activity', { entry: listLocator }, ac.tokens[member])
    children[0] = (await ac.get(child, children[0])).record
    const update = command(
      root.values[parent.ids.name],
      [{ id: p.id, expectedRevision: p.revision, values: {} }],
      [row(child, '仅修改关联项', children[0])],
      root
    )
    root = (await taskSubmit(update)).result.record
    let events = await activity()
    assert.equal(events.items.length, 1)
    assert.match(events.items[0].entryName, /关联数据更新/)
    await taskSubmit(update)
    assert.equal((await activity()).items.length, 1)
    children[0] = (await ac.get(child, children[0])).record
    root = (
      await taskSubmit(
        command(
          root.values[parent.ids.name],
          [{ id: p.id, expectedRevision: p.revision, values: {} }],
          [row(child, '仅修改关联项', children[0])],
          root
        )
      )
    ).result.record
    assert.equal((await activity()).items.length, 1)
    const fail = command(
      root.values[parent.ids.name],
      [{ id: p.id, expectedRevision: p.revision, values: {} }],
      [row(child, '')],
      root
    )
    assert.notEqual(
      (await ac.request('/nocode/task-entry/submit', { entry: listLocator, record: fail }, ac.tokens[member])).code,
      0
    )
    assert.equal((await activity()).items.length, 1)
    const historyQuery = { start: new Date(Date.now() - 3600000).toISOString(), applicationId: ac.app.application.id }
    const summary = await ac.api('/nocode/record-history/query', historyQuery)
    const page = await ac.api('/nocode/record-history/page', {
      query: { ...historyQuery, end: summary.end },
      visibility: summary.visibility,
      objectId: parent.objectId,
      changesOnly: true,
      pageNo: 1,
      pageSize: 20
    })
    assert.ok(page.table.rows.some(r => r.id === root.id))
    const detail = await ac.api('/nocode/record-history/detail', {
      query: { ...historyQuery, end: summary.end },
      visibility: summary.visibility,
      objectId: parent.objectId,
      recordId: root.id
    })
    assert.ok(detail.row.changes.length)
    assert.ok(
      detail.row.changes.some(c => c.source?.relatedUpdate),
      '老板历史解释只有关联对象变化的更新'
    )
    assert.ok(!JSON.stringify(detail).includes('relatedEvents'), '子事件仅服务端记录，当前权限之外不暴露')
    return { activityCount: 1, historyChanges: detail.row.changes.length }
  })
  await ac.record('浏览器真实表单一对一及一对多填写并保存', async () => {
    browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
    const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    const info = await ac.api('/system/auth/get-permission-info')
    await page.addInitScript(
      ({ token, info }) => {
        Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true })
        localStorage.setItem('token', token)
        localStorage.setItem('userInfo', JSON.stringify(info.user))
        localStorage.setItem('roles', JSON.stringify(info.roles))
        localStorage.setItem('permissions', JSON.stringify(info.permissions))
        localStorage.setItem('menus', JSON.stringify(info.menus))
        sessionStorage.setItem('lastActivityAt', String(Date.now()))
      },
      { token: ac.tokens.admin, info }
    )
    await page.goto(`${origin}/nocode-app/runtime?id=${ac.app.application.id}`)
    await page.getByRole('button', { name: /新增$/ }).click()
    const modal = page.locator('.ant-modal-content:visible').filter({ hasText: '新建记录' })
    await expect(modal.getByText('关联档案', { exact: true }).first()).toBeVisible()
    await modal.locator('.os-form-surface').first().locator('input').first().fill('浏览器联合主记录')
    await modal.getByRole('button', { name: '新增关联档案', exact: true }).click()
    const profileSection = modal
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '关联档案', exact: true }) })
    await profileSection.locator('input').first().fill('浏览器档案')
    await profileSection.getByRole('button', { name: '添加档案明细', exact: true }).click()
    await profileSection.getByPlaceholder('请输入明细名称').fill('浏览器独立对象内部明细')
    const childSection = modal
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '关联子记录', exact: true }) })
    await childSection.getByRole('button', { name: '新增关联子记录', exact: true }).click()
    await childSection.locator('input').first().fill('浏览器子记录一')
    await childSection.getByRole('button', { name: '新增关联子记录', exact: true }).click()
    await childSection.locator('article').nth(1).locator('input').first().fill('浏览器子记录二')
    const otherSection = modal
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '另一个关联区域', exact: true }) })
    await otherSection.getByRole('button', { name: '新增另一个关联区域', exact: true }).click()
    await otherSection.locator('article').first().locator('input').first().fill('不同关系同一对象')
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(ac.output, 'related-form-filled.png'), fullPage: true })
    await modal.getByRole('button', { name: '联合保存', exact: true }).click()
    await expect(modal).toHaveCount(0)
    await expect(page.getByText('浏览器联合主记录', { exact: true }).first()).toBeVisible()
    assert.deepEqual(errors, [])
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(ac.output, 'related-form-saved.png'), fullPage: true })
    const found = (await ac.page(parent, undefined, { search: '浏览器联合主记录' })).list[0]
    assert.ok(found)
    assert.equal((await ac.api('/nocode/runtime/related-form', query('child_binding', found.id))).records.length, 2)
    const otherRows = (await ac.api('/nocode/runtime/related-form', query('secondary_child_binding', found.id))).records
    assert.equal(otherRows.length, 1)
    assert.equal(otherRows[0].record.values[secondaryLink.fieldId], found.id)
    assert.ok(!otherRows[0].record.values[childLink.fieldId], '同一目标对象的不同关系区域不能按ref数组索引错配')
    const tableRow = page.locator('.ant-table-row').filter({ hasText: '浏览器联合主记录' }).first()
    await tableRow.getByText('编辑', { exact: true }).click()
    const edit = page.locator('.ant-modal-content:visible').filter({ hasText: '编辑记录' })
    const editProfile = edit
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '关联档案', exact: true }) })
    const editChild = edit
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '关联子记录', exact: true }) })
    await expect(editChild.locator('article')).toHaveCount(2)
    await editProfile.locator('input').first().fill('浏览器档案改')
    await editChild.locator('article').first().locator('input').first().fill('浏览器子记录改')
    await edit.getByRole('button', { name: '联合保存', exact: true }).click()
    await expect(edit).toHaveCount(0)
    assert.equal(
      (await ac.api('/nocode/runtime/related-form', query('profile_binding', found.id))).records[0].record.values[
        profile.ids.name
      ],
      '浏览器档案改'
    )
    assert.ok(
      (await ac.api('/nocode/runtime/related-form', query('child_binding', found.id))).records.some(
        r => r.record.values[child.ids.name] === '浏览器子记录改'
      )
    )
    assert.deepEqual(errors, [])
    await page.goto(`${origin}/nocode-app/workspace?id=${ac.app.application.id}`)
    await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
    await page.getByText('业务表单', { exact: true }).click()
    await page.locator('tr').filter({ hasText: 'parent_form' }).getByText('配置', { exact: true }).click()
    await expect(page.getByText('明细与关联区域', { exact: true })).toBeVisible()
    await page.locator('.business-designer .designer-tools').getByText('预览', { exact: true }).click()
    const preview = page.locator('.nocode-form-preview .ant-modal-content')
    await expect(preview.getByRole('heading', { name: /档案明细/ })).toBeVisible()
    await preview.getByRole('button', { name: '添加明细', exact: true }).click()
    await preview.getByPlaceholder('请输入明细名称').fill('预览内部明细')
    await preview.getByText('窄屏', { exact: true }).click()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(ac.output, 'related-form-preview-details.png'), fullPage: true })
    return { id: found.id, previewIncludesInternalDetails: true }
  })
  await ac.record('普通员工浏览器关联草稿暂存、重载恢复及直接草稿入口', async () => {
    const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    const info = await ac.api('/system/auth/get-permission-info', undefined, ac.tokens[member])
    await page.addInitScript(
      ({ token, info }) => {
        Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true })
        localStorage.setItem('token', token)
        localStorage.setItem('userInfo', JSON.stringify(info.user))
        localStorage.setItem('roles', JSON.stringify(info.roles))
        localStorage.setItem('permissions', JSON.stringify(info.permissions))
        localStorage.setItem('menus', JSON.stringify(info.menus))
        sessionStorage.setItem('lastActivityAt', String(Date.now()))
      },
      { token: ac.tokens[member], info }
    )
    const url = `${origin}/nocode-app/task-center?app=${ac.app.application.id}&entry=related_task`
    await page.goto(url)
    const surface = page.locator('.task-direct-form')
    await expect(surface.getByText('未填完可暂存；保存后立即更新业务数据。', { exact: true })).toBeVisible()
    await expect(surface.getByRole('button', { name: '恢复上次暂存', exact: true })).toHaveCount(0)
    await surface.locator('.os-form-surface').first().locator('input').first().fill('浏览器任务草稿')
    const profiles = surface
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '关联档案', exact: true }) })
    const children = surface
      .locator('.related-section')
      .filter({ has: page.getByRole('heading', { name: '关联子记录', exact: true }) })
    await profiles.getByRole('button', { name: '新增关联档案', exact: true }).click()
    await profiles.locator('input').first().fill('浏览器草稿档案')
    await profiles.getByRole('button', { name: '添加档案明细', exact: true }).click()
    await profiles.getByPlaceholder('请输入明细名称').fill('浏览器草稿内部明细')
    await children.getByRole('button', { name: '新增关联子记录', exact: true }).click()
    await children.locator('input').first().fill('浏览器草稿子记录')
    await surface.getByRole('button', { name: '暂存草稿', exact: true }).click()
    await expect(page.getByText('已暂存；下次从这个入口新建时可恢复，不计入业务更新', { exact: true })).toBeVisible()
    await expect(profiles.locator('input').first()).toHaveValue('浏览器草稿档案')
    const saved = await taskDraft()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(ac.output, 'task-related-draft-saved.png'), fullPage: true })
    await page.reload()
    await surface.getByRole('button', { name: '恢复上次暂存', exact: true }).click()
    await expect(profiles.locator('input').first()).toHaveValue('浏览器草稿档案')
    await expect(children.locator('input').first()).toHaveValue('浏览器草稿子记录')
    await expect(profiles.getByPlaceholder('请输入明细名称')).toHaveValue('浏览器草稿内部明细')
    await page.goto(`${url}&version=${locator.version}&draftId=${saved.id}`)
    await expect(profiles.locator('input').first()).toHaveValue('浏览器草稿档案')
    await expect(children.locator('input').first()).toHaveValue('浏览器草稿子记录')
    await surface.getByRole('button', { name: '联合保存', exact: true }).click()
    await expect(page.locator('.task-direct-form')).toHaveCount(0)
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    assert.equal(await taskDraft(), null)
    assert.deepEqual(errors, [])
    return { draftId: saved.id }
  })
} catch (error) {
  for (const context of browser?.contexts() || [])
    for (const page of context.pages()) {
      await page.screenshot({ path: resolve(ac.output, 'failure.png'), fullPage: true }).catch(() => {})
    }
  console.error(error.stack)
  process.exitCode = 1
} finally {
  await browser?.close()
  await ac.persist()
  for (const user of ac.owned.users) {
    try {
      await ac.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
    } catch (error) {
      console.error('停用本次验收账号失败：' + error.message)
      process.exitCode = 1
    }
  }
  await writeFile(
    resolve(ac.output, 'summary.txt'),
    ac.checks.map(c => `${c.passed ? 'PASS' : 'FAIL'} ${c.name}${c.error ? ': ' + c.error : ''}`).join('\n')
  )
  console.log(JSON.stringify({ prefix: ac.prefix, checks: ac.checks, output: ac.output }, null, 2))
}
