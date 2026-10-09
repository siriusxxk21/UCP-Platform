import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实开发环境验收：凭据只在内存中，所有写操作限制为本次登记的夹具。
// 不导入施工体验初始化工具，不读取或改写施工体验应用 4430。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.prefix = `tp${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
ac.output = resolve(process.env.TASK_POOL_OUTPUT || '.work/task-pool', ac.prefix)
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const expect = playwrightExpect.configure({ timeout: 20000 })
const checks = [],
  screenshots = [],
  errors = [],
  blockedWrites = [],
  browserWrites = [],
  taskIds = [],
  draftIds = [],
  records = [],
  taskCleanup = []
let browser, page, failure, object, record, adminId, employeeA, employeeB, employeeC, roleId
let dropNextDraftPublish = false,
  droppedPublish
const title = suffix => `${ac.prefix} ${suffix}`
const api = (path, body, token) => ac.api(`/nocode/tasks/${path}`, body, token)
const request = (path, body, token) => ac.request(`/nocode/tasks/${path}`, body, token)
const query = extra => ({ scope: 'MANAGE', tab: 'ALL', search: ac.prefix, pageNo: 1, pageSize: 100, ...extra })
const tasks = (extra, token) => api('page', query(extra), token)
const appId = () => ac.app.application.id

async function persist() {
  await ac.persist()
  await writeFile(
    resolve(ac.output, 'manifest.json'),
    JSON.stringify(
      {
        prefix: ac.prefix,
        source: 'task-pool',
        ...ac.owned,
        records,
        taskIds,
        draftIds,
        retention: '只保留本清单精确标识的夹具；临时任务结束后取消、用户停用；保留审计，未清理用户已有数据'
      },
      null,
      2
    )
  )
}
async function check(name, work, dependencies = []) {
  const blockedBy = dependencies.filter(dependency => !dependency.passed).map(dependency => dependency.name)
  if (blockedBy.length) {
    const result = { name, passed: false, status: 'blocked', blockedBy }
    checks.push(result)
    console.log(`BLOCKED ${name}`)
    await persist()
    return result
  }
  let result
  try {
    const detail = await work()
    result = { name, passed: true, status: 'passed', detail }
    console.log(`PASS ${name}`)
  } catch (error) {
    result = { name, passed: false, status: 'failed', error: error.stack || error.message }
    failure ||= result.error
    console.error(`FAIL ${name}: ${error.message}`)
    // 独立验收组继续运行；有依赖的组通过 dependencies 明确记录 blocked。
    if (page && !page.isClosed()) {
      await screenshot(`failure-${checks.length + 1}`).catch(() => {})
      await writeFile(
        resolve(ac.output, `failure-${checks.length + 1}.txt`),
        await page
          .locator('body')
          .innerText()
          .catch(() => '')
      ).catch(() => {})
    }
  } finally {
    checks.push(result)
    await persist()
  }
  return result
}
async function screenshot(name) {
  const path = resolve(ac.output, `${name}.png`)
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  screenshots.push(path)
}
function remember(detail) {
  for (const task of detail.nodes || [detail.task]) {
    if (!taskIds.includes(task.id)) taskIds.push(task.id)
  }
  return detail
}
function task(suffix, extra = {}) {
  return {
    id: randomUUID(),
    parentId: null,
    title: title(suffix),
    description: '',
    assigneeId: null,
    assignmentMode: 'OPEN',
    candidateUserIds: [],
    urgency: 'NORMAL',
    priority: 'MEDIUM',
    schedule: { mode: 'UNSCHEDULED', fixedStart: null, offsetDays: 0, durationDays: 1 },
    predecessorIds: [],
    binding: null,
    sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
    ...extra
  }
}
async function create(suffix, extra = {}, command = {}, token) {
  const detail = remember(
    await api('create', { task: task(suffix, extra), requestKey: randomUUID(), ...command }, token)
  )
  await persist()
  return detail
}
async function transition(id, action, token) {
  assert.ok(taskIds.includes(id), '只执行本次任务')
  const detail = await api('detail', { id }, token)
  return api('transition', { id, action, expectedRevision: detail.task.revision, requestKey: randomUUID() }, token)
}
async function cancelOwnTasks() {
  const pending = []
  for (const id of taskIds) {
    try {
      const detail = await api('detail', { id })
      assert.ok(detail.task.title.startsWith(ac.prefix), '取消前必须逐项复核清单ID与本次前缀')
      let depth = 0,
        parentId = detail.task.parentId
      while (parentId && depth <= detail.nodes.length) {
        depth++
        parentId = detail.nodes.find(node => node.id === parentId)?.parentId
      }
      pending.push({ id, depth })
    } catch (error) {
      taskCleanup.push({ id, status: 'failed', error: error.message })
      errors.push(`复核夹具任务 ${id}: ${error.message}`)
    }
  }
  // 服务端要求先结束子任务；只取消清单内节点，不扩展到任何查询到的其他记录。
  for (const { id } of pending.sort((left, right) => right.depth - left.depth)) {
    try {
      const current = (await api('detail', { id })).task
      assert.ok(current.title.startsWith(ac.prefix))
      if (['COMPLETED', 'CANCELLED'].includes(current.status)) {
        taskCleanup.push({ id, status: current.status })
        continue
      }
      const result = await transition(id, 'CANCEL')
      assert.equal(result.task.status, 'CANCELLED')
      taskCleanup.push({ id, status: 'CANCELLED' })
    } catch (error) {
      taskCleanup.push({ id, status: 'failed', error: error.message })
      errors.push(`取消夹具任务 ${id}: ${error.message}`)
    }
  }
}
async function denied(
  path,
  body,
  token,
  pattern = /权限|授权|无权|范围|领取|分配|执行|开始|前置|不存在|修改|版本|草稿|状态/
) {
  const result = await request(path, body, token)
  if (result.code === 0 && result.data?.task) {
    remember(result.data)
    await persist()
  }
  assert.notEqual(result.code, 0, `${path} 必须拒绝`)
  assert.ok(result.http < 500, '业务拒绝不能是未处理服务端错误')
  assert.match(result.msg, pattern)
  return { code: result.code, message: result.msg }
}

