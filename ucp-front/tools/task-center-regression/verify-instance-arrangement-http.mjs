import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 复用正式HTTP装配，只新建当次可识别夹具，不修改用户任务或模板。 */
const ac = new FormAcceptance()
ac.prefix = `实例编排联测-${Date.now().toString(36)}`
ac.output = resolve('.work/task-instance-arrangement', ac.prefix)
ac.owned.tasks = []
const api = (path, body) => ac.api(`/nocode/tasks/${path}`, body)
let actor, detail
const node = (title, parentId = null) => ({
  id: randomUUID(),
  title: `${ac.prefix} ${title}`,
  description: '',
  parentId,
  assigneeId: actor,
  assignmentMode: 'ASSIGNED',
  candidateUserIds: [],
  acceptorId: null,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'UNSCHEDULED', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 1 },
  predecessorIds: [],
  binding: null,
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] }
})
const input = row =>
  Object.fromEntries(
    Object.keys(node(''))
      .concat('entries', 'dataPolicy')
      .filter(key => key in row)
      .map(key => [key, row[key]])
  )
const command = nodes => ({
  rootId: detail.task.rootId,
  expectedRevision: detail.task.instanceRevision,
  plannedStart: detail.task.plannedStart,
  nodes,
  reason: '本轮实例编排联合验证'
})
const read = async () => (detail = await api('detail', { id: detail.task.rootId }))
const transition = async id => {
  const target = await api('detail', { id })
  await api('transition', { id, action: 'START', expectedRevision: target.task.revision, requestKey: randomUUID() })
  await read()
}
try {
  await ac.login()
  actor = (await ac.api('/system/auth/get-permission-info')).user.id
  await ac.record('创建独立联测实例：总任务及两个步骤', async () => {
    detail = await api('create', { task: node('总任务'), nodes: [node('A'), node('B')], requestKey: randomUUID() })
    ac.owned.tasks = detail.nodes.map(row => row.id)
    return { rootId: detail.task.rootId, count: detail.nodes.length }
  })
  const a = detail.nodes.find(row => row.title.endsWith(' A')).id
  const b = detail.nodes.find(row => row.title.endsWith(' B')).id
  await ac.record('未开始节点预览、保存及回读', async () => {
    const nodes = detail.nodes.map(input).map(row => (row.id === b ? { ...row, description: '已保存的实例说明' } : row))
    assert.ok((await api('adjust-preview', command(nodes))).changedIds.includes(b))
    await api('adjust', command(nodes))
    await read()
    assert.equal(detail.nodes.find(row => row.id === b).description, '已保存的实例说明')
  })
  await ac.record('开始A后仍能调整尚未开始B', async () => {
    await transition(detail.task.rootId)
    await transition(a)
    const nodes = detail.nodes.map(input).map(row => (row.id === b ? { ...row, priority: 'HIGH' } : row))
    await api('adjust-preview', command(nodes))
    await api('adjust', command(nodes))
    await read()
    assert.equal(detail.nodes.find(row => row.id === b).priority, 'HIGH')
  })
  await ac.record('运行中的A不能改名或删除，失败后原任务仍在', async () => {
    for (const nodes of [
      detail.nodes.map(input).map(row => (row.id === a ? { ...row, title: '不应生效' } : row)),
      detail.nodes.filter(row => row.id !== a).map(input)
    ]) {
      const result = await ac.request('/nocode/tasks/adjust-preview', command(nodes))
      assert.notEqual(result.code, 0)
      assert.ok(result.http < 500)
    }
    await read()
    assert.equal(detail.nodes.find(row => row.id === a).status, 'RUNNING')
  })
  await ac.record('本人任务走个人拆分接口，新增子项在整组管理详情可见', async () => {
    const created = await api('split', { parentId: a, task: node('个人拆分子项'), requestKey: randomUUID() })
    ac.owned.tasks.push(created.task.id)
    await read()
    const child = detail.nodes.find(row => row.id === created.task.id)
    assert.equal(child.parentId, a)
    assert.equal(String(child.assigneeId), String(actor))
    const denied = await ac.request('/nocode/tasks/split', {
      parentId: a,
      task: { ...node('非法依赖'), predecessorIds: [b] },
      requestKey: randomUUID()
    })
    assert.notEqual(denied.code, 0)
    return { childId: child.id }
  })
  console.log(
    JSON.stringify({
      prefix: ac.prefix,
      rootId: detail.task.rootId,
      passed: ac.checks.filter(row => row.passed).length,
      total: ac.checks.length,
      output: ac.output
    })
  )
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
} finally {
  await ac.persist()
}
