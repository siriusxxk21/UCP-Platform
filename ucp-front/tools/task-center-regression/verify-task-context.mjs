import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

/** 上级只读上下文与准确开始条件专项；业务变更只用精确登记的 HTTP 夹具，浏览器只读。 */
class ContextAcceptance extends TaskDataPolicyAcceptance {
  constructor() {
    super()
    this.prefix = `tc${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
    this.output = resolve(process.env.TASK_CONTEXT_OUTPUT || '.work/task-context', this.prefix)
    this.browserResult = { captures: [], errors: [], blockedWrites: [], failures: [] }
    this.tasks = {}
  }

  async persist() {
    await super.persist()
    await writeFile(
      resolve(this.output, 'result.json'),
      JSON.stringify(
        {
          prefix: this.prefix,
          results: this.results,
          tasks: this.tasks,
          cleanup: this.cleanup,
          errors: this.errors,
          browser: this.browserResult
        },
        null,
        2
      )
    )
  }

  assigned(title, id, extra = {}) {
    return this.node(title, { assignmentMode: 'ASSIGNED', assigneeId: id, ...extra })
  }
  summary(title, extra = {}) {
    return this.node(title, { assignmentMode: 'UNASSIGNED', assigneeId: null, ...extra })
  }
  async graph(root, nodes) {
    const detail = await this.create(root, { nodes })
    return [root, ...nodes].map(node => detail.nodes.find(task => task.title === node.title))
  }
  query(search = this.prefix) {
    return { scope: 'MINE', tab: 'POOL', search, scheduleScope: 'PERSONAL', pageNo: 1, pageSize: 100 }
  }
  async mine() {
    return this.taskApi('personal-tree-page', this.query(), this.tokens[this.employeeA])
  }
  async child(task) {
    return (await this.taskApi('detail', { id: task.id }, this.tokens[this.employeeA])).task
  }
  async rejectStart(task) {
    const current = await this.child(task)
    const response = await this.taskRequest(
      'transition',
      { id: task.id, expectedRevision: current.revision, action: 'START', requestKey: randomUUID() },
      this.tokens[this.employeeA]
    )
    assert.notEqual(response.code, 0)
    assert.ok(response.http < 500)
    assert.equal((await this.child(task)).status, 'PENDING')
    return response.msg
  }
}

const expect = playwrightExpect.configure({ timeout: 20000 })
async function run() {
  const ac = new ContextAcceptance()
  await mkdir(ac.output, { recursive: true })
  let browser
  try {
    const fixtures = await ac.check('01 创建两个员工和16个独立任务节点', async () => {
      await ac.login()
      await ac.prepareUsers()
      const parent = ac.assigned('父级施工', ac.employeeB, { description: ac.title('私密父级描述') })
      const child = ac.assigned('本人墙面施工', ac.employeeA, { parentId: parent.id })
      const sibling = ac.assigned('私密兄弟预算', ac.employeeB, { description: ac.title('私密兄弟描述') })
      ac.tasks.main = await ac.graph(
        ac.assigned('项目总施工', ac.employeeB, { description: ac.title('私密总任务描述') }),
        [parent, child, sibling]
      )
      await ac.taskApi('comment', {
        taskId: ac.tasks.main[3].id,
        parentId: null,
        content: ac.title('私密兄弟评论'),
        mentionedUserIds: [],
        requestKey: randomUUID()
      })
      ac.tasks.fold = await ac.graph(ac.assigned('本人汇总', ac.employeeA), [ac.assigned('本人下级', ac.employeeA)])
      ac.tasks.summary = await ac.graph(ac.summary('无执行人父项'), [ac.assigned('无需等待父项领取', ac.employeeA)])
      const predecessor = ac.assigned('前置材料到场', ac.employeeB)
      const gate = ac.summary('有前置约束汇总', { predecessorIds: [predecessor.id] })
      ac.tasks.predecessor = await ac.graph(ac.summary('材料总任务'), [
        predecessor,
        gate,
        ac.assigned('前置受限子任务', ac.employeeA, { parentId: gate.id })
      ])
      const future = Date.now() + 5 * 86400000
      ac.tasks.time = await ac.graph(
        ac.summary('未来开工汇总', {
          schedule: { mode: 'FIXED', fixedStart: future, fixedEnd: future + 86400000, offsetDays: 0, durationDays: 1 }
        }),
        [ac.assigned('时间受限子任务', ac.employeeA)]
      )
      ac.tasks.claim = await ac.graph(ac.summary('领取独立总任务'), [
        ac.node('领取但不开始', { candidateUserIds: [ac.employeeA] })
      ])
      assert.equal(ac.taskIds.length, 16)
      return { taskIds: ac.taskIds, users: [ac.employeeA, ac.employeeB], applicationCount: 0, objectCount: 0 }
    })
    const context = await ac.check(
      '02 分派后提前可见，上级摘要白名单及私密内容隔离',
      async () => {
        const mine = await ac.mine()
        const child = mine.list.find(item => item.task.id === ac.tasks.main[2].id)?.task
        assert.ok(child, '总任务未开始时本人子任务已在我的待办')
        assert.equal(child.status, 'PENDING')
        assert.equal(child.canStart, false)
        assert.deepEqual(
          child.ancestorContext.map(item => item.id),
          ac.tasks.main.slice(0, 2).map(item => item.id)
        )
        for (const item of child.ancestorContext) {
          assert.deepEqual(
            Object.keys(item).sort(),
            ['id', 'parentId', 'title', 'assigneeName', 'status', 'detailVisible'].sort()
          )
          assert.equal(item.detailVisible, false)
          assert.equal(item.status, 'PENDING')
          assert.ok(item.assigneeName)
          const denied = await ac.taskRequest('detail', { id: item.id }, ac.tokens[ac.employeeA])
          assert.notEqual(denied.code, 0)
          assert.ok(denied.http < 500)
        }
        const detail = await ac.taskApi('detail', { id: child.id }, ac.tokens[ac.employeeA])
        assert.deepEqual(
          detail.nodes.map(item => item.id),
          [child.id]
        )
        const serialized = JSON.stringify({ mine, detail })
        for (const secret of ['私密父级描述', '私密总任务描述', '私密兄弟描述', '私密兄弟评论', '私密兄弟预算'])
          assert.ok(!serialized.includes(ac.title(secret)), `不得泄漏${secret}`)
        const siblingDenied = await ac.taskRequest('detail', { id: ac.tasks.main[3].id }, ac.tokens[ac.employeeA])
        assert.notEqual(siblingDenied.code, 0)
        assert.ok(child.blockedReason.includes(ac.tasks.main[0].title))
        assert.ok(child.blockedReason.includes(child.ancestorContext[0].assigneeName))
        await ac.rejectStart(child)
        return {
          ancestorContext: child.ancestorContext,
          blockedReason: child.blockedReason,
          nodes: detail.nodes.map(item => item.id)
        }
      },
      [fixtures]
    )
    await ac.check(
      '03 前置、父项时间和未分配汇总例外保持真实执行门控',
      async () => {
        let row = await ac.child(ac.tasks.predecessor[3])
        assert.equal(row.canStart, false)
        assert.ok(row.blockedReason.includes(ac.tasks.predecessor[1].title))
        assert.ok(row.blockedReason.includes(ac.tasks.predecessor[2].title))
        const predecessorReason = row.blockedReason
        await ac.rejectStart(row)
        await ac.command(ac.tasks.predecessor[1].id, 'START', ac.tokens[ac.employeeB])
        await ac.command(ac.tasks.predecessor[1].id, 'COMPLETE', ac.tokens[ac.employeeB])
        row = await ac.child(ac.tasks.predecessor[3])
        assert.equal(row.canStart, true)
        assert.equal(row.status, 'PENDING')
        const timed = await ac.child(ac.tasks.time[1])
        assert.equal(timed.canStart, false)
        assert.ok(timed.blockedReason.includes(ac.tasks.time[0].title))
        assert.match(timed.blockedReason, /\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
        await ac.rejectStart(timed)
        const free = await ac.child(ac.tasks.summary[1])
        assert.equal(free.canStart, true)
        await ac.command(free.id, 'START', ac.tokens[ac.employeeA])
        const freeParent = (await ac.taskApi('detail', { id: ac.tasks.summary[0].id })).task
        assert.equal(freeParent.status, 'PENDING')
        assert.equal(freeParent.assigneeId, null)
        const claimed = await ac.command(ac.tasks.claim[1].id, 'CLAIM', ac.tokens[ac.employeeA])
        assert.equal(claimed.task.status, 'PENDING')
        assert.equal(claimed.task.actualStart, null)
        return {
          predecessorReason,
          afterPredecessor: row.canStart,
          timeReason: timed.blockedReason,
          unassignedParent: freeParent.status,
          claimedStatus: claimed.task.status
        }
      },
      [fixtures]
    )
    if (context.status === 'passed') {
      browser = await chromium.launch({ channel: 'chrome', headless: true })
      await verifyBrowser(ac, browser)
    }
  } catch (error) {
    ac.errors.push(error.stack || error.message)
  } finally {
    if (browser) await browser.close()
    if (ac.tokens.admin) await ac.finish()
    await ac.persist()
    console.log(
      JSON.stringify({
        output: ac.output,
        results: ac.results.map(({ name, status }) => ({ name, status })),
        cleanupFailed: ac.cleanup.filter(item => item.status === 'failed').length,
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

async function verifyBrowser(ac, browser) {
  const token = ac.tokens[ac.employeeA]
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
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
      /* 路由取消没有 JSON 结果。 */
    }
  })
  const reads = new Set([
    'page',
    'personal-tree-page',
    'personal-tree-children',
    'detail',
    'readiness',
    'plan-context',
    'entries/list'
  ])
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
  const list = page.locator('.task-list').first()
  const rows = list.locator('.ant-table-tbody > tr.ant-table-row')
  const rowIds = () => rows.evaluateAll(items => items.map(item => item.getAttribute('data-row-key')))
  const settle = async () => {
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    await page.waitForLoadState('networkidle')
  }
  const capture = async name => {
    const screenshot = resolve(ac.output, `${name}.png`)
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
    await writeFile(resolve(ac.output, `${name}.txt`), await page.locator('body').innerText())
    ac.browserResult.captures.push({ name, screenshot })
    await ac.persist()
  }
  const check = (name, work) =>
    ac.check(name, async () => {
      try {
        return await work()
      } catch (error) {
        await capture(`failure-${ac.results.length + 1}`)
        const drawer = page.locator('.task-hierarchy-drawer:visible')
        if (await drawer.count()) await drawer.locator('.ant-drawer-close').click()
        throw error
      }
    })
  const search = async text => {
    await page.getByPlaceholder('搜索任务名称').fill(text)
    await page.getByRole('button', { name: /查\s*询$/ }).click()
    await settle()
  }
  await page.goto('http://127.0.0.1:5173/nocode-app/task-center')
  await settle()
  await check('04 宽窄列表和详情给出上级只读上下文，无详情跳转和私密兄弟', async () => {
    await search(ac.tasks.main[2].title)
    await expect.poll(rowIds).toEqual([ac.tasks.main[2].id])
    const row = rows.first()
    await expect(row).toContainText(ac.tasks.main[1].title)
    await expect(row).toContainText(ac.tasks.main[0].title)
    await expect(row.getByRole('button', { name: '开始', exact: true })).toHaveCount(0)
    await capture('context-list-wide')
    await row.getByRole('button', { name: ac.tasks.main[2].title, exact: true }).click()
    const drawer = page.locator('.task-hierarchy-drawer:visible')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('button', { name: '开始执行', exact: true })).toBeDisabled()
    const position = drawer.getByRole('navigation', { name: '任务位置', exact: true })
    const tree = drawer.getByRole('tree', { name: '整体任务树', exact: true })
    await expect(tree.getByRole('treeitem')).toHaveCount(3)
    await expect(tree.locator('[aria-current="true"]')).toContainText(ac.tasks.main[2].title)
    for (const [index, ancestor] of ac.tasks.main.slice(0, 2).entries()) {
      await expect(position).toContainText(ancestor.title)
      const reference = tree.locator(`[role="treeitem"][aria-level="${index + 1}"]`)
      await expect(reference).toContainText('层级参考')
      await expect(reference).toContainText(ancestor.assigneeName)
      const controls = await reference.getByRole('button').allTextContents()
      assert.ok(
        controls.every(text => !text.trim()),
        '参考节点仅保留无文字的折叠箭头，不提供任务操作'
      )
      await expect(drawer.getByRole('button', { name: ancestor.title, exact: true })).toHaveCount(0)
      await expect(drawer.getByRole('link', { name: ancestor.title, exact: true })).toHaveCount(0)
    }
    await expect(drawer).not.toContainText(ac.tasks.main[3].title)
    await capture('context-detail-wide')
    await page.setViewportSize({ width: 1100, height: 760 })
    await capture('context-detail-narrow')
    await drawer.locator('.ant-drawer-close').click()
    await expect(drawer).toHaveCount(0)
    await capture('context-list-narrow')
    await page.setViewportSize({ width: 1512, height: 1050 })
    return {
      ownTask: ac.tasks.main[2].id,
      ancestors: ac.tasks.main.slice(0, 2).map(item => item.id),
      siblingsHidden: true
    }
  })
  await check('05 逐层开始后准确更新等待对象，子任务不会自动执行', async () => {
    await ac.command(ac.tasks.main[0].id, 'START', ac.tokens[ac.employeeB])
    let child = await ac.child(ac.tasks.main[2])
    assert.equal(child.canStart, false)
    assert.equal(child.status, 'PENDING')
    assert.ok(child.blockedReason.includes(ac.tasks.main[1].title))
    assert.ok(!child.blockedReason.includes(ac.tasks.main[0].title))
    await ac.rejectStart(child)
    await list.getByRole('button', { name: /刷\s*新$/ }).click()
    await expect(rows.first()).toContainText(child.blockedReason)
    await capture('waiting-for-direct-parent')
    await ac.command(ac.tasks.main[1].id, 'START', ac.tokens[ac.employeeB])
    child = await ac.child(ac.tasks.main[2])
    assert.equal(child.canStart, true)
    assert.equal(child.status, 'PENDING')
    assert.equal(child.actualStart, null)
    await list.getByRole('button', { name: /刷\s*新$/ }).click()
    await expect(rows.first().getByRole('button', { name: '开始', exact: true })).toBeVisible()
    await capture('can-start-but-not-auto-started')
    await ac.command(child.id, 'START', ac.tokens[ac.employeeA])
    assert.equal((await ac.child(child)).status, 'RUNNING')
    return { root: ac.tasks.main[0].id, parent: ac.tasks.main[1].id, child: child.id, explicitStart: true }
  })
  await check('06 上级上下文不破坏本人树展开收起', async () => {
    await search(ac.title('本人'))
    const root = ac.tasks.fold[0],
      child = ac.tasks.fold[1]
    // 排序属于服务端当前口径，这里只核对任务集合，不假设创建顺序。
    await expect.poll(async () => (await rowIds()).sort()).toEqual([root.id, ac.tasks.main[2].id].sort())
    const before = await rowIds()
    const total = await list.locator('.ant-pagination-total-text').innerText()
    await list.getByRole('button', { name: `展开子任务：${root.title}`, exact: true }).click()
    await expect.poll(async () => (await rowIds()).sort()).toEqual([...before, child.id].sort())
    await list.getByRole('button', { name: child.title, exact: true }).click()
    const drawer = page.locator('.task-hierarchy-drawer:visible')
    const permittedParent = drawer
      .getByRole('navigation', { name: '任务位置', exact: true })
      .getByRole('button', { name: root.title, exact: true })
    await expect(permittedParent).toBeVisible()
    await permittedParent.click()
    await expect(drawer.locator('[role="treeitem"][aria-current="true"]')).toContainText(root.title)
    await capture('authorized-parent-remains-navigable')
    await drawer.locator('.ant-drawer-close').click()
    await expect(drawer).toHaveCount(0)
    await list.getByRole('button', { name: `收起子任务：${root.title}`, exact: true }).click()
    await expect.poll(rowIds).toEqual(before)
    await expect(list.locator('.ant-pagination-total-text')).toHaveText(total)
    await capture('context-fold-still-hides-children')
    return { before, total }
  })
  await ac.check('07 浏览器请求和控制台无异常', async () => {
    assert.deepEqual(ac.browserResult.errors, [])
    assert.deepEqual(ac.browserResult.blockedWrites, [])
    assert.deepEqual(ac.browserResult.failures, [])
    return ac.browserResult
  })
}

/** 中断清理只读入保存的精确清单；不重新造夹具或尝试恢复员工口令。 */
async function cleanupInterrupted() {
  const output = resolve(process.env.TASK_CONTEXT_CLEANUP)
  const manifest = JSON.parse(await readFile(resolve(output, 'manifest.json'), 'utf8'))
  const saved = JSON.parse(await readFile(resolve(output, 'result.json'), 'utf8'))
  assert.match(manifest.prefix, /^tc[a-z0-9]+$/)
  assert.equal(saved.prefix, manifest.prefix)
  const ac = new ContextAcceptance()
  ac.prefix = manifest.prefix
  ac.output = output
  ac.taskIds = manifest.taskIds
  ac.owned = Object.fromEntries(
    ['objects', 'users', 'roles', 'departments', 'applications'].map(key => [key, manifest[key] || []])
  )
  ac.results = saved.results
  ac.tasks = saved.tasks
  ac.browserResult = saved.browser
  await ac.login()
  await ac.finish()
  await ac.persist()
  console.log(JSON.stringify({ output, cleanup: ac.cleanup, errors: ac.errors }))
}

if (process.env.TASK_CONTEXT_CLEANUP) await cleanupInterrupted()
else if (process.env.TASK_CONTEXT_WRITE_RUN === '1') await run()
else
  console.log(
    JSON.stringify({
      mode: 'prepare-only',
      writes: 0,
      fixtures: '2 临时员工，16独立任务节点；不建应用和业务对象',
      scenarios: '上级摘要白名单/提前可见/逐层开始/私密兄弟/前置及时间/无执行人例外/领取开始分离/上下文只读/本人折叠'
    })
  )
