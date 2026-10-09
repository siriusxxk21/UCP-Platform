import assert from 'node:assert/strict'

/** 所有场景走正式认证、设计、发布与运行 HTTP 入口。 */
export async function prepare(ac, source) {
  ac.source =
    source ||
    (await ac.object('source', '取数来源', [
      ac.field('name', 'TEXT', '来源名称'),
      ac.field('group_code', 'TEXT', '分类'),
      ac.field('amount', 'DECIMAL', '金额')
    ]))
  const match = [{ targetField: 'group_code', operator: 'eq', localField: 'name', value: null }]
  const lookup = ac.calculation('LOOKUP', 'LIVE', {
    targetObjectId: ac.source.objectId,
    targetField: 'amount',
    conditions: match
  })
  ac.main = await ac.object(
    'main',
    '计算与链接',
    [
      ac.field('name', 'TEXT', '名称'),
      ac.field('qty', 'INTEGER', '数量'),
      ac.field('price', 'DECIMAL', '单价'),
      ac.field('link', 'URL', '资产说明'),
      ac.field('total', 'FORMULA', '本行金额'),
      ac.field('live_sum', 'FORMULA', '实时汇总'),
      ac.field('saved_sum', 'FORMULA', '保存快照'),
      ac.field('dependent', 'FORMULA', '引用计算字段')
    ],
    {
      total: ac.option({ expression: 'qty * price', resultType: 'DECIMAL', calculation: ac.calculation('LOCAL') }),
      live_sum: ac.option({ resultType: 'DECIMAL', calculation: lookup }),
      saved_sum: ac.option({ resultType: 'DECIMAL', calculation: { ...lookup, updateMode: 'ON_SAVE' } }),
      dependent: ac.option({
        expression: 'coalesce(live_sum, 0) * qty',
        resultType: 'DECIMAL',
        calculation: ac.calculation('LOCAL')
      })
    }
  )
  ac.permission = await ac.object('permission', '权限边界', [
    ac.field('name', 'TEXT', '名称'),
    ac.field('status', 'TEXT', '分类'),
    ac.field('amount', 'INTEGER', '金额'),
    ac.field('department', 'INTEGER', '归属部门'),
    ac.field('owner', 'INTEGER', '负责人'),
    ac.field('secret', 'TEXT', '内部备注')
  ])
  const resources = []
  for (const [key, object] of Object.entries({ source: ac.source, main: ac.main, permission: ac.permission })) {
    const ids = object.definition.fields.map(f => f.id)
    resources.push({
      id: `${key}_form`,
      kind: 'FORM',
      code: `${key}_form`,
      name: `${object.definition.objectName}录入`,
      config: {
        objectId: object.objectId,
        nodes: ids.map(fieldId => ({
          id: `${key}_${fieldId}`,
          type: 'FIELD',
          fieldId,
          resourceId: null,
          text: null,
          span: 12,
          children: []
        })),
        detailIds: [],
        options: { layout: 'vertical', submitText: '保存' }
      }
    })
    resources.push({
      id: `${key}_view`,
      kind: 'VIEW',
      code: `${key}_view`,
      name: object.definition.objectName,
      config: {
        objectId: object.objectId,
        fieldIds: ids,
        equal: {},
        sortFieldId: null,
        descending: false,
        pageSize: 10,
        formId: `${key}_form`,
        list: { queryFieldIds: [ids[0]], advancedFieldIds: [ids[0]], columnWidths: {}, batchDelete: false }
      }
    })
    resources.push({
      id: `${key}_menu`,
      kind: 'MENU',
      code: `${key}_menu`,
      name: object.definition.objectName,
      config: { targetId: `${key}_view` }
    })
  }
  resources.push({
    id: 'permission_report',
    kind: 'REPORT',
    code: 'permission_report',
    name: '权限记录统计',
    config: {
      objectId: ac.permission.objectId,
      dimensions: [],
      metrics: [{ id: 'count', name: '记录数', operation: 'COUNT', fieldId: null }],
      equal: {},
      filterFieldIds: [],
      timeZone: 'Asia/Shanghai',
      display: 'METRIC',
      descending: false,
      limit: 20
    }
  })
  ac.app ||= await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: `${ac.prefix}_app`,
    name: '数据表单功能验收0911本地',
    description: `${ac.prefix} 专用测试数据`,
    icon: null,
    definition: {
      objects: [ac.source, ac.main, ac.permission].map(({ objectId, versionNo, checksum }) => ({
        objectId,
        versionNo,
        checksum
      })),
      resources
    }
  })
  if (!ac.owned.applications.length)
    ac.owned.applications.push({ id: ac.app.application.id, code: ac.app.application.code })
  await ac.persist()
  for (const object of [ac.source, ac.main, ac.permission]) {
    await ac.share(object, ac.grant(object, { computeFields: object.definition.fields.map(f => f.id) }))
  }
  if (!ac.app.application.publishedVersion)
    ac.app = await ac.api('/nocode/application/publish', {
      id: ac.app.application.id,
      expectedRevision: ac.app.application.revision,
      reason: '本地表单功能验收'
    })
  const organizations = await ac.api('/system/organization/tree')
  assert.ok(organizations[0]?.id, '需要当前租户有效组织来创建专用部门夹具')
  ac.department ||= {}
  for (const key of ['root', 'child', 'outside']) {
    if (ac.department[key]) continue
    const body = {
      orgId: organizations[0].id,
      name: `${ac.prefix}${key}`,
      parentId: key === 'child' ? ac.department.root : '0',
      sort: 999,
      status: 0
    }
    ac.department[key] = String(await ac.api('/system/dept', body))
    ac.owned.departments.push({ id: ac.department[key], ...body })
    await ac.persist()
  }
  ac.userId ||= await ac.user('member', ac.department.root)
  ac.otherUserId ||= await ac.user('other', ac.department.outside)
  ac.rows ||= {}
  for (const [key, department] of Object.entries(ac.department)) {
    for (const status of ['A', 'B']) {
      const name = { root: '本部', child: '下级', outside: '外部' }[key] + status
      ac.rows[key + status] ||= await ac.save(ac.permission, {
        name,
        status,
        amount: status === 'A' ? 50 : 200,
        department,
        owner: key === 'outside' ? ac.otherUserId : ac.userId,
        secret: '仅管理员可见'
      })
    }
  }
  return { applicationId: ac.app.application.id, objects: ac.owned.objects, users: ac.owned.users }
}

