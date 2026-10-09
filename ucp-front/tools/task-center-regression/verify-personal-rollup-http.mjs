import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 当前开发环境三账号收尾回归：只创建唯一前缀任务，不改真实账号、权限或业务记录。 */
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.prefix = `收尾验收 ${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
ac.output = resolve(process.env.TASK_ROLLUP_OUTPUT || '.work/task-personal-rollup', ac.prefix.replaceAll(' ', '-'))
const actors = {}
const ids = new Set()
const roots = []
const checks = []
const secrets = []
const clean = value =>
  secrets.filter(Boolean).reduce((text, secret) => text.split(secret).join('[已隐去]'), String(value))
const persist = async () => {
  await mkdir(ac.output, { recursive: true })
  await writeFile(
    resolve(ac.output, 'http-result.json'),
    JSON.stringify(
      {
        source: 'task-personal-rollup-v1',
        prefix: ac.prefix,
        base: ac.base,
        time: new Date().toISOString(),
        actors,
        roots,
        taskIds: [...ids],
        checks,
        retention: '保留本轮唯一前缀任务及审计；不删除、取消或更改其他任务，不改账号或授权。',
        limits: [
          '真实 HTTP，不替代浏览器布局检查。',
          '不通过 SQL 改历史状态；只读验证以请求前后修订与历史事件一致为依据。',
          '中间父项业务表单缺失通过真实 HTTP 验证；审批与撤权失败另由离线规则测试覆盖，未冒充真实审批/撤权。'
        ]
      },
      null,
      2
    )
  )
}
const step = async (name, run) => {
  try {
    const detail = await run()
    checks.push({ name, passed: true, detail })
    console.log(`PASS ${name}`)
  } catch (error) {
    checks.push({ name, passed: false, error: clean(error.message) })
    console.log(`FAIL ${name}: ${clean(error.message)}`)
  }
  await persist()
}
const read = (path, body, role = 'BOSS') => ac.api(`/nocode/tasks/${path}`, body, ac.tokens[role])
const request = (path, body, role = 'BOSS') => ac.request(`/nocode/tasks/${path}`, body, ac.tokens[role])
const node = (title, role = 'LI', extra = {}) => ({
  id: randomUUID(),
  parentId: null,
  title: `${ac.prefix} ${title}`,
  // 底座将超出 JS 安全整数的 Long 输出为字符串，写回时保持原 ID，不能转 Number。
  assigneeId: actors[role].id,
  assignmentMode: 'ASSIGNED',
  candidateUserIds: [],
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'UNSCHEDULED', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 0 },
  predecessorIds: [],
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
  ...extra
})
async function create(task, nodes = [], parentId = null) {
  assert.ok(task.title.startsWith(ac.prefix))
  assert.ok(nodes.every(item => item.title.startsWith(ac.prefix)))
  if (parentId) assert.ok(ids.has(parentId))
  const result = await read('create', { task, nodes, parentId, requestKey: randomUUID() })
  for (const item of result.nodes) {
    assert.ok(item.title.startsWith(ac.prefix))
    ids.add(item.id)
  }
  ids.add(result.task.id)
  if (!parentId) roots.push(result.task.id)
  await persist()
  return result
}
const find = (tree, title) => {
  const item = tree.nodes.find(item => item.title === `${ac.prefix} ${title}`)
  assert.ok(item, `缺少节点 ${title}`)
  return item.id
}
const detail = (id, role = 'BOSS') => read('detail', { id }, role)
async function command(id, action, role = 'LI', extra = {}) {
  assert.ok(ids.has(id), '仅允许修改本轮任务')
  const current = await detail(id, role)
  return {
    id,
    action,
    expectedRevision: current.task.revision,
    note: `${ac.prefix} ${action}`,
    requestKey: randomUUID(),
    ...extra
  }
}
async function action(id, verb, role = 'LI', extra = {}) {
  return read('transition', await command(id, verb, role, extra), role)
}
async function finish(id, role = 'LI') {
  await action(id, 'START', role)
  return action(id, 'COMPLETE', role)
}
const query = (title, personalScope = 'ACTION', tab = 'TODO') => ({
  scope: 'MINE',
  tab,
  search: `${ac.prefix} ${title}`,
  pageNo: 1,
  pageSize: 100,
  ...(personalScope ? { personalScope } : {})
})
const page = (title, role = 'LI', personalScope = 'ACTION', tab = 'TODO') =>
  read('personal-tree-page', query(title, personalScope, tab), role)
async function denied(path, body, role, pattern) {
  const response = await request(path, body, role)
  assert.notEqual(response.code, 0, '非法操作必须拒绝')
  assert.ok(response.http < 500, '必须返回可处理业务错误')
  assert.match(response.msg, pattern)
  return response.msg
}

try {
  await step('三账号登录，使用现有账号且不调整权限', async () => {
    for (const [role, fallback] of [
      ['BOSS', 'admin'],
      ['LI', 'lizhihang'],
      ['KE', 'kewei']
    ]) {
      const username = process.env[`TASK_QA_${role}_USERNAME`] || fallback
      const password = process.env[`TASK_QA_${role}_PASSWORD`]
      assert.ok(password, `缺少 TASK_QA_${role}_PASSWORD`)
      secrets.push(password)
      const session = await ac.api('/system/auth/login', { username, password }, null)
      ac.tokens[role] = session.accessToken
      secrets.push(session.accessToken, session.refreshToken)
      const info = await ac.api('/system/auth/get-permission-info', undefined, ac.tokens[role])
      actors[role] = { id: String(info.user.id), username }
    }
    assert.equal(new Set(Object.values(actors).map(actor => actor.id)).size, 3)
    return actors
  })
  if (!checks[0].passed) throw new Error('登录未通过，不创建任何任务')

  await step('普通任务保留显式完成，多层子项成功后逐层自动收尾与原键重放', async () => {
    const ordinary = await create(node('普通任务'))
    await action(ordinary.task.id, 'START')
    assert.equal((await detail(ordinary.task.id)).task.status, 'RUNNING')
    await action(ordinary.task.id, 'COMPLETE')
    const branch = node('多层-父')
    const tree = await create(node('多层-根'), [branch, node('多层-叶', 'LI', { parentId: branch.id })])
    const parent = find(tree, '多层-父'),
      leaf = find(tree, '多层-叶')
    await action(tree.task.id, 'START')
    await action(parent, 'START')
    await action(leaf, 'START')
    const cmd = await command(leaf, 'COMPLETE')
    await read('transition', cmd, 'LI')
    await read('transition', cmd, 'LI')
    const done = await detail(tree.task.id)
    assert.ok(done.nodes.every(item => item.status === 'COMPLETED'))
    assert.equal(done.events.filter(event => event.type === 'COMPLETED').length, 3)
    return { rootId: tree.task.id, completed: done.nodes.length }
  })

  await step('跨员工完成自动提交总任务验收，退回后新增整改项完成不自动重提', async () => {
    const tree = await create(node('验收-根', 'LI', { acceptorId: actors.BOSS.id }), [node('验收-叶', 'KE')])
    await action(tree.task.id, 'START')
    await finish(find(tree, '验收-叶'), 'KE')
    assert.equal((await detail(tree.task.id)).task.status, 'PENDING_ACCEPTANCE')
    await action(tree.task.id, 'REJECT', 'BOSS', { note: '整改说明：请补充检查' })
    assert.match((await detail(tree.task.id, 'LI')).task.completionReason, /退回/)
    const repair = await create(node('验收-整改'), [], tree.task.id)
    await finish(repair.task.id)
    assert.equal((await detail(tree.task.id)).task.status, 'RUNNING')
    await action(tree.task.id, 'COMPLETE')
    await action(tree.task.id, 'APPROVE', 'BOSS')
    const done = await detail(tree.task.id)
    assert.equal(done.task.status, 'COMPLETED')
    assert.equal(done.events.filter(event => event.type === 'SUBMITTED_FOR_ACCEPTANCE').length, 2)
    return { rootId: tree.task.id, acceptanceRounds: 2 }
  })

  await step('取消子项不算成功，需勾选范围确认和交付说明；重放不重复记历史', async () => {
    const tree = await create(node('取消-根'), [node('取消-放弃'), node('取消-完成')])
    await action(tree.task.id, 'START')
    await action(find(tree, '取消-放弃'), 'CANCEL')
    await finish(find(tree, '取消-完成'))
    assert.equal((await detail(tree.task.id)).task.status, 'RUNNING')
    const readiness = await read('readiness', { id: tree.task.id }, 'LI')
    assert.ok(readiness.checks.some(check => check.code === 'CHILDREN_CANCELLED' && !check.passed))
    await denied('transition', await command(tree.task.id, 'COMPLETE'), 'LI', /确认/)
    await denied(
      'transition',
      await command(tree.task.id, 'COMPLETE', 'LI', { confirmCancelledChildren: true, note: '' }),
      'LI',
      /交付说明/
    )
    const cmd = await command(tree.task.id, 'COMPLETE', 'LI', {
      confirmCancelledChildren: true,
      note: '确认取消项不影响剩余交付'
    })
    await read('transition', cmd, 'LI')
    await read('transition', cmd, 'LI')
    const done = await detail(tree.task.id)
    assert.equal(done.task.status, 'COMPLETED')
    assert.equal(done.events.filter(event => event.taskId === tree.task.id && event.type === 'COMPLETED').length, 1)
    return { rootId: tree.task.id }
  })

  await step('个人待办保留已完成上下文，本人完成后移入按总任务分组的已办，不能泄漏他人节点', async () => {
    const title = '个人上下文'
    const tree = await create(node(title, 'KE'), [node(`${title}-A`), node(`${title}-B`), node(`${title}-私有`, 'KE')])
    await action(tree.task.id, 'START', 'KE')
    const a = find(tree, `${title}-A`),
      b = find(tree, `${title}-B`),
      hidden = find(tree, `${title}-私有`)
    await finish(a)
    const groups = await page(title)
    assert.equal(groups.total, 1)
    const group = groups.list[0]
    assert.equal(group.task.id, tree.task.id)
    assert.equal(group.detailVisible, false)
    assert.equal(group.contextOnly, true)
    assert.equal(group.task.canExecute, false)
    assert.equal(group.task.binding, null)
    const children = await read('personal-tree-children', { query: query(title), parentId: tree.task.id }, 'LI')
    assert.deepEqual(children.map(item => item.task.id).sort(), [a, b].sort())
    assert.equal(children.find(item => item.task.id === a).contextOnly, true)
    assert.ok(!children.some(item => item.task.id === hidden))
    await denied('detail', { id: tree.task.id }, 'LI', /权限|访问/)
    await finish(b)
    assert.equal((await page(title)).total, 0)
    const done = await page(title, 'LI', null, 'DONE')
    assert.equal(done.total, 1)
    assert.equal(done.list[0].myCompletedCount, 2)
    assert.equal(done.list[0].task.groupStatus, 'RUNNING')
    const before = await detail(tree.task.id)
    await page(title, 'LI', null, 'DONE')
    await page(title, 'KE', 'FOLLOW_UP')
    const after = await detail(tree.task.id)
    assert.equal(after.task.revision, before.task.revision)
    assert.deepEqual(after.events, before.events)
    await finish(hidden, 'KE')
    return { rootId: tree.task.id, visibleChildCount: children.length, historyReadsArePure: true }
  })

  await step('负责跟进不混入执行待办，覆盖非根父任务把孙任务分给同事', async () => {
    const title = '跟进'
    const branch = node(`${title}-父`)
    const tree = await create(node(title, 'KE'), [branch, node(`${title}-孙`, 'KE', { parentId: branch.id })])
    await action(tree.task.id, 'START', 'KE')
    await action(find(tree, `${title}-父`), 'START')
    assert.equal((await page(title)).total, 0)
    const follow = await page(title, 'LI', 'FOLLOW_UP')
    assert.equal(follow.total, 1)
    assert.equal(follow.list[0].task.id, tree.task.id)
    assert.equal(follow.list[0].contextOnly, true)
    await finish(find(tree, `${title}-孙`), 'KE')
    assert.equal((await page(title, 'LI', 'FOLLOW_UP')).total, 0)
    assert.equal((await detail(tree.task.id)).task.status, 'COMPLETED')
    return { rootId: tree.task.id }
  })

  await step('两个员工并发完成最后子项，父项只汇总一次', async () => {
    const tree = await create(node('并发-根'), [node('并发-A'), node('并发-B', 'KE')])
    await action(tree.task.id, 'START')
    const a = find(tree, '并发-A'),
      b = find(tree, '并发-B')
    await action(a, 'START')
    await action(b, 'START', 'KE')
    const [one, two] = await Promise.all([command(a, 'COMPLETE'), command(b, 'COMPLETE', 'KE')])
    await Promise.all([read('transition', one, 'LI'), read('transition', two, 'KE')])
    const done = await detail(tree.task.id)
    assert.equal(done.task.status, 'COMPLETED')
    assert.equal(done.events.filter(event => event.taskId === tree.task.id && event.type === 'COMPLETED').length, 1)
    return { rootId: tree.task.id }
  })

  await step('中间父项缺少业务表单不能自动完成，但已完成的员工子项仍成功保存', async () => {
    // 只读取既有施工资源身份；不发布配置或写入任何既有项目/业务记录。
    const fixture = JSON.parse(await readFile(resolve('.work/construction-experience/manifest.json'), 'utf8'))
    assert.equal(fixture.applicationCode, 'construction_guide_app')
    const application = await ac.api(
      `/nocode/runtime/application?id=${fixture.applicationId}`,
      undefined,
      ac.tokens.BOSS
    )
    assert.equal(application.application.code, fixture.applicationCode)
    assert.ok(application.definition.resources.some(resource => resource.id === 'project_form'))
    const middle = node('材料-父', 'BOSS', {
      binding: { applicationId: String(fixture.applicationId), formId: 'project_form', entryId: null }
    })
    const tree = await create(node('材料-根'), [middle, node('材料-叶', 'LI', { parentId: middle.id })])
    const parent = find(tree, '材料-父'),
      child = find(tree, '材料-叶')
    await action(tree.task.id, 'START')
    await action(parent, 'START', 'BOSS')
    await finish(child)
    assert.equal((await detail(child)).task.status, 'COMPLETED')
    const blocked = await detail(parent)
    assert.equal(blocked.task.status, 'RUNNING')
    assert.match(blocked.task.completionReason, /表单|材料|必填/)
    assert.ok(blocked.events.some(event => event.taskId === parent && event.type === 'ROLLUP_BLOCKED'))
    assert.equal((await detail(tree.task.id)).task.status, 'RUNNING')
    return { rootId: tree.task.id, parentId: parent, childId: child, reason: blocked.task.completionReason }
  })
} catch (error) {
  checks.push({ name: '验收入口', passed: false, error: clean(error.message) })
} finally {
  await persist()
  console.log(`Report: ${resolve(ac.output, 'http-result.json')}`)
  if (checks.some(check => !check.passed)) process.exitCode = 1
}
