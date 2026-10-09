import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

/** 真实三账号验收。默认只列计划；不创建账号、不改授权、不调用施工初始化、不清理真实用户。 */
const source = 'multi-account-construction-v1'
const matrix = [
  ['01', '三账号真实登录与身份核对'],
  ['02', '老板分配独立总任务、员工仅在自己的待办找到本人节点'],
  ['03', '非法跨人开始、父项开始和 A→B→B1 祖先依赖门控'],
  ['04', '员工依序执行，子项完成后总任务才可提交验收'],
  ['05', '老板退回、员工重提、老板通过及历史轮次'],
  ['06', '开放总任务安全归组、默认和显式独立分支预览'],
  ['07', '仅领单项、旧整项预览冲突且不影响其他分工'],
  ['08', '一次领取 OPEN/FOLLOW_ROOT、同键恢复、不开始不入清单'],
  ['09', '总负责人仅转交本人未开始子项，权限不随总任务扩大'],
  ['10', '本人今日与本周清单独立移除，状态和日期不变'],
  ['11', '读取既有施工发布资源、两项目及原数据摘要'],
  ['12', '仅挂应用与挂两项目任务的真实分页隔离'],
  ['13', '老板配置施工授权，project_form 仅链接既有项目'],
  ['14', '未开始只读、必填日志阻止完成、员工真实新增日志与幂等'],
  ['15', '员工完成施工任务、老板验收与操作者/节点/材料追溯'],
  ['16', '原施工记录和发布配置不变，保留本次新增及失败清单']
]
const roles = ['BOSS', 'LI', 'KE']
const now = () => new Date().toISOString()
const sameId = (actual, expected) => assert.equal(String(actual), String(expected))
const canonical = value => {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map(key => [key, canonical(value[key])])
    )
  }
  return value
}
const digest = value =>
  createHash('sha256')
    .update(JSON.stringify(canonical(value)))
    .digest('hex')
const permissionDenied = result =>
  result.http === 403 || /权限|授权|无权|未授权|只能管理自己|不允许访问|无法访问|不可访问/.test(result.msg || '')
class Blocked extends Error {}

class MultiAccountAcceptance extends TaskDataPolicyAcceptance {
  constructor() {
    super()
    this.base = process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api'
    this.runId = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
    this.prefix = `三账号验收 ${this.runId}`
    this.output = resolve('.work/task-multi-account-construction', this.runId)
    this.state = {
      source,
      runId: this.runId,
      prefix: this.prefix,
      base: this.base,
      createdAt: now(),
      actors: {},
      tasks: {},
      taskIds: [],
      records: [],
      commands: {},
      results: [],
      attempts: [],
      facts: {},
      retention: '不取消或删除任务、不停用真实用户、不改成员权限。保留本轮任务、反馈和审计；原施工记录只读。'
    }
    this.secrets = []
  }

  async initialize() {
    const resume = process.env.TASK_MULTI_ACCOUNT_RESUME
    if (resume) {
      const file = resolve(resume)
      const saved = JSON.parse(await readFile(file, 'utf8'))
      assert.equal(saved.source, source, '仅能恢复本脚本的清单')
      assert.match(saved.runId, /^[a-z0-9]+-[a-f0-9]{8}$/)
      assert.equal(saved.prefix, `三账号验收 ${saved.runId}`)
      assert.equal(saved.base, this.base, '恢复时服务地址必须与原批次一致')
      this.state = saved
      this.runId = saved.runId
      this.prefix = saved.prefix
      this.output = dirname(file)
    }
    await this.persist()
  }

  clean(message) {
    let result = String(message)
    for (const secret of this.secrets.filter(Boolean)) result = result.split(secret).join('[已隐去]')
    return result
  }

  async persist() {
    await mkdir(this.output, { recursive: true })
    this.state.updatedAt = now()
    await writeFile(resolve(this.output, 'manifest.json'), JSON.stringify(this.state, null, 2))
    await writeFile(
      resolve(this.output, 'result.json'),
      JSON.stringify(
        {
          source,
          runId: this.runId,
          prefix: this.prefix,
          results: this.state.results,
          attempts: this.state.attempts,
          taskIds: this.state.taskIds,
          records: this.state.records,
          unfinishedCommands: Object.entries(this.state.commands)
            .filter(([, command]) => command.status !== 'APPLIED')
            .map(([key, command]) => ({ key, path: command.path, status: command.status })),
          retention: this.state.retention,
          coverageLimits: [
            '仅 HTTP；不以此替代真实浏览器按钮、弹窗和布局验收。',
            '不重置真实账号，不授予权限。授权不足明确 BLOCKED，不伪装业务通过。',
            '已通过步骤恢复时跳过；写请求复用原修订和 requestKey。失败步骤的阶段状态需依据失败清单核对，不承诺任意中断点自动通过。',
            '只对已取得读取权的原施工记录做摘要核对，不宣称验证不可见记录。',
            '不清理真实用户或本次任务。未完成任务和失败命令保留在 manifest 供后续人工核对。'
          ]
        },
        null,
        2
      )
    )
  }

