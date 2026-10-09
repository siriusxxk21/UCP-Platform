import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 使用正常登录配置；不把凭据、认证响应或存储态写进验收产物。
const { values } = parseArgs({
  options: {
    fixture: { type: 'string' },
    output: { type: 'string' },
    smoke: { type: 'boolean' },
    'ready-only': { type: 'boolean' }
  }
})
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const api = process.env.NOCODE_VERIFY_API || 'http://127.0.0.1:8080/api'
const output = resolve(
  values.output || `.work/ordered-calibration-ui/${new Date().toISOString().replace(/[:.]/g, '-')}`
)
const ac = new FormAcceptance(api, output)
if (existsSync(resolve(output, 'result.json'))) throw new Error('输出目录已有验收记录，请使用新目录')
const checks = [],
  errors = [],
  commands = []
let browser, page, failure, releaseResponse, calibrationPreview

try {
  await mkdir(output, { recursive: true })
  await ac.login()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (request.method() === 'POST' && /\/calculation\/(calibrate|resume|retry|pause)$/.test(request.url()))
      commands.push({ action: request.url().split('/').at(-1), ...request.postDataJSON() })
  })
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      for (const [key, value] of Object.entries({
        userInfo: info.user,
        permissions: info.permissions,
        roles: info.roles,
        menus: info.menus
      }))
        localStorage.setItem(key, JSON.stringify(value))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  // 新建页仅作读取，不保存草稿，不访问或改写既有业务对象。
  await page.goto(origin + '/nocode/object/editor')
  await expect(page.locator('.object-editor h2')).toHaveText('新建数据对象')
  await expect(page.locator('.field-designer')).toBeVisible()
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  await page.screenshot({ path: resolve(output, '01-editor-smoke.png'), fullPage: true, animations: 'disabled' })
  checks.push('正常登录后真实Chrome载入对象设计器，前端资产无Vite错误')
  if (!values.smoke) {
    assert.ok(values.fixture, '完整校准验收必须提供本轮fixture manifest，不能选择既有业务对象')
    const fixture = JSON.parse(await readFile(resolve(values.fixture), 'utf8'))
    assert.ok(fixture.objectId && fixture.objectCode && fixture.prefix, 'fixture缺少对象身份与当次前缀')
    const design = await ac.api('/nocode/design/get?id=' + fixture.objectId)
    assert.equal(design.draft.objectCode, fixture.objectCode)
    assert.ok(fixture.objectCode.startsWith(fixture.prefix), '只允许当次登记的测试对象')
    await page.goto(origin + '/nocode/object/editor?id=' + fixture.objectId)
    await page.getByRole('tab', { name: '对象数据', exact: true }).click()
    await expect(page.getByRole('button', { name: '校准有序计算', exact: true })).toBeEnabled()
    checks.push('本轮fixture对象身份核对通过，管理员有序校准入口可见')

    const statusPath = '/nocode/object-data/calculation/status?objectId=' + fixture.objectId
    const initialStates = await ac.api(statusPath)
    assert.ok(
      initialStates.length > 0 &&
        initialStates.every(state => state.state === (values['ready-only'] ? 'READY' : 'PENDING'))
    )
    const model = await ac.api('/nocode/object-data/model?objectId=' + fixture.objectId)
    const fields = model.model.object.fields.filter(field => initialStates.some(state => state.fieldId === field.id))
    const numeric = fields.find(field =>
      ['MONEY', 'DECIMAL', 'INTEGER'].includes(model.model.object.fieldOptions[field.id]?.resultType)
    )
    assert.ok(numeric, '夹具需包含一个数值有序计算')

    if (!values['ready-only']) {
      const openCalibration = async () => {
        await page.getByRole('button', { name: '校准有序计算', exact: true }).click()
        const dialog = page.locator('.ant-modal-content:visible').filter({ hasText: '校准有序计算数据' })
        await expect(dialog).toBeVisible()
        await expect(dialog.getByRole('button', { name: '刷新状态', exact: true })).not.toHaveClass(/ant-btn-loading/)
        return dialog
      }
      let dialog = await openCalibration()
      const previewResponse = page.waitForResponse(response =>
        response.url().endsWith('/calculation/calibrate-preview')
      )
      await dialog.getByRole('button', { name: '预览校准范围', exact: true }).click()
      const preview = await (await previewResponse).json()
      calibrationPreview = preview.data
      assert.equal(preview.code, 0)
      assert.equal(preview.data.fields.length, fields.length)
      assert.ok(
        preview.data.fields.some(field => field.groups > 1),
        '夹具至少需2组以验证分批与关闭'
      )
      assert.ok(preview.data.fields.some(field => field.changedRows > 0))
      assert.ok(preview.data.fields.every(field => field.changedRows === field.fillRows + field.incorrectRows))
      await expect(dialog).toContainText('合法空结果')
      await expect(dialog.getByRole('button', { name: '确认并开始校准', exact: true })).toBeDisabled()
      await page.screenshot({ path: resolve(output, '02-preview.png'), fullPage: true, animations: 'disabled' })
      checks.push('PENDING预览展示真实差异、合法空值，未确认维护前不能开始')

      // 转发真实请求后暂缓浏览器接收响应，确定性验证“关闭不继续下一批”。
      let committed, forwardingFailure
      const released = new Promise(resolveRelease => {
        releaseResponse = resolveRelease
      })
      await page.route(
        '**/calculation/calibrate',
        async route => {
          try {
            const response = await route.fetch()
            committed = await response.json()
            await released
            await route.fulfill({ response })
          } catch (error) {
            forwardingFailure = error
            await route.abort('failed').catch(() => {})
          }
        },
        { times: 1 }
      )
      await dialog.getByRole('checkbox').check()
      await dialog.getByRole('button', { name: '确认并开始校准', exact: true }).click()
      await expect.poll(() => !!committed || !!forwardingFailure).toBe(true)
      if (forwardingFailure) throw forwardingFailure
      assert.equal(committed.code, 0)
      assert.equal(committed.data.complete, false)
      await dialog.getByRole('button', { name: '关闭并保留进度', exact: true }).click()
      releaseResponse()
      await expect(dialog).toBeHidden()
      const suspended = await ac.api(statusPath)
      assert.ok(suspended.some(state => state.state === 'BACKFILLING'))
      assert.equal(commands.filter(command => command.action === 'resume' || command.action === 'pause').length, 0)
      checks.push('首批真实提交后关闭，不追加resume或pause，服务端保留BACKFILLING与批次游标')

      dialog = await openCalibration()
      await expect(dialog.getByRole('button', { name: '继续此批次', exact: true })).toBeEnabled()
      const pausedResponse = page.waitForResponse(response => response.url().endsWith('/calculation/pause'))
      await dialog.getByRole('button', { name: '暂停此批次', exact: true }).click()
      assert.equal((await (await pausedResponse).json()).code, 0)
      await expect(dialog).toContainText('管理员已暂停')
      const paused = await ac.api(statusPath)
      assert.ok(paused.every(state => state.state === 'READY' || state.state === 'FAILED'))
      assert.ok(paused.every(state => state.cursor.requestId === commands[0].requestId))
      await page.screenshot({ path: resolve(output, '03-paused.png'), fullPage: true, animations: 'disabled' })
      checks.push('重开识别同一固定批次，显式暂停保存可恢复状态，保留已有进度')

      // 仅模拟一次未到达后端的网络失败，随后仍由真实接口恢复原批次。
      await page.route('**/calculation/retry', route => route.abort('failed'), { times: 1 })
      await dialog.getByRole('button', { name: '重试或继续此批次', exact: true }).click()
      await expect(dialog).toContainText('进度保存在服务端')
      await expect(dialog.getByRole('button', { name: '重试或继续此批次', exact: true })).toBeEnabled()
      await dialog.getByRole('button', { name: '重试或继续此批次', exact: true }).click()
      await expect
        .poll(async () => (await ac.api(statusPath)).every(state => state.state === 'READY'), {
          timeout: 60000
        })
        .toBe(true)
      await expect(dialog.getByText('有序计算尚未全部就绪', { exact: true })).toHaveCount(0)
      await expect(dialog.getByRole('button', { name: '确认并开始校准', exact: true })).toBeDisabled()
      assert.ok(commands.every(command => command.requestId === commands[0].requestId))
      assert.ok(commands.every(command => command.maxGroups === 1))
      assert.ok(
        commands.every(
          command => command.versionNo === commands[0].versionNo && command.checksum === commands[0].checksum
        )
      )
      await page.screenshot({ path: resolve(output, '04-ready.png'), fullPage: true, animations: 'disabled' })
      checks.push('网络失败后保留原批次重试，连续分批至全部READY；版本、摘要、操作标识始终一致')

      await dialog.getByRole('button', { name: /^关\s*闭$/ }).click()
    } else {
      checks.push('只读续验：确认同一专用fixture全部READY，不重置状态、不重复校准')
    }
    const grid = page.locator('.object-data-grid')
    await expect(grid.getByRole('button', { name: '校准有序计算', exact: true })).toBeEnabled()
    const records = await ac.api('/nocode/object-data/page', { objectId: fixture.objectId, pageNo: 1, pageSize: 20 })
    const sample = records.list.find(row => row.values[numeric.id] != null)
    assert.ok(sample, '数值结果应有可查询的非空记录')
    await grid.locator('.filter-field .ant-select-selector').click()
    const popup = page.locator('.ant-select-dropdown:visible')
    const option = popup.locator('.ant-select-item-option').filter({ hasText: new RegExp(`^${numeric.name}$`) })
    for (let attempt = 0; attempt < 4 && !(await option.count()); attempt++) {
      await popup.locator('.rc-virtual-list-holder').evaluate(element => {
        element.scrollTop += 200
      })
    }
    await option.click()
    await grid.locator('.filter-value').getByRole('spinbutton').fill(String(sample.values[numeric.id]))
    const queryResponse = page.waitForResponse(
      response => response.url().endsWith('/nocode/object-data/page') && response.request().method() === 'POST'
    )
    await grid.getByRole('button', { name: '查询', exact: true }).click()
    const queried = await (await queryResponse).json()
    assert.equal(queried.code, 0)
    assert.ok(queried.data.list.length > 0)
    assert.ok(queried.data.list.every(row => row.values[numeric.id] === sample.values[numeric.id]))
    await page.screenshot({ path: resolve(output, '05-number-filter.png'), fullPage: true, animations: 'disabled' })
    checks.push('READY后有序数值字段进入筛选候选，真实数值控件提交筛选并回读相符记录')
    await writeFile(
      resolve(output, 'calibration.json'),
      JSON.stringify(
        {
          fixture: { objectId: fixture.objectId, objectCode: fixture.objectCode },
          preview: calibrationPreview,
          commands,
          states: await ac.api(statusPath)
        },
        null,
        2
      )
    )
  }
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  releaseResponse?.()
  await browser?.close()
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(resolve(output, 'commands.json'), JSON.stringify(commands, null, 2))
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify({ checks, errors, failure: failure?.message }, null, 2)
  )
  console.log(JSON.stringify({ output, checks: checks.length, failure: failure?.message }))
}
if (failure) throw failure
