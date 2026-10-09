import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

// 仅在确认当前开发后端已更新后显式运行；夹具身份与操作证据沿用公共验收清单。
if (process.env.TASK_WORK_HISTORY_RUN !== '1') {
  console.log('准备模式：设置 TASK_WORK_HISTORY_RUN=1 验证任务留痕；不写数据。')
  process.exit(0)
}
process.env.TASK_DATA_POLICY_OUTPUT ||= '.work/task-work-history'
const ac = new TaskDataPolicyAcceptance()
const expect = playwrightExpect.configure({ timeout: 20000 })
const entry = (path, body, token) => ac.taskApi(`entries/${path}`, body, token)
const A = () => ac.tokens[ac.employeeA],
  B = () => ac.tokens[ac.employeeB]
const pageBody = (taskId, extra = {}) => ({ taskId, onlyCurrentTask: false, pageNo: 1, pageSize: 100, ...extra })
const history = (taskId, extra, token) => entry('history-page', pageBody(taskId, extra), token)
const detail = (taskId, contributionId, token) => entry('history-detail', { taskId, contributionId }, token)
const target = (taskId, entryKey, recordId) => ({ taskId, entryKey, recordId, contributionId: null })
const current = async (taskId, entryKey, recordId, token) =>
  (await entry('form', target(taskId, entryKey, recordId), token)).record.record
