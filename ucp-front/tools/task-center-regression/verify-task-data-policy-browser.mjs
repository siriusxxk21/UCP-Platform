import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'

/** 已有独立夹具的真实界面验收，不另建任务；禁止浏览器写入清单外身份。 */
export async function verifyTaskDataPolicyBrowser(ac, { all, allReady, predecessor, grandchild, timeReady }) {
  const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
  const expect = playwrightExpect.configure({ timeout: 20000 })
  const browserErrors = [],
    blockedWrites = [],
    writes = []
  const browser = await chromium.launch({ channel: process.env.TASK_CENTER_BROWSER || 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => browserErrors.push(error.message))
  const token = ac.tokens[ac.employeeA]
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  function setSession({ token, info }) {
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
  }
  await page.addInitScript(setSession, { token, info })
  const reads = new Set([
    '/nocode/tasks/page',
    '/nocode/tasks/detail',
    '/nocode/tasks/readiness',
    '/nocode/tasks/entries/list',
    '/nocode/tasks/entries/page',
    '/nocode/tasks/entries/form',
    '/nocode/tasks/entries/materials',
    '/nocode/tasks/entries/receipt',
    '/nocode/tasks/entries/selection',
    '/nocode/tasks/entries/fill',
    '/nocode/tasks/entries/related',
    '/nocode/tasks/entries/related-selection',
    '/nocode/tasks/entries/related-fill'
  ])
  const guard = async route => {
    const request = route.request(),
      path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || reads.has(path)) return route.continue()
    const body = request.postDataJSON()
    const ownTask = ac.taskIds.includes(body?.taskId)
    const ownRecord = !body?.recordId || ac.records.some(item => item.id === body.recordId)
    const saveRecord = body?.record
    const ownSave =
      path === '/nocode/tasks/entries/save' &&
      String(saveRecord?.applicationId) === String(ac.applicationId) &&
      [ac.business.objectId, ac.feedback.objectId].includes(saveRecord?.objectId) &&
      (!saveRecord?.id || ac.records.some(item => item.id === saveRecord.id))
    if (
      ownTask &&
      ownRecord &&
      (ownSave || ['/nocode/tasks/entries/delete', '/nocode/tasks/entries/link'].includes(path))
    ) {
      writes.push({ path, taskId: body.taskId, entryKey: body.entryKey })
      const response = await route.fetch()
      const result = await response.json()
      const record = result.data?.handling?.result?.record
      if (result.code === 0 && record)
        await ac.rememberRecord(body.entryKey === '__business' ? ac.business : ac.feedback, record)
      return route.fulfill({ response })
    }
    blockedWrites.push({ path, method: request.method() })
    return route.abort('blockedbyclient')
  }
  await page.route('**/api/**', guard)

  async function screenshot(name, targetPage = page) {
    const path = resolve(ac.output, `${name}.png`)
    await expect(targetPage.locator('.ant-message-notice')).toHaveCount(0)
    await targetPage.screenshot({ path, fullPage: true, animations: 'disabled' })
    ac.screenshots.push(path)
  }
  async function check(name, work, dependencies) {
    const result = await ac.check(name, work, dependencies)
    if (result.status === 'failed') {
      await screenshot(`browser-failure-${ac.results.length}`).catch(() => {})
      await writeFile(
        resolve(ac.output, `browser-failure-${ac.results.length}.txt`),
        await page.locator('body').innerText()
      ).catch(() => {})
    }
    return result
  }
  async function openTask(id) {
    await page.goto(`${origin}/nocode-app/task-center?taskId=${id}`)
    await page.getByRole('tab', { name: '业务数据与反馈', exact: true }).click()
    await page.locator('.task-entry-workspace').getByRole('tab', { name: '过程反馈', exact: true }).click()
    const workspace = page.locator('.task-entry-workspace[data-category="FEEDBACK"]')
    await expect(workspace).toBeVisible()
    await workspace.getByRole('tab', { name: '施工日志', exact: true }).click()
    return workspace
  }
  const groupTab = workspace => workspace.getByRole('tab', { name: '本组任务数据', exact: true })
  const allTab = workspace => workspace.getByRole('tab', { name: '全部业务数据', exact: true })
  const row = (workspace, name) => workspace.locator('tr[data-row-key]').filter({ hasText: name })
  const waitLoaded = workspace => expect(workspace.locator('.ant-spin-spinning')).toHaveCount(0)
  async function switchScope(workspace, allRecords) {
    const response = page.waitForResponse(response => {
      if (!response.url().endsWith('/nocode/tasks/entries/page') || response.request().method() !== 'POST') return false
      const body = response.request().postDataJSON()
      return body.taskId === all.task.id && body.entryKey === 'feedback' && body.all === allRecords
    })
    await (allRecords ? allTab(workspace) : groupTab(workspace)).click()
    const result = await (await response).json()
    assert.equal(result.code, 0, result.msg)
    await waitLoaded(workspace)
    return result.data
  }
  const editor = () =>
    page.locator('.ant-drawer-content:visible').filter({
      has: page.getByRole('button', { name: '保存记录', exact: true })
    })
  async function fillName(value) {
    const field = editor()
      .locator('.ant-form-item')
      .filter({
        has: page.locator('label').filter({ hasText: /^反馈内容$/ })
      })
    await field.locator('input').fill(value)
  }
  async function saveEditor() {
    const response = page.waitForResponse(
      response => response.url().endsWith('/nocode/tasks/entries/save') && response.request().method() === 'POST'
    )
    await editor().getByRole('button', { name: '保存记录', exact: true }).click()
    const result = await (await response).json()
    assert.equal(result.code, 0, result.msg)
    await expect(editor()).toHaveCount(0)
    return result.data
  }

  try {
    const allView = await check(
      '员工真实界面可切换本总任务/全部业务数据，能看应用历史记录',
      async () => {
        const workspace = await openTask(all.task.id)
        await expect(groupTab(workspace)).toBeVisible()
        await expect(allTab(workspace)).toBeEnabled()
        await switchScope(workspace, true)
        await expect(row(workspace, ac.existingFeedback.values[ac.feedback.ids.name])).toBeVisible()
        await expect(row(workspace, ac.title('模板授权登记'))).toBeVisible()
        await expect(workspace.locator('.ant-alert-error')).toHaveCount(0)
        await screenshot('employee-all-records')
        const selected = await switchScope(workspace, false)
        assert.equal(selected.total, 1, '切回本组后只保留已在本总任务办理的预存记录')
        await expect(row(workspace, ac.existingFeedback.values[ac.feedback.ids.name])).toBeVisible()
        await expect(row(workspace, ac.title('模板授权登记'))).toHaveCount(0)
        await expect(row(workspace, ac.title('后续节点修改'))).toHaveCount(0)
        await expect(workspace.locator('tr[data-row-key]')).toHaveCount(1)
        await screenshot('employee-task-records')
      },
      [allReady]
    )
    await check(
      '真实浏览器通过任务新增/修改/删除日志，来源为当前任务及领取员工',
      async () => {
        const workspace = page.locator('.task-entry-workspace[data-category="FEEDBACK"]')
        const original = ac.title('浏览器日志'),
          revised = ac.title('浏览器日志修订')
        await workspace.getByRole('button', { name: '新增反馈', exact: true }).click()
        await fillName(original)
        const created = await saveEditor()
        const recordId = created.handling.result.record.id
        await expect(row(workspace, original)).toBeVisible()
        await row(workspace, original).getByText('修改', { exact: true }).click()
        await fillName(revised)
        await saveEditor()
        await expect(row(workspace, revised)).toBeVisible()
        await row(workspace, revised).getByText('来源', { exact: true }).click()
        const sources = page.locator('.ant-drawer-content:visible').filter({
          has: page.locator('.ant-drawer-title').filter({ hasText: /^过程反馈来源$/ })
        })
        await expect(sources).toContainText(all.task.title)
        await expect(sources).toContainText('表单验收a')
        await expect(sources).not.toContainText(/\b\d{13}\b/)
        await expect(sources).toContainText(/\d{4}-\d{2}-\d{2}/)
        await screenshot('employee-operation-source')
        await sources.locator('.ant-drawer-close').click()
        await row(workspace, revised).getByText('删除', { exact: true }).click()
        const confirmation = page.locator('.ant-modal:visible')
        await expect(confirmation).toContainText('删除')
        const response = page.waitForResponse(
          response => response.url().endsWith('/nocode/tasks/entries/delete') && response.request().method() === 'POST'
        )
        await confirmation.getByRole('button', { name: /删除/, exact: false }).click()
        const deleted = await (await response).json()
        assert.equal(deleted.code, 0, deleted.msg)
        await expect(row(workspace, revised)).toHaveCount(0)
        await screenshot('employee-record-deleted')
        return { taskId: all.task.id, recordId }
      },
      [allView]
    )
    await check(
      'GROUP 子任务显示根共享范围，不开放全业务列表，现有共享数据可见',
      async () => {
        const workspace = await openTask(predecessor.id)
        await expect(workspace.getByText('本组任务数据', { exact: true })).toBeVisible()
        await expect(allTab(workspace)).toHaveCount(0)
        await expect(workspace).toContainText(ac.title('后续节点修改'))
        await waitLoaded(workspace)
        await expect(workspace.locator('.ant-alert-error')).toHaveCount(0)
        await screenshot('employee-group-only')
        await page.setViewportSize({ width: 1100, height: 800 })
        await screenshot('employee-group-1100')
        const completedPage = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
        completedPage.on('pageerror', error => browserErrors.push(error.message))
        await completedPage.route('**/api/**', guard)
        const completedToken = ac.tokens[ac.employeeB]
        const completedInfo = await ac.api('/system/auth/get-permission-info', undefined, completedToken)
        await completedPage.addInitScript(setSession, { token: completedToken, info: completedInfo })
        try {
          await completedPage.goto(`${origin}/nocode-app/task-center?taskId=${grandchild.id}`)
          await completedPage.getByRole('tab', { name: '任务信息', exact: true }).click()
          const descriptions = completedPage.locator('.task-hierarchy-drawer .ant-descriptions')
          await expect(completedPage.locator('.task-detail__actions')).toContainText('已完成')
          await expect(descriptions).toContainText('2000-01-03 09:00')
          await expect(descriptions).toContainText('2000-01-04 09:00')
          for (const label of ['预计开始', '预计结束', '实际开始', '实际结束']) {
            const heading = descriptions
              .locator('.ant-descriptions-item-label')
              .filter({ hasText: new RegExp(`^${label}$`) })
            await expect(heading).toBeVisible()
            await expect(heading.locator('xpath=following-sibling::*[1]')).toContainText(
              /\d{4}-\d{2}-\d{2} \d{2}:\d{2}/
            )
          }
          await screenshot('employee-completed-four-times', completedPage)
        } finally {
          await completedPage.close()
        }
      },
      [timeReady]
    )
    await check('浏览器无脚本错误、无越界写请求，真实业务请求均为夹具任务', async () => {
      assert.deepEqual(browserErrors, [])
      assert.deepEqual(blockedWrites, [])
      assert.ok(
        writes.some(item => item.path.endsWith('/save')),
        '必须确实从界面保存而非仅看静态按钮'
      )
      assert.ok(
        writes.some(item => item.path.endsWith('/delete')),
        '必须确实从界面删除'
      )
      return { writes, browserErrors, blockedWrites }
    })
  } finally {
    await browser.close()
    await writeFile(
      resolve(ac.output, 'browser-result.json'),
      JSON.stringify({ browserErrors, blockedWrites, writes }, null, 2)
    )
  }
}
