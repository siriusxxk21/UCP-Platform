import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
import { session, output, origin, settle, geometry, save } from './session.mjs'

// 每页仅一条独立夹具，所有 UI 操作在真实手机视口内执行，失败也按已登记 ID 清理。
const { ac, browser, context } = await session()
const page = await context.newPage()
page.setDefaultTimeout(8000)
const prefix = `mob${Date.now().toString(36)}`
const results = []
const rx = text => new RegExp(text.split('').join('\\s*'))
const button = (scope, name) => scope.getByRole('button', { name: rx(name) }).first()
const content = () => page.locator('.content-wrapper-dual')
const dialog = () => page.locator('.ant-modal:visible, .ant-drawer-content:visible').last()
const field = (scope, label) =>
  scope.locator('.ant-form-item').filter({ has: page.locator('label').filter({ hasText: new RegExp(`^${label}$`) }) })
async function input(scope, label, value) {
  await field(scope, label).locator('input:not([type=radio]):not([type=checkbox]),textarea').first().fill(value)
}
async function select(scope, label, name) {
  await field(scope, label).locator('.ant-select-selector').click()
  await page.locator('.ant-select-dropdown:visible').getByText(name, { exact: true }).last().click()
}
async function reply(path, method, action) {
  const promise = page.waitForResponse(
    r => new URL(r.url()).pathname === `/api${path}` && r.request().method() === method,
    { timeout: 12000 }
  )
  promise.catch(() => {})
  await action()
  const response = await promise
  const body = await response.json()
  assert.equal(body.code, 0, `${path}: ${body.msg}`)
  return body.data
}
async function fit(stage) {
  const box = await dialog().boundingBox()
  const viewport = page.viewportSize()
  assert.ok(box && box.x >= -1 && box.x + box.width <= viewport.width + 1, `${stage}: 弹层超出视口`)
  await page.screenshot({ path: resolve(output, `${stage}.png`) })
}
async function confirmDelete() {
  for (let attempt = 0; attempt < 3; attempt++) {
    await page.waitForTimeout(600)
    const visible = page.locator('.ant-popover:visible, .ant-modal-confirm:visible').last()
    if (!(await visible.count())) return
    const ok = visible.getByRole('button', { name: /确\s*定|O\s*K|删\s*除/ }).last()
    if (!(await ok.count())) return
    await ok.click()
  }
}
const specs = [
  {
    key: 'role',
    path: '/system/role',
    create: '新增',
    name: '角色名称',
    fields: { 角色编码: '$code' },
    api: '/system/role',
    filter: '请输入角色名称'
  },
  {
    key: 'organization',
    path: '/system/organization',
    create: '新增',
    name: '组织名称',
    fields: { 组织编码: '$code' },
    createApi: '/system/organization',
    updateApi: id => `/system/organization/${id}`,
    deleteApi: id => `/system/organization/${id}`,
    filter: '请输入组织名称'
  },
  {
    key: 'user',
    path: '/system/user',
    create: '新增',
    name: '昵称',
    fields: { 用户名: '$code', 密码: '$password' },
    api: '/system/user',
    filter: '用户名/昵称/拼音'
  },
  {
    key: 'config',
    path: '/system/config',
    create: '新增参数',
    name: '参数名称',
    fields: { 参数分类: '手机适配验收', 参数键名: '$code', 参数键值: 'mobile-test-only' },
    api: '/infra/config',
    filter: '请输入参数名称'
  },
  {
    key: 'dict',
    path: '/system/dict',
    create: '新增',
    name: '字典名称',
    fields: { 字典编码: '$code' },
    api: '/system/dict-type',
    deleteApi: id => `/system/dict-type/delete-list?ids=${id}`,
    filter: '请输入字典名称'
  },
  {
    key: 'category',
    path: '/bpm/category',
    create: '新建流程分类',
    name: '分类名',
    fields: { 分类标志: '$code' },
    api: '/bpm/category',
    filter: '请输入分类名'
  },
  {
    key: 'menu',
    path: '/system/menu',
    create: '新增',
    name: '菜单名称',
    fields: {},
    api: '/system/menu',
    filter: '请输入菜单名称',
    prepare: async d => {
      await field(d, '菜单类型').getByText('目录', { exact: true }).click()
      await d.getByText('隐藏', { exact: true }).click()
      await d.getByText('禁用', { exact: true }).click()
      await field(d, '路由地址').locator('input').fill('/mobile-acceptance-unlinked')
    }
  },
  {
    key: 'fileconfig',
    path: '/system/file-config',
    create: '新增配置',
    name: '配置名',
    fields: {},
    api: '/infra/file-config',
    filter: '请输入配置名',
    prepare: async d => {
      await select(d, '存储器', '数据库')
      await input(d, '自定义域名', 'http://127.0.0.1:8080')
    }
  },
  {
    key: 'department',
    path: '/system/department',
    create: '新增',
    name: '部门名称',
    fields: {},
    createApi: '/system/dept',
    updateApi: id => `/system/dept/${id}`,
    deleteApi: id => `/system/dept/${id}`,
    filter: '请输入部门名称',
    beforeOpen: async () => {
      await button(content(), '筛选组织架构').click()
      await content().locator('.ant-tree-title').first().click()
      await button(content(), '收起组织架构').click()
    }
  },
  { key: 'space', path: '/drive/space', create: '新建空间', name: '空间名称', fields: {}, api: '/drive/space' },
  {
    key: 'bpmform',
    path: '/bpm/form',
    create: '新建流程表单',
    name: '表单名称',
    fields: {},
    api: '/bpm/form',
    filter: '请输入表单名称',
    openForm: async () => {
      await page.locator('.mobile-designer-panels').getByText('组件', { exact: true }).tap()
      await page.locator('._fc-l-item').getByText('输入框', { exact: true }).tap()
      await page.locator('.mobile-designer-panels').getByText('画布', { exact: true }).tap()
      await page.locator('._fc-m').waitFor({ state: 'visible' })
      assert.ok((await page.locator('._fc-m input').count()) > 0)
      await page.screenshot({ path: resolve(output, 'bpmform-designer.png') })
      await page.locator('.mobile-designer-panels').getByText('属性', { exact: true }).tap()
      await page.locator('._fc-r').waitFor({ state: 'visible' })
      await button(content(), '保存').click()
      await settle(page)
    }
  },
  {
    key: 'messageTemplate',
    path: '/message/template',
    create: '消息模板',
    name: '模板名称',
    fields: { 模板编码: '$code', 标题模板: '手机验收标题' },
    createApi: '/msg/template/add',
    updateApi: () => '/msg/template/edit',
    updateMethod: 'POST',
    deleteApi: id => `/msg/template/delete?id=${id}`,
    filter: '模板编码/名称',
    searchEnter: true
  }
]
const selected = process.env.MOBILE_CASES?.split(',')
try {
  for (const spec of specs.filter(s => !selected || selected.includes(s.key))) {
    const name = `${prefix}_${spec.key}`
    const result = { page: spec.path, name, operations: [] }
    let id
    let removed = false
    const deleteApi = value => spec.deleteApi?.(value) || `${spec.api}/delete?id=${value}`
    try {
      await page.goto(origin + spec.path)
      await settle(page)
      await spec.beforeOpen?.()
      await button(content(), spec.create).click()
      await settle(page)
      await spec.openForm?.()
      await input(dialog(), spec.name, name)
      for (const [label, value] of Object.entries(spec.fields))
        await input(
          dialog(),
          label,
          value === '$code'
            ? name.replaceAll('_', '')
            : value === '$password'
              ? `Mb!${randomBytes(12).toString('hex')}`
              : value
        )
      await spec.prepare?.(dialog())
      await fit(`${spec.key}-create`)
      id = await reply(spec.createApi || `${spec.api}/create`, 'POST', () =>
        button(dialog(), '保存')
          .count()
          .then(count => (count ? button(dialog(), '保存').click() : button(dialog(), '确定').click()))
      )
      assert.ok(id, '新增返回 ID')
      if (spec.key === 'messageTemplate') {
        const templates = await ac.api('/msg/template/list')
        id = templates.find(item => item.name === name)?.id
        assert.ok(id, '创建模板后读取真实 ID，供失败时清理')
      }
      if (spec.key === 'bpmform') assert.equal((await ac.api('/bpm/form/get?id=' + id)).fields.length, 1)
      result.id = id
      result.operations.push('create')
      await save('crud.json', { prefix, results: [...results, result] })
      await settle(page)
      if (spec.filter) {
        await content().getByPlaceholder(spec.filter, { exact: true }).fill(name)
        if (spec.searchEnter) await content().getByPlaceholder(spec.filter, { exact: true }).press('Enter')
        else await button(content(), '查询').click()
        await settle(page)
      }
      let row = page.locator('tr.ant-table-row').filter({ hasText: name }).first()
      await row.waitFor({ state: 'visible' })
      result.operations.push('read')
      await button(row, '编辑').click()
      await settle(page)
      await spec.openForm?.()
      const edited = `${name}_改`
      await input(dialog(), spec.name, edited)
      await fit(`${spec.key}-edit`)
      await reply(spec.updateApi?.(id) || `${spec.api}/update`, spec.updateMethod || 'PUT', () =>
        button(dialog(), '保存')
          .count()
          .then(count => (count ? button(dialog(), '保存').click() : button(dialog(), '确定').click()))
      )
      result.operations.push('update')
      if (spec.key === 'bpmform') assert.equal((await ac.api('/bpm/form/get?id=' + id)).fields.length, 2)
      await settle(page)
      row = page.locator('tr.ant-table-row').filter({ hasText: edited }).first()
      await row.waitFor({ state: 'visible' })
      await reply(deleteApi(id).split('?')[0], 'DELETE', async () => {
        await button(row, '删除').click()
        await confirmDelete()
      })
      removed = true
      result.operations.push('delete')
      await settle(page)
      assert.equal(await page.locator('tr.ant-table-row').filter({ hasText: name }).count(), 0)
      result.geometry = await geometry(page)
      result.passed = true
    } catch (error) {
      result.error = error.message
      result.passed = false
      await page.screenshot({ path: resolve(output, `${spec.key}-failure.png`) })
      result.visibleText = (await page.locator('body').innerText()).slice(-2800)
    } finally {
      if (id && !removed) {
        try {
          await ac.api(deleteApi(id), undefined, undefined, 'DELETE')
          result.cleaned = true
        } catch (error) {
          result.cleanupError = error.message
        }
      } else result.cleaned = removed
      results.push(result)
      await save('crud.json', { prefix, results })
      console.log(spec.key, result.passed ? 'PASS C/R/U/D' : result.error, 'cleaned', result.cleaned)
    }
  }
} finally {
  await browser.close()
}
if (results.some(r => !r.passed || !r.cleaned)) process.exitCode = 1
