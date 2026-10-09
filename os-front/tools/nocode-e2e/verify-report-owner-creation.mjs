/** A01/A22：普通拥有者正式制作三对象数据集；仅操作随机前缀夹具，真实角色撤权后恢复并正式清理。 */
import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { execFile } from 'node:child_process'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { promisify } from 'node:util'

const base = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const originalManifest = await readFile('.work/report-demo/current.json', 'utf8')
const sourceManifest = JSON.parse(originalManifest)
assert.equal(String(sourceManifest.dashboardId), '5')
assert.equal(String(sourceManifest.datasetId), '981')
assert.deepEqual(sourceManifest.objectIds.map(String), ['5915', '5914', '5913'])
assert.match(sourceManifest.prefix, /^test_b1_[a-f0-9]+$/)
const prefix = 'rptOwner_' + randomBytes(6).toString('hex')
const username = prefix.replaceAll('_', '')
const output = resolve('.work/report-demo/owner-creation', prefix)
await mkdir(output, { recursive: true })
const lock = resolve(output, '.running')
await mkdir(lock)
const fixture = { prefix, createdAt: new Date().toISOString(), retained: false, sourceDatasetId: '981' }
const checks = [],
  errors = [],
  cleanupErrors = [],
  denials = [],
  publishBoundaries = []
const boundaries = ['本次只新增随机普通账号、两个角色、数据集与单METRIC看板；未创建应用或写入业务记录。']
const exports = []
const makerPermissions = [
  'nocode:report:query',
  'nocode:report:create',
  'nocode:report:update',
  'nocode:report:publish',
  'nocode:report:manage',
  'nocode:report:authorize',
  'nocode:object:query'
]
let adminToken,
  ownerToken,
  stage = '初始化',
  sourceBefore,
  sourceAfter,
  dataset,
  fixedQuery,
  boardQuery,
  dataRoleRemoved = false
async function checkpoint() {
  await writeFile(resolve(output, 'fixture.json'), JSON.stringify({ ...fixture, stage }, null, 2))
}
async function step(value) {
  stage = value
  await checkpoint()
  console.log('普通拥有者制作: ' + value)
}
async function request(path, body, session = adminToken, method = body === undefined ? 'GET' : 'POST') {
  return fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(session ? { Authorization: 'Bearer ' + session } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000)
  })
}
async function api(path, body, session = adminToken, method) {
  const result = await (await request(path, body, session, method)).json()
  assert.equal(result.code, 0, path + ': ' + result.code + ' ' + result.msg)
  return result.data
}
async function denied(label, path, body, session = ownerToken) {
  const response = await request(path, body, session)
  assert.ok(response.headers.get('content-type')?.includes('json'), label + ': 拒绝不得交付文件')
  const result = await response.json()
  assert.equal(result.code, 403, label + ': 应由真实业务数据授权拒绝，实际 ' + result.code + ' ' + result.msg)
  assert.match(result.msg || '', /权限|授权|无权|共享|上限/)
  denials.push({ label, path, http: response.status, code: result.code, message: result.msg })
}
async function sourceState() {
  const paths = [
    '/nocode/report/dashboard/get?id=5',
    '/nocode/report/dashboard/published?id=5',
    '/nocode/report/dashboard/releases?id=5&pageNo=1&pageSize=100',
    '/nocode/report/dashboard/resource-policy?id=5',
    '/nocode/report/dataset/get?id=981',
    '/nocode/report/dataset/releases?id=981&pageNo=1&pageSize=100',
    '/nocode/report/dataset/data-policy?id=981',
    '/nocode/report/dataset/resource-policy?id=981',
    '/nocode/report/dataset/ceilings?id=981'
  ]
  const state = {}
  for (const path of paths) state[path] = await api(path)
  return { state, manifest: await readFile('.work/report-demo/current.json', 'utf8') }
}
const references = source => {
  const values = [source.root, ...source.relations.map(relation => relation.target)]
  const unique = new Map(values.map(ref => [ref.objectId, ref]))
  assert.equal(unique.size, 3)
  assert.deepEqual([...unique.keys()], sourceManifest.objectIds)
  assert.ok(values.every(ref => ref.versionNo > 0 && typeof ref.checksum === 'string' && ref.checksum.length === 64))
  return [...unique.values()]
}
const dataGrants = original =>
  original.map(item => {
    const grant = structuredClone(item)
    assert.ok(grant.actions.includes('READ') && grant.actions.includes('EXPORT'))
    grant.actions = ['READ', 'EXPORT']
    grant.actionScopes = Object.fromEntries(
      Object.entries(grant.actionScopes || {}).filter(([action]) => grant.actions.includes(action))
    )
    return grant
  })