async function prepareUsers() {
  adminId = String((await ac.api('/system/auth/get-permission-info')).user.id)
  const menus = await ac.api('/system/menu/list')
  const selected = new Set(
    menus
      .filter(menu => ['nocode:task:query', 'nocode:task:create'].includes(menu.permission))
      .map(menu => String(menu.id))
  )
  for (const selectedId of [...selected]) {
    let parent = menus.find(menu => String(menu.id) === selectedId)?.parentId
    while (parent && menus.some(menu => String(menu.id) === String(parent))) {
      const menu = menus.find(menu => String(menu.id) === String(parent))
      selected.add(String(menu.id))
      parent = menu.parentId
    }
  }
  assert.ok(selected.size, '现有任务查询/创建权限菜单必须存在')
  roleId = await ac.api('/system/role/create', {
    name: title('任务池成员'),
    code: `${ac.prefix}_member`,
    sort: 1,
    status: 0,
    dataScope: 1
  })
  ac.owned.roles.push({ id: roleId })
  await persist()
  await ac.api('/system/permission/assign-role-menu', { roleId, menuIds: [...selected] })
  for (const suffix of ['a', 'b', 'c']) {
    const id = await ac.user(suffix)
    await ac.api('/system/permission/assign-user-role', { userId: id, roleIds: [roleId] })
    if (suffix === 'a') employeeA = id
    if (suffix === 'b') employeeB = id
    if (suffix === 'c') employeeC = id
  }
  for (const id of [employeeA, employeeB, employeeC]) {
    const info = await ac.api('/system/auth/get-permission-info', undefined, ac.tokens[id])
    assert.ok(info.permissions.includes('nocode:task:query'))
    assert.ok(!info.permissions.includes('nocode:task:manage-all'))
  }
}

async function prepareApplication() {
  object = await ac.object('record', '任务池记录夹具', [ac.field('name', 'TEXT', '记录名称')])
  const resource = (id, kind, name, config) => ({ id, code: id, kind, name, config })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: `${ac.prefix}_app`,
    name: title('任务池应用'),
    description: '新建任务草稿与全员领取验收专用夹具',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource('app_page', 'PAGE', '应用任务页', {
          nodes: [{ id: 'app_tasks', type: 'TASKS', text: '应用任务', children: [] }]
        }),
        resource('record_page', 'PAGE', '记录任务页', {
          contextObjectId: object.objectId,
          nodes: [{ id: 'record_tasks', type: 'TASKS', text: '本记录任务', children: [] }]
        }),
        resource('app_menu', 'MENU', '应用任务', { targetId: 'app_page' }),
        resource('record_menu', 'MENU', '记录任务', { targetId: 'record_page' })
      ]
    }
  })
  assert.notEqual(String(appId()), '4430')
  ac.owned.applications.push({ id: appId(), code: ac.app.application.code })
  await persist()
  await ac.share(object, ac.grant(object))
  ac.app = await ac.api('/nocode/application/publish', {
    id: appId(),
    expectedRevision: ac.app.application.revision,
    reason: title('独立任务池夹具')
  })
  record = await ac.save(object, { name: title('项目甲') })
  records.push({ applicationId: appId(), objectId: object.objectId, id: record.id })
  await persist()
}

async function browserPage(token = ac.tokens.admin) {
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  const tab = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  tab.setDefaultTimeout(20000)
  tab.on('pageerror', error => errors.push(error.message))
  await tab.addInitScript(
    ({ token, info }) => {
      const flatten = (items, parentId = 0) =>
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
                ...flatten(menu.children || [], menu.id)
              ]
        )
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(flatten(info.menus || [])))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info }
  )
  const reads = new Set([
    '/nocode/tasks/page',
    '/nocode/tasks/page-tasks',
    '/nocode/tasks/detail',
    '/nocode/tasks/readiness',
    '/nocode/tasks/draft-get',
    '/nocode/runtime/page'
  ])
  await tab.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || reads.has(path)) return route.continue()
    const body = request.postDataJSON()
    const ownDraftCreate =
      path === '/nocode/tasks/draft-save' &&
      body.expectedRevision === 0 &&
      body.content?.task?.title?.startsWith(ac.prefix) &&
      (!body.content.applicationId || String(body.content.applicationId) === String(appId()))
    if (ownDraftCreate && body.id && !draftIds.includes(body.id)) draftIds.push(body.id)
    const allowed =
      (path === '/nocode/tasks/create' &&
        body.task?.title?.startsWith(ac.prefix) &&
        (!body.applicationId || String(body.applicationId) === String(appId()))) ||
      (['/nocode/tasks/claim', '/nocode/tasks/assign', '/nocode/tasks/transition'].includes(path) &&
        taskIds.includes(body.id)) ||
      (path === '/nocode/tasks/draft-save' &&
        (!body.id || draftIds.includes(body.id)) &&
        body.content?.task?.title?.startsWith(ac.prefix)) ||
      (path === '/nocode/tasks/draft-publish' && draftIds.includes(body.id))
    if (allowed) {
      browserWrites.push({ path, body })
      if (path === '/nocode/tasks/draft-publish' && dropNextDraftPublish) {
        dropNextDraftPublish = false
        const response = await route.fetch()
        const result = await response.json()
        if (result.code === 0) {
          remember(result.data)
          droppedPublish = { taskId: result.data.task.id, body, writeIndex: browserWrites.length - 1 }
          await persist()
        }
        // 服务端已提交而浏览器未收到回执，重试只能重放原发布请求。
        await route.abort('connectionreset')
        return
      }
      return route.continue()
    }
    blockedWrites.push({ path, method: request.method() })
    await route.abort('blockedbyclient')
  })
  return tab
}

async function claim(id, token, extra = {}) {
  assert.ok(taskIds.includes(id))
  const current = await api('detail', { id })
  return api('claim', { id, expectedRevision: current.task.revision, requestKey: randomUUID(), ...extra }, token)
}
async function assign(id, assignmentMode, assigneeId = null, candidateUserIds = [], token) {
  assert.ok(taskIds.includes(id))
  const current = await api('detail', { id })
  return api(
    'assign',
    {
      id,
      expectedRevision: current.task.revision,
      assignmentMode,
      assigneeId,
      candidateUserIds,
      requestKey: randomUUID(),
      note: title('验收人员安排')
    },
    token
  )
}
function sameId(left, right) {
  assert.equal(String(left), String(right))
}
function unstarted(row) {
  assert.equal(row.status, 'PENDING')
  assert.equal(row.actualStart, null)
  assert.equal(row.actualEnd, null)
}
const milliseconds = value => (typeof value === 'number' ? value : Date.parse(value))
const assigned = userId => ({ assignmentMode: 'ASSIGNED', assigneeId: userId })
const viewTasks = (recordId, token) =>
  api(
    'page-tasks',
    {
      applicationId: appId(),
      pageId: recordId ? 'record_page' : 'app_page',
      nodeId: recordId ? 'record_tasks' : 'app_tasks',
      recordId,
      query: query({ scope: 'VISIBLE' })
    },
    token
  )
