import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

/** 统一工作安排专项。缺少显式开关时连认证也不执行，避免准备阶段误造夹具。 */
const matrix = [
  '真实计划上下文：版本、现有安排、主管约束与历史',
  '直接分配自动进入个人待办；个人节点与团队权限范围独立',
  '日安排及日期范围投影到日、周、月且不复制任务',
  '周月粗排不自动摊入每天，待细化与之前未完成可查询',
  '员工只能在主管期间内细化，不可越界或撤销主管安排',
  '改期移动原安排并保留历史，过期版本与混合越权批次拒绝',
  '撤销精确计划，不删除任务、不改变任务预计或实际时间',
  '领取不开始；领取成功后安排失败重试只调用安排接口',
  '完成离开待办、保留原计划历史和真实完成状态',
  '浏览器真实状态弹窗、团队筛选、历史与日期视图'
]
if (process.env.TASK_PLAN_WRITE_RUN !== '1') {
  console.log(
    JSON.stringify(
      { mode: 'prepare-only', writes: 0, fixtures: '2临时员工、1权限角色、4独立任务；不建应用或数据对象', matrix },
      null,
      2
    )
  )
}

class PlanAcceptance extends TaskDataPolicyAcceptance {
  constructor() {
    super()
    this.prefix = `pl${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
    this.output = resolve(process.env.TASK_PLAN_OUTPUT || '.work/task-plan', this.prefix)
    this.snapshots = []
    this.browser = { captures: [], errors: [], blockedWrites: [], injectedFailures: [] }
  }

  async persist() {
    await super.persist()
    await writeFile(
      resolve(this.output, 'manifest.json'),
      JSON.stringify(
        {
          prefix: this.prefix,
          source: 'task-unified-plan-20261003',
          ...this.owned,
          taskIds: this.taskIds,
          retention: '仅取消本清单未结束任务并停用本清单员工；保留历史审计，不操作用户任务'
        },
        null,
        2
      )
    )
    await writeFile(
      resolve(this.output, 'result.json'),
      JSON.stringify(
        {
          prefix: this.prefix,
          output: this.output,
          results: this.results,
          cleanup: this.cleanup,
          errors: this.errors,
          snapshots: this.snapshots,
          browser: this.browser
        },
        null,
        2
      )
    )
  }

  async context(id, token, target = 'SELF') {
    assert.ok(this.taskIds.includes(id))
    const result = await this.taskApi('plan-context', { ids: [id], target }, token)
    assert.equal(result.items.length, 1)
    assert.equal(result.items[0].taskId, id)
    return result.items[0]
  }

  async user(suffix, departmentId) {
    // 复用统一账号夹具，并让浏览器中的显示名也含本轮唯一前缀，避免选择同名旧测试员工。
    return super.user(`${this.prefix.slice(2)}${suffix}`, departmentId)
  }

  async schedule(id, token, input, target = 'SELF') {
    const item = await this.context(id, token, target)
    const command = { ids: [id], target, action: 'ARRANGE', expectedVersions: { [id]: item.version }, ...input }
    const result = await this.taskApi('schedule', command, token)
    assert.deepEqual([...result.changed, ...result.unchanged], [id])
    return { result, command, item: await this.context(id, token, target) }
  }

  async page(token, overrides = {}) {
    return this.taskApi(
      'page',
      {
        scope: 'MINE',
        tab: 'POOL',
        date: '2030-10-10',
        search: this.prefix,
        scheduleScope: 'PERSONAL',
        pageNo: 1,
        pageSize: 100,
        ...overrides
      },
      token
    )
  }

  async rejectSchedule(command, token) {
    const result = await this.taskRequest('schedule', command, token)
    assert.notEqual(result.code, 0, '受保护的计划写入必须拒绝，不能把成功当成业务错误')
    assert.ok(result.http < 500, '业务拒绝不能是500')
    return { code: result.code, message: result.msg }
  }
}

async function run() {
  const ac = new PlanAcceptance()
  await mkdir(ac.output, { recursive: true })
  let employeeToken, managerToken, managed, outside, open, history
  try {
    const fixtures = await ac.check('01 最小独立夹具和真实身份', async () => {
      await ac.login()
      await ac.prepareUsers()
      managerToken = ac.tokens[ac.employeeA]
      employeeToken = ac.tokens[ac.employeeB]
      managed = await ac.create(
        ac.node('主管安排', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB }),
        {},
        managerToken
      )
      outside = await ac.create(ac.node('范围外员工任务', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB }))
      open = await ac.create(ac.node('领取与排期失败恢复'))
      history = await ac.create(
        ac.node('个人安排历史', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB }),
        {},
        employeeToken
      )
      assert.equal(ac.taskIds.length, 4)
      return { tasks: ac.taskIds, employees: [ac.employeeA, ac.employeeB], applications: 0, objects: 0 }
    })
    const initial = await ac.check(
      '02 未排期真实状态与直接分配进入个人待办',
      async () => {
        const item = await ac.context(managed.task.id, employeeToken)
        assert.deepEqual(item.plans, [])
        assert.deepEqual(item.constraints, [])
        assert.equal(item.canCancel, false)
        assert.equal(item.canArrange, true)
        const mine = await ac.page(employeeToken)
        assert.ok(mine.list.some(task => task.id === managed.task.id))
        assert.ok(!mine.list.some(task => task.id === open.task.id))
        assert.equal((await ac.taskApi('detail', { id: managed.task.id }, employeeToken)).task.status, 'PENDING')
        return item
      },
      [fixtures]
    )
    await ac.check(
      '03 TEAM仅既有管理范围，指定员工不能扩权',
      async () => {
        const team = await ac.page(managerToken, {
          scope: 'MANAGE',
          scheduleScope: 'TEAM',
          assigneeId: ac.employeeB,
          rootsOnly: false
        })
        assert.ok(team.list.some(task => task.id === managed.task.id))
        assert.ok(!team.list.some(task => task.id === outside.task.id))
        assert.ok(!team.list.some(task => task.id === history.task.id))
        const personal = await ac.taskRequest(
          'page',
          {
            scope: 'MINE',
            tab: 'POOL',
            scheduleScope: 'PERSONAL',
            assigneeId: ac.employeeB,
            date: '2030-10-10',
            pageNo: 1,
            pageSize: 100
          },
          managerToken
        )
        assert.notEqual(personal.code, 0, 'PERSONAL不能通过assigneeId切到别人的待办')
        assert.ok(personal.http < 500)
        const denial = await ac.taskRequest(
          'plan-context',
          { ids: [outside.task.id], target: 'ASSIGNEE' },
          managerToken
        )
        assert.notEqual(denial.code, 0)
        assert.ok(denial.http < 500)
        return { visibleTeam: team.list.map(task => task.id), denial: denial.code }
      },
      [fixtures]
    )
    const constraint = await ac.check(
      '04 主管周安排形成真实约束且不自动开始',
      async () => {
        const response = await ac.schedule(
          managed.task.id,
          managerToken,
          { period: 'WEEK', date: '2030-10-09' },
          'ASSIGNEE'
        )
        const item = await ac.context(managed.task.id, employeeToken)
        assert.equal(item.constraints.length, 1)
        assert.equal(item.constraints[0].source, 'MANAGER')
        assert.equal(item.constraints[0].date, '2030-10-07')
        assert.equal(item.constraints[0].endDate, '2030-10-13')
        assert.equal(String(item.constraints[0].arrangedById), ac.employeeA)
        const current = (await ac.taskApi('detail', { id: managed.task.id }, employeeToken)).task
        assert.equal(current.status, 'PENDING')
        assert.equal(current.actualStart, null)
        assert.equal(current.expectedStart, null)
        assert.equal(current.expectedEnd, null)
        return { response: response.result, item }
      },
      [initial]
    )
    await ac.check(
      '05 员工不能撤销主管安排或越界，拒绝无副作用',
      async () => {
        const item = await ac.context(managed.task.id, employeeToken)
        const base = { ids: [managed.task.id], target: 'SELF', expectedVersions: { [managed.task.id]: item.version } }
        const cancel = await ac.rejectSchedule(
          { ...base, action: 'CANCEL', planIds: [item.constraints[0].id] },
          employeeToken
        )
        const outsideRange = await ac.rejectSchedule(
          { ...base, action: 'ARRANGE', period: 'DAY', date: '2030-10-14' },
          employeeToken
        )
        const forgedManager = await ac.rejectSchedule(
          { ...base, target: 'ASSIGNEE', action: 'CANCEL', planIds: [item.constraints[0].id] },
          employeeToken
        )
        const legacy = await ac.taskRequest(
          'plan',
          { ids: [managed.task.id], target: 'SELF', period: 'WEEK', date: '2030-10-09', include: false },
          employeeToken
        )
        assert.notEqual(legacy.code, 0, '旧plan接口也不能撤销主管安排')
        assert.ok(legacy.http < 500)
        const after = await ac.context(managed.task.id, employeeToken)
        assert.equal(after.version, item.version)
        assert.deepEqual(after.constraints, item.constraints)
        return { cancel, outsideRange, forgedManager, legacyDenial: legacy.code }
      },
      [constraint]
    )
    const refinement = await ac.check(
      '06 主管周内细化到日，仅撤销个人细化',
      async () => {
        const response = await ac.schedule(managed.task.id, employeeToken, { period: 'DAY', date: '2030-10-10' })
        const own = response.item.plans.find(plan => plan.source === 'SELF')
        assert.ok(own?.id)
        assert.equal(response.item.constraints.length, 1)
        assert.equal(response.item.constraints[0].period, 'WEEK')
        const result = await ac.schedule(managed.task.id, employeeToken, { action: 'CANCEL', planIds: [own.id] })
        assert.ok(!result.item.plans.some(plan => plan.id === own.id))
        assert.equal(result.item.constraints.length, 1)
        assert.ok(result.item.history.some(plan => plan.id === own.id))
        return result.item
      },
      [constraint]
    )
    await ac.check(
      '07 主管指定日期后员工只读，显示来源不冒充个人安排',
      async () => {
        await ac.schedule(managed.task.id, managerToken, { period: 'DAY', date: '2030-10-10' }, 'ASSIGNEE')
        const item = await ac.context(managed.task.id, employeeToken)
        assert.equal(item.readOnly, true)
        assert.equal(item.canArrange, false)
        assert.equal(item.canCancel, false)
        assert.equal(item.constraints[0].source, 'MANAGER')
        return item
      },
      [refinement]
    )
    const range = await ac.check(
      '08 一份多日安排投影日周月，未复制任务或预计时间',
      async () => {
        const result = await ac.schedule(history.task.id, employeeToken, {
          period: 'DAY',
          date: '2030-09-30',
          endDate: '2030-10-02'
        })
        assert.equal(result.item.plans.length, 1)
        const projected = []
        for (const [tab, date] of [
          ['TODAY', '2030-10-01'],
          ['WEEK', '2030-09-30'],
          ['MONTH', '2030-10-01']
        ]) {
          const page = await ac.page(employeeToken, { tab, date })
          assert.equal(page.list.filter(task => task.id === history.task.id).length, 1)
          projected.push({ tab, date, matches: 1 })
        }
        const offDay = await ac.page(employeeToken, { tab: 'TODAY', date: '2030-10-03', planFilter: 'PLANNED' })
        assert.ok(!offDay.list.some(task => task.id === history.task.id))
        const row = (await ac.taskApi('detail', { id: history.task.id }, employeeToken)).task
        assert.equal(row.expectedStart, null)
        assert.equal(row.expectedEnd, null)
        assert.equal(row.actualStart, null)
        return projected
      },
      [fixtures]
    )
    const revised = await ac.check(
      '09 改期替换而非叠加；旧版本冲突不覆盖',
      async () => {
        const before = await ac.context(history.task.id, employeeToken)
        const result = await ac.schedule(history.task.id, employeeToken, { period: 'MONTH', date: '2030-10-01' })
        assert.equal(result.item.plans.length, 1)
        assert.equal(result.item.plans[0].period, 'MONTH')
        assert.ok(result.item.history.some(plan => plan.id === before.plans[0].id))
        const denial = await ac.rejectSchedule(
          {
            ids: [history.task.id],
            target: 'SELF',
            action: 'ARRANGE',
            period: 'DAY',
            date: '2030-11-01',
            expectedVersions: { [history.task.id]: before.version }
          },
          employeeToken
        )
        assert.deepEqual((await ac.context(history.task.id, employeeToken)).plans, result.item.plans)
        return { denial, item: result.item }
      },
      [range]
    )
    await ac.check(
      '10 周月粗排待细化，不自动复制为每天的已安排',
      async () => {
        const coarse = await ac.page(employeeToken, { tab: 'TODAY', date: '2030-10-10', planFilter: 'COARSE' })
        assert.ok(coarse.list.some(task => task.id === history.task.id))
        const daily = await ac.page(employeeToken, { tab: 'TODAY', date: '2030-10-10', planFilter: 'PLANNED' })
        assert.ok(!daily.list.some(task => task.id === history.task.id))
        const monthly = await ac.page(employeeToken, { tab: 'MONTH', date: '2030-10-10', planFilter: 'PLANNED' })
        assert.equal(monthly.list.filter(task => task.id === history.task.id).length, 1)
        return { coarse: history.task.id }
      },
      [revised]
    )
    const overdue = await ac.check(
      '11 之前未完成保留原期，不滚动为今日安排',
      async () => {
        await ac.schedule(history.task.id, employeeToken, { period: 'DAY', date: '2000-01-01' })
        const page = await ac.page(employeeToken, { tab: 'POOL', date: '2030-10-10', planFilter: 'CARRYOVER' })
        assert.ok(page.list.some(task => task.id === history.task.id))
        const item = await ac.context(history.task.id, employeeToken)
        assert.equal(item.plans[0].date, '2000-01-01')
        const planned = await ac.page(employeeToken, { tab: 'TODAY', date: '2030-10-10', planFilter: 'PLANNED' })
        assert.ok(!planned.list.some(task => task.id === history.task.id))
        return item
      },
      [revised]
    )
    await ac.check(
      '12 混合批次越权整体拒绝，合法任务也不被改动',
      async () => {
        const item = await ac.context(managed.task.id, managerToken, 'ASSIGNEE')
        const other = await ac.context(outside.task.id, ac.tokens.admin, 'ASSIGNEE')
        const denied = await ac.rejectSchedule(
          {
            ids: [managed.task.id, outside.task.id],
            target: 'ASSIGNEE',
            action: 'ARRANGE',
            period: 'DAY',
            date: '2031-01-01',
            expectedVersions: { [managed.task.id]: item.version, [outside.task.id]: other.version }
          },
          managerToken
        )
        assert.deepEqual((await ac.context(managed.task.id, managerToken, 'ASSIGNEE')).plans, item.plans)
        return denied
      },
      [fixtures]
    )
    await ac.check(
      '13 完成离开待办但历史计划仍可读取',
      async () => {
        await ac.command(history.task.id, 'START', employeeToken)
        await ac.command(history.task.id, 'COMPLETE', employeeToken)
        const row = (await ac.taskApi('detail', { id: history.task.id }, employeeToken)).task
        assert.equal(row.status, 'COMPLETED')
        assert.ok(row.actualStart)
        assert.ok(row.actualEnd)
        assert.ok(!(await ac.page(employeeToken)).list.some(task => task.id === history.task.id))
        const historical = await ac.page(employeeToken, { tab: 'ALL', status: 'COMPLETED' })
        assert.ok(historical.list.some(task => task.id === history.task.id))
        const oldDay = await ac.page(employeeToken, { tab: 'TODAY', date: '2000-01-01', planFilter: 'PLANNED' })
        assert.ok(oldDay.list.some(task => task.id === history.task.id))
        return { status: row.status, context: await ac.context(history.task.id, employeeToken) }
      },
      [overdue]
    )
    await verifyBrowser(ac, { employeeToken, managerToken, managed, outside, open, history }, fixtures)
  } catch (error) {
    ac.errors.push(error.stack || error.message)
  } finally {
    const adminToken = ac.tokens.admin
    await ac.finish()
    for (const user of ac.owned.users) {
      const current = await ac.api(`/system/user/get?id=${user.id}`, undefined, adminToken).catch(() => null)
      if (!current || current.username !== user.username || Number(current.status) !== 1)
        ac.errors.push(`临时员工停用复核失败：${user.id}`)
    }
    await ac.persist()
  }
  if (
    ac.results.some(item => item.status !== 'passed') ||
    ac.errors.length ||
    ac.cleanup.some(item => item.status === 'failed')
  )
    process.exitCode = 1
  console.log(
    JSON.stringify(
      {
        output: ac.output,
        passed: ac.results.filter(item => item.status === 'passed').length,
        failed: ac.results.filter(item => item.status === 'failed').length,
        blocked: ac.results.filter(item => item.status === 'blocked').length,
        cleanup: ac.cleanup
      },
      null,
      2
    )
  )
}

async function verifyBrowser(ac, tasks, fixtures) {
  const expect = playwrightExpect.configure({ timeout: 20000 })
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  let injectFailure = true
  let currentPage
  const claimRequests = [],
    scheduleRequests = []
  async function pageFor(token) {
    const info = await ac.api('/system/auth/get-permission-info', undefined, token)
    const page = await browser.newPage({ viewport: { width: 1512, height: 1000 } })
    currentPage = page
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
    page.on('pageerror', error => ac.browser.errors.push(error.message))
    await page.route('**/api/**', async route => {
      const request = route.request(),
        path = new URL(request.url()).pathname.replace(/^\/api/, '')
      if (['GET', 'HEAD', 'OPTIONS'].includes(request.method())) return route.continue()
      const reads = [
        '/nocode/tasks/page',
        '/nocode/tasks/detail',
        '/nocode/tasks/plan-context',
        '/nocode/tasks/readiness',
        '/nocode/tasks/entries/list'
      ]
      if (reads.includes(path)) return route.continue()
      const body = request.postDataJSON()
      if (path === '/nocode/tasks/claim' && body.id === tasks.open.task.id) {
        claimRequests.push({ id: body.id, requestKey: body.requestKey })
        return route.continue()
      }
      if (path === '/nocode/tasks/schedule' && body.ids?.length === 1 && body.ids[0] === tasks.open.task.id) {
        scheduleRequests.push(body)
        if (injectFailure) {
          injectFailure = false
          ac.browser.injectedFailures.push({
            path,
            taskId: body.ids[0],
            purpose: '仅浏览器模拟一次排期网络失败，不执行此请求'
          })
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ code: 500, msg: '验收注入：计划暂不可用，请重试', data: null })
          })
        }
        return route.continue()
      }
      ac.browser.blockedWrites.push({ path, method: request.method() })
      return route.abort('blockedbyclient')
    })
    return page
  }
  async function capture(page, name) {
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    const screenshot = resolve(ac.output, `${name}.png`)
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
    await writeFile(resolve(ac.output, `${name}.txt`), await page.locator('body').innerText())
    ac.browser.captures.push({ name, screenshot })
    await ac.persist()
  }
  async function checkUi(name, work, dependencies) {
    return ac.check(
      name,
      async () => {
        try {
          return await work()
        } catch (error) {
          // 失败状态也保留真实页面；不等待可能正是缺陷的加载状态消失。
          if (currentPage) {
            const name = `failure-${ac.results.length + 1}`
            const screenshot = resolve(ac.output, `${name}.png`)
            await currentPage.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
            await writeFile(resolve(ac.output, `${name}.txt`), await currentPage.locator('body').innerText())
            ac.browser.captures.push({ name, screenshot })
          }
          throw error
        }
      },
      dependencies
    )
  }
  async function search(page) {
    await page.getByPlaceholder(/搜索任务名称|搜索总任务或子任务名称/).fill(ac.prefix)
    await page.getByRole('button', { name: /查\s*询$/ }).click()
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
  }
  async function selectDate(page, input, date) {
    await input.click()
    const picker = page.locator('.ant-picker-dropdown:visible')
    await picker.locator('.ant-picker-year-btn').click()
    await picker.locator(`.ant-picker-year-panel td[title="${date.slice(0, 4)}"]`).click()
    if (!(await picker.locator('.ant-picker-month-panel').isVisible()))
      await picker.locator('.ant-picker-month-btn').click()
    await picker.locator(`.ant-picker-month-panel td[title="${date.slice(0, 7)}"]`).click()
    await picker.locator(`.ant-picker-date-panel td[title="${date}"]`).click()
    await expect(picker).toBeHidden()
    await expect(input).toHaveValue(date)
  }
  try {
    const employee = await pageFor(tasks.employeeToken)
    const claimed = await checkUi(
      '14 浏览器领取成功但首次安排失败，重试不重复领取',
      async () => {
        await employee.goto('http://127.0.0.1:5173/nocode-app/task-center')
        await employee.getByRole('tab', { name: '可领取任务', exact: true }).click()
        await search(employee)
        const row = employee.locator(`tr[data-row-key="${tasks.open.task.id}"]`)
        await expect(row).toContainText(tasks.open.task.title)
        await row.getByRole('button', { name: '领取并安排', exact: true }).click()
        let modal = employee.locator('.ant-modal-content:visible').last()
        await modal.getByRole('button', { name: '领取并安排', exact: true }).click()
        modal = employee.locator('.ant-modal-content:visible').filter({ hasText: '尚未安排' })
        await expect(modal.getByRole('button', { name: '保存工作安排', exact: true })).toBeVisible()
        await capture(employee, 'claim-success-unplanned')
        await modal.getByRole('button', { name: '保存工作安排', exact: true }).click()
        await expect(modal).toContainText('验收注入：计划暂不可用')
        await capture(employee, 'claim-success-plan-failed')
        await modal.getByRole('button', { name: '刷新当前安排', exact: true }).click()
        await expect(modal.getByText('验收注入：计划暂不可用', { exact: false })).toHaveCount(0)
        await modal.getByRole('button', { name: '保存工作安排', exact: true }).click()
        await expect(employee.locator('.ant-modal-content:visible')).toHaveCount(0)
        assert.equal(claimRequests.length, 1)
        assert.equal(scheduleRequests.length, 2)
        const detail = await ac.taskApi('detail', { id: tasks.open.task.id }, tasks.employeeToken)
        assert.equal(String(detail.task.assigneeId), ac.employeeB)
        assert.equal(detail.task.status, 'PENDING')
        assert.equal(detail.task.actualStart, null)
        assert.equal((await ac.context(tasks.open.task.id, tasks.employeeToken)).plans.length, 1)
        return { claimCalls: 1, scheduleAttempts: 2, injectedFailures: 1, state: 'PENDING' }
      },
      [fixtures]
    )
    await checkUi(
      '15 浏览器主管安排只读及已完成历史入口',
      async () => {
        await employee.getByRole('tab', { name: '我的待办', exact: true }).click()
        await search(employee)
        const row = employee.locator(`tr[data-row-key="${tasks.managed.task.id}"]`)
        await row.getByRole('button', { name: /^(?:计\s*划|调整安排|查看安排)$/ }).click()
        const modal = employee.locator('.ant-modal-content:visible').last()
        await expect(modal).toContainText('查看工作安排')
        await expect(modal).toContainText('主管安排')
        await expect(modal.getByRole('button', { name: '撤销此安排', exact: true })).toHaveCount(0)
        await expect(modal.getByRole('button', { name: '保存调整', exact: true })).toHaveCount(0)
        await capture(employee, 'manager-plan-readonly')
        await modal.getByRole('button', { name: /^关\s*闭$/, exact: true }).click()
        await employee.getByRole('tab', { name: '已完成', exact: true }).click()
        await search(employee)
        await expect(employee.locator(`tr[data-row-key="${tasks.history.task.id}"]`)).toContainText('已完成')
        await capture(employee, 'completed-history')
        await employee
          .locator(`tr[data-row-key="${tasks.history.task.id}"]`)
          .getByRole('button', { name: '查看安排', exact: true })
          .click()
        const historical = employee.locator('.ant-modal-content:visible').last()
        await expect(historical).toContainText('查看工作安排')
        await expect(historical).toContainText('2000-01-01')
        await expect(historical.getByRole('button', { name: /保存|撤销此安排|联系安排人/ })).toHaveCount(0)
        await historical.getByRole('button', { name: /查看安排历史/ }).click()
        await capture(employee, 'completed-plan-readonly-history')
        await historical.getByRole('button', { name: /^关\s*闭$/, exact: true }).click()
        return { protectedSchedule: tasks.managed.task.id, completed: tasks.history.task.id }
      },
      [fixtures]
    )
    await checkUi(
      '16 浏览器日周月同任务与团队范围',
      async () => {
        await employee.getByRole('tab', { name: '我的计划', exact: true }).click()
        await search(employee)
        for (const label of ['日计划', '周计划', '月计划']) {
          await employee.getByRole('button', { name: label, exact: true }).click()
          await expect(employee.locator(`tr[data-row-key="${tasks.open.task.id}"]`)).toHaveCount(1)
        }
        await capture(employee, 'personal-month-projection')
        const manager = await pageFor(tasks.managerToken)
        await manager.goto('http://127.0.0.1:5173/nocode-app/task-center/manage')
        await manager.getByRole('button', { name: '人员工作安排', exact: true }).click()
        await search(manager)
        await selectDate(manager, manager.locator('[aria-label="计划日期导航"] .ant-picker input'), '2030-10-10')
        await manager.locator('[aria-label="执行人筛选"]').click()
        const employeeName = (await ac.taskApi('members', undefined, tasks.managerToken)).find(
          member => String(member.id) === ac.employeeB
        )?.name
        assert.ok(employeeName)
        await manager.locator('.ant-select-dropdown:visible').getByText(employeeName, { exact: true }).click()
        await expect(manager.locator(`tr[data-row-key="${tasks.managed.task.id}"]`)).toBeVisible()
        await expect(manager.locator(`tr[data-row-key="${tasks.outside.task.id}"]`)).toHaveCount(0)
        await expect(manager.locator(`tr[data-row-key="${tasks.open.task.id}"]`)).toHaveCount(0)
        await capture(manager, 'team-scoped-plan')
        assert.deepEqual(ac.browser.errors, [])
        assert.deepEqual(ac.browser.blockedWrites, [])
        return { personalProjections: 3, teamTask: tasks.managed.task.id }
      },
      [claimed]
    )
  } finally {
    await browser.close()
  }
}

/** 仅补验已完成自有任务的团队日期筛选，不再创建任务或覆盖原报告。 */
async function runTeamSupplement() {
  const sourceFile = resolve(process.env.TASK_PLAN_SOURCE || '')
  assert.ok(sourceFile.startsWith(resolve('.work/task-plan') + '\\'), '只允许本轮工作目录内的精确夹具清单')
  const source = JSON.parse(await readFile(sourceFile, 'utf8'))
  assert.equal(source.source, 'task-unified-plan-20261003')
  const actor = source.users.find(user => user.username.endsWith('b'))
  assert.ok(actor?.username.startsWith(source.prefix))
  const ac = new PlanAcceptance()
  ac.prefix = source.prefix
  ac.output = resolve(sourceFile, '..', `team-supplement-${Date.now()}`)
  await mkdir(ac.output, { recursive: true })
  let browser
  let page
  let adminToken
  let credentialResets = 0
  const expect = playwrightExpect.configure({ timeout: 20000 })
  try {
    await ac.check('TEAM 补验：仅恢复精确临时员工，复用已完成任务只读历史', async () => {
      await ac.login()
      adminToken = ac.tokens.admin
      const existing = await ac.api(`/system/user/get?id=${actor.id}`)
      assert.equal(existing.username, actor.username)
      assert.equal(Number(existing.status), 1)
      const references = []
      for (const id of source.taskIds) {
        const detail = await ac.taskApi('detail', { id })
        assert.ok(detail.task.title.startsWith(source.prefix))
        if (detail.task.status === 'COMPLETED' && String(detail.task.creatorId) === actor.id)
          references.push(detail.task)
      }
      assert.equal(references.length, 1, '只复用唯一已完成的本人历史任务')
      const task = references[0]
      ac.owned.users = [actor]
      await ac.persist()
      let password = `Fa9${randomBytes(6).toString('hex')}`
      await ac.api('/system/user/update-password', { id: actor.id, password }, undefined, 'PUT')
      credentialResets++
      await ac.api('/system/user/update-status', { id: actor.id, status: 0 }, undefined, 'PUT')
      let session = await ac.api('/system/auth/login', { username: actor.username, password }, null)
      if (session.loginStatus === 'PASSWORD_CHANGE_REQUIRED') {
        password = `Fa9${randomBytes(6).toString('hex')}`
        session = await ac.api(
          '/system/auth/change-required-password',
          {
            passwordChangeToken: session.passwordChangeToken,
            newPassword: password
          },
          null,
          'PUT'
        )
      }
      const token = session.accessToken
      assert.ok(token)
      const info = await ac.api('/system/auth/get-permission-info', undefined, token)
      assert.equal(String(info.user.id), actor.id)
      browser = await chromium.launch({ channel: 'chrome', headless: true })
      page = await browser.newPage({ viewport: { width: 1512, height: 1000 } })
      await page.addInitScript(
        ({ token, info }) => {
          localStorage.setItem('token', token)
          localStorage.setItem('userInfo', JSON.stringify(info.user))
          localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
          localStorage.setItem('roles', JSON.stringify(info.roles || []))
          sessionStorage.setItem('lastActivityAt', String(Date.now()))
        },
        { token, info }
      )
      page.on('pageerror', error => ac.browser.errors.push(error.message))
      await page.route('**/api/**', route => {
        const request = route.request()
        const path = new URL(request.url()).pathname.replace(/^\/api/, '')
        if (
          ['GET', 'HEAD', 'OPTIONS'].includes(request.method()) ||
          ['/nocode/tasks/page', '/nocode/tasks/plan-context'].includes(path)
        )
          return route.continue()
        ac.browser.blockedWrites.push({ path, method: request.method() })
        return route.abort('blockedbyclient')
      })
      await page.goto('http://127.0.0.1:5173/nocode-app/task-center/manage')
      await page.getByRole('button', { name: '人员工作安排', exact: true }).click()
      await page.getByPlaceholder(/搜索任务名称|搜索总任务或子任务名称/).fill(source.prefix)
      await page.getByRole('button', { name: /查\s*询$/ }).click()
      const input = page.locator('[aria-label="计划日期导航"] .ant-picker input')
      await input.click()
      const picker = page.locator('.ant-picker-dropdown:visible')
      await picker.locator('.ant-picker-year-btn').click()
      for (let step = 0; step < 10 && !(await picker.locator('td[title="2000"]').count()); step++)
        await picker.locator('.ant-picker-header-super-prev-btn').click()
      await picker.locator('.ant-picker-year-panel td[title="2000"]').click()
      if (!(await picker.locator('.ant-picker-month-panel').isVisible()))
        await picker.locator('.ant-picker-month-btn').click()
      await picker.locator('.ant-picker-month-panel td[title="2000-01"]').click()
      await picker.locator('.ant-picker-date-panel td[title="2000-01-01"]').click()
      await expect(picker).toBeHidden()
      await expect(input).toHaveValue('2000-01-01')
      await page.locator('[aria-label="执行人筛选"]').click()
      const members = await ac.taskApi('members', undefined, token)
      const name = members.find(member => String(member.id) === actor.id)?.name
      assert.ok(name)
      await page.locator('.ant-select-dropdown:visible').getByText(name, { exact: true }).click()
      await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
      await expect(page.locator(`tr[data-row-key="${task.id}"]`)).toContainText('已完成')
      await expect(page.locator('tr[data-row-key]')).toHaveCount(1)
      const screenshot = resolve(ac.output, 'team-completed-history-filter.png')
      await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
      await writeFile(resolve(ac.output, 'team-completed-history-filter.txt'), await page.locator('body').innerText())
      ac.browser.captures.push({ name: 'team-completed-history-filter', screenshot })
      assert.deepEqual(ac.browser.errors, [])
      assert.deepEqual(ac.browser.blockedWrites, [])
      return {
        source: sourceFile,
        taskId: task.id,
        actorId: actor.id,
        selectedDate: '2000-01-01',
        newTasks: 0,
        businessWrites: 0,
        credentialResets
      }
    })
    if (ac.results.some(item => item.status !== 'passed') && page) {
      const screenshot = resolve(ac.output, 'failure.png')
      await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
      await writeFile(resolve(ac.output, 'failure.txt'), await page.locator('body').innerText())
      ac.browser.captures.push({ name: 'failure', screenshot })
    }
  } finally {
    await browser?.close()
    await ac.finish()
    if (ac.owned.users.length) {
      const after = await ac.api(`/system/user/get?id=${actor.id}`, undefined, adminToken)
      assert.equal(after.username, actor.username)
      assert.equal(Number(after.status), 1)
      ac.snapshots.push({ actorId: actor.id, username: actor.username, disabledVerified: true, credentialResets })
    }
    await ac.persist()
  }
  console.log(JSON.stringify({ output: ac.output, results: ac.results, cleanup: ac.snapshots }, null, 2))
  if (ac.results.some(item => item.status !== 'passed') || ac.errors.length) process.exitCode = 1
}

/** 弹窗布局补验只新增一项明确归自己的夹具，不提交工作安排。 */
async function runLayoutSupplement() {
  const sourceFile = resolve(process.env.TASK_PLAN_SOURCE || '')
  assert.ok(sourceFile.startsWith(resolve('.work/task-plan') + '\\'))
  const source = JSON.parse(await readFile(sourceFile, 'utf8'))
  assert.equal(source.source, 'task-unified-plan-20261003')
  const ac = new PlanAcceptance()
  ac.output = resolve(sourceFile, '..', `layout-supplement-${Date.now()}`)
  await mkdir(ac.output, { recursive: true })
  let browser
  let page
  const expect = playwrightExpect.configure({ timeout: 20000 })
  try {
    await ac.check('布局补验：管理员本人未安排任务，标签单行、日期完整、无横向溢出', async () => {
      await ac.login()
      const token = ac.tokens.admin
      const info = await ac.api('/system/auth/get-permission-info', undefined, token)
      const actorId = String(info.user.id)
      const task = await ac.create(
        ac.node('20261003 日期布局补验', { assignmentMode: 'ASSIGNED', assigneeId: actorId })
      )
      browser = await chromium.launch({ channel: 'chrome', headless: true })
      page = await browser.newPage({ viewport: { width: 1512, height: 1000 } })
      await page.addInitScript(
        ({ token, info }) => {
          localStorage.setItem('token', token)
          localStorage.setItem('userInfo', JSON.stringify(info.user))
          localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
          localStorage.setItem('roles', JSON.stringify(info.roles || []))
          sessionStorage.setItem('lastActivityAt', String(Date.now()))
        },
        { token, info }
      )
      page.on('pageerror', error => ac.browser.errors.push(error.message))
      await page.route('**/api/**', route => {
        const request = route.request()
        const path = new URL(request.url()).pathname.replace(/^\/api/, '')
        if (
          ['GET', 'HEAD', 'OPTIONS'].includes(request.method()) ||
          ['/nocode/tasks/page', '/nocode/tasks/plan-context'].includes(path)
        )
          return route.continue()
        ac.browser.blockedWrites.push({ path, method: request.method() })
        return route.abort('blockedbyclient')
      })
      await page.goto('http://127.0.0.1:5173/nocode-app/task-center')
      await page.getByRole('tab', { name: '我的待办', exact: true }).click()
      await page.getByPlaceholder('搜索任务名称').fill(ac.prefix)
      await page.getByRole('button', { name: /查\s*询$/ }).click()
      await page
        .locator(`tr[data-row-key="${task.task.id}"]`)
        .getByRole('button', { name: /^计\s*划$/ })
        .click()
      const modal = page.locator('.ant-modal-content:visible').last()
      await expect(modal.getByRole('button', { name: '保存工作安排', exact: true })).toBeEnabled()
      await expect
        .poll(() =>
          modal.evaluate(element =>
            element
              .closest('.ant-modal')
              .getAnimations({ subtree: true })
              .every(animation => animation.playState !== 'running')
          )
        )
        .toBe(true)
      const measurements = []
      for (const width of [1512, 1100]) {
        await page.setViewportSize({ width, height: 1000 })
        await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
        const layout = await modal.evaluate(element => ({
          modalOverflow: element.scrollWidth - element.clientWidth,
          pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
          labels: Array.from(element.querySelectorAll('.task-plan__dates .ant-form-item-label label')).map(label => ({
            text: label.textContent,
            height: label.getBoundingClientRect().height,
            lineHeight: parseFloat(getComputedStyle(label).lineHeight),
            width: label.getBoundingClientRect().width
          })),
          inputs: Array.from(element.querySelectorAll('.task-plan__dates input')).map(input => {
            const style = getComputedStyle(input)
            const ruler = document.createElement('canvas').getContext('2d')
            ruler.font = style.font
            return {
              value: input.value,
              width: input.getBoundingClientRect().width,
              requiredWidth:
                ruler.measureText(input.value).width + parseFloat(style.paddingLeft) + parseFloat(style.paddingRight)
            }
          })
        }))
        measurements.push({ viewportWidth: width, ...layout })
        const name = `plan-date-layout-${width}`
        const screenshot = resolve(ac.output, `${name}.png`)
        await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
        await writeFile(resolve(ac.output, `${name}.txt`), await page.locator('body').innerText())
        ac.browser.captures.push({ name, screenshot })
      }
      ac.snapshots.push({ measurements })
      for (const layout of measurements) {
        assert.equal(layout.labels.length, 2)
        for (const label of layout.labels) assert.ok(label.height <= label.lineHeight + 1, `${label.text} 应保持单行`)
        assert.equal(layout.inputs.length, 2)
        for (const input of layout.inputs) {
          assert.match(input.value, /^\d{4}-\d{2}-\d{2}$/)
          assert.ok(input.width >= input.requiredWidth, '输入框应完整显示实际字体下的日期字符串')
        }
        assert.ok(layout.modalOverflow <= 1)
        assert.ok(layout.pageOverflow <= 1)
      }
      await modal.getByRole('button', { name: '暂不调整', exact: true }).click()
      assert.deepEqual((await ac.context(task.task.id, token)).plans, [])
      assert.deepEqual(ac.browser.errors, [])
      assert.deepEqual(ac.browser.blockedWrites, [])
      return {
        source: sourceFile,
        actorId,
        actorName: info.user.nickname || info.user.username,
        taskId: task.task.id,
        newTasks: 1,
        employeeAccountsChanged: 0,
        scheduleWrites: 0,
        measurements
      }
    })
    if (ac.results.some(item => item.status !== 'passed') && page) {
      const screenshot = resolve(ac.output, 'failure.png')
      await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
      await writeFile(resolve(ac.output, 'failure.txt'), await page.locator('body').innerText())
      ac.browser.captures.push({ name: 'failure', screenshot })
    }
  } finally {
    await browser?.close()
    await ac.finish()
    await ac.persist()
  }
  console.log(JSON.stringify({ output: ac.output, results: ac.results, cleanup: ac.cleanup }, null, 2))
  if (
    ac.results.some(item => item.status !== 'passed') ||
    ac.errors.length ||
    ac.cleanup.some(item => item.status === 'failed')
  )
    process.exitCode = 1
}

if (process.env.TASK_PLAN_WRITE_RUN === '1') {
  if (process.env.TASK_PLAN_PHASE === 'team-supplement') await runTeamSupplement()
  else if (process.env.TASK_PLAN_PHASE === 'layout-supplement') await runLayoutSupplement()
  else await run()
}
