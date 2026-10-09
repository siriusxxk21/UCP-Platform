import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect as playwrightExpect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 用户长期体验数据只读验收：不调用准备/清理工具，不提交表单，不创建任务、模板或反馈规则。
const root = resolve(process.env.CONSTRUCTION_OUTPUT || '.work/construction-experience')
const output = resolve(root, 'ui')
const origin = process.env.CONSTRUCTION_URL || 'http://127.0.0.1:5173'
const ac = new FormAcceptance(process.env.CONSTRUCTION_API || 'http://127.0.0.1:8080/api', output)
const expect = playwrightExpect.configure({ timeout: 20000 })
const checks = [],
  screenshots = [],
  pageErrors = [],
  apiErrors = [],
  blockedWrites = []
const labels = { project: '项目台账', content: '施工内容', log: '施工日志', cost: '费用登记', inspection: '验收记录' }
let manifest, app, browser, page, before, after, failure
const application = () => page.locator('.application-runtime')
const workbench = () =>
  page
    .locator('.ant-drawer-content:visible')
    .filter({ has: page.getByRole('heading', { name: '项目工作台', exact: true }) })
const launch = () =>
  page.locator('.ant-drawer-content:visible').filter({ has: page.getByPlaceholder('填写可执行的任务名称') })
const field = (surface, label) =>
  surface.locator('.ant-form-item').filter({
    has: page.locator('label').filter({ hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) })
  })
const rows = scope => scope.locator('tr[data-row-key]')
const rowName = (key, row) => String(row.values[manifest.objects[key].fields.name])
const taskQuery = { scope: 'VISIBLE', tab: 'ALL', pageNo: 1, pageSize: 100 }

async function snapshot() {
  const records = {}
  for (const [key, object] of Object.entries(manifest.objects)) {
    const result = await ac.api('/nocode/runtime/page', {
      applicationId: manifest.applicationId,
      objectId: object.id,
      pageNo: 1,
      pageSize: 100,
      descending: false
    })
    assert.ok(result.total <= 100, '体验验收最多读取每对象 100 行；超出时请扩展分页而非忽略数据')
    records[key] = result.list.sort((a, b) => String(a.id).localeCompare(String(b.id)))
  }
  const tasks = await ac.api('/nocode/tasks/page-tasks', {
    applicationId: manifest.applicationId,
    pageId: 'application_tasks',
    nodeId: 'application_tasks_list',
    query: taskQuery
  })
  return { records, tasks }
}
async function check(name, run) {
  const detail = await run()
  checks.push({ name, passed: true, detail })
  console.log('PASS ' + name)
}
async function screenshot(name) {
  const path = resolve(output, name + '.png')
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  screenshots.push(path)
}
async function navigate(menu, label) {
  await page.getByRole('navigation', { name: '应用内导航' }).getByRole('button', { name: label, exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`menu=${menu}(?:&|$)`))
  await expect(application().locator('.ant-alert-error')).toHaveCount(0)
}
async function closeSurface(surface) {
  await surface.locator('.ant-drawer-close').click()
  await finishClosing(surface)
}
async function finishClosing(surface) {
  const discard = page.getByRole('button', { name: '放弃修改', exact: true })
  await expect.poll(async () => !(await surface.count()) || (await discard.isVisible())).toBe(true)
  if (await discard.isVisible()) await discard.click()
  await expect(surface).toHaveCount(0)
}
async function openProject(tag) {
  const id = manifest.seeded.project[tag]
  const row = rows(application()).filter({
    hasText: rowName(
      'project',
      before.records.project.find(item => item.id === id)
    )
  })
  await row.getByRole('button').filter({ hasText: '查看' }).click()
  await expect(workbench()).toBeVisible()
  return workbench()
}
async function cancelTask(surface, projectName) {
  await surface.getByRole('button').filter({ hasText: '发起任务' }).click()
  await expect(launch()).toBeVisible()
  await expect(field(launch(), '关联应用')).toContainText(app.application.name)
  if (projectName) await expect(field(launch(), '关联业务记录')).toContainText(projectName)
  else await expect(field(launch(), '关联业务记录')).toHaveCount(0)
  await expect(launch()).toContainText('任务基本信息')
  await expect(launch()).toContainText('业务关联')
  await expect(launch()).toContainText('过程反馈')
  await launch().getByPlaceholder('填写可执行的任务名称').fill('只读界面验收，取消后不保存')
  await screenshot(
    projectName ? `task-cancel-project-${projectName.includes('3') ? '3' : '5'}` : 'task-cancel-application'
  )
  await launch().locator('.ant-drawer-close').click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(launch()).toHaveCount(0)
}
async function openBrowser() {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1512, height: 1050 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => pageErrors.push(error.message))
  page.on('response', async response => {
    if (!response.url().includes('/api/nocode/')) return
    try {
      const value = await response.json()
      if (value.code != null && value.code !== 0)
        apiErrors.push({ url: new URL(response.url()).pathname, code: value.code, message: value.msg })
    } catch {
      /* 非 JSON 响应不属于应用错误结果。 */
    }
  })
  // 在浏览器边界拒绝业务写请求，即使页面意外提交也不能碰用户的体验数据。
  const readPosts = new Set([
    '/nocode/runtime/page',
    '/nocode/runtime/selection',
    '/nocode/runtime/report',
    '/nocode/runtime/form-fill',
    '/nocode/runtime/field-rules/evaluate',
    '/nocode/tasks/page',
    '/nocode/tasks/page-tasks',
    '/nocode/runtime/related-form',
    '/nocode/runtime/view-children'
  ])
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api/, '')
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && !readPosts.has(path)) {
      blockedWrites.push({ path, method: request.method() })
      await route.abort('blockedbyclient')
    } else await route.continue()
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
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(flatten(info.menus || [])))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
}

