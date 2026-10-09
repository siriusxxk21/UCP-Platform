import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 当前开发环境真实接口与 Chrome 验收。只保存本次唯一前缀草稿，不发布业务表或应用。
// 从 ucp-front 执行：node tools/nocode-e2e/verify-categories.mjs
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.prefix = `cat${Date.now().toString(36)}`
const output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/categories', ac.prefix)
ac.output = output
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const categoryA = `采购分类_${ac.prefix}`
const categoryB = `档案分类_${ac.prefix}`
const checks = []
const errors = []
const geometry = []
let browser, page, failure
let objectId, applicationId

async function record(name, work) {
  try {
    await work()
    checks.push({ name, passed: true })
  } catch (error) {
    checks.push({ name, passed: false, error: error.message })
    throw error
  } finally {
    ac.checks = checks
    await ac.persist()
  }
}

function ownObject(detail) {
  const draft = detail.draft
  assert.ok(draft.objectCode.startsWith(ac.prefix + '_'), '只登记本轮自有对象')
  if (!ac.owned.objects.some(value => value.id === draft.id))
    ac.owned.objects.push({ id: draft.id, code: draft.objectCode })
  return draft.id
}

function ownApplication(detail) {
  assert.ok(detail.application.code.startsWith(ac.prefix + '_'), '只登记本轮自有应用')
  if (!ac.owned.applications.includes(detail.application.id)) ac.owned.applications.push(detail.application.id)
  return detail.application.id
}

async function createObject(suffix, category) {
  const detail = await ac.api('/nocode/design/save', {
    draft: {
      id: null,
      expectedLockVersion: null,
      objectCode: `${ac.prefix}_${suffix}`,
      objectName: `${ac.prefix}_${suffix}`,
      tableName: `biz_${ac.prefix}_${suffix}`,
      description: '分类验收自有草稿，不发布',
      ...(category === undefined ? {} : { category }),
      titleFieldKey: 'name',
      fields: [ac.field('name', 'TEXT', '名称')],
      removedFieldIds: []
    },
    settings: {},
    fieldOptions: {},
    relations: [],
    indexes: [],
    details: []
  })
  ownObject(detail)
  await ac.persist()
  assert.equal(detail.draft.category || '', category || '')
  assert.equal(detail.publishedVersion, null)
}

async function createApplication(suffix, category) {
  const detail = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: `${ac.prefix}_${suffix}`,
    name: `${ac.prefix}_${suffix}`,
    description: '分类验收自有草稿，不发布',
    ...(category === undefined ? {} : { category }),
    definition: { objects: [], resources: [] }
  })
  ownApplication(detail)
  await ac.persist()
  assert.equal(detail.application.category || '', category || '')
  assert.equal(detail.application.publishedVersion, null)
}

const input = (label, scope = page) => scope.locator(`input[aria-label="${label}"]`)
const tree = () => page.locator('.nocode-category-panel')
const tableRows = () => page.locator('.nocode-category-content tbody tr[data-row-key]')
const screenshot = name =>
  page.screenshot({ path: resolve(output, `${name}.png`), fullPage: true, animations: 'disabled' })

async function mutation(path, action) {
  const pending = page.waitForResponse(
    response => response.url().endsWith(path) && response.request().method() === 'POST'
  )
  await action()
  const body = await (await pending).json()
  assert.equal(body.code, 0, `${path}: ${body.msg}`)
  return body.data
}

async function queryResponse(endpoint, action, expected) {
  const pending = page.waitForResponse(response => {
    const url = new URL(response.url())
    return (
      url.pathname.endsWith(endpoint) &&
      Object.entries(expected).every(([key, value]) => url.searchParams.get(key) === value)
    )
  })
  await action()
  const response = await (await pending).json()
  assert.equal(response.code, 0, response.msg)
  return response.data
}

async function selectCategory(endpoint, value) {
  return queryResponse(
    endpoint,
    () =>
      tree()
        .getByText(value === undefined ? '全部分类' : value || '未分类', { exact: true })
        .click(),
    { category: value === undefined ? null : value, pageNo: '1' }
  )
}

