import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { prepareBusinessDemo, output } from './business-demo.mjs'

// 使用真实发布配置、真实员工权限及浏览器；只新增明确标识的示例记录，不改动原业务数据。
const { ac, state } = await prepareBusinessDemo()
const out = resolve(output, ac.prefix)
await mkdir(out, { recursive: true })
ac.output = out
const origin = process.env.TASK_ENTRY_URL || 'http://127.0.0.1:5173'
const root = '/nocode/task-entry'
const { finance, engineering, account, company } = state.objects
const checks = [],
  errors = [],
  records = {}
let browser, activePage, failure
const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const locator = entryId => ({ applicationId: state.applicationId, entryId, version: state.version })
const api = (path, body, user) => ac.api(root + path, body, user ? ac.tokens[user] : undefined)
const save = async (entryId, obj, values, user, row) => {
  const result = await api(
    '/save',
    { entry: locator(entryId), record: { ...ac.saveBody(obj, values, row), requestKey: randomUUID() } },
    user
  )
  return result.record
}
const list = (entryId, obj, user) => api('/page', { entry: locator(entryId), query: ac.query(obj) }, user)
const shot = (page, name) =>
  page.screenshot({ path: resolve(out, name + '.png'), fullPage: true, animations: 'disabled' })
const check = async (name, work) => {
  await work()
  checks.push(name)
  console.log('PASS ' + name)
}
const readonly = obj => ac.grant(obj, { actions: ['READ'], writeFields: [] })
const financeValues = summary => ({
  summary,
  transaction_date: date,
  direction: 'EXPENSE',
  amount: '100',
  currency: 'CNY',
  account_id: state.accountRecordId,
  notes: '任务中心验收示例，非真实收支'
})
async function pageFor(user) {
  const page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(18000)
  page.on('pageerror', error => errors.push(error.message))
  const token = ac.tokens[user] || ac.tokens.admin
  const info = await ac.api('/system/auth/get-permission-info', undefined, token)
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(info.menus || []))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token, info }
  )
  activePage = page
  return page
}
const field = (page, modal, name) =>
  modal
    .locator('.os-form-surface .ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + name + '$') }) })

try {
  const financialUser = await ac.user('finance')
  const engineeringUser = await ac.user('engineering')
  const peer = await ac.user('financepeer')
  records.employees = { financialUser, engineeringUser, peer }
  const roleUsers = await ac.api('/system/permission/list-role-users?roleId=' + state.employeeRoleId)
  await ac.api('/system/permission/assign-role-users', {
    roleId: state.employeeRoleId,
    userIds: [...new Set([...roleUsers.map(String), financialUser, engineeringUser, peer])]
  })
  for (const [id, users, grants] of [
    ['finance', [financialUser, peer], [state.limits.finance, readonly(company), readonly(account)]],
    ['engineering', [engineeringUser], [state.limits.engineering]]
  ]) {
    const policy = await api(`/policy?applicationId=${state.applicationId}&entryId=${id}`)
    await api('/policy', {
      applicationId: state.applicationId,
      entryId: id,
      expectedRevision: policy.revision,
      enabled: true,
      members: [...policy.members, ...users.map(user => ac.member(user, grants))]
    })
  }

  await check('员工按岗位只发现自己的入口，普通应用 API 不能绕过入口授权', async () => {
    for (const [user, id, obj] of [
      [financialUser, 'finance', finance],
      [engineeringUser, 'engineering', engineering]
    ]) {
      const cards = await api('/mine', undefined, user)
      assert.deepEqual(
        cards.filter(c => c.applicationId === state.applicationId).map(c => c.entryId),
        [id]
      )
      await ac.denied(
        `/nocode/runtime/model?applicationId=${state.applicationId}&objectId=${obj.objectId}`,
        undefined,
        ac.tokens[user]
      )
      await ac.denied(root + '/context', locator(id === 'finance' ? 'engineering' : 'finance'), ac.tokens[user])
    }
  })

  await check('公司与账户沿用共享关系，候选账户只返回明确标识的示例账户', async () => {
    const selection = await api(
      '/selection',
      {
        entry: locator('finance'),
        query: {
          applicationId: state.applicationId,
          objectId: finance.objectId,
          fieldId: finance.ids.account_id,
          creating: true,
          formId: 'finance_form',
          formValues: {},
          pageNo: 1,
          pageSize: 20,
          selected: []
        }
      },
      financialUser
    )
    assert.deepEqual(
      selection.options.map(option => String(option.value)),
      [state.accountRecordId]
    )
  })

  browser = await chromium.launch({ headless: true, channel: process.env.TASK_ENTRY_BROWSER || 'chrome' })
  const financePage = await pageFor(financialUser)
  const modal = financePage.locator('.task-host .ant-modal-content')
  const financeTitle = `【任务示例】材料采购 ${ac.prefix}`
  await check('财务员工通过任务弹窗填写并保存收支，列表自动刷新', async () => {
    await financePage.goto(origin + '/nocode-app/task-center')
    await expect(financePage.locator('.task-card')).toHaveCount(1)
    await shot(financePage, '01-财务员工入口')
    await financePage.locator('.task-card__main').filter({ hasText: '财务收支登记' }).click()
    await modal.getByRole('button', { name: /新增$/ }).click()
    await field(financePage, modal, '交易摘要').locator('input').fill(financeTitle)
    const day = field(financePage, modal, '交易日期').locator('input')
    await day.click()
    await financePage.locator(`.ant-picker-dropdown:visible td[title="${date}"]`).click()
    await field(financePage, modal, '收支方向').locator('.ant-select-selector').click()
    await financePage.locator('.ant-select-item-option').filter({ hasText: '支出' }).click()
    await field(financePage, modal, '交易金额').locator('input').fill('100')
    await field(financePage, modal, '币种').locator('input').fill('CNY')
    await field(financePage, modal, '所属账号').locator('.ant-select-selector').click()
    await financePage.locator('.ant-select-item-option').filter({ hasText: '【任务中心示例】收支演示账户' }).click()
    await field(financePage, modal, '交易对方').locator('input').fill('示例材料商（非真实）')
    await field(financePage, modal, '备注').locator('textarea').fill('任务中心验收示例，非真实收支')
    await shot(financePage, '02-财务填报表单')
    await modal.getByRole('button', { name: /保存收支记录$/ }).click()
    await expect(modal.getByText(financeTitle, { exact: true })).toBeVisible()
    assert.equal(await financePage.locator('.ant-modal-content:visible').count(), 1)
    records.finance = (await list('finance', finance, financialUser)).list.find(
      r => r.values[finance.ids.summary] === financeTitle
    )
    assert.ok(records.finance)
  })

  await check('财务员工修改金额，读取结果与列表同步', async () => {
    await modal.getByRole('button', { name: /编辑$/ }).click()
    await field(financePage, modal, '交易金额').locator('input').fill('150')
    await modal.getByRole('button', { name: /保存收支记录$/ }).click()
    await expect(modal.getByText(financeTitle, { exact: true })).toBeVisible()
    records.finance = (await list('finance', finance, financialUser)).list.find(r => r.id === records.finance.id)
    assert.equal(Number(records.finance.values[finance.ids.amount]), 150)
    await shot(financePage, '03-财务办理结果')
  })

  await check('同岗位只看本人记录；不能伪造写入未授权字段或借入口修改账户', async () => {
    const peerRow = await save('finance', finance, financeValues(`【任务示例】同岗位隔离 ${ac.prefix}`), peer)
    records.peer = peerRow
    assert.ok(!(await list('finance', finance, financialUser)).list.some(row => row.id === peerRow.id))
    assert.ok(!(await list('finance', finance, peer)).list.some(row => row.id === records.finance.id))
    await ac.denied(root + '/get', { entry: locator('finance'), recordId: peerRow.id }, ac.tokens[financialUser])
    await ac.denied(
      root + '/save',
      {
        entry: locator('finance'),
        record: {
          ...ac.saveBody(finance, { ...financeValues(financeTitle), is_locked: true }, records.finance),
          requestKey: randomUUID()
        }
      },
      ac.tokens[financialUser]
    )
    const switched = await ac.request(
      root + '/save',
      {
        entry: locator('finance'),
        record: {
          ...ac.saveBody(account, { account_name: '不应修改' }, { id: state.accountRecordId, revision: 1 }),
          requestKey: randomUUID()
        }
      },
      ac.tokens[financialUser]
    )
    assert.notEqual(switched.code, 0)
    assert.equal(switched.msg, '任务入口不能切换应用或业务对象')
  })

  await check('删除本次临时收支记录，列表移除且操作记录保留', async () => {
    records.deleted = await save(
      'finance',
      finance,
      financeValues(`【任务示例】撤销重复填报 ${ac.prefix}`),
      financialUser
    )
    await modal.locator('.ant-modal-close').click()
    await financePage.locator('.task-card__main').filter({ hasText: '财务收支登记' }).click()
    const row = modal.locator('tr').filter({ hasText: records.deleted.values[finance.ids.summary] })
    await row.getByRole('button', { name: /删除$/ }).click()
    await financePage
      .locator('.ant-popconfirm')
      .getByRole('button', { name: /确\s*定$/ })
      .click()
    await expect(row).toHaveCount(0)
    assert.ok(!(await list('finance', finance, financialUser)).list.some(r => r.id === records.deleted.id))
  })

  const engineeringPage = await pageFor(engineeringUser)
  const engModal = engineeringPage.locator('.task-host .ant-modal-content')
  const engineeringTitle = `【任务示例】研发办公区改造 ${ac.prefix}`
  await check('工程员工填写日报并修订进度，办理窗口不跳转到应用页面', async () => {
    await engineeringPage.goto(origin + '/nocode-app/task-center')
    await expect(engineeringPage.locator('.task-card')).toHaveCount(1)
    await engineeringPage.locator('.task-card__main').filter({ hasText: '工程每日进度' }).click()
    await engModal.getByRole('button', { name: /新增$/ }).click()
    await field(engineeringPage, engModal, '项目名称').locator('input').fill(engineeringTitle)
    const day = field(engineeringPage, engModal, '填报日期').locator('input')
    await day.click()
    await engineeringPage.locator(`.ant-picker-dropdown:visible td[title="${date}"]`).click()
    await field(engineeringPage, engModal, '累计完成百分比').locator('input').fill('20')
    await field(engineeringPage, engModal, '当日到岗人数').locator('input').fill('6')
    await field(engineeringPage, engModal, '今日完成工作')
      .locator('textarea')
      .fill('示例：完成基础施工，进入设备安装准备')
    await field(engineeringPage, engModal, '问题与风险').locator('textarea').fill('示例：部分材料待确认交期')
    await field(engineeringPage, engModal, '下一步计划').locator('textarea').fill('示例：核对材料清单并开始安装')
    await shot(engineeringPage, '04-工程进度填报')
    await engModal.getByRole('button', { name: /保存工程进度$/ }).click()
    await expect(engModal.getByText(engineeringTitle, { exact: true })).toBeVisible()
    await engModal.getByRole('button', { name: /编辑$/ }).click()
    await field(engineeringPage, engModal, '累计完成百分比').locator('input').fill('40')
    await engModal.getByRole('button', { name: /保存工程进度$/ }).click()
    await expect(engModal.getByText(engineeringTitle, { exact: true })).toBeVisible()
    records.engineering = (await list('engineering', engineering, engineeringUser)).list.find(
      r => r.values[engineering.ids.name] === engineeringTitle
    )
    assert.equal(Number(records.engineering.values[engineering.ids.progress]), 40)
    assert.ok(engineeringPage.url().includes('/nocode-app/task-center'))
  })

  await check('后端执行进度范围校验，伪造 101% 不落库', async () => {
    const invalid = await ac.request(
      root + '/save',
      {
        entry: locator('engineering'),
        record: {
          ...ac.saveBody(
            engineering,
            { name: engineeringTitle, report_date: date, progress: 101, work_content: '不应保存' },
            records.engineering
          ),
          requestKey: randomUUID()
        }
      },
      ac.tokens[engineeringUser]
    )
    assert.notEqual(invalid.code, 0)
    assert.match(invalid.msg, /100|最大|范围|超过/)
    assert.equal(
      Number(
        (await list('engineering', engineering, engineeringUser)).list.find(r => r.id === records.engineering.id)
          .values[engineering.ids.progress]
      ),
      40
    )
  })

  const today = new Date(date + 'T00:00:00+08:00')
  await check('老板历史保留新增、修改、删除，准确记录办理人和任务入口来源', async () => {
    const query = { start: today.toISOString(), applicationId: state.applicationId }
    const summary = await ac.api('/nocode/record-history/query', query)
    for (const [key, obj, id, operations] of [
      ['finance', finance, 'finance', ['CREATE', 'UPDATE']],
      ['engineering', engineering, 'engineering', ['CREATE', 'UPDATE']],
      ['deleted', finance, 'finance', ['CREATE', 'DELETE']]
    ]) {
      const detail = await ac.api('/nocode/record-history/detail', {
        query: { ...query, end: summary.end },
        visibility: summary.visibility,
        objectId: obj.objectId,
        recordId: records[key].id
      })
      const events = detail.row.changes.filter(
        c => c.source?.applicationId === state.applicationId && c.source?.entryId === id
      )
      assert.deepEqual(events.map(c => c.operation).sort(), operations.sort())
      assert.ok(events.every(e => e.source.kind === 'TASK_ENTRY'))
      if (key === 'finance') {
        const updated = events.find(e => e.operation === 'UPDATE')
        assert.equal(Number(updated.before[finance.ids.amount]), 100)
        assert.equal(Number(updated.after[finance.ids.amount]), 150)
        assert.equal(updated.employeeId, financialUser)
      }
    }
  })

  const bossPage = await pageFor('admin')
  await check('老板浏览器默认业务动态，可下钻修改过程并切换人员动态', async () => {
    await bossPage.goto(origin + '/nocode-app/record-history')
    await expect(bossPage.getByRole('radio', { name: '业务动态', exact: true })).toBeChecked()
    await bossPage.getByRole('button', { name: '工程进度日报（示例） · 查看变化', exact: true }).click()
    await bossPage.getByRole('button', { name: `记录 ${records.engineering.id} · 查看变化详情`, exact: true }).click()
    const drawer = bossPage.locator('.history-drawer')
    await drawer.getByRole('tab', { name: '修改过程 2', exact: true }).click()
    await expect(drawer.getByText('任务中心 · 工程每日进度', { exact: true })).toHaveCount(2)
    await expect(
      drawer
        .locator('.history-event')
        .filter({ hasText: '修改' })
        .getByText(/^20(?:\.0+)?$/)
    ).toBeVisible()
    await expect(
      drawer
        .locator('.history-event')
        .filter({ hasText: '修改' })
        .getByText(/^40(?:\.0+)?$/)
    ).toBeVisible()
    await shot(bossPage, '05-老板查看修改过程')
    await drawer.getByRole('button', { name: 'Close', exact: true }).click()
    await bossPage.getByRole('button', { name: /返回业务动态/ }).click()
    await bossPage.getByText('人员动态', { exact: true }).click()
    await expect(bossPage.getByText('表单验收engineering', { exact: true }).first()).toBeVisible()
    await shot(bossPage, '06-老板按人员查看')
  })
  assert.deepEqual(errors, [], '浏览器无未捕获异常')
} catch (error) {
  failure = error
  if (activePage) {
    await shot(activePage, 'failed').catch(() => {})
    await writeFile(resolve(out, 'failed-dom.html'), await activePage.content()).catch(() => {})
  }
} finally {
  // 授权按当前版本减去本轮临时成员，保留用户并发新增的成员。员工停用但历史身份继续可追溯。
  const owned = ac.owned.users.map(user => String(user.id))
  for (const entryId of ['finance', 'engineering']) {
    try {
      const policy = await api(`/policy?applicationId=${state.applicationId}&entryId=${entryId}`)
      const members = policy.members.filter(m => m.principalKind !== 'USER' || !owned.includes(String(m.principalId)))
      if (members.length !== policy.members.length)
        await api('/policy', {
          applicationId: state.applicationId,
          entryId,
          expectedRevision: policy.revision,
          enabled: policy.enabled,
          members
        })
    } catch (error) {
      errors.push('回收临时入口授权失败：' + error.message)
    }
  }
  for (const user of ac.owned.users) {
    try {
      await ac.api('/system/user/update-status', { id: user.id, status: 1 }, undefined, 'PUT')
    } catch (error) {
      errors.push('停用临时员工失败：' + error.message)
    }
  }
  try {
    const roleUsers = await ac.api('/system/permission/list-role-users?roleId=' + state.employeeRoleId)
    await ac.api('/system/permission/assign-role-users', {
      roleId: state.employeeRoleId,
      userIds: roleUsers.filter(id => !owned.includes(String(id)))
    })
  } catch (error) {
    errors.push('回收临时菜单角色失败：' + error.message)
  }
  await browser?.close()
  await ac.persist()
  ac.tokens = {}
  ac.passwords = {}
  const report = {
    time: new Date().toISOString(),
    applicationId: state.applicationId,
    checks,
    errors,
    records,
    failure: failure?.message
  }
  await writeFile(resolve(out, 'result.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify({ output: out, checks: checks.length, errors, failure: failure?.message }))
}
if (failure) throw failure
assert.deepEqual(errors, [])