try {
  await mkdir(output, { recursive: true })
  manifest = JSON.parse(await readFile(resolve(root, 'manifest.json'), 'utf8'))
  assert.equal(manifest.batch, 'CONSTRUCTION_GUIDE_20261002')
  assert.equal(manifest.complete, true)
  await ac.login()
  app = await ac.api(`/nocode/runtime/application?id=${manifest.applicationId}`)
  assert.equal(app.application.code, manifest.applicationCode)
  before = await snapshot()
  assert.equal(before.tasks.total, 0, '体验开始前不应已创建施工应用任务')
  for (const [key, ids] of Object.entries(manifest.seeded))
    for (const id of Object.values(ids))
      assert.ok(
        before.records[key].some(row => row.id === id),
        `保留的 ${key} 示例记录必须存在`
      )
  await openBrowser()
  await page.goto(`${origin}/nocode-app/runtime?id=${manifest.applicationId}&menu=overview_menu`)

  await check('施工应用 7 个菜单与真实概览统计', async () => {
    await expect(page.getByRole('navigation', { name: '应用内导航' }).getByRole('button')).toHaveCount(7)
    const metrics = {
      项目数量: before.records.project.length,
      施工内容数量: before.records.content.length,
      '登记费用（元）': before.records.cost.reduce(
        (sum, row) => sum + Number(row.values[manifest.objects.cost.fields.amount]),
        0
      ),
      验收记录数量: before.records.inspection.length
    }
    for (const [name, expected] of Object.entries(metrics)) {
      const cell = application().locator('.metric-cell').filter({ hasText: name })
      await expect(cell).toBeVisible()
      await expect.poll(async () => Number((await cell.locator('strong').innerText()).replace(/,/g, ''))).toBe(expected)
    }
    await screenshot('overview')
    return metrics
  })

  for (const [key, label] of Object.entries(labels)) {
    await check(`${label}菜单与业务表单控件`, async () => {
      await navigate(`${key}_menu`, label)
      await expect(rows(application())).toHaveCount(before.records[key].length)
      for (const row of before.records[key])
        await expect(rows(application()).filter({ hasText: rowName(key, row) })).toHaveCount(1)
      await application().getByRole('button').filter({ hasText: '新增' }).click()
      const editor = page.locator('.ant-drawer-content:visible').filter({
        has: page.getByRole('button', { name: `保存${key === 'project' ? '施工项目' : label}`, exact: true })
      })
      await expect(editor).toBeVisible()
      await expect(editor.getByRole('button', { name: /取\s*消/ })).toBeVisible()
      if (key !== 'project') {
        const selection = field(editor, '所属项目').getByRole('combobox')
        await field(editor, '所属项目').locator('.ant-select-selector').click()
        const options = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
        for (const project of before.records.project)
          await expect(options.filter({ hasText: rowName('project', project) })).toBeVisible()
        await screenshot(`form-${key}-project-options`)
        await selection.press('Escape')
        await expect(editor).toBeVisible()
      }
      const controls = {
        project: {
          date: ['计划开工日期', '计划完工日期'],
          number: ['项目预算（元）'],
          select: ['项目状态'],
          upload: ['项目附件']
        },
        content: { date: [], number: ['计划工程量'], select: ['专业工序', '计量单位'], upload: ['施工图纸或资料'] },
        log: { date: ['施工日期'], number: ['现场人数'], select: ['天气'], upload: ['现场照片'] },
        cost: { date: ['发生日期'], number: ['金额（元）'], select: ['费用类别', '支付状态'], upload: ['费用凭证'] },
        inspection: { date: ['验收日期'], number: [], select: ['验收结果'], upload: ['验收照片'] }
      }[key]
      for (const label of controls.date) await expect(field(editor, label).locator('.ant-picker input')).toBeVisible()
      // 运行表单用十进制文本输入避免大金额浮点损失，不是 a-input-number 的 spinbutton。
      for (const label of controls.number)
        await expect(field(editor, label).locator('input[inputmode="decimal"]')).toBeVisible()
      for (const label of controls.select) {
        const control = field(editor, label).getByRole('combobox')
        await field(editor, label).locator('.ant-select-selector').click()
        await expect(page.locator('.ant-select-dropdown:visible .ant-select-item-option').first()).toBeVisible()
        await control.press('Escape')
      }
      for (const label of controls.upload) {
        const input = field(editor, label).locator('input[type=file]')
        await expect(input).toHaveCount(1)
        if (label.includes('照片')) assert.match(await input.getAttribute('accept'), /image\/jpeg/)
      }
      await screenshot(`form-${key}`)
      await editor.getByRole('button', { name: /取\s*消/ }).click()
      await finishClosing(editor)
      return { rows: before.records[key].length, controls, submitted: false, uploaded: false }
    })
  }

  await navigate('project_menu', '项目台账')
  for (const tag of Object.keys(manifest.seeded.project)) {
    await check(`${tag} 项目工作台关联页签隔离与任务取消`, async () => {
      const projectId = manifest.seeded.project[tag]
      const projectRecord = before.records.project.find(row => row.id === projectId)
      const projectName = rowName('project', projectRecord)
      const surface = await openProject(tag)
      await expect(surface.getByRole('tab')).toHaveCount(7)
      await expect(surface).toContainText(projectName)
      await expect(
        surface
          .locator('.ant-tabs-tabpane-active')
          .getByText(String(projectRecord.values[manifest.objects.project.fields.project_no]), { exact: true })
      ).toBeVisible()
      await screenshot(`project-${tag}`)
      for (const key of ['content', 'log', 'cost', 'inspection']) {
        await surface.getByRole('tab', { name: labels[key], exact: true }).click()
        const pane = surface.locator('.ant-tabs-tabpane-active')
        const expected = before.records[key].filter(
          row => String(row.values[manifest.objects[key].fields.project_id]) === projectId
        )
        const excluded = before.records[key].filter(
          row => String(row.values[manifest.objects[key].fields.project_id]) !== projectId
        )
        await expect(rows(pane)).toHaveCount(expected.length)
        for (const row of expected) await expect(rows(pane).filter({ hasText: rowName(key, row) })).toHaveCount(1)
        for (const row of excluded) await expect(rows(pane).filter({ hasText: rowName(key, row) })).toHaveCount(0)
        await screenshot(`project-${tag}-${key}`)
      }
      await surface.getByRole('tab', { name: '项目附件', exact: true }).click()
      await expect(surface.locator('.ant-tabs-tabpane-active')).toContainText('暂无文件')
      await surface.getByRole('tab', { name: '项目任务', exact: true }).click()
      const pane = surface.locator('.ant-tabs-tabpane-active')
      await expect(pane.getByRole('button').filter({ hasText: '发起任务' })).toBeVisible()
      await expect(rows(pane)).toHaveCount(0)
      await cancelTask(pane, projectName)
      await closeSurface(surface)
      return { projectId, projectName, relatedTabs: 4, tasksCreated: 0 }
    })
  }

  await check('应用任务为空，发起自动关联应用，取消不落库', async () => {
    await navigate('tasks_menu', '应用任务')
    await expect(application().getByRole('button').filter({ hasText: '发起任务' })).toBeVisible()
    await expect(rows(application())).toHaveCount(0)
    await screenshot('application-tasks-empty')
    await cancelTask(application())
    await navigate('overview_menu', '施工概览')
    await expect(application().locator('.metric-cell')).toHaveCount(4)
  })
  await check('前后记录完全一致、任务仍为零，无业务写请求或页面异常', async () => {
    after = await snapshot()
    assert.deepEqual(after, before)
    assert.deepEqual(blockedWrites, [])
    assert.deepEqual(pageErrors, [])
    assert.deepEqual(apiErrors, [])
    return {
      objects: Object.keys(after.records).length,
      records: Object.values(after.records).reduce((sum, list) => sum + list.length, 0),
      tasks: after.tasks.total,
      businessWrites: 0
    }
  })
} catch (error) {
  failure = error.stack || error.message
  if (page) {
    await screenshot('failure').catch(() => {})
    await writeFile(
      resolve(output, 'failure-text.txt'),
      await page
        .locator('body')
        .innerText()
        .catch(() => ''),
      'utf8'
    )
  }
  console.error(failure)
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        applicationId: manifest?.applicationId,
        checks,
        failure,
        pageErrors,
        apiErrors,
        blockedWrites,
        screenshots,
        retention: '用户授权的长期体验配置；未提交业务表单、上传文件、创建任务、模板、反馈规则或执行清理'
      },
      null,
      2
    )
  )
  console.log(JSON.stringify({ output, passed: checks.length, failure: !!failure }, null, 2))
}
