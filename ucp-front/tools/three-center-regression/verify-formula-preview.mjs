import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 设计器真实试算 API；只传样例，不创建对象、记录或草稿。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-formula-preview', ac.prefix)
const path = '/nocode/design/formula-preview'
const request = (expression, values = {}, fieldCodes = Object.keys(values)) => ({ expression, fieldCodes, values })
try {
  await ac.login()
  await ac.record('十进制金额舍入及超过浮点安全范围的大整数仍精确', async () => {
    const amount = await ac.api(path, request('round(qty * price, 2)', { qty: '3', price: '12.345' }))
    assert.equal(amount.value, '37.04')
    assert.deepEqual(amount.referencedFields, ['price', 'qty'])
    const large = await ac.api(path, request('amount + 1', { amount: '9007199254740993' }))
    assert.equal(large.value, '9007199254740994')
    return { amount: amount.value, large: large.value }
  })
  await ac.record('空值、空值备用值、文本函数使用实际服务器语义', async () => {
    const empty = await ac.api(path, request('amount * 2', {}, ['amount']))
    assert.equal(empty.value ?? null, null)
    const zero = await ac.api(path, request('coalesce(amount, 0)', {}, ['amount']))
    assert.equal(zero.value, '0')
    const text = await ac.api(path, request("upper(name) || '备选'", { name: 'abc' }))
    assert.equal(text.value, 'ABC备选')
    return { empty: null, fallback: zero.value, text: text.value }
  })
  await ac.record('错误参数被明确拒绝，异常不返回成功试算', async () => {
    const cases = [
      [request('1 / divisor', { divisor: '0' }), /除数.*零/],
      [request('round(1.23, 1.5)'), /整数/],
      [request('amount * 2', { amount: 'abc' }), /数值/],
      [request('amount + 1', { amount: '1e999999999' }), /范围|精度/],
      [request("system('x')"), /函数/],
      [request('1', { hidden: 'x' }, ['allowed']), /未知字段/],
      [request('1', {}, ['duplicate', 'duplicate']), /重复/],
      [
        request(
          '1',
          {},
          Array.from({ length: 201 }, (_, i) => 'f' + i)
        ),
        /200/
      ]
    ]
    for (const [body, message] of cases) {
      const result = await ac.request(path, body)
      assert.notEqual(result.code, 0)
      assert.match(result.msg, message)
    }
    return { rejected: cases.length }
  })
  await ac.record('设计试算仍要求正常登录权限', async () => {
    const result = await ac.request(path, request('1 + 1'), null)
    assert.notEqual(result.code, 0)
    return { code: result.code }
  })
  console.log(JSON.stringify({ output: ac.output, passed: ac.checks.length }))
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
} finally {
  await ac.persist()
}
