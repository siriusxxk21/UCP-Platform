import { test as base, expect, type Page } from '@playwright/test'
import { LoginPage } from '../pages/LoginPage'
import { injectAuth } from '../utils/auth'

/**
 * 自定义 Fixtures
 *
 * 扩展 base test，提供：
 *  - loginPage: 已绑定页面的登录页对象（供登录流程测试使用）
 *  - authedPage: 已完成登录的页面对象
 *
 * 登录态复用机制：
 *  - auth.setup 项目先登录一次，将登录态写入 tests/.auth/user.json
 *  - authedPage 通过 utils/auth.injectAuth 在页面加载前手动注入登录态，
 *    确保 token 等登录态真实生效，避免业务测试重复登录。
 */

export interface AuthFixtures {
  loginPage: LoginPage
  authedPage: Page
}

export const test = base.extend<AuthFixtures>({
  loginPage: async ({ page }, use) => {
    const loginPage = new LoginPage(page)
    await use(loginPage)
  },

  authedPage: async ({ page }, use) => {
    // 在页面加载前注入登录态（localStorage + sessionStorage）
    await injectAuth(page)
    await use(page)
  }
})

// 导出常用的 expect 与类型，方便测试文件统一引用
export { expect }
export type { Page }
