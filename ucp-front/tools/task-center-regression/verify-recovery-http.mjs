import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 使用正式 HTTP 与当前开发配置；只停用本轮新建应用中的入口，保留夹具供浏览器复核。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.output = resolve(process.env.TASK_RECOVERY_OUTPUT || '.work/task-recovery', ac.prefix)
ac.owned.tasks = []
const tasks = (path, body) => ac.api(`/nocode/tasks/${path}`, body)
let object, applicationId, policy
const binding = () => ({ applicationId, formId: 'form', entryId: 'entry' })
const node = (title, extra = {}) => ({
  id: randomUUID(),
  parentId: null,
  title: `${ac.prefix} ${title}`,
  assigneeId: null,
  predecessorIds: [],
  ...extra
})
const config = (key, required = false, useEntry = true) => ({
  key,
  name: key,
  binding: { ...binding(), entryId: useEntry ? 'entry' : null },
  dataMode: 'INDEPENDENT',
  required,
  allowAll: false
})
const record = (name, row = null) => ({
  ...ac.saveBody(object, { name }, row),
  formId: 'form',
  requestKey: randomUUID()
})
async function create(title, entries) {
  const detail = await tasks('create', { task: node(title, { entries }), requestKey: randomUUID() })
  ac.owned.tasks.push({ id: detail.task.id, title: detail.task.title })
  await ac.persist()
  return detail.task.id
}
async function transition(id, action) {
  const detail = await tasks('detail', { id })
  return tasks('transition', {
    id,
    expectedRevision: detail.task.revision,
    action,
    note: '任务异常恢复回归',
    requestKey: randomUUID()
  })
}
async function enable(enabled) {
  policy = await ac.api('/nocode/task-entry/policy', {
    applicationId,
    entryId: 'entry',
    expectedRevision: policy?.revision || 0,
    enabled,
    members: []
  })
}
async function rejectCompletion(id) {
  const detail = await tasks('detail', { id })
  const result = await ac.request('/nocode/tasks/transition', {
    id,
    expectedRevision: detail.task.revision,
    action: 'COMPLETE',
    requestKey: randomUUID()
  })
  assert.notEqual(result.code, 0)
  assert.match(result.msg, /停用|权限|开放/)
  assert.equal((await tasks('detail', { id })).task.status, 'RUNNING')
}
const saveFeedback = (taskId, entryKey, name) =>
  tasks('entries/save', { taskId, entryKey, contributionId: null, record: record(name) })

