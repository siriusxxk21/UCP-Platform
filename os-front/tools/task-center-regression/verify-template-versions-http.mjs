import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 当前开发环境真实 HTTP 版本联测；只操作本轮唯一前缀模板、私有草稿及实例。 */
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.prefix = `模板版本联测-${Date.now().toString(36)}-${randomUUID().slice(0, 6)}`
ac.output = resolve('.work/task-template-versions', ac.prefix)
ac.owned.templates = []
ac.owned.tasks = []
ac.owned.drafts = []
const templateApi = (path, body) => ac.api(`/nocode/task-templates/${path}`, body)
const taskApi = (path, body) => ac.api(`/nocode/tasks/${path}`, body)
let head, first, draft, original, actor
const snapshots = new Map()
const node = (title, extra = {}) => ({
  id: randomUUID(),
  parentId: null,
  title: `${ac.prefix} ${title}`,
  // 底座 Long 可能输出为字符串，不能转 Number 丢失精度。
  assigneeId: actor,
  assignmentMode: 'ASSIGNED',
  candidateUserIds: [],
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'UNSCHEDULED', fixedStart: null, fixedEnd: null, offsetDays: 0, durationDays: 0 },
  predecessorIds: [],
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] },
  ...extra
})
const refresh = async () => {
  head = (await templateApi('list')).find(item => item.id === head.id)
  assert.ok(head, '本轮模板必须仍可查询')
  return head
}
const version = number => templateApi(`version?id=${head.id}${number === undefined ? '' : `&version=${number}`}`)
const publish = async setAsPrimary => {
  const body = { id: head.id, expectedRevision: head.revision }
  if (setAsPrimary !== undefined) body.setAsPrimary = setAsPrimary
  const snapshot = await templateApi('publish', body)
  snapshots.set(snapshot.version, snapshot)
  await refresh()
  return snapshot
}
const primary = async number => {
  const before = structuredClone(head)
  head = await templateApi('primary', { id: head.id, version: number, expectedRevision: head.revision })
  assert.equal(head.primaryVersion, number)
  assert.equal(head.publishedVersion, before.publishedVersion, '切主版本不能改变最新版本号')
  assert.deepEqual(
    [head.name, head.description, head.task, head.nodes],
    [before.name, before.description, before.task, before.nodes]
  )
  return head
}
const content = (snapshot, suffix, explicit = true) => ({
  templateId: head.id,
  ...(explicit ? { templateVersion: snapshot.version } : {}),
  task: { ...snapshot.task, title: `${ac.prefix} ${suffix}` },
  nodes: snapshot.nodes,
  requestKey: randomUUID()
})
const create = async (snapshot, suffix, explicit = true) => {
  const result = await taskApi('create', content(snapshot, suffix, explicit))
  ac.owned.tasks.push(result.task.id)
  await ac.persist()
  assert.equal(result.task.templateVersion, snapshot.version)
  assert.equal(result.nodes.filter(item => item.parentId).length, snapshot.nodes.length)
  return result
}
const edit = async title => {
  head = await templateApi('save', {
    id: head.id,
    expectedRevision: head.revision,
    name: head.name,
    description: title,
    task: { ...head.task, title: `${ac.prefix} ${title}` },
    nodes: head.nodes.map(item => ({ ...item, title: `${ac.prefix} ${title} 子项` }))
  })
}
const denied = async (path, body) => {
  const response = await ac.request(`/nocode/task-templates/${path}`, body)
  assert.notEqual(response.code, 0, '非法操作必须拒绝')
  assert.ok(response.http < 500, '应返回业务错误而非服务器异常')
  return response.msg
}