export async function calculations(ac) {
  const s = ac.source,
    m = ac.main
  await ac.record('F01 新增、本行和跨表精确计算', async () => {
    ac.sourceRows = [
      await ac.save(s, { name: '源一', group_code: 'A', amount: '0.1' }),
      await ac.save(s, { name: '源二', group_code: 'A', amount: '0.2' })
    ]
    ac.mainA = await ac.save(m, {
      name: 'A',
      qty: 3,
      price: '10',
      link: { link: 'https://example.com', text: '资产说明链接' }
    })
    const v = ac.mainA.values
    assert.equal(Number(v[m.ids.total]), 30)
    assert.equal(Number(v[m.ids.live_sum]), 0.3)
    assert.equal(Number(v[m.ids.saved_sum]), 0.3)
    assert.equal(Number(v[m.ids.dependent]), 0.9)
    return {
      total: v[m.ids.total],
      live: v[m.ids.live_sum],
      snapshot: v[m.ids.saved_sum],
      dependent: v[m.ids.dependent]
    }
  })
  await ac.record('F02 来源变化、实时重算与保存快照', async () => {
    ac.sourceRows[0] = await ac.save(s, { amount: '0.5' }, ac.sourceRows[0])
    let row = (await ac.get(m, ac.mainA)).record
    assert.equal(Number(row.values[m.ids.live_sum]), 0.7)
    assert.equal(Number(row.values[m.ids.saved_sum]), 0.3)
    assert.equal(Number(row.values[m.ids.dependent]), 2.1)
    row = await ac.save(m, { qty: 4 }, row)
    assert.equal(Number(row.values[m.ids.total]), 40)
    assert.equal(Number(row.values[m.ids.saved_sum]), 0.7)
    ac.mainA = row
    return { live: row.values[m.ids.live_sum], snapshot: row.values[m.ids.saved_sum], total: row.values[m.ids.total] }
  })
  await ac.record('F03 无匹配来源与来源删除', async () => {
    ac.mainB = await ac.save(m, { name: 'B', qty: 1, price: 5 })
    ac.mainC = await ac.save(m, { name: 'C', qty: 1, price: 8 })
    assert.equal(Number(ac.mainB.values[m.ids.live_sum]), 0)
    await ac.remove(s, ac.sourceRows[1])
    const row = (await ac.get(m, ac.mainA)).record
    assert.equal(Number(row.values[m.ids.live_sum]), 0.5)
    assert.equal(Number(row.values[m.ids.saved_sum]), 0.7)
    return { liveAfterDelete: row.values[m.ids.live_sum], snapshot: row.values[m.ids.saved_sum] }
  })
  await ac.record('F04 超链接往返和非法协议拒绝', async () => {
    const row = (await ac.get(m, ac.mainA)).record
    assert.equal(row.values[m.ids.link].link, 'https://example.com')
    assert.equal(row.values[m.ids.link].text, '资产说明链接')
    for (const link of ['javascript:alert(1)', 'data:text/html,x', '//example.com']) {
      const result = await ac.request('/nocode/runtime/save', ac.saveBody(m, { link: { link, text: '非法地址' } }, row))
      assert.notEqual(result.code, 0)
      assert.match(result.msg, /超链接|HTTP|HTTPS|地址/)
    }
    const unchanged = (await ac.get(m, row)).record
    assert.equal(unchanged.revision, row.revision)
    assert.equal(unchanged.values[m.ids.link].link, 'https://example.com')
    return '3 种非法协议均拒绝，原值和版本保持'
  })
  await ac.record('F05 来源计算授权撤销即时生效', async () => {
    try {
      await ac.share(s, ac.grant(s))
      await ac.denied(
        `/nocode/runtime/get?applicationId=${ac.app.application.id}&objectId=${m.objectId}&id=${ac.mainA.id}`
      )
    } finally {
      await ac.share(s, ac.grant(s, { computeFields: s.definition.fields.map(f => f.id) }))
    }
    return '撤销取数授权后拒绝读取；恢复后可继续验收'
  })
}

