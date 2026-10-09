import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'
import { verifyTaskDataPolicyBrowser } from './verify-task-data-policy-browser.mjs'

// 默认只打印计划。后端确认部署后显式设置 TASK_DATA_POLICY_RUN=1，防止准备阶段误造数据。
const plan = [
  '无应用成员员工仅通过任务授权访问，应用外仍拒绝',
  '根 GROUP/ALL 权限、业务/反馈独立设置、全层级继承及子级越权拒绝',
  'GROUP 记录增改删及跨任务记录隔离；ALL 可办理应用预存记录',
  '领取不自动开始，前置及四种时间规则不被数据授权绕过',
  '操作归属真实节点和操作者，删除与历史提交快照独立',
  '应用 owner/超管授权上限、模板授权防篡改、停用和终止即时拒绝写入',
  '真实浏览器双范围页签、新增修改删除和权限文案',
  '只取消本轮清单未结束任务、停用临时员工，保留审计'
]
if (process.env.TASK_DATA_POLICY_RUN !== '1') {
  console.log(JSON.stringify({ mode: 'prepare-only', writes: 0, plan }, null, 2))
  process.exit(0)
}

const ac = new TaskDataPolicyAcceptance()
const A = () => ac.tokens[ac.employeeA]
const B = () => ac.tokens[ac.employeeB]
const policy = (business = 'GROUP', feedback = 'GROUP') => ({ version: 1, business, feedback })
const entry = (path, body, token) => ac.taskApi(`entries/${path}`, body, token)
const entryPath = path => `/nocode/tasks/entries/${path}`
const target = (taskId, entryKey, recordId = null, contributionId = null) => ({
  taskId,
  entryKey,
  recordId,
  contributionId
})
const pageBody = (taskId, entryKey, extra = {}) => ({
  taskId,
  entryKey,
  all: false,
  onlyMine: false,
  pageNo: 1,
  pageSize: 100,
  search: '',
  ...extra
})
const rows = (taskId, entryKey, token, extra) => entry('page', pageBody(taskId, entryKey, extra), token)
const ref = (object, record) => ({
  applicationId: ac.applicationId,
  objectId: object.objectId,
  recordId: record.id,
  label: ac.title('业务归属')
})
const sameId = (actual, expected) => assert.equal(String(actual), String(expected))
const milliseconds = value => (typeof value === 'number' ? value : Date.parse(value))
const feedbackEntry = (extra = {}) => ({
  key: 'feedback',
  name: '施工日志',
  binding: ac.binding('feedback'),
  dataMode: 'ROOT_SHARED',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: null,
  writableFieldIds: null,
  required: false,
  allowAll: false,
  ...extra
})
const configuredRoot = (suffix, extra = {}) =>
  ac.node(suffix, {
    binding: ac.binding('business'),
    dataPolicy: policy(),
    entries: [feedbackEntry()],
    ...extra
  })
const saveBody = (taskId, entryKey, object, values, row = null) => ({
  taskId,
  entryKey,
  contributionId: null,
  record: {
    ...ac.saveBody(object, values, row),
    formId: entryKey === '__business' ? 'business' : 'feedback',
    requestKey: randomUUID()
  }
})
async function save(taskId, entryKey, object, values, token, row = null) {
  const saved = await entry('save', saveBody(taskId, entryKey, object, values, row), token)
  assert.ok(saved.contributionId, '真实写入必须返回稳定贡献身份')
  const record = saved.handling?.result?.record
  assert.ok(record?.id, '真实写入必须返回原业务记录')
  await ac.rememberRecord(object, record)
  return { saved, record }
}
async function current(taskId, key, row, token) {
  return (await entry('form', target(taskId, key, row.id), token)).record.record
}
async function remove(taskId, key, row, token) {
  assert.ok(
    ac.records.some(item => item.id === row.id),
    '只删除本轮登记的记录'
  )
  return entry(
    'delete',
    { taskId, entryKey: key, recordId: row.id, expectedRevision: row.revision, requestKey: randomUUID() },
    token
  )
}
function assertSource(item, taskId, actorId, operation) {
  assert.ok(
    item.sources.some(
      source => source.taskId === taskId && String(source.actorId) === String(actorId) && source.operation === operation
    ),
    `记录来源应包含真实节点 ${taskId} / 员工 ${actorId} / ${operation}`
  )
}