async function verifyGeometry(label) {
  for (const width of [1600, 1280, 900]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect(tree()).toBeVisible()
    const left = await tree().boundingBox()
    const right = await page.locator('.nocode-category-content').boundingBox()
    assert.ok(left && right && left.x + left.width <= right.x + 1, `${label}/${width}: 左树不得覆盖右表`)
    assert.ok(right.width >= 300, `${label}/${width}: 表格保留可操作宽度`)
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    assert.ok(overflow <= 1, `${label}/${width}: 页面不得整体横向溢出`)
    const labels = await tree()
      .locator('.ant-tree-title')
      .evaluateAll(nodes =>
        nodes.map(node => ({
          height: node.getBoundingClientRect().height,
          whiteSpace: getComputedStyle(node).whiteSpace,
          ellipsis: getComputedStyle(node).textOverflow,
          text: node.textContent,
          tooltip: node.firstElementChild?.getAttribute('title')
        }))
      )
    for (const item of labels) {
      assert.equal(item.whiteSpace, 'nowrap', '分类名称保持单行')
      assert.equal(item.ellipsis, 'ellipsis', '长分类名称省略显示')
      assert.ok(item.height <= 28)
      assert.equal(item.tooltip, item.text, '悬停保留完整分类名称')
    }
    geometry.push({ label, width, left, right, overflow, labels })
    await screenshot(`${label}-${width}`)
  }
  await page.setViewportSize({ width: 1600, height: 1000 })
}

async function verifyList({ kind, route, endpoint, searchLabel, categoryLabel, createLabel }) {
  await page.goto(origin + route)
  await expect(tree().getByText(categoryA, { exact: true })).toBeVisible()
  await expect(tree().getByText(categoryB, { exact: true })).toBeVisible()
  await verifyGeometry(kind)
  const first = await selectCategory(endpoint, categoryA)
  assert.equal(first.total, 11)
  assert.equal(first.list.length, 10)
  assert.ok(first.list.every(value => value.category === categoryA))
  await expect(tableRows()).toHaveCount(10)
  const second = await queryResponse(endpoint, () => page.locator('.ant-pagination-item-2').click(), {
    category: categoryA,
    pageNo: '2'
  })
  assert.equal(second.total, 11)
  await expect(tableRows()).toHaveCount(1)
  await expect(tableRows().first().locator('td').first()).toHaveText('11')
  const searchKey = kind === 'objects' ? 'name' : 'search'
  await input(searchLabel).fill(`${ac.prefix}_ui`)
  const found = await queryResponse(endpoint, () => input(searchLabel).press('Enter'), {
    category: categoryA,
    pageNo: '1',
    [searchKey]: `${ac.prefix}_ui`
  })
  assert.equal(found.total, 1)
  await expect(tableRows()).toHaveCount(1)
  assert.equal((await selectCategory(endpoint, categoryB)).total, 0, '分类切换保留关键词并联合筛选')
  await expect(tableRows()).toHaveCount(0)
  await expect(tree().getByText(categoryA, { exact: true })).toBeVisible()
  await expect(tree().getByText(categoryB, { exact: true })).toBeVisible()
  await input(searchLabel).fill(ac.prefix)
  const other = await queryResponse(endpoint, () => input(searchLabel).press('Enter'), {
    category: categoryB,
    pageNo: '1',
    [searchKey]: ac.prefix
  })
  assert.equal(other.total, 1)
  await expect(tableRows()).toHaveCount(1)
  const unclassified = await selectCategory(endpoint, '')
  assert.equal(unclassified.total, 1, '未传分类的旧接口格式进入未分类')
  assert.ok(unclassified.list.every(value => !value.category))
  await expect(tableRows()).toHaveCount(1)
  await expect(tableRows().first()).toContainText('未分类')
  assert.equal((await selectCategory(endpoint, undefined)).total, 13)
  await input(`搜索${categoryLabel}`).fill(categoryB)
  await expect(tree().getByText(categoryA, { exact: true })).toHaveCount(0)
  await expect(tree().getByText(categoryB, { exact: true })).toBeVisible()
  await input(`搜索${categoryLabel}`).fill('')
  await selectCategory(endpoint, categoryA)
  await page.getByRole('button', { name: new RegExp(createLabel + '$') }).click()
  await expect(input(categoryLabel)).toHaveValue(categoryA)
  if (kind === 'applications') {
    await page
      .locator('.ant-modal-content:visible')
      .getByRole('button', { name: /取\s*消/ })
      .click()
  } else {
    // 尚未填写的草稿只有分类默认值，不保存；刷新离开不改已有记录。
    await page.goto(origin + route)
  }
}