function saveBody(taskId, entryKey, object, values, record) {
  return {
    taskId,
    entryKey,
    contributionId: null,
    record: {
      ...ac.saveBody(object, values, record),
      formId: entryKey === '__business' ? 'business' : 'feedback',
      requestKey: randomUUID()
    }
  }
}
async function save(taskId, key, object, values, token, record) {
  const response = await entry('save', saveBody(taskId, key, object, values, record), token)
  const saved = response.handling?.result?.record
  assert.ok(saved?.id)
  await ac.rememberRecord(object, saved)
  return { record: saved, contributionId: response.contributionId }
}
let root, first, second, created, updated, deletedRecordId, baseline
let failure
try {
  await ac.login()
  await ac.prepareUsers()
  await ac.prepareApplication()
  const nodeA = ac.node('采购登记'),
    nodeB = ac.node('采购复核')
  root = await ac.create(
    ac.node('办理历史验收', {
      assignmentMode: 'UNASSIGNED',
      binding: ac.binding('business'),
      dataPolicy: { version: 1, business: 'ALL', feedback: 'GROUP' },
      entries: [
        {
          key: 'feedback',
          name: '采购反馈',
          binding: ac.binding('feedback'),
          dataMode: 'ROOT_SHARED',
          sourceNodeId: null,
          sourceEntryKey: null,
          readableFieldIds: null,
          writableFieldIds: null,
          required: false,
          allowAll: false
        }
      ]
    }),
    { nodes: [nodeA, nodeB] }
  )
  first = root.nodes.find(node => node.title === nodeA.title)
  second = root.nodes.find(node => node.title === nodeB.title)
  for (const [node, token] of [
    [first, A()],
    [second, B()]
  ]) {
    await ac.command(node.id, 'CLAIM', token)
    await ac.command(node.id, 'START', token)
  }
  await ac.check('两步骤维护同一记录，前后值来自该次真实变更', async () => {
    created = await save(first.id, '__business', ac.business, { name: ac.title('电视采购'), quantity: 3 }, A())
    deletedRecordId = created.record.id
    // 在两个任务操作之间插入一次应用原生更新，验证差分不取“前次任务提交”。
    const external = await ac.save(ac.business, { name: ac.title('电视采购'), quantity: 7 }, created.record)
    updated = await save(
      second.id,
      '__business',
      ac.business,
      { name: ac.title('电视采购'), quantity: 9 },
      B(),
      external
    )
    const quantity = ac.business.definition.fields.find(field => field.code === 'quantity').id
    const event = await detail(second.id, updated.contributionId, B())
    assert.equal(String(event.before[quantity]), '7')
    assert.equal(String(event.after[quantity]), '9')
    assert.equal(event.row.taskId, second.id)
    assert.equal(String(event.row.actorId), String(ac.employeeB))
    assert.equal(event.row.historyKnown, true)
    baseline = await detail(first.id, created.contributionId, A())
    assert.equal(String(baseline.after[quantity]), '3')
    return { recordId: deletedRecordId, before: 7, after: 9 }
  })
  await ac.check('无变化保存、失败保存与浏览不新增成功操作', async () => {
    assert.ok(updated)
    const before = await history(root.task.id)
    await entry('form', target(first.id, '__business', deletedRecordId), A())
    await save(second.id, '__business', ac.business, { name: ac.title('电视采购'), quantity: 9 }, B(), updated.record)
    const stale = await ac.request(
      '/nocode/tasks/entries/save',
      saveBody(second.id, '__business', ac.business, { name: ac.title('失败修改'), quantity: 100 }, created.record),
      B()
    )
    assert.notEqual(stale.code, 0)
    assert.equal((await history(root.task.id)).total, before.total)
    return { operations: before.total, failedWriteCode: stale.code }
  })
  await ac.check('单步骤多条反馈、明确关联与根任务汇总可区分', async () => {
    await save(first.id, 'feedback', ac.feedback, { name: ac.title('第一次询价'), quantity: 1 }, A())
    await save(first.id, 'feedback', ac.feedback, { name: ac.title('第二次询价'), quantity: 2 }, A())
    const linked = await entry(
      'link',
      { taskId: first.id, entryKey: '__business', recordId: ac.existingBusiness.id, requestKey: randomUUID() },
      A()
    )
    const snapshot = await detail(first.id, linked.contributionId, A())
    assert.equal(snapshot.row.operation, 'LINKED')
    assert.equal(snapshot.beforeKnown, false)
    assert.ok(snapshot.after)
    const feedback = await history(first.id, { onlyCurrentTask: true, entryKey: 'feedback' }, A())
    assert.equal(feedback.total, 2)
    const all = await history(root.task.id),
      own = await history(second.id, { onlyCurrentTask: true }, B())
    assert.ok(all.total > own.total)
    assert.ok(own.list.every(item => item.taskId === second.id))
    return { feedback: feedback.total, group: all.total, own: own.total }
  })
  await ac.check('删除后历史仍可查，之前快照不被覆盖，跨组贡献拒绝', async () => {
    assert.ok(baseline)
    const record = await current(second.id, '__business', deletedRecordId, B())
    assert.ok(ac.records.some(item => item.id === record.id))
    await entry(
      'delete',
      {
        taskId: second.id,
        entryKey: '__business',
        recordId: record.id,
        expectedRevision: record.revision,
        requestKey: randomUUID()
      },
      B()
    )
    const rows = await history(root.task.id, { operation: 'DELETED', recordId: record.id })
    assert.equal(rows.total, 1)
    const deletion = await detail(root.task.id, rows.list[0].id)
    assert.ok(deletion.before)
    assert.equal(deletion.after, null)
    assert.equal(deletion.row.historyKnown, true)
    assert.deepEqual((await detail(first.id, created.contributionId, A())).after, baseline.after)
    const currentRows = await entry(
      'page',
      { taskId: first.id, entryKey: '__business', all: false, onlyMine: false, pageNo: 1, pageSize: 100, search: '' },
      A()
    )
    assert.ok(!currentRows.list.some(item => item.record?.id === record.id))
    const other = await ac.create(ac.node('无关任务'))
    await ac.denied('/nocode/tasks/entries/history-detail', {
      taskId: other.task.id,
      contributionId: created.contributionId
    })
    return { deletion: rows.list[0].id, recordId: record.id }
  })
  await ac.check('真实浏览器汇总、字段差分、删除筛选与窄屏', async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true })
    const errors = [],
      blocked = [],
      failedRequests = [],
      responses = []
    const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
    try {
      page.setDefaultTimeout(20000)
      page.on('pageerror', error => errors.push(error.message))
      page.on('requestfailed', request => {
        const path = new URL(request.url()).pathname
        if (path.startsWith('/api/')) failedRequests.push({ path, error: request.failure()?.errorText })
      })
      page.on('response', async response => {
        if (!response.url().includes('/entries/history-')) return
        const body = await response.json().catch(() => ({}))
        responses.push({
          path: new URL(response.url()).pathname,
          status: response.status(),
          code: body.code,
          message: body.msg,
          total: body.data?.total
        })
      })
      const info = await ac.api('/system/auth/get-permission-info')
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
          localStorage.setItem('permissions', JSON.stringify(info.permissions))
          localStorage.setItem('roles', JSON.stringify(info.roles))
          localStorage.setItem('menus', JSON.stringify(flatten(info.menus || [])))
          sessionStorage.setItem('lastActivityAt', String(Date.now()))
        },
        { token: ac.tokens.admin, info }
      )
      const reads = new Set([
        'page',
        'detail',
        'readiness',
        'entries/list',
        'entries/page',
        'entries/form',
        'entries/history-page',
        'entries/history-detail'
      ])
      await page.route('**/api/**', route => {
        const req = route.request(),
          path = new URL(req.url()).pathname.replace(/^\/api\/nocode\/tasks\//, '')
        if (['GET', 'OPTIONS', 'HEAD'].includes(req.method()) || reads.has(path)) return route.continue()
        blocked.push(path)
        return route.abort('blockedbyclient')
      })
      await page.goto(`http://127.0.0.1:5173/nocode-app/task-center?taskId=${root.task.id}`)
      await page.waitForLoadState('networkidle')
      await page.getByRole('tab', { name: '业务数据与反馈', exact: true }).click()
      await page.getByRole('button', { name: '办理记录', exact: true }).click()
      const drawer = page.locator('.task-work-history')
      await expect(drawer.getByRole('checkbox', { name: '只看当前任务' })).not.toBeChecked()
      await expect(drawer).toContainText('采购反馈')
      await page.screenshot({ path: resolve(ac.output, 'history-group.png'), fullPage: true })
      const update = drawer.locator('tr').filter({ hasText: second.title }).filter({ hasText: '修改' })
      await update.getByText('查看当时数据').click()
      await expect(drawer.getByRole('table', { name: '本次字段变化' })).toContainText('7')
      await expect(drawer.getByRole('table', { name: '本次字段变化' })).toContainText('9')
      await page.screenshot({ path: resolve(ac.output, 'history-diff.png'), fullPage: true })
      await drawer.getByRole('button', { name: '返回办理记录' }).click()
      await drawer.locator('.ant-select[aria-label="办理记录动作"] .ant-select-selector').click()
      await page.getByTitle('删除', { exact: true }).click()
      await expect(drawer.locator('tbody tr.ant-table-row')).toHaveCount(1)
      await drawer.getByText('查看当时数据').click()
      await expect(drawer.getByRole('table', { name: '本次字段变化' })).toContainText('记录已删除')
      await page.setViewportSize({ width: 1100, height: 760 })
      await expect(drawer).toBeVisible()
      await expect
        .poll(
          async () => {
            const bounds = await page.locator('.task-work-history-drawer > .ant-drawer-content-wrapper').boundingBox()
            return !!bounds && bounds.x >= -1 && bounds.x + bounds.width <= 1101
          },
          { message: '办理记录抽屉稳定后不能超出视口' }
        )
        .toBe(true)
      assert.ok(await drawer.evaluate(el => el.scrollWidth <= el.clientWidth + 1), '留痕详情不能横向溢出')
      await page.waitForLoadState('networkidle')
      await expect(page.locator('.ant-notification-notice')).toHaveCount(0)
      await page.screenshot({ path: resolve(ac.output, 'history-deleted-1100.png'), fullPage: true })
      assert.deepEqual(errors, [])
      assert.deepEqual(blocked, [])
      assert.deepEqual(failedRequests, [])
      return {
        errors,
        blocked,
        failedRequests,
        screenshots: ['history-group.png', 'history-diff.png', 'history-deleted-1100.png']
      }
    } catch (error) {
      await page.screenshot({ path: resolve(ac.output, 'history-browser-failure.png'), fullPage: true }).catch(() => {})
      await writeFile(
        resolve(ac.output, 'browser-failure.json'),
        JSON.stringify(
          {
            errors,
            blocked,
            failedRequests,
            responses,
            visible: await page
              .locator('.task-work-history')
              .textContent()
              .catch(() => null)
          },
          null,
          2
        )
      )
      throw error
    } finally {
      await browser.close()
    }
  })
} catch (error) {
  failure = error
  console.error(error.stack)
} finally {
  await ac.finish()
  const result = {
    prefix: ac.prefix,
    results: ac.results,
    cleanup: ac.cleanup,
    errors: ac.errors,
    fatal: failure?.message
  }
  await writeFile(resolve(ac.output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(
    JSON.stringify({
      output: ac.output,
      passed: ac.results.filter(item => item.status === 'passed').length,
      total: ac.results.length
    })
  )
  if (
    failure ||
    ac.results.some(item => item.status !== 'passed') ||
    ac.cleanup.some(item => item.status === 'failed') ||
    ac.errors.length
  )
    process.exitCode = 1
}
