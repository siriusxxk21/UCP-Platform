import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 真实开发 API 与浏览器：只修改本次独立夹具的对象授权，不触碰用户体验应用。
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
ac.output = resolve(process.env.NOCODE_VERIFY_OUTPUT || '.work/three-center-empty-fields', ac.prefix)
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
let browser, page, object, failure
const errors = []
try {
  await ac.login()
  object = await ac.object('empty_fields', '字段授权反馈 ' + ac.prefix, [ac.field('name', 'TEXT', '供应商名称')])
  const form = {
    id: 'form',
    kind: 'FORM',
    code: 'supplier_form',
    name: '供应商维护',
    config: {
      objectId: object.objectId,
      nodes: [{ id: 'name', type: 'FIELD', fieldId: object.ids.name, children: [], span: 12 }],
      detailIds: [],
      options: { layout: 'vertical', submitText: '保存供应商' }
    }
  }
  ac.app = await ac.api('/nocode/application/save', {
    id: null,
    expectedRevision: null,
    code: ac.prefix + '_empty',
    name: '字段授权反馈验收 ' + ac.prefix,
    definition: {
      objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
      resources: [
        form,
        {
          id: 'view',
          kind: 'VIEW',
          code: 'supplier_view',
          name: '供应商列表',
          config: {
            objectId: object.objectId,
            fieldIds: [object.ids.name],
            equal: {},
            pageSize: 10,
            formId: 'form'
          }
        },
        { id: 'menu', kind: 'MENU', code: 'supplier_menu', name: '供应商维护', config: { targetId: 'view' } }
      ]
    }
  })
  ac.owned.applications.push(ac.app.application.id)
  await ac.persist()
  await ac.share(object, ac.grant(object))
  await ac.api('/nocode/application/publish', {
    id: ac.app.application.id,
    expectedRevision: ac.app.application.revision,
    reason: '空字段权限反馈验收'
  })
  await ac.share(object, ac.grant(object, { actions: ['READ', 'CREATE'], readFields: [], writeFields: [] }))
  browser = await chromium.launch({ headless: true, channel: process.env.NOCODE_VERIFY_BROWSER || 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 } })
  page.setDefaultTimeout(20000)
  page.on('pageerror', error => errors.push(error.message))
  const info = await ac.api('/system/auth/get-permission-info')
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', '[]')
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  await ac.record('零字段授权有明确反馈、禁止空表单保存，服务端拒绝伪造字段写入', async () => {
    const model = await ac.api(
      `/nocode/runtime/model?applicationId=${ac.app.application.id}&objectId=${object.objectId}`
    )
    assert.deepEqual(model.permissions.readFields, [])
    assert.deepEqual(model.permissions.writeFields, [])
    assert.ok(model.permissions.actions.includes('CREATE'))
    await page.goto(`${origin}/nocode-app/runtime?id=${ac.app.application.id}`)
    await expect(page.getByText('当前列表没有可显示的业务字段', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: /新增$/ }).click()
    const modal = page.locator('.ant-modal-content:visible').filter({ hasText: '新建记录' })
    await expect(modal.getByText('暂无可填写字段，请联系管理员检查字段权限及表单配置。', { exact: true })).toBeVisible()
    await expect(modal.getByRole('button', { name: '保存供应商', exact: true })).toBeDisabled()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(ac.output, '01-empty-field-feedback.png'), fullPage: true })
    const denied = await ac.denied('/nocode/runtime/save', {
      ...ac.saveBody(object, { name: '不能写入' }),
      formId: 'form'
    })
    return { denied: denied.message }
  })
  await ac.record('恢复字段授权后表单可以填写，正常无记录不再误报字段不足', async () => {
    await ac.share(object, ac.grant(object))
    await page.reload()
    await expect(page.getByRole('button', { name: /新增$/ })).toBeVisible()
    await expect(page.getByRole('columnheader', { name: /^供应商名称/ })).toBeVisible()
    await expect(page.getByText('当前列表没有可显示的业务字段', { exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: /新增$/ }).click()
    const modal = page.locator('.ant-modal-content:visible').filter({ hasText: '新建记录' })
    await expect(modal.locator('.os-form-surface input')).toHaveCount(1)
    await expect(modal.getByRole('button', { name: '保存供应商', exact: true })).toBeEnabled()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.screenshot({ path: resolve(ac.output, '02-fields-restored.png'), fullPage: true })
    assert.equal((await ac.page(object)).total, 0)
    assert.deepEqual(errors, [])
    return { browserErrors: errors.length }
  })
} catch (error) {
  failure = error
  if (page) await page.screenshot({ path: resolve(ac.output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  if (object && ac.app) await ac.share(object, ac.grant(object)).catch(() => {})
  await ac.persist()
  await writeFile(resolve(ac.output, 'browser-errors.json'), JSON.stringify(errors, null, 2))
  await browser?.close()
  console.log(JSON.stringify({ prefix: ac.prefix, checks: ac.checks, output: ac.output }))
}
if (failure) throw failure