let claimableTask, assignedTask, sharedTask, uiTask

async function verifyHttp() {
  const draftSaved = await check(
    '不完整草稿本人保存与恢复，其他员工及管理员不能读取/修改，草稿不创建任务',
    async () => {
      const before = (await tasks()).total
      const content = {
        task: task('', { title: '', description: title('未填写名称的草稿') }),
        nodes: [task('', { title: '' })],
        requestKey: randomUUID()
      }
      const id = randomUUID()
      draftIds.push(id)
      const saved = await api('draft-save', { id, expectedRevision: 0, content }, ac.tokens[employeeA])
      assert.equal(saved.id, id)
      assert.equal(saved.content.task.title, '')
      assert.equal(saved.content.nodes[0].title, '')
      const restored = await api('draft-get', { id }, ac.tokens[employeeA])
      assert.deepEqual(restored.content, saved.content)
      assert.ok((await api('drafts', undefined, ac.tokens[employeeA])).some(row => row.id === id))
      assert.ok(!(await api('drafts', undefined, ac.tokens[employeeB])).some(row => row.id === id))
      for (const token of [ac.tokens[employeeB], ac.tokens.admin]) {
        await denied('draft-get', { id }, token)
        await denied('draft-save', { id, expectedRevision: saved.revision, content }, token)
        await denied('draft-publish', { id, expectedRevision: saved.revision, requestKey: randomUUID() }, token)
      }
      assert.equal((await tasks()).total, before)
      await denied(
        'draft-publish',
        { id, expectedRevision: saved.revision, requestKey: randomUUID() },
        ac.tokens[employeeA],
        /名称|任务|不能为空/
      )
      assert.equal((await tasks()).total, before)
      return { draftId: id, revision: restored.revision, incompleteNodes: 2 }
    }
  )

  await check(
    '草稿修订冲突拒绝；发布及同请求重放只产生一个PENDING任务实例',
    async () => {
      const id = draftIds[0]
      const saved = await api('draft-get', { id }, ac.tokens[employeeA])
      const content = {
        ...saved.content,
        task: { ...saved.content.task, title: title('草稿正式发布') },
        nodes: saved.content.nodes.map(child => ({ ...child, title: title('草稿子任务') }))
      }
      const changed = await api('draft-save', { id, expectedRevision: saved.revision, content }, ac.tokens[employeeA])
      assert.ok(changed.revision > saved.revision)
      await denied('draft-save', { id, expectedRevision: saved.revision, content }, ac.tokens[employeeA])
      const command = { id, expectedRevision: changed.revision, requestKey: randomUUID() }
      const published = remember(await api('draft-publish', command, ac.tokens[employeeA]))
      const replay = await api('draft-publish', command, ac.tokens[employeeA])
      assert.equal(replay.task.id, published.task.id)
      assert.equal(replay.nodes.length, 2)
      for (const row of published.nodes) {
        unstarted(row)
        assert.equal(row.assigneeId, null)
        assert.equal(row.assignmentMode, 'OPEN')
      }
      const visible = await tasks({ search: title('草稿正式发布') })
      assert.equal(visible.total, 1)
      assert.equal((await api('draft-get', { id }, ac.tokens[employeeA])).publishedTaskId, published.task.id)
      assert.ok(!(await api('drafts', undefined, ac.tokens[employeeA])).some(row => row.id === id))
      return { taskId: published.task.id, nodes: published.nodes.map(row => row.id) }
    },
    [draftSaved]
  )

  await check('候选包含null返回业务拒绝而非服务端500', async () => {
    await denied(
      'create',
      {
        task: task('非法候选空值', { candidateUserIds: [null] }),
        requestKey: randomUUID()
      },
      undefined,
      /候选|人员|成员|为空|用户|领取/
    )
  })

  const openCreated = await check('OPEN全员可领且MINE+CLAIMABLE不按执行人过滤；未排期不生成默认日期', async () => {
    claimableTask = (await create('默认全员可领取')).task
    unstarted(claimableTask)
    assert.equal(claimableTask.assigneeId, null)
    assert.equal(claimableTask.assignmentMode, 'OPEN')
    assert.deepEqual(claimableTask.candidateUserIds || [], [])
    assert.equal(claimableTask.schedule.mode, 'UNSCHEDULED')
    for (const field of ['baselineStart', 'baselineEnd', 'expectedStart', 'expectedEnd'])
      assert.equal(claimableTask[field], null)
    for (const id of [employeeA, employeeB]) {
      const pool = await tasks({ scope: 'MINE', tab: 'CLAIMABLE' }, ac.tokens[id])
      assert.ok(pool.list.some(row => row.id === claimableTask.id && row.canClaim))
      assert.ok(
        !(await tasks({ scope: 'MINE', tab: 'POOL' }, ac.tokens[id])).list.some(row => row.id === claimableTask.id)
      )
    }
    return { taskId: claimableTask.id, assignmentMode: claimableTask.assignmentMode }
  })

  await check(
    '两位普通员工同时领取仅一人成功，重放不重复事件且领取不等于开始',
    async () => {
      const command = { id: claimableTask.id, expectedRevision: claimableTask.revision, requestKey: randomUUID() }
      const claims = await Promise.all([employeeA, employeeB].map(id => request('claim', command, ac.tokens[id])))
      assert.equal(claims.filter(result => result.code === 0).length, 1)
      assert.ok(claims.every(result => result.http < 500))
      const winner = claims[0].code === 0 ? employeeA : employeeB
      const result = claims.find(result => result.code === 0).data
      sameId(result.task.assigneeId, winner)
      assert.equal(result.task.assignmentMode, 'ASSIGNED')
      unstarted(result.task)
      const replay = await api('claim', command, ac.tokens[winner])
      assert.equal(replay.task.revision, result.task.revision)
      const current = await api('detail', { id: claimableTask.id })
      assert.equal(current.events.filter(event => event.type === 'CLAIMED').length, 1)
      assert.ok(
        (await tasks({ scope: 'MINE', tab: 'POOL' }, ac.tokens[winner])).list.some(row => row.id === claimableTask.id)
      )
      for (const id of [employeeA, employeeB])
        assert.ok(
          !(await tasks({ scope: 'MINE', tab: 'CLAIMABLE' }, ac.tokens[id])).list.some(
            row => row.id === claimableTask.id
          )
        )
      return { winner, taskId: claimableTask.id, status: current.task.status }
    },
    [openCreated]
  )

  await check('暂不分配不可领取；管理员指定到人；普通非管理者不能改派，开始执行后拒绝改派', async () => {
    const unassigned = (await create('暂不分配', { assignmentMode: 'UNASSIGNED' })).task
    assert.equal(unassigned.assigneeId, null)
    await denied(
      'claim',
      { id: unassigned.id, expectedRevision: unassigned.revision, requestKey: randomUUID() },
      ac.tokens[employeeA]
    )
    const allocated = await assign(unassigned.id, 'ASSIGNED', employeeA)
    assignedTask = allocated.task
    sameId(assignedTask.assigneeId, employeeA)
    unstarted(assignedTask)
    assert.ok(
      (await tasks({ scope: 'MINE', tab: 'POOL' }, ac.tokens[employeeA])).list.some(row => row.id === unassigned.id)
    )
    await denied(
      'assign',
      {
        id: unassigned.id,
        expectedRevision: assignedTask.revision,
        assignmentMode: 'ASSIGNED',
        assigneeId: employeeB,
        candidateUserIds: [],
        requestKey: randomUUID()
      },
      ac.tokens[employeeB]
    )
    await transition(unassigned.id, 'START', ac.tokens[employeeA])
    const started = await api('detail', { id: unassigned.id })
    assert.equal(started.task.status, 'RUNNING')
    assert.ok(started.task.actualStart)
    await denied('assign', {
      id: unassigned.id,
      expectedRevision: started.task.revision,
      assignmentMode: 'ASSIGNED',
      assigneeId: employeeB,
      candidateUserIds: [],
      requestKey: randomUUID()
    })
    return { taskId: unassigned.id, status: started.task.status }
  })

  await check('限定候选领取、撤销权限与停用账号均在服务端拒绝，未产生执行人', async () => {
    const restricted = (await create('限定候选', { candidateUserIds: [employeeA] })).task
    assert.ok(
      (await tasks({ scope: 'MINE', tab: 'CLAIMABLE' }, ac.tokens[employeeA])).list.some(
        row => row.id === restricted.id
      )
    )
    assert.ok(
      !(await tasks({ scope: 'MINE', tab: 'CLAIMABLE' }, ac.tokens[employeeB])).list.some(
        row => row.id === restricted.id
      )
    )
    await denied(
      'claim',
      { id: restricted.id, expectedRevision: restricted.revision, requestKey: randomUUID() },
      ac.tokens[employeeB]
    )
    const revoked = (await create('领取前失权')).task
    await ac.api('/system/permission/assign-user-role', { userId: employeeC, roleIds: [] })
    await denied(
      'claim',
      { id: revoked.id, expectedRevision: revoked.revision, requestKey: randomUUID() },
      ac.tokens[employeeC],
      /权限|访问|授权/
    )
    await ac.api('/system/permission/assign-user-role', { userId: employeeC, roleIds: [roleId] })
    await ac.api('/system/user/update-status', { id: employeeC, status: 1 }, undefined, 'PUT')
    await denied(
      'claim',
      { id: revoked.id, expectedRevision: revoked.revision, requestKey: randomUUID() },
      ac.tokens[employeeC],
      /权限|访问|授权|停用|禁用|登录|用户|账号/
    )
    await denied(
      'assign',
      {
        id: revoked.id,
        expectedRevision: revoked.revision,
        assignmentMode: 'ASSIGNED',
        assigneeId: employeeC,
        candidateUserIds: [],
        requestKey: randomUUID()
      },
      undefined,
      /停用|禁用|用户|人员|账号/
    )
    assert.equal((await api('detail', { id: revoked.id })).task.assigneeId, null)
    return { restricted: restricted.id, revoked: revoked.id }
  })

  await check('领取前不可借公开子任务读整个私有树；领取只分配本节点且不绕过父子/前置约束', async () => {
    const open = task('公开子节点'),
      secret = task('不可见兄弟节点', assigned(adminId))
    const detail = await create('私有总任务', assigned(adminId), { nodes: [open, secret] })
    const target = detail.nodes.find(row => row.title === open.title)
    const sibling = detail.nodes.find(row => row.title === secret.title)
    const secretComment = title('私有兄弟评论内容不得泄漏')
    await api('comment', {
      taskId: sibling.id,
      parentId: null,
      content: secretComment,
      mentionedUserIds: [],
      requestKey: randomUUID()
    })
    assert.ok(
      (await tasks({ scope: 'MINE', tab: 'CLAIMABLE' }, ac.tokens[employeeB])).list.some(row => row.id === target.id)
    )
    const inspected = await request('detail', { id: target.id }, ac.tokens[employeeB])
    if (inspected.code === 0) {
      assert.ok(!inspected.data.nodes.some(row => row.id === sibling.id), '未领取者不应获取私有兄弟任务')
      assert.ok(!JSON.stringify(inspected.data).includes(secretComment), '公开节点详情不能夹带私有评论')
    } else assert.ok(inspected.http < 500)
    await denied('detail', { id: sibling.id }, ac.tokens[employeeB])
    await denied('form', { id: sibling.id }, ac.tokens[employeeB])
    const outsiderComment = await denied(
      'comment',
      {
        taskId: sibling.id,
        parentId: null,
        content: title('不应写入私有节点'),
        mentionedUserIds: [],
        requestKey: randomUUID()
      },
      ac.tokens[employeeB]
    )
    assert.ok(outsiderComment.code)
    await claim(target.id, ac.tokens[employeeB])
    await denied('detail', { id: sibling.id }, ac.tokens[employeeB])
    const claimedView = await api('detail', { id: target.id }, ac.tokens[employeeB])
    assert.ok(!claimedView.nodes.some(row => row.id === sibling.id), '领取后仍不能访问私有兄弟任务')
    await denied(
      'comment',
      {
        taskId: sibling.id,
        parentId: null,
        content: title('私有兄弟不能通过提及泄漏给公开节点领取者'),
        mentionedUserIds: [employeeB],
        requestKey: randomUUID()
      },
      undefined,
      /只能|有权|成员|权限/
    )
    const current = await api('detail', { id: target.id })
    unstarted(current.task)
    sameId(current.task.assigneeId, employeeB)
    sameId(current.nodes.find(row => row.id === detail.task.id).assigneeId, adminId)
    sameId(current.nodes.find(row => row.id === sibling.id).assigneeId, adminId)
    assert.equal(current.task.canStart, false)
    await denied(
      'transition',
      { id: target.id, expectedRevision: current.task.revision, action: 'START', requestKey: randomUUID() },
      ac.tokens[employeeB]
    )
    return { root: detail.task.id, target: target.id, sibling: sibling.id }
  })

  await check('计划开始基准和相对自然日独立于发布时间；固定截止时间精确保留', async () => {
    const plannedStart = Date.parse('2030-10-04T09:00:00+08:00')
    const child = task('计划开始后两天', {
      schedule: { mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 3 }
    })
    const detail = await create(
      '整体计划基准',
      { schedule: { mode: 'PLAN_START', fixedStart: null, offsetDays: 0, durationDays: 10 } },
      { plannedStart, nodes: [child] }
    )
    assert.equal(milliseconds(detail.task.expectedStart), plannedStart)
    const actualChild = detail.nodes.find(row => row.title === child.title)
    assert.equal(milliseconds(actualChild.expectedStart), plannedStart + 2 * 86400000)
    assert.equal(milliseconds(actualChild.expectedEnd), plannedStart + 5 * 86400000)
    const fixedEnd = plannedStart + 86400000 + 3600000
    const fixed = (
      await create('指定起止日期', {
        schedule: { mode: 'FIXED', fixedStart: plannedStart, fixedEnd, offsetDays: 0, durationDays: 1 }
      })
    ).task
    assert.equal(milliseconds(fixed.expectedEnd), fixedEnd)
    const startOnly = (
      await create('只安排开始时间', {
        schedule: { mode: 'FIXED', fixedStart: plannedStart, fixedEnd: null, offsetDays: 0, durationDays: 0 }
      })
    ).task
    assert.equal(milliseconds(startOnly.expectedStart), plannedStart)
    assert.equal(startOnly.expectedEnd, null, '新协议只填写开始且未设工期时不得捏造截止')
    assert.equal(startOnly.baselineEnd, null)
    const endOnly = (
      await create('只安排截止时间', {
        schedule: { mode: 'FIXED', fixedStart: null, fixedEnd, offsetDays: 0, durationDays: 0 }
      })
    ).task
    assert.equal(endOnly.expectedStart, null, '只填写截止时不得用创建时间捏造开始')
    assert.equal(endOnly.baselineStart, null)
    assert.equal(milliseconds(endOnly.expectedEnd), fixedEnd)
    await denied(
      'create',
      {
        task: task('缺计划基准', {
          schedule: { mode: 'PLAN_START', fixedStart: null, offsetDays: 1, durationDays: 1 }
        }),
        requestKey: randomUUID()
      },
      undefined,
      /计划|基准|时间/
    )
    return { plannedTask: detail.task.id, plannedStart, fixedTask: fixed.id, fixedEnd }
  })

  await check('未分配总任务不阻塞已分配子任务开工，父项不被自动分配或自动开始', async () => {
    const child = task('员工可先执行的子任务', assigned(employeeB))
    const detail = await create('暂不分配的总任务', { assignmentMode: 'UNASSIGNED' }, { nodes: [child] })
    const target = detail.nodes.find(row => row.title === child.title)
    const before = await api('detail', { id: target.id }, ac.tokens[employeeB])
    assert.equal(before.task.canStart, true)
    await transition(target.id, 'START', ac.tokens[employeeB])
    const root = (await api('detail', { id: detail.task.id })).task
    unstarted(root)
    assert.equal(root.assignmentMode, 'UNASSIGNED')
    assert.equal(root.assigneeId, null)
    assert.equal((await api('detail', { id: target.id })).task.status, 'RUNNING')
    return { root: root.id, child: target.id }
  })

  await check('前置完成后延后两天按实际完成时间计算；提前领取不解除前置阻断', async () => {
    const predecessor = task('前置施工', assigned(adminId))
    const next = task('两天后验收', {
      predecessorIds: [predecessor.id],
      schedule: { mode: 'PREDECESSOR', fixedStart: null, offsetDays: 2, durationDays: 1 }
    })
    const detail = await create('前置自然日验证', assigned(adminId), { nodes: [predecessor, next] })
    const first = detail.nodes.find(row => row.title === predecessor.title)
    const second = detail.nodes.find(row => row.title === next.title)
    await claim(second.id, ac.tokens[employeeB])
    await transition(detail.task.id, 'START')
    const before = await api('detail', { id: second.id })
    assert.equal(before.task.canStart, false)
    assert.equal(before.task.expectedStart, null, '未知前置完成时间不应回退到创建时刻')
    assert.equal(before.task.expectedEnd, null)
    await denied(
      'transition',
      { id: second.id, expectedRevision: before.task.revision, action: 'START', requestKey: randomUUID() },
      ac.tokens[employeeB]
    )
    await transition(first.id, 'START')
    const ended = await transition(first.id, 'COMPLETE')
    const after = await api('detail', { id: second.id })
    assert.equal(milliseconds(after.task.expectedStart), milliseconds(ended.task.actualEnd) + 2 * 86400000)
    assert.equal(milliseconds(after.task.expectedEnd), milliseconds(ended.task.actualEnd) + 3 * 86400000)
    assert.equal(after.task.canStart, false, '延后两天尚未到时不能开始')
    return { root: detail.task.id, predecessor: first.id, next: second.id }
  })

  await check('旧请求省略新安排字段仍保持原执行人和T0语义，不自动开放旧任务', async () => {
    const legacy = task('旧请求兼容', { schedule: { mode: 'T0', fixedStart: null, offsetDays: 0, durationDays: 1 } })
    delete legacy.assignmentMode
    delete legacy.candidateUserIds
    const detail = remember(await api('create', { task: legacy, requestKey: randomUUID() }))
    sameId(detail.task.assigneeId, adminId)
    assert.equal(detail.task.assignmentMode, 'ASSIGNED')
    assert.ok(detail.task.expectedStart)
    const fixedLegacy = {
      ...legacy,
      id: randomUUID(),
      title: title('旧固定日期与工期'),
      schedule: { mode: 'FIXED', fixedStart: Date.parse('2030-10-04T09:00:00+08:00'), offsetDays: 0, durationDays: 2 }
    }
    const fixed = remember(await api('create', { task: fixedLegacy, requestKey: randomUUID() })).task
    assert.equal(milliseconds(fixed.expectedEnd), Date.parse('2030-10-06T09:00:00+08:00'))
    const zeroLegacy = {
      ...fixedLegacy,
      id: randomUUID(),
      title: title('旧固定零工期'),
      schedule: { ...fixedLegacy.schedule, durationDays: 0 }
    }
    const zero = remember(await api('create', { task: zeroLegacy, requestKey: randomUUID() })).task
    assert.equal(milliseconds(zero.expectedEnd), milliseconds(zero.expectedStart))
    assert.equal(zero.legacyProtocol, true)
    const reassigned = await assign(zero.id, 'ASSIGNED', employeeA)
    assert.equal(milliseconds(reassigned.task.expectedEnd), milliseconds(zero.expectedStart))
    assert.equal(milliseconds(reassigned.task.schedule.fixedEnd), milliseconds(zero.expectedStart))
    const recalculated = await assign(zero.id, 'ASSIGNED', employeeB)
    assert.equal(milliseconds(recalculated.task.expectedEnd), milliseconds(zero.expectedStart))
    assert.ok(
      !(await tasks({ scope: 'MINE', tab: 'CLAIMABLE' }, ac.tokens[employeeA])).list.some(
        row => row.id === detail.task.id
      )
    )
    return { taskId: detail.task.id, assignmentMode: detail.task.assignmentMode }
  })

  await check('独立夹具应用/记录/任务中心为同一任务；领取不授予业务资料读取权限', async () => {
    const project = { applicationId: appId(), objectId: object.objectId, recordId: record.id, label: title('项目甲') }
    sharedTask = (await create('三入口同一任务', {}, { applicationId: appId(), project })).task
    for (const found of [await tasks(), await viewTasks(), await viewTasks(record.id)])
      assert.ok(found.list.some(row => row.id === sharedTask.id))
    await claim(sharedTask.id, ac.tokens[employeeA])
    const rejected = await ac.request(
      `/nocode/runtime/get?applicationId=${appId()}&objectId=${object.objectId}&id=${record.id}`,
      undefined,
      ac.tokens[employeeA]
    )
    assert.notEqual(rejected.code, 0, '领取任务不能暗中授权业务记录')
    assert.ok(rejected.http < 500)
    assert.deepEqual((await ac.get(object, record)).record.values, record.values)
    assert.equal((await ac.page(object)).total, 1)
    return { taskId: sharedTask.id, applicationId: appId(), objectId: object.objectId, recordId: record.id }
  })

  await check('管理人员安排筛选返回真实分页总数，三种方式互不混入', async () => {
    for (const mode of ['OPEN', 'UNASSIGNED', 'ASSIGNED']) {
      await create(`人员筛选${mode}`, { assignmentMode: mode, assigneeId: mode === 'ASSIGNED' ? adminId : null })
      const full = await tasks({ assignmentMode: mode })
      assert.ok(full.total > 0)
      assert.ok(full.list.every(row => row.assignmentMode === mode))
      const first = await tasks({ assignmentMode: mode, pageSize: 1 })
      assert.equal(first.total, full.total)
      assert.equal(first.list.length, 1)
    }
  })
}

