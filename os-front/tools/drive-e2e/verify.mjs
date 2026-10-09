/** 当前开发环境网盘回归：每次创建独立空间、最小网盘角色与账号，finally 仅清理本次编号。 */
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { chromium, expect } from '@playwright/test'

const base = process.env.DRIVE_TEST_API || 'http://127.0.0.1:8080/api'
const origin = process.env.DRIVE_TEST_URL || 'http://127.0.0.1:5173'
const recovery = process.env.DRIVE_CLEANUP_MANIFEST
  ? JSON.parse(await readFile(process.env.DRIVE_CLEANUP_MANIFEST, 'utf8'))
  : null
const prefix = recovery?.prefix || 'drv' + randomUUID().replaceAll('-', '').slice(0, 16)
assert.match(prefix, /^drv[a-f0-9]{16}$/)
const output = '.work/drive-review/' + prefix
await mkdir(output, { recursive: true })
const env = Object.fromEntries(
  (await readFile('.env.test', 'utf8'))
    .split(/\r?\n/)
    .filter(line => /^(E2E_USERNAME|E2E_PASSWORD)=/.test(line))
    .map(line => {
      const i = line.indexOf('=')
      return [
        line.slice(0, i),
        line
          .slice(i + 1)
          .trim()
          .replace(/^(['"])(.*)\1$/, '$2')
      ]
    })
)
let token, viewerToken, roleId, userId, spaceId, browser, page
const checks = [],
  cleanupErrors = [],
  pageErrors = []
async function request(path, body, method = body ? 'POST' : 'GET', session = token) {
  const form = body instanceof FormData
  return fetch(base + path, {
    method,
    headers: {
      ...(form ? {} : { 'Content-Type': 'application/json' }),
      ...(session ? { Authorization: `Bearer ${session}` } : {})
    },
    body: body == null ? undefined : form ? body : JSON.stringify(body)
  })
}
async function api(path, body, method, session) {
  const result = await (await request(path, body, method, session)).json()
  assert.equal(result.code, 0, `${path}: ${result.msg}`)
  return result.data
}
async function denied(path, body, method, session = viewerToken) {
  const result = await (await request(path, body, method, session)).json()
  assert.equal(result.code, 1010002001, `${path}: should reject by node permission`)
}
const folder = (name, parentId = '0') => api('/drive/entry/create-folder', { spaceId, parentId, name })
const list = (parentId, session = token) =>
  api(`/drive/entry/list?spaceId=${spaceId}&parentId=${parentId}`, undefined, 'GET', session)
const grant = (entryId, role) =>
  api('/drive/permission/save', { spaceId, entryId, subjectType: 'USER', subjectId: userId, role })
const payload = '网盘真实内容校验\n0123456789\n'
async function upload(parentId, name) {
  const form = new FormData()
  form.set('spaceId', spaceId)
  form.set('parentId', parentId)
  form.set('file', new Blob([payload], { type: 'text/plain' }), name)
  return api('/drive/entry/upload', form)
}

try {
  token = (await api('/system/auth/login', { username: env.E2E_USERNAME, password: env.E2E_PASSWORD })).accessToken
  delete env.E2E_PASSWORD
  delete env.E2E_USERNAME
  if (recovery) {
    assert.equal((await api('/system/user/get?id=' + recovery.userId)).username, prefix)
    assert.equal((await api('/system/role/get?id=' + recovery.roleId)).code, prefix)
    ;({ roleId, userId, spaceId } = recovery)
  } else {
    const info = await api('/system/auth/get-permission-info')
    roleId = await api('/system/role/create', {
      name: prefix,
      code: prefix,
      sort: 999,
      status: 0,
      remark: '网盘回归临时角色'
    })
    const menus = await api('/system/menu/list')
    const selected = menus.filter(menu => menu.permission?.startsWith('drive:')).map(menu => menu.id)
    assert.ok(selected.length > 10)
    await api('/system/permission/assign-role-menu', { roleId, menuIds: selected })
    const password = 'T9' + randomUUID().replaceAll('-', '').slice(0, 14)
    userId = await api('/system/user/create', {
      username: prefix,
      nickname: prefix,
      password,
      remark: '网盘回归临时账号'
    })
    await api('/system/permission/assign-user-role', { userId, roleIds: [roleId] })
    let login = await api('/system/auth/login', { username: prefix, password })
    if (login.loginStatus === 'PASSWORD_CHANGE_REQUIRED') {
      login = await api(
        '/system/auth/change-required-password',
        {
          passwordChangeToken: login.passwordChangeToken,
          newPassword: 'K8' + randomUUID().replaceAll('-', '').slice(0, 14)
        },
        'PUT'
      )
    }
    viewerToken = login.accessToken
    assert.ok(viewerToken)
    spaceId = await api('/drive/space/create', { name: prefix, quotaBytes: 0 })
    await writeFile(output + '/fixture.json', JSON.stringify({ prefix, roleId, userId, spaceId }, null, 2))
    await grant('0', 'VIEWER')

    const oldFile = await upload('0', '旧文件.txt')
    const source = await folder('来源目录')
    const destination = await folder('复制目标')
    const uploadDir = await folder('上传目录', source)
    await grant(destination, 'EDITOR')
    await api('/drive/entry/move', { id: oldFile.id, targetParentId: source }, 'PUT')
    const secret = await upload(source, '保密文件.txt')
    await api('/drive/entry/inherit', { id: secret.id, inheritParent: false }, 'PUT')
    assert.equal(
      (await list(source, viewerToken)).some(e => e.id === secret.id),
      false
    )
    await denied('/drive/entry/content?id=' + secret.id, undefined, 'GET')
    await denied('/drive/entry/copy', { id: source, targetSpaceId: spaceId, targetParentId: destination })
    assert.deepEqual(await list(destination), [])
    checks.push('普通账号看不到断开继承的文件；整树复制被拒且目标无半成品')

    await grant(secret.id, 'VIEWER')
    const copied = await api(
      '/drive/entry/copy',
      { id: source, targetSpaceId: spaceId, targetParentId: destination },
      'POST',
      viewerToken
    )
    const children = await list(copied, viewerToken)
    assert.deepEqual(children.map(e => e.name).sort(), ['上传目录', '保密文件.txt', '旧文件.txt'].sort())
    const copiedFile = children.find(e => e.name === '旧文件.txt')
    assert.equal(
      await (await request('/drive/entry/content?id=' + copiedFile.id, undefined, 'GET', viewerToken)).text(),
      payload
    )
    checks.push('旧文件移入新目录后整树复制完整，副本内容逐字一致')

    const managerGrant = await grant(source, 'MANAGER')
    const shareId = await api(
      '/drive/share/create',
      { spaceId, entryId: source, role: 'VIEWER', subjects: [{ subjectType: 'USER', subjectId: info.user.id }] },
      'POST',
      viewerToken
    )
    await api('/drive/permission/delete?id=' + managerGrant, undefined, 'DELETE')
    await denied('/drive/share/update', { id: shareId, role: 'EDITOR' }, 'PUT')
    await api('/drive/share/revoke?id=' + shareId, undefined, 'DELETE', viewerToken)
    checks.push('失去管理权的原分享者不能再扩权，仍可撤回自己的分享')

    await api('/drive/entry/trash', { ids: [copied] }, 'PUT')
    const unavailable = await (await request('/drive/entry/content?id=' + copiedFile.id)).json()
    assert.notEqual(unavailable.code, 0)
    await api('/drive/entry/restore?id=' + copied, undefined, 'PUT')
    assert.equal(await (await request('/drive/entry/content?id=' + copiedFile.id)).text(), payload)
    const range = await fetch(base + '/drive/entry/content?id=' + copiedFile.id, {
      headers: { Authorization: `Bearer ${token}`, Range: 'bytes=0-5' }
    })
    assert.equal(range.status, 206)
    assert.equal((await range.arrayBuffer()).byteLength, 6)
    checks.push('目录回收后内容不可读，恢复后内容一致，Range 下载返回 206 和正确字节数')

    browser = await chromium.launch({
      headless: true,
      channel: 'chrome',
      args: ['--disable-background-timer-throttling']
    })
    page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
    page.setDefaultTimeout(15000)
    page.on('pageerror', error => pageErrors.push(error.message))
    await page.addInitScript(
      ({ token, info }) => {
        localStorage.setItem('token', token)
        for (const [key, value] of Object.entries({
          userInfo: info.user,
          permissions: info.permissions,
          roles: info.roles,
          menus: info.menus
        }))
          localStorage.setItem(key, JSON.stringify(value))
        sessionStorage.setItem('lastActivityAt', String(Date.now()))
        document.addEventListener('DOMContentLoaded', () => {
          const style = document.createElement('style')
          style.textContent = '*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important}'
          document.head.appendChild(style)
        })
      },
      { token, info }
    )
    await page.goto(origin + '/drive/team?' + new URLSearchParams({ space: spaceId, path: '/来源目录/上传目录' }))
    await expect(page.locator('.drive-workspace h2')).toHaveText(prefix)
    await expect(page.locator('.vuefinder')).toBeVisible()
    // 初始深链会逐层解析并加载列表；等待它完成，避免尚未结束的列表加载关闭操作弹窗。
    await page.waitForLoadState('networkidle')
    await page.getByTitle('上传', { exact: true }).click()
    const uploadInput = page.locator('input[type="file"]:not([webkitdirectory])')
    await uploadInput.setInputFiles({ name: '页面上传.txt', mimeType: 'text/plain', buffer: Buffer.from(payload) })
    await expect(page.locator('.vuefinder__modal-layout__container')).toContainText('页面上传.txt')
    const [uploaded] = await Promise.all([
      page.waitForResponse(r => r.url().includes('/drive/entry/upload') && r.request().method() === 'POST'),
      page.getByRole('button', { name: '上传', exact: true }).click()
    ])
    assert.equal((await uploaded.json()).code, 0)
    await expect.poll(async () => (await list(uploadDir)).some(e => e.name === '页面上传.txt')).toBe(true)
    await expect(page.locator('.vuefinder').getByText('页面上传.txt', { exact: true })).toBeVisible()
    await page.waitForLoadState('networkidle')
    checks.push('浏览器直接进入二级目录后选择文件上传，真实文件落入正确目录并显示成功')

    // 另一会话删除当前目录；上传请求真实到达后端，不拦截或伪造响应。
    await page.getByTitle('上传', { exact: true }).click()
    await uploadInput.setInputFiles({ name: '应拒绝.txt', mimeType: 'text/plain', buffer: Buffer.from(payload) })
    await api('/drive/entry/trash', { ids: [uploadDir] }, 'PUT')
    const [failed] = await Promise.all([
      page.waitForResponse(r => r.url().includes('/drive/entry/upload') && r.request().method() === 'POST'),
      page.getByRole('button', { name: '上传', exact: true }).click()
    ])
    assert.notEqual((await failed.json()).code, 0)
    await expect(page.locator('.vuefinder__modal-layout__container')).toContainText('节点已在回收站中')
    await page.screenshot({ path: output + '/upload-rejected.png' })
    await page.getByRole('button', { name: '关闭', exact: true }).click()
    await api('/drive/entry/restore?id=' + uploadDir, undefined, 'PUT')
    checks.push('另一会话回收上传目标后，HTTP 200 业务拒绝在浏览器显示失败原因')

    await page.goto(origin + '/drive/team?' + new URLSearchParams({ space: spaceId, path: '/' }))
    await expect(page.locator('.vuefinder__explorer__item-name').filter({ hasText: /^来源目录$/ })).toBeVisible()
    await page.getByTitle('搜索文件', { exact: true }).click()
    await page.getByRole('checkbox', { name: '包含子文件夹' }).check()
    await page.getByPlaceholder('搜索文件', { exact: true }).fill('页面上传')
    await expect(page.locator('.vuefinder__modal-layout__container')).toContainText('页面上传.txt')
    await writeFile(output + '/search-ui.txt', await page.locator('.vuefinder__modal-layout__container').innerText())
    await page.screenshot({ path: output + '/workspace.png' })
    checks.push('浏览器从根目录搜索二级目录内刚上传的文件，显示真实结果')
    if (process.env.DRIVE_UI_REVIEW === '1') {
      const { reviewDriveUi } = await import('./review-ui.mjs')
      await reviewDriveUi({ page, api, spaceId, uploadDir, output, checks, origin, prefix, payload, upload })
    }
    assert.deepEqual(pageErrors, [])
  }
} catch (error) {
  if (page) {
    await writeFile(output + '/failure-ui.txt', await page.locator('body').innerText())
    await page.screenshot({ path: output + '/failure.png' })
  }
  throw error
} finally {
  if (browser) await browser.close()
  // 只依据本次生成的空间与账号编号清理；任一步失败都记录，不猜测或扫描其他业务数据。
  async function clean(label, action) {
    try {
      await action()
    } catch (error) {
      cleanupErrors.push(label + ': ' + error.message)
    }
  }
  if (spaceId)
    await clean('测试空间', async () => {
      assert.equal((await api('/drive/space/get?id=' + spaceId)).name, prefix)
      for (const entry of await list('0')) await api('/drive/entry/trash', { ids: [entry.id] }, 'PUT')
      for (const entry of await api('/drive/entry/trash-list?spaceId=' + spaceId))
        await api('/drive/entry/purge', { ids: [entry.id] }, 'DELETE')
      await api('/drive/space/delete?id=' + spaceId, undefined, 'DELETE')
    })
  if (viewerToken) await clean('测试会话', () => api('/system/auth/logout', {}, 'POST', viewerToken))
  if (userId) await clean('测试账号', () => api('/system/user/delete?id=' + userId, undefined, 'DELETE'))
  if (roleId) await clean('测试角色', () => api('/system/role/delete?id=' + roleId, undefined, 'DELETE'))
  if (token) await clean('验证会话', () => api('/system/auth/logout', {}, 'POST'))
  await writeFile(output + '/result.json', JSON.stringify({ prefix, checks, pageErrors, cleanupErrors }, null, 2))
  console.log(JSON.stringify({ output, checks, pageErrors, cleanupErrors }, null, 2))
  assert.deepEqual(cleanupErrors, [])
}