let group, all, other, child, grandchild, predecessor, fixed, outside, businessRow, feedbackRow, submitted
let failure, groupReady, working, shared, allReady, timeReady

async function verifyTemplate() {
  await ac.check('发布模板承载 owner 批准的根授权，普通发起者可用但不能篡改扩大', async () => {
    const root = configuredRoot('模板根授权', { dataPolicy: policy('ALL', 'GROUP') })
    const template = await ac.api('/nocode/task-templates/save', {
      id: null,
      expectedRevision: null,
      name: ac.title('受控授权模板'),
      description: '仅此轮验收，已发布根配置不允许发起者扩大授权',
      kind: 'ORDINARY',
      task: root,
      nodes: []
    })
    ac.templates.push({ id: template.id, name: template.name })
    await ac.persist()
    const published = await ac.api('/nocode/task-templates/publish', {
      id: template.id,
      expectedRevision: template.revision
    })
    assert.deepEqual(published.task.dataPolicy, policy('ALL', 'GROUP'))
    const approved = { ...published.task, id: randomUUID(), title: ac.title('员工按模板发起') }
    const created = await ac.create(approved, { templateId: template.id, templateVersion: published.version }, A())
    assert.deepEqual(created.task.dataPolicy, policy('ALL', 'GROUP'))
    await ac.command(created.task.id, 'CLAIM', A())
    await ac.command(created.task.id, 'START', A())
    const capabilities = await entry('list', { id: created.task.id }, A())
    assert.equal(capabilities.find(item => item.category === 'BUSINESS').effectivePolicy, 'ALL')
    assert.equal(capabilities.find(item => item.category === 'FEEDBACK').effectivePolicy, 'GROUP')
    assert.ok((await rows(created.task.id, '__business', A(), { all: true })).total > 0)
    await ac.denied(entryPath('page'), pageBody(created.task.id, 'feedback', { all: true }), A())
    await save(created.task.id, 'feedback', ac.feedback, { name: ac.title('模板授权登记'), quantity: 1 }, A())
    await ac.denied(
      '/nocode/tasks/create',
      {
        task: { ...approved, id: randomUUID(), title: ac.title('模板不可扩权'), dataPolicy: policy('ALL', 'ALL') },
        templateId: template.id,
        templateVersion: published.version,
        requestKey: randomUUID()
      },
      A()
    )
    assert.deepEqual(
      (await ac.api(`/nocode/task-templates/version?id=${template.id}&version=${published.version}`)).task.dataPolicy,
      policy('ALL', 'GROUP')
    )
    return { templateId: template.id, version: published.version, taskId: created.task.id }
  })
}

