import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只操作本次前缀夹具；凭据沿用开发环境既有测试配置，不输出到验收文件。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.TASK_CENTER_OUTPUT || '.work/task-center-v1', ac.prefix)
ac.output = output
const checks = [],
  errors = [],
  tasks = []
let browser, page, failure, employee, appId
let commentTask, receivedNotice
const api = (path, body, token) => ac.api('/nocode/tasks/' + path, body, token)
const detail = id => api('detail', { id })
const check = async (name, run) => {
  await run()
  checks.push(name)
  console.log('PASS ' + name)
}
const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
const node = (id, type, resourceId, extra = {}) => ({ id, type, resourceId, children: [], ...extra })
const date = new Date().toLocaleDateString('en-CA')
const query = extra => ({ scope: 'MANAGE', tab: 'POOL', date, pageNo: 1, pageSize: 100, ...extra })
const title = name => `${ac.prefix} ${name}`
const taskNode = (name, assigneeId, extra = {}) => ({
  id: randomUUID(),
  parentId: null,
  title: title(name),
  description: '',
  assigneeId,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'T0', fixedStart: null, offsetDays: 0, durationDays: 1 },
  predecessorIds: [],
  binding: null,
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
  ...extra
})
async function create(task, extra = {}) {
  const result = await api('create', { task, requestKey: randomUUID(), ...extra })
  tasks.push(result.task.id)
  return result
}
async function transition(id, action, extra = {}) {
  const current = await detail(id)
  return api('transition', {
    id,
    expectedRevision: current.task.revision,
    action,
    note: '任务中心本轮验收',
    requestKey: randomUUID(),
    ...extra
  })
}
async function pageFor(token = ac.tokens.admin, staleMenus = false) {
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  const tab = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  tab.setDefaultTimeout(20000)
  tab.on('pageerror', error => errors.push(error.message))
  await tab.addInitScript(
    ({ token, info, staleMenus }) => {
      const menus = (items, parentId = 0) =>
        items.flatMap((menu, index) =>
          menu.visible === false
            ? []
            : [
                {
                  id: menu.id,
                  name: menu.name,
                  path: menu.path || '',
                  component: menu.component,
                  icon: menu.icon,
                  parentId: menu.parentId ?? parentId,
                  sort: menu.sort ?? index
                },
                ...menus(menu.children || [], menu.id)
              ]
        )
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem(
        'menus',
        JSON.stringify(
          staleMenus
            ? [{ id: 'legacy-cache', name: '旧任务导航缓存', path: '/nocode-app', parentId: 0, sort: 0 }]
            : menus(info.menus || [])
        )
      )
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info, staleMenus }
  )
  return tab
}
try {
  await ac.login()
  await mkdir(output, { recursive: true })
  const info = await ac.api('/system/auth/get-permission-info')
  const actor = String(info.user.id)
  const project = await ac.object('project', '任务项目', [
    ac.field('name', 'TEXT', '项目名称'),
    ac.field('attachment', 'ATTACHMENT', '项目附件')
  ])
  const work = await ac.object('work', '施工任务内容', [
    ac.field('name', 'TEXT', '施工内容'),
    ac.field('quantity', 'INTEGER', '数量')
  ])
  const finance = await ac.object(
    'finance',
    '项目财务',
    [ac.field('name', 'TEXT', '费用说明'), ac.field('amount', 'DECIMAL', '金额')],
    {},
    [
      {
        id: null,
        code: 'project',
        name: '所属项目',
        kind: 'REFERENCE',
        targetObjectId: project.objectId,
        fieldId: null,
        targetFieldId: null,
        required: false,
        onDelete: 'RESTRICT',
        sourceDetailId: null
      }
    ]
  )
  const form = object => ({
    objectId: object.objectId,
    nodes: object.definition.fields.map(f => ({ id: 'f_' + f.id, type: 'FIELD', fieldId: f.id, children: [] })),
    detailIds: [],
    options: { layout: 'vertical', submitText: '保存业务内容' }
  })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_task_app',
    name: title('任务中心验收应用'),
    description: '本轮验收专用配置，非用户业务数据',
    definition: {
      objects: [project, work, finance].map(o => ({
        objectId: o.objectId,
        versionNo: o.versionNo,
        checksum: o.checksum
      })),
      resources: [
        resource('projectform', 'FORM', '项目资料', form(project)),
        resource('workform', 'FORM', '施工业务内容', form(work)),
        resource('financeform', 'FORM', '财务填写', form(finance)),
        resource('financeview', 'VIEW', '项目财务', {
          objectId: finance.objectId,
          fieldIds: [finance.ids.name, finance.ids.amount],
          equal: {},
          pageSize: 10,
          formId: 'financeform'
        }),
        resource('projectpage', 'PAGE', '配置的项目页', {
          contextObjectId: project.objectId,
          nodes: [
            node('projectdetail', 'DETAIL', 'projectform'),
            node('projecttasks', 'TASKS', 'projectform', {
              text: '本项目任务',
              taskView: {
                businessFormId: 'workform',
                columnKeys: ['title', 'business:' + work.ids.name, 'business:' + work.ids.quantity, 'owner', 'status'],
                conditions: {
                  logic: 'AND',
                  items: [{ type: 'condition', field: work.ids.quantity, operator: 'gte', value: 5 }]
                },
                sort: { field: 'business:' + work.ids.quantity, descending: true }
              }
            }),
            node('financeblock', 'RELATED', 'financeview', {
              binding: { relationId: finance.definition.relations[0].id, direction: 'INCOMING' },
              text: '本项目财务'
            }),
            node('files', 'ATTACHMENTS', 'projectform', { text: '项目文件' })
          ]
        }),
        resource('workpage', 'PAGE', '任务型业务表', {
          nodes: [node('worktasks', 'TASKS', 'workform', { text: '施工任务表' })]
        }),
        resource('projectmenu', 'MENU', '项目资料', { targetId: 'projectpage' }),
        resource('workmenu', 'MENU', '施工任务', { targetId: 'workpage' })
      ]
    }
  })
  appId = ac.app.application.id
  ac.owned.applications.push({ id: appId, code: ac.app.application.code })
  for (const object of [project, work, finance]) await ac.share(object, ac.grant(object))
  await ac.api('/nocode/application/publish', {
    id: appId,
    expectedRevision: ac.app.application.revision,
    reason: '任务中心本轮真实验收'
  })
  const projectA = await ac.save(project, { name: title('项目 A') })
  const projectB = await ac.save(project, { name: title('项目 B') })
  const projectRef = row => ({
    applicationId: appId,
    objectId: project.objectId,
    recordId: row.id,
    label: row.values[project.ids.name]
  })
  const financeValues = {
    [finance.ids.name]: title('A 项目费用'),
    [finance.ids.amount]: 200,
    [finance.definition.relations[0].fieldId]: projectA.id
  }
  await ac.api('/nocode/runtime/save', {
    applicationId: appId,
    objectId: finance.objectId,
    values: financeValues,
    details: {},
    formId: 'financeform',
    requestKey: randomUUID()
  })
  let root
  await check('业务内容与任务同次发起，刷新后稳定身份和一份数据', async () => {
    root = await create(
      taskNode('施工工作', actor, { binding: { applicationId: appId, formId: 'workform', entryId: null } }),
      {
        project: projectRef(projectA),
        business: {
          ...ac.saveBody(work, { name: title('放线复核'), quantity: 10 }),
          formId: 'workform',
          requestKey: randomUUID()
        }
      }
    )
    assert.ok(root.task.business.recordId)
    assert.equal((await ac.page(work)).total, 1)
    assert.equal((await detail(root.task.id)).task.business.recordId, root.task.business.recordId)
  })
  await check('项目配置按当前记录隔离；缺上下文/伪节点拒绝，不退回全部任务', async () => {
    const payload = {
      applicationId: appId,
      pageId: 'projectpage',
      nodeId: 'projecttasks',
      recordId: projectA.id,
      query: query()
    }
    assert.deepEqual(
      (await api('page-tasks', payload)).list.map(r => r.id),
      [root.task.id]
    )
    assert.equal((await api('page-tasks', { ...payload, recordId: projectB.id })).total, 0)
    assert.notEqual((await ac.request('/nocode/tasks/page-tasks', { ...payload, recordId: null })).code, 0)
    assert.notEqual((await ac.request('/nocode/tasks/page-tasks', { ...payload, nodeId: 'files' })).code, 0)
    assert.equal(
      (await api('page-tasks', { ...payload, pageId: 'workpage', nodeId: 'worktasks', recordId: null })).total,
      1
    )
  })
  await check('日周计划幂等且互不覆盖，移出计划不删除任务', async () => {
    for (const period of ['DAY', 'DAY', 'WEEK']) await api('plan', { ids: [root.task.id], period, date, include: true })
    assert.equal((await detail(root.task.id)).task.plans.length, 2)
    await api('plan', { ids: [root.task.id], period: 'DAY', date, include: false })
    assert.equal((await detail(root.task.id)).task.plans.length, 1)
    await api('plan', { ids: [root.task.id], period: 'DAY', date, include: true })
  })
  let child
  await check('多级子任务持久化，父任务关闭受子任务约束', async () => {
    child = await create(taskNode('子任务', actor), { parentId: root.task.id })
    await transition(root.task.id, 'START')
    const parent = await detail(root.task.id)
    assert.notEqual(
      (
        await ac.request('/nocode/tasks/transition', {
          id: root.task.id,
          expectedRevision: parent.task.revision,
          action: 'COMPLETE',
          note: '',
          requestKey: randomUUID()
        })
      ).code,
      0
    )
    await transition(child.task.id, 'START')
    await transition(child.task.id, 'COMPLETE')
    await transition(root.task.id, 'COMPLETE')
    const completed = await detail(root.task.id)
    assert.equal(completed.task.status, 'COMPLETED')
    assert.ok(completed.task.actualEnd)
  })
  await check('模板版本固定；前驱完成推动相对后继，拒绝循环依赖', async () => {
    const a = taskNode('工序 A', actor, { id: 'a' })
    const b = taskNode('工序 B', actor, {
      id: 'b',
      predecessorIds: ['a'],
      schedule: { mode: 'PREDECESSOR', fixedStart: null, offsetDays: 0, durationDays: 1 }
    })
    const draft = await ac.api('/nocode/task-templates/save', {
      id: null,
      expectedRevision: null,
      name: title('工序模板'),
      description: '',
      nodes: [a, b]
    })
    const version = await ac.api('/nocode/task-templates/publish', { id: draft.id, expectedRevision: draft.revision })
    const instance = await create(taskNode('编排实例', actor), {
      templateId: draft.id,
      templateVersion: version.version
    })
    const first = instance.nodes.find(r => r.title === a.title),
      second = instance.nodes.find(r => r.title === b.title)
    assert.ok(first && second)
    assert.equal(second.canStart, false)
    assert.equal(first.canStart, false)
    await transition(instance.task.id, 'START')
    await transition(first.id, 'START')
    const finished = await transition(first.id, 'COMPLETE')
    const successor = (await detail(second.id)).task
    assert.equal(successor.expectedStart, finished.task.actualEnd)
    assert.equal(successor.canStart, true)
    const latest = (await ac.api('/nocode/task-templates/list')).find(t => t.id === draft.id)
    const saved = await ac.api('/nocode/task-templates/save', {
      id: draft.id,
      expectedRevision: latest.revision,
      name: latest.name,
      description: '第二版',
      nodes: [{ ...a, title: title('工序 A 新版') }, b]
    })
    await ac.api('/nocode/task-templates/publish', { id: draft.id, expectedRevision: saved.revision })
    assert.equal((await detail(first.id)).task.title, a.title)
    assert.equal((await detail(first.id)).task.templateVersion, version.version)
    const cyclic = await ac.request('/nocode/task-templates/save', {
      id: null,
      expectedRevision: null,
      name: title('非法环'),
      description: '',
      nodes: [{ ...a, predecessorIds: ['b'] }, b]
    })
    assert.notEqual(cyclic.code, 0)
  })
  await check('节点共享受字段白名单控制，业务重试幂等；实例调整只影响当前副本', async () => {
    const a = taskNode('共享来源', actor, {
      id: 'source',
      binding: { applicationId: appId, formId: 'workform', entryId: null }
    })
    const b = taskNode('共享补充', actor, {
      id: 'supplement',
      predecessorIds: ['source'],
      sharing: { mode: 'SHARED', sourceNodeId: 'source', writableFieldIds: [work.ids.quantity] }
    })
    const draft = await ac.api('/nocode/task-templates/save', {
      id: null,
      expectedRevision: null,
      name: title('共享模板'),
      description: '',
      nodes: [a, b]
    })
    const published = await ac.api('/nocode/task-templates/publish', { id: draft.id, expectedRevision: draft.revision })
    const instance = await create(taskNode('共享实例', actor), {
      templateId: draft.id,
      templateVersion: published.version
    })
    const source = instance.nodes.find(task => task.title === a.title),
      target = instance.nodes.find(task => task.title === b.title)
    await transition(instance.task.id, 'START')
    await transition(source.id, 'START')
    const active = (await detail(source.id)).task
    const save = {
      taskId: source.id,
      expectedRevision: active.revision,
      record: {
        ...ac.saveBody(work, { name: title('仅录入一次共享内容'), quantity: 2 }),
        formId: 'workform',
        requestKey: randomUUID()
      }
    }
    const saved = await api('business', save)
    const retry = await api('business', save)
    assert.equal(saved.record.record.id, retry.record.record.id)
    await transition(source.id, 'COMPLETE')
    const sharedBeforeOwnSave = await api('page-tasks', {
      applicationId: appId,
      pageId: 'workpage',
      nodeId: 'worktasks',
      query: query({ tab: 'ALL' }),
      conditions: { logic: 'AND', items: [{ type: 'condition', field: work.ids.quantity, operator: 'eq', value: 2 }] }
    })
    assert.deepEqual(new Set(sharedBeforeOwnSave.list.map(row => row.id)), new Set([source.id, target.id]))
    const current = await detail(instance.task.id)
    const toInput = row => ({
      id: row.id,
      parentId: row.parentId,
      title: row.title,
      description: row.description,
      assigneeId: row.assigneeId,
      urgency: row.urgency,
      priority: row.priority,
      schedule: row.schedule,
      predecessorIds: row.predecessorIds,
      binding: row.sharing.mode === 'SHARED' ? null : row.binding,
      sharing: row.sharing
    })
    const adjustment = {
      rootId: instance.task.id,
      expectedRevision: current.task.instanceRevision,
      reason: '本轮仅调整当前实例未开始节点',
      nodes: current.nodes.map(row => ({
        ...toInput(row),
        ...(row.id === target.id ? { title: title('当前实例补充') } : {})
      }))
    }
    assert.ok((await api('adjust-preview', adjustment)).changedIds.includes(target.id))
    await api('adjust', adjustment)
    assert.equal(
      (await ac.api(`/nocode/task-templates/version?id=${draft.id}&version=${published.version}`)).nodes.find(
        row => row.id === 'supplement'
      ).title,
      b.title
    )
    await transition(target.id, 'START')
    const form = await api('form', { id: target.id })
    assert.equal(form.record.record.id, saved.record.record.id)
    assert.deepEqual(form.writableFieldIds, [work.ids.quantity])
    const targetRow = (await detail(target.id)).task
    const input = {
      taskId: target.id,
      expectedRevision: targetRow.revision,
      record: {
        applicationId: appId,
        objectId: work.objectId,
        id: form.record.record.id,
        expectedRevision: form.record.record.revision,
        values: { [work.ids.name]: '非法修改' },
        details: {},
        formId: 'workform',
        requestKey: randomUUID()
      }
    }
    assert.notEqual((await ac.request('/nocode/tasks/business', input)).code, 0)
    const updated = await api('business', {
      ...input,
      record: { ...input.record, values: { [work.ids.quantity]: 12 }, requestKey: randomUUID() }
    })
    assert.equal(Number(updated.record.record.values[work.ids.quantity]), 12)
    assert.equal(updated.record.record.values[work.ids.name], title('仅录入一次共享内容'))
    const sourceDetail = await detail(source.id)
    const completedEvent = sourceDetail.events.find(event => event.taskId === source.id && event.type === 'COMPLETED')
    const material = await api('material', { taskId: source.id, eventId: completedEvent.id })
    assert.equal(Number(material.record.record.values[work.ids.quantity]), 2)
    assert.notEqual(
      (await ac.request('/nocode/tasks/material', { taskId: target.id, eventId: completedEvent.id })).code,
      0
    )
  })
  await check('业务字段条件与任务统一分页，非法字段拒绝，无匹配不泄漏全部任务', async () => {
    const payload = {
      applicationId: appId,
      pageId: 'workpage',
      nodeId: 'worktasks',
      recordId: null,
      query: query({ tab: 'ALL', pageSize: 1 }),
      conditions: { logic: 'AND', items: [{ type: 'condition', field: work.ids.quantity, operator: 'gte', value: 11 }] }
    }
    const first = await api('page-tasks', payload)
    const second = await api('page-tasks', { ...payload, query: { ...payload.query, pageNo: 2 } })
    assert.equal(first.total, 2)
    assert.equal(first.list.length, 1)
    assert.equal(second.list.length, 1)
    assert.notEqual(first.list[0].id, second.list[0].id)
    const condition = (field, value) => ({
      logic: 'AND',
      items: [{ type: 'condition', field, operator: 'gte', value }]
    })
    assert.equal((await api('page-tasks', { ...payload, conditions: condition(work.ids.quantity, 999) })).total, 0)
    assert.notEqual(
      (await ac.request('/nocode/tasks/page-tasks', { ...payload, conditions: condition('unreadable-field', 0) })).code,
      0
    )
  })
  await check('真实消息中心接收评论与回复，重试不重复，非参与人不获任务数据', async () => {
    employee = await ac.user('taskmember')
    const outsider = await ac.user('taskoutside')
    const menus = await ac.api('/system/menu/list')
    const roleId = await ac.api('/system/role/create', {
      name: title('任务验收成员'),
      code: ac.prefix + '_task',
      sort: 1,
      status: 0,
      dataScope: 1
    })
    ac.owned.roles.push({ id: roleId })
    await ac.api('/system/permission/assign-role-menu', {
      roleId,
      menuIds: menus
        .filter(
          menu =>
            ['nocode:task:query', 'nocode:task:create'].includes(menu.permission) ||
            String(menu.permission || '').startsWith('msg:notice:')
        )
        .map(menu => menu.id)
    })
    for (const userId of [employee, outsider])
      await ac.api('/system/permission/assign-user-role', { userId, roleIds: [roleId] })
    commentTask = await create(taskNode('评论协作', employee))
    const body = {
      taskId: commentTask.task.id,
      parentId: null,
      content: title('请核对 <script>window.taskInjected=true</script>'),
      mentionedUserIds: [employee],
      requestKey: randomUUID()
    }
    const comment = await api('comment', body)
    const retry = await api('comment', body)
    assert.equal(comment.id, retry.id)
    assert.notEqual(
      (await ac.request('/nocode/tasks/detail', { id: commentTask.task.id }, ac.tokens[outsider])).code,
      0
    )
    await expect
      .poll(
        async () => {
          const messages = await ac.api('/msg/notice/page?pageNo=1&pageSize=100', undefined, ac.tokens[employee])
          const rows = messages.list || messages.records || []
          const matches = rows.filter(
            row => String(row.url || '').includes(commentTask.task.id) && row.content?.includes(ac.prefix)
          )
          receivedNotice = matches[0]
          return matches.length
        },
        { timeout: 20000, intervals: [300, 600, 1000] }
      )
      .toBe(1)
    assert.ok(!receivedNotice.content.includes('<script>'))
    assert.ok(receivedNotice.url.includes('taskId='))
    assert.ok(receivedNotice.url.includes('commentId=' + comment.id))
    await api(
      'comment',
      {
        taskId: commentTask.task.id,
        parentId: comment.id,
        content: title('已核对，回复原作者'),
        mentionedUserIds: [],
        requestKey: randomUUID()
      },
      ac.tokens[employee]
    )
    assert.equal((await detail(commentTask.task.id)).comments.length, 2)
  })
  await check('近期实际处理日期与负责人代安排计划来源生效', async () => {
    const today = await api('page', query({ tab: 'RECENT', search: ac.prefix, date, recentPeriod: 'DAY' }))
    assert.ok(today.list.length > 0)
    assert.equal((await api('page', query({ tab: 'RECENT', search: ac.prefix, date: '2000-01-01' }))).total, 0)
    assert.equal((await api('page', query({ tab: 'RECENT', search: ac.prefix, date: '2099-01-01' }))).total, 0)
    await api('plan', { ids: [commentTask.task.id], target: 'ASSIGNEE', period: 'DAY', date, include: true })
    const assigned = await api('detail', { id: commentTask.task.id }, ac.tokens[employee])
    assert.equal(assigned.task.plans[0].source, 'MANAGER')
    assert.equal(String(assigned.task.plans[0].arrangedById), actor)
    assert.equal(String(assigned.task.plans[0].userId), String(employee))
    const personal = await api('page', query({ scope: 'MINE', tab: 'TODAY' }), ac.tokens[employee])
    assert.ok(personal.list.some(row => row.id === commentTask.task.id))
  })
  await check('发布固定任务视图与临时业务条件相交，项目记录关联及解除不复制任务', async () => {
    const candidate = await create(
      taskNode('可关联的已有任务', actor, { binding: { applicationId: appId, formId: 'workform' } }),
      {
        business: {
          ...ac.saveBody(work, { name: title('关联材料'), quantity: 30 }),
          formId: 'workform',
          requestKey: randomUUID()
        }
      }
    )
    const excluded = await create(
      taskNode('固定条件排除任务', actor, { binding: { applicationId: appId, formId: 'workform' } }),
      {
        project: projectRef(projectA),
        business: {
          ...ac.saveBody(work, { name: title('工程量不足'), quantity: 3 }),
          formId: 'workform',
          requestKey: randomUUID()
        }
      }
    )
    const context = { applicationId: appId, pageId: 'projectpage', nodeId: 'projecttasks', recordId: projectA.id }
    const payload = { ...context, query: query({ tab: 'ALL' }) }
    const candidates = await api('record-link-candidates', payload)
    assert.ok(candidates.list.some(row => row.id === candidate.task.id))
    const cmd = {
      context,
      taskId: candidate.task.id,
      expectedRevision: candidate.task.revision,
      include: true,
      requestKey: randomUUID()
    }
    await api('record-link', cmd)
    await api('record-link', cmd)
    const linked = await api('page-tasks', payload)
    assert.equal(linked.list[0].id, candidate.task.id)
    assert.ok(linked.list[0].canUnlink)
    assert.ok(!linked.list.some(row => row.id === excluded.task.id))
    assert.equal(
      (
        await api('page-tasks', {
          ...payload,
          conditions: {
            logic: 'AND',
            items: [{ type: 'condition', field: work.ids.quantity, operator: 'lt', value: 5 }]
          }
        })
      ).total,
      0
    )
    const same = await detail(candidate.task.id)
    assert.equal(same.task.business.recordId, candidate.task.business.recordId)
    assert.equal(same.task.parentId, null)
    await api('record-link', { ...cmd, expectedRevision: same.task.revision, include: false, requestKey: randomUUID() })
    assert.ok(!(await api('page-tasks', payload)).list.some(row => row.id === candidate.task.id))
    assert.equal((await detail(candidate.task.id)).task.status, 'PENDING')
  })
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await pageFor(ac.tokens.admin, true)
  await check('真实列表就近评论和完成保留备注，配置记录页可选择关联已有任务', async () => {
    const quick = await create(taskNode('列表就近操作', actor))
    await transition(quick.task.id, 'START')
    await page.goto(origin + '/nocode-app/task-center/manage')
    await page.getByPlaceholder('搜索任务名称').fill(quick.task.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    const row = page.locator(`tr[data-row-key="${quick.task.id}"]`)
    await row.getByRole('button', { name: '评论', exact: true }).click()
    await expect(page.locator('.ant-modal-content:visible')).toHaveCount(0)
    await expect(page.locator('.ant-drawer-content:visible')).toHaveCount(1)
    await page.getByRole('textbox', { name: '评论内容', exact: true }).fill(title('列表直接评论'))
    const commentDrawer = page.locator('.ant-drawer-content').filter({ hasText: `评论：${quick.task.title}` })
    await commentDrawer.getByRole('button', { name: /^确\s*定$/ }).click()
    await expect(commentDrawer).toBeHidden()
    assert.ok((await detail(quick.task.id)).comments.some(item => item.content === title('列表直接评论')))
    await row.getByRole('button', { name: '完成', exact: true }).click()
    await page.getByRole('textbox', { name: '完成备注', exact: true }).fill(title('列表完成备注'))
    const completeDrawer = page.locator('.ant-drawer-content').filter({ hasText: `完成任务：${quick.task.title}` })
    await completeDrawer.getByRole('button', { name: /^确\s*定$/ }).click()
    await expect(completeDrawer).toBeHidden()
    assert.equal((await detail(quick.task.id)).task.status, 'COMPLETED')
    assert.ok((await detail(quick.task.id)).events.some(item => item.note === title('列表完成备注')))
    await page.screenshot({ path: resolve(output, 'quick-list-actions.png'), fullPage: true, animations: 'disabled' })
    await page.goto(origin + `/nocode-app/runtime?id=${appId}&menu=projectmenu&recordId=${projectA.id}`)
    await page.getByRole('button', { name: '关联已有任务', exact: true }).click()
    await page.getByPlaceholder('搜索已有任务').fill(title('可关联的已有任务'))
    await page.getByPlaceholder('搜索已有任务').press('Enter')
    const candidate = page
      .locator('.ant-drawer-content')
      .last()
      .locator('tr[data-row-key]')
      .filter({ hasText: title('可关联的已有任务') })
    await candidate.getByRole('button', { name: '关联', exact: true }).click()
    await expect(candidate).toBeHidden()
    await page.locator('.ant-drawer-close').last().click()
    const linkedRow = page.locator('tr[data-row-key]').filter({ hasText: title('可关联的已有任务') })
    await expect(linkedRow).toBeVisible()
    await linkedRow.getByRole('button', { name: '解除关联', exact: true }).click()
    const unlinkDrawer = page.locator('.ant-drawer-content').filter({ hasText: '解除与当前记录的关联？' }).last()
    await unlinkDrawer.getByRole('button', { name: '解除关联', exact: true }).click()
    await expect(linkedRow).toBeHidden()
  })
  await check('直接在真实列表父行连续拆分，取消不产生空任务，刷新仍保留', async () => {
    const inlineRoot = await create(taskNode('列表拆分验收', actor))
    await page.goto(origin + '/nocode-app/task-center/manage')
    await page.getByPlaceholder('搜索任务名称').fill(inlineRoot.task.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    const parent = page.locator(`tr[data-row-key="${inlineRoot.task.id}"]`)
    await parent.getByRole('button', { name: '＋ 子任务', exact: true }).click()
    const input = page.getByRole('textbox', { name: '子任务名称', exact: true })
    await input.fill(title('行内子任务一'))
    await input.press('Enter')
    await expect(page.getByText(title('行内子任务一'), { exact: true }).first()).toBeVisible()
    await input.fill(title('行内子任务二'))
    await input.press('Enter')
    await expect(page.getByText(title('行内子任务二'), { exact: true }).first()).toBeVisible()
    await expect(input).toHaveValue('')
    await input.press('Escape')
    await expect(input).toHaveCount(0)
    assert.equal((await detail(inlineRoot.task.id)).nodes.length, 3)
    await page.screenshot({ path: resolve(output, 'inline-subtasks.png'), fullPage: true, animations: 'disabled' })
    await parent.getByRole('button', { name: '计划', exact: true }).click()
    const plan = page.locator('.ant-drawer-content').filter({ hasText: '安排执行计划' }).last()
    await expect(page.locator('.ant-modal-content:visible')).toHaveCount(0)
    await plan.getByText('周计划', { exact: true }).click()
    await plan.getByRole('button', { name: /^确\s*定$/ }).click()
    await expect(plan).toBeHidden()
    assert.ok((await detail(inlineRoot.task.id)).task.plans.some(item => item.period === 'WEEK'))
    await parent.getByText(inlineRoot.task.title, { exact: true }).click()
    await page.getByRole('button', { name: '调整当前实例', exact: true }).click()
    const adjustment = page.locator('.ant-drawer-content').filter({ hasText: '调整当前运行实例' }).last()
    const adjustedTitle = title('调整后列表父任务')
    await adjustment.getByPlaceholder('任务名称，直接在列表中填写').first().fill(adjustedTitle)
    await adjustment.locator('textarea').fill('本次浏览器验收调整，只影响当前实例')
    await adjustment.getByRole('button', { name: '预览调整影响', exact: true }).click()
    await adjustment.getByRole('button', { name: '确认调整当前实例', exact: true }).click()
    await expect(adjustment).toBeHidden()
    assert.equal((await detail(inlineRoot.task.id)).task.title, adjustedTitle)
    await page.reload()
    await expect(page.getByPlaceholder('搜索任务名称')).toBeVisible()
    assert.equal((await detail(inlineRoot.task.id)).nodes.length, 3)
  })
  await check('统一任务在列表拆分和依赖推进，树与关系图定位同一实例', async () => {
    const a = taskNode('定位前序', actor, { id: 'position-a' }),
      b = taskNode('定位后序', actor, { id: 'position-b', predecessorIds: ['position-a'] })
    const flow = await create(taskNode('定位总任务', actor), { nodes: [a, b] })
    const first = flow.nodes.find(node => node.title === a.title),
      second = flow.nodes.find(node => node.title === b.title)
    assert.equal(first.role, 'NODE')
    await transition(flow.task.id, 'START')
    await transition(first.id, 'START')
    const standalone = await create(taskNode('独立工作项', actor))
    await page.goto(origin + '/nocode-app/task-center/manage')
    await expect(page.getByText('普通任务', { exact: true })).toHaveCount(0)
    await expect(page.getByText('流程任务', { exact: true })).toHaveCount(0)
    await page.getByPlaceholder('搜索任务名称').fill(flow.task.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    await expect(page.locator(`tr[data-row-key="${flow.task.id}"]`)).toBeVisible()
    await page.getByPlaceholder('搜索任务名称').fill(standalone.task.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    await expect(page.locator(`tr[data-row-key="${standalone.task.id}"]`)).toBeVisible()
    await page.getByPlaceholder('搜索任务名称').fill(a.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    const taskRow = page.locator(`tr[data-row-key="${first.id}"]`)
    await taskRow.getByRole('button', { name: '＋ 子任务', exact: true }).click()
    await page.getByRole('textbox', { name: '子任务名称', exact: true }).fill(title('节点协作子项'))
    await page.getByRole('textbox', { name: '子任务名称', exact: true }).press('Enter')
    await expect(page.getByText(title('节点协作子项'), { exact: true })).toBeVisible()
    await page.getByRole('textbox', { name: '子任务名称', exact: true }).press('Escape')
    const child = (await detail(first.id)).nodes.find(node => node.title === title('节点协作子项'))
    assert.equal(child.role, 'SUBTASK')
    await taskRow.getByRole('button', { name: a.title, exact: true }).click()
    await expect(page.getByRole('navigation', { name: '任务位置' })).toContainText(flow.task.title)
    await expect(page.locator('[role="treeitem"][aria-current="true"]')).toContainText(a.title)
    await page
      .locator('.task-context__relations')
      .getByRole('button', { name: new RegExp(b.title) })
      .click()
    await expect(page.locator('[role="treeitem"][aria-current="true"]')).toContainText(b.title)
    await expect(page.getByRole('button', { name: '开始执行', exact: true })).toBeDisabled()
    await page.getByRole('button', { name: '返回最初办理的任务', exact: true }).click()
    await expect(page.locator('[role="treeitem"][aria-current="true"]')).toContainText(a.title)
    await page.getByRole('button', { name: '查看关系图', exact: true }).click()
    await expect(page.locator('svg [aria-current="true"]')).toHaveAttribute('aria-label', a.title)
    await expect(page.locator('svg path[stroke-dasharray]')).not.toHaveCount(0)
    await page.screenshot({
      path: resolve(output, 'task-current-position.png'),
      fullPage: true,
      animations: 'disabled'
    })
    await page.locator('.ant-drawer-close').last().click()
  })
  await check('真实浏览器三菜单可达；配置项目页显示同一任务、财务与文件', async () => {
    await page.goto(origin + '/nocode-app/task-center')
    await expect(page.locator('.sidebar-l1 .l1-item.active')).toHaveText(/任务中心/)
    await expect(page.locator('.sidebar-l2 .l2-item')).toHaveCount(3)
    await expect(page.locator('.sidebar-l2 .l2-empty')).toHaveCount(0)
    assert.ok(!(await page.evaluate(() => localStorage.getItem('menus'))).includes('旧任务导航缓存'))
    for (const [name, path] of [
      ['任务管理', 'manage'],
      ['任务模板', 'templates'],
      ['我的任务', '']
    ]) {
      await page
        .locator('.sidebar-l2 .l2-item')
        .filter({ hasText: new RegExp('^\\s*' + name + '\\s*$') })
        .click()
      await expect(page).toHaveURL(new RegExp('/nocode-app/task-center' + (path ? '/' + path : '') + '(?:[?#]|$)'))
      await expect(page.locator('.sidebar-l1 .l1-item.active')).toHaveText(/任务中心/)
      await expect(page.locator('.sidebar-l2 .l2-item.active')).toHaveText(new RegExp(name))
      await expect(page.locator('.task-workspace__navigation')).toHaveCount(0)
      if (path === 'manage') {
        await page.getByRole('button', { name: '发起任务', exact: true }).click()
        await expect(page.getByPlaceholder('填写可执行的任务名称')).toBeVisible()
        await expect(page.locator('.ant-modal-content:visible')).toHaveCount(0)
        await page.locator('.ant-drawer-close').last().click()
        await expect(page.getByPlaceholder('填写可执行的任务名称')).toBeHidden()
      }
    }
    await page.screenshot({
      path: resolve(output, 'three-menu-navigation.png'),
      fullPage: true,
      animations: 'disabled'
    })
    await page.goto(origin + `/nocode-app/runtime?id=${appId}&menu=projectmenu&recordId=${projectA.id}`)
    await expect(page.getByText('本项目任务', { exact: true }).first()).toBeVisible()
    await expect(page.getByText(root.task.title, { exact: true }).first()).toBeVisible()
    await expect(page.getByText('数量', { exact: true }).first()).toBeVisible()
    await expect(page.getByText(title('放线复核'), { exact: true }).first()).toBeVisible()
    await expect(page.getByText(title('A 项目费用'), { exact: true }).first()).toBeVisible()
    await expect(page.getByText('项目文件', { exact: true }).first()).toBeVisible()
    await page.screenshot({ path: resolve(output, 'configured-project.png'), fullPage: true, animations: 'disabled' })
  })
  await check('任务型业务表显示同一业务字段；消息深链接定位任务', async () => {
    await page.goto(origin + `/nocode-app/runtime?id=${appId}&menu=workmenu`)
    await expect(page.getByText(title('放线复核'), { exact: true }).first()).toBeVisible()
    await expect(page.getByText('数量', { exact: true }).first()).toBeVisible()
    await page.screenshot({ path: resolve(output, 'task-business-table.png'), fullPage: true, animations: 'disabled' })
    const before = (await ac.page(work)).total
    await page.getByRole('button', { name: '发起任务', exact: true }).click()
    await page.getByPlaceholder('填写可执行的任务名称').fill(title('页面直接发起'))
    await page.getByRole('button', { name: '填写业务数据并发起', exact: true }).click()
    const materialDrawer = page.locator('.ant-drawer-content').last()
    await materialDrawer
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^施工内容$/ }) })
      .locator('input')
      .fill(title('从业务表只填一次'))
    await materialDrawer.getByRole('button', { name: '保存业务内容', exact: true }).click()
    await expect(page.getByText(title('页面直接发起'), { exact: true }).first()).toBeVisible()
    await expect(page.getByText(title('从业务表只填一次'), { exact: true }).first()).toBeVisible()
    assert.equal((await ac.page(work)).total, before + 1)
    assert.ok(page.url().includes('menu=workmenu'))
    await page.getByRole('button', { name: '筛选业务字段', exact: true }).click()
    const filter = page.locator('.ant-drawer-content').filter({ hasText: '高级检索' }).last()
    await filter.getByRole('button', { name: /添加条件/ }).click()
    await filter.getByPlaceholder('请输入值', { exact: true }).fill(title('从业务表只填一次'))
    await filter.getByRole('button', { name: '确认查询', exact: true }).click()
    await expect(filter).toBeHidden()
    await expect(page.locator('tr[data-row-key]')).toHaveCount(1)
    await expect(page.getByText(title('页面直接发起'), { exact: true }).first()).toBeVisible()
    await expect(page.getByText(title('从业务表只填一次'), { exact: true }).first()).toBeVisible()
    await page.screenshot({
      path: resolve(output, 'business-field-filter.png'),
      fullPage: true,
      animations: 'disabled'
    })
    await page.getByRole('button', { name: '清除业务筛选', exact: true }).click()
    await expect(page.getByText(root.task.title, { exact: true }).first()).toBeVisible()
    const recipient = await pageFor(ac.tokens[employee])
    await recipient.goto(origin + receivedNotice.url)
    await expect(recipient.getByText(commentTask.task.title, { exact: true }).first()).toBeVisible()
    await expect(recipient.getByRole('tab', { name: /评论/ })).toHaveAttribute('aria-selected', 'true')
    await expect(recipient.locator('.task-comment--target')).toBeVisible()
    await expect(recipient.getByText(title('已核对，回复原作者'), { exact: true }).first()).toBeVisible()
    assert.equal(await recipient.evaluate(() => window.taskInjected), undefined)
    await recipient.screenshot({
      path: resolve(output, 'comment-deep-link.png'),
      fullPage: true,
      animations: 'disabled'
    })
  })
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  if (browser) await browser.close()
  for (const user of ac.owned.users)
    await ac
      .api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
      .catch(error => errors.push(error.message))
  await ac.persist()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      { checks, errors, tasks, appId, failure: failure?.stack, finishedAt: new Date().toISOString() },
      null,
      2
    )
  )
  console.log(JSON.stringify({ checks: checks.length, output, failure: failure?.message }))
}
if (failure) throw failure
