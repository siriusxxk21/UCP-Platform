import assert from 'node:assert/strict'

/** 只通过现有设计和运行 API 操作本次登记的唯一夹具。 */
export function saveBody(design, extra = {}) {
  return structuredClone({
    draft: {
      id: design.draft.id,
      expectedLockVersion: design.draft.lockVersion,
      objectCode: design.draft.objectCode,
      objectName: design.draft.objectName,
      description: design.draft.description,
      tableName: design.draft.tableName,
      titleFieldKey: design.draft.titleFieldId,
      category: design.draft.category,
      fields: design.draft.fields.map(field => ({ ...field, key: field.id })),
      removedFieldIds: []
    },
    settings: design.settings,
    fieldOptions: design.fieldOptions,
    mainBinding: design.mainBinding,
    details: design.details.map(detail => ({
      ...detail,
      fields: detail.fields.map(field => ({ ...field, key: field.id }))
    })),
    relations: design.relations,
    indexes: design.indexes,
    restoredFieldIds: [],
    ...extra
  })
}

export const readDesign = (ac, id) => ac.api(`/nocode/design/get?id=${id}`)
export const inactive = (ac, id, detailId) =>
  ac.api(`/nocode/design/inactive-fields?id=${id}${detailId ? `&detailId=${detailId}` : ''}`)

export async function edit(ac, id) {
  const design = await readDesign(ac, id)
  return ac.api('/nocode/design/edit', {
    id,
    expectedLockVersion: design.draft.lockVersion,
    reason: `${ac.prefix} 字段恢复验收`
  })
}

export async function publish(ac, design) {
  const plan = await ac.api('/nocode/design/plan', {
    id: design.draft.id,
    expectedLockVersion: design.draft.lockVersion
  })
  assert.deepEqual(
    plan.checks.filter(check => check.blocking),
    [],
    '发布计划无阻断'
  )
  const execution = await ac.api('/nocode/design/execute', {
    planId: plan.id,
    reason: `${ac.prefix} 字段恢复验收`
  })
  assert.equal(execution.state, 'SUCCEEDED')
  const object = await ac.api(`/nocode/application/object-version?id=${design.draft.id}`)
  object.ids = Object.fromEntries(object.definition.fields.map(field => [field.code, field.id]))
  return object
}

export async function createFixture(ac) {
  const code = `${ac.prefix}_restore`
  const design = await ac.api('/nocode/design/save', {
    draft: {
      id: null,
      expectedLockVersion: null,
      objectCode: code,
      objectName: `字段恢复验收 ${ac.prefix}`,
      description: 'UX-040 专用回归夹具，保留用于复核，不操作已有业务数据',
      tableName: `biz_${code}`,
      titleFieldKey: 'name',
      fields: [ac.field('name', 'TEXT', '业务名称'), { ...ac.field('phone', 'TEXT', '联系电话'), unique: true }],
      removedFieldIds: []
    },
    settings: {},
    fieldOptions: { phone: ac.option({ description: '恢复后保留的原字段说明', pattern: '^1[0-9]{10}$' }) },
    relations: [],
    indexes: [],
    details: ['items', 'other_items'].map((code, index) => ({
      id: null,
      code,
      name: index ? '其他明细' : '联系明细',
      tableName: `biz_${ac.prefix}_${code}`,
      state: 'ACTIVE',
      fields: [ac.field('label', 'TEXT', '明细名称'), ac.field('contact', 'TEXT', '明细联系方式')],
      fieldOptions: { contact: ac.option({ description: '明细原字段说明' }) },
      indexes: []
    }))
  })
  ac.owned.objects.push({ id: design.draft.id, code })
  await ac.persist()
  return design
}

export async function bindApplication(ac, object) {
  // 已发布应用要求至少一个对象；切换到自有占位对象，解除被测对象的依赖。
  if (!object) {
    ac.detachedObject ||= await ac.object('detached', `字段恢复占位 ${ac.prefix}`, [ac.field('name')])
    object = ac.detachedObject
  }
  const current = ac.app ? await ac.api(`/nocode/application/get?id=${ac.app.application.id}`) : null
  ac.app = await ac.api('/nocode/application/save', {
    id: current?.application.id || null,
    expectedRevision: current?.application.revision ?? null,
    code: `${ac.prefix}_restore_app`,
    name: `字段恢复验收应用 ${ac.prefix}`,
    definition: {
      objects: object ? [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }] : [],
      resources: []
    }
  })
  if (!current) ac.owned.applications.push(ac.app.application.id)
  if (object) {
    const details = object.definition.details.map(detail => detail.id)
    await ac.share(object, ac.grant(object, { readDetails: details, writeDetails: details }))
  }
  await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: `${ac.prefix} 更新自有验收应用引用`
  })
}

export function restoreInto(body, entry, detailId) {
  assert.equal(entry.restorable, true, entry.blockedReason || '字段应可恢复')
  const fields = detailId ? body.details.find(detail => detail.id === detailId).fields : body.draft.fields
  const options = detailId ? body.details.find(detail => detail.id === detailId).fieldOptions : body.fieldOptions
  fields.push({ ...entry.field, key: entry.field.id })
  options[entry.field.id] = { ...entry.options, state: 'ACTIVE' }
  body.restoredFieldIds.push(entry.field.id)
}