async function verifyHttp() {
  await ac.check('员工没有应用成员权限，应用运行时读写均拒绝', async () => {
    const authorization = await ac.api(`/nocode/application/authorization?id=${ac.applicationId}`)
    assert.ok(
      !(authorization.members || []).some(member => [ac.employeeA, ac.employeeB].includes(String(member.principalId)))
    )
    for (const token of [A(), B()]) {
      await ac.denied('/nocode/runtime/page', ac.query(ac.business), token)
      await ac.denied('/nocode/runtime/save', ac.saveBody(ac.business, { name: ac.title('任务外禁止新增') }), token)
    }
  })

  await ac.check('普通任务创建者不能给自己授予非本人应用的 GROUP/ALL 数据权限', async () => {
    for (const scope of ['GROUP', 'ALL']) {
      await ac.denied(
        '/nocode/tasks/create',
        {
          task: configuredRoot(`禁止自授${scope}`, { dataPolicy: policy(scope, scope) }),
          requestKey: randomUUID()
        },
        A()
      )
    }
  })

  groupReady = await ac.check('owner 配置一次根策略，业务与反馈入口自动供全部层级继承', async () => {
    const root = configuredRoot('本组授权', { assignmentMode: 'UNASSIGNED' })
    child = ac.node('施工执行')
    grandchild = ac.node('施工复核', {
      parentId: child.id,
      schedule: { mode: 'PLAN_START', fixedStart: null, fixedEnd: null, offsetDays: 2, durationDays: 1 }
    })
    predecessor = ac.node('前置完成接续', {
      predecessorIds: [child.id],
      schedule: { mode: 'PREDECESSOR', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 1 }
    })
    fixed = ac.node('指定日期工作', {
      schedule: {
        mode: 'FIXED',
        fixedStart: Date.parse('2000-01-02T09:00:00+08:00'),
        fixedEnd: null,
        offsetDays: 0,
        durationDays: 0
      }
    })
    group = await ac.create(root, {
      applicationId: ac.applicationId,
      project: ref(ac.business, ac.existingBusiness),
      existingRecord: ref(ac.business, ac.existingBusiness),
      plannedStart: Date.parse('2000-01-01T09:00:00+08:00'),
      nodes: [child, grandchild, predecessor, fixed]
    })
    // 直接编排的一级子节点 parentId 为空；服务端生成正式身份后依唯一夹具名称读取回执。
    const actualNode = draft => {
      const actual = group.nodes.find(node => node.title === draft.title)
      assert.ok(actual, `缺少编排节点回执：${draft.title}`)
      return actual
    }
    ;[child, grandchild, predecessor, fixed] = [child, grandchild, predecessor, fixed].map(actualNode)
    assert.equal(group.nodes.length, 5)
    for (const node of group.nodes) {
      assert.deepEqual(node.dataPolicy, policy())
      const entries = await entry('list', { id: node.id })
      assert.deepEqual(entries.map(item => item.config.key).sort(), ['__business', 'feedback'])
      assert.equal(entries.find(item => item.config.key === '__business').category, 'BUSINESS')
      assert.equal(entries.find(item => item.config.key === 'feedback').category, 'FEEDBACK')
      assert.ok(entries.every(item => item.effectivePolicy === 'GROUP'))
    }
    const linked = (await rows(group.task.id, '__business')).list.find(
      item => item.record?.id === ac.existingBusiness.id
    )
    assert.ok(linked, '同对象根归属记录应明确关联到业务入口')
    assertSource(linked, group.task.id, ac.adminId, 'LINKED')
    return { rootId: group.task.id, nodes: group.nodes.map(node => node.id) }
  })

  await ac.check(
    '拒绝子任务配置独立权限或用独立入口替换根反馈配置',
    async () => {
      for (const extra of [
        { dataPolicy: policy('ALL', 'ALL') },
        { entries: [feedbackEntry({ dataMode: 'INDEPENDENT' })] }
      ]) {
        await ac.denied('/nocode/tasks/create', {
          parentId: group.task.id,
          task: ac.node('禁止子级覆盖', extra),
          requestKey: randomUUID()
        })
      }
    },
    [groupReady]
  )

  working = await ac.check(
    '领取后仅可查看；开始后允许写入，未分配总任务不被隐式开始',
    async () => {
      await ac.command(child.id, 'CLAIM', A())
      const claimed = (await ac.taskApi('detail', { id: child.id }, A())).task
      assert.equal(claimed.status, 'PENDING')
      assert.equal(claimed.actualStart, null)
      assert.ok((await rows(child.id, '__business', A())).list.some(item => item.record?.id === ac.existingBusiness.id))
      assert.ok((await entry('list', { id: child.id }, A())).every(item => !item.canWrite && !item.canDelete))
      await ac.denied(
        entryPath('save'),
        saveBody(child.id, 'feedback', ac.feedback, { name: ac.title('未开始禁止登记') }),
        A()
      )
      await ac.command(child.id, 'START', A())
      const root = (await ac.taskApi('detail', { id: group.task.id })).task
      assert.equal(root.status, 'PENDING')
      assert.equal(root.assigneeId, null)
      assert.equal(root.actualStart, null)
      assert.ok((await entry('list', { id: child.id }, A())).every(item => item.canWrite && item.canDelete))
    },
    [groupReady]
  )

  shared = await ac.check(
    '无应用成员员工可新增业务与反馈，子孙共享同记录并追溯真实节点和人员',
    async () => {
      businessRow = (
        await save(child.id, '__business', ac.business, { name: ac.title('组内施工内容'), quantity: 3 }, A())
      ).record
      feedbackRow = (
        await save(child.id, 'feedback', ac.feedback, { name: ac.title('组内施工日志'), quantity: 4 }, A())
      ).record
      await ac.command(grandchild.id, 'CLAIM', B())
      await ac.command(grandchild.id, 'START', B())
      for (const [key, record, object] of [
        ['__business', businessRow, ac.business],
        ['feedback', feedbackRow, ac.feedback]
      ]) {
        const first = (await rows(grandchild.id, key, B())).list.find(item => item.record?.id === record.id)
        assert.ok(first)
        assertSource(first, child.id, ac.employeeA, 'CREATED')
        await save(
          grandchild.id,
          key,
          object,
          { name: record.values[object.ids.name], quantity: 8 },
          B(),
          await current(grandchild.id, key, record, B())
        )
        const modified = (await rows(child.id, key, A())).list.find(item => item.record?.id === record.id)
        assertSource(modified, grandchild.id, ac.employeeB, 'UPDATED')
        assert.equal(Number(modified.record.values[object.ids.quantity]), 8)
      }
      await ac.denied(
        entryPath('save'),
        saveBody(child.id, 'feedback', ac.feedback, { name: ac.title('不能冒用他人节点登记') }),
        B()
      )
      await ac.denied('/nocode/runtime/page', ac.query(ac.business), A())
      await ac.denied('/nocode/runtime/save', ac.saveBody(ac.feedback, { name: ac.title('直接接口仍拒绝') }), B())
    },
    [working]
  )

  await ac.check(
    '本总任务数据拒绝其他总任务记录、应用预存记录及伪造全量开关',
    async () => {
      outside = await ac.save(ac.business, { name: ac.title('另一总任务资料'), quantity: 99 })
      await ac.rememberRecord(ac.business, outside)
      other = await ac.create(configuredRoot('另一总任务'), { existingRecord: ref(ac.business, outside) })
      for (const [key, object, record] of [
        ['__business', ac.business, outside],
        ['feedback', ac.feedback, ac.existingFeedback]
      ]) {
        assert.ok(!(await rows(child.id, key, A())).list.some(item => item.record?.id === record.id))
        await ac.denied(entryPath('page'), pageBody(child.id, key, { all: true }), A())
        await ac.denied(entryPath('form'), target(child.id, key, record.id), A())
        await ac.denied(
          entryPath('save'),
          saveBody(child.id, key, object, { name: ac.title('禁止跨组修改') }, record),
          A()
        )
        await ac.denied(
          entryPath('delete'),
          {
            taskId: child.id,
            entryKey: key,
            recordId: record.id,
            expectedRevision: record.revision,
            requestKey: randomUUID()
          },
          A()
        )
        await ac.denied(
          entryPath('link'),
          { taskId: child.id, entryKey: key, recordId: record.id, requestKey: randomUUID() },
          A()
        )
        assert.equal((await ac.get(object, record)).record.values[object.ids.name], record.values[object.ids.name])
      }
    },
    [working]
  )

  allReady = await ac.check('ALL 可以查看和修改应用预存业务/反馈记录，仍只经本任务入口授权', async () => {
    all = await ac.create(
      configuredRoot('全列表授权', {
        dataPolicy: policy('ALL', 'ALL'),
        schedule: {
          mode: 'FIXED',
          fixedStart: null,
          fixedEnd: Date.parse('2099-01-01T09:00:00+08:00'),
          offsetDays: 0,
          durationDays: 0
        }
      })
    )
    await ac.command(all.task.id, 'CLAIM', A())
    await ac.command(all.task.id, 'START', A())
    for (const [key, object, record] of [
      ['__business', ac.business, ac.existingBusiness],
      ['feedback', ac.feedback, ac.existingFeedback]
    ]) {
      const allRows = await rows(all.task.id, key, A(), { all: true })
      assert.ok(allRows.list.some(item => item.record?.id === record.id))
      await save(
        all.task.id,
        key,
        object,
        { name: record.values[object.ids.name], quantity: 12 },
        A(),
        await current(all.task.id, key, record, A())
      )
      const linked = (await rows(all.task.id, key, A())).list.find(item => item.record?.id === record.id)
      assertSource(linked, all.task.id, ac.employeeA, 'UPDATED')
    }
    await ac.denied('/nocode/runtime/page', ac.query(ac.feedback), A())
    await ac.denied(entryPath('page'), pageBody(all.task.id, 'feedback', { all: true }), B())
    await ac.denied(
      entryPath('save'),
      saveBody(all.task.id, 'feedback', ac.feedback, { name: ac.title('不得借用他人全表授权') }),
      B()
    )
    return { rootId: all.task.id }
  })

  await ac.check(
    '授权删除只作用自有记录，公开审计保留真实节点/操作者/入口/记录',
    async () => {
      for (const [key, object] of [
        ['__business', ac.business],
        ['feedback', ac.feedback]
      ]) {
        const temporary = (await save(grandchild.id, key, object, { name: ac.title(`删除${key}`), quantity: 1 }, B()))
          .record
        assert.equal(await remove(grandchild.id, key, temporary, B()), true)
        const deletedDetail = await ac.taskApi('detail', { id: grandchild.id }, B())
        assert.ok(
          deletedDetail.events.some(
            event =>
              event.taskId === grandchild.id &&
              String(event.actorId) === String(ac.employeeB) &&
              event.type === 'BUSINESS_SAVED' &&
              event.note?.includes('删除业务数据') &&
              event.note.includes(`入口=${key}`) &&
              event.note.includes(`记录=${temporary.id}`)
          ),
          '删除审计必须保留真实节点、操作者、入口和被删除记录身份'
        )
        assert.ok(!(await rows(child.id, key, A())).list.some(item => item.record?.id === temporary.id))
        await ac.denied(entryPath('form'), target(grandchild.id, key, temporary.id), B())
      }
    },
    [shared]
  )

  timeReady = await ac.check(
    '四种时间口径不变，前置未完成拒绝提前开始或填写反馈',
    async () => {
      const detail = await ac.taskApi('detail', { id: group.task.id })
      const node = id => detail.nodes.find(item => item.id === id)
      assert.equal(node(child.id).expectedStart, null)
      assert.equal(node(child.id).expectedEnd, null)
      assert.equal(milliseconds(node(grandchild.id).expectedStart), Date.parse('2000-01-03T09:00:00+08:00'))
      assert.equal(milliseconds(node(fixed.id).expectedStart), Date.parse('2000-01-02T09:00:00+08:00'))
      assert.equal(node(fixed.id).expectedEnd, null)
      assert.equal(node(predecessor.id).expectedStart, null)
      assert.equal(node(predecessor.id).expectedEnd, null)
      await ac.command(predecessor.id, 'CLAIM', A())
      const pending = (await ac.taskApi('detail', { id: predecessor.id })).task
      await ac.denied(
        '/nocode/tasks/transition',
        { id: predecessor.id, action: 'START', expectedRevision: pending.revision, requestKey: randomUUID() },
        A()
      )
      await ac.denied(
        entryPath('save'),
        saveBody(predecessor.id, 'feedback', ac.feedback, { name: ac.title('禁止提前登记') }),
        A()
      )
      await ac.command(grandchild.id, 'COMPLETE', B())
      submitted = await entry('materials', { id: grandchild.id }, B())
      assert.ok(submitted.some(material => material.submissions.length > 0))
      await ac.command(child.id, 'COMPLETE', A())
      const finished = (await ac.taskApi('detail', { id: child.id })).task
      const ready = (await ac.taskApi('detail', { id: predecessor.id })).task
      assert.equal(milliseconds(ready.expectedStart), milliseconds(finished.actualEnd))
      await ac.command(predecessor.id, 'START', A())
      assert.equal((await ac.taskApi('detail', { id: group.task.id })).task.status, 'PENDING')
      if (all) {
        assert.equal(all.task.expectedStart, null)
        assert.equal(milliseconds(all.task.expectedEnd), Date.parse('2099-01-01T09:00:00+08:00'))
      }
    },
    [shared]
  )

  await ac.check(
    '后续节点修改同一业务记录，不覆盖已完成节点的提交快照',
    async () => {
      assert.ok(submitted, '前置验收必须已完成提交')
      await save(
        predecessor.id,
        'feedback',
        ac.feedback,
        { name: ac.title('后续节点修改'), quantity: 20 },
        A(),
        await current(predecessor.id, 'feedback', feedbackRow, A())
      )
      assert.deepEqual(await entry('materials', { id: grandchild.id }, B()), submitted)
    },
    [timeReady]
  )

  await ac.check('没有业务 binding 的项目仅是归属，不按对象猜表单或生成业务入口', async () => {
    const projectOnly = await ac.create(ac.node('只记录归属', { dataPolicy: policy() }), {
      project: ref(ac.business, ac.existingBusiness)
    })
    assert.equal(projectOnly.task.binding, null)
    assert.equal(projectOnly.task.business, null)
    assert.deepEqual(await entry('list', { id: projectOnly.task.id }), [])
    return { taskId: projectOnly.task.id }
  })

  await ac.check('旧协议缺少根授权时继续遵守原应用权限，不自动扩大到全员', async () => {
    const legacy = ac.node('旧协议保留授权', { binding: ac.binding('business'), entries: [feedbackEntry()] })
    const created = await ac.create(legacy)
    assert.equal(created.task.dataPolicy ?? null, null)
    await ac.command(created.task.id, 'CLAIM', A())
    await ac.command(created.task.id, 'START', A())
    await ac.denied(entryPath('form'), target(created.task.id, 'feedback'), A())
    await ac.denied(
      entryPath('save'),
      saveBody(created.task.id, 'feedback', ac.feedback, { name: ac.title('旧协议禁止越权') }),
      A()
    )
    return { taskId: created.task.id }
  })
}

