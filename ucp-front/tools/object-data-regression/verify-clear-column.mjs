import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 两条清空路径使用独立列；仅本批精确夹具允许写入，不触碰用户体验对象。
const argument = process.argv.indexOf('--fixture')
const previous = argument < 0 ? null : JSON.parse(await readFile(resolve(process.argv[argument + 1]), 'utf8'))
const prefix = previous?.prefix || `e2efc${Date.now().toString(36)}${randomBytes(2).toString('hex')}`
const output = resolve(previous?.output || `.work/clear-column/${prefix}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
ac.prefix = prefix
ac.owned = previous?.owned || ac.owned
const state = previous || { prefix, output, owned: ac.owned, records: [], checks: [] }
await mkdir(output, { recursive: true })
await ac.login()
async function persist() {
  state.owned = ac.owned
  await writeFile(resolve(output, 'fixture.json'), JSON.stringify(state, null, 2))
  await writeFile(
    resolve(output, 'cleanup-manifest.json'),
    JSON.stringify(
      {
        batchPrefix: prefix + '_',
        applications: [],
        objects: ac.owned.objects.map(object => ({
          ...object,
          name: '表单验收字段转换 ' + prefix,
          tableName: 'biz_' + object.code
        }))
      },
      null,
      2
    )
  )
}
ac.persist = persist
async function check(name, detail) {
  state.checks = state.checks.filter(item => item.name !== name)
  state.checks.push({ name, detail })
  await persist()
}
const design = () => ac.api(`/nocode/design/get?id=${state.source.objectId}`)
const model = () => ac.api(`/nocode/object-data/model?objectId=${state.source.objectId}`)
const rows = () => ac.api('/nocode/object-data/page', { objectId: state.source.objectId, pageNo: 1, pageSize: 100 })
const columnBody = (current, fieldId) => ({
  objectId: state.source.objectId,
  versionNo: current.versionNo,
  checksum: current.checksum,
  fieldId
})
let browser, page, failure
const errors = []
try {
  state.source ||= await ac.object('source', '字段转换 ' + prefix, [
    ac.field('name', 'TEXT', '记录名称'),
    ac.field('clear_target', 'TEXT', '网格待清空'),
    ac.field('convert_target', 'TEXT', '待清空转换'),
    ac.field('keep_note', 'TEXT', '保留备注')
  ])
  await persist()
  const source = state.source
  if (!state.seeded) {
    const current = await model()
    for (let index = state.records.length; index < 3; index++) {
      const result = await ac.api('/nocode/object-data/save', {
        objectId: source.objectId,
        versionNo: current.versionNo,
        checksum: current.checksum,
        id: null,
        expectedRevision: null,
        requestKey: randomUUID(),
        values: ac.values(source, {
          name: `整列验收记录 ${index + 1}`,
          clear_target: `网格旧值 ${index + 1}`,
          convert_target: `非数字 ${index + 1}`,
          keep_note: `必须保留 ${index + 1}`
        })
      })
      state.records.push(result.record)
      await persist()
    }
    state.seeded = true
    await persist()
  }
  if (!state.deleted) {
    const current = await model()
    const row = state.records[2]
    const body = {
      objectId: source.objectId,
      versionNo: current.versionNo,
      checksum: current.checksum,
      id: row.id,
      expectedRevision: row.revision
    }
    const preview = await ac.api('/nocode/object-data/delete-preview', body)
    assert.equal(preview.allowed, true)
    await ac.api('/nocode/object-data/delete', { ...body, impactToken: preview.impactToken })
    state.deleted = true
    await persist()
  }
  if (process.argv.includes('--setup-only')) {
    console.log(JSON.stringify({ objectId: source.objectId, fixture: resolve(output, 'fixture.json') }))
  } else {
    browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
    page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(token => {
      localStorage.setItem('token', token)
      for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    }, ac.tokens.admin)
    const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
    const dialog = title =>
      page
        .locator('.ant-modal-content:visible, .ant-drawer-content:visible')
        .filter({ has: page.locator('.ant-modal-title, .ant-drawer-title').filter({ hasText: title }) })
    const shot = name =>
      page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled' })
    if (!state.gridCleared) {
      const beforeDesign = await design()
      await page.goto(`${origin}/nocode/object/editor?id=${source.objectId}&tab=data`)
      const grid = page.getByRole('region', { name: '对象数据维护' })
      await expect(grid.locator('.ant-table-row')).toHaveCount(2)
      await grid.getByPlaceholder('输入标题关键词').fill('整列验收记录 1')
      const filtered = page.waitForResponse(response => response.url().includes('/nocode/object-data/page'))
      await grid.getByRole('button', { name: /查询/ }).click()
      assert.equal((await (await filtered).json()).data.total, 1)
      await grid.getByRole('button', { name: '清空整列', exact: true }).click()
      const clear = dialog(/^清空整列$/)
      async function selectColumn(name) {
        await clear.locator('.ant-select').click()
        const response = page.waitForResponse(item => item.url().includes('/nocode/object-data/clear-column-preview'))
        await page.locator('.ant-select-dropdown:visible').getByText(`主表 · ${name}`, { exact: true }).click()
        const result = await (await response).json()
        assert.equal(result.code, 0)
        return result.data
      }
      const blocked = await selectColumn('记录名称')
      assert.equal(blocked.allowed, false)
      await expect(clear).toContainText('必填')
      await expect(clear.getByRole('button', { name: /清空此列/ })).toBeDisabled()
      await check('必填列展示具体阻断，不能清空', { blockers: blocked.blockers })
      const preview = await selectColumn('网格待清空')
      assert.equal(preview.allowed, true)
      assert.equal(preview.activeRows, 2)
      assert.equal(preview.deletedRows, 1)
      await expect(clear).toContainText('当前筛选、分页或定位记录不会缩小范围')
      await expect(clear.getByRole('button', { name: '清空此列 3 个值', exact: true })).toBeEnabled()
      await shot('01-清空整列预检包含逻辑删除与完整范围')
      const current = await model()
      const row = await ac.api(`/nocode/object-data/get?objectId=${source.objectId}&id=${state.records[0].id}`)
      await ac.api('/nocode/object-data/save', {
        objectId: source.objectId,
        versionNo: current.versionNo,
        checksum: current.checksum,
        id: row.record.id,
        expectedRevision: row.record.revision,
        requestKey: randomUUID(),
        values: { [source.ids.clear_target]: '模拟预检后并发修改' }
      })
      const stale = page.waitForResponse(response =>
        new URL(response.url()).pathname.endsWith('/nocode/object-data/clear-column')
      )
      await clear.getByRole('button', { name: '清空此列 3 个值', exact: true }).click()
      const rejected = await (await stale).json()
      assert.notEqual(rejected.code, 0)
      await expect(clear).toContainText(/请重新检查.*确认/)
      await expect(clear.getByRole('button', { name: '清空此列', exact: true })).toBeDisabled()
      assert.ok((await rows()).list.every(record => record.values[source.ids.clear_target] != null))
      await shot('02-预检后数据变化拒绝并要求重新确认')
      await check('预检后列数据变化拒绝执行，保留弹窗与全部旧值', { message: rejected.msg })
      await clear.getByRole('button', { name: '重新检查', exact: true }).click()
      await expect(clear.getByRole('button', { name: '清空此列 3 个值', exact: true })).toBeEnabled()
      const execution = page.waitForResponse(response =>
        new URL(response.url()).pathname.endsWith('/nocode/object-data/clear-column')
      )
      await clear.getByRole('button', { name: '清空此列 3 个值', exact: true }).click()
      const result = await (await execution).json()
      assert.equal(result.code, 0)
      assert.deepEqual(result.data, { clearedActiveRows: 2, clearedDeletedRows: 1 })
      await expect(clear).toHaveCount(0)
      assert.deepEqual(await design(), beforeDesign)
      const empty = await ac.api(
        '/nocode/object-data/clear-column-preview',
        columnBody(await model(), source.ids.clear_target)
      )
      assert.equal(empty.activeRows + empty.deletedRows, 0)
      state.gridCleared = true
      await check('网格立即清空正常2值和逻辑删除1值；结构不变', result.data)
    }
    if (!state.conversionDraft) {
      let current = await design()
      if (current.draft.state !== 'DRAFT') {
        await ac.api('/nocode/design/edit', {
          id: source.objectId,
          expectedLockVersion: current.draft.lockVersion,
          reason: '整列清空体验：创建转换草稿'
        })
        current = await design()
      }
      await page.goto(`${origin}/nocode/object/editor?id=${source.objectId}`)
      const beforeRows = await rows()
      const targetIndex = current.draft.fields.findIndex(field => field.code === 'convert_target')
      const row = page
        .locator('tr')
        .filter({ has: page.getByRole('textbox', { name: `第 ${targetIndex + 1} 行字段名称`, exact: true }) })
      await row.getByRole('button', { name: /配置/ }).click()
      const field = dialog(/^字段配置$/)
      await field
        .locator('.ant-form-item')
        .filter({ has: page.locator('label').filter({ hasText: /^字段类型$/ }) })
        .locator('.ant-select')
        .click()
      await page.locator('.ant-select-dropdown:visible').getByText('整数', { exact: true }).click()
      const review = dialog(/^检查字段变更/)
      await expect(review).toContainText('3 个值')
      await review.getByRole('checkbox', { name: /选择发布时清空本列 3 个值并转换/ }).check()
      await review.getByRole('button', { name: '确认变更方案', exact: true }).click()
      await field.getByRole('button', { name: '应用到草稿', exact: true }).click()
      const saved = page.waitForResponse(response => response.url().includes('/nocode/design/save'))
      await page.getByRole('button', { name: /保存草稿/ }).click()
      assert.equal((await (await saved).json()).code, 0)
      assert.deepEqual(await rows(), beforeRows)
      const unchanged = await ac.api(
        '/nocode/object-data/clear-column-preview',
        columnBody(await model(), source.ids.convert_target)
      )
      assert.equal(unchanged.activeRows, 2)
      assert.equal(unchanged.deletedRows, 1)
      state.conversionDraft = true
      await check('选择发布清空并保存草稿，正常与逻辑删除旧值均未提前清除', {
        activeRows: unchanged.activeRows,
        deletedRows: unchanged.deletedRows
      })
    }
    if (!state.conversionPublished) {
      await page.goto(`${origin}/nocode/object/editor?id=${source.objectId}`)
      await page.getByRole('button', { name: /发\s*布$/ }).click()
      const publish = dialog(/^发布确认$/)
      await expect(publish).toContainText('3 个')
      await expect(publish.getByRole('checkbox')).toHaveCount(0)
      await publish.locator('textarea').fill('最终一次确认：清空此列正常与逻辑删除旧值并转整数')
      const action = publish.getByRole('button', { name: /清空.*3.*发布/ })
      await expect(action).toBeEnabled()
      await shot('03-发布仅一次最终确认无需重复勾选')
      const executed = page.waitForResponse(response => response.url().includes('/nocode/design/execute'))
      await action.click()
      const result = await (await executed).json()
      assert.equal(result.code, 0)
      assert.equal(result.data.state, 'SUCCEEDED')
      const body = (await executed).request().postDataJSON()
      assert.deepEqual(body.clearFieldIds, [source.ids.convert_target])
      state.conversionPublished = true
      await persist()
    }
    const finalModel = await model()
    const finalRows = await rows()
    assert.equal(finalRows.total, 2)
    assert.equal(finalModel.columnTypes[source.ids.convert_target], 'bigint')
    assert.match(finalModel.columnTypes[source.ids.clear_target], /character varying|varchar/)
    for (const record of finalRows.list) {
      const original = state.records.find(item => item.id === record.id)
      assert.ok(original)
      assert.equal(record.values[source.ids.name], original.values[source.ids.name])
      assert.equal(record.values[source.ids.keep_note], original.values[source.ids.keep_note])
      assert.equal(record.values[source.ids.clear_target], null)
      assert.equal(record.values[source.ids.convert_target], null)
    }
    const finalPreview = await ac.api(
      '/nocode/object-data/clear-column-preview',
      columnBody(finalModel, source.ids.convert_target)
    )
    assert.equal(finalPreview.activeRows + finalPreview.deletedRows, 0)
    await page.goto(`${origin}/nocode/object/editor?id=${source.objectId}&tab=data`)
    await expect(page.getByRole('region', { name: '对象数据维护' })).toContainText('必须保留 1')
    await shot('04-两种清空路径均保留记录及其他列')
    assert.deepEqual(errors, [])
    await check('最终发布清空3值并转bigint，整行及其他列保留，逻辑删除值也清空', {
      activeRecords: finalRows.total,
      affectedIncludingDeleted: 3,
      physicalType: finalModel.columnTypes[source.ids.convert_target]
    })
    await writeFile(
      resolve(output, 'result.json'),
      JSON.stringify(
        {
          time: new Date().toISOString(),
          passed: true,
          objectId: source.objectId,
          checks: state.checks,
          pageErrors: errors
        },
        null,
        2
      )
    )
    console.log(JSON.stringify({ passed: true, objectId: source.objectId, output, checks: state.checks.length }))
  }
} catch (error) {
  failure = error
  await page
    ?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true, animations: 'disabled' })
    .catch(() => {})
  await writeFile(
    resolve(output, 'failure.json'),
    JSON.stringify(
      { time: new Date().toISOString(), message: error.message, checks: state.checks, pageErrors: errors },
      null,
      2
    )
  )
} finally {
  await browser?.close()
  await persist()
}
if (failure) throw failure