export function baselineGrant(ac) {
  const p = ac.permission
  return ac.grant(p, {
    actions: ['READ', 'CREATE', 'UPDATE', 'DELETE', 'EXPORT'],
    readFields: p.definition.fields.filter(f => f.code !== 'secret').map(f => f.id),
    writeFields: ['name', 'status', 'amount'].map(code => p.ids[code]),
    actionScopes: {
      READ: ac.scope(ac.condition(p, 'name', 'in', ['本部A', '下级B'])),
      CREATE: ac.scope(ac.condition(p, 'status', 'eq', 'A')),
      UPDATE: ac.scope(ac.condition(p, 'amount', 'lte', 100)),
      DELETE: ac.scope(ac.condition(p, 'status', 'eq', 'A')),
      EXPORT: ac.scope(ac.condition(p, 'status', 'eq', 'B'))
    }
  })
}

export async function permissions(ac) {
  const p = ac.permission,
    token = ac.tokens[ac.userId]
  await ac.authorize([ac.member(ac.userId, [baselineGrant(ac)])])
  await ac.record('P01 普通账号、行过滤与分页总数', async () => {
    const roles = await ac.api(`/system/permission/list-user-roles?userId=${ac.userId}`)
    assert.equal(roles.length, 0)
    const page1 = await ac.page(p, token, { pageSize: 1 })
    const page2 = await ac.page(p, token, { pageNo: 2, pageSize: 1 })
    assert.equal(page1.total, 2)
    assert.equal(page2.total, 2)
    assert.equal(page1.list.length, 1)
    assert.equal(page2.list.length, 1)
    const names = [page1.list[0].values[p.ids.name], page2.list[0].values[p.ids.name]].sort()
    assert.equal(JSON.stringify(names), JSON.stringify(['下级B', '本部A'].sort()))
    return { roles: 0, total: page1.total, records: names }
  })
  await ac.record('P02 字段裁剪、隐藏字段筛选及伪造写入', async () => {
    const model = await ac.api(
      `/nocode/runtime/model?applicationId=${ac.app.application.id}&objectId=${p.objectId}`,
      undefined,
      token
    )
    assert.ok(!model.permissions.readFields.includes(p.ids.secret))
    const row = (await ac.get(p, ac.rows.rootA, token)).record
    assert.ok(!(p.ids.secret in row.values))
    await ac.denied('/nocode/runtime/save', ac.saveBody(p, { secret: '尝试修改' }, row), token)
    await ac.denied('/nocode/runtime/page', ac.query(p, { equal: { [p.ids.secret]: '仅管理员可见' } }), token)
    const unchanged = (await ac.get(p, ac.rows.rootA)).record
    assert.equal(unchanged.revision, ac.rows.rootA.revision)
    assert.equal(unchanged.values[p.ids.secret], '仅管理员可见')
    return '列表/详情裁剪隐藏字段，隐藏字段不能筛选和写入'
  })
  await ac.record('P03 详情越权与修改前后条件、事务回滚', async () => {
    await ac.denied(
      `/nocode/runtime/get?applicationId=${ac.app.application.id}&objectId=${p.objectId}&id=${ac.rows.outsideA.id}`,
      undefined,
      token
    )
    const beforeB = await ac.denied('/nocode/runtime/save', ac.saveBody(p, { amount: 50 }, ac.rows.childB), token)
    const afterA = await ac.denied('/nocode/runtime/save', ac.saveBody(p, { amount: 101 }, ac.rows.rootA), token)
    const forged = await ac.request(
      '/nocode/runtime/save',
      { ...ac.saveBody(p, { amount: 101 }, ac.rows.rootA), actorId: 1 },
      token
    )
    assert.notEqual(forged.code, 0)
    assert.match(forged.msg, /请求结构无效/)
    const a = (await ac.get(p, ac.rows.rootA)).record
    const b = (await ac.get(p, ac.rows.childB)).record
    assert.equal(a.values[p.ids.amount], '50')
    assert.equal(a.revision, ac.rows.rootA.revision)
    assert.equal(b.values[p.ids.amount], '200')
    assert.equal(b.revision, ac.rows.childB.revision)
    const changed = await ac.save(p, { amount: 99 }, a, token)
    assert.equal(changed.values[p.ids.amount], '99')
    ac.rows.rootA = await ac.save(p, { amount: 50 }, changed, token)
    return { beforeB, afterA, originalValuesPreserved: true, allowedUpdate: true }
  })
  await ac.record('P04 新增与删除的独立权限', async () => {
    await ac.denied('/nocode/runtime/save', ac.saveBody(p, { name: '本部A', status: 'B', amount: 5 }), token)
    assert.equal((await ac.page(p)).total, 6)
    await ac.denied(
      '/nocode/runtime/delete',
      {
        applicationId: ac.app.application.id,
        objectId: p.objectId,
        id: ac.rows.childB.id,
        expectedRevision: ac.rows.childB.revision
      },
      token
    )
    const temporary = await ac.save(p, { name: '本部A', status: 'A', amount: 5 }, null, token)
    assert.equal((await ac.page(p, token)).total, 3)
    await ac.remove(p, temporary, token)
    assert.equal((await ac.page(p, token)).total, 2)
    assert.equal((await ac.page(p)).total, 6)
    return '范围外新增/删除均拒绝；范围内临时记录可新增后删除'
  })
  await ac.record('P05 导出与统计使用同一记录权限', async () => {
    const bytes = await ac.export(p, token, 'permission-export.xlsx')
    const query = {
      applicationId: ac.app.application.id,
      reportId: 'permission_report',
      equal: {},
      pageNo: 1,
      pageSize: 20
    }
    const report = await ac.api('/nocode/runtime/report', query, token)
    const details = await ac.api('/nocode/runtime/report-details', query, token)
    assert.equal(report.recordCount, 2)
    assert.equal(details.total, 2)
    assert.ok(details.list.every(row => !(p.ids.secret in row.values)))
    return {
      excelBytes: bytes,
      reportCount: report.recordCount,
      detailCount: details.total,
      excelContentRequiresCheck: true
    }
  })
}

