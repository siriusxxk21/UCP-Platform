import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

/** 真实开发环境浏览器验收的 HTTP 夹具；创建与清理都只接受本工具登记的唯一身份。 */
class FeedbackAlignmentFixture extends TaskDataPolicyAcceptance {
  constructor() {
    super()
    this.prefix = `fta${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
    this.output = resolve('.work/task-feedback-alignment', this.prefix)
    this.metadata = {}
    this.phase = 'preparing'
  }

  async persist() {
    await super.persist()
    await writeFile(
      resolve(this.output, 'manifest.json'),
      JSON.stringify(
        {
          source: 'task-feedback-alignment-fixture-v1',
          prefix: this.prefix,
          base: this.base,
          phase: this.phase,
          owned: this.owned,
          taskIds: this.taskIds,
          records: this.records,
          metadata: this.metadata,
          checks: this.checks,
          cleanup: this.cleanup,
          errors: this.errors,
          retention: '任务终态、停用员工及已发布对象定义保留审计；应用进入回收站，业务记录按登记身份回收'
        },
        null,
        2
      )
    )
  }

  async prepareEmployee() {
    const menus = await this.api('/system/menu/list')
    const selected = new Set(
      menus
        .filter(item => ['nocode:task:query', 'nocode:task:create'].includes(item.permission))
        .map(item => String(item.id))
    )
    assert.ok(selected.size, '必须复用现有任务权限')
    for (const id of [...selected]) {
      let parentId = menus.find(item => String(item.id) === id)?.parentId
      while (parentId && menus.some(item => String(item.id) === String(parentId))) {
        const parent = menus.find(item => String(item.id) === String(parentId))
        selected.add(String(parent.id))
        parentId = parent.parentId
      }
    }
    const roleId = await this.api('/system/role/create', {
      name: this.title('反馈验收执行人'),
      code: `${this.prefix}_executor`,
      sort: 1,
      status: 0,
      dataScope: 1
    })
    this.owned.roles.push({ id: roleId, code: `${this.prefix}_executor` })
    await this.persist()
    await this.api('/system/permission/assign-role-menu', { roleId, menuIds: [...selected] })
    this.employee = await this.user('executor')
    await this.api('/system/permission/assign-user-role', { userId: this.employee, roleIds: [roleId] })
    const permission = await this.api('/system/auth/get-permission-info', undefined, this.tokens[this.employee])
    assert.ok(permission.permissions.includes('nocode:task:query'))
    assert.ok(!permission.permissions.includes('nocode:task:manage-all'))
    const user = this.owned.users.find(item => item.id === this.employee)
    this.metadata.employee = { id: user.id, username: user.username }
    // 供人工浏览器登录使用；只写已被 .gitignore 忽略的独立文件，清理时删除。
    await writeFile(
      resolve(this.output, 'credentials.json'),
      JSON.stringify({ username: user.username, password: this.passwords[user.id] }, null, 2),
      { mode: 0o600 }
    )
    await this.persist()
  }

  form(object, id, name) {
    return {
      id,
      code: id,
      name,
      kind: 'FORM',
      config: {
        objectId: object.objectId,
        nodes: object.definition.fields.map(field => ({
          id: `field_${field.id}`,
          type: 'FIELD',
          fieldId: field.id,
          children: [],
          ...(field.id === this.metadata.referenceFieldId
            ? {
                presentation: {
                  selection: { appearance: 'SELECT', rootIds: [], includeDescendants: false, viewId: 'project_view' }
                }
              }
            : {})
        })),
        detailIds: [],
        options: { layout: 'vertical', submitText: name === '施工日志' ? '保存施工日志' : '保存项目' }
      }
    }
  }

  async prepareApplication(suffix) {
    const resource = (id, kind, name, config) => ({ id, kind, code: id, name, config })
    const taskNode = (id, resourceId, text) => ({ id, type: 'TASKS', resourceId, text, children: [] })
    const app = await this.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${this.prefix}_${suffix}`,
      name: this.title(`任务验收应用 ${suffix.toUpperCase()}`),
      description: '反馈引用权限和应用任务一致性验收专用，无员工应用授权',
      definition: {
        objects: [this.project, this.feedback].map(object => ({
          objectId: object.objectId,
          versionNo: object.versionNo,
          checksum: object.checksum
        })),
        resources: [
          this.form(this.project, 'project_form', '项目资料'),
          this.form(this.feedback, 'feedback_form', '施工日志'),
          resource('project_view', 'VIEW', '东区可选项目', {
            objectId: this.project.objectId,
            fieldIds: [this.project.ids.name, this.project.ids.area],
            equal: { [this.project.ids.area]: '东区' },
            sortFieldId: null,
            descending: false,
            pageSize: 20,
            formId: 'project_form'
          }),
          resource('project_list', 'VIEW', '项目台账', {
            objectId: this.project.objectId,
            fieldIds: [this.project.ids.name, this.project.ids.area],
            equal: {},
            sortFieldId: null,
            descending: false,
            pageSize: 20,
            formId: 'project_form',
            detailPageId: 'record_page'
          }),
          resource('app_page', 'PAGE', '应用任务', { nodes: [taskNode('app_tasks', null, '应用任务')] }),
          resource('bound_page', 'PAGE', '项目表单任务', {
            nodes: [taskNode('bound_tasks', 'project_form', '项目表单任务')]
          }),
          resource('record_page', 'PAGE', '项目任务', {
            contextObjectId: this.project.objectId,
            nodes: [taskNode('record_tasks', 'project_form', '当前项目任务')]
          }),
          resource('list_page', 'PAGE', '项目台账', {
            nodes: [{ id: 'project_records', type: 'VIEW', resourceId: 'project_list', children: [] }]
          }),
          resource('app_menu', 'MENU', '应用任务', { targetId: 'app_page' }),
          resource('bound_menu', 'MENU', '项目表单任务', { targetId: 'bound_page' }),
          resource('record_menu', 'MENU', '项目任务', { targetId: 'record_page' }),
          resource('list_menu', 'MENU', '项目台账', { targetId: 'list_page' })
        ]
      }
    })
    this.app = app
    this.owned.applications.push({ id: app.application.id, code: app.application.code })
    await this.persist()
    for (const object of [this.project, this.feedback]) await this.share(object, this.grant(object))
    const published = await this.api('/nocode/application/publish', {
      id: app.application.id,
      expectedRevision: app.application.revision,
      reason: this.title('发布验收夹具')
    })
    // 不调用 authorize：反馈执行人必须没有应用成员权限。
    return published
  }

  async setup() {
    await this.login()
    await this.persist()
    await this.prepareEmployee()
    this.project = await this.object('project', '反馈关联项目', [
      this.field('name', 'TEXT', '项目名称'),
      this.field('area', 'TEXT', '地区')
    ])
    this.feedback = await this.object('feedback', '任务施工日志', [this.field('name', 'TEXT', '日志标题')], {}, [
      {
        id: null,
        code: 'project',
        name: '所属项目',
        kind: 'REFERENCE',
        targetObjectId: this.project.objectId,
        fieldId: null,
        targetFieldId: null,
        required: false,
        onDelete: 'SET_NULL'
      }
    ])
    this.metadata.referenceFieldId = this.feedback.definition.relations.find(item => item.code === 'project').fieldId
    this.metadata.objects = { project: this.project.objectId, feedback: this.feedback.objectId }
    const appA = await this.prepareApplication('a')
    const appB = await this.prepareApplication('b')
    this.metadata.applications = { a: appA.application.id, b: appB.application.id }
    this.app = appA
    const recordA = await this.save(this.project, { name: this.title('项目 A（东区可选）'), area: '东区' })
    await this.rememberRecord(this.project, recordA)
    const recordB = await this.save(this.project, { name: this.title('项目 B（西区不可选）'), area: '西区' })
    await this.rememberRecord(this.project, recordB)
    this.metadata.projects = { a: recordA.id, b: recordB.id }
    const ref = (app, row) => ({
      applicationId: app.application.id,
      objectId: this.project.objectId,
      recordId: row.id,
      label: row.values[this.project.ids.name]
    })
    const appTask = await this.create(this.node('A 应用整体任务'), { applicationId: appA.application.id })
    const recordTaskA = await this.create(this.node('A 项目 A 任务', { binding: this.binding('project_form') }), {
      applicationId: appA.application.id,
      project: ref(appA, recordA),
      existingRecord: ref(appA, recordA)
    })
    const recordTaskB = await this.create(this.node('A 项目 B 任务', { binding: this.binding('project_form') }), {
      applicationId: appA.application.id,
      project: ref(appA, recordB),
      existingRecord: ref(appA, recordB)
    })
    const feedbackTask = await this.create(
      this.node('无应用权限员工反馈', {
        assignmentMode: 'ASSIGNED',
        assigneeId: this.employee,
        dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
        entries: [
          {
            key: 'feedback',
            name: '施工日志',
            binding: this.binding('feedback_form'),
            dataMode: 'ROOT_SHARED',
            sourceNodeId: null,
            sourceEntryKey: null,
            readableFieldIds: null,
            writableFieldIds: null,
            required: false,
            allowAll: false
          }
        ]
      }),
      { applicationId: appA.application.id, project: ref(appA, recordA) }
    )
    await this.command(feedbackTask.task.id, 'START', this.tokens[this.employee])
    this.app = appB
    const otherAppTask = await this.create(
      this.node('B 同对象任务不得进入 A', { binding: this.binding('project_form') }),
      {
        applicationId: appB.application.id,
        project: ref(appB, recordB),
        existingRecord: ref(appB, recordB)
      }
    )
    this.app = appA
    this.metadata.tasks = {
      app: appTask.task.id,
      recordA: recordTaskA.task.id,
      recordB: recordTaskB.task.id,
      feedback: feedbackTask.task.id,
      otherApp: otherAppTask.task.id
    }
    const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
    const url = (menu, recordId = '') =>
      `${origin}/nocode-app/runtime?id=${appA.application.id}&menu=${menu}${recordId ? `&recordId=${recordId}` : ''}`
    this.metadata.urls = {
      app: url('app_menu'),
      bound: url('bound_menu'),
      list: url('list_menu'),
      recordA: url('record_menu', recordA.id),
      recordB: url('record_menu', recordB.id),
      feedback: `${origin}/nocode-app/task-center?taskId=${feedbackTask.task.id}`
    }
    await this.persist()
    await this.record('应用与表单绑定任务页排除另一应用', async () => {
      const query = { scope: 'VISIBLE', tab: 'ALL', search: this.prefix, pageNo: 1, pageSize: 100 }
      const app = await this.taskApi('page-tasks', {
        applicationId: this.applicationId,
        pageId: 'app_page',
        nodeId: 'app_tasks',
        query
      })
      const bound = await this.taskApi('page-tasks', {
        applicationId: this.applicationId,
        pageId: 'bound_page',
        nodeId: 'bound_tasks',
        query
      })
      const project = await this.taskApi('page-tasks', {
        applicationId: this.applicationId,
        pageId: 'record_page',
        nodeId: 'record_tasks',
        recordId: recordA.id,
        query
      })
      assert.ok(app.list.some(row => row.id === appTask.task.id))
      assert.ok(bound.list.some(row => row.id === recordTaskA.task.id))
      assert.ok(project.list.some(row => row.id === recordTaskA.task.id))
      assert.ok(project.list.every(row => row.id !== recordTaskB.task.id))
      assert.ok([...app.list, ...bound.list].every(row => row.id !== otherAppTask.task.id))
      return {
        app: app.list.map(row => row.id),
        bound: bound.list.map(row => row.id),
        recordA: project.list.map(row => row.id)
      }
    })
    await this.record('执行人无应用授权但可选任务反馈依赖项目', async () => {
      const denied = await this.denied('/nocode/runtime/page', this.query(this.project), this.tokens[this.employee])
      const target = { taskId: feedbackTask.task.id, entryKey: 'feedback', recordId: null, contributionId: null }
      const selected = await this.taskApi(
        'entries/selection',
        {
          target,
          query: {
            applicationId: this.applicationId,
            objectId: this.feedback.objectId,
            fieldId: this.metadata.referenceFieldId,
            formId: 'feedback_form',
            formValues: {},
            creating: true,
            pageNo: 1,
            pageSize: 20,
            selected: []
          }
        },
        this.tokens[this.employee]
      )
      const candidates = selected.list || selected.options || []
      assert.ok(
        candidates.some(row => String(row.id ?? row.value) === String(recordA.id)),
        '东区项目 A 可选'
      )
      assert.ok(
        candidates.every(row => String(row.id ?? row.value) !== String(recordB.id)),
        '视图限定必须排除西区项目 B'
      )
      return { outsideTask: denied, selection: selected }
    })
    this.phase = 'ready'
    await this.persist()
    console.log(
      JSON.stringify(
        {
          phase: this.phase,
          prefix: this.prefix,
          manifest: resolve(this.output, 'manifest.json'),
          credentialsFile: resolve(this.output, 'credentials.json'),
          ...this.metadata
        },
        null,
        2
      )
    )
  }

  async cleanupFixture(manifestPath) {
    const manifest = JSON.parse(await readFile(resolve(manifestPath), 'utf8'))
    assert.equal(manifest.source, 'task-feedback-alignment-fixture-v1')
    assert.match(manifest.prefix, /^fta[a-z0-9]+$/)
    this.prefix = manifest.prefix
    this.output = dirname(resolve(manifestPath))
    this.base = manifest.base
    this.owned = manifest.owned
    this.taskIds = manifest.taskIds
    this.records = manifest.records
    this.metadata = manifest.metadata
    this.checks = manifest.checks
    this.cleanup = manifest.cleanup || []
    this.errors = manifest.errors || []
    this.phase = 'cleaning'
    await this.login()
    // 先逐一确认临时账号身份，避免复用旧清单时停用已被替换的账号。
    for (const user of this.owned.users) {
      const current = await this.api(`/system/user/get?id=${user.id}`)
      assert.equal(current.username, user.username)
      assert.ok(user.username.startsWith(this.prefix))
    }
    const attempt = async (kind, id, work) => {
      let succeeded = false
      try {
        await work()
        this.cleanup.push({ kind, id, status: 'cleaned' })
        succeeded = true
      } catch (error) {
        this.cleanup.push({ kind, id, status: 'retained', reason: error.message })
      }
      await this.persist()
      return succeeded
    }
    // 对象是本轮新建且未向其他应用共享；先登记浏览器新增记录，再逐项回收，拒绝跨前缀数据。
    let discoveryComplete = true
    for (const application of this.owned.applications) {
      const discovered = await attempt('discover', application.id, async () => {
        const app = await this.api(`/nocode/application/get?id=${application.id}`)
        assert.equal(app.application.code, application.code)
        assert.ok(application.code.startsWith(this.prefix))
        const tasks = await this.taskApi('page-tasks', {
          applicationId: application.id,
          pageId: 'app_page',
          nodeId: 'app_tasks',
          query: { scope: 'VISIBLE', tab: 'ALL', search: this.prefix, pageNo: 1, pageSize: 100 }
        })
        assert.ok(tasks.total <= 100, '任务超出单页时停止自动回收')
        for (const task of tasks.list) {
          assert.ok(task.title.startsWith(this.prefix), '只接管唯一前缀任务')
          const detail = await this.taskApi('detail', { id: task.id })
          await this.remember(detail)
        }
        for (const object of this.owned.objects) {
          const version = await this.api(`/nocode/application/object-version?id=${object.id}`)
          assert.equal(version.definition.objectCode, object.code)
          const page = await this.api('/nocode/runtime/page', {
            applicationId: application.id,
            objectId: object.id,
            pageNo: 1,
            pageSize: 100,
            equal: {},
            descending: false
          })
          assert.ok(page.total <= 100, '夹具超出单页时停止自动回收')
          for (const row of page.list) {
            if (!this.records.some(item => item.objectId === object.id && item.id === row.id))
              this.records.push({ applicationId: application.id, objectId: object.id, id: row.id })
          }
        }
      })
      discoveryComplete = discovered && discoveryComplete
    }
    assert.ok(discoveryComplete, '夹具发现不完整，停止回收应用与账号；按 manifest 修复后重试')
    // 先结束任务，后回收数据；任务和贡献历史保留，完成状态不被清理改写。
    await this.finish()
    await this.login()
    for (const record of [...this.records].reverse()) {
      await attempt('record', record.id, async () => {
        assert.ok(
          this.owned.objects.some(
            object => String(object.id) === String(record.objectId) && object.code.startsWith(this.prefix)
          )
        )
        assert.ok(
          this.owned.applications.some(
            app => String(app.id) === String(record.applicationId) && app.code.startsWith(this.prefix)
          )
        )
        const result = await this.api(
          `/nocode/runtime/get?applicationId=${record.applicationId}&objectId=${record.objectId}&id=${record.id}`
        )
        const row = result.record || result
        await this.api('/nocode/runtime/delete', { ...record, expectedRevision: row.revision })
      })
    }
    for (const application of this.owned.applications) {
      await attempt('application', application.id, async () => {
        const current = await this.api(`/nocode/application/get?id=${application.id}`)
        assert.equal(current.application.code, application.code)
        assert.ok(application.code.startsWith(this.prefix))
        await this.api('/nocode/application/delete', {
          id: application.id,
          expectedRevision: current.application.revision,
          reason: this.title('验收结束回收应用')
        })
      })
    }
    for (const user of this.owned.users) {
      await attempt('user', user.id, async () => {
        const current = await this.api(`/system/user/get?id=${user.id}`)
        assert.equal(current.username, user.username)
        assert.ok(user.username.startsWith(this.prefix))
        await this.api('/system/permission/assign-user-role', { userId: user.id, roleIds: [] })
        await this.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
      })
    }
    for (const role of this.owned.roles) {
      await attempt('role', role.id, async () => {
        const current = await this.api(`/system/role/get?id=${role.id}`)
        assert.equal(current.code, role.code)
        assert.ok(role.code.startsWith(this.prefix))
        await this.api(`/system/role/delete?id=${role.id}`, undefined, undefined, 'DELETE')
      })
    }
    // 精确文件路径，不递归删除目录；清理凭据后仍保留不含敏感值的操作记录。
    await rm(resolve(this.output, 'credentials.json'), { force: true })
    this.phase = 'cleaned'
    await this.persist()
    this.tokens = {}
    console.log(
      JSON.stringify(
        {
          phase: this.phase,
          manifest: resolve(this.output, 'manifest.json'),
          cleanup: this.cleanup,
          errors: this.errors
        },
        null,
        2
      )
    )
  }
}

const [action, manifest] = process.argv.slice(2)
if (!['setup', 'cleanup'].includes(action)) {
  console.log('用法：node tools/task-center-regression/feedback-alignment-fixture.mjs setup | cleanup <manifest.json>')
} else {
  const fixture = new FeedbackAlignmentFixture()
  try {
    if (action === 'setup') await fixture.setup()
    else {
      assert.ok(manifest, '清理必须显式传入本轮 manifest.json')
      await fixture.cleanupFixture(manifest)
    }
  } catch (error) {
    fixture.errors.push(error.message)
    await mkdir(fixture.output, { recursive: true })
    await fixture.persist()
    console.error(
      JSON.stringify(
        { phase: fixture.phase, error: error.message, manifest: resolve(fixture.output, 'manifest.json') },
        null,
        2
      )
    )
    process.exitCode = 1
  }
}
