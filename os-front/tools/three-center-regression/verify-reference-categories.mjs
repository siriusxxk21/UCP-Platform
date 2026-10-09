import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 复用当前开发服务和真实分类；仅修改本次登记的唯一前缀应用，已有对象只读引用。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/reference-categories', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const checks = [],
  errors = [],
  snapshots = {}
let browser, page, failure
const query = params =>
  ac.api('/nocode/design/page?' + new URLSearchParams({ pageNo: '1', pageSize: '10', status: 'ACTIVE', ...params }))
const categoryLabel = category =>
  !category ? '未分类' : ['未分类', '全部分类'].includes(category) ? `${category}（自定义）` : category

try {
  await ac.login()
  const categories = await ac.api('/nocode/design/categories')
  const initial = await query({})
  assert.ok(initial.total > 20, '当前开发库需超过 20 个已发布对象来验证分页与内部滚动')
  assert.ok(initial.list.every(row => row.publishedVersion != null))
  const categoryData = []
  for (const category of categories) categoryData.push({ category, result: await query({ category }) })
  const populated = categoryData.find(item => item.result.total > 0)
  const empty = categoryData.find(item => item.result.total === 0)
  assert.ok(populated, '需要至少一个有已发布对象的分类')
  const uncategorized = await query({ category: '' })
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_reference_categories',
    name: '引用分类验收 ' + ac.prefix,
    description: '数据对象只读引用；本次浏览器验收专用草稿',
    definition: { objects: [], resources: [] }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1600, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
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
  await page.goto(`${origin}/nocode-app/workspace?id=${ac.app.application.id}`)
  await page.getByRole('button', { name: /引用对象$/ }).click()
  const picker = page.locator('.ant-modal-content:visible').filter({ hasText: '引用已发布对象' })
  const tree = picker.locator('.nocode-category-panel')
  const table = picker.locator('.reference-picker-table')
  const rows = table.locator('tbody tr[data-row-key]')
  const search = picker.getByRole('textbox', { name: '查询可引用对象' })
  const done = picker.getByRole('button', { name: '完成选择', exact: true })
  const screenshot = name =>
    page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled' })
  const assertRows = async expected => {
    await expect(rows).toHaveCount(expected.list.filter(row => row.publishedVersion != null).length)
    await expect
      .poll(() => rows.evaluateAll(elements => elements.map(element => element.dataset.rowKey)))
      .toEqual(expected.list.filter(row => row.publishedVersion != null).map(row => row.id))
  }
  const selectCategory = async category => {
    const response = page.waitForResponse(response => {
      const url = new URL(response.url())
      return (
        url.pathname.endsWith('/nocode/design/page') &&
        (category === undefined ? !url.searchParams.has('category') : url.searchParams.get('category') === category)
      )
    })
    await tree.getByText(category === undefined ? '全部分类' : categoryLabel(category), { exact: true }).click()
    assert.equal((await (await response).json()).code, 0)
  }
  await assertRows(initial)
  await expect(tree.getByText('全部分类', { exact: true })).toBeVisible()
  await expect(done).toBeInViewport()
  await expect
    .poll(() =>
      picker.locator('.reference-picker').evaluate(element => Math.round(element.getBoundingClientRect().height))
    )
    .toBe(520)
  await screenshot('01-all-categories')
  checks.push('初始真实列表 10 条；分类树与对象表并列；宽屏内容区固定 520px')

  await table.locator('.ant-pagination-item-2').click()
  await assertRows(await query({ pageNo: '2' }))
  await expect(rows.first().locator('td').first()).toHaveText('11')
  await table.locator('.ant-pagination-options .ant-select').click()
  await page.locator('.ant-select-dropdown:visible').getByText('20 条/页', { exact: true }).click()
  await assertRows(await query({ pageSize: '20' }))
  await expect(rows.first().locator('td').first()).toHaveText('1')
  checks.push('第 2 页显示真实对象且序号从 11 开始；切换 20 条/页后回到第 1 页')

  await selectCategory(populated.category)
  await assertRows(await query({ category: populated.category, pageSize: '20' }))
  const exactName = populated.result.list[0].objectName
  await search.fill(exactName)
  await assertRows(await query({ category: populated.category, pageSize: '20', name: exactName }))
  await screenshot('02-category-and-name')
  checks.push(`真实分类“${populated.category}”与对象名称联合筛选结果和 API 一致`)
  await search.fill('不存在的引用对象' + ac.prefix)
  await expect(rows).toHaveCount(0)
  await expect(table.getByText('暂无数据', { exact: true })).toBeVisible()
  await expect(done).toBeInViewport()
  await search.fill('')
  await selectCategory('')
  await assertRows(await query({ category: '', pageSize: '20' }))
  checks.push(`未分类筛选与真实 API 一致（共 ${uncategorized.total} 条）；无匹配名称呈现空态且完成按钮可达`)
  if (empty) {
    await selectCategory(empty.category)
    await expect(rows).toHaveCount(0)
    await expect(table.getByText('暂无数据', { exact: true })).toBeVisible()
    checks.push(`无可引用对象的真实分类“${empty.category}”呈现空态`)
  }
  await selectCategory(undefined)
  await assertRows(await query({ pageSize: '20' }))
  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(done).toBeInViewport()
  const body = table.locator('.ant-table-body')
  snapshots.narrowBefore = await picker.evaluate(element => {
    const box = target => {
      const r = target.getBoundingClientRect()
      return { top: r.top, bottom: r.bottom, height: r.height }
    }
    const list = element.querySelector('.ant-table-body')
    const modalBody = element.querySelector('.ant-modal-body')
    return {
      content: box(element.querySelector('.reference-picker')),
      header: box(element.querySelector('.ant-table-header')),
      footer: box(element.querySelector('.ant-modal-footer')),
      list: { ...box(list), clientHeight: list.clientHeight, scrollHeight: list.scrollHeight },
      modalBody: {
        clientHeight: modalBody.clientHeight,
        scrollHeight: modalBody.scrollHeight,
        scrollTop: modalBody.scrollTop
      }
    }
  })
  assert.ok(snapshots.narrowBefore.list.scrollHeight > snapshots.narrowBefore.list.clientHeight, '表体需独立滚动')
  assert.ok(
    snapshots.narrowBefore.modalBody.scrollHeight <= snapshots.narrowBefore.modalBody.clientHeight + 2,
    '弹窗正文不应二次垂直滚动'
  )
  // 底座分页会播放回顶动画，先等其结束，再验证真实滚轮能稳定滚动表体。
  await page.waitForTimeout(600)
  await body.hover()
  await page.mouse.wheel(0, 2000)
  await expect.poll(() => body.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  await page.waitForTimeout(500)
  const stableScrollTop = await body.evaluate(element => element.scrollTop)
  assert.ok(stableScrollTop > 0, '真实滚轮滚动后等待 500ms 仍保持在列表下方')
  await expect(done).toBeInViewport()
  await expect(table.locator('.ant-table-header')).toBeInViewport()
  await screenshot('03-narrow-internal-scroll')
  snapshots.narrowScrollTop = await body.evaluate(element => element.scrollTop)
  assert.equal(snapshots.narrowScrollTop, stableScrollTop, '截图前后表体滚动位置应保持稳定')
  checks.push('1280×720 下表体独立滚动；表头、分页与完成按钮保持可见；弹窗正文无二次纵向滚动')

  await page.setViewportSize({ width: 1600, height: 1050 })
  await body.evaluate(element => {
    element.scrollTop = 0
  })
  const selectedIds = initial.list.slice(0, 2).map(row => row.id)
  for (const objectId of selectedIds) {
    const selectedRow = table.locator(`tbody tr[data-row-key="${objectId}"]`)
    await selectedRow.getByRole('button', { name: /^引\s*用$/ }).click()
    await expect(selectedRow.getByRole('button', { name: '已引用', exact: true })).toBeDisabled()
    await expect(picker).toBeVisible()
  }
  await expect(picker.locator('.picker-complete')).toContainText('当前已引用 2 个对象')
  await screenshot('04-two-references')
  await done.click()
  assert.equal((await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)).draft.objects.length, 0)
  await page.getByRole('button', { name: /保存草稿$/ }).click()
  await expect(page.locator('.workspace-header')).not.toContainText('有未保存修改')
  const saved = await ac.api(`/nocode/application/get?id=${ac.app.application.id}`)
  assert.deepEqual(
    saved.draft.objects.map(row => row.objectId),
    selectedIds
  )
  await page.reload()
  await expect(page.locator('.workspace .nocode-embedded-table').first().locator('tbody tr[data-row-key]')).toHaveCount(
    2
  )
  await page.getByRole('button', { name: /引用对象$/ }).click()
  await expect(picker.getByRole('button', { name: '已引用', exact: true })).toHaveCount(2)
  checks.push('连续引用 2 个真实对象后弹窗保持打开且防重复；显式保存前服务端引用为空，保存刷新后 2 个引用保持一致')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  await mkdir(output, { recursive: true })
  await ac.persist()
  ac.tokens = {}
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        origin,
        api: ac.base,
        applicationId: ac.app?.application.id,
        checks,
        snapshots,
        errors,
        failure: failure?.message
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output, checks: checks.length, errors, failure: failure?.message }))
}
if (failure) throw failure