export async function departmentAndRoles(ac) {
  const p = ac.permission,
    token = ac.tokens[ac.userId]
  const readFields = p.definition.fields.filter(f => f.code !== 'secret').map(f => f.id)
  const readGrant = scope =>
    ac.grant(p, { actions: ['READ'], readFields, writeFields: [], actionScopes: { READ: scope } })
  await ac.record('P06 真实本部门及下级与共享上限交集', async () => {
    await ac.authorize([
      ac.member(ac.userId, [readGrant(ac.scope(ac.condition(p, 'department', 'eq', null, 'CURRENT_DEPARTMENT')))])
    ])
    assert.equal((await ac.page(p, token)).total, 2)
    const treeGrant = readGrant(ac.scope(ac.condition(p, 'department', 'in', null, 'CURRENT_DEPARTMENT_TREE')))
    await ac.authorize([ac.member(ac.userId, [treeGrant])])
    assert.equal((await ac.page(p, token)).total, 4)
    try {
      await ac.share(p, ac.grant(p, { actionScopes: { READ: ac.scope(ac.condition(p, 'status', 'eq', 'A')) } }))
      const result = await ac.page(p, token)
      assert.equal(result.total, 2)
      assert.ok(result.list.every(row => row.values[p.ids.status] === 'A'))
      assert.equal((await ac.page(p)).total, 3, '管理员也受共享上限约束')
    } finally {
      await ac.share(p, ac.grant(p))
    }
    return { ownDepartment: 2, withDescendants: 4, intersectCeiling: 2, ownerCeiling: 3 }
  })
  await ac.record('P07 部门禁用与当前用户动态值不得放宽', async () => {
    try {
      await ac.api(`/system/dept/${ac.department.root}/status?status=1`, undefined, undefined, 'PUT')
      assert.equal((await ac.page(p, token)).total, 0)
    } finally {
      await ac.api(`/system/dept/${ac.department.root}/status?status=0`, undefined, undefined, 'PUT')
    }
    const own = readGrant(ac.scope(ac.condition(p, 'owner', 'eq', null, 'CURRENT_USER')))
    await ac.authorize([ac.member(ac.userId, [own]), ac.member(ac.otherUserId, [own])])
    assert.equal((await ac.page(p, token)).total, 4)
    assert.equal((await ac.page(p, ac.tokens[ac.otherUserId])).total, 2)
    const forged = await ac.request(
      '/nocode/runtime/page',
      ac.query(p, { actorId: ac.otherUserId, currentUser: ac.otherUserId }),
      token
    )
    assert.notEqual(forged.code, 0)
    assert.match(forged.msg, /请求结构无效/)
    return { disabledDepartment: 0, memberOwn: 4, otherOwn: 2, forgedIdentityRejected: true }
  })
  await ac.record('P08 多角色合并不得交叉扩展修改范围', async () => {
    ac.roleIds = []
    for (const key of ['readA', 'readB']) {
      const roleId = String(
        await ac.api('/system/role/create', {
          name: `${ac.prefix}${key}`,
          code: `${ac.prefix}_${key}`,
          sort: 999,
          status: 0,
          remark: '表单功能验收专用角色，无系统管理菜单',
          globalProjectView: 0
        })
      )
      ac.roleIds.push(roleId)
      ac.owned.roles.push({ id: roleId, code: `${ac.prefix}_${key}` })
      await ac.persist()
    }
    await ac.api('/system/permission/assign-user-role', { userId: ac.userId, roleIds: ac.roleIds })
    const roleA = ac.grant(p, {
      actions: ['READ', 'UPDATE'],
      readFields,
      writeFields: [p.ids.amount],
      actionScopes: {
        READ: ac.scope(ac.condition(p, 'status', 'eq', 'A')),
        UPDATE: ac.scope(ac.condition(p, 'status', 'eq', 'A'), ac.condition(p, 'amount', 'lte', 100))
      }
    })
    const roleB = readGrant(ac.scope(ac.condition(p, 'status', 'eq', 'B')))
    await ac.authorize([ac.member(ac.roleIds[0], [roleA], 'ROLE'), ac.member(ac.roleIds[1], [roleB], 'ROLE')])
    assert.equal((await ac.page(p, token)).total, 6)
    await ac.denied('/nocode/runtime/save', ac.saveBody(p, { amount: 50 }, ac.rows.childB), token)
    const row = await ac.save(p, { amount: 99 }, ac.rows.rootA, token)
    ac.rows.rootA = await ac.save(p, { amount: 50 }, row, token)
    await ac.api('/system/permission/assign-user-role', { userId: ac.userId, roleIds: [ac.roleIds[0]] })
    assert.equal((await ac.page(p, token)).total, 3, '撤销角色后旧会话应立即失去其范围')
    await ac.api('/system/permission/assign-user-role', { userId: ac.userId, roleIds: [] })
    await ac.denied('/nocode/runtime/page', ac.query(p), token)
    return { mergedRead: 6, afterOneRoleRevoked: 3, afterAllRevoked: '拒绝', crossScopeUpdate: '拒绝' }
  })
  await ac.authorize([ac.member(ac.userId, [baselineGrant(ac)])])
}

