import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 对指定的已发布宽表做只读验收：滚动、列宽、显隐和取消编辑，不提交业务变更。
const objectId = process.argv[2] || process.env.NOCODE_VERIFY_OBJECT_ID
assert.ok(objectId, '请传入待验收的已发布对象 ID，要求有记录且属性列足够横向滚动')
const output = resolve(`.work/object-data-fixed-columns/${new Date().toISOString().replace(/[:.]/g, '-')}`)
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, output)
const checks = [],
  errors = [],
  mutations = [],
  measurements = []
let browser, page, failure

async function settle() {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}
async function measure(grid) {
  return grid.evaluate(root => {
    const body = root.querySelector('.ant-table-body')
    const head = root.querySelector('.ant-table-header .ant-table-thead tr')
    const row = root.querySelector('.ant-table-body tr[data-row-key]')
    const cell = element => {
      const rect = element.getBoundingClientRect()
      const css = getComputedStyle(element)
      return {
        x: rect.x,
        right: rect.right,
        width: rect.width,
        position: css.position,
        background: css.backgroundColor,
        zIndex: css.zIndex,
        left: css.left,
        topmost:
          document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)?.closest('td,th') ===
          element
      }
    }
    const columns = elements => ({
      sequence: cell(elements[0]),
      id: cell(elements[1]),
      property: cell(elements[2]),
      actions: cell([...elements].find(el => el.classList.contains('ant-table-cell-fix-right')))
    })
    return {
      scrollLeft: body.scrollLeft,
      maxScroll: body.scrollWidth - body.clientWidth,
      bodyLeft: body.getBoundingClientRect().left,
      body: columns(row.children),
      header: columns(head.children)
    }
  })
}
function near(actual, expected, message) {
  assert.ok(Math.abs(actual - expected) <= 2, `${message}: ${actual} / ${expected}`)
}
async function verifyScroll(grid, label) {
  const body = grid.locator('.ant-table-body')
  await body.evaluate(el => (el.scrollLeft = 0))
  await settle()
  const baseline = await measure(grid)
  assert.ok(baseline.maxScroll > 100, '验收表必须有横向滚动范围')
  for (const offset of [180, Math.round(baseline.maxScroll / 2), baseline.maxScroll, 0]) {
    await body.evaluate((el, x) => (el.scrollLeft = x), offset)
    await settle()
    const current = await measure(grid)
    measurements.push({ label, ...current })
    for (const part of ['header', 'body']) {
      for (const column of ['sequence', 'id', 'actions']) {
        const value = current[part][column]
        near(value.x, baseline[part][column].x, `${label} ${part} ${column} 固定位置`)
        assert.equal(value.position, 'sticky', `${column} 必须使用固定列`)
        assert.ok(value.topmost, `${label} ${part} ${column} 被其他列覆盖`)
        assert.ok(!['transparent', 'rgba(0, 0, 0, 0)'].includes(value.background), '固定列背景必须不透明')
      }
      near(current[part].id.x, current[part].sequence.right, '序号与记录 ID 之间不能留空档')
      near(current[part].property.x, baseline[part].property.x - current.scrollLeft, '属性列随横向滚动移动')
    }
    near(current.header.id.x, current.body.id.x, '记录 ID 表头与表体对齐')
    near(current.header.sequence.x, current.body.sequence.x, '序号表头与表体对齐')
  }
  checks.push(label)
}

try {
  await mkdir(output, { recursive: true })
  await ac.login()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
  page.setDefaultTimeout(15000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (
      request.method() !== 'GET' &&
      /\/nocode\/.*(?:save|delete|execute|calibrate|clear-column)$/.test(new URL(request.url()).pathname)
    )
      mutations.push(new URL(request.url()).pathname)
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
  await page.goto(
    (process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173') + `/nocode/object/editor?id=${objectId}&tab=data`
  )
  const grid = page.locator('.object-data-grid .data-grid-table')
  await expect(grid.locator('.ant-table-body tr[data-row-key]').first()).toBeVisible()
  await verifyScroll(grid, '常规视口：两列固定，属性滚动，操作列右侧固定')
  const originalIdWidth = (await measure(grid)).body.id.width
  const idHeader = grid.locator('.ant-table-header th').nth(1)
  const handle = idHeader.locator('.la-resize-handle')
  await grid.locator('.ant-table-body').evaluate(el => (el.scrollLeft = 0))
  const handleBox = await handle.boundingBox()
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(handleBox.x + handleBox.width / 2 + 50, handleBox.y + handleBox.height / 2, { steps: 5 })
  await page.mouse.up()
  await settle()
  near((await measure(grid)).body.id.width, originalIdWidth + 50, '记录 ID 列宽实际拖宽')
  await verifyScroll(grid, '拖宽记录 ID 后固定列偏移和表头表体对齐')
  await grid.getByTitle('列设置', { exact: true }).click()
  const settings = page.locator('.la-column-setting-panel:visible')
  await settings.getByRole('checkbox').nth(1).uncheck()
  await grid.getByTitle('列设置', { exact: true }).click()
  await settle()
  await verifyScroll(grid, '隐藏首个属性后两列仍连续固定')
  await grid.getByTitle('列设置', { exact: true }).click()
  await settings.getByRole('checkbox').nth(1).check()
  await grid.getByTitle('列设置', { exact: true }).click()
  const firstRow = grid.locator('.ant-table-body tr[data-row-key]').first()
  await firstRow.getByRole('button', { name: /^编辑$/ }).click()
  await expect(firstRow.getByRole('button', { name: '取消', exact: true })).toBeVisible()
  await verifyScroll(grid, '编辑本行时输入控件不遮挡固定列')
  await firstRow.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page.locator('.ant-modal-wrap:visible')).toHaveCount(0)
  await expect(firstRow.getByRole('button', { name: /^编辑$/ })).toBeVisible()
  await page.setViewportSize({ width: 1280, height: 900 })
  await settle()
  await verifyScroll(grid, '窄视口：固定列不被属性内容覆盖')
  await grid.locator('.ant-table-body').evaluate(el => (el.scrollLeft = el.scrollWidth / 2))
  await settle()
  await page.screenshot({ path: resolve(output, 'fixed-columns-scrolled.png'), fullPage: true, animations: 'disabled' })
  assert.deepEqual(mutations, [])
  assert.deepEqual(errors, [])
  checks.push('没有业务写入请求或页面异常')
} catch (error) {
  failure = error
  errors.push(error.message)
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  ac.tokens = {}
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, errors, mutations, measurements }, null, 2))
  console.log(JSON.stringify({ output, checks, errors, mutations }))
}
if (failure) process.exitCode = 1
