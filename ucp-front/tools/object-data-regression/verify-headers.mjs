import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅检查已有对象的表头和表格交互，不新增夹具、不进入行编辑、不提交数据写入。
const objectId = process.argv[process.argv.indexOf('--object-id') + 1]
assert.match(objectId || '', /^\d+$/, '请通过 --object-id 指定可只读验收的已发布对象')
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || `.work/object-data-headers/${objectId}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
await ac.login()
const model = await ac.api(`/nocode/object-data/model?objectId=${objectId}`)
assert.ok(model.columnTypes?.__id, '接口应返回当前物理主键类型')
const browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
try {
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  const errors = []
  const writes = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (/\/nocode\/object-data\/(save|delete)(\?|$)/.test(request.url())) writes.push(request.url())
  })
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, ac.tokens.admin)
  const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
  const firstPage = page.waitForResponse(response => response.url().includes('/nocode/object-data/page'))
  await page.goto(`${origin}/nocode/object/editor?id=${objectId}&tab=data`)
  const grid = page.getByRole('region', { name: '对象数据维护' })
  const headers = grid.locator('.ant-table-thead')
  await expect(headers.getByText('记录 ID', { exact: true })).toBeVisible()
  assert.equal((await (await firstPage).json()).code, 0, '对象数据应读取成功')
  await expect(grid.locator('.ant-spin-spinning')).toHaveCount(0)
  const displayed = [{ id: '__id', name: '记录 ID' }].concat(
    model.model.object.fields.map(field => ({
      id: field.id || field.key,
      name: field.name + (field.required ? ' *' : '')
    }))
  )
  for (const field of displayed) {
    const title = headers.locator('.data-column-title').filter({ has: page.getByText(field.name, { exact: true }) })
    await expect(title.locator('small')).toHaveText(model.columnTypes[field.id] || '物理列未找到')
    await expect(title.locator('small')).toHaveAttribute(
      'title',
      `数据库实际类型：${model.columnTypes[field.id] || '物理列未找到'}`
    )
  }
  const headerBox = await headers.locator('tr').first().boundingBox()
  assert.ok(headerBox.height <= 44, `双行表头应保持紧凑，实际 ${headerBox.height}px`)
  const rows = grid.locator('tr[data-row-key]')
  let rowHeight = null
  if (await rows.count()) {
    rowHeight = (await rows.first().boundingBox()).height
    assert.ok(rowHeight >= 30 && rowHeight <= 36, `数据行高度应保持 30–36px，实际 ${rowHeight}px`)
  }
  await page.screenshot({ path: resolve(output, '01-数据库实际类型表头.png'), fullPage: true })

  // 表头展示走插槽，列设置仍使用可读名称；显隐只改变当前浏览器偏好。
  await grid.locator('button[title="列设置"]').click()
  const settings = page.locator('.la-column-setting-panel')
  const field = displayed[1]
  await expect(settings.getByText(field.name, { exact: true })).toBeVisible()
  assert.ok(!(await settings.innerText()).includes('=>'), '列设置不应出现渲染函数源码')
  await settings.getByRole('checkbox', { name: field.name, exact: true }).uncheck()
  await expect(headers.getByText(field.name, { exact: true })).toHaveCount(0)
  await settings.getByRole('checkbox', { name: field.name, exact: true }).check()
  await expect(headers.getByText(field.name, { exact: true })).toBeVisible()
  await grid.locator('button[title="列设置"]').click()

  const header = headers.locator('th').filter({ has: page.getByText(field.name, { exact: true }) })
  await expect(headers.locator('.ant-table-column-sorter')).toHaveCount(0)
  const beforeWidth = (await header.boundingBox()).width
  const handle = await header.locator('.la-resize-handle').boundingBox()
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2)
  await page.mouse.down()
  await page.mouse.move(handle.x + handle.width / 2 + 80, handle.y + handle.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect.poll(async () => (await header.boundingBox()).width).not.toBe(beforeWidth)
  await expect(header.locator('small')).toHaveText(model.columnTypes[field.id] || '物理列未找到')
  assert.deepEqual(errors, [], '页面不应有脚本异常')
  assert.deepEqual(writes, [], '只读验收不能提交数据写入')
  const result = {
    objectId,
    headerHeight: headerBox.height,
    rowHeight,
    columns: displayed.length,
    columnTypes: model.columnTypes,
    checks: [
      '实际数据库类型逐列匹配',
      '紧凑双行表头',
      '列设置名称与显隐',
      '无列排序入口',
      '拖动列宽',
      '无业务写入',
      '无页面异常'
    ]
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ ...result, output }))
} finally {
  await browser.close()
}