export async function saveApplication(ac, resources, objects = ac.app.draft.objects) {
  const current = await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)
  ac.app = await ac.api('/nocode/application/save', {
    id: current.application.id,
    expectedRevision: current.application.revision,
    code: current.application.code,
    name: current.application.name,
    description: current.application.description,
    icon: current.application.icon,
    definition: { objects, resources }
  })
  ac.app = await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '补充表单验收配置'
  })
}

export async function fixedDictionary(ac) {
  const m = ac.main
  await ac.record('V01 固定字典多值、默认值、清空与越界筛选', async () => {
    const current = await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)
    const dictionary = {
      id: 'accept_dictionary',
      kind: 'DICTIONARY',
      code: 'accept_dictionary',
      name: '验收分类',
      config: {
        items: [
          { code: 'A', label: '办公A', disabled: false },
          { code: 'B', label: '生产B', disabled: false },
          { code: 'C', label: '其他C', disabled: false }
        ]
      }
    }
    const resources = current.draft.resources
      .filter(r => r.id !== dictionary.id)
      .map(resource =>
        resource.id !== 'main_view'
          ? resource
          : {
              ...resource,
              config: {
                ...resource.config,
                filterDictionaries: { [m.ids.name]: dictionary.id },
                query: {
                  fixed: [ac.condition(m, 'name', 'in', ['A', 'B'])],
                  defaults: { [m.ids.name]: 'A' },
                  candidates: {}
                }
              }
            }
      )
    await saveApplication(ac, [...resources, dictionary])
    assert.equal((await ac.page(m)).total, 3)
    assert.equal((await ac.page(m, undefined, { viewId: 'main_view' })).total, 2)
    assert.equal((await ac.page(m, undefined, { viewId: 'main_view', equal: { [m.ids.name]: 'A' } })).total, 1)
    assert.equal((await ac.page(m, undefined, { viewId: 'main_view', equal: { [m.ids.name]: 'C' } })).total, 0)
    const runtime = await ac.api(`/nocode/runtime/application?id=${ac.app.application.id}`)
    const view = runtime.definition.resources.find(r => r.id === 'main_view')
    assert.equal(view.config.query.defaults[m.ids.name], 'A')
    assert.equal(JSON.stringify(view.config.query.candidates[m.ids.name].sort()), '["A","B"]')
    return { unfiltered: 3, fixed: 2, defaultA: 1, outsideC: 0, candidates: ['A', 'B'] }
  })
}