async function verifyRevocation() {
  await ac.check(
    '共享授权收紧后旧编辑内容不能继续保存，恢复只影响本夹具授权',
    async () => {
      const before = await current(all.task.id, 'feedback', ac.existingFeedback, A())
      try {
        await ac.share(ac.feedback, ac.grant(ac.feedback, { actions: ['READ'], writeFields: [] }))
        await ac.denied(
          entryPath('save'),
          saveBody(all.task.id, 'feedback', ac.feedback, { name: ac.title('收紧后禁止更新') }, before),
          A()
        )
      } finally {
        await ac.share(ac.feedback, ac.grant(ac.feedback))
      }
    },
    [allReady]
  )
  await ac.check(
    '员工停用即时拒绝旧会话写入，恢复账号后任务取消也拒绝',
    async () => {
      const before = await current(all.task.id, 'feedback', ac.existingFeedback, A())
      try {
        await ac.api('/system/user/update-status', { id: ac.employeeA, status: 1 }, undefined, 'PUT')
        await ac.denied(
          entryPath('save'),
          saveBody(all.task.id, 'feedback', ac.feedback, { name: ac.title('停用禁止更新') }, before),
          A()
        )
      } finally {
        await ac.api('/system/user/update-status', { id: ac.employeeA, status: 0 }, undefined, 'PUT')
      }
      // 停用会撤销旧会话；恢复后重新登录，确保下个断言确实验证任务终止而非登录失效。
      await ac.user('a')
      await current(all.task.id, 'feedback', ac.existingFeedback, A())
      await ac.command(all.task.id, 'CANCEL')
      await ac.denied(
        entryPath('save'),
        saveBody(all.task.id, 'feedback', ac.feedback, { name: ac.title('取消禁止更新') }, before),
        A()
      )
    },
    [allReady]
  )
}

