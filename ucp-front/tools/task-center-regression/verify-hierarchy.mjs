import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 只创建本次前缀夹具；复用开发环境身份、API 和应用任务页，不修改已有任务。
const ac = new FormAcceptance(process.env.TASK_CENTER_API || 'http://127.0.0.1:8080/api')
if (process.env.TASK_HIERARCHY_PREFIX) ac.prefix = process.env.TASK_HIERARCHY_PREFIX
const origin = process.env.TASK_CENTER_URL || 'http://127.0.0.1:5173'
const output = resolve(process.env.TASK_CENTER_OUTPUT || '.work/task-hierarchy', ac.prefix)
ac.output = output
const checks = [],
  errors = [],
  fixtures = {},
  measurements = []
let browser, page, failure
const title = suffix => `${ac.prefix} ${suffix}`
const node = (suffix, parentId = null) => ({
  id: randomUUID(),
  parentId,
  title: title(suffix),
  description: '',
  assigneeId: null,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  schedule: { mode: 'T0', fixedStart: null, offsetDays: 0, durationDays: 1 },
  predecessorIds: [],
  binding: null,
  sharing: { mode: 'INDEPENDENT', sourceNodeId: null, writableFieldIds: [] }
})
const check = async (name, run) => {
  await run()
  checks.push(name)
  console.log('PASS ' + name)
}
const drawer = label =>
  page.locator('.ant-drawer-content:visible').filter({ has: page.getByText(label, { exact: true }) })
const row = id => page.locator(`tr[data-row-key="${id}"]`)
const editorRow = (editor, value) =>
  editor.locator('tr[data-row-key]').filter({ has: page.locator(`input[value="${value}"]`) })
async function screenshot(name) {
  await page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled' })
}
async function closeTop() {
  await page.locator('.ant-drawer-content:visible .ant-drawer-close').last().click()
}
async function assertIndent(editor, names, name) {
  await expect
    .poll(async () => {
      const box = await editor.boundingBox()
      return !!box && box.x >= 0 && box.x + box.width <= page.viewportSize().width + 1
    })
    .toBe(true)
  for (const value of names) {
    const input = editor.locator(`input[value="${value}"]`)
    await expect(input).toBeVisible()
  }
  // 抽屉进入可视范围后同一帧量测，避免异步读取之间的动画影响坐标差。
  const boxes = await editor.evaluate(
    (element, values) =>
      values.map(value => {
        const input = [...element.querySelectorAll('input')].find(input => input.value === value)
        const { x, y, width, height } = input.getBoundingClientRect()
        return { x, y, width, height }
      }),
    names
  )
  for (let i = 1; i < boxes.length; i++) {
    assert.ok(Math.abs(boxes[i].x - boxes[i - 1].x - 24) < 2, `${name}: 第 ${i + 1} 层应缩进 24px`)
    assert.ok(boxes[i].width > 160, `${name}: 深层输入框仍可编辑`)
  }
  measurements.push({ name, boxes })
}
async function listSearch(value) {
  await page.getByPlaceholder('搜索任务名称').fill(value)
  await page.getByRole('button', { name: /查\s*询/ }).click()
}
async function expand(id, value) {
  await row(id)
    .getByRole('button', { name: `展开子任务：${value}`, exact: true })
    .click()
}