  async step(id, work, dependencies = [], always = false) {
    if (!always && this.selected && !this.selected.has(id)) return
    const previous = this.state.results.find(item => item.id === id)
    if (!always && previous?.status === 'PASS') return console.log(`SKIP ${id} 已有成功回执`)
    const unavailable = dependencies.filter(dep => this.state.results.find(item => item.id === dep)?.status !== 'PASS')
    let result
    try {
      if (id !== '01' && id !== '16' && !this.loginReady) throw new Blocked('本次运行尚未完成三账号身份核对')
      if (unavailable.length) throw new Blocked(`依赖步骤未通过：${unavailable.join(', ')}`)
      result = { id, name: matrix.find(item => item[0] === id)[1], status: 'PASS', detail: await work(), at: now() }
    } catch (error) {
      result = {
        id,
        name: matrix.find(item => item[0] === id)[1],
        status: error instanceof Blocked ? 'BLOCKED' : 'FAIL',
        error: this.clean(error.message),
        at: now()
      }
    }
    this.state.results = this.state.results.filter(item => item.id !== id).concat(result)
    this.state.attempts.push(result)
    await this.persist()
    console.log(`${result.status} ${id} ${result.name}${result.error ? `：${result.error}` : ''}`)
  }

  async loginRealAccounts() {
    // 不调用父类 login/prepareUsers；此工具没有 .env.test 或管理员身份回退路径。
    for (const role of roles) {
      const username = process.env[`TASK_QA_${role}_USERNAME`]
      const password = process.env[`TASK_QA_${role}_PASSWORD`]
      if (!username || !password) throw new Blocked(`缺少 TASK_QA_${role}_USERNAME/PASSWORD`)
      this.secrets.push(password)
    }
    for (const role of roles) {
      const username = process.env[`TASK_QA_${role}_USERNAME`]
      const login = await this.request(
        '/system/auth/login',
        { username, password: process.env[`TASK_QA_${role}_PASSWORD`] },
        null
      )
      if (login.code !== 0 || !login.data?.accessToken)
        throw new Blocked(`${role} 登录被拒：${this.clean(login.msg || '未返回会话')}`)
      this.tokens[role] = login.data.accessToken
      this.secrets.push(login.data.accessToken, login.data.refreshToken)
      const info = await this.api('/system/auth/get-permission-info', undefined, this.tokens[role])
      const actor = {
        id: String(info.user.id),
        username,
        name: info.user.nickname || username,
        taskWideAccess: (info.permissions || []).some(value => ['*:*:*', 'nocode:task:manage-all'].includes(value))
      }
      if (this.state.actors[role]) {
        sameId(actor.id, this.state.actors[role].id)
        assert.equal(actor.username, this.state.actors[role].username, '不能用其他账号接续原批次')
      }
      this.state.actors[role] = actor
    }
    assert.equal(new Set(Object.values(this.state.actors).map(actor => actor.id)).size, 3, '必须为三个不同真实账号')
    this.loginReady = true
    return this.state.actors
  }

  guardWrite(path, body) {
    assert.ok(this.loginReady, '三账号身份核对通过后才允许任务写入')
    const allowed = ['create', 'claim', 'claim-group', 'assign', 'transition', 'checklist', 'entries/save']
    assert.ok(allowed.includes(path), '没有授权用户/权限/应用配置/其他写接口')
    if (path === 'create') {
      assert.ok(body.task.title.startsWith(`${this.prefix} `), '新建只能使用本轮前缀')
      assert.ok((body.nodes || []).every(node => node.title.startsWith(`${this.prefix} `)))
      assert.ok(!body.business && !body.existingRecord, '此验收不在创建时写原业务整单')
      if (body.applicationId) sameId(body.applicationId, this.state.construction.applicationId)
      if (body.project) {
        sameId(body.project.applicationId, this.state.construction.applicationId)
        sameId(body.project.objectId, this.state.construction.objects.project.id)
        assert.ok(this.state.construction.projects.includes(String(body.project.recordId)))
      }
      return
    }
    if (path === 'checklist') body.ids.forEach(id => this.own(id))
    else this.own(body.id || body.rootId || body.taskId)
    if (path === 'assign')
      assert.ok(Object.values(this.state.actors).some(actor => actor.id === String(body.assigneeId)))
    if (path === 'entries/save') {
      const object = this.state.construction.objects.log
      assert.equal(body.entryKey, 'construction_log')
      assert.equal(body.record.id, null, '仅新增日志，禁止更新任何已有施工记录')
      assert.equal(body.record.formId, 'log_form')
      sameId(body.record.applicationId, this.state.construction.applicationId)
      sameId(body.record.objectId, object.id)
      assert.ok(String(body.record.values[object.fields.name]).startsWith(`${this.prefix} `))
    }
  }

