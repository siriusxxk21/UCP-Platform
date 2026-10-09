import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅使用正式对象设计及数据维护接口，无隐藏应用，不操作保留的体验样例。
const supplied = process.argv.indexOf('--fixture')
const previous = supplied >= 0 ? JSON.parse(await readFile(resolve(process.argv[supplied + 1]), 'utf8')) : null
const prefix = previous?.prefix || `e2eod${Date.now().toString(36)}`
const output = resolve(previous?.output || process.env.NOCODE_VERIFY_OUTPUT || `.work/object-data-grid/${prefix}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
ac.prefix = prefix
ac.owned = previous?.owned || ac.owned
await mkdir(output, { recursive: true })
await ac.login()
const state = previous || { prefix, output, owned: ac.owned, records: {} }
let browser
async function persist() {
  state.owned = ac.owned
  await writeFile(resolve(output, 'fixture.json'), JSON.stringify(state, null, 2))
  await writeFile(
    resolve(output, 'cleanup-manifest.json'),
    JSON.stringify(
      {
        batchPrefix: `${prefix}_`,
        objects: ac.owned.objects.map(item => ({
          ...item,
          name: [state.target, state.source].find(object => object?.objectId === item.id)?.definition.objectName,
          tableName: `biz_${item.code}`
        })),
        applications: []
      },
      null,
      2
    )
  )
}
const savedPersist = ac.persist.bind(ac)
ac.persist = async () => {
  await savedPersist()
  await persist()
}
try {
  state.target ||= await ac.object('supplier', `数据维护供应商 ${prefix}`, [ac.field('name', 'TEXT', '供应商名称')])
  await persist()
  state.source ||= await ac.object(
    'orders',
    `数据维护订单 ${prefix}`,
    [
      ac.field('name', 'TEXT', '记录名称'),
      ac.field('quantity', 'INTEGER', '数量'),
      ac.field('amount', 'DECIMAL', '精确金额'),
      ac.field('flag', 'BOOLEAN', '已确认'),
      ac.field('note', 'TEXT', '备注'),
      ac.field('double_quantity', 'FORMULA', '翻倍数量')
    ],
    {
      double_quantity: ac.option({
        expression: 'quantity * 2',
        resultType: 'DECIMAL',
        calculation: ac.calculation('LOCAL')
      })
    },
    [
      {
        id: null,
        code: 'supplier',
        name: '供应商',
        kind: 'REFERENCE',
        targetObjectId: state.target.objectId,
        fieldId: null,
        sourceDetailId: null,
        required: false,
        onDelete: 'RESTRICT'
      }
    ]
  )
  state.source.ids.supplier = state.source.definition.relations.find(relation => relation.code === 'supplier').fieldId
  await persist()
  if (process.argv.includes('--setup-only')) {
    console.log(JSON.stringify({ fixture: resolve(output, 'fixture.json'), objects: ac.owned.objects }))
  } else if (process.argv.includes('--visual-only')) {
    browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    await page.addInitScript(token => {
      localStorage.setItem('token', token)
      for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    }, ac.tokens.admin)
    const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
    await page.goto(
      `${origin}/nocode/object/editor?id=${state.source.objectId}&tab=data&fieldId=${state.source.ids.note}`
    )
    const grid = page.getByRole('region', { name: '对象数据维护' })
    const row = grid.locator(`tr[data-row-key="${state.records.source}"]`)
    await expect(row).toBeVisible()
    const rowBox = await row.boundingBox()
    const headerBox = await grid.locator('.ant-table-thead > tr').first().boundingBox()
    assert.ok(rowBox.height >= 30 && rowBox.height <= 36, `数据行高度应为30–36px，实际${rowBox.height}`)
    assert.ok(headerBox.height <= 44, `包含数据库类型的双行表头应保持紧凑，实际${headerBox.height}`)
    await page.screenshot({ path: resolve(output, '05-紧凑对象数据网格.png'), fullPage: true })
    await row.getByRole('button', { name: '编辑', exact: true }).click()
    await row.locator('td.object-data-focused input').fill('仅核对高亮，不保存此修改')
    await expect(row.locator('.changed')).toHaveCSS('background-color', 'rgb(255, 251, 235)')
    await expect(row.locator('td.object-data-focused')).toHaveCSS('background-color', 'rgb(238, 242, 255)')
    await page.screenshot({ path: resolve(output, '04-定位列与修改值高亮.png'), fullPage: true })
    console.log(
      JSON.stringify({
        output,
        rowHeight: rowBox.height,
        headerHeight: headerBox.height,
        visual: '紧凑网格、列定位与未保存修改高亮通过；未提交任何业务写入'
      })
    )
  } else {
    const model = object => ac.api(`/nocode/object-data/model?objectId=${object.objectId}`)
    const context = async object => {
      const result = await model(object)
      return { objectId: object.objectId, versionNo: result.versionNo, checksum: result.checksum }
    }
    const save = async (object, values, row = null) =>
      ac.api('/nocode/object-data/save', {
        ...(await context(object)),
        id: row?.id || null,
        expectedRevision: row?.revision || null,
        values: Object.fromEntries(Object.entries(values).map(([code, value]) => [object.ids[code], value])),
        requestKey: randomUUID()
      })
    const get = (object, id) => ac.api(`/nocode/object-data/get?objectId=${object.objectId}&id=${id}`)
    const pageRows = object =>
      ac.api('/nocode/object-data/page', { objectId: object.objectId, pageNo: 1, pageSize: 20 })
    await ac.record('无应用上下文的已发布模型与只读公式', async () => {
      const result = await model(state.source)
      assert.equal(result.versionNo, 1)
      assert.ok(result.readonlyReasons[state.source.ids.double_quantity])
      assert.equal(ac.owned.applications.length, 0)
    })
    if (
      state.records.target &&
      (await ac.request(`/nocode/object-data/get?objectId=${state.target.objectId}&id=${state.records.target}`))
        .code !== 0
    )
      delete state.records.target
    state.records.target ||= (await save(state.target, { name: `${prefix} 供应商甲` })).record.id
    state.records.source ||= (
      await save(state.source, {
        name: `${prefix} 订单甲`,
        quantity: '12',
        amount: '9007199254740.1234',
        flag: false,
        note: '待编辑备注',
        supplier: state.records.target
      })
    ).record.id
    await save(state.source, { supplier: state.records.target }, (await get(state.source, state.records.source)).record)
    await persist()
    await ac.record('引用名称、布尔和精确小数往返', async () => {
      const result = await get(state.source, state.records.source)
      assert.equal(String(result.record.values[state.source.ids.amount]), '9007199254740.1234')
      assert.equal(result.record.values[state.source.ids.flag], false)
      const selection = await ac.api('/nocode/object-data/selection', {
        objectId: state.source.objectId,
        fieldId: state.source.ids.supplier,
        pageNo: 1,
        pageSize: 10,
        selected: [state.records.target],
        search: ''
      })
      assert.ok(selection.options.some(item => item.value === state.records.target && item.label.includes('供应商甲')))
    })
    await ac.record('局部更新保留其他列及并发旧版本拒绝', async () => {
      const before = (await get(state.source, state.records.source)).record
      const result = await save(state.source, { note: '接口修改' }, before)
      assert.equal(result.record.values[state.source.ids.quantity], before.values[state.source.ids.quantity])
      const stale = await ac.request('/nocode/object-data/save', {
        ...(await context(state.source)),
        id: before.id,
        expectedRevision: before.revision,
        values: { [state.source.ids.note]: '不应覆盖' },
        requestKey: randomUUID()
      })
      assert.notEqual(stale.code, 0)
      assert.equal((await get(state.source, before.id)).record.values[state.source.ids.note], '接口修改')
    })
    await ac.record('删除被引用的供应商展示真实阻断记录', async () => {
      const row = (await get(state.target, state.records.target)).record
      const result = await ac.api('/nocode/object-data/delete-preview', {
        ...(await context(state.target)),
        id: row.id,
        expectedRevision: row.revision
      })
      assert.equal(result.allowed, false)
      assert.ok(result.impacts.some(item => item.recordId === state.records.source && item.action === 'BLOCK'))
    })
    await ac.record('限定冲突样本和按字段筛选', async () => {
      const located = await ac.api('/nocode/object-data/page', {
        objectId: state.source.objectId,
        pageNo: 1,
        pageSize: 10,
        recordIds: [state.records.source]
      })
      assert.equal(located.total, 1)
      const result = await ac.api('/nocode/object-data/page', {
        objectId: state.source.objectId,
        pageNo: 1,
        pageSize: 10,
        conditions: {
          logic: 'AND',
          items: [{ type: 'condition', field: state.source.ids.note, operator: 'eq', value: '接口修改' }]
        }
      })
      assert.equal(result.total, 1)
    })

    browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(token => {
      localStorage.setItem('token', token)
      for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    }, ac.tokens.admin)
    const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
    await page.goto(
      `${origin}/nocode/object/editor?id=${state.source.objectId}&tab=data&fieldId=${state.source.ids.note}&recordIds=${state.records.source}`
    )
    const grid = page.getByRole('region', { name: '对象数据维护' })
    await expect(grid.getByText('已定位列：')).toBeVisible()
    const row = grid.locator(`tr[data-row-key="${state.records.source}"]`)
    const cell = (record, code) =>
      record.locator('td').nth(2 + state.source.definition.fields.findIndex(field => field.code === code))
    await ac.record('浏览器数据页定位和取消行编辑', async () => {
      await expect(row).toBeVisible()
      await expect(grid.locator('thead').first()).toContainText('记录名称 *')
      await expect(grid.locator('thead').first()).not.toContainText('=>')
      await row.getByRole('button', { name: '编辑', exact: true }).click()
      await expect(cell(row, 'supplier_id').locator('.ant-select')).toBeVisible()
      await expect(cell(row, 'supplier_id')).toContainText(`${prefix} 供应商甲`)
      await cell(row, 'note').locator('input').fill('取消后不保存')
      await row.getByRole('button', { name: '取消', exact: true }).click()
      await page.getByRole('button', { name: '放弃修改', exact: true }).click()
      assert.equal((await get(state.source, state.records.source)).record.values[state.source.ids.note], '接口修改')
    })
    await ac.record('浏览器并发冲突保留输入并不覆盖新数据', async () => {
      await row.getByRole('button', { name: '编辑', exact: true }).click()
      await cell(row, 'note').locator('input').fill('浏览器未保存输入')
      await save(state.source, { note: '另一个会话已更新' }, (await get(state.source, state.records.source)).record)
      await row.getByRole('button', { name: '保存本行', exact: true }).click()
      await expect(grid.getByText(/当前行输入已保留/)).toBeVisible()
      await expect(cell(row, 'note').locator('input')).toHaveValue('浏览器未保存输入')
      assert.equal(
        (await get(state.source, state.records.source)).record.values[state.source.ids.note],
        '另一个会话已更新'
      )
      await page.screenshot({ path: resolve(output, '01-并发失败保留输入.png'), fullPage: true })
      await row.getByRole('button', { name: '取消', exact: true }).click()
      await page.getByRole('button', { name: '放弃修改', exact: true }).click()
    })
    await ac.record('浏览器成功保存行并保留其他列', async () => {
      await row.getByRole('button', { name: '编辑', exact: true }).click()
      await cell(row, 'note').locator('input').fill('浏览器保存成功')
      await row.getByRole('button', { name: '保存本行', exact: true }).click()
      await expect(cell(row, 'note')).toContainText('浏览器保存成功')
      assert.equal(
        (await get(state.source, state.records.source)).record.values[state.source.ids.supplier],
        state.records.target
      )
    })
    await ac.record('浏览器新增一行', async () => {
      await grid.getByRole('button', { name: '查看全部记录', exact: true }).click()
      await grid.getByRole('button', { name: '新增一行', exact: true }).click()
      const fresh = grid.locator('tr[data-row-key="__new__"]')
      await cell(fresh, 'name').locator('input').fill(`${prefix} 浏览器新增`)
      await cell(fresh, 'quantity').locator('input').fill('8')
      await fresh.getByRole('button', { name: '保存本行', exact: true }).click()
      await expect(fresh).toHaveCount(0)
      const result = await pageRows(state.source)
      assert.ok(result.list.some(item => item.values[state.source.ids.name] === `${prefix} 浏览器新增`))
      await page.screenshot({ path: resolve(output, '02-数据网格新增修改.png'), fullPage: true })
    })
    await ac.record('浏览器删除影响阻断', async () => {
      await page.goto(`${origin}/nocode/object/editor?id=${state.target.objectId}&tab=data`)
      const target = page
        .getByRole('region', { name: '对象数据维护' })
        .locator(`tr[data-row-key="${state.records.target}"]`)
      await target.getByRole('button', { name: '删除', exact: true }).click()
      const modal = page
        .locator('.ant-modal-content:visible')
        .filter({ has: page.getByText('检查删除影响', { exact: true }) })
      await expect(modal.getByRole('button', { name: '确认删除', exact: true })).toBeDisabled()
      await expect(modal.locator('strong').filter({ hasText: `${prefix} 订单甲` })).toBeVisible()
      await page.screenshot({ path: resolve(output, '03-关联删除阻断.png'), fullPage: true })
      await modal.getByRole('button', { name: /^取\s*消$/ }).click()
    })
    await ac.record('清除引用后可以预检并删除，执行要求影响凭据', async () => {
      await save(state.source, { supplier: null }, (await get(state.source, state.records.source)).record)
      const row = (await get(state.target, state.records.target)).record
      const command = { ...(await context(state.target)), id: row.id, expectedRevision: row.revision }
      const preview = await ac.api('/nocode/object-data/delete-preview', command)
      assert.equal(preview.allowed, true)
      assert.ok(preview.impactToken)
      assert.notEqual((await ac.request('/nocode/object-data/delete', command)).code, 0)
      await ac.api('/nocode/object-data/delete', { ...command, impactToken: preview.impactToken })
      assert.equal((await pageRows(state.target)).total, 0)
    })
    assert.deepEqual(errors, [])
    await persist()
    console.log(JSON.stringify({ output, passed: ac.checks.length, fixture: resolve(output, 'fixture.json') }))
  }
} finally {
  await browser?.close()
  ac.tokens = {}
  ac.passwords = {}
  await persist()
}