try {
  await mkdir(output, { recursive: true })
  await ac.login()
  const a = node('一级分工'),
    b = node('二级分工', a.id),
    c = node('三级分工', b.id),
    sibling = node('同级分工')
  let draft, app, created
  if (process.env.TASK_HIERARCHY_PREFIX) {
    Object.assign(fixtures, JSON.parse(await readFile(resolve(output, 'fixtures.json'), 'utf8')))
    draft = { id: fixtures.templateId }
    app = { application: { id: fixtures.applicationId } }
    created = await ac.api('/nocode/tasks/detail', { id: fixtures.rootId })
    assert.ok(created.task.title.startsWith(ac.prefix), '复用夹具必须匹配指定前缀')
  } else {
    draft = await ac.api('/nocode/task-templates/save', {
      id: null,
      expectedRevision: null,
      name: title('层级模板'),
      description: '父子任务层级验收夹具',
      nodes: [a, b, c, sibling]
    })
    fixtures.templateId = draft.id
    const object = await ac.object('hierarchy', '层级验收对象', [ac.field('name', 'TEXT', '名称')])
    fixtures.objects = ac.owned.objects
    app = await ac.api('/nocode/application/save', {
      id: null,
      expectedRevision: null,
      code: `${ac.prefix}_hierarchy`,
      name: title('层级应用'),
      description: '层级验收夹具',
      definition: {
        objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
        resources: [
          {
            id: 'page',
            kind: 'PAGE',
            code: 'page',
            name: '任务层级',
            config: {
              nodes: [
                {
                  id: 'tasks',
                  type: 'TASKS',
                  resourceId: null,
                  children: [],
                  text: '应用任务',
                  taskView: { columnKeys: ['title', 'owner', 'status', 'time'] }
                }
              ]
            }
          },
          { id: 'menu', kind: 'MENU', code: 'menu', name: '任务层级', config: { targetId: 'page' } }
        ]
      }
    })
    fixtures.applicationId = app.application.id
    ac.app = app
    await ac.share(object, ac.grant(object))
    await ac.api('/nocode/application/publish', {
      id: app.application.id,
      expectedRevision: app.application.revision,
      reason: '层级展示验收'
    })
    created = await ac.api('/nocode/tasks/create', {
      task: node('总任务'),
      nodes: [a, b, c, sibling],
      applicationId: app.application.id,
      requestKey: randomUUID()
    })
    fixtures.rootId = created.task.id
  }
  const find = name => created.nodes.find(n => n.title === name)
  const first = find(a.title),
    second = find(b.title),
    third = find(c.title)
  fixtures.nodes = created.nodes.map(n => ({ id: n.id, title: n.title, parentId: n.parentId }))
  await writeFile(resolve(output, 'fixtures.json'), JSON.stringify(fixtures, null, 2))
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  page = await browser.newPage({ viewport: { width: 1680, height: 1100 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
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

  await check('模板三级输入缩进、同级对齐、父子归属与折叠展开', async () => {
    await page.goto(origin + '/nocode-app/task-center/templates')
    await page.locator('.ant-form-item').filter({ hasText: '模板名称' }).locator('input').fill(title('层级模板'))
    await page.getByRole('button', { name: /查\s*询/ }).click()
    await row(draft.id).getByRole('button', { name: '编辑草稿', exact: true }).click()
    const editor = drawer('编辑任务模板草稿')
    await assertIndent(editor, [a.title, b.title, c.title], 'template')
    await expect(editorRow(editor, a.title)).toContainText('一级子任务')
    await expect(editorRow(editor, c.title)).toContainText(`上级：${b.title}`)
    const siblingBox = await editor.locator(`input[value="${sibling.title}"]`).boundingBox()
    const firstBox = await editor.locator(`input[value="${a.title}"]`).boundingBox()
    assert.equal(Math.round(siblingBox.x), Math.round(firstBox.x))
    await screenshot('template-hierarchy')
    await editor.getByRole('button', { name: '收起全部', exact: true }).click()
    await expect(editor.locator(`input[value="${c.title}"]`)).toBeHidden()
    await editor.getByRole('button', { name: '展开全部', exact: true }).click()
    await expect(editor.locator(`input[value="${c.title}"]`)).toBeVisible()
    await editorRow(editor, b.title)
      .getByRole('button', { name: /配\s*置/, exact: true })
      .click()
    const config = drawer('配置任务节点')
    await expect(config.locator('.task-node-editor__location')).toContainText(`上级：${a.title}`)
    const parentField = config
      .locator('.ant-form-item')
      .filter({ has: page.locator('label').filter({ hasText: /^直接上级任务$/ }) })
    await expect(parentField).toContainText(`一级子任务 1 · ${a.title}`)
    await screenshot('configuration-parent')
    await closeTop()
    await page.setViewportSize({ width: 1000, height: 900 })
    await assertIndent(editor, [a.title, b.title, c.title], 'template-narrow')
    await expect.poll(async () => (await editor.boundingBox()).x).toBeGreaterThanOrEqual(0)
    assert.ok(
      await editor.locator('.ant-table-content').evaluate(el => el.scrollWidth > el.clientWidth),
      '窄屏使用表内横向滚动'
    )
    await screenshot('template-narrow')
    await page.setViewportSize({ width: 1680, height: 1100 })
    await closeTop()
  })
  await check('发起编辑器连续拆分、当前新增项明确上级且输入同行', async () => {
    await page.goto(origin + '/nocode-app/task-center/manage')
    await page.getByRole('button', { name: '发起任务', exact: true }).click()
    const editor = drawer('发起任务')
    await editor.getByRole('button', { name: '添加任务节点', exact: true }).click()
    const inputs = editor.locator('.task-hierarchy__title input')
    await inputs.nth(0).fill(title('新一级'))
    await editorRow(editor, title('新一级')).getByRole('button', { name: '＋ 子任务', exact: true }).click()
    await inputs.nth(1).fill(title('新二级'))
    await editorRow(editor, title('新二级')).getByRole('button', { name: '＋ 子任务', exact: true }).click()
    await inputs.nth(2).fill(title('新三级'))
    await assertIndent(editor, [title('新一级'), title('新二级'), title('新三级')], 'launch')
    await expect(editorRow(editor, title('新三级'))).toContainText(`上级：${title('新二级')}`)
    await screenshot('launch-hierarchy')
    await closeTop()
    await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  })
  await check('管理列表展开三层、行内新增归属、筛选孤儿子任务身份', async () => {
    await listSearch(title('总任务'))
    await expand(created.task.id, created.task.title)
    await expand(first.id, first.title)
    await expand(second.id, second.title)
    await expect(row(third.id).locator('.task-hierarchy')).toHaveAttribute('data-depth', '3')
    await expect(row(third.id)).toContainText(`上级：${second.title}`)
    await expect(row(created.task.id).locator('.task-hierarchy__label')).toHaveText('总任务')
    await screenshot('manage-hierarchy')
    await row(second.id).getByRole('button', { name: '＋ 子任务', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '子任务名称', exact: true })).toBeFocused()
    await expect(row('inline:' + second.id)).toContainText(`上级：${second.title}`)
    await row('inline:' + second.id)
      .getByRole('button', { name: /取\s*消/ })
      .click()
    await listSearch(third.title)
    await expect(row(third.id).locator('.task-hierarchy__label')).toHaveText('子任务')
    await expect(row(third.id)).toContainText('上级任务未在当前视图中')
    await expect(row(created.task.id)).toHaveCount(0)
    await screenshot('filtered-subtask')
  })
  await check('详情任务树、总任务与上级跳转、直接子任务及关系图', async () => {
    await row(third.id).getByRole('button', { name: third.title, exact: true }).click()
    await expect(page.locator('.task-context')).toContainText(second.title)
    await expect(page.locator('.task-context [aria-current="true"]')).toContainText(third.title)
    const parentInfo = page
      .locator('.ant-descriptions-item-content')
      .filter({ has: page.getByRole('button', { name: second.title, exact: true }) })
    await parentInfo.getByRole('button', { name: second.title, exact: true }).click()
    await expect(page.getByText('当前任务的直接子任务（1）', { exact: true })).toBeVisible()
    await screenshot('detail-hierarchy')
    await page.getByRole('tab', { name: '依赖关系图', exact: true }).click()
    await expect(page.locator('.task-dag')).toContainText('总任务')
    await expect(page.getByText('实线箭头表示前置完成依赖', { exact: false })).toBeVisible()
    await screenshot('dag-hierarchy')
    await page.getByRole('button', { name: '调整当前实例', exact: true }).click()
    const editor = drawer('调整当前运行实例')
    await assertIndent(editor, [created.task.title, a.title, b.title, c.title], 'adjustment')
    await expect(editorRow(editor, created.task.title)).toContainText('总任务')
    await screenshot('adjustment-hierarchy')
    await closeTop()
    await closeTop()
  })
  await check('我的任务与应用内任务页复用相同的父子层级', async () => {
    await page.goto(origin + '/nocode-app/task-center')
    await page.getByRole('tab', { name: '待处理任务池', exact: true }).click()
    await listSearch(title('总任务'))
    await expand(created.task.id, created.task.title)
    await expect(row(first.id)).toContainText(`上级：${created.task.title}`)
    await screenshot('my-task-hierarchy')
    await page.goto(origin + `/nocode-app/runtime?id=${app.application.id}&menu=menu`)
    await expand(created.task.id, created.task.title)
    await expand(first.id, first.title)
    await expect(row(second.id).locator('.task-hierarchy')).toHaveAttribute('data-depth', '2')
    await expect(row(second.id)).toContainText(`上级：${first.title}`)
    await screenshot('application-hierarchy')
  })
  assert.deepEqual(errors, [], '无浏览器运行错误')
} catch (error) {
  failure = error
  console.error(error.message)
  if (page) await screenshot('failure').catch(() => {})
} finally {
  await writeFile(
    resolve(output, 'result.json'),
    JSON.stringify(
      {
        time: new Date().toISOString(),
        prefix: ac.prefix,
        checks,
        errors,
        measurements,
        fixtures,
        failure: failure?.stack
      },
      null,
      2
    )
  )
  await browser?.close()
  console.log('REPORT ' + output)
}
if (failure) process.exitCode = 1