  id(role) {
    return this.state.actors[role].id
  }
  own(id) {
    assert.ok(this.state.taskIds.includes(id), '写操作只能作用于本轮精确登记任务')
  }
  taskId(label) {
    const id = this.state.tasks[label]
    assert.ok(id, `缺少本轮任务 ${label}`)
    this.own(id)
    return id
  }
  async read(path, body, role = 'BOSS', permissionAsBlocked = false) {
    const result = await this.request(path, body, this.tokens[role])
    if (result.code !== 0 && permissionAsBlocked && permissionDenied(result))
      throw new Blocked(this.clean(`${path}: ${result.msg}`))
    assert.equal(result.code, 0, `${path}: ${this.clean(result.msg || '请求失败')}`)
    return result.data
  }
  task(path, body, role = 'BOSS', permissionAsBlocked = false) {
    if (['create', 'claim', 'claim-group', 'assign', 'transition', 'checklist', 'entries/save'].includes(path))
      this.guardWrite(path, body)
    return this.read(`/nocode/tasks/${path}`, body, role, permissionAsBlocked)
  }
  detail(label, role = 'BOSS') {
    return this.task('detail', { id: this.taskId(label) }, role)
  }

  async memo(key, path, build, role = 'BOSS', permissionAsBlocked = false) {
    let command = this.state.commands[key]
    if (!command) {
      command = { path, role, body: await build(), status: 'PREPARED', at: now() }
      this.state.commands[key] = command
      await this.persist()
    }
    assert.equal(command.path, path)
    assert.equal(command.role, role)
    if (command.status === 'APPLIED') return command.response
    this.guardWrite(path, command.body)
    const result = await this.request(`/nocode/tasks/${path}`, command.body, this.tokens[role])
    if (result.code !== 0) {
      command.status = 'REJECTED'
      command.error = { code: result.code, message: this.clean(result.msg || '') }
      await this.persist()
      if (permissionAsBlocked && permissionDenied(result)) throw new Blocked(command.error.message)
      assert.equal(result.code, 0, `${path}: ${command.error.message}`)
    }
    command.status = 'APPLIED'
    command.response = result.data
    delete command.error
    await this.persist()
    return result.data
  }

  async deny(path, body, role, expression) {
    if (path !== 'detail') this.guardWrite(path, body)
    else this.own(body.id)
    const result = await this.request(`/nocode/tasks/${path}`, body, this.tokens[role])
    assert.notEqual(result.code, 0, `${path} 越权或状态冲突应拒绝`)
    assert.ok(result.http < 500, '拒绝不能是未处理 500')
    if (expression) assert.match(result.msg || '', expression)
    return { code: result.code, message: this.clean(result.msg) }
  }

  node(label, extra = {}) {
    return super.node(label, extra)
  }
  async create(label, root, children = [], extra = {}, business = false) {
    const detail = await this.memo(
      `create:${label}`,
      'create',
      async () => ({ task: root, nodes: children, requestKey: randomUUID(), ...extra }),
      'BOSS',
      business
    )
    assert.ok(detail.task.title.startsWith(`${this.prefix} `))
    for (const row of detail.nodes || [detail.task]) {
      assert.ok(row.title.startsWith(`${this.prefix} `), '不能登记原有任务')
      if (!this.state.taskIds.includes(row.id)) this.state.taskIds.push(row.id)
      this.state.tasks[row.title.slice(this.prefix.length + 1)] = row.id
    }
    this.state.tasks[label] = detail.task.id
    await this.persist()
    return detail
  }

  async action(key, label, action, role, note = '三账号实际验收') {
    const id = this.taskId(label)
    return this.memo(
      key,
      'transition',
      async () => ({
        id,
        action,
        expectedRevision: (await this.detail(label, role)).task.revision,
        note,
        requestKey: randomUUID()
      }),
      role
    )
  }
  async todo(role) {
    return this.task('page', { scope: 'MINE', tab: 'TODO', search: this.prefix, pageNo: 1, pageSize: 100 }, role)
  }
  async checklist(label, role) {
    const context = await this.task('checklist-context', { ids: [this.taskId(label)], target: 'SELF' }, role)
    return { ...context, item: context.items[0] }
  }
  async listChange(key, label, role, period, action) {
    const id = this.taskId(label)
    return this.memo(
      key,
      'checklist',
      async () => {
        const context = await this.checklist(label, role)
        const plans = period === 'DAY' ? context.item.todayPlans : context.item.weekPlans
        const planIds = action === 'REMOVE' ? plans.filter(plan => plan.canCancel).map(plan => plan.id) : []
        if (action === 'REMOVE') assert.ok(planIds.length, '只能移出真实可移出项')
        return {
          ids: [id],
          target: 'SELF',
          action,
          period,
          date: period === 'DAY' ? context.today : context.weekStart,
          planIds,
          expectedVersions: { [id]: context.item.version },
          requestKey: randomUUID()
        }
      },
      role
    )
  }
  async readRecords(objectId) {
    const all = []
    for (let pageNo = 1; pageNo <= 1000; pageNo++) {
      const page = await this.read(
        '/nocode/runtime/page',
        { applicationId: this.state.construction.applicationId, objectId, pageNo, pageSize: 100 },
        'BOSS',
        true
      )
      all.push(...page.list)
      if (all.length >= page.total) return all
      assert.ok(page.list.length, '业务分页不能无进展')
    }
    throw new Blocked('施工原记录超过安全读取上限，请人工缩小验收范围')
  }
  async entryRows(label, entryKey, role) {
    return this.task(
      'entries/page',
      { taskId: this.taskId(label), entryKey, all: false, onlyMine: false, pageNo: 1, pageSize: 100 },
      role
    )
  }
  async applicationTasks(recordId) {
    const context = recordId
      ? { pageId: 'project_detail', nodeId: 'project_tasks', recordId }
      : { pageId: 'application_tasks', nodeId: 'application_tasks_list' }
    return this.task(
      'page-tasks',
      {
        applicationId: this.state.construction.applicationId,
        ...context,
        query: { scope: 'VISIBLE', tab: 'ALL', search: this.prefix, pageNo: 1, pageSize: 100 }
      },
      'BOSS',
      true
    )
  }
}

