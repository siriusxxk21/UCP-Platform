import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 正式接口验证，沿用当前登录配置；只创建和操作带本轮前缀的夹具，保留给浏览器复核。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.output = resolve(process.env.TASK_EXPERIENCE_OUTPUT || '.work/task-experience', ac.prefix)
ac.owned.tasks = []
const api = (path, body) => ac.api(`/nocode/tasks/${path}`, body)
const dateOf = value => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(value)
const today = dateOf(new Date())
const yesterday = dateOf(new Date(Date.now() - 86_400_000))
const beforeYesterday = dateOf(new Date(Date.now() - 2 * 86_400_000))
const nextWeek = dateOf(new Date(Date.now() + 7 * 86_400_000))
// 正式 HTTP 的 LocalDateTime 使用毫秒时间戳，前端客户端转换在本工具中不参与。
const startOf = date => Date.parse(`${date}T00:00:00+08:00`)
const node = (title, extra = {}) => ({
  id: randomUUID(),
  parentId: null,
  title: `${ac.prefix} ${title}`,
  description: '',
  assigneeId: null,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'FIXED', fixedStart: startOf(yesterday), offsetDays: 0, durationDays: 10 },
  predecessorIds: [],
  binding: null,
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
  ...extra
})
async function create(title, extra = {}, command = {}) {
  const detail = await api('create', { task: node(title, extra), requestKey: randomUUID(), ...command })
  ac.owned.tasks.push({ id: detail.task.id, title: detail.task.title })
  await ac.persist()
  return detail
}
async function transition(id, action) {
  const detail = await api('detail', { id })
  return api('transition', {
    id,
    action,
    expectedRevision: detail.task.revision,
    note: '八项体验优化回归',
    requestKey: randomUUID()
  })
}
const page = extra =>
  api('page', { scope: 'MINE', tab: 'TODAY', date: today, search: ac.prefix, pageNo: 1, pageSize: 100, ...extra })
const plan = (id, date) => api('plan', { ids: [id], period: 'DAY', date, include: true, target: 'SELF' })
let applicationId, object
const entry = (key, extra = {}) => ({
  key,
  name: key === 'work' ? '施工记录' : '验收记录',
  binding: { applicationId, formId: 'form', entryId: null },
  dataMode: 'ROOT_SHARED',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: null,
  writableFieldIds: null,
  required: true,
  allowAll: false,
  ...extra
})
const saveFeedback = (taskId, entryKey, name) =>
  api('entries/save', {
    taskId,
    entryKey,
    contributionId: null,
    record: { ...ac.saveBody(object, { name }), formId: 'form', requestKey: randomUUID() }
  })
const readiness = id => api('readiness', { id })
const coreOnly = process.env.TASK_EXPERIENCE_CORE_ONLY === '1'