export async function formulaBoundaries(ac) {
  const s = ac.source
  const edge = await ac.object(
    'edge',
    '公式边界',
    [
      ac.field('name', 'TEXT', '名称'),
      ac.field('denominator', 'INTEGER', '除数'),
      ac.field('amount', 'DECIMAL', '金额'),
      ac.field('local_sum', 'FORMULA', '本表汇总'),
      ac.field('unique_source', 'FORMULA', '唯一取数'),
      ac.field('ratio', 'FORMULA', '除法计算')
    ],
    {
      local_sum: ac.option({
        resultType: 'DECIMAL',
        calculation: ac.calculation('LOOKUP', 'LIVE', {
          targetField: 'amount',
          conditions: [{ targetField: 'name', operator: 'eq', localField: 'name', value: null }]
        })
      }),
      unique_source: ac.option({
        resultType: 'DECIMAL',
        calculation: ac.calculation('LOOKUP', 'ON_SAVE', {
          targetObjectId: s.objectId,
          targetField: 'amount',
          aggregate: 'SINGLE',
          conditions: [{ targetField: 'group_code', operator: 'eq', localField: 'name', value: null }]
        })
      }),
      ratio: ac.option({
        resultType: 'DECIMAL',
        expression: '10 / denominator',
        calculation: ac.calculation('LOCAL', 'ON_SAVE')
      })
    }
  )
  ac.edge = edge
  const current = await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)
  const ref = { objectId: edge.objectId, versionNo: edge.versionNo, checksum: edge.checksum }
  await ac.share(edge, ac.grant(edge, { computeFields: edge.definition.fields.map(f => f.id) }))
  await saveApplication(ac, current.draft.resources, [...current.draft.objects, ref])
  await ac.record('F06 唯一匹配错误与除零均原子回滚', async () => {
    const duplicate = await ac.save(s, { name: '重复来源', group_code: 'A', amount: '7' })
    try {
      const result = await ac.request(
        '/nocode/runtime/save',
        ac.saveBody(edge, { name: 'A', denominator: 2, amount: '0.1' })
      )
      assert.notEqual(result.code, 0)
      assert.match(result.msg, /多条|唯一/)
      assert.equal((await ac.page(edge)).total, 0)
    } finally {
      await ac.remove(s, duplicate)
    }
    const result = await ac.request(
      '/nocode/runtime/save',
      ac.saveBody(edge, { name: 'A', denominator: 0, amount: '0.1' })
    )
    assert.notEqual(result.code, 0)
    assert.match(result.msg, /除数/)
    assert.equal((await ac.page(edge)).total, 0)
    return '两类计算错误均未遗留主记录'
  })
  await ac.record('F07 本表条件汇总与正常唯一取数', async () => {
    let first = await ac.save(edge, { name: 'A', denominator: 2, amount: '0.1' })
    await ac.save(edge, { name: 'A', denominator: 2, amount: '0.2' })
    first = (await ac.get(edge, first)).record
    assert.equal(Number(first.values[edge.ids.local_sum]), 0.3)
    assert.equal(Number(first.values[edge.ids.unique_source]), 0.5)
    assert.equal(Number(first.values[edge.ids.ratio]), 5)
    return {
      sameTableSum: first.values[edge.ids.local_sum],
      singleSource: first.values[edge.ids.unique_source],
      ratio: first.values[edge.ids.ratio]
    }
  })
  await ac.record('F08 循环依赖在保存设计时拒绝', async () => {
    const fields = [ac.field('name'), ac.field('a', 'FORMULA'), ac.field('b', 'FORMULA')]
    const response = await ac.request('/nocode/design/save', {
      draft: {
        id: null,
        expectedLockVersion: null,
        objectCode: `${ac.prefix}_cycle`,
        objectName: '表单验收循环拒绝',
        tableName: `biz_${ac.prefix}_cycle`,
        titleFieldKey: 'name',
        fields,
        removedFieldIds: []
      },
      settings: {},
      relations: [],
      indexes: [],
      details: [],
      fieldOptions: {
        a: ac.option({ resultType: 'DECIMAL', expression: 'b + 1', calculation: ac.calculation('LOCAL') }),
        b: ac.option({ resultType: 'DECIMAL', expression: 'a + 1', calculation: ac.calculation('LOCAL') })
      }
    })
    assert.notEqual(response.code, 0)
    assert.match(response.msg, /循环/)
    return { code: response.code, message: response.msg }
  })
}

