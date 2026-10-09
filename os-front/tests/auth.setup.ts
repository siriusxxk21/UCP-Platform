import { test as setup, expect } from '@playwright/test'
import { LoginPage } from './pages/LoginPage'
import { getTestConfig } from './utils/config'

/**
 * 认证 Setup 项目
 *
 * 作用：仅登录一次，将登录态（localStorage 中的 token 等）保存到
 * tests/.auth/user.json，供后续所有业务测试通过 storageState 复用。
 *
 * 配合 playwright.config.ts：
 *  - auth-setup 项目先执行，业务项目（chromium）通过 storageState 复用登录态
 *  - 这样整个测试运行只需真正登录一次，大幅提升效率
 */

const AUTH_FILE = './tests/.auth/user.json'

setup('登录并保存认证状态', async ({ page }) => {
  const config = getTestConfig()
  const loginPage = new LoginPage(page)

  await loginPage.goto()
  await loginPage.login(config.username, config.password)

  // 等待跳转离开登录页，确认登录成功
  await loginPage.expectLoginSuccess()

  // 保存登录态（token、userInfo、menus 等 localStorage 数据）
  await page.context().storageState({ path: AUTH_FILE })
})