const launch = () =>
  page.locator('.ant-drawer-content:visible').filter({ has: page.getByPlaceholder('填写可执行的任务名称') })
async function selectOption(control, label) {
  await control.click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: new RegExp(`^${label}$`) })
    .click()
}
async function chooseDateTime(input, date, hour) {
  await input.click()
  const picker = page.locator('.ant-picker-dropdown:visible')
  await picker.locator('.ant-picker-year-btn').click()
  await picker.locator(`.ant-picker-year-panel td[title="${date.slice(0, 4)}"]`).click()
  await expect(picker.locator('.ant-picker-year-panel')).toBeHidden()
  if (!(await picker.locator('.ant-picker-month-panel').isVisible()))
    await picker.locator('.ant-picker-month-btn').click()
  await picker.locator(`.ant-picker-month-panel td[title="${date.slice(0, 7)}"]`).click()
  await picker.locator(`.ant-picker-date-panel td[title="${date}"]`).click()
  const columns = picker.locator('.ant-picker-time-panel-column')
  for (const [index, value] of [hour, '00', '00'].entries()) {
    await columns
      .nth(index)
      .locator('.ant-picker-time-panel-cell-inner')
      .filter({ hasText: new RegExp(`^${value}$`) })
      .click()
  }
  await picker.getByRole('button', { name: /确\s*定/, exact: true }).click()
  await expect(picker).toBeHidden()
  await expect(input).toHaveValue(`${date} ${hour}:00:00`)
}
async function closeDrawers() {
  for (let count = 0; count < 5 && (await page.locator('.ant-drawer-content:visible').count()); count++) {
    await page.locator('.ant-drawer-content:visible .ant-drawer-close').last().click()
    const discard = page.getByRole('button', { name: '放弃修改', exact: true })
    if (await discard.isVisible()) await discard.click()
  }
}

