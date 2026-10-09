import type { Page } from '@playwright/test'
import fs from 'fs'
import path from 'path'

/**
 * 认证工具
 *
 * 提供读取 auth.setup.ts 保存的登录态（tests/.auth/user.json），
 * 并在页面加载前注入 localStorage 的方法。
 *
 * 说明：Playwright 的 storageState 对远程 origin 的 localStorage 注入不可靠
 * （实测复用后 localStorage 为空），因此改为手动读取 user.json 并注入，
 * 确保 token 等登录态真实生效。
 */
const AUTH_FILE = './tests/.auth/user.json'

/** 从 auth.setup 保存的 user.json 读取登录态（localStorage 键值对） */
export function loadAuthLocalStorage(): Array<{ name: string; value: string }> {
  const authFile = path.resolve(process.cwd(), AUTH_FILE)
  try {
    const auth = JSON.parse(fs.readFileSync(authFile, 'utf-8'))
    return auth.origins?.[0]?.localStorage ?? []
  } catch {
    return []
  }
}

/**
 * 在页面加载前注入登录态（localStorage + sessionStorage）。
 * 适用于测试内新建页面或 beforeAll / afterAll 中需要复用登录态的场景。
 */
export async function injectAuth(page: Page): Promise<void> {
  const localData = loadAuthLocalStorage()
  await page.addInitScript((entries) => {
    entries.forEach((e: { name: string; value: string }) => localStorage.setItem(e.name, e.value))
    // sessionStorage 记录活动时间，防止 isSessionIdle() 误判超时
    sessionStorage.setItem('lastActivityAt', String(Date.now()))
  }, localData)
}
