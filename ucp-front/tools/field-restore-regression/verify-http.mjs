import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'
import {
  bindApplication,
  createFixture,
  edit,
  inactive,
  publish,
  readDesign,
  restoreInto,
  saveBody
} from './fixture.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix = `fr${Date.now().toString(36)}`
ac.output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/field-restore-http', ac.prefix)
let design, object, original, detailId, otherDetailId, contactId, phoneId, record, mainEntry, detailEntry
const rejected = async (body, pattern) => {
  const result = await ac.request('/nocode/design/save', body)
  assert.notEqual(result.code, 0, '非法恢复请求必须被拒绝')
  assert.ok(result.http < 500, '必须返回受控业务拒绝')
  if (pattern) assert.match(result.msg, pattern)
  return { code: result.code, message: result.msg }
}

try {
  await ac.login()
  await ac.record('建立独立对象与明细夹具，写入真实历史值', async () => {
    design = await createFixture(ac)
    assert.deepEqual(await inactive(ac, design.draft.id), [])
    object = await publish(ac, design)
    original = structuredClone(object)
    phoneId = object.ids.phone
    detailId = object.definition.details.find(detail => detail.code === 'items').id
    otherDetailId = object.definition.details.find(detail => detail.code === 'other_items').id
    contactId = object.definition.details
      .find(detail => detail.id === detailId)
      .fields.find(field => field.code === 'contact').id
    await bindApplication(ac, object)
    record = await ac.api('/nocode/runtime/save', {
      ...ac.saveBody(object, { name: '原始联系记录', phone: '13800138000' }),
      details: { [detailId]: [{ id: null, revision: null, values: { [contactId]: '明细历史联系方式' } }] }
    })
    assert.equal(record.record.values[phoneId], '13800138000')
    assert.equal(record.details[detailId][0].values[contactId], '明细历史联系方式')
    return { objectId: object.objectId, detailId, phoneId, contactId, recordId: record.record.id }
  })

  await ac.record('应用引用阻止停用；解除自有引用后保存并发布停用版本', async () => {
    design = await edit(ac, object.objectId)
    const body = saveBody(design)
    body.draft.fields = body.draft.fields.filter(field => field.id !== phoneId)
    body.draft.removedFieldIds = [phoneId]
    delete body.fieldOptions[phoneId]
    delete body.details.find(detail => detail.id === detailId).fieldOptions[contactId]
    body.details.find(detail => detail.id === detailId).fields = body.details
      .find(detail => detail.id === detailId)
      .fields.filter(field => field.id !== contactId)
    const dependency = await rejected(body, /仍被引用/)
    assert.equal((await ac.get(original, record.record)).record.values[phoneId], '13800138000')
    await bindApplication(ac, null)
    design = await ac.api('/nocode/design/save', body)
    object = await publish(ac, design)
    assert.equal(
      object.definition.fields.some(field => field.id === phoneId),
      false
    )
    return { inactiveVersion: object.versionNo, dependency }
  })

  await ac.record('跨发布读取主表与对应明细停用字段，保留稳定身份和配置', async () => {
    mainEntry = (await inactive(ac, object.objectId)).find(entry => entry.field.id === phoneId)
    detailEntry = (await inactive(ac, object.objectId, detailId)).find(entry => entry.field.id === contactId)
    assert.equal(mainEntry.field.code, 'phone')
    assert.equal(mainEntry.options.description, '恢复后保留的原字段说明')
    assert.equal(mainEntry.options.pattern, '^1[0-9]{10}$')
    assert.equal(detailEntry.options.description, '明细原字段说明')
    assert.equal(
      (await inactive(ac, object.objectId, otherDetailId)).some(entry => entry.field.id === contactId),
      false
    )
    await bindApplication(ac, object)
    const read = await ac.get(object, record.record)
    assert.equal(phoneId in read.record.values, false)
    assert.equal(contactId in read.details[detailId][0].values, false)
    await ac.save(object, { name: '停用期间新增记录' })
    design = await edit(ac, object.objectId)
    return { main: mainEntry.field.id, detail: detailEntry.field.id }
  })

  await ac.record('主表与明细重复编码保存立即拒绝，失败不改变草稿版本', async () => {
    const body = saveBody(design)
    body.draft.fields.push(ac.field('phone', 'TEXT', '另一个同编码字段'))
    const main = await rejected(body, /停用|占用|编码/)
    const detailBody = saveBody(design)
    detailBody.details
      .find(detail => detail.id === detailId)
      .fields.push(ac.field('contact', 'TEXT', '另一个明细同编码'))
    const detail = await rejected(detailBody, /停用|占用|编码/)
    assert.equal((await readDesign(ac, object.objectId)).draft.lockVersion, design.draft.lockVersion)
    return { main, detail }
  })

  await ac.record('外来 ID、跨明细 ID、缺少恢复声明和无实际恢复均被拒绝', async () => {
    const foreign = await ac.object('foreign', `恢复外来字段 ${ac.prefix}`, [ac.field('name')])
    const external = saveBody(design)
    restoreInto(external, mainEntry)
    restoreInto(external, detailEntry, detailId)
    external.restoredFieldIds.push(foreign.ids.name)
    const foreignRejected = await rejected(external, /字段|恢复/)
    const cross = saveBody(design)
    restoreInto(cross, detailEntry, otherDetailId)
    const crossRejected = await rejected(cross, /字段|明细|恢复/)
    const undeclared = saveBody(design)
    restoreInto(undeclared, mainEntry)
    undeclared.restoredFieldIds = []
    await rejected(undeclared, /字段|恢复/)
    const omitted = saveBody(design)
    omitted.restoredFieldIds = [phoneId]
    await rejected(omitted, /字段|恢复/)
    assert.equal((await readDesign(ac, object.objectId)).draft.lockVersion, design.draft.lockVersion)
    return { foreignRejected, crossRejected }
  })

  await ac.record('恢复保存沿用原 ID 与选项；旧运行发布版本仍隐藏字段', async () => {
    const body = saveBody(design)
    restoreInto(body, mainEntry)
    restoreInto(body, detailEntry, detailId)
    design = await ac.api('/nocode/design/save', body)
    design = await readDesign(ac, object.objectId)
    assert.equal(design.draft.fields.find(field => field.code === 'phone').id, phoneId)
    assert.equal(
      design.details.find(detail => detail.id === detailId).fields.find(field => field.code === 'contact').id,
      contactId
    )
    assert.equal(design.fieldOptions[phoneId].pattern, '^1[0-9]{10}$')
    assert.deepEqual(await inactive(ac, object.objectId), [])
    assert.deepEqual(await inactive(ac, object.objectId, detailId), [])
    assert.equal(phoneId in (await ac.get(object, record.record)).record.values, false)
    return { restoredDraft: design.draft.lockVersion, runningVersion: object.versionNo }
  })

  await ac.record('恢复发布重新使用原物理列，更新应用引用后读回主表与明细历史值', async () => {
    object = await publish(ac, design)
    await bindApplication(ac, object)
    const read = await ac.get(object, record.record)
    assert.equal(read.record.values[phoneId], '13800138000')
    assert.equal(read.details[detailId][0].values[contactId], '明细历史联系方式')
    assert.equal(read.details[detailId][0].id, record.details[detailId][0].id)
    const duplicate = await ac.request(
      '/nocode/runtime/save',
      ac.saveBody(object, { name: '重复手机号不得保存', phone: '13800138000' })
    )
    assert.notEqual(duplicate.code, 0, '恢复后唯一约束仍生效')
    const invalid = await ac.request(
      '/nocode/runtime/save',
      ac.saveBody(object, { name: '非法手机号不得保存', phone: 'INVALID' })
    )
    assert.notEqual(invalid.code, 0, '恢复后校验规则仍生效')
    return {
      version: object.versionNo,
      recordId: read.record.id,
      phone: read.record.values[phoneId],
      detailValue: read.details[detailId][0].values[contactId]
    }
  })
} finally {
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  console.log(
    JSON.stringify({
      output: ac.output,
      passed: ac.checks.filter(check => check.passed).length,
      checks: ac.checks.length
    })
  )
}