async function requireRoles(expected) {
  const actual = await api('/system/permission/list-user-roles?userId=' + fixture.userId)
  assert.deepEqual(new Set(actual.map(String)), new Set(expected.map(String)))
}
async function requireMakerIdentity() {
  const identity = await api('/system/auth/get-permission-info', undefined, ownerToken)
  assert.equal(String(identity.user.id), String(fixture.userId))
  assert.deepEqual(new Set(identity.permissions.filter(Boolean)), new Set(makerPermissions))
  assert.ok(!identity.roles.includes('super_admin') && !identity.roles.includes('tenant_admin'))
  return identity
}
async function query(label, body, expected) {
  const result = await api('/nocode/report/dataset/query', body, ownerToken)
  assert.deepEqual(result.totals, expected.totals, label + ': 聚合口径须与原精确授权一致')
  assert.equal(result.recordCount, expected.recordCount)
  assert.equal(result.canExport, false, '直接数据集查询不提供文件导出能力')
  await writeFile(resolve(output, label + '.json'), JSON.stringify(result, null, 2))
  return result
}
async function boardResult(label, expected) {
  const result = await api('/nocode/report/dashboard/query', boardQuery, ownerToken)
  assert.deepEqual(result.totals, expected.totals)
  assert.equal(result.recordCount, expected.recordCount)
  assert.equal(result.canExport, true)
  const response = await request('/nocode/report/dashboard/export', boardQuery, ownerToken)
  assert.equal(response.status, 200)
  assert.ok(response.headers.get('content-type')?.includes('spreadsheet'), '真实导出必须交付XLSX')
  const bytes = Buffer.from(await response.arrayBuffer())
  assert.ok(bytes.length > 100 && bytes.subarray(0, 2).toString() === 'PK')
  const path = resolve(output, label + '.xlsx')
  await writeFile(path, bytes)
  const xml = (await promisify(execFile)('/usr/bin/unzip', ['-p', path, 'xl/worksheets/sheet1.xml'])).stdout
  assert.ok(xml.includes('<t>19.25</t>'), '真实XLSX须含精确合计19.25')
  assert.ok(!xml.includes('<f>'), '导出值不得转成Excel公式')
  exports.push({ label, path, bytes: bytes.length, total: 19.25, recordCount: result.recordCount })
  await writeFile(resolve(output, label + '-dashboard.json'), JSON.stringify(result, null, 2))
}
async function publicationBoundary(label, hasCeilings) {
  const current = await api('/nocode/report/dataset/get?id=' + fixture.datasetId, undefined, ownerToken)
  const response = await request(
    '/nocode/report/dataset/publish',
    {
      id: current.id,
      expectedRevision: current.revision,
      requestId: randomUUID(),
      reason: prefix + ' 发布边界：' + label
    },
    ownerToken
  )
  const result = await response.json()
  const after = await api('/nocode/report/dataset/get?id=' + fixture.datasetId, undefined, ownerToken)
  publishBoundaries.push({
    label,
    http: response.status,
    code: result.code,
    message: result.msg || '',
    publishedVersion: after.publishedVersion
  })
  if (!hasCeilings) {
    assert.equal(result.code, 1050000001, '缺少对象上限按现有发布契约返回来源校验拒绝')
    assert.match(result.msg || '', /上限|授权|字段|关联/)
    assert.equal(after.publishedVersion, null)
  } else {
    assert.equal(result.code, 0, '有完整上限时资源发布不以成员数据读取权为前提')
    assert.equal(after.publishedVersion, result.data.versionNo)
    boundaries.push(
      '真实发布边界：无对象上限返回1050000001且未发布；只有完整上限而无成员数据权允许资源发布，取数仍403。'
    )
  }
  return result.data
}
async function makeBoard(metricIds) {
  const ownBoard = await api(
    '/nocode/report/dashboard/save',
    {
      id: null,
      expectedRevision: 0,
      content: {
        schemaVersion: 1,
        name: prefix + ' 普通拥有者权限看板',
        description: prefix + ' A22仅自有固定数据集单指标与真实XLSX授权验收',
        filters: [],
        charts: [
          {
            id: 'owner_amount',
            title: '自有精确金额',
            display: 'METRIC',
            dataset: fixture.datasetReference,
            dimensions: [],
            metricIds,
            x: 0,
            y: 0,
            w: 12,
            h: 4,
            drillDimensions: [],
            links: []
          }
        ]
      }
    },
    ownerToken
  )
  fixture.dashboardId = ownBoard.id
  fixture.boardDatasetReference = structuredClone(fixture.datasetReference)
  await checkpoint()
  assert.notEqual(ownBoard.id, '5')
  assert.equal(String(ownBoard.ownerId), fixture.userId)
  const boardRelease = await api(
    '/nocode/report/dashboard/publish',
    { id: ownBoard.id, expectedRevision: ownBoard.revision, requestId: randomUUID() },
    ownerToken
  )
  fixture.dashboardReference = { id: ownBoard.id, versionNo: boardRelease.versionNo, checksum: boardRelease.checksum }
  boardQuery = { ...fixture.dashboardReference, preview: false, chartId: 'owner_amount' }
  await checkpoint()
}
async function cleanup(label, work) {
  try {
    await work()
  } catch (error) {
    cleanupErrors.push(label + ': ' + error.message)
  }
}
try {
  await step('管理员正常登录与原5/981完整只读快照')
  const env = Object.fromEntries(
    (await readFile('.env.test', 'utf8'))
      .split(/\r?\n/)
      .filter(line => /^E2E_(USERNAME|PASSWORD)=/.test(line))
      .map(line => {
        const index = line.indexOf('=')
        return [
          line.slice(0, index),
          line
            .slice(index + 1)
            .trim()
            .replace(/^(['"])(.*)\1$/, '$2')
        ]
      })
  )
  adminToken = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD }, null))
    .accessToken
  delete env.E2E_USERNAME
  delete env.E2E_PASSWORD
  assert.ok(adminToken)
  sourceBefore = await sourceState()
  assert.equal(sourceBefore.manifest, originalManifest)
  await writeFile(resolve(output, 'source-before.json'), JSON.stringify(sourceBefore, null, 2))
  const seed = sourceBefore.state['/nocode/report/dataset/get?id=981']
  const board = sourceBefore.state['/nocode/report/dashboard/published?id=5']
  const metric = board.content.charts.find(chart => chart.display === 'METRIC')
  assert.equal(metric.dataset.id, '981')
  const seedRelease = sourceBefore.state['/nocode/report/dataset/releases?id=981&pageNo=1&pageSize=100'].list.find(
    release => release.versionNo === metric.dataset.versionNo && release.checksum === metric.dataset.checksum
  )
  assert.ok(seedRelease, '须使用原看板明确固定的数据集版本')
  const content = structuredClone(seedRelease.definition)
  const sourceReferences = references(content.source)
  const seedPolicy = sourceBefore.state['/nocode/report/dataset/data-policy?id=981']
  const seedOwner = seedPolicy.members.find(
    member => member.principalKind === 'USER' && String(member.principalId) === String(seed.ownerId)
  )
  assert.ok(seedOwner?.objects.length === 3, '原拥有者必须已有精确三对象数据权限')
  assert.deepEqual(new Set(seedOwner.objects.map(grant => grant.objectId)), new Set(sourceManifest.objectIds))
  const grants = dataGrants(seedOwner.objects)
  fixture.sourceReferences = sourceReferences
  fixture.sourceReference = metric.dataset
  fixture.grants = grants
  const expected = await api('/nocode/report/dataset/query', {
    datasetId: '981',
    preview: false,
    versionNo: seedRelease.versionNo,
    checksum: seedRelease.checksum,
    dimensions: [],
    metricIds: metric.metricIds,
    limit: 100
  })
  assert.equal(Number(expected.totals[metric.metricIds[0]]), 19.25)
  fixture.expected = { totals: expected.totals, recordCount: expected.recordCount }
  await checkpoint()

  await step('随机普通账号与精确制作角色、无菜单数据角色')
  for (const [key, suffix] of [
    ['makerRoleId', '_maker'],
    ['dataRoleId', '_data']
  ]) {
    fixture[key] = String(
      await api('/system/role/create', {
        name: prefix + suffix,
        code: prefix + suffix,
        sort: 999,
        status: 0,
        remark: prefix + ' A01/A22自有临时角色'
      })
    )
    await checkpoint()
  }
  const menus = await api('/system/menu/list')
  for (const permission of makerPermissions)
    assert.ok(
      menus.some(menu => menu.permission === permission),
      '缺少实际权限菜单定义：' + permission
    )
  const selectedMenus = new Set(menus.filter(menu => makerPermissions.includes(menu.permission)).map(menu => menu.id))
  const menuById = new Map(menus.map(menu => [String(menu.id), menu]))
  for (const id of [...selectedMenus]) {
    let current = menuById.get(String(id))
    while (current?.parentId && String(current.parentId) !== '0') {
      current = menuById.get(String(current.parentId))
      assert.ok(current, '实际权限菜单祖先必须存在')
      assert.ok(
        !current.permission || makerPermissions.includes(current.permission),
        '菜单祖先不得放大普通角色全局权限'
      )
      selectedMenus.add(current.id)
    }
  }
  const menuIds = [...selectedMenus]
  await api('/system/permission/assign-role-menu', { roleId: fixture.makerRoleId, menuIds })
  await api('/system/permission/assign-role-menu', { roleId: fixture.dataRoleId, menuIds: [] })
  assert.deepEqual(await api('/system/permission/list-role-menus?roleId=' + fixture.dataRoleId), [])
  let password = 'T9' + randomBytes(7).toString('hex')
  fixture.userId = String(
    await api('/system/user/create', {
      username,
      nickname: prefix,
      password,
      remark: prefix + ' A01/A22自有普通拥有者'
    })
  )
  await checkpoint()
  await api('/system/permission/assign-user-role', {
    userId: fixture.userId,
    roleIds: [fixture.makerRoleId, fixture.dataRoleId]
  })
  await requireRoles([fixture.makerRoleId, fixture.dataRoleId])
  let login = await api('/system/auth/login', { username, password }, null)
  password = undefined
  if (login.loginStatus === 'PASSWORD_CHANGE_REQUIRED')
    login = await api(
      '/system/auth/change-required-password',
      { passwordChangeToken: login.passwordChangeToken, newPassword: 'K8' + randomBytes(7).toString('hex') },
      null,
      'PUT'
    )
  ownerToken = login.accessToken
  login = undefined
  assert.ok(ownerToken)
  await requireMakerIdentity()
  fixture.makerPermissions = makerPermissions
  checks.push('真实普通账号仅七个报表制作/管理/授权及对象元数据权限，无object:share；数据角色没有菜单或全局权限')

  await step('普通拥有者选择精确三对象固定来源并正式新建数据集')
  for (const ref of sourceReferences) {
    const candidate = await api(
      '/nocode/report/dataset/source-object?id=' + ref.objectId + '&versionNo=' + ref.versionNo,
      undefined,
      ownerToken
    )
    assert.equal(candidate.reference.objectId, ref.objectId)
    assert.equal(candidate.reference.versionNo, ref.versionNo)
    assert.equal(candidate.reference.checksum, ref.checksum)
  }
  dataset = await api(
    '/nocode/report/dataset/save',
    {
      id: null,
      expectedRevision: 0,
      name: prefix + ' 普通拥有者数据集',
      description: prefix + ' A01/A22验收；只引用原精确三对象固定版本，不写业务记录。',
      source: content.source,
      analysis: content.analysis
    },
    ownerToken
  )
  fixture.datasetId = dataset.id
  assert.notEqual(dataset.id, '981')
  await checkpoint()
  assert.equal(String(dataset.ownerId), fixture.userId)
  assert.equal(dataset.draft.name, prefix + ' 普通拥有者数据集')
  assert.deepEqual(dataset.draft.source, content.source)
  assert.deepEqual(dataset.draft.analysis, content.analysis)
  assert.deepEqual(await api('/nocode/report/dataset/ceilings?id=' + dataset.id), [])
  assert.deepEqual(
    (await api('/nocode/report/dataset/data-policy?id=' + dataset.id, undefined, ownerToken)).members,
    []
  )
  const previewQuery = { datasetId: dataset.id, preview: true, dimensions: [], metricIds: metric.metricIds, limit: 100 }
  await denied('无对象上限与数据成员时预览拒绝', '/nocode/report/dataset/query', previewQuery)
  await publicationBoundary('无上限且无数据成员', false)
  checks.push('普通拥有者正式新建精确三对象固定来源/指标，服务端owner正确；资源所有权未产生上限或成员数据权，预览403')

  await step('只为新数据集授予精确三对象上限与ROLE读取/导出策略')
  for (const permission of grants) {
    const ceiling = await api('/nocode/report/dataset/ceiling', {
      datasetId: dataset.id,
      objectId: permission.objectId,
      expectedRevision: 0,
      permission,
      reason: prefix + ' 自有三对象精确上限'
    })
    assert.deepEqual(ceiling.permission, permission)
  }
  await denied('已有三对象上限仍无数据成员时预览拒绝', '/nocode/report/dataset/query', previewQuery)
  const firstRelease = await publicationBoundary('上限齐全但无数据成员', true)
  fixture.datasetReference = { id: dataset.id, versionNo: firstRelease.versionNo, checksum: firstRelease.checksum }
  const noMemberFixed = {
    datasetId: dataset.id,
    preview: false,
    versionNo: firstRelease.versionNo,
    checksum: firstRelease.checksum,
    dimensions: [],
    metricIds: metric.metricIds,
    limit: 100
  }
  await denied('上限齐全且已发布但无数据成员时固定查询拒绝', '/nocode/report/dataset/query', noMemberFixed)
  await makeBoard(metric.metricIds)
  await denied('资源发布不授数据成员时固定看板查询拒绝', '/nocode/report/dashboard/query', boardQuery)
  await denied('资源发布不授数据成员时固定看板XLSX拒绝', '/nocode/report/dashboard/export', boardQuery)
  const policy = await api('/nocode/report/dataset/data-policy?id=' + dataset.id, undefined, ownerToken)
  const savedPolicy = await api(
    '/nocode/report/dataset/data-policy',
    {
      datasetId: dataset.id,
      expectedRevision: policy.revision,
      members: [{ principalKind: 'ROLE', principalId: fixture.dataRoleId, objects: grants }],
      reason: prefix + ' 无菜单角色精确READ/EXPORT数据成员'
    },
    ownerToken
  )
  assert.deepEqual(savedPolicy.members, [{ principalKind: 'ROLE', principalId: fixture.dataRoleId, objects: grants }])
  await query('owner-preview', previewQuery, expected)
  const current = await api('/nocode/report/dataset/get?id=' + dataset.id, undefined, ownerToken)
  const release = await api(
    '/nocode/report/dataset/publish',
    {
      id: dataset.id,
      expectedRevision: current.revision,
      requestId: randomUUID(),
      reason: prefix + ' 普通拥有者正式发布'
    },
    ownerToken
  )
  assert.equal(release.datasetId, dataset.id)
  assert.equal(release.versionNo, current.publishedVersion + 1)
  fixture.datasetReference = { id: dataset.id, versionNo: release.versionNo, checksum: release.checksum }
  fixedQuery = {
    datasetId: dataset.id,
    preview: false,
    versionNo: release.versionNo,
    checksum: release.checksum,
    dimensions: [],
    metricIds: metric.metricIds,
    limit: 100
  }
  await checkpoint()
  await query('owner-fixed', fixedQuery, expected)
  await boardResult('owner-fixed', expected)
  checks.push(
    '三对象上限不自动授予成员数据权；显式ROLE精确READ/EXPORT后，普通拥有者预览、数据集/自有单指标看板发布、固定查询与真实XLSX均为19.25'
  )

  await step('保留制作角色，仅正式撤销数据角色，再原样恢复')
  await api('/system/permission/assign-user-role', { userId: fixture.userId, roleIds: [fixture.makerRoleId] })
  dataRoleRemoved = true
  await requireRoles([fixture.makerRoleId])
  await requireMakerIdentity()
  const ownerAfterRevoke = await api('/nocode/report/dataset/get?id=' + dataset.id, undefined, ownerToken)
  assert.equal(String(ownerAfterRevoke.ownerId), fixture.userId)
  assert.equal(ownerAfterRevoke.publishedVersion, release.versionNo)
  assert.equal(ownerAfterRevoke.checksum, release.checksum)
  assert.deepEqual(
    (await api('/nocode/report/dataset/data-policy?id=' + dataset.id, undefined, ownerToken)).members,
    savedPolicy.members
  )
  await denied('同一普通会话数据ROLE撤销后固定查询拒绝', '/nocode/report/dataset/query', fixedQuery)
  await denied('同一普通会话数据ROLE撤销后草稿预览拒绝', '/nocode/report/dataset/query', previewQuery)
  await denied('同一普通会话数据ROLE撤销后固定看板查询拒绝', '/nocode/report/dashboard/query', boardQuery)
  await denied('同一普通会话数据ROLE撤销后固定看板XLSX拒绝', '/nocode/report/dashboard/export', boardQuery)
  await api('/system/permission/assign-user-role', {
    userId: fixture.userId,
    roleIds: [fixture.makerRoleId, fixture.dataRoleId]
  })
  dataRoleRemoved = false
  await requireRoles([fixture.makerRoleId, fixture.dataRoleId])
  await requireMakerIdentity()
  await query('owner-role-restored', fixedQuery, expected)
  await boardResult('owner-role-restored', expected)
  checks.push(
    '同一普通会话保留制作角色、owner、发布pin与策略，仅移除无菜单数据ROLE后数据集/看板固定查询、预览及XLSX均403；恢复后查询与真实XLSX均19.25'
  )
} catch (error) {
  errors.push(stage + ': ' + error.message)
} finally {
  await step('finally按ID/owner/名称守卫正式清理全部自有夹具')
  if (adminToken && dataRoleRemoved)
    await cleanup('恢复自有数据角色', async () => {
      await api('/system/permission/assign-user-role', {
        userId: fixture.userId,
        roleIds: [fixture.makerRoleId, fixture.dataRoleId]
      })
      dataRoleRemoved = false
    })
  if (ownerToken && fixture.dashboardId)
    await cleanup('先删除自有看板', async () => {
      const own = await api('/nocode/report/dashboard/get?id=' + fixture.dashboardId, undefined, ownerToken)
      assert.equal(own.id, fixture.dashboardId)
      assert.notEqual(own.id, '5')
      assert.equal(String(own.ownerId), fixture.userId)
      assert.equal(own.draft.name, prefix + ' 普通拥有者权限看板')
      const preview = await api('/nocode/report/dashboard/delete-preview?id=' + own.id, undefined, ownerToken)
      assert.equal(preview.id, own.id)
      assert.equal(preview.canDelete, true)
      assert.equal(preview.referenceCount, 0)
      const deleted = await api(
        '/nocode/report/dashboard/delete',
        {
          id: own.id,
          expectedRevision: preview.revision,
          reason: prefix + ' A01/A22验收结束仅删除自有看板'
        },
        ownerToken
      )
      assert.equal(deleted.deleted, true)
      fixture.dashboardDeleted = true
    })
  if (ownerToken && fixture.datasetId && (!fixture.dashboardId || fixture.dashboardDeleted))
    await cleanup('删除自有数据集', async () => {
      const own = await api('/nocode/report/dataset/get?id=' + fixture.datasetId, undefined, ownerToken)
      assert.equal(own.id, fixture.datasetId)
      assert.notEqual(own.id, '981')
      assert.equal(String(own.ownerId), fixture.userId)
      assert.equal(own.draft.name, prefix + ' 普通拥有者数据集')
      const preview = await api('/nocode/report/dataset/delete-preview?id=' + own.id, undefined, ownerToken)
      assert.equal(preview.id, own.id)
      assert.equal(preview.canDelete, true)
      assert.equal(preview.referenceCount, 0)
      const deleted = await api(
        '/nocode/report/dataset/delete',
        {
          id: own.id,
          expectedRevision: preview.revision,
          reason: prefix + ' A01/A22验收结束仅删除自有数据集'
        },
        ownerToken
      )
      assert.equal(deleted.deleted, true)
      fixture.datasetDeleted = true
    })
  if (ownerToken)
    await cleanup('注销普通会话', async () => {
      await api('/system/auth/logout', {}, ownerToken)
      ownerToken = undefined
      fixture.ownerLoggedOut = true
    })
  // 数据集删除失败时保留唯一拥有者和数据角色，以便按输出清单恢复，避免留下失去拥有者的资源。
  if (adminToken && (!fixture.datasetId || fixture.datasetDeleted)) {
    if (fixture.userId)
      await cleanup('删除自有账号', async () => {
        const own = await api('/system/user/get?id=' + fixture.userId)
        assert.equal(String(own.id), fixture.userId)
        assert.equal(own.username, username)
        await api('/system/user/delete?id=' + fixture.userId, undefined, adminToken, 'DELETE')
        fixture.userDeleted = true
      })
    for (const [key, suffix] of [
      ['makerRoleId', '_maker'],
      ['dataRoleId', '_data']
    ])
      if (fixture[key])
        await cleanup('删除自有角色' + suffix, async () => {
          const own = await api('/system/role/get?id=' + fixture[key])
          assert.equal(String(own.id), fixture[key])
          assert.equal(own.code, prefix + suffix)
          assert.equal(own.name, prefix + suffix)
          await api('/system/role/delete?id=' + fixture[key], undefined, adminToken, 'DELETE')
          fixture[key + 'Deleted'] = true
        })
  } else if (fixture.datasetId && !fixture.datasetDeleted) {
    fixture.retained = true
    cleanupErrors.push('自有数据集未删除，保留其唯一拥有者和两个角色等待按manifest恢复；未处理其他资源')
  }
  if (adminToken && sourceBefore)
    await cleanup('原5/981与current.json完整不变复核', async () => {
      sourceAfter = await sourceState()
      await writeFile(resolve(output, 'source-after.json'), JSON.stringify(sourceAfter, null, 2))
      assert.deepEqual(sourceAfter, sourceBefore)
      fixture.sourceUnchanged = true
      checks.push(
        '自有看板→数据集正式delete且普通会话/账号/角色清理；原5/981草稿、发布版本、政策、上限与current.json完整前后相等'
      )
    })
  if (adminToken)
    await cleanup('注销管理员验收会话', async () => {
      await api('/system/auth/logout', {})
    })
  adminToken = undefined
  ownerToken = undefined
  fixture.finishedAt = new Date().toISOString()
  await checkpoint()
  await rm(lock, { recursive: true, force: true })
  const cleaned =
    fixture.dashboardDeleted &&
    fixture.datasetDeleted &&
    fixture.userDeleted &&
    fixture.makerRoleIdDeleted &&
    fixture.dataRoleIdDeleted
  const result = {
    ...fixture,
    checks,
    denials,
    publishBoundaries,
    exports,
    boundaries,
    errors,
    cleanupErrors,
    status:
      checks.length === 5 && cleaned && fixture.sourceUnchanged && !errors.length && !cleanupErrors.length
        ? 'PASSED'
        : 'FAILED'
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(
    'A01/A22 ordinary owner creation: ' + checks.length + ' groups; ' + result.status + '; evidence ' + output
  )
  if (result.status !== 'PASSED') process.exitCode = 1
}
