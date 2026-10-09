import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅创建本轮前缀的普通任务；浏览器只注入传输失败，状态和回执使用开发服务。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
ac.output = resolve(process.env.TASK_TRANSITION_OUTPUT || '.work/task-transition-recovery', ac.prefix)
ac.owned.tasks = []
const api = (path, body) => ac.api(`/nocode/tasks/${path}`, body)
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const detail = id => api('detail', { id })
async function create(name) {
  const result = await api('create', {
    task: { id: randomUUID(), title: `${ac.prefix} ${name}`, assigneeId: null, predecessorIds: [] },
    requestKey: randomUUID()
  })
  ac.owned.tasks.push({ id: result.task.id, title: result.task.title })
  await ac.persist()
  return result.task.id
}
async function command(id, action) {
  return { id, action, expectedRevision: (await detail(id)).task.revision, note: '恢复验收说明', requestKey: randomUUID() }
}
let browser
try {
  await ac.login()
  await ac.record('无回执同修订仍待确认，修订改变后原命令失效且迟到重试不能写入', async () => {
    const id = await create('未提交请求')
    const original = await command(id, 'START')
    assert.deepEqual(await api('transition-recovery', original), { applied: null, superseded: false })
    await api('transition', { ...original, requestKey: randomUUID() })
    const before = await detail(id)
    assert.deepEqual(await api('transition-recovery', original), { applied: null, superseded: true })
    const late = await ac.request('/nocode/tasks/transition', original)
    assert.notEqual(late.code, 0)
    assert.deepEqual((await detail(id)).events, before.events)
    return { id }
  })
  await ac.record('已提交命令返回本人完整回执，篡改内容被拒绝且重放不重复事件', async () => {
    const id = await create('成功回执')
    const original = await command(id, 'START')
    await api('transition', original)
    const before = await detail(id)
    const receipt = await api('transition-recovery', original)
    assert.equal(receipt.superseded, false)
    assert.equal(receipt.applied.task.status, 'RUNNING')
    assert.notEqual((await ac.request('/nocode/tasks/transition-recovery', { ...original, note: '篡改' })).code, 0)
    await api('transition', original)
    assert.deepEqual((await detail(id)).events, before.events)
    return { id }
  })

  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.setDefaultTimeout(20000)
  const info = await ac.api('/system/auth/get-permission-info')
  await page.addInitScript(({ token, info }) => {
    const menus = (items, parentId = 0) => items.flatMap((menu, index) => menu.visible === false ? [] : [
      { id: menu.id, name: menu.name, path: menu.path || '', component: menu.component, icon: menu.icon, parentId: menu.parentId ?? parentId, sort: menu.sort ?? index },
      ...menus(menu.children || [], menu.id)
    ])
    localStorage.setItem('token', token)
    localStorage.setItem('userInfo', JSON.stringify(info.user))
    localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
    localStorage.setItem('roles', JSON.stringify(info.roles || []))
    localStorage.setItem('menus', JSON.stringify(menus(info.menus || [])))
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, { token: ac.tokens.admin, info })

  for (const committed of [false, true]) {
    await ac.record(committed ? '真实浏览器：响应丢失后刷新恢复，重试保持原命令且只完成一次' : '真实浏览器：未提交后任务被修改，重试确认失效后解除锁定并保留备注', async () => {
      const id = await create(committed ? '刷新恢复' : '冲突解锁')
      await api('transition', await command(id, 'START'))
      let original, intercepted = false
      const routeHandler = async route => {
        if (!intercepted) {
          intercepted = true
          original = route.request().postDataJSON()
          if (committed) {
            const response = await route.fetch()
            assert.equal((await response.json()).code, 0)
          }
          await route.abort('failed')
        } else await route.continue()
      }
      await page.route('**/api/nocode/tasks/transition', routeHandler)
      await page.goto(`${origin}/nocode-app/task-center/manage?taskId=${id}`)
      await page.getByRole('button', { name: '完成任务', exact: true }).click()
      const actionDrawer = page.locator('.ant-drawer-content').last()
      await actionDrawer.locator('textarea').fill('浏览器保留的原始完成说明')
      await actionDrawer.getByRole('button', { name: '确认完成', exact: true }).click()
      await expect(page.getByRole('button', { name: '重试原请求', exact: true })).toBeVisible()
      const key = `nocode.task.transition.pending:${encodeURIComponent(String(info.user.id))}:${id}`
      assert.deepEqual(await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)), key), original)
      if (committed) {
        await page.reload()
        await expect(page.getByRole('button', { name: '重试原请求', exact: true })).toBeVisible()
        await expect(page.locator('.ant-drawer-content').last().locator('textarea')).toHaveValue(original.note)
        let replay
        const listener = request => { if (request.url().endsWith('/nocode/tasks/transition')) replay = request.postDataJSON() }
        page.on('request', listener)
        await page.getByRole('button', { name: '重试原请求', exact: true }).click()
        await expect(page.getByRole('button', { name: '重试原请求', exact: true })).toHaveCount(0)
        page.off('request', listener)
        assert.deepEqual(replay, original)
        assert.equal((await detail(id)).events.filter(event => event.type === 'COMPLETED').length, 1)
      } else {
        // 本轮夹具另一个客户端取消，确保旧 COMPLETE 永远不能提交。
        await api('transition', await command(id, 'CANCEL'))
        await page.getByRole('button', { name: '重试原请求', exact: true }).click()
        await expect(page.locator('.ant-drawer-content').last()).toContainText('原操作未提交')
        await expect(page.locator('.ant-drawer-content').last().locator('textarea')).toBeEnabled()
        await expect(page.locator('.ant-drawer-content').last().locator('textarea')).toHaveValue(original.note)
        await expect(page.locator('.ant-drawer-content').last().getByRole('button', { name: /^返\s*回$/ })).toBeEnabled()
        assert.equal((await detail(id)).task.status, 'CANCELLED')
      }
      assert.equal(await page.evaluate(key => sessionStorage.getItem(key), key), null)
      await page.screenshot({ path: resolve(ac.output, committed ? 'refresh-recovered.png' : 'conflict-released.png'), fullPage: true })
      await page.unroute('**/api/nocode/tasks/transition', routeHandler)
      return { id, committed, noteRetained: true }
    })
  }
  console.log(`PASS ${ac.checks.length} 组恢复验收：${ac.output}`)
} finally {
  await browser?.close()
  await ac.persist()
}