try {
  await ac.login()
  await ac.record('日计划承接、逾期与分页总数，续做保留旧计划且不复制任务', async () => {
    const carried = (await create('此前安排')).task.id
    const planned = (await create('今日安排')).task.id
    const overdue = (
      await create('逾期未安排', {
        schedule: { mode: 'FIXED', fixedStart: startOf(beforeYesterday), offsetDays: 0, durationDays: 0 }
      })
    ).task.id
    await plan(carried, beforeYesterday)
    await plan(carried, yesterday)
    await plan(planned, today)
    assert.deepEqual(
      (await page({ dayScope: 'PLANNED' })).list.map(row => row.id),
      [planned]
    )
    const backlog = await page({ dayScope: 'CARRYOVER', pageSize: 1 })
    assert.equal(backlog.total, 1)
    assert.equal(backlog.list[0].id, carried)
    assert.deepEqual(
      (await page({ dayScope: 'OVERDUE' })).list.map(row => row.id),
      [overdue]
    )
    await plan(carried, today)
    await plan(carried, today)
    assert.equal((await page({ dayScope: 'CARRYOVER' })).total, 0)
    assert.equal((await page({ dayScope: 'PLANNED' })).total, 2)
    const detail = await api('detail', { id: carried })
    assert.equal(detail.task.id, carried)
    assert.equal(detail.task.plans.filter(p => p.period === 'DAY').length, 3)
    assert.equal(detail.task.status, 'PENDING')
    const manager = await page({ scope: 'MANAGE', tab: 'ALL', attention: 'OVERDUE', pageSize: 1 })
    assert.equal(manager.total, 1)
    assert.equal(manager.list[0].id, overdue)
    return { carried, planned, overdue }
  })

  await ac.record('取消预览包含直接和传递后续，取消后仍阻断并可按管理关注定位', async () => {
    const a = node('前置A'),
      b = node('后续B'),
      c = node('后续C')
    b.predecessorIds = [a.id]
    c.predecessorIds = [b.id]
    const created = await create('取消影响实例', {}, { nodes: [a, b, c] })
    const find = title => created.nodes.find(item => item.title === title).id
    const aid = find(a.title),
      bid = find(b.title),
      cid = find(c.title)
    await transition(created.task.id, 'START')
    const check = await readiness(aid)
    assert.equal(check.canCancel, true)
    assert.ok(check.cancellationImpacts.some(item => item.taskId === bid && item.direct))
    assert.ok(check.cancellationImpacts.some(item => item.taskId === cid && !item.direct))
    await transition(aid, 'CANCEL')
    const after = await api('detail', { id: bid })
    assert.equal(after.task.canStart, false)
    assert.match(after.task.blockedReason, /取消/)
    assert.ok((await page({ scope: 'MANAGE', tab: 'ALL', attention: 'BLOCKED' })).list.some(row => row.id === bid))
    return { root: created.task.id, a: aid, b: bid, c: cid }
  })

  await ac.record('父子任务完成清单只读，子项结束后管理关注可定位父项收尾', async () => {
    const root = (await create('父项收尾检查')).task.id
    await transition(root, 'START')
    const child = (await create('收尾检查子项', {}, { parentId: root })).task.id
    const before = await api('detail', { id: root })
    const blocked = await readiness(root)
    assert.equal(blocked.canComplete, false)
    assert.ok(blocked.checks.some(check => check.code === 'CHILDREN' && !check.passed))
    const after = await api('detail', { id: root })
    assert.equal(after.task.revision, before.task.revision)
    assert.deepEqual(after.events, before.events)
    assert.ok(
      !(await page({ scope: 'MANAGE', tab: 'ALL', attention: 'CHILDREN_ENDED' })).list.some(row => row.id === root)
    )
    await transition(child, 'START')
    await transition(child, 'COMPLETE')
    assert.ok(
      (await page({ scope: 'MANAGE', tab: 'ALL', attention: 'CHILDREN_ENDED' })).list.some(row => row.id === root)
    )
    assert.equal((await readiness(root)).canComplete, true)
    await transition(root, 'COMPLETE')
    assert.equal((await api('detail', { id: root })).task.status, 'COMPLETED')
    return { root, child }
  })

  const uiCarry = (await create('浏览器续做验证')).task.id
  await plan(uiCarry, yesterday)
  const future = (
    await create('未来日期不应标为已逾期', {
      schedule: { mode: 'FIXED', fixedStart: startOf(nextWeek), offsetDays: 0, durationDays: 0 }
    })
  ).task.id
  assert.ok(!(await page({ dayScope: 'OVERDUE', date: nextWeek })).list.some(row => row.id === future))
  await ac.record('历史日期不改变当前开始受阻判定，分页计数与行内可开始一致', async () => {
    const filter = { scope: 'MANAGE', tab: 'ALL', attention: 'BLOCKED' }
    const current = await page(filter)
    const historical = await page({ ...filter, date: beforeYesterday })
    assert.deepEqual(historical.list.map(row => row.id).sort(), current.list.map(row => row.id).sort())
    assert.equal(historical.total, current.total)
    assert.ok(historical.list.every(row => row.canStart === false && row.blockedReason))
    assert.ok(historical.list.some(row => row.id === future))
    assert.ok(!historical.list.some(row => row.id === uiCarry))
    const first = await page({ ...filter, date: beforeYesterday, pageSize: 1 })
    assert.equal(first.total, historical.total)
    assert.equal(first.list.length, 1)
    return { currentTotal: current.total, historicalTotal: historical.total, future, readyTaskId: uiCarry }
  })
  await ac.record('完成和取消响应未知时，原完整请求重放恢复结果且不重复事件', async () => {
    const results = []
    for (const action of ['COMPLETE', 'CANCEL']) {
      const id = (await create(`${action} 原请求恢复`)).task.id
      await transition(id, 'START')
      const before = await api('detail', { id })
      const command = {
        id,
        action,
        expectedRevision: before.task.revision,
        note: '首次请求冻结的办理备注',
        requestKey: randomUUID()
      }
      // 丢弃首次响应，再用首次 revision/note/key 重放；这不是浏览器断网注入。
      await api('transition', command)
      const recovered = await api('transition', command)
      const detail = await api('detail', { id })
      assert.equal(recovered.task.status, action === 'COMPLETE' ? 'COMPLETED' : 'CANCELLED')
      assert.equal(recovered.task.revision, before.task.revision + 1)
      assert.equal(detail.events.length, before.events.length + 1)
      results.push({ id, status: detail.task.status, revision: detail.task.revision })
    }
    return results
  })
  await ac.record('日计划浏览器夹具已准备（不计为页面已通过）', async () => ({
    carryTaskId: uiCarry,
    prefix: ac.prefix
  }))

  // 开发库升级待授权时可以单独验证不依赖业务文件表的任务链路，不能记为完整回归。
  if (coreOnly) {
    console.log(
      `PASS ${ac.checks.length - 1} 组基础任务 HTTP；另 1 项为页面夹具准备，业务反馈场景未运行；证据：${ac.output}`
    )
  } else {
    object = await ac.object('task_experience', '任务办理体验回归', [ac.field('name', 'TEXT', '施工内容')])
    ac.app = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${ac.prefix}_experience`,
      name: `${ac.prefix} 任务体验应用`,
      description: '八项体验优化独立验收夹具',
      definition: {
        objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
        resources: [
          {
            id: 'form',
            code: 'form',
            name: '施工业务信息',
            kind: 'FORM',
            config: {
              objectId: object.objectId,
              nodes: [{ id: 'name', type: 'FIELD', fieldId: object.ids.name, children: [] }],
              detailIds: []
            }
          }
        ]
      }
    })
    applicationId = ac.app.application.id
    ac.owned.applications.push({ id: applicationId, code: ac.app.application.code })
    await ac.persist()
    await ac.share(object, ac.grant(object))
    await ac.api('/nocode/application/publish', {
      id: applicationId,
      expectedRevision: ac.app.application.revision,
      reason: '任务体验回归'
    })

    await ac.record('完成条件一次汇总、只读无事件副作用，补齐材料后可完成', async () => {
      const task = (await create('两项必需反馈', { entries: [entry('work'), entry('review')] })).task.id
      await transition(task, 'START')
      const child = (
        await create(
          '未结束子项',
          { entries: [entry('child-note', { required: false, dataMode: 'INDEPENDENT' })] },
          { parentId: task }
        )
      ).task.id
      const before = await api('detail', { id: task })
      const initial = await readiness(task)
      assert.equal(initial.canComplete, false)
      assert.ok(initial.checks.some(c => c.code === 'CHILDREN' && !c.passed))
      assert.equal(initial.checks.filter(c => c.code === 'FEEDBACK' && !c.passed).length, 2)
      await readiness(task)
      const after = await api('detail', { id: task })
      assert.equal(after.task.revision, before.task.revision)
      assert.deepEqual(after.events, before.events)
      await transition(child, 'START')
      await transition(child, 'COMPLETE')
      const closing = await page({ scope: 'MANAGE', tab: 'ALL', attention: 'CHILDREN_ENDED' })
      assert.ok(closing.list.some(row => row.id === task))
      assert.equal((await readiness(task)).canComplete, false)
      await saveFeedback(task, 'work', '第一份有效施工反馈')
      assert.equal((await readiness(task)).checks.filter(c => c.code === 'FEEDBACK' && !c.passed).length, 1)
      await saveFeedback(task, 'review', '第一份有效验收反馈')
      assert.equal((await readiness(task)).canComplete, true)
      await transition(task, 'COMPLETE')
      assert.equal((await api('detail', { id: task })).task.status, 'COMPLETED')
      return { task, child }
    })

    await ac.record('本节点反馈要求不能被父材料满足，显式关联同一记录后通过且不复制业务数据', async () => {
      const root = (await create('本节点反馈根任务', { entries: [entry('work', { requireOwnContribution: true })] }))
        .task.id
      await transition(root, 'START')
      const saved = await saveFeedback(root, 'work', '沿用同一记录的施工内容')
      const recordId = saved.handling.result.record.id
      const child = (await create('继承且要求本节点贡献', {}, { parentId: root })).task.id
      await transition(child, 'START')
      const read = await readiness(child)
      assert.equal(read.canComplete, false)
      assert.ok(read.checks.some(c => c.code === 'FEEDBACK' && !c.passed))
      assert.equal((await api('entries/list', { id: child }))[0].config.requireOwnContribution, true)
      const rejected = await ac.request('/nocode/tasks/transition', {
        id: child,
        action: 'COMPLETE',
        expectedRevision: (await api('detail', { id: child })).task.revision,
        requestKey: randomUUID()
      })
      assert.notEqual(rejected.code, 0)
      const beforeRecords = await ac.page(object)
      await api('entries/link', { taskId: child, entryKey: 'work', recordId, requestKey: randomUUID() })
      assert.equal((await readiness(child)).canComplete, true)
      const records = await api('entries/page', {
        taskId: root,
        entryKey: 'work',
        all: false,
        onlyMine: false,
        pageNo: 1,
        pageSize: 100,
        search: ''
      })
      assert.equal(new Set(records.list.map(item => item.record?.id).filter(Boolean)).size, 1)
      assert.equal((await ac.page(object)).total, beforeRecords.total)
      await transition(child, 'COMPLETE')
      return { root, child, recordId }
    })

    const uiFeedback = (await create('浏览器完成清单', { entries: [entry('work')] })).task.id
    await transition(uiFeedback, 'START')
    await ac.record('浏览器复核夹具已准备（不计为页面已通过）', async () => ({
      carryTaskId: uiCarry,
      feedbackTaskId: uiFeedback,
      prefix: ac.prefix,
      applicationId,
      feedbackUrl: `${process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'}/nocode-app/task-center/manage?taskId=${uiFeedback}`
    }))
    console.log(`PASS ${ac.checks.length} 组正式 HTTP；证据：${ac.output}`)
  }
} finally {
  await ac.persist()
}