try {
  await ac.login()
  await ac.prepareUsers()
  await ac.prepareApplication()
  await verifyHttp()
  await verifyTemplate()
  if (process.env.TASK_DATA_POLICY_HTTP_ONLY !== '1') {
    await verifyTaskDataPolicyBrowser(ac, { all, allReady, predecessor, grandchild, timeReady })
  }
  await verifyRevocation()
} catch (error) {
  failure = error.stack || error.message
  console.error(failure)
} finally {
  await ac.finish()
  await writeFile(
    resolve(ac.output, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        prefix: ac.prefix,
        checks: ac.results,
        errors: ac.errors,
        cleanup: ac.cleanup,
        screenshots: ac.screenshots,
        failure,
        browserStatus: process.env.TASK_DATA_POLICY_HTTP_ONLY === '1' ? 'skipped-explicitly' : 'attempted'
      },
      null,
      2
    )
  )
  process.exitCode =
    failure ||
    ac.results.some(item => item.status !== 'passed') ||
    ac.errors.length ||
    ac.cleanup.some(item => item.status === 'failed')
      ? 1
      : 0
  console.log(
    JSON.stringify(
      {
        output: ac.output,
        passed: ac.results.filter(item => item.status === 'passed').length,
        failed: ac.results.filter(item => item.status === 'failed').length,
        blocked: ac.results.filter(item => item.status === 'blocked').length
      },
      null,
      2
    )
  )
}
