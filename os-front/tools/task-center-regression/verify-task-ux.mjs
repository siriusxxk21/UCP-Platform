import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

if (['acceptance', 'supplement'].includes(process.env.TASK_UX_PHASE)) {
  if (process.env.TASK_UX_WRITE_RUN !== '1') {
    console.log(
      JSON.stringify({
        mode: 'prepare-only',
        writes: 0,
        fixtures: '1 对象、1 应用、1 员工、2 普通任务和含 2 个子项的总任务'
      })
    )
  } else await runTaskUxAcceptance()
  process.exit(process.exitCode || 0)
}

// 只读 UX 走查：沿用管理员认证，不建立夹具；浏览器拒绝白名单以外的一切写请求。
const output = resolve(process.env.TASK_UX_OUTPUT || `.work/task-ux/${Date.now()}`)
const ac = new FormAcceptance('http://127.0.0.1:8080/api', output)
const expect = playwrightExpect.configure({ timeout: 20000 })
const report = {
  output,
  captures: [],
  errors: [],
  blockedWrites: [],
  apiFailures: [],
  readResponses: [],
  requestFailures: [],
  browserNavigation: [],
  devReloads: []
}
const reads = new Set([
  '/nocode/tasks/page',
  '/nocode/tasks/personal-tree-page',
  '/nocode/tasks/personal-tree-children',
  '/nocode/tasks/page-tasks',
  '/nocode/tasks/detail',
  '/nocode/tasks/readiness',
  '/nocode/tasks/draft-get',
  '/nocode/tasks/form',
  '/nocode/tasks/form-preview',
  '/nocode/tasks/material',
  '/nocode/tasks/entries/list',
  '/nocode/tasks/entries/page',
  '/nocode/tasks/entries/form',
  '/nocode/tasks/entries/materials',
  '/nocode/tasks/entries/handling-location',
  '/nocode/tasks/record-link-candidates',
  '/nocode/tasks/adjust-preview',
  '/nocode/runtime/page',
  '/nocode/runtime/get',
  '/nocode/runtime/form',
  '/nocode/runtime/report'
])
let browser
let activeBrowserPage
let ownedArrangementDraft
const arrangementTitle = `编排验收-${Date.now()}：整件施工`
try {
  await mkdir(output, { recursive: true })
  await ac.login()
  const info = await ac.api('/system/auth/get-permission-info')
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  activeBrowserPage = page
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame()) report.browserNavigation.push({ url: frame.url(), time: new Date().toISOString() })
  })
  page.on('console', item => {
    if (/^\[vite\]/.test(item.text())) report.devReloads.push({ message: item.text(), time: new Date().toISOString() })
  })
  page.on('pageerror', error => report.errors.push(error.message))
  page.on('requestfailed', request => {
    if (request.url().includes('/api/'))
      report.requestFailures.push({ path: new URL(request.url()).pathname, error: request.failure()?.errorText })
  })
  page.on('response', async response => {
    if (!response.url().includes('/api/')) return
    try {
      const body = await response.json()
      const item = { path: new URL(response.url()).pathname, status: response.status(), code: body.code }
      report.readResponses.push(item)
      if (body.code !== undefined && body.code !== 0) report.apiFailures.push({ ...item, message: body.msg })
    } catch {
      /* 仅 JSON 接口入证据，不读取认证报文。 */
    }
  })
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
    { token: ac.tokens.admin, info }
  )
  await page.route('**/api/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (
      process.env.TASK_UX_PHASE === 'arrangement' &&
      process.env.TASK_UX_WRITE_RUN === '1' &&
      path === '/nocode/tasks/draft-save'
    ) {
      const body = request.postDataJSON()
      if (
        body.content?.task?.title === arrangementTitle &&
        body.id &&
        (!ownedArrangementDraft || ownedArrangementDraft.id === body.id)
      ) {
        ownedArrangementDraft = { id: body.id }
        return route.continue()
      }
    }
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || reads.has(path)) return route.continue()
    report.blockedWrites.push({ path, method: request.method() })
    return route.abort('blockedbyclient')
  })
  const settle = async () => {
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    await page.waitForLoadState('networkidle').catch(() => {})
  }
  const capture = async name => {
    await settle()
    const screenshot = resolve(output, `${name}.png`)
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
    await writeFile(resolve(output, `${name}.txt`), await page.locator('body').innerText())
    report.captures.push({ name, screenshot, url: page.url(), dialogs: await page.getByRole('dialog').count() })
    await writeFile(resolve(output, 'result.json'), JSON.stringify(report, null, 2))
  }
  const goto = async path => {
    await page.goto(`http://127.0.0.1:5173${path}`)
    await expect(page.locator('.task-workspace, .app-runtime, .runtime-page').first())
      .toBeVisible()
      .catch(() => {})
    await settle()
  }
  const phase = process.env.TASK_UX_PHASE || 'overview'
  if (phase === 'compact-heading') {
    report.compactHeading = []
    for (const width of [1512, 1100]) {
      await page.setViewportSize({ width, height: width === 1512 ? 1050 : 760 })
      for (const [suffix, name] of [
        ['', 'mine'],
        ['/manage', 'manage'],
        ['/templates', 'templates']
      ]) {
        await goto(`/nocode-app/task-center${suffix}`)
        const workspace = page.locator('.task-workspace')
        await expect(workspace.locator('.task-page-heading, h2')).toHaveCount(0)
        await expect(workspace.getByRole('button', { name: '业务申请与办理草稿', exact: true })).toHaveCount(0)
        if (!suffix) {
          await expect(workspace.getByRole('button', { name: '任务池与任务草稿', exact: true })).toBeInViewport()
        } else {
          await expect(
            workspace.getByRole('button', { name: suffix === '/manage' ? '新建任务' : '新建模板', exact: true })
          ).toBeInViewport()
        }
        report.compactHeading.push({
          name,
          width,
          workspace: await workspace.boundingBox()
        })
        await capture(`compact-${name}-${width}`)
      }
    }
    await goto('/nocode-app/task-center')
    await page.getByRole('button', { name: '任务池与任务草稿', exact: true }).click()
    await expect(page).toHaveURL(/task-center\/manage$/)
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
  }
  if (phase === 'list-scroll') {
    await goto('/nocode-app/task-center')
    const list = page.locator('.task-list').first()
    const taskRows = list.locator('.ant-table-tbody > tr.ant-table-row')
    report.listScroll = []
    const metrics = () =>
      list.evaluate(element => ({
        containers: Array.from(
          element.querySelectorAll('.ant-table-container, .ant-table-content, .ant-table-body, .ant-spin-container')
        ).map(item => ({
          className: item.className,
          clientHeight: item.clientHeight,
          scrollHeight: item.scrollHeight,
          scrollTop: item.scrollTop,
          overflowY: getComputedStyle(item).overflowY,
          top: item.getBoundingClientRect().top,
          bottom: item.getBoundingClientRect().bottom
        })),
        headerTop: element.querySelector('.ant-table-thead')?.getBoundingClientRect().top,
        pagerTop: element.querySelector('.ant-pagination')?.getBoundingClientRect().top,
        lastBottom: element.querySelector('.ant-table-tbody > tr.ant-table-row:last-child')?.getBoundingClientRect()
          .bottom,
        documentScroll: document.scrollingElement?.scrollTop
      }))
    for (const [width, height, pageSize] of [
      [1512, 1050, 10],
      [1100, 760, 10],
      [1100, 760, 20]
    ]) {
      await page.setViewportSize({ width, height })
      if (pageSize === 20) {
        await list.locator('.ant-pagination-options-size-changer .ant-select-selector').click()
        await page.locator('.ant-select-dropdown:visible').getByText('20 条/页', { exact: true }).click()
      }
      await settle()
      assert.equal(await taskRows.count(), pageSize)
      await list.locator('.ant-table-container').hover()
      await page.mouse.wheel(0, -4000)
      await page.waitForTimeout(250)
      const before = await metrics()
      assert.ok(
        before.containers.every(item => item.scrollTop === 0),
        '向上滚动应能回到首行'
      )
      await list.locator('.ant-table-container').hover()
      await page.mouse.wheel(0, 4000)
      await page.waitForTimeout(250)
      const after = await metrics()
      report.listScroll.push({ width, height, pageSize, before, after })
      await capture(`task-list-scroll-${width}-${pageSize}`)
      assert.ok(
        after.containers.some(item => item.scrollTop > 0),
        '滚轮必须能滚动表格内部，而不是裁掉下方任务'
      )
      assert.ok(after.lastBottom <= after.pagerTop + 1, '滚动到底后最后一行不能被分页器遮挡')
      assert.ok(Math.abs(after.headerTop - before.headerTop) <= 1, '纵向滚动时表头保持可见')
      assert.ok(Math.abs(after.pagerTop - before.pagerTop) <= 1, '分页器保持在表格底部')
      assert.equal(after.documentScroll, before.documentScroll)
    }
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
  }
  if (phase === 'list-pagination-expansion') {
    const targetTitle = process.env.TASK_EXPAND_TITLE || 'famum5348s 编排实例'
    const pageNumber = Number(process.env.TASK_EXPAND_PAGE || 3)
    await goto('/nocode-app/task-center')
    const list = page.locator('.task-list').first()
    const taskRows = list.locator('.ant-table-tbody > tr.ant-table-row')
    if (pageNumber > 1) await list.locator(`.ant-pagination-item-${pageNumber}`).click()
    await settle()
    const before = await taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key')))
    const totalText = await list.locator('.ant-pagination-total-text').innerText()
    assert.ok(before.length > 0)
    await list.getByRole('button', { name: targetTitle, exact: true }).click()
    const drawer = page.locator('.task-hierarchy-drawer:visible')
    await expect(drawer).toBeVisible()
    await drawer.locator('.ant-drawer-close').click()
    await expect(drawer).toHaveCount(0)
    assert.deepEqual(await taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key'))), before)
    await capture('paginated-before-expand')
    await list.getByRole('button', { name: `展开子任务：${targetTitle}`, exact: true }).click()
    await expect.poll(() => taskRows.count()).toBeGreaterThan(before.length)
    await capture('paginated-after-expand')
    const after = await taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key')))
    report.listPaginationExpansion = { pageNumber, targetTitle, before, after, totalText }
    assert.ok(
      before.every(id => after.includes(id)),
      '展开后必须保留当前页原有任务，不能再次分页截断'
    )
    await expect(list.locator('.ant-pagination-item-active')).toHaveText(String(pageNumber))
    await expect(list.locator('.ant-pagination-total-text')).toHaveText(totalText)
    await list.getByRole('button', { name: `收起子任务：${targetTitle}`, exact: true }).click()
    await settle()
    assert.deepEqual(await taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key'))), before)
    const failureMessage = 'UX验收：子任务读取暂时失败'
    const detailPattern = '**/api/nocode/tasks/personal-tree-children'
    await page.route(detailPattern, route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, msg: failureMessage, data: null })
      })
    )
    await list.getByRole('button', { name: `展开子任务：${targetTitle}`, exact: true }).click()
    await expect(list.locator('.ant-alert-error')).toContainText(failureMessage)
    const failedRows = await taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key')))
    assert.ok(
      before.every(id => failedRows.includes(id)),
      '子任务读取失败不能清空当前分页任务'
    )
    await expect(list.locator('.ant-pagination-total-text')).toHaveText(totalText)
    await capture('pagination-child-load-failure')
    await page.unroute(detailPattern)
    await list.locator('.ant-alert-close-icon').click()
    await list.getByRole('button', { name: `展开子任务：${targetTitle}`, exact: true }).click()
    await settle()
    await expect
      .poll(() => taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key'))))
      .toEqual(after)
    await list.getByRole('button', { name: `收起子任务：${targetTitle}`, exact: true }).click()
    report.listPaginationExpansion.injectedFailure = failureMessage
    await list.locator(`.ant-pagination-item-${pageNumber + 1}`).click()
    await settle()
    assert.ok((await taskRows.count()) > 0)
    await expect(list.locator('.ant-pagination-item-active')).toHaveText(String(pageNumber + 1))
    await list.locator(`.ant-pagination-item-${pageNumber}`).click()
    await settle()
    assert.deepEqual(await taskRows.evaluateAll(rows => rows.map(row => row.getAttribute('data-row-key'))), before)
    await list.locator('.ant-pagination-options-size-changer .ant-select-selector').click()
    await page.locator('.ant-select-dropdown:visible').getByText('20 条/页', { exact: true }).click()
    await settle()
    await expect(list.locator('.ant-pagination-item-active')).toHaveText('1')
    await expect(list.locator('.ant-pagination-total-text')).toHaveText(totalText)
    assert.equal(await taskRows.count(), 20)
    await capture('pagination-page-size-20')
    assert.deepEqual(report.errors, [])
    assert.equal(report.apiFailures.filter(item => item.message === failureMessage).length, 1)
    assert.deepEqual(
      report.apiFailures.filter(item => item.message !== failureMessage),
      []
    )
    assert.deepEqual(report.blockedWrites, [])
  }
  if (phase === 'management-groups') {
    await goto('/nocode-app/task-center/manage')
    report.managementGroups = []
    for (const [status, label] of [
      ['PENDING', '未开始'],
      ['RUNNING', '进行中'],
      ['COMPLETED', '已完成'],
      ['CANCELLED', '已取消']
    ]) {
      await page.getByRole('tab', { name: label, exact: true }).click()
      await settle()
      const query = { scope: 'MANAGE', tab: 'ALL', rootsOnly: true, status, pageNo: 1, pageSize: 10 }
      const first = await ac.api('/nocode/tasks/page', query)
      const second = await ac.api('/nocode/tasks/page', { ...query, pageNo: 2 })
      const roots = [...first.list, ...second.list]
      assert(roots.every(row => row.id === row.rootId && !row.parentId))
      assert(roots.every(row => (row.groupStatus || row.status) === status))
      assert.equal(new Set(roots.map(row => row.id)).size, roots.length, '两页总任务不得重复')
      const displayed = page.locator('.task-list .task-hierarchy')
      assert(
        (await displayed.evaluateAll(items => items.map(item => item.dataset.depth))).every(depth => depth === '0')
      )
      await capture(`management-${status.toLowerCase()}`)
      const expandable = page.getByRole('button', { name: /^展开子任务：/ }).first()
      let expanded = false
      if (await expandable.count()) {
        await expandable.click()
        await settle()
        const children = page.locator('.task-list .task-hierarchy[data-depth="1"]')
        await expect(children.first()).toBeVisible()
        const keys = await page
          .locator('.task-list tr[data-row-key]')
          .evaluateAll(rows => rows.map(row => row.dataset.rowKey))
        assert.equal(new Set(keys).size, keys.length, '展开后节点不得重复')
        await capture(`management-${status.toLowerCase()}-expanded`)
        expanded = true
      }
      report.managementGroups.push({ status, total: first.total, sampledRoots: roots.length, expanded })
    }
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
  }
  if (phase === 'arrangement') {
    await goto('/nocode-app/task-center/manage')
    await page.getByRole('button', { name: '新建任务', exact: true }).click()
    const launch = page.locator('.ant-drawer-content:visible').last()
    await launch.getByPlaceholder('填写可执行的任务名称').fill(arrangementTitle)
    const table = launch.locator('.task-node-editor')
    await table.getByRole('button', { name: '添加连续步骤', exact: true }).click()
    const batch = page.locator('.ant-modal-content:visible').last()
    await batch.getByLabel('批量任务名称').fill('A 准备\nB 施工\nC 验收')
    await batch.getByRole('button', { name: '添加到编排', exact: true }).click()
    // 直接取输入值，不依赖 AntD 把响应式 value 回写为 HTML 属性。
    const rowByName = async name => {
      const input = table.getByRole('textbox').filter({ visible: true })
      for (const item of await input.all())
        if ((await item.inputValue()) === name) return item.locator('xpath=ancestor::tr[1]')
      throw new Error(`缺少任务行：${name}`)
    }
    const nameNewNode = async title => {
      for (const input of await table.getByPlaceholder('任务名称，直接在列表中填写').filter({ visible: true }).all()) {
        if (!(await input.inputValue())) {
          await input.fill(title)
          return input.locator('xpath=ancestor::tr[1]')
        }
      }
      throw new Error('新增任务输入应存在')
    }
    await (await rowByName('A 准备')).getByRole('button', { name: '添加下一步', exact: true }).click()
    await nameNewNode('X 进场检查')
    await expect((await rowByName('B 施工')).locator('.task-node-editor__dependencies')).toContainText('X 进场检查')
    await (await rowByName('X 进场检查')).getByRole('button', { name: '更多操作：X 进场检查', exact: true }).click()
    await page.getByRole('menuitem', { name: '添加并行任务', exact: true }).click()
    await nameNewNode('P 并行备料')
    await expect((await rowByName('P 并行备料')).locator('.task-node-editor__dependencies')).toContainText('A 准备')
    const merged = (await rowByName('B 施工')).locator('.task-node-editor__dependencies')
    await expect(merged).toContainText('X 进场检查')
    await expect(merged).toContainText('P 并行备料')
    await expect(table.getByRole('button', { name: /设置先后/ })).toHaveCount(0)
    await expect(page.locator('.task-dependency-picker')).toHaveCount(0)
    for (const [parent, title] of [
      ['A 准备', 'A1 勘察'],
      ['A1 勘察', 'A1.1 测量'],
      ['A1.1 测量', 'A1.1.1 标记']
    ]) {
      await (await rowByName(parent)).getByRole('button', { name: '拆分子任务', exact: true }).click()
      const child = await nameNewNode(title)
      await expect(child.locator('.task-node-editor__dependencies')).toContainText('可从开始处开展')
    }
    await expect(table.locator('tr[data-row-key]')).toHaveCount(9)
    await table.scrollIntoViewIfNeeded()
    await capture('arrangement-list-four-levels')
    await table.getByRole('button', { name: '图上编排', exact: true }).click()
    const graphDrawer = page.locator('.ant-drawer-content:visible').last()
    const graph = graphDrawer.locator('.task-dag')
    await expect(graph).toBeVisible()
    await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(5)
    await expect(graph.locator('.task-dag__container--root')).toHaveCount(1)
    await expect(graph.locator('[data-boundary="START"]')).toHaveCount(1)
    await expect(graph.locator('[data-boundary="END"]')).toHaveCount(1)
    const nodeByTitle = name => graph.locator('.task-dag__node').filter({ has: page.getByText(name, { exact: true }) })
    await nodeByTitle('B 施工').click()
    await graph.getByRole('button', { name: '拆分子任务', exact: true }).click()
    await capture('arrangement-graph-new-child')
    await graph.getByRole('button', { name: /改\s*名/ }).click()
    await graph.getByLabel('任务名称', { exact: true }).fill('B1 图上拆分')
    await graph.getByRole('button', { name: '确认改名', exact: true }).click()
    await expect(graph.locator('.task-dag__title').filter({ hasText: 'B1 图上拆分' })).toHaveCount(1)
    await nodeByTitle('C 验收').click()
    await graph.getByRole('button', { name: '连接后续任务', exact: true }).click()
    await nodeByTitle('A 准备').click()
    await expect(graphDrawer.getByText('任务依赖存在循环或父子等待死锁，请调整前置关系')).toBeVisible()
    await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(5)
    await graph.getByRole('button', { name: '适配画布', exact: true }).click()
    await capture('arrangement-graph-nested-editable')
    const chooseDependency = async (from, to) => {
      const point = await graph
        .getByRole('button', { name: `选择依赖：${from} → ${to}`, exact: true })
        .evaluate(path => {
          const point = path.getPointAtLength(path.getTotalLength() / 2)
          const screen = new DOMPoint(point.x, point.y).matrixTransform(path.getScreenCTM())
          return { x: screen.x, y: screen.y }
        })
      await page.mouse.click(point.x, point.y)
    }
    await chooseDependency('B 施工', 'C 验收')
    await graph.getByRole('button', { name: '断开依赖', exact: true }).click()
    await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(4)
    await nodeByTitle('B 施工').click()
    await graph.getByRole('button', { name: '连接后续任务', exact: true }).click()
    await nodeByTitle('C 验收').click()
    await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(5)
    await chooseDependency('B 施工', 'C 验收')
    await graph.getByRole('button', { name: '插入步骤', exact: true }).click()
    await graph.getByRole('button', { name: /改\s*名/ }).click()
    await graph.getByLabel('任务名称', { exact: true }).fill('Y 图上交接')
    await graph.getByRole('button', { name: '确认改名', exact: true }).click()
    await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(6)
    await expect(graph.getByRole('button', { name: '选择依赖：B 施工 → C 验收', exact: true })).toHaveCount(0)
    for (const name of ['选择依赖：B 施工 → Y 图上交接', '选择依赖：Y 图上交接 → C 验收']) {
      const edge = graph.getByRole('button', { name, exact: true })
      await expect(edge).toHaveCount(1)
      // 水平 SVG 路径的几何高度为零，不能用 HTML 可见性断言代替实际描边与画布坐标检查。
      const geometry = await edge.evaluate(path => {
        const line = path.parentElement.querySelector('[data-relation="predecessor"]')
        const point = line.getPointAtLength(line.getTotalLength() / 2)
        const screen = new DOMPoint(point.x, point.y).matrixTransform(line.getScreenCTM())
        const bounds = line.closest('svg').getBoundingClientRect()
        const style = getComputedStyle(line)
        return {
          length: line.getTotalLength(),
          drawn: style.stroke !== 'none' && style.display !== 'none' && style.visibility !== 'hidden',
          inCanvas:
            screen.x >= bounds.left && screen.x <= bounds.right && screen.y >= bounds.top && screen.y <= bounds.bottom
        }
      })
      assert.ok(geometry.length > 0 && geometry.drawn && geometry.inCanvas, `${name} 应有画布内的实际连线`)
    }
    await page.setViewportSize({ width: 1100, height: 760 })
    await graph.getByRole('button', { name: '适配画布', exact: true }).click()
    await capture('arrangement-graph-1100')
    await graphDrawer.locator('.ant-drawer-close').click()
    await expect(graph).toBeHidden()
    await settle()
    await capture('arrangement-list-after-graph')
    await expect((await rowByName('B1 图上拆分')).locator('input.ant-input')).toHaveValue('B1 图上拆分')
    await expect(table.locator('tr[data-row-key]')).toHaveCount(11)
    await expect((await rowByName('C 验收')).locator('.task-node-editor__dependencies')).toContainText('Y 图上交接')
    const b = await rowByName('B 施工')
    await expect(b.locator('.task-node-editor__dependencies')).toContainText('X 进场检查')
    await expect(b.locator('.task-node-editor__dependencies')).toContainText('P 并行备料')
    await expect(b.locator('.task-node-editor__dependencies button')).toHaveCount(0)
    await b.getByRole('button', { name: '配置', exact: true }).click()
    const configuration = page.locator('.ant-drawer-content:visible').last()
    await expect(configuration.getByLabel('任务顺序', { exact: true })).toContainText('X 进场检查')
    await expect(configuration.getByLabel('任务顺序', { exact: true })).toContainText('P 并行备料')
    await expect(configuration.locator('.task-dependency-picker')).toHaveCount(0)
    await capture('arrangement-readonly-order-summary')
    await configuration.locator('.ant-drawer-close').click()
    report.arrangement = {
      overallTasks: 1,
      totalNodes: 11,
      dependencyEdges: 6,
      maxDepth: 4,
      graphRoundTrip: true,
      cycleRejected: true,
      nextStepInserted: true,
      parallelJoined: true,
      orderSelectionRemoved: true,
      edgeInserted: true,
      diagramBoundaryPair: true
    }
    if (process.env.TASK_UX_WRITE_RUN === '1') {
      const saved = page.waitForResponse(response => response.url().endsWith('/nocode/tasks/draft-save'))
      await launch.getByRole('button', { name: '保存草稿', exact: true }).click()
      const response = await (await saved).json()
      assert.equal(response.code, 0)
      assert.equal(response.data.content.task.title, arrangementTitle)
      assert.equal(response.data.content.nodes.length, 10)
      assert.equal(response.data.content.nodes.filter(node => node.parentId === null).length, 6)
      assert.equal(
        response.data.content.nodes.reduce((total, node) => total + node.predecessorIds.length, 0),
        6
      )
      const savedNode = title => {
        const node = response.data.content.nodes.find(node => node.title === title)
        assert.ok(node, `草稿应保留任务：${title}`)
        return node
      }
      assert.deepEqual(savedNode('X 进场检查').predecessorIds, [savedNode('A 准备').id])
      assert.deepEqual(savedNode('P 并行备料').predecessorIds, [savedNode('A 准备').id])
      assert.deepEqual(savedNode('B 施工').predecessorIds, [savedNode('X 进场检查').id, savedNode('P 并行备料').id])
      assert.deepEqual(savedNode('Y 图上交接').predecessorIds, [savedNode('B 施工').id])
      assert.deepEqual(savedNode('C 验收').predecessorIds, [savedNode('Y 图上交接').id])
      for (const title of ['A1 勘察', 'A1.1 测量', 'A1.1.1 标记', 'B1 图上拆分'])
        assert.deepEqual(savedNode(title).predecessorIds, [])
      ownedArrangementDraft = { id: response.data.id }
      await goto('/nocode-app/task-center/manage')
      await page.getByRole('button', { name: '我的草稿', exact: true }).click()
      await page
        .locator('tr')
        .filter({ hasText: arrangementTitle })
        .getByRole('button', { name: '继续编辑', exact: true })
        .click()
      await expect(page.getByPlaceholder('填写可执行的任务名称')).toHaveValue(arrangementTitle)
      await expect(page.locator('.task-node-editor tr[data-row-key]')).toHaveCount(11)
      const restored = page.locator('.task-node-editor')
      await expect(restored).toContainText('X 进场检查')
      await expect(restored).toContainText('P 并行备料')
      await expect(restored).toContainText('Y 图上交接')
      await expect(restored.getByRole('button', { name: /设置先后/ })).toHaveCount(0)
      await capture('arrangement-draft-restored')
      report.arrangement.draftRoundTrip = true
    }
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
  }
  if (phase === 'arrangement-boundaries') {
    report.arrangementBoundaries = { writes: 0, scenarios: [] }
    const openEditor = async title => {
      await page.setViewportSize({ width: 1512, height: 1050 })
      await goto('/nocode-app/task-center/manage')
      await page.getByRole('button', { name: '新建任务', exact: true }).click()
      const launch = page.locator('.ant-drawer-content:visible').last()
      await launch.getByPlaceholder('填写可执行的任务名称').fill(title)
      return launch.locator('.task-node-editor')
    }
    const addInternalWork = async (table, title) => {
      await table.getByRole('button', { name: '拆分子任务', exact: true }).first().click()
      for (const input of await table.getByPlaceholder('任务名称，直接在列表中填写').all()) {
        if (!(await input.inputValue())) {
          await input.fill(title)
          return
        }
      }
      throw new Error('拆分子任务后应存在名称输入框')
    }
    const openGraph = async table => {
      await table.getByRole('button', { name: '图上编排', exact: true }).click()
      const graph = page.locator('.ant-drawer-content:visible').last().locator('.task-dag')
      await expect(graph).toBeVisible()
      return graph
    }
    const node = (graph, title) =>
      graph.locator('.task-dag__node').filter({ has: page.getByText(title, { exact: true }) })
    const renameSelected = async (graph, title) => {
      await graph.getByRole('button', { name: /改\s*名/ }).click()
      await graph.getByLabel('任务名称', { exact: true }).fill(title)
      await graph.getByRole('button', { name: '确认改名', exact: true }).click()
    }
    const verifyBoundary = async (graph, name, nodeCount, starts, ends, dependencies) => {
      await expect(graph.locator('.task-dag__container--root')).toHaveCount(1)
      await expect(graph.locator('[data-boundary="START"]')).toHaveCount(1)
      await expect(graph.locator('[data-boundary="END"]')).toHaveCount(1)
      await expect(graph.locator('[data-task-id]')).toHaveCount(nodeCount)
      await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(dependencies.length)
      const actual = await graph.evaluate(element => {
        const titles = new Map(
          Array.from(element.querySelectorAll('[data-task-id]')).map(item => [
            item.getAttribute('data-task-id'),
            item.querySelector('.task-dag__title')?.textContent?.trim()
          ])
        )
        const targets = kind =>
          Array.from(element.querySelectorAll(`[data-boundary-edge="${kind}"]`))
            .map(item => titles.get(item.getAttribute('data-boundary-task')))
            .sort()
        return { starts: targets('START'), ends: targets('END') }
      })
      assert.deepEqual(actual.starts, [...starts].sort())
      assert.deepEqual(actual.ends, [...ends].sort())
      for (const [from, to] of dependencies)
        await expect(graph.getByRole('button', { name: `选择依赖：${from} → ${to}`, exact: true })).toHaveCount(1)
      for (const [width, height] of [
        [1512, 1050],
        [1100, 760]
      ]) {
        await page.setViewportSize({ width, height })
        await graph.getByRole('button', { name: '适配画布', exact: true }).click()
        await capture(`boundary-${name}-${width}`)
      }
      report.arrangementBoundaries.scenarios.push({ name, nodeCount, starts, ends, dependencies })
    }
    const independent = await openEditor('只读图示验收：普通并行拆分')
    const empty = await openGraph(independent)
    await verifyBoundary(empty, 'empty', 1, [], [], [])
    await page.locator('.ant-drawer-content:visible').last().locator('.ant-drawer-close').click()
    await addInternalWork(independent, 'A 独立工作')
    await addInternalWork(independent, 'P 独立工作')
    await verifyBoundary(
      await openGraph(independent),
      'independent-two',
      3,
      ['A 独立工作', 'P 独立工作'],
      ['A 独立工作', 'P 独立工作'],
      []
    )
    const parallel = await openEditor('只读图示验收：首项并行与下一步')
    await addInternalWork(parallel, 'A 首项工作')
    const graph = await openGraph(parallel)
    await node(graph, 'A 首项工作').click()
    await graph.getByRole('button', { name: '添加并行任务', exact: true }).click()
    await renameSelected(graph, 'P 首项并行')
    await verifyBoundary(graph, 'first-parallel', 3, ['A 首项工作', 'P 首项并行'], ['A 首项工作', 'P 首项并行'], [])
    await node(graph, 'A 首项工作').click()
    await graph.getByRole('button', { name: '添加下一步', exact: true }).click()
    await renameSelected(graph, 'B 下一步')
    await verifyBoundary(
      graph,
      'first-next',
      4,
      ['A 首项工作', 'P 首项并行'],
      ['B 下一步', 'P 首项并行'],
      [['A 首项工作', 'B 下一步']]
    )
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
    assert.equal(ownedArrangementDraft, undefined, '边界图示验收不得保存草稿或建立正式任务')
  }
  if (phase === 'arrangement-location') {
    report.arrangementLocation = { writes: 0, scenarios: [] }
    for (const context of ['new-task', 'new-template']) {
      await page.setViewportSize({ width: 1512, height: 1050 })
      const title = `归属验收20261003-${context}：办公室装修`
      await goto(context === 'new-task' ? '/nocode-app/task-center/manage' : '/nocode-app/task-center/templates')
      await page.getByRole('button', { name: context === 'new-task' ? '新建任务' : '新建模板', exact: true }).click()
      const editor = page
        .locator('.ant-drawer-content')
        .filter({
          has: page.getByPlaceholder(context === 'new-task' ? '填写可执行的任务名称' : '例如：标准施工项目模板')
        })
        .first()
      await editor
        .getByPlaceholder(context === 'new-task' ? '填写可执行的任务名称' : '例如：标准施工项目模板')
        .fill(title)
      const table = editor.locator('.task-node-editor')
      const rowByName = async name => {
        for (const input of await table.getByRole('textbox').all())
          if ((await input.inputValue()) === name) return input.locator('xpath=ancestor::tr[1]')
        throw new Error(`缺少任务行：${name}`)
      }
      const nameNew = async name => {
        for (const input of await table.getByPlaceholder('任务名称，直接在列表中填写').all())
          if (!(await input.inputValue())) {
            await input.fill(name)
            return
          }
        throw new Error('新增工作应存在空名称输入')
      }
      for (const name of ['A 装修施工', 'B 设备安装', 'C 材料准备']) {
        await table.getByRole('button', { name: '拆分子任务', exact: true }).first().click()
        await nameNew(name)
      }
      await (await rowByName('A 装修施工')).getByRole('button', { name: '添加下一步', exact: true }).click()
      await nameNew('D 墙面粉刷')
      await (await rowByName('D 墙面粉刷')).getByRole('button', { name: '拆分子任务', exact: true }).click()
      await nameNew('D1 基层处理')
      await expect(table.locator('tr[data-row-key]')).toHaveCount(6)
      await table.getByRole('button', { name: '图上编排', exact: true }).click()
      const graphDrawer = page
        .locator('.ant-drawer-content')
        .filter({ has: page.locator('.task-dag') })
        .last()
      const graph = graphDrawer.locator('.task-dag')
      await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(1)
      await expect(graph.getByRole('button', { name: '选择依赖：A 装修施工 → D 墙面粉刷', exact: true })).toHaveCount(1)
      const geometry = []
      for (const [width, height] of [
        [1512, 1050],
        [1100, 760]
      ]) {
        await page.setViewportSize({ width, height })
        await graph.getByRole('button', { name: '适配画布', exact: true }).click()
        // 取实际 SVG 描边采样与卡片屏幕矩形相交，不能只检查布局列或路径字符串。
        const collisions = await graph.evaluate(element => {
          const cards = Array.from(element.querySelectorAll('.task-dag__node')).map(item => ({
            title: item.querySelector('.task-dag__title')?.textContent?.trim(),
            bounds: item.querySelector('.task-dag__card').getBoundingClientRect()
          }))
          const hits = []
          for (const path of element.querySelectorAll('[data-relation="predecessor"], [data-boundary-edge]')) {
            const length = path.getTotalLength()
            for (let distance = 2; distance < length - 2; distance += 2) {
              const local = path.getPointAtLength(distance)
              const point = new DOMPoint(local.x, local.y).matrixTransform(path.getScreenCTM())
              const hit = cards.find(
                ({ bounds }) =>
                  point.x > bounds.left + 2 &&
                  point.x < bounds.right - 2 &&
                  point.y > bounds.top + 2 &&
                  point.y < bounds.bottom - 2
              )
              if (hit) {
                hits.push({
                  edge:
                    path.getAttribute('data-edge-id') ||
                    `${path.getAttribute('data-boundary-edge')}:${path.getAttribute('data-boundary-task')}`,
                  card: hit.title
                })
                break
              }
            }
          }
          return hits
        })
        assert.deepEqual(collisions, [], `${context} ${width}px 连线不应穿过任务卡片`)
        geometry.push({ width, collisions })
        await capture(`location-${context}-edges-${width}`)
      }
      await graphDrawer.locator('.ant-drawer-close').click()
      await expect(graph).toBeHidden()
      const openMove = async () => {
        await (await rowByName('B 设备安装')).getByRole('button', { name: '更多操作：B 设备安装', exact: true }).click()
        await page.getByRole('menuitem', { name: '移动到其他任务下', exact: true }).click()
        const modal = page.locator('.ant-modal-content:visible').last()
        await expect(modal.locator('.ant-modal-title')).toHaveText('移动到其他任务下')
        return modal
      }
      const choose = async (modal, target) => {
        await modal.locator('[aria-label="移入任务"]').click()
        await page.locator('.ant-select-dropdown:visible').getByText(target, { exact: true }).click()
      }
      let modal = await openMove()
      await expect(modal).toContainText(`当前所属：${title}`)
      await choose(modal, `${title} / C 材料准备`)
      await expect(modal).toContainText(`${title} / C 材料准备`)
      await capture(`location-${context}-move-preview`)
      await modal.getByRole('button', { name: /^取\s*消$/ }).click()
      await expect((await rowByName('B 设备安装')).locator('.task-hierarchy')).toHaveAttribute('data-depth', '1')
      modal = await openMove()
      await choose(modal, `${title} / C 材料准备`)
      await modal.getByRole('button', { name: '确认移动', exact: true }).click()
      await expect(modal).toBeHidden()
      await expect((await rowByName('B 设备安装')).locator('.task-hierarchy')).toHaveAttribute('data-depth', '2')
      await (await rowByName('B 设备安装')).getByRole('button', { name: '配置', exact: true }).click()
      const config = page
        .locator('.ant-drawer-content')
        .filter({ has: page.getByLabel('所属位置', { exact: true }) })
        .last()
      await expect(config.getByLabel('所属位置', { exact: true })).toContainText(`${title} / C 材料准备`)
      await expect(config.getByText('直接上级任务', { exact: true })).toHaveCount(0)
      await expect(config.getByPlaceholder('未指定时属于总任务的一级子任务')).toHaveCount(0)
      await capture(`location-${context}-configuration-path`)
      await config.locator('.ant-drawer-close').click()
      await expect(config).toBeHidden()
      await table.getByRole('button', { name: '图上编排', exact: true }).click()
      await graph
        .locator('.task-dag__node')
        .filter({ has: page.getByText('C 材料准备', { exact: true }) })
        .click()
      await graph.getByRole('button', { name: '删除任务', exact: true }).click()
      let removal = page.locator('.ant-modal-content:visible').last()
      await expect(removal).toContainText(/2\s*(个|项)/)
      await capture(`location-${context}-delete-subtree-confirmation`)
      await removal.getByRole('button', { name: /^取\s*消$/ }).click()
      await expect(graph.locator('[data-task-id]')).toHaveCount(6)
      await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(1)
      await graph
        .locator('.task-dag__node')
        .filter({ has: page.getByText('D 墙面粉刷', { exact: true }) })
        .click()
      await graph.getByRole('button', { name: '删除任务', exact: true }).click()
      removal = page.locator('.ant-modal-content:visible').last()
      await expect(removal).toContainText(/2\s*(个|项)/)
      await expect(removal).toContainText(/1\s*条/)
      await removal.locator('.ant-btn-primary').click()
      await expect(removal).toBeHidden()
      await expect(graph.locator('[data-task-id]')).toHaveCount(4)
      await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(0)
      await expect(graph.getByText('D 墙面粉刷', { exact: true })).toHaveCount(0)
      await capture(`location-${context}-deleted-step`)
      await graph
        .locator('.task-dag__node')
        .filter({ has: page.getByText('B 设备安装', { exact: true }) })
        .click()
      await graph.getByRole('button', { name: '图上更多操作', exact: true }).click()
      await page.getByRole('menuitem', { name: '移动到其他任务下', exact: true }).click()
      modal = page.locator('.ant-modal-content:visible').last()
      await choose(modal, title)
      await modal.getByRole('button', { name: '确认移动', exact: true }).click()
      await expect(modal).toBeHidden()
      await expect(graph.locator('[data-relation="predecessor"]')).toHaveCount(0)
      await expect(graph.locator('[data-task-id]')).toHaveCount(4)
      for (const boundary of ['START', 'END']) {
        const titles = await graph.evaluate((element, kind) => {
          const nodes = new Map(
            Array.from(element.querySelectorAll('[data-task-id]')).map(node => [
              node.getAttribute('data-task-id'),
              node.querySelector('.task-dag__title')?.textContent?.trim()
            ])
          )
          return Array.from(element.querySelectorAll(`[data-boundary-edge="${kind}"]`))
            .map(edge => nodes.get(edge.getAttribute('data-boundary-task')))
            .sort()
        }, boundary)
        assert.deepEqual(titles, ['A 装修施工', 'B 设备安装', 'C 材料准备'])
      }
      await capture(`location-${context}-returned-to-root`)
      await graphDrawer.locator('.ant-drawer-close').click()
      await expect(graph).toBeHidden()
      await expect((await rowByName('B 设备安装')).locator('.task-hierarchy')).toHaveAttribute('data-depth', '1')
      report.arrangementLocation.scenarios.push({
        context,
        geometry,
        cancelledWithoutChange: true,
        movedUnderSibling: true,
        readonlyLocation: true,
        graphReturnedToRoot: true,
        cancelledSubtreeDelete: true,
        removedSelectedStepAndEdge: true,
        independentBranchesPreserved: true,
        taskCount: 4
      })
    }
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
    assert.equal(ownedArrangementDraft, undefined, '所属位置只读验收不保存草稿或建立正式任务')
  }
  if (phase === 'launch-layout') {
    await goto('/nocode-app/task-center/manage')
    await page.getByRole('button', { name: '新建任务', exact: true }).click()
    const drawer = page.locator('.ant-drawer-content:visible').last()
    const sections = drawer.locator('.task-optional-sections > .ant-collapse-item')
    const business = sections.nth(0)
    const feedback = sections.nth(1)
    const header = section => section.locator(':scope > .ant-collapse-header')
    const body = drawer.locator('.ant-drawer-body')
    const footer = drawer.locator('.ant-drawer-footer')
    const name = page.getByPlaceholder('填写可执行的任务名称')
    await expect(sections).toHaveCount(2)
    for (const section of [business, feedback]) await expect(header(section)).toHaveAttribute('aria-expanded', 'false')
    await expect(header(business)).toContainText('未关联业务')
    await expect(header(feedback)).toContainText('未配置反馈')
    await expect(drawer.getByText('关联应用', { exact: true })).not.toBeVisible()
    await expect(drawer.getByText('过程反馈授权', { exact: true })).not.toBeVisible()
    await name.fill('只读验收：折叠与独立底栏')
    await expect(drawer.locator('.task-node-editor')).toContainText('只读验收：折叠与独立底栏')
    await header(business).click()
    await expect(drawer.getByText('关联应用', { exact: true })).toBeVisible()
    await header(feedback).click()
    await expect(drawer.getByText('过程反馈授权', { exact: true })).toBeVisible()
    const allData = feedback.getByText('全部业务数据', { exact: true })
    await allData.click()
    await expect(feedback.locator('input[value="ALL"]')).toBeChecked()
    await header(feedback).click()
    await header(feedback).click()
    await expect(feedback.locator('input[value="ALL"]')).toBeChecked()
    await expect(name).toHaveValue('只读验收：折叠与独立底栏')
    report.layout = []
    for (const viewport of [
      { width: 1512, height: 1050 },
      { width: 1100, height: 760 }
    ]) {
      await page.setViewportSize(viewport)
      for (const position of ['middle', 'bottom']) {
        await body.evaluate((element, position) => {
          element.scrollTop =
            position === 'bottom' ? element.scrollHeight : (element.scrollHeight - element.clientHeight) / 2
        }, position)
        await expect(footer.getByRole('button', { name: '保存草稿', exact: true })).toBeInViewport()
        await expect(footer.getByRole('button', { name: '加入任务池', exact: true })).toBeInViewport()
        await expect(drawer.locator('.task-launch__footer')).toHaveCount(1)
        await expect(drawer.locator('.task-launch .task-launch__footer')).toHaveCount(0)
        const geometry = await drawer.evaluate(element => {
          const body = element.querySelector('.ant-drawer-body').getBoundingClientRect()
          const footer = element.querySelector('.ant-drawer-footer').getBoundingClientRect()
          return {
            bodyBottom: body.bottom,
            footerTop: footer.top,
            footerBottom: footer.bottom,
            height: window.innerHeight
          }
        })
        assert.ok(geometry.bodyBottom <= geometry.footerTop + 1, '滚动正文不能与底栏重叠')
        assert.ok(geometry.footerBottom <= geometry.height + 1, '操作底栏不能超出视口')
        report.layout.push({ viewport, position, ...geometry })
        await capture(`layout-${viewport.width}-${position}`)
      }
    }
    await header(business).click()
    await header(feedback).click()
    for (const section of [business, feedback]) await expect(header(section)).toHaveAttribute('aria-expanded', 'false')
    await drawer.locator('.task-optional-sections').scrollIntoViewIfNeeded()
    await capture('layout-collapsed-1100')
    // 只关闭测试浏览器，不保存、入池，也不修改用户已有草稿。
    assert.deepEqual(report.errors, [])
    assert.deepEqual(report.apiFailures, [])
    assert.deepEqual(report.blockedWrites, [])
  }
  if (phase === 'overview') {
    await goto('/nocode-app/task-center')
    await capture('01-my-today')
    for (const [label, name] of [
      ['本周计划', '02-week'],
      ['本月计划', '03-month'],
      ['我的未完成', '04-my-incomplete'],
      ['可领取任务', '05-claimable'],
      ['近期处理', '06-recent']
    ]) {
      const tab = page.getByRole('tab', { name: label, exact: true })
      if (await tab.count()) {
        await tab.click()
        await capture(name)
      }
    }
    await goto('/nocode-app/task-center/manage')
    await capture('07-manage')
    await page.getByRole('button', { name: '我的草稿', exact: true }).click()
    await capture('08-drafts')
    await goto('/nocode-app/task-center/templates')
    await capture('09-templates')
    await page.getByRole('button', { name: '新建模板', exact: true }).click()
    await capture('10-new-template')
    await goto('/nocode-app/task-center/manage')
    await page.getByRole('button', { name: '新建任务', exact: true }).click()
    await capture('11-new-task-top')
    await page.locator('.task-node-editor').scrollIntoViewIfNeeded()
    await expect(page.locator('.task-node-editor')).toBeInViewport()
    await capture('12-new-task-bottom')
  }
  if (phase === 'detail') {
    const id = process.env.TASK_UX_TASK || '0266d63d-f576-455d-a556-28782a93003a'
    await goto(`/nocode-app/task-center?taskId=${id}`)
    await capture('20-detail-top')
    for (const [label, name] of [
      ['业务数据与反馈', '21-business-feedback'],
      ['依赖关系图', '22-dependency'],
      ['执行历史', '23-history']
    ]) {
      await page.getByRole('tab', { name: label, exact: true }).click()
      await capture(name)
    }
    await page.getByRole('tab', { name: '任务信息', exact: true }).click()
    const plan = page.getByRole('button', { name: '安排计划', exact: true })
    if (await plan.count()) {
      await plan.click()
      await capture('24-plan')
    }
    await goto('/nocode-app/task-center?legacy=1')
    await expect(page).toHaveURL(/task-center$/)
    await capture('25-retired-portal-redirect')
  }
  if (phase === 'embedded') {
    const manifest = JSON.parse(await readFile('.work/task-pool/tpmuqsphv7497f/manifest.json', 'utf8'))
    report.fixture = { prefix: manifest.prefix, applications: manifest.applications, records: manifest.records }
    const appId = manifest.applications[0].id
    const record = manifest.records[0]
    await goto(`/nocode-app/runtime?id=${appId}&menu=record_menu&recordId=${record.id}`)
    await capture('30-embedded-record')
    await goto(`/nocode-app/runtime?id=${appId}&menu=app_menu`)
    await capture('31-embedded-application')
  }
  if (phase === 'plan') {
    await goto('/nocode-app/task-center')
    await page.getByRole('button', { name: '计划', exact: true }).first().click()
    await capture('40-plan-dialog')
    await page.locator('.ant-modal-content').getByText('周计划', { exact: true }).click()
    await capture('41-week-plan-dialog')
    await page
      .locator('.ant-modal-content')
      .getByRole('button', { name: /取\s*消/ })
      .click()
    await page.getByRole('button', { name: '办理', exact: true }).first().click()
    await capture('42-active-task-detail')
    const tab = page.getByRole('tab', { name: '业务数据与反馈', exact: true })
    if (await tab.count()) {
      await tab.click()
      await capture('43-active-task-feedback')
    }
  }
  if (phase === 'narrow') {
    await page.setViewportSize({ width: 1100, height: 850 })
    await goto('/nocode-app/task-center/manage')
    await capture('50-manage-1100')
    await page.getByRole('button', { name: '新建任务', exact: true }).click()
    await capture('51-create-1100')
    await page.locator('.task-node-editor').scrollIntoViewIfNeeded()
    await expect(page.locator('.task-node-editor')).toBeInViewport()
    await capture('52-create-tree-1100')
  }
  if (phase === 'list-retry') {
    await goto('/nocode-app/task-center')
    await page.getByRole('tab', { name: '我的未完成', exact: true }).click()
    await settle()
    let failures = 0
    const pattern = '**/api/nocode/tasks/page'
    await page.route(pattern, async route => {
      failures++
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, msg: 'UX验收：列表读取暂时失败', data: null })
      })
    })
    await page.getByRole('button', { name: /刷\s*新/, exact: true }).click()
    await expect(page.locator('.task-list .ant-alert-error')).toContainText('UX验收：列表读取暂时失败')
    await capture('list-failure')
    await page.unroute(pattern)
    await page
      .getByRole('button', { name: /重试|重新加载/ })
      .last()
      .click()
    await expect(page.locator('.task-list .ant-alert-error')).toHaveCount(0)
    await capture('list-recovered')
    assert.ok(failures > 0)
    report.injectedFailures = failures
  }
} catch (error) {
  report.errors.push(error.stack || error.message)
  if (activeBrowserPage && !activeBrowserPage.isClosed()) {
    try {
      const screenshot = resolve(output, 'failure.png')
      await activeBrowserPage.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
      await writeFile(resolve(output, 'failure.txt'), await activeBrowserPage.locator('body').innerText())
      report.captures.push({ name: 'failure', screenshot, url: activeBrowserPage.url() })
    } catch {
      /* 保留原始失败；页面已不可用时不以截屏异常覆盖它。 */
    }
  }
  process.exitCode = 1
} finally {
  await browser?.close()
  if (ownedArrangementDraft) {
    try {
      const draft = await ac.api('/nocode/tasks/draft-get', { id: ownedArrangementDraft.id })
      assert.equal(draft.content.task.title, arrangementTitle, '只允许清理本次创建的任务草稿')
      await ac.api('/nocode/tasks/draft-delete', { id: draft.id, expectedRevision: draft.revision })
      report.arrangementDraftCleanup = { id: draft.id, deleted: true }
    } catch (error) {
      report.errors.push(`草稿清理失败：${error.message}`)
      process.exitCode = 1
    }
  }
  ac.tokens = {}
  await writeFile(resolve(output, 'result.json'), JSON.stringify(report, null, 2))
  console.log(
    JSON.stringify(
      {
        output,
        captures: report.captures.map(item => item.name),
        errors: report.errors,
        blockedWrites: report.blockedWrites,
        apiFailures: report.apiFailures
      },
      null,
      2
    )
  )
}