async function verifyBrowser() {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browserPage()
  let savedDraft, published
  const uiTitle = title('浏览器保存与恢复')
  const launchReady = await check(
    '真实记录页新建默认全员领取/暂不安排，共享人员选择器和直观时间控件可操作',
    async () => {
      await page.goto(`${origin}/nocode-app/runtime?id=${appId()}&menu=record_menu&recordId=${record.id}`)
      await page
        .locator('.application-runtime')
        .getByRole('button', { name: /^(新建|发起)任务$/ })
        .click()
      await expect(launch()).toBeVisible()
      await expect(launch()).not.toContainText('应用列表加载失败')
      await expect(launch().locator('[aria-label="人员安排"]').first()).toContainText('开放领取')
      await expect(launch()).toContainText('所有人可领取')
      await expect(launch().getByRole('radio', { name: '暂不安排', exact: true })).toBeChecked()
      await expect(launch().getByRole('button', { name: '保存草稿', exact: true })).toBeVisible()
      await expect(launch().getByRole('button', { name: '加入任务池', exact: true })).toBeVisible()
      await selectOption(launch().locator('[aria-label="人员安排"]').first(), '指定执行人')
      await launch().getByRole('button', { name: '选择执行人', exact: true }).click()
      const picker = page.locator('.ant-modal:visible').filter({ has: page.locator('.user-selector-container') })
      await expect(picker).toContainText('选择执行人')
      await picker.getByPlaceholder('用户名/昵称/拼音').fill(`${ac.prefix}a`)
      await picker.getByRole('button', { name: /查\s*询/ }).click()
      await picker
        .locator('tr[data-row-key]')
        .filter({ hasText: `${ac.prefix}a` })
        .getByRole('radio')
        .check()
      await screenshot('person-selector')
      await picker.getByRole('button', { name: /确\s*认/, exact: true }).click()
      await expect(picker).toBeHidden()
      await expect(launch().getByRole('button', { name: '表单验收a', exact: true })).toBeVisible()
      await selectOption(launch().locator('[aria-label="人员安排"]').first(), '开放领取')
      await launch().getByRole('radio', { name: '指定日期', exact: true }).check()
      await expect(launch().getByPlaceholder('可不填写预计开始')).toBeVisible()
      await expect(launch().getByPlaceholder('可不填写预计结束')).toBeVisible()
      await launch().getByRole('radio', { name: '相对时间', exact: true }).check()
      await expect(launch()).toContainText('整体任务计划开始')
      await expect(launch()).toContainText('预计工期')
      await expect(launch()).not.toContainText('偏移自然日')
      await expect(launch()).not.toContainText('实例起点')
      await launch().getByRole('radio', { name: '指定日期', exact: true }).check()
      await chooseDateTime(launch().getByPlaceholder('可不填写预计开始'), '2030-10-04', '09')
      await chooseDateTime(launch().getByPlaceholder('可不填写预计结束'), '2030-10-05', '10')
      await screenshot('date-selection')
    }
  )

  const browserDraftSaved = await check(
    '总任务随上方名称同步显示，添加的是子任务，关系图同时显示父子且草稿保存不入池',
    async () => {
      const titleInput = launch().getByPlaceholder('填写可执行的任务名称')
      await titleInput.fill(uiTitle)
      const editor = launch().locator('.task-node-editor')
      await expect(editor.locator('tr[data-row-key]')).toHaveCount(1)
      await expect(editor.locator('tr[data-row-key]').first()).toContainText(uiTitle)
      await titleInput.fill(uiTitle + '修订')
      await expect(editor.locator('tr[data-row-key]').first()).toContainText(uiTitle + '修订')
      await titleInput.fill(uiTitle)
      await editor.getByRole('button', { name: '添加子任务', exact: true }).click()
      await expect(editor.locator('tr[data-row-key]')).toHaveCount(2)
      await expect(editor.getByPlaceholder('任务名称，直接在列表中填写')).toHaveCount(1)
      await expect(editor.getByPlaceholder('任务名称，直接在列表中填写')).toBeFocused()
      await editor.getByRole('button', { name: '查看关系图', exact: true }).click()
      const graph = page.locator('.ant-drawer-content:visible').filter({ has: page.locator('.task-dag') })
      await expect(graph.locator('.task-dag__node')).toHaveCount(2)
      await expect(graph.locator('[data-relation="parent"]')).toHaveCount(1)
      await expect(graph).toContainText(uiTitle)
      await screenshot('draft-task-tree')
      await graph.locator('.ant-drawer-close').click()
      const [response] = await Promise.all([
        page.waitForResponse(
          result => result.url().endsWith('/nocode/tasks/draft-save') && result.request().method() === 'POST'
        ),
        launch().getByRole('button', { name: '保存草稿', exact: true }).click()
      ])
      const result = await response.json()
      assert.equal(result.code, 0, result.msg)
      savedDraft = result.data
      if (!draftIds.includes(savedDraft.id)) draftIds.push(savedDraft.id)
      await persist()
      assert.equal(savedDraft.content.task.title, uiTitle)
      assert.equal(savedDraft.content.nodes.length, 1, '展示根任务不能重复混入待创建子节点')
      assert.equal(savedDraft.content.nodes[0].title, '')
      assert.equal(savedDraft.content.task.assignmentMode, 'OPEN')
      assert.equal(savedDraft.content.task.assigneeId, null)
      assert.equal(milliseconds(savedDraft.content.task.schedule.fixedStart), Date.parse('2030-10-04T09:00:00+08:00'))
      assert.equal(milliseconds(savedDraft.content.task.schedule.fixedEnd), Date.parse('2030-10-05T10:00:00+08:00'))
      assert.equal(savedDraft.content.project.recordId, record.id)
      assert.equal((await tasks({ search: uiTitle })).total, 0)
      await expect(page.locator('.ant-message')).not.toContainText('已加入任务池')
      await screenshot('draft-saved')
    },
    [launchReady]
  )

  await check(
    '刷新恢复草稿；发布回执丢失后原请求重试，只产生一个实例且三入口相同',
    async () => {
      await page.reload()
      await page.goto(origin + '/nocode-app/task-center/manage')
      await page.getByRole('button', { name: '我的草稿', exact: true }).click()
      const draftDrawer = page.locator('.ant-drawer-content:visible').filter({ hasText: '我的草稿' })
      const draftRow = draftDrawer.locator('tr[data-row-key]').filter({ hasText: uiTitle })
      await expect(draftRow).toBeVisible()
      await draftRow.getByRole('button', { name: /继续编辑|编辑/, exact: true }).click()
      await expect(launch().getByPlaceholder('填写可执行的任务名称')).toHaveValue(uiTitle)
      await expect(launch()).toContainText(title('项目甲'))
      await expect(launch()).toContainText(title('任务池应用'))
      await expect(launch().getByPlaceholder('可不填写预计开始')).toHaveValue('2030-10-04 09:00:00')
      await expect(launch().getByPlaceholder('可不填写预计结束')).toHaveValue('2030-10-05 10:00:00')
      await expect(launch()).not.toContainText('应用列表加载失败')
      await screenshot('draft-restored-dates')
      await expect(launch().locator('.task-node-editor tr[data-row-key]')).toHaveCount(2)
      await expect(launch().locator('.task-node-editor tr[data-row-key]').first()).not.toContainText('0 天 ·')
      await launch().getByPlaceholder('任务名称，直接在列表中填写').fill(title('浏览器恢复子任务'))
      await screenshot('draft-resumed')
      await launch().getByRole('button', { name: '保存草稿', exact: true }).scrollIntoViewIfNeeded()
      await screenshot('draft-ready-actions')
      dropNextDraftPublish = true
      await launch().getByRole('button', { name: '加入任务池', exact: true }).click()
      await expect.poll(() => droppedPublish?.taskId).toBeTruthy()
      await expect(launch().getByRole('button', { name: '加入任务池', exact: true })).toBeEnabled()
      await expect(launch()).toBeVisible()
      const [response] = await Promise.all([
        page.waitForResponse(
          result => result.url().endsWith('/nocode/tasks/draft-publish') && result.request().method() === 'POST'
        ),
        launch().getByRole('button', { name: '加入任务池', exact: true }).click()
      ])
      const result = await response.json()
      assert.equal(result.code, 0, result.msg)
      published = remember(result.data)
      await persist()
      assert.equal(published.nodes.length, 2)
      assert.equal(milliseconds(published.task.expectedStart), Date.parse('2030-10-04T09:00:00+08:00'))
      assert.equal(milliseconds(published.task.expectedEnd), Date.parse('2030-10-05T10:00:00+08:00'))
      assert.equal(published.task.id, droppedPublish.taskId)
      const retriedWrites = browserWrites.slice(droppedPublish.writeIndex + 1)
      assert.deepEqual(
        retriedWrites.map(write => write.path),
        ['/nocode/tasks/draft-publish'],
        '未知结果重试不能先保存已发布草稿'
      )
      assert.deepEqual(retriedWrites[0].body, droppedPublish.body, '重试必须使用原key和revision')
      for (const row of published.nodes) {
        unstarted(row)
        assert.equal(row.assigneeId, null)
        assert.equal(row.assignmentMode, 'OPEN')
      }
      await expect(launch()).toHaveCount(0)
      await closeDrawers()
      await page.getByPlaceholder('搜索任务名称').fill(uiTitle)
      await page.getByPlaceholder('搜索任务名称').press('Enter')
      await expect(page.locator(`tr[data-row-key="${published.task.id}"]`)).toBeVisible()
      for (const suffix of [`menu=app_menu`, `menu=record_menu&recordId=${record.id}`]) {
        await page.goto(`${origin}/nocode-app/runtime?id=${appId()}&${suffix}`)
        await expect(page.locator(`tr[data-row-key="${published.task.id}"]`)).toBeVisible()
      }
      assert.equal((await tasks({ search: uiTitle })).total, 1)
      await screenshot('same-task-in-record')
      return { taskId: published.task.id, draftId: savedDraft.id }
    },
    [browserDraftSaved]
  )

  await check('普通员工可领取入口完成真实领取，任务移入我的未完成但不自动开始', async () => {
    uiTask = (await create('浏览器待领取')).task
    await page.close()
    page = await browserPage(ac.tokens[employeeB])
    await page.goto(origin + '/nocode-app/task-center')
    await page.getByRole('tab', { name: '可领取任务', exact: true }).click()
    await page.getByPlaceholder('搜索任务名称').fill(uiTask.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    const row = page.locator(`tr[data-row-key="${uiTask.id}"]`)
    await expect(row).toBeVisible()
    const responsePromise = page.waitForResponse(
      result => result.url().endsWith('/nocode/tasks/claim') && result.request().method() === 'POST'
    )
    await row.getByRole('button', { name: /^(领取|领取任务)$/ }).click()
    const confirm = page.locator('.ant-modal-confirm:visible').getByRole('button', { name: /领取/ })
    if (await confirm.isVisible()) await confirm.click()
    const result = await (await responsePromise).json()
    assert.equal(result.code, 0, result.msg)
    sameId(result.data.task.assigneeId, employeeB)
    unstarted(result.data.task)
    await expect(row).toHaveCount(0)
    await page.getByRole('tab', { name: '我的未完成', exact: true }).click()
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: '开始', exact: true })).toBeVisible()
    await expect(row).not.toContainText('进行中')
    await screenshot('claimed-not-started')
  })

  await check('管理页人员安排筛选可操作；浏览器无脚本错误和越界写入', async () => {
    await page.close()
    page = await browserPage()
    await page.goto(origin + '/nocode-app/task-center/manage')
    await page.getByPlaceholder('搜索任务名称').fill(ac.prefix)
    const filter = page
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^人员安排$/ }) })
    await expect(filter).toBeVisible()
    await selectOption(filter.locator('.ant-select'), '待领取')
    const [response] = await Promise.all([
      page.waitForResponse(
        result =>
          result.url().endsWith('/nocode/tasks/page') &&
          result.request().method() === 'POST' &&
          result.request().postDataJSON().assignmentMode === 'OPEN'
      ),
      page.getByPlaceholder('搜索任务名称').press('Enter')
    ])
    const result = await response.json()
    assert.equal(result.code, 0, result.msg)
    assert.ok(result.data.total > 0)
    assert.ok(result.data.list.every(row => row.assignmentMode === 'OPEN'))
    await screenshot('manage-assignment-filter')
    assert.deepEqual(errors, [])
    assert.deepEqual(blockedWrites, [])
  })
}