export async function relationAndMultiple(ac) {
  await ac.record('F09 关系取值、改绑与来源删除', async () => {
    let relationObject = await ac.object('relation', '关系取值', [ac.field('name', 'TEXT', '名称')], {}, [
      {
        id: null,
        code: 'related',
        name: '业务关联',
        kind: 'REFERENCE',
        targetObjectId: ac.source.objectId,
        fieldId: null,
        targetFieldId: null,
        required: false,
        onDelete: 'SET_NULL'
      }
    ])
    const relation = relationObject.definition.relations[0]
    if (!relationObject.definition.fields.some(field => field.code === 'related_amount')) {
      const design = await ac.api(`/nocode/design/get?id=${relationObject.objectId}`)
      const edit =
        design.draft.state === 'DRAFT'
          ? design
          : await ac.api('/nocode/design/edit', {
              id: design.draft.id,
              expectedLockVersion: design.draft.lockVersion,
              reason: '验收关系计算'
            })
      const saved = await ac.api('/nocode/design/save', {
        draft: {
          id: edit.draft.id,
          objectCode: edit.draft.objectCode,
          objectName: edit.draft.objectName,
          description: edit.draft.description,
          tableName: edit.draft.tableName,
          expectedLockVersion: edit.draft.lockVersion,
          titleFieldKey: edit.draft.titleFieldId,
          fields: [
            ...edit.draft.fields.map(f => ({ ...f, key: f.id })),
            ac.field('related_amount', 'FORMULA', '关联金额')
          ],
          removedFieldIds: []
        },
        settings: edit.settings,
        fieldOptions: {
          ...edit.fieldOptions,
          related_amount: ac.option({
            resultType: 'DECIMAL',
            calculation: ac.calculation('RELATION', 'LIVE', {
              relationId: relation.id,
              targetField: 'amount',
              aggregate: 'SINGLE'
            })
          })
        },
        relations: edit.relations,
        indexes: edit.indexes,
        details: edit.details
      })
      const plan = await ac.api('/nocode/design/plan', {
        id: saved.draft.id,
        expectedLockVersion: saved.draft.lockVersion
      })
      assert.equal(plan.checks.filter(c => c.blocking).length, 0)
      assert.equal(
        (await ac.api('/nocode/design/execute', { planId: plan.id, reason: '验收关系计算发布' })).state,
        'SUCCEEDED'
      )
      relationObject = await ac.api(`/nocode/application/object-version?id=${relationObject.objectId}`)
    }
    relationObject.ids = Object.fromEntries(relationObject.definition.fields.map(f => [f.code, f.id]))
    const current = await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)
    await ac.share(relationObject, ac.grant(relationObject))
    await saveApplication(ac, current.draft.resources, [
      ...current.draft.objects,
      { objectId: relationObject.objectId, versionNo: relationObject.versionNo, checksum: relationObject.checksum }
    ])
    const first = await ac.save(ac.source, { name: '关系源一', group_code: 'R', amount: '12.34' })
    const second = await ac.save(ac.source, { name: '关系源二', group_code: 'R', amount: '25.01' })
    const reference = relationObject.definition.fields.find(f => f.id === relation.fieldId).code
    let row = await ac.save(relationObject, { name: '关系验收', [reference]: first.id })
    assert.equal(Number(row.values[relationObject.ids.related_amount]), 12.34)
    row = await ac.save(relationObject, { [reference]: second.id }, row)
    assert.equal(Number(row.values[relationObject.ids.related_amount]), 25.01)
    await ac.remove(ac.source, second)
    row = (await ac.get(relationObject, row)).record
    assert.equal(row.values[relationObject.ids.related_amount], null)
    return { initial: '12.34', rebound: '25.01', afterTargetDeletion: null }
  })
  await ac.record('V02 多选字段包含任意、包含全部与完全相等', async () => {
    const object = await ac.object(
      'multiple',
      '多选集合',
      [ac.field('name', 'TEXT', '名称'), ac.field('tags', 'MULTI_SELECT', '分类')],
      {
        tags: ac.option({ options: ['A', 'B', 'C'].map(code => ({ code, label: code, disabled: false })) })
      }
    )
    const current = await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)
    const resources = ['containsAny', 'containsAll', 'eq'].map(operator => ({
      id: `multiple_${operator}`,
      kind: 'VIEW',
      code: `multiple_${operator.toLowerCase()}`,
      name: `多选${operator}`,
      config: {
        objectId: object.objectId,
        fieldIds: Object.values(object.ids),
        equal: {},
        pageSize: 10,
        descending: false,
        query: { fixed: [ac.condition(object, 'tags', operator, ['A', 'B'])], defaults: {}, candidates: {} }
      }
    }))
    await ac.share(object, ac.grant(object))
    await saveApplication(
      ac,
      [...current.draft.resources, ...resources],
      [...current.draft.objects, { objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }]
    )
    for (const tags of [['A', 'B'], ['B', 'C'], ['C']]) await ac.save(object, { name: tags.join(''), tags })
    assert.equal((await ac.page(object, undefined, { viewId: 'multiple_containsAny' })).total, 2)
    assert.equal((await ac.page(object, undefined, { viewId: 'multiple_containsAll' })).total, 1)
    assert.equal((await ac.page(object, undefined, { viewId: 'multiple_eq' })).total, 1)
    assert.equal(
      (await ac.page(object, undefined, { viewId: 'multiple_containsAny', equal: { [object.ids.tags]: ['C'] } })).total,
      0
    )
    return { any: 2, all: 1, exact: 1, outside: 0 }
  })
}