/** 在主协调者明确部署就绪后启用；不重用旧员工或施工应用，所有身份落精确 manifest。 */
async function runTaskUxAcceptance() {
  const supplemental = process.env.TASK_UX_PHASE === 'supplement'
  class UxAcceptance extends TaskDataPolicyAcceptance {
    constructor() {
      super()
      this.prefix = `ux${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
      this.output = resolve('.work/task-ux', this.prefix)
      this.draftIds = []
    }
    async prepareSupplement() {
      assert.ok(process.env.TASK_UX_MANIFEST, '补验必须指定精确清单')
      const source = JSON.parse(await readFile(resolve(process.env.TASK_UX_MANIFEST), 'utf8'))
      assert.equal(source.source, 'task-ux')
      assert.equal(source.prefix, 'uxmuqwdbd744e7')
      assert.equal(source.applications[0].id, '4765')
      assert.equal(source.objects[0].id, '9223')
      const actor = source.users.find(user => user.id === '2105987704820396033')
      assert.equal(actor?.username, `${source.prefix}a`)
      this.prefix = source.prefix
      this.output = resolve('.work/task-ux', this.prefix, `supplement-${Date.now()}`)
      await this.login()
      const existing = await this.api(`/system/user/get?id=${actor.id}`)
      assert.equal(existing.username, actor.username)
      assert.equal(existing.status, 1)
      this.employeeA = actor.id
      this.owned.users = [actor]
      this.owned.applications = source.applications
      this.owned.objects = source.objects
      this.app = await this.api(`/nocode/application/get?id=${source.applications[0].id}`)
      assert.equal(this.app.application.code, source.applications[0].code)
      this.business = { objectId: source.objects[0].id }
      this.projectRecord = source.records[0]
      this.credentialResets = 0
      let password = `Fa9${randomBytes(6).toString('hex')}`
      await this.api('/system/user/update-password', { id: actor.id, password }, undefined, 'PUT')
      this.credentialResets++
      await this.api('/system/user/update-status', { id: actor.id, status: 0 }, undefined, 'PUT')
      let session = await this.api('/system/auth/login', { username: actor.username, password }, null)
      if (session.loginStatus === 'PASSWORD_CHANGE_REQUIRED') {
        password = `Fa9${randomBytes(6).toString('hex')}`
        session = await this.api(
          '/system/auth/change-required-password',
          { passwordChangeToken: session.passwordChangeToken, newPassword: password },
          null,
          'PUT'
        )
      }
      assert.ok(session.accessToken)
      this.tokens[this.employeeA] = session.accessToken
      const info = await this.api('/system/auth/get-permission-info', undefined, session.accessToken)
      assert.equal(String(info.user.id), this.employeeA)
      this.ordinary = (
        await this.create(
          this.node('补验施工日志完成链', {
            assignmentMode: 'ASSIGNED',
            assigneeId: this.employeeA,
            dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
            entries: [
              {
                key: 'feedback',
                name: '施工日志',
                binding: this.binding('feedback'),
                dataMode: 'ROOT_SHARED',
                sourceNodeId: null,
                sourceEntryKey: null,
                readableFieldIds: null,
                writableFieldIds: null,
                required: true,
                allowAll: false
              }
            ]
          }),
          {
            applicationId: this.applicationId,
            project: {
              applicationId: this.applicationId,
              objectId: this.business.objectId,
              recordId: this.projectRecord.id,
              label: this.title('项目甲')
            }
          }
        )
      ).task
      assert.equal(this.taskIds.length, 1)
    }
    async finish() {
      const token = this.tokens.admin
      await super.finish()
      this.accountChecks = []
      for (const actor of this.owned.users) {
        const user = await this.api(`/system/user/get?id=${actor.id}`, undefined, token)
        assert.equal(user.username, actor.username)
        assert.equal(user.status, 1)
        this.accountChecks.push({ id: actor.id, username: actor.username, status: 'DISABLED' })
      }
    }
    async persist() {
      await FormAcceptance.prototype.persist.call(this)
      await writeFile(
        resolve(this.output, 'manifest.json'),
        JSON.stringify(
          {
            source: 'task-ux',
            prefix: this.prefix,
            ...this.owned,
            taskIds: this.taskIds,
            records: this.records,
            drafts: this.draftIds,
            templates: this.templates,
            retention: '仅清单任务取消、临时员工停用；不动用户应用4430及旧员工'
          },
          null,
          2
        )
      )
    }
    async prepare() {
      await this.login()
      this.adminId = String((await this.api('/system/auth/get-permission-info')).user.id)
      const menus = await this.api('/system/menu/list')
      const selected = new Set(
        menus
          .filter(item => ['nocode:task:query', 'nocode:task:create'].includes(item.permission))
          .map(item => String(item.id))
      )
      assert.ok(selected.size)
      for (const id of [...selected]) {
        let parent = menus.find(item => String(item.id) === id)?.parentId
        while (parent && menus.some(item => String(item.id) === String(parent))) {
          const menu = menus.find(item => String(item.id) === String(parent))
          selected.add(String(menu.id))
          parent = menu.parentId
        }
      }
      const roleId = await this.api('/system/role/create', {
        name: this.title('体验验收成员'),
        code: `${this.prefix}_member`,
        sort: 1,
        status: 0,
        dataScope: 1
      })
      this.owned.roles.push({ id: roleId })
      await this.persist()
      await this.api('/system/permission/assign-role-menu', { roleId, menuIds: [...selected] })
      this.employeeA = await this.user('a')
      await this.api('/system/permission/assign-user-role', { userId: this.employeeA, roleIds: [roleId] })
      this.business = await this.object('record', '任务体验记录', [this.field('name', 'TEXT', '登记内容')])
      this.feedback = this.business
      const resource = (id, kind, name, config) => ({ id, code: id, kind, name, config })
      this.app = await this.api('/nocode/application/save', {
        id: null,
        expectedRevision: null,
        code: `${this.prefix}_app`,
        name: this.title('任务体验验收'),
        description: '仅任务UX独立验收',
        definition: {
          objects: [
            { objectId: this.business.objectId, versionNo: this.business.versionNo, checksum: this.business.checksum }
          ],
          resources: [
            resource('feedback', 'FORM', '施工日志', {
              objectId: this.business.objectId,
              nodes: this.business.definition.fields.map(field => ({
                id: `field_${field.id}`,
                type: 'FIELD',
                fieldId: field.id,
                children: []
              })),
              detailIds: [],
              options: { layout: 'vertical', submitText: '保存记录' }
            }),
            resource('app_page', 'PAGE', '应用任务页', {
              nodes: [{ id: 'app_tasks', type: 'TASKS', text: '应用任务', children: [] }]
            }),
            resource('record_page', 'PAGE', '记录任务页', {
              contextObjectId: this.business.objectId,
              nodes: [{ id: 'record_tasks', type: 'TASKS', text: '本记录任务', children: [] }]
            }),
            resource('app_menu', 'MENU', '应用任务', { targetId: 'app_page' }),
            resource('record_menu', 'MENU', '记录任务', { targetId: 'record_page' })
          ]
        }
      })
      assert.notEqual(this.applicationId, '4430')
      this.owned.applications.push({ id: this.applicationId, code: this.app.application.code })
      await this.persist()
      await this.share(this.business, this.grant(this.business))
      this.app = await this.api('/nocode/application/publish', {
        id: this.applicationId,
        expectedRevision: this.app.application.revision,
        reason: this.title('独立验收发布')
      })
      this.projectRecord = await this.save(this.business, { name: this.title('项目甲') })
      await this.rememberRecord(this.business, this.projectRecord)
      const policy = { version: 1, business: 'GROUP', feedback: 'GROUP' }
      const entries = [
        {
          key: 'feedback',
          name: '施工日志',
          binding: this.binding('feedback'),
          dataMode: 'ROOT_SHARED',
          sourceNodeId: null,
          sourceEntryKey: null,
          readableFieldIds: null,
          writableFieldIds: null,
          required: true,
          allowAll: false
        }
      ]
      this.ordinary = (
        await this.create(
          this.node('登记施工日志', {
            assignmentMode: 'ASSIGNED',
            assigneeId: this.employeeA,
            dataPolicy: policy,
            entries
          }),
          {
            applicationId: this.applicationId,
            project: {
              applicationId: this.applicationId,
              objectId: this.business.objectId,
              recordId: this.projectRecord.id,
              label: this.title('项目甲')
            }
          }
        )
      ).task
      const first = this.node('前置施工', { assignmentMode: 'ASSIGNED', assigneeId: this.employeeA })
      const next = this.node('后续验收', {
        assignmentMode: 'ASSIGNED',
        assigneeId: this.employeeA,
        predecessorIds: [first.id]
      })
      this.group = await this.create(
        this.node('工程总任务', { assignmentMode: 'ASSIGNED', assigneeId: this.adminId, dataPolicy: policy }),
        { nodes: [first, next], applicationId: this.applicationId }
      )
      this.first = this.group.nodes.find(node => node.title === first.title)
      this.next = this.group.nodes.find(node => node.title === next.title)
      assert.equal(this.taskIds.length, 4)
    }
  }
  const qa = new UxAcceptance()
  const expect = playwrightExpect.configure({ timeout: 15000 })
  let browser
  const blockedWrites = [],
    writes = [],
    browserErrors = [],
    apiFailures = []
  let page, employeePage
  const origin = 'http://127.0.0.1:5173'
  const detailUrl = id => `${origin}/nocode-app/task-center?taskId=${id}`
  const writeResult = async () =>
    writeFile(
      resolve(qa.output, 'result.json'),
      JSON.stringify(
        {
          prefix: qa.prefix,
          checks: qa.results,
          cleanup: qa.cleanup,
          errors: qa.errors,
          browserErrors,
          apiFailures,
          screenshots: qa.screenshots,
          blockedWrites,
          writes,
          credentialResets: qa.credentialResets || 0,
          accountChecks: qa.accountChecks || []
        },
        null,
        2
      )
    )
  const snap = async (name, tab = page) => {
    await expect(tab.locator('.ant-spin-spinning')).toHaveCount(0)
    await tab.waitForLoadState('networkidle').catch(() => {})
    await expect(tab.locator('.ant-message-notice')).toHaveCount(0)
    const path = resolve(qa.output, `${name}.png`)
    await tab.screenshot({ path, fullPage: true, animations: 'disabled' })
    await writeFile(resolve(qa.output, `${name}.txt`), await tab.locator('body').innerText())
    qa.screenshots.push(path)
  }
  const check = async (name, work, dependencies = [], target = page) => {
    const result = await qa.check(name, work, dependencies)
    if (result.status === 'failed' && target) await snap(`failure-${qa.results.length}`, target).catch(() => {})
    await writeResult()
    return result
  }
  async function browserPage(token) {
    const info = await qa.api('/system/auth/get-permission-info', undefined, token)
    const tab = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
    tab.setDefaultTimeout(15000)
    tab.on('pageerror', error => browserErrors.push(error.message))
    tab.on('response', async response => {
      if (!response.url().includes('/api/') || response.url().includes('/auth/')) return
      try {
        const result = await response.json()
        if (result.code !== undefined && result.code !== 0) {
          apiFailures.push({
            path: new URL(response.url()).pathname,
            http: response.status(),
            code: result.code,
            message: result.msg,
            injected: String(result.msg).startsWith('UX验收：')
          })
        }
      } catch {
        // 导航取消或非 JSON 资源不读取正文；账号凭据不纳入报告。
      }
    })
    await tab.addInitScript(
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
    await tab.route('**/api/**', async route => {
      const request = route.request(),
        path = new URL(request.url()).pathname.replace(/^\/api/, '')
      const allowedReads = new Set([
        '/nocode/tasks/page',
        '/nocode/tasks/personal-tree-page',
        '/nocode/tasks/personal-tree-children',
        '/nocode/tasks/page-tasks',
        '/nocode/tasks/detail',
        '/nocode/tasks/readiness',
        '/nocode/tasks/draft-get',
        '/nocode/tasks/entries/list',
        '/nocode/tasks/entries/page',
        '/nocode/tasks/entries/form',
        '/nocode/tasks/entries/materials',
        '/nocode/tasks/entries/receipt',
        '/nocode/tasks/entries/selection',
        '/nocode/tasks/entries/fill',
        '/nocode/tasks/entries/related',
        '/nocode/tasks/entries/related-selection',
        '/nocode/tasks/entries/related-fill',
        '/nocode/tasks/form',
        '/nocode/tasks/form-preview',
        '/nocode/tasks/adjust-preview'
      ])
      if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || allowedReads.has(path)) return route.continue()
      const body = request.postDataJSON()
      const ownId = qa.taskIds.includes(body?.id || body?.taskId)
      const saveDraft =
        path === '/nocode/tasks/draft-save' &&
        body?.content?.task?.title?.startsWith(qa.prefix) &&
        (!body.content.applicationId || body.content.applicationId === qa.applicationId)
      const publishDraft = path === '/nocode/tasks/draft-publish' && qa.draftIds.includes(body?.id)
      const ownPlan = path === '/nocode/tasks/plan' && body?.ids?.every(id => qa.taskIds.includes(id))
      const ownTaskCommand =
        ownId && ['/nocode/tasks/claim', '/nocode/tasks/transition', '/nocode/tasks/comment'].includes(path)
      const ownFeedback =
        ownId &&
        path === '/nocode/tasks/entries/save' &&
        body?.record?.applicationId === qa.applicationId &&
        body.record.objectId === qa.business.objectId
      if (saveDraft || publishDraft || ownPlan || ownTaskCommand || ownFeedback) {
        const write = { path, id: body.id || body.taskId || null, action: body.action, actorId: String(info.user.id) }
        writes.push(write)
        const response = await route.fetch(),
          result = await response.json()
        Object.assign(write, { http: response.status(), code: result.code, message: result.msg })
        if (result.code === 0) {
          if (saveDraft && !qa.draftIds.includes(result.data.id)) qa.draftIds.push(result.data.id)
          if (publishDraft) await qa.remember(result.data)
          if (ownFeedback && result.data?.handling?.result?.record)
            await qa.rememberRecord(qa.business, result.data.handling.result.record)
          await qa.persist()
        }
        return route.fulfill({ response })
      }
      blockedWrites.push({ path, method: request.method() })
      return route.abort('blockedbyclient')
    })
    return tab
  }
  async function waitTask(tab, title) {
    await expect(tab.locator('.task-hierarchy-drawer .ant-drawer-title')).toHaveText(title)
    await expect(tab.locator('.task-hierarchy-drawer .ant-spin-spinning')).toHaveCount(0)
  }
  async function failOnce(tab, path, message) {
    let first = true
    const pattern = `**/api${path}*`
    const handler = async route => {
      if (!first) return route.fallback()
      first = false
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, msg: message, data: null })
      })
    }
    await tab.route(pattern, handler)
    return () => tab.unroute(pattern, handler)
  }
  try {
    const fixtures = await check(
      supplemental ? '精确复用本批应用员工，只新增1项补验任务' : '最小独立夹具：一个应用对象、一个员工、4项初始任务',
      async () => {
        if (supplemental) await qa.prepareSupplement()
        else await qa.prepare()
        return {
          applicationId: qa.applicationId,
          objectId: qa.business.objectId,
          actorId: qa.employeeA,
          tasks: [...qa.taskIds]
        }
      }
    )
    if (fixtures.status !== 'passed') return
    browser = await chromium.launch({ channel: 'chrome', headless: true })
    page = await browserPage(qa.tokens.admin)
    employeePage = await browserPage(qa.tokens[qa.employeeA])
    if (supplemental)
      await check('编排底部真实可见，固定草稿与入池按钮可达', async () => {
        await page.goto(`${origin}/nocode-app/task-center/manage`)
        await page.getByRole('button', { name: '新建任务', exact: true }).click()
        await page.locator('.task-node-editor').scrollIntoViewIfNeeded()
        await expect(page.locator('.task-node-editor')).toBeInViewport()
        await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeInViewport()
        await expect(page.getByRole('button', { name: '加入任务池', exact: true })).toBeInViewport()
        await snap('new-task-real-tree-bottom')
      })
    if (!supplemental)
      await check(
        '操作预检为只读：完成阻断、取消影响、前置门控',
        async () => {
          const before = (await qa.taskApi('detail', { id: qa.group.task.id })).task
          const readiness = await qa.taskApi('readiness', { id: before.id })
          assert.equal(readiness.canComplete, false)
          assert.ok(readiness.checks.some(item => item.code === 'CHILDREN' && !item.passed))
          assert.equal((await qa.taskApi('detail', { id: before.id })).task.revision, before.revision)
          const first = await qa.taskApi('readiness', { id: qa.first.id })
          assert.ok(first.cancellationImpacts.some(item => item.taskId === qa.next.id))
          const denial = await qa.denied(
            '/nocode/tasks/transition',
            {
              id: qa.next.id,
              expectedRevision: qa.next.revision,
              action: 'START',
              note: qa.title('前置尚未完成拒绝'),
              requestKey: randomUUID()
            },
            qa.tokens[qa.employeeA]
          )
          return { readiness, first, denial }
        },
        [fixtures]
      )
    await check('我的任务日期导航、空筛选与加载失败重试', async () => {
      await page.goto(`${origin}/nocode-app/task-center`)
      for (const [name, forward, back] of [
        ['本周计划', '后一周', '前一周'],
        ['本月计划', '后一月', '前一月']
      ]) {
        await page.getByRole('tab', { name, exact: true }).click()
        await expect(page.getByRole('button', { name: forward, exact: true })).toBeVisible()
        await page.getByRole('button', { name: forward, exact: true }).click()
        await page.getByRole('button', { name: back, exact: true }).click()
      }
      await page.getByRole('tab', { name: '我的未完成', exact: true }).click()
      await page.getByPlaceholder('搜索任务名称').fill(qa.title('不存在'))
      await page.getByRole('button', { name: /查\s*询/, exact: true }).click()
      await expect(page.getByText('没有符合筛选条件的任务', { exact: true })).toBeVisible()
      await page.getByRole('button', { name: '清除筛选', exact: true }).click()
      await page.waitForLoadState('networkidle')
      const remove = await failOnce(page, '/nocode/tasks/page', 'UX验收：列表读取暂时失败')
      await page.getByRole('button', { name: /刷\s*新/, exact: true }).click()
      await expect(page.getByText('UX验收：列表读取暂时失败', { exact: true }).first()).toBeVisible()
      await snap('list-failure-retry')
      await page
        .getByRole('button', { name: /重试|重新加载/ })
        .last()
        .click()
      await expect(page.locator('.task-list .ant-alert-error')).toHaveCount(0)
      await remove()
    })
    if (!supplemental) {
      const draft = await check('新建总任务同步、私有草稿恢复、加入任务池并默认全员领取', async () => {
        await page.goto(`${origin}/nocode-app/task-center/manage`)
        await page.getByRole('button', { name: '新建任务', exact: true }).click()
        await page.getByPlaceholder('填写可执行的任务名称').fill(qa.title('草稿恢复与领取'))
        await expect(page.locator('.task-launch')).toContainText('所有人可领取')
        await expect(page.locator('.task-node-editor')).toContainText(qa.title('草稿恢复与领取'))
        await page.getByRole('button', { name: '保存草稿', exact: true }).click()
        await expect(page.locator('.task-launch')).toHaveCount(0)
        assert.equal(qa.draftIds.length, 1)
        await page.reload()
        await page.getByRole('button', { name: '我的草稿', exact: true }).click()
        await page.getByRole('button', { name: '继续编辑', exact: true }).click()
        await expect(page.getByPlaceholder('填写可执行的任务名称')).toHaveValue(qa.title('草稿恢复与领取'))
        await page.locator('.task-node-editor').scrollIntoViewIfNeeded()
        await snap('draft-restored-root')
        await page.getByRole('button', { name: '加入任务池', exact: true }).click()
        await waitTask(page, qa.title('草稿恢复与领取'))
        const tasks = await qa.taskApi('page', {
          scope: 'MANAGE',
          tab: 'ALL',
          search: qa.title('草稿恢复与领取'),
          pageNo: 1,
          pageSize: 10
        })
        assert.equal(tasks.total, 1)
        qa.openTask = tasks.list[0]
        assert.equal(qa.openTask.assignmentMode, 'OPEN')
        assert.equal(qa.openTask.status, 'PENDING')
        assert.equal(qa.taskIds.length, 5)
        return { id: qa.openTask.id, draftId: qa.draftIds[0] }
      })
      await check('新建与模板人员加载失败可重试，人员使用弹窗，窄布局可见', async () => {
        await page.goto(`${origin}/nocode-app/task-center/manage`)
        await page.waitForLoadState('networkidle')
        const remove = await failOnce(page, '/nocode/tasks/members', 'UX验收：人员列表暂时失败')
        await page.getByRole('button', { name: '新建任务', exact: true }).click()
        await expect(page.getByRole('button', { name: '重试人员列表', exact: true })).toBeVisible()
        await page.getByRole('button', { name: '重试人员列表', exact: true }).click()
        await remove()
        await page.getByRole('button', { name: '限定可领取人员（可选）', exact: true }).click()
        await expect(page.getByText('选择可领取人员', { exact: true }).last()).toBeVisible()
        await snap('person-selector')
        await page.goto(`${origin}/nocode-app/task-center/templates`)
        await page.getByRole('button', { name: '新建模板', exact: true }).click()
        await expect(page.locator('.ant-drawer-title')).toHaveText('新建任务模板')
        await page.setViewportSize({ width: 1100, height: 850 })
        await snap('new-template-1100')
        await page.setViewportSize({ width: 1512, height: 1050 })
      })
      await check(
        '员工领取不自动开始，可领取列表与我的未完成串联',
        async () => {
          await employeePage.goto(`${origin}/nocode-app/task-center`)
          await employeePage.getByRole('tab', { name: '可领取任务', exact: true }).click()
          await employeePage.getByPlaceholder('搜索任务名称').fill(qa.openTask.title)
          await employeePage.getByRole('button', { name: /查\s*询/, exact: true }).click()
          await employeePage.getByRole('button', { name: '领取', exact: true }).click()
          await expect
            .poll(async () => (await qa.taskApi('detail', { id: qa.openTask.id })).task.assigneeId)
            .toBe(qa.employeeA)
          const current = (await qa.taskApi('detail', { id: qa.openTask.id })).task
          assert.equal(current.status, 'PENDING')
          await employeePage.getByRole('tab', { name: '我的未完成', exact: true }).click()
          await expect(employeePage.getByRole('button', { name: qa.openTask.title, exact: true })).toBeVisible()
          await snap('claimed-not-started', employeePage)
        },
        [draft]
      )
    }
    await check('详情加载与提醒成员解耦，失败可重试且保留主信息', async () => {
      const remove = await failOnce(page, `/nocode/tasks/members?taskId=${qa.ordinary.id}`, 'UX验收：提醒人员暂时失败')
      await page.goto(detailUrl(qa.ordinary.id))
      await waitTask(page, qa.ordinary.title)
      await page.getByRole('tab', { name: /评论/ }).click()
      await expect(page.getByRole('button', { name: /重试.*成员|重试.*人员/ })).toBeVisible()
      await snap('detail-members-failure')
      await page.getByRole('button', { name: /重试.*成员|重试.*人员/ }).click()
      await remove()
      const removeDetail = await failOnce(page, '/nocode/tasks/detail', 'UX验收：任务详情暂时失败')
      await page.goto(detailUrl(qa.ordinary.id))
      await expect(page.getByRole('button', { name: /重试|重新加载/ }).last()).toBeVisible()
      await page
        .getByRole('button', { name: /重试|重新加载/ })
        .last()
        .click()
      await waitTask(page, qa.ordinary.title)
      await removeDetail()
    })
    const started = await check(
      '员工显式开始、完成条件提示和个人计划',
      async () => {
        await employeePage.goto(detailUrl(qa.ordinary.id))
        await waitTask(employeePage, qa.ordinary.title)
        assert.equal(
          await employeePage.evaluate(() => String(JSON.parse(localStorage.getItem('userInfo')).id)),
          qa.employeeA
        )
        assert.equal(new URL(employeePage.url()).searchParams.get('taskId'), qa.ordinary.id)
        const drawer = employeePage.locator('.task-hierarchy-drawer')
        await drawer.getByRole('button', { name: '开始执行', exact: true }).click()
        const transitioned = employeePage.waitForResponse(response =>
          response.url().endsWith('/api/nocode/tasks/transition')
        )
        await employeePage.locator('.ant-modal-content').getByRole('button', { name: '开始执行', exact: true }).click()
        const response = await transitioned
        assert.equal(response.request().postDataJSON().id, qa.ordinary.id)
        const received = await response.json()
        assert.equal(received.code, 0, `START响应：${received.code} ${received.msg}`)
        await expect.poll(async () => (await qa.taskApi('detail', { id: qa.ordinary.id })).task.status).toBe('RUNNING')
        await drawer.getByRole('button', { name: '安排计划', exact: true }).click()
        await expect(employeePage.locator('.ant-modal-content')).toContainText(qa.ordinary.title)
        await snap('personal-plan-context', employeePage)
        await employeePage.locator('.ant-modal-content').getByRole('button', { name: '保存计划', exact: true }).click()
        await expect(employeePage.locator('.ant-modal-content')).toHaveCount(0)
        await drawer.getByRole('button', { name: '完成任务', exact: true }).click()
        await expect(employeePage.locator('.ant-modal-content')).toContainText('施工日志')
        await snap('completion-requires-feedback', employeePage)
        const readiness = await qa.taskApi('readiness', { id: qa.ordinary.id }, qa.tokens[qa.employeeA])
        assert.ok(readiness.checks.some(item => item.code === 'FEEDBACK' && !item.passed))
        await employeePage.locator('.ant-modal-content textarea').fill('尚未提交的补验备注')
        await employeePage.locator('.ant-modal-content').getByRole('button', { name: '去处理', exact: true }).click()
        await employeePage.getByRole('button', { name: '继续编辑', exact: true }).click()
        await expect(employeePage.locator('.ant-modal-content textarea')).toHaveValue('尚未提交的补验备注')
        await employeePage.locator('.ant-modal-content').getByRole('button', { name: '去处理', exact: true }).click()
        await employeePage.getByRole('button', { name: '放弃修改', exact: true }).click()
        await expect(employeePage.locator('.task-entry-workspace')).toContainText('施工日志')
        await snap('missing-feedback-navigation', employeePage)
      },
      [],
      employeePage
    )
    const feedback = await check(
      '业务反馈加载失败重试、登记、完成条件转为满足',
      async () => {
        await employeePage.goto(detailUrl(qa.ordinary.id))
        const remove = await failOnce(employeePage, '/nocode/tasks/entries/list', 'UX验收：反馈目录暂时失败')
        await employeePage.getByRole('tab', { name: '业务数据与反馈', exact: true }).click()
        const workspace = employeePage.locator('.task-entry-workspace')
        await expect(workspace.getByRole('button', { name: '重新加载数据', exact: true })).toBeVisible()
        await snap('feedback-retry', employeePage)
        await workspace.getByRole('button', { name: '重新加载数据', exact: true }).click()
        await remove()
        await workspace.getByRole('tab', { name: '过程反馈', exact: true }).click()
        await workspace.getByRole('button', { name: '新增反馈', exact: true }).click()
        const editor = employeePage
          .locator('.ant-drawer-content:visible')
          .filter({ has: employeePage.getByRole('button', { name: '保存记录', exact: true }) })
        await editor
          .locator('.ant-form-item')
          .filter({ has: employeePage.locator('label').filter({ hasText: /^登记内容$/ }) })
          .locator('input')
          .fill(qa.title('完成现场登记'))
        await editor.getByRole('button', { name: '保存记录', exact: true }).click()
        await expect(editor).toHaveCount(0)
        await expect(workspace).toContainText(qa.title('完成现场登记'))
        await snap('feedback-recorded', employeePage)
        const readiness = await qa.taskApi('readiness', { id: qa.ordinary.id }, qa.tokens[qa.employeeA])
        assert.equal(readiness.canComplete, true)
      },
      [started],
      employeePage
    )
    await check(
      supplemental ? '非执行人只读原因与应用内同一任务' : '非执行人只读原因、应用内同一任务与取消影响预览',
      async () => {
        await page.goto(detailUrl(qa.ordinary.id))
        await page.getByRole('tab', { name: '业务数据与反馈', exact: true }).click()
        await expect(page.locator('.task-entry-workspace')).toContainText(/只读|仅查看|执行人/)
        await snap('manager-readonly-data')
        await page.goto(
          `${origin}/nocode-app/runtime?id=${qa.applicationId}&menu=record_menu&recordId=${qa.projectRecord.id}`
        )
        await expect(page.getByRole('button', { name: qa.ordinary.title, exact: true })).toBeVisible()
        await snap('embedded-same-task')
        if (!supplemental) {
          await page.goto(detailUrl(qa.first.id))
          await page.getByRole('button', { name: '取消任务', exact: true }).click()
          await expect(page.locator('.ant-modal-content')).toContainText(qa.next.title)
          await snap('cancel-dependency-impact')
          await page.locator('.ant-modal-content').getByRole('button', { name: '暂不操作', exact: true }).click()
        }
      }
    )
    await check(
      '满足条件后真实确认完成，实际结束自动记录',
      async () => {
        await employeePage.goto(detailUrl(qa.ordinary.id))
        await waitTask(employeePage, qa.ordinary.title)
        await employeePage
          .locator('.task-hierarchy-drawer')
          .getByRole('button', { name: '完成任务', exact: true })
          .click()
        await expect(
          employeePage.locator('.ant-modal-content').getByRole('button', { name: '确认完成', exact: true })
        ).toBeEnabled()
        await snap('completion-ready', employeePage)
        await employeePage.locator('.ant-modal-content').getByRole('button', { name: '确认完成', exact: true }).click()
        await expect
          .poll(async () => (await qa.taskApi('detail', { id: qa.ordinary.id })).task.status)
          .toBe('COMPLETED')
        const completed = (await qa.taskApi('detail', { id: qa.ordinary.id })).task
        assert.ok(completed.actualStart && completed.actualEnd)
        await expect(employeePage.locator('.task-detail__actions')).toContainText('已完成')
        await snap('task-completed', employeePage)
        return { taskId: completed.id, actualStart: completed.actualStart, actualEnd: completed.actualEnd }
      },
      [feedback],
      employeePage
    )
    await check(
      supplemental
        ? '浏览器无脚本异常、意外接口失败或越界写入，补验仅1任务'
        : '浏览器无脚本异常、意外接口失败或越界写入，最终仅5任务',
      async () => {
        assert.deepEqual(browserErrors, [])
        assert.deepEqual(blockedWrites, [])
        assert.deepEqual(
          apiFailures.filter(item => !item.injected),
          []
        )
        assert.equal(qa.taskIds.length, supplemental ? 1 : 5)
        return { tasks: qa.taskIds.length, writes: writes.length, screenshots: qa.screenshots.length }
      }
    )
  } catch (error) {
    qa.errors.push(error.stack || error.message)
  } finally {
    await browser?.close()
    if (qa.tokens.admin) await qa.finish().catch(error => qa.errors.push(error.stack || error.message))
    await writeResult()
    console.log(
      JSON.stringify(
        {
          output: qa.output,
          checks: qa.results.map(item => ({ name: item.name, status: item.status })),
          cleanup: qa.cleanup,
          errors: qa.errors
        },
        null,
        2
      )
    )
    process.exitCode =
      qa.errors.length ||
      qa.results.some(item => item.status !== 'passed') ||
      qa.cleanup.some(item => item.status === 'failed')
        ? 1
        : 0
  }
}
