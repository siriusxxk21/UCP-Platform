import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

/** 个人责任树专项：仅创建清单内独立任务，浏览器拒绝写请求，结束后取消夹具并停用临时身份。 */
class PersonalTreeAcceptance extends TaskDataPolicyAcceptance {
  constructor() {
    super()
    this.prefix = `pt${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
    this.output = resolve(process.env.TASK_PERSONAL_TREE_OUTPUT || '.work/task-personal-tree', this.prefix)
    this.browserResult = { captures: [], errors: [], blockedWrites: [], failures: [], injected: [] }
  }

  async persist() {
    await super.persist()
    await writeFile(
      resolve(this.output, 'result.json'),
      JSON.stringify(
        {
          prefix: this.prefix,
          results: this.results,
          cleanup: this.cleanup,
          errors: this.errors,
          browser: this.browserResult
        },
        null,
        2
      )
    )
  }

  query(overrides = {}) {
    return {
      scope: 'MINE',
      tab: 'POOL',
      search: this.prefix,
      date: new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }),
      scheduleScope: 'PERSONAL',
      pageNo: 1,
      pageSize: 100,
      ...overrides
    }
  }

  page(query = {}, token = this.tokens[this.employeeA]) {
    return this.taskApi('personal-tree-page', this.query(query), token)
  }

  children(parentId, query = {}, token = this.tokens[this.employeeA]) {
    return this.taskApi('personal-tree-children', { query: this.query(query), parentId }, token)
  }

  async tree(names, assignees) {
    const nodes = names.map((name, index) =>
      this.node(name, { assignmentMode: 'ASSIGNED', assigneeId: assignees[index] })
    )
    // create 的 nodes 是总任务内编排；其顶层 parentId 留空，由服务端接到新总任务。
    for (let index = 2; index < nodes.length; index++) nodes[index].parentId = nodes[index - 1].id
    const detail = await this.create(nodes[0], { nodes: nodes.slice(1) })
    return names.map(name => {
      const task = detail.nodes.find(task => task.title === this.title(name))
      assert.ok(task, `缺少新建夹具 ${name}`)
      return task
    })
  }

  async plan(task, token) {
    const context = await this.taskApi('plan-context', { ids: [task.id], target: 'SELF' }, token)
    return this.taskApi(
      'schedule',
      {
        ids: [task.id],
        target: 'SELF',
        action: 'ARRANGE',
        period: 'DAY',
        date: this.query().date,
        expectedVersions: { [task.id]: context.items[0].version }
      },
      token
    )
  }
}

const ids = rows => rows.map(row => row.task.id)
const expect = playwrightExpect.configure({ timeout: 20000 })

async function run() {
  const ac = new PersonalTreeAcceptance()
  await mkdir(ac.output, { recursive: true })
  const tasks = {}
  let browser
  try {
    const fixture = await ac.check('01 两位最小权限员工及独立任务树夹具', async () => {
      await ac.login()
      // 新接口缺失时先停止，不留下无意义夹具。
      await ac.page({}, ac.tokens.admin)
      await ac.prepareUsers()
      tasks.own = await ac.tree(['本人总任务', '本人子任务', '本人孙任务'], [ac.employeeA, ac.employeeA, ac.employeeA])
      tasks.foreign = await ac.tree(
        ['他人总任务', '独立入口子任务', '独立入口孙任务'],
        [ac.employeeB, ac.employeeA, ac.employeeA]
      )
      tasks.bridge = await ac.tree(
        ['跨层本人总任务', '跨层他人子任务', '跨层本人孙任务'],
        [ac.employeeA, ac.employeeB, ac.employeeA]
      )
      tasks.admin = await ac.tree(['管理员总任务', '管理员可见他人子任务'], [ac.adminId, ac.employeeB])
      tasks.pages = []
      for (let index = 1; index <= 11; index++)
        tasks.pages.push((await ac.tree([`分页入口${String(index).padStart(2, '0')}`], [ac.employeeA]))[0])
      tasks.done = (await ac.tree(['本人已完成对照任务'], [ac.employeeA]))[0]
      await ac.command(tasks.done.id, 'START', ac.tokens[ac.employeeA])
      await ac.command(tasks.done.id, 'COMPLETE', ac.tokens[ac.employeeA])
      for (const task of tasks.own) await ac.plan(task, ac.tokens[ac.employeeA])
      return { taskIds: ac.taskIds, employeeIds: [ac.employeeA, ac.employeeB], applications: 0, objects: 0 }
    })
    const http = await ac.check(
      '02 服务端归并、跨层、搜索、计划、权限及入口分页',
      async () => {
        const all = await ac.page()
        assert.equal(all.total, 14)
        assert.equal(all.list.length, 14)
        assert.ok(all.list.every(row => row.task.assigneeId === ac.employeeA))
        assert.ok(ids(all.list).includes(tasks.own[0].id))
        assert.ok(!ids(all.list).includes(tasks.own[1].id))
        assert.deepEqual(ids(await ac.children(tasks.own[0].id)), [tasks.own[1].id])
        assert.deepEqual(ids(await ac.children(tasks.own[1].id)), [tasks.own[2].id])
        assert.ok(ids(all.list).includes(tasks.foreign[1].id))
        assert.ok(!ids(all.list).includes(tasks.foreign[0].id))
        assert.deepEqual(ids(await ac.children(tasks.foreign[1].id)), [tasks.foreign[2].id])
        assert.deepEqual(ids(await ac.children(tasks.bridge[0].id)), [tasks.bridge[2].id])
        const match = await ac.page({ search: tasks.own[2].title })
        assert.deepEqual(ids(match.list), [tasks.own[2].id])
        const pages = []
        for (let pageNo = 1; pageNo <= 5; pageNo++) {
          const page = await ac.page({ pageNo, pageSize: 3 })
          assert.equal(page.total, 14)
          pages.push(...ids(page.list))
        }
        assert.equal(new Set(pages).size, 14)
        assert.deepEqual(new Set(pages), new Set(ids(all.list)))
        assert.deepEqual(ids((await ac.page({ tab: 'ALL', status: 'COMPLETED' })).list), [tasks.done.id])
        const planQuery = { tab: 'TODAY', planFilter: 'PLANNED' }
        assert.deepEqual(ids((await ac.page(planQuery)).list), [tasks.own[0].id])
        assert.deepEqual(ids(await ac.children(tasks.own[0].id, planQuery)), [tasks.own[1].id])
        const admin = await ac.page({}, ac.tokens.admin)
        assert.deepEqual(ids(admin.list), [tasks.admin[0].id])
        assert.equal(admin.list[0].matchingChildCount, 0)
        assert.deepEqual(await ac.children(tasks.admin[0].id, {}, ac.tokens.admin), [])
        return {
          entries: ids(all.list),
          pageSize: 3,
          pages,
          searchMatch: ids(match.list),
          plan: tasks.own.map(task => task.id)
        }
      },
      [fixture]
    )
    if (http.status === 'passed') {
      browser = await chromium.launch({ channel: 'chrome', headless: true })
      await verifyBrowser(ac, browser, tasks)
    }
  } catch (error) {
    ac.errors.push(error.stack || error.message)
    throw error
  } finally {
    if (browser) await browser.close()
    if (ac.tokens.admin) await ac.finish()
    await ac.persist()
    console.log(
      JSON.stringify({
        output: ac.output,
        results: ac.results.map(({ name, status }) => ({ name, status })),
        cleanupFailed: ac.cleanup.filter(item => item.status === 'failed').length
      })
    )
    if (
      ac.results.some(result => result.status !== 'passed') ||
      ac.cleanup.some(item => item.status === 'failed') ||
      ac.errors.length
    )
      process.exitCode = 1
  }
}

async function verifyBrowser(ac, browser, tasks, adminOnly = false) {
  let current
  const reads = new Set([
    'page',
    'personal-tree-page',
    'personal-tree-children',
    'detail',
    'plan-context',
    'readiness',
    'entries/list'
  ])
  async function pageFor(token) {
    const info = await ac.api('/system/auth/get-permission-info', undefined, token)
    const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
    current = page
    await page.addInitScript(
      ({ token, info }) => {
        const flatten = (items, parentId = 0) =>
          items.flatMap((menu, index) =>
            menu.visible === false
              ? []
              : [
                  {
                    id: menu.id,
                    name: menu.name,
                    path: menu.path || '',
                    component: menu.component,
                    icon: menu.icon,
                    parentId: menu.parentId ?? parentId,
                    sort: menu.sort ?? index
                  },
                  ...flatten(menu.children || [], menu.id)
                ]
          )
        localStorage.setItem('token', token)
        localStorage.setItem('userInfo', JSON.stringify(info.user))
        localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
        localStorage.setItem('roles', JSON.stringify(info.roles || []))
        localStorage.setItem('menus', JSON.stringify(flatten(info.menus || [])))
        sessionStorage.setItem('lastActivityAt', String(Date.now()))
      },
      { token, info }
    )
    page.on('pageerror', error => ac.browserResult.errors.push(error.message))
    page.on('response', async response => {
      if (!response.url().includes('/api/nocode/')) return
      try {
        const body = await response.json()
        if (body.code !== undefined && body.code !== 0)
          ac.browserResult.failures.push({ path: new URL(response.url()).pathname, code: body.code, message: body.msg })
      } catch {
        /* 导航取消的请求不作为业务结果。 */
      }
    })
    await page.route('**/api/**', route => {
      const request = route.request(),
        path = new URL(request.url()).pathname.replace(/^\/api/, '')
      if (
        ['GET', 'HEAD', 'OPTIONS'].includes(request.method()) ||
        (path.startsWith('/nocode/tasks/') && reads.has(path.slice('/nocode/tasks/'.length)))
      )
        return route.continue()
      ac.browserResult.blockedWrites.push({ path, method: request.method() })
      return route.abort('blockedbyclient')
    })
    await page.goto('http://127.0.0.1:5173/nocode-app/task-center')
    await expect(page.locator('.task-list')).toBeVisible()
    await settle(page)
    return page
  }
  const list = page => page.locator('.task-list').first()
  const rows = page => list(page).locator('.ant-table-tbody > tr.ant-table-row')
  const rowIds = page => rows(page).evaluateAll(items => items.map(item => item.getAttribute('data-row-key')))
  // AntD 表格渲染滞后于接口与按钮状态；等待实际行标识，不能把 networkidle 当成 DOM 提交完成。
  const expectRows = (page, expected) => expect.poll(() => rowIds(page)).toEqual(expected)
  const settle = async page => {
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    await page.waitForLoadState('networkidle')
  }
  async function capture(page, name) {
    const screenshot = resolve(ac.output, `${name}.png`)
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
    await writeFile(resolve(ac.output, `${name}.txt`), await page.locator('body').innerText())
    ac.browserResult.captures.push({ name, screenshot })
    await ac.persist()
  }
  async function check(name, work) {
    return ac.check(name, async () => {
      try {
        return await work()
      } catch (error) {
        if (current) await capture(current, `failure-${ac.results.length + 1}`)
        throw error
      }
    })
  }
  async function search(page, text = ac.prefix) {
    await page.getByPlaceholder(/搜索任务名称|搜索总任务或子任务名称/).fill(text)
    await page.getByRole('button', { name: /查\s*询$/ }).click()
    await settle(page)
  }
  async function toggle(page, task, open) {
    await list(page)
      .getByRole('button', { name: `${open ? '展开' : '收起'}子任务：${task.title}`, exact: true })
      .click()
    await settle(page)
  }
  if (!adminOnly) {
    const employee = await pageFor(ac.tokens[ac.employeeA])
    await check('03 浏览器本人父子孙逐级展开和收起，收起后子孙不独立出现', async () => {
      await search(employee, ac.title('本人'))
      // 先用精确树前缀避免跨层树混入；输入以本人开头的完整前缀可同时匹配三层。
      await expectRows(employee, [tasks.own[0].id])
      await capture(employee, 'own-collapsed')
      const total = await list(employee).locator('.ant-pagination-total-text').innerText()
      await toggle(employee, tasks.own[0], true)
      await expectRows(
        employee,
        tasks.own.slice(0, 2).map(task => task.id)
      )
      await toggle(employee, tasks.own[1], true)
      await expectRows(
        employee,
        tasks.own.map(task => task.id)
      )
      await expect(list(employee).locator('.ant-pagination-total-text')).toHaveText(total)
      await capture(employee, 'own-expanded-three-levels')
      await toggle(employee, tasks.own[0], false)
      await expectRows(employee, [tasks.own[0].id])
      return { collapsed: [tasks.own[0].id], expanded: tasks.own.map(task => task.id), total }
    })
    await check('04 他人父任务下本人子任务独立入口，跨非本人层级不暴露他人任务', async () => {
      await search(employee, ac.title('独立入口'))
      await expectRows(employee, [tasks.foreign[1].id])
      await toggle(employee, tasks.foreign[1], true)
      await expectRows(
        employee,
        tasks.foreign.slice(1).map(task => task.id)
      )
      await capture(employee, 'foreign-parent-own-children')
      await search(employee, ac.title('跨层'))
      await expectRows(employee, [tasks.bridge[0].id])
      await toggle(employee, tasks.bridge[0], true)
      await expectRows(employee, [tasks.bridge[0].id, tasks.bridge[2].id])
      await capture(employee, 'own-descendant-across-foreign-parent')
      await search(employee, tasks.own[2].title)
      await expectRows(employee, [tasks.own[2].id])
      return { foreignParentHidden: tasks.foreign[0].id, skippedParent: tasks.bridge[1].id, search: tasks.own[2].id }
    })
    await check('05 入口分页不重复，展开不改总数，宽窄窗口滚动能到最后一行', async () => {
      await search(employee)
      await expect(rows(employee)).toHaveCount(10)
      const first = await rowIds(employee)
      assert.equal(first.length, 10)
      const total = await list(employee).locator('.ant-pagination-total-text').innerText()
      await list(employee).locator('.ant-pagination-item-2').click()
      await settle(employee)
      await expect(rows(employee)).toHaveCount(4)
      const second = await rowIds(employee)
      assert.equal(second.length, 4)
      assert.equal(new Set([...first, ...second]).size, 14)
      await list(employee).locator('.ant-pagination-item-1').click()
      await settle(employee)
      await expectRows(employee, first)
      const expandable = list(employee)
        .getByRole('button', { name: /^展开子任务：/ })
        .first()
      if (await expandable.count()) {
        await expandable.click()
        await settle(employee)
        await expect.poll(async () => (await rowIds(employee)).length).toBeGreaterThan(first.length)
        await expect(list(employee).locator('.ant-pagination-total-text')).toHaveText(total)
        const expanded = await rowIds(employee)
        assert.ok(first.every(id => expanded.includes(id)))
        assert.equal(new Set(expanded).size, expanded.length)
      }
      const scroll = []
      for (const [width, height] of [
        [1512, 1050],
        [1100, 760]
      ]) {
        await employee.setViewportSize({ width, height })
        await list(employee).locator('.ant-table-body').hover()
        await employee.mouse.wheel(0, -4000)
        await employee.waitForTimeout(250)
        const before = await list(employee).evaluate(element => ({
          top: element.querySelector('.ant-table-body').scrollTop,
          header: element.querySelector('.ant-table-thead').getBoundingClientRect().top,
          pager: element.querySelector('.ant-pagination').getBoundingClientRect().top
        }))
        await employee.mouse.wheel(0, 6000)
        await employee.waitForTimeout(300)
        const after = await list(employee).evaluate(element => ({
          top: element.querySelector('.ant-table-body').scrollTop,
          header: element.querySelector('.ant-table-thead').getBoundingClientRect().top,
          pager: element.querySelector('.ant-pagination').getBoundingClientRect().top,
          last: element.querySelector('.ant-table-tbody > tr.ant-table-row:last-child').getBoundingClientRect().bottom
        }))
        assert.ok(after.top > before.top)
        assert.ok(after.last <= after.pager + 1)
        assert.equal(before.header, after.header)
        assert.equal(before.pager, after.pager)
        scroll.push({ width, height, before, after })
        await capture(employee, `scroll-${width}`)
      }
      await employee.setViewportSize({ width: 1512, height: 1050 })
      return { first, second, total, scroll }
    })
    await check('06 子任务请求失败保留入口，重试能够正常展开', async () => {
      await search(employee, ac.title('本人'))
      const pattern = '**/api/nocode/tasks/personal-tree-children',
        failure = '验收注入：个人子任务读取失败'
      await employee.route(pattern, route =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 500, msg: failure, data: null })
        })
      )
      ac.browserResult.injected.push(failure)
      await list(employee)
        .getByRole('button', { name: `展开子任务：${tasks.own[0].title}`, exact: true })
        .click()
      await expect(list(employee).locator('.ant-alert-error')).toContainText(failure)
      await expectRows(employee, [tasks.own[0].id])
      await capture(employee, 'child-failure-preserves-root')
      await employee.unroute(pattern)
      const close = list(employee).locator('.ant-alert-close-icon')
      if (await close.count()) await close.click()
      const collapse = list(employee).getByRole('button', { name: `收起子任务：${tasks.own[0].title}`, exact: true })
      if (await collapse.count()) await collapse.click()
      await toggle(employee, tasks.own[0], true)
      await expectRows(
        employee,
        tasks.own.slice(0, 2).map(task => task.id)
      )
      return { failure, retry: await rowIds(employee) }
    })
    await check('07 切换已完成期间到达的旧展开请求不得串入新页', async () => {
      await search(employee, ac.title('本人'))
      let release, received
      const gate = new Promise(resolve => {
          release = resolve
        }),
        started = new Promise(resolve => {
          received = resolve
        })
      const pattern = '**/api/nocode/tasks/personal-tree-children'
      await employee.route(pattern, async route => {
        const response = await route.fetch()
        received()
        await gate
        await route.fulfill({ response }).catch(() => {})
      })
      try {
        await list(employee)
          .getByRole('button', { name: /^展开子任务：/ })
          .first()
          .click()
        await started
        await employee.getByRole('tab', { name: '已完成', exact: true }).click()
        await expect(rows(employee)).toHaveCount(1)
        await expectRows(employee, [tasks.done.id])
      } finally {
        release()
        await employee.unroute(pattern)
      }
      await settle(employee)
      await expectRows(employee, [tasks.done.id])
      await capture(employee, 'old-child-response-does-not-enter-done')
      await employee.getByRole('tab', { name: '我的计划', exact: true }).click()
      await settle(employee)
      await expectRows(employee, [tasks.own[0].id])
      await toggle(employee, tasks.own[0], true)
      await expectRows(
        employee,
        tasks.own.slice(0, 2).map(task => task.id)
      )
      await capture(employee, 'personal-plan-tree')
      return { done: tasks.done.id, planRoot: tasks.own[0].id }
    })
  }
  await check('08 管理员个人列表不会展开他人的任务，管理页仍按总任务展示', async () => {
    const admin = await pageFor(ac.tokens.admin)
    await search(admin)
    await expectRows(admin, [tasks.admin[0].id])
    await expect(list(admin).getByRole('button', { name: /^展开子任务：/ })).toHaveCount(0)
    await capture(admin, 'admin-personal-excludes-foreign-children')
    await admin.goto('http://127.0.0.1:5173/nocode-app/task-center/manage')
    await settle(admin)
    await search(admin, tasks.admin[0].title)
    await expectRows(admin, [tasks.admin[0].id])
    await toggle(admin, tasks.admin[0], true)
    await expectRows(
      admin,
      tasks.admin.map(task => task.id)
    )
    await capture(admin, 'management-preserves-authorized-tree')
    return { personal: [tasks.admin[0].id], management: tasks.admin.map(task => task.id) }
  })
  await ac.check('09 浏览器无非预期接口失败和写请求', async () => {
    assert.deepEqual(ac.browserResult.errors, [])
    assert.deepEqual(ac.browserResult.blockedWrites, [])
    assert.deepEqual(
      ac.browserResult.failures.filter(item => !ac.browserResult.injected.includes(item.message)),
      []
    )
    return ac.browserResult
  })
}

/** 中断恢复仅接续管理员只读验收和精确清单清理，不重建夹具，也不恢复员工凭据。 */
async function resume() {
  const output = resolve(process.env.TASK_PERSONAL_TREE_RESUME)
  const manifest = JSON.parse(await readFile(resolve(output, 'manifest.json'), 'utf8'))
  const saved = JSON.parse(await readFile(resolve(output, 'result.json'), 'utf8'))
  assert.match(manifest.prefix, /^pt[a-z0-9]+$/)
  assert.equal(saved.prefix, manifest.prefix)
  const ac = new PersonalTreeAcceptance()
  ac.prefix = manifest.prefix
  ac.output = output
  ac.taskIds = manifest.taskIds
  ac.owned = Object.fromEntries(
    ['objects', 'users', 'roles', 'departments', 'applications'].map(key => [key, manifest[key] || []])
  )
  ac.results = saved.results.filter(item => !/^0[89] /.test(item.name))
  ac.cleanup = saved.cleanup || []
  ac.errors = saved.errors || []
  ac.browserResult = saved.browser
  let browser
  try {
    await ac.login()
    const page = await ac.taskApi('page', {
      scope: 'MANAGE',
      tab: 'ALL',
      rootsOnly: true,
      search: ac.title('管理员总任务'),
      pageNo: 1,
      pageSize: 100
    })
    assert.equal(page.list.length, 1)
    assert.ok(ac.taskIds.includes(page.list[0].id))
    const detail = await ac.taskApi('detail', { id: page.list[0].id })
    assert.ok(detail.nodes.every(task => ac.taskIds.includes(task.id) && task.title.startsWith(ac.prefix)))
    const tasks = { admin: [detail.task, detail.nodes.find(task => task.parentId === detail.task.id)] }
    assert.ok(tasks.admin[1])
    browser = await chromium.launch({ channel: 'chrome', headless: true })
    await verifyBrowser(ac, browser, tasks, true)
  } catch (error) {
    ac.errors.push(error.stack || error.message)
  } finally {
    if (browser) await browser.close()
    if (ac.tokens.admin) await ac.finish()
    await ac.persist()
    console.log(
      JSON.stringify({
        output,
        results: ac.results.map(({ name, status }) => ({ name, status })),
        cleanup: ac.cleanup,
        errors: ac.errors
      })
    )
    if (
      ac.results.some(item => item.status !== 'passed') ||
      ac.cleanup.some(item => item.status === 'failed') ||
      ac.errors.length
    )
      process.exitCode = 1
  }
}

if (process.env.TASK_PERSONAL_TREE_RESUME) await resume()
else if (process.env.TASK_PERSONAL_TREE_WRITE_RUN === '1') await run()
else
  console.log(
    JSON.stringify({
      mode: 'prepare-only',
      writes: 0,
      fixtures: '2 临时员工、1权限角色、23独立任务节点；不建应用或对象',
      scenarios: '个人归并/跨层/搜索/计划/入口分页/宽窄滚动/失败重试/旧响应隔离/管理员边界'
    })
  )