try {
  await ac.login()
  object = await ac.object('task_recovery', '任务异常恢复回归', [ac.field('name', 'TEXT', '反馈内容')])
  const grant = ac.grant(object, { actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'] })
  const resource = (id, kind, config) => ({ id, code: id, name: `${ac.prefix} ${id}`, kind, config })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: `${ac.prefix}_recovery`,
    name: `${ac.prefix} 任务异常恢复验收`,
    description: '任务回执及可选反馈撤权回归专用夹具',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        resource('form', 'FORM', {
          objectId: object.objectId,
          nodes: [{ id: 'name', type: 'FIELD', fieldId: object.ids.name, children: [] }],
          detailIds: []
        }),
        resource('view', 'VIEW', {
          objectId: object.objectId,
          fieldIds: [object.ids.name],
          equal: {},
          pageSize: 10,
          formId: 'form'
        }),
        resource('entry', 'TASK_ENTRY', {
          objectId: object.objectId,
          formId: 'form',
          viewId: 'view',
          mode: 'LIST',
          category: '任务回归',
          sortOrder: 1,
          limits: [grant]
        })
      ]
    }
  })
  applicationId = ac.app.application.id
  ac.owned.applications.push({ id: applicationId, code: ac.app.application.code })
  await ac.persist()
  await ac.share(object, grant)
  await enable(true)
  await ac.api('/nocode/application/publish', {
    id: applicationId,
    expectedRevision: ac.app.application.revision,
    reason: '任务异常恢复回归夹具发布'
  })

  await ac.record('ENTRY 任务发起与办理回执恢复、原请求重试保持同一任务和记录', async () => {
    const business = record('首次业务提交')
    const command = { task: node('ENTRY 回执恢复', { binding: binding() }), business, requestKey: randomUUID() }
    const created = await tasks('create', command)
    const id = created.task.id
    ac.owned.tasks.push({ id, title: created.task.title })
    await ac.persist()
    // 不使用提交响应作为恢复结果：新请求仅凭原业务键重新获取已保存的任务及记录。
    const recovered = await tasks('form-runtime/create-receipt', { requestKey: business.requestKey })
    assert.equal(recovered.taskId, id)
    assert.equal(recovered.handling.outcome, 'EFFECTIVE')
    assert.equal(recovered.handling.result.record.id, created.task.business.recordId)
    assert.equal((await tasks('create', command)).task.id, id)
    await transition(id, 'START')
    const detail = await tasks('detail', { id })
    const form = await tasks('form', { id })
    const updated = record('办理业务更新', form.record.record)
    await tasks('business', { taskId: id, expectedRevision: detail.task.revision, record: updated })
    const receipt = await tasks('form-runtime/receipt', { taskId: id, requestKey: updated.requestKey })
    assert.equal(receipt.outcome, 'EFFECTIVE')
    assert.equal(receipt.result.record.id, recovered.handling.result.record.id)
    assert.equal(receipt.result.record.values[object.ids.name], '办理业务更新')
    const replay = await tasks('business', { taskId: id, expectedRevision: detail.task.revision, record: updated })
    assert.equal(replay.record.record.revision, receipt.result.record.revision)
    await enable(false)
    const denied = await ac.request('/nocode/tasks/form-runtime/receipt', {
      taskId: id,
      requestKey: updated.requestKey
    })
    assert.notEqual(denied.code, 0)
    assert.match(denied.msg, /停用|权限|开放/)
    await enable(true)
    return { taskId: id, recordId: receipt.result.record.id }
  })

  await ac.record('失权的可选空入口不阻断完成，必需入口和已有贡献仍保持校验', async () => {
    const empty = await create('可选空入口', [config('optional')])
    const required = await create('必需入口', [config('required', true)])
    const contributed = await create('已有贡献入口', [config('contributed')])
    for (const id of [empty, required, contributed]) await transition(id, 'START')
    await saveFeedback(contributed, 'contributed', '已有反馈不能因撤权跳过')
    await enable(false)
    assert.deepEqual(await tasks('entries/list', { id: empty }), [])
    assert.equal((await transition(empty, 'COMPLETE')).task.status, 'COMPLETED')
    await rejectCompletion(required)
    await rejectCompletion(contributed)
    await enable(true)
    return { completedTaskId: empty, requiredTaskId: required, contributedTaskId: contributed }
  })

  await ac.record('单个入口撤权后，其余完成材料仍可读取且无被撤权入口泄露', async () => {
    const id = await create('多入口完成材料', [config('revoked'), config('available', true, false)])
    await transition(id, 'START')
    await saveFeedback(id, 'revoked', '此入口随后撤权')
    await saveFeedback(id, 'available', '仍有权查看的材料')
    await transition(id, 'COMPLETE')
    assert.equal((await tasks('entries/materials', { id })).length, 2)
    await enable(false)
    const materials = await tasks('entries/materials', { id })
    assert.deepEqual(
      materials.map(item => item.entryKey),
      ['available']
    )
    assert.equal(materials[0].submissions[0].record.record.values[object.ids.name], '仍有权查看的材料')
    await enable(true)
    return { taskId: id, visibleEntryKeys: materials.map(item => item.entryKey) }
  })

  const uiTaskId = await create('浏览器完成可选失权任务', [config('optional')])
  await transition(uiTaskId, 'START')
  await enable(false)
  await ac.record('已准备浏览器复核夹具，仅可选空入口处于停用状态', async () => ({
    taskId: uiTaskId,
    url: `${process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'}/nocode-app/task-center/manage?taskId=${uiTaskId}`,
    status: (await tasks('detail', { id: uiTaskId })).task.status
  }))
  console.log(`PASS ${ac.checks.length} 组 HTTP 检查；证据目录：${ac.output}`)
} finally {
  await ac.persist()
}
