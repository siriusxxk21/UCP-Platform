import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

/** 旧门户退役专项：登录后只读现有任务/应用，拒绝业务写请求，不创建或修改数据。 */
const expect = playwrightExpect.configure({ timeout: 20000 })
const pages = [
  { path: '/nocode-app/task-center', title: '我的任务', key: 'mine' },
  { path: '/nocode-app/task-center/manage', title: '任务管理', key: 'manage' },
  { path: '/nocode-app/task-center/templates', title: '任务模板', key: 'templates' }
]
const readPosts = new Set([
  '/nocode/tasks/page',
  '/nocode/tasks/personal-tree-page',
  '/nocode/tasks/personal-tree-children',
  '/nocode/tasks/detail',
  '/nocode/tasks/readiness',
  '/nocode/tasks/form',
  '/nocode/tasks/form-preview',
  '/nocode/tasks/entries/list',
  '/nocode/tasks/entries/page',
  '/nocode/tasks/entries/form',
  '/nocode/runtime/page',
  '/nocode/runtime/get',
  '/nocode/runtime/form'
])
const retiredParameters = ['legacy', 'entry', 'app', 'recordId', 'version', 'draftId']

async function run() {
  const output = resolve(process.env.TASK_LEGACY_RETIREMENT_OUTPUT || `.work/task-legacy-retirement/${Date.now()}`)
  const origin = process.env.TASK_LEGACY_FRONTEND || 'http://127.0.0.1:5173'
  const ac = new FormAcceptance(process.env.TASK_LEGACY_BACKEND || 'http://127.0.0.1:8080/api', output)
  const report = { output, checks: [], captures: [], errors: [], blockedWrites: [], apiFailures: [], requests: [] }
  let browser
  const persist = () => writeFile(resolve(output, 'result.json'), JSON.stringify(report, null, 2))
  const check = async (name, action) => {
    try {
      report.checks.push({ name, passed: true, detail: await action() })
    } catch (error) {
      report.checks.push({ name, passed: false, error: error.message })
      throw error
    } finally {
      await persist()
    }
  }
  try {
    await mkdir(output, { recursive: true })
    await ac.login()
    const info = await ac.api('/system/auth/get-permission-info')
    await check('00 旧门户目录、草稿汇总和活动接口已退役', async () => {
      const results = []
      for (const [path, method, body] of [
        ['/nocode/task-entry/mine', 'GET', undefined],
        ['/nocode/task-entry/drafts', 'POST', { before: null, limit: 20 }],
        ['/nocode/task-entry/activity', 'POST', { entry: {}, before: null }]
      ]) {
        const response = await fetch(ac.base + path, {
          method,
          headers: { Authorization: `Bearer ${ac.tokens.admin}`, 'Content-Type': 'application/json' },
          body: body === undefined ? undefined : JSON.stringify(body)
        })
        let code
        try {
          code = (await response.json()).code
        } catch {
          // 服务器的静态 404 响应不一定是 JSON。
        }
        assert.ok(
          response.status === 404 || response.status === 405 || code === 404 || code === 405,
          `${path}: ${response.status}/${code}`
        )
        results.push({ path, method, status: response.status, code })
      }
      return results
    })
    const applications = await ac.api('/nocode/application/page?pageNo=1&pageSize=100')
    const appId = process.env.TASK_LEGACY_APPLICATION_ID || applications.list[0]?.id
    assert.ok(appId, '需要一个可读应用来验证工作区；不会创建验收应用')
    const mine = await ac.api('/nocode/tasks/personal-tree-page', {
      scope: 'MINE',
      tab: 'POOL',
      pageNo: 1,
      pageSize: 10,
      scheduleScope: 'PERSONAL'
    })
    const managed = mine.list.length
      ? null
      : await ac.api('/nocode/tasks/page', { scope: 'MANAGE', tab: 'POOL', pageNo: 1, pageSize: 10 })
    const taskId = mine.list[0]?.task.id || managed?.list[0]?.id
    browser = await chromium.launch({ channel: 'chrome', headless: true })
    const page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
    page.on('pageerror', error => report.errors.push(error.message))
    page.on('response', async response => {
      if (!response.url().includes('/api/')) return
      try {
        const body = await response.json()
        if (body.code !== undefined && body.code !== 0)
          report.apiFailures.push({ path: new URL(response.url()).pathname, code: body.code, message: body.msg })
      } catch {
        // 非 JSON 响应不作业务结果，不保存请求体、认证头或令牌。
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
      report.requests.push({ path, method: request.method() })
      if (['GET', 'HEAD', 'OPTIONS'].includes(request.method()) || readPosts.has(path)) return route.continue()
      report.blockedWrites.push({ path, method: request.method() })
      return route.abort('blockedbyclient')
    })
    const settle = async () => {
      await page.waitForLoadState('networkidle')
      await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    }
    const goto = async path => {
      await page.goto(`${origin}${path}`)
      await settle()
    }
    const capture = async name => {
      await settle()
      const screenshot = resolve(output, `${name}.png`)
      await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' })
      report.captures.push({ name, screenshot, url: page.url() })
      await persist()
    }
    const noPortal = async () => {
      await expect(page.locator('.task-center-page')).toHaveCount(0)
      for (const name of ['业务申请与办理草稿', '我的申请', '我的收藏', '全部事项']) {
        await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
        await expect(page.getByRole('tab', { name, exact: true })).toHaveCount(0)
      }
    }
    const closeDrawer = async () => {
      await page.locator('.ant-drawer-content:visible .ant-drawer-close').last().click()
      await expect(page.locator('.ant-drawer-content:visible')).toHaveCount(0)
    }

    await check('01 三个正式任务页宽窄屏均无旧门户入口', async () => {
      for (const width of [1512, 1100]) {
        await page.setViewportSize({ width, height: width === 1512 ? 1050 : 760 })
        for (const current of pages) {
          await goto(current.path)
          await expect(page.getByRole('main', { name: current.title, exact: true })).toBeVisible()
          await noPortal()
          await capture(`${current.key}-${width}`)
        }
      }
      return { pages: pages.map(item => item.path), widths: [1512, 1100] }
    })
    await check('02 旧门户和旧入口草稿深链归一新版且不加载旧业务', async () => {
      const probes = [
        'legacy=1',
        'entry=retirement-probe&app=retirement-probe&recordId=retirement-probe',
        'legacy=1&entry=retirement-probe&app=retirement-probe&version=1&draftId=retirement-probe'
      ]
      const links = []
      for (const current of pages) {
        for (const query of probes) {
          const start = report.requests.length
          await goto(`${current.path}?${query}#retirement`)
          await expect.poll(() => new URL(page.url()).search).toBe('')
          const location = new URL(page.url())
          assert.equal(location.pathname, current.path)
          assert.equal(location.hash, '#retirement')
          for (const key of retiredParameters) assert.equal(location.searchParams.has(key), false)
          await expect(page.getByRole('main', { name: current.title, exact: true })).toBeVisible()
          await expect(page.locator('.ant-drawer-content:visible')).toHaveCount(0)
          await noPortal()
          const requests = report.requests.slice(start)
          assert.ok(!requests.some(item => item.path.startsWith('/nocode/task-entry/')))
          assert.ok(!requests.some(item => item.path === '/nocode/tasks/draft-get'))
          links.push({ path: current.path, query, finalUrl: page.url() })
        }
      }
      await capture('retired-link-normalized')
      return links
    })
    await check('03 新建任务与模板编辑宽窄屏仍可打开和关闭', async () => {
      for (const width of [1512, 1100]) {
        await page.setViewportSize({ width, height: width === 1512 ? 1050 : 760 })
        await goto('/nocode-app/task-center/manage')
        await page.getByRole('button', { name: '新建任务', exact: true }).click()
        await expect(page.locator('.ant-drawer-title').filter({ hasText: /^新建任务$/ })).toBeVisible()
        await expect(page.getByRole('button', { name: '加入任务池', exact: true })).toBeVisible()
        await capture(`new-task-${width}`)
        await closeDrawer()
        await goto('/nocode-app/task-center/templates')
        await page.getByRole('button', { name: '新建模板', exact: true }).click()
        await expect(page.locator('.ant-drawer-title').filter({ hasText: /^新建任务模板$/ })).toBeVisible()
        await expect(page.getByPlaceholder('例如：标准施工项目模板')).toBeVisible()
        await capture(`new-template-${width}`)
        await closeDrawer()
      }
      await goto('/nocode-app/task-center/launch?entry=retirement-probe&draftId=retirement-probe&legacy=1')
      await expect(page.locator('.ant-drawer-title').filter({ hasText: /^新建任务$/ })).toBeVisible()
      await expect.poll(() => new URL(page.url()).pathname).toBe('/nocode-app/task-center/manage')
      for (const key of retiredParameters) assert.equal(new URL(page.url()).searchParams.has(key), false)
      await capture('legacy-launch-current-task')
      await closeDrawer()
      return { saved: false, published: false, widths: [1512, 1100] }
    })
    await check('04 应用工作区无旧任务入口配置且共享资源页仍可打开', async () => {
      const tabs = []
      for (const width of [1512, 1100]) {
        await page.setViewportSize({ width, height: width === 1512 ? 1050 : 760 })
        await goto(`/nocode-app/workspace?id=${encodeURIComponent(appId)}`)
        await expect(page.locator('.workspace-tabs')).toBeVisible()
        await expect(page.getByRole('tab', { name: '任务入口', exact: true })).toHaveCount(0)
        await expect(page.getByRole('button', { name: '新增任务入口', exact: true })).toHaveCount(0)
        await expect(page.getByRole('tab', { name: '已引用对象', exact: true })).toBeVisible()
        await capture(`application-workspace-${width}`)
        await page.getByRole('tab', { name: '页面与视图', exact: true }).click()
        await settle()
        await expect(page.getByRole('tab', { name: '页面与视图', exact: true })).toHaveAttribute(
          'aria-selected',
          'true'
        )
        await capture(`application-resources-${width}`)
        tabs.push({ width, tabs: await page.locator('.workspace-tabs > .ant-tabs-nav [role="tab"]').allTextContents() })
      }
      return { applicationId: appId, tabs }
    })
    await check('05 新版任务详情与只读请求边界', async () => {
      if (taskId) {
        await goto(`/nocode-app/task-center?taskId=${encodeURIComponent(taskId)}`)
        await expect(page.locator('.task-hierarchy-drawer:visible')).toBeVisible()
        await expect(page.getByRole('navigation', { name: '任务位置', exact: true })).toBeVisible()
        await capture('current-task-detail')
        await closeDrawer()
      }
      assert.deepEqual(report.errors, [])
      assert.deepEqual(report.apiFailures, [])
      assert.deepEqual(report.blockedWrites, [])
      return {
        taskId: taskId || null,
        detailVerified: !!taskId,
        ...(taskId ? {} : { detailSkipped: '现有可读任务为空，未创建验收任务' }),
        writes: 0,
        createdFixtures: 0
      }
    })
    console.log(JSON.stringify({ output, passed: report.checks.length, writes: 0 }))
  } catch (error) {
    report.failure = error.message
    process.exitCode = 1
    console.error(JSON.stringify({ output, error: error.message }))
  } finally {
    await browser?.close()
    await persist()
  }
}

if (process.env.TASK_LEGACY_RETIREMENT_RUN === '1') await run()
else console.log(JSON.stringify({ mode: 'prepare-only', login: false, writes: 0 }))
