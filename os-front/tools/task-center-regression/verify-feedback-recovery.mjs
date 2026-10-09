import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 独立夹具与真实 Chrome；只在传输层丢弃响应，不模拟业务结果或操作已有用户数据。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.output = resolve(process.env.TASK_FEEDBACK_OUTPUT || '.work/task-feedback-recovery', ac.prefix)
ac.owned.tasks = []
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const api = (path, body) => ac.api(`/nocode/tasks/${path}`, body)
const detail = id => api('detail', { id })
const name = title => `${ac.prefix} ${title}`
const errors = []
let browser, page, object, applicationId
const entry = (key, title, extra = {}) => ({
  key,
  name: title,
  binding: { applicationId, formId: 'feedback', entryId: null },
  dataMode: 'ROOT_SHARED',
  required: true,
  requireOwnContribution: true,
  allowAll: false,
  ...extra
})
async function create(title, entries, parentId = null) {
  const created = await api('create', {
    parentId,
    task: { id: randomUUID(), title: name(title), assigneeId: null, predecessorIds: [], entries },
    requestKey: randomUUID()
  })
  ac.owned.tasks.push({ id: created.task.id, title: created.task.title })
  await ac.persist()
  return created.task.id
}
async function start(id) {
  return api('transition', {
    id,
    action: 'START',
    expectedRevision: (await detail(id)).task.revision,
    note: '反馈恢复验收开始',
    requestKey: randomUUID()
  })
}
const feedbackPage = (taskId, entryKey, onlyMine = false) =>
  api('entries/page', { taskId, entryKey, onlyMine, all: false, pageNo: 1, pageSize: 100, search: '' })
const shot = file => page.screenshot({ path: resolve(ac.output, file), fullPage: true, animations: 'disabled' })