try {
  await ac.login()
  await mkdir(output, { recursive: true })
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  // 由正式 bootstrap 获取并规范化权限菜单，避免直接将 API 树形菜单写成 Store 平面菜单。
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    for (const key of ['roles', 'permissions', 'menus']) localStorage.removeItem(key)
  }, ac.tokens.admin)

  await record('真实 UI 新增数据对象分类、保存及刷新回读', async () => {
    await page.goto(origin + '/nocode/object')
    await page.getByRole('button', { name: /新建对象$/ }).click()
    await input('对象名称').fill(`${ac.prefix}_ui`)
    await input('对象编码').fill(`${ac.prefix}_ui`)
    await input('主表名称').fill(`biz_${ac.prefix}_ui`)
    await input('数据对象分类').fill(categoryA)
    await input('数据对象分类').press('Tab')
    const saved = await mutation('/nocode/design/save', () => page.getByRole('button', { name: /保存草稿$/ }).click())
    objectId = ownObject(saved)
    await ac.persist()
    assert.equal(saved.draft.category, categoryA)
    await expect(page).toHaveURL(new RegExp(`id=${objectId}`))
    await page.reload()
    await page.getByRole('button', { name: /编辑基本信息$/ }).click()
    await expect(input('数据对象分类')).toHaveValue(categoryA)
    assert.equal((await ac.api(`/nocode/design/get?id=${objectId}`)).draft.category, categoryA)
    await screenshot('object-created-category')
  })

  await record('真实 UI 新增应用分类、保存及刷新回读', async () => {
    await page.goto(origin + '/nocode-app/application')
    await page.getByRole('button', { name: /新建应用$/ }).click()
    const modal = page.locator('.ant-modal-content:visible')
    await input('应用名称', modal).fill(`${ac.prefix}_ui`)
    await input('应用编码', modal).fill(`${ac.prefix}_ui`)
    await input('应用分类', modal).fill(categoryA)
    await input('应用分类', modal).press('Tab')
    const saved = await mutation('/nocode/application/save', () =>
      modal.getByRole('button', { name: /确\s*定/ }).click()
    )
    applicationId = ownApplication(saved)
    await ac.persist()
    assert.equal(saved.application.category, categoryA)
    await expect(page).toHaveURL(new RegExp(`id=${applicationId}`))
    await page.reload()
    await page.getByRole('tab', { name: '基本设置', exact: true }).click()
    await expect(input('应用分类')).toHaveValue(categoryA)
    await screenshot('application-created-category')
  })

  await record('真实 API 准备自有分类分页、第二分类及无分类草稿', async () => {
    for (let index = 1; index <= 10; index++) {
      await createObject(`page${index}`, categoryA)
      await createApplication(`page${index}`, categoryA)
    }
    await createObject('other', categoryB)
    await createApplication('other', categoryB)
    await createObject('legacy', undefined)
    await createApplication('legacy', undefined)
    for (const endpoint of ['/nocode/design/categories', '/nocode/application/categories']) {
      const categories = await ac.api(endpoint)
      assert.ok(categories.includes(categoryA) && categories.includes(categoryB))
      assert.equal(categories.filter(value => value === categoryA).length, 1)
    }
  })

  await record('对象左树右表、完整分页、联合搜索、未分类与新建继承分类', () =>
    verifyList({
      kind: 'objects',
      route: '/nocode/object',
      endpoint: '/nocode/design/page',
      searchLabel: '查询对象名称',
      categoryLabel: '数据对象分类',
      createLabel: '新建对象'
    })
  )
  await record('应用左树右表、完整分页、联合搜索、未分类与新建继承分类', () =>
    verifyList({
      kind: 'applications',
      route: '/nocode-app/application',
      endpoint: '/nocode/application/page',
      searchLabel: '搜索应用',
      categoryLabel: '应用分类',
      createLabel: '新建应用'
    })
  )

  await record('应用基本设置修改分类保存、刷新与新分类列表归属', async () => {
    await page.goto(`${origin}/nocode-app/workspace?id=${applicationId}`)
    await page.getByRole('tab', { name: '基本设置', exact: true }).click()
    await input('应用分类').fill(categoryB)
    await input('应用分类').press('Tab')
    await mutation('/nocode/application/save', () => page.getByRole('button', { name: /保存草稿$/ }).click())
    assert.equal((await ac.api(`/nocode/application/get?id=${applicationId}`)).application.category, categoryB)
    await page.reload()
    await page.getByRole('tab', { name: '基本设置', exact: true }).click()
    await expect(input('应用分类')).toHaveValue(categoryB)
    await page.goto(origin + '/nocode-app/application')
    await expect(tree().getByText(categoryB, { exact: true })).toBeVisible()
    const result = await selectCategory('/nocode/application/page', categoryB)
    assert.equal(result.total, 2)
    await expect(tableRows().filter({ hasText: `${ac.prefix}_ui` })).toHaveCount(1)
    await screenshot('application-reclassified')
  })
  await record('浏览器没有未捕获页面错误', async () => assert.deepEqual(errors, []))
} catch (error) {
  failure = error
  if (page) {
    await screenshot('failure').catch(() => {})
    await writeFile(resolve(output, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
} finally {
  ac.checks = checks
  await ac.persist()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        prefix: ac.prefix,
        origin,
        api: ac.base,
        owned: ac.owned,
        objectId,
        applicationId,
        checks,
        errors,
        geometry,
        failure: failure?.stack
      },
      null,
      2
    )
  )
  await browser?.close()
}
if (failure) throw failure
console.log(JSON.stringify({ output, checks: checks.length, passed: true }))
