import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 根任务授权验收夹具；只登记本轮身份，凭据不落盘，不操作施工体验应用。 */
export class TaskDataPolicyAcceptance extends FormAcceptance {
  constructor() {
    super(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
    this.prefix = `dp${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
    this.output = resolve(process.env.TASK_DATA_POLICY_OUTPUT || '.work/task-data-policy', this.prefix)
    this.taskIds = []
    this.records = []
    this.templates = []
    this.cleanup = []
    this.errors = []
    this.results = []
    this.screenshots = []
  }

  title(suffix) {
    return `${this.prefix} ${suffix}`
  }

  get applicationId() {
    return this.app?.application.id
  }

  taskApi(path, body, token) {
    return this.api(`/nocode/tasks/${path}`, body, token)
  }

  taskRequest(path, body, token) {
    return this.request(`/nocode/tasks/${path}`, body, token)
  }

  async persist() {
    await super.persist()
    await writeFile(
      resolve(this.output, 'manifest.json'),
      JSON.stringify(
        {
          prefix: this.prefix,
          source: 'task-data-policy',
          ...this.owned,
          taskIds: this.taskIds,
          records: this.records,
          templates: this.templates,
          retention: '仅精确清单内未结束任务取消、临时成员停用；保留对象、应用、记录及审计，不删除用户数据'
        },
        null,
        2
      )
    )
  }

  async check(name, work, dependencies = []) {
    const blockedBy = dependencies.filter(item => item.status !== 'passed').map(item => item.name)
    let result
    if (blockedBy.length) result = { name, status: 'blocked', blockedBy }
    else {
      try {
        result = { name, status: 'passed', detail: await work() }
      } catch (error) {
        result = { name, status: 'failed', error: error.stack || error.message }
      }
    }
    this.results.push(result)
    console.log(`${result.status.toUpperCase()} ${name}`)
    if (result.error) console.error(result.error)
    await this.persist()
    return result
  }

  node(suffix, extra = {}) {
    return {
      id: randomUUID(),
      parentId: null,
      title: this.title(suffix),
      description: '',
      assigneeId: null,
      assignmentMode: 'OPEN',
      candidateUserIds: [],
      urgency: 'NORMAL',
      priority: 'MEDIUM',
      schedule: { mode: 'UNSCHEDULED', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 0 },
      predecessorIds: [],
      binding: null,
      sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
      ...extra
    }
  }

  binding(formId) {
    return { applicationId: this.applicationId, formId, entryId: null }
  }

  async remember(detail) {
    for (const task of detail.nodes || [detail.task]) {
      assert.ok(task.title.startsWith(this.prefix), '回执只能包含本轮任务')
      if (!this.taskIds.includes(task.id)) this.taskIds.push(task.id)
    }
    await this.persist()
    return detail
  }

  async create(task, extra = {}, token) {
    assert.ok(task.title.startsWith(this.prefix))
    return this.remember(await this.taskApi('create', { task, requestKey: randomUUID(), ...extra }, token))
  }

  async command(id, action, token) {
    assert.ok(this.taskIds.includes(id), '任务命令只能作用于本轮清单')
    const current = await this.taskApi('detail', { id })
    return this.taskApi(
      action === 'CLAIM' ? 'claim' : 'transition',
      {
        id,
        expectedRevision: current.task.revision,
        ...(action === 'CLAIM' ? {} : { action, note: this.title(`验收${action}`) }),
        requestKey: randomUUID()
      },
      token
    )
  }

  async denied(path, body, token, method) {
    const result = await this.request(path, body, token, method)
    if (result.code === 0 && result.data?.task) await this.remember(result.data)
    assert.notEqual(result.code, 0, `${path} 必须拒绝`)
    assert.ok(result.http < 500, '业务拒绝不能是未处理的服务端错误')
    assert.match(
      result.msg,
      /权限|授权|无权|范围|领取|执行|开始|前置|状态|停用|不可|不能|不允许|访问|不存在|已删除|未配置|入口|未登录/
    )
    return { code: result.code, message: result.msg }
  }

  async prepareUsers() {
    this.adminId = String((await this.api('/system/auth/get-permission-info')).user.id)
    const menus = await this.api('/system/menu/list')
    const selected = new Set(
      menus
        .filter(item => ['nocode:task:query', 'nocode:task:create'].includes(item.permission))
        .map(item => String(item.id))
    )
    assert.ok(selected.size, '复用现有任务查询、创建权限')
    for (const id of [...selected]) {
      let parent = menus.find(item => String(item.id) === id)?.parentId
      while (parent && menus.some(item => String(item.id) === String(parent))) {
        const menu = menus.find(item => String(item.id) === String(parent))
        selected.add(String(menu.id))
        parent = menu.parentId
      }
    }
    const roleId = await this.api('/system/role/create', {
      name: this.title('授权验收员工'),
      code: `${this.prefix}_member`,
      sort: 1,
      status: 0,
      dataScope: 1
    })
    this.owned.roles.push({ id: roleId })
    await this.persist()
    await this.api('/system/permission/assign-role-menu', { roleId, menuIds: [...selected] })
    for (const suffix of ['a', 'b']) {
      const id = await this.user(suffix)
      await this.api('/system/permission/assign-user-role', { userId: id, roleIds: [roleId] })
      this[suffix === 'a' ? 'employeeA' : 'employeeB'] = id
      const info = await this.api('/system/auth/get-permission-info', undefined, this.tokens[id])
      assert.ok(info.permissions.includes('nocode:task:query'))
      assert.ok(!info.permissions.includes('nocode:task:manage-all'))
    }
  }

  async prepareApplication() {
    this.business = await this.object('business', '任务授权业务夹具', [
      this.field('name', 'TEXT', '工作内容'),
      this.field('quantity', 'INTEGER', '数量')
    ])
    this.feedback = await this.object('feedback', '任务授权反馈夹具', [
      this.field('name', 'TEXT', '反馈内容'),
      this.field('quantity', 'INTEGER', '数量')
    ])
    const form = (object, id, name) => ({
      id,
      code: id,
      kind: 'FORM',
      name,
      config: {
        objectId: object.objectId,
        nodes: object.definition.fields.map(field => ({
          id: `field_${field.id}`,
          type: 'FIELD',
          fieldId: field.id,
          children: []
        })),
        detailIds: [],
        options: { layout: 'vertical', submitText: '保存记录' }
      }
    })
    this.app = await this.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${this.prefix}_app`,
      name: this.title('任务授权验收应用'),
      description: '仅用于根任务受控委派、范围与审计验收',
      definition: {
        objects: [this.business, this.feedback].map(object => ({
          objectId: object.objectId,
          versionNo: object.versionNo,
          checksum: object.checksum
        })),
        resources: [form(this.business, 'business', '施工内容'), form(this.feedback, 'feedback', '施工日志')]
      }
    })
    assert.notEqual(String(this.applicationId), '4430')
    this.owned.applications.push({ id: this.applicationId, code: this.app.application.code })
    await this.persist()
    for (const object of [this.business, this.feedback]) await this.share(object, this.grant(object))
    this.app = await this.api('/nocode/application/publish', {
      id: this.applicationId,
      expectedRevision: this.app.application.revision,
      reason: this.title('发布独立授权夹具')
    })
    // 不调用 authorize：员工无应用成员授权是本验收的关键前提。
    this.existingBusiness = await this.save(this.business, { name: this.title('任务前业务'), quantity: 1 })
    this.existingFeedback = await this.save(this.feedback, { name: this.title('任务前日志'), quantity: 2 })
    await this.rememberRecord(this.business, this.existingBusiness)
    await this.rememberRecord(this.feedback, this.existingFeedback)
  }

  async rememberRecord(object, record) {
    if (!this.records.some(item => item.objectId === object.objectId && item.id === record.id)) {
      this.records.push({ applicationId: this.applicationId, objectId: object.objectId, id: record.id })
    }
    await this.persist()
    return record
  }

  async finish() {
    const pending = []
    for (const id of this.taskIds) {
      try {
        const detail = await this.taskApi('detail', { id })
        assert.ok(detail.task.title.startsWith(this.prefix), '收尾前复核清单身份和前缀')
        let depth = 0,
          parentId = detail.task.parentId
        while (parentId && depth <= detail.nodes.length) {
          depth++
          parentId = detail.nodes.find(item => item.id === parentId)?.parentId
        }
        pending.push({ id, depth })
      } catch (error) {
        this.cleanup.push({ id, status: 'failed', error: error.message })
      }
    }
    for (const { id } of pending.sort((left, right) => right.depth - left.depth)) {
      try {
        let current = (await this.taskApi('detail', { id })).task
        if (!['COMPLETED', 'CANCELLED'].includes(current.status)) current = (await this.command(id, 'CANCEL')).task
        assert.ok(['COMPLETED', 'CANCELLED'].includes(current.status))
        this.cleanup.push({ id, status: current.status })
      } catch (error) {
        this.cleanup.push({ id, status: 'failed', error: error.message })
      }
    }
    for (const user of this.owned.users) {
      await this.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT').catch(error =>
        this.errors.push(`停用临时员工 ${user.id}: ${error.message}`)
      )
    }
    await this.persist()
    this.tokens = {}
    this.passwords = {}
  }
}