try {
  await ac.login()
  object = await ac.object('feedback_recovery', '反馈恢复验收', [ac.field('name', 'TEXT', '反馈内容')])
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: `${ac.prefix}_feedback`,
    name: name('反馈恢复应用'),
    description: 'LINK 传输故障与完成清单闭环专用夹具',
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        {
          id: 'feedback',
          code: 'feedback',
          name: '反馈填写',
          kind: 'FORM',
          config: {
            objectId: object.objectId,
            nodes: [{ id: 'name', type: 'FIELD', fieldId: object.ids.name, children: [] }],
            detailIds: [],
            options: { layout: 'vertical', submitText: '保存反馈记录' }
          }
        }
      ]
    }
  })
  applicationId = ac.app.application.id
  ac.owned.applications.push({ id: applicationId, code: ac.app.application.code })
  await ac.persist()
  await ac.share(object, ac.grant(object))
  await ac.api('/nocode/application/publish', {
    id: applicationId,
    expectedRevision: ac.app.application.revision,
    reason: '真实反馈恢复验收'
  })

  browser = await chromium.launch({ channel: process.env.TASK_CENTER_BROWSER || 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1512, height: 1000 } })
  page.setDefaultTimeout(25000)
  page.on('pageerror', error => errors.push(error.message))
  const info = await ac.api('/system/auth/get-permission-info')
  await page.addInitScript(
    ({ token, info }) => {
      const menus = (items, parentId = 0) =>
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
                ...menus(menu.children || [], menu.id)
              ]
        )
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(menus(info.menus || [])))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )

  await ac.record('真实只读 LINK 已成功但响应丢失，暂阻回执后刷新恢复且只产生一条贡献', async () => {
    const root = await create('共享材料来源', [entry('work', '原始材料')])
    await start(root)
    const saved = await api('entries/save', {
      taskId: root,
      entryKey: 'work',
      contributionId: null,
      record: { ...ac.saveBody(object, { name: name('共享有效材料') }), formId: 'feedback', requestKey: randomUUID() }
    })
    const source = saved.handling.result.record
    const child = await create(
      '只读关联恢复',
      [entry('review', '只读复核', { dataMode: 'SOURCE_SHARED', sourceNodeId: root, sourceEntryKey: 'work' })],
      root
    )
    await start(child)
    const capability = (await api('entries/list', { id: child }))[0]
    assert.equal(capability.canWrite, false)
    assert.equal(capability.canLink, true)
    assert.equal((await api('readiness', { id: child })).canComplete, false)
    const before = await ac.get(object, source)
    let original,
      committed,
      linkRequests = 0,
      blockedReceipts = 0,
      blockReceipt = true
    const linkRoute = async route => {
      linkRequests++
      if (linkRequests === 1) {
        original = route.request().postDataJSON()
        const response = await route.fetch()
        const body = await response.json()
        assert.equal(body.code, 0, body.msg)
        committed = body.data
        await route.abort('failed')
      } else await route.continue()
    }
    const receiptRoute = async route => {
      if (blockReceipt) {
        blockedReceipts++
        await route.abort('failed')
      } else await route.continue()
    }
    await page.route('**/api/nocode/tasks/entries/link', linkRoute)
    await page.route('**/api/nocode/tasks/entries/receipt', receiptRoute)
    try {
      await page.goto(`${origin}/nocode-app/task-center/manage?taskId=${child}`)
      await page.getByRole('tab', { name: '业务材料', exact: true }).click()
      const workspace = page.locator('.task-entry-workspace')
      await expect(workspace.getByRole('button', { name: '新增反馈', exact: true })).toBeDisabled()
      await workspace.getByText('关联为本节点反馈', { exact: true }).click()
      const confirmation = page.locator('.ant-drawer-content').filter({ hasText: '将此记录关联为本节点反馈？' })
      await confirmation.getByRole('button', { name: '关联记录', exact: true }).click()
      await expect(workspace.getByText('关联结果尚未确认，原请求已保留。', { exact: true })).toBeVisible()
      await expect.poll(() => blockedReceipts).toBeGreaterThan(0)
      const storageKey = `nocode.task.link.pending:${encodeURIComponent(String(info.user.id))}:${child}`
      assert.deepEqual(await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)), storageKey), original)
      assert.equal((await feedbackPage(child, 'review', true)).total, 1)
      await shot('link-response-lost.png')

      await page.reload()
      await page.getByRole('tab', { name: '业务材料', exact: true }).click()
      await expect(workspace.getByRole('button', { name: '查询关联结果', exact: true })).toBeVisible()
      assert.deepEqual(await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)), storageKey), original)
      blockReceipt = false
      await workspace.getByRole('button', { name: '查询关联结果', exact: true }).click()
      await expect(workspace.getByRole('button', { name: '查询关联结果', exact: true })).toHaveCount(0)
      assert.equal(await page.evaluate(key => sessionStorage.getItem(key), storageKey), null)
      assert.equal(linkRequests, 1)
      const receipt = await api('entries/receipt', {
        taskId: child,
        entryKey: 'review',
        requestKey: original.requestKey
      })
      assert.equal(receipt.contributionId, committed.contributionId)
      assert.equal((await api('entries/link', original)).contributionId, committed.contributionId)
      const contributions = await feedbackPage(child, 'review', true)
      assert.equal(contributions.total, 1)
      assert.equal(contributions.list[0].record.id, source.id)
      assert.equal(contributions.list[0].sources.filter(s => s.taskId === child && s.operation === 'LINKED').length, 1)
      // 入口摘要不返回贡献计数；用本人贡献分页、来源条数和稳定回执身份验证没有重复。
      assert.deepEqual(await ac.get(object, source), before)
      assert.equal((await api('readiness', { id: child })).canComplete, true)
      await shot('link-refresh-recovered.png')
      return {
        root,
        child,
        recordId: source.id,
        contributionId: committed.contributionId,
        original,
        linkRequests,
        blockedReceipts
      }
    } finally {
      await page.unroute('**/api/nocode/tasks/entries/link', linkRoute)
      await page.unroute('**/api/nocode/tasks/entries/receipt', receiptRoute)
    }
  })

  await ac.record('真实完成清单保留备注，去处理保存反馈后返回完成并同步原列表', async () => {
    const id = await create('补反馈再完成', [entry('work', '交付反馈', { dataMode: 'INDEPENDENT' })])
    await start(id)
    const current = await detail(id)
    await page.goto(`${origin}/nocode-app/task-center/manage`)
    await page.getByPlaceholder('搜索任务名称').fill(current.task.title)
    await page.getByPlaceholder('搜索任务名称').press('Enter')
    const row = page.locator(`tr[data-row-key="${id}"]`)
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '完成', exact: true }).click()
    const closing = page.locator('.ant-drawer-content').filter({ hasText: `完成任务：${current.task.title}` })
    const note = name('补充交付材料后完成，备注应保留')
    await closing.getByRole('textbox', { name: '完成备注', exact: true }).fill(note)
    await expect(closing.getByRole('button', { name: '确认完成', exact: true })).toBeDisabled()
    await closing.getByRole('button', { name: '去处理', exact: true }).click()
    const inspection = page.locator('.ant-drawer-content').filter({ has: page.locator('.task-detail__current') })
    const workspace = inspection.locator('.task-entry-workspace')
    await expect(workspace.getByRole('tab', { name: '交付反馈', exact: true })).toBeVisible()
    await workspace.getByRole('button', { name: '新增反馈', exact: true }).click()
    const editor = page
      .locator('.ant-drawer-content')
      .filter({ has: page.getByRole('button', { name: '保存反馈记录', exact: true }) })
    const field = editor
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^反馈内容$/ }) })
    await field.locator('input').fill(name('浏览器真实交付反馈'))
    await editor.getByRole('button', { name: '保存反馈记录', exact: true }).click()
    await expect(editor).toBeHidden()
    await expect(workspace.getByText(name('浏览器真实交付反馈'), { exact: true })).toBeVisible()
    assert.equal((await detail(id)).task.status, 'RUNNING')
    assert.equal((await feedbackPage(id, 'work', true)).total, 1)
    await inspection.locator('.ant-drawer-close').click()
    await expect(inspection).toBeHidden()
    await expect(closing.getByRole('textbox', { name: '完成备注', exact: true })).toHaveValue(note)
    await expect(closing.getByRole('button', { name: '确认完成', exact: true })).toBeEnabled()
    await shot('readiness-feedback-return.png')
    await closing.getByRole('button', { name: '确认完成', exact: true }).click()
    await expect(page.locator('.ant-drawer-content:visible')).toHaveCount(0)
    await expect(row.getByText('已完成', { exact: true })).toBeVisible()
    await expect(row.getByRole('button', { name: '完成', exact: true })).toHaveCount(0)
    const completed = await detail(id)
    assert.equal(completed.task.status, 'COMPLETED')
    const events = completed.events.filter(event => event.taskId === id && event.type === 'COMPLETED')
    assert.equal(events.length, 1)
    assert.equal(events[0].note, note)
    assert.equal((await api('entries/materials', { id }))[0].submissions.length, 1)
    await shot('feedback-completed-list.png')
    return { taskId: id, note, feedbackCount: 1, completionEvents: events.length, listUpdated: true }
  })
  assert.deepEqual(errors, [])
  console.log(`PASS ${ac.checks.length} 组真实 Chrome 反馈恢复验收：${ac.output}`)
} catch (error) {
  if (page) {
    await shot('failure.png').catch(() => {})
    await writeFile(resolve(ac.output, 'failure.txt'), await page.locator('body').innerText()).catch(() => {})
  }
  throw error
} finally {
  await browser?.close()
  await ac.persist()
  await writeFile(resolve(ac.output, 'page-errors.json'), JSON.stringify(errors, null, 2))
}