try {
  await mkdir(ac.output, { recursive: true })
  await ac.login()
  await prepareUsers()
  await prepareApplication()
  await verifyHttp()
  if (process.env.TASK_POOL_HTTP_ONLY !== '1') await verifyBrowser()
} catch (error) {
  failure = error.stack || error.message
  console.error(failure)
  if (page) {
    await screenshot('failure').catch(() => {})
    await writeFile(
      resolve(ac.output, 'failure.txt'),
      await page
        .locator('body')
        .innerText()
        .catch(() => '')
    ).catch(() => {})
  }
  process.exitCode = 1
} finally {
  await browser?.close()
  await cancelOwnTasks()
  for (const user of ac.owned.users) {
    await ac
      .api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
      .catch(error => errors.push(`停用夹具成员 ${user.id}: ${error.message}`))
  }
  await persist()
  await writeFile(
    resolve(ac.output, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        prefix: ac.prefix,
        checks,
        errors,
        blockedWrites,
        taskCleanup,
        screenshots,
        failure,
        browserSkipped: process.env.TASK_POOL_HTTP_ONLY === '1'
      },
      null,
      2
    )
  )
  ac.tokens = {}
  ac.passwords = {}
  if (failure || checks.some(check => !check.passed) || errors.length || blockedWrites.length) process.exitCode = 1
  console.log(
    JSON.stringify(
      {
        output: ac.output,
        passed: checks.filter(check => check.passed).length,
        failed: checks.filter(check => check.status === 'failed').length,
        blocked: checks.filter(check => check.status === 'blocked').length,
        failure: !!failure
      },
      null,
      2
    )
  )
}
