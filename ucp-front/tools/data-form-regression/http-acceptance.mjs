import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

/** 正式 HTTP 验收夹具：凭据仅存内存，只操作本次创建并登记的对象、账号和业务记录。 */
export class FormAcceptance {
  constructor(base = 'http://127.0.0.1:8080/api', output = 'tests/test-results/form-acceptance') {
    this.base = base
    this.output = resolve(output)
    this.prefix = `fa${Date.now().toString(36)}`
    this.checks = []
    this.owned = { objects: [], users: [], roles: [], departments: [], applications: [] }
    this.tokens = {}
    this.passwords = {}
  }

  async request(path, body, token = this.tokens.admin, method = body === undefined ? 'GET' : 'POST') {
    const response = await fetch(this.base + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
    return { http: response.status, ...(await response.json()) }
  }

  async api(path, body, token, method) {
    const result = await this.request(path, body, token, method)
    assert.equal(result.code, 0, `${path}: ${result.code} ${result.msg}`)
    return result.data
  }

  async login() {
    const text = await readFile(resolve('.env.test'), 'utf8')
    const env = Object.fromEntries(
      text
        .split(/\r?\n/)
        .filter(line => /^E2E_(USERNAME|PASSWORD)=/.test(line))
        .map(line => {
          const pos = line.indexOf('=')
          return [
            line.slice(0, pos),
            line
              .slice(pos + 1)
              .trim()
              .replace(/^['"]|['"]$/g, '')
          ]
        })
    )
    const result = await this.api(
      '/system/auth/login',
      { username: env.E2E_USERNAME, password: env.E2E_PASSWORD },
      null
    )
    assert.ok(result.accessToken, '需要正常管理员登录会话')
    this.tokens.admin = result.accessToken
    await mkdir(this.output, { recursive: true })
  }

  async record(name, work) {
    this.checks = this.checks.filter(check => check.name !== name)
    try {
      const detail = await work()
      this.checks.push({ name, passed: true, detail })
      await this.persist()
      return detail
    } catch (error) {
      this.checks.push({ name, passed: false, error: error.message })
      await this.persist()
      throw error
    }
  }

  async persist() {
    await mkdir(this.output, { recursive: true })
    await writeFile(
      resolve(this.output, 'http-result.json'),
      JSON.stringify(
        {
          time: new Date().toISOString(),
          base: this.base,
          prefix: this.prefix,
          applicationId: this.app?.application.id,
          owned: this.owned,
          checks: this.checks
        },
        null,
        2
      )
    )
  }

  field(code, type = 'TEXT', name = code) {
    return {
      key: code,
      id: null,
      code,
      name,
      type,
      length: type === 'TEXT' ? 100 : null,
      precision: type === 'DECIMAL' ? 20 : null,
      scale: type === 'DECIMAL' ? 4 : null,
      required: code === 'name',
      unique: false,
      sort: 10
    }
  }

  option(extra = {}) {
    return { classification: 'NORMAL', state: 'ACTIVE', options: [], resolver: 'NONE', ...extra }
  }

  calculation(mode, updateMode = 'LIVE', extra = {}) {
    return {
      mode,
      updateMode,
      targetObjectId: null,
      relationId: null,
      targetField: null,
      aggregate: 'SUM',
      logic: 'AND',
      conditions: [],
      excludeCurrent: false,
      ...extra
    }
  }

  async object(suffix, name, fields, options = {}, relations = [], details = []) {
    const existing = this.owned.objects.find(object => object.code === `${this.prefix}_${suffix}`)
    const draft = existing
      ? await this.api(`/nocode/design/get?id=${existing.id}`)
      : await this.api('/nocode/design/save', {
          draft: {
            id: null,
            expectedLockVersion: null,
            objectCode: `${this.prefix}_${suffix}`,
            objectName: `表单验收${name}`,
            description: '2026-09-11 本地功能验收专用',
            tableName: `biz_${this.prefix}_${suffix}`,
            titleFieldKey: 'name',
            fields,
            removedFieldIds: []
          },
          settings: {},
          fieldOptions: options,
          relations,
          indexes: [],
          details
        })
    if (!existing) this.owned.objects.push({ id: draft.draft.id, code: draft.draft.objectCode })
    await this.persist()
    if (!draft.publishedVersion) {
      const plan = await this.api('/nocode/design/plan', {
        id: draft.draft.id,
        expectedLockVersion: draft.draft.lockVersion
      })
      assert.equal(plan.checks.filter(c => c.blocking).length, 0, '对象发布前检查')
      const execution = await this.api('/nocode/design/execute', { planId: plan.id, reason: '表单功能验收夹具发布' })
      assert.equal(execution.state, 'SUCCEEDED')
    }
    const published = await this.api(`/nocode/application/object-version?id=${draft.draft.id}`)
    published.ids = Object.fromEntries(published.definition.fields.map(field => [field.code, field.id]))
    return published
  }

  grant(object, extra = {}) {
    const definition = object.definition
    return {
      objectId: object.objectId,
      actions: ['READ', 'CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT'],
      scope: 'ALL',
      readFields: definition.fields.map(field => field.id),
      writeFields: definition.fields
        .filter(field => !['FORMULA', 'SUMMARY', 'AUTO_NUMBER'].includes(field.type))
        .map(field => field.id),
      readDetails: [],
      writeDetails: [],
      readRelations: definition.relations.filter(r => r.kind === 'MANY_TO_MANY').map(r => r.id),
      writeRelations: definition.relations.filter(r => r.kind === 'MANY_TO_MANY').map(r => r.id),
      actionScopes: {},
      computeFields: [],
      ...extra
    }
  }

  async share(object, permission) {
    const current = await this.api(`/nocode/object-sharing/list?objectId=${object.objectId}`)
    return this.api('/nocode/object-sharing/save', {
      objectId: object.objectId,
      applicationId: this.app.application.id,
      expectedRevision: current.find(g => g.applicationId === this.app.application.id)?.revision || 0,
      permission,
      reason: '表单功能验收：专用应用授权'
    })
  }

  async authorize(members) {
    const current = await this.api(`/nocode/application/authorization?id=${this.app.application.id}`)
    return this.api('/nocode/application/authorization', {
      applicationId: this.app.application.id,
      expectedRevision: current.revision,
      members
    })
  }

  member(id, grants, kind = 'USER') {
    return { principalKind: kind, principalId: String(id), objects: grants }
  }
  scope(...conditions) {
    return { logic: 'AND', conditions, groups: [] }
  }
  condition(object, code, operator, value, valueSource = 'CONSTANT') {
    return { fieldId: object.ids[code], operator, value, valueSource }
  }
  values(object, values) {
    return Object.fromEntries(Object.entries(values).map(([key, value]) => [object.ids[key], value]))
  }
  query(object, extra = {}) {
    return {
      applicationId: this.app.application.id,
      objectId: object.objectId,
      pageNo: 1,
      pageSize: 100,
      equal: {},
      descending: false,
      ...extra
    }
  }
  async page(object, token, extra) {
    return this.api('/nocode/runtime/page', this.query(object, extra), token)
  }
  async get(object, row, token) {
    return this.api(
      `/nocode/runtime/get?applicationId=${this.app.application.id}&objectId=${object.objectId}&id=${row.id}`,
      undefined,
      token
    )
  }
  saveBody(object, values, row = null) {
    return {
      applicationId: this.app.application.id,
      objectId: object.objectId,
      id: row?.id || null,
      expectedRevision: row?.revision || null,
      values: this.values(object, values),
      details: {}
    }
  }
  async save(object, values, row, token) {
    return (await this.api('/nocode/runtime/save', this.saveBody(object, values, row), token)).record
  }
  async remove(object, row, token) {
    return this.api(
      '/nocode/runtime/delete',
      { applicationId: this.app.application.id, objectId: object.objectId, id: row.id, expectedRevision: row.revision },
      token
    )
  }
  async denied(path, body, token, method) {
    const result = await this.request(path, body, token, method)
    assert.notEqual(result.code, 0, `${path} 应拒绝越权请求`)
    assert.match(result.msg, /权限|授权|无权|范围|不可|不允许|访问/, '必须由业务权限边界拒绝，而非其他错误')
    return { code: result.code, message: result.msg }
  }

  async user(suffix, deptId) {
    const username = `${this.prefix}${suffix}`
    const existing = this.owned.users.find(user => user.username === username)
    const password = existing ? this.passwords[existing.id] : `Fa9${randomBytes(6).toString('hex')}`
    const id =
      existing?.id ||
      String(
        await this.api('/system/user/create', {
          username,
          nickname: `表单验收${suffix}`,
          password,
          deptId,
          remark: `${this.prefix} 功能验收专用，无管理角色`,
          sex: 0,
          postIds: []
        })
      )
    if (!existing) this.owned.users.push({ id, username })
    this.passwords[id] = password
    await this.persist()
    let result = await this.api('/system/auth/login', { username, password }, null)
    if (result.loginStatus === 'PASSWORD_CHANGE_REQUIRED') {
      const newPassword = `Fa9${randomBytes(6).toString('hex')}`
      result = await this.api(
        '/system/auth/change-required-password',
        {
          passwordChangeToken: result.passwordChangeToken,
          newPassword
        },
        null,
        'PUT'
      )
      this.passwords[id] = newPassword
    }
    assert.ok(result.accessToken, '临时成员应能正常登录')
    this.tokens[id] = result.accessToken
    return id
  }

  async export(object, token, file, extra = {}) {
    const response = await fetch(this.base + '/nocode/runtime/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(this.query(object, extra))
    })
    assert.ok(response.headers.get('content-type')?.includes('spreadsheet'), '导出应返回 Excel')
    const bytes = Buffer.from(await response.arrayBuffer())
    await writeFile(resolve(this.output, file), bytes)
    return bytes.length
  }
}