async function run() {
  const ac = new MultiAccountAcceptance()
  await ac.initialize()
  if (process.env.TASK_MULTI_ACCOUNT_STEPS) {
    ac.selected = new Set(process.env.TASK_MULTI_ACCOUNT_STEPS.split(',').map(value => value.trim().padStart(2, '0')))
    assert.ok(
      [...ac.selected].every(id => matrix.some(item => item[0] === id)),
      '步骤只接受01至16'
    )
  }
  const fieldState = row =>
    Object.fromEntries(
      ['assigneeId', 'status', 'plannedStart', 'expectedStart', 'expectedEnd', 'actualStart', 'actualEnd'].map(key => [
        key,
        row[key] ?? null
      ])
    )
  const projectRef = index => ({
    applicationId: ac.state.construction.applicationId,
    objectId: ac.state.construction.objects.project.id,
    recordId: ac.state.construction.projects[index],
    label: `既有施工项目 ${index + 1}`
  })
  const binding = formId => ({ applicationId: ac.state.construction.applicationId, formId, entryId: null })
  try {
    await ac.step('01', () => ac.loginRealAccounts(), [], true)
    await ac.step(
      '02',
      async () => {
        const a = ac.node('独立A', { assignmentMode: 'ASSIGNED', assigneeId: ac.id('LI') })
        const b = ac.node('独立B', { assignmentMode: 'ASSIGNED', assigneeId: ac.id('KE'), predecessorIds: [a.id] })
        const b1 = ac.node('独立B1', { parentId: b.id, assignmentMode: 'ASSIGNED', assigneeId: ac.id('KE') })
        const created = await ac.create(
          '独立总任务',
          ac.node('独立总任务', { assignmentMode: 'ASSIGNED', assigneeId: ac.id('LI'), acceptorId: ac.id('BOSS') }),
          [a, b, b1]
        )
        const li = await ac.todo('LI'),
          ke = await ac.todo('KE')
        assert.ok(li.list.some(row => row.id === ac.taskId('独立A')))
        assert.ok(!li.list.some(row => row.id === ac.taskId('独立B')))
        assert.ok(ke.list.some(row => row.id === ac.taskId('独立B')))
        assert.ok(!ke.list.some(row => row.id === ac.taskId('独立A')))
        return { rootId: created.task.id, nodes: created.nodes.map(row => row.id) }
      },
      ['01']
    )
    await ac.step(
      '03',
      async () => {
        const a = (await ac.detail('独立A')).task
        await ac.deny(
          'transition',
          { id: a.id, action: 'START', expectedRevision: a.revision, requestKey: randomUUID() },
          'KE',
          /权限|负责人/
        )
        await ac.action('independent-root-start', '独立总任务', 'START', 'LI')
        for (const label of ['独立B', '独立B1']) {
          const row = (await ac.detail(label, 'KE')).task
          assert.equal(row.canStart, false)
          await ac.deny(
            'transition',
            { id: row.id, action: 'START', expectedRevision: row.revision, requestKey: randomUUID() },
            'KE',
            /前置|上级|等待/
          )
        }
        await ac.action('independent-a-start', '独立A', 'START', 'LI')
        return { unauthorizedStart: '拒绝', dependencyAndAncestor: '拒绝' }
      },
      ['02']
    )
    await ac.step(
      '04',
      async () => {
        await ac.action('independent-a-complete', '独立A', 'COMPLETE', 'LI')
        await ac.action('independent-b-start', '独立B', 'START', 'KE')
        await ac.action('independent-b1-start', '独立B1', 'START', 'KE')
        const b = (await ac.detail('独立B', 'KE')).task
        await ac.deny(
          'transition',
          { id: b.id, action: 'COMPLETE', expectedRevision: b.revision, requestKey: randomUUID() },
          'KE',
          /子任务/
        )
        await ac.action('independent-b1-complete', '独立B1', 'COMPLETE', 'KE')
        await ac.action('independent-b-complete', '独立B', 'COMPLETE', 'KE')
        const submitted = await ac.action('independent-submit-1', '独立总任务', 'COMPLETE', 'LI')
        assert.equal(submitted.task.status, 'PENDING_ACCEPTANCE')
        assert.equal(submitted.task.actualEnd, null)
        return { root: submitted.task.id, status: submitted.task.status }
      },
      ['03']
    )
    await ac.step(
      '05',
      async () => {
        const root = (await ac.detail('独立总任务')).task
        await ac.deny(
          'transition',
          {
            id: root.id,
            action: 'APPROVE',
            expectedRevision: root.revision,
            note: '无资格不能验收',
            requestKey: randomUUID()
          },
          'KE',
          /权限|验收/
        )
        await ac.deny(
          'transition',
          { id: root.id, action: 'REJECT', expectedRevision: root.revision, note: '', requestKey: randomUUID() },
          'BOSS',
          /原因/
        )
        assert.equal(
          (await ac.action('independent-reject', '独立总任务', 'REJECT', 'BOSS', '请补充收尾说明')).task.status,
          'RUNNING'
        )
        await ac.action('independent-submit-2', '独立总任务', 'COMPLETE', 'LI', '已补充收尾说明')
        const done = await ac.action('independent-approve', '独立总任务', 'APPROVE', 'BOSS', '核验通过')
        assert.equal(done.task.status, 'COMPLETED')
        assert.ok(done.task.actualEnd)
        assert.equal(done.events.filter(event => event.type === 'SUBMITTED_FOR_ACCEPTANCE').length, 2)
        assert.ok(done.events.some(event => event.type === 'REJECTED'))
        return { status: done.task.status, actualEnd: done.task.actualEnd, submissions: 2 }
      },
      ['04']
    )
    await ac.step(
      '06',
      async () => {
        const children = [
          ac.node('单领默认项', { assignmentMode: 'FOLLOW_ROOT' }),
          ac.node('整体默认项', { assignmentMode: 'FOLLOW_ROOT' }),
          ac.node('整体开放项'),
          ac.node('限定柯伟项', { candidateUserIds: [ac.id('KE')] }),
          ac.node('明确待分配项', { assignmentMode: 'UNASSIGNED' }),
          ac.node('柯伟既有分工', { assignmentMode: 'ASSIGNED', assigneeId: ac.id('KE') })
        ]
        const created = await ac.create('领取总任务', ac.node('领取总任务'), children)
        const page = await ac.task(
          'claimable-groups',
          { search: `${ac.prefix} 领取总任务`, pageNo: 1, pageSize: 100 },
          'LI'
        )
        assert.equal(page.total, 1)
        sameId(page.list[0].rootId, created.task.id)
        const summary = await ac.task('claimable-children', { rootId: created.task.id }, 'LI')
        assert.ok(
          summary.every(
            item => item.rootId === created.task.id && !('binding' in item) && !('candidateUserIds' in item)
          )
        )
        assert.ok(!summary.some(item => item.id === ac.taskId('限定柯伟项')))
        ac.state.facts.stalePreview = await ac.task(
          'claim-preview',
          { rootId: created.task.id, includeOpen: true },
          'LI'
        )
        await ac.persist()
        return { root: created.task.id, previewCount: ac.state.facts.stalePreview.items.length }
      },
      ['01']
    )
    await ac.step(
      '07',
      async () => {
        const id = ac.taskId('单领默认项')
        await ac.memo(
          'single-claim',
          'claim',
          async () => ({
            id,
            expectedRevision: (await ac.detail('单领默认项')).task.revision,
            requestKey: randomUUID()
          }),
          'KE'
        )
        assert.equal((await ac.detail('领取总任务')).task.assigneeId, null)
        const preview = ac.state.facts.stalePreview
        await ac.deny(
          'claim-group',
          {
            rootId: preview.rootId,
            expectedInstanceRevision: preview.instanceRevision,
            includeOpen: true,
            requestKey: randomUUID()
          },
          'LI',
          /修改|刷新|变化/
        )
        return { single: id, rootStillUnassigned: true }
      },
      ['06']
    )
    await ac.step(
      '08',
      async () => {
        const id = ac.taskId('领取总任务')
        const claimed = await ac.memo(
          'whole-claim',
          'claim-group',
          async () => {
            const preview = await ac.task('claim-preview', { rootId: id, includeOpen: true }, 'LI')
            assert.deepEqual(
              new Set(preview.items.map(item => item.id)),
              new Set([id, ac.taskId('整体默认项'), ac.taskId('整体开放项')])
            )
            return {
              rootId: id,
              expectedInstanceRevision: preview.instanceRevision,
              includeOpen: true,
              requestKey: randomUUID()
            }
          },
          'LI'
        )
        const replay = await ac.task('claim-group', ac.state.commands['whole-claim'].body, 'LI')
        sameId(replay.task.id, claimed.task.id)
        for (const label of ['领取总任务', '整体默认项', '整体开放项']) {
          const row = (await ac.detail(label)).task
          sameId(row.assigneeId, ac.id('LI'))
          assert.equal(row.status, 'PENDING')
          assert.equal(row.actualStart, null)
          assert.equal((await ac.checklist(label, 'LI')).item.weekPlans.length, 0)
        }
        sameId((await ac.detail('单领默认项')).task.assigneeId, ac.id('KE'))
        sameId((await ac.detail('柯伟既有分工')).task.assigneeId, ac.id('KE'))
        assert.equal((await ac.detail('明确待分配项')).task.assigneeId, null)
        return { root: id, sameKeyReplay: true }
      },
      ['07']
    )
    await ac.step(
      '09',
      async () => {
        if (ac.state.actors.LI.taskWideAccess)
          throw new Blocked('LI 当前具有全任务管理权，不能据此证明普通总负责人的受限转交边界；不自动降权')
        const id = ac.taskId('整体开放项')
        await ac.memo(
          'delegate',
          'assign',
          async () => {
            const row = (await ac.detail('整体开放项', 'LI')).task
            assert.equal(row.canDelegate, true)
            return {
              id,
              expectedRevision: row.revision,
              assignmentMode: 'ASSIGNED',
              assigneeId: ac.id('KE'),
              candidateUserIds: [],
              requestKey: randomUUID(),
              note: '总负责人转交本人未开始分工'
            }
          },
          'LI'
        )
        sameId((await ac.detail('整体开放项', 'KE')).task.assigneeId, ac.id('KE'))
        await ac.deny('detail', { id }, 'LI', /权限/)
        const other = (await ac.detail('柯伟既有分工')).task
        await ac.deny(
          'assign',
          {
            id: other.id,
            expectedRevision: other.revision,
            assignmentMode: 'ASSIGNED',
            assigneeId: ac.id('LI'),
            candidateUserIds: [],
            requestKey: randomUUID()
          },
          'LI',
          /本人|权限/
        )
        return { delegated: id, unrelatedAssignment: '拒绝' }
      },
      ['08']
    )
    await ac.step(
      '10',
      async () => {
        const label = '整体默认项'
        const baseline = ac.state.facts.checklistBaseline || fieldState((await ac.detail(label, 'LI')).task)
        ac.state.facts.checklistBaseline = baseline
        await ac.persist()
        await ac.listChange('week-add', label, 'LI', 'WEEK', 'ADD')
        await ac.listChange('day-add', label, 'LI', 'DAY', 'ADD')
        let context = await ac.checklist(label, 'LI')
        assert.equal(context.item.todayPlans.length, 1)
        assert.equal(context.item.weekPlans.length, 1)
        await ac.listChange('day-remove', label, 'LI', 'DAY', 'REMOVE')
        context = await ac.checklist(label, 'LI')
        assert.equal(context.item.todayPlans.length, 0)
        assert.equal(context.item.weekPlans.length, 1)
        await ac.listChange('day-readd', label, 'LI', 'DAY', 'ADD')
        await ac.listChange('week-remove', label, 'LI', 'WEEK', 'REMOVE')
        context = await ac.checklist(label, 'LI')
        assert.equal(context.item.todayPlans.length, 1)
        assert.equal(context.item.weekPlans.length, 0)
        assert.deepEqual(fieldState((await ac.detail(label, 'LI')).task), baseline)
        return { todayCount: 1, weekCount: 0, taskStateUnchanged: true }
      },
      ['08']
    )
    await ac.step(
      '11',
      async () => {
        const fixture = JSON.parse(await readFile(resolve('.work/construction-experience/manifest.json'), 'utf8'))
        assert.equal(fixture.applicationCode, 'construction_guide_app')
        const application = await ac.read(
          `/nocode/runtime/application?id=${fixture.applicationId}`,
          undefined,
          'BOSS',
          true
        )
        assert.equal(application.application.code, fixture.applicationCode)
        const resources = application.definition.resources
        for (const id of ['project_form', 'log_form', 'project_detail', 'application_tasks'])
          assert.ok(
            resources.some(resource => resource.id === id),
            `既有资源 ${id} 必须可用，不自动发布修复`
          )
        const projects = Object.values(fixture.seeded.project).map(String)
        assert.ok(projects.length >= 2 && projects[0] !== projects[1])
        ac.state.construction = {
          applicationId: String(fixture.applicationId),
          projects: projects.slice(0, 2),
          objects: fixture.objects
        }
        // 整批快照完成后一次封存；后续不以新值重设已存基线。
        if (!ac.state.baseline) {
          const snapshots = {}
          for (const [name, object] of Object.entries(fixture.objects)) {
            snapshots[name] = Object.fromEntries(
              (await ac.readRecords(object.id)).map(row => [
                row.id,
                digest({ id: row.id, revision: row.revision, values: row.values })
              ])
            )
          }
          ac.state.baseline = { application: digest(application), records: snapshots, capturedAt: now() }
        }
        await ac.persist()
        return {
          applicationId: fixture.applicationId,
          projects,
          recordCounts: Object.fromEntries(
            Object.entries(ac.state.baseline.records).map(([name, rows]) => [name, Object.keys(rows).length])
          )
        }
      },
      ['01']
    )
    await ac.step(
      '12',
      async () => {
        const applicationId = ac.state.construction.applicationId
        await ac.create(
          '仅施工应用',
          ac.node('仅施工应用', { assignmentMode: 'ASSIGNED', assigneeId: ac.id('LI') }),
          [],
          { applicationId },
          true
        )
        for (const index of [0, 1])
          await ac.create(
            `项目${index + 1}挂接`,
            ac.node(`项目${index + 1}挂接`, {
              assignmentMode: 'ASSIGNED',
              assigneeId: ac.id(index === 0 ? 'LI' : 'KE')
            }),
            [],
            { applicationId, project: projectRef(index) },
            true
          )
        const app = await ac.applicationTasks()
        for (const name of ['仅施工应用', '项目1挂接', '项目2挂接'])
          assert.ok(app.list.some(row => row.id === ac.taskId(name)))
        assert.ok(!app.list.some(row => row.id === ac.taskId('独立总任务')))
        for (const index of [0, 1]) {
          const scoped = await ac.applicationTasks(ac.state.construction.projects[index])
          assert.ok(scoped.list.some(row => row.id === ac.taskId(`项目${index + 1}挂接`)))
          assert.ok(
            !scoped.list.some(row => [ac.taskId('仅施工应用'), ac.taskId(`项目${2 - index}挂接`)].includes(row.id))
          )
        }
        return { applicationRows: app.total, isolatedProjects: 2 }
      },
      ['02', '11']
    )
    await ac.step(
      '13',
      async () => {
        const root = ac.node('施工业务总任务', {
          assignmentMode: 'ASSIGNED',
          assigneeId: ac.id('LI'),
          acceptorId: ac.id('BOSS'),
          binding: binding('project_form'),
          dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
          entries: [
            {
              key: 'construction_log',
              name: '本次施工日志',
              binding: binding('log_form'),
              dataMode: 'ROOT_SHARED',
              sourceNodeId: null,
              sourceEntryKey: null,
              readableFieldIds: null,
              writableFieldIds: null,
              required: true,
              allowAll: false
            }
          ]
        })
        const created = await ac.create(
          '施工业务总任务',
          root,
          [ac.node('施工员工执行', { assignmentMode: 'ASSIGNED', assigneeId: ac.id('KE') })],
          { applicationId: ac.state.construction.applicationId, project: projectRef(0) },
          true
        )
        const rows = await ac.entryRows('施工业务总任务', '__business', 'LI')
        assert.equal(rows.total, 1)
        sameId(rows.list[0].record.id, projectRef(0).recordId)
        assert.ok(rows.list[0].sources.some(item => item.operation === 'LINKED'))
        const before = await ac.task('entries/list', { id: ac.taskId('施工员工执行') }, 'KE')
        assert.ok(
          before.every(entry => !entry.canWrite),
          '尚未开始只能只读'
        )
        await ac.action('construction-root-start', '施工业务总任务', 'START', 'LI')
        return { root: created.task.id, linkedProject: rows.list[0].record.id, businessCreated: 0 }
      },
      ['11']
    )
    await ac.step(
      '14',
      async () => {
        await ac.action('construction-child-start', '施工员工执行', 'START', 'KE')
        const id = ac.taskId('施工员工执行')
        const readiness = await ac.task('readiness', { id }, 'KE')
        assert.equal(readiness.canComplete, false)
        const current = (await ac.detail('施工员工执行', 'KE')).task
        await ac.deny(
          'transition',
          { id, action: 'COMPLETE', expectedRevision: current.revision, requestKey: randomUUID() },
          'KE',
          /反馈|材料|记录|必填/
        )
        const object = ac.state.construction.objects.log
        const save = await ac.memo(
          'construction-log-save',
          'entries/save',
          async () => {
            const context = await ac.checklist('施工员工执行', 'KE')
            const values = {
              name: `${ac.prefix} 施工日志`,
              log_date: context.today,
              progress: '本次三账号验收新增日志，不修改原施工记录',
              workers: 2,
              weather: 'SUNNY',
              project_id: projectRef(0).recordId
            }
            return {
              taskId: id,
              entryKey: 'construction_log',
              contributionId: null,
              record: {
                applicationId: ac.state.construction.applicationId,
                objectId: object.id,
                id: null,
                expectedRevision: null,
                formId: 'log_form',
                values: Object.fromEntries(Object.entries(values).map(([key, value]) => [object.fields[key], value])),
                details: {},
                requestKey: randomUUID()
              }
            }
          },
          'KE'
        )
        const record = save.handling?.result?.record
        assert.ok(record?.id && save.contributionId, '新增日志必须返回真实记录与贡献身份')
        assert.ok(String(record.values[object.fields.name]).startsWith(ac.prefix))
        if (!ac.state.records.some(item => item.objectId === object.id && item.id === record.id))
          ac.state.records.push({
            applicationId: ac.state.construction.applicationId,
            objectId: object.id,
            id: record.id,
            contributionId: save.contributionId,
            taskId: id
          })
        await ac.persist()
        const replay = await ac.task('entries/save', ac.state.commands['construction-log-save'].body, 'KE')
        sameId(replay.contributionId, save.contributionId)
        const rows = await ac.entryRows('施工业务总任务', 'construction_log', 'LI')
        assert.equal(rows.total, 1)
        assert.ok(
          rows.list[0].sources.some(
            item =>
              item.taskId === id && String(item.actorId) === ac.id('KE') && item.operation === 'CREATED' && item.time
          )
        )
        return {
          recordId: record.id,
          contributionId: save.contributionId,
          sourceActor: ac.id('KE'),
          sameKeyReplay: true
        }
      },
      ['13']
    )
    await ac.step(
      '15',
      async () => {
        await ac.action('construction-child-complete', '施工员工执行', 'COMPLETE', 'KE')
        await ac.action('construction-submit', '施工业务总任务', 'COMPLETE', 'LI')
        const done = await ac.action(
          'construction-approve',
          '施工业务总任务',
          'APPROVE',
          'BOSS',
          '施工日志和项目关联核验通过'
        )
        assert.equal(done.task.status, 'COMPLETED')
        assert.ok(done.task.actualEnd)
        const history = await ac.task(
          'entries/history-page',
          { taskId: ac.taskId('施工业务总任务'), entryKey: null, onlyCurrentTask: false, pageNo: 1, pageSize: 100 },
          'BOSS'
        )
        const saved = ac.state.records[0]
        const contribution = history.list.find(item => item.id === saved.contributionId)
        assert.ok(contribution && contribution.operation === 'CREATED' && contribution.time)
        sameId(contribution.actorId, ac.id('KE'))
        sameId(contribution.taskId, ac.taskId('施工员工执行'))
        const audit = await ac.task(
          'entries/history-detail',
          { taskId: ac.taskId('施工员工执行'), entryKey: 'construction_log', contributionId: saved.contributionId },
          'BOSS'
        )
        assert.equal(audit.afterKnown, true)
        const materials = await ac.task('entries/materials', { id: ac.taskId('施工员工执行') }, 'BOSS')
        assert.ok(materials.length, '完成后保留固定材料')
        return {
          root: done.task.id,
          status: done.task.status,
          contributionId: saved.contributionId,
          historyCount: history.total,
          materials: materials.length
        }
      },
      ['14']
    )
  } finally {
    // 即便业务阶段中断也尽力核对原记录；从未取得基线时明确 BLOCKED，不把未检查写成未改变。
    await ac.step(
      '16',
      async () => {
        if (!ac.state.baseline || !ac.tokens.BOSS) throw new Blocked('未取得可读的原施工基线，无法证明原记录不变')
        const app = await ac.read(
          `/nocode/runtime/application?id=${ac.state.construction.applicationId}`,
          undefined,
          'BOSS',
          true
        )
        assert.equal(digest(app), ac.state.baseline.application, '施工发布定义发生变化，请人工核对；脚本不会恢复覆盖')
        let checked = 0
        for (const [name, before] of Object.entries(ac.state.baseline.records)) {
          const current = new Map(
            (await ac.readRecords(ac.state.construction.objects[name].id)).map(row => [row.id, row])
          )
          for (const [id, expected] of Object.entries(before)) {
            const row = current.get(id)
            assert.ok(row, `原施工记录 ${name}/${id} 不应消失`)
            assert.equal(
              digest({ id: row.id, revision: row.revision, values: row.values }),
              expected,
              `原施工记录 ${name}/${id} 发生变化；不自动覆盖`
            )
            checked++
          }
        }
        return {
          originalRecordsChecked: checked,
          newRecords: ac.state.records.length,
          applicationUnchanged: true,
          cleanupPerformed: false
        }
      },
      [],
      true
    )
    await ac.persist()
    console.log(`验收证据：${ac.output}`)
    if (ac.state.results.some(result => result.status !== 'PASS')) process.exitCode = 1
  }
}

if (process.env.TASK_MULTI_ACCOUNT_RUN !== '1') {
  console.log(
    JSON.stringify(
      {
        mode: 'prepare-only',
        authenticated: false,
        writes: 0,
        matrix,
        credentials: roles.flatMap(role => [`TASK_QA_${role}_USERNAME`, `TASK_QA_${role}_PASSWORD`]),
        execution:
          'TASK_MULTI_ACCOUNT_RUN=1；可用 TASK_MULTI_ACCOUNT_RESUME=<manifest.json> 及 TASK_MULTI_ACCOUNT_STEPS=02,03 续跑。'
      },
      null,
      2
    )
  )
} else {
  await run()
}
