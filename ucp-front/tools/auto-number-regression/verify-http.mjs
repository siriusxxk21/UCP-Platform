import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/auto-number-http', ac.prefix)
const rule = { prefix: 'NO-', dateFormat: '', sequenceLength: 4, startValue: 20, resetCycle: 'NONE' }
const lineRule = { prefix: 'L-', dateFormat: 'yyyyMMdd', sequenceLength: 2, startValue: 1, resetCycle: 'DAY' }
try {
  await ac.login()
  const code = `${ac.prefix}_number`
  let draft
  await ac.record('对象与明细规则保存并重新读取一致', async () => {
    draft = await ac.api('/nocode/design/save', {
      draft: {
        id: null,
        expectedLockVersion: null,
        objectCode: code,
        objectName: `自动编号链路验收 ${ac.prefix}`,
        description: '2026-09-14 自动编号专用验收夹具',
        tableName: `biz_${code}`,
        titleFieldKey: 'name',
        fields: [
          ac.field('name', 'TEXT', '名称'),
          { ...ac.field('serial', 'AUTO_NUMBER', '编号'), required: true, unique: true }
        ],
        removedFieldIds: []
      },
      settings: {},
      fieldOptions: { serial: ac.option({ autoNumber: rule }) },
      relations: [],
      indexes: [],
      details: [
        {
          id: null,
          code: 'items',
          name: '编号明细',
          tableName: `biz_${ac.prefix}_items`,
          state: 'ACTIVE',
          fields: [{ ...ac.field('line', 'AUTO_NUMBER', '行号'), required: true, unique: true }],
          fieldOptions: { line: ac.option({ autoNumber: lineRule }) },
          indexes: []
        }
      ]
    })
    ac.owned.objects.push({ id: draft.draft.id, code })
    draft = await ac.api(`/nocode/design/get?id=${draft.draft.id}`)
    const serial = draft.draft.fields.find(f => f.code === 'serial').id
    assert.deepEqual(draft.fieldOptions[serial].autoNumber, rule)
    const detail = draft.details[0]
    assert.deepEqual(detail.fieldOptions[detail.fields[0].id].autoNumber, lineRule)
    return { objectId: draft.draft.id, rule, lineRule }
  })
  let object, detail, line
  await ac.record('真实发布并授权自有应用', async () => {
    const plan = await ac.api('/nocode/design/plan', {
      id: draft.draft.id,
      expectedLockVersion: draft.draft.lockVersion
    })
    assert.deepEqual(
      plan.checks.filter(c => c.blocking),
      []
    )
    const execution = await ac.api('/nocode/design/execute', { planId: plan.id, reason: '自动编号规则链路验收' })
    assert.equal(execution.state, 'SUCCEEDED')
    object = await ac.api(`/nocode/application/object-version?id=${draft.draft.id}`)
    object.ids = Object.fromEntries(object.definition.fields.map(f => [f.code, f.id]))
    detail = object.definition.details[0]
    line = detail.fields[0].id
    ac.app = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${ac.prefix}_number_app`,
      name: `自动编号验收 ${ac.prefix}`,
      definition: {
        objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
        resources: []
      }
    })
    ac.owned.applications.push(ac.app.application.id)
    await ac.share(object, ac.grant(object, { readDetails: [detail.id], writeDetails: [detail.id] }))
    await ac.api('/nocode/application/publish', {
      id: ac.app.application.id,
      expectedRevision: ac.app.application.revision,
      reason: '自动编号规则链路验收'
    })
    return { objectId: object.objectId, applicationId: ac.app.application.id, version: object.versionNo }
  })
  const save = name =>
    ac.api('/nocode/runtime/save', {
      ...ac.saveBody(object, { name }),
      details: { [detail.id]: [{ id: null, revision: null, values: {} }] }
    })
  let first, second
  await ac.record('连续两条真实记录主表及内部明细按规则编号', async () => {
    first = await save('第一条')
    second = await save('第二条')
    assert.equal(first.record.values[object.ids.serial], 'NO-0020')
    assert.equal(second.record.values[object.ids.serial], 'NO-0021')
    assert.match(first.details[detail.id][0].values[line], /^L-\d{8}01$/)
    assert.match(second.details[detail.id][0].values[line], /^L-\d{8}02$/)
    const read = await ac.get(object, first.record)
    assert.equal(read.record.values[object.ids.serial], 'NO-0020')
    assert.equal(read.details[detail.id][0].values[line], first.details[detail.id][0].values[line])
    return {
      first: first.record.id,
      second: second.record.id,
      firstNumber: first.record.values[object.ids.serial],
      firstLineNumber: first.details[detail.id][0].values[line]
    }
  })
  await ac.record('修改已有记录保持主表及明细编号；API手填被拒绝', async () => {
    const updated = await ac.save(object, { name: '第一条修改' }, first.record)
    assert.equal(updated.values[object.ids.serial], 'NO-0020')
    const read = await ac.get(object, updated)
    assert.equal(read.details[detail.id][0].values[line], first.details[detail.id][0].values[line])
    const forged = await ac.request('/nocode/runtime/save', ac.saveBody(object, { name: '伪造编号', serial: 'FORGED' }))
    assert.notEqual(forged.code, 0)
    assert.match(forged.msg, /字段|系统|权限/)
    return { number: updated.values[object.ids.serial], rejectedMessage: forged.msg }
  })
  await ac.record('编号规则只保存草稿时，运行继续使用当前发布规则', async () => {
    let editing = await ac.api(`/nocode/design/get?id=${object.objectId}`)
    editing = await ac.api('/nocode/design/edit', {
      id: object.objectId,
      expectedLockVersion: editing.draft.lockVersion,
      reason: '验证草稿规则隔离'
    })
    const fields = editing.draft.fields.map(f => ({ ...f, key: f.id }))
    editing.fieldOptions[object.ids.serial].autoNumber = { ...rule, prefix: 'DRAFT-' }
    await ac.api('/nocode/design/save', {
      draft: {
        id: editing.draft.id,
        expectedLockVersion: editing.draft.lockVersion,
        objectCode: editing.draft.objectCode,
        objectName: editing.draft.objectName,
        description: editing.draft.description,
        tableName: editing.draft.tableName,
        titleFieldKey: editing.draft.titleFieldId,
        fields,
        removedFieldIds: []
      },
      settings: editing.settings,
      fieldOptions: editing.fieldOptions,
      relations: editing.relations,
      indexes: editing.indexes,
      details: editing.details
    })
    const after = await save('草稿期间新增')
    assert.equal(after.record.values[object.ids.serial], 'NO-0022')
    return { newRecord: after.record.id, number: after.record.values[object.ids.serial], unpublishedPrefix: 'DRAFT-' }
  })
} finally {
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  console.log(
    JSON.stringify({ output: ac.output, passed: ac.checks.filter(c => c.passed).length, checks: ac.checks.length })
  )
}
