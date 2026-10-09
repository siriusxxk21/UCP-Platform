import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实开发服务：校验旧链接默认值归一、PostgreSQL 发布和主表/明细缺省写入。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-url-defaults', ac.prefix)
const expected = {
  legacy: { link: 'https://example.com/legacy', text: '' },
  quoted: { link: 'https://example.com/quoted', text: '' },
  named: { link: 'https://example.com/named', text: '采购说明' }
}
const raw = {
  legacy: expected.legacy.link,
  quoted: JSON.stringify(expected.quoted.link),
  named: JSON.stringify(expected.named)
}
const fields = () => [ac.field('name'), ...Object.keys(raw).map(code => ac.field(code, 'URL'))]
const options = () =>
  Object.fromEntries(Object.entries(raw).map(([code, value]) => [code, ac.option({ defaultValue: value })]))
try {
  await ac.login()
  let object
  await ac.record('主表与明细的三种历史链接协议归一为 JSON 并发布成功', async () => {
    object = await ac.object(
      'url_defaults',
      '链接默认值归一 ' + ac.prefix,
      fields(),
      options(),
      [],
      [
        {
          id: null,
          code: 'items',
          name: '链接明细',
          tableName: `biz_${ac.prefix}_url_lines`,
          state: 'ACTIVE',
          fields: fields(),
          fieldOptions: options(),
          indexes: []
        }
      ]
    )
    const detail = object.definition.details[0]
    for (const definition of [object.definition, detail]) {
      for (const field of definition.fields.filter(field => field.type === 'URL')) {
        const normalized = definition.fieldOptions[field.id].defaultValue
        assert.deepEqual(JSON.parse(normalized), expected[field.code])
      }
    }
    const loaded = await ac.api(`/nocode/design/get?id=${object.objectId}`)
    for (const [code, id] of Object.entries(object.ids).filter(([code]) => code !== 'name'))
      assert.deepEqual(JSON.parse(loaded.fieldOptions[id].defaultValue), expected[code])
    return { objectId: object.objectId, detailId: detail.id, version: object.versionNo }
  })
  const detail = object.definition.details[0]
  const detailIds = Object.fromEntries(detail.fields.map(field => [field.code, field.id]))
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_url',
    name: '链接默认值验收 ' + ac.prefix,
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
    reason: '链接默认值真实写入验收'
  })
  await ac.persist()
  let saved
  await ac.record('运行时未传链接字段，主表和明细均写入有效 JSON 默认值', async () => {
    saved = await ac.api('/nocode/runtime/save', {
      ...ac.saveBody(object, { name: '链接缺省写入' }),
      details: { [detail.id]: [{ id: null, revision: null, values: { [detailIds.name]: '第一行' } }] }
    })
    saved = await ac.get(object, saved.record)
    for (const [code, value] of Object.entries(expected)) {
      assert.deepEqual(saved.record.values[object.ids[code]], value)
      assert.deepEqual(saved.details[detail.id][0].values[detailIds[code]], value)
    }
    return { recordId: saved.record.id, detailRecordId: saved.details[detail.id][0].id }
  })
  await ac.record('显式清空不重新套用默认值，非法链接仍拒绝且不改变已存记录', async () => {
    const cleared = await ac.api('/nocode/runtime/save', {
      ...ac.saveBody(object, { name: '链接显式清空', legacy: null }, saved.record),
      details: {
        [detail.id]: [
          {
            ...saved.details[detail.id][0],
            values: { ...saved.details[detail.id][0].values, [detailIds.legacy]: null }
          }
        ]
      }
    })
    const real = await ac.get(object, cleared.record)
    assert.equal(real.record.values[object.ids.legacy] ?? null, null)
    assert.equal(real.details[detail.id][0].values[detailIds.legacy] ?? null, null)
    const invalid = await ac.request(
      '/nocode/runtime/save',
      ac.saveBody(object, { legacy: 'javascript:alert(1)' }, real.record)
    )
    assert.notEqual(invalid.code, 0)
    assert.equal((await ac.get(object, real.record)).record.revision, real.record.revision)
    return { invalidRejected: true, recordId: real.record.id }
  })
} finally {
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  console.log(JSON.stringify({ output: ac.output, checks: ac.checks }))
}