export async function sharedCalculation(ac) {
  await ac.record('P09 不同成员来源可见范围不污染共享计算结果', async () => {
    const sourceGrant = name =>
      ac.grant(ac.source, {
        actions: ['READ'],
        writeFields: [],
        actionScopes: { READ: ac.scope(ac.condition(ac.source, 'name', 'eq', name)) }
      })
    const mainGrant = ac.grant(ac.main, { actions: ['READ'], writeFields: [] })
    try {
      await ac.authorize([
        ac.member(ac.userId, [baselineGrant(ac), sourceGrant('源一'), mainGrant]),
        ac.member(ac.otherUserId, [sourceGrant('不存在的来源'), mainGrant])
      ])
      assert.equal((await ac.page(ac.source, ac.tokens[ac.userId])).total, 1)
      assert.equal((await ac.page(ac.source, ac.tokens[ac.otherUserId])).total, 0)
      const first = (await ac.get(ac.main, ac.mainA, ac.tokens[ac.userId])).record
      const second = (await ac.get(ac.main, ac.mainA, ac.tokens[ac.otherUserId])).record
      assert.equal(first.values[ac.main.ids.live_sum], second.values[ac.main.ids.live_sum])
      assert.equal(Number(first.values[ac.main.ids.live_sum]), 0.5)
      return { sourceRowsByMember: [1, 0], sharedValue: first.values[ac.main.ids.live_sum] }
    } finally {
      await ac.authorize([ac.member(ac.userId, [baselineGrant(ac)])])
    }
  })
}
