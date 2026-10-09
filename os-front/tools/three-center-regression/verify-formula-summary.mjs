import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实设计、发布、授权与业务 API；只创建本次唯一前缀的夹具。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-formula', ac.prefix)
try {
  await ac.login()
  await ac.record('默认值在服务端设计保存时阻止无效类型和数值精度', async () => {
    const cases = [
      ['DATE', '明天'],
      ['DATE', '2026-02-30'],
      ['DATETIME', '2026-99-99 12:00:00'],
      ['TIME', '25:01:00'],
      ['INTEGER', '1.2'],
      ['INTEGER', '9999999999999999999'],
      ['DECIMAL', '1.234'],
      ['BOOLEAN', 'yes'],
      ['MULTI_SELECT', '["missing"]']
    ]
    for (const [i, [type, value]] of cases.entries()) {
      const code = `${ac.prefix}_invalid_${i}`
      const result = await ac.request('/nocode/design/save', {
        draft: {
          id: null,
          expectedLockVersion: null,
          objectCode: code,
          objectName: '默认值拒绝验收 ' + code,
          tableName: 'biz_' + code,
          titleFieldKey: 'name',
          fields: [
            ac.field('name'),
            {
              ...ac.field('mark', type),
              precision: type === 'DECIMAL' ? 10 : null,
              scale: type === 'DECIMAL' ? 2 : null
            }
          ],
          removedFieldIds: []
        },
        settings: {},
        fieldOptions: { mark: ac.option({ defaultValue: value }) },
        relations: [],
        indexes: [],
        details: []
      })
      if (result.code === 0) ac.owned.objects.push({ id: result.data.draft.id, code })
      assert.notEqual(result.code, 0, `${type} 默认值 ${value} 必须拒绝`)
      assert.match(result.msg, /默认值/, '应返回可理解的默认值错误')
    }
    return { rejected: cases.length }
  })

  const summaryCodes = ['sum', 'avg', 'min', 'max', 'count']
  const summaryOptions = Object.fromEntries(
    summaryCodes.map(code => [
      code,
      ac.option({
        expression: code === 'count' ? 'count(items)' : `${code}(items.amount)`,
        resultType: code === 'count' ? 'INTEGER' : 'DECIMAL'
      })
    ])
  )
  let object
  await ac.record('数值公式可作为五种明细汇总来源并真实发布', async () => {
    object = await ac.object(
      'summary',
      '行金额与整单汇总 ' + ac.prefix,
      [ac.field('name'), ...summaryCodes.map(code => ac.field(code, 'SUMMARY'))],
      summaryOptions,
      [],
      [
        {
          id: null,
          code: 'items',
          name: '采购明细',
          tableName: `biz_${ac.prefix}_items`,
          state: 'ACTIVE',
          fields: [
            ac.field('qty', 'DECIMAL', '数量'),
            ac.field('price', 'DECIMAL', '单价'),
            ac.field('amount', 'FORMULA', '行金额')
          ],
          fieldOptions: { amount: ac.option({ expression: 'round(qty * price, 2)', resultType: 'DECIMAL' }) },
          indexes: []
        }
      ]
    )
    return { objectId: object.objectId, version: object.versionNo }
  })
  const detail = object.definition.details[0]
  const fields = Object.fromEntries(detail.fields.map(field => [field.code, field.id]))
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_formula',
    name: '公式与明细汇总复核 ' + ac.prefix,
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
    reason: '数值公式汇总真实接口复核'
  })
  await ac.persist()
  const row = (qty, price, previous) => ({
    id: previous?.id || null,
    revision: previous?.revision || null,
    values: { [fields.qty]: qty, [fields.price]: price }
  })
  const save = (rows, previous) =>
    ac.api('/nocode/runtime/save', {
      ...ac.saveBody(object, { name: '金额汇总验证' }, previous?.record),
      details: { [detail.id]: rows }
    })
  let saved
  const amounts = aggregate => aggregate.details[detail.id].map(record => Number(record.values[fields.amount]))
  const summaries = aggregate =>
    Object.fromEntries(
      summaryCodes.map(code => [
        code,
        aggregate.record.values[object.ids[code]] == null ? null : Number(aggregate.record.values[object.ids[code]])
      ])
    )
  await ac.record('新增三行后按实际公式结果汇总，含舍入与零', async () => {
    saved = await save([row('3', '12.345'), row('2', '7.5'), row('0', '10')])
    saved = await ac.get(object, saved.record)
    assert.deepEqual(amounts(saved), [37.04, 15, 0])
    const values = summaries(saved)
    assert.equal(values.sum, 52.04)
    assert.equal(values.min, 0)
    assert.equal(values.max, 37.04)
    assert.equal(values.count, 3)
    assert.ok(Math.abs(values.avg - 52.04 / 3) < 0.00001)
    return values
  })
  await ac.record('修改和移除明细后汇总同步，空明细可正确返回', async () => {
    const first = saved.details[detail.id][0]
    saved = await save([row('4', '12.345', first)], saved)
    saved = await ac.get(object, saved.record)
    assert.deepEqual(amounts(saved), [49.38])
    assert.deepEqual(summaries(saved), { sum: 49.38, avg: 49.38, min: 49.38, max: 49.38, count: 1 })
    saved = await save([], saved)
    saved = await ac.get(object, saved.record)
    assert.equal(saved.details[detail.id].length, 0)
    assert.equal(summaries(saved).count, 0)
    assert.ok(summaries(saved).sum === 0 || summaries(saved).sum === null)
    return summaries(saved)
  })
} finally {
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  console.log(JSON.stringify({ output: ac.output, checks: ac.checks }))
}