try {
  await ac.login()
  actor = (await ac.api('/system/auth/get-permission-info')).user.id
  await ac.record('首次发布自动成为主版本，保留 V1 快照及任务草稿', async () => {
    head = await templateApi('save', {
      name: ac.prefix,
      description: 'V1 基线',
      task: node('V1 总任务', { dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' } }),
      nodes: [node('V1 子项')]
    })
    ac.owned.templates.push(head.id)
    first = await publish(false)
    assert.equal(head.publishedVersion, 1)
    assert.equal(head.primaryVersion, 1)
    assert.equal((await version()).version, 1)
    original = await create(first, '历史实例 V1')
    draft = await taskApi('draft-save', { id: null, expectedRevision: null, content: content(first, '固定 V1 草稿') })
    ac.owned.drafts.push(draft.id)
    assert.equal(draft.content.templateVersion, 1)
    return { templateId: head.id, instanceId: original.task.id, draftId: draft.id }
  })
  await ac.record('发布 V2 保留 V1 为主版本，历史列表真实标注主版本', async () => {
    await edit('V2 配置')
    await publish(false)
    assert.equal(head.publishedVersion, 2)
    assert.equal(head.primaryVersion, 1)
    assert.deepEqual(await version(1), first)
    assert.equal((await version()).version, 1)
    const versions = await templateApi(`versions?id=${head.id}`)
    assert.deepEqual(
      versions.map(item => item.version),
      [2, 1]
    )
    assert.deepEqual(
      versions.filter(item => item.primary).map(item => item.version),
      [1]
    )
    assert.ok(versions.every(item => item.nodeCount === 1 && item.publishedAt))
    return { latest: head.publishedVersion, primary: head.primaryVersion }
  })
  await ac.record('切主版本不覆盖模板草稿；默认使用 V2，显式仍可使用 V1', async () => {
    await edit('未发布的新草稿')
    await primary(2)
    assert.equal((await version()).version, 2)
    await create(snapshots.get(2), '默认主版本 V2', false)
    await create(first, '显式历史 V1')
    assert.match(head.task.title, /未发布的新草稿/)
    return { primary: head.primaryVersion, draftTitle: head.task.title }
  })
  await ac.record('已存任务草稿恢复与发布仍固定 V1，不随主版本升级', async () => {
    const restored = await taskApi('draft-get', { id: draft.id })
    assert.deepEqual(restored.content, draft.content)
    const result = await taskApi('draft-publish', {
      id: draft.id,
      expectedRevision: restored.revision,
      requestKey: randomUUID()
    })
    ac.owned.tasks.push(result.task.id)
    assert.equal(result.task.templateVersion, 1)
    return { draftId: draft.id, taskId: result.task.id, version: result.task.templateVersion }
  })
  await ac.record('切回旧主版本后继续发布，最新序号递增且不覆盖旧快照', async () => {
    await primary(1)
    const third = await publish(false)
    assert.equal(third.version, 3)
    assert.equal(head.primaryVersion, 1)
    assert.equal(head.publishedVersion, 3)
    await create(first, '切回后的默认 V1', false)
    await primary(3)
    await create(third, '切换后的默认 V3', false)
    return { latest: head.publishedVersion, primary: head.primaryVersion }
  })
  await ac.record('无效版本与过期修订拒绝，不更改模板状态', async () => {
    const before = structuredClone(head)
    const missing = await denied('primary', { id: head.id, version: 999, expectedRevision: head.revision })
    const stale = await denied('primary', { id: head.id, version: 1, expectedRevision: head.revision - 1 })
    const staleSame = await denied('primary', {
      id: head.id,
      version: head.primaryVersion,
      expectedRevision: head.revision - 1
    })
    const publishStale = await denied('publish', {
      id: head.id,
      expectedRevision: head.revision - 1,
      setAsPrimary: true
    })
    await refresh()
    assert.deepEqual(head, before)
    return { missing, stale, staleSame, publishStale }
  })
  await ac.record('显式同时设主版本与旧协议发布均保持兼容', async () => {
    await edit('V4 配置')
    await publish(true)
    assert.equal(head.primaryVersion, 4)
    await publish(undefined)
    assert.equal(head.publishedVersion, 5)
    assert.equal(head.primaryVersion, 5)
    return { latest: head.publishedVersion, primary: head.primaryVersion }
  })
  await ac.record('全部历史快照、原任务配置与事件不变，实例可按来源版本筛选', async () => {
    for (const [number, snapshot] of snapshots) assert.deepEqual(await version(number), snapshot)
    assert.deepEqual(await taskApi('detail', { id: original.task.id }), original)
    for (const number of [1, 2, 3]) {
      const page = await templateApi('instances', { templateId: head.id, version: number, pageNo: 1, pageSize: 100 })
      assert.ok(page.list.length > 0)
      assert.ok(page.list.every(item => item.templateVersion === number))
    }
    return { versions: [...snapshots.keys()], instanceIds: ac.owned.tasks }
  })
  console.log(JSON.stringify({ passed: ac.checks.length, output: ac.output, templateId: head.id }))
} catch (error) {
  // 不输出请求头、会话或登录返回，只保留业务断言。
  console.error(error.message)
  process.exitCode = 1
} finally {
  await ac.persist()
}
