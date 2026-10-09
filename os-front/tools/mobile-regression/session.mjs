import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

export const origin = process.env.MOBILE_URL || 'http://127.0.0.1:5173'
export const output = resolve(process.env.MOBILE_OUTPUT || '.work/mobile-20261005')
export const flattenMenus = (items, parentId = 0) =>
  items.flatMap((m, index) =>
    m.visible === false
      ? []
      : [
          { ...m, children: undefined, parentId: m.parentId ?? parentId, sort: m.sort ?? index },
          ...flattenMenus(m.children || [], m.id)
        ]
  )
export async function session() {
  await mkdir(output, { recursive: true })
  const ac = new FormAcceptance(process.env.MOBILE_API || 'http://127.0.0.1:8080/api', output)
  await ac.login()
  const info = await ac.api('/system/auth/get-permission-info')
  const menus = flattenMenus(info.menus || [])
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext({
    viewport: { width: Number(process.env.MOBILE_WIDTH || 390), height: Number(process.env.MOBILE_HEIGHT || 844) },
    isMobile: true,
    hasTouch: true
  })
  await context.addInitScript(
    ({ token, info, menus }) => {
      if (localStorage.getItem('mobile-acceptance-session')) return
      localStorage.setItem('token', token)
      localStorage.setItem('userInfo', JSON.stringify(info.user))
      localStorage.setItem('permissions', JSON.stringify(info.permissions || []))
      localStorage.setItem('roles', JSON.stringify(info.roles || []))
      localStorage.setItem('menus', JSON.stringify(menus))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
      localStorage.setItem('mobile-acceptance-session', '1')
    },
    { token: ac.tokens.admin, info, menus }
  )
  return { ac, info, menus, browser, context }
}
export async function settle(page) {
  await page
    .locator('.ant-spin-spinning')
    .first()
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {})
  await page.waitForTimeout(350)
}
export async function geometry(page) {
  return page.evaluate(() => {
    const width = innerWidth
    const content = document.querySelector('.content-scroll')
    const rect = content?.getBoundingClientRect()
    const wide = [
      ...document.querySelectorAll(
        '.content-wrapper-dual > *, .content-wrapper-dual > * > *, .ant-modal, .ant-drawer-content-wrapper'
      )
    ]
      .filter(el => {
        const r = el.getBoundingClientRect()
        return (
          r.width && r.height && getComputedStyle(el).visibility !== 'hidden' && (r.right > width + 2 || r.left < -2)
        )
      })
      .map(el => ({ tag: el.tagName, class: el.className, width: Math.round(el.getBoundingClientRect().width) }))
    return {
      viewport: width,
      documentWidth: document.documentElement.scrollWidth,
      contentWidth: Math.round(rect?.width || 0),
      contentScrollWidth: content?.scrollWidth,
      wide
    }
  })
}
export const save = (file, value) => writeFile(resolve(output, file), JSON.stringify(value, null, 2))
